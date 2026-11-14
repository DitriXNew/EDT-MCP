"""Offline checks for the stand-profile helpers of two e2e tests (issue #935).

- tools/list golden: a tool-SET mismatch names the CI tool profile the golden is taken in;
- import_project_from_file: the no-platform refusal test reads the platform precondition from the
  tool's own first answer instead of assuming a platform-free EDT.
"""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


E2E_DIR = Path(__file__).resolve().parents[1]


def _load(name, relative):
    spec = importlib.util.spec_from_file_location(name, E2E_DIR / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


HARNESS = _load("edt_mcp_stand_profile_harness", "harness.py")
with mock.patch.dict("sys.modules", {"harness": HARNESS}):
    GOLDEN = _load("edt_mcp_stand_profile_golden", "tools/test_tools_list_golden.py")
    IMPORT = _load("edt_mcp_stand_profile_import", "tools/test_import_project_from_file.py")


def _listing(*names):
    return json.dumps([{"name": name, "description": ""} for name in names])


class ToolSetHintTest(unittest.TestCase):
    def test_the_same_set_adds_no_note(self):
        self.assertEqual("", GOLDEN.tool_set_hint(_listing("a", "b"), _listing("b", "a")))

    def test_a_tool_missing_live_names_it_and_the_ci_profile(self):
        hint = GOLDEN.tool_set_hint(_listing("get_form_screenshot", "list_projects"),
                                    _listing("list_projects"))
        self.assertIn("only in the golden: ['get_form_screenshot']", hint)
        self.assertIn("only live: -", hint)
        self.assertIn("mcpDisabledTools=git,ask_workmate", hint)
        self.assertIn("mcpToolPrefsMigration=15", hint)
        self.assertIn("tests/TESTING.md", hint)

    def test_an_extra_live_tool_is_named_too(self):
        hint = GOLDEN.tool_set_hint(_listing("list_projects"), _listing("git", "list_projects"))
        self.assertIn("only live: ['git']", hint)

    def test_a_malformed_golden_adds_no_note(self):
        self.assertEqual("", GOLDEN.tool_set_hint("not json", _listing("a")))

    def test_the_golden_test_reports_the_note_on_a_set_mismatch(self):
        names = ["tool%02d" % i for i in range(60)]
        live = json.dumps([{"name": n} for n in names[1:]], indent=2) + "\n"
        with tempfile.TemporaryDirectory() as tmp:
            golden_path = Path(tmp) / "golden.json"
            golden_path.write_text(json.dumps([{"name": n} for n in names], indent=2) + "\n",
                                   encoding="utf-8")
            failures = []

            def fail(message):
                failures.append(message)
                raise AssertionError(message)

            with mock.patch.object(GOLDEN, "_canonical_tools_list", return_value=(live, 59)), \
                    mock.patch.object(GOLDEN, "_GOLDEN", str(golden_path)), \
                    mock.patch.object(GOLDEN, "_ACTUAL", str(Path(tmp) / "actual.json")), \
                    mock.patch.object(GOLDEN, "_fail", side_effect=fail), \
                    mock.patch.dict("os.environ", {"EDT_MCP_UPDATE_GOLDEN": ""}):
                with self.assertRaises(AssertionError):
                    GOLDEN.test_tools_list_matches_committed_golden_snapshot()
        self.assertEqual(1, len(failures))
        self.assertIn("only in the golden: ['tool00']", failures[0])

    def test_the_named_profile_is_the_one_ci_writes(self):
        workflow = (E2E_DIR.parents[1] / ".github" / "workflows" / "e2e-tests.yml").read_text(encoding="utf-8")
        self.assertIn("mcpDisabledTools=" + ",".join(GOLDEN.CI_DISABLED_TOOLS) + "\n", workflow)
        self.assertIn("mcpToolPrefsMigration=15\n", workflow)


class PlatformPreconditionTest(unittest.TestCase):
    def test_the_no_platform_refusal_reads_as_refused(self):
        refusal = ("No installed 1C:Enterprise platform with a thick client fits this import (x). "
                   "No project was created.")
        self.assertEqual("refused", IMPORT.platform_precondition(True, refusal))

    def test_a_started_job_reads_as_admitted(self):
        self.assertEqual("admitted", IMPORT.platform_precondition(False, '**Pending:** jobId="1"'))
        self.assertEqual("admitted", IMPORT.platform_precondition(False, "# Project imported from file"))

    def test_the_platform_failing_on_the_file_reads_as_admitted(self):
        failed = ("The 1C:Enterprise platform failed while creating a temporary infobase from the .cf "
                  "file: [exit code 1] No project was created.")
        self.assertEqual("admitted", IMPORT.platform_precondition(True, failed))

    def test_any_other_error_is_neither(self):
        self.assertEqual("other", IMPORT.platform_precondition(True, "projectName 'X' already exists"))
        self.assertEqual("other", IMPORT.platform_precondition(True, "The conversion ran out of time"))
        self.assertEqual("other", IMPORT.platform_precondition(True, None))

    def test_the_markers_are_the_products_texts(self):
        source = (E2E_DIR.parents[1] / "mcp" / "bundles" / "com.ditrix.edt.mcp.server" / "src" / "com"
                  / "ditrix" / "edt" / "mcp" / "server" / "utils" / "BinaryToXmlConverter.java")
        text = source.read_text(encoding="utf-8")
        self.assertIn('"' + IMPORT._NO_PLATFORM_REFUSAL, text)
        self.assertIn('"' + IMPORT._PLATFORM_RAN, text)


if __name__ == "__main__":
    unittest.main()
