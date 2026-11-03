"""Execute the stand regressions against local fakes, without importing the live harness."""

import ast
from contextlib import contextmanager
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "tools" / "test_project_checks.py"
ROUND_TRIP = "test_set_disable_then_restore_round_trips_through_before_and_writes_the_cset"
SWITCH = "test_switch_round_trip_between_default_and_a_profile_created_on_disk"
TRAVERSAL = "test_create_profile_refuses_traversal_without_creating_any_cset"


class FixtureSkip(Exception):
    pass


def load(*names, **namespace):
    tree = ast.parse(SOURCE.read_text(encoding="utf-8-sig"))
    functions = {node.name: node for node in tree.body if isinstance(node, ast.FunctionDef)}
    assert set(names) <= functions.keys(), "Stand regression functions are missing"
    selected = set(names)
    if "_stored_check" in functions:
        selected.add("_stored_check")
    nodes = [node for name, node in functions.items() if name in selected]
    for node in nodes:
        node.decorator_list = []
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(SOURCE), "exec"), namespace)
    return namespace


class ProjectChecksRoundFiveTest(unittest.TestCase):
    @staticmethod
    def row(check_id="module-structure-method-in-regions", contributor="checks", **values):
        row = dict(checkId=check_id, contributorId=contributor, uid=check_id + "|" + contributor,
                   shortUid="SU72", enabled=True, defaultEnabled=True, customized=False,
                   severity="MINOR", defaultSeverity="MINOR", criticalDataIntegrity=False,
                   hasDocumentation=False)
        row.update(values)
        return row

    def test_cset_round_trip_accepts_bare_and_collision_keys(self):
        row = self.row("empty-except-statement", hasDocumentation=True)
        for key in (row["checkId"], row["contributorId"] + "|" + row["checkId"]):
            with self.subTest(key=key):
                stored = {}

                @contextmanager
                def temporary(selected, **values):
                    self.assertIs(row, selected)
                    stored[key] = dict(values)
                    try:
                        yield dict(changed=1)
                    finally:
                        stored.clear()

                def success(action, **params):
                    if action == "profiles":
                        return dict(activeProfile="Default")
                    self.assertEqual("list", action)
                    return dict(total=int(bool(stored)), checks=[row] if stored else [])

                def poll(probe, predicate, context):
                    observed = probe()
                    self.assertTrue(predicate(observed), context + ": " + repr(observed))

                namespace = load(ROUND_TRIP, _cheap_noncritical_check=lambda *args, **kwargs: row,
                                 _all=lambda: [row], _stored=lambda profile: stored, _success=success,
                                 _temporary_set=temporary, _poll=poll)
                namespace[ROUND_TRIP]()
                self.assertEqual({}, stored)

    def test_collision_key_selects_the_exact_contributor_before_the_bare_key(self):
        row = self.row()
        namespace = load("_stored_check")
        settings = {row["checkId"]: dict(enabled=True),
                    row["contributorId"] + "|" + row["checkId"]: dict(enabled=False)}
        self.assertEqual(dict(enabled=False), namespace["_stored_check"](settings, row))

    def test_mcp_uid_is_not_mistaken_for_an_edt_file_key(self):
        row = self.row()
        namespace = load("_stored_check")
        self.assertEqual({}, namespace["_stored_check"]({row["uid"]: dict(enabled=False)}, row))

    def test_live_marker_selection_accepts_the_fixture_check_without_a_shipped_guide(self):
        eligible = self.row(contributor="a")
        rows = [self.row("begin-transaction", enabled=False, hasDocumentation=True),
                self.row("empty-except-statement", shortUid="SU1", hasDocumentation=True),
                self.row(contributor="z"), self.row(contributor="0", criticalDataIntegrity=True), eligible]
        for ordered in (rows, list(reversed(rows))):
            namespace = load("_cheap_noncritical_check", "_check_with_markers", _all=lambda: ordered,
                             _error_rows=lambda: ["SU72"], E2ESkip=FixtureSkip)
            self.assertEqual(eligible, namespace["_check_with_markers"]())

    def test_switch_cleanup_waits_for_stale_profile_discovery(self):
        with tempfile.TemporaryDirectory() as temporary:
            settings = Path(temporary)
            profile = settings / "E2eProfile.cset"
            active = "Default"
            discovered = False
            removal_calls = 0
            poll_timeouts = []

            def success(action, **params):
                nonlocal active, discovered, removal_calls
                if action == "switch":
                    previous, active = active, params["profile"]
                    return dict(before=dict(activeProfile=previous), after=dict(activeProfile=active),
                                changed=True, persisted=True, profile=active)
                self.assertEqual("profiles", action)
                names = ["Default"]
                if profile.exists():
                    discovered = True
                    names.append("E2eProfile")
                elif discovered:
                    removal_calls += 1
                    # The repository lists a removed file until a later resource notification.
                    if removal_calls < 3:
                        names.append("E2eProfile")
                return dict(activeProfile=active, profiles=[dict(name=name) for name in names])

            def poll(probe, predicate, context, timeout=90):
                if "forgets removed" in context:
                    poll_timeouts.append(timeout)
                for _ in range(5):
                    observed = probe()
                    if predicate(observed):
                        return observed
                self.fail(context)

            namespace = load(SWITCH, SETTINGS=settings, PREFS=settings / "check.prefs", json=json, _success=success,
                             _stored=lambda name: {}, _poll=poll, _active_on_disk=lambda: active,
                             assert_no_diff=lambda: None,
                             harness=SimpleNamespace(calls_aborted=lambda: False, fixtures_frozen=lambda: False))
            namespace[SWITCH]()
            self.assertFalse(profile.exists())
            self.assertEqual(3, removal_calls)
            self.assertEqual([30], poll_timeouts)
            self.assertEqual("Default", active)

    def exercise_traversal(self, side_effect=None):
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            settings = project / ".settings"
            settings.mkdir()
            requests = []

            def call(tool, params):
                requests.append((tool, params))
                if side_effect == "project":
                    (project / "E2e757_escape_probe.cset").write_text("{}", encoding="utf-8")
                elif side_effect == "settings":
                    (settings / "escaped.cset").write_text("{}", encoding="utf-8")
                return "Invalid profile name '../E2e757_escape_probe': not a path"

            namespace = load(TRAVERSAL, PROJECT="TestConfiguration", PROJECT_DIR=project, Path=Path,
                             _snapshot=lambda **kwargs: {p.name: p.read_bytes() for p in settings.glob("*.cset")},
                             call=call, assert_error=lambda result: result,
                             assert_error_quality=lambda *args, **kwargs: None, assert_no_diff=lambda: None,
                             uuid4=lambda: SimpleNamespace(hex="probe"))
            namespace[TRAVERSAL]()
            self.assertEqual([("project_checks", dict(projectName="TestConfiguration", action="createProfile",
                                                      profile="../E2e757_escape_probe"))], requests)

    def test_traversal_pin_sends_the_path_and_checks_ignored_files(self):
        self.exercise_traversal()

    def test_traversal_pin_detects_a_file_at_the_project_root(self):
        with self.assertRaisesRegex(AssertionError, "profile escaped"):
            self.exercise_traversal("project")

    def test_traversal_pin_detects_a_new_settings_file(self):
        with self.assertRaisesRegex(AssertionError, "refused profile created or changed settings"):
            self.exercise_traversal("settings")


if __name__ == "__main__":
    unittest.main()
