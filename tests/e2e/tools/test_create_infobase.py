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

# #271: a directory that exists but holds NO 1Cv8.1CD — for the CI-safe standalone register
# negative test (the register precondition fires before any platform/runtime lookup).
_NO_DB_DIR = os.path.join(tempfile.gettempdir(), "edt_standalone_register_no_db_e2e")

# #271: temp dir / name for the live "wrap an existing infobase with a standalone server" round-trip.
_SRV_IB_DIR = os.path.join(tempfile.gettempdir(), "edt_standalone_register_e2e")
_SRV_IB_NAME = "StandaloneRegister_e2e"


def _ensure_standalone_register_absent():
    """Best-effort pre/post clean for the standalone-register round-trip: remove any leftover
    wst-server registration AND the plain infobase registration for a prior crashed run, then the
    temp directory. Ignores the results."""
    # Remove the standalone-server registration (keeps the database untouched — no deleteDatabaseFiles).
    call("delete_infobase",
         {"projectName": PROJECT, "infobaseName": _SRV_IB_NAME,
          "deleteRegistration": True, "confirm": True})
    # Remove the plain infobase registration bound over the same directory.
    call("delete_infobase",
         {"projectName": PROJECT, "infobaseName": _SRV_IB_NAME,
          "deleteRegistration": True, "confirm": True})
    shutil.rmtree(_SRV_IB_DIR, ignore_errors=True)


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


@e2e_test(tool="create_infobase", kind="action")
def test_standalone_create_with_credentials_errors_steering_to_register():
    """#275 negative (CI-safe): applicationKind='standaloneServer' with the default mode='create'
    plus any of user/password/access must still be rejected -- a brand-new standalone server has no
    existing infobase reference to store credentials against yet. Unlike the OLD blanket rejection
    (any standaloneServer + credentials), the error must now steer to BOTH supported alternatives:
    applicationKind='infobase', or applicationKind='standaloneServer' + mode='register' (which DOES
    accept credentials -- see test_live_standalone_register_over_existing_infobase below). Validated
    before any platform/service lookup (headless-safe)."""
    r = call("create_infobase",
             {"projectName": PROJECT,
              "infobaseFile": "C:\\infobases\\ci_probe_credentials_test",
              "applicationKind": "standaloneServer",
              "user": "Admin"})
    err = assert_error(r, "standaloneServer+create with credentials")
    assert_error_quality(err, names=["infobase"], suggests=["register"],
                         ctx="standaloneServer+create+credentials must steer to mode='register'")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_infobase", kind="action")
def test_standalone_register_without_database_errors_naming_path():
    """#271 negative (CI-safe): applicationKind='standaloneServer' + mode='register' pointed at a
    directory that holds NO 1Cv8.1CD must fail FAST — before any platform/standalone-runtime lookup —
    with an actionable error that NAMES the path and steers to mode='create'. This is the register
    precondition (the same check the plain register path uses), so CI exercises it without a runtime.

    It also proves the old routing rejection is gone: standaloneServer+register is now a supported
    combination, so the error must NOT be the former 'not supported' message."""
    os.makedirs(_NO_DB_DIR, exist_ok=True)
    try:
        r = call("create_infobase",
                 {"projectName": PROJECT,
                  "infobaseFile": _NO_DB_DIR,
                  "applicationKind": "standaloneServer",
                  "mode": "register"})
        err = assert_error(r, "standalone register without 1Cv8.1CD")
        assert_error_quality(
            err, names=["edt_standalone_register_no_db_e2e"], suggests=["create"],
            ctx="standalone register must name the path and steer to mode='create'")
        assert "not supported" not in err, \
            ("standaloneServer+register must no longer be rejected as 'not supported': %r" % err)
    finally:
        shutil.rmtree(_NO_DB_DIR, ignore_errors=True)
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


@e2e_test(tool="create_infobase", kind="action")
def test_live_standalone_register_over_existing_infobase():
    """#271 happy path: wrap an EXISTING file infobase with a standalone server via
    applicationKind='standaloneServer' + mode='register'.

    REQUIRES: EDT_MCP_LIVE_INFOBASE=1 (a live EDT stand with BOTH a 1C platform runtime and a 1C
    standalone-server runtime (platform >= 8.3.23) registered, and TestConfiguration open).

    This test:
    1. Creates a plain FILE infobase (mode='create') at a temp directory — this writes the 1Cv8.1CD.
    2. Registers a standalone server OVER that SAME directory (mode='register'); the existing database
       must be reused (no new DB materialized), so action=='registered' and webUrl/port are reported.
       #275: also passes user/password -- standaloneServer+register is the ONE standalone-server
       combination that accepts connection credentials -- and asserts the result acknowledges they
       were stored (the message notes it; storing does not require the user to actually exist in the
       infobase, only actual authentication would).
    3. Verifies get_applications lists a wst-server application for the directory.
    4. Cleans up: delete the standalone-server registration (keeping the database), then delete the
       plain infobase registration, then the temp directory.
    """
    requires_live_infobase("create_infobase standalone-register round-trip")

    _ensure_standalone_register_absent()
    try:
        # 1. Seed an existing FILE infobase (writes 1Cv8.1CD on disk).
        r_seed = call("create_infobase",
                      {"projectName": PROJECT,
                       "infobaseFile": _SRV_IB_DIR,
                       "infobaseName": _SRV_IB_NAME})
        assert_ok(r_seed, "seed plain infobase for standalone-register")
        assert os.path.isfile(os.path.join(_SRV_IB_DIR, "1Cv8.1CD")), \
            "the seed step must write a 1Cv8.1CD into %s" % _SRV_IB_DIR

        # 2. Register a standalone server OVER the existing infobase, ALSO passing connection
        #    credentials (#275): standaloneServer+register is the one standalone-server combination
        #    that accepts them.
        r_reg = call("create_infobase",
                     {"projectName": PROJECT,
                      "infobaseFile": _SRV_IB_DIR,
                      "infobaseName": _SRV_IB_NAME,
                      "applicationKind": "standaloneServer",
                      "mode": "register",
                      "user": "Admin",
                      "password": ""})
        assert_ok(r_reg, "standalone register over existing infobase")
        sc = r_reg.structured
        assert isinstance(sc, dict), "structured must be a dict: %r" % sc
        assert sc.get("action") == "registered", \
            "standalone register must report action='registered': %r" % sc
        assert sc.get("applicationKind") == "standaloneServer", \
            "result must echo applicationKind='standaloneServer': %r" % sc
        assert sc.get("webUrl"), "a registered standalone server must expose a webUrl: %r" % sc
        assert sc.get("port"), "a registered standalone server must report its web port: %r" % sc
        # The existing database must still be on disk (register must not have removed/rewritten it).
        assert os.path.isfile(os.path.join(_SRV_IB_DIR, "1Cv8.1CD")), \
            "the existing 1Cv8.1CD must remain after register: %s" % _SRV_IB_DIR
        # #275: the result must acknowledge the stored connection credentials (a success note, or --
        # non-fatally -- a WARNING naming why storage failed; either way NOT silently dropped).
        message = sc.get("message") or ""
        assert ("stored connection credentials" in message.lower()
                or "connection credentials were not stored" in message.lower()), \
            "message must acknowledge the credentials request (stored or an explicit WARNING): %r" % sc

        # 3. Verify a wst-server application now exists for the directory.
        r_apps = call("get_applications", {"projectName": PROJECT})
        assert_ok(r_apps, "get_applications after standalone register")
        apps = r_apps.structured.get("applications", [])
        srv_type = "com.e1c.g5.dt.applications.type.wst-server"
        found = [a for a in apps if a.get("name") == _SRV_IB_NAME
                 and a.get("type") == srv_type]
        assert found, \
            ("registered standalone server '%s' must appear in get_applications with type %s; got: %r"
             % (_SRV_IB_NAME, srv_type, apps))

        # 4. Clean up: remove the server registration (keep the DB), then the plain registration.
        srv_app_id = found[0].get("id")
        if srv_app_id:
            r_del_srv = call("delete_infobase",
                             {"projectName": PROJECT, "applicationId": srv_app_id, "confirm": True})
            assert_ok(r_del_srv, "delete standalone-server registration")
            # The database directory must survive (we did NOT ask to delete the database files).
            assert os.path.isfile(os.path.join(_SRV_IB_DIR, "1Cv8.1CD")), \
                "deleting only the server registration must keep the database on disk"
    finally:
        _ensure_standalone_register_absent()

    assert_no_diff("the round-trip must not touch the committed fixture (TestConfiguration)")
