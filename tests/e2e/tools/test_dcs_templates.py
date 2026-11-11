"""#662: ``dcs get`` counts and addresses a schema's area templates and grouping bindings.

Read-only, the way data-set links are read: every template node is read under ``type='schema'``
because the typed write vocabulary has no template type. The fixture is seeded through the
lossless XML channel (the only authoring path for templates), and every read is pinned to leave
the project tree untouched.
"""

import re

from harness import (
    assert_error,
    assert_error_quality,
    assert_ok,
    assert_tree_unchanged,
    call,
    e2e_test,
    poll_disk_contains,
    read_disk,
    tree_snapshot,
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
    for action, address, extra in (
            ("remove", root + "#/templates/" + _TEMPLATE, {}),
            ("update", root + "#/groupTemplates/0", {"body": {"templateType": "Header"}}),
            ("upsert", root + "#/groupHeaderTemplates", {"body": {"groupName": "Other"}})):
        args = {"projectName": PROJECT, "fqn": address, "action": action, "type": "schema",
                "expectedHash": _hash(current)}
        args.update(extra)
        refused = assert_error(call("dcs", args), "a typed %s at a template address" % action)
        assert_error_quality(refused, names=["read-only", address],
                             suggests=["format='xml'", "action='replace'"],
                             ctx="typed %s at a template address" % action)

    assert read_disk(dcs_rel) == disk_before, \
        "refused reads and typed writes must leave Template.dcs byte-for-byte unchanged"
    xml, _pages = _read_all_xml(root)
    assert _TEMPLATE in xml and _RU_TEMPLATE in xml and "groupHeaderTemplate" in xml, \
        "the lossless XML read must still carry the templates and their bindings"
