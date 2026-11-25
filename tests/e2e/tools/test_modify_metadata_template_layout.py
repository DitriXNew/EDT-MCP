"""
e2e tests for template_content LAYOUT authoring (#1001): cell borders, border / back / text
colours, unmerging an existing merge, and removing / renaming a named area, through the same
`template` payload as #245:

    template_content(method="modify", fqn="Catalog.Catalog.Template.<Name>",
                    template={cells: [{row, col, borders?, borderColor?, backColor?, textColor?, ...}],
                              unmerges: [{fromRow, fromCol, toRow, toCol}],
                              removeAreas: [name], renameAreas: [{name, newName}], ...})

The load-bearing proof is the template's OWN Template.mxlx on disk (the moxel content resource beside
the owner's .mdo): a border is a <line> of the document's line pool referenced from a <format>, a colour
is written inline in the <format>, a merge is a <merge> block and a named area a <namedItem>. The write
force-exports the template before it returns, so the file is read DIRECTLY after the call (no polling: an
early-returning build must fail), and only then is the MODEL read back - by a render, or by a follow-up
call whose answer depends on the model (a second unmerge counts 0, a removed name is refused).

reset: kind="write-metadata" -> reset_fixture()+reset_model() after each test.

FIXTURE TRUTH (TestConfiguration)
  - Catalog.Catalog hosts owned templates; each happy path seeds a FRESH EMPTY one (no lines, no
    colours, no merges, no named items), so every authored element landing on disk is a clean anti-cheat.
  - CommonTemplate.PrintForm is a content-bearing fixture template with ONE merge and NO named area: a
    refused write must leave it byte-for-byte untouched.
"""

import base64
import re
import xml.etree.ElementTree as ET

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    read_disk,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)

FIXTURE_TEMPLATE = "CommonTemplate.PrintForm"


def _seed_owned_template(name):
    """Create a fresh empty owned SpreadsheetDocument template; return (fqn, mxlx relpath)."""
    fqn = "Catalog.Catalog.Template." + name
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, "seed owned template " + fqn)
    wait_for_project_ready()
    return fqn, "src/Catalogs/Catalog/Templates/%s/Template.mxlx" % name


def _counts(r, ctx):
    assert r.structured is not None, "%s: JSON tool must return structuredContent: %r" % (ctx, r.raw)
    assert r.structured.get("action") == "modified", "%s: must report modified: %r" % (ctx, r.structured)
    assert r.structured.get("method") == "modify", r.structured
    counts = r.structured.get("template")
    assert isinstance(counts, dict), "%s: the template counts object is missing: %r" % (ctx, r.structured)
    return counts


def _png_ok(r):
    res = r.raw.get("result") if isinstance(r.raw, dict) else None
    content = (res or {}).get("content") or []
    if not (content and isinstance(content[0], dict)):
        return False
    resource = content[0].get("resource")
    blob = resource.get("blob") if isinstance(resource, dict) else None
    try:
        return bool(blob) and base64.b64decode(blob)[:8] == b"\x89PNG\r\n\x1a\n"
    except Exception:
        return False


def _cell_format(doc, row, col):
    """The <format> block a cell of the .mxlx points at. The serializer writes formats 1-based (a cell's
    <f>0</f> means "no format of its own") and lines / fonts 0-based, each pool in document order."""
    rows = re.findall(r"<rowsItem>\s*<index>(\d+)</index>(.*?)</rowsItem>", doc, re.S)
    body = dict((int(i), b) for i, b in rows).get(row)
    assert body is not None, "row %d is not in the .mxlx: %r" % (row, doc[:1500])
    cells = re.findall(r"<c>\s*(?:<i>(\d+)</i>\s*)?<c>\s*<f>(\d+)</f>", body)
    index, previous = None, -1
    for explicit, f in cells:
        previous = int(explicit) if explicit else previous + 1
        if previous == col:
            index = int(f)
    assert index, "cell (%d, %d) has no format of its own: %r" % (row, col, body)
    formats = re.findall(r"<format>(.*?)</format>", doc, re.S)
    return formats[index - 1]


def _side_line(doc, cell_format, side):
    """(width, style) of the pooled line a format's border side references."""
    m = re.search(r"<%s>(\d+)</%s>" % (side, side), cell_format)
    assert m, "%s is not set on the format %r" % (side, cell_format)
    lines = re.findall(r'<line width="(\d+)" gap="(?:true|false)">\s*<v8ui:style[^>]*>(\w+)</v8ui:style>', doc)
    return lines[int(m.group(1))]


def _merges(doc):
    """The <merge> blocks of an .mxlx as (row, col, height, width) tuples (absent h / w = 0)."""
    found = []
    for block in re.findall(r"<merge>(.*?)</merge>", doc, re.S):
        def num(tag):
            m = re.search(r"<%s>(\d+)</%s>" % (tag, tag), block)
            return int(m.group(1)) if m else 0
        found.append((num("r"), num("c"), num("h"), num("w")))
    return found


# ══════════════════════════════════════════════════════════════════════════════
# Happy — borders and colours land in the .mxlx in the platform's own spelling
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="template_content", kind="write-metadata")
def test_cell_borders_and_colours_land_in_mxlx_on_disk():
    fqn, mxlx = _seed_owned_template("E2ELayoutBorders1001")
    text = "E2E Bordered 1001"
    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": fqn,
        "template": {"cells": [
            {"row": 0, "col": 0, "text": text,
             "borders": {"left": {"style": "Double", "width": 2}, "bottom": "ThinDashed"},
             "backColor": {"red": 255, "green": 240, "blue": 200},
             "textColor": "Web.Red",
             "borderColor": {"red": 0, "green": 0, "blue": 128}},
        ]},
    })
    assert_ok(r, "author a bordered, coloured cell")
    _counts(r, "borders / colours author")

    doc = read_disk(mxlx)
    assert text in doc, "the bordered cell must be on disk when the call returns (%s): %r" % (mxlx, doc[:1500])
    # A line of the line pool, in the serializer's own attributes and style token.
    assert re.search(r'<line width="2" gap="false">\s*<v8ui:style xsi:type="v8ui:SpreadsheetDocumentCellLineType">'
                     r"Double</v8ui:style>", doc), "the Double width-2 line must be pooled in %s: %r" % (mxlx, doc[:1500])
    assert re.search(r"SpreadsheetDocumentCellLineType\">ThinDashed<", doc), \
        "the ThinDashed line must be pooled in %s: %r" % (mxlx, doc[:1500])
    # Follow the cell to ITS format and each side to ITS line: swapping left and bottom must fail.
    cell_format = _cell_format(doc, 0, 0)
    assert _side_line(doc, cell_format, "leftBorder") == ("2", "Double"), \
        "the left side must reference the Double width-2 line in %s: %r" % (mxlx, cell_format)
    assert _side_line(doc, cell_format, "bottomBorder") == ("1", "ThinDashed"), \
        "the bottom side must reference the ThinDashed line in %s: %r" % (mxlx, cell_format)
    for element in ("topBorder", "rightBorder"):
        assert "<%s>" % element not in cell_format, "an unnamed side must stay unset in %s: %r" % (mxlx, cell_format)
    assert "<backColor>#FFF0C8</backColor>" in doc, "the RGB back colour must be written inline in %s: %r" % (
        mxlx, doc[:1500])
    assert "<borderColor>#000080</borderColor>" in doc, "the border colour must be written in %s: %r" % (
        mxlx, doc[:1500])
    assert re.search(r"<textColor[^>]*>[^<]*:Red</textColor>", doc), \
        "the platform web colour must be written by name in %s: %r" % (mxlx, doc[:1500])

    wait_for_project_ready()
    shot = call("get_template_screenshot", {"projectName": PROJECT, "templatePath": fqn})
    assert not shot.is_error, "the bordered template must render; got: %r" % (shot.error_text()[:300],)
    assert _png_ok(shot), "the render must return a PNG; got: %r" % (str(shot.raw)[:300],)


@e2e_test(tool="template_content", kind="write-metadata")
def test_back_colour_names_a_configuration_style_item():
    style = "E2EAccent1001"
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "StyleItem." + style})
    assert_ok(r, "seed a configuration style item")
    r = call("modify_metadata", {"projectName": PROJECT, "fqn": "StyleItem." + style,
                                 "properties": [{"name": "value",
                                                 "value": {"color": {"red": 10, "green": 20, "blue": 30}}}]})
    assert_ok(r, "give the style item a colour")
    wait_for_project_ready()
    fqn, mxlx = _seed_owned_template("E2ELayoutStyle1001")
    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": fqn,
        "template": {"cells": [{"row": 0, "col": 0, "text": "Styled 1001", "backColor": "Style." + style}]},
    })
    assert_ok(r, "a back colour naming the configuration style item")
    doc = read_disk(mxlx)
    assert "Styled 1001" in doc, "the styled cell must be on disk when the call returns (%s): %r" % (mxlx, doc[:1500])
    assert re.search(r"<backColor[^>]*>[^<]*%s</backColor>" % style, doc), \
        "the back colour must reference the style item by name, not copy its RGB, in %s: %r" % (mxlx, doc[:1500])
    assert "#0A141E" not in doc, "the style item's RGB must not be inlined in %s: %r" % (mxlx, doc[:1500])


@e2e_test(tool="template_content", kind="write-metadata")
def test_unknown_line_style_is_refused_naming_the_platform_styles():
    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": FIXTURE_TEMPLATE,
        "template": {"cells": [{"row": 0, "col": 0, "text": "x", "borders": "Wavy1001"}]},
    })
    e = assert_error(r, "an unknown border line style")
    assert_error_quality(e, names=["Wavy1001"], suggests=["Solid", "ThinDashed"], ctx="unknown line style")
    assert_no_diff("a refused border write must change nothing on disk")


# ══════════════════════════════════════════════════════════════════════════════
# Happy — unmerge an existing merge and author a new layout in the same call
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="template_content", kind="write-metadata")
def test_unmerge_removes_the_old_merge_and_a_new_one_is_written():
    fqn, mxlx = _seed_owned_template("E2ELayoutUnmerge1001")
    title = "E2E Unmerge 1001"
    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": fqn,
        "template": {"cells": [{"row": 0, "col": 0, "text": title, "borders": "Solid"}],
                     "merges": [{"fromRow": 0, "fromCol": 0, "toRow": 1, "toCol": 3}]},
    })
    assert_ok(r, "author the old layout: one merge over rows 0..1, columns 0..3")
    doc = read_disk(mxlx)
    assert _merges(doc) == [(0, 0, 1, 3)], "the old merge must be on disk first (%s): %r" % (mxlx, doc[:1500])

    # An inner cell is enough: every merge touching the range goes, as with EDT's Unmerge on a selection.
    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": fqn,
        "template": {"unmerges": [{"fromRow": 1, "fromCol": 2, "toRow": 1, "toCol": 2}],
                     "merges": [{"fromRow": 0, "fromCol": 0, "toRow": 0, "toCol": 1}]},
    })
    assert_ok(r, "unmerge the old range and merge a narrower one")
    counts = _counts(r, "unmerge + merge")
    assert counts.get("unmerges") == 1, "one existing merge touched the range: %r" % (counts,)
    assert counts.get("merges") == 1, "one new merge was added: %r" % (counts,)

    doc = read_disk(mxlx)
    assert _merges(doc) == [(0, 0, 0, 1)], \
        "only the new merge is on disk when the call returns (%s): %r" % (mxlx, doc[:1500])
    assert title in doc, "the merged cell's text stays with its top-left cell in %s: %r" % (mxlx, doc[:1500])

    # MODEL read-back: the old merge is gone from the model too, so unmerging its former inner cell again
    # touches nothing, while the new merge is still found.
    r = call("template_content", {"method": "modify", "projectName": PROJECT, "fqn": fqn,
                                 "template": {"unmerges": [{"fromRow": 1, "fromCol": 2, "toRow": 1, "toCol": 2}]}})
    assert_ok(r, "unmerge the former inner cell again")
    assert _counts(r, "repeat unmerge").get("unmerges") == 0, "the old merge must be gone from the model: %r" % (
        r.structured,)
    r = call("template_content", {"method": "modify", "projectName": PROJECT, "fqn": fqn,
                                 "template": {"unmerges": [{"fromRow": 0, "fromCol": 1, "toRow": 0, "toCol": 1}]}})
    assert_ok(r, "unmerge the new merge")
    assert _counts(r, "unmerge the new merge").get("unmerges") == 1, "the new merge must be in the model: %r" % (
        r.structured,)


# ══════════════════════════════════════════════════════════════════════════════
# Happy — remove one named area and rename another
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="template_content", kind="write-metadata")
def test_remove_and_rename_named_areas_on_disk():
    fqn, mxlx = _seed_owned_template("E2ELayoutAreas1001")
    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": fqn,
        "template": {"cells": [{"row": 0, "col": 0, "text": "Areas 1001"}],
                     "areas": [{"name": "E2EHeader1001", "fromRow": 0, "fromCol": 0, "toRow": 0, "toCol": 3},
                               {"name": "E2EFooter1001", "fromRow": 4, "fromCol": 0, "toRow": 4, "toCol": 3}]},
    })
    assert_ok(r, "author two named areas")
    doc = read_disk(mxlx)
    assert "E2EHeader1001" in doc and "E2EFooter1001" in doc, "the two areas must be on disk first (%s): %r" % (
        mxlx, doc[:1500])

    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": fqn,
        "template": {"removeAreas": ["E2EFooter1001"],
                     "renameAreas": [{"name": "E2EHeader1001", "newName": "E2ETitle1001"}]},
    })
    assert_ok(r, "remove one area and rename the other")
    counts = _counts(r, "remove + rename areas")
    assert counts.get("removedAreas") == 1 and counts.get("renamedAreas") == 1, \
        "one area removed, one renamed: %r" % (counts,)

    doc = read_disk(mxlx)
    assert "E2ETitle1001" in doc and "<namedItem" in doc, \
        "the renamed area must be a named item on disk when the call returns (%s): %r" % (mxlx, doc[:1500])
    for gone in ("E2EHeader1001", "E2EFooter1001"):
        assert gone not in doc, "%s must be gone from %s: %r" % (gone, mxlx, doc[:1500])

    # MODEL read-back: the old names are gone from the model, the new one is there.
    r = call("template_content", {"method": "modify", "projectName": PROJECT, "fqn": fqn,
                                 "template": {"removeAreas": ["E2EHeader1001"]}})
    e = assert_error(r, "the old name is no longer an area")
    assert_error_quality(e, names=["E2EHeader1001", "E2ETitle1001"], suggests=["Existing areas"],
                         ctx="the model lists the renamed area, not the old names")
    assert "E2EFooter1001" not in e, "the removed area must not be listed as existing: %r" % (e,)


@e2e_test(tool="template_content", kind="write-metadata")
def test_named_area_types_are_persisted_and_invalid_axes_do_not_write():
    fqn, mxlx = _seed_owned_template("E2ENamedAreaTypes1034")
    args = {"method": "modify", "projectName": PROJECT, "fqn": fqn}
    result = call("template_content", {**args, "template": {
        "columnWidths": [{"col": 0, "width": 33}, {"col": 1, "width": 64}],
        "areas": [
            {"name": "Header1034", "type": "Rows", "fromRow": 0, "toRow": 2},
            {"name": "Side1034", "type": "Columns", "fromCol": 1, "toCol": 3},
            {"name": "OldStyle1034", "fromRow": 1, "toRow": 3, "fromCol": 0, "toCol": 2},
        ],
    }})
    assert_ok(result, "author all three named area types")
    assert _counts(result, "named area types")["areas"] == 3
    saved = read_disk(mxlx)
    doc = ET.fromstring(saved)
    named = {item.findtext("{*}name"): item.find("{*}area") for item in doc.findall("{*}namedItem")}
    for name, kind, coords in [
        ("Header1034", "Rows", ("0", "2", "-1", "-1")),
        ("Side1034", "Columns", ("-1", "-1", "1", "3")),
        ("OldStyle1034", "Rectangle", ("1", "3", "0", "2")),
    ]:
        area = named[name]
        assert area.findtext("{*}type") == kind
        assert tuple(area.findtext("{*}" + key) for key in
                     ("beginRow", "endRow", "beginColumn", "endColumn")) == coords

    result = call("template_content", {**args, "template": {
        "cells": [{"row": 0, "col": 0, "text": "Must not be written"}],
        "areas": [{"name": "Bad1034", "type": "Rows", "fromRow": 0, "toRow": 2, "toCol": 1}],
    }})
    assert_error(result, "opposite-axis coordinates on a row area")
    assert read_disk(mxlx) == saved, "invalid area parameters must not persist any part of the write"


@e2e_test(tool="template_content", kind="write-metadata")
def test_removing_an_unknown_area_is_refused_and_writes_nothing():
    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": FIXTURE_TEMPLATE,
        "template": {"cells": [{"row": 0, "col": 0, "text": "never written 1001"}],
                     "removeAreas": ["E2ENoSuchArea1001"]},
    })
    e = assert_error(r, "removing an area the template does not have")
    assert_error_quality(e, names=["E2ENoSuchArea1001"], suggests=["Existing areas"],
                         ctx="unknown area names the value and lists the existing ones")
    assert_no_diff("a refused area removal must change nothing on disk, not even the cell of the same call")
