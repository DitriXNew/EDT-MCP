"""Pure isolation-layer contracts; no EDT server or fixture mutation required."""

import contextlib
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
        self.assertNotIn("STALE BACKUP LINE", out)
        self.assertIn(".bak_7.log", out, "the heading must name the file the tail came from")

    def test_a_backup_removed_after_listing_is_skipped_without_losing_the_current_log(self):
        with tempfile.TemporaryDirectory() as tmp:
            log_dir = os.path.join(tmp, ".metadata")
            os.makedirs(log_dir)
            backup_path = os.path.join(log_dir, ".bak_1.log")
            with open(backup_path, "w", encoding="utf-8") as handle:
                handle.write("rotated evidence\n")
            with open(os.path.join(log_dir, ".log"), "w", encoding="utf-8") as handle:
                handle.write("current evidence\n")

            real_glob = HARNESS.glob.glob

            def list_then_remove(pattern):
                paths = real_glob(pattern)
                os.remove(backup_path)
                return paths

            printed = io.StringIO()
            with mock.patch.object(HARNESS, "_workspace_dir", return_value=tmp), \
                    mock.patch.object(HARNESS.glob, "glob", side_effect=list_then_remove), \
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
