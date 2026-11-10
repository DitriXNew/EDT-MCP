"""
e2e tests for get_problem_summary (kind: read).

Read tool: aggregates EDT validation markers (IMarkerManager) into a MARKDOWN
report — an "Overall Totals" severity table plus an optional per-project table.
ResponseType is the default MARKDOWN, so on success the payload is the raw
markdown string in r.text (NOT r.structured).

A ToolResult.error(...) payload IS still flagged isError:true even for a MARKDOWN
tool (McpProtocolHandler.isJsonErrorPayload detects {"success":false,...}); the
message then lands in r.structured["error"], which error_text() returns.

Why the HAPPY assertions target structural anchors, not a marker count:
  The TestConfiguration fixture's marker set is not part of the committed on-disk
  truth (markers are computed by EDT validation, not stored in git) and may be 0.
  So we cannot assert a specific count. But execute() ALWAYS emits, on any success:
    "## Problem Summary", "### Overall Totals", the "| Severity | Count |" table
    header, and a "| **TOTAL** | **<n>** |" row.
  These four anchors are produced by the real aggregation code path and would be
  ABSENT if the tool returned a no-op / wrong payload / error — so asserting them
  satisfies mutation thinking. (The per-project "### By Project" / "*No problems
  found.*" branch depends on whether any marker exists, so it is NOT asserted as a
  fixed value.)

Aggregation (groupBy / top / severity / checkId / modulePaths):
  With none of them the legacy report above is returned unchanged (pinned below). With
  groupBy the report is ONE table of groups sorted by count, the rest folded into one
  "*(N other groups)*" row so the Count column always adds up to the Total line. A
  grouping test that must see a REAL group seeds a syntax error into a fixture module
  first (kind="write-metadata": the runner reverts the module and resyncs the model).

Negative matrix (every one reachable from client input):
  - non-existent projectName -> "Project not found: <name>"
  - top without groupBy      -> refused, names groupBy
  - unknown groupBy value    -> refused, lists check / module / object
  - out-of-set severity      -> refused, lists the valid set
"""

import re
import time

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains, assert_no_diff, e2e_test, PROJECT, _fail,
    wait_for_project_ready,
)

# The fixture common module the marker is seeded into (Cyrillic Name on purpose: the module
# path carries the programmatic Name verbatim).
SEEDED_MODULE_NAME = "Вычисление"
SEEDED_MODULE_PATH = "CommonModules/%s/Module.bsl" % SEEDED_MODULE_NAME
SEEDED_MODULE_OWNER = "CommonModule.%s" % SEEDED_MODULE_NAME
# A bare identifier at module level is not a statement in BSL -> a hard syntax error.
SEEDED_BAD_SOURCE = "\nE2eSummaryProbeXyz\n"
MARKER_POLL_TIMEOUT = 120

TOTAL_RE = re.compile(r"\*\*Total:\*\* (\d+) problems in (\d+) groups")


def _data_rows(text):
    """Data rows of the single grouping table (header and separator dropped)."""
    rows = [ln for ln in text.splitlines() if ln.startswith("|")]
    return rows[2:]


def _cells(row):
    return [c.strip() for c in row.strip("|").split("|")]


def _seed_and_wait_for_module_group():
    """Seed a syntax error into the fixture module and wait until the module grouping shows it.

    Fails (never skips) when no marker appears: the assertions that follow would otherwise be
    vacuously true.
    """
    w = call("write_module_source", {
        "projectName": PROJECT, "modulePath": SEEDED_MODULE_PATH,
        "mode": "append", "source": SEEDED_BAD_SOURCE,
    })
    assert_ok(w, "seeding a BSL syntax error into %s" % SEEDED_MODULE_PATH)
    wait_for_project_ready()
    call("revalidate_objects", {"projectName": PROJECT, "objects": [SEEDED_MODULE_OWNER]})
    deadline = time.time() + MARKER_POLL_TIMEOUT
    last = ""
    while time.time() < deadline:
        r = call("get_problem_summary", {"projectName": PROJECT, "groupBy": "module",
                                         "modulePaths": [SEEDED_MODULE_PATH]})
        assert_ok(r, "polling the module grouping for the seeded marker")
        last = r.text or ""
        if "| %s |" % SEEDED_MODULE_PATH in last:
            return last
        time.sleep(2)
    _fail("no group for %s appeared within %ds; last report:\n%s"
          % (SEEDED_MODULE_PATH, MARKER_POLL_TIMEOUT, last[:600]))
    return ""  # unreachable; _fail raises


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_problem_summary", kind="read")
def test_all_projects_renders_summary_and_does_not_mutate():
    # No args -> summary across ALL projects. The aggregation always renders the
    # report skeleton; assert every structural anchor so a no-op / wrong payload
    # would fail this test.
    r = call("get_problem_summary", {})
    assert_ok(r, "get_problem_summary all projects")
    assert_contains(r.text, "## Problem Summary",
                    "the report must carry its top-level heading")
    assert_contains(r.text, "### Overall Totals",
                    "the totals section header must be emitted")
    # The severity table HEADER row is fixed text built unconditionally — proves the
    # totals table was constructed, not just the heading echoed.
    assert_contains(r.text, "| Severity | Count |",
                    "the Overall Totals table header must be present")
    # The grand-total row is always appended (even when every count is 0) — proves
    # the marker stream was actually summed, not skipped.
    assert_contains(r.text, "| **TOTAL** |",
                    "the grand-total row must be present (aggregation ran)")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_problem_summary", kind="read")
def test_filter_by_existing_project_renders_summary():
    # projectName=TestConfiguration is a REAL project -> ProjectContext.exists()
    # passes and the summary still renders. If the project filter wrongly rejected a
    # valid project, this would surface as isError (assert_ok would fail).
    r = call("get_problem_summary", {"projectName": PROJECT})
    assert_ok(r, "get_problem_summary for the fixture project")
    assert_contains(r.text, "## Problem Summary",
                    "a valid project filter must still render the report")
    assert_contains(r.text, "### Overall Totals",
                    "the totals section must render for a valid project filter")
    assert_contains(r.text, "| **TOTAL** |",
                    "the grand-total row must render for a valid project filter")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_problem_summary", kind="read")
def test_default_call_output_is_unchanged():
    """Without the aggregation arguments the report keeps its legacy shape: the totals
    heading directly follows the title, with no grouping title and no filter line."""
    r = call("get_problem_summary", {"projectName": PROJECT})
    assert_ok(r, "get_problem_summary legacy call")
    assert_contains(r.text, "## Problem Summary\n\n### Overall Totals\n\n| Severity | Count |",
                    "the legacy heading sequence must be byte-identical")
    assert_not_contains(r.text, "(by ", "no grouping title on the legacy path")
    assert_not_contains(r.text, "filter:", "no filter line on the legacy path")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_problem_summary", kind="write-metadata")
def test_group_by_check_counts_the_seeded_syntax_error():
    """A seeded syntax error shows up as a module group of exactly that path, and the check
    grouping inside that module lists at least one check whose counts add up."""
    module_report = _seed_and_wait_for_module_group()
    assert_contains(module_report, "## Problem Summary (by module)", "the grouping title")
    assert_contains(module_report, "| Module path | Count | Checks |", "the module table header")
    for row in _data_rows(module_report):
        if _cells(row)[0] != SEEDED_MODULE_PATH:
            _fail("modulePaths let another module through: %r" % row)

    r = call("get_problem_summary", {"projectName": PROJECT, "groupBy": "check",
                                     "modulePaths": [SEEDED_MODULE_PATH]})
    assert_ok(r, "groupBy=check inside the seeded module")
    assert_contains(r.text, "## Problem Summary (by check)", "the grouping title")
    assert_contains(r.text, "| Check code | Severity | Count | Fix registered | Modules |",
                    "the check table header")
    m = TOTAL_RE.search(r.text)
    if not m or int(m.group(1)) < 1:
        _fail("the seeded marker must be counted: %s" % r.text[:600])
    rows = _data_rows(r.text)
    if not rows:
        _fail("groupBy=check returned no group for the seeded module:\n%s" % r.text[:600])
    counted = sum(int(_cells(row)[2]) for row in rows)
    if counted != int(m.group(1)):
        _fail("the Count column (%d) must add up to the Total (%s)" % (counted, m.group(1)))
    for row in rows:
        if _cells(row)[4] != "1":
            _fail("every check here lives in exactly one module (the filtered one): %r" % row)


@e2e_test(tool="get_problem_summary", kind="read")
def test_top_one_folds_the_rest_and_totals_add_up():
    """top=1 shows one group and folds the rest into a single other-row; whatever the live
    marker set is, the Count column (other-row included) adds up to the Total line."""
    r = call("get_problem_summary", {"groupBy": "check", "top": 1})
    assert_ok(r, "groupBy=check top=1")
    assert_contains(r.text, "## Problem Summary (by check)", "the grouping title")
    m = TOTAL_RE.search(r.text)
    if not m:
        _fail("the Total line is missing:\n%s" % r.text[:600])
    total, groups = int(m.group(1)), int(m.group(2))
    rows = _data_rows(r.text)
    if groups == 0:
        assert_contains(r.text, "*No problems found.*", "an empty grouping says so")
        return
    counted = sum(int(_cells(row)[2]) for row in rows)
    if counted != total:
        _fail("shown + folded counts (%d) must equal the Total (%d):\n%s" % (counted, total, r.text[:800]))
    if groups > 1:
        if len(rows) != 2 or "other group" not in rows[1]:
            _fail("top=1 over %d groups must print one group and one other-row: %r" % (groups, rows))
        assert_contains(r.text, "(showing top 1)", "the truncation is announced")
    else:
        assert_not_contains(r.text, "other group", "nothing folded, no other-row")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_problem_summary", kind="read")
def test_top_without_group_by_is_refused():
    r = call("get_problem_summary", {"projectName": PROJECT, "top": 5})
    err = assert_error(r, "top without groupBy")
    assert_error_quality(err, names=["top"], suggests=["groupBy"],
                         ctx="top without groupBy names the missing parameter")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_problem_summary", kind="read")
def test_unknown_group_by_is_refused_with_the_valid_set():
    r = call("get_problem_summary", {"projectName": PROJECT, "groupBy": "severity"})
    err = assert_error(r, "unknown groupBy")
    assert_error_quality(err, names=["severity"], suggests=["check", "module", "object"],
                         ctx="unknown groupBy echoes the value and lists the valid set")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_problem_summary", kind="read")
def test_invalid_severity_is_refused_not_widened():
    r = call("get_problem_summary", {"projectName": PROJECT, "severity": "WARNINGS"})
    err = assert_error(r, "invalid severity")
    assert_error_quality(err, names=["WARNINGS"], suggests=["ERRORS", "MINOR"],
                         ctx="invalid severity echoes the value and lists the valid set")
    assert_no_diff("an invalid call must not touch the project on disk")

@e2e_test(tool="get_problem_summary", kind="read")
def test_nonexistent_project_errors_and_names_value():
    # ProjectContext.of(bad).exists() is false -> ToolResult.error("Project not
    # found: <name>"). For a MARKDOWN tool this is still flagged isError:true and the
    # message is carried in structuredContent.error.
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_problem_summary", {"projectName": bad})
    err = assert_error(r, "non-existent project filter")
    # AUDIT: the error names the bad project ("Project not found: <name>") but is NOT
    # actionable — it does not point at list_projects (the sibling tool that
    # enumerates valid project names). suggests=[] is deliberate; this is a fix-card
    # to append a next-step hint to the message.
    assert_error_quality(err, names=[bad], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")
