"""End-to-end contract tests for the ``dcs`` schema read/write path (#404).

The fixture does not carry four convenient DCS roots, so happy-path tests seed their own
metadata with the existing authoring tools and then pin the project diff before every read.
The post-read equality checks are load-bearing: ``BasicTemplate.getTemplate()`` materializes
resources lazily, and a read must still roll that model side effect back.
"""

import os
import re
import time
import xml.etree.ElementTree as ET

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
    poll_disk_lacks,
    read_disk,
    wait_for_project_ready,
)


MAIN_DCS_TEMPLATE = (
    "\u041e\u0441\u043d\u043e\u0432\u043d\u0430\u044f"
    "\u0421\u0445\u0435\u043c\u0430\u041a\u043e\u043c\u043f\u043e\u043d\u043e\u0432\u043a\u0438"
    "\u0414\u0430\u043d\u043d\u044b\u0445"
)

_UUID_RE = re.compile(
    r"(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])"
)

_OUTPUT_GUARD_NOTICE = "so the response stays under the size cap."


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
    assert_ok(call("dcs", {
        "projectName": PROJECT,
        "fqn": fqn,
        "action": "upsert",
        "type": "schema",
        "body": {"dataSets": data_sets},
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


def _poll_report_dcs(report_name, timeout=30, ctx=""):
    """The report's Template.dcs path, once EDT has actually written the file.

    A BM write only schedules the export, so the file appears after the call returns. Polling the
    git diff for the REPORT NAME does not wait for it: seeding the report already put that name in
    the diff, so the poll returns immediately and the test then reads a file that does not exist
    yet. It passed locally and failed on CI, which is exactly the shape of a race. Wait for the
    file itself, which is the thing the assertion actually needs.
    """
    deadline = time.time() + timeout
    while True:
        found = _find_report_dcs(report_name)
        if found:
            return found
        if time.time() >= deadline:
            raise AssertionError(
                "Template.dcs never materialized for %s within %gs [%s] - the write reported "
                "success, so either the force-export did not run or it targeted another object"
                % (report_name, timeout, ctx))
        time.sleep(0.5)


def _assert_read_did_not_change(before, ctx):
    assert diff() == before, "%s must not change the seeded project" % ctx


def _hash(result):
    match = re.search(r"\*\*Hash:\*\* `([0-9a-f]{20})`", result.text)
    assert match, "dcs result must carry a 20-character hash:\n%s" % result.text
    return match.group(1)


def _projection_without_hash(result):
    parts = result.text.split("\n\n", 1)
    return parts[1] if len(parts) == 2 else result.text


def _xml_structure(xml):
    """Canonical structural XML with volatile UUID values normalized."""
    normalized = _UUID_RE.sub("00000000-0000-0000-0000-000000000000", xml)
    root = ET.fromstring(normalized)

    def node(element):
        text = element.text
        if text is not None and not text.strip():
            text = None
        return (
            element.tag,
            tuple(sorted(element.attrib.items())),
            text,
            tuple(node(child) for child in element),
        )

    return node(root)


def _read_all_xml(fqn, limit=None):
    """Read every JSON-envelope page and enforce the transfer invariants."""
    offset = 0
    chunks = []
    pages = []
    transfer_hash = None
    total_chars = None
    while True:
        extra = {"format": "xml", "offset": offset}
        if limit is not None:
            extra["limit"] = limit
        result = _get(fqn, "schema", **extra)
        assert_ok(result, "read DCS XML chunk at offset %d" % offset)
        page = result.structured
        assert isinstance(page, dict), \
            "format=xml must return a JSON envelope, got: %r" % (page,)
        assert page.get("success") is True, "XML envelope must report success: %r" % page
        assert page.get("offset") == offset, \
            "XML page must start at requested offset %d: %r" % (offset, page)
        assert "hasMore" in page and type(page["hasMore"]) is bool, \
            "XML envelope must carry an explicit boolean hasMore: %r" % page
        assert isinstance(page.get("totalChars"), int) and page["totalChars"] >= 0, \
            "XML envelope must carry totalChars: %r" % page
        assert re.fullmatch(r"[0-9a-f]{20}", page.get("hash", "")), \
            "every XML chunk must carry the normal 20-character DCS hash: %r" % page
        assert isinstance(page.get("xml"), str), "XML envelope must carry a string chunk: %r" % page

        if transfer_hash is None:
            transfer_hash = page["hash"]
            total_chars = page["totalChars"]
        else:
            assert page["hash"] == transfer_hash, \
                "the schema changed during the paged XML transfer"
            assert page["totalChars"] == total_chars, \
                "totalChars changed during the paged XML transfer"

        chunk = page["xml"]
        assert _OUTPUT_GUARD_NOTICE not in chunk, \
            "OutputSizeGuard must never splice its truncation notice into an XML chunk"
        has_more = page["hasMore"]
        if has_more:
            assert "nextOffset" in page and type(page["nextOffset"]) is int, \
                "a non-terminal XML page must carry numeric nextOffset: %r" % page
            next_offset = page["nextOffset"]
            assert next_offset == offset + len(chunk), \
                "nextOffset must equal offset plus this chunk length: %r" % page
            assert next_offset > offset, "a non-final XML page must make progress: %r" % page
        else:
            assert "nextOffset" not in page, \
                "a terminal XML page must omit nextOffset and use hasMore=false: %r" % page
        chunks.append(chunk)
        pages.append(page)
        if not has_more:
            break
        offset = page["nextOffset"]

    document = "".join(chunks)
    assert len(document) == total_chars, \
        "concatenated XML length must equal totalChars (%d != %d)" % (len(document), total_chars)
    return document, pages


@e2e_test(tool="dcs", kind="write-metadata")
def test_small_lossless_xml_schema_round_trip_is_one_chunk_and_identical_on_disk():
    language = "Language.E2EDcsRussianXml"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": language}),
              "declare Russian for the bilingual XML fixture")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": language,
        "properties": [{"name": "languageCode", "value": "ru"}],
    }), "assign the Russian language code")
    wait_for_project_ready()

    source_name = "E2EDcsXmlSource"
    source_root = "Report." + source_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": source_root}),
              "create the source XML report")
    wait_for_project_ready()
    authored = _write(source_root, "upsert", "schema", {
        "dataSources": [{"name": "DataSource1", "type": "Local"}],
        "dataSets": [{
            "name": "Sales",
            "type": "query",
            "dataSource": "DataSource1",
            "query": "SELECT 1 AS Customer, 2 AS Amount",
            "autoFillFields": False,
            "fields": [{
                "dataPath": "Customer",
                "field": "Customer",
                "title": {"en": "Customer", "ru": "\u041a\u043b\u0438\u0435\u043d\u0442"},
            }, {
                "dataPath": "Amount",
                "field": "Amount",
                "title": {"en": "Amount", "ru": "\u0421\u0443\u043c\u043c\u0430"},
            }],
        }, {
            "name": "Archive",
            "type": "query",
            "dataSource": "DataSource1",
            "query": "SELECT 1 AS Customer",
            "autoFillFields": False,
            "fields": [{"dataPath": "Customer", "field": "Customer"}],
        }],
        "dataSetLinks": [{
            "sourceDataSet": "Sales",
            "destinationDataSet": "Archive",
            "sourceExpression": "Customer",
            "destinationExpression": "Customer",
        }],
        "parameters": [{
            "name": "Period",
            "title": {"en": "Period", "ru": "\u041f\u0435\u0440\u0438\u043e\u0434"},
            "use": "Always",
        }],
        "variants": [{
            "name": "ManagerView",
            "presentation": {"en": "Manager view", "ru": "\u0414\u043b\u044f \u0440\u0443\u043a\u043e\u0432\u043e\u0434\u0438\u0442\u0435\u043b\u044f"},
            "settings": {
                "selection": {"items": [{
                    "field": {"kind": "field", "value": "Customer"},
                    "use": True,
                }]},
                "filter": {"items": [{
                    "left": {"kind": "field", "value": "Amount"},
                    "comparisonType": "Greater",
                    "right": [{"kind": "number", "value": 0}],
                    "use": True,
                }]},
                "order": {"items": [{
                    "field": {"kind": "field", "value": "Customer"},
                    "orderType": "Asc",
                    "use": True,
                }]},
                "conditionalAppearance": {"items": [{
                    "use": True,
                    "selection": {"items": [{
                        "field": {"kind": "field", "value": "Amount"},
                    }]},
                    "filter": {"items": [{
                        "left": {"kind": "field", "value": "Amount"},
                        "comparisonType": "Less",
                        "right": [{"kind": "number", "value": 0}],
                    }]},
                }]},
            },
        }],
    }, language="en")
    assert_ok(authored, "author the non-trivial bilingual source schema")

    source_rel = _poll_report_dcs(source_name, ctx="the XML round-trip source schema")
    poll_disk_contains(source_rel, "ManagerView",
                       ctx="the complete source fixture must reach Template.dcs")
    source_disk = read_disk(source_rel)

    source_xml, source_pages = _read_all_xml(source_root)
    assert len(source_pages) == 1 and source_pages[0]["hasMore"] is False, \
        "the small fixture must exercise the single-chunk XML contract"
    assert "DataCompositionSchema" in source_xml, \
        "format=xml must return the complete serialized schema: %s" % source_xml[:400]

    target_name = "E2EDcsXmlTarget"
    target_root = "Report." + target_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": target_root}),
              "create the fresh XML target report")
    wait_for_project_ready()
    target_before = _get(target_root, "schema")
    assert_ok(target_before, "read the fresh target hash required by replace")

    replaced = _write(target_root, "replace", "schema", {"xml": source_xml},
                      expectedHash=_hash(target_before))
    assert_ok(replaced, "replace the fresh target with the serialized source XML")
    assert "xml=wholesale" in replaced.text, \
        "the result must identify the wholesale XML replacement: %s" % replaced.text[:400]

    target_rel = _poll_report_dcs(target_name, ctx="the XML round-trip target schema")
    poll_disk_contains(target_rel, "ManagerView",
                       ctx="the replaced schema must be present on disk before comparison")
    target_disk = read_disk(target_rel)
    assert _xml_structure(target_disk) == _xml_structure(source_disk), \
        "source and target Template.dcs files must be structurally identical after UUID normalization"

    target_xml, target_pages = _read_all_xml(target_root)
    assert len(target_pages) == 1 and target_pages[0]["hasMore"] is False, \
        "the copied small schema must still fit in one XML chunk"
    assert _xml_structure(target_xml) == _xml_structure(source_xml), \
        "the target model must contain the complete source schema after wholesale replacement"

    before_invalid_replace = read_disk(target_rel)
    current = _get(target_root, "schema")
    assert_ok(current, "read the target hash before the invalid wholesale replacement")
    invalid_xml, replacements = re.subn(
        r"(<[^>]*destinationDataSet[^>]*>)Archive(</[^>]*destinationDataSet>)",
        r"\1MissingDataSet\2", source_xml, count=1)
    assert replacements == 1, \
        "the serialized fixture must expose its link destination for corruption"

    refused = _write(target_root, "replace", "schema", {"xml": invalid_xml},
                     expectedHash=_hash(current))
    refusal = assert_error(refused, "replace schema XML with a dangling link destination")
    assert_error_quality(refusal,
                         names=["MissingDataSet", target_root + "#/dataSetLinks/0"],
                         suggests=["Add or keep", "data set"],
                         ctx="wholesale XML replacement names the dangling link endpoint")
    assert read_disk(target_rel) == before_invalid_replace, \
        "a refused wholesale XML replacement must leave Template.dcs byte-for-byte unchanged"


@e2e_test(tool="dcs", kind="write-metadata")
def test_large_xml_schema_pages_past_output_guard_and_round_trips_whole_document():
    language = "Language.E2EDcsLargeRussianXml"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": language}),
              "declare Russian for the large bilingual XML fixture")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": language,
        "properties": [{"name": "languageCode", "value": "ru"}],
    }), "assign the Russian language code for the large fixture")
    wait_for_project_ready()

    source_name = "E2EDcsLargeXmlSource"
    source_root = "Report." + source_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": source_root}),
              "create the large XML source report")
    wait_for_project_ready()

    field_count = 512
    fields = []
    for index in range(field_count):
        field_name = "TransferField%04d" % index
        fields.append({
            "dataPath": field_name,
            "field": field_name,
            "title": {
                "en": "Transfer field title %04d" % index,
                "ru": "\u041f\u043e\u043b\u0435 \u0434\u0430\u043d\u043d\u044b\u0445 %04d" % index,
            },
        })
    authored = _write(source_root, "upsert", "schema", {
        "dataSources": [{"name": "DataSource1", "type": "Local"}],
        "dataSets": [{
            "name": "LargeTransfer",
            "type": "query",
            "dataSource": "DataSource1",
            "query": "SELECT 1 AS TransferField0000",
            "autoFillFields": False,
            "fields": fields,
        }],
    }, language="en")
    assert_ok(authored, "author a DCS fixture larger than the output guard")

    source_rel = _poll_report_dcs(source_name, ctx="the large XML source schema")
    poll_disk_contains(source_rel, "Transfer field title 0511",
                       ctx="all large-fixture fields must reach Template.dcs")
    source_disk = read_disk(source_rel)
    assert len(source_disk) > 100_000, \
        "the regression fixture must exceed OutputSizeGuard.MAX_CONTENT_CHARS: %d" % len(source_disk)

    source_xml, pages = _read_all_xml(source_root)
    assert len(pages) > 1 and all(
        page["hasMore"] is True and type(page["nextOffset"]) is int
        for page in pages[:-1]
    ), \
        "every non-terminal chunk must carry hasMore=true and numeric nextOffset"
    assert "hasMore" in pages[-1] and pages[-1]["hasMore"] is False, \
        "the terminal chunk must carry the explicit hasMore=false wire signal"
    assert pages[0]["hasMore"] is True, \
        "the first chunk must prove that a >100,000-character schema is paged"
    assert pages[0]["totalChars"] > 100_000, \
        "the EDT-serialized transfer itself must exceed the guard budget"

    target_name = "E2EDcsLargeXmlTarget"
    target_root = "Report." + target_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": target_root}),
              "create the fresh large XML target report")
    wait_for_project_ready()
    target_before = _get(target_root, "schema")
    assert_ok(target_before, "read the fresh large target hash required by replace")

    replaced = _write(target_root, "replace", "schema", {"xml": source_xml},
                      expectedHash=_hash(target_before))
    assert_ok(replaced, "replace the target with the reassembled whole XML document")
    assert "xml=wholesale" in replaced.text, \
        "the large replacement must use the wholesale XML path: %s" % replaced.text[:400]

    target_rel = _poll_report_dcs(target_name, ctx="the large XML target schema")
    poll_disk_contains(target_rel, "Transfer field title 0511",
                       ctx="the reassembled large schema must reach the target Template.dcs")
    target_disk = read_disk(target_rel)
    assert _xml_structure(target_disk) == _xml_structure(source_disk), \
        "large source and target Template.dcs files must match after UUID normalization"


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
def test_union_member_fields_page_prints_addresses_that_resolve_verbatim():
    report_name = "E2EDcsUnionFields"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for recursive union fields")
    wait_for_project_ready()
    fields = [
        {"dataPath": "UnionField%02d" % index, "field": "UnionField%02d" % index}
        for index in range(25)
    ]
    authored = _write(root, "upsert", "schema", {
        "dataSets": [{
            "name": "AllSales",
            "type": "union",
            "items": [{
                "name": "Retail",
                "type": "query",
                "query": "SELECT 1 AS UnionField00",
                "autoFillFields": False,
                "fields": fields,
            }],
        }],
    })
    assert_ok(authored, "author a union whose member data set owns the fields")
    dcs_rel = _poll_report_dcs(report_name, ctx="the recursive union fixture")
    poll_disk_contains(dcs_rel, "UnionField24",
                       ctx="all union-member fields must reach Template.dcs")

    page = _get(root, "field", limit=100)
    assert_ok(page, "page fields across recursive union members")
    expected = root + "#/dataSets/AllSales/items/Retail/fields/UnionField00"
    copied = re.search(re.escape(expected), page.text)
    assert copied, "the root field page must include the union member field address: %s" % page.text
    assert "/fields/fields/" not in page.text, \
        "the field feature must be appended exactly once: %s" % page.text

    resolved = _get(copied.group(0), "field")
    assert_ok(resolved, "resolve an address copied verbatim from the root field page")
    assert "UnionField00" in resolved.text, \
        "the copied address must resolve the actual member field: %s" % resolved.text

    edited_marker = "EditedUnionFieldSource"
    edited = _write(copied.group(0), "update", "field", {"field": edited_marker},
                    expectedHash=_hash(resolved))
    assert_ok(edited, "edit a union-member field through the address copied from its page")
    poll_disk_contains(dcs_rel, edited_marker,
                       ctx="the nested field edit must reach Template.dcs")

    bounded = _get(root + "#/dataSets/AllSales/items/Retail/fields/MissingUnionField", "field")
    error = assert_error(bounded, "bad selector in a large union-member field collection")
    assert_error_quality(error, names=["MissingUnionField", "UnionField19"],
                         suggests=["(5 more)", "parent collection"],
                         ctx="large pointer errors show a bounded sample and the omitted count")
    assert "UnionField20" not in error, \
        "the pointer error must not dump every sibling key: %s" % error

    on_disk = read_disk(dcs_rel)
    assert "UnionField00" in on_disk and "UnionField24" in on_disk \
        and edited_marker in on_disk, \
        "the recursively-read and edited fields must originate from Template.dcs"


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
    field = "E2EDcsExplicitAmount"
    parameter = "E2EDcsTypedPeriod"
    created = _write(root, "upsert", "schema", {
        "dataSets": [{
            "name": "Sales",
            "type": "query",
            "query": first_query,
            "autoFillFields": False,
            "fields": [{"dataPath": field}],
        }],
        "parameters": [{
            "name": parameter,
            "valueType": {"types": [{"kind": "Date", "fractions": "Date"}]},
        }],
    })
    assert_ok(created, "author a schema batch with a query dataset and typed parameter")
    poll_diff_contains("E2EDcsWriteFirst",
                       ctx="the dcs write must force-export the first query to disk")
    poll_diff_contains(field,
                       ctx="the explicit dataset field must force-export to Template.dcs")
    poll_diff_contains(parameter,
                       ctx="the typed schema parameter must force-export to Template.dcs")

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

    dcs_rel = _poll_report_dcs(report_name, ctx="the first dcs write")
    on_disk = read_disk(dcs_rel)
    assert "E2EDcsWriteSecond" in on_disk, "the committed query must persist in %s" % dcs_rel
    assert "E2EDcsWriteFirst" not in on_disk, "the old query must be replaced in %s" % dcs_rel
    assert field in on_disk, "the explicit dataset field must persist in %s" % dcs_rel
    assert parameter in on_disk, "the typed parameter must persist in %s" % dcs_rel
    assert "<dataSourceType>Local</dataSourceType>" in on_disk, \
        "the lazy-created data source must use EDT's canonical Local token in %s" % dcs_rel
    assert on_disk.count("<name>Sales</name>") == 1, \
        "the report's .dcs must contain one Sales dataset, not duplicate natural keys"


@e2e_test(tool="dcs", kind="write-metadata")
def test_calculated_field_upserts_in_place_and_persists_to_disk():
    report_name = "E2EDcsWriteCalculated"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for calculated-field write")
    wait_for_project_ready()

    data_path = "E2EDcsCalcMargin"
    first_expression = "E2EDcsRevenue - E2EDcsCost"
    second_expression = "E2EDcsRevenue * 2 - E2EDcsCost"
    created = _write(root, "upsert", "calculatedField", {
        "dataPath": data_path,
        "expression": first_expression,
        "title": "Margin",
    })
    assert_ok(created, "author a calculated field through dcs")
    poll_diff_contains(first_expression,
                       ctx="the calculated field expression must reach Template.dcs")

    dcs_rel = _poll_report_dcs(report_name, ctx="the first calculated-field write")
    first_disk = read_disk(dcs_rel)
    assert data_path in first_disk and first_expression in first_disk, \
        "the calculated field and expression must persist in %s" % dcs_rel

    updated = _write(root + "#/calculatedFields/" + data_path, "upsert", "calculatedField", {
        "expression": second_expression,
    })
    assert_ok(updated, "update the calculated field by its natural-key address")
    poll_diff_contains(second_expression,
                       ctx="the updated calculated-field expression must reach Template.dcs")

    second_disk = read_disk(dcs_rel)
    assert second_expression in second_disk, "the updated expression must persist in %s" % dcs_rel
    assert first_expression not in second_disk, "the old expression must be removed from %s" % dcs_rel
    assert second_disk.count(data_path) == 1, \
        "the calculated field must be updated in place, never duplicated in %s" % dcs_rel


@e2e_test(tool="dcs", kind="write-metadata")
def test_update_renames_unreferenced_data_set_in_template_dcs():
    report_name = "E2EDcsRenameDataSet"
    root = _seed_report(report_name)
    old_name = "DataSet1"
    new_name = "RenamedSet"
    before = _get(root + "#/dataSets/" + old_name, "dataSet")
    assert_ok(before, "read the data set and root hash before renaming")

    renamed = _write(root + "#/dataSets/" + old_name, "update", "dataSet", {
        "name": new_name,
    }, expectedHash=_hash(before))
    assert_ok(renamed, "rename an unreferenced data set through update")

    dcs_rel = _poll_report_dcs(report_name, ctx="the renamed data-set fixture")
    poll_disk_contains(dcs_rel, new_name,
                       ctx="the new data-set name must reach Template.dcs")
    poll_disk_lacks(dcs_rel, old_name,
                    ctx="the old data-set name must leave Template.dcs")
    on_disk = read_disk(dcs_rel)
    assert new_name in on_disk
    assert old_name not in on_disk


@e2e_test(tool="dcs", kind="write-metadata")
def test_unsupported_root_is_a_clean_non_mutating_error():
    root = "Catalog.Catalog"
    result = _write(root, "upsert", "schema", {
        "dataSets": [{
            "name": "DataSet1",
            "type": "query",
            "query": "SELECT Ref FROM Catalog.Catalog",
        }],
    })
    error = assert_error(result, "a Catalog is not a supported DCS root")
    assert_error_quality(error, names=[root], suggests=["Report.<Name>", "CommonTemplate"],
                         ctx="the unsupported-root error names the target and valid root shapes")
    assert_no_diff("a rejected unsupported-root write must change nothing on disk")


@e2e_test(tool="dcs", kind="write-metadata")
def test_localized_title_rejects_an_undeclared_language():
    report_name = "E2EDcsUndeclaredLocale"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for undeclared-locale validation")
    wait_for_project_ready()

    result = _write(root, "upsert", "parameter", {
        "name": "Period",
        "title": {"fr_CA": "Periode"},
    })
    error = assert_error(result, "a DCS title in an undeclared language")
    assert_error_quality(error, names=["fr_CA"], suggests=["en"],
                         ctx="the locale error names the bad code and declared alternatives")
    assert "fr_CA" not in diff(), "a rejected locale must not reach disk"
    assert "Periode" not in diff(), "a rejected localized title must not reach disk"


@e2e_test(tool="dcs", kind="write-metadata")
def test_localized_title_uses_the_declared_language_code_spelling():
    report_name = "E2EDcsCanonicalLocale"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for language-code canonicalization")
    wait_for_project_ready()

    result = _write(root, "upsert", "parameter", {
        "name": "Period",
        "title": {"EN": "Period"},
    })
    assert_ok(result, "accept a declared language code in another case")
    # forceExportToDisk only SCHEDULES the flush, so poll for it instead of reading the tree
    # immediately - the write itself already reported success.
    dcs_rel = _poll_report_dcs(report_name, ctx="the localized parameter write")
    on_disk = read_disk(dcs_rel)
    assert ">en<" in on_disk, \
        "the title must use the configuration's declared spelling 'en': %s" % on_disk[:700]
    assert ">EN<" not in on_disk, \
        "the requested casing must not create a second language key: %s" % on_disk[:700]


@e2e_test(tool="dcs", kind="write-metadata")
def test_localized_title_warns_for_a_declared_but_unused_language():
    language = "Language.E2EDcsFrench"
    report_name = "E2EDcsUnusedLocale"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": language}),
              "add a second configuration language")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": language,
        "properties": [{"name": "languageCode", "value": "fr"}],
    }), "assign the second language code")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for unused-language warning")
    wait_for_project_ready()

    unused = _write(root, "upsert", "parameter", {
        "name": "Period",
        "title": {"fr": "P\u00e9riode"},
    })
    assert_ok(unused, "write a title in a declared but unused language")
    assert "**localeUnusedInConfiguration:** `true` (fr)." in unused.text, \
        "the Markdown result must warn about the declared but unused language:\n%s" % unused.text
    assert "Ask the user before translating further." in unused.text, \
        "the unused-language warning must remain actionable"

    in_use = _write(root + "#/parameters/Period", "upsert", "parameter", {
        "title": {"en": "Period"},
    })
    assert_ok(in_use, "write the same title in the configuration's in-use language")
    assert "localeUnusedInConfiguration" not in in_use.text, \
        "the configuration's in-use language must not produce the warning: %s" % in_use.text

    valid_schema = _write(root, "upsert", "schema", {
        "dataSources": [{"name": "DataSource1", "type": "Local"}],
        "parameters": [{"name": "Period", "title": {"en": "Period"}}],
    })
    assert_ok(valid_schema, "write a titleless data source beside an in-use localized title")
    assert "localeUnusedInConfiguration" not in valid_schema.text, \
        "members without a presentation must not produce a language warning: %s" % valid_schema.text


@e2e_test(tool="dcs", kind="write-metadata")
def test_unsupported_title_locations_are_rejected_without_a_locale_warning():
    root = "Report.E2EDcsUnsupportedTitle"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for unsupported-title validation")
    wait_for_project_ready()
    before = diff()

    on_source = _write(root, "upsert", "schema", {
        "dataSources": [{
            "name": "DataSource1",
            "type": "Local",
            "title": {"fr": "Ignored"},
        }],
    })
    source_error = assert_error(on_source, "a data source has no title member")
    assert_error_quality(source_error, names=["title", "dataSources[0]"],
                         suggests=["Accepted members", "Remove 'title'"],
                         ctx="unsupported data-source presentations explain the valid body shape")
    assert "localeUnusedInConfiguration" not in on_source.text, \
        "an unsupported presentation must not produce a locale warning"
    assert diff() == before, "a rejected data-source title must not change the project"

    nested_lookalike = _write(root, "upsert", "schema", {
        "dataSources": [{
            "name": "DataSource1",
            "type": "Local",
            "parameters": [{"title": {"fr_CA": "Ignored"}}],
        }],
    })
    nested_error = assert_error(nested_lookalike,
                                "a data source has no nested parameters member")
    assert_error_quality(nested_error, names=["parameters", "dataSources[0]"],
                         suggests=["Accepted members", "Remove 'parameters'"],
                         ctx="unsupported nested look-alikes explain the valid body shape")
    assert "localeUnusedInConfiguration" not in nested_lookalike.text, \
        "an unsupported nested presentation must not produce a locale warning"
    assert diff() == before, "a rejected nested look-alike member must not change the project"


@e2e_test(tool="dcs", kind="write-metadata")
def test_localized_title_rejects_duplicate_canonical_language_codes():
    report_name = "E2EDcsDuplicateLocale"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for duplicate-language validation")
    wait_for_project_ready()

    result = _write(root, "upsert", "parameter", {
        "name": "Period",
        "title": {"en": "Period", "EN": "Other"},
    })
    error = assert_error(result, "one canonical language named twice")
    assert_error_quality(error, names=["en"], suggests=["once"],
                         ctx="the duplicate-language error names the code and corrective action")
    assert "Other" not in diff(), "a rejected duplicate language must not reach disk"


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

    variant_node = _get(root + "#/variants/ManagerView", "variant", language="en")
    assert_ok(variant_node, "read the exact variant including its presentation containment")
    assert "Manager view" in variant_node.text, \
        "an exact variant read must render the user-visible presentation: %s" % variant_node.text
    assert root + "#/variants/ManagerView/presentation" in variant_node.text, \
        "the presentation containment must have its canonical address"
    variants = _get(root, "variant", language="en")
    assert_ok(variants, "page settings variants with their presentations")
    assert "Manager view" in variants.text, \
        "the variant collection page must expose each user-visible presentation: %s" % variants.text

    dcs_rel = _poll_report_dcs("E2EDcsSettings", ctx="the settings-variant write")
    poll_disk_contains(dcs_rel, "Manager view",
                       ctx="the variant presentation must reach Template.dcs")

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
    # the platform serializes an xs:decimal with its fraction, so the literal on disk is 99.0 -
    # asserting ">99<" can never match and says nothing about the write actually landing
    poll_disk_contains(dcs_rel, ">99.0<",
                       ctx="the indexed settings update must reach Template.dcs")


@e2e_test(tool="dcs", kind="write-metadata")
def test_user_fields_holder_replace_with_empty_body_clears_exported_items():
    report_name = "E2EDcsReplaceUserFields"
    root = _seed_report(report_name)
    marker = "E2EOldUserMargin"
    seeded = _write(root, "upsert", "schema", {
        "defaultSettings": {
            "userFields": {
                "items": [{
                    "kind": "expression",
                    "dataPath": marker,
                    "detailExpression": "Amount - Cost",
                    "title": {"en": "Old user margin"},
                }],
            },
        },
    }, language="en")
    assert_ok(seeded, "seed one default-settings user field")
    dcs_rel = _poll_report_dcs(report_name, ctx="the user-fields fixture")
    poll_disk_contains(dcs_rel, marker,
                       ctx="the old user field must exist on disk before replacement")

    before = _get(root + "#/defaultSettings/userFields", "userField", language="en")
    assert_ok(before, "read the holder hash before authoritative replacement")
    assert marker in before.text, "the fixture must expose the user field that should be lost"
    replaced = _write(root + "#/defaultSettings/userFields", "replace", "userField", {},
                      expectedHash=_hash(before), language="en")
    assert_ok(replaced, "replace the addressed userFields holder from an empty body")

    after = _get(root + "#/defaultSettings/userFields", "userField", language="en")
    assert_ok(after, "read back the replaced userFields holder")
    assert marker not in after.text, \
        "an authoritative holder replacement must not retain omitted items: %s" % after.text
    poll_disk_lacks(dcs_rel, marker, timeout=30,
                    ctx="the omitted user field must be removed from Template.dcs")
    assert marker not in read_disk(dcs_rel), \
        "the old user field must be absent from disk after export"


@e2e_test(tool="dcs", kind="write-metadata")
def test_settings_collection_address_copied_from_outline_renders_a_page():
    root = _seed_report("E2EDcsSettingsCollectionRead")
    authored = _write(root, "upsert", "variant", {
        "name": "Readable",
        "settings": {
            "selection": {
                "items": [{"field": {"kind": "field", "value": "Amount1"}}],
            },
        },
    })
    assert_ok(authored, "author a selection collection under variant settings")

    outline = _get(root + "#/variants/Readable/settings", "userSettings")
    assert_ok(outline, "read the settings outline that advertises collection addresses")
    collection_address = root + "#/variants/Readable/settings/selection/items"
    assert "`" + collection_address + "`" in outline.text, \
        "the settings outline must advertise the exact collection address: %s" % outline.text

    page = _get(collection_address, "selection")
    assert_ok(page, "read the collection address copied verbatim from the settings outline")
    assert "# DCS collection: selection" in page.text
    assert "**Address:** `" + collection_address + "`" in page.text
    assert "**Items:** 1" in page.text


@e2e_test(tool="dcs", kind="write-metadata")
def test_indexed_group_field_replace_resets_omitted_members_on_model_and_disk():
    report_name = "E2EDcsReplaceGroupField"
    root = _seed_report(report_name)
    old_field = "OldGroupField"
    new_field = "NewGroupField"
    seeded = _write(root, "upsert", "schema", {
        "defaultSettings": {
            "items": [{
                "name": "Group",
                "groupFields": {
                    "items": [{
                        "field": {"kind": "field", "value": old_field},
                        "use": False,
                        "groupType": "Items",
                        "periodAdditionType": "None",
                        "periodAdditionBegin": {"kind": "number", "value": 31337},
                        "periodAdditionEnd": {"kind": "number", "value": 31338},
                    }],
                },
            }],
        },
    })
    assert_ok(seeded, "seed a group field carrying non-default members")
    dcs_rel = _poll_report_dcs(report_name, ctx="the group-field replacement fixture")
    poll_disk_contains(dcs_rel, "31337",
                       ctx="the old period addition must reach Template.dcs before replacement")

    address = root + "#/defaultSettings/items/0/groupFields/items/0"
    before = _get(address, "grouping")
    assert_ok(before, "read the non-default group field before replacement")
    assert "| use | false |" in before.text
    assert "31337" in before.text

    replaced = _write(address, "replace", "grouping", {
        "field": {"kind": "field", "value": new_field},
    }, expectedHash=_hash(before))
    assert_ok(replaced, "replace the indexed group field with only its field member")

    after = _get(address, "grouping")
    assert_ok(after, "read the authoritative group-field replacement")
    assert "| use | true |" in after.text, \
        "the omitted use member must return to its model default: %s" % after.text
    assert "31337" not in after.text and "31338" not in after.text, \
        "omitted period additions must return to their defaults: %s" % after.text
    assert new_field in after.text and old_field not in after.text

    poll_disk_contains(dcs_rel, new_field,
                       ctx="the replacement group field must reach Template.dcs")
    poll_disk_lacks(dcs_rel, "31337",
                    ctx="the omitted period addition must leave Template.dcs")
    on_disk = read_disk(dcs_rel)
    assert new_field in on_disk and old_field not in on_disk
    assert "31337" not in on_disk and "31338" not in on_disk


@e2e_test(tool="dcs", kind="write-metadata")
def test_schema_summary_and_schema_collection_read_expose_data_set_links():
    report_name = "E2EDcsReadLinks"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for data-set-link reads")
    wait_for_project_ready()
    authored = _write(root, "upsert", "schema", {
        "dataSets": [
            {"name": "Source", "type": "query", "query": "SELECT 1 AS Key"},
            {"name": "Destination", "type": "query", "query": "SELECT 1 AS Key"},
        ],
        "parameters": [{"name": "LinkParameter"}],
        "dataSetLinks": [{
            "sourceDataSet": "Source",
            "destinationDataSet": "Destination",
            "sourceExpression": "Key",
            "destinationExpression": "Key",
            "parameter": "LinkParameter",
        }],
    })
    assert_ok(authored, "author two data sets and their link")
    dcs_rel = _poll_report_dcs(report_name, ctx="the readable data-set-link fixture")
    poll_disk_contains(dcs_rel, "Destination",
                       ctx="the linked data sets must reach Template.dcs")
    poll_disk_contains(dcs_rel, "LinkParameter",
                       ctx="the link parameter must reach Template.dcs")

    summary = _get(root, "schema")
    assert_ok(summary, "read the schema summary containing data-set links")
    assert "| Data set links | 1 | " + root + "#/dataSetLinks |" in summary.text
    assert root + "#/dataSetLinks/0" in summary.text
    assert "Source → Destination" in summary.text

    page = _get(root + "#/dataSetLinks", "schema")
    assert_ok(page, "page data-set links through the schema public type")
    assert "# DCS collection: schema" in page.text
    assert "**Items:** 1" in page.text
    assert root + "#/dataSetLinks/0" in page.text

    before_disk = read_disk(dcs_rel)
    parameter = _get(root + "#/parameters/LinkParameter", "parameter")
    assert_ok(parameter, "read the parameter retained by the data-set link")
    refused = call("dcs", {
        "projectName": PROJECT,
        "fqn": root + "#/parameters/LinkParameter",
        "action": "remove",
        "type": "parameter",
        "expectedHash": _hash(parameter),
    })
    error = assert_error(refused, "remove a parameter retained by a data-set link")
    assert_error_quality(error, names=["LinkParameter", root + "#/dataSetLinks/0"],
                         suggests=["referring nodes", "re-run get"],
                         ctx="a retained link must block parameter removal")
    assert read_disk(dcs_rel) == before_disk, \
        "a refused linked-parameter removal must leave Template.dcs byte-for-byte unchanged"


@e2e_test(tool="dcs", kind="write-metadata")
def test_identity_collection_replace_refuses_dangling_references_and_preserves_disk():
    report_name = "E2EDcsReplaceReferences"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for identity-reference replacement guards")
    wait_for_project_ready()
    authored = _write(root, "upsert", "schema", {
        "dataSources": [
            {"name": "RemovedSource", "type": "Local"},
            {"name": "RetainedSource", "type": "Local"},
        ],
        "dataSets": [
            {"name": "RemovedSet", "type": "query", "dataSource": "RemovedSource",
             "query": "SELECT 1 AS Key"},
            {"name": "RetainedSet", "type": "query", "dataSource": "RetainedSource",
             "query": "SELECT 2 AS Key"},
        ],
        "dataSetLinks": [{
            "sourceDataSet": "RemovedSet",
            "destinationDataSet": "RetainedSet",
            "sourceExpression": "Key",
            "destinationExpression": "Key",
        }],
    })
    assert_ok(authored, "seed data-set and data-source references")
    dcs_rel = _poll_report_dcs(report_name, ctx="the reference-guard fixture")
    poll_disk_contains(dcs_rel, "RemovedSet",
                       ctx="the referenced data set must reach Template.dcs")
    poll_disk_contains(dcs_rel, "RemovedSource",
                       ctx="the referenced data source must reach Template.dcs")
    before_disk = read_disk(dcs_rel)

    schema = _get(root, "schema")
    assert_ok(schema, "read the bare schema hash")
    refused_schema = _write(root, "replace", "schema", {
        "dataSources": [
            {"name": "RemovedSource", "type": "Local"},
            {"name": "RetainedSource", "type": "Local"},
        ],
        "dataSets": [
            {"name": "RemovedSet", "type": "query", "dataSource": "RemovedSource",
             "query": "SELECT 1 AS Key"},
        ],
        "dataSetLinks": [{
            "sourceDataSet": "RemovedSet",
            "destinationDataSet": "RetainedSet",
            "sourceExpression": "Key",
            "destinationExpression": "Key",
        }],
    }, expectedHash=_hash(schema))
    schema_error = assert_error(
        refused_schema, "replace the bare schema with a link whose destination was omitted")
    assert_error_quality(schema_error,
                         names=["destinationDataSet", "RetainedSet",
                                root + "#/dataSetLinks/0"],
                         suggests=["replacement body", "referring nodes"],
                         ctx="schema replacement names the assembled dangling reference")
    assert read_disk(dcs_rel) == before_disk, \
        "a refused bare schema replacement must leave Template.dcs byte-for-byte unchanged"

    data_sets = _get(root, "dataSet")
    assert_ok(data_sets, "read the data-set collection hash")
    refused_sets = _write(root + "#/dataSets", "replace", "dataSet", {
        "name": "RetainedSet", "type": "query", "dataSource": "RetainedSource",
        "query": "SELECT 2 AS Key",
    }, expectedHash=_hash(data_sets))
    set_error = assert_error(refused_sets, "replace collection while a retained link refers to omission")
    assert_error_quality(set_error, names=["RemovedSet", root + "#/dataSetLinks/0"],
                         suggests=["referring nodes", "replacement body"],
                         ctx="data-set replacement names the dangling link and remediation")
    assert read_disk(dcs_rel) == before_disk, \
        "a refused data-set collection replacement must leave Template.dcs byte-for-byte unchanged"

    data_sources = _get(root, "dataSource")
    assert_ok(data_sources, "read the data-source collection hash")
    refused_sources = _write(root + "#/dataSources", "replace", "dataSource", {
        "name": "RetainedSource", "type": "Local",
    }, expectedHash=_hash(data_sources))
    source_error = assert_error(
        refused_sources, "replace collection while a retained data set refers to omission")
    assert_error_quality(source_error,
                         names=["RemovedSource", root + "#/dataSets/RemovedSet"],
                         suggests=["referring nodes", "replacement body"],
                         ctx="data-source replacement names the dangling data set and remediation")
    assert read_disk(dcs_rel) == before_disk, \
        "a refused data-source collection replacement must leave Template.dcs byte-for-byte unchanged"

    model = _get(root, "schema")
    assert_ok(model, "read back the schema after both refused replacements")
    assert "RemovedSet" in model.text and "RemovedSource" in model.text, \
        "both referenced identities must remain in the model after refusal: %s" % model.text


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
    query_text = "SELECT Ref,\n    Description AS %s\nFROM %s" % (query_marker, catalog)
    configured = _write(root, "upsert", "dynamicList", {
        "queryText": query_text,
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
    query_address = root + "#/queryText"
    copied_query = re.search(re.escape(query_address), read_back.text)
    assert copied_query, \
        "the query-text count row must advertise its exact drill-down address: %s" % read_back.text
    query_page = _get(copied_query.group(0), "dynamicList", limit=1000)
    assert_ok(query_page, "read queryText through the address advertised by the summary")
    opening = re.search(r"(?m)^(`{3,})sql\n", query_page.text)
    assert opening, "the scalar page must carry one fenced exact-value block: %s" % query_page.text
    value_start = opening.end()
    read_query = query_page.text[value_start:value_start + len(query_text)]
    value_end = value_start + len(query_text)
    closing = opening.group(1) if query_text.endswith("\n") else "\n" + opening.group(1)
    assert query_page.text.startswith(closing, value_end), \
        "the scalar value fence must close after the advertised page characters: %s" % query_page.text
    assert read_query.encode("utf-8") == query_text.encode("utf-8"), \
        "queryText read through the advertised address must be byte-identical"
    settings = _get(root + "#/listSettings", "userSettings")
    assert_ok(settings, "read back external dynamic-list settings")
    assert root + "#/listSettings/selection" in settings.text
    assert root + "#/listSettings/filter" in settings.text
    assert root + "#/listSettings/order" in settings.text
    assert root + "#/listSettings/conditionalAppearance" in settings.text


@e2e_test(tool="dcs", kind="write")
def test_dynamic_list_settings_accept_replace_and_remove_but_its_own_types_do_not():
    """replace/remove reach a dynamic list's SETTINGS; the list's own types refuse them.

    The tool guide advertised replace and remove for dynamic lists while the planner refused
    every action except upsert/update - a promise the tool did not keep, and nothing caught it
    because no test addressed a dynamic list with either action. The settings layer is shared
    with report variants and already implements both, so they belong below '#/listSettings'.
    The list's OWN types keep upsert/update: accepting replace there would be an update
    wearing the wrong label.
    """
    catalog_name = "E2EDcsListReplace"
    catalog = "Catalog." + catalog_name
    form = catalog + ".Form.ListForm"
    root = form + ".Attribute.List"
    for fqn, why in ((catalog, "catalog"), (form, "form"), (root, "attribute")):
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}),
                  "seed the %s" % why)
        wait_for_project_ready()

    seeded = _write(root, "upsert", "dynamicList", {
        "queryText": "SELECT Ref FROM " + catalog,
        "customQuery": True,
        "mainTable": catalog,
        "fields": [{"dataPath": "Ref"}],
        "listSettings": {
            "selection": {
                "items": [{"kind": "field", "field": {"kind": "field", "value": "Ref"},
                           "title": {"EN": "Reference"}}],
            },
        },
    }, language="en")
    assert_ok(seeded, "seed a dynamic list carrying one titled selection item")

    # A titled item is the thing an authoritative replace must NOT preserve.
    before = _get(root + "#/listSettings/selection", "selection")
    assert_ok(before, "read the seeded selection")
    assert "Reference" in before.text, "the fixture must start with a title to lose"

    replaced = _write(root + "#/listSettings/selection", "replace", "selection",
                      {"items": []}, expectedHash=_hash(before), language="en")
    assert_ok(replaced, "replace a dynamic list's selection through the shared settings layer")

    after = _get(root + "#/listSettings/selection", "selection")
    assert_ok(after, "read the replaced selection")
    assert "Reference" not in after.text,         "an authoritative replace must clear the item it never mentioned"

    # ON DISK, not just in the read-back: a settings write that only ever lived in memory would
    # satisfy every assertion above and still be lost on the next refresh. A dynamic list's
    # settings are exported to their OWN file, so that is the one to read.
    assert "**ListSettings.dcss export scheduled:** `true`" in replaced.text,         "a settings replace must schedule the external file's export: %s" % replaced.text[:400]
    settings_rel = ("src/Catalogs/%s/Forms/ListForm/Attributes/List/ExtInfo/ListSettings.dcss"
                    % catalog_name)
    # Wait for the file to EXIST before asserting what is not in it. An absence check on a missing
    # file passes for the wrong reason - poll_disk_lacks is satisfied immediately by a file that was
    # never written, which is how this assertion passed locally and failed on CI.
    poll_disk_contains(settings_rel, "selection",
                       ctx="the replaced settings must reach ListSettings.dcss")
    on_disk = read_disk(settings_rel)
    assert "Reference" not in on_disk,         "the title the replace never mentioned must not survive on disk: %s" % on_disk[:600]

    # The list's own types are a different contract, and the refusal must say where to go.
    refused = _write(root, "replace", "dynamicList", {"queryText": "SELECT 1"},
                     expectedHash=_hash(_get(root, "dynamicList")), language="en")
    error = assert_error(refused, "replace on a dynamic list's own type")
    assert_error_quality(error, names=["#/listSettings"],
                         ctx="the refusal must point at the settings layer that does accept it")


@e2e_test(tool="dcs", kind="read")
def test_unknown_action_is_a_clean_non_mutating_error():
    result = call("dcs", {
        "projectName": PROJECT,
        "fqn": "Report.DoesNotNeedToExist",
        "action": "merge",
        "type": "dataSet",
        "body": {"name": "DataSet1"},
        "expectedHash": "00000000000000000000",
    })
    error = assert_error(result, "unknown action")
    assert_error_quality(error, names=["merge"], suggests=["get", "upsert"],
                         ctx="unknown actions name the supported alternatives")
    assert_no_diff("a rejected unknown action must not change the project")
