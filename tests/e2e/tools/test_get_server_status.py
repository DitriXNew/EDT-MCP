"""
e2e tests for get_server_status (kind: read).

Self-diagnosis tool. It takes NO input parameters (getInputSchema() is an empty
JsonSchemaBuilder.object().build()) and execute() reads NOTHING from the params
map. ResponseType is JSON, so the real payload is a dict in r.structured (NOT
r.text — for a JSON tool r.text is just the placeholder/content text).

It returns a fixed snapshot of the running server:
  port, running, transports { http, ipc }, protocolVersion, pluginVersion, edtVersion,
  enabledTools, totalTools, plainTextMode, checksFolderConfigured, authEnabled,
  formRenderFlags { nativeFormLayoutRender, nativeFormBufferedLayoutRender },
  activeCalls [ {tool, projectName?, startedAtMs, elapsedMs, responseSent?} ] and
  runningJobs [ {jobId, tool, startedAtMs, elapsedMs} ] (#993: what is running now).

Stable, code-anchored expectations (from the real impl, verified against source):
  - protocolVersion == McpConstants.PROTOCOL_VERSION == "2025-11-25"
  - running == true  (we are talking to a LIVE server on :8765, so server!=null
    and server.isRunning() must be true; a broken tool that hardcodes the
    headless `false` branch would fail here)
  - totalTools / enabledTools are positive ints (registry is populated)
  - formRenderFlags carries BOTH named JVM-flag keys, each with the render mode
    sampled at plugin activation (the diagnostic for a blank form screenshot).
    EDT binds the modes and its offscreen handler once at class initialization.
    atStartup records the mode at MCP activation; optional requested is the raw
    current system property. Changing the launch configuration requires an EDT restart.

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

import json
import threading
import time
import urllib.request

import harness
from harness import (
    call, assert_ok, assert_no_diff, e2e_test, PROJECT,
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

    if s.get("hostKind") != "WINDOWED":
        raise AssertionError("Windowed HTTP e2e must report hostKind=WINDOWED")

    transports = s.get("transports")
    if not isinstance(transports, dict) or transports.get("http") is not True:
        raise AssertionError("HTTP e2e requires an active HTTP transport")
    if not isinstance(transports.get("ipc"), bool):
        raise AssertionError("IPC listener status must be a boolean")
    if set(transports) != {"http", "ipc"}:
        raise AssertionError("Transport status must contain only listener booleans")

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
    # load-bearing contract; each must expose the render mode at plugin activation.
    flags = s.get("formRenderFlags")
    if not isinstance(flags, dict):
        raise AssertionError("expected formRenderFlags object, got %r" % (flags,))
    for fk in ("nativeFormLayoutRender", "nativeFormBufferedLayoutRender"):
        if fk not in flags:
            raise AssertionError(
                "formRenderFlags must contain key %r; got keys %r"
                % (fk, sorted(flags.keys())))
        state = flags[fk]
        if not isinstance(state, dict):
            raise AssertionError(
                "formRenderFlags[%r] must be an object, got %r" % (fk, state))
        if state.get("atStartup") not in {"on", "off", "unknown"}:
            raise AssertionError(
                "formRenderFlags[%r].atStartup must be on, off, or unknown; got %r"
                % (fk, state.get("atStartup")))
        if "effective" in state:
            raise AssertionError(
                "formRenderFlags[%r] must NOT claim an effective mode: the renderer binds it at "
                "HippoLayoutService class-init and this tool cannot observe that moment; got %r"
                % (fk, state))
        if "requested" in state and not isinstance(state["requested"], str):
            raise AssertionError(
                "formRenderFlags[%r].requested must be a string when present; got %r"
                % (fk, state["requested"]))

    # SECURITY contract: the snapshot must NEVER leak the raw auth token or the
    # checks-folder path — only the derived booleans above. Assert no token/path
    # key sneaked into the payload.
    for forbidden in ("authToken", "token", "checksFolder", "secret", "socket"):
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


# ──────────────────────────────────────────────────────────────────────────────
# Running work (#993): a call that is still executing is visible to a second request
# ──────────────────────────────────────────────────────────────────────────────

# wait_for_break with an applicationId no session can have blocks for exactly its `timeout`
# and then answers {hit:false, reason:"timeout"} (see test_wait_for_break.py): a call whose
# duration is set by the test, not by the stand's load, and that touches no model.
SLOW_TOOL = "wait_for_break"
SLOW_SECONDS = 8
NO_SUCH_APP = "attach:NoSuchApp_ZZZ_e2e993"


def _post_on_this_session(request_id, tool, arguments):
    """A tools/call on the harness's session, sent from a background thread. harness._post keeps
    a global request counter, so the thread sends its own envelope with its own id instead."""
    body = json.dumps({"jsonrpc": "2.0", "id": request_id, "method": "tools/call",
                       "params": {"name": tool, "arguments": arguments}}).encode("utf-8")
    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": harness.PROTOCOL_VERSION,
    }
    if harness._SESSION_ID:
        headers["Mcp-Session-Id"] = harness._SESSION_ID
    req = urllib.request.Request(harness.MCP_URL, data=body, headers=headers)
    with urllib.request.urlopen(req, timeout=harness.CALL_TIMEOUT) as resp:
        return resp.read().decode("utf-8", "replace")


def _entries(snapshot, tool):
    return [c for c in (snapshot.get("activeCalls") or []) if c.get("tool") == tool]


@e2e_test(tool="get_server_status", kind="read")
def test_lists_a_running_call_of_another_request_and_drops_it_once_answered():
    """A call still executing is listed by get_server_status asked on another request, with its
    tool, its projectName argument and a growing elapsedMs; the asking call itself is not listed;
    once the slow call has answered its entry is gone. The registry keeps the projectName argument
    of ANY call (wait_for_break ignores it), so the test passes one to see it reported - and an
    applicationId value it must NOT report."""
    outcome = {}

    def slow():
        try:
            outcome["response"] = _post_on_this_session(
                993001, SLOW_TOOL,
                {"applicationId": NO_SUCH_APP, "timeout": SLOW_SECONDS, "projectName": PROJECT})
        except Exception as e:  # reported by the main thread
            outcome["error"] = e

    worker = threading.Thread(target=slow, name="e2e-993-slow-call", daemon=True)
    started = time.time()
    worker.start()
    seen = None
    last = None
    try:
        # Poll, never sleep for a fixed time: the entry must appear while the slow call runs.
        while time.time() - started < SLOW_SECONDS - 1 and worker.is_alive():
            r = call("get_server_status", {})
            assert_ok(r, "get_server_status while another call runs")
            last = r.structured or {}
            if "activeCalls" not in last:
                raise AssertionError(
                    "get_server_status must report activeCalls (the calls running now); keys=%r"
                    % sorted(last))
            if any(c.get("tool") == "get_server_status" for c in last["activeCalls"]):
                raise AssertionError(
                    "the asking get_server_status call must not list itself: %r"
                    % (last["activeCalls"],))
            found = _entries(last, SLOW_TOOL)
            if found and found[0].get("elapsedMs", 0) > 0:
                seen = found[0]
                break
            time.sleep(0.2)
    finally:
        worker.join(SLOW_SECONDS + harness.CALL_TIMEOUT)

    if "error" in outcome:
        raise AssertionError("the slow %s call failed: %r" % (SLOW_TOOL, outcome["error"]))
    if seen is None:
        raise AssertionError(
            "a running %s call was never listed in activeCalls within %ss; last snapshot=%r"
            % (SLOW_TOOL, SLOW_SECONDS, last))
    if seen.get("projectName") != PROJECT:
        raise AssertionError("the entry must carry the call's projectName %r: %r" % (PROJECT, seen))
    if not isinstance(seen.get("startedAtMs"), int) or abs(seen["startedAtMs"] / 1000.0 - started) > 60:
        raise AssertionError("startedAtMs must be the call's epoch start in ms: %r" % (seen,))
    if "responseSent" in seen:
        raise AssertionError("nobody interrupted the call, so no responseSent: %r" % (seen,))
    if NO_SUCH_APP in json.dumps(seen, ensure_ascii=False):
        raise AssertionError("an argument other than projectName must never be reported: %r" % (seen,))
    if not isinstance(last.get("runningJobs"), list):
        raise AssertionError("runningJobs must always be present as an array: %r" % (last,))

    # The reply may arrive as an SSE frame (the request accepts text/event-stream): parse it like the harness.
    answered = harness.Result(harness._parse_response(outcome["response"]))
    assert_ok(answered, "the slow call itself must succeed with its timeout status")
    if (answered.structured or {}).get("reason") != "timeout":
        raise AssertionError("the slow call must answer reason=timeout: %r" % (answered.structured,))

    # The entry is removed before the response is built, so the first look after the answer is final.
    after = call("get_server_status", {})
    assert_ok(after, "get_server_status after the slow call answered")
    if _entries(after.structured or {}, SLOW_TOOL):
        raise AssertionError(
            "an answered call must no longer be listed: %r" % ((after.structured or {}).get("activeCalls"),))

    assert_no_diff("listing running calls must not touch the project on disk")