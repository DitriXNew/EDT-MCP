#!/usr/bin/env python3
"""
EDT-MCP e2e harness — the shared base every per-tool test imports.

Owns: the HTTP/JSON-RPC(+SSE) client, the git-fixture isolation helpers
(TestConfiguration is a committed fixture; on-disk truth is git), and all
assertion helpers including error-quality. Tests call these; they never
re-implement them. See SKILL.md for the full guide.

Python stdlib only. No third-party dependencies.
"""

import hashlib
import json
import os
import re
import socket
import subprocess
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

# The YAXUnit test suite lives in a SEPARATE EDT extension project (V8ExtensionNature)
# named "<base>.tests" — breakpoints in the test modules resolve against THIS project,
# not the base configuration. Override with MCP_TESTS_PROJECT if the layout changes.
TESTS_PROJECT = os.environ.get("MCP_TESTS_PROJECT", PROJECT + ".tests")
# Git path of the extension fixture (its EDT project dir is "tests", under tests/), kept
# decoupled from the extension's EDT NAME like PROJECT_REL. The tests only READ it, but a
# stale EDT model can autosave a manual editor edit back to it, so the end-of-run cleanup
# reverts this too and re-syncs the model — a session must leave the WHOLE tree clean.
TESTS_PROJECT_REL = os.environ.get("MCP_TESTS_PROJECT_REL", "tests/tests")

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
# It has to be a LIST, and the list has to include what the suite actually RENAMES. A single
# Catalog probe could not see the one residue that really leaks: rename_metadata_object
# renames CommonModule.Calc -> Compute, and a probe that only asks for a Catalog answers
# "baseline is back" while the renamed common module is still in the model. The next tests to
# depend on model/disk agreement then fail far from the cause — a resync exporting the stale
# model over the clean tree, or a module whose IFile no longer resolves. So probe the
# canonical Catalog AND the common modules the write/rename tests touch.
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

# How many full revert + clean_project cycles reset_model may spend getting the model back
# to the baseline before it gives up and stops the run. >1 because the lost race it recovers
# from (an async disk export landing after the revert) is one-shot: the second cycle reverts
# what that export wrote and re-imports it. Not a timeout knob — each cycle re-does the work,
# it does not merely wait longer. Override with E2E_MODEL_RESET_ATTEMPTS.
MODEL_RESET_ATTEMPTS = int(os.environ.get("E2E_MODEL_RESET_ATTEMPTS", "3"))

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
    """Refuse every SUBSEQUENT MCP call, whatever is still holding the wire.

    Armed by a call timeout and by the orchestrator when it abandons a timed-out worker. Both
    mean the same thing: something we cannot cancel is still working, and every later request -
    a test's own teardown, a reset_model still in flight on the abandoned thread - would race it.
    Refusing at the ONE place they all pass through beats hoping each caller checks a flag.
    """
    global _TIMED_OUT, _ABORT_REASON
    _TIMED_OUT = True
    _ABORT_REASON = reason


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
    while True:
        result = Result(_post("tools/call", {"name": tool, "arguments": arguments}))
        if not _is_transient_building(result) or time.time() >= deadline:
            _record_call(tool)
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

_CALLED_TOOLS = set()
_BASELINE_INVENTORY = None


def _record_call(tool):
    _CALLED_TOOLS.add(tool)


def begin_test_calls():
    """Start recording which tools a test invokes (the orchestrator calls this per test)."""
    _CALLED_TOOLS.clear()


def _top_object_inventory():
    """A stable, cheap fingerprint of the model's top-level metadata objects.

    One call. It sees exactly the mutations a git-clean tree can still hide: an object
    created, deleted or renamed IN MEMORY without reaching disk. Returns None when it cannot
    be read, which callers must treat as "no evidence" (and therefore reset in full)."""
    try:
        r = call("get_metadata_objects", {"projectName": PROJECT, "limit": 1000})
    except Exception:
        return None
    if r.is_error or not (r.text or "").strip():
        return None
    return "\n".join(sorted(line.strip() for line in r.text.splitlines() if line.strip()))


def snapshot_model_baseline():
    """Record the pristine inventory once, before the first test runs."""
    global _BASELINE_INVENTORY
    _BASELINE_INVENTORY = _top_object_inventory()
    return _BASELINE_INVENTORY is not None


def model_is_pristine():
    """Is there POSITIVE evidence that the model still matches the committed baseline?

    False whenever the evidence is missing or ambiguous - the caller then does the full
    reset, so a wrong answer here costs time, never correctness."""
    if _BASELINE_INVENTORY is None:
        return False
    if _CALLED_TOOLS & DEEP_MUTATION_TOOLS:
        return False
    if all_fixtures_status().strip():
        return False
    return _top_object_inventory() == _BASELINE_INVENTORY


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


def wait_for_project_ready(timeout=None):
    """Wait until every project is fully indexed (state 'ready') — i.e. none is still
    'building' its derived data AND none is 'not_available' (mid (re)load).

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
    The per-tool ProjectStateChecker guard is the real safety net — this only removes the
    test-timing flake so a normal run starts on a fully-indexed workspace.
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
    while time.time() < deadline:
        try:
            text = (call("list_projects", {}).text or "").lower()
            if text and "building" not in text and "not_available" not in text:
                return True
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
    return False


# ──────────────────────────────────────────────────────────────────────────────
# git fixture (TestConfiguration is the committed baseline; on-disk truth = git)
# ──────────────────────────────────────────────────────────────────────────────
def _git(*args):
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
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )


# Both fixture projects. The BASE is the one tests mutate (reset before every test); the
# EXTENSION is read-only to the tests but the end-of-run cleanup reverts it too.
ALL_FIXTURE_RELS = [PROJECT_REL, TESTS_PROJECT_REL]


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


def reset_fixture():
    """Hard reset the BASE fixture to HEAD. Called before EVERY test (never trust the prev)."""
    _reset_rel(PROJECT_REL)


def reset_all_fixtures():
    """Hard reset EVERY fixture path (base + extension) to HEAD — used by the end-of-run
    cleanup so the whole working tree returns to the committed baseline."""
    for rel in ALL_FIXTURE_RELS:
        _reset_rel(rel)


def _status_porcelain():
    # Strip only TRAILING newlines. A bare .strip() also eats the LEADING space of the
    # first porcelain line (status column "XY" -> " M file" becomes "M file"), which
    # shifts the fixed-width `line[3:]` path slice by one and breaks path parsing in
    # assert_diff_contains / assert_diff_paths. Leading whitespace is significant here.
    return _git("status", "--porcelain", "--", PROJECT_REL).stdout.rstrip("\r\n")


def diff():
    return _git("diff", "--", PROJECT_REL).stdout


def read_disk(relpath):
    with open(os.path.join(PROJECT_DIR, relpath), encoding="utf-8") as f:
        return f.read()


def _baseline_is_back():
    """Did the model actually return to the committed baseline? (reset post-condition)

    'clean_project returned ok' and 'the project reports ready' are both SIGNALS, not
    proof: they say EDT finished the work it knew about, not that the model now matches
    the committed fixture. Only reading the model says that. So probe the objects every
    baseline is guaranteed to have and no test is allowed to leave renamed or deleted — if
    ALL of BASELINE_PROBE_FQNS resolve, the re-import landed.

    Probing ALL of them, not one, is the point: the residue that actually leaks is a RENAMED
    COMMON MODULE (rename_metadata_object turns CommonModule.Calc into Compute), and a probe
    that only asked for a Catalog reported "baseline is back" while that rename was still in
    the model. One request carries the whole list, so the stronger check costs nothing.

    Best-effort by construction: any failure to read them counts as 'not back' and the caller
    retries the whole revert+clean cycle. A call TIMEOUT still propagates (see call()).
    """
    try:
        r = call("get_metadata_details",
                 {"projectName": PROJECT, "objectFqns": list(BASELINE_PROBE_FQNS)})
    except E2ECallTimeout:
        raise
    except Exception:
        return False
    # Require POSITIVE evidence: a non-error result whose body actually says something and
    # does not report the object missing. An empty body must not read as "baseline is back" —
    # that is an unexplained response, and treating it as proof is how a stale model slips
    # through into the next test.
    text = (r.text or "").strip()
    return (not r.is_error) and bool(text) and "not found" not in text.lower()


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
    (_baseline_is_back) instead of assuming it. Verification — not a longer timeout — is
    what makes this correct: the failure is a lost write-back race, not slowness.
    """
    for _ in range(MODEL_RESET_ATTEMPTS):
        cleaned = False
        for _ in range(3):
            # Settle BEFORE the revert: out-wait the recompute (so the clean is accepted) and
            # give any lagging disk export time to land, so the revert below is the last write.
            wait_for_project_ready(timeout=MODEL_SETTLE_TIMEOUT)
            # Re-revert: undo whatever that late export wrote over the orchestrator's revert.
            # Cheap local git and idempotent, so doing it on the first pass too costs nothing.
            reset_fixture()
            try:
                if not call("clean_project", {"projectName": PROJECT}).is_error:
                    cleaned = True
                    break
            except E2ECallTimeout:
                # The one failure a best-effort catch must NOT swallow: the server is still running
                # that call, so retrying - or reporting success - hides it from the runner, the only
                # place that can stop the run before the next test reads a model it is still writing.
                raise
            except Exception:
                pass
        if not cleaned:
            # Three refusals in a row: the model still carries the finished test's write, and the next
            # test would read it. That is the cascade this reset exists to prevent, so stop the run
            # instead of continuing on a model we know is stale.
            raise E2EModelResetFailed(
                "clean_project did not succeed in 3 attempts, so the in-memory model still carries "
                "the last test's write. Continuing would hand it to the next test.")
        # Final settle: clean_project's revalidation re-triggers derived data; make sure the
        # next test starts on a fully-indexed model regardless of which branch above we took.
        # A negative result here is the same hazard as the exhausted-retries branch above (the
        # model is not guaranteed to be back in sync) and must not be swallowed either.
        if not wait_for_project_ready(timeout=MODEL_SETTLE_TIMEOUT):
            raise E2EModelResetFailed(
                "clean_project succeeded, but the final settle did not report the project ready "
                "within %ds, so the model is not guaranteed to be back in sync." % MODEL_SETTLE_TIMEOUT)
        if _baseline_is_back():
            return
    # Every attempt reported success and the model STILL does not match the baseline. Continuing
    # would hand the previous test's mutation to the next one (exactly the cascade this reset
    # exists to prevent), and the next failure would be reported against an innocent test.
    raise E2EModelResetFailed(
        "the model still does not resolve the baseline object %s after %d revert+clean_project "
        "cycles, even though every clean_project reported ok and the project reported ready. The "
        "in-memory model does not match the committed fixture; the next test would read the last "
        "test's write." % (BASELINE_PROBE_FQN, MODEL_RESET_ATTEMPTS))


def all_fixtures_status():
    """Porcelain status across EVERY fixture path (base + extension). The end-of-run gate
    uses this so a session that leaves ANY fixture dirty is VISIBLE — 'no diff' then means
    the run touched nothing it should not have."""
    parts = []
    for rel in ALL_FIXTURE_RELS:
        s = _git("status", "--porcelain", "--", rel).stdout.rstrip("\r\n")
        if s:
            parts.append(s)
    return "\n".join(parts)


def final_cleanup():
    """Leave the working tree verifiably clean ('no diff' == the session passed and left
    nothing behind).

    Reverts BOTH fixtures on disk, then clean_projects BOTH, with the SAME retry-until-synced
    contract as reset_model() (wait for the project to settle, THEN clean_project, retried up
    to 3 times each): call() only raises on a TIMEOUT, so a clean_project that came back with
    isError (e.g. the derived-data pipeline outlived BUILDING_RETRY_TIMEOUT and the server
    refused it) used to be swallowed by a bare `except Exception: pass`, silently declaring an
    unsynchronised model clean. The clean_project is the part that defeats the autosave
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
        cleaned = False
        for _ in range(3):
            wait_for_project_ready(timeout=MODEL_SETTLE_TIMEOUT)
            try:
                if not call("clean_project", {"projectName": proj}).is_error:
                    cleaned = True
                    break
            except E2ECallTimeout:
                # The one failure a best-effort catch must NOT swallow: the server is still running
                # that call, so retrying - or reporting success - hides it from the runner, the only
                # place that can stop the run before the next test reads a model it is still writing.
                raise
            except Exception:
                pass
        if not cleaned:
            raise E2EModelResetFailed(
                "clean_project did not succeed in 3 attempts for project %r, so its in-memory "
                "model may still carry an unsynchronised change - reporting this run clean would "
                "be a lie." % proj)
    if not wait_for_project_ready(timeout=MODEL_SETTLE_TIMEOUT):
        raise E2EModelResetFailed(
            "clean_project succeeded for every project, but the final settle did not report "
            "every project ready within %ds, so the model is not guaranteed to be back in "
            "sync." % MODEL_SETTLE_TIMEOUT)
    reset_all_fixtures()


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


def tree_snapshot():
    """Capture the BASE fixture's full on-disk state for a later 'changed NOTHING'
    comparison: porcelain status (every untracked file listed individually), the
    tracked content diff vs HEAD (staged + unstaged), and a content hash of each
    untracked file (so an in-place rewrite of a brand-new file is caught too).

    For tests whose SETUP legitimately dirties the tree (e.g. seeding a referenced
    catalog before probing a blocked delete): plain assert_no_diff would flag the
    seeding itself. Snapshot AFTER the seeding, run the operation under test, then
    assert_tree_unchanged(snapshot) — asserting the operation added nothing on top."""
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


def e2e_test(tool, kind="read"):
    """Register a test function. kind: 'read' | 'write' | 'action'."""
    def deco(fn):
        REGISTRY.append({"func": fn, "tool": tool, "kind": kind, "name": fn.__name__})
        return fn
    return deco
