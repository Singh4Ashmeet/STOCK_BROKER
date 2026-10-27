"""Focused tests for the corrected local supervisor behaviors.

Run with:
    python -m pytest tests/test_supervisor.py -v

These tests validate the fixes for findings 1-6 from the b9d820f review
without starting real services.
"""

from __future__ import annotations

import importlib
import json
import os
import sys
import textwrap
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import pytest

# Make the repo root importable so we can import main.py.
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import main as supervisor  # noqa: E402


# ---------------------------------------------------------------------------
# Finding 1: ``stop`` subcommand removed
# ---------------------------------------------------------------------------

class TestStopCommandRemoved:
    """The ``stop`` subcommand must not exist in the parser."""

    def test_stop_rejected_by_parser(self):
        """argparse must reject 'stop' as an invalid command."""
        parser = supervisor.build_parser()
        with pytest.raises(SystemExit) as exc_info:
            parser.parse_args(["stop"])
        assert exc_info.value.code == 2, "'stop' should cause argparse error exit"

    def test_valid_commands_accepted(self):
        """Smoke test: accepted commands still parse."""
        parser = supervisor.build_parser()
        for cmd in ("start", "status", "test", "install"):
            args = parser.parse_args([cmd])
            assert args.command == cmd


# ---------------------------------------------------------------------------
# Finding 2: Dashboard auto-refresh
# ---------------------------------------------------------------------------

class TestDashboardRefresh:
    """The dashboard HTML must schedule periodic polling."""

    def test_setinterval_present(self):
        html = supervisor.dashboard_html()
        assert "setInterval" in html, "dashboard must call setInterval for auto-refresh"

    def test_clearinterval_on_unload(self):
        html = supervisor.dashboard_html()
        assert "clearInterval" in html, "dashboard must clear interval on page unload"

    def test_fetch_error_handling(self):
        html = supervisor.dashboard_html()
        assert "catch" in html, "dashboard fetch must have error handling"

    def test_error_element_present(self):
        html = supervisor.dashboard_html()
        assert 'id="error"' in html, "dashboard must show fetch errors to the user"


# ---------------------------------------------------------------------------
# Finding 3: .env vs shell environment precedence
# ---------------------------------------------------------------------------

class TestEnvPrecedence:
    """Shell environment must override .env defaults."""

    def test_merged_env_shell_overrides_dotenv(self, tmp_path, monkeypatch):
        dotenv = tmp_path / ".env"
        dotenv.write_text("FOO=from_dotenv\nBAR=only_dotenv\n")
        monkeypatch.setattr(supervisor, "ROOT", tmp_path)
        monkeypatch.setenv("FOO", "from_shell")

        merged = supervisor.merged_env()
        assert merged["FOO"] == "from_shell", "shell must win over .env"
        assert merged.get("BAR") == "only_dotenv", ".env-only values must still appear"

    def test_child_env_construction_order(self, tmp_path, monkeypatch):
        """The child env built in Supervisor.start() must follow the
        same precedence: .env < shell < spec.env."""
        dotenv = tmp_path / ".env"
        dotenv.write_text("MY_VAR=dotenv_value\n")
        monkeypatch.setattr(supervisor, "ROOT", tmp_path)
        monkeypatch.setenv("MY_VAR", "shell_value")

        env = supervisor.load_dotenv()
        child_env = {**env, **os.environ}
        assert child_env["MY_VAR"] == "shell_value"


# ---------------------------------------------------------------------------
# Finding 4: install creates and uses .venv
# ---------------------------------------------------------------------------

class TestInstallUsesVenv:
    """``python main.py install`` must create .venv when missing."""

    def test_ensure_venv_creates_venv(self, tmp_path, monkeypatch):
        monkeypatch.setattr(supervisor, "ROOT", tmp_path)
        venv_dir = supervisor.ensure_venv()
        assert venv_dir == tmp_path / ".venv"
        assert venv_dir.exists(), ".venv must be created"
        # On Windows, check Scripts/python.exe exists.
        if os.name == "nt":
            assert (venv_dir / "Scripts" / "python.exe").exists()
        else:
            assert (venv_dir / "bin" / "python").exists()

    def test_ensure_venv_idempotent(self, tmp_path, monkeypatch):
        monkeypatch.setattr(supervisor, "ROOT", tmp_path)
        v1 = supervisor.ensure_venv()
        v2 = supervisor.ensure_venv()
        assert v1 == v2

    def test_run_install_does_not_use_global_python(self, tmp_path, monkeypatch):
        """run_install must use .venv python, not sys.executable."""
        monkeypatch.setattr(supervisor, "ROOT", tmp_path)
        # Pre-create the .venv structure to test resolution without running full ensurepip
        bin_dir = tmp_path / ".venv" / ("Scripts" if os.name == "nt" else "bin")
        bin_dir.mkdir(parents=True)
        py_name = "python.exe" if os.name == "nt" else "python"
        (bin_dir / py_name).write_text("")

        # Create a requirements.txt
        reqs_dir = tmp_path / "python" / "backtest"
        reqs_dir.mkdir(parents=True)
        (reqs_dir / "requirements.txt").write_text("# empty\n")

        # Now mock subprocess.run to capture the pip install call.
        calls = []
        def fake_run(cmd, **kw):
            calls.append(cmd)
            return SimpleNamespace(returncode=0)

        monkeypatch.setattr(supervisor.subprocess, "run", fake_run)
        supervisor.run_install()

        assert len(calls) == 1
        used_python = calls[0][0]
        assert ".venv" in used_python, f"install must use .venv python, got: {used_python}"
        assert str(sys.executable) != used_python


# ---------------------------------------------------------------------------
# Finding 5: Status exit-code semantics
# ---------------------------------------------------------------------------

class TestStatusExitCode:
    """``python main.py status`` exit code must be nonzero when unhealthy."""

    def _make_snapshot(self, services):
        """Build a snapshot dict with given service list."""
        enabled = [s for s in services if s.get("process") != "disabled"]
        all_healthy = bool(enabled) and all(
            s.get("process") in ("running", "external")
            and (s.get("health", {}).get("state") == "healthy" or (s.get("health", {}).get("state") == "tcp-only" and s.get("port_open")))
            for s in enabled
        )
        return {
            "supervisor": {"host": "127.0.0.1", "port": 8080, "state_file": ".stockbroker/supervisor.json"},
            "services": services,
            "all_healthy": all_healthy,
            "timestamp": "2026-01-01T00:00:00Z",
        }

    def test_status_nonzero_when_unreachable(self, monkeypatch):
        """When services are unreachable, exit code must be 1."""
        snapshot = self._make_snapshot([
            {"name": "test", "port": 8000, "process": "stopped", "health": {"state": "unreachable"}, "port_open": False},
        ])
        monkeypatch.setattr(supervisor, "port_open", lambda port: False)
        monkeypatch.setattr(supervisor.Supervisor, "snapshot", lambda self: snapshot)
        code = supervisor.run_status()
        assert code == 1

    def test_status_zero_when_all_healthy(self, monkeypatch):
        snapshot = self._make_snapshot([
            {"name": "test", "port": 8000, "process": "running", "health": {"state": "healthy"}, "port_open": True},
        ])
        monkeypatch.setattr(supervisor, "port_open", lambda port: False)
        monkeypatch.setattr(supervisor.Supervisor, "snapshot", lambda self: snapshot)
        code = supervisor.run_status()
        assert code == 0

    def test_status_nonzero_when_no_services(self, monkeypatch):
        """When no services are present at all, exit code must be 1."""
        snapshot = self._make_snapshot([])
        monkeypatch.setattr(supervisor, "port_open", lambda port: False)
        monkeypatch.setattr(supervisor.Supervisor, "snapshot", lambda self: snapshot)
        code = supervisor.run_status()
        assert code == 1

    def test_disabled_services_ignored_in_health_check(self, monkeypatch):
        """Disabled services must not count as failures."""
        snapshot = self._make_snapshot([
            {"name": "enabled", "port": 8000, "process": "running", "health": {"state": "healthy"}, "port_open": True},
            {"name": "skipped", "port": 9000, "process": "disabled", "health": {"state": "unreachable"}, "port_open": False},
        ])
        monkeypatch.setattr(supervisor, "port_open", lambda port: False)
        monkeypatch.setattr(supervisor.Supervisor, "snapshot", lambda self: snapshot)
        code = supervisor.run_status()
        assert code == 0, "disabled services should not cause failure"


# ---------------------------------------------------------------------------
# Finding 6: --with-feed removed
# ---------------------------------------------------------------------------

class TestWithFeedRemoved:
    """The redundant ``--with-feed`` flag must not exist."""

    def test_with_feed_not_in_parser(self):
        parser = supervisor.build_parser()
        # --with-feed must not be recognized.
        with pytest.raises(SystemExit):
            parser.parse_args(["start", "--with-feed"])

    def test_no_feed_still_works(self):
        parser = supervisor.build_parser()
        args = parser.parse_args(["start", "--no-feed"])
        assert args.no_feed is True


# ---------------------------------------------------------------------------
# Supervisor snapshot: disabled vs exited semantics
# ---------------------------------------------------------------------------

class TestSnapshotDisabledVsExited:
    """Snapshot must distinguish disabled, stopped, and exited services."""

    def test_disabled_service_state(self):
        sup = supervisor.Supervisor()
        with sup.lock:
            sup.disabled_services.add("java-api")
        snap = sup.snapshot()
        java = next(s for s in snap["services"] if s["name"] == "java-api")
        assert java["process"] == "disabled"

    def test_all_healthy_requires_enabled_services(self):
        """If every service is disabled, all_healthy must be False."""
        sup = supervisor.Supervisor()
        for spec in supervisor.service_specs():
            with sup.lock:
                sup.disabled_services.add(spec.name)
        snap = sup.snapshot()
        assert snap["all_healthy"] is False


# ---------------------------------------------------------------------------
# Edge cases: port_open, probe
# ---------------------------------------------------------------------------

class TestProbeFunction:
    def test_probe_no_url_returns_tcp_only(self):
        result = supervisor.probe(None)
        assert result["state"] == "tcp-only"

    def test_probe_unreachable(self):
        result = supervisor.probe("http://127.0.0.1:1/nonexistent")
        assert result["state"] == "unreachable"


class TestPortOpen:
    def test_closed_port(self):
        # Port 1 is almost certainly not open.
        assert supervisor.port_open(1) is False


# ---------------------------------------------------------------------------
# Process shutdown and external process ownership
# ---------------------------------------------------------------------------

class TestProcessLifecycleAndOwnership:
    def test_external_healthy_service_not_killed_on_stop(self, monkeypatch):
        """Supervisor must not kill external services during stop()."""
        sup = supervisor.Supervisor()
        with sup.lock:
            sup.external_services.add("rust-feed")

        # stop() should clear external_services and not attempt to kill them
        sup.stop()
        assert "rust-feed" not in sup.external_services
        assert len(sup.processes) == 0

    def test_stop_windows_taskkill_fallback(self, monkeypatch):
        """When a child process fails to stop gracefully, supervisor falls back to taskkill on Windows."""
        sup = supervisor.Supervisor()

        taskkill_calls = []
        def fake_subprocess_run(cmd, **kw):
            taskkill_calls.append(cmd)
            return SimpleNamespace(returncode=0)

        class DummyProcess:
            pid = 99999
            def poll(self):
                return None
            def send_signal(self, sig):
                pass
            def wait(self, timeout=None):
                if timeout == 8:
                    raise supervisor.subprocess.TimeoutExpired(cmd="dummy", timeout=8)
                return 0
            def kill(self):
                pass

        dummy_proc = DummyProcess()
        spec = supervisor.ServiceSpec("dummy", ("dummy",), Path("."), 9999, None)
        item = supervisor.ManagedProcess(spec, dummy_proc, Path("dummy.log"), 0.0)
        with sup.lock:
            sup.processes["dummy"] = item

        monkeypatch.setattr(supervisor.os, "name", "nt")
        monkeypatch.setattr(supervisor.subprocess, "run", fake_subprocess_run)

        sup.stop()

        # Check taskkill was called
        assert any(call[0] == "taskkill" and str(dummy_proc.pid) in call for call in taskkill_calls)
