"""Read-only preset tools/list checks, selected by EDT_MCP_TOOL_PRESET at EDT launch.

Predefined override IDs are all-tools, analysis-only, code-review and development;
matching is case-insensitive and trimmed. Unset/blank values and custom use the
stored selection. Explicit custom logs one INFO line explaining there is no launch
override; unknown IDs are ignored with one WARNING listing only the predefined
override IDs and the stored-selection hint. Resolution happens once on first use.

Run from tests/e2e with `python -m tools.test_preset_launch` after launching EDT
with the same preset variable. The normal runner's model cleanup requires
clean_project, which these presets deliberately disable. This read-only entry
point reuses the harness handshake, transport and fixture cleanliness checks.
"""

import os

from harness import (
    E2ESkip, _post, all_fixtures_status, assert_no_diff, e2e_test,
    initialize, wait_for_server,
)

_KEEP = {"compare_configurations", "enable_toolset", "revalidate_objects"}
_WRITERS = {"switch_git_branch", "create_git_branch", "set_branch_infobase",
            "create_project", "resync_to_disk", "clean_project"}


def _expected_preset():
    value = os.environ.get("EDT_MCP_TOOL_PRESET", "").strip().lower()
    if not value:
        raise E2ESkip("EDT_MCP_TOOL_PRESET is unset; launch EDT and this test with "
                      "analysis-only or code-review to check the preset on the wire")
    if value not in ("analysis-only", "code-review"):
        raise E2ESkip("EDT_MCP_TOOL_PRESET=%r is outside this read-only check; "
                      "launch EDT and this test with analysis-only or code-review" % value)
    return value


def _preset_tools():
    preset = _expected_preset()
    response = _post("tools/list", {})
    assert "error" not in response, "%s tools/list failed: %r" % (preset, response)
    tools = response.get("result", {}).get("tools")
    assert isinstance(tools, list) and tools, "%s tools/list must be non-empty: %r" % (preset, response)
    by_name = {tool["name"]: tool for tool in tools}
    assert len(by_name) == len(tools), "%s tools/list contains duplicate names" % preset
    return preset, by_name


@e2e_test(tool="_tools_list_preset_launch", kind="read")
def test_preset_launch_only_advertises_read_only_tools_and_explicit_keeps():
    preset, tools = _preset_tools()
    leaked = sorted(name for name, tool in tools.items()
                    if (tool.get("annotations") or {}).get("readOnlyHint") is not True
                    and name not in _KEEP)
    assert not leaked, "%s tools/list advertises tools without readOnlyHint=true: %r" % (preset, leaked)
    assert _KEEP <= tools.keys(), "%s tools/list lost explicit keeps: %r" % (preset, sorted(_KEEP - tools.keys()))
    for name in sorted(_KEEP):
        assert (tools[name].get("annotations") or {}).get("readOnlyHint") is False, (
            "%s must keep %s with its existing readOnlyHint=false" % (preset, name))
    assert {"list_projects", "get_edt_version", "list_git_branches", "get_comparison_node"} <= tools.keys(), (
        "%s tools/list lost baseline read tools; disable progressive disclosure for this check" % preset)
    # A distinct read-tool control proves which of the two presets reached the server.
    assert ("read_module_source" in tools) == (preset == "code-review"), (
        "tools/list does not match EDT_MCP_TOOL_PRESET=%s: read_module_source visibility is wrong" % preset)
    assert_no_diff("preset tools/list must not change the fixture")


@e2e_test(tool="_tools_list_preset_launch", kind="read")
def test_preset_launch_hides_all_six_workspace_and_git_writers():
    preset, tools = _preset_tools()
    leaked = _WRITERS & tools.keys()
    assert not leaked, "%s tools/list leaked #641 writers: %r" % (preset, sorted(leaked))
    assert "list_git_branches" in tools, "%s must keep git branch listing" % preset
    assert_no_diff("listing preset tools must not execute a writer")


def main():
    try:
        preset = _expected_preset()
    except E2ESkip as exc:
        print("[SKIP] preset launch wire checks: %s" % exc)
        return
    # No writes are made, so require a clean start and end without invoking a disabled writer.
    assert not all_fixtures_status(), "preset wire checks require clean fixtures before launch"
    wait_for_server()
    initialize()
    try:
        for test in (test_preset_launch_only_advertises_read_only_tools_and_explicit_keeps,
                     test_preset_launch_hides_all_six_workspace_and_git_writers):
            test()
            print("[PASS] %s: %s" % (preset, test.__name__))
    finally:
        assert not all_fixtures_status(), "preset wire checks left dirty fixtures"


if __name__ == "__main__":
    main()
