"""
e2e tests for delete_infobase (kind: action).

WHAT THE TOOL DOES
------------------
delete_infobase dissociates a FILE infobase from a configuration project and,
optionally, deregisters it from the global EDT Infobases list. It is the inverse
of create_infobase and the cleanup half of the create/delete round-trip.

Destructive, guarded by a confirm-preview: a bare call (confirm=false) reports what
would be removed WITHOUT changing anything; only confirm=true performs the removal.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  preview:  {"success": true, "action": "preview", "confirmationRequired": true,
             "project", "applicationId", "infobaseName", "deleteRegistration", "message"}
  deleted:  {"success": true, "action": "deleted", "project", "applicationId",
             "infobaseName", "deleteRegistration", "message"}
  error:    {"success": false, "error": "..."}

CI STRATEGY
-----------
The negative matrix and confirm-gate tests are CI-safe (no platform needed). The
live happy-path (full create+delete round-trip) is covered by test_create_infobase.py
and gated behind EDT_MCP_LIVE_INFOBASE=1.

NOTE: delete_infobase never writes TestConfiguration source files. All calls
leave the project tree clean: assert_no_diff() on all paths.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

NONEXISTENT_PROJECT = "NoSuchProject_di_zzz"
NONEXISTENT_APP_ID = "no_such_app_di_zzz"
NONEXISTENT_IB_NAME = "no_such_infobase_di_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# CONTRACT / NEGATIVE
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_infobase", kind="action")
def test_missing_projectname_errors_with_hint():
    """No projectName -> the shared required-arg guard fires, names the parameter,
    and steers to list_projects."""
    r = call("delete_infobase", {"applicationId": "someApp"})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName: must name param and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_infobase", kind="action")
def test_missing_both_id_and_name_errors_clearly():
    """Neither applicationId nor infobaseName provided -> the tool's own guard fires
    with an actionable message naming both parameters."""
    r = call("delete_infobase", {"projectName": PROJECT})
    err = assert_error(r, "missing applicationId and infobaseName")
    assert_error_quality(err, names=["applicationId", "infobaseName"],
                         ctx="must name both parameters that can satisfy the requirement")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_infobase", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project -> 'Project not found' with a list_projects hint."""
    r = call("delete_infobase",
             {"projectName": NONEXISTENT_PROJECT, "applicationId": NONEXISTENT_APP_ID,
              "confirm": True})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_infobase", kind="action")
def test_nonexistent_application_id_errors_with_hint():
    """A real project but a non-existent applicationId -> 'Application not found' with
    a get_applications hint. Exercises the application-resolution chain."""
    r = call("delete_infobase",
             {"projectName": PROJECT, "applicationId": NONEXISTENT_APP_ID, "confirm": True})
    err = assert_error(r, "nonexistent applicationId")
    assert_error_quality(err, names=[NONEXISTENT_APP_ID], suggests=["get_applications"],
                         ctx="nonexistent applicationId is named with get_applications hint")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_infobase", kind="action")
def test_nonexistent_infobase_name_errors_with_hint():
    """A real project but a non-existent infobaseName -> actionable not-found error
    suggesting get_applications."""
    r = call("delete_infobase",
             {"projectName": PROJECT, "infobaseName": NONEXISTENT_IB_NAME, "confirm": True})
    err = assert_error(r, "nonexistent infobaseName")
    assert_error_quality(err, names=[NONEXISTENT_IB_NAME], suggests=["get_applications"],
                         ctx="nonexistent infobaseName is named with get_applications hint")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="delete_infobase", kind="action")
def test_preview_on_nonexistent_is_not_ok():
    """Even without confirm=true, a non-existent application must error (not preview).
    The existence check precedes the confirm-preview gate to give a trustworthy preview."""
    r = call("delete_infobase",
             {"projectName": PROJECT, "applicationId": NONEXISTENT_APP_ID})
    # The existence check must reject this, not preview it.
    assert_error(r, "preview on nonexistent app must error, not preview")
    assert_no_diff("a rejected call must not touch the fixture")
