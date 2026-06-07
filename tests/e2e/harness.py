#!/usr/bin/env python3
"""
EDT-MCP e2e harness — the shared base every per-tool test imports.

Owns: the HTTP/JSON-RPC(+SSE) client, the git-fixture isolation helpers
(TestConfiguration is a committed fixture; on-disk truth is git), and all
assertion helpers including error-quality. Tests call these; they never
re-implement them. See SKILL.md for the full guide.

Python stdlib only. No third-party dependencies.
"""

import json
import os
import re
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
    req = urllib.request.Request(MCP_URL, data=body, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=CALL_TIMEOUT) as resp:
            sid = resp.headers.get("Mcp-Session-Id")
            if sid:
                _SESSION_ID = sid
            text = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
    return _parse_response(text)


def call(tool, arguments):
    """Send tools/call and return a Result."""
    return Result(_post("tools/call", {"name": tool, "arguments": arguments}))


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
    deadline = time.time() + timeout
    last_log = 0.0
    while time.time() < deadline:
        try:
            text = (call("list_projects", {}).text or "").lower()
            if text and "building" not in text and "not_available" not in text:
                return True
        except Exception:
            pass
        now = time.time()
        if now - last_log >= 15:
            print("  [wait_for_project_ready] config still indexing (%ds left of %ds)..."
                  % (int(deadline - now), timeout), flush=True)
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
    # missed them. git emits content as UTF-8 and quotes non-ASCII PATHS as ASCII
    # octal escapes (core.quotepath), so utf-8 decoding is always safe here.
    return subprocess.run(
        ["git", "-C", REPO_ROOT, *args],
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


def reset_model():
    """Re-sync EDT's in-memory BM model to the on-disk baseline.

    Metadata-write tools (create/add/delete/rename metadata) mutate the in-memory
    BM model but do NOT flush every change to disk, so a git reset alone cannot
    undo them — the model would carry the unsaved change into the next test.
    clean_project refreshes files from disk + revalidates, discarding unsaved model
    changes. The orchestrator calls this after each kind='write-metadata' test.

    clean_project's revalidation re-triggers derived-data computation (the reference
    index), so we then wait for the project to settle — otherwise the NEXT test (e.g. a
    cascade rename/delete) could run mid-reindex and flake.
    """
    try:
        call("clean_project", {"projectName": PROJECT})
    except Exception:
        pass
    wait_for_project_ready()


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

    Reverts BOTH fixtures on disk, then clean_projects BOTH. The clean_project is the part
    that defeats the autosave resurrection: it tears down EDT's in-memory model and
    re-imports it from the now-clean disk (synchronously — the call blocks on the project
    restart + derived-data rebuild), so a STALE model (e.g. a manual edit made in the EDT
    editor, or a metadata write whose model change was not flushed) no longer has a pending
    change to AUTOSAVE back and re-dirty the tree (the Compute/Goods whack-a-mole). The
    final reset_all_fixtures() only mops up any file clean_project itself re-touched (e.g. a
    CRLF/marker touch). Best-effort on the MCP calls (a wedged EDT must not make teardown
    raise). Run at startup AND at the end."""
    reset_all_fixtures()
    for proj in (PROJECT, TESTS_PROJECT):
        try:
            call("clean_project", {"projectName": proj})
        except Exception:
            pass
    wait_for_project_ready()
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
    effort — never raises, so it can run in a finally block."""
    try:
        call("terminate_launch", {"all": True, "confirm": True})
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
