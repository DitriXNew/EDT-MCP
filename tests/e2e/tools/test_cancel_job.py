"""Black-box contract tests for capability-aware confirm-preview job cancellation.

Committed jobs remain alreadyCommitted unless their owner declared a destructive
cancellation handler at start. In particular, ask_workmate must never inherit the
YAXUnit client-termination capability.
"""

import os
import re

from harness import (
    E2ESkip,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_ok,
    call,
    e2e_test,
)


WORKMATE_ENABLED = os.environ.get("EDT_MCP_WORKMATE_E2E", "").strip() not in (
    "", "0", "false", "no"
)


@e2e_test(tool="cancel_job", kind="action")
def test_cancel_job_unknown_id_is_actionable_without_confirm():
    unknown = "e2e-expired-cancellable-job"
    result = call("cancel_job", {"jobId": unknown})
    error = assert_error(result, "unknown cancellation preview")
    assert_error_quality(
        error,
        names=["jobId", unknown],
        suggests=["tool that originally created it", "new jobId", "cancel_job"],
    )
    assert_no_diff()


@e2e_test(tool="cancel_job", kind="action")
def test_cancel_job_missing_id_is_actionable():
    result = call("cancel_job", {"confirm": True})
    error = assert_error(result, "missing cancellation target")
    assert_error_quality(
        error,
        names=["jobId"],
        suggests=["tool that started the job"],
    )
    assert_no_diff()


@e2e_test(tool="cancel_job", kind="action")
def test_cancel_job_previews_then_reports_the_honest_commit_outcome_when_enabled():
    if not WORKMATE_ENABLED:
        raise E2ESkip(
            "requires EDT_MCP_WORKMATE_E2E=1 and an enabled ask_workmate tool"
        )

    started = call(
        "ask_workmate",
        {
            "question": "Reply with exactly CANCEL_JOB_E2E_OK. Do not call tools.",
            "maxToolRounds": 1,
            "timeoutSeconds": 30,
            "waitSeconds": 0,
        },
    )
    assert_ok(started, "start cancellation target")
    match = re.search(r"^\| jobId \| ([^|]+) \|\s*$", started.text, re.MULTILINE)
    if not match:
        raise AssertionError("ask_workmate did not return a jobId: " + started.text)
    job_id = match.group(1).strip()

    preview = call("cancel_job", {"jobId": job_id})
    assert_ok(preview, "preview background-job cancellation")
    for expected in (
        "# Background job cancellation: preview",
        "No change was made",
        "owned by `ask_workmate`",
        "confirm=true",
    ):
        if expected not in preview.text:
            raise AssertionError("incomplete cancellation preview: " + preview.text)
    if "client process" in preview.text or "infobase" in preview.text:
        raise AssertionError(
            "ask_workmate preview inherited another owner's capability: " + preview.text
        )

    confirmed = call("cancel_job", {"jobId": job_id, "confirm": True})
    assert_ok(confirmed, "confirm background-job cancellation")
    if "cancellation: cancelled" in confirmed.text:
        for expected in ("was cancelled before", "| status | cancelled |"):
            if expected not in confirmed.text:
                raise AssertionError("dishonest cancelled outcome: " + confirmed.text)
    elif "cancellation: alreadyCommitted" in confirmed.text:
        for expected in ("NOT cancelled", "cannot be recalled", "do not start a duplicate"):
            if expected not in confirmed.text:
                raise AssertionError("dishonest committed outcome: " + confirmed.text)
    elif "cancellation: terminated" in confirmed.text:
        raise AssertionError(
            "ask_workmate cannot recall a dispatched cloud request: " + confirmed.text
        )
    elif "cancellation: alreadyTerminal" not in confirmed.text:
        raise AssertionError("unexpected cancellation outcome: " + confirmed.text)
    assert_no_diff()
