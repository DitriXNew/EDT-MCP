"""Strict newline-delimited MCP client used by the live stdio transport check."""

import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import threading
import time


def user_directory():
    if os.name == "nt":
        return Path(os.environ["LOCALAPPDATA"]) / "EDT-MCP"
    base = os.environ.get("XDG_RUNTIME_DIR")
    if base:
        return Path(base) / "edt-mcp"
    return Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local/state"))) / "edt-mcp"


def _private(path, regular=False):
    path = Path(path)
    if not path.is_absolute() or ".." in path.parts:
        raise AssertionError("IPC discovery requires an absolute normalized path")
    for item in (path, *path.parents):
        info = item.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise AssertionError("IPC discovery must not traverse links or reparse points")
    info = path.stat()
    if regular and not stat.S_ISREG(info.st_mode):
        raise AssertionError("IPC discovery requires a regular file")
    if os.name != "nt" and (info.st_uid != os.getuid() or info.st_mode & 0o077):
        raise AssertionError("IPC discovery requires owner-only storage")
    return path


def discover(workspace):
    """Return only public launch metadata; never include the handshake secret in failures."""
    identity = str(Path(workspace).resolve(strict=True))
    if os.name == "nt":
        identity = identity.lower()
    key = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:24]
    root = _private(user_directory())
    directory = _private(root / "ipc")
    record = _private(directory / (key + ".json"), regular=True)
    try:
        endpoint = json.loads(record.read_text(encoding="utf-8"))
        reported = str(Path(endpoint["workspace"]).resolve(strict=True))
        if (reported.lower() if os.name == "nt" else reported) != identity:
            raise ValueError()
        jar = Path(endpoint["bootstrap"])
        if jar.parent.parent != root / "bootstrap" or jar.name != "edt-mcp-bootstrap.jar":
            raise ValueError()
        _private(root / "bootstrap")
        _private(jar.parent)
        _private(jar, regular=True)
        return jar, (endpoint["pid"], endpoint["startTime"], endpoint["generation"])
    except (KeyError, ValueError, TypeError, OSError):
        raise AssertionError("Missing or invalid public bootstrap discovery metadata") from None


class StdioClient:
    def __init__(self, command):
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE)
        self.condition = threading.Condition()
        self.write_lock = threading.Lock()
        self.responses = {}
        self.pending = set()
        self.failure = None
        self.notifications = []
        self.frames = 0
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.errors = threading.Thread(target=self._drain_errors, daemon=True)
        self.reader.start()
        self.errors.start()

    def _drain_errors(self):
        # Drain without echoing potentially sensitive launcher diagnostics into CI logs.
        while self.process.stderr.read(4096):
            pass

    def _read(self):
        try:
            for raw in self.process.stdout:
                if not raw.endswith(b"\n") or len(raw) > 16 * 1024 * 1024:
                    raise ValueError()
                frame = json.loads(raw.decode("utf-8", errors="strict"))
                if not isinstance(frame, dict) or frame.get("jsonrpc") != "2.0":
                    raise ValueError()
                with self.condition:
                    if "id" in frame:
                        identity = frame["id"]
                        if identity not in self.pending or identity in self.responses:
                            raise ValueError()
                        if ("result" in frame) == ("error" in frame):
                            raise ValueError()
                        self.responses[identity] = frame
                    elif isinstance(frame.get("method"), str):
                        self.notifications.append(frame["method"])
                    else:
                        raise ValueError()
                    self.frames += 1
                    self.condition.notify_all()
        except (ValueError, UnicodeError, TypeError):
            with self.condition:
                self.failure = "Bootstrap stdout contains an invalid UTF-8 JSON-RPC frame"
        finally:
            with self.condition:
                self.ended = True
                self.condition.notify_all()

    def send(self, method, params=None, identity=None):
        frame = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            frame["params"] = params
        if identity is not None:
            frame["id"] = identity
            with self.condition:
                if identity in self.pending:
                    raise AssertionError("Duplicate outstanding client request id")
                self.pending.add(identity)
        encoded = (json.dumps(frame, ensure_ascii=False) + "\n").encode("utf-8")
        with self.write_lock:
            self.process.stdin.write(encoded)
            self.process.stdin.flush()

    def request(self, method, params=None, identity=1, timeout=60):
        self.send(method, params, identity)
        deadline = time.monotonic() + timeout
        with self.condition:
            while identity not in self.responses:
                if self.failure or getattr(self, "ended", False):
                    raise AssertionError(self.failure or "Bootstrap exited before its response")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise AssertionError("Bootstrap request timed out: " + method)
                self.condition.wait(remaining)
            if self.failure:
                raise AssertionError(self.failure)
            self.pending.remove(identity)
            return self.responses.pop(identity)

    def initialize(self, capabilities=None):
        response = self.request("initialize", {"protocolVersion": "2025-11-25",
            "capabilities": capabilities or {}, "clientInfo": {"name": "stdio-e2e", "version": "1"}})
        if response.get("result", {}).get("protocolVersion") != "2025-11-25":
            raise AssertionError("Bootstrap initialize failed")
        self.send("notifications/initialized")
        return response

    def close(self):
        if self.process.stdin and not self.process.stdin.closed:
            self.process.stdin.close()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
            raise AssertionError("Bootstrap did not exit after stdin closed") from None
        finally:
            self.reader.join(timeout=5)
            self.errors.join(timeout=5)
            self.process.stdout.close()
            self.process.stderr.close()
        if self.failure:
            raise AssertionError(self.failure)
        if self.process.returncode != 0:
            raise AssertionError("Bootstrap exited unsuccessfully")
