"""
e2e for validate_form_model - the structural check of one managed form (issue #473).

A form can be saved successfully and still be structurally broken; the defect then surfaces later,
when someone opens the form. The flagship case here is one the tools produce themselves and was
reproduced live before the test was written: deleting a form COMMAND leaves every button that ran
it pointing at `Form.Command.<Name>`, which is no longer in the model. Nothing refuses the delete
and nothing rewrites the button.

reset: kind="write-metadata" for the tests that mutate, "read" for the rest.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)

FORM = "Catalog.Catalog.Form.ItemForm"


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
