"""
e2e tests for evaluate_expression (kind: read).

evaluate_expression is a DEBUG/RUNTIME tool: it evaluates an arbitrary BSL
expression in the context of a SUSPENDED stack frame. It is a JSON-response tool
(getResponseType() == JSON), so a ToolResult.error payload is diverted to a
structured isError:true response — r.is_error is the machine signal and
r.error_text() carries the message (read from structuredContent.error).

Declared parameters (EvaluateExpressionTool.getInputSchema), BOTH required:
  - frameRef   (integer) — a stable frame reference produced by wait_for_break.
  - expression (string)  — the BSL expression text to evaluate.

ENVIRONMENT (this EDT, this fixture): there is NO active debug session and
TestConfiguration has NO running infobase / launched application, so the
DebugSessionRegistry holds ZERO live frames. evaluate_expression's control flow
(EvaluateExpressionTool.execute) is therefore fully exercised UP TO the frame
lookup, which is the realistic happy contract here:

  1. frameRef <= 0  (absent -> default -1L, or 0, or negative, or non-numeric
                     which extractLongArgument coerces to the -1L default)
                                      -> ToolResult.error("frameRef is required")
  2. expression missing/blank        -> ToolResult.error("expression is required")
                                         (requireArgument: null OR "" is "missing")
  3. registry.getFrame(frameRef) == null  (no live frame in a no-session env)
                                      -> ToolResult.error(
                                           "stale frameRef - call wait_for_break again")

NOTE THE ORDER: frameRef is validated FIRST, then expression, then the frame
lookup. So to reach the "expression is required" branch you MUST pass a positive
frameRef; and to reach the stale-frameRef SENTINEL you must pass BOTH a positive
frameRef AND a non-empty expression. That sentinel IS the correct happy contract
in this no-session environment — it names the missing precondition (a frame from
wait_for_break) and the next step (call wait_for_break again). We deliberately do
NOT start a real infobase/debug session (heavy, not configured); the sentinel +
the negative matrix are the coverage.

DIFF: evaluate_expression operates on the running infobase via the Eclipse debug
model; it NEVER touches the git-tracked project tree. So every test also asserts
assert_no_diff() — a debug/read tool that mutated TestConfiguration source would
be a bug, and this catches it.

String-matching note: the stale sentinel literal contains an em dash
("stale frameRef - call wait_for_break again"); to stay robust against any
JSON/unicode escaping we assert on the stable ASCII fragments "stale frameRef"
and "wait_for_break" (each is specific and mutation-sensitive) rather than the
exact dash glyph.

Fixture inventory referenced (TestConfiguration, English Names): CommonModule
Calc (Function Add lines 1-3) — used only to build a realistic-looking BSL
expression string; no live frame exists to evaluate it against, by design.
"""

from harness import (
    call,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL  (no active debug session -> the stale-frameRef sentinel IS
# the correct, actionable happy contract here)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="evaluate_expression", kind="read")
def test_no_session_valid_call_returns_stale_frame_sentinel():
    """Valid-SHAPED call in a no-session environment -> clear stale-frame sentinel.

    With a POSITIVE frameRef and a NON-EMPTY expression, execute() passes both
    argument guards and reaches DebugSessionRegistry.getFrame(frameRef). In this
    EDT there is no active debug session, so the registry holds no frames and the
    lookup returns null, yielding the sentinel:

        "stale frameRef - call wait_for_break again"

    This is the realistic happy contract: a precise, actionable message that names
    the failed precondition (a live frameRef) AND the recovery step (call
    wait_for_break again). Mutation-sensitive: a broken tool that skipped the frame
    lookup, evaluated against a phantom frame, or returned a bare/opaque error would
    NOT produce this specific sentinel.
    """
    r = call("evaluate_expression", {"frameRef": 999999, "expression": "Add(1, 2)"})
    err = assert_error(r, "no active debug session -> stale frameRef sentinel")
    # Names the failed precondition (stale frame) + the recovery tool (wait_for_break).
    assert_error_quality(err, names=["stale frameRef"], suggests=["wait_for_break"],
                         ctx="stale-frame sentinel names the precondition and the next step")
    assert_no_diff("a debug/read tool must not touch the project on disk")


@e2e_test(tool="evaluate_expression", kind="read")
def test_no_session_validates_frame_before_evaluating_expression():
    """Frame is validated BEFORE the expression is ever evaluated.

    Even a syntactically-broken BSL expression must surface the stale-frameRef
    sentinel (not a BSL parse/eval error) when no live frame exists, because the
    frame lookup happens before the watch-expression delegate is invoked. This
    pins the ordering frameRef-check -> frame-lookup -> evaluate, and proves the
    tool never tries to evaluate arbitrary BSL without a real suspended frame.
    """
    r = call("evaluate_expression",
             {"frameRef": 12345, "expression": ")))not-valid-bsl((("})
    err = assert_error(r, "garbage expression still gated by the missing frame")
    assert_error_quality(err, names=["stale frameRef"], suggests=["wait_for_break"],
                         ctx="missing frame is reported before any BSL evaluation")
    assert_no_diff("a debug/read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX  (missing required params; invalid frameRef values/types;
# blank expression)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="evaluate_expression", kind="read")
def test_missing_both_params_reports_frameref_first():
    """No arguments at all -> frameRef is validated FIRST -> "frameRef is required".

    extractLongArgument(..., -1L) returns the -1L default for an absent frameRef,
    so `frameRef <= 0` fires before the expression guard is ever reached. The
    message must name the frameRef parameter, not produce an NPE / bare "Error".
    """
    r = call("evaluate_expression", {})
    err = assert_error(r, "missing both required params")
    # AUDIT: "frameRef is required" names the missing parameter but is not
    # actionable — it does not point at wait_for_break (the sibling that produces a
    # valid frameRef). suggests=[] is deliberate; fix-card: make the required-arg
    # guard actionable (e.g. "frameRef is required - get it from wait_for_break").
    assert_error_quality(err, names=["frameRef"], suggests=[],
                         ctx="frameRef guard runs before the expression guard")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="evaluate_expression", kind="read")
def test_missing_frameref_only_errors_clearly():
    """expression present, frameRef omitted -> still "frameRef is required".

    Confirms the frameRef guard is independent of the expression argument: a
    well-formed expression does not let a missing frameRef slip through.
    """
    r = call("evaluate_expression", {"expression": "Add(1, 2)"})
    err = assert_error(r, "frameRef omitted, expression present")
    # AUDIT: see test_missing_both_params_reports_frameref_first — not actionable.
    assert_error_quality(err, names=["frameRef"], suggests=[],
                         ctx="missing frameRef rejected even with a valid expression")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="evaluate_expression", kind="read")
def test_missing_expression_with_valid_frameref_errors_clearly():
    """Positive frameRef but no expression -> "expression is required".

    This is the ONLY way to reach the expression guard: frameRef must be > 0 so the
    frameRef check passes, then requireArgument(params, "expression") rejects the
    absent expression. A broken tool that swapped the guard order (or never checked
    the expression) would fail here.
    """
    r = call("evaluate_expression", {"frameRef": 4242})
    err = assert_error(r, "valid frameRef, expression omitted")
    # AUDIT: "expression is required" names the parameter but offers no next step
    # (no example of a valid BSL expression / pointer to read_method_source to find
    # something to evaluate). suggests=[] is deliberate; fix-card.
    assert_error_quality(err, names=["expression"], suggests=[],
                         ctx="expression guard reached only when frameRef is valid")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="evaluate_expression", kind="read")
def test_blank_expression_with_valid_frameref_errors_clearly():
    """Positive frameRef + EMPTY-STRING expression -> "expression is required".

    requireArgument treats null OR "" as missing (value == null || value.isEmpty()),
    so a blank expression is rejected by the same guard as an omitted one rather
    than being passed to the debug delegate as an empty BSL string. This pins the
    empty-string boundary on the expression argument.
    """
    r = call("evaluate_expression", {"frameRef": 4242, "expression": ""})
    err = assert_error(r, "valid frameRef, blank expression")
    # AUDIT: blank surfaces via the same non-actionable "expression is required".
    assert_error_quality(err, names=["expression"], suggests=[],
                         ctx="empty-string expression rejected by the required-arg guard")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="evaluate_expression", kind="read")
def test_zero_frameref_is_rejected_boundary():
    """Boundary: frameRef == 0 -> rejected by `frameRef <= 0` -> "frameRef is required".

    0 is never a valid stable frame id (DebugSessionRegistry's idGenerator starts at
    1), so the <= 0 guard must reject it rather than calling getFrame(0). A regression
    to `< 0` (treating 0 as valid) would fall through to the stale-frame sentinel and
    fail this assertion — that is exactly the mutation this test catches.
    """
    r = call("evaluate_expression", {"frameRef": 0, "expression": "Add(1, 2)"})
    err = assert_error(r, "frameRef == 0 boundary")
    assert_error_quality(err, names=["frameRef"], suggests=[],
                         ctx="zero frameRef rejected by the <= 0 guard")
    # It must be the required-arg guard, NOT the stale-frame branch: 0 never reaches
    # the registry lookup.
    assert_contains(err, "required",
                    "frameRef 0 must hit the required-arg guard, not the frame lookup")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="evaluate_expression", kind="read")
def test_negative_frameref_is_rejected():
    """A negative frameRef -> rejected by `frameRef <= 0` -> "frameRef is required".

    Negative ids are never issued; the guard must reject them before any registry
    lookup. Distinguishes the required-arg guard from the stale-frame sentinel.
    """
    r = call("evaluate_expression", {"frameRef": -7, "expression": "Add(1, 2)"})
    err = assert_error(r, "negative frameRef")
    assert_error_quality(err, names=["frameRef"], suggests=[],
                         ctx="negative frameRef rejected by the <= 0 guard")
    assert_contains(err, "required",
                    "negative frameRef must hit the required-arg guard, not the frame lookup")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="evaluate_expression", kind="read")
def test_non_numeric_frameref_is_coerced_to_default_and_rejected():
    """A non-numeric frameRef ("abc") -> extractLongArgument can't parse it ->
    returns the -1L default -> `frameRef <= 0` -> "frameRef is required".

    This proves the tool degrades GRACEFULLY on a wrong-typed frameRef: it returns a
    clean required-arg error instead of throwing a NumberFormatException / leaking a
    stack trace (assert_error_quality also fails the test if the message looks like a
    raw stack trace). A broken parser that propagated the exception would fail here.
    """
    r = call("evaluate_expression", {"frameRef": "abc", "expression": "Add(1, 2)"})
    err = assert_error(r, "non-numeric frameRef")
    assert_error_quality(err, names=["frameRef"], suggests=[],
                         ctx="non-numeric frameRef coerced to default and rejected cleanly")
    assert_contains(err, "required",
                    "non-numeric frameRef must surface as a clean required-arg error")
    assert_no_diff("an invalid call must not touch the project on disk")
