"""EDS structure CRUD (#448); run on a stand with the full create/modify slices.

The harness restores the fixture and model after each write-metadata test.
Table delete registration repair, Cube folders, formatter output and in-tx
reference-list resolution are intentionally stand assertions, not local claims.
After this slice the conductor also checks tools_list (unchanged) and EDT logs.
"""

import os
import xml.etree.ElementTree as ET

from harness import (
    PROJECT, PROJECT_DIR, assert_contains, assert_error, assert_error_quality,
    assert_not_contains, assert_ok, call, e2e_test, poll_disk_contains_all,
    poll_disk_count, poll_disk_path_gone, wait_for_project_ready,
)

EDS = "ExternalDataSource.E2E448Source"
TABLE = EDS + ".Table.T"
FIELD_NAME = "E2E448Field"
FIELD = TABLE + ".Field." + FIELD_NAME
FUNCTION = EDS + ".Function.Fn"
EDS_PATH = "src/ExternalDataSources/E2E448Source/E2E448Source.mdo"
TABLE_PATH = "src/ExternalDataSources/E2E448Source/Tables/T/T.mdo"
RU_EDS = "ВнешнийИсточникДанных"
RU_TABLE = "Таблица"
RU_FIELD = "Поле"
RU_FUNCTION = "Функция"
RU_CUBE = "Куб"


def _create(fqn, kind=None):
    assert wait_for_project_ready(), "EDT must finish indexing before create"
    result = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(result, "create " + fqn)
    assert result.structured.get("action") == "created", result.structured
    if kind:
        assert result.structured.get("kind") == kind, result.structured
    return result


def _seed(table=True, members=False):
    _create(EDS)
    if table:
        result = _create(TABLE, "Table")
        assert result.structured.get("fqn") == TABLE, result.structured
        poll_disk_contains_all(EDS_PATH, ["<tables>" + TABLE + "</tables>"])
        poll_disk_contains_all(TABLE_PATH, ["<parentDataSource>" + EDS + "</parentDataSource>",
                                           "<producedTypes>"])
    if members:
        _create(FIELD, "Field")
        _create(FUNCTION, "Function")


def _modify(fqn, **values):
    assert wait_for_project_ready(), "EDT must finish indexing before modify"
    result = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "properties": [{"name": name, "value": value} for name, value in values.items()],
    })
    assert_ok(result, "modify " + fqn)
    assert result.structured.get("persisted") is True, result.structured
    return result


def _delete(fqn):
    assert wait_for_project_ready(), "EDT must finish indexing before delete preview"
    preview = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(preview, "preview delete " + fqn)
    assert wait_for_project_ready(), "EDT must finish indexing before delete confirm"
    result = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn, "confirm": True})
    assert_ok(result, "delete " + fqn)
    # delete_metadata carries `persisted` only as false, on a partial result.
    assert result.structured.get("persisted") is not False, result.structured
    assert_not_contains(result.text, "not confirmed", "delete verification must finish")
    return result


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_external_data_source_table_defaults_and_russian_canonical_fqn():
    _seed()
    poll_disk_contains_all(TABLE_PATH, ["<tableDataType>NonobjectData</tableDataType>",
                                       "<useStandardCommands>true</useStandardCommands>", "<producedTypes>"])
    result = _create(RU_EDS + ".E2E448Source." + RU_TABLE + ".Russian", "Table")
    assert result.structured.get("fqn") == EDS + ".Table.Russian", result.structured
    poll_disk_contains_all("src/ExternalDataSources/E2E448Source/Tables/Russian/Russian.mdo",
                           ["<parentDataSource>" + EDS + "</parentDataSource>"])


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_external_data_source_field_function_and_command_export_to_their_own_files():
    _seed()
    with open(os.path.join(PROJECT_DIR, EDS_PATH), encoding="utf-8") as stream:
        eds_before = stream.read()
    result = _create(RU_EDS + ".E2E448Source." + RU_TABLE + ".T." + RU_FIELD + "." + FIELD_NAME, "Field")
    assert result.structured.get("fqn") == FIELD, result.structured
    poll_disk_contains_all(TABLE_PATH, ["<tableFields", "<name>" + FIELD_NAME + "</name>", "<types>String</types>",
                                       "<length>10</length>", "<allowNull>true</allowNull>"])
    with open(os.path.join(PROJECT_DIR, EDS_PATH), encoding="utf-8") as stream:
        assert stream.read() == eds_before, "a Field belongs in the Table file"
    result = _create(RU_EDS + ".E2E448Source." + RU_FUNCTION + ".Fn", "Function")
    assert result.structured.get("fqn") == FUNCTION, result.structured
    poll_disk_contains_all(EDS_PATH, ["<functions", "<name>Fn</name>", "<types>String</types>"])
    _create(TABLE + ".Command.Cmd")
    poll_disk_contains_all(TABLE_PATH, ["<commands", "<name>Cmd</name>"])


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_external_data_source_modifies_table_field_and_function_in_the_correct_files():
    _seed(members=True)
    _modify(TABLE, nameInDataSource="dbo.t", tableType="Expression", expressionInDataSource="SELECT 1",
            tableDataType="ObjectData", quickChoice=True)
    poll_disk_contains_all(TABLE_PATH, ["<nameInDataSource>dbo.t</nameInDataSource>",
                                       "<tableType>Expression</tableType>",
                                       "<expressionInDataSource>SELECT 1</expressionInDataSource>",
                                       "<quickChoice>true</quickChoice>"])
    # ObjectData is the EMF default: absence means ObjectData; NonobjectData must disappear.
    poll_disk_count(TABLE_PATH, "<tableDataType>NonobjectData</tableDataType>", 0)
    _modify(FIELD, nameInDataSource="id", type={"types": [{"kind": "Number", "precision": 10, "scale": 2}]},
            allowNull=False)
    xml = poll_disk_contains_all(TABLE_PATH, ["<nameInDataSource>id</nameInDataSource>",
                                            "<types>Number</types>", "<precision>10</precision>",
                                            "<scale>2</scale>"])
    fields = [node for node in ET.fromstring(xml).iter() if node.tag.rsplit("}", 1)[-1] == "tableFields"]
    assert len(fields) == 1, xml
    assert all(node.text != "true" for node in fields[0] if node.tag.rsplit("}", 1)[-1] == "allowNull"), xml
    _modify(FUNCTION, returnValue=True, expressionInDataSource="SELECT COUNT(*)")
    poll_disk_contains_all(EDS_PATH, ["<returnValue>true</returnValue>",
                                     "<expressionInDataSource>SELECT COUNT(*)</expressionInDataSource>"])
    # Native single-field references go through the new grammar too.
    _modify(TABLE, presentationField=FIELD, parentField=FIELD)
    poll_disk_contains_all(TABLE_PATH, ["<presentationField>" + FIELD + "</presentationField>",
                                       "<parentField>" + FIELD + "</parentField>"])


@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_external_data_source_table_details_and_member_assignable_views():
    _seed(members=True)
    _modify(TABLE, nameInDataSource="dbo.t")
    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [TABLE], "full": True})
    assert_ok(details)
    assert_not_contains(details.text, "## Errors", "Table must render as its own top object")
    assert_contains(details.text, "dbo.t")
    assert_contains(details.text, FIELD_NAME)
    details = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": [FIELD, FUNCTION], "assignable": True,
    })
    assert_ok(details)
    for property_name in ["nameInDataSource", "allowNull", "type", "returnValue"]:
        assert_contains(details.text, property_name)
    plain = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [FIELD]})
    assert_contains(plain.text, "This addresses a member of '" + TABLE + "'")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_external_data_source_delete_field_function_and_russian_table_registration():
    _seed(members=True)
    _delete(RU_EDS + ".E2E448Source." + RU_TABLE + ".T." + RU_FIELD + "." + FIELD_NAME)
    poll_disk_count(TABLE_PATH, "<name>" + FIELD_NAME + "</name>", 0)
    _delete(FUNCTION)
    poll_disk_count(EDS_PATH, "<name>Fn</name>", 0)
    result = _delete(RU_EDS + ".E2E448Source." + RU_TABLE + ".T")
    poll_disk_path_gone("src/ExternalDataSources/E2E448Source/Tables/T")
    poll_disk_count(EDS_PATH, "<tables>" + TABLE + "</tables>", 0)
    # The echo keeps the caller's address with only the type token normalized, as for every delete.
    assert result.structured.get("fqn") == EDS + "." + RU_TABLE + ".T", result.structured


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_external_data_source_negative_create_requests_are_actionable():
    _seed()
    for fqn, hint in [
        ("ExternalDataSource.NoSuch.Table.T", "parent must already exist"),
        (EDS + ".Column.X", "It accepts: Table, Cube, Function"),
        (EDS + ".Table.Missing.Field.F", "parent must already exist"),
        (TABLE, "Node already exists"),
    ]:
        result = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
        error = assert_error(result, "invalid EDS create " + fqn)
        assert_error_quality(error, suggests=[hint], ctx=fqn)
    for fqn in [EDS + ".Table. T", EDS + ".Table.", EDS + "..T", TABLE + "."]:
        result = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
        assert_error_quality(assert_error(result), ctx=fqn)
    result = call("create_metadata", {"projectName": PROJECT, "fqn": TABLE + ".Field.New",
                                      "properties": [{"name": "nameInDataSource", "value": "id"}]})
    assert_error_quality(assert_error(result), names=["nameInDataSource"], suggests=["modify_metadata"])


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_external_data_source_cube_create_delete_smoke():
    _seed(table=False)
    result = _create(RU_EDS + ".E2E448Source." + RU_CUBE + ".C", "Cube")
    cube = EDS + ".Cube.C"
    assert result.structured.get("fqn") == cube, result.structured
    poll_disk_contains_all("src/ExternalDataSources/E2E448Source/Cubes/C/C.mdo",
                           ["<parentDataSource>" + EDS + "</parentDataSource>"])
    _delete(cube)
    poll_disk_path_gone("src/ExternalDataSources/E2E448Source/Cubes/C")
    poll_disk_count(EDS_PATH, "<cubes>" + cube + "</cubes>", 0)


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_external_data_source_adopt_and_rename_refuse_members_explicitly():
    _seed()
    for tool, args in [
        ("adopt_metadata_object", {"fqn": TABLE}),
        ("rename_metadata_object", {"objectFqn": TABLE, "newName": "Renamed"}),
    ]:
        result = call(tool, dict(args, projectName=PROJECT))
        assert_error_quality(assert_error(result), names=[tool], suggests=["EDT designer"])


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_external_data_source_role_rights_accept_table_and_field_addresses():
    _seed(members=True)
    role = "Role.E2E448Rights"
    _create(role)
    assert wait_for_project_ready(), "EDT must finish indexing before the rights write"
    written = call("modify_metadata", {
        "projectName": PROJECT, "fqn": role,
        "rights": [{"object": TABLE, "right": "View", "value": "set"},
                   {"object": FIELD, "right": "View", "value": "set"}],
    })
    assert_ok(written, "write EDS table and field role rights")
    poll_disk_contains_all("src/Roles/E2E448Rights/Rights.rights",
                           ["<name>" + TABLE + "</name>", "<name>" + FIELD + "</name>"])
    read = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [role], "full": True})
    assert_ok(read)
    assert_contains(read.text, TABLE)
    assert_contains(read.text, FIELD)
