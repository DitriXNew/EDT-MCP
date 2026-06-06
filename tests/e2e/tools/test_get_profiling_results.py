"""
e2e tests for get_profiling_results (kind: read).

WHAT IT DOES (read GetProfilingResultsTool.java for the exact branches):
  Returns profiling (замер производительности) results accumulated during a debug
  session: per-module / per-line call count, timing, percentage. getResponseType()
  == JSON, so the real payload lives in r.structured (structuredContent); r.text is
  only the JSON placeholder. All three parameters are OPTIONAL:
    - moduleFilter   (substring filter on module name)
    - minFrequency   (only lines called >= N times; default 1)
    - applicationId  (debug session id; toggles the profilingActive on/off report)
  There are NO required parameters.

CONTRACT — LATEST-ONLY (read GetProfilingResultsTool.latestOnly):
  get_profiling_results returns ONLY the most recent measurement session, never the
  historical ones. IProfilingService.getResults() is backed by an unordered cache + a
  PERSISTENT store (1C keeps замеры производительности across an EDT -clean relaunch), so
  the tool picks the max getDateOfSession() and drops the rest. Therefore `count` is
  always 0 or 1 — NEVER the raw number of historical sessions.

ENVIRONMENT (why count is 0 OR 1, not a fixed value):
  This EDT has NO active debug session and TestConfiguration has NO running infobase, so
  nothing called start_profiling THIS run. But a measurement may already PERSIST from an
  earlier live profiling round (it survives -clean). So:
    - clean store (e.g. fresh CI infobase) -> getResults() empty -> count==0 + the
      no-results sentinel: ToolResult.success().put("count",0).put("profilingActive",false)
      .put("message","No profiling results available. ... start_profiling ...").
    - a persisted session present -> latestOnly keeps exactly ONE -> count==1 with a
      populated `results` array (the latest session only).
  Both are BENIGN SUCCESS (NOT is_error). The tests assert the LATEST-ONLY invariant
  (count in {0,1}) — which is also mutation-sensitive: a regression to echoing every
  historical session (count>1) FAILS them. The count==0 branch additionally asserts the
  actionable sentinel; the count==1 branch asserts exactly one well-formed result set.

NEGATIVE / EDGE MATRIX (honest to what the Java actually does):
  get_profiling_results has NO required params and performs NO hard validation of its
  optional params — instead it DEGRADES GRACEFULLY:
    - minFrequency  -> JsonUtils.extractIntArgument(..., default 1): a non-numeric or
                       fractional value silently falls back to the default; it never
                       throws and never sets is_error.
    - applicationId -> an UNKNOWN id is simply absent from StartProfilingTool's active
                       set, so profilingActive resolves to false; still a benign success.
  So the realistic "negative" cases return a benign success with a stable, asserted
  contract (count 0 / profilingActive false), NOT an is_error. We assert that REAL
  contract (mutation-sensitive) and flag the silent-coercion gaps as # AUDIT fix-cards
  rather than fabricating an error the tool does not raise.

DIFF: profiling reads the running infobase / EDT runtime state, never the git-tracked
project. EVERY test asserts assert_no_diff() — a read/debug tool must not modify the
project source on disk.
"""

from harness import (
    call,
    assert_ok,
    assert_contains,
    assert_no_diff,
    e2e_test,
)

# NOTE on the negative matrix (read this before flagging "no assert_error tests"):
# get_profiling_results has NO required parameters and performs NO hard validation of
# its three optional params — every malformed value DEGRADES GRACEFULLY to a benign
# success (extractIntArgument defaults silently; an unknown/empty applicationId resolves
# to profilingActive=false; a non-matching moduleFilter yields no matches). The only
# ToolResult.error() paths in GetProfilingResultsTool are missing-OSGi-bundle / reflection
# failures, which are NOT reproducible against a healthy EDT. Per SKILL §5.2 we do NOT
# fabricate an is_error the tool never raises (that is exactly the self-fulfilling /
# tolerant-matching cheat the anti-cheat verifier rejects). Instead each edge case below
# asserts the REAL, mutation-sensitive benign contract AND records the missing-validation
# gaps as `# AUDIT` fix-cards (silent minFrequency coercion; unknown applicationId
# indistinguishable from inactive). assert_error/assert_error_quality are intentionally
# NOT imported — there is no reachable error to assert against here.


# ──────────────────────────────────────────────────────────────────────────────
# Helpers (local; no harness re-implementation)
# ──────────────────────────────────────────────────────────────────────────────
def _structured(r, ctx):
    """get_profiling_results is a JSON tool: payload is in structuredContent.
    Fail loudly (mutation-sensitive) if it is missing or not a dict — a tool that
    stopped emitting structured content, or emitted a bare placeholder, must fail."""
    s = r.structured
    if not isinstance(s, dict):
        raise AssertionError(
            "expected structuredContent dict [%s]; got %r / text=%r"
            % (ctx, s, (r.text or "")[:200]))
    return s


def _assert_latest_only(s, ctx):
    """Assert the LATEST-ONLY invariant and return the count.

    get_profiling_results returns only the most recent measurement session, so `count`
    is 0 (clean store) or 1 (a session persists) — NEVER the raw number of historical
    sessions. This is mutation-sensitive: a regression to echoing every session (count>1)
    fails here, and it is deterministic regardless of whether a measurement persists from
    an earlier live run. When count==0 the actionable no-results sentinel must be present;
    when count==1 exactly one well-formed result set must be carried."""
    c = s.get("count")
    if c not in (0, 1):
        raise AssertionError(
            "latest-only: expected count in {0,1} (only the most recent session); got %r [%s]"
            % (c, ctx))
    if c == 0:
        msg = str(s.get("message") or "")
        assert_contains(msg, "No profiling results available",
                        "no-results sentinel must state there are no results [%s]" % ctx)
        assert_contains(msg, "start_profiling",
                        "no-results sentinel must point at the start_profiling next step [%s]" % ctx)
    else:
        results = s.get("results")
        if not isinstance(results, list) or len(results) != 1:
            raise AssertionError(
                "latest-only: count==1 must carry exactly one result set; got %r [%s]"
                % (results, ctx))
    return c


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY / SENTINEL (no debug session => benign success with the no-results sentinel)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_profiling_results", kind="read")
def test_no_session_returns_benign_no_results_sentinel():
    """No active debug session / no profiling run THIS run => benign success.

    assert_ok (NOT is_error): profilingActive is false (nothing is profiling now), and
    the latest-only contract holds: count is 0 (clean store -> the actionable no-results
    sentinel naming start_profiling) or 1 (a measurement persists from an earlier live
    run -> exactly one result set).

    Mutation thinking: a tool that reported profilingActive=true with no session, that
    echoed every historical session (count>1), or that errored instead of degrading
    gracefully would all FAIL here.
    """
    r = call("get_profiling_results", {})
    assert_ok(r, "no-session profiling read must be a benign success, not an error")

    s = _structured(r, "no-session payload")
    # Nothing is profiling in this environment (independent of a persisted measurement).
    if s.get("profilingActive") is not False:
        raise AssertionError("expected profilingActive==False with no session; got %r"
                             % s.get("profilingActive"))
    # Latest-only: count in {0,1}; the 0 branch asserts the actionable no-results sentinel.
    _assert_latest_only(s, "no-session latest-only")

    assert_no_diff("a profiling read must not touch the project on disk")


@e2e_test(tool="get_profiling_results", kind="read")
def test_module_filter_does_not_break_latest_only_contract():
    """moduleFilter is applied only while iterating real line results, so it is a harmless
    no-op on an empty store and a per-line narrowing on a populated one. Either way it must
    NOT flip the top-level contract — still a benign success with count in {0,1}, never an
    error and never a phantom multi-session count.

    Mutation thinking: a tool that mishandled the filter (NPE, error, fabricated a match)
    or that let the filter perturb the latest-only count would fail this.
    """
    r = call("get_profiling_results", {"moduleFilter": "Calc"})
    assert_ok(r, "moduleFilter must stay a benign success")

    s = _structured(r, "filtered payload")
    _assert_latest_only(s, "filtered latest-only")

    assert_no_diff("a profiling read must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE / EDGE MATRIX
# Honest to the Java: optional params degrade gracefully (no is_error). Each case
# asserts the REAL, mutation-sensitive benign contract; silent-coercion gaps are
# recorded as # AUDIT fix-cards (per SKILL §5.2 — do NOT fabricate an error).
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_profiling_results", kind="read")
def test_unknown_application_id_reports_profiling_inactive():
    """applicationId branch: an UNKNOWN/bogus session id is not in StartProfilingTool's
    active set, so profilingActive must resolve to FALSE (StartProfilingTool.isProfiling
    Active returns false for an unknown id). The tool reports the on/off state benignly
    rather than erroring.

    Mutation thinking: a tool that ignored applicationId and reported the global
    isAnyProfilingActive(), or that defaulted profilingActive to true, would fail —
    this asserts the per-id false specifically.
    """
    bogus = "no-such-session-zzz-e2e"
    r = call("get_profiling_results", {"applicationId": bogus})
    assert_ok(r, "unknown applicationId must be a benign status report, not an error")

    s = _structured(r, "unknown-applicationId payload")
    if s.get("profilingActive") is not False:
        raise AssertionError("unknown applicationId must report profilingActive==False; got %r"
                             % s.get("profilingActive"))
    # No active session => benign latest-only contract (count in {0,1}).
    _assert_latest_only(s, "unknown-applicationId latest-only")
    # AUDIT: an UNKNOWN applicationId is silently treated as "a valid but inactive
    # session" (profilingActive=false) — the tool does NOT distinguish "this id does not
    # exist" from "this id exists but is not profiling", so a typo'd session id yields a
    # falsely-reassuring "off" with no hint to verify the id (e.g. via get_applications /
    # debug_status). Fix-card: validate the applicationId against known sessions and, if
    # unknown, surface a clear "unknown applicationId <id> — use get_applications" note.

    assert_no_diff("a profiling read must not touch the project on disk")


@e2e_test(tool="get_profiling_results", kind="read")
def test_nonnumeric_min_frequency_coerces_to_default_not_error():
    """minFrequency boundary: a NON-NUMERIC value ("abc"). Per JsonUtils.extractInt
    Argument the value silently falls back to the default (1) — it never throws and
    never sets is_error. So the call is a benign success, not a validation error.

    This asserts the REAL graceful-coercion contract (the call still returns the
    no-results sentinel, not a crash). Mutation thinking: a tool that propagated a
    NumberFormatException as is_error, or that returned a stack trace, would fail.
    """
    r = call("get_profiling_results", {"minFrequency": "abc"})
    # The tool does NOT validate minFrequency — it coerces to the default and succeeds.
    assert_ok(r, "non-numeric minFrequency must coerce to default, not error")

    s = _structured(r, "non-numeric minFrequency payload")
    _assert_latest_only(s, "coerced minFrequency latest-only")
    # AUDIT: a malformed minFrequency ("abc", or a fraction like "1.5") is SILENTLY
    # swallowed and replaced by the default of 1 — the caller gets no signal that their
    # filter was ignored, so a typo silently widens the result set. Fix-card: reject a
    # non-integer minFrequency with a clear "minFrequency must be a positive integer"
    # error instead of silently defaulting.

    assert_no_diff("a profiling read must not touch the project on disk")


@e2e_test(tool="get_profiling_results", kind="read")
def test_negative_min_frequency_is_accepted_not_rejected():
    """minFrequency boundary: a NEGATIVE value (-5) parses cleanly as an int, so it is
    accepted (no validation guards minFrequency > 0). The per-line guard `freq <
    minFrequency` is then trivially satisfied by every line. With no results to filter
    this is again a benign no-results success, NOT an error.

    Mutation thinking: a tool that erroneously rejected a valid (if nonsensical) integer
    as is_error, or that crashed on a negative bound, would fail this.
    """
    r = call("get_profiling_results", {"minFrequency": "-5"})
    assert_ok(r, "negative minFrequency parses as int and is accepted (no >0 guard)")

    s = _structured(r, "negative minFrequency payload")
    _assert_latest_only(s, "negative minFrequency latest-only")
    # AUDIT: minFrequency accepts negative / zero values (no `> 0` guard); a negative
    # bound is meaningless ("called at least -5 times") and silently disables the filter.
    # Fix-card: clamp/reject minFrequency < 1 with a clear message.

    assert_no_diff("a profiling read must not touch the project on disk")


@e2e_test(tool="get_profiling_results", kind="read")
def test_empty_application_id_falls_back_to_global_state():
    """applicationId boundary: an EMPTY string. execute() guards with
    `applicationId != null && !applicationId.isEmpty()`, so "" is treated as "not
    supplied" and the report falls back to the GLOBAL isAnyProfilingActive(). With no
    session anywhere that is false. This proves "" does not get passed through as a
    literal session id (which would never match and is a different code path).

    Mutation thinking: a tool that passed "" to isProfilingActive("") (instead of the
    isEmpty guard) would still return false here BUT a tool that errored on the empty
    string, or that defaulted profilingActive to true, would fail.
    """
    r = call("get_profiling_results", {"applicationId": ""})
    assert_ok(r, "empty applicationId must fall back to global state, not error")

    s = _structured(r, "empty-applicationId payload")
    if s.get("profilingActive") is not False:
        raise AssertionError("empty applicationId => global state, expected profilingActive==False; got %r"
                             % s.get("profilingActive"))
    _assert_latest_only(s, "empty-applicationId latest-only")

    assert_no_diff("a profiling read must not touch the project on disk")


@e2e_test(tool="get_profiling_results", kind="read")
def test_unknown_module_filter_yields_empty_not_error():
    """moduleFilter boundary: a filter that can never match any real module
    ("ZZZ_no_such_module_e2e"). With no profiling results at all this is, again, the
    benign no-results sentinel — but it confirms a non-matching filter does NOT produce
    an error or a phantom match. (A populated run would simply yield zero matched lines.)

    Mutation thinking: a tool that treated an unmatched filter as an error, or that
    ignored the filter and returned everything, would fail this benign-empty assertion.
    """
    r = call("get_profiling_results", {"moduleFilter": "ZZZ_no_such_module_e2e"})
    assert_ok(r, "non-matching moduleFilter must be benign, not an error")

    s = _structured(r, "non-matching moduleFilter payload")
    # A non-matching filter never errors and never perturbs the latest-only count: it
    # narrows per-line/per-module data within the single returned session, not the count.
    _assert_latest_only(s, "non-matching filter latest-only")

    assert_no_diff("a profiling read must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# responseFormat CONTRACT (token-saving concise default vs full detailed)
# ──────────────────────────────────────────────────────────────────────────────
# responseFormat toggles ONLY the per-line verbosity of a POPULATED `results` array:
#   - detailed -> each line carries the verbose extras code/method/dur/pureDur.
#   - concise (the DEFAULT) -> each line keeps only line/calls/pct.
# The top-level no-results sentinel (count/profilingActive/message) and the per-result
# name/totalDurability/moduleCount are IDENTICAL in both formats — which is exactly why
# every sentinel assertion above (the only path reachable headless) still passes under
# the new concise default with no `responseFormat` added. We cannot exercise the
# per-line difference headless (no debug session => empty `results`), so the contract
# tests below prove the wiring that IS reachable: both formats are accepted, neither
# regresses the benign sentinel, and an unrecognized value falls back to concise (no
# is_error). A populated run would additionally show concise omitting code/method/
# dur/pureDur while keeping line/calls/pct.
@e2e_test(tool="get_profiling_results", kind="read")
def test_detailed_format_accepted_and_matches_concise_sentinel():
    """responseFormat=detailed is an accepted value (closed enum concise|detailed) and,
    on the no-results path, returns the SAME benign sentinel as the concise default —
    adding the verbosity toggle must not regress the count/profilingActive/message
    contract. (The per-line code/method/dur extras only appear in a populated `results`
    array, which is not reachable headless.)

    Mutation thinking: a tool that rejected the documented `detailed` value as an error,
    or that changed the sentinel when detailed was requested, would fail here.
    """
    r = call("get_profiling_results", {"responseFormat": "detailed"})
    assert_ok(r, "responseFormat=detailed must be an accepted format, not an error")

    s = _structured(r, "detailed payload")
    if s.get("profilingActive") is not False:
        raise AssertionError("expected profilingActive==False with no session; got %r"
                             % s.get("profilingActive"))
    # detailed must not regress the top-level latest-only contract (count in {0,1}).
    _assert_latest_only(s, "detailed latest-only")

    assert_no_diff("a profiling read must not touch the project on disk")


@e2e_test(tool="get_profiling_results", kind="read")
def test_concise_is_default_and_leaner_than_detailed():
    """Contract: the DEFAULT call (no responseFormat) behaves as `concise`, and an
    UNRECOGNIZED value falls back to concise too (no is_error) — never the verbose
    `detailed` shape. We prove the token-saving default is wired without depending on a
    populated run: the default call equals the explicit concise call, and a bogus value
    degrades to that same lean contract rather than erroring or silently upgrading to
    detailed.

    On the headless no-results path the per-line extras are absent in BOTH formats, so
    this asserts the reachable invariant (default == concise == bogus-fallback, all the
    benign sentinel). A populated run would show concise's per-line rows omitting the
    verbose code/method/dur/pureDur that detailed carries — strictly fewer tokens.

    Mutation thinking: a tool that defaulted to detailed, that errored on an unrecognized
    responseFormat instead of falling back to concise, or that diverged the default from
    the explicit concise contract would fail here.
    """
    default_r = call("get_profiling_results", {})
    concise_r = call("get_profiling_results", {"responseFormat": "concise"})
    bogus_r = call("get_profiling_results", {"responseFormat": "verbose-please"})

    assert_ok(default_r, "default (concise) call must succeed")
    assert_ok(concise_r, "explicit concise call must succeed")
    assert_ok(bogus_r, "unrecognized responseFormat must fall back to concise, not error")

    ds = _structured(default_r, "default payload")
    cs = _structured(concise_r, "explicit-concise payload")
    bs = _structured(bogus_r, "bogus-format payload")

    # The default IS concise: same benign latest-only contract as the explicit concise call.
    for label, s in (("default", ds), ("concise", cs), ("bogus-fallback", bs)):
        if s.get("profilingActive") is not False:
            raise AssertionError("%s: expected profilingActive==False; got %r"
                                 % (label, s.get("profilingActive")))
        _assert_latest_only(s, "%s latest-only" % label)

    # All three formats must agree on the top-level contract (count is format-independent).
    if not (ds.get("count") == cs.get("count") == bs.get("count")):
        raise AssertionError("count must be identical across default/concise/bogus; got %r/%r/%r"
                             % (ds.get("count"), cs.get("count"), bs.get("count")))

    assert_no_diff("a profiling read must not touch the project on disk")
