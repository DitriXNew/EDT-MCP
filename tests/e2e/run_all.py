#!/usr/bin/env python3
"""
EDT-MCP e2e orchestrator.

Discovers every @e2e_test in tests/e2e/tools/test_*.py and runs them SERIALLY
(all tests mutate the same TestConfiguration + git tree, so they cannot run in
parallel). Resets the fixture before EVERY test, enforces a clean final state,
and emits a JUnit XML report. See SKILL.md.

Usage:
    python tests/e2e/run_all.py [--host H] [--port P] [--project NAME]
                                [--junit-xml PATH] [--filter SUBSTR]

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
    ap.add_argument("--test-timeout", type=float,
                    default=float(os.environ.get("MCP_TEST_TIMEOUT", "600")),
                    help="per-test wall-clock timeout in seconds (default 600). Must exceed the "
                         "slowest LEGIT test: a write-metadata unit chains the test call + "
                         "clean_project + wait_for_project_ready, each bounded by MCP_CALL_TIMEOUT "
                         "(~180s), so keep it well above ~3x that. If a test exceeds the timeout it "
                         "is FAILED (timeout) and ALL remaining tests are SKIPPED (a hung EDT makes "
                         "them hang too). No auto-relaunch - restart EDT and re-run.")
    return ap.parse_args()


def write_junit(results, path, final_clean):
    # Skips are neither pass nor failure: they are reported as JUnit <skipped/> and
    # excluded from the failure count (the gated live-infobase suite skips in a
    # headless run and must not turn the report red).
    total = len(results) + (0 if final_clean else 1)
    fails = sum(1 for _, s, _, _ in results if s not in ("pass", "skip")) + (0 if final_clean else 1)
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
    out.append('</testsuite>')
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))


def _run_test_unit(harness, t):
    """All EDT-touching work for ONE test, timed as a unit: the test fn plus, for a
    write-metadata test, its model cleanup (reset_fixture reverts disk; reset_model =
    clean_project refreshes the in-memory model — the step that actually hung when EDT's
    ProjectRestartJob wedged). The pre-test reset_fixture is fast local git and is done by
    the caller OUTSIDE the timeout."""
    t["func"]()
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

    print("EDT-MCP e2e: %d test(s) against %s, project=%s" % (len(tests), harness.MCP_URL, harness.PROJECT))
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
    harness.final_cleanup()  # clean start: revert BOTH fixtures + sync EDT model so the run
                             # does not begin on a stale extension edit (e.g. a manual experiment)

    # Each test (incl. its write-metadata model cleanup, see _run_test_unit) runs under a
    # per-test wall-clock timeout. If a test exceeds it, EDT is almost certainly hung (the
    # clean_project / ProjectRestartJob wedge that motivated this), so the test is FAILED
    # (timeout) and EVERY remaining test is SKIPPED rather than each also hanging for the
    # full timeout. No EDT auto-relaunch — restart it and re-run.
    results = []
    aborted_after = None
    for t in tests:
        if aborted_after is not None:
            results.append((t, "skip",
                            "skipped: run aborted after a TIMEOUT in %s (EDT likely hung; "
                            "restart it and re-run)" % aborted_after, 0.0))
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
        if timed_out:
            aborted_after = "%s::%s" % (t["tool"], t["name"])

    # Final cleanliness guarantee across BOTH fixtures (base + extension). On a normal run,
    # full cleanup (revert + EDT model sync) so a stale model can't autosave changes back
    # after the run. On an ABORT the EDT is wedged, so model-sync would hang — do git-only.
    if aborted_after:
        harness.reset_all_fixtures()
    else:
        harness.final_cleanup()
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
        write_junit(results, args.junit, final_clean)
        print("junit -> %s" % args.junit)

    # A skip is neither pass nor fail: the run is green when nothing FAILED and the
    # fixture is clean (skipped gated tests do not block a headless green run).
    sys.exit(0 if (nfail == 0 and final_clean) else 1)


if __name__ == "__main__":
    main()
