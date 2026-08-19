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
    assert_not_contains, assert_no_diff, assert_no_diff_rel, reset_fixture_rel, e2e_test,
    PROJECT, EXT_OBJECTS_PROJECT, EXT_OBJECTS_REL,
)

# The Russian TYPE tokens for the two external-objects types. The bilingual token catalogue
# accepts them exactly like the English ones, and a caller in a Russian workspace types these.
RU_EXTERNAL_DATA_PROCESSOR = "ВнешняяОбработка"
RU_EXTERNAL_REPORT = "ВнешнийОтчет"


@e2e_test(tool="get_metadata_objects", kind="read")
def test_external_objects_project_lists_its_own_roots():
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
def test_external_objects_type_filter_is_bilingual():
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
def test_configuration_category_is_refused_not_answered_from_the_base_config():
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
def test_details_resolve_the_external_object_and_its_form():
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
def test_details_resolve_the_russian_type_token():
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
def test_external_type_asked_of_a_configuration_says_which_project_kind_holds_it():
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


@e2e_test(tool="create_metadata", kind="write")
def test_create_and_delete_a_form_element_on_an_external_data_processor_form():
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
        assert_contains(created.text, "created", "the result must report the creation")
        # The real effect, read off disk: the group is in the form's own content file.
        after = call("get_metadata_details",
                     {"projectName": EXT_OBJECTS_PROJECT,
                      "objectFqns": ["ExternalDataProcessor.ExtProc.Form.MainForm"]})
        assert_ok(after, "re-read the form")
        assert_contains(after.text, "E2eGroup", "the new group must be in the form structure")
    finally:
        removed = call("delete_metadata",
                       {"projectName": EXT_OBJECTS_PROJECT, "fqn": fqn, "confirm": True})
        assert_ok(removed, "delete the form group again")
    # Create + delete round-trips to the committed baseline: nothing left behind.
    assert_no_diff_rel(EXT_OBJECTS_REL, "the create/delete round trip must leave no diff")
    assert_no_diff("the base project must never be touched by this test")


@e2e_test(tool="create_metadata", kind="write")
def test_create_and_delete_an_attribute_on_an_external_data_processor():
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
    finally:
        removed = call("delete_metadata",
                       {"projectName": EXT_OBJECTS_PROJECT, "fqn": fqn, "confirm": True})
        assert_ok(removed, "delete the attribute again")
    assert_no_diff_rel(EXT_OBJECTS_REL, "the create/delete round trip must leave no diff")
    assert_no_diff("the base project must never be touched by this test")


@e2e_test(tool="create_metadata", kind="error")
def test_a_kind_the_type_does_not_have_is_refused_by_name():
    """An ExternalDataProcessor has no `commands` collection at all — the error says so.

    The generic "cannot resolve a create target" reads as a spelling problem and sends the
    caller round the same loop; naming the kinds the object DOES have is the next step.
    """
    r = call("create_metadata",
             {"projectName": EXT_OBJECTS_PROJECT,
              "fqn": "ExternalDataProcessor.ExtProc.Command.E2eCmd"})
    e = assert_error(r, "a kind the owner type does not have")
    assert_error_quality(e, names=["Command", "Attribute", "TabularSection"],
                         ctx="the refusal must name the rejected kind AND the accepted ones")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a refused call must not touch the fixture")


@e2e_test(tool="create_metadata", kind="error")
def test_creating_a_top_level_external_object_is_refused_with_the_way_to_do_it():
    """create_metadata cannot create the ROOT object — and says where one comes from."""
    r = call("create_metadata",
             {"projectName": EXT_OBJECTS_PROJECT, "fqn": "ExternalDataProcessor.E2eNewProc"})
    e = assert_error(r, "a top-level external data processor")
    assert_error_quality(e, names=["ExternalDataProcessor", "create_project"],
                         ctx="the refusal must say what DOES create such an object")
    assert_no_diff_rel(EXT_OBJECTS_REL, "a refused call must not touch the fixture")


@e2e_test(tool="modify_metadata", kind="write")
def test_modify_a_form_member_title_on_an_external_data_processor_form():
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
    finally:
        reset_fixture_rel(EXT_OBJECTS_REL)
        call("clean_project", {"projectName": EXT_OBJECTS_PROJECT})
    assert_no_diff_rel(EXT_OBJECTS_REL, "the fixture must be back at its baseline")
    assert_no_diff("the base project must never be touched by this test")
