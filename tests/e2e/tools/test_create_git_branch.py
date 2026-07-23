"""
e2e tests for create_git_branch (kind: action).

WHAT THE TOOL DOES
------------------
create_git_branch creates a new local git branch (Git.branchCreate()), optionally
checks it out (the same shared GitCheckoutSupport Job switch_git_branch uses), and
optionally attaches an EXISTING infobase to the new branch's context.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success:  {"success": true, "branch", "created", "checkedOut", "startPoint", "bound"?}
  error:    {"success": false, "error": "..."}

CI STRATEGY -- NEGATIVES ONLY, NO HAPPY-PATH CREATE
------------------------------------------------------
!!! LOUD WARNING !!! Like list_git_branches/switch_git_branch, the CI fixture project
(PROJECT, "TestConfiguration") lives INSIDE the EDT-MCP plugin's OWN git working tree
(git-dir-discovery resolves the PLUGIN repo, same as those two files' module
docstrings). A real "happy path" create would leave a NEW branch littered in the
PLUGIN repository itself while CI is running from it. This file therefore contains
ONLY negative-path tests that are guaranteed to be rejected by the tool's own
pre-checks BEFORE any Git.branchCreate() call runs. The real happy-path create
(a scratch repo, optionally + checkout + an attached base) is a LIVE, ATTENDED gate
run by hand on a throwaway stand -- never automated here.

The "branch already exists" test independently verifies via a direct `git branch
--list` call (before/after) that the findRef pre-check really does fire BEFORE any
branch is created/modified -- not just that the tool CLAIMS an error (anti-cheat).

Every test below asserts assert_no_diff(): a rejected call must never touch the
fixture (and never touch the plugin repo's branch list).
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

NONEXISTENT_PROJECT = "NoSuchProject_cgb_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (CI-safe: every case is rejected before any branch is created)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_git_branch", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard fires, names the parameter,
    and steers to list_projects."""
    r = call("create_git_branch", {"branch": "some_new_branch_zzz"})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_git_branch", kind="action")
def test_missing_branch_errors_clearly():
    """A real project but no branch -> the tool's own required-arg guard fires,
    naming the missing parameter."""
    r = call("create_git_branch", {"projectName": PROJECT})
    err = assert_error(r, "missing branch")
    assert_error_quality(err, names=["branch"], ctx="missing branch must name the parameter")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_git_branch", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project cannot resolve a repository -> 'not found' error
    naming the bad value and steering to list_projects."""
    r = call("create_git_branch", {"projectName": NONEXISTENT_PROJECT, "branch": "some_new_branch_zzz"})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_git_branch", kind="action")
def test_branch_already_exists_errors_without_creating_anything():
    """Targeting the branch currently checked out (guaranteed to exist) -> the
    findRef pre-check must reject BEFORE any Git.branchCreate() call runs. Ground
    truth for 'current' AND for 'nothing was created' both come from direct `git`
    calls, independent of the tool under test (anti-cheat): this proves the
    pre-check fires before any mutation, not just that the tool claims an error."""
    current = _git("rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    if not current or current == "HEAD":
        # Detached HEAD (the CI checkout): there is no named branch guaranteed to
        # exist to target here - covered by the unit tests and the live-gate
        # scenario instead. SKIP, not fail.
        raise E2ESkip("plugin repo is on a detached HEAD (CI checkout); the "
                      "already-exists path needs a named branch")

    branches_before = _git("branch", "--list").stdout

    r = call("create_git_branch", {"projectName": PROJECT, "branch": current})
    err = assert_error(r, "branch already exists")
    assert_error_quality(err, names=[current], suggests=["switch_git_branch"],
                         ctx="already-exists error must name the branch and steer to switch_git_branch")

    branches_after = _git("branch", "--list").stdout
    if branches_after != branches_before:
        raise AssertionError(
            "create_git_branch must not create/modify any branch when rejecting an "
            "already-existing name (findRef pre-check must fire before any mutation); "
            "before=%r after=%r" % (branches_before, branches_after))

    assert_no_diff("a rejected call must not touch the fixture or the plugin repo's branches")
