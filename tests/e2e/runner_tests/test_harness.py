"""Pure isolation-layer contracts; no EDT server or fixture mutation required."""

import contextlib
import glob
import importlib.util
import io
import os
import tempfile
import threading
import time
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
        self.old_cascade_confirmed_called = HARNESS._CASCADE_CONFIRMED_CALLED
        self.old_unresolved_cascade_calls = HARNESS._UNRESOLVED_CASCADE_CALLS
        self.old_mutated_projects = set(HARNESS._MUTATED_PROJECTS)
        self.old_evidenced_projects = set(HARNESS._EVIDENCED_MUTATION_PROJECTS)
        self.old_unresolved_projects = dict(HARNESS._UNRESOLVED_MUTATION_PROJECTS)
        HARNESS._MUTATIONS_UNRESOLVED = 0
        HARNESS._MUTATION_CONFIRMED = False
        HARNESS._CONFIRMED_MUTATION_TOOLS.clear()
        HARNESS._CALLED_TOOLS.clear()
        HARNESS._CASCADE_CONFIRMED_CALLED = False
        HARNESS._UNRESOLVED_CASCADE_CALLS = 0
        HARNESS._MUTATED_PROJECTS.clear()
        HARNESS._EVIDENCED_MUTATION_PROJECTS.clear()
        HARNESS._UNRESOLVED_MUTATION_PROJECTS.clear()

    def tearDown(self):
        HARNESS._MUTATIONS_UNRESOLVED = self.old_unresolved
        HARNESS._MUTATION_CONFIRMED = self.old_confirmed
        HARNESS._CONFIRMED_MUTATION_TOOLS.clear()
        HARNESS._CONFIRMED_MUTATION_TOOLS.update(self.old_confirmed_tools)
        HARNESS._CALLED_TOOLS.clear()
        HARNESS._CALLED_TOOLS.update(self.old_called)
        HARNESS._CASCADE_CONFIRMED_CALLED = self.old_cascade_confirmed_called
        HARNESS._UNRESOLVED_CASCADE_CALLS = self.old_unresolved_cascade_calls
        HARNESS._MUTATED_PROJECTS.clear()
        HARNESS._MUTATED_PROJECTS.update(self.old_mutated_projects)
        HARNESS._EVIDENCED_MUTATION_PROJECTS.clear()
        HARNESS._EVIDENCED_MUTATION_PROJECTS.update(self.old_evidenced_projects)
        HARNESS._UNRESOLVED_MUTATION_PROJECTS.clear()
        HARNESS._UNRESOLVED_MUTATION_PROJECTS.update(self.old_unresolved_projects)

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

    def test_adoption_attempt_tracks_base_and_extension_projects(self):
        HARNESS._record_attempt("adopt_metadata_object", {
            "projectName": HARNESS.PROJECT,
            "extensionProjectName": HARNESS.TESTS_PROJECT,
        })

        self.assertEqual(
            frozenset({HARNESS.PROJECT, HARNESS.TESTS_PROJECT}),
            HARNESS.mutated_fixture_projects())

    def test_rename_attempt_marks_possible_cascade_but_modify_does_not(self):
        args = {
            "projectName": HARNESS.PROJECT,
            "confirm": True,
        }
        HARNESS._record_attempt("rename_metadata_object", args)
        HARNESS._record_outcome("rename_metadata_object", args, False, None)

        self.assertTrue(HARNESS.mutation_could_have_cascaded())

        HARNESS.begin_test_calls()
        HARNESS._record_attempt("modify_metadata", {
            "projectName": HARNESS.PROJECT,
            "confirm": True,
        })

        self.assertFalse(HARNESS.mutation_could_have_cascaded())

    def test_rename_cascade_tracking_handles_preview_and_string_confirm_arguments(self):
        preview_args = (
            {"projectName": HARNESS.PROJECT},
            {"projectName": HARNESS.PROJECT, "confirm": False},
            {"projectName": HARNESS.PROJECT, "confirm": "false"},
        )

        for args in preview_args:
            with self.subTest(args=args):
                HARNESS.begin_test_calls()
                HARNESS._record_attempt("rename_metadata_object", args)
                self.assertFalse(HARNESS.mutation_could_have_cascaded())

        HARNESS.begin_test_calls()
        args = {
            "projectName": HARNESS.PROJECT,
            "confirm": "true",
        }
        HARNESS._record_attempt("rename_metadata_object", args)
        HARNESS._record_outcome("rename_metadata_object", args, False, None)

        self.assertTrue(HARNESS.mutation_could_have_cascaded())

    def test_delete_cascade_tracking_requires_confirmation(self):
        args = {
            "projectName": HARNESS.PROJECT,
            "confirm": True,
        }
        HARNESS._record_attempt("delete_metadata", args)
        HARNESS._record_outcome("delete_metadata", args, False, None)

        self.assertTrue(HARNESS.mutation_could_have_cascaded())

        HARNESS.begin_test_calls()
        HARNESS._record_attempt(
            "delete_metadata", {"projectName": HARNESS.PROJECT})

        self.assertFalse(HARNESS.mutation_could_have_cascaded())

    def test_a_refused_confirmed_rename_does_not_claim_a_cascade(self):
        args = {"projectName": HARNESS.PROJECT, "confirm": True}
        HARNESS._record_attempt("rename_metadata_object", args)
        HARNESS._record_outcome(
            "rename_metadata_object", args, True, {"success": False})

        self.assertFalse(HARNESS.mutation_could_have_cascaded())

    def test_a_successful_confirmed_rename_claims_a_cascade_without_structured_data(self):
        args = {"projectName": HARNESS.PROJECT, "confirm": True}
        HARNESS._record_attempt("rename_metadata_object", args)
        # A Markdown-only success has no structuredContent payload.
        HARNESS._record_outcome("rename_metadata_object", args, False, None)

        self.assertTrue(HARNESS.mutation_could_have_cascaded())

    def test_confirmation_follows_the_servers_boolean_parser(self):
        cases = (
            (True, True),
            ("true", True),
            (" TRUE ", True),
            ("1", True),
            ("yes", True),
            ("YES", True),
            (1, True),
            (False, False),
            ("false", False),
            ("0", False),
            ("no", False),
            ("", False),
            (0, False),
            (2, False),
            (1.0, False),
            ([], False),
            ({}, False),
            ("y", False),
            ("on", False),
        )

        for value, expected in cases:
            with self.subTest(confirm=value):
                HARNESS.begin_test_calls()
                args = {"projectName": HARNESS.PROJECT, "confirm": value}
                HARNESS._record_attempt("rename_metadata_object", args)
                HARNESS._record_outcome("rename_metadata_object", args, False, None)
                self.assertEqual(
                    expected, HARNESS.mutation_could_have_cascaded())

        self.assertTrue(HARNESS._confirmed(None))

    def test_a_numeric_confirm_runs_a_preview_and_does_not_widen(self):
        args = {
            "projectName": HARNESS.PROJECT,
            "fqn": "Catalog.C",
            "newName": "D",
            "confirm": 0,
        }
        HARNESS._record_attempt("rename_metadata_object", args)
        HARNESS._record_outcome("rename_metadata_object", args, False, None)

        self.assertFalse(HARNESS.mutation_could_have_cascaded())

    def test_a_committed_delete_error_claims_a_cascade(self):
        args = {"projectName": HARNESS.PROJECT, "confirm": True}
        HARNESS._record_attempt("delete_metadata", args)
        HARNESS._record_outcome(
            "delete_metadata", args, True,
            {"success": False, "mutationCommitted": True})

        self.assertTrue(HARNESS.mutation_could_have_cascaded())

    def test_an_unresolved_confirmed_cascade_survives_the_test_boundary(self):
        HARNESS._record_attempt("rename_metadata_object", {
            "projectName": HARNESS.PROJECT,
            "confirm": True,
        })

        self.assertTrue(HARNESS.mutation_could_have_cascaded())
        HARNESS.begin_test_calls()
        self.assertTrue(HARNESS.mutation_could_have_cascaded())

    def test_a_delete_outside_the_base_does_not_widen_but_evidences_its_target(self):
        args = {
            "projectName": HARNESS.EXT_OBJECTS_PROJECT,
            "fqn": "ExternalReport.R.Form.F.Attribute.A",
            "confirm": True,
        }
        HARNESS._record_attempt("delete_metadata", args)
        HARNESS._record_outcome(
            "delete_metadata", args, False, {"action": "executed"})

        self.assertFalse(HARNESS.mutation_could_have_cascaded())
        self.assertIn(
            HARNESS.EXT_OBJECTS_PROJECT,
            HARNESS.evidenced_mutation_fixture_projects(),
        )

    def test_a_confirmed_delete_in_the_base_still_widens(self):
        args = {
            "projectName": HARNESS.PROJECT,
            "fqn": "Catalog.C",
            "confirm": True,
        }
        HARNESS._record_attempt("delete_metadata", args)
        HARNESS._record_outcome(
            "delete_metadata", args, False, {"action": "executed"})

        self.assertTrue(HARNESS.mutation_could_have_cascaded())

    def test_a_cascade_call_naming_no_fixture_stays_wide(self):
        args = {
            "projectName": "SomethingElse",
            "fqn": "Catalog.C",
            "confirm": True,
        }
        HARNESS._record_attempt("rename_metadata_object", args)
        HARNESS._record_outcome("rename_metadata_object", args, False, None)

        self.assertTrue(HARNESS.mutation_could_have_cascaded())

    def test_a_request_body_that_cannot_be_built_counts_no_attempt(self):
        class Unserializable:
            pass

        with self.assertRaises(TypeError):
            HARNESS.call("delete_metadata", {
                "projectName": HARNESS.PROJECT,
                "fqn": "Catalog.C",
                "confirm": True,
                "junk": Unserializable(),
            })

        self.assertFalse(HARNESS.mutations_unresolved())
        self.assertFalse(HARNESS.mutation_could_have_cascaded())
        self.assertEqual(frozenset(), HARNESS.mutated_fixture_projects())

    def test_adoption_without_extension_tracks_implicit_fixture_extension(self):
        HARNESS._record_attempt(
            "adopt_metadata_object", {"projectName": HARNESS.PROJECT})

        self.assertEqual(
            frozenset({HARNESS.PROJECT, HARNESS.TESTS_PROJECT}),
            HARNESS.mutated_fixture_projects())
        self.assertIn(
            HARNESS.TESTS_PROJECT,
            HARNESS.evidenced_mutation_fixture_projects(),
        )

    def test_adoption_with_empty_extension_tracks_implicit_fixture_extension(self):
        HARNESS._record_attempt("adopt_metadata_object", {
            "projectName": HARNESS.PROJECT,
            "extensionProjectName": "",
        })

        self.assertEqual(
            frozenset({HARNESS.PROJECT, HARNESS.TESTS_PROJECT}),
            HARNESS.mutated_fixture_projects())
        self.assertIn(
            HARNESS.TESTS_PROJECT,
            HARNESS.evidenced_mutation_fixture_projects(),
        )

    def test_adoption_with_non_string_extension_widens_to_implicit_extension(self):
        HARNESS._record_attempt("adopt_metadata_object", {
            "projectName": HARNESS.PROJECT,
            "extensionProjectName": 123,
        })

        self.assertEqual(
            frozenset({HARNESS.PROJECT, HARNESS.TESTS_PROJECT}),
            HARNESS.mutated_fixture_projects())

    def test_implicit_adoption_does_not_infer_extension_for_non_fixture_base(self):
        HARNESS._record_attempt(
            "adopt_metadata_object", {"projectName": "UnrelatedBase"})

        self.assertEqual(frozenset(), HARNESS.mutated_fixture_projects())

    def test_refused_implicit_adoption_retires_candidate_targets(self):
        args = {"projectName": HARNESS.PROJECT}
        HARNESS._record_attempt("adopt_metadata_object", args)
        self.assertEqual(
            frozenset({HARNESS.PROJECT, HARNESS.TESTS_PROJECT}),
            HARNESS.mutated_fixture_projects())

        HARNESS._record_outcome(
            "adopt_metadata_object", args, True, {"success": False})

        self.assertEqual(
            frozenset(), HARNESS.evidenced_mutation_fixture_projects())
        self.assertFalse(HARNESS.mutations_unresolved())

    def test_unknown_implicit_adoption_outcome_evidences_extension(self):
        args = {"projectName": HARNESS.PROJECT}
        HARNESS._record_attempt("adopt_metadata_object", args)
        HARNESS._record_outcome("adopt_metadata_object", args, True, {
            "success": False,
            "mutationOutcomeUnknown": True,
        })

        self.assertIn(
            HARNESS.TESTS_PROJECT,
            HARNESS.evidenced_mutation_fixture_projects(),
        )

    def test_explicit_non_fixture_adoption_extension_is_not_second_guessed(self):
        HARNESS._record_attempt("adopt_metadata_object", {
            "projectName": HARNESS.PROJECT,
            "extensionProjectName": "OtherExt",
        })

        self.assertEqual(
            frozenset({HARNESS.PROJECT}), HARNESS.mutated_fixture_projects())

    def test_written_projects_outcome_tracks_fixture_missing_from_arguments(self):
        HARNESS._record_outcome(
            "adopt_metadata_object",
            {"projectName": HARNESS.PROJECT},
            False,
            {"writtenProjects": [HARNESS.TESTS_PROJECT]},
        )

        self.assertEqual(
            frozenset({HARNESS.TESTS_PROJECT}), HARNESS.mutated_fixture_projects())

    def test_invalid_written_projects_values_record_nothing_and_do_not_raise(self):
        responses = (
            {},
            {"writtenProjects": HARNESS.TESTS_PROJECT},
            {"writtenProjects": [None, 7, "UnrelatedProject"]},
        )

        for structured in responses:
            with self.subTest(structured=structured):
                HARNESS.begin_test_calls()
                HARNESS._record_outcome(
                    "adopt_metadata_object", {}, False, structured)
                self.assertEqual(frozenset(), HARNESS.mutated_fixture_projects())

    def test_refused_mutating_call_does_not_evidence_its_named_fixture_project(self):
        HARNESS.begin_test_calls()
        HARNESS._record_attempt(
            "modify_metadata", {"projectName": HARNESS.EXT_OBJECTS_PROJECT})
        HARNESS._record_outcome(
            "modify_metadata",
            {"projectName": HARNESS.EXT_OBJECTS_PROJECT},
            True,
            {"success": False, "error": "project not found"},
        )

        self.assertEqual(
            frozenset(), HARNESS.evidenced_mutation_fixture_projects())

    def test_successful_write_does_not_evidence_fixture_named_only_by_source(self):
        HARNESS._record_attempt("write_module_source", {
            "projectName": HARNESS.PROJECT,
            "source": HARNESS.EXT_OBJECTS_PROJECT,
        })
        HARNESS._record_outcome(
            "write_module_source",
            {
                "projectName": HARNESS.PROJECT,
                "source": HARNESS.EXT_OBJECTS_PROJECT,
            },
            False,
            {"success": True},
        )

        self.assertEqual(
            frozenset({HARNESS.PROJECT}),
            HARNESS.evidenced_mutation_fixture_projects(),
        )

    def test_unresolved_write_evidences_fixture_named_by_project_argument(self):
        HARNESS._record_attempt(
            "write_module_source", {"projectName": HARNESS.EXT_OBJECTS_PROJECT})

        self.assertEqual(
            frozenset({HARNESS.EXT_OBJECTS_PROJECT}),
            HARNESS.evidenced_mutation_fixture_projects(),
        )

    def test_each_mutation_outcome_signal_evidences_the_call_named_fixture_project(self):
        outcomes = (
            (False, {"success": True}),
            (True, {"success": False, "mutationCommitted": True}),
            (True, {"success": False, "mutationOutcomeUnknown": True}),
            (True, {"success": False, "writtenProjects": [HARNESS.PROJECT]}),
        )

        for is_error, structured in outcomes:
            with self.subTest(is_error=is_error, structured=structured):
                HARNESS.begin_test_calls()
                HARNESS._record_attempt(
                    "modify_metadata", {"projectName": HARNESS.EXT_OBJECTS_PROJECT})
                HARNESS._record_outcome(
                    "modify_metadata",
                    {"projectName": HARNESS.EXT_OBJECTS_PROJECT},
                    is_error,
                    structured,
                )

                self.assertIn(
                    HARNESS.EXT_OBJECTS_PROJECT,
                    HARNESS.evidenced_mutation_fixture_projects(),
                )

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

    def test_final_cleanup_synchronizes_external_objects_on_the_happy_path(self):
        synced = (True, 1, 0, None)
        with mock.patch.object(HARNESS, "reset_all_fixtures") as reset_all, \
                mock.patch.object(HARNESS, "_revert_and_clean", return_value=synced) as clean, \
                mock.patch.object(HARNESS, "wait_for_project_ready", return_value=True):
            HARNESS.final_cleanup()

        self.assertEqual([
            mock.call(HARNESS.PROJECT, reset_all,
                      ignore_projects={HARNESS.EXT_OBJECTS_PROJECT}),
            mock.call(HARNESS.TESTS_PROJECT, reset_all,
                      ignore_projects={HARNESS.EXT_OBJECTS_PROJECT}),
            mock.call(HARNESS.EXT_OBJECTS_PROJECT, reset_all),
        ], clean.call_args_list)

    def test_final_cleanup_does_not_raise_when_external_objects_sync_fails(self):
        def clean_result(project, _revert, ignore_projects=()):
            if project == HARNESS.EXT_OBJECTS_PROJECT:
                raise RuntimeError("fixture is not loaded")
            return (True, 1, 0, None)

        with mock.patch.object(HARNESS, "reset_all_fixtures") as reset_all, \
                mock.patch.object(HARNESS, "_revert_and_clean", side_effect=clean_result) as clean, \
                mock.patch.object(HARNESS, "wait_for_project_ready", return_value=True), \
                mock.patch("builtins.print") as output:
            HARNESS.final_cleanup()

        self.assertEqual([
            mock.call(HARNESS.PROJECT, reset_all,
                      ignore_projects={HARNESS.EXT_OBJECTS_PROJECT}),
            mock.call(HARNESS.TESTS_PROJECT, reset_all,
                      ignore_projects={HARNESS.EXT_OBJECTS_PROJECT}),
            mock.call(HARNESS.EXT_OBJECTS_PROJECT, reset_all),
        ], clean.call_args_list)
        self.assertIn("skipped", output.call_args.args[0].lower())
        self.assertIn("fixture is not loaded", output.call_args.args[0])

    def test_external_objects_model_synced_reports_what_final_cleanup_recorded(self):
        for external_synced in (True, False):
            with self.subTest(external_synced=external_synced):
                def clean_result(project, _revert, ignore_projects=()):
                    return (project != HARNESS.EXT_OBJECTS_PROJECT or external_synced,
                            1, 0, None)

                with mock.patch.object(HARNESS, "reset_all_fixtures"), \
                        mock.patch.object(HARNESS, "_revert_and_clean",
                                          side_effect=clean_result), \
                        mock.patch.object(HARNESS, "wait_for_project_ready", return_value=True), \
                        mock.patch("builtins.print"):
                    HARNESS.final_cleanup()

                self.assertEqual(
                    external_synced, HARNESS.external_objects_model_synced())

    def test_an_external_objects_timeout_is_not_absorbed_by_the_optional_attempt(self):
        """"Optional" means its model may be absent, not that the server may be unreachable.

        A timeout arms the global latch and may leave the request running server-side, so
        swallowing it here would carry the whole run on a latched harness and pin the failure on
        whichever test trips over it next - the same reason the baseline capture re-raises it.
        """
        def clean_result(project, _revert, ignore_projects=()):
            if project == HARNESS.EXT_OBJECTS_PROJECT:
                raise HARNESS.E2ECallTimeout("clean_project timed out")
            return (True, 1, 0, None)

        with mock.patch.object(HARNESS, "reset_all_fixtures"), \
                mock.patch.object(HARNESS, "_revert_and_clean", side_effect=clean_result), \
                mock.patch.object(HARNESS, "wait_for_project_ready", return_value=True), \
                mock.patch("builtins.print"):
            with self.assertRaises(HARNESS.E2ECallTimeout):
                HARNESS.final_cleanup()

    def test_a_latched_optional_clean_failure_is_not_absorbed(self):
        def clean_result(project, _revert, ignore_projects=()):
            if project == HARNESS.EXT_OBJECTS_PROJECT:
                raise ConnectionResetError("connection reset during clean_project")
            return (True, 1, 0, None)

        with mock.patch.object(HARNESS, "reset_all_fixtures"), \
                mock.patch.object(HARNESS, "_revert_and_clean", side_effect=clean_result), \
                mock.patch.object(HARNESS, "wait_for_project_ready", return_value=True), \
                mock.patch.object(HARNESS, "_TIMED_OUT", True), \
                mock.patch("builtins.print"):
            with self.assertRaises(ConnectionResetError):
                HARNESS.final_cleanup()

    def test_a_building_optional_project_does_not_abort_the_mandatory_cleanup(self):
        projects = """\
| Name | State | Kind | Open | EDT Project |
| --- | --- | --- | --- | --- |
| %s | ready | Configuration | Yes | Yes |
| %s | ready | Extension | Yes | Yes |
| %s | building | External objects | Yes | Yes |
""" % (HARNESS.PROJECT, HARNESS.TESTS_PROJECT, HARNESS.EXT_OBJECTS_PROJECT)

        def ready(timeout=None, failure_details=None, progress=None, ignore_projects=()):
            blocking = []
            is_ready = HARNESS._all_edt_projects_ready(
                projects, not_ready=blocking, ignore=ignore_projects)
            if not is_ready and failure_details is not None:
                failure_details[:] = [
                    HARNESS._projects_not_ready_message(timeout, blocking)]
            return is_ready

        successful = HARNESS.Result({"result": {"isError": False}})
        with mock.patch.object(HARNESS, "reset_all_fixtures") as reset_all, \
                mock.patch.object(HARNESS, "wait_for_project_ready", side_effect=ready), \
                mock.patch.object(HARNESS, "call", return_value=successful) as call, \
                mock.patch("builtins.print"):
            HARNESS.final_cleanup()

        self.assertEqual([
            mock.call("clean_project", {"projectName": HARNESS.PROJECT}),
            mock.call("clean_project", {"projectName": HARNESS.TESTS_PROJECT}),
        ], call.call_args_list)
        self.assertEqual(4, reset_all.call_count)

    def test_baseline_skips_external_objects_after_its_sync_failed(self):
        def clean_result(project, _revert, ignore_projects=()):
            return (project != HARNESS.EXT_OBJECTS_PROJECT, 1, 0, None)

        inventories = {
            HARNESS.PROJECT: "base inventory",
            HARNESS.TESTS_PROJECT: "tests inventory",
            HARNESS.EXT_OBJECTS_PROJECT: "stale external inventory",
        }

        with mock.patch.object(HARNESS, "reset_all_fixtures"), \
                mock.patch.object(HARNESS, "_revert_and_clean", side_effect=clean_result), \
                mock.patch.object(HARNESS, "wait_for_project_ready", return_value=True), \
                mock.patch("builtins.print"), \
                mock.patch.object(HARNESS, "_top_object_inventory",
                                  side_effect=lambda project=HARNESS.PROJECT: inventories[project]), \
                mock.patch.object(HARNESS, "_probe_details", return_value="base details"), \
                mock.patch.object(HARNESS, "_BASELINE_INVENTORY", None), \
                mock.patch.object(HARNESS, "_BASELINE_DETAILS", None), \
                mock.patch.dict(HARNESS._BASELINE_INVENTORY_BY_PROJECT, {}, clear=True), \
                mock.patch.dict(HARNESS._BASELINE_DETAILS_BY_PROJECT, {}, clear=True):
            HARNESS.final_cleanup()
            HARNESS.snapshot_model_baseline()
            captured = dict(HARNESS._BASELINE_INVENTORY_BY_PROJECT)
            captured_details = dict(HARNESS._BASELINE_DETAILS_BY_PROJECT)

        self.assertEqual({
            HARNESS.PROJECT: "base inventory",
            HARNESS.TESTS_PROJECT: "tests inventory",
        }, captured)
        self.assertEqual({
            HARNESS.PROJECT: "base details",
            HARNESS.TESTS_PROJECT: "base details",
        }, captured_details)

    def test_baseline_records_external_objects_after_its_sync_succeeded(self):
        inventories = {
            HARNESS.PROJECT: "base inventory",
            HARNESS.TESTS_PROJECT: "tests inventory",
            HARNESS.EXT_OBJECTS_PROJECT: "external inventory",
        }

        with mock.patch.object(HARNESS, "reset_all_fixtures"), \
                mock.patch.object(HARNESS, "_revert_and_clean",
                                  return_value=(True, 1, 0, None)) as clean, \
                mock.patch.object(HARNESS, "wait_for_project_ready", return_value=True), \
                mock.patch.object(HARNESS, "_top_object_inventory",
                                  side_effect=lambda project=HARNESS.PROJECT: inventories[project]), \
                mock.patch.object(HARNESS, "_probe_details", return_value="base details"), \
                mock.patch.object(HARNESS, "_BASELINE_INVENTORY", None), \
                mock.patch.object(HARNESS, "_BASELINE_DETAILS", None), \
                mock.patch.dict(HARNESS._BASELINE_INVENTORY_BY_PROJECT, {}, clear=True), \
                mock.patch.dict(HARNESS._BASELINE_DETAILS_BY_PROJECT, {}, clear=True):
            HARNESS.final_cleanup()
            HARNESS.snapshot_model_baseline()
            captured = dict(HARNESS._BASELINE_INVENTORY_BY_PROJECT)
            captured_details = dict(HARNESS._BASELINE_DETAILS_BY_PROJECT)

        self.assertIn(mock.call(HARNESS.EXT_OBJECTS_PROJECT, mock.ANY), clean.call_args_list)
        self.assertEqual(inventories, captured)
        self.assertEqual({
            HARNESS.PROJECT: "base details",
            HARNESS.TESTS_PROJECT: "base details",
            HARNESS.EXT_OBJECTS_PROJECT: "base details",
        }, captured_details)

    def test_non_base_verify_fails_when_clean_disk_inventory_differs(self):
        with mock.patch.dict(
                HARNESS._BASELINE_INVENTORY_BY_PROJECT,
                {HARNESS.TESTS_PROJECT: "Catalog.Baseline"}, clear=True), \
                mock.patch.object(HARNESS, "_disk_mismatch", return_value=None), \
                mock.patch.object(
                    HARNESS, "_top_object_inventory",
                    return_value="Catalog.Mutated") as inventory:
            mismatch = HARNESS._non_base_mismatch(
                HARNESS.TESTS_PROJECT, HARNESS.TESTS_PROJECT_REL)

        self.assertIsNotNone(mismatch)
        inventory.assert_called_once_with(HARNESS.TESTS_PROJECT)

    def test_non_base_verify_passes_when_disk_and_inventory_match(self):
        baseline = "Catalog.Baseline"
        with mock.patch.dict(
                HARNESS._BASELINE_INVENTORY_BY_PROJECT,
                {HARNESS.TESTS_PROJECT: baseline}, clear=True), \
                mock.patch.dict(
                    HARNESS._BASELINE_DETAILS_BY_PROJECT,
                    {HARNESS.TESTS_PROJECT: "baseline details"}, clear=True), \
                mock.patch.object(HARNESS, "_disk_mismatch", return_value=None), \
                mock.patch.object(HARNESS, "_top_object_inventory", return_value=baseline), \
                mock.patch.object(HARNESS, "_probe_details",
                                  return_value="baseline details"):
            mismatch = HARNESS._non_base_mismatch(
                HARNESS.TESTS_PROJECT, HARNESS.TESTS_PROJECT_REL)

        self.assertIsNone(mismatch)

    def test_non_base_verify_fails_when_a_nested_detail_still_differs(self):
        baseline = "Catalog.Baseline"
        with mock.patch.dict(
                HARNESS._BASELINE_INVENTORY_BY_PROJECT,
                {HARNESS.TESTS_PROJECT: baseline}, clear=True), \
                mock.patch.dict(
                    HARNESS._BASELINE_DETAILS_BY_PROJECT,
                    {HARNESS.TESTS_PROJECT: "baseline details"}, clear=True), \
                mock.patch.object(HARNESS, "_disk_mismatch", return_value=None), \
                mock.patch.object(HARNESS, "_top_object_inventory", return_value=baseline), \
                mock.patch.object(HARNESS, "_probe_details",
                                  return_value="changed nested details") as details:
            mismatch = HARNESS._non_base_mismatch(
                HARNESS.TESTS_PROJECT, HARNESS.TESTS_PROJECT_REL)

        self.assertIsNotNone(mismatch)
        details.assert_called_once_with(
            HARNESS.TESTS_PROJECT, HARNESS.NON_BASE_PROBE_FQNS[HARNESS.TESTS_PROJECT])

    def test_non_base_verify_uses_the_detail_baseline_it_captured(self):
        with mock.patch.dict(HARNESS._BASELINE_INVENTORY_BY_PROJECT, {}, clear=True), \
                mock.patch.dict(
                    HARNESS._BASELINE_DETAILS_BY_PROJECT,
                    {HARNESS.TESTS_PROJECT: "baseline details"}, clear=True), \
                mock.patch.object(HARNESS, "_disk_mismatch", return_value=None), \
                mock.patch.object(HARNESS, "_top_object_inventory") as inventory, \
                mock.patch.object(HARNESS, "_probe_details",
                                  return_value="changed nested details") as details:
            mismatch = HARNESS._non_base_mismatch(
                HARNESS.TESTS_PROJECT, HARNESS.TESTS_PROJECT_REL)

        self.assertIsNotNone(mismatch)
        inventory.assert_not_called()
        details.assert_called_once_with(
            HARNESS.TESTS_PROJECT, HARNESS.NON_BASE_PROBE_FQNS[HARNESS.TESTS_PROJECT])

    def test_non_base_verify_degrades_to_clean_disk_without_inventory_baseline(self):
        with mock.patch.dict(HARNESS._BASELINE_INVENTORY_BY_PROJECT, {}, clear=True), \
                mock.patch.dict(HARNESS._BASELINE_DETAILS_BY_PROJECT, {}, clear=True), \
                mock.patch.object(HARNESS, "_disk_mismatch", return_value=None), \
                mock.patch.object(HARNESS, "_top_object_inventory") as inventory, \
                mock.patch.object(HARNESS, "_probe_details") as details:
            mismatch = HARNESS._non_base_mismatch(
                HARNESS.TESTS_PROJECT, HARNESS.TESTS_PROJECT_REL)

        self.assertIsNone(mismatch)
        inventory.assert_not_called()
        details.assert_not_called()


class SettleProgressNoteTest(unittest.TestCase):
    """The note REPORTS what a failed settle saw; it must never become a decision.

    list_projects answers a coarse categorical state, so an unchanged snapshot cannot tell a
    stalled queue from a slow one. An earlier revision shortened the retries on exactly that
    signal, which would have failed slow-but-healthy runs.
    """

    def test_stalled_snapshot_names_the_polls_and_seconds_and_owns_its_ambiguity(self):
        note = HARNESS._settle_progress_note({
            "changed": False,
            "observed": [[("TestConfiguration", "building")]],
            "polls": 287,
            "elapsed": 600,
        })

        self.assertEqual(
            "project state never changed in 287 polls over 600s (a coarse state, so this does "
            "not by itself distinguish a stalled queue from a slow one)", note)

    def test_observed_change_is_reported_as_such(self):
        note = HARNESS._settle_progress_note({
            "changed": True,
            "observed": [
                [("TestConfiguration", "building")],
                [("TestConfiguration", "not_available")],
            ],
            "polls": 287,
            "elapsed": 600,
        })

        self.assertEqual("project state changed during the wait (287 polls over 600s)", note)

    def test_all_failed_polls_are_reported_as_unreadable_not_unchanged(self):
        note = HARNESS._settle_progress_note({
            "changed": False, "observed": [], "polls": 6, "elapsed": 12,
        })

        self.assertEqual("project state could not be read at all in 6 polls over 12s", note)
        self.assertNotIn("never changed", note)

    def test_missing_progress_keys_do_not_raise(self):
        self.assertIn("0 polls", HARNESS._settle_progress_note({}))


class ResetSettleEvidenceTest(unittest.TestCase):
    @staticmethod
    def _failed_wait(snapshot):
        def wait(*, timeout, failure_details, progress=None, ignore_projects=()):
            failure_details[:] = ["projects not ready after %ds: P=building" % timeout]
            progress.update({"last_list_projects": snapshot})
            return False
        return wait

    def test_reset_model_final_settle_collects_its_last_snapshot_before_raising(self):
        snapshot = "| P | building | reset final settle |"
        collected = []
        with mock.patch.object(HARNESS, "_revert_and_clean",
                               return_value=(True, 1, 0, None)), \
                mock.patch.object(HARNESS, "wait_for_project_ready",
                                  side_effect=self._failed_wait(snapshot)), \
                mock.patch.object(HARNESS, "_failed_settle_evidence",
                                  side_effect=collected.append):
            with self.assertRaises(HARNESS.E2EModelResetFailed):
                HARNESS.reset_model()

        self.assertEqual([snapshot], collected,
                         "the final snapshot must be handed off before reset_model raises")

    def test_final_cleanup_final_settle_collects_its_last_snapshot_before_raising(self):
        snapshot = "| P | building | cleanup final settle |"
        collected = []
        with mock.patch.object(HARNESS, "reset_all_fixtures"), \
                mock.patch.object(HARNESS, "_revert_and_clean",
                                  return_value=(True, 1, 0, None)), \
                mock.patch.object(HARNESS, "wait_for_project_ready",
                                  side_effect=self._failed_wait(snapshot)), \
                mock.patch.object(HARNESS, "_failed_settle_evidence",
                                  side_effect=collected.append):
            with self.assertRaises(HARNESS.E2EModelResetFailed):
                HARNESS.final_cleanup()

        self.assertEqual([snapshot], collected,
                         "the final snapshot must be handed off before final_cleanup raises")

    def test_reset_model_final_settle_failure_reports_its_progress(self):
        def failed_wait(*, timeout, failure_details, progress=None, ignore_projects=()):
            failure_details[:] = ["projects not ready after %ds: P=building" % timeout]
            progress.update({
                "changed": True,
                "observed": [[("P", "building")], [("P", "not_available")]],
                "polls": 17,
                "elapsed": 23,
                "last_list_projects": "| P | building | reset final settle |",
            })
            return False

        with mock.patch.object(HARNESS, "_revert_and_clean",
                               return_value=(True, 1, 0, None)), \
                mock.patch.object(HARNESS, "wait_for_project_ready",
                                  side_effect=failed_wait), \
                mock.patch.object(HARNESS, "_failed_settle_evidence"):
            with self.assertRaises(HARNESS.E2EModelResetFailed) as raised:
                HARNESS._reset_model_project("P", mock.Mock(), mock.Mock())

        self.assertIn(
            "project state changed during the wait (17 polls over 23s)",
            str(raised.exception))

    def test_final_cleanup_final_settle_failure_reports_its_progress(self):
        def failed_wait(*, timeout, failure_details, progress=None, ignore_projects=()):
            failure_details[:] = ["projects not ready after %ds: P=building" % timeout]
            progress.update({
                "changed": False,
                "observed": [[("P", "building")]],
                "polls": 19,
                "elapsed": 31,
                "last_list_projects": "| P | building | cleanup final settle |",
            })
            return False

        with mock.patch.object(HARNESS, "reset_all_fixtures"), \
                mock.patch.object(HARNESS, "_revert_and_clean",
                                  return_value=(True, 1, 0, None)), \
                mock.patch.object(HARNESS, "wait_for_project_ready",
                                  side_effect=failed_wait), \
                mock.patch.object(HARNESS, "_failed_settle_evidence"):
            with self.assertRaises(HARNESS.E2EModelResetFailed) as raised:
                HARNESS.final_cleanup()

        self.assertIn(
            "project state never changed in 19 polls over 31s (a coarse state, so this does not "
            "by itself distinguish a stalled queue from a slow one)",
            str(raised.exception))

    def test_a_settle_call_timeout_collects_the_last_completed_poll_before_reraising(self):
        snapshot = ("| Name | State | X | Open | EDT Project |\n"
                    "|---|---|---|---|---|\n"
                    "| P | building | - | Yes | Yes |\n")
        polls = iter((mock.Mock(text=snapshot),
                      HARNESS.E2ECallTimeout("list_projects timed out")))
        collected = []

        with mock.patch.object(HARNESS, "call", side_effect=polls), \
                mock.patch.object(HARNESS, "_failed_settle_evidence",
                                  side_effect=collected.append), \
                mock.patch.object(HARNESS.time, "sleep"):
            with self.assertRaises(HARNESS.E2ECallTimeout):
                HARNESS.wait_for_project_ready(timeout=HARNESS.MODEL_SETTLE_TIMEOUT)

        self.assertEqual([snapshot], collected,
                         "the timeout must preserve the last list_projects poll that completed")


class EvidenceLogTailTest(unittest.TestCase):
    """The evidence block must not be able to change the reset outcome, and that is EXECUTED here.

    A comment promising it has been wrong four times, in four different channels: an RPC arming the
    global latch, a second RPC inside the workspace locator, unbounded bytes, and unbounded time on
    a hung filesystem. The fix for the fourth was itself insufficient - a BOUNDED wait is still a
    wait, and the runner's per-test timeout is absolute, so any wait can be the one that overruns
    it and gets the worker abandoned. So the block now costs the caller no time at all, and that is
    what these tests pin.
    """

    def setUp(self):
        self.released = threading.Event()
        HARNESS._FAILED_SETTLE_EVIDENCE_THREAD = None

    def tearDown(self):
        # Let a blocked reader finish so no test leaves a thread mid-read.
        self.released.set()
        collector = HARNESS._FAILED_SETTLE_EVIDENCE_THREAD
        if collector is not None:
            collector.join(5)
        HARNESS._FAILED_SETTLE_EVIDENCE_THREAD = None
        HARNESS.__dict__.pop("open", None)

    def test_a_read_that_hangs_forever_costs_the_reset_no_time(self):
        released = self.released
        entered = threading.Event()

        def hanging_open(*_args, **_kwargs):
            entered.set()
            released.wait(30)
            raise AssertionError("the reader must have been left behind, not awaited")

        # Module-global 'open' shadows the builtin inside harness, so this reaches the real call.
        HARNESS.open = hanging_open

        # The workspace locator has to succeed, or the block never reaches the read and the test
        # would pass without exercising anything.
        with mock.patch.object(HARNESS, "_workspace_dir", return_value="any/workspace"):
            started = time.time()
            HARNESS._failed_settle_evidence("last list_projects body")
            elapsed = time.time() - started

            self.assertTrue(entered.wait(5), "the reader thread must actually have started")

        self.assertLess(elapsed, 1.0,
                        "collecting evidence must not spend the caller's budget at all")

    def test_the_block_still_prints_what_the_caller_already_held(self):
        """The synchronous half, so 'costs no time' did not become 'reports nothing'."""
        with tempfile.TemporaryDirectory() as tmp:
            log_dir = os.path.join(tmp, ".metadata")
            os.makedirs(log_dir)
            with open(os.path.join(log_dir, ".log"), "w", encoding="utf-8") as handle:
                handle.write("!ENTRY com.example 4 0\n!MESSAGE something went wrong\n")

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("FIRST FAILED SETTLE EVIDENCE", out)
        self.assertIn("| P | building |", out)
        self.assertIn("something went wrong", out)

    def test_the_tail_combines_the_newest_backup_and_current_log(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_dir = os.path.join(tmp, ".metadata")
            os.makedirs(log_dir)
            with open(os.path.join(log_dir, ".bak_1.log"), "w", encoding="utf-8") as handle:
                handle.write("failure before rotation\n")
            with open(os.path.join(log_dir, ".log"), "w", encoding="utf-8") as handle:
                handle.write("lines after rotation\n")

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("project state")

        out = printed.getvalue()
        self.assertIn("failure before rotation", out)
        self.assertIn("lines after rotation", out)
        # One section PER source: a shared budget could drop a whole file silently.
        self.assertIn("EDT log tail: .metadata/.bak_1.log", out)
        self.assertIn("EDT log tail: .metadata/.log", out)
        self.assertLess(out.index("failure before rotation"), out.index("lines after rotation"))
        self.assertNotIn("INCOMPLETE", out,
                         "an ordinary backup plus current log is complete evidence")

    def test_a_configured_workspace_without_a_current_log_keeps_rotated_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            with open(os.path.join(metadata, ".bak_1.log"), "w", encoding="utf-8") as handle:
                handle.write("FAILURE FROM ROTATED BACKUP\n")

            printed = io.StringIO()
            with mock.patch.dict(os.environ, {"EDT_MCP_EDT_WORKSPACE": tmp}), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("FAILURE FROM ROTATED BACKUP", out)
        self.assertNotIn("EDT workspace not found", out)
        self.assertIn("INCOMPLETE", out,
                      "the unreadable current log must leave the collected evidence partial")

    def test_a_burst_of_rotations_keeps_the_EARLIEST_one_the_failure_went_into(self):
        """The cap must spend its budget on the first rotation, not the last three.

        The failure is at or before the moment collection started, so among the backups created
        during collection it lives in the FIRST one - the file that was .log when the settle
        failed. Keeping the newest would discard precisely the file being looked for.
        """
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("FAILURE MOMENT\n")

            real_read = HARNESS._read_log_tail
            rotations = []

            def rotate_four_times_then_read(path, *args):
                if not rotations:
                    rotations.append(True)
                    for index in range(2, 6):
                        rotated_to = os.path.join(metadata, ".bak_%d.log" % index)
                        os.replace(current, rotated_to)
                        stamp = 2_000_000_000 + index
                        os.utime(rotated_to, (stamp, stamp))
                        with open(current, "w", encoding="utf-8") as handle:
                            handle.write("LATER WRITE %d\n" % index)
                return real_read(path, *args)

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail",
                                      side_effect=rotate_four_times_then_read), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("FAILURE MOMENT", out,
                      "the earliest rotation holds the failure and must survive the cap")
        self.assertIn(".bak_2.log", out)

    def test_appeared_backups_consuming_the_cap_mark_pre_existing_evidence_incomplete(self):
        """The cap can leave no room for a backup that predates the first snapshot."""
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            with open(os.path.join(metadata, ".bak_1.log"), "w", encoding="utf-8") as handle:
                handle.write("FAILURE ALREADY ROTATED\n")
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("POST-FAILURE CURRENT\n")

            real_read = HARNESS._read_log_tail
            rotated = []

            def rotate_through_the_cap_then_read(path, *args):
                if not rotated:
                    rotated.append(True)
                    for index in range(2, 2 + HARNESS._EVIDENCE_LOG_MAX_BACKUPS):
                        rotated_to = os.path.join(metadata, ".bak_%d.log" % index)
                        os.replace(current, rotated_to)
                        stamp = 2_000_000_000 + index
                        os.utime(rotated_to, (stamp, stamp))
                        with open(current, "w", encoding="utf-8") as handle:
                            handle.write("POST-FAILURE ROTATION %d\n" % index)
                return real_read(path, *args)

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail",
                                      side_effect=rotate_through_the_cap_then_read), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertNotIn("FAILURE ALREADY ROTATED", out,
                         "this scenario must exercise the pre-existing backup displaced by cap")
        self.assertIn("INCOMPLETE", out)
        self.assertIn(
            "backup cap of %d omitted 1 pre-existing backup for want of room"
            % HARNESS._EVIDENCE_LOG_MAX_BACKUPS,
            out)

    @staticmethod
    def _evidence_with_one_appeared_backup(pre_existing_count):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("CURRENT LINE\n")

            before = {}
            for index in range(1, pre_existing_count + 1):
                path = os.path.join(metadata, ".bak_%d.log" % index)
                with open(path, "w", encoding="utf-8") as handle:
                    handle.write("PRE-EXISTING BACKUP %d\n" % index)
                os.utime(path, (1_000_000_000 + index, 1_000_000_000 + index))
                st = os.stat(path)
                before[path] = (st.st_mtime_ns, st.st_size, getattr(st, "st_ino", 0))

            appeared = os.path.join(metadata, ".bak_appeared.log")
            with open(appeared, "w", encoding="utf-8") as handle:
                handle.write("APPEARED BACKUP\n")
            os.utime(appeared, (2_000_000_000, 2_000_000_000))
            st = os.stat(appeared)
            after = dict(before)
            after[appeared] = (st.st_mtime_ns, st.st_size, getattr(st, "st_ino", 0))

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_backup_identities",
                                      side_effect=((before, None), (after, None))), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        return printed.getvalue()

    def test_one_appeared_backup_omitting_pre_existing_backups_marks_incomplete(self):
        """A partly consumed cap must admit how many older candidates it dropped."""
        out = self._evidence_with_one_appeared_backup(4)

        self.assertIn("INCOMPLETE", out)
        self.assertIn(
            "backup cap of %d omitted 2 pre-existing backups for want of room"
            % HARNESS._EVIDENCE_LOG_MAX_BACKUPS,
            out)

    def test_pre_existing_backup_cap_boundary_only_marks_the_overflow_incomplete(self):
        """Every candidate fitting stays complete; one more makes the omission explicit."""
        fits = self._evidence_with_one_appeared_backup(2)
        overflows = self._evidence_with_one_appeared_backup(3)

        self.assertNotIn("INCOMPLETE", fits)
        self.assertIn("INCOMPLETE", overflows)
        self.assertIn(
            "backup cap of %d omitted 1 pre-existing backup for want of room"
            % HARNESS._EVIDENCE_LOG_MAX_BACKUPS,
            overflows)

    def test_more_appeared_backups_than_the_cap_with_one_mtime_mark_tie_incomplete(self):
        """Scandir order cannot decide which member of a capped timestamp tie is first."""
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("CURRENT LINE\n")

            stamp_ns = 2_000_000_000
            paths = []
            for index in range(1, HARNESS._EVIDENCE_LOG_MAX_BACKUPS + 2):
                path = os.path.join(metadata, ".bak_%d.log" % index)
                with open(path, "w", encoding="utf-8") as handle:
                    handle.write("BACKUP %d\n" % index)
                os.utime(path, ns=(stamp_ns, stamp_ns))
                paths.append(path)

            after = {}
            for path in paths:
                st = os.stat(path)
                after[path] = (st.st_mtime_ns, st.st_size, getattr(st, "st_ino", 0))
            tied_mtime = after[paths[0]][0]
            self.assertTrue(all(identity[0] == tied_mtime for identity in after.values()))

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_backup_identities",
                                      side_effect=(({}, None), (after, None))), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        tied_count = HARNESS._EVIDENCE_LOG_MAX_BACKUPS + 1
        self.assertIn("INCOMPLETE", out)
        self.assertIn(
            "backup mtime tie: %d appeared backups share timestamp %d, exceeding cap of %d; "
            "their order is not decidable"
            % (tied_count, tied_mtime, HARNESS._EVIDENCE_LOG_MAX_BACKUPS),
            out)

    def test_a_reused_backup_name_with_an_unchanged_timestamp_is_still_detected(self):
        """EDT reuses backup NAMES, so a rotation can overwrite one in place.

        If the replacement happens to carry the same coarse timestamp, an mtime-only comparison
        calls it unchanged and the rotation goes unseen. The identity carries size and inode too.
        """
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            reused = os.path.join(metadata, ".bak_1.log")
            stamp = 2_000_000_000
            with open(reused, "w", encoding="utf-8") as handle:
                handle.write("STALE\n")
            os.utime(reused, (stamp, stamp))
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("FAILURE MOMENT\n")

            real_read = HARNESS._read_log_tail
            rotations = []

            def rotate_in_place_then_read(path, *args):
                if not rotations:
                    rotations.append(True)
                    os.replace(current, reused)
                    os.utime(reused, (stamp, stamp))    # the timestamp is deliberately unchanged
                    with open(current, "w", encoding="utf-8") as handle:
                        handle.write("AFTER ROTATION\n")
                return real_read(path, *args)

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail",
                                      side_effect=rotate_in_place_then_read), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        self.assertIn("FAILURE MOMENT", printed.getvalue(),
                      "a same-name, same-mtime replacement must still register as a rotation")

    def test_an_in_place_rotation_marks_the_overwritten_generation_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            reused = os.path.join(metadata, ".bak_1.log")
            stamp = 2_000_000_000
            with open(reused, "w", encoding="utf-8") as handle:
                handle.write("STALE GENERATION\n")
            os.utime(reused, (stamp, stamp))
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("FAILURE MOMENT\n")

            real_read = HARNESS._read_log_tail
            rotations = []

            def rotate_in_place_then_read(path, *args):
                if not rotations:
                    rotations.append(True)
                    os.replace(current, reused)
                    os.utime(reused, (stamp, stamp))
                    with open(current, "w", encoding="utf-8") as handle:
                        handle.write("AFTER ROTATION\n")
                return real_read(path, *args)

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail",
                                      side_effect=rotate_in_place_then_read), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("FAILURE MOMENT", out)
        self.assertIn("INCOMPLETE", out)
        self.assertIn(
            "1 pre-existing backup overwritten in place during collection, so its earlier "
            "contents are gone: .bak_1.log",
            out)

    def test_overwritten_and_cap_omitted_backups_report_both_losses(self):
        reused = os.path.join("metadata", ".bak_1.log")
        omitted = os.path.join("metadata", ".bak_9.log")
        appeared_2 = os.path.join("metadata", ".bak_2.log")
        appeared_3 = os.path.join("metadata", ".bak_3.log")
        before = {
            reused: (1_000, 10, 11),
            omitted: (500, 10, 19),
        }
        after = {
            reused: (2_000, 99, 77),
            appeared_2: (2_001, 5, 21),
            appeared_3: (2_002, 5, 22),
            omitted: (500, 10, 19),
        }
        failures = []

        chosen = HARNESS._backups_covering(before, after, failures)

        self.assertEqual([reused, appeared_2, appeared_3], chosen)
        self.assertEqual([
            "1 pre-existing backup overwritten in place during collection, so its earlier "
            "contents are gone: .bak_1.log",
            "backup cap of %d omitted 1 pre-existing backup for want of room"
            % HARNESS._EVIDENCE_LOG_MAX_BACKUPS,
        ], failures)

    def test_a_selected_backup_replaced_before_read_marks_its_path_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            backup = os.path.join(metadata, ".bak_1.log")
            replacement = os.path.join(metadata, "replacement.log")
            current = os.path.join(metadata, ".log")
            with open(backup, "w", encoding="utf-8") as handle:
                handle.write("FAILURE IN SELECTED GENERATION\n")
            with open(replacement, "w", encoding="utf-8") as handle:
                handle.write("REPLACEMENT GENERATION\n")
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("CURRENT LINE\n")

            real_read = HARNESS._read_log_tail
            replaced = []

            def replace_selected_path_then_read(path, *args):
                if path == backup and not replaced:
                    replaced.append(True)
                    os.replace(replacement, backup)
                return real_read(path, *args)

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail",
                                      side_effect=replace_selected_path_then_read), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("REPLACEMENT GENERATION", out,
                      "the replacement must have been the generation actually opened")
        self.assertNotIn("FAILURE IN SELECTED GENERATION", out)
        self.assertIn("INCOMPLETE", out)
        self.assertIn("selected backup identity changed at read time: %s" % backup, out)

    def test_a_tail_missing_one_source_says_so_instead_of_looking_complete(self):
        """An unread backup may be the file that held the failure; silence would overclaim."""
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            backup = os.path.join(metadata, ".bak_1.log")
            current = os.path.join(metadata, ".log")
            with open(backup, "w", encoding="utf-8") as handle:
                handle.write("BACKUP LINE\n")
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("CURRENT LINE\n")

            real_read = HARNESS._read_log_tail

            def fail_on_the_backup(path, *args):
                if path.endswith(".bak_1.log"):
                    raise OSError("vanished")
                return real_read(path, *args)

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail", side_effect=fail_on_the_backup), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("CURRENT LINE", out, "what could be read must still be reported")
        self.assertIn("INCOMPLETE", out)
        self.assertIn(".bak_1.log", out, "the unread source must be named")

    def test_a_failed_backup_scan_marks_the_tail_incomplete_and_names_the_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            with open(os.path.join(metadata, ".log"), "w", encoding="utf-8") as handle:
                handle.write("CURRENT LINE\n")

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(
                        HARNESS, "_backup_identities",
                        side_effect=PermissionError("backup directory denied")), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

            out = printed.getvalue()
            self.assertIn("CURRENT LINE", out,
                          "a failed scan must not hide the readable current log")
            self.assertIn("INCOMPLETE", out)
            self.assertIn("backup scan", out)
            self.assertIn("PermissionError", out)

    def test_permission_denied_while_enumerating_backups_marks_the_tail_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            with open(os.path.join(metadata, ".log"), "w", encoding="utf-8") as handle:
                handle.write("CURRENT LINE\n")

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(
                        HARNESS.os, "scandir",
                        side_effect=PermissionError("backup directory enumeration denied")), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("CURRENT LINE", out,
                      "a failed enumeration must not hide the readable current log")
        self.assertIn("INCOMPLETE", out)
        self.assertIn("backup scan", out)
        self.assertIn("PermissionError", out)
        self.assertIn("backup directory enumeration denied", out)

    def test_permission_denied_while_stating_a_backup_marks_the_tail_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            backup = os.path.join(metadata, ".bak_1.log")
            with open(backup, "w", encoding="utf-8") as handle:
                handle.write("BACKUP LINE\n")
            with open(os.path.join(metadata, ".log"), "w", encoding="utf-8") as handle:
                handle.write("CURRENT LINE\n")

            real_stat = HARNESS.os.stat

            def deny_backup_stat(path, *args, **kwargs):
                if path == backup:
                    raise PermissionError("backup stat denied")
                return real_stat(path, *args, **kwargs)

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS.os, "stat", side_effect=deny_backup_stat), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("CURRENT LINE", out, "the readable current log must still be reported")
        self.assertIn("INCOMPLETE", out)
        self.assertIn("backup scan", out)
        self.assertIn("PermissionError", out)
        self.assertIn("backup stat denied", out)

    def test_a_genuinely_empty_backup_directory_does_not_mark_the_tail_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            with open(os.path.join(metadata, ".log"), "w", encoding="utf-8") as handle:
                handle.write("ONLY CURRENT LINE\n")

            scan_results = []
            real_scan = HARNESS._backup_identities

            def record_scan(path):
                result = real_scan(path)
                scan_results.append(result)
                return result

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_backup_identities", side_effect=record_scan), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        self.assertEqual([({}, None), ({}, None)], scan_results,
                         "an empty successful scan must be distinct from a failed scan")
        self.assertNotIn("INCOMPLETE", printed.getvalue())

    def test_backup_scan_selects_the_same_dot_prefixed_names_as_glob(self):
        with tempfile.TemporaryDirectory() as metadata:
            for name in (
                    ".bak_.log",
                    ".bak_1.log",
                    ".bak_descriptive.log",
                    ".BAK_PLATFORM_CASE.LOG",
                    "bak_1.log",
                    ".bak_1.txt",
                    ".bak_1.log.extra",
                    "prefix.bak_1.log"):
                with open(os.path.join(metadata, name), "w", encoding="utf-8") as handle:
                    handle.write(name)

            expected = set(glob.glob(os.path.join(metadata, ".bak_*.log")))
            identities, failure = HARNESS._backup_identities(metadata)

        self.assertIsNone(failure)
        self.assertEqual(expected, set(identities),
                         "the direct scan must preserve glob's platform matching semantics")
        self.assertIn(os.path.join(metadata, ".bak_1.log"), identities,
                      "directory enumeration must include the dot-prefixed backup name")

    def test_a_backup_named_directory_does_not_displace_a_real_log_from_the_cap(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            for name, body, mtime in (
                    (".bak_2.log", "FAILURE IN REAL BACKUP\n", 1_000_000_000),
                    (".bak_3.log", "LATER BACKUP THREE\n", 2_000_000_000),
                    (".bak_4.log", "LATER BACKUP FOUR\n", 3_000_000_000),
                    (".log", "CURRENT LOG\n", 5_000_000_000)):
                path = os.path.join(metadata, name)
                with open(path, "w", encoding="utf-8") as handle:
                    handle.write(body)
                os.utime(path, (mtime, mtime))

            backup_named_directory = os.path.join(metadata, ".bak_1.log")
            os.makedirs(backup_named_directory)
            os.utime(backup_named_directory, (4_000_000_000, 4_000_000_000))

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("FAILURE IN REAL BACKUP", out,
                      "a directory must not consume a place in the three-backup cap")
        self.assertNotIn("INCOMPLETE", out,
                         "a backup-shaped directory is not an unreadable log source")

    def test_backup_scan_admits_a_file_symlink_but_not_a_backup_named_directory(self):
        with tempfile.TemporaryDirectory() as metadata:
            real_log = os.path.join(metadata, "rotated-source")
            with open(real_log, "w", encoding="utf-8") as handle:
                handle.write("ROTATED THROUGH SYMLINK\n")

            symlink = os.path.join(metadata, ".bak_symlink.log")
            try:
                os.symlink(real_log, symlink)
            except (NotImplementedError, OSError) as exc:
                self.skipTest("file symlink creation is unavailable: %s" % exc)

            backup_named_directory = os.path.join(metadata, ".bak_directory.log")
            os.makedirs(backup_named_directory)

            identities, failure = HARNESS._backup_identities(metadata)
            target_stat = os.stat(real_log)

        self.assertIsNone(failure)
        self.assertEqual({symlink}, set(identities),
                         "the scan must follow a file symlink but reject a directory")
        self.assertEqual(
            (target_stat.st_mtime_ns, target_stat.st_size, getattr(target_stat, "st_ino", 0)),
            identities[symlink])

    def test_a_backup_vanishing_between_enumeration_and_stat_does_not_mark_the_tail_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            backup = os.path.join(metadata, ".bak_1.log")
            with open(backup, "w", encoding="utf-8") as handle:
                handle.write("ROTATED LINE\n")
            with open(os.path.join(metadata, ".log"), "w", encoding="utf-8") as handle:
                handle.write("CURRENT LINE\n")

            real_stat = HARNESS.os.stat

            def vanish_before_stat(path, *args, **kwargs):
                if path == backup:
                    raise FileNotFoundError("rotation removed the backup")
                return real_stat(path, *args, **kwargs)

            scan_results = []
            real_scan = HARNESS._backup_identities

            def record_scan(path):
                result = real_scan(path)
                scan_results.append(result)
                return result

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS.os, "stat", side_effect=vanish_before_stat), \
                    mock.patch.object(HARNESS, "_backup_identities", side_effect=record_scan), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        self.assertEqual([({}, None), ({}, None)], scan_results,
                         "a normal rotation race is still a successful scan")
        self.assertIn("CURRENT LINE", printed.getvalue())
        self.assertNotIn("INCOMPLETE", printed.getvalue())

    def test_two_rotations_in_a_row_do_not_push_the_failure_out_of_reach(self):
        """The case a single "newest backup" could not survive.

        The first rotation puts the failure in one backup; the second makes a DIFFERENT backup the
        newest. Picking one file collects the intermediate log and misses the failure entirely.
        Bracketing the current read with two directory snapshots makes both rotations observable,
        so both files are read.
        """
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            with open(os.path.join(metadata, ".bak_1.log"), "w", encoding="utf-8") as handle:
                handle.write("OLDEST BACKUP LINE\n")
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("FAILURE MOMENT\n")

            real_read = HARNESS._read_log_tail
            rotations = []

            def rotate_twice_then_read(path, *args):
                if not rotations:
                    rotations.append(True)
                    for backup_name, next_body, stamp in (
                            (".bak_2.log", "INTERMEDIATE\n", 2_000_000_000),
                            (".bak_3.log", "AFTER TWO ROTATIONS\n", 2_000_000_100)):
                        rotated_to = os.path.join(metadata, backup_name)
                        os.replace(current, rotated_to)
                        os.utime(rotated_to, (stamp, stamp))
                        with open(current, "w", encoding="utf-8") as handle:
                            handle.write(next_body)
                return real_read(path, *args)

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail",
                                      side_effect=rotate_twice_then_read), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("FAILURE MOMENT", out,
                      "the first rotation's backup must be read too, or two rotations lose it")
        self.assertIn(".bak_2.log", out)
        self.assertIn(".bak_3.log", out)

    def test_a_rotation_before_the_first_read_still_reaches_the_failure(self):
        """The other window: the backup is CHOSEN after the current file has been read.

        Ordering only the two reads is not enough. If the backup is picked first and EDT rotates
        before .log is read, the selection names the OLD backup while the failure moves into a new
        one that nothing reads - the reads were in the right order and the tail is still clean.
        Choosing after the read means the selection sees the post-rotation directory.
        """
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            older = os.path.join(metadata, ".bak_1.log")
            current = os.path.join(metadata, ".log")
            with open(older, "w", encoding="utf-8") as handle:
                handle.write("OLDER BACKUP LINE\n")
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("FAILURE MOMENT\n")

            real_read = HARNESS._read_log_tail
            rotated = []

            def rotate_then_read(path, *args):
                if not rotated:
                    # The writer rotates before the very first read gets its bytes.
                    rotated.append(True)
                    rotated_to = os.path.join(metadata, ".bak_2.log")
                    os.replace(current, rotated_to)
                    os.utime(rotated_to, (2_000_000_000, 2_000_000_000))
                    with open(current, "w", encoding="utf-8") as handle:
                        handle.write("AFTER ROTATION\n")
                return real_read(path, *args)

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail", side_effect=rotate_then_read), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("FAILURE MOMENT", out,
                      "the backup must be chosen after the current read, or the rotated-out "
                      "failure is never looked at")
        # The file the rotation CREATED has to be named, not merely happen to be included: that
        # is what proves the snapshot diff found it rather than the pre-existing backup being
        # picked up by luck. The pre-existing one is collected too, deliberately - it is where an
        # EARLIER rotation would have put a failure.
        self.assertIn(".bak_2.log", out)

    def test_a_rotated_current_log_is_not_charged_twice_against_the_line_budget(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("FAILURE MOMENT\n")
                handle.write("".join("noise line %d\n" % index for index in range(50)))

            real_read = HARNESS._read_log_tail
            rotated = []

            def read_then_rotate(path, *args):
                text = real_read(path, *args)
                if not rotated:
                    # The writer rotates the instant after the first read returns.
                    rotated.append(True)
                    os.replace(current, os.path.join(metadata, ".bak_2.log"))
                    with open(current, "w", encoding="utf-8") as handle:
                        handle.write("AFTER ROTATION\n")
                return text

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail", side_effect=read_then_rotate), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("FAILURE MOMENT", out,
                      "one generation that fits the budget must not be charged twice")
        headings = [line for line in out.splitlines() if line.startswith("--- EDT log tail:")]
        self.assertEqual(1, len(headings))
        self.assertIn(".metadata/.bak_2.log", headings[0])
        self.assertNotIn("INCOMPLETE", out)

    def test_a_grown_rotated_log_supersedes_the_bytes_read_from_current(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            backup = os.path.join(metadata, ".bak_2.log")
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("FAILURE MOMENT\n")
                handle.write("".join("noise line %d\n" % index for index in range(50)))

            real_read = HARNESS._read_log_tail
            rotated = []

            def read_grow_then_rotate(path, *args):
                text = real_read(path, *args)
                if not rotated:
                    rotated.append(True)
                    with open(current, "a", encoding="utf-8") as handle:
                        handle.write("APPENDED BEFORE ROTATION\n")
                    os.replace(current, backup)
                    with open(current, "w", encoding="utf-8") as handle:
                        handle.write("AFTER ROTATION\n")
                return text

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail",
                                      side_effect=read_grow_then_rotate), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        headings = [line for line in out.splitlines() if line.startswith("--- EDT log tail:")]
        self.assertEqual(1, len(headings))
        self.assertIn(".metadata/.bak_2.log", headings[0])
        self.assertIn("FAILURE MOMENT", out)
        self.assertIn("APPENDED BEFORE ROTATION", out)
        self.assertNotIn("INCOMPLETE", out)

    def test_a_reused_rotated_backup_keeps_the_current_bytes_and_marks_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            backup = os.path.join(metadata, ".bak_2.log")
            replacement = os.path.join(metadata, "replacement.log")
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("FAILURE MOMENT\n")
            with open(replacement, "w", encoding="utf-8") as handle:
                handle.write("REUSED BACKUP GENERATION\n")

            real_read = HARNESS._read_log_tail
            rotated = []
            reused = []

            def read_rotate_then_reuse(path, *args):
                if path == backup and not reused:
                    reused.append(True)
                    os.replace(replacement, backup)
                text = real_read(path, *args)
                if path == current and not rotated:
                    rotated.append(True)
                    os.replace(current, backup)
                    with open(current, "w", encoding="utf-8") as handle:
                        handle.write("AFTER ROTATION\n")
                return text

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail",
                                      side_effect=read_rotate_then_reuse), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        headings = [line for line in out.splitlines() if line.startswith("--- EDT log tail:")]
        self.assertEqual(2, len(headings))
        self.assertIn(".metadata/.bak_2.log", headings[0])
        self.assertIn(".metadata/.log", headings[1])
        backup_start = out.index(headings[0])
        current_start = out.index(headings[1])
        incomplete_start = out.index("--- EDT log tail - INCOMPLETE")
        self.assertIn("REUSED BACKUP GENERATION", out[backup_start:current_start])
        self.assertIn("FAILURE MOMENT", out[current_start:incomplete_start])
        self.assertIn("selected backup identity changed at read time: %s" % backup, out)

    def test_two_rotations_after_the_read_keep_the_backup_chronology(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            first_backup = os.path.join(metadata, ".bak_2.log")
            second_backup = os.path.join(metadata, ".bak_3.log")
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("FAILURE MOMENT\n")
            os.utime(current, ns=(1_000_000_000, 1_000_000_000))

            real_read = HARNESS._read_log_tail
            rotated = []

            def read_then_rotate_twice(path, *args):
                text = real_read(path, *args)
                if not rotated:
                    rotated.append(True)
                    os.replace(current, first_backup)
                    with open(current, "w", encoding="utf-8") as handle:
                        handle.write("NEWER GENERATION\n")
                    os.utime(current, ns=(2_000_000_000, 2_000_000_000))
                    os.replace(current, second_backup)
                    with open(current, "w", encoding="utf-8") as handle:
                        handle.write("AFTER TWO ROTATIONS\n")
                return text

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail",
                                      side_effect=read_then_rotate_twice), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        headings = [line for line in out.splitlines() if line.startswith("--- EDT log tail:")]
        self.assertEqual(2, len(headings))
        self.assertIn(".metadata/.bak_2.log", headings[0])
        self.assertIn(".metadata/.bak_3.log", headings[1])
        first_start = out.index(headings[0])
        second_start = out.index(headings[1])
        self.assertIn("FAILURE MOMENT", out[first_start:second_start])
        self.assertNotIn("INCOMPLETE", out)

    def test_a_backup_containing_all_held_current_bytes_replaces_the_stale_current_source(self):
        """A post-read rotation makes the captured .log bytes belong to the backup.

        Keeping the same stream under the now-new .log path would mislabel it and split the line
        budget away from the backup's newer suffix.
        """
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            backup = os.path.join(metadata, ".bak_2.log")
            identity = (1_000_000_000, 100, 42)
            current_text = "FAILURE MOMENT\nCURRENT TAIL\n"
            backup_text = current_text + "".join(
                "later backup line %d\n" % index
                for index in range(HARNESS._EVIDENCE_TAIL_LINES))

            def read_tail(path, capture_identity=False):
                text = current_text if path == current else backup_text
                return (text, identity) if capture_identity else text

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_backup_identities",
                                      side_effect=(({}, None), ({backup: identity}, None))), \
                    mock.patch.object(HARNESS, "_read_log_tail", side_effect=read_tail), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        headings = [line for line in out.splitlines() if line.startswith("--- EDT log tail:")]
        self.assertEqual(1, len(headings))
        self.assertIn(".metadata/.bak_2.log", headings[0])
        self.assertNotIn("EDT log tail: .metadata/.log", out)
        self.assertIn("later backup line 79", out)
        self.assertNotIn("INCOMPLETE", out)

    def test_a_rotation_that_only_duplicates_the_stream_does_not_change_what_is_displayed(self):
        """The 80-line budget drops old lines whether or not rotation happened.

        Dedupe must therefore make rotation invisible to the displayed stream, not evade the
        budget by charging one generation as two sources.
        """
        stream_lines = (["STREAM S01 OLD END"] +
                        ["STREAM S%02d" % index for index in range(2, 82)] +
                        ["STREAM S82 NEW END"])
        stream_text = "\n".join(stream_lines) + "\n"
        displayed_by_case = []
        tail_section_counts = []

        for current_line_count in (None, 2, 51, len(stream_lines)):
            with tempfile.TemporaryDirectory() as tmp:
                metadata = os.path.join(tmp, ".metadata")
                current = os.path.join(metadata, ".log")
                backup = os.path.join(metadata, ".bak_2.log")
                identity = (1_000_000_000, len(stream_text), 42)
                current_text = (stream_text if current_line_count is None else
                                "\n".join(stream_lines[:current_line_count]) + "\n")
                after = {} if current_line_count is None else {backup: identity}

                def read_tail(path, capture_identity=False):
                    text = current_text if path == current else stream_text
                    return (text, identity) if capture_identity else text

                printed = io.StringIO()
                with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                        mock.patch.object(HARNESS, "_backup_identities",
                                          side_effect=(({}, None), (after, None))), \
                        mock.patch.object(HARNESS, "_read_log_tail", side_effect=read_tail), \
                        contextlib.redirect_stdout(printed):
                    HARNESS._print_failed_settle_evidence("| P | building |")

            output_lines = printed.getvalue().splitlines()
            displayed_by_case.append(
                [line for line in output_lines if line.startswith("STREAM ")])
            tail_section_counts.append(sum(
                line.startswith("--- EDT log tail:") for line in output_lines))

        self.assertEqual(stream_lines[-HARNESS._EVIDENCE_TAIL_LINES:], displayed_by_case[0])
        self.assertEqual(displayed_by_case[0], displayed_by_case[1])
        self.assertEqual(displayed_by_case[0], displayed_by_case[2])
        self.assertEqual(displayed_by_case[0], displayed_by_case[3])
        self.assertEqual([1, 1, 1], tail_section_counts[1:])

    def test_current_text_inside_an_unchanged_backup_remains_live_evidence(self):
        """A pre-existing generation can match coincidentally, so it cannot replace `.log`."""
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            backup = os.path.join(metadata, ".bak_1.log")
            with open(backup, "w", encoding="utf-8") as handle:
                handle.write("".join("OLD B%02d\n" % index for index in range(1, 101)))
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("OLD B05\nOLD B06\n")

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        headings = [line for line in out.splitlines() if line.startswith("--- EDT log tail:")]
        self.assertEqual(2, len(headings))
        self.assertIn(".metadata/.bak_1.log", headings[0])
        self.assertIn(".metadata/.log", headings[1])
        backup_start = out.index(headings[0])
        current_start = out.index(headings[1])
        self.assertNotIn("OLD B05", out[backup_start:current_start])
        self.assertNotIn("OLD B06", out[backup_start:current_start])
        self.assertIn("OLD B05", out[current_start:])
        self.assertIn("OLD B06", out[current_start:])
        self.assertNotIn("INCOMPLETE", out)

    def test_a_backup_that_grew_after_current_was_read_keeps_the_failure_marker(self):
        """Keeping both copies splits the budget and hides L5 from both displayed tails."""
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            backup = os.path.join(metadata, ".bak_2.log")
            current_lines = ["L%d" % index for index in range(1, 52)]
            marker = "UNIQUE FAILURE MARKER AT L5"
            current_lines[4] = marker
            appended_lines = ["A%d" % index for index in range(1, 31)]
            with open(current, "w", encoding="utf-8") as handle:
                handle.write("\n".join(current_lines) + "\n")
            with open(backup, "w", encoding="utf-8") as handle:
                handle.write("\n".join(current_lines) + "\n")

            real_read = HARNESS._read_log_tail
            grew = []

            def read_then_grow_backup(path, *args):
                text = real_read(path, *args)
                if path == current and not grew:
                    grew.append(True)
                    with open(backup, "a", encoding="utf-8") as handle:
                        handle.write("\n".join(appended_lines) + "\n")
                return text

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail",
                                      side_effect=read_then_grow_backup), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn(marker, out)
        self.assertNotIn("EDT log tail: .metadata/.log", out)

    def test_a_backup_tail_that_does_not_cover_current_keeps_both_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            current = os.path.join(metadata, ".log")
            backup = os.path.join(metadata, ".bak_2.log")
            identity = (1_000_000_000, 100, 42)

            def read_tail(path, capture_identity=False):
                if path == current:
                    text = "FAILURE MOMENT\nCURRENT TAIL\n"
                else:
                    text = "BACKUP TAIL AFTER MANY APPENDS\n"
                return (text, identity) if capture_identity else text

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_backup_identities",
                                      return_value=({backup: identity}, None)), \
                    mock.patch.object(HARNESS, "_read_log_tail", side_effect=read_tail), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        headings = [line for line in out.splitlines() if line.startswith("--- EDT log tail:")]
        self.assertEqual(2, len(headings))
        self.assertIn(".metadata/.bak_2.log", headings[0])
        self.assertIn(".metadata/.log", headings[1])
        backup_start = out.index(headings[0])
        current_start = out.index(headings[1])
        self.assertIn("BACKUP TAIL AFTER MANY APPENDS", out[backup_start:current_start])
        self.assertIn("FAILURE MOMENT", out[current_start:])
        self.assertNotIn("INCOMPLETE", out)

    def test_a_noisy_current_log_cannot_crowd_the_rotated_failure_out(self):
        """The whole reason the backup is collected is that the failure is IN it.

        A single shared line budget looks equivalent and is not: concatenating the files and
        keeping the last 80 lines means a .log that has since written 80 lines of its own pushes
        every backup line out - the failure with them - while the heading still names the backup.
        Complete-looking evidence with the evidence removed.
        """
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            with open(os.path.join(metadata, ".bak_1.log"), "w", encoding="utf-8") as handle:
                handle.write("FAILURE MOMENT\n")
            with open(os.path.join(metadata, ".log"), "w", encoding="utf-8") as handle:
                handle.write("".join("noise line %d\n" % i for i in range(500)))

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("FAILURE MOMENT", out,
                      "a talkative current log must not evict the rotated-out failure")
        self.assertIn("noise line 499", out, "the current log's own tail is still reported")

    def test_the_byte_cap_is_passed_to_the_read_not_just_to_the_seek(self):
        """The seek bounds where the read STARTS; only read(N) bounds where it ends.

        EDT is appending while this runs, so a bare read() returns everything written between the
        tell() and the read - the cap that justifies calling this cheap would not apply at all.
        """
        recorded = []

        class RecordingHandle:
            def __enter__(self):
                return self

            def __exit__(self, *_exc):
                return False

            def seek(self, *_args):
                return 0

            def tell(self):
                return HARNESS._EVIDENCE_LOG_TAIL_BYTES * 4

            def read(self, *args):
                recorded.append(args)
                return b"tail\n"

        HARNESS.open = lambda *_args, **_kwargs: RecordingHandle()

        HARNESS._read_log_tail("any/path/.log")

        self.assertEqual([(HARNESS._EVIDENCE_LOG_TAIL_BYTES,)], recorded,
                         "the read must be given the byte cap, not called bare")

    def test_the_earliest_rotation_is_decided_by_time_not_by_size_or_inode(self):
        """The identity tuple DETECTS a replacement; it must not sequence one.

        Sorting by the whole tuple lets a smaller file or a lower inode pass for "earlier", and the
        earliest is exactly the one the cap keeps.
        """
        before = {}
        # Deliberately adversarial: the EARLIER file is the larger one with the higher inode, so a
        # tuple sort would order these the other way round.
        after = {
            "first.log": (1_000, 9_999, 900),
            "second.log": (2_000, 1, 1),
        }

        self.assertEqual(["first.log", "second.log"], HARNESS._backups_covering(before, after))

    def test_a_pre_existing_backup_precedes_an_appeared_backup_on_an_mtime_tie(self):
        stamp = 1_700_000_000_000_000_000
        pre_existing = "bak7"
        appeared = "bak8"
        before = {pre_existing: (stamp, 10, 1)}
        after = {
            pre_existing: (stamp, 10, 1),
            appeared: (stamp, 20, 2),
        }

        self.assertEqual(
            [pre_existing, appeared],
            HARNESS._backups_covering(before, after))

    def test_backups_that_rotated_before_collection_are_still_reachable(self):
        """Two rotations completed before the collector started leave nothing in the diff.

        `appeared` is empty then, so reading only the newest pre-existing backup would miss a
        failure that had already rotated twice.
        """
        identical = {
            "old.log": (1_000, 10, 1),
            "middle.log": (2_000, 10, 2),
            "newest.log": (3_000, 10, 3),
        }

        chosen = HARNESS._backups_covering(identical, dict(identical))

        self.assertEqual(["old.log", "middle.log", "newest.log"], chosen,
                         "every pre-existing backup within the cap is read, newest first")

    def test_an_unused_share_of_the_line_budget_is_handed_to_a_source_that_wants_it(self):
        """An equal split drops evidence while the block is still under budget."""
        short_source = ["only line"]
        long_source = ["line %d" % i for i in range(500)]

        shares = HARNESS._share_tail_lines([long_source, short_source])

        self.assertEqual(1, len(shares[1]))
        self.assertGreater(len(shares[0]), HARNESS._EVIDENCE_TAIL_LINES // 2,
                           "the short source's unused share must go to the one that can use it")
        self.assertLessEqual(len(shares[0]) + len(shares[1]), HARNESS._EVIDENCE_TAIL_LINES)
        self.assertEqual(long_source[-len(shares[0]):], shares[0], "each share is a TAIL")

    def test_the_backup_is_chosen_by_write_time_not_by_its_number(self):
        """EDT REUSES the backup numbers, so the suffix does not order them.

        A real workspace held .bak_7 written hours after .bak_8 and .bak_9. Taking the
        lexicographic last would read a file that predates the failure being diagnosed and show a
        tail that looks clean.
        """
        with tempfile.TemporaryDirectory() as tmp:
            metadata = os.path.join(tmp, ".metadata")
            os.makedirs(metadata)
            for name, body, mtime in (
                    (".bak_7.log", "NEWEST BACKUP LINE\n", 2_000_000_000),
                    (".bak_9.log", "STALE BACKUP LINE\n", 1_000_000_000),
                    (".log", "CURRENT LOG LINE\n", 2_000_000_100)):
                path = os.path.join(metadata, name)
                with open(path, "w", encoding="utf-8") as handle:
                    handle.write(body)
                os.utime(path, (mtime, mtime))

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        out = printed.getvalue()
        self.assertIn("NEWEST BACKUP LINE", out)
        self.assertIn("CURRENT LOG LINE", out)
        self.assertIn(".bak_7.log", out, "the heading must name the file the tail came from")
        # The ORDER is what proves mtime decided it. Both backups are collected - a failure that
        # rotated out before collection began sits in an older one - so "the stale file is absent"
        # is no longer the claim. By NAME .bak_7 would precede .bak_9; by write time it follows it,
        # and that is the sequence the block must print.
        self.assertLess(out.index(".bak_9.log"), out.index(".bak_7.log"),
                        "sections must be ordered by write time, not by backup number")
        self.assertLess(out.index(".bak_7.log"), out.index("EDT log tail: .metadata/.log"),
                        "the current log is the newest and comes last")

    def test_a_backup_removed_after_listing_is_skipped_without_losing_the_current_log(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_dir = os.path.join(tmp, ".metadata")
            os.makedirs(log_dir)
            backup_path = os.path.join(log_dir, ".bak_1.log")
            with open(backup_path, "w", encoding="utf-8") as handle:
                handle.write("rotated evidence\n")
            with open(os.path.join(log_dir, ".log"), "w", encoding="utf-8") as handle:
                handle.write("current evidence\n")

            real_stat = HARNESS.os.stat

            def vanish_before_stat(path, *args, **kwargs):
                if path == backup_path:
                    raise FileNotFoundError("rotation removed the backup")
                return real_stat(path, *args, **kwargs)

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS.os, "stat", side_effect=vanish_before_stat), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("project state")

        out = printed.getvalue()
        self.assertIn("current evidence", out)
        self.assertIn("EDT log tail: .metadata/.log", out)

    def test_a_live_collector_makes_the_next_collection_skip_without_starting_a_thread(self):
        entered = threading.Event()
        real_thread = threading.Thread

        def blocking_collector(_last_list_projects):
            entered.set()
            self.released.wait(30)

        printed = io.StringIO()
        with mock.patch.object(HARNESS, "_print_failed_settle_evidence",
                               side_effect=blocking_collector) as collector, \
                mock.patch.object(HARNESS.threading, "Thread", wraps=real_thread) as thread_type, \
                contextlib.redirect_stdout(printed):
            HARNESS._failed_settle_evidence("first state")
            self.assertTrue(entered.wait(5), "the first collector must be in flight")
            HARNESS._failed_settle_evidence("second state")

        self.assertEqual(1, thread_type.call_count)
        self.assertEqual(1, collector.call_count)
        self.assertIn("still in flight from an earlier settle and was skipped", printed.getvalue())

    def test_a_failed_collector_start_is_retried_by_a_later_settle_in_the_same_cycle(self):
        snapshots = iter(("first state", "second state", "third state"))

        def failed_wait(*, timeout, failure_details, progress=None, ignore_projects=()):
            failure_details[:] = ["projects not ready after %ds: P=building" % timeout]
            progress.update({"last_list_projects": next(snapshots)})
            return False

        real_start = threading.Thread.start
        start_attempts = []

        def fail_first_start(thread):
            start_attempts.append(thread)
            if len(start_attempts) == 1:
                raise RuntimeError("native thread exhausted")
            return real_start(thread)

        printed = io.StringIO()
        with mock.patch.object(HARNESS, "MODEL_SETTLE_ATTEMPTS", 3), \
                mock.patch.object(HARNESS, "wait_for_project_ready", side_effect=failed_wait), \
                mock.patch.object(HARNESS.threading.Thread, "start", new=fail_first_start), \
                mock.patch.object(HARNESS, "_print_failed_settle_evidence") as collector, \
                contextlib.redirect_stdout(printed):
            result = HARNESS._revert_and_clean("P", mock.Mock())
            started = HARNESS._FAILED_SETTLE_EVIDENCE_THREAD
            self.assertIsNotNone(started, "a later settle must retry the failed thread start")
            started.join(5)

        self.assertEqual((False, 0, 3), result[:3])
        self.assertEqual(2, len(start_attempts),
                         "the failed start is retried, then the successful start suppresses more")
        collector.assert_called_once_with("second state")
        self.assertIs(start_attempts[1], HARNESS._FAILED_SETTLE_EVIDENCE_THREAD)

    def test_a_second_final_settle_failure_does_not_replace_a_completed_first_collection(self):
        def failed_wait(*, timeout, failure_details, progress=None, ignore_projects=()):
            failure_details[:] = ["projects not ready after %ds: P=building" % timeout]
            progress.update({"last_list_projects": "| P | building |"})
            return False

        printed = io.StringIO()
        with mock.patch.object(HARNESS, "reset_all_fixtures"), \
                mock.patch.object(HARNESS, "_revert_and_clean",
                                  return_value=(True, 1, 0, None)), \
                mock.patch.object(HARNESS, "wait_for_project_ready", side_effect=failed_wait), \
                mock.patch.object(HARNESS, "_print_failed_settle_evidence") as collector, \
                contextlib.redirect_stdout(printed):
            with self.assertRaises(HARNESS.E2EModelResetFailed):
                HARNESS.reset_model()
            first = HARNESS._FAILED_SETTLE_EVIDENCE_THREAD
            first.join(5)
            with self.assertRaises(HARNESS.E2EModelResetFailed):
                HARNESS.final_cleanup()
            HARNESS._FAILED_SETTLE_EVIDENCE_THREAD.join(5)

        self.assertEqual(1, collector.call_count)
        self.assertIs(first, HARNESS._FAILED_SETTLE_EVIDENCE_THREAD,
                      "a later settle failure must not overwrite the first collector")
        self.assertIn("already collected for an earlier settle and was skipped",
                      printed.getvalue())

    def test_the_tail_is_the_last_bytes_of_a_large_log(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_path = os.path.join(tmp, ".log")
            with open(log_path, "wb") as handle:
                handle.write(b"x" * (HARNESS._EVIDENCE_LOG_TAIL_BYTES * 2))
                handle.write("\nLAST LINE\n".encode("utf-8"))

            text = HARNESS._read_log_tail(log_path)

        self.assertIn("LAST LINE", text)
        self.assertLessEqual(len(text.encode("utf-8")),
                             HARNESS._EVIDENCE_LOG_TAIL_BYTES + len("\nLAST LINE\n"))

    def test_an_ordinary_read_failure_is_reported_as_itself(self):
        with self.assertRaises(OSError):
            HARNESS._read_log_tail(os.path.join("no", "such", "directory", ".log"))


if __name__ == "__main__":
    unittest.main()
