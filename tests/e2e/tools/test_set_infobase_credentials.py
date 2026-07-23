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

import os
import shutil
import tempfile

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    requires_live_infobase,
    e2e_test,
    PROJECT,
)

NONEXISTENT_PROJECT = "NoSuchProject_sic_zzz"
NONEXISTENT_APP_ID = "no_such_app_sic_zzz"

# #275: temp dir / name for the live "target a standalone-server application directly" round-trip.
_SRV_IB_DIR = os.path.join(tempfile.gettempdir(), "edt_sic_standalone_e2e")
_SRV_IB_NAME = "SicStandalone_e2e"


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


# ──────────────────────────────────────────────────────────────────────────────
# LIVE ROUND-TRIP (Tier-2 -- gated behind EDT_MCP_LIVE_INFOBASE=1)
# ──────────────────────────────────────────────────────────────────────────────

def _ensure_standalone_absent():
    """Best-effort pre/post clean: remove any leftover wst-server AND plain infobase registration
    from a prior crashed run, then the temp directory. Ignores the results."""
    call("delete_infobase",
         {"projectName": PROJECT, "infobaseName": _SRV_IB_NAME,
          "deleteRegistration": True, "confirm": True})
    call("delete_infobase",
         {"projectName": PROJECT, "infobaseName": _SRV_IB_NAME,
          "deleteRegistration": True, "confirm": True})
    shutil.rmtree(_SRV_IB_DIR, ignore_errors=True)


@e2e_test(tool="set_infobase_credentials", kind="action")
def test_live_standalone_server_application_id_roundtrip():
    """#275 happy path (Tier-2 / live-gated): set_infobase_credentials now accepts a standalone-
    server (wst-server) applicationId directly, not just a plain file/server infobase -- the
    widened InfobaseAccessSupport.storeCredentials(IApplication, ...) adapts the wst-server
    application to the InfobaseReference EDT's own launch path resolves against.

    On the headless CI fixture there is NO wst-server application (TestConfiguration only has the
    plain infobase used by the other tools' fixtures), so a CI-safe rejection-wording assert here
    would be indistinguishable from the existing 'nonexistent applicationId' matrix above -- it is
    therefore LIVE-GATED instead: it seeds a real standalone server (create_infobase
    applicationKind='standaloneServer' mode='register' over an existing infobase) and targets it
    directly.

    REQUIRES: EDT_MCP_LIVE_INFOBASE=1 (a live EDT stand with both a 1C platform runtime and a
    standalone-server runtime registered, and TestConfiguration open)."""
    requires_live_infobase("set_infobase_credentials standalone-server applicationId round-trip")

    _ensure_standalone_absent()
    try:
        # Seed: a plain FILE infobase, then a standalone server registered over it (no credentials
        # here -- this test exercises set_infobase_credentials targeting the app directly).
        r_seed = call("create_infobase",
                      {"projectName": PROJECT, "infobaseFile": _SRV_IB_DIR,
                       "infobaseName": _SRV_IB_NAME})
        assert_ok(r_seed, "seed plain infobase for the standalone-server credentials round-trip")

        r_reg = call("create_infobase",
                     {"projectName": PROJECT, "infobaseFile": _SRV_IB_DIR,
                      "infobaseName": _SRV_IB_NAME, "applicationKind": "standaloneServer",
                      "mode": "register"})
        assert_ok(r_reg, "register standalone server for the credentials round-trip")
        app_id = r_reg.structured.get("applicationId")
        assert app_id, "register result must carry an applicationId: %r" % r_reg.structured

        # Target the wst-server application DIRECTLY with set_infobase_credentials (#275) -- this is
        # the tool under test, not create_infobase's own credentials parameters.
        r = call("set_infobase_credentials",
                 {"projectName": PROJECT, "applicationId": app_id, "user": "Admin", "password": ""})
        assert_ok(r, "set_infobase_credentials against a wst-server application")
        sc = r.structured
        assert isinstance(sc, dict), "structured must be a dict: %r" % sc
        assert sc.get("applicationId") == app_id, "applicationId must be echoed: %r" % sc
        assert sc.get("user") == "Admin", "stored user must be echoed: %r" % sc
        assert sc.get("access") == "INFOBASE", "default access must be echoed: %r" % sc
    finally:
        _ensure_standalone_absent()

    assert_no_diff("the round-trip must not touch the committed fixture (TestConfiguration)")
