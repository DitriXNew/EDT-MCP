"""
e2e tests for push_git_branch (kind: action).

WHAT THE TOOL DOES
------------------
push_git_branch pushes a project's git branch (or an explicit refspec) to a remote
via a headless JGit PushCommand, on the shared bounded GitRemoteSupport background Job.
Both `remote` and `refspec` are REQUIRED with no defaulting (the no-autonomous-push
guard); `force` is opt-in. SSH auth is transparent (ssh-agent/~/.ssh); HTTPS uses the
optional username/token, else fails fast (never a login dialog). A rejected push
(e.g. RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD) is reported as an error, never
swallowed as success.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success:  {"success": true, "remote", "resolvedRefspec", "forced", "pushed", "updates"}
  error:    {"success": false, "error": "..."}

CI STRATEGY -- NEGATIVES ONLY, NO ACTUAL PUSH
----------------------------------------------
!!! LOUD WARNING !!! Like list_git_branches/switch_git_branch/create_git_branch, the CI
fixture project (PROJECT, "TestConfiguration") lives INSIDE the EDT-MCP plugin's OWN git
working tree (git-dir-discovery resolves the PLUGIN repo, same as those files' module
docstrings). A real push here would push the PLUGIN REPOSITORY ITSELF to some remote --
an actual network push of the running clone. This file therefore contains ONLY negative
cases that the tool rejects BEFORE any PushCommand.call() reaches the network:

  * missing projectName / remote / refspec  -> required-arg guards fire first
  * a non-existent project                  -> repository resolution fails first

The real happy path (push a scratch repo's branch to a local bare-repo remote) and the
non-fast-forward rejection (RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> an
actionable error, never success) are a LIVE, ATTENDED gate run by hand on a throwaway
stand -- never automated here, because both require an ACTUAL push that would otherwise
push the plugin repo. The rejected-push mapping itself is unit-tested
(PushGitBranchToolTest.testRejectionStatusesAreNotSuccess / testDescribeUpdate*).

Every test below asserts assert_no_diff(): a rejected call must never touch the fixture
(and never push the plugin repo).
"""

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

NONEXISTENT_PROJECT = "NoSuchProject_pgb_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (CI-safe: every case is rejected before any PushCommand runs)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="push_git_branch", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard fires, names the parameter,
    and steers to list_projects."""
    r = call("push_git_branch", {"remote": "origin", "refspec": "main"})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="push_git_branch", kind="action")
def test_missing_remote_errors_clearly():
    """A real project but no remote -> the no-autonomous-push guard fires: `remote`
    is required with no defaulting, so the required-arg guard rejects and names it."""
    r = call("push_git_branch", {"projectName": PROJECT, "refspec": "main"})
    err = assert_error(r, "missing remote")
    assert_error_quality(err, names=["remote"],
                         ctx="missing remote must name the parameter (the no-autonomous-push guard)")
    assert_no_diff("a rejected call must not touch the fixture or push the plugin repo")


@e2e_test(tool="push_git_branch", kind="action")
def test_missing_refspec_errors_clearly():
    """A real project and a remote but no refspec -> the no-autonomous-push guard fires:
    `refspec` is required with no defaulting, so the required-arg guard rejects and names it."""
    r = call("push_git_branch", {"projectName": PROJECT, "remote": "origin"})
    err = assert_error(r, "missing refspec")
    assert_error_quality(err, names=["refspec"],
                         ctx="missing refspec must name the parameter (the no-autonomous-push guard)")
    assert_no_diff("a rejected call must not touch the fixture or push the plugin repo")


@e2e_test(tool="push_git_branch", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project cannot resolve a repository -> 'not found' error naming
    the bad value and steering to list_projects, rejected BEFORE any push."""
    r = call("push_git_branch",
             {"projectName": NONEXISTENT_PROJECT, "remote": "origin", "refspec": "main"})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture or push the plugin repo")
