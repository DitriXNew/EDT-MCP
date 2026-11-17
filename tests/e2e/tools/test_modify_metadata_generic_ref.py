"""
e2e tests for the generic reference type in modify_metadata (issue #966; kind: write-metadata).

A `<Type>Ref` kind WITHOUT `ref` is the generic reference - "any document" (`DocumentRef` /
`ДокументСсылка`), the platform type set EDT's type editor lists for an attribute or a register
dimension and stores as `<types>DocumentRef</types>`. Which feature may hold it is EDT's call: its type
editor offers it on a dimension or an attribute but not on an accumulation register's resource, and that
refusal names the owner and the concrete form. `{kind:'Ref', ref:'Document'}` keeps one grammar: it is
refused with a pointer to `{kind:'DocumentRef'}`.

Before #966 every `{kind:'DocumentRef'}` was refused by the spec shape ("Invalid member 'ref'"), so each
accepting test fails on a jar without the fix at its first call, never on wording alone.

modify_metadata drains its own export before answering, so every accepting test reads the .mdo DIRECTLY,
then the model read-back (get_metadata_details, assignable:true), then writes the read-back type again and
checks the file did not change. Fixture: InformationRegister.QueryPrices, AccumulationRegister.QueryStock,
Document.QueryOrder, Catalog.Catalog; reset: kind="write-metadata" -> reset_model() after each test.
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

PRICES_MDO = "src/InformationRegisters/QueryPrices/QueryPrices.mdo"
STOCK_MDO = "src/AccumulationRegisters/QueryStock/QueryStock.mdo"
ORDER_MDO = "src/Documents/QueryOrder/QueryOrder.mdo"

# DokumentSsylka (DocumentRef) - the Russian spelling of the generic type.
RU_DOCUMENT_REF = "ДокументСсылка"


def _set_type(fqn, types):
    return call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": fqn,
        "properties": [{"name": "type", "value": {"types": types}}],
    })


def _local(element):
    return element.tag.rsplit("}", 1)[-1]


def _member_types(mdo_text, path):
    """The <types> of one member's <type> block: path is a list of (collection tag, member name)."""
    node = ET.fromstring(mdo_text)
    for tag, name in path:
        matches = [child for child in node if _local(child) == tag
                   and any(_local(n) == "name" and n.text == name for n in child)]
        assert len(matches) == 1, "expected one <%s> named %s in:\n%s" % (tag, name, mdo_text[:1500])
        node = matches[0]
    blocks = [child for child in node if _local(child) == "type"]
    assert len(blocks) == 1, "the member must hold exactly one <type> block:\n%s" % mdo_text[:1500]
    return [element.text for element in blocks[0] if _local(element) == "types"]


def _read_back_types(fqn):
    """The type names get_metadata_details(assignable:true) shows for the member's `type`."""
    r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn], "assignable": True})
    assert_ok(r, "get_metadata_details(assignable) for %s" % fqn)
    # cells: [Property, Kind, Current, Allowed values]
    rows = [cells for cells in (split_markdown_row(line) for line in r.text.splitlines())
            if len(cells) == 4 and cells[0] == "type"]
    assert len(rows) == 1, "the member's type must be readable through assignable:true:\n%s" % r.text[:1500]
    return [name.strip() for name in rows[0][2].split(",") if name.strip()]


def _spec_item(type_name):
    """A read-back type name as a spec item: `CatalogRef.X` -> {kind, ref}; `DocumentRef` -> {kind}."""
    kind, _, ref = type_name.partition(".")
    return {"kind": kind, "ref": ref} if ref else {"kind": kind}


def _assert_round_trip(fqn, mdo_path, expected):
    """The model reads the stored type back, and writing that read-back again leaves the file as it is."""
    read_back = _read_back_types(fqn)
    assert read_back == expected, "the model must read %r back, got %r" % (expected, read_back)
    before = read_disk(mdo_path)
    again = _set_type(fqn, [_spec_item(name) for name in read_back])
    assert_ok(again, "writing the read-back type of %s again" % fqn)
    assert read_disk(mdo_path) == before, "writing the read-back type again must leave %s unchanged" % mdo_path


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_generic_document_ref_on_a_register_dimension_reaches_disk_and_reads_back():
    assert wait_for_project_ready(), "EDT must finish indexing before the write"
    fqn = "InformationRegister.QueryPrices.Dimension.Item"

    r = _set_type(fqn, [{"kind": "DocumentRef"}])
    assert_ok(r, "the generic DocumentRef on an information register dimension")

    # DISK FIRST: the dimension's own type block, as EDT exported it.
    stored = _member_types(read_disk(PRICES_MDO), [("dimensions", "Item")])
    assert stored == ["DocumentRef"], "the dimension must store exactly the generic DocumentRef: %r" % (stored,)
    assert "type" in (r.structured.get("applied") or []), "the type must be reported as applied: %r" % (r.structured,)
    _assert_round_trip(fqn, PRICES_MDO, ["DocumentRef"])


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_generic_document_ref_spelled_in_russian_on_a_tabular_section_attribute():
    assert wait_for_project_ready(), "EDT must finish indexing before the write"
    fqn = "Document.QueryOrder.TabularSection.Items.Attribute.Quantity"

    r = _set_type(fqn, [{"kind": RU_DOCUMENT_REF}])
    assert_ok(r, "the generic DocumentRef in its Russian spelling on a tabular-section attribute")

    stored = _member_types(read_disk(ORDER_MDO), [("tabularSections", "Items"), ("attributes", "Quantity")])
    assert stored == ["DocumentRef"], "the Russian spelling must store the platform's DocumentRef: %r" % (stored,)
    _assert_round_trip(fqn, ORDER_MDO, ["DocumentRef"])


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_generic_and_concrete_reference_mix_in_one_composite_type():
    assert wait_for_project_ready(), "EDT must finish indexing before the write"
    fqn = "AccumulationRegister.QueryStock.Dimension.Item"

    r = _set_type(fqn, [{"kind": "DocumentRef"}, {"kind": "CatalogRef", "ref": "Catalog"}])
    assert_ok(r, "a composite of the generic DocumentRef and one catalog's reference")

    stored = _member_types(read_disk(STOCK_MDO), [("dimensions", "Item")])
    assert sorted(stored) == ["CatalogRef.Catalog", "DocumentRef"], \
        "the dimension must store both the generic and the concrete reference: %r" % (stored,)
    _assert_round_trip(fqn, STOCK_MDO, stored)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_generic_reference_is_refused_where_edts_type_editor_does_not_offer_it():
    assert wait_for_project_ready(), "EDT must finish indexing before the write"
    before = tree_snapshot()

    r = _set_type("AccumulationRegister.QueryStock.Resource.Quantity", [{"kind": "CatalogRef"}])

    e = assert_error(r, "the generic CatalogRef on an accumulation register resource")
    assert_error_quality(e, names=["CatalogRef", "AccumulationRegisterResource 'Quantity'"],
                         suggests=["EDT's type editor", "ref:'Name'"],
                         ctx="the refusal must name the type, the owner EDT judged and the concrete form")
    assert_not_contains(e, "Invalid member 'ref'", "the shape must no longer refuse an omitted ref")
    assert_tree_unchanged(before, "a refused type must not change the fixture")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_ref_naming_a_metadata_type_points_to_the_generic_kind():
    assert wait_for_project_ready(), "EDT must finish indexing before the write"
    before = tree_snapshot()

    r = _set_type("InformationRegister.QueryPrices.Dimension.Item", [{"kind": "Ref", "ref": "Document"}])

    e = assert_error(r, "{kind:'Ref', ref:'Document'} names a type, not an object")
    assert_contains(e, "Cannot resolve the reference target for kind 'Ref' ref 'Document'",
                    "the refusal keeps its unresolved-reference opening")
    assert_contains(e, "{kind:'DocumentRef'} without ref", "the refusal must point to the one generic grammar")
    assert_tree_unchanged(before, "a refused type must not change the fixture")
