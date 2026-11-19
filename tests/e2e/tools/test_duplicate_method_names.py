"""
e2e tests for repeated procedure/function names (#987).

The 1C platform refuses to load a module that declares one name twice in one preprocessor context - every
method of the module stops working - and EDT marks the second declaration (method-already-defined). The
block-balance syntax check cannot see it, so:
  - write_module_source refuses, in every mode, a write that CREATES such a duplicate (nothing written);
    copies in different arms of one #If block are legal and written; skipSyntaxCheck=true writes it and warns;
  - read_method_source on such a module says the name is declared more than once and lists every declaration;
  - get_module_structure names the repeated name above its methods table.

Fixture module (the orchestrator resets the fixture before every write test):
  CommonModules/Calc - Function Add(A, B) Export (lines 1-3) and Procedure Test() Export (lines 5-7).
"""

import time

from harness import (
    call, assert_ok, assert_error, assert_error_quality, assert_contains, assert_not_contains,
    read_disk, tree_snapshot, assert_tree_unchanged, wait_for_project_ready,
    e2e_test, E2EAssertion, PROJECT,
)

CALC = "CommonModules/Calc/Module.bsl"
CALC_OWNER = "CommonModule.Calc"
# A second Add, another letter case, Russian keywords - the reported shape.
SECOND_ADD = "\nФункция add(A, B)\n\tВозврат 0;\n" \
             "КонецФункции\n"
MARKER_POLL_TIMEOUT = 120


def _seed_duplicate():
    """Write a second Add with the check skipped, as a module that already repeats the name."""
    w = call("write_module_source", {
        "projectName": PROJECT, "modulePath": CALC, "mode": "append", "source": SECOND_ADD,
        "skipSyntaxCheck": True,
    })
    assert_ok(w, "seed a second Add with skipSyntaxCheck=true")
    disk = read_disk("src/" + CALC)
    if disk.lower().count("функция add(") != 2:
        raise E2EAssertion("the seeded module does not declare Add twice on disk:\n%s" % disk[:600])
    wait_for_project_ready()


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_search_replace_that_adds_a_second_function_of_one_name_is_refused():
    """The reported case: searchReplace inserts a function whose name the module already declares."""
    before = tree_snapshot()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": CALC,
        "oldSource": "\tРезультат = Add(1, 2);\n"
                     "КонецПроцедуры\n",
        "source": "\tРезультат = Add(1, 2);\n"
                  "КонецПроцедуры\n" + SECOND_ADD,
    })
    err = assert_error(r, "searchReplace adding a second Add")
    assert_error_quality(err, names=["Add"], suggests=["replaceMethod"], ctx="the duplicate is named with the fix")
    assert_contains(err, "Nothing was written", "the refusal says nothing was written")
    assert_contains(err, "the current module declares it at line 1", "the existing declaration's line is named")
    assert_tree_unchanged(before, "a refused write must not touch the project")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_append_of_an_english_duplicate_is_refused():
    before = tree_snapshot()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": CALC, "mode": "append",
        "source": "\nProcedure TEST()\nEndProcedure\n",
    })
    err = assert_error(r, "append of a second Test")
    assert_error_quality(err, names=["Test"], ctx="the duplicate is named")
    assert_tree_unchanged(before, "a refused append must not touch the project")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_copies_in_different_arms_of_one_if_block_are_written():
    source = ("\n#If Server Then\nProcedure E2E987Branch() Export\nEndProcedure\n#Else\n"
              "Procedure E2E987Branch() Export\nEndProcedure\n#EndIf\n")
    r = call("write_module_source", {"projectName": PROJECT, "modulePath": CALC, "mode": "append", "source": source})
    assert_ok(r, "two copies in the arms of one #If block are legal")
    assert_not_contains(r.text, "duplicate method name", "no duplicate warning for excluding branches")
    assert_contains(read_disk("src/" + CALC), "#Else\nProcedure E2E987Branch()", "both copies are on disk")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_skip_syntax_check_writes_the_duplicate_and_says_so():
    w = call("write_module_source", {
        "projectName": PROJECT, "modulePath": CALC, "mode": "append", "source": SECOND_ADD,
        "skipSyntaxCheck": True,
    })
    assert_ok(w, "skipSyntaxCheck=true writes a second Add")
    assert_contains(w.text, "syntaxCheck: skipped", "the check was skipped")
    assert_contains(w.text, "skipSyntaxCheck=true wrote a duplicate method name: 'Add'", "the forced write warns")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_a_duplicate_the_module_already_had_does_not_block_an_unrelated_edit():
    _seed_duplicate()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": CALC,
        "oldSource": "Add(1, 2);", "source": "Add(1, 3);",
    })
    assert_ok(r, "an unrelated edit of a module that already repeats Add")
    assert_contains(r.text, "already declared a method name twice before this write", "it warns")
    assert_contains(read_disk("src/" + CALC), "Add(1, 3);", "the edit is on disk")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_edt_marks_the_duplicate_the_forced_write_left():
    """The platform-side verdict the refusal stands for: EDT's validator marks the second declaration."""
    _seed_duplicate()
    call("revalidate_objects", {"projectName": PROJECT, "objects": [CALC_OWNER]})
    deadline = time.time() + MARKER_POLL_TIMEOUT
    last = ""
    while time.time() < deadline:
        r = call("get_project_errors", {"projectName": PROJECT, "modulePaths": [CALC]})
        assert_ok(r, "poll the module's problems")
        last = r.text or ""
        if "method-already-defined" in last or "уже определена" in last \
                or "already defined" in last:
            return
        time.sleep(2)
    raise E2EAssertion("EDT reported no method-already-defined problem for %s within %ds; last report:\n%s"
                       % (CALC, MARKER_POLL_TIMEOUT, last[:800]))


@e2e_test(tool="read_method_source", kind="write-metadata")
def test_read_method_source_lists_every_declaration_of_a_repeated_name():
    _seed_duplicate()
    r = call("read_method_source", {"projectName": PROJECT, "modulePath": CALC, "methodName": "ADD"})
    assert_ok(r, "read a repeated name")
    assert_contains(r.text, "duplicateDeclarations: [\"1-3\", ", "every declaration's lines are listed")
    assert_contains(r.text, "'Add' is declared 2 times in this module", "the answer says the name repeats")
    assert_contains(r.text, "shown: lines 1-3", "the answer says which declaration it shows")
    multi = call("read_method_source", {"projectName": PROJECT, "modulePath": CALC, "methodNames": ["Add", "Test"]})
    assert_ok(multi, "methodNames over a repeated name")
    assert_contains(multi.text, "'Add' is declared 2 times in this module", "the section says it too")
    assert_not_contains(multi.text, "'Test' is declared", "a name declared once carries no note")


@e2e_test(tool="get_module_structure", kind="write-metadata")
def test_get_module_structure_names_a_repeated_name():
    _seed_duplicate()
    r = call("get_module_structure", {"projectName": PROJECT, "modulePath": CALC})
    assert_ok(r, "structure of a module that repeats Add")
    assert_contains(r.text, "**Repeated method names.** Add (lines 1, ", "the repeated name is named with its lines")
    assert_contains(r.text, "the 1C platform will refuse to load the module", "and what it means")
