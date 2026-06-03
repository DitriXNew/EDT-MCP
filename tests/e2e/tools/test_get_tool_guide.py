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
    assert_contains, assert_no_diff, e2e_test,
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
