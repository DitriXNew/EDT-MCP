"""
e2e tests for remove_breakpoint (kind: read).

remove_breakpoint deletes a previously-set 1C BSL line breakpoint, EITHER by its
marker id (breakpointId, returned by set_breakpoint) OR by (projectName + module +
lineNumber) coordinates. It is a JSON tool (getResponseType() == JSON), so the
payload lands in r.structured:
  - success: {"success": true,  "removed": <bool>}
  - error  : {"success": false, "error": "<message>"}   (surfaced as r.is_error
             via McpProtocolHandler.isJsonErrorPayload; r.error_text() reads
             structured.error)

ENVIRONMENT NOTE — this is a DEBUG tool, but it does NOT need a running session.
Eclipse line breakpoints live in the EDT WORKSPACE (the org.eclipse.debug
breakpoint manager / project markers), NOT in the git-tracked TestConfiguration
source tree. So remove_breakpoint genuinely WORKS here without any debug_launch /
infobase: we set a real breakpoint on CommonModules/Calc/Module.bsl via the sibling
set_breakpoint tool, then remove it and assert the REAL success contract. Because
breakpoints are workspace markers, NONE of this touches the project source —
assert_no_diff() holds on EVERY test (a debug tool that mutated project files would
be a bug, and that guardrail catches it).

execute() control flow (RemoveBreakpointTool.java), in order:
  1. breakpointId = extractLong("breakpointId", -1). If breakpointId > 0 ->
     removeBreakpointById(id): scans the breakpoint manager, removes the marker
     whose id matches; returns removed=true if found, removed=false otherwise
     (a NON-existent id is a benign success with removed=false, NOT an error).
  2. else (id <= 0): require module (modulePath, or legacy "module" alias) non-empty
     AND lineNumber >= 1, else -> error "Provide either breakpointId or
     modulePath+lineNumber".
  3. resolve the module file; if null/!exists -> error "Module file not found: <module>".
  4. else removeBreakpointAt(file, line): removed=true if a matching line breakpoint
     existed, removed=false otherwise (benign success).

Fixture inventory used (TestConfiguration, English Names):
  CommonModules/Calc/Module.bsl — Function Add lines 1-3, Procedure Test lines 5-7;
  line 2 ("Возврат A + B;") is a valid breakpointable body line.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

CALC_MODULE = "CommonModules/Calc/Module.bsl"


def _set_bp(line):
    """Set a real breakpoint on Calc line `line`; return (breakpointId, structured)."""
    r = call("set_breakpoint", {"projectName": PROJECT, "modulePath": CALC_MODULE,
                                "lineNumber": line})
    assert_ok(r, "precondition: set_breakpoint on Calc:%d" % line)
    sc = r.structured or {}
    bp_id = sc.get("breakpointId")
    return bp_id, sc


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH — breakpoints work WITHOUT a debug session (workspace markers)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="remove_breakpoint", kind="read")
def test_remove_by_coordinates_removes_the_breakpoint():
    """Set a breakpoint on Calc:2, then remove it by (project+module+line).

    The success contract is structured.removed == True (a real breakpoint WAS
    present and got deleted). A broken tool that no-ops, errors, or fails to find
    the just-set breakpoint would return removed=False / is_error -> this fails.
    Removing again must report removed=False (nothing left), proving the first
    call actually deleted it rather than reporting a constant True.
    """
    _set_bp(2)

    r = call("remove_breakpoint", {"projectName": PROJECT, "modulePath": CALC_MODULE,
                                   "lineNumber": 2})
    assert_ok(r, "remove existing breakpoint by coordinates")
    sc = r.structured or {}
    if sc.get("removed") is not True:
        raise AssertionError("expected removed=True for an existing breakpoint, got: %r" % sc)

    # Idempotency / mutation-proof: the breakpoint is now gone -> a second remove
    # at the SAME coordinates must report removed=False (not a constant True).
    r2 = call("remove_breakpoint", {"projectName": PROJECT, "modulePath": CALC_MODULE,
                                    "lineNumber": 2})
    assert_ok(r2, "second remove at the same coordinates")
    sc2 = r2.structured or {}
    if sc2.get("removed") is not False:
        raise AssertionError("expected removed=False after the breakpoint was already "
                             "removed, got: %r" % sc2)

    assert_no_diff("breakpoints are workspace markers; project source must be untouched")


@e2e_test(tool="remove_breakpoint", kind="read")
def test_remove_by_breakpoint_id_round_trip():
    """set_breakpoint returns a breakpointId; remove_breakpoint must consume it.

    This exercises the breakpointId>0 branch (removeBreakpointById) end-to-end:
    the id minted by the sibling tool must round-trip to a successful removal
    (removed=True). A resolver that ignored breakpointId or matched the wrong
    marker would return removed=False here.
    """
    bp_id, _ = _set_bp(2)
    if not isinstance(bp_id, (int, float)) or bp_id <= 0:
        raise AssertionError("precondition: set_breakpoint must return a positive "
                             "breakpointId, got: %r" % bp_id)

    r = call("remove_breakpoint", {"breakpointId": bp_id})
    assert_ok(r, "remove by breakpointId")
    sc = r.structured or {}
    if sc.get("removed") is not True:
        raise AssertionError("expected removed=True removing by the minted breakpointId "
                             "%r, got: %r" % (bp_id, sc))

    assert_no_diff("remove by id must not touch the project source")


@e2e_test(tool="remove_breakpoint", kind="read")
def test_remove_nonexistent_coordinate_is_benign_false():
    """Removing a coordinate where NO breakpoint is set is a benign success.

    The file exists and the line is valid, but no breakpoint was placed there, so
    removeBreakpointAt finds nothing -> removed=False, success (NOT an error). This
    pins the "nothing to remove" contract: it must NOT error, and must NOT report a
    spurious removed=True. (Use line 3 — never set in this test — for isolation.)
    """
    r = call("remove_breakpoint", {"projectName": PROJECT, "modulePath": CALC_MODULE,
                                   "lineNumber": 3})
    assert_ok(r, "remove where no breakpoint exists")
    sc = r.structured or {}
    if sc.get("removed") is not False:
        raise AssertionError("expected benign removed=False when no breakpoint exists "
                             "at the coordinate, got: %r" % sc)
    assert_no_diff("a no-op remove must not touch the project source")


@e2e_test(tool="remove_breakpoint", kind="read")
def test_remove_nonexistent_id_is_benign_false():
    """breakpointId>0 but no marker has that id -> removeBreakpointById returns false.

    A large, never-minted id takes the breakpointId>0 branch and finds no matching
    marker, so the contract is a benign success removed=False — NOT an error and NOT
    a spurious True. Distinguishes "id branch, nothing matched" from the coordinate
    branch and from the error paths.
    """
    r = call("remove_breakpoint", {"breakpointId": 999999999})
    assert_ok(r, "remove by a non-existent breakpointId")
    sc = r.structured or {}
    if sc.get("removed") is not False:
        raise AssertionError("expected benign removed=False for a non-existent "
                             "breakpointId, got: %r" % sc)
    assert_no_diff("an unmatched id remove must not touch the project source")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — missing params, invalid coords, branch boundaries
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="remove_breakpoint", kind="read")
def test_no_arguments_errors_clearly():
    """No breakpointId, no module, no lineNumber -> the "Provide either ..." guard.

    With every selector absent, neither the id branch (id<=0) nor the coordinate
    branch (module empty / line<1) is satisfiable, so execute() returns the
    actionable usage error naming BOTH ways to call the tool.
    """
    r = call("remove_breakpoint", {})
    e = assert_error(r, "no selectors provided")
    # The message lists both selectors (breakpointId AND modulePath+lineNumber) — it
    # both names the missing inputs and tells you exactly how to retry. Good error.
    assert_error_quality(e, names=["breakpointId"],
                         suggests=["modulePath", "lineNumber"],
                         ctx="empty call must point at both ways to identify a breakpoint")
    assert_no_diff("a rejected call must not touch the project source")


@e2e_test(tool="remove_breakpoint", kind="read")
def test_module_without_line_errors_clearly():
    """Coordinate branch with modulePath but NO lineNumber (defaults to -1 < 1).

    A half-specified coordinate must not silently remove "line 0" or the whole
    module — it hits the same "Provide either ..." guard. Confirms lineNumber is a
    hard requirement of the coordinate path.
    """
    r = call("remove_breakpoint", {"projectName": PROJECT, "modulePath": CALC_MODULE})
    e = assert_error(r, "modulePath without lineNumber")
    assert_error_quality(e, names=["breakpointId"],
                         suggests=["modulePath", "lineNumber"],
                         ctx="modulePath alone is incomplete; the guard must demand a line")
    assert_no_diff("a rejected call must not touch the project source")


@e2e_test(tool="remove_breakpoint", kind="read")
def test_line_without_module_errors_clearly():
    """Coordinate branch with lineNumber but NO module (module empty).

    A line without a module cannot resolve a file; the tool must reject it via the
    "Provide either ..." guard rather than NPE or guess a module.
    """
    r = call("remove_breakpoint", {"projectName": PROJECT, "lineNumber": 2})
    e = assert_error(r, "lineNumber without modulePath")
    assert_error_quality(e, names=["breakpointId"],
                         suggests=["modulePath", "lineNumber"],
                         ctx="lineNumber alone is incomplete; the guard must demand a module")
    assert_no_diff("a rejected call must not touch the project source")


@e2e_test(tool="remove_breakpoint", kind="read")
def test_nonexistent_module_errors_and_names_value():
    """Coordinate branch, lineNumber valid, but the modulePath does not resolve.

    resolveModuleFile returns null/!exists -> "Module file not found: <module>".
    The bad module path MUST be echoed so the caller can see WHAT was wrong; a
    broken resolver that returned a generic failure (or silently removed_false a
    real breakpoint) would fail this.
    """
    bad = "CommonModules/NoSuchModule_ZZZ/Module.bsl"
    r = call("remove_breakpoint", {"projectName": PROJECT, "modulePath": bad,
                                   "lineNumber": 2})
    e = assert_error(r, "non-existent module")
    # AUDIT: "Module file not found: <module>" names the bad value but offers NO next
    # step (no pointer to list_modules / read_module_source to discover a valid
    # modulePath). suggests=[] is deliberate; fix-card: make this error actionable.
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="unresolvable module must echo the bad modulePath")
    assert_no_diff("a rejected call must not touch the project source")


@e2e_test(tool="remove_breakpoint", kind="read")
def test_module_relative_path_without_project_errors():
    """Coordinate branch, module-relative path + lineNumber, but NO projectName.

    resolveModuleFile needs the project to resolve a src/-relative path; with
    projectName absent it returns null -> "Module file not found: <module>". This
    proves the module-relative path is not silently resolved against some default
    project (which would be a cross-project leak).
    """
    r = call("remove_breakpoint", {"modulePath": CALC_MODULE, "lineNumber": 2})
    e = assert_error(r, "module-relative path without projectName")
    # AUDIT: the message echoes the module but, when the real cause is a MISSING
    # projectName, it still says only "Module file not found" — it does not name
    # projectName as the missing input nor suggest supplying it. suggests=[]
    # deliberate; fix-card: distinguish "no project" from "no such file".
    assert_error_quality(e, names=[CALC_MODULE], suggests=[],
                         ctx="module-relative path needs projectName to resolve")
    assert_no_diff("a rejected call must not touch the project source")


@e2e_test(tool="remove_breakpoint", kind="read")
def test_breakpoint_id_zero_falls_through_to_coordinate_guard():
    """Boundary: breakpointId == 0 is NOT > 0, so it does NOT take the id branch.

    With breakpointId=0 and no coordinate, execute() must fall through to the
    coordinate branch and fail the "Provide either ..." guard (rather than treat 0
    as a real id and scan/return removed=false). Pins the `breakpointId > 0`
    threshold: a regression to `>= 0` would wrongly take the id branch and SUCCEED
    here with removed=False, which this rejects by requiring is_error.
    """
    r = call("remove_breakpoint", {"breakpointId": 0})
    e = assert_error(r, "breakpointId=0 must not be treated as a real id")
    assert_error_quality(e, names=["breakpointId"],
                         suggests=["modulePath", "lineNumber"],
                         ctx="id 0 falls through to the coordinate guard")
    assert_no_diff("a rejected call must not touch the project source")
