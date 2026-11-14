"""E2E: a subsystem's own properties through MCP, at creation and afterwards (issues #929, #930).

#930: create_metadata for a Subsystem (top-level or nested, either language) applies the properties
modify_metadata sets on a subsystem - validated by modify_metadata's own rules, written in the creating
transaction - so a new subsystem can be created already out of the command interface. Every other kind
keeps refusing such a property by name, a 'properties' argument that is not an array of objects is
refused (it used to be read as "no properties" and dropped), and what the create decides itself (Name,
parent, uuid, belonging) is refused.

#929: get_metadata_details(assignable:true) - the view of what modify_metadata sets - reads a NESTED
subsystem by its chain (it answered "Object not found"), and the short address 'Subsystem.<Child>' of a
subsystem that exists only nested names its whole chain instead of a bare "Node not found".

Every write is checked on disk, in the subsystem's own .mdo: a fresh subsystem stores
<includeInCommandInterface>true</...> and <includeHelpInContents>true</...>; false is the EMF default,
so a flag set to false is NOT serialized - its element must be absent.
"""

import os

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    poll_disk_contains,
    poll_disk_contains_all,
    wait_for_project_ready,
    restore_extension_fixture,
    e2e_test,
    E2ECallTimeout,
    PROJECT,
    PROJECT_DIR,
    TESTS_PROJECT,
    TESTS_PROJECT_REL,
)

RU_SUBSYSTEM = "Подсистема"
PARENT = "Subsystem"  # the fixture's top-level subsystem
COMMAND_FLAG = "<includeInCommandInterface>true</includeInCommandInterface>"
HELP_FLAG = "<includeHelpInContents>true</includeHelpInContents>"


def _top_mdo(name):
    return "src/Subsystems/%s/%s.mdo" % (name, name)


def _nested_mdo(parent, child):
    return "src/Subsystems/%s/Subsystems/%s/%s.mdo" % (parent, child, child)


def _disk_text(rel, root=PROJECT_DIR):
    with open(os.path.join(root, *rel.split("/")), encoding="utf-8", errors="replace") as f:
        return f.read()


def _flag(name, value):
    return {"name": name, "value": value}


# ══════════════════════════════════════════════════════════════════════════════
# #930 - create_metadata applies a subsystem's properties
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_subsys_930_create_nested_subsystem_out_of_the_command_interface():
    child = "E2E930Child"
    fqn = "Subsystem.%s.Subsystem.%s" % (PARENT, child)
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "properties": [_flag("includeInCommandInterface", False), _flag("includeHelpInContents", False),
                       {"name": "synonym", "value": "E2E 930 child", "language": "en"}],
    })
    assert_ok(r, "create a nested subsystem with its command-interface flag off")
    assert r.structured.get("fqn") == fqn, "the reply names the canonical chain: %r" % (r.structured,)
    assert r.structured.get("persisted") is True, "the create must persist: %r" % (r.structured,)
    assert_contains(r.structured.get("message"), "includeInCommandInterface",
                    "the reply names the property it set")
    mdo = poll_disk_contains(_nested_mdo(PARENT, child), "<name>%s</name>" % child,
                             ctx="the nested subsystem lands in its own .mdo")
    assert_not_contains(mdo, COMMAND_FLAG, "includeInCommandInterface=false is written at creation")
    assert_not_contains(mdo, HELP_FLAG, "includeHelpInContents=false is written at creation")
    assert_contains(mdo, "E2E 930 child", "the synonym is still written by the create itself")
    wait_for_project_ready()
    model = call("get_subsystem_content", {"projectName": PROJECT, "subsystemFqn": fqn})
    assert_ok(model, "read the new subsystem back from the model")
    assert_contains(model.text, "Include In Command Interface | No", "the model holds the flag, not only the file")
    assert_contains(_disk_text(_top_mdo(PARENT)), COMMAND_FLAG, "the PARENT keeps its own flag")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_subsys_930_create_top_level_subsystem_by_russian_token():
    name = "E2E930Top"
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": RU_SUBSYSTEM + "." + name,
        "properties": [_flag("includeInCommandInterface", False), _flag("useOneCommand", True)],
    })
    assert_ok(r, "create a top-level subsystem with Russian type token and two flags")
    mdo = poll_disk_contains(_top_mdo(name), "<name>%s</name>" % name,
                             ctx="the top-level subsystem lands in its own .mdo")
    assert_not_contains(mdo, COMMAND_FLAG, "includeInCommandInterface=false is written at creation")
    assert_contains(mdo, "<useOneCommand>true</useOneCommand>", "useOneCommand=true is written at creation")


@e2e_test(tool="create_metadata", kind="write")
def test_subsys_930_create_refuses_what_it_decides_and_what_modify_refuses():
    fqn = "Subsystem.%s.Subsystem.E2E930Refused" % PARENT
    cases = (
        ([_flag("parentSubsystem", "Subsystem.Subsystem")], ["create_metadata decides", "Nothing was created"]),
        ([_flag("noSuchProperty", True)], ["'noSuchProperty' is not assignable on Subsystem",
                                           "includeInCommandInterface"]),
        ([_flag("includeInCommandInterface", "maybe")], ["includeInCommandInterface"]),
    )
    for properties, suggests in cases:
        r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn, "properties": properties})
        e = assert_error(r, "a refused subsystem property %r" % (properties,))
        assert_error_quality(e, names=[properties[0]["name"]], suggests=suggests,
                             ctx="the refusal names the property and why")
    assert_no_diff("a refused create must not touch the project")


@e2e_test(tool="create_metadata", kind="write")
def test_subsys_930_properties_of_another_shape_are_refused_not_dropped():
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Subsystem.%s.Subsystem.E2E930Object" % PARENT,
        "properties": {"includeInCommandInterface": False},
    })
    e = assert_error(r, "an object instead of an array of properties")
    assert_error_quality(e, names=["properties"],
                         suggests=["must be an array of {name, value} objects", "Nothing was created"],
                         ctx="the refusal says what shape is expected")
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Subsystem." + PARENT,
        "properties": {"includeInCommandInterface": False},
    })
    e = assert_error(r, "modify: an object instead of an array of properties")
    assert_contains(e, "must be an array of {name, value} objects", "not the misleading 'properties is required'")
    assert_not_contains(e, "properties is required", "the argument WAS given")
    assert_no_diff("a refused argument must not touch the project")


@e2e_test(tool="create_metadata", kind="write")
def test_subsys_930_other_kinds_still_refuse_a_modify_property_by_name():
    r = call("create_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.E2E930Catalog",
        "properties": [_flag("includeInCommandInterface", False)],
    })
    e = assert_error(r, "a catalog does not take a subsystem property at creation")
    assert_error_quality(e, names=["includeInCommandInterface"],
                         suggests=["not supported yet in create_metadata", "modify_metadata"],
                         ctx="other kinds keep refusing by name")
    assert_no_diff("a refused create must not touch the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_subsys_930_create_extension_own_nested_subsystem_applies_the_flag():
    top, child = "tests_E2E930Top", "tests_E2E930Child"
    try:
        assert_ok(call("create_metadata", {"projectName": TESTS_PROJECT, "fqn": "Subsystem." + top}),
                  "seed a subsystem the extension owns")
        wait_for_project_ready()
        r = call("create_metadata", {
            "projectName": TESTS_PROJECT, "fqn": "Subsystem.%s.Subsystem.%s" % (top, child),
            "properties": [_flag("includeInCommandInterface", False)],
        })
        assert_ok(r, "create a nested subsystem in the extension with its flag off")
        mdo = poll_disk_contains_all(_nested_mdo(top, child), ["<name>%s</name>" % child],
                                     ctx="the extension's nested subsystem lands in its own .mdo",
                                     fixture_rel=TESTS_PROJECT_REL)
        assert_not_contains(mdo, COMMAND_FLAG, "the flag is written in the extension too")
    except E2ECallTimeout:
        raise
    except BaseException:
        restore_extension_fixture("creating an extension-own nested subsystem with properties")
        raise
    else:
        restore_extension_fixture("creating an extension-own nested subsystem with properties")


# ══════════════════════════════════════════════════════════════════════════════
# #929 - reading and addressing a nested subsystem's properties
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_subsys_929_assignable_view_reads_a_nested_subsystem():
    child = "E2E929Assignable"
    fqn = "Subsystem.%s.Subsystem.%s" % (PARENT, child)
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": fqn}), "seed the nested subsystem")
    poll_disk_contains(_nested_mdo(PARENT, child), COMMAND_FLAG, ctx="setup: a fresh subsystem is in the interface")
    wait_for_project_ready()
    assert_ok(call("modify_metadata", {"projectName": PROJECT, "fqn": fqn,
                                       "properties": [_flag("includeInCommandInterface", False)]}),
              "turn the child's flag off (the parent's stays on)")
    for address in (fqn, "%s.%s.%s.%s" % (RU_SUBSYSTEM, PARENT, RU_SUBSYSTEM, child)):
        r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [address], "assignable": True})
        assert_ok(r, "assignable view of " + address)
        assert_not_contains(r.text, "## Errors", "a nested chain renders, it is not 'Object not found'")
        assert_contains(r.text, "## Assignable properties: " + fqn, "named by its canonical chain")
        row = next((line for line in r.text.splitlines() if line.startswith("| includeInCommandInterface |")), None)
        assert row is not None, "the table lists includeInCommandInterface: %s" % r.text[:600]
        assert row.split("|")[3].strip() == "false", "Current is the CHILD's value, not the parent's: %r" % row


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_subsys_929_short_address_of_a_nested_subsystem_names_its_chain():
    child = "E2E929Short"
    chain = "Subsystem.%s.Subsystem.%s" % (PARENT, child)
    assert_ok(call("create_metadata", {"projectName": PROJECT, "fqn": chain}), "seed the nested subsystem")
    wait_for_project_ready()
    r = call("modify_metadata", {"projectName": PROJECT, "fqn": "Subsystem." + child,
                                 "properties": [_flag("includeInCommandInterface", False)]})
    e = assert_error(r, "the short address is not guessed")
    assert_error_quality(e, names=["Subsystem." + child], suggests=[chain, "whole chain"],
                         ctx="the refusal names the chain that does exist")
    r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": ["Subsystem." + child]})
    assert_ok(r, "details answers with an error row")
    assert_contains(r.text, chain, "the details row names the chain too")
    assert_contains(_disk_text(_nested_mdo(PARENT, child)), COMMAND_FLAG,
                    "the refused short address changed nothing")
