"""Issue #769: call() keeps the text of the building refusals it retries away, and the ratchet that
reads them tells a Java object identity from a worded build state. No EDT server is needed."""

import importlib.util
import os
import sys
import unittest
from unittest import mock


E2E_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HARNESS_PATH = os.path.join(E2E_DIR, "harness.py")
SPEC = importlib.util.spec_from_file_location("edt_mcp_e2e_harness_769", HARNESS_PATH)
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)

LEAKED = ("Project is building: com._1c.g5.v8.derived.DerivedDataStatus@6749e34c. "
          "Please wait and retry.")
WORDED = ("Project is building: stage NORMAL, pipeline WAITING_STAGE_PROCCESING_END; computing "
          "MD (12 of 340 units remaining). Please wait and retry.")


def _error(text):
    return {"result": {"isError": True, "content": [{"type": "text", "text": text}]}}


def _success(text):
    return {"result": {"isError": False, "content": [{"type": "text", "text": text}]}}


def _load_ratchet():
    """The ratchet module imports `harness` by name, so it is loaded with that name resolvable."""
    sys.path.insert(0, E2E_DIR)
    try:
        spec = importlib.util.spec_from_file_location(
            "edt_mcp_e2e_log_ratchet_769", os.path.join(E2E_DIR, "tools", "test_edt_log_ratchet.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        sys.path.remove(E2E_DIR)


class BuildingRefusalRecordTest(unittest.TestCase):

    def setUp(self):
        del HARNESS._BUILDING_REFUSALS_SEEN[:]

    def test_a_retried_refusal_is_recorded_and_the_final_answer_is_returned(self):
        answers = [_error(WORDED), _success("done")]
        with mock.patch.object(HARNESS, "_post", side_effect=lambda *a: answers.pop(0)), \
                mock.patch.object(HARNESS.time, "sleep"):
            result = HARNESS.call("get_tags", {"projectName": "P"})

        self.assertFalse(result.is_error)
        self.assertEqual([WORDED], HARNESS.building_refusals_seen())

    def test_an_ordinary_answer_records_nothing(self):
        with mock.patch.object(HARNESS, "_post", return_value=_error("Project not found: P")):
            HARNESS.call("get_tags", {"projectName": "P"})

        self.assertEqual([], HARNESS.building_refusals_seen())

    def test_the_record_is_capped(self):
        HARNESS._BUILDING_REFUSALS_SEEN.extend(["x"] * HARNESS._BUILDING_REFUSALS_CAP)
        answers = [_error(WORDED), _success("done")]
        with mock.patch.object(HARNESS, "_post", side_effect=lambda *a: answers.pop(0)), \
                mock.patch.object(HARNESS.time, "sleep"):
            HARNESS.call("get_tags", {"projectName": "P"})

        self.assertEqual(HARNESS._BUILDING_REFUSALS_CAP, len(HARNESS.building_refusals_seen()))

    def test_the_returned_copy_does_not_alias_the_record(self):
        HARNESS._BUILDING_REFUSALS_SEEN.append(WORDED)
        HARNESS.building_refusals_seen().clear()
        self.assertEqual([WORDED], HARNESS.building_refusals_seen())


class JavaIdentityPatternTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.ratchet = _load_ratchet()

    def test_the_leaked_identity_is_caught(self):
        self.assertTrue(self.ratchet._JAVA_IDENTITY.search(LEAKED))
        self.assertTrue(self.ratchet._JAVA_IDENTITY.search("Project is building: java.lang.Object@1a2b3c"))

    def test_a_worded_build_state_is_not(self):
        self.assertIsNone(self.ratchet._JAVA_IDENTITY.search(WORDED))
        self.assertIsNone(self.ratchet._JAVA_IDENTITY.search(
            "Project build in progress (derived data not complete): computing MD (3 of 9 units "
            "remaining). Please wait and retry."))

    def test_only_the_derived_data_verdicts_are_selected(self):
        self.assertTrue(self.ratchet._BUILD_STATE_REFUSAL.search(WORDED))
        self.assertTrue(self.ratchet._BUILD_STATE_REFUSAL.search(
            "Project build in progress (derived data not complete). Please wait and retry."))
        self.assertIsNone(self.ratchet._BUILD_STATE_REFUSAL.search(
            "Project 'Ext' extends 'Base' and is still building, so it takes part in this cascade "
            "with an incomplete index. Please wait and retry."))


if __name__ == "__main__":
    unittest.main()
