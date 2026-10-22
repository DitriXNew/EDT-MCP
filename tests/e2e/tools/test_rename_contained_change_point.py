"""Rename query references held by contained BM objects (#790).

The Russian query dialect is essential: the live probe found that an English
query produces no change point on this fixture. The orchestrator restores disk
and model after these write-metadata tests through the shared reset helpers.
"""

import xml.etree.ElementTree as ET

from harness import (
    rename_call,
    PROJECT,
    assert_contains,
    assert_not_contains,
    assert_ok,
    assert_tree_unchanged,
    call,
    e2e_test,
    poll_disk_contains,
    read_disk,
    settle_or_fail,
    tree_snapshot,
)
from tools.test_rename_metadata_object import _change_points, _frontmatter_value


def _query(document_name):
    return (
        "ВЫБРАТЬ Док.Ссылка КАК Ссылка, Док.Номер КАК Номер "
        "ИЗ Документ." + document_name + " КАК Док"
    )


def _query_texts(exported):
    return [" ".join((element.text or "").split()) for element in ET.fromstring(exported).iter()
            if element.tag.rsplit("}", 1)[-1] == "queryText"]


def _assert_addressable(fqn, heading):
    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
    assert_ok(details, "the FQN column must expose an addressable metadata object")
    assert_not_contains(details.text, "## Errors", "the metadata address must resolve without refusal")
    assert_contains(details.text, heading, "details must render the owning form or schema")


def _create(fqn):
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}),
              "seed contained query owner " + fqn)
    settle_or_fail("seeding the next contained-query object")


def _preview(document_name, new_name):
    settle_or_fail("previewing a rename with a contained query reference")
    before = tree_snapshot()
    result = rename_call({
        "projectName": PROJECT,
        "objectFqn": "Document." + document_name,
        "newName": new_name,
        "confirm": False,
        "maxResults": 0,
    })
    assert_ok(result, "preview a document referenced by a contained query")
    assert_contains(result.text, "action: preview", "the call must stay in preview mode")
    # The seed is intentionally dirty, so use the harness's full snapshot guard
    # (including untracked query files) instead of comparing with the git baseline.
    assert_tree_unchanged(before, "preview must leave every seeded file unchanged")
    assert _frontmatter_value(result.text, "contentHash"), "preview must issue its hash"
    return result


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_contained_dynamic_list_preview_and_confirm_keep_the_query_change_point():
    source_name, destination_name = "E2E790ListSrc", "E2E790ListDst"
    source = "Document." + source_name
    form = source + ".Form.ListForm"
    attribute = form + ".Attribute.List"
    for fqn in (source, form, attribute):
        _create(fqn)
    query = _query(source_name)
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": attribute,
        "properties": [
            {"name": "queryText", "value": query},
            {"name": "customQuery", "value": True},
            {"name": "mainTable", "value": source},
        ],
    }), "author the Russian dynamic-list query")
    original_rel = "src/Documents/%s/Forms/ListForm/Form.form" % source_name
    exported = poll_disk_contains(original_rel, "Документ." + source_name,
                                  ctx="the triggering query must exist before preview")
    assert query in _query_texts(exported), "the Russian query must be present before preview"

    preview = _preview(source_name, destination_name)
    rows = _change_points(preview.text)
    query_rows = [row for row in rows if row["fqn"] == form and row["line"].isdigit()]
    assert query_rows, "preview must name the owning form in a located query row:\n" + preview.text
    assert all(row["skippable"] == "yes" for row in query_rows), \
        "attached contained targets must have verifiable identities"
    assert_not_contains(preview.text, form + ".Form", "content FQNs must not appear in the preview")
    _assert_addressable(form, "# Form Structure: " + source + ".forms.ListForm")
    assert _query_texts(read_disk(original_rel)).count(query) == 1, \
        "preview must preserve the original query"

    # An out-of-range index keeps every real row enabled while making the request
    # non-empty, so confirm MUST traverse expectedHashError's contained targets.
    disabled_index = 99
    result = rename_call({
        "projectName": PROJECT,
        "objectFqn": source,
        "newName": destination_name,
        "confirm": True,
        "disableIndices": str(disabled_index),
        "expectedHash": _frontmatter_value(preview.text, "contentHash"),
    })
    assert_ok(result, "confirm with the preview hash and the query change point retained")
    assert_contains(result.text, "action: executed", "the guarded rename must execute")
    assert_contains(result.text, "unknownIndices: [%d]" % disabled_index,
                    "the non-empty skip request must be processed")

    renamed_rel = "src/Documents/%s/Forms/ListForm/Form.form" % destination_name
    expected_query = _query(destination_name)
    exported = poll_disk_contains(renamed_rel, "Документ." + destination_name,
                                  ctx="EDT must rewrite the contained query on disk")
    # Inspect queryText itself and tolerate EDT's whitespace formatting.
    query_texts = _query_texts(exported)
    assert expected_query in query_texts, "the queryText element must name the new document"
    assert query not in query_texts, "the old document must leave the queryText element"

    model = call("dcs", {
        "projectName": PROJECT,
        "fqn": "Document." + destination_name + ".Form.ListForm.Attribute.List#/queryText",
        "action": "get",
        "type": "dynamicList",
    })
    assert_ok(model, "read back the renamed list query from the model")
    assert_contains(" ".join(model.text.split()), expected_query,
                    "model and disk must agree on the new query")
    assert_not_contains(" ".join(model.text.split()), query,
                        "the model must release the old document reference")
    old = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [source]})
    assert_ok(old, "check the old document address after rename")
    assert_contains(old.text.lower(), "not found", "the old document must no longer resolve")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_contained_report_dcs_query_previews_its_template_owner():
    source_name = "E2E790DcsSrc"
    report = "Report.E2E790QueryReport"
    _create("Document." + source_name)
    _create(report)
    assert_ok(call("dcs", {
        "projectName": PROJECT,
        "fqn": report,
        "action": "upsert",
        "type": "schema",
        "body": {"dataSets": [{
            "name": "Documents",
            "type": "query",
            "query": _query(source_name),
            "autoFillFields": False,
        }]},
    }), "author a report data-set query referencing another document")

    preview = _preview(source_name, "E2E790DcsDst")
    query_rows = [row for row in _change_points(preview.text)
                  if row["fqn"].startswith(report + ".Template.") and row["line"].isdigit()]
    assert query_rows, "the contained DCS query must name its owning top template:\n" + preview.text
    assert all(row["skippable"] == "yes" for row in query_rows), \
        "DCS query targets must have verifiable identities"
    # Read the template name EDT created (Russian on this fixture) from the preview.
    # Exactly four segments distinguishes the metadata address from its content top.
    parts = query_rows[0]["fqn"].split(".")
    assert len(parts) == 4 and parts[:3] == report.split(".") + ["Template"], \
        "DCS query rows must name the metadata template address: " + query_rows[0]["fqn"]
    template = report + ".Template." + parts[3]
    assert all(row["fqn"] == template for row in query_rows), \
        "all data-set changes must name the exact template EDT created"
    assert_not_contains(preview.text, template + ".Template",
                        "the content-top address must not appear in the preview")
    _assert_addressable(template, "# Data Composition Schema: " + template)
