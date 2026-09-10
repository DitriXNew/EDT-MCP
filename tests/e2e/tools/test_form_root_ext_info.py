"""
e2e for the form ROOT extInfo - the node that publishes a form's write and read events.

A record/object form carries `<extInfo xsi:type="form:...FormExtInfo">` at its root, and that node
is an event-handler container of its own: EDT's wizard writes `BeforeWriteAtServer` INSIDE it while
`OnCreateAtServer` sits on the root's own `<handlers>`. Two defects came out of that one structure:

- #591 - `create_metadata` built the form without the root extInfo, so every write/read event was
  refused as "not valid for Form" and no MCP property could supply the missing node;
- #592 - on a form that HAD the node, the tool read only the root's list, so it saw no existing
  binding, appended a SECOND one, and `get_metadata_details` reported neither.

The kind of extInfo follows the MAIN attribute's type, exactly as the platform's
`ExtInfoManagementService.setExtInfo(Form, FormAttribute, Version)` decides it - so the tests set
the main attribute's type and then read the file back.

reset: kind="write-metadata" -> reset_model() after each test; each test seeds a UNIQUE register.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    read_disk,
    poll_diff_contains,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)

EXT_INFO_TYPE = 'xsi:type="form:InformationRegisterManagerFormExtInfo"'


def _seed_record_form(suffix):
    """Register + record form + a typed MAIN attribute. Returns (register name, form fqn)."""
    reg = "E2ERecForm" + suffix
    reg_fqn = "InformationRegister." + reg
    form_fqn = reg_fqn + ".Form.RecordForm"
    attr_fqn = form_fqn + ".Attribute.Record"

    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": reg_fqn}),
              "seed InformationRegister " + reg)
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": form_fqn}),
              "seed the record form")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attr_fqn}),
              "seed the main form attribute")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT, "fqn": attr_fqn,
        "properties": [{"name": "valueType", "value": {
            "types": [{"kind": "InformationRegisterRecordManager", "ref": reg}]}}],
    }), "type the main attribute as the register's record manager")
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT, "fqn": attr_fqn,
        "properties": [{"name": "main", "value": True}, {"name": "savedData", "value": True}],
    }), "flag the attribute as the form's main data source")
    wait_for_project_ready()
    return reg, form_fqn


def _form_xml(reg):
    return read_disk("src/InformationRegisters/%s/Forms/RecordForm/Form.form" % reg)


# ──────────────────────────────────────────────────────────────────────────────
# #591 — the node itself
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_record_form_gets_its_root_ext_info():
    reg, _form = _seed_record_form("Node")

    poll_diff_contains(EXT_INFO_TYPE,
                       ctx="the form root must carry the register-manager ext-info on disk")
    xml = _form_xml(reg)
    assert xml.count(EXT_INFO_TYPE) == 1, \
        "exactly one root ext-info, not one per write: %d" % (xml.count(EXT_INFO_TYPE),)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_type_and_main_in_one_batch_still_decide_the_ext_info():
    """The root ext-info is decided ONCE, after the whole batch: per property it would depend on
    the order the properties arrived in ([main, valueType] vs the reverse describe one end state)."""
    reg = "E2ERecFormBatch"
    reg_fqn = "InformationRegister." + reg
    attr_fqn = reg_fqn + ".Form.RecordForm.Attribute.Record"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": reg_fqn}), "seed register")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT,
                                       "fqn": reg_fqn + ".Form.RecordForm"}), "seed record form")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attr_fqn}), "seed attribute")
    wait_for_project_ready()

    # main FIRST, then the type - the order that used to leave the root ext-info behind.
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": attr_fqn,
        "properties": [
            {"name": "main", "value": True},
            {"name": "valueType", "value": {
                "types": [{"kind": "InformationRegisterRecordManager", "ref": reg}]}},
        ],
    })
    assert_ok(r, "set main and the type in ONE batch")
    poll_diff_contains(EXT_INFO_TYPE,
                       ctx="the batch decides the root ext-info whatever order it arrived in")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_write_event_binds_and_lands_inside_the_ext_info():
    reg, form = _seed_record_form("Bind")

    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": form + ".Handler.BeforeWriteAtServer",
        "properties": [{"name": "procedure", "value": "RecBeforeWriteAtServer"}]})
    assert_ok(r, "bind BeforeWriteAtServer on a record form")
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    poll_diff_contains("RecBeforeWriteAtServer",
                       ctx="the bound handler must land in the form file on disk")

    # WHERE it landed is the point: inside <extInfo>, the way EDT's own wizard writes it.
    xml = _form_xml(reg)
    ext_at = xml.find("<extInfo")
    assert ext_at != -1, "the root ext-info must exist: %s" % (xml[:400],)
    assert xml.find("RecBeforeWriteAtServer") > ext_at, \
        "a write event belongs INSIDE the root ext-info, not in the root's own handlers: %s" % (xml,)


@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_details_lists_a_binding_that_lives_in_the_ext_info():
    _reg, form = _seed_record_form("Read")
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": form + ".Handler.BeforeWriteAtServer",
        "properties": [{"name": "procedure", "value": "ReadBackBeforeWrite"}]}), "bind the event")

    r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [form], "full": True})
    assert_ok(r, "read the form back in full")
    assert_contains(r.text, "ReadBackBeforeWrite",
                    "the handler table must list a binding that lives in the extInfo")


# ──────────────────────────────────────────────────────────────────────────────
# #592 — one event, one binding, across both lists
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_second_binding_of_the_same_write_event_is_refused():
    reg, form = _seed_record_form("Dup")
    handler_fqn = form + ".Handler.BeforeWriteAtServer"
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": handler_fqn,
        "properties": [{"name": "procedure", "value": "DupBeforeWrite"}]}), "bind the event once")
    wait_for_project_ready()

    r = call("create_metadata", {"projectName": PROJECT, "fqn": handler_fqn,
                                 "properties": [{"name": "procedure", "value": "DupBeforeWrite2"}]})
    e = assert_error(r, "the same write event bound twice")
    assert_error_quality(e, names=["BeforeWriteAtServer"], suggests=["already exists"],
                         ctx="the second binding must be refused, naming the event")

    xml = _form_xml(reg)
    assert "DupBeforeWrite2" not in xml, "the refused binding must not be written: %s" % (xml,)
    assert xml.count("<event>BeforeWriteAtServer</event>") == 1, \
        "exactly one binding for the event: %d" % (xml.count("<event>BeforeWriteAtServer</event>"),)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_a_main_dynamic_list_gets_the_list_form_ext_info():
    """The dynamic-list branch writes valueType and main itself, outside the property loop that
    syncs the form root - so it has to ask for the root ext-info on its own."""
    base = "Catalog.E2ERootExtDynList"
    list_form = base + ".Form.ListForm"
    list_attr = list_form + ".Attribute.List"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": base}), "seed catalog")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": list_form}), "seed list form")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": list_attr}), "seed attribute")
    wait_for_project_ready()

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": list_attr,
        "properties": [
            {"name": "queryText",
             "value": "SELECT Ref, Description AS Description FROM " + base},
            {"name": "customQuery", "value": True},
        ],
    })
    assert_ok(r, "convert the attribute into a dynamic list")
    assert "dynamicList" in (r.structured.get("applied") or []), \
        "the attribute must really be converted: %r" % (r.structured,)

    poll_diff_contains('xsi:type="form:DynamicListFormExtInfo"',
                       ctx="a MAIN dynamic list must give the form root its list ext-info")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_a_binding_inside_the_ext_info_can_be_deleted():
    reg, form = _seed_record_form("Del")
    handler_fqn = form + ".Handler.BeforeWriteAtServer"
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": handler_fqn,
        "properties": [{"name": "procedure", "value": "DelBeforeWrite"}]}), "bind the event")
    wait_for_project_ready()

    assert_ok(call("delete_metadata", {"projectName": PROJECT, "fqn": handler_fqn, "confirm": True}),
              "delete a handler that lives inside the root ext-info")
    poll_diff_contains("<extInfo", ctx="the form file must be rewritten after the delete")
    assert "DelBeforeWrite" not in _form_xml(reg), \
        "the binding must be gone from the file, wherever it lived"
