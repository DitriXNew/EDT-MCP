"""
e2e tests for write_module_source's contentHash (#978).

Every successful write answers the contentHash of the text it wrote - the very token
read_module_source reports for the module right after - so an agent chains guarded edits
(expectedHash, REQUIRED for the method-targeted modes) on the write responses alone, without
reading the module again between them.

Fixture target: CommonModules/OK/Module.bsl (empty in the committed baseline; the orchestrator
resets the fixture before every test).
"""

import re

from harness import (
    call, assert_ok, assert_error, assert_error_quality, assert_contains, read_disk,
    e2e_test, E2EAssertion, PROJECT,
)

MODULE = "CommonModules/OK/Module.bsl"

_HASH_RE = re.compile(r'contentHash:\s*"?([0-9a-f]{16})"?')


def _hash_of(result, ctx):
    """The contentHash a response's frontmatter carries (or fail)."""
    assert_ok(result, ctx)
    m = _HASH_RE.search(result.text or "")
    if not m:
        raise E2EAssertion("%s: the response carries no contentHash:\n%s" % (ctx, (result.text or "")[:400]))
    return m.group(1)


def _read_hash(ctx):
    return _hash_of(call("read_module_source", {"projectName": PROJECT, "modulePath": MODULE}), ctx)


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_write_answers_the_content_hash_read_module_source_reports():
    r = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE,
        "mode": "append", "source": "// e2e_978_append\n",
    })
    written = _hash_of(r, "append answers a contentHash")
    assert_contains(read_disk("src/" + MODULE), "// e2e_978_append", "the append is on disk")
    read = _read_hash("read after the append")
    if written != read:
        raise E2EAssertion("the write answered contentHash %s, read_module_source reports %s" % (written, read))


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_method_edits_chain_on_the_returned_hashes_without_a_read():
    seed = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replace", "overwrite": True,
        "source": "Procedure Target() Export\n\tValue = 1;\nEndProcedure\n",
    })
    token = _hash_of(seed, "the seeding replace answers a contentHash")
    # No read between the writes: each guarded edit carries the token the previous write answered.
    replaced = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceMethod", "methodName": "Target",
        "expectedHash": token,
        "source": "Procedure Target() Export\n\tValue = 2;\nEndProcedure\n",
    })
    token = _hash_of(replaced, "replaceMethod with the seed's contentHash is accepted")
    inserted = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "insertAfter", "methodName": "Target",
        "expectedHash": token,
        "source": "Procedure E2E978Added() Export\nEndProcedure\n",
    })
    token = _hash_of(inserted, "insertAfter with replaceMethod's contentHash is accepted")
    disk = read_disk("src/" + MODULE)
    assert_contains(disk, "Value = 2;", "the replaced method is on disk")
    assert_contains(disk, "Procedure E2E978Added() Export", "the inserted method is on disk")
    read = _read_hash("read after the chain")
    if token != read:
        raise E2EAssertion("the last write answered contentHash %s, read_module_source reports %s" % (token, read))


@e2e_test(tool="write_module_source", kind="write-metadata")
def test_the_returned_hash_goes_stale_once_the_module_changes_again():
    first = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replace", "overwrite": True,
        "source": "Procedure Target() Export\nEndProcedure\n",
    })
    stale = _hash_of(first, "the first write answers a contentHash")
    second = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "append", "source": "// e2e_978_later\n",
    })
    assert_ok(second, "a later write")
    refused = call("write_module_source", {
        "projectName": PROJECT, "modulePath": MODULE, "mode": "replaceMethod", "methodName": "Target",
        "expectedHash": stale, "source": "Procedure Target() Export\n\tValue = 9;\nEndProcedure\n",
    })
    err = assert_error(refused, "an edit carrying the first write's token after a second write")
    assert_error_quality(err, names=["expectedHash"], ctx="the stale token is named")
    if "Value = 9;" in read_disk("src/" + MODULE):
        raise E2EAssertion("the refused edit reached the disk")
