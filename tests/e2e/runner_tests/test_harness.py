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

        def ready(timeout=None, failure_details=None, ignore_projects=()):
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


if __name__ == "__main__":
    unittest.main()
