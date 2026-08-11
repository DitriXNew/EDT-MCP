#!/usr/bin/env python3
"""
EDT-MCP e2e orchestrator.

Discovers every @e2e_test in tests/e2e/tools/test_*.py and runs them SERIALLY
(all tests mutate the same TestConfiguration + git tree, so within ONE workspace
they cannot run in parallel). Resets the fixture before EVERY test, enforces a
clean final state, and emits a JUnit XML report. See SKILL.md.

Parallelism across MULTIPLE workspaces is done by sharding into NAMED lanes:
`--shard <name>` runs only that lane. Each lane is a disjoint slice of the suite,
so the lanes on independent runners (each with its own EDT + git fixture) cover
everything with no shared state. See issue #385 and SHARDS below.

Lanes are named by AREA, not by number, so a failing shard says WHAT failed and a
new test lands in the right lane automatically — routing is by the `kind` already
declared on every @e2e_test:
  * read-action ..... reads, source writes, and actions — everything cheap
                      (~4% of the runtime, all in one lane).
  * metadata-write-N  the write-metadata kind (~96% of the runtime). One tool,
                      modify_metadata, is ~40% of the whole suite on its own, so
                      write-metadata cannot fit one named lane and is spread across
                      N lanes by a stable hash of the test id (deterministic across
                      runners). Bump _METADATA_LANES to add more parallelism.
Adding a tool needs NO edit here: its `kind` routes it. `--list-shards` prints the
lane names (JSON) so CI can build the matrix straight from this file.

Usage:
    python tests/e2e/run_all.py [--host H] [--port P] [--project NAME]
                                [--junit-xml PATH] [--filter SUBSTR]
                                [--shard NAME | --list-shards]

Python stdlib only.
"""

import argparse
import importlib
import json
import os
import sys
import threading
import time
import traceback
import xml.sax.saxutils as su
import zlib


def parse_args():
    ap = argparse.ArgumentParser(description="EDT-MCP e2e orchestrator (serial, git-fixture isolated)")
    ap.add_argument("--host", default=os.environ.get("MCP_HOST", "127.0.0.1"))
    ap.add_argument("--port", default=os.environ.get("MCP_PORT", "8765"))
    ap.add_argument("--project", default=os.environ.get("MCP_PROJECT", "TestConfiguration"))
    ap.add_argument("--junit-xml", dest="junit", default=None)
    ap.add_argument("--filter", default=None, help="substring filter on test name or tool")
    ap.add_argument("--shard", default=None, metavar="NAME",
                    help="run only the named shard lane (see --list-shards). Lanes are areas, not "
                         "numbers: 'read-action' plus 'metadata-write-N'. Applied AFTER --filter.")
    ap.add_argument("--list-shards", action="store_true",
                    help="print the shard lane names as a JSON array and exit (CI reads this to "
                         "build the matrix, so the lanes have a single source of truth).")
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


# ── Named shard lanes (issue #385) ─────────────────────────────────────────────
# A shard is a NAMED area, not a number, so a red shard says WHERE it failed and a new
# test routes itself. Routing is by the `kind` already on every @e2e_test:
#   - read / write / action  -> the single "read-action" lane (~4% of the runtime).
#   - write-metadata         -> spread across _METADATA_LANES "metadata-write-N" lanes.
# Why write-metadata is spread and not one named lane: it is ~96% of the runtime and one
# tool (modify_metadata, 101 tests) is ~40% of the WHOLE suite, so no single lane can hold
# it without becoming the bottleneck (this is exactly the file-sharding ceiling issue #385
# is about). The spread is a stable hash of "tool::name" (zlib.crc32 — deterministic across
# machines, unlike the salted built-in hash()), so it is balanced and every runner agrees
# which lane a test is in. Order within a lane never matters: the fixture resets before each
# test. Bump _METADATA_LANES for more parallelism (CI reads the names via --list-shards).
# 4 lanes puts each metadata lane (~3560s of write-metadata / 4) level with the single cheap
# read-action lane (~850s + the modify_metadata stragglers), so the slowest shard is ~17 min
# — the balance point for this suite. Add lanes to go faster, at the cost of runner-minutes.
_METADATA_LANES = 4

READ_ACTION_LANE = "read-action"


def shard_names():
    """The ordered list of lane names — the single source of truth CI builds its matrix from."""
    return [READ_ACTION_LANE] + ["metadata-write-%d" % (i + 1) for i in range(_METADATA_LANES)]


def route_shard(t):
    """The one lane a test belongs to. Total by construction (an unknown/new kind falls to
    read-action), so the lanes always PARTITION the suite — no test is dropped or double-run."""
    if t.get("kind") == "write-metadata":
        bucket = zlib.crc32(("%s::%s" % (t["tool"], t["name"])).encode("utf-8")) % _METADATA_LANES
        return "metadata-write-%d" % (bucket + 1)
    return READ_ACTION_LANE


def select_shard(tests, name):
    """Tests routed to lane `name`. Rejects an unknown lane loudly, so a typo in the CI
    matrix fails the job instead of silently running nothing."""
    valid = shard_names()
    if name not in valid:
        raise SystemExit("--shard must be one of: %s (got %r)" % (", ".join(valid), name))
    return [t for t in tests if route_shard(t) == name]


def write_junit(results, path, final_clean, cleanup_failed=False):
    # Skips are neither pass nor failure: they are reported as JUnit <skipped/> and
    # excluded from the failure count (the gated live-infobase suite skips in a
    # headless run and must not turn the report red).
    # A cleanup that failed is its own synthetic case: without it an all-green run whose
    # final model sync never completed publishes a green report while the process exits
    # non-zero, and the report is what the CI check reads.
    extra = (0 if final_clean else 1) + (1 if cleanup_failed else 0)
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
    if not final_clean:
        out.append('  <testcase name="fixture::final_clean">'
                   '<failure>TestConfiguration left dirty after the run</failure></testcase>')
    if cleanup_failed:
        out.append('  <testcase name="fixture::final_cleanup">'
                   '<failure>the final model sync did not complete: the workspace model may still '
                   'differ from the committed disk</failure></testcase>')
    out.append('</testsuite>')
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))


# Set when the run is abandoning a worker (a per-test timeout). The worker thread is a daemon
# that was never actually stopped: if its slow call returns before the process exits, it would
# walk on into its own post-test cleanup and git-reset files the server may still be writing -
# the very race the abort is avoiding. It checks this flag before touching anything.
_ABANDONED = False


def abandon_workers(harness):
    """Give up on a worker we cannot stop: no more MCP calls, no cleanup, from anyone.

    The flag alone is checked only AFTER the test function returns, which is too late for a
    worker already inside reset_model or inside a test's own teardown - it would resume and
    keep calling the server the moment its current request came back. So the harness latch is
    armed as well: from here on every request is refused before it is sent.
    """
    global _ABANDONED
    _ABANDONED = True
    harness.abort_further_calls(
        "the run abandoned a test that outlived its timeout, and the server may still be "
        "working on it")


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
    """reset_fixture (disk) + reset_model (in-memory) for a write-metadata test."""
    if _ABANDONED:
        # This worker was given up on; the main thread has already decided the fixtures are
        # not safe to touch. Do not undo that decision from a thread nobody is waiting for.
        return
    if t.get("kind") == "write-metadata":
        harness.reset_fixture()
        harness.reset_model()


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
    if args.list_shards:
        # Pure metadata query — no server, no harness import. CI uses it to build the matrix.
        print(json.dumps(shard_names()))
        return
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

    shard_note = ""
    if args.shard:
        selected = len(tests)
        tests = select_shard(tests, args.shard)
        shard_note = " [shard '%s': %d of %d test(s)]" % (args.shard, len(tests), selected)

    print("EDT-MCP e2e: %d test(s) against %s, project=%s%s"
          % (len(tests), harness.MCP_URL, harness.PROJECT, shard_note))
    harness.wait_for_server()
    harness.initialize()     # proper MCP handshake (captures Mcp-Session-Id if issued)
    if not harness.wait_for_project_ready():
        # The config never reached 'ready' (still building / not_available). Every
        # metadata tool would then fail with "Could not get configuration", so running
        # the suite produces a wall of cascade failures that hides the real cause.
        # Abort with ONE actionable message + the project state, instead.
        print("\nERROR: the configuration did not finish indexing (no project reached "
              "'ready') within the wait_for_project_ready timeout. Metadata tools cannot "
              "resolve the configuration yet, so the suite is aborted before it starts.\n"
              "If the runner is just slow (a cold cloud runner indexes the whole config "
              "from scratch), raise E2E_PROJECT_READY_TIMEOUT. If it never goes ready, the "
              "project import/build is broken — check the EDT log.")
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
    for t in tests:
        if aborted_after is not None:
            results.append((t, "skip",
                            "skipped: run aborted after a TIMEOUT in %s (EDT is still busy or "
                            "hung; restart it and re-run)" % aborted_after, 0.0))
            print("[%-7s] %s::%s - aborted after timeout in %s"
                  % ("SKIP", t["tool"], t["name"], aborted_after))
            continue
        harness.reset_fixture()  # hard reset BEFORE each test (fast local git) — never trust the previous
        start = time.time()
        status, msg, timed_out = _run_with_timeout(harness, t, args.test_timeout)
        dur = time.time() - start
        results.append((t, status, msg, dur))
        head = msg.splitlines()[0] if msg else ""
        print("[%-7s] %s::%s (%.2fs)%s" % (status.upper(), t["tool"], t["name"], dur,
                                           " - " + head if head else ""))
        # A per-CALL timeout aborts the run for the same reason a per-TEST one does: the server
        # is still busy with work we cannot cancel, and every later test would be reading a
        # model it is still writing.
        if timed_out or status in ("call-timeout", "reset-failed"):
            aborted_after = "%s::%s" % (t["tool"], t["name"])
            if timed_out or status == "call-timeout":
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
    final_clean = (harness.all_fixtures_status() == "")

    npass = sum(1 for _, s, _, _ in results if s == "pass")
    nskip = sum(1 for _, s, _, _ in results if s == "skip")
    nfail = sum(1 for _, s, _, _ in results if s not in ("pass", "skip"))
    skip_note = (" | %d skipped" % nskip) if nskip else ""
    # On abort the EDT is wedged and was NOT model-synced, so 'clean' is only a point-in-time
    # disk check (EDT may re-dirty after exit) — label it so it is not read as a guarantee.
    clean_label = ("%s (point-in-time; EDT wedged)" % final_clean) if aborted_after else str(final_clean)
    print("\n== %d/%d passed%s | fixture clean: %s ==" % (npass, len(results) - nskip, skip_note, clean_label))
    if aborted_after:
        print("!! RUN ABORTED after a TIMEOUT in %s - subsequent tests were skipped. "
              "Restart EDT and re-run." % aborted_after)
    if not final_clean:
        print("!! fixtures left dirty after cleanup:\n%s" % harness.all_fixtures_status()[:500])

    if args.junit:
        write_junit(results, args.junit, final_clean, cleanup_failed)
        print("junit -> %s" % args.junit)

    # A skip is neither pass nor fail: the run is green when nothing FAILED and the
    # fixture is clean (skipped gated tests do not block a headless green run).
    # A cleanup that timed out is a failed run even when every test passed and the tree LOOKS
    # clean: the server may still be finishing that call and can re-dirty it after this check.
    sys.exit(0 if (nfail == 0 and final_clean and not cleanup_failed) else 1)


if __name__ == "__main__":
    main()
