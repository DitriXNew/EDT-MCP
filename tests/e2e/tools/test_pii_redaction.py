"""
e2e tests for the 152-FZ PII redactor (issue #242) — the OFF-path (CI-safe) gate.

The redactor is cross-cutting infra, NOT an MCP tool: it runs at the SINGLE
wire-serialization choke point (McpProtocolHandler.handleToolCall) on the response
of every tool flagged `returnsInfobaseData` — in v1 that set is get_variables,
evaluate_expression and wait_for_break (get_event_log joins it when #243 merges).
It is toggled in Preferences (Window -> Preferences -> MCP Server) and defaults
**OFF**; a bidirectional env override `EDT_MCP_PII_REDACTION` set at the EDT/CI launch
wins over the preference (mirrors EDT_MCP_DESTRUCTIVE_CONSENT) — on/true/1/yes/enabled
force redaction ON, off/false/0/no/disabled force it OFF. Because
there is NO new MCP tool, tools/list is unchanged (no golden change) and there is
no XxxToolTest.

WHAT THIS SUITE PROVES (the CI-safe half):
  The redactor's headline invariant is "OFF -> byte-identical to a build without the
  redactor": the choke point returns the tool's result string UNCHANGED (no JSON
  parse / re-serialize) when redaction is disabled. In a normal headless run
  redaction is OFF (default, and/or the env kill-switch), so every flagged tool must
  come back as its plain, un-redacted response. We assert exactly that:
    * the flagged tool returns its documented UN-redacted baseline (the same sentinel
      the sibling test_get_variables / test_evaluate_expression / test_wait_for_break
      suites pin) — proving the OFF choke point is a pure pass-through, and
    * the payload carries NO redaction artifact (no pseudonym `Физлицо#hhhhhhhh`
      token — `#` + 8 hex — no `[redacted]` mask marker), and
    * the payload is DETERMINISTIC across two identical calls (a redactor that leaked
      into the OFF path — e.g. a nondeterministic pseudonym, or a Gson round-trip that
      reformats whitespace — would perturb the bytes and fail this), and
    * a debug/read tool never touches the project on disk (assert_no_diff).

WHAT THIS SUITE DOES NOT PROVE (the ON half — a documented manual/live step):
  The ON -> redacted assertion needs (a) redaction turned ON in Preferences (there is
  deliberately NO management tool to flip it over the wire) AND (b) a real personal-
  data VALUE sitting in a suspended debug frame (a running infobase + a breakpoint on
  BSL that binds e.g. a Физлицо / СНИЛС local). Neither is available in headless CI,
  so that leg is an EXECUTABLE-DOC skip below (test_on_path_redaction_is_manual_live)
  that records the exact manual recipe rather than silently omitting it.

DIFF: these are debug/runtime tools operating on the Eclipse debug model of a running
infobase, never on the git-tracked project, so every test asserts assert_no_diff().

Registered under the synthetic tool name "_pii_redaction" (like the coverage ratchet's
"_coverage_ratchet"): the redactor is not a tool, so this must not be read as extra
per-tool coverage, and it must not trip the write-metadata model-reset path.
"""

import json
import re

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_contains,
    assert_no_diff,
    e2e_test,
    E2ESkip,
)

# A pseudonym like `Физлицо#a3f2b1c0` ends in `#` + 8 hex chars (Pseudonymizer.shortHmac
# formats the first 4 HMAC bytes with `%02x%02x%02x%02x` = 8 hex); a full mask reads the
# literal `[redacted]` (Pseudonymizer.MASK, lowercase). NONE of these appear in an
# un-redacted debug sentinel, so their ABSENCE is a robust "the OFF path did not redact"
# signal. We match the real artifacts the production code emits: the 8-hex token, the
# literal pseudonym prefix (Pseudonymizer.PREFIX), and the mask (case-insensitively).
_PSEUDONYM_TOKEN = re.compile(r"#[0-9a-fA-F]{8}\b")
# The literal pseudonym prefix Pseudonymizer.PREFIX ("Физлицо"); a leaked pseudonym is
# `<PREFIX>#<8 hex>`, so the prefix itself is a strong artifact signal.
_PSEUDONYM_PREFIX = "Физлицо"
# Mask markers, checked case-insensitively; the real mask is Pseudonymizer.MASK = "[redacted]".
_MASK_MARKERS = ("[redacted]", "***masked***")

# A synthetic applicationId that cannot match any running session here, used to drive
# wait_for_break's benign explicit-id timeout path (no auto-resolution).
_NO_SUCH_APP = "attach:NoSuchApp_pii_e2e"


def _result_bytes(result):
    """Deterministic serialization of the TOOL RESULT payload the client sees (content
    + structuredContent + isError). Excludes the JSON-RPC envelope `id`, which the
    harness increments per request — so two identical calls yield identical bytes iff
    the payload itself is byte-stable (the OFF-path invariant)."""
    return json.dumps(result.result, sort_keys=True, ensure_ascii=False)


def _assert_no_redaction_artifact(blob, ctx):
    """The serialized payload must carry NO redaction artifact (the OFF path did not
    rewrite anything)."""
    if _PSEUDONYM_TOKEN.search(blob):
        raise AssertionError(
            "OFF-path response contains a pseudonym-shaped `#hhhhhhhh` (8-hex) token "
            "[%s]: %s" % (ctx, blob[:300]))
    if _PSEUDONYM_PREFIX in blob:
        raise AssertionError(
            "OFF-path response contains the pseudonym prefix %r [%s]: %s"
            % (_PSEUDONYM_PREFIX, ctx, blob[:300]))
    lowered = blob.lower()
    for marker in _MASK_MARKERS:
        if marker in lowered:
            raise AssertionError(
                "OFF-path response contains a redaction marker %r [%s]: %s"
                % (marker, ctx, blob[:300]))


# ──────────────────────────────────────────────────────────────────────────────
# OFF-path (default) — each flagged tool returns its UN-redacted baseline, verbatim
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="_pii_redaction", kind="read")
def test_get_variables_off_default_is_unredacted_passthrough():
    """get_variables (returnsInfobaseData) with redaction OFF (default) -> its plain,
    un-redacted no-session sentinel, byte-stable, with no redaction artifact.

    Mutation-sensitive: a redactor that leaked into the OFF path would either rewrite
    the sentinel text (failing the fragment asserts), inject a pseudonym/mask (failing
    the artifact assert), or perturb the bytes via a Gson round-trip (failing the
    determinism assert)."""
    r1 = call("get_variables", {})
    err = assert_error(r1, "get_variables OFF-path is a plain error sentinel")
    # The exact un-redacted baseline (same fragments test_get_variables pins). If the
    # OFF choke point had touched the payload, these would not survive verbatim.
    assert_contains(err, "no single suspended debug",
                    "OFF path must return the plain get_variables sentinel unchanged")
    assert_contains(err, "wait_for_break",
                    "OFF path must preserve the recovery hint verbatim")

    blob1 = _result_bytes(r1)
    _assert_no_redaction_artifact(blob1, "get_variables OFF")

    # Byte-stable: a second identical call yields an identical result payload (no
    # nondeterministic redaction crept into the OFF path).
    r2 = call("get_variables", {})
    if _result_bytes(r2) != blob1:
        raise AssertionError(
            "get_variables OFF-path payload is not byte-stable across identical calls:\n"
            "first : %s\nsecond: %s" % (blob1[:300], _result_bytes(r2)[:300]))
    assert_no_diff("a debug-read tool must not touch the project on disk")


@e2e_test(tool="_pii_redaction", kind="read")
def test_evaluate_expression_off_default_is_unredacted_passthrough():
    """evaluate_expression (returnsInfobaseData) with redaction OFF -> its plain
    stale-frame sentinel, byte-stable, no redaction artifact.

    A valid-shaped call (positive frameRef + non-empty expression) reaches the frame
    lookup; with no live session it returns the stale-frameRef sentinel. That is the
    un-redacted baseline the OFF choke point must pass through untouched."""
    args = {"frameRef": 999999, "expression": "Add(1, 2)"}
    r1 = call("evaluate_expression", args)
    err = assert_error(r1, "evaluate_expression OFF-path is a plain error sentinel")
    assert_contains(err, "stale frameRef",
                    "OFF path must return the plain evaluate_expression sentinel unchanged")
    assert_contains(err, "wait_for_break",
                    "OFF path must preserve the recovery hint verbatim")

    blob1 = _result_bytes(r1)
    _assert_no_redaction_artifact(blob1, "evaluate_expression OFF")

    r2 = call("evaluate_expression", args)
    if _result_bytes(r2) != blob1:
        raise AssertionError(
            "evaluate_expression OFF-path payload is not byte-stable across identical "
            "calls:\nfirst : %s\nsecond: %s" % (blob1[:300], _result_bytes(r2)[:300]))
    assert_no_diff("a debug/read tool must not touch the project on disk")


@e2e_test(tool="_pii_redaction", kind="read")
def test_wait_for_break_off_default_is_unredacted_passthrough():
    """wait_for_break (returnsInfobaseData) with redaction OFF -> its benign timeout
    status, byte-stable, no redaction artifact.

    With an explicit unknown applicationId and a tiny timeout the tool returns the
    benign success-with-status {hit:false, reason:"timeout", applicationId:<echoed>}.
    This is a SUCCESS payload (not an error), so it exercises the OFF choke point on a
    non-error JSON response too: the redactor's error-payload short-circuit is not what
    keeps it verbatim here — the OFF master toggle is."""
    args = {"applicationId": _NO_SUCH_APP, "timeout": 1}
    r1 = call("wait_for_break", args)
    assert_ok(r1, "explicit-unknown-app timeout is a benign success under OFF redaction")
    sc = r1.structured or {}
    if sc.get("hit") is not False or sc.get("reason") != "timeout":
        raise AssertionError(
            "OFF path must preserve the benign timeout status (hit:false, "
            "reason:timeout); got structured=%r" % (sc,))
    # The echoed applicationId must come back EXACTLY as sent — a redactor that treated
    # the id as data and pseudonymized it would corrupt this even though it is not PII.
    if sc.get("applicationId") != _NO_SUCH_APP:
        raise AssertionError(
            "OFF path must echo the applicationId verbatim %r; got structured=%r"
            % (_NO_SUCH_APP, sc))

    blob1 = _result_bytes(r1)
    _assert_no_redaction_artifact(blob1, "wait_for_break OFF")

    r2 = call("wait_for_break", args)
    if _result_bytes(r2) != blob1:
        raise AssertionError(
            "wait_for_break OFF-path payload is not byte-stable across identical calls:\n"
            "first : %s\nsecond: %s" % (blob1[:300], _result_bytes(r2)[:300]))
    assert_no_diff("a debug-read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# ON-path — a documented manual / live step (cannot be automated headlessly)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="_pii_redaction", kind="read")
def test_on_path_redaction_is_manual_live():
    """The ON -> redacted assertion is an EXECUTABLE-DOC skip, not a CI assertion.

    It cannot run headlessly: it needs (1) redaction turned ON in Window -> Preferences
    -> MCP Server (there is deliberately NO management tool to flip it over the wire),
    and (2) a real personal-data VALUE in a suspended debug frame (a running infobase +
    a breakpoint on BSL that binds e.g. a Физлицо / СНИЛС / e-mail local). This test
    records the manual recipe so the ON leg is explicit rather than silently omitted;
    it always SKIPs (reported as skip, never failing a headless run).

    MANUAL / LIVE recipe (stand :8766, EDT_MCP_DESTRUCTIVE_CONSENT=allow, never :8765):
      1. Window -> Preferences -> MCP Server: tick the PII redaction checkbox (ON).
         (Leave EDT_MCP_PII_REDACTION UNSET so the preference decides — the env
          override is bidirectional and wins over the preference: on/true/1/yes/enabled
          force redaction ON, off/false/0/no/disabled force it OFF.)
      2. debug_launch a config whose infobase holds real personal data; set a
         breakpoint on a line where a local binds a person's ФИО / СНИЛС / passport.
      3. wait_for_break, then get_variables / evaluate_expression on that frame.
      4. Confirm the personal-data value comes back PSEUDONYMIZED (stable `<Prefix>#hhhhhhhh`,
         `#` + 8 hex)
         or fully MASKED (special-category), and that the plugin log has one audit line
         (tool + sensitivity + count, and NO value / token->value mapping).
      5. (KNOWN v1 gap — free-form names) With redaction still ON, evaluate_expression an
         expression that returns a BARE personal NAME, e.g. `Сотрудник.ФИО` ->
         "Иванов Иван Иванович". Confirm it comes back VERBATIM (NOT redacted): the
         evaluate_expression success payload is {success,value,type,truncated,fullLength}
         with NO sibling `name`, so only the content-regex layer applies to its `value`,
         and a bare full name matches none of the structured patterns (e-mail / phone /
         СНИЛС / passport / ИНН). Contrast with the SAME ФИО read as a get_variables local
         in step 3, which IS redacted via its sibling `name`. This exposes the acknowledged
         v1 free-form-name gap that the deferred v2 metadata catalog closes.
      6. Untick the checkbox (OFF) and repeat step 3: the value now comes back VERBATIM
         (byte-identical to a no-redactor build) — the OFF invariant this suite gates.
    """
    raise E2ESkip(
        "ON-path PII redaction is a manual/live step: needs redaction toggled ON in "
        "Preferences + a real personal-data value in a suspended debug frame (see this "
        "test's docstring for the recipe). The OFF-path byte-identical invariant is the "
        "CI-safe gate covered by the sibling tests in this file.")
