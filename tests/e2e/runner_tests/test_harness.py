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
        def wait(*, timeout, failure_details, progress):
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
        self.assertIn("FIRST FAILED MODEL SETTLE EVIDENCE", out)
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

            def rotate_four_times_then_read(path):
                if not rotations:
                    rotations.append(True)
                    for index in range(2, 6):
                        rotated_to = os.path.join(metadata, ".bak_%d.log" % index)
                        os.replace(current, rotated_to)
                        stamp = 2_000_000_000 + index
                        os.utime(rotated_to, (stamp, stamp))
                        with open(current, "w", encoding="utf-8") as handle:
                            handle.write("LATER WRITE %d\n" % index)
                return real_read(path)

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

            def rotate_in_place_then_read(path):
                if not rotations:
                    rotations.append(True)
                    os.replace(current, reused)
                    os.utime(reused, (stamp, stamp))    # the timestamp is deliberately unchanged
                    with open(current, "w", encoding="utf-8") as handle:
                        handle.write("AFTER ROTATION\n")
                return real_read(path)

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS, "_read_log_tail",
                                      side_effect=rotate_in_place_then_read), \
                    contextlib.redirect_stdout(printed):
                HARNESS._print_failed_settle_evidence("| P | building |")

        self.assertIn("FAILURE MOMENT", printed.getvalue(),
                      "a same-name, same-mtime replacement must still register as a rotation")

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

            def fail_on_the_backup(path):
                if path.endswith(".bak_1.log"):
                    raise OSError("vanished")
                return real_read(path)

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

            def rotate_twice_then_read(path):
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
                return real_read(path)

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

            def rotate_then_read(path):
                if not rotated:
                    # The writer rotates before the very first read gets its bytes.
                    rotated.append(True)
                    rotated_to = os.path.join(metadata, ".bak_2.log")
                    os.replace(current, rotated_to)
                    os.utime(rotated_to, (2_000_000_000, 2_000_000_000))
                    with open(current, "w", encoding="utf-8") as handle:
                        handle.write("AFTER ROTATION\n")
                return real_read(path)

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

    def test_a_rotation_between_the_two_reads_costs_a_duplicate_not_the_failure(self):
        """The current log is read FIRST, which is why this race cannot lose evidence.

        EDT rotates by renaming .log to a .bak_N and starting an empty .log. Reading the backup
        first, a rotation before the second read leaves the failure moment in a NEW backup that
        neither chosen path points at, and the tail looks clean. Reading .log first, the same
        rotation only duplicates lines. Re-scanning after the reads would race the writer the same
        way, so the ORDER is the fix.
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

            def read_then_rotate(path):
                text = real_read(path)
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
                      "a rotation caught mid-collection must not lose the failure moment")

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

        def failed_wait(*, timeout, failure_details, progress):
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
        def failed_wait(*, timeout, failure_details, progress):
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
