"""
e2e tests for set_branch_infobase (kind: action).

WHAT THE TOOL DOES
------------------
set_branch_infobase attaches or detaches an EXISTING infobase (application) to/from
a git branch CONTEXT via IInfobaseAssociationManager, so list_git_branches' bindings
section and switch_git_branch's automatic binding follow that branch.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success:  {"success": true, "action", "branch", "applicationId", "bound"?}
  error:    {"success": false, "error": "..."}

CI STRATEGY -- NEGATIVES ONLY, NO HAPPY-PATH ATTACH/DETACH
------------------------------------------------------------
!!! LOUD WARNING !!! Like list_git_branches/switch_git_branch, the CI fixture project
(PROJECT, "TestConfiguration") lives INSIDE the EDT-MCP plugin's OWN git working tree
(git-dir-discovery resolves the PLUGIN repo). A real "happy path" attach/detach would
record an association against the PLUGIN's own workspace-metadata store (EDT instance
preferences), which is stateful and not reset by the git-fixture cleanup this suite
relies on. This file therefore contains ONLY negative-path tests that are guaranteed
to be rejected BEFORE any IInfobaseAssociationManager mutation runs. The real
happy-path attach/detach (a scratch repo + a real associated base) is a LIVE, ATTENDED
gate run by hand on a throwaway stand -- never automated here.

Every test below asserts assert_no_diff(): a rejected call must never touch the
fixture (it also never touches ANY association state, but that has no git-visible
signal to assert on, unlike the file-tree tools).
"""

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

NONEXISTENT_PROJECT = "NoSuchProject_sbi_zzz"
NONEXISTENT_APPLICATION = "no_such_application_sbi_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (CI-safe: every case is rejected before any association mutation)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="set_branch_infobase", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard fires, names the parameter,
    and steers to list_projects."""
    r = call("set_branch_infobase", {"branch": "main", "applicationId": "x"})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName must name it and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_branch_infobase", kind="action")
def test_missing_branch_errors_clearly():
    """A real project but no branch -> the tool's own required-arg guard fires,
    naming the missing parameter."""
    r = call("set_branch_infobase", {"projectName": PROJECT, "applicationId": "x"})
    err = assert_error(r, "missing branch")
    assert_error_quality(err, names=["branch"], ctx="missing branch must name the parameter")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_branch_infobase", kind="action")
def test_missing_applicationid_errors_with_hint():
    """A real project and branch but no applicationId -> the required-arg guard
    fires, naming the parameter and steering to get_applications."""
    r = call("set_branch_infobase", {"projectName": PROJECT, "branch": "main"})
    err = assert_error(r, "missing applicationId")
    assert_error_quality(err, names=["applicationId"], suggests=["get_applications"],
                         ctx="missing applicationId must name it and steer to get_applications")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_branch_infobase", kind="action")
def test_invalid_action_errors_clearly():
    """An out-of-enum action value ('bogus') is rejected by the tool's own pure
    guard -- reachable even against a made-up project name, since it fires BEFORE
    any project/repository resolution."""
    r = call("set_branch_infobase", {
        "projectName": NONEXISTENT_PROJECT, "branch": "main",
        "applicationId": "x", "action": "bogus",
    })
    err = assert_error(r, "invalid action")
    assert_error_quality(err, names=["bogus", "attach", "detach"],
                         ctx="invalid action must name the bad value and the allowed ones")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_branch_infobase", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project cannot resolve a repository -> 'not found' error
    naming the bad value and steering to list_projects."""
    r = call("set_branch_infobase", {
        "projectName": NONEXISTENT_PROJECT, "branch": "main", "applicationId": "x",
    })
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_branch_infobase", kind="action")
def test_nonexistent_application_errors_with_hint():
    """A real project + branch but an unknown applicationId -> the application
    resolution fails, naming the bad id and steering to get_applications, BEFORE
    any IInfobaseAssociationManager call runs."""
    r = call("set_branch_infobase", {
        "projectName": PROJECT, "branch": "main", "applicationId": NONEXISTENT_APPLICATION,
    })
    err = assert_error(r, "nonexistent application")
    assert_error_quality(err, names=[NONEXISTENT_APPLICATION], suggests=["get_applications"],
                         ctx="nonexistent application must be named and steer to get_applications")
    assert_no_diff("a rejected call must not touch the fixture")
