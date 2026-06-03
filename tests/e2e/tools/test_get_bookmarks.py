"""
e2e tests for get_bookmarks (kind: read).

Read tool: enumerates Eclipse workspace BOOKMARK markers
(marker type org.eclipse.core.resources.bookmark) across the open projects and
renders them as a MARKDOWN report (header "## Bookmarks", a "**Found:** N
bookmarks" count, then either "*No bookmarks found.*" or a Project/Message/Path/
Line table). ResponseType is the default MARKDOWN, so the happy payload is in
r.text (NOT r.structured). Errors come back via ToolResult.error(...).toJson()
which the server surfaces as isError=true.

Params (ALL optional — GetBookmarksTool.getInputSchema):
  - projectName : filter to one project (optional)
  - filePath    : case-insensitive path-substring filter (optional)
  - limit       : max results; CLAMPED via Pagination.clampLimit(limit, 1000) to
                  [1, 1000] — an out-of-range limit is silently clamped, NOT an
                  error. So there is no limit-validation negative to fabricate.

Negative matrix is intentionally MINIMAL and matches the tool's REAL reachable
errors from valid client input:
  - There is NO required parameter (every param is optional) -> no "missing
    required param" error can occur.
  - There is NO enum and NO XOR/conditional-required combination -> none to test.
  - The ONLY reachable error is a non-existent projectName ->
    ProjectContext.of(name).exists() == false ->
    ToolResult.error("Project not found: <name>").
  That single error path is covered below.

Fixture note: TestConfiguration is a MINIMAL committed 1C project and has NO
bookmarks (bookmarks are Eclipse workspace markers set by a human in the IDE,
not files tracked in the project tree — a repo grep for "bookmark" finds none).
The valid HAPPY state for this tool here is therefore the well-formed EMPTY
report. That is a real happy case, asserted on its required structural markers,
and every test ends with assert_no_diff() because a read tool must never mutate
the project on disk.
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains, assert_no_diff, e2e_test, PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths (valid empty-state report — the fixture has no bookmarks)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_bookmarks", kind="read")
def test_no_args_returns_wellformed_empty_report():
    # No filters: the tool walks every open project's bookmark markers. The fixture
    # has none, so the CORRECT result is the well-formed empty report. We assert all
    # three structural markers the renderer must emit; a broken tool that returned a
    # no-op / blank string / a raw JSON error would fail every one of these.
    r = call("get_bookmarks", {})
    assert_ok(r, "get_bookmarks no args")
    assert_contains(r.text, "## Bookmarks", "report must carry the section header")
    # The count line proves the collector ran and counted (0), not just printed a banner.
    assert_contains(r.text, "**Found:** 0 bookmarks",
                    "empty fixture must report exactly zero bookmarks")
    # The empty-state sentinel proves the isEmpty() branch rendered (no stray table).
    assert_contains(r.text, "*No bookmarks found.*",
                    "empty result must render the explicit no-bookmarks notice")
    # If a table header leaked in on an empty result, the renderer is broken.
    assert_not_contains(r.text, "| Project | Message | Path | Line |",
                        "empty result must NOT render the bookmark table header")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_bookmarks", kind="read")
def test_projectname_filter_on_fixture_returns_empty_report():
    # Scoping to the real fixture project is a valid call (ProjectContext.of(PROJECT)
    # exists). It still has no bookmarks -> same well-formed empty report. This proves
    # the projectName branch resolves the project and does NOT error for a real name.
    r = call("get_bookmarks", {"projectName": PROJECT})
    assert_ok(r, "get_bookmarks projectName=fixture")
    assert_contains(r.text, "**Found:** 0 bookmarks",
                    "scoping to the fixture must still report zero bookmarks")
    assert_contains(r.text, "*No bookmarks found.*",
                    "scoped empty result must render the no-bookmarks notice")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_bookmarks", kind="read")
def test_filepath_filter_is_accepted_and_filters_to_empty():
    # filePath is a path-substring filter, NOT a validated path: a value that matches
    # nothing is a normal empty result, not an error. This documents that contract and
    # proves the filter branch runs without throwing on a non-existent path fragment.
    r = call("get_bookmarks",
             {"filePath": "ZZZ_no_such_path_fragment_e2e"})
    assert_ok(r, "get_bookmarks filePath=non-matching")
    assert_contains(r.text, "**Found:** 0 bookmarks",
                    "a non-matching filePath filter yields zero, not an error")
    assert_contains(r.text, "*No bookmarks found.*",
                    "filtered-to-empty result must render the no-bookmarks notice")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_bookmarks", kind="read")
def test_out_of_range_limit_is_clamped_not_rejected():
    # limit is routed through Pagination.clampLimit(limit, 1000) = min(max(1,n),1000),
    # so a zero/negative limit is CLAMPED to 1 (silently), NOT rejected. Asserting OK
    # here pins that contract: if someone "added validation" that errored on limit<=0,
    # this test would catch the behavior change. There is no limit-error to fabricate.
    r = call("get_bookmarks", {"limit": 0})
    assert_ok(r, "get_bookmarks limit=0 is clamped, not an error")
    assert_contains(r.text, "## Bookmarks",
                    "clamped-limit call must still render the report")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix (the ONE reachable error: non-existent projectName)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_bookmarks", kind="read")
def test_nonexistent_project_errors_and_names_value():
    # projectName given but no such project -> ProjectContext.of(bad).exists() == false
    # -> ToolResult.error("Project not found: <bad>"). This is the tool's only reachable
    # error path from a valid MCP client (all params optional; limit clamps; filePath
    # never validates). A broken/permissive resolver would succeed -> assert_error fails.
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_bookmarks", {"projectName": bad})
    err = assert_error(r, "non-existent project")
    # AUDIT: the error names the bad project but is NOT actionable — it gives no
    # next step (no pointer to list_projects, the sibling tool that enumerates valid
    # project names). suggests=[] is deliberate; this is a fix-card to add a hint.
    assert_error_quality(err, names=[bad], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")
