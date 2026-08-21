"""End-to-end contract tests for the ``dcs`` schema read/write path (#404).

The fixture does not carry four convenient DCS roots, so happy-path tests seed their own
metadata with the existing authoring tools and then pin the project diff before every read.
The post-read equality checks are load-bearing: ``BasicTemplate.getTemplate()`` materializes
resources lazily, and a read must still roll that model side effect back.
"""

import os
import re

from harness import (
    PROJECT,
    PROJECT_DIR,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_ok,
    call,
    diff,
    e2e_test,
    poll_diff_contains,
    poll_disk_contains,
    read_disk,
    wait_for_project_ready,
)


MAIN_DCS_TEMPLATE = (
    "\u041e\u0441\u043d\u043e\u0432\u043d\u0430\u044f"
    "\u0421\u0445\u0435\u043c\u0430\u041a\u043e\u043c\u043f\u043e\u043d\u043e\u0432\u043a\u0438"
    "\u0414\u0430\u043d\u043d\u044b\u0445"
)


def _seed_report(name, data_set_names=("DataSet1",)):
    fqn = "Report." + name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}),
              "seed report " + fqn)
    wait_for_project_ready()
    data_sets = []
    for index, data_set_name in enumerate(data_set_names):
        data_sets.append({
            "name": data_set_name,
            "type": "query",
            "query": "SELECT %d AS Amount" % (index + 1),
            "autoFillFields": False,
            "fields": [{"dataPath": "Amount%d" % (index + 1)}],
        })
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": fqn,
        "dcs": {"dataSets": data_sets},
    }), "author report DCS " + fqn)
    wait_for_project_ready()
    return fqn


def _seed_dynamic_list(suffix):
    catalog = "Catalog.E2EDcsList" + suffix
    form = catalog + ".Form.ListForm"
    attribute = form + ".Attribute.List"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": catalog}),
              "seed dynamic-list catalog")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": form}),
              "seed dynamic-list form")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attribute}),
              "seed dynamic-list attribute")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": attribute,
        "properties": [
            {"name": "queryText",
             "value": "SELECT Ref, Description AS Description FROM " + catalog},
            {"name": "customQuery", "value": True},
            {"name": "mainTable", "value": catalog},
        ],
    }), "convert the form attribute to a dynamic list")
    wait_for_project_ready()
    return attribute


def _get(fqn, target_type, **extra):
    args = {
        "projectName": PROJECT,
        "fqn": fqn,
        "action": "get",
        "type": target_type,
    }
    args.update(extra)
    return call("dcs", args)


def _write(fqn, action, target_type, body, **extra):
    args = {
        "projectName": PROJECT,
        "fqn": fqn,
        "action": action,
        "type": target_type,
        "body": body,
    }
    args.update(extra)
    return call("dcs", args)


def _find_report_dcs(report_name):
    templates = os.path.join(PROJECT_DIR, "src", "Reports", report_name, "Templates")
    if not os.path.isdir(templates):
        return None
    for root, _dirs, files in os.walk(templates):
        for filename in files:
            if filename.lower() == "template.dcs":
                return os.path.relpath(os.path.join(root, filename), PROJECT_DIR).replace(os.sep, "/")
    return None


def _assert_read_did_not_change(before, ctx):
    assert diff() == before, "%s must not change the seeded project" % ctx


def _hash(result):
    match = re.search(r"\*\*Hash:\*\* `([0-9a-f]{20})`", result.text)
    assert match, "dcs result must carry a 20-character hash:\n%s" % result.text
    return match.group(1)


def _projection_without_hash(result):
    parts = result.text.split("\n\n", 1)
    return parts[1] if len(parts) == 2 else result.text


@e2e_test(tool="dcs", kind="write-metadata")
def test_report_summary_collection_pagination_and_pointer_drill_down():
    root = _seed_report("E2EDcsReadReport", ("First", "Second", "Third"))
    before = diff()

    summary = _get(root, "schema")
    assert_ok(summary, "read report DCS summary")
    assert "**Hash:** `" in summary.text
    assert root + "#/dataSets" in summary.text
    assert "| Data sets | 3 |" in summary.text
    assert "SELECT 1 AS Amount" not in summary.text, "summary must not expose full query text"

    page = _get(root, "dataSet", limit=1, offset=1)
    assert_ok(page, "page report data sets")
    assert "showing 1 of 3" in page.text
    assert "**Next offset:** 2" in page.text
    assert root + "#/dataSets/Second" in page.text
    assert root + "#/dataSets/First" not in page.text

    drill = _get(root + "#/dataSets/Second", "dataSet")
    assert_ok(drill, "drill into one query data set")
    assert "```sql\nSELECT 2 AS Amount\n```" in drill.text
    assert root + "#/dataSets/Second/fields/Amount2" in drill.text
    _assert_read_did_not_change(before, "report summary/pagination/drill-down")


@e2e_test(tool="dcs", kind="write-metadata")
def test_common_template_root():
    root = "CommonTemplate.E2EDcsCommon"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed common template")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": root,
        "properties": [{"name": "templateType", "value": "DataCompositionSchema"}],
    }), "declare the common template as a DCS")
    wait_for_project_ready()
    before = diff()

    result = _get(root, "schema")
    assert_ok(result, "read a common DCS template")
    assert root + "#/dataSets" in result.text
    assert "**Hash:** `" in result.text
    _assert_read_did_not_change(before, "common-template read")


@e2e_test(tool="dcs", kind="write-metadata")
def test_owned_template_root():
    report = _seed_report("E2EDcsOwnedTemplate")
    root = report + ".Template." + MAIN_DCS_TEMPLATE
    before = diff()

    result = _get(root, "schema")
    assert_ok(result, "read an object-owned DCS template")
    assert root + "#/dataSets" in result.text
    assert root + "#/dataSets/DataSet1" in result.text
    _assert_read_did_not_change(before, "owned-template read")


@e2e_test(tool="dcs", kind="write-metadata")
def test_dynamic_list_summary_and_shared_settings_drill_down():
    root = _seed_dynamic_list("Read")
    before = diff()

    summary = _get(root, "dynamicList")
    assert_ok(summary, "read dynamic-list summary")
    assert root + "#/fields" in summary.text
    assert root + "#/listSettings" in summary.text
    assert "SELECT Ref, Description" not in summary.text, "summary must not expose full query text"

    settings = _get(root + "#/listSettings", "userSettings")
    assert_ok(settings, "drill into dynamic-list settings")
    assert root + "#/listSettings" in settings.text
    _assert_read_did_not_change(before, "dynamic-list summary/settings read")


@e2e_test(tool="dcs", kind="write-metadata")
def test_bad_pointer_names_failed_segment_and_existing_keys():
    root = _seed_report("E2EDcsBadPointer")
    before = diff()

    result = _get(root + "#/dataSets/MissingDataSet", "dataSet")
    error = assert_error(result, "unresolvable DCS pointer")
    assert_error_quality(error, names=["MissingDataSet"],
                         suggests=["Existing keys/indices", "DataSet1"],
                         ctx="bad pointers enumerate valid choices at the failed level")
    _assert_read_did_not_change(before, "failed pointer read")


@e2e_test(tool="dcs", kind="write-metadata")
def test_russian_name_is_preserved_in_canonical_addresses():
    name = "\u041e\u0442\u0447\u0435\u0442\u041f\u0440\u043e\u0434\u0430\u0436\u0438"
    root = _seed_report(name)
    before = diff()

    result = _get(root, "schema")
    assert_ok(result, "read report with a Russian Name")
    assert root + "#/dataSets/DataSet1" in result.text
    _assert_read_did_not_change(before, "Russian-Name read")


@e2e_test(tool="dcs", kind="write-metadata")
def test_schema_write_upserts_dataset_without_duplicate_and_persists_to_disk():
    report_name = "E2EDcsWriteDataset"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for dcs write")
    wait_for_project_ready()

    first_query = "SELECT 101 AS E2EDcsWriteFirst"
    second_query = "SELECT 202 AS E2EDcsWriteSecond"
    created = _write(root, "upsert", "dataSet", {
        "name": "Sales",
        "type": "query",
        "query": first_query,
        "autoFillFields": False,
        "fields": [{"dataPath": "Amount"}],
    })
    assert_ok(created, "author a query dataset through dcs")
    poll_diff_contains("E2EDcsWriteFirst",
                       ctx="the dcs write must force-export the first query to disk")

    updated = _write(root + "#/dataSets/Sales", "upsert", "dataSet", {
        "query": second_query,
    })
    assert_ok(updated, "re-author the same natural key")
    poll_diff_contains("E2EDcsWriteSecond",
                       ctx="the re-authored query must force-export to disk")

    drill = _get(root + "#/dataSets/Sales", "dataSet")
    assert_ok(drill, "read back the upserted dataset")
    assert second_query in drill.text
    assert first_query not in drill.text
    assert drill.text.count(root + "#/dataSets/Sales`") == 1, \
        "the same natural key must be updated, never duplicated"

    dcs_rel = _find_report_dcs(report_name)
    assert dcs_rel is not None, "the first dcs write must materialize the report's Template.dcs"
    on_disk = read_disk(dcs_rel)
    assert "E2EDcsWriteSecond" in on_disk, "the committed query must persist in %s" % dcs_rel
    assert "E2EDcsWriteFirst" not in on_disk, "the old query must be replaced in %s" % dcs_rel
    assert on_disk.count("<name>Sales</name>") == 1, \
        "the report's .dcs must contain one Sales dataset, not duplicate natural keys"


@e2e_test(tool="dcs", kind="write-metadata")
def test_total_field_and_validation_failure_is_atomic():
    report_name = "E2EDcsWriteTotal"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for total-field write")
    wait_for_project_ready()

    total = _write(root, "upsert", "totalField", {
        "dataPath": "Amount",
        "expression": "Sum(E2EDcsTotalAmount)",
        "groups": ["Goods"],
    })
    assert_ok(total, "author a totalField")
    poll_diff_contains("E2EDcsTotalAmount",
                       ctx="the authored totalField must force-export to disk")
    before = _get(root, "schema")
    assert_ok(before, "capture the schema hash before a rejected write")

    rejected = _write(root, "upsert", "schema", {
        "dataSets": [{
            "name": "MustNotLand",
            "type": "query",
            "query": "SELECT 999 AS MustNotLand",
        }],
        "totalFields": [{
            "dataPath": "Broken",
            "expression": "Sum(Broken)",
            "titel": "bad unknown member",
        }],
    })
    error = assert_error(rejected, "unknown nested body member")
    assert_error_quality(error, names=["titel"], suggests=["Accepted members", "dataPath"],
                         ctx="unknown body members name the bad key and accepted members")

    after = _get(root, "schema")
    assert_ok(after, "read schema after rejected write")
    assert after.text == before.text, "validation failure must leave the model and hash untouched"
    assert "MustNotLand" not in diff(), "an earlier valid section must not partially reach disk"

    total_read = _get(root + "#/totalFields/Amount", "totalField")
    assert_ok(total_read, "read back the totalField")
    assert "Sum(E2EDcsTotalAmount)" in total_read.text


@e2e_test(tool="dcs", kind="write-metadata")
def test_variant_nested_settings_and_hash_guarded_filter_index_update():
    root = _seed_report("E2EDcsSettings")
    authored = _write(root, "upsert", "variant", {
        "name": "ManagerView",
        "presentation": {"EN": "Manager view"},
        "settings": {
            "items": [{
                "name": "CustomerGroup",
                "groupFields": {"items": [{
                    "field": {"kind": "field", "value": "Customer"},
                    "groupType": "Items",
                    "use": True,
                }]},
                "items": [{
                    "name": "PeriodGroup",
                    "groupFields": {"items": [{
                        "field": {"kind": "field", "value": "Period"},
                        "groupType": "Items",
                    }]},
                }],
            }],
            "selection": {
                "viewMode": "Normal",
                "userSettingID": "selection",
                "items": [{
                    "field": {"kind": "field", "value": "Customer"},
                    "use": True,
                }],
            },
            "filter": {
                "viewMode": "Normal",
                "userSettingID": "filter",
                "items": [{
                    "kind": "group",
                    "groupType": "AndGroup",
                    "items": [{
                        "left": {"kind": "field", "value": "Quantity"},
                        "comparisonType": "Greater",
                        "right": [{"kind": "number", "value": 10}],
                        "use": True,
                    }, {
                        "kind": "group",
                        "groupType": "OrGroup",
                        "items": [{
                            "left": {"kind": "field", "value": "Amount"},
                            "comparisonType": "Equal",
                            "right": [{"kind": "number", "value": 20}],
                            "use": True,
                        }],
                    }],
                }],
            },
            "order": {
                "viewMode": "Normal",
                "userSettingID": "order",
                "items": [{
                    "field": {"kind": "field", "value": "Customer"},
                    "orderType": "Asc",
                    "use": True,
                }],
            },
        },
    }, language="en")
    assert_ok(authored, "author a complete settings variant")

    variant = _get(root + "#/variants/ManagerView/settings", "userSettings")
    assert_ok(variant, "read back the settings addresses")
    first_address = root + "#/variants/ManagerView/settings/filter/items/0/items/0"
    changed_address = root + "#/variants/ManagerView/settings/filter/items/0/items/1/items/0"
    assert root + "#/variants/ManagerView/settings/items/0" in variant.text
    assert root + "#/variants/ManagerView/settings/items/0/items/0" in variant.text
    assert first_address in variant.text
    assert changed_address in variant.text

    first_before = _get(first_address, "filter")
    changed_before = _get(changed_address, "filter")
    assert_ok(first_before, "read the sibling condition before the indexed update")
    assert_ok(changed_before, "read the target condition before the indexed update")
    current_hash = _hash(variant)

    updated = _write(changed_address, "update", "filter", {
        "kind": "item",
        "right": [{"kind": "number", "value": 99}],
    }, expectedHash=current_hash)
    assert_ok(updated, "update exactly one nested filter item with its root hash")

    first_after = _get(first_address, "filter")
    changed_after = _get(changed_address, "filter")
    assert_ok(first_after, "read the untouched sibling after the indexed update")
    assert_ok(changed_after, "read the changed condition after the indexed update")
    assert _projection_without_hash(first_after) == _projection_without_hash(first_before), \
        "the hash-guarded update must not change a sibling filter item"
    changed_projection = _projection_without_hash(changed_after)
    assert "99" in changed_projection, "the selected filter item must carry its new value"
    assert "20" not in changed_projection, \
        "the selected filter item must not retain its old value"


@e2e_test(tool="dcs", kind="write-metadata")
def test_dynamic_list_write_persists_form_and_external_list_settings_files():
    catalog_name = "E2EDcsListWrite"
    catalog = "Catalog." + catalog_name
    form = catalog + ".Form.ListForm"
    root = form + ".Attribute.List"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": catalog}),
              "seed dynamic-list catalog")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": form}),
              "seed dynamic-list form")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed plain form attribute for guarded conversion")
    wait_for_project_ready()

    query_marker = "E2EDcsDynamicDescription"
    configured = _write(root, "upsert", "dynamicList", {
        "queryText": "SELECT Ref, Description AS %s FROM %s" % (query_marker, catalog),
        "customQuery": True,
        "mainTable": catalog,
        "dynamicDataRead": True,
        "autoFillAvailableFields": False,
        "autoSaveUserSettings": True,
        "getInvisibleFieldPresentations": True,
        "keyType": "RowKey",
        "keyField": ["Ref"],
        "fields": [
            {"dataPath": "Ref"},
            {"dataPath": query_marker, "field": query_marker},
        ],
        "calculatedFields": [{
            "dataPath": "DisplayText",
            "expression": query_marker,
        }],
        "parameters": [{"name": "OnlyActive", "use": "Always"}],
        "listSettings": {
            "selection": {
                "items": [], "viewMode": "Normal", "userSettingID": "selection",
                "userSettingPresentation": {"EN": "Selection"},
            },
            "filter": {
                "items": [], "viewMode": "Normal", "userSettingID": "filter",
            },
            "order": {
                "items": [], "viewMode": "Normal", "userSettingID": "order",
            },
            "conditionalAppearance": {
                "items": [], "viewMode": "Normal", "userSettingID": "appearance",
            },
        },
    }, language="en")
    assert_ok(configured, "configure a dynamic list and its shared settings through dcs")
    assert "**Form.form export scheduled:** `true`" in configured.text
    assert "**ListSettings.dcss export scheduled:** `true`" in configured.text

    form_rel = "src/Catalogs/%s/Forms/ListForm/Form.form" % catalog_name
    settings_rel = (
        "src/Catalogs/%s/Forms/ListForm/Attributes/List/ExtInfo/ListSettings.dcss"
        % catalog_name
    )
    poll_disk_contains(form_rel, query_marker,
                       ctx="dynamic-list ext-info and fields must reach Form.form")
    poll_disk_contains(settings_rel, "selection",
                       ctx="shared list settings must reach the external ListSettings.dcss")
    form_disk = read_disk(form_rel)
    settings_disk = read_disk(settings_rel)
    assert query_marker in form_disk, "the custom query must persist in Form.form"
    assert "<dataPath>Ref</dataPath>" in form_disk, "dynamic-list fields must persist in Form.form"
    assert "selection" in settings_disk and "filter" in settings_disk and "order" in settings_disk \
        and "appearance" in settings_disk, \
        "the empty holder scaffolding must persist in ListSettings.dcss"

    read_back = _get(root, "dynamicList")
    assert_ok(read_back, "read back the authored dynamic list")
    assert root + "#/fields/Ref" in read_back.text
    settings = _get(root + "#/listSettings", "userSettings")
    assert_ok(settings, "read back external dynamic-list settings")
    assert root + "#/listSettings/selection" in settings.text
    assert root + "#/listSettings/filter" in settings.text
    assert root + "#/listSettings/order" in settings.text
    assert root + "#/listSettings/conditionalAppearance" in settings.text


@e2e_test(tool="dcs", kind="read")
def test_unimplemented_mutation_action_is_a_clean_non_mutating_error():
    result = call("dcs", {
        "projectName": PROJECT,
        "fqn": "Report.DoesNotNeedToExist",
        "action": "replace",
        "type": "dataSet",
        "body": {"name": "DataSet1"},
        "expectedHash": "00000000000000000000",
    })
    error = assert_error(result, "unimplemented replace action")
    assert_error_quality(error, names=["replace"], suggests=["get", "upsert"],
                         ctx="unimplemented actions name the working alternatives")
    assert_no_diff("a rejected unimplemented action must not change the project")
