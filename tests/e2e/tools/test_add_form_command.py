"""
e2e tests for add_form_command (kind: write-metadata).

The tool adds a FORM command - a FormCommand in the form's `formCommands`
collection (what get_form_structure lists under `## Commands`) - to an existing
managed form, inside a BM WRITE transaction (AbstractMetadataWriteTool ->
BmTransactions.write). The form model is mutated reflectively
(EObject/EClass/EFactory) so the bundle needs no form-model bundle dependency, the
exact pattern proven by add_form_attribute. After the write commits, the editable
form (its own top object, serialized to Form.form) is force-exported to disk. It
is a pure ADD that sets only the command's name + bilingual title: the action
(handler) is RESERVED (a complex containment chain in the form model, not wired by
this version) and putting a BUTTON on the form is out of scope. So this file covers
the happy add + its negative matrix only.

FIXTURE TARGET
--------------
The committed fixture CommonForm.Form
(TestConfiguration/src/CommonForms/Form/Form.form, a managed form) has NO
<formCommands> at all - so get_form_structure renders "_(no commands)_" before the
add. That makes it the ideal target: the probe command name is provably absent
before, and a no-op / broken write leaves the read-back unchanged so the test
FAILS. The addressing FQN is "CommonForm.Form" (a CommonForm IS a BasicForm),
matching test_get_form_structure.py / test_add_form_attribute.py.

HOW THE EFFECT IS VERIFIED (two independent channels)
-----------------------------------------------------
  1) MODEL READ-BACK over the wire: after the write we call get_form_structure on
     the same form and assert the new command name now appears under the
     "## Commands" section (it was absent / "_(no commands)_" before). A no-op
     write leaves it absent -> the test FAILS.
  2) ON DISK: the add is force-exported to the form's Form.form, so the happy test
     ALSO asserts the command lands on disk via poll_diff_contains as a
     "<name>E2EFormCmd</name>" element (the export can lag a beat after the call
     returns, hence poll not a bare diff). A write that mutated only the in-memory
     model would FAIL this on-disk check. The probe name is unique (the fixture's
     only other <name> elements are OK / OKExtendedTooltip / OKContextMenu /
     FormCommandBar), so the match is unambiguous.

  The orchestrator reverts the on-disk Form.form via reset_fixture (git) and
  resyncs the model via reset_model() (clean_project) AFTER each write-metadata
  test, so every test starts clean. We never reset ourselves.

ERROR-QUALITY note on Gson escaping: ToolResult.toJson() HTML-escapes the
apostrophe (1C-style 'X' quoting) to \\uXXXX in the JSON text channel, so
assertions only ever match delimiter-free substrings (e.g. "CommonForm.Form",
"already exists", "must start with a letter"), never a raw 'name'.

Negative matrix (whole-call errors; server sets isError via ToolResult.error):
  missing projectName / formPath / name; invalid identifier; non-existent project;
  non-existent form; duplicate form-command name. Each rejected write additionally
  asserts assert_no_diff() (a rejected write must not touch disk).
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

# Addressing FQN for the fixture managed CommonForm (same as test_get_form_structure.py).
FORM_FQN = "CommonForm.Form"


def _structure(form_path, language=None):
    """Read the form structure back (MARKDOWN; the Commands table renders each
    form command's Name + Title as a row)."""
    args = {"projectName": PROJECT, "formPath": form_path}
    if language is not None:
        args["language"] = language
    return call("get_form_structure", args)


# ──────────────────────────────────────────────────────────────────────────────
# Happy paths — write, then VERIFY VIA MODEL READ-BACK + ON-DISK
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="add_form_command", kind="write-metadata")
def test_add_form_command_appears_in_readback_and_on_disk():
    new_cmd = "E2EFormCmd"

    # Precondition: the fixture form has NO commands, so the probe is absent and the
    # section marker is the empty one. If the command were already present the
    # post-write read-back would be a self-fulfilling pass.
    before = _structure(FORM_FQN)
    assert_ok(before, "read form structure before add")
    assert_not_contains(before.text, new_cmd,
        "probe command must be absent before the add (else the read-back proves nothing)")
    assert_contains(before.text, "_(no commands)_",
        "fixture form must start with no commands")

    r = call("add_form_command", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": new_cmd,
    })
    assert_ok(r, "add_form_command CommonForm.Form/E2EFormCmd")

    # 1) MODEL READ-BACK: the new command now renders under ## Commands. A no-op write
    # leaves the "_(no commands)_" marker and no row -> FAILS.
    after = _structure(FORM_FQN)
    assert_ok(after, "read form structure after add")
    assert_contains(after.text, new_cmd,
        "the newly added FORM command must appear in the form structure read-back")
    assert_not_contains(after.text, "_(no commands)_",
        "the empty-commands marker must be gone once a command was added")

    # 2) ON DISK: the command must be serialized into Form.form as a <name> element.
    # forceExport can lag a beat, so poll. The probe name appears nowhere else.
    poll_diff_contains("<name>%s</name>" % new_cmd,
        ctx="add_form_command must serialize the form command as a <name> element in Form.form")


@e2e_test(tool="add_form_command", kind="write-metadata")
def test_add_form_command_with_bilingual_title():
    # A localized title is written into the command's title EMap keyed by the language
    # CODE (en). get_form_structure reads command rows as Name + Title, so the title is
    # verified BOTH in the model read-back (the Title column) AND ON DISK: Form.form
    # gains a <title> with <key>en</key><value>...</value> for the new command.
    new_cmd = "E2EFormCmdEn"
    title = "E2EFormCmdTitleEn"

    before = _structure(FORM_FQN)
    assert_ok(before, "read form structure before bilingual add")
    assert_not_contains(before.text, new_cmd, "probe command absent before the add")

    r = call("add_form_command", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": new_cmd,
        "title": title,
        "language": "en",
    })
    assert_ok(r, "add_form_command with en title")

    after = _structure(FORM_FQN)
    assert_ok(after, "read form structure after bilingual add")
    assert_contains(after.text, new_cmd,
        "the new form command Name appears in the model read-back")
    # The Commands table renders Name + Title, so the en-keyed title resolves and shows.
    assert_contains(after.text, title,
        "the en-keyed command title must render in the Commands table read-back")

    # ON DISK: the command's name AND its en-keyed title land in Form.form. The title
    # value is unique to this test, so a missing/mis-keyed title fails.
    poll_diff_contains("<name>%s</name>" % new_cmd,
        ctx="bilingual add persists the command name to Form.form")
    poll_diff_contains("<value>%s</value>" % title,
        ctx="bilingual add persists the en-keyed title value to Form.form")


@e2e_test(tool="add_form_command", kind="write-metadata")
def test_add_form_command_russian_type_token_resolves_same_form():
    # The formPath TYPE token is bilingual: "ОбщаяФорма" (Russian for CommonForm)
    # normalizes to "CommonForm" and resolves to the SAME form. The form Name ("Form")
    # is never translated. A regression in the bilingual type resolver would make this
    # FQN miss -> "Form not found".
    russian_fqn = "ОбщаяФорма.Form"  # ОбщаяФорма == CommonForm
    new_cmd = "E2EFormCmdRuToken"

    r = call("add_form_command", {
        "projectName": PROJECT,
        "formPath": russian_fqn,
        "name": new_cmd,
    })
    assert_ok(r, "add_form_command via Russian type token")

    # Verify against the English-token view of the SAME form.
    after = _structure(FORM_FQN)
    assert_ok(after, "read CommonForm.Form after Russian-token add")
    assert_contains(after.text, new_cmd,
        "the Russian type token must resolve to the same form (command present)")


@e2e_test(tool="add_form_command", kind="write-metadata")
def test_action_param_is_accepted_but_reserved():
    # The 'action' (handler) is RESERVED: the form model stores it as a complex
    # containment chain, not a plain string, so this version does NOT wire it. Passing
    # 'action' must NOT error - the command is still created (name + title) and the
    # parameter is simply ignored for now. Verifies the reserved param is tolerated.
    new_cmd = "E2EFormCmdAction"

    r = call("add_form_command", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": new_cmd,
        "action": "SomeHandlerName",
    })
    assert_ok(r, "add_form_command with a (reserved) action must still succeed")

    after = _structure(FORM_FQN)
    assert_ok(after, "read form structure after add with reserved action")
    assert_contains(after.text, new_cmd,
        "the command is created even though 'action' is reserved/ignored")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — missing required params (whole-call errors)
# Each rejected write must also leave the project clean on disk.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="add_form_command", kind="write-metadata")
def test_missing_project_name_is_error():
    r = call("add_form_command", {
        # projectName omitted on purpose
        "formPath": FORM_FQN,
        "name": "E2ECmd",
    })
    e = assert_error(r, "missing projectName")
    assert_error_quality(e, names=["projectName"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_form_command", kind="write-metadata")
def test_missing_form_path_is_error():
    r = call("add_form_command", {
        "projectName": PROJECT,
        # formPath omitted on purpose
        "name": "E2ECmd",
    })
    e = assert_error(r, "missing formPath")
    # "formPath is required. Examples: 'CommonForm.MyForm', ... Usage: ..."
    assert_error_quality(e, names=["formPath"], suggests=["required", "CommonForm.MyForm"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_form_command", kind="write-metadata")
def test_missing_name_is_error():
    r = call("add_form_command", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        # name omitted on purpose
    })
    e = assert_error(r, "missing name")
    assert_error_quality(e, names=["name"], suggests=["required"])
    assert_no_diff("a rejected call must not change the project")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — invalid value / not-found / duplicate
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="add_form_command", kind="write-metadata")
def test_invalid_identifier_name_is_error():
    # A name starting with a digit / containing a space is not a valid 1C identifier
    # (isValidIdentifier). The guard fires BEFORE any model access.
    bad = "1bad name"
    r = call("add_form_command", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": bad,
    })
    e = assert_error(r, "invalid identifier")
    # "Invalid form command name '1bad name'. A name must start with a letter or
    # underscore ...". The bad value is apostrophe-wrapped (escaped) so we anchor on
    # the delimiter-free substring of the value plus the actionable rule text.
    assert_error_quality(e, names=["1bad name"], suggests=["must start with a letter"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_form_command", kind="write-metadata")
def test_nonexistent_project_is_error():
    bogus = "NoSuchProject_zzz"
    r = call("add_form_command", {
        "projectName": bogus,
        "formPath": FORM_FQN,
        "name": "E2ECmd",
    })
    e = assert_error(r, "non-existent project")
    # AbstractMetadataWriteTool.resolveProjectAndConfig -> "Project not found: <name>".
    assert_error_quality(e, names=[bogus], suggests=["not found"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_form_command", kind="write-metadata")
def test_nonexistent_form_is_error():
    # Correct type token + correct FQN shape, but no such CommonForm exists ->
    # resolveMdForm returns null -> "Form not found: <formPath>. ... Names are the
    # programmatic Name, not the synonym. Use get_form_structure to inspect the form."
    bad = "CommonForm.NoSuchForm_e2e"
    r = call("add_form_command", {
        "projectName": PROJECT,
        "formPath": bad,
        "name": "E2ECmd",
    })
    e = assert_error(r, "non-existent form")
    # Names the bad FQN AND steers to get_form_structure / the Name-not-synonym rule.
    assert_error_quality(e, names=[bad], suggests=["not the synonym", "get_form_structure"])
    assert_no_diff("a rejected call must not change the project")


@e2e_test(tool="add_form_command", kind="write-metadata")
def test_duplicate_form_command_name_is_error():
    # First add succeeds; re-adding the SAME name hits the in-transaction duplicate
    # guard -> "Failed to add form command: Form command already exists: <name>".
    # The match is case-insensitive (hasCommand uses equalsIgnoreCase). The first add
    # lands on disk; the orchestrator reverts after the test, so the duplicate add
    # asserting the error is the point (and the second call must NOT add a second copy).
    dup = "E2EDupFormCmd"

    first = call("add_form_command", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": dup,
    })
    assert_ok(first, "first add of the soon-to-be-duplicate command")

    # The first add force-exports the form, which kicks off a derived-data rebuild.
    # Wait for the project to settle before the second add, else the (correct) BUILDING
    # guard returns "Project is building, wait and retry" before the in-transaction
    # duplicate check is reached — which is what we are actually asserting here.
    wait_for_project_ready()

    second = call("add_form_command", {
        "projectName": PROJECT,
        "formPath": FORM_FQN,
        "name": dup,
    })
    e = assert_error(second, "duplicate form command name")
    # Delimiter-free anchors: the name + the "already exists" reason.
    assert_error_quality(e, names=[dup], suggests=["already exists"])
