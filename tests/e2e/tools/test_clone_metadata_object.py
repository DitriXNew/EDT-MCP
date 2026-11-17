"""Native clone contract: preview/drift, every supported top kind, resources and explicit transfers.

The orchestrator owns fixture resets. Every apply checks disk before another MCP call.
"""
import os
import hashlib
import time
import copy
import xml.etree.ElementTree as ET

from harness import (
    call, assert_ok, assert_error, assert_error_quality, assert_contains,
    assert_no_diff, assert_tree_unchanged, tree_snapshot, read_disk,
    e2e_test, PROJECT, PROJECT_DIR, TESTS_PROJECT, wait_for_project_ready,
    E2ECallTimeout, EXT_OBJECTS_PROJECT, REPO_ROOT, TESTS_PROJECT_REL, assert_no_diff_rel, diff_rel,
    read_fixture_file, restore_extension_fixture, settle_or_fail, status_porcelain_rel, PROJECT_REL,
)


def _clone(**arguments):
    return call("clone_metadata_object", {"projectName": PROJECT, **arguments})


def _preview(**arguments):
    result = _clone(**arguments)
    assert_ok(result, "clone preview")
    data = result.structured
    assert isinstance(data, dict) and data.get("success") is True, "clone preview refused: " + result.error_text()
    assert data["action"] == "preview", data
    assert data["contentHash"], data
    assert data["codeReferencePolicy"] == "preserveText", data
    assert data["writtenProjects"] == [], data
    return data


def _apply(preview, **arguments):
    result = _clone(**arguments, confirm=True, expectedHash=preview["contentHash"])
    assert_ok(result, "native clone")
    data = result.structured
    assert data["action"] == "cloned" and data["persisted"] is True, data
    assert data["writtenProjects"] == [PROJECT], data
    assert data["fqn"] != data["sourceFqn"], data
    assert data["resources"] and all(row["verified"] for row in data["resources"]), data
    return data


def _xml(path):
    return ET.fromstring(read_disk(path))


def _assert_only_external_incoming(data):
    source = data["sourceFqn"]
    for field in ("incomingRelations", "notCopied"):
        for row in data[field]:
            owner = row["owner"]
            assert owner != source and not owner.startswith((source + ".", source + " - ", source + " / ")), row
            assert not owner.startswith(("Catalogs/Catalog/", "src/Catalogs/Catalog/")), row


def _details(fqn):
    result = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn], "full": True})
    assert_ok(result, "clone model read-back")
    return result.text


def _hash_file(path):
    return hashlib.sha256(_read_bytes(path)).hexdigest()


def _read_bytes(path):
    with open(os.path.join(PROJECT_DIR, *path.split("/")), "rb") as stream:
        return stream.read()


def _assert_module_copy(source, clone, before):
    current = _read_bytes(source)
    assert current == before, "Source module changed: " + source
    assert _read_bytes(clone) == current, "Module bytes differ: " + source + " -> " + clone


def _rights_row_xml(row, object_name=None):
    expected = copy.deepcopy(row)
    expected.tail = None
    if object_name is not None:
        expected.find("{*}name").text = object_name
    return ET.tostring(expected, encoding="unicode")


def _assert_rights_copy(source, clone, clone_fqn):
    expected = _rights_row_xml(source, clone_fqn)
    actual = _rights_row_xml(clone)
    assert actual == expected, "Copied rights row differs:\nEXPECTED:\n%s\nACTUAL:\n%s" % (expected, actual)


def _absent(fqn):
    result = call("get_metadata_objects", {"projectName": PROJECT, "metadataType": fqn.split(".")[0], "limit": 1000})
    assert_ok(result, "check refused clone is absent from BM")
    assert fqn.split(".")[1] not in result.text, "refused clone leaked into BM: " + result.text


def _top_roundtrip(kind, plural):
    source = kind + ".E2ECloneSource"
    target = kind + ".E2ECloneNamed"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": source}), "seed " + kind)
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": source + ".Attribute.CloneField"}), "seed owned attribute")
    wait_for_project_ready()
    before = tree_snapshot()
    preview = _preview(fqn=source, newName="E2ECloneNamed")
    assert preview["sourceFqn"] == source
    assert_tree_unchanged(before, "preview must not attach or copy files")
    source_path = "src/%s/E2ECloneSource/E2ECloneSource.mdo" % plural
    source_hash = _hash_file(source_path)
    source_xml = _xml(source_path)
    for module in ("ObjectModule.bsl", "ManagerModule.bsl"):
        assert not os.path.exists(os.path.join(PROJECT_DIR, "src", plural, "E2ECloneSource", module)), "seed must have no optional module files"
        assert all(not row["file"].endswith("/" + module) for row in preview["resources"]), preview
    data = _apply(preview, fqn=source, newName="E2ECloneNamed")
    # Disk checks precede read-back; the clone call itself owes the export drain.
    root = _xml("src/%s/E2ECloneNamed/E2ECloneNamed.mdo" % plural)
    assert root.findtext("name") == "E2ECloneNamed"
    assert root.attrib["uuid"] != source_xml.attrib["uuid"]
    assert root.find("attributes/name").text == "CloneField"
    assert root.find("attributes").attrib["uuid"] != source_xml.find("attributes").attrib["uuid"]
    assert target in [node.text for node in _xml("src/Configuration/Configuration.mdo").findall(plural[0].lower() + plural[1:])]
    assert _hash_file(source_path) == source_hash, "copy must preserve the source bytes"
    assert data["fqn"] == target and data["namePolicy"] == "callerSupplied", data
    assert not os.path.exists(os.path.join(PROJECT_DIR, "src", plural, "E2ECloneSource1")), "no auto-name may leak"
    for module in ("ObjectModule.bsl", "ManagerModule.bsl"):
        assert not os.path.exists(os.path.join(PROJECT_DIR, "src", plural, "E2ECloneNamed", module)), "absent source modules must remain absent"
    assert_contains(_details(target), "CloneField", "owned member must resolve from cloned model")
    assert_contains(_details(source), "E2ECloneSource", "source must remain addressable")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_catalog_roundtrip():
    _top_roundtrip("Catalog", "Catalogs")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_document_roundtrip():
    _top_roundtrip("Document", "Documents")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_report_roundtrip():
    _top_roundtrip("Report", "Reports")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_data_processor_roundtrip():
    _top_roundtrip("DataProcessor", "DataProcessors")


def _seed(*steps):
    for tool, arguments, ctx in steps:
        assert_ok(call(tool, {"projectName": PROJECT, **arguments}), ctx)
    wait_for_project_ready()


def _write_module(path, source, ctx):
    return ("write_module_source", {"modulePath": path, "mode": "replace", "source": source, "overwrite": True}, ctx)


def _registered(fqn):
    collection = _CONFIGURATION_TAG[fqn.split(".")[0]]
    return fqn in [node.text for node in _xml("src/Configuration/Configuration.mdo").findall(collection)]


# Configuration.mdo element per kind; Role also has defaultRoles, which a clone must never join.
_CONFIGURATION_TAG = {"Constant": "constants", "Enum": "enums", "InformationRegister": "informationRegisters",
                   "ScheduledJob": "scheduledJobs", "CommonModule": "commonModules", "Role": "roles",
                   "CommonForm": "commonForms", "ExchangePlan": "exchangePlans"}


def _uuids(root):
    return [node.attrib["uuid"] for node in root.iter() if "uuid" in node.attrib]


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_common_form_copies_the_separate_form_and_module():
    source = "CommonForm.E2ECloneWindow"
    source_folder = "src/CommonForms/E2ECloneWindow/"
    clone_folder = "src/CommonForms/E2ECloneWindowCopy/"
    _seed(("create_metadata", {"fqn": source}, "seed common form"),
          ("create_metadata", {"fqn": source + ".Decoration.CloneLabel"}, "seed form label"),
          _write_module("CommonForms/E2ECloneWindow/Module.bsl",
                        "&AtClient\nProcedure CloneProbe()\nEndProcedure\n", "seed form module"))
    source_mdo = source_folder + "E2ECloneWindow.mdo"
    before = {path: _read_bytes(path) for path in (source_mdo, source_folder + "Form.form",
                                                   source_folder + "Module.bsl")}
    scope = {"fqn": "ОбщаяФорма.E2ECloneWindow", "newName": "E2ECloneWindowCopy"}
    preview = _preview(**scope)
    assert preview["sourceFqn"] == source, preview
    data = _apply(preview, **scope)

    clone = _xml(clone_folder + "E2ECloneWindowCopy.mdo")
    assert clone.findtext("name") == "E2ECloneWindowCopy"
    assert clone.attrib["uuid"] != _xml(source_mdo).attrib["uuid"]
    assert "CloneLabel" in [node.findtext("name") for node in _xml(clone_folder + "Form.form").findall("items")]
    assert clone_folder + "Form.form" in {row["file"] for row in data["resources"]}, data
    assert _read_bytes(clone_folder + "Module.bsl") == before[source_folder + "Module.bsl"]
    for path, expected in before.items():
        assert _read_bytes(path) == expected, "Source bytes changed: " + path
    assert data["fqn"] == "CommonForm.E2ECloneWindowCopy" and _registered(data["fqn"]), data
    details = _details(data["fqn"])
    assert_contains(details, "Form Structure", "cloned common form content is attached")
    assert_contains(details, "CloneLabel", "cloned form label read-back")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_exchange_plan_regenerates_this_node_and_other_identities():
    source = "ExchangePlan.E2ECloneQueue"
    _seed(("create_metadata", {"fqn": source}, "seed exchange plan"),
          ("create_metadata", {"fqn": source + ".Attribute.QueueField"}, "seed exchange plan attribute"))
    path = "src/ExchangePlans/E2ECloneQueue/E2ECloneQueue.mdo"
    before = _read_bytes(path)
    original = ET.fromstring(before)
    scope = {"fqn": source, "newName": "E2ECloneQueueCopy"}
    data = _apply(_preview(**scope), **scope)

    clone = _xml("src/ExchangePlans/E2ECloneQueueCopy/E2ECloneQueueCopy.mdo")
    assert clone.findtext("name") == "E2ECloneQueueCopy"
    assert clone.findtext("attributes/name") == "QueueField"
    assert not set(_uuids(original)) & set(_uuids(clone)), "exchange plan and member UUIDs must be fresh"
    assert clone.attrib["uuid"] and clone.find("attributes").attrib["uuid"]
    original_node, cloned_node = original.findtext("thisNode"), clone.findtext("thisNode")
    assert original_node and cloned_node and original_node != cloned_node, (original_node, cloned_node)
    type_kinds = {"objectType", "refType", "selectionType", "listType", "managerType"}
    types_before = {node.tag: node for node in original.findall("producedTypes/*")}
    types_after = {node.tag: node for node in clone.findall("producedTypes/*")}
    assert types_before.keys() == types_after.keys() == type_kinds
    for attr in ("typeId", "valueTypeId"):
        old_ids = {node.attrib[attr] for node in types_before.values()}
        new_ids = {node.attrib[attr] for node in types_after.values()}
        assert len(new_ids) == len(type_kinds) and not old_ids & new_ids, (attr, old_ids, new_ids)
    assert _read_bytes(path) == before, "exchange plan source bytes changed"
    assert data["fqn"] == "ExchangePlan.E2ECloneQueueCopy" and _registered(data["fqn"]), data
    assert_contains(_details(data["fqn"]), "QueueField", "cloned exchange plan attribute read-back")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_constant_copies_type_and_value_manager_module():
    module = "Procedure BeforeWrite(Cancel)\n    Value = Constants.E2ECloneConst.Get();\nEndProcedure\n"
    _seed(("create_metadata", {"fqn": "Constant.E2ECloneConst"}, "seed constant"),
          ("modify_metadata", {"fqn": "Constant.E2ECloneConst", "properties": [
              {"name": "type", "value": {"types": [{"kind": "Number", "precision": 12, "scale": 3}]}}]}, "seed type"),
          _write_module("Constants/E2ECloneConst/ValueManagerModule.bsl", module, "seed value manager module"))
    source = _xml("src/Constants/E2ECloneConst/E2ECloneConst.mdo")
    module_bytes = _read_bytes("src/Constants/E2ECloneConst/ValueManagerModule.bsl")
    preview = _preview(fqn="Constant.E2ECloneConst", newName="E2ECloneConstCopy")
    assert ("src/Constants/E2ECloneConst/ValueManagerModule.bsl", 2) in {
        (hit["file"], hit["line"]) for hit in preview["codeReferences"]}, preview
    data = _apply(preview, fqn="Constant.E2ECloneConst", newName="E2ECloneConstCopy")
    root = _xml("src/Constants/E2ECloneConstCopy/E2ECloneConstCopy.mdo")
    assert root.findtext("name") == "E2ECloneConstCopy" and root.attrib["uuid"] != source.attrib["uuid"]
    assert ET.tostring(root.find("type"), encoding="unicode") == ET.tostring(source.find("type"), encoding="unicode")
    assert root.findtext("type/numberQualifiers/precision") == "12", ET.tostring(root, encoding="unicode")
    _assert_module_copy("src/Constants/E2ECloneConst/ValueManagerModule.bsl",
                        "src/Constants/E2ECloneConstCopy/ValueManagerModule.bsl", module_bytes)
    assert ("src/Constants/E2ECloneConstCopy/ValueManagerModule.bsl", 2) in {
        (hit["file"], hit["line"]) for hit in data["codeReferences"]}, data
    assert _registered("Constant.E2ECloneConstCopy")
    assert_contains(_details("Constant.E2ECloneConstCopy"), "E2ECloneConstCopy", "clone model read-back")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_enum_gives_values_new_identities():
    _seed(("create_metadata", {"fqn": "Enum.E2ECloneEnum"}, "seed enum"),
          ("create_metadata", {"fqn": "Enum.E2ECloneEnum.EnumValue.First"}, "seed first value"),
          ("create_metadata", {"fqn": "Enum.E2ECloneEnum.EnumValue.Second"}, "seed second value"))
    source = _xml("src/Enums/E2ECloneEnum/E2ECloneEnum.mdo")
    data = _apply(_preview(fqn="Enum.E2ECloneEnum", newName="E2ECloneEnumCopy"),
                  fqn="Enum.E2ECloneEnum", newName="E2ECloneEnumCopy")
    root = _xml("src/Enums/E2ECloneEnumCopy/E2ECloneEnumCopy.mdo")
    assert [node.findtext("name") for node in root.findall("enumValues")] == ["First", "Second"]
    source_values = {node.attrib["uuid"] for node in source.findall("enumValues")}
    clone_values = [node.attrib["uuid"] for node in root.findall("enumValues")]
    assert len(set(clone_values)) == 2 and not source_values & set(clone_values), (source_values, clone_values)
    assert not set(_uuids(source)) & set(_uuids(root)), "no identity may be shared with the source"
    assert _xml("src/Enums/E2ECloneEnum/E2ECloneEnum.mdo").attrib == source.attrib, "source must stay unchanged"
    assert _registered("Enum.E2ECloneEnumCopy")
    assert_contains(_details(data["fqn"]), "Second", "cloned values read-back")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_information_register_reports_recorders_left_on_the_source():
    register = "InformationRegister.E2ECloneRegister"
    module = "Procedure BeforeWrite(Cancel, Replacing)\n    // InformationRegisters.E2ECloneRegister\nEndProcedure\n"
    _seed(("create_metadata", {"fqn": register}, "seed register"),
          ("create_metadata", {"fqn": register + ".Dimension.Item"}, "seed dimension"),
          ("create_metadata", {"fqn": register + ".Resource.Amount"}, "seed resource"),
          ("create_metadata", {"fqn": register + ".Attribute.Note"}, "seed attribute"),
          ("create_metadata", {"fqn": register + ".Form.RecordForm"}, "seed form"),
          ("modify_metadata", {"fqn": register, "properties": [{"name": "writeMode", "value": "RecorderSubordinate"}]},
           "seed recorder-subordinate mode"),
          ("modify_metadata", {"fqn": "Document.QueryOrder", "properties": [
              {"name": "registerRecords", "value": ["AccumulationRegister.QueryStock", register]}]}, "seed recorder"),
          _write_module("InformationRegisters/E2ECloneRegister/RecordSetModule.bsl", module, "seed record set module"))
    source = _xml("src/InformationRegisters/E2ECloneRegister/E2ECloneRegister.mdo")
    recorder = _hash_file("src/Documents/QueryOrder/QueryOrder.mdo")
    preview = _preview(fqn=register, newName="E2ECloneRegisterCopy")
    assert any(row["owner"].startswith("Document.QueryOrder") for row in preview["notCopied"]), preview
    assert "RecorderSubordinate" in preview["warning"] and "registerRecords" in preview["warning"], preview
    data = _apply(preview, fqn=register, newName="E2ECloneRegisterCopy")
    assert "RecorderSubordinate" in data["warning"], data
    assert any(row["owner"].startswith("Document.QueryOrder") for row in data["notCopied"]), data
    folder = "src/InformationRegisters/E2ECloneRegisterCopy"
    root = _xml(folder + "/E2ECloneRegisterCopy.mdo")
    for member, name in (("dimensions", "Item"), ("resources", "Amount"), ("attributes", "Note")):
        assert root.find(member + "/name").text == name, member
        assert root.find(member).attrib["uuid"] != source.find(member).attrib["uuid"], member
    assert root.findtext("writeMode") == "RecorderSubordinate"
    assert root.find("forms/name").text == "RecordForm"
    assert _xml(folder + "/Forms/RecordForm/Form.form").tag == "{http://g5.1c.ru/v8/dt/form}Form"
    assert _read_bytes(folder + "/RecordSetModule.bsl") == _read_bytes(
        "src/InformationRegisters/E2ECloneRegister/RecordSetModule.bsl")
    assert _hash_file("src/Documents/QueryOrder/QueryOrder.mdo") == recorder, "recorders must not be redirected"
    assert _registered(data["fqn"])
    assert_contains(_details(data["fqn"]), "Amount", "cloned register fields read-back")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_register_with_a_generated_list_form_is_not_refused_as_drift():
    # A generated list table's search additions omit 'enabled' on disk; EDT's import reads that as true.
    register = "InformationRegister.E2ECloneListed"
    form_path = "src/InformationRegisters/E2ECloneListed/Forms/ListForm/Form.form"
    _seed(("create_metadata", {"fqn": register}, "seed register"),
          ("create_metadata", {"fqn": register + ".Dimension.Key"}, "seed dimension"),
          ("create_metadata", {"fqn": register + ".Form.ListForm", "formType": "LIST", "generateContent": True},
           "seed generated list form"))
    source_form = _read_bytes(form_path)
    assert b"<searchStringAddition>" in source_form, "the generated list form must carry table additions"
    scope = {"fqn": register, "newName": "E2ECloneListedCopy"}
    data = _apply(_preview(**scope), **scope)
    clone_form = "src/InformationRegisters/E2ECloneListedCopy/Forms/ListForm/Form.form"
    assert b"<searchStringAddition>" in _read_bytes(clone_form), "the clone keeps the list table additions"
    assert _read_bytes(form_path) == source_form, "the source form must stay byte-identical"
    assert _registered(data["fqn"]), data


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_enabled_scheduled_job_warns_that_both_jobs_run():
    _seed(("create_metadata", {"fqn": "ScheduledJob.E2ECloneJob"}, "seed job"),
          ("modify_metadata", {"fqn": "ScheduledJob.E2ECloneJob", "properties": [
              {"name": "methodName", "value": "CommonModule.Calc.Add"}, {"name": "use", "value": True}]},
           "seed method and use"))
    preview = _preview(fqn="ScheduledJob.E2ECloneJob", newName="E2ECloneJobCopy")
    assert "both jobs will run the same method" in preview["warning"], preview
    assert "CommonModule.Calc.Add" in preview["warning"], preview
    data = _apply(preview, fqn="ScheduledJob.E2ECloneJob", newName="E2ECloneJobCopy")
    assert "both jobs will run the same method" in data["warning"], data
    root = _xml("src/ScheduledJobs/E2ECloneJobCopy/E2ECloneJobCopy.mdo")
    assert root.findtext("methodName") == "CommonModule.Calc.Add"
    assert root.findtext("use") == "true"
    assert _registered(data["fqn"])
    quiet = _preview(fqn="Catalog.Catalog", newName="E2ECloneQuietJob")
    assert "both jobs" not in quiet["warning"], quiet


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_common_module_reports_bare_name_calls_and_refuses_a_global_module():
    code = ("Function Twice(Value) Export\n    Return Value * 2;\nEndFunction\n\n"
            "Function Quad(Value) Export\n    Return E2ECloneModule.Twice(E2ECloneModule.Twice(Value));\nEndFunction\n")
    _seed(("create_metadata", {"fqn": "CommonModule.E2ECloneModule"}, "seed module"),
          ("modify_metadata", {"fqn": "CommonModule.E2ECloneModule", "properties": [{"name": "server", "value": True}]},
           "seed server flag"),
          _write_module("CommonModules/E2ECloneModule/Module.bsl", code, "seed module text"))
    source_path = "src/CommonModules/E2ECloneModule/Module.bsl"
    module_bytes = _read_bytes(source_path)
    preview = _preview(fqn="CommonModule.E2ECloneModule", newName="E2ECloneModuleCopy")
    assert (source_path, 6) in {(hit["file"], hit["line"]) for hit in preview["codeReferences"]}, preview
    data = _apply(preview, fqn="CommonModule.E2ECloneModule", newName="E2ECloneModuleCopy")
    clone_path = "src/CommonModules/E2ECloneModuleCopy/Module.bsl"
    _assert_module_copy(source_path, clone_path, module_bytes)
    hits = {(hit["file"], hit["line"]) for hit in data["codeReferences"]}
    assert (clone_path, 6) in hits and (clone_path, 1) not in hits, data
    root = _xml("src/CommonModules/E2ECloneModuleCopy/E2ECloneModuleCopy.mdo")
    assert root.findtext("server") == "true"
    assert _registered(data["fqn"])
    _seed(("create_metadata", {"fqn": "CommonModule.E2ECloneGlobal"}, "seed global module"),
          ("modify_metadata", {"fqn": "CommonModule.E2ECloneGlobal", "properties": [
              {"name": "global", "value": True}, {"name": "server", "value": True}]}, "seed global flag"))
    before = tree_snapshot()
    message = assert_error(_clone(fqn="CommonModule.E2ECloneGlobal", newName="E2ECloneGlobalCopy"), "global module")
    assert_error_quality(message, names=["CommonModule.E2ECloneGlobal", "global=true"], suggests=["non-global"])
    assert "resync_to_disk" not in message, "a kind refusal is not a stale-source problem: " + message
    assert_tree_unchanged(before, "a refused global module clone writes nothing")
    _absent("CommonModule.E2ECloneGlobalCopy")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_role_copies_its_rights_model_rls_and_templates():
    condition = "WHERE Description = &Name"
    _seed(("create_metadata", {"fqn": "Role.E2ECloneRoleSource"}, "seed role"),
          ("modify_metadata", {"fqn": "Role.E2ECloneRoleSource",
                               "rights": [{"object": "Catalog.Catalog", "right": "Read", "value": "set",
                                           "rls": condition, "rlsFields": ["Description"]}],
                               "templates": [{"op": "add", "name": "ByName", "condition": condition}]},
           "seed rights, RLS and template"))
    source_rights = _read_bytes("src/Roles/E2ECloneRoleSource/Rights.rights")
    preview = _preview(fqn="Role.E2ECloneRoleSource", newName="E2ECloneRoleCopy")
    assert any(row["file"].endswith("/Rights.rights") for row in preview["resources"]), preview
    data = _apply(preview, fqn="Role.E2ECloneRoleSource", newName="E2ECloneRoleCopy")
    rights_path = "src/Roles/E2ECloneRoleCopy/Rights.rights"
    assert any(row["file"] == rights_path and row["verified"] for row in data["resources"]), data
    rights = _xml(rights_path)
    source = ET.fromstring(source_rights)
    assert ET.tostring(rights, encoding="unicode") == ET.tostring(source, encoding="unicode"), \
        "the clone's rights model must carry the same rows, RLS and templates"
    row = next(node for node in rights.findall("{*}object") if node.findtext("{*}name") == "Catalog.Catalog")
    assert row.findtext("{*}right/{*}restrictionByCondition/{*}condition") == condition
    assert rights.findtext("{*}restrictionTemplate/{*}name") == "ByName"
    assert _read_bytes("src/Roles/E2ECloneRoleSource/Rights.rights") == source_rights, "source rights unchanged"
    configuration = _xml("src/Configuration/Configuration.mdo")
    assert data["fqn"] in [node.text for node in configuration.findall("roles")]
    assert data["fqn"] not in [node.text for node in configuration.findall("defaultRoles")]
    assert_contains(_details("Role.E2ECloneRoleCopy"), "Catalog.Catalog", "cloned rights model read-back")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_preserves_named_forms_modules_templates_types_and_predefined():
    code = "Procedure CloneProbe() Export\n    Value = Catalogs.Catalog.EmptyRef();\nEndProcedure\n"
    modules = ["Catalogs/Catalog/ObjectModule.bsl", "Catalogs/Catalog/ManagerModule.bsl",
               "Catalogs/Catalog/Forms/ItemForm/Module.bsl"]
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Command.CloneCommand"}), "seed own command")
    modules.append("Catalogs/Catalog/Commands/CloneCommand/CommandModule.bsl")
    for module in modules:
        assert_ok(call("write_module_source", {"projectName": PROJECT, "modulePath": module,
                  "mode": "replace", "source": code, "overwrite": True}), "seed owned module")
    for name, kind, target in (("SelfRef", "CatalogRef", "Catalog"), ("ExternalRef", "CatalogRef", "E2ECloneExternal")):
        if target != "Catalog":
            assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog." + target}), "seed external target")
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + name}), "seed reference attribute")
        assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + name,
                  "properties": [{"name": "type", "value": {"types": [{"kind": kind, "ref": target}]}}]}), "set reference type")
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Predefined.CloneSeed",
              "properties": [{"name": "code", "value": "819"}]}), "seed native predefined identity")
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute.CloneMain"}), "seed form main attribute")
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute.CloneMain",
              "properties": [{"name": "type", "value": {"types": [{"kind": "CatalogObject", "ref": "Catalog"}]}},
                             {"name": "main", "value": True}]}), "set form main self type")
    wait_for_project_ready()
    source_hash = _hash_file("src/Catalogs/Catalog/Catalog.mdo")
    form_before = _xml("src/Catalogs/Catalog/Forms/ItemForm/Form.form")
    template_hash = _hash_file("src/Catalogs/Catalog/Templates/Invoice/Template.mxlx")
    module_bytes = {module: _read_bytes("src/" + module) for module in modules}
    before = tree_snapshot()
    preview = _preview(fqn="Catalog.Catalog", newName="E2ERichClone")
    _assert_only_external_incoming(preview)
    assert {"src/" + path for path in modules} <= {row["file"] for row in preview["resources"]}, preview
    assert_tree_unchanged(before, "rich source preview")
    data = _apply(preview, fqn="Catalog.Catalog", newName="E2ERichClone")
    _assert_only_external_incoming(data)
    name = data["fqn"].split(".")[1]
    folder = "src/Catalogs/" + name
    root = _xml(folder + "/" + name + ".mdo")
    assert root.findtext("defaultObjectForm") == data["fqn"] + ".Form.ItemForm"
    assert root.find("forms/name").text == "ItemForm"
    assert root.find("templates/name").text == "Invoice"
    form = _xml(folder + "/Forms/ItemForm/Form.form")
    expected_form = ET.tostring(form_before, encoding="unicode").replace("CatalogObject.Catalog", "CatalogObject." + name)
    assert ET.tostring(form, encoding="unicode") == expected_form, "complete form values and self type must be preserved/retargeted"
    attributes = {node.findtext("name"): node for node in root.findall("attributes")}
    assert attributes["SelfRef"].findtext("type/types") == "CatalogRef." + name
    assert attributes["ExternalRef"].findtext("type/types") == "CatalogRef.E2ECloneExternal"
    original = _xml("src/Catalogs/Catalog/Catalog.mdo")
    original_item = next(node for node in original.iter() if node.findtext("name") == "CloneSeed")
    copied_item = next(node for node in root.iter() if node.findtext("name") == "CloneSeed")
    original_id = original_item.get("id") or original_item.findtext("id")
    assert original_id and (copied_item.get("id") or copied_item.findtext("id")) == original_id
    assert _hash_file(folder + "/Templates/Invoice/Template.mxlx") == template_hash
    assert _hash_file("src/Catalogs/Catalog/Catalog.mdo") == source_hash
    expected_hits = set()
    for module in modules:
        copied = module.replace("Catalogs/Catalog/", "Catalogs/" + name + "/")
        _assert_module_copy("src/" + module, "src/" + copied, module_bytes[module])
        expected_hits.add(("src/" + copied, 2))
    assert expected_hits <= {(hit["file"], hit["line"]) for hit in data["codeReferences"]}, data
    assert data["namePolicy"] == "callerSupplied"
    assert_contains(_details(data["fqn"]), "ItemForm", "cloned form read-back")
    assert_contains(_details(data["fqn"] + ".Form.ItemForm"), "ItemForm", "form ownership read-back")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_auto_name_is_actual_created_fqn():
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Report.E2EAutoSource"}), "seed auto-name source")
    preview = _preview(fqn="Report.E2EAutoSource")
    data = _apply(preview, fqn="Report.E2EAutoSource")
    name = data["fqn"].split(".")[1]
    assert _xml("src/Reports/%s/%s.mdo" % (name, name)).findtext("name") == name
    assert data["namePolicy"] == "edtAutomatic" and name != "E2EAutoSource"
    assert_contains(_details(data["fqn"]), name, "actual native free name")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_ignores_unowned_sidecar_files():
    note = os.path.join(PROJECT_DIR, "src", "Catalogs", "Catalog", "README.clone819.txt")
    with open(note, "w", encoding="utf-8") as stream:
        stream.write("Unowned note; native paste must not copy this file.\n")
    assert_ok(call("clean_project", {"projectName": PROJECT}), "refresh source folder containing sidecar")
    wait_for_project_ready()
    before = tree_snapshot()
    preview = _preview(fqn="Catalog.Catalog", newName="E2ESidecarClone")
    assert all(not row["file"].endswith("README.clone819.txt") for row in preview["resources"]), preview
    assert_tree_unchanged(before, "preview must leave unowned note unchanged")
    data = _apply(preview, fqn="Catalog.Catalog", newName="E2ESidecarClone")
    assert _xml("src/Catalogs/E2ESidecarClone/E2ESidecarClone.mdo").findtext("name") == "E2ESidecarClone"
    assert _xml("src/Catalogs/E2ESidecarClone/Forms/ItemForm/Form.form").tag == "{http://g5.1c.ru/v8/dt/form}Form"
    assert not os.path.exists(os.path.join(PROJECT_DIR, "src", "Catalogs", "E2ESidecarClone", "README.clone819.txt"))
    with open(note, encoding="utf-8") as stream:
        assert stream.read() == "Unowned note; native paste must not copy this file.\n"
    assert_contains(_details(data["fqn"]), "ItemForm", "native graph must still be copied completely")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_reports_and_copies_only_selected_incoming_relations():
    condition = "WHERE Description IN (\n    SELECT Description FROM Catalog.Catalog\n)"
    for name in ("E2ECloneSelected", "E2ECloneUntouched"):
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Subsystem." + name}), "seed subsystem")
        assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": "Subsystem." + name,
                  "properties": [{"name": "content", "value": ["Catalog.Catalog"]}]}), "seed membership")
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Role.E2ECloneRole"}), "seed role")
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": "Role.E2ECloneRole",
              "rights": [{"object": "Catalog.Catalog", "right": "Read", "value": "set",
                          "rls": condition, "rlsFields": ["Description"]}]}), "seed authored rights and RLS field")
    wait_for_project_ready()
    role_path = "src/Roles/E2ECloneRole/Rights.rights"
    source_row = next(row for row in _xml(role_path).findall("{*}object")
                      if row.findtext("{*}name") == "Catalog.Catalog")
    selection = [{"kind": "subsystem", "owner": "Subsystem.E2ECloneSelected"},
                 {"kind": "roleRights", "owner": "Role.E2ECloneRole"}]
    base = _preview(fqn="Catalog.Catalog", newName="E2ECloneTransfers")
    _assert_only_external_incoming(base)
    assert {row["owner"] for row in base["incomingRelations"] if row["transferSupport"] == "supported"} >= {
        "Subsystem.E2ECloneSelected", "Subsystem.E2ECloneUntouched", "Role.E2ECloneRole"}
    before = tree_snapshot()
    refused = assert_error(_clone(fqn="Catalog.Catalog", newName="E2ECloneTransfers", transfer=selection,
                                 confirm=True, expectedHash=base["contentHash"]), "selection scope drift")
    assert_error_quality(refused, names=["expectedHash"], suggests=["preview", "without confirm"])
    assert_tree_unchanged(before, "changed selection must require a new hash")
    _absent("Catalog.E2ECloneTransfers")
    preview = _preview(fqn="Catalog.Catalog", newName="E2ECloneTransfers", transfer=selection)
    assert preview["contentHash"] != base["contentHash"]
    untouched_hash = _hash_file("src/Subsystems/E2ECloneUntouched/E2ECloneUntouched.mdo")
    data = _apply(preview, fqn="Catalog.Catalog", newName="E2ECloneTransfers", transfer=selection)
    assert _xml("src/Catalogs/E2ECloneTransfers/Forms/ItemForm/Form.form").tag == "{http://g5.1c.ru/v8/dt/form}Form"
    assert _hash_file("src/Subsystems/E2ECloneUntouched/E2ECloneUntouched.mdo") == untouched_hash
    selected = _xml("src/Subsystems/E2ECloneSelected/E2ECloneSelected.mdo")
    assert {row.text for row in selected.findall("content")} == {"Catalog.Catalog", data["fqn"]}
    rights = _xml(role_path)
    objects = {node.findtext("{*}name"): node for node in rights.findall("{*}object")}
    assert {"Catalog.Catalog", data["fqn"]} <= set(objects)
    for fqn in ("Catalog.Catalog", data["fqn"]):
        assert objects[fqn].findtext("{*}right/{*}name") == "Read"
        assert objects[fqn].findtext("{*}right/{*}value") == "true"
    assert _rights_row_xml(objects["Catalog.Catalog"]) == _rights_row_xml(source_row), "Source rights row changed"
    _assert_rights_copy(source_row, objects[data["fqn"]], data["fqn"])
    assert objects[data["fqn"]].findtext("{*}right/{*}restrictionByCondition/{*}condition") == source_row.findtext(
        "{*}right/{*}restrictionByCondition/{*}condition"), "RLS query text must remain verbatim"
    assert data["transferred"] == selection
    role_text = read_disk(role_path)
    clone_row_start = role_text.index("<name>" + data["fqn"] + "</name>")
    condition_start = role_text.index("<condition>", clone_row_start)
    source_mention = role_text.index("Catalog.Catalog", condition_start)
    assert source_mention < role_text.index("</condition>", condition_start)
    rls_line = role_text[:source_mention].count("\n") + 1
    assert (role_path, rls_line) in {(hit["file"], hit["line"]) for hit in data["codeReferences"]}, data
    assert all(hit["file"].startswith("src/Catalogs/E2ECloneTransfers/") or hit["file"] == role_path
               for hit in data["codeReferences"]), data
    assert any(row["owner"] == "Subsystem.E2ECloneUntouched" for row in data["notCopied"])
    assert_contains(_details(data["fqn"]), "E2ECloneTransfers", "clone model")
    assert_contains(_details("Role.E2ECloneRole"), data["fqn"], "transferred rights model")
    content = call("get_subsystem_content", {"projectName": PROJECT, "subsystemFqn": "Subsystem.E2ECloneSelected"})
    assert_ok(content, "transferred subsystem model")
    assert_contains(content.text, "E2ECloneTransfers", "selected membership model")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_source_drift_and_name_collision_write_nothing():
    preview = _preview(fqn="Catalog.Catalog", newName="E2ECloneDrift")
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.E2ECloneOther"}), "change destination collection")
    before = tree_snapshot()
    message = assert_error(_clone(fqn="Catalog.Catalog", newName="E2ECloneDrift", confirm=True,
                                 expectedHash=preview["contentHash"]), "stale destination")
    assert_error_quality(message, names=["expectedHash"], suggests=["without confirm"])
    assert_tree_unchanged(before, "stale preview must create no files or object")
    _absent("Catalog.E2ECloneDrift")
    message = assert_error(_clone(fqn="Catalog.Catalog", newName="cAtAlOg"), "case-insensitive collision")
    assert_error_quality(message, names=["cAtAlOg"], suggests=["unused", "omit newName"])
    assert_tree_unchanged(before, "collision must create no files")
    assert_contains(_details("Catalog.Catalog"), "Catalog", "source remains")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_source_module_drift_refuses_before_copy():
    path = "Catalogs/Catalog/ObjectModule.bsl"
    source = "Procedure Probe() Export\nEndProcedure\n"
    assert_ok(call("write_module_source", {"projectName": PROJECT, "modulePath": path,
              "mode": "replace", "source": source, "overwrite": True}), "seed source module")
    wait_for_project_ready()
    preview = _preview(fqn="Catalog.Catalog", newName="E2EFileDriftClone")
    assert_ok(call("write_module_source", {"projectName": PROJECT, "modulePath": path,
              "mode": "replace", "source": source + "// source changed\n", "overwrite": True}), "change source bytes")
    before = tree_snapshot()
    message = assert_error(_clone(fqn="Catalog.Catalog", newName="E2EFileDriftClone", confirm=True,
                                 expectedHash=preview["contentHash"]), "stale source resource")
    assert_error_quality(message, names=["expectedHash"], suggests=["without confirm"])
    assert_tree_unchanged(before, "source drift may not create files")
    _absent("Catalog.E2EFileDriftClone")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_negative_contract():
    cases = [
        ({"fqn": "AccumulationRegister.X"}, ["AccumulationRegister.X"], ["CommonForm", "ExchangePlan"]),
        ({"fqn": "Catalog.Catalog.Form.ItemForm"}, ["ItemForm"], ["top object"]),
        ({"fqn": "Catalog.Catalog", "confirm": True}, ["expectedHash"], ["without confirm"]),
        ({"fqn": "Catalog.Catalog", "newName": "1bad"}, ["1bad"], ["identifier"]),
        ({"fqn": "Catalog.Catalog", "transfer": [{"kind": "exchangePlan", "owner": "ExchangePlan.X"}]},
         ["exchangePlan"], ["reported only"]),
        ({"fqn": "Catalog.DoesNotExist819"}, ["DoesNotExist819"], ["get_metadata_objects"]),
        ({"projectName": EXT_OBJECTS_PROJECT, "fqn": "Catalog.Catalog"}, [EXT_OBJECTS_PROJECT],
         ["configuration or extension project"]),
    ]
    for arguments, names, suggests in cases:
        message = assert_error(call("clone_metadata_object", {"projectName": PROJECT, **arguments}), "invalid clone")
        assert_error_quality(message, names=names, suggests=suggests)
        assert_no_diff()
    message = assert_error(call("clone_metadata_object", {}), "required arguments")
    assert_error_quality(message, names=["projectName"], suggests=["required", "list_projects"])
    assert_no_diff()


# ──────────────────────────────────────────────────────────────────────────────
# Extension project (issue #933 PR-2): an extension's OWN objects clone inside that extension.
# The fixture extension owns Catalog.tests_ExtOnly, Role.tests_DefaultRole (with a rights row for
# that catalog) and CommonModule.tests_MathHelper; it adopts Catalog.Catalog and CommonModule.Calc.
# Mutating tests restore the extension themselves (restore_extension_fixture), like adopt's tests.
# ──────────────────────────────────────────────────────────────────────────────
_EXT_SOURCE = "Catalog.tests_ExtOnly"
_EXT_CONFIGURATION = "src/Configuration/Configuration.mdo"
_EXT_ROLE_RIGHTS = "src/Roles/tests_DefaultRole/Rights.rights"
_ADOPTED_SUBSYSTEM = "Subsystem.Subsystem"
_OWN_SUBSYSTEM = "Subsystem.tests_E2ECloneGroup"
# "ОбщийМодуль" - the Russian type token, written as escapes like the Java tests.
_RU_COMMON_MODULE = "ОбщийМодуль"


def _ext_clone(**arguments):
    return call("clone_metadata_object", {"projectName": TESTS_PROJECT, **arguments})


def _ext_bytes(path):
    with open(os.path.join(REPO_ROOT, *TESTS_PROJECT_REL.split("/"), *path.split("/")), "rb") as stream:
        return stream.read()


def _ext_xml(path):
    return ET.fromstring(read_fixture_file(TESTS_PROJECT_REL, path))


def _ext_state():
    return status_porcelain_rel(TESTS_PROJECT_REL), diff_rel(TESTS_PROJECT_REL)


def _ext_preview(**arguments):
    result = _ext_clone(**arguments)
    assert_ok(result, "extension clone preview")
    data = result.structured
    assert isinstance(data, dict) and data.get("action") == "preview", data
    assert data["writtenProjects"] == [], data
    return data


def _ext_apply(preview, **arguments):
    result = _ext_clone(**arguments, confirm=True, expectedHash=preview["contentHash"])
    assert_ok(result, "native clone inside the extension")
    data = result.structured
    assert data["action"] == "cloned" and data["persisted"] is True, data
    assert data["writtenProjects"] == [TESTS_PROJECT], "the clone writes the extension and nothing else: %r" % data
    assert data["resources"] and all(row["verified"] for row in data["resources"]), data
    return data


def _ext_registered(tag, fqn):
    return fqn in [node.text for node in _ext_xml(_EXT_CONFIGURATION).findall(tag)]


def _child_tags(root):
    return [child.tag for child in root]


def _support(data, kind, owner):
    rows = [row for row in data["incomingRelations"] if row["kind"] == kind and row["owner"] == owner]
    assert len(rows) == 1, "expected one %s relation for %s: %r" % (kind, owner, data["incomingRelations"])
    return rows[0]["transferSupport"]


def _settled_ext_preview(timeout=120, **arguments):
    """The preview once the extension's files and the preview's contentHash both hold still for 10 s.
    Right after an adoption and a write into the adopted object EDT may still change the extension's
    model and files once after list_projects reports ready (measured on the stand: an identical preview
    repeated 15 s later had a new hash, and the extension's files changed under a refused call); the hash
    rightly refuses a confirm across that change, so the test waits it out instead of racing it."""
    deadline = time.time() + timeout
    previous = (_ext_state(), _ext_preview(**arguments))
    while True:
        time.sleep(10)
        current = (_ext_state(), _ext_preview(**arguments))
        if current[0] == previous[0] and current[1]["contentHash"] == previous[1]["contentHash"]:
            return current[1]
        assert time.time() < deadline, "the extension kept changing for %ss after its seeds" % timeout
        previous = current


def _restoring_extension(ctx, body):
    try:
        body()
    except E2ECallTimeout:
        # NO cleanup: the timed-out call may still be writing these files; the orchestrator aborts.
        raise
    except BaseException:
        restore_extension_fixture(ctx)
        raise
    else:
        restore_extension_fixture(ctx)


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_extension_own_object_stays_own_and_transfers_only_into_own_owners():
    """An own catalog of the extension clones inside the extension with EDT's extension semantics:
    the copy is an own (Native) object of the same shape as the source, registered in the extension's
    Configuration.mdo. Its own subsystem and own role are transferable; the adopted base subsystem that
    lists it is reportedOnly, refused when selected, and never written."""
    def body():
        assert_ok(call("create_metadata", {"projectName": TESTS_PROJECT, "fqn": _OWN_SUBSYSTEM}),
                  "seed an own subsystem of the extension")
        assert_ok(call("modify_metadata", {"projectName": TESTS_PROJECT, "fqn": _OWN_SUBSYSTEM,
                  "properties": [{"name": "content", "value": [_EXT_SOURCE]}]}), "own subsystem lists the source")
        assert_ok(call("adopt_metadata_object", {"projectName": PROJECT, "fqn": _ADOPTED_SUBSYSTEM,
                  "extensionProjectName": TESTS_PROJECT}), "adopt the base subsystem into the extension")
        assert_ok(call("modify_metadata", {"projectName": TESTS_PROJECT, "fqn": _ADOPTED_SUBSYSTEM,
                  "properties": [{"name": "content", "value": [_EXT_SOURCE]}]}), "adopted subsystem lists the source")
        settle_or_fail("the extension clone preview")
        base = _settled_ext_preview(fqn=_EXT_SOURCE, newName="tests_E2ECloneCopy")
        source_path = "src/Catalogs/tests_ExtOnly/tests_ExtOnly.mdo"
        adopted_path = "src/Subsystems/Subsystem/Subsystem.mdo"
        source_bytes = _ext_bytes(source_path)
        adopted_bytes = _ext_bytes(adopted_path)
        source = ET.fromstring(source_bytes)
        assert base["sourceFqn"] == _EXT_SOURCE, base
        assert _support(base, "subsystem", _OWN_SUBSYSTEM) == "supported"
        assert _support(base, "roleRights", "Role.tests_DefaultRole") == "supported"
        assert _support(base, "subsystem", _ADOPTED_SUBSYSTEM) == "reportedOnly"
        assert "namePrefix" not in base["warning"], "a prefixed newName carries no prefix warning: %r" % base

        before = _ext_state()
        refused = assert_error(_ext_clone(fqn=_EXT_SOURCE, newName="tests_E2ECloneCopy",
                                          transfer=[{"kind": "subsystem", "owner": _ADOPTED_SUBSYSTEM}]),
                               "selecting an adopted owner")
        assert_error_quality(refused, names=[_ADOPTED_SUBSYSTEM, "adopted"], suggests=["notCopied"])
        assert "resync_to_disk" not in refused, "an adopted owner is not a stale source: " + refused
        after = _ext_state()
        assert after == before, "a refused selection writes nothing into the extension:\nBEFORE %s\nAFTER %s" % (
            before[0], after[0])

        selection = [{"kind": "subsystem", "owner": _OWN_SUBSYSTEM},
                     {"kind": "roleRights", "owner": "Role.tests_DefaultRole"}]
        preview = _settled_ext_preview(fqn=_EXT_SOURCE, newName="tests_E2ECloneCopy", transfer=selection)
        data = _ext_apply(preview, fqn=_EXT_SOURCE, newName="tests_E2ECloneCopy", transfer=selection)
        assert data["fqn"] == "Catalog.tests_E2ECloneCopy", data
        assert data["transferred"] == selection, data
        assert any(row["owner"] == _ADOPTED_SUBSYSTEM for row in data["notCopied"]), data
        assert_no_diff("a clone inside the extension must not touch the base project")

        # Disk first. The clone is an own object exactly like its source: no objectBelonging and no
        # extension block, the same element sequence, new identities.
        root = _ext_xml("src/Catalogs/tests_E2ECloneCopy/tests_E2ECloneCopy.mdo")
        assert root.findtext("name") == "tests_E2ECloneCopy"
        assert root.attrib["uuid"] != source.attrib["uuid"]
        assert root.find("objectBelonging") is None and root.find("extension") is None, \
            ET.tostring(root, encoding="unicode")
        assert _child_tags(root) == _child_tags(source), (_child_tags(root), _child_tags(source))
        assert root.find("attributes/name").text == "ExtValue"
        assert root.find("attributes").attrib["uuid"] != source.find("attributes").attrib["uuid"]
        assert _ext_bytes(source_path) == source_bytes, "the source must stay byte-identical"
        assert _ext_registered("catalogs", "Catalog.tests_E2ECloneCopy")
        own = _ext_xml("src/Subsystems/tests_E2ECloneGroup/tests_E2ECloneGroup.mdo")
        assert {node.text for node in own.findall("content")} == {_EXT_SOURCE, "Catalog.tests_E2ECloneCopy"}
        assert _ext_bytes(adopted_path) == adopted_bytes, "the adopted subsystem must never be written"
        rights = {node.findtext("{*}name"): node for node in _ext_xml(_EXT_ROLE_RIGHTS).findall("{*}object")}
        assert {_EXT_SOURCE, "Catalog.tests_E2ECloneCopy"} <= set(rights), set(rights)
        _assert_rights_copy(rights[_EXT_SOURCE], rights["Catalog.tests_E2ECloneCopy"], "Catalog.tests_E2ECloneCopy")

        details = call("get_metadata_details", {"projectName": TESTS_PROJECT,
                                                "objectFqns": ["Catalog.tests_E2ECloneCopy"], "full": True})
        assert_ok(details, "clone read-back from the extension model")
        assert_contains(details.text, "ExtValue", "the owned attribute resolves from the cloned model")
    _restoring_extension("an extension clone with transfers", body)


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_extension_role_and_common_module_keep_rights_and_text():
    """An own role clones with its rights model (and stays out of the extension's defaultRoles); an own
    common module clones with its module text byte for byte."""
    def body():
        source_rights = _ext_bytes(_EXT_ROLE_RIGHTS)
        role = _ext_apply(_ext_preview(fqn="Role.tests_DefaultRole", newName="tests_E2ERoleCopy"),
                          fqn="Role.tests_DefaultRole", newName="tests_E2ERoleCopy")
        assert role["fqn"] == "Role.tests_E2ERoleCopy", role
        clone_rights = "src/Roles/tests_E2ERoleCopy/Rights.rights"
        assert any(row["file"] == clone_rights and row["verified"] for row in role["resources"]), role
        assert ET.tostring(_ext_xml(clone_rights), encoding="unicode") == ET.tostring(
            ET.fromstring(source_rights), encoding="unicode"), "the clone carries the same rights model"
        assert _ext_bytes(_EXT_ROLE_RIGHTS) == source_rights, "source rights unchanged"
        assert _ext_registered("roles", "Role.tests_E2ERoleCopy")
        assert not _ext_registered("defaultRoles", "Role.tests_E2ERoleCopy"), "a clone never joins defaultRoles"

        module_path = "src/CommonModules/tests_MathHelper/Module.bsl"
        module_bytes = _ext_bytes(module_path)
        # The Russian TYPE token addresses the same own module.
        preview = _ext_preview(fqn=_RU_COMMON_MODULE + ".tests_MathHelper", newName="tests_E2EMathCopy")
        assert preview["sourceFqn"] == "CommonModule.tests_MathHelper", preview
        data = _ext_apply(preview, fqn=_RU_COMMON_MODULE + ".tests_MathHelper", newName="tests_E2EMathCopy")
        assert _ext_bytes("src/CommonModules/tests_E2EMathCopy/Module.bsl") == module_bytes, \
            "module text is copied unchanged"
        assert _ext_bytes(module_path) == module_bytes, "source module unchanged"
        assert _ext_xml("src/CommonModules/tests_E2EMathCopy/tests_E2EMathCopy.mdo").findtext("server") == "true"
        assert _ext_registered("commonModules", data["fqn"])
    _restoring_extension("extension role and common module clones", body)


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_extension_refuses_adopted_sources_and_warns_about_the_prefix():
    """Adopted base objects are not the extension's own objects: refused finally, in both TYPE-token
    languages, with nothing written. A newName without the extension's namePrefix previews with
    EDT's extension-md-object-prefix warning."""
    assert_no_diff_rel(TESTS_PROJECT_REL, "the extension fixture starts clean")
    for fqn, address in (("Catalog.Catalog", "Catalog.Catalog"),
                         (_RU_COMMON_MODULE + ".Calc", "CommonModule.Calc")):
        message = assert_error(_ext_clone(fqn=fqn, newName="tests_E2EAdoptedCopy"), "adopted source " + fqn)
        assert_error_quality(message, names=[address, "objectBelonging=Adopted"],
                             suggests=["base configuration", "adopt_metadata_object"])
        assert "resync_to_disk" not in message, "an adopted source is not a stale source: " + message
    assert_no_diff_rel(TESTS_PROJECT_REL, "a refused adopted source writes nothing")
    warned = _ext_preview(fqn=_EXT_SOURCE, newName="E2ENoPrefixCopy")
    assert "namePrefix 'tests_'" in warned["warning"] and "'E2ENoPrefixCopy'" in warned["warning"], warned
    assert "extension-md-object-prefix" in warned["warning"], warned
    automatic = _ext_preview(fqn=_EXT_SOURCE)
    assert "namePrefix" not in automatic["warning"], "the automatic name keeps the source's prefix: %r" % automatic
    assert_no_diff_rel(TESTS_PROJECT_REL, "previews write nothing")
    assert_no_diff("previews in the extension never touch the base")


# ──────────────────────────────────────────────────────────────────────────────
# Between projects (issue #933 PR-3): targetProjectName writes the clone into ANOTHER configuration or
# extension project, with the destination's copier semantics. References the destination lacks are
# refused before any write, with the list; nothing is adopted automatically.
# ──────────────────────────────────────────────────────────────────────────────
def _cross(project, **arguments):
    return call("clone_metadata_object", {"projectName": project, **arguments})


def _settled_cross_preview(project, timeout=120, **arguments):
    """A cross-project preview once both fixtures and the contentHash hold still for 10 s (see
    _settled_ext_preview: the extension's model may change once more after list_projects is ready)."""
    deadline = time.time() + timeout

    def take():
        result = _cross(project, **arguments)
        assert_ok(result, "cross-project clone preview")
        data = result.structured
        assert isinstance(data, dict) and data.get("action") == "preview", data
        assert data["writtenProjects"] == [], data
        return (_ext_state(), status_porcelain_rel(PROJECT_REL)), data

    previous = take()
    while True:
        time.sleep(10)
        current = take()
        if current[0] == previous[0] and current[1]["contentHash"] == previous[1]["contentHash"]:
            return current[1]
        assert time.time() < deadline, "the fixtures kept changing for %ss" % timeout
        previous = current


def _cross_apply(project, preview, written, **arguments):
    result = _cross(project, **arguments, confirm=True, expectedHash=preview["contentHash"])
    assert_ok(result, "cross-project clone")
    data = result.structured
    assert data["action"] == "cloned" and data["persisted"] is True, data
    assert data["writtenProjects"] == [written], "only the destination is written: %r" % data
    assert data["targetProjectName"] == written, data
    assert data["resources"] and all(row["verified"] for row in data["resources"]), data
    return data


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_into_the_extension_writes_an_own_object_there_and_nothing_in_the_source():
    """A configuration catalog (only platform types, a form and a template) clones into the extension
    as an OWN object of the extension: no objectBelonging, no extension block, new identities, its
    files copied, registered in the extension's Configuration.mdo. The source project is not touched."""
    def body():
        settle_or_fail("the cross-project clone preview")
        source_path = "src/Catalogs/Catalog/Catalog.mdo"
        source_bytes = _read_bytes(source_path)
        preview = _settled_cross_preview(PROJECT, fqn="Catalog.Catalog", newName="tests_E2ECrossCopy",
                                         targetProjectName=TESTS_PROJECT)
        assert preview["targetProjectName"] == TESTS_PROJECT, preview
        assert preview["selectedTransfers"] == [], preview
        assert all(row["transferSupport"] == "reportedOnly" for row in preview["incomingRelations"]), preview
        assert "written into project '%s' only" % TESTS_PROJECT in preview["warning"], preview
        assert_no_diff_rel(TESTS_PROJECT_REL, "a cross-project preview writes nothing")
        data = _cross_apply(PROJECT, preview, TESTS_PROJECT, fqn="Catalog.Catalog", newName="tests_E2ECrossCopy",
                            targetProjectName=TESTS_PROJECT)
        assert data["fqn"] == "Catalog.tests_E2ECrossCopy", data
        assert_no_diff("a clone into the extension must not touch the source project")
        assert _read_bytes(source_path) == source_bytes

        # Disk first: an own object of the extension, with the source's shape and new identities.
        folder = "src/Catalogs/tests_E2ECrossCopy/"
        root = _ext_xml(folder + "tests_E2ECrossCopy.mdo")
        source = ET.fromstring(source_bytes)
        assert root.findtext("name") == "tests_E2ECrossCopy"
        assert root.attrib["uuid"] != source.attrib["uuid"]
        assert root.find("objectBelonging") is None and root.find("extension") is None, \
            ET.tostring(root, encoding="unicode")
        assert root.find("attributes/name").text == source.find("attributes/name").text
        assert root.find("attributes").attrib["uuid"] != source.find("attributes").attrib["uuid"]
        assert [node.text for node in root.findall("inputByString")] == [
            "Catalog.tests_E2ECrossCopy.StandardAttribute.Code",
            "Catalog.tests_E2ECrossCopy.StandardAttribute.Description"], "own fields follow the clone"
        assert _ext_bytes(folder + "Templates/Invoice/Template.mxlx") == _read_bytes(
            "src/Catalogs/Catalog/Templates/Invoice/Template.mxlx"), "the template is copied byte for byte"
        assert _ext_xml(folder + "Forms/ItemForm/Form.form") is not None
        assert _ext_registered("catalogs", "Catalog.tests_E2ECrossCopy")

        details = call("get_metadata_details", {"projectName": TESTS_PROJECT,
                                                "objectFqns": ["Catalog.tests_E2ECrossCopy"], "full": True})
        assert_ok(details, "clone read-back from the extension model")
        assert_contains(details.text, source.find("attributes/name").text, "the owned attribute resolves there")
    _restoring_extension("a cross-project clone into the extension", body)


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_into_the_extension_refuses_missing_references_and_name_collisions():
    """Before any write: a reference the destination lacks (the document's register record, a register the
    extension has not adopted) is refused with the complete list and the adopt_metadata_object way out;
    a name the extension already has, or an unadopted base name, is refused; transfer and an
    external-objects destination are refused."""
    settle_or_fail("the cross-project refusals")
    missing = _cross(PROJECT, fqn="Document.QueryOrder", newName="tests_E2ECrossOrder",
                     targetProjectName=TESTS_PROJECT)
    message = assert_error(missing, "a document whose register the extension lacks")
    assert_error_quality(message, names=["AccumulationRegister.QueryStock", TESTS_PROJECT],
                         suggests=["adopt_metadata_object", "Nothing was written"])
    assert "resync_to_disk" not in message, "a missing reference is final, not a stale source: " + message
    assert missing.structured["missingReferences"] == ["AccumulationRegister.QueryStock"], missing.structured

    taken = assert_error(_cross(PROJECT, fqn="Catalog.Catalog", targetProjectName=TESTS_PROJECT),
                         "the extension already has Catalog.Catalog (adopted)")
    assert_error_quality(taken, names=["Catalog.Catalog", "already exists", TESTS_PROJECT], suggests=["newName"])
    base = assert_error(_cross(PROJECT, fqn="Document.QueryOrder", targetProjectName=TESTS_PROJECT),
                        "an unadopted base name")
    assert_error_quality(base, names=["Document.QueryOrder", "base configuration"],
                         suggests=["newName", "adopt_metadata_object"])
    transfer = assert_error(_cross(PROJECT, fqn="Catalog.Catalog", newName="tests_E2ECrossCopy",
                                   targetProjectName=TESTS_PROJECT,
                                   transfer=[{"kind": "subsystem", "owner": "Subsystem.Subsystem"}]),
                            "transfer across projects")
    assert_error_quality(transfer, names=["transfer", TESTS_PROJECT], suggests=["modify_metadata"])
    external = assert_error(_cross(PROJECT, fqn="Catalog.Catalog", newName="E2ECrossCopy",
                                   targetProjectName=EXT_OBJECTS_PROJECT), "an external-objects destination")
    assert_error_quality(external, names=[EXT_OBJECTS_PROJECT], suggests=["configuration or extension project"])
    unknown = assert_error(_cross(PROJECT, fqn="Catalog.Catalog", newName="E2ECrossCopy",
                                  targetProjectName="NoSuchProject_e2e"), "an unknown destination")
    assert_error_quality(unknown, names=["NoSuchProject_e2e"], suggests=["list_projects"])
    assert_no_diff("refusals write nothing in the source")
    assert_no_diff_rel(TESTS_PROJECT_REL, "refusals write nothing in the extension")


@e2e_test(tool="clone_metadata_object", kind="write-metadata")
def test_clone_from_the_extension_into_the_configuration():
    """An own catalog of the extension clones into its base configuration as a plain configuration object;
    the extension is not touched. Its own role cannot: the rights model names that own catalog, which the
    configuration does not have."""
    settle_or_fail("the extension-to-configuration clone")
    source_bytes = _ext_bytes("src/Catalogs/tests_ExtOnly/tests_ExtOnly.mdo")
    rights = assert_error(_cross(TESTS_PROJECT, fqn="Role.tests_DefaultRole", newName="E2ECrossRole",
                                 targetProjectName=PROJECT), "a role whose rights name an extension object")
    assert_error_quality(rights, names=["Catalog.tests_ExtOnly", PROJECT], suggests=["create_metadata"])
    preview = _settled_cross_preview(TESTS_PROJECT, fqn=_EXT_SOURCE, newName="E2ECrossFromExtension",
                                     targetProjectName=PROJECT)
    assert all(row["transferSupport"] == "reportedOnly" for row in preview["incomingRelations"]), preview
    data = _cross_apply(TESTS_PROJECT, preview, PROJECT, fqn=_EXT_SOURCE, newName="E2ECrossFromExtension",
                        targetProjectName=PROJECT)
    assert data["fqn"] == "Catalog.E2ECrossFromExtension", data
    assert_no_diff_rel(TESTS_PROJECT_REL, "a clone out of the extension must not touch it")
    root = _xml("src/Catalogs/E2ECrossFromExtension/E2ECrossFromExtension.mdo")
    source = ET.fromstring(source_bytes)
    assert root.findtext("name") == "E2ECrossFromExtension"
    assert root.attrib["uuid"] != source.attrib["uuid"]
    assert root.find("objectBelonging") is None and root.find("extension") is None
    assert root.find("attributes/name").text == "ExtValue"
    assert "Catalog.E2ECrossFromExtension" in [node.text for node in
                                                _xml("src/Configuration/Configuration.mdo").findall("catalogs")]
    assert_contains(_details("Catalog.E2ECrossFromExtension"), "ExtValue", "read back from the configuration model")
