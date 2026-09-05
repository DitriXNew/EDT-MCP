"""Pure runner-contract tests; no EDT server or fixture checkout required."""

import ast
import importlib.util
import inspect
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from unittest import mock


RUN_ALL_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "run_all.py")
SPEC = importlib.util.spec_from_file_location("edt_mcp_e2e_run_all", RUN_ALL_PATH)
RUN_ALL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUN_ALL)


class RunAllRatchetTest(unittest.TestCase):
    @staticmethod
    def _mutation_harness():
        harness = mock.Mock()
        harness.PROJECT = "Base"
        harness.EXT_OBJECTS_PROJECT = "ExternalObjects"
        harness.ALL_FIXTURE_PROJECTS = ["Base", "Extension", "ExternalObjects"]
        harness.external_objects_model_synced.return_value = True
        harness.confirmed_mutation_tools.return_value = frozenset({"modify_metadata"})
        harness.mutation_kind_violation_tools.return_value = ("modify_metadata",)
        harness.mutations_unresolved.return_value = False
        harness.mutated_fixture_projects.return_value = frozenset()
        harness.evidenced_mutation_fixture_projects.return_value = frozenset()
        harness.mutation_could_have_cascaded.return_value = False
        harness.reset_all_fixtures.return_value = True
        return harness

    def test_kind_violation_resets_every_fixture_through_model_reset_and_prints_advisory(self):
        harness = self._mutation_harness()
        output = StringIO()

        with redirect_stdout(output):
            RUN_ALL._reset_after_write(harness, {"name": "writer", "kind": "action"})

        harness.reset_all_fixtures.assert_called_once_with()
        harness.reset_model.assert_called_once_with(harness.ALL_FIXTURE_PROJECTS)
        harness.call.assert_not_called()
        self.assertIn("[kind-advisory]", output.getvalue())

    def test_kind_violation_skips_unsynced_external_objects_named_only_by_refused_call(self):
        harness = self._mutation_harness()
        harness.external_objects_model_synced.return_value = False
        # Another call produced the confirmed mutation that triggered this branch. The refused
        # call only named ExternalObjects, so it is present in the attempted-target union but not
        # in the per-call outcome-evidenced set.
        harness.mutated_fixture_projects.return_value = frozenset({"ExternalObjects"})

        RUN_ALL._reset_after_write(harness, {"name": "writer", "kind": "action"})

        harness.reset_all_fixtures.assert_called_once_with()
        harness.reset_model.assert_called_once_with(["Base", "Extension"])

    def test_kind_violation_resets_unsynced_external_objects_named_by_evidenced_call(self):
        harness = self._mutation_harness()
        harness.external_objects_model_synced.return_value = False
        harness.mutated_fixture_projects.return_value = frozenset({"ExternalObjects"})
        harness.evidenced_mutation_fixture_projects.return_value = frozenset(
            {"ExternalObjects"})

        RUN_ALL._reset_after_write(harness, {"name": "writer", "kind": "action"})

        harness.reset_all_fixtures.assert_called_once_with()
        harness.reset_model.assert_called_once_with(harness.ALL_FIXTURE_PROJECTS)
        harness.evidenced_mutation_fixture_projects.assert_called_once_with()

    def test_kind_violation_skips_unsynced_external_objects_when_call_did_not_target_it(self):
        harness = self._mutation_harness()
        harness.external_objects_model_synced.return_value = False
        harness.mutated_fixture_projects.return_value = frozenset({"Base"})

        RUN_ALL._reset_after_write(harness, {"name": "writer", "kind": "action"})

        # This case is deliberately indistinguishable from the pre-fix behaviour: an unsynced
        # fixture the call never named stays skipped either way. It guards the OPPOSITE direction
        # from its sibling above - that widening the set to "what the call targeted" did not
        # quietly become "everything" - so it is a boundary test, not a discriminating one. The
        # assertion is therefore on the decision, not on which accessor was consulted to reach it.
        harness.reset_all_fixtures.assert_called_once_with()
        harness.reset_model.assert_called_once_with(["Base", "Extension"])

    def test_kind_violation_model_reset_failure_propagates(self):
        class E2EModelResetFailed(Exception):
            pass

        harness = self._mutation_harness()
        harness.E2EModelResetFailed = E2EModelResetFailed
        harness.reset_model.side_effect = E2EModelResetFailed("could not restore fixture")

        with self.assertRaisesRegex(E2EModelResetFailed, "could not restore fixture"):
            RUN_ALL._reset_after_write(harness, {"name": "writer", "kind": "action"})

    def test_declared_write_resets_base_and_named_fixture_projects(self):
        harness = self._mutation_harness()
        harness.mutation_kind_violation_tools.return_value = ()
        harness.model_is_pristine.return_value = False
        harness.reset_fixture.return_value = True
        harness.mutated_fixture_projects.return_value = frozenset({"Extension"})

        RUN_ALL._reset_after_write(harness, {"name": "writer", "kind": "write-metadata"})

        harness.reset_model.assert_called_once_with(["Base", "Extension"])

    def test_declared_cascade_write_resets_every_available_fixture_project(self):
        harness = self._mutation_harness()
        harness.mutation_kind_violation_tools.return_value = ()
        harness.model_is_pristine.return_value = False
        harness.reset_fixture.return_value = True
        harness.external_objects_model_synced.return_value = False
        harness.mutated_fixture_projects.return_value = frozenset({"Base"})
        harness.mutation_could_have_cascaded.return_value = True

        RUN_ALL._reset_after_write(
            harness, {"name": "base delete", "kind": "write-metadata"})

        harness.reset_model.assert_called_once_with(["Base", "Extension"])

    def test_every_shard_holds_its_own_log_ratchet_out_of_the_main_loop(self):
        first = {"tool": "alpha", "name": "first"}
        deferred = {"tool": "omega", "name": "last", "last": True}
        ratchet = {"tool": "_edt_log_ratchet", "name": "audit", "last": True}

        scheduled, held = RUN_ALL.schedule_tests([first, deferred],
                                                  [first, ratchet, deferred],
                                                  per_shard=True)

        self.assertEqual([first, deferred], scheduled)
        self.assertEqual([ratchet], held)
        self.assertNotIn(ratchet, scheduled)

    def test_post_cleanup_ratchet_failure_is_a_junit_testcase_failure(self):
        ratchet = {"tool": "_edt_log_ratchet", "name": "audit"}
        results = [(ratchet, "fail", "cleanup logged a new plugin ERROR", 0.25)]
        handle, path = tempfile.mkstemp(suffix=".xml")
        os.close(handle)
        self.addCleanup(lambda: os.path.exists(path) and os.remove(path))

        RUN_ALL.write_junit(results, path, final_clean=True)

        with open(path, encoding="utf-8") as stream:
            report = stream.read()
        self.assertIn('tests="1" failures="1"', report)
        self.assertIn('_edt_log_ratchet::audit', report)
        self.assertIn('cleanup logged a new plugin ERROR', report)

    def test_main_executes_log_ratchet_after_the_last_final_cleanup_call(self):
        tree = ast.parse(inspect.getsource(RUN_ALL.main))
        cleanup_lines = []
        ratchet_loop_line = None
        nfail_line = None
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) \
                    and node.func.attr == "final_cleanup":
                cleanup_lines.append(node.lineno)
            if isinstance(node, ast.For) and isinstance(node.iter, ast.Name) \
                    and node.iter.id == "log_ratchets":
                ratchet_loop_line = node.lineno
            if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name)
                    and target.id == "nfail" for target in node.targets):
                nfail_line = node.lineno

        self.assertTrue(cleanup_lines)
        self.assertIsNotNone(ratchet_loop_line)
        self.assertIsNotNone(nfail_line)
        self.assertLess(max(cleanup_lines), ratchet_loop_line)
        self.assertLess(ratchet_loop_line, nfail_line,
                        "ratchet result must be counted before the exit decision")


if __name__ == "__main__":
    unittest.main()
