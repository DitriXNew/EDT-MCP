"""
e2e tests for get_mcp_history (kind: read).

WHAT THE TOOL DOES
------------------
get_mcp_history returns this server's in-memory MCP call-history ring (the
request/response exchanges captured at the transport choke point) so an AI client can
introspect its OWN traffic: which tools it called, how long they took, which failed,
and what has been filling its context. It is read-only (a snapshot; no model / tx /
session). Filters (AND): tool (substring over the tool name / method), status
(all|error|ok), minDurationMs, sinceMs/untilMs (half-open epoch-ms window); newest
first, capped by limit. Records are metadata only by default; includeBodies attaches
the raw payloads, includeStats appends the aggregated per-tool statistics.

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success:  {"success": true, "matched", "returned", "limit", "totalRecorded",
             "recording", "bufferSize",
             "records": [{"timestampMs","method","tool","durationMs","status",
                          "requestChars","responseChars",
                          (+ "requestJson","responseJson" when includeBodies)}],
             (+ "stats": {"summary","rows","top3"} when includeStats)}
  error:    {"success": false, "error": "..."}

CI STRATEGY
-----------
The tool records the live server's own traffic, so by the time these tests run the
harness has already issued many calls -> the ring is populated (recording is on by
default). Every assertion is envelope/shape based, never a fixed count, so it is
deterministic regardless of how many calls preceded it. The bad-enum branch validates
BEFORE touching the ring. get_mcp_history is read-only and never writes the project
tree: assert_no_diff() on every path.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
)

_ENVELOPE_KEYS = ("matched", "returned", "limit", "totalRecorded", "recording",
                  "bufferSize", "records")


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SHAPE
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_mcp_history", kind="read")
def test_returns_history_envelope_metadata_only():
    """A bare (limit-capped) call returns the success envelope, caps the page at limit,
    and the records are METADATA ONLY by default (no raw request/response bodies)."""
    r = call("get_mcp_history", {"limit": 5})
    assert_ok(r, "get_mcp_history basic page")
    s = r.structured or {}
    if not s.get("success"):
        raise AssertionError("expected success envelope, got: %r" % (s,))
    for key in _ENVELOPE_KEYS:
        if key not in s:
            raise AssertionError("structuredContent missing %r: %r" % (key, s))
    records = s.get("records") or []
    if len(records) > 5:
        raise AssertionError("limit=5 must cap the page, got %d records" % len(records))
    if s.get("returned") != len(records):
        raise AssertionError("returned must equal len(records): %r" % s)
    for rec in records:
        if "requestJson" in rec or "responseJson" in rec:
            raise AssertionError("default page must be metadata-only, got bodies: %r" % rec)
        for field in ("timestampMs", "method", "tool", "durationMs", "status"):
            if field not in rec:
                raise AssertionError("record missing metadata field %r: %r" % (field, rec))
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_mcp_history", kind="read")
def test_bodies_are_opt_in():
    """includeBodies attaches the raw request/response payloads. The default page carries
    none; with includeBodies the recorded tools/call records expose a requestJson."""
    default = call("get_mcp_history", {"limit": 10})
    assert_ok(default, "default page (no bodies)")
    for rec in (default.structured or {}).get("records") or []:
        if "requestJson" in rec or "responseJson" in rec:
            raise AssertionError("default must be metadata-only: %r" % rec)

    withb = call("get_mcp_history", {"limit": 10, "includeBodies": True})
    assert_ok(withb, "page with includeBodies")
    recs = (withb.structured or {}).get("records") or []
    tool_calls = [rec for rec in recs if rec.get("method") == "tools/call"]
    # A tools/call always has a request body; if any are on this page, includeBodies must expose it.
    if tool_calls and not any("requestJson" in rec for rec in tool_calls):
        raise AssertionError("includeBodies must attach request bodies to tools/call records: %r"
                             % (tool_calls,))
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_mcp_history", kind="read")
def test_include_stats_appends_block():
    """includeStats appends the aggregated statistics block (summary + rows + top3),
    always present regardless of how many records matched."""
    r = call("get_mcp_history", {"includeStats": True})
    assert_ok(r, "get_mcp_history includeStats")
    s = r.structured or {}
    stats = s.get("stats")
    if not isinstance(stats, dict):
        raise AssertionError("includeStats must append a stats object: %r" % (s,))
    for key in ("summary", "rows", "top3"):
        if key not in stats:
            raise AssertionError("stats block missing %r: %r" % (key, stats))
    summary = stats.get("summary") or {}
    for key in ("totalCalls", "totalOutputChars", "approxTotalTokens", "totalErrors"):
        if key not in summary:
            raise AssertionError("stats summary missing %r: %r" % (key, summary))
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="get_mcp_history", kind="read")
def test_tool_filter_can_empty_the_page():
    """The tool filter is an AND substring over the call key; a substring that matches
    nothing yields a valid, empty page (matched 0), not an error."""
    r = call("get_mcp_history", {"tool": "no_such_tool_zzz_e2e", "limit": 5})
    assert_ok(r, "non-matching tool filter")
    s = r.structured or {}
    if s.get("matched") != 0:
        raise AssertionError("a non-matching tool filter must yield matched 0: %r" % (s,))
    if s.get("records"):
        raise AssertionError("a non-matching tool filter must return no records: %r" % (s,))
    assert_no_diff("a read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE / ERROR-QUALITY (validated before the ring is read)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_mcp_history", kind="read")
def test_invalid_status_is_rejected_actionably():
    """status is a closed set {all, error, ok}; an out-of-set value is rejected up front,
    echoing the bad value AND enumerating the valid set."""
    bad = "sideways_e2e"
    r = call("get_mcp_history", {"status": bad})
    err = assert_error(r, "out-of-set status")
    assert_error_quality(err, names=[bad], suggests=["all", "error", "ok"],
                         ctx="invalid status echoes the bad value and lists the valid tokens")
    assert_no_diff("a rejected call must not touch the fixture")
