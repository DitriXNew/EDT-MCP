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

import xml.etree.ElementTree as ET

from harness import (
    E2ECallTimeout,
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_diff_contains,
    assert_no_diff,
    assert_tree_unchanged,
    diff,
    poll_diff_contains,
    poll_disk_contains,
    read_disk,
    reset_all_fixtures,
    tree_snapshot,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    TESTS_PROJECT,
    _fail,
)


# The fixture form the kind-resolution probes write to (issue #343).
_KIND_PROBE_FORM = "src/Catalogs/Catalog/Forms/ItemForm/Form.form"


def _objects_text(metadata_type):
    """Read back the model's object list for one type as markdown (the client view)."""
    r = call("get_metadata_objects", {"projectName": PROJECT, "metadataType": metadata_type})
    assert_ok(r, "get_metadata_objects read-back (%s)" % metadata_type)
    return r.text


def _xml_local(tag):
    return tag.rsplit("}", 1)[-1]


def _direct_child_text(node, child_name):
    for child in list(node):
        if _xml_local(child.tag) == child_name:
            return child.text or ""
    return None


def _direct_child_int(node, child_name):
    text = _direct_child_text(node, child_name)
    if text is None or not text.strip():
        return 0
    try:
        return int(text.strip())
    except ValueError:
        _fail("expected <%s> to be an integer, got %r" % (child_name, text))


def _assert_unique_nonzero(ids_by_name, ctx):
    seen = {}
    for name, value in ids_by_name.items():
        if value == 0:
            _fail("%s %s must have a nonzero id: %r" % (ctx, name, ids_by_name))
        if value in seen:
            _fail("%s ids must be unique, but %s and %s both use %s: %r"
                  % (ctx, seen[value], name, value, ids_by_name))
        seen[value] = name


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
def test_create_dimension_member_on_register():
    # Dimension is a member kind distinct from Resource/Attribute. Create a register, then a Dimension.
    reg = "E2EDimReg"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "InformationRegister." + reg}),
              "seed InformationRegister")
    wait_for_project_ready()
    dim = "E2EUnifiedDim"
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "InformationRegister.%s.Dimension.%s" % (reg, dim)})
    assert_ok(r, "create Dimension member on the register")
    assert "Dimension" in (r.structured.get("kind") or ""), \
        "kind must be the concrete register-dimension EClass: %r" % (r.structured,)
    poll_diff_contains("<name>%s</name>" % dim,
                       ctx="the new dimension must land in the register's .mdo on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_enum_value_member():
    # EnumValue is a member kind unique to an Enum. Create an Enum, then a value on it.
    enum = "E2EUnifiedEnum"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Enum." + enum}), "seed Enum")
    wait_for_project_ready()
    val = "E2EUnifiedEnumVal"
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Enum.%s.EnumValue.%s" % (enum, val)})
    assert_ok(r, "create EnumValue member on the enum")
    assert "EnumValue" in (r.structured.get("kind") or ""), \
        "kind must be the concrete EnumValue EClass: %r" % (r.structured,)
    poll_diff_contains("<name>%s</name>" % val,
                       ctx="the new enum value must land in the enum's .mdo on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_nested_tabular_section_attribute():
    # depth-6: a member of a NESTED object. Create a tabular section (depth-4), then an
    # attribute ON that tabular section (depth-6) via in-transaction owner re-navigation.
    tab, attr = "E2EUnifiedTab", "E2ENestedAttr"
    r1 = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.TabularSection." + tab})
    assert_ok(r1, "create tabular section (depth-4) must succeed")

    # The tabular-section create triggers a derived-data rebuild; wait before the dependent nested create.
    wait_for_project_ready()

    r2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.TabularSection.%s.Attribute.%s" % (tab, attr),
    })
    assert_ok(r2, "create nested tabular-section attribute (depth-6)")
    assert r2.structured.get("action") == "created", "must report created: %r" % (r2.structured,)
    assert "Attribute" in (r2.structured.get("kind") or ""), \
        "kind must be the concrete TS-attribute EClass: %r" % (r2.structured,)
    # The tabular section and its nested attribute live inline in the owner Catalog.Catalog.mdo.
    poll_diff_contains("<name>%s</name>" % attr,
                       ctx="the nested attribute must land in the owner Catalog.Catalog.mdo on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_command_member_on_catalog():
    # Object-level Command child via the new 'Command' kind token.
    cmd = "E2EUnifiedCmd"
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Command." + cmd})
    assert_ok(r, "create Catalog.Catalog.Command.%s" % cmd)
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    assert "Command" in (r.structured.get("kind") or ""), \
        "kind must be a command EClass: %r" % (r.structured,)
    poll_diff_contains(cmd,
                       ctx="the new command must be referenced from the owner Catalog.Catalog.mdo on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_chart_of_accounts_inline_special_flags():
    # ChartOfAccounts carries two special INLINE child collections the former tools could not
    # create: accountingFlags (AccountingFlag) and extDimensionAccountingFlags.
    coa = "E2EUnifiedCoA"
    r1 = call("create_metadata", {"projectName": PROJECT, "fqn": "ChartOfAccounts." + coa})
    assert_ok(r1, "create ChartOfAccounts.%s" % coa)
    wait_for_project_ready()

    flag = "E2EAcctFlag"
    r2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "ChartOfAccounts.%s.AccountingFlag.%s" % (coa, flag),
    })
    assert_ok(r2, "create AccountingFlag member")
    assert r2.structured.get("kind") == "AccountingFlag", \
        "kind must be the concrete AccountingFlag EClass: %r" % (r2.structured,)

    # The first member-create triggers a derived-data rebuild; wait before the second child create
    # so it does not hit the BUILDING write-guard.
    wait_for_project_ready()

    ext = "E2EExtFlag"
    r3 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "ChartOfAccounts.%s.ExtDimensionAccountingFlag.%s" % (coa, ext),
    })
    assert_ok(r3, "create ExtDimensionAccountingFlag member")
    assert r3.structured.get("kind") == "ExtDimensionAccountingFlag", \
        "kind must be the concrete ExtDimensionAccountingFlag EClass: %r" % (r3.structured,)
    # Both flags live inline in the chart-of-accounts .mdo on disk.
    poll_diff_contains("<name>%s</name>" % flag, ctx="accountingFlag must land in the ChartOfAccounts .mdo")
    poll_diff_contains("<name>%s</name>" % ext, ctx="extDimensionAccountingFlag must land in the .mdo")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_task_addressing_attribute():
    # Task.addressingAttributes (AddressingAttribute) — an INLINE child unique to a Task.
    task = "E2EUnifiedTask"
    r1 = call("create_metadata", {"projectName": PROJECT, "fqn": "Task." + task})
    assert_ok(r1, "create Task.%s" % task)
    wait_for_project_ready()

    addr = "E2EAddrAttr"
    r2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Task.%s.AddressingAttribute.%s" % (task, addr),
    })
    assert_ok(r2, "create AddressingAttribute member")
    assert r2.structured.get("kind") == "AddressingAttribute", \
        "kind must be the concrete AddressingAttribute EClass: %r" % (r2.structured,)
    poll_diff_contains("<name>%s</name>" % addr,
                       ctx="the addressing attribute must land in the Task .mdo on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_document_journal_column():
    # DocumentJournal.columns (Column) — an INLINE child unique to a DocumentJournal.
    journal = "E2EUnifiedJournal"
    r1 = call("create_metadata", {"projectName": PROJECT, "fqn": "DocumentJournal." + journal})
    assert_ok(r1, "create DocumentJournal.%s" % journal)
    wait_for_project_ready()

    col = "E2EJournalCol"
    r2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "DocumentJournal.%s.Column.%s" % (journal, col),
    })
    assert_ok(r2, "create Column member")
    assert r2.structured.get("kind") == "Column", \
        "kind must be the concrete Column EClass: %r" % (r2.structured,)
    poll_diff_contains("<name>%s</name>" % col,
                       ctx="the journal column must land in the DocumentJournal .mdo on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_template_on_catalog():
    # Template needs the model-object factory to be well-formed (a bare create would skip its type).
    # It is serialized inline in the owner .mdo, like other members. Catalog.Catalog exists.
    tpl = "E2EUnifiedTpl"
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Template." + tpl})
    assert_ok(r, "create Catalog.Catalog.Template.%s" % tpl)
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    assert "Template" in (r.structured.get("kind") or ""), \
        "kind must be a template EClass: %r" % (r.structured,)
    poll_diff_contains("<name>%s</name>" % tpl,
                       ctx="the new template must land in the owner Catalog.Catalog.mdo on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_recalculation_on_calc_register():
    # Recalculation MUST go through the factory so its produced types are wired; a bare
    # EcoreUtil.create would leave them empty. We assert <producedTypes> lands on disk to prove the
    # factory path (anti-cheat: distinguishes a real factory create from a name-only stub).
    reg = "E2EUnifiedCalcReg"
    r1 = call("create_metadata", {"projectName": PROJECT, "fqn": "CalculationRegister." + reg})
    assert_ok(r1, "create CalculationRegister.%s" % reg)
    wait_for_project_ready()

    rc = "E2EUnifiedRecalc"
    r2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "CalculationRegister.%s.Recalculation.%s" % (reg, rc),
    })
    assert_ok(r2, "create Recalculation child on the new register")
    assert "Recalculation" in (r2.structured.get("kind") or ""), \
        "kind must be a Recalculation EClass: %r" % (r2.structured,)
    poll_diff_contains("<name>%s</name>" % rc,
                       ctx="the new recalculation must land in the register .mdo on disk")
    poll_diff_contains("<producedTypes>",
                       ctx="the factory must wire the recalculation's produced types on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_http_service_url_template_and_method():
    # HTTPService -> urlTemplate (depth-4) -> method (depth-6 NESTED). Both inline in the service .mdo.
    svc = "E2EUnifiedHttp"
    r1 = call("create_metadata", {"projectName": PROJECT, "fqn": "HTTPService." + svc})
    assert_ok(r1, "create HTTPService.%s" % svc)
    wait_for_project_ready()

    tmpl = "E2EUrlTmpl"
    r2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "HTTPService.%s.URLTemplate.%s" % (svc, tmpl),
    })
    assert_ok(r2, "create URLTemplate on the HTTP service")
    assert "URLTemplate" in (r2.structured.get("kind") or ""), \
        "kind must be a URLTemplate EClass: %r" % (r2.structured,)
    wait_for_project_ready()

    meth = "E2EHttpMethod"
    r3 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "HTTPService.%s.URLTemplate.%s.Method.%s" % (svc, tmpl, meth),
    })
    assert_ok(r3, "create nested Method on the URL template (depth-6)")
    assert "Method" in (r3.structured.get("kind") or ""), \
        "kind must be a Method EClass: %r" % (r3.structured,)
    poll_diff_contains("<name>%s</name>" % meth,
                       ctx="the nested HTTP method must land in the service .mdo on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_web_service_operation_and_parameter():
    # WebService -> operation (depth-4) -> parameter (depth-6 NESTED). Both inline in the service .mdo.
    svc = "E2EUnifiedWs"
    r1 = call("create_metadata", {"projectName": PROJECT, "fqn": "WebService." + svc})
    assert_ok(r1, "create WebService.%s" % svc)
    wait_for_project_ready()

    op = "E2EWsOp"
    r2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "WebService.%s.Operation.%s" % (svc, op),
    })
    assert_ok(r2, "create Operation on the web service")
    assert "Operation" in (r2.structured.get("kind") or ""), \
        "kind must be an Operation EClass: %r" % (r2.structured,)
    wait_for_project_ready()

    par = "E2EWsParam"
    r3 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "WebService.%s.Operation.%s.Parameter.%s" % (svc, op, par),
    })
    assert_ok(r3, "create nested Parameter on the operation (depth-6)")
    assert "Parameter" in (r3.structured.get("kind") or ""), \
        "kind must be a Parameter EClass: %r" % (r3.structured,)
    poll_diff_contains("<name>%s</name>" % par,
                       ctx="the nested WS parameter must land in the service .mdo on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — FORM OBJECT creation (the BasicForm mdo + a renderable content Form)
# A 4-part form FQN 'Type.Object.Form.FormName' creates the form itself; the content
# form gets the render-critical autoCommandBar + form defaults and is attached under
# its canonical FQN so its structure re-resolves.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_on_catalog():
    # Create a NEW managed form on the fixture Catalog.Catalog and confirm it exists + renders.
    form = "Z_McpNewForm"
    fqn = "Catalog.Catalog.Form." + form
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, "create form object %s" % fqn)
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    assert r.structured.get("kind") == "Form", "kind must be Form: %r" % (r.structured,)
    assert r.structured.get("name") == form, "name must be the form name: %r" % (r.structured,)

    # The new form must register in the owner Catalog.Catalog.mdo on disk (its <forms> entry) and
    # the content Form.form must be written (the form name appears in both).
    poll_diff_contains(form, ctx="the new form must land in the owner .mdo / Form.form on disk")

    # MODEL read-back: get_metadata_details on the form FQN renders its structure (the "# Form
    # Structure" heading proves the content form resolved - i.e. it was attached under the canonical
    # FQN and the editable model is reachable; a store-less attach would fail to resolve here).
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
    assert_ok(d, "render the new form's structure")
    assert_contains(d.text, "Form Structure", "the new form must render a structure (content model resolved)")
    assert_contains(d.text, form, "the rendered structure must name the new form")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_default_seeds_no_attributes():
    # Issue #208: WITHOUT generateContent the form stays EMPTY (today's byte-stable behaviour) - no
    # main Object attribute is seeded. The serialized Form.form must carry no <attributes> block.
    form = "Z_McpEmptyForm"
    form_rel = "src/Catalogs/Catalog/Forms/%s/Form.form" % form
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Form." + form})
    assert_ok(r, "create form object without generateContent")
    assert r.structured.get("generateContent") is False, \
        "the default create must echo generateContent=false: %r" % (r.structured,)
    # Wait on the .form FILE: the form name lands in the owner .mdo first, so waiting on the diff
    # can release before the content form is exported and the read below hits a missing file.
    poll_disk_contains(form_rel, "<autoCommandBar>", ctx="the empty form must land on disk")
    form_xml = read_disk(form_rel)
    assert "<attributes>" not in form_xml, \
        "an unseeded object form must carry no <attributes> block: %s" % form_xml


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_generate_content_seeds_main_object_attribute():
    # Issue #208: generateContent=true seeds the main Object attribute like the designer's "New form"
    # wizard - name Object, type CatalogObject.Catalog (the owner is the fixture Catalog.Catalog),
    # main=true and savedData=true - so an agent never needs to edit the .form outside MCP.
    form = "Z_McpSeededForm"
    fqn = "Catalog.Catalog.Form." + form
    form_rel = "src/Catalogs/Catalog/Forms/%s/Form.form" % form
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn, "generateContent": True})
    assert_ok(r, "create form object with generateContent")
    assert r.structured.get("kind") == "Form", "kind must be Form: %r" % (r.structured,)
    assert r.structured.get("generateContent") is True, \
        "the create must echo generateContent=true: %r" % (r.structured,)
    # Poll for a Form.form CONTENT marker (the seeded Object attribute), NOT just the form name: the
    # name lands in the Catalog.mdo form list BEFORE the .form file is exported, so polling on the name
    # races the async .form write and read_disk below can hit a missing file under e2e write-load.
    poll_diff_contains("<name>Object</name>", ctx="the seeded form's .form content must land on disk")

    # The seeded main Object attribute must serialize with its designer flags + the object value type.
    form_xml = read_disk(form_rel)
    assert "<attributes>" in form_xml, \
        "generateContent must seed an <attributes> block: %s" % form_xml
    assert "<name>Object</name>" in form_xml, \
        "the seeded main attribute must be named Object: %s" % form_xml
    assert "<main>true</main>" in form_xml, \
        "the seeded Object attribute must be the form's main attribute: %s" % form_xml
    assert "<savedData>true</savedData>" in form_xml, \
        "the seeded Object attribute must carry savedData: %s" % form_xml
    assert "CatalogObject.Catalog" in form_xml, \
        "the seeded Object attribute must carry the CatalogObject.Catalog value type: %s" % form_xml

    # The form must still resolve/render with the seeded attribute (a malformed seed would fail here),
    # and the rendered structure must surface the Object attribute.
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
    assert_ok(d, "render the seeded form's structure")
    assert_contains(d.text, "Form Structure", "the seeded form must still render a structure")
    assert_contains(d.text, "Object", "the rendered structure must surface the main Object attribute")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_generate_content_extension_owned_owner_gets_value_type():
    """Issue #262 (Problem 1): generateContent=true used to write the main Object attribute with
    NO <valueType> at all when the OWNER OBJECT was created IN AN EXTENSION project - the
    produced-types path (MdClassUtil.getProducedTypes -> getObjectType) never materializes for an
    extension-own object, and the old code silently skipped the value type instead of falling back.
    FormElementWriter now falls back to resolving the SAME value type (DataProcessorObject.<Name>)
    directly BY NAME through the platform type provider, so the seeded attribute still gets a type
    for an extension-owned DataProcessor.

    Runs against TESTS_PROJECT (the extension fixture): the orchestrator only auto-resets the BASE
    fixture between tests (see harness.reset_fixture), so this test reverts the extension itself
    (disk + in-memory model) in a finally block, mirroring what the suite's own final_cleanup() does
    for both fixtures - a real mutation here must not pollute later extension-reading tests.
    """
    obj, form = "tests_Z262ExtDp", "tests_Z262ExtDpForm"
    fqn = "DataProcessor.%s.Form.%s" % (obj, form)
    try:
        assert_ok(
            call("create_metadata", {"projectName": TESTS_PROJECT, "fqn": "DataProcessor." + obj}),
            "seed the owning DataProcessor IN THE EXTENSION project")
        wait_for_project_ready()
        r = call("create_metadata", {"projectName": TESTS_PROJECT, "fqn": fqn, "generateContent": True})
        assert_ok(r, "create the extension-owned DataProcessor's form with generateContent")
        assert r.structured.get("generateContent") is True, \
            "the create must echo generateContent=true: %r" % (r.structured,)

        # MODEL READ-BACK (the harness has no on-disk diff helper for the extension fixture, only for
        # the base project): the rendered "## Attributes" table's Type column must name the object
        # value type, not just the bare attribute name - a regression to the pre-fix silent skip would
        # still show "Object" with an EMPTY Type cell.
        d = call("get_metadata_details", {"projectName": TESTS_PROJECT, "objectFqns": [fqn]})
        assert_ok(d, "render the extension-owned form's structure")
        assert_contains(d.text, "Object", "the rendered structure must surface the main Object attribute")
        assert_contains(d.text, "DataProcessorObject.%s" % obj,
                        "the main Object attribute must carry the DataProcessorObject value type even "
                        "though the owner was created in an extension (issue #262)")
    except E2ECallTimeout:
        # NO cleanup here: the timed-out call may still be writing these very files, and a git
        # reset would race it. The orchestrator aborts the run on this.
        raise
    except BaseException:
        _restore_extension_fixture()
        raise
    else:
        _restore_extension_fixture()


def _restore_extension_fixture():
    """Revert the EXTENSION fixture on disk, then re-sync ITS in-memory model from the clean disk -
    the same two-step final_cleanup() uses for both fixtures, scoped here to just the extension
    (the orchestrator's kind="write-metadata" post-test hook already handles the BASE fixture)."""
    reset_all_fixtures()
    call("clean_project", {"projectName": TESTS_PROJECT})
    wait_for_project_ready()
    reset_all_fixtures()


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_generate_content_seeds_default_object_fields():
    # Issue #208 round 2: generateContent on a DOCUMENT object form seeds the kind-default bound fields
    # (Number, Date) as InputFields whose dataPath is Object.Number / Object.Date - mirroring the
    # designer's checked-attribute list. The owner is created first so its DocumentObject type resolves.
    doc, form = "Z_McpSeedDoc", "Z_McpSeedDocForm"
    fqn = "Document.%s.Form.%s" % (doc, form)
    form_rel = "src/Documents/%s/Forms/%s/Form.form" % (doc, form)
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Document." + doc}),
              "seed the owning document")
    wait_for_project_ready()
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn, "generateContent": True})
    assert_ok(r, "create document form object with generateContent (default fields)")
    assert r.structured.get("generateContent") is True, \
        "the create must echo generateContent=true: %r" % (r.structured,)
    poll_diff_contains(form, ctx="the seeded document form must land on disk")

    # The form carries the main Object attribute plus the two default bound fields.
    form_xml = read_disk(form_rel)
    assert "<name>Object</name>" in form_xml, \
        "the seeded form must carry the main Object attribute: %s" % form_xml
    # Each default field is an InputField bound to Object.<name>; a multi-segment dataPath serializes
    # as ONE dot-joined <segments> element (Object.Number / Object.Date), not two separate ones.
    for seg in ("Number", "Date"):
        assert "<segments>Object.%s</segments>" % seg in form_xml, \
            "generateContent must seed a bound field for the default attribute %s: %s" % (seg, form_xml)
    # The seeded fields reuse the field builder, so they carry the designer auto-children.
    assert "ContextMenu" in form_xml and "ExtendedTooltip" in form_xml, \
        "the seeded default fields must carry the designer auto-children: %s" % form_xml


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_explicit_object_fields_seeds_only_listed():
    # Issue #208 round 2 (Part 1): an EXPLICIT non-empty objectFields list is seeded VERBATIM - exactly
    # the listed sub-attributes, NOT the per-kind defaults. A Document defaults to Number/Date; passing
    # objectFields=["Number"] must seed the Number field and must NOT seed the default Date field.
    doc, form = "Z_McpPickFieldsDoc", "Z_McpPickFieldsForm"
    fqn = "Document.%s.Form.%s" % (doc, form)
    form_rel = "src/Documents/%s/Forms/%s/Form.form" % (doc, form)
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Document." + doc}),
              "seed the owning document")
    wait_for_project_ready()
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": fqn, "generateContent": True, "objectFields": ["Number"]})
    assert_ok(r, "create document form with an explicit single objectFields list")
    assert r.structured.get("generateContent") is True, \
        "the create must echo generateContent=true: %r" % (r.structured,)
    # Wait on the .form FILE (see above): the name reaches the owner .mdo before the content is
    # exported, and the assertions below read the content form itself.
    poll_disk_contains(form_rel, "<name>Object</name>",
                       ctx="the seeded document form must land on disk")
    form_xml = read_disk(form_rel)
    # The main Object attribute is still seeded, and the one listed field binds to Object.Number.
    assert "<name>Object</name>" in form_xml, \
        "the main Object attribute must still be seeded: %s" % form_xml
    assert "<segments>Object.Number</segments>" in form_xml, \
        "the explicit Number field must bind to the 2-segment Object.Number data path: %s" % form_xml
    # The per-kind default Date field must NOT appear - an explicit list overrides the defaults.
    assert "<segments>Object.Date</segments>" not in form_xml, \
        "an explicit objectFields list must not seed the unlisted default Date field: %s" % form_xml


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_field_bound_to_object_sub_attribute():
    # Issue #208 round 2 (Part 2): a standalone Field may bind to a sub-attribute of the form's main
    # Object attribute via a dotted dataPath (Object.<attr>) - not only a top-level attribute or a
    # dynamic-list column. Seed a Catalog object form (gives the main Object attribute), then bind a
    # field to Object.Description (a real Catalog standard attribute).
    form, fld = "Z_McpObjFieldForm", "DescriptionField"
    base_fqn = "Catalog.Catalog.Form." + form
    form_rel = "src/Catalogs/Catalog/Forms/%s/Form.form" % form
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": base_fqn, "generateContent": True}),
              "seed a Catalog object form with the main Object attribute")
    wait_for_project_ready()
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "%s.Field.%s" % (base_fqn, fld),
        "properties": [{"name": "dataPath", "value": "Object.Description"}]})
    assert_ok(r, "create a Field bound to Object.Description")
    assert "FormField" in (r.structured.get("kind") or ""), \
        "kind must be FormField: %r" % (r.structured,)
    poll_diff_contains("<name>%s</name>" % fld,
                       ctx="the object sub-attribute field must land on disk")
    # The dotted path serialized as ONE dot-joined <segments> element (Object.Description), like a
    # designer-bound field.
    form_xml = read_disk(form_rel)
    assert "<segments>Object.Description</segments>" in form_xml, \
        "the field must bind to the 2-segment Object.Description data path: %s" % form_xml


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_empty_object_fields_seeds_only_main_attribute():
    # Issue #208 round 2 (Part 1): an explicit empty objectFields array seeds the main Object attribute
    # but NO bound fields (the designer's "main attribute only" choice).
    doc, form = "Z_McpEmptyFieldsDoc", "Z_McpEmptyFieldsForm"
    fqn = "Document.%s.Form.%s" % (doc, form)
    form_rel = "src/Documents/%s/Forms/%s/Form.form" % (doc, form)
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Document." + doc}),
              "seed the owning document")
    wait_for_project_ready()
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": fqn, "generateContent": True, "objectFields": []})
    assert_ok(r, "create document form with an empty objectFields list")
    # Wait on the .form FILE (see above): the name reaches the owner .mdo before the content is
    # exported, and the assertions below read the content form itself.
    poll_disk_contains(form_rel, "<name>Object</name>",
                       ctx="the seeded document form must land on disk")
    form_xml = read_disk(form_rel)
    assert "<name>Object</name>" in form_xml, \
        "the main Object attribute must still be seeded: %s" % form_xml
    # No bound field is seeded -> the default attribute names do not appear as field data-path segments.
    assert "<segments>Number</segments>" not in form_xml, \
        "an empty objectFields must seed no bound fields: %s" % form_xml


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_unknown_object_field_is_error():
    # Issue #208 round 2 (review): an EXPLICIT objectFields name that is not a bindable sub-attribute of
    # the owner's Object is a clean, actionable error - it names the bad value and lists the available
    # names (mirrors test_create_form_field_missing_attribute_is_error). The owner is seeded first so its
    # object type + bindable attributes resolve.
    doc, form = "Z_McpBadFieldDoc", "Z_McpBadFieldForm"
    fqn = "Document.%s.Form.%s" % (doc, form)
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Document." + doc}),
              "seed the owning document")
    wait_for_project_ready()
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": fqn, "generateContent": True,
        "objectFields": ["NoSuchAttr_zz"]})
    e = assert_error(r, "form object with a bogus objectFields name")
    assert_error_quality(e, names=["NoSuchAttr_zz"], suggests=["Available"],
                         ctx="an unknown objectFields name is a clean error that lists the available ones")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_then_add_member():
    # End-to-end: create a form OBJECT, then add a member to it via the folded member path. This
    # only works if the content form was created renderable + reachable.
    form, attr = "Z_McpFormThenAttr", "NewAttr"
    r1 = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Form." + form})
    assert_ok(r1, "create form object")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.%s.Attribute.%s" % (form, attr)})
    assert_ok(r2, "add an attribute to the just-created form")
    assert r2.structured.get("action") == "created", "must report created: %r" % (r2.structured,)
    poll_diff_contains(attr, ctx="the member added to the new form must land in its Form.form on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_table_with_columns_and_additions_issue_177():
    # Issue #177: create a form:Table via create_metadata. The table must be built with its auto
    # columns (a LineNumber column + one InputField per tabular-section attribute) AND the three table
    # additions (search string / view status / search control), which are the chrome that renders
    # grey/read-only when not created enabled. Here we assert that whole kit lands on disk and the form
    # still resolves; the additions-enabled invariant itself is locked by the FormElementWriter unit test.
    ts, attr, table = "McpTableTS", "Product", "McpTable"
    form_fqn = "Catalog.Catalog.Form.ItemForm"
    form_rel = "src/Catalogs/Catalog/Forms/ItemForm/Form.form"

    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.TabularSection." + ts}),
        "seed the tabular section the table binds to")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.TabularSection.%s.Attribute.%s" % (ts, attr)}),
        "seed a tabular-section attribute so the table has a data column")
    wait_for_project_ready()

    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "%s.Table.%s" % (form_fqn, table),
        "properties": [{"name": "dataPath", "value": "Object.%s" % ts}],
    })
    assert_ok(r, "create the form table %s" % table)
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    assert "Table" in (r.structured.get("kind") or ""), \
        "kind must be a Table EClass: %r" % (r.structured,)
    wait_for_project_ready()

    # The table and its full kit must be written to the content Form.form on disk.
    form_xml = read_disk(form_rel)
    assert "<name>%s</name>" % table in form_xml, \
        "the table must be written to Form.form: %s" % table
    for addition in ("searchStringAddition", "viewStatusAddition", "searchControlAddition"):
        assert "<%s>" % addition in form_xml, \
            "the table must carry its <%s> (chrome that renders grey when not enabled)" % addition
    assert "<name>%s%s</name>" % (table, attr) in form_xml, \
        "the table must auto-generate an input column for the TS attribute %s" % attr

    # The form must still resolve/render after the table write - a malformed table would fail to resolve.
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [form_fqn]})
    assert_ok(d, "render the form structure after adding a table")
    assert_contains(d.text, "Form Structure", "the form must still render its structure")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_set_as_default_on_data_processor_succeeds():
    """Issue #262 (Problem 2a): setAsDefault=true on a DataProcessor's Form used to fail with
    "Owner type 'DataProcessor' has no compatible setDefaultObjectForm(...) method" - DataProcessor
    (like Report/Task) exposes setDefaultForm, not setDefaultObjectForm. The reflective setter lookup
    now tries setDefaultObjectForm THEN setDefaultForm, so this must succeed and the owner .mdo must
    record the new form as its defaultForm.
    """
    obj, form = "Z_McpDefaultDp262", "Z_McpDefaultForm262"
    fqn = "DataProcessor.%s.Form.%s" % (obj, form)
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "DataProcessor." + obj}),
              "seed the owning DataProcessor")
    wait_for_project_ready()
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn, "setAsDefault": True})
    assert_ok(r, "create the DataProcessor's form with setAsDefault=true (used to error - issue #262)")
    assert r.structured.get("kind") == "Form", "kind must be Form: %r" % (r.structured,)

    # ON-DISK: the owner .mdo gains the exact <defaultForm> reference to the new form (the structural
    # element, not just the bare form name, which would false-match the owner's <forms> list entry).
    poll_diff_contains("<defaultForm>DataProcessor.%s.Form.%s</defaultForm>" % (obj, form),
                       ctx="the owner .mdo must record the new form as its defaultForm")

    # MODEL read-back: the owner's rendered details still resolve and name the new form (a broken
    # default-form assignment that corrupted the owner would fail to render here).
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": ["DataProcessor." + obj]})
    assert_ok(d, "render the owner after setAsDefault")
    assert_contains(d.text, form, "the owner's details must still name the new default form")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_data_processor_form_members_allocate_form_ids_issue_189():
    # Issue #189 end-to-end repro: a managed form and ALL its attributes/items/commands are created by
    # MCP writes. The validator used to see duplicate id=0 for field context menus, the command
    # bar serialized without <id>, and form attributes/commands had no ids at all.
    obj, form = "E2EIdCheck", "Form"
    base = "DataProcessor.%s" % obj
    form_fqn = "%s.Form.%s" % (base, form)
    form_rel = "src/DataProcessors/%s/Forms/%s/Form.form" % (obj, form)

    def create_ok(fqn, properties=None, ctx=None):
        payload = {"projectName": PROJECT, "fqn": fqn}
        if properties is not None:
            payload["properties"] = properties
        r = call("create_metadata", payload)
        assert_ok(r, ctx or ("create " + fqn))
        wait_for_project_ready()
        return r

    create_ok(base, ctx="create the DataProcessor owner")
    # setAsDefault is intentionally omitted here: this repro is about issue #189 (form-item id
    # allocation), not setAsDefault - see test_create_form_object_set_as_default_on_data_processor_
    # succeeds for the issue #262 coverage of setAsDefault on a DataProcessor.
    create_ok(form_fqn, ctx="create the DataProcessor managed form")

    attrs = ["FAttr%d" % i for i in range(1, 8)]
    for i, attr in enumerate(attrs, start=1):
        create_ok("%s.Attribute.%s" % (form_fqn, attr), ctx="create form attribute " + attr)
        r = call("modify_metadata", {
            "projectName": PROJECT,
            "fqn": "%s.Attribute.%s" % (form_fqn, attr),
            "properties": [{"name": "type",
                            "value": {"types": [{"kind": "Number", "precision": 8 + i, "scale": 0}]}}],
        })
        assert_ok(r, "set form attribute type " + attr)
        assert "valueType" in (r.structured.get("applied") or []), \
            "type alias must apply to valueType for %s: %r" % (attr, r.structured)
        wait_for_project_ready()

    fields = ["FField%d" % i for i in range(1, 8)]
    for attr, field in zip(attrs, fields):
        create_ok("%s.Field.%s" % (form_fqn, field),
                  [{"name": "dataPath", "value": attr}],
                  ctx="create field %s bound to %s" % (field, attr))

    cmd, second_cmd, proc, btn = "RunCheck", "ResetCheck", "RunCheckAction", "RunCheckBtn"
    create_ok("%s.Command.%s" % (form_fqn, cmd), ctx="create form command")
    create_ok("%s.Command.%s" % (form_fqn, second_cmd), ctx="create second form command")
    create_ok("%s.Command.%s.Handler.Action" % (form_fqn, cmd),
              [{"name": "procedure", "value": proc}],
              ctx="bind the command Action handler")
    create_ok("%s.Button.%s" % (form_fqn, btn),
              [{"name": "command", "value": cmd}, {"name": "parent", "value": "AutoCommandBar"}],
              ctx="create command-bar button bound to the command")

    poll_diff_contains("<name>%s</name>" % btn,
                       ctx="the fully MCP-created DataProcessor form must be exported to disk")

    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [form_fqn]})
    assert_ok(details, "read the fully MCP-created form structure")
    for name in attrs + fields + [cmd, second_cmd, proc, btn, "AutoCommandBar"]:
        assert_contains(details.text, name, "get_metadata_details must surface " + name)

    reval = call("revalidate_objects", {"projectName": PROJECT, "objects": [form_fqn]})
    assert_ok(reval, "revalidate the MCP-created form")
    assert_contains(reval.text, "status: success", "revalidation must report success")
    assert_contains(reval.text, form_fqn, "revalidation must name the requested form")

    problems = call("get_project_errors", {
        "projectName": PROJECT,
        "objects": [form_fqn],
        "responseFormat": "detailed",
    })
    assert_ok(problems, "read form validation markers")
    assert_not_contains(problems.text, "form-legacy-emf-check",
                        "legacy EMF twin must not report duplicate id=0")
    assert_not_contains(problems.text, "form-invalid-item-id",
                        "autoCommandBar must not report an invalid form item id")
    assert_not_contains(problems.text, "Duplicate id '0'",
                        "duplicate zero-id diagnostics must be gone")

    root = ET.fromstring(read_disk(form_rel))
    attr_ids = {}
    for node in root.iter():
        if _xml_local(node.tag) == "attributes":
            name = _direct_child_text(node, "name") or "<unnamed>"
            attr_ids[name] = _direct_child_int(node, "id")
    for attr in attrs:
        if attr not in attr_ids:
            _fail("created form attribute %s was not serialized: %r" % (attr, sorted(attr_ids)))
    _assert_unique_nonzero(attr_ids, "form attribute")

    command_ids = {}
    for node in root.iter():
        if _xml_local(node.tag) == "formCommands":
            name = _direct_child_text(node, "name") or "<unnamed>"
            command_ids[name] = _direct_child_int(node, "id")
    created_command_ids = {}
    for command in (cmd, second_cmd):
        if command not in command_ids:
            _fail("created form command %s was not serialized: %r"
                  % (command, sorted(command_ids)))
        created_command_ids[command] = command_ids[command]
    _assert_unique_nonzero(created_command_ids, "form command")

    item_tags = ("items", "childItems", "extendedTooltip", "contextMenu", "autoCommandBar")
    item_ids = {}
    for node in root.iter():
        if _xml_local(node.tag) in item_tags:
            name = _direct_child_text(node, "name") or _xml_local(node.tag)
            item_ids[name] = _direct_child_int(node, "id")

    bar_id = item_ids.get("FormCommandBar")
    if bar_id != -1:
        _fail("autoCommandBar must serialize the designer sentinel id -1, got %r in %r"
              % (bar_id, item_ids))

    positive_item_ids = {name: value for name, value in item_ids.items()
                         if name != "FormCommandBar"}
    expected_items = set(fields)
    expected_items.update(field + "ExtendedTooltip" for field in fields)
    expected_items.update(field + "ContextMenu" for field in fields)
    expected_items.add(btn)
    expected_items.add(btn + "ExtendedTooltip")
    missing = expected_items - set(positive_item_ids)
    if missing:
        _fail("expected item/auto-child ids for %r, got %r"
              % (sorted(missing), sorted(positive_item_ids)))
    _assert_unique_nonzero(positive_item_ids, "form item")

    # Namespace guard: attributes and FormItems are independent id spaces. The test fails
    # on zero/duplicates inside either namespace, but it intentionally does not compare
    # attribute ids against item ids.

    before = tree_snapshot()
    dup = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "%s.Command.%s.Handler.Action" % (form_fqn, cmd),
    })
    err = assert_error(dup, "duplicate command Action handler")
    assert_error_quality(err, suggests=["already exists"],
                         ctx="duplicate Action handler must be a clean rejected call")
    assert_tree_unchanged(before, "duplicate Action handler rejection must not mutate the dirty setup")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_russian_token():
    # The form token is bilingual: "Форма" creates the form object just like "Form".
    form = "Z_McpRuForm"
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Форма." + form})
    assert_ok(r, "create form object via the Russian form token")
    assert r.structured.get("kind") == "Form", "kind must be Form: %r" % (r.structured,)
    poll_diff_contains(form, ctx="the Russian-token form object must land on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_duplicate_is_error():
    # Catalog.Catalog already has the fixture form "ItemForm" -> creating it again is rejected.
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm"})
    e = assert_error(r, "duplicate form object")
    assert_error_quality(e, names=["ItemForm"], suggests=["already exists"],
                         ctx="creating an existing form must be a clean duplicate error")
    assert_no_diff("a rejected duplicate form create must not change the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_invalid_name_is_error():
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Form.1Bad-Form"})
    e = assert_error(r, "invalid form name")
    assert_error_quality(e, names=["1Bad-Form"], suggests=["must start with"],
                         ctx="an invalid form name is a clean error")
    assert_no_diff("a rejected form create must not change the project")


# ──────────────────────────────────────────────────────────────────────────────
# A STANDALONE form (CommonForm) owns a content form of its own — issue #297.
# ──────────────────────────────────────────────────────────────────────────────

def _read_disk_or_fail(rel_path, what):
    """read_disk, but a MISSING file fails as a plain assertion instead of raising
    FileNotFoundError — here the missing file IS the regression under test (issue #297), so it
    must be reported as such and not as a harness crash."""
    try:
        return read_disk(rel_path)
    except FileNotFoundError:
        _fail("%s was never written: %s is missing on disk" % (what, rel_path))


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_common_form_writes_the_descriptor_and_the_content_form():
    """Issue #297: creating a CommonForm wrote ONLY the descriptor <Name>.mdo. Its content form
    (the file Form.form — what the editor renders and what every form element attaches to) was
    never created, so the form opened empty and the first element create failed with "bmGetFqn may
    be called on attached BM objects only".

    Covers the whole round trip: create the form, add an element to it (a Decoration — a label),
    then assert BOTH files exist on disk AND carry the expected content.
    """
    form, label = "Z_McpCommonForm", "Z_McpCommonFormLabel"
    mdo_rel = "src/CommonForms/%s/%s.mdo" % (form, form)
    content_rel = "src/CommonForms/%s/Form.form" % form

    r = call("create_metadata", {"projectName": PROJECT, "fqn": "CommonForm." + form})
    assert_ok(r, "create a standalone CommonForm")
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    assert r.structured.get("name") == form, "name must be the form name: %r" % (r.structured,)
    poll_diff_contains(form, ctx="the new common form must register in the configuration on disk")
    # Then poll a marker that ONLY the content file carries: the name lands in Configuration.mdo
    # first, so polling on it alone races the .form export and the reads below could hit a file
    # that is not there yet (same reason as the object-form tests above).
    poll_diff_contains("<autoCommandBar>",
                       ctx="the new common form's content .form must land on disk")

    # 1) The DESCRIPTOR — src/CommonForms/<Name>/<Name>.mdo.
    mdo_xml = _read_disk_or_fail(mdo_rel, "the CommonForm descriptor")
    assert "mdclass:CommonForm" in mdo_xml, \
        "the descriptor must be a CommonForm: %s" % mdo_xml
    assert "<name>%s</name>" % form in mdo_xml, \
        "the descriptor must carry the form name: %s" % mdo_xml

    # 2) The CONTENT form — src/CommonForms/<Name>/Form.form. THIS is the file issue #297 was
    # missing entirely. It must be a real form root carrying the render-critical predefined
    # command bar with its -1 id sentinel (issue #189) — the same shape an owned form gets.
    content_xml = _read_disk_or_fail(content_rel, "the CommonForm content form")
    assert "form:Form" in content_xml, \
        "the content must be a form root: %s" % content_xml
    assert "<autoCommandBar>" in content_xml, \
        "the content form must carry the predefined command bar: %s" % content_xml
    assert "<id>-1</id>" in content_xml, \
        "the predefined command bar must keep its -1 id sentinel: %s" % content_xml
    # The rest of the designer form root the issue lists. Read BEFORE any item is added, so these
    # can only come from the root itself.
    for marker in ("<autoTitle>true</autoTitle>", "<autoFillCheck>true</autoFillCheck>",
                   "<enabled>true</enabled>", "<commandInterface>"):
        assert marker in content_xml, \
            "the content form must carry the designer form default %s: %s" % (marker, content_xml)

    # 3) An ELEMENT added afterwards must attach to that content form and serialize into it. Before
    # the fix this call failed outright — the content form was never a BM top object.
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "CommonForm.%s.Decoration.%s" % (form, label)})
    assert_ok(r2, "add a label (Decoration) to the new common form")
    poll_diff_contains(label, ctx="the label must land in the common form's Form.form on disk")
    content_xml = _read_disk_or_fail(content_rel, "the CommonForm content form after the label")
    assert 'xsi:type="form:Decoration"' in content_xml, \
        "the label must serialize as a form Decoration: %s" % content_xml
    assert "<name>%s</name>" % label in content_xml, \
        "the label must serialize under its own name: %s" % content_xml

    # 4) MODEL read-back over the wire: the form renders its structure (which only resolves when
    # the content was attached under its canonical FQN) and that structure names the label.
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": ["CommonForm." + form]})
    assert_ok(d, "render the new common form's structure")
    assert_contains(d.text, "Form Structure", "the common form must render a structure")
    assert_contains(d.text, label, "the rendered structure must name the added label")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — FORM content members (the cross-model hop into the editable .form)
# Fixture: Catalog.Catalog has a managed form "ItemForm".
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_attribute():
    attr = "E2EFormAttr"
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr})
    assert_ok(r, "create a form attribute by FQN")
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    poll_diff_contains(attr, ctx="the new form attribute must land in the form's .form on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_command():
    cmd = "E2EFormCmd"
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(r, "create a form command by FQN")
    poll_diff_contains(cmd, ctx="the new form command must land in the form's .form on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_group_and_nested_decoration():
    # A Group at the form root, then a Decoration NESTED under it via the 'parent' property.
    grp, dec = "E2EFormGroup", "E2EFormDeco"
    r1 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group." + grp})
    assert_ok(r1, "create a form group by FQN")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Decoration." + dec,
        "properties": [{"name": "parent", "value": grp}]})
    assert_ok(r2, "create a form decoration nested under the group")
    poll_diff_contains(grp, ctx="the new form group must land on disk")
    poll_diff_contains(dec, ctx="the nested decoration must land on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_attribute_russian_token():
    # The form token + element kind token are bilingual: "Форма" + "Реквизит".
    attr = "E2EFormAttrRu"
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Форма.ItemForm.Реквизит." + attr})
    assert_ok(r, "create a form attribute via Russian form/kind tokens")
    poll_diff_contains(attr, ctx="the Russian-token form attribute must land on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_unknown_kind_is_error():
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Nonsense.X"})
    e = assert_error(r, "unknown form element kind")
    assert_error_quality(e, names=["Nonsense"], suggests=["Attribute", "Command", "Column"],
                         ctx="an unknown form kind must list the supported form kinds")


# ──────────────────────────────────────────────────────────────────────────────
# Columns of a collection-typed form attribute (issue #295)
# ──────────────────────────────────────────────────────────────────────────────

def _seed_collection_attribute(attr):
    """Create a form attribute and make it a ValueTable - the shape that owns columns."""
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr})
    assert_ok(r, "seed collection attribute " + attr)
    wait_for_project_ready()
    t = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr,
        "properties": [{"name": "type", "value": {"types": [{"kind": "ValueTable"}]}}]})
    assert_ok(t, "type " + attr + " as a ValueTable")
    wait_for_project_ready()


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_attribute_column():
    attr, col = "E2EColOwner", "E2ECol"
    _seed_collection_attribute(attr)
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr + ".Column." + col})
    assert_ok(r, "create a column on a collection form attribute")
    assert r.structured.get("kind") == "FormAttributeColumn", \
        "the created element must be a FormAttributeColumn: %r" % (r.structured,)
    poll_disk_contains("src/Catalogs/Catalog/Forms/ItemForm/Form.form", "<name>" + col + "</name>",
                       ctx="the new column must land in the form's .form on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_attribute_column_russian_token():
    attr, col = "E2EColOwnerRu", "E2EColRu"
    _seed_collection_attribute(attr)
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Реквизит." + attr + ".Колонка." + col})
    assert_ok(r, "create a column via the Russian Колонка token")
    poll_disk_contains("src/Catalogs/Catalog/Forms/ItemForm/Form.form", "<name>" + col + "</name>",
                       ctx="the Russian-token column must land on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_a_field_cannot_walk_past_a_memberless_column():
    # A column typed UUID / ValueStorage holds one opaque value, so no tail can resolve against it and
    # EDT does not flag the binding. The terminality check knew only the four primitives while the
    # type builder had grown these two, so 'Rows.Id.Part' was accepted merely because the 'Id' column
    # existed and the tool reported success for a dead binding (issue #295 review). Asserted LIVE
    # because a real form's types are platform PROXIES, not the in-memory Types a unit test builds.
    attr = "E2EOpaqueOwner"
    _seed_collection_attribute(attr)
    base = "Catalog.Catalog.Form.ItemForm.Attribute." + attr
    for col, kind in (("OpaqueId", "UUID"), ("OpaqueBlob", "ValueStorage")):
        c = call("create_metadata", {"projectName": PROJECT, "fqn": base + ".Column." + col})
        assert_ok(c, "seed column " + col)
        wait_for_project_ready()
        t = call("modify_metadata", {
            "projectName": PROJECT, "fqn": base + ".Column." + col,
            "properties": [{"name": "type", "value": {"types": [{"kind": kind}]}}]})
        assert_ok(t, "type the column as " + kind)
        wait_for_project_ready()

        r = call("create_metadata", {
            "projectName": PROJECT,
            "fqn": "Catalog.Catalog.Form.ItemForm.Field.Deep" + col,
            "properties": [{"name": "dataPath", "value": "%s.%s.Part" % (attr, col)}]})
        e = assert_error(r, "a path past a %s column must be refused" % kind)
        assert_error_quality(e, names=[col], suggests=["dataPath"],
                             ctx="the refusal must name the column and how to bind to it instead")

    # ...and the column ITSELF still binds - the guard is about continuing past it, not about the type.
    ok = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.OpaqueCell",
        "properties": [{"name": "dataPath", "value": attr + ".OpaqueId"}]})
    assert_ok(ok, "a field bound to the opaque column itself must still be created")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_a_field_cannot_walk_past_a_collection_column():
    # A COLLECTION column is not terminal, so a two-valued "terminal refuses / else passes" rule let
    # 'Rows.Nested.Price' through as soon as the column existed. But the members such a value implies
    # are COLUMNS, and the form metamodel puts `columns` on FormAttribute only - a column owns none -
    # so 'Price' can never be declared under 'Nested' (issue #295 review). Asserted live, because the
    # verdict is read off the real metamodel, not the synthetic one a unit test builds.
    attr, col = "E2ENestedOwner", "Nested"
    _seed_collection_attribute(attr)
    base = "Catalog.Catalog.Form.ItemForm.Attribute." + attr
    c = call("create_metadata", {"projectName": PROJECT, "fqn": base + ".Column." + col})
    assert_ok(c, "seed the column")
    wait_for_project_ready()
    t = call("modify_metadata", {
        "projectName": PROJECT, "fqn": base + ".Column." + col,
        "properties": [{"name": "type", "value": {"types": [{"kind": "ValueTable"}]}}]})
    assert_ok(t, "type the column as a ValueTable")
    wait_for_project_ready()

    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.DeepOnNested",
        "properties": [{"name": "dataPath", "value": "%s.%s.Price" % (attr, col)}]})
    e = assert_error(r, "a path past a collection column must be refused")
    assert_error_quality(e, names=[col], suggests=["dataPath", "ATTRIBUTE"],
                         ctx="the refusal must name the column, say only an attribute owns columns, "
                             "and give the two ways out")

    # The OTHER side: the collection column as the FINAL segment stays addressable.
    ok = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.NestedCell",
        "properties": [{"name": "dataPath", "value": "%s.%s" % (attr, col)}]})
    assert_ok(ok, "a field bound to the collection column ITSELF must still be created")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_column_on_non_collection_attribute_is_error():
    # Only a ValueTable / ValueTree attribute owns columns. EDT would not flag a column hung off a
    # String attribute, so the tool has to - and it must say how to fix it (issue #295).
    attr = "E2EPlainAttr"
    cr = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr})
    assert_ok(cr, "seed a plain form attribute")
    wait_for_project_ready()
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr + ".Column.Nope"})
    e = assert_error(r, "a column on a non-collection attribute is refused")
    assert_error_quality(e, names=[attr], suggests=["ValueTable", "modify_metadata"],
                         ctx="the refusal must name the attribute and say how to make it a collection")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_inapplicable_properties_are_refused_on_a_column():
    # A column takes only `title`. Every other property was parsed, stored and then never applied by
    # createColumn - the call reported success for a discarded request (issue #295 review).
    attr = "E2EColPropsOwner"
    _seed_collection_attribute(attr)
    fqn = "Catalog.Catalog.Form.ItemForm.Attribute." + attr + ".Column.WithProps"
    for prop, value in (("parent", "SomeGroup"), ("dataPath", "Price"),
                        ("attribute", "Price"), ("command", "Post"), ("type", "InputField")):
        r = call("create_metadata", {
            "projectName": PROJECT, "fqn": fqn,
            "properties": [{"name": prop, "value": value}]})
        e = assert_error(r, "'%s' on a column must be refused, not silently dropped" % prop)
        assert_error_quality(e, names=[prop], suggests=["title", "modify_metadata"],
                             ctx="the refusal must name the property and what a column does accept")

    # None of the refused calls may have created anything.
    d = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": ["Catalog.Catalog.Form.ItemForm"]})
    assert_not_contains(d.text or "", "WithProps",
                        "a refused create must not have written the column")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_column_handler_fqn_does_not_bind_to_a_same_named_item():
    # '...Form.F.Column.X.Handler.Event' parses as an ITEM-LEVEL handler, so the bare-Column guard
    # alone let it through and the handler container was looked up among the form's ITEMS by name -
    # binding the handler to a visual item that merely shares the name (issue #295 review).
    attr, fld = "E2EColHandlerAttr", "E2EColHandlerField"
    a = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr})
    assert_ok(a, "seed the form attribute")
    wait_for_project_ready()
    f = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field." + fld,
        "properties": [{"name": "dataPath", "value": attr}]})
    assert_ok(f, "seed a FIELD with the name the bad address will use")
    wait_for_project_ready()

    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Column." + fld + ".Handler.OnChange",
        "properties": [{"name": "procedure", "value": "Hijacked"}]})
    e = assert_error(r, "a handler addressed on a Column must be refused")
    assert_contains(e, "no event handlers", "the refusal must say a column carries no events")

    # The same-named FIELD must not have gained a handler.
    d = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": ["Catalog.Catalog.Form.ItemForm"]})
    assert_not_contains(d.text or "", "Hijacked",
                        "the same-named visual item must NOT have been bound")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_a_collection_attribute_can_be_shown_on_the_form():
    # The point of the whole feature: a ValueTable attribute the user can SEE. The data column was
    # creatable, but nothing could display it - a Table over the attribute auto-generated a bogus
    # `<Attr>.LineNumber` column (a collection has no such field) and an explicit field with
    # dataPath 'Rows.Price' was refused, because a dotted path was accepted only for a dynamic list
    # or the main object attribute (issue #295 review).
    attr, col, tbl = "E2EShownRows", "E2EShownPrice", "E2EShownTable"
    _seed_collection_attribute(attr)
    c = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr + ".Column." + col})
    assert_ok(c, "create the data column")
    wait_for_project_ready()

    t = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Table." + tbl,
        "properties": [{"name": "dataPath", "value": attr}]})
    assert_ok(t, "create a table bound to the collection attribute")
    form_file = "src/Catalogs/Catalog/Forms/ItemForm/Form.form"
    # The table's auto-columns come from the ATTRIBUTE's columns, addressed <Attr>.<Column> (a dotted
    # path serializes as ONE dot-joined <segments> element).
    poll_disk_contains(form_file, "<segments>%s.%s</segments>" % (attr, col),
                       ctx="the table column must be bound to the attribute's own column")
    form_xml = read_disk(form_file)
    assert "<name>%sLineNumber</name>" % tbl not in form_xml, \
        "an in-memory collection has no LineNumber field, so the table must not generate that " \
        "column: %s" % form_xml

    # ...and an EXPLICIT field bound to the same column is accepted too.
    f = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.E2EShownField",
        "properties": [{"name": "dataPath", "value": attr + "." + col},
                       {"name": "parent", "value": tbl}]})
    assert_ok(f, "an explicit field may bind to a collection attribute's column")
    poll_disk_contains(form_file, "E2EShownField",
                       ctx="the explicit column field must land on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_a_field_on_an_unknown_collection_column_is_error():
    # Widening the dotted path must not accept ANY tail: an unknown column is named, with the
    # address that would create it.
    attr = "E2EGhostRows"
    _seed_collection_attribute(attr)
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.E2EGhostField",
        "properties": [{"name": "dataPath", "value": attr + ".NoSuchColumn"}]})
    e = assert_error(r, "a field bound to a nonexistent column is refused")
    assert_error_quality(e, names=["NoSuchColumn", attr], suggests=["create_metadata", "Column"],
                         ctx="the refusal must name the missing column and how to create it")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_column_on_missing_attribute_is_error():
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Attribute.NoSuchAttr_zzz.Column.C"})
    e = assert_error(r, "a column on a missing attribute is refused")
    assert_error_quality(e, names=["NoSuchAttr_zzz"], suggests=["Create it first"],
                         ctx="the refusal must name the missing owner attribute")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_event_handler():
    # Bind a BSL handler to the form's OnOpen event; the leaf is the event name, the proc via property.
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Handler.OnOpen",
        "properties": [{"name": "procedure", "value": "MyOnOpen"}]})
    assert_ok(r, "bind an OnOpen form handler")
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    poll_diff_contains("MyOnOpen", ctx="the handler procedure name must land in the form's .form on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_event_handler_by_russian_name_on_english_config():
    # Issue #157 remark: configurations also exist in ENGLISH — event names must resolve in BOTH script
    # variants. TestConfiguration is an English config; binding by the RUSSIAN event name 'ПриОткрытии'
    # (== OnOpen) must still resolve, because createHandler matches the platform event by name OR nameRu.
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Handler.ПриОткрытии",
        "properties": [{"name": "procedure", "value": "RuNameOnOpen"}]})
    assert_ok(r, "bind a handler addressed by the RUSSIAN event name on an ENGLISH config")
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    poll_diff_contains("RuNameOnOpen",
                       ctx="the handler bound via the Russian event name must land in the .form on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_handler_calltype_rejected_on_base_config():
    # callType (extension event interception) is only valid in a configuration EXTENSION; on the base
    # TestConfiguration it must be rejected with an actionable error that names the project and points
    # at adopt_metadata_object — never silently written as a plain handler.
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Handler.OnOpen",
        "callType": "After"})
    e = assert_error(r, "callType on a base configuration")
    assert_error_quality(e, names=[PROJECT], suggests=["adopt_metadata_object"],
                         ctx="callType on a base config must point at extension projects")
    assert_no_diff("a rejected extension-only handler must not change the base project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_calltype_on_non_handler_fqn_rejected():
    # callType applies ONLY to a form event handler FQN; on any other create it is rejected (not
    # silently dropped), naming callType and steering to a handler FQN.
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute.CallTypeMisuse_zz",
        "callType": "After"})
    e = assert_error(r, "callType on a non-handler FQN")
    assert_error_quality(e, names=["callType"], suggests=["handler"],
                         ctx="callType on a non-handler FQN is rejected, not dropped")
    assert_no_diff("a rejected callType misuse must not change the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_unknown_event_lists_available():
    # An unknown event must be rejected WITH the list of available events (the user-required advisory).
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Handler.DefinitelyNotAnEvent_zz"})
    e = assert_error(r, "unknown form event")
    assert_error_quality(e, names=["DefinitelyNotAnEvent_zz"], suggests=["Available events"],
                         ctx="an unknown event must list the available events")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_field_bound_to_attribute():
    # Create a form attribute, then a Field bound to it via dataPath.
    attr, fld = "FPrice", "PriceField"
    r1 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr})
    assert_ok(r1, "seed form attribute")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field." + fld,
        "properties": [{"name": "dataPath", "value": attr}]})
    assert_ok(r2, "create a Field bound to the attribute")
    assert "FormField" in (r2.structured.get("kind") or ""), "kind must be FormField: %r" % (r2.structured,)
    poll_diff_contains("<segments>%s</segments>" % attr,
                       ctx="the field's dataPath must bind to the attribute on disk")
    # Designer parity: a created field must serialize the same designer defaults a
    # UI-created field carries (the booleans default to false in the model).
    poll_diff_contains("<showInHeader>true</showInHeader>",
                       ctx="a created field must show in the table header like a designer one")
    poll_diff_contains("<wrap>true</wrap>",
                       ctx="the input-field extInfo must carry the designer defaults")
    poll_diff_contains(fld + "ExtendedTooltip",
                       ctx="the designer's extended-tooltip auto-child must be created")
    poll_diff_contains(fld + "ContextMenu",
                       ctx="the designer's context-menu auto-child must be created")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_button_bound_to_command():
    # Create a form command, then a Button bound to it.
    cmd, btn = "FRefresh", "RefreshBtn"
    r1 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(r1, "seed form command")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button." + btn,
        "properties": [{"name": "command", "value": cmd}]})
    assert_ok(r2, "create a Button bound to the command")
    assert "Button" in (r2.structured.get("kind") or ""), "kind must be Button: %r" % (r2.structured,)
    poll_diff_contains(cmd, ctx="the button's commandName must reference the command on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_item_level_handler():
    # Create an attribute + a Field bound to it, then bind an ITEM-LEVEL handler to the field's
    # OnChange (an input-field event that lives on the field's extInfo sub-type, not the form root) -
    # this exercises the 8-part item-level FQN and the base+extInfo event union.
    attr, fld, proc = "IHAttr", "IHField", "IHFieldOnChange"
    r1 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr})
    assert_ok(r1, "seed form attribute")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field." + fld,
        "properties": [{"name": "dataPath", "value": attr}]})
    assert_ok(r2, "seed bound field")
    wait_for_project_ready()
    r3 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Field.%s.Handler.OnChange" % fld,
        "properties": [{"name": "procedure", "value": proc}]})
    assert_ok(r3, "bind an item-level OnChange handler to the field")
    assert r3.structured.get("action") == "created", "must report created: %r" % (r3.structured,)
    # The message must reference the owning item, not just the form path.
    assert fld in (r3.structured.get("message") or ""), \
        "the item-level handler message must name the field: %r" % (r3.structured,)
    poll_diff_contains(proc, ctx="the item-level handler procedure must land in the form's .form on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_item_level_handler_unknown_event_lists_available():
    # An item-level handler with an unknown event must list the item's available events (which include
    # the extInfo sub-type's events, e.g. OnChange for an input field).
    attr, fld = "IHEAttr", "IHEField"
    r1 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr})
    assert_ok(r1, "seed form attribute")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field." + fld,
        "properties": [{"name": "dataPath", "value": attr}]})
    assert_ok(r2, "seed bound field")
    wait_for_project_ready()
    r3 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Field.%s.Handler.NotARealEvent_zz" % fld})
    e = assert_error(r3, "unknown item-level event")
    assert_error_quality(e, names=["NotARealEvent_zz"], suggests=["Available events", "OnChange"],
                         ctx="an unknown field event must list the field's available events incl. OnChange")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_item_level_handler_missing_item_is_error():
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Field.NoSuchItem_zz.Handler.OnChange"})
    e = assert_error(r, "item-level handler on a missing item")
    assert_error_quality(e, names=["NoSuchItem_zz"], suggests=["not found", "Create the item first"],
                         ctx="an item-level handler on a missing item is a clean error")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_field_missing_attribute_is_error():
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field.OrphanField",
        "properties": [{"name": "dataPath", "value": "NoSuchAttr_zz"}]})
    e = assert_error(r, "field bound to a missing attribute")
    assert_error_quality(e, names=["NoSuchAttr_zz"], suggests=["not found"],
                         ctx="a field bound to a missing attribute is a clean error")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_button_missing_command_is_error():
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button.OrphanBtn",
        "properties": [{"name": "command", "value": "NoSuchCmd_zz"}]})
    e = assert_error(r, "button bound to a missing command")
    assert_error_quality(e, names=["NoSuchCmd_zz"], suggests=["not found"],
                         ctx="a button bound to a missing command is a clean error")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_button_enabled_and_in_auto_command_bar():
    # Issue #138 bugs 2+3: parent 'AutoCommandBar' must place the button INSIDE the form's command
    # bar (not the form root), and the created button must export <enabled>true</enabled> (the model
    # default is false -> a disabled, half-transparent button in the client).
    cmd, btn = "BarCmd", "BarBtn"
    r1 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(r1, "seed form command")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button." + btn,
        "properties": [{"name": "command", "value": cmd},
                       {"name": "parent", "value": "AutoCommandBar"}]})
    assert_ok(r2, "create a Button inside the form's AutoCommandBar")
    poll_diff_contains(btn, ctx="the new button must land in the form's .form on disk")
    poll_diff_contains("<enabled>true</enabled>",
                       ctx="a created button must be explicitly enabled like a designer-created one")
    # Inside a command bar the platform requires the CommandBarButton type. CommandBarButton is the
    # EMF default literal (value 0), so the XMI OMITS <type> for it - the wrong outcome would be an
    # explicit <type>UsualButton</type> in this test's diff (which adds only the command + this button).
    assert "UsualButton" not in diff(), \
        "a button in the command bar must NOT serialize the UsualButton type"
    # The structure read-back shows the button nested under the bar (the verification surface).
    r3 = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": ["Catalog.Catalog.Form.ItemForm"]})
    assert_ok(r3, "read the form structure back")
    assert_contains(r3.text, "AutoCommandBar", "the structure must surface the auto command bar")
    assert_contains(r3.text, btn, "the structure must show the button inside the bar")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_button_parent_dotted_auto_command_bar_path():
    # The parent shapes reported in issue #138 ('Form.X.AutoCommandBar' / '...ChildItems') resolve too.
    cmd, btn = "BarCmd2", "BarBtn2"
    r1 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(r1, "seed form command")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button." + btn,
        "properties": [{"name": "command", "value": cmd},
                       {"name": "parent", "value": "Form.ItemForm.AutoCommandBar.ChildItems"}]})
    assert_ok(r2, "a dotted AutoCommandBar parent path resolves to the form's bar")
    poll_diff_contains(btn, ctx="the button created via the dotted parent path must land on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_unknown_parent_suggests_auto_command_bar():
    cmd = "OrphanParentCmd"
    r1 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(r1, "seed form command")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button.OrphanParentBtn",
        "properties": [{"name": "command", "value": cmd},
                       {"name": "parent", "value": "NoSuchParent_zz"}]})
    e = assert_error(r2, "button under a missing parent")
    assert_error_quality(e, names=["NoSuchParent_zz"], suggests=["AutoCommandBar"],
                         ctx="a missing parent error must advertise the AutoCommandBar token")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_popup_group_with_button():
    # A print-style submenu: a Group with an explicit type=Popup in the command bar, holding a
    # button. Inside the popup the platform requires command-bar buttons.
    cmd, grp, btn = "PopCmd", "PopMenu", "PopBtn"
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(r, "seed form command")
    wait_for_project_ready()
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group." + grp,
        "properties": [{"name": "parent", "value": "AutoCommandBar"},
                       {"name": "type", "value": "Popup"}]})
    assert_ok(r, "create a Popup group in the command bar")
    poll_diff_contains("<type>Popup</type>",
                       ctx="the explicit group type must serialize to the .form on disk")
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Button." + btn,
        "properties": [{"name": "command", "value": cmd}, {"name": "parent", "value": grp}]})
    assert_ok(r, "create a button inside the popup submenu")
    poll_diff_contains(btn, ctx="the popup's button must land on disk")
    # CommandBarButton is the model-default literal (omitted by XMI); the wrong outcome would be an
    # explicit UsualButton inside the popup.
    assert "UsualButton" not in diff(), \
        "a button inside a popup submenu must not serialize the UsualButton type"


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_group_unknown_type_lists_allowed():
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Group.BadTypeGrp",
        "properties": [{"name": "type", "value": "Bogus_zz"}]})
    e = assert_error(r, "unknown group type")
    assert_error_quality(e, names=["Bogus_zz"], suggests=["Allowed group types", "Popup"],
                         ctx="an unknown group type must list the allowed literals")
    assert_no_diff("a rejected group type must not change the form")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_decoration_in_command_bar_is_rejected():
    # The designer forbids decorations in command bars (FormItemTypeInformationService) - placing
    # one would build a model the UI could never produce.
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Decoration.BarDeco_zz",
        "properties": [{"name": "parent", "value": "AutoCommandBar"}]})
    e = assert_error(r, "decoration into the command bar")
    assert_error_quality(e, names=["AutoCommandBar"], suggests=["cannot hold decorations"],
                         ctx="the placement error must name the parent and the rule")
    assert_no_diff("a rejected placement must not change the form on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_command_action_handler():
    # Issue #138 bug 1: ...Command.X.Handler.Action binds the command's Action (the designer's
    # "Action" property) -> Designer XML <Command><Action>Proc</Action></Command>.
    cmd, proc = "ActCmd", "ActCmdProc"
    r1 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(r1, "seed form command")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Command.%s.Handler.Action" % cmd,
        "properties": [{"name": "procedure", "value": proc}]})
    assert_ok(r2, "bind the command's Action handler")
    assert "CommandHandler" in (r2.structured.get("kind") or ""), \
        "kind must be CommandHandler: %r" % (r2.structured,)
    poll_diff_contains(proc, ctx="the action's BSL procedure name must land in the .form on disk")
    # The commands table surfaces the binding (the verification surface for the model state).
    r3 = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": ["Catalog.Catalog.Form.ItemForm"]})
    assert_ok(r3, "read the form structure back")
    assert_contains(r3.text, proc, "the commands table must show the bound action handler")
    # A second Action on the same command is a clean duplicate error.
    r4 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Command.%s.Handler.Action" % cmd})
    e = assert_error(r4, "duplicate Action handler")
    assert_error_quality(e, suggests=["already exists"],
                         ctx="a second Action on the same command must be rejected")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_command_action_default_procedure_is_command_name():
    # Without a 'procedure' property the BSL handler name defaults to the COMMAND name (the EDT UI
    # suggestion), never the literal 'Action'.
    cmd = "ActDfltCmd"
    r1 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(r1, "seed form command")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Command.%s.Handler.Action" % cmd})
    assert_ok(r2, "bind the Action handler with the default procedure name")
    r3 = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": ["Catalog.Catalog.Form.ItemForm"]})
    assert_ok(r3, "read the form structure back")
    # The commands table row carries the handler name == the command name (twice in the row).
    row = next((ln for ln in r3.text.splitlines() if ln.strip().startswith("| " + cmd)), None)
    assert row is not None, "the commands table must list %s:\n%s" % (cmd, r3.text[:800])
    assert row.count(cmd) >= 2, \
        "the default handler name must equal the command name in the row: %r" % (row,)


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_command_action_wrong_event_lists_action():
    cmd = "ActWrongCmd"
    r1 = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Command." + cmd})
    assert_ok(r1, "seed form command")
    wait_for_project_ready()
    r2 = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Command.%s.Handler.OnChange" % cmd})
    e = assert_error(r2, "a non-Action event on a command")
    assert_error_quality(e, names=["OnChange"], suggests=["Available events", "Action"],
                         ctx="a command handler accepts only Action and must say so")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_command_action_missing_command_is_error():
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Command.NoSuchCmd_zz.Handler.Action"})
    e = assert_error(r, "Action handler on a missing command")
    assert_error_quality(e, names=["NoSuchCmd_zz"], suggests=["Form command not found"],
                         ctx="an Action handler on a missing command is a clean error")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_handler_on_an_owner_of_a_foreign_kind_is_refused():
    # Issue #343: the OWNER's kind segment of an item-level handler address is part of the
    # resolution. Before the fix the owner was looked up by NAME alone, so a handler addressed at
    # 'Button.<a field>' was bound to the FIELD that merely bears the name.
    attr, fld = "CkAttr", "CreateKindFld"
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Attribute." + attr})
    assert_ok(r, "seed the probe attribute")
    wait_for_project_ready()
    poll_disk_contains(_KIND_PROBE_FORM, attr, timeout=60,
                       ctx="the seeded attribute must be visible before the field binds to it")
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.Form.ItemForm.Field." + fld,
        "properties": [{"name": "dataPath", "value": attr}]})
    assert_ok(r, "seed the probe field")
    wait_for_project_ready()
    poll_disk_contains(_KIND_PROBE_FORM, fld, timeout=60,
                       ctx="the seeded probe field must be on disk first")

    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Button.%s.Handler.OnChange" % fld,
        "properties": [{"name": "procedure", "value": "CreateKindWrong_zz"}]})
    e = assert_error(r, "bind a handler through an owner of a foreign kind")
    assert_error_quality(e, names=[fld], suggests=["Field"],
                         ctx="a foreign owner kind must name the kind the owner really has")
    assert_contains(e, "(kind 'Button')", "the refusal must name the kind that found nothing")
    assert_not_contains(read_disk(_KIND_PROBE_FORM),
                        "CreateKindWrong_zz",
                        "the refused handler create must not have bound anything")

    # The owner's OWN kind still binds it.
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Field.%s.Handler.OnChange" % fld,
        "properties": [{"name": "procedure", "value": "CreateKindRight_zz"}]})
    assert_ok(r, "bind the handler through the owner's own kind")
    poll_disk_contains(_KIND_PROBE_FORM, "CreateKindRight_zz", timeout=60,
                       ctx="the correctly-addressed handler must land on disk")


# ──────────────────────────────────────────────────────────────────────────────
# ё->е normalization — the Name leaf + synonym are normalized at the parse step
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_normalizes_yo_in_name_and_synonym_by_default():
    # Default normalizeYo=true: 'ё' in the NAME leaf and the synonym is rewritten to 'е' at the parse
    # step, BEFORE identifier validation, so the std474 mdo-ru-name-unallowed-letter issue never
    # arises. The stored Name and synonym must be the 'е'-form on disk.
    # "Серёжка" -> "Серёжка"/"Сережка" ; synonym "Тест отчёт" -> "Тест отчет".
    name_yo = "Серёжка"      # contains ё
    name_ye = "Сережка"           # expected stored form
    syn_yo = "Тест отчёт"    # contains ё
    syn_ye = "Тест отчет"         # expected stored form
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog." + name_yo,
        # The fixture declares only 'en'; a 'ru' here would now be REJECTED as an undeclared
        # locale (issue #298). The ё-normalization under test is about the VALUE, not the locale.
        "properties": [{"name": "synonym", "value": syn_yo, "language": "en"}],
    })
    assert_ok(r, "create a Catalog whose Name + synonym carry ё (default normalizeYo)")
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    # The result echoes the normalized Name and reports which fields were rewritten.
    assert r.structured.get("name") == name_ye, \
        "the stored Name must be the е-form: %r" % (r.structured,)
    assert r.structured.get("fqn") == "Catalog." + name_ye, \
        "the FQN leaf must be normalized to the е-form: %r" % (r.structured,)
    normalized = r.structured.get("normalized") or []
    assert "name" in normalized and "synonym" in normalized, \
        "the normalization report must list name + synonym: %r" % (r.structured,)
    # On disk the new object's .mdo carries the е-form Name (and never the ё-form).
    poll_diff_contains("<name>%s</name>" % name_ye,
                       ctx="the normalized (е-form) Name must land in the new object's .mdo")
    assert_not_contains(diff(), name_yo, "the ё-form Name must NOT appear on disk under default normalize")
    # The synonym lands in the NEW object's own .mdo, which is an UNTRACKED file — plain
    # diff() covers tracked modifications only, so use the untracked-aware helper.
    assert_diff_contains(syn_ye, "the synonym must be stored in its normalized (е-form) on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_preserves_yo_when_normalize_disabled():
    # normalizeYo=false: the 'ё' is kept exactly. 'ё' is still a valid 1C identifier character (the
    # std474 issue is an advisory, not a hard reject), so the create succeeds and stores the ё-form.
    name_yo = "Полёт"  # contains ё
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog." + name_yo,
        "normalizeYo": False,
    })
    assert_ok(r, "create a Catalog whose Name carries ё (normalizeYo=false)")
    assert r.structured.get("name") == name_yo, \
        "with normalizeYo=false the ё-form Name must be preserved: %r" % (r.structured,)
    # No field was rewritten, so the report must be absent/empty.
    assert not (r.structured.get("normalized") or []), \
        "no normalization must be reported when disabled: %r" % (r.structured,)
    poll_diff_contains("<name>%s</name>" % name_yo,
                       ctx="the ё-form Name must be stored verbatim when normalizeYo=false")


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
# EVERY configuration top-type the resolver (MetadataTypeUtils) knows - an EXHAUSTIVE list, not a
# sample - so this test proves create_metadata can instantiate each one (the 8-type allow-list is
# gone). If a new type is added to the resolver, add it here too (the coverage is deliberate).
_ALL_TOP_TYPES = [
    "Catalog", "Document", "CommonModule", "InformationRegister", "AccumulationRegister",
    "Enum", "Report", "DataProcessor", "ExchangePlan", "BusinessProcess", "Task", "Role",
    "Subsystem", "CommonCommand", "CommonForm", "WebService", "HTTPService", "Constant",
    "ChartOfCharacteristicTypes", "ChartOfAccounts", "ChartOfCalculationTypes",
    "AccountingRegister", "CalculationRegister", "DocumentJournal", "Sequence",
    "FilterCriterion", "SettingsStorage", "ExternalDataSource", "CommonAttribute",
    "EventSubscription", "ScheduledJob", "SessionParameter", "FunctionalOption",
    "FunctionalOptionsParameter", "CommonPicture", "StyleItem", "DefinedType",
    "CommonTemplate", "CommandGroup", "DocumentNumerator", "WSReference", "XDTOPackage",
    "Language", "Style", "Interface", "IntegrationService", "Bot", "WebSocketClient",
]


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_every_top_type():
    # EXHAUSTIVE: create one of EVERY configuration top-type and read each back. A type the EDT
    # factory cannot instantiate, or a wrong configuration-collection name, fails this test.
    created = []
    failed = []
    for t in _ALL_TOP_TYPES:
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


# ──────────────────────────────────────────────────────────────────────────────
# Create-time-only, type-specific options (CommonModule presets, XDTO namespace)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_xdto_package_with_target_namespace():
    # XDTOPackage needs a non-empty namespace to be valid; the targetNamespace arg sets it and
    # the create echoes it back. The namespace also lands in the package's own .mdo on disk.
    name = "E2EXdtoNs"
    ns = "http://example.org/e2e/%s" % name
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "XDTOPackage." + name,
        "targetNamespace": ns,
    })
    assert_ok(r, "create XDTOPackage.%s with targetNamespace" % name)
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    assert r.structured.get("targetNamespace") == ns, \
        "the create must echo the written XDTO namespace: %r" % (r.structured,)
    # Model read-back via get_metadata_details, by FQN: a targeted read of THIS package is the
    # sharper proof of model visibility than finding its row among every package the
    # get_metadata_objects 'xdtoPackages' filter lists (that filter is covered by its own test).
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": ["XDTOPackage." + name]})
    assert_ok(d, "get_metadata_details read-back (XDTOPackage.%s)" % name)
    assert_contains(d.text, name, "the new XDTO package must appear in the model read-back")
    poll_diff_contains(ns, ctx="the targetNamespace must land in the XDTOPackage .mdo on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_common_module_server_call_preset_sets_flags():
    # commonModuleKind=ServerCall maps to the canonical server + server-call flag combo the
    # common-module-type validator accepts. Verify the resolved flags via get_metadata_details.
    name = "E2ECMServerCall"
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "CommonModule." + name,
        "commonModuleKind": "ServerCall",
    })
    assert_ok(r, "create CommonModule.%s with commonModuleKind=ServerCall" % name)
    assert r.structured.get("commonModuleKind") == "ServerCall", \
        "the create must echo the resolved CommonModule kind: %r" % (r.structured,)

    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": ["CommonModule." + name]})
    assert_ok(d, "read-back CommonModule.%s flags" % name)
    # A ServerCall module is server-side AND a server call, with no client flags set.
    assert_contains(d.text, "ServerCall", "the read-back must show the server-call flag")
    assert_contains(d.text, "Server", "the read-back must show the server-side flag")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_common_module_illegal_flag_combo_is_error():
    # serverCall on a pure client kind has no validator-accepted combo and is rejected up front,
    # before any model change (the validator would otherwise flag an arbitrary flag set).
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "CommonModule.E2EShouldNotExist",
        "commonModuleKind": "ClientManaged",
        "serverCall": True,
    })
    e = assert_error(r, "illegal CommonModule flag combo")
    assert_error_quality(e, names=["serverCall"], suggests=["Server"],
                         ctx="an illegal flag combo must be a clean, actionable error")
    assert_no_diff("a rejected create must not change the project")


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


# ──────────────────────────────────────────────────────────────────────────────
# Localized strings must name a DECLARED locale — issue #298.
# Fixture ground truth: TestConfiguration declares exactly ONE language, code 'en'.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_rejects_a_synonym_in_an_undeclared_locale():
    """Issue #298: a synonym written under a locale the configuration does not declare was accepted
    silently. The platform has no fallback between locale codes, so that value is NEVER displayed —
    the label comes out blank and the mistake is invisible until a human opens the form."""
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Z298Undeclared",
        "properties": [{"name": "synonym", "value": "Marchandises", "language": "fr_CA"}],
    })
    e = assert_error(r, "a synonym in an undeclared locale must be refused")
    assert_error_quality(e, names=["fr_CA"], suggests=["en"],
                         ctx="the error must name the bad code AND list what the configuration declares")
    assert_no_diff("a rejected localized write must not change the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_stores_a_declared_locale_under_its_declared_spelling():
    # A differently-cased request names a DECLARED locale, so it is accepted — but stored under the
    # configuration's own spelling, or it would be a second, never-displayed key (issue #298).
    name = "Z298Case"
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog." + name,
        "properties": [{"name": "synonym", "value": "Goods", "language": "EN"}],
    })
    assert_ok(r, "a declared locale in a different case must be accepted")
    assert r.structured.get("language") == "en", \
        "the result must echo the DECLARED spelling, not the requested one: %r" % (r.structured,)
    poll_diff_contains("<key>en</key>", ctx="the synonym must be keyed by the declared code")
    mdo = read_disk("src/Catalogs/%s/%s.mdo" % (name, name))
    assert "<key>EN</key>" not in mdo, \
        "the requested casing must NOT create a second synonym key: %s" % mdo


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_reports_the_locales_still_missing_a_translation():
    # Issue #298 part 2: after a localized write the result lists the locales that still have NO
    # value, so a caller building a multilingual configuration knows what it still owes - but only
    # for the languages the configuration ITSELF uses. The fixture is named in 'en' only, so the
    # second language added here is declared and NOT in use: there is nothing to nag about, and a
    # write under it is FLAGGED so the agent can ask whether translating into it is really wanted.
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Language.Z298FrOnCreate"}),
              "add a second language to the configuration")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": "Language.Z298FrOnCreate",
                                       "properties": [{"name": "languageCode", "value": "fr"}]}),
              "give the second language its code")
    wait_for_project_ready()

    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Z298Missing",
        "properties": [{"name": "synonym", "value": "Goods", "language": "en"}],
    })
    assert_ok(r, "create with a synonym in one of the two declared locales")
    assert r.structured.get("language") == "en", \
        "the result must echo the locale used: %r" % (r.structured,)
    assert r.structured.get("localesMissing") == [], \
        "a language the configuration is not translated into is not owed one: %r" % (r.structured,)
    assert "localeUnusedInConfiguration" not in r.structured, \
        "a write in the language the configuration DOES use must not be questioned: %r" % (r.structured,)
    wait_for_project_ready()

    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Z298Unused",
        "properties": [{"name": "synonym", "value": "Marchandises", "language": "fr"}],
    })
    assert_ok(r, "a synonym in a declared but unused language is legal")
    assert r.structured.get("localeUnusedInConfiguration") is True, \
        "writing into a language the configuration does not use must be flagged: %r" % (r.structured,)


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_member_rejects_a_title_in_an_undeclared_locale():
    # The same guard on the OTHER localized create path (a form element's title).
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Decoration.Z298Deco",
        "properties": [{"name": "title", "value": "Étiquette", "language": "fr_CA"}],
    })
    e = assert_error(r, "a form-element title in an undeclared locale must be refused")
    assert_error_quality(e, names=["fr_CA"], suggests=["en"],
                         ctx="the form-title path must give the same actionable error")
    assert_no_diff("a rejected form-member create must not change the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_form_object_reports_the_locales_still_missing_a_translation():
    # The form-OBJECT create builds its own payload, so it needs its own proof that the localized
    # report is there too (issue #298).
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Language.Z298FrOnFormObject"}),
              "add a second language to the configuration")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": "Language.Z298FrOnFormObject",
                                       "properties": [{"name": "languageCode", "value": "fr"}]}),
              "give the second language its code")
    wait_for_project_ready()

    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.Z298LocForm",
        "properties": [{"name": "synonym", "value": "Item form", "language": "en"}],
    })
    assert_ok(r, "create a form object with a synonym")
    assert r.structured.get("language") == "en",         "the form-object create must echo the locale used: %r" % (r.structured,)
    assert r.structured.get("localesMissing") == [],         "the form-object create must carry the report - empty, the second language is unused: %r" % (r.structured,)
    assert "localeUnusedInConfiguration" not in r.structured,         "the language the configuration uses must not be questioned: %r" % (r.structured,)
    wait_for_project_ready()

    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.Z298LocFormFr",
        "properties": [{"name": "synonym", "value": "Formulaire", "language": "fr"}],
    })
    assert_ok(r, "create a form object with a synonym in the unused language")
    assert r.structured.get("localeUnusedInConfiguration") is True,         "the form-object create must flag a write into an unused language: %r" % (r.structured,)
    wait_for_project_ready()

    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.Z298LocFormFr.Decoration.Z298DecoFr",
        "properties": [{"name": "title", "value": "Étiquette", "language": "fr"}],
    })
    assert_ok(r, "create a form element with a title in the unused language")
    assert r.structured.get("localeUnusedInConfiguration") is True,         "the form-MEMBER create must flag it too - that path builds its own payload: %r" % (r.structured,)


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_table_without_a_title_still_reports_the_generated_titles_locale():
    # A Table with no explicit title still GETS one (its own name, the way the designer builds it),
    # so a localized value IS written - and it must land under a DECLARED locale, not one guessed
    # from the script variant, and be reported like any other localized write (issue #298).
    ts, table = "Z298TableTS", "Z298Table"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Language.Z298FrOnTable"}),
              "add a second language to the configuration")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": "Language.Z298FrOnTable",
                                       "properties": [{"name": "languageCode", "value": "fr"}]}),
              "give the second language its code")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog.TabularSection." + ts}),
        "seed the tabular section the table binds to")
    wait_for_project_ready()

    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Form.ItemForm.Table." + table,
        "properties": [{"name": "dataPath", "value": "Object.%s" % ts}],
    })
    assert_ok(r, "create a table without an explicit title")
    assert r.structured.get("language") == "en",         "the generated title's locale must be reported: %r" % (r.structured,)
    assert r.structured.get("localesMissing") == [],         "a generated title must carry the report too - empty, the second language is unused: %r" % (r.structured,)
    wait_for_project_ready()

    # And it must really be stored under the declared code - the pre-fix code wrote the script-variant
    # guess, which in an en_CA-only configuration would be an invisible 'en' key.
    form_xml = read_disk("src/Catalogs/Catalog/Forms/ItemForm/Form.form")
    assert "<key>en</key>" in form_xml,         "the generated title must be keyed by a declared language code: %s" % form_xml[:400]


# ──────────────────────────────────────────────────────────────────────────────
# Nested subsystems (issue #351) — Subsystem.Parent.Subsystem.Child, any depth
# ──────────────────────────────────────────────────────────────────────────────
#
# A nested subsystem LOOKS like a member but is structurally a TOP object: EDT stores it in its
# own .mdo under Subsystems/<Parent>/Subsystems/<Child>/ with a <parentSubsystem> back-reference,
# and the parent registers it by NAME in its own <subsystems> list. Both files therefore have to
# be written, and Configuration.mdo (which lists only top-level subsystems) must NOT be.
#
# The fixture's single subsystem is Subsystem.Subsystem — the parent used below.

_FIXTURE_SUBSYSTEM = "Subsystem"
_PARENT_MDO = "src/Subsystems/Subsystem/Subsystem.mdo"


def _nested_mdo(*chain):
    """The .mdo path of a nested subsystem addressed by names BELOW the fixture subsystem."""
    path = "src/Subsystems/" + _FIXTURE_SUBSYSTEM
    for name in chain:
        path += "/Subsystems/" + name
    return path + "/" + chain[-1] + ".mdo"


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_nested_subsystem_lands_on_disk():
    # The headline case of issue #351: a depth-2 chain create_metadata used to refuse outright.
    child = "E2ENestedSub"
    fqn = "Subsystem.%s.Subsystem.%s" % (_FIXTURE_SUBSYSTEM, child)
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, "create the nested subsystem %s" % fqn)
    assert r.structured.get("action") == "created", "must report created: %r" % (r.structured,)
    assert r.structured.get("kind") == "Subsystem", \
        "kind must be the Subsystem EClass: %r" % (r.structured,)
    assert r.structured.get("fqn") == fqn, \
        "the result must carry the canonical chain FQN: %r" % (r.structured,)
    assert r.structured.get("persisted") is True, \
        "create must report persisted=true once both .mdo files are exported: %r" % (r.structured,)

    # The child's OWN .mdo, in the nested folder, with the back-reference to its parent.
    child_mdo = _nested_mdo(child)
    poll_disk_contains(child_mdo, "<name>%s</name>" % child,
                       ctx="the nested subsystem needs its own .mdo")
    poll_disk_contains(child_mdo,
                       "<parentSubsystem>Subsystem.%s</parentSubsystem>" % _FIXTURE_SUBSYSTEM,
                       ctx="the nested subsystem must point back at its parent")
    # ...and the PARENT registers it by name. That line appears only because the parent .mdo is
    # exported too: a create that exported the child alone would leave the parent's list empty.
    poll_disk_contains(_PARENT_MDO, "<subsystems>%s</subsystems>" % child,
                       ctx="the parent .mdo must register the new child")
    # Configuration.mdo lists TOP-level subsystems only - the child must not leak into it.
    assert_not_contains(read_disk("src/Configuration/Configuration.mdo"), child,
                        "a nested subsystem must not be registered at configuration level")

    # MODEL read-back over the wire: the tree really carries the new child.
    wait_for_project_ready()
    d = call("list_subsystems", {"projectName": PROJECT})
    assert_ok(d, "list_subsystems read-back")
    assert_contains(d.text, fqn, "MODEL read-back: the nested subsystem is in the tree")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_nested_subsystem_with_russian_tokens():
    # Bilingual at EVERY position: normalizeFqn canonicalizes only the LEADING token, so a chain
    # whose SECOND token is Russian is exactly the shape that used to fall through. The result must
    # come back as the canonical address, not as the half-translated one that was requested.
    child = "E2ENestedSubRu"
    requested = "Подсистема.%s.Подсистема.%s" % (_FIXTURE_SUBSYSTEM, child)
    canonical = "Subsystem.%s.Subsystem.%s" % (_FIXTURE_SUBSYSTEM, child)
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": requested,
        "properties": [{"name": "synonym", "value": "Nested RU", "language": "en"}],
    })
    assert_ok(r, "create %s" % requested)
    assert r.structured.get("fqn") == canonical, \
        "a Russian-spelled chain must come back canonicalized: %r" % (r.structured,)
    child_mdo = _nested_mdo(child)
    poll_disk_contains(child_mdo, "<name>%s</name>" % child,
                       ctx="the Russian-addressed nested subsystem must land on disk")
    poll_disk_contains(child_mdo, "<value>Nested RU</value>",
                       ctx="the synonym must be written like on any other create")
    poll_disk_contains(_PARENT_MDO, "<subsystems>%s</subsystems>" % child,
                       ctx="the parent must register the Russian-addressed child too")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_create_nested_subsystem_depth_three():
    # Depth is not special-cased: the parent of a depth-3 chain is itself a nested subsystem, so
    # this passes only if the walk descends through a child created moments earlier.
    mid, leaf = "E2EDepth2", "E2EDepth3"
    assert_ok(call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Subsystem.%s.Subsystem.%s" % (_FIXTURE_SUBSYSTEM, mid)}),
        "seed the intermediate subsystem")
    wait_for_project_ready()
    deep = "Subsystem.%s.Subsystem.%s.Subsystem.%s" % (_FIXTURE_SUBSYSTEM, mid, leaf)
    r = call("create_metadata", {"projectName": PROJECT, "fqn": deep})
    assert_ok(r, "create the depth-3 chain %s" % deep)
    assert r.structured.get("fqn") == deep, \
        "the depth-3 result must carry the whole chain: %r" % (r.structured,)
    leaf_mdo = _nested_mdo(mid, leaf)
    poll_disk_contains(leaf_mdo, "<name>%s</name>" % leaf,
                       ctx="the depth-3 leaf needs its own .mdo two folders down")
    poll_disk_contains(leaf_mdo,
                       "<parentSubsystem>Subsystem.%s.Subsystem.%s</parentSubsystem>"
                       % (_FIXTURE_SUBSYSTEM, mid),
                       ctx="the leaf must point back at the NESTED parent, by its full chain")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_nested_subsystem_with_a_missing_parent_is_a_clear_error():
    # Acceptance criterion 2 of #351: the refusal has to stay legible where the parent is absent,
    # and it must name the chain that is missing rather than the leaf.
    missing = "E2ENoSuchParent"
    fqn = "Subsystem.%s.Subsystem.Whatever" % missing
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    e = assert_error(r, "nested subsystem under a parent that does not exist")
    assert_error_quality(e, names=["Subsystem." + missing],
                         suggests=["not found", "list_subsystems"])
    assert_no_diff("a rejected nested-subsystem create must not change the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_nested_subsystem_duplicate_is_rejected():
    # The duplicate guard applies to the CHAIN, not just to top-level names: a second create of the
    # same child must be refused, and expectedNotExists must sharpen the refusal the same way it
    # does everywhere else.
    child = "E2ENestedDup"
    fqn = "Subsystem.%s.Subsystem.%s" % (_FIXTURE_SUBSYSTEM, child)
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}), "seed the child")
    wait_for_project_ready()
    e = assert_error(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}),
                     "duplicate nested subsystem")
    assert_error_quality(e, names=[fqn], suggests=["already exists"])
    e2 = assert_error(call("create_metadata",
                           {"projectName": PROJECT, "fqn": fqn, "expectedNotExists": True}),
                      "duplicate nested subsystem with expectedNotExists")
    assert_error_quality(e2, names=[fqn], suggests=["precondition failed", "stale"])
    # A same-named child under a DIFFERENT parent is a different address and must be allowed.
    sibling = "E2ENestedDupParent"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Subsystem." + sibling}),
              "create a second top-level subsystem")
    wait_for_project_ready()
    assert_ok(call("create_metadata",
                   {"projectName": PROJECT, "fqn": "Subsystem.%s.Subsystem.%s" % (sibling, child)}),
              "the same child name under another parent is a different node")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_nested_chain_reproduces_the_dead_end_parent_shape_of_342():
    # The scenario #342 could only prove with a unit test, now built LIVE: two top-level subsystems
    # spelled with ё and е, where the ё one is a DEAD END and the е one carries the child. Before
    # #351 the fixture could not hold a nested subsystem at all, so the backtracking resolver had
    # no live evidence.
    yo_parent, ye_parent, child = "Мёд", "Мед", "Вес"
    # normalizeYo=false keeps the ё spelling; the default would rewrite it into its е twin and the
    # two parents would collide.
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Subsystem." + yo_parent,
                                       "normalizeYo": False}),
              "create the ё-spelled dead-end parent")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": "Subsystem." + ye_parent}),
              "create the е-spelled live parent")
    wait_for_project_ready()
    r = call("create_metadata",
             {"projectName": PROJECT, "fqn": "Subsystem.%s.Subsystem.%s" % (ye_parent, child)})
    assert_ok(r, "nest the child under the е-spelled parent only")
    wait_for_project_ready()

    # The shape is real on disk: the child sits under the е parent, and the ё parent stays childless.
    poll_disk_contains("src/Subsystems/%s/Subsystems/%s/%s.mdo" % (ye_parent, child, child),
                       "<parentSubsystem>Subsystem.%s</parentSubsystem>" % ye_parent,
                       ctx="the child belongs to the е-spelled parent")
    assert_not_contains(read_disk("src/Subsystems/%s/%s.mdo" % (yo_parent, yo_parent)), child,
                        "the ё-spelled parent must stay a DEAD END")

    # ...and that is what makes the #342 behaviour testable live: the ё-spelled address of the whole
    # chain still resolves, because the resolver backtracks to the parent's е twin instead of
    # stopping at the childless ё parent.
    requested = "Subsystem.%s.Subsystem.%s" % (yo_parent, child)
    g = call("get_project_errors",
             {"projectName": PROJECT, "objectFqns": [requested], "severity": "NONE"})
    assert_ok(g, "address the chain through the dead-end ё parent")
    assert g.structured.get("objectsNotFound") == [], \
        "the ё-spelled chain must not be reported missing: %r" % (g.structured,)
    assert g.structured.get("objectsResolved") == [requested], \
        "the ё-spelled chain must resolve through the parent's е twin: %r" % (g.structured,)
