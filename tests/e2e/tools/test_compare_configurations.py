"""
e2e tests for compare_configurations (kind: action).

WHAT THE TOOL DOES
------------------
Starts a THREE-WAY comparison of a project's working tree against two git revisions
and reports which top objects differ. It never merges and never writes the project;
what it does change is EDT's own state, because the workbench runs exactly ONE
comparison at a time and this call takes that slot.

RESPONSE SHAPE
--------------
MARKDOWN tool: the payload lands in r.text.
  start, still running: "**Pending:** the comparison continues in background job `<id>`"
                        plus the shared "# Background job: running" snapshot
  start, finished:      "# Background job: done" + "## Result" + "# Comparison: <project>"
  error:                {"success": false, "error": "..."} delivered as isError

CI STRATEGY - WHY BOTH REVISIONS ARE "HEAD"
-------------------------------------------
`.github/workflows/e2e-tests.yml` checks out with the default `fetch-depth: 1`, so
`HEAD~1` DOES NOT EXIST on CI and any test pinned to real history would fail there and
pass locally. The alternative - manufacturing a commit inside the run - would write to
the very clone the stand imports its fixture from, and a `--filter git` slice has
already moved that clone's HEAD live once. So these tests use `HEAD` for both sides:
it resolves on any clone, shallow or not, it needs no commit, no branch switch and no
tag, and it exercises exactly the contract under test (launch, refusal, cancellation,
error quality). WHAT the tree contains is proved by the unit tests over a stubbed node
set, not here.

The tool is read-only with respect to the project, so assert_no_diff() on every path.

CANCELLATION IS PART OF THE TEST, NOT CLEANUP
---------------------------------------------
Every launched comparison is released before the test returns. A comparison left
alive holds EDT's single slot and its temporary workspace, which would make every
later test in the file - and any later run - fail with "already running".

Releasing takes TWO calls, not one, because the two states need different ones:
`cancel_job` stops a comparison that is still RUNNING, and it cannot end one that has
FINISHED - that job is terminal, so the tool's cancellation handler never runs at all.
A finished comparison is given back with `compare_configurations(releaseComparisonId=...)`.
`_finish()` below does both, which is why no test here calls `_cancel` on its own.
"""

import re
import time

from harness import (
    E2ESkip,
    assert_contains,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_ok,
    call,
    e2e_test,
    PROJECT,
)

JOB_ID_ROW = re.compile(r"^\| jobId \| ([^|]+) \|\s*$", re.MULTILINE)
COMPARISON_ID_ROW = re.compile(r"^\| comparisonId \| ([^|]+) \|\s*$", re.MULTILINE)
# The launch journals this the moment EDT has ACCEPTED the batch - see _await_slot_taken.
STARTED_LINE = re.compile(r"Comparison (cmp-\d+) started\.")

NONEXISTENT_PROJECT = "NoSuchProject_cmpcfg_zzz"

HEAD = "HEAD"


def _job_id(result):
    match = JOB_ID_ROW.search(result.text)
    if not match:
        raise AssertionError("no jobId in the compare_configurations result: " + result.text)
    return match.group(1).strip()


def _cancel(job_id):
    """Stop a launched comparison; tolerate a job that already ended by itself."""
    if not job_id:
        return None
    return call("cancel_job", {"jobId": job_id, "confirm": True})


def _release(comparison_id):
    """Give a finished comparison's session - and EDT's single slot - back."""
    if not comparison_id:
        return None
    return call("compare_configurations", {"releaseComparisonId": comparison_id})


def _comparison_id(result):
    """The comparisonId out of a rendered report, or None when there is no report yet."""
    match = COMPARISON_ID_ROW.search(getattr(result, "text", "") or "")
    return match.group(1).strip() if match else None


def _finish(job_id):
    """Free the slot whatever state the comparison reached.

    cancel_job covers the RUNNING case and nothing else: a comparison that finished has
    published its result, so its job is terminal and the owning tool's handler is never
    invoked. The report the job carries names the comparisonId, and releasing that is what
    hands the slot back.
    """
    if not job_id:
        return
    _cancel(job_id)
    status = call("get_job_status", {"jobId": job_id, "waitSeconds": 0})
    _release(_comparison_id(status))


def _await_slot_taken(job_id, rounds=20, seconds=0.5):
    """Wait until the launch has really handed the comparison to EDT.

    A RUNNING JOB IS NOT A RUNNING COMPARISON, and that is the whole reason this helper
    exists. `waitSeconds=0` answers the moment the job is SUBMITTED; the worker thread
    still has two git revision resolutions, a project lookup, a scope build and the batch
    construction to get through before it registers anything or hands EDT a batch. Until
    it does, EDT's single slot is genuinely free and a second launch is genuinely allowed
    - so a test that attempts one on the strength of "| status | running |" is measuring
    the worker's head start, not the one-at-a-time rule, and fails whenever the machine
    is slow enough for the second call to overtake the first.

    The observable evidence that the slot IS taken is the launch's own progress line,
    published immediately after the platform accepted the batch.

    Returns the live comparisonId, or None when the job reached a terminal state first -
    the slot is free again then, and the premise of the caller is gone rather than broken.
    """
    for _ in range(rounds):
        status = call("get_job_status", {"jobId": job_id, "waitSeconds": 0})
        text = status.text or ""
        found = STARTED_LINE.search(text)
        if found:
            return found.group(1)
        if "| status | running |" not in text:
            return None
        time.sleep(seconds)
    return None


def _start(**overrides):
    args = {
        "projectName": PROJECT,
        "otherRevision": HEAD,
        "ancestorRevision": HEAD,
        "waitSeconds": 0,
    }
    args.update(overrides)
    return call("compare_configurations", args)


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH — the launch returns while the comparison is still running
# ──────────────────────────────────────────────────────────────────────────────


@e2e_test(tool="compare_configurations", kind="action")
def test_launch_returns_a_job_id_without_waiting_for_the_comparison():
    """The whole point of the async contract: waitSeconds=0 comes back with a jobId
    instead of holding the request for the minutes a real comparison takes.

    Mutation check: a tool that waited for COMPARISON_PROCESS_FINISHED before answering
    would either time out this call or return a finished report, and the Pending
    assertion below would fail.
    """
    started = _start()
    assert_ok(started, "start a three-way comparison")
    job_id = None
    try:
        assert_contains(started.text, "jobId", "the launch must return a job handle")
        job_id = _job_id(started)

        status = call("get_job_status", {"jobId": job_id, "waitSeconds": 0})
        assert_ok(status, "poll the comparison job")
        assert_contains(status.text, "compare_configurations",
                        "the job must name its owning tool")
        if "| status | running |" not in status.text:
            # A comparison that ended within the same second is possible on a tiny
            # fixture; it does not disprove the contract, and the assertion above
            # already proved the CALL did not block.
            assert_contains(status.text, "| status |", "the job snapshot must state a status")
    finally:
        _finish(job_id)
    assert_no_diff("compare_configurations must not touch the project")


@e2e_test(tool="compare_configurations", kind="action")
def test_a_second_launch_is_refused_naming_the_live_comparison():
    """EDT runs one comparison per workbench, so the second call must REFUSE rather
    than queue - a queued launch looks accepted and then sits behind invisible work."""
    first = _start()
    assert_ok(first, "start the first comparison")
    job_id = _job_id(first)
    try:
        # Waited for, not assumed: the previous version of this test attempted the second
        # launch as soon as the JOB reported "running", which it does from the moment it is
        # submitted. On a slow enough machine the first worker had not reached EDT yet, the
        # slot was honestly free, the second launch was honestly accepted, and the test read
        # that as the one-at-a-time rule being broken.
        live_id = _await_slot_taken(job_id)
        if live_id is None:
            raise E2ESkip("the first comparison ended before a second could be attempted")

        second = _start()
        error = assert_error(second, "a second comparison while one is live")
        assert_contains(error, live_id,
                        "the refusal must NAME the comparison in the way, not just say one exists")
        assert_error_quality(
            error,
            names=["cancel_job"],
            suggests=["refused rather than queued", "one at a time"],
        )
    finally:
        _finish(job_id)
    assert_no_diff("a refused second launch must not touch the project")


@e2e_test(tool="compare_configurations", kind="action")
def test_cancel_job_stops_the_comparison_and_frees_the_slot():
    """cancel_job on the returned jobId must really stop the comparison: the proof is
    that a fresh launch is accepted afterwards, which the one-at-a-time rule would
    forbid if the session were still alive.

    Mutation check: a cancellation that only marked the JOB cancelled, leaving EDT's
    comparison running, would make the second launch below fail with "already running".
    """
    first = _start()
    assert_ok(first, "start a comparison to cancel")
    job_id = _job_id(first)
    cancelled = _cancel(job_id)
    assert_ok(cancelled, "cancel the comparison")
    assert_contains(cancelled.text, "cancellation:", "cancel_job must report an outcome")

    second_job = None
    try:
        # Polled rather than asserted once: EDT releases the session on its own thread,
        # so a single immediate attempt would test the timing, not the release.
        again = None
        for attempt in range(6):
            again = _start()
            if not again.is_error:
                break
            time.sleep(2)
        assert_ok(again, "start a comparison after the previous one was cancelled")
        second_job = _job_id(again)
    finally:
        _finish(second_job)
    assert_no_diff("cancelling a comparison must not touch the project")


@e2e_test(tool="compare_configurations", kind="action")
def test_releasing_a_finished_comparison_frees_the_slot():
    """A FINISHED comparison still holds EDT's single slot, and cancel_job cannot give it
    back: its job is terminal, so the owning tool's cancellation handler is never invoked.
    releaseComparisonId is the way back, and the proof is that a fresh launch is accepted.

    Mutation check: remove the release call below and the second launch is refused with
    "already running"; remove the release path from the tool and the first call errors.
    """
    first = _start(waitSeconds=25)
    assert_ok(first, "start a comparison and wait for it to finish")
    job_id = _job_id(first)
    comparison_id = _comparison_id(first)
    if not comparison_id:
        # No report means it had not finished inside the start call's wait. The premise of
        # this test is a FINISHED comparison, so it is skipped rather than weakened.
        _finish(job_id)
        raise E2ESkip("the comparison did not finish within the start call's wait")

    released = _release(comparison_id)
    assert_ok(released, "release the finished comparison")
    assert_contains(released.text, "Released", "the release must say what it did")
    assert_contains(released.text, comparison_id, "the release must name the comparison")

    second_job = None
    try:
        again = _start()
        assert_ok(again, "start a comparison after the previous one was released")
        second_job = _job_id(again)
    finally:
        _finish(second_job)
    assert_no_diff("releasing a comparison must not touch the project")


# ──────────────────────────────────────────────────────────────────────────────
# ERROR QUALITY — nothing is started, so nothing needs cancelling
# ──────────────────────────────────────────────────────────────────────────────


@e2e_test(tool="compare_configurations", kind="action")
def test_unknown_project_is_actionable():
    result = _start(projectName=NONEXISTENT_PROJECT, waitSeconds=10)
    error = assert_error(result, "unknown project")
    assert_error_quality(error, names=[NONEXISTENT_PROJECT], suggests=["list_projects"])
    assert_no_diff()


@e2e_test(tool="compare_configurations", kind="action")
def test_unresolvable_revision_names_the_value_and_the_fix():
    """A revision git cannot resolve must name the offending value, not just fail.

    Reported on the JOB rather than as a structured error, and deliberately so: whether
    a revision resolves is only knowable once the project's repository is open, which is
    the job's work. What must not happen is a job that reports the failure as "still
    running" - the platform's comparison status has no failed literal, so that is a real
    way for this to break.
    """
    bogus = "no-such-revision-cmpcfg-zzz"
    result = _start(otherRevision=bogus, waitSeconds=10)
    assert_ok(result, "start with an unresolvable revision")
    assert_contains(result.text, "# Background job: failed",
                    "an unresolvable revision must fail the job, not leave it running")
    assert_error_quality(result.text, names=[bogus], suggests=["list_git_branches"])
    assert_no_diff()


@e2e_test(tool="compare_configurations", kind="action")
def test_missing_required_arguments_are_actionable():
    result = call("compare_configurations", {"projectName": PROJECT})
    error = assert_error(result, "missing revisions")
    assert_error_quality(error, names=["otherRevision"])
    assert_no_diff()


@e2e_test(tool="compare_configurations", kind="action")
def test_wait_seconds_out_of_range_is_actionable():
    result = _start(waitSeconds=600)
    error = assert_error(result, "wait outside the transport-safe range")
    assert_error_quality(error, names=["waitSeconds"], suggests=["0 to 25"])
    assert_no_diff()


@e2e_test(tool="compare_configurations", kind="action")
def test_releasing_a_comparison_nobody_holds_is_actionable():
    """Refused rather than reported as a release: "there was nothing to release" and "the
    comparison you named is closed" are different facts, and a caller acting on the second
    would believe a slot was freed that somebody else still holds."""
    missing = "cmp-no-such-cmpcfg-zzz"
    result = call("compare_configurations", {"releaseComparisonId": missing})
    error = assert_error(result, "release of a comparison nobody holds")
    assert_error_quality(error, names=[missing], suggests=["not running"])
    assert_no_diff()


@e2e_test(tool="compare_configurations", kind="action")
def test_unreadable_merge_rules_file_is_refused_before_anything_starts():
    """Checked before the launch on purpose: a typo that took EDT's single comparison
    slot and only then failed would block the next honest attempt as well."""
    missing = "no-such-directory-cmpcfg-zzz/rules.xml"
    result = _start(mergeRulesFile=missing, waitSeconds=10)
    error = assert_error(result, "unreadable merge-rules file")
    assert_error_quality(error, names=["mergeRulesFile"], suggests=["omit"])
    assert_no_diff()
