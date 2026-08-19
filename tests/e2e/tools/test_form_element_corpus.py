"""
e2e coverage of the WHOLE managed-form element corpus — one leg per type the form
metamodel can produce, so a type that stops being reachable through MCP is caught here
rather than by a user.

The corpus has four axes, and every one of them is walked exhaustively:

  * form ATTRIBUTE value types — the nine platform types that pair with a concrete
    FormAttributeExtInfo (DynamicList / ValueList / Planner / SpreadsheetDocument / Chart /
    Dendrogram / GanttChart / GeographicalSchema / GraphicalSchema), plus the plain
    form-only value types that pair with none. Issue #369: only 8 of ~30 value types a
    production configuration actually uses could be set at all, and `ValueList` — the third
    most used of them — answered "Unknown type kind".
  * form FIELD types — all 20 concrete ManagedFormFieldType literals.
  * form GROUP types — all 12 ManagedFormGroupType literals.
  * form DECORATION types — both ManagedFormDecorationType literals.

The attribute legs assert the ON-DISK XML, not just a success envelope: the ext-info is the
half that was missing, and a `success: true` with no `<extInfo xsi:type="form:...">` is
exactly the half-built attribute this suite exists to refuse. The expected shapes were taken
from a production ERP configuration authored by the designer.

reset: kind="write-metadata" -> reset_model() after each test. No leg creates a TOP object:
each hangs a uniquely-named form off the fixture catalog instead. That is deliberate — a new
top object is registered in Configuration.mdo, and undoing THAT depends on EDT flushing its
async export before the fixture revert, a race the harness documents as observed on EDT 2026.2.
A form is owned by its catalog and indexed nowhere above it, so `git checkout` + `git clean -fd`
put the tree back with nothing left to race. (It cost a red shard-4 to learn: three seeding legs
landed ahead of resync_to_disk's full-export test, which then re-exported a Configuration.mdo
that no longer matched the committed one.)
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    poll_disk_contains,
    poll_disk_lacks,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)

# ── the corpus, as the form metamodel defines it ─────────────────────────────────────────

# value-type category -> the FormAttributeExtInfo the designer pairs with it.
# DynamicList is absent on purpose: it is NOT settable through a bare `type` spec (it needs
# its query too) and has its own suite — test_modify_metadata_dynamiclist.py.
EXT_INFO_VALUE_TYPES = [
    ("ValueList", "ValueListExtInfo"),
    ("SpreadsheetDocument", "SpreadsheetDocumentExtInfo"),
    ("Chart", "ChartExtInfo"),
    ("GanttChart", "GanttChartExtInfo"),
    ("Dendrogram", "DendrogramExtInfo"),
    ("Planner", "PlannerExtInfo"),
    ("GeographicalSchema", "GeographicalSchemaExtInfo"),
    # The platform TYPE says Schema, the ext-info EClass says Scheme. Not a typo.
    ("GraphicalSchema", "GraphicalSchemeExtInfo"),
]

# Form-only platform value types that pair with NO ext-info — every one of them was
# "Unknown type kind" before #369, and all are in real use in a production configuration.
PLAIN_FORM_VALUE_TYPES = [
    "StandardPeriod",
    "StandardBeginningDate",
    "TypeDescription",
    "FormattedString",
    "TextDocument",
    "FormattedDocument",
    "PDFDocument",
    "DataCompositionSettingsComposer",
    "Picture",
    "Color",
    "Font",
]

# Every concrete ManagedFormFieldType literal (NONE is the unset default, not a field kind),
# paired with the FieldExtInfo the platform swaps in with it. Four of the pairings spell their
# two sides differently (GeographicalSchema/GeographicalMap, GraphicalSchema/Flowchart,
# HTMLDocument/Html, Picture/Image) — each is the platform's own, not a typo here.
FIELD_TYPES = [
    ("InputField", "InputFieldExtInfo"),
    ("LabelField", "LabelFieldExtInfo"),
    ("CheckBoxField", "CheckBoxFieldExtInfo"),
    ("PictureField", "ImageFieldExtInfo"),
    ("RadioButtonField", "RadioButtonsFieldExtInfo"),
    ("SpreadsheetDocumentField", "SpreadSheetDocFieldExtInfo"),
    ("TextDocumentField", "TextDocFieldExtInfo"),
    ("CalendarField", "CalendarFieldExtInfo"),
    ("ProgressBarField", "ProgressBarFieldExtInfo"),
    ("TrackBarField", "TrackBarFieldExtInfo"),
    ("ChartField", "ChartFieldExtInfo"),
    ("GanttChartField", "GanttChartFieldExtInfo"),
    ("DendrogramField", "DendrogramFieldExtInfo"),
    ("GraphicalSchemaField", "FlowchartFieldExtInfo"),
    ("HTMLDocumentField", "HtmlFieldExtInfo"),
    ("GeographicalSchemaField", "GeographicalMapFieldExtInfo"),
    ("FormattedDocumentField", "FormattedDocFieldExtInfo"),
    ("PDFDocumentField", "PDFDocumentFieldExtInfo"),
    ("PlannerField", "PlannerFieldExtInfo"),
    ("PeriodField", "PeriodFieldExtInfo"),
]

# Every ManagedFormGroupType literal, paired with its GroupExtInfo — or None for the five the
# platform pairs with no ext-info at all, which must therefore LOSE the one they had.
GROUP_TYPES = [
    ("UsualGroup", "UsualGroupExtInfo"),
    ("Pages", "PagesGroupExtInfo"),
    ("Page", "PageGroupExtInfo"),
    ("CommandBar", "CommandBarExtInfo"),
    ("ButtonGroup", "ButtonGroupExtInfo"),
    ("Popup", "PopupGroupExtInfo"),
    ("ColumnGroup", "ColumnGroupExtInfo"),
    ("ContextMenu", None),
    ("Navigator", None),
    ("RowActionsPanel", None),
    ("SelectedItemsActionsPanel", None),
    ("AutoCommandBar", None),
]

# Every ManagedFormDecorationType literal.
DECORATION_TYPES = ["Label", "Picture"]

# SpisokZnachenij = ValueList — the Russian spelling of the type in issue #369, built from
# code points so the assertion tests the real Cyrillic and not a copied literal.
RU_VALUE_LIST = "".join(chr(c) for c in (
    0x0421, 0x043f, 0x0438, 0x0441, 0x043e, 0x043a,
    0x0417, 0x043d, 0x0430, 0x0447, 0x0435, 0x043d, 0x0438, 0x0439))


# The fixture catalog every leg hangs its form off. Deliberately NOT a catalog of our own:
# creating a TOP object registers it in Configuration.mdo, and undoing that depends on EDT
# flushing its async export before the fixture revert - a race the harness documents as observed
# on EDT 2026.2. A form is owned by its catalog and appears in no top-level index, so the whole
# blast radius is Catalog.mdo plus the form's own directory, both of which `git checkout` +
# `git clean -fd` restore with nothing left to race.
FIXTURE_CATALOG = "Catalog.Catalog"

# The stored (mdclass) attribute the METADATA-target refusal is aimed at - the fixture's own, so
# that leg creates nothing either.
FIXTURE_STORED_ATTRIBUTE = FIXTURE_CATALOG + ".Attribute.Attribute"


def _seed_form(suffix):
    """An empty managed form on the fixture catalog. Returns (base, form_fqn, form_file)."""
    form_name = "E2EFormCorpus" + suffix
    form = FIXTURE_CATALOG + ".Form." + form_name
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": form}),
              "seed form " + suffix)
    wait_for_project_ready()
    form_file = "src/Catalogs/Catalog/Forms/%s/Form.form" % form_name
    return FIXTURE_CATALOG, form, form_file


def _set_attribute_type(attr_fqn, kind):
    return call("modify_metadata", {
        "projectName": PROJECT, "fqn": attr_fqn,
        "properties": [{"name": "type", "value": {"types": [{"kind": kind}]}}],
    })


# ──────────────────────────────────────────────────────────────────────────────
# ATTRIBUTE value types — the nine that carry an ext-info
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_corpus_every_ext_info_value_type_writes_its_ext_info():
    base, form, form_file = _seed_form("Ext")
    for kind, ext_info in EXT_INFO_VALUE_TYPES:
        attr = form + ".Attribute.A" + kind
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attr}),
                  "create the attribute for " + kind)
        r = _set_attribute_type(attr, kind)
        assert_ok(r, "set the value type to " + kind)
        applied = r.structured.get("applied") or []
        # anti-cheat: the ext-info is the half that used to be missing, so the envelope must
        # SAY it was written and the file must SHOW it.
        assert "valueType" in applied, "%s: valueType must be applied: %r" % (kind, applied)
        assert "extInfo" in applied, \
            "%s: the paired extInfo must be applied too, not just the value type: %r" \
            % (kind, applied)

    for kind, ext_info in EXT_INFO_VALUE_TYPES:
        poll_disk_contains(form_file, "<types>%s</types>" % kind,
                           ctx="the %s value type must reach disk" % kind)
        poll_disk_contains(form_file, 'xsi:type="form:%s"' % ext_info,
                           ctx="%s must carry a %s on disk" % (kind, ext_info))

    # the designer writes <itemValueType/> on a ValueList — an EMPTY TypeDescription
    poll_disk_contains(form_file, "<itemValueType/>",
                       ctx="a ValueList's empty itemValueType must reach disk")

    # and EDT itself must accept the result: no new problems on the form.
    wait_for_project_ready()
    errs = call("get_project_errors", {"projectName": PROJECT, "objects": [form]})
    assert_ok(errs, "read the form's problems")
    assert_contains(errs.text, "No Errors Found",
                    "the whole ext-info corpus must validate clean in EDT")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_corpus_plain_value_types_carry_no_ext_info():
    base, form, form_file = _seed_form("Plain")
    for kind in PLAIN_FORM_VALUE_TYPES:
        attr = form + ".Attribute.A" + kind
        assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attr}),
                  "create the attribute for " + kind)
        r = _set_attribute_type(attr, kind)
        assert_ok(r, "set the value type to " + kind)
        applied = r.structured.get("applied") or []
        assert "extInfo" not in applied, \
            "%s pairs with no ext-info, so none may be written: %r" % (kind, applied)

    for kind in PLAIN_FORM_VALUE_TYPES:
        poll_disk_contains(form_file, "<types>%s</types>" % kind,
                           ctx="the %s value type must reach disk" % kind)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_corpus_russian_type_spelling_resolves_the_same():
    # The platform type provider indexes every type under both names, so the Russian
    # spelling must produce the SAME canonical English type on disk.
    base, form, form_file = _seed_form("Ru")
    attr = form + ".Attribute.RuList"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attr}), "create attribute")

    r = _set_attribute_type(attr, RU_VALUE_LIST)
    assert_ok(r, "set the value type with the Russian spelling")
    assert "extInfo" in (r.structured.get("applied") or []), \
        "the Russian spelling must reach the same ext-info pairing: %r" % (r.structured,)

    poll_disk_contains(form_file, "<types>ValueList</types>",
                       ctx="the Russian spelling must be stored under the canonical English name")
    poll_disk_contains(form_file, 'xsi:type="form:ValueListExtInfo"',
                       ctx="the Russian spelling must get the ValueListExtInfo too")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_corpus_value_list_item_type_is_settable_afterwards():
    # The seeded empty <itemValueType/> is not decoration: it is the live holder that lets the
    # list's ITEM type be set in a follow-up call, the same {types:[...]} shape as any other type.
    base, form, form_file = _seed_form("Item")
    attr = form + ".Attribute.Options"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attr}), "create attribute")
    assert_ok(_set_attribute_type(attr, "ValueList"), "type -> ValueList")

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": attr,
        "properties": [{"name": "itemValueType",
                        "value": {"types": [{"kind": "String", "length": 25}]}}],
    })
    assert_ok(r, "set the list's item type")
    assert "itemValueType" in (r.structured.get("applied") or []), \
        "itemValueType must be applied: %r" % (r.structured,)
    poll_disk_contains(form_file, "<length>25</length>",
                       ctx="the item type's qualifier must reach the ValueListExtInfo on disk")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_corpus_retype_batched_with_an_ext_info_prop_is_refused():
    # A retype REPLACES the ext-info, so batching it with a property that lives on the OLD holder is
    # order-dependent: the property is either discarded (while still reported as applied) or applied
    # to an EClass that lacks it. The guard must catch it for an ATTRIBUTE too, whose `type` is
    # normalized to `valueType` before the guard reads the name.
    base, form, form_file = _seed_form("Combo")
    attr = form + ".Attribute.Options"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attr}), "create attribute")
    assert_ok(_set_attribute_type(attr, "ValueList"), "type -> ValueList")

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": attr,
        "properties": [
            {"name": "itemValueType", "value": {"types": [{"kind": "String"}]}},
            {"name": "type", "value": {"types": [{"kind": "SpreadsheetDocument"}]}},
        ],
    })
    err = assert_error(r, "a retype batched with an ext-info property must be refused")
    assert_error_quality(err, suggests=["separate call"], ctx="the retype+extInfo combo refusal")

    # and nothing may have been applied: the attribute is still a ValueList with its ext-info.
    poll_disk_contains(form_file, 'xsi:type="form:ValueListExtInfo"',
                       ctx="the refused batch must not have retyped the attribute")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_corpus_retype_clears_the_stale_ext_info():
    base, form, form_file = _seed_form("Retype")
    attr = form + ".Attribute.Switcher"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attr}), "create attribute")
    assert_ok(_set_attribute_type(attr, "ValueList"), "type -> ValueList")
    poll_disk_contains(form_file, 'xsi:type="form:ValueListExtInfo"', ctx="the ValueList ext-info")

    assert_ok(_set_attribute_type(attr, "SpreadsheetDocument"),
              "type -> SpreadsheetDocument")
    poll_disk_contains(form_file, 'xsi:type="form:SpreadsheetDocumentExtInfo"',
                       ctx="the new type's ext-info must replace the old one")
    poll_disk_lacks(form_file, 'xsi:type="form:ValueListExtInfo"',
                    ctx="the stale ValueListExtInfo must NOT survive the retype")

    assert_ok(_set_attribute_type(attr, "String"), "type -> String")
    poll_disk_lacks(form_file, 'xsi:type="form:SpreadsheetDocumentExtInfo"',
                    ctx="retyping to a plain type must clear the ext-info entirely")


# ──────────────────────────────────────────────────────────────────────────────
# ATTRIBUTE value types — the refusals that keep the widened vocabulary honest
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="read")
def test_form_corpus_form_only_type_refused_on_a_stored_attribute():
    # Aimed at the fixture's OWN stored attribute, and it is a pure refusal - the type never
    # reaches the model, so this leg seeds nothing and mutates nothing.
    r = _set_attribute_type(FIXTURE_STORED_ATTRIBUTE, "ValueList")
    err = assert_error(r, "a stored metadata attribute must refuse a form-only platform type")
    assert_error_quality(err, names=["ValueList"], suggests=["Form.FormName.Attribute"],
                         ctx="the stored-attribute refusal")
    assert "Unknown type kind" not in err, \
        "a RECOGNIZED type must not be reported as unknown (issue #369): %r" % (err,)
    assert_no_diff("a refused retype must not touch the fixture")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_corpus_bare_dynamic_list_spec_refused():
    base, form, form_file = _seed_form("BareList")
    attr = form + ".Attribute.List"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attr}), "create attribute")

    r = _set_attribute_type(attr, "DynamicList")
    err = assert_error(r, "a bare DynamicList type spec would build a list with no query")
    assert_error_quality(err, names=["DynamicList"], suggests=["queryText"],
                         ctx="the bare-DynamicList refusal")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_corpus_unknown_platform_type_stays_unknown():
    # The widened vocabulary must not turn every typo into a "wrong target" answer. The attribute
    # has to be REAL: the member is resolved before the type is validated, so a made-up FQN would
    # answer "form member not found" and prove nothing about the type vocabulary.
    base, form, form_file = _seed_form("Typo")
    attr = form + ".Attribute.Probe"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attr}), "create attribute")

    r = _set_attribute_type(attr, "NoSuchPlatformType_E2E")
    err = assert_error(r, "an unknown type name must stay unknown")
    assert_error_quality(err, names=["NoSuchPlatformType_E2E"], suggests=["ValueList"],
                         ctx="the unknown-kind refusal")
    assert_contains(err, "Unknown type kind",
                    "a name the platform does not know must stay an UNKNOWN kind")


# ──────────────────────────────────────────────────────────────────────────────
# FIELD / GROUP / DECORATION types — the visual corpus
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_corpus_every_field_type_is_settable():
    base, form, form_file = _seed_form("Field")
    attr = form + ".Attribute.Data"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attr}), "seed the bound attribute")
    field = form + ".Field.Probe"
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": field,
        "properties": [{"name": "dataPath", "value": "Data"}]}), "seed the field")

    for field_type, ext_info in FIELD_TYPES:
        r = call("modify_metadata", {"projectName": PROJECT, "fqn": field,
                                     "properties": [{"name": "type", "value": field_type}]})
        assert_ok(r, "set the field type to " + field_type)
        applied = r.structured.get("applied") or []
        assert "type" in applied, "%s: type must be applied: %r" % (field_type, applied)
        # A field's `type` is a CLASSIFIER: the nested extInfo must be re-paired with it, or the
        # field reads back as its new type while its holder still describes the old one.
        assert "extInfo" in applied, \
            "%s: the paired extInfo must be re-created too: %r" % (field_type, applied)
        poll_disk_contains(form_file, 'xsi:type="form:%s"' % ext_info,
                           ctx="%s must carry a %s on disk" % (field_type, ext_info))

    # the LAST type set must be what the model reports back (not merely accepted)
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [form]})
    assert_ok(d, "read back the form")
    assert_contains(d.text, FIELD_TYPES[-1][0],
                    "the field must actually carry the last type that was set")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_corpus_every_group_type_is_settable():
    base, form, form_file = _seed_form("Group")
    group = form + ".Group.Probe"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": group}), "seed the group")

    for group_type, ext_info in GROUP_TYPES:
        r = call("modify_metadata", {"projectName": PROJECT, "fqn": group,
                                     "properties": [{"name": "type", "value": group_type}]})
        assert_ok(r, "set the group type to " + group_type)
        applied = r.structured.get("applied") or []
        assert "type" in applied, "%s: type must be applied: %r" % (group_type, applied)
        if ext_info is None:
            # The five group types that pair with NO ext-info must LOSE the previous one, not
            # carry it forward. Asserted against the GROUP ext-infos specifically: the item's
            # auto-children (its extended tooltip) legitimately carry one of their own.
            assert "extInfo" not in applied, \
                "%s pairs with no ext-info, so none may be attached: %r" % (group_type, applied)
            for _, stale in GROUP_TYPES:
                if stale is not None:
                    poll_disk_lacks(form_file, 'xsi:type="form:%s"' % stale,
                                    ctx="a %s group must not keep a %s" % (group_type, stale))
        else:
            assert "extInfo" in applied, \
                "%s: the paired extInfo must be re-created too: %r" % (group_type, applied)
            poll_disk_contains(form_file, 'xsi:type="form:%s"' % ext_info,
                               ctx="%s must carry a %s on disk" % (group_type, ext_info))

    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [form]})
    assert_ok(d, "read back the form")
    assert_contains(d.text, GROUP_TYPES[-1][0],
                    "the group must actually carry the last type that was set")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_corpus_every_decoration_type_is_settable():
    base, form, form_file = _seed_form("Dec")
    decoration = form + ".Decoration.Probe"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": decoration}),
              "seed the decoration")

    for decoration_type in DECORATION_TYPES:
        r = call("modify_metadata", {"projectName": PROJECT, "fqn": decoration,
                                     "properties": [{"name": "type", "value": decoration_type}]})
        assert_ok(r, "set the decoration type to " + decoration_type)

    # a Picture decoration swaps in a PictureDecorationExtInfo — the type change is structural
    poll_disk_contains(form_file, 'xsi:type="form:PictureDecorationExtInfo"',
                       ctx="the Picture decoration's ext-info must reach disk")


# ────────────────────────────────────────────────────────────────────────────
# PARAMETERS - the fifth axis (issue #396)
# ────────────────────────────────────────────────────────────────────────────


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_form_corpus_parameter_is_addressable_end_to_end():
    """A form PARAMETER can be created, read, retyped and deleted through MCP.

    This axis had no address at all: `Parameter` was not a kind token, so create / modify /
    delete answered "Unsupported form element kind" and get_metadata_details never showed one.
    Every other axis of the form corpus is walked by this file; this leg is what keeps this one
    from falling out again.
    """
    base, form, form_file = _seed_form("Param")
    param = form + ".Parameter.Filter"

    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": param}),
              "create a form parameter")
    poll_disk_contains(form_file, "<name>Filter</name>",
                       ctx="the parameter must reach Form.form on disk")

    # It renders in the form structure, in its own section - a parameter has no title, so the
    # table is Name / Type / Key / Comment.
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [form]})
    assert_ok(d, "read the form back")
    assert_contains(d.text, "## Parameters", "the parameters section must render")
    assert_contains(d.text, "Filter", "the parameter must be listed")

    # Its three real features go through modify_metadata - valueType via the SAME shared
    # {types:[...]} vocabulary an attribute takes.
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": param,
        "properties": [{"name": "valueType", "value": {"types": [{"kind": "String", "length": 50}]}},
                       {"name": "keyParameter", "value": True},
                       {"name": "comment", "value": "corpus probe"}]})
    assert_ok(r, "set the parameter's type, key flag and comment")
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [form]})
    assert_ok(d, "re-read the form")
    assert_contains(d.text, "String", "the parameter must carry the type that was set")
    assert_contains(d.text, "corpus probe", "the comment must render")

    assert_ok(call("delete_metadata", {"projectName": PROJECT, "fqn": param, "confirm": True}),
              "delete the parameter")
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [form]})
    assert_ok(d, "read the form after the delete")
    assert_not_contains(d.text, "## Parameters",
                        "the section must disappear with the last parameter")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_form_corpus_parameter_is_addressable_in_russian():
    """The Russian kind token addresses the same member - only the TOKEN is bilingual."""
    base, form, form_file = _seed_form("ParamRu")
    ru_param = form + "." + "Параметр" + ".Filter"

    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": ru_param}),
              "create a form parameter through the Russian token")
    poll_disk_contains(form_file, "<name>Filter</name>",
                       ctx="the parameter must reach Form.form on disk")
    # The SAME member is reachable through the English token - one member, two spellings.
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT, "fqn": form + ".Parameter.Filter",
        "properties": [{"name": "comment", "value": "one member"}]}),
        "the English token must reach the member the Russian one created")
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [form]})
    assert_ok(d, "read the form back")
    assert_contains(d.text, "one member", "the comment written through the English token")


@e2e_test(tool="create_metadata", kind="error")
def test_form_corpus_parameter_refuses_properties_it_cannot_store():
    """A parameter has no title and no parent, so a create-time property is refused, not dropped.

    The platform type carries name / valueType / keyParameter / comment only. Accepting a
    `title` here would report a success for a value that was never stored anywhere.
    """
    base, form, form_file = _seed_form("ParamProps")
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": form + ".Parameter.Probe",
        "properties": [{"name": "title", "value": "Nope", "language": "en"}]})
    e = assert_error(r, "a title on a form parameter")
    assert_error_quality(e, names=["title", "modify_metadata"],
                         ctx="the refusal must name the property and where the real ones go")
