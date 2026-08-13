"""Black-box contract tests for asynchronous ask_workmate jobs."""

import re

from harness import (
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_ok,
    e2e_test,
    call,
)


@e2e_test(tool="ask_workmate", kind="read")
def test_ask_workmate_real_answer_or_actionable_environment_error():
    sentinel = "EDT_MCP_WORKMATE_E2E_OK"
    result = call("ask_workmate", {
        "question": (
            "Reply with exactly EDT_MCP_WORKMATE_E2E_OK and no other text. "
            "Do not call tools."
        ),
        "maxToolRounds": 1,
        "timeoutSeconds": 30,
        "waitSeconds": 5,
    })

    assert_ok(result, "start Workmate background job")
    status, job_id = _job_status_and_id(result.text)
    for _ in range(7):
        if status != "running":
            break
        result = call("ask_workmate", {"jobId": job_id, "waitSeconds": 5})
        assert_ok(result, "poll Workmate background job")
        status, polled_id = _job_status_and_id(result.text)
        if polled_id != job_id:
            raise AssertionError(
                "ask_workmate changed jobId while polling: %s -> %s"
                % (job_id, polled_id)
            )

    if status == "running":
        raise AssertionError("Workmate job did not reach a terminal state: " + result.text)
    if status == "done":
        if sentinel not in result.text:
            raise AssertionError(
                "installed Workmate did not return the requested sentinel: "
                + result.text
            )
    elif status == "failed":
        error = result.text
        if "is not installed" in error:
            assert_error_quality(
                error,
                names=["1C:Workmate", "OSGi bundle"],
                suggests=["Install New Software", "restart EDT", "retry ask_workmate"],
            )
        elif "installed but switched off" in error:
            assert_error_quality(
                error,
                names=["1C:Workmate", "ISettings.isEnabled"],
                suggests=["Window > Preferences", "retry ask_workmate"],
            )
        elif "has no valid access key" in error:
            assert_error_quality(
                error,
                names=["1C:Workmate", "ISettings.hasClientToken"],
                suggests=["1C ITS portal", "User Token", "retry ask_workmate"],
            )
        elif "Incompatible 1C:Workmate version or structure" in error:
            assert_error_quality(
                error,
                names=["1C:Workmate"],
                suggests=["compatible with 1.0.5", "update EDT-MCP", "retry"],
            )
        elif "installed but not initialized" in error:
            assert_error_quality(
                error,
                names=["1C:Workmate"],
                suggests=["Open Workmate", "restart EDT", "retry"],
            )
        elif "did not answer within" in error or "total timeoutSeconds budget" in error:
            assert_error_quality(
                error,
                names=["30 seconds"],
                suggests=["larger timeoutSeconds", "network status"],
            )
        elif "failed to answer" in error:
            assert_error_quality(
                error,
                names=["1C:Workmate"],
                suggests=["sign-in", "network", "settings", "retry"],
            )
        elif "returned an empty answer" in error:
            assert_error_quality(
                error,
                names=["1C:Workmate"],
                suggests=["signed in", "configured", "retry"],
            )
        else:
            raise AssertionError("unexpected ask_workmate error contract: " + error)
    else:
        raise AssertionError("unexpected ask_workmate status: " + status)

    assert_no_diff()


@e2e_test(tool="ask_workmate", kind="read")
def test_ask_workmate_missing_question_is_actionable_without_workmate():
    result = call("ask_workmate", {})
    error = assert_error(result, "missing start/poll mode")
    assert_error_quality(
        error,
        names=["question", "jobId"],
        suggests=["start a new job", "poll"],
    )
    assert_no_diff()


@e2e_test(tool="ask_workmate", kind="read")
def test_ask_workmate_unknown_job_id_is_actionable_without_workmate():
    unknown = "e2e-missing-workmate-job"
    result = call("ask_workmate", {"jobId": unknown, "waitSeconds": 0})
    error = assert_error(result, "unknown Workmate job")
    assert_error_quality(
        error,
        names=["jobId", unknown],
        suggests=["Check the value", "start a new job", "question"],
    )
    assert_no_diff()


@e2e_test(tool="ask_workmate", kind="read")
def test_ask_workmate_rejects_both_modes_without_workmate():
    result = call("ask_workmate", {"question": "q", "jobId": "job-1"})
    error = assert_error(result, "mutually exclusive Workmate modes")
    assert_error_quality(
        error,
        names=["question", "jobId"],
        suggests=["only question", "only jobId", "poll"],
    )
    assert_no_diff()


@e2e_test(tool="ask_workmate", kind="read")
def test_ask_workmate_rejects_unsupported_mode_without_workmate():
    result = call("ask_workmate", {"question": "q", "mode": "jshell"})
    error = assert_error(result, "unsupported Workmate mode")
    assert_error_quality(
        error,
        names=["mode", "jshell"],
        suggests=["answer", "chat", "pass workmateTool instead", "retry ask_workmate"],
    )
    assert_no_diff()


@e2e_test(tool="ask_workmate", kind="read")
def test_ask_workmate_rejects_blank_workmate_tool_without_workmate():
    result = call("ask_workmate", {"workmateTool": "   "})
    error = assert_error(result, "blank workmateTool")
    assert_error_quality(
        error,
        names=["workmateTool", "JShellSession"],
        suggests=["non-empty name", "retry ask_workmate"],
    )
    assert_no_diff()


def _job_status_and_id(markdown):
    status_match = re.search(
        r"^# Workmate job: (running|done|failed)\s*$", markdown, re.MULTILINE
    )
    id_match = re.search(r"^\| jobId \| ([^|]+) \|\s*$", markdown, re.MULTILINE)
    if not status_match or not id_match:
        raise AssertionError("invalid ask_workmate job markdown: " + markdown)
    return status_match.group(1), id_match.group(1).strip()
