"""
e2e tests for modify_metadata authoring a SpreadsheetDocument (.mxlx) print-form template's CONTENT
(#245).

A 1C template (макет) of type SpreadsheetDocument is a POOLED cell grid — text / parameter cells with
per-cell formatting (bold / font size / alignment / word-wrap), cell MERGES, named AREAS, and column /
row sizes. #245 lets that content be AUTHORED through modify_metadata via a sibling `template` payload
on a template FQN (a BasicTemplate — a common `CommonTemplate.<Name>` OR an object-owned
`<Type>.<Owner>.Template.<Name>`), mirroring the Role `rights[]` / CommonAttribute `content[]`
precedent — NO new tool:

    modify_metadata(fqn="CommonTemplate.X" | "Catalog.Y.Template.Z",
                    template={
                        cells:  [{row, col, text?|parameter?, bold?, fontSize?, hAlign?, vAlign?, wrap?}],
                        merges: [{fromRow, fromCol, toRow, toCol}],
                        areas:  [{name, fromRow, fromCol, toRow, toCol}],
                        columnWidths: [...], rowHeights: [...] })

The write goes through a BM write transaction and force-exports the template so its CONTENT resource
(the `Template.mxlx` moxel file that lives BESIDE the .mdo, not inline in it) drains to disk — the
load-bearing proof here, the #239 force-export-target lesson: a change committed only in-memory is
silently discarded on the next refresh. The success payload reports action="modified" like every other
modify_metadata payload.

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

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
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
    """A modify_metadata template write reports the shared modified envelope (action='modified')."""
    assert r.structured is not None, "%s: JSON tool must return structuredContent: %r" % (ctx, r.raw)
    assert r.structured.get("action") == "modified", "%s: must report modified: %r" % (ctx, r.structured)


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


# The v1 template writer stores a cell's LITERAL text under the platform's language-NEUTRAL key "#"
# (SheetFactory.DEFAULT_LANGUAGE) so it renders for EVERY viewing language: the moxel text reader
# resolves content.get(currentLanguage) then falls back to content.get("#"). On disk that neutral
# entry serializes as a <v8:item> whose <v8:lang> is "#". A bare `text in doc` is NOT enough — the
# substring is present no matter which <v8:lang> key stores the text (the shipped fixtures key their
# cell text under "en"), so a regression that keyed authored text under "en"/"" would render BLANK yet
# still pass the substring gate. This pins the text to the neutral "#" item — the deterministic half
# of the render proof; actual pixel visibility is live-verified with get_template_screenshot.
_NEUTRAL_TEXT_RE_TMPL = r"<v8:lang>#</v8:lang>\s*<v8:content>%s</v8:content>"


def _assert_text_under_neutral_key(doc, text, mxlx):
    """Fail unless the authored cell text is serialized under the language-neutral '#' key (the key that
    renders for every viewing language), not under a specific language code like the fixtures' 'en'."""
    pattern = re.compile(_NEUTRAL_TEXT_RE_TMPL % re.escape(text))
    assert pattern.search(doc), (
        "the authored cell text must be stored under the language-neutral '#' key (so it renders for "
        "every viewing language), not under 'en'/'' like the fixtures, in %s: %r" % (mxlx, doc[:600]))


# ══════════════════════════════════════════════════════════════════════════════
# Happy — author cells (text + a formatted parameter) + a merge + a named area; the
# content DRAINS to the template's own .mxlx moxel resource on disk
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_template_content_lands_in_mxlx_on_disk():
    fqn, mxlx = _seed_owned_template("E2EMxlxContent")
    text = "E2E MXLX Cell 245"
    parameter = "E2EMxlxParam245"
    area = "E2EMxlxArea245"

    # Distinctive column-width / row-height values so their on-disk serialization (a size-bearing
    # <format>) is unambiguous — a fresh empty template has neither.
    col_width = 137
    row_height = 43

    r = call("modify_metadata", {
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
    # Not a bare `text in doc` (satisfied under ANY <v8:lang> key): pin the cell text to the
    # language-NEUTRAL "#" item — the key that renders for every viewing language. Text keyed under
    # "en"/"" (like the shipped fixtures) would still contain the substring yet render blank; this
    # fails it. This is the on-disk half of the render proof (pixel visibility is live-verified).
    _assert_text_under_neutral_key(doc, text, mxlx)
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
# Happy — round-trip smoke: an authored template RENDERS via get_template_screenshot
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_authored_template_renders_via_screenshot():
    fqn, mxlx = _seed_owned_template("E2EMxlxRender")
    text = "Rendered 245"
    r = call("modify_metadata", {
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

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_bad_alignment_enum_is_error():
    # An unrecognized hAlign token is rejected UP FRONT (before any BM write), naming the bad value and
    # listing the accepted alignment literals. Targets the pristine fixture template so a rejected write
    # can be proven to change nothing on disk.
    r = call("modify_metadata", {
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

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_template_payload_on_non_template_fqn_is_error():
    # A `template` payload is only valid for a BasicTemplate FQN. Addressed to a Catalog (a real object
    # of the WRONG kind), it is refused UP FRONT naming the FQN — it must NOT fall through to the generic
    # property path (which, with no properties, would report a false success and drop the payload).
    r = call("modify_metadata", {
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
def test_template_mixed_with_properties_is_error():
    # Like the Role rights[] / content[] dispatch guards, a `template` change is its own surface and
    # cannot be mixed with a generic `properties` change in one call — the two are applied separately.
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": FIXTURE_TEMPLATE,
        "template": {"cells": [{"row": 0, "col": 0, "text": "x"}]},
        "properties": [{"name": "comment", "value": "y"}],
    })
    e = assert_error(r, "a template payload mixed with a properties change")
    assert_error_quality(e, suggests=["cannot be combined", "separately"],
                         ctx="a template change cannot be combined with a generic properties change")
    assert_no_diff("a rejected mixed template+properties call must change nothing on disk")
