"""Offline regressions for live check selection and the e2e assertions it must preserve."""

import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import ModuleType
import unittest
from unittest import mock


E2E = Path(__file__).resolve().parents[1]
SOURCE = E2E / "tools/test_project_checks.py"
HARNESS_SPEC = importlib.util.spec_from_file_location("project_checks_catalog_harness", E2E / "harness.py")
HARNESS = importlib.util.module_from_spec(HARNESS_SPEC)
HARNESS_SPEC.loader.exec_module(HARNESS)
TREE_TEST = "test_list_returns_the_check_tree_with_ids_severities_and_the_active_profile"
DETAIL_TEST = "test_list_with_check_ids_returns_only_those_and_their_parameters"
NOOP_TEST = "test_set_with_no_effective_change_reports_changed_zero_and_touches_nothing"


class ProjectChecksLiveCatalogTest(unittest.TestCase):
    def setUp(self):
        self.module = ModuleType("project_checks_catalog_under_test")
        self.module.__file__ = str(SOURCE)
        with mock.patch.dict(sys.modules, {"harness": HARNESS}), mock.patch.object(HARNESS, "REGISTRY", []):
            exec(compile(SOURCE.read_bytes(), str(SOURCE), "exec"), self.module.__dict__)
        temporary = self.enterContext(tempfile.TemporaryDirectory())
        self.module.SETTINGS = Path(temporary)
        self.module.PREFS = Path(temporary) / "com.e1c.g5.v8.dt.check.prefs"
        (Path(temporary) / "Default.cset").write_text('{"version":1,"settings":{}}', encoding="utf-8")
        self.module.PREFS.write_text("eclipse.preferences.version=1\n", encoding="utf-8")
        self.module.assert_no_diff = mock.Mock()
        self.rows = [self.row("edt-core-%02d" % index) for index in range(60)]
        self.rows.append(self.row("module-undefined-variable-check", hasDocumentation=True))
        self.requests = []
        self.fault = None
        self.module.call = self.call

    @staticmethod
    def row(check_id, contributor="com.e1c.g5.v8.dt.bsl", **overrides):
        row = dict(checkId=check_id, contributorId=contributor, uid=check_id + "|" + contributor,
                   shortUid="SU-" + check_id + "-" + contributor, enabled=True, severity="MAJOR",
                   type="WARNING", title=check_id, category="Core", defaultEnabled=True,
                   defaultSeverity="MAJOR", customized=False, criticalDataIntegrity=False,
                   hasDocumentation=False)
        row.update(overrides)
        return row

    @staticmethod
    def result(data=None, error=None):
        return HARNESS.Result({"result": {"isError": error is not None,
                               "structuredContent": dict(success=error is None, **(data or {})),
                               "content": [{"type": "text", "text": error or "Done"}]}})

    def call(self, tool, params):
        self.assertEqual("project_checks", tool)
        self.requests.append(copy.deepcopy(params))
        action = params["action"]
        if self.fault == "missing_tool":
            return self.result(error="Unknown tool: project_checks")
        if action == "profiles":
            return self.result(dict(activeProfile="Default"))
        if action == "list":
            rows = sorted(copy.deepcopy(self.rows), key=lambda row: (row["category"], row["checkId"], row["uid"]))
            if "checkIds" in params:
                ids = params["checkIds"]
                rows = [row for row in rows if any(value in (row["checkId"], row["uid"], row["shortUid"])
                                                  for value in ids)]
                if not rows:
                    return self.result(error="Unknown checkId '%s'; use project_checks list" % ids[0])
                for row in rows:
                    row["parameters"] = None if self.fault == "parameters" else []
                    if self.fault == "filtered_uid" or (self.fault == "short_uid" and ids == [row["shortUid"]]):
                        row["uid"] = "wrong-check|wrong-contributor"
            total = len(rows)
            offset = params.get("offset", 0)
            rows = rows[offset:offset + params.get("limit", 200)]
            return self.result(dict(checks=rows, total=total, returned=len(rows), offset=offset,
                                    activeProfile="Default"))
        self.assertEqual("set", action)
        changes = json.loads(params["changes"])
        self.assertEqual(1, len(changes))
        change = changes[0]
        row = next(row for row in self.rows if row["uid"] == change["checkId"])
        self.assertEqual((row["enabled"], row["severity"]), (change["enabled"], change["severity"]))
        before = {key: row[key] for key in ("uid", "checkId", "enabled", "severity")}
        after = dict(before)
        if self.fault == "after":
            after["checkId"] = "wrong-check"
        if self.fault == "mtime":
            path = self.module.SETTINGS / "Default.cset"
            stat = path.stat()
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns + 1000000))
        return self.result(dict(profile="Default", persisted=True, before=[before], after=[after],
                                changed=1 if self.fault == "changed" else 0))

    def test_tree_accepts_a_minimal_edt_without_v8_code_style(self):
        getattr(self.module, TREE_TEST)()
        self.module.assert_no_diff.assert_called_once_with()

    def test_documentation_flags_are_checked_against_files_in_both_directions(self):
        for documented in (True, False):
            with self.subTest(documented=documented):
                row = next(row for row in self.rows if row["hasDocumentation"] is documented)
                row["hasDocumentation"] = not documented
                try:
                    with self.assertRaises(AssertionError):
                        getattr(self.module, TREE_TEST)()
                finally:
                    row["hasDocumentation"] = documented

    def test_no_documented_live_check_skips_only_that_assertion_and_still_checks_disk(self):
        self.rows = [row for row in self.rows if not row["hasDocumentation"]]
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            getattr(self.module, TREE_TEST)()
        self.assertIn("no live check has a shipped checks/<id>.md", output.getvalue())
        self.module.assert_no_diff.assert_called_once_with()
        self.module.assert_no_diff.side_effect = AssertionError("tracked fixture changed")
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(AssertionError, "tracked fixture"):
            getattr(self.module, TREE_TEST)()

    def test_no_documented_live_check_does_not_hide_structural_regressions(self):
        self.rows = [row for row in self.rows if not row["hasDocumentation"]]
        self.rows[0]["severity"] = "SEVERE"
        with self.assertRaises(AssertionError):
            getattr(self.module, TREE_TEST)()

    def test_detail_uses_live_uid_and_round_trips_its_short_uid(self):
        self.rows = [self.row("optional-check", contributor="org.example.checks"),
                     self.row("core-check", enabled=False)]
        getattr(self.module, DETAIL_TEST)()
        self.assertEqual([[self.rows[1]["uid"]], [self.rows[1]["shortUid"]]],
                         [request["checkIds"] for request in self.requests if "checkIds" in request])
        self.module.assert_no_diff.assert_called_once_with()

    def test_detail_preserves_parameter_identity_and_short_uid_assertions(self):
        for fault in ("parameters", "filtered_uid", "short_uid"):
            with self.subTest(fault=fault):
                self.fault = fault
                with self.assertRaises(AssertionError):
                    getattr(self.module, DETAIL_TEST)()

    def test_detail_skips_an_empty_live_tree_with_a_reason(self):
        self.rows = []
        with self.assertRaisesRegex(HARNESS.E2ESkip, "no validation checks"):
            getattr(self.module, DETAIL_TEST)()
        self.assertEqual(["list"], [request["action"] for request in self.requests])

    def test_noop_prefers_an_enabled_core_check_and_restores_it(self):
        self.rows = [self.row("aaa-optional-check", contributor="org.example.checks"),
                     self.row("aaa-disabled-core", enabled=False), self.row("zzz-core-check")]
        getattr(self.module, NOOP_TEST)()
        sets = [json.loads(request["changes"]) for request in self.requests if request["action"] == "set"]
        self.assertEqual(2, len(sets), "no-op still runs its restore and verifies the repository")
        self.assertTrue(all(batch[0]["checkId"] == self.rows[2]["uid"] for batch in sets))

    def test_noop_accepts_an_enabled_check_when_only_an_optional_contributor_exists(self):
        self.rows = [self.row("available-check", contributor="org.example.checks", criticalDataIntegrity=True)]
        getattr(self.module, NOOP_TEST)()

    def test_noop_skips_without_enabled_checks_and_never_sets(self):
        for rows in ([], [self.row("disabled-check", enabled=False)]):
            with self.subTest(rows=rows):
                self.rows = rows
                self.requests.clear()
                with self.assertRaisesRegex(HARNESS.E2ESkip, "no enabled validation check"):
                    getattr(self.module, NOOP_TEST)()
                self.assertEqual(["list"], [request["action"] for request in self.requests])

    def test_noop_still_rejects_changed_counts_unequal_states_and_settings_rewrites(self):
        for fault in ("changed", "after", "mtime"):
            with self.subTest(fault=fault):
                self.fault = fault
                with self.assertRaises(AssertionError):
                    getattr(self.module, NOOP_TEST)()

    def test_missing_tool_fails_before_any_environment_skip(self):
        self.rows = []
        self.fault = "missing_tool"
        for name in (TREE_TEST, DETAIL_TEST, NOOP_TEST):
            with self.subTest(name=name), self.assertRaisesRegex(HARNESS.E2EAssertion, "Unknown tool"):
                getattr(self.module, name)()


if __name__ == "__main__":
    unittest.main()
