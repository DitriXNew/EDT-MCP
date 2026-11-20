"""
e2e tests for the METADATA OBJECT of an owned form - issue #995 (kinds: read, write-metadata).

`Type.Object.Form.F` names two objects: the form's metadata object (a BasicForm in the owner's
`forms`, written inline in the OWNER's .mdo - name, synonym, comment, usePurposes, ...) and the form's
content root (the form:Form object in Form.form - title, autoTitle, ...). The two share no property
name, so modify_metadata routes a call by its property names, the rule CommonForm.Name already
followed: any property of the metadata object sends the whole call to the .mdo, a call with none goes
to Form.form as before, and a call mixing both is refused before anything is written.
get_metadata_details(assignable:true) lists the metadata object's table first, then the content root's.

Before #995 every owned-form address went to the content root: `synonym` was refused with "Property
'synonym' is not assignable on Form", the assignable view showed only the content root, and an owned
form of an external data processor or of an extension had no way to its metadata object either.

DISK FIRST: what a write leaves is read from the owner's .mdo, parsed, and looked up in the <forms>
entry of that form; Form.form must stay untouched by a metadata-object write (and the reverse for a
content-root write). The answer is asserted after the file. The fixture configuration's language is
English, so a synonym is keyed by the language CODE `en`.

Every test name contains "form_object", so `--filter form_object` selects the set.
Cyrillic is written as escapes so a non-UTF-8 checkout cannot corrupt it.
"""

import xml.etree.ElementTree as ET

from harness import (
    EXT_OBJECTS_PROJECT,
    EXT_OBJECTS_REL,
    PROJECT,
    TESTS_PROJECT,
    TESTS_PROJECT_REL,
    assert_contains,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    assert_no_diff_rel,
    assert_not_contains,
    assert_ok,
    call,
    e2e_test,
    poll_disk_contains_all,
    read_disk,
    read_fixture_file,
    reset_fixture_rel,
    restore_extension_fixture,
    status_porcelain_rel,
    PROJECT_REL,
)

FORM_FQN = "Catalog.Catalog.Form.ItemForm"
# "Spravochnik" / "Forma" - the Russian type and form tokens.
RU_CATALOG = "Справочник"
RU_FORM = "Форма"
OWNER_MDO = "src/Catalogs/Catalog/Catalog.mdo"
FORM_FORM = "src/Catalogs/Catalog/Forms/ItemForm/Form.form"
EXT_PROC_MDO = "src/ExternalDataProcessors/ExtProc/ExtProc.mdo"
DISK_TIMEOUT = 30


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _form_entry(mdo_text, form_name):
    """The <forms> entry of one form in an owner's .mdo (direct children of the root only)."""
    root = ET.fromstring(mdo_text)
    for child in list(root):
        if _local(child.tag) == "forms":
            names = [c.text for c in list(child) if _local(c.tag) == "name"]
            if names == [form_name]:
                return child
    raise AssertionError("no <forms> entry named %s in the owner's .mdo:\n%s" % (form_name, mdo_text[:800]))


def _synonym(entry):
    """The entry's synonym as {language code: text}."""
    result = {}
    for syn in [c for c in list(entry) if _local(c.tag) == "synonym"]:
        key = [c.text for c in list(syn) if _local(c.tag) == "key"]
        value = [c.text for c in list(syn) if _local(c.tag) == "value"]
        if key and value:
            result[key[0]] = value[0]
    return result


def _comment(entry):
    found = [c.text for c in list(entry) if _local(c.tag) == "comment"]
    return found[0] if found else None


def _changed(rel):
    """The repo-relative paths git reports as changed under one fixture path."""
    return {line[3:].strip() for line in status_porcelain_rel(rel).splitlines() if line.strip()}


def _assignable(project, fqn):
    r = call("get_metadata_details", {"projectName": project, "objectFqns": [fqn], "assignable": True})
    assert_ok(r, "assignable view of " + fqn)
    return r.text


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_object_synonym_lands_in_the_owner_mdo():
    value = "E2E995 item form"
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": FORM_FQN,
        "properties": [{"name": "synonym", "value": value, "language": "en"}],
    })
    # DISK FIRST: the synonym lives in the owner's <forms> entry, keyed by the language CODE.
    # Read right after the call: modify_metadata answers after its own export reached the disk.
    text = read_disk(OWNER_MDO)
    assert value in text, "the form's synonym must be in the owner's .mdo when the call returns"
    assert _synonym(_form_entry(text, "ItemForm")).get("en") == value, \
        "the synonym must be the ItemForm entry's, keyed by 'en': %r" % (_synonym(_form_entry(text, "ItemForm")),)
    changed = _changed(PROJECT_REL)
    assert PROJECT_REL + "/" + FORM_FORM not in changed, \
        "a metadata-object write must leave Form.form untouched: %s" % sorted(changed)
    assert_ok(r, "set the synonym of an owned form")
    assert r.structured.get("fqn") == FORM_FQN, "the write echoes the form FQN: %r" % (r.structured,)
    assert "synonym" in (r.structured.get("applied") or []), r.structured
    assert r.structured.get("language") == "en", "the language CODE is reported: %r" % (r.structured,)

    view = _assignable(PROJECT, FORM_FQN)
    content_at = view.find("## Form content root assignable properties: ")
    row = next((l for l in view.splitlines() if l.startswith("| synonym |")), None)
    assert row is not None and content_at != -1 and view.index(row) < content_at, \
        "the synonym row belongs to the metadata object's table: %s" % view[:1500]
    assert_contains(row, value, "the assignable view reads the written synonym back")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_object_comment_by_a_russian_address():
    value = "E2E995 comment"
    fqn = RU_CATALOG + ".Catalog." + RU_FORM + ".ItemForm"
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn, "properties": [{"name": "comment", "value": value}],
    })
    text = read_disk(OWNER_MDO)
    assert value in text, "a Russian-token address writes the same form's comment, on disk when the call returns"
    assert _comment(_form_entry(text, "ItemForm")) == value, "the comment is the ItemForm entry's"
    assert_ok(r, "set the comment of an owned form through Russian tokens")
    assert PROJECT_REL + "/" + FORM_FORM not in _changed(PROJECT_REL), "Form.form stays untouched"


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_object_title_alone_still_lands_in_form_form():
    """The control (#549): a call with no metadata-object property is the content root's, as before."""
    title = "E2E995 root title"
    before = read_disk(OWNER_MDO)
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": FORM_FQN,
        "properties": [{"name": "title", "value": title, "language": "en"}],
    })
    assert title in read_disk(FORM_FORM), "a content-root title is in Form.form when the call returns"
    assert_ok(r, "set the content root's title")
    assert read_disk(OWNER_MDO) == before, "a content-root write must leave the owner's .mdo as it was"


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_object_mixed_batch_is_refused_and_writes_nothing():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": FORM_FQN,
        "properties": [{"name": "synonym", "value": "E2E995 mixed", "language": "en"},
                       {"name": "title", "value": "E2E995 mixed title", "language": "en"}],
    })
    e = assert_error(r, "a batch that mixes the metadata object and the content root")
    assert_error_quality(e, names=[FORM_FQN, "synonym", "title"],
                         suggests=["Properties of the metadata object: synonym",
                                   "not properties of it: title", "separate call", "Nothing was changed"],
                         ctx="the refusal names the split and the way out")
    assert_no_diff("a refused mixed batch must change nothing")


@e2e_test(tool="get_metadata_details", kind="read")
def test_form_object_assignable_lists_the_metadata_object_then_the_content_root():
    view = _assignable(PROJECT, FORM_FQN)
    head = "## Assignable properties: " + FORM_FQN
    content = "## Form content root assignable properties: " + FORM_FQN
    assert_contains(view, head, "the metadata object's table keeps the plain heading")
    assert_contains(view, content, "the content root's table has its own heading")
    split = view.index(content)
    assert view.index(head) < split, "the metadata object's table comes first"
    metadata, root = view[:split], view[split:]
    for name in ("synonym", "comment", "usePurposes"):
        assert_contains(metadata, "| %s |" % name, "the metadata object offers " + name)
        assert_not_contains(root, "| %s |" % name, "the content root does not offer " + name)
    # modify_metadata refuses an owned form's formType, so the table does not offer it (review of #1005).
    assert_not_contains(metadata, "| formType |", "the table offers only what the write accepts")
    for name in ("title", "autoTitle"):
        assert_contains(root, "| %s |" % name, "the content root offers " + name)
        assert_not_contains(metadata, "| %s |" % name, "the metadata object does not offer " + name)
    assert_not_contains(view, "## Errors", "a valid form renders both tables")
    assert_no_diff("reading the assignable view is read-only")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_object_misspelt_root_property_names_the_metadata_object():
    bad = "definitelyNotAFormProp_zz995"
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": FORM_FQN, "properties": [{"name": bad, "value": True}],
    })
    e = assert_error(r, "a name neither layer of the form has")
    assert_error_quality(e, names=[bad],
                         suggests=["not assignable", "autoTitle", "form's own metadata properties",
                                   "assignable:true on the same FQN"],
                         ctx="the content-root refusal also points at the form's metadata object")
    assert_no_diff("a refused property must change nothing")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_object_of_an_external_data_processor():
    reset_fixture_rel(EXT_OBJECTS_REL)
    value = "E2E995 main form"
    try:
        r = call("modify_metadata", {
            "projectName": EXT_OBJECTS_PROJECT, "fqn": "ExternalDataProcessor.ExtProc.Form.MainForm",
            "properties": [{"name": "synonym", "value": value, "language": "en"}],
        })
        root = EXT_OBJECTS_REL
        text = poll_disk_contains_all(EXT_PROC_MDO, [value], timeout=DISK_TIMEOUT, fixture_rel=root,
                                      ctx="the external form's synonym reaches the external .mdo")
        assert _synonym(_form_entry(text, "MainForm")).get("en") == value, "the MainForm entry's synonym"
        assert_ok(r, "set the synonym of an external data processor's form")
    finally:
        reset_fixture_rel(EXT_OBJECTS_REL)
        call("clean_project", {"projectName": EXT_OBJECTS_PROJECT})
    assert_no_diff_rel(EXT_OBJECTS_REL, "the external-objects fixture is back at its baseline")
    assert_no_diff("the base project is never touched by this test")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_form_object_of_an_adopted_form_in_the_extension():
    """The extension adopts Catalog.Catalog and its ItemForm. A synonym of the adopted form has no
    Extended state, so EDT would leave it out of the extension's .mdo: refused, as for any adopted object
    (#709). Its comment has no state and is written - into the extension's own .mdo entry of the form."""
    try:
        r = call("modify_metadata", {
            "projectName": TESTS_PROJECT, "fqn": FORM_FQN,
            "properties": [{"name": "synonym", "value": "E2E995 adopted", "language": "en"}],
        })
        e = assert_error(r, "the synonym of an adopted form")
        assert_error_quality(e, names=["synonym", "adopted"], suggests=["None"],
                             ctx="the adopted-object refusal, not the content root's")
        assert_no_diff_rel(TESTS_PROJECT_REL, "the refused synonym writes nothing")

        value = "E2E995 adopted comment"
        r = call("modify_metadata", {
            "projectName": TESTS_PROJECT, "fqn": FORM_FQN, "properties": [{"name": "comment", "value": value}],
        })
        text = poll_disk_contains_all(OWNER_MDO, [value], timeout=DISK_TIMEOUT, fixture_rel=TESTS_PROJECT_REL,
                                      ctx="the adopted form's comment reaches the extension's .mdo")
        entry = _form_entry(text, "ItemForm")
        assert _comment(entry) == value, "the comment is the adopted ItemForm entry's"
        assert_ok(r, "set the comment of an adopted form")
        assert read_fixture_file(TESTS_PROJECT_REL, "src/Catalogs/Catalog/Forms/ItemForm/Form.form") \
            .count(value) == 0, "the extension's Form.form stays untouched"
    finally:
        restore_extension_fixture("an adopted form's metadata-object write")
