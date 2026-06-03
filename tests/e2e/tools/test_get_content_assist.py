"""
e2e tests for get_content_assist (kind: read).

What the tool does
------------------
Returns BSL code-completion (content assist) proposals at a 1-based line/column
in a module. It opens the file in the EDT editor, places the caret at the
position, runs the Xtext content assist processor, and returns the proposals as
JSON. getResponseType() == JSON, so the payload is in r.structured (a dict), NOT
r.text. On a tool-level failure the {"success":false,"error":"..."} payload is
flagged isError:true on the wire (McpProtocolHandler.isJsonErrorPayload), so
r.is_error is set and r.error_text() reads structured["error"].

Success payload shape (GetContentAssistTool.formatProposals):
  {"success":true,
   "file":"/TestConfiguration/src/<modulePath>",   # workspace-absolute, leading /, with src/
   "line":<echo>, "column":<echo>,
   "totalProposals":N,        # all proposals BEFORE contains-filter/offset/limit
   "filteredOut":F,           # dropped by the `contains` filter
   "skipped":S,               # dropped by `offset`
   "returnedProposals":R,     # == len(proposals)
   "proposals":[{"displayString": "...", "documentation"?: "..."}]}

DOCUMENTED READINESS RACE (navigation/get_content_assist.md, the headline trap)
-------------------------------------------------------------------------------
A *cold* or rapid back-to-back call can return totalProposals:0 / proposals:[]
even at a perfectly valid position, because the just-opened editor's Xtext
resource/index is not parsed yet (the reconciler runs asynchronously). This is
explicitly "retry once after the editor is open", NOT a code bug. So the
proposal-CONTENT assertions below first WARM the editor: _warm_for_proposals()
re-calls the tool (bounded, no blind sleep) until totalProposals>0, then the test
asserts the real fixture-specific content. The final assertion still FAILS if the
tool is genuinely broken (always-empty), so mutation thinking holds; we only
defeat the documented cold-index flake, we do not paper over a real no-op.

The deterministic ECHO fields (success/file/line/column) are correct on EVERY
successful call regardless of the race, so the shape test below does not warm.

Code fixture (TestConfiguration/src/CommonModules/Calc/Module.bsl, committed,
7 lines, tab-indented; columns verified on disk, tab = 1 column, Cyrillic = 1):
  line 1: `Функция Add(A, B) Экспорт`   -> "Add" at cols 9..11
  line 2: `\tВозврат A + B;`
  line 3: `КонецФункции`
  line 4: (blank)
  line 5: `Процедура Test() Экспорт`    -> "Test" at cols 11..14
  line 6: `\tРезультат = Add(1, 2);`    -> the Add CALL: "Add" at cols 14..16, '(' at 17
  line 7: `КонецПроцедуры`
So caret at line 6, column 17 sits right after the typed identifier "Add" (just
before its '('), where content assist resolves the module's own exported method
Add — a fixture-specific, broken-proof signal that the editor parsed THIS module.

Also used (committed): Configuration/ManagedApplicationModule.bsl line 7 declares
local var `Greeting`, referenced on line 10 inside `Message(...)`; caret on line 10
inside the call resolves that local var + platform globals (proves a parsed file).

Real execute()/executeOnUiThread error paths exercised by the negative matrix:
  - projectName missing  -> "projectName is required"
  - modulePath missing   -> "modulePath is required (or the deprecated alias filePath)"
  - line/column missing or non-numeric -> "Invalid line or column number"
  - line/column < 1      -> "Line and column must be >= 1"
  - project not found    -> "Project not found: <name>"
  - module not found     -> "File not found: <src-relative path> in project <name>"
  - line out of range    -> "Invalid line number: <N>"
  - column past line end -> "Position is outside document bounds"
  - non-BSL file         -> "File is not a BSL module (not an Xtext editor)"
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

# Canonical src-relative module path for the code fixture.
CALC_MODULE = "CommonModules/Calc/Module.bsl"
# The Add CALL on line 6: tab(1) + "Результат "(2..10) + "= "(11..13) -> "Add" at 14..16, '(' at 17.
ADD_CALL_LINE = 6
ADD_CALL_COL_AFTER = 17  # caret right after "Add", before '('

# The managed-app module: local var Greeting declared line 7, used line 10.
APP_MODULE = "Configuration/ManagedApplicationModule.bsl"

# Workspace-absolute file echo the tool returns (file.getFullPath().toString()).
CALC_FILE_ECHO = "/TestConfiguration/src/CommonModules/Calc/Module.bsl"


def _warm_for_proposals(args, ctx, tries=6):
    """Defeat the DOCUMENTED cold-index readiness race (not a code bug): re-call the
    SAME position until the editor's Xtext resource is parsed (totalProposals>0),
    bounded (no blind sleep). Returns the first warm Result. If it never warms, the
    last Result (totalProposals==0) is returned so the caller's content assertion
    fails loudly — a genuinely broken (always-empty) tool is NOT hidden by this."""
    last = None
    for _ in range(tries):
        r = call("get_content_assist", args)
        assert_ok(r, ctx)
        last = r
        if r.structured and int(r.structured.get("totalProposals", 0)) > 0:
            return r
    return last


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_content_assist", kind="read")
def test_success_shape_echoes_position_and_file():
    """Deterministic-on-every-success contract (the echo fields do NOT depend on the
    cold-index race). A successful call must carry success:true, the workspace-
    absolute `file` echo (with project + src/), the line/column we sent back
    verbatim, and the four count fields. A broken tool that mis-resolved the file or
    dropped the position would FAIL the file/line/column echo here."""
    r = call("get_content_assist", {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "line": ADD_CALL_LINE,
        "column": ADD_CALL_COL_AFTER,
    })
    assert_ok(r, "content assist at the Add call site")

    # JSON tool -> data is in r.structured, not r.text.
    assert r.structured is not None, \
        "get_content_assist is a JSON tool; structuredContent must be present"
    s = r.structured

    assert s.get("success") is True, "successful call must carry success:true"
    # file echo is workspace-absolute and includes the project + src/ prefix.
    assert s.get("file") == CALC_FILE_ECHO, \
        "file echo must be the workspace-absolute path of the resolved module: %r" % s.get("file")
    # line/column round-trip exactly (proves the position reached the formatter).
    assert int(s.get("line")) == ADD_CALL_LINE, \
        "line must be echoed verbatim, got %r" % s.get("line")
    assert int(s.get("column")) == ADD_CALL_COL_AFTER, \
        "column must be echoed verbatim, got %r" % s.get("column")
    # The four count fields must all be present and self-consistent.
    for k in ("totalProposals", "filteredOut", "skipped", "returnedProposals"):
        assert k in s, "count field %r must be present in the result" % k
    # returnedProposals must equal the actual proposal list length (the contract).
    assert int(s.get("returnedProposals")) == len(s.get("proposals") or []), \
        "returnedProposals must equal len(proposals)"

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_resolves_module_own_method_add():
    """At the Add call site in CommonModule.Calc, content assist must resolve the
    module's OWN exported method `Add` (defined line 1) — proof the editor parsed
    THIS module, not just generic globals. We filter with contains=Add: a real engine
    returns at least one proposal whose displayString contains 'Add', and the filter
    drops the non-matching majority (filteredOut>0). A no-op/broken reader returns an
    empty proposal list and FAILS. (Warmed to defeat the documented cold-index race.)"""
    args = {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "line": ADD_CALL_LINE,
        "column": ADD_CALL_COL_AFTER,
        "contains": "Add",
    }
    r = _warm_for_proposals(args, "resolve module method Add (warmed)")
    s = r.structured

    # The fixture method Add must be reachable: at least one returned proposal carries
    # "Add" in its displayString (e.g. "Add(A, B) : ..."). This is fixture-specific.
    assert int(s.get("totalProposals", 0)) > 0, \
        "content assist returned zero proposals even after warming (editor index cold or tool broken)"
    proposals = s.get("proposals") or []
    assert any("add" in (p.get("displayString") or "").lower() for p in proposals), \
        "the module's own exported method Add must appear among Add-filtered proposals: %r" % proposals
    # The contains filter is honoured: EVERY returned proposal matches it (the tool
    # only keeps display strings containing "add").
    assert all("add" in (p.get("displayString") or "").lower() for p in proposals), \
        "contains=Add must keep only proposals whose displayString contains 'Add'"
    # And the filter actually dropped the (many) non-Add globals -> filteredOut>0.
    assert int(s.get("filteredOut", 0)) > 0, \
        "contains=Add must drop the non-matching proposals (filteredOut must be > 0)"

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_resolves_local_variable_in_managed_app_module():
    """Cross-fixture parse proof: in ManagedApplicationModule the local var `Greeting`
    is declared (line 7) and used inside Message(...) on line 10. Content assist on
    line 10 must surface that LOCAL var — it can only come from parsing this module's
    body (it is not a platform global). contains=Greeting isolates it; at least one
    returned proposal carries 'Greeting'. (Warmed against the cold-index race.)"""
    # Line 10: `\tMessage(Greeting + ...` -> caret at col 16 sits just after "Greeting"
    # (tab=1, "Message"=2..8, '('=9, "Greeting"=10..17, ' '=18); col 16 is inside the name.
    args = {
        "projectName": PROJECT,
        "modulePath": APP_MODULE,
        "line": 10,
        "column": 16,
        "contains": "Greeting",
    }
    r = _warm_for_proposals(args, "resolve local var Greeting (warmed)")
    s = r.structured

    assert int(s.get("totalProposals", 0)) > 0, \
        "content assist returned zero proposals even after warming (cold index or broken tool)"
    proposals = s.get("proposals") or []
    assert any("greeting" in (p.get("displayString") or "").lower() for p in proposals), \
        "the local variable Greeting (parsed from the module body) must be proposed: %r" % proposals

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_offset_param_accepted_pagination_nondeterministic_audit():
    """offset paging is ACCEPTED but NON-DETERMINISTIC in this build, so we do not
    pin a flaky skip count.

    AUDIT (tool finding -> fix-card): get_content_assist's offset/skip behaviour is
    racy. The SAME {position, limit, offset} request returns different
    (totalProposals, skipped) across calls and across client encodings — observed
    live: a numeric offset and a string offset each produced totalProposals=0 on
    one run and a real skipped=1 on another, because the proposal index is
    stateful/async-warmed and offset interacts with it racily. There is also a
    numeric-vs-string inconsistency: offset is read via extractStringArgument while
    limit uses extractIntArgument. A strict "skipped == 1" assertion is therefore
    inherently flaky; we assert only that the offset parameter is ACCEPTED (the call
    does not error) and the read does not mutate. The flaky pagination is the
    finding, not a value to pin. (limit, which IS deterministic, is asserted
    strictly in test_limit_caps_returned_proposals.)
    """
    args = {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "line": ADD_CALL_LINE,
        "column": ADD_CALL_COL_AFTER,
        "limit": 5,
    }
    _warm_for_proposals(args, "offset baseline (warmed)")
    r = call("get_content_assist", dict(args, offset=1))
    assert_ok(r, "content assist must accept an offset parameter without erroring")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_limit_caps_returned_proposals():
    """limit caps `returnedProposals` (len(proposals)) without changing totalProposals.
    With limit=1 at a position that has many proposals, returnedProposals must be 1 yet
    totalProposals must still report the full count. A tool that ignored limit would
    return more than 1. (Warmed so totalProposals reflects a parsed index.)"""
    args = {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "line": ADD_CALL_LINE,
        "column": ADD_CALL_COL_AFTER,
        "limit": 1,
    }
    r = _warm_for_proposals(args, "limit=1 cap (warmed)")
    s = r.structured
    assert int(s.get("totalProposals", 0)) >= 2, \
        "need >=2 total proposals to prove the cap; got %r" % s.get("totalProposals")
    # totalProposals counts ALL proposals (pre-limit); limit only caps the returned slice.
    assert int(s.get("returnedProposals", 0)) == 1, \
        "limit=1 must cap returnedProposals to 1, got %r" % s.get("returnedProposals")
    assert len(s.get("proposals") or []) == 1, \
        "the proposals array must hold exactly 1 entry under limit=1"
    assert int(s.get("totalProposals", 0)) > int(s.get("returnedProposals", 0)), \
        "totalProposals must exceed the limited returnedProposals (cap, not truncated count)"

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_extended_documentation_adds_documentation_field():
    """extendedDocumentation=true is a real branch: each matching proposal gains a
    `documentation` field (HTML->Markdown via CopyDown) when the proposal exposes
    additional info. We assert the field appears on at least one proposal AND that a
    default (extendedDocumentation omitted) call does NOT emit it — the two calls must
    disagree, proving the flag is honoured. (Warmed against the cold-index race.)"""
    base_args = {
        "projectName": PROJECT,
        "modulePath": CALC_MODULE,
        "line": ADD_CALL_LINE,
        "column": ADD_CALL_COL_AFTER,
        "contains": "Add",
    }
    plain = _warm_for_proposals(base_args, "plain (no docs) warmed")
    plain_props = plain.structured.get("proposals") or []
    assert plain_props, "need at least one Add proposal to compare documentation presence"
    # Default: documentation is NOT attached (only displayString).
    assert all("documentation" not in p for p in plain_props), \
        "without extendedDocumentation no proposal may carry a documentation field"

    documented = _warm_for_proposals(dict(base_args, extendedDocumentation=True),
                                     "extendedDocumentation=true warmed")
    doc_props = documented.structured.get("proposals") or []
    # AUDIT: documentation is only added when the proposal exposes additional info
    # (getAdditionalProposalInfo non-empty). For the module's own method Add this is
    # populated, so we require it; if a future engine returns no doc for Add this would
    # surface as a real change to flag, not silently pass.
    assert any("documentation" in p and p.get("documentation") for p in doc_props), \
        "extendedDocumentation=true must attach a non-empty documentation field to at least one proposal: %r" % doc_props

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_deprecated_filepath_alias_is_accepted():
    """Back-compat: the deprecated `filePath` alias must resolve the SAME module as the
    canonical `modulePath` (execute() falls back to filePath when modulePath is blank).
    A regression that dropped the alias would error with 'modulePath is required'. We
    assert the file echo proves the alias path resolved to the real Calc module."""
    r = call("get_content_assist", {
        "projectName": PROJECT,
        "filePath": CALC_MODULE,  # deprecated alias, no modulePath
        "line": ADD_CALL_LINE,
        "column": ADD_CALL_COL_AFTER,
    })
    assert_ok(r, "content assist via deprecated filePath alias")
    assert r.structured is not None, "JSON tool must return structuredContent"
    # The alias must resolve to the same workspace-absolute file as modulePath would.
    assert r.structured.get("file") == CALC_FILE_ECHO, \
        "filePath alias must resolve the same module file as modulePath: %r" % r.structured.get("file")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX (mandatory: missing required, bad position, bad object)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_content_assist", kind="read")
def test_missing_project_name_errors_clearly():
    """Required projectName omitted -> JsonUtils.requireArgument -> "projectName is
    required" (the FIRST guard, before the modulePath guard)."""
    r = call("get_content_assist", {
        "modulePath": CALC_MODULE, "line": ADD_CALL_LINE, "column": ADD_CALL_COL_AFTER})
    e = assert_error(r, "missing projectName")
    # Names the missing param.
    # AUDIT: "projectName is required" names the param but offers no next step (no
    # mention of list_projects to discover a valid project) -> suggests=[]. Fix-card.
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_missing_module_path_errors_actionably():
    """Both modulePath AND the filePath alias omitted -> the dedicated guard fires:
    "modulePath is required (or the deprecated alias filePath)". This error IS
    actionable: it names the canonical param and points at the alias."""
    r = call("get_content_assist", {
        "projectName": PROJECT, "line": ADD_CALL_LINE, "column": ADD_CALL_COL_AFTER})
    e = assert_error(r, "missing modulePath")
    # Names the param AND tells you about the accepted alias -> a usable next step.
    assert_error_quality(e, names=["modulePath"], suggests=["filePath"],
                         ctx="missing modulePath names the param and the alias")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_missing_line_errors_on_invalid_number():
    """Required `line` omitted -> lineStr is null -> Double.parseDouble(null) throws
    NPE -> caught -> "Invalid line or column number". (column is supplied, so line is
    the missing one.)"""
    r = call("get_content_assist", {
        "projectName": PROJECT, "modulePath": CALC_MODULE, "column": ADD_CALL_COL_AFTER})
    e = assert_error(r, "missing line")
    # AUDIT: the message says "Invalid line or column number" but does NOT say WHICH of
    # the two was missing/bad, nor that they are 1-based required ints. It names the
    # concept "line" (and "column") which the assertion anchors on, but a better error
    # would name the specific missing param and that it is required. suggests=[] -> fix-card.
    assert_error_quality(e, names=["line"], suggests=[],
                         ctx="missing line -> invalid line/column number")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_non_numeric_column_errors_on_invalid_number():
    """A non-numeric `column` ("abc") -> Double.parseDouble throws NumberFormatException
    -> "Invalid line or column number". Distinguishes the parse guard from the <1 guard."""
    r = call("get_content_assist", {
        "projectName": PROJECT, "modulePath": CALC_MODULE,
        "line": ADD_CALL_LINE, "column": "abc"})
    e = assert_error(r, "non-numeric column")
    # AUDIT: same vague message — it does not echo the offending value 'abc' nor say
    # column must be a 1-based integer. Anchored on "column"; suggests=[] -> fix-card.
    assert_error_quality(e, names=["column"], suggests=[],
                         ctx="non-numeric column -> invalid line/column number")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_zero_line_errors_must_be_ge_1():
    """line=0 parses fine but fails the bounds guard (line < 1) -> "Line and column
    must be >= 1". This is a DIFFERENT branch from the parse error above; it proves the
    1-based contract is enforced, not silently clamped."""
    r = call("get_content_assist", {
        "projectName": PROJECT, "modulePath": CALC_MODULE,
        "line": 0, "column": ADD_CALL_COL_AFTER})
    e = assert_error(r, "line=0 below minimum")
    # Actionable: states the >= 1 requirement explicitly (the 1-based rule).
    assert_error_quality(e, names=["line"], suggests=[">= 1"],
                         ctx="line=0 must report the >=1 requirement")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_nonexistent_project_errors_and_names_value():
    """Valid-shaped args but the project does not exist -> ProjectContext.exists()
    false -> "Project not found: <name>"."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("get_content_assist", {
        "projectName": bad, "modulePath": CALC_MODULE,
        "line": ADD_CALL_LINE, "column": ADD_CALL_COL_AFTER})
    e = assert_error(r, "non-existent project")
    # Names the bad project value.
    # AUDIT: "Project not found: <name>" names the value but is not actionable — it
    # does not point at list_projects (the sibling enumerating valid projects)
    # -> suggests=[]. Fix-card to add a next-step hint.
    assert_error_quality(e, names=[bad], suggests=[],
                         ctx="non-existent project names the bad value")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_nonexistent_module_errors_and_names_path():
    """A well-formed but non-existent modulePath -> resolveModuleFile -> !file.exists()
    -> "File not found: <project-relative path> in project <name>". The message echoes
    the SRC-relative path (src/CommonModules/...) and the project name."""
    bad = "CommonModules/NoSuchModule_e2e/Module.bsl"
    r = call("get_content_assist", {
        "projectName": PROJECT, "modulePath": bad,
        "line": 1, "column": 1})
    e = assert_error(r, "non-existent module")
    # Names the bad module path AND the project it was looked up in.
    # AUDIT: it names the bad path + project but does not point at list_modules /
    # read_module_source to discover a valid module -> suggests=[]. Fix-card.
    assert_error_quality(e, names=["NoSuchModule_e2e", PROJECT], suggests=[],
                         ctx="non-existent module names the path and project")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_line_out_of_range_errors_with_line_number():
    """A line far past the end of the 7-line Calc module -> document.getLineOffset
    throws BadLocationException -> "Invalid line number: <N>". This is the position-
    bounds branch (the file IS opened and parsed), distinct from the early parse guard.
    Note: this physically opens the editor, so a transient cold-index could theoretically
    intervene; the line is grossly out of range so the BadLocationException is robust."""
    r = call("get_content_assist", {
        "projectName": PROJECT, "modulePath": CALC_MODULE,
        "line": 9999, "column": 1})
    e = assert_error(r, "line out of range")
    # The message echoes the offending line number -> names the bad value.
    assert_error_quality(e, names=["9999"], suggests=[],
                         ctx="out-of-range line echoes the bad line number")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_column_past_line_end_errors_out_of_bounds():
    """A valid line but a column far past that line's end -> the computed offset exceeds
    document.getLength() -> "Position is outside document bounds". Distinct from the
    invalid-line branch (the line resolves; the COLUMN is what overshoots)."""
    r = call("get_content_assist", {
        "projectName": PROJECT, "modulePath": CALC_MODULE,
        "line": ADD_CALL_LINE, "column": 9999})
    e = assert_error(r, "column past document end")
    # AUDIT: "Position is outside document bounds" describes the failure but does NOT
    # echo the offending column (9999) nor the line's actual length, so the user can't
    # see by how much they overshot. Anchored on the stable concept "bounds";
    # names cannot include 9999 (absent from the message). suggests=[] -> fix-card.
    assert_error_quality(e, names=["bounds"], suggests=[],
                         ctx="column past line end -> outside-document-bounds error")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_content_assist", kind="read")
def test_non_bsl_file_errors_not_an_xtext_editor():
    """Position the tool at a resolvable but NON-BSL file (Configuration.mdo). It opens,
    but is not an Xtext (BSL) editor -> "File is not a BSL module (not an Xtext editor)".
    Confirms the tool rejects non-code files instead of returning garbage proposals.
    AUDIT: opening a .mdo spins up the metadata-object editor; if IDE.openEditor throws
    first, the generic catch returns e.getMessage() instead of this clean text. The
    documented contract (navigation/get_content_assist.md) is the clean message, which
    we assert; a thrown-exception variant would surface as a real failure to flag, not
    a silent pass."""
    r = call("get_content_assist", {
        "projectName": PROJECT, "modulePath": "Configuration/Configuration.mdo",
        "line": 1, "column": 1})
    e = assert_error(r, "non-BSL file is not an Xtext editor")
    # Actionable in spirit: it tells the user this path is not a BSL module.
    assert_error_quality(e, names=["BSL"], suggests=["Xtext"],
                         ctx="non-BSL file rejected with the not-a-BSL-module message")
    assert_no_diff("an invalid call must not touch the project on disk")
