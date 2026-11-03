"""Issue #797: successful readiness data is not a retryable refusal; no EDT needed."""

import importlib.util
import os
import sys
import unittest
from unittest import mock


E2E_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPEC = importlib.util.spec_from_file_location(
    "edt_mcp_e2e_harness_797", os.path.join(E2E_DIR, "harness.py"))
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)

BUILDING = "Project is building: stage NORMAL. Please wait and retry."
TABLE = ("| Name | State | Path | Open | EDT Project | Natures | Reason |\n"
         "|------|-------|------|------|-------------|--------|--------|\n"
         "| Demo | building | /workspace/Demo | Yes | Yes | V8ConfigurationNature | "
         + BUILDING + " |\n")


def response(text, *, error=False, structured=None):
    result = {"isError": error, "content": [{"type": "text", "text": text}]}
    if structured is not None:
        result["structuredContent"] = structured
    return {"result": result}


class BuildingRefusalChannelTest(unittest.TestCase):
    def setUp(self):
        HARNESS._BUILDING_REFUSALS_SEEN.clear()

    def assert_no_retry(self, raw):
        # A second answer makes an accidental retry terminate promptly and fail the count.
        with mock.patch.object(HARNESS, "_post", side_effect=[raw, response("done")]) as post, \
                mock.patch.object(HARNESS.time, "sleep") as sleep:
            result = HARNESS.call("list_projects", {})
        self.assertIs(raw, result.raw)
        self.assertEqual(1, post.call_count)
        sleep.assert_not_called()
        self.assertEqual([], HARNESS.building_refusals_seen())

    def test_successful_markdown_reason_is_not_retried_or_recorded(self):
        self.assert_no_retry(response(TABLE))

    def test_successful_json_reason_is_not_retried_or_recorded(self):
        self.assert_no_retry(response("Done", structured={"success": True, "projects": [
            {"name": "Demo", "state": "building", "reason": BUILDING}]}))

    def test_error_text_is_still_retried_and_recorded(self):
        self.assert_retry(response(BUILDING, error=True))

    def test_structured_failure_is_still_retried_and_recorded(self):
        self.assert_retry(response("Error", structured={"success": False, "error": BUILDING}))

    def assert_retry(self, refusal):
        with mock.patch.object(HARNESS, "_post", side_effect=[refusal, response("done")]) as post, \
                mock.patch.object(HARNESS.time, "sleep"):
            result = HARNESS.call("list_projects", {})
        self.assertEqual("done", result.text)
        self.assertEqual(2, post.call_count)
        self.assertEqual([BUILDING], HARNESS.building_refusals_seen())

    def test_an_error_searches_only_the_error_text(self):
        self.assert_no_retry(response("Error", error=True, structured={
            "success": False, "error": "Project not found: Missing", "projects": [
                {"name": "Demo", "reason": BUILDING}]}))

    def test_a_structured_failure_searches_only_the_error_text(self):
        self.assert_no_retry(response("Error", structured={
            "success": False, "error": "Project not found: Missing", "reason": BUILDING}))

    def test_successful_text_is_not_searched_when_the_error_is_structured(self):
        self.assert_no_retry(response(TABLE, structured={
            "success": False, "error": "Project not found: Missing"}))


class ProjectNaturesHeaderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "edt_mcp_applications_797", os.path.join(E2E_DIR, "tools", "test_get_applications.py"))
        cls.tool = importlib.util.module_from_spec(spec)
        with mock.patch.dict(sys.modules, {"harness": HARNESS}):
            spec.loader.exec_module(cls.tool)

    def test_trailing_reason_keeps_dependent_fixture_discovery(self):
        text = ("| Name | State | Path | Open | EDT Project | Natures | Reason |\n"
                "|---|---|---|---|---|---|---|\n"
                "| Ext | ready | /Ext | Yes | Yes | V8ExtensionNature | |\n")
        with mock.patch.object(self.tool, "call", return_value=HARNESS.Result(response(text))):
            natures = self.tool._project_natures()
        self.assertEqual({"Ext": "V8ExtensionNature"}, natures)
        self.assertTrue(self.tool._is_dependent(natures["Ext"]))

    def test_nature_lookup_uses_headers_and_the_markdown_escape_parser(self):
        text = ("| State | Reason | Natures | Name |\n"
                "|---|---|---|---|\n"
                "| not_available | a\\|b | V8ExternalObjectsNature | Ext\\|Name |\n")
        with mock.patch.object(self.tool, "call", return_value=HARNESS.Result(response(text))):
            self.assertEqual({"Ext|Name": "V8ExternalObjectsNature"}, self.tool._project_natures())


if __name__ == "__main__":
    unittest.main()
