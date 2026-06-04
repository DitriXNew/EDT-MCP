"""
e2e tests for add_form_item (kind: write-metadata).

The tool adds a visual ITEM - a `FormGroup` container or a `Decoration` label (what
get_form_structure lists under `## Items`) - to an existing managed form, optionally
nested under an existing container via `parentId`, inside a BM WRITE transaction
(AbstractMetadataWriteTool -> BmTransactions.write). The form model is mutated
reflectively (EObject/EClass/EFactory): the concrete item EClass (FormGroup /
Decoration) is looked up by classifier NAME on the form EPackage, its `type` enum
(UsualGroup / Label) and a default `extInfo` (UsualGroupExtInfo /
LabelDecorationExtInfo) are set, the name + bilingual title are written, and the item
is appended to the chosen container's `items` collection - the exact pattern proven by
add_form_attribute / add_form_command / set_form_item_property. After the write commits
the editable form (its own top object, serialized to Form.form) is force-exported to
disk.

IMPLEMENTED itemTypes: `group`, `decoration`. RESERVED (rejected): `field`, `button`
(they need a data/command binding - a complex containment chain in the form model -
which this version does not build; add those in the EDT form editor).

FIXTURE TARGET
--------------
The committed fixture CommonForm.Form
(TestConfiguration/src/CommonForms/Form/Form.form, a managed form) has a single
top-level Decoration "OK" and a "FormCommandBar" - and NO group. So a probe item name
is provably absent before the add, and a no-op / broken write leaves the read-back
unchanged so the test FAILS. The addressing FQN is "CommonForm.Form" (a CommonForm IS a
BasicForm), matching test_add_form_command.py / test_add_form_attribute.py.

HOW THE EFFECT IS VERIFIED (two independent channels)
-----------------------------------------------------
  1) MODEL READ-BACK over the wire: after the write we call get_form_structure on the
     same form and assert the new item name now appears under the "## Items" section
     (absent before). For nesting, get_form_structure renders child items INDENTED two
     spaces under their parent ("- Parent (...)" then "  - Child (...)"), so the nesting
     assertion checks the child renders with that leading indentation - proving it sits
     UNDER the parent group, not at the form root.
  2) ON DISK: the add is force-exported into the form's Form.form, so the happy tests
     ALSO assert the item lands on disk via poll_diff_contains as a "<name>...</name>"
     element (the export can lag a beat after the call returns, hence poll not a bare
     diff). The probe names are unique (the fixture's only other <name> elements are
     OK / OKExtendedTooltip / OKContextMenu / FormCommandBar), so the match is
     unambiguous.

  The orchestrator reverts the on-disk Form.form via reset_fixture (git) and resyncs
  the model via reset_model() (clean_project) AFTER each write-metadata test, so every
  test starts clean. We never reset ourselves.

ERROR-QUALITY note: ToolResult.toJson() uses Gson with HTML-escaping DISABLED, so the
JSON text channel carries raw characters (apostrophes, < > & are not \\uXXXX-escaped).
Assertions still match only delimiter-free substrings (e.g. "CommonForm.Form",
"reserved", "not found"), never a quoted 'name' - that stays robust either way.

Negative matrix (whole-call errors; server sets isError via ToolResult.error):
  missing projectName / formPath / name / itemType; bad itemType enum value; reserved
  field/button itemType; non-existent parentId; non-existent project / form. Each
  rejected write additionally asserts assert_no_diff() (a rejected write must not touch
  disk).
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
    e2e_test,
    PROJECT,
)

# Addressing FQN for the fixture managed CommonForm (same as test_add_form_command.py).
FORM_FQN = "CommonForm.Form"


def _structure(form_path, language=None):
    """Read the form structure back (MARKDOWN; the Items section renders each item as a
    nested outline line, child items indented two spaces under their parent)."""
    args = {"projectName": PROJECT, "formPath": form_path}
    if language is not None:
        args["language"] = language
    return call("get_form_structure", args)


def _poll_structure_contains(form_path, needle, timeout=20, ctx=""):
    """Poll get_form_structure until `needle` shows up in the model read-back.

    A form write force-exports Form.form, which kicks off an async derived-data rebuild.
    list_projects can read 'not building' in the brief lag BEFORE that rebuild flips the
    state, so wait_for_project_ready() may return in a false-ready window while a
    reload-from-disk is still in flight - momentarily dropping a just-added item from the
    model. Polling the MODEL itself (not the project state) is the race-free precondition
    before a dependent SECOND write that resolves the first item by name."""
    deadline = time.time() + timeout
    r = _structure(form_path)
    while time.time() < deadline and (r.is_error or needle not in (r.text or "")):
        time.sleep(1)
        r = _structure(form_path)
    assert_ok(r, ctx)
    assert_contains(r.text, needle, ctx)
    return r


def _call_settled(tool, args, attempts=4):
    """Call a tool, treating a transient BUILDING-state response as 'retry', not a verdict.

    AbstractMetadataWriteTool refuses to touch the model while derived data is rebuilding
    (ProjectStateChecker), and that base-class guard fires BEFORE the tool's own input
    validation. A negative test must assert the tool's OWN error (bad enum / reserved
    type), not the infra guard, so we wait for the project to settle and retry while the
    response is the transient 'Project is building ... Please wait and retry'."""
    wait_for_project_ready()
    r = call(tool, args)
    for _ in range(attempts - 1):
        if not (r.is_error and "building" in (r.error_text() or "").lower()):
            return r
        wait_for_project_ready()
        time.sleep(1)
        r = call(tool, args)
    return r


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths — write, then VERIFY VIA MODEL READ-BACK + ON-DISK
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="add_form_item", kind="write-metadata")
def test_add_group_at_root_appears_in_readback_and_on_disk():
    new_item = "E2ERootGroup"

    # Precondition: the probe is absent before the add (else the read-back proves nothing).
    before = _structure(FORM_FQN)
    assert_ok(before, "read form structure before group add")
    assert_not_contains(before.text, new_item,
        "probe group must be absent before the add")

    r = call("add_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": new_item,
        "itemType": "group",
    })
    assert_ok(r, "add_form_item group at root")

    # 1) MODEL READ-BACK: the new group now renders under ## Items. A no-op write leaves
    # it absent -> FAILS. A FormGroup renders with type FormGroup in the outline line.
    after = _structure(FORM_FQN)
    assert_ok(after, "read form structure after group add")
    assert_contains(after.text, new_item,
        "the newly added FORM group must appear in the form structure read-back")

    # 2) ON DISK: the group must be serialized into Form.form as a <name> element.
    poll_diff_contains("<name>%s</name>" % new_item,
        ctx="add_form_item group must serialize the item as a <name> element in Form.form")

    # 3) VALID ID (regression guard): the new item must get a unique non-zero id that does
    # NOT collide with any existing form item. A fresh item left at id=0 trips EDT's
    # form-invalid-item-id check, whose marker text is "Invalid form item identifier"; an id
    # that duplicates an existing item (e.g. a sibling decoration's contextMenu /
    # extendedTooltip id, which live OUTSIDE the items subtree) trips the same check with
    # "Form item identifier is duplicated by another form item: N". Both are MAJOR/ERROR
    # markers. Wait for the post-write revalidation to settle, then assert NEITHER surfaced —
    # this guards the form-wide id-assignment fix (it would FAIL if the tool appended the item
    # with id=0, OR with an id colliding with a non-items form item).
    wait_for_project_ready()
    errs = call("get_project_errors", {"projectName": PROJECT})
    assert_ok(errs, "read project errors after the add")
    assert_not_contains(errs.text, "Invalid form item identifier",
        "the added form item must carry a valid non-zero id (no form-invalid-item-id error)")
    assert_not_contains(errs.text, "duplicated by another form item",
        "the added form item id must not collide with any existing form item, incl. a "
        "sibling's contextMenu/extendedTooltip that lives outside the items subtree")


@e2e_test(tool="add_form_item", kind="write-metadata")
def test_add_decoration_at_root_appears_in_readback_and_on_disk():
    new_item = "E2ERootDeco"

    before = _structure(FORM_FQN)
    assert_ok(before, "read form structure before decoration add")
    assert_not_contains(before.text, new_item, "probe decoration absent before the add")

    r = call("add_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": new_item,
        "itemType": "decoration",
    })
    assert_ok(r, "add_form_item decoration at root")

    after = _structure(FORM_FQN)
    assert_ok(after, "read form structure after decoration add")
    assert_contains(after.text, new_item,
        "the newly added decoration must appear in the form structure read-back")

    poll_diff_contains("<name>%s</name>" % new_item,
        ctx="add_form_item decoration must serialize the item to Form.form")


@e2e_test(tool="add_form_item", kind="write-metadata")
def test_add_item_under_parent_group_nests_in_readback():
    # Two-step: create a group at the root, wait for the project to settle (the export
    # kicks off a derived-data rebuild; the BUILDING guard would otherwise reject the
    # second write), then add a decoration UNDER it via parentId. The read-back must show
    # the child INDENTED under the parent (get_form_structure renders children with two
    # leading spaces), proving the nesting really happened (not a root-level add).
    parent = "E2EParentGroup"
    child = "E2EChildDeco"

    rp = call("add_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": parent,
        "itemType": "group",
    })
    assert_ok(rp, "create the parent group")

    # The first add force-exports the form -> derived-data rebuild. Wait before the second
    # write (the BUILDING-guard gotcha), else the (correct) guard rejects it before the
    # parentId resolution we are actually testing. wait_for_project_ready can return in a
    # false-ready window though (the rebuild has not flipped the state yet), so additionally
    # poll the MODEL until the parent itself is visible again - that is the real precondition
    # the second write depends on (it resolves the parent by name).
    wait_for_project_ready()
    _poll_structure_contains(FORM_FQN, parent,
        ctx="the parent group must settle back into the model before the nested add")

    rc = call("add_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": child,
        "itemType": "decoration",
        "parentId": parent,
    })
    assert_ok(rc, "add a child decoration under the parent group via parentId")

    after = _structure(FORM_FQN)
    assert_ok(after, "read form structure after nested add")
    assert_contains(after.text, parent, "the parent group must be present")
    assert_contains(after.text, child, "the nested child must be present")
    # NESTING proof: get_form_structure indents a child two spaces under its parent. A
    # root-level add would render "- E2EChildDeco ..." with NO leading indentation, so the
    # indented form being present proves the parentId nesting worked.
    assert_contains(after.text, "  - %s" % child,
        "the child must render INDENTED under the parent group (nested, not at the root)")

    # ON DISK: both names land in Form.form (the child serialized as a nested <items>).
    poll_diff_contains("<name>%s</name>" % child,
        ctx="the nested child item must be serialized to Form.form")


@e2e_test(tool="add_form_item", kind="write-metadata")
def test_add_decoration_with_bilingual_title():
    # A localized title is written into the item's title EMap keyed by the language CODE
    # (en). get_form_structure shows the item's title in the outline line, so the title is
    # verified BOTH in the model read-back AND ON DISK (a <title> with <key>en</key>).
    new_item = "E2EDecoEn"
    title = "E2EDecoTitleEn"

    before = _structure(FORM_FQN)
    assert_ok(before, "read form structure before bilingual add")
    assert_not_contains(before.text, new_item, "probe item absent before the add")

    r = call("add_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": new_item,
        "itemType": "decoration",
        "title": title,
        "language": "en",
    })
    assert_ok(r, "add_form_item decoration with en title")

    after = _structure(FORM_FQN)
    assert_ok(after, "read form structure after bilingual add")
    assert_contains(after.text, new_item,
        "the new item Name appears in the model read-back")
    # The Items outline renders the title (by language code) when present.
    assert_contains(after.text, title,
        "the en-keyed item title must render in the Items read-back")

    poll_diff_contains("<name>%s</name>" % new_item,
        ctx="bilingual add persists the item name to Form.form")
    poll_diff_contains("<value>%s</value>" % title,
        ctx="bilingual add persists the en-keyed title value to Form.form")


@e2e_test(tool="add_form_item", kind="write-metadata")
def test_russian_type_token_resolves_same_form():
    # The formPath TYPE token is bilingual: "ОбщаяФорма" (Russian for CommonForm)
    # normalizes to "CommonForm" and resolves to the SAME form. The form Name ("Form") is
    # never translated. A regression in the bilingual type resolver would make this FQN
    # miss -> "Form not found".
    russian_fqn = "ОбщаяФорма.Form"  # ОбщаяФорма == CommonForm
    new_item = "E2EGroupRuToken"

    r = call("add_form_item", {
        "projectName": PROJECT,
        "formPath": russian_fqn,
        "name": new_item,
        "itemType": "group",
    })
    assert_ok(r, "add_form_item via Russian type token")

    after = _structure(FORM_FQN)
    assert_ok(after, "read CommonForm.Form after Russian-token add")
    assert_contains(after.text, new_item,
        "the Russian type token must resolve to the same form (item present)")


# ──────────────────────────────────────────────────────────────────────────────
# Reserved itemTypes — field / button rejected with a clear message
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="add_form_item", kind="write-metadata")
def test_field_itemtype_is_reserved():
    # 'field' is a declared enum value but RESERVED (needs a dataPath binding). It is
    # rejected BEFORE any model access with a message naming the type and steering to EDT.
    r = _call_settled("add_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": "E2EField",
        "itemType": "field",
    })
    e = assert_error(r, "reserved field itemType")
    assert_error_quality(e, names=["field"], suggests=["reserved", "EDT form editor"])
    assert_no_diff("a rejected (reserved) call must not change the project")


@e2e_test(tool="add_form_item", kind="write-metadata")
def test_button_itemtype_is_reserved():
    r = _call_settled("add_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": "E2EButton",
        "itemType": "button",
    })
    e = assert_error(r, "reserved button itemType")
    assert_error_quality(e, names=["button"], suggests=["reserved"])
    assert_no_diff("a rejected (reserved) call must not change the project")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — bad enum / bad parent / missing required / not-found
# Each rejected write must also leave the project clean on disk.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="add_form_item", kind="write-metadata")
def test_bad_itemtype_enum_value_is_error():
    # A value outside the schema enum is rejected with the bad value echoed + the accepted
    # set. (The enum is also schema-enforced at the protocol layer; this asserts the tool's
    # own guard message regardless.)
    bad = "spaceship"
    r = _call_settled("add_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": "E2EBadType",
        "itemType": bad,
    })
    e = assert_error(r, "bad itemType enum value")
    # The message echoes the bad value and lists the accepted itemTypes.
    assert_error_quality(e, names=[bad], suggests=["group", "decoration"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_form_item", kind="write-metadata")
def test_nonexistent_parent_id_is_error():
    # A parentId that matches no item in the nested items tree -> "Parent item not found".
    bad_parent = "NoSuchParent_e2e"
    r = call("add_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": "E2EOrphan",
        "itemType": "decoration",
        "parentId": bad_parent,
    })
    e = assert_error(r, "non-existent parentId")
    assert_error_quality(e, names=[bad_parent], suggests=["not found"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_form_item", kind="write-metadata")
def test_missing_project_name_is_error():
    r = call("add_form_item", {
        # projectName omitted on purpose
        "formPath": FORM_FQN,
        "name": "E2EItem",
        "itemType": "group",
    })
    e = assert_error(r, "missing projectName")
    assert_error_quality(e, names=["projectName"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_form_item", kind="write-metadata")
def test_missing_form_path_is_error():
    r = call("add_form_item", {
        "projectName": PROJECT,
        # formPath omitted on purpose
        "name": "E2EItem",
        "itemType": "group",
    })
    e = assert_error(r, "missing formPath")
    assert_error_quality(e, names=["formPath"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_form_item", kind="write-metadata")
def test_missing_name_is_error():
    r = call("add_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        # name omitted on purpose
        "itemType": "group",
    })
    e = assert_error(r, "missing name")
    assert_error_quality(e, names=["name"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_form_item", kind="write-metadata")
def test_missing_item_type_is_error():
    r = call("add_form_item", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": "E2EItem",
        # itemType omitted on purpose
    })
    e = assert_error(r, "missing itemType")
    assert_error_quality(e, names=["itemType"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_form_item", kind="write-metadata")
def test_nonexistent_project_is_error():
    bogus = "NoSuchProject_zzz"
    r = call("add_form_item", {
        "projectName": bogus,
        "formPath": FORM_FQN,
        "name": "E2EItem",
        "itemType": "group",
    })
    e = assert_error(r, "non-existent project")
    assert_error_quality(e, names=[bogus], suggests=["not found"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_form_item", kind="write-metadata")
def test_nonexistent_form_is_error():
    # Correct type token + FQN shape, but no such CommonForm -> resolveMdForm returns null
    # -> "Form not found: <formPath>. ... Names are the programmatic Name, not the synonym.
    # Use get_form_structure to inspect the form."
    bad = "CommonForm.NoSuchForm_e2e"
    r = call("add_form_item", {
        "projectName": PROJECT,
        "formPath": bad,
        "name": "E2EItem",
        "itemType": "group",
    })
    e = assert_error(r, "non-existent form")
    assert_error_quality(e, names=[bad], suggests=["not the synonym", "get_form_structure"])
    assert_no_diff("a rejected call must not change the project")
