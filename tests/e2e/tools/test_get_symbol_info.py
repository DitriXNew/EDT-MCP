"""
e2e tests for get_symbol_info (kind: read).

What the tool does
------------------
Returns hover/type info for the symbol at a 1-based line/column in a BSL module:
the same content EDT shows on mouse-hover (inferred types, signatures, parameter
docs). Source: GetSymbolInfoTool.java -> SymbolInfoService. ResponseType is
MARKDOWN, so the payload lands in r.text (r.structured is None). The result is
wrapped in YAML frontmatter (projectName, module, line, column).

Two resolution paths, ONE observable contract for our assertions
----------------------------------------------------------------
SymbolInfoService resolves in layers:
  1. UI-thread editor hover (IDE.openEditor + reflection on the configured
     ITextHover) -> HTML converted to Markdown via CopyDown.
  2. EObject/EMF analysis -> a deterministic Markdown table built by
     buildEObjectInfo: a "| **Symbol** | <name> |" row plus "| **Kind** | ... |",
     "| **Signature** | ... |", "| **Export** | Yes/No |", "| **Lines** | a - b |".
  3. Headless EMF fallback (getSymbolInfoViaEmf) when no workbench window exists.

The hover *text* is environment-dependent (UI locale, which hover is configured,
and a known annotation-hover quirk on some DECLARATION positions). What is stable
across ALL paths is the resolved SYMBOL NAME: for our fixture that distinctive
token is "Add". So the happy paths point at the `Add` call site and the parameter
read inside the body (the clean-hover case per the testing reference) and assert
that "Add" / the parameter resolve. A broken tool (no-op, wrong offset, dropped
symbol) would not surface "Add" and would FAIL. We do NOT assert the exact hover
prose (that would over-fit one path / one locale).

Fixture truth (TestConfiguration/src/CommonModules/Calc/Module.bsl, committed):
  L1  Функция Add(A, B) Экспорт   <- "Add" name at col 9; param "A" at col 13
  L2  \tВозврат A + B;            <- col 1 = TAB (whitespace); "A" read at col 10
  L3  КонецФункции
  L4  (blank, length 0)
  L5  Процедура Test() Экспорт
  L6  \tРезультат = Add(1, 2);    <- "Add" CALL at col 14 (TAB=1 + "Результат = "=11)
  L7  КонецПроцедуры
Columns verified by reading the raw bytes: a TAB is ONE column, each Cyrillic
letter is ONE column. Document length is 120 chars (used by the out-of-range
column case below).

Real execute()/getSymbolInfo() error paths exercised in the negative matrix:
  - missing projectName            -> "projectName is required" (requireArgument)
  - missing modulePath & filePath  -> "modulePath is required (or the deprecated alias filePath)"
  - line/column not a number       -> "Invalid line or column number"
  - line < 1 or column < 1         -> "Line and column must be >= 1"
  - project not found              -> "Project not found: <name>"
  - module file not found          -> "File not found: src/<path> in project <name>"
  - out-of-range line              -> "Invalid line number: <line>" (BadLocationException)
  - out-of-range column            -> "Position is outside document bounds"
                                      (editor path) — see AUDIT in that test
  - whitespace / blank position    -> SUCCESS with literal "No symbol at this position."
                                      (hasTokenAtPosition pre-check; NOT is_error)
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

# The Calc fixture module (canonical modulePath, src/-relative).
CALC_MODULE = "CommonModules/Calc/Module.bsl"


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_symbol_info", kind="read")
def test_resolves_add_call_site_and_does_not_mutate():
    """Point at the `Add` CALL inside Test (line 6, col 14 = the 'A' of 'Add(1, 2)').

    This is THE reference position: the static feature access for the called
    function. Whether the editor hover path or the EMF table path runs, the
    resolved symbol is "Add" — so "Add" MUST appear in the rendered output. The
    frontmatter must also echo the exact module/line/column we asked for, proving
    the tool resolved at the requested position rather than echoing a constant.

    Mutation thinking: a no-op tool, a tool that mis-computed the offset (and so
    landed on whitespace / a different token), or one that dropped the symbol name
    would NOT contain "Add" at line 6 / col 14 and would FAIL here.
    """
    r = call("get_symbol_info", {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "line": 6,
        "column": 14,
    })
    assert_ok(r, "get_symbol_info on the Add call site (L6 C14)")

    # MARKDOWN tool -> data is in r.text, never r.structured.
    assert r.structured is None, \
        "get_symbol_info is a MARKDOWN tool; structuredContent must be None"

    # Frontmatter echoes the exact request (proves position-specific resolution).
    assert_contains(r.text, "module: " + CALC_MODULE,
                    "frontmatter must echo the module that was resolved")
    assert_contains(r.text, "line: 6", "frontmatter must echo the requested line")
    assert_contains(r.text, "column: 14", "frontmatter must echo the requested column")

    # The resolved symbol at the call site is the function "Add" — stable across
    # both the hover and the EMF-table path.
    assert_contains(r.text, "Add",
                    "the Add call site must resolve to the 'Add' symbol")

    # A real symbol resolved -> NOT the empty-position sentinel and NOT the
    # nothing-found sentinel (distinguishes "resolved Add" from "found nothing").
    assert_not_contains(r.text, "No symbol at this position.",
                        "the Add call is a real token, not an empty position")
    assert_not_contains(r.text, "No symbol found at this position.",
                        "the Add call must resolve, not fall through to 'nothing found'")

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_symbol_info", kind="read")
def test_resolves_parameter_read_inside_body():
    """Clean-hover case (per the testing reference): a variable/parameter READ
    inside a method body. Line 2 is `Возврат A + B;`; col 10 is the 'A' read.

    The parameter "A" resolves here (StaticFeatureAccess -> FormalParam A of Add).
    Asserting the distinctive containing context proves a real resolution rather
    than a constant: we require the output to NOT be an empty/nothing sentinel and
    to carry the symbol. (We deliberately avoid asserting the bare token 'A' as the
    sole signal — too weak — and instead anchor on the sentinels + frontmatter.)

    Mutation thinking: a tool that resolved at the wrong offset would hit the space
    at col 9 or col 11 and return "No symbol at this position." — caught below.
    """
    r = call("get_symbol_info", {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "line": 2,
        "column": 10,
    })
    assert_ok(r, "get_symbol_info on the parameter read (L2 C10)")
    assert_contains(r.text, "line: 2", "frontmatter must echo the requested line")
    assert_contains(r.text, "column: 10", "frontmatter must echo the requested column")
    # A real token resolved at col 10 -> not the empty-position pre-check sentinel.
    assert_not_contains(r.text, "No symbol at this position.",
                        "col 10 is the 'A' read, a real token, not whitespace")
    assert_not_contains(r.text, "No symbol found at this position.",
                        "the parameter read must resolve to a symbol")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_symbol_info", kind="read")
def test_filepath_alias_resolves_same_as_modulepath():
    """Back-compat: the deprecated `filePath` alias must work like `modulePath`.
    execute() reads modulePath first, then falls back to filePath. Addressing the
    Add call site via filePath must resolve the SAME 'Add' symbol.

    Mutation thinking: if the alias were silently ignored, filePath-only would hit
    the 'modulePath is required' error and assert_ok would FAIL.
    """
    r = call("get_symbol_info", {
        "projectName": PROJECT,
        "filePath": CALC_MODULE,
        "line": 6,
        "column": 14,
    })
    assert_ok(r, "get_symbol_info via deprecated filePath alias")
    assert_contains(r.text, "Add",
                    "the filePath alias must resolve the same 'Add' symbol")
    assert_not_contains(r.text, "No symbol at this position.",
                        "alias addressing must resolve a real token")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_symbol_info", kind="read")
def test_double_formatted_line_column_accepted():
    """Leniency contract: line/column are parsed via Double.parseDouble, so the
    JSON numbers serialize-and-resolve identically whether the wire value is an
    integer or a float. Passing 6.0 / 14.0 must resolve the same 'Add' call site.

    Mutation thinking: a tool that did a strict Integer.parseInt would throw on
    "6.0" -> "Invalid line or column number", and assert_ok would FAIL.
    """
    r = call("get_symbol_info", {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "line": 6.0,
        "column": 14.0,
    })
    assert_ok(r, "get_symbol_info with float-formatted line/column")
    assert_contains(r.text, "Add",
                    "float-formatted position must resolve the same 'Add' symbol")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# DOCUMENTED SUCCESS-WITH-EMPTY (not an error): the empty-position pre-check
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_symbol_info", kind="read")
def test_whitespace_position_returns_no_symbol_success():
    """Line 2, col 1 is the leading TAB. hasTokenAtPosition() returns false for
    whitespace, so the tool returns the literal "No symbol at this position." as a
    SUCCESS payload (NOT is_error). This is the REAL contract — assert it exactly,
    do not fabricate an error.

    Mutation thinking: a tool that dropped the empty-position pre-check would
    instead return contextual hover info for the surrounding line (confusing an
    agent), so the absence of the sentinel would FAIL this test.
    """
    r = call("get_symbol_info", {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "line": 2,
        "column": 1,
    })
    assert_ok(r, "whitespace position is a graceful success, not an error")
    assert_contains(r.text, "No symbol at this position.",
                    "a TAB position must yield the empty-position sentinel")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_symbol_info", kind="read")
def test_blank_line_returns_no_symbol_success():
    """Line 4 is blank (length 0). Col 1 there is the line terminator / empty
    position -> hasTokenAtPosition() false -> "No symbol at this position."
    (success). A valid in-range line with no token, distinct from out-of-range.

    Mutation thinking: a tool that treated a blank line as out-of-bounds (error)
    instead of an empty position (success) would fail assert_ok; one that returned
    stray symbol info would fail the sentinel assertion.
    """
    r = call("get_symbol_info", {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "line": 4,
        "column": 1,
    })
    assert_ok(r, "blank line is a graceful success, not an error")
    assert_contains(r.text, "No symbol at this position.",
                    "a blank line must yield the empty-position sentinel")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (mandatory): missing params, bad values, out-of-range
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_symbol_info", kind="read")
def test_missing_projectname_errors_clearly():
    """Required projectName omitted -> requireArgument returns "projectName is
    required". Names the param.

    AUDIT: the message names the param but offers NO next step (no list_projects
    hint to discover a valid project). suggests=[] is intentional -> fix-card.
    """
    r = call("get_symbol_info", {
        "modulePath": CALC_MODULE, "line": 6, "column": 14,
    })
    e = assert_error(r, "missing projectName")
    assert_error_quality(e, names=["projectName is required"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_symbol_info", kind="read")
def test_missing_modulepath_errors_and_names_alias():
    """Neither modulePath nor the filePath alias supplied -> the tool returns
    "modulePath is required (or the deprecated alias filePath)". This IS actionable:
    it names the canonical param AND tells you the accepted alias.
    """
    r = call("get_symbol_info", {
        "projectName": PROJECT, "line": 6, "column": 14,
    })
    e = assert_error(r, "missing modulePath and filePath")
    assert_error_quality(e, names=["modulePath is required"], suggests=["filePath"],
                         ctx="missing path names the param and the accepted alias")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_symbol_info", kind="read")
def test_nonnumeric_line_errors():
    """line is present but not a number -> Double.parseDouble throws ->
    "Invalid line or column number". A bad-type guard distinct from out-of-range.
    """
    r = call("get_symbol_info", {
        "projectName": PROJECT, "modulePath": CALC_MODULE,
        "line": "notanumber", "column": 14,
    })
    e = assert_error(r, "non-numeric line")
    # The message names both offending params (line, column). It does not echo the
    # bad literal, but "Invalid line or column number" is a clear, non-bare,
    # actionable-enough diagnostic of WHAT was wrong.
    # AUDIT: the message does not echo the offending value ("notanumber") nor say
    # values must be 1-based positive integers; suggests=[] -> fix-card.
    assert_error_quality(e, names=["Invalid line or column number"], suggests=[],
                         ctx="non-numeric line is diagnosed clearly")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_symbol_info", kind="read")
def test_zero_line_below_one_errors():
    """line < 1 -> the explicit bounds guard "Line and column must be >= 1".
    Distinct from a non-numeric value and from an out-of-range (too-large) line.
    """
    r = call("get_symbol_info", {
        "projectName": PROJECT, "modulePath": CALC_MODULE,
        "line": 0, "column": 14,
    })
    e = assert_error(r, "line below 1")
    assert_error_quality(e, names=["Line and column must be >= 1"], suggests=[],
                         ctx="line<1 hits the explicit >=1 guard")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_symbol_info", kind="read")
def test_zero_column_below_one_errors():
    """column < 1 -> the SAME bounds guard ("Line and column must be >= 1"), via
    the other branch of `line < 1 || column < 1`. Covers the column side too.
    """
    r = call("get_symbol_info", {
        "projectName": PROJECT, "modulePath": CALC_MODULE,
        "line": 6, "column": 0,
    })
    e = assert_error(r, "column below 1")
    assert_error_quality(e, names=["Line and column must be >= 1"], suggests=[],
                         ctx="column<1 hits the explicit >=1 guard")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_symbol_info", kind="read")
def test_out_of_range_line_errors_and_names_value():
    """A line far beyond the 7-line module -> document.getLineOffset throws
    BadLocationException -> "Invalid line number: <line>". This error DOES echo the
    bad value, so we can assert it is named.
    """
    r = call("get_symbol_info", {
        "projectName": PROJECT, "modulePath": CALC_MODULE,
        "line": 9999, "column": 1,
    })
    e = assert_error(r, "out-of-range line")
    # Names the offending line number explicitly.
    assert_error_quality(e, names=["9999"], suggests=[],
                         ctx="out-of-range line echoes the bad line number")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_symbol_info", kind="read")
def test_out_of_range_column_errors():
    """A column far past the end of a valid line: offset = lineOffset + column - 1
    exceeds the 120-char document length -> "Position is outside document bounds".
    Confirms a too-large column on an in-range line is rejected (not silently
    clamped or treated as an empty position).

    AUDIT: the bounds message does NOT echo the offending column number (so we
    cannot assert names=[the value]); it is a clear, non-bare, non-stacktrace
    error but a fix-card would have it name the column + the document length so the
    caller can correct it. We still keep a meaningful assertion: a quality error
    that mentions the document bounds.
    """
    r = call("get_symbol_info", {
        "projectName": PROJECT, "modulePath": CALC_MODULE,
        "line": 7, "column": 9999,
    })
    e = assert_error(r, "out-of-range column")
    # No bad value echoed -> assert quality + an actionable token about WHY it
    # failed ("document" bounds), not just the fact of failure.
    assert_error_quality(e, names=[], suggests=["document"],
                         ctx="out-of-range column reports a document-bounds error")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_symbol_info", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args but the project does not exist -> ProjectContext.exists()
    is false -> ProjectContext.notFoundMessage: "Project not found: <name>. Use
    list_projects to see available projects." Names the bad project AND points at
    the sibling discovery tool, so the error is actionable.
    """
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_symbol_info", {
        "projectName": bad, "modulePath": CALC_MODULE, "line": 6, "column": 14,
    })
    e = assert_error(r, "non-existent project")
    assert_error_quality(e, names=[bad, "Project not found"], suggests=["list_projects"],
                         ctx="non-existent project names the bad value and points at list_projects")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_symbol_info", kind="read")
def test_nonexistent_module_errors_and_names_path():
    """A well-formed but non-existent modulePath -> file.exists() is false ->
    "File not found: src/<path> in project <name>". The bad path and the project
    are both named; '/' is NOT Gson-HTML-escaped so the path survives intact.

    Mutation thinking: a tool that silently returned empty/nothing for a missing
    file (instead of erroring) would fail assert_error; one that omitted the bad
    path would fail assert_error_quality.

    AUDIT: names the bad path + project but points to no sibling discovery tool
    (e.g. list_modules) to find a valid module path. suggests=[] -> fix-card.
    """
    bad = "CommonModules/NoSuchModule_e2e/Module.bsl"
    r = call("get_symbol_info", {
        "projectName": PROJECT, "modulePath": bad, "line": 1, "column": 1,
    })
    e = assert_error(r, "non-existent module file")
    assert_error_quality(e, names=[bad, "File not found", PROJECT], suggests=[],
                         ctx="non-existent module names the bad path and project")
    assert_no_diff("an invalid call must not touch the project on disk")
