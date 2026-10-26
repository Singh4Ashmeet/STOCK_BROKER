"""Local Stock Broker development supervisor.

Starts the Rust market feed, Python research API, Java product API, and Next.js
frontend together, then exposes a local-only service dashboard on port 8080.

Examples:
    python main.py start
    python main.py status
    python main.py stop
    python main.py test

The supervisor is intentionally local-only. It does not expose secrets or
proxy arbitrary requests; its dashboard only reports process state and fixed
health endpoints for the four managed services.
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import socket
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.error import URLError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent
STATE_DIR = ROOT / ".stockbroker"
LOG_DIR = STATE_DIR / "logs"
STATE_FILE = STATE_DIR / "supervisor.json"
SUPERVISOR_HOST = "127.0.0.1"
SUPERVISOR_PORT = 8080


@dataclass(frozen=True)
class ServiceSpec:
    name: str
    command: tuple[str, ...]
    cwd: Path
    port: int
    health_url: str | None
    env: dict[str, str] = field(default_factory=dict)


@dataclass
class ManagedProcess:
    spec: ServiceSpec
    process: subprocess.Popen[str]
    log_path: Path
    started_at: float


def load_dotenv() -> dict[str, str]:
    """Load the ignored root .env without printing or overwriting the shell."""

    values: dict[str, str] = {}
    dotenv = ROOT / ".env"
    if not dotenv.exists():
        return values
    for raw_line in dotenv.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key.isidentifier():
            values[key] = value
    return values


def command_available(command: tuple[str, ...]) -> bool:
    executable = command[0]
    if Path(executable).is_absolute():
        return Path(executable).exists()
    search_path = os.environ.get("PATH", "")
    candidates = [executable]
    if os.name == "nt" and not Path(executable).suffix:
        candidates.extend(f"{executable}{suffix}" for suffix in (".exe", ".cmd", ".bat"))
    return any((Path(directory) / candidate).exists() for directory in search_path.split(os.pathsep) for candidate in candidates)


def python_with_research_dependencies() -> str:
    candidates = [
        ROOT / ".venv" / ("Scripts" if os.name == "nt" else "bin") / ("python.exe" if os.name == "nt" else "python"),
        Path(sys.executable),
    ]
    for candidate in candidates:
        if not candidate.exists():
            continue
        check = subprocess.run(
            [str(candidate), "-c", "import fastapi, uvicorn, scipy"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if check.returncode == 0:
            return str(candidate)
    return str(candidates[-1])


def service_specs() -> list[ServiceSpec]:
    env = load_dotenv()
    merged = {**env, **os.environ}
    cargo = "cargo.exe" if os.name == "nt" else "cargo"
    npm = "npm.cmd" if os.name == "nt" else "npm"
    mvnw = str(ROOT / "java" / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
    python = python_with_research_dependencies()

    rust_env = {"RUST_LOG": merged.get("RUST_LOG", "sb_server=info")}
    private_key = merged.get("SB_JWT_PRIVATE_KEY_PATH")
    public_key = merged.get("SB_JWT_PUBLIC_KEY_PATH")
    if not private_key or not Path(private_key.removeprefix("file:")).is_absolute():
        private_key = f"file:{ROOT / 'infra' / 'secrets' / 'jwt_private.key'}"
    if not public_key or not Path(public_key.removeprefix("file:")).is_absolute():
        public_key = f"file:{ROOT / 'infra' / 'secrets' / 'jwt_public.key'}"
    java_env = {
        "SB_API_PORT": merged.get("SB_API_PORT", "8081"),
        # The Java default is 9000; the Rust TOML default is 50051.
        "SB_FEED_GRPC_PORT": merged.get("SB_FEED_GRPC_PORT", "50051"),
        # Resolve development keys from the repository root.  Relative
        # `file:../infra/...` values are interpreted against Spring's servlet
        # resource context when launched through the Maven plugin on Windows.
        "SB_JWT_PRIVATE_KEY_PATH": private_key,
        "SB_JWT_PUBLIC_KEY_PATH": public_key,
    }
    preferred_java = Path(r"C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot")
    if (preferred_java / "bin" / ("java.exe" if os.name == "nt" else "java")).exists():
        java_env["JAVA_HOME"] = str(preferred_java)
        java_env["PATH"] = str(preferred_java / "bin") + os.pathsep + merged.get("PATH", os.environ.get("PATH", ""))
    frontend_env = {
        "API_URL": merged.get("API_URL", "http://localhost:8081"),
        "NEXT_PUBLIC_API_URL": merged.get("NEXT_PUBLIC_API_URL", "http://localhost:8081"),
        "NEXT_PUBLIC_WS_URL": merged.get("NEXT_PUBLIC_WS_URL", "ws://localhost:8081"),
        "NEXT_PUBLIC_APP_URL": merged.get("NEXT_PUBLIC_APP_URL", "http://localhost:3000"),
    }
    java_command: tuple[str, ...]
    if os.name == "nt":
        # The workspace path contains no spaces; omitting nested quotes avoids
        # Python's Windows argument quoting turning them into literal backslashes.
        java_command = ("cmd.exe", "/d", "/c", f"call {mvnw} spring-boot:run -Dspring-boot.run.profiles=dev")
    else:
        java_command = (mvnw, "spring-boot:run", "-Dspring-boot.run.profiles=dev")
    return [
        ServiceSpec(
            "rust-feed",
            (cargo, "run", "--manifest-path", str(ROOT / "rust" / "server" / "Cargo.toml"), "--", str(ROOT / "rust" / "server" / "server.toml")),
            ROOT,
            50051,
            "http://[::1]:9090/health",
            rust_env,
        ),
        ServiceSpec(
            "python-research",
            (python, "-m", "uvicorn", "api:app", "--host", "127.0.0.1", "--port", "8000"),
            ROOT / "python" / "backtest",
            8000,
            "http://127.0.0.1:8000/health",
            {"PYTHONPATH": os.pathsep.join((str(ROOT / "python" / "src"), str(ROOT / "python" / "backtest")))},
        ),
        ServiceSpec(
            "java-api",
            java_command,
            ROOT / "java",
            8081,
            "http://127.0.0.1:8081/api/actuator/health",
            java_env,
        ),
        ServiceSpec(
            "next-frontend",
            (npm, "run", "dev", "--", "--hostname", "127.0.0.1", "--port", "3000"),
            ROOT / "frontend",
            3000,
            "http://127.0.0.1:3000/",
            frontend_env,
        ),
    ]


def port_open(port: int, host: str = "127.0.0.1") -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(0.5)
        return sock.connect_ex((host, port)) == 0


def probe(url: str | None) -> dict[str, Any]:
    if not url:
        return {"state": "tcp-only"}
    try:
        request = Request(url, headers={"User-Agent": "stockbroker-supervisor/1"})
        with urlopen(request, timeout=1.5) as response:
            return {"state": "healthy" if response.status < 400 else "unhealthy", "status": response.status}
    except URLError as exc:
        return {"state": "unreachable", "detail": str(exc.reason)[:160]}
    except OSError as exc:
        return {"state": "unreachable", "detail": str(exc)[:160]}


class Supervisor:
    def __init__(self) -> None:
        self.processes: dict[str, ManagedProcess] = {}
        self.external_services: set[str] = set()
        self.lock = threading.RLock()

    def start(self, selected: set[str] | None = None) -> None:
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        specs = [spec for spec in service_specs() if selected is None or spec.name in selected]
        for spec in specs:
            with self.lock:
                existing = self.processes.get(spec.name)
            if existing and existing.process.poll() is None:
                continue
            if port_open(spec.port):
                health = probe(spec.health_url)
                if health.get("state") == "healthy":
                    # Reuse an already-running local service instead of
                    # killing a process that this supervisor did not spawn.
                    with self.lock:
                        self.external_services.add(spec.name)
                    print(f"{spec.name}: using existing healthy service on port {spec.port}")
                    continue
                raise RuntimeError(f"{spec.name} cannot start: port {spec.port} is already in use")
            if not command_available(spec.command):
                raise RuntimeError(f"{spec.name} executable is unavailable: {spec.command[0]}")
            log_path = LOG_DIR / f"{spec.name}.log"
            log_file = log_path.open("a", encoding="utf-8", buffering=1)
            child_env = os.environ.copy()
            child_env.update(load_dotenv())
            child_env.update(spec.env)
            creationflags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
            process = subprocess.Popen(
                list(spec.command),
                cwd=spec.cwd,
                env=child_env,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                text=True,
                creationflags=creationflags,
            )
            # The child owns the file descriptor after Popen; closing it here
            # keeps the supervisor from preventing log rotation on Windows.
            log_file.close()
            with self.lock:
                self.processes[spec.name] = ManagedProcess(spec, process, log_path, time.time())
            self._write_state()

    def stop(self) -> None:
        with self.lock:
            managed = list(self.processes.values())
        for item in managed:
            process = item.process
            if process.poll() is not None:
                continue
            try:
                if os.name == "nt":
                    process.send_signal(signal.CTRL_BREAK_EVENT)
                else:
                    process.terminate()
                process.wait(timeout=8)
            except (subprocess.TimeoutExpired, OSError):
                process.kill()
                process.wait(timeout=3)
        with self.lock:
            self.processes.clear()
            self.external_services.clear()
        self._write_state()

    def snapshot(self) -> dict[str, Any]:
        services: list[dict[str, Any]] = []
        with self.lock:
            managed_by_name = dict(self.processes)
        for spec in service_specs():
            managed = managed_by_name.get(spec.name)
            return_code = managed.process.poll() if managed else None
            process_state = "running" if managed and return_code is None else "stopped"
            if spec.name in self.external_services:
                process_state = "external"
            if managed and return_code is not None:
                process_state = "exited"
            services.append({
                "name": spec.name,
                "port": spec.port,
                "url": f"http://127.0.0.1:{spec.port}",
                "process": process_state,
                "pid": managed.process.pid if managed else None,
                "return_code": return_code,
                "port_open": port_open(spec.port),
                "health": probe(spec.health_url),
                "log": str(managed.log_path.relative_to(ROOT)) if managed else None,
            })
        return {
            "supervisor": {"host": SUPERVISOR_HOST, "port": SUPERVISOR_PORT, "state_file": str(STATE_FILE.relative_to(ROOT))},
            "services": services,
            "all_processes_running": all(service["process"] in ("running", "external") for service in services),
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }

    def _write_state(self) -> None:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        STATE_FILE.write_text(json.dumps(self.snapshot(), indent=2) + "\n", encoding="utf-8")


def dashboard_html() -> str:
    return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Stock Broker Service Monitor</title><style>
:root{color-scheme:dark;font:14px system-ui;background:#171916;color:#f0eee7}body{max-width:1100px;margin:0 auto;padding:28px}h1{font-size:28px}p{color:#b9baaf}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:12px}.card{border:1px solid #383a32;background:#22241f;padding:16px}.name{font-weight:700;color:#c2a46d}.state{font-family:monospace;margin-top:12px}.healthy{color:#8ba58b}.bad{color:#c77c73}.muted{color:#92948a}code{color:#d0b782}button{background:#c2a46d;color:#171916;border:0;padding:9px 14px;font-weight:700;cursor:pointer}a{color:#d0b782}</style></head>
<body><p class="name">STOCK_BROKER // LOCAL SUPERVISOR</p><h1>Service health</h1><p>Process and fixed health-endpoint status for the local development stack. Refreshes every 3 seconds.</p><p><button onclick="load()">Refresh now</button> <span id="time" class="muted"></span></p><main id="services" class="grid"></main>
<script>async function load(){const r=await fetch('/api/status');const d=await r.json();document.getElementById('time').textContent=d.timestamp;document.getElementById('services').innerHTML=d.services.map(s=>{const h=s.health||{};const ok=h.state==='healthy'||(h.state==='tcp-only'&&s.port_open);return `<article class="card"><div class="name">${s.name}</div><div class="state ${ok?'healthy':'bad'}">${h.state} · ${s.process}</div><p>port <code>${s.port}</code> · pid <code>${s.pid??'—'}</code></p><p><a href="${s.url}" target="_blank" rel="noreferrer">open service</a>${s.log?`<br><span class="muted">log: ${s.log}</span>`:''}</p></article>`}).join('')}</script></body></html>"""


class DashboardHandler(BaseHTTPRequestHandler):
    supervisor: Supervisor

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/api/status":
            body = json.dumps(self.supervisor.snapshot()).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
        elif self.path in ("/", "/index.html"):
            body = dashboard_html().encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
        else:
            body = b"Not found"
            self.send_response(404)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args: Any) -> None:
        # Keep the monitor quiet; service output belongs in .stockbroker/logs.
        return


def serve_dashboard(supervisor: Supervisor) -> ThreadingHTTPServer:
    DashboardHandler.supervisor = supervisor
    server = ThreadingHTTPServer((SUPERVISOR_HOST, SUPERVISOR_PORT), DashboardHandler)
    thread = threading.Thread(target=server.serve_forever, name="stockbroker-dashboard", daemon=True)
    thread.start()
    return server


def run_start(args: argparse.Namespace) -> int:
    supervisor = Supervisor()
    selected = {name for name, enabled in (("rust-feed", not args.no_feed), ("python-research", not args.no_python), ("java-api", not args.no_java), ("next-frontend", not args.no_frontend)) if enabled}
    try:
        supervisor.start(selected)
    except RuntimeError as exc:
        print(f"start failed: {exc}", file=sys.stderr)
        supervisor.stop()
        return 1
    server = serve_dashboard(supervisor)
    print(f"Supervisor: http://{SUPERVISOR_HOST}:{SUPERVISOR_PORT}")
    print("Press Ctrl+C to stop all managed services.")
    try:
        while True:
            supervisor._write_state()
            time.sleep(3)
    except KeyboardInterrupt:
        print("\nStopping services…")
    finally:
        server.shutdown()
        supervisor.stop()
    return 0


def run_status() -> int:
    snapshot = Supervisor().snapshot()
    print(json.dumps(snapshot, indent=2))
    return 0 if all(service["health"]["state"] in ("healthy", "tcp-only") for service in snapshot["services"] if service["port_open"]) else 1


def run_tests() -> int:
    commands = [
        ("frontend", ("npm.cmd" if os.name == "nt" else "npm", "run", "type-check")),
        ("frontend", ("npm.cmd" if os.name == "nt" else "npm", "test")),
        ("frontend", ("npm.cmd" if os.name == "nt" else "npm", "run", "lint")),
        ("rust", ("cargo.exe" if os.name == "nt" else "cargo", "fmt", "--all", "--", "--check")),
    ]
    for cwd_name, command in commands:
        print(f"\n$ ({cwd_name}) {' '.join(command)}")
        result = subprocess.run(command, cwd=ROOT / cwd_name, env={**os.environ, **load_dotenv()})
        if result.returncode != 0:
            return result.returncode
    return 0


def run_install() -> int:
    venv_python = ROOT / ".venv" / ("Scripts" if os.name == "nt" else "bin") / ("python.exe" if os.name == "nt" else "python")
    python = str(venv_python if venv_python.exists() else Path(sys.executable))
    requirements = ROOT / "python" / "backtest" / "requirements.txt"
    print(f"Installing research API dependencies into {python}")
    return subprocess.run([python, "-m", "pip", "install", "-r", str(requirements)], cwd=ROOT).returncode


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Start and monitor the local Stock Broker stack.")
    sub = parser.add_subparsers(dest="command", required=True)
    start = sub.add_parser("start", help="start services and the dashboard on port 8080")
    start.add_argument("--with-feed", action="store_true", default=True, help="start the Rust market feed (default)")
    start.add_argument("--no-feed", action="store_true", help="skip the Rust market feed")
    start.add_argument("--no-python", action="store_true")
    start.add_argument("--no-java", action="store_true")
    start.add_argument("--no-frontend", action="store_true")
    sub.add_parser("status", help="print process and endpoint status")
    sub.add_parser("stop", help="stop services recorded by this process")
    sub.add_parser("test", help="run the fast local frontend and Rust checks")
    sub.add_parser("install", help="install the pinned Python research API dependencies into .venv")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.command == "start":
        return run_start(args)
    if args.command == "status":
        return run_status()
    if args.command == "test":
        return run_tests()
    if args.command == "install":
        return run_install()
    if args.command == "stop":
        print("stop must be issued from the running supervisor with Ctrl+C; no detached process registry is used")
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
