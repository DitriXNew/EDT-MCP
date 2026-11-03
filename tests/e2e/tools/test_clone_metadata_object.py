"""Native clone contract: preview/drift, all four top types, resources and explicit transfers.

The orchestrator owns fixture resets. Every apply checks disk before another MCP call.
"""
import os
import hashlib
import copy
import xml.etree.ElementTree as ET

from harness import (
    call, assert_ok, assert_error, assert_error_quality, assert_contains,
    assert_no_diff, assert_tree_unchanged, tree_snapshot, read_disk,
    e2e_test, PROJECT, PROJECT_DIR, TESTS_PROJECT, wait_for_project_ready,
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
        ({"fqn": "Role.X"}, ["Role.X"], ["Catalog", "Document"]),
        ({"fqn": "Catalog.Catalog.Form.ItemForm"}, ["ItemForm"], ["top object"]),
        ({"fqn": "Catalog.Catalog", "confirm": True}, ["expectedHash"], ["without confirm"]),
        ({"fqn": "Catalog.Catalog", "newName": "1bad"}, ["1bad"], ["identifier"]),
        ({"fqn": "Catalog.Catalog", "transfer": [{"kind": "exchangePlan", "owner": "ExchangePlan.X"}]},
         ["exchangePlan"], ["reported only"]),
        ({"fqn": "Catalog.DoesNotExist819"}, ["DoesNotExist819"], ["get_metadata_objects"]),
        ({"projectName": TESTS_PROJECT, "fqn": "Catalog.Catalog"}, [TESTS_PROJECT], ["plain configuration"]),
    ]
    for arguments, names, suggests in cases:
        message = assert_error(call("clone_metadata_object", {"projectName": PROJECT, **arguments}), "invalid clone")
        assert_error_quality(message, names=names, suggests=suggests)
        assert_no_diff()
    message = assert_error(call("clone_metadata_object", {}), "required arguments")
    assert_error_quality(message, names=["projectName"], suggests=["required", "list_projects"])
    assert_no_diff()
