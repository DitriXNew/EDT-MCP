"""
e2e tests for list_projects (kind: read).

EXEMPLAR — read tool. Shows: happy path asserts the response content, and the
non-destructive guardrail asserts the project tree is untouched (assert_no_diff).
"""

import harness
from harness import call, assert_ok, assert_contains, assert_no_diff, e2e_test, PROJECT


@e2e_test(tool="list_projects", kind="read")
def test_lists_fixture_and_does_not_mutate():
    r = call("list_projects", {})
    assert_ok(r, "list_projects happy path")
    assert_contains(r.text, PROJECT, "output should list the test project")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_projects", kind="read")
def test_format_json_returns_structured_projects():
    # format=json is the machine contract the multi-EDT proxy routes on: the project list comes
    # back in structuredContent instead of the human table.
    r = call("list_projects", {"format": "json"})
    assert_ok(r, "list_projects format=json")
    structured = r.structured or {}
    projects = structured.get("projects")
    assert isinstance(projects, list) and projects, \
        "format=json must return structuredContent.projects: %r" % (structured,)
    names = [p.get("name") for p in projects if isinstance(p, dict)]
    assert PROJECT in names, \
        "structuredContent.projects must include the fixture project %r: %r" % (PROJECT, names)
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_projects", kind="read")
def test_format_md_is_the_default_human_table():
    # The default (and an explicit format=md) render the Markdown table, unchanged from before the
    # format parameter existed.
    default = call("list_projects", {})
    assert_ok(default, "list_projects default format")
    explicit = call("list_projects", {"format": "md"})
    assert_ok(explicit, "list_projects format=md")
    for r in (default, explicit):
        assert_contains(r.text, "| Name |", "md format must render the projects table")
        assert_contains(r.text, PROJECT, "md format must list the test project")


# The readiness gate parses exactly this table, and it is the precondition every settling test
# stands on. It is asserted offline, against literal tables, because the bug it guards against is
# invisible on a live stand: a gate that stops examining rows still answers "ready" whenever the
# LAST row happens to be ready, which on a warm workspace is almost always.
@e2e_test(tool="list_projects", kind="read")
def test_readiness_gate_examines_every_row_not_just_the_last():
    header = ("| Name | State | Path | Open | EDT Project | Natures |\n"
              "|---|---|---|---|---|---|\n")
    # The blocker is NOT the last row: the standalone server's permanently 'not_available'
    # container is, and it is skipped as a known non-EDT project. A gate that keeps only the last
    # parsed row therefore sees nothing blocking and waves the suite through mid-build.
    building_first = header + \
        "| TestConfiguration | building | /w/TestConfiguration | Yes | Yes | 1c |\n" \
        "| tests | ready | /w/tests | Yes | Yes | 1c |\n" \
        "| Servers | not_available | /w/Servers | Yes | No | - |\n"
    blockers = []
    assert not harness._all_edt_projects_ready(building_first, not_ready=blockers), \
        "a project still building must block, wherever its row sits in the table"
    assert blockers == [("TestConfiguration", "building")], \
        "the blocking project must be named for the timeout diagnostic: %r" % (blockers,)

    all_ready = header + \
        "| TestConfiguration | ready | /w/TestConfiguration | Yes | Yes | 1c |\n" \
        "| tests | ready | /w/tests | Yes | Yes | 1c |\n" \
        "| Servers | not_available | /w/Servers | Yes | No | - |\n"
    blockers = []
    assert harness._all_edt_projects_ready(all_ready, not_ready=blockers), \
        "every EDT project ready must read as ready (the non-EDT container is ignored)"
    assert blockers == [], "nothing may be reported as blocking on a ready workspace: %r" % (blockers,)

    # A CLOSED project is not a blocker: EDT is deliberately not building it, so it reads
    # 'not_available' for as long as it stays closed. Blocking on it aborted every local run on a
    # workspace that keeps one heavy configuration closed on purpose.
    closed_heavy = header + (
        "| ERP_XML | not_available | /w/ERP | No | - | - |\n"
        "| TestConfiguration | ready | /w/TestConfiguration | Yes | Yes | 1c |\n")
    blockers = []
    assert harness._all_edt_projects_ready(closed_heavy, not_ready=blockers), \
        "a project closed on purpose must not block the suite"
    assert blockers == [], "a closed project must not be reported as blocking: %r" % (blockers,)

    # ...and skipping it must not smuggle a real blocker through: an OPEN project mid-build still
    # blocks while a closed one sits in the same table.
    closed_and_building = header + (
        "| ERP_XML | not_available | /w/ERP | No | - | - |\n"
        "| TestConfiguration | building | /w/TestConfiguration | Yes | Yes | 1c |\n")
    blockers = []
    assert not harness._all_edt_projects_ready(closed_and_building, not_ready=blockers), \
        "a closed project must not make a genuinely building project read as ready"
    assert blockers == [("TestConfiguration", "building")], \
        "the building project must still be named: %r" % (blockers,)

    # No parseable row at all: the substring fallback must stay conservative rather than decay to
    # a permanent "ready", and must still say what it saw.
    blockers = []
    assert not harness._all_edt_projects_ready("state: building", not_ready=blockers), \
        "an unparseable output naming a blocking state must not read as ready"
    assert blockers and blockers[0][1] == "building", \
        "the fallback must still report the state it saw: %r" % (blockers,)


@e2e_test(tool="list_projects", kind="read")
def test_readiness_gate_tolerates_the_trailing_reason_column():
    """Offline: the seventh column must preserve all six-column readiness verdicts."""
    header = ("| Name | State | Path | Open | EDT Project | Natures |\n"
              "|---|---|---|---|---|---|\n")
    reason_header = ("| Name | State | Path | Open | EDT Project | Natures | Reason |\n"
                     "|---|---|---|---|---|---|---|\n")
    rows = [
        ("| Ready | ready | /w/Ready | Yes | Yes | 1c |", ""),
        ("| Busy | building | /w/Busy | Yes | Yes | 1c |", "Computing derived data"),
        ("| Stopped | not_available | /w/Stopped | Yes | Yes | 1c |",
         "EDT context is not started; close and reopen"),
        ("| Closed | not_available | /w/Closed | No | - | - |", "Project is closed"),
        ("| Servers | not_available | /w/Servers | Yes | No | - |", "Not an EDT project"),
    ]
    for selected, ignore, expected in [
        ([0, 3, 4], (), []),
        ([1, 0, 4], (), [("Busy", "building")]),
        ([2, 0, 4], (), [("Stopped", "not_available")]),
        ([1, 2, 0], (), [("Busy", "building"), ("Stopped", "not_available")]),
        ([2, 0, 4], ("Stopped",), []),
    ]:
        original = header + "".join(rows[index][0] + "\n" for index in selected)
        trailing = reason_header + "".join(
            rows[index][0] + " " + rows[index][1] + " |\n" for index in selected)
        original_blockers = []
        trailing_blockers = []
        original_ready = harness._all_edt_projects_ready(original, not_ready=original_blockers, ignore=ignore)
        trailing_ready = harness._all_edt_projects_ready(trailing, not_ready=trailing_blockers, ignore=ignore)
        assert original_blockers == expected, (selected, original_blockers)
        assert trailing_blockers == expected, (selected, trailing_blockers)
        assert original_ready == trailing_ready == (not expected), (selected, trailing_blockers)
