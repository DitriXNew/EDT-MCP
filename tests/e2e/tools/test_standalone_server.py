"""E2E contract tests for standalone_server (#890).

The CI-safe cases never stop or start a server: they exercise validation, application resolution,
the type refusal, `status`, and the `stop` preview (which must change nothing). The round trip that
really stops and starts a standalone server needs a 1C runtime with ibsrv and is gated by
EDT_MCP_LIVE_INFOBASE; it also proves the stop hold against update_database.
"""

import os
import shutil
import tempfile

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    requires_live_infobase,
    e2e_test,
    E2ESkip,
    PROJECT,
)

TOOL = "standalone_server"
WST_SERVER_TYPE = "com.e1c.g5.dt.applications.type.wst-server"
STATES = {"STARTING", "STARTED", "STOPPING", "STOPPED", "UNKNOWN"}

_LIVE_DIR = os.path.join(tempfile.gettempdir(), "edt_standalone_server_lifecycle_e2e")
_LIVE_NAME = "StandaloneServerLifecycle_e2e"


def _applications():
    r = call("get_applications", {"projectName": PROJECT})
    assert_ok(r, "discover applications for standalone_server")
    envelope = r.structured
    if not isinstance(envelope, dict) or not isinstance(envelope.get("applications"), list):
        raise AssertionError("get_applications must return its documented JSON envelope: %r" % envelope)
    return envelope["applications"]


def _servers_and_others():
    applications = _applications()
    servers = [a for a in applications if a.get("type") == WST_SERVER_TYPE and a.get("id")]
    others = [a for a in applications if a.get("type") != WST_SERVER_TYPE and a.get("id")]
    return servers, others


@e2e_test(tool=TOOL, kind="action")
def test_missing_project_errors_clearly():
    r = call(TOOL, {})
    e = assert_error(r, "missing projectName")
    assert_error_quality(e, names=["projectName"], ctx="required project is named")
    assert_no_diff("standalone_server validation must not touch project sources")


@e2e_test(tool=TOOL, kind="action")
def test_unknown_action_lists_the_vocabulary():
    r = call(TOOL, {"projectName": PROJECT, "action": "kill"})
    e = assert_error(r, "unknown action")
    assert_contains(e, "Allowed values: status, stop, start, restart", "closed action vocabulary")
    assert_no_diff("an unknown action must not touch project sources")


@e2e_test(tool=TOOL, kind="action")
def test_confirm_on_start_is_refused_before_platform_access():
    r = call(TOOL, {"projectName": PROJECT, "action": "start", "confirm": True})
    e = assert_error(r, "confirm on start")
    assert_contains(e, "confirm applies only to action='stop' and action='restart'",
                    "confirm is a stop/restart parameter")
    assert_no_diff("a refused start must not touch project sources")


@e2e_test(tool=TOOL, kind="action")
def test_stop_preview_for_an_unknown_application_names_the_discovery_route():
    bad = "no_such_standalone_server_e2e_zzz"
    r = call(TOOL, {"projectName": PROJECT, "applicationId": bad, "action": "stop"})
    e = assert_error(r, "unknown applicationId")
    assert_error_quality(e, names=[bad], suggests=["get_applications"],
                         ctx="unknown standalone-server application names its discovery route")
    assert_no_diff("failed target resolution must not touch project sources")


@e2e_test(tool=TOOL, kind="action")
def test_a_non_server_application_is_refused_with_its_type():
    servers, others = _servers_and_others()
    if not others:
        raise E2ESkip("the fixture project has no non-standalone-server application")
    application_id = others[0]["id"]
    r = call(TOOL, {"projectName": PROJECT, "applicationId": application_id})
    e = assert_error(r, "non-wst application")
    assert_error_quality(e, names=[application_id, "wst-server"], suggests=["get_applications"],
                         ctx="a wrong application type is named with the expected one")
    assert_no_diff("a type refusal must not touch project sources")


@e2e_test(tool=TOOL, kind="action")
def test_status_and_stop_preview_change_nothing():
    servers, _ = _servers_and_others()
    if not servers:
        raise E2ESkip("the fixture project has no standalone-server application")
    application_id = servers[0]["id"]

    before = call(TOOL, {"projectName": PROJECT, "applicationId": application_id})
    assert_ok(before, "standalone_server status")
    sc = before.structured
    if not isinstance(sc, dict) or sc.get("applicationId") != application_id:
        raise AssertionError("status must echo the resolved application: %r" % sc)
    if sc.get("state") not in STATES:
        raise AssertionError("status must report a WST state name: %r" % sc)
    if not isinstance(sc.get("heldStopped"), bool):
        raise AssertionError("status must carry boolean heldStopped: %r" % sc)

    preview = call(TOOL, {"projectName": PROJECT, "applicationId": application_id, "action": "stop"})
    assert_ok(preview, "stop preview")
    pv = preview.structured
    if pv.get("action") != "preview" or pv.get("confirmationRequired") is not True:
        raise AssertionError("stop without confirm must be a preview: %r" % pv)
    sessions = pv.get("sessions")
    if not isinstance(sessions, dict) or not isinstance(sessions.get("reachable"), bool):
        raise AssertionError("the preview must carry the sessions block: %r" % pv)
    for row in sessions.get("sessions") or []:
        if "userName" in row or "host" in row:
            raise AssertionError("preview session rows must stay non-personal: %r" % row)

    after = call(TOOL, {"projectName": PROJECT, "applicationId": application_id})
    assert_ok(after, "status after the preview")
    if after.structured.get("state") != sc.get("state"):
        raise AssertionError("a preview must not change the server state: %r -> %r"
                             % (sc, after.structured))
    if after.structured.get("heldStopped") != sc.get("heldStopped"):
        raise AssertionError("a preview must not install a hold: %r -> %r" % (sc, after.structured))
    assert_no_diff("status and the stop preview must not touch project sources")


def _ensure_live_server_absent():
    """Best-effort cleanup of a crashed previous live run."""
    servers, _ = _servers_and_others()
    for app in servers:
        if app.get("name") == _LIVE_NAME:
            call(TOOL, {"projectName": PROJECT, "applicationId": app["id"], "action": "start"})
            call("delete_infobase", {"projectName": PROJECT, "applicationId": app["id"],
                                     "deleteRegistration": True, "confirm": True})
    shutil.rmtree(_LIVE_DIR, ignore_errors=True)


@e2e_test(tool=TOOL, kind="action")
def test_live_stop_holds_the_server_until_start():
    """REQUIRES: EDT_MCP_LIVE_INFOBASE=1 and a 1C runtime that includes the standalone server.

    create (standaloneServer) -> start -> status STARTED with a pid -> stop confirm -> process gone
    and held -> update_database refused by the hold -> start -> STARTED with a new pid -> delete.
    """
    requires_live_infobase("standalone_server stop/start round trip")

    _ensure_live_server_absent()
    application_id = None
    try:
        created = call("create_infobase", {"projectName": PROJECT, "infobaseFile": _LIVE_DIR,
                                           "infobaseName": _LIVE_NAME,
                                           "applicationKind": "standaloneServer"})
        assert_ok(created, "create a standalone-server infobase")
        servers, _ = _servers_and_others()
        found = [a for a in servers if a.get("name") == _LIVE_NAME]
        if not found:
            raise AssertionError("the created standalone server must be listed: %r" % servers)
        application_id = found[0]["id"]
        target = {"projectName": PROJECT, "applicationId": application_id}

        started = call(TOOL, dict(target, action="start"))
        assert_ok(started, "first start")
        status = call(TOOL, target)
        assert_ok(status, "status after start")
        if status.structured.get("state") != "STARTED" or not status.structured.get("pid"):
            raise AssertionError("a started server must be STARTED with a pid: %r" % status.structured)
        first_pid = status.structured["pid"]

        stopped = call(TOOL, dict(target, action="stop", confirm=True))
        assert_ok(stopped, "confirmed stop")
        st = stopped.structured
        if st.get("processAlive") is not False or st.get("heldStopped") is not True:
            raise AssertionError("a verified stop reports the process gone and the hold: %r" % st)
        if st.get("state") != "STOPPED":
            raise AssertionError("EDT must report the server STOPPED: %r" % st)

        refused = call("update_database", dict(target, confirm=True))
        e = assert_error(refused, "update_database while held")
        assert_contains(e, "held stopped by standalone_server(action='stop')",
                        "the hold refuses plugin-driven starts")

        restarted = call(TOOL, dict(target, action="start"))
        assert_ok(restarted, "start releases the hold")
        rs = restarted.structured
        if rs.get("state") != "STARTED" or rs.get("heldStopped") is not False:
            raise AssertionError("start must bring the server up and release the hold: %r" % rs)
        if rs.get("pid") == first_pid:
            raise AssertionError("a restarted ibsrv must be a new process: %r" % rs)
    finally:
        if application_id:
            call(TOOL, {"projectName": PROJECT, "applicationId": application_id, "action": "start"})
            call("delete_infobase", {"projectName": PROJECT, "applicationId": application_id,
                                     "deleteRegistration": True, "confirm": True})
        shutil.rmtree(_LIVE_DIR, ignore_errors=True)

    assert_no_diff("the round trip must not touch the committed fixture")
