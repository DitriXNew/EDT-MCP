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
    call, assert_ok, assert_contains, assert_error,
    assert_error_quality, assert_no_diff, e2e_test, PROJECT, E2ESkip, _fail,
    split_markdown_row, PROJECT_DIR,
)

# The two answers that mean the engine is ABSENT, each matched by a phrase unique to it
# (BslLsRunner.jarNotFoundMessage / javaNotFoundMessage). Deliberately not a loose pair of
# substrings such as the env-var name plus the releases URL: incompatibleEngineMessage ("engine
# <jar> needs Java 21, but EDT runs on Java 17 ... point EDT_MCP_BSL_LS_JAR at it: <releases url>")
# carries BOTH of those, so a broad match reads a MISCONFIGURED engine as a missing one and skips
# every happy path while the run still reports green. An engine that is installed but unusable is
# a broken setup, and a broken setup has to fail loudly.
ENGINE_MISSING_MESSAGES = (
    "BSL Language Server engine not found",
    "No Java runtime found to launch the BSL Language Server",
)

# A module of TestConfiguration known to carry a metric finding (MagicNumber).
CALC_MODULE = "CommonModules/Calc/Module.bsl"


def _run_or_skip(args, ctx):
    """Call code_review; return the Result when the engine actually ran. If the engine jar (or a
    Java to launch it) is not installed AT ALL, the tool returns its actionable not-found error ->
    SKIP (an unmet precondition, not a failure). Any OTHER error is a real failure - including the
    engine being present but unrunnable on this Java, which is a broken setup, not an absent one."""
    r = call("code_review", args)
    if r.is_error:
        err = r.error_text() or ""
        if any(m in err for m in ENGINE_MISSING_MESSAGES):
            raise E2ESkip("BSL Language Server engine not installed: " + ctx)
        _fail(ctx + " -> unexpected error: " + err)
    return r


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS (engine-gated: real findings where configured, else skip)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="code_review", kind="read")
def test_reports_metric_findings_for_project():
    """A whole-project review runs the engine and reports its METRIC findings — the delta
    over get_project_errors. A no-op/broken tool fails here: an empty report is a FAILURE,
    never a skip. Only the metric example itself (MagicNumber) may legitimately be absent on
    an engine whose catalog a local config retuned, and that case skips instead of lying."""
    r = _run_or_skip({"projectName": PROJECT}, "whole-project review")
    assert_ok(r, "code_review whole-project happy path")
    assert_contains(r.text, "Code review — " + PROJECT, "must render the scope heading naming the project")
    assert_contains(r.text, "finding(s)", "must render the findings summary line")
    # The auto-remediation steering (fix at Module path + Line, then re-verify).
    assert_contains(r.text, "write_module_source", "output must steer to fixing via write_module_source")
    # The metric delta the whole tool exists for.
    rules = _require_findings(r.text, "whole-project review")
    _rule_present_or_skip(rules, "MagicNumber", "whole-project review")
    assert_no_diff("reviewing code must not touch the project on disk")


@e2e_test(tool="code_review", kind="read")
def test_module_scope_echoes_path_and_finds_metric():
    """Scoping to one module echoes that module in the heading AND still surfaces its
    metric finding (Calc has a MagicNumber). Proves modulePath narrows the review rather
    than being ignored."""
    r = _run_or_skip({"projectName": PROJECT, "modulePath": CALC_MODULE}, "single-module review")
    assert_ok(r, "code_review single-module happy path")
    assert_contains(r.text, CALC_MODULE, "the heading must echo the requested module path")
    rules = _require_findings(r.text, "single-module review")
    _rule_present_or_skip(rules, "MagicNumber", "single-module review")
    assert_no_diff("reviewing one module must not touch the project on disk")


@e2e_test(tool="code_review", kind="read")
def test_rule_filter_narrows_to_matching_rule():
    """`rule` keeps only the matching diagnostics. Both the rule that must survive and the
    rule that must disappear are taken from an unfiltered scan of the same fixture rather than
    assumed of the engine's catalog, so a local .bsl-language-server.json that retunes the rule
    set cannot paint a correctly working filter red."""
    base = _run_or_skip({"projectName": PROJECT}, "unfiltered project review")
    assert_ok(base, "unfiltered project review")
    distinct = sorted(set(_require_findings(base.text, "unfiltered project review")))
    # The pair must not nest: `rule` matches by SUBSTRING, so a rule containing the other would
    # legitimately survive the filter and the assertion below would be wrong, not the tool.
    keep = distinct[0]
    drop = next((x for x in distinct[1:]
                 if keep.lower() not in x.lower() and x.lower() not in keep.lower()), None)
    if drop is None:
        raise E2ESkip("the fixture reports no two rules distinct enough for a substring rule "
                      "filter; reported: " + ", ".join(distinct[:8]))

    r = _run_or_skip({"projectName": PROJECT, "rule": keep}, "rule-filtered review")
    assert_ok(r, "code_review rule filter happy path")
    remaining = _finding_rules(r.text)
    if keep not in remaining:
        raise AssertionError("rule=%r dropped its own rule - the filter is muting, not narrowing. "
                             "Rows left: %r" % (keep, remaining[:5]))
    if drop in remaining:
        raise AssertionError("rule=%r left %r in the table, so the filter is not reaching the "
                             "report at all" % (keep, drop))
    assert_no_diff("a filtered review must not touch the project on disk")


@e2e_test(tool="code_review", kind="read")
def test_severity_filter_drops_lower_severities():
    """severity='error' is a MINIMUM: Error rows stay, everything below it goes. Asserted
    over EVERY surviving row instead of one named rule, and the precondition - that the fixture
    really reports both an Error and something below it - is read from an unfiltered scan rather
    than assumed of the engine's catalog."""
    base = _run_or_skip({"projectName": PROJECT}, "unfiltered project review")
    assert_ok(base, "unfiltered project review")
    rows = _finding_rows(base.text)
    if not rows:
        _fail("unfiltered project review -> no findings at all on a fixture expected to have some")
    severities = {row["severity"].lower() for row in rows}
    if "error" not in severities or severities <= {"error"}:
        raise E2ESkip("this engine configuration reports no Error-plus-lower mix on the fixture "
                      "(%s), so a minimum-severity filter cannot be observed"
                      % ", ".join(sorted(severities)))

    r = _run_or_skip({"projectName": PROJECT, "severity": "error"}, "severity-filtered review")
    assert_ok(r, "code_review severity filter happy path")
    kept = _finding_rows(r.text)
    if not kept:
        raise AssertionError("severity='error' emptied the table although the unfiltered scan "
                             "reported Error-level findings")
    below = [row for row in kept if row["severity"].lower() != "error"]
    if below:
        raise AssertionError("severity='error' is a minimum, but %d row(s) below Error survived: "
                             "%r" % (len(below), below[:3]))
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


def _finding_rows(text):
    """Every finding row as a {severity, rule} dict, in table order.

    Columns are located by their names in the HEADER, so the parser does not depend on the
    column order and a caller can ask for severity and rule together — which is what lets the
    severity test check EVERY surviving row instead of one named rule.
    """
    rows = []
    at = None
    for line in (text or "").splitlines():
        cells = [c.strip() for c in split_markdown_row(line)]
        if len(cells) < 3:
            continue
        lowered = [c.lower() for c in cells]
        if "rule" in lowered and "severity" in lowered:
            at = {"rule": lowered.index("rule"), "severity": lowered.index("severity")}
            continue
        if at is None or max(at.values()) >= len(cells):
            continue
        rule = cells[at["rule"]].strip("`")
        if not rule or set(rule) <= set("-:"):       # the header separator row
            continue
        rows.append({"rule": rule, "severity": cells[at["severity"]].strip("`")})
    return rows


def _require_findings(text, ctx):
    """The rules this run reported — and a hard FAILURE when it reported none.

    "Nothing at all" is never an unmet precondition: a tool that silently returns an empty
    report has to fail here. Only the CHOICE of which rule to assert on may adapt to the
    engine's catalog — see _rule_present_or_skip.
    """
    rules = [row["rule"] for row in _finding_rows(text)]
    if not rules:
        _fail(ctx + " -> the engine reported no findings at all on a fixture that is expected "
                    "to produce some; that is a broken run, not a tuned diagnostic catalog")
    return rules


def _rule_present_or_skip(rules, needle, ctx):
    """One reported rule whose id contains `needle`, else SKIP naming what was reported.

    The suite must not assume the engine's DEFAULT catalog. BslLsRunner deliberately honours a
    project / engine-home / user-home `.bsl-language-server.json`, so a machine whose config
    disables or retunes a rule would otherwise paint a correctly working tool red. Skipping is
    only safe because _require_findings has already failed the "nothing at all" case.
    """
    for r in rules:
        if needle.lower() in r.lower():
            return r
    raise E2ESkip("this engine configuration reports no %r rule on the fixture (%s); it did "
                  "report: %s" % (needle, ctx, ", ".join(sorted(set(rules))[:8])))


def _finding_rules(text):
    """The Rule column of every finding row, in table order.

    Read by the column's position in the HEADER rather than by scanning cells for a needle: the
    steering paragraph above the table names MagicNumber as an example of a mechanically fixable
    rule, so a naive `"MagicNumber" in text` reports a leftover row that does not exist. Reading
    the whole column (not just the rows matching one rule) is what lets a caller assert what
    SURVIVED a filter, not only what vanished.

    Parsing goes through the shared escape-aware splitter because MarkdownUtils.escapeForTable
    writes a literal pipe as an escaped one, which a naive split would cut in the wrong place.
    """
    return [row["rule"] for row in _finding_rows(text)]


@e2e_test(tool="code_review", kind="read")
def test_exclude_rule_drops_only_the_named_rule():
    """excludeRule is the parameter the description leans on hardest (drop rules you already
    get from get_project_errors), yet it was only covered headlessly against canned JSON.
    This pins the WIRING: execute() reads it and render() applies it. Both filters are asked
    of the same live scan, so the assertion is a real comparison rather than a self-check -
    the excluded rule must vanish from the rows while the rest of the table survives.

    Engine-gated like every other happy path here: _run_or_skip turns the actionable
    "engine not installed" answer into a SKIP, which is what CI (no jar) must get."""
    base = _run_or_skip({"projectName": PROJECT}, "unfiltered project review")
    assert_ok(base, "unfiltered project review")
    baseline = _finding_rules(base.text)
    if "MagicNumber" not in baseline:
        raise E2ESkip("the fixture currently reports no MagicNumber finding to exclude")
    # A rule that must SURVIVE the exclusion, taken from THIS scan rather than assumed of the
    # fixture. Without it the test cannot tell "dropped one rule" from "dropped everything".
    survivors = sorted({r for r in baseline if r != "MagicNumber"})
    if not survivors:
        raise E2ESkip("the fixture reports MagicNumber only, so nothing here can tell a scalpel "
                      "from a mute button")
    survivor = survivors[0]

    filtered = _run_or_skip({"projectName": PROJECT, "excludeRule": "MagicNumber"},
                            "review with excludeRule=MagicNumber")
    assert_ok(filtered, "review with excludeRule=MagicNumber")
    remaining = _finding_rules(filtered.text)
    leftover = [r for r in remaining if r == "MagicNumber"]
    if leftover:
        raise AssertionError(
            "excludeRule=MagicNumber must drop every MagicNumber row, but %d row(s) remain: %r "
            "- the parameter is not reaching the filter" % (len(leftover), leftover[:3]))
    # The filter must be a scalpel, not a mute button. Asserting on the RESPONSE being non-empty
    # would not catch that: with zero findings the tool still renders a heading, a summary and
    # "no findings match the current filters", so such a check can never fail. Naming a rule the
    # unfiltered scan really reported is what makes the difference observable.
    if survivor not in remaining:
        raise AssertionError(
            "excludeRule=MagicNumber also dropped %r, which it must not touch - the unfiltered "
            "scan reported it. %d row(s) left of %d: the filter is muting the report instead of "
            "excluding one rule" % (survivor, len(remaining), len(baseline)))
    assert_no_diff("a read-only review must not touch the project on disk")


def _filesystem_is_case_insensitive():
    """Does THIS filesystem resolve a differently-cased name to the same file?

    Asked of the fixture itself rather than guessed from the OS name: a case-sensitive volume can
    be mounted on Windows and macOS is configurable either way.
    """
    import os
    probe = os.path.join(PROJECT_DIR, "src", "Configuration", "Configuration.mdo")
    if not os.path.isfile(probe):
        # NOT the same as "case-sensitive": the probe is simply gone (fixture moved, or
        # MCP_PROJECT_REL points elsewhere). Returning False here would silently disable the
        # regression this test guards on Windows while claiming the filesystem is case-sensitive.
        _fail("case-sensitivity probe %s is missing - cannot tell what this filesystem does" % probe)
    return os.path.isfile(probe.lower()) and os.path.isfile(probe.upper())


@e2e_test(tool="code_review", kind="read")
def test_module_path_matching_is_case_insensitive_on_the_filesystem():
    """A modulePath whose casing differs from the on-disk name must still scope correctly.

    On a case-insensitive filesystem (Windows) the workspace happily resolves
    'commonmodules/calc/module.bsl', but the engine reports the on-disk casing - so a
    case-SENSITIVE comparison of the two filtered every finding out and answered "module is
    clean". A false clean is the worst answer this tool can give, which is why identity is
    decided by the filesystem (Files.isSameFile) rather than by string equality.

    Needs a real filesystem, so it lives here rather than in a unit test - and it only MEANS
    anything where the filesystem is case-insensitive. On a case-sensitive volume (Linux) the
    lowercased path names nothing, the tool correctly answers "Module not found", and asserting
    findings there would be asserting the opposite of correct behaviour. So probe the filesystem
    first and skip when case-only lookup genuinely cannot work."""
    if not _filesystem_is_case_insensitive():
        raise E2ESkip("case-sensitive filesystem: a case-only path variant names no file here, "
                      "so there is nothing for this test to assert")
    exact = _run_or_skip({"projectName": PROJECT, "modulePath": CALC_MODULE}, "module review, exact casing")
    assert_ok(exact, "module review with the canonical casing")
    assert_contains(exact.text, "MagicNumber", "the canonical casing must report the module's finding")

    lowered = _run_or_skip({"projectName": PROJECT, "modulePath": CALC_MODULE.lower()},
                           "module review, lowercased path")
    assert_ok(lowered, "module review with a lowercased path")
    assert_contains(lowered.text, "MagicNumber",
                    "the same module addressed in a different case must report the same finding, "
                    "not come back empty")
    assert_no_diff("a read-only review must not touch the project on disk")


@e2e_test(tool="code_review", kind="read")
def test_non_bsl_module_path_is_rejected():
    """A modulePath naming an EXISTING file that is not a BSL module must be refused, not
    reviewed. The engine reports diagnostics for .bsl sources only, so scoping to (say)
    Configuration.mdo would analyse its folder, match nothing when the findings are filtered
    to that exact path, and answer 'no issues' for a file that was never checked - a false
    clean, which is worse than an error."""
    not_a_module = "Configuration/Configuration.mdo"
    r = call("code_review", {"projectName": PROJECT, "modulePath": not_a_module})
    err = assert_error(r, "modulePath pointing at a non-BSL file")
    assert_contains(err, not_a_module, "the error must name the offending path")
    assert_contains(err, ".bsl", "the error must say what IS accepted")
    assert_no_diff("a rejected call must not touch the project on disk")
