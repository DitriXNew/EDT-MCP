"""
e2e tests for template_content authoring a SpreadsheetDocument (.mxlx) print-form template's CONTENT
(#245).

A 1C template (макет) of type SpreadsheetDocument is a POOLED cell grid — text / parameter cells with
per-cell formatting (bold / font size / alignment / word-wrap), cell MERGES, named AREAS, and column /
row sizes. #245 lets that content be AUTHORED through template_content via its `template` payload
on a template FQN (a BasicTemplate — a common `CommonTemplate.<Name>` OR an object-owned
`<Type>.<Owner>.Template.<Name>`), mirroring the Role `rights[]` / CommonAttribute `content[]`
precedent — NO new tool:

    template_content(method="modify", fqn="CommonTemplate.X" | "Catalog.Y.Template.Z",
                    template={
                        cells:  [{row, col, text?|parameter?, bold?, fontSize?, hAlign?, vAlign?,
                                  wrap?, textOrientation?, autoIndent?, autoMarkIncomplete?}],
                        merges: [{fromRow, fromCol, toRow, toCol}],
                        areas:  [{name, fromRow, fromCol, toRow, toCol}],
                        columnWidths: [{col, width?, autoWidthCalculation?, widthWeightFactor?}],
                        rowHeights: [...] })

The write goes through a BM write transaction and force-exports the template so its CONTENT resource
(the `Template.mxlx` moxel file that lives BESIDE the .mdo, not inline in it) drains to disk — the
load-bearing proof here, the #239 force-export-target lesson: a change committed only in-memory is
silently discarded on the next refresh. The success payload reports action="modified" like every other
template_content payload.

reset: kind="write-metadata" -> reset_fixture()+reset_model() after each test.

FIXTURE TRUTH (TestConfiguration, English Names)
  - CommonTemplate.PrintForm  -> an EXISTING, content-bearing SpreadsheetDocument common template
    (src/CommonTemplates/PrintForm/Template.mxlx). Used by the negative tests as a real template FQN
    that a rejected write must leave byte-for-byte untouched (assert_no_diff).
  - Catalog.Catalog           -> a real Catalog that HOSTS owned templates AND is itself a non-template
    FQN (the wrong-kind target for the "template payload on a non-template FQN" negative).

The happy paths SEED a FRESH EMPTY owned template on Catalog.Catalog (create_metadata
Catalog.Catalog.Template.<Name>) rather than reuse a fixture one: an object-owned template is created
through the parent-aware model-object factory that GUARANTEES its TemplateType is SPREADSHEET_DOCUMENT
(so it resolves + renders), and an empty template carries NO cells / NO <merge>, so the authored text /
parameter / <merge> landing on disk is a clean anti-cheat (a no-op would fail the diff). The created
top object is reverted by the write-metadata reset (reset_fixture git-clean + reset_model clean_project).
"""

import base64
import re
from xml.etree import ElementTree

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    tree_snapshot,
    assert_tree_unchanged,
    poll_diff_contains,
    read_disk,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)

# A real, content-bearing SpreadsheetDocument common template shipped in the fixture — a valid template
# FQN the negative tests target so a REJECTED write can be proven to change nothing (assert_no_diff).
FIXTURE_TEMPLATE = "CommonTemplate.PrintForm"
# A real object that is NOT a template (a Catalog) — the wrong-kind target for the non-template negative.
NON_TEMPLATE_FQN = "Catalog.Catalog"


def _seed_owned_template(name):
    """Create a FRESH EMPTY owned SpreadsheetDocument template on Catalog.Catalog and wait for the
    derived-data rebuild to settle. Returns (fqn, mxlx_relpath). The owned-template create path runs
    through the model-object factory that sets TemplateType.SPREADSHEET_DOCUMENT, so the seed is a valid,
    renderable, EMPTY template — the clean canvas the happy paths author onto."""
    fqn = "Catalog.Catalog.Template." + name
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, "seed owned template " + fqn)
    wait_for_project_ready()
    # The moxel content resource sits beside the owner, one folder per template (like the fixture's
    # Catalog.Catalog.Template.Invoice at src/Catalogs/Catalog/Templates/Invoice/Template.mxlx).
    mxlx = "src/Catalogs/Catalog/Templates/%s/Template.mxlx" % name
    return fqn, mxlx


def _template_result(r, ctx):
    """A template_content write reports the shared modified envelope (action='modified')."""
    assert r.structured is not None, "%s: JSON tool must return structuredContent: %r" % (ctx, r.raw)
    assert r.structured.get("action") == "modified", "%s: must report modified: %r" % (ctx, r.structured)
    assert r.structured.get("method") == "modify", r.structured


def _png_ok(r):
    """True when the result carries a genuine PNG image blob at content[0].resource.blob (an 8-byte
    PNG signature). Used by the round-trip render smoke — a populated template must RENDER, not error."""
    res = r.raw.get("result") if isinstance(r.raw, dict) else None
    content = (res or {}).get("content") or []
    if not (content and isinstance(content[0], dict)):
        return False
    resource = content[0].get("resource")
    blob = resource.get("blob") if isinstance(resource, dict) else None
    if not blob:
        return False
    try:
        return base64.b64decode(blob)[:8] == b"\x89PNG\r\n\x1a\n"
    except Exception:
        return False


# The neutral key is EDT's fallback; a concrete config language key is what
# lets a 1C session read the same authored cell text (#1039).
def _assert_text_under_language_key(doc, text, mxlx, code):
    pattern = r"<v8:lang>%s</v8:lang>\s*<v8:content>%s</v8:content>" % (
        re.escape(code), re.escape(text))
    assert re.search(pattern, doc), (
        "the authored cell text must be stored under %r in %s: %r" % (code, mxlx, doc[:1000]))


def _assert_text_under_neutral_key(doc, text, mxlx):
    _assert_text_under_language_key(doc, text, mxlx, "#")


# ══════════════════════════════════════════════════════════════════════════════
# Happy — author cells (text + a formatted parameter) + a merge + a named area; the
# content DRAINS to the template's own .mxlx moxel resource on disk
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="template_content", kind="write-metadata")
def test_template_content_lands_in_mxlx_on_disk():
    fqn, mxlx = _seed_owned_template("E2EMxlxContent")
    text = "E2E MXLX Cell 245"
    parameter = "E2EMxlxParam245"
    area = "E2EMxlxArea245"

    # Distinctive column-width / row-height values so their on-disk serialization (a size-bearing
    # <format>) is unambiguous — a fresh empty template has neither.
    col_width = 137
    row_height = 43

    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": fqn,
        "template": {
            "cells": [
                {"row": 0, "col": 0, "text": text},
                # a parameter cell with formatting: bold + centered (exercises the pooled font/format
                # interning + HorizontalAlignment enum on the same cell)
                {"row": 1, "col": 0, "parameter": parameter, "bold": True, "hAlign": "Center"},
            ],
            "merges": [{"fromRow": 0, "fromCol": 0, "toRow": 0, "toCol": 3}],
            "areas": [{"name": area, "fromRow": 0, "fromCol": 0, "toRow": 1, "toCol": 3}],
            "columnWidths": [{"col": 2, "width": col_width}],
            "rowHeights": [{"row": 4, "height": row_height}],
        },
    })
    assert_ok(r, "author cells + a merge + a named area + a column width + a row height on the empty template")
    _template_result(r, "template content author")

    # ON-DISK: wait for the force-export to flush the content resource, then pin every authored element
    # in the template's OWN .mxlx (NOT the .mdo) — this is the #239 proof that the moxel content drained
    # to its separate resource, not just committed in-memory.
    poll_diff_contains(text, ctx="the authored cell text must flush to the template's .mxlx on disk")
    doc = read_disk(mxlx)
    # The fixture's configured language code is en (the object name is English).
    # Requiring both keys catches a regression that only works in EDT rendering.
    _assert_text_under_neutral_key(doc, text, mxlx)
    _assert_text_under_language_key(doc, text, mxlx, "en")
    assert parameter in doc, \
        "the parameter cell must land in the same .mxlx (%s): %r" % (mxlx, doc[:400])
    # A fresh empty template has NO <merge>, so its presence proves the authored merge wrote (anti-cheat).
    assert "<merge>" in doc, \
        "the authored merge must serialize as a <merge> block in the .mxlx (%s): %r" % (mxlx, doc[:400])
    assert area in doc, \
        "the named area must serialize into the .mxlx (%s): %r" % (mxlx, doc[:400])
    # Column width / row height land on a size-bearing <format> in the SAME .mxlx (an empty template has
    # neither), so their distinctive values prove the col/row-size path drained to disk too — closing the
    # gap that the on-disk gate previously only covered cells / merges / areas.
    assert "<width>%d</width>" % col_width in doc, \
        "the authored column width must serialize as a <format> width in the .mxlx (%s): %r" % (mxlx, doc[:600])
    assert "<height>%d</height>" % row_height in doc, \
        "the authored row height must serialize as a <format> height in the .mxlx (%s): %r" % (mxlx, doc[:600])


# ══════════════════════════════════════════════════════════════════════════════
# Happy — the CELL-FORMAT and COLUMN-SIZING properties (#461) reach the .mxlx on disk
# in EXACTLY the platform's own spelling and unit
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="template_content", kind="write-metadata")
def test_cell_rotation_and_auto_column_width_land_in_mxlx_on_disk():
    fqn, mxlx = _seed_owned_template("E2EMxlxFormat")

    # 900 is the PLATFORM unit (tenths of a degree) for 90 degrees: EDT's cell-property editor shows
    # degrees and multiplies by ten before storing. If the writer ever "helpfully" converted degrees,
    # the file would carry 90 (== 9 degrees) — asserted absent below.
    rotation = 900
    auto_indent = 7
    weight = 3

    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": fqn,
        "template": {
            "cells": [
                {"row": 0, "col": 0, "text": "Rotated 461",
                 "textOrientation": rotation, "autoIndent": auto_indent, "autoMarkIncomplete": True},
            ],
            "columnWidths": [{"col": 0, "autoWidthCalculation": True, "widthWeightFactor": weight}],
        },
    })
    assert_ok(r, "author a rotated cell + an auto-width column")
    _template_result(r, "cell rotation / auto column width author")

    poll_diff_contains("<textOrientation>", ctx="the rotation must flush to the template's .mxlx on disk")
    doc = read_disk(mxlx)
    # EXACT serialized form, not a substring of the value: V8MoxelSerializer writes each Format attribute
    # as its own element, guarded by isSetXxx(), so these elements exist only if the value really landed.
    for element, value in (
            ("textOrientation", rotation),
            ("autoIndent", auto_indent),
            ("widthWeightFactor", weight)):
        assert "<%s>%d</%s>" % (element, value, element) in doc, \
            "<%s>%d</%s> must serialize into the .mxlx (%s): %r" % (element, value, element, mxlx, doc[:900])
    for element in ("autoMarkIncomplete", "autoWidthCalculation"):
        assert "<%s>true</%s>" % (element, element) in doc, \
            "<%s>true</%s> must serialize into the .mxlx (%s): %r" % (element, element, mxlx, doc[:900])
    # Anti-cheat on the UNIT: the degree reading (90) must NOT be what was written.
    assert "<textOrientation>90</textOrientation>" not in doc, \
        "the rotation must stay in the platform's tenths-of-a-degree unit, not be rescaled to degrees " \
        "(%s): %r" % (mxlx, doc[:900])


# ══════════════════════════════════════════════════════════════════════════════
# Negative — a width weight factor with no auto width calculation is REFUSED, not
# written where the platform never reads it
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="template_content", kind="write-metadata")
def test_width_weight_factor_without_auto_width_is_refused():
    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": FIXTURE_TEMPLATE,
        "template": {"columnWidths": [{"col": 0, "width": 40, "widthWeightFactor": 3}]},
    })
    e = assert_error(r, "a width weight factor without auto width calculation")
    assert_error_quality(e, names=["widthWeightFactor", "3"], suggests=["autoWidthCalculation"],
                         ctx="weight factor without auto width")
    assert_no_diff(ctx="a refused template write must leave the fixture template untouched")


# ══════════════════════════════════════════════════════════════════════════════
# Happy — round-trip smoke: an authored template RENDERS via get_template_screenshot
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="template_content", kind="write-metadata")
def test_authored_template_renders_via_screenshot():
    fqn, mxlx = _seed_owned_template("E2EMxlxRender")
    text = "Rendered 245"
    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": fqn,
        "template": {"cells": [{"row": 0, "col": 0, "text": text}]},
    })
    assert_ok(r, "author a cell to render")
    _template_result(r, "template content author for render")

    # Deterministic half of the render proof: the authored text must land under the language-neutral
    # "#" key the moxel reader falls back to for every viewing language. Without this the PNG smoke
    # below passes even if every cell renders BLANK (text under a wrong key). Pixel visibility itself is
    # live-verified with get_template_screenshot on the stand.
    poll_diff_contains(text, ctx="the render cell text must flush to the template's .mxlx on disk")
    _assert_text_under_neutral_key(read_disk(mxlx), text, mxlx)

    wait_for_project_ready()

    # Round-trip: the content just authored must be a valid SpreadsheetDocument the renderer can read
    # back and rasterize (the render is editor-free — an error here is a real bug, not a cold-editor flake).
    shot = call("get_template_screenshot", {"projectName": PROJECT, "templatePath": fqn})
    assert not shot.is_error, \
        "the authored template must render without error; got: %r" % (shot.error_text()[:300],)
    assert _png_ok(shot), \
        "the render must return a genuine PNG image blob; got: %r" % (str(shot.raw)[:300],)


# ══════════════════════════════════════════════════════════════════════════════
# Negative — a bad alignment enum names the value + lists the accepted literals
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="template_content", kind="write-metadata")
def test_bad_alignment_enum_is_error():
    # An unrecognized hAlign token is rejected UP FRONT (before any BM write), naming the bad value and
    # listing the accepted alignment literals. Targets the pristine fixture template so a rejected write
    # can be proven to change nothing on disk.
    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": FIXTURE_TEMPLATE,
        "template": {"cells": [{"row": 0, "col": 0, "text": "x", "hAlign": "BOGUS_zz"}]},
    })
    e = assert_error(r, "a bad hAlign enum token")
    assert_error_quality(e, names=["BOGUS_zz"], suggests=["Left", "Center", "Right"],
                         ctx="an unrecognized alignment token names the value and lists the literals")
    assert_no_diff("a rejected bad-enum template write must change nothing on disk")


# ══════════════════════════════════════════════════════════════════════════════
# Negative — a template payload on a NON-template FQN is rejected (wrong kind)
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="template_content", kind="write-metadata")
def test_template_payload_on_non_template_fqn_is_error():
    # A `template` payload is only valid for a BasicTemplate FQN. Addressed to a Catalog (a real object
    # of the WRONG kind), it is refused UP FRONT naming the FQN — it must NOT fall through to the generic
    # property path (which, with no properties, would report a false success and drop the payload).
    r = call("template_content", {
        "method": "modify",
        "projectName": PROJECT, "fqn": NON_TEMPLATE_FQN,
        "template": {"cells": [{"row": 0, "col": 0, "text": "x"}]},
    })
    e = assert_error(r, "a template payload on a non-template FQN")
    assert_error_quality(e, names=[NON_TEMPLATE_FQN], suggests=["template"],
                         ctx="a template payload on a non-template object names the FQN + a template hint")
    assert_no_diff("a rejected non-template template write must change nothing on disk")


# ══════════════════════════════════════════════════════════════════════════════
# Negative — a template payload cannot be combined with a generic 'properties' change
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_legacy_template_payload_is_migration_error():
    # The legacy authoring route must refuse explicitly instead of dropping template content.
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": FIXTURE_TEMPLATE,
        "template": {"cells": [{"row": 0, "col": 0, "text": "x"}]},
    })
    e = assert_error(r, "a legacy template payload")
    assert_error_quality(e, suggests=["template_content", "modify"],
                         ctx="legacy template authors are directed to the new facade")
    assert_no_diff("a rejected legacy template call must change nothing on disk")


@e2e_test(tool="template_content", kind="read")
def test_help_describes_both_template_surfaces_without_a_project():
    r = call("template_content", {"method": "help"})
    assert_ok(r, "template help without a project")
    doc = r.structured.get("help", "")
    assert "SpreadsheetDocument" in doc and "TextDocument" in doc, r.structured
    assert "renameAreas" in doc and "expectedHash" in doc, r.structured


@e2e_test(tool="template_content", kind="read")
def test_existing_common_spreadsheet_template_is_read_without_changes():
    r = call("template_content", {"method": "read", "projectName": PROJECT, "fqn": FIXTURE_TEMPLATE})
    assert_ok(r, "read existing common spreadsheet template")
    data = r.structured["template"]
    assert any(cell["type"] == "text" and cell["text"] == "E2E Print Template"
               for cell in data["cells"]), data
    assert data["merges"] == [{"type": "rectangle", "fromRow": 5, "fromCol": 1,
                               "toRow": 8, "toCol": 6}], data
    assert "writtenProjects" not in r.structured, r.structured
    assert_no_diff(ctx="reading a common spreadsheet template")


@e2e_test(tool="template_content", kind="write-metadata")
def test_read_owned_spreadsheet_template_area_and_parameter_round_trip():
    fqn, mxlx = _seed_owned_template("E2EMxlxRead")
    r = call("template_content", {
        "method": "modify", "projectName": PROJECT, "fqn": fqn,
        "template": {
            "cells": [{"row": 0, "col": 0, "text": "E2E Read"},
                      {"row": 1, "col": 0, "parameter": "ДолжностьРуководителя"},
                      {"row": 2, "col": 0, "text": "Outside"}],
            "merges": [{"fromRow": 0, "fromCol": 0, "toRow": 0, "toCol": 1}],
            "areas": [{"name": "Header", "fromRow": 0, "fromCol": 0, "toRow": 1, "toCol": 1}],
        },
    })
    assert_ok(r, "author owned spreadsheet template")
    poll_diff_contains("E2E Read", ctx="persist before checking read-only behavior")
    before = read_disk(mxlx)
    args = {"method": "read", "projectName": PROJECT,
            "fqn": fqn.replace("Catalog.", "Справочник.", 1).replace(".Template.", ".Макет."),
            "areaName": "header", "limit": 1}
    r = call("template_content", args)
    assert_ok(r, "read first named-area page with Russian structural FQN tokens")
    first = r.structured["template"]
    assert first["areaName"] == "Header", first
    assert first["areas"] == [{"name": "Header", "type": "rectangle", "fromRow": 0,
                               "fromCol": 0, "toRow": 1, "toCol": 1}], first
    assert first["cells"][0]["text"] == "E2E Read", first
    assert first["merges"][0]["toCol"] == 1, first
    assert "persisted" not in r.structured and "writtenProjects" not in r.structured, r.structured
    r = call("template_content", dict(args, offset=first["nextOffset"]))
    assert_ok(r, "read next page")
    next_page = r.structured["template"]
    assert next_page["cells"] == [{"row": 1, "col": 0, "type": "parameter",
                                   "parameter": "ДолжностьРуководителя"}], next_page
    assert "nextOffset" not in next_page, next_page
    assert read_disk(mxlx) == before, "read must not change the template resource"


@e2e_test(tool="template_content", kind="read")
def test_read_unknown_named_area_names_the_value_and_how_to_list_areas():
    missing = "E2EUnknownArea1019"
    r = call("template_content", {"method": "read", "projectName": PROJECT,
                                  "fqn": FIXTURE_TEMPLATE, "areaName": missing})
    error = assert_error(r, "read an unknown named cell area")
    assert_error_quality(error, names=[missing], suggests=["Omit areaName", "list", "areas"],
                         ctx="unknown area must name the value and explain how to discover cell areas")
    assert_no_diff("a refused area read must not change the fixture")


@e2e_test(tool="template_content", kind="read")
def test_read_non_template_fqn_names_the_target_and_template_address():
    r = call("template_content", {"method": "read", "projectName": PROJECT,
                                  "fqn": NON_TEMPLATE_FQN})
    error = assert_error(r, "read spreadsheet content from a Catalog rather than its template")
    assert_error_quality(error, names=[NON_TEMPLATE_FQN],
                         suggests=["CommonTemplate.<Name>", "<Type>.<Owner>.Template.<Name>"],
                         ctx="wrong-kind read must direct the caller to a template FQN")
    assert_no_diff("a wrong-kind template read must not change the fixture")


@e2e_test(tool="template_content", kind="write-metadata")
def test_read_text_document_refers_to_its_supported_reader_without_changes():
    name = "E2EReadTextRefusal1019"
    fqn = "Catalog.Catalog.Template." + name
    created = call("create_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "properties": [{"name": "templateType", "value": "TextDocument"}],
    })
    assert_ok(created, "seed a real TextDocument rather than a nonexistent template")
    # Creation submits export: prove its actual type on disk before another MCP call.
    owner = ElementTree.fromstring(read_disk("src/Catalogs/Catalog/Catalog.mdo"))
    definitions = [item for item in owner.findall("templates") if item.findtext("name") == name]
    assert len(definitions) == 1, "the created template must exist in its owner's descriptor"
    assert definitions[0].findtext("templateType") == "TextDocument", ElementTree.tostring(definitions[0])
    wait_for_project_ready()
    before = tree_snapshot()
    r = call("template_content", {"method": "read", "projectName": PROJECT, "fqn": fqn})
    error = assert_error(r, "SpreadsheetDocument reader must not silently accept a TextDocument")
    assert_error_quality(error, names=[fqn, "TextDocument"], suggests=["get_metadata_details"],
                         ctx="TextDocument read refusal points to its supported content reader")
    assert_tree_unchanged(before, "the refused read must not retype or otherwise modify the seeded template")


@e2e_test(tool="template_content", kind="read")
def test_read_pagination_rejects_out_of_range_values_without_changes():
    # Pin both lower/upper boundaries; a clamping implementation must not pass.
    for key, value, minimum, maximum in (
            ("limit", 0, 1, 200), ("limit", -1, 1, 200), ("limit", 201, 1, 200),
            ("offset", -1, 0, 2147483647), ("offset", 2147483648, 0, 2147483647)):
        args = {"method": "read", "projectName": PROJECT, "fqn": FIXTURE_TEMPLATE, key: value}
        error = assert_error(call("template_content", args), "out-of-range %s=%s" % (key, value))
        assert_error_quality(error, names=[key, str(value)],
                             suggests=[str(minimum if value < minimum else maximum)],
                             ctx="pagination refusal must name the rejected value and violated bound")
        assert_no_diff("a refused %s=%s read must not change the fixture" % (key, value))


@e2e_test(tool="template_content", kind="read")
def test_read_unknown_argument_names_the_key_and_supported_read_keys():
    key = "unrecognizedReadArgument1019"
    r = call("template_content", {"method": "read", "projectName": PROJECT,
                                  "fqn": FIXTURE_TEMPLATE, key: True})
    error = assert_error(r, "unknown read argument must not be silently ignored")
    assert_error_quality(error, names=[key], suggests=["areaName", "offset", "limit"],
                         ctx="unknown read argument names the key and lists the accepted read arguments")
    assert_no_diff("a refused unknown read argument must not change the fixture")


@e2e_test(tool="template_content", kind="read")
def test_read_native_detail_picture_and_drawing_parameters_without_changes():
    r = call("template_content", {"method": "read", "projectName": PROJECT,
                                  "fqn": "CommonTemplate.ReadParameters"})
    assert_ok(r, "read native parameter-only cells and drawing")
    template = r.structured["template"]
    cells = {(cell["row"], cell["col"]): cell for cell in template["cells"]}
    assert cells[(0, 0)]["detailParameter"] == "DetailCell", template
    assert cells[(0, 1)]["pictureParameter"] == "PictureCell", template
    # These are parameter-only native cells, not text cells concealing a skipped projection.
    for position in ((0, 0), (0, 1)):
        assert "text" not in cells[position] and "parameter" not in cells[position], cells[position]
    drawings = [drawing for drawing in template["drawings"] if drawing.get("name") == "DetailDrawing"]
    assert len(drawings) == 1, template
    drawing = drawings[0]
    assert drawing["type"] == "TextDrawing", drawing
    assert drawing["detailParameter"] == "DetailDrawingValue", drawing
    assert drawing["parameter"] == "DrawingParam", drawing
    assert "persisted" not in r.structured and "writtenProjects" not in r.structured, r.structured
    assert_no_diff("reading native cell and drawing parameters must not change the fixture")


@e2e_test(tool="template_content", kind="read")
def test_read_drawing_name_is_not_accepted_as_a_named_cell_area():
    args = {"method": "read", "projectName": PROJECT, "fqn": "CommonTemplate.ReadParameters"}
    r = call("template_content", args)
    assert_ok(r, "prove the rejected area name actually belongs to an existing native drawing")
    assert any(drawing.get("name") == "DetailDrawing"
               for drawing in r.structured["template"]["drawings"]), r.structured
    error = assert_error(call("template_content", dict(args, areaName="DetailDrawing")),
                         "drawing names must not be interpreted as named cell areas")
    assert_error_quality(error, names=["DetailDrawing"], suggests=["Omit areaName", "list", "areas"],
                         ctx="drawing-as-area refusal names the value and shows how to discover cell areas")
    assert_no_diff("a drawing-as-area refusal must not change the fixture")
