"""
e2e tests for set_form_item_property (kind: write-metadata).

The tool sets the Title (bilingual), Visible flag and/or ReadOnly flag of an
EXISTING form ITEM (a field / group / button / decoration / table) of a managed
form, addressed by its itemId - the programmatic Name that get_form_structure
lists - inside a BM WRITE transaction (AbstractMetadataWriteTool ->
BmTransactions.write). The form model is mutated reflectively (EObject/EClass/
eSet) so the bundle needs no form-model bundle dependency. title is an
EMap<String,String> keyed by the language CODE on the Titled supertype; visible
is a plain EBoolean on the Visible supertype; readOnly is a plain EBoolean only on
fields/groups/tables. After the write commits, the editable form (its own top
object, serialized to Form.form) is force-exported to disk.

FIXTURE TARGET
--------------
The committed fixture CommonForm.Form
(TestConfiguration/src/CommonForms/Form/Form.form, a managed form) starts with
exactly ONE item: a Decoration named "OK" (id 1) whose title EMap is en->"OK" and
whose <visible> is true. So:
  - itemId "OK" resolves the only item -> a happy title/visible change is provable.
  - a Decoration is Titled + Visible but has NO readOnly property, so it is the
    perfect target for the "readOnly not settable on this item type" negative.
The addressing FQN is "CommonForm.Form" (a CommonForm IS a BasicForm), matching
test_get_form_structure.py / test_add_form_attribute.py.

HOW THE EFFECT IS VERIFIED (two independent channels)
-----------------------------------------------------
  1) MODEL READ-BACK over the wire: after a title write we call get_form_structure
     and assert the OK item now renders with the NEW title (it was "OK" before). A
     no-op write leaves "title: OK" -> the test FAILS.
  2) ON DISK: the change is force-exported to Form.form, so the happy tests ALSO
     assert it lands on disk via poll_diff_contains (a unique <value>...</value>
     for the title, or "<visible>false</visible>" for the flag). A write that
     mutated only the in-memory model would FAIL the on-disk check.

  The orchestrator reverts the on-disk Form.form via reset_fixture (git) and
  resyncs the model via reset_model() (clean_project) AFTER each write-metadata
  test, so every test starts clean. We never reset ourselves.

A second write inside one test waits for the project to settle first
(wait_for_project_ready): the first set force-exports the form, which kicks off a
derived-data rebuild, and a second mutation run too early hits the (correct)
BUILDING guard before reaching the real logic - the exact issue hit in
add_form_attribute's duplicate test.

Negative matrix (whole-call errors; server sets isError via ToolResult.error):
  missing projectName / formPath / itemId; none of title/visible/readOnly;
  non-existent project / form / item; readOnly on an item type without it. Each
  rejected write additionally asserts assert_no_diff() (a rejected write must not
  touch disk).
"""

import time

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    poll_diff_contains,
    wait_for_project_ready,
    diff,
    e2e_test,
    PROJECT,
)


def _poll_diff_changed_line(substr, timeout=10, ctx=""):
    """Poll until the on-disk git diff has a CHANGED line (added '+' or removed '-')
    containing substr. Used for a flag write whose serialization may either ADD the
    new value (e.g. <visible>false</visible>) OR REMOVE the old one
    (<visible>true</visible>) depending on the form serializer's default-omission -
    both show up as a changed diff line carrying substr, so either outcome passes,
    while an unchanged context line (leading ' ') does not."""
    deadline = time.time() + timeout
    while True:
        for line in diff().splitlines():
            if line[:1] in ("+", "-") and line[1:2] not in ("+", "-") and substr in line:
                return
        if time.time() >= deadline:
            break
        time.sleep(0.5)
    raise AssertionError("expected a changed diff line containing %r [%s]; diff:\n%s"
                         % (substr, ctx, diff()[:600]))

# Addressing FQN + the fixture item id (same as test_get_form_structure.py).
FORM_FQN = "CommonForm.Form"
ITEM_ID = "OK"  # the only item: a Decoration, id 1, title(en)="OK", visible=true


def _structure(form_path, language=None):
    """Read the form structure back (MARKDOWN). The OK item renders as
    '- OK (type: Decoration, id: 1, title: <title>)'."""
    args = {"projectName": PROJECT, "formPath": form_path}
    if language is not None:
        args["language"] = language
    return call("get_form_structure", args)


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths — write, then VERIFY VIA MODEL READ-BACK + ON-DISK
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="set_form_item_property", kind="write-metadata")
def test_set_title_appears_in_readback_and_on_disk():
    new_title = "E2EItemTitle"

    # Precondition: the OK item's en-title is "OK", so the probe title is absent and
    # the read-back will prove the change (not be a self-fulfilling pass).
    before = _structure(FORM_FQN)
    assert_ok(before, "read form structure before title set")
    assert_contains(before.text, "- OK (type: Decoration, id: 1, title: OK)",
        "fixture OK item must start with title 'OK'")
    assert_not_contains(before.text, new_title,
        "probe title must be absent before the set")

    r = call("set_form_item_property", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
        "title": new_title,
    })
    assert_ok(r, "set_form_item_property title on CommonForm.Form/OK")

    # 1) MODEL READ-BACK: the OK item now renders with the NEW title. The default
    # config language is en, so no `language` arg reads the en-keyed title we wrote.
    after = _structure(FORM_FQN)
    assert_ok(after, "read form structure after title set")
    assert_contains(after.text, "title: %s" % new_title,
        "the OK item must render with the newly set title")

    # 2) ON DISK: the new title value is serialized into Form.form as a <value>.
    # The probe value is unique, so the match is unambiguous.
    poll_diff_contains("<value>%s</value>" % new_title,
        ctx="set_form_item_property must serialize the new title into Form.form")


@e2e_test(tool="set_form_item_property", kind="write-metadata")
def test_set_bilingual_titles():
    # Two title writes for the SAME item in two languages (ru then en). The title
    # EMap is keyed by the language CODE, so the two entries are independent and the
    # ru write must NOT remove a later en write (and vice-versa). Both land on disk.
    ru_title = "E2EItemTitleRu"
    en_title = "E2EItemTitleEn"

    first = call("set_form_item_property", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
        "title": ru_title,
        "language": "ru",
    })
    assert_ok(first, "set ru title on OK")

    # The first set force-exports the form -> a derived-data rebuild. Wait for the
    # project to settle, else the second set hits the BUILDING guard before the
    # in-transaction logic (the issue hit in add_form_attribute's duplicate test).
    wait_for_project_ready()

    second = call("set_form_item_property", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
        "title": en_title,
        "language": "en",
    })
    assert_ok(second, "set en title on OK")

    # ON DISK: BOTH language values must be present in Form.form (the ru entry was
    # not clobbered by the en write). The values are unique to this test.
    poll_diff_contains("<value>%s</value>" % en_title,
        ctx="the en title must persist to Form.form")
    poll_diff_contains("<value>%s</value>" % ru_title,
        ctx="the ru title must still be present (additive per language code)")


@e2e_test(tool="set_form_item_property", kind="write-metadata")
def test_set_visible_false_persists_to_disk():
    # visible is a plain EBoolean on the Visible supertype; a Decoration has it. The
    # fixture OK item is <visible>true</visible>, so setting it false produces a
    # unique "<visible>false</visible>" on disk (the fixture has no other false
    # visible). visible=false must be a real SET (the present-key sentinel), not lost
    # as a default. get_form_structure does not render visibility, so the proof is
    # on disk.
    r = call("set_form_item_property", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
        "visible": False,
    })
    assert_ok(r, "set visible=false on OK")
    # The tool echoes the written flag in structuredContent.
    if r.structured is not None:
        assert r.structured.get("visible") is False, \
            "result must echo the written visible=false (present-key sentinel honored)"

    # ON DISK: the OK item is the ONLY item with a <visible> line, so a CHANGED
    # <visible> line is unambiguous. Whether the serializer writes the new
    # <visible>false</visible> OR drops the old <visible>true</visible> (EBoolean
    # default-omission), the change shows as a +/- diff line carrying "<visible>".
    _poll_diff_changed_line("<visible>",
        ctx="set_form_item_property must change the OK item's <visible> in Form.form")


@e2e_test(tool="set_form_item_property", kind="write-metadata")
def test_russian_type_token_resolves_same_form():
    # The formPath TYPE token is bilingual: "ОбщаяФорма" (Russian for CommonForm)
    # normalizes to "CommonForm" and resolves to the SAME form/item. A regression in
    # the bilingual type resolver would make this FQN miss -> "Form not found".
    russian_fqn = "ОбщаяФорма.Form"  # ОбщаяФорма == CommonForm
    new_title = "E2EItemTitleRuToken"

    r = call("set_form_item_property", {
        "projectName": PROJECT,
        "formPath": russian_fqn,
        "itemId": ITEM_ID,
        "title": new_title,
    })
    assert_ok(r, "set title via Russian type token")

    # Verify against the English-token view of the SAME form.
    after = _structure(FORM_FQN)
    assert_ok(after, "read CommonForm.Form after Russian-token set")
    assert_contains(after.text, "title: %s" % new_title,
        "the Russian type token must resolve to the same form/item")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — missing required params (whole-call errors)
# Each rejected write must also leave the project clean on disk.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="set_form_item_property", kind="write-metadata")
def test_missing_project_name_is_error():
    r = call("set_form_item_property", {
        # projectName omitted on purpose
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
        "title": "X",
    })
    e = assert_error(r, "missing projectName")
    assert_error_quality(e, names=["projectName"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="set_form_item_property", kind="write-metadata")
def test_missing_form_path_is_error():
    r = call("set_form_item_property", {
        "projectName": PROJECT,
        # formPath omitted on purpose
        "itemId": ITEM_ID,
        "title": "X",
    })
    e = assert_error(r, "missing formPath")
    assert_error_quality(e, names=["formPath"], suggests=["required", "CommonForm.MyForm"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="set_form_item_property", kind="write-metadata")
def test_missing_item_id_is_error():
    r = call("set_form_item_property", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        # itemId omitted on purpose
        "title": "X",
    })
    e = assert_error(r, "missing itemId")
    assert_error_quality(e, names=["itemId"], suggests=["required", "get_form_structure"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="set_form_item_property", kind="write-metadata")
def test_no_editable_property_is_error():
    # All required args present but NONE of title/visible/readOnly -> the at-least-one
    # guard fires BEFORE any model access and must name the three options.
    r = call("set_form_item_property", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
    })
    e = assert_error(r, "no editable property supplied")
    assert_error_quality(e, names=["title", "visible", "readOnly"], suggests=["at least one"])
    assert_no_diff("a rejected call must not change the project")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — not-found / not-settable
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="set_form_item_property", kind="write-metadata")
def test_nonexistent_project_is_error():
    bogus = "NoSuchProject_zzz"
    r = call("set_form_item_property", {
        "projectName": bogus,
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
        "title": "X",
    })
    e = assert_error(r, "non-existent project")
    # AbstractMetadataWriteTool.resolveProjectAndConfig -> "Project not found: <name>".
    assert_error_quality(e, names=[bogus], suggests=["not found"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="set_form_item_property", kind="write-metadata")
def test_nonexistent_form_is_error():
    # Correct type token + correct FQN shape, but no such CommonForm exists ->
    # resolveMdForm returns null -> "Form not found: <formPath>. ... Names are the
    # programmatic Name, not the synonym. Use get_form_structure to inspect the form."
    bad = "CommonForm.NoSuchForm_e2e"
    r = call("set_form_item_property", {
        "projectName": PROJECT,
        "formPath": bad,
        "itemId": ITEM_ID,
        "title": "X",
    })
    e = assert_error(r, "non-existent form")
    assert_error_quality(e, names=[bad], suggests=["not the synonym", "get_form_structure"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="set_form_item_property", kind="write-metadata")
def test_nonexistent_item_is_error():
    # Real form, but no item with that Name -> the in-transaction findItem returns
    # null -> "Failed to set form item property: Item not found: <itemId>". The names
    # the bad id; the description/guide steer to get_form_structure to list ids.
    bad_item = "NoSuchItem_e2e"
    r = call("set_form_item_property", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "itemId": bad_item,
        "title": "X",
    })
    e = assert_error(r, "non-existent item")
    assert_error_quality(e, names=[bad_item], suggests=["not found"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="set_form_item_property", kind="write-metadata")
def test_readonly_on_decoration_is_error():
    # The fixture OK item is a Decoration, which is Titled + Visible but has NO
    # readOnly property. Setting readOnly on it must FAIL CLOSED with a clear error
    # (naming the item + that only fields/groups/tables are read-only-able), not
    # silently no-op. This validates the per-item-type feature guard.
    r = call("set_form_item_property", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "itemId": ITEM_ID,
        "readOnly": True,
    })
    e = assert_error(r, "readOnly on a decoration")
    assert_error_quality(e, names=[ITEM_ID], suggests=["readOnly"])
    assert_no_diff("a rejected call must not change the project")
