"""Standard command exclusion sets on a test-owned ValueTable form table."""

import json
import xml.etree.ElementTree as ET
from contextlib import contextmanager

from harness import (
    PROJECT,
    assert_contains,
    assert_disk_path_gone,
    assert_error,
    assert_error_quality,
    assert_ok,
    assert_tree_unchanged,
    call,
    e2e_test,
    read_disk,
    split_markdown_row,
    tree_snapshot,
    wait_for_project_ready,
)


@contextmanager
def _table(suffix):
    wait_for_project_ready()
    name = "E2EExcludedCommands" + suffix
    form = "Catalog.Catalog.Form." + name
    table = form + ".Table.RowsTable"
    path = "src/Catalogs/Catalog/Forms/%s/Form.form" % name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": form}), "create owned form")
    try:
        wait_for_project_ready()
        attribute = form + ".Attribute.Rows"
        # Form attributes accept no type at creation; set valueType afterwards.
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attribute}), "create attribute")
        wait_for_project_ready()
        assert_ok(call("modify_metadata", {
            "projectName": PROJECT, "fqn": attribute,
            "properties": [{"name": "valueType", "value": {"types": [{"kind": "ValueTable"}]}}],
        }), "type the attribute as ValueTable")
        wait_for_project_ready()
        # Table creation accepts dataPath; excludedCommands is set by modify_metadata.
        assert_ok(call("create_metadata", {
            "projectName": PROJECT, "fqn": table,
            "properties": [{"name": "dataPath", "value": "Rows"}],
        }), "create and bind the table")
        wait_for_project_ready()
        assert_contains(read_disk(path), "<types>ValueTable</types>", "typed attribute is on disk")
        _assert_disk(path, [])
        yield table, path
    finally:
        result = call("delete_metadata", {"projectName": PROJECT, "fqn": form, "confirm": True})
        assert_disk_path_gone(path, "cleanup removes the test-owned form and all its children")
        remaining = call("get_metadata_details", {"projectName": PROJECT,
                         "objectFqns": ["Catalog.Catalog"], "full": True})
        assert_ok(remaining, "read owner after cleanup")
        assert name not in remaining.text, "the deleted test form must also leave the owner model"
        assert_ok(result, "delete the test-owned form")


def _set(table, value):
    return call("modify_metadata", {
        "projectName": PROJECT, "fqn": table,
        "properties": [{"name": "excludedCommands", "value": value}],
    })


def _assert_disk(path, expected):
    text = read_disk(path)
    root = ET.fromstring(text)
    tables = [node for node in root.iter()
              if node.attrib.get("{http://www.w3.org/2001/XMLSchema-instance}type") == "form:Table"
              and node.findtext("name") == "RowsTable"]
    assert len(tables) == 1, "the test's exact table must exist on disk"
    actual = [node.text for node in tables[0].findall("excludedCommands")]
    assert sorted(actual) == sorted(expected), "disk exclusions: %r != %r" % (actual, expected)
    for name in expected:
        assert "<excludedCommands>%s</excludedCommands>" % name in text, \
            "EDT serializes canonical names as repeated excludedCommands elements"


def _assert_model(table, expected):
    result = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": [table], "assignable": True,
    })
    rows = [split_markdown_row(line) for line in result.text.splitlines()]
    row = next((cells for cells in rows if cells and cells[0] == "excludedCommands"), None)
    assert row is not None, "assignable listing must include excludedCommands: " + result.text
    assert row[1] == "EXCLUDED_COMMANDS", row
    assert sorted(json.loads(row[2])) == sorted(expected), "model exclusions: %r" % row
    assert "Copy" in row[3].split(", "), "allowed values must come from this table's command list"
    assert_ok(result, "read the table's current command exclusions")


def _expect(table, path, value, canonical):
    result = _set(table, value)
    _assert_disk(path, canonical)
    _assert_model(table, canonical)
    assert_ok(result, "replace excluded commands")
    assert result.structured.get("excludedCommands") == canonical, "echo canonical English names"
    assert result.structured.get("persisted") is True, "the replacement must be exported"


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_excluded_commands_subset_and_copy_only_replace_on_disk_and_in_model():
    with _table("Subset") as (table, path):
        subset = ["Add", "Change", "Copy", "Delete", "EndEdit", "MoveDown", "MoveUp"]
        _expect(table, path, subset, subset)
        _expect(table, path, ["Copy"], ["Copy"])


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_excluded_commands_russian_name_persists_and_echoes_english():
    with _table("Russian") as (table, path):
        russian_copy = "\u0421\u043a\u043e\u043f\u0438\u0440\u043e\u0432\u0430\u0442\u044c"
        _expect(table, path, [russian_copy], ["Copy"])


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_excluded_commands_unknown_name_preserves_disk_and_model():
    with _table("Unknown") as (table, path):
        _expect(table, path, ["Copy"], ["Copy"])
        before = tree_snapshot()
        result = _set(table, ["Add", "NoSuchTableCommand"])
        assert_tree_unchanged(before, "an unknown name must not change any file")
        _assert_disk(path, ["Copy"])
        _assert_model(table, ["Copy"])
        error = assert_error(result, "unknown table command")
        assert_error_quality(error, names=["NoSuchTableCommand"], suggests=["get_metadata_details"])
        assert_contains(error, "Allowed:", "unknown command refusal must list allowed names")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_excluded_commands_empty_array_clears_disk_and_model():
    with _table("Clear") as (table, path):
        _expect(table, path, ["Add", "Copy"], ["Add", "Copy"])
        _expect(table, path, [], [])


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_excluded_commands_invalid_shapes_and_duplicates_leave_disk_unchanged():
    with _table("Invalid") as (table, path):
        _expect(table, path, ["Copy"], ["Copy"])
        for value in ("Copy", [1], ["Copy", "copy"]):
            before = tree_snapshot()
            result = _set(table, value)
            assert_tree_unchanged(before, "invalid shape or duplicate must leave disk unchanged")
            _assert_disk(path, ["Copy"])
            _assert_model(table, ["Copy"])
            error = assert_error(result, "invalid exclusions %r" % (value,))
            assert_error_quality(error, names=["excludedCommands"],
                                 suggests=["once"] if value == ["Copy", "copy"] else ["JSON array"])


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_excluded_commands_batched_with_a_source_change_is_a_structured_refusal():
    with _table("Batch") as (table, path):
        _expect(table, path, ["Copy"], ["Copy"])
        before = tree_snapshot()
        result = call("modify_metadata", {
            "projectName": PROJECT, "fqn": table,
            "properties": [{"name": "excludedCommands", "value": ["Add"]},
                           {"name": "changeRowSet", "value": False}],
        })
        assert_tree_unchanged(before, "a refused batch must not change any file")
        _assert_disk(path, ["Copy"])
        _assert_model(table, ["Copy"])
        error = assert_error(result, "excludedCommands batched with changeRowSet")
        assert_error_quality(error, names=["changeRowSet", "excludedCommands"], suggests=["separate"])
