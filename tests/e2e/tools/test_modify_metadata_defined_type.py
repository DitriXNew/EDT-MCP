"""
e2e tests for modify_metadata on a DefinedType's own `type` (issue #727; kind: write-metadata).

A DefinedType's `type` takes what EDT's DefinedType type editor offers. modify_metadata builds each item the
way a FORM attribute's type is built, then asks EDT's own type category - `UserDefinedTypeInfoCategory`, the
one EDT builds that editor from - whether the item belongs to it. So a runtime object type
(`DocumentObject.X`) and a platform value (`ValueTable`) are accepted, while a list type (`CatalogList.X`)
is refused in the defined type's own words. Before #727 a DefinedType's type was checked as a stored
attribute and refused `DocumentObject` and `ValueTable` outright.

modify_metadata submits the DefinedType's export itself and drains that queue before answering, so every
accepting test reads the .mdo DIRECTLY and before any other MCP round trip, then checks the answer's fields,
then the model read-back (get_metadata_details, assignable:true). A jar without the fix fails on the refusal
or on the stored type, never on wording alone.

Fixture: Catalog.Catalog. The tests seed their own Documents and DefinedTypes;
reset: kind="write-metadata" -> reset_model() after each test.
"""

import xml.etree.ElementTree as ET

from harness import (
    assert_contains,
    assert_error,
    assert_error_quality,
    assert_not_contains,
    assert_ok,
    assert_tree_unchanged,
    call,
    e2e_test,
    read_disk,
    split_markdown_row,
    tree_snapshot,
    wait_for_project_ready,
    PROJECT,
)


def _seed(fqn, ctx):
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}), ctx)
    wait_for_project_ready()


def _mdo(defined_name):
    return "src/DefinedTypes/%s/%s.mdo" % (defined_name, defined_name)


def _set_type(defined_name, types):
    return call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": "DefinedType." + defined_name,
        "properties": [{"name": "type", "value": {"types": types}}],
    })


def _stored_types(defined_name):
    """The <types> of the DefinedType's own <type> block on disk, in order."""
    mdo = read_disk(_mdo(defined_name))
    root = ET.fromstring(mdo)
    blocks = [child for child in root if child.tag.rsplit("}", 1)[-1] == "type"]
    assert len(blocks) == 1, "the DefinedType .mdo must hold exactly one <type> block:\n%s" % mdo[:1500]
    return [element.text for element in blocks[0].iter() if element.tag.rsplit("}", 1)[-1] == "types"]


def _read_back_types(defined_name):
    """The type names get_metadata_details(assignable:true) shows for the DefinedType's `type`, in order."""
    fqn = "DefinedType." + defined_name
    r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn], "assignable": True})
    assert_ok(r, "get_metadata_details(assignable) for %s" % fqn)
    # cells: [Property, Kind, Current, Allowed values]
    rows = [cells for cells in (split_markdown_row(line) for line in r.text.splitlines())
            if len(cells) == 4 and cells[0] == "type"]
    assert len(rows) == 1, "the DefinedType's type must be readable through assignable:true:\n%s" % r.text[:1500]
    return [name.strip() for name in rows[0][2].split(",") if name.strip()]


def _spec_item(type_name):
    """A read-back type name as a type spec item: `DocumentObject.X` -> {kind:'DocumentObject', ref:'X'}."""
    kind, _, ref = type_name.partition(".")
    return {"kind": kind, "ref": ref} if ref else {"kind": kind}


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_defined_type_takes_a_document_object_and_a_reference():
    document = "E2EDtObjectDocument"
    defined = "E2EDtObjectTypes"
    _seed("Document." + document, "seed the Document whose runtime object type the DefinedType holds")
    _seed("DefinedType." + defined, "seed the DefinedType")

    r = _set_type(defined, [{"kind": "DocumentObject", "ref": "Document." + document},
                            {"kind": "Ref", "ref": "Catalog.Catalog"}])
    assert_ok(r, "a DocumentObject and a reference in a DefinedType's type list")

    # DISK FIRST: the DefinedType's own type block, as EDT exported it.
    stored = _stored_types(defined)
    assert stored == ["DocumentObject." + document, "CatalogRef.Catalog"], \
        "the DefinedType must store exactly the two types, in order: %r" % (stored,)
    assert "type" in (r.structured.get("applied") or []), \
        "the type must be reported as applied: %r" % (r.structured,)
    assert _read_back_types(defined) == stored, "the model must read the same type list back"


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_defined_type_takes_a_value_table():
    defined = "E2EDtValueTable"
    _seed("DefinedType." + defined, "seed the DefinedType")

    r = _set_type(defined, [{"kind": "ValueTable"}])
    assert_ok(r, "a ValueTable in a DefinedType's type list")

    stored = _stored_types(defined)
    assert stored == ["ValueTable"], "the DefinedType must store exactly ValueTable: %r" % (stored,)
    assert "type" in (r.structured.get("applied") or []), \
        "the type must be reported as applied: %r" % (r.structured,)
    assert _read_back_types(defined) == ["ValueTable"], "the model must read ValueTable back"


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_defined_type_refuses_a_catalog_list_in_its_own_words():
    defined = "E2EDtNoList"
    _seed("DefinedType." + defined, "seed the DefinedType")
    before = tree_snapshot()

    r = _set_type(defined, [{"kind": "CatalogList", "ref": "Catalog"}])

    e = assert_error(r, "a CatalogList in a DefinedType's type list")
    assert_error_quality(e, names=["DefinedType." + defined, "CatalogList", "type.types[0]"],
                         suggests=["defined type", "UserDefinedTypeInfoCategory", "Remove it"],
                         ctx="the refusal must name the defined type, the entry and the category EDT decides it by")
    assert_not_contains(e, "stored metadata feature",
                        "a defined type does not follow the stored-feature rule, so the refusal must not cite it")
    assert_tree_unchanged(before, "a refused type list must not change the seeded tree")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_defined_type_takes_its_read_composition_back_with_one_more_document_object():
    # The reporter's case (#727): read the DefinedType's type list, pass it back whole with one more
    # DocumentObject. Before #727 the first DocumentObject already in the list was refused.
    first = "E2EDtRoundTripFirst"
    second = "E2EDtRoundTripSecond"
    defined = "E2EDtRoundTrip"
    _seed("Document." + first, "seed the first Document")
    _seed("Document." + second, "seed the second Document")
    _seed("DefinedType." + defined, "seed the DefinedType")
    assert_ok(_set_type(defined, [{"kind": "DocumentObject", "ref": first}]),
              "precondition: the DefinedType holds the first DocumentObject")
    assert _stored_types(defined) == ["DocumentObject." + first], \
        "precondition: the first DocumentObject is on disk"

    composition = _read_back_types(defined)
    assert_contains(", ".join(composition), "DocumentObject." + first,
                    "the read-back composition must name the first DocumentObject")

    r = _set_type(defined, [_spec_item(name) for name in composition]
                  + [{"kind": "DocumentObject", "ref": second}])
    assert_ok(r, "the read composition passed back whole with one more DocumentObject")

    stored = _stored_types(defined)
    assert stored == composition + ["DocumentObject." + second], \
        "the DefinedType must keep its list and add the new type: %r" % (stored,)
    assert "type" in (r.structured.get("applied") or []), \
        "the type must be reported as applied: %r" % (r.structured,)
    assert _read_back_types(defined) == stored, "the model must read the extended type list back"
