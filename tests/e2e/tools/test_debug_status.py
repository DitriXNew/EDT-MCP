"""
e2e tests for debug_status (kind: read).

What the tool does
------------------
debug_status reports every ACTIVE (non-terminated) EDT debug/run launch tracked by
the Eclipse DebugPlugin launch manager: its applicationId (real ATTR_APPLICATION_ID
or the synthetic "attach:<configName>"), launch-configuration name/type, mode
(debug/run), whether the target is currently suspended, the thread count, and the
top suspended frame line. It is a pure read/diagnosis tool — it walks the launch
manager and the DebugSessionRegistry singleton; it NEVER touches project source.

Response shape (IMPORTANT)
--------------------------
DebugStatusTool.getResponseType() == JSON, so the real payload lands in
Result.structured (NOT Result.text — for a JSON tool r.text is just a "Done"/error
placeholder). The success envelope (ToolResult.success() + .put(...)) is:
    {"success": true,
     "launches": [ {applicationId, mode, debug, [launchConfiguration, configurationType,
                    attach, project, infobaseAlias, debugServerUrl], threadCount,
                    suspended, [suspendedAt], registered}, ... ],
     "count": <int>,                                  # == len(launches)
     "registry": {"activeApplications": <int>,        # DebugSessionRegistry.snapshotInfo()
                  "liveThreads": <int>, "liveFrames": <int>}}

ENVIRONMENT / SENTINEL (the realistic happy contract here)
----------------------------------------------------------
These are DEBUG/RUNTIME tools. In THIS EDT there is NO active debug session and
TestConfiguration has NO running infobase / launched application. execute() iterates
mgr.getLaunches(), SKIPS every terminated launch (launch.isTerminated()) and every
non-EDT launch (appId == null), so with no session the resulting list is EMPTY.

Crucially, debug_status does NOT signal "no session" via an isError sentinel — it
takes the benign ToolResult.success() branch and the EMPTY-but-well-formed envelope
IS the sentinel: count==0, launches==[], registry.activeApplications==0. That empty
status is the clear, correct, machine-readable "nothing is being debugged right now"
contract (a caller reads count==0 to know there is no session to step/resume). We
assert that exact empty-with-success shape (assert_ok + the zeroed status). We do
NOT start a real infobase/debug session (heavy, not configured); the empty-status
branch + the negative/robustness matrix below IS the coverage.

NEGATIVE / ROBUSTNESS MATRIX — why it has no client-reachable VALIDATION error
------------------------------------------------------------------------------
The ONLY declared parameter is `applicationId` and it is OPTIONAL (the input schema
has no "required" list; execute() reads it via extractStringArgument and treats
null/empty as "no filter"). There is therefore:
  - NO required parameter to omit,
  - NO enum to violate, NO XOR pair, NO conditional-required parameter.
A NON-EXISTENT applicationId is NOT an error either: the filter only narrows the
already-built list (`!filterAppId.equals(appId)` skips non-matches), so a bogus
filter against the empty session set still returns success with count==0. The tool
intentionally tolerates any filter string — surfacing "no matching launch" as an
empty result, not an isError. (The only error branches in the Java —
"DebugPlugin not available" and the outer catch(Exception) — are headless/internal
and unreachable from a well-formed client request against this LIVE server.)
Fabricating an isError "negative" that the tool cannot produce would be a cheat
(SKILL §6). The reachable, mutation-sensitive negatives we CAN assert are:
  - a non-existent applicationId filter -> graceful empty success (count==0), the
    SAME well-formed envelope (a tool that crashed / mislabeled this as an error, or
    leaked a phantom launch through the filter, would fail this);
  - an unexpected extra argument -> ignored gracefully, snapshot unchanged.
  See the AUDIT note on test_nonexistent_application_id_* — the silent-empty contract
  is debatable for discoverability; flagged as a fix-card, not weakened.

Every test ends with assert_no_diff(): a debug/diagnosis tool operates on the
running infobase / EDT workspace, NEVER on the git-tracked TestConfiguration tree.
"""

from harness import (
    call,
    assert_ok,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


def _envelope(r, ctx):
    """Validate the JSON success envelope and return (launches_list, count, registry).

    A JSON tool MUST populate structuredContent; a missing/typed-wrong envelope means
    the tool returned the wrong shape (a real regression), so we hard-fail rather than
    tolerate it. The launches/count/registry invariants below hold for ANY session
    count, so they break if the tool is broken regardless of the environment."""
    sc = r.structured
    if not isinstance(sc, dict):
        raise AssertionError("expected structuredContent dict [%s]: %r" % (ctx, sc))
    if sc.get("success") is not True:
        raise AssertionError("success envelope must set success=true [%s]: %r" % (ctx, sc))
    if "error" in sc:
        raise AssertionError("success envelope must NOT carry an 'error' field [%s]: %r" % (ctx, sc))
    launches = sc.get("launches")
    count = sc.get("count")
    if not isinstance(launches, list):
        raise AssertionError("'launches' must be a list [%s]: %r" % (ctx, launches))
    if not isinstance(count, int):
        raise AssertionError("'count' must be an int [%s]: %r" % (ctx, count))
    # count is built from launches.size() — the two MUST agree; a desync means the
    # tool miscounted (e.g. counted before/after the applicationId filter), a real bug.
    if count != len(launches):
        raise AssertionError(
            "count(%d) must equal len(launches)(%d) [%s]" % (count, len(launches), ctx))
    registry = sc.get("registry")
    if not isinstance(registry, dict):
        raise AssertionError("'registry' must be an object [%s]: %r" % (ctx, registry))
    # snapshotInfo() always emits these three int keys — their presence proves the
    # diagnostic block was built (not silently dropped).
    for rk in ("activeApplications", "liveThreads", "liveFrames"):
        if not isinstance(registry.get(rk), int):
            raise AssertionError(
                "registry.%s must be an int [%s]: %r" % (rk, ctx, registry.get(rk)))
    return launches, count, registry


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL  (no active debug session -> benign empty-status success)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="debug_status", kind="read")
def test_no_session_returns_empty_success_status():
    """No debug session is active in this EDT and TestConfiguration has no running
    infobase, so debug_status must take its benign ToolResult.success() branch and
    return the EMPTY-but-well-formed status that IS the "nothing is being debugged"
    sentinel: success=true, launches==[], count==0, registry.activeApplications==0.

    This is the realistic happy contract here (we do NOT start an infobase). It is
    mutation-sensitive on every axis:
      - a tool that wrongly reported an isError for the no-session case fails assert_ok;
      - a tool that leaked a terminated/non-EDT launch through (it must skip
        isTerminated() and appId==null) would make launches non-empty / count>0;
      - a tool that desynced count from the list, or dropped the registry block,
        fails _envelope.
    """
    r = call("debug_status", {})
    assert_ok(r, "debug_status with no active session")

    launches, count, registry = _envelope(r, "no-session envelope")

    # The session-absent sentinel: an EMPTY launch list with count 0. A caller reads
    # count==0 to know there is nothing to step/resume. If a session ever DID exist in
    # the environment this would legitimately be >0, but in this fixture there is none
    # (no infobase launched), so the empty branch is the asserted, observed contract.
    if count != 0:
        raise AssertionError(
            "no debug session is active in this fixture -> count must be 0; got %r (launches=%r)"
            % (count, launches))
    if launches != []:
        raise AssertionError(
            "no debug session -> launches must be the empty list; got %r" % (launches,))

    # The registry singleton holds no live suspend snapshots / threads / frames when
    # nothing is suspended. activeApplications mirrors snapshots.size() -> 0.
    if registry.get("activeApplications") != 0:
        raise AssertionError(
            "no suspended session -> registry.activeApplications must be 0; got %r"
            % (registry.get("activeApplications"),))

    assert_no_diff("a debug/read tool must not touch the project on disk")


@e2e_test(tool="debug_status", kind="read")
def test_explicit_application_id_filter_no_session_is_consistent_empty_success():
    """Passing an applicationId filter while no session exists must still succeed with
    the SAME empty status — the filter narrows an already-empty list, it cannot
    fabricate a launch. This exercises the filter branch (filterAppId != null) and
    proves it does not change the success/shape contract or leak a phantom entry."""
    r = call("debug_status", {"applicationId": "TestConfiguration"})
    assert_ok(r, "debug_status with an applicationId filter, no session")

    launches, count, registry = _envelope(r, "filtered no-session envelope")
    if count != 0 or launches != []:
        raise AssertionError(
            "filter over an empty session set must stay empty; count=%r launches=%r"
            % (count, launches))
    assert_no_diff("a debug/read tool must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE / ROBUSTNESS MATRIX
#
# debug_status has ONE optional parameter (applicationId) and NO required/enum/XOR/
# conditional parameter, and it tolerates any filter string (a non-match yields an
# empty result, not an isError). So there is no client-reachable VALIDATION error to
# assert error-quality on (same situation as get_server_status). The matrix below is
# the reachable, mutation-sensitive coverage: a bogus filter and an unexpected arg
# must both be handled GRACEFULLY (graceful empty success), and a broken tool that
# crashed / mislabeled these as errors would fail here.
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="debug_status", kind="read")
def test_nonexistent_application_id_filter_is_graceful_empty_not_error():
    """A non-existent applicationId filter is NOT a validation error: the tool skips
    every launch whose appId != filter (`!filterAppId.equals(appId)`), so against the
    no-session set the result is an empty SUCCESS, not isError.

    We assert the real, observed contract: success + count==0 (NOT assert_error). A
    tool that started throwing on an unknown filter, or that ignored the filter and
    leaked a launch, would fail this. We also assert the call did NOT take the error
    branch, so a future regression that flipped this to isError is caught.

    AUDIT: a bogus applicationId yields a SILENT empty result with no signal that the
    filter matched nothing (vs. genuinely no sessions). This is debatable for caller
    discoverability — arguably the tool could note "no launch matches <id>; use
    debug_status with no filter / get_applications". Not weakened here (the silent
    empty IS the current contract); fix-card: make a no-match filter self-describing.
    """
    bogus = "no-such-app-id_ZZZ_e2e"
    r = call("debug_status", {"applicationId": bogus})
    # Must be the success branch, not an error.
    if r.is_error:
        raise AssertionError(
            "a non-existent applicationId filter must be a graceful empty success, "
            "not isError: %s" % (r.error_text()[:200],))
    assert_ok(r, "non-existent applicationId filter")

    launches, count, registry = _envelope(r, "bogus-filter envelope")
    # The bogus id matched nothing -> empty list. (And since nothing is running, no
    # launch could carry that id anyway.) A non-empty result here would mean the
    # filter was ignored — a real bug.
    if count != 0 or launches != []:
        raise AssertionError(
            "bogus filter %r must match no launch -> empty result; count=%r launches=%r"
            % (bogus, count, launches))
    assert_no_diff("an invalid/no-match filter must not touch the project on disk")


@e2e_test(tool="debug_status", kind="read")
def test_empty_string_application_id_behaves_as_no_filter():
    """Boundary: applicationId="" must behave as NO filter (execute() treats an empty
    filter as absent: `filterAppId != null && !filterAppId.isEmpty()` gates the skip),
    so it returns the SAME empty-status success as the no-arg call — not an error and
    not a different shape. A tool that mishandled the empty string (e.g. matched it
    against every appId and excluded everything erroneously, or errored) would diverge
    from the no-arg baseline asserted in test_no_session_returns_empty_success_status."""
    r = call("debug_status", {"applicationId": ""})
    assert_ok(r, "empty-string applicationId == no filter")

    launches, count, registry = _envelope(r, "empty-filter envelope")
    if count != 0 or launches != []:
        raise AssertionError(
            "empty applicationId must act as no filter -> empty status; count=%r launches=%r"
            % (count, launches))
    assert_no_diff("a read tool must not touch the project on disk")


@e2e_test(tool="debug_status", kind="read")
def test_ignores_unexpected_argument_gracefully():
    """The tool declares only `applicationId` and reads only that key. An unknown extra
    argument must be IGNORED, not cause a failure, and the well-formed empty-status
    snapshot must be unchanged. A tool that rejected/crashed on an unknown key would
    be a robustness regression caught here."""
    r = call("debug_status", {"bogusUnknownArg_e2e": "x"})
    assert_ok(r, "unexpected extra arg must be ignored, not error")

    launches, count, registry = _envelope(r, "extra-arg envelope")
    # The extra arg changed nothing: still the empty no-session status.
    if count != 0 or launches != []:
        raise AssertionError(
            "extra arg must not disturb the snapshot; count=%r launches=%r"
            % (count, launches))
    assert_no_diff("an ignored-arg call must not touch the project on disk")
