"""
e2e tests for get_event_log (kind: read).

WHAT THE TOOL DOES
------------------
get_event_log reads a 1C infobase event log (журнал регистрации) WITHOUT a running
1C session, by parsing the raw legacy-text log files (a 1Cv8.lgf references dictionary
plus dated *.lgp record partitions, "ver 2.0" format). The log directory is resolved
reflectively from projectName (+ optional applicationId) for a FILE infobase, or passed
directly via logDir (an offline copy / SERVER-mode). Filters: from/to period, user,
event, eventContains, severity, commentContains, metadataContains, session; paginated
(limit/offset/order).

RESPONSE SHAPE
--------------
JSON tool (getResponseType() == JSON); payload in r.structured:
  success:  {"success": true, "resolvedLogDir", "infobaseType", "format",
             "matched", "scanned", "returned", "limit", "offset", "truncated",
             "events": [{"date","severity","severityPresentation","user","computer",
                         "application","event","comment","metadata",
                         "metadataUuid","session","data"}]}
  error:    {"success": false, "error": "..."}

CI STRATEGY
-----------
The negative matrix is CI-safe: those branches validate/resolve BEFORE reading any log
(no live infobase, no real 1Cv8Log needed) and are the primary assertions here. A REAL
query needs an actual 1Cv8Log directory, so it is gated behind EDT_MCP_EVENT_LOG_DIR
(a path to a text-format 1Cv8Log) and SKIPPED otherwise — an attended/live check.

get_event_log is read-only and never writes the project tree: assert_no_diff() on all
CI-safe paths.
"""

import os

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    E2ESkip,
    PROJECT,
)

NONEXISTENT_PROJECT = "NoSuchProject_gel_zzz"


# ──────────────────────────────────────────────────────────────────────────────
# CONTRACT / NEGATIVE (CI-safe — validated/resolved before any log is read)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_event_log", kind="read")
def test_missing_project_and_logdir_errors_with_hint():
    """Neither projectName nor logDir -> the tool cannot locate a log; it errors up front,
    names both ways to satisfy the requirement, and steers to list_projects."""
    r = call("get_event_log", {})
    err = assert_error(r, "missing projectName and logDir")
    assert_error_quality(err, names=["projectName", "logDir"], suggests=["list_projects"],
                         ctx="missing project/logDir must name both parameters and steer to list_projects")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="get_event_log", kind="read")
def test_invalid_order_is_rejected_actionably():
    """order is a closed set {date_asc, date_desc}; an out-of-set value is rejected up front
    (before the log is located/read), echoing the bad value AND enumerating the valid set.
    A logDir is supplied only to pass the earlier project/logDir guard."""
    bad = "sideways_e2e"
    r = call("get_event_log", {"logDir": "/tmp/no_such_1cv8log_e2e", "order": bad})
    err = assert_error(r, "out-of-set order")
    assert_error_quality(err, names=[bad], suggests=["date_asc", "date_desc"],
                         ctx="invalid order echoes the bad value and lists the valid tokens")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="get_event_log", kind="read")
def test_nonexistent_project_errors_with_hint():
    """A non-existent project cannot resolve a log directory -> a 'not found' error that names
    the bad value and points at list_projects (the reflective locator uses ProjectContext)."""
    r = call("get_event_log", {"projectName": NONEXISTENT_PROJECT})
    err = assert_error(r, "nonexistent project")
    assert_error_quality(err, names=[NONEXISTENT_PROJECT], suggests=["list_projects"],
                         ctx="nonexistent project is named in the not-found error")
    assert_no_diff("a rejected call must not touch the fixture")


# ──────────────────────────────────────────────────────────────────────────────
# LIVE / ATTENDED — a real query against an actual 1Cv8Log directory
# (gated: set EDT_MCP_EVENT_LOG_DIR to a text-format 1Cv8Log path to run)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_event_log", kind="read")
def test_live_query_returns_events_from_a_real_log():
    """Point logDir at a real text-format 1Cv8Log and read a small page. Asserts the tool
    parsed the log and returned the structured envelope + at most `limit` events. Skipped
    unless EDT_MCP_EVENT_LOG_DIR is set (an attended check — see module docstring)."""
    log_dir = os.environ.get("EDT_MCP_EVENT_LOG_DIR")
    if not log_dir:
        raise E2ESkip("set EDT_MCP_EVENT_LOG_DIR to a text-format 1Cv8Log directory to run")

    r = call("get_event_log", {"logDir": log_dir, "limit": 5})
    assert_ok(r, "live get_event_log via logDir override")
    s = r.structured or {}
    if not s.get("success"):
        raise AssertionError("expected success envelope, got: %r" % (s,))
    for key in ("resolvedLogDir", "infobaseType", "format", "matched", "scanned",
                "returned", "limit", "offset", "truncated", "events"):
        if key not in s:
            raise AssertionError("structuredContent missing %r: %r" % (key, s))
    events = s.get("events") or []
    if len(events) > 5:
        raise AssertionError("limit=5 must cap the page, got %d events" % len(events))
    assert_no_diff("a read tool must not touch the project on disk")
