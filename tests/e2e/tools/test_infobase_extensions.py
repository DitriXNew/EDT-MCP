"""Non-mutating wire checks; lifecycle writes are accepted separately on a disposable infobase.

The last test is attended (EDT_MCP_LIVE_INFOBASE=1): it reads the extensions of every STARTED standalone-server
application of the project and needs at least one installed extension. The standalone server's own Designer
agent puts the active flag in place of the version (#916); the list must never echo that flag as a version.
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality, assert_contains, assert_no_diff,
    requires_live_infobase, e2e_test, E2ESkip, PROJECT,
)

WST_SERVER_TYPE = "com.e1c.g5.dt.applications.type.wst-server"


def _args(**extra):
    return {"projectName": PROJECT, "applicationId": "no_such_cfe_application_e2e", **extra}


@e2e_test(tool="infobase_extensions", kind="action")
def test_required_target_is_explicit():
    error = assert_error(call("infobase_extensions", {"projectName": PROJECT}), "missing target")
    assert_error_quality(error, names=["applicationId"], ctx="explicit application is required")
    assert_no_diff("required-target refusal")


@e2e_test(tool="infobase_extensions", kind="action")
def test_action_and_write_validation_precede_platform_access():
    cases = [
        ({"action": "replace"}, "Allowed values: list, install, update, uninstall"),
        ({"action": "install"}, "extensionName is required"),
        ({"action": "update", "extensionName": "Runtime"}, "cfePath is required"),
        ({"action": "uninstall"}, "extensionName is required"),
        ({"action": "uninstall", "extensionName": "Runtime", "safeMode": False}, "remove those parameters"),
        ({"action": "install", "extensionName": "Runtime", "cfePath": "relative.cfe"}, "absolute path"),
        ({"action": "list", "confirm": True}, "remove write parameters"),
        ({"action": "list", "expectedHash": "stale"}, "remove write parameters"),
        ({"action": "uninstall", "extensionName": "Runtime", "confirm": True}, "expectedHash is required"),
        ({"action": "uninstall", "extensionName": "Runtime", "expectedHash": "stale"},
         "expectedHash is accepted only with confirm=true"),
        ({"timeoutSeconds": 601}, "10 to 600"),
    ]
    for params, expected in cases:
        error = assert_error(call("infobase_extensions", _args(**params)), "invalid lifecycle request")
        assert_contains(error, expected, "specific actionable validation")
    assert_no_diff("invalid extension operations")


@e2e_test(tool="infobase_extensions", kind="action")
def test_unknown_application_never_becomes_empty_inventory():
    args = _args(action="list")
    result = call("infobase_extensions", args)
    error = assert_error(result, "unknown application")
    assert_error_quality(error, names=[args["applicationId"]], suggests=["get_applications"],
                         ctx="discovery route for an unknown target")
    if result.structured and "extensions" in result.structured:
        raise AssertionError("failed lookup must not publish an empty installed inventory")
    assert_no_diff("failed extension inventory lookup")


@e2e_test(tool="infobase_extensions", kind="action")
def test_live_standalone_server_list_never_reports_the_active_flag_as_version():
    """REQUIRES: EDT_MCP_LIVE_INFOBASE=1 and a STARTED standalone-server application with an extension.

    Only servers that are already started are read: stopping a server again after the read would leave
    it held stopped (standalone_server's hold), so the test never starts or stops one. A failed status or
    inventory call fails the test; only successful but empty inventories lead to a skip.
    """
    requires_live_infobase("standalone-server extension inventory")
    apps = call("get_applications", {"projectName": PROJECT})
    assert_ok(apps, "discover applications")
    servers = [a for a in (apps.structured or {}).get("applications", [])
               if a.get("type") == WST_SERVER_TYPE and a.get("id")]
    records, errors, not_started = [], [], []
    for app in servers:
        target = {"projectName": PROJECT, "applicationId": app["id"]}
        status = call("standalone_server", target)
        if status.is_error or not status.structured:
            errors.append("%s status: %s" % (app["id"], status.text))
            continue
        if status.structured.get("state") != "STARTED":
            not_started.append("%s (%s)" % (app["id"], status.structured.get("state")))
            continue
        listed = call("infobase_extensions", target)
        if listed.is_error or not (listed.structured and listed.structured.get("success")):
            errors.append("%s list: %s" % (app["id"], listed.text))
            continue
        records.extend(listed.structured.get("extensions") or [])
    if errors:
        raise AssertionError("standalone-server inventory calls failed: %s" % "; ".join(errors))
    if not records:
        raise E2ESkip("no STARTED standalone-server application of %s has an installed extension "
                      "(not started: %s); start one with standalone_server(action='start') first"
                      % (PROJECT, ", ".join(not_started) or "none"))
    for record in records:
        if not isinstance(record.get("active"), bool):
            raise AssertionError("active must be a JSON boolean: %r" % record)
        if record.get("version") in ("true", "false"):
            raise AssertionError("the agent's active flag leaked into version (#916): %r" % record)
        if "version" not in record and record.get("versionReported") is not False:
            raise AssertionError("a missing version must be declared with versionReported:false: %r" % record)
    assert_no_diff("reading extensions must not touch the fixture")