"""
e2e tests for get_variables (kind: read).

get_variables reads the variables visible in a stack frame of a SUSPENDED debug
thread. It is a pure DEBUG/RUNTIME tool: it never touches the project source — it
walks the live Eclipse debug model (DebugSessionRegistry) of a running 1C
infobase. getResponseType() == JSON, so on the wire the payload is delivered as
structuredContent and a ToolResult.error (success:false / error field) is flagged
isError:true; the harness surfaces that error string via r.error_text()
(structured["error"]). See GetVariablesTool.java + DebugSessionRegistry.java.

ENVIRONMENT (the realistic happy contract here):
  In THIS EDT there is NO active debug session and TestConfiguration has NO
  running infobase / launched application, so DebugSessionRegistry holds no
  snapshots/threads/frames. get_variables therefore CANNOT return real variables;
  the REAL, CORRECT contract for every input we can supply is a CLEAR, ACTIONABLE
  SENTINEL naming the missing precondition + the next step (wait_for_break). That
  sentinel IS the coverage — we deliberately do NOT start an infobase/debug_launch
  (heavy, not configured for this fixture).

  The exact sentinels (read straight from GetVariablesTool.execute, in branch order):
    - no frameRef AND no threadId, and no lone suspended debug launch
        -> "Provide frameRef or threadId — no single suspended debug launch
            available for auto-resolution. Call wait_for_break first."
    - frameRef > 0 but not in the registry (stale / never issued)
        -> "stale frameRef — call wait_for_break again"
    - threadId > 0 but not in the registry (stale / never issued)
        -> "stale threadId — call wait_for_break again"
    - (only reachable WITH a live session, so NOT exercisable here:)
        frameIndex out of range -> "frameIndex out of range (0..N)"
        expandPath not found     -> "expandPath not found: <path>"

PARAMETERS (all OPTIONAL — there is no required param to omit):
  frameRef:int, threadId:int, frameIndex:int(default 0), expandPath:str. Resolution
  order in execute(): frameRef > 0  ->  threadId > 0  ->  lone-suspended fallback.
  This ordering is itself a behavior under test (a stale threadId must surface the
  threadId sentinel, NOT the no-arg fallback sentinel).

DIFF: a debug-read tool operates on the running infobase / EDT workspace, never on
the git-tracked project, so EVERY test asserts assert_no_diff() — a debug tool that
mutated TestConfiguration source would be a bug, and this catches it.

Fixture inventory referenced (TestConfiguration, English Names): CommonModule.Calc
(Function Add lines 1-3, Procedure Test lines 5-7) — referenced only in the prose of
the can't-reach-without-a-session AUDIT notes; no project file is read or written.
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
@e2e_test(tool="get_variables", kind="read")
def test_no_session_returns_clear_actionable_sentinel():
    """No args + no active suspended debug launch -> the auto-resolution fallback.

    With neither frameRef nor threadId, execute() falls to
    findLoneActiveApplicationId(); in this EDT there is no debug launch, so snap is
    null and the tool returns the no-session sentinel. This IS the happy contract
    here: a clear message that names the missing precondition (no suspended debug
    launch) AND the actionable next step (call wait_for_break first).

    Mutation-sensitive: a broken tool that returned an empty/benign success, a bare
    "Error", or fabricated variables would fail assert_error + the specific-sentinel
    assertions below.
    """
    r = call("get_variables", {})
    err = assert_error(r, "no active suspended debug session")
    # Name the missing precondition AND the recovery step. The em-dash sentence is
    # asserted in delimiter-free fragments so JSON/Gson escaping can't break the match.
    assert_error_quality(
        err,
        names=["frameRef", "threadId"],
        suggests=["wait_for_break"],
        ctx="no-session sentinel names the inputs to provide and the recovery tool",
    )
    assert_contains(
        err, "no single suspended debug",
        "sentinel must state WHY auto-resolution failed (no suspended launch)",
    )
    assert_no_diff("a debug-read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — invalid / stale references + resolution-order proof
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_variables", kind="read")
def test_stale_frameref_errors_and_names_recovery():
    """frameRef > 0 that the registry does not know (never issued / session gone).

    Because there has never been a suspend in this EDT, framesById is empty, so
    getFrame(N) is null and the tool returns the stale-frameRef sentinel. The bad
    branch (frameRef) must be the one reported — not the no-arg fallback — proving
    execute() entered the frameRef branch and validated the id.

    Mutation-sensitive: a tool that ignored an unknown frameRef and silently fell
    back (or returned empty variables) would NOT produce this specific sentinel.
    """
    r = call("get_variables", {"frameRef": 999999})
    err = assert_error(r, "stale / unknown frameRef")
    assert_error_quality(
        err,
        names=["frameRef"],
        suggests=["wait_for_break"],
        ctx="stale-frameRef sentinel names the param and the recovery tool",
    )
    assert_contains(
        err, "stale",
        "must diagnose the frameRef as stale, not a generic failure",
    )
    assert_no_diff("a debug-read tool must not touch the project on disk")


@e2e_test(tool="get_variables", kind="read")
def test_stale_threadid_errors_and_names_recovery():
    """threadId > 0 that the registry does not know -> stale-threadId sentinel.

    With no frameRef supplied and threadId > 0, execute() enters the threadId
    branch; getThread(N) is null (empty registry) -> "stale threadId — call
    wait_for_break again". Distinct from the frameRef sentinel: confirms the two
    reference kinds are diagnosed independently.
    """
    r = call("get_variables", {"threadId": 888888})
    err = assert_error(r, "stale / unknown threadId")
    assert_error_quality(
        err,
        names=["threadId"],
        suggests=["wait_for_break"],
        ctx="stale-threadId sentinel names the param and the recovery tool",
    )
    assert_contains(
        err, "stale",
        "must diagnose the threadId as stale, not a generic failure",
    )
    # The threadId branch must NOT misreport as a frameRef problem.
    assert_contains(
        err, "stale threadId",
        "the threadId branch must name threadId specifically, not frameRef",
    )
    assert_no_diff("a debug-read tool must not touch the project on disk")


@e2e_test(tool="get_variables", kind="read")
def test_resolution_order_frameref_takes_precedence_over_threadid():
    """Both frameRef and threadId supplied (both stale) -> frameRef wins.

    execute() checks `if (frameRef > 0)` BEFORE `else if (threadId > 0)`, so when
    both are present the frameRef branch resolves first and a stale frameRef must
    surface as the frameRef sentinel — NOT the threadId one. This pins the
    documented precedence (frameRef preferred over threadId+frameIndex); a
    refactor that flipped the branch order would flip the sentinel and fail here.
    """
    r = call("get_variables", {"frameRef": 777777, "threadId": 666666})
    err = assert_error(r, "frameRef precedence over threadId")
    assert_contains(
        err, "stale frameRef",
        "with both refs present the frameRef branch must resolve first",
    )
    assert_error_quality(
        err,
        names=["frameRef"],
        suggests=["wait_for_break"],
        ctx="precedence sentinel is the frameRef one",
    )
    assert_no_diff("a debug-read tool must not touch the project on disk")


@e2e_test(tool="get_variables", kind="read")
def test_threadid_with_frameindex_still_reports_stale_threadid():
    """threadId + frameIndex, threadId stale -> stale-threadId sentinel.

    The frameIndex-out-of-range check sits AFTER getThread() inside the threadId
    branch, so it is only reachable once the thread resolves. With a stale threadId
    the tool stops at the stale-threadId sentinel — frameIndex is irrelevant. This
    proves a bogus frameIndex does not mask / change the missing-session diagnosis.

    # AUDIT: the genuine "frameIndex out of range (0..N)" sentinel is NOT reachable
    # in a no-session environment (it requires a live suspended thread whose frames
    # can be counted). Exercising it would need a real debug_launch + wait_for_break
    # against a running infobase, which this fixture does not provide. Coverage of
    # that branch is deferred to a live-session run; documented here so the gap is
    # explicit rather than silently skipped.
    """
    r = call("get_variables", {"threadId": 555555, "frameIndex": 42})
    err = assert_error(r, "stale threadId masks frameIndex check")
    assert_contains(
        err, "stale threadId",
        "stale threadId must short-circuit before the frameIndex range check",
    )
    assert_error_quality(
        err,
        names=["threadId"],
        suggests=["wait_for_break"],
        ctx="threadId+frameIndex still yields the threadId sentinel",
    )
    assert_no_diff("a debug-read tool must not touch the project on disk")


@e2e_test(tool="get_variables", kind="read")
def test_expandpath_without_session_falls_through_to_no_session_sentinel():
    """expandPath supplied but no frameRef/threadId and no suspended launch.

    The expandPath drill-down (VariableSerializer.resolvePath) runs only AFTER a
    frame is resolved. With no reference and no lone suspended launch, execute()
    never reaches the expandPath branch — it returns the no-session fallback
    sentinel. So even a richly-shaped call cannot fabricate variables without a
    live session. The sentinel must still name the recovery path.

    # AUDIT: the dedicated "expandPath not found: <path>" sentinel (a *resolved*
    # frame whose dot-path has no such child) is unreachable without a live
    # suspended frame, so it is not exercised here. Reaching it requires a real
    # debug_launch + a breakpoint hit (e.g. inside CommonModule.Calc) and a frame
    # with structured locals. Deferred to a live-session run; flagged, not skipped.
    """
    r = call("get_variables", {"expandPath": "SomeStruct.Field.Missing"})
    err = assert_error(r, "expandPath with no session")
    # It must be the no-session fallback, NOT a confusing "expandPath not found"
    # (which would falsely imply a frame existed and was searched).
    assert_contains(
        err, "no single suspended debug",
        "with no session, expandPath must surface the no-session sentinel",
    )
    # Guard against a regression that ran resolvePath against a null/garbage frame
    # and reported "expandPath not found" — that would falsely imply a frame existed
    # and was searched. The no-session path must NOT claim it searched a path.
    assert_not_contains(
        err, "expandPath not found",
        "no-session call must not claim it searched a (non-existent) frame",
    )
    assert_error_quality(
        err,
        names=["frameRef", "threadId"],
        suggests=["wait_for_break"],
        ctx="expandPath-without-session yields the no-session sentinel",
    )
    assert_no_diff("a debug-read tool must not touch the project on disk")
