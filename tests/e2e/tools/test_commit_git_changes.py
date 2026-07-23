"""
e2e tests for commit_git_changes (kind: action).

WHAT THE TOOL DOES
------------------
commit_git_changes stages a project's ON-DISK git changes (all tracked-modified
files via all=true, and/or explicit paths[]) and records a commit, returning its
SHA. It commits only what is written to disk (the caller must save/resync the EDT
model first). 'Nothing to commit' and a missing user.name/user.email are actionable
errors, never a fake success. No auth, no background Job, no workspace refresh.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success:  {"success": true, "commitId": "<40-hex>", "branch", "stagedFiles"}
  error:    {"success": false, "error": "..."}

CI STRATEGY -- NEGATIVES ONLY, NO HAPPY-PATH COMMIT
----------------------------------------------------
!!! LOUD WARNING !!! Like list_git_branches/switch_git_branch/create_git_branch, the
CI fixture project (PROJECT, "TestConfiguration") lives INSIDE the EDT-MCP plugin's
OWN git working tree (git-dir-discovery resolves the PLUGIN repo -- see those files'
module docstrings). A real "happy path" commit would therefore record a COMMIT IN THE
PLUGIN REPOSITORY ITSELF while CI is running from it. This file therefore contains
ONLY tests that are guaranteed NOT to record a commit: the argument/identity guards
(rejected before any staging), plus a nothing-to-commit probe that is GUARDED to run
only when the whole repo is clean (so nothing is staged) and asserts HEAD did not move
(anti-cheat). The real happy-path commit (a scratch repo: modify a file, commit,
assert the SHA + a subsequent get_git_status 'clean') is a LIVE, ATTENDED gate run by
hand on a throwaway stand -- and is covered at the unit level by CommitGitChangesToolTest
against a REAL temporary repository -- never automated here.

Every test asserts assert_no_diff(): a rejected call must never touch the fixture.
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

NONEXISTENT_PROJECT = "NoSuchProject_cgc_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (CI-safe: every case is rejected before any commit is recorded)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="commit_git_changes", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard fires, names the parameter,
    and steers to list_projects."""
    r = call("commit_git_changes", {"message": "some message"})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="commit_git_changes", kind="action")
def test_missing_message_errors_clearly():
    """A real project but no message -> the required-arg guard fires, naming the
    missing parameter."""
    r = call("commit_git_changes", {"projectName": PROJECT})
    err = assert_error(r, "missing message")
    assert_error_quality(err, names=["message"], ctx="missing message must name the parameter")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="commit_git_changes", kind="action")
def test_blank_message_is_rejected():
    """A whitespace-only message is not a real message -> rejected naming 'message'
    (the guard also catches this, before any staging)."""
    r = call("commit_git_changes", {"projectName": PROJECT, "message": "   "})
    err = assert_error(r, "blank message")
    assert_error_quality(err, names=["message"], ctx="blank message must be rejected naming the parameter")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="commit_git_changes", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project cannot resolve a repository -> 'not found' error
    naming the bad value and steering to list_projects."""
    r = call("commit_git_changes", {"projectName": NONEXISTENT_PROJECT, "message": "x"})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="commit_git_changes", kind="action")
def test_nothing_to_commit_is_an_error_without_moving_head():
    """With NOTHING staged, commit_git_changes must refuse with an actionable
    'nothing to commit' error and record NO commit -- never a fake success, never an
    empty commit.

    GUARDED: this probe stages nothing (all defaults false, no paths[]), so it is only
    safe when the whole plugin repo is already clean (a dirty index would otherwise be
    committed). It therefore SKIPs unless `git status --porcelain` is empty, and
    independently verifies via a direct `git rev-parse HEAD` (before/after) that HEAD
    did not move -- ground truth from git itself, not the tool under test (anti-cheat)."""
    if _git("status", "--porcelain").stdout.strip():
        raise E2ESkip("plugin repo working tree is not clean; the nothing-to-commit probe "
                      "needs an empty index so it cannot accidentally record a commit")

    head_before = _git("rev-parse", "HEAD").stdout.strip()

    r = call("commit_git_changes", {"projectName": PROJECT, "message": "e2e nothing-to-commit probe"})
    err = assert_error(r, "nothing to commit")
    assert_error_quality(err, names=["nothing to commit"], suggests=["get_git_status"],
                         ctx="nothing-to-commit must be an actionable error, not a silent no-op")

    head_after = _git("rev-parse", "HEAD").stdout.strip()
    if head_after != head_before:
        raise AssertionError(
            "commit_git_changes must NOT record a commit when nothing is staged "
            "(HEAD must not move); before=%r after=%r" % (head_before, head_after))

    assert_no_diff("a refused commit must not touch the fixture or the plugin repo")
