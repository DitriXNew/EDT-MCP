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
def test_emits_structured_content_projects():
    # structuredContent.projects[*].name is the machine contract the multi-EDT proxy routes on
    # (issue #302). It rides ALONGSIDE the human markdown table (which the test above checks).
    r = call("list_projects", {})
    assert_ok(r, "list_projects happy path")
    structured = r.structured or {}
    projects = structured.get("projects")
    assert isinstance(projects, list) and projects, \
        "list_projects must emit structuredContent.projects: %r" % (structured,)
    names = [p.get("name") for p in projects if isinstance(p, dict)]
    assert PROJECT in names, \
        "structuredContent.projects must include the fixture project %r: %r" % (PROJECT, names)
    assert_no_diff("a read tool must not touch the project on disk")
