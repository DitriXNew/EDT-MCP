"""Issue #728: composer collections and the shared create/modify dataPath validation.

Each scenario owns a fresh form and its composer attribute. Disk structure and a model
read-back establish the effect; a success message alone cannot satisfy these tests.
"""

from contextlib import contextmanager
import xml.etree.ElementTree as ET

from harness import (
    PROJECT,
    assert_error,
    assert_error_quality,
    assert_ok,
    call,
    calls_aborted,
    e2e_test,
    poll_disk_path_gone,
    project_error_rows,
    read_disk,
    wait_for_project_ready,
)


def _local(element):
    return element.tag.rsplit("}", 1)[-1]


def _name(element):
    return next((child.text for child in element if _local(child) == "name"), None)


def _item(xml, name):
    matches = [item for item in ET.fromstring(xml).iter()
               if _local(item) == "items" and _name(item) == name]
    assert len(matches) == 1, "expected exactly one authored item %r, found %d" % (name, len(matches))
    return matches[0]


def _binding(item):
    data = next((child for child in item if _local(child) == "dataPath"), None)
    assert data is not None, "item %r has no dataPath" % _name(item)
    return ".".join(child.text or "" for child in data if _local(child) == "segments")


def _model_binding(fqn, name, path):
    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
    assert_ok(details, "read the form structure from the model")
    assert any(name in line and "dataPath: " + path in line for line in (details.text or "").splitlines()), \
        "the model must show %s bound to %s: %s" % (name, path, details.text)


def _create(fqn, kind, name, path, parent=None):
    properties = [{"name": "dataPath", "value": path}]
    if parent is not None:
        properties.append({"name": "parent", "value": parent})
    return call("create_metadata", {"projectName": PROJECT,
                                   "fqn": fqn + "." + kind + "." + name,
                                   "properties": properties})


def _modify(fqn, kind, name, path):
    return call("modify_metadata", {"projectName": PROJECT,
                                   "fqn": fqn + "." + kind + "." + name,
                                   "properties": [{"name": "dataPath", "value": path}]})


@contextmanager
def _composer_form(name, head="K"):
    fqn = "Catalog.Catalog.Form." + name
    disk = "src/Catalogs/Catalog/Forms/%s/Form.form" % name
    created = False
    try:
        result = call("create_metadata", {"projectName": PROJECT, "fqn": fqn,
                                          "generateContent": True})
        assert_ok(result, "create this test's form")
        created = True
        wait_for_project_ready()
        result = call("create_metadata", {
            "projectName": PROJECT, "fqn": fqn + ".Attribute." + head})
        assert_ok(result, "create this test's composer attribute")
        wait_for_project_ready()
        result = call("modify_metadata", {
            "projectName": PROJECT, "fqn": fqn + ".Attribute." + head,
            "properties": [{"name": "type", "value": {
                "types": [{"kind": "DataCompositionSettingsComposer"}]}}]})
        assert_ok(result, "type this test's attribute as a settings composer")
        xml = read_disk(disk)
        attributes = [node for node in ET.fromstring(xml) if _local(node) == "attributes" and _name(node) == head]
        assert len(attributes) == 1, "the test-owned composer attribute must be on disk"
        assert "DataCompositionSettingsComposer" in ET.tostring(attributes[0], encoding="unicode"), \
            "the composer type must be persisted"
        wait_for_project_ready()
        yield fqn, disk
    finally:
        # A timed-out write may still be executing; the harness stops such a run instead of racing it.
        if created and not calls_aborted():
            result = call("delete_metadata", {"projectName": PROJECT, "fqn": fqn, "confirm": True})
            assert_ok(result, "delete the entire test-owned form and its content")
            poll_disk_path_gone(disk, ctx="all test-owned form content must be removed")
            wait_for_project_ready()
            owner = call("get_metadata_details", {"projectName": PROJECT,
                                                   "objectFqns": ["Catalog.Catalog"]})
            assert_ok(owner, "read back the owner after cleanup")
            assert name not in (owner.text or ""), "the deleted test form must also be absent from the model"


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_composer_filter_table_and_four_columns():
    with _composer_form("E2E728Filter") as (fqn, disk):
        result = _create(fqn, "Table", "Filter", "K.Settings.Filter")
        assert_ok(result, "bind a table to the composer filter")
        xml = read_disk(disk)
        table = _item(xml, "Filter")
        assert _binding(table) == "K.Settings.Filter"
        assert not [child for child in table if _local(child) == "items"], \
            "a composer collection must start with no generated columns"
        assert "LineNumber" not in ET.tostring(table, encoding="unicode")
        assert "\u041d\u043e\u043c\u0435\u0440\u0421\u0442\u0440\u043e\u043a\u0438" not in ET.tostring(table, encoding="unicode")
        _model_binding(fqn, "Filter", "K.Settings.Filter")

        for column in ("Use", "LeftValue", "ComparisonType", "RightValue"):
            name, path = "Filter" + column, "K.Settings.Filter." + column
            result = _create(fqn, "Field", name, path, "Filter")
            assert_ok(result, "create the explicit filter column " + column)
            xml = read_disk(disk)
            table = _item(xml, "Filter")
            children = [child for child in table if _local(child) == "items" and _name(child) == name]
            assert len(children) == 1, "the column must be inside its table on disk"
            assert _binding(children[0]) == path
            _model_binding(fqn, name, path)
        wait_for_project_ready()
        assert not [row for row in project_error_rows(fqn) if row[0] == "form-data-path"], \
            "the composer filter must have no unresolved data-path markers"


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_composer_russian_and_indexed_filter_paths():
    head = "\u041a"
    with _composer_form("E2E728RussianIndexed", head) as (fqn, disk):
        russian = head + ".\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438.\u041e\u0442\u0431\u043e\u0440"
        assert_ok(_create(fqn, "Table", "Filter", russian), "the Russian collection resolves")
        right = russian + ".\u041f\u0440\u0430\u0432\u043e\u0435\u0417\u043d\u0430\u0447\u0435\u043d\u0438\u0435"
        assert_ok(_create(fqn, "Field", "RussianRight", right, "Filter"), "the Russian field resolves")
        canonical = head + ".Settings.Filter.RightValue"
        xml = read_disk(disk)
        assert _binding(_item(xml, "RussianRight")) == canonical
        _model_binding(fqn, "RussianRight", canonical)

        indexed = head + ".Settings.ConditionalAppearance[0].Filter"
        assert_ok(_create(fqn, "Table", "ConditionalFilter", indexed), "EDT accepts the indexed collection")
        assert_ok(_create(fqn, "Field", "ConditionalUse", indexed + ".Use", "ConditionalFilter"),
                  "EDT accepts a field under the indexed filter")
        xml = read_disk(disk)
        assert _binding(_item(xml, "ConditionalFilter")) == indexed
        assert _binding(_item(xml, "ConditionalUse")) == indexed + ".Use"
        conditional_xml = ET.tostring(_item(xml, "ConditionalFilter"), encoding="unicode")
        assert "LineNumber" not in conditional_xml and "\u041d\u043e\u043c\u0435\u0440\u0421\u0442\u0440\u043e\u043a\u0438" not in conditional_xml
        _model_binding(fqn, "ConditionalUse", indexed + ".Use")

        for collection in ("Order", "Selection"):
            path = head + ".Settings." + collection
            assert_ok(_create(fqn, "Table", collection, path), "bind the " + collection + " collection")
            assert_ok(_create(fqn, "Field", collection + "Field", path + ".Field", collection),
                      "bind the " + collection + " field")
            xml = read_disk(disk)
            assert _binding(_item(xml, collection + "Field")) == path + ".Field"
            _model_binding(fqn, collection + "Field", path + ".Field")
        wait_for_project_ready()
        assert not [row for row in project_error_rows(fqn) if row[0] == "form-data-path"]


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_composer_field_and_empty_table_data_path_round_trip():
    with _composer_form("E2E728Rebind") as (fqn, disk):
        assert_ok(_create(fqn, "Table", "Filter", "K.Settings.Filter"), "seed an empty table")
        before = read_disk(disk)
        repeated = call("modify_metadata", {
            "projectName": PROJECT, "fqn": fqn + ".Table.Filter",
            "properties": [{"name": "dataPath", "value": "K.Settings.Order"},
                           {"name": "DATAPATH", "value": "K.Settings.Filter"}]})
        error = assert_error(repeated, "a batch must specify its final dataPath once")
        assert read_disk(disk) == before, "repeated dataPath must not apply an intermediate binding"
        _model_binding(fqn, "Filter", "K.Settings.Filter")
        assert_error_quality(error, names=["dataPath"], suggests=["only once", "final path"])
        assert_ok(_modify(fqn, "Table", "Filter", "K.Settings.Order"), "rebind the empty table")
        xml = read_disk(disk)
        assert _binding(_item(xml, "Filter")) == "K.Settings.Order"
        assert not [child for child in _item(xml, "Filter") if _local(child) == "items"]
        _model_binding(fqn, "Filter", "K.Settings.Order")
        assert_ok(_modify(fqn, "Table", "Filter", "K.Settings.Filter"), "restore the table binding")
        assert_ok(_create(fqn, "Field", "Value", "K.Settings.Filter.LeftValue", "Filter"), "seed a column")
        for path in ("K.Settings.Filter.RightValue", "K.Settings.Filter.LeftValue"):
            assert_ok(_modify(fqn, "Field", "Value", path), "rebind the field")
            xml = read_disk(disk)
            assert _binding(_item(xml, "Value")) == path
            _model_binding(fqn, "Value", path)

        before = read_disk(disk)
        error = assert_error(_modify(fqn, "Table", "Filter", "K.Settings.Order"),
                             "a table with columns must not be half-rebound")
        assert read_disk(disk) == before, "the refused table rebind must leave disk unchanged"
        _model_binding(fqn, "Filter", "K.Settings.Filter")
        _model_binding(fqn, "Value", "K.Settings.Filter.LeftValue")
        assert_error_quality(error, names=["Filter"], suggests=["columns", "delete_metadata", "dataPath"])


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_composer_nonsense_segment_refuses_without_model_or_disk_changes():
    with _composer_form("E2E728Refusal") as (fqn, disk):
        assert_ok(_create(fqn, "Field", "Value", "K.Settings.Filter.Use"), "seed a valid field")
        wait_for_project_ready()
        before = read_disk(disk)
        for kind, path in (("Field", "K.Settings.NotAProperty728.Use"),
                           ("Table", "K.Settings.NotAProperty728")):
            error = assert_error(_create(fqn, kind, "Rejected" + kind, path), "refuse an unresolved path")
            assert read_disk(disk) == before, "a refused create must not write the form"
            details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
            assert_ok(details)
            assert "Rejected" + kind not in (details.text or ""), "a refused create must leave no model item"
            assert_error_quality(error, names=[path, "NotAProperty728"], suggests=["first unresolved segment", "EDT"])

        error = assert_error(_modify(fqn, "Field", "Value", "K.Settings.NotAProperty728.Use"),
                             "modify must share create's path validation")
        assert read_disk(disk) == before, "a refused modify must leave disk unchanged"
        _model_binding(fqn, "Value", "K.Settings.Filter.Use")
        assert_error_quality(error, names=["K.Settings.NotAProperty728.Use", "NotAProperty728"],
                             suggests=["first unresolved segment", "EDT"])
