"""Pure isolation-layer contracts; no EDT server or fixture mutation required."""

import importlib.util
import os
import unittest


HARNESS_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "harness.py")
SPEC = importlib.util.spec_from_file_location("edt_mcp_e2e_harness", HARNESS_PATH)
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)


class MutationOutcomeTest(unittest.TestCase):
    def setUp(self):
        self.old_unresolved = HARNESS._MUTATIONS_UNRESOLVED
        self.old_confirmed = HARNESS._MUTATION_CONFIRMED
        self.old_confirmed_tools = set(HARNESS._CONFIRMED_MUTATION_TOOLS)
        self.old_called = set(HARNESS._CALLED_TOOLS)
        HARNESS._MUTATIONS_UNRESOLVED = 0
        HARNESS._MUTATION_CONFIRMED = False
        HARNESS._CONFIRMED_MUTATION_TOOLS.clear()
        HARNESS._CALLED_TOOLS.clear()

    def tearDown(self):
        HARNESS._MUTATIONS_UNRESOLVED = self.old_unresolved
        HARNESS._MUTATION_CONFIRMED = self.old_confirmed
        HARNESS._CONFIRMED_MUTATION_TOOLS.clear()
        HARNESS._CONFIRMED_MUTATION_TOOLS.update(self.old_confirmed_tools)
        HARNESS._CALLED_TOOLS.clear()
        HARNESS._CALLED_TOOLS.update(self.old_called)

    def test_structural_post_commit_marker_confirms_mutation_regardless_of_message(self):
        HARNESS._record_attempt("dcs")
        HARNESS._record_outcome("dcs", True, {
            "success": False,
            "error": "wording with no legacy committed phrase at all",
            "mutationCommitted": True,
        })

        self.assertEqual(0, HARNESS._MUTATIONS_UNRESOLVED)
        self.assertTrue(HARNESS._MUTATION_CONFIRMED)
        self.assertTrue(HARNESS._model_may_have_moved())

    def test_ordinary_dcs_refusal_does_not_confirm_mutation(self):
        HARNESS._record_attempt("dcs")
        HARNESS._record_outcome("dcs", True, {
            "success": False,
            "error": "validation refused before commit",
        })

        self.assertEqual(0, HARNESS._MUTATIONS_UNRESOLVED)
        self.assertFalse(HARNESS._MUTATION_CONFIRMED)
        self.assertFalse(HARNESS._model_may_have_moved())

    def test_unknown_mutation_outcome_forfeits_the_shortcut_without_a_phrase(self):
        HARNESS._record_attempt("apply_quick_fix")
        HARNESS._record_outcome("apply_quick_fix", True, {
            "success": False,
            "error": "opaque provider failed",
            "mutationOutcomeUnknown": True,
        })

        self.assertEqual(0, HARNESS._MUTATIONS_UNRESOLVED)
        self.assertTrue(HARNESS._MUTATION_CONFIRMED)
        self.assertTrue(HARNESS._model_may_have_moved())

    def test_kind_ratchet_flags_confirmed_dirtying_mutation_outside_write_metadata(self):
        HARNESS._record_attempt("create_metadata")
        HARNESS._record_outcome("create_metadata", False, {"success": True})

        violations = HARNESS.mutation_kind_violation_tools(
            "action", HARNESS.confirmed_mutation_tools())

        self.assertEqual(("create_metadata",), violations)

    def test_kind_ratchet_allows_confirmed_mutation_for_write_metadata(self):
        HARNESS._record_attempt("modify_metadata")
        HARNESS._record_outcome("modify_metadata", False, {"success": True})

        violations = HARNESS.mutation_kind_violation_tools(
            "write-metadata", HARNESS.confirmed_mutation_tools())

        self.assertEqual((), violations)

    def test_kind_ratchet_ignores_successful_clean_project_for_action(self):
        HARNESS._record_attempt("clean_project")
        HARNESS._record_outcome("clean_project", False, {"success": True})

        self.assertEqual(frozenset({"clean_project"}), HARNESS.confirmed_mutation_tools())
        self.assertEqual((), HARNESS.mutation_kind_violation_tools(
            "action", HARNESS.confirmed_mutation_tools()))

    def test_kind_ratchet_ignores_test_without_confirmed_mutation(self):
        HARNESS._record_attempt("create_metadata")
        HARNESS._record_outcome("create_metadata", True, {
            "success": False,
            "error": "validation refused before commit",
        })

        self.assertEqual(frozenset(), HARNESS.confirmed_mutation_tools())
        self.assertEqual((), HARNESS.mutation_kind_violation_tools(
            "action", HARNESS.confirmed_mutation_tools()))


if __name__ == "__main__":
    unittest.main()
