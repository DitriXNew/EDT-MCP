"""
e2e tests for delete_form_item (kind: write-metadata).

The tool DELETES a visual ITEM (a field / group / table / button / decoration) of
a managed form, addressed by its itemId - the programmatic Name that
get_form_structure lists - via a mandatory CONFIRM-PREVIEW: a call WITHOUT
confirm returns a PREVIEW of what would be removed (the item, its type, and any
contained descendant items + a count) and makes NO model change at all (it opens
NO write transaction). Only confirm:true performs the delete, inside a BM WRITE
transaction (AbstractMetadataWriteTool -> BmTransactions.write): the item is
removed from its parent container's `items` collection via EcoreUtil.remove (which
cascades the contained subtree, because `items` is a containment reference). After
the write commits, the editable form (its own top object, serialized to Form.form)
is force-exported to disk. The form model is read/mutated reflectively
(EObject/EClass/eGet) so the bundle needs no form-model bundle dependency.

FIXTURE TARGET
--------------
The committed fixture CommonForm.Form
(TestConfiguration/src/CommonForms/Form/Form.form, a managed form) starts with
exactly ONE item: a Decoration named "OK" (id 1). So:
  - itemId "OK" resolves the only item -> a happy preview + delete is provable.
  - the OK item is a LEAF (no contained sub-items), so the preview's
    descendantCount is 0 and deleting it leaves an EMPTY form (which still
    serializes - see test_delete_leaves_empty_form_serializable).
The addressing FQN is "CommonForm.Form" (a CommonForm IS a BasicForm), matching
test_get_form_structure.py / test_set_form_item_property.py.

HOW THE DELETION IS VERIFIED (deletions are harder to assert than additions)
----------------------------------------------------------------------------
Unlike an ADD (where poll_diff_contains can match a unique NEW marker), a deletion
must assert the item is GONE. Two independent channels:
  1) MODEL READ-BACK over the wire: after confirm:true we call get_form_structure
     and assert the "- OK (type: Decoration, ...)" item line is NO LONGER present.
     A no-op delete would leave it -> the test FAILS. The read-back is
     mutation-sensitive (it reads the in-memory BM model).
  2) ON DISK: the deletion is force-exported to Form.form, so the OK item's
     "<name>OK</name>" element (unique to the OK item - the nested OKExtendedTooltip
     / OKContextMenu carry DIFFERENT <name> values) must DISAPPEAR from the file.
     poll_disk_lacks polls until the file no longer contains it. A delete that
     mutated only the in-memory model would FAIL the on-disk check.

The PREVIEW must NOT mutate: every preview-only call additionally asserts
assert_no_diff() AND that get_form_structure still lists the OK item.

The orchestrator reverts the on-disk Form.form via reset_fixture (git) and
resyncs the model via reset_model() (clean_project) AFTER each write-metadata
test, so every test starts clean. We never reset ourselves. Each test here does
at most ONE write, so no in-test wait_for_project_ready is needed.

Negative matrix (whole-call errors; server sets isError via ToolResult.error):
  missing projectName / formPath / itemId; non-existent project / form / item.
Each rejected call additionally asserts assert_no_diff() (a rejected call must
not touch disk).
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    poll_disk_lacks,
    e2e_test,
    PROJECT,
)

# Addressing FQN + the fixture item id (same as test_get_form_structure.py).
FORM_FQN = "CommonForm.Form"
ITEM_ID = "OK"  # the only item: a Decoration, id 1, a leaf (no contained sub-items)

# The OK item's <name> element in Form.form. Unique to the OK item itself: the
# nested OKExtendedTooltip / OKContextMenu have their OWN <name> values, so this
# exact string disappears only when the OK item is removed.
OK_NAME_MARKER = "<name>OK</name>"
# Path to the fixture form file (relative to the project dir, for poll_disk_lacks).
FORM_FILE = "src/CommonForms/Form/Form.form"

# The OK item's line in get_form_structure's MARKDOWN item outline.
OK_ITEM_LINE = "- OK (type: Decoration, id: 1"


def _structure(form_path=FORM_FQN):
    """Read the form structure back (MARKDOWN). The OK item renders as
    '- OK (type: Decoration, id: 1, title: OK)'."""
    return call("get_form_structure", {"projectName": PROJECT, "formPath": form_path})


# ──────────────────────────────────────────────────────────────────────────────
# PREVIEW (confirm omitted) — must NOT mutate
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_form_item", kind="write-metadata")
def test_preview_without_confirm_does_not_mutate():
    # Precondition: the OK item exists.
    before = _structure()
    assert_ok(before, "read form structure before preview")
    assert_contains(before.text, OK_ITEM_LINE, "fixture OK item must exist before preview")

    r = call("delete_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
        # confirm omitted -> PREVIEW only
    })
    assert_ok(r, "preview delete_form_item (no confirm)")

    # The preview is a SUCCESS (not an error) that flags confirmation-required and
    # names the targeted item. The placeholder text channel carries the message; the
    # structured channel carries the machine fields.
    blob = (r.text or "")
    if r.structured is not None:
        blob = blob + " " + str(r.structured)
    assert_contains(blob, "confirm", "preview must instruct to re-call with confirm")
    assert_contains(blob, ITEM_ID, "preview must name the targeted item")
    if r.structured is not None:
        assert r.structured.get("action") == "preview", \
            "preview must report action='preview'"
        assert r.structured.get("confirmationRequired") is True, \
            "preview must set confirmationRequired=true"
        # OK is a leaf: no contained descendants.
        assert r.structured.get("descendantCount") == 0, \
            "the OK leaf decoration has no contained descendants"

    # AIRTIGHT: a preview must change NOTHING — not on disk, not in the read-back.
    assert_no_diff("a preview must not touch disk")
    after = _structure()
    assert_ok(after, "read form structure after preview")
    assert_contains(after.text, OK_ITEM_LINE,
        "the OK item must STILL be present after a preview-only call")


# ──────────────────────────────────────────────────────────────────────────────
# DELETE (confirm:true) — item GONE from read-back AND from disk
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_form_item", kind="write-metadata")
def test_delete_with_confirm_removes_item():
    # Precondition: the OK item exists in the read-back and on disk.
    before = _structure()
    assert_ok(before, "read form structure before delete")
    assert_contains(before.text, OK_ITEM_LINE, "fixture OK item must exist before delete")

    r = call("delete_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
        "confirm": True,
    })
    assert_ok(r, "delete_form_item with confirm:true")
    if r.structured is not None:
        assert r.structured.get("action") == "deleted", \
            "a confirmed call must report action='deleted'"
        assert r.structured.get("persisted") is True, \
            "the deletion must be persisted to disk"

    # 1) MODEL READ-BACK: the OK item is no longer listed (mutation-sensitive).
    after = _structure()
    assert_ok(after, "read form structure after delete")
    assert_not_contains(after.text, OK_ITEM_LINE,
        "the OK item must be GONE from the form structure read-back after delete")

    # 2) ON DISK: the OK item's <name>OK</name> element must disappear from Form.form.
    # poll_disk_lacks polls (the export can lag a beat after the call returns).
    poll_disk_lacks(FORM_FILE, OK_NAME_MARKER,
        ctx="delete_form_item must remove the OK item from Form.form on disk")


@e2e_test(tool="delete_form_item", kind="write-metadata")
def test_delete_leaves_empty_form_serializable():
    # Deleting the fixture's ONLY item leaves an EMPTY form. This must still
    # serialize (no crash) and the form must read back as having no items. If an
    # empty form failed to serialize/validate, the delete would error or the
    # read-back would be unavailable.
    r = call("delete_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
        "confirm": True,
    })
    assert_ok(r, "delete the only item -> empty form")

    # The form must still be readable and now have no items.
    after = _structure()
    assert_ok(after, "an emptied form must still read back (it serialized)")
    assert_not_contains(after.text, OK_ITEM_LINE, "the removed item must be gone")
    # get_form_structure renders an empty items section as '_(no items)_'.
    assert_contains(after.text, "_(no items)_",
        "the emptied form must render an empty items section")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — missing required params (whole-call errors)
# Each rejected call must also leave the project clean on disk.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_form_item", kind="write-metadata")
def test_missing_project_name_is_error():
    r = call("delete_form_item", {
        # projectName omitted on purpose
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
    })
    e = assert_error(r, "missing projectName")
    assert_error_quality(e, names=["projectName"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="delete_form_item", kind="write-metadata")
def test_missing_form_path_is_error():
    r = call("delete_form_item", {
        "projectName": PROJECT,
        # formPath omitted on purpose
        "itemId": ITEM_ID,
    })
    e = assert_error(r, "missing formPath")
    assert_error_quality(e, names=["formPath"], suggests=["required", "CommonForm.MyForm"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="delete_form_item", kind="write-metadata")
def test_missing_item_id_is_error():
    r = call("delete_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        # itemId omitted on purpose
    })
    e = assert_error(r, "missing itemId")
    assert_error_quality(e, names=["itemId"], suggests=["required", "get_form_structure"])
    assert_no_diff("a rejected call must not change the project")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — not-found
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_form_item", kind="write-metadata")
def test_nonexistent_project_is_error():
    bogus = "NoSuchProject_zzz"
    r = call("delete_form_item", {
        "projectName": bogus,
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
        "confirm": True,
    })
    e = assert_error(r, "non-existent project")
    # AbstractMetadataWriteTool.resolveProjectAndConfig -> "Project not found: <name>".
    assert_error_quality(e, names=[bogus], suggests=["not found"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="delete_form_item", kind="write-metadata")
def test_nonexistent_form_is_error():
    # Correct type token + correct FQN shape, but no such CommonForm exists ->
    # resolveMdForm returns null -> "Form not found: <formPath>. ... Use
    # get_form_structure to inspect the form."
    bad = "CommonForm.NoSuchForm_e2e"
    r = call("delete_form_item", {
        "projectName": PROJECT,
        "formPath": bad,
        "itemId": ITEM_ID,
        "confirm": True,
    })
    e = assert_error(r, "non-existent form")
    assert_error_quality(e, names=[bad], suggests=["not the synonym", "get_form_structure"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="delete_form_item", kind="write-metadata")
def test_nonexistent_item_preview_is_error():
    # Real form, but no item with that Name. Even on a PREVIEW (no confirm) the
    # missing item must be reported as an error (the preview must walk a real item),
    # and nothing must change on disk. The error names the bad id and steers to
    # get_form_structure to list the valid ids.
    bad_item = "NoSuchItem_e2e"
    r = call("delete_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "itemId": bad_item,
        # no confirm: a preview of a missing item is still an error
    })
    e = assert_error(r, "non-existent item (preview)")
    assert_error_quality(e, names=[bad_item], suggests=["not found", "get_form_structure"])
    assert_no_diff("a rejected preview must not change the project")


@e2e_test(tool="delete_form_item", kind="write-metadata")
def test_nonexistent_item_confirm_is_error():
    # Same missing item, but WITH confirm:true. The in-transaction findItem returns
    # null -> "Failed to delete form item: Item not found: <itemId>". A rejected
    # delete must not touch disk.
    bad_item = "NoSuchItem_e2e_confirm"
    r = call("delete_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "itemId": bad_item,
        "confirm": True,
    })
    e = assert_error(r, "non-existent item (confirm)")
    assert_error_quality(e, names=[bad_item], suggests=["not found"])
    assert_no_diff("a rejected delete must not change the project")
