"""The committed fixtures must not already contain what the suite seeds at runtime.

`run_all.py` ends every run with a "fixture clean" verdict, but that verdict compares the working
tree against HEAD - so an object a run left behind and that then got COMMITTED is, by construction,
"clean". It stays invisible until the test that seeds it fails with "Node already exists", one
shard at a time, on a machine that is not the one that committed it.

The `E2E` prefix separates the two populations: the fixture projects ship no object carrying it,
while the suite's own identifiers use it in the hundreds. So a TRACKED fixture path with an
E2E-prefixed component is a leftover, and naming it here is cheaper than reading a shard log.
"""

import importlib.util
import os
import re
import subprocess
import unittest


HARNESS_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "harness.py")
SPEC = importlib.util.spec_from_file_location("edt_mcp_e2e_harness", HARNESS_PATH)
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(HARNESS_PATH))))

# A path COMPONENT that opens with the reserved prefix, in any casing: the suite writes
# `E2EMdP4ExtDp` and `E2eShouldRefuse` alike, and the fixtures reserve neither spelling.
_SEEDED_COMPONENT = re.compile(r"(?:^|/)e2e", re.IGNORECASE)


def _tracked_fixture_paths():
    """Every path git TRACKS under the fixture projects the harness itself knows about.

    Tracked, not on-disk. An UNTRACKED leftover is what the end-of-run fixture-clean verdict
    already catches, and a local run legitimately has some in flight; the gap this closes is the
    committed one. Reading the directories out of FIXTURE_REL_BY_PROJECT keeps this from drifting
    the day a fourth fixture project is added.
    """
    rels = sorted(HARNESS.FIXTURE_REL_BY_PROJECT.values())
    out = subprocess.run(["git", "ls-files", "--"] + rels, cwd=REPO_ROOT,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    return out.stdout.decode("utf-8", "replace").splitlines()


class FixtureHygieneTest(unittest.TestCase):
    def test_no_runtime_seeded_object_is_committed_into_a_fixture(self):
        offenders = [path for path in _tracked_fixture_paths() if _SEEDED_COMPONENT.search(path)]

        self.assertEqual([], offenders,
                         "these E2E-named paths are committed into a fixture, but the suite creates "
                         "objects with that prefix itself - the test that seeds one will fail with "
                         "\"Node already exists\". Revert them (git checkout master -- <path>, and "
                         "delete the added directories); the fixture-clean verdict cannot see them.")

    def test_the_guard_flags_a_leftover_and_leaves_the_real_fixture_alone(self):
        """Discrimination, against the paths that actually occur.

        The leftover below is the one that reached CI: committing it turned a passing
        modify_metadata test into "Node already exists" on shard 3 only.
        """
        self.assertTrue(
            _SEEDED_COMPONENT.search("tests/tests/src/DataProcessors/E2EMdP4ExtDp/E2EMdP4ExtDp.mdo"),
            "the guard must flag the leftover that slipped through")

        for innocent in ("tests/tests/src/Catalogs/tests_ExtOnly/tests_ExtOnly.mdo",
                         "tests/TestConfiguration/src/CommonModules/Calc/Module.bsl",
                         "tests/TestConfiguration/src/Configuration/Configuration.mdo"):
            self.assertIsNone(_SEEDED_COMPONENT.search(innocent),
                              "a genuine fixture path must not be flagged: " + innocent)

    def test_the_scanned_directories_come_from_the_harness(self):
        """A fixture project the harness resets but this guard never scans is a blind spot."""
        rels = set(HARNESS.FIXTURE_REL_BY_PROJECT.values())

        self.assertEqual(set(HARNESS.ALL_FIXTURE_PROJECTS),
                         set(HARNESS.FIXTURE_REL_BY_PROJECT),
                         "every fixture project the runner resets needs a path here")
        self.assertTrue(rels, "the fixture path map must not be empty")
