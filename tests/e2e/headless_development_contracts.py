"""Development acceptance against a disposable project; disk reads precede rescue calls."""

import json
import re
import subprocess
import tempfile
from pathlib import Path
import xml.etree.ElementTree as ET
from harness import assert_ok, assert_contains, poll_disk_contains_all, wait_for_named_projects_ready


READ_TOOLS = ("go_to_definition", "find_references", "get_method_call_hierarchy", "validate_query",
              "get_configuration_properties", "get_outgoing_structures", "list_subsystems",
              "get_subsystem_content", "get_platform_documentation")
WRITE_TOOLS = ("create_metadata", "modify_metadata", "sort_metadata", "clone_metadata_object",
               "rename_metadata_object", "delete_metadata", "resync_to_disk", "dcs",
               "business_process_route_map", "adopt_metadata_object", "project_checks", "merge_rules", "git",
               "apply_quick_fix", "delete_project", "delete_infobase", "update_database",
               "evaluate_expression", "infobase_sessions", "infobase_extensions", "ask_workmate")
READY_WRITES = ("create_metadata", "modify_metadata", "sort_metadata", "clone_metadata_object",
                "rename_metadata_object", "delete_metadata", "dcs", "business_process_route_map",
                "adopt_metadata_object", "project_checks", "delete_project")


def prepare_quick_fix_fixture(project):
    project = Path(project)
    root = project / "src/Configuration/Configuration.mdo"
    shared_uuid = ET.fromstring((project / "src/Catalogs/Catalog/Catalog.mdo").read_bytes()).attrib["uuid"]
    text = root.read_text(encoding="utf-8")
    for name in ("HeadlessFixEnglish", "ИсправлениеБезОкна"):
        folder = project / "src/Catalogs" / name
        folder.mkdir()
        (folder / (name + ".mdo")).write_text(
            '<mdclass:Catalog xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass" uuid="'
            + shared_uuid + '"><name>' + name + '</name></mdclass:Catalog>\n', encoding="utf-8")
        text = text.replace("</mdclass:Configuration>", "  <catalogs>Catalog." + name
                            + "</catalogs>\n</mdclass:Configuration>")
    root.write_text(text, encoding="utf-8")


def run_quick_fix(invoke, project):
    project = Path(project)
    files = [project / "src/Catalogs" / name / (name + ".mdo")
             for name in ("Catalog", "HeadlessFixEnglish", "ИсправлениеБезОкна")]
    before = [path.read_bytes() for path in files]
    result = _ok(invoke, "apply_quick_fix", {"projectName": "TestConfiguration",
        "checkId": "md-object-duplicate-uuid", "index": 1})
    after = [path.read_bytes() for path in files]
    assert before != after, "a real quick fix must reach disk before another MCP call"
    assert result.structured["appliedVariant"], result.structured
    assert all(_registered(project, "Catalog." + name) for name in ("HeadlessFixEnglish", "ИсправлениеБезОкна"))


def failure_details(result):
    details = []
    if isinstance(result.structured, dict) and result.structured.get("error"):
        details.append(str(result.structured["error"]))
    for part in result.result.get("content", []):
        text = part.get("text", part.get("resource", {}).get("text", ""))
        if text and text not in details:
            details.append(text)
    if result.rpc_error:
        details.append(json.dumps(result.rpc_error, ensure_ascii=False))
    return "\n".join(details)


def _ok(invoke, name, arguments):
    if name in READY_WRITES and arguments.get("projectName"):
        project = arguments["projectName"]
        states = wait_for_named_projects_ready([project], ctx=name, invoke=invoke)
        assert states.get(project) == "ready", "%s requires a ready project: %r" % (name, states)
    result = invoke(name, arguments)
    assert_ok(result, name + " on the unattended host\n" + failure_details(result))
    return result


def _root(project):
    path = Path(project) / "src/Configuration/Configuration.mdo"
    return ET.fromstring(path.read_bytes())


def _registered(project, fqn):
    return any(node.text == fqn for node in _root(project))


def run_metadata_writes(invoke, project, extension=None):
    project = Path(project)
    p = "TestConfiguration"
    src = project / "src"
    language = _root(project).findtext("languages/languageCode")
    assert language, "the disposable fixture must declare its synonym language code"
    autosort = {path: path.read_bytes() for path in (project / ".settings").glob("*autosort*")}
    for name, token in (("HeadlessCatalog", "Catalog"), ("КаталогБезОкна", "Справочник")):
        fqn = token + "." + name
        canonical = "Catalog." + name
        args = {"projectName": p, "fqn": fqn}
        synonym = "Headless display " + name
        created = _ok(invoke, "create_metadata", dict(args, properties=[
            {"name": "synonym", "value": synonym, "language": language}]))
        file = src / "Catalogs" / name / (name + ".mdo")
        assert file.is_file() and _registered(project, canonical), "create must export object and root"
        assert created.structured["synonym"] == synonym and created.structured["language"] == language
        localized = {entry.findtext("key"): entry.findtext("value")
                     for entry in ET.fromstring(file.read_bytes()).findall("synonym")}
        assert localized[language] == synonym, "the language-code synonym must reach disk before another call"
        _ok(invoke, "modify_metadata", dict(args, properties=[{"name": "comment", "value": "Disk proof " + name}]))
        assert "Disk proof " + name in file.read_text(encoding="utf-8"), "modify must reach disk before another call"
        for child in ("Zulu", "Альфа", "Alpha"):
            _ok(invoke, "create_metadata", {"projectName": p, "fqn": fqn + ".Attribute." + child})
            assert child in file.read_text(encoding="utf-8")
        _ok(invoke, "sort_metadata", {"projectName": p, "fqn": fqn + ".Attributes",
            "mode": "explicit", "items": ["Zulu", "Альфа", "Alpha"]})
        assert [node.findtext("name") for node in ET.fromstring(file.read_bytes()) if node.tag == "attributes"] == ["Zulu", "Альфа", "Alpha"]
        sorted_result = _ok(invoke, "sort_metadata", dict(args, mode="alphabetical"))
        children = [node.findtext("name") for node in ET.fromstring(file.read_bytes()) if node.tag == "attributes"]
        assert children == ["Alpha", "Zulu", "Альфа"], "alphabetical sorting must persist the changed collection"
        assert children == next(row["order"] for row in sorted_result.structured["collections"] if row["fqn"].endswith(".attributes"))
        clone_name = name + "Copy"
        clone_args = dict(args, newName=clone_name)
        preview = _ok(invoke, "clone_metadata_object", clone_args)
        _ok(invoke, "clone_metadata_object", dict(clone_args, confirm=True,
            expectedHash=preview.structured["contentHash"]))
        clone_file = src / "Catalogs" / clone_name / (clone_name + ".mdo")
        assert clone_file.is_file() and _registered(project, "Catalog." + clone_name), "clone must export both files"
        renamed = name + "Renamed"
        rename_args = {"projectName": p, "objectFqn": token + "." + clone_name, "newName": renamed}
        _ok(invoke, "rename_metadata_object", rename_args)
        _ok(invoke, "rename_metadata_object", dict(rename_args, confirm=True))
        renamed_file = src / "Catalogs" / renamed / (renamed + ".mdo")
        assert renamed_file.is_file() and not clone_file.exists(), "rename must export the renamed object"
        assert _registered(project, "Catalog." + renamed) and not _registered(project, "Catalog." + clone_name), (
            "rename returned before Configuration.mdo was updated")
        for victim in (name, renamed):
            delete = {"projectName": p, "fqn": token + "." + victim}
            preview = _ok(invoke, "delete_metadata", delete)
            _ok(invoke, "delete_metadata", dict(delete, confirm=True,
                expectedHash=preview.structured.get("contentHash")))
            assert not (src / "Catalogs" / victim).exists(), "delete must remove the directory"
            assert not _registered(project, "Catalog." + victim), "delete must remove the root registration"
    _ok(invoke, "resync_to_disk", {"projectName": p})
    assert not _registered(project, "Catalog.HeadlessCatalog")
    assert autosort == {path: path.read_bytes() for path in (project / ".settings").glob("*autosort*")}


def run_adoption(invoke, project, extension):
    for name, token in (("HeadlessAdoption", "Catalog"), ("ЗаимствованиеБезОкна", "Справочник")):
        args = {"projectName": "TestConfiguration", "fqn": token + "." + name}
        _ok(invoke, "create_metadata", args)
        adopted = _ok(invoke, "adopt_metadata_object", dict(args, extensionProjectName="TestConfiguration.tests"))
        assert adopted.structured["action"] == "adopted" and adopted.structured["objectBelonging"] == "ADOPTED"
        file = Path(extension) / "src/Catalogs" / name / (name + ".mdo")
        assert file.is_file() and _registered(extension, "Catalog." + name), "adoption must export object and extension root"
        saved = file.read_bytes()
        repeated = _ok(invoke, "adopt_metadata_object", dict(args, extensionProjectName="TestConfiguration.tests"))
        assert repeated.structured["action"] == "alreadyAdopted" and repeated.structured["writtenProjects"] == []
        assert file.read_bytes() == saved and _registered(extension, "Catalog." + name)


def run_auxiliary_writes(invoke, project, workspace, temp_directory=None):
    project, workspace = Path(project), Path(workspace)
    for name in ("HeadlessProfile", "ПрофильБезОкна"):
        _ok(invoke, "project_checks", {"projectName": "TestConfiguration", "action": "createProfile", "profile": name})
        assert (project / ".settings" / (name + ".cset")).is_file()
        _ok(invoke, "project_checks", {"projectName": "TestConfiguration", "action": "switch", "profile": name})
        prefs = project / ".settings/com.e1c.g5.v8.dt.check.prefs"
        _wait_for_active_profile(prefs, name)
        assert prefs.is_file(), "switch must persist its active-profile preference"
    with tempfile.TemporaryDirectory(dir=temp_directory) as directory:
        file = Path(directory) / "headless-rules.xml"
        decisions = [{"path": ["catalogs", "Catalog:Catalog:Catalog"], "rule": "GetFromOther"}]
        _ok(invoke, "merge_rules", {"mode": "write", "filePath": str(file), "decisions": decisions})
        assert "GetFromOther" in file.read_text(encoding="utf-8")
        _ok(invoke, "merge_rules", {"mode": "write", "filePath": str(file), "basedOn": str(file), "decisions": decisions})
        assert "GetFromOther" in file.read_text(encoding="utf-8"), "gated same-path write must persist"
    for args in (("init",), ("add", "."), ("-c", "user.name=HeadlessFixture", "-c",
            "user.email=headless@example.invalid", "commit", "-m", "Disposable fixture")):
        subprocess.run(["git", "-C", str(project), *args], check=True, capture_output=True)
    for name in ("HeadlessBranch", "ВеткаБезОкна"):
        _ok(invoke, "git", {"projectName": "TestConfiguration", "command": "branch " + name})
        subprocess.run(["git", "-C", str(project), "show-ref", "--verify", "refs/heads/" + name],
                       check=True, capture_output=True)
    for name in ("HeadlessDeleteProject", "УдаляемыйПроект"):
        _ok(invoke, "create_project", {"projectKind": "configuration", "name": name, "version": "8.5.1"})
        assert (workspace / name).is_dir()
        _ok(invoke, "delete_project", {"projectName": name})
        _ok(invoke, "delete_project", {"projectName": name, "confirm": True, "deleteContent": True})
        assert not (workspace / name).exists(), "confirmed project deletion must remove the directory"


def _wait_for_active_profile(prefs, name, timeout=90):
    def matches(text):
        entries = dict(line.split("=", 1) for line in text.splitlines()
                       if "=" in line and not line.startswith("#"))
        value = entries.get("activeProfile", "")
        return re.sub(r"\\u([0-9a-fA-F]{4})", lambda match: chr(int(match[1], 16)), value) == name

    text = poll_disk_contains_all(str(prefs), [], timeout=timeout,
                                 ctx="activeProfile must persist as " + name, check=matches)
    assert matches(text), "switch must persist the exact active profile"


def run_registration_writes(invoke, workspace, launcher_file):
    """Register private offline cluster references; no licensed runtime is needed."""
    launcher_file = Path(launcher_file)
    for name in ("HeadlessRegistration", "РегистрацияБезОкна"):
        name += "_" + Path(workspace).name
        created = _ok(invoke, "create_infobase", {"projectName": "TestConfiguration", "mode": "register",
            "infobaseServer": "127.0.0.1:65534", "infobaseRef": name, "infobaseName": name})
        registration_id = created.structured["registrationId"]
        args = {"projectName": "TestConfiguration", "applicationId": created.structured["applicationId"],
                "deleteRegistration": True, "deleteDatabaseFiles": False}
        cleanup = {"projectName": "TestConfiguration", "infobaseName": name,
                   "registrationId": registration_id, "deleteRegistration": True, "deleteDatabaseFiles": False}
        deleted = False
        try:
            assert registration_id.encode() in launcher_file.read_bytes(), "registration must reach the launcher file"
            _ok(invoke, "delete_infobase", args)
            _ok(invoke, "delete_infobase", dict(args, confirm=True))
            deleted = True
            assert registration_id.encode() not in launcher_file.read_bytes(), "deregistration must reach disk"
        finally:
            if not deleted:
                _ok(invoke, "delete_infobase", dict(cleanup, confirm=True))


def seed_and_read_modules(invoke, project):
    project = Path(project)
    p = "TestConfiguration"
    for name, token in (("HeadlessModule", "CommonModule"), ("МодульБезОкна", "ОбщийМодуль")):
        _ok(invoke, "create_metadata", {"projectName": p, "fqn": token + "." + name})
        module = "CommonModules/" + name + "/Module.bsl"
        file = project / "src" / module
        previous = file.read_text(encoding="utf-8-sig") if file.exists() else ""
        source = ("Procedure Send(Data) Export\nEndProcedure\n\nProcedure Build() Export\n"
                  "    Data = New Structure;\n    Data.Insert(\"ProofKey\", 1);\n    " + name + ".Send(Data);\nEndProcedure\n")
        _ok(invoke, "write_module_source", {"projectName": p, "modulePath": module,
            "source": source, "mode": "replace", "expectedSource": previous, "overwrite": True})
        assert "ProofKey" in file.read_text(encoding="utf-8")
        for tool, args, expected in (
            ("read_method_source", {"methodName": "Build"}, "ProofKey"),
            ("get_module_structure", {}, "Build"),
            ("get_symbol_info", {"line": 1, "column": 2}, "Send"),
            ("go_to_definition", {"symbol": name + ".Send"}, "Send"),
            ("get_method_call_hierarchy", {"methodName": "Build", "direction": "outgoing"}, "Send"),
        ):
            result = _ok(invoke, tool, dict(projectName=p, modulePath=module, **args))
            assert_contains(result.text, expected, tool + " must inspect saved BSL")
        refs = _ok(invoke, "find_references", {"projectName": p, "objectFqn": token + "." + name})
        assert_contains(refs.text, name, "references must resolve the stored Name")
        outgoing = _ok(invoke, "get_outgoing_structures", {"projectName": p, "modulePath": module})
        assert any("ProofKey" in row["keys"] for row in outgoing.structured["structures"]), outgoing.structured


def run_designer_writes(invoke, project):
    project = Path(project)
    p = "TestConfiguration"
    for name, token in (("HeadlessReport", "Report"), ("ОтчетБезОкна", "Отчет")):
        fqn = token + "." + name
        _ok(invoke, "create_metadata", {"projectName": p, "fqn": fqn})
        _ok(invoke, "dcs", {"projectName": p, "fqn": fqn, "action": "upsert", "type": "dataSet",
            "body": {"name": "ProofQuery", "query": "SELECT 1 AS Amount WHERE 1 = &Minimum", "autoFillFields": True}})
        paths = list((project / "src/Reports" / name).rglob("Template.dcs"))
        assert len(paths) == 1 and "Amount" in paths[0].read_text(encoding="utf-8")
        assert "Minimum" in paths[0].read_text(encoding="utf-8"), "DCS auto-fill must reach disk"
        result = _ok(invoke, "dcs", {"projectName": p, "fqn": fqn, "action": "get", "type": "schema"})
        assert_contains(result.text, "ProofQuery", "DCS must read the saved data set")
    for name, token in (("HeadlessFlow", "BusinessProcess"), ("ПроцессБезОкна", "БизнесПроцесс")):
        fqn = token + "." + name
        _ok(invoke, "create_metadata", {"projectName": p, "fqn": fqn})
        result = _ok(invoke, "business_process_route_map", {"projectName": p, "fqn": fqn, "action": "replace",
            "points": [{"name": "Begin", "type": "Start"}, {"name": "Done", "type": "Completion"}],
            "connections": [{"from": "Begin", "to": "Done", "name": "ProofConnection"}]})
        path = result.structured["file"].replace("\\", "/")
        prefix = "/" + p + "/"
        if path.startswith(prefix): path = path[len(prefix):]
        disk = project / path
        assert disk.is_file() and "ProofConnection" in disk.read_text(encoding="utf-8")
        read = _ok(invoke, "business_process_route_map", {"projectName": p, "fqn": fqn, "action": "get"})
        assert "ProofConnection" in json.dumps(read.structured), read.structured


def run_additional_reads(invoke):
    p = "TestConfiguration"
    properties = _ok(invoke, "get_configuration_properties", {"projectName": p})
    assert_contains(properties.text, "name: TestConfiguration", "configuration properties must come from the project")
    for query in ("SELECT Ref FROM Catalog.Catalog", "ВЫБРАТЬ Ссылка ИЗ Справочник.Catalog"):
        result = _ok(invoke, "validate_query", {"projectName": p, "queryText": query})
        assert result.structured["valid"] is True and result.structured["errorCount"] == 0, result.structured
        assert result.structured["sources"], "query must resolve a real catalog"
    for name, token in (("HeadlessSubsystem", "Subsystem"), ("ПодсистемаБезОкна", "Подсистема")):
        _ok(invoke, "create_metadata", {"projectName": p, "fqn": token + "." + name})
        result = _ok(invoke, "list_subsystems", {"projectName": p})
        assert_contains(result.text, name, "subsystem Name must resolve")
        result = _ok(invoke, "get_subsystem_content", {"projectName": p, "subsystemFqn": token + "." + name})
        assert_contains(result.text, name, "subsystem content must identify the resolved object")
    for name in ("Array", "Массив"):
        result = _ok(invoke, "get_platform_documentation", {"projectName": p, "typeName": name})
        assert_contains(result.text, "Add", "documentation must include native type members")
