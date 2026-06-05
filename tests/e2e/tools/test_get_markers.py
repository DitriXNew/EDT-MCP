"""
e2e tests for get_markers (kind: read).

Consolidation of the former get_bookmarks + get_tasks tools. Scans Eclipse
workspace markers and renders them as a MARKDOWN report: a "## Markers" header, a
"**Found:** N markers" banner, then either "*No markers found.*" or a table of
Kind | Type | Priority | Message | Path | Line. ResponseType is the default
MARKDOWN, so the happy payload is in r.text (NOT r.structured); a
ToolResult.error payload is diverted to a structured JSON error (isError:true).

Params (ALL optional — GetMarkersTool.getInputSchema):
  - projectName : restrict to one project (else all open projects)
  - filePath    : case-insensitive substring match on the resource path
  - markerKind  : enum {bookmark, task}; omit to list BOTH families
  - priority    : enum {high, normal, low}; sub-filters TASK markers only and may
                  NOT be combined with markerKind=bookmark (a bookmark has no
                  priority) — that combination is a rejected contradiction
  - limit       : CLAMPED via Pagination.clampLimit(limit, 1000) to [1, 1000]

Deterministic vs non-deterministic state:
  - The committed TestConfiguration fixture has NO human-placed bookmarks
    (bookmarks are workspace markers set in the IDE, not files in the tree), so
    markerKind=bookmark and an unmatchable filePath are DETERMINISTIC empty
    states ("**Found:** 0 markers" + "*No markers found.*") — strong mutation
    signals: a tool that ignored the filter would surface non-zero rows.
  - Task markers (TODO/FIXME) are produced by EDT's live Xtext indexing at
    runtime, not stored in git, so a no-filter / markerKind=task scan asserts only
    the structurally-guaranteed banner (header + "**Found:**"), never a specific
    row count. The well-formed report IS the valid happy result.
Every test ends with assert_no_diff() — a read tool must never mutate the project.

Negative matrix targets the tool's REAL execute() error paths, all validated
before any workspace access (so reachable from valid client input):
  - non-existent project   -> "Project not found: <name>. Use list_projects ..."
  - out-of-set markerKind  -> "Invalid markerKind: '<v>'. Must be one of: bookmark, task"
  - out-of-set priority    -> "Invalid priority: '<v>'. Must be one of: high, normal, low"
  - priority + markerKind=bookmark -> "priority filter applies to task markers only ..."
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains, assert_no_diff, e2e_test, PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_markers", kind="read")
def test_no_args_returns_well_formed_report_and_does_not_mutate():
    # All params optional -> {} scans every open project for both marker families.
    # The tool ALWAYS emits the header + Found banner when it ran the markdown
    # build; a broken tool returning a no-op / empty / wrong payload fails these,
    # even though the fixture may surface zero rows.
    r = call("get_markers", {})
    assert_ok(r, "get_markers no args")
    assert_contains(r.text, "## Markers", "report must carry the Markers header")
    assert_contains(r.text, "**Found:**", "report must carry the Found-count banner")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_markers", kind="read")
def test_project_filter_returns_well_formed_report():
    # projectName resolves the fixture project (must EXIST, else this takes the
    # "Project not found" branch). A valid project still yields the banner-bearing
    # report — proves resolution succeeded AND the markdown build ran.
    r = call("get_markers", {"projectName": PROJECT})
    assert_ok(r, "get_markers projectName=fixture")
    assert_contains(r.text, "## Markers", "scoped report must carry the Markers header")
    assert_contains(r.text, "**Found:**", "scoped report must carry the Found-count banner")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_markers", kind="read")
def test_markerkind_bookmark_yields_empty_report():
    # markerKind=bookmark scans ONLY bookmark markers. The fixture has none, so the
    # CORRECT result is the deterministic well-formed empty report. A tool that
    # ignored markerKind (and leaked task rows) would report a non-zero count ->
    # the "0 markers" / "No markers found" assertion would FAIL.
    r = call("get_markers", {"markerKind": "bookmark"})
    assert_ok(r, "get_markers markerKind=bookmark")
    assert_contains(r.text, "**Found:** 0 markers",
                    "empty fixture must report exactly zero bookmark markers")
    assert_contains(r.text, "*No markers found.*",
                    "empty result must render the explicit no-markers notice")
    assert_not_contains(r.text, "| Kind | Type | Priority | Message | Path | Line |",
                        "empty result must NOT render the marker table header")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_markers", kind="read")
def test_markerkind_task_returns_well_formed_report():
    # markerKind=task scans ONLY task markers (TODO/FIXME). Their presence is
    # runtime-non-deterministic, so assert only the structural banner the renderer
    # must emit. Proves the task branch runs without error.
    r = call("get_markers", {"markerKind": "task"})
    assert_ok(r, "get_markers markerKind=task")
    assert_contains(r.text, "## Markers", "task report must carry the Markers header")
    assert_contains(r.text, "**Found:**", "task report must carry the Found banner")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_markers", kind="read")
def test_nonmatching_filepath_yields_empty_report():
    # A filePath substring that cannot match any resource path is a VALID call (not
    # an error) and must return the empty report. Discriminating mutation signal: a
    # tool that IGNORED filePath would surface whatever real rows exist and report a
    # non-zero count, failing the "0 markers" / "No markers found" assertion.
    r = call("get_markers", {"filePath": "zzz_no_such_path_substring_e2e"})
    assert_ok(r, "get_markers non-matching filePath")
    assert_contains(r.text, "**Found:** 0 markers",
                    "an unmatchable filePath must filter every marker out")
    assert_contains(r.text, "No markers found",
                    "the empty-state banner must be emitted when nothing matches")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_markers", kind="read")
def test_valid_priority_enum_is_accepted():
    # "low" is a valid priority enum value WITHOUT markerKind -> allowed (priority
    # sub-filters tasks; bookmarks unaffected). The call must SUCCEED and produce
    # the banner-bearing report. Guards the enum's accept side.
    r = call("get_markers", {"priority": "low"})
    assert_ok(r, "get_markers priority=low (valid enum)")
    assert_contains(r.text, "## Markers", "valid-priority report must carry the Markers header")
    assert_contains(r.text, "**Found:**", "valid-priority report must carry the Found banner")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_markers", kind="read")
def test_out_of_range_limit_is_clamped_not_rejected():
    # limit is routed through Pagination.clampLimit(limit, 1000) = min(max(1,n),1000),
    # so limit=0 is CLAMPED to 1 (silently), NOT rejected. Pins that contract: a
    # later "validation" that errored on limit<=0 would be caught here.
    r = call("get_markers", {"limit": 0})
    assert_ok(r, "get_markers limit=0 is clamped, not an error")
    assert_contains(r.text, "## Markers", "clamped-limit call must still render the report")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix (all reachable from valid client input, validated up front)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_markers", kind="read")
def test_nonexistent_project_errors_and_names_value():
    # projectName resolves via ProjectContext; a missing project ->
    # ToolResult.error(ProjectContext.notFoundMessage(name)) which names the bad
    # value AND points at list_projects (the sibling discovery tool).
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_markers", {"projectName": bad})
    err = assert_error(r, "non-existent project")
    assert_error_quality(err, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project names the bad value and points at list_projects")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_markers", kind="read")
def test_invalid_markerkind_is_rejected_actionably():
    # markerKind is a closed enum {bookmark, task}; an out-of-set value is rejected
    # up front with a message that echoes the bad value AND enumerates the valid set.
    bad = "comment_e2e"
    r = call("get_markers", {"markerKind": bad})
    err = assert_error(r, "out-of-set markerKind enum")
    assert_error_quality(err, names=[bad], suggests=["bookmark", "task"],
                         ctx="invalid markerKind echoes the bad value and lists the valid enum values")
    assert "## Markers" not in (err or ""), \
        "a rejected markerKind must NOT fall through to the marker report"
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_markers", kind="read")
def test_invalid_priority_is_rejected_actionably():
    # priority is a closed enum {high, normal, low}; an out-of-set value is rejected
    # up front, echoing the bad value AND enumerating the valid set.
    bad = "urgent_e2e"
    r = call("get_markers", {"priority": bad})
    err = assert_error(r, "out-of-set priority enum")
    assert_error_quality(err, names=[bad], suggests=["high", "normal", "low"],
                         ctx="invalid priority echoes the bad value and lists the valid enum values")
    assert "## Markers" not in (err or ""), \
        "a rejected priority must NOT fall through to the marker report"
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_markers", kind="read")
def test_priority_with_bookmark_kind_is_rejected():
    # priority sub-filters the task family only; combining it with
    # markerKind=bookmark selects no rows it could apply to. The tool rejects the
    # contradiction up front with an actionable message rather than silently
    # emptying the result.
    r = call("get_markers", {"markerKind": "bookmark", "priority": "high"})
    err = assert_error(r, "priority + markerKind=bookmark contradiction")
    assert_contains(err or "", "task markers only",
                    "the contradiction error must explain priority is task-only")
    assert "## Markers" not in (err or ""), \
        "a rejected contradiction must NOT fall through to the marker report"
    assert_no_diff("an invalid call must not touch the project on disk")
