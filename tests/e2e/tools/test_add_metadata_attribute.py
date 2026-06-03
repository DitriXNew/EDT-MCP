"""
e2e tests for add_metadata_attribute (kind: write-metadata).

The tool adds a new attribute to a metadata object (Catalog/Document/Register/
ChartOf*/BusinessProcess/Task/DataProcessor/Report/...) inside a BM WRITE
transaction (AbstractMetadataWriteTool -> BmTransactions.write). It is a pure
ADD: there is NO confirm/preview branch and NO cascade (unlike delete/rename),
so this file covers the happy add + its negative matrix only.

HOW THE EFFECT IS VERIFIED (this batch is different from the on-disk batches):
  Metadata-write tools mutate EDT's IN-MEMORY BM model but do NOT flush the new
  attribute to the object's .mdo on disk synchronously -- a git diff is EMPTY and
  therefore PROVES NOTHING about the add. So the happy path verifies the effect
  via a MODEL READ-BACK over the wire: after the write we call
  get_metadata_details(full=true) on the parent and assert the new attribute row
  appears in the rendered Attributes table (UniversalMetadataFormatter renders
  each BasicFeature attribute as a markdown row whose first cell is its Name).
  The fixture Catalog.Catalog starts with exactly ONE attribute, "Attribute"
  (TestConfiguration/src/Catalogs/Catalog/Catalog.mdo), and the probe name is
  absent before the add -> a no-op / broken write leaves the read-back unchanged
  and the test FAILS.

  ON-DISK too: since the metadata-writes-not-persisted-to-disk fix, the add is
  force-exported to the parent's .mdo on disk (not just the in-memory model), so the
  happy test ALSO asserts the attribute lands in Catalog.mdo via poll_diff_contains
  (the export can lag a beat after the call returns, hence poll not a bare diff). A
  write that mutated only the model would now FAIL this on-disk check.

  The orchestrator reverts the on-disk .mdo via reset_fixture (git) and resyncs the
  model via reset_model() (clean_project) AFTER each write-metadata test, so every
  test starts clean. We never reset ourselves.

ERROR-QUALITY note on Gson escaping: ToolResult.toJson() HTML-escapes the
apostrophe (1C-style 'X' quoting) to \\uXXXX in the JSON text channel, so
assertions only ever match delimiter-free substrings (e.g. "Catalog.Products",
"does not support attributes", "CommonModule"), never a raw 'name'.

Negative matrix (whole-call errors; server sets isError via ToolResult.error):
  missing projectName / parentFqn / attributeName; invalid identifier;
  non-existent project; non-existent parent object; unsupported parent TYPE
  (CommonModule is not an attribute-bearing object); duplicate attribute name.
Each rejected write additionally asserts assert_no_diff() (a rejected write must
not touch disk -- the model-only mutation never even happens on the error paths).
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


def _details_full(parent_fqn):
    """Read the parent back in full mode (renders the Attributes table as markdown rows)."""
    return call("get_metadata_details", {
        "projectName": PROJECT,
        "objectFqns": [parent_fqn],
        "full": True,
    })


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths — write, then VERIFY VIA MODEL READ-BACK (not git diff)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_add_attribute_appears_in_model_readback():
    parent = "Catalog.Catalog"
    new_attr = "E2EWeight"

    # Precondition: the probe attribute must NOT already exist in the model. If it
    # did, the post-write read-back would be a self-fulfilling pass. The fixture
    # ships Catalog.Catalog with only the attribute named "Attribute".
    before = _details_full(parent)
    assert_ok(before, "read parent before add")
    assert_not_contains(before.text, new_attr,
        "probe attribute must be absent before the add (else the read-back proves nothing)")

    r = call("add_metadata_attribute", {
        "projectName": PROJECT,
        "parentFqn": parent,
        "attributeName": new_attr,
    })
    assert_ok(r, "add_metadata_attribute Catalog.Catalog/E2EWeight")

    # VERIFY THE EFFECT IN THE MODEL: the new attribute now renders as a row in the
    # full-mode Attributes table. A no-op write leaves this absent -> FAILS.
    after = _details_full(parent)
    assert_ok(after, "read parent after add")
    assert_contains(after.text, new_attr,
        "the newly added attribute must appear in the parent's model read-back")
    # The pre-existing fixture attribute is still there (the add must not wipe it).
    assert_contains(after.text, "Attribute",
        "the pre-existing fixture attribute must remain after the add")
    # ON DISK: the attribute must be serialized into the parent's .mdo as a proper
    # <name> element (not just the name appearing SOMEWHERE in the diff) — this asserts
    # WHAT changed, the actual <name>E2EWeight</name> the .mdo gains, so a malformed or
    # mislocated write fails. The probe name appears nowhere else (the catalog's own
    # name is <name>Catalog</name>). forceExport can lag a beat, so poll.
    poll_diff_contains("<name>%s</name>" % new_attr,
        ctx="add_metadata_attribute must serialize the attribute as a <name> element in Catalog.mdo")


@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_add_second_attribute_appears_in_model_readback():
    # add_metadata_attribute creates an attribute with default properties (its schema
    # has only projectName/parentFqn/attributeName — no synonym/type param). Verify the
    # effect via MODEL READ-BACK: the new attribute Name is ABSENT before and PRESENT
    # after, so a no-op write cannot pass.
    parent = "Catalog.Catalog"
    new_attr = "E2EColor"

    before = _details_full(parent)
    assert_ok(before, "read parent before add")
    assert_not_contains(before.text, new_attr, "the attribute must be ABSENT before add (else a no-op passes)")

    r = call("add_metadata_attribute", {
        "projectName": PROJECT,
        "parentFqn": parent,
        "attributeName": new_attr,
    })
    assert_ok(r, "add_metadata_attribute")

    after = _details_full(parent)
    assert_ok(after, "read parent after add")
    assert_contains(after.text, new_attr, "the new attribute Name appears in the model read-back")


@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_add_attribute_russian_type_token_resolves_same_parent():
    # The parentFqn TYPE token is bilingual: "Справочник" (Russian for Catalog)
    # normalizes to "Catalog" (MetadataTypeUtils.normalizeFqn) and resolves to the
    # SAME object. The object Name ("Catalog") is never translated. A regression in
    # the bilingual type resolver would make this FQN miss -> "Parent object not found".
    parent_ru = "Справочник.Catalog"  # Справочник.Catalog
    new_attr = "E2ERuToken"

    r = call("add_metadata_attribute", {
        "projectName": PROJECT,
        "parentFqn": parent_ru,
        "attributeName": new_attr,
    })
    assert_ok(r, "add_metadata_attribute via Russian type token")

    # Verify against the English-token view of the SAME object.
    after = _details_full("Catalog.Catalog")
    assert_ok(after, "read Catalog.Catalog after Russian-token add")
    assert_contains(after.text, new_attr,
        "the Russian type token must resolve to the same Catalog object (attribute present)")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — missing required params (whole-call errors)
# Each rejected write must also leave the project clean on disk.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_missing_project_name_is_error():
    r = call("add_metadata_attribute", {
        # projectName omitted on purpose
        "parentFqn": "Catalog.Catalog",
        "attributeName": "E2EAttr",
    })
    e = assert_error(r, "missing projectName")
    # requireArgument(... ". Usage: {projectName: ..., parentFqn: 'Catalog.Products', ...}")
    # -> "projectName is required. Usage: {...}". Names the param + usage example.
    assert_error_quality(e, names=["projectName"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_missing_parent_fqn_is_error():
    r = call("add_metadata_attribute", {
        "projectName": PROJECT,
        # parentFqn omitted on purpose
        "attributeName": "E2EAttr",
    })
    e = assert_error(r, "missing parentFqn")
    # "parentFqn is required. Examples: 'Catalog.Products', 'Document.SalesOrder'. Usage: ..."
    # Delimiter-free anchors only (apostrophes are \\uXXXX-escaped by Gson).
    assert_error_quality(e, names=["parentFqn"], suggests=["required", "Catalog.Products"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_missing_attribute_name_is_error():
    r = call("add_metadata_attribute", {
        "projectName": PROJECT,
        "parentFqn": "Catalog.Catalog",
        # attributeName omitted on purpose
    })
    e = assert_error(r, "missing attributeName")
    assert_error_quality(e, names=["attributeName"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — invalid values / not-found / unsupported type / duplicate
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_invalid_identifier_attribute_name_is_error():
    # A name starting with a digit / containing a space is not a valid 1C identifier
    # (isValidIdentifier). The guard fires BEFORE any model access.
    bad = "1bad name"
    r = call("add_metadata_attribute", {
        "projectName": PROJECT,
        "parentFqn": "Catalog.Catalog",
        "attributeName": bad,
    })
    e = assert_error(r, "invalid identifier")
    # "Invalid attribute name '1bad name'. A name must start with a letter or
    # underscore ...". The bad value is apostrophe-wrapped (escaped) so we anchor on
    # the delimiter-free substring of the value plus the actionable rule text.
    assert_error_quality(e, names=["1bad name"], suggests=["must start with a letter"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_nonexistent_project_is_error():
    bogus = "NoSuchProject_zzz"
    r = call("add_metadata_attribute", {
        "projectName": bogus,
        "parentFqn": "Catalog.Catalog",
        "attributeName": "E2EAttr",
    })
    e = assert_error(r, "non-existent project")
    # AbstractMetadataWriteTool.resolveProjectAndConfig -> "Project not found: <name>".
    assert_error_quality(e, names=[bogus], suggests=["not found"])
    # AUDIT: "Project not found: <name>" names the bad project but gives NO next step
    # (no "use list_projects to see available projects"). Names-but-not-actionable.
    # Fix-card: append a list_projects discovery hint to resolveProjectAndConfig.
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_nonexistent_parent_object_is_error():
    bad = "Catalog.NoSuchCatalog"
    r = call("add_metadata_attribute", {
        "projectName": PROJECT,
        "parentFqn": bad,
        "attributeName": "E2EAttr",
    })
    e = assert_error(r, "non-existent parent object")
    # "Parent object not found: Catalog.NoSuchCatalog. Check the FQN format ...
    #  Use get_metadata_objects tool to list available objects." -> names + actionable.
    assert_error_quality(e, names=[bad], suggests=["not found", "get_metadata_objects"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_unsupported_parent_type_commonmodule_is_error():
    # CommonModule has no attributes collection -> supportsAttributes() == false.
    # CommonModule.Error/OK/Calc are real fixture modules, so this exercises the
    # TYPE rejection (not a missing-object miss): the object resolves but its kind
    # is wrong. A regression that dropped the type guard would try to add and crash.
    r = call("add_metadata_attribute", {
        "projectName": PROJECT,
        "parentFqn": "CommonModule.Calc",
        "attributeName": "E2EAttr",
    })
    e = assert_error(r, "unsupported parent type (CommonModule)")
    # "Object type 'CommonModule' does not support attributes. Supported types:
    #  Catalog, Document, ... Report, ...". Names the type + lists valid types.
    assert_error_quality(e,
        names=["CommonModule"],
        suggests=["does not support attributes", "Catalog"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_duplicate_attribute_name_is_error():
    # The fixture Catalog.Catalog already has an attribute literally named
    # "Attribute". Re-adding it hits the in-transaction hasAttribute() guard, which
    # throws -> "Failed to add attribute: Attribute already exists: Attribute".
    # The match is case-insensitive (hasAttribute uses equalsIgnoreCase), so a
    # different-case spelling still collides.
    dup = "Attribute"
    r = call("add_metadata_attribute", {
        "projectName": PROJECT,
        "parentFqn": "Catalog.Catalog",
        "attributeName": dup,
    })
    e = assert_error(r, "duplicate attribute name")
    # Delimiter-free anchors: the name "Attribute" + the "already exists" reason.
    assert_error_quality(e, names=[dup], suggests=["already exists"])
    # AUDIT: "Failed to add attribute: Attribute already exists: Attribute" names the
    # value and the reason, but offers no next step (e.g. "pick a different name or
    # use get_metadata_details to inspect existing attributes"). Weakly actionable.
    # Fix-card: append a discovery hint to the duplicate-attribute message.
    assert_no_diff("a rejected duplicate add must not change the project")


@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_expected_not_exists_on_existing_attribute_is_precondition_error():
    # Stale-intent guard: the agent asserts (per a stale snapshot) that the attribute
    # does NOT exist, but the fixture Catalog.Catalog already has "Attribute". With
    # expectedNotExists=true the add is rejected EARLY (before the write transaction)
    # with a precondition-framed, re-read-steering message — distinct from the generic
    # in-transaction "already exists" duplicate error.
    r = call("add_metadata_attribute", {
        "projectName": PROJECT,
        "parentFqn": "Catalog.Catalog",
        "attributeName": "Attribute",
        "expectedNotExists": True,
    })
    e = assert_error(r, "expectedNotExists on an existing attribute")
    # Precondition framing + a re-read steer (get_metadata_details). "Precondition" and
    # "stale" are delimiter-free; the apostrophe around the name is Gson-escaped so we
    # do not anchor on it.
    assert_error_quality(e, names=["precondition"],
                         suggests=["stale", "get_metadata_details"],
                         ctx="expectedNotExists violation is a precondition error, not a generic dup")
    assert_no_diff("a rejected precondition add must not change the project")


@e2e_test(tool="add_metadata_attribute", kind="write-metadata")
def test_expected_not_exists_on_new_attribute_succeeds():
    # The opt-in guard does NOT block a legitimate add: when the attribute genuinely is
    # absent, expectedNotExists=true passes and the add lands (model read-back proves it).
    parent = "Catalog.Catalog"
    new_attr = "E2EGuardedNew"
    before = _details_full(parent)
    assert_not_contains(before.text, new_attr, "probe attribute absent before the guarded add")
    r = call("add_metadata_attribute", {
        "projectName": PROJECT,
        "parentFqn": parent,
        "attributeName": new_attr,
        "expectedNotExists": True,
    })
    assert_ok(r, "expectedNotExists on a genuinely-new attribute must succeed")
    after = _details_full(parent)
    assert_contains(after.text, new_attr, "the guarded add appears in the model read-back")
    poll_diff_contains("<name>%s</name>" % new_attr, ctx="guarded add persists to Catalog.mdo")
