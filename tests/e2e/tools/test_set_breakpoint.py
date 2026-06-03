"""
e2e tests for set_breakpoint (kind: read).

What the tool does
------------------
set_breakpoint creates a 1C BSL line breakpoint via the Eclipse breakpoint
framework (SetBreakpointTool -> BreakpointUtils.createLineBreakpoint). It accepts
EITHER an EDT module-relative path ("CommonModules/Calc/Module.bsl") OR an
absolute filesystem path, plus a 1-based lineNumber.

ENVIRONMENT / WHY THIS IS STILL A REAL HAPPY PATH
-------------------------------------------------
Unlike the other debug/runtime tools (resume/step/evaluate_expression/...),
set_breakpoint does NOT require an active debug session or a running infobase.
A breakpoint is a workspace-level Eclipse artifact: it is registered on the
IBreakpointManager and its backing IMarker lives on the workspace resource, NOT
in the git-tracked TestConfiguration source tree. So against this EDT (no debug
session, no launched application) set_breakpoint genuinely SUCCEEDS — and the
project working tree must stay clean. That is the realistic happy contract here,
so we exercise the real success path against the fixture's Calc module.

The breakpoint MAY come back "degraded" (marker-only) if the EDT BSL breakpoint
class is not on the runtime classpath — BreakpointUtils falls back through EDT
marker types to a generic Eclipse marker and sets degraded=true + a warning. Both
the native and the degraded outcome are correct *successes*; we assert the parts
of the contract that hold in either case (success + echoed coordinates + a real
resolvedFile + a numeric breakpointId) and additionally assert that IF degraded,
the warning is present and actionable (so a silent degradation can't hide).

Response type / where the payload is
------------------------------------
getResponseType() == JSON, so the real payload is in r.structured
(structuredContent); r.text is only a "Done"/placeholder string. Success keys:
  breakpointId (long marker id), modulePath, module, resolvedFile, lineNumber,
  and on degraded: degraded=true + warning.

Real execute() error paths (SetBreakpointTool.java, all via ToolResult.error):
  - module/modulePath missing            -> "modulePath is required"
  - lineNumber < 1 (incl. omitted=-1)    -> "lineNumber must be >= 1"
  - module-relative path but no project  -> "projectName is required when
                                            modulePath is given as an EDT module path"
  - project not ready/absent (module-rel)-> ProjectStateChecker.checkReadyOrError:
                                            "Project does not exist. Please wait and
                                            retry." (NOTE: does NOT echo the name -> AUDIT)
  - file does not resolve/exist          -> "Module file not found: <module>
                                            [ in project <projectName>]"

Fixture inventory used (TestConfiguration, English Names):
  CommonModule.Calc -> src/CommonModules/Calc/Module.bsl, with
    Function Add  on lines 1-3 (line 2 = "Возврат A + B;", a real executable line)
    Procedure Test on lines 5-7
  This file is committed; line 2 is a deterministic, valid breakpoint target.

Cleanup note: a breakpoint set here lives in the EDT workspace (NOT the project
tree), so reset_fixture() does not remove it. The happy-path test therefore
removes its own breakpoint via remove_breakpoint at the end so the workspace does
not accumulate breakpoints across runs and the list_breakpoints read-back stays
deterministic. assert_no_diff() still proves the project source was untouched.
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

CALC_MODULE = "CommonModules/Calc/Module.bsl"


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH (set_breakpoint works WITHOUT a debug session; breakpoints are a
# workspace artifact, so the project tree must stay clean)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="set_breakpoint", kind="read")
def test_sets_breakpoint_on_calc_module_line_and_does_not_touch_project():
    """Set a breakpoint at the fixture's Calc module, line 2 (a real executable
    line inside Function Add). The tool must SUCCEED even with no debug session,
    echo the exact coordinates, resolve to the real Calc module file, and return a
    numeric breakpointId — while leaving the git-tracked project source untouched
    (a breakpoint lives in the workspace, not the project tree).

    Mutation sensitivity: a broken tool that no-ops, resolves the wrong file,
    drops the line number, or returns no id would FAIL one of these asserts. A
    tool that secretly wrote into the project tree would FAIL assert_no_diff().
    """
    r = call("set_breakpoint", {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "lineNumber": 2,
    })
    assert_ok(r, "set_breakpoint on Calc module line 2")

    # JSON tool -> the real payload is in structuredContent, not r.text.
    assert r.structured is not None, \
        "set_breakpoint is a JSON tool; structuredContent must carry the result"
    sc = r.structured

    # Coordinates echoed back verbatim (proves the tool used OUR inputs, not defaults).
    assert sc.get("modulePath") == CALC_MODULE, \
        "structured modulePath must echo the requested module path; got %r" % sc.get("modulePath")
    assert sc.get("module") == CALC_MODULE, \
        "structured module (legacy alias) must echo the requested path; got %r" % sc.get("module")
    assert sc.get("lineNumber") == 2, \
        "structured lineNumber must be the requested line 2; got %r" % sc.get("lineNumber")

    # resolvedFile must point at the REAL Calc module under the project (the tool
    # actually resolved the EDT module path to a workspace IFile, not a stub).
    resolved = str(sc.get("resolvedFile") or "")
    assert_contains(resolved, "Calc/Module.bsl",
                    "resolvedFile must be the actual Calc module file path")
    assert_contains(resolved, PROJECT,
                    "resolvedFile must be under the requested project")

    # A breakpoint was really created -> a numeric marker id is returned. -1 means
    # the marker was not created (a broken/degraded-to-nothing path).
    bp_id = sc.get("breakpointId")
    assert isinstance(bp_id, int) and bp_id > 0, \
        "breakpointId must be a positive marker id (a real breakpoint was created); got %r" % bp_id

    # If the EDT BSL breakpoint class was unavailable, the tool degrades to a
    # marker-only breakpoint — that is still a success, but it MUST announce itself
    # with an actionable warning (so a silent degradation cannot pass unnoticed).
    if sc.get("degraded"):
        warning = str(sc.get("warning") or "")
        assert_contains(warning, "marker-only",
                        "a degraded breakpoint must say it is marker-only")
        assert_contains(warning, "Breakpoints view",
                        "the degraded warning must tell the user how to verify (Breakpoints view)")

    # Read it back through the sibling list tool: the breakpoint we just set must be
    # enumerable by breakpointId, on the requested project + line. This proves the
    # breakpoint is really registered on the manager, not just reported.
    listed = call("list_breakpoints", {"projectName": PROJECT})
    assert_ok(listed, "list_breakpoints read-back")
    assert listed.structured is not None, "list_breakpoints is a JSON tool"
    bps = listed.structured.get("breakpoints") or []
    mine = [b for b in bps if b.get("breakpointId") == bp_id]
    assert mine, \
        "the breakpoint just set (id=%r) must appear in list_breakpoints; got ids %r" \
        % (bp_id, [b.get("breakpointId") for b in bps])
    assert mine[0].get("lineNumber") == 2, \
        "the listed breakpoint must report line 2; got %r" % mine[0].get("lineNumber")

    # The whole sequence must NOT have modified the git-tracked project source —
    # breakpoints are a workspace artifact, never a project-tree edit.
    assert_no_diff("setting a breakpoint must not touch the project source tree")

    # Clean up our own workspace breakpoint (reset_fixture does not, since it is
    # not in the project tree). Best-effort: a failure here does not change the
    # already-proven contract above, but normally this removes the breakpoint so
    # the workspace does not accumulate state across runs.
    removed = call("remove_breakpoint", {"breakpointId": bp_id})
    assert_ok(removed, "remove_breakpoint cleanup of the breakpoint we set")
    assert removed.structured and removed.structured.get("removed") is True, \
        "cleanup remove_breakpoint must report removed=true for our own breakpoint id"


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (mandatory: missing required, bad values, bad combinations)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="set_breakpoint", kind="read")
def test_missing_module_path_errors_clearly():
    """modulePath/module omitted -> the first guard fires -> "modulePath is
    required" (this guard runs before the lineNumber guard, so omitting both still
    surfaces the modulePath message)."""
    r = call("set_breakpoint", {"projectName": PROJECT, "lineNumber": 2})
    e = assert_error(r, "missing modulePath")
    # AUDIT: names the missing param but offers no next step (no list_modules hint
    # to discover a valid module path). suggests=[] is intentional -> fix-card.
    assert_error_quality(e, names=["modulePath"], suggests=[],
                         ctx="missing modulePath names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="set_breakpoint", kind="read")
def test_missing_line_number_errors_clearly():
    """lineNumber omitted -> JsonUtils.extractIntArgument defaults to -1 -> the
    "lineNumber must be >= 1" guard fires. modulePath is present so the modulePath
    guard does NOT win first; this isolates the lineNumber guard."""
    r = call("set_breakpoint", {"projectName": PROJECT, "modulePath": CALC_MODULE})
    e = assert_error(r, "missing lineNumber")
    # The message names the offending parameter and the constraint (>= 1).
    # AUDIT: actionable enough (states the >=1 rule) but does not name a sibling
    # tool; the constraint itself is the next step, so suggests=["lineNumber"].
    assert_error_quality(e, names=["lineNumber"], suggests=["lineNumber"],
                         ctx="missing lineNumber names the param and the >=1 constraint")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="set_breakpoint", kind="read")
def test_zero_line_number_rejected_at_boundary():
    """Boundary: lineNumber == 0 is below the 1-based minimum -> "lineNumber must
    be >= 1". Guards the exact `lineNumber < 1` boundary (0 must be rejected, not
    coerced to line 1)."""
    r = call("set_breakpoint", {
        "projectName": PROJECT, "modulePath": CALC_MODULE, "lineNumber": 0,
    })
    e = assert_error(r, "zero lineNumber")
    assert_error_quality(e, names=["lineNumber"], suggests=["lineNumber"],
                         ctx="zero lineNumber rejected at the >=1 boundary")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="set_breakpoint", kind="read")
def test_negative_line_number_rejected():
    """A negative lineNumber (-5) is below the minimum -> "lineNumber must be >= 1".
    Confirms a negative value is rejected with the constraint named, not silently
    accepted."""
    r = call("set_breakpoint", {
        "projectName": PROJECT, "modulePath": CALC_MODULE, "lineNumber": -5,
    })
    e = assert_error(r, "negative lineNumber")
    assert_error_quality(e, names=["lineNumber"], suggests=["lineNumber"],
                         ctx="negative lineNumber rejected at the >=1 boundary")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="set_breakpoint", kind="read")
def test_module_relative_path_without_project_errors_actionably():
    """A module-RELATIVE path needs a project to resolve against. Omitting
    projectName (with a non-absolute modulePath) -> the dedicated guard:
    "projectName is required when modulePath is given as an EDT module path".
    This is the conditional-required combination for this tool (project is only
    required when the path is module-relative, not when it is absolute).

    This is a GOOD, actionable error: it names the missing param AND explains the
    exact condition under which it is required -> assert its quality in full."""
    r = call("set_breakpoint", {"modulePath": CALC_MODULE, "lineNumber": 2})
    e = assert_error(r, "module-relative path without projectName")
    assert_error_quality(e, names=["projectName"],
                         suggests=["EDT module path"],
                         ctx="missing projectName for a module-relative path is explained")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="set_breakpoint", kind="read")
def test_nonexistent_project_errors():
    """A module-relative path with a project that does not exist -> the project
    readiness check (ProjectStateChecker.checkReadyOrError) fires before file
    resolution -> "Project does not exist. Please wait and retry."

    AUDIT: this message is NOT actionable for a typo'd project name — it does NOT
    echo the bad project name and does NOT point at list_projects (the sibling tool
    that enumerates valid open projects). It also reads as a transient "wait and
    retry" hint, which is misleading for a genuinely non-existent project. We
    assert the SPECIFIC sentinel text (mutation-sensitive: a no-op or a wrong
    branch would not produce it) but cannot assert the bad value is named, because
    it is not. Fix-card: name the project + point at list_projects, and drop the
    "wait and retry" wording for the not-found case.
    """
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("set_breakpoint", {
        "projectName": bad, "modulePath": CALC_MODULE, "lineNumber": 2,
    })
    e = assert_error(r, "non-existent project")
    # Assert the exact, specific sentinel (delimiter-free substring) so the test is
    # mutation-sensitive even though the bad value is not echoed.
    assert_contains(e, "Project does not exist",
                    "non-existent project must hit the readiness 'does not exist' sentinel")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="set_breakpoint", kind="read")
def test_nonexistent_module_in_real_project_errors_and_names_value():
    """A valid, existing project but a module path that does not resolve to any
    file -> after the readiness check passes, file resolution returns null ->
    "Module file not found: <module> in project <projectName>". The bad module
    path AND the project are both named -> a clear, value-naming error."""
    bad_module = "CommonModules/NoSuchModule_e2e/Module.bsl"
    r = call("set_breakpoint", {
        "projectName": PROJECT, "modulePath": bad_module, "lineNumber": 2,
    })
    e = assert_error(r, "non-existent module in a real project")
    # AUDIT: names the bad module + the project, but offers no next step (no
    # list_modules pointer to discover valid module paths). suggests=[] is
    # intentional -> fix-card to make the not-found error actionable.
    assert_error_quality(e, names=[bad_module, PROJECT], suggests=[],
                         ctx="module-not-found names the bad module and the project")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="set_breakpoint", kind="read")
def test_nonexistent_absolute_path_errors_and_names_value():
    """An ABSOLUTE filesystem path (starts with a drive prefix) bypasses the
    project-required + readiness checks (looksLikeAbsolutePath -> true) and goes
    straight to file resolution. A non-existent absolute .bsl path -> "Module file
    not found: <module>" (no " in project ..." suffix, since absolute resolution
    is by location, not by project). Exercises the absolute-path branch of the
    modulePath-style detection — distinct from the module-relative branch above."""
    bad_abs = "C:/no/such/dir/NoSuchModule_e2e.bsl"
    r = call("set_breakpoint", {"modulePath": bad_abs, "lineNumber": 2})
    e = assert_error(r, "non-existent absolute path")
    # AUDIT: names the bad path but offers no next step. suggests=[] -> fix-card.
    assert_error_quality(e, names=[bad_abs], suggests=[],
                         ctx="absolute-path not-found names the bad path")
    assert_no_diff("an invalid call must not touch the project on disk")
