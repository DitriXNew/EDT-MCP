"""
e2e tests for wait_for_break (kind: read).

wait_for_break is a DEBUG/RUNTIME tool. It blocks until a SUSPEND event (e.g. a
breakpoint hit) is observed for a debug application, then returns the suspended
thread/frame snapshot; on timeout it returns a benign {hit:false, reason:"timeout"}.
Response type is JSON (getResponseType() == JSON), so the payload lives in
r.structured (NOT r.text); the server flags isError:true ONLY for the
ToolResult.error shape ({"success":false,"error":...}).

ENVIRONMENT (this EDT, this fixture):
  There is NO active debug session and TestConfiguration has NO running infobase /
  launched application. So the realistic, correct contract has two observable shapes,
  BOTH exercised below directly from the Java in WaitForBreakTool.execute():

  1. applicationId OMITTED + no active debug launch:
     findLoneActiveApplicationId() -> null (listActiveApplicationIds() is empty),
     so the tool returns the CLEAR, ACTIONABLE SENTINEL via ToolResult.error:
       "applicationId is required - no single active debug launch available for
        auto-resolution. Use debug_status to list active launches."
     This is is_error:true and names the missing precondition + the next step.

  2. applicationId GIVEN but no such running session (real or synthetic
     'attach:<config>'):
     auto-resolution is skipped (applicationId was supplied), scanForAlreadySuspended
     finds no live target, waitForSuspend never sees a snapshot for that appId, and on
     the (clamped) timeout the tool returns a BENIGN SUCCESS-WITH-STATUS:
       {success:true, hit:false, reason:"timeout", applicationId:<echoed>}
     This is is_error:false. It is the genuine "no session -> no break" contract and
     is what callers poll on. We pass timeout:1 so the wait window is ~1s, not 60s.

  We do NOT start a real infobase / launch (heavy, not configured here). The
  sentinel (#1) + the timeout status (#2) + the negative matrix below ARE the coverage.

DIFF: a debug tool operates on the running infobase / EDT workspace, never on the
git-tracked project source. EVERY test asserts assert_no_diff() on TestConfiguration
(a read/debug tool that mutated project source would be a bug, and this catches it).

TIMING (verified against WaitForBreakTool.clampTimeout): the requested wait window is
clamped to [1, MAX_TIMEOUT=600] seconds. timeout:1 -> ~1s wait; timeout:0 is floored
to 1 (NOT an instant return and NOT an unbounded block). We always pass a tiny timeout
on the no-session timeout paths so the suite stays fast.

Parameters (both OPTIONAL, per getInputSchema): applicationId (string), timeout (int).
There are no required params, no enum, no XOR/conditional combinations. JsonUtils
.extractIntArgument is TOLERANT: a non-numeric/fractional timeout silently falls back
to the default (60) rather than erroring (see test_invalid_timeout_*).

Fixture inventory used: the project name "TestConfiguration"; no debug launch, no
running infobase (so #1/#2 above are the real observed branches).
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

# A synthetic applicationId that cannot correspond to any running session in this
# environment. Used to drive the explicit-id timeout path without auto-resolution.
NO_SUCH_APP = "attach:NoSuchApp_ZZZ_e2e"


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL (this no-session environment)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="wait_for_break", kind="read")
def test_no_app_and_no_launch_returns_actionable_sentinel():
    """applicationId omitted AND no active debug launch -> the auto-resolution
    sentinel (ToolResult.error). This is the realistic happy contract here.

    The message MUST name the missing precondition (applicationId / auto-resolution)
    AND point at the next step (debug_status to list active launches). A broken tool
    that silently blocked, returned a bare "Error", or fabricated a fake session would
    fail this. The error text is read from structured.error (JSON response type).
    """
    r = call("wait_for_break", {})
    err = assert_error(r, "no applicationId + no active debug launch")
    # Specific, mutation-sensitive sentinel: names the missing arg AND the next tool.
    assert_error_quality(
        err,
        names=["applicationId", "auto-resolution"],
        suggests=["debug_status"],
        ctx="no-launch auto-resolution sentinel is clear + actionable",
    )
    assert_no_diff("a debug read tool must not touch project source")


@e2e_test(tool="wait_for_break", kind="read")
def test_explicit_unknown_app_times_out_with_clean_status():
    """applicationId GIVEN but no such running session + a tiny timeout -> the benign
    success-with-status {hit:false, reason:"timeout"}, echoing the requested appId.

    This is the genuine "no session -> no break" contract callers poll on. It must:
      - NOT be an error (is_error:false) — a timeout is a normal, expected outcome;
      - report hit:false (no suspend was observed) — NOT a fabricated hit:true;
      - report reason:"timeout" (why it returned);
      - echo the EXACT applicationId we passed (proves no silent re-resolution);
      - NOT mark autoResolved (we supplied the id explicitly, so the auto-resolve
        branch must be skipped).
    A broken tool that invented a suspend, dropped the reason, or swapped the appId
    would fail one of these. timeout:1 keeps the wait ~1s (clampTimeout floor is 1).
    """
    r = call("wait_for_break", {"applicationId": NO_SUCH_APP, "timeout": 1})
    assert_ok(r, "explicit-unknown-app timeout is a benign success, not an error")
    sc = r.structured or {}
    if sc.get("hit") is not False:
        raise AssertionError("expected hit:false on timeout, got structured=%r" % (sc,))
    if sc.get("reason") != "timeout":
        raise AssertionError("expected reason:'timeout', got structured=%r" % (sc,))
    if sc.get("applicationId") != NO_SUCH_APP:
        raise AssertionError(
            "tool must echo the requested applicationId %r, got structured=%r"
            % (NO_SUCH_APP, sc))
    # We passed the id explicitly -> the auto-resolution branch (and its autoResolved
    # marker) must NOT fire. A broken tool that always auto-resolves would set it.
    if sc.get("autoResolved"):
        raise AssertionError(
            "autoResolved must NOT be set when applicationId is supplied; structured=%r"
            % (sc,))
    assert_no_diff("a debug read tool must not touch project source")


@e2e_test(tool="wait_for_break", kind="read")
def test_synthetic_attach_id_times_out_cleanly_not_crash():
    """A synthetic 'attach:<configName>' id for a config that is NOT running must flow
    through the same wait/timeout path and return a CLEAN benign timeout — not a
    crash, a leaked stack trace, or a spurious error.

    findActiveTarget('attach:...') returns null (no matching live target), so the wait
    simply times out. This proves the synthetic-id branch is handled gracefully when no
    session exists. (Distinct synthetic name from the test above to keep the snapshot
    keyed independently.) timeout:1 keeps it fast.
    """
    synthetic = "attach:NoSuchConfig_e2e"
    r = call("wait_for_break", {"applicationId": synthetic, "timeout": 1})
    assert_ok(r, "synthetic attach id for a non-running config -> clean timeout")
    sc = r.structured or {}
    if sc.get("hit") is not False or sc.get("reason") != "timeout":
        raise AssertionError(
            "synthetic attach id must time out cleanly (hit:false, reason:timeout); "
            "got structured=%r" % (sc,))
    if sc.get("applicationId") != synthetic:
        raise AssertionError(
            "must echo the synthetic applicationId %r, got structured=%r"
            % (synthetic, sc))
    assert_no_diff("a debug read tool must not touch project source")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (edge cases — no required/enum/XOR params, so the edges are:
# empty-id auto-resolve failure, timeout clamping, and tolerant non-numeric timeout)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="wait_for_break", kind="read")
def test_empty_string_app_is_treated_as_omitted_and_errors():
    """Boundary: applicationId present but the EMPTY string. execute() guards with
    `applicationId == null || applicationId.isEmpty()`, so "" is treated EXACTLY like
    an omitted id and hits the same auto-resolution path -> with no active launch it
    must return the actionable sentinel, NOT silently wait on a blank id.

    Mutation-sensitive: a broken guard that let "" through would block on waitForSuspend
    for an empty appId (never the sentinel). We assert the specific sentinel text.
    """
    r = call("wait_for_break", {"applicationId": ""})
    err = assert_error(r, "empty-string applicationId is treated as omitted")
    assert_error_quality(
        err,
        names=["applicationId", "auto-resolution"],
        suggests=["debug_status"],
        ctx="empty-string applicationId hits the auto-resolution sentinel",
    )
    assert_no_diff("a rejected call must not touch project source")


@e2e_test(tool="wait_for_break", kind="read")
def test_zero_timeout_is_clamped_to_floor_and_returns_promptly():
    """Edge: timeout:0 with a non-existent explicit appId. clampTimeout floors a
    sub-1 request to 1 second, so the call must return PROMPTLY with the benign
    timeout status — it must neither return instantly with a wrong "hit" nor block
    unbounded. Proves the clamp floor is applied (not a 0s/infinite-wait bug).

    Mutation-sensitive: a tool that skipped clamping would either spin on a 0ms wait
    or (if 0 meant "forever") hang the request. Either breaks this assertion.
    """
    r = call("wait_for_break", {"applicationId": NO_SUCH_APP, "timeout": 0})
    assert_ok(r, "timeout:0 is clamped to the 1s floor, not an error")
    sc = r.structured or {}
    if sc.get("hit") is not False or sc.get("reason") != "timeout":
        raise AssertionError(
            "clamped-floor wait must end in a benign timeout (hit:false, "
            "reason:timeout); got structured=%r" % (sc,))
    if sc.get("applicationId") != NO_SUCH_APP:
        raise AssertionError(
            "must echo applicationId %r; got structured=%r" % (NO_SUCH_APP, sc))
    assert_no_diff("a debug read tool must not touch project source")


@e2e_test(tool="wait_for_break", kind="read")
def test_invalid_timeout_value_is_refused():
    """timeout "abc" is refused by the argument check, naming the value, instead of silently
    waiting for the default 60 seconds."""
    r = call("wait_for_break", {"applicationId": NO_SUCH_APP, "timeout": "abc"})
    err = assert_error(r, "non-numeric timeout")
    assert_error_quality(err, names=["timeout", "abc"], suggests=["must be an integer", "nothing was run"],
                         ctx="the argument check refuses the value instead of using the default")
    assert_no_diff("a refused call must not touch the project on disk")