"""
e2e tests for debug_pause (kind: read).

debug_pause is a DEBUG/RUNTIME tool: it asks a running debug session to suspend, waits
(bounded) until a suspended thread is actually observed, and returns that thread's stack
in the wait_for_break shape. It is a JSON-response tool, so a ToolResult.error payload is
a structured isError:true response whose structuredContent is exactly
{"success": false, "error": ...}.

Classified kind="read" for the fixture contract: it drives the live Eclipse debug model,
never the git-tracked project source, so every test ends with assert_no_diff().

ENVIRONMENT: these tests assert the NO-SESSION contract. The two blank-id tests would
pause a real session wherever one is active (a live stand, an attended run), so they SKIP
unless debug_status reports no active launch and no debug-server target. The explicit
unknown-id tests never auto-resolve (DebugTargetResolver does not fall back to a lone
session for a concrete id), so they run everywhere. The real pause -> inspect -> resume
round-trip lives in test_live_roundtrip.py behind EDT_MCP_LIVE_INFOBASE.
"""

import time

from harness import (
    E2ESkip,
    call,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
)

BLANK_ID_ERROR = (
    "applicationId is required - no single active debug session is available for "
    "auto-resolution (none or several are running). Use debug_status to list active sessions.")

# An unresolved id answers at once: nothing is requested, so the wait window never opens.
PROMPT_SECONDS = 30


def _unknown_id_error(app_id):
    return ("No active debug session for applicationId: %s. Use debug_status to list active "
            "sessions and their applicationId." % app_id)


def _require_no_debug_session():
    """SKIP when any debug session is live: a blank-id call would pause it for real."""
    st = call("debug_status", {}).structured or {}
    launches = st.get("launches") or []
    targets = st.get("debugServerTargets") or []
    if launches or targets:
        raise E2ESkip("a debug session is active (%d launch(es), %d debug-server target(s)); "
                      "a blank-id debug_pause would pause it" % (len(launches), len(targets)))


def _assert_plain_error(r, expected, ctx):
    """The exact message, and nothing else: no pause keys, no mutation markers."""
    err = assert_error(r, ctx)
    if err != expected:
        raise AssertionError("%s: expected the exact error %r, got %r" % (ctx, expected, err))
    sc = r.structured or {}
    if set(sc.keys()) != {"success", "error"}:
        raise AssertionError("%s: a refusal must carry only success+error, got %r" % (ctx, sc))
    return err


# ──────────────────────────────────────────────────────────────────────────────
# Blank id: auto-resolution finds nothing to pause
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="debug_pause", kind="read")
def test_no_app_and_no_session_returns_actionable_sentinel():
    """No applicationId and no active session: the auto-resolution sentinel, naming the
    missing argument and the tool that lists sessions."""
    _require_no_debug_session()
    r = call("debug_pause", {})
    err = _assert_plain_error(r, BLANK_ID_ERROR, "blank id, no session")
    assert_error_quality(err, names=["applicationId", "auto-resolution"], suggests=["debug_status"],
                         ctx="blank-id sentinel is clear and actionable")
    assert_no_diff("a debug tool must not touch project source")


@e2e_test(tool="debug_pause", kind="read")
def test_empty_string_app_is_treated_as_omitted():
    """applicationId "" is the omitted id: the same sentinel, not a pause keyed by ""."""
    _require_no_debug_session()
    r = call("debug_pause", {"applicationId": ""})
    _assert_plain_error(r, BLANK_ID_ERROR, "empty-string id")
    assert_no_diff("a rejected call must not touch project source")


# ──────────────────────────────────────────────────────────────────────────────
# Explicit unknown id: refused at once, never a wait and never a fallback
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="debug_pause", kind="read")
def test_unknown_launch_id_is_refused_promptly_even_with_max_timeout():
    """A launch:<name> id that matches no session is refused by name without opening the
    wait window: timeout 600 must not turn a missing session into a 10-minute call."""
    app_id = "launch:NoSuchPause_e2e"
    started = time.time()
    r = call("debug_pause", {"applicationId": app_id, "timeout": 600})
    elapsed = time.time() - started
    err = _assert_plain_error(r, _unknown_id_error(app_id), "unknown launch id")
    assert_error_quality(err, names=[app_id], suggests=["debug_status"],
                         ctx="unknown id names itself and the listing tool")
    if elapsed >= PROMPT_SECONDS:
        raise AssertionError("an unresolved id must be refused at once, took %.1fs" % elapsed)
    assert_no_diff("a debug tool must not touch project source")


@e2e_test(tool="debug_pause", kind="read")
def test_unknown_server_application_id_is_refused():
    """The ServerApplication.<app> form goes through the debug-server target view; an
    application with no live target is refused the same way."""
    app_id = "ServerApplication.NoSuchPause_e2e"
    r = call("debug_pause", {"applicationId": app_id, "timeout": 1})
    _assert_plain_error(r, _unknown_id_error(app_id), "unknown ServerApplication id")
    assert_no_diff("a debug tool must not touch project source")


@e2e_test(tool="debug_pause", kind="read")
def test_non_numeric_timeout_falls_back_not_crash():
    """timeout "abc" falls back to the default instead of leaking a parse exception; the
    unknown attach:<name> id is still refused by name."""
    app_id = "attach:NoSuchPause_e2e"
    r = call("debug_pause", {"applicationId": app_id, "timeout": "abc"})
    err = _assert_plain_error(r, _unknown_id_error(app_id), "non-numeric timeout")
    if "NumberFormatException" in err:
        raise AssertionError("a bad timeout must not leak a parse exception: %r" % err)
    assert_no_diff("a debug tool must not touch project source")
