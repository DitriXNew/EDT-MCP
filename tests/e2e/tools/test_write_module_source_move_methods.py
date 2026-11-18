"""
e2e tests for moving methods between modules with write_module_source (#978).

A move is two calls and the method text never travels through the agent:
  1. copy  - modulePath = target, fromModulePath = source, methodNames = the methods;
             the response carries fromContentHash, the source's token for the text copied;
  2. delete - modulePath = source, mode=deleteMethods, the same methodNames,
             expectedHash = fromContentHash, so it deletes exactly what was copied.

Fixture modules (the orchestrator resets the fixture before every test):
  CommonModules/Calc       - Add(A, B) and Test(), where Test calls Add unqualified;
  CommonModules/DrySignal  - the same two methods;
  CommonModules/Вычисление - Маркер();
  CommonModules/OK         - empty.
"""

import os
import re

from harness import (
    call, assert_ok, assert_error, assert_error_quality, assert_contains, assert_not_contains,
    read_disk, tree_snapshot, assert_tree_unchanged, wait_for_project_ready,
    e2e_test, E2EAssertion, PROJECT, PROJECT_DIR,
)

CALC = "CommonModules/Calc/Module.bsl"
DRY = "CommonModules/DrySignal/Module.bsl"
RUSSIAN = "CommonModules/\u0412\u044b\u0447\u0438\u0441\u043b\u0435\u043d\u0438\u0435/Module.bsl"
MARKER = "\u041c\u0430\u0440\u043a\u0435\u0440"
OK = "CommonModules/OK/Module.bsl"

_HASH_RE = r'%s:\s*"?([0-9a-f]{16})"?'


def _field(result, key, ctx):
    assert_ok(result, ctx)
    m = re.search(_HASH_RE % key, result.text or "")
    if not m:
        raise E2EAssertion("%s: the response carries no %s:\n%s" % (ctx, key, (result.text or "")[:600]))
    return m.group(1)


def _read_hash(module, ctx):
    return _field(call("read_module_source", {"projectName": PROJECT, "modulePath": module}), "contentHash", ctx)


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_move_copies_into_a_new_module_and_deletes_with_the_source_token():
    name = "E2E978Target"
    target = "CommonModules/%s/Module.bsl" % name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "CommonModule." + name}),
              "seed the target common module")
    wait_for_project_ready()
    calc_before = read_disk("src/" + CALC)
    calc_hash = _read_hash(CALC, "read the source")
    # A common module created by create_metadata may or may not have its Module.bsl yet: replace creates it,
    # append adds to an existing one - the copy supports both.
    mode = "append" if os.path.exists(os.path.join(PROJECT_DIR, "src", *target.split("/"))) else "replace"
    copied = call("write_module_source", {
        "projectName": PROJECT, "modulePath": target, "mode": mode,
        "fromModulePath": CALC, "methodNames": ["Add"],
    })
    from_hash = _field(copied, "fromContentHash", "the copy answers fromContentHash")
    if from_hash != calc_hash:
        raise E2EAssertion("fromContentHash %s is not the source's read_module_source token %s" % (from_hash, calc_hash))
    assert_contains(copied.text, "copiedMethods", "the copy names the methods")
    disk = read_disk("src/" + target)
    assert_contains(disk, "Add(A, B)", "the method is in the target on disk")
    assert_not_contains(disk, "Test()", "only the named method is copied")
    if read_disk("src/" + CALC) != calc_before:
        raise E2EAssertion("the copy changed its source module")
    if _field(copied, "contentHash", "the copy answers the target's contentHash") != _read_hash(target, "read target"):
        raise E2EAssertion("the copy's contentHash is not the target's read_module_source token")

    deleted = call("write_module_source", {
        "projectName": PROJECT, "modulePath": CALC, "mode": "deleteMethods",
        "methodNames": ["Add"], "expectedHash": from_hash,
    })
    assert_ok(deleted, "delete with expectedHash=fromContentHash")
    assert_contains(deleted.text, "deletedMethods", "the deletion names the methods")
    assert_contains(deleted.text, "still called", "Test still calls Add: the deletion warns")
    calc_after = read_disk("src/" + CALC)
    assert_not_contains(calc_after, "Add(A, B)", "the method is gone from the source")
    assert_contains(calc_after, "Test()", "the other method stays")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_a_repeated_copy_is_refused_and_changes_nothing():
    first = call("write_module_source", {
        "projectName": PROJECT, "modulePath": OK, "fromModulePath": DRY, "methodNames": ["Add"],
    })
    assert_ok(first, "the first copy (append is the default)")
    assert_contains(read_disk("src/" + OK), "Add(A, B)", "the copy is on disk")
    before = tree_snapshot()
    again = call("write_module_source", {
        "projectName": PROJECT, "modulePath": OK, "fromModulePath": DRY, "methodNames": ["Add"],
    })
    err = assert_error(again, "the same copy again")
    assert_error_quality(err, names=["Add"], suggests=["deleteMethods"], ctx="a repeated copy names the method")
    assert_tree_unchanged(before, "a refused copy must not touch the project")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_a_russian_method_is_copied_by_its_russian_name():
    copied = call("write_module_source", {
        "projectName": PROJECT, "modulePath": OK, "fromModulePath": RUSSIAN, "methodNames": [MARKER.lower()],
    })
    assert_ok(copied, "copy by the Russian name, other letter case")
    assert_contains(copied.text, MARKER, "copiedMethods names the method as declared")
    assert_contains(read_disk("src/" + OK), MARKER + "()", "the Russian method is in the target")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_a_copy_with_a_missing_name_writes_nothing():
    before = tree_snapshot()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": OK, "fromModulePath": CALC, "methodNames": ["Add", "E2E978Nope"],
    })
    err = assert_error(r, "one of the names does not exist")
    assert_error_quality(err, names=["E2E978Nope"], ctx="the missing name is named")
    assert_contains(err, "Nothing was written", "all or nothing")
    assert_tree_unchanged(before, "the refused copy must not write the found method either")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_a_method_inside_an_if_block_is_not_copied():
    seed = "Procedure Outside() Export\nEndProcedure\n\n#If Server Then\nProcedure E2E978Inside() Export\nEndProcedure\n#EndIf\n"
    assert_ok(call("write_module_source", {
        "projectName": PROJECT, "modulePath": OK, "mode": "replace", "overwrite": True, "source": seed,
    }), "seed a module with a method inside #If")
    before = tree_snapshot()
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": DRY, "fromModulePath": OK, "methodNames": ["E2E978Inside"],
    })
    err = assert_error(r, "copy of a method inside #If")
    assert_error_quality(err, names=["E2E978Inside", "#If"], ctx="the condition would be dropped")
    assert_tree_unchanged(before, "the refused copy must not touch the project")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_the_deletion_is_refused_once_the_source_changed_after_the_copy():
    copied = call("write_module_source", {
        "projectName": PROJECT, "modulePath": OK, "fromModulePath": DRY, "methodNames": ["Add"],
    })
    token = _field(copied, "fromContentHash", "the copy")
    assert_ok(call("write_module_source", {
        "projectName": PROJECT, "modulePath": DRY, "mode": "append", "source": "// e2e_978_later\n",
    }), "the source changes after the copy")
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": DRY, "mode": "deleteMethods",
        "methodNames": ["Add"], "expectedHash": token,
    })
    err = assert_error(r, "delete with the copy's stale token")
    assert_error_quality(err, names=["expectedHash"], ctx="the stale token is named")
    assert_contains(read_disk("src/" + DRY), "Add(A, B)", "the refused deletion left the method in place")


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_deleting_every_method_leaves_an_empty_module():
    seeded = call("write_module_source", {
        "projectName": PROJECT, "modulePath": OK, "mode": "replace", "overwrite": True,
        "source": "Procedure E2E978Only() Export\nEndProcedure\n",
    })
    token = _field(seeded, "contentHash", "seed one method")
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": OK, "mode": "deleteMethods",
        "methodNames": ["E2E978Only"], "expectedHash": token,
    })
    assert_ok(r, "delete the only method")
    assert_contains(r.text, "linesAfter: 0", "the response says the module has no lines")
    disk = read_disk("src/" + OK).lstrip("\ufeff")
    if disk != "":
        raise E2EAssertion("the module must be empty after deleting every method, got %r" % disk[:80])
    if _field(r, "contentHash", "the deletion") != _read_hash(OK, "read the empty module"):
        raise E2EAssertion("the deletion's contentHash is not the read tool's for the empty module")
