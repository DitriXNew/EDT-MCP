#!/usr/bin/env python3
"""
EDT-MCP e2e orchestrator.

Discovers every @e2e_test in tests/e2e/tools/test_*.py and runs them SERIALLY
(all tests mutate the same TestConfiguration + git tree, so they cannot run in
parallel WITHIN one runner). Resets the fixture before EVERY test, enforces a
clean final state, and emits a JUnit XML report. See SKILL.md.

--shard I/N splits the suite ACROSS runners instead: each shard is an ordinary
serial run of its own subset, on its own machine, with its own workspace and its
own fixture checkout. That is what makes it safe - the isolation comes from not
sharing a working tree, not from locking one. Never point two shards at the same
checkout.

Usage:
    python tests/e2e/run_all.py [--host H] [--port P] [--project NAME]
                                [--junit-xml PATH] [--filter SUBSTR]
                                [--shard I/N]

Python stdlib only.
"""

import argparse
import importlib
import os
import sys
import threading
import time
import traceback
import xml.sax.saxutils as su


def parse_args():
    ap = argparse.ArgumentParser(description="EDT-MCP e2e orchestrator (serial, git-fixture isolated)")
    ap.add_argument("--host", default=os.environ.get("MCP_HOST", "127.0.0.1"))
    ap.add_argument("--port", default=os.environ.get("MCP_PORT", "8765"))
    ap.add_argument("--project", default=os.environ.get("MCP_PROJECT", "TestConfiguration"))
    ap.add_argument("--junit-xml", dest="junit", default=None)
    ap.add_argument("--filter", default=None, help="substring filter on test name or tool")
    ap.add_argument("--shard", default=None, metavar="I/N",
                    help="run only shard I of N (1-based), e.g. --shard 2/4. Each shard is a "
                         "self-contained run: its own baseline, its own cleanup, its own exit "
                         "code and JUnit report. Intended for a CI matrix where every shard gets "
                         "its OWN runner, workspace and fixture checkout - two shards must never "
                         "share a working tree, because reset_fixture is a git operation on it.")
    ap.add_argument("--test-timeout", type=float,
                    default=float(os.environ.get("MCP_TEST_TIMEOUT", "3600")),
                    help="per-test wall-clock timeout in seconds (default 3600). Must exceed the "
                         "slowest LEGIT test, and that chain is long: the test call (up to "
                         "MCP_CALL_TIMEOUT, 600 on CI) plus reset_model, which can spend "
                         "MODEL_SETTLE_TIMEOUT (600 on CI, pinned there for exactly this reason) "
                         "BEFORE and after its clean_project. The old 600 - and even 1200 - could report a "
                         "legitimately slow test as a hang - and the CI maxima already sum to 2400 "
                         "(call 600 + settle 600 + clean_project 600 + settle 600), so the cap has "
                         "to sit ABOVE that chain, not on it. That is the one thing this timeout "
                         "must never do: it FAILS the test and SKIPS all the rest. No auto-relaunch "
                         "- restart EDT and re-run.")
    return ap.parse_args()


def parse_shard(spec):
    """'I/N' -> (index, total), both 1-based and validated. Returns (1, 1) for None.

    A malformed spec is a hard exit rather than a fallback to "run everything": in a matrix the
    shards divide the suite between them, so one silently running the WHOLE suite would look like
    a pass while the split it was supposed to prove never happened."""
    if not spec:
        return (1, 1)
    parts = spec.split("/")
    if len(parts) != 2 or not all(p.strip().isdigit() for p in parts):
        print("!! --shard expects I/N with both parts numeric (e.g. 2/4), got %r" % spec)
        sys.exit(2)
    index, total = int(parts[0]), int(parts[1])
    if total < 1 or not 1 <= index <= total:
        print("!! --shard %r is out of range: need 1 <= I <= N and N >= 1" % spec)
        sys.exit(2)
    return (index, total)


def select_shard(tests, index, total):
    """The slice of `tests` this shard owns - every Nth test, NOT a contiguous block.

    Round-robin, and that is the whole point. Tests are registered file by file, so a contiguous
    split hands one shard whole files: modify_metadata alone is ~40% of the suite's wall clock, so
    block-splitting caps the speed-up at ~2.5x however many shards you add, while striping the same
    files across all of them scales linearly (the slowest SINGLE test is ~110s, which is the real
    floor). Deterministic and stateless - shard 3/4 selects the same tests on every runner, with no
    timing file to keep in sync.

    Every test lands in exactly one shard, and the union is the full list: the arithmetic is a
    plain stride, so there is no rounding case where a test at the tail belongs to nobody."""
    return tests[index - 1::total] if total > 1 else tests


def write_junit(results, path, final_clean, cleanup_failed=False, status_error=None,
                unresolved_mutation=False):
    # Skips are neither pass nor failure: they are reported as JUnit <skipped/> and
    # excluded from the failure count (the gated live-infobase suite skips in a
    # headless run and must not turn the report red).
    # A cleanup that failed is its own synthetic case: without it an all-green run whose
    # final model sync never completed publishes a green report while the process exits
    # non-zero, and the report is what the CI check reads.
    extra = ((0 if final_clean else 1) + (1 if cleanup_failed else 0)
             + (1 if unresolved_mutation else 0))
    total = len(results) + extra
    fails = sum(1 for _, s, _, _ in results if s not in ("pass", "skip")) + extra
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<testsuite name="edt-mcp-e2e" tests="%d" failures="%d">' % (total, fails)]
    for t, status, msg, dur in results:
        nm = su.quoteattr("%s::%s" % (t["tool"], t["name"]))
        if status == "pass":
            out.append('  <testcase name=%s time="%.3f"/>' % (nm, dur))
        elif status == "skip":
            out.append('  <testcase name=%s time="%.3f"><skipped message=%s/></testcase>'
                       % (nm, dur, su.quoteattr(msg or "skipped")))
        elif status == "timeout":
            # A timeout is a FAILURE (counts against the run), tagged distinctly so the
            # report says plainly it timed out rather than burying it as a generic error.
            out.append('  <testcase name=%s time="%.3f"><failure type="timeout">%s</failure></testcase>'
                       % (nm, dur, su.escape(msg)))
        else:
            tag = "failure" if status == "fail" else "error"
            out.append('  <testcase name=%s time="%.3f"><%s>%s</%s></testcase>'
                       % (nm, dur, tag, su.escape(msg), tag))
    if status_error:
        # "Dirty" and "we could not tell" are both red, and they are NOT the same message: one
        # sends a reader looking for a test that left files behind, the other for a broken git.
        # Both are failures, so the count is unchanged; only the diagnosis differs.
        out.append('  <testcase name="fixture::final_clean"><failure>could not read the fixture '
                   'status, so the run is not certified clean: %s</failure></testcase>'
                   % su.escape(status_error))
    elif not final_clean:
        out.append('  <testcase name="fixture::final_clean">'
                   '<failure>TestConfiguration left dirty after the run</failure></testcase>')
    if cleanup_failed:
        out.append('  <testcase name="fixture::final_cleanup">'
                   '<failure>the final model sync did not complete: the workspace model may still '
                   'differ from the committed disk</failure></testcase>')
    if unresolved_mutation:
        out.append('  <testcase name="fixture::unresolved_mutation">'
                   '<failure>a mutating call died without an answer and was never accounted for: '
                   'the server may have executed it after the cleanliness check</failure>'
                   '</testcase>')
    out.append('</testsuite>')
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))


# Set when the run is abandoning a worker (a per-test timeout). The worker thread is a daemon
# that was never actually stopped: if its slow call returns before the process exits, it would
# walk on into its own post-test cleanup and git-reset files the server may still be writing -
# the very race the abort is avoiding. It reads this flag to skip work it no longer needs to do;
# what makes that SAFE rather than merely likely is the harness's fixture freeze, which decides
# "abandoned?" and "resetting" under one lock (see harness.freeze_fixtures).
_ABANDONED = False


def abandon_workers(harness):
    """Give up on a worker we cannot stop: no more MCP calls, no cleanup, from anyone.

    The flag alone is checked only AFTER the test function returns, which is too late for a
    worker already inside reset_model or inside a test's own teardown - it would resume and
    keep calling the server the moment its current request came back. So the harness latch is
    armed as well: from here on every request is refused before it is sent, and the git fixtures
    are frozen - which also WAITS OUT a reset already in progress, so this function returns only
    once nobody is touching the tree any more.
    """
    global _ABANDONED
    _ABANDONED = True
    if not harness.abort_further_calls(
            "the run abandoned a test that outlived its timeout, and the server may still be "
            "working on it"):
        # A git reset was running and did not finish while we waited. Every LATER one is refused,
        # but that one is beyond reach - so the tree may still be moving under the final status
        # check, and a reader deserves to know that rather than wonder at the result.
        print("!! a fixture reset was still running when the run gave up on the worker - the "
              "working tree may have been touched after this point", flush=True)


def _run_test_unit(harness, t):
    """All EDT-touching work for ONE test, timed as a unit: the test fn plus, for a
    write-metadata test, its model cleanup (reset_fixture reverts disk; reset_model =
    settle + re-revert + clean_project refreshes the in-memory model and VERIFIES it is
    back on the baseline — the step that actually hung when EDT's ProjectRestartJob
    wedged). The pre-test reset_fixture is fast local git and is done by the caller
    OUTSIDE the timeout; reset_model re-reverts inside it because a metadata write's disk
    export is async and can land AFTER that pre-test revert."""
    try:
        t["func"]()
    except harness.E2ECallTimeout:
        # Deliberately NO reset: the call may still be running server-side, and reset_model
        # would race the very write we abandoned (clean_project against a live mutation).
        # The runner aborts on this, so no later test inherits the state either.
        raise
    except harness.E2ESkip:
        # A skip is not a failed write - it is a test that decided there was nothing to do
        # (an unsupported seed that committed nothing). Paying the full cleanup budget for it
        # would be waste at best, and at worst would turn a legitimate skip into a
        # reset-failed / call-timeout if clean_project happens to be refused just then.
        raise
    except BaseException:
        # Any OTHER failure still leaves the write applied, exactly like a passing test does.
        # Skipping the reset there is how ONE real failure became two: the next test read a
        # model that still carried the previous test's rename and reported "object not found".
        _reset_after_write(harness, t)
        raise
    _reset_after_write(harness, t)


def _reset_after_write(harness, t):
    """reset_fixture (disk) + reset_model (in-memory) for a write-metadata test.

    The model reset is SKIPPED when the model provably did not move. It is the single most
    expensive thing the suite does - 331 write-metadata tests, ~11 s each, ~84% of the whole
    run - and most of those tests are negative: they hand a write tool a bad argument, assert
    the refusal, and then pay a full clean_project to re-import a model that never changed.

    "Provably" is the operative word: harness.model_is_pristine() answers only on positive
    evidence (git-clean fixtures AND an unchanged top-object inventory, and no deep-mutation
    tool involved). Anything unclear answers False and the full reset runs, so the shortcut
    can cost time but never correctness."""
    if _ABANDONED:
        # This worker was given up on; the main thread has already decided the fixtures are
        # not safe to touch. Do not undo that decision from a thread nobody is waiting for.
        return
    if t.get("kind") != "write-metadata" and not harness.mutations_unresolved():
        # The declared kind decides the ROUTINE case: a test that means to write says so, and only
        # those pay the cleanup. It cannot decide the accidental one. A mutating request that died
        # on the wire (connection reset, truncated body) may have been committed by the server
        # anyway, and it can happen to a test of any kind - 17 tests declare kind='write' and 123
        # kind='action', and every one of them would have carried that unknown into the next test.
        # So the kind gate is checked WITH the evidence, never instead of it.
        return
    if harness.model_is_pristine():
        _SKIPPED_RESETS.append(t.get("name", "?"))
        return
    # model_is_pristine() spends real time in MCP calls (a settle can burn the whole ready
    # timeout), so the main thread may have given up on this worker while it waited. Re-reading
    # the flag here would still be check-then-act - the abandonment can land in the gap - so the
    # decision belongs to reset_fixture(), which makes it under the freeze lock and answers False
    # when the tree is off limits. Believe that answer instead of racing it.
    if not harness.reset_fixture():
        return
    harness.reset_model()


# Names of the tests whose model reset was skipped — reported at the end so the shortcut is
# VISIBLE. A silent optimization in a suite whose value is trustworthiness is not acceptable.
_SKIPPED_RESETS = []


def _fixture_status(harness):
    """The end-of-run cleanliness read, which must never take the summary down with it.

    all_fixtures_status() REFUSES to read a failed `git status` as "clean" - it raises, which is
    the only safe direction for a check whose false positive certifies a dirty tree. But this call
    site sits after every test has run and before the summary and the JUnit report are written, so
    an unhandled raise here would replace the entire result of a 50-minute run with a traceback.
    Turn it into what it actually means instead: not certified clean, and the reason why.

    @return (status_text, error) - exactly one of the two is None"""
    try:
        return (harness.all_fixtures_status(), None)
    except Exception as e:  # noqa: BLE001 - any failure to READ the tree means the same thing
        return (None, str(e))


def _run_with_timeout(harness, t, timeout_s):
    """Run one test unit bounded by a wall-clock timeout. Returns (status, msg, timed_out).

    The unit runs in a daemon thread; the main thread joins for at most timeout_s. A hung
    EDT call blocks the worker in a socket read that cannot be interrupted cleanly, so on
    timeout the worker is ABANDONED (daemon — it dies with the process). That is safe
    because the orchestrator ABORTS the whole run on any timeout (a wedged EDT makes every
    later test hang too), so no subsequent test shares state with the abandoned worker. On a
    genuine wedge the worker is parked in a socket read (not touching git/disk), so it also
    cannot race the final reset_fixture; the per-test timeout is set well above the slowest
    legit unit (see --test-timeout) precisely so a timeout only ever means a real hang."""
    box = {}

    def target():
        try:
            _run_test_unit(harness, t)
            box["r"] = ("pass", "")
        except harness.E2ECallTimeout as e:
            box["r"] = ("call-timeout", str(e))
        except harness.E2EModelResetFailed as e:
            box["r"] = ("reset-failed", str(e))
        except harness.E2ESkip as e:
            box["r"] = ("skip", str(e))
        except harness.E2EAssertion as e:
            box["r"] = ("fail", str(e))
        except BaseException as e:  # noqa: BLE001 - any unexpected error is a test error
            box["r"] = ("error", "%s\n%s" % (e, traceback.format_exc()))

    th = threading.Thread(target=target, name="e2e-%s" % t["name"], daemon=True)
    th.start()
    th.join(timeout_s)
    if th.is_alive():
        # The worker is still running and cannot be stopped. Tell it to skip its own cleanup
        # before we return: from here on nobody may touch the fixtures, this thread included.
        abandon_workers(harness)
        return ("timeout",
                "TIMEOUT: test exceeded %gs and was considered FAILED. EDT is likely hung "
                "(e.g. clean_project / ProjectRestartJob wedged); the remaining tests are "
                "skipped. Restart EDT and re-run from here." % timeout_s,
                True)
    status, msg = box.get("r", ("error", "worker thread produced no result"))
    return (status, msg, False)


def main():
    args = parse_args()
    # Set env BEFORE importing harness (it reads config once at import).
    os.environ["MCP_HOST"] = args.host
    os.environ["MCP_PORT"] = str(args.port)
    os.environ["MCP_PROJECT"] = args.project

    here = os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, here)  # so `import harness` and `from harness import ...` resolve
    import harness

    # Discover per-tool test files (they self-register via @e2e_test on import).
    tools_dir = os.path.join(here, "tools")
    if os.path.isdir(tools_dir):
        for fn in sorted(os.listdir(tools_dir)):
            if fn.startswith("test_") and fn.endswith(".py"):
                importlib.import_module("tools.%s" % fn[:-3])

    tests = harness.REGISTRY
    if args.filter:
        tests = [t for t in tests if args.filter in t["name"] or args.filter in t["tool"]]
    # Sharded AFTER filtering, so --filter and --shard compose the way a reader expects: the
    # filter says WHICH tests exist for this run, the shard says which of those this runner takes.
    # harness.REGISTRY itself is left whole - the coverage ratchet reads it to check that every
    # advertised tool has a test, and that question has one answer for the suite, not one per shard.
    shard_index, shard_total = parse_shard(args.shard)
    selected = select_shard(tests, shard_index, shard_total)
    shard_note = ""
    if shard_total > 1:
        shard_note = " [shard %d/%d of %d selected]" % (shard_index, shard_total, len(tests))
    # Deferred tests go to the END, stable so everything else keeps registry order. The EDT-log
    # ratchet is different from an ordinary registered test: every shard owns a separate EDT log,
    # so every shard must append and run it after its own selected tests. In an unsharded run its
    # one registry entry is merely moved to the end, never appended a second time.
    if shard_total > 1:
        ratchets = [t for t in harness.REGISTRY if t.get("last")
                    and t["tool"] == "_edt_log_ratchet"]
        selected = [t for t in selected if t not in ratchets]
        tests = ([t for t in selected if not t.get("last")]
                 + [t for t in selected if t.get("last")] + ratchets)
    else:
        tests = [t for t in selected if not t.get("last")] + [t for t in selected if t.get("last")]

    print("EDT-MCP e2e: %d test(s)%s against %s, project=%s"
          % (len(tests), shard_note, harness.MCP_URL, harness.PROJECT))
    harness.wait_for_server()
    harness.initialize()     # proper MCP handshake (captures Mcp-Session-Id if issued)
    ready_failure = []
    if not harness.wait_for_project_ready(failure_details=ready_failure):
        # At least one EDT project never reached 'ready'. Every
        # metadata tool would then fail with "Could not get configuration", so running
        # the suite produces a wall of cascade failures that hides the real cause.
        # Abort with ONE actionable message + the project state, instead.
        print("\nERROR: %s. Metadata tools cannot resolve the configuration yet, so the suite "
              "is aborted before it starts.\n"
              "If the runner is just slow (a cold cloud runner indexes the whole config "
              "from scratch), raise E2E_PROJECT_READY_TIMEOUT. If it never goes ready, the "
              "project import/build is broken — check the EDT log." % ready_failure[0])
        try:
            print("---- list_projects ----")
            print(harness.call("list_projects", {}).text)
        except Exception as e:  # noqa: BLE001
            print("(could not read list_projects: %s)" % e)
        sys.exit(2)
    try:
        harness.final_cleanup()  # clean start: revert BOTH fixtures + sync EDT model so the run
                                 # does not begin on a stale extension edit (e.g. a manual run)
    except harness.E2ECallTimeout as e:
        # The server did not answer the very first call: nothing to run against, and a traceback
        # here would bury the reason.
        print("!! setup cleanup timed out: %s" % e)
        sys.exit(2)
    except harness.E2EModelResetFailed as e:
        # Every call RETURNED (nothing hung), but clean_project could not be gotten to succeed,
        # so the model is not verifiably in sync before a single test has run - nothing to run
        # against that would be trustworthy either.
        print("!! setup cleanup could not sync the model: %s" % e)
        sys.exit(2)

    # Fingerprint the pristine model ONCE, right after the setup cleanup proved it is in sync.
    # Every later "may I skip this test's model reset?" answer is measured against this. If it
    # cannot be read, the shortcut simply never engages and every write-metadata test resets in
    # full, exactly as before.
    if harness.mutations_unresolved():
        # Checked BEFORE the fingerprint, not after: the setup's own clean_project died on the wire,
        # so the model may still be moving under the very snapshot every later "did it move?" answer
        # is measured against. A suspect baseline is worse than none - it would certify the wrong
        # state as home - so take none, and let every write test reset in full.
        print("!! a setup call died without an answer - skipping the baseline fingerprint, so "
              "every write test resets in full (the model may still be moving server-side)")
        inventory_ok, details_ok = (False, False)
    else:
        try:
            inventory_ok, details_ok = harness.snapshot_model_baseline()
        except harness.E2ECallTimeout as e:
            # The probe hung, which arms the global latch: every later call is refused, so the run
            # would report a wall of cascade failures against innocent tests. Stop here, like the
            # setup cleanup does, with the reason intact.
            print("!! baseline fingerprint timed out: %s" % e)
            sys.exit(2)
    if not inventory_ok:
        print("!! could not fingerprint the baseline model - every write test will reset in full")
    elif not details_ok:
        # Degraded, not broken: the shortcut still runs on the inventory + git evidence, and a
        # SUCCESSFUL write forfeits it outright regardless. Say so rather than quietly weakening
        # a check nobody would notice had gone missing.
        print("!! baseline object DETAILS unreadable - the shortcut keeps only its coarse braces")

    # Each test (incl. its write-metadata model cleanup, see _run_test_unit) runs under a
    # per-test wall-clock timeout. If a test exceeds it, EDT is almost certainly hung (the
    # clean_project / ProjectRestartJob wedge that motivated this), so the test is FAILED
    # (timeout) and EVERY remaining test is SKIPPED rather than each also hanging for the
    # full timeout. No EDT auto-relaunch — restart it and re-run.
    results = []
    aborted_after = None
    # Set for EITHER race that can leave a live worker behind: a per-CALL timeout (the server
    # never answered) or a per-TEST timeout (the worker THREAD is still alive when --test-timeout
    # elapses - it was only abandoned, never actually stopped, so it may still be blocked inside
    # that same kind of unresponsive call, or inside its own reset_model()). Both mean the same
    # thing to the cleanup below: a git reset now could race a write the server may still be
    # performing. "reset-failed" is NOT one of these - every call involved already RETURNED
    # (clean_project came back isError, not hung), so there is no live worker to race.
    still_running_in = None
    cleanup_failed = False
    # WHY the run stopped, in words. Not every abort is a timeout: a mutating request that died in
    # flight stops the run too, and calling that "a TIMEOUT" in the skip reason, the summary and the
    # JUnit report sends whoever reads them looking for a hang that never happened.
    abort_cause = None
    for t in tests:
        if aborted_after is not None:
            results.append((t, "skip",
                            "skipped: the run was aborted at %s - %s"
                            % (aborted_after, abort_cause), 0.0))
            print("[%-7s] %s::%s - aborted at %s"
                  % ("SKIP", t["tool"], t["name"], aborted_after))
            continue
        harness.reset_fixture()  # hard reset BEFORE each test (fast local git) — never trust the previous
        harness.begin_test_calls()  # so the cleanup can tell what this test actually invoked
        start = time.time()
        status, msg, timed_out = _run_with_timeout(harness, t, args.test_timeout)
        dur = time.time() - start
        results.append((t, status, msg, dur))
        lines = msg.splitlines() if msg else []
        head = lines[0] if lines else ""
        print("[%-7s] %s::%s (%.2fs)%s" % (status.upper(), t["tool"], t["name"], dur,
                                           " - " + head if head else ""))
        # A failure's DETAIL (the on-disk delta, the offending payload) lives on the lines
        # after the first. Printing only the head loses it unless --junit-xml was passed,
        # which turns every red test into a second full run just to see why. Failures are
        # worth the vertical space; passes and skips stay one line.
        if status not in ("pass", "skip"):
            for extra in lines[1:]:
                print("            " + extra)
        # A per-CALL timeout aborts the run for the same reason a per-TEST one does: the server
        # is still busy with work we cannot cancel, and every later test would be reading a
        # model it is still writing.
        # The latch is armed by a call timeout AND by a mutating request that died on the wire. The
        # second one fails only its own test, in no category the runner would recognise - but every
        # later call is refused from that point on, so carrying on would just pin a wall of cascade
        # failures on tests that did nothing. Asking the latch covers both without guessing at
        # statuses; it means the same thing in either case: something we cannot cancel may still be
        # running, so nothing may read the model and nothing may touch the tree.
        latched = harness.calls_aborted()
        if timed_out or latched or status == "reset-failed":
            aborted_after = "%s::%s" % (t["tool"], t["name"])
            if timed_out:
                abort_cause = ("the test outlived --test-timeout and was abandoned; EDT is likely "
                               "hung, so restart it and re-run")
            elif latched:
                abort_cause = harness.abort_reason()
            else:
                abort_cause = ("the model could not be re-synced, so every later test would read "
                               "the last one's write")
            if timed_out or latched:
                still_running_in = aborted_after

    # Final cleanliness guarantee across BOTH fixtures (base + extension). On a normal run,
    # full cleanup (revert + EDT model sync) so a stale model can't autosave changes back
    # after the run. When a live worker may still be running (a per-CALL OR per-TEST timeout),
    # any reset - even git-only - would race it, so leave the tree alone. Any OTHER abort (e.g.
    # reset-failed: clean_project came back isError, not hung) has no live worker to race, so a
    # git-only reset is still safe.
    if still_running_in is not None and aborted_after == still_running_in:
        # The server may still be writing these very files (or the abandoned worker may still be
        # inside its own reset_model()): a git reset now races EDT (it can rename/overwrite
        # underneath us, or re-dirty right after). Leave the tree alone - the run is over, and
        # the workspace is disposable.
        print("!! left the fixtures untouched: %s may still be running server-side" % aborted_after)
    elif aborted_after:
        harness.reset_all_fixtures()
    else:
        try:
            harness.final_cleanup()
        except harness.E2ECallTimeout as e:
            # Do not lose the summary and the JUnit report over a cleanup that hung - but do not
            # call the run green either: the server may still be finishing that clean_project and
            # can re-dirty the fixture right after the status check below.
            print("!! final cleanup timed out (fixtures may be dirty): %s" % e)
            cleanup_failed = True
        except harness.E2EModelResetFailed as e:
            # Same idea, different failure mode: every call RETURNED (nothing hung), but
            # clean_project kept refusing (or the final settle never reported ready), so the
            # model may still be out of sync. Do not call the run green over that either.
            print("!! final cleanup could not sync the model: %s" % e)
            cleanup_failed = True
    final_status, status_error = _fixture_status(harness)
    final_clean = (final_status == "")
    # A mutating request that died on the wire and was never accounted for is the same class of
    # unknown as a cleanup that did not complete, and gets the same answer: the run is not green.
    # The disk check above is point-in-time - the server may still execute that request and
    # re-dirty the tree a second after it - so certifying the run over it would assert something
    # nobody checked. Consistency with cleanup_failed is the point; a suite whose green means
    # "probably" is not worth running. It is also not a flake risk: a timeout already aborts the
    # whole run, so reaching here at all means a transport died mid-write, which is never normal.
    unresolved_mutation = harness.mutations_unresolved()

    npass = sum(1 for _, s, _, _ in results if s == "pass")
    nskip = sum(1 for _, s, _, _ in results if s == "skip")
    nfail = sum(1 for _, s, _, _ in results if s not in ("pass", "skip"))
    skip_note = (" | %d skipped" % nskip) if nskip else ""
    # On abort the EDT is wedged and was NOT model-synced, so 'clean' is only a point-in-time
    # disk check (EDT may re-dirty after exit) — label it so it is not read as a guarantee.
    # On abort the model was NOT synced, so 'clean' is only what the disk said at this instant -
    # and when a live worker may remain (still_running_in), it can stop being true right after.
    clean_label = str(final_clean)
    if still_running_in:
        clean_label = "%s (point-in-time; something may still be running)" % final_clean
    elif aborted_after:
        clean_label = "%s (point-in-time; the model was not synced)" % final_clean
    print("\n== %d/%d passed%s | fixture clean: %s ==" % (npass, len(results) - nskip, skip_note, clean_label))
    if _SKIPPED_RESETS:
        # Say it out loud: a shortcut nobody can see is a shortcut nobody can audit.
        print("   model reset skipped for %d write test(s) whose model provably did not move"
              % len(_SKIPPED_RESETS))
    if unresolved_mutation:
        print("!! a mutating call died without an answer during this run and was never accounted "
              "for - the server may have executed it, so this run is NOT certified clean")
    if aborted_after:
        print("!! RUN ABORTED at %s - subsequent tests were skipped. Cause: %s"
              % (aborted_after, abort_cause))
    if status_error:
        print("!! could not read the fixture status, so this run is NOT certified clean: %s"
              % status_error)
    elif not final_clean:
        print("!! fixtures left dirty after cleanup:\n%s" % final_status[:500])

    if args.junit:
        write_junit(results, args.junit, final_clean, cleanup_failed, status_error,
                    unresolved_mutation)
        print("junit -> %s" % args.junit)

    # A skip is neither pass nor fail: the run is green when nothing FAILED and the
    # fixture is clean (skipped gated tests do not block a headless green run).
    # A cleanup that timed out is a failed run even when every test passed and the tree LOOKS
    # clean: the server may still be finishing that call and can re-dirty it after this check.
    # An unaccounted-for mutating request is the same unknown, so it gets the same answer.
    sys.exit(0 if (nfail == 0 and final_clean and not cleanup_failed and not unresolved_mutation)
             else 1)


if __name__ == "__main__":
    main()
