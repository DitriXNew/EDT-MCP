"""Pure isolation-layer contracts; no EDT server or fixture mutation required."""

import importlib.util
import os
import unittest
from unittest import mock


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
        self.old_mutated_projects = set(HARNESS._MUTATED_PROJECTS)
        HARNESS._MUTATIONS_UNRESOLVED = 0
        HARNESS._MUTATION_CONFIRMED = False
        HARNESS._CONFIRMED_MUTATION_TOOLS.clear()
        HARNESS._CALLED_TOOLS.clear()
        HARNESS._MUTATED_PROJECTS.clear()

    def tearDown(self):
        HARNESS._MUTATIONS_UNRESOLVED = self.old_unresolved
        HARNESS._MUTATION_CONFIRMED = self.old_confirmed
        HARNESS._CONFIRMED_MUTATION_TOOLS.clear()
        HARNESS._CONFIRMED_MUTATION_TOOLS.update(self.old_confirmed_tools)
        HARNESS._CALLED_TOOLS.clear()
        HARNESS._CALLED_TOOLS.update(self.old_called)
        HARNESS._MUTATED_PROJECTS.clear()
        HARNESS._MUTATED_PROJECTS.update(self.old_mutated_projects)

    def test_mutating_attempt_tracks_fixture_project_but_read_attempt_does_not(self):
        HARNESS._record_attempt(
            "modify_metadata", {"projectName": HARNESS.TESTS_PROJECT})

        self.assertEqual(
            frozenset({HARNESS.TESTS_PROJECT}), HARNESS.mutated_fixture_projects())

        HARNESS.begin_test_calls()
        HARNESS._record_attempt(
            "get_metadata_objects", {"projectName": HARNESS.TESTS_PROJECT})

        self.assertEqual(frozenset(), HARNESS.mutated_fixture_projects())

    def test_mutating_attempt_ignores_project_that_is_not_a_fixture(self):
        HARNESS._record_attempt("modify_metadata", {"projectName": "UnrelatedProject"})

        self.assertEqual(frozenset(), HARNESS.mutated_fixture_projects())

    def test_model_is_not_pristine_after_non_base_fixture_mutation(self):
        HARNESS._MUTATED_PROJECTS.add(HARNESS.TESTS_PROJECT)

        with mock.patch.object(HARNESS, "_BASELINE_INVENTORY", ("baseline",)), \
                mock.patch.object(HARNESS, "_model_may_have_moved", return_value=False):
            self.assertFalse(HARNESS.model_is_pristine())

    def test_structural_post_commit_marker_confirms_mutation_regardless_of_message(self):
        HARNESS._record_attempt("dcs")
        HARNESS._record_outcome("dcs", {}, True, {
            "success": False,
            "error": "wording with no legacy committed phrase at all",
            "mutationCommitted": True,
        })

        self.assertEqual(0, HARNESS._MUTATIONS_UNRESOLVED)
        self.assertTrue(HARNESS._MUTATION_CONFIRMED)
        self.assertTrue(HARNESS._model_may_have_moved())

    def test_ordinary_dcs_refusal_does_not_confirm_mutation(self):
        HARNESS._record_attempt("dcs")
        HARNESS._record_outcome("dcs", {}, True, {
            "success": False,
            "error": "validation refused before commit",
        })

        self.assertEqual(0, HARNESS._MUTATIONS_UNRESOLVED)
        self.assertFalse(HARNESS._MUTATION_CONFIRMED)
        self.assertFalse(HARNESS._model_may_have_moved())

    def test_unknown_mutation_outcome_forfeits_the_shortcut_without_a_phrase(self):
        HARNESS._record_attempt("apply_quick_fix")
        HARNESS._record_outcome("apply_quick_fix", {}, True, {
            "success": False,
            "error": "opaque provider failed",
            "mutationOutcomeUnknown": True,
        })

        self.assertEqual(0, HARNESS._MUTATIONS_UNRESOLVED)
        self.assertTrue(HARNESS._MUTATION_CONFIRMED)
        self.assertTrue(HARNESS._model_may_have_moved())

    def test_kind_ratchet_flags_confirmed_dirtying_mutation_outside_write_metadata(self):
        HARNESS._record_attempt("create_metadata")
        HARNESS._record_outcome("create_metadata", {}, False, {"success": True})

        violations = HARNESS.mutation_kind_violation_tools(
            "action", HARNESS.confirmed_mutation_tools())

        self.assertEqual(("create_metadata",), violations)

    def test_kind_ratchet_allows_confirmed_mutation_for_write_metadata(self):
        HARNESS._record_attempt("modify_metadata")
        HARNESS._record_outcome("modify_metadata", {}, False, {"success": True})

        violations = HARNESS.mutation_kind_violation_tools(
            "write-metadata", HARNESS.confirmed_mutation_tools())

        self.assertEqual((), violations)

    def test_kind_ratchet_ignores_successful_clean_project_for_action(self):
        """clean_project restores the model FROM disk, so it never enters the set at all."""
        HARNESS._record_attempt("clean_project")
        HARNESS._record_outcome("clean_project", {}, False, {"success": True})

        self.assertEqual(frozenset(), HARNESS.confirmed_mutation_tools())
        self.assertEqual((), HARNESS.mutation_kind_violation_tools(
            "action", HARNESS.confirmed_mutation_tools()))

    # The two tools whose ORDINARY mode moves nothing and whose opt-in mode moves the model.
    # A tool-wide exemption was wrong in both directions; these pin the per-call rule.

    def test_resync_to_disk_is_a_writer_only_when_asked_to_clean_dangling_references(self):
        HARNESS._record_attempt("resync_to_disk")
        HARNESS._record_outcome("resync_to_disk", {"projectName": "P"}, False, {"success": True})
        self.assertEqual(frozenset(), HARNESS.confirmed_mutation_tools())

        HARNESS.begin_test_calls()
        HARNESS._record_attempt("resync_to_disk")
        HARNESS._record_outcome("resync_to_disk", {"cleanDanglingReferences": True}, False,
                                {"success": True})
        self.assertEqual(frozenset({"resync_to_disk"}), HARNESS.confirmed_mutation_tools())

    def test_build_external_objects_stamps_the_model_unless_asked_not_to(self):
        # recordBuildTime defaults to TRUE in the tool, so an absent argument is a write.
        HARNESS._record_attempt("build_external_objects")
        HARNESS._record_outcome("build_external_objects", {"projectName": "P"}, False,
                                {"success": True})
        self.assertEqual(frozenset({"build_external_objects"}), HARNESS.confirmed_mutation_tools())

        HARNESS.begin_test_calls()
        HARNESS._record_attempt("build_external_objects")
        HARNESS._record_outcome("build_external_objects", {"recordBuildTime": False}, False,
                                {"success": True})
        self.assertEqual(frozenset(), HARNESS.confirmed_mutation_tools())

    def test_a_preview_is_never_a_mutation(self):
        """A dry run reports action=preview and applies nothing - true for rename and delete."""
        HARNESS._record_attempt("rename_metadata_object")
        HARNESS._record_outcome("rename_metadata_object", {"objectFqn": "CommonModule.Calc"},
                                False, {"success": True, "action": "preview"})

        self.assertEqual(frozenset(), HARNESS.confirmed_mutation_tools())
        self.assertEqual((), HARNESS.mutation_kind_violation_tools(
            "write", HARNESS.confirmed_mutation_tools()))

    def test_kind_ratchet_ignores_test_without_confirmed_mutation(self):
        HARNESS._record_attempt("create_metadata")
        HARNESS._record_outcome("create_metadata", {}, True, {
            "success": False,
            "error": "validation refused before commit",
        })

        self.assertEqual(frozenset(), HARNESS.confirmed_mutation_tools())
        self.assertEqual((), HARNESS.mutation_kind_violation_tools(
            "action", HARNESS.confirmed_mutation_tools()))


class FixtureResetTest(unittest.TestCase):
    # reset_all_fixtures is the revert callable INSIDE _revert_and_clean's retry loop, so the two
    # halves of its failure condition have to be pinned separately: a dirty tree alone is the race
    # that loop absorbs, a failed git command alone can be a `clean -fd` complaining about a file
    # the checkout already restored. Only both together mean the revert could not do its job.

    def _reset_all(self, failed_rels, dirty_rels):
        """Run reset_all_fixtures with the git layer stubbed to the given outcome."""
        return mock.patch.object(HARNESS, "_FIXTURES_FROZEN", False), \
            mock.patch.object(HARNESS, "_reset_rel",
                              side_effect=lambda rel: (["git checkout -> exit 1: locked index"]
                                                       if rel in failed_rels else [])), \
            mock.patch.object(HARNESS, "status_porcelain_rel",
                              side_effect=lambda rel: (" M %s/Configuration.mdo" % rel
                                                       if rel in dirty_rels else ""))

    def test_reset_all_fixtures_raises_when_a_failed_git_left_the_path_dirty(self):
        frozen, reset_rel, status = self._reset_all({HARNESS.TESTS_PROJECT_REL},
                                                    {HARNESS.TESTS_PROJECT_REL})
        with frozen, reset_rel as spy, status:
            with self.assertRaisesRegex(
                    HARNESS.E2EModelResetFailed, HARNESS.TESTS_PROJECT_REL):
                HARNESS.reset_all_fixtures()

        self.assertEqual([mock.call(rel) for rel in HARNESS.ALL_FIXTURE_RELS], spy.call_args_list)

    def test_a_dirty_path_alone_is_the_retryable_race_and_not_a_failure(self):
        frozen, reset_rel, status = self._reset_all(set(), set(HARNESS.ALL_FIXTURE_RELS))
        with frozen, reset_rel, status:
            self.assertTrue(HARNESS.reset_all_fixtures())

    def test_a_failed_git_that_left_the_path_clean_is_not_a_failure(self):
        frozen, reset_rel, status = self._reset_all(set(HARNESS.ALL_FIXTURE_RELS), set())
        with frozen, reset_rel, status:
            self.assertTrue(HARNESS.reset_all_fixtures())

    def test_reset_all_fixtures_still_returns_false_when_fixtures_are_frozen(self):
        with mock.patch.object(HARNESS, "_FIXTURES_FROZEN", True), \
                mock.patch.object(HARNESS, "_reset_rel") as reset_rel:
            self.assertFalse(HARNESS.reset_all_fixtures())

        reset_rel.assert_not_called()


if __name__ == "__main__":
    unittest.main()
