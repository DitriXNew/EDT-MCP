"""The committed fixtures must not already contain what the suite seeds at runtime.

`run_all.py` ends every run with a "fixture clean" verdict, but that verdict compares the working
tree against HEAD - so an object a run left behind and that then got COMMITTED is, by construction,
"clean". It stays invisible until the test that seeds it fails with "Node already exists", one
shard at a time, on a machine that is not the one that committed it.

The `E2E` prefix separates the two populations: the fixture projects ship no object carrying it,
while the suite's own identifiers use it in the hundreds. So a TRACKED fixture path with an
E2E-prefixed component is a leftover, and naming it here is cheaper than reading a shard log.

Both prefix scans go blind on the same leftover: one that carries NO marker and is serialized
inside an already-committed file - a form field written into `Form.form`, an attribute written into
its owner's `.mdo`. That is not the rare case: most seed names carry no marker, and enforcing one
is not available either, because the suite builds most FQNs at runtime from expressions, so no
static rule can even name what it would have to check. The third guard therefore pins what the
fixture DECLARES: `fixture_declarations.txt` holds every `<name>` in every tracked fixture file, and
a leftover shows up as an added line whether or not it is marked, nested or literal.
"""

import importlib.util
import os
import re
import subprocess
import tempfile
import unittest
from collections import Counter


HARNESS_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "harness.py")
SPEC = importlib.util.spec_from_file_location("edt_mcp_e2e_harness", HARNESS_PATH)
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(HARNESS_PATH))))

# A path COMPONENT that opens with the reserved prefix, in any casing: the suite writes
# `E2EMdP4ExtDp` and `E2eShouldRefuse` alike, and the fixtures reserve neither spelling.
_SEEDED_COMPONENT = re.compile(r"(?:^|/)e2e", re.IGNORECASE)

# The pinned declaration list. Sorted `path:name`, one line per `<name>` element, regenerated on
# purpose (see UPDATE_ENV) so a fixture change is a reviewable diff and nothing else is.
DECLARATIONS_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                 "fixture_declarations.txt")
UPDATE_ENV = "EDT_MCP_UPDATE_FIXTURE_DECLARATIONS"
_REGENERATE_COMMAND = ("%s=1 python3 -m unittest discover -s tests/e2e/runner_tests -p \"test_*.py\""
                       % UPDATE_ENV)
# Carried in the pinned file itself, so whoever opens it finds out how it is meant to be changed.
_PIN_HEADER = (
    "# Every <name> the tracked e2e fixture files declare, as sorted `path:name` (# lines ignored).\n"
    "# An added line you did not intend is a seeded object that got committed into the fixture.\n"
    "# After an INTENTIONAL fixture change, regenerate and review the diff:\n"
    "#   " + _REGENERATE_COMMAND + "\n")


def _committed_seeded_names(root=REPO_ROOT, rels=None):
    """Tracked fixture lines that DECLARE an E2E-prefixed object name.

    The path scan below cannot see a seeded CHILD. EDT serializes an attribute, a dimension or a
    tabular section INSIDE its owner's `.mdo`, so committing
    `Catalog.Catalog.Attribute.E2EDefinedTypeAttr` modifies `Catalogs/Catalog/Catalog.mdo` and adds
    no path component at all - the leftover is invisible to a filename rule while the test that
    seeds it still dies with "Node already exists".

    The match is on the NAME element rather than the raw prefix: the fixture legitimately contains
    the string in cell text (a print template reads "E2E Print Template"), and flagging prose would
    make this gate cry wolf until someone switched it off.
    """
    if rels is None:
        rels = sorted(HARNESS.FIXTURE_REL_BY_PROJECT.values())
    out = subprocess.run(["git", "grep", "-I", "-n", "-i", "-E", "--cached", "<name>E2E", "--"]
                         + list(rels), cwd=root,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    # git grep exits 1 for "no matches", which is the healthy case; anything else is a real error
    # and must not be read as a clean fixture.
    if out.returncode not in (0, 1):
        raise AssertionError("git grep failed over the fixtures: %s"
                             % out.stderr.decode("utf-8", "replace").strip())
    return out.stdout.decode("utf-8", "replace").splitlines()


def _tracked_fixture_paths(root=REPO_ROOT, rels=None):
    """Every path git TRACKS under the fixture projects the harness itself knows about.

    Tracked, not on-disk. An UNTRACKED leftover is what the end-of-run fixture-clean verdict
    already catches, and a local run legitimately has some in flight; the gap this closes is the
    committed one. Reading the directories out of FIXTURE_REL_BY_PROJECT keeps this from drifting
    the day a fourth fixture project is added.
    """
    if rels is None:
        rels = sorted(HARNESS.FIXTURE_REL_BY_PROJECT.values())
    out = subprocess.run(["git", "ls-files", "--"] + list(rels), cwd=root,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    return out.stdout.decode("utf-8", "replace").splitlines()


def _declared_fixture_names(root=REPO_ROOT, rels=None):
    """Every object name the tracked fixture files DECLARE, as sorted `path:name` lines.

    `<name>` is what EDT writes for a declaration at every depth - a catalog, its attribute, a form
    and a field inside that form - so one element covers the top-level objects and the nested ones
    that a path rule cannot reach. Duplicates are KEPT: two identical lines mean the file declares
    the name twice, and collapsing them would hide a leftover that reuses an existing name.

    Read from the INDEX (`--cached`), so a local run in flight - an uncommitted seeded object, a
    CRLF checkout - is neither flagged nor able to mask a leftover.
    """
    if rels is None:
        rels = sorted(HARNESS.FIXTURE_REL_BY_PROJECT.values())
    # -c core.quotepath=false: without it git octal-escapes a non-ASCII path (the fixture has a
    # Cyrillic-named common module), and whether it does depends on the local config - the pinned
    # file would then drift between two clean checkouts.
    out = subprocess.run(["git", "-c", "core.quotepath=false", "grep", "-o", "-I", "-E",
                          "--cached", "<name>[^<]*</name>", "--"] + list(rels), cwd=root,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    # Same contract as the scan above: 1 is "no matches", anything else is a broken scan that must
    # not be read as a fixture without declarations.
    if out.returncode not in (0, 1):
        raise AssertionError("git grep failed over the fixtures: %s"
                             % out.stderr.decode("utf-8", "replace").strip())
    declarations = []
    for line in out.stdout.decode("utf-8", "replace").splitlines():
        path, _, match = line.partition(":")
        declarations.append("%s:%s" % (path, match[len("<name>"):-len("</name>")]))
    return sorted(declarations)


class FixtureHygieneTest(unittest.TestCase):
    def test_no_runtime_seeded_object_is_committed_into_a_fixture(self):
        offenders = [path for path in _tracked_fixture_paths() if _SEEDED_COMPONENT.search(path)]

        self.assertEqual([], offenders,
                         "these E2E-named paths are committed into a fixture, but the suite creates "
                         "objects with that prefix itself - the test that seeds one will fail with "
                         "\"Node already exists\". Revert them (git checkout master -- <path>, and "
                         "delete the added directories); the fixture-clean verdict cannot see them.")

    def test_no_seeded_child_object_is_committed_inside_a_fixture_file(self):
        declared = _committed_seeded_names()

        self.assertEqual([], declared,
                         "these lines declare an E2E-named object inside a committed fixture file. "
                         "The suite seeds those at runtime, so the test that creates one will fail "
                         "with \"Node already exists\". Revert the file "
                         "(git checkout master -- <path>); neither the fixture-clean verdict nor "
                         "the path scan above can see a leftover serialized inside its owner.")

    def test_the_name_rule_separates_a_declaration_from_prose(self):
        """Why the match is on the NAME element and not on the bare prefix.

        The fixture's print template legitimately contains the string in cell text. A rule that
        flagged it would fail on a clean checkout, and a gate that cries wolf gets disabled.
        """
        pattern = re.compile(r"<name>e2e", re.IGNORECASE)

        self.assertTrue(pattern.search("    <name>E2EDefinedTypeAttr</name>"))
        self.assertIsNone(pattern.search("<v8:content>E2E Print Template</v8:content>"),
                          "cell text that merely mentions the prefix must not be flagged")

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

    def test_the_scanner_flags_a_leftover_injected_into_a_scratch_fixture(self):
        rel = "src/Catalogs/Catalog/Forms/ItemForm/Form.form"
        with tempfile.TemporaryDirectory() as root:
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            path = os.path.join(root, *rel.split("/"))
            os.makedirs(os.path.dirname(path))
            with open(path, "w", encoding="utf-8") as fixture:
                fixture.write("<name>E2EScratchLeftover</name>\n")
            subprocess.run(["git", "add", "--", rel], cwd=root, check=True)

            self.assertEqual([rel], _tracked_fixture_paths(root=root, rels=["src"]))
            declared = _committed_seeded_names(root=root, rels=["src"])

        self.assertTrue(any(rel in line and "E2EScratchLeftover" in line
                            for line in declared), declared)

    def test_the_fixture_declares_exactly_the_pinned_object_names(self):
        """The pin: every name the fixture declares, against the committed list.

        The two prefix scans above answer "does anything look seeded?". This one answers "is the
        fixture still the one the suite was written against?", which is the question a marker-less
        name nested inside a committed file can be asked at all.
        """
        actual = _declared_fixture_names()

        if os.environ.get(UPDATE_ENV):
            with open(DECLARATIONS_PATH, "w", encoding="utf-8", newline="\n") as pinned:
                pinned.write(_PIN_HEADER + "\n".join(actual) + "\n")
            return

        with open(DECLARATIONS_PATH, encoding="utf-8") as pinned:
            expected = [line for line in pinned.read().splitlines()
                        if not line.startswith("#")]

        # A multiset difference, so a gained or lost DUPLICATE declaration is named too.
        added = sorted((Counter(actual) - Counter(expected)).elements())
        removed = sorted((Counter(expected) - Counter(actual)).elements())
        # Our own message IS the whole diff, and unittest's truncated list dump in front of it
        # ("Diff is 8101 characters long") only buries the two lines a contributor needs.
        self.longMessage = False
        self.assertEqual(expected, actual,
                         "the fixture's declared object names drifted from %s.\n"
                         "ADDED (in the fixture, not in the pin): %s\n"
                         "REMOVED (in the pin, not in the fixture): %s\n"
                         "An ADDED line you did not intend is a seeded leftover that got committed "
                         "- the test that creates it will fail with \"Node already exists\" "
                         "(git checkout master -- <path>). If you changed the fixture ON PURPOSE, "
                         "regenerate the pin with `%s` and commit that diff with the fixture "
                         "change. (Overriding MCP_*_REL points this scan at your own paths, not "
                         "the committed fixture.)"
                         % (os.path.relpath(DECLARATIONS_PATH, REPO_ROOT).replace(os.sep, "/"),
                            added or "none", removed or "none", _REGENERATE_COMMAND))

    def test_the_pin_sees_the_leftover_both_prefix_scans_are_blind_to(self):
        """The gap this pin exists for, reproduced end to end in a scratch fixture.

        A form field seeded into an ALREADY-COMMITTED `Form.form`, carrying no `E2E` marker: it
        adds no path component and matches no prefix, so both scans above stay green while the
        test that seeds it dies with "Node already exists".
        """
        rel = "src/Catalogs/Catalog/Forms/ItemForm/Form.form"
        with tempfile.TemporaryDirectory() as root:
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            path = os.path.join(root, *rel.split("/"))
            os.makedirs(os.path.dirname(path))
            clean = "<Form><name>ItemForm</name><name>Object</name></Form>\n"
            with open(path, "w", encoding="utf-8") as fixture:
                fixture.write(clean)
            subprocess.run(["git", "add", "--", rel], cwd=root, check=True)
            baseline = _declared_fixture_names(root=root, rels=["src"])

            with open(path, "w", encoding="utf-8") as fixture:
                fixture.write(clean.replace("</Form>", "<name>KindProbeFld</name></Form>"))
            subprocess.run(["git", "add", "--", rel], cwd=root, check=True)

            self.assertEqual([], [p for p in _tracked_fixture_paths(root=root, rels=["src"])
                                  if _SEEDED_COMPONENT.search(p)],
                             "the leftover adds no path component, so the path scan cannot see it")
            self.assertEqual([], _committed_seeded_names(root=root, rels=["src"]),
                             "the leftover carries no E2E marker, so the prefix scan cannot see it")

            seeded = _declared_fixture_names(root=root, rels=["src"])

        self.assertEqual([rel + ":ItemForm", rel + ":Object"], baseline)
        self.assertEqual([line for line in seeded if line not in baseline],
                         [rel + ":KindProbeFld"],
                         "the pin must report the seeded field, and nothing else")

    def test_the_scanned_directories_come_from_the_harness(self):
        """A fixture project the harness resets but this guard never scans is a blind spot."""
        rels = set(HARNESS.FIXTURE_REL_BY_PROJECT.values())

        self.assertEqual(set(HARNESS.ALL_FIXTURE_PROJECTS),
                         set(HARNESS.FIXTURE_REL_BY_PROJECT),
                         "every fixture project the runner resets needs a path here")
        self.assertTrue(rels, "the fixture path map must not be empty")


if __name__ == "__main__":
    unittest.main()
