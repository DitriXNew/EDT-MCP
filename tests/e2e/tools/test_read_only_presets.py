"""Default-profile wire regressions for the read-only preset repair (#641).

The server has no runtime preset-selection contract. Java's
ReadOnlyPresetVisibilityTest applies both presets and exercises the exact
getVisibleTools path used by tools/list. This module checks the complementary
stand contract: the default profile keeps the six writers and the three explicit
write-hint exceptions keep their annotations. It never executes a workspace or
git writer. Run with --filter read_only_presets on a default-profile stand with
progressive disclosure off, like the tools/list golden slice.
"""

from harness import _post, assert_no_diff, e2e_test


def _tools_by_name():
    raw = _post("tools/list", {})
    if "error" in raw:
        raise AssertionError("tools/list failed: %r" % raw["error"])
    tools = raw.get("result", {}).get("tools")
    if not isinstance(tools, list) or not tools:
        raise AssertionError("tools/list must return a non-empty catalogue: %r" % raw)
    names = [tool["name"] for tool in tools]
    if len(names) != len(set(names)):
        raise AssertionError("tools/list returned duplicate names: %r" % names)
    return {tool["name"]: tool for tool in tools}


@e2e_test(tool="_tools_list_read_only_presets", kind="read")
def test_read_only_presets_leave_the_default_profile_writers_available():
    """Changing the two presets must not globally disable these six tools."""
    tools = _tools_by_name()
    writers = {"switch_git_branch", "create_git_branch", "set_branch_infobase",
               "create_project", "resync_to_disk", "clean_project"}
    missing = writers - tools.keys()
    if missing:
        raise AssertionError("default tools/list lost workspace/git writers: %r" % sorted(missing))
    for name in sorted(writers):
        annotations = tools[name].get("annotations") or {}
        if annotations.get("readOnlyHint") is not False:
            raise AssertionError("%s must retain readOnlyHint=false: %r" % (name, annotations))
    for name in ("git", "ask_workmate"):
        if name in tools:
            raise AssertionError("default-off tool %r was unexpectedly advertised" % name)
    assert_no_diff("listing default-profile tools must not change the fixture")


@e2e_test(tool="_tools_list_read_only_presets", kind="read")
def test_read_only_presets_keep_the_existing_analysis_tool_annotations():
    """The explicit exceptions retain false hints; actual read tools retain true hints."""
    tools = _tools_by_name()
    expected = {"revalidate_objects": False, "compare_configurations": False,
                "enable_toolset": False, "list_git_branches": True,
                "get_comparison_node": True}
    for name, read_only_hint in expected.items():
        if name not in tools:
            raise AssertionError("default tools/list must keep %r" % name)
        annotations = tools[name].get("annotations") or {}
        if annotations.get("readOnlyHint") is not read_only_hint:
            raise AssertionError("%s changed its readOnlyHint (expected %r): %r"
                                 % (name, read_only_hint, annotations))
    assert_no_diff("listing the retained analysis tools must not change the fixture")
