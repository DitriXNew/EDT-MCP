"""Black-box contract tests for shared background-job polling."""

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


@e2e_test(tool="get_job_status", kind="read")
def test_get_job_status_unknown_id_is_actionable():
    unknown = "e2e-expired-background-job"
    result = call("get_job_status", {"jobId": unknown, "waitSeconds": 0})
    error = assert_error(result, "unknown background job")
    assert_error_quality(
        error,
        names=["jobId", unknown],
        suggests=["tool that originally created it", "new jobId", "get_job_status"],
    )
    assert_no_diff()


@e2e_test(tool="get_job_status", kind="read")
def test_get_job_status_missing_id_is_actionable():
    result = call("get_job_status", {})
    error = assert_error(result, "missing background job id")
    assert_error_quality(
        error,
        names=["jobId"],
        suggests=["tool that started the job"],
    )
    assert_no_diff()


@e2e_test(tool="get_job_status", kind="read")
def test_get_job_status_rejects_unsafe_wait():
    result = call(
        "get_job_status",
        {"jobId": "e2e-any-job", "waitSeconds": 46},
    )
    error = assert_error(result, "transport-unsafe polling wait")
    assert_error_quality(
        error,
        names=["waitSeconds", "46", "0 to 45"],
        suggests=["Pass 0", "omit it"],
    )
    assert_no_diff()


@e2e_test(tool="get_job_status", kind="read")
def test_get_job_status_polls_a_real_workmate_job_when_enabled():
    if not WORKMATE_ENABLED:
        raise E2ESkip(
            "requires EDT_MCP_WORKMATE_E2E=1 and an enabled ask_workmate tool"
        )

    started = call(
        "ask_workmate",
        {
            "question": "Reply with exactly JOB_STATUS_E2E_OK. Do not call tools.",
            "maxToolRounds": 1,
            "timeoutSeconds": 30,
            "waitSeconds": 0,
        },
    )
    assert_ok(started, "start a real background job")
    match = re.search(r"^\| jobId \| ([^|]+) \|\s*$", started.text, re.MULTILINE)
    if not match:
        raise AssertionError("ask_workmate did not return a jobId: " + started.text)

    job_id = match.group(1).strip()
    polled = call("get_job_status", {"jobId": job_id, "waitSeconds": 5})
    assert_ok(polled, "poll the real Workmate job")
    if "# Background job:" not in polled.text:
        raise AssertionError("missing shared job snapshot heading: " + polled.text)
    if "| owningTool | ask_workmate |" not in polled.text:
        raise AssertionError("snapshot did not retain the owning tool: " + polled.text)
    if "| jobId | %s |" % job_id not in polled.text:
        raise AssertionError("snapshot changed the jobId: " + polled.text)
    assert_no_diff()
