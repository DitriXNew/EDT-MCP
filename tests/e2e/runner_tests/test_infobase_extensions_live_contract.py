"""Offline contracts for the attended standalone-server inventory test of infobase_extensions (#916)."""

import importlib.util
from pathlib import Path
import types
import unittest
from unittest import mock


E2E_DIR = Path(__file__).resolve().parents[1]
HARNESS_SPEC = importlib.util.spec_from_file_location(
    "edt_mcp_infobase_extensions_harness", E2E_DIR / "harness.py")
HARNESS = importlib.util.module_from_spec(HARNESS_SPEC)
HARNESS_SPEC.loader.exec_module(HARNESS)
TOOL_SPEC = importlib.util.spec_from_file_location(
    "edt_mcp_infobase_extensions_live", E2E_DIR / "tools" / "test_infobase_extensions.py")
LIVE = importlib.util.module_from_spec(TOOL_SPEC)
with mock.patch.dict("sys.modules", {"harness": HARNESS}):
    TOOL_SPEC.loader.exec_module(LIVE)

SERVER = {"id": "ServerApplication.Live", "type": LIVE.WST_SERVER_TYPE}


def _result(structured=None, is_error=False, text=""):
    return types.SimpleNamespace(structured=structured, is_error=is_error, text=text,
                                 error_text=lambda: text)


class FakeServer:
    """Answers the calls of the live test and records every one of them."""

    def __init__(self, state="STARTED", inventory=None, list_error=None, status_error=None):
        self.state = state
        self.inventory = inventory if inventory is not None else []
        self.list_error = list_error
        self.status_error = status_error
        self.calls = []

    def __call__(self, tool, arguments):
        self.calls.append((tool, dict(arguments)))
        if tool == "get_applications":
            return _result({"success": True, "applications": [SERVER]})
        if tool == "standalone_server":
            if self.status_error:
                return _result(None, True, self.status_error)
            return _result({"success": True, "state": self.state})
        if tool == "infobase_extensions":
            if self.list_error:
                return _result({"success": False, "error": self.list_error}, True, self.list_error)
            return _result({"success": True, "extensions": self.inventory})
        raise AssertionError("unexpected call %s" % tool)


class InfobaseExtensionsLiveContractTest(unittest.TestCase):
    def _run(self, server):
        with mock.patch.object(HARNESS, "LIVE_INFOBASE", True), \
                mock.patch.object(LIVE, "call", server), \
                mock.patch.object(LIVE, "assert_no_diff", lambda ctx="": None):
            LIVE.test_live_standalone_server_list_never_reports_the_active_flag_as_version()

    def test_a_stopped_server_is_neither_started_nor_stopped_and_the_test_skips(self):
        server = FakeServer(state="STOPPED", inventory=[{"name": "E", "active": True, "versionReported": False}])
        with self.assertRaises(HARNESS.E2ESkip) as skipped:
            self._run(server)
        self.assertIn("STOPPED", str(skipped.exception))
        actions = [a.get("action") for tool, a in server.calls if tool == "standalone_server"]
        self.assertEqual([None], actions, "only a status read is allowed: a confirmed stop leaves a hold")
        self.assertFalse(any(tool == "infobase_extensions" for tool, _ in server.calls))

    def test_a_failed_inventory_call_fails_instead_of_skipping(self):
        server = FakeServer(list_error="Connection refused")
        with self.assertRaises(AssertionError) as failed:
            self._run(server)
        self.assertNotIsInstance(failed.exception, HARNESS.E2ESkip)
        self.assertIn("Connection refused", str(failed.exception))

    def test_a_failed_status_call_fails_instead_of_skipping(self):
        server = FakeServer(status_error="status unavailable")
        with self.assertRaises(AssertionError) as failed:
            self._run(server)
        self.assertIn("status unavailable", str(failed.exception))

    def test_a_successful_empty_inventory_skips(self):
        with self.assertRaises(HARNESS.E2ESkip):
            self._run(FakeServer(inventory=[]))

    def test_the_swapped_flag_as_version_fails_and_the_declared_version_passes(self):
        with self.assertRaises(AssertionError) as failed:
            self._run(FakeServer(inventory=[{"name": "E", "active": False, "version": "true"}]))
        self.assertIn("#916", str(failed.exception))
        self._run(FakeServer(inventory=[{"name": "E", "active": True, "versionReported": False},
                                        {"name": "F", "active": False, "version": "25.12"}]))


if __name__ == "__main__":
    unittest.main()
