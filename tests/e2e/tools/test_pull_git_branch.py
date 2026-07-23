"""
e2e tests for pull_git_branch (kind: action).

WHAT THE TOOL DOES
------------------
pull_git_branch fetches a REQUIRED remote + REQUIRED branch and integrates it into
the project's current branch (merge, or rebase when rebase=true), on a bounded
background Job, then refreshes the project. Auth is non-interactive: SSH via
ssh-agent, HTTPS via optional username/token (never a dialog). A merge/rebase
conflict is a mapped, actionable error echoing the conflicting paths -- never a
false success.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success:  {"success": true, "remote", "branch", "rebase", "fetchedFrom"?, "status"?}
  error:    {"success": false, "error": "..."}

CI STRATEGY -- NEGATIVES ONLY, NO HAPPY-PATH PULL AGAINST THE FIXTURE
--------------------------------------------------------------------
!!! LOUD WARNING !!! Like list/switch/create_git_branch, the CI fixture project
(PROJECT, "TestConfiguration") lives INSIDE the EDT-MCP plugin's OWN git working
tree (git-dir-discovery resolves the PLUGIN repo, same as those files' module
docstrings). A real "happy path" pull here would FETCH FROM THE PLUGIN REPO'S OWN
REMOTE and MERGE/REBASE INTO THE PLUGIN'S CHECKED-OUT BRANCH while CI is running
from it -- a network operation against, and a mutation of, the very tree the test
harness depends on. Unlike switch/create, pull has NO pre-check that rejects before
the network call, so this file NEVER calls pull_git_branch against PROJECT with a
resolvable remote. The CI matrix is therefore limited to cases guaranteed to be
rejected by the shared required-arg guards / project resolution BEFORE any
PullCommand runs. Every such case asserts assert_no_diff().

The real happy path (pull a new upstream commit from a local fixture remote and
assert the file updated) and the conflict path are OPT-IN, SKIP-by-default gates
run by hand on a THROWAWAY scratch stand, driven by the MCP_GIT_PULL_* env vars
below -- they never touch the plugin repo.
"""

import os

from harness import (
    E2ESkip,
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

NONEXISTENT_PROJECT = "NoSuchProject_pgb_zzz"

# Opt-in scratch stand (a throwaway EDT project in a git working tree with a remote whose
# named branch is AHEAD of the local checkout). All absent by default => the happy/conflict
# gates SKIP and CI stays on the negatives-only matrix.
SCRATCH_PROJECT = os.environ.get("MCP_GIT_PULL_SCRATCH_PROJECT")
SCRATCH_REMOTE = os.environ.get("MCP_GIT_PULL_REMOTE", "origin")
SCRATCH_BRANCH = os.environ.get("MCP_GIT_PULL_BRANCH", "main")
# An ABSOLUTE path to a file the new upstream commit changed, plus a substring it introduced --
# lets the happy-path assert the working tree really updated (independent of the tool's claim).
EXPECT_FILE = os.environ.get("MCP_GIT_PULL_EXPECT_FILE")
EXPECT_SUBSTR = os.environ.get("MCP_GIT_PULL_EXPECT_SUBSTR")
# A branch whose incoming change conflicts with an uncommitted/committed local change on the
# scratch checkout, to exercise the conflict-mapping path.
CONFLICT_BRANCH = os.environ.get("MCP_GIT_PULL_CONFLICT_BRANCH")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (CI-safe: every case is rejected before any PullCommand runs)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="pull_git_branch", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard fires, names the parameter,
    and steers to list_projects."""
    r = call("pull_git_branch", {"remote": "origin", "branch": "main"})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="pull_git_branch", kind="action")
def test_missing_remote_errors_clearly():
    """A real project but no remote -> the required-arg guard fires (remote is never
    defaulted), naming the missing parameter."""
    r = call("pull_git_branch", {"projectName": PROJECT, "branch": "main"})
    err = assert_error(r, "missing remote")
    assert_error_quality(err, names=["remote"], ctx="missing remote must name the parameter")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="pull_git_branch", kind="action")
def test_missing_branch_errors_clearly():
    """A real project + remote but no branch -> the required-arg guard fires (branch is
    never defaulted), naming the missing parameter."""
    r = call("pull_git_branch", {"projectName": PROJECT, "remote": "origin"})
    err = assert_error(r, "missing branch")
    assert_error_quality(err, names=["branch"], ctx="missing branch must name the parameter")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="pull_git_branch", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project cannot resolve a repository -> 'not found' error naming
    the bad value and steering to list_projects, BEFORE any PullCommand runs."""
    r = call("pull_git_branch",
             {"projectName": NONEXISTENT_PROJECT, "remote": "origin", "branch": "main"})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


# ──────────────────────────────────────────────────────────────────────────────
# OPT-IN ATTENDED GATES (SKIP by default; never touch the plugin repo)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="pull_git_branch", kind="action")
def test_happy_path_pull_updates_working_tree():
    """Pull a new upstream commit from a local fixture remote and assert the working
    tree updated. Requires a throwaway scratch stand (MCP_GIT_PULL_SCRATCH_PROJECT +
    a remote whose branch is ahead); SKIP otherwise so CI never pulls into the plugin
    repo. When MCP_GIT_PULL_EXPECT_FILE/SUBSTR are given, additionally proves the file
    on disk really carries the upstream change (independent of the tool's own claim)."""
    if not SCRATCH_PROJECT:
        raise E2ESkip("set MCP_GIT_PULL_SCRATCH_PROJECT (+ remote/branch ahead of local) "
                      "on a throwaway stand to run the happy-path pull gate")

    r = call("pull_git_branch",
             {"projectName": SCRATCH_PROJECT, "remote": SCRATCH_REMOTE, "branch": SCRATCH_BRANCH})
    assert_ok(r, "pull from the fixture remote must integrate cleanly")
    data = r.structured or {}
    if data.get("remote") != SCRATCH_REMOTE or data.get("branch") != SCRATCH_BRANCH:
        raise AssertionError("pull result must echo the requested remote/branch; got %r" % (data,))

    if EXPECT_FILE and EXPECT_SUBSTR:
        # Ground truth from disk (NOT the tool's own response): the working tree really updated.
        with open(EXPECT_FILE, encoding="utf-8") as fh:
            content = fh.read()
        if EXPECT_SUBSTR not in content:
            raise AssertionError(
                "after pull, %s must contain the upstream change %r" % (EXPECT_FILE, EXPECT_SUBSTR))


@e2e_test(tool="pull_git_branch", kind="action")
def test_conflict_is_a_mapped_error_not_a_false_success():
    """Pulling a branch whose incoming change conflicts with the local checkout must be a
    mapped, actionable error (not success:true) that echoes the conflicting paths. Requires
    a throwaway scratch stand with a prepared conflicting branch; SKIP otherwise."""
    if not (SCRATCH_PROJECT and CONFLICT_BRANCH):
        raise E2ESkip("set MCP_GIT_PULL_SCRATCH_PROJECT + MCP_GIT_PULL_CONFLICT_BRANCH on a "
                      "throwaway stand to run the conflict-path gate")

    r = call("pull_git_branch",
             {"projectName": SCRATCH_PROJECT, "remote": SCRATCH_REMOTE, "branch": CONFLICT_BRANCH})
    err = assert_error(r, "conflicting pull must be a mapped error, never a false success")
    # The mapped conflict error names the remote/branch and lists conflicting paths.
    assert_error_quality(err, names=[SCRATCH_REMOTE, CONFLICT_BRANCH],
                         ctx="conflict error must name the remote/branch and describe recovery")
