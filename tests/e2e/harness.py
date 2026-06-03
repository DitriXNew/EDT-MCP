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

_REQUEST_ID = 0


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


def _post(method, params):
    global _REQUEST_ID
    _REQUEST_ID += 1
    body = json.dumps({
        "jsonrpc": "2.0", "id": _REQUEST_ID, "method": method, "params": params,
    }).encode("utf-8")
    req = urllib.request.Request(MCP_URL, data=body, headers={
        "Content-Type": "application/json; charset=utf-8",
        "Accept": "application/json, text/event-stream",
    })
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            text = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
    # Streamable HTTP / SSE framing: "data: {json}"
    m = re.search(r"data:\s*(\{.*\})\s*$", text, re.S)
    if m:
        text = m.group(1)
    return json.loads(text)


def call(tool, arguments):
    """Send tools/call and return a Result."""
    return Result(_post("tools/call", {"name": tool, "arguments": arguments}))


def initialize(capabilities=None):
    return _post("initialize", {
        "protocolVersion": "2025-11-25",
        "capabilities": capabilities or {},
        "clientInfo": {"name": "e2e", "version": "1"},
    })


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
    return subprocess.run(
        ["git", "-C", REPO_ROOT, *args],
        capture_output=True, text=True,
    )


def reset_fixture():
    """Hard reset the fixture to the committed baseline. Called before EVERY test."""
    _git("checkout", "--", PROJECT_REL)
    _git("clean", "-fd", PROJECT_REL)


def _status_porcelain():
    return _git("status", "--porcelain", "--", PROJECT_REL).stdout.strip()


def diff():
    return _git("diff", "--", PROJECT_REL).stdout


def read_disk(relpath):
    with open(os.path.join(PROJECT_DIR, relpath), encoding="utf-8") as f:
        return f.read()


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


def assert_no_diff(ctx=""):
    """Non-destructive guardrail: the project working tree must be clean (no mod, no new files)."""
    st = _status_porcelain()
    if st:
        _fail("expected NO change to %s but found [%s]:\n%s" % (PROJECT_REL, ctx, st[:500]))


def assert_diff_contains(substr, ctx=""):
    """The on-disk change includes substr (works for modified tracked files AND new untracked files)."""
    if substr in diff():
        return
    for line in _status_porcelain().splitlines():
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
          % (substr, ctx, diff()[:400], _status_porcelain()[:300]))


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
