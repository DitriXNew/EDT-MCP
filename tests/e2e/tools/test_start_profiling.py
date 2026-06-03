"""
e2e tests for start_profiling (kind: read).

start_profiling enables 1C line-level performance measurement (замер
производительности) on an ACTIVE debug target. It is a DEBUG/RUNTIME tool:
its happy path requires a live debug session created by debug_launch or
debug_yaxunit_tests. Response type is JSON (getResponseType() == JSON), so the
payload is in r.structured ({"success":..,"error"/"message":..}); r.text is the
serialized JSON, and the harness error_text() reads structured["error"] first.

Single declared parameter (getInputSchema): applicationId (string, REQUIRED).
There is no XOR/enum/conditional parameter to fuzz — the negative matrix is the
required-arg guard plus invalid applicationId values.

ENVIRONMENT (this EDT): there is NO active debug session and TestConfiguration
has NO running infobase / launched application. The on/off profiling state set
(ACTIVE_APPLICATION_IDS) is therefore empty. So for ANY applicationId we pass
here, execute() takes the realistic branch:

    isProfilingActive(id) == false  (nothing was started)
      -> DebugSessionRegistry.findActiveTarget(id) == null
      -> ToolResult.error("No active debug target for applicationId: <id>. "
                          + "Start a debug session first "
                          + "(debug_launch or debug_yaxunit_tests).")

THAT no-session sentinel IS the correct, realistic happy contract here: a CLEAR,
ACTIONABLE error that (a) names the missing applicationId and (b) names the exact
next step (start a debug session via debug_launch / debug_yaxunit_tests). We
assert that specific sentinel (mutation-sensitive: a no-op, a wrong/vague error,
or a tool that silently "succeeded" without a session would all fail it).

We deliberately do NOT start a real infobase / debug_launch (heavy, not
configured) — the sentinel + the negative matrix is the full coverage.

DIFF: start_profiling drives the running infobase / EDT debug runtime, NOT the
git-tracked project source. Every test asserts assert_no_diff() — a debug tool
must never modify TestConfiguration's files on disk.

Real branches in StartProfilingTool.execute() relevant here:
  - missing/empty applicationId -> JsonUtils.requireArgument
        -> "applicationId is required"
  - present applicationId, not already-active, no live target (this env)
        -> "No active debug target for applicationId: <id>. Start a debug
            session first (debug_launch or debug_yaxunit_tests)."
  - already-active id (unreachable here — set is empty without a prior start)
        -> success {"started": false, "message": "already active ..."}
"""

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
)


# A syntactically plausible application id that is guaranteed NOT to be a live
# debug session in this environment (no debug_launch was performed).
NONEXISTENT_APP_ID = "e2e-no-such-application-zzz"


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL  (no active debug session in this EDT)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="start_profiling", kind="read")
def test_no_active_session_returns_clear_actionable_sentinel():
    """The realistic happy contract in a no-session environment.

    With no debug session running and an applicationId that was never started,
    isProfilingActive(id) is false and DebugSessionRegistry.findActiveTarget(id)
    returns null, so the tool returns the no-active-target sentinel. We assert
    the SPECIFIC message: it must name the missing applicationId AND point at the
    exact next step (start a debug session via debug_launch / debug_yaxunit_tests).

    Mutation-sensitive: a broken tool that toggled profiling on a non-existent
    target, returned success-without-a-session, or emitted a bare/opaque error
    would all fail here.
    """
    r = call("start_profiling", {"applicationId": NONEXISTENT_APP_ID})
    err = assert_error(r, "start_profiling with no active debug session")
    assert_error_quality(
        err,
        names=[NONEXISTENT_APP_ID],
        suggests=["No active debug target", "debug_launch", "debug_yaxunit_tests"],
        ctx="no-session sentinel names the id + the next step (start a debug session)",
    )
    assert_no_diff("start_profiling must not touch the project source on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX  (edge cases + error quality)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="start_profiling", kind="read")
def test_missing_application_id_errors_clearly():
    """Missing the only required parameter -> JsonUtils.requireArgument guard.

    applicationId is the single declared, required input; omitting it must
    produce the required-arg error ("applicationId is required"), never a
    NullPointer, a generic failure, or a silent success against some default.
    """
    r = call("start_profiling", {})
    e = assert_error(r, "missing required applicationId")
    # AUDIT: "applicationId is required" names the param but offers no next step
    # (no pointer to get_applications / debug_launch to obtain a valid id) ->
    # suggests=[] intentionally. Fix-card: make the required-arg guard actionable.
    assert_error_quality(
        e,
        names=["applicationId"],
        suggests=[],
        ctx="missing applicationId names the required param",
    )
    assert_no_diff("an invalid call must not touch the project source on disk")


@e2e_test(tool="start_profiling", kind="read")
def test_empty_application_id_treated_as_missing():
    """Boundary: applicationId present but the EMPTY string.

    JsonUtils.requireArgument checks `value == null || value.isEmpty()`, so "" is
    treated as missing and hits the SAME required-arg guard as the omitted-param
    case — it must NOT fall through to a target lookup on a blank id, and must NOT
    silently succeed.
    """
    r = call("start_profiling", {"applicationId": ""})
    e = assert_error(r, "empty-string applicationId")
    # AUDIT: an empty value is reported via the same "applicationId is required"
    # guard; it does not distinguish "empty" from "omitted" nor suggest a next
    # step. suggests=[] intentional; fix-card to make the guard actionable.
    assert_error_quality(
        e,
        names=["applicationId"],
        suggests=[],
        ctx="empty-string applicationId rejected by the required-arg guard",
    )
    assert_no_diff("an invalid call must not touch the project source on disk")


@e2e_test(tool="start_profiling", kind="read")
def test_nonexistent_application_id_names_value_and_suggests_start():
    """Invalid value: a well-formed but non-existent applicationId.

    requireArgument passes (non-empty), isProfilingActive is false, and there is
    no live debug target for this id, so the tool must return the no-active-target
    sentinel that ECHOES the bad id and points at how to fix it (start a debug
    session). A resolver that ignored the id, or returned a generic error, would
    fail the names=[...] / suggests=[...] checks.
    """
    bad = "00000000-0000-0000-0000-000000000000"  # plausible app-id shape, not live
    r = call("start_profiling", {"applicationId": bad})
    e = assert_error(r, "non-existent applicationId")
    assert_error_quality(
        e,
        names=[bad],
        suggests=["No active debug target", "debug_launch", "debug_yaxunit_tests"],
        ctx="non-existent applicationId is echoed + the fix (start a debug session) is named",
    )
    assert_no_diff("an invalid call must not touch the project source on disk")


@e2e_test(tool="start_profiling", kind="read")
def test_whitespace_application_id_surfaces_as_no_target():
    """Boundary: a whitespace-only applicationId.

    Because requireArgument uses isEmpty() (no trim), "   " is NOT caught by the
    required-arg guard; it flows to DebugSessionRegistry.findActiveTarget("   ")
    which has no live target, so the tool returns the no-active-target sentinel.
    This proves the tool does not strip/coerce a blank id into a real session
    (which would be a silent-success bug). The blank id itself is awkward to match
    through JSON escaping, so we assert the stable, delimiter-free sentinel prefix
    plus the actionable next step.
    """
    r = call("start_profiling", {"applicationId": "   "})
    e = assert_error(r, "whitespace-only applicationId")
    # AUDIT: a whitespace id surfaces as a confusing "No active debug target for
    # applicationId:    " rather than a dedicated "blank id" diagnostic. The
    # message is still actionable (names the next step), but does not flag the
    # blank input. Fix-card: trim + reject blank ids with a dedicated message.
    assert_error_quality(
        e,
        names=["No active debug target"],
        suggests=["debug_launch", "debug_yaxunit_tests"],
        ctx="whitespace applicationId surfaces as no-active-target + names the next step",
    )
    assert_no_diff("an invalid call must not touch the project source on disk")
