"""#662: ``dcs`` reads and writes a schema's area templates and grouping bindings.

They are addressed the way data-set links are: every template node is read and written under
``type='schema'``. The read fixture is seeded through the lossless XML channel; the write tests
author through the typed API and check Template.dcs, and every refusal is pinned to leave the file
byte-for-byte unchanged.
"""

import json
import re

from harness import (
    assert_error,
    assert_error_quality,
    assert_ok,
    assert_tree_unchanged,
    call,
    e2e_test,
    poll_disk_contains,
    poll_disk_contains_all,
    read_disk,
    tree_snapshot,
    wait_for_project_ready,
    PROJECT,
)
from tools.test_dcs import _get, _hash, _poll_report_dcs, _read_all_xml, _seed_report, _write

_AREA_NS = "http://v8.1c.ru/8.1/data-composition-system/area-template"
_CORE_NS = "http://v8.1c.ru/8.1/data-composition-system/core"
_XSI_NS = "http://www.w3.org/2001/XMLSchema-instance"
_LOCAL_NS = 'xmlns:dcsat="%s" xmlns:xsi="%s"' % (_AREA_NS, _XSI_NS)

_TEMPLATE = "E2ETotalRow662"
# A Cyrillic template name, as real configurations carry: "МакетИтогов662".
_RU_TEMPLATE = "\u041c\u0430\u043a\u0435\u0442\u0418\u0442\u043e\u0433\u043e\u0432662"


def _cell(parameter):
    return (
        '<dcsat:tableCell><dcsat:item xsi:type="dcsat:Field">'
        '<dcsat:value xmlns:dcscor="%s" xsi:type="dcscor:Parameter">%s</dcsat:value>'
        "</dcsat:item></dcsat:tableCell>" % (_CORE_NS, parameter)
    )


def _templates_snippet():
    # The shape EDT writes (see the issue): an AreaTemplate body, an expression parameter, then the
    # bindings. The template types are not the "Header" default, so the export must state them.
    return (
        "<template><name>%(t)s</name>"
        '<template %(ns)s xsi:type="dcsat:AreaTemplate">'
        '<dcsat:item xsi:type="dcsat:TableRow">%(c1)s%(c2)s</dcsat:item>'
        "</template>"
        '<parameter %(ns)s xsi:type="dcsat:ExpressionAreaTemplateParameter">'
        "<dcsat:name>TotalNameParam</dcsat:name><dcsat:expression>Amount1</dcsat:expression>"
        "</parameter></template>"
        "<template><name>%(ru)s</name>"
        '<template %(ns)s xsi:type="dcsat:AreaTemplate">'
        '<dcsat:item xsi:type="dcsat:TableRow">%(c1)s</dcsat:item>'
        "</template></template>"
        "<groupTemplate><groupName>Totals</groupName><templateType>Footer</templateType>"
        "<template>%(t)s</template></groupTemplate>"
        "<groupTemplate><groupName>Totals</groupName>"
        "<templateType>HierarchicalFooter</templateType><template>%(ru)s</template></groupTemplate>"
        "<groupHeaderTemplate><groupName>Totals</groupName>"
        "<templateType>OverallHeader</templateType><template>%(ru)s</template>"
        "</groupHeaderTemplate>"
    ) % {"t": _TEMPLATE, "ru": _RU_TEMPLATE, "ns": _LOCAL_NS,
         "c1": _cell("TotalNameParam"), "c2": _cell("TotalNameParam")}


def _with_templates(xml):
    """Insert the templates where the schema orders them: before the settings, else at the end."""
    settings = re.search(r"<(?:\w+:)?(?:defaultSettings|settingsVariant)\b", xml)
    at = settings.start() if settings else xml.rindex("</")
    return xml[:at] + _templates_snippet() + xml[at:]


def _seed_templates(name):
    root = _seed_report(name)
    empty = _get(root, "schema")
    assert_ok(empty, "read the template-free schema summary")
    assert "| Templates | 0 | %s#/templates |" % root in empty.text, empty.text
    xml, _pages = _read_all_xml(root)
    imported = _write(root, "replace", "schema", {"xml": _with_templates(xml)},
                      expectedHash=_hash(empty))
    assert_ok(imported, "seed area templates and grouping bindings through the XML channel")
    dcs_rel = _poll_report_dcs(name, ctx="the template-bearing schema")
    poll_disk_contains(dcs_rel, "groupHeaderTemplate",
                       ctx="the templates and bindings must reach Template.dcs")
    return root, dcs_rel


@e2e_test(tool="dcs", kind="write-metadata")
def test_schema_get_counts_and_addresses_templates_and_grouping_bindings():
    root, _dcs_rel = _seed_templates("E2EDcsTemplatesRead662")
    before = tree_snapshot()

    summary = _get(root, "schema")
    assert_ok(summary, "read the schema summary carrying templates")
    for row in ("| Templates | 2 | %s#/templates |" % root,
                "| Group templates | 2 | %s#/groupTemplates |" % root,
                "| Group header templates | 1 | %s#/groupHeaderTemplates |" % root,
                "| %s | 1 row, 2 cells, 1 parameter | %s#/templates/%s |" % (_TEMPLATE, root, _TEMPLATE),
                "%s#/templates/%s" % (root, _RU_TEMPLATE),
                "| %s#/groupTemplates/0 | Totals · Footer → %s |" % (root, _TEMPLATE),
                "| %s#/groupHeaderTemplates/0 | Totals · OverallHeader → %s |" % (root, _RU_TEMPLATE)):
        assert row in summary.text, "summary must carry %r:\n%s" % (row, summary.text)

    page = _get(root + "#/templates", "schema")
    assert_ok(page, "page the templates collection under the schema type")
    assert "# DCS collection: schema" in page.text and "**Items:** 2" in page.text, page.text
    assert "%s#/templates/%s" % (root, _TEMPLATE) in page.text, page.text

    template = _get(root + "#/templates/" + _TEMPLATE, "schema")
    assert_ok(template, "drill into the area template copied from the summary")
    for row in ("| Body | DataCompositionAreaTemplate |", "| Rows | 1 |", "| Cells | 2 |",
                "| Parameters | 1 |",
                "| TotalNameParam | DataCompositionExpressionAreaParameter | Amount1 |",
                "| %s#/groupTemplates/0 | Totals · Footer → %s |" % (root, _TEMPLATE),
                "format='xml'"):
        assert row in template.text, "template page must carry %r:\n%s" % (row, template.text)
    assert "## Contained structure" not in template.text, template.text

    russian = _get(root + "#/templates/" + _RU_TEMPLATE, "schema")
    assert_ok(russian, "drill into the Cyrillic-named template")
    assert "%s#/groupTemplates/1" % root in russian.text, russian.text
    assert "%s#/groupHeaderTemplates/0" % root in russian.text, russian.text
    assert "%s#/groupTemplates/0" % root not in russian.text, russian.text

    binding = _get(root + "#/groupTemplates/0", "schema")
    assert_ok(binding, "drill into the grouping binding")
    for row in ("| groupName | Totals |", "| templateType | Footer |",
                "| template | %s |" % _TEMPLATE,
                "| Template address | %s#/templates/%s |" % (root, _TEMPLATE)):
        assert row in binding.text, "binding page must carry %r:\n%s" % (row, binding.text)

    assert_tree_unchanged(before, "template reads must not touch the project")


@e2e_test(tool="dcs", kind="write-metadata")
def test_template_addresses_refuse_unknown_keys_wrong_types_and_typed_writes():
    root, dcs_rel = _seed_templates("E2EDcsTemplatesRefuse662")
    disk_before = read_disk(dcs_rel)

    unknown = assert_error(_get(root + "#/templates/Nope662", "schema"),
                           "an unknown template name")
    assert_error_quality(unknown, names=["Nope662", _TEMPLATE, _RU_TEMPLATE],
                         suggests=["Copy one of those"], ctx="unknown template name")

    out_of_range = assert_error(_get(root + "#/groupTemplates/7", "schema"),
                                "an out-of-range binding index")
    assert_error_quality(out_of_range, names=["'7'", "Existing keys/indices at that level: 0, 1."],
                         suggests=["Copy one of those"], ctx="out-of-range binding index")

    mismatch = assert_error(_get(root + "#/templates/" + _TEMPLATE, "dataSet"),
                            "a template read with a foreign type")
    assert_error_quality(mismatch, names=["its type is 'schema'"], suggests=["Pass type='schema'"],
                         ctx="template read type")

    current = _get(root, "schema")
    assert_ok(current, "read the hash before the refused typed writes")
    # Templates are writable now (#662); what stays refused: a template a binding still names, an
    # address inside a template node, and field templates (XML only).
    for action, address, extra, names, suggests in (
            ("remove", root + "#/templates/" + _TEMPLATE, {},
             [_TEMPLATE, root + "#/groupTemplates/0"], ["Update or remove those referring nodes first"]),
            ("update", root + "#/templates/%s/template/items/0" % _TEMPLATE, {"body": {}},
             [root + "#/templates/" + _TEMPLATE], ["body.cells"]),
            ("upsert", root + "#/fieldTemplates", {"body": {"template": _TEMPLATE}},
             ["fieldTemplates"], ["format='xml'", "body={xml:...}"])):
        args = {"projectName": PROJECT, "fqn": address, "action": action, "type": "schema",
                "expectedHash": _hash(current)}
        args.update(extra)
        refused = assert_error(call("dcs", args), "a refused typed %s at a template address" % action)
        assert_error_quality(refused, names=names, suggests=suggests,
                             ctx="refused typed %s at a template address" % action)

    assert read_disk(dcs_rel) == disk_before, \
        "refused reads and typed writes must leave Template.dcs byte-for-byte unchanged"
    xml, _pages = _read_all_xml(root)
    assert _TEMPLATE in xml and _RU_TEMPLATE in xml and "groupHeaderTemplate" in xml, \
        "the lossless XML read must still carry the templates and their bindings"


# ----- typed authoring (#662 part B) -------------------------------------------------------------

_WRITTEN = "E2EFooter662"


def _template_body(name=_WRITTEN):
    return {"name": name,
            "rows": [{"cells": [{"text": "Total"},
                                {"parameter": "Amount662", "appearance": {"HorizontalAlign": "Right"}},
                                {"parameter": "Amount662"}]}],
            "parameters": [{"name": "Amount662", "expression": "Amount1"}]}


def _seed_typed(name, grouping="Totals"):
    """A report whose default settings carry a grouping named ``grouping``, plus one typed template
    and its footer binding written in a single root call."""
    root = _seed_report(name)
    assert_ok(_write(root, "upsert", "grouping", {"name": grouping}), "seed grouping " + grouping)
    written = _write(root, "upsert", "schema", {
        "templates": [_template_body()],
        "groupTemplates": [{"groupName": grouping, "templateType": "Footer", "template": _WRITTEN}],
    })
    assert_ok(written, "author a template and its binding through the typed API")
    dcs_rel = _poll_report_dcs(name, ctx="the typed template")
    return root, dcs_rel, written


def _remove(address, expected_hash):
    return call("dcs", {"projectName": PROJECT, "fqn": address, "action": "remove", "type": "schema",
                         "expectedHash": expected_hash})


def _node_body(page):
    """The JSON body a template or binding page prints under ## Body."""
    match = re.search(r"## Body\n\n[^\n]*\n\n(`{3,})json\n(.*?)\n\1", page.text, re.S)
    assert match, "the node page must print its ## Body:\n%s" % page.text
    return json.loads(match.group(2))


@e2e_test(tool="dcs", kind="write-metadata")
def test_typed_root_upsert_writes_a_template_and_its_binding_to_template_dcs():
    root, dcs_rel, written = _seed_typed("E2EDcsTemplatesWrite662")
    assert "**Templates applied:** templates=1, groupTemplates=1, groupHeaderTemplates=0" in written.text, \
        written.text
    assert "Template warnings" not in written.text, written.text
    disk = poll_disk_contains_all(dcs_rel, [
        "<name>%s</name>" % _WRITTEN, 'xsi:type="dcsat:TableRow"', 'xsi:type="dcscor:Parameter"',
        "Amount662", "<templateType>Footer</templateType>", "<template>%s</template>" % _WRITTEN,
        "HorizontalAlign", ">Right<"], timeout=30, ctx="the typed template and binding")
    assert "ExpressionAreaTemplateParameter" in disk, disk

    page = _get(root + "#/templates/" + _WRITTEN, "schema")
    assert_ok(page, "read the typed template back")
    for row in ("| Rows | 1 |", "| Cells | 3 |", "| Parameters | 1 |",
                "| %s#/groupTemplates/0 | Totals · Footer → %s |" % (root, _WRITTEN)):
        assert row in page.text, "template page must carry %r:\n%s" % (row, page.text)
    body = _node_body(page)
    assert body["name"] == _WRITTEN and body["parameters"] == [{"name": "Amount662", "expression": "Amount1"}], body
    assert body["rows"][0]["cells"][1]["items"] == [{"parameter": "Amount662"}], body


@e2e_test(tool="dcs", kind="write-metadata")
def test_a_cells_patch_changes_only_that_cell_and_replace_with_the_printed_body_is_byte_identical():
    root, dcs_rel, _written = _seed_typed("E2EDcsTemplatesPatch662")
    address = root + "#/templates/" + _WRITTEN
    before = poll_disk_contains(dcs_rel, ">Right<", timeout=30, ctx="the seeded cell appearance")
    page = _get(address, "schema")
    assert_ok(page, "read the template before the patch")

    unhashed = assert_error(_write(address, "update", "schema",
                                   {"cells": [{"row": 0, "col": 2, "appearance": {"HorizontalAlign": "Center"}}]}),
                            "a cells patch without expectedHash")
    assert_error_quality(unhashed, names=["expectedHash", "body.cells"], suggests=["action='get'"])

    patched = _write(address, "update", "schema",
                     {"cells": [{"row": 0, "col": 2, "appearance": {"HorizontalAlign": "Center"}}]},
                     expectedHash=_hash(page))
    assert_ok(patched, "patch one cell's appearance")
    after = poll_disk_contains(dcs_rel, ">Center<", timeout=30, ctx="the patched cell")
    assert after.count(">Right<") == before.count(">Right<") == 1, after
    assert after.count("Amount662") == before.count("Amount662"), after
    assert after.count("<dcsat:tableCell>") == before.count("<dcsat:tableCell>") == 3, after

    # The ## Body the read prints restates the node exactly: replacing with it changes no byte.
    page = _get(address, "schema")
    assert_ok(page, "read the patched template")
    body = _node_body(page)
    assert body["rows"][0]["cells"][2]["appearance"]["items"][0]["parameter"]["value"] == "HorizontalAlign", body
    disk_before = read_disk(dcs_rel)
    restated = _write(address, "replace", "schema", body, expectedHash=_hash(page))
    assert_ok(restated, "replace the template with the body get printed")
    assert read_disk(dcs_rel) == disk_before, "a replace with the printed body must not change Template.dcs"
    again = _get(address, "schema")
    assert_ok(again, "read the restated template")
    assert _node_body(again) == body, again.text

    outside = assert_error(_write(address, "update", "schema",
                                  {"cells": [{"row": 3, "col": 0, "text": "x"}]}, expectedHash=_hash(again)),
                           "a cell outside the grid")
    assert_error_quality(outside, names=["row 3", "1 row(s) with 3 cell(s)"], suggests=["Re-run get"])
    assert read_disk(dcs_rel) == disk_before


@e2e_test(tool="dcs", kind="write-metadata")
def test_a_stored_value_the_writer_cannot_restate_blocks_replace_and_rows_but_not_a_cells_patch():
    root, dcs_rel, _written = _seed_typed("E2EDcsTemplatesGuard662")
    poll_disk_contains(dcs_rel, ">Right<", timeout=30, ctx="the seeded cell appearance")
    current = _get(root, "schema")
    assert_ok(current, "read the schema before the XML seed")
    xml, _pages = _read_all_xml(root)
    # Rename the stored appearance key of cell 1 to a name outside the platform catalogue.
    foreign, count = re.subn(r"(<(?:\w+:)?parameter>)HorizontalAlign(</(?:\w+:)?parameter>)",
                             r"\1NoSuchKey662\2", xml)
    assert count == 1, xml
    assert_ok(_write(root, "replace", "schema", {"xml": foreign}, expectedHash=_hash(current)),
              "seed a foreign appearance key through the XML channel")
    poll_disk_contains(dcs_rel, "NoSuchKey662", timeout=30, ctx="the foreign key on disk")
    address = root + "#/templates/" + _WRITTEN
    page = _get(address, "schema")
    assert_ok(page, "read the template carrying the foreign key")
    disk_before = read_disk(dcs_rel)

    for action, body in (("replace", _node_body(page)), ("update", {"rows": _template_body()["rows"]})):
        refused = assert_error(_write(address, action, "schema", body, expectedHash=_hash(page)),
                               "a %s that would drop the foreign key" % action)
        assert_error_quality(refused, names=["NoSuchKey662", _WRITTEN], suggests=["body.cells"],
                             ctx="%s over a value the writer cannot restate" % action)
        assert read_disk(dcs_rel) == disk_before, "a refused %s must not change Template.dcs" % action

    patched = _write(address, "update", "schema", {"cells": [{"row": 0, "col": 0, "text": "Sum"}]},
                     expectedHash=_hash(page))
    assert_ok(patched, "a cells patch of another cell keeps working")
    disk = poll_disk_contains(dcs_rel, ">Sum<", timeout=30, ctx="the patched text")
    assert "NoSuchKey662" in disk, "the foreign key must still be on disk:\n%s" % disk


@e2e_test(tool="dcs", kind="write-metadata")
def test_template_integrity_bindings_need_templates_and_bound_templates_stay():
    root, dcs_rel, _written = _seed_typed("E2EDcsTemplatesIntegrity662")
    poll_disk_contains(dcs_rel, "<templateType>Footer</templateType>", timeout=30, ctx="the binding")
    current = _get(root, "schema")
    assert_ok(current, "read the hash")
    disk_before = read_disk(dcs_rel)

    dangling = assert_error(_write(root + "#/groupHeaderTemplates", "upsert", "schema",
                                   {"groupName": "Totals", "template": "Missing662"}),
                            "a binding to a missing template")
    assert_error_quality(dangling, names=["Missing662", _WRITTEN], suggests=["Create the template first"])
    bound = assert_error(_remove(root + "#/templates/" + _WRITTEN, _hash(current)), "remove a bound template")
    assert_error_quality(bound, names=[_WRITTEN, root + "#/groupTemplates/0"],
                         suggests=["Update or remove those referring nodes first"])
    assert read_disk(dcs_rel) == disk_before, "refused integrity writes must not change Template.dcs"

    warned = _write(root + "#/groupHeaderTemplates", "upsert", "schema",
                    {"groupName": "Elsewhere662", "templateType": "OverallHeader", "template": _WRITTEN})
    assert_ok(warned, "a binding to an unknown grouping is written")
    assert "**Template warnings:**" in warned.text and "Elsewhere662" in warned.text \
        and "Totals" in warned.text, warned.text
    poll_disk_contains(dcs_rel, "Elsewhere662", timeout=30, ctx="the warned binding")

    for address in (root + "#/groupTemplates/0", root + "#/groupHeaderTemplates/0"):
        page = _get(address, "schema")
        assert_ok(page, "read " + address)
        assert_ok(_remove(address, _hash(page)), "remove " + address)
    page = _get(root + "#/templates/" + _WRITTEN, "schema")
    assert_ok(_remove(root + "#/templates/" + _WRITTEN, _hash(page)),
              "remove the unbound template")
    summary = _get(root, "schema")
    assert "| Templates | 0 | %s#/templates |" % root in summary.text, summary.text
    disk = read_disk(dcs_rel)
    assert _WRITTEN not in disk, disk


@e2e_test(tool="dcs", kind="write-metadata")
def test_a_data_set_link_node_update_is_not_judged_as_a_root_body():
    # Before #662 a body at #/dataSetLinks/<i> was checked against the root member list
    # ("Unknown schema body member") and a replace entered the settings planner.
    report = "E2EDcsLinkUpdate662"
    root = "Report." + report
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}), "seed report")
    wait_for_project_ready()
    assert_ok(_write(root, "upsert", "schema", {
        "dataSources": [{"name": "MainSource", "type": "Local"}],
        "dataSets": [{"name": "Source", "type": "query", "dataSource": "MainSource",
                      "query": "SELECT 1 AS Key, 2 AS Other", "autoFillFields": False,
                      "fields": [{"dataPath": "Key"}, {"dataPath": "Other"}]},
                     {"name": "Destination", "type": "query", "dataSource": "MainSource",
                      "query": "SELECT 1 AS Key", "autoFillFields": False,
                      "fields": [{"dataPath": "Key"}]}],
        "dataSetLinks": [{"sourceDataSet": "Source", "destinationDataSet": "Destination",
                          "sourceExpression": "Key", "destinationExpression": "Key"}],
    }), "author a data-set link")
    dcs_rel = _poll_report_dcs(report, ctx="the linked schema")
    poll_disk_contains(dcs_rel, "<sourceExpression>Key</sourceExpression>", timeout=30, ctx="the link")
    link = root + "#/dataSetLinks/0"
    page = _get(link, "schema")
    assert_ok(page, "read the link")
    updated = _write(link, "update", "schema", {"sourceExpression": "Other"}, expectedHash=_hash(page))
    assert_ok(updated, "update one member of an index-addressed data-set link")
    disk = poll_disk_contains(dcs_rel, "<sourceExpression>Other</sourceExpression>", timeout=30,
                              ctx="the updated link")
    assert "<destinationExpression>Key</destinationExpression>" in disk, disk
    page = _get(link, "schema")
    replaced = _write(link, "replace", "schema", {"sourceDataSet": "Source", "destinationDataSet": "Destination",
                                                  "sourceExpression": "Key", "destinationExpression": "Key"},
                      expectedHash=_hash(page))
    assert_ok(replaced, "replace an index-addressed data-set link")
    poll_disk_contains(dcs_rel, "<sourceExpression>Key</sourceExpression>", timeout=30, ctx="the replaced link")
