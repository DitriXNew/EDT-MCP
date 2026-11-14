"""create_metadata: a report's main data composition schema in one call (issue #919).

A Report / ExternalReport template created with setAsDefault=true is a DataCompositionSchema template
AND the report's mainDataCompositionSchema - what EDT's report DCS wizard does. Covered in a
configuration EXTENSION's own report (the reported case) and in the base configuration with Russian
type tokens; every claim is read back from the exported files, not only from the response.
"""

import os
from xml.etree import ElementTree

from harness import (
    PROJECT, PROJECT_DIR, REPO_ROOT, TESTS_PROJECT, TESTS_PROJECT_REL,
    call, e2e_test, assert_ok, assert_error, assert_error_quality, assert_contains,
    poll_disk_contains_all, read_disk, tree_snapshot, assert_tree_unchanged, wait_for_project_ready,
)

RU_REPORT = "Отчет"                                  # Report
RU_TEMPLATE = "Макет"                                # Template
RU_DCS_SPACED = ("Схема компоновки "
                 "данных")                      # the EDT label, with spaces


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _assert_main_schema_on_disk(report, template, fixture_rel=None):
    """The report .mdo names the template as its main DCS and declares it a DCS; Template.dcs exists."""
    rel = "src/Reports/%s" % report
    mdo = poll_disk_contains_all(rel + "/%s.mdo" % report, [
        "<mainDataCompositionSchema>Report.%s.Template.%s</mainDataCompositionSchema>" % (report, template),
        "<templateType>DataCompositionSchema</templateType>",
    ], ctx="report .mdo names its new main DCS", fixture_rel=fixture_rel)
    root = ElementTree.fromstring(mdo)
    declared = [t for t in root.iter() if _local(t.tag) == "templates"
                and any(_local(c.tag) == "name" and c.text == template for c in t)]
    assert len(declared) == 1, "exactly one template %r in the report .mdo: %s" % (template, mdo)
    assert any(_local(c.tag) == "templateType" and c.text == "DataCompositionSchema" for c in declared[0]), mdo
    base = os.path.join(REPO_ROOT, *fixture_rel.split("/")) if fixture_rel else PROJECT_DIR
    content = os.path.join(base, *rel.split("/"), "Templates", template, "Template.dcs")
    assert os.path.isfile(content), "the DCS content file must exist: %s" % content
    with open(content, encoding="utf-8") as f:
        assert _local(ElementTree.fromstring(f.read()).tag) == "DataCompositionSchema"
    assert not os.path.exists(os.path.join(os.path.dirname(content), "Template.mxlx")), \
        "a DCS template must not get spreadsheet content"


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_report_main_dcs_in_extension_in_one_call_919():
    report = "tests_E2EMainDcs919"
    assert_ok(call("create_metadata", {"projectName": TESTS_PROJECT, "fqn": "Report." + report}),
              "seed the extension's own report")
    wait_for_project_ready()
    fqn = "Report.%s.Template.MainSchema" % report
    created = call("create_metadata", {"projectName": TESTS_PROJECT, "fqn": fqn, "setAsDefault": True})
    assert_ok(created, "create the main DCS template in one call")
    assert created.structured.get("setAsDefault") is True, created.structured
    assert_contains(created.structured.get("message"),
                    "(templateType DataCompositionSchema), set as the main data composition schema of Report." + report,
                    "the response names the type and the main-schema assignment")
    _assert_main_schema_on_disk(report, "MainSchema", fixture_rel=TESTS_PROJECT_REL)
    # The report's main-DCS root now opens the new, empty schema.
    read = call("dcs", {"projectName": TESTS_PROJECT, "fqn": "Report." + report, "action": "get",
                        "type": "schema", "format": "xml"})
    assert_ok(read, "dcs opens the report main schema")
    assert "DataCompositionSchema" in (read.structured or {}).get("xml", ""), read.structured
    details = call("get_metadata_details", {"projectName": TESTS_PROJECT, "objectFqns": ["Report." + report],
                                            "full": True})
    assert_ok(details, "details read-back")
    assert_contains(details.text, "| MainSchema |  | DataCompositionSchema |", "details show the template type")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_report_main_dcs_with_russian_tokens_919():
    report = "E2EMainDcsRu919"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "%s.%s" % (RU_REPORT, report)}),
              "seed the report with a Russian type token")
    wait_for_project_ready()
    created = call("create_metadata", {
        "projectName": PROJECT, "fqn": "%s.%s.%s.Main" % (RU_REPORT, report, RU_TEMPLATE),
        "properties": [{"name": "templateType", "value": RU_DCS_SPACED}], "setAsDefault": True,
    })
    assert_ok(created, "Russian tokens and the EDT label create the main DCS")
    assert created.structured.get("fqn") == "Report.%s.Template.Main" % report, created.structured
    _assert_main_schema_on_disk(report, "Main")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_template_set_as_default_refusals_919():
    report = "E2EMainDcsRefused919"
    processor = "E2EMainDcsProc919"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Report." + report}), "seed report")
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "DataProcessor." + processor}),
              "seed data processor")
    wait_for_project_ready()
    before = tree_snapshot()
    wrong_type = assert_error(call("create_metadata", {
        "projectName": PROJECT, "fqn": "Report.%s.Template.Text" % report,
        "properties": [{"name": "templateType", "value": "TextDocument"}], "setAsDefault": True,
    }), "a main schema cannot be a text template")
    assert_error_quality(wrong_type, names=["DataCompositionSchema", "TextDocument"], ctx="wrong templateType")
    wrong_owner = assert_error(call("create_metadata", {
        "projectName": PROJECT, "fqn": "DataProcessor.%s.Template.Main" % processor, "setAsDefault": True,
    }), "only a report owns a main schema")
    assert_error_quality(wrong_owner, names=["DataProcessor.%s.Template.Main" % processor], ctx="wrong owner")
    assert_contains(wrong_owner, "Omit setAsDefault", "the refusal names the fix")
    misnamed = assert_error(call("create_metadata", {
        "projectName": PROJECT, "fqn": "Report.%s.Template.Typed" % report,
        "properties": [{"name": "type", "value": "DataCompositionSchema"}],
    }), "'type' is not a create-time template property")
    assert_contains(misnamed, "properties:[{name:'templateType', value:'DataCompositionSchema'}]",
                    "the refusal leads to templateType instead of a second retype call")
    assert_tree_unchanged(before, "the refused creates changed nothing")
    assert "<templates" not in read_disk("src/Reports/%s/%s.mdo" % (report, report))
