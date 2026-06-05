"""
e2e tests for modify_metadata (kind: write-metadata).

modify_metadata sets properties of a metadata node (object or member) addressed by a
1C full-name FQN, as properties=[{name, value, language?}]. It folds the former
set_metadata_property and adds VALIDATION: a non-assignable property is rejected WITH
the list of assignable properties; an out-of-range enum value is rejected WITH the
allowed literals; the `name` property is refused (use rename_metadata_object); the data
`type` is not yet settable. Nothing is written unless EVERY property validates.

JSON-responseType tool (payload in r.structured: {action:'modified', fqn, applied[],
persisted, message}). The assignable-property discovery lives in
get_metadata_details(assignable:true).

reset: kind="write-metadata" -> reset_model() after each test.

Fixture: Catalog.Catalog (attribute "Attribute"), CommonModule.Error/OK/Calc, ...
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    poll_diff_contains,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)


def _assignable_text(fqn):
    r = call("get_metadata_details",
             {"projectName": PROJECT, "objectFqns": [fqn], "assignable": True})
    assert_ok(r, "get_metadata_details(assignable) for %s" % fqn)
    return r.text


def _first_enum_with_value(fqn):
    """Parse the assignable table for the first ENUM property and its first allowed value."""
    for line in _assignable_text(fqn).splitlines():
        if "| ENUM |" not in line:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        # cells: [Property, Kind, Current, Allowed values]
        if len(cells) >= 4 and cells[1] == "ENUM" and cells[3] and cells[3] != "—":
            allowed = [a.strip() for a in cells[3].split(",") if a.strip()]
            if allowed:
                return cells[0], allowed[0]
    return None, None


# ──────────────────────────────────────────────────────────────────────────────
# Happy — set scalar/synonym (verified by structured echo + disk)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_comment_persists():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog",
        "properties": [{"name": "comment", "value": "E2E modify comment"}],
    })
    assert_ok(r, "set comment on Catalog.Catalog")
    assert r.structured.get("action") == "modified", "must report modified: %r" % (r.structured,)
    assert "comment" in (r.structured.get("applied") or []), "comment must be in applied: %r" % (r.structured,)
    poll_diff_contains("E2E modify comment",
                       ctx="the comment must land in Catalog.Catalog.mdo on disk")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_synonym_with_language():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog",
        "properties": [{"name": "synonym", "value": "E2ESynonymMod", "language": "en"}],
    })
    assert_ok(r, "set synonym on Catalog.Catalog")
    assert "synonym" in (r.structured.get("applied") or []), "synonym must be applied: %r" % (r.structured,)
    poll_diff_contains("E2ESynonymMod", ctx="the synonym must land on disk")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_enum_on_attribute_discovered_value():
    # Seed an attribute, discover one of its enum properties + an allowed value, then set it.
    attr = "E2EModEnumAttr"
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr})
    assert_ok(cr, "seed attribute")
    wait_for_project_ready()

    fqn = "Catalog.Catalog.Attribute." + attr
    prop, value = _first_enum_with_value(fqn)
    assert prop is not None, "the attribute must expose an enum property with allowed values"

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "properties": [{"name": prop, "value": value}],
    })
    assert_ok(r, "set enum %s=%s" % (prop, value))
    assert prop in (r.structured.get("applied") or []), "%s must be applied: %r" % (prop, r.structured)


# ──────────────────────────────────────────────────────────────────────────────
# Discovery view
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="read")
def test_get_metadata_details_assignable_lists_enum_allowed_values():
    text = _assignable_text("Catalog.Catalog.Attribute.Attribute")
    assert_contains(text, "Assignable properties", "assignable mode must render the schema heading")
    assert_contains(text, "Allowed values", "assignable table must have an Allowed values column")
    assert "| ENUM |" in text, "an attribute must list at least one ENUM property: %r" % (text[:400],)


# ──────────────────────────────────────────────────────────────────────────────
# Validation matrix (the requirement) — every reject is actionable + changes nothing
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_unknown_property_lists_assignable():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog",
        "properties": [{"name": "noSuchProperty_e2e", "value": "x"}],
    })
    e = assert_error(r, "unknown property")
    assert_error_quality(e, names=["noSuchProperty_e2e"],
                         suggests=["not assignable", "Assignable properties", "assignable:true"])
    assert_no_diff("a rejected modify must change nothing")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_name_property_points_to_rename():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog",
        "properties": [{"name": "name", "value": "Renamed_e2e"}],
    })
    e = assert_error(r, "name property refused")
    assert_error_quality(e, suggests=["rename_metadata_object"],
                         ctx="renaming via 'name' must point at rename_metadata_object")
    assert_no_diff("a refused rename must change nothing")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_bad_enum_value_lists_allowed():
    # Discover a real enum property, then send a bogus value -> error must list the allowed values.
    attr = "E2EBadEnumAttr"
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr})
    assert_ok(cr, "seed attribute")
    wait_for_project_ready()
    fqn = "Catalog.Catalog.Attribute." + attr
    prop, value = _first_enum_with_value(fqn)
    assert prop is not None, "precondition: an enum property exists"

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "properties": [{"name": prop, "value": "NotAValidLiteral_zzz"}],
    })
    e = assert_error(r, "bad enum value")
    # the error names the bad value AND lists the allowed literals (the discovered one included)
    assert_error_quality(e, names=["NotAValidLiteral_zzz"], suggests=["Allowed", value])


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_type_property_not_yet_supported():
    attr = "E2ETypePropAttr"
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr})
    assert_ok(cr, "seed attribute")
    wait_for_project_ready()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr,
        "properties": [{"name": "type", "value": "String"}],
    })
    e = assert_error(r, "type not yet supported")
    assert_error_quality(e, suggests=["not yet supported"],
                         ctx="setting the data type is gated for now")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_empty_value_is_rejected_not_a_silent_clear():
    # An empty value must be rejected, never silently clear the property (parity with the former
    # set_metadata_property's "empty = not provided" guard).
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog",
        "properties": [{"name": "comment", "value": ""}],
    })
    e = assert_error(r, "empty value rejected")
    assert_error_quality(e, names=["comment"], suggests=["non-empty", "does not clear"])
    assert_no_diff("a rejected modify must change nothing")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_missing_properties_is_error():
    r = call("modify_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog"})
    e = assert_error(r, "missing properties")
    assert_error_quality(e, names=["properties"], suggests=["required"])
    assert_no_diff("a rejected modify must change nothing")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_missing_project_name_is_error():
    r = call("modify_metadata", {"fqn": "Catalog.Catalog",
                                 "properties": [{"name": "comment", "value": "x"}]})
    e = assert_error(r, "missing projectName")
    assert_error_quality(e, names=["projectName"], suggests=["required", "list_projects"])
    assert_no_diff("a rejected modify must change nothing")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_nonexistent_node_is_error():
    bad = "Catalog.DoesNotExist_e2e"
    r = call("modify_metadata", {"projectName": PROJECT, "fqn": bad,
                                 "properties": [{"name": "comment", "value": "x"}]})
    e = assert_error(r, "nonexistent node")
    assert_error_quality(e, names=[bad], suggests=["not found", "get_metadata_objects"])
    assert_no_diff("a rejected modify must change nothing")
