"""Exercise the real page-size stand test offline, including its old digest blind spot."""

import ast
import json
from pathlib import Path
from types import SimpleNamespace
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "tools" / "test_project_checks_page_size.py"
TEST_NAME = "test_default_and_maximum_pages_fit_below_the_text_guard"


class ProjectChecksPageSizeTest(unittest.TestCase):
    def exercise(self, *, default_padding=10000, maximum_padding=20000, truncated=False,
                 astral=False, sessions=None):
        tree = ast.parse(SOURCE.read_text(encoding="utf-8-sig"))
        functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)]
        for node in functions:
            node.decorator_list = []
        namespace = dict(json=json, PROJECT="TestConfiguration", _snapshot=lambda: {},
                         assert_no_diff=lambda: None, assert_ok=lambda *args: None)
        sessions = sessions if sessions is not None else []
        opted_out = False

        def initialize(capabilities=None):
            nonlocal opted_out
            sessions.append(capabilities)
            opted_out = capabilities == {"experimental": {"structuredContent": False}}

        def call(tool, args):
            self.assertEqual("project_checks", tool)
            maximum = "limit" in args
            returned = 100 if maximum else 50
            padding = maximum_padding if maximum else default_padding
            page = dict(success=True, total=300, returned=returned,
                        checks=[dict(title=("\U0001f600" if astral else "x") * padding)]
                        + [{} for _ in range(returned - 1)])
            text = json.dumps(page, ensure_ascii=False, separators=(",", ":"))
            if truncated:
                text = text[:1000] + "\n[OUTPUT TRUNCATED]"
            # Matches ToolCallResult.json: default text is a tiny digest even when
            # the structured payload exceeds the guard used by text-only delivery.
            digest = "Success; returned=" + str(returned)
            self.assertLess(len(digest), 50000)
            return SimpleNamespace(structured=None if opted_out else page, text=text if opted_out else digest)

        namespace.update(initialize=initialize, call=call)
        exec(compile(ast.Module(body=functions, type_ignores=[]), str(SOURCE), "exec"), namespace)
        namespace[TEST_NAME]()
        return sessions

    def test_full_text_pages_with_safe_headroom_pass_and_restore_the_session(self):
        self.assertEqual([{"experimental": {"structuredContent": False}}, None], self.exercise())

    def test_old_digest_measurement_misses_oversize_default_page_but_full_text_rejects_it(self):
        sessions = []
        with self.assertRaisesRegex(AssertionError, "full text page near guard"):
            self.exercise(default_padding=110000, sessions=sessions)
        self.assertEqual([{"experimental": {"structuredContent": False}}, None], sessions)

    def test_old_digest_measurement_misses_oversize_maximum_page_but_full_text_rejects_it(self):
        with self.assertRaisesRegex(AssertionError, "full text page near guard"):
            self.exercise(maximum_padding=110000)

    def test_default_page_must_stay_below_half_the_guard(self):
        with self.assertRaisesRegex(AssertionError, "full text page near guard"):
            self.exercise(default_padding=50000)

    def test_maximum_page_must_leave_twenty_percent_headroom(self):
        with self.assertRaisesRegex(AssertionError, "full text page near guard"):
            self.exercise(maximum_padding=80000)

    def test_truncation_is_detected_before_parsing_incomplete_json(self):
        with self.assertRaisesRegex(AssertionError, "full text page was truncated"):
            self.exercise(truncated=True)

    def test_measurement_counts_java_utf16_units_for_non_bmp_titles(self):
        with self.assertRaisesRegex(AssertionError, "full text page near guard"):
            self.exercise(default_padding=26000, astral=True)


if __name__ == "__main__":
    unittest.main()
