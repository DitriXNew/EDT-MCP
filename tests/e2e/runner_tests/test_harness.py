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
        self.old_called = set(HARNESS._CALLED_TOOLS)
        HARNESS._MUTATIONS_UNRESOLVED = 0
        HARNESS._MUTATION_CONFIRMED = False
        HARNESS._CALLED_TOOLS.clear()

    def tearDown(self):
        HARNESS._MUTATIONS_UNRESOLVED = self.old_unresolved
        HARNESS._MUTATION_CONFIRMED = self.old_confirmed
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


class SettleProgressNoteTest(unittest.TestCase):
    """The note REPORTS what a failed settle saw; it must never become a decision.

    list_projects answers a coarse categorical state, so an unchanged snapshot cannot tell a
    stalled queue from a slow one. An earlier revision shortened the retries on exactly that
    signal, which would have failed slow-but-healthy runs.
    """

    def test_stalled_snapshot_names_the_polls_and_seconds_and_owns_its_ambiguity(self):
        note = HARNESS._settle_progress_note({"changed": False, "polls": 287, "elapsed": 600})

        self.assertIn("287 polls", note)
        self.assertIn("600s", note)
        self.assertIn("does not", note)

    def test_observed_change_is_reported_as_such(self):
        note = HARNESS._settle_progress_note({"changed": True, "polls": 287, "elapsed": 600})

        self.assertIn("changed", note)
        self.assertIn("287 polls", note)

    def test_missing_progress_keys_do_not_raise(self):
        self.assertIn("0 polls", HARNESS._settle_progress_note({}))


if __name__ == "__main__":
    unittest.main()
