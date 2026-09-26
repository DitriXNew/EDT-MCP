"""
e2e tests for a section's COMMAND INTERFACE (#666): get_metadata_details renders it, and
modify_metadata's commands[] payload changes command visibility (common and per role), placement
into a panel group and order within a group.

    get_metadata_details(objectFqns=["Subsystem.X.CommandInterface"])
    modify_metadata(fqn="Subsystem.X.CommandInterface",
                    commands=[{command, visible?, roles?, group?, after? | before?}])

The section is EDT's COMPUTED command interface: it lists the commands of the subsystem's content
objects and is recomputed asynchronously after a change, so a read that follows a write polls until
the expected state shows. A write runs EDT's own customization tasks in one BM write transaction and
exports the section's CommandInterface.cmi - asserted structurally on disk below, fragment by
fragment, in the platform's own serialization.

FIXTURE: Subsystem.Subsystem (included in the command interface, empty content, an empty
CommandInterface.cmi) and Catalog.Catalog. A write test first adds Catalog.Catalog to the subsystem's
content, which puts the catalog's OpenList (NavigationPanelOrdinary, visible) and Create
(ActionsPanelCreate, hidden by default) standard commands into the section.

reset: kind="write-metadata" -> reset_fixture()+reset_model() after each test.
"""

import re
import time

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_tree_unchanged,
    tree_snapshot,
    poll_disk_contains_all,
    split_markdown_row,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)


SECTION = "Subsystem.Subsystem.CommandInterface"
CMI = "src/Subsystems/Subsystem/CommandInterface.cmi"
OPEN_LIST = "Catalog.Catalog.StandardCommand.OpenList"
CREATE = "Catalog.Catalog.StandardCommand.Create"
ROLE = "E2ECiRole"


def _read(fqns, **extra):
    args = {"projectName": PROJECT, "objectFqns": fqns}
    args.update(extra)
    r = call("get_metadata_details", args)
    assert_ok(r, "read %s" % fqns)
    return r.text or ""


def _groups(text):
    """{group id: (heading line, [(command, visible, roles, customized), ...])} of a rendered section."""
    groups = {}
    current = None
    for line in text.splitlines():
        if line.startswith("### "):
            current = line[4:].split(" ", 1)[0]
            groups[current] = (line, [])
            continue
        if current is None:
            continue
        cells = split_markdown_row(line)
        if len(cells) == 5 and cells[0].isdigit():
            groups[current][1].append(tuple(cells[1:]))
    return groups


def _poll_section(predicate, what, timeout=60):
    """Re-read the computed section until predicate(groups) holds: EDT recomputes it asynchronously."""
    deadline = time.time() + timeout
    text = ""
    while time.time() < deadline:
        text = _read([SECTION])
        if predicate(_groups(text)):
            return text
        time.sleep(1.0)
    raise AssertionError("%s did not show within %ss:\n%s" % (what, timeout, text[:2000]))


def _commands_of(groups, group_id):
    return [row[0] for row in groups.get(group_id, ("", []))[1]]


def _row(groups, command):
    for _, rows in groups.values():
        for row in rows:
            if row[0] == command:
                return row
    return None


def _seed_catalog_in_section():
    r = call("modify_metadata", {"projectName": PROJECT, "fqn": "Subsystem.Subsystem",
                                 "content": [{"op": "add", "metadata": "Catalog.Catalog"}]})
    assert_ok(r, "add Catalog.Catalog to the subsystem content")
    wait_for_project_ready()
    return _poll_section(lambda g: OPEN_LIST in _commands_of(g, "NavigationPanelOrdinary")
                         and CREATE in _commands_of(g, "ActionsPanelCreate"),
                         "the catalog's commands in the section")


def _modify(commands, fqn=SECTION):
    return call("modify_metadata", {"projectName": PROJECT, "fqn": fqn, "commands": commands})


def _structured(r, ctx):
    assert r.structured is not None, "%s: JSON tool must return structuredContent: %r" % (ctx, r.raw)
    return r.structured


# ══════════════════════════════════════════════════════════════════════════════
# Read — the computed section, by panel group, in both languages
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_command_interface_read_lists_panel_groups_and_content_commands():
    text = _seed_catalog_in_section()
    assert_contains(text, "## Command interface: Subsystem.Subsystem", "the section heading")
    groups = _groups(text)
    for group in ("NavigationPanelImportant", "NavigationPanelOrdinary", "NavigationPanelSeeAlso",
                  "ActionsPanelCreate", "ActionsPanelReports", "ActionsPanelTools"):
        assert group in groups, "every standard panel group must render, missing %s:\n%s" % (group, text[:1500])
    assert "(ПанельНавигацииОбычное) - navigation panel, order: automatic" in groups["NavigationPanelOrdinary"][0], \
        "the heading names the Russian id, the panel and the order mode: %r" % (groups["NavigationPanelOrdinary"][0],)
    assert _row(groups, OPEN_LIST) == (OPEN_LIST, "yes", "-", "-"), \
        "OpenList is visible and not customized by default: %r" % (_row(groups, OPEN_LIST),)
    assert _row(groups, CREATE) == (CREATE, "no", "-", "-"), \
        "Create is hidden by default in the actions panel: %r" % (_row(groups, CREATE),)

    # The Russian address reads the SAME section.
    ru = _read(["Подсистема.Subsystem.КомандныйИнтерфейс"])
    assert_contains(ru, "## Command interface: Subsystem.Subsystem", "the Russian tokens address the same section")
    assert _commands_of(_groups(ru), "NavigationPanelOrdinary") == [OPEN_LIST], ru[:1500]


@e2e_test(tool="get_metadata_details", kind="read")
def test_command_interface_read_main_section_and_refusals():
    text = _read(["Configuration.MainSectionCommandInterface", "Конфигурация.КомандныйИнтерфейсОсновногоРаздела",
                  "Configuration.CommandInterface", "Catalog.Catalog.CommandInterface",
                  "Subsystem.NoSuch.CommandInterface"])
    assert text.count("## Command interface: Configuration") == 2, \
        "the main section renders under both spellings:\n%s" % text[:1500]
    errors = text[text.find("## Errors"):]
    assert "## Errors" in text, text[:1500]
    assert_contains(errors, "is the sections panel", "Configuration.CommandInterface is refused with the reason")
    assert_contains(errors, "only a subsystem and the configuration have a section command interface",
                    "a catalog has no section - refused instead of rendering Catalog.Catalog")
    assert "Catalog: Catalog" not in text, \
        "a misaddressed command interface must not render its owner:\n%s" % text[:1500]
    assert_contains(errors, "Subsystem not found: Subsystem.NoSuch", "an unknown subsystem names itself")

    assignable = _read([SECTION], assignable=True)
    assert_contains(assignable, "a command interface has no assignable properties",
                    "assignable mode points at the commands payload instead")


# ══════════════════════════════════════════════════════════════════════════════
# Write — visibility (common + per role), Russian tokens, disk + model read-back
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_command_interface_visibility_and_role_override():
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Role." + ROLE})
    assert_ok(r, "seed a role")
    wait_for_project_ready()
    _seed_catalog_in_section()

    w = _modify([{"command": "Справочник.Catalog.СтандартнаяКоманда.ОткрытьСписок", "visible": False,
                  "roles": [{"role": "Роль." + ROLE, "visible": True}]}],
                fqn="Подсистема.Subsystem.КомандныйИнтерфейс")
    assert_ok(w, "hide OpenList for everyone but one role, addressed in Russian")
    s = _structured(w, "visibility write")
    assert s.get("action") == "modified" and s.get("fqn") == SECTION, s
    assert s.get("commands") == {"visibility": 1, "placement": 0, "order": 0}, s
    assert s.get("persisted") is True, "the change must be exported, not only committed: %r" % (s,)
    assert s["stored"]["commands"][OPEN_LIST] == {
        "visible": {"common": False, "roles": {"Role." + ROLE: True}}, "group": "default"}, s["stored"]

    # ON DISK: the platform's own fragment shape - common false is the omitted default, the role
    # override is a <for> element.
    disk = poll_disk_contains_all(CMI, ["<command>%s</command>" % OPEN_LIST, "<role>Role.%s</role>" % ROLE],
                                  ctx="the visibility fragment lands in the section's CommandInterface.cmi")
    fragment = re.search(r"<visibilityFragments>\s*<command>%s</command>(.*?)</visibilityFragments>"
                         % re.escape(OPEN_LIST), disk, re.S)
    assert fragment, disk
    assert "<common>true</common>" not in fragment.group(1), "common must be false: %s" % fragment.group(1)
    assert re.search(r"<for>\s*<value>true</value>\s*<role>Role\.%s</role>\s*</for>" % ROLE, fragment.group(1)), \
        fragment.group(1)

    # MODEL: the recomputed section shows it.
    _poll_section(lambda g: _row(g, OPEN_LIST) == (OPEN_LIST, "no", "Role.%s: yes" % ROLE, "visibility"),
                  "the hidden OpenList with its role override")

    # Dropping the override and restoring the default visibility removes the whole fragment.
    back = _modify([{"command": OPEN_LIST, "visible": True, "roles": [{"role": "Role." + ROLE, "visible": "default"}]}])
    assert_ok(back, "restore the default visibility")
    assert _structured(back, "restore")["stored"]["commands"][OPEN_LIST]["visible"] == "default", back.structured
    _poll_section(lambda g: _row(g, OPEN_LIST) == (OPEN_LIST, "yes", "-", "-"), "OpenList back to its defaults")


# ══════════════════════════════════════════════════════════════════════════════
# Write — placement into another panel group + order by anchor
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_command_interface_move_and_order():
    _seed_catalog_in_section()
    w = _modify([{"command": CREATE, "visible": True, "group": "NavigationPanelOrdinary", "before": OPEN_LIST}])
    assert_ok(w, "move Create into the navigation panel, before OpenList")
    s = _structured(w, "move write")
    assert s.get("commands") == {"visibility": 1, "placement": 1, "order": 1}, s
    assert s["stored"]["commands"][CREATE]["group"] == "NavigationPanelOrdinary", s["stored"]
    assert s["stored"]["order"] == {"NavigationPanelOrdinary": [CREATE, OPEN_LIST]}, s["stored"]

    disk = poll_disk_contains_all(CMI, ["<placementFragments>", "<orderFragments>"],
                                  ctx="placement and order land in CommandInterface.cmi")
    assert re.search(r"<placementFragments>\s*<group>NavigationPanelOrdinary</group>\s*<commands>%s</commands>"
                     % re.escape(CREATE), disk), disk
    assert re.search(r"<orderFragments>\s*<group>NavigationPanelOrdinary</group>\s*<commands>%s</commands>"
                     r"\s*<commands>%s</commands>\s*</orderFragments>" % (re.escape(CREATE), re.escape(OPEN_LIST)),
                     disk), disk

    text = _poll_section(lambda g: _commands_of(g, "NavigationPanelOrdinary") == [CREATE, OPEN_LIST]
                         and CREATE not in _commands_of(g, "ActionsPanelCreate"),
                         "Create first in NavigationPanelOrdinary")
    groups = _groups(text)
    assert "order: custom" in groups["NavigationPanelOrdinary"][0], groups["NavigationPanelOrdinary"][0]
    assert _row(groups, CREATE) == (CREATE, "yes", "-", "placement, visibility"), _row(groups, CREATE)

    # Moving it back to its default group (named in Russian) removes the stored placement.
    back = _modify([{"command": CREATE, "group": "ПанельДействийСоздать"}])
    assert_ok(back, "move Create back to its default group")
    assert _structured(back, "move back")["stored"]["commands"][CREATE]["group"] == "default", back.structured
    _poll_section(lambda g: CREATE in _commands_of(g, "ActionsPanelCreate"), "Create back in ActionsPanelCreate")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_command_interface_of_a_new_subsystem():
    # A subsystem created a moment ago has never had its section customized: the first write must
    # create the command interface object under the platform's own FQN and export it.
    name = "E2ECiFresh"
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Subsystem." + name})
    assert_ok(r, "seed a subsystem")
    wait_for_project_ready()
    r = call("modify_metadata", {"projectName": PROJECT, "fqn": "Subsystem." + name,
                                 "content": [{"op": "add", "metadata": "Catalog.Catalog"}]})
    assert_ok(r, "add Catalog.Catalog to the new subsystem")
    wait_for_project_ready()
    section = "Subsystem.%s.CommandInterface" % name
    deadline = time.time() + 60
    while OPEN_LIST not in _read([section]) and time.time() < deadline:
        time.sleep(1.0)

    w = _modify([{"command": OPEN_LIST, "group": "NavigationPanelImportant"}], fqn=section)
    assert_ok(w, "the first customization of a new section")
    s = _structured(w, "new section write")
    assert s.get("fqn") == section and s.get("persisted") is True, s
    assert s["stored"]["commands"][OPEN_LIST]["group"] == "NavigationPanelImportant", s["stored"]
    poll_disk_contains_all("src/Subsystems/%s/CommandInterface.cmi" % name,
                           ["<group>NavigationPanelImportant</group>", "<commands>%s</commands>" % OPEN_LIST],
                           ctx="the new section's CommandInterface.cmi carries the placement")


# ══════════════════════════════════════════════════════════════════════════════
# Batch — judged by its END state; a refusal anywhere writes nothing
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_command_interface_end_state_no_op_writes_nothing():
    _seed_catalog_in_section()
    before = tree_snapshot()
    r = _modify([{"command": OPEN_LIST, "visible": False}, {"command": OPEN_LIST, "visible": True}])
    assert_ok(r, "hide then show in one batch")
    s = _structured(r, "no-op batch")
    assert s.get("action") == "unchanged", "a batch whose END state is the current one changes nothing: %r" % (s,)
    assert s.get("unchanged") == [OPEN_LIST] and s.get("persisted") is False, s
    assert_tree_unchanged(before, "a no-op batch must not touch the disk")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_command_interface_refusals_write_nothing():
    _seed_catalog_in_section()
    before = tree_snapshot()

    err = assert_error(_modify([{"command": "CommonCommand.NoSuch", "visible": False}]), "unknown command")
    assert_error_quality(err, names=["CommonCommand.NoSuch"], suggests=["content", OPEN_LIST], ctx="unknown command")

    err = assert_error(_modify([{"command": CREATE, "group": "NoSuchGroup"}]), "unknown group")
    assert_error_quality(err, names=["NoSuchGroup"], suggests=["NavigationPanelImportant", "ПанельНавигацииВажное"],
                         ctx="unknown group")

    err = assert_error(_modify([{"command": CREATE, "after": OPEN_LIST}]), "anchor in another group")
    assert_error_quality(err, names=[OPEN_LIST, "ActionsPanelCreate"], suggests=["group:'NavigationPanelOrdinary'"],
                         ctx="anchor in another group")

    err = assert_error(_modify([{"command": OPEN_LIST, "roles": [{"role": "Role.NoSuch", "visible": False}]}]),
                       "unknown role")
    assert_error_quality(err, names=["Role.NoSuch"], ctx="unknown role")

    # A valid entry followed by a bad one: the whole batch is refused before anything is written.
    err = assert_error(_modify([{"command": OPEN_LIST, "visible": False},
                                {"command": CREATE, "group": "NoSuchGroup"}]), "half-valid batch")
    assert_contains(err, "commands[1].group", "the refusal names the failing entry")

    err = assert_error(call("modify_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog",
                                                "commands": [{"command": OPEN_LIST, "visible": False}]}),
                       "commands on a catalog")
    assert_error_quality(err, names=["Catalog.Catalog"], suggests=["Subsystem.<Name>.CommandInterface"],
                         ctx="commands on a catalog")

    err = assert_error(call("modify_metadata", {"projectName": PROJECT, "fqn": SECTION,
                                                "properties": [{"name": "comment", "value": "x"}]}),
                       "properties on a command interface")
    assert_contains(err, "'commands'", "properties on a command interface point at the commands payload")

    err = assert_error(_modify([{"command": OPEN_LIST, "visible": False}], fqn="Configuration.CommandInterface"),
                       "the sections panel")
    assert_contains(err, "sections panel", "the sections panel is out of scope, said so")

    assert_tree_unchanged(before, "no refusal may write anything")
