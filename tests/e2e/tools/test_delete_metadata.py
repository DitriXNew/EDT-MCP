"""
e2e tests for delete_metadata (kind: write-metadata).

delete_metadata deletes a metadata node (a top-level object or a subordinate member:
attribute / tabular section / dimension / resource / enum value) addressed by a 1C
full-name FQN, via EDT's refactoring service, cleaning up every reference in BSL code,
forms and other metadata. It folds the former delete_metadata_object onto the unified
`fqn` parameter and the shared MetadataNodeResolver.

JSON-responseType tool (payload in r.structured). Two-phase:
  * confirm absent/false -> PREVIEW: action="preview", refactoringTitle, items,
    affectedReferences, affectedReferencesCount, message. Model NOT mutated.
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
    poll_disk_path_gone,
    poll_disk_lacks,
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
    assert "affectedReferencesCount" in r.structured, "preview must report the affected-reference count"
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
def test_delete_form_missing_member_is_error():
    r = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.NoSuchField_zz",
        "confirm": True})
    e = assert_error(r, "delete a missing form member")
    assert_error_quality(e, names=["NoSuchField_zz"], suggests=["not found", "get_form_structure"],
                         ctx="a missing form member points to get_form_structure")
    assert_no_diff("a rejected form-member delete must change nothing")


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
