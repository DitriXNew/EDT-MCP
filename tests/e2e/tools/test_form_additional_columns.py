"""#737 cross-tool round trips for Attribute.Object.Column.<TabularSection>.<Name>.

Run on a stand with --filter additional_column. Seed derived data through the public tools,
wait for readiness, and retry ONLY the explicit model-not-ready refusal. Unknown-table
errors are failures, not a reason to keep retrying. The runner owns fixture restoration.
"""

import time
import xml.etree.ElementTree as ET

from harness import (
    PROJECT, call, assert_ok, assert_error, assert_error_quality, assert_contains,
    assert_not_contains, read_disk, tree_snapshot, assert_tree_unchanged,
    poll_disk_contains, poll_disk_lacks, settle_or_fail, e2e_test,
)


FORM = "Catalog.Catalog.Form.ItemForm"
FILE = "src/Catalogs/Catalog/Forms/ItemForm/Form.form"
TABLE = "E2EAdditionalGoods"
REAL = "E2EProduct"


def _children(node, name):
    return [child for child in node if child.tag.rsplit("}", 1)[-1] == name]


def _text(node, name):
    children = _children(node, name)
    return (children[0].text or "").strip() if children else ""


def _named(node, collection, name):
    matches = [child for child in _children(node, collection) if _text(child, "name") == name]
    assert len(matches) == 1, (collection, name, len(matches), ET.tostring(node, encoding="unicode"))
    return matches[0]


def _address(name, table=TABLE, owner="Object"):
    return FORM + ".Attribute.%s.Column.%s.%s" % (owner, table, name)


def _entries(root=None):
    root = ET.fromstring(read_disk(FILE)) if root is None else root
    return _children(_named(root, "attributes", "Object"), "additionalColumns")


def _path(entry):
    paths = _children(entry, "tablePath")
    assert len(paths) == 1, ET.tostring(entry, encoding="unicode")
    return ".".join((s.text or "").strip() for s in _children(paths[0], "segments"))


def _entry(root=None):
    matches = [entry for entry in _entries(root) if _path(entry) == "Object." + TABLE]
    assert len(matches) == 1, ("expected one canonical additionalColumns entry", len(matches))
    return matches[0]


def _details(fqn=FORM, assignable=False):
    r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn], "assignable": assignable})
    assert_ok(r, "additional-column model read-back")
    return r.text


def _seed():
    assert_ok(call("create_metadata", {"projectName": PROJECT,
        "fqn": "Catalog.Catalog.TabularSection." + TABLE}), "seed tabular section")
    settle_or_fail("the additional-column tabular-section attribute")
    assert_ok(call("create_metadata", {"projectName": PROJECT,
        "fqn": "Catalog.Catalog.TabularSection.%s.Attribute.%s" % (TABLE, REAL)}), "seed real table property")
    settle_or_fail("additional-column resolution in EDT's derived data tree")


def _create(name="E2EExtra", table=TABLE, russian=False):
    fqn = _address(name, table)
    if russian:
        fqn = "Справочник.Catalog.Форма.ItemForm.Реквизит.Object.Колонка.%s.%s" % (table, name)
    deadline = time.monotonic() + 90
    while True:
        r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn,
            "properties": [{"name": "title", "language": "en", "value": "Additional amount"}]})
        if not r.is_error:
            assert r.structured.get("kind") == "FormAttributeColumn", r.structured
            assert r.structured.get("tablePath") == "Object." + TABLE, r.structured
            return r
        error = r.error_text()
        if "model is not ready" not in error or time.monotonic() >= deadline:
            assert_ok(r, "additional-column create must resolve a real table")
        assert "retry" in error and "No such table" not in error, error
        settle_or_fail("retrying the explicitly unready additional-column data tree")


def _column(name="E2EExtra"):
    poll_disk_contains(FILE, "<name>%s</name>" % name, ctx="additional column reaches Form.form")
    return _named(_entry(), "columns", name)


def _bind(name="E2EExtra"):
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": _address(name),
        "properties": [{"name": "type", "value": {"types": [{"kind": "Number", "precision": 10, "scale": 2}]}}]}),
        "give the bound additional column a value type")
    column = _column(name)
    assert _text(_children(column, "valueType")[0], "types") == "Number", ET.tostring(column)
    settle_or_fail("the additional-column field binding")
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": FORM + ".Table.E2EAdditionalTable",
        "properties": [{"name": "dataPath", "value": "Object." + TABLE}]}), "show the real tabular section")
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": FORM + ".Field.E2EAdditionalField",
        "properties": [{"name": "parent", "value": "E2EAdditionalTable"},
                       {"name": "dataPath", "value": "Object.%s.%s" % (TABLE, name)}]}), "bind an additional-column field")
    poll_disk_contains(FILE, "Object.%s.%s" % (TABLE, name), ctx="bound field setup reaches disk")


def _field_path(root):
    table = _named(root, "items", "E2EAdditionalTable")
    field = _named(table, "items", "E2EAdditionalField")
    return ".".join((segment.text or "").strip()
                    for path in _children(field, "dataPath") for segment in _children(path, "segments"))


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_additional_column_lands_under_the_tabular_section():
    _seed()
    _create()
    column = _column()
    assert int(_text(column, "id")) > 0
    assert len(_children(column, "valueType")) == 1
    for flag in ("view", "edit"):
        assert _text(_children(column, flag)[0], "common") == "true", (flag, ET.tostring(column))
    assert_contains(_details(), "E2EExtra", "created column is in the model")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_two_additional_columns_share_one_entry():
    _seed()
    _create("E2EFirstExtra")
    _column("E2EFirstExtra")
    _create("E2ESecondExtra")
    _column("E2ESecondExtra")
    assert len(_entries()) == 1
    assert [_text(c, "name") for c in _children(_entry(), "columns")] == ["E2EFirstExtra", "E2ESecondExtra"]
    text = _details()
    assert_contains(text, "E2EFirstExtra")
    assert_contains(text, "E2ESecondExtra")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_additional_column_canonicalizes_the_table_spelling():
    _seed()
    _create(table=TABLE.lower())
    _column()
    assert _path(_entry()) == "Object." + TABLE
    assert_contains(_details(), "Object." + TABLE)


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_additional_column_russian_tokens():
    _seed()
    _create(russian=True)
    _column()
    assert_contains(_details(), "E2EExtra")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_additional_column_refusals_leave_the_form_unchanged():
    _seed()
    _create()
    _column()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": FORM + ".Attribute.E2ERows"}), "seed collection")
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": FORM + ".Attribute.E2ERows",
        "properties": [{"name": "type", "value": {"types": [{"kind": "ValueTable"}]}}]}), "type collection")
    settle_or_fail("the collection-owner refusal probe")
    cases = [
        (_address("Extra", owner="E2ERows"), [], ["E2ERows", "ValueTable"], ["Column.<Name>"]),
        (_address("Extra", table="MissingE2ETable"), [], ["MissingE2ETable"], ["available tables"]),
        (_address("E2ENestedExtra", table=TABLE + ".E2ENested"), [], ["Object.%s.E2ENested" % TABLE],
         ["nested tables are not created", "Column.<TabularSection>.<Name>"]),
        (_address(REAL), [], [REAL], ["table property"]),
        (_address("E2EExtra"), [], ["E2EExtra"], ["already exists"]),
        (_address("E2EBadProperty"), [{"name": "dataPath", "value": "Object." + TABLE}], ["dataPath"], ["only", "title"]),
    ]
    for fqn, properties, names, suggests in cases:
        before = tree_snapshot()
        error = assert_error(call("create_metadata", {"projectName": PROJECT, "fqn": fqn, "properties": properties}), fqn)
        assert_error_quality(error, names=names, suggests=suggests)
        assert_tree_unchanged(before, "refused additional-column create")
    assert_contains(_details(), "E2EExtra")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_additional_column_type_and_title():
    _seed()
    _create()
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": _address("E2EExtra"),
        "properties": [{"name": "type", "value": {"types": [{"kind": "Number", "precision": 10, "scale": 2}]}},
                       {"name": "title", "language": "en", "value": "Updated additional amount"},
                       {"name": "view", "value": False}, {"name": "edit", "value": True}]}), "modify additional column")
    column = _column()
    value_type = _children(column, "valueType")[0]
    assert _text(value_type, "types") == "Number", ET.tostring(column)
    qualifiers = _children(value_type, "numberQualifiers")[0]
    assert _text(qualifiers, "precision") == "10" and _text(qualifiers, "scale") == "2"
    assert "Updated additional amount" in ET.tostring(column, encoding="unicode")
    view = _children(column, "view")
    assert len(view) == 1 and not _children(view[0], "common"), "common=false is serialized as <view/>"
    assert _text(_children(column, "edit")[0], "common") == "true"
    assert_contains(_details(_address("E2EExtra"), True), "Number")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_additional_column_preview_and_confirm():
    _seed()
    _create()
    _bind()
    # A same-named direct collection column lives in a different namespace and must survive.
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": FORM + ".Attribute.E2ERows"}), "seed direct owner")
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": FORM + ".Attribute.E2ERows",
        "properties": [{"name": "type", "value": {"types": [{"kind": "ValueTable"}]}}]}), "type direct owner")
    assert_ok(call("create_metadata", {"projectName": PROJECT,
        "fqn": FORM + ".Attribute.E2ERows.Column.E2EExtra"}), "seed direct column")
    settle_or_fail("the additional-column deletion collector")
    before = tree_snapshot()
    preview = call("delete_metadata", {"projectName": PROJECT, "fqn": _address("E2EExtra")})
    assert_ok(preview, "additional-column delete preview")
    assert "FormAttributeColumn" in str(preview.structured) and "E2EAdditionalField" in str(preview.structured), preview.structured
    assert_tree_unchanged(before, "delete preview")
    assert_contains(_details(_address("E2EExtra"), True), "E2EExtra")
    result = call("delete_metadata", {"projectName": PROJECT, "fqn": _address("E2EExtra"), "confirm": True})
    assert_ok(result, "delete additional column")
    assert "persisted" not in result.structured, \
        "a completed form-member delete leaves persisted out: %r" % (result.structured,)
    root = ET.fromstring(read_disk(FILE))
    assert not _entries(root), "last column must remove the additionalColumns entry"
    assert "E2EAdditionalField" not in ET.tostring(root, encoding="unicode")
    rows = _named(root, "attributes", "E2ERows")
    _named(rows, "columns", "E2EExtra")
    text = _details()
    assert_not_contains(text, "## Additional columns")
    assert_contains(text, "E2EExtra", "the direct collection namespace survives")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_additional_column_keeps_a_sibling_entry_column():
    _seed()
    _create("E2EFirstExtra")
    _create("E2ESecondExtra")
    settle_or_fail("the additional-column sibling deletion")
    result = call("delete_metadata", {"projectName": PROJECT, "fqn": _address("E2EFirstExtra"), "confirm": True})
    assert_ok(result, "delete one additional column")
    assert "persisted" not in result.structured, \
        "a completed form-member delete leaves persisted out: %r" % (result.structured,)
    assert [_text(c, "name") for c in _children(_entry(), "columns")] == ["E2ESecondExtra"]
    text = _details()
    assert_contains(text, "E2ESecondExtra")
    assert_not_contains(text, "E2EFirstExtra")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_additional_column_round_trip_updates_bound_item_data_path():
    _seed()
    _create()
    _bind()
    settle_or_fail("the additional-column rename cascade")
    before = tree_snapshot()
    args = {"projectName": PROJECT, "objectFqn": _address("E2EExtra"), "newName": "E2ERenamedExtra"}
    preview = call("rename_metadata_object", args)
    assert_ok(preview, "additional-column rename preview")
    assert_contains(preview.text, "preview")
    assert_tree_unchanged(before, "rename preview")
    assert _text(_column(), "name") == "E2EExtra"
    assert_contains(_details(_address("E2EExtra"), True), "E2EExtra")
    assert_ok(call("rename_metadata_object", dict(args, confirm=True)), "rename additional column")
    poll_disk_contains(FILE, "<name>E2ERenamedExtra</name>", ctx="renamed column reaches disk")
    poll_disk_contains(FILE, "Object.%s.E2ERenamedExtra" % TABLE, ctx="rename rewrites the bound item's dataPath")
    root = ET.fromstring(read_disk(FILE))
    _named(_entry(root), "columns", "E2ERenamedExtra")
    assert _field_path(root) == "Object.%s.E2ERenamedExtra" % TABLE
    assert not any(_text(c, "name") == "E2EExtra" for c in _children(_entry(root), "columns"))
    assert_contains(_details(_address("E2ERenamedExtra"), True), "E2ERenamedExtra")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_additional_column_refuses_sibling_and_real_property_names():
    _seed()
    _create("E2EFirstExtra")
    _create("E2ESecondExtra")
    _column("E2ESecondExtra")
    settle_or_fail("the additional-column rename clash checks")
    for name in ("E2ESecondExtra", REAL):
        before = tree_snapshot()
        error = assert_error(call("rename_metadata_object", {"projectName": PROJECT,
            "objectFqn": _address("E2EFirstExtra"), "newName": name, "confirm": True}), "rename clash")
        assert_error_quality(error, names=[name], suggests=["different name"])
        assert_tree_unchanged(before, "refused rename")
    assert_contains(_details(), "E2EFirstExtra")


@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_form_details_render_additional_columns_and_missing_table_advice():
    _seed()
    _create()
    _column()
    before = tree_snapshot()
    text = _details()
    assert_contains(text, "## Additional columns")
    assert_contains(text, "Object." + TABLE)
    assert_contains(text, "E2EExtra")
    assert_contains(_details(_address("E2EExtra"), True), "valueType")
    missing = call("get_metadata_details", {"projectName": PROJECT,
        "objectFqns": [_address("E2EExtra", table="WrongE2ETable")], "assignable": True})
    # This read tool can return a per-object Errors section rather than a whole-call isError.
    advice = missing.error_text() if missing.is_error else missing.text
    assert_contains(advice, "WrongE2ETable")
    assert_contains(advice, "Object." + TABLE)
    assert_contains(advice, "additional-column paths")
    assert_tree_unchanged(before, "details read-back")


@e2e_test(tool="get_project_errors", kind="write-metadata")
def test_additional_column_project_errors_address_is_resolved():
    _seed()
    _create()
    _column()
    before = tree_snapshot()
    r = call("get_project_errors", {"projectName": PROJECT, "objectFqns": [_address("E2EExtra")]})
    assert_ok(r, "additional-column member error scope")
    assert r.structured.get("objectsNotFound") == [], r.structured
    assert _address("E2EExtra") in (r.structured.get("objectsResolved") or []), r.structured
    assert_tree_unchanged(before, "scoped error read")
