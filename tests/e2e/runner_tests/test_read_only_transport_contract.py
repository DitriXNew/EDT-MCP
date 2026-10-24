"""Action-aware attempt and abandoned-transport tracking, without a live EDT."""

import http.client
import importlib.util
from pathlib import Path
import unittest
from unittest import mock
import urllib.error


HARNESS_PATH = Path(__file__).resolve().parents[1] / "harness.py"
READS = (("business_process_route_map", "get"), ("dcs", "get"), ("dcs", "options"))
WRITES = (("business_process_route_map", "replace"), ("business_process_route_map", "remove"),
          ("dcs", "replace"))


def fresh_harness():
    spec = importlib.util.spec_from_file_location("read_only_transport_harness", HARNESS_PATH)
    harness = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(harness)
    harness._BASELINE_INVENTORY = []
    harness.wait_for_project_ready = mock.Mock(return_value=True)
    harness.all_fixtures_status = mock.Mock(return_value="")
    harness._baseline_mismatch = mock.Mock(return_value=None)
    return harness


class ReadOnlyTransportContractTest(unittest.TestCase):
    def assert_read_left_no_mutation(self, harness):
        self.assertFalse(harness.calls_aborted())
        self.assertFalse(harness.fixtures_frozen())
        self.assertFalse(harness.mutations_unresolved())
        self.assertFalse(harness._UNRESOLVED_MUTATION_PROJECTS)
        self.assertFalse(harness.mutated_fixture_projects())
        self.assertFalse(harness.evidenced_mutation_fixture_projects())
        self.assertTrue(harness.model_is_pristine())

    def test_read_attempts_never_record_mutation_targets_or_unresolved_writes(self):
        for tool, action in READS:
            with self.subTest(tool=tool, action=action):
                harness = fresh_harness()
                harness._record_attempt(tool, {"projectName": harness.TESTS_PROJECT, "action": action})
                self.assert_read_left_no_mutation(harness)

    def test_read_transport_loss_after_dispatch_does_not_abort_or_freeze(self):
        for tool, action in READS:
            for error in (ConnectionResetError("after dispatch"), http.client.IncompleteRead(b"partial")):
                with self.subTest(tool=tool, action=action, error=type(error).__name__):
                    harness = fresh_harness()
                    with mock.patch.object(harness, "_post", side_effect=error) as post, \
                            mock.patch.object(harness, "freeze_fixtures", wraps=harness.freeze_fixtures) as freeze:
                        with self.assertRaises(type(error)):
                            harness.call(tool, {"projectName": harness.TESTS_PROJECT, "action": action})
                    post.assert_called_once()
                    freeze.assert_not_called()
                    self.assert_read_left_no_mutation(harness)

    def test_read_socket_timeouts_in_all_post_paths_do_not_abort_or_freeze(self):
        for tool, action in READS:
            for mode in ("socket", "url", "response_body", "error_body"):
                with self.subTest(tool=tool, action=action, mode=mode):
                    harness = fresh_harness()
                    timeout = TimeoutError("after dispatch")
                    response = mock.MagicMock()
                    response.__enter__.return_value.read.side_effect = timeout
                    error = urllib.error.HTTPError("http://unused", 500, "failed", {}, None)
                    error.read = mock.Mock(side_effect=timeout)
                    failure = {"socket": timeout, "url": urllib.error.URLError(timeout),
                               "error_body": error}.get(mode)
                    with mock.patch.object(harness.urllib.request, "urlopen", return_value=response,
                                           side_effect=failure) as post, \
                            mock.patch.object(harness, "freeze_fixtures", wraps=harness.freeze_fixtures) as freeze:
                        with self.assertRaises((harness.E2ECallTimeout, TimeoutError)) as raised:
                            harness.call(tool, {"projectName": harness.TESTS_PROJECT, "action": action})
                    post.assert_called_once()
                    freeze.assert_not_called()
                    self.assertIsInstance(raised.exception, TimeoutError)
                    self.assert_read_left_no_mutation(harness)

    def test_read_timeout_after_a_completed_write_still_allows_runner_cleanup(self):
        spec = importlib.util.spec_from_file_location("read_timeout_runner", HARNESS_PATH.parent / "run_all.py")
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        harness = fresh_harness()
        with mock.patch.object(harness, "_post", return_value={
                "result": {"structuredContent": {"success": True}}}):
            harness.call("business_process_route_map", {"projectName": harness.PROJECT, "action": "replace"})
        self.assertTrue(harness._MUTATION_CONFIRMED)
        test = {"kind": "write-metadata", "func": lambda: harness.call("business_process_route_map", {
            "projectName": harness.PROJECT, "action": "get"})}
        with mock.patch.object(harness.urllib.request, "urlopen", side_effect=TimeoutError("after dispatch")), \
                mock.patch.object(runner, "_reset_after_write") as reset:
            with self.assertRaises((harness.E2ECallTimeout, TimeoutError)):
                runner._run_test_unit(harness, test)
        reset.assert_called_once_with(harness, test)
        self.assertFalse(harness.calls_aborted())
        self.assertFalse(harness.fixtures_frozen())

    def test_write_transport_loss_and_timeouts_still_abort_and_freeze(self):
        for tool, action in WRITES:
            for timeout in (False, True):
                with self.subTest(tool=tool, action=action, timeout=timeout):
                    harness = fresh_harness()
                    error = TimeoutError("after dispatch") if timeout else ConnectionResetError("after dispatch")
                    target = harness.urllib.request if timeout else harness
                    attribute = "urlopen" if timeout else "_post"
                    with mock.patch.object(target, attribute, side_effect=error), \
                            mock.patch.object(harness, "freeze_fixtures", wraps=harness.freeze_fixtures) as freeze:
                        with self.assertRaises(harness.E2ECallTimeout if timeout else ConnectionResetError):
                            harness.call(tool, {"projectName": harness.PROJECT, "action": action})
                    freeze.assert_called_once_with()
                    self.assertTrue(harness.calls_aborted())
                    self.assertTrue(harness.fixtures_frozen())
                    self.assertTrue(harness.mutations_unresolved())
                    self.assertEqual({harness.PROJECT: 1}, harness._UNRESOLVED_MUTATION_PROJECTS)
                    self.assertFalse(harness.model_is_pristine())

    def test_read_response_cannot_retire_a_different_unresolved_write(self):
        for tool, action in READS:
            with self.subTest(tool=tool, action=action):
                harness = fresh_harness()
                args = {"projectName": harness.PROJECT, "action": "replace"}
                harness._record_attempt("business_process_route_map", args)
                with mock.patch.object(harness, "_post", return_value={
                        "result": {"structuredContent": {"success": True}}}):
                    harness.call(tool, {"projectName": harness.PROJECT, "action": action})
                self.assertEqual(1, harness._MUTATIONS_UNRESOLVED)
                self.assertEqual({harness.PROJECT: 1}, harness._UNRESOLVED_MUTATION_PROJECTS)
                self.assertFalse(harness._MUTATION_CONFIRMED)


if __name__ == "__main__":
    unittest.main()
