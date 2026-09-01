"""Pure isolation-layer contracts; no EDT server or fixture mutation required."""

import importlib.util
import os
import tempfile
import threading
import time
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


class EvidenceLogTailTest(unittest.TestCase):
    """The tail read is bounded in BOTH dimensions, and the bounds are checked by executing them.

    A comment promising "this cannot change the reset outcome" has now been wrong three times, in
    three different channels (an RPC arming the global latch, a second RPC inside the workspace
    locator, and unbounded bytes). A hung filesystem is the fourth: the size cap bounds the bytes,
    not the wait, and an overrun trips the runner's per-test timeout, which abandons the worker and
    arms the very latch the block must not touch. So both bounds are PROVEN here rather than
    asserted in prose.
    """

    def setUp(self):
        self.old_timeout = HARNESS._EVIDENCE_LOG_READ_TIMEOUT
        self.released = threading.Event()

    def tearDown(self):
        HARNESS._EVIDENCE_LOG_READ_TIMEOUT = self.old_timeout
        # Let the blocked reader finish so the interpreter is not left with a thread mid-test.
        self.released.set()
        HARNESS.__dict__.pop("open", None)

    def test_a_read_that_hangs_is_abandoned_instead_of_blocking_the_reset(self):
        HARNESS._EVIDENCE_LOG_READ_TIMEOUT = 1
        released = self.released

        def hanging_open(*_args, **_kwargs):
            released.wait(30)
            raise AssertionError("the reader must have been abandoned before this returns")

        # Module-global 'open' shadows the builtin inside harness, so this reaches the real call.
        HARNESS.open = hanging_open

        started = time.time()
        with self.assertRaises(RuntimeError) as caught:
            HARNESS._read_log_tail("any/path/.log")
        elapsed = time.time() - started

        self.assertLess(elapsed, 10, "the read must return on its own bound, not on the caller's")
        self.assertIn("abandoned", str(caught.exception))

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
