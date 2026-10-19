"""Offline coverage of issue #769's opportunistic ratchet; no live server is contacted."""

import importlib.util
import os
import sys
import unittest
from unittest import mock


E2E_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEAKED = "Project is building: com._1c.g5.v8.derived.DerivedDataStatus@6749e34c. Please wait and retry."
WORDED = ("Project is building: stage NORMAL, pipeline WAITING_STAGE_PROCCESING_END, "
          "model sync active. Please wait and retry.")


class BuildingRefusalRatchetTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        sys.path.insert(0, E2E_DIR)
        try:
            import harness

            spec = importlib.util.spec_from_file_location(
                "edt_mcp_e2e_log_ratchet_review769",
                os.path.join(E2E_DIR, "tools", "test_edt_log_ratchet.py"))
            cls.ratchet = importlib.util.module_from_spec(spec)
            with mock.patch.object(harness, "REGISTRY", []):
                spec.loader.exec_module(cls.ratchet)
            cls.failure = harness.E2EAssertion
        finally:
            sys.path.remove(E2E_DIR)

    def test_an_empty_run_discloses_opportunistic_coverage_and_the_required_stand_counts(self):
        with mock.patch.object(self.ratchet, "building_refusals_seen", return_value=[]), \
                mock.patch.object(self.ratchet, "_workspace_dir", return_value=None):
            with self.assertRaisesRegex(
                    self.ratchet.E2ESkip, r"opportunistic.*pre-deploy.*post-deploy"):
                self.ratchet.test_building_refusals_name_the_build_state_not_a_java_object_identity()

    def test_a_recorded_old_jar_refusal_fails_deterministically(self):
        with mock.patch.object(self.ratchet, "building_refusals_seen", return_value=[LEAKED]), \
                mock.patch.object(self.ratchet, "_workspace_dir", return_value=None):
            with self.assertRaisesRegex(self.failure, "carry a Java object identity"):
                self.ratchet.test_building_refusals_name_the_build_state_not_a_java_object_identity()

    def test_the_supported_polled_status_wording_passes(self):
        with mock.patch.object(self.ratchet, "building_refusals_seen", return_value=[WORDED]), \
                mock.patch.object(self.ratchet, "_workspace_dir", return_value=None):
            self.ratchet.test_building_refusals_name_the_build_state_not_a_java_object_identity()

    def test_a_bound_log_refusal_is_checked_without_a_recorded_response(self):
        token = "review769-log-probe"
        with mock.patch.object(self.ratchet, "building_refusals_seen", return_value=[]), \
                mock.patch.object(self.ratchet, "_workspace_dir", return_value="unused-workspace"), \
                mock.patch.object(self.ratchet, "_emit_log_probe", return_value=token), \
                mock.patch.object(self.ratchet, "_our_log_messages_this_run", return_value=[token, LEAKED]):
            with self.assertRaisesRegex(self.failure, r"\[EDT log\]"):
                self.ratchet.test_building_refusals_name_the_build_state_not_a_java_object_identity()

    def test_an_unbound_log_is_not_used_as_positive_evidence(self):
        with mock.patch.object(self.ratchet, "building_refusals_seen", return_value=[]), \
                mock.patch.object(self.ratchet, "_workspace_dir", return_value="unused-workspace"), \
                mock.patch.object(self.ratchet, "_emit_log_probe", return_value="missing-probe"), \
                mock.patch.object(self.ratchet, "_our_log_messages_this_run", return_value=[LEAKED]):
            with self.assertRaisesRegex(self.ratchet.E2ESkip, "does not carry this server's run-unique probe"):
                self.ratchet.test_building_refusals_name_the_build_state_not_a_java_object_identity()


if __name__ == "__main__":
    unittest.main()
