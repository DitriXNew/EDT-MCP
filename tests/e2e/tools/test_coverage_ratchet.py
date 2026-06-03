"""
Coverage ratchet: every tool the live server advertises in tools/list MUST have
e2e coverage (at least one @e2e_test registered for it). Adding a new MCP tool
without a per-tool test file fails this — the e2e analogue of the Java
BuiltInToolTestCoverageTest.
"""

import harness
from harness import e2e_test, assert_no_diff, _post, _fail


@e2e_test(tool="_coverage_ratchet", kind="read")
def test_every_registered_tool_has_e2e_coverage():
    raw = _post("tools/list", {})
    advertised = sorted(t["name"] for t in (raw.get("result", {}).get("tools", []) or []))
    if not advertised:
        _fail("tools/list returned no tools — cannot verify coverage")

    covered = set(t["tool"] for t in harness.REGISTRY)
    missing = [name for name in advertised if name not in covered]
    if missing:
        _fail("%d tool(s) advertised by tools/list have NO e2e coverage: %s"
              % (len(missing), ", ".join(missing)))

    # The ratchet itself is read-only.
    assert_no_diff("the coverage ratchet must not mutate the project")
