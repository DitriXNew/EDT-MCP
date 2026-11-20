"""
e2e tests for write_module_source mode=replaceAll (#996).

replaceAll replaces EVERY whole-word occurrence of oldSource with source in one call - a renamed variable or a
called procedure across one module - and reports how many (`replacements`) and on which lines of the text before
the write (`replacedLines`). Parts of longer identifiers are never rewritten; comments and string literals are
text like any other; a spelling in another letter case is reported in the warning, never replaced. Every guard of
the text modes applies, the repeated-method-name check (#987) included.

Fixture target: CommonModules/OK/Module.bsl (empty in the committed baseline) and CommonModules/Calc
(Function Add, Procedure Test); the orchestrator resets the fixture before every write test.
"""

import re

from harness import (
    call, assert_ok, assert_error, assert_error_quality, assert_contains, assert_not_contains,
    read_disk, tree_snapshot, assert_tree_unchanged, e2e_test, E2EAssertion, PROJECT,
)

MODULE = "CommonModules/OK/Module.bsl"
CALC = "CommonModules/Calc/Module.bsl"

# The old name as an assignment target, a call argument, part of a longer identifier, in a comment, in a string
# and once in another letter case.
SEED = ("Procedure E2E996Run() Export\n"
        "\tE2E996Old = 1; // E2E996Old is the counter\n"
        "\tE2E996OldLonger = E2E996Old + 2;\n"
        "\tMessage(\"E2E996Old\");\n"
        "\te2e996old = 3;\n"
        "EndProcedure\n")

# A Cyrillic name, and the same name glued to a following Cyrillic letter (one longer identifier for BSL).
RU_OLD = "\u0421\u0447\u0435\u0442\u0447\u0438\u043a996"            # Счетчик996
RU_NEW = "\u0418\u0442\u043e\u0433996"                              # Итог996
RU_SEED = ("\u041f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u0430 E2E996Ru() \u042d\u043a\u0441\u043f\u043e\u0440\u0442\n"
           "\t" + RU_OLD + " = 1;\n"
           "\t" + RU_OLD + "\u0418\u0442\u043e\u0433\u043e = " + RU_OLD + ";\n"
           "\u041a\u043e\u043d\u0435\u0446\u041f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u044b\n")


def _seed(text):
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replace", "overwrite": True, "source": text,
    })
    assert_ok(r, "seed the module")
    disk = read_disk("src/" + MODULE).replace("\r\n", "\n").lstrip("\ufeff")
    if disk != text:
        raise E2EAssertion("the seeded module is not on disk as sent:\n%r" % disk[:600])


def _field(result, key):
    m = re.search(r"\n%s: \"?([^\"\n]*)\"?\n" % re.escape(key), result.text or "")
    if not m:
        raise E2EAssertion("the response carries no %s:\n%s" % (key, (result.text or "")[:600]))
    return m.group(1)


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_replace_all_rewrites_every_whole_word_occurrence_and_reports_them():
    _seed(SEED)
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceAll",
        "oldSource": "E2E996Old", "source": "E2E996New",
    })
    assert_ok(r, "replaceAll of a whole word")
    if _field(r, "replacements") != "4":
        raise E2EAssertion("expected replacements: 4 (assignment, comment, argument, string):\n%s" % r.text[:600])
    if _field(r, "replacedLines") != "2-4":
        raise E2EAssertion("expected replacedLines: 2-4:\n%s" % r.text[:600])
    assert_contains(r.text, "'e2e996old' at line 5", "the other-case spelling is named in the warning")
    expected = (SEED.replace("E2E996Old = 1; // E2E996Old", "E2E996New = 1; // E2E996New")
                .replace("= E2E996Old + 2", "= E2E996New + 2")
                .replace("\"E2E996Old\"", "\"E2E996New\""))
    disk = read_disk("src/" + MODULE).replace("\r\n", "\n").lstrip("\ufeff")
    if disk != expected:
        raise E2EAssertion("the module on disk is not the expected rewrite:\n%r\nexpected:\n%r" % (disk, expected))
    read = call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE})
    assert_ok(read, "read the rewritten module back")
    assert_contains(read.text, "E2E996OldLonger = E2E996New + 2;", "a longer identifier keeps its text")
    assert_contains(read.text, "e2e996old = 3;", "the other-case spelling is left as it was")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_replace_all_keeps_the_boundary_of_a_cyrillic_name():
    _seed(RU_SEED)
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceAll", "oldSource": RU_OLD, "source": RU_NEW,
    })
    assert_ok(r, "replaceAll of a Cyrillic name")
    if _field(r, "replacements") != "2":
        raise E2EAssertion("expected replacements: 2:\n%s" % r.text[:600])
    disk = read_disk("src/" + MODULE).replace("\r\n", "\n")
    assert_contains(disk, "\t" + RU_NEW + " = 1;", "the assignment is renamed")
    assert_contains(disk, "\t" + RU_OLD + "\u0418\u0442\u043e\u0433\u043e = " + RU_NEW + ";",
                    "the longer identifier keeps its prefix, the use is renamed")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_replace_all_without_a_whole_word_occurrence_is_refused():
    _seed(SEED)
    before = tree_snapshot()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceAll",
        "oldSource": "E2E996OldLong", "source": "E2E996New",
    })
    err = assert_error(r, "replaceAll of a fragment that occurs only inside a longer identifier")
    assert_error_quality(err, names=["oldSource"], suggests=["searchReplace"], ctx="the refusal names the way out")
    assert_contains(err, "inside longer identifiers (line 3)", "the line of the longer identifier is named")
    assert_contains(err, "Nothing was written", "the refusal says nothing was written")
    assert_tree_unchanged(before, "a refused replaceAll must not touch the project")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_replace_all_that_creates_a_duplicate_method_is_refused():
    """#987 applies: renaming the declaration Test to Add makes the module declare Add twice."""
    before = tree_snapshot()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": CALC, "mode": "replaceAll", "oldSource": "Test", "source": "Add",
    })
    err = assert_error(r, "replaceAll that turns Test into a second Add")
    assert_error_quality(err, names=["Add"], ctx="the duplicate is named")
    assert_contains(err, "BSL duplicate method check failed", "the duplicate check refused it")
    assert_contains(err, "Nothing was written", "the refusal says nothing was written")
    assert_contains(read_disk("src/" + CALC), "Процедура Test()",  # Процедура Test()
                    "the module keeps its procedure Test")
    assert_tree_unchanged(before, "a refused replaceAll must not touch the project")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_replace_all_that_the_write_would_erase_is_refused():
    """Removing the final line break of a one-line module: the write puts it back, so nothing would change."""
    _seed("E2E996Line = 1;\n")
    before = tree_snapshot()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceAll", "oldSource": "\n", "source": "",
    })
    err = assert_error(r, "replaceAll whose only effect the write undoes")
    assert_contains(err, "replaceAll would write the module exactly as it is", "the refusal says nothing would change")
    assert_error_quality(err, suggests=["join or split"], ctx="the refusal says how to change a line break")
    assert_contains(err, "Nothing was written", "the refusal says nothing was written")
    assert_tree_unchanged(before, "a refused replaceAll must not touch the project")