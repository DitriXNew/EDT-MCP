"""
e2e tests for switch_git_branch (kind: action).

WHAT THE TOOL DOES
------------------
switch_git_branch performs a headless EGit checkout of a project's git repository
onto another branch, after pre-checks (branch exists, not already on it, working
tree clean) and a bounded background Job.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success:  {"success": true, "previousBranch", "branch", "bindings"?}
  error:    {"success": false, "error": "..."}

CI STRATEGY -- NEGATIVES ONLY, NO HAPPY-PATH SWITCH
----------------------------------------------------
!!! LOUD WARNING !!! The CI fixture project (PROJECT, "TestConfiguration") lives
INSIDE the EDT-MCP plugin's OWN git working tree (git-dir-discovery resolves the
PLUGIN repo, same as list_git_branches — see that file's module docstring). A real
"happy path" switch here would therefore CHECK OUT A DIFFERENT BRANCH OF THE PLUGIN
REPOSITORY ITSELF while CI is running from it — corrupting the running checkout,
the test harness's own git-fixture assumptions, and potentially the CI job. This
file therefore contains ONLY negative-path tests that are guaranteed to be rejected
by the tool's own pre-checks BEFORE any BranchOperation.execute() runs. The real
happy-path switch (a scratch git repo with two branches) is a LIVE, ATTENDED gate
run by hand on a throwaway stand — never automated here.

Every test below asserts assert_no_diff(): a rejected call must never touch the
fixture (and never touch the plugin repo's checked-out branch).
"""

from harness import (
    E2ESkip,
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
    _git,
)

NONEXISTENT_PROJECT = "NoSuchProject_sgb_zzz"
NONEXISTENT_BRANCH = "no_such_branch_sgb_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (CI-safe: every case is rejected before any checkout runs)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="switch_git_branch", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard fires, names the parameter,
    and steers to list_projects."""
    r = call("switch_git_branch", {"branch": "main"})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="switch_git_branch", kind="action")
def test_missing_branch_errors_clearly():
    """A real project but no branch -> the tool's own required-arg guard fires,
    naming the missing parameter."""
    r = call("switch_git_branch", {"projectName": PROJECT})
    err = assert_error(r, "missing branch")
    assert_error_quality(err, names=["branch"],
                         ctx="missing branch must name the parameter")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="switch_git_branch", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project cannot resolve a repository -> 'not found' error
    naming the bad value and steering to list_projects."""
    r = call("switch_git_branch", {"projectName": NONEXISTENT_PROJECT, "branch": "main"})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="switch_git_branch", kind="action")
def test_nonexistent_branch_names_it_and_the_current_branch():
    """A real project but an unknown branch -> Repository.findRef returns null ->
    an actionable error naming BOTH the bad branch AND the branch currently checked
    out (independent ground truth via a direct `git` call, NOT the tool under test —
    anti-cheat: this proves the error message reflects the REAL repo state, not a
    hardcoded placeholder)."""
    current = _git("rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    if not current:
        raise AssertionError("could not determine the plugin repo's current branch via git")
    if current == "HEAD":
        # Detached HEAD (the CI checkout): the tool honestly reports the commit SHA instead
        # of a branch name - keep the same independent ground truth, just in SHA form.
        current = _git("rev-parse", "HEAD").stdout.strip()

    r = call("switch_git_branch", {"projectName": PROJECT, "branch": NONEXISTENT_BRANCH})
    err = assert_error(r, "nonexistent branch")
    assert_error_quality(err, names=[NONEXISTENT_BRANCH, current], suggests=["list_git_branches"],
                         ctx="nonexistent branch must name the bad value, the current branch, "
                             "and steer to list_git_branches")
    assert_no_diff("a rejected call must not touch the fixture or the plugin repo's checkout")


@e2e_test(tool="switch_git_branch", kind="action")
def test_switching_to_the_current_branch_is_rejected():
    """Switching to the branch already checked out must be an 'already on it' error,
    never a silent no-op success (which would also be indistinguishable from a
    successful-but-broken checkout). Ground truth for 'current' comes from a direct
    `git` call, independent of the tool under test."""
    current = _git("rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    if not current or current == "HEAD":
        # Detached HEAD (the CI checkout leaves the merge ref detached): there is no named
        # branch to be "already on", so this path is untestable here - covered by the unit
        # tests and the live-gate scenario. SKIP, not fail.
        raise E2ESkip("plugin repo is on a detached HEAD (CI checkout); the already-on-it "
                      "path needs a named branch")

    r = call("switch_git_branch", {"projectName": PROJECT, "branch": current})
    err = assert_error(r, "already on the current branch")
    assert_error_quality(err, names=[current],
                         ctx="already-on-it error must name the current branch")
    assert_no_diff("a rejected call must not touch the fixture or the plugin repo's checkout")
