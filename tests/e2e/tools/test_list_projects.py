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
