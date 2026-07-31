"""
e2e tests for search_in_dcs (kind: read).

Read tool: literal/regex search across all .dcs (Data Composition Schema) files of a
project. getResponseType() == MARKDOWN, so the payload is in r.text (NOT r.structured).
It walks src/ for *.dcs, matches each line against a compiled Pattern, and renders
markdown. outputMode selects the shape:
  - "full"  (default): "## DCS search results for \"<q>\"" + "**Total:** N matches in M .dcs file(s)"
            + per-file "### <path>" sections with "**Line K:**" + ```xml``` context blocks.
  - "count":           "## DCS search count for \"<q>\"" + "**Total matches:** N in **M** .dcs file(s)".
  - "files":           "## DCS search files for \"<q>\"" + a "| File | Matches |" table.
A query with no hits renders the literal sentinel "No matches found.".

The base fixture has NO .dcs, so the real-match tests first create a DCS report+template
(via create_metadata) whose Template.dcs then contains known, countable tokens; those
tests are mutating and do NOT assert_no_diff. The zero-hit and negative tests stay
read-only and DO assert_no_diff. Fixture is git-reset per-test, so each build is fresh.

Created schema (report E2EDcsSearch, template MainSchema) serializes to
Reports/E2EDcsSearch/Templates/MainSchema/Template.dcs with a local data source
("DataSource1" on TWO lines: <name> and the dataset's <dataSource>) and a query dataset
whose <query> holds "FROM Catalog.Catalog" (ONE line) and "<dataSet xsi:type=\"DataSetQuery\">"
(ONE line). Those exact counts are the broken-proof signals.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)

_QUERY = "SELECT Catalog.Ref AS Ref, Catalog.Description AS Description FROM Catalog.Catalog AS Catalog"


def _make_schema(report):
    """Create Report.<report> + a DataCompositionSchema template (query -> local source + dataset),
    and return (fileMask, dcsPath) scoped to it. A UNIQUE name per test avoids colliding with the
    reports left in EDT's in-memory model by earlier tests (git resets disk, not the loaded model);
    the returned fileMask scopes each search to this one .dcs so on-disk accumulation can't skew counts.
    """
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Report." + report}),
              "create the DCS report")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": "Report.%s.Template.MainSchema" % report,
        "templateType": "DataCompositionSchema", "query": _QUERY}), "create the DCS template")
    wait_for_project_ready()
    return "Reports/%s/" % report, "Reports/%s/Templates/MainSchema/Template.dcs" % report


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS (create a .dcs, then search it - scoped by fileMask to its own report)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="search_in_dcs", kind="read")
def test_full_mode_finds_token_in_created_schema():
    """Default (full) mode: "FROM Catalog.Catalog" lives on the single <query> line of the
    created schema -> exactly one match, in one file, whose section and the matched XML line
    must render. A no-op/broken search would render the sentinel instead."""
    mask, dcs = _make_schema("E2EDcsSearchFull")
    r = call("search_in_dcs", {"projectName": PROJECT, "query": "FROM Catalog.Catalog", "fileMask": mask})
    assert_ok(r, "search literal 'FROM Catalog.Catalog'")
    assert_contains(r.text, 'DCS search results for "FROM Catalog.Catalog"',
                    "full-mode header must echo the query")
    assert_contains(r.text, "### " + dcs, "the .dcs that contains the token must be reported")
    assert_contains(r.text, "FROM Catalog.Catalog", "the matched XML line must appear in context")
    assert_contains(r.text, "**Total:** 1 matches in 1 .dcs file(s)",
                    "the query fragment occurs exactly once in the scoped .dcs")
    assert_not_contains(r.text, "No matches found.", "a real hit must not render the sentinel")


@e2e_test(tool="search_in_dcs", kind="read")
def test_count_mode_reports_exact_totals():
    """count mode: "DataSource1" appears on TWO lines (the source <name> and the dataset's
    <dataSource>) of the created schema -> exactly 2 matches in 1 file (scoped). Exact totals
    prove the count branch counted (and rendered no context)."""
    mask, _ = _make_schema("E2EDcsSearchCount")
    r = call("search_in_dcs",
             {"projectName": PROJECT, "query": "DataSource1", "fileMask": mask, "outputMode": "count"})
    assert_ok(r, "count mode for 'DataSource1'")
    assert_contains(r.text, 'DCS search count for "DataSource1"', "count mode must use the count header")
    assert_contains(r.text, "**Total matches:** 2 in **1** .dcs file(s)",
                    "count must report the exact totals for DataSource1")
    assert_not_contains(r.text, "```xml", "count mode must not render context code fences")


@e2e_test(tool="search_in_dcs", kind="read")
def test_files_mode_lists_matching_file():
    """files mode renders a "| File | Matches |" table. "DataSetQuery" appears on exactly one
    line of the created schema -> that file, count 1 (scoped by fileMask)."""
    mask, dcs = _make_schema("E2EDcsSearchFiles")
    r = call("search_in_dcs",
             {"projectName": PROJECT, "query": "DataSetQuery", "fileMask": mask, "outputMode": "files"})
    assert_ok(r, "files mode for 'DataSetQuery'")
    assert_contains(r.text, 'DCS search files for "DataSetQuery"', "files mode must use the files header")
    assert_contains(r.text, dcs, "files mode must list the .dcs that contains the token")


@e2e_test(tool="search_in_dcs", kind="read")
def test_regex_mode_matches_pattern():
    """isRegex=true compiles the query as a regex. "Data\\w+Query" matches "DataSetQuery"
    (1 match); the SAME query as a LITERAL searches for the chars 'Data\\w+Query', which
    never appear -> the two runs must disagree, proving regex mode is active."""
    mask, _ = _make_schema("E2EDcsSearchRegex")
    as_regex = call("search_in_dcs",
                    {"projectName": PROJECT, "query": r"Data\w+Query", "fileMask": mask,
                     "isRegex": True, "outputMode": "count"})
    assert_ok(as_regex, "regex 'Data\\w+Query'")
    assert_contains(as_regex.text, "**Total matches:** 1 in **1** .dcs file(s)",
                    "regex 'Data\\w+Query' must match DataSetQuery")
    as_literal = call("search_in_dcs",
                      {"projectName": PROJECT, "query": r"Data\w+Query", "fileMask": mask,
                       "outputMode": "count"})
    assert_ok(as_literal, "literal 'Data\\w+Query'")
    assert_contains(as_literal.text, "**Total matches:** 0 in **0** .dcs file(s)",
                    "literal 'Data\\w+Query' must NOT match (the literal chars are absent)")


@e2e_test(tool="search_in_dcs", kind="read")
def test_file_mask_scopes_search():
    """fileMask is a case-insensitive path-substring filter. The token matches under the
    report's own path; a mask the path cannot contain must exclude it (no matches)."""
    mask, _ = _make_schema("E2EDcsSearchMask")
    hit = call("search_in_dcs",
               {"projectName": PROJECT, "query": "DataSetQuery", "fileMask": mask, "outputMode": "count"})
    assert_ok(hit, "matching fileMask")
    assert_contains(hit.text, "**Total matches:** 1 in **1** .dcs file(s)",
                    "a matching fileMask must keep the file in scope")
    miss = call("search_in_dcs",
                {"projectName": PROJECT, "query": "DataSetQuery", "fileMask": "NoSuchPath_ZZZ",
                 "outputMode": "count"})
    assert_ok(miss, "non-matching fileMask")
    assert_contains(miss.text, "**Total matches:** 0 in **0** .dcs file(s)",
                    "a fileMask the path cannot contain must exclude the file")


# ──────────────────────────────────────────────────────────────────────────────
# READ-ONLY: zero-hit sentinel (no .dcs in the base fixture)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="search_in_dcs", kind="read")
def test_valid_query_with_no_hits_renders_sentinel():
    """A well-formed query that matches nothing must render "No matches found." with zero
    totals - NOT an error. The base fixture has no .dcs, so any token yields zero."""
    r = call("search_in_dcs", {"projectName": PROJECT, "query": "ZZ_NoSuchToken_e2e_QQ"})
    assert_ok(r, "valid query with zero hits is not an error")
    assert_contains(r.text, "**Total:** 0 matches in 0 .dcs file(s)", "a zero-hit search reports zero")
    assert_contains(r.text, "No matches found.", "a zero-hit search renders the explicit sentinel")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (read-only)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="search_in_dcs", kind="read")
def test_missing_project_name_errors_clearly():
    """Required projectName omitted -> JsonUtils.requireArguments -> "projectName is required"."""
    r = call("search_in_dcs", {"query": "Amount"})
    assert_error_quality(assert_error(r, "missing projectName"), names=["projectName"], suggests=[])
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="search_in_dcs", kind="read")
def test_missing_query_errors_clearly():
    """Required query omitted -> "query is required" (projectName is valid here)."""
    r = call("search_in_dcs", {"projectName": PROJECT})
    assert_error_quality(assert_error(r, "missing query"), names=["query"], suggests=[])
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="search_in_dcs", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args but the project does not exist -> "Project not found: <name>"."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("search_in_dcs", {"projectName": bad, "query": "Amount"})
    assert_error_quality(assert_error(r, "non-existent project"), names=[bad], suggests=[])
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="search_in_dcs", kind="read")
def test_invalid_output_mode_enum_errors_actionably():
    """outputMode is an enum; an out-of-set value -> "outputMode must be 'full', 'count',
    or 'files'". Actionable: it enumerates the valid values."""
    r = call("search_in_dcs",
             {"projectName": PROJECT, "query": "Amount", "outputMode": "verbose_e2e"})
    assert_error_quality(assert_error(r, "invalid outputMode"), names=["outputMode"],
                         suggests=["count", "files"])
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="search_in_dcs", kind="read")
def test_invalid_regex_pattern_errors_with_detail():
    """isRegex=true with a malformed pattern -> Pattern.compile throws -> "Invalid regex
    pattern '<q>': <detail>". The literal path would never error on this."""
    bad = "(unclosed_e2e"
    r = call("search_in_dcs", {"projectName": PROJECT, "query": bad, "isRegex": True})
    assert_error_quality(assert_error(r, "invalid regex"), names=[bad], suggests=[])
    assert_no_diff("an invalid call must not touch the project on disk")
