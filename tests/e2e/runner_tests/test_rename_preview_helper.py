"""The shared Pending follower every rename e2e routes through (#803); no EDT server required."""

import importlib.util
import os
import unittest
from unittest import mock


HARNESS_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "harness.py")
SPEC = importlib.util.spec_from_file_location("edt_mcp_e2e_harness_rename_preview", HARNESS_PATH)
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)

PREVIEW = "---\naction: preview\ncontentHash: abc123\n---\n\n## Change Points\n\n| # | Type |\n"
JOB_ID = "0f1e2d3c-0000-4000-8000-000000000001"


def _text(text, is_error=False):
    result = {"content": [{"type": "text", "text": text}]}
    if is_error:
        result["isError"] = True
    return HARNESS.Result({"result": result})


def _pending():
    return _text("**Pending:** the preview of 'Catalog.A' -> 'B' continues in background job `%s`.\n\n"
                 "# Background job: running\n\n| Field | Value |\n|---|---|\n| jobId | %s |\n"
                 % (JOB_ID, JOB_ID))


def _status(state, tail=""):
    return _text("# Background job: %s\n\n| Field | Value |\n|---|---|\n| jobId | %s |\n\n"
                 "## Progress\n\n- `t` — accepted\n%s" % (state, JOB_ID, tail))


class FollowPendingTest(unittest.TestCase):

    def _route(self, answers):
        calls = []

        def fake_call(tool, arguments):
            calls.append((tool, dict(arguments)))
            return answers.pop(0)
        return calls, mock.patch.object(HARNESS, "call", side_effect=fake_call)

    def test_a_pending_preview_is_followed_to_its_result(self):
        calls, patched = self._route([_pending(), _status("running"),
                                      _status("done", "\n## Result\n\n" + PREVIEW)])
        with patched, mock.patch.object(HARNESS, "CALL_TIMEOUT", 180.0):
            result = HARNESS.rename_call({"projectName": "P", "objectFqn": "Catalog.A", "newName": "B"})
        self.assertFalse(result.is_error)
        self.assertEqual(PREVIEW, result.text)
        self.assertEqual("rename_metadata_object", calls[0][0])
        self.assertEqual([("get_job_status", {"jobId": JOB_ID, "waitSeconds": 45})] * 2, calls[1:])

    def test_a_failed_job_comes_back_as_the_tool_error(self):
        _, patched = self._route([_pending(), _status("failed", "\n## Error\n\nThis was a PREVIEW")])
        with patched:
            result = HARNESS.rename_call({"projectName": "P", "objectFqn": "Catalog.A", "newName": "B"})
        self.assertTrue(result.is_error)
        self.assertEqual("This was a PREVIEW", result.text)

    def test_an_inline_answer_or_a_refusal_is_returned_unchanged(self):
        for answer in (_text(PREVIEW), _text("refused", is_error=True), _text("action: executed")):
            calls, patched = self._route([answer])
            with patched:
                self.assertIs(answer, HARNESS.rename_call({"confirm": True}))
            self.assertEqual(1, len(calls))

    def test_a_job_still_running_at_the_call_timeout_fails_loudly(self):
        _, patched = self._route([_pending()] + [_status("running")] * 3)
        with patched, mock.patch.object(HARNESS, "CALL_TIMEOUT", 0.0):
            with self.assertRaises(HARNESS.E2EAssertion) as raised:
                HARNESS.rename_call({"projectName": "P", "objectFqn": "Catalog.A", "newName": "B"})
        self.assertIn("still running", str(raised.exception))

    def test_every_rename_call_in_the_e2e_modules_goes_through_the_helper(self):
        tools = os.path.join(os.path.dirname(os.path.dirname(__file__)), "tools")
        raw = []
        for name in sorted(os.listdir(tools)):
            if not name.endswith(".py"):
                continue
            with open(os.path.join(tools, name), encoding="utf-8") as stream:
                for number, line in enumerate(stream, 1):
                    if 'call("rename_metadata_object"' in line and "rename_call(" not in line:
                        raw.append("%s:%d" % (name, number))
        # The single deliberate exceptions read the raw Pending / inline answer on purpose.
        allowed = {"test_rename_preview_job.py"}
        self.assertEqual([], [where for where in raw if where.split(":")[0] not in allowed],
                         "route rename previews through harness.rename_call so a Pending answer is followed")


if __name__ == "__main__":
    unittest.main()
