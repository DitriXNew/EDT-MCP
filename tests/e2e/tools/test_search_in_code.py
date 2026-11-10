"""
e2e tests for search_in_code (kind: read).

Read tool: literal/regex full-text search across all .bsl modules of a project.
getResponseType() == MARKDOWN, so the payload is in r.text (NOT r.structured).
It walks the project's src/ tree, matches each line against a compiled Pattern,
and renders markdown. outputMode selects the shape:
  - "full"  (default): "## Search Results for \"<q>\"" + "**Total:** N matches in M files"
            + per-file "### <path>" sections with "**Line K:**" + ```bsl``` context blocks.
  - "count":           "## Search Count for \"<q>\"" + "**Total matches:** N in **M** files".
  - "files":           "## Search Files for \"<q>\"" + a "| File | Matches |" table.
  - "lines":           "## Search Lines for \"<q>\"" + the "**Total:**" line of full mode + ONE
            ```text``` fence of "<path>:<line>: <matched line verbatim>" rows (no per-file
            headings, no context: contextLines is ignored; limit caps the rows as in full).
A query with no hits renders the literal sentinel "No matches found.".

Being a READ tool, EVERY test ends with assert_no_diff(): search must never mutate
the project on disk (the empty-diff guardrail also catches a tool that secretly writes
its result file into the project tree).

Fixture truth used (TestConfiguration/src, English Names — verified on disk):
  - CommonModules/Error/Module.bsl  -> a single line, the literal word "Error".
  - CommonModules/OK/Module.bsl     -> EMPTY (no lines, no matches ever).
  - Configuration/ManagedApplicationModule.bsl -> real BSL with the literal comment
    "// TEST DEBUG", procedures BeforeStart/OnStart, EndProcedure, and the string
    "Debug e2e OK". This file lives under Configuration/, which has NO metadataType
    folder, so the commonModules / catalogs metadata filters EXCLUDE it.
  Other .bsl files exist (CommonModules/Calc, plus the rename-cascade modules
  CascadeEn / CascadeUser / Вычисление) but they use Russian keywords and custom
  identifiers, so they contribute ZERO matches to the English-token / literal counts
  asserted below. The totals therefore stay exactly knowable and remain discriminating
  signals (a broken search can't fake them).

Real execute() error paths targeted by the negative matrix (SearchInCodeTool.java):
  - missing projectName / query -> JsonUtils.requireArguments -> "<name> is required".
  - non-existent project         -> "Project not found: <name>".
  - invalid outputMode enum      -> "outputMode must be 'full', 'lines', 'count', or 'files'".
  - unknown metadataType         -> "Unknown metadataType '<t>'. Supported: ...".
  - invalid regex + isRegex=true -> "Invalid regex pattern '<q>': <detail>".
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="search_in_code", kind="read")
def test_full_mode_finds_literal_token_in_real_file():
    """Default (full) mode: the literal "// TEST DEBUG" comment exists on exactly one
    line of Configuration/ManagedApplicationModule.bsl and NOWHERE else in the fixture.
    A working search must render that file's section, the exact line number block, and
    the surrounding context. A no-op/broken search would render "No matches found."."""
    r = call("search_in_code", {"projectName": PROJECT, "query": "// TEST DEBUG"})
    assert_ok(r, "search literal '// TEST DEBUG'")

    # Header echoes the query -> proves the query reached the formatter.
    assert_contains(r.text, 'Search Results for "// TEST DEBUG"',
                    "full-mode header must echo the query")
    # The match lives in this file -> its "### <path>" section must be present.
    assert_contains(r.text, "### Configuration/ManagedApplicationModule.bsl",
                    "the file actually containing the token must be reported")
    # The context block carries the matched source line verbatim (numbered, in a code
    # fence). A no-op tool that returns an empty result cannot produce this.
    assert_contains(r.text, "// TEST DEBUG",
                    "the matched source line must appear in the context block")
    # The fixture's only other files (Error, empty OK) do NOT contain this comment;
    # exactly one match in one file is the precise, broken-proof signal.
    assert_contains(r.text, "**Total:** 1 matches in 1 files",
                    "the literal occurs exactly once in exactly one fixture file")
    # Sentinel must be ABSENT on a real hit (guards against an always-empty result).
    assert_not_contains(r.text, "No matches found.",
                        "a real hit must not render the no-match sentinel")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_count_mode_reports_exact_totals_for_known_token():
    """count mode is a distinct branch (no context, no per-file sections). The literal
    "EndProcedure" appears once each in BeforeStart and OnStart of the managed-app
    module -> exactly 2 matches in 1 file across the whole fixture (Error/OK don't
    contain it). Asserting the exact totals proves the count branch actually counted."""
    r = call("search_in_code",
             {"projectName": PROJECT, "query": "EndProcedure", "outputMode": "count"})
    assert_ok(r, "count mode for 'EndProcedure'")

    # count-specific header distinguishes this branch from 'full'/'files'.
    assert_contains(r.text, 'Search Count for "EndProcedure"',
                    "count mode must use the count header")
    # Exact totals: 2 occurrences (two EndProcedure lines), all in 1 file.
    assert_contains(r.text, "**Total matches:** 2 in **1** files",
                    "count must report the exact fixture totals for EndProcedure")
    # count mode must NOT emit per-line context blocks (that's the 'full' branch).
    assert_not_contains(r.text, "```bsl",
                        "count mode must not render context code fences")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_files_mode_lists_matching_file_with_count():
    """files mode renders a "| File | Matches |" table, one row per matching file with
    its per-file count. The literal word "Error" matches the single line of
    CommonModules/Error/Module.bsl (and nowhere else) -> exactly that file, count 1."""
    r = call("search_in_code",
             {"projectName": PROJECT, "query": "Error", "outputMode": "files",
              "caseSensitive": True})
    assert_ok(r, "files mode for 'Error'")

    assert_contains(r.text, 'Search Files for "Error"',
                    "files mode must use the files header")
    # The table row must name the file that contains the token, with its count.
    assert_contains(r.text, "CommonModules/Error/Module.bsl",
                    "files mode must list the file that contains the token")
    # The OK module is empty -> it can never appear as a matching file.
    assert_not_contains(r.text, "CommonModules/OK/Module.bsl",
                        "the empty OK module must never be reported as a match")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_metadatatype_filter_scopes_search_to_folder():
    """metadataType=commonModules restricts the walk to CommonModules/*. The literal
    "// TEST DEBUG" lives ONLY in Configuration/ManagedApplicationModule.bsl, which is
    OUTSIDE CommonModules -> the filter must exclude it, yielding the no-match sentinel.
    A broken/ignored filter would still find the comment and report a hit."""
    r = call("search_in_code",
             {"projectName": PROJECT, "query": "// TEST DEBUG",
              "metadataType": "commonModules"})
    assert_ok(r, "metadataType=commonModules excludes Configuration module")

    # The token exists in the project but NOT under CommonModules -> filtered out.
    assert_contains(r.text, "No matches found.",
                    "the commonModules filter must exclude the Configuration module")
    # The Configuration file's section must NOT leak through the filter.
    assert_not_contains(r.text, "### Configuration/ManagedApplicationModule.bsl",
                        "a respected metadata filter must not report out-of-scope files")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_case_sensitive_flag_changes_results():
    """caseSensitive is a real branch. The fixture contains "Procedure"/"EndProcedure"
    (capital P) but never the all-lowercase token "endprocedure". With the default
    case-insensitive search that lowercase query matches; with caseSensitive=true it
    must NOT. The two calls must disagree -> proves the flag is honoured, not dropped."""
    insensitive = call("search_in_code",
                       {"projectName": PROJECT, "query": "endprocedure",
                        "outputMode": "count"})
    assert_ok(insensitive, "case-insensitive 'endprocedure'")
    # Default (insensitive) folds case -> the capitalised EndProcedure lines match.
    assert_contains(insensitive.text, "**Total matches:** 2 in **1** files",
                    "case-insensitive 'endprocedure' must match the EndProcedure lines")

    sensitive = call("search_in_code",
                     {"projectName": PROJECT, "query": "endprocedure",
                      "outputMode": "count", "caseSensitive": True})
    assert_ok(sensitive, "case-sensitive 'endprocedure'")
    # With case sensitivity on, the all-lowercase query has zero matches.
    assert_contains(sensitive.text, "**Total matches:** 0 in **0** files",
                    "case-sensitive 'endprocedure' must NOT match capitalised tokens")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_regex_mode_matches_pattern():
    """isRegex=true compiles the query as a regex instead of a literal. The pattern
    "End\\w+" matches the "EndProcedure" tokens (2 occurrences). The SAME pattern run
    as a LITERAL (isRegex omitted) would search for the four characters 'End\\w+',
    which never appear -> the two runs must disagree, proving regex mode is active."""
    as_regex = call("search_in_code",
                    {"projectName": PROJECT, "query": r"End\w+",
                     "isRegex": True, "outputMode": "count"})
    assert_ok(as_regex, "regex 'End\\w+'")
    # Regex matches both EndProcedure lines.
    assert_contains(as_regex.text, "**Total matches:** 2 in **1** files",
                    "regex 'End\\w+' must match the EndProcedure tokens")

    as_literal = call("search_in_code",
                      {"projectName": PROJECT, "query": r"End\w+",
                       "outputMode": "count"})
    assert_ok(as_literal, "literal 'End\\w+'")
    # As a literal the backslash-w sequence is absent from the fixture -> zero matches.
    assert_contains(as_literal.text, "**Total matches:** 0 in **0** files",
                    "literal 'End\\w+' must NOT match (the literal chars are absent)")
    assert_no_diff("a read tool must not touch the project on disk")


MANAGED_APP = "Configuration/ManagedApplicationModule.bsl"


def _lines_block(text):
    """The rows of the single ```text fence of a lines-mode answer."""
    start = text.index("```text\n") + len("```text\n")
    end = text.index("\n```", start)
    return text[start:end].split("\n")


@e2e_test(tool="search_in_code", kind="read")
def test_lines_mode_prints_path_line_text_without_file_headings():
    """lines mode renders ONE fence of "<path>:<line>: <matched line verbatim>" rows. The
    literal "A + B" is on line 2 of CommonModules/Calc/Module.bsl, written with a leading
    TAB ("\\tВозврат A + B;"): the row must carry that tab byte-for-byte. No "### " file
    heading and no "**Line" block may appear - those are the full-mode shape."""
    r = call("search_in_code",
             {"projectName": PROJECT, "query": "A + B", "outputMode": "lines"})
    assert_ok(r, "lines mode for 'A + B'")

    assert_contains(r.text, 'Search Lines for "A + B"', "lines mode must use the lines header")
    rows = _lines_block(r.text)
    assert "CommonModules/Calc/Module.bsl:2: \tВозврат A + B;" in rows, \
        "the Calc row must be path:line: + the verbatim source line:\n" + r.text
    assert_contains(r.text, "**Total:** %d matches in %d files" % (len(rows), len(rows)),
                    "every match of this one-per-file literal is a row (no limit reached)")
    assert_not_contains(r.text, "### ", "lines mode has no per-file headings")
    assert_not_contains(r.text, "**Line", "lines mode has no per-match blocks")
    assert_not_contains(r.text, "```bsl", "lines mode is one text fence, not bsl context blocks")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_lines_mode_ignores_context_lines():
    """contextLines is ignored in lines mode: "EndProcedure" is on lines 4 and 11 of the
    managed-app module, and with contextLines=5 the answer is still exactly those two rows -
    no neighbouring line (e.g. line 3's TODO comment) and the same text as contextLines=0."""
    wide = call("search_in_code",
                {"projectName": PROJECT, "query": "EndProcedure", "outputMode": "lines",
                 "contextLines": 5})
    assert_ok(wide, "lines mode with contextLines=5")
    assert _lines_block(wide.text) == [MANAGED_APP + ":4: EndProcedure",
                                      MANAGED_APP + ":11: EndProcedure"], wide.text
    assert_not_contains(wide.text, "TODO", "a context neighbour must not leak into lines mode")

    narrow = call("search_in_code",
                  {"projectName": PROJECT, "query": "EndProcedure", "outputMode": "lines",
                   "contextLines": 0})
    assert_ok(narrow, "lines mode with contextLines=0")
    assert narrow.text == wide.text, "contextLines must not change a lines-mode answer"
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_lines_mode_respects_limit_and_prints_the_showing_notice():
    """limit caps the rows as in full mode while the totals stay exact: limit=1 on the two
    EndProcedure lines prints one row and "(showing 1 of 2)"."""
    r = call("search_in_code",
             {"projectName": PROJECT, "query": "EndProcedure", "outputMode": "lines",
              "limit": 1})
    assert_ok(r, "lines mode capped at 1")
    assert_contains(r.text, "**Total:** 2 matches in 1 files (showing 1 of 2)",
                    "the truncation notice names the exact total")
    assert _lines_block(r.text) == [MANAGED_APP + ":4: EndProcedure"], r.text
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_valid_query_with_no_hits_renders_sentinel():
    """A well-formed query that genuinely matches nothing must render the explicit
    "No matches found." sentinel with zero totals — NOT an error. This documents the
    valid empty-result contract (the fixture has no such token)."""
    r = call("search_in_code",
             {"projectName": PROJECT, "query": "ZZ_NoSuchToken_e2e_QQ"})
    assert_ok(r, "valid query with zero hits is not an error")
    assert_contains(r.text, "**Total:** 0 matches in 0 files",
                    "a zero-hit search must report zero totals")
    assert_contains(r.text, "No matches found.",
                    "a zero-hit search must render the explicit sentinel")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (mandatory)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="search_in_code", kind="read")
def test_missing_project_name_errors_clearly():
    """Required projectName omitted -> JsonUtils.requireArguments -> "projectName is
    required". (query is supplied so projectName is the first failing required arg.)"""
    r = call("search_in_code", {"query": "Error"})
    e = assert_error(r, "missing projectName")
    # Names the missing param.
    # AUDIT: "projectName is required" names the param but offers no next step (no
    # mention of list_projects to discover a valid project) -> suggests=[]. Fix-card.
    assert_error_quality(e, names=["projectName"], suggests=[])
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_missing_query_errors_clearly():
    """Required query omitted -> JsonUtils.requireArguments -> "query is required".
    projectName is valid here, so the query guard is what fires."""
    r = call("search_in_code", {"projectName": PROJECT})
    e = assert_error(r, "missing query")
    # Names the missing param.
    # AUDIT: "query is required" names the param but gives no usage hint / example of
    # a valid query -> suggests=[]. Fix-card: make the required-arg guard actionable.
    assert_error_quality(e, names=["query"], suggests=[])
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args but the project does not exist -> ProjectContext.exists()
    false -> "Project not found: <name>"."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("search_in_code", {"projectName": bad, "query": "Error"})
    e = assert_error(r, "non-existent project")
    # Names the bad project value.
    # AUDIT: "Project not found: <name>" names the value but is not actionable — it
    # does not point at list_projects (the sibling that enumerates valid projects)
    # -> suggests=[]. Fix-card to add a next-step hint.
    assert_error_quality(e, names=[bad], suggests=[])
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_invalid_output_mode_enum_errors_actionably():
    """outputMode is an enum; an out-of-set value -> "outputMode must be 'full',
    'lines', 'count', or 'files'". This error IS actionable: it enumerates the valid values."""
    bad = "verbose_e2e"
    r = call("search_in_code",
             {"projectName": PROJECT, "query": "Error", "outputMode": bad})
    e = assert_error(r, "invalid outputMode enum")
    # The message lists the valid alternatives ('count' is one) -> genuinely actionable.
    # NOTE: the guard message does not echo the bad value itself, so names targets the
    # parameter name "outputMode" (which IS present) rather than the rejected literal.
    # AUDIT: the error does not echo the rejected value ('verbose_e2e'); a better
    # message would name what was passed. Flagged; the enumerated valid set keeps it
    # actionable so the assertion is not weakened.
    assert_error_quality(e, names=["outputMode"], suggests=["lines", "count", "files"])
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_unknown_metadata_type_errors_actionably():
    """metadataType resolves to a folder prefix; an unknown value -> resolveMetadataFolder
    returns null -> "Unknown metadataType '<t>'. Supported: ...". Actionable: it both
    names the bad value and lists the supported types."""
    bad = "bogusKind_e2e"
    r = call("search_in_code",
             {"projectName": PROJECT, "query": "Error", "metadataType": bad})
    e = assert_error(r, "unknown metadataType")
    # Names the bad value AND lists a valid alternative ('commonModules') -> actionable.
    assert_error_quality(e, names=[bad], suggests=["commonModules"])
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="search_in_code", kind="read")
def test_invalid_regex_pattern_errors_with_detail():
    """isRegex=true with a malformed pattern -> Pattern.compile throws
    PatternSyntaxException -> "Invalid regex pattern '<q>': <detail>". An unclosed
    group "(" is a classic compile error. The literal-search path would never error
    on this, so this case only fires because isRegex=true is honoured."""
    bad = "(unclosed_e2e"
    r = call("search_in_code",
             {"projectName": PROJECT, "query": bad, "isRegex": True})
    e = assert_error(r, "invalid regex pattern")
    # Names the offending pattern.
    # AUDIT: the message includes the underlying java.util.regex detail (e.g. "Unclosed
    # group") which IS a usable hint, but it does not suggest setting isRegex=false to
    # search literally -> suggests=[]. Fix-card: add that next-step hint.
    assert_error_quality(e, names=[bad], suggests=[])
    assert_no_diff("an invalid call must not touch the project on disk")
