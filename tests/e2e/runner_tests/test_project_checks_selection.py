"""Offline regressions for the stand test's check selection; no EDT or harness calls."""

import ast
import contextlib
from pathlib import Path
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "tools/test_project_checks.py"


class FixtureSkip(Exception):
    pass


class SelectionObserved(Exception):
    pass


class ProjectChecksSelectionTest(unittest.TestCase):
    def namespace(self, rows, **overrides):
        # Loading only these AST nodes avoids importing the live stand harness.
        tree = ast.parse(SOURCE.read_text(encoding="utf-8-sig"))
        names = {"_cheap_noncritical_check", "_check_with_markers", "_stored_check",
                 "test_set_disable_then_restore_round_trips_through_before_and_writes_the_cset"}
        functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names]
        for node in functions:
            node.decorator_list = []
        namespace = dict(E2ESkip=FixtureSkip, _all=lambda: rows,
                         _success=lambda *args: {"activeProfile": "Default"}, _stored=lambda *args: {})
        namespace.update(overrides)
        exec(compile(ast.Module(body=functions, type_ignores=[]), str(SOURCE), "exec"), namespace)
        return namespace

    def select(self, rows):
        # Exercise the real round-trip test's selection, including its call site.
        observed = []

        @contextlib.contextmanager
        def temporary(row, **values):
            observed.append(row)
            raise SelectionObserved()
            yield  # Keeps the stand's context-manager call path intact.

        namespace = self.namespace(rows, _temporary_set=temporary)
        try:
            namespace["test_set_disable_then_restore_round_trips_through_before_and_writes_the_cset"]()
        except SelectionObserved:
            return observed[0]
        self.fail("The round-trip test never selected a check")

    @staticmethod
    def row(check_id="begin-transaction", contributor="a", **overrides):
        row = dict(checkId=check_id, contributorId=contributor, uid=check_id + "|" + contributor, shortUid="SU1",
                   enabled=True, defaultEnabled=True, customized=False,
                   criticalDataIntegrity=False, hasDocumentation=True)
        row.update(overrides)
        return row

    def test_critical_undocumented_and_nondefault_checks_are_excluded(self):
        safe = self.row("empty-except-statement")
        rows = [self.row(criticalDataIntegrity=True), self.row(hasDocumentation=False),
                self.row(customized=True), self.row(defaultEnabled=False),
                self.row("expensive-project-wide-check"), safe]
        self.assertEqual(safe, self.select(rows))

    def test_preference_and_contributor_order_are_deterministic(self):
        preferred = self.row(contributor="a")
        rows = [self.row("empty-except-statement"), self.row(contributor="z"), preferred]
        self.assertEqual(preferred, self.select(rows))
        self.assertEqual(preferred, self.select(list(reversed(rows))))

    def test_a_fixture_without_a_safe_check_is_skipped_before_writing(self):
        with self.assertRaises(FixtureSkip):
            self.select([self.row(criticalDataIntegrity=True)])

    def test_marker_scenarios_choose_a_safe_check_and_accept_its_short_uid(self):
        critical = self.row(criticalDataIntegrity=True)
        safe = self.row("empty-except-statement", shortUid="SU2")
        namespace = self.namespace([critical, safe],
                                   _error_rows=lambda: [critical["checkId"], safe["shortUid"]])
        self.assertEqual(safe, namespace["_check_with_markers"]())
        namespace["_error_rows"] = lambda: [critical["checkId"]]
        with self.assertRaises(FixtureSkip):
            namespace["_check_with_markers"]()


if __name__ == "__main__":
    unittest.main()
