"""Pure runner-contract tests; no EDT server or fixture checkout required."""

import ast
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
    def test_a_located_workspace_needs_readable_current_or_rotated_log_content(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            with mock.patch.dict(os.environ, {"EDT_MCP_EDT_WORKSPACE": tmp}):
                with self.assertRaises(HARNESS.E2ESkip) as skipped:
                    RATCHET.test_run_adds_no_unbaselined_error_entries_to_the_edt_log()
                self.assertIn("workspace found", str(skipped.exception))
                self.assertIn("no readable EDT log content to inspect", str(skipped.exception))
                no_log_message = str(skipped.exception)

                backup = os.path.join(metadata, ".bak_1.log")
                with open(backup, "w", encoding="utf-8"):
                    pass
                with self.assertRaises(HARNESS.E2ESkip) as skipped:
                    RATCHET.test_run_adds_no_unbaselined_error_entries_to_the_edt_log()
                self.assertEqual(no_log_message, str(skipped.exception))

                with open(backup, "w", encoding="utf-8") as handle:
                    handle.write("one line\n")
                self.assertIsNone(
                    RATCHET.test_run_adds_no_unbaselined_error_entries_to_the_edt_log())

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
