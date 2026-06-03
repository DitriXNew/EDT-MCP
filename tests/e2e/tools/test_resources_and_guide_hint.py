"""
e2e tests for the MCP resources channel + the error-path guide hint (kind: read).

These are protocol-level features, not a tool, so they register under the synthetic
underscore tool name "_resources" (mirroring test_tools_list_golden.py's
"_tools_list_golden") to satisfy the per-tool coverage ratchet without inventing a
fake tool.

WHAT IS COVERED
---------------
1. resources/list  -> one guide://<toolName> entry per enabled tool, text/markdown.
   We assert a guide:// entry for a known, always-registered tool (read_module_source)
   exists.
2. resources/read of a known guide:// uri -> a single text/markdown content whose body
   is the rendered guide (must contain the "Parameters" section).
3. resources/read of a bad uri (wrong scheme / unknown tool) -> a JSON-RPC error, not a
   partial result.
4. The error-path guide hint: a real tool called with a MISSING required arg returns an
   error whose human-readable TEXT now points the caller to get_tool_guide(...).

resources/list and resources/read are NOT tools/call, so they go through the raw
JSON-RPC channel via harness._post(method, params), not call(tool, args).

Read-only: every test ends with assert_no_diff() — inspecting the protocol surface
must never mutate the project fixture on disk (SKILL guardrail).
"""

from harness import (
    _post, call, assert_error, assert_contains, assert_no_diff, _fail, e2e_test,
)

# A tool that is always registered and has a known required param (projectName).
KNOWN_TOOL = "read_module_source"
KNOWN_GUIDE_URI = "guide://" + KNOWN_TOOL


# ──────────────────────────────────────────────────────────────────────────────
# resources/list + resources/read
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="_resources", kind="read")
def test_resources_list_and_read_guide():
    """resources/list advertises guide:// docs; resources/read returns one as markdown.

    Mutation thinking: if the server stopped advertising resources (or served a wrong
    body), the guide:// entry would be missing from the list, or the read body would
    lack the rendered "Parameters" section, and this would FAIL.
    """
    # --- resources/list: a guide:// entry for a known tool must exist ---
    listed = _post("resources/list", {})
    resources = (listed.get("result", {}) or {}).get("resources", []) or []
    if not resources:
        _fail("resources/list returned no resources (server not advertising guides?)")
    uris = [r.get("uri") for r in resources]
    if KNOWN_GUIDE_URI not in uris:
        _fail("resources/list must include %r; got %d entries, e.g. %s"
              % (KNOWN_GUIDE_URI, len(uris), uris[:5]))
    # Each advertised guide must be markdown.
    known = next(r for r in resources if r.get("uri") == KNOWN_GUIDE_URI)
    assert_contains(known.get("mimeType", ""), "text/markdown",
                    "a guide resource must advertise text/markdown")

    # --- resources/read of the known guide:// uri returns rendered markdown ---
    read = _post("resources/read", {"uri": KNOWN_GUIDE_URI})
    contents = (read.get("result", {}) or {}).get("contents", []) or []
    if not contents:
        _fail("resources/read of %r returned no contents: %s" % (KNOWN_GUIDE_URI, read))
    body = contents[0]
    assert_contains(body.get("mimeType", ""), "text/markdown",
                    "guide content must be text/markdown")
    assert_contains(body.get("uri", ""), KNOWN_GUIDE_URI,
                    "guide content must echo the requested uri")
    text = body.get("text", "")
    assert_contains(text, "Parameters", "rendered guide must carry a Parameters section")
    assert_contains(text, KNOWN_TOOL, "rendered guide must name the documented tool")

    # --- a bad uri must error (wrong scheme), not return a partial result ---
    bad_scheme = _post("resources/read", {"uri": "file:///etc/passwd"})
    if "error" not in bad_scheme:
        _fail("resources/read of a non-guide:// uri must be a JSON-RPC error: %s" % bad_scheme)

    # --- an unknown guide tool must also error ---
    unknown = _post("resources/read", {"uri": "guide://no_such_tool_zzz"})
    if "error" not in unknown:
        _fail("resources/read of an unknown guide must be a JSON-RPC error: %s" % unknown)

    assert_no_diff("inspecting the resources surface must not modify the project")


# ──────────────────────────────────────────────────────────────────────────────
# Error-path guide hint
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="_resources", kind="read")
def test_error_text_points_to_get_tool_guide():
    """A real tool failing on a missing required arg points the caller to get_tool_guide.

    read_module_source requires projectName; omitting it yields a tool-level error.
    The human-readable error TEXT channel (Result.text == content[0].text) must now
    carry the get_tool_guide(...) pointer so the caller knows where to find the full
    parameter list and examples.

    Mutation thinking: if the hint were dropped (or appended to the wrong channel),
    the get_tool_guide token would be absent from the error text and this would FAIL.
    """
    r = call(KNOWN_TOOL, {"modulePath": "CommonModules/Error/Module.bsl"})
    # It must be a tool-level error (missing required projectName).
    assert_error(r, "missing required projectName must be an error")
    # The hint lands on the human-readable text channel, naming the tool to call.
    assert_contains(r.text, "get_tool_guide",
                    "error text must point the caller to get_tool_guide")
    assert_contains(r.text, KNOWN_TOOL,
                    "the guide hint must name the failing tool")
    assert_no_diff("an invalid call must not touch the project on disk")
