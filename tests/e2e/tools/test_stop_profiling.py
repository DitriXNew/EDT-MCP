"""
e2e tests for stop_profiling (kind: read).

What the tool does
------------------
stop_profiling switches 1C performance measurement (замер производительности) OFF
on the active debug target for a given applicationId. It is the counterpart to
start_profiling and shares an on/off state keyed by applicationId
(StartProfilingTool.ACTIVE_APPLICATION_IDS, queried via isProfilingActive). The
underlying EDT primitive (IProfilingService.toggleProfiling) is a single toggle with
no public stop / is-active query, so this tool only calls toggle when the SHARED
state says profiling is currently ON; otherwise it must NOT toggle (that would
silently switch profiling ON). It is a pure DEBUG/RUNTIME tool: it walks the live
Eclipse debug model (DebugSessionRegistry) + reflective profiling services; it never
touches project source.

Response shape (IMPORTANT)
--------------------------
StopProfilingTool.getResponseType() == JSON, so the real payload lands in
Result.structured (NOT Result.text — for a JSON tool r.text is just a "Done"/error
placeholder). The benign success envelope (ToolResult.success() + .put(...)) is:
    {"success": true,
     "active": false,
     "stopped": <bool>,                       # false = nothing was running; true = toggled off
     "applicationId": <echoed id>,
     "message": <human-readable status>}
A ToolResult.error(...) (the missing-arg guard) is delivered as isError:true with the
message surfaced via r.error_text().

ENVIRONMENT / SENTINEL (the realistic happy contract here)
----------------------------------------------------------
This is a DEBUG/RUNTIME tool. In THIS EDT there is NO active debug session and
TestConfiguration has NO running infobase / launched application, and — crucially —
NO profiling was ever started, so StartProfilingTool.ACTIVE_APPLICATION_IDS is EMPTY.
For ANY applicationId we can supply, execute() therefore takes its FIRST branch:

    if (!StartProfilingTool.isProfilingActive(applicationId))   // always true here
        -> ToolResult.success()
             .put("active", false).put("stopped", false)
             .put("applicationId", applicationId)
             .put("message", "Profiling was not active for this applicationId;
                              nothing to stop.")

This is the documented IDEMPOTENT contract: stopping something that was never started
is a BENIGN SUCCESS, not an error (an error/forced toggle would have switched
profiling ON — the exact bug the branch guards against). The empty-but-well-formed
"nothing to stop" envelope IS the sentinel here, so the happy-path tests assert
assert_ok + that specific envelope (active:false, stopped:false, the echoed id, and
the "nothing to stop" message). We deliberately do NOT start a real infobase /
debug_launch + start_profiling (heavy, not configured for this fixture); the benign
idempotent branch + the negative matrix IS the coverage.

Branches NOT reachable without a live session (documented, not silently skipped):
  - the "No active debug target ... debug session has ended. Profiling state cleared."
    success branch — needs isProfilingActive==true (a prior start_profiling) but a
    now-dead target;
  - the genuine toggle-OFF path (stopped:true, "Call get_profiling_results ...");
  - "Debug target does not support profiling", "IProfilingService not available", and
    the bundle-missing / outer-catch errors — all need a live target / are internal.
  These require a real debug_launch + start_profiling against a running infobase,
  which this fixture does not provide; deferred to a live-session run. See the AUDIT
  notes below.

NEGATIVE MATRIX — what is client-reachable here
-----------------------------------------------
The ONLY declared parameter is `applicationId` and it IS required (input schema marks
it required; execute() guards with JsonUtils.requireArgument). So:
  - the reachable VALIDATION error is the missing/blank applicationId guard
    ("applicationId is required");
  - there is NO enum / XOR / conditional-required parameter to violate;
  - a NON-EXISTENT / bogus applicationId is NOT a validation error — by the idempotent
    contract it is a benign "nothing to stop" success (same as the happy path). Forcing
    an assert_error against a bogus id would be a CHEAT (SKILL §6): the tool genuinely
    cannot produce one here. We instead assert the real graceful-success contract for a
    bogus id (mutation-sensitive: a tool that errored, toggled, or echoed the wrong id
    fails it) and AUDIT the discoverability gap.

DIFF: a debug/profiling tool operates on the running infobase / EDT workspace, NEVER
on the git-tracked TestConfiguration tree, so EVERY test ends with assert_no_diff() —
a stop_profiling that mutated project source would be a bug, and this catches it.

Fixture inventory referenced (TestConfiguration, English Names): only the project
name "TestConfiguration" (used to shape a realistic applicationId); no project file is
read or written by this tool.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# The exact benign "nothing to stop" message emitted when profiling is not active.
# Asserted in a delimiter-free fragment so JSON/Gson escaping cannot break the match.
_NOTHING_TO_STOP = "Profiling was not active for this applicationId"


def _benign_not_active_envelope(r, app_id, ctx):
    """Validate the idempotent "nothing to stop" success envelope and return it.

    A JSON tool MUST populate structuredContent; a missing/typed-wrong envelope means
    the tool returned the wrong shape (a real regression), so we hard-fail. Every
    invariant below holds in THIS no-session fixture (isProfilingActive is always
    false), so each one breaks if the tool is broken:
      - success:true and NO 'error' key  -> it took the benign branch, not an error;
      - active:false                      -> it reports profiling is not running;
      - stopped:false                     -> it did NOT toggle (toggling an inactive id
                                             would switch profiling ON — the guarded bug);
      - applicationId == the echoed input -> it operated on the id we passed, not a
                                             default / wrong one;
      - message names the "nothing to stop" reason.
    """
    sc = r.structured
    if not isinstance(sc, dict):
        raise AssertionError("expected structuredContent dict [%s]: %r" % (ctx, sc))
    if sc.get("success") is not True:
        raise AssertionError("benign envelope must set success=true [%s]: %r" % (ctx, sc))
    if "error" in sc:
        raise AssertionError(
            "the idempotent not-active branch must NOT carry an 'error' field [%s]: %r" % (ctx, sc))
    if sc.get("active") is not False:
        raise AssertionError(
            "no profiling was ever started -> active must be False [%s]: %r" % (ctx, sc.get("active")))
    # The decisive anti-toggle invariant: stopping a never-started id must NOT toggle
    # (stopped:false). A regression that toggled regardless of state would set
    # stopped:true here and SILENTLY SWITCH PROFILING ON — exactly the bug the
    # isProfilingActive guard exists to prevent. This is the most mutation-sensitive
    # assertion in the file.
    if sc.get("stopped") is not False:
        raise AssertionError(
            "stopping a never-started id must NOT toggle -> stopped must be False [%s]: %r"
            % (ctx, sc.get("stopped")))
    if sc.get("applicationId") != app_id:
        raise AssertionError(
            "the tool must echo the requested applicationId %r, got %r [%s]"
            % (app_id, sc.get("applicationId"), ctx))
    msg = sc.get("message") or ""
    if _NOTHING_TO_STOP not in msg:
        raise AssertionError(
            "message must explain the not-active idempotent result [%s]: %r" % (ctx, msg))
    return sc


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL  (no profiling active -> benign idempotent "nothing to stop")
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="stop_profiling", kind="read")
def test_no_active_profiling_returns_benign_nothing_to_stop():
    """No profiling was started in this EDT (ACTIVE_APPLICATION_IDS is empty), so
    stop_profiling for any id takes its first branch: isProfilingActive==false ->
    a BENIGN success that did NOT toggle (active:false, stopped:false) and explains
    "nothing to stop". This is the documented idempotent contract and the realistic
    happy path here (we do not start a real profiling session).

    Mutation-sensitive on every axis (see _benign_not_active_envelope): a tool that
    wrongly errored on a not-active id fails assert_ok; a tool that toggled anyway
    (stopped:true) — silently switching profiling ON — fails the stopped:false
    invariant; a tool that echoed the wrong id or dropped the message also fails.
    """
    app_id = PROJECT  # a realistic, plainly-not-running applicationId
    r = call("stop_profiling", {"applicationId": app_id})
    assert_ok(r, "stop_profiling for a never-started id is benign, not an error")

    _benign_not_active_envelope(r, app_id, "no-active-profiling envelope")

    # Belt-and-suspenders on the wire payload (independent of the dict-typed checks):
    # the specific not-active message must be present. stop_profiling is a JSON tool,
    # so the message lives in structuredContent.message (r.text is just the digest).
    assert_contains(
        str((r.structured or {}).get("message", "")), _NOTHING_TO_STOP,
        "the not-active sentinel message must surface in the response payload",
    )

    # AUDIT: the idempotent "nothing to stop" result is benign-but-silent — it does not
    # point the caller at how to verify there IS a session to stop (e.g. debug_status /
    # get_applications to find a live applicationId, or start_profiling first). Arguably
    # fine for an idempotent stop, but it offers no discoverability when the caller
    # expected an active session. Not weakened (benign success IS the current contract);
    # fix-card: have the not-active message name a next step (debug_status/get_applications).
    assert_no_diff("a debug/profiling tool must not touch the project on disk")


@e2e_test(tool="stop_profiling", kind="read")
def test_unknown_application_id_is_idempotent_not_an_error():
    """A bogus / never-seen applicationId is NOT a validation error here: it is simply
    not in ACTIVE_APPLICATION_IDS, so isProfilingActive==false and the SAME benign
    "nothing to stop" success is returned (active:false, stopped:false, the bogus id
    echoed). This pins the idempotent contract: stop on an unknown id is a safe no-op,
    never a forced toggle and never an isError.

    Distinct from the happy test (which uses the project name): this proves the
    not-active branch is reached purely by the id being absent from the active set, for
    an id that resembles nothing real. A tool that toggled on an unknown id (stopped:true)
    would SWITCH PROFILING ON — caught by the stopped:false invariant.

    Forcing an assert_error here would be a CHEAT (SKILL §6) — the tool genuinely cannot
    produce a validation error for a well-formed-but-unknown id.
    """
    bogus = "no-such-app-id_ZZZ_e2e"
    r = call("stop_profiling", {"applicationId": bogus})
    if r.is_error:
        raise AssertionError(
            "an unknown applicationId must be a benign idempotent success, not isError: %s"
            % (r.error_text()[:200],))
    assert_ok(r, "unknown applicationId is a benign no-op")

    _benign_not_active_envelope(r, bogus, "unknown-id envelope")

    # AUDIT: an unknown applicationId yields a SILENT "nothing to stop" with no signal
    # distinguishing "this id is unknown / never profiled" from "it was profiling and is
    # now stopped". Debatable for caller discoverability; not weakened (the silent benign
    # result IS the current idempotent contract). Fix-card: make the not-active message
    # self-describing (e.g. point at get_applications/debug_status to find a real id).
    assert_no_diff("an unknown-id no-op must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX — the one client-reachable validation error: missing/blank id
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="stop_profiling", kind="read")
def test_missing_application_id_errors_clearly():
    """applicationId is the only declared parameter AND it is required. Omitting it must
    hit JsonUtils.requireArgument -> ToolResult.error("applicationId is required") and
    be flagged isError — NOT a NullPointer, not a benign success, and not a toggle.

    Mutation-sensitive: a tool that dropped the required-arg guard would fall through to
    isProfilingActive(null)->false and return a benign success (no isError) — caught by
    assert_error; one that crashed would leak a stack trace — caught by
    assert_error_quality's stack-trace check.
    """
    r = call("stop_profiling", {})
    e = assert_error(r, "missing required applicationId")
    # The message names the missing parameter.
    # AUDIT: "applicationId is required" names the param but is not actionable — it does
    # not point at the sibling that enumerates valid ids (get_applications / debug_status).
    # suggests=[] is intentional; fix-card: make the required-arg guard suggest a way to
    # discover a valid applicationId.
    assert_error_quality(
        e,
        names=["applicationId"],
        suggests=[],
        ctx="missing applicationId names the required param",
    )
    assert_contains(
        e, "required",
        "the guard must state the param is required, not a generic failure",
    )
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="stop_profiling", kind="read")
def test_empty_string_application_id_errors_clearly():
    """Boundary: applicationId present but the EMPTY string. JsonUtils.requireArgument
    treats null||isEmpty() identically, so "" hits the SAME required-arg guard as the
    omitted-param case — it must NOT silently coerce to a real id or fall through to a
    benign "nothing to stop" success. Confirms the empty-string boundary is rejected.

    (Verified against JsonUtils.requireArgument: it uses isEmpty(), NOT trim()+isEmpty,
    so a whitespace-only id would instead pass the guard and flow to the idempotent
    not-active branch — that benign-success behavior is already covered by the
    unknown-id test, so it is not re-asserted as an error here.)
    """
    r = call("stop_profiling", {"applicationId": ""})
    e = assert_error(r, "empty-string applicationId")
    # AUDIT: an empty value is reported via the same "applicationId is required" guard;
    # it does not distinguish "empty" from "omitted" and offers no next step.
    # suggests=[] intentional; fix-card to make the guard actionable.
    assert_error_quality(
        e,
        names=["applicationId"],
        suggests=[],
        ctx="empty-string applicationId rejected by the required-arg guard",
    )
    assert_contains(
        e, "required",
        "an empty applicationId must surface as the required-arg guard, not a NPE",
    )
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="stop_profiling", kind="read")
def test_unexpected_extra_argument_is_ignored():
    """The tool declares only `applicationId` and reads only that key. An unknown extra
    argument (alongside a valid applicationId) must be IGNORED, not cause a failure, and
    the benign "nothing to stop" snapshot must be unchanged. A tool that rejected /
    crashed on an unknown key would be a robustness regression caught here.

    This pairs the extra arg with a valid id (the arg is required), so the call still
    reaches the idempotent not-active branch and we assert the SAME benign envelope.
    """
    app_id = PROJECT
    r = call("stop_profiling", {"applicationId": app_id, "bogusUnknownArg_e2e": "x"})
    assert_ok(r, "an unexpected extra arg must be ignored, not error")

    # The extra arg changed nothing: still the benign not-active envelope for our id.
    _benign_not_active_envelope(r, app_id, "extra-arg envelope")
    assert_no_diff("an ignored-arg call must not touch the project on disk")
