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
    read_disk,
    tree_snapshot,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    _fail,
)


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
def test_create_data_processor_form_members_allocate_form_ids_issue_189():
    # Issue #189 end-to-end repro: a managed form and ALL its attributes/items are created by
    # MCP writes. The validator used to see duplicate id=0 for field context menus, the command
    # bar serialized without <id>, and form attributes had no ids at all.
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
    # DataProcessor managed forms reject setAsDefault=true, so the repro creates the form without it.
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

    cmd, proc, btn = "RunCheck", "RunCheckAction", "RunCheckBtn"
    create_ok("%s.Command.%s" % (form_fqn, cmd), ctx="create form command")
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
    for name in attrs + fields + [cmd, proc, btn, "AutoCommandBar"]:
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
    assert_error_quality(e, names=["Nonsense"], suggests=["Attribute", "Command"],
                         ctx="an unknown form kind must list the supported form kinds")


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
        "properties": [{"name": "synonym", "value": syn_yo, "language": "ru"}],
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
    # Model read-back via get_metadata_details: get_metadata_objects has no 'xDTOPackages'
    # type filter (its supported-type list is the common-object subset), so read the created
    # package back by its FQN instead — same model-visibility proof, supported channel.
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
