"""
e2e tests for create_git_repository (kind: action).

WHAT THE TOOL DOES
------------------
create_git_repository bootstraps a git repository for an EDT project. Init mode (no
`url`) runs `git init` at an EXISTING open project's location and connects it to EGit,
rejecting a project that is already inside a git working tree (a walk-up findGitDir
match). Clone mode (`url` + `targetPath`) clones a remote on a bounded background Job
and then imports/connects the project.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success:  {"success": true, "mode", "repositoryPath", "shared", "project"?, "remoteUrl"?,
             "imported"?, "message"?}
  error:    {"success": false, "error": "..."}
  NOTE: clone mode adds "imported" (bool). "project" is present only when imported (init mode always
        imports; clone with a name collision / import failure -> imported=false and NO "project").

CI STRATEGY -- NEGATIVES ONLY, NO HAPPY-PATH INIT/CLONE
-------------------------------------------------------
!!! LOUD WARNING !!! Like list_git_branches / switch_git_branch / create_git_branch,
the CI fixture project (PROJECT, "TestConfiguration") lives INSIDE the EDT-MCP plugin's
OWN git working tree (git-dir-discovery walks up to the PLUGIN repo, same as those
files' module docstrings). A real happy-path INIT on it is therefore REJECTED by the
tool's own findGitDir pre-check (the project is already inside the plugin repository) --
and a genuine `git init` there would litter the plugin tree with a nested repository.
Clone mode needs a live network remote. This file therefore contains ONLY negative-path
tests that are guaranteed to be rejected BEFORE any git init / clone runs. The real
happy path (init a scratch project, or clone a local bare-repo fixture) is a LIVE,
ATTENDED gate run by hand on a throwaway stand -- never automated here.

The "already inside a repository" test independently verifies (os.path.isdir) that NO
new .git directory was created under the project dir -- proving the findGitDir pre-check
fires before any InitCommand runs, not just that the tool CLAIMS an error (anti-cheat).

Every test below asserts assert_no_diff(): a rejected call must never touch the fixture.
"""

import os

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
    PROJECT_DIR,
)

NONEXISTENT_PROJECT = "NoSuchProject_cgr_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (CI-safe: every case is rejected before any init/clone runs)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_git_repository", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard fires, names the parameter,
    and steers to list_projects."""
    r = call("create_git_repository", {})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_git_repository", kind="action")
def test_nonexistent_project_init_errors_with_hint():
    """Init mode with a non-existent project -> 'not found' error naming the bad value
    and steering to list_projects (no repository is created)."""
    r = call("create_git_repository", {"projectName": NONEXISTENT_PROJECT})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_git_repository", kind="action")
def test_existing_repo_rejected_without_creating_anything():
    """Init on the fixture project (which lives inside the plugin's own git tree) must
    be rejected by the findGitDir pre-check BEFORE any InitCommand runs. Independent
    ground truth (os.path.isdir on the project's own .git) proves nothing was created --
    not just that the tool claims an error (anti-cheat)."""
    project_git = os.path.join(PROJECT_DIR, ".git")
    existed_before = os.path.isdir(project_git)

    r = call("create_git_repository", {"projectName": PROJECT})
    err = assert_error(r, "already inside a git repository")
    assert_error_quality(err, names=[PROJECT], suggests=["get_git_status"],
                         ctx="already-in-a-repo error must name the project and steer to an inspect tool")

    if os.path.isdir(project_git) and not existed_before:
        raise AssertionError(
            "create_git_repository must not create a nested .git under the project when the "
            "findGitDir pre-check rejects an already-versioned project; a new .git appeared at %r"
            % project_git)

    assert_no_diff("a rejected call must not touch the fixture or create a nested repository")


@e2e_test(tool="create_git_repository", kind="action")
def test_clone_without_targetpath_errors():
    """Clone mode (url given) with no targetPath -> rejected BEFORE any network access,
    naming the missing targetPath parameter. No remote is contacted."""
    r = call("create_git_repository",
             {"projectName": PROJECT, "url": "https://example.invalid/repo.git"})
    err = assert_error(r, "clone without targetPath")
    assert_error_quality(err, names=["targetPath"],
                         ctx="clone with no targetPath must name the missing parameter")
    assert_no_diff("a rejected call must not touch the fixture")
