"""
e2e for validate_form_model - the structural check of one managed form (issue #473).

A form can be saved successfully and still be structurally broken; the defect then surfaces later,
when someone opens the form. The flagship case here is one the tools produce themselves and was
reproduced live before the test was written: deleting a form COMMAND leaves every button that ran
it pointing at `Form.Command.<Name>`, which is no longer in the model. Nothing refuses the delete
and nothing rewrites the button.

reset: kind="write-metadata" for the tests that mutate, "read" for the rest.
"""

import os
import re
import xml.etree.ElementTree as ET

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    poll_diff_contains,
    read_disk,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    PROJECT_DIR,
)

FORM = "Catalog.Catalog.Form.ItemForm"


@e2e_test(tool="validate_form_model", kind="write-metadata")
def test_arbitrary_and_typed_parameters_pass_the_type_picker_validation():
    fqn = FORM + ".Parameter.E2EValidateParameterType"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}),
              "declare an arbitrary parameter")
    poll_diff_contains("<name>E2EValidateParameterType</name>", ctx="untyped parameter reaches disk")
    arbitrary = call("validate_form_model", {"projectName": PROJECT, "formFqn": FORM})
    assert_ok(arbitrary, "validate the arbitrary parameter")
    assert arbitrary.structured.get("valid") is True, arbitrary.structured
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "properties": [{"name": "valueType", "value": {"types": [{"kind": "String"}]}}],
    }), "select a type offered by EDT")
    xml = read_disk("src/Catalogs/Catalog/Forms/ItemForm/Form.form")
    parameter = next(node for node in ET.fromstring(xml).findall("parameters")
                     if node.findtext("name") == "E2EValidateParameterType")
    assert parameter.findtext("valueType/types") == "String", "typed parameter must persist"
    details = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": [fqn], "assignable": True,
    })
    assert_ok(details, "read back the typed parameter")
    assert "String" in details.text, details.text
    typed = call("validate_form_model", {"projectName": PROJECT, "formFqn": FORM})
    assert_ok(typed, "validate the typed parameter")
    assert typed.structured.get("valid") is True, typed.structured


@e2e_test(tool="validate_form_model", kind="write-metadata")
def test_existing_form_parameter_with_array_type_is_an_error():
    name = "E2ELegacyArrayParameter"
    fqn = FORM + ".Parameter." + name
    path = "src/Catalogs/Catalog/Forms/ItemForm/Form.form"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}),
              "seed the parameter before importing its legacy invalid type")
    poll_diff_contains("<name>%s</name>" % name, ctx="seed parameter reaches disk")
    wait_for_project_ready()

    # Seed a legacy file directly: the guarded MCP writers must refuse this state.
    xml = read_disk(path)
    name_at = xml.index("<name>%s</name>" % name)
    start = xml.rfind("<parameters", 0, name_at)
    end = xml.index("</parameters>", name_at) + len("</parameters>")
    assert start >= 0, "the named parameter must exist in the serialized form"
    legacy, count = re.subn(r"<valueType\s*/>|<valueType>.*?</valueType>",
                           "<valueType><types>Array</types></valueType>",
                           xml[start:end], count=1, flags=re.S)
    assert count == 1, "replace exactly the seeded parameter's type"
    with open(os.path.join(PROJECT_DIR, path), "w", encoding="utf-8", newline="") as stream:
        stream.write(xml[:start] + legacy + xml[end:])
    assert_ok(call("clean_project", {"projectName": PROJECT}), "reload the legacy form from disk")
    wait_for_project_ready()
    details = call("get_metadata_details", {
        "projectName": PROJECT, "objectFqns": [fqn], "assignable": True,
    })
    assert_ok(details, "confirm that EDT loaded the legacy Array type")
    assert "Array" in details.text, details.text
    result = call("validate_form_model", {"projectName": PROJECT, "formFqn": FORM})
    assert_ok(result, "audit the legacy form")
    assert result.structured.get("valid") is False, result.structured
    assert result.structured.get("errors", 0) >= 1, result.structured
    findings = [f for f in _findings(result) if f.get("code") == "invalid-form-value-type"
                and f.get("path") == "Parameter." + name]
    assert len(findings) == 1, _findings(result)
    assert findings[0].get("severity") == "error", findings
    assert "Array" in findings[0].get("message", ""), findings
    assert "without a type" in findings[0].get("message", ""), findings


def _findings(result):
    return (result.structured or {}).get("findings") or []


@e2e_test(tool="validate_form_model", kind="read")
def test_a_clean_form_reports_no_errors():
    r = call("validate_form_model", {"projectName": PROJECT, "formFqn": FORM})
    assert_ok(r, "validate the fixture's catalog item form")
    sc = r.structured or {}
    assert sc.get("valid") is True, \
        "the fixture form must be structurally valid, got: %r" % (_findings(r),)
    assert sc.get("errors") == 0, "a valid form carries no errors: %r" % (sc.get("errors"),)
    assert sc.get("formPath") == FORM, \
        "the result must name the form it validated: %r" % (sc.get("formPath"),)


@e2e_test(tool="validate_form_model", kind="read")
def test_a_common_form_validates_too():
    r = call("validate_form_model", {"projectName": PROJECT, "formFqn": "CommonForm.Form"})
    assert_ok(r, "a CommonForm is addressed by its own two-part FQN")
    assert (r.structured or {}).get("valid") is True, \
        "the fixture's common form must be valid: %r" % (_findings(r),)


@e2e_test(tool="validate_form_model", kind="write-metadata")
def test_a_deleted_command_leaves_the_button_pointing_at_nothing():
    """The defect this tool exists for: delete_metadata removes the command and nothing rewrites the
    buttons that ran it. Verified live before this test was written - the button keeps
    `<commandName>Form.Command.ProbeCmd</commandName>` for a command that is gone."""
    command = FORM + ".Command.E2EValidateCmd"
    button = FORM + ".Button.E2EValidateBtn"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": command}),
              "seed a form command")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": button,
                                       "properties": [{"name": "command",
                                                       "value": "E2EValidateCmd"}]}),
              "seed a button bound to it")
    wait_for_project_ready()

    clean = call("validate_form_model", {"projectName": PROJECT, "formFqn": FORM})
    assert_ok(clean, "the form is still sound while the command exists")
    assert (clean.structured or {}).get("valid") is True, \
        "a bound button is not a defect: %r" % (_findings(clean),)

    assert_ok(call("delete_metadata", {"projectName": PROJECT, "fqn": command, "confirm": True}),
              "delete the command the button runs")
    wait_for_project_ready()

    broken = call("validate_form_model", {"projectName": PROJECT, "formFqn": FORM})
    assert_ok(broken, "validate after the delete")
    sc = broken.structured or {}
    assert sc.get("valid") is False, \
        "a button pointing at a deleted command must make the form invalid: %r" % (_findings(broken),)
    about_button = [f for f in _findings(broken) if "E2EValidateBtn" in (f.get("path") or "")]
    assert about_button, \
        "a finding must name the button that lost its command: %r" % (_findings(broken),)
    assert about_button[0].get("code") in ("missing-command-reference",
                                          "unresolved-command-reference"), \
        "and say what is wrong with it: %r" % (about_button[0],)


@e2e_test(tool="validate_form_model", kind="write-metadata")
def test_a_real_event_binding_is_not_called_foreign():
    """`foreign-event-reference` judges a binding against the events the platform publishes for
    its owner, so the risk it carries is noise on healthy forms: an incomplete published set would
    accuse an ordinary handler. Measured live before this test was written.

    The second half is what makes the first half mean anything. The check stays silent when it
    cannot tell, so a clean verdict alone would also be what a broken, always-empty publication
    looks like. Binding a FIELD event to the form ROOT is refused by the writer, and the refusal
    lists the events the root does publish - which proves the set is populated on this stand and
    the clean verdict above is a real comparison."""
    handler = FORM + ".Handler.OnCreateAtServer"
    assert_ok(call("create_metadata",
                   {"projectName": PROJECT, "fqn": handler,
                    "properties": [{"name": "procedure", "value": "E2EValidateOnCreate"}]}),
              "bind an event the form root really publishes")
    wait_for_project_ready()

    r = call("validate_form_model", {"projectName": PROJECT, "formFqn": FORM})
    assert_ok(r, "validate the form carrying a real binding")
    codes = [f.get("code") for f in _findings(r)]
    assert "foreign-event-reference" not in codes, \
        "a binding to an event the root publishes must not be called foreign: %r" % (codes,)

    # The published set is not simply empty - the writer names it back when it refuses.
    refused = call("create_metadata",
                   {"projectName": PROJECT, "fqn": FORM + ".Handler.OnChange",
                    "properties": [{"name": "procedure", "value": "E2EValidateOnChange"}]})
    err = assert_error(refused, "a FIELD event bound to the form root")
    assert "OnChange" in err and "Available events" in err, \
        "the refusal must name the events the root does publish: %r" % (err,)


@e2e_test(tool="validate_form_model", kind="write-metadata")
def test_writer_output_satisfies_the_new_ext_info_and_presentation_checks():
    """The two new checks do not fire on a form, attribute, and table produced by our writers.

    Their negative direction is covered by unit tests because the writers cannot produce a model
    with a missing presentation holder or a mismatched readable root ext-info."""
    form = "Catalog.Catalog.Form.E2EValidateWriterOutput"
    attribute = form + ".Attribute.Rows"
    table = form + ".Table.RowsTable"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": form}),
              "create the managed form")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attribute}),
              "create its form attribute")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": attribute,
        "properties": [{"name": "type", "value": {"types": [{"kind": "ValueTable"}]}}],
    }), "make the attribute a table row source")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {
        "projectName": PROJECT,
        "fqn": table,
        "properties": [{"name": "dataPath", "value": "Rows"}],
    }), "create a table bound to the attribute")
    wait_for_project_ready()
    poll_diff_contains("<name>RowsTable</name>",
                       ctx="the ValueTable-bound table must reach its form file")
    value_table_xml = read_disk(
        "src/Catalogs/Catalog/Forms/E2EValidateWriterOutput/Form.form")
    assert 'xsi:type="form:DynamicListTableExtInfo"' not in value_table_xml, \
        "a ValueTable-bound table must not gain a dynamic-list node: %s" % value_table_xml

    result = call("validate_form_model", {"projectName": PROJECT, "formFqn": form})
    assert_ok(result, "validate the writer-produced form")
    findings = _findings(result)
    assert not [f for f in findings if f.get("code") == "missing-presentation-flag"], \
        "writer-created attributes and columns must carry both holders: %r" % (findings,)
    root_ext_info = [
        f for f in findings
        if f.get("path") == "(form)"
        and f.get("code") in ("missing-ext-info", "stale-ext-info", "orphan-form-ext-info")
    ]
    assert not root_ext_info, \
        "the writer-created form root must match its main attribute: %r" % (root_ext_info,)


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_a_dynamic_list_table_gets_its_ext_info_on_disk():
    """A DynamicList-bound table gets its node on disk; the preceding ValueTable case pins the
    opposite no-node pairing."""
    base = "Catalog.E2EValidateDynTableExt"
    form = base + ".Form.ListForm"
    attribute = form + ".Attribute.List"
    table = form + ".Table.ListTable"
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": base}), "seed catalog")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": form}), "seed list form")
    wait_for_project_ready()
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": attribute}),
              "seed list attribute")
    wait_for_project_ready()

    converted = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": attribute,
        "properties": [
            {"name": "queryText",
             "value": "SELECT Ref, Description AS Description FROM " + base},
            {"name": "customQuery", "value": True},
        ],
    })
    assert_ok(converted, "convert the attribute into a dynamic list")
    assert "dynamicList" in ((converted.structured or {}).get("applied") or []), \
        "the attribute must really be converted: %r" % (converted.structured,)
    wait_for_project_ready()

    assert_ok(call("create_metadata", {
        "projectName": PROJECT,
        "fqn": table,
        "properties": [{"name": "dataPath", "value": "List"}],
    }), "create a table bound to the dynamic list")
    poll_diff_contains('xsi:type="form:DynamicListTableExtInfo"',
                       ctx="a DynamicList-bound table must carry its ext-info on disk")


@e2e_test(tool="validate_form_model", kind="read")
def test_an_unknown_form_is_refused_with_the_address_shape():
    r = call("validate_form_model",
             {"projectName": PROJECT, "formFqn": "Catalog.Catalog.Form.NoSuchForm"})
    err = assert_error(r, "a form that does not exist")
    assert_error_quality(err, names=["NoSuchForm"], suggests=["CommonForm", "get_metadata_objects"],
                         ctx="the refusal must name the value and how to address a form")


@e2e_test(tool="validate_form_model", kind="read")
def test_a_non_form_fqn_is_refused():
    r = call("validate_form_model", {"projectName": PROJECT, "formFqn": "Catalog.Catalog"})
    err = assert_error(r, "an object FQN that is not a form")
    assert "Catalog.Catalog" in err, "the refusal must name the value: %s" % (err,)


@e2e_test(tool="validate_form_model", kind="read")
def test_the_form_fqn_is_required():
    r = call("validate_form_model", {"projectName": PROJECT})
    err = assert_error(r, "a call with no form")
    assert "formFqn" in err, "the refusal must name the missing parameter: %s" % (err,)
