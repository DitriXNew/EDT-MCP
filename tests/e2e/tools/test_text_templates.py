"""TextDocument template persistence and concurrency regressions for #730 / #707.1."""

import hashlib
import re
from pathlib import Path
from xml.etree import ElementTree

from harness import (
    PROJECT, PROJECT_DIR, call, e2e_test, assert_ok, assert_error,
    assert_error_quality, assert_contains, poll_disk_path_gone,
    read_disk, assert_no_diff, tree_snapshot, assert_tree_unchanged, split_markdown_row,
)


def _hash(text):
    return hashlib.sha256(text.replace("\r\n", "\n").encode("utf-8")).hexdigest()[:16]


def _details(fqn):
    result = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": [fqn],
    })
    assert_ok(result, "template model read-back")
    return result.text


def _text_and_hash(details):
    match = re.search(r"\n(`{3,})text\n(.*?)\n\1(?:\n|$)", details, re.S)
    assert match, "details must contain a fenced text section: %r" % details
    digest = re.search(r"(?m)^contentHash: [\"']?([0-9a-f]{16})[\"']?$", details)
    assert digest, "details must publish the full-text contentHash: %r" % details
    return match.group(2), digest.group(1)


def _owner(suffix):
    name = "E2EText730" + suffix
    fqn = "DataProcessor." + name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}),
              "create test-owned data processor")
    # Creation supports synonym/comment on this node; no template-only property is sent here.
    owner_file = "src/DataProcessors/%s/%s.mdo" % (name, name)
    assert "<name>%s</name>" % name in read_disk(owner_file)
    assert_contains(_details(fqn), name, "owner exists in model")
    return fqn, "src/DataProcessors/" + name


def _create(owner, root, name, template_type):
    fqn = owner + ".Template." + name
    result = call("create_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "properties": [{"name": "templateType", "value": template_type}],
    })
    assert_ok(result, "create typed template")
    path = Path(PROJECT_DIR, root, "Templates", name, "Template.txt")
    expected = "TextDocument" if template_type in ("TextDocument", "ТекстовыйДокумент") else "SpreadsheetDocument"
    _descriptor(root, name, expected)
    return fqn, path


def _descriptor(root, name, expected):
    """Pin the inline template and its type in the owner's actual exported metadata."""
    owner_name = root.rsplit("/", 1)[-1]
    xml = ElementTree.fromstring(read_disk(root + "/" + owner_name + ".mdo"))
    definitions = [item for item in xml.findall("templates") if item.findtext("name") == name]
    assert len(definitions) == 1, "owner .mdo must contain exactly the created template"
    # SpreadsheetDocument is enum value zero and EDT omits that default on disk.
    assert definitions[0].findtext("templateType", "SpreadsheetDocument") == expected


def _cleanup(owner, root):
    assert_ok(call("delete_metadata", {
        "projectName": PROJECT, "fqn": owner, "confirm": True,
    }), "remove test-owned data processor and templates")
    assert "<dataProcessors>%s</dataProcessors>" % owner not in read_disk(
        "src/Configuration/Configuration.mdo")
    poll_disk_path_gone(root, ctx="test-owned data processor resources removed")
    _absent(owner)
    assert_no_diff("all test-owned resources and configuration references cleaned")


def _absent(fqn):
    result = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": [fqn], "assignable": True,
    })
    assert_ok(result, "per-object absence uses the in-band Errors table")
    assert_contains(result.text, "## Errors", "missing FQN is reported")
    rows = [split_markdown_row(line) for line in result.text.splitlines() if line.startswith("|")]
    assert any(len(row) == 3 and row[0] == fqn and row[1] == "ERROR"
               and "not found" in row[2].lower() for row in rows), result.text


def _spreadsheet_model(fqn):
    result = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": [fqn], "assignable": True,
    })
    assert_ok(result, "specific template assignable model")
    assert "## Errors" not in result.text, result.text
    assert "## Assignable properties: " + fqn in result.text, result.text
    rows = [split_markdown_row(line) for line in result.text.splitlines() if line.startswith("|")]
    assert any(len(row) == 4 and row[0] == "templateType" and row[2] == "SpreadsheetDocument"
               for row in rows), result.text


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_text_template_roundtrip_utf8_hash_and_stale_guard():
    owner, root = _owner("Roundtrip")
    try:
        fqn, path = _create(owner, root, "Contract", "TextDocument")
        assert path.read_bytes() == b"", "creation must create an empty Template.txt"
        assert _text_and_hash(_details(fqn)) == ("", _hash(""))
        text = '{\n  "message": "Привет, мир",\n  "version": 730\n}\n'
        written = call("modify_metadata", {
            "projectName": PROJECT, "fqn": fqn,
            "template": {"text": text, "expectedHash": _hash("")},
        })
        assert path.read_bytes() == text.encode("utf-8"), "exact UTF-8 bytes, no BOM, trailing LF"
        assert _text_and_hash(_details(fqn)) == (text, _hash(text))
        assert_ok(written, "write text with matching hash")
        assert written.structured["template"]["contentHash"] == _hash(text)

        before_tree = tree_snapshot()
        stale = call("modify_metadata", {
            "projectName": PROJECT, "fqn": fqn,
            "template": {"text": "must not replace", "expectedHash": _hash("")},
        })
        assert_tree_unchanged(before_tree, "stale expectedHash must change nothing")
        assert path.read_bytes() == text.encode("utf-8"), "stale hash leaves disk unchanged"
        assert _text_and_hash(_details(fqn)) == (text, _hash(text))
        error = assert_error(stale, "lost-update guard")
        assert_error_quality(error, names=[_hash(text)], suggests=["get_metadata_details"])
    finally:
        _cleanup(owner, root)


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_text_template_russian_type_spelling_creates_txt():
    owner, root = _owner("Russian")
    try:
        fqn, path = _create(owner, root, "ContractRu", "ТекстовыйДокумент")
        assert path.read_bytes() == b""
        assert _text_and_hash(_details(fqn)) == ("", _hash(""))
    finally:
        _cleanup(owner, root)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_text_template_retype_nonempty_refused_empty_allowed():
    owner, root = _owner("Retype")
    try:
        fqn, path = _create(owner, root, "Retype", "TextDocument")
        assert path.read_bytes() == b""
        assert _text_and_hash(_details(fqn))[0] == ""
        assert_ok(call("modify_metadata", {
            "projectName": PROJECT, "fqn": fqn, "template": {"text": "keep\n"},
        }), "populate before retype")
        before = path.read_bytes()
        before_tree = tree_snapshot()
        refused = call("modify_metadata", {
            "projectName": PROJECT, "fqn": fqn,
            "properties": [{"name": "templateType", "value": "SpreadsheetDocument"}],
        })
        assert_tree_unchanged(before_tree, "non-empty text retype must change nothing")
        assert path.read_bytes() == before
        _descriptor(root, "Retype", "TextDocument")
        assert _text_and_hash(_details(fqn))[0] == "keep\n"
        assert_error_quality(assert_error(refused, "non-empty retype"),
                             names=["content"], suggests=["create_metadata"])
        assert_ok(call("modify_metadata", {
            "projectName": PROJECT, "fqn": fqn, "template": {"text": ""},
        }), "clear text explicitly")
        retyped = call("modify_metadata", {
            "projectName": PROJECT, "fqn": fqn,
            "properties": [{"name": "templateType", "value": "SpreadsheetDocument"}],
        })
        mxlx = path.with_name("Template.mxlx")
        assert mxlx.is_file(), "empty retype must create spreadsheet content"
        assert not path.exists(), "old empty text resource must be removed"
        _descriptor(root, "Retype", "SpreadsheetDocument")
        assert_ok(retyped, "retype empty text template")
        # A spreadsheet payload accepted on the same FQN proves the model type changed too.
        authored = call("modify_metadata", {
            "projectName": PROJECT, "fqn": fqn,
            "template": {"cells": [{"row": 0, "col": 0, "text": "Retyped730"}]},
        })
        assert "Retyped730" in mxlx.read_text(encoding="utf-8")
        _spreadsheet_model(fqn)
        assert_ok(authored, "author retyped spreadsheet")
    finally:
        _cleanup(owner, root)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_spreadsheet_template_empty_retype_to_text_then_cell_retype_refused():
    owner, root = _owner("Sheet")
    try:
        fqn, path = _create(owner, root, "Empty", "ТабличныйДокумент")
        mxlx = path.with_name("Template.mxlx")
        assert mxlx.is_file()
        _spreadsheet_model(fqn)
        retyped = call("modify_metadata", {
            "projectName": PROJECT, "fqn": fqn,
            "properties": [{"name": "templateType", "value": "TextDocument"}],
        })
        assert path.read_bytes() == b""
        assert not mxlx.exists(), "old empty spreadsheet resource removed"
        _descriptor(root, "Empty", "TextDocument")
        assert _text_and_hash(_details(fqn)) == ("", _hash(""))
        assert_ok(retyped, "empty spreadsheet retyped to text")

        sheet_fqn, sheet_path = _create(owner, root, "Populated", "SpreadsheetDocument")
        assert_ok(call("modify_metadata", {
            "projectName": PROJECT, "fqn": sheet_fqn,
            "template": {"cells": [{"row": 0, "col": 0, "text": "KeepCell730"}]},
        }), "populate spreadsheet before refused retype")
        sheet_file = sheet_path.with_name("Template.mxlx")
        assert "KeepCell730" in sheet_file.read_text(encoding="utf-8")
        _descriptor(root, "Populated", "SpreadsheetDocument")
        _spreadsheet_model(sheet_fqn)
        before = sheet_file.read_bytes()
        before_tree = tree_snapshot()
        refused = call("modify_metadata", {
            "projectName": PROJECT, "fqn": sheet_fqn,
            "properties": [{"name": "templateType", "value": "TextDocument"}],
        })
        assert_tree_unchanged(before_tree, "non-empty spreadsheet retype must change nothing")
        assert sheet_file.read_bytes() == before
        assert not sheet_path.exists()
        _descriptor(root, "Populated", "SpreadsheetDocument")
        _spreadsheet_model(sheet_fqn)
        assert_error_quality(assert_error(refused, "non-empty spreadsheet retype"),
                             names=["content"], suggests=["create_metadata"])
    finally:
        _cleanup(owner, root)


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_text_template_dcs_and_unknown_type_refused_without_creation():
    owner, root = _owner("Refused")
    try:
        for index, bad in enumerate(("DataCompositionSchema", "NoSuchTemplateType730", "BinaryData")):
            fqn = owner + ".Template.Refused%d" % index
            owner_file = Path(PROJECT_DIR, root, root.rsplit("/", 1)[-1] + ".mdo")
            before = owner_file.read_bytes()
            before_tree = tree_snapshot()
            refused = call("create_metadata", {
                "projectName": PROJECT, "fqn": fqn,
                "properties": [{"name": "templateType", "value": bad}],
            })
            assert_tree_unchanged(before_tree, "unsupported type must not create anything")
            assert owner_file.read_bytes() == before
            assert not Path(PROJECT_DIR, root, "Templates", "Refused%d" % index).exists()
            _absent(fqn)
            assert_error_quality(assert_error(refused, "unsupported template type"),
                                 names=[bad], suggests=["TextDocument", "SpreadsheetDocument"])
    finally:
        _cleanup(owner, root)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_text_template_payload_kind_mismatches_leave_content_unchanged():
    owner, root = _owner("Mismatch")
    try:
        fqn, path = _create(owner, root, "Text", "TextDocument")
        assert path.read_bytes() == b""
        before = path.read_bytes()
        before_tree = tree_snapshot()
        refused = call("modify_metadata", {
            "projectName": PROJECT, "fqn": fqn, "template": {"cells": []},
        })
        assert_tree_unchanged(before_tree, "cells on text must change nothing")
        assert path.read_bytes() == before
        assert _text_and_hash(_details(fqn)) == ("", _hash(""))
        assert_error_quality(assert_error(refused, "cells on text"),
                             names=["cells"], suggests=["text"])
        sheet_fqn, sheet_path = _create(owner, root, "Sheet", "SpreadsheetDocument")
        sheet_file = sheet_path.with_name("Template.mxlx")
        before_sheet = sheet_file.read_bytes()
        before_tree = tree_snapshot()
        refused = call("modify_metadata", {
            "projectName": PROJECT, "fqn": sheet_fqn, "template": {"text": "wrong"},
        })
        assert_tree_unchanged(before_tree, "text on spreadsheet must change nothing")
        assert sheet_file.read_bytes() == before_sheet
        assert not sheet_path.exists()
        _spreadsheet_model(sheet_fqn)
        assert_error_quality(assert_error(refused, "text on spreadsheet"),
                             names=["text"], suggests=["TextDocument", "cells"])
    finally:
        _cleanup(owner, root)
