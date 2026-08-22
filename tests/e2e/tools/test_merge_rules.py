"""
e2e tests for merge_rules (kind: action - it reads and writes files under the OS TEMP root,
never inside the project, so every test also asserts the project tree stayed clean).

merge_rules is the read/author half of EDT's merge-settings file: the sparse XML document of
per-node merge decisions a configuration comparison saves and re-applies when it is launched.
The format is the platform's own (measured on MergeSettingsTree): `Settings/@Format_version="2.0"`
-> `MergeSettings` -> `Node Key="$$Root$$"` -> a collection keyed by the model feature name ->
an object keyed `main:other:ancestor`, with `NONE` for a side on which the object does not exist.

What these tests prove that a unit test cannot: the CONTRACT on the wire.
  - read renders the decisions of a real file: a rename shows three different names, `NONE`
    renders as an absent side rather than as an object called "NONE", a positional child below
    the object is reported as a read-only `member` row, and the payload the tool does not
    interpret is counted rather than quietly dropped.
  - a write with no live comparison SAYS it was not validated and names compare_configurations -
    it must never read like a checked file.
  - an illegal rule is an isError whose text carries the allowed set, and nothing is written.

The fixtures are built inside the run (no dependency on any pre-existing file or history), in a
fresh mkdtemp directory, which is also why these tests are safe under the CI checkout depth.
"""

import os
import tempfile
import zipfile

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_contains,
    assert_not_contains,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
)


FIXTURE = """<?xml version="1.0" encoding="UTF-8"?>
<Settings Format_version="2.0">
  <MergeSettings>
    <Node Key="$$Root$$">
      <Properties>
        <SkipUnchanged>true</SkipUnchanged>
      </Properties>
      <Node Key="commonModules" MergeRule="GetFromOther">
        <Node Key="Alpha:Beta:Gamma" MergeRule="MergePrioritizingMain"/>
        <Node Key="Added:NONE:Added" MergeRule="DoNotMerge">
          <Node Key="7" MergeRule="GetFromOther" OrderSide="Other"/>
        </Node>
      </Node>
    </Node>
  </MergeSettings>
</Settings>
"""


OTHER_FIXTURE = """<?xml version="1.0" encoding="UTF-8"?>
<Settings Format_version="2.0">
  <MergeSettings>
    <Node Key="$$Root$$">
      <Node Key="documents" MergeRule="MergePrioritizingOther"/>
    </Node>
  </MergeSettings>
</Settings>
"""


def _workdir():
    # mkdtemp creates a NEW empty dir under the OS temp root - guaranteed outside this repo,
    # so a write can never touch the fixture project.
    return tempfile.mkdtemp(prefix="edt_merge_rules_e2e_")


def _seed_xml(directory, name="saved.xml"):
    path = os.path.join(directory, name)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(FIXTURE)
    return path


def _read_file(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


# ──────────────────────────────────────────────────────────────────────────────
# READ
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="merge_rules", kind="action")
def test_read_reports_the_decisions_of_a_saved_file():
    path = _seed_xml(_workdir())

    r = call("merge_rules", {"mode": "read", "filePath": path})
    assert_ok(r, "read a saved merge-rules file")
    assert_contains(r.text, "# Merge rules:", "the report must name what it read")
    assert_contains(r.text, "Format version: 2.0", "the format version is part of the answer")
    assert_contains(r.text, "Decisions: 4", "the fixture holds four decisions")
    # A rename: three different names on the three sides.
    assert_contains(r.text, "| Alpha | Beta | Gamma |",
                    "a top-object key must be split into main/other/ancestor")
    # NONE is the absence of the object on that side, not the name of an object called NONE.
    assert_contains(r.text, "| Added | (absent) | Added |",
                    "NONE must render as an absent side")
    assert_not_contains(r.text, "| Added | NONE | Added |",
                        "the literal NONE must not be presented as an object name")
    # A collection element is keyed by a computed position: reported, never authored.
    assert_contains(r.text, "| member |", "a positional child is reported at member level")
    assert_contains(r.text, "Preserved sections this tool does not interpret: 1",
                    "the payload the tool does not understand must be accounted for")
    assert_no_diff("read must not touch the project")


@e2e_test(tool="merge_rules", kind="action")
def test_read_of_a_zip_names_the_entry_it_read():
    directory = _workdir()
    zip_path = os.path.join(directory, "settings.zip")
    with zipfile.ZipFile(zip_path, "w") as z:
        z.writestr("Main_Other_Ancestor.xml", FIXTURE)

    r = call("merge_rules", {"mode": "read", "filePath": zip_path})
    assert_ok(r, "a comparison saves the zipped form; reading it must work")
    assert_contains(r.text, "!Main_Other_Ancestor.xml",
                    "a zip holds one entry per comparison - the report must say WHICH was read")
    assert_contains(r.text, "Decisions: 4", "the zipped content is the same document")
    assert_no_diff("read must not touch the project")


@e2e_test(tool="merge_rules", kind="action")
def test_read_of_a_missing_file_is_actionable():
    path = os.path.join(_workdir(), "not-here.xml")

    r = call("merge_rules", {"mode": "read", "filePath": path})
    err = assert_error(r, "a missing rules file")
    assert_error_quality(err, names=["not-here.xml"], suggests=["write"],
                         ctx="missing merge-rules file")
    assert_no_diff("a failed read must not touch the project")


@e2e_test(tool="merge_rules", kind="action")
def test_a_file_that_is_not_merge_settings_is_refused():
    directory = _workdir()
    path = os.path.join(directory, "configuration.xml")
    with open(path, "w", encoding="utf-8") as f:
        f.write('<Configuration Name="X"/>')

    r = call("merge_rules", {"mode": "read", "filePath": path})
    err = assert_error(r, "a configuration file is not a merge-rules file")
    assert_error_quality(err, names=["Configuration"], suggests=["Settings"],
                         ctx="foreign root element")


# ──────────────────────────────────────────────────────────────────────────────
# WRITE - the honest-mode contract
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="merge_rules", kind="action")
def test_write_without_a_live_comparison_says_it_was_not_validated():
    target = os.path.join(_workdir(), "rules.xml")

    r = call("merge_rules", {
        "mode": "write",
        "filePath": target,
        "decisions": [
            {"path": [], "rule": "DoNotMerge"},
            {"path": ["commonModules"], "rule": "GetFromOther"},
            {"path": ["commonModules", "Alpha:Beta:Gamma"], "rule": "MergePrioritizingMain"},
        ],
    })
    assert_ok(r, "authoring from names needs no comparison")
    assert_contains(r.text, "NOT VALIDATED",
                    "an unchecked file must never be reported as a checked one")
    assert_contains(r.text, "compare_configurations",
                    "the report must name how to get the rules validated")
    assert_not_contains(r.text, "Validated against comparison",
                        "it must not claim a validation that did not happen")
    assert_contains(r.text, "Decisions recorded: 3", "the counters must be whole")

    written = _read_file(target)
    assert_contains(written, 'Format_version="2.0"', "the file must be the platform's format")
    assert_contains(written, '<Node Key="$$Root$$" MergeRule="DoNotMerge">',
                    "the root decision applies to the whole configuration")
    assert_contains(written, '<Node Key="Alpha:Beta:Gamma" MergeRule="MergePrioritizingMain"/>',
                    "an object is addressed by its name on the three sides")
    assert_no_diff("writing outside the workspace must not touch the project")


@e2e_test(tool="merge_rules", kind="action")
def test_written_file_reads_back_as_the_same_decisions():
    target = os.path.join(_workdir(), "rules.xml")
    call("merge_rules", {"mode": "write", "filePath": target,
                         "decisions": [{"path": ["catalogs", "Products:Products:Products"],
                                        "rule": "GetFromOther"}]})

    r = call("merge_rules", {"mode": "read", "filePath": target})
    assert_ok(r, "what this tool writes, it must be able to read")
    assert_contains(r.text, "| Products | Products | Products |",
                    "the round trip must preserve the three-name key")
    assert_contains(r.text, "Decisions: 1", "exactly the decision that was written")


@e2e_test(tool="merge_rules", kind="action")
def test_write_refuses_to_replace_an_existing_file_without_based_on():
    path = _seed_xml(_workdir())

    r = call("merge_rules", {"mode": "write", "filePath": path,
                             "decisions": [{"path": [], "rule": "DoNotMerge"}]})
    err = assert_error(r, "silently discarding somebody's decisions is not an option")
    assert_error_quality(err, names=["saved.xml"], suggests=["basedOn"],
                         ctx="write over an existing rules file")
    assert_contains(_read_file(path), 'MergeRule="MergePrioritizingMain"',
                    "the refused write must leave the file exactly as it was")


@e2e_test(tool="merge_rules", kind="action")
def test_write_refuses_to_replace_a_different_file_even_with_based_on():
    # basedOn says where the decisions COME FROM. It is not permission to overwrite a third file:
    # with a different target, that target's own decisions would be gone and the report would name
    # only the ones carried in from basedOn.
    directory = _workdir()
    starting_point = _seed_xml(directory)
    target = os.path.join(directory, "target.xml")
    with open(target, "w", encoding="utf-8", newline="\n") as f:
        f.write(OTHER_FIXTURE)

    r = call("merge_rules", {"mode": "write", "filePath": target,
                             "basedOn": starting_point,
                             "decisions": [{"path": [], "rule": "DoNotMerge"}]})
    err = assert_error(r, "one file's decisions must never be written over another's")
    assert_error_quality(err, names=["target.xml", "saved.xml"], suggests=["basedOn"],
                         ctx="write over a DIFFERENT existing rules file")
    assert_contains(_read_file(target), 'MergeRule="MergePrioritizingOther"',
                    "the target must keep its own decisions, byte for byte")


@e2e_test(tool="merge_rules", kind="action")
def test_write_with_based_on_keeps_what_was_already_decided():
    path = _seed_xml(_workdir())

    r = call("merge_rules", {
        "mode": "write",
        "filePath": path,
        "basedOn": path,
        "decisions": [{"path": ["catalogs", "Products:Products:Products"], "rule": "DoNotMerge"}],
    })
    assert_ok(r, "updating a rules file in place")

    written = _read_file(path)
    assert_contains(written, '<Node Key="Alpha:Beta:Gamma" MergeRule="MergePrioritizingMain"/>',
                    "the decisions already in the file must survive")
    assert_contains(written, "<SkipUnchanged>true</SkipUnchanged>",
                    "the payload this tool does not interpret must survive verbatim")
    assert_contains(written, '<Node Key="Products:Products:Products" MergeRule="DoNotMerge"/>',
                    "and the new decision must be there")


# ──────────────────────────────────────────────────────────────────────────────
# WRITE - refusals
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="merge_rules", kind="action")
def test_an_illegal_rule_is_an_error_carrying_the_allowed_set():
    target = os.path.join(_workdir(), "rules.xml")

    r = call("merge_rules", {"mode": "write", "filePath": target,
                             "decisions": [{"path": [], "rule": "GET_FROM_OTHER"}]})
    err = assert_error(r, "the Java constant spelling is not a rule literal")
    assert_error_quality(
        err,
        names=["GET_FROM_OTHER"],
        suggests=["GetFromOther", "DoNotMerge", "MergePrioritizingMain", "MergePrioritizingOther"],
        ctx="illegal merge rule")
    assert not os.path.exists(target), "an illegal rule must never reach the file"


@e2e_test(tool="merge_rules", kind="action")
def test_custom_merge_is_refused_unconditionally():
    target = os.path.join(_workdir(), "rules.xml")

    r = call("merge_rules", {"mode": "write", "filePath": target,
                             "decisions": [{"path": [], "rule": "CustomMerge"}]})
    err = assert_error(r, "a custom merge carries settings this tool cannot author")
    assert_error_quality(err, names=["CustomMerge"], suggests=["GetFromOther"],
                         ctx="custom merge")
    assert not os.path.exists(target), "a refused decision must leave no file behind"


@e2e_test(tool="merge_rules", kind="action")
def test_a_rule_below_the_object_is_refused_because_the_key_is_a_position():
    target = os.path.join(_workdir(), "rules.xml")

    r = call("merge_rules", {
        "mode": "write",
        "filePath": target,
        "decisions": [{"path": ["commonModules", "A:A:A", "3"], "rule": "DoNotMerge"}],
    })
    err = assert_error(r, "below the object the platform keys nodes by position")
    assert_error_quality(err, suggests=["position"], ctx="too deep an address")


@e2e_test(tool="merge_rules", kind="action")
def test_an_object_key_without_the_three_names_is_refused_with_the_form_spelled_out():
    target = os.path.join(_workdir(), "rules.xml")

    r = call("merge_rules", {
        "mode": "write",
        "filePath": target,
        "decisions": [{"path": ["commonModules", "Alpha"], "rule": "DoNotMerge"}],
    })
    err = assert_error(r, "an object is keyed by its name on all three sides")
    assert_error_quality(err, names=["Alpha"], suggests=["Alpha:Alpha:Alpha", "Alpha:NONE:NONE"],
                         ctx="single-name object key")


@e2e_test(tool="merge_rules", kind="action")
def test_a_zip_target_is_refused_because_edt_would_ignore_it():
    target = os.path.join(_workdir(), "rules.zip")

    r = call("merge_rules", {"mode": "write", "filePath": target,
                             "decisions": [{"path": [], "rule": "DoNotMerge"}]})
    err = assert_error(r, "EDT picks the zip entry by the comparison's project triple")
    assert_error_quality(err, names=[".zip"], suggests=[".xml"], ctx="zip write target")
    assert not os.path.exists(target), "the refused write must create nothing"


@e2e_test(tool="merge_rules", kind="action")
def test_a_comparison_that_is_not_running_is_refused_rather_than_quietly_unvalidated():
    target = os.path.join(_workdir(), "rules.xml")

    r = call("merge_rules", {"mode": "write", "filePath": target,
                             "comparisonId": "no-such-comparison",
                             "decisions": [{"path": [], "rule": "DoNotMerge"}]})
    err = assert_error(r, "the caller asked for validation against a named comparison")
    assert_error_quality(err, names=["no-such-comparison"], suggests=["compare_configurations"],
                         ctx="unknown comparison id")
    assert not os.path.exists(target), "asking for validation and getting none is not a success"


@e2e_test(tool="merge_rules", kind="action")
def test_unknown_mode_is_refused_naming_both_modes():
    target = os.path.join(_workdir(), "rules.xml")

    r = call("merge_rules", {"mode": "merge", "filePath": target})
    err = assert_error(r, "there is no merge mode - merging is not what this tool does")
    assert_error_quality(err, names=["merge"], suggests=["read", "write"], ctx="unknown mode")


@e2e_test(tool="merge_rules", kind="action")
def test_a_relative_file_path_is_refused_instead_of_landing_in_edts_own_directory():
    """A relative path is not a smaller absolute one: the server resolves it against the
    working directory of the EDT PROCESS, which is wherever EDT was started from. That
    resolution never fails, so the old behaviour was not an error - it was a file created
    somewhere nobody named, reported as a success.

    Run on the wire because that is where the promise lives: the schema says "Absolute
    path", and only a live call can show the server keeping it.
    """
    r = call("merge_rules", {"mode": "write", "filePath": "rules.xml",
                             "decisions": [{"path": [], "rule": "DoNotMerge"}]})
    err = assert_error(r, "a relative filePath")
    assert_error_quality(err, names=["rules.xml", "filePath"],
                         suggests=["ABSOLUTE"], ctx="relative filePath")
    assert_no_diff("a refused write must not touch the project")


@e2e_test(tool="merge_rules", kind="action")
def test_write_needs_decisions():
    target = os.path.join(_workdir(), "rules.xml")

    r = call("merge_rules", {"mode": "write", "filePath": target})
    err = assert_error(r, "a write with nothing to record")
    assert_error_quality(err, names=["decisions"], suggests=["GetFromOther"],
                         ctx="write without decisions")


@e2e_test(tool="merge_rules", kind="action")
def test_the_guide_carries_the_addressing_and_validation_facts():
    # InputSchemaCompactor strips parameter prose at the tools/list boundary, so the guide is
    # where a caller learns the key format and what "not validated" means. If it stops saying
    # these, the facts are gone from the wire entirely.
    r = call("get_tool_guide", {"toolName": "merge_rules"})
    assert_ok(r, "merge_rules must ship a guide")
    for fragment in ("main:other:ancestor", "NOT VALIDATED", "$$Root$$", "GetFromOther"):
        assert_contains(r.text, fragment, "the guide must carry the addressing/validation facts")
    assert_no_diff("reading a guide must not touch the project")
