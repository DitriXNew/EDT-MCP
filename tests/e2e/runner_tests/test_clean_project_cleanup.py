"""Offline checks for failure preservation in the disposable-extension e2e scenario."""

import ast
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock


SCENARIO = "test_clean_names_a_dependent_the_platform_could_not_restart_and_brings_the_siblings_back"
SOURCE = Path(__file__).resolve().parents[1] / "tools" / "test_clean_project.py"


class CleanProjectCleanupTest(unittest.TestCase):
    def scenario(self, create_failure, cleanup_failure, created=False):
        tree = ast.parse(SOURCE.read_text(encoding="utf-8"))
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == SCENARIO)
        function.decorator_list = []
        create_result = SimpleNamespace(failure=None if created else create_failure)
        delete_result = SimpleNamespace(failure=cleanup_failure, structured={"state": "releasing"})
        calls = []

        def call(tool, params):
            calls.append(tool)
            if tool == "create_project":
                return create_result
            if tool == "delete_project":
                if len(calls) > 1 and isinstance(cleanup_failure, RuntimeError):
                    raise cleanup_failure
                return delete_result
            # Once creation succeeds, fail before touching any real project files.
            raise create_failure

        def assert_ok(result, context):
            if result.failure is not None:
                raise result.failure

        namespace = {"PROJECT": "Fixture", "call": call, "assert_ok": assert_ok,
                     "settle_or_fail": lambda context: None}
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(SOURCE), "exec"), namespace)
        return namespace[SCENARIO], calls

    def test_failed_creation_is_not_masked_by_a_failed_cleanup_response(self):
        primary = AssertionError("creation failed")
        scenario, calls = self.scenario(primary, AssertionError("cleanup failed"))
        with self.assertRaises(AssertionError) as raised:
            scenario()
        self.assertIs(primary, raised.exception)
        self.assertEqual(["delete_project", "create_project", "delete_project"], calls)

    def test_failed_creation_is_not_masked_by_a_cleanup_transport_exception(self):
        primary = AssertionError("creation failed")
        scenario, calls = self.scenario(primary, RuntimeError("cleanup transport failed"))
        with mock.patch("builtins.print"), self.assertRaises(AssertionError) as raised:
            scenario()
        self.assertIs(primary, raised.exception)
        self.assertEqual("delete_project", calls[-1])

    def test_cleanup_is_still_checked_after_successful_creation(self):
        primary = AssertionError("later scenario failure")
        cleanup = AssertionError("cleanup failed")
        scenario, calls = self.scenario(primary, cleanup, created=True)
        with self.assertRaises(AssertionError) as raised:
            scenario()
        self.assertIs(cleanup, raised.exception)
        self.assertEqual("delete_project", calls[-1])

    def test_release_is_still_checked_after_successful_creation(self):
        scenario, _ = self.scenario(AssertionError("later scenario failure"), None, created=True)
        with self.assertRaises(AssertionError) as raised:
            scenario()
        self.assertEqual({"state": "releasing"}, raised.exception.args[0])


if __name__ == "__main__":
    unittest.main()
