"""
e2e tests for set_variable (kind: read).

Registered kind="read" — the debug-tool convention in this suite (every debug tool,
INCLUDING the mutating ones resume / step / set_breakpoint, uses kind="read"): it
mutates the LIVE debug model, never the git-tracked project, so the isolation idiom is
the read guardrail assert_no_diff(), not a file-write diff. The tool is nonetheless a
WRITE semantically — it sets a variable's value.

set_variable WRITES a BSL variable's value into a stack frame of a SUSPENDED debug
thread and returns the variable after the write. It is a pure DEBUG/RUNTIME tool: it
never touches the project source — it resolves the frame through the SAME shared
frame resolver get_variables uses (utils/DebugFrameResolver over the live Eclipse
debug model / DebugSessionRegistry), then sets the value via the plain Eclipse
IValueModification API. getResponseType() == JSON, so on the wire the payload is
structuredContent and a ToolResult.error (success:false / error field) is flagged
isError:true; the harness surfaces that error string via r.error_text()
(structured["error"]). See SetVariableTool.java + DebugFrameResolver.java.

ENVIRONMENT (the realistic happy contract here):
  In THIS EDT there is NO active debug session and TestConfiguration has NO running
  infobase / launched application, so DebugSessionRegistry holds no snapshots /
  threads / frames. set_variable therefore CANNOT reach a real variable to write; the
  REAL, CORRECT contract for every frame reference we can supply is a CLEAR,
  ACTIONABLE SENTINEL naming the missing precondition + the next step
  (wait_for_break). That sentinel IS the coverage — we deliberately do NOT start an
  infobase / debug_launch (heavy, not configured for this fixture), so the
  happy/resume path (set a var -> get_variables re-read shows it -> resume -> new
  value in effect) is exercised MANUALLY on a live suspended session, not here.

  set_variable resolves the frame FIRST (through DebugFrameResolver, the exact triplet
  get_variables uses), so with no live session every call short-circuits on the SAME
  frame-resolution sentinels — the strings are shared with get_variables verbatim:
    - no frameRef AND no threadId, and no lone suspended debug launch
        -> "Provide frameRef or threadId — no single suspended debug session
            available for auto-resolution. Call wait_for_break first."
    - frameRef > 0 but not in the registry (stale / never issued)
        -> "stale frameRef — call wait_for_break again"
    - threadId > 0 but not in the registry (stale / never issued)
        -> "stale threadId — call wait_for_break again"

  # AUDIT: the set_variable-SPECIFIC error branches — a resolved frame whose
  # `variableName` is not found ("variable not found"), a variable that is read-only
  # / does not support modification, and an "invalid value" rejected by verifyValue —
  # are all UNREACHABLE without a live suspended frame, so they are not exercised
  # here. Reaching them needs a real debug_launch + a breakpoint hit (e.g. inside
  # CommonModule.Calc) with a live frame whose variables can be addressed. Deferred to
  # a live-session run; flagged here so the gap is explicit rather than silently
  # skipped.

PARAMETERS:
  variableName:str (required), value:str (required); frame addressing is OPTIONAL and
  identical to get_variables — frameRef:int, threadId:int, frameIndex:int(default 0).
  Resolution order in the shared resolver: frameRef > 0 -> threadId > 0 -> lone-
  suspended fallback. Every call below supplies variableName + value so it is the
  FRAME-RESOLUTION branch (shared with get_variables) that is under test, not a
  missing-argument path. That ordering is itself a behavior under test (a stale
  threadId must surface the threadId sentinel, NOT the no-arg fallback sentinel).

DIFF: a debug-write tool mutates the running infobase / EDT debug model, never the
git-tracked project, so EVERY test asserts assert_no_diff() — a debug tool that
changed TestConfiguration source on disk would be a bug, and this catches it.
"""

from harness import (
    _post,
    call,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
    _fail,
)

# A concrete (name, value) pair used on every call so the frame-resolution branch — not
# a missing-required-argument path — is the one exercised. The value is a BSL literal.
_VAR = {"variableName": "Counter", "value": "42"}


# ──────────────────────────────────────────────────────────────────────────────
# PRESENCE / SCHEMA CONTRACT — the tool is registered and advertises its inputs
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="set_variable", kind="read")
def test_tool_is_advertised_with_the_expected_input_contract():
    """set_variable appears in tools/list and declares exactly its documented inputs.

    Proves registration on the live wire AND the parameter contract from the spec:
    variableName + value are REQUIRED; the frame-addressing trio
    (frameRef/threadId/frameIndex) is present but OPTIONAL (same addressing as
    get_variables). Mutation-sensitive: dropping the tool, misnaming a param, or
    forgetting to mark variableName/value required would fail here.
    """
    raw = _post("tools/list", {})
    tools = {t.get("name"): t for t in (raw.get("result", {}) or {}).get("tools", []) or []}
    tool = tools.get("set_variable")
    if tool is None:
        _fail("set_variable is not advertised in tools/list — is it registered in "
              "BuiltInToolRegistrar and the debug ToolGroup?")

    schema = tool.get("inputSchema") or {}
    props = schema.get("properties") or {}
    for p in ("frameRef", "threadId", "frameIndex", "variableName", "value"):
        if p not in props:
            _fail("set_variable inputSchema is missing the %r property; has %s"
                  % (p, sorted(props)))

    required = set(schema.get("required") or [])
    if required != {"variableName", "value"}:
        _fail("set_variable must require exactly variableName + value (the frame-addressing "
              "params are optional); required=%s" % sorted(required))

    assert_no_diff("reading tools/list must not modify the project")


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL — the realistic correct contract in a no-session environment
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="set_variable", kind="read")
def test_no_session_returns_clear_actionable_sentinel():
    """variableName+value but no frame ref and no suspended launch -> no-session sentinel.

    With neither frameRef nor threadId, the shared resolver falls to the lone-suspended
    fallback; in this EDT there is no debug launch, so it returns the no-session
    sentinel. This IS the happy contract here: a clear message naming the missing
    precondition (no suspended debug session) AND the actionable next step (call
    wait_for_break first) — the write never fabricates a frame to poke.

    Mutation-sensitive: a broken tool that silently "succeeded", returned a bare
    "Error", or claimed it set the variable would fail assert_error + the sentinel
    assertions below.
    """
    r = call("set_variable", _VAR)
    err = assert_error(r, "no active suspended debug session")
    assert_error_quality(
        err,
        names=["frameRef", "threadId"],
        suggests=["wait_for_break"],
        ctx="no-session sentinel names the inputs to provide and the recovery tool",
    )
    assert_contains(
        err, "no single suspended debug",
        "sentinel must state WHY auto-resolution failed (no suspended session)",
    )
    assert_no_diff("a debug-write tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — invalid / stale references + resolution-order proof
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="set_variable", kind="read")
def test_stale_frameref_errors_and_names_recovery():
    """frameRef > 0 that the registry does not know (never issued / session gone).

    Because there has never been a suspend in this EDT, framesById is empty, so the
    shared resolver's getFrame(N) is null and the tool returns the stale-frameRef
    sentinel — BEFORE it ever tries to write. The bad branch (frameRef) must be the one
    reported, not the no-arg fallback, proving the frameRef branch was entered and the
    id validated.

    Mutation-sensitive: a tool that ignored an unknown frameRef and silently fell back
    (or claimed a write) would NOT produce this specific sentinel.
    """
    r = call("set_variable", dict(_VAR, frameRef=999999))
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
    assert_no_diff("a debug-write tool must not touch the project on disk")


@e2e_test(tool="set_variable", kind="read")
def test_stale_threadid_errors_and_names_recovery():
    """threadId > 0 that the registry does not know -> stale-threadId sentinel.

    With no frameRef supplied and threadId > 0, the resolver enters the threadId branch;
    getThread(N) is null (empty registry) -> "stale threadId — call wait_for_break
    again". Distinct from the frameRef sentinel: confirms the two reference kinds are
    diagnosed independently, and that the write is never attempted.
    """
    r = call("set_variable", dict(_VAR, threadId=888888))
    err = assert_error(r, "stale / unknown threadId")
    assert_error_quality(
        err,
        names=["threadId"],
        suggests=["wait_for_break"],
        ctx="stale-threadId sentinel names the param and the recovery tool",
    )
    assert_contains(
        err, "stale threadId",
        "the threadId branch must name threadId specifically, not frameRef",
    )
    assert_no_diff("a debug-write tool must not touch the project on disk")


@e2e_test(tool="set_variable", kind="read")
def test_resolution_order_frameref_takes_precedence_over_threadid():
    """Both frameRef and threadId supplied (both stale) -> frameRef wins.

    The shared resolver checks `frameRef > 0` BEFORE `threadId > 0`, so when both are
    present the frameRef branch resolves first and a stale frameRef must surface as the
    frameRef sentinel — NOT the threadId one. This pins the documented precedence
    (frameRef preferred over threadId+frameIndex) for set_variable too; a refactor that
    flipped the branch order would flip the sentinel and fail here.
    """
    r = call("set_variable", dict(_VAR, frameRef=777777, threadId=666666))
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
    assert_no_diff("a debug-write tool must not touch the project on disk")
