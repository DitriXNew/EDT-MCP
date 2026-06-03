"""
e2e tests for get_edt_version (kind: read).

The tool returns the running 1C:EDT version as a PLAIN version string.
ResponseType is TEXT -> the payload is in r.text (NOT r.structured).

Source contract (GetEdtVersionTool.java):
  - getInputSchema() == JsonSchemaBuilder.object().build()  -> the tool takes
    NO parameters at all (no required param, no enum, no XOR, no path/object
    resolution).
  - execute(params) IGNORES params entirely and returns getEdtVersion().
  - getEdtVersion() is total: every branch returns a non-empty string, and any
    failure is caught and returns the documented sentinel "Unknown". It NEVER
    calls ToolResult.error and NEVER throws out of the tool.

Consequence for the negative matrix: there is NO reachable error path from any
client input. A tool with no params and a total, non-throwing implementation
cannot be driven into ToolResult.error from the wire. Per the e2e SKILL.md
rules we therefore do NOT fabricate a negative that cannot occur. Instead the
"negative" coverage is the robustness probe below: unexpected/spurious args must
be tolerated (the tool must still SUCCEED, not error) -- the only adversarial
input the tool's surface actually admits.

Every test ends with assert_no_diff(): a read tool must never mutate the
project on disk.
"""

from harness import (
    call, assert_ok, assert_error, assert_contains, assert_not_contains,
    assert_no_diff, e2e_test,
)

import re

# A real version string from any non-fallback branch is dotted-numeric
# (eclipse.buildId "2025.2.0.454", marketing "2026.1.0", or a raw OSGi version
# "x.y.z..."). This matches a "<digits>.<digits>" token anywhere in the text.
_VERSION_TOKEN = re.compile(r"\d+\.\d+")


# ──────────────────────────────────────────────────────────────────────────────
# Happy path
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_edt_version", kind="read")
def test_returns_version_string_and_does_not_mutate():
    # No-arg call: the entire purpose of the tool. Must report a concrete running
    # EDT version (a live EDT workbench is required for the suite to run, so the
    # "Unknown" fallback should NOT be hit here).
    r = call("get_edt_version", {})
    assert_ok(r, "get_edt_version happy path")

    text = (r.text or "").strip()
    # Mutation thinking: a broken tool returning a no-op (empty string) FAILS here.
    assert text, "get_edt_version must return a non-empty version string"

    # The version is a single-line plain string, not a table / blob / multi-line
    # dump. A tool that accidentally returned a stack trace or markdown would have
    # newlines -> this FAILS.
    assert "\n" not in text, \
        "version must be a single-line plain string, got multi-line: %r" % text[:200]

    # The discriminating signal: a live EDT yields a dotted-numeric version token
    # (e.g. 2026.1.0). If the tool were broken and returned garbage / a placeholder
    # like the JSON "Done" sentinel, this FAILS. We do NOT hardcode the exact
    # version (it differs per install) but a non-fallback run MUST be dotted.
    assert _VERSION_TOKEN.search(text), \
        "expected a dotted version token (e.g. 2026.1.0), got: %r" % text[:200]

    # It must NOT be the failure sentinel: getEdtVersion() returns "Unknown" only
    # when every detection method failed -> on a live EDT that would itself be a
    # bug worth surfacing.
    assert_not_contains(text, "Unknown",
                        "live EDT must resolve a real version, not the 'Unknown' fallback")

    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Robustness probe (the only adversarial input this paramless tool admits)
# ──────────────────────────────────────────────────────────────────────────────
#
# AUDIT (not a tool defect, a coverage note): get_edt_version exposes an EMPTY
# input schema and a total, non-throwing implementation. There is NO required
# param, enum, XOR or path/object to make invalid, so the SKILL.md mandatory
# negative matrix (non-existent object/path/project, bad param combo, missing
# required) is INAPPLICABLE here -- none of those cases can be constructed. This
# is documented per the task's "no fabricated negatives" rule.

@e2e_test(tool="get_edt_version", kind="read")
def test_ignores_unexpected_arguments_and_still_succeeds():
    # execute() ignores its params map entirely. Passing spurious args (which a
    # confused client might send) must NOT break the tool: it must still SUCCEED
    # and return the same kind of version string -- not error, not echo the junk.
    r = call("get_edt_version",
             {"projectName": "NoSuchProject_ZZZ_e2e", "bogusParam": "garbage_value_e2e"})
    # Mutation thinking: if the tool started (incorrectly) validating/resolving
    # the spurious projectName, it would error here and this assert_ok FAILS.
    assert_ok(r, "spurious args must be ignored, not rejected")

    text = (r.text or "").strip()
    assert text, "version must still be returned when unexpected args are present"
    assert _VERSION_TOKEN.search(text), \
        "spurious args must not corrupt the version output, got: %r" % text[:200]
    # The junk we sent must NOT leak into the response (proves the tool returns the
    # real version, not an echo of arguments).
    assert_not_contains(text, "garbage_value_e2e",
                        "tool must not echo client-supplied junk into the version string")
    assert_not_contains(text, "NoSuchProject_ZZZ_e2e",
                        "tool must not echo the spurious projectName")

    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_edt_version", kind="read")
def test_is_deterministic_across_calls():
    # The running EDT version is fixed for the life of the process: two calls must
    # agree. This catches a tool that returned a random/uninitialized value or a
    # different code path on a second invocation (a real no-op/cache bug would
    # diverge here).
    r1 = call("get_edt_version", {})
    assert_ok(r1, "get_edt_version first call")
    r2 = call("get_edt_version", {})
    assert_ok(r2, "get_edt_version second call")

    v1 = (r1.text or "").strip()
    v2 = (r2.text or "").strip()
    assert v1 and v2, "both calls must return a non-empty version"
    assert v1 == v2, \
        "version must be stable across calls: %r != %r" % (v1[:120], v2[:120])

    assert_no_diff("a read tool must not touch the project on disk")
