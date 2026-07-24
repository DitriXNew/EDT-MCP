"""
e2e tests for list_projects (kind: read).

EXEMPLAR — read tool. Shows: happy path asserts the response content, and the
non-destructive guardrail asserts the project tree is untouched (assert_no_diff).
"""

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
