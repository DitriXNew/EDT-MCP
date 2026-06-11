"""
e2e tests for modify_metadata setting a StyleItem's value (Color / Font).

A StyleItem (created generically with create_metadata) has no value yet. modify_metadata sets its
`value` property to a STRUCTURED object with EITHER a `color` OR a `font` member; the style item's
`type` (Color / Font) is synced automatically. get_metadata_details then renders the assigned value
under a "Value" section (Style Type + Color RGB(r, g, b) / Auto, or the Font face / height / flags).

reset: kind="write-metadata" -> reset_model() after each test.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)


def _seed_style_item(name):
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "StyleItem." + name})
    assert_ok(r, "seed style item " + name)
    wait_for_project_ready()


def _details_text(fqn):
    r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
    assert_ok(r, "get_metadata_details for " + fqn)
    return r.text


# ──────────────────────────────────────────────────────────────────────────────
# Happy — set an explicit RGB color, then read it back via get_metadata_details
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_style_item_explicit_color_and_read_back():
    name = "E2EStyleColor"
    _seed_style_item(name)
    fqn = "StyleItem." + name
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "properties": [{"name": "value", "value": {"color": {"red": 255, "green": 0, "blue": 0}}}],
    })
    assert_ok(r, "set an explicit RGB color on the style item")
    assert r.structured.get("action") == "modified", "must report modified: %r" % (r.structured,)
    assert "value" in (r.structured.get("applied") or []), "value must be applied: %r" % (r.structured,)

    text = _details_text(fqn)
    assert_contains(text, "Value", "the details must render a Value section")
    assert_contains(text, "Color", "the Style Type must be Color")
    assert_contains(text, "RGB(255, 0, 0)", "the assigned RGB color must be rendered")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — set the automatic color (renders as Auto, never RGB(0,0,0))
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_style_item_auto_color():
    name = "E2EStyleAuto"
    _seed_style_item(name)
    fqn = "StyleItem." + name
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "properties": [{"name": "value", "value": {"color": "auto"}}],
    })
    assert_ok(r, "set the automatic color on the style item")
    assert "value" in (r.structured.get("applied") or []), "value must be applied: %r" % (r.structured,)

    text = _details_text(fqn)
    assert_contains(text, "Auto", "the automatic color must render as Auto")
    assert "RGB(0, 0, 0)" not in text, \
        "the automatic color must NOT render as RGB(0, 0, 0):\n%s" % text


# ──────────────────────────────────────────────────────────────────────────────
# Happy — set a font (face / height / flags), then read it back
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_style_item_font_and_read_back():
    name = "E2EStyleFont"
    _seed_style_item(name)
    fqn = "StyleItem." + name
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "properties": [{"name": "value",
                        "value": {"font": {"faceName": "Arial", "height": 12, "bold": True}}}],
    })
    assert_ok(r, "set a font on the style item")
    assert "value" in (r.structured.get("applied") or []), "value must be applied: %r" % (r.structured,)

    text = _details_text(fqn)
    assert_contains(text, "Font", "the Style Type must be Font")
    assert_contains(text, "Arial", "the font face name must be rendered")
    assert_contains(text, "bold", "the bold flag must be rendered")


# ──────────────────────────────────────────────────────────────────────────────
# Discovery — the value property appears in the assignable schema as STYLE_VALUE
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="read")
def test_style_item_value_is_assignable_style_value():
    name = "E2EStyleAssign"
    _seed_style_item(name)
    r = call("get_metadata_details",
             {"projectName": PROJECT, "objectFqns": ["StyleItem." + name], "assignable": True})
    assert_ok(r, "get_metadata_details(assignable) for the style item")
    assert_contains(r.text, "value", "the value property must be listed as assignable")
    assert_contains(r.text, "STYLE_VALUE", "the value property must report its STYLE_VALUE kind")


# ──────────────────────────────────────────────────────────────────────────────
# Negative — invalid shapes are clean, actionable errors
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_style_item_out_of_range_rgb_is_error():
    name = "E2EStyleBadRgb"
    _seed_style_item(name)
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "StyleItem." + name,
        "properties": [{"name": "value", "value": {"color": {"red": 300, "green": 0, "blue": 0}}}],
    })
    e = assert_error(r, "out-of-range RGB component")
    assert_error_quality(e, names=["300"], suggests=["range"],
                         ctx="an out-of-range RGB component is a clean, actionable error")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_style_item_color_and_font_together_is_error():
    name = "E2EStyleBoth"
    _seed_style_item(name)
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "StyleItem." + name,
        "properties": [{"name": "value", "value": {"color": "auto", "font": {"bold": True}}}],
    })
    e = assert_error(r, "color + font together")
    assert_error_quality(e, suggests=["EITHER", "not both"],
                         ctx="setting both color and font is rejected")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_style_item_empty_font_is_error():
    name = "E2EStyleEmptyFont"
    _seed_style_item(name)
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "StyleItem." + name,
        "properties": [{"name": "value", "value": {"font": {}}}],
    })
    e = assert_error(r, "empty font")
    assert_error_quality(e, suggests=["faceName", "height"],
                         ctx="a font with no face / height / flag is rejected")
