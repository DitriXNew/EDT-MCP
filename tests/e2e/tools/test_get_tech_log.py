"""Offline JSON-fixture analysis through the live MCP wire; no running 1C session needed."""
import re
from pathlib import Path

from harness import (REPO_ROOT, call, assert_ok, assert_error, assert_error_quality,
                     assert_contains, assert_no_diff, e2e_test)

FIXTURE = str(Path(REPO_ROOT) / "tests/fixtures/tech-log")


@e2e_test(tool="get_tech_log", kind="read")
def test_fixture_summary_details_chain_and_events():
    summary = call("get_tech_log", {"path": FIXTURE})
    assert_ok(summary, "JSON fixture summary")
    assert_contains(summary.text, "## Problems")
    assert_contains(summary.text, "## Coverage")
    assert_contains(summary.text, "capture enablement unknown")
    match = re.search(r"\| ([a-f0-9]{24}) \| slow-db \| DBV8DBENG", summary.text)
    assert match, summary.text
    details = call("get_tech_log", {"mode": "details", "path": FIXTURE,
                                   "groupId": match.group(1), "limit": 1})
    assert_ok(details, "SQL group details")
    assert_contains(details.text, "showing 1-1 of 2")
    assert_contains(details.text, "FULL SCAN")
    occurrence = re.search(r"\| ([a-f0-9]{24}) \|", details.text)
    assert occurrence, details.text
    chain = call("get_tech_log", {"mode": "chain", "path": FIXTURE,
                                 "occurrenceId": occurrence.group(1)})
    assert_ok(chain, "nested operation chain")
    assert_contains(chain.text, "CALL")
    assert_contains(chain.text, "SDBL")
    assert_contains(chain.text, "INCOMPLETE")
    events = call("get_tech_log", {"mode": "events", "path": FIXTURE,
                                  "event": "EXCP", "property": "descr",
                                  "contains": "Failure", "session": "7", "limit": 1, "offset": 1})
    assert_ok(events, "filtered raw events")
    assert_contains(events.text, "Failure id=456")
    assert_contains(events.text, "showing 2-2 of 2")
    assert_no_diff()


@e2e_test(tool="get_tech_log", kind="read")
def test_reference_and_actionable_refusals():
    for selector, name in (("event", "TLOCK"), ("property", "duration"),
                           ("profile", "locks"), ("problem", "slow-db")):
        result = call("get_tech_log", {"mode": "describe", selector: name})
        assert_ok(result, "reference " + name)
        assert_contains(result.text, "https://")
    result = call("get_tech_log", {"mode": "describe"})
    assert_ok(result, "reference desk")
    assert_contains(result.text, "Workflow")
    for params, names, suggests in (
        ({"mode": "describe", "event": "TDEADLOK"}, ["TDEADLOK"], ["TDEADLOCK"]),
        ({"mode": "sideways"}, ["sideways"], ["summary", "events"]),
        ({"mode": "summary"}, ["path"], ["describe"]),
        ({"mode": "details", "path": FIXTURE, "groupId": "missing"}, ["missing"], ["summary"]),
        ({"mode": "chain", "path": FIXTURE, "occurrenceId": "missing"}, ["missing"], ["details"]),
        ({"mode": "events", "path": FIXTURE, "contains": "X"}, ["contains"], ["property"]),
    ):
        error = assert_error(call("get_tech_log", params), "invalid tech log input")
        assert_error_quality(error, names=names, suggests=suggests)
    assert_no_diff()
