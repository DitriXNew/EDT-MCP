"""End-to-end contract tests for the read side of the ``dcs`` tool (#404).

The fixture does not carry four convenient DCS roots, so happy-path tests seed their own
metadata with the existing authoring tools and then pin the project diff before every read.
The post-read equality checks are load-bearing: ``BasicTemplate.getTemplate()`` materializes
resources lazily, and a read must still roll that model side effect back.
"""

from harness import (
    PROJECT,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_ok,
    call,
    diff,
    e2e_test,
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


def _assert_read_did_not_change(before, ctx):
    assert diff() == before, "%s must not change the seeded project" % ctx


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


@e2e_test(tool="dcs", kind="read")
def test_reserved_mutation_action_is_a_clean_non_mutating_error():
    result = call("dcs", {
        "projectName": PROJECT,
        "fqn": "Report.DoesNotNeedToExist",
        "action": "upsert",
        "type": "dataSet",
        "body": {"name": "DataSet1"},
    })
    error = assert_error(result, "Stage 1b reserved action")
    assert_error_quality(error, names=["upsert"], suggests=["get"],
                         ctx="reserved actions name the working alternative")
    assert_no_diff("a rejected reserved action must not change the project")
