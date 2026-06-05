"""
e2e tests for create_metadata (kind: write-metadata).

create_metadata is the unified, FQN-addressed create that folded the former
create_metadata_object (top-level) and add_metadata_attribute (member) tools. It
creates a node addressed by a 1C full-name FQN:
  * top object  -> 'Type.Name'            (e.g. 'Catalog.Products')
  * member      -> 'Type.Name.Kind.Name'  (e.g. 'Catalog.Products.Attribute.Weight',
                   'InformationRegister.Prices.Resource.Sum', 'Enum.Colors.EnumValue.Red')
The kind is inferred from the FQN; type and kind tokens may be English or Russian.

It is a JSON-responseType tool (AbstractMetadataWriteTool -> ResponseType.JSON), so
the payload lives in r.structured ({action:"created", fqn, kind, name, persisted,
[synonym, language], message}); r.text is only the "Done"/"Error" placeholder.
Errors come through ToolResult.error(...) (success:false + error); the harness
surfaces the message via r.error_text().

HOW WE VERIFY:
  PRIMARY is a MODEL READ-BACK over the wire (get_metadata_objects) for top objects,
  and an ON-DISK diff (poll_diff_contains for the owner .mdo) for members. A no-op
  create would leave the read-back / diff without the new name -> the test FAILS.
  assert_no_diff() is the guard for REJECTED (negative) calls only.

reset: kind="write-metadata" -> the orchestrator runs reset_model() (clean_project,
discarding the unsaved create) AFTER each test, so each test starts clean.

Fixture inventory (TestConfiguration, English Names):
  Catalog.Catalog (attribute "Attribute", form ItemForm), CommonModule.Error/OK/Calc,
  CommonForm.Form, Subsystem.Subsystem, CommonAttribute.CommonAttribute,
  SessionParameter.SessionParameter. (No register / enum in the baseline -> tests that
  need one create it first.)
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
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)


def _objects_text(metadata_type):
    """Read back the model's object list for one type as markdown (the client view)."""
    r = call("get_metadata_objects", {"projectName": PROJECT, "metadataType": metadata_type})
    assert_ok(r, "get_metadata_objects read-back (%s)" % metadata_type)
    return r.text


# ──────────────────────────────────────────────────────────────────────────────
# Happy — top-level objects (model read-back)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_top_level_catalog_appears_in_readback():
    name = "E2EUnifiedCatalog"
    assert_not_contains(_objects_text("catalogs"), name, "unique name must NOT pre-exist")

    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog." + name})
    assert_ok(r, "create top-level Catalog.%s" % name)
    assert r.structured is not None, "JSON tool must return structuredContent"
    assert r.structured.get("action") == "created", "must report action=created: %r" % (r.structured,)
    assert r.structured.get("fqn") == "Catalog." + name, "structured.fqn mismatch: %r" % (r.structured,)
    assert r.structured.get("kind") == "Catalog", "kind must be the created EClass: %r" % (r.structured,)

    assert_contains(_objects_text("catalogs"), name,
                    "the new catalog must appear in the model read-back")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_document_with_synonym_echoes_language_code():
    name = "E2EUnifiedDoc"
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Document." + name,
        "properties": [{"name": "synonym", "value": "E2E Doc", "language": "en"}],
    })
    assert_ok(r, "create Document.%s with synonym" % name)
    assert r.structured.get("synonym") == "E2E Doc", "synonym must be echoed: %r" % (r.structured,)
    assert r.structured.get("language"), "a synonym write must echo the resolved language CODE"
    assert_contains(_objects_text("documents"), name, "the new document must appear in the read-back")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_russian_type_token_creates_catalog():
    # The leading TYPE token is bilingual: the Russian Catalog token must create a
    # Catalog (canonicalized to English before lookup). The Name itself is never translated.
    name = "E2ERuUnifiedCat"
    r = call("create_metadata", {
        "projectName": PROJECT,
        # "Справочник" = the Russian token for Catalog
        "fqn": "Справочник." + name,
    })
    assert_ok(r, "create with Russian type token")
    assert r.structured.get("kind") == "Catalog", \
        "Russian type token must produce a Catalog: %r" % (r.structured,)
    assert_contains(_objects_text("catalogs"), name, "the Russian-type create must be visible in read-back")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_persists_object_and_configuration_to_disk():
    name = "E2EUnifiedPersist"
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog." + name})
    assert_ok(r, "create Catalog.%s (on-disk)" % name)
    assert r.structured.get("persisted") is True, \
        "create must report persisted=true once the .mdo is exported: %r" % (r.structured,)
    # The new object's own .mdo carries its <name>, and Configuration.mdo gains the
    # collection reference. The export can lag a beat, so poll.
    poll_diff_contains("<name>%s</name>" % name,
                       ctx="create must write the new object's own .mdo with a <name> element")
    poll_diff_contains("<catalogs>Catalog." + name + "</catalogs>",
                       ctx="create must add the Configuration.mdo collection reference")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — members addressed by FQN (the add_metadata_attribute fold + new kinds)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_attribute_member_on_existing_catalog_persists():
    # Catalog.Catalog exists in the fixture. Add an attribute addressed by its full FQN.
    attr = "E2EUnifiedAttr"
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr})
    assert_ok(r, "create attribute Catalog.Catalog.Attribute.%s" % attr)
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    assert r.structured.get("kind") == "CatalogAttribute", \
        "kind must be the concrete attribute EClass: %r" % (r.structured,)
    # The owner Catalog.Catalog.mdo gains the new attribute's <name>.
    poll_diff_contains("<name>%s</name>" % attr,
                       ctx="the new attribute must land in the owner Catalog.Catalog.mdo on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_register_then_resource_member():
    # No register in the baseline -> create an InformationRegister (top), then a Resource
    # member on it (a NEW kind the former add_metadata_attribute could not create).
    reg = "E2EUnifiedReg"
    r1 = call("create_metadata", {"projectName": PROJECT, "fqn": "InformationRegister." + reg})
    assert_ok(r1, "create InformationRegister.%s" % reg)
    assert_contains(_objects_text("informationRegisters"), reg, "register must be in the read-back")

    # Creating a top object triggers a derived-data rebuild; the dependent member create below
    # would otherwise hit the BUILDING write-guard. Wait for the model to settle.
    wait_for_project_ready()

    res = "E2EUnifiedRes"
    r2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "InformationRegister.%s.Resource.%s" % (reg, res),
    })
    assert_ok(r2, "create Resource member on the new register")
    assert r2.structured.get("kind") == "InformationRegisterResource", \
        "kind must be the concrete register-resource EClass: %r" % (r2.structured,)
    poll_diff_contains("<name>%s</name>" % res,
                       ctx="the new resource must land in the register's .mdo on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_nested_member_creation_is_rejected_for_now():
    # depth-4 tabular section is supported; a member of THAT (depth-6) is gated for now.
    tab = "E2EUnifiedTab"
    r1 = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.TabularSection." + tab})
    assert_ok(r1, "create tabular section (depth-4) must succeed")

    # Let the derived-data rebuild from the tabular-section create settle before the nested call,
    # otherwise the BUILDING write-guard masks the intended "not yet supported" rejection.
    wait_for_project_ready()

    r2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.TabularSection.%s.Attribute.E2ENestedAttr" % tab,
    })
    e = assert_error(r2, "nested member (depth-6) is gated")
    assert_error_quality(e, suggests=["not yet supported"],
                         ctx="nested-member create is rejected with a clear 'not yet supported' message")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — every rejected call: error quality + assert_no_diff()
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_missing_project_name_is_error():
    r = call("create_metadata", {"fqn": "Catalog.E2EShouldNotExist"})
    e = assert_error(r, "missing required projectName")
    assert_error_quality(e, names=["projectName"], suggests=["required"])
    assert_no_diff("a rejected create must not change the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_missing_fqn_is_error():
    r = call("create_metadata", {"projectName": PROJECT})
    e = assert_error(r, "missing required fqn")
    assert_error_quality(e, names=["fqn"], suggests=["required"])
    assert_no_diff("a rejected create must not change the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_nonexistent_project_is_error():
    bogus = "NoSuchProject_ZZZ_e2e"
    r = call("create_metadata", {"projectName": bogus, "fqn": "Catalog.E2EShouldNotExist"})
    e = assert_error(r, "non-existent project")
    assert_error_quality(e, names=[bogus], suggests=["not found", "list_projects"])
    assert_no_diff("a rejected create must not change the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_duplicate_node_is_error():
    # Catalog.Catalog already exists -> creating it again must hit the duplicate guard.
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog"})
    e = assert_error(r, "duplicate node")
    assert_error_quality(e, names=["Catalog.Catalog"], suggests=["already exists"])
    assert_no_diff("a rejected duplicate create must not change the project")


# Top-types newly enabled by removing the hardcoded 8-type allow-list: the EDT factory
# produces default content for any configuration object type. Representative spread incl.
# the user-named services / charts / registers.
_NEWLY_ENABLED_TOP_TYPES = [
    "Subsystem", "HTTPService", "WebService", "ChartOfCharacteristicTypes",
    "ChartOfAccounts", "ChartOfCalculationTypes", "ExchangePlan", "BusinessProcess",
    "Task", "Constant", "CommonCommand", "AccountingRegister", "CalculationRegister",
    "DefinedType", "FilterCriterion", "DocumentJournal",
]


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_all_top_types_incl_services_charts_registers():
    # The hardcoded 8-type allow-list is gone: any configuration top-type the EDT factory can
    # instantiate now creates. Create one of each representative type, then read each back.
    created = []
    failed = []
    for t in _NEWLY_ENABLED_TOP_TYPES:
        wait_for_project_ready()
        name = "E2EChk" + t
        r = call("create_metadata", {"projectName": PROJECT, "fqn": t + "." + name})
        if r.is_error:
            failed.append("%s -> %s" % (t, (r.error_text() or "")[:140]))
            continue
        assert r.structured.get("action") == "created", "%s: %r" % (t, r.structured)
        created.append((t, name))
    assert not failed, "these top types failed to create:\n  " + "\n  ".join(failed)
    # MODEL read-back: each created object resolves by FQN.
    for t, name in created:
        d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [t + "." + name]})
        assert_ok(d, "read-back %s.%s" % (t, name))
        assert_contains(d.text, name, "MODEL read-back: %s.%s present" % (t, name))


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_unknown_type_token_is_error():
    # A gibberish type token cannot resolve a create target.
    bad = "Sprocket.E2EShouldNotExist"
    r = call("create_metadata", {"projectName": PROJECT, "fqn": bad})
    e = assert_error(r, "unknown type token")
    assert_error_quality(e, names=[bad], suggests=["Type.Name"])
    assert_no_diff("a rejected create must not change the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_malformed_fqn_is_error():
    # A bare token (odd arity) cannot resolve a create target and must not fall back.
    bad = "JustAName"
    r = call("create_metadata", {"projectName": PROJECT, "fqn": bad})
    e = assert_error(r, "malformed FQN (no dot)")
    assert_error_quality(e, names=[bad], suggests=["Type.Name"])
    assert_no_diff("a rejected create must not change the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_invalid_identifier_name_is_error():
    bad = "1Bad-Name"
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog." + bad})
    e = assert_error(r, "invalid identifier name")
    assert_error_quality(e, names=[bad], suggests=["must start with"])
    assert_no_diff("a rejected create must not change the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_unsupported_property_is_error():
    # This version applies only synonym / comment; any other property name is rejected.
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.E2EShouldNotExist",
        "properties": [{"name": "indexing", "value": "Index"}],
    })
    e = assert_error(r, "unsupported property name")
    assert_error_quality(e, names=["indexing"], suggests=["synonym, comment", "modify_metadata"])
    assert_no_diff("a rejected create must not change the project")
