"""
e2e tests for get_server_status (kind: read).

Self-diagnosis tool. It takes NO input parameters (getInputSchema() is an empty
JsonSchemaBuilder.object().build()) and execute() reads NOTHING from the params
map. ResponseType is JSON, so the real payload is a dict in r.structured (NOT
r.text — for a JSON tool r.text is just the placeholder/content text).

It returns a fixed snapshot of the running server:
  port, running, protocolVersion, pluginVersion, edtVersion,
  enabledTools, totalTools, plainTextMode, checksFolderConfigured, authEnabled,
  formRenderFlags { nativeFormBufferedLayoutRender, nativeFormLayoutRender }.

Stable, code-anchored expectations (from the real impl, verified against source):
  - protocolVersion == McpConstants.PROTOCOL_VERSION == "2025-11-25"
  - running == true  (we are talking to a LIVE server on :8765, so server!=null
    and server.isRunning() must be true; a broken tool that hardcodes the
    headless `false` branch would fail here)
  - totalTools / enabledTools are positive ints (registry is populated)
  - formRenderFlags carries BOTH named JVM-flag keys (the diagnostic for a blank
    form screenshot) — these key names are the load-bearing contract.

NEGATIVE MATRIX — why it is minimal (documented, not fudged):
  The tool has NO parameters: no required param to omit, no enum to violate, no
  path/object/projectName to make non-existent, and no XOR/conditional branch.
  execute() never reads the params map, and its only failure path is an internal
  `catch (Exception)` that a well-formed client request cannot reach. There is
  therefore NO client-reachable validation error to assert error-quality against
  (same situation as get_edt_version). Fabricating a "negative" that cannot occur
  would be a cheat (§6).
  What we CAN and DO assert as robustness coverage: passing an UNEXPECTED extra
  argument must be ignored gracefully (the tool must still succeed and return the
  same well-formed snapshot) — a tool that choked on an unknown key would be a
  real bug, so this test fails if that robustness regressed.

Every test ends with assert_no_diff(): a read/diagnosis tool must never mutate
the project on disk.
"""

from harness import (
    call, assert_ok, assert_no_diff, e2e_test,
)

# Code-anchored constant from McpConstants.PROTOCOL_VERSION; if the server spoke a
# different protocol version than it reports here, that is a real contract break.
EXPECTED_PROTOCOL_VERSION = "2025-11-25"


# ──────────────────────────────────────────────────────────────────────────────
# Happy path
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_server_status", kind="read")
def test_returns_live_server_snapshot_and_does_not_mutate():
    r = call("get_server_status", {})
    assert_ok(r, "get_server_status happy path")

    # JSON response type -> payload is in structured, not text.
    s = r.structured
    if not isinstance(s, dict):
        raise AssertionError(
            "get_server_status must return a JSON object in structuredContent; got: %r"
            % (s,))

    # --- the server is LIVE: prove we hit the running-server branch, not the
    # headless fallback. A no-op/headless-stubbed tool would report running=false.
    if s.get("running") is not True:
        raise AssertionError(
            "expected running=true from a live server on :8765; snapshot=%r" % (s,))

    # --- protocol version is the code-anchored constant. This MUST match exactly;
    # a wrong/placeholder value here is a real regression (mutation thinking).
    if s.get("protocolVersion") != EXPECTED_PROTOCOL_VERSION:
        raise AssertionError(
            "expected protocolVersion=%r, got %r"
            % (EXPECTED_PROTOCOL_VERSION, s.get("protocolVersion")))

    # --- port is a real positive port number (live branch put server.getPort()).
    port = s.get("port")
    if not isinstance(port, int) or port <= 0:
        raise AssertionError("expected a positive integer port, got %r" % (port,))

    # --- tool counts: the registry is populated, so both are positive ints and
    # enabled <= total. A broken registry snapshot (0 tools) would fail here.
    total = s.get("totalTools")
    enabled = s.get("enabledTools")
    if not isinstance(total, int) or total <= 0:
        raise AssertionError("expected totalTools to be a positive int, got %r" % (total,))
    if not isinstance(enabled, int) or enabled <= 0:
        raise AssertionError("expected enabledTools to be a positive int, got %r" % (enabled,))
    if enabled > total:
        raise AssertionError(
            "enabledTools (%r) must not exceed totalTools (%r)" % (enabled, total))

    # --- version strings are present and non-empty (plugin + EDT identity).
    for key in ("pluginVersion", "edtVersion"):
        val = s.get(key)
        if not isinstance(val, str) or not val.strip():
            raise AssertionError("expected non-empty string %s, got %r" % (key, val))

    # --- the three security/diagnostic booleans must be real booleans (the tool
    # deliberately emits booleans, never the token value or the checks-folder path).
    for key in ("plainTextMode", "checksFolderConfigured", "authEnabled"):
        if not isinstance(s.get(key), bool):
            raise AssertionError(
                "expected boolean %s, got %r" % (key, s.get(key)))

    # --- formRenderFlags: the blank-screenshot diagnostic. BOTH named keys are the
    # load-bearing contract; their presence proves the nested object was built.
    flags = s.get("formRenderFlags")
    if not isinstance(flags, dict):
        raise AssertionError("expected formRenderFlags object, got %r" % (flags,))
    for fk in ("nativeFormBufferedLayoutRender", "nativeFormLayoutRender"):
        if fk not in flags:
            raise AssertionError(
                "formRenderFlags must contain key %r; got keys %r"
                % (fk, sorted(flags.keys())))
        if not isinstance(flags[fk], bool):
            raise AssertionError(
                "formRenderFlags[%r] must be a boolean, got %r" % (fk, flags[fk]))

    # SECURITY contract: the snapshot must NEVER leak the raw auth token or the
    # checks-folder path — only the derived booleans above. Assert no token/path
    # key sneaked into the payload.
    for forbidden in ("authToken", "token", "checksFolder"):
        if forbidden in s:
            raise AssertionError(
                "snapshot must NOT expose %r (security: booleans only); snapshot=%r"
                % (forbidden, s))

    assert_no_diff("a diagnosis/read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative / robustness matrix
#
# The tool has NO parameters (no required/enum/path/XOR), so there is no
# client-reachable VALIDATION error to assert error-quality on. The matrix below
# is therefore robustness coverage (graceful handling of an unexpected arg),
# which IS client-reachable and would catch a real regression.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_server_status", kind="read")
def test_ignores_unexpected_argument_gracefully():
    # The tool declares no params and reads none. An unknown extra argument must
    # be IGNORED, not cause a failure. A tool that rejected/crashed on an unknown
    # key would be a robustness regression -> this test would then fail.
    r = call("get_server_status", {"bogusUnknownArg_e2e": "x"})
    assert_ok(r, "unexpected extra arg must be ignored, not error")

    # And the snapshot must still be the same well-formed payload: the extra arg
    # changed nothing, so the stable protocolVersion + running flag still hold.
    s = r.structured
    if not isinstance(s, dict):
        raise AssertionError(
            "expected the same JSON snapshot despite the extra arg; got %r" % (s,))
    if s.get("running") is not True:
        raise AssertionError(
            "extra arg must not disturb the snapshot; expected running=true, got %r"
            % (s.get("running"),))
    if s.get("protocolVersion") != EXPECTED_PROTOCOL_VERSION:
        raise AssertionError(
            "extra arg must not disturb the snapshot; expected protocolVersion=%r, got %r"
            % (EXPECTED_PROTOCOL_VERSION, s.get("protocolVersion")))

    assert_no_diff("an ignored-arg call must not touch the project on disk")
