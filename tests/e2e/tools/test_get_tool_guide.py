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
def test_query_returns_a_matching_line_with_its_section_and_anchor():
    """A mixed-case literal query returns a bounded snippet, excluding neighbouring prose."""
    r = call("get_tool_guide", {"toolName": TARGET_TOOL,
                               "query": "OpAqUe WhOlE-FiLe ReViSiOn ToKeN"})
    assert_ok(r, "case-insensitive literal query")
    assert_contains(r.text, "1 matching line in 1 section;", "query counts matching lines")
    assert_contains(r.text, "- Output (#output): - `contentHash`: an opaque whole-file revision token.",
                    "query labels the matching line with a reusable title and anchor")
    assert_not_contains(r.text, "Frontmatter fields:", "query must omit non-matching neighbouring lines")
    assert_not_contains(r.text, "## Examples", "query excludes unrelated sections")
    if len(r.text) >= 1_000:
        _fail("one matching line must return a short snippet")
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


@e2e_test(tool="get_tool_guide", kind="read")
def test_large_modify_metadata_summary_is_small_and_lists_every_h2():
    """The real large guide exposes all H2 headings, parameters and the full resource."""
    full = call("get_tool_guide", {"toolName": "modify_metadata"})
    assert_ok(full, "full modify_metadata guide")
    if len(full.text) <= 90_000:
        _fail("the regression must exercise the real large modify_metadata guide")
    r = call("get_tool_guide", {"toolName": "modify_metadata", "mode": "summary"})
    assert_ok(r, "large guide summary")
    if len(r.text) >= 15_000 or len(r.text) >= len(full.text) / 4:
        _fail("summary must retain only the description, parameters and a small heading index")
    assert_contains(r.text, "| fqn |", "summary retains the real parameter table")
    for line in full.text.splitlines():
        if line.startswith("## "):
            assert_contains(r.text, "| " + line[3:] + " | #", "every H2 is discoverable")
    assert_not_contains(r.text, "When the `fqn` is a **Role**", "summary excludes body instructions")
    assert_contains(r.text, "guide://modify_metadata", "summary offers the full guide resource")
    assert_no_diff("large guide discovery must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_broad_modify_metadata_query_caps_lines_with_an_exact_remainder():
    """An independently counted broad query cannot return neighbouring tens of KB."""
    full = call("get_tool_guide", {"toolName": "modify_metadata"})
    assert_ok(full, "independent source for the matching-line count")
    query = "type"
    count = sum(query in line.lower() for line in full.text.splitlines())
    if count <= 20:
        _fail("broad query fixture must contain more than the 20-line cap")
    r = call("get_tool_guide", {"toolName": "modify_metadata", "query": query})
    assert_ok(r, "bounded query of the large guide")
    assert_contains(r.text, "%s matching lines in " % count, "header counts all matching lines")
    assert_contains(r.text, "section=<heading|#anchor>", "query explains how to fetch complete instructions")
    hits = [line for line in r.text.splitlines() if line.startswith("- ")]
    if len(hits) != 20 or len(r.text) >= 10_000:
        _fail("broad query must return exactly 20 short matching-line snippets")
    for line in hits:
        assert_contains(line, "(#", "each snippet identifies its enclosing anchor")
    if not r.text.endswith("... (%s more)\n" % (count - 20)):
        _fail("remainder must equal the independently counted total minus 20")
    assert_not_contains(r.text, "A PREDEFINED item is addressed through its owner's member FQN",
                        "query excludes a body sentence from a non-matching section")
    assert_no_diff("broad guide search must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_nested_heading_accepts_pasted_markers_and_unquoted_title():
    """A real H3 can be selected with pasted Markdown markers and no backticks."""
    full = call("get_tool_guide", {"toolName": "modify_metadata"})
    assert_ok(full, "full guide for the independent H3 span")
    heading = "### Adjustable flags (`view` / `edit` / `userVisible` / `use`)\n"
    start = full.text.index(heading)
    end = full.text.index("### Use always", start)
    r = call("get_tool_guide", {"toolName": "modify_metadata",
                               "section": "### Adjustable flags (view / edit / userVisible / use)"})
    assert_ok(r, "normalized nested heading")
    if r.text != full.text[start:end]:
        _fail("nested selection must equal the original H3 span, including whitespace")
    assert_not_contains(r.text, "### Use always", "nested selection excludes its sibling")
    assert_no_diff("nested guide selection must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_query_reaches_the_body_h1_introduction():
    """Text after the deprecated guide's H1 remains searchable outside all H2 spans."""
    r = call("get_tool_guide", {"toolName": "debug_yaxunit_tests",
                               "query": "The job resolves and prepares the application"})
    assert_ok(r, "query outside H2 sections")
    assert_contains(r.text, "1 matching line in 1 section;", "one introduction line matches")
    assert_contains(r.text, "debug_yaxunit_tests (deprecated) (#debug_yaxunit_tests-deprecated)",
                    "the enclosing H1 identifies the hit")
    assert_not_contains(r.text, "## Parameter details", "query excludes the next H2")
    assert_no_diff("searching a guide introduction must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_blank_sections_and_short_queries_offer_actionable_fixes():
    """Empty/whitespace sections and one-character queries fail before rendering."""
    for value in ("", "   ", " \t\r\n"):
        r = call("get_tool_guide", {"toolName": TARGET_TOOL, "section": value})
        err = assert_error(r, "blank guide section")
        assert_error_quality(err, names=["section must not be blank"],
                             suggests=["Pass a heading or #anchor", "mode=summary"],
                             ctx="blank section offers usable selectors")
    r = call("get_tool_guide", {"toolName": TARGET_TOOL, "query": " a "})
    err = assert_error(r, "one-character guide query")
    assert_error_quality(err, names=["query", "at least 2 characters"],
                         suggests=["Pass a longer literal search term"],
                         ctx="short query offers a fix")
    assert_no_diff("invalid guide selectors must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_real_create_infobase_summary_omits_fenced_heading_comments():
    """Real shell comments in the infobase guide must stay out of the heading index."""
    r = call("get_tool_guide", {"toolName": "create_infobase", "mode": "summary"})
    assert_ok(r, "create_infobase summary")
    assert_contains(r.text, "| Typical workflow | #typical-workflow |", "real guide headings are loaded")
    assert_not_contains(r.text, "Create a brand-new infobase:", "shell comment is not a TOC row")
    assert_not_contains(r.text, "Or register an existing one:", "second shell comment is not a TOC row")
    assert_contains(r.text, "guide://create_infobase", "summary links to the full guide")
    assert_no_diff("infobase guide discovery must not change the fixture")


@e2e_test(tool="get_tool_guide", kind="read")
def test_late_credentials_query_keeps_the_match_in_its_bounded_snippet():
    """The real late match stays visible with its section and a leading cut marker."""
    phrase = "client half normally"
    full = call("get_tool_guide", {"toolName": "set_infobase_credentials"})
    assert_ok(full, "full credentials guide for the independent late-match check")
    source_lines = [line for line in full.text.splitlines() if phrase in line]
    if len(source_lines) != 1 or source_lines[0].index(phrase) <= 200:
        _fail("the real credentials match must exercise clipping beyond the first 200 characters")
    r = call("get_tool_guide", {"toolName": "set_infobase_credentials", "query": phrase})
    assert_ok(r, "query whose first match is beyond the old clipping window")
    assert_contains(r.text, "1 matching line in 1 section;", "the real phrase matches one line")
    assert_contains(r.text, "all three write the client half normally, because",
                    "the snippet must show the actual match with nearby context")
    assert_contains(r.text, "(#what-these-credentials-are-and-are-not): \u2026",
                    "the hit names its section and marks the clipped beginning")
    assert_not_contains(r.text, "A **shared** launch configuration", "the snippet must omit the distant line start")
    hits = [line for line in r.text.splitlines() if line.startswith("- ")]
    if len(hits) != 1 or len(hits[0].split("): ", 1)[1]) > 202:
        _fail("the query must return one snippet of at most 200 code points plus two cut markers")
    assert_no_diff("querying credentials instructions must not change the fixture")
