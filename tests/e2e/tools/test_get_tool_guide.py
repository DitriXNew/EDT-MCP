"""
e2e tests for get_tool_guide (kind: read).

get_tool_guide renders the on-demand Markdown how-to for ANOTHER registered tool:
a title (# <name>), the tool's description, a "## Parameters" table parsed from the
target tool's input schema, and an optional "## Guide" section. Response type is
MARKDOWN, so the payload lands in Result.text.

A read tool must never mutate the project on disk: every test below ends with
assert_no_diff() as the non-destructive guardrail (SKILL §4).

Happy path uses a stable target tool that is always registered:
  - read_module_source -> guide must contain "Parameters" and the "modulePath" param.

Negative matrix targets the tool's REAL execute() errors (read from the Java):
  - missing toolName  -> "toolName is required"
  - unknown toolName  -> "Unknown tool: <name>. Call tools/list ..."
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality,
    assert_contains, assert_not_contains, assert_no_diff, e2e_test, _post, _fail,
)

# A target tool that is always registered, with a known required param.
TARGET_TOOL = "read_module_source"


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_tool_guide", kind="read")
def test_renders_guide_for_known_tool():
    """The guide for a real tool carries its title, its Parameters table and a known param.

    Mutation thinking: if the renderer dropped the schema table or named the wrong
    tool, the "Parameters" header or the "modulePath" param token would be absent
    and this would FAIL.
    """
    r = call("get_tool_guide", {"toolName": TARGET_TOOL})
    assert_ok(r, "guide for a known tool")
    # Title is "# <toolName>".
    assert_contains(r.text, TARGET_TOOL, "guide must name the documented tool")
    # The parsed schema table.
    assert_contains(r.text, "Parameters", "guide must contain the Parameters section")
    # read_module_source declares a modulePath parameter -> it must appear in the table.
    assert_contains(r.text, "modulePath", "guide must list the target tool's modulePath param")
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_tool_guide", kind="read")
def test_renders_guide_for_itself():
    """get_tool_guide can document itself: title + its own required toolName param.

    Mutation thinking: a renderer that mis-read its own schema (or the registry
    lookup that returned the wrong tool) would not surface the toolName param.
    """
    r = call("get_tool_guide", {"toolName": "get_tool_guide"})
    assert_ok(r, "guide for get_tool_guide itself")
    assert_contains(r.text, "get_tool_guide", "self-guide must name the tool")
    assert_contains(r.text, "toolName", "self-guide must list its required toolName param")
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix (missing required param + unknown tool)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_tool_guide", kind="read")
def test_missing_toolname_errors_clearly():
    """Missing required toolName -> clear, value-naming error; no disk change."""
    r = call("get_tool_guide", {})
    err = assert_error(r, "missing required toolName")
    # The tool's real message is exactly "toolName is required".
    # AUDIT: names the param but offers no next step, so suggests=[] is intentional.
    assert_error_quality(err, names=["toolName is required"], suggests=[],
                         ctx="missing toolName names the param")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_tool_guide", kind="read")
def test_unknown_tool_errors_with_pointer_to_tools_list():
    """An unknown toolName -> error names the bad value AND points back to tools/list.

    Mutation thinking: a tool that returned an empty / generic guide for a bad name
    (instead of an error) would fail assert_error; one whose message omitted the
    bad name would fail assert_error_quality.
    """
    bad = "no_such_tool_zzz"
    r = call("get_tool_guide", {"toolName": bad})
    err = assert_error(r, "unknown tool name")
    # Real message: "Unknown tool: <name>. Call tools/list to see available tool names."
    # "Unknown tool" + "tools/list" are delimiter-free actionable anchors.
    assert_error_quality(err, names=[bad, "Unknown tool"],
                         suggests=["tools/list"],
                         ctx="unknown tool names the bad value and points to tools/list")
    assert_no_diff("an invalid call must not touch the project on disk")


@e2e_test(tool="get_tool_guide", kind="read")
def test_summary_lists_sections_without_their_bodies():
    """The contents list retains parameters and makes a later section discoverable."""
    r = call("get_tool_guide", {"toolName": TARGET_TOOL, "mode": "summary"})
    assert_ok(r, "summary of a known guide")
    assert_contains(r.text, "## Parameters\n", "summary retains the parameter table")
    assert_contains(r.text, "| modulePath |", "summary retains the actual schema rows")
    assert_contains(r.text, "## Sections\n", "summary exposes a contents list")
    assert_contains(r.text, "| When to use | #when-to-use |", "contents list names a guide section")
    assert_contains(r.text, "| Notes | #notes |", "contents list reaches the end of the guide")
    assert_not_contains(r.text, "For an empty file,", "summary must omit section bodies")
    assert_no_diff("guide discovery must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_one_section_matches_the_original_span_case_insensitively():
    """Selecting a heading returns precisely its original section, excluding siblings."""
    full = call("get_tool_guide", {"toolName": TARGET_TOOL})
    assert_ok(full, "full guide for the independent section-span comparison")
    start = full.text.index("## Empty file\n")
    end = full.text.index("## Examples\n", start)
    r = call("get_tool_guide", {"toolName": TARGET_TOOL, "section": "eMpTy FiLe"})
    assert_ok(r, "case-insensitive section title")
    if r.text != full.text[start:end]:
        _fail("section must equal the original Empty file span, including whitespace")
    assert_contains(r.text, "`totalLines` is `0`", "selected section retains its instructions")
    assert_not_contains(r.text, "## Examples", "selection excludes the next section")
    assert_no_diff("reading a section must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_section_accepts_an_anchor_from_the_contents_list():
    """The anchor advertised by summary can be used to retrieve the section."""
    summary = call("get_tool_guide", {"toolName": TARGET_TOOL, "mode": "summary"})
    assert_ok(summary, "discover section anchors")
    assert_contains(summary.text, "| Empty file | #empty-file |", "discover the exact anchor")
    title = call("get_tool_guide", {"toolName": TARGET_TOOL, "section": "Empty file"})
    assert_ok(title, "retrieve by title")
    anchored = call("get_tool_guide", {"toolName": TARGET_TOOL, "section": "#EMPTY-FILE"})
    assert_ok(anchored, "retrieve by anchor")
    if anchored.text != title.text:
        _fail("the advertised anchor must select exactly the same section as its title")
    assert_contains(anchored.text, "## Empty file\n", "anchor selects the requested section")
    assert_not_contains(anchored.text, "## Examples", "anchor must not return the full guide")
    assert_no_diff("anchor selection must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_query_returns_the_complete_matching_section():
    """A mixed-case literal body query returns Output, without other sections."""
    full = call("get_tool_guide", {"toolName": TARGET_TOOL})
    assert_ok(full, "full guide for the independent query-span comparison")
    start = full.text.index("## Output\n")
    end = full.text.index("## Continuation (large files)\n", start)
    r = call("get_tool_guide", {"toolName": TARGET_TOOL,
                               "query": "OpAqUe WhOlE-FiLe ReViSiOn ToKeN"})
    assert_ok(r, "case-insensitive literal query")
    if r.text != full.text[start:end]:
        _fail("query must return the entire original matching Output section")
    assert_contains(r.text, "Frontmatter fields:", "query retains the whole matching section")
    assert_not_contains(r.text, "## Examples", "query excludes unrelated sections")
    assert_no_diff("querying a guide must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_unknown_section_lists_actionable_headings():
    """An unknown heading errors with the bad value and available alternatives."""
    bad = "not-a-real-heading-629"
    r = call("get_tool_guide", {"toolName": TARGET_TOOL, "section": bad})
    err = assert_error(r, "unknown guide section")
    assert_error_quality(err, names=[bad, "Unknown section"],
                         suggests=["Pass a heading or anchor", "When to use", "#when-to-use"],
                         ctx="unknown section gives usable alternatives")
    assert_not_contains(err, "For an empty file,", "error must list headings without returning the full guide")
    assert_no_diff("an invalid section must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_default_and_explicit_full_equal_the_unchanged_guide_resource():
    """The default keeps the previous full text shape and exactly matches resources/read."""
    default = call("get_tool_guide", {"toolName": TARGET_TOOL})
    assert_ok(default, "default full guide")
    explicit = call("get_tool_guide", {"toolName": TARGET_TOOL, "mode": "full"})
    assert_ok(explicit, "explicit full guide")
    resource = _post("resources/read", {"uri": "guide://" + TARGET_TOOL})
    if "error" in resource:
        _fail("resources/read must return the original full guide: %r" % resource)
    contents = (resource.get("result") or {}).get("contents") or []
    if len(contents) != 1 or not contents[0].get("text"):
        _fail("resources/read must return exactly one non-empty guide body: %r" % resource)
    original = contents[0]["text"]
    if default.text.encode("utf-8") != original.encode("utf-8") or explicit.text != original:
        _fail("default and explicit full output must be byte-identical to the unchanged guide resource")
    if not default.text.startswith("# read_module_source\n\n"):
        _fail("the original full guide must retain its tool-name title")
    assert_contains(default.text, "| Parameter | Required | Type | Description |", "original parameter table")
    assert_contains(default.text, "\n## Guide\n", "original full guide wrapper")
    assert_contains(default.text, "## Notes\n", "default guide includes its final section")
    assert_not_contains(default.text, "## Sections\n", "default must not switch to summary")
    assert_no_diff("reading the full guide and its resource must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_no_match_query_is_literal_and_lists_headings():
    """Regex metacharacters are literal, and an empty result offers section discovery."""
    r = call("get_tool_guide", {"toolName": TARGET_TOOL, "query": ".*"})
    assert_ok(r, "query with no literal matches")
    assert_contains(r.text, "No sections match query `.*`.", "query must not treat .* as a regex")
    assert_contains(r.text, "| Empty file | #empty-file |", "no-match result lists usable headings")
    assert_not_contains(r.text, "For an empty file,", "no-match result must not return section bodies")
    assert_no_diff("an unmatched query must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_selectors_are_mutually_exclusive():
    """Every conflicting selector pair, including explicit full, is rejected clearly."""
    for selectors in (
        {"mode": "summary", "section": "Output"},
        {"mode": "full", "query": "contentHash"},
        {"section": "Output", "query": "contentHash"},
    ):
        r = call("get_tool_guide", dict(selectors, toolName=TARGET_TOOL))
        err = assert_error(r, "conflicting guide selectors")
        assert_error_quality(err, names=list(selectors), suggests=["Pass only one"],
                             ctx="selector conflict is actionable")
    assert_no_diff("conflicting selectors must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_unknown_mode_and_blank_query_are_actionable_errors():
    """Invalid enum and blank search terms fail before returning a full guide."""
    r = call("get_tool_guide", {"toolName": TARGET_TOOL, "mode": "tiny"})
    err = assert_error(r, "unknown guide mode")
    assert_error_quality(err, names=["tiny", "mode"], suggests=["full", "summary"],
                         ctx="unknown mode offers valid values")
    r = call("get_tool_guide", {"toolName": TARGET_TOOL, "query": " \t"})
    err = assert_error(r, "blank guide query")
    assert_error_quality(err, names=["query"], suggests=["Pass a literal search term"],
                         ctx="blank query offers a fix")
    assert_no_diff("invalid selectors must not change the fixture")
