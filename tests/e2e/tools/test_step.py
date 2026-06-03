"""
e2e tests for step (kind: read).

step is a DEBUG/RUNTIME control tool: it steps a SUSPENDED debug thread (over /
into / out) and then BLOCKS until the next SUSPEND event (or a timeout), returning
a fresh frame snapshot in the SAME JSON shape as wait_for_break. It never touches
the project source — it drives the live Eclipse debug model (DebugSessionRegistry)
of a running 1C infobase. getResponseType() == JSON, so a ToolResult.error payload
is delivered as a structured isError:true response; r.is_error is the machine
signal and r.error_text() carries the message. See StepTool.java +
DebugSessionRegistry.java.

Declared parameters (StepTool.getInputSchema):
  - threadId (integer, REQUIRED) — a thread id from wait_for_break.
  - kind     (enum,    REQUIRED) — one of {over, into, out}.
  - timeout  (integer, optional, default 30; clamped to [1, 600]).

CONTROL FLOW (StepTool.execute), in branch order — this ordering is itself under test:
  1. threadId <= 0  (absent -> extractLongArgument default -1L; or 0; or negative;
                     or non-numeric which extractLongArgument coerces to -1L)
                                       -> ToolResult.error("threadId is required")
  2. kind null/blank                  -> ToolResult.error("kind is required (over/into/out)")
  3. registry.getThread(threadId) == null  (no live thread in a no-session env)
                                       -> ToolResult.error(
                                            "stale threadId — call wait_for_break again")
  (only reachable WITH a live suspended thread, so NOT exercisable here:)
     thread not IStep        -> "thread does not support stepping"
     appId unresolved        -> "could not determine applicationId for thread"
     canStepOver/Into/Return false -> "cannot step over/into/out"
     kind not over/into/out  -> "unknown kind: <kind>"   (the DEFAULT branch — see AUDIT)
     timeout with no new SUSPEND  -> benign success {hit:false, reason:"timeout"}

ENVIRONMENT (the realistic happy contract here): in THIS EDT there is NO active
debug session and TestConfiguration has NO running infobase / launched
application, so DebugSessionRegistry holds ZERO live threads. step's flow is
therefore fully exercised UP TO the thread lookup, and for any input we can supply
the REAL, CORRECT contract is the stale-threadId SENTINEL — a clear message that
names the missing precondition (a live threadId) AND the recovery step (call
wait_for_break again). That sentinel IS the coverage. We deliberately do NOT start
a real infobase/debug_launch (heavy, not configured for this fixture); the sentinel
+ the negative matrix are the coverage.

NOTE THE ORDER: threadId is validated FIRST, then kind, then the thread lookup. So
to reach the "kind is required" guard you MUST pass a positive threadId; and to
reach the stale-threadId SENTINEL you must pass BOTH a positive threadId AND a
non-empty kind. A bad/unknown kind value cannot be diagnosed without a live thread
(the unknown-kind DEFAULT branch sits AFTER the registry lookup), so a stale
threadId short-circuits it — see test_unknown_kind_* below.

String-matching note: the stale sentinel literal contains an em dash
("stale threadId — call wait_for_break again"); to stay robust against any
JSON/Gson unicode escaping we assert on the stable ASCII fragments
"stale threadId" and "wait_for_break" (each is specific and mutation-sensitive)
rather than the exact dash glyph.

DIFF: step operates on the running infobase / EDT debug model, never on the
git-tracked project tree. So every test also asserts assert_no_diff() — a debug
control tool that mutated TestConfiguration source would be a bug, and this catches
it.

Fixture inventory referenced (TestConfiguration, English Names): CommonModule.Calc
(Function Add lines 1-3, Procedure Test lines 5-7) — referenced only in the prose
of the can't-reach-without-a-session AUDIT notes; no project file is read or
written by these tests, by design.
"""

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    e2e_test,
)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL — the realistic correct contract in a no-session environment
# (a valid-SHAPED step request -> the actionable stale-threadId sentinel)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="step", kind="read")
def test_no_session_valid_call_returns_stale_thread_sentinel():
    """Valid-SHAPED call in a no-session environment -> clear stale-thread sentinel.

    With a POSITIVE threadId and a valid kind ("over"), execute() passes both
    argument guards and reaches DebugSessionRegistry.getThread(threadId). In this
    EDT there is no active debug session, so threadsById is empty and the lookup
    returns null, yielding the sentinel:

        "stale threadId — call wait_for_break again"

    This is the realistic happy contract: a precise, actionable message that names
    the failed precondition (a live threadId) AND the recovery step (call
    wait_for_break again). Mutation-sensitive: a broken tool that skipped the thread
    lookup, stepped a phantom thread, or returned a bare/opaque error would NOT
    produce this specific sentinel.
    """
    r = call("step", {"threadId": 999999, "kind": "over"})
    err = assert_error(r, "no active debug session -> stale threadId sentinel")
    # Names the failed precondition (stale thread) + the recovery tool (wait_for_break).
    assert_error_quality(err, names=["stale threadId"], suggests=["wait_for_break"],
                         ctx="stale-thread sentinel names the precondition and the next step")
    assert_no_diff("a debug control tool must not touch the project on disk")


@e2e_test(tool="step", kind="read")
def test_no_session_every_kind_reports_stale_thread_sentinel():
    """All three enum kinds (over/into/out) gate on the SAME missing thread.

    The per-kind canStepOver/Into/Return capability checks sit INSIDE the switch,
    which runs only AFTER the thread is resolved. With no live thread, every kind
    must short-circuit at the identical stale-threadId sentinel — no kind may leak
    a "cannot step over/into/out" capability error (that would falsely imply a real
    stepper existed). This proves the thread precondition is enforced uniformly
    across the enum, not just for one branch.
    """
    for kind in ("over", "into", "out"):
        r = call("step", {"threadId": 4242, "kind": kind})
        err = assert_error(r, "kind=%s with no live thread" % kind)
        assert_error_quality(err, names=["stale threadId"], suggests=["wait_for_break"],
                             ctx="kind=%s short-circuits at the stale-thread sentinel" % kind)
        # Must NOT surface a capability error: those are unreachable without a thread.
        assert_not_contains(err, "cannot step",
                            "no live thread must not yield a 'cannot step' capability error")
    assert_no_diff("a debug control tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — missing required params; invalid threadId values/types;
# blank/unknown kind; guard ordering
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="step", kind="read")
def test_missing_all_params_reports_threadid_first():
    """No arguments at all -> threadId is validated FIRST -> "threadId is required".

    extractLongArgument(..., -1L) returns the -1L default for an absent threadId,
    so `threadId <= 0` fires before the kind guard is ever reached. The message must
    name the threadId parameter, not produce an NPE / bare "Error".
    """
    r = call("step", {})
    err = assert_error(r, "missing both required params")
    # AUDIT: "threadId is required" names the missing parameter but is NOT actionable
    # — it does not point at wait_for_break (the sibling that produces a valid
    # threadId). suggests=[] is deliberate; fix-card: make the required-arg guard
    # actionable (e.g. "threadId is required - get it from wait_for_break").
    assert_error_quality(err, names=["threadId"], suggests=[],
                         ctx="threadId guard runs before the kind guard")
    assert_contains(err, "required",
                    "missing threadId must hit the required-arg guard, not the thread lookup")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="step", kind="read")
def test_missing_threadid_only_errors_clearly():
    """kind present, threadId omitted -> still "threadId is required".

    Confirms the threadId guard is independent of the kind argument: a valid kind
    does not let a missing threadId slip through to the thread lookup.
    """
    r = call("step", {"kind": "over"})
    err = assert_error(r, "threadId omitted, kind present")
    # AUDIT: see test_missing_all_params_reports_threadid_first — not actionable.
    assert_error_quality(err, names=["threadId"], suggests=[],
                         ctx="missing threadId rejected even with a valid kind")
    assert_contains(err, "required",
                    "missing threadId must surface as a required-arg error")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="step", kind="read")
def test_missing_kind_with_valid_threadid_errors_clearly():
    """Positive threadId but no kind -> "kind is required (over/into/out)".

    This is the ONLY way to reach the kind guard: threadId must be > 0 so the
    threadId check passes, then the kind null/blank check rejects the absent kind.
    A broken tool that swapped the guard order (or never checked kind) would fail
    here. Note this guard is ACTIONABLE — it enumerates the valid enum values.
    """
    r = call("step", {"threadId": 4242})
    err = assert_error(r, "valid threadId, kind omitted")
    # The guard text "(over/into/out)" enumerates the legal values -> actionable.
    assert_error_quality(err, names=["kind"], suggests=["over", "into", "out"],
                         ctx="kind guard reached only when threadId is valid; lists legal values")
    assert_contains(err, "required",
                    "missing kind must surface as a required-arg error")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="step", kind="read")
def test_blank_kind_with_valid_threadid_errors_clearly():
    """Positive threadId + EMPTY-STRING kind -> "kind is required (over/into/out)".

    The kind guard is `kind == null || kind.isEmpty()`, so a blank kind is rejected
    by the same guard as an omitted one rather than being passed to the switch as an
    empty string (which would fall to the "unknown kind:" default). This pins the
    empty-string boundary on the kind argument.
    """
    r = call("step", {"threadId": 4242, "kind": ""})
    err = assert_error(r, "valid threadId, blank kind")
    assert_error_quality(err, names=["kind"], suggests=["over", "into", "out"],
                         ctx="empty-string kind rejected by the required-arg guard")
    # Must be the required-arg guard, NOT the "unknown kind" default branch.
    assert_not_contains(err, "unknown kind",
                        "blank kind must be caught by the required guard, not the switch default")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="step", kind="read")
def test_zero_threadid_is_rejected_boundary():
    """Boundary: threadId == 0 -> rejected by `threadId <= 0` -> "threadId is required".

    0 is never a valid thread id (DebugSessionRegistry's idGenerator starts at 1),
    so the <= 0 guard must reject it rather than calling getThread(0). A regression
    to `< 0` (treating 0 as valid) would fall through to the stale-thread sentinel
    and fail this assertion — exactly the mutation this test catches.
    """
    r = call("step", {"threadId": 0, "kind": "over"})
    err = assert_error(r, "threadId == 0 boundary")
    assert_error_quality(err, names=["threadId"], suggests=[],
                         ctx="zero threadId rejected by the <= 0 guard")
    # It must be the required-arg guard, NOT the stale-thread branch: 0 never reaches
    # the registry lookup.
    assert_contains(err, "required",
                    "threadId 0 must hit the required-arg guard, not the thread lookup")
    assert_not_contains(err, "stale threadId",
                        "threadId 0 must not reach the registry lookup / stale sentinel")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="step", kind="read")
def test_negative_threadid_is_rejected():
    """A negative threadId -> rejected by `threadId <= 0` -> "threadId is required".

    Negative ids are never issued; the guard must reject them before any registry
    lookup. Distinguishes the required-arg guard from the stale-thread sentinel.
    """
    r = call("step", {"threadId": -7, "kind": "into"})
    err = assert_error(r, "negative threadId")
    assert_error_quality(err, names=["threadId"], suggests=[],
                         ctx="negative threadId rejected by the <= 0 guard")
    assert_contains(err, "required",
                    "negative threadId must hit the required-arg guard, not the thread lookup")
    assert_not_contains(err, "stale threadId",
                        "negative threadId must not reach the registry lookup / stale sentinel")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="step", kind="read")
def test_non_numeric_threadid_is_coerced_to_default_and_rejected():
    """A non-numeric threadId ("abc") -> extractLongArgument can't parse it ->
    returns the -1L default -> `threadId <= 0` -> "threadId is required".

    This proves the tool degrades GRACEFULLY on a wrong-typed threadId: it returns a
    clean required-arg error instead of throwing a NumberFormatException / leaking a
    stack trace (assert_error_quality also fails the test if the message looks like a
    raw stack trace). A broken parser that propagated the exception would fail here.
    """
    r = call("step", {"threadId": "abc", "kind": "over"})
    err = assert_error(r, "non-numeric threadId")
    assert_error_quality(err, names=["threadId"], suggests=[],
                         ctx="non-numeric threadId coerced to default and rejected cleanly")
    assert_contains(err, "required",
                    "non-numeric threadId must surface as a clean required-arg error")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="step", kind="read")
def test_unknown_kind_with_stale_threadid_short_circuits_to_stale_sentinel():
    """Bogus enum value ("sideways") with a stale threadId -> stale-threadId sentinel.

    The schema declares kind as an enum {over, into, out}, but the SERVER does not
    reject an out-of-enum value up front: execute() only rejects null/blank kind,
    then resolves the thread, and the unknown-kind DEFAULT branch ("unknown kind:
    <kind>") sits AFTER getThread(). So with a stale threadId the bogus kind never
    reaches the switch — the missing-thread precondition short-circuits first. This
    pins the ordering threadId-check -> kind-blank-check -> thread-lookup -> switch.

    # AUDIT: the dedicated "unknown kind: <kind>" sentinel for a non-{over,into,out}
    # value is UNREACHABLE without a live suspended thread (the switch runs only
    # after getThread() returns non-null). It also means a non-enum kind is NOT
    # validated against the schema enum at the wire boundary — a richly-shaped but
    # invalid kind is only diagnosable once a real thread exists. Reaching/asserting
    # that branch needs a real debug_launch + wait_for_break against a running
    # infobase, which this fixture does not provide. Deferred to a live-session run;
    # flagged here so the gap is explicit rather than silently skipped.
    """
    r = call("step", {"threadId": 555555, "kind": "sideways"})
    err = assert_error(r, "bogus kind gated by the missing thread")
    # With no live thread, the bogus kind must NOT surface as an enum/unknown-kind
    # error — the missing-thread precondition wins.
    assert_contains(err, "stale threadId",
                    "a stale threadId must short-circuit before the kind switch")
    assert_not_contains(err, "unknown kind",
                        "no live thread must not reach the unknown-kind switch default")
    assert_error_quality(err, names=["stale threadId"], suggests=["wait_for_break"],
                         ctx="bogus kind still yields the stale-thread sentinel, not an enum error")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="step", kind="read")
def test_out_of_range_timeout_does_not_mask_missing_thread():
    """An absurd timeout (clamped to [1, 600]) does not change the no-session verdict.

    timeout is clamped by clampTimeout BEFORE the thread lookup, so an out-of-range
    value (here 100000, which clamps to MAX_TIMEOUT=600) is harmless and must NOT
    alter the diagnosis: with no live thread the call still returns the stale-thread
    sentinel, and crucially it must NOT block for the (clamped) timeout because the
    thread lookup fails fast, long before waitForSuspend is ever reached. A
    regression that consulted timeout before validating the thread (or that surfaced
    a timeout/benign-success response) would fail this assertion.
    """
    r = call("step", {"threadId": 333333, "kind": "out", "timeout": 100000})
    err = assert_error(r, "out-of-range timeout with a stale threadId")
    assert_contains(err, "stale threadId",
                    "timeout clamping must not mask the missing-thread diagnosis")
    # Must NOT be the benign timeout success ({hit:false, reason:"timeout"}): that
    # path is only reached after a real step on a live thread.
    assert_not_contains(err, "reason",
                        "no live thread must not produce the benign step-timeout response")
    assert_error_quality(err, names=["stale threadId"], suggests=["wait_for_break"],
                         ctx="out-of-range timeout still yields the stale-thread sentinel")
    assert_no_diff("an invalid call must not touch the project on disk")
