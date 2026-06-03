"""
e2e tests for list_breakpoints (kind: read).

WHAT THIS TOOL ACTUALLY DOES (read ListBreakpointsTool.java):
  It enumerates the Eclipse workspace-level breakpoints via
  DebugPlugin.getDefault().getBreakpointManager().getBreakpoints() and returns a
  JSON result: {"success": true, "breakpoints": [ {...dto...} ], "count": N}.
  Each DTO carries: breakpointId, project, file, lineNumber (for line
  breakpoints), enabled, modelId. The OPTIONAL "projectName" argument is a pure
  client-side filter (kept only when it .equals(project.getName())).

  Response type is JSON (getResponseType() == JSON), so the payload is in
  r.structured (NOT r.text — text is only the bounded success digest).

ENVIRONMENT (no debug session / no running infobase — that is the realistic case
here, and it is FINE for this tool):
  Unlike step/resume/evaluate_expression/wait_for_break, list_breakpoints does
  NOT need an active debug session or a running infobase. Breakpoints are stored
  in the Eclipse BREAKPOINT MANAGER (a workspace-level singleton), not inside the
  TestConfiguration project files and not inside a debug session. So the realistic
  HAPPY contract here is a genuine SUCCESS that reflects the breakpoint-manager
  state — NOT a "no active debug session" sentinel. (The tool's ONLY error branch
  is `DebugPlugin == null` -> "DebugPlugin not available", which is unreachable in
  a live EDT workbench; there is no required parameter, no enum, no XOR.)

  Because of that, the happy path is exercised for real: we SET a breakpoint with
  the sibling set_breakpoint tool (also session-less — it creates an Eclipse
  marker) on CommonModules/Calc/Module.bsl line 2, then list_breakpoints must read
  it back. We always REMOVE it again (remove_breakpoint) so the breakpoint manager
  is left as we found it. Throughout, assert_no_diff() holds: breakpoints live in
  the workspace, so a debug/breakpoint tool must NEVER modify the git-tracked
  project source.

NEGATIVE / EDGE MATRIX for an argument-free read tool:
  There is no missing-required-param case (no required params), no enum, no XOR.
  The meaningful edges are the FILTER semantics, which MUST be handled gracefully
  (success), not errored or mis-applied:
    - projectName filter that matches NO project -> success, count 0, our
      just-set breakpoint absent (a broken filter would error, NPE, or leak ALL
      breakpoints).
    - projectName filter that matches -> our breakpoint present AND every
      returned DTO belongs to that project (the filter really filters).
    - empty-string projectName -> treated as "no filter" (Java guards
      `!projectFilter.isEmpty()`), i.e. same as omitting it.
  See the per-test AUDIT note on why there is no is_error negative to assert.

Fixture inventory used (TestConfiguration, English Names):
  CommonModule.Calc -> src/CommonModules/Calc/Module.bsl (Function Add lines 1-3,
  Procedure Test lines 5-7); line 2 is inside Add and is a valid breakpoint line.
"""

from harness import (
    call,
    assert_ok,
    assert_no_diff,
    e2e_test,
    PROJECT,
)

# Module + line we set a probe breakpoint on. Line 2 is inside Function Add
# (lines 1-3) of CommonModule.Calc — a real, resolvable BSL line.
PROBE_MODULE = "CommonModules/Calc/Module.bsl"
PROBE_LINE = 2


def _set_probe():
    """Set the probe breakpoint via the sibling tool; return its breakpointId.

    Asserts the set itself succeeded so a list assertion never silently runs
    against a breakpoint that was never created (which would make a broken
    list_breakpoints look correct by listing nothing)."""
    s = call("set_breakpoint", {
        "projectName": PROJECT,
        "modulePath": PROBE_MODULE,
        "lineNumber": PROBE_LINE,
    })
    assert_ok(s, "precondition: set_breakpoint on %s:%d must succeed" % (PROBE_MODULE, PROBE_LINE))
    sc = s.structured or {}
    bid = sc.get("breakpointId")
    if bid is None:
        raise AssertionError("set_breakpoint did not return a breakpointId: %s" % sc)
    return bid


def _remove_probe(breakpoint_id):
    """Remove the probe breakpoint so the workspace breakpoint manager is restored.

    Best-effort by id; not asserted (cleanup must not mask the test's own
    assertion failure). The orchestrator's git reset does NOT clean the breakpoint
    manager, so we remove explicitly."""
    try:
        call("remove_breakpoint", {"breakpointId": breakpoint_id})
    except Exception:
        pass


def _breakpoints(result):
    """Pull the breakpoints list out of the JSON structured payload (or fail)."""
    sc = result.structured
    if not isinstance(sc, dict):
        raise AssertionError("list_breakpoints must return JSON structuredContent, got: %r" % (sc,))
    bps = sc.get("breakpoints")
    if not isinstance(bps, list):
        raise AssertionError("structuredContent.breakpoints must be a list, got: %r" % (bps,))
    return sc, bps


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH — real round-trip: set a breakpoint, list it back, then remove it.
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="list_breakpoints", kind="read")
def test_lists_a_breakpoint_we_just_set():
    """The CORE contract: a breakpoint registered in the workspace breakpoint
    manager is enumerated by list_breakpoints with its real coordinates.

    Mutation thinking: if list_breakpoints were broken (no-op, empty list, wrong
    field names, dropped lineNumber), it would NOT contain an entry on the Calc
    module at our line, and this test FAILS. We assert the SPECIFIC entry (project
    + file naming Calc + the exact lineNumber), not merely "count > 0".
    """
    bid = _set_probe()
    try:
        r = call("list_breakpoints", {})
        assert_ok(r, "list_breakpoints in a session-less env still succeeds (reads the breakpoint manager)")
        sc, bps = _breakpoints(r)

        # count must be an int that matches the array length (serialization sanity).
        count = sc.get("count")
        if count != len(bps):
            raise AssertionError("count (%r) must equal len(breakpoints) (%d)" % (count, len(bps)))

        # The breakpoint we just set must be in the list, identified by its real id.
        mine = [b for b in bps if isinstance(b, dict) and b.get("breakpointId") == bid]
        if not mine:
            raise AssertionError(
                "the breakpoint we set (id=%r) is not listed; got ids %r"
                % (bid, [b.get("breakpointId") for b in bps if isinstance(b, dict)]))
        dto = mine[0]

        # Real coordinates round-trip: project, file (names the Calc module), line.
        if dto.get("project") != PROJECT:
            raise AssertionError("listed breakpoint.project=%r, expected %r" % (dto.get("project"), PROJECT))
        if "Calc" not in str(dto.get("file") or ""):
            raise AssertionError("listed breakpoint.file=%r must reference the Calc module" % dto.get("file"))
        if dto.get("lineNumber") != PROBE_LINE:
            raise AssertionError("listed breakpoint.lineNumber=%r, expected %d" % (dto.get("lineNumber"), PROBE_LINE))
    finally:
        _remove_probe(bid)

    # The breakpoint lives in the workspace breakpoint manager, NOT in project
    # source — neither set, list, nor remove may touch the git-tracked tree.
    assert_no_diff("list_breakpoints (and the set/remove around it) must not modify project source")


@e2e_test(tool="list_breakpoints", kind="read")
def test_returns_wellformed_json_envelope():
    """With no probe set, list_breakpoints still returns the well-formed success
    envelope: success:true, a breakpoints LIST, and an integer count equal to the
    list length.

    Mutation thinking: a tool that errored, returned text instead of JSON, omitted
    `count`, or returned a count inconsistent with the array would FAIL here. We do
    NOT assert count==0 (a shared live EDT may carry unrelated breakpoints) — we
    assert the structural invariant that always holds, which is what catches a
    broken serializer.
    """
    r = call("list_breakpoints", {})
    assert_ok(r, "list_breakpoints with no args must succeed (argument-free read)")
    sc, bps = _breakpoints(r)
    if sc.get("success") is not True:
        raise AssertionError("expected success:true envelope, got: %r" % sc)
    count = sc.get("count")
    if not isinstance(count, int):
        raise AssertionError("count must be an integer, got: %r" % (count,))
    if count != len(bps):
        raise AssertionError("count (%d) must equal len(breakpoints) (%d)" % (count, len(bps)))
    assert_no_diff("a read of the breakpoint manager must not touch project source")


# ──────────────────────────────────────────────────────────────────────────────
# EDGE MATRIX — filter semantics (this tool has no is_error path from input).
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="list_breakpoints", kind="read")
def test_filter_matching_project_returns_only_that_project():
    """projectName filter that DOES match: our just-set breakpoint is present, and
    EVERY returned DTO belongs to the requested project (the filter truly filters).

    Mutation thinking: a broken filter that ignored projectName would still pass
    "ours is present" but would FAIL "every entry is TestConfiguration" if any
    foreign breakpoint existed; conversely a filter that over-filtered would drop
    ours. Asserting both directions pins the filter behavior.
    """
    bid = _set_probe()
    try:
        r = call("list_breakpoints", {"projectName": PROJECT})
        assert_ok(r, "list_breakpoints filtered by the real project must succeed")
        _sc, bps = _breakpoints(r)

        ids = [b.get("breakpointId") for b in bps if isinstance(b, dict)]
        if bid not in ids:
            raise AssertionError("filtered-by-%s list must include our breakpoint id=%r; got %r" % (PROJECT, bid, ids))
        # The filter must NOT leak entries from other projects.
        foreign = [b.get("project") for b in bps
                   if isinstance(b, dict) and b.get("project") != PROJECT]
        if foreign:
            raise AssertionError("projectName filter leaked foreign-project entries: %r" % foreign)
    finally:
        _remove_probe(bid)
    assert_no_diff("a filtered read must not touch project source")


@e2e_test(tool="list_breakpoints", kind="read")
def test_filter_matching_no_project_returns_empty_not_error_not_all():
    """EDGE (the closest thing to a 'negative' for an argument-free read tool):
    a projectName that matches NO project must yield a CLEAN empty success
    (count 0, our breakpoint absent) — NOT an error, NOT an NPE, and NOT the full
    unfiltered list.

    Mutation thinking: three ways the tool could be broken, all caught here —
      (a) it errors on an unknown filter (we assert_ok),
      (b) it ignores the filter and leaks ALL breakpoints (we assert our just-set
          breakpoint is ABSENT and count == 0),
      (c) it crashes resolving a non-existent project (assert_ok again).

    AUDIT (negative-matrix shape): list_breakpoints has NO input that can produce
    `is_error` — there is no required parameter, no enum, no mutually-exclusive
    pair, and the projectName filter is intentionally lenient (an unknown project
    is "matches nothing", not an error). So there is no actionable error message to
    assert here; the audit obligation is satisfied by proving the LENIENT branch is
    correct and safe (empty success), which is the realistic and correct contract.
    A 'not found' error here would be a REGRESSION, not an improvement, since a
    filter legitimately yields the empty set. No is_error assertion is appropriate.
    """
    bid = _set_probe()
    bad_project = "NoSuchProject_ZZZ_e2e"
    try:
        r = call("list_breakpoints", {"projectName": bad_project})
        # Must be a benign success, not an error sentinel.
        assert_ok(r, "an unknown projectName filter must be a clean empty success, not an error")
        sc, bps = _breakpoints(r)
        if sc.get("count") != 0:
            raise AssertionError("unknown-project filter must yield count 0, got %r" % sc.get("count"))
        if bps:
            raise AssertionError("unknown-project filter must yield an empty list, got: %r" % bps)
        # Our breakpoint exists but belongs to TestConfiguration, so it must be filtered out.
        ids = [b.get("breakpointId") for b in bps if isinstance(b, dict)]
        if bid in ids:
            raise AssertionError("filter leaked our breakpoint (id=%r) under a non-matching project" % bid)
    finally:
        _remove_probe(bid)
    assert_no_diff("a filtered read must not touch project source")


@e2e_test(tool="list_breakpoints", kind="read")
def test_empty_filter_is_treated_as_no_filter():
    """Boundary: an EMPTY-string projectName. The Java guards the filter with
    `projectFilter != null && !projectFilter.isEmpty()`, so "" disables filtering
    and behaves exactly like omitting the argument — our just-set breakpoint must
    still be listed (it must NOT be silently dropped by a "" that fails to match
    any project name).

    Mutation thinking: if "" were (wrongly) compared with .equals against project
    names, NO project would match and our breakpoint would vanish — this test would
    FAIL. Asserting presence under "" pins the empty-string-as-no-filter semantics.
    """
    bid = _set_probe()
    try:
        r = call("list_breakpoints", {"projectName": ""})
        assert_ok(r, "empty projectName must behave as no filter (not match-nothing)")
        _sc, bps = _breakpoints(r)
        ids = [b.get("breakpointId") for b in bps if isinstance(b, dict)]
        if bid not in ids:
            raise AssertionError("empty-string filter must NOT drop our breakpoint id=%r; got %r" % (bid, ids))
    finally:
        _remove_probe(bid)
    assert_no_diff("a read with an empty filter must not touch project source")
