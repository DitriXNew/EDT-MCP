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
    return ap.parse_args()


def write_junit(results, path, final_clean):
    total = len(results) + (0 if final_clean else 1)
    fails = sum(1 for _, s, _, _ in results if s != "pass") + (0 if final_clean else 1)
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<testsuite name="edt-mcp-e2e" tests="%d" failures="%d">' % (total, fails)]
    for t, status, msg, dur in results:
        nm = su.quoteattr("%s::%s" % (t["tool"], t["name"]))
        if status == "pass":
            out.append('  <testcase name=%s time="%.3f"/>' % (nm, dur))
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
    harness.reset_fixture()  # clean start

    results = []
    for t in tests:
        harness.reset_fixture()  # hard reset BEFORE each test — never trust the previous
        start = time.time()
        status, msg = "pass", ""
        try:
            t["func"]()
        except harness.E2EAssertion as e:
            status, msg = "fail", str(e)
        except Exception as e:  # noqa: BLE001 - report any unexpected error as a test error
            status, msg = "error", "%s\n%s" % (e, traceback.format_exc())
        dur = time.time() - start
        results.append((t, status, msg, dur))
        head = msg.splitlines()[0] if msg else ""
        print("[%-5s] %s::%s (%.2fs)%s" % (status.upper(), t["tool"], t["name"], dur,
                                           " - " + head if head else ""))
        # Metadata-write tools mutate the in-memory BM model AND can persist to disk
        # (and EDT may async-autosave the model). Clean up IMMEDIATELY: revert the disk
        # first (reset_fixture), then discard the in-memory change (reset_model =
        # clean_project, which refreshes the model from the now-clean disk). Doing this
        # right after the test (not before the next) closes the autosave race window.
        if t.get("kind") == "write-metadata":
            harness.reset_fixture()
            harness.reset_model()

    # Final cleanliness guarantee.
    harness.reset_fixture()
    final_clean = (harness._status_porcelain() == "")

    npass = sum(1 for _, s, _, _ in results if s == "pass")
    print("\n== %d/%d passed | fixture clean: %s ==" % (npass, len(results), final_clean))
    if not final_clean:
        print("!! TestConfiguration left dirty:\n%s" % harness._status_porcelain()[:500])

    if args.junit:
        write_junit(results, args.junit, final_clean)
        print("junit -> %s" % args.junit)

    sys.exit(0 if (npass == len(results) and final_clean) else 1)


if __name__ == "__main__":
    main()
