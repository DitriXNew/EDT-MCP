"""Request validation before tool lookup; uses the shared live MCP transport."""

from harness import _post, assert_error_quality, assert_no_diff, e2e_test


@e2e_test(tool="_tool_call_validation", kind="read")
def test_missing_and_blank_tool_names_are_invalid_params():
    cases = [None, {}, {"arguments": {}}, {"name": None},
             {"name": ""}, {"name": " \t\n"}]
    for params in cases:
        response = _post("tools/call", params)
        error = response.get("error") or {}
        if "result" in response or error.get("code") != -32602:
            raise AssertionError("missing or blank params.name must be invalid params: %r" % response)
        assert_error_quality(error.get("message") or "", names=["params.name"],
                             suggests=["tools/list"], ctx="missing tool-name recovery")
    assert_no_diff("rejected tool lookup must not modify the fixture")


@e2e_test(tool="_tool_call_validation", kind="read")
def test_nonblank_unknown_tool_name_keeps_lookup_error():
    name = "__history_cards_nonexistent_tool__"
    response = _post("tools/call", {"name": name, "arguments": {}})
    error = response.get("error") or {}
    if "result" in response or error.get("code") != -32601 or name not in error.get("message", ""):
        raise AssertionError("a nonblank unknown name must retain its lookup error: %r" % response)
    assert_no_diff("unknown tool lookup must not modify the fixture")
