"""E2E contract tests for infobase_sessions.

The suite never terminates a session. It exercises validation and the real application-resolution
plus list boundary, including the structural difference between a readable empty list and an
unreachable standalone server.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


@e2e_test(tool="infobase_sessions", kind="action")
def test_missing_project_errors_clearly():
    r = call("infobase_sessions", {})
    e = assert_error(r, "missing projectName")
    assert_error_quality(e, names=["projectName"], ctx="required project is named")
    assert_no_diff("session validation must not touch project sources")


@e2e_test(tool="infobase_sessions", kind="action")
def test_invalid_action_is_rejected_before_platform_access():
    r = call("infobase_sessions", {"projectName": PROJECT, "action": "disconnect"})
    e = assert_error(r, "invalid action")
    assert_contains(e, "Allowed values: list, terminate", "closed action vocabulary")
    assert_no_diff("invalid session action must not touch project sources")


@e2e_test(tool="infobase_sessions", kind="action")
def test_real_project_unknown_application_is_actionable():
    bad = "no_such_session_application_e2e_zzz"
    r = call("infobase_sessions", {
        "projectName": PROJECT,
        "applicationId": bad,
    })
    e = assert_error(r, "real project with unknown applicationId")
    assert_error_quality(e, names=[bad], suggests=["get_applications"],
                         ctx="unknown session application names its discovery route")
    assert_no_diff("failed session target resolution must not touch project sources")


@e2e_test(tool="infobase_sessions", kind="action")
def test_terminate_requires_confirmation_and_one_selector():
    r = call("infobase_sessions", {
        "projectName": PROJECT,
        "action": "terminate",
        "sessionId": "42",
    })
    e = assert_error(r, "terminate without confirm")
    assert_contains(e, "confirm=true", "termination confirmation")

    r = call("infobase_sessions", {
        "projectName": PROJECT,
        "action": "terminate",
        "confirm": True,
    })
    e = assert_error(r, "confirmed terminate without selector")
    assert_contains(e, "exactly one", "one selector is required")

    r = call("infobase_sessions", {
        "projectName": PROJECT,
        "action": "terminate",
        "sessionId": "42",
        "all": True,
        "confirm": True,
    })
    e = assert_error(r, "two terminate selectors")
    assert_contains(e, "exactly one", "selectors are mutually exclusive")
    assert_no_diff("rejected termination calls must not touch project sources")


@e2e_test(tool="infobase_sessions", kind="action")
def test_real_application_list_preserves_reachability_shape():
    apps_result = call("get_applications", {"projectName": PROJECT})
    assert_ok(apps_result, "discover applications for session-list boundary")
    envelope = apps_result.structured
    if not isinstance(envelope, dict) or not isinstance(envelope.get("applications"), list):
        raise AssertionError("get_applications must return its documented JSON envelope: %r" % envelope)

    applications = envelope["applications"]
    if not applications:
        r = call("infobase_sessions", {"projectName": PROJECT})
        e = assert_error(r, "project with no default application")
        assert_error_quality(e, names=[PROJECT], suggests=["get_applications"],
                             ctx="missing default application is actionable")
        assert_no_diff("session lookup must not touch project sources")
        return

    standalone = [a for a in applications if a.get("type", "").endswith("wst-server")]
    target = (standalone or applications)[0]
    application_id = target.get("id")
    if not application_id:
        raise AssertionError("discovered application must carry an id: %r" % target)

    r = call("infobase_sessions", {
        "projectName": PROJECT,
        "applicationId": application_id,
        "action": "list",
    })
    assert_ok(r, "real application session list")
    sc = r.structured
    if not isinstance(sc, dict) or sc.get("applicationId") != application_id:
        raise AssertionError("session result must echo the resolved application: %r" % sc)
    if not isinstance(sc.get("reachable"), bool):
        raise AssertionError("session result must carry boolean reachable: %r" % sc)

    if sc["reachable"]:
        sessions = sc.get("sessions")
        if not isinstance(sessions, list) or sc.get("count") != len(sessions):
            raise AssertionError("readable result must carry a count-consistent list: %r" % sc)
        required = {
            "sessionId", "sessionNumber", "applicationKind", "userName", "host",
            "startedAt", "lastActiveAt", "applicationKindIsDesigner",
        }
        for session in sessions:
            if not isinstance(session, dict) or not required.issubset(session):
                raise AssertionError("session record has the wrong shape: %r" % session)
            if "isEdtAgent" in session:
                raise AssertionError("session record must not claim EDT ownership: %r" % session)
            expected_designer_kind = session.get("applicationKind", "").lower() == "designer"
            if session.get("applicationKindIsDesigner") is not expected_designer_kind:
                raise AssertionError("Designer application-kind flag is inconsistent: %r" % session)
    else:
        if not sc.get("unreachableReason"):
            raise AssertionError("unreachable result must name its reason: %r" % sc)
        if "sessions" in sc:
            raise AssertionError("unreachable result must not masquerade as a session list: %r" % sc)

    assert_no_diff("session listing must not touch project sources")
