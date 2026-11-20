"""
e2e tests for rename_metadata_object on object MEMBERS beyond attributes (issue #994): a command, an
owned form, a template and an enum value.

The rename used to resolve its target through a private resolver that knew four child kinds
(Attribute, TabularSection, Dimension, Resource) and answered "Object not found" for any other one.
It now resolves through the shared MetadataNodeResolver, and EDT's createMdObjectRenameRefactoring -
the call EDT's own Name editor makes for every metadata object - does the rest. These tests prove the
CASCADE on disk for each kind: preview first (nothing changes), then confirm, then the files.

Every object is created by the test with an E2E994 prefix, so the cascade cannot reach a fixture
object by a common name. kind="write-metadata" -> the harness resets the fixture and the model after
each test.
"""

import os

from harness import (
    rename_call,
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    assert_tree_unchanged,
    tree_snapshot,
    poll_disk_contains,
    poll_disk_contains_all,
    poll_disk_lacks,
    poll_disk_path_gone,
    read_disk,
    settle_or_fail,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    PROJECT_DIR,
)


PROC = "E2E994Proc"
CMD = "E2E994Open"
CMD_NEW = "E2E994Launch"
SUB = "E2E994Sub"
ROLE = "E2E994Role"
CAT = "E2E994Cat"
FORM = "E2E994Form"
FORM_NEW = "E2E994Card"
TPL = "E2E994Tpl"
TPL_NEW = "E2E994Layout"
ENUM = "E2E994Enum"
VAL = "E2E994Val"
VAL_NEW = "E2E994Choice"
USER_MODULE = "E2E994User"


def _ok(tool, args, ctx):
    r = call(tool, args)
    assert_ok(r, ctx)
    return r


def _create(fqn, **extra):
    args = {"projectName": PROJECT, "fqn": fqn}
    args.update(extra)
    _ok("create_metadata", args, "seed " + fqn)
    wait_for_project_ready()


def _write_module(source, **address):
    args = {"projectName": PROJECT, "source": source, "mode": "replace"}
    args.update(address)
    _ok("write_module_source", args, "seed module %r" % (address,))


def _exists(rel):
    return os.path.exists(os.path.join(PROJECT_DIR, *rel.split("/")))


def _poll_exists(rel, ctx, timeout=30):
    poll_disk_contains_all(rel, [], timeout=timeout, ctx=ctx)

def _owner_lists_only(owner_fqn, new_name, old_name):
    """MODEL read-back: the owner's details list the member under its new name only."""
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner_fqn]})
    assert_ok(d, "read %s back" % owner_fqn)
    assert_contains(d.text, new_name, "the model lists the renamed member of %s" % owner_fqn)
    assert_not_contains(d.text, old_name, "the model no longer lists the old member name of %s" % owner_fqn)


def _preview_then_confirm(fqn, new_name, preview_needles=()):
    """Preview (must change nothing and list the given needles), then confirm."""
    settle_or_fail("a member rename (it waits for the derived-data drain inside its own budget)")
    before = tree_snapshot()
    p = rename_call({"projectName": PROJECT, "objectFqn": fqn, "newName": new_name, "maxResults": 0})
    assert_ok(p, "preview rename of %s" % fqn)
    assert_contains(p.text, "action: preview", "the preview contract for a member")
    assert_contains(p.text, "may not be listed as rows",
                    "the preview says EDT's metadata reference updates are not all rows")
    for needle in preview_needles:
        assert_contains(p.text, needle, "the preview of %s must list the change point in %s" % (fqn, needle))
    assert_tree_unchanged(before, "a preview (confirm=false) must not change the project on disk")
    r = rename_call({"projectName": PROJECT, "objectFqn": fqn, "newName": new_name, "confirm": True})
    assert_ok(r, "confirm rename of %s" % fqn)
    assert_contains(r.text, "action: executed", "execute mode for a member")
    return p.text, r.text


# ──────────────────────────────────────────────────────────────────────────────
# Command: its folder and module move; the section's command interface and role rights follow
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_data_processor_command_cascades_to_command_interface_and_rights():
    old_fqn = "DataProcessor.%s.Command.%s" % (PROC, CMD)
    new_fqn = "DataProcessor.%s.Command.%s" % (PROC, CMD_NEW)
    _create("DataProcessor." + PROC)
    _create(old_fqn)
    _ok("modify_metadata", {"projectName": PROJECT, "fqn": old_fqn,
                            "properties": [{"name": "group", "value": "NavigationPanelOrdinary"}]},
        "put the command into the navigation panel")
    _write_module("&НаКлиенте\nПроцедура ОбработкаКоманды(ПараметрКоманды, ПараметрыВыполненияКоманды)\n"
                  "\t// E2E994 command body\nКонецПроцедуры\n",
                  objectName="DataProcessor." + PROC, moduleType="CommandModule", commandName=CMD)
    _create("Subsystem." + SUB)
    _ok("modify_metadata", {"projectName": PROJECT, "fqn": "Subsystem." + SUB,
                            "content": [{"op": "add", "metadata": "DataProcessor." + PROC}]},
        "put the data processor into the subsystem")
    wait_for_project_ready()
    _ok("modify_metadata", {"projectName": PROJECT, "fqn": "Subsystem.%s.CommandInterface" % SUB,
                            "commands": [{"command": old_fqn, "group": "NavigationPanelImportant"}]},
        "customize the command in the section's command interface")
    cmi = "src/Subsystems/%s/CommandInterface.cmi" % SUB
    poll_disk_contains(cmi, "<commands>%s</commands>" % old_fqn, timeout=30,
                       ctx="the section's CommandInterface.cmi names the command")
    _create("Role." + ROLE)
    _ok("modify_metadata", {"projectName": PROJECT, "fqn": "Role." + ROLE,
                            "rights": [{"object": old_fqn, "right": "View", "value": "set"}]},
        "grant View on the command")
    rights = "src/Roles/%s/Rights.rights" % ROLE
    poll_disk_contains(rights, "<name>%s</name>" % old_fqn, timeout=30, ctx="the role's rights name the command")

    _preview_then_confirm(old_fqn, CMD_NEW)

    proc_dir = "src/DataProcessors/%s" % PROC
    poll_disk_contains("%s/Commands/%s/CommandModule.bsl" % (proc_dir, CMD_NEW), "E2E994 command body",
                       timeout=30, ctx="the command module moves to the renamed command's folder")
    poll_disk_path_gone("%s/Commands/%s/CommandModule.bsl" % (proc_dir, CMD), timeout=30,
                        ctx="the old command folder's module is gone")
    mdo = poll_disk_contains("%s/%s.mdo" % (proc_dir, PROC), "<name>%s</name>" % CMD_NEW, timeout=30,
                             ctx="the data processor .mdo holds the new command name")
    assert "<name>%s</name>" % CMD not in mdo, "the old command name must be gone from the .mdo:\n%s" % mdo
    text = poll_disk_contains(cmi, "<commands>%s</commands>" % new_fqn, timeout=30,
                              ctx="the section's command interface follows the rename")
    assert old_fqn + "<" not in text, "the command interface must not name the old command:\n%s" % text
    text = poll_disk_contains(rights, "<name>%s</name>" % new_fqn, timeout=30,
                              ctx="the role's rights follow the rename")
    assert "<name>%s</name>" % old_fqn not in text, "the rights must not name the old command:\n%s" % text

    # MODEL: the owner lists the command under its new name only (get_metadata_details does not render a
    # command member on its own address).
    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": ["DataProcessor." + PROC]})
    assert_ok(d, "read the data processor back")
    assert_contains(d.text, CMD_NEW, "the model lists the renamed command")
    assert_not_contains(d.text, CMD, "the model no longer lists the old command name")


# ──────────────────────────────────────────────────────────────────────────────
# Form object: folder, Form.form and module move; the owner's default-form property follows
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_owned_form_moves_its_files_and_the_default_form_reference():
    old_fqn = "Catalog.%s.Form.%s" % (CAT, FORM)
    new_fqn = "Catalog.%s.Form.%s" % (CAT, FORM_NEW)
    _create("Catalog." + CAT)
    _create(old_fqn, setAsDefault=True)
    _write_module("&НаКлиенте\nПроцедура E2E994FormProc()\n\t// E2E994 form body\nКонецПроцедуры\n",
                  objectName="Catalog." + CAT, moduleType="FormModule", formName=FORM)
    cat_dir = "src/Catalogs/%s" % CAT
    poll_disk_contains("%s/%s.mdo" % (cat_dir, CAT), "<defaultObjectForm>%s</defaultObjectForm>" % old_fqn,
                       timeout=30, ctx="setup: the form is the catalog's default object form")

    _preview_then_confirm(old_fqn, FORM_NEW)

    poll_disk_contains("%s/Forms/%s/Module.bsl" % (cat_dir, FORM_NEW), "E2E994 form body", timeout=30,
                       ctx="the form module moves to the renamed form's folder")
    _poll_exists("%s/Forms/%s/Form.form" % (cat_dir, FORM_NEW), "Form.form moves with the form")
    poll_disk_path_gone("%s/Forms/%s/Form.form" % (cat_dir, FORM), timeout=30, ctx="the old Form.form is gone")
    poll_disk_path_gone("%s/Forms/%s/Module.bsl" % (cat_dir, FORM), timeout=30, ctx="the old form module is gone")
    mdo = poll_disk_contains("%s/%s.mdo" % (cat_dir, CAT),
                             "<defaultObjectForm>%s</defaultObjectForm>" % new_fqn, timeout=30,
                             ctx="the catalog's default object form follows the rename")
    assert "<name>%s</name>" % FORM not in mdo, "the old form name must be gone from the catalog .mdo:\n%s" % mdo

    d = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [new_fqn]})
    assert_ok(d, "the renamed form resolves")
    assert_not_contains(d.text, "## Errors", "the renamed form resolves by its new address")


# ──────────────────────────────────────────────────────────────────────────────
# Template: the folder moves with its content
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_template_moves_its_folder_with_the_content():
    old_fqn = "Catalog.%s.Template.%s" % (CAT, TPL)
    _create("Catalog." + CAT)
    _create(old_fqn)
    cat_dir = "src/Catalogs/%s" % CAT
    old_dir = os.path.join(PROJECT_DIR, *("%s/Templates/%s" % (cat_dir, TPL)).split("/"))
    content_before = {}
    if os.path.isdir(old_dir):
        for name in os.listdir(old_dir):
            with open(os.path.join(old_dir, name), "rb") as f:
                content_before[name] = f.read()
    assert content_before, "setup: a created template has its content file under %s" % old_dir

    _preview_then_confirm(old_fqn, TPL_NEW)

    mdo = poll_disk_contains("%s/%s.mdo" % (cat_dir, CAT), "<name>%s</name>" % TPL_NEW, timeout=30,
                             ctx="the catalog .mdo holds the new template name")
    assert "<name>%s</name>" % TPL not in mdo, "the old template name must be gone:\n%s" % mdo
    for name, data in content_before.items():
        rel = "%s/Templates/%s/%s" % (cat_dir, TPL_NEW, name)
        _poll_exists(rel, "the template content file %s moves with the template" % name)
        with open(os.path.join(PROJECT_DIR, *rel.split("/")), "rb") as f:
            assert f.read() == data, "the template content %s must be intact after the move" % name
        poll_disk_path_gone("%s/Templates/%s/%s" % (cat_dir, TPL, name), timeout=30,
                            ctx="the old template content file is gone")
    _owner_lists_only("Catalog." + CAT, TPL_NEW, TPL)


# ──────────────────────────────────────────────────────────────────────────────
# Enum value: a BSL reference to it is rewritten
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_enum_value_rewrites_its_bsl_reference():
    old_fqn = "Enum.%s.EnumValue.%s" % (ENUM, VAL)
    _create("Enum." + ENUM)
    _create(old_fqn)
    _create("CommonModule." + USER_MODULE)
    _write_module("Функция E2E994Value() Экспорт\n\tВозврат Перечисления.%s.%s;\nКонецФункции\n" % (ENUM, VAL),
                  modulePath="CommonModules/%s/Module.bsl" % USER_MODULE)

    _preview_then_confirm(old_fqn, VAL_NEW, preview_needles=("Перечисления.%s.%s" % (ENUM, VAL),))

    module = "src/CommonModules/%s/Module.bsl" % USER_MODULE
    text = poll_disk_contains(module, "Перечисления.%s.%s;" % (ENUM, VAL_NEW), timeout=30,
                              ctx="the BSL reference to the enum value is rewritten")
    assert "Перечисления.%s.%s;" % (ENUM, VAL) not in text, text
    mdo = poll_disk_contains("src/Enums/%s/%s.mdo" % (ENUM, ENUM), "<name>%s</name>" % VAL_NEW, timeout=30,
                             ctx="the enum .mdo holds the new value name")
    assert "<name>%s</name>" % VAL not in mdo, mdo
    _owner_lists_only("Enum." + ENUM, VAL_NEW, VAL)


# ──────────────────────────────────────────────────────────────────────────────
# Russian tokens address the same member
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_member_preview_accepts_russian_type_and_kind_tokens():
    _create("Enum." + ENUM)
    _create("Enum.%s.EnumValue.%s" % (ENUM, VAL))
    settle_or_fail("a member rename preview")
    before = tree_snapshot()
    p = rename_call({"projectName": PROJECT, "objectFqn": "Перечисление.%s.ЗначениеПеречисления.%s" % (ENUM, VAL),
                     "newName": VAL_NEW})
    assert_ok(p, "preview with Russian type and kind tokens")
    assert_contains(p.text, "action: preview", "the Russian address reaches EDT's rename")
    assert_tree_unchanged(before, "a preview must not change the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negatives: an unknown kind names the owner's kinds; an odd arity is refused, not read as the owner
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_unknown_member_kind_names_the_owners_kinds():
    bad = "Catalog.Catalog.Comand.X"
    r = rename_call({"projectName": PROJECT, "objectFqn": bad, "newName": "Whatever", "confirm": True})
    err = assert_error(r, "an unknown member kind")
    assert_error_quality(err, names=[bad], suggests=["not found", "member kinds", "Attribute", "Command"],
                         ctx="the refusal names the kinds the owner has")
    assert_not_contains(err, "Supported child types", "the four-kind list is gone")
    assert_no_diff("a refused rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_odd_arity_address_is_refused_not_read_as_its_owner():
    bad = "Catalog.Catalog.Attribute"
    r = rename_call({"projectName": PROJECT, "objectFqn": bad, "newName": "Goods"})
    err = assert_error(r, "an odd-arity address must be refused (it used to preview a rename of the catalog)")
    assert_error_quality(err, names=[bad], suggests=["odd number of segments"],
                         ctx="the refusal says what is missing")
    assert_no_diff("a refused rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_member_to_a_sibling_name_is_refused():
    _create("Enum." + ENUM)
    _create("Enum.%s.EnumValue.%s" % (ENUM, VAL))
    _create("Enum.%s.EnumValue.%s" % (ENUM, VAL_NEW))
    before = tree_snapshot()
    r = rename_call({"projectName": PROJECT, "objectFqn": "Enum.%s.EnumValue.%s" % (ENUM, VAL),
                     "newName": VAL_NEW.lower(), "confirm": True})
    err = assert_error(r, "a new name another value of the same enum already has")
    assert_error_quality(err, names=[VAL_NEW], suggests=["already exists", "Nothing was renamed"],
                         ctx="the clash names the existing member")
    assert_tree_unchanged(before, "a refused rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_rename_address_with_a_trailing_dot_is_refused():
    bad = "Catalog.Catalog."
    r = rename_call({"projectName": PROJECT, "objectFqn": bad, "newName": "Goods"})
    err = assert_error(r, "a trailing '.' must be refused (it used to preview a rename of the catalog)")
    assert_error_quality(err, names=["Catalog.Catalog"], suggests=["empty segment"],
                         ctx="the refusal says the address is malformed")
    assert_no_diff("a refused rename must not change the project on disk")