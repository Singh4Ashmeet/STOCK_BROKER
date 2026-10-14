#!/usr/bin/env python3
"""Local development launcher for the Stock Broker workspace.

The project is intentionally polyglot.  This script keeps its setup and
runtime commands in one place:

    python main.py install       # install project-managed dependencies
    python main.py start         # start the native API and web UI
    python main.py status        # show managed process status
    python main.py stop          # stop managed processes

It uses only Python's standard library so it remains runnable before Python
project dependencies have been installed. Node.js, Java 21, Rust, PostgreSQL,
and Redis must be installed by the developer because they are system runtimes,
not Python package dependencies.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import signal
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


ROOT = Path(__file__).resolve().parent
RUNTIME_DIR = ROOT / ".stockbroker"
PID_FILE = RUNTIME_DIR / "processes.json"
LOG_DIR = RUNTIME_DIR / "logs"
IS_WINDOWS = os.name == "nt"
MAVEN_WRAPPER = ROOT / "java" / ("mvnw.cmd" if IS_WINDOWS else "mvnw")
VENV_DIR = ROOT / ".venv"
VENV_PYTHON = VENV_DIR / ("Scripts/python.exe" if IS_WINDOWS else "bin/python")


@dataclass(frozen=True)
class Service:
    """A foreground development process owned by this launcher."""

    name: str
    command: list[str]
    cwd: Path
    url: str


def executable(name: str) -> str:
    """Return an executable path or stop with a useful installation message."""
    path = shutil.which(name)
    if not path:
        raise RuntimeError(f"Required runtime '{name}' was not found on PATH.")
    return path


def native_command(command: Sequence[str]) -> list[str]:
    """Resolve command shims such as npm.cmd before spawning a process."""
    if not command:
        raise RuntimeError("Cannot run an empty command.")
    executable_path = shutil.which(command[0]) or command[0]
    return [executable_path, *command[1:]]


def run(command: Sequence[str], *, cwd: Path = ROOT, check: bool = True) -> subprocess.CompletedProcess[str]:
    native = native_command(command)
    print("+", subprocess.list2cmdline(native))
    return subprocess.run(native, cwd=cwd, check=check, text=True)


def ensure_environment() -> None:
    if not (ROOT / ".env").exists():
        raise RuntimeError(
            "Missing .env. Copy .env.example to .env and set local development secrets, "
            "or run the repository bootstrap script from a Bash-compatible shell first."
        )


def install_dependencies(_: argparse.Namespace) -> None:
    """Install dependencies tracked by the repository, without installing OS tools."""
    executable("node")
    executable("npm")
    executable("cargo")
    executable("python")
    if not MAVEN_WRAPPER.exists():
        raise RuntimeError(f"Maven wrapper not found: {MAVEN_WRAPPER}")

    print("Installing frontend packages from frontend/package-lock.json …")
    run(["npm", "ci"], cwd=ROOT / "frontend")

    print("Resolving Java/Maven dependencies through the committed wrapper …")
    run([str(MAVEN_WRAPPER), "dependency:go-offline", "-B"], cwd=ROOT / "java")

    print("Fetching Rust workspace dependencies …")
    run(["cargo", "fetch"], cwd=ROOT / "rust")

    print("Creating an isolated Python environment …")
    if not VENV_PYTHON.exists():
        run([sys.executable, "-m", "venv", str(VENV_DIR)])
    print("Installing Python research package and test dependencies …")
    run([str(VENV_PYTHON), "-m", "pip", "install", "--upgrade", "pip"])
    run([str(VENV_PYTHON), "-m", "pip", "install", "-e", ".[test]"], cwd=ROOT / "python")
    print("\nDependency installation complete.")


def managed_services(include_feed: bool) -> list[Service]:
    services = [
        Service(
            "api",
            [str(MAVEN_WRAPPER), "spring-boot:run", "-Dspring-boot.run.profiles=dev"],
            ROOT / "java",
            "http://127.0.0.1:8081/api/actuator/health",
        ),
        Service(
            "web",
            ["npm", "run", "dev", "--", "--hostname", "127.0.0.1", "--port", "3000"],
            ROOT / "frontend",
            "http://127.0.0.1:3000",
        ),
    ]
    if include_feed:
        services.insert(
            0,
            Service(
                "feed",
                ["cargo", "run", "-p", "sb-server", "--", "server/server.toml"],
                ROOT / "rust",
                "gRPC [::1]:50051; HTTP http://[::1]:9090/health",
            ),
        )
    return services


def read_processes() -> dict[str, int]:
    if not PID_FILE.exists():
        return {}
    try:
        data = json.loads(PID_FILE.read_text(encoding="utf-8"))
        return {name: int(pid) for name, pid in data.items()}
    except (OSError, ValueError, TypeError):
        return {}


def is_running(pid: int) -> bool:
    if pid <= 0:
        return False
    if IS_WINDOWS:
        result = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True, check=False
        )
        return str(pid) in result.stdout
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    return True


def start_services(args: argparse.Namespace) -> None:
    ensure_environment()
    executable("node")
    executable("npm")
    if not MAVEN_WRAPPER.exists():
        raise RuntimeError(f"Maven wrapper not found: {MAVEN_WRAPPER}")
    if args.with_feed:
        executable("cargo")

    existing = {name: pid for name, pid in read_processes().items() if is_running(pid)}
    if existing:
        names = ", ".join(f"{name} (PID {pid})" for name, pid in existing.items())
        raise RuntimeError(f"Managed services already running: {names}. Run 'python main.py stop' first.")

    RUNTIME_DIR.mkdir(exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    processes: dict[str, int] = {}
    try:
        for service in managed_services(args.with_feed):
            log_file = (LOG_DIR / f"{service.name}.log").open("a", encoding="utf-8")
            print(f"Starting {service.name}; logs: {log_file.name}")
            process = subprocess.Popen(
                native_command(service.command),
                cwd=service.cwd,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                start_new_session=not IS_WINDOWS,
                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if IS_WINDOWS else 0,
            )
            processes[service.name] = process.pid
        PID_FILE.write_text(json.dumps(processes, indent=2), encoding="utf-8")
    except Exception:
        stop_services(argparse.Namespace())
        raise

    print("\nStarted local development stack:")
    for service in managed_services(args.with_feed):
        print(f"  {service.name:5} {service.url}")
    print("\nUse 'python main.py status' for process status and 'python main.py stop' when finished.")


def stop_process(pid: int) -> None:
    if not is_running(pid):
        return
    if IS_WINDOWS:
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], check=False, capture_output=True)
    else:
        os.killpg(pid, signal.SIGTERM)


def stop_services(args: argparse.Namespace) -> None:
    processes = read_processes()
    for name, pid in processes.items():
        print(f"Stopping {name} (PID {pid}) …")
        stop_process(pid)
    PID_FILE.unlink(missing_ok=True)
def status(_: argparse.Namespace) -> None:
    processes = read_processes()
    if not processes:
        print("No application processes are managed by main.py.")
    else:
        for name, pid in processes.items():
            print(f"{name:5} {'running' if is_running(pid) else 'stopped'} (PID {pid})")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the Stock Broker local development stack.")
    subcommands = parser.add_subparsers(dest="command", required=True)
    subcommands.add_parser("install", help="Install frontend, Java, Rust, and Python project dependencies.")
    start = subcommands.add_parser("start", help="Start the Java API and Next.js web UI natively.")
    start.add_argument("--with-feed", action="store_true", help="Also start the Rust market-data gRPC server.")
    subcommands.add_parser("status", help="Show managed service status.")
    stop = subcommands.add_parser("stop", help="Stop managed services.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "install":
            install_dependencies(args)
        elif args.command == "start":
            start_services(args)
        elif args.command == "status":
            status(args)
        else:
            stop_services(args)
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
