"""
e2e tests for enable_toolset (kind: action).

enable_toolset reveals (or, with disable=true, hides) tool groups for progressive
tool disclosure. It is a JSON tool that mutates only the in-memory server toolset
state — NEVER the project tree — so every path asserts assert_no_diff.

The live e2e server runs with progressive disclosure OFF by default, so revealing a
toolset has no effect on tools/list here (all tools are already listed); these tests
verify the tool's CONTRACT (required-arg + invalid-value errors, the enable/disable
round-trip, core-is-ignored). The actual on/off filtering of tools/list is exercised
in the live redeploy loop with EDT_MCP_PROGRESSIVE_DISCLOSURE=true.

Each test restores the toolset state it changed (disable what it enabled) so the
suite leaves the server's disclosure state as it found it.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
)

# A toolset id that is real but rarely needed, used for the enable/disable round-trip
# so the test perturbs an unobtrusive group.
ROUNDTRIP_TOOLSET = "tags"


@e2e_test(tool="enable_toolset", kind="action")
def test_missing_toolsets_errors_actionably():
    """No toolsets arg -> a clear required-arg error that names the parameter and
    points at the discovery tool (list_toolsets)."""
    r = call("enable_toolset", {})
    err = assert_error(r, "missing toolsets")
    assert_error_quality(
        err,
        names=["toolsets"],
        suggests=["list_toolsets"],
        ctx="missing toolsets names the param and points at list_toolsets",
    )
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="enable_toolset", kind="action")
def test_all_invalid_ids_error_and_name_value():
    """A toolset id that does not exist -> an error that echoes the bad value and
    points at list_toolsets (no silent no-op)."""
    bad = "no_such_toolset_zzz"
    r = call("enable_toolset", {"toolsets": bad})
    err = assert_error(r, "invalid toolset id")
    assert_error_quality(
        err,
        names=[bad],
        suggests=["list_toolsets"],
        ctx="invalid toolset id is named and routed to list_toolsets",
    )
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="enable_toolset", kind="action")
def test_enable_then_disable_roundtrip_does_not_mutate_disk():
    """Enabling a valid toolset succeeds (action=enabled, id in applied) and changes
    no files; disabling it again succeeds (action=disabled) — which also restores the
    server state so the suite leaves disclosure as it found it."""
    r = call("enable_toolset", {"toolsets": ROUNDTRIP_TOOLSET})
    assert_ok(r, "enable a valid toolset")
    sc = r.structured or {}
    if sc.get("action") != "enabled":
        raise AssertionError("action must be 'enabled': %r" % sc)
    if ROUNDTRIP_TOOLSET not in (sc.get("applied") or []):
        raise AssertionError("applied must list the enabled toolset: %r" % sc)
    if "visibleToolsets" not in sc:
        raise AssertionError("envelope must report visibleToolsets: %r" % sc)
    assert_no_diff("enabling a toolset must not touch the project on disk")

    # Disable again: verifies the disable path AND restores the prior state.
    r2 = call("enable_toolset", {"toolsets": ROUNDTRIP_TOOLSET, "disable": True})
    assert_ok(r2, "disable the toolset again")
    if (r2.structured or {}).get("action") != "disabled":
        raise AssertionError("action must be 'disabled': %r" % r2.structured)
    assert_no_diff("disabling a toolset must not touch the project on disk")


@e2e_test(tool="enable_toolset", kind="action")
def test_core_is_ignored_not_an_error():
    """Toggling the always-on core toolset is meaningless but not an error: it comes
    back in 'ignored', not 'applied', with success=true."""
    r = call("enable_toolset", {"toolsets": "core"})
    assert_ok(r, "toggling core is a benign no-op")
    sc = r.structured or {}
    if "core" not in (sc.get("ignored") or []):
        raise AssertionError("core must be reported in 'ignored': %r" % sc)
    if "core" in (sc.get("applied") or []):
        raise AssertionError("core must NOT be in 'applied': %r" % sc)
    assert_no_diff("a no-op call must not touch the project on disk")
