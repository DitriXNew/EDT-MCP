"""Exercise the stand test's failure cleanup without an EDT process or tool calls."""

import ast
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SOURCE = Path(__file__).resolve().parents[1] / "tools" / "test_project_checks.py"
TEST_NAME = "test_profile_create_copy_delete_round_trip_restores_the_repository_and_cset_files"


class BodyFailure(AssertionError):
    pass


class CleanupFailure(RuntimeError):
    pass


class ProjectChecksCleanupTest(unittest.TestCase):
    def exercise(self, *, body_fails=True, deletes_fail=False, discovery_fails=False, unlink_fails=False,
                 calls_aborted=False, fixtures_frozen=False, body_error=None):
        tree = ast.parse(SOURCE.read_text(encoding="utf-8"))
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == TEST_NAME)
        function.decorator_list = []
        body_error = body_error if body_error is not None else BodyFailure("original assertion")
        attempted = []
        profile_calls = 0
        failed_body = False
        with tempfile.TemporaryDirectory() as temporary:
            settings = Path(temporary)

            def profiles():
                names = ["Default", *sorted(path.stem for path in settings.glob("*.cset"))]
                return dict(activeProfile="Default", profiles=[dict(name=name, customizedChecks=0) for name in names])

            def success(action, **params):
                nonlocal profile_calls, failed_body
                if action == "profiles":
                    profile_calls += 1
                    if body_fails and profile_calls == 2:
                        failed_body = True
                        raise body_error
                    cleanup_start = 3 if body_fails else 4
                    if discovery_fails and profile_calls == cleanup_start:
                        raise CleanupFailure("profile discovery failed")
                    return profiles()
                name = params["profile"]
                path = settings / (name + ".cset")
                if action in ("createProfile", "copyProfile"):
                    path.write_text('{"version":1,"settings":{}}', encoding="utf-8")
                    return dict(profiles(), profile=name, **{"from": params.get("from", "Default")})
                self.assertEqual("deleteProfile", action)
                if failed_body:
                    attempted.append(name)
                    if deletes_fail:
                        raise CleanupFailure("delete failed: " + name)
                path.unlink()
                return dict(profiles(), deletedProfile=name, deletedCustomizedChecks=0)

            def poll(probe, predicate, context):
                result = probe()
                if not predicate(result):
                    raise AssertionError(context)
                return result

            namespace = dict(SETTINGS=settings, _success=success,
                             _snapshot=lambda: {path.name: path.read_bytes() for path in settings.glob("*.cset")},
                             _stored=lambda name: {}, _poll=poll, assert_no_diff=lambda: None,
                             uuid4=lambda: SimpleNamespace(hex="cleanup"),
                             harness=SimpleNamespace(calls_aborted=lambda: calls_aborted,
                                                     fixtures_frozen=lambda: fixtures_frozen))
            exec(compile(ast.Module(body=[function], type_ignores=[]), str(SOURCE), "exec"), namespace)
            real_unlink = Path.unlink

            def unlink(path, *args, **kwargs):
                if unlink_fails and path.name.endswith("_copy.cset"):
                    raise PermissionError("fallback unlink failed")
                return real_unlink(path, *args, **kwargs)

            error = None
            with patch.object(Path, "unlink", unlink):
                try:
                    namespace[TEST_NAME]()
                except BaseException as caught:
                    error = caught
            remaining = {path.stem for path in settings.glob("*.cset")}
        return SimpleNamespace(error=error, body_error=body_error, attempted=attempted, remaining=remaining)

    def test_each_delete_is_attempted_and_direct_unlink_restores_both_files(self):
        observed = self.exercise(deletes_fail=True)
        self.assertEqual(["E2e757_cleanup_copy", "E2e757_cleanup"], observed.attempted)
        self.assertEqual(set(), observed.remaining)
        self.assertIs(observed.body_error, observed.error)
        self.assertEqual(2, len(observed.error.__notes__))
        self.assertTrue(all("CleanupFailure: delete failed:" in note for note in observed.error.__notes__))

    def test_failed_discovery_also_falls_back_and_does_not_prevent_the_next_delete(self):
        observed = self.exercise(discovery_fails=True)
        self.assertEqual(["E2e757_cleanup"], observed.attempted)
        self.assertEqual(set(), observed.remaining)
        self.assertIs(observed.body_error, observed.error)
        self.assertTrue(any("CleanupFailure: profile discovery failed" in note
                            for note in observed.error.__notes__))

    def test_unlink_failure_is_collected_after_the_original_and_next_profile_is_attempted(self):
        observed = self.exercise(deletes_fail=True, unlink_fails=True)
        self.assertEqual(["E2e757_cleanup_copy", "E2e757_cleanup"], observed.attempted)
        self.assertEqual({"E2e757_cleanup_copy"}, observed.remaining)
        self.assertIs(observed.body_error, observed.error)
        self.assertTrue(any("PermissionError: fallback unlink failed" in note for note in observed.error.__notes__))

    def test_cleanup_errors_are_raised_when_the_body_succeeded(self):
        observed = self.exercise(body_fails=False, discovery_fails=True)
        self.assertIsInstance(observed.error, ExceptionGroup)
        self.assertIsInstance(observed.error.exceptions[0], CleanupFailure)
        self.assertEqual(set(), observed.remaining)

    def test_aborted_calls_leave_both_files_untouched_by_the_fallback(self):
        observed = self.exercise(deletes_fail=True, calls_aborted=True)
        self.assertEqual({"E2e757_cleanup", "E2e757_cleanup_copy"}, observed.remaining)
        self.assertIs(observed.body_error, observed.error)

    def test_frozen_fixtures_leave_both_files_untouched_by_the_fallback(self):
        observed = self.exercise(deletes_fail=True, fixtures_frozen=True)
        self.assertEqual({"E2e757_cleanup", "E2e757_cleanup_copy"}, observed.remaining)
        self.assertIs(observed.body_error, observed.error)

    def test_cleanup_preserves_assertion_skip_timeout_and_base_exception_types(self):
        for error in (BodyFailure("assertion"), unittest.SkipTest("skip"),
                      TimeoutError("call timeout"), KeyboardInterrupt("interrupted")):
            with self.subTest(type=type(error).__name__):
                observed = self.exercise(deletes_fail=True, body_error=error)
                self.assertIs(error, observed.error)
                self.assertEqual(2, len(observed.error.__notes__))

    def test_successful_cleanup_reraises_the_exact_original_failure(self):
        observed = self.exercise()
        self.assertIs(observed.body_error, observed.error)
        self.assertEqual(["E2e757_cleanup_copy", "E2e757_cleanup"], observed.attempted)
        self.assertEqual(set(), observed.remaining)

    def test_successful_round_trip_has_no_cleanup_error(self):
        observed = self.exercise(body_fails=False)
        self.assertIsNone(observed.error)
        self.assertEqual(set(), observed.remaining)


if __name__ == "__main__":
    unittest.main()
