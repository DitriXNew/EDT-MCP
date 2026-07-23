"""
e2e tests for delete_metadata (kind: write-metadata).

delete_metadata deletes a metadata node (a top-level object or a subordinate member:
attribute / tabular section / dimension / resource / enum value) addressed by a 1C
full-name FQN, via EDT's refactoring service, cleaning up every reference in BSL code,
forms and other metadata. It folds the former delete_metadata_object onto the unified
`fqn` parameter and the shared MetadataNodeResolver.

JSON-responseType tool (payload in r.structured). Two-phase:
  * confirm absent/false -> PREVIEW: action="preview", refactoringTitle, items,
    blocking, blockingReferences, blockingReferencesCount, message. Model NOT mutated.
  * confirm=true         -> EXECUTE: action="executed"; the node is gone and its
    references are cleaned.

reset: kind="write-metadata" -> the orchestrator runs reset_model() (clean_project,
discarding the unsaved delete) AFTER each test, so each test starts clean.

Fixture inventory (TestConfiguration, English Names):
  Catalog.Catalog (attribute "Attribute", form ItemForm), CommonModule.Error/OK/Calc,
  CommonForm.Form, Subsystem.Subsystem, CommonAttribute.CommonAttribute,
  SessionParameter.SessionParameter.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    assert_tree_unchanged,
    assert_diff_contains,
    poll_disk_path_gone,
    poll_disk_lacks,
    tree_snapshot,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)


def _list_commonmodules():
    r = call("get_metadata_objects", {"projectName": PROJECT, "metadataType": "commonModules"})
    assert_ok(r, "read-back: list commonModules")
    return r.text


def _list_catalogs():
    r = call("get_metadata_objects", {"projectName": PROJECT, "metadataType": "catalogs"})
    assert_ok(r, "read-back: list catalogs")
    return r.text


# ──────────────────────────────────────────────────────────────────────────────
# Happy — CONFIRM deletes (verified by model read-back + disk)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_confirm_deletes_top_object_gone_from_model_and_disk():
    assert_contains(_list_commonmodules(), "Calc", "baseline: CommonModule.Calc must exist")

    r = call("delete_metadata", {"projectName": PROJECT, "fqn": "CommonModule.Calc", "confirm": True})
    assert_ok(r, "delete CommonModule.Calc (confirm=true)")
    assert r.structured is not None, "a JSON tool must return structuredContent"
    assert r.structured.get("action") == "executed", \
        "confirm=true must take the execute branch (action=executed): %r" % (r.structured,)
    assert r.structured.get("fqn") == "CommonModule.Calc", "must echo the target fqn"

    after = _list_commonmodules()
    assert_not_contains(after, "| Calc ", "CommonModule.Calc must be GONE from the model")
    assert_contains(after, "OK", "sibling CommonModule.OK must survive a targeted delete")
    poll_disk_path_gone("src/CommonModules/Calc/Calc.mdo",
                        ctx="delete must remove the object's own .mdo from disk")
    poll_disk_lacks("src/Configuration/Configuration.mdo", "CommonModule.Calc",
                    ctx="delete must remove the Configuration.mdo collection reference")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_confirm_deletes_member_attribute():
    # Create a uniquely-named attribute, let the model settle, then delete it by FQN.
    attr = "E2EDelAttr"
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr})
    assert_ok(cr, "seed attribute to delete")
    wait_for_project_ready()

    r = call("delete_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Attribute." + attr,
        "confirm": True,
    })
    assert_ok(r, "delete the seeded attribute")
    assert r.structured.get("action") == "executed", "member delete must execute: %r" % (r.structured,)
    # The parent catalog must survive a member delete.
    assert_contains(_list_catalogs(), "| Catalog ",
                    "a member delete must NOT delete the parent Catalog.Catalog")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_confirm_deletes_command_member():
    # A Command child (a new kind create_metadata can address) is deletable by FQN.
    cmd = "E2EDelCmd"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Command." + cmd}),
              "seed command to delete")
    wait_for_project_ready()

    r = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Command." + cmd, "confirm": True,
    })
    assert_ok(r, "delete the seeded command")
    assert r.structured.get("action") == "executed", "command delete must execute: %r" % (r.structured,)
    assert_contains(_list_catalogs(), "| Catalog ", "the parent catalog must survive a command delete")
    poll_disk_lacks("src/Catalogs/Catalog/Catalog.mdo", cmd,
                    ctx="the deleted command must be gone from the owner .mdo")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_confirm_deletes_nested_tabular_section_attribute():
    # A NESTED member (depth-6) is deletable: resolveExisting resolves the leaf and the refactoring
    # service removes it.
    ts, attr = "E2EDelTab", "E2EDelNestedAttr"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.TabularSection." + ts}),
              "seed tabular section")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.TabularSection.%s.Attribute.%s" % (ts, attr)}), "seed nested attribute")
    wait_for_project_ready()

    r = call("delete_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.TabularSection.%s.Attribute.%s" % (ts, attr), "confirm": True,
    })
    assert_ok(r, "delete the nested tabular-section attribute (depth-6)")
    assert r.structured.get("action") == "executed", "nested delete must execute: %r" % (r.structured,)
    poll_disk_lacks("src/Catalogs/Catalog/Catalog.mdo", attr,
                    ctx="the deleted nested attribute must be gone from the owner .mdo")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_confirm_deletes_inline_special_child():
    # A type-specific inline child (a ChartOfAccounts AccountingFlag) is deletable by FQN.
    coa, flag = "E2EDelCoA", "E2EDelFlag"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "ChartOfAccounts." + coa}),
              "seed chart of accounts")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": "ChartOfAccounts.%s.AccountingFlag.%s" % (coa, flag)}),
        "seed accounting flag")
    wait_for_project_ready()

    r = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "ChartOfAccounts.%s.AccountingFlag.%s" % (coa, flag), "confirm": True,
    })
    assert_ok(r, "delete the accounting flag")
    assert r.structured.get("action") == "executed", "special-child delete must execute: %r" % (r.structured,)
    # Anti-cheat (path-independent): re-creating the same flag must SUCCEED - it would fail with
    # "already exists" if the delete had been a no-op.
    wait_for_project_ready()
    again = call("create_metadata", {
        "projectName": PROJECT, "fqn": "ChartOfAccounts.%s.AccountingFlag.%s" % (coa, flag)})
    assert_ok(again, "re-creating the flag proves the delete actually removed it")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_confirm_deletes_template_factory_child():
    # A Template (a factory-initialized child create_metadata addresses) is deletable by FQN. Verified
    # on the known owner path, closing the gap on the generalized "any addressable node" claim.
    tpl = "E2EDelTpl"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Template." + tpl}),
              "seed template to delete")
    wait_for_project_ready()
    r = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Template." + tpl, "confirm": True,
    })
    assert_ok(r, "delete the seeded template")
    assert r.structured.get("action") == "executed", "template delete must execute: %r" % (r.structured,)
    poll_disk_lacks("src/Catalogs/Catalog/Catalog.mdo", tpl,
                    ctx="the deleted template must be gone from the owner .mdo")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — PREVIEW (confirm absent) lists change points and does NOT mutate
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_preview_without_confirm_lists_changepoints_and_does_not_mutate():
    assert_contains(_list_commonmodules(), "Calc", "baseline: CommonModule.Calc must exist")

    r = call("delete_metadata", {"projectName": PROJECT, "fqn": "CommonModule.Calc"})
    assert_ok(r, "preview delete CommonModule.Calc (no confirm)")
    assert r.structured.get("action") == "preview", \
        "absent confirm must produce a preview: %r" % (r.structured,)
    assert r.structured.get("fqn") == "CommonModule.Calc", "preview must echo the target fqn"
    assert "items" in r.structured, "preview must list refactoring items"
    assert "blockingReferencesCount" in r.structured, "preview must report the blocking-reference count"
    assert_contains(r.structured.get("message", ""), "confirm=true",
                    "preview message must instruct re-calling with confirm=true")

    assert_contains(_list_commonmodules(), "Calc", "preview must NOT delete CommonModule.Calc")
    assert_no_diff("a preview must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — FORM members (the cross-model hop: delete an item / attribute / command /
# handler from the editable .form). Fixture: Catalog.Catalog has form "ItemForm".
# ──────────────────────────────────────────────────────────────────────────────

_FORM = "src/Catalogs/Catalog/Forms/ItemForm/Form.form"


def _seed_form_attribute(attr):
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr})
    assert_ok(r, "seed form attribute " + attr)
    wait_for_project_ready()


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_form_field_preview_then_confirm():
    # Seed an attribute + a bound field, PREVIEW the field delete (no mutation shape), then confirm.
    _seed_form_attribute("DFAttr")
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.DFField",
        "properties": [{"name": "dataPath", "value": "DFAttr"}]})
    assert_ok(r, "seed bound field")
    wait_for_project_ready()
    fqn = "Catalog.Catalog.Form.ItemForm.Field.DFField"
    # Preview (confirm omitted): shape only, nothing removed yet.
    pv = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(pv, "preview the field delete")
    assert pv.structured.get("action") == "preview", "must be a preview: %r" % (pv.structured,)
    names = [it.get("name") for it in (pv.structured.get("items") or [])]
    assert "DFField" in names, "preview items must list the field: %r" % (pv.structured,)
    assert_contains(pv.structured.get("message", ""), "confirm=true",
                    "preview must instruct re-calling with confirm=true")
    # Confirm: the field is removed and the form persisted.
    r = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn, "confirm": True})
    assert_ok(r, "delete the field (confirm)")
    assert r.structured.get("action") == "executed", "confirm must execute: %r" % (r.structured,)
    poll_disk_lacks(_FORM, "DFField", ctx="the deleted field must be gone from the .form on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_form_attribute_confirm():
    _seed_form_attribute("DAAttr")
    r = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute.DAAttr",
        "confirm": True})
    assert_ok(r, "delete a form attribute (confirm)")
    assert r.structured.get("action") == "executed", "confirm must execute: %r" % (r.structured,)
    poll_disk_lacks(_FORM, "DAAttr", ctx="the deleted form attribute must be gone from the .form")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_form_command_confirm():
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command.DCmd"})
    assert_ok(r, "seed form command")
    wait_for_project_ready()
    r = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command.DCmd", "confirm": True})
    assert_ok(r, "delete a form command (confirm)")
    poll_disk_lacks(_FORM, "DCmd", ctx="the deleted form command must be gone from the .form")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_form_group_cascades_subtree():
    # A Group with a nested Decoration: the preview lists the descendant, confirm cascades the subtree.
    g, d = "DGroup", "DDeco"
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group." + g})
    assert_ok(r, "seed group")
    wait_for_project_ready()
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Decoration." + d,
        "properties": [{"name": "parent", "value": g}]})
    assert_ok(r, "seed nested decoration")
    wait_for_project_ready()
    fqn = "Catalog.Catalog.Form.ItemForm.Group." + g
    pv = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(pv, "preview the group delete")
    names = [it.get("name") for it in (pv.structured.get("items") or [])]
    assert g in names and d in names, \
        "the preview must list the group AND its contained decoration: %r" % (pv.structured,)
    r = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn, "confirm": True})
    assert_ok(r, "delete the group (confirm, cascades)")
    poll_disk_lacks(_FORM, g, ctx="the deleted group must be gone from the .form")
    poll_disk_lacks(_FORM, d, ctx="the cascaded decoration must be gone from the .form")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_form_handler_confirm():
    # Seed a form-level OnOpen handler with a distinctive proc name, then delete it by event FQN.
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Handler.OnOpen",
        "properties": [{"name": "procedure", "value": "DelHandlerProc_zz"}]})
    assert_ok(r, "seed form handler")
    wait_for_project_ready()
    r = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Handler.OnOpen", "confirm": True})
    assert_ok(r, "delete a form handler (confirm)")
    assert r.structured.get("action") == "executed", "confirm must execute: %r" % (r.structured,)
    poll_disk_lacks(_FORM, "DelHandlerProc_zz",
                    ctx="the deleted handler's procedure must be gone from the .form")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_form_command_action_confirm():
    # Issue #138: a command's Action handler is addressed the same way it is created
    # (...Command.X.Handler.Action) - deleting it clears the binding but keeps the command.
    cmd, proc = "DelActCmd", "DelActProc_zz"
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(r, "seed form command")
    wait_for_project_ready()
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Command.%s.Handler.Action" % cmd,
        "properties": [{"name": "procedure", "value": proc}]})
    assert_ok(r, "seed the command's Action handler")
    wait_for_project_ready()
    r = call("delete_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Command.%s.Handler.Action" % cmd, "confirm": True})
    assert_ok(r, "delete the command's Action handler (confirm)")
    poll_disk_lacks(_FORM, proc, ctx="the cleared action's procedure must be gone from the .form")
    # The command itself must survive; only its action binding was removed.
    rb = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": ["Catalog.Catalog.Form.ItemForm"]})
    assert_ok(rb, "read the form structure back")
    assert_contains(rb.text, cmd, "the command must survive its action's deletion")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_form_missing_member_is_error():
    r = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.NoSuchField_zz",
        "confirm": True})
    e = assert_error(r, "delete a missing form member")
    assert_error_quality(e, names=["NoSuchField_zz"], suggests=["not found", "get_metadata_details"],
                         ctx="a missing form member points to get_metadata_details")
    assert_no_diff("a rejected form-member delete must change nothing")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — FORM OBJECT delete (symmetric with create_metadata). An owned form
# created by the 4-part FQN 'Type.Object.Form.Name' is deletable by the SAME FQN
# (previously returned "Node not found"). Preview (no confirm) lists it; confirm=true
# removes the form + its content Form.form and clears the owner default-form ref.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_form_object_preview_then_confirm():
    # Create a NEW owned form, PREVIEW its delete (no mutation), then confirm it is gone.
    form = "Z_McpDelForm"
    fqn = "Catalog.Catalog.Form." + form
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(cr, "seed form object to delete")
    wait_for_project_ready()

    # Preview (confirm omitted): the form is LISTED and nothing is removed.
    pv = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(pv, "preview the form-object delete")
    assert pv.structured.get("action") == "preview", "must be a preview: %r" % (pv.structured,)
    names = [it.get("name") for it in (pv.structured.get("items") or [])]
    assert form in names, "preview items must list the form: %r" % (pv.structured,)
    assert_contains(pv.structured.get("message", ""), "confirm=true",
                    "preview must instruct re-calling with confirm=true")
    # The form must still render after a preview (not mutated).
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
    assert_ok(d, "the form must still resolve after a preview")
    assert_contains(d.text, "Form Structure", "a preview must NOT remove the form")

    # Confirm: the form is removed (no "Node not found"), persisted off the owner .mdo.
    r = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn, "confirm": True})
    assert_ok(r, "delete the form object (confirm)")
    assert r.structured.get("action") == "executed", "confirm must execute: %r" % (r.structured,)
    assert r.structured.get("fqn") == fqn, "must echo the target form fqn"
    poll_disk_path_gone("src/Catalogs/Catalog/Forms/%s/Form.form" % form,
                        ctx="the deleted form's content Form.form must be gone from disk")
    # The form's whole resource FOLDER (not just Form.form) must be gone - an orphan
    # Forms/<Name>/ folder survived the model delete, the owner delete and resync_to_disk before.
    poll_disk_path_gone("src/Catalogs/Catalog/Forms/%s" % form,
                        ctx="the deleted form's resource folder must be gone from disk")
    poll_disk_lacks("src/Catalogs/Catalog/Catalog.mdo", form,
                    ctx="the deleted form's <forms> entry must be gone from the owner .mdo")
    # With the orphan folder gone, get_metadata_details on the form FQN no longer resolves it -
    # it must NOT render a live structure, and must behave exactly like a form that NEVER existed (the
    # form branch reports the same unresolvable-form ERROR for both, so the deleted form is now
    # indistinguishable from a never-existed one rather than half-resolving off the orphan file).
    gd = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
    body = (gd.text or "") + (gd.error_text() if gd.is_error else "")
    assert_not_contains(body, "Form Structure",
                        "the deleted form must no longer render a structure")
    never = call("get_metadata_details",
                 {"projectName": PROJECT, "objectFqns": ["Catalog.Catalog.Form.ZZ_NeverExisted_e2e"]})
    never_body = (never.text or "") + (never.error_text() if never.is_error else "")
    deleted_unresolved = ("ERROR" in body) or ("no editable content model" in body)
    never_unresolved = ("ERROR" in never_body) or ("no editable content model" in never_body)
    assert deleted_unresolved and never_unresolved, \
        "the deleted form FQN must be as unresolvable as a never-existed one: %r vs %r" % (body, never_body)
    # Anti-cheat: re-creating the same form must SUCCEED (would fail "already exists" on a no-op delete).
    wait_for_project_ready()
    again = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(again, "re-creating the form proves the delete actually removed it")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_default_form_object_clears_owner_ref():
    # A form registered as the owner's default object form is deletable; the owner's
    # defaultObjectForm reference is cleared (no dangling ref) so the owner stays valid.
    form = "Z_McpDelDefaultForm"
    fqn = "Catalog.Catalog.Form." + form
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": fqn, "setAsDefault": True})
    assert_ok(cr, "seed default-object form to delete")
    wait_for_project_ready()

    r = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn, "confirm": True})
    assert_ok(r, "delete the default-object form (confirm)")
    assert r.structured.get("action") == "executed", "confirm must execute: %r" % (r.structured,)
    poll_disk_lacks("src/Catalogs/Catalog/Catalog.mdo", form,
                    ctx="the deleted default form must be gone from the owner .mdo (incl. the default ref)")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_form_object_missing_is_error():
    # Deleting a non-existent owned form is a clean error, not a silent no-op.
    fqn = "Catalog.Catalog.Form.NoSuchForm_zz"
    r = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn, "confirm": True})
    e = assert_error(r, "delete a missing form object")
    assert_error_quality(e, names=["NoSuchForm_zz"], suggests=["not found", "get_metadata_details"],
                         ctx="a missing form object points to get_metadata_details")
    assert_no_diff("a rejected form-object delete must change nothing")


# ──────────────────────────────────────────────────────────────────────────────
# Reference blocking + force override. An object still referenced
# by other metadata the refactoring CANNOT auto-clean must BLOCK a confirm=true
# delete (action='blocked', success=false) unless force=true is also passed, in
# which case the object is deleted and the incoming reference is left dangling.
# Setup: a fresh Catalog referenced by an attribute Type on Catalog.Catalog.
# ──────────────────────────────────────────────────────────────────────────────


def _seed_referenced_catalog(cat, ref_attr):
    """Create Catalog.<cat>, then an attribute on Catalog.Catalog whose Type points at it.
    Deleting Catalog.<cat> is then blocked by that incoming metadata reference."""
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog." + cat})
    assert_ok(cr, "seed catalog to be referenced: " + cat)
    wait_for_project_ready()
    ca = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + ref_attr})
    assert_ok(ca, "seed the referencing attribute: " + ref_attr)
    wait_for_project_ready()
    st = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + ref_attr,
        "properties": [{"name": "type", "value": {"types": [{"kind": "CatalogRef", "ref": cat}]}}],
    })
    assert_ok(st, "point the attribute Type at Catalog." + cat)
    wait_for_project_ready()


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_confirm_without_force_is_blocked_by_incoming_reference():
    # The referenced catalog cannot be deleted while an attribute Type still points at it.
    cat, ref_attr = "E2EBlockedCat", "E2ERefAttr"
    _seed_referenced_catalog(cat, ref_attr)
    # The seeding itself dirties the tree (new catalog dir + Catalog.mdo/Configuration.mdo edits
    # are force-exported), so a plain assert_no_diff would flag the setup. Snapshot after the
    # seeding and assert the BLOCKED delete added NOTHING on top (verified live: status, tracked
    # diff and untracked-file hashes are byte-identical before/after a blocked delete).
    before = tree_snapshot()

    r = call("delete_metadata", {"projectName": PROJECT, "fqn": "Catalog." + cat, "confirm": True})
    e = assert_error(r, "delete a still-referenced catalog without force must be blocked")
    assert_error_quality(e, names=[cat], suggests=["referenced", "force"],
                         ctx="a blocked delete names the target and points at force=true")
    # Structured envelope marks the block and lists the referencer.
    assert r.structured is not None, "a JSON tool must return structuredContent on a blocked delete"
    assert r.structured.get("action") == "blocked", \
        "a still-referenced confirm=true delete without force must be blocked: %r" % (r.structured,)
    assert (r.structured.get("blockingReferencesCount") or 0) >= 1, \
        "the blocked delete must report at least one blocking reference: %r" % (r.structured,)
    # The object must SURVIVE and nothing must be written.
    assert_contains(_list_catalogs(), "| " + cat + " ",
                    "a blocked delete must NOT remove the still-referenced catalog")
    assert_tree_unchanged(before, "a blocked delete must not touch the project on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_force_deletes_referenced_object_leaving_dangling_reference():
    # force=true overrides the reference block: the catalog is deleted, the attribute Type is
    # left dangling (the refactoring did not auto-clean it).
    cat, ref_attr = "E2EForceCat", "E2EForceRefAttr"
    _seed_referenced_catalog(cat, ref_attr)

    r = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "Catalog." + cat, "confirm": True, "force": True,
    })
    assert_ok(r, "force-delete a still-referenced catalog")
    assert r.structured.get("action") == "executed", \
        "force=true must take the execute branch even when referenced: %r" % (r.structured,)
    assert r.structured.get("forced") is True, "a forced delete must echo forced=true: %r" % (r.structured,)
    # The catalog is gone from the model and disk; its sibling Catalog.Catalog survives.
    after = _list_catalogs()
    assert_not_contains(after, "| " + cat + " ", "force=true must remove Catalog." + cat)
    assert_contains(after, "| Catalog ", "the referencing Catalog.Catalog must survive a forced delete")
    poll_disk_path_gone("src/Catalogs/%s/%s.mdo" % (cat, cat),
                        ctx="a forced delete must remove the object's own .mdo from disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_preview_flags_blocking_reference_without_mutating():
    # The preview (confirm omitted) must surface the blocking reference so a caller sees what
    # would block, and must NOT mutate the project.
    cat, ref_attr = "E2EPreviewBlockCat", "E2EPreviewRefAttr"
    _seed_referenced_catalog(cat, ref_attr)
    # Snapshot after the (legitimately dirtying) seeding — the preview must add nothing on top.
    before = tree_snapshot()

    r = call("delete_metadata", {"projectName": PROJECT, "fqn": "Catalog." + cat})
    assert_ok(r, "preview a still-referenced catalog delete")
    assert r.structured.get("action") == "preview", "absent confirm must preview: %r" % (r.structured,)
    assert r.structured.get("blocking") is True, \
        "the preview must mark a still-referenced node as blocking: %r" % (r.structured,)
    assert (r.structured.get("blockingReferencesCount") or 0) >= 1, \
        "the preview must list the blocking reference: %r" % (r.structured,)
    assert_contains(_list_catalogs(), "| " + cat + " ", "a preview must NOT delete the catalog")
    assert_tree_unchanged(before, "a preview must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — bad input must error clearly AND change nothing
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_missing_project_name_is_error():
    r = call("delete_metadata", {"fqn": "CommonModule.Calc", "confirm": True})
    e = assert_error(r, "missing required projectName")
    assert_error_quality(e, names=["projectName"], suggests=["required", "list_projects"])
    assert_contains(_list_commonmodules(), "Calc", "a rejected call must not delete CommonModule.Calc")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_missing_fqn_is_error():
    r = call("delete_metadata", {"projectName": PROJECT, "confirm": True})
    e = assert_error(r, "missing required fqn")
    assert_error_quality(e, names=["fqn"], suggests=["required"])
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_nonexistent_project_is_error():
    bogus = "NoSuchProject_ZZZ_e2e"
    r = call("delete_metadata", {"projectName": bogus, "fqn": "CommonModule.Calc", "confirm": True})
    e = assert_error(r, "non-existent project")
    assert_error_quality(e, names=[bogus], suggests=["not found", "list_projects"])
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_nonexistent_node_is_error():
    bad = "CommonModule.DoesNotExist_e2e"
    r = call("delete_metadata", {"projectName": PROJECT, "fqn": bad, "confirm": True})
    e = assert_error(r, "non-existent node")
    assert_error_quality(e, names=[bad], suggests=["Type.Name", "Catalog.Products"])
    assert_contains(e, "get_tool_guide('create_metadata')",
                    "the not-found message points to the create guide for the addressable kinds")
    assert_contains(_list_commonmodules(), "OK", "a rejected lookup must not delete the sibling OK")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_malformed_fqn_is_error_and_parent_survives():
    # A well-formed nested FQN whose CHILD does not exist -> resolveExisting returns null.
    # The parent must NOT be deleted as a side effect (the arity/child guard prevents that).
    bad = "Catalog.Catalog.Attribute.NoSuchAttr_e2e"
    r = call("delete_metadata", {"projectName": PROJECT, "fqn": bad, "confirm": True})
    e = assert_error(r, "non-existent nested attribute")
    assert_error_quality(e, names=[bad], suggests=["not found"])
    assert_contains(_list_catalogs(), "| Catalog ",
                    "a failed nested-attribute delete must NOT delete the parent Catalog.Catalog")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_bare_token_without_dot_is_error():
    bad = "JustAName"
    r = call("delete_metadata", {"projectName": PROJECT, "fqn": bad, "confirm": True})
    e = assert_error(r, "malformed FQN (no dot)")
    assert_error_quality(e, names=[bad], suggests=["Type.Name"])
    assert_no_diff("a rejected call must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# XDTO PACKAGE MEMBER delete (issue #183 stream 1) — a Property nested in an
# ObjectType is deleted by its own FQN; its SIBLING property + the owning (now
# emptied) ObjectType both survive. Confirmed live: after the delete, the owner
# collapses to a self-closed <objectType name="Row"/> once its last remaining
# property is also gone, but here ONE sibling survives, so the ObjectType stays
# non-empty. On disk the change lands in the package's own Package.xdto.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_xdto_member():
    pkg, obj = "E2EXdtoDel", "Row"
    pkg_fqn = "XDTOPackage." + pkg
    xdto_path = "src/XDTOPackages/%s/Package.xdto" % pkg
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": pkg_fqn}), "seed the XDTO package")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": pkg_fqn + ".ObjectType." + obj}),
              "seed the ObjectType " + obj)
    wait_for_project_ready()
    gone_fqn = pkg_fqn + ".ObjectType.%s.Property.Gone" % obj
    kept_fqn = pkg_fqn + ".ObjectType.%s.Property.Kept" % obj
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": gone_fqn,
        "properties": [{"name": "type", "value": "string"}]}), "seed Property Gone")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": kept_fqn,
        "properties": [{"name": "type", "value": "string"}]}), "seed Property Kept")
    wait_for_project_ready()

    r = call("delete_metadata", {"projectName": PROJECT, "fqn": gone_fqn, "confirm": True})
    assert_ok(r, "delete the Gone property (confirm=true)")
    assert r.structured.get("action") == "executed", "confirm must execute: %r" % (r.structured,)
    assert r.structured.get("fqn") == gone_fqn, "must echo the target fqn"

    # Package.xdto is a brand-new UNTRACKED file (this test creates the whole package), so a plain
    # `git diff` (tracked files only) would never see it -- assert_diff_contains covers untracked
    # files too (the same reason poll_disk_lacks / poll_disk_path_gone read the actual file for
    # "gone" checks rather than relying on git diff).
    poll_disk_lacks(xdto_path, 'name="Gone"',
                    ctx="the deleted property must be gone from Package.xdto on disk")
    assert_diff_contains('name="Kept"',
                         ctx="the surviving sibling property must remain in Package.xdto on disk")
    assert_diff_contains('name="%s"' % obj,
                         ctx="the owning ObjectType must survive a member-only delete")

    # MODEL read-back: get_metadata_details on the package no longer lists Gone, still lists Kept.
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [pkg_fqn]})
    assert_ok(d, "get_metadata_details read-back on the package")
    assert_not_contains(d.text, "| Gone |", "the deleted property must be GONE from the model read-back")
    assert_contains(d.text, "| Kept |", "the surviving property must remain in the model read-back")
