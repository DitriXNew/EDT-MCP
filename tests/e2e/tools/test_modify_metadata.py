"""
e2e tests for modify_metadata (kind: write-metadata).

modify_metadata sets properties of a metadata node (object or member) addressed by a
1C full-name FQN, as properties=[{name, value, language?}]. It folds the former
set_metadata_property and adds VALIDATION: a non-assignable property is rejected WITH
the list of assignable properties; an out-of-range enum value is rejected WITH the
allowed literals; the `name` property is refused (use rename_metadata_object); the data
`type` takes a structured value. A member of a NESTED object (a tabular-section attribute) is
modifiable via in-transaction owner re-navigation. Nothing is written unless EVERY property validates.

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
def test_set_structured_type_number_on_attribute():
    attr = "E2ETypeNumAttr"
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr})
    assert_ok(cr, "seed attribute")
    wait_for_project_ready()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr,
        "properties": [{"name": "type", "value": {"types": [{"kind": "Number", "precision": 10, "scale": 2}]}}],
    })
    assert_ok(r, "set type Number(10,2)")
    assert "type" in (r.structured.get("applied") or []), "type must be applied: %r" % (r.structured,)
    # the new Number qualifier lands in the owner .mdo (precision element appears in the diff)
    poll_diff_contains("precision", ctx="the new Number(10,2) type must land in the owner .mdo")


def _seed_attr_and_set_type(attr, type_value):
    """Seed an attribute on Catalog.Catalog, then set its `type` to the structured value."""
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr})
    assert_ok(cr, "seed attribute " + attr)
    wait_for_project_ready()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr,
        "properties": [{"name": "type", "value": type_value}],
    })
    assert_ok(r, "set type on " + attr)
    assert "type" in (r.structured.get("applied") or []), "type must be applied: %r" % (r.structured,)
    return r


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_string_type_with_length():
    # String type with a length qualifier (the user-named "set string length" case).
    _seed_attr_and_set_type("E2ETypeStrAttr", {"types": [{"kind": "String", "length": 137}]})
    poll_diff_contains("137", ctx="the String length qualifier must land in the owner .mdo")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_boolean_type():
    _seed_attr_and_set_type("E2ETypeBoolAttr", {"types": [{"kind": "Boolean"}]})
    poll_diff_contains("Boolean", ctx="the Boolean type must land in the owner .mdo")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_date_type_with_fractions():
    # Use Time (a NON-default fraction): DateTime is the platform default and EDT omits it from the
    # serialized <dateQualifiers/>, so Time is what reliably proves the fraction landed.
    _seed_attr_and_set_type("E2ETypeDateAttr", {"types": [{"kind": "Date", "fractions": "Time"}]})
    poll_diff_contains("<dateFractions>Time</dateFractions>",
                       ctx="the Date Time fractions must land in the owner .mdo")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_composite_type():
    # A composite (mixed) type: the list may carry several kinds at once.
    _seed_attr_and_set_type("E2ETypeCompAttr",
                            {"types": [{"kind": "Number", "precision": 8}, {"kind": "Boolean"}]})
    poll_diff_contains("Boolean", ctx="a composite type's Boolean member must land in the owner .mdo")
    poll_diff_contains("precision", ctx="a composite type's Number member must land in the owner .mdo")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_typed_ref_shorthand():
    # The '<Type>Ref' shorthand (CatalogRef + Name) is an alternative to {kind:'Ref', ref:'Type.Name'}.
    _seed_attr_and_set_type("E2ETypeRefShAttr", {"types": [{"kind": "CatalogRef", "ref": "Catalog"}]})
    poll_diff_contains("CatalogRef.Catalog",
                       ctx="the CatalogRef shorthand must resolve to the catalog ref on disk")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_type_on_nested_tabular_section_attribute():
    # A member of a NESTED object (a tabular-section attribute, depth-6) is modifiable: the tool
    # re-fetches the TOP object and re-navigates to the leaf's owner inside the write transaction.
    ts, attr = "E2EModTab", "E2EModNestedAttr"
    c1 = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.TabularSection." + ts})
    assert_ok(c1, "seed tabular section")
    wait_for_project_ready()
    c2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.TabularSection.%s.Attribute.%s" % (ts, attr),
    })
    assert_ok(c2, "seed nested attribute")
    wait_for_project_ready()

    fqn = "Catalog.Catalog.TabularSection.%s.Attribute.%s" % (ts, attr)
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "properties": [{"name": "type", "value": {"types": [{"kind": "Number", "precision": 8, "scale": 0}]}}],
    })
    assert_ok(r, "set type on the NESTED tabular-section attribute")
    assert "type" in (r.structured.get("applied") or []), "type must be applied: %r" % (r.structured,)
    poll_diff_contains("precision",
                       ctx="the nested attribute's Number type must land in the owner Catalog.Catalog.mdo")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — object reference properties (single + many), set by FQN
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_many_reference_subsystem_content():
    # A Subsystem's `content` is a LIST reference to metadata objects: set it to [Catalog.Catalog]
    # by FQN. The whole list is replaced; the referenced FQN lands in the subsystem .mdo.
    sub = "E2ERefSubsystem"
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Subsystem." + sub})
    assert_ok(cr, "seed subsystem")
    wait_for_project_ready()

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Subsystem." + sub,
        "properties": [{"name": "content", "value": ["Catalog.Catalog"]}],
    })
    assert_ok(r, "set the subsystem content list")
    assert "content" in (r.structured.get("applied") or []), "content must be applied: %r" % (r.structured,)
    poll_diff_contains("Catalog.Catalog",
                       ctx="the referenced object FQN must land in the subsystem .mdo content")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_single_reference_accounting_register_chart_of_accounts():
    # An AccountingRegister.chartOfAccounts is a SINGLE reference to a ChartOfAccounts: set it by FQN.
    coa = "E2ERefCoA"
    reg = "E2ERefAcctReg"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "ChartOfAccounts." + coa}), "seed CoA")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "AccountingRegister." + reg}), "seed register")
    wait_for_project_ready()

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "AccountingRegister." + reg,
        "properties": [{"name": "chartOfAccounts", "value": "ChartOfAccounts." + coa}],
    })
    assert_ok(r, "set the chartOfAccounts single reference")
    assert "chartOfAccounts" in (r.structured.get("applied") or []), \
        "chartOfAccounts must be applied: %r" % (r.structured,)
    poll_diff_contains(coa, ctx="the referenced chart of accounts must land in the register .mdo")


@e2e_test(tool="modify_metadata", kind="read")
def test_assignable_lists_reference_property_with_target_type():
    # The Subsystem's `content` reference must appear in the assignable schema as a (MANY_)REFERENCE
    # with its allowed target type, so a client can discover it.
    sub = "E2ERefSubsystem2"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Subsystem." + sub}), "seed subsystem")
    wait_for_project_ready()
    text = _assignable_text("Subsystem." + sub)
    assert_contains(text, "content", "the content reference must be listed as assignable")
    assert_contains(text, "REFERENCE", "a reference property must report its REFERENCE kind")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_reference_to_nonexistent_target_is_error():
    sub = "E2ERefSubsystem3"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Subsystem." + sub}), "seed subsystem")
    wait_for_project_ready()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Subsystem." + sub,
        "properties": [{"name": "content", "value": ["Catalog.NoSuchObjectHere"]}],
    })
    e = assert_error(r, "reference to a nonexistent target")
    assert_error_quality(e, names=["Catalog.NoSuchObjectHere"],
                         ctx="a missing reference target is a clean, actionable error")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_ref_type_to_catalog():
    attr = "E2ERefTypeAttr"
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr})
    assert_ok(cr, "seed attribute")
    wait_for_project_ready()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr,
        "properties": [{"name": "type", "value": {"types": [{"kind": "Ref", "ref": "Catalog.Catalog"}]}}],
    })
    assert_ok(r, "set a CatalogRef type")
    assert "type" in (r.structured.get("applied") or []), "type must be applied: %r" % (r.structured,)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_ref_type_to_non_ref_object_is_clean_error():
    # A reference to an object with NO ref type (a CommonModule) must be a CLEAN error, not a crash
    # (the underlying getRefType throws AssertionError for such kinds; the tool must convert it).
    attr = "E2EBadRefAttr"
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr})
    assert_ok(cr, "seed attribute")
    wait_for_project_ready()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr,
        "properties": [{"name": "type", "value": {"types": [{"kind": "Ref", "ref": "CommonModule.OK"}]}}],
    })
    e = assert_error(r, "ref to a non-ref object")
    assert_error_quality(e, suggests=["not a reference type"],
                         ctx="a ref to a non-ref-producing object is a clean error, not a crash")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_set_type_malformed_spec_is_error():
    attr = "E2ETypeBadAttr"
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr})
    assert_ok(cr, "seed attribute")
    wait_for_project_ready()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr,
        # a bare string, not the structured {types:[{kind:...}]} shape -> rejected with the shape
        "properties": [{"name": "type", "value": "String"}],
    })
    e = assert_error(r, "malformed type spec")
    assert_error_quality(e, suggests=["types", "kind"],
                         ctx="a non-structured type value is rejected with the expected shape")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — FORM members (the cross-model hop: modify an item / attribute / command)
# Fixture: Catalog.Catalog has a managed form "ItemForm".
# ──────────────────────────────────────────────────────────────────────────────

def _seed_form_attribute(attr):
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr})
    assert_ok(r, "seed form attribute " + attr)
    wait_for_project_ready()


def _seed_form_field(attr, fld):
    _seed_form_attribute(attr)
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field." + fld,
        "properties": [{"name": "dataPath", "value": attr}]})
    assert_ok(r, "seed bound field " + fld)
    wait_for_project_ready()


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_form_field_title_visible_readonly():
    # Folds set_form_item_property: set title + visible + readOnly on a field in one call.
    _seed_form_field("MFAttr", "MFField")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.MFField",
        "properties": [
            {"name": "title", "value": "Modified field title", "language": "en"},
            {"name": "visible", "value": False},
            {"name": "readOnly", "value": True},
        ],
    })
    assert_ok(r, "modify a form field's title/visible/readOnly")
    assert r.structured.get("action") == "modified", "must report modified: %r" % (r.structured,)
    applied = r.structured.get("applied") or []
    for f in ("title", "visible", "readOnly"):
        assert f in applied, "%s must be in applied: %r" % (f, r.structured)
    poll_diff_contains("Modified field title",
                       ctx="the field title must land in the form's .form on disk")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_form_attribute_type():
    # The deferred form-attribute value-TYPE: set Number(10,2) on a form attribute via the `type`
    # alias (mapped to the attribute's real valueType feature).
    _seed_form_attribute("MFTypeAttr")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute.MFTypeAttr",
        "properties": [{"name": "type",
                        "value": {"types": [{"kind": "Number", "precision": 10, "scale": 2}]}}],
    })
    assert_ok(r, "set a form attribute's value type")
    assert "valueType" in (r.structured.get("applied") or []), \
        "the type alias must apply to valueType: %r" % (r.structured,)
    poll_diff_contains("precision",
                       ctx="the form attribute's Number(10,2) type must land in the .form on disk")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_form_command_title():
    cmd = "MFCmd"
    cr = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(cr, "seed form command")
    wait_for_project_ready()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd,
        "properties": [{"name": "title", "value": "Refresh now", "language": "en"}],
    })
    assert_ok(r, "set a form command's title")
    assert "title" in (r.structured.get("applied") or []), "title must be applied: %r" % (r.structured,)
    poll_diff_contains("Refresh now", ctx="the command title must land in the .form on disk")


# ── Negative (form members) ─────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_form_unknown_property_lists_assignable():
    _seed_form_field("MFUAttr", "MFUField")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.MFUField",
        "properties": [{"name": "definitelyNotAProp_zz", "value": "x"}],
    })
    e = assert_error(r, "unknown form item property")
    assert_error_quality(e, names=["definitelyNotAProp_zz"], suggests=["assignable", "visible"],
                         ctx="an unknown form property lists the item's assignable properties")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_form_id_is_rejected():
    _seed_form_field("MFIdAttr", "MFIdField")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.MFIdField",
        "properties": [{"name": "id", "value": 99}],
    })
    e = assert_error(r, "form item id rejected")
    assert_error_quality(e, names=["id"], suggests=["automatically", "unique"],
                         ctx="the auto-allocated form item id cannot be set")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_form_handler_is_rejected():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Handler.OnOpen",
        "properties": [{"name": "title", "value": "x"}],
    })
    e = assert_error(r, "modify form handler rejected")
    assert_error_quality(e, suggests=["not supported", "create_metadata", "delete_metadata"],
                         ctx="modifying a form handler points to create/delete")
    assert_no_diff("a rejected form-handler modify must change nothing")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_form_missing_member_is_error():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.NoSuchField_zz",
        "properties": [{"name": "visible", "value": False}],
    })
    e = assert_error(r, "missing form member")
    assert_error_quality(e, names=["NoSuchField_zz"], suggests=["not found", "get_metadata_details"],
                         ctx="a missing form member points to get_metadata_details")
    assert_no_diff("a rejected form modify must change nothing")


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
