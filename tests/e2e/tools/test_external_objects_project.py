"""
EXTERNAL-OBJECTS project coverage — issue #309.

An EDT project with `V8ExternalObjectsNature` is a THIRD project kind, alongside a base
configuration and a configuration extension. Its roots are its own `ExternalDataProcessor`
/ `ExternalReport` objects, held by the project itself; it has no `Configuration` of its
own, and `IConfigurationProvider.getConfiguration(project)` answers with the BASE
configuration it is linked to — a different project's model entirely.

Every FQN-addressed metadata tool used to resolve against that answer. The result was not
an error but a WRONG one: `get_metadata_objects` listed the base configuration's objects
for the external project, `get_metadata_details` reported "Object not found" for an
external data processor that plainly exists, and `create_metadata` answered "Form not
found" for a form sitting on disk. This file is the regression guard for that whole class:
the tools must answer about THE PROJECT THE CALLER NAMED.

Fixture ground truth (tests/ExternalObjects, base project TestConfiguration):
  ExternalDataProcessor `ExtProc` — attribute `Note` (String 100), form `MainForm`
    (managed, main attribute `Object`, one bound field `Note`);
  ExternalReport `ExtReport` — no members.

Mutation safety: the mutating tests here create and then DELETE what they created through
the tools, and each ends by asserting the fixture path is byte-clean again. They never
touch the base project, so assert_no_diff() (which is scoped to it) holds throughout.
"""

from harness import (
    call, assert_ok, assert_error, assert_error_quality, assert_contains,
    assert_not_contains, assert_no_diff, assert_no_diff_rel, poll_diff_contains_rel,
    read_fixture_file, reset_fixture_rel, wait_for_project_ready, e2e_test, PROJECT,
    EXT_OBJECTS_PROJECT, EXT_OBJECTS_REL,
)

# The Russian TYPE tokens for the two external-objects types. The bilingual token catalogue
# accepts them exactly like the English ones, and a caller in a Russian workspace types these.
RU_EXTERNAL_DATA_PROCESSOR = "ВнешняяОбработка"
RU_EXTERNAL_REPORT = "ВнешнийОтчет"


@e2e_test(tool="get_metadata_objects", kind="read")
def test_extobj_project_lists_its_own_roots():
    """The external-objects project lists ExtProc / ExtReport — not the base config's objects.

    This is the reported symptom verbatim: the tool answered with the LINKED configuration's
    objects. A regression would show TestConfiguration's Catalog / CommonModule names here and
    none of the project's own.
    """
    r = call("get_metadata_objects", {"projectName": EXT_OBJECTS_PROJECT})
    assert_ok(r, "get_metadata_objects on an external-objects project")
    assert_contains(r.text, "ExtProc", "the project's own external data processor must be listed")
    assert_contains(r.text, "ExternalDataProcessor", "its TYPE must be named")
    assert_contains(r.text, "ExtReport", "the project's own external report must be listed")
    # The discriminating half: nothing from the BASE configuration may leak in.
    assert_not_contains(r.text, "CommonModule",
                        "the base configuration's objects must NOT be listed for this project")
    assert_no_diff("a read tool must not touch the base project on disk")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a read tool must not touch the external-objects project")


@e2e_test(tool="get_metadata_objects", kind="read")
def test_extobj_type_filter_is_bilingual():
    """The type filter accepts the English AND the Russian type token, and filters by it."""
    en = call("get_metadata_objects",
              {"projectName": EXT_OBJECTS_PROJECT, "metadataType": "externalReports"})
    assert_ok(en, "English category token")
    assert_contains(en.text, "ExtReport", "the report must be listed")
    assert_not_contains(en.text, "ExtProc",
                        "a report-only filter must exclude the data processor")

    ru = call("get_metadata_objects",
              {"projectName": EXT_OBJECTS_PROJECT, "metadataType": RU_EXTERNAL_DATA_PROCESSOR})
    assert_ok(ru, "Russian type token")
    assert_contains(ru.text, "ExtProc", "the Russian token must resolve to the same type")
    assert_not_contains(ru.text, "ExtReport",
                        "a data-processor-only filter must exclude the report")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a read tool must not touch the fixture")


@e2e_test(tool="get_metadata_objects", kind="error")
def test_extobj_configuration_category_is_refused():
    """A configuration category asked of this project is REFUSED, naming what it does hold.

    Answering it from the linked base configuration is the bug; answering it with an empty
    list would be almost as bad (it reads as "this project has no catalogs" rather than
    "this project cannot have catalogs").
    """
    r = call("get_metadata_objects",
             {"projectName": EXT_OBJECTS_PROJECT, "metadataType": "catalogs"})
    e = assert_error(r, "a configuration category on an external-objects project")
    assert_error_quality(e, names=["externalDataProcessors", "ExternalReport"],
                         ctx="the refusal must name the vocabulary this project does accept")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a refused call must not touch the fixture")


@e2e_test(tool="get_metadata_details", kind="read")
def test_extobj_details_resolve_the_object_and_its_form():
    """get_metadata_details renders the external data processor AND its form's content model.

    "the form has no editable content model" was the reported failure: the form was being
    looked for in the base configuration, where it does not exist.
    """
    r = call("get_metadata_details",
             {"projectName": EXT_OBJECTS_PROJECT,
              "objectFqns": ["ExternalDataProcessor.ExtProc",
                             "ExternalDataProcessor.ExtProc.Form.MainForm"]})
    assert_ok(r, "get_metadata_details on an external-objects project")
    assert_contains(r.text, "ExternalDataProcessor: ExtProc", "the object must render")
    assert_contains(r.text, "Note", "its attribute must render")
    assert_contains(r.text, "MainForm", "its form must be listed")
    # The form's CONTENT model — the part that reported "no editable content model".
    assert_contains(r.text, "Form Structure", "the form's structure must render")
    assert_contains(r.text, "FormCommandBar", "the form's items must render")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a read tool must not touch the fixture")


@e2e_test(tool="get_metadata_details", kind="read")
def test_extobj_details_resolve_the_russian_type_token():
    """The leading TYPE token is bilingual here exactly as it is for a configuration type."""
    r = call("get_metadata_details",
             {"projectName": EXT_OBJECTS_PROJECT,
              "objectFqns": [RU_EXTERNAL_DATA_PROCESSOR + ".ExtProc",
                             RU_EXTERNAL_REPORT + ".ExtReport"]})
    assert_ok(r, "Russian type tokens")
    assert_contains(r.text, "ExternalDataProcessor: ExtProc",
                    "the Russian token must resolve to the same object")
    assert_contains(r.text, "ExternalReport: ExtReport", "and so must the report token")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a read tool must not touch the fixture")


@e2e_test(tool="get_metadata_details", kind="error")
def test_extobj_type_on_a_configuration_names_the_project_kind():
    """On the BASE configuration the same FQN cannot resolve — and the reason says why.

    A bare "Object not found" sent the caller looking for a typo in a name that is spelled
    correctly; the project kind is the actual answer.
    """
    r = call("get_metadata_details",
             {"projectName": PROJECT, "objectFqns": ["ExternalDataProcessor.ExtProc"]})
    # A per-object miss is a failures TABLE inside a successful call, not a call-level error.
    assert_ok(r, "an external FQN on a configuration project")
    assert_contains(r.text, "Object not found", "it must still report the miss")
    assert_contains(r.text, "external-objects",
                    "the reason must name the project kind that holds this type")
    assert_no_diff("a read tool must not touch the base project on disk")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_extobj_create_and_delete_a_form_element():
    """The reported call: create a Group on an external data processor's form.

    It answered "Form not found for '...'" because the form was resolved against the base
    configuration. Here it must create the group, persist it into the fixture's Form.form,
    and delete cleanly again.
    """
    reset_fixture_rel(EXT_OBJECTS_REL)
    fqn = "ExternalDataProcessor.ExtProc.Form.MainForm.Group.E2eGroup"
    created = call("create_metadata",
                   {"projectName": EXT_OBJECTS_PROJECT, "fqn": fqn,
                    "properties": [{"name": "title", "value": "E2e group", "language": "en"}],
                    "expectedNotExists": True})
    try:
        assert_ok(created, "create a form group on an external data processor's form")
        # The real effect, read off disk: the group is in the form's own content file.
        after = call("get_metadata_details",
                     {"projectName": EXT_OBJECTS_PROJECT,
                      "objectFqns": ["ExternalDataProcessor.ExtProc.Form.MainForm"]})
        assert_ok(after, "re-read the form")
        assert_contains(after.text, "E2eGroup", "the new group must be in the form structure")
        # …and on DISK, in the form's own content file - a model-only change would pass the
        # read-back above and still leave the project unchanged for everyone else.
        poll_diff_contains_rel(EXT_OBJECTS_REL, "E2eGroup",
                               ctx="the group must reach Form.form on disk")
    finally:
        removed = call("delete_metadata",
                       {"projectName": EXT_OBJECTS_PROJECT, "fqn": fqn, "confirm": True})
        assert_ok(removed, "delete the form group again")
    # Create + delete round-trips to the committed baseline: nothing left behind.
    assert_no_diff_rel(EXT_OBJECTS_REL, "the create/delete round trip must leave no diff")
    assert_no_diff("the base project must never be touched by this test")


@e2e_test(tool="create_metadata", kind="write")
def test_extobj_create_and_delete_an_attribute():
    """A MEMBER of the external object itself — "Cannot resolve a create target" in the report."""
    reset_fixture_rel(EXT_OBJECTS_REL)
    fqn = "ExternalDataProcessor.ExtProc.Attribute.E2eAttr"
    created = call("create_metadata",
                   {"projectName": EXT_OBJECTS_PROJECT, "fqn": fqn,
                    "properties": [{"name": "synonym", "value": "E2e attr", "language": "en"}],
                    "expectedNotExists": True})
    try:
        assert_ok(created, "create an attribute on an external data processor")
        after = call("get_metadata_details",
                     {"projectName": EXT_OBJECTS_PROJECT,
                      "objectFqns": ["ExternalDataProcessor.ExtProc"]})
        assert_ok(after, "re-read the object")
        assert_contains(after.text, "E2eAttr", "the new attribute must be listed")
        poll_diff_contains_rel(EXT_OBJECTS_REL, "E2eAttr",
                               ctx="the attribute must reach the .mdo on disk")
    finally:
        removed = call("delete_metadata",
                       {"projectName": EXT_OBJECTS_PROJECT, "fqn": fqn, "confirm": True})
        assert_ok(removed, "delete the attribute again")
    assert_no_diff_rel(EXT_OBJECTS_REL, "the create/delete round trip must leave no diff")
    assert_no_diff("the base project must never be touched by this test")


@e2e_test(tool="create_metadata", kind="write")
def test_extobj_create_a_form_object_with_generated_content():
    """generateContent must SEED the main Object attribute on an external data processor.

    An external data processor's object form has exactly the shape a DataProcessor's does - the
    committed fixture form is that shape - so the seed applies. It was silently skipped because
    the object-form capability list named only the configuration twins, and the call then
    reported generateContent=false and created an empty form (issue #309 review).
    """
    reset_fixture_rel(EXT_OBJECTS_REL)
    fqn = "ExternalDataProcessor.ExtProc.Form.E2eSeeded"
    created = call("create_metadata",
                   {"projectName": EXT_OBJECTS_PROJECT, "fqn": fqn,
                    "generateContent": True, "expectedNotExists": True})
    try:
        assert_ok(created, "create a form object with generateContent on an external owner")
        after = call("get_metadata_details",
                     {"projectName": EXT_OBJECTS_PROJECT, "objectFqns": [fqn]})
        assert_ok(after, "re-read the new form")
        assert_contains(after.text, "Object",
                        "the seeded main attribute must be in the form structure")
        # The seeded attribute's value type is the owner's OWN produced object type. In the DT
        # model that is ExternalDataProcessor.<Name> - the "Object" suffix is an XML-export
        # spelling - so assert the DT name reaches disk.
        poll_diff_contains_rel(EXT_OBJECTS_REL, "ExternalDataProcessor.ExtProc",
                               ctx="the main attribute must be typed by the owner's object type")
    finally:
        removed = call("delete_metadata",
                       {"projectName": EXT_OBJECTS_PROJECT, "fqn": fqn, "confirm": True})
        assert_ok(removed, "delete the generated form again")
    assert_no_diff_rel(EXT_OBJECTS_REL, "the create/delete round trip must leave no diff")
    assert_no_diff("the base project must never be touched by this test")


@e2e_test(tool="create_metadata", kind="error")
def test_extobj_unsupported_member_kind_is_refused_by_name():
    """An ExternalDataProcessor has no `commands` collection at all — the error says so.

    The generic "cannot resolve a create target" reads as a spelling problem and sends the
    caller round the same loop; naming the kinds the object DOES have is the next step.
    """
    reset_fixture_rel(EXT_OBJECTS_REL)
    r = call("create_metadata",
             {"projectName": EXT_OBJECTS_PROJECT,
              "fqn": "ExternalDataProcessor.ExtProc.Command.E2eCmd"})
    e = assert_error(r, "a kind the owner type does not have")
    assert_error_quality(e, names=["Command", "Attribute", "TabularSection"],
                         ctx="the refusal must name the rejected kind AND the accepted ones")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a refused call must not touch the fixture")


@e2e_test(tool="create_metadata", kind="error")
def test_extobj_top_level_create_is_refused_with_the_way_to_do_it():
    """create_metadata cannot create the ROOT object — and says where one comes from."""
    reset_fixture_rel(EXT_OBJECTS_REL)
    r = call("create_metadata",
             {"projectName": EXT_OBJECTS_PROJECT, "fqn": "ExternalDataProcessor.E2eNewProc"})
    e = assert_error(r, "a top-level external data processor")
    assert_error_quality(e, names=["ExternalDataProcessor", "create_project"],
                         ctx="the refusal must say what DOES create such an object")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a refused call must not touch the fixture")


@e2e_test(tool="modify_metadata", kind="write")
def test_extobj_modify_a_form_member_title():
    """modify_metadata reaches a form member of an external data processor (reported broken)."""
    reset_fixture_rel(EXT_OBJECTS_REL)
    fqn = "ExternalDataProcessor.ExtProc.Form.MainForm.Field.Note"
    try:
        r = call("modify_metadata",
                 {"projectName": EXT_OBJECTS_PROJECT, "fqn": fqn,
                  "properties": [{"name": "title", "value": "E2e note", "language": "en"}]})
        assert_ok(r, "modify a form field's title on an external data processor's form")
        after = call("get_metadata_details",
                     {"projectName": EXT_OBJECTS_PROJECT,
                      "objectFqns": ["ExternalDataProcessor.ExtProc.Form.MainForm"]})
        assert_ok(after, "re-read the form")
        assert_contains(after.text, "E2e note", "the new title must be in the form structure")
        poll_diff_contains_rel(EXT_OBJECTS_REL, "E2e note",
                               ctx="the title must reach Form.form on disk")
    finally:
        reset_fixture_rel(EXT_OBJECTS_REL)
        call("clean_project", {"projectName": EXT_OBJECTS_PROJECT})
    assert_no_diff_rel(EXT_OBJECTS_REL, "the fixture must be back at its baseline")
    assert_no_diff("the base project must never be touched by this test")


@e2e_test(tool="dcs", kind="write-metadata")
def test_extobj_external_report_owned_dcs_round_trip_uses_inherited_language():
    """An ExternalReport-owned DCS template resolves without any owner-type special case.

    The plain field title is intentionally written without ``language``. ExternalObjects inherits
    TestConfiguration through Base-Project, whose language code is ``en``; the exported DCS must
    therefore carry ``en`` and never a fabricated ``ru`` fallback.
    """
    reset_fixture_rel(EXT_OBJECTS_REL)
    root = "ExternalReport.ExtReport.Template.E2EDcsOwned"
    dcs_rel = "src/ExternalReports/ExtReport/Templates/E2EDcsOwned/Template.dcs"
    created = call("create_metadata",
                   {"projectName": EXT_OBJECTS_PROJECT, "fqn": root,
                    "expectedNotExists": True})
    try:
        assert_ok(created, "create an owned template on the existing external report")
        wait_for_project_ready()
        declared = call("modify_metadata", {
            "projectName": EXT_OBJECTS_PROJECT,
            "fqn": root,
            "properties": [{"name": "templateType", "value": "DataCompositionSchema"}],
        })
        assert_ok(declared, "declare the external-report-owned template as a DCS")
        wait_for_project_ready()

        summary = call("dcs", {
            "projectName": EXT_OBJECTS_PROJECT,
            "fqn": root,
            "action": "get",
            "type": "schema",
        })
        assert_ok(summary, "read the external-report-owned DCS template")
        assert_contains(summary.text, root + "#/dataSets",
                        "the owned template must expose schema addresses")

        title = "Inherited English field title"
        field = "InheritedLanguageField"
        written = call("dcs", {
            "projectName": EXT_OBJECTS_PROJECT,
            "fqn": root,
            "action": "upsert",
            "type": "schema",
            "body": {"dataSets": [{
                "name": "ExternalData",
                "type": "query",
                "query": "SELECT 1 AS " + field,
                "autoFillFields": False,
                "fields": [{"dataPath": field, "field": field, "title": title}],
            }]},
        })
        assert_ok(written, "write a typed schema body through the owned DCS root")
        poll_diff_contains_rel(EXT_OBJECTS_REL, title,
                               ctx="the DCS field title must reach Template.dcs")
        on_disk = read_fixture_file(EXT_OBJECTS_REL, dcs_rel)
        assert title in on_disk and field in on_disk, \
            "the authored field and title must be read back from %s" % dcs_rel
        assert ">en<" in on_disk, \
            "a plain title must use the inherited base-project language code 'en'"
        assert ">ru<" not in on_disk, \
            "the external project must not fabricate a Russian language fallback"
    finally:
        removed = call("delete_metadata",
                       {"projectName": EXT_OBJECTS_PROJECT, "fqn": root, "confirm": True})
        assert_ok(removed, "delete the external-report-owned DCS template again")
    assert_no_diff_rel(EXT_OBJECTS_REL, "the owned-DCS round trip must leave no diff")
    assert_no_diff("the owned-DCS round trip must never touch the base project")


@e2e_test(tool="dcs", kind="write-metadata")
def test_extobj_external_report_main_dcs_root_resolves():
    """The existing ExtReport is a supported main-DCS root even before one is materialized."""
    reset_fixture_rel(EXT_OBJECTS_REL)
    root = "ExternalReport.ExtReport"
    result = call("dcs", {
        "projectName": EXT_OBJECTS_PROJECT,
        "fqn": root,
        "action": "get",
        "type": "schema",
    })
    assert_ok(result, "read the existing external report's main DCS root")
    assert_contains(result.text, "**Hash:** `", "an empty main-DCS root still returns a hash")
    assert_contains(result.text, root + "#/dataSets",
                    "the external report root must expose schema addresses")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a main-DCS read must not touch the external fixture")
    assert_no_diff("a main-DCS read must not fall through to or touch the base project")


@e2e_test(tool="dcs", kind="error")
def test_extobj_external_data_processor_has_owned_templates_but_no_main_dcs_root():
    """ExtProc is refused as a main root and points to its supported owned-template shape."""
    reset_fixture_rel(EXT_OBJECTS_REL)
    root = "ExternalDataProcessor.ExtProc"
    result = call("dcs", {
        "projectName": EXT_OBJECTS_PROJECT,
        "fqn": root,
        "action": "get",
        "type": "schema",
    })
    error = assert_error(result, "an external data processor addressed as a main DCS root")
    assert_error_quality(error, names=[root, "no main DCS"],
                         suggests=["ExternalDataProcessor.<Name>.Template.<Name>"],
                         ctx="the refusal must name the owned-template alternative")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a refused DCS root must not touch the external fixture")
    assert_no_diff("a refused DCS root must not touch the base project")


@e2e_test(tool="go_to_definition", kind="read")
def test_extobj_go_to_definition_resolves_the_projects_own_object():
    """go_to_definition resolves an FQN in the project that owns it.

    The tool ADVERTISES every type in the shared catalogue, which now includes
    ExternalDataProcessor / ExternalReport. Resolving through the base configuration would
    make it advertise what it cannot do: the object exists, and the answer would be
    "Symbol not found".
    """
    r = call("go_to_definition",
             {"projectName": EXT_OBJECTS_PROJECT, "symbol": "ExternalDataProcessor.ExtProc"})
    assert_ok(r, "go_to_definition on the project's own external data processor")
    assert_not_contains(r.text, "Symbol not found",
                        "the object exists in THIS project and must resolve")
    assert_contains(r.text, "ExtProc", "the resolved object must be named")
    assert_contains(r.text, "MetadataObject", "it must resolve as a metadata object")
    assert_no_diff("a read tool must not touch the base project on disk")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a read tool must not touch the external-objects project")


@e2e_test(tool="delete_metadata", kind="error")
def test_extobj_every_write_tool_refuses_a_type_it_cannot_hold():
    """create / modify / delete all refuse a configuration type by naming the project kind.

    Each tool has specialized dispatches that resolve their own project context and reach the
    Configuration directly — the BASE one for a linked external-objects project. Each branch
    that lacked the check answered about the wrong project: a real owner found next door and
    then "Owner object not found in transaction", or a local not-found that sends the caller
    looking for something this project can never hold. The guard is now part of getting the
    context, so the three tools answer the same way.
    """
    reset_fixture_rel(EXT_OBJECTS_REL)
    predefined = call("create_metadata",
                      {"projectName": EXT_OBJECTS_PROJECT,
                       "fqn": "Catalog.Catalog.Predefined.E2eItem"})
    e = assert_error(predefined, "a predefined item addressed at an external-objects project")
    assert_not_contains(e, "in transaction", "an internal transaction message is not an answer")
    assert_error_quality(e, names=["Catalog", EXT_OBJECTS_PROJECT],
                         ctx="the refusal must name the type and the project kind")

    modified = call("modify_metadata",
                    {"projectName": EXT_OBJECTS_PROJECT, "fqn": "Subsystem.Subsystem",
                     "content": [{"metadata": "Catalog.Catalog"}]})
    e = assert_error(modified, "a subsystem content write on an external-objects project")
    assert_error_quality(e, names=["Subsystem", EXT_OBJECTS_PROJECT],
                         ctx="modify must refuse the same way create does")

    deleted = call("delete_metadata",
                   {"projectName": EXT_OBJECTS_PROJECT,
                    "fqn": "Catalog.Catalog.Predefined.Item", "confirm": True})
    e = assert_error(deleted, "a predefined delete on an external-objects project")
    assert_not_contains(e, "get_metadata_objects to list",
                        "do not send the caller hunting for an owner that cannot be here")
    assert_error_quality(e, names=["Catalog", EXT_OBJECTS_PROJECT],
                         ctx="delete must refuse the same way create does")

    assert_no_diff_rel(EXT_OBJECTS_REL, "refused calls must not touch the fixture")
    assert_no_diff("refused calls must not touch the base project either")


@e2e_test(tool="create_metadata", kind="error")
def test_extobj_specialized_dispatches_refuse_before_reading_the_base_configuration():
    """The branches that own their resolution must be refused too, not just the generic one.

    Two dispatches in create_metadata return before the scope-aware create target and resolve
    through the Configuration — which for a LINKED external-objects project is the BASE one.
    The nested-subsystem branch found a real parent in the other project and then failed with
    "Parent subsystem not found in transaction"; the XDTO branch answered "XDTOPackage not
    found ... create it first", telling the caller to create one in a project that can never
    hold it. Both must refuse by naming the project kind instead.
    """
    reset_fixture_rel(EXT_OBJECTS_REL)
    nested = call("create_metadata",
                  {"projectName": EXT_OBJECTS_PROJECT,
                   "fqn": "Subsystem.Subsystem.Subsystem.E2eChild"})
    e = assert_error(nested, "a nested subsystem addressed at an external-objects project")
    assert_not_contains(e, "in transaction",
                        "an internal transaction message is not an answer")
    assert_error_quality(e, names=["Subsystem", EXT_OBJECTS_PROJECT],
                         ctx="the refusal must name the type and the project kind")

    xdto = call("create_metadata",
                {"projectName": EXT_OBJECTS_PROJECT,
                 "fqn": "XDTOPackage.BasePkg.ObjectType.NewType"})
    e = assert_error(xdto, "an XDTO member addressed at an external-objects project")
    assert_not_contains(e, "Create it first",
                        "never advise creating something this project kind cannot hold")
    assert_error_quality(e, names=["XDTOPackage", EXT_OBJECTS_PROJECT],
                         ctx="the refusal must name the type and the project kind")

    assert_no_diff_rel(EXT_OBJECTS_REL, "a refused call must not touch the fixture")
    assert_no_diff("a refused call must not touch the base project either")


@e2e_test(tool="go_to_definition", kind="read")
def test_extobj_type_asked_of_a_configuration_says_where_it_lives():
    """The REVERSE direction: a standalone type asked of a configuration project.

    The type catalogue is global, so go_to_definition advertises ExternalDataProcessor to a
    configuration project too. A bare "object was not found" there implies the object could
    exist in this project if only the name were right — it never can.
    """
    r = call("go_to_definition",
             {"projectName": PROJECT, "symbol": "ExternalDataProcessor.ExtProc"})
    assert_ok(r, "a standalone type asked of the configuration project")
    assert_contains(r.text, "Symbol not found", "it cannot resolve here")
    assert_contains(r.text, "EXTERNAL-OBJECTS type",
                    "the answer must say the type lives in another project KIND")
    assert_contains(r.text, "list_projects", "and how to find that project")
    assert_no_diff("a read tool must not touch the base project on disk")


@e2e_test(tool="get_metadata_details", kind="read")
def test_extobj_details_do_not_call_an_external_object_a_core_object():
    """The Origin footer must not claim an external object belongs to a configuration.

    The footer is two-valued (core / extension) because those were the only project kinds it
    knew. An external data processor is owned by its own project, so "core" is not a rougher
    answer — it is a false one, and it is the exact confusion this whole area removes.
    """
    r = call("get_metadata_details",
             {"projectName": EXT_OBJECTS_PROJECT,
              "objectFqns": ["ExternalDataProcessor.ExtProc"]})
    assert_ok(r, "details of an external data processor")
    assert_contains(r.text, "external object", "the origin must name the owning project kind")
    assert_not_contains(r.text, "Origin:** core",
                        "an external object is not a core configuration object")
    assert_no_diff("a read tool must not touch the base project on disk")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a read tool must not touch the external-objects project")


@e2e_test(tool="create_metadata", kind="error")
def test_extobj_configuration_root_create_is_refused_not_crashed():
    """A configuration FQN on an external-objects project refuses by name, never by exception.

    There is no Configuration here to add a top object to. The create used to treat this
    project's root as one and surface a raw ClassCastException ("ExternalReportImpl cannot be
    cast to Configuration") — an internal detail instead of the refusal that says which project
    kind holds a Catalog.
    """
    reset_fixture_rel(EXT_OBJECTS_REL)
    r = call("create_metadata",
             {"projectName": EXT_OBJECTS_PROJECT, "fqn": "Catalog.E2eShouldRefuse"})
    e = assert_error(r, "a configuration type addressed at an external-objects project")
    assert_not_contains(e, "cannot be cast",
                        "a refusal must not surface a ClassCastException")
    assert_error_quality(e, names=["Catalog", EXT_OBJECTS_PROJECT],
                         ctx="the refusal must name the type and the project kind that holds it")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a refused call must not touch the fixture")


@e2e_test(tool="go_to_definition", kind="read")
def test_extobj_go_to_definition_hints_for_a_type_shaped_module_name():
    """A base common module NAMED like a metadata type still gets the base-project pointer.

    `Catalog` is both a metadata type token and a legal common-module name. The type branch
    wins the dispatch, so without the hint the caller of `Catalog.SomeMethod` hears only "no
    such Catalog object" and is never told a module of that name could live in the base project.
    """
    r = call("go_to_definition",
             {"projectName": EXT_OBJECTS_PROJECT, "symbol": "Catalog.SomeMethod"})
    assert_ok(r, "a type-shaped module name on an external-objects project")
    assert_contains(r.text, "Symbol not found", "neither reading resolves in this project")
    assert_contains(r.text, "no common modules",
                    "the module reading must be answered, not silently dropped")
    assert_contains(r.text, PROJECT, "the base project must be named as where to ask")
    assert_no_diff("a read tool must not touch the base project on disk")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a read tool must not touch the external-objects project")


@e2e_test(tool="go_to_definition", kind="read")
def test_extobj_go_to_definition_never_reaches_the_base_common_modules():
    """A base common module must not be answered under the external project's name.

    The base configuration holds `Calc`; this project holds no common modules at all. The
    tool used to find the base one and then load `src/CommonModules/Calc/Module.bsl` from
    HERE, failing with "Module not found" about a path the caller never named — an error
    blaming the wrong project. Worse, had such a path existed here it would have served an
    unrelated local file under the base module's identity.
    """
    r = call("go_to_definition",
             {"projectName": EXT_OBJECTS_PROJECT, "symbol": "Calc.Add"})
    assert_ok(r, "a base common module name asked of an external-objects project")
    assert_not_contains(r.text, "CommonModules/Calc/Module.bsl",
                        "the answer must not blame a path in the project the caller named")
    assert_contains(r.text, "Symbol not found", "the symbol is not in this project")
    # A dead end would be truthful but useless: the refusal names where common modules live.
    assert_contains(r.text, PROJECT,
                    "the refusal must name the base project that does hold common modules")
    assert_no_diff("a read tool must not touch the base project on disk")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a read tool must not touch the external-objects project")


@e2e_test(tool="go_to_definition", kind="read")
def test_extobj_go_to_definition_never_suggests_from_the_base_configuration():
    """A miss on the external project suggests from ITS root, never from the base config.

    The base configuration TestConfiguration holds a Catalog; this project holds none. A
    "Did you mean?" list here could only have come from the wrong model — the #309 bug in
    its quietest form, since the response still looks perfectly reasonable.
    """
    r = call("go_to_definition",
             {"projectName": EXT_OBJECTS_PROJECT, "symbol": "Catalog.Catalo"})
    assert_ok(r, "go_to_definition for a configuration type on an external-objects project")
    assert_contains(r.text, "Symbol not found", "this project holds no catalogs")
    assert_not_contains(r.text, "Did you mean",
                        "a suggestion here could only come from the base configuration")
    # The same tool DOES suggest, from this project's own root, for a type it does hold.
    own = call("go_to_definition",
               {"projectName": EXT_OBJECTS_PROJECT, "symbol": "ExternalDataProcessor.ExtPro"})
    assert_ok(own, "go_to_definition for a near-miss on the project's own type")
    assert_contains(own.text, "ExternalDataProcessor.ExtProc",
                    "the suggestion must come from the project's own objects")
    assert_no_diff("a read tool must not touch the base project on disk")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a read tool must not touch the external-objects project")
