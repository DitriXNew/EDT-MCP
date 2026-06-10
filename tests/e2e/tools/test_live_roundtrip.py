"""
ATTENDED live-infobase ROUND-TRIP suite — the part of the debug/profiling/YAXUnit
coverage that a headless run cannot reach.

Why this file is special
------------------------
Every OTHER e2e file asserts the realistic *no-infobase* contract: in CI there is
no running infobase, no YAXUnit engine, and no debug session, so the genuine happy
path for those runtime tools is an actionable SENTINEL (see test_run_yaxunit_tests,
test_wait_for_break, test_terminate_launch, ...). Those files fully cover argument
validation, error quality, and the no-session branches.

What they CANNOT cover is a real END-TO-END round-trip against a live infobase:
  1. a real YAXUnit run -> real junit.xml -> parsed Markdown counts;
  2. a real breakpoint SUSPEND -> inspect (get_variables / evaluate_expression)
     -> RESUME;
  3. a launch/session id MINTED by one tool and CONSUMED by its siblings
     (debug_status / terminate_launch; start_profiling -> get_profiling_results
     -> stop_profiling).

Those chains need a running infobase + a runtime-client launch configuration + the
YAXUnit engine (YAxUnit.cfe) loaded — heavy, stateful, and absent from CI. So this
whole file is GATED behind requires_live_infobase(): each test SKIPS (E2ESkip, NOT
a failure) unless EDT_MCP_LIVE_INFOBASE=1. A normal `run_all.py` therefore reports
them as skipped and stays green; an attended operator runs them explicitly:

    EDT_MCP_LIVE_INFOBASE=1 python tests/e2e/run_all.py --project TestConfiguration \
        --filter test_live_

Preconditions for the live run (the attended operator owns these):
  - EDT open on the TestConfiguration workspace, MCP up on :8765;
  - a runtime-client launch config named LIVE_LAUNCH_CONFIG
    ("TestConfiguration Thin Client" by default) pointing at the infobase;
  - the infobase has the YAXUnit engine loaded and the "tests" extension applied
    (run update_database once if the extension changed).

Isolation / teardown
---------------------
The suite drives REAL launches. Every test, at BOTH ends, terminates its own launch
config (targeted by name — not a blanket all=true kill, which can disturb EDT's
shared infobase-connection registry) and waits for the infobase to go quiet, so the
next test starts clean. Each debug test passes the launch's minted applicationId
EXPLICITLY to wait_for_break (rather than relying on single-launch auto-resolution),
so an async-starting launch is awaited, not missed. None of these tools touch the
git-tracked project tree — but a live EDT may incidentally re-touch a metadata .mdo
with a CRLF normalization while updating the infobase, so each test ends with
assert_no_substantive_diff() (tolerant of a whitespace/line-ending-only touch, but
still failing a real source content change or a new/deleted file).

Fixture inventory (TestConfiguration.tests extension, English module file names):
  CommonModule tests_SampleTests exports YAXUnit tests; TwoPlusTwoIsFour asserts
  `2 + 2 == 4` on a single line (the breakpoint target). All 8 registered cases pass.
"""

import re
import time

from harness import (
    call,
    assert_ok,
    assert_no_substantive_diff,
    e2e_test,
    requires_live_infobase,
    parse_yaxunit_counts,
    extract_application_id,
    wait_until_no_running_launch,
    wait_for_project_ready,
    _fail,
    PROJECT,
    TESTS_PROJECT,
    LIVE_LAUNCH_CONFIG,
)

# The breakpoint target: tests_SampleTests.TwoPlusTwoIsFour is a one-line assertion
# `ЮТест.ОжидаетЧто(2 + 2).Равно(4);`. We pin the method (not the raw line number) by
# locating the assertion line at run time so the test survives edits above it.
SAMPLE_MODULE_PATH = "CommonModules/tests_SampleTests/Module.bsl"
SAMPLE_TEST = "tests_SampleTests.TwoPlusTwoIsFour"
# A dedicated module with one passing + one DELIBERATELY failing test (kept separate
# so tests_SampleTests stays all-green). Used for the fail-count round-trip.
FAILURE_DEMO_MODULE = "tests_FailureDemo"


def _quiet_infobase():
    """Setup + teardown: terminate OUR launch config and wait for it to go down. Run at
    BOTH ends of each test so a lingering client from a prior run cannot leak a
    connection into the next launch.

    Targeted on LIVE_LAUNCH_CONFIG by name — NOT a blanket terminate_all_live_launches
    (all=true). A blanket kill would also tear down unrelated launches (e.g. IRP) and
    can disturb EDT's shared infobase-connection registry, after which a fresh debug
    launch fails with "Connection is not registered for infobase …" / "project is on
    'closing' phase". Terminating only our own config keeps the blast radius minimal."""
    try:
        call("terminate_launch", {"launchConfigurationName": LIVE_LAUNCH_CONFIG})
    except Exception:
        pass
    wait_until_no_running_launch(config_name=LIVE_LAUNCH_CONFIG, timeout=60)


def _ready_for_launch():
    """Setup: quiet the infobase AND wait for every project to be fully indexed (state
    'ready', not 'building'/'not_available'). A debug launch or breakpoint against a
    project whose derived data is still being computed fails with "Project build in
    progress" (or the breakpoint silently never binds), so block until the index has
    settled. Relevant when a heavy preceding run (e.g. the full headless suite) left
    EDT re-indexing, or when EDT was only just opened."""
    _quiet_infobase()
    wait_for_project_ready(timeout=180)


# Transient infobase-busy conditions surfaced by the 1C platform (NOT our code) when
# the updateBeforeLaunch / launch step cannot get exclusive access because a client or
# updater from a just-finished run is still releasing the infobase. They clear within
# a few seconds, so we quiet + retry rather than fail.
_TRANSIENT_BUSY = ("already connected", "being updated", "already in progress",
                   "build in progress", "derived data not complete")


def _run_yaxunit_until_done(args, attempts=4):
    """Run YAXUnit, tolerating two non-fatal conditions:
      - **Pending**: the launch is still running past `timeout`; re-call to keep
        polling (the launch is not terminated on timeout).
      - a transient infobase-busy error (a lingering client/lock from a prior run):
        quiet the infobase and retry.
    Returns the first Result that is neither Pending nor a transient-busy error; if
    every attempt is exhausted still Pending/busy, fails with a clear reason (so the
    caller does not misread an empty report as 'no tests ran')."""
    r = None
    for _ in range(attempts):
        r = call("run_yaxunit_tests", dict(args))
        body = ((r.text or "") + " " + (r.error_text() or "")).lower()
        if "pending" in body:
            continue
        if r.is_error and any(s in body for s in _TRANSIENT_BUSY):
            _quiet_infobase()
            wait_for_project_ready(timeout=120)  # clears a 'build in progress' transient
            time.sleep(3)
            continue
        return r
    _fail("run_yaxunit_tests did not finish after %d attempts (still pending/busy); last body: %s"
          % (attempts, ((r.text if r else "") or (r.error_text() if r else "") or "")[:200]))


def _assertion_line(module_rel, snippet):
    """Best-effort: find the 1-based line of `snippet` inside a fixture module on disk
    (the breakpoint goes there). Falls back to None if not found."""
    from harness import PROJECT_DIR  # base config dir; the extension is a sibling
    import os
    # The tests extension lives next to the base project: <repo>/tests/tests/src/...
    tests_root = os.path.join(os.path.dirname(PROJECT_DIR), "tests", "src")
    path = os.path.join(tests_root, *module_rel.split("/"))
    try:
        with open(path, encoding="utf-8") as f:
            for i, line in enumerate(f.read().splitlines(), start=1):
                if snippet in line:
                    return i
    except OSError:
        pass
    return None


def _junit_path_from_report(report_text):
    """Locate the on-disk junit.xml that backed a run_yaxunit_tests Markdown report.

    A successful report ends with a footer pointing at the sibling report.md
    ("*Full report saved to:* `<dir>/report.md`"); junit.xml lives next to it. This
    runs ON the EDT host (the live suite is attended/local), so the temp path is
    readable. Fails loudly if the footer is absent (a successful run always writes it)."""
    import os
    m = re.search(r"saved to:\*?\s*`([^`]+)`", report_text or "")
    if not m:
        _fail("run report carried no 'saved to' path to locate junit.xml:\n%s"
              % ((report_text or "")[:400]))
    return os.path.join(os.path.dirname(m.group(1)), "junit.xml")


# ──────────────────────────────────────────────────────────────────────────────
# 1. YAXUnit pass-count round-trip — REAL junit.xml -> parsed Markdown counts
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_live_yaxunit_run_reports_real_pass_counts():
    """Launch the green tests_SampleTests module for real and assert the Markdown
    report's parsed counts. This is the ONE thing the headless suite cannot do: prove
    a real YAXUnit run produces junit.xml that the tool parses into an accurate
    summary table (Total/Passed/Failed/Errors) with the PASSED verdict.

    Mutation-sensitive: a tool that faked a Pending, mis-summed the counts, dropped
    the Failed/Errors rows, or reported PASSED while tests failed would break here.
    """
    requires_live_infobase("runs the real tests_SampleTests YAXUnit suite")
    try:
        _ready_for_launch()  # quiet the infobase + wait for the index to be fully built
        r = _run_yaxunit_until_done({
            "launchConfigurationName": LIVE_LAUNCH_CONFIG,
            "modules": "tests_SampleTests",
            "timeout": 150,
        })
        assert_ok(r, "live YAXUnit run of the green tests_SampleTests module")
        counts = parse_yaxunit_counts(r.text)
        if counts.get("total", 0) < 1:
            _fail("expected >=1 test in the live report, got %r:\n%s" % (counts, (r.text or "")[:400]))
        # The demo module is all-green: every test passed, none failed/errored.
        if counts.get("passed") != counts.get("total"):
            _fail("expected passed==total for the green module, got %r" % (counts,))
        if counts.get("failed", 0) != 0 or counts.get("errors", 0) != 0:
            _fail("green module must report 0 failed / 0 errors, got %r" % (counts,))
        if counts.get("result") != "PASSED":
            _fail("expected the PASSED verdict, got %r:\n%s" % (counts, (r.text or "")[:400]))
    finally:
        _quiet_infobase()
    assert_no_substantive_diff("a YAXUnit run must never write into the project tree")


# ──────────────────────────────────────────────────────────────────────────────
# 1b. YAXUnit FAIL-count round-trip — REAL failing run -> parsed Failed count
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_live_yaxunit_run_reports_real_failure_count():
    """Run the dedicated tests_FailureDemo module (one passing + one DELIBERATELY
    failing test) and assert the parsed counts: Total=2, Passed=1, Failed=1, with the
    FAILED verdict. This is the complement to the pass-count round-trip: it proves a
    REAL YAXUnit FAILURE (not a synthetic junit.xml) flows through to an accurate
    Failed count and the FAILED result — the counts are independent, not a blanket
    'all failed'. The failing test lives in its OWN module so tests_SampleTests stays
    green; see CommonModules/tests_FailureDemo/Module.bsl.

    Mutation-sensitive: a tool that swallowed the failure, reported PASSED while a
    test failed, or mis-summed Passed/Failed would break here.
    """
    requires_live_infobase("runs the deliberately-failing tests_FailureDemo suite")
    try:
        _ready_for_launch()  # quiet the infobase + wait for the index to be fully built
        r = _run_yaxunit_until_done({
            "launchConfigurationName": LIVE_LAUNCH_CONFIG,
            "modules": FAILURE_DEMO_MODULE,
            "timeout": 150,
        })
        assert_ok(r, "live YAXUnit run of the tests_FailureDemo module")
        counts = parse_yaxunit_counts(r.text)
        if counts.get("total") != 2:
            _fail("expected Total=2 for the demo module, got %r:\n%s" % (counts, (r.text or "")[:400]))
        if counts.get("passed") != 1:
            _fail("expected Passed=1 (the independent counts), got %r" % (counts,))
        if counts.get("failed") != 1:
            _fail("expected Failed=1 from the deliberate failure, got %r" % (counts,))
        if counts.get("result") != "FAILED":
            _fail("a run with a failing test must report the FAILED verdict, got %r:\n%s"
                  % (counts, (r.text or "")[:400]))
    finally:
        _quiet_infobase()
    assert_no_substantive_diff("a YAXUnit run must never write into the project tree")


# ──────────────────────────────────────────────────────────────────────────────
# 1c. NO time-cache — a re-run with identical args RE-EXECUTES (regression for #136)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="run_yaxunit_tests", kind="read")
def test_live_yaxunit_rerun_reexecutes_not_cached():
    """Two identical sequential runs must EACH execute the tests — the tool must not
    return a stale time-cached report (issue #136: an agent fixed the code under test,
    re-ran the SAME filter, and got the previous result back unchanged).

    The discriminator is the on-disk junit.xml mtime. The 1C platform writes junit.xml
    ONLY when a run actually executes; a fresh run wipes+recreates the stable report dir
    first. So the second identical run MUST advance junit.xml's mtime past the first. The
    OLD behaviour served the recent report straight from disk WITHOUT relaunching, leaving
    the mtime unchanged — exactly what this test now forbids.

    Mutation-sensitive: re-introducing the disk timestamp cache (or any 'serve the recent
    report instead of re-running' shortcut) leaves the mtime unchanged and fails here. The
    report path itself is asserted stable, so a fresh run must reuse the SAME dir (proving
    we compare like-for-like, not two unrelated runs).

    NOT covered (deliberately): the 'abandoned Pending -> rerun' angle — a caller that got a
    Pending, never fetched, then re-ran with identical args gets the prior report served ONCE
    before the following call re-executes. That is #136's documented "ambiguous identical args"
    tradeoff (see runTests javadoc + PENDING_FETCH); it cannot be reproduced deterministically
    here without forcing a polling timeout, so it is left unasserted."""
    requires_live_infobase("re-runs tests_SampleTests twice to prove no stale cache")
    import os
    args = {"launchConfigurationName": LIVE_LAUNCH_CONFIG,
            "modules": "tests_SampleTests", "timeout": 150}
    try:
        _ready_for_launch()  # quiet the infobase + wait for the index to be fully built
        r1 = _run_yaxunit_until_done(dict(args))
        assert_ok(r1, "first live YAXUnit run")
        junit1 = _junit_path_from_report(r1.text)
        if not os.path.exists(junit1):
            _fail("first run did not leave a junit.xml at %r" % (junit1,))
        m1 = os.path.getmtime(junit1)

        # Quiet the infobase between runs (the auto-chain also terminates a live client)
        # and let the filesystem clock advance so a re-write is observable as a newer mtime.
        # 3s clears coarse mtime granularity (e.g. FAT/exFAT's 2s) so the strict
        # m2 > m1 assertion below stays robust on such filesystems.
        _quiet_infobase()
        wait_for_project_ready(timeout=120)
        time.sleep(3)

        r2 = _run_yaxunit_until_done(dict(args))
        assert_ok(r2, "second identical live YAXUnit run")
        junit2 = _junit_path_from_report(r2.text)
        if junit2 != junit1:
            _fail("identical args produced a different report dir (%r vs %r) — the run key "
                  "is unstable, the mtime comparison would be meaningless" % (junit1, junit2))
        m2 = os.path.getmtime(junit2)
        if not m2 > m1:
            _fail("the second identical run did NOT re-execute: junit.xml mtime unchanged "
                  "(%r -> %r). A stale time-cache regressed #136." % (m1, m2))
    finally:
        _quiet_infobase()
    assert_no_substantive_diff("a YAXUnit run must never write into the project tree")


# ──────────────────────────────────────────────────────────────────────────────
# 2. Debug round-trip — set_breakpoint -> debug launch -> SUSPEND -> inspect -> RESUME
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="wait_for_break", kind="read")
def test_live_debug_breakpoint_suspend_inspect_resume():
    """The full interactive-debug chain against a live infobase:

        set_breakpoint (assertion line in TwoPlusTwoIsFour)
          -> run_yaxunit_tests(debug=true, tests=TwoPlusTwoIsFour)   [DEBUG launch]
          -> wait_for_break                                          [SUSPEND observed]
          -> evaluate_expression("2 + 2") == "4"                     [live frame eval]
          -> get_variables                                           [frame readable]
          -> resume                                                  [thread released]

    Asserts the observable suspend (hit:true with a frameRef on the expected module/
    line) AND a real evaluation in that frame. This is the crown-jewel round-trip:
    none of it is reachable headlessly. A tool that never suspended, returned a
    phantom frame, or evaluated against no frame would fail.
    """
    requires_live_infobase("drives a real breakpoint suspend/resume")
    bp_id = None
    try:
        _ready_for_launch()  # quiet the infobase + wait for the index to be fully built
        line = _assertion_line(SAMPLE_MODULE_PATH, ".Равно(4)") or 51
        bset = call("set_breakpoint", {
            "projectName": TESTS_PROJECT,
            "module": SAMPLE_MODULE_PATH,
            "lineNumber": line,
        })
        assert_ok(bset, "set breakpoint on the assertion line")
        bp_id = (bset.structured or {}).get("breakpointId")

        # DEBUG-mode launch of the single test method; returns immediately (handle).
        launch = call("run_yaxunit_tests", {
            "launchConfigurationName": LIVE_LAUNCH_CONFIG,
            "tests": SAMPLE_TEST,
            "debug": "true",
        })
        assert_ok(launch, "debug-mode YAXUnit launch of the single test")
        app_id = extract_application_id(launch.text)

        # Block until the breakpoint trips. Pass the minted applicationId EXPLICITLY:
        # auto-resolution errors out fast if the async launch is not yet 'active', while
        # an explicit id makes wait_for_break WAIT (up to timeout) for THAT launch to
        # suspend.
        brk = call("wait_for_break",
                   {"applicationId": app_id, "timeout": 150} if app_id else {"timeout": 150})
        sc = brk.structured or {}
        if sc.get("hit") is not True:
            _fail("expected a breakpoint SUSPEND (hit:true), got structured=%r" % (sc,))
        frames = sc.get("frames") or []
        if not frames:
            _fail("suspend snapshot carried no stack frames: %r" % (sc,))
        frame_ref = sc.get("topFrameRef") or frames[0].get("frameRef")
        if not frame_ref:
            _fail("no usable frameRef in the suspend snapshot: %r" % (sc,))
        # The top frame should be our test module — proves the right breakpoint tripped.
        top = frames[0]
        if "tests_SampleTests" not in (top.get("modulePath") or ""):
            _fail("top frame is not the test module: %r" % (top,))

        # Evaluate a BSL expression in the SUSPENDED frame: 2 + 2 must be 4.
        ev = call("evaluate_expression", {"frameRef": frame_ref, "expression": "2 + 2"})
        assert_ok(ev, "evaluate 2 + 2 in the suspended frame")
        val = str((ev.structured or {}).get("value", "")).strip()
        if val != "4":
            _fail("evaluate_expression('2 + 2') must be 4 in the live frame, got %r (%r)"
                  % (val, ev.structured))

        # Variables must be readable in that frame (success, even if the count is small).
        gv = call("get_variables", {"frameRef": frame_ref})
        assert_ok(gv, "read variables from the suspended frame")
        if (gv.structured or {}).get("success") is not True:
            _fail("get_variables must succeed on a live frame, got %r" % (gv.structured,))

        # Release via the NO-ARGUMENT convenience path (the tool resolves the single
        # active launch and resumes its target). For a 1C target this exercises the
        # thread-granularity fallback: target.canResume() is false while a thread is
        # suspended, so resume must release the suspended thread(s) rather than
        # dead-end at "debug target cannot resume". (test_live_profiling covers the
        # explicit threadId path.)
        rz = call("resume", {})
        assert_ok(rz, "resume the single active launch via the no-argument convenience")
        if (rz.structured or {}).get("resumed") is not True:
            _fail("no-arg resume must report resumed:true, got %r" % (rz.structured,))
    finally:
        if bp_id:
            try:
                call("remove_breakpoint", {"breakpointId": bp_id})
            except Exception:
                pass
        _quiet_infobase()
    assert_no_substantive_diff("a debug round-trip must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# 3. Launch-id round-trip — id minted by debug launch, consumed by status + terminate
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="debug_status", kind="read")
def test_live_debug_launch_id_consumed_by_status_and_terminate():
    """The applicationId a DEBUG launch mints must round-trip through its siblings:

        debug launch -> handle carries applicationId
          -> debug_status lists THAT applicationId as a live debug launch
          -> terminate_launch(projectName+applicationId) kills exactly that launch

    Proves the id is a real, consumable handle (not a cosmetic string) and that the
    sibling tools agree on it. We launch in debug mode WITHOUT a breakpoint, so the
    test simply runs to completion while we observe the live session.
    """
    requires_live_infobase("mints and consumes a real launch applicationId")
    app_id = None
    try:
        _ready_for_launch()  # quiet the infobase + wait for the index to be fully built
        launch = call("run_yaxunit_tests", {
            "launchConfigurationName": LIVE_LAUNCH_CONFIG,
            "tests": SAMPLE_TEST,
            "debug": "true",
        })
        assert_ok(launch, "debug-mode launch to mint an applicationId")
        app_id = extract_application_id(launch.text)
        if not app_id:
            _fail("debug launch handle carried no applicationId:\n%s" % ((launch.text or "")[:400]))

        # debug_status must report the same id among the live launches.
        found = False
        deadline = time.time() + 60
        while time.time() < deadline and not found:
            st = call("debug_status", {"applicationId": app_id})
            for lp in (st.structured or {}).get("launches", []) or []:
                if lp.get("applicationId") == app_id:
                    found = True
                    break
            if not found:
                time.sleep(2)
        if not found:
            _fail("debug_status did not list the minted applicationId %r" % (app_id,))

        # Consume the id from the OTHER end: terminate exactly that launch. debug_status
        # just proved this launch is LIVE, so terminate must REALLY kill it — assert a
        # real-kill result code ('terminated'/'force_terminated'/'already_terminated'/
        # 'detached') and NOT the 'not_found' sentinel (which would mean the id round-trip
        # silently matched nothing — a regression). assert_ok already caught a rejected
        # selection (validateSelection -> ToolResult.error).
        term = call("terminate_launch", {"projectName": PROJECT, "applicationId": app_id})
        assert_ok(term, "terminate the launch by its minted applicationId")
        term_text = (term.text or "").lower()
        if "not_found" in term_text:
            _fail("terminate_launch found no live launch for the minted id (debug_status "
                  "proved it live, so a real kill was expected): %s" % ((term.text or "")[:300]))
        if "terminated" not in term_text and "detached" not in term_text:
            _fail("terminate_launch did not report a real-kill result for the live launch: %s"
                  % ((term.text or "")[:300]))
    finally:
        _quiet_infobase()
    assert_no_substantive_diff("a launch/status round-trip must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# 4. Profiling round-trip — start -> (run) -> results -> stop, keyed by applicationId
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="get_profiling_results", kind="read")
def test_live_profiling_start_results_stop_roundtrip():
    """Performance-measurement round-trip on a live debug session:

        debug launch -> start_profiling(appId) -> wait_for_break (let code execute)
          -> resume -> get_profiling_results -> stop_profiling(appId)

    Asserts start/stop accept the applicationId and report active/stopped state, and
    that get_profiling_results returns cleanly (either real per-line data or the
    explicit 'no results' shape — both are valid; a crash/leak is not). Keyed on the
    same applicationId the launch minted, proving the id threads the whole chain.
    """
    requires_live_infobase("drives line-level profiling on a live session")
    app_id = None
    bp_id = None
    try:
        _ready_for_launch()  # quiet the infobase + wait for the index to be fully built
        line = _assertion_line(SAMPLE_MODULE_PATH, ".Равно(4)") or 51
        bset = call("set_breakpoint", {
            "projectName": TESTS_PROJECT, "module": SAMPLE_MODULE_PATH, "lineNumber": line,
        })
        assert_ok(bset, "breakpoint so the session pauses while we enable profiling")
        bp_id = (bset.structured or {}).get("breakpointId")

        launch = call("run_yaxunit_tests", {
            "launchConfigurationName": LIVE_LAUNCH_CONFIG, "tests": SAMPLE_TEST, "debug": "true",
        })
        assert_ok(launch, "debug-mode launch for profiling")
        app_id = extract_application_id(launch.text)
        if not app_id:
            _fail("debug launch handle carried no applicationId for profiling")

        # Explicit applicationId (see the debug round-trip note): wait for THIS launch.
        brk = call("wait_for_break", {"applicationId": app_id, "timeout": 150})
        brk_sc = brk.structured or {}
        if brk_sc.get("hit") is not True:
            _fail("profiling round-trip never suspended: %r" % (brk_sc,))
        thread_id = brk_sc.get("threadId")

        started = call("start_profiling", {"applicationId": app_id})
        assert_ok(started, "start_profiling on the live session")
        if (started.structured or {}).get("active") is not True:
            _fail("start_profiling must report active:true, got %r" % (started.structured,))

        # Let the profiled code run to completion (resume by the precise thread id).
        call("resume", {"threadId": thread_id} if thread_id else {})
        time.sleep(3)

        results = call("get_profiling_results", {})
        assert_ok(results, "get_profiling_results returns cleanly")
        rs = results.structured or {}
        # Either real data (moduleCount/modules) or the explicit empty shape (count:0
        # + message). Both are valid; what matters is no crash and a structured answer.
        if "moduleCount" not in rs and "count" not in rs:
            _fail("get_profiling_results returned neither data nor the empty shape: %r" % (rs,))

        stopped = call("stop_profiling", {"applicationId": app_id})
        assert_ok(stopped, "stop_profiling on the session")
        if (stopped.structured or {}).get("stopped") is not True:
            _fail("stop_profiling must report stopped:true, got %r" % (stopped.structured,))
    finally:
        if bp_id:
            try:
                call("remove_breakpoint", {"breakpointId": bp_id})
            except Exception:
                pass
        _quiet_infobase()
    assert_no_substantive_diff("a profiling round-trip must not touch the project on disk")
