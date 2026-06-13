"""
e2e tests for create_infobase (kind: action).

WHAT THE TOOL DOES
------------------
create_infobase creates a new FILE 1C infobase (database) on disk and binds it to an
EDT configuration project, so it surfaces via get_applications as an application of
type com.e1c.g5.dt.applications.type.infobase. The creation shells out to
IInfobaseCreationOperation.perform(...), which invokes the 1cv8 thick-client binary
(1cv8 CREATEINFOBASE). This requires a registered 1C:Enterprise platform runtime in
EDT; without one, the tool returns an actionable error.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success path: {"success": true, "action": "created", "project", "infobaseFile",
                 "infobaseName", "applications": [...], ["applicationId": "..."],
                 "message"}
  error path:   {"success": false, "error": "..."}

CI / HEADLESS STRATEGY (IMPORTANT)
-----------------------------------
The happy-path create round-trip requires a 1C platform runtime that headless CI
lacks. The primary tests (the ones CI exercises) are the NEGATIVE matrix — they
exercise the full validation chain without needing a platform and prove the tool is
wired correctly:
  - missing required projectName  -> "projectName is required"
  - missing required infobaseFile -> "infobaseFile is required"
  - non-existent project          -> "Project not found: <name>"
  - no-platform-runtime probe     -> the actionable platform-not-registered error
    (this is the path CI actually exercises for create_infobase — it proves that
    the platform probe fires fast and cleanly instead of hanging)

The live happy-path (create -> verify via get_applications -> delete round-trip) is
Tier-2 / stand-only, gated behind EDT_MCP_LIVE_INFOBASE=1 (the same gate as
test_live_roundtrip.py). It is documented here but SKIPPED in headless CI.

NOTE: create_infobase never writes TestConfiguration source files (it targets the
infobase, which lives in the EDT workspace, not in git). Every call in this file
leaves the project tree clean: assert_no_diff() on all paths.
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
    LIVE_INFOBASE,
)

# Sentinel for a project name that does not exist in the workspace.
NONEXISTENT_PROJECT = "NoSuchProject_ci_zzz"

# A temp directory for the new infobase; used only in the live round-trip.
_LIVE_IB_DIR = os.path.join(tempfile.gettempdir(), "edt_create_ib_e2e")
_LIVE_IB_NAME = "CreateInfobase_e2e"


def _ensure_infobase_absent():
    """Best-effort pre/post clean: dissociate + delete a leftover infobase from a
    prior crashed run. Ignores the result."""
    call("delete_infobase",
         {"projectName": PROJECT, "infobaseName": _LIVE_IB_NAME,
          "deleteRegistration": True, "confirm": True})
    shutil.rmtree(_LIVE_IB_DIR, ignore_errors=True)


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (CI-safe — no platform required)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_infobase", kind="action")
def test_missing_projectname_errors_with_hint():
    """Required projectName omitted -> requireArgument guard -> actionable error naming
    the parameter, before any platform or workspace access."""
    r = call("create_infobase", {"infobaseFile": "C:\\infobases\\test"})
    err = assert_error(r, "missing projectName")
    assert_error_quality(err, names=["projectName"], suggests=["list_projects"],
                         ctx="missing projectName: must name param and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_infobase", kind="action")
def test_missing_infobasefile_errors_clearly():
    """Required infobaseFile omitted -> requireArgument guard fires."""
    r = call("create_infobase", {"projectName": PROJECT})
    err = assert_error(r, "missing infobaseFile")
    assert_error_quality(err, names=["infobaseFile"],
                         ctx="missing infobaseFile: must name the parameter")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_infobase", kind="action")
def test_nonexistent_project_errors_with_hint():
    """A syntactically valid but non-existent project -> 'Project not found' with a
    list_projects hint. Exercises the project-resolution chain."""
    r = call("create_infobase",
             {"projectName": NONEXISTENT_PROJECT, "infobaseFile": "C:\\infobases\\test"})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_infobase", kind="action")
def test_no_platform_runtime_errors_actionably():
    """On a headless CI stand (no 1C platform runtime registered in EDT), the platform
    probe must fire fast with an actionable 'No 1C platform runtime is registered' error
    instead of hanging. This is the primary CI contract for create_infobase.

    On a live stand WITH a platform registered, this test is expected to PASS (the
    platform probe succeeds and execution moves on to actually try to create the
    infobase) — in that case a later error (e.g. the project itself) may fire instead.
    We assert the probe contract only when no platform is available (headless CI).

    Design note: we cannot reliably distinguish "probe fired and said no platform" from
    "probe passed but later step failed" in a single call without inspecting the exact
    error text. So this test gates on NOT LIVE_INFOBASE (headless only) to avoid a
    false failure on the stand."""
    if LIVE_INFOBASE:
        # On the stand the platform is registered and the probe passes; skip this
        # specific test (the live round-trip covers the success path).
        from harness import E2ESkip
        raise E2ESkip(
            "no-platform-runtime test skipped on the live stand "
            "(a platform IS registered; the probe would pass)")

    # On headless CI: provide a REAL, open project and a valid path so every
    # earlier guard passes and execution reaches the platform probe.
    r = call("create_infobase",
             {"projectName": PROJECT, "infobaseFile": "C:\\infobases\\ci_probe_test"})
    err = assert_error(r, "no-platform-runtime probe")
    # The actionable error must name the thing that is missing and tell the user what
    # to do about it ("Register" / "Preferences" / "platform").
    assert_error_quality(err, names=["platform"],
                         ctx="no-platform error must name 'platform' so the user knows what is missing")
    assert_no_diff("a rejected call must not touch the fixture")


# ──────────────────────────────────────────────────────────────────────────────
# LIVE ROUND-TRIP (Tier-2 — gated behind EDT_MCP_LIVE_INFOBASE=1)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_infobase", kind="action")
def test_live_create_verify_delete_roundtrip():
    """Full create -> verify via get_applications -> delete round-trip.

    REQUIRES: EDT_MCP_LIVE_INFOBASE=1 (a live EDT stand with a 1C platform runtime
    registered and the TestConfiguration project open).

    This test:
    1. Creates a new FILE infobase at a temp directory.
    2. Verifies it appears in get_applications as type …type.infobase with an
       applicationId in the result.
    3. Removes it via delete_infobase (preview, then confirm).
    4. Verifies it is gone from get_applications.
    5. Cleans up the temp directory.

    NOTE: this test drives the 1cv8 CREATEINFOBASE process (heavy, ~10-30 s) and
    is NOT run in headless CI. The result proves that the platform probe, background
    Job, association, and read-back chain all work end-to-end on the live stand.
    """
    requires_live_infobase("create_infobase live round-trip")

    _ensure_infobase_absent()
    try:
        # 1. Create
        r_create = call("create_infobase",
                        {"projectName": PROJECT,
                         "infobaseFile": _LIVE_IB_DIR,
                         "infobaseName": _LIVE_IB_NAME})
        assert_ok(r_create, "create_infobase live")
        sc = r_create.structured
        assert isinstance(sc, dict), "structured must be a dict: %r" % sc
        assert sc.get("action") == "created", "action must be 'created': %r" % sc
        assert sc.get("project") == PROJECT, "project must be echoed: %r" % sc
        assert os.path.isdir(_LIVE_IB_DIR), \
            "infobase directory must exist after creation: %s" % _LIVE_IB_DIR

        # 2. Verify via get_applications
        r_apps = call("get_applications", {"projectName": PROJECT})
        assert_ok(r_apps, "get_applications after create")
        apps = r_apps.structured.get("applications", [])
        ib_type = "com.e1c.g5.dt.applications.type.infobase"
        found = [a for a in apps if a.get("name") == _LIVE_IB_NAME
                 and a.get("type") == ib_type]
        assert found, \
            ("new infobase '%s' must appear in get_applications with type %s; got: %r"
             % (_LIVE_IB_NAME, ib_type, apps))

        new_app_id = found[0].get("id")
        assert new_app_id, "newly created infobase must carry an id in get_applications"

        # applicationId in the create result must match.
        result_app_id = sc.get("applicationId")
        if result_app_id:
            assert result_app_id == new_app_id, \
                ("create result applicationId %r must match get_applications id %r"
                 % (result_app_id, new_app_id))

        # 3. Preview delete (must NOT remove).
        r_prev = call("delete_infobase",
                      {"projectName": PROJECT, "applicationId": new_app_id})
        assert_ok(r_prev, "delete_infobase preview")
        assert r_prev.structured.get("action") == "preview", \
            "no-confirm delete must return action='preview'"
        assert r_prev.structured.get("confirmationRequired") is True, \
            "preview must set confirmationRequired=true"

        # 4. Confirm delete.
        r_del = call("delete_infobase",
                     {"projectName": PROJECT, "applicationId": new_app_id,
                      "deleteRegistration": True, "confirm": True})
        assert_ok(r_del, "delete_infobase confirm")
        assert r_del.structured.get("action") == "deleted", \
            "confirm delete must report action='deleted'"

        # 5. Verify gone.
        r_apps2 = call("get_applications", {"projectName": PROJECT})
        assert_ok(r_apps2, "get_applications after delete")
        apps2 = r_apps2.structured.get("applications", [])
        still_there = [a for a in apps2 if a.get("name") == _LIVE_IB_NAME]
        assert not still_there, \
            ("infobase '%s' must be gone from get_applications after delete; found: %r"
             % (_LIVE_IB_NAME, still_there))

    finally:
        _ensure_infobase_absent()

    assert_no_diff("the round-trip must not touch the committed fixture (TestConfiguration)")
