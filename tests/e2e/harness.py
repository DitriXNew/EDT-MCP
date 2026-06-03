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
PROJECT = os.environ.get("MCP_PROJECT", "TestConfiguration")

HARNESS_DIR = os.path.dirname(os.path.abspath(__file__))          # tests/e2e
REPO_ROOT = os.path.abspath(os.path.join(HARNESS_DIR, "..", ".."))
PROJECT_REL = PROJECT                                             # git path rel to repo root
PROJECT_DIR = os.path.join(REPO_ROOT, PROJECT)                    # absolute project dir

MCP_URL = "http://%s:%s/mcp" % (MCP_HOST, MCP_PORT)
HEALTH_URL = "http://%s:%s/health" % (MCP_HOST, MCP_PORT)

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


def _fail(msg):
    raise E2EAssertion(msg)


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
        with urllib.request.urlopen(req, timeout=180) as resp:
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


def reset_fixture():
    """Hard reset the fixture to the committed baseline (HEAD). Called before EVERY test.

    Metadata delete/rename/create operations persist to disk AND can leave the change
    STAGED in the index (observed: a renamed-to module appears as `A` staged). The
    revert therefore: (1) `reset` to UNSTAGE (staged add -> untracked; staged delete ->
    unstaged delete), (2) `checkout HEAD --` to restore tracked files (undo deletions /
    mods / renames-from), (3) `clean -fd` to remove the now-untracked files. Plain
    `checkout --` (from the index) cannot undo staged changes, so all three are needed."""
    _git("reset", "-q", "--", PROJECT_REL)
    _git("checkout", "HEAD", "--", PROJECT_REL)
    _git("clean", "-fd", PROJECT_REL)


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
    """
    try:
        call("clean_project", {"projectName": PROJECT})
    except Exception:
        pass


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
# Test registry (per-tool files register via @e2e_test; the orchestrator runs them)
# ──────────────────────────────────────────────────────────────────────────────
REGISTRY = []


def e2e_test(tool, kind="read"):
    """Register a test function. kind: 'read' | 'write' | 'action'."""
    def deco(fn):
        REGISTRY.append({"func": fn, "tool": tool, "kind": kind, "name": fn.__name__})
        return fn
    return deco
