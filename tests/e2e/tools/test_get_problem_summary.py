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

Why the NEGATIVE matrix is minimal (documented, not fabricated):
  getInputSchema() declares exactly ONE optional property, projectName. execute()
  reads only projectName (extractStringArgument — NOT requireArgument), so:
    - there is NO required parameter   -> no "missing required" negative,
    - there is NO XOR / conditional / enum parameter -> no invalid-combo negative.
  The ONLY error reachable from valid client input is a non-existent projectName,
  which resolves to ToolResult.error("Project not found: <name>"). That single
  negative is covered below. Fabricating any other negative would be inventing an
  error path that cannot occur (forbidden by the anti-cheat rules).
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_no_diff, e2e_test, PROJECT,
)


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


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix
#
# Only ONE negative is reachable: projectName is optional and is the sole param,
# so there is no missing-required / XOR / conditional / enum case to test (see the
# module docstring). The reachable error is a non-existent projectName.
# ──────────────────────────────────────────────────────────────────────────────

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
