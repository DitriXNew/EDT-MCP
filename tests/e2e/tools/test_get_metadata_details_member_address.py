"""Plain-view member addresses must produce error rows instead of owner data (#731).

All objects come from tests/TestConfiguration/src; these tests only read them.
Per-object refusals remain in the successful response's ## Errors table.
"""

from harness import (
    PROJECT,
    assert_contains,
    assert_no_diff,
    assert_not_contains,
    assert_ok,
    call,
    e2e_test,
    split_markdown_row,
)


_OWNER = "Catalog.Catalog"
_OWNER_HEADER = "## Catalog: Catalog"
_MEMBER = _OWNER + ".Attribute.Attribute"


def _read(fqn, **options):
    result = call("get_metadata_details", dict(
        {"projectName": PROJECT, "objectFqns": [fqn]}, **options))
    assert_ok(result, "per-object results stay in the markdown body: " + fqn)
    return result.text


def _assert_refusal(text, fqn, *reason_parts):
    assert_contains(text, "## Errors", "the member needs a machine-detectable failure section")
    rows = [split_markdown_row(line) for line in text.split("## Errors", 1)[1].splitlines()]
    matches = [row for row in rows if len(row) == 3 and row[0] == fqn]
    assert len(matches) == 1, "expected exactly one error row for %s:\n%s" % (fqn, text)
    assert matches[0][1] == "ERROR", "a refused member must have ERROR status: %r" % matches[0]
    for part in reason_parts:
        assert_contains(matches[0][2], part, "the reason must explain the refusal and the next step")
    assert_not_contains(text, _OWNER_HEADER, "a member address must never render the owner's header")
    assert_not_contains(text, "### Basic Properties", "owner data must not accompany the refusal")


@e2e_test(tool="get_metadata_details", kind="read")
def test_member_address_existing_attribute_is_refused_in_basic_and_full():
    # Catalog.mdo declares <attributes><name>Attribute</name>.
    for full in (False, True):
        _assert_refusal(_read(_MEMBER, full=full), _MEMBER,
                        "member of 'Catalog.Catalog'", "assignable: true",
                        "owner's details render from 'Catalog.Catalog'")
    assert_no_diff("refusing an existing member is read-only")


@e2e_test(tool="get_metadata_details", kind="read")
def test_member_address_missing_attribute_is_not_a_successful_owner_read():
    fqn = _OWNER + ".Attribute.NoSuchMember731"
    _assert_refusal(_read(fqn), fqn, "Object not found", "get_metadata_objects")
    assert_no_diff("a missing member must not change the fixture")


@e2e_test(tool="get_metadata_details", kind="read")
def test_member_address_russian_type_and_member_tokens_are_equivalent():
    fqn = "Справочник.Catalog.Реквизит.Attribute"
    _assert_refusal(_read(fqn), fqn, "member of 'Catalog.Catalog'", "assignable: true")
    missing = "Справочник.Catalog.Реквизит.NoSuchMember731"
    _assert_refusal(_read(missing), missing, "Object not found", "get_metadata_objects")
    assert_no_diff("Russian structural tokens resolve the same read-only fixture")


@e2e_test(tool="get_metadata_details", kind="read")
def test_member_address_owned_spreadsheet_template_is_refused():
    # Catalog.mdo declares Invoice; its Template.mxlx is a spreadsheet, not text/DCS.
    fqn = _OWNER + ".Template.Invoice"
    _assert_refusal(_read(fqn), fqn, "member of 'Catalog.Catalog'", "assignable: true")
    assert_no_diff("an owned spreadsheet template must not render the catalog")


@e2e_test(tool="get_metadata_details", kind="read")
def test_member_address_control_two_part_owner_still_renders():
    text = _read(_OWNER)
    assert_contains(text, _OWNER_HEADER, "a two-part address still renders the catalog")
    assert_contains(text, "Basic Properties", "the owner's details remain available")
    assert_contains(text, "**Origin:** core", "the existing origin footer is preserved")
    assert_not_contains(text, "## Errors", "a valid owner must not be refused")
    assert_no_diff("the owner control is read-only")


@e2e_test(tool="get_metadata_details", kind="read")
def test_member_address_control_form_fqn_still_renders_structure():
    fqn = _OWNER + ".Form.ItemForm"
    text = _read(fqn)
    assert_contains(text, "# Form Structure: ", "a form keeps its special structure view")
    assert_contains(text, "ItemForm", "the structure view names the addressed form")
    assert_contains(text, "## Items", "the fixture's managed form has an items outline")
    assert_contains(text, "Decoration1", "the form's checked-in decoration must still render")
    assert_not_contains(text, "## Errors", "the real fixture form must not be refused")
    assert_not_contains(text, _OWNER_HEADER, "a form renders its structure")
    assert_no_diff("the form control is read-only")


@e2e_test(tool="get_metadata_details", kind="read")
def test_member_address_control_common_spreadsheet_template_still_renders():
    text = _read("CommonTemplate.PrintForm")
    assert_contains(text, "## CommonTemplate: PrintForm", "a two-part template renders its own details")
    assert_not_contains(text, "## Errors", "the common template must not be refused")
    assert_no_diff("the common template control is read-only")


@e2e_test(tool="get_metadata_details", kind="read")
def test_member_address_control_assignable_attribute_still_renders_properties():
    text = _read(_MEMBER, assignable=True)
    assert_contains(text, "## Assignable properties: " + _MEMBER,
                    "the refusal's recommended view must render the member")
    assert_contains(text, "| name |", "the member's settable name is listed")
    assert_not_contains(text, "## Errors", "a real member resolves in the assignable view")
    assert_not_contains(text, _OWNER_HEADER, "the assignable view describes the member")
    assert_no_diff("the assignable control is read-only")
