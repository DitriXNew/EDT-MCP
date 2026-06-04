"""
Golden-snapshot guard for the live tools/list schema payload (kind: read).

WHY
---
The other tools/list test only checks ">0 tools + a few names"; ToolContractConsistencyTest
(Java) checks schema SHAPE, not CONTENT. So a silent contract drift — a parameter
renamed (objectFqn -> fqn), a `required` entry dropped, an enum's allowed values
changed, an annotation flipped, a description rewritten — passes CI unnoticed. This
test pins the EXACT wire contract every MCP client sees (the serialized
name + description + inputSchema + annotations + outputSchema for all tools) against a
committed golden file. Any change to that surface now shows up as a reviewable diff in this
file: intended changes are regenerated on purpose; unintended ones fail the suite.

This guards the real SERIALIZED payload (what McpProtocolHandler emits over the wire),
not just the in-process registry — so serialization drift is caught too.

REGENERATING (a CONSCIOUS act, never automatic in a normal run)
--------------------------------------------------------------
After an intentional schema change, regenerate and review the diff:

    EDT_MCP_UPDATE_GOLDEN=1 python tests/e2e/run_all.py --filter _tools_list_golden

then `git diff tests/e2e/tools_list.golden.json` to confirm the change is exactly
what you meant, and commit it alongside the code change.
"""

import os
import json
import difflib

from harness import _post, _fail, assert_no_diff, e2e_test

_GOLDEN = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "tools_list.golden.json")
_ACTUAL = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "tools_list.actual.json")  # written on mismatch for inspection (gitignored)


def _canonical_tools_list():
    """Fetch the live tools/list and render it in a STABLE canonical form.

    Sorted by tool name, keys sorted recursively, UTF-8 preserved — so the only way
    the text changes is a real change to a tool's
    name/description/inputSchema/annotations/outputSchema.
    """
    raw = _post("tools/list", {})
    tools = (raw.get("result", {}) or {}).get("tools", []) or []
    entries = [{
        "name": t.get("name"),
        "description": t.get("description", ""),
        "inputSchema": t.get("inputSchema"),
        "annotations": t.get("annotations"),
        # outputSchema is present only on JSON tools (others omit it); capturing it
        # here pins the structured-output contract against drift too.
        "outputSchema": t.get("outputSchema"),
    } for t in sorted(tools, key=lambda t: t.get("name") or "")]
    return json.dumps(entries, sort_keys=True, ensure_ascii=False, indent=2) + "\n", len(entries)


@e2e_test(tool="_tools_list_golden", kind="read")
def test_tools_list_matches_committed_golden_snapshot():
    actual, count = _canonical_tools_list()
    if count < 50:
        _fail("tools/list returned only %d tools — refusing to compare/regenerate a "
              "truncated snapshot (server not fully up?)" % count)

    # Conscious regeneration: opt-in via env var. Overwrites the golden and passes,
    # so the reviewer inspects `git diff tools_list.golden.json`.
    if os.environ.get("EDT_MCP_UPDATE_GOLDEN"):
        with open(_GOLDEN, "w", encoding="utf-8", newline="\n") as f:
            f.write(actual)
        return

    if not os.path.exists(_GOLDEN):
        with open(_ACTUAL, "w", encoding="utf-8", newline="\n") as f:
            f.write(actual)
        _fail("golden snapshot missing (%s). Generate it with "
              "EDT_MCP_UPDATE_GOLDEN=1 and commit it. Current payload written to %s."
              % (_GOLDEN, _ACTUAL))

    with open(_GOLDEN, encoding="utf-8") as f:
        golden = f.read()

    if actual != golden:
        with open(_ACTUAL, "w", encoding="utf-8", newline="\n") as f:
            f.write(actual)
        diff = "".join(difflib.unified_diff(
            golden.splitlines(keepends=True), actual.splitlines(keepends=True),
            fromfile="tools_list.golden.json (committed)", tofile="tools/list (live)",
            n=2))
        # Keep the failure message bounded but show the meaningful drift.
        snippet = "".join(diff.splitlines(keepends=True)[:60])
        _fail("tools/list schema drifted from the committed golden. If this change is "
              "INTENTIONAL, regenerate with EDT_MCP_UPDATE_GOLDEN=1 and review/commit "
              "the golden diff; otherwise a tool's name/param/enum/required/annotation/"
              "description changed unexpectedly.\nFull live payload: %s\n--- drift (first 60 lines) ---\n%s"
              % (_ACTUAL, snippet))

    # A protocol read must never touch the fixture on disk.
    assert_no_diff("reading tools/list must not modify the project")
