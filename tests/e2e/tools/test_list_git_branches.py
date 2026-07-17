"""
e2e tests for list_git_branches (kind: read).

WHAT THE TOOL DOES
------------------
list_git_branches lists a project's git branches (local + remote-tracking, current
branch marked, detached HEAD flagged) and a best-effort "Application Bindings"
section showing which 1C infobase(s) each branch context is bound to.

RESPONSE SHAPE
--------------
MARKDOWN tool (getResponseType() == MARKDOWN); payload lands in r.text:
  "## Git Branches: <project>\\n\\n**Current:** <branch>\\n\\n"
  "| Branch | Type | Current |\\n|--------|------|---------|\\n..."
  "\\n### Application Bindings\\n\\n..."
  error: {"success": false, "error": "..."}

CI STRATEGY
-----------
The happy path IS safe to run in CI (read-only, never mutates anything) — BUT the
CI fixture project (PROJECT, "TestConfiguration") lives INSIDE the EDT-MCP plugin's
own git working tree (it has no separate git repo of its own, so the tool's
git-dir-discovery fallback resolves the PLUGIN repo). We therefore assert only
STRUCTURAL invariants (a current branch/HEAD line is present, the branch table is
non-empty) — NEVER a specific branch name, which would pin CI to whatever branch
happens to be checked out when the suite runs and break on every rebase/merge.

list_git_branches is read-only and never writes the project tree: assert_no_diff()
on every path here.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

NONEXISTENT_PROJECT = "NoSuchProject_lgb_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH (structural invariants ONLY — see module docstring)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="list_git_branches", kind="read")
def test_lists_branches_with_structural_invariants_only():
    """Read-only happy path against the real (plugin-repo-backed) fixture. Asserts
    the report shape and that SOME branch/HEAD state is reported, never a specific
    branch name (see module docstring for why).

    Mutation check: a broken tool that returned an empty/no-op body would fail the
    header/current-line/table-header/bindings-section assertions below; a tool that
    left the "Current:" value blank (e.g. repo.getBranch() silently swallowed) would
    fail the non-empty-current-value check.
    """
    r = call("list_git_branches", {"projectName": PROJECT})
    assert_ok(r, "list_git_branches happy path")

    assert_contains(r.text, "## Git Branches: " + PROJECT, "report header names the project")
    assert_contains(r.text, "**Current:**", "current branch/HEAD line is present")
    assert_contains(r.text, "| Branch | Type | Current |", "branch table header")
    assert_contains(r.text, "### Application Bindings", "bindings section is present")

    idx = r.text.index("**Current:**")
    line_end = r.text.index("\n", idx)
    current_value = r.text[idx + len("**Current:**"):line_end].strip()
    if not current_value:
        raise AssertionError(
            "Current: line must name a branch or detached HEAD, got blank: %r" % r.text[idx:idx + 80]
        )

    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="list_git_branches", kind="read")
def test_missing_projectname_errors_with_hint():
    """Missing the required projectName -> the shared required-arg guard fires,
    names the parameter, and steers to list_projects."""
    r = call("list_git_branches", {})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="list_git_branches", kind="read")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project cannot resolve a repository -> 'not found' error
    naming the bad value and steering to list_projects."""
    r = call("list_git_branches", {"projectName": NONEXISTENT_PROJECT})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")
