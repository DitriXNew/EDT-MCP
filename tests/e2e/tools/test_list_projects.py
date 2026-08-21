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

    # No parseable row at all: the substring fallback must stay conservative rather than decay to
    # a permanent "ready", and must still say what it saw.
    blockers = []
    assert not harness._all_edt_projects_ready("state: building", not_ready=blockers), \
        "an unparseable output naming a blocking state must not read as ready"
    assert blockers and blockers[0][1] == "building", \
        "the fallback must still report the state it saw: %r" % (blockers,)
