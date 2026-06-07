"""
e2e tests for list_toolsets (kind: read).

list_toolsets reports the tool groups (toolsets) that drive progressive tool
disclosure: each toolset's id, title, description, member tools, toolCount, whether
it is currently visible in tools/list, and whether it is the always-on core group.
It is a JSON tool (data in r.structured), parameter-free, and read-only — it must
never touch the project tree (assert_no_diff on every path).

The live e2e server runs with progressive disclosure OFF by default, so every
toolset reports visible=true and progressiveDisclosure=false here; the ON-mode
filtering (only core + revealed groups appear in tools/list) is exercised in the
live redeploy loop with EDT_MCP_PROGRESSIVE_DISCLOSURE=true, not by this suite.
"""

from harness import (
    call,
    assert_ok,
    assert_no_diff,
    e2e_test,
)

# Toolset entry fields that are part of the contract (sibling tool enable_toolset
# and any UI rely on them).
_ENTRY_FIELDS = ("id", "title", "description", "tools", "toolCount", "visible", "core")


@e2e_test(tool="list_toolsets", kind="read")
def test_returns_wellformed_toolset_catalogue():
    """The catalogue must be a well-formed JSON envelope: a list of toolsets, a
    count that matches it, the disclosure flag, and the always-on core group that
    carries the two meta-tools. Structural invariants only (the exact set of groups
    is environment-independent but asserting it verbatim would be brittle)."""
    r = call("list_toolsets", {})
    assert_ok(r, "list_toolsets happy path")

    sc = r.structured
    if not isinstance(sc, dict):
        raise AssertionError("list_toolsets must populate structuredContent: %r" % sc)
    if "progressiveDisclosure" not in sc:
        raise AssertionError("envelope must carry progressiveDisclosure: %r" % sc)
    toolsets = sc.get("toolsets")
    if not isinstance(toolsets, list) or not toolsets:
        raise AssertionError("'toolsets' must be a non-empty list: %r" % toolsets)
    if sc.get("count") != len(toolsets):
        raise AssertionError("count(%r) must equal len(toolsets)(%d)" % (sc.get("count"), len(toolsets)))

    for entry in toolsets:
        if not isinstance(entry, dict):
            raise AssertionError("each toolset entry must be an object: %r" % entry)
        for field in _ENTRY_FIELDS:
            if field not in entry:
                raise AssertionError("toolset entry missing %r: %r" % (field, entry))
        if entry["toolCount"] != len(entry["tools"]):
            raise AssertionError("toolCount must equal len(tools): %r" % entry)

    core = [t for t in toolsets if t.get("id") == "core"]
    if not core:
        raise AssertionError("the core toolset must be present: %r" % toolsets)
    core_entry = core[0]
    if not core_entry.get("core"):
        raise AssertionError("the core toolset must be flagged core=true: %r" % core_entry)
    core_tools = core_entry.get("tools") or []
    for meta in ("list_toolsets", "enable_toolset"):
        if meta not in core_tools:
            raise AssertionError("core toolset must contain %r: %r" % (meta, core_tools))

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_toolsets", kind="read")
def test_core_is_always_visible():
    """The core toolset is always visible regardless of disclosure mode — a client
    can always reach list_toolsets / enable_toolset to manage the rest."""
    r = call("list_toolsets", {})
    assert_ok(r, "list_toolsets")
    core = [t for t in (r.structured or {}).get("toolsets", []) if t.get("id") == "core"]
    if not core or core[0].get("visible") is not True:
        raise AssertionError("core toolset must be visible: %r" % core)
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="list_toolsets", kind="read")
def test_ignores_unknown_params_and_does_not_mutate():
    """Unknown params are ignored (the tool takes none); it still returns the
    catalogue and changes nothing on disk."""
    r = call("list_toolsets", {"bogusParam": "x"})
    assert_ok(r, "list_toolsets with an unknown param")
    if not isinstance(r.structured, dict) or "toolsets" not in r.structured:
        raise AssertionError("unknown params must be ignored, catalogue still returned: %r" % r.structured)
    assert_no_diff("a read tool must not touch the project on disk")
