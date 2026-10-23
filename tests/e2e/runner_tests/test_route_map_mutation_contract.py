"""Route-map mutation tracking and reset eligibility; no live EDT is required."""

import importlib.util
from pathlib import Path
import unittest
from unittest import mock


HARNESS_PATH = Path(__file__).resolve().parents[1] / "harness.py"
TOOL = "business_process_route_map"


class RouteMapMutationContractTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("route_map_mutation_harness", HARNESS_PATH)
        self.harness = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.harness)
        for name, value in (("_BASELINE_INVENTORY", []), ("wait_for_project_ready", mock.Mock(return_value=True)),
                            ("all_fixtures_status", mock.Mock(return_value="")),
                            ("_baseline_mismatch", mock.Mock(return_value=None))):
            patch = mock.patch.object(self.harness, name, value)
            patch.start()
            self.addCleanup(patch.stop)

    def call_with_outcome(self, action, payload, is_error=False):
        raw = {"result": {"isError": is_error, "structuredContent": payload}}
        with mock.patch.object(self.harness, "_post", return_value=raw):
            return self.harness.call(TOOL, {
                "projectName": self.harness.PROJECT, "fqn": "BusinessProcess.Approval", "action": action,
            })

    def test_successful_replace_and_remove_forfeit_the_pristine_shortcut(self):
        for action in ("replace", "remove"):
            with self.subTest(action=action):
                self.harness.begin_test_calls()
                self.call_with_outcome(action, {"success": True, "action": action, "persisted": False})
                self.assertFalse(self.harness.mutations_unresolved())
                self.assertIn(TOOL, self.harness.confirmed_mutation_tools())
                self.assertFalse(self.harness.model_is_pristine())

    def test_refused_get_replace_and_remove_keep_the_pristine_shortcut(self):
        for action in ("get", "replace", "remove"):
            with self.subTest(action=action):
                self.harness.begin_test_calls()
                result = self.call_with_outcome(action, {"success": False, "error": "Refused"}, True)
                self.assertTrue(result.is_error)
                self.assertFalse(self.harness.mutations_unresolved())
                self.assertFalse(self.harness.confirmed_mutation_tools())
                self.assertTrue(self.harness.model_is_pristine())

    def test_a_write_that_dies_in_flight_aborts_calls_and_freezes_cleanup(self):
        with mock.patch.object(self.harness, "_post", side_effect=ConnectionResetError("Unread outcome")), \
                mock.patch.object(self.harness, "freeze_fixtures", return_value=True) as freeze:
            with self.assertRaises(ConnectionResetError):
                self.harness.call(TOOL, {"projectName": self.harness.PROJECT, "action": "replace"})
        self.assertTrue(self.harness.mutations_unresolved())
        self.assertTrue(self.harness.calls_aborted())
        freeze.assert_called_once_with()
        self.assertIn(TOOL, self.harness.abort_reason())
        self.assertFalse(self.harness.model_is_pristine())

    def test_committed_or_unknown_write_failures_forfeit_the_pristine_shortcut(self):
        for marker in ("mutationCommitted", "mutationOutcomeUnknown"):
            with self.subTest(marker=marker):
                self.harness.begin_test_calls()
                self.call_with_outcome("replace", {"success": False, "error": "Write failed", marker: True}, True)
                self.assertFalse(self.harness.mutations_unresolved())
                self.assertIn(TOOL, self.harness.confirmed_mutation_tools())
                self.assertFalse(self.harness.model_is_pristine())


if __name__ == "__main__":
    unittest.main()
