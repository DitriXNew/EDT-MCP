"""
e2e tests for set_infobase_credentials (kind: action).

WHAT THE TOOL DOES
------------------
set_infobase_credentials only PERSISTS the infobase connection credentials
(user/password) into EDT's per-infobase access settings. The store path is
DESIGNER-FREE: it commits via IInfobaseAccessManager.updateSettings and never opens,
connects to, or validates a designer session. Those stored credentials are later read
by EDT when update_database / debug_launch authenticate the designer agent against an
infobase that has a user list (issue #194) — but that authentication happens in those
other tools, not here. The tool selects an EXISTING infobase user (does not create
users); an empty password is valid (demo bases). Target by launchConfigurationName
(preferred) or projectName + applicationId.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  stored: {"success": true, "project", "applicationId", "applicationName",
           "user", "access", "passwordSet", "message"}
  error:  {"success": false, "error": "..."}

CI STRATEGY
-----------
The negative/contract matrix is CI-safe (no platform, no infobase needed): it exercises
the argument guards and the project/application resolution chain. The live happy path
(persist credentials) needs a registered infobase with a user list and is verified on
the EDT stand, not in CI.

HANG FIX (#194 follow-up)
-------------------------
The previously-reported hang was NOT in the credential store itself (updateSettings is
designer-free). It was an EDT BACKGROUND application-update-state recompute provoked by
running the model reads synchronously on the unbounded MCP worker thread, which could
loop forever waiting on a designer connection. The store is now wrapped in a bounded
background Eclipse Job joined with a short timeout: credentials are persisted FIRST, and
on timeout the recorded success is returned (else a graceful error). This is a server-side
concern only — the wire surface (params / output fields) is unchanged, so this matrix and
its assert_no_diff() stay exactly as-is.

NOTE: the tool writes EDT's per-infobase access settings (secure storage), never
TestConfiguration source files — every call leaves the project tree clean: assert_no_diff().
"""

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

NONEXISTENT_PROJECT = "NoSuchProject_sic_zzz"
NONEXISTENT_APP_ID = "no_such_app_sic_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# CONTRACT / NEGATIVE
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="set_infobase_credentials", kind="action")
def test_missing_target_errors_with_hint():
    """No launchConfigurationName and no projectName -> names projectName and steers
    to the launchConfigurationName alternative."""
    r = call("set_infobase_credentials", {"user": "Admin"})
    err = assert_error(r, "missing target")
    assert_error_quality(err, names=["projectName"], suggests=["launchConfigurationName"],
                         ctx="missing target: name projectName and the launch-config alternative")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_infobase_credentials", kind="action")
def test_missing_application_id_errors_with_hint():
    """projectName given but no applicationId and no launchConfigurationName -> names
    applicationId and steers to get_applications / list_configurations."""
    r = call("set_infobase_credentials", {"projectName": PROJECT, "user": "Admin"})
    err = assert_error(r, "missing applicationId")
    assert_error_quality(err, names=["applicationId"], suggests=["get_applications"],
                         ctx="missing applicationId: name param and steer to get_applications")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_infobase_credentials", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project -> 'Project not found' naming the project, with a
    list_projects hint (the shared resolution chain)."""
    r = call("set_infobase_credentials",
             {"projectName": NONEXISTENT_PROJECT, "applicationId": NONEXISTENT_APP_ID,
              "user": "Admin"})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="set_infobase_credentials", kind="action")
def test_nonexistent_application_id_errors_with_hint():
    """A real project but a non-existent applicationId -> 'Application not found' with
    a get_applications hint. Exercises the application-resolution chain."""
    r = call("set_infobase_credentials",
             {"projectName": PROJECT, "applicationId": NONEXISTENT_APP_ID, "user": "Admin"})
    err = assert_error(r, "nonexistent applicationId")
    assert_error_quality(err, names=[NONEXISTENT_APP_ID], suggests=["get_applications"],
                         ctx="nonexistent applicationId is named with get_applications hint")
    assert_no_diff("a rejected call must not touch the fixture")
