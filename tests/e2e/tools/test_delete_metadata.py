"""
e2e tests for delete_metadata (kind: write-metadata).

delete_metadata deletes a metadata node (a top-level object or a subordinate member:
attribute / tabular section / dimension / resource / enum value) addressed by a 1C
full-name FQN. A TOP-LEVEL object goes via EDT's refactoring service, which cleans up
the references it CAN auto-clean in BSL code, forms and other metadata (one it cannot
blocks the delete instead); a form object / form member / XDTO member is removed from
its own container with no reference cascade at all. It folds the former delete_metadata_object onto the unified
`fqn` parameter and the shared MetadataNodeResolver.

JSON-responseType tool (payload in r.structured). Two-phase:
  * confirm absent/false -> PREVIEW: action="preview", refactoringTitle, items,
    blocking, blockingReferences, blockingReferencesCount, message. Model NOT mutated.
  * confirm=true         -> EXECUTE: action="executed"; the node is gone. On the
    md-refactoring path its auto-cleanable references are cleaned too (with force,
    the ones that blocked are left dangling); a form / XDTO member gets no cleanup.

reset: kind="write-metadata" -> the orchestrator runs reset_model() (clean_project,
discarding the unsaved delete) AFTER each test, so each test starts clean.

Fixture inventory (TestConfiguration, English Names):
  Catalog.Catalog (attribute "Attribute", form ItemForm), CommonModule.Error/OK/Calc/DrySignal,
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
    assert_disk_lacks,
    assert_disk_path_gone,
    poll_disk_path_gone,
    poll_disk_lacks,
    poll_disk_contains,
    read_disk,
    tree_snapshot,
    settle_or_fail,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)


# The fixture Catalog's own .mdo — the file a member delete under Catalog.Catalog has to rewrite,
# because a member's container IS its owning top object.
_CATALOG_MDO = "src/Catalogs/Catalog/Catalog.mdo"


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
    # Settle first, so this measures the delete rather than a backlog it inherited.
    settle_or_fail("this delete (its Configuration.mdo export is asserted below)")
    assert_contains(_list_commonmodules(), "Calc", "baseline: CommonModule.Calc must exist")

    r = call("delete_metadata", {"projectName": PROJECT, "fqn": "CommonModule.Calc", "confirm": True})
    assert_ok(r, "delete CommonModule.Calc (confirm=true)")
    assert r.structured is not None, "a JSON tool must return structuredContent"
    assert r.structured.get("action") == "executed", \
        "confirm=true must take the execute branch (action=executed): %r" % (r.structured,)
    assert r.structured.get("fqn") == "CommonModule.Calc", "must echo the target fqn"

    # The two files are asserted DIFFERENTLY, and the difference is the whole point of #408.
    #
    # #406 gave the write tools an export barrier, but a barrier that only WAITS is ordered with an
    # export only when the same call SUBMITTED it. Until #408 the generic mdclass delete path
    # submitted nothing at all - the refactoring scheduled its own save - so "is the export segment
    # quiet?" could answer yes truthfully and too early. That is not theory: on run 31728870176 the
    # Configuration.mdo assertion below failed at 6.34s (the call returned on its own, nowhere near
    # the 60s deadline) with the object's own .mdo ALREADY gone, Configuration.mdo not yet
    # rewritten, and none of the barrier's failure markers in that shard's EDT log.
    #
    # Now the delete queues the export of the CONTAINER that registers the deleted node - here the
    # Configuration - before the barrier runs, so nothing of THIS call's export is still queued
    # when it answers, and the file is read immediately. (Which is what makes the read fair, not a
    # promise about the bytes: the platform logs a per-file write failure and completes the
    # computation anyway, so a drained queue never means "the write succeeded" - the assertion
    # below is what checks that.)
    #
    # The object's OWN .mdo is still polled, and deliberately: EDT builds a save task by looking the
    # FQN up in the transaction, so an FQN that no longer resolves yields no task, and that file
    # cannot be resubmitted by anyone but the refactoring. Asserting more than the tool promises is
    # the same defect as promising more than it does.
    #
    # Why both files at all: they are SEPARATE export tasks with no ordering between them, so the
    # tree passes through a state where Configuration.mdo still references an object whose own file
    # is already gone.
    #
    # ORDER MATTERS HERE, and it is the difference between a proof and a decoration: the immediate
    # assertion has to be the FIRST thing after the call. Putting the 10s poll first would hand a
    # build that queued nothing ten seconds to finish, and the "immediate" read below would then
    # pass on the very defect it exists to catch.
    #
    # assert_disk_lacks, not poll_disk_lacks: the polling helper treats a MISSING file as "lacks",
    # so it would also pass if Configuration.mdo vanished entirely. The claim is that the file is
    # present and already correct the moment the call returned.
    assert_disk_lacks("src/Configuration/Configuration.mdo", "CommonModule.Calc",
                      ctx="delete must remove the Configuration.mdo collection reference")
    poll_disk_path_gone("src/CommonModules/Calc/Calc.mdo",
                        ctx="delete must remove the object's own .mdo from disk")

    after = _list_commonmodules()
    assert_not_contains(after, "| Calc ", "CommonModule.Calc must be GONE from the model")
    assert_contains(after, "OK", "sibling CommonModule.OK must survive a targeted delete")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_confirm_deletes_member_attribute():
    # Create a uniquely-named attribute, let the model settle, then delete it by FQN.
    attr = "E2EDelAttr"
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr})
    assert_ok(cr, "seed attribute to delete")
    settle_or_fail("this delete (the owner .mdo export is asserted below)")
    # POSITIVE CONTROL for the disk assertion after the delete. create_metadata reports success
    # even when its own export did not complete, so without this the "gone from the owner .mdo"
    # check below would also pass on a token that never got written - the failure mode and the
    # pass would be the same observation.
    poll_disk_contains(_CATALOG_MDO, "<name>%s</name>" % attr,
                       ctx="baseline: the seeded attribute must be in the owner .mdo before it is deleted")

    r = call("delete_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Attribute." + attr,
        "confirm": True,
    })
    assert_ok(r, "delete the seeded attribute")
    assert r.structured.get("action") == "executed", "member delete must execute: %r" % (r.structured,)
    # #408: a confirmed delete states the project it wrote in, so the barrier waits for the export
    # of THAT project rather than for whatever the arguments named. The cascade participants are
    # awaited too but deliberately NOT listed here - "the platform may have written there" must not
    # be published under a name that says "wrote".
    assert r.structured.get("writtenProjects") == [PROJECT],         "a confirmed delete must publish the project it wrote in: %r" % (
            r.structured.get("writtenProjects"),)
    # The member half of #408, and it must come BEFORE any other MCP round trip: a member's
    # container IS its owning top object, so the owner .mdo is the file this call queued the export
    # of, and it has to be correct the moment the call returned. An intervening call is time an
    # early-returning build could use to finish, which is how the assertion stops testing anything.
    assert_disk_lacks(_CATALOG_MDO, "<name>%s</name>" % attr,
                      ctx="a member delete must remove the attribute from the owner .mdo")
    # MODEL read-back, next to the disk one: "gone from the file" and "gone from the model" are
    # separate claims, and a delete that satisfied only one of them is exactly the drift this suite
    # exists to catch.
    details = call("get_metadata_details",
                   {"projectName": PROJECT, "objectFqns": ["Catalog.Catalog"], "full": True})
    assert_ok(details, "read back the owner after the member delete")
    assert_not_contains(details.text, attr, "the deleted attribute must be GONE from the model")
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

    # #408: the preview states that it queued nothing, instead of the barrier inferring it from
    # the `confirm` ARGUMENT - which says the caller authorized a destructive path, not that
    # anything was written.
    assert r.structured.get("writtenProjects") == [],         "a preview writes nowhere and must say so: %r" % (r.structured.get("writtenProjects"),)

    assert_contains(_list_commonmodules(), "Calc", "preview must NOT delete CommonModule.Calc")
    assert_no_diff("a preview must not touch the project on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_preview_accepts_explicit_timeout_and_clamps_out_of_range_value():
    assert_contains(_list_commonmodules(), "Calc", "baseline: CommonModule.Calc must exist")

    for timeout in (600, 1):
        r = call("delete_metadata", {
            "projectName": PROJECT,
            "fqn": "CommonModule.Calc",
            "timeout": timeout,
        })
        assert_ok(r, "preview with timeout=%s" % timeout)
        assert r.structured is not None, "a JSON tool must return structuredContent"
        assert r.structured.get("action") == "preview", \
            "timeout=%s must still return a normal preview: %r" % (timeout, r.structured)
        assert r.structured.get("fqn") == "CommonModule.Calc", \
            "the preview must echo its target when timeout=%s" % timeout
        assert r.structured.get("writtenProjects") == [], \
            "a bounded preview must still declare that it wrote nowhere: %r" % (r.structured,)

    assert_contains(_list_commonmodules(), "Calc",
                    "explicit and clamped preview timeouts must not delete CommonModule.Calc")
    assert_no_diff("explicit and clamped preview timeouts must not touch the project on disk")

    # The other half of #509: the guide has to SAY what a timeout leaves the model in. This is what
    # makes the test fail on a pre-fix server - an unknown 'timeout' argument is simply ignored, so
    # the calls above would pass there too.
    g = call("get_tool_guide", {"toolName": "delete_metadata"})
    assert_ok(g, "the delete_metadata guide must be readable")
    assert_contains(g.text, "Timeout, and what the model is left in",
                    "the guide must document what a timed-out delete leaves the model in")
    assert_contains(g.text, "`timeout`",
                    "the guide's parameter list must name the timeout parameter")


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
def test_delete_form_field_preview_lists_the_handler_it_takes():
    # A field's event handler lives in a `handlers` containment - not in `items`, not a FormItem - so
    # the walk that named the features it followed never saw it, while EcoreUtil.remove takes it with
    # the field. The delete was authorized and previewed as "one member" and carried the procedure
    # binding off silently (issue #295 review). The radius now follows the CONTAINMENT structure.
    _seed_form_attribute("DHAttr")
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.DHField",
        "properties": [{"name": "dataPath", "value": "DHAttr"}]})
    assert_ok(r, "seed bound field")
    wait_for_project_ready()
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Field.DHField.Handler.OnChange",
        "properties": [{"name": "procedure", "value": "DHFieldOnChange_zz"}]})
    assert_ok(r, "seed the field's OnChange handler")
    wait_for_project_ready()

    fqn = "Catalog.Catalog.Form.ItemForm.Field.DHField"
    pv = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(pv, "preview the field delete")
    names = [it.get("name") for it in (pv.structured.get("items") or [])]
    assert "DHFieldOnChange_zz" in names, \
        "the preview must list the handler the delete takes with the field: %r" % (pv.structured,)
    assert_contains(pv.structured.get("message", ""), "EventHandler",
                    "the message must break the radius down by what it actually found")

    r = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn, "confirm": True})
    assert_ok(r, "delete the field (confirm)")
    poll_disk_lacks(_FORM, "DHFieldOnChange_zz",
                    ctx="the handler binding must be gone from the .form with its field")


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
# Negative — the FQN's KIND segment is part of the address, not a hint (issue #343).
# Before the fix every kind except Attribute / Command fell through to a by-NAME item
# search, so 'Button.<a field>' - and even a misspelt 'Fielld.<a field>' - previewed
# removing the FIELD and, with confirm=true, would have deleted the wrong element.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_form_member_addressed_with_a_foreign_kind_is_refused():
    _seed_form_attribute("KpAttr")
    poll_disk_contains(_FORM, "KpAttr", timeout=60,
                       ctx="the seeded attribute must be visible before the field binds to it")
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.KindProbeFld",
        "properties": [{"name": "dataPath", "value": "KpAttr"}]})
    assert_ok(r, "seed the probe field")
    wait_for_project_ready()
    poll_disk_contains(_FORM, "KindProbeFld", timeout=60,
                       ctx="the seeded probe field must be on disk first")

    # Every foreign kind - and a typo in the kind segment - is refused, in PREVIEW already.
    for kind in ("Button", "Decoration", "Group", "Table", "Fielld"):
        fqn = "Catalog.Catalog.Form.ItemForm.%s.KindProbeFld" % kind
        pv = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn})
        e = assert_error(pv, "preview a delete addressed with kind '%s'" % kind)
        assert_error_quality(e, names=["KindProbeFld"], suggests=["Field"],
                             ctx="a foreign kind must name the kind the element REALLY has "
                                 "(kind '%s')" % kind)
        assert_contains(e, "Catalog.Catalog.Form.ItemForm.Field.KindProbeFld",
                        "the refusal must spell the CORRECTED address (kind '%s')" % kind)

    # confirm=true is refused the same way, and the field is still on disk afterwards.
    r = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button.KindProbeFld",
        "confirm": True})
    assert_error(r, "a CONFIRMED delete addressed with a foreign kind")
    assert_contains(read_disk(_FORM), "KindProbeFld",
                    "the wrongly-addressed delete must not have removed the field")

    # The element's OWN kind still deletes it (the fix refuses the wrong address, not the right one).
    r = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.KindProbeFld",
        "confirm": True})
    assert_ok(r, "delete the field by its own kind")
    poll_disk_lacks(_FORM, "KindProbeFld", timeout=60,
                    ctx="the correctly-addressed delete must remove the field")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_form_handler_owner_with_a_foreign_kind_is_refused():
    # The OWNER's kind segment of an item-level handler address is resolved too: a handler
    # addressed at 'Button.<a field>' must not be found on the same-named FIELD.
    _seed_form_attribute("HkAttr")
    poll_disk_contains(_FORM, "HkAttr", timeout=60,
                       ctx="the seeded attribute must be visible before the field binds to it")
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.HandlerKindFld",
        "properties": [{"name": "dataPath", "value": "HkAttr"}]})
    assert_ok(r, "seed the handler-owner probe field")
    wait_for_project_ready()
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Field.HandlerKindFld.Handler.OnChange",
        "properties": [{"name": "procedure", "value": "HandlerKindProc_zz"}]})
    assert_ok(r, "seed the field's OnChange handler")
    wait_for_project_ready()
    poll_disk_contains(_FORM, "HandlerKindProc_zz", timeout=60,
                       ctx="the seeded handler must be on disk first")

    r = call("delete_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Button.HandlerKindFld.Handler.OnChange",
        "confirm": True})
    e = assert_error(r, "delete a handler addressed at an owner of a foreign kind")
    assert_error_quality(e, names=["HandlerKindFld"], suggests=["Field"],
                         ctx="a foreign owner kind must name the kind the owner really has")
    # The miss is the OWNER's, not the handler's: blaming a missing handler would be false about an
    # element that does have one, and the message must name the kind that found nothing.
    assert_contains(e, "Form item not found",
                    "a foreign OWNER kind must be reported as the owner miss it is")
    assert_contains(e, "(kind 'Button')", "the refusal must name the kind that found nothing")
    assert_not_contains(e, "No event handler",
                        "the handler exists - the address named the wrong owner kind")
    assert_contains(read_disk(_FORM), "HandlerKindProc_zz",
                    "the wrongly-addressed handler delete must not have removed the handler")

    # The owner's own kind still deletes it.
    r = call("delete_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Field.HandlerKindFld.Handler.OnChange",
        "confirm": True})
    assert_ok(r, "delete the handler by the owner's own kind")
    poll_disk_lacks(_FORM, "HandlerKindProc_zz", timeout=60,
                    ctx="the correctly-addressed handler delete must remove the binding")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_preview_reaches_a_designer_child_by_its_inherited_kind_only():
    # Regression guard for the OTHER half of issue #343: the designer's own children have no kind
    # token of their own, but a token addresses its EClass AND its subclasses - an AutoCommandBar IS
    # a Group. So the form-root command bar keeps exactly ONE supported address ('Group'), and a
    # foreign token must NOT reach it either ("no token denotes it" is not "every token fits").
    pv = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group.FormCommandBar"})
    assert_ok(pv, "preview the auto command bar via its inherited kind 'Group'")
    names = [it.get("name") for it in (pv.structured.get("items") or [])]
    assert "FormCommandBar" in names, \
        "the auto command bar must stay addressable via 'Group': %r" % (pv.structured,)

    for kind in ("Field", "Button", "Decoration", "Table", "Grroup"):
        r = call("delete_metadata", {
            "projectName": PROJECT,
            "fqn": "Catalog.Catalog.Form.ItemForm.%s.FormCommandBar" % kind})
        e = assert_error(r, "preview the auto command bar via the foreign kind '%s'" % kind)
        assert_error_quality(e, names=["FormCommandBar"], suggests=["not found"],
                             ctx="a designer child must not answer to a foreign kind ('%s')" % kind)
    assert_no_diff("previews must not touch the project on disk")


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
    # A form-level event handler: it lives in the form's `handlers` containment, which is neither the
    # items tree nor one of the three named features the old count walked - so a whole-form delete
    # carried the procedure binding off unmentioned (issue #295 review).
    h = call("create_metadata", {
        "projectName": PROJECT, "fqn": fqn + ".Handler.OnOpen",
        "properties": [{"name": "procedure", "value": "ZDelFormOnOpen_zz"}]})
    assert_ok(h, "seed a form-level handler")
    wait_for_project_ready()

    # Preview (confirm omitted): the form is LISTED and nothing is removed.
    pv = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(pv, "preview the form-object delete")
    assert pv.structured.get("action") == "preview", "must be a preview: %r" % (pv.structured,)
    names = [it.get("name") for it in (pv.structured.get("items") or [])]
    assert form in names, "preview items must list the form: %r" % (pv.structured,)
    assert_contains(pv.structured.get("message", ""), "confirm=true",
                    "preview must instruct re-calling with confirm=true")
    assert "ZDelFormOnOpen_zz" in names, \
        "the preview must list the handler the form delete takes with it: %r" % (pv.structured,)
    assert_contains(pv.structured.get("message", ""), "EventHandler",
                    "the breakdown must name what the walk actually found")
    # ...and it must NOT be padded with the form's DERIVED data (the form-data structure, the BSL
    # context types/parameters/events, the standard commands): none of that is authored or persisted,
    # and counting it turned a small form into a 450-entry prompt (found by this round's live probe).
    assert len(names) < 60, \
        "the preview must count authored content, not derived data: %d entries" % (len(names),)
    # The prompt counts the form's CONTENT and points the caller here for the details, so the preview
    # has to list that content too - it used to answer with the BasicForm alone (issue #295 review).
    # A fresh form already carries its auto command bar.
    assert len(names) > 1, \
        "the preview must list the form's content, not the form alone: %r" % (pv.structured,)
    # The form must still render after a preview (not mutated).
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
    assert_ok(d, "the form must still resolve after a preview")
    assert_contains(d.text, "Form Structure", "a preview must NOT remove the form")

    # Confirm: the form is removed (no "Node not found"), persisted off the owner .mdo.
    r = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn, "confirm": True})
    assert_ok(r, "delete the form object (confirm)")
    assert r.structured.get("action") == "executed", "confirm must execute: %r" % (r.structured,)
    assert r.structured.get("fqn") == fqn, "must echo the target form fqn"
    # IMMEDIATE, unlike the top-object delete above, and for the reason spelled out there: the
    # owned-form branch calls forceExportToDisk itself, so its submission happens-before the #406
    # barrier's wait and the files really are settled when the call returns.
    assert_disk_path_gone("src/Catalogs/Catalog/Forms/%s/Form.form" % form,
                          ctx="the deleted form's content Form.form must be gone from disk")
    # The form's whole resource FOLDER (not just Form.form) must be gone - an orphan
    # Forms/<Name>/ folder survived the model delete, the owner delete and resync_to_disk before.
    assert_disk_path_gone("src/Catalogs/Catalog/Forms/%s" % form,
                          ctx="the deleted form's resource folder must be gone from disk")
    assert_disk_lacks("src/Catalogs/Catalog/Catalog.mdo", form,
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
    # "reference", not "referenced": the message distinguishes incoming REFERENCES from platform
    # prohibitions now, so it says "incoming reference(s)" - pinning one inflection would fail the
    # test for a wording that is more precise, not less.
    assert_error_quality(e, names=[cat], suggests=["reference", "force"],
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


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_preview_of_a_collection_attribute_lists_its_columns():
    """The preview must name the COLUMNS the confirmed delete will take with the attribute.

    Columns are containment children of a collection-typed form attribute, so EcoreUtil.remove
    removes them - but they are not in the `items` tree the preview used to walk. Listing only the
    attribute would understate the destruction of a two-phase confirm (issue #295 review).
    """
    attr, col = "E2EDelColOwner", "E2EDelCol"
    a = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr})
    assert_ok(a, "seed the attribute")
    wait_for_project_ready()
    t = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr,
        "properties": [{"name": "type", "value": {"types": [{"kind": "ValueTable"}]}}]})
    assert_ok(t, "make it a ValueTable")
    wait_for_project_ready()
    c = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr + ".Column." + col})
    assert_ok(c, "seed the column")
    wait_for_project_ready()

    r = call("delete_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr})
    assert_ok(r, "preview the collection attribute delete")
    assert r.structured.get("action") == "preview", \
        "confirm absent must take the preview branch: %r" % (r.structured,)
    names = [str(item.get("name")) for item in (r.structured.get("items") or [])]
    assert col in names, \
        "the preview must list the column the delete will remove: %r" % (names,)
