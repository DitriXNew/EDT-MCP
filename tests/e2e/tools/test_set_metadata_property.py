# -*- coding: utf-8 -*-
"""
e2e tests for set_metadata_property (kind: write-metadata).

The tool sets the Comment and/or Synonym of an existing metadata object, or one
of its attributes (objectFqn [+ attributeName]), inside a BM WRITE transaction
(AbstractMetadataWriteTool -> BmTransactions.write), then force-exports the TOP
object's .mdo to disk. It is a pure property EDIT: there is NO confirm/preview
branch and NO cascade (unlike delete/rename), so this file covers the happy edit
+ its negative matrix only.

SCOPE: only Comment and Synonym. Other properties (type, flags, ...) are out of
scope and the tool steers the caller to the export/import XML path.

HOW THE EFFECT IS VERIFIED (same approach as test_add_metadata_attribute.py):
  Metadata-write tools mutate EDT's IN-MEMORY BM model AND force-export to the
  object's .mdo on disk. So happy paths verify BOTH channels:
    * MODEL READ-BACK over the wire: get_metadata_details(full=true) renders the
      object's Basic Properties table (Name / Synonym / Comment) and each
      attribute row (Name / Synonym / Type ...). NOTE: the formatter renders the
      object's Comment AND the object's/attribute's Synonym, but does NOT render
      an ATTRIBUTE's Comment -- so an attribute-Comment edit is verified ON DISK
      only (see test_set_attribute_comment_persists_on_disk).
    * ON DISK: the change lands in Catalog.mdo as a <comment> / <synonym> element,
      asserted via poll_diff_contains (forceExport can lag a beat, hence poll).

  The fixture Catalog.Catalog ships with synonym en=Catalog, NO comment, and one
  attribute "Attribute" (synonym en=Attribute, no comment) -- see
  TestConfiguration/src/Catalogs/Catalog/Catalog.mdo. The probe values below are
  unique strings absent before the edit, so a no-op / broken write leaves the
  read-back and the diff unchanged and the test FAILS.

  The orchestrator reverts the on-disk .mdo via reset_fixture (git) and resyncs
  the model via reset_model() (clean_project) AFTER each write-metadata test, so
  every test starts clean. We never reset ourselves.

ERROR-QUALITY note on Gson: ToolResult.toJson() is delimiter-friendly, but the
existing suite anchors errors on delimiter-free substrings (e.g. "not found",
"get_metadata_objects", the bad value) for robustness; we follow that style.

Negative matrix (whole-call errors; server sets isError via ToolResult.error):
  missing projectName / objectFqn; neither comment nor synonym; non-existent
  project; non-existent object; non-existent attribute. Each rejected write
  additionally asserts assert_no_diff() (a rejected write must not touch disk).
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    poll_diff_contains,
    e2e_test,
    PROJECT,
)


def _details_full(object_fqn):
    """Read the object back in full mode (renders Basic Properties + the Attributes table)."""
    return call("get_metadata_details", {
        "projectName": PROJECT,
        "objectFqns": [object_fqn],
        "full": True,
    })


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths — write, then VERIFY VIA MODEL READ-BACK and/or ON-DISK .mdo
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="set_metadata_property", kind="write-metadata")
def test_set_object_comment_appears_in_model_readback_and_on_disk():
    obj = "Catalog.Catalog"
    comment = "E2EObjectComment"  # unique probe; absent from the fixture

    before = _details_full(obj)
    assert_ok(before, "read object before set comment")
    assert_not_contains(before.text, comment,
        "probe comment must be absent before the edit (else the read-back proves nothing)")

    r = call("set_metadata_property", {
        "projectName": PROJECT,
        "objectFqn": obj,
        "comment": comment,
    })
    assert_ok(r, "set_metadata_property Catalog.Catalog comment")

    # MODEL READ-BACK: the object's Comment now renders in the Basic Properties table.
    after = _details_full(obj)
    assert_ok(after, "read object after set comment")
    assert_contains(after.text, comment,
        "the new Comment must appear in the object's model read-back")

    # ON DISK: the comment serializes as a <comment> element in Catalog.mdo.
    poll_diff_contains("<comment>%s</comment>" % comment,
        ctx="set_metadata_property must serialize the Comment into Catalog.mdo")


@e2e_test(tool="set_metadata_property", kind="write-metadata")
def test_set_object_synonym_en_appears_in_model_readback_and_on_disk():
    obj = "Catalog.Catalog"
    synonym = "E2EObjEnSynonym"  # unique probe value

    before = _details_full(obj)
    assert_ok(before, "read object before set synonym")
    assert_not_contains(before.text, synonym, "probe synonym must be absent before the edit")

    r = call("set_metadata_property", {
        "projectName": PROJECT,
        "objectFqn": obj,
        "synonym": synonym,
        "language": "en",
    })
    assert_ok(r, "set_metadata_property Catalog.Catalog synonym en")

    # MODEL READ-BACK: the object's Synonym now renders in the Basic Properties table.
    after = _details_full(obj)
    assert_ok(after, "read object after set synonym")
    assert_contains(after.text, synonym,
        "the new Synonym must appear in the object's model read-back")

    # ON DISK: the synonym value serializes under the en key in Catalog.mdo.
    poll_diff_contains("<value>%s</value>" % synonym,
        ctx="set_metadata_property must serialize the Synonym into Catalog.mdo")


@e2e_test(tool="set_metadata_property", kind="write-metadata")
def test_set_attribute_synonym_appears_in_model_readback():
    # An attribute's Synonym IS rendered (the Attributes-table Synonym column), so the
    # attribute-target path is verified via model read-back here. The fixture attribute
    # is literally named "Attribute"; we give it a unique synonym.
    obj = "Catalog.Catalog"
    attr = "Attribute"
    synonym = "E2EAttrEnSynonym"

    before = _details_full(obj)
    assert_ok(before, "read object before set attribute synonym")
    assert_not_contains(before.text, synonym, "probe attribute synonym must be absent before the edit")

    r = call("set_metadata_property", {
        "projectName": PROJECT,
        "objectFqn": obj,
        "attributeName": attr,
        "synonym": synonym,
        "language": "en",
    })
    assert_ok(r, "set_metadata_property attribute synonym")

    after = _details_full(obj)
    assert_ok(after, "read object after set attribute synonym")
    assert_contains(after.text, synonym,
        "the attribute's new Synonym must appear in the model read-back")
    # The attribute Name itself is untouched (the edit must not rename it).
    assert_contains(after.text, attr, "the attribute must keep its Name after the synonym edit")


@e2e_test(tool="set_metadata_property", kind="write-metadata")
def test_set_attribute_comment_persists_on_disk():
    # An ATTRIBUTE's Comment is NOT rendered by get_metadata_details (the Attributes
    # table has no Comment column), so this edit is verified ON DISK only: the comment
    # serializes as a <comment> element nested under the <attributes> block in Catalog.mdo.
    obj = "Catalog.Catalog"
    attr = "Attribute"
    comment = "E2EAttrComment"  # unique probe; appears nowhere else in the .mdo

    r = call("set_metadata_property", {
        "projectName": PROJECT,
        "objectFqn": obj,
        "attributeName": attr,
        "comment": comment,
    })
    assert_ok(r, "set_metadata_property attribute comment")

    poll_diff_contains("<comment>%s</comment>" % comment,
        ctx="attribute Comment must serialize as a <comment> element in Catalog.mdo")


@e2e_test(tool="set_metadata_property", kind="write-metadata")
def test_set_object_synonym_russian_is_bilingual():
    # Bilingual proof: the synonym EMap is keyed by the language CODE ("ru"), independent
    # of the existing en synonym. This test asserts the ru value LANDS (model read-back +
    # on disk). The "ru does not wipe the en entry" guarantee is structural — getSynonym()
    # .put("ru", ...) only touches the "ru" key — so an additive write shows the diff ONLY
    # ADDING the ru entry (the untouched en entry never appears in the diff); a destructive
    # rewrite would instead show the en value on a removed line. Raw Cyrillic is fine here.
    obj = "Catalog.Catalog"
    synonym_ru = "Справочник"  # the Russian word for "catalog" (display name, not the FQN token)

    r = call("set_metadata_property", {
        "projectName": PROJECT,
        "objectFqn": obj,
        "synonym": synonym_ru,
        "language": "ru",
    })
    assert_ok(r, "set_metadata_property Catalog.Catalog synonym ru")

    # MODEL READ-BACK: the Russian synonym value appears (the formatter prefers the
    # config display language, but the value is present in the Synonym map either way).
    after = _details_full(obj)
    assert_ok(after, "read object after set ru synonym")
    assert_contains(after.text, synonym_ru,
        "the Russian Synonym value must appear in the object's model read-back")

    # ON DISK: a new ru synonym entry with the Cyrillic value lands in Catalog.mdo (the
    # diff ADDS it; the pre-existing en entry is not in the diff = additive, not a rewrite).
    poll_diff_contains("<value>%s</value>" % synonym_ru,
        ctx="set_metadata_property must serialize the ru Synonym into Catalog.mdo")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — missing required params (whole-call errors)
# Each rejected write must also leave the project clean on disk.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="set_metadata_property", kind="write-metadata")
def test_missing_project_name_is_error():
    r = call("set_metadata_property", {
        # projectName omitted on purpose
        "objectFqn": "Catalog.Catalog",
        "comment": "E2E",
    })
    e = assert_error(r, "missing projectName")
    assert_error_quality(e, names=["projectName"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="set_metadata_property", kind="write-metadata")
def test_missing_object_fqn_is_error():
    r = call("set_metadata_property", {
        "projectName": PROJECT,
        # objectFqn omitted on purpose
        "comment": "E2E",
    })
    e = assert_error(r, "missing objectFqn")
    assert_error_quality(e, names=["objectFqn"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="set_metadata_property", kind="write-metadata")
def test_neither_comment_nor_synonym_is_error():
    # With objectFqn present but no comment and no synonym, there is nothing to set: the
    # guard rejects EARLY (before any model access) and names both properties.
    r = call("set_metadata_property", {
        "projectName": PROJECT,
        "objectFqn": "Catalog.Catalog",
        # neither comment nor synonym
    })
    e = assert_error(r, "neither comment nor synonym")
    assert_error_quality(e, names=["comment", "synonym"], suggests=["at least one"])
    assert_no_diff("a rejected call must not change the project")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — not-found (object / attribute / project)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="set_metadata_property", kind="write-metadata")
def test_nonexistent_project_is_error():
    bogus = "NoSuchProject_zzz"
    r = call("set_metadata_property", {
        "projectName": bogus,
        "objectFqn": "Catalog.Catalog",
        "comment": "E2E",
    })
    e = assert_error(r, "non-existent project")
    # AbstractMetadataWriteTool.resolveProjectAndConfig -> "Project not found: <name>".
    assert_error_quality(e, names=[bogus], suggests=["not found"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="set_metadata_property", kind="write-metadata")
def test_nonexistent_object_is_error():
    bad = "Catalog.NoSuchCatalog"
    r = call("set_metadata_property", {
        "projectName": PROJECT,
        "objectFqn": bad,
        "comment": "E2E",
    })
    e = assert_error(r, "non-existent object")
    # "Object not found: Catalog.NoSuchCatalog. Check the FQN format ...
    #  Use get_metadata_objects tool to list available objects." -> names + actionable.
    assert_error_quality(e, names=[bad], suggests=["not found", "get_metadata_objects"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="set_metadata_property", kind="write-metadata")
def test_nonexistent_attribute_is_error():
    bad_attr = "NoSuchAttribute"
    r = call("set_metadata_property", {
        "projectName": PROJECT,
        "objectFqn": "Catalog.Catalog",
        "attributeName": bad_attr,
        "comment": "E2E",
    })
    e = assert_error(r, "non-existent attribute")
    # "Attribute not found: 'NoSuchAttribute' on Catalog.Catalog. Use get_metadata_details
    #  to list the object's attributes by Name ..." -> names + actionable.
    assert_error_quality(e, names=[bad_attr], suggests=["not found", "get_metadata_details"])
    assert_no_diff("a rejected call must not change the project")
