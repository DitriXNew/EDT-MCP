"""
e2e tests for create_metadata_object (kind: write-metadata).

create_metadata_object adds a NEW top-level metadata object (Catalog, Document,
InformationRegister, AccumulationRegister, Enum, CommonModule, Report,
DataProcessor) to the configuration via the EDT model object factory + a BM write
transaction (CreateMetadataObjectTool). It returns ResponseType.JSON, so the
machine-readable payload lives in Result.structured ({fqn, metadataType, name,
message, [synonym, language]}); content[0].text is just a placeholder.

HOW WE VERIFY (this is a metadata-WRITE tool — verification differs from the
on-disk batches):

  PRIMARY proof is a MODEL READ-BACK over the wire: after the create we call
  get_metadata_objects and assert the new object NAME appears in its markdown
  table. A broken (no-op) create would leave the read-back without the new name
  -> the test FAILS.

  ON-DISK too: since the metadata-writes-not-persisted-to-disk fix, a create
  force-exports BOTH the new object's own .mdo AND the Configuration.mdo collection
  entry (the parent Configuration is dirtied too). The half-create (object files
  never written) is gone, so a dedicated test asserts both land on disk via
  poll_diff_contains (the export can lag a beat after the call returns, hence poll).

  assert_no_diff() is NOT the happy guardrail (a successful create legitimately
  changes the tree now); it IS used for the REJECTED (negative) calls — a call the
  tool refuses must not touch the project at all.

  The orchestrator calls reset_model() (clean_project — refreshes the model from
  disk, discarding the unsaved create) AFTER every kind='write-metadata' test, so
  each test starts from a clean baseline model. We do NOT reset the model here.

NEGATIVE MATRIX (mandatory): non-existent project; unsupported (but recognized)
type; unknown (gibberish) type; each missing required param (projectName,
metadataType, name); invalid identifier; duplicate name. Each negative asserts
error quality (names the bad value + actionable hint) AND assert_no_diff() — a
rejected create must change nothing on disk.

IMPORTANT — fixture correction vs. the task brief: the brief suggested using
"Catalog" as an UNSUPPORTED create type. That is wrong against the real code:
CreateMetadataObjectTool.SUPPORTED_TYPES INCLUDES "Catalog". The genuine
"recognized type that create refuses" is e.g. Subsystem / Constant / Role
(known to MetadataTypeUtils but absent from SUPPORTED_TYPES). We use Subsystem,
which is also a real fixture object, and exercise the real error branch
("... is not supported for creation").
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


# Names unique to this suite so a stale model entry from elsewhere can never be
# mistaken for our own creation (mutation thinking: the read-back must see THIS
# name, which does not exist in the committed baseline).
_NEW_CATALOG = "E2ECreatedCatalog"
_NEW_ENUM = "E2ECreatedEnum"
_NEW_COMMON_MODULE = "E2ECreatedCommonModule"


def _objects_text(metadata_type):
    """Read back the model's object list for one type as markdown (the client view)."""
    r = call("get_metadata_objects", {
        "projectName": PROJECT,
        "metadataType": metadata_type,
    })
    assert_ok(r, "get_metadata_objects read-back (%s)" % metadata_type)
    return r.text


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths — create, then VERIFY VIA MODEL READ-BACK
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_create_catalog_appears_in_model_readback():
    # Pre-condition the test would silently pass on if the baseline already had it:
    # the unique name must be ABSENT before we create it. reset_fixture() + the
    # orchestrator's reset_model() guarantee the baseline, but assert it explicitly
    # so a no-op create cannot be "covered" by a pre-existing object.
    before = _objects_text("catalogs")
    assert_not_contains(before, _NEW_CATALOG,
                        "the unique catalog name must NOT pre-exist in the baseline model")

    r = call("create_metadata_object", {
        "projectName": PROJECT,
        "metadataType": "Catalog",
        "name": _NEW_CATALOG,
    })
    assert_ok(r, "create Catalog.%s" % _NEW_CATALOG)
    # JSON response: the structured echo confirms the canonical type + fqn + name.
    assert r.structured is not None, "create_metadata_object must return structuredContent (JSON tool)"
    assert r.structured.get("fqn") == "Catalog." + _NEW_CATALOG, \
        "structured.fqn must be the created FQN, got: %r" % (r.structured.get("fqn"),)
    assert r.structured.get("metadataType") == "Catalog", \
        "structured.metadataType must be the canonical English singular, got: %r" % (r.structured.get("metadataType"),)

    # PRIMARY proof: the model now lists the new object. A no-op create -> absent -> FAIL.
    after = _objects_text("catalogs")
    assert_contains(after, _NEW_CATALOG,
                    "the newly created catalog must appear in get_metadata_objects (model read-back)")


@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_create_enum_appears_in_model_readback():
    # A second supported type (Enum) so the read-back proof is not specific to one
    # collection wiring. Filter the read-back to enums so the name match is precise.
    before = _objects_text("enums")
    assert_not_contains(before, _NEW_ENUM, "the unique enum name must NOT pre-exist")

    r = call("create_metadata_object", {
        "projectName": PROJECT,
        "metadataType": "Enum",
        "name": _NEW_ENUM,
    })
    assert_ok(r, "create Enum.%s" % _NEW_ENUM)
    assert r.structured is not None, "JSON tool must return structuredContent"
    assert r.structured.get("fqn") == "Enum." + _NEW_ENUM, \
        "structured.fqn mismatch: %r" % (r.structured.get("fqn"),)

    after = _objects_text("enums")
    assert_contains(after, _NEW_ENUM,
                    "the newly created enum must appear in the model read-back")


@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_create_common_module_with_synonym_echoes_language_code():
    # CommonModule is supported; supplying a synonym exercises the synonym-by-
    # language-CODE path (resolveLanguage -> MetadataLanguageUtils). The tool
    # echoes the synonym + the RESOLVED language code; both are non-null together.
    before = _objects_text("commonModules")
    assert_not_contains(before, _NEW_COMMON_MODULE, "the unique common-module name must NOT pre-exist")

    r = call("create_metadata_object", {
        "projectName": PROJECT,
        "metadataType": "CommonModule",
        "name": _NEW_COMMON_MODULE,
        "synonym": "E2E Created Module",
    })
    assert_ok(r, "create CommonModule.%s with synonym" % _NEW_COMMON_MODULE)
    assert r.structured is not None, "JSON tool must return structuredContent"
    # The synonym was written, so the echo must carry it AND a non-empty language code.
    assert r.structured.get("synonym") == "E2E Created Module", \
        "the created synonym must be echoed back, got: %r" % (r.structured.get("synonym"),)
    lang = r.structured.get("language")
    assert lang, "a synonym write must echo the resolved language CODE, got: %r" % (lang,)

    after = _objects_text("commonModules")
    assert_contains(after, _NEW_COMMON_MODULE,
                    "the newly created common module must appear in the model read-back")


@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_russian_type_token_creates_catalog_under_english_collection():
    # The metadata TYPE token is bilingual: "Справочник" (Russian for Catalog) must
    # create the SAME kind of object as "Catalog" — the canonical type is resolved
    # to English ("Catalog") before lookup (MetadataTypeUtils.toEnglishSingular).
    # The programmatic Name itself is never translated.
    name = "E2ERuTypeCatalog"
    before = _objects_text("catalogs")
    assert_not_contains(before, name, "the unique name must NOT pre-exist")

    r = call("create_metadata_object", {
        "projectName": PROJECT,
        "metadataType": "Справочник",  # Справочник
        "name": name,
    })
    assert_ok(r, "create with Russian type token Справочник")
    assert r.structured is not None, "JSON tool must return structuredContent"
    # Canonical type echoed in English regardless of the Russian input token.
    assert r.structured.get("metadataType") == "Catalog", \
        "Russian type token must canonicalize to 'Catalog', got: %r" % (r.structured.get("metadataType"),)

    after = _objects_text("catalogs")
    assert_contains(after, name,
                    "the Russian-type-token create must produce a Catalog visible in the read-back")


@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_create_persists_object_and_configuration_to_disk():
    # ON-DISK regression guard for the metadata-writes-not-persisted-to-disk fix: a
    # create now force-exports BOTH the new object's own .mdo AND the Configuration.mdo
    # collection entry (the parent Configuration is dirtied too). Before the fix the
    # object files were never written (half-create) and the git diff was empty/partial
    # at call time; a regression to that behaviour now FAILS here.
    name = "E2EPersistCatalog"
    r = call("create_metadata_object", {
        "projectName": PROJECT,
        "metadataType": "Catalog",
        "name": name,
    })
    assert_ok(r, "create Catalog.%s (on-disk persistence)" % name)
    assert r.structured is not None and r.structured.get("persisted") is True, \
        "create must report persisted=true once the .mdo is force-exported, got: %r" \
        % (r.structured,)

    # Model read-back (the object is genuinely in the model).
    after = _objects_text("catalogs")
    assert_contains(after, name, "PRIMARY: new catalog visible in the model read-back")

    # ON DISK: the new object's own .mdo carries its <name> (the half-create fix), and
    # Configuration.mdo gains the "Catalog.<name>" collection reference. The forceExport
    # flush can lag a beat after the call returns, so poll rather than a bare diff.
    # WHAT changed, not just that something did: the new object's own .mdo must carry
    # its name as a proper <name>E2EPersistCatalog</name> element (the half-create fix —
    # the object file is written, not just a Configuration entry).
    poll_diff_contains("<name>%s</name>" % name,
                       ctx="create must write the new object's own .mdo with a <name> element (half-create fix)")
    # Guard the DUAL-flush half specifically: the "<catalogs>Catalog.<name></catalogs>"
    # collection element appears ONLY in Configuration.mdo (not the object's own .mdo,
    # which would also contain "Catalog.<name>." in default StandardAttribute paths). A
    # regression to single-flush (object exported, Configuration lagging) FAILS here.
    poll_diff_contains("<catalogs>Catalog." + name + "</catalogs>",
                       ctx="create must add the Configuration.mdo collection reference on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — every rejected call: error quality + assert_no_diff()
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_missing_project_name_is_error():
    r = call("create_metadata_object", {
        # projectName omitted
        "metadataType": "Catalog",
        "name": "E2EShouldNotExist",
    })
    e = assert_error(r, "missing required projectName")
    # JsonUtils.requireArgument -> "projectName is required. Usage: {...}"
    assert_error_quality(e, names=["projectName"], suggests=["required"])
    # The usage hint shows the canonical call shape -> actionable.
    assert_contains(e, "metadataType", "the projectName guard hint shows the call shape")
    assert_no_diff("a rejected create must not change the project")


@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_missing_metadata_type_is_error():
    r = call("create_metadata_object", {
        "projectName": PROJECT,
        # metadataType omitted
        "name": "E2EShouldNotExist",
    })
    e = assert_error(r, "missing required metadataType")
    # "metadataType is required. Supported: Catalog, Document, ..." -> names the param,
    # lists the supported types (actionable: tells the caller what to pass).
    assert_error_quality(e, names=["metadataType"], suggests=["required", "Catalog"])
    assert_no_diff("a rejected create must not change the project")


@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_missing_name_is_error():
    r = call("create_metadata_object", {
        "projectName": PROJECT,
        "metadataType": "Catalog",
        # name omitted
    })
    e = assert_error(r, "missing required name")
    # "name is required. Usage: {metadataType: 'Catalog', name: 'Products'}"
    assert_error_quality(e, names=["name"], suggests=["required"])
    assert_contains(e, "Usage", "the name guard shows a usage example")
    assert_no_diff("a rejected create must not change the project")


@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_unknown_metadata_type_is_error():
    bad = "Sprocket"  # not any English/Russian metadata type -> toEnglishSingular == null
    r = call("create_metadata_object", {
        "projectName": PROJECT,
        "metadataType": bad,
        "name": "E2EShouldNotExist",
    })
    e = assert_error(r, "unknown metadata type")
    # "Unknown metadata type: Sprocket". It names the bad value.
    assert_error_quality(e, names=[bad])
    # AUDIT: "Unknown metadata type: <x>" names the bad token but does NOT list the
    # supported/creatable types, unlike the missing-metadataType guard which does.
    # An unknown-type caller gets no recovery hint here. Weakly actionable.
    # Fix-card: append the SUPPORTED_TYPES list (or a "see metadataType schema" note)
    # to the unknown-type branch in CreateMetadataObjectTool.executeInternal.
    assert_no_diff("a rejected create must not change the project")


@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_recognized_but_unsupported_type_is_error():
    # Subsystem IS a recognized metadata type (MetadataTypeUtils knows it) but is
    # NOT in CreateMetadataObjectTool.SUPPORTED_TYPES, so create must refuse with
    # the "not supported for creation" branch (a DIFFERENT error from an unknown
    # type). This is the genuine unsupported-type case (NOT "Catalog", which IS
    # supported — see module docstring).
    r = call("create_metadata_object", {
        "projectName": PROJECT,
        "metadataType": "Subsystem",
        "name": "E2EShouldNotExist",
    })
    e = assert_error(r, "recognized-but-unsupported type Subsystem")
    # "Metadata type 'Subsystem' is not supported for creation. Supported: ..."
    # Names the bad type, states it is unsupported, AND lists what IS creatable.
    assert_error_quality(e, names=["Subsystem"], suggests=["not supported", "Catalog"])
    assert_no_diff("a rejected create must not change the project")


@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_invalid_identifier_name_is_error():
    bad = "1Bad-Name"  # starts with a digit and contains '-' -> not a valid 1C identifier
    r = call("create_metadata_object", {
        "projectName": PROJECT,
        "metadataType": "Catalog",
        "name": bad,
    })
    e = assert_error(r, "invalid object name")
    # "Invalid object name '1Bad-Name'. A name must start with a letter or underscore..."
    # Names the bad value AND states the identifier rule -> actionable.
    assert_error_quality(e, names=[bad], suggests=["must start with"])
    assert_no_diff("a rejected create must not change the project")


@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_duplicate_object_name_is_error():
    # Catalog.Catalog already exists in the fixture. Creating a Catalog with the
    # same Name must hit the duplicate guard, NOT create a second one.
    r = call("create_metadata_object", {
        "projectName": PROJECT,
        "metadataType": "Catalog",
        "name": "Catalog",
    })
    e = assert_error(r, "duplicate object name Catalog.Catalog")
    # "Object already exists: Catalog.Catalog" -> names the conflicting FQN.
    assert_error_quality(e, names=["Catalog.Catalog"], suggests=["already exists"])
    # AUDIT: "Object already exists: <fqn>" names the conflict but offers no next
    # step (e.g. "choose a different name" / "use rename_metadata_object to rename
    # the existing one"). Names-but-not-actionable. Fix-card: append guidance to
    # the duplicate branch in CreateMetadataObjectTool.executeInternal.
    assert_no_diff("a rejected (duplicate) create must not change the project")


@e2e_test(tool="create_metadata_object", kind="write-metadata")
def test_nonexistent_project_is_error():
    bogus = "NoSuchProject_zzz"
    r = call("create_metadata_object", {
        "projectName": bogus,
        "metadataType": "Catalog",
        "name": "E2EShouldNotExist",
    })
    e = assert_error(r, "non-existent project")
    # AbstractMetadataWriteTool.resolveProjectAndConfig -> "Project not found: <name>".
    assert_error_quality(e, names=[bogus], suggests=["not found"])
    # AUDIT: "Project not found: <name>" names the bad project but offers no next
    # step (e.g. "use list_projects to see available projects"). Names-but-not-
    # actionable. Fix-card: append a list_projects hint to resolveProjectAndConfig.
    assert_no_diff("a rejected create (bad project) must not change the project")
