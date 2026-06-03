"""
e2e tests for resume (kind: read).

resume is a DEBUG/RUNTIME tool: it resumes a SUSPENDED debug thread (by threadId
from wait_for_break) or all threads of a debug target (by applicationId), with a
no-argument convenience fallback that resumes the single active debug launch when
exactly one exists. It is a JSON-response tool (getResponseType() == JSON), so a
ToolResult.error payload is diverted to a structured isError:true response —
r.is_error is the machine signal and r.error_text() carries the message
(structuredContent.error). See ResumeTool.java + DebugSessionRegistry.java.

It is classified kind="read" here NOT because it observes (it is an action), but
for the e2e fixture contract: resume drives the live Eclipse debug model of a
running infobase and NEVER touches the git-tracked project source — so the
on-disk guardrail for EVERY test is assert_no_diff(). A "resume" that mutated
TestConfiguration source would be a bug, and that assertion catches it.

ENVIRONMENT (the realistic happy contract here):
  In THIS EDT there is NO active debug session and TestConfiguration has NO
  running infobase / launched application, so DebugSessionRegistry holds no
  snapshots/threads/targets and the launch manager has no active debug launch.
  resume therefore CANNOT resume anything real; the REAL, CORRECT contract for
  every input we can supply is a CLEAR, ACTIONABLE SENTINEL that names the missing
  precondition (a suspended thread / an active debug launch) and the next step
  (wait_for_break / debug_status). That sentinel IS the coverage — we deliberately
  do NOT start an infobase/debug_launch (heavy, not configured for this fixture).

  The exact sentinels, read straight from ResumeTool.execute() in branch order
  (threadId is resolved FIRST, before applicationId / the auto-resolution fallback):

    1. threadId > 0 but not in the registry (stale / never issued)
         -> "stale threadId — call wait_for_break again"
    2. no threadId, no applicationId, and no single active debug launch
         -> "Provide threadId or applicationId — no single active debug launch
             available for auto-resolution. Use debug_status to list active launches."
    3. applicationId given but no matching active, non-terminated debug target
         -> "no active debug target for applicationId: <id>"
    (only reachable WITH a live session, so NOT exercisable here — see AUDIT notes:)
       - thread present but !canResume -> "thread cannot resume (state: ...)"
       - target present but !canResume -> "debug target cannot resume"
       - the success paths            -> {resumed:true, scope:"thread"|"target", ...}

PARAMETERS (both OPTIONAL — there is no required param to omit; the no-arg call is
a legitimate convenience path that, here, surfaces the no-session sentinel):
  - threadId      (integer) — a stable thread id produced by wait_for_break.
  - applicationId (string)  — real app id or 'attach:<configName>'; resumes all
                              threads of that debug target.
  Resolution order in execute(): `if (threadId > 0)` is checked BEFORE the
  applicationId / lone-launch fallback. That ordering is itself a behavior under
  test (a stale threadId must surface the threadId sentinel, NOT the no-arg
  fallback, even when an applicationId is also supplied).

String-matching note: the stale-threadId sentinel literal contains an em dash
("stale threadId — call wait_for_break again"); to stay robust against any
JSON/Gson/unicode escaping we assert on the stable ASCII fragments
("stale threadId", "wait_for_break") rather than the exact dash glyph.

Fixture inventory referenced (TestConfiguration, English Names): CommonModule.Calc
(Function Add lines 1-3, Procedure Test lines 5-7) — referenced only in the prose
of the can't-reach-without-a-session AUDIT notes; no project file is read/written.
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
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="resume", kind="read")
def test_no_args_no_session_returns_clear_actionable_sentinel():
    """No args + no active debug launch -> the auto-resolution fallback sentinel.

    With neither threadId nor applicationId, execute() skips the threadId branch
    (threadId default -1L is not > 0), then computes effectiveAppId via
    findLoneActiveApplicationId(); in this EDT there is no debug launch, so that
    is null and the tool returns the no-session fallback sentinel. This IS the
    happy contract here: a clear message that names what to provide (threadId or
    applicationId), WHY auto-resolution failed (no single active debug launch),
    AND the actionable next step (debug_status to list active launches).

    Mutation-sensitive: a broken tool that returned a benign success
    ({resumed:true}), a bare "Error", or resumed a phantom target would fail
    assert_error + the specific-fragment assertions below.
    """
    r = call("resume", {})
    err = assert_error(r, "no args + no active debug launch")
    # Names both inputs the caller could provide AND the recovery/inspection tool.
    assert_error_quality(
        err,
        names=["threadId", "applicationId"],
        suggests=["debug_status"],
        ctx="no-session fallback names the inputs to provide and the inspection tool",
    )
    # State WHY auto-resolution failed (the distinguishing fragment of this branch).
    assert_contains(
        err, "no single active debug",
        "fallback sentinel must state WHY auto-resolution failed (no single active debug launch)",
    )
    # It must be the fallback branch, NOT a stale-thread / no-target diagnosis.
    assert_not_contains(
        err, "stale threadId",
        "the no-arg call never entered the threadId branch, so must not claim a stale threadId",
    )
    assert_no_diff("a debug action tool must not touch the project on disk")


@e2e_test(tool="resume", kind="read")
def test_valid_shaped_threadid_no_session_returns_stale_thread_sentinel():
    """Valid-SHAPED call (positive threadId) in a no-session env -> stale-thread sentinel.

    A POSITIVE threadId enters `if (threadId > 0)` and calls
    DebugSessionRegistry.getThread(threadId). In this EDT there has never been a
    suspend, so threadsById is empty, the lookup returns null, and the tool yields:

        "stale threadId — call wait_for_break again"

    This is a realistic happy contract: a precise, actionable message naming the
    failed precondition (a live thread id) AND the recovery step (call
    wait_for_break again). Mutation-sensitive: a tool that ignored an unknown
    threadId and silently fell back to the no-arg path, or resumed a phantom
    thread, would NOT produce this specific sentinel.
    """
    r = call("resume", {"threadId": 999999})
    err = assert_error(r, "positive but stale threadId, no session")
    assert_error_quality(
        err,
        names=["threadId"],
        suggests=["wait_for_break"],
        ctx="stale-threadId sentinel names the param and the recovery tool",
    )
    assert_contains(
        err, "stale threadId",
        "must diagnose the threadId as stale, not a generic failure",
    )
    # Must NOT degrade into the no-arg fallback message (that would mean the
    # threadId branch was never entered / the unknown id was silently dropped).
    assert_not_contains(
        err, "no single active debug",
        "a stale threadId must be reported as stale, not swallowed into the no-arg fallback",
    )
    assert_no_diff("a debug action tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — invalid/non-existent values, resolution order, boundaries
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="resume", kind="read")
def test_nonexistent_applicationid_errors_and_names_value():
    """A concrete applicationId that has no active debug target -> names the bad value.

    With no threadId and a non-empty applicationId, execute() skips the threadId
    branch, sets effectiveAppId = applicationId (no fallback), then
    findActiveTarget(appId) returns null (no matching non-terminated target in this
    EDT) -> "no active debug target for applicationId: <id>". The exact bad value
    MUST be echoed; a resolver that ignored the id (and reported the generic no-arg
    fallback) or that resumed the wrong target would fail this.
    """
    bad = "no-such-app-id-e2e-zzz"
    r = call("resume", {"applicationId": bad})
    err = assert_error(r, "non-existent applicationId")
    # AUDIT: "no active debug target for applicationId: <id>" echoes the bad value
    # but is NOT actionable — it does not point at debug_status / get_applications
    # (the siblings that enumerate valid applicationIds) nor at debug_launch (to
    # start one). suggests=[] is deliberate; fix-card: make this sentinel actionable.
    assert_error_quality(
        err,
        names=[bad],
        suggests=[],
        ctx="non-existent applicationId echoes the bad value",
    )
    assert_contains(
        err, "no active debug target",
        "must diagnose the missing debug target, not a generic failure",
    )
    # It must be the applicationId branch, NOT the no-arg fallback (which would mean
    # the supplied id was dropped and auto-resolution ran instead).
    assert_not_contains(
        err, "no single active debug",
        "a supplied applicationId must be resolved, not replaced by the no-arg fallback",
    )
    assert_no_diff("a debug action tool must not touch the project on disk")


@e2e_test(tool="resume", kind="read")
def test_resolution_order_threadid_takes_precedence_over_applicationid():
    """Both threadId (stale) and applicationId supplied -> the threadId branch wins.

    execute() checks `if (threadId > 0)` BEFORE it ever looks at applicationId /
    the lone-launch fallback. So when both are present, a stale positive threadId
    must surface as the stale-threadId sentinel — NOT the "no active debug target
    for applicationId: <id>" message. This pins the documented precedence; a
    refactor that resolved applicationId first would flip the sentinel and fail
    here.
    """
    bad_app = "ignored-app-id-e2e"
    r = call("resume", {"threadId": 424242, "applicationId": bad_app})
    err = assert_error(r, "threadId precedence over applicationId")
    assert_contains(
        err, "stale threadId",
        "with both present the threadId branch must resolve first",
    )
    # The applicationId must NOT appear: proof the threadId branch short-circuited
    # before applicationId was ever considered.
    assert_not_contains(
        err, bad_app,
        "the threadId branch must short-circuit before applicationId is resolved",
    )
    assert_error_quality(
        err,
        names=["threadId"],
        suggests=["wait_for_break"],
        ctx="precedence sentinel is the stale-threadId one",
    )
    assert_no_diff("a debug action tool must not touch the project on disk")


@e2e_test(tool="resume", kind="read")
def test_zero_threadid_falls_through_to_no_session_fallback_boundary():
    """Boundary: threadId == 0 -> NOT > 0 -> skips the threadId branch -> no-arg fallback.

    DebugSessionRegistry's idGenerator starts at 1, so 0 is never a valid thread id;
    the `threadId > 0` guard must treat 0 as "absent" and fall through to the
    auto-resolution fallback (which, with no applicationId and no active launch,
    yields the no-session sentinel) — it must NOT call getThread(0) and report a
    stale threadId. A regression to `>= 0` (treating 0 as a real id) would instead
    surface "stale threadId" and fail this, which is exactly the mutation this
    boundary test catches.
    """
    r = call("resume", {"threadId": 0})
    err = assert_error(r, "threadId == 0 boundary")
    # 0 is treated as absent -> the no-arg fallback sentinel, NOT the stale branch.
    assert_contains(
        err, "no single active debug",
        "threadId 0 must be treated as absent and hit the auto-resolution fallback",
    )
    assert_not_contains(
        err, "stale threadId",
        "threadId 0 must NOT enter the threadId branch (idGenerator starts at 1)",
    )
    assert_error_quality(
        err,
        names=["threadId", "applicationId"],
        suggests=["debug_status"],
        ctx="zero threadId degrades to the no-session fallback, not a stale-thread error",
    )
    assert_no_diff("a debug action tool must not touch the project on disk")


@e2e_test(tool="resume", kind="read")
def test_negative_threadid_falls_through_to_no_session_fallback():
    """A negative threadId -> NOT > 0 -> skips the threadId branch -> no-arg fallback.

    Negative ids are never issued; the `threadId > 0` guard must skip the threadId
    branch and fall through to auto-resolution, surfacing the no-session fallback
    sentinel rather than a stale-threadId diagnosis. Distinguishes the threadId
    branch from the fallback path.
    """
    r = call("resume", {"threadId": -5})
    err = assert_error(r, "negative threadId")
    assert_contains(
        err, "no single active debug",
        "a negative threadId must fall through to the auto-resolution fallback",
    )
    assert_not_contains(
        err, "stale threadId",
        "a negative threadId must NOT be looked up as a real thread id",
    )
    assert_error_quality(
        err,
        names=["threadId", "applicationId"],
        suggests=["debug_status"],
        ctx="negative threadId degrades to the no-session fallback",
    )
    assert_no_diff("a debug action tool must not touch the project on disk")


@e2e_test(tool="resume", kind="read")
def test_non_numeric_threadid_coerced_to_default_and_falls_through():
    """A non-numeric threadId ("abc") -> extractLongArgument can't parse it ->
    returns the -1L default -> NOT > 0 -> the no-arg fallback sentinel.

    This proves the tool degrades GRACEFULLY on a wrong-typed threadId: it returns
    the clean no-session fallback instead of throwing a NumberFormatException /
    leaking a stack trace (assert_error_quality also fails the test if the message
    looks like a raw stack trace). A broken parser that propagated the exception
    would fail here.
    """
    r = call("resume", {"threadId": "abc"})
    err = assert_error(r, "non-numeric threadId")
    assert_contains(
        err, "no single active debug",
        "a non-numeric threadId is coerced to the default and hits the fallback",
    )
    assert_not_contains(
        err, "stale threadId",
        "a non-numeric threadId must not be looked up as a real thread id",
    )
    assert_error_quality(
        err,
        names=["threadId", "applicationId"],
        suggests=["debug_status"],
        ctx="non-numeric threadId coerced to default and degraded cleanly to the fallback",
    )
    assert_no_diff("a debug action tool must not touch the project on disk")


@e2e_test(tool="resume", kind="read")
def test_empty_applicationid_treated_as_absent_falls_through_to_fallback():
    """Boundary: applicationId == "" -> treated as absent -> the no-arg fallback.

    execute() guards `applicationId != null && !applicationId.isEmpty()` before
    using it, so an EMPTY-STRING applicationId is NOT resolved as a real id; it
    falls through to findLoneActiveApplicationId() and, with no active launch,
    surfaces the no-session fallback. It must NOT produce "no active debug target
    for applicationId: " (an empty id resolved against the target search) — that
    would mean the empty-string boundary was mishandled.
    """
    r = call("resume", {"applicationId": ""})
    err = assert_error(r, "empty-string applicationId")
    assert_contains(
        err, "no single active debug",
        "an empty applicationId must be treated as absent and hit the fallback",
    )
    assert_not_contains(
        err, "no active debug target for applicationId",
        "an empty applicationId must not be resolved against the debug-target search",
    )
    assert_error_quality(
        err,
        names=["threadId", "applicationId"],
        suggests=["debug_status"],
        ctx="empty applicationId degrades to the no-session fallback",
    )
    assert_no_diff("a debug action tool must not touch the project on disk")


@e2e_test(tool="resume", kind="read")
def test_attach_style_applicationid_no_target_errors_and_names_value():
    """A plausibly-shaped 'attach:<configName>' applicationId with no live target.

    The schema documents applicationId as "real or 'attach:<configName>'". An
    attach-style id is therefore a realistic input; with no matching active debug
    target in this EDT, findActiveTarget returns null and the tool echoes the id in
    "no active debug target for applicationId: attach:NoSuchConfig". Confirms the
    attach: form is treated as a normal applicationId (resolved, then reported as
    missing) rather than special-cased into a different/opaque error.
    """
    bad = "attach:NoSuchConfig-e2e"
    r = call("resume", {"applicationId": bad})
    err = assert_error(r, "attach-style applicationId with no target")
    # AUDIT: echoes the id but is not actionable (no pointer to debug_status /
    # debug_launch). suggests=[] deliberate; same fix-card as the plain-id case.
    assert_error_quality(
        err,
        names=[bad],
        suggests=[],
        ctx="attach-style applicationId echoes the bad value",
    )
    assert_contains(
        err, "no active debug target",
        "an attach-style id with no live target must surface the missing-target sentinel",
    )
    assert_no_diff("a debug action tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# AUDIT — coverage gaps that genuinely require a live infobase / debug session
# (documented, NOT silently skipped; deferred to a live-session run):
#   * thread/target present but !canResume:
#       "thread cannot resume (state: suspended|running)" / "debug target cannot
#       resume" — needs a real wait_for_break-suspended thread (e.g. a breakpoint
#       hit inside CommonModule.Calc) whose resume-ability can be probed.
#   * the SUCCESS paths {resumed:true, scope:"thread"|"target", autoResolved:true}
#       — need a live, suspended debug target to actually resume.
#   These require a real debug_launch + a running infobase, which this fixture
#   does NOT provide; the sentinel + negative matrix above are the coverage here.
# ──────────────────────────────────────────────────────────────────────────────
