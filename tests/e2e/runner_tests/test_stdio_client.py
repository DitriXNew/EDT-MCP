"""Runner controls; real EDT transport acceptance lives in stdio_transport.py."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("stdio_client", Path(__file__).parents[1] / "stdio_client.py")
C = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(C)


class StdioClientTest(unittest.TestCase):
    def test_cyrillic_and_identical_ids_are_isolated_and_eof_exits(self):
        script = (
            "import sys,json\n"
            "for raw in sys.stdin.buffer:\n"
            " q=json.loads(raw.decode('utf-8'))\n"
            " if 'id' in q:\n"
            "  r={'jsonrpc':'2.0','id':q['id'],'result':q['params']}\n"
            "  sys.stdout.buffer.write((json.dumps(r,ensure_ascii=False)+'\\n').encode('utf-8'));sys.stdout.buffer.flush()\n"
        )
        clients = [C.StdioClient([sys.executable, "-u", "-c", script]) for _ in range(2)]
        try:
            for client, value in zip(clients, ("Справочник.Ёж", "Вычисление")):
                self.assertEqual({"text": value}, client.request("echo", {"text": value}, identity="same-Ё")["result"])
        finally:
            for client in clients:
                client.close()

    def test_stdout_pollution_fails_closed(self):
        client = C.StdioClient([sys.executable, "-u", "-c", "import sys;print('launcher log');sys.stdin.buffer.read()"])
        try:
            with self.assertRaisesRegex(AssertionError, "invalid UTF-8 JSON-RPC"):
                client.request("ping", identity=1)
        finally:
            with self.assertRaisesRegex(AssertionError, "invalid UTF-8 JSON-RPC"):
                client.close()

    def test_discovery_returns_public_metadata_and_rejects_external_jar(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            workspace = root / "workspace"
            workspace.mkdir()
            ipc = root / "ipc"
            ipc.mkdir(mode=0o700)
            install = root / "bootstrap" / "install-hash"
            install.mkdir(parents=True, mode=0o700)
            install.parent.chmod(0o700)
            jar = install / "edt-mcp-bootstrap.jar"
            jar.write_bytes(b"unit fixture")
            jar.chmod(0o600)
            identity = str(workspace.resolve())
            if C.os.name == "nt":
                identity = identity.lower()
            key = C.hashlib.sha256(identity.encode("utf-8")).hexdigest()[:24]
            record = ipc / (key + ".json")
            endpoint = {"workspace": str(workspace), "bootstrap": str(jar), "secret": "never-report-me",
                        "pid": 123, "startTime": "start", "generation": "generation"}
            record.write_text(json.dumps(endpoint), encoding="utf-8")
            record.chmod(0o600)
            with patch.object(C, "user_directory", return_value=root):
                self.assertEqual((jar, (123, "start", "generation")), C.discover(workspace))
                endpoint["bootstrap"] = str(root / "foreign.jar")
                record.write_text(json.dumps(endpoint), encoding="utf-8")
                with self.assertRaises(AssertionError) as error:
                    C.discover(workspace)
                self.assertNotIn("never-report-me", str(error.exception))


if __name__ == "__main__":
    unittest.main()
