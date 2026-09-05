"""Pure runner-contract tests; no EDT server or fixture checkout required."""

import ast
import datetime
import importlib.util
import inspect
import os
import tempfile
import unittest
from unittest import mock


E2E_DIR = os.path.dirname(os.path.dirname(__file__))
RUN_ALL_PATH = os.path.join(E2E_DIR, "run_all.py")
SPEC = importlib.util.spec_from_file_location("edt_mcp_e2e_run_all", RUN_ALL_PATH)
RUN_ALL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN_ALL)

HARNESS_SPEC = importlib.util.spec_from_file_location(
    "edt_mcp_e2e_harness", os.path.join(E2E_DIR, "harness.py"))
HARNESS = importlib.util.module_from_spec(HARNESS_SPEC)
HARNESS_SPEC.loader.exec_module(HARNESS)
RATCHET_SPEC = importlib.util.spec_from_file_location(
    "edt_mcp_e2e_log_ratchet", os.path.join(E2E_DIR, "tools", "test_edt_log_ratchet.py"))
RATCHET = importlib.util.module_from_spec(RATCHET_SPEC)
with mock.patch.dict("sys.modules", {"harness": HARNESS}):
    RATCHET_SPEC.loader.exec_module(RATCHET)


class RunAllRatchetTest(unittest.TestCase):
    def test_a_located_workspace_must_contain_the_server_log_probe(self):
        def entry_at(plugin, severity, message, epoch=None):
            if epoch is None:
                epoch = HARNESS.RUN_STARTED_AT
            stamp = datetime.datetime.fromtimestamp(epoch).strftime("%Y-%m-%d %H:%M:%S")
            return "!ENTRY %s %s 0 %s.000\n!MESSAGE %s\n" % (
                plugin, severity, stamp, message)

        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            log_path = os.path.join(metadata, ".log")
            captured_calls = []
            write_probe = {"enabled": False}

            def fake_call(tool, arguments):
                captured_calls.append((tool, arguments))
                if write_probe["enabled"]:
                    token = arguments["project"]
                    with open(log_path, "a", encoding="utf-8") as handle:
                        handle.write(entry_at(
                            RATCHET.OUR_PLUGIN, "2",
                            "Failed tools/call: get_project_errors - Project not found: %s"
                            % token,
                            datetime.datetime.now().timestamp()))

            with mock.patch.object(RATCHET, "call", side_effect=fake_call), \
                    mock.patch.dict(os.environ, {"EDT_MCP_EDT_WORKSPACE": tmp}):
                with self.assertRaises(HARNESS.E2ESkip) as skipped:
                    RATCHET.test_run_adds_no_unbaselined_error_entries_to_the_edt_log()
                self.assertIn("does not carry this server's own log output",
                              str(skipped.exception))
                self.assertFalse(os.path.exists(log_path))

                with open(log_path, "w", encoding="utf-8") as handle:
                    handle.write(entry_at(
                        "org.eclipse.core.runtime", "1", "unrelated platform status"))
                with self.assertRaises(HARNESS.E2ESkip) as skipped:
                    RATCHET.test_run_adds_no_unbaselined_error_entries_to_the_edt_log()
                self.assertIn("does not carry this server's own log output",
                              str(skipped.exception))

                with open(log_path, "w", encoding="utf-8") as handle:
                    handle.write(entry_at(
                        RATCHET.OUR_PLUGIN, "2", "another server instance was here"))
                with self.assertRaises(HARNESS.E2ESkip) as skipped:
                    RATCHET.test_run_adds_no_unbaselined_error_entries_to_the_edt_log()
                self.assertIn("does not carry this server's own log output",
                              str(skipped.exception))

                with open(log_path, "w", encoding="utf-8"):
                    pass
                write_probe["enabled"] = True
                self.assertIsNone(
                    RATCHET.test_run_adds_no_unbaselined_error_entries_to_the_edt_log())
                probe_tool, probe_arguments = captured_calls[-1]
                self.assertEqual("get_project_errors", probe_tool)
                probe_token = probe_arguments["project"]
                self.assertTrue(probe_token.startswith("edtmcplogprobe"))
                with open(log_path, encoding="utf-8") as handle:
                    self.assertIn("Project not found: %s" % probe_token, handle.read())

                failure_message = "Runner probe gate found an unbaselined plugin error"
                with open(log_path, "w", encoding="utf-8") as handle:
                    handle.write(entry_at(RATCHET.OUR_PLUGIN, "4", failure_message))
                with self.assertRaises(HARNESS.E2EAssertion) as failed:
                    RATCHET.test_run_adds_no_unbaselined_error_entries_to_the_edt_log()
                self.assertIn(failure_message, str(failed.exception))
                probe_tool, probe_arguments = captured_calls[-1]
                self.assertEqual("get_project_errors", probe_tool)
                probe_token = probe_arguments["project"]
                with open(log_path, encoding="utf-8") as handle:
                    self.assertIn("Project not found: %s" % probe_token, handle.read())

    def test_every_shard_holds_its_own_log_ratchet_out_of_the_main_loop(self):
        first = {"tool": "alpha", "name": "first"}
        deferred = {"tool": "omega", "name": "last", "last": True}
        ratchet = {"tool": "_edt_log_ratchet", "name": "audit", "last": True}

        scheduled, held = RUN_ALL.schedule_tests([first, deferred],
                                                  [first, ratchet, deferred],
                                                  per_shard=True)

        self.assertEqual([first, deferred], scheduled)
        self.assertEqual([ratchet], held)
        self.assertNotIn(ratchet, scheduled)

    def test_post_cleanup_ratchet_failure_is_a_junit_testcase_failure(self):
        ratchet = {"tool": "_edt_log_ratchet", "name": "audit"}
        results = [(ratchet, "fail", "cleanup logged a new plugin ERROR", 0.25)]
        handle, path = tempfile.mkstemp(suffix=".xml")
        os.close(handle)
        self.addCleanup(lambda: os.path.exists(path) and os.remove(path))

        RUN_ALL.write_junit(results, path, final_clean=True)

        with open(path, encoding="utf-8") as stream:
            report = stream.read()
        self.assertIn('tests="1" failures="1"', report)
        self.assertIn('_edt_log_ratchet::audit', report)
        self.assertIn('cleanup logged a new plugin ERROR', report)

    def test_main_executes_log_ratchet_after_the_last_final_cleanup_call(self):
        tree = ast.parse(inspect.getsource(RUN_ALL.main))
        cleanup_lines = []
        ratchet_loop_line = None
        nfail_line = None
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) \
                    and node.func.attr == "final_cleanup":
                cleanup_lines.append(node.lineno)
            if isinstance(node, ast.For) and isinstance(node.iter, ast.Name) \
                    and node.iter.id == "log_ratchets":
                ratchet_loop_line = node.lineno
            if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name)
                    and target.id == "nfail" for target in node.targets):
                nfail_line = node.lineno

        self.assertTrue(cleanup_lines)
        self.assertIsNotNone(ratchet_loop_line)
        self.assertIsNotNone(nfail_line)
        self.assertLess(max(cleanup_lines), ratchet_loop_line)
        self.assertLess(ratchet_loop_line, nfail_line,
                        "ratchet result must be counted before the exit decision")


if __name__ == "__main__":
    unittest.main()
