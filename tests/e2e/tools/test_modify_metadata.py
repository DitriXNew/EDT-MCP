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
    assert_not_contains,
    assert_no_diff,
    assert_tree_unchanged,
    poll_diff_contains,
    tree_snapshot,
    wait_for_project_ready,
    diff,
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


# ──────────────────────────────────────────────────────────────────────────────
# ё->е normalization — localized-string / free-text values are normalized at parse
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_normalizes_yo_in_synonym_and_comment_by_default():
    # Default normalizeYo=true: the synonym + comment values are rewritten 'ё'->'е' at the parse step,
    # so they are stored compliant with mdo-ru-name-unallowed-letter.
    syn_yo, syn_ye = "Серёжки", "Сережки"        # synonym with ё / expected
    com_yo, com_ye = "Полётный журнал", "Полетный журнал"  # comment with ё / expected
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog",
        "properties": [
            {"name": "synonym", "value": syn_yo, "language": "ru"},
            {"name": "comment", "value": com_yo},
        ],
    })
    assert_ok(r, "set synonym + comment carrying ё on Catalog.Catalog (default normalizeYo)")
    normalized = r.structured.get("normalized") or []
    assert "synonym" in normalized and "comment" in normalized, \
        "the normalization report must list synonym + comment: %r" % (r.structured,)
    poll_diff_contains(syn_ye, ctx="the synonym must be stored in its normalized (е-form) on disk")
    assert_contains(diff(), com_ye, "the comment must be stored in its normalized (е-form) on disk")
    assert_not_contains(diff(), syn_yo, "the ё-form synonym must NOT appear on disk under default normalize")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_preserves_yo_when_normalize_disabled():
    # normalizeYo=false: the comment keeps its 'ё' exactly as supplied.
    com_yo = "Расчёт стоимости"  # contains ё
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog",
        "normalizeYo": False,
        "properties": [{"name": "comment", "value": com_yo}],
    })
    assert_ok(r, "set a comment carrying ё with normalizeYo=false")
    assert not (r.structured.get("normalized") or []), \
        "no normalization must be reported when disabled: %r" % (r.structured,)
    poll_diff_contains(com_yo, ctx="the ё-form comment must be stored verbatim when normalizeYo=false")


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


@e2e_test(tool="modify_metadata", kind="write-metadata")  # seeds a Subsystem -> needs the model reset
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


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_move_form_button_into_auto_command_bar():
    # Reparent an EXISTING button into the form's command bar via the 'parent' property - the move
    # half of the #138 reporter's manual XML edits (new buttons can be parented at creation; this
    # covers buttons that already exist at the form root).
    cmd, btn = "MoveCmd", "MoveBtn"
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(r, "seed form command")
    wait_for_project_ready()
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button." + btn,
        "properties": [{"name": "command", "value": cmd}]})
    assert_ok(r, "seed a root-level button")
    wait_for_project_ready()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button." + btn,
        "properties": [{"name": "parent", "value": "AutoCommandBar"}]})
    assert_ok(r, "move the button into the AutoCommandBar")
    assert "parent" in (r.structured.get("applied") or []), (
        "the move must report parent as applied: %r" % (r.structured,))
    poll_diff_contains(btn, ctx="the moved button must land in the form's .form on disk")
    # The structure read-back shows the button nested under the bar - the moved containment.
    rb = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": ["Catalog.Catalog.Form.ItemForm"]})
    assert_ok(rb, "read the form structure back")
    lines = rb.text.splitlines()
    bar_idx = next((i for i, ln in enumerate(lines) if "AutoCommandBar" in ln), None)
    btn_idx = next((i for i, ln in enumerate(lines) if btn in ln), None)
    assert bar_idx is not None and btn_idx is not None and btn_idx > bar_idx, (
        "the moved button must render nested under the AutoCommandBar: " + rb.text[:800])


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_move_form_button_unknown_parent_is_error():
    cmd, btn = "MoveErrCmd", "MoveErrBtn"
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(r, "seed form command")
    wait_for_project_ready()
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button." + btn,
        "properties": [{"name": "command", "value": cmd}]})
    assert_ok(r, "seed a root-level button")
    wait_for_project_ready()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button." + btn,
        "properties": [{"name": "parent", "value": "NoSuchParent_zz"}]})
    e = assert_error(r, "move to a missing parent")
    assert_error_quality(e, names=["NoSuchParent_zz"], suggests=["AutoCommandBar"],
                         ctx="a missing move target must advertise the AutoCommandBar token")


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
def test_modify_form_handler_non_procedure_property_is_rejected():
    # A handler FQN only supports REBINDING the procedure (a 'procedure' property). Any other property
    # (here 'title') is refused with a pointer to the 'procedure' rebind + create/delete.
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Handler.OnOpen",
        "properties": [{"name": "title", "value": "x"}],
    })
    e = assert_error(r, "modify form handler with a non-procedure property rejected")
    assert_error_quality(e, suggests=["procedure", "create_metadata", "delete_metadata"],
                         ctx="a non-procedure property on a handler FQN points to procedure rebind + create/delete")
    assert_no_diff("a rejected form-handler modify must change nothing")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_rebind_form_level_handler_procedure_when_absent_is_error():
    # Rebind only re-points an EXISTING handler; with no OnOpen handler bound yet the error steers to
    # create_metadata (binding a NEW event is create_metadata's job).
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Handler.OnOpen",
        "properties": [{"name": "procedure", "value": "OnOpenProc"}],
    })
    # Either there is already a handler (then this succeeds) or there is none (then a clean error). Both
    # outcomes are acceptable here; the dedicated round-trip test below seeds then rebinds deterministically.
    if r.is_error:
        e = assert_error(r, "rebind a non-existent handler")
        assert_error_quality(e, names=["OnOpen"], suggests=["create_metadata"],
                             ctx="rebinding an absent handler steers to create_metadata")


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


# ──────────────────────────────────────────────────────────────────────────────
# Happy / negative — MOVE / REORDER a form item: the 'parent' / 'position'
# move properties re-parent / reorder an item in the form's items tree.
# Fixture: Catalog.Catalog has a managed form "ItemForm".
# ──────────────────────────────────────────────────────────────────────────────

def _seed_form_group(grp):
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group." + grp})
    assert_ok(r, "seed form group " + grp)
    wait_for_project_ready()


def _form_structure_text():
    """The rendered form structure (the items outline) from get_metadata_details."""
    r = call("get_metadata_details",
             {"projectName": PROJECT, "objectFqns": ["Catalog.Catalog.Form.ItemForm"]})
    assert_ok(r, "read ItemForm structure")
    return r.text


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_move_field_into_group():
    # Create a group + a bound field at the form root, then move the field INTO the group:
    # the field must appear nested under the group in the structure read-back.
    _seed_form_group("MoveGrp")
    _seed_form_field("MoveAttr", "MoveFld")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.MoveFld",
        "properties": [{"name": "parent", "value": "MoveGrp"}],
    })
    assert_ok(r, "move the field into the group")
    assert r.structured.get("action") == "modified", "must report modified: %r" % (r.structured,)
    assert "parent" in (r.structured.get("applied") or []), "parent must be applied: %r" % (r.structured,)
    assert "MoveGrp" in (r.structured.get("destination") or ""), \
        "destination must name the target group: %r" % (r.structured,)
    # The structure outline indents children under their parent; the field is now under the group.
    text = _form_structure_text()
    assert_contains(text, "MoveGrp", "the group must be in the structure")
    g = text.index("MoveGrp")
    f = text.index("MoveFld")
    assert f > g, "the moved field must be listed AFTER (nested under) its new parent group:\n%s" % text


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_reorder_field_first_at_root():
    # Two fields at the form root; reorder the second to 'first' -> it precedes the first in the outline.
    _seed_form_field("OrdAttr1", "OrdFld1")
    _seed_form_field("OrdAttr2", "OrdFld2")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.OrdFld2",
        "properties": [{"name": "position", "value": "first"}],
    })
    assert_ok(r, "reorder OrdFld2 to first")
    assert "position" in (r.structured.get("applied") or []), "position must be applied: %r" % (r.structured,)
    assert "index 0" in (r.structured.get("destination") or ""), \
        "destination must report index 0: %r" % (r.structured,)
    text = _form_structure_text()
    assert text.index("OrdFld2") < text.index("OrdFld1"), \
        "OrdFld2 must now precede OrdFld1 in the form outline:\n%s" % text


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_reorder_before_sibling_persists_to_disk():
    # 'before:<sibling>' lands the moved field at the sibling's index; verify it persists to .form.
    _seed_form_field("BefAttrA", "BefFldA")
    _seed_form_field("BefAttrB", "BefFldB")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.BefFldB",
        "properties": [{"name": "position", "value": "before:BefFldA"}],
    })
    assert_ok(r, "reorder BefFldB before BefFldA")
    assert r.structured.get("persisted") is True, \
        "the move must force-export the .form to disk: %r" % (r.structured,)
    poll_diff_contains("BefFldB", ctx="the reordered field must remain in the .form on disk")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_move_field_back_to_form_root():
    # Field inside a group, then move it back to the form root by naming the form as the parent.
    _seed_form_group("BackGrp")
    _seed_form_attribute("BackAttr")
    cr = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.BackFld",
        "properties": [{"name": "dataPath", "value": "BackAttr"}, {"name": "parent", "value": "BackGrp"}]})
    # create_metadata may not accept 'parent' at creation; if not, fall back to creating then moving in.
    if cr.is_error:
        cr = call("create_metadata", {
            "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.BackFld",
            "properties": [{"name": "dataPath", "value": "BackAttr"}]})
        assert_ok(cr, "seed field at root")
        wait_for_project_ready()
        assert_ok(call("modify_metadata", {
            "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.BackFld",
            "properties": [{"name": "parent", "value": "BackGrp"}]}), "move field into group")
    wait_for_project_ready()

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.BackFld",
        "properties": [{"name": "parent", "value": "ItemForm"}],
    })
    assert_ok(r, "move the field back to the form root")
    assert "form root" in (r.structured.get("destination") or ""), \
        "destination must report the form root: %r" % (r.structured,)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_move_group_into_own_descendant_rejected():
    # A group cannot be moved into a group nested inside itself (a containment cycle).
    _seed_form_group("OuterGrp")
    inner = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group.InnerGrp",
        "properties": [{"name": "parent", "value": "OuterGrp"}]})
    if inner.is_error:
        inner = call("create_metadata", {
            "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group.InnerGrp"})
        assert_ok(inner, "seed inner group")
        wait_for_project_ready()
        assert_ok(call("modify_metadata", {
            "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group.InnerGrp",
            "properties": [{"name": "parent", "value": "OuterGrp"}]}), "nest inner under outer")
    wait_for_project_ready()

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group.OuterGrp",
        "properties": [{"name": "parent", "value": "InnerGrp"}],
    })
    e = assert_error(r, "move group into its own descendant")
    assert_error_quality(e, suggests=["itself", "descendant"],
                         ctx="moving a group into its own descendant is a clean cycle error")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_move_missing_item_is_error():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.NoSuchFld_zz",
        "properties": [{"name": "position", "value": "first"}],
    })
    e = assert_error(r, "move a missing item")
    assert_error_quality(e, names=["NoSuchFld_zz"], suggests=["not found", "get_metadata_details"],
                         ctx="moving a non-existent item is a clean, actionable error")
    assert_no_diff("a rejected move must change nothing")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_move_missing_target_group_is_error():
    _seed_form_field("TgtAttr", "TgtFld")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.TgtFld",
        "properties": [{"name": "parent", "value": "NoSuchGroup_zz"}],
    })
    e = assert_error(r, "move into a missing group")
    assert_error_quality(e, names=["NoSuchGroup_zz"], suggests=["not found"],
                         ctx="a missing target group is a clean error")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_move_cannot_be_mixed_with_other_properties():
    # A structural move must not be combined with an ordinary property change in one call.
    _seed_form_field("MixAttr", "MixFld")
    # The SEEDING legitimately dirties the tree (create_metadata force-exports the .form), so a
    # plain assert_no_diff would flag the setup, not the rejected call. Snapshot after seeding
    # and assert the rejected mixed call added NOTHING on top (verified live: the mix rejection
    # happens before any BM mutation, so the diff is byte-identical before/after the call).
    before = tree_snapshot()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.MixFld",
        "properties": [{"name": "position", "value": "first"},
                       {"name": "visible", "value": False}],
    })
    e = assert_error(r, "move mixed with a property change")
    assert_error_quality(e, suggests=["cannot be combined", "separate call"],
                         ctx="a move cannot be mixed with a property change")
    assert_tree_unchanged(before, "a rejected mixed move must change nothing")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_move_on_form_attribute_is_rejected():
    # 'parent'/'position' address a form ITEM only - a form ATTRIBUTE is not positioned.
    _seed_form_attribute("NoPosAttr")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute.NoPosAttr",
        "properties": [{"name": "position", "value": "first"}],
    })
    e = assert_error(r, "position on a form attribute")
    assert_error_quality(e, suggests=["form ITEM", "not positioned"],
                         ctx="a form attribute cannot be positioned")


# ──────────────────────────────────────────────────────────────────────────────
# Happy / negative — REBIND a form event handler's procedure and re-point a
# button at another form command. Binding the handler / creating the button is
# create_metadata's job; modify_metadata only REBINDS the existing link.
# Fixture: Catalog.Catalog has a managed form "ItemForm".
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_rebind_item_level_handler_procedure_roundtrip():
    # Create a bound field + an item-level OnChange handler (procedure Proc1) via create_metadata, then
    # REBIND the handler's procedure to Proc2 via modify_metadata; the new name lands on disk.
    _seed_form_field("RbAttr", "RbFld")
    cr = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Field.RbFld.Handler.OnChange",
        "properties": [{"name": "procedure", "value": "RbProc1"}]})
    assert_ok(cr, "bind the OnChange handler to RbProc1")
    wait_for_project_ready()

    r = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Field.RbFld.Handler.OnChange",
        "properties": [{"name": "procedure", "value": "RbProc2"}],
    })
    assert_ok(r, "rebind the handler procedure to RbProc2")
    assert r.structured.get("action") == "modified", "must report modified: %r" % (r.structured,)
    assert r.structured.get("persisted") is True, \
        "the rebind must force-export the .form to disk: %r" % (r.structured,)
    poll_diff_contains("RbProc2", ctx="the new handler procedure name must land in the .form on disk")
    assert_not_contains(diff(), "RbProc1", "the old procedure name must be replaced on disk")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_rebind_handler_other_property_in_same_call_rejected():
    # A handler FQN accepts only the 'procedure' rebind; mixing another property is refused.
    _seed_form_field("RbMixAttr", "RbMixFld")
    assert_ok(call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Field.RbMixFld.Handler.OnChange",
        "properties": [{"name": "procedure", "value": "RbMixProc1"}]}), "bind OnChange")
    wait_for_project_ready()
    r = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Field.RbMixFld.Handler.OnChange",
        "properties": [{"name": "title", "value": "x"}],
    })
    e = assert_error(r, "non-procedure property on a handler FQN")
    assert_error_quality(e, suggests=["procedure", "create_metadata", "delete_metadata"],
                         ctx="only the procedure rebind is supported on a handler FQN")


def _seed_button_and_command(btn, cmd):
    """Seed a form command + a button bound to it (the button needs an existing command)."""
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd}),
        "seed form command " + cmd)
    wait_for_project_ready()
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button." + btn,
        "properties": [{"name": "command", "value": cmd}]}),
        "seed button %s bound to %s" % (btn, cmd))
    wait_for_project_ready()


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_rebind_button_command_roundtrip():
    # Create a button bound to command Cmd1, create a second command Cmd2, then RE-POINT the button at
    # Cmd2 via modify_metadata.
    _seed_button_and_command("RbBtn", "RbCmd1")
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command.RbCmd2"}),
        "seed the second command")
    wait_for_project_ready()

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button.RbBtn",
        "properties": [{"name": "command", "value": "RbCmd2"}],
    })
    assert_ok(r, "re-point the button at RbCmd2")
    assert r.structured.get("action") == "modified", "must report modified: %r" % (r.structured,)
    assert "command" in (r.structured.get("applied") or []), "command must be applied: %r" % (r.structured,)
    assert r.structured.get("persisted") is True, \
        "the rebind must force-export the .form to disk: %r" % (r.structured,)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_rebind_button_to_missing_command_is_error():
    _seed_button_and_command("RbMissBtn", "RbMissCmd1")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button.RbMissBtn",
        "properties": [{"name": "command", "value": "NoSuchCmd_zz"}],
    })
    e = assert_error(r, "re-point a button at a missing command")
    assert_error_quality(e, names=["NoSuchCmd_zz"], suggests=["not found", "create_metadata"],
                         ctx="a missing form command is a clean, actionable error")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_rebind_button_command_mixed_with_other_property_rejected():
    # A 'command' rebind cannot be combined with an ordinary property change in one call.
    _seed_button_and_command("RbMixBtn", "RbMixCmd")
    # The SEEDING dirties the tree (the .form is force-exported), so snapshot after it and
    # assert the rejected mixed call changed NOTHING on top (same rationale as the mixed-move
    # test above: the rebind branch rejects the mix before any BM mutation).
    before = tree_snapshot()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button.RbMixBtn",
        "properties": [{"name": "command", "value": "RbMixCmd"},
                       {"name": "title", "value": "x", "language": "en"}],
    })
    e = assert_error(r, "command rebind mixed with a property change")
    assert_error_quality(e, suggests=["cannot be combined", "separate call"],
                         ctx="a button command rebind cannot be mixed with a property change")
    assert_tree_unchanged(before, "a rejected mixed rebind must change nothing")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — form GROUP layout props that live under <extInfo> (UsualGroupExtInfo):
# the grouping `group` enum + the `united` flag are NOT on the group element but
# on its nested UsualGroupExtInfo. modify_metadata resolves / creates that extInfo
# holder reflectively and routes the eSet there (issue #235). A mixed direct +
# extInfo batch routes each property to its correct holder in one transaction.
# Fixture: Catalog.Catalog has a managed form "ItemForm".
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_form_group_extinfo_layout_props():
    # Set a UsualGroup's grouping (`group`) + `united` flag: both live on the group's nested
    # UsualGroupExtInfo, so the tool must create / reuse that extInfo holder and land the values there.
    _seed_form_group("ExtGrp")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group.ExtGrp",
        "properties": [
            # "Vertical" (not the enum default "Horizontal", which EMF omits from disk as eIsSet==false).
            {"name": "group", "value": "Vertical"},
            {"name": "united", "value": True},
        ],
    })
    assert_ok(r, "set a UsualGroup's group + united (extInfo layout props)")
    assert r.structured.get("action") == "modified", "must report modified: %r" % (r.structured,)
    applied = r.structured.get("applied") or []
    for f in ("group", "united"):
        assert f in applied, "%s must be in applied: %r" % (f, r.structured)
    assert r.structured.get("persisted") is True, \
        "the extInfo change must force-export the .form to disk: %r" % (r.structured,)
    # The nested <extInfo xsi:type="form:UsualGroupExtInfo"> + the grouping value land in the .form.
    poll_diff_contains("UsualGroupExtInfo",
                       ctx="the nested extInfo (form:UsualGroupExtInfo) must land in the .form on disk")
    assert_contains(diff(), "<group>Vertical</group>",
                    "the group's Vertical grouping must land under <extInfo> on disk")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_form_group_mixed_direct_and_extinfo_batch():
    # A single call mixing a DIRECT feature (visible, on the group element) with an extInfo feature
    # (group, on UsualGroupExtInfo) must apply each to its correct holder in ONE transaction: both land.
    _seed_form_group("MixExtGrp")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group.MixExtGrp",
        "properties": [
            {"name": "visible", "value": False},        # direct feature on the group element
            {"name": "group", "value": "Vertical"},     # extInfo feature (UsualGroupExtInfo.group); non-default so it serializes
        ],
    })
    assert_ok(r, "mixed direct + extInfo batch on a UsualGroup")
    applied = r.structured.get("applied") or []
    for f in ("visible", "group"):
        assert f in applied, "%s must be in applied: %r" % (f, r.structured)
    # The extInfo grouping prop from the mixed batch reaches the created UsualGroupExtInfo holder.
    poll_diff_contains("<group>Vertical</group>",
                       ctx="the extInfo group prop from a mixed batch must land under <extInfo>")
    assert_contains(diff(), "UsualGroupExtInfo",
                    "the mixed batch must create the UsualGroupExtInfo holder for the extInfo prop")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_modify_form_group_unknown_extinfo_property_lists_assignable():
    # An unknown property on a group is rejected with the now-EXTENDED assignable set (member ∪ extInfo),
    # so the error steers the caller to the real layout props that live under <extInfo>.
    _seed_form_group("BadExtGrp")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group.BadExtGrp",
        "properties": [{"name": "definitelyNotAGroupProp_zz", "value": "x"}],
    })
    e = assert_error(r, "unknown group property")
    assert_error_quality(e, names=["definitelyNotAGroupProp_zz"],
                         suggests=["not assignable", "Assignable properties"],
                         ctx="an unknown group property lists the assignable set incl. the extInfo props")
