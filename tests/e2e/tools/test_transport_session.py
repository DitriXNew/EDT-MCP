"""
e2e tests for the Streamable HTTP transport's SESSION layer and Origin allow-list
(issues #564 and #561). Not a tool: these assert the wire contract of the server
itself, which no per-tool test can reach.

Why raw urllib instead of harness.call(): both contracts are HTTP STATUS CODES
(400 / 404 / 403), and the harness deliberately hides them - _post() reads an
HTTPError's body and hands back the parsed JSON-RPC payload, so a test written
through it could not tell 404 from 200-with-an-error. These requests are also
deliberately malformed (no session, a forged session, a hostile Origin), which
is not something the shared client should learn to do.

Isolation: every test mints its OWN session with a fresh initialize and only ever
closes that one. The harness's session (captured by run_all's handshake) is never
touched, so a DELETE here cannot log the rest of the suite out.
"""

import json
import urllib.error
import urllib.request

import harness
from harness import MCP_URL, assert_no_diff, e2e_test, _fail


PROTOCOL_VERSION = harness.PROTOCOL_VERSION
SESSION_HEADER = "Mcp-Session-Id"


class _Headers(dict):
    """Response headers looked up without regard to case.

    HTTP header names are case-insensitive and the JDK's HttpServer normalises what it sends
    to its own spelling - the server declares "MCP-Session-Id" and the wire carries
    "Mcp-session-id". A plain dict keyed by the sent spelling therefore misses the header the
    test is looking for, which is exactly how the first version of this file failed.
    """

    def __init__(self, message):
        super().__init__((k, v) for k, v in message.items())
        self._lower = {k.lower(): v for k, v in message.items()}

    def get(self, name, default=None):
        return self._lower.get(name.lower(), default)


def _raw(method, params=None, session=None, origin=None, http_method="POST", request_id=1):
    """One request, returning (status, headers, parsed-body-or-None).

    An HTTP error status is a RESULT here, not an exception: the status is the
    thing under test.
    """
    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": PROTOCOL_VERSION,
    }
    if session:
        headers[SESSION_HEADER] = session
    if origin:
        headers["Origin"] = origin

    data = None
    if http_method == "POST":
        data = json.dumps({
            "jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {},
        }).encode("utf-8")

    req = urllib.request.Request(MCP_URL, data=data, headers=headers, method=http_method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, _Headers(resp.headers), _body(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, _Headers(e.headers), _body(e.read())


def _body(raw):
    text = raw.decode("utf-8", "replace").strip()
    if not text:
        return None
    # The server frames a response as SSE when the client accepts it; unwrap the data line.
    if text.startswith("event:") or text.startswith("data:"):
        for line in text.splitlines():
            if line.startswith("data:"):
                text = line[len("data:"):].strip()
                break
    try:
        return json.loads(text)
    except ValueError:
        return {"_unparsed": text}


def _open_session():
    """A fresh initialize, returning its issued session id."""
    status, headers, body = _raw("initialize", {
        "protocolVersion": PROTOCOL_VERSION,
        "capabilities": {},
        "clientInfo": {"name": "e2e-transport-session", "version": "1.0"},
    })
    if status != 200:
        _fail("initialize must be answered 200, got %s: %s" % (status, body))
    session = headers.get(SESSION_HEADER)
    if not session:
        _fail("initialize must issue an %s header; got headers %s" % (SESSION_HEADER, sorted(headers)))
    return session


def _error_text(body):
    return json.dumps(body) if body is not None else "<empty body>"


# ──────────────────────────────────────────────────────────────────────────────
# Sessions (#564)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="_transport_session", kind="read")
def test_initialize_issues_a_session_that_then_works():
    session = _open_session()

    status, _, body = _raw("tools/list", session=session, request_id=2)

    if status != 200:
        _fail("a request carrying the issued session must be served, got %s: %s"
              % (status, _error_text(body)))
    if not (body or {}).get("result", {}).get("tools"):
        _fail("tools/list on a valid session must return tools: %s" % _error_text(body))
    assert_no_diff("the session handshake must not mutate the project")


@e2e_test(tool="_transport_session", kind="read")
def test_a_failed_initialize_issues_no_session():
    # A session issued alongside an error would tell a client whose handshake FAILED that it may
    # proceed, and would spend a slot toward the session cap - so a stream of malformed
    # initialize requests could exhaust the registry without ever completing a handshake.
    # An unsupported JSON-RPC version is the cheapest way to make initialize fail.
    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": PROTOCOL_VERSION,
    }
    body = json.dumps({
        "jsonrpc": "1.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": PROTOCOL_VERSION, "capabilities": {}},
    }).encode("utf-8")
    req = urllib.request.Request(MCP_URL, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=60) as resp:
        issued = _Headers(resp.headers).get(SESSION_HEADER)
        answered = _body(resp.read())

    if "error" not in (answered or {}):
        _fail("test premise: this initialize must fail; got %s" % _error_text(answered))
    if issued:
        _fail("a failed initialize must issue no %s, got %r" % (SESSION_HEADER, issued))


@e2e_test(tool="_transport_session", kind="read")
def test_a_request_without_a_session_is_refused_with_400():
    # The drive-by case: a POST that never initialized. Before #564 this was served.
    status, _, body = _raw("tools/list", request_id=3)

    if status != 400:
        _fail("a non-initialize request with NO %s must answer 400, got %s: %s"
              % (SESSION_HEADER, status, _error_text(body)))
    message = json.dumps(body or {})
    if SESSION_HEADER.lower() not in message.lower():
        _fail("the refusal must name the missing header so a client can fix it: %s" % message)
    if "initialize" not in message:
        _fail("the refusal must say what to do about it: %s" % message)


@e2e_test(tool="_transport_session", kind="read")
def test_an_unknown_session_is_refused_with_404():
    status, _, body = _raw("tools/list", session="00000000-dead-beef-0000-000000000000", request_id=4)

    if status != 404:
        _fail("a session id this server never issued must answer 404, got %s: %s"
              % (status, _error_text(body)))
    if "initialize" not in json.dumps(body or {}):
        _fail("the refusal must tell the client to initialize again: %s" % _error_text(body))


@e2e_test(tool="_transport_session", kind="read")
def test_delete_terminates_the_session_and_it_then_404s():
    session = _open_session()

    status, _, _ = _raw(None, session=session, http_method="DELETE")
    if status != 200:
        _fail("DELETE /mcp must answer 200, got %s" % status)

    status, _, body = _raw("tools/list", session=session, request_id=5)
    if status != 404:
        _fail("a terminated session must answer 404 - DELETE used to terminate nothing. "
              "Got %s: %s" % (status, _error_text(body)))

    # Idempotent: closing it again is still a 200, not an error.
    status, _, _ = _raw(None, session=session, http_method="DELETE")
    if status != 200:
        _fail("a repeated DELETE must stay 200 (idempotent), got %s" % status)


@e2e_test(tool="_transport_session", kind="read")
def test_two_sessions_are_independent():
    first = _open_session()
    second = _open_session()
    if first == second:
        _fail("each initialize must mint its own session id")

    _raw(None, session=first, http_method="DELETE")

    status, _, _ = _raw("tools/list", session=second, request_id=6)
    if status != 200:
        _fail("closing one client's session must not disconnect another, got %s" % status)


# ──────────────────────────────────────────────────────────────────────────────
# Origin allow-list (#561)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="_transport_session", kind="read")
def test_a_loopback_origin_is_still_served():
    session = _open_session()

    for origin in ("http://localhost:3000", "http://127.0.0.1:8765", "http://[::1]:3000"):
        status, _, body = _raw("tools/list", session=session, origin=origin, request_id=7)
        if status != 200:
            _fail("a loopback Origin (%s) must still be served, got %s: %s"
                  % (origin, status, _error_text(body)))


@e2e_test(tool="_transport_session", kind="read")
def test_attacker_reachable_origins_are_refused_with_403():
    # Each of these is producible by a hostile page: "null" from a sandboxed iframe, a data: URL
    # or a cross-origin redirect; file:// from a downloaded page; vscode-webview:// from ANY
    # extension's webview (the host part is a per-webview UUID, never an extension id). All three
    # were on the allow-list, and on a default install (loopback, no token) the Origin check is
    # the only thing between such a page and evaluate_expression.
    session = _open_session()

    for origin in ("null", "file:///C:/page.html", "vscode-webview://abc123",
                   "https://evil.example.com", "http://localhost.attacker.com"):
        status, _, body = _raw("tools/list", session=session, origin=origin, request_id=8)
        if status != 403:
            _fail("Origin %r must be refused with 403, got %s: %s"
                  % (origin, status, _error_text(body)))


@e2e_test(tool="_transport_session", kind="read")
def test_a_refused_origin_gets_no_cors_grant():
    # The refusal must not also hand the page permission to READ the answer.
    _, headers, _ = _raw("tools/list", origin="null", request_id=9)

    granted = headers.get("Access-Control-Allow-Origin")
    if granted:
        _fail("a refused Origin must not receive Access-Control-Allow-Origin, got %r" % granted)
    assert_no_diff("a refused request must not mutate the project")
