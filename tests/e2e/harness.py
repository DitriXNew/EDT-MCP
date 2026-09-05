#!/usr/bin/env python3
"""
EDT-MCP e2e harness — the shared base every per-tool test imports.

Owns: the HTTP/JSON-RPC(+SSE) client, the git-fixture isolation helpers
(TestConfiguration is a committed fixture; on-disk truth is git), and all
assertion helpers including error-quality. Tests call these; they never
re-implement them. See SKILL.md for the full guide.

Python stdlib only. No third-party dependencies.
"""

import fnmatch
import hashlib
import http.client
import json
import math
import os
import re
import socket
import subprocess
import threading
import time
import urllib.request
import urllib.error

# ──────────────────────────────────────────────────────────────────────────────
# Configuration (read once at import; the orchestrator sets env BEFORE importing)
# ──────────────────────────────────────────────────────────────────────────────
MCP_HOST = os.environ.get("MCP_HOST", "127.0.0.1")
MCP_PORT = os.environ.get("MCP_PORT", "8765")
PROJECT = os.environ.get("MCP_PROJECT", "TestConfiguration")      # EDT project NAME (for MCP calls)

HARNESS_DIR = os.path.dirname(os.path.abspath(__file__))          # tests/e2e
REPO_ROOT = os.path.abspath(os.path.join(HARNESS_DIR, "..", ".."))
# The 1C fixture lives under tests/ (grouped with this suite + the YAXUnit test
# extension), so its git path is NOT the same as the EDT project name. Keep the
# two decoupled: PROJECT is the name MCP calls use; PROJECT_REL is the git path.
# Override with MCP_PROJECT_REL if the fixture is relocated again.
PROJECT_REL = os.environ.get("MCP_PROJECT_REL", "tests/" + PROJECT)  # git path rel to repo root (fwd slashes for git)
PROJECT_DIR = os.path.join(REPO_ROOT, *PROJECT_REL.split("/"))       # absolute project dir

# Wall-clock when this run started (module import happens once, before the first test).
# The EDT log ratchet uses it to look only at entries THIS run produced, so a stale workspace
# log full of yesterday's noise cannot fail - or silently pass - today's run.
RUN_STARTED_AT = time.time()

# The YAXUnit test suite lives in a SEPARATE EDT extension project (V8ExtensionNature)
# named "<base>.tests" — breakpoints in the test modules resolve against THIS project,
# not the base configuration. Override with MCP_TESTS_PROJECT if the layout changes.
TESTS_PROJECT = os.environ.get("MCP_TESTS_PROJECT", PROJECT + ".tests")
# Git path of the extension fixture (its EDT project dir is "tests", under tests/), kept
# decoupled from the extension's EDT NAME like PROJECT_REL. The tests only READ it, but a
# stale EDT model can autosave a manual editor edit back to it, so the end-of-run cleanup
# reverts this too and re-syncs the model — a session must leave the WHOLE tree clean.
TESTS_PROJECT_REL = os.environ.get("MCP_TESTS_PROJECT_REL", "tests/tests")

# The EXTERNAL-OBJECTS fixture (V8ExternalObjectsNature, issue #309): a project whose roots
# are its own external data processors / reports, linked to TestConfiguration as its base.
# It is a THIRD project kind - neither a configuration nor an extension - and the tools
# resolved FQNs in it against the BASE configuration until #309 was fixed.
EXT_OBJECTS_PROJECT = os.environ.get("MCP_EXT_OBJECTS_PROJECT", "ExternalObjects")
EXT_OBJECTS_REL = os.environ.get("MCP_EXT_OBJECTS_REL", "tests/ExternalObjects")

# Opt-in gate for the ATTENDED live-infobase round-trip suite (test_live_roundtrip.py).
# Those tests drive a REAL 1C runtime-client launch / debug session against a running
# infobase with YAXUnit installed — heavy, stateful, and absent in headless CI. They
# SKIP (E2ESkip) unless this is set, so a normal `run_all.py` stays green without an
# infobase. Set EDT_MCP_LIVE_INFOBASE=1 (attended) to actually run them.
LIVE_INFOBASE = os.environ.get("EDT_MCP_LIVE_INFOBASE", "").strip() not in ("", "0", "false", "no")
# Launch configuration name the live suite drives (a runtime-client config that points
# at the TestConfiguration infobase). Override with MCP_LIVE_LAUNCH_CONFIG.
LIVE_LAUNCH_CONFIG = os.environ.get("MCP_LIVE_LAUNCH_CONFIG", "TestConfiguration Thin Client")

MCP_URL = "http://%s:%s/mcp" % (MCP_HOST, MCP_PORT)
HEALTH_URL = "http://%s:%s/health" % (MCP_HOST, MCP_PORT)

# Per-CALL HTTP timeout (seconds). A single MCP call that exceeds this raises a socket
# timeout instead of blocking forever; run_all's per-TEST timeout is the outer backstop
# that fails the test and aborts the run. Generous by default — clean_project can
# legitimately take a while, especially right after a -clean relaunch. Override with
# MCP_CALL_TIMEOUT.
CALL_TIMEOUT = float(os.environ.get("MCP_CALL_TIMEOUT", "180"))

# Budget (seconds) for reset_model to out-wait the derived-data recompute a write-metadata
# test schedules. A rename of a REFERENCED object (e.g. a common module) keeps the project
# BUILDING for a long time while EDT revalidates its dependents; clean_project is REFUSED
# until that settles, so reset_model must wait at least this long before (and after) the
# clean — otherwise the clean is refused, the model is left un-reset, and the NEXT rename
# blocks for minutes on EDT's still-draining pipeline (DerivedDataManager.blockAsyncPipeline).
# Defaults to the ready timeout but never below 300s (a short local default would expire
# mid-drain). The per-test --test-timeout must exceed it. Override with E2E_MODEL_SETTLE_TIMEOUT.
MODEL_SETTLE_TIMEOUT = int(os.environ.get(
    "E2E_MODEL_SETTLE_TIMEOUT",
    str(max(int(os.environ.get("E2E_PROJECT_READY_TIMEOUT", "180")), 300))))

# reset_model's POST-CONDITION probe: objects the committed fixture always has and no test
# may leave renamed or deleted (a rename test that targets one must be reverted by the same
# reset). Resolving them is the only direct evidence that clean_project's re-import actually
# landed — 'clean_project ok' + 'project ready' can both hold while the model still carries
# the previous test's write (see reset_model).
#
# It is a LIST because a change INSIDE one object is all this brace can see, and one object is
# not a representative sample of the fixture. It deliberately does NOT have to enumerate every
# object the suite RENAMES: naming - an object created, deleted or renamed - is the INVENTORY
# brace's job (_top_object_inventory), which sees the whole top level in one call and therefore
# cannot rot as tests are added. Keeping the two braces to their own questions is what stopped
# this list from being a hand-maintained mirror of the suite: it once had to list the renamed
# CommonModule.Calc, and the next rename test to be written (CascadeEn) was of course not added
# to it, so the reset certified a model that still carried the rename.
#
# Override the whole set with E2E_BASELINE_PROBE_FQN (comma-separated) if the fixture's
# canonical objects are ever renamed.
BASELINE_PROBE_FQNS = [
    fqn.strip() for fqn in os.environ.get(
        "E2E_BASELINE_PROBE_FQN",
        "Catalog.Catalog,CommonModule.Calc,CommonModule.OK").split(",")
    if fqn.strip()
]

# Kept as the single-value alias some tests/messages still read.
BASELINE_PROBE_FQN = BASELINE_PROBE_FQNS[0]

# get_metadata_details reports a PER-OBJECT failure under this heading inside an otherwise
# SUCCESSFUL response, so it is how the probe tells "the model answered" from "the model answered
# that my probe objects are gone". It is the tool's documented structural marker for the section.
_DETAILS_ERROR_HEADING = "## Errors"

# How many full revert + clean_project cycles reset_model may spend getting the model back
# to the baseline before it gives up and stops the run. >1 because the lost race it recovers
# from (an async disk export landing after the revert) is one-shot: the second cycle reverts
# what that export wrote and re-imports it. Not a timeout knob — each cycle re-does the work,
# it does not merely wait longer. Override with E2E_MODEL_RESET_ATTEMPTS.
MODEL_RESET_ATTEMPTS = int(os.environ.get("E2E_MODEL_RESET_ATTEMPTS", "3"))

# Within ONE such cycle, the two ways it can fail have SEPARATE budgets, because they are
# separate failures with separate diagnoses and separate fixes:
#   * the project never reports ready, so clean_project would only be refused — waiting is the
#     only remedy, and the fix is a longer E2E_MODEL_SETTLE_TIMEOUT;
#   * clean_project itself keeps coming back isError — waiting does not help, EDT does.
# Sharing one budget let three failed settles consume the whole allowance and then report
# "clean_project did not succeed in 3 attempts" — a verdict on a call that had never been made,
# and an abort that had never once tried the thing it was aborting over.
MODEL_CLEAN_ATTEMPTS = int(os.environ.get("E2E_MODEL_CLEAN_ATTEMPTS", "3"))
MODEL_SETTLE_ATTEMPTS = int(os.environ.get("E2E_MODEL_SETTLE_ATTEMPTS", "3"))

# Transient "Project is building ... Please wait and retry" refusal: when a call arrives
# while EDT is still recomputing derived data (heaviest right after a big write-metadata
# test, and slow to drain on an under-powered CI runner), the server REFUSES it UPFRONT
# with this message and NO side effect. That message is never an assertion target, so
# call() transparently re-issues the SAME call until it clears (or this budget elapses) —
# stabilizing the slower matrix legs without masking real results (a genuine success/error
# returns unchanged on the next attempt). Override with E2E_BUILDING_RETRY_TIMEOUT.
BUILDING_RETRY_TIMEOUT = int(os.environ.get("E2E_BUILDING_RETRY_TIMEOUT", "120"))
# The server's derived-data "still building, retry" refusal, in any response channel.
_BUILDING_REFUSAL_RE = re.compile(r"Project is building|Please wait and retry", re.IGNORECASE)

# The longest ONE logical call() can take: the retry loop keeps re-issuing a building refusal for
# BUILDING_RETRY_TIMEOUT, the attempt in flight when that elapses still gets its full CALL_TIMEOUT,
# and the backoff sleep before it is capped at 10s. Budgeting a clean_project at CALL_TIMEOUT alone
# understates all three.
_LOGICAL_CALL_CEILING = math.ceil(CALL_TIMEOUT) + BUILDING_RETRY_TIMEOUT + 10

# The longest wait_for_project_ready(MODEL_SETTLE_TIMEOUT) can actually take. Its deadline gates
# the START of each poll, so the poll in flight when the budget runs out still gets its own full
# ceiling, plus the 2s sleep between polls. Budgeting the settle at MODEL_SETTLE_TIMEOUT alone
# understates it - and understating it here is not conservative, it is the opposite: the budget
# would run out early and cut off a clean_project attempt the pre-change code always made.
_SETTLE_CEILING = MODEL_SETTLE_TIMEOUT + _LOGICAL_CALL_CEILING + 2

# One settle plus one clean_project: the MCP part of a revert+clean iteration, which is all of it
# that can plausibly run long. The git revert between them is local and unbounded only in theory.
_RESET_CYCLE_CEILING = _SETTLE_CEILING + _LOGICAL_CALL_CEILING

# Wall-clock budget for ONE revert+clean cycle. It exists for one purpose: to stop the SEPARATE
# attempt counters above from multiplying iterations. By count alone the worst case doubled, from
# CLEAN iterations to CLEAN+SETTLE ones, which is time the single shared budget never had.
#
# Sized at the full CLEAN_ATTEMPTS cycles so that in the ORDINARY failure - clean_project simply
# being refused, settle after settle succeeding - every configured attempt starts with room to
# spare and the budget is never what ends the cycle. It is NOT a promise that the counters always
# win: interleave a settle failure before each refused clean and the time runs out first (with the
# defaults, 612+922+612+922 = 3068 against a 2766 budget), which is the trade being made on
# purpose - the counters alone permit CLEAN+SETTLE iterations, roughly twice what the single shared
# budget ever had. What must not happen is that the abort then LIES about it, and it does not:
# _clean_failure_cause names the budget when the budget is what stopped it.
#
# A budget, NOT a hard stop. Neither a settle nor a clean_project can be cancelled once started and
# the deadline is only checked where an iteration BEGINS, so the cycle can overrun it by the one
# iteration already under way - worst case CLEAN_ATTEMPTS+1 cycles, against CLEAN_ATTEMPTS before
# the split and CLEAN_ATTEMPTS+SETTLE_ATTEMPTS without a budget at all. The pathological case was
# already above run_all's --test-timeout before this branch (on CI one cycle alone can approach
# 35 minutes); this narrows the regression rather than pretending to close it.
MODEL_RESET_BUDGET = int(os.environ.get(
    "E2E_MODEL_RESET_BUDGET", str(MODEL_CLEAN_ATTEMPTS * _RESET_CYCLE_CEILING)))

# MCP protocol version this client speaks (sent as the MCP-Protocol-Version header,
# per the 2025-11-25 Streamable HTTP transport spec).
PROTOCOL_VERSION = os.environ.get("MCP_PROTOCOL_VERSION", "2025-11-25")

_REQUEST_ID = 0
# Captured from the server's InitializeResult response (Mcp-Session-Id header). When
# the server issues one, every subsequent request MUST echo it (2025-11-25 spec).
# Our server is currently session-less, so this stays None and nothing is sent.
_SESSION_ID = None


# ──────────────────────────────────────────────────────────────────────────────
# Errors
# ──────────────────────────────────────────────────────────────────────────────
class E2EAssertion(Exception):
    """Raised when an e2e assertion fails (a normal test failure)."""


class E2ECallTimeout(Exception):
    """One MCP call exceeded MCP_CALL_TIMEOUT.

    NOT the same as a failed call: the request was never answered, so the server may still be
    RUNNING it - a write tool keeps mutating the model after we walk away. Measured on CI: a
    rename_metadata_object the client abandoned at 300s completed server-side at 301s
    ("Completed tools/call: rename_metadata_object in 301090ms, outcome=ok" followed by
    "Client connection lost: Broken pipe") and left the object renamed, so the next test failed
    on a fixture nobody had touched. Nothing here can cancel that call, so the run must STOP
    rather than read - or try to reset - a model somebody else is still writing.
    """


class E2EModelResetFailed(Exception):
    """clean_project could not be gotten to succeed within its retry budget - raised by both
    reset_model() (per-test model cleanup) and final_cleanup() (start/end-of-run sync), and by
    either one's FINAL settle wait reporting the project still not ready afterward.

    Either way the in-memory model may still carry an unsynchronised change (the just-finished
    test's write, or whatever a stale session/manual edit left behind), and the next reader - the
    next test, or a caller trusting a "clean" run - would inherit it. Silently continuing is what
    used to turn one real failure into two (or report a run green over a model that was never
    actually back in sync), so this is raised rather than swallowed, and callers must not treat
    it as best-effort.
    """


class E2ESkip(Exception):
    """Raised to SKIP a test (an unmet precondition, not a failure).

    The orchestrator reports these as `skip` and does NOT count them against the
    run, so the gated live-infobase suite stays out of the way of a headless run.
    """


def _fail(msg):
    raise E2EAssertion(msg)


def requires_live_infobase(reason=""):
    """Gate an ATTENDED live-infobase test. Raises E2ESkip unless EDT_MCP_LIVE_INFOBASE
    is set, so the test is skipped (not failed) in a normal headless run. Call this as
    the FIRST line of every test in test_live_roundtrip.py."""
    if not LIVE_INFOBASE:
        raise E2ESkip(
            "live-infobase round-trip skipped (set EDT_MCP_LIVE_INFOBASE=1 to run)"
            + (": " + reason if reason else ""))


# ──────────────────────────────────────────────────────────────────────────────
# MCP client (real black-box client over HTTP; handles SSE framing)
# ──────────────────────────────────────────────────────────────────────────────
class Result:
    def __init__(self, raw):
        self.raw = raw
        result = raw.get("result", {}) if isinstance(raw, dict) else {}
        self.result = result
        self.is_error = bool(result.get("isError", False))
        self.structured = result.get("structuredContent")
        self.text = _extract_text(result)
        self.rpc_error = raw.get("error") if isinstance(raw, dict) else None

    def error_text(self):
        """Best-effort human-readable error string (structured.error, then text, then rpc error)."""
        if isinstance(self.structured, dict) and self.structured.get("error"):
            return str(self.structured.get("error"))
        if self.text:
            return self.text
        if self.rpc_error:
            return str(self.rpc_error.get("message", self.rpc_error))
        return ""


def _extract_text(result):
    content = result.get("content") or []
    if content and isinstance(content[0], dict):
        c0 = content[0]
        if c0.get("text"):
            return c0["text"]
        res = c0.get("resource")
        if isinstance(res, dict) and res.get("text"):
            return res["text"]
    return ""


def _parse_response(text):
    """Parse a Streamable-HTTP response body: a bare JSON object, or SSE event frames.

    Robust to multiple events and `event:`/`id:`/`data:` lines (the 2025-11-25
    transport may stream several messages); returns the last JSON-RPC response
    object (the one carrying result/error)."""
    t = text.strip()
    if t.startswith("{"):
        return json.loads(t)
    events, cur = [], []
    for line in t.splitlines():
        if line.startswith("data:"):
            cur.append(line[5:].lstrip())
        elif not line.strip():
            if cur:
                events.append("\n".join(cur))
                cur = []
    if cur:
        events.append("\n".join(cur))
    for payload in reversed(events):
        try:
            obj = json.loads(payload)
            if isinstance(obj, dict) and ("result" in obj or "error" in obj):
                return obj
        except Exception:
            pass
    return json.loads(t)  # last resort: raise with detail


# Set once a call times out. After that the server is still busy with work nobody can cancel,
# so EVERY later call is refused HERE rather than at each call site: a test-level `finally` that
# tears down its fixture (delete_project, delete_metadata, remove_breakpoint) would otherwise race
# the request we walked away from. The run is over at that point; the latch just makes it true
# everywhere instead of only where someone remembered to check.
_TIMED_OUT = False
_ABORT_REASON = "an earlier call timed out and may still be running"


def _post(method, params):
    global _REQUEST_ID, _SESSION_ID
    _REQUEST_ID += 1
    body = json.dumps({
        "jsonrpc": "2.0", "id": _REQUEST_ID, "method": method, "params": params,
    }).encode("utf-8")
    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": PROTOCOL_VERSION,
    }
    if _SESSION_ID:
        headers["Mcp-Session-Id"] = _SESSION_ID
    if _TIMED_OUT:
        raise E2ECallTimeout(
            "refusing to send %s: %s, so the run is over. (The latch, not a new timeout - see "
            "_TIMED_OUT.)" % (method, _ABORT_REASON))
    req = urllib.request.Request(MCP_URL, data=body, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=CALL_TIMEOUT) as resp:
            sid = resp.headers.get("Mcp-Session-Id")
            if sid:
                _SESSION_ID = sid
            text = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        try:
            text = e.read().decode("utf-8", "replace")
        except (TimeoutError, socket.timeout) as body_timeout:
            # Headers arrived, the body did not - still a call we cannot account for.
            _arm_timeout_latch()
            raise E2ECallTimeout(_call_timeout_message(body_timeout))
    except urllib.error.URLError as e:
        # A socket read timeout arrives either bare or wrapped in URLError, depending on the
        # Python build; only the timeout becomes E2ECallTimeout - a refused connection is a
        # different failure and must keep its own traceback.
        if isinstance(getattr(e, "reason", None), (TimeoutError, socket.timeout)):
            _arm_timeout_latch()
            raise E2ECallTimeout(_call_timeout_message(e))
        raise
    except (TimeoutError, socket.timeout) as e:
        _arm_timeout_latch()
        raise E2ECallTimeout(_call_timeout_message(e))
    return _parse_response(text)


def _arm_timeout_latch():
    """Remember that a call was abandoned - see _TIMED_OUT."""
    abort_further_calls("an earlier call timed out and may still be running")


def abort_further_calls(reason):
    """Refuse every SUBSEQUENT MCP call AND every fixture reset, whatever is still holding the wire.

    Armed by a call timeout and by the orchestrator when it abandons a timed-out worker. Both
    mean the same thing: something we cannot cancel is still working, and every later request -
    a test's own teardown, a reset_model still in flight on the abandoned thread - would race it.
    Refusing at the ONE place they all pass through beats hoping each caller checks a flag.

    The git tree is frozen by the SAME act, because it is the same fact: a `git checkout` while
    EDT may still be exporting races a write we cannot see. Deriving both from one condition is
    the point - keeping two flags in step across two threads is how the window reopens.

    @return freeze_fixtures()'s verdict: False when a fixture reset was still running and did not
            finish within FIXTURE_FREEZE_WAIT, which the caller should say out loud"""
    global _TIMED_OUT, _ABORT_REASON
    _TIMED_OUT = True
    _ABORT_REASON = reason
    return freeze_fixtures()


# Failures that mean the request MAY have reached the server and its outcome is UNKNOWN - the only
# ones that justify stopping the run over a write. Deliberately not "any exception":
#   * a body we could not build (json.dumps on a bad argument) never left this process;
#   * a response we could not parse DID arrive, so nothing is still in flight;
# neither leaves work running that a cleanup could race, and aborting a suite over either would be
# a false alarm. http.client's own exceptions are listed because a truncated body (IncompleteRead)
# is not an OSError and is exactly the uncertain case.
_UNCERTAIN_TRANSPORT_ERRORS = (E2ECallTimeout, OSError, http.client.HTTPException)


def calls_aborted():
    """Has the latch been armed - i.e. is something uncancellable believed to be still running?

    The orchestrator asks after every test. A test can arm it without failing in a way the runner
    would otherwise recognise (a mutating request that died on the wire errors that ONE test), and
    from that moment every later call is refused anyway - so continuing would only manufacture
    cascade failures against tests that did nothing wrong."""
    return _TIMED_OUT


def abort_reason():
    """Why calls are being refused, in words fit to print. Meaningless unless calls_aborted()."""
    return _ABORT_REASON


def _call_timeout_message(cause):
    return ("no response in %gs (MCP_CALL_TIMEOUT). The server may still be RUNNING this call, "
            "so the model is not safe to read or even to reset - the run stops here. Check the "
            "EDT log for the matching 'Completed tools/call: ... in Nms' line to see whether it "
            "finished late (raise MCP_CALL_TIMEOUT) or never finished (a real hang). %s"
            % (CALL_TIMEOUT, cause))


def _is_transient_building(result):
    """Whether a Result is the transient derived-data "Project is building ... please wait
    and retry" refusal — surfaced in any channel (an isError text, or a structured
    success=false envelope). The call was refused with NO side effect, so re-issuing it is
    safe and correct (it is never an assertion target)."""
    try:
        blob = json.dumps(result.raw, ensure_ascii=False)
    except (TypeError, ValueError):
        blob = str(result.raw)
    return bool(_BUILDING_REFUSAL_RE.search(blob))


def call(tool, arguments):
    """Send tools/call and return a Result.

    Transparently retries the transient "Project is building ... please wait and retry"
    refusal (derived data still recomputing — the call was refused with no side effect, so
    re-issuing the SAME call is safe). That message is never asserted on, so retrying it
    until it clears stabilizes the slower matrix legs without masking a real success/error
    (which returns unchanged on the next attempt). Bounded by BUILDING_RETRY_TIMEOUT; on
    expiry the building refusal is returned so the test fails loudly rather than hanging."""
    deadline = time.time() + BUILDING_RETRY_TIMEOUT
    attempt = 0
    # Record the attempt BEFORE issuing it, once for the whole retry loop. If the request dies
    # without a parseable answer (connection reset, truncated body, timeout), the server may
    # still have committed the write — recording only on the way out left the shortcut believing
    # nothing happened, so it skipped the reset and the next test inherited the mutation. An
    # unknown outcome counts as a mutation; a REFUSAL that was actually read back takes it back.
    _record_attempt(tool)
    while True:
        try:
            raw = _post("tools/call", {"name": tool, "arguments": arguments})
        except _UNCERTAIN_TRANSPORT_ERRORS:
            # A MUTATING request that died IN FLIGHT is the same situation as one that timed out,
            # and gets the same treatment. The socket is gone; the server-side handler is not
            # necessarily gone with it, and it may still be committing the write. Cleaning up on top
            # of that - git-reverting the fixture, then clean_project - RACES it: the late commit or
            # export lands on the restored tree and leaks into the next test. So arm the same latch
            # a timeout arms, which also freezes the fixtures, and let the run stop. Reading a write
            # back is what makes it safe to undo; nothing else does.
            if tool in MODEL_MUTATION_TOOLS and not _TIMED_OUT:
                abort_further_calls(
                    "a %s request died in flight, so the server may still be executing it" % tool)
            raise
        result = Result(raw)
        if not _is_transient_building(result) or time.time() >= deadline:
            _record_outcome(tool, result.is_error, result.structured)
            return result
        attempt += 1
        time.sleep(min(2 * attempt, 10))


# ── Model-reset shortcut: don't pay for a reset when nothing was changed ──────────────
#
# The write-metadata cleanup (reset_fixture + reset_model) dominates the whole suite: 331
# tests, ~3560 s, ~11 s each, while the tool call itself is a fraction of a second. Most of
# those tests are NEGATIVE - they hand a write tool a bad argument and assert the refusal -
# and refusing changes nothing, so the clean_project they pay for re-imports a model that
# never moved.
#
# The shortcut is decided on EVIDENCE, never on a guess about what a tool "probably" did:
# after the test the fixture must be git-clean AND the model's top-object inventory must
# equal the snapshot taken before the suite ran. Failing either, the full reset runs. On top
# of that, a test that invoked one of the DEEP tools always pays in full - those mutate
# broadly enough (cascades, cross-object rewrites, whole-configuration import) that an
# unchanged inventory is not evidence of an unchanged model.
DEEP_MUTATION_TOOLS = frozenset({
    "rename_metadata_object", "delete_metadata", "adopt_metadata_object",
    "update_database", "import_configuration_from_xml", "resync_to_disk",
    "clean_project", "create_project", "delete_project",
})

# Tools that change the BM model. A SUCCESSFUL call, an observed post-commit error, or an error
# whose mutating API cannot report rollback forfeits the shortcut, whatever later evidence says.
#
# Because the evidence has a blind spot, and this closes it: a metadata write can succeed
# with persisted=false — the transaction changed the in-memory model while the fixture stays
# git-clean — and a NESTED change (an attribute added to an existing catalog) leaves the
# top-object inventory identical. Both probes then report "pristine" while the next test
# inherits an in-memory child. A refusal changes nothing, so negative tests — the ones the
# shortcut is actually for — still qualify.
# Kept honest by test_mutation_set_ratchet.py: a tool that extends AbstractMetadataWriteTool on
# the Java side and is missing here fails the suite. Hand-maintained membership silently rots -
# apply_quick_fix landed on master mutating the model, and this set did not know about it.
MODEL_MUTATION_TOOLS = frozenset({
    "create_metadata", "modify_metadata", "write_module_source",
    "apply_quick_fix", "build_external_objects",
    # dcs authors schemas / settings / dynamic lists. It belongs here rather than in
    # DEEP_MUTATION_TOOLS because an ordinary refusal does not move the model: the writer validates
    # the request before the first eSet. The exception is a post-commit force-export scheduling
    # failure; every post-commit error carries mutationCommitted:true, and an opaque in-flight
    # failure carries mutationOutcomeUnknown:true. Both forfeit the shortcut without making an
    # ordinary negative test do so.
    "dcs",
    # Writers whose write happens OUTSIDE our code: both call LanguageTool through reflection.
    # Their entry points now mark an exception after invoke() as outcome-unknown, but source
    # scanning still cannot discover their membership; the ratchet pins their names explicitly.
    "generate_translation_strings", "translate_configuration",
}) | DEEP_MUTATION_TOOLS

_CALLED_TOOLS = set()
_BASELINE_INVENTORY = None
_BASELINE_DETAILS = None

# A mutating call that succeeded, committed before failing, or entered an opaque mutation whose
# rollback outcome is unknown. Any one is enough to forfeit the shortcut for the whole test.
_MUTATION_CONFIRMED = False
# Mutating calls issued whose outcome was never read back (connection reset, truncated body,
# timeout). The server may well have committed them, so while this is non-zero the model counts
# as moved. A call that throws never reaches _record_outcome, so it stays counted - which is
# the point.
#
# It is deliberately NOT per-test. An unknown outcome is not a fact about the test that issued
# it, it is a fact about the MODEL, and it stays true until something re-establishes the
# baseline. Clearing it in begin_test_calls() (as the per-test counters are) threw away exactly
# the evidence it was collected for: the test that issued the call would reset in full - fine -
# but a test that did NOT declare kind='write-metadata' gets no reset at all, and the next
# write test would then start from a cleared counter and happily take the shortcut over a model
# carrying an uncommitted write. So only a VERIFIED restore clears it (see _mark_model_synced),
# and until then every write test pays in full: slower, never wrong.
_MUTATIONS_UNRESOLVED = 0


def _record_attempt(tool):
    """Called ONCE per logical call, before the request goes out."""
    global _MUTATIONS_UNRESOLVED
    _CALLED_TOOLS.add(tool)
    if tool in MODEL_MUTATION_TOOLS:
        _MUTATIONS_UNRESOLVED += 1


def _record_outcome(tool, is_error, structured):
    """Called once the server's answer has actually been read.

    Mutation-bearing failures are identified by boolean response fields, never their prose.
    ToolResult emits mutationCommitted for an observed commit and mutationOutcomeUnknown for an
    entered opaque/in-flight mutation, so wording changes cannot accidentally re-arm the shortcut.
    """
    global _MUTATIONS_UNRESOLVED, _MUTATION_CONFIRMED
    if tool not in MODEL_MUTATION_TOOLS:
        return
    _MUTATIONS_UNRESOLVED = max(0, _MUTATIONS_UNRESOLVED - 1)
    mutation_committed = (isinstance(structured, dict)
                          and structured.get("mutationCommitted") is True)
    mutation_unknown = (isinstance(structured, dict)
                        and structured.get("mutationOutcomeUnknown") is True)
    if not is_error or mutation_committed or mutation_unknown:
        _MUTATION_CONFIRMED = True


def _mark_model_synced():
    """Called ONLY where the model was just proven to be back on the baseline.

    That proof (reset_model verifying _baseline_mismatch) is what retires an
    unknown outcome: whatever the abandoned request may or may not have committed, the model has
    since been re-imported from the clean disk and checked. Nothing else may clear it."""
    global _MUTATIONS_UNRESOLVED
    _MUTATIONS_UNRESOLVED = 0


def _model_may_have_moved():
    """True when a write succeeded, or when one was issued and its fate is unknown."""
    return _MUTATION_CONFIRMED or _MUTATIONS_UNRESOLVED > 0


def mutations_unresolved():
    """Was a mutating call issued whose outcome nobody ever read? (public: the orchestrator asks)

    It is the one piece of this evidence a test's DECLARED kind cannot be trusted to cover. A test
    declares kind='write-metadata' when it means to write, and the orchestrator resets those; a
    request that died on the wire is by definition not what the test meant to do, and it can hit a
    test of any kind. So this question is asked of every test, not just the declared writers."""
    return _MUTATIONS_UNRESOLVED > 0


def begin_test_calls():
    """Start recording what a test invokes (the orchestrator calls this per test).

    Resets only what is genuinely per-test. _MUTATIONS_UNRESOLVED is not - see its comment."""
    global _MUTATION_CONFIRMED
    _CALLED_TOOLS.clear()
    _MUTATION_CONFIRMED = False


def _top_object_inventory():
    """A stable, cheap fingerprint of the model's top-level metadata objects.

    One call. It sees exactly the mutations a git-clean tree can still hide: an object
    created, deleted or renamed IN MEMORY without reaching disk. Returns None when it cannot
    be read, which callers must treat as "no evidence" (and therefore reset in full).

    A TIMEOUT is not "no evidence" and must not be swallowed: E2ECallTimeout means the request
    may STILL BE RUNNING server-side and it arms the global latch, so absorbing it here would
    let the run continue and pin the latched failure on the next innocent test - or start a git
    reset while EDT is still writing. It propagates, like every other probe's."""
    try:
        r = call("get_metadata_objects", {"projectName": PROJECT, "limit": 1000})
    except E2ECallTimeout:
        raise
    except Exception:
        return None
    if r.is_error or not (r.text or "").strip():
        return None
    return "\n".join(sorted(line.strip() for line in r.text.splitlines() if line.strip()))


def _probe_details():
    """The DETAIL text of BASELINE_PROBE_FQNS, or None when it cannot be read AS EVIDENCE.

    None means "no evidence", and every caller treats it as such. The distinction that matters is
    that an EMPTY body is also no evidence: an unexplained blank answer is not a fingerprint, and
    the one thing it must never do is become the thing later answers are compared against - a
    stored "" makes any later blank-but-not-error response compare EQUAL and certify a model
    nobody read.

    A TIMEOUT propagates, like every other probe's: it arms the global latch and means the request
    may still be running server-side, so absorbing it here would let the run continue and pin the
    latched failure on the next innocent test."""
    try:
        r = call("get_metadata_details",
                 {"projectName": PROJECT, "objectFqns": list(BASELINE_PROBE_FQNS)})
    except E2ECallTimeout:
        raise
    except Exception:
        return None
    if r.is_error:
        return None
    text = (r.text or "").strip()
    if not text:
        return None
    # is_error is NOT the whole story. get_metadata_details reports a PER-OBJECT failure inside a
    # SUCCESSFUL response - a "## Errors" section saying "Object not found" - so a body in which
    # every probe object is missing arrives as a perfectly good result. Storing that as the
    # baseline would make the model's WORST state canonical, and every later reset would then be
    # certified by reproducing it. A body that reports a probe object missing is not evidence.
    #
    # Matched as an anchored HEADING, not as free text anywhere in the document. The heading is the
    # tool's documented structural marker for this condition, while a bare substring search would
    # also fire on a fixture object that merely MENTIONS it - in a synonym, a comment, a module
    # body - and that false positive is expensive: it would drop the detail brace for good and
    # then fail every reset's post-condition.
    if any(line.lstrip().startswith(_DETAILS_ERROR_HEADING) for line in text.splitlines()):
        return None
    return text


def snapshot_model_baseline():
    """Record the pristine fingerprints once, before the first test runs.

    Two of them, because they answer different questions: the INVENTORY sees a top object that
    appeared, vanished or was renamed, while the DETAIL of the probed objects sees a change
    INSIDE one - a property, a child, a synonym - which the inventory cannot. Together they are
    what makes "the model did not move" an observation rather than an assumption.

    Either may come back unreadable, and then it is simply not recorded - the shortcut degrades to
    the evidence that IS available (and to no shortcut at all when the inventory is missing). What
    it must not do is record something unusable AS the baseline; see _probe_details.

    @return (inventory_captured, details_captured) so the caller can say which brace it lost."""
    global _BASELINE_INVENTORY, _BASELINE_DETAILS
    _BASELINE_INVENTORY = _top_object_inventory()
    _BASELINE_DETAILS = _probe_details()
    return (_BASELINE_INVENTORY is not None, _BASELINE_DETAILS is not None)


def model_is_pristine():
    """Is there POSITIVE evidence that the model still matches the committed baseline?

    False whenever the evidence is missing or ambiguous - the caller then does the full
    reset, so a wrong answer here costs time, never correctness.

    SETTLE FIRST, then look. A metadata write's disk export is ASYNC: read git the moment the
    test returns and a write that has not flushed yet reads as "clean", the reset is skipped,
    and the export lands later - inside some LATER test, which then fails for a change it
    never made. That is not hypothetical; skipping the settle did exactly this to
    modify_metadata::test_subsystem_content_reject_subsystem_member. reset_model settles for
    the same reason before it reverts, and the settle is the cheap half of it - the expensive
    half is the clean_project this shortcut is trying to avoid."""
    if _BASELINE_INVENTORY is None or _model_may_have_moved():
        return False
    if _CALLED_TOOLS & DEEP_MUTATION_TOOLS:
        return False
    try:
        # Its VERDICT matters, not just that it ran: it returns False on timeout, meaning EDT is
        # still building. The whole reason to settle here is that a pending async export would
        # otherwise land after the git/inventory snapshots and leak into a later test - so a
        # settle that did not finish is exactly the case where the evidence must not be trusted.
        if not wait_for_project_ready():
            return False
    except E2ECallTimeout:
        raise
    except Exception:
        return False
    try:
        if all_fixtures_status().strip():
            return False
    except E2EAssertion:
        # git itself failed (locked index, filesystem hiccup). all_fixtures_status refuses to read
        # that as "clean", and rightly so - but here a raise would fail the finished test over its
        # cleanup. It is simply no evidence, and no evidence means the full reset.
        return False
    # The same question the reset post-condition asks, deliberately in the same words: a shortcut
    # that skips the reset on weaker evidence than the reset itself accepts is a hole of exactly
    # the same shape. It carries the third brace with it - a change INSIDE an object leaves both
    # the tree and the top-object list identical.
    return _baseline_mismatch() is None


def _notify(method, params):
    """Send a JSON-RPC notification (no id, no response expected)."""
    global _SESSION_ID
    body = json.dumps({"jsonrpc": "2.0", "method": method, "params": params}).encode("utf-8")
    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": PROTOCOL_VERSION,
    }
    if _SESSION_ID:
        headers["Mcp-Session-Id"] = _SESSION_ID
    req = urllib.request.Request(MCP_URL, data=body, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            resp.read()  # notifications return 202 Accepted / empty body
    except urllib.error.HTTPError:
        pass


def initialize(capabilities=None):
    """MCP lifecycle handshake: initialize -> capture session id -> notifications/initialized.

    Per the 2025-06-18 / 2025-11-25 spec the client MUST send initialize first and
    then the initialized notification before normal operations. Done once at startup."""
    result = _post("initialize", {
        "protocolVersion": PROTOCOL_VERSION,
        "capabilities": capabilities or {},
        "clientInfo": {"name": "edt-mcp-e2e", "version": "1"},
    })
    _notify("notifications/initialized", {})
    return result


def wait_for_server(timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(HEALTH_URL, timeout=5) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError("MCP server not reachable at %s" % HEALTH_URL)


def _workspace_dir(list_projects_markdown=None):
    """Locate the EDT workspace holding .metadata/.log.

    Explicit env wins. Otherwise infer it: a workspace almost always contains at least one
    project of its own (the Servers container, for one), so walk each project's ancestors
    looking for a .metadata/.log. Returns None when it cannot be found - callers decide whether
    missing diagnostics should be skipped or merely reported as unavailable.

    @param list_projects_markdown a list_projects table the caller ALREADY holds. Pass it from
    any path that must not touch the wire: a call() that times out arms the global abort latch
    (abort_further_calls), which refuses every later MCP call AND every fixture reset - so a
    diagnostic that issued one could destroy the very reset it was called to explain.
    """
    override = os.environ.get("EDT_MCP_EDT_WORKSPACE")
    if override:
        return override if os.path.isfile(os.path.join(override, ".metadata", ".log")) else None

    if list_projects_markdown is not None:
        text = list_projects_markdown
    else:
        text = call("list_projects", {}).text or ""
    for raw in re.findall(r"[A-Za-z]:\\[^|\s]+|/(?:[^/|\s]+/)*[^|\s]+", text):
        candidate = raw.rstrip("\\/ `")
        for _ in range(4):
            candidate = os.path.dirname(candidate)
            if not candidate:
                break
            if os.path.isfile(os.path.join(candidate, ".metadata", ".log")):
                return candidate
    return None


def _all_edt_projects_ready(list_projects_markdown, not_ready=None):
    """True when every EDT project in the list_projects table reads 'ready'.

    Two kinds of row are skipped, because neither can ever become ready and neither can serve a
    tool: one KNOWN to be non-EDT (`EDT Project` = No), and one that is CLOSED (`Open` = No).
    A closed project reads 'not_available' forever - EDT is deliberately not building it, which
    is the entire point of closing a heavy configuration - so blocking on it aborts every local
    run on such a workspace before the first test starts. A test that actually targets a closed
    project still fails on its own, through the per-tool ProjectStateChecker guard, with a message
    naming that project instead of a mute suite-level timeout. Everything else, "-" included,
    keeps blocking: a real project that is genuinely building must never be mistaken for one that
    cannot become ready.

    A workspace that hosts a 1C STANDALONE SERVER
    contains the WST container project ("Servers", `EDT Project` = No, no natures), which is
    permanently 'not_available' because it is not an EDT project at all and can never become
    ready. A plain substring scan for 'not_available' over the whole table therefore never
    succeeds on such a workspace: the suite waited out the full timeout and aborted with "the
    configuration did not finish indexing" while every real project had been ready all along.

    Falls back to a conservative substring scan when no row can be parsed (an output-format
    change must not degrade to a permanent "ready"). When `not_ready` is supplied,
    fill it with the blocking (project name, state) pairs from this same parse so timeout callers
    can report which project prevented progress without parsing the table again.
    """
    rows = []
    for line in list_projects_markdown.splitlines():
        line = line.strip()
        if not line.startswith("|") or set(line) <= set("|- "):
            continue  # separator row (or an empty one)
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) < 5 or cells[0].lower() == "name":
            continue  # header row, or a table shape this parse does not know
        rows.append(cells)
    if not rows:
        low = list_projects_markdown.lower()
        blocking_states = [state for state in ("building", "not_available") if state in low]
        if not_ready is not None:
            not_ready[:] = [("<unparsed project table>", state) for state in blocking_states]
        return not blocking_states
    blocking_projects = []
    for cells in rows:
        state, is_open = cells[1].strip().lower(), cells[3].strip().lower()
        edt_project = cells[4].strip().lower()
        if edt_project == "no":
            continue  # a KNOWN non-EDT project (the standalone server's "Servers" container)
        if is_open == "no":
            continue  # closed on purpose: it will never leave 'not_available' by itself
        if state in ("building", "not_available"):
            blocking_projects.append((cells[0], state))
    if not_ready is not None:
        not_ready[:] = blocking_projects
    return not blocking_projects


def _projects_not_ready_message(timeout, projects):
    states = ", ".join("%s=%s" % (name, state) for name, state in projects)
    return "projects not ready after %ds: %s" % (timeout, states or "states unavailable")


_PROJECT_READY_OBSERVED_LIMIT = 20


def _store_project_ready_progress(progress, changed, observed, polls, start,
                                  last_list_projects):
    """Publish one completed wait's observations without leaving stale caller-owned keys."""
    if progress is None:
        return
    progress.clear()
    progress.update({
        "changed": changed,
        "observed": [list(snapshot) for snapshot in observed],
        "polls": polls,
        "elapsed": int(time.time() - start),
        # The reset diagnostic needs the exact final response, not a reconstructed table.
        "last_list_projects": last_list_projects,
    })


def wait_for_project_ready(timeout=None, failure_details=None, progress=None):
    """Wait until every EDT project is fully indexed (state 'ready') — i.e. none is still
    'building' its derived data AND none is 'not_available' (mid (re)load). Non-EDT projects
    are ignored (see _all_edt_projects_ready): a standalone server's "Servers" container is
    permanently 'not_available' and would otherwise block every run on such a workspace.

    After a `-clean` relaunch the MCP port opens (wait_for_server) BEFORE EDT finishes
    indexing, so a cascade/mutation tool (rename / delete / create) run too early would
    hit a project whose state is 'building'. A heavy preceding run (lots of
    clean_project / reset_model) can also leave a project transiently 'not_available'
    or 'building' while EDT recomputes the reference index — a debug launch / breakpoint
    against such a project fails with "Project build in progress (derived data not
    complete)". list_projects reports each project's state value, so poll until none
    reads 'building' OR 'not_available'.

    Timeout: the local dev loop indexes a warm workspace fast, but a COLD cloud runner
    (first-time index of the whole config, modest 2-core CPU) takes several minutes, so
    the default is overridable via E2E_PROJECT_READY_TIMEOUT (seconds). Progress is
    logged periodically so a slow cloud run is visibly "still indexing", not hung.

    Best-effort: returns True once ready (or if state cannot be read), False on timeout.
    If `failure_details` is a list, a timeout replaces its contents with one diagnostic naming
    the last parsed blocking projects and their states. If `progress` is a dict, completion
    replaces its contents with whether the blocking project/state snapshot ever changed, the
    observed snapshots (consecutive duplicates removed and capped), the poll count, elapsed
    seconds, and the final raw list_projects text. The per-tool ProjectStateChecker guard is the
    real safety net — this only removes the test-timing flake so a normal run starts on a
    fully-indexed workspace.
    """
    if timeout is None:
        timeout = int(os.environ.get("E2E_PROJECT_READY_TIMEOUT", "180"))
    start = time.time()
    deadline = start + timeout
    # Seed last_log with `start` (not 0) so a SHORT wait stays silent: this function is
    # also called after every write-metadata test (the model briefly re-indexes and is
    # ready again within ~2s), and each such call would otherwise emit one immediate
    # "still indexing" line — a confusing wall of identical "1199s left" entries (each a
    # fresh call at t≈0, not one stuck wait). Logging only after 15s of ACTUAL waiting
    # suppresses that churn and makes the counter visibly count DOWN during a genuine
    # long cold-index wait.
    last_log = start
    last_not_ready = []
    last_snapshot = None
    observed = []
    changed = False
    polls = 0
    last_list_projects = ""
    while time.time() < deadline:
        try:
            polls += 1
            text = call("list_projects", {}).text or ""
            last_list_projects = text
            if text:
                not_ready = []
                ready = _all_edt_projects_ready(text, not_ready=not_ready)
                snapshot = tuple(sorted(not_ready))
                if last_snapshot is not None and snapshot != last_snapshot:
                    changed = True
                if last_snapshot is None or snapshot != last_snapshot:
                    observed.append(snapshot)
                    if len(observed) > _PROJECT_READY_OBSERVED_LIMIT:
                        observed.pop(0)
                last_snapshot = snapshot
                if ready:
                    _store_project_ready_progress(
                        progress, changed, observed, polls, start, last_list_projects)
                    return True
                if not_ready:
                    last_not_ready = not_ready
        except E2ECallTimeout:
            # The one failure a best-effort catch must NOT swallow: the server is still running
            # that call, so retrying - or reporting success - hides it from the runner, the only
            # place that can stop the run before the next test reads a model it is still writing.
            raise
        except Exception:
            pass
        now = time.time()
        if now - last_log >= 15:
            print("  [wait_for_project_ready] config still indexing (%ds elapsed, %ds left of %ds)..."
                  % (int(now - start), int(deadline - now), timeout), flush=True)
            last_log = now
        time.sleep(2)
    if failure_details is not None:
        failure_details[:] = [_projects_not_ready_message(timeout, last_not_ready)]
    _store_project_ready_progress(
        progress, changed, observed, polls, start, last_list_projects)
    return False


def settle_or_fail(what):
    """Wait for the project to be ready, and FAIL the test if it never is.

    The precondition form of wait_for_project_ready, and the only one a test should use. Calling
    the bare function and dropping its answer is a trap that has now been walked into twice in
    this suite: the settle gets added precisely because a still-building EDT breaks the test, and
    then the code proceeds into that exact state when the wait expires - looking fixed, behaving
    as before. There is no sensible way to continue from a False here, so it is not a decision a
    caller should be offered.

    Failing (rather than skipping) is deliberate: a project that cannot reach ready within the
    ready timeout is a broken environment, and a run that quietly skipped its way past that would
    report green over tests nobody executed.

    @param what a short phrase naming what was about to run, for the message
    """
    failure_details = []
    if not wait_for_project_ready(failure_details=failure_details):
        _fail("%s, so EDT is still recomputing derived data - %s would be measuring that "
              "recompute, not itself." % (failure_details[0], what))


# ──────────────────────────────────────────────────────────────────────────────
# git fixture (TestConfiguration is the committed baseline; on-disk truth = git)
# ──────────────────────────────────────────────────────────────────────────────
def _git(*args, timeout=None):
    # Decode git output as UTF-8 explicitly. With bare text=True, Python uses the
    # platform locale codepage (cp125x on Windows), which mangles UTF-8 content in
    # `git diff` — Cyrillic BSL bodies came back as mojibake and substring checks
    # missed them.
    #
    # core.quotepath=false: by default git quotes non-ASCII PATHS as C-style octal
    # escapes ('?? "tests/.../\320\241..."'), so the `line[3:]` path parsing in
    # assert_diff_contains / tree_snapshot got a quoted-escaped string that
    # os.path.isfile() cannot resolve — a freshly created Cyrillic-named object
    # (e.g. Catalogs/Сережка/Сережка.mdo) was silently skipped and its content
    # never searched (the ё-normalization e2e failures). With quotepath=false git
    # emits the raw UTF-8 bytes, which this explicit utf-8 decoding handles.
    return subprocess.run(
        ["git", "-C", REPO_ROOT, "-c", "core.quotepath=false", *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout,
    )


# Every fixture project. The BASE is the one most tests mutate (reset before every test); the
# EXTENSION and the EXTERNAL-OBJECTS project are touched only by their own files, and the
# end-of-run cleanup reverts all three.
ALL_FIXTURE_RELS = [PROJECT_REL, TESTS_PROJECT_REL, EXT_OBJECTS_REL]


def _reset_rel(rel):
    """Hard-revert one fixture path to the committed baseline (HEAD).

    Metadata delete/rename/create operations persist to disk AND can leave the change
    STAGED in the index (observed: a renamed-to module appears as `A` staged). The
    revert therefore: (1) `reset` to UNSTAGE (staged add -> untracked; staged delete ->
    unstaged delete), (2) `checkout HEAD --` to restore tracked files (undo deletions /
    mods / renames-from), (3) `clean -fd` to remove the now-untracked files. Plain
    `checkout --` (from the index) cannot undo staged changes, so all three are needed."""
    _git("reset", "-q", "--", rel)
    _git("checkout", "HEAD", "--", rel)
    _git("clean", "-fd", rel)


# Held for the duration of a git fixture reset, and by whoever freezes the fixtures. It is what
# makes "may I touch the tree?" and "I am touching the tree" ONE decision instead of two.
#
# A plain flag cannot do that. The abandoning thread sets it while the abandoned worker is between
# its own check and its `git checkout` - a window a slow settle makes seconds wide - and the worker
# then resets a tree the main thread has just declared untouchable, racing writes the server may
# still be performing. Re-reading the flag closer to the git call only makes the window smaller.
#
# The lock is held only across the git commands (fast and local), never across an MCP call, so
# freeze_fixtures() waits for at most one reset and cannot deadlock behind a wedged EDT.
_FIXTURE_LOCK = threading.RLock()
_FIXTURES_FROZEN = False

# How long freeze_fixtures() waits for a reset already in progress. Three local git commands, so
# anything beyond this is a stall (a locked index, an unresponsive filesystem), not slowness - and
# then reporting it beats blocking the thread that has to write the run's summary.
FIXTURE_FREEZE_WAIT = float(os.environ.get("E2E_FIXTURE_FREEZE_WAIT", "60"))

# Ceiling on a git STATUS read (see _git_checked). Not applied to the reverting commands: a
# checkout/clean can legitimately take a while on a big change, while a scoped status cannot.
GIT_STATUS_TIMEOUT = float(os.environ.get("E2E_GIT_STATUS_TIMEOUT", "120"))


def freeze_fixtures():
    """Forbid every later fixture reset, and wait out any that is already running.

    Called when a worker is abandoned: from that moment nobody - the abandoned worker included -
    may touch the tree, because the server may still be writing to it.

    The wait is BOUNDED, and the flag is set either way. git subprocesses have no timeout of their
    own, so a `git checkout` stalled on a locked index or an unresponsive filesystem would hold the
    lock indefinitely - and this is called from the main thread on the per-test timeout path, i.e.
    exactly where blocking forever means the run never prints its summary or writes its JUnit
    report. Setting the flag is a plain assignment and needs no lock; what the lock buys is the
    guarantee that no reset is IN PROGRESS when we return, and that guarantee is reported rather
    than waited for indefinitely.

    @return True if no fixture reset is running any more, False if one is stuck and the caller
            should treat the tree as being touched by somebody else"""
    global _FIXTURES_FROZEN
    _FIXTURES_FROZEN = True
    if not _FIXTURE_LOCK.acquire(timeout=FIXTURE_FREEZE_WAIT):
        return False
    _FIXTURE_LOCK.release()
    return True


def fixtures_frozen():
    """Whether the fixtures have been declared untouchable (see freeze_fixtures)."""
    return _FIXTURES_FROZEN


def reset_fixture():
    """Hard reset the BASE fixture to HEAD. Called before EVERY test (never trust the prev).

    @return True if the reset ran, False if the fixtures are frozen and it was refused."""
    with _FIXTURE_LOCK:
        if _FIXTURES_FROZEN:
            return False
        _reset_rel(PROJECT_REL)
        return True


def reset_fixture_rel(rel):
    """Hard reset ONE fixture path to HEAD - for a test file that mutates a fixture other
    than the base project (the external-objects one, say), which reset_fixture() does not cover.

    @return True if the reset ran, False if the fixtures are frozen and it was refused."""
    with _FIXTURE_LOCK:
        if _FIXTURES_FROZEN:
            return False
        _reset_rel(rel)
        return True


def status_porcelain_rel(rel):
    """git status --porcelain scoped to one fixture path (see _status_porcelain)."""
    return _git_checked("status", "--porcelain", "--", rel).stdout.rstrip("\r\n")


def assert_no_diff_rel(rel, ctx=""):
    """The given fixture path must be clean - assert_no_diff for a non-base fixture."""
    st = status_porcelain_rel(rel)
    if st:
        _fail("expected NO change to %s but found [%s]:\n%s" % (rel, ctx, st[:500]))


def diff_rel(rel):
    """git diff scoped to one fixture path."""
    return _git("diff", "--", rel).stdout


def read_fixture_file(rel, relpath):
    """Read a file inside a fixture path other than the base project."""
    with open(os.path.join(REPO_ROOT, *rel.split("/"), *relpath.split("/")), encoding="utf-8") as f:
        return f.read()


def reset_all_fixtures():
    """Hard reset EVERY fixture path (base + extension) to HEAD — used by the end-of-run
    cleanup so the whole working tree returns to the committed baseline.

    @return True if the reset ran, False if the fixtures are frozen and it was refused."""
    with _FIXTURE_LOCK:
        if _FIXTURES_FROZEN:
            return False
        for rel in ALL_FIXTURE_RELS:
            _reset_rel(rel)
        return True


def _status_porcelain():
    # Strip only TRAILING newlines. A bare .strip() also eats the LEADING space of the
    # first porcelain line (status column "XY" -> " M file" becomes "M file"), which
    # shifts the fixed-width `line[3:]` path slice by one and breaks path parsing in
    # assert_diff_contains / assert_diff_paths. Leading whitespace is significant here.
    return _git_checked("status", "--porcelain", "--", PROJECT_REL).stdout.rstrip("\r\n")


def diff():
    return _git("diff", "--", PROJECT_REL).stdout


def read_disk(relpath):
    with open(os.path.join(PROJECT_DIR, relpath), encoding="utf-8") as f:
        return f.read()


# ── Markdown table parsing (this project's OWN presentation contract) ──────────
#
# Splits on a '|' COLUMN DELIMITER but never on an escaped '\|'. This mirrors the
# production writer: MarkdownUtils.escapeForTable turns a literal '|' inside a cell's
# own text into '\|' precisely so it cannot be mistaken for a delimiter. A naive
# str.split("|") does not know about that escape, so it cuts an escaped cell at the
# WRONG point: the row then yields MORE cells than the table has columns, and a caller
# filtering on an exact column count silently DROPS that row - a real row, quietly
# invisible to the test. Any e2e test parsing a table this project rendered must go
# through here (see CLAUDE.md pre-push item #10). Covered by test_markdown_table.py.
_MD_CELL_SPLIT = re.compile(r"(?<!\\)\|")


def split_markdown_row(line):
    r"""Splits one rendered '| c1 | c2 | ... |' table row into its cell strings.

    Delimiters are unescaped '|' only; each returned cell is stripped and has its
    '\|' escapes turned back into a literal '|', so a cell's value equals the text
    production was given. Returns [] for a line that is not a table row.
    """
    if line is None:
        return []
    stripped = line.strip()
    if not stripped.startswith("|"):
        return []
    parts = _MD_CELL_SPLIT.split(stripped)
    # A well-formed row's OUTER delimiters produce an empty string before the first and
    # after the last real cell - drop those two BY POSITION, never by stripping '|'
    # characters off the ends (that would eat a genuine trailing '\|' in the last cell).
    if len(parts) >= 2 and parts[0] == "" and parts[-1] == "":
        parts = parts[1:-1]
    return [p.strip().replace("\\|", "|") for p in parts]


# How many differing objects an abort message names before it says "and N more". A reset that
# cannot get the model home has usually lost ONE object; a difference of dozens is a different
# failure entirely (the wrong project, a truncated listing) and the first few names say so just
# as well as all of them would.
_MAX_DIFF_NAMES = 12


def _named(lines):
    """A set of inventory lines as a short, readable list of object names.

    The inventory is a markdown TABLE, so its lines are "| Name | Synonym | ... |" - printing them raw
    turns a one-object difference into a wall of pipes. Only the Name (and the Type, when the row
    has the width the tool documents) carries diagnosis; anything that is not a row - a heading, a
    total, the separator - is worth printing verbatim, because a changed total IS the finding.
    """
    out = []
    for line in sorted(lines):
        # split_markdown_row, not a hand split on "|": a synonym or comment cell may contain an
        # ESCAPED pipe, and splitting on every "|" would shift every cell after it - printing a
        # confident wrong Type. It returns [] for anything that is not a row.
        cells = split_markdown_row(line)
        if cells and cells[0]:
            # 6 columns, or 7 with the extension Origin column (get_metadata_objects). Any other
            # width is not the table this reads, so name the object and claim nothing else.
            out.append("%s (%s)" % (cells[0], cells[3]) if len(cells) in (6, 7) else cells[0])
        else:
            out.append(line)
    if len(out) > _MAX_DIFF_NAMES:
        return "%s and %d more" % (", ".join(out[:_MAX_DIFF_NAMES]), len(out) - _MAX_DIFF_NAMES)
    return ", ".join(out)


def _inventory_difference(current):
    """The top objects that differ between `current` and the captured baseline, as prose.

    A reset that cannot get the model home aborts the run, and the abort is the only artifact
    anyone reads afterwards - so it must say WHAT is wrong. "the model still does not resolve
    Catalog.Catalog" (a name that was never the problem) cost a full investigation to see
    through; "in the model but not in the baseline: Reckoner / in the baseline but not in the
    model: CascadeEn" is the same failure, already diagnosed."""
    have = set(current.splitlines())
    want = set((_BASELINE_INVENTORY or "").splitlines())
    extra = _named(have - want)
    missing = _named(want - have)
    parts = []
    if extra:
        parts.append("in the model but not in the baseline: %s" % extra)
    if missing:
        parts.append("in the baseline but not in the model: %s" % missing)
    # Equal sets with unequal text means the two rendered the same names differently - possible
    # only if the listing itself changed shape, which is worth saying rather than swallowing.
    return "; ".join(parts) or "the top-object listing changed without any name appearing or "\
        "disappearing"


def _baseline_mismatch():
    """Why the model is not back on the committed baseline, or None when it is.

    'clean_project returned ok' and 'the project reports ready' are both SIGNALS, not proof:
    they say EDT finished the work it knew about, not that the model now matches the committed
    fixture. Only reading the model says that.

    ONE definition of "the model is home", asked by both the reset post-condition
    (reset_model) and the skip-the-reset shortcut (model_is_pristine). They used to ask
    DIFFERENT questions, and in the wrong direction: the shortcut compared the whole top-object
    inventory, while the post-condition compared three named FQNs. So a renamed common module
    correctly forced a reset - and was then certified as reset, because the object it renamed was
    not one of the three. The run continued on a stale model and the failure surfaced in a later,
    innocent test (rename_metadata_object::test_unparsable_disable_index_token_is_refused_before
    _rename, which read a configuration listing that had never come back). A post-condition
    weaker than the precondition that triggered it can only certify the thing it was called to
    catch, so the two are now literally the same code.

    Both braces are needed and neither subsumes the other: the INVENTORY sees a top object that
    appeared, vanished or was renamed; the DETAIL of BASELINE_PROBE_FQNS sees a change INSIDE one
    - a property, a child, a synonym - which leaves every name identical.

    Best-effort by construction: anything that cannot be read counts as a mismatch and the caller
    retries the whole revert+clean cycle (or, for the shortcut, simply does not skip it). A call
    TIMEOUT still propagates - see call().
    """
    if _BASELINE_INVENTORY is not None:
        inventory = _top_object_inventory()
        if inventory is None:
            return "the top-object inventory could not be read"
        if inventory != _BASELINE_INVENTORY:
            return _inventory_difference(inventory)
    # _probe_details already rejects everything that is not positive evidence - a blank body, a
    # tool error, and a per-object "not found" reported inside a successful one - so there is
    # nothing left to re-check here: either it handed back a real fingerprint or it handed back
    # nothing.
    text = _probe_details()
    if text is None:
        return "the detail of %s could not be read as evidence" % ", ".join(BASELINE_PROBE_FQNS)
    if _BASELINE_DETAILS is not None and text != _BASELINE_DETAILS:
        return "%s still resolve, but their detail no longer matches the baseline - something "\
            "INSIDE one of them changed" % ", ".join(BASELINE_PROBE_FQNS)
    return None


def _settle_progress_note(progress):
    """One clause describing what the settle observed, for the failure message.

    Deliberately NOT a decision. list_projects reports a COARSE categorical state: a project
    reads `building` for the entire recompute, whether the queue is draining steadily or has
    stalled, so an unchanged snapshot is not evidence of a stall and must not shorten the
    retries - a slow-but-healthy runner would start failing. It is still worth SAYING, because
    the next occurrence is diagnosed from what was printed.
    """
    polls = progress.get("polls", 0)
    elapsed = int(progress.get("elapsed", 0))
    if not progress.get("observed"):
        return "project state could not be read at all in %d polls over %ds" % (polls, elapsed)
    if progress.get("changed"):
        return "project state changed during the wait (%d polls over %ds)" % (polls, elapsed)
    return ("project state never changed in %d polls over %ds (a coarse state, so this does not "
            "by itself distinguish a stalled queue from a slow one)" % (polls, elapsed))


# The evidence tail is capped so a large log cannot turn a diagnostic into a delay. 80 lines of
# EDT log run well under this; the cap only decides how much is READ to find them.
_EVIDENCE_LOG_TAIL_BYTES = 256 * 1024

# The line budget for the whole block, split between the files it ended up reading, and the floor
# below which a share stops being worth printing. Split rather than shared: see the assembly.
_EVIDENCE_TAIL_LINES = 80
_EVIDENCE_TAIL_MIN_LINES = 20

# ...and the size cap alone is not enough: open/seek/read on a hung or very slow filesystem do not
# return, so the bytes are bounded while the WAIT is not. A BOUNDED wait does not fix it either -
# the runner's per-test timeout is absolute, so any wait at all can be the one that overruns it,
# and an overrun abandons the worker and arms the global abort latch, killing the remaining reset
# attempts. The only wait that provably cannot change the outcome is no wait, so the whole block is
# collected and printed by a daemon thread and the caller returns at once.
#
# Which leaves the threads themselves as the last way to spend the caller's budget: a later settle
# failure must not start another collector, whether the first is still reading or already printed.
# Apart from duplicating a block titled FIRST, enough overlapping collectors can reach the thread
# limit and make Thread.start() raise SYNCHRONOUSLY - the diagnostic replacing the retry it exists
# to explain. Hence single-flight for the whole run: once a collector starts, the stored thread is
# never replaced. A start that fails is reported as unavailable evidence rather than raised, and
# clears the slot so a later failure can still try to leave the run's one block.
_FAILED_SETTLE_EVIDENCE_THREAD = None
_FAILED_SETTLE_EVIDENCE_LOCK = threading.Lock()


def _read_log_tail(log_path, capture_identity=False):
    """Return the last _EVIDENCE_LOG_TAIL_BYTES of `log_path`.

    Reads the TAIL rather than the file: seek back a bounded number of bytes instead of pulling in
    a log that may have grown to any size. It can still BLOCK on a filesystem that stopped
    answering, which is safe only because its sole caller runs off the reset thread entirely.

    When requested, return the identity of the generation that was actually opened along with the
    text. The fstat happens after open, so a backup path reused after selection cannot silently
    substitute a different file generation.
    """
    with open(log_path, "rb") as handle:
        opened_identity = None
        if capture_identity:
            st = os.fstat(handle.fileno())
            opened_identity = (st.st_mtime_ns, st.st_size, getattr(st, "st_ino", 0))
        handle.seek(0, os.SEEK_END)
        size = handle.tell()
        handle.seek(max(0, size - _EVIDENCE_LOG_TAIL_BYTES))
        # read(N), not read(): the seek bounds where the read STARTS, not where it ends, and
        # EDT is still appending. A bare read() runs to the CURRENT end of file and returns
        # however much was written meanwhile - so the cap that justifies calling this cheap
        # was not being applied at all.
        blob = handle.read(_EVIDENCE_LOG_TAIL_BYTES)
    text = blob.decode("utf-8", errors="replace")
    return (text, opened_identity) if capture_identity else text


def _failed_settle_evidence(last_list_projects):
    """Print one best-effort evidence block for the first failed settle in the run.

    Returns immediately because the work runs on one daemon thread. It issues no MCP call, reads
    bounded bytes, and never starts another collector while a filesystem read is still in flight.
    """
    global _FAILED_SETTLE_EVIDENCE_THREAD

    try:
        with _FAILED_SETTLE_EVIDENCE_LOCK:
            current = _FAILED_SETTLE_EVIDENCE_THREAD
            if current is not None:
                if current.is_alive():
                    message = "collection is still in flight from an earlier settle and was skipped"
                else:
                    message = "evidence was already collected for an earlier settle and was skipped"
                _print_failed_settle_evidence_note(message)
                return
            thread = threading.Thread(
                target=_print_failed_settle_evidence, args=(last_list_projects,),
                name="e2e-failed-settle-evidence", daemon=True)
            _FAILED_SETTLE_EVIDENCE_THREAD = thread
            try:
                thread.start()
            except Exception as exc:
                _FAILED_SETTLE_EVIDENCE_THREAD = None
                _print_failed_settle_evidence_note(
                    "evidence is unavailable because collection could not start and was skipped "
                    "(%s: %s)" % (type(exc).__name__, exc))
    except Exception as exc:
        _print_failed_settle_evidence_note(
            "evidence is unavailable and collection was skipped (%s: %s)" %
            (type(exc).__name__, exc))


# At most this many backups are read for one evidence block, on top of the current .log. Not a
# guess about rotation frequency - a ceiling on what a diagnostic may cost if something
# pathological is rotating in a loop. Which files the cap KEEPS is the part that matters, and it
# keeps the earliest rotations; see _backups_covering. Realistically the selection returns one.
_EVIDENCE_LOG_MAX_BACKUPS = 3


def _backup_identities(metadata):
    """Return (`{path: identity}`, scan failure) for every `.bak_*.log`.

    The identity is (mtime_ns, size, inode), not the mtime alone. EDT reuses the backup NAMES, so
    a rotation can overwrite .bak_1 rather than add a file - and if the replacement happens to
    carry the same coarse timestamp, an mtime-only comparison calls that "unchanged" and the
    rotation goes unseen. Three independent fields make a same-name replacement essentially
    impossible to miss: the file that replaced it would have to match all three.

    A file vanishing between enumeration and stat is a normal rotation race and remains a
    successful scan. A failure of the scan itself is returned separately: an empty dict alone
    cannot tell the collector whether there really were no backups or whether it failed to look
    for them.
    """
    seen = {}
    try:
        with os.scandir(metadata) as entries:
            for entry in entries:
                if not fnmatch.fnmatch(entry.name, ".bak_*.log"):
                    continue
                if not entry.is_file(follow_symlinks=True):
                    continue
                path = os.path.join(metadata, entry.name)
                try:
                    st = os.stat(path)
                except FileNotFoundError:
                    continue    # rotation removed it between enumeration and stat
                seen[path] = (st.st_mtime_ns, st.st_size, getattr(st, "st_ino", 0))
    except Exception as exc:
        return ({}, "%s: %s" % (type(exc).__name__, exc))
    return (seen, None)


def _backups_covering(before, after, failures=None):
    """The backups that can still hold the failure moment, oldest first.

    `before` and `after` are _backup_identities snapshots taken around the read of the current
    log. Two groups qualify, and each answers a different rotation:

    - every backup that APPEARED or CHANGED between the snapshots. Each one is a file that was
      .log moments ago, so a burst of rotations during collection cannot push the failure past us
      - which is what one "newest backup" could not survive: two rotations in a row leave the
      failure in the FIRST rotation's backup while the second becomes the newest;
    - the newest backup that already existed, where an earlier rotation had put it.

    Comparing SNAPSHOTS is what makes this exact - no clock, no epsilon - and comparing identities
    rather than timestamps is what survives EDT reusing a backup name. Names never order these
    files: a workspace can hold a .bak_7 written hours after its .bak_8.

    THE CAP KEEPS THE EARLIEST ROTATION, not the newest. The failure is at or before the moment
    collection started, so among the backups created during collection it lives in the FIRST one -
    the file that was .log when the settle failed. Later ones hold what was written after. Sorting
    newest-first and truncating would throw away precisely the file being looked for, which is the
    mistake the previous revision made.
    """
    appeared = [path for path, identity in after.items() if before.get(path) != identity]
    pre_existing = [path for path in before if path not in appeared]
    overwritten = sorted(path for path in appeared if path in before)

    def when(path):
        """Only the MTIME orders these files.

        The identity tuple carries size and inode as well, but those exist to DETECT a same-name
        replacement, not to sequence one. Sorting by the whole tuple means that when two rotations
        share a coarse timestamp the "earliest" is decided by which file is smaller or which inode
        the filesystem happened to hand out - and the earliest is exactly the one the cap keeps.
        """
        identity = after.get(path, before.get(path, (0, 0, 0)))
        return identity[0]

    # Do not make selection cleverer in an ambiguity: enlarging the cap, reserving a slot or
    # inventing a tie-break cannot prove that the omitted source was safe to omit. The honest
    # answer is to mark the evidence incomplete and tell the reader to inspect the raw workspace.
    if failures is not None:
        if overwritten:
            failures.append(
                "%d pre-existing backup%s overwritten in place during collection, so %s earlier "
                "contents are gone: %s"
                % (len(overwritten), "" if len(overwritten) == 1 else "s",
                   "its" if len(overwritten) == 1 else "their",
                   ", ".join(os.path.basename(path) for path in overwritten)))
        slots_left = max(0, _EVIDENCE_LOG_MAX_BACKUPS - len(appeared))
        omitted_pre_existing = max(0, len(pre_existing) - slots_left)
        if omitted_pre_existing:
            failures.append(
                "backup cap of %d omitted %d pre-existing backup%s for want of room"
                % (_EVIDENCE_LOG_MAX_BACKUPS, omitted_pre_existing,
                   "" if omitted_pre_existing == 1 else "s"))
        appeared_by_mtime = {}
        for path in appeared:
            appeared_by_mtime.setdefault(when(path), []).append(path)
        for timestamp, tied in sorted(appeared_by_mtime.items()):
            if len(tied) > _EVIDENCE_LOG_MAX_BACKUPS:
                failures.append(
                    "backup mtime tie: %d appeared backups share timestamp %d, exceeding cap "
                    "of %d; their order is not decidable"
                    % (len(tied), timestamp, _EVIDENCE_LOG_MAX_BACKUPS))

    appeared.sort(key=when)
    # The earliest rotations first: the failure is at or before the moment collection started, so
    # among the files that became backups DURING collection it lives in the first of them.
    chosen = appeared[:_EVIDENCE_LOG_MAX_BACKUPS]
    # Then the pre-existing backups, NEWEST first, for whatever room is left. More than one,
    # because rotations that completed BEFORE the collector started leave nothing in the snapshot
    # diff to find them by - a failure that had already rotated twice sits behind the newest one.
    # Newest-first here is not the same rule inverted: among files that predate collection, the
    # newest is the closest to the failure, while among files created during it, the earliest is.
    for path in sorted(pre_existing, key=when, reverse=True):
        if len(chosen) >= _EVIDENCE_LOG_MAX_BACKUPS:
            break
        chosen.append(path)
    # Oldest first, which is also the display order. A tie between the two snapshot groups is
    # broken by the group, since a pre-existing backup can never be the later one.
    pre_existing_set = set(pre_existing)
    chosen.sort(key=lambda path: (when(path), 0 if path in pre_existing_set else 1))
    return chosen


def _share_tail_lines(sources):
    """Split the line budget between sources, leaving none of it unspent.

    Each source starts with an equal share; a source with fewer lines than its share releases the
    difference, and the remainder is dealt round-robin to the ones that can still use it. The floor
    keeps a share from collapsing to nothing when many files were read.

    @param sources the lines of each source, in the order they will be displayed
    @return the TAIL of each source, same order, each already cut to its final share
    """
    if not sources:
        return []
    share = max(_EVIDENCE_TAIL_MIN_LINES, _EVIDENCE_TAIL_LINES // len(sources))
    wanted = [len(lines) for lines in sources]
    granted = [min(share, want) for want in wanted]
    spare = max(0, _EVIDENCE_TAIL_LINES - sum(granted))
    while spare > 0 and any(g < w for g, w in zip(granted, wanted)):
        for index, (grant, want) in enumerate(zip(granted, wanted)):
            if spare <= 0:
                break
            if grant < want:
                granted[index] = grant + 1
                spare -= 1
    return [lines[-grant:] if grant else [] for lines, grant in zip(sources, granted)]


def _print_failed_settle_evidence_note(message):
    """Print a one-line status without letting diagnostic output alter the reset outcome."""
    try:
        print("  [failed settle evidence] %s" % message, flush=True)
    except Exception:
        pass


def _print_failed_settle_evidence(last_list_projects):
    """Build and print the evidence block. Runs on the daemon thread started above."""
    sections = [("last list_projects (raw)", last_list_projects or "<empty response>")]

    try:
        workspace = _workspace_dir(last_list_projects or "")
        if workspace is None:
            raise RuntimeError(
                "EDT workspace not found; set EDT_MCP_EDT_WORKSPACE to the -data directory")
        metadata = os.path.join(workspace, ".metadata")
        current = os.path.join(metadata, ".log")
        by_path = {}
        sources = []
        failures = []

        def scan_backups(when):
            try:
                identities, failure = _backup_identities(metadata)
            except Exception as exc:
                # Keep a helper failure local just like a failed log read: .log may still be
                # readable, but the block must admit that a rotated source may be missing.
                identities = {}
                failure = "%s: %s" % (type(exc).__name__, exc)
            if failure:
                failures.append("backup scan %s: %s" % (when, failure))
            return identities

        def read_into(log_path, selected_identity=None):
            try:
                if selected_identity is None:
                    text = _read_log_tail(log_path)
                else:
                    text, opened_identity = _read_log_tail(log_path, True)
                    if selected_identity is not None and opened_identity != selected_identity:
                        failures.append(
                            "selected backup identity changed at read time: %s" % log_path)
                by_path[log_path] = text
            except Exception as exc:
                # Rotation may remove a path before it can be opened, so one failed read must not
                # hide evidence that remains available in the other file. This also contains a
                # failed local fstat: identity verification must never raise out of this block.
                failures.append("%s: %s: %s" %
                                (os.path.basename(log_path), type(exc).__name__, exc))

        # ORDER IS THE MECHANISM, and what has to be ordered are the OPERATIONS, not just the
        # reads. EDT rotates by renaming .log to a .bak_N and starting an empty .log, so the file
        # holding the failure moves while it is being collected. Bracket the read of the current
        # log with two cheap directory snapshots, and every rotation that happens in that window
        # shows up as a backup that appeared or changed - which is exactly the set that has to be
        # read as well:
        #   rotation before the read -> .log comes back empty, but the rotated-out file is in the
        #                               `after` snapshot and gets read;
        #   rotation after the read  -> the failure is already in hand; when a selected backup's
        #                               displayed text contains it, that backup keeps the bytes;
        #   two in a row             -> BOTH new backups are in the snapshot diff, so the failure
        #                               stays in the covering backup's chronological slot.
        # Choosing a single "newest backup" survived none of these fully, and choosing it BEFORE
        # the read survived neither of the first two. Re-scanning at the end alone is no fix - it
        # races the writer the same way; the pair of snapshots is what makes the window observable.
        before_rotation = scan_backups("before reading .log")
        read_into(current)
        after_rotation = scan_backups("after reading .log")
        backup_paths = _backups_covering(before_rotation, after_rotation, failures)
        backups = [(path, after_rotation.get(path, before_rotation.get(path)))
                   for path in backup_paths]
        for log_path, selected_identity in backups:
            read_into(log_path, selected_identity)
        # Chronological for DISPLAY - the opposite of the read order, and stated separately rather
        # than derived from it, which would make the displayed chronology silently wrong the moment
        # the read order is touched.
        display_order = backup_paths + [current]
        # Drop the current source only when what a readable backup will actually show contains
        # every byte its section would have shown. If a backup holds those bytes outside its
        # displayed tail, both sources stay: duplication costs line budget, but never the failure
        # line.
        current_text = by_path.get(current)
        readable_backups = [log_path for log_path in backup_paths if log_path in by_path]
        if current_text and readable_backups:
            base_share = max(
                _EVIDENCE_TAIL_MIN_LINES, _EVIDENCE_TAIL_LINES // len(readable_backups))
            current_section = "\n".join(current_text.splitlines()).rstrip()
            if current_section and any(
                    current_section in "\n".join(
                        by_path[log_path].splitlines()[-base_share:]).rstrip()
                    for log_path in readable_backups):
                display_order.remove(current)
                by_path.pop(current, None)
        texts = []
        for log_path in display_order:
            if log_path in by_path:
                texts.append(by_path[log_path])
                sources.append(".metadata/" + os.path.basename(log_path))
        if not sources:
            raise RuntimeError("no readable EDT logs (%s)" % "; ".join(failures))
        # ONE SECTION PER SOURCE, each with its OWN share of the line budget. Concatenating the
        # files and taking the last 80 lines of the result looks equivalent and is not: when the
        # failure has rotated into a backup and the current .log has since accumulated 80 lines of
        # its own, the global cut discards every backup line - the failure included - while the
        # heading still names the backup as a source. That would undo the whole reason these files
        # are collected, and present the result as complete. Per-source budgets cannot do it, and
        # a reader can see which lines came from which file.
        # An equal split alone still drops evidence while the block is under budget: two sources
        # get 40 lines each, and a short current log leaves 39 of its share unspent while the
        # backup holding the failure is cut at 40. So the shares are settled first, giving every
        # source what it can use and handing the remainder to those that want more.
        for source, lines in zip(sources, _share_tail_lines([body.splitlines() for body in texts])):
            sections.append(("EDT log tail: %s (last %d lines, last %d bytes at most)"
                             % (source, len(lines), _EVIDENCE_LOG_TAIL_BYTES),
                             "\n".join(lines).rstrip() or "<empty log>"))
        # A PARTIAL tail must say so. A backup that rotation removed before it could be opened is
        # a file that may have held the failure, and reporting only what was read would present an
        # incomplete block as a complete one - the same overclaim this block keeps being fixed for,
        # in its own output this time.
        if failures:
            sections.append(("EDT log tail - INCOMPLETE",
                             "evidence may be partial: %s" % "; ".join(failures)))
    except Exception as exc:
        sections.append(("EDT log tail (last 80 lines, last %d bytes per file at most)" %
                         _EVIDENCE_LOG_TAIL_BYTES,
                         "<evidence unavailable: %s: %s>" %
                         (type(exc).__name__, exc)))

    try:
        lines = ["\n===== FIRST FAILED MODEL SETTLE EVIDENCE ====="]
        for heading, body in sections:
            lines.extend(("--- %s ---" % heading, body))
        lines.append("===== END FIRST FAILED MODEL SETTLE EVIDENCE =====")
        print("\n".join(lines), flush=True)
    except Exception as exc:
        # Diagnostics must never replace or otherwise alter the reset failure being diagnosed.
        print("\n===== FIRST FAILED MODEL SETTLE EVIDENCE =====\n"
              "<evidence unavailable: %s: %s>\n"
              "===== END FIRST FAILED MODEL SETTLE EVIDENCE ====="
              % (type(exc).__name__, exc), flush=True)


def _revert_and_clean(project, revert):
    """One revert + clean_project cycle for `project`, with SEPARATE budgets for its two failures.

    Settling and cleaning fail for different reasons and are fixed differently (see
    MODEL_CLEAN_ATTEMPTS / MODEL_SETTLE_ATTEMPTS), so a settle that never reports ready must not
    consume the allowance for a call it prevented from ever being made.

    Bounded by MODEL_RESET_BUDGET as well as by the counters, so the split cannot spend more wall
    clock than the single shared budget could - see that constant.

    @param revert the disk revert to re-run once the project has settled - the base fixture for a
           per-test reset, every fixture for the end-of-run cleanup
    @return (cleaned, clean_attempts, settle_failures, last_settle_failure) - the counts and the
            last project-state diagnostic are the material the caller turns into a message, so
            an abort always names what actually ran out and which project blocked it."""
    clean_attempts = 0
    settle_failures = 0
    last_settle_failure = None
    deadline = time.time() + MODEL_RESET_BUDGET
    while (clean_attempts < MODEL_CLEAN_ATTEMPTS and settle_failures < MODEL_SETTLE_ATTEMPTS
           and time.time() < deadline):
        # Settle BEFORE the revert: out-wait the recompute (so the clean is accepted) and
        # give any lagging disk export time to land, so the revert below is the last write.
        # A settle that TIMED OUT means the export may still be in flight, so reverting now
        # would not be the last write: retry the whole cycle instead of building on it. The
        # verification the caller does afterwards is what finally decides.
        failure_details = []
        progress = {}
        if not wait_for_project_ready(timeout=MODEL_SETTLE_TIMEOUT,
                                      failure_details=failure_details,
                                      progress=progress):
            settle_failures += 1
            last_settle_failure = failure_details[0]
            # No deadline credit-back: the call returns at once (the block is built and
            # printed on a daemon thread), so there is no diagnostic time to give back.
            _failed_settle_evidence(progress.get("last_list_projects", ""))
            last_settle_failure = "%s; %s" % (
                last_settle_failure, _settle_progress_note(progress))
            continue
        # Re-revert: undo whatever that late export wrote over the orchestrator's revert.
        # Cheap local git and idempotent, so doing it on the first pass too costs nothing.
        revert()
        # Counted BEFORE the call, so an attempt that dies on the way out still spends its
        # budget - otherwise a repeatable transport failure loops here forever.
        clean_attempts += 1
        try:
            if not call("clean_project", {"projectName": project}).is_error:
                return (True, clean_attempts, settle_failures, last_settle_failure)
        except E2ECallTimeout:
            # The one failure a best-effort catch must NOT swallow: the server is still running
            # that call, so retrying - or reporting success - hides it from the runner, the only
            # place that can stop the run before the next test reads a model it is still writing.
            raise
        except Exception:
            # Best-effort - UNLESS this failure already stopped the run. A clean_project that dies
            # in flight arms the global latch inside call(), and looping on from a latched harness
            # is pointless: the next request is refused anyway. It is refused IMMEDIATELY, so this
            # costs no wall clock either way - what it costs is the diagnosis, because the abort
            # then gets attributed to whichever call trips over the latch next instead of to the
            # one that actually died. Re-raise and keep the cause attached to its effect.
            if calls_aborted():
                raise
    return (False, clean_attempts, settle_failures, last_settle_failure)


def _clean_failure_cause(clean_attempts, settle_failures, last_settle_failure):
    """Name the budget that actually ran out, for the abort message."""
    terminal = (clean_attempts >= MODEL_CLEAN_ATTEMPTS or
                settle_failures >= MODEL_SETTLE_ATTEMPTS)
    if clean_attempts == 0:
        return ("%s (%d settle attempts of %ds each%s), so "
                "clean_project was never even accepted for an attempt"
                % (last_settle_failure or "projects never reported ready",
                   settle_failures, MODEL_SETTLE_TIMEOUT,
                   "" if terminal else "; the %ds reset budget ran out first" % MODEL_RESET_BUDGET))
    return ("clean_project was refused in all %d attempts%s%s"
            % (clean_attempts,
               " (plus %d settle timeouts; %s)" % (settle_failures, last_settle_failure)
               if settle_failures else "",
               "" if terminal else ", and the %ds reset budget ran out first" % MODEL_RESET_BUDGET))


def reset_model():
    """Re-sync EDT's in-memory BM model to the on-disk baseline after a write-metadata test.

    Metadata-write tools (create/add/delete/rename metadata) mutate the in-memory BM model
    but do NOT flush every change to disk, so a git reset alone cannot undo them — the model
    would carry the unsaved change into the next test. clean_project re-imports the clean disk
    + revalidates, discarding the in-memory change. The orchestrator calls this after each
    kind='write-metadata' test.

    CRITICAL ORDERING (root cause of the rename >300s e2e timeout): a metadata write also
    SCHEDULES a derived-data recompute, so the project is BUILDING right after the test —
    and clean_project REFUSES a building project. The old code called clean_project FIRST
    and swallowed the refusal (it returns an isError result, not an exception), leaving the
    model UN-reset; the next rename then blocked for minutes inside EDT's still-draining
    derived-data pipeline (DerivedDataManager.blockAsyncPipeline), tripping the per-test
    timeout. So: wait for the project to SETTLE first (out-waiting that recompute) so the
    clean is accepted, THEN clean_project (which itself blocks on its own derived-data
    rebuild). Retry if a late-starting recompute re-flags BUILDING between the wait and the
    call.

    A successful clean_project is NOT that guarantee on its own, which is the second race
    this function has to close. The orchestrator reverts the fixture on disk BEFORE the
    test's model cleanup, but a metadata write's disk export is ASYNC: EDT can flush the
    MUTATED state back out DURING the settle wait, i.e. AFTER that revert — and then
    clean_project faithfully re-imports the mutated disk and still reports ok. Observed on
    EDT 2026.2 (a renamed Catalog survived a green clean_project and the next test failed
    on the baseline FQN). Hence, per attempt: settle FIRST so any lagging export has landed,
    re-revert the disk, THEN clean, and finally VERIFY the baseline is actually back
    (_baseline_mismatch) instead of assuming it. Verification — not a longer timeout — is
    what makes this correct: the failure is a lost write-back race, not slowness.
    """
    last_mismatch = "the post-condition was never reached"
    for _ in range(MODEL_RESET_ATTEMPTS):
        cleaned, clean_attempts, settle_failures, settle_failure = \
            _revert_and_clean(PROJECT, reset_fixture)
        if not cleaned:
            # The model still carries the finished test's write, and the next test would read it.
            # That is the cascade this reset exists to prevent, so stop the run instead of
            # continuing on a model we know is stale.
            # Do not start a "FAILED MODEL SETTLE" collector here: _revert_and_clean already did
            # so if a settle failed; if only clean_project retries ran out, there is no failed
            # settle snapshot and that title would overclaim what the evidence represents.
            raise E2EModelResetFailed(
                "%s, so the in-memory model still carries the last test's write. Continuing would "
                "hand it to the next test."
                % _clean_failure_cause(clean_attempts, settle_failures, settle_failure))
        # Final settle: clean_project's revalidation re-triggers derived data; make sure the
        # next test starts on a fully-indexed model regardless of which branch above we took.
        # A negative result here is the same hazard as the exhausted-retries branch above (the
        # model is not guaranteed to be back in sync) and must not be swallowed either.
        failure_details = []
        progress = {}
        if not wait_for_project_ready(timeout=MODEL_SETTLE_TIMEOUT,
                                      failure_details=failure_details, progress=progress):
            _failed_settle_evidence(progress.get("last_list_projects", ""))
            raise E2EModelResetFailed(
                "clean_project succeeded, but %s, so the model is not guaranteed to be back in "
                "sync." % failure_details[0])
        mismatch = _baseline_mismatch()
        if mismatch is None:
            # The one place entitled to say the model is verifiably home again - which is also
            # what retires a write whose outcome was never read back. See _mark_model_synced.
            _mark_model_synced()
            return
        last_mismatch = mismatch
    # Every attempt reported success and the model STILL does not match the baseline. Continuing
    # would hand the previous test's mutation to the next one (exactly the cascade this reset
    # exists to prevent), and the next failure would be reported against an innocent test.
    # Every settle succeeded here, so a block titled "FAILED MODEL SETTLE" would be misleading;
    # the baseline mismatch below is the evidence for this semantic post-condition failure.
    raise E2EModelResetFailed(
        "the model did not come back to the committed fixture after %d revert+clean_project "
        "cycles, even though every clean_project reported ok and the project reported ready: %s. "
        "The next test would read the last test's write."
        % (MODEL_RESET_ATTEMPTS, last_mismatch))


def _git_checked(*args):
    """Run git and REFUSE to interpret a failure as an answer.

    `_git` never looks at the return code, so a `git status` that failed - a locked index, a
    filesystem hiccup, a broken repo - comes back with empty stdout, which every caller reads
    as "the tree is clean". That false positive is the worst possible direction: it lets the
    reset shortcut skip over a genuinely dirty tree and lets the end-of-run gate certify a run
    that left changes behind.

    It is also BOUNDED, unlike the reverting commands. This is the read the end-of-run gate makes
    after every test has finished: a `git status` that never returns (an unresponsive filesystem,
    the same one that can wedge a reset) would hang the run with no summary and no JUnit report.
    A status scoped to a fixture path takes well under a second, so the ceiling only ever fires on
    a stall - and a stall is exactly the thing that has to become a message rather than a hang."""
    try:
        r = _git(*args, timeout=GIT_STATUS_TIMEOUT)
    except subprocess.TimeoutExpired:
        raise E2EAssertion("git %s did not return within %gs - the working tree cannot be read"
                           % (" ".join(args), GIT_STATUS_TIMEOUT)) from None
    if r.returncode != 0:
        raise E2EAssertion("git %s failed (rc=%s): %s"
                           % (" ".join(args), r.returncode, (r.stderr or "").strip()[:300]))
    return r


def all_fixtures_status():
    """Porcelain status across EVERY fixture path (base + extension). The end-of-run gate
    uses this so a session that leaves ANY fixture dirty is VISIBLE — 'no diff' then means
    the run touched nothing it should not have."""
    parts = []
    for rel in ALL_FIXTURE_RELS:
        s = _git_checked("status", "--porcelain", "--", rel).stdout.rstrip("\r\n")
        if s:
            parts.append(s)
    return "\n".join(parts)


def final_cleanup():
    """Leave the working tree verifiably clean ('no diff' == the session passed and left
    nothing behind).

    Reverts BOTH fixtures on disk, then clean_projects BOTH, with the SAME retry-until-synced
    contract as reset_model() - literally the same code, _revert_and_clean: wait for the project
    to settle, THEN clean_project, each with its own budget. call() only raises on a TIMEOUT, so a
    clean_project that came back with isError (e.g. the derived-data pipeline outlived
    BUILDING_RETRY_TIMEOUT and the server refused it) must not be swallowed by a bare
    `except Exception: pass` - that silently declares an unsynchronised model clean. The
    clean_project is the part that defeats the autosave
    resurrection: it tears down EDT's in-memory model and re-imports it from the now-clean disk
    (synchronously — the call blocks on the project restart + derived-data rebuild), so a STALE
    model (e.g. a manual edit made in the EDT editor, or a metadata write whose model change was
    not flushed) no longer has a pending change to AUTOSAVE back and re-dirty the tree (the
    Compute/Goods whack-a-mole). If a project still refuses after the retry budget - or the
    final settle never reports every project ready - that must be EXPLICIT: raises
    E2EModelResetFailed rather than let a run be reported green over a model nobody actually
    verified is back in sync. The final reset_all_fixtures() only mops up any file clean_project
    itself re-touched (e.g. a CRLF/marker touch). Run at startup AND at the end."""
    reset_all_fixtures()
    for proj in (PROJECT, TESTS_PROJECT):
        cleaned, clean_attempts, settle_failures, settle_failure = \
            _revert_and_clean(proj, reset_all_fixtures)
        if not cleaned:
            # Any failed settle already started the single-flight collector in _revert_and_clean;
            # exhausted clean_project retries alone do not provide a failed-settle snapshot.
            raise E2EModelResetFailed(
                "%s for project %r, so its in-memory model may still carry an unsynchronised "
                "change - reporting this run clean would be a lie."
                % (_clean_failure_cause(clean_attempts, settle_failures, settle_failure), proj))
    failure_details = []
    progress = {}
    if not wait_for_project_ready(timeout=MODEL_SETTLE_TIMEOUT,
                                  failure_details=failure_details, progress=progress):
        _failed_settle_evidence(progress.get("last_list_projects", ""))
        raise E2EModelResetFailed(
            "clean_project succeeded for every project, but %s, so the model is not guaranteed "
            "to be back in sync." % failure_details[0])
    reset_all_fixtures()
    # Deliberately NOT _mark_model_synced() here. This function cleans and settles but never
    # VERIFIES the baseline came back (that is reset_model's _baseline_mismatch), and only a
    # verified restore may retire an unknown outcome. Clearing it on a weaker signal is how the
    # flag would come to mean "we tried" instead of "we checked".


# ──────────────────────────────────────────────────────────────────────────────
# Assertions
# ──────────────────────────────────────────────────────────────────────────────
def assert_ok(result, ctx=""):
    if result.is_error:
        _fail("expected success but tool returned isError [%s]: %s" % (ctx, result.error_text()[:300]))


def assert_error(result, ctx=""):
    """Assert the tool reported an error; return the error message text for further checks."""
    if not result.is_error:
        _fail("expected isError but tool succeeded [%s]: %s" % (ctx, (result.text or "")[:200]))
    return result.error_text()


def assert_contains(haystack, needle, ctx=""):
    if needle not in (haystack or ""):
        _fail("expected text to contain %r [%s]: %s" % (needle, ctx, (haystack or "")[:300]))


def assert_not_contains(haystack, needle, ctx=""):
    if needle in (haystack or ""):
        _fail("expected text to NOT contain %r [%s]: %s" % (needle, ctx, (haystack or "")[:300]))


def assert_no_diff(ctx=""):
    """Non-destructive guardrail: the project working tree must be clean (no mod, no new files)."""
    st = _status_porcelain()
    if st:
        _fail("expected NO change to %s but found [%s]:\n%s" % (PROJECT_REL, ctx, st[:500]))


def assert_no_substantive_diff(ctx=""):
    """Like assert_no_diff, but tolerant of a tracked file that a live EDT autosave only
    TOUCHED with a line-ending/whitespace normalization — under core.autocrlf such a
    touch shows as modified in `git status` yet yields an EMPTY `git diff`. Still fails
    on a real CONTENT change (non-empty diff) or any new/deleted/renamed file.

    Use for live RUNTIME tools (a real YAXUnit run / debug launch) that must not change
    project SOURCE but may incidentally make EDT re-touch a metadata `.mdo` on disk while
    it updates the infobase — a CRLF touch is not the tool 'writing into the project'."""
    # `git diff HEAD` (NOT plain `git diff`) so a STAGED in-place modify is caught too —
    # EDT tools can leave a change staged in the index (see reset_fixture). A CRLF-only
    # touch still normalises to an EMPTY diff under core.autocrlf, so it is tolerated; a
    # real content change (staged or unstaged) yields a non-empty diff and fails.
    content = _git("diff", "HEAD", "--", PROJECT_REL).stdout
    if content.strip():
        _fail("substantive content change to %s [%s]:\n%s" % (PROJECT_REL, ctx, content[:600]))
    # `git diff HEAD` does not list untracked files, so scan status for new/deleted/
    # renamed entries (a CRLF-only modify shows as ' M' with no add/delete/rename code).
    status = _git("status", "--porcelain", "--untracked-files=all", "--", PROJECT_REL).stdout
    for line in status.splitlines():
        code = line[:2]
        if "?" in code or "A" in code or "D" in code or "R" in code:
            _fail("new/deleted/renamed file under %s [%s]:\n%s" % (PROJECT_REL, ctx, status[:500]))


def _tree_sample():
    """One instantaneous read of the fixture's on-disk state. See tree_snapshot()."""
    status = _git("status", "--porcelain", "--untracked-files=all", "--", PROJECT_REL).stdout
    diff_head = _git("diff", "HEAD", "--", PROJECT_REL).stdout
    hashes = {}
    for line in status.splitlines():
        if line[:2] == "??":
            path = line[3:].strip()
            full = os.path.join(REPO_ROOT, path)
            if os.path.isfile(full):
                try:
                    with open(full, "rb") as f:
                        hashes[path] = hashlib.sha1(f.read()).hexdigest()
                except OSError:
                    hashes[path] = "<unreadable>"
    return {"status": status, "diff": diff_head, "untracked": hashes}


def tree_snapshot(stable_for=0.75, timeout=8):
    """Capture the BASE fixture's full on-disk state for a later 'changed NOTHING'
    comparison: porcelain status (every untracked file listed individually), the
    tracked content diff vs HEAD (staged + unstaged), and a content hash of each
    untracked file (so an in-place rewrite of a brand-new file is caught too).

    For tests whose SETUP legitimately dirties the tree (e.g. seeding a referenced
    catalog before probing a blocked delete): plain assert_no_diff would flag the
    seeding itself. Snapshot AFTER the seeding, run the operation under test, then
    assert_tree_unchanged(snapshot) — asserting the operation added nothing on top.

    SETTLED, not instantaneous. EDT exports asynchronously, so a snapshot taken the moment
    a test finishes seeding can capture a tree the exporter is still writing — and then the
    exporter catching up, NOT the operation under test, is what assert_tree_unchanged
    reports. That is a real flake, and it reads as an accusation: "a rejected call must
    change nothing" failing with `tracked diff changed (before 713 chars, after 677 chars)`
    — the diff SHRANK while the call under test was busy being refused.

    So sample until two consecutive reads agree. Both sides of the comparison are then
    states the exporter has finished with, which is what makes the difference between them
    attributable to the operation. Settling the AFTER side does not hide a late write the
    operation caused — it waits for it, so it is caught rather than raced against.

    A tree that never settles within the timeout returns its last sample: the assertion is
    then no worse off than before this settling existed, and failing here would blame the
    test for a stand that is busy for reasons of its own.
    """
    previous = _tree_sample()
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(stable_for)
        current = _tree_sample()
        if current == previous:
            return current
        previous = current
    return previous


def assert_tree_unchanged(before, ctx=""):
    """The fixture's on-disk state is IDENTICAL to the given tree_snapshot() — the
    operation between the snapshot and this call must not have touched the project
    (even though the tree itself may be legitimately dirty from earlier seeding)."""
    after = tree_snapshot()
    if after == before:
        return
    deltas = []
    if after["status"] != before["status"]:
        deltas.append("status before:\n%s\nstatus after:\n%s"
                      % (before["status"][:400], after["status"][:400]))
    if after["diff"] != before["diff"]:
        deltas.append("tracked diff changed (before %d chars, after %d chars)"
                      % (len(before["diff"]), len(after["diff"])))
    for path in sorted(set(before["untracked"]) | set(after["untracked"])):
        if before["untracked"].get(path) != after["untracked"].get(path):
            deltas.append("untracked file changed: %s" % path)
    _fail("expected the operation to change NOTHING on disk (relative to the post-setup "
          "snapshot) but it did [%s]:\n%s" % (ctx, "\n".join(deltas)[:700]))


def assert_diff_contains(substr, ctx=""):
    """The on-disk change includes substr — in a modified TRACKED file (via `git diff`)
    OR in a new UNTRACKED file, INCLUDING a file inside a brand-new untracked directory.

    A newly-created metadata object lands as a whole new folder (e.g. Catalogs/<name>/),
    which `git status --porcelain` collapses to the DIRECTORY line (`?? .../<name>/`),
    so a plain os.path.isfile() on that path skips the object's own .mdo content. We
    therefore enumerate untracked entries with --untracked-files=all, which lists each
    untracked FILE individually, so the new object's own .mdo is searched, not skipped."""
    if substr in diff():
        return
    status = _git("status", "--porcelain", "--untracked-files=all", "--", PROJECT_REL).stdout
    for line in status.splitlines():
        path = line[3:].strip()
        full = os.path.join(REPO_ROOT, path)
        if os.path.isfile(full):
            try:
                with open(full, encoding="utf-8", errors="replace") as f:
                    if substr in f.read():
                        return
            except Exception:
                pass
    _fail("expected on-disk change to contain %r [%s]; diff:\n%s\nstatus:\n%s"
          % (substr, ctx, diff()[:400], status[:300]))


def assert_diff_paths(paths, ctx=""):
    """Exactly these repo-relative paths must have changed (modified/added/deleted)."""
    changed = set(l[3:].strip() for l in _status_porcelain().splitlines())
    missing = set(paths) - changed
    if missing:
        _fail("expected changed paths %s not found [%s]; changed: %s"
              % (sorted(missing), ctx, sorted(changed)))


_STACKTRACE = re.compile(r"\n\tat |\bat [\w.$]+\([\w.]+:\d+\)")


def assert_error_quality(err, names=None, suggests=None, ctx=""):
    """Assert the error is a GOOD error: clear, names the bad value, actionable, not a bare 'Error'/stacktrace."""
    e = (err or "").strip()
    low = e.lower()
    if not e or low in ("error", "error:"):
        _fail("error is bare/empty, not a clear message [%s]: %r" % (ctx, err))
    if _STACKTRACE.search(e):
        _fail("error looks like a raw stack trace, not an actionable message [%s]: %s" % (ctx, e[:200]))
    for n in (names or []):
        if n.lower() not in low:
            _fail("error must name the invalid value %r [%s]: %s" % (n, ctx, e[:300]))
    for s in (suggests or []):
        if s.lower() not in low:
            _fail("error must be actionable / mention %r [%s]: %s" % (s, ctx, e[:300]))


def poll_diff_contains(substr, timeout=10, ctx=""):
    """For tools whose on-disk flush may be async: poll until the change appears (no blind sleep)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            assert_diff_contains(substr, ctx)
            return
        except E2EAssertion:
            time.sleep(0.5)
    assert_diff_contains(substr, ctx)  # final attempt raises with detail


def assert_diff_contains_rel(rel, substr, ctx=""):
    """assert_diff_contains for a fixture path other than the base project.

    Searches the tracked diff of that path AND every untracked file under it, so a change that
    landed in a brand-new file counts too - the same two channels assert_diff_contains reads."""
    if substr in diff_rel(rel):
        return
    status = _git("status", "--porcelain", "--untracked-files=all", "--", rel).stdout
    for line in status.splitlines():
        path = line[3:].strip()
        full = os.path.join(REPO_ROOT, path)
        if os.path.isfile(full):
            try:
                with open(full, encoding="utf-8", errors="replace") as f:
                    if substr in f.read():
                        return
            except OSError:
                continue
    _fail("expected the on-disk change under %s to contain %r [%s]; status was: %s"
          % (rel, substr, ctx, status_porcelain_rel(rel)[:500]))


def poll_diff_contains_rel(rel, substr, timeout=10, ctx=""):
    """poll_diff_contains for a fixture path other than the base project: the export is async,
    so poll instead of sleeping blindly."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            assert_diff_contains_rel(rel, substr, ctx)
            return
        except E2EAssertion:
            time.sleep(0.5)
    assert_diff_contains_rel(rel, substr, ctx)  # final attempt raises with detail


def poll_disk_path_gone(rel_path, timeout=10, ctx=""):
    """Poll until a path under the fixture is REMOVED from disk (for delete tools — the
    removal can lag a beat after the call returns, like the write export). rel_path is
    relative to the project dir, e.g. 'src/CommonModules/Calc/Calc.mdo'."""
    full = os.path.join(PROJECT_DIR, rel_path)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not os.path.exists(full):
            return
        time.sleep(0.5)
    _fail("expected %s to be deleted from disk [%s]" % (rel_path, ctx))


def poll_disk_contains(rel_path, substr, timeout=10, ctx=""):
    """Poll until ONE named fixture file contains substr. rel_path is relative to the project dir,
    e.g. 'src/XDTOPackages/P/Package.xdto'.

    Use this instead of poll_diff_contains whenever the assertion that follows reads ONE SPECIFIC
    file: poll_diff_contains is satisfied by the substring appearing in ANY changed file, so when a
    write touches several files (an object plus the ones it cascades into) it can release while the
    file about to be read is still being exported - a race that only shows up on a fast machine.
    A missing file just keeps polling: the export may not have created it yet."""
    full = os.path.join(PROJECT_DIR, rel_path)
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        try:
            with open(full, encoding="utf-8", errors="replace") as f:
                last = f.read()
            if substr in last:
                return
        except FileNotFoundError:
            last = "(file does not exist yet)"
        time.sleep(0.5)
    _fail("expected %s to contain %r [%s]; it holds:\n%s"
          % (rel_path, substr, ctx, last[:700]))


def assert_disk_path_gone(rel_path, ctx=""):
    """A path under the fixture is ALREADY gone — checked once, with no polling.

    PRECONDITION, and it is not optional: only for a path THIS call already dealt with — either it
    removed the path itself and synchronously (a form's resource folder goes through IFolder.delete),
    or it SUBMITTED the export that removes it (create_metadata / modify_metadata / the specialized
    delete branches call forceExportToDisk, then the #406 barrier waits, so submission
    happens-before the wait). The barrier only WAITS, so it is ordered with an export only when the
    same call queued it — otherwise it can truthfully observe a quiet export segment before the
    work has been queued at all.

    The generic delete path now submits too, but only for the CONTAINER of the deleted node (#408).
    The deleted object's OWN file is not covered: EDT builds a save task by looking the FQN up in
    the transaction, so an FQN that no longer resolves yields no task, and nobody but the
    refactoring can schedule that removal. Poll for it — which is what
    test_confirm_deletes_top_object_gone_from_model_and_disk does, right next to an immediate
    assertion on the container's file."""
    full = os.path.join(PROJECT_DIR, rel_path)
    if os.path.exists(full):
        _fail("expected %s to be gone from disk the moment the call returned, but it is still "
              "there - the tool answered before its export reached disk [%s]" % (rel_path, ctx))


def assert_disk_lacks(rel_path, substr, ctx=""):
    """One named fixture file EXISTS and does not contain substr — checked once, no polling.

    Requiring the file to exist is deliberate, and is the difference from poll_disk_lacks:
    that helper treats a MISSING file as satisfying "lacks", so it would also pass while the
    owning file is mid-rewrite. Here the file must be present AND already correct.

    Same precondition as assert_disk_path_gone: only for a file whose export this call submitted —
    which, for the generic delete path, is the CONTAINER's file and not the deleted object's own."""
    full = os.path.join(PROJECT_DIR, rel_path)
    if not os.path.exists(full):
        _fail("expected %s to exist and no longer mention %r, but the file is missing [%s]"
              % (rel_path, substr, ctx))
    with open(full, encoding="utf-8", errors="replace") as f:
        content = f.read()
    if substr in content:
        _fail("expected %s to no longer contain %r the moment the call returned - the tool "
              "answered before its export reached disk [%s]" % (rel_path, substr, ctx))


def poll_disk_lacks(rel_path, substr, timeout=10, ctx=""):
    """Poll until a fixture file no longer contains substr (e.g. a removed collection
    reference). A missing file also satisfies 'lacks'. Polls because the on-disk edit
    can lag a beat after the call returns."""
    full = os.path.join(PROJECT_DIR, rel_path)
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with open(full, encoding="utf-8", errors="replace") as f:
                if substr not in f.read():
                    return
        except FileNotFoundError:
            return
        time.sleep(0.5)
    _fail("expected %s to no longer contain %r [%s]" % (rel_path, substr, ctx))


def poll_disk_count(rel_path, substr, expected, timeout=10, stable_for=1.0, ctx=""):
    """Poll until ONE named fixture file has held substr EXACTLY `expected` times for `stable_for`.

    The COUNT sibling of poll_disk_contains, for the "written exactly once" class of assertion (an
    idempotent re-add must not duplicate a row), where presence is not enough: the count itself is
    the claim, so the failure message has to carry the count - a bare
    `read_disk(f).count(x) == n` reports neither the number it saw nor the file, and "must NOT
    duplicate" then describes a count of 0 just as readily as a count of 2.

    WHY THE DWELL, and not "return on the first matching read": in the case this exists for, the
    expected count is ALREADY true before the call under test - the row was written by the previous
    step. A first-sample poll is therefore satisfied by the PRE-call contents and never observes
    what the call did, so a regression that eventually writes a second row would pass. Requiring the
    count to HOLD for `stable_for` closes that: a late duplicate resets the dwell, the count settles
    at 2, `expected` is never reached again and the call fails with the count it actually saw.

    This is a dwell, not a proof that an export happened - the write under test may legitimately be
    a no-op that rewrites nothing, so demanding evidence of a rewrite would fail those cases
    spuriously. Pick `stable_for` longer than the export lag you care about.

    A missing file keeps polling (and resets the dwell): the export may not have created it yet."""
    full = os.path.join(PROJECT_DIR, rel_path)
    deadline = time.time() + timeout
    last = ""
    actual = None
    stable_since = None
    while time.time() < deadline:
        try:
            with open(full, encoding="utf-8", errors="replace") as f:
                last = f.read()
            actual = last.count(substr)
        except FileNotFoundError:
            last = "(file does not exist yet)"
            actual = None
        if actual == expected:
            if stable_since is None:
                stable_since = time.time()
            if time.time() - stable_since >= stable_for:
                return
        else:
            stable_since = None
        time.sleep(0.1)
    _fail("expected %s to contain %r exactly %d time(s) held for %.1fs, last saw %s [%s]; "
          "it holds:\n%s"
          % (rel_path, substr, expected, stable_for,
             "no file" if actual is None else "%d" % actual, ctx, last[:700]))


# ──────────────────────────────────────────────────────────────────────────────
# Live-infobase helpers (used only by the gated test_live_roundtrip.py suite)
# ──────────────────────────────────────────────────────────────────────────────
def parse_yaxunit_counts(text):
    """Parse the YAXUnit Markdown summary table into a dict of counts + the verdict.

    The report (run_yaxunit_tests, MARKDOWN) renders a `| Metric | Count |` table:
        | Total  | 8 |   | Passed | 8 |   | Failed | 0 |   | Errors | 0 |   | Skipped | 0 |
    followed by `**Result: PASSED**` (or FAILED). Returns e.g.
        {"total":8,"passed":8,"failed":0,"errors":0,"skipped":0,"result":"PASSED"}
    Keys are absent when a row is missing, so callers should use .get()."""
    out = {}
    for metric in ("Total", "Passed", "Failed", "Errors", "Skipped"):
        m = re.search(r"\|\s*%s\s*\|\s*(\d+)\s*\|" % metric, text or "", re.IGNORECASE)
        if m:
            out[metric.lower()] = int(m.group(1))
    verdict = re.search(r"\*\*Result:\s*([A-Za-z]+)\*\*", text or "")
    if verdict:
        out["result"] = verdict.group(1).upper()
    return out


_APP_ID_RE = re.compile(r"\*\*applicationId:\*\*\s*`([^`]+)`")


def extract_application_id(text):
    """Pull the applicationId out of a debug launch handle Markdown (the
    `- **applicationId:** \\`<id>\\`` bullet from buildDebugLaunchMarkdown). Returns
    the id string, or None if absent (e.g. the launch produced no app id)."""
    m = _APP_ID_RE.search(text or "")
    return m.group(1) if m else None


def _configurations_payload(result):
    """Return list_configurations' entries as a list of dicts. The tool is a
    JSON-responseType tool, so the data lands in structuredContent (r.text is just a
    'Done' placeholder). Falls back to parsing r.text if structured is absent."""
    s = result.structured
    if isinstance(s, dict) and isinstance(s.get("configurations"), list):
        return s["configurations"]
    try:
        obj = json.loads(result.text or "")
        if isinstance(obj, dict) and isinstance(obj.get("configurations"), list):
            return obj["configurations"]
    except Exception:
        pass
    return []


def any_launch_running(config_name=None):
    """True if list_configurations reports a live launch (optionally only for a given
    config name). Reads the structured `running` flag, not a text heuristic."""
    cfgs = _configurations_payload(call("list_configurations", {}))
    for c in cfgs:
        if config_name and c.get("name") != config_name:
            continue
        if c.get("running"):
            return True
    return False


def terminate_all_live_launches():
    """Teardown helper: kill EVERY live EDT launch (all=true,confirm=true). Idempotent
    and safe when nothing is running (returns the benign not_found sentinel). Best
    effort in a finally block: it swallows every failure EXCEPT a call timeout, which means
    the server is still busy and must not be hidden."""
    try:
        call("terminate_launch", {"all": True, "confirm": True})
    except E2ECallTimeout:
        # The one failure a best-effort catch must NOT swallow: the server is still running
        # that call, so retrying - or reporting success - hides it from the runner, the only
        # place that can stop the run before the next test reads a model it is still writing.
        raise
    except Exception:
        pass


def wait_until_no_running_launch(config_name=None, timeout=60):
    """Poll list_configurations until no launch reports running=true (optionally only
    for a given config). Used after terminate to confirm the infobase actually went
    down before the next test. Returns True once quiet, False on timeout."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if not any_launch_running(config_name):
                return True
        except E2ECallTimeout:
            # The one failure a best-effort catch must NOT swallow: the server is still running
            # that call, so retrying - or reporting success - hides it from the runner, the only
            # place that can stop the run before the next test reads a model it is still writing.
            raise
        except Exception:
            pass
        time.sleep(2)
    return False


# ──────────────────────────────────────────────────────────────────────────────
# Test registry (per-tool files register via @e2e_test; the orchestrator runs them)
# ──────────────────────────────────────────────────────────────────────────────
REGISTRY = []


def e2e_test(tool, kind="read", last=False):
    """Register a test function. kind: 'read' | 'write' | 'action'.

    last=True defers the test to the END of the run. Use it only when the SUBJECT of the test
    is the run itself rather than one tool's behaviour - the EDT-log ratchet, which can only
    judge what the suite logged once the suite has logged it. Registry order is import order,
    so without this such a test lands wherever its filename sorts and certifies a window that
    has barely opened.
    """
    def deco(fn):
        REGISTRY.append({"func": fn, "tool": tool, "kind": kind, "name": fn.__name__,
                         "last": last})
        return fn
    return deco
