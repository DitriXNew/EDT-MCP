"""
e2e tests for code_review (kind: read).

code_review runs the external BSL Language Server engine over a project (or one
module) and renders its diagnostics as a Markdown table. It is the delta over
get_project_errors: code METRICS (magic number, complexity, ...) that EDT's own
checks do not raise. Response is the Markdown string -> Result.text; the error path
goes through ToolResult.error(...).toJson() -> Result.structured.error.

The engine (jar + Java) is an external, env-provided dependency
(EDT_MCP_BSL_LS_JAR / EDT_MCP_BSL_LS_JAVA). Where it is configured (the dev machine),
the happy paths assert REAL findings on TestConfiguration — its CommonModules/Calc and
Configuration/ManagedApplicationModule carry MagicNumber, and CommonModules/Error
carries a ParseError. Where it is NOT configured (e.g. CI), the tool returns its
ACTIONABLE engine-not-found error and the happy paths SKIP — but ONLY for that specific
error; any other error still fails, and the content assertions still catch a no-op tool.

The negative matrix runs regardless of the engine: every rejection there happens BEFORE
the engine is launched (missing/invalid args, unknown project/module).

Read tool => every test asserts assert_no_diff(): analysis writes only to a system temp
dir (cleaned up) and must never mutate the project on disk.
"""

from harness import (
    call, assert_ok, assert_contains, assert_not_contains, assert_error,
    assert_error_quality, assert_no_diff, e2e_test, PROJECT, E2ESkip, _fail,
)

# Substrings that identify the actionable "engine not installed" error
# (see BslLsRunner.jarNotFoundMessage): both must be present to treat it as a skip.
ENGINE_MISSING_MARKERS = ("EDT_MCP_BSL_LS_JAR", "bsl-language-server")

# A module of TestConfiguration known to carry a metric finding (MagicNumber).
CALC_MODULE = "CommonModules/Calc/Module.bsl"


def _run_or_skip(args, ctx):
    """Call code_review; return the Result when the engine actually ran. If the engine
    jar/Java is not configured, the tool returns its actionable not-found error -> SKIP
    (an unmet precondition, not a failure). Any OTHER error is a real failure."""
    r = call("code_review", args)
    if r.is_error:
        err = r.error_text() or ""
        if all(m in err for m in ENGINE_MISSING_MARKERS):
            raise E2ESkip("BSL Language Server engine not configured: " + ctx)
        _fail(ctx + " -> unexpected error: " + err)
    return r


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS (engine-gated: real findings where configured, else skip)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="code_review", kind="read")
def test_reports_metric_findings_for_project():
    """A whole-project review runs the engine and reports its METRIC findings — the
    delta over get_project_errors. MagicNumber is present in TestConfiguration, so a
    working tool renders it; a no-op/broken tool fails the content assertions."""
    r = _run_or_skip({"projectName": PROJECT}, "whole-project review")
    assert_ok(r, "code_review whole-project happy path")
    assert_contains(r.text, "Code review — " + PROJECT, "must render the scope heading naming the project")
    assert_contains(r.text, "finding(s)", "must render the findings summary line")
    # The metric delta the whole tool exists for.
    assert_contains(r.text, "MagicNumber", "the engine's MagicNumber metric must be reported")
    # The auto-remediation steering (fix at Module path + Line, then re-verify).
    assert_contains(r.text, "write_module_source", "output must steer to fixing via write_module_source")
    assert_no_diff("reviewing code must not touch the project on disk")


@e2e_test(tool="code_review", kind="read")
def test_module_scope_echoes_path_and_finds_metric():
    """Scoping to one module echoes that module in the heading AND still surfaces its
    metric finding (Calc has a MagicNumber). Proves modulePath narrows the review rather
    than being ignored."""
    r = _run_or_skip({"projectName": PROJECT, "modulePath": CALC_MODULE}, "single-module review")
    assert_ok(r, "code_review single-module happy path")
    assert_contains(r.text, CALC_MODULE, "the heading must echo the requested module path")
    assert_contains(r.text, "MagicNumber", "the module's MagicNumber finding must be reported")
    assert_no_diff("reviewing one module must not touch the project on disk")


@e2e_test(tool="code_review", kind="read")
def test_rule_filter_narrows_to_matching_rule():
    """rule='Magic' keeps only magic-number diagnostics: the MagicNumber doc link is
    present and an unrelated rule's doc link (UnusedLocalVariable, which Calc also has)
    is filtered out. Proves the rule filter is applied, not dropped."""
    r = _run_or_skip({"projectName": PROJECT, "rule": "Magic"}, "rule-filtered review")
    assert_ok(r, "code_review rule filter happy path")
    assert_contains(r.text, "diagnostics/Magic", "a MagicNumber row must survive rule='Magic'")
    assert_not_contains(r.text, "diagnostics/UnusedLocalVariable",
                        "a non-matching rule must be filtered out by rule='Magic'")
    assert_no_diff("a filtered review must not touch the project on disk")


@e2e_test(tool="code_review", kind="read")
def test_severity_filter_drops_lower_severities():
    """severity='error' (minimum) keeps Error-level diagnostics (ParseError exists in
    TestConfiguration) and drops the Information-level MagicNumber. Proves the
    minimum-severity filter, not just that some rows appear."""
    r = _run_or_skip({"projectName": PROJECT, "severity": "error"}, "severity-filtered review")
    assert_ok(r, "code_review severity filter happy path")
    assert_contains(r.text, "ParseError", "an Error-level diagnostic must remain at severity='error'")
    assert_not_contains(r.text, "diagnostics/MagicNumber",
                        "an Information-level finding must be dropped at severity='error'")
    assert_no_diff("a filtered review must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (deterministic: rejected before the engine is launched)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="code_review", kind="read")
def test_missing_project_name_is_rejected():
    """projectName is required; omitting it errors before any engine/workspace access."""
    r = call("code_review", {})
    err = assert_error(r, "missing projectName")
    assert_contains(err, "projectName", "the required-arg error must name projectName")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="code_review", kind="read")
def test_nonexistent_project_is_rejected():
    """A non-existent projectName must error (names the value, points at list_projects),
    reached before the engine runs."""
    bad = "NoSuchProject_e2e_xyz"
    r = call("code_review", {"projectName": bad})
    err = assert_error(r, "non-existent projectName")
    assert_error_quality(
        err,
        names=[bad],
        suggests=["list_projects"],
        ctx="non-existent project: names the bad value and points at list_projects",
    )
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="code_review", kind="read")
def test_invalid_severity_is_rejected_with_valid_set():
    """An out-of-set severity must be REJECTED with the accepted values listed, before the
    engine runs (so it is deterministic even without the jar)."""
    r = call("code_review", {"projectName": PROJECT, "severity": "catastrophic"})
    err = assert_error(r, "invalid severity enum")
    assert_error_quality(
        err,
        names=["catastrophic"],
        suggests=["severity", "error", "warning"],
        ctx="invalid severity echoes the bad value and lists the valid set",
    )
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="code_review", kind="read")
def test_nonexistent_module_is_rejected():
    """A modulePath that resolves to no module must error (naming the src/ path), reached
    after project resolution but before the engine — so it is deterministic."""
    bad_module = "CommonModules/NoSuchModule_e2e/Module.bsl"
    r = call("code_review", {"projectName": PROJECT, "modulePath": bad_module})
    err = assert_error(r, "non-existent modulePath")
    assert_contains(err, "Module not found", "the error must state the module was not found")
    assert_contains(err, bad_module, "the error must name the bad module path")
    assert_no_diff("a rejected call must not touch the project on disk")
