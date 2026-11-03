"""Non-mutating wire checks; lifecycle writes are accepted separately on a disposable infobase."""

from harness import (
    call, assert_error, assert_error_quality, assert_contains, assert_no_diff,
    e2e_test, PROJECT,
)


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
