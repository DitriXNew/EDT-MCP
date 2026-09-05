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
_DCS_DEFAULT_CHARACTER_LIMIT = 40_000
_DCS_MAX_CHARACTER_REQUEST = 100_000

_VERTICAL_OVERALL_PLACEMENT_RU = (
    "\u0412\u0435\u0440\u0442\u0438\u043a\u0430\u043b\u044c\u043d\u043e\u0435"
    "\u0420\u0430\u0441\u043f\u043e\u043b\u043e\u0436\u0435\u043d\u0438\u0435"
    "\u041e\u0431\u0449\u0438\u0445\u0418\u0442\u043e\u0433\u043e\u0432"
)
_TITLE_RU = "\u0417\u0430\u0433\u043e\u043b\u043e\u0432\u043e\u043a"


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


def _get(fqn, target_type, *, limit=None, **extra):
    """Read through the public contract; ``None`` always means omit ``limit``."""
    args = {
        "projectName": PROJECT,
        "fqn": fqn,
        "action": "get",
        "type": target_type,
    }
    args.update(extra)
    if limit is None:
        # Keep an explicit no-limit intent authoritative even if a blanket key is added above.
        args.pop("limit", None)
    else:
        args["limit"] = limit
    return call("dcs", args)


def _options(fqn, target_type, *, limit=None, **extra):
    """Read the platform-version vocabulary through the public contract."""
    args = {
        "projectName": PROJECT,
        "fqn": fqn,
        "action": "options",
        "type": target_type,
    }
    args.update(extra)
    if limit is not None:
        args["limit"] = limit
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
        # Pass None explicitly: _get guarantees that this means the tool's real XML default.
        result = _get(fqn, "schema", format="xml", limit=limit, offset=offset)
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


def _read_all_fenced_scalar(fqn, target_type, *, limit):
    """Read and concatenate every character page from one advertised scalar address."""
    offset = 0
    chunks = []
    while True:
        page = _get(fqn, target_type, limit=limit, offset=offset)
        assert_ok(page, "read scalar page at offset %d" % offset)
        count = re.search(r"\*\*Page characters:\*\* (\d+)", page.text)
        opening = re.search(r"(?m)^(`{3,})sql\n", page.text)
        assert count and opening, "scalar page must carry its exact fenced value: %s" % page.text
        page_chars = int(count.group(1))
        start = opening.end()
        chunk = page.text[start:start + page_chars]
        closing = opening.group(1) if chunk.endswith("\n") else "\n" + opening.group(1)
        assert page.text.startswith(closing, start + page_chars), \
            "the scalar fence must close exactly after Page characters: %s" % page.text
        chunks.append(chunk)

        next_offset = re.search(r"\*\*Next offset:\*\* (none|\d+)", page.text)
        assert next_offset, "scalar page must advertise its continuation offset: %s" % page.text
        if next_offset.group(1) == "none":
            return "".join(chunks)
        effective_limit = _DCS_DEFAULT_CHARACTER_LIMIT if limit is None \
            else min(max(1, limit), _DCS_MAX_CHARACTER_REQUEST)
        assert page_chars >= effective_limit // 2, \
            "a non-final scalar page must use the character budget: %s" % page.text[:500]
        following = int(next_offset.group(1))
        assert following > offset, "a non-final scalar page must make progress"
        offset = following


def _read_all_text_value(fqn, target_type, *, limit):
    """Read and concatenate every unfenced UTF-16 character page."""
    offset = 0
    chunks = []
    pages = []
    while True:
        page = _get(fqn, target_type, limit=limit, offset=offset)
        assert_ok(page, "read composite page at offset %d" % offset)
        assert _OUTPUT_GUARD_NOTICE not in page.text, \
            "a DCS page must fit before the outer guard sees it: %s" % page.text[-500:]
        count = re.search(r"\*\*Page characters:\*\* (\d+)", page.text)
        heading = "## Value\n\n"
        start = page.text.find(heading)
        assert count and start >= 0, "composite page must expose its exact value slice: %s" % page.text
        page_chars = int(count.group(1))
        start += len(heading)
        encoded = page.text[start:].encode("utf-16-le")
        chunk = encoded[:page_chars * 2].decode("utf-16-le")
        chunks.append(chunk)
        pages.append(page.text)

        next_offset = re.search(r"\*\*Next offset:\*\* (none|\d+)", page.text)
        assert next_offset, "composite page must advertise its continuation offset: %s" % page.text
        if next_offset.group(1) == "none":
            return "".join(chunks), pages
        effective_limit = _DCS_DEFAULT_CHARACTER_LIMIT if limit is None \
            else min(max(1, limit), _DCS_MAX_CHARACTER_REQUEST)
        assert page_chars >= effective_limit // 2, \
            "a non-final composite page must use the character budget: %s" % page.text[:500]
        following = int(next_offset.group(1))
        assert following > offset, "a non-final composite page must make progress"
        offset = following


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
                    "appearance": {
                        "BackColor": {
                            "color": {"red": 255, "green": 0, "blue": 0},
                        },
                    },
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

    before_lossy_replace = read_disk(target_rel)
    current = _get(target_root, "schema")
    assert_ok(current, "read the target hash before the lossy-type replacement")
    undeclared_style_xml = re.sub(
        r'\s+xmlns:style="[^"]+"', "", source_xml, count=1)
    lossy_xml, replacements = re.subn(
        r'xsi:type="v8ui:Color"', 'xsi:type="style:StyleColor"',
        undeclared_style_xml, count=1)
    assert replacements == 1, \
        "the serialized fixture must expose one typed color value for the loss reproduction"
    refused = _write(target_root, "replace", "schema", {"xml": lossy_xml},
                     expectedHash=_hash(current))
    refusal = assert_error(refused, "replace schema XML with an undeclared-prefix color type")
    assert_error_quality(
        refusal,
        names=["value"],
        suggests=["round trip", "nothing was written"],
        ctx="the deserialize/serialize loss refusal names the first missing value path")
    assert read_disk(target_rel) == before_lossy_replace, \
        "a lossy wholesale replacement must roll back and leave Template.dcs byte-identical"

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

    # One item keeps Second on a middle page; the default 100 would also consume Third.
    page = _get(root, "dataSet", limit=1, offset=1)
    assert_ok(page, "page report data sets")
    assert "showing 1 of 3" in page.text
    assert "**Next offset:** 2" in page.text
    assert root + "#/dataSets/Second" in page.text
    assert root + "#/dataSets/First" not in page.text

    drill = _get(root + "#/dataSets/Second", "dataSet")
    assert_ok(drill, "drill into one query data set")
    exact_address = "**Address:** `%s#/dataSets/Second`" % root
    assert drill.text.count(exact_address) == 1, \
        "the exact-node envelope must print its address once: %s" % drill.text
    assert "# DCS node: DataCompositionSchemaDataSetQuery" in drill.text, \
        "removing the duplicate address must retain the concrete EClass: %s" % drill.text
    query_address = root + "#/dataSets/Second/query"
    assert query_address in drill.text
    assert "SELECT 2 AS Amount" not in drill.text
    assert _read_all_fenced_scalar(
        query_address, "dataSet", limit=None) == "SELECT 2 AS Amount"
    assert root + "#/dataSets/Second/fields/Amount2" in drill.text
    _assert_read_did_not_change(before, "report summary/pagination/drill-down")


@e2e_test(tool="dcs", kind="write-metadata")
def test_cut_report_summary_section_names_its_local_continuation_offsets():
    root = _seed_report("E2EDcsSummarySectionMarkers")
    authored = _write(root, "upsert", "schema", {
        "variants": [{
            "name": "Variant%d" % index,
            "presentation": "Paged variant %d" % index,
        } for index in range(8)],
    })
    assert_ok(authored, "seed eight variants for a section-local paging signal")

    first = _get(root, "schema", limit=3, offset=0)
    assert_ok(first, "read the first partial Variants section")
    assert "| Variants | 8 |" in first.text, \
        "the unpaged count must continue to describe the whole section"
    assert "_(section continues at offset 3)_" in first.text, \
        "the cut section itself must name the continuation offset: %s" % first.text

    middle = _get(root, "schema", limit=3, offset=3)
    assert_ok(middle, "read a middle page of the Variants section")
    assert "_(continued from an earlier page)_" in middle.text, \
        "a section resumed mid-table must identify itself as continued: %s" % middle.text
    assert "_(section continues at offset 6)_" in middle.text, \
        "a middle section page must also name its next local offset: %s" % middle.text


@e2e_test(tool="dcs", kind="write-metadata")
def test_data_set_query_pages_reassemble_the_exact_long_text():
    report_name = "E2EDcsPagedDataSetQuery"
    root = _seed_report(report_name)
    data_set_address = root + "#/dataSets/DataSet1"
    query = "\nUNION ALL\n".join(
        "SELECT %d AS E2EDcsPagedQuery%03d" % (index, index)
        for index in range(180)
    )
    before = _get(data_set_address, "dataSet")
    assert_ok(before, "read the data-set hash before writing its long query")
    updated = _write(data_set_address, "update", "dataSet", {"query": query},
                     expectedHash=_hash(before))
    assert_ok(updated, "write a data-set query long enough to require character paging")
    dcs_rel = _poll_report_dcs(report_name, ctx="the paged data-set query fixture")
    poll_disk_contains(dcs_rel, "E2EDcsPagedQuery179",
                       ctx="the complete long query must reach Template.dcs")

    data_set = _get(data_set_address, "dataSet")
    assert_ok(data_set, "read the data-set page advertising its query scalar")
    query_address = data_set_address + "/query"
    copied = re.search(re.escape(query_address), data_set.text)
    assert copied, "the data-set page must advertise its exact query address: %s" % data_set.text
    # 257 forces multiple chunks; the 40,000-character default would return this query whole.
    reconstructed = _read_all_fenced_scalar(copied.group(0), "dataSet", limit=257)
    assert reconstructed.encode("utf-8") == query.encode("utf-8"), \
        "concatenated query pages must reproduce the authored query byte-for-byte"


@e2e_test(tool="dcs", kind="write-metadata")
def test_large_variant_settings_exact_read_is_complete_and_has_no_duplicate_nodes():
    report_name = "E2EDcsPagedVariantSettings"
    root = _seed_report(report_name)
    variant_name = "ErpShape"
    item_count = 250
    items = [{
        "kind": "field",
        "field": {
            "kind": "field",
            "value": "E2EDcsLargeSettings%03d.%s" % (
                index,
                ".".join("Segment%02d" % part for part in range(18)),
            ),
        },
    } for index in range(item_count)]
    authored = _write(root, "upsert", "schema", {
        "variants": [{
            "name": variant_name,
            "presentation": "ERP-shaped settings",
            "settings": {"selection": {"items": items}},
        }],
    })
    assert_ok(authored, "author an ERP-shaped settings outline larger than the output guard")

    address = root + "#/variants/" + variant_name + "/settings"
    outline, pages = _read_all_text_value(address, "userSettings", limit=None)
    assert len(outline) > 100_000, \
        "the fixture must exceed the production content guard before paging"
    assert len(pages) > 1, "the oversized exact settings node must advertise continuation"
    for index in range(item_count):
        item_address = address + "/selection/items/%d" % index
        line = "DataCompositionSelectedField — `%s`" % item_address
        assert outline.count(line) == 1, \
            "every settings node must appear exactly once across all pages: %s" % item_address
    assert pages[-1].find("**Next offset:** none") >= 0, \
        "the final page must distinguish completion from a size stop"


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

    page = _get(root, "field")
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
def test_union_member_data_set_address_updates_query_on_disk():
    report_name = "E2EDcsUnionMember"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for recursive union-member data-set writes")
    wait_for_project_ready()
    old_marker = "OriginalUnionMemberQuery"
    authored = _write(root, "upsert", "schema", {
        "dataSets": [{
            "name": "AllSales",
            "type": "union",
            "items": [{
                "name": "Retail",
                "type": "query",
                "query": "SELECT 1 AS " + old_marker,
            }],
        }],
    })
    assert_ok(authored, "author a union with a query member")
    dcs_rel = _poll_report_dcs(report_name, ctx="the union-member data-set fixture")
    poll_disk_contains(dcs_rel, old_marker,
                       ctx="the original union-member query must reach Template.dcs")

    union_page = _get(root + "#/dataSets/AllSales/items", "dataSet")
    assert_ok(union_page, "read the parent union's member collection")
    expected = root + "#/dataSets/AllSales/items/Retail"
    copied = re.search(re.escape(expected), union_page.text)
    assert copied, "the union page must print its member's canonical address: %s" % union_page.text

    member = _get(copied.group(0), "dataSet")
    assert_ok(member, "read the union member through its advertised address")
    new_marker = "EditedUnionMemberQuery"
    edited = _write(copied.group(0), "update", "dataSet",
                    {"query": "SELECT 2 AS " + new_marker},
                    expectedHash=_hash(member))
    assert_ok(edited, "update a union member through the copied address")
    poll_disk_contains(dcs_rel, new_marker,
                       ctx="the recursive data-set edit must reach Template.dcs")

    on_disk = read_disk(dcs_rel)
    assert new_marker in on_disk and old_marker not in on_disk, \
        "Template.dcs must contain only the updated union-member query"


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
    query_address = root + "#/dataSets/Sales/query"
    assert query_address in drill.text
    assert _read_all_fenced_scalar(query_address, "dataSet", limit=None) == second_query
    assert first_query not in drill.text

    data_sets = _get(root + "#/dataSets", "dataSet")
    assert_ok(data_sets, "read the parent data-set collection after re-authoring Sales")
    sales_rows = [
        line for line in data_sets.text.splitlines()
        if line.startswith("| Sales | ")
    ]
    assert len(sales_rows) == 1, \
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
def test_schema_member_bodies_persist_typed_default_field_type_appearance_and_attribute_restriction():
    report_name = "E2EDcsSchemaMembers"
    root = _seed_report(report_name)
    parameter = "TypedDefaultDate"
    field_address = root + "#/dataSets/DataSet1/fields/Amount1"

    authored_parameter = _write(root, "upsert", "parameter", {
        "name": parameter,
        "valueType": {"types": [{"kind": "Date", "fractions": "Date"}]},
        "values": [{"kind": "date", "value": "2026-08-24T00:00:00"}],
    })
    assert_ok(authored_parameter, "author a schema parameter carrying a typed default value")

    authored_field = _write(field_address, "update", "field", {
        "valueType": {"types": [{"kind": "String", "length": 40}]},
        "appearance": {
            "BackColor": {
                "color": {"red": 17, "green": 34, "blue": 51},
            },
            "TextColor": {
                "color": {"red": 238, "green": 221, "blue": 204},
            },
        },
        "attributeUseRestriction": {
            "field": True,
            "condition": False,
            "group": True,
            "order": False,
        },
    })
    assert_ok(authored_field, "author field valueType, appearance, and attribute restriction")

    patched_field = _write(field_address, "update", "field", {
        "appearance": {
            "BackColor": {
                "color": {"red": 51, "green": 34, "blue": 17},
            },
        },
    })
    assert_ok(patched_field, "patch one field appearance key without dropping its sibling")

    dcs_rel = _poll_report_dcs(report_name, ctx="the extended schema-member write")
    for needle, why in (
            (parameter, "the parameter default"),
            ("2026-08-24", "the parameter default value"),
            ("BackColor", "the field appearance"),
            ("TextColor", "the omitted field appearance key retained by merge-on-update"),
            ("valueType", "the field String value type"),
            ("attributeUseRestriction", "the field attribute-use restriction")):
        poll_disk_contains(dcs_rel, needle, ctx=why + " must reach Template.dcs")

    on_disk = read_disk(dcs_rel)
    parameter_start = on_disk.index(parameter)
    parameter_window = on_disk[parameter_start:parameter_start + 3000]
    assert re.search(r'<(?:\w+:)?values?\s+xsi:type="[^"]*[Dd]ate[^"]*"',
                     parameter_window), \
        "the default below %s must carry a Date XML type, not an inferred string: %s" % (
            parameter, parameter_window[:800])
    field_start = on_disk.index("Amount1")
    field_window = on_disk[field_start:field_start + 6000]
    assert "BackColor" in field_window and "TextColor" in field_window \
        and "valueType" in field_window \
        and "String" in field_window and "40" in field_window, \
        "the field appearance and value type must stay on the same field: %s" % field_window[:1200]
    assert "attributeUseRestriction" in field_window, \
        "the field-level attribute-use restriction must survive export: %s" % field_window[:1200]


@e2e_test(tool="dcs", kind="write-metadata")
def test_number_value_type_qualifiers_round_trip_through_typed_calls():
    report_name = "E2EDcsNumberQualifiers"
    root = "Report." + report_name
    parameter = "QualifiedAmount"
    precision = 17
    scale = 5
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for Number value-type qualifiers")
    wait_for_project_ready()

    authored = _write(root, "upsert", "parameter", {
        "name": parameter,
        "valueType": {"types": [{
            "kind": "Number",
            "precision": precision,
            "scale": scale,
        }]},
    })
    assert_ok(authored, "author Number precision/scale through the typed DCS body")

    dcs_rel = _poll_report_dcs(report_name, ctx="the qualified Number parameter write")
    poll_disk_contains(dcs_rel, parameter,
                       ctx="the qualified parameter must reach Template.dcs")
    on_disk = read_disk(dcs_rel)
    assert "Digits>%d</" % precision in on_disk, \
        "Number precision must persist as the 1C XML Digits qualifier in %s" % dcs_rel
    assert "FractionDigits>%d</" % scale in on_disk, \
        "Number scale must persist as the 1C XML FractionDigits qualifier in %s" % dcs_rel

    read_back = _get(root + "#/parameters/" + parameter, "parameter")
    assert_ok(read_back, "read back the qualified Number parameter through dcs")
    assert "NumberQualifiers" in read_back.text, \
        "the typed read must expose the Number qualifier object:\n%s" % read_back.text
    assert "- precision: %d" % precision in read_back.text, \
        "the typed read must expose the written precision:\n%s" % read_back.text
    assert "- scale: %d" % scale in read_back.text, \
        "the typed read must expose the written scale:\n%s" % read_back.text


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
def test_calculated_field_empty_expression_survives_write_export_and_read():
    report_name = "E2EDcsEmptyCalculatedExpression"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for empty calculated-field expression")
    wait_for_project_ready()

    data_path = "RuntimeFilledValue"
    written = _write(root, "upsert", "calculatedField", {
        "dataPath": data_path,
        "expression": "",
    })
    assert_ok(written, "author a deliberately empty calculated-field expression")

    dcs_rel = _poll_report_dcs(report_name, ctx="the empty calculated-field write")
    poll_disk_contains(dcs_rel, data_path,
                       ctx="the empty-expression field must reach Template.dcs")
    on_disk = read_disk(dcs_rel)
    assert re.search(r"<expression\s*/>", on_disk), \
        "the deliberate empty expression must serialize as an empty XML element in %s" % dcs_rel

    read_back = _get(root + "#/calculatedFields/" + data_path, "calculatedField")
    assert_ok(read_back, "read back the calculated field with an empty expression")
    assert "- expression: \n" in read_back.text, \
        "the typed read must expose the deliberately empty expression:\n%s" % read_back.text


@e2e_test(tool="dcs", kind="write-metadata")
def test_small_settings_members_survive_write_export_and_typed_read():
    report_name = "E2EDcsSmallSettingsMembers"
    root = _seed_report(report_name)
    authored = _write(root, "upsert", "userSettings", {
        "items": [
            {"kind": "grouping", "name": "SmallGroup", "id": "AgentGroupId",
             "groupState": "Disabled"},
            {"kind": "table", "name": "SmallTable", "id": "AgentTableId"},
        ],
        "additionalProperties": {
            "AgentMarker": {"kind": "string", "value": "SmallSettingsRoundTrip"},
        },
    })
    assert_ok(authored, "author settable group/table/settings members")

    dcs_rel = _poll_report_dcs(report_name, ctx="the small settings members")
    for marker in ("AgentGroupId", "AgentTableId", "SmallSettingsRoundTrip"):
        poll_disk_contains(dcs_rel, marker,
                           ctx="%s must reach Template.dcs" % marker)

    read_back = _get(root + "#/defaultSettings", "userSettings")
    assert_ok(read_back, "read back all small settings members")
    for marker in ("AgentGroupId", "AgentTableId", "Disabled",
                   "AgentMarker", "SmallSettingsRoundTrip"):
        assert marker in read_back.text, \
            "the typed read must expose %s:\n%s" % (marker, read_back.text)


@e2e_test(tool="dcs", kind="write-metadata")
def test_grouping_conditional_appearance_address_reaches_template_dcs():
    report_name = "E2EDcsGroupingAppearance"
    root = _seed_report(report_name)
    authored = _write(root, "upsert", "grouping", {"name": "AppearanceGroup"})
    assert_ok(authored, "seed a grouping for conditional appearance")

    grouping_address = root + "#/defaultSettings/items/0"
    grouping = _get(grouping_address, "grouping")
    assert_ok(grouping, "read the grouping before its indexed child write")
    appearance_address = grouping_address + "/conditionalAppearance"
    marker = "GroupingAppearanceField"
    written = _write(appearance_address, "upsert", "conditionalAppearance", {
        "items": [{
            "selection": {"items": [{
                "use": True,
                "field": {"kind": "field", "value": marker},
            }]},
        }],
    }, expectedHash=_hash(grouping))
    assert_ok(written, "author grouping-level conditional appearance by its child address")

    dcs_rel = _poll_report_dcs(report_name, ctx="the grouping appearance fixture")
    poll_disk_contains(dcs_rel, marker,
                       ctx="grouping conditional appearance must reach Template.dcs")
    read_back = _get(appearance_address, "conditionalAppearance")
    assert_ok(read_back, "read grouping conditional appearance through the accepted address")
    assert marker in read_back.text
    assert appearance_address + "/items/0" in read_back.text, \
        "the read must expose writable addresses below the grouping holder"


@e2e_test(tool="dcs", kind="write-metadata")
def test_form_conditional_appearance_reaches_form_file_and_reads_back():
    catalog_name = "E2EDcsFormAppearance"
    catalog = "Catalog." + catalog_name
    form = catalog + ".Form.ItemForm"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": catalog}),
              "seed catalog for form conditional appearance")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": form}),
              "seed managed form for conditional appearance")
    wait_for_project_ready()

    marker = "AcceptedUnvalidatedFormField"
    written = _write(form, "upsert", "conditionalAppearance", {
        "items": [{
            "selection": {"items": [{
                "use": True,
                "field": {"kind": "field", "value": marker},
            }]},
            "appearance": {
                "Visible": {"kind": "boolean", "value": False},
            },
        }],
    })
    assert_ok(written, "author the form's own conditional appearance")
    assert "**Form.form export scheduled:** `true`" in written.text

    # A form owns its appearance as a BM EXTERNAL PROPERTY, so EDT exports it beside the form
    # rather than inside it - the same way a dynamic list gets its own ListSettings.dcss.
    appearance_rel = "src/Catalogs/%s/Forms/ItemForm/ConditionalAppearance.dcssca" % catalog_name
    poll_disk_contains(appearance_rel, marker,
                       ctx="form conditional appearance must reach ConditionalAppearance.dcssca")
    appearance_disk = read_disk(appearance_rel)
    assert "Visible" in appearance_disk, \
        "the form-only appearance key must persist through FormAppearanceParameters"

    read_back = _get(form, "conditionalAppearance")
    assert_ok(read_back, "read back the form conditional appearance")
    assert marker in read_back.text and "Visible" in read_back.text
    assert form + "#/items/0" in read_back.text, \
        "a form read must expose the same root-relative rule address accepted by write"


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
    # _poll_report_dcs only waits for the FILE, and seeding the report already created it,
    # so it can release before this write's export lands. Wait for the write's own mark.
    poll_disk_contains(dcs_rel, ">en<",
                       ctx="the canonicalized language key must reach Template.dcs")
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
def test_variant_create_without_presentation_is_refused_actionably():
    root = _seed_report("E2EDcsVariantPresentationRequired")
    before = _get(root, "schema")
    assert_ok(before, "capture the schema before rejecting an incomplete variant")
    before_diff = diff()

    refused = _write(root, "upsert", "variant", {"name": "MissingPresentation"})
    error = assert_error(refused, "create a variant without presentation")
    assert_error_quality(
        error,
        names=["presentation"],
        suggests=["string", "languageCode"],
        ctx="the refusal must name presentation and both accepted shapes")

    after = _get(root, "schema")
    assert_ok(after, "read the schema after the rejected variant")
    assert after.text == before.text, "a rejected variant must not reach the EDT model"
    assert diff() == before_diff, "a rejected variant must not change the exported DCS"


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
    structure_address = root + "#/variants/ManagerView/settings/items"
    refused_settings_root = _get(root + "#/variants/ManagerView/settings", "grouping")
    settings_root_error = assert_error(
        refused_settings_root, "read the settings root as grouping")
    assert_error_quality(
        settings_root_error,
        names=[structure_address, "type='userSettings'", structure_address + "/<index>"],
        suggests=["For a write", "describes the body"],
        ctx="the settings-root refusal must lead directly to the readable structure addresses")
    refused_structure = _get(structure_address, "grouping")
    structure_error = assert_error(
        refused_structure, "read the polymorphic structure collection as grouping")
    assert_error_quality(
        structure_error,
        names=["type='userSettings'", structure_address + "/<index>"],
        suggests=["For a write", "describes the body"],
        ctx="the refusal must explain the deliberate read/write type asymmetry")
    structure = _get(structure_address, "userSettings")
    assert_ok(structure, "read the variant structure collection by its actual public type")
    first_structure = _get(structure_address + "/0", "grouping")
    assert_ok(first_structure, "read one structure item by its own concrete type")
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
def test_settings_write_refuses_type_mismatch_without_touching_disk():
    report_name = "E2EDcsSettingsTypeGuard"
    root = _seed_report(report_name)
    operand_marker = "L1GuardOperand"
    authored = _write(root, "upsert", "schema", {
        "defaultSettings": {
            "filter": {
                "items": [{
                    "left": {"kind": "field", "value": operand_marker},
                    "comparisonType": "Greater",
                    "right": [{"kind": "number", "value": 10}],
                    "use": True,
                }],
            },
        },
    })
    assert_ok(authored, "author a default-settings filter item")
    dcs_rel = _poll_report_dcs(report_name, ctx="the settings type-guard fixture")
    poll_disk_contains(dcs_rel, operand_marker,
                       ctx="the filter operand must reach Template.dcs before the refusal")

    address = root + "#/defaultSettings/filter/items/0"
    outline = _get(root + "#/defaultSettings", "userSettings")
    assert_ok(outline, "read the settings outline containing the filter-item address")
    assert "`" + address + "`" in outline.text, \
        "the refused address must be copied from the settings read: %s" % outline.text
    before = _get(address, "filter")
    assert_ok(before, "read the filter item before the mismatched write")
    before_disk = read_disk(dcs_rel)

    refused = _write(address, "replace", "selection", {"use": False},
                     expectedHash=_hash(before))
    error = assert_error(refused, "replace a filter item while declaring selection")
    assert "type='selection'" in error and "type='filter'" in error, \
        "the refusal must name both the declared and resolved types: %s" % error
    assert read_disk(dcs_rel) == before_disk, \
        "a refused settings type mismatch must leave Template.dcs byte-for-byte unchanged"


@e2e_test(tool="dcs", kind="write-metadata")
def test_conditional_appearance_field_address_copied_from_read_updates_disk():
    report_name = "E2EDcsAppearanceFieldAddress"
    root = _seed_report(report_name)
    old_field = "OldAppearanceField"
    new_field = "NewAppearanceField"
    authored = _write(root, "upsert", "schema", {
        "defaultSettings": {
            "conditionalAppearance": {
                "items": [{
                    "selection": {
                        "items": [{
                            "use": True,
                            "field": {"kind": "field", "value": old_field},
                        }],
                    },
                }],
            },
        },
    })
    assert_ok(authored, "author a conditional-appearance selection field")
    dcs_rel = _poll_report_dcs(report_name, ctx="the appearance-field address fixture")
    poll_disk_contains(dcs_rel, old_field,
                       ctx="the original appearance field must reach Template.dcs")

    holder = _get(root + "#/defaultSettings/conditionalAppearance",
                  "conditionalAppearance")
    assert_ok(holder, "read the appearance holder advertising its selection-field address")
    expected_address = (root + "#/defaultSettings/conditionalAppearance/items/0/"
                        "selection/items/0")
    copied = re.search(re.escape(expected_address), holder.text)
    assert copied, "the appearance read must print the exact field address: %s" % holder.text

    before = _get(copied.group(0), "conditionalAppearance")
    assert_ok(before, "read the appearance field through the copied address")
    updated = _write(copied.group(0), "update", "conditionalAppearance", {
        "field": {"kind": "field", "value": new_field},
    }, expectedHash=_hash(before))
    assert_ok(updated, "update the appearance field through its copied address")

    after = _get(copied.group(0), "conditionalAppearance")
    assert_ok(after, "read back the updated appearance field")
    assert new_field in after.text and old_field not in after.text
    poll_disk_contains(dcs_rel, new_field,
                       ctx="the appearance-field update must reach Template.dcs")
    poll_disk_lacks(dcs_rel, old_field,
                    ctx="the old appearance field must leave Template.dcs")
    on_disk = read_disk(dcs_rel)
    assert new_field in on_disk and old_field not in on_disk

    selection_address = (root + "#/defaultSettings/conditionalAppearance/items/0/"
                         "selection")
    selection_before = _get(selection_address, "conditionalAppearance")
    assert_ok(selection_before, "read the appearance selection holder before replacing it")
    cleared = _write(selection_address, "replace", "conditionalAppearance", {},
                     expectedHash=_hash(selection_before))
    assert_ok(cleared, "replace the exact appearance selection holder with an empty body")
    selection_after = _get(selection_address, "conditionalAppearance")
    assert_ok(selection_after, "read back the cleared appearance selection holder")
    assert new_field not in selection_after.text and old_field not in selection_after.text, \
        "an authoritative holder replace must not retain omitted appearance fields"
    poll_disk_lacks(dcs_rel, new_field,
                    ctx="the omitted appearance field must leave Template.dcs on holder replace")


@e2e_test(tool="dcs", kind="write-metadata")
def test_conditional_appearance_parameter_patch_preserves_omitted_keys_on_disk():
    report_name = "E2EDcsAppearanceParameterPatch"
    root = _seed_report(report_name)
    rule_address = root + "#/defaultSettings/conditionalAppearance/items/0"
    authored = _write(root, "upsert", "schema", {
        "defaultSettings": {
            "conditionalAppearance": {
                "items": [{
                    "appearance": {
                        "BackColor": {
                            "color": {"red": 255, "green": 0, "blue": 0},
                        },
                        "TextColor": {
                            "use": False,
                            "color": {"red": 0, "green": 128, "blue": 0},
                        },
                    },
                }],
            },
        },
    })
    assert_ok(authored, "author two conditional-appearance parameters")
    dcs_rel = _poll_report_dcs(report_name, ctx="the appearance-parameter patch fixture")
    poll_disk_contains(dcs_rel, "TextColor",
                       ctx="both initial appearance parameters must reach Template.dcs")
    initial_disk = read_disk(dcs_rel)
    text_color_item = next((item for item in re.findall(
        r"<dcscor:item>(.*?)</dcscor:item>", initial_disk, re.DOTALL)
        if "<dcscor:parameter>TextColor</dcscor:parameter>" in item), None)
    assert text_color_item and "<dcscor:use>false</dcscor:use>" in text_color_item, \
        "the disabled TextColor parameter must persist use=false on its dcscor:item: %s" \
        % initial_disk[:2400]

    before_update = _get(rule_address, "conditionalAppearance")
    assert_ok(before_update, "read the conditional-appearance rule before update")
    updated = _write(rule_address, "update", "conditionalAppearance", {
        "appearance": {
            "BackColor": {
                "color": {"red": 0, "green": 0, "blue": 255},
            },
        },
    }, expectedHash=_hash(before_update))
    assert_ok(updated, "patch one appearance parameter with update")
    after_update = _get(rule_address, "conditionalAppearance")
    assert_ok(after_update, "read the conditional-appearance rule after update")
    assert "BackColor" in after_update.text and "TextColor" in after_update.text, \
        "update must retain the omitted TextColor parameter: %s" % after_update.text
    poll_disk_contains(dcs_rel, "TextColor",
                       ctx="update must retain the omitted appearance parameter on disk")

    before_upsert = _get(rule_address, "conditionalAppearance")
    assert_ok(before_upsert, "read the conditional-appearance rule before exact upsert")
    upserted = _write(rule_address, "upsert", "conditionalAppearance", {
        "appearance": {
            "BackColor": {
                "color": {"red": 255, "green": 255, "blue": 0},
            },
        },
    }, expectedHash=_hash(before_upsert))
    assert_ok(upserted, "patch one appearance parameter with exact-target upsert")
    after_upsert = _get(rule_address, "conditionalAppearance")
    assert_ok(after_upsert, "read the conditional-appearance rule after exact upsert")
    assert "BackColor" in after_upsert.text and "TextColor" in after_upsert.text, \
        "exact-target upsert must retain the omitted TextColor parameter: %s" % after_upsert.text
    poll_disk_contains(dcs_rel, "TextColor",
                       ctx="exact-target upsert must retain the omitted parameter on disk")

    replaced = _write(rule_address, "replace", "conditionalAppearance", {
        "appearance": {
            "BackColor": {
                "color": {"red": 0, "green": 0, "blue": 0},
            },
        },
    }, expectedHash=_hash(after_upsert))
    assert_ok(replaced, "replace the appearance rule from one parameter")
    after_replace = _get(rule_address, "conditionalAppearance")
    assert_ok(after_replace, "read the conditional-appearance rule after replace")
    assert "BackColor" in after_replace.text and "TextColor" not in after_replace.text, \
        "replace must clear omitted appearance parameters: %s" % after_replace.text
    poll_disk_lacks(dcs_rel, "TextColor",
                    ctx="replace must clear the omitted appearance parameter on disk")


@e2e_test(tool="dcs", kind="write-metadata")
def test_variant_output_parameters_use_declared_xml_types_and_refuse_unknown_names():
    language = "Language.E2EDcsOutputRussian"
    report_name = "E2EDcsTypedOutputParameters"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": language}),
              "add the output-parameter test language")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": language,
        "properties": [{"name": "languageCode", "value": "ru"}],
    }), "assign the output-parameter test language code")
    wait_for_project_ready()
    root = _seed_report(report_name)

    options = _options(root, "outputParameter", limit=1000, language="en")
    assert_ok(options, "read the version-aware output-parameter vocabulary")
    placement_option = re.search(
        r"\| output parameter \| (VerticalOverallPlacement) "
        r"\| ([^|]+) \| ([^|]+) \|", options.text)
    assert placement_option, \
        "options must report the placement key, declared enum type and literals: %s" \
        % options.text[:3000]
    reported_parameter = placement_option.group(1)
    declared_type = placement_option.group(2).strip()
    assert declared_type, "options must report a declared type: %s" % options.text[:3000]
    reported_literals = [value.strip() for value in placement_option.group(3).split(",")]
    assert "None" in reported_literals, \
        "options must report the None literal accepted by the writer: %s" % options.text[:3000]
    reported_literal = "None"

    authored = _write(root, "upsert", "variant", {
        "name": "TypedOutput",
        "presentation": "Typed output",
        "settings": {
            "outputParameters": {
                "items": [{
                    "parameter": {
                        "kind": "parameter",
                        "value": reported_parameter,
                    },
                    "value": reported_literal,
                }, {
                    "parameter": {"kind": "parameter", "value": "Title"},
                    "value": {
                        "ru": "Russian output title",
                        "en": "English output title",
                    },
                }],
            },
        },
    }, language="en")
    assert_ok(authored, "author enum and localized output parameters")

    authored_russian = _write(root, "upsert", "variant", {
        "name": "TypedOutputRussian",
        "presentation": "Typed output Russian names",
        "settings": {
            "outputParameters": {
                "items": [{
                    "parameter": {
                        "kind": "parameter",
                        "value": reported_parameter,
                    },
                    "value": reported_literal,
                }, {
                    "parameter": {"kind": "parameter", "value": "Title"},
                    "value": "Russian-call output title",
                }],
            },
        },
    }, language="ru")
    assert_ok(authored_russian,
              "author localized values in Russian without changing stored parameter names")
    dcs_rel = _poll_report_dcs(report_name, ctx="the typed output-parameter fixture")
    poll_disk_contains(dcs_rel, "English output title",
                       ctx="the typed output parameters must reach Template.dcs")
    # Both writes are asserted below, so both must be polled for. Polling only the first
    # variant's marker releases as soon as ITS export lands, and on a loaded machine the
    # second variant can still be in flight - the parse below then finds one variant and
    # blames the writer for a race that lives in this test.
    poll_disk_contains(dcs_rel, "Russian-call output title",
                       ctx="the Russian-call output parameters must reach Template.dcs")
    on_disk = read_disk(dcs_rel)

    namespaces = {
        "dcs": "http://v8.1c.ru/8.1/data-composition-system/schema",
        "dcscor": "http://v8.1c.ru/8.1/data-composition-system/core",
        "dcsset": "http://v8.1c.ru/8.1/data-composition-system/settings",
        "v8": "http://v8.1c.ru/8.1/data/core",
        "xsi": "http://www.w3.org/2001/XMLSchema-instance",
    }
    xml_root = ET.fromstring(on_disk)
    variants = {
        node.findtext("dcsset:name", namespaces=namespaces): node
        for node in xml_root.findall("dcs:settingsVariant", namespaces)
    }
    expected_variants = {"TypedOutput", "TypedOutputRussian"}
    assert expected_variants <= set(variants), \
        "the two writes must create their own settings variants: %s" % on_disk[:2400]

    output_values = {}
    for variant_name in expected_variants:
        variant = variants[variant_name]
        output_parameters = variant.find(
            "dcsset:settings/dcsset:outputParameters", namespaces)
        assert output_parameters is not None, \
            "%s must carry output parameters in its own settings: %s" \
            % (variant_name, on_disk[:2400])
        item_nodes = output_parameters.findall("dcscor:item", namespaces)
        parameter_names = [
            item.findtext("dcscor:parameter", namespaces=namespaces)
            for item in item_nodes
        ]
        assert sorted(parameter_names) == sorted([reported_parameter, "Title"]), \
            "%s must store both exact English parameter names in its own settings: %s" \
            % (variant_name, ET.tostring(output_parameters, encoding="unicode"))
        items = dict(zip(parameter_names, item_nodes))
        placement_value = items[reported_parameter].find("dcscor:value", namespaces)
        assert placement_value is not None \
            and placement_value.get("{%s}type" % namespaces["xsi"]) \
            == "dcscor:DataCompositionTotalPlacement" \
            and placement_value.text == reported_literal, \
            "%s placement must carry DataCompositionTotalPlacement and None: %s" \
            % (variant_name, ET.tostring(items[reported_parameter], encoding="unicode"))
        title_value = items["Title"].find("dcscor:value", namespaces)
        assert title_value is not None \
            and title_value.get("{%s}type" % namespaces["xsi"]) == "v8:LocalStringType", \
            "%s title must carry v8:LocalStringType: %s" \
            % (variant_name, ET.tostring(items["Title"], encoding="unicode"))
        output_values[variant_name] = {
            item.findtext("v8:lang", namespaces=namespaces):
                item.findtext("v8:content", namespaces=namespaces)
            for item in title_value.findall("v8:item", namespaces)
        }

    assert _VERTICAL_OVERALL_PLACEMENT_RU not in on_disk and _TITLE_RU not in on_disk, \
        "language='ru' must not switch parameter names away from the configuration language: %s" \
        % on_disk[:2400]
    assert output_values["TypedOutput"] == {
        "en": "English output title",
        "ru": "Russian output title",
    }, "the English-call variant must retain both localized title values: %s" \
        % output_values["TypedOutput"]
    assert output_values["TypedOutputRussian"] == {
        "ru": "Russian-call output title",
    }, "language='ru' must select ru for the localized value: %s" \
        % output_values["TypedOutputRussian"]

    before_refusal = read_disk(dcs_rel)
    refused = _write(root + "#/variants/TypedOutput/settings/outputParameters",
                     "upsert", "outputParameter", {
                         "items": [{
                             "parameter": {
                                 "kind": "parameter",
                                 "value": "ThisParameterDoesNotExist",
                             },
                             "value": "must not land",
                         }],
                     }, language="en")
    error = assert_error(refused, "an unknown output-parameter name")
    assert_error_quality(
        error,
        names=["ThisParameterDoesNotExist", "VerticalOverallPlacement", "Title"],
        suggests=["typed keys"],
        ctx="the output-parameter error names the bad and valid platform keys")
    assert read_disk(dcs_rel) == before_refusal, \
        "a refused output parameter must leave Template.dcs byte-for-byte unchanged"


@e2e_test(tool="dcs", kind="write-metadata")
def test_typed_conditional_appearance_resolves_named_style_color_to_style_literal():
    style_name = "E2EDcsNamedBackColor"
    style_fqn = "StyleItem." + style_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": style_fqn}),
              "create the named color style item")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": style_fqn,
        "properties": [{
            "name": "value",
            "value": {"color": {"red": 240, "green": 80, "blue": 80}},
        }],
    }), "make the named style item a color")
    wait_for_project_ready()

    report_name = "E2EDcsNamedStyleColor"
    root = _seed_report(report_name)
    authored = _write(root, "upsert", "schema", {
        "defaultSettings": {
            "conditionalAppearance": {"items": [{
                "appearance": {
                    "BackColor": {"color": {"style": style_name}},
                },
            }]},
        },
    })
    assert_ok(authored, "author a typed conditional-appearance style color")

    dcs_rel = _poll_report_dcs(report_name, ctx="the named style-color DCS fixture")
    poll_disk_contains(dcs_rel, "style:" + style_name,
                       ctx="the named style color must reach Template.dcs")
    on_disk = read_disk(dcs_rel)
    assert re.search(
        r'<dcscor:value xsi:type="v8ui:Color">style:%s</dcscor:value>'
        % re.escape(style_name), on_disk), \
        "the typed path must serialize the resolved style item as style:<name>: %s" \
        % on_disk[:2400]


@e2e_test(tool="dcs", kind="write-metadata")
def test_nested_selection_item_address_copied_from_read_can_be_removed():
    report_name = "E2EDcsNestedSelectionRemove"
    root = _seed_report(report_name)
    removed_field = "NestedSelectionRemoveMe"
    retained_field = "NestedSelectionKeepMe"
    authored = _write(root, "upsert", "schema", {
        "defaultSettings": {
            "selection": {"items": [{
                "kind": "group",
                "items": [{
                    "kind": "group",
                    "items": [
                        {"kind": "field", "field": {
                            "kind": "field", "value": removed_field,
                        }},
                        {"kind": "field", "field": {
                            "kind": "field", "value": retained_field,
                        }},
                    ],
                }],
            }]},
        },
    })
    assert_ok(authored, "author recursively nested selection groups")
    dcs_rel = _poll_report_dcs(report_name, ctx="the nested-selection removal fixture")
    poll_disk_contains(dcs_rel, removed_field,
                       ctx="the nested field must reach Template.dcs before removal")

    selection = _get(root + "#/defaultSettings/selection", "selection")
    assert_ok(selection, "read the selection tree containing the nested item address")
    target = (root + "#/defaultSettings/selection/items/0/items/0/items/0")
    assert target in selection.text, \
        "the nested removal target must be an address printed by read: %s" % selection.text
    before = _get(target, "selection")
    assert_ok(before, "read the exact nested selection item before removal")
    removed = call("dcs", {
        "projectName": PROJECT,
        "fqn": target,
        "action": "remove",
        "type": "selection",
        "expectedHash": _hash(before),
    })
    assert_ok(removed, "remove the nested selection item through its copied address")

    after = _get(root + "#/defaultSettings/selection", "selection")
    assert_ok(after, "read back the nested selection tree after removal")
    assert removed_field not in after.text and retained_field in after.text
    poll_disk_lacks(dcs_rel, removed_field,
                    ctx="the removed nested field must leave Template.dcs")
    poll_disk_contains(dcs_rel, retained_field,
                       ctx="the nested sibling must survive removal")


@e2e_test(tool="dcs", kind="write-metadata")
def test_default_settings_remove_refuses_mismatched_type_without_touching_disk():
    report_name = "E2EDcsSettingsRootRemoveGuard"
    root = _seed_report(report_name)
    marker = "M2RootGuardOperand"
    authored = _write(root, "upsert", "schema", {
        "defaultSettings": {
            "filter": {
                "items": [{
                    "left": {"kind": "field", "value": marker},
                    "comparisonType": "Equal",
                    "use": True,
                }],
            },
        },
    })
    assert_ok(authored, "author default settings for the root-remove type guard")
    dcs_rel = _poll_report_dcs(report_name, ctx="the settings root-remove fixture")
    poll_disk_contains(dcs_rel, marker,
                       ctx="the default settings must reach Template.dcs before the refusal")

    settings = _get(root + "#/defaultSettings", "userSettings")
    assert_ok(settings, "read the default-settings root before the mismatched remove")
    before_disk = read_disk(dcs_rel)
    refused = call("dcs", {
        "projectName": PROJECT,
        "fqn": root + "#/defaultSettings",
        "action": "remove",
        "type": "selection",
        "expectedHash": _hash(settings),
    })
    error = assert_error(refused, "remove defaultSettings while declaring selection")
    assert "type='selection'" in error and "type='userSettings'" in error, \
        "the refusal must name both the declared and resolved root types: %s" % error
    assert read_disk(dcs_rel) == before_disk, \
        "a refused defaultSettings removal must leave Template.dcs byte-for-byte unchanged"


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
def test_case_variant_address_copied_from_user_field_read_can_be_updated():
    report_name = "E2EDcsCaseVariantAddress"
    root = _seed_report(report_name)
    before_marker = "E2ECaseVariantBefore"
    after_marker = "E2ECaseVariantAfter"
    seeded = _write(root, "upsert", "schema", {
        "defaultSettings": {
            "userFields": {"items": [{
                "kind": "case",
                "dataPath": "E2EChoice",
                "variants": {"items": [{
                    "value": {"kind": "string", "value": before_marker},
                    "use": True,
                }]},
            }]},
        },
    })
    assert_ok(seeded, "seed a case user field carrying one variant")

    field_address = root + "#/defaultSettings/userFields/items/0"
    field = _get(field_address, "userField")
    assert_ok(field, "read the case field that advertises its variant address")
    expected = field_address + "/variants/items/0"
    copied = re.search(re.escape(expected), field.text)
    assert copied, "the user-field read must print its exact variant address: %s" % field.text

    before = _get(copied.group(0), "userField")
    assert_ok(before, "read the case variant through the copied address")
    updated = _write(copied.group(0), "update", "userField", {
        "value": {"kind": "string", "value": after_marker},
    }, expectedHash=_hash(before))
    assert_ok(updated, "update the case variant through its copied address")

    after = _get(copied.group(0), "userField")
    assert_ok(after, "read back the case variant through the same address")
    assert after_marker in after.text and before_marker not in after.text
    dcs_rel = _poll_report_dcs(report_name, ctx="the exact case-variant update")
    poll_disk_contains(dcs_rel, after_marker,
                       ctx="the case-variant update must reach Template.dcs")


@e2e_test(tool="dcs", kind="write-metadata")
def test_settings_collection_address_copied_from_outline_renders_a_page():
    root = _seed_report("E2EDcsSettingsCollectionRead")
    authored = _write(root, "upsert", "variant", {
        "name": "Readable",
        "presentation": "Readable settings",
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
def test_bare_user_settings_read_exposes_the_complete_default_settings_target():
    report_name = "E2EDcsBareUserSettings"
    root = _seed_report(report_name)
    marker = "BareRootRevenue"
    authored = _write(root, "upsert", "schema", {
        "defaultSettings": {
            "selection": {
                "items": [{"field": {"kind": "field", "value": marker}}],
            },
        },
    })
    assert_ok(authored, "author a selection item in defaultSettings")
    dcs_rel = _poll_report_dcs(report_name, ctx="the bare userSettings read fixture")
    poll_disk_contains(dcs_rel, marker,
                       ctx="the selected field must reach Template.dcs before the read")

    settings = _get(root, "userSettings")
    assert_ok(settings, "read the complete defaultSettings target from the bare report root")
    assert "**Address:** `" + root + "#/defaultSettings`" in settings.text, \
        "the bare userSettings read must identify the object the matching write targets"
    selection_address = root + "#/defaultSettings/selection/items/0"
    assert "`" + selection_address + "`" in settings.text, \
        "the whole-settings projection must expose the selected item address: %s" % settings.text
    assert marker in settings.text, \
        "the bare read must expose selection content that a bare userSettings write can change"


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
def test_table_selection_item_address_copied_from_read_can_be_updated():
    report_name = "E2EDcsTableSelectionAddress"
    root = _seed_report(report_name)
    old_field = "E2ETableSelectionBefore"
    new_field = "E2ETableSelectionAfter"
    seeded = _write(root, "upsert", "schema", {
        "defaultSettings": {
            "items": [{
                "kind": "table",
                "name": "SalesTable",
                "selection": {"items": [{
                    "field": {"kind": "field", "value": old_field},
                    "use": True,
                }]},
            }],
        },
    })
    assert_ok(seeded, "seed a table carrying one selected field")

    table_address = root + "#/defaultSettings/items/0"
    table = _get(table_address, "table")
    assert_ok(table, "read the table that advertises its selected-field address")
    expected = table_address + "/selection/items/0"
    copied = re.search(re.escape(expected), table.text)
    assert copied, "the table read must print its exact selection-item address: %s" % table.text

    before = _get(copied.group(0), "selection")
    assert_ok(before, "read the table selection item through the copied address")
    updated = _write(copied.group(0), "update", "selection", {
        "field": {"kind": "field", "value": new_field},
    }, expectedHash=_hash(before))
    assert_ok(updated, "update the table selection item through its copied address")

    after = _get(copied.group(0), "selection")
    assert_ok(after, "read back the table selection item through the same address")
    assert new_field in after.text and old_field not in after.text
    dcs_rel = _poll_report_dcs(report_name, ctx="the exact table-selection update")
    poll_disk_contains(dcs_rel, new_field,
                       ctx="the table-selection update must reach Template.dcs")


@e2e_test(tool="dcs", kind="write-metadata")
def test_table_axis_holder_address_copied_from_read_writes_to_disk():
    report_name = "E2EDcsTableAxisHolder"
    root = _seed_report(report_name)
    old_id = "oldAxisSelection"
    new_id = "newAxisSelection"
    seeded = _write(root, "upsert", "schema", {
        "defaultSettings": {
            "items": [{
                "kind": "table",
                "name": "SalesTable",
                "rows": [{
                    "name": "CustomerAxis",
                    "selection": {"userSettingID": old_id, "items": []},
                }],
            }],
        },
    })
    assert_ok(seeded, "author a table carrying a row-axis selection holder")

    table_address = root + "#/defaultSettings/items/0"
    holder_address = table_address + "/rows/0/selection"
    table = _get(table_address, "table")
    assert_ok(table, "read the table that advertises nested axis-holder addresses")
    assert "`" + holder_address + "`" in table.text, \
        "the table read must advertise the canonical axis-holder address: %s" % table.text

    before = _get(holder_address, "selection")
    assert_ok(before, "read the row-axis selection through the advertised address")
    replaced = _write(holder_address, "replace", "selection", {
        "userSettingID": new_id,
        "items": [],
    }, expectedHash=_hash(before))
    assert_ok(replaced, "replace the row-axis selection through its canonical address")

    after = _get(holder_address, "selection")
    assert_ok(after, "read back the row-axis selection replacement")
    assert new_id in after.text and old_id not in after.text

    dcs_rel = _poll_report_dcs(report_name, ctx="the table-axis holder fixture")
    poll_disk_contains(dcs_rel, new_id,
                       ctx="the row-axis holder replacement must reach Template.dcs")
    on_disk = read_disk(dcs_rel)
    assert new_id in on_disk and old_id not in on_disk


@e2e_test(tool="dcs", kind="write-metadata")
def test_schema_summary_and_schema_collection_read_expose_data_set_links():
    report_name = "E2EDcsReadLinks"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for data-set-link reads")
    wait_for_project_ready()
    authored = _write(root, "upsert", "schema", {
        "dataSources": [{"name": "MainSource", "type": "Local"}],
        "dataSets": [
            {"name": "Source", "type": "query", "dataSource": "mainsource",
             "query": "SELECT 1 AS Key"},
            {"name": "Destination", "type": "query", "dataSource": "MAINSOURCE",
             "query": "SELECT 1 AS Key"},
        ],
        "parameters": [{"name": "LinkParameter"}],
        "dataSetLinks": [{
            "sourceDataSet": "source",
            "destinationDataSet": "DESTINATION",
            "sourceExpression": "Key",
            "destinationExpression": "Key",
            "parameter": "linkparameter",
        }],
    })
    assert_ok(authored, "author caller-cased references to existing DCS identities")
    dcs_rel = _poll_report_dcs(report_name, ctx="the readable data-set-link fixture")
    poll_disk_contains(dcs_rel, "Destination",
                       ctx="the linked data sets must reach Template.dcs")
    poll_disk_contains(dcs_rel, "LinkParameter",
                       ctx="the link parameter must reach Template.dcs")

    summary = _get(root, "schema")
    assert_ok(summary, "read the schema summary containing data-set links")
    assert "| Data set links | 1 | " + root + "#/dataSetLinks |" in summary.text
    assert root + "#/dataSetLinks/0" in summary.text
    assert "source → DESTINATION" in summary.text

    page = _get(root + "#/dataSetLinks", "schema")
    assert_ok(page, "page data-set links through the schema public type")
    assert "# DCS collection: schema" in page.text
    assert "**Items:** 1" in page.text
    assert root + "#/dataSetLinks/0" in page.text

    unguarded = _write(root + "#/dataSetLinks/0", "update", "schema", {
        "sourceExpression": "ChangedKey",
    })
    unguarded_error = assert_error(unguarded,
                                   "update an index-selected link without expectedHash")
    assert_error_quality(unguarded_error, names=["expectedHash", "#/dataSetLinks/0"],
                         suggests=["action='get'"],
                         ctx="an ordered data-set link must require optimistic locking")

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
def test_user_field_reference_guard_covers_report_variant_and_dynamic_list_settings():
    settings = {
        "selection": {"items": [{
            "kind": "field",
            "field": {"kind": "field", "value": "ProtectedField"},
        }]},
        "userFields": {"items": [
            {"kind": "expression", "dataPath": "ProtectedField"},
            {"kind": "expression", "dataPath": "FreeField"},
        ]},
    }

    report_root = _seed_report("E2EDcsUserFieldReferences")
    variant_name = "Protected"
    authored_report = _write(report_root, "upsert", "variant", {
        "name": variant_name,
        "presentation": "Protected settings",
        "settings": settings,
    })
    assert_ok(authored_report, "seed referenced and free user fields in a report variant")
    report_settings = report_root + "#/variants/%s/settings" % variant_name
    report_target = report_settings + "/userFields/items/0"
    report_reference = report_settings + "/selection/items/0"
    report_field = _get(report_target, "userField")
    assert_ok(report_field, "read the referenced report user field and its hash")

    report_remove = call("dcs", {
        "projectName": PROJECT,
        "fqn": report_target,
        "action": "remove",
        "type": "userField",
        "expectedHash": _hash(report_field),
    })
    report_remove_error = assert_error(report_remove, "remove a referenced report user field")
    assert_error_quality(
        report_remove_error,
        names=["ProtectedField", report_reference],
        suggests=["referring nodes", "re-run get"],
        ctx="the report-variant refusal must name the referring selection item")

    report_rename = _write(report_target, "update", "userField", {
        "dataPath": "RenamedField",
    }, expectedHash=_hash(report_field))
    report_rename_error = assert_error(report_rename, "rename a referenced report user field")
    assert_error_quality(
        report_rename_error,
        names=["ProtectedField", report_reference],
        suggests=["referring nodes", "retry"],
        ctx="the report-variant rename must reach the same reference guard")

    report_holder = _get(report_settings + "/userFields", "userField")
    assert_ok(report_holder, "read the report userFields holder before replacement")
    report_replace = _write(report_settings + "/userFields", "replace", "userField", {
        "items": [{"kind": "expression", "dataPath": "FreeField"}],
    }, expectedHash=_hash(report_holder))
    report_replace_error = assert_error(
        report_replace, "replace the report userFields holder while omitting a referenced field")
    assert_error_quality(
        report_replace_error,
        names=["ProtectedField", report_reference],
        suggests=["referring nodes", "retry"],
        ctx="authoritative report holder replacement must guard deletion by omission")

    report_free = _get(report_settings + "/userFields/items/1", "userField")
    assert_ok(report_free, "read the unreferenced report user field")
    report_free_remove = call("dcs", {
        "projectName": PROJECT,
        "fqn": report_settings + "/userFields/items/1",
        "action": "remove",
        "type": "userField",
        "expectedHash": _hash(report_free),
    })
    assert_ok(report_free_remove, "remove an unreferenced report user field")
    report_fields = _get(report_settings + "/userFields/items", "userField")
    assert_ok(report_fields, "read back report user fields after the allowed removal")
    assert "ProtectedField" in report_fields.text and "FreeField" not in report_fields.text

    list_root = _seed_dynamic_list("UserFieldReferences")
    authored_list = _write(list_root, "upsert", "dynamicList", {"listSettings": settings})
    assert_ok(authored_list, "seed referenced and free user fields in listSettings")
    list_settings = list_root + "#/listSettings"
    list_target = list_settings + "/userFields/items/0"
    list_reference = list_settings + "/selection/items/0"
    first_list_settings = _get(list_settings, "userSettings")
    assert_ok(first_list_settings, "read listSettings immediately after its first settings-only write")
    assert "ProtectedField" in first_list_settings.text, \
        "the first write must attach userFields content: %s" % first_list_settings.text
    assert list_target in first_list_settings.text, \
        "the first write must expose its user-field address: %s" % first_list_settings.text
    assert list_reference in first_list_settings.text, \
        "the first write must attach selection content: %s" % first_list_settings.text
    assert _hash(authored_list) == _hash(first_list_settings), \
        "the write response hash must come from the committed model read back by the tool"
    list_field = _get(list_target, "userField")
    assert_ok(list_field, "read the referenced dynamic-list user field and its hash")

    list_remove = call("dcs", {
        "projectName": PROJECT,
        "fqn": list_target,
        "action": "remove",
        "type": "userField",
        "expectedHash": _hash(list_field),
    })
    list_remove_error = assert_error(list_remove, "remove a referenced list user field")
    assert_error_quality(
        list_remove_error,
        names=["ProtectedField", list_reference],
        suggests=["referring nodes", "re-run get"],
        ctx="the dynamic-list refusal must name the referring selection item")

    list_rename = _write(list_target, "update", "userField", {
        "dataPath": "RenamedField",
    }, expectedHash=_hash(list_field))
    list_rename_error = assert_error(list_rename, "rename a referenced list user field")
    assert_error_quality(
        list_rename_error,
        names=["ProtectedField", list_reference],
        suggests=["referring nodes", "retry"],
        ctx="the dynamic-list rename must reach the same reference guard")

    list_holder = _get(list_settings + "/userFields", "userField")
    assert_ok(list_holder, "read the list userFields holder before replacement")
    list_replace = _write(list_settings + "/userFields", "replace", "userField", {
        "items": [{"kind": "expression", "dataPath": "FreeField"}],
    }, expectedHash=_hash(list_holder))
    list_replace_error = assert_error(
        list_replace, "replace the list userFields holder while omitting a referenced field")
    assert_error_quality(
        list_replace_error,
        names=["ProtectedField", list_reference],
        suggests=["referring nodes", "retry"],
        ctx="authoritative list holder replacement must guard deletion by omission")

    list_free = _get(list_settings + "/userFields/items/1", "userField")
    assert_ok(list_free, "read the unreferenced dynamic-list user field")
    list_free_remove = call("dcs", {
        "projectName": PROJECT,
        "fqn": list_settings + "/userFields/items/1",
        "action": "remove",
        "type": "userField",
        "expectedHash": _hash(list_free),
    })
    assert_ok(list_free_remove, "remove an unreferenced dynamic-list user field")
    list_fields = _get(list_settings + "/userFields/items", "userField")
    assert_ok(list_fields, "read back list user fields after the allowed removal")
    assert "ProtectedField" in list_fields.text and "FreeField" not in list_fields.text


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
def test_field_collection_replace_refuses_omitting_selected_field_and_preserves_disk():
    report_name = "E2EDcsReplaceSelectedField"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "seed report for the field-collection reference guard")
    wait_for_project_ready()
    authored = _write(root, "upsert", "schema", {
        "dataSets": [{
            "name": "Sales",
            "type": "query",
            "query": "SELECT 1 AS Referenced, 2 AS Retained",
            "fields": [
                {"dataPath": "Referenced", "field": "Referenced"},
                {"dataPath": "Retained", "field": "Retained"},
            ],
        }],
        "defaultSettings": {
            "selection": {
                "items": [{
                    "kind": "field",
                    "field": {"kind": "field", "value": "Referenced"},
                }],
            },
        },
    })
    assert_ok(authored, "seed a selected explicit data-set field")
    dcs_rel = _poll_report_dcs(report_name, ctx="the selected-field fixture")
    poll_disk_contains(dcs_rel, "Referenced",
                       ctx="the selected field must reach Template.dcs before the refusal")
    before_disk = read_disk(dcs_rel)

    collection_address = root + "#/dataSets/Sales/fields"
    data_set = _get(root + "#/dataSets/Sales", "dataSet")
    assert_ok(data_set, "read the data set that advertises its field collection")
    assert collection_address in data_set.text, \
        "the data-set page must print the field collection address: %s" % data_set.text
    fields = _get(collection_address, "field")
    assert_ok(fields, "read the advertised field collection and its hash")
    refused = _write(collection_address, "replace", "field", {
        "dataPath": "Retained",
        "field": "Retained",
    }, expectedHash=_hash(fields))

    error = assert_error(refused, "replace the field collection while omitting a selected field")
    assert_error_quality(error,
                         names=["Referenced", root + "#/defaultSettings/selection"],
                         suggests=["referring nodes", "retry"],
                         ctx="field collection replacement names the retained reference")
    assert read_disk(dcs_rel) == before_disk, \
        "a refused field collection replacement must leave Template.dcs byte-for-byte unchanged"


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
    query_page = _get(copied_query.group(0), "dynamicList")
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


@e2e_test(tool="dcs", kind="write-metadata")
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
    # Two waits, because each guards a different wrong-reason pass. Poll for the ROOT ELEMENT first:
    # an absence check on a missing file is satisfied by the file never having been written. Then poll
    # for the ABSENCE itself, because the export is asynchronous and the seed already put "selection"
    # in this file - polling for "selection" is satisfied by the STALE content and reads the old title
    # back, which is how this passed on a fast local disk and flaked on a CI shard.
    poll_disk_contains(settings_rel, "<Settings",
                       ctx="the settings file must exist before asserting what is not in it")
    poll_disk_lacks(settings_rel, "Reference",
                    ctx="the replaced settings must reach ListSettings.dcss")
    on_disk = read_disk(settings_rel)
    assert "Reference" not in on_disk,         "the title the replace never mentioned must not survive on disk: %s" % on_disk[:600]

    # The list's own types are a different contract, and the refusal must say where to go.
    refused = _write(root, "replace", "dynamicList", {"queryText": "SELECT 1"},
                     expectedHash=_hash(_get(root, "dynamicList")), language="en")
    error = assert_error(refused, "replace on a dynamic list's own type")
    assert_error_quality(error, names=["#/listSettings"],
                         ctx="the refusal must point at the settings layer that does accept it")


@e2e_test(tool="dcs", kind="write-metadata")
def test_dynamic_list_settings_change_refuses_the_pre_change_hash():
    root = _seed_dynamic_list("SettingsHashGuard")
    selection_address = root + "#/listSettings/selection"
    seeded = _write(root, "upsert", "dynamicList", {
        "listSettings": {
            "selection": {
                "items": [
                    {"kind": "field", "field": {"kind": "field", "value": "First"}},
                    {"kind": "field", "field": {"kind": "field", "value": "Second"}},
                ],
            },
        },
    })
    assert_ok(seeded, "seed two index-addressable dynamic-list selection items")

    first_read = _get(root, "dynamicList")
    assert_ok(first_read, "get the dynamic-list hash before reordering its settings")
    first_hash = _hash(first_read)
    reordered = _write(selection_address, "replace", "selection", {
        "items": [
            {"kind": "field", "field": {"kind": "field", "value": "Second"}},
            {"kind": "field", "field": {"kind": "field", "value": "First"}},
        ],
    }, expectedHash=first_hash)
    assert_ok(reordered, "reorder dynamic-list settings through a second call")
    current_hash = _hash(reordered)
    assert current_hash != first_hash, \
        "the hash returned after a settings reorder must describe the new settings order"

    stale = _write(selection_address + "/items/0", "update", "selection", {"use": False},
                   expectedHash=first_hash)
    error = assert_error(stale, "reuse the hash from before the dynamic-list settings reorder")
    assert_error_quality(error, names=[first_hash, current_hash],
                         suggests=["Re-run dcs action='get'", "expectedHash"],
                         ctx="dynamic-list settings must participate in the stale-index guard")


@e2e_test(tool="dcs", kind="write-metadata")
def test_field_folder_and_its_addressed_child_reach_exported_dcs():
    report_name = "E2EDcsFieldFolder"
    root = "Report." + report_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": root}),
              "create the field-folder report")
    wait_for_project_ready()
    authored = _write(root, "upsert", "schema", {
        "dataSets": [{
            "name": "Sales",
            "type": "query",
            "query": "SELECT 1 AS CustomerName",
            "autoFillFields": False,
            "fields": [{
                "kind": "folder",
                "dataPath": "Customer",
                "title": {"en": "Customer"},
                "useRestriction": {"field": True},
                "fields": [{
                    "dataPath": "Customer.Name",
                    "field": "CustomerName",
                }],
            }],
        }],
    }, language="en")
    assert_ok(authored, "author a DCS field folder with one field")

    dcs_rel = _poll_report_dcs(report_name, ctx="the field-folder report")
    poll_disk_contains(dcs_rel, "DataSetFieldFolder",
                       ctx="the field-folder subtype must reach Template.dcs")
    poll_disk_contains(dcs_rel, "<dataPath>Customer.Name</dataPath>",
                       ctx="the field inside the folder must reach Template.dcs")

    folder_address = root + "#/dataSets/Sales/fields/Customer"
    child_address = folder_address + "/fields/Customer.Name"
    folder = _get(folder_address, "fieldFolder")
    assert_ok(folder, "read the authored folder through its public type")
    assert child_address in folder.text, \
        "the folder read must advertise the exact child address: %s" % folder.text

    updated = _write(child_address, "update", "field", {
        "title": {"en": "Customer name"},
    }, language="en")
    assert_ok(updated, "write the field through the address returned inside its folder")
    poll_disk_contains(dcs_rel, "Customer name",
                       ctx="the addressed child update must reach Template.dcs")


@e2e_test(tool="dcs", kind="write-metadata")
def test_chart_schema_survives_xml_copy_and_typed_chart_write_is_articulately_refused():
    schema_ns = "http://v8.1c.ru/8.1/data-composition-system/schema"
    settings_ns = "http://v8.1c.ru/8.1/data-composition-system/settings"
    xsi_ns = "http://www.w3.org/2001/XMLSchema-instance"
    ET.register_namespace("", schema_ns)
    ET.register_namespace("dcsset", settings_ns)
    ET.register_namespace("xsi", xsi_ns)

    source_name = "E2EDcsChartXmlSource"
    source_root = "Report." + source_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": source_root}),
              "create the chart XML source report")
    wait_for_project_ready()
    seeded = _write(source_root, "upsert", "schema", {
        "defaultSettings": {"items": []},
    })
    assert_ok(seeded, "materialize default settings for the chart XML fixture")

    plain_xml, _pages = _read_all_xml(source_root)
    document = ET.fromstring(plain_xml)
    default_settings = next(
        (node for node in document.iter()
         if node.tag.rsplit("}", 1)[-1] == "defaultSettings"), None)
    assert default_settings is not None, \
        "the typed seed must serialize a defaultSettings node"
    # Seed the chart in the shape EDT itself writes. A bare <item xsi:type="...Chart"/> has no
    # point axis, EDT builds nothing from it, and the round-trip loss guard then correctly
    # reports the submitted node as removed. Every real chart carries a point with a selection.
    chart = ET.Element("{%s}item" % settings_ns)
    chart.set("{%s}type" % xsi_ns, "dcsset:StructureItemChart")
    point = ET.SubElement(chart, "{%s}point" % settings_ns)
    point_selection = ET.SubElement(point, "{%s}selection" % settings_ns)
    point_auto = ET.SubElement(point_selection, "{%s}item" % settings_ns)
    point_auto.set("{%s}type" % xsi_ns, "dcsset:SelectedItemAuto")
    chart_selection = ET.SubElement(chart, "{%s}selection" % settings_ns)
    chart_auto = ET.SubElement(chart_selection, "{%s}item" % settings_ns)
    chart_auto.set("{%s}type" % xsi_ns, "dcsset:SelectedItemAuto")
    default_settings.append(chart)
    chart_xml = ET.tostring(document, encoding="unicode")

    current = _get(source_root, "schema")
    imported = _write(source_root, "replace", "schema", {"xml": chart_xml},
                      expectedHash=_hash(current))
    assert_ok(imported, "seed an intentionally typed-unsupported chart through XML")
    source_rel = _poll_report_dcs(source_name, ctx="the chart XML source")
    poll_disk_contains(source_rel, "StructureItemChart",
                       ctx="the chart-bearing source must reach Template.dcs")

    chart_address = source_root + "#/defaultSettings/items/0"
    settings = _get(source_root, "userSettings")
    assert_ok(settings, "read settings containing the chart")
    assert "DataCompositionChart" in settings.text and chart_address in settings.text, \
        "the read must expose the chart and its write address: %s" % settings.text

    refused = _write(chart_address, "update", "grouping", {"name": "NoChartWrite"},
                     expectedHash=_hash(settings))
    error = assert_error(refused, "typed write addressed at an existing chart")
    assert_error_quality(
        error,
        names=["DataCompositionChart", "chart"],
        suggests=["authoring it is not supported by this tool",
                  "action='replace', type='schema'", "body={xml:...}",
                  "bare schema root"],
        ctx="the chart refusal must name the deliberate exclusion and XML escape hatch")
    assert "no public DCS type" not in error

    source_chart_xml, _pages = _read_all_xml(source_root)
    target_name = "E2EDcsChartXmlTarget"
    target_root = "Report." + target_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": target_root}),
              "create the chart XML target report")
    wait_for_project_ready()
    target_before = _get(target_root, "schema")
    copied = _write(target_root, "replace", "schema", {"xml": source_chart_xml},
                    expectedHash=_hash(target_before))
    assert_ok(copied, "copy the chart-bearing schema through lossless XML")
    target_rel = _poll_report_dcs(target_name, ctx="the chart XML target")
    poll_disk_contains(target_rel, "StructureItemChart",
                       ctx="the copied chart must reach the target Template.dcs")
    target_chart_xml, _pages = _read_all_xml(target_root)
    assert _xml_structure(target_chart_xml) == _xml_structure(source_chart_xml), \
        "the chart-bearing schema must survive the XML copy unchanged"


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
