"""
e2e tests for the configuration ROOT address - issue #741 (kinds: read, write-metadata).

The root of a configuration or an extension is addressed as 'Configuration' (EDT's own top-object
FQN of it), the Russian token, or 'Configuration.<its Name>'. get_metadata_details(assignable:true)
lists the nine properties modify_metadata sets on it - synonym, comment, version, vendor, copyright,
briefInformation, detailedInformation, vendorInformationAddress, configurationInformationAddress -
and nothing else; the default view routes to get_configuration_properties. modify_metadata writes
exactly those nine, through the generic property path (one BM transaction, the export of the
'Configuration' top object), and refuses everything else BEFORE anything is written: the root's name,
its behaviour settings and collections, an extension's own settings, a payload other than
'properties', another configuration's Name, and an external-objects project (whose configuration is
the LINKED base, never its own root).

Before #741 every one of these was refused, and no refusal carries a positive token asserted here:
get_metadata_details answered "Invalid FQN. Expected format: Type.Name" for 'Configuration' and the
Russian token, and "Object not found - use get_metadata_objects" for 'Configuration.<Name>' (a valid
two-part shape with no such type); modify_metadata answered "Node not found: <the address>".

The extension test is the live proof that EDT's extension formatter keeps all nine on an ADOPTED root
(none has a property state there; the synonym's is exempt on a ConfigurationExtension), and that the
write leaves the root's <extension> block - its property states - exactly as it was.

Every test name contains "configuration_root", so `--filter configuration_root` selects the set.
Cyrillic is written as escapes so a non-UTF-8 checkout cannot corrupt it.
"""

import re

from harness import (
    E2ECallTimeout,
    E2ESkip,
    EXT_OBJECTS_PROJECT,
    EXT_OBJECTS_REL,
    PROJECT,
    TESTS_PROJECT,
    TESTS_PROJECT_REL,
    _fail,
    assert_contains,
    assert_error,
    assert_error_quality,
    assert_marker_contract,
    assert_no_diff,
    assert_no_diff_rel,
    assert_not_contains,
    assert_ok,
    call,
    e2e_test,
    poll_disk_contains_all,
    read_fixture_file,
    restore_extension_fixture,
)

# "Konfiguraciya" - the Russian token of the root.
RU_CONFIGURATION = "\u041a\u043e\u043d\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u044f"

ROOT_MDO = "src/Configuration/Configuration.mdo"
ROOT_HEADING = "## Assignable properties: Configuration\n"

# The nine, in the order the requests below send them (applied echoes request order).
NINE = ["synonym", "comment", "version", "vendor", "copyright", "briefInformation",
        "detailedInformation", "vendorInformationAddress", "configurationInformationAddress"]
LOCALIZED = {"synonym", "copyright", "briefInformation", "detailedInformation",
             "vendorInformationAddress", "configurationInformationAddress"}

# Properties of the root that must never be offered or written through MCP.
CLOSED_ROWS = ["| compatibilityMode |", "| catalogs |", "| scriptVariant |", "| name |", "| uuid |",
               "| defaultLanguage |", "| defaultRoles |"]
EXTENSION_ONLY_ROWS = ["| keepMappingToExtendedConfigurationObjectsByIDs |", "| namePrefix |",
                       "| configurationExtensionPurpose |", "| configurationExtensionCompatibilityMode |"]

_EXTENSION_BLOCK = re.compile(
    r'<extension xsi:type="mdclassExtension:ConfigurationExtension">(.*?)</extension>', re.S)


def _values(tag):
    """One unique ASCII value per property; the information addresses are URLs."""
    values = {}
    for name in NINE:
        if name.endswith("InformationAddress"):
            values[name] = "https://example.com/e2e741/%s/%s" % (tag, name)
        elif name == "version":
            values[name] = "7.4.1.%s" % (741 if tag == "base" else 742)
        else:
            values[name] = "E2E %s %s 741" % (tag, name)
    return values


def _properties(values):
    return [dict({"name": name, "value": values[name]}, **({"language": "en"} if name in LOCALIZED else {}))
            for name in NINE]


def _configuration_name(project):
    """The root's programmatic Name, read where the root is read (so the fixture can be renamed)."""
    r = call("get_configuration_properties", {"projectName": project})
    assert_ok(r, "read the root of %s" % project)
    m = re.search(r"^name: (.+)$", r.text or "", re.M)
    if not m:
        _fail("get_configuration_properties of %s names no root: %r" % (project, (r.text or "")[:300]))
    return m.group(1).strip().strip('"')


def _disk_needles(values):
    """What one read of the root's .mdo must hold after the nine were written."""
    needles = ["<version>%s</version>" % values["version"], "<vendor>%s</vendor>" % values["vendor"],
               "<comment>%s</comment>" % values["comment"]]
    needles += [values[name] for name in NINE if name in LOCALIZED]
    return needles


def _assert_localized_on_disk(text, values, ctx):
    """Each localized property is stored under its own element, keyed by the code it was written under."""
    for name in sorted(LOCALIZED):
        pattern = r"<%s>\s*<key>en</key>\s*<value>%s</value>" % (name, re.escape(values[name]))
        if not re.search(pattern, text):
            _fail("%s must be stored as <%s><key>en</key><value>%s</value> [%s]; the file holds:\n%s"
                  % (name, name, values[name], ctx, text[:900]))


def _assert_written(r, ctx):
    """The success of a write of the nine: the canonical address, what was applied, persisted."""
    assert_ok(r, ctx)
    s = r.structured if isinstance(r.structured, dict) else {}
    if s.get("fqn") != "Configuration":
        _fail("the root is echoed as 'Configuration' whatever address named it [%s]: %r" % (ctx, s))
    if s.get("applied") != NINE:
        _fail("applied must be the nine, in request order [%s]: %r" % (ctx, s.get("applied")))
    if s.get("persisted") is not True:
        _fail("the root's export must be accepted [%s]: %r" % (ctx, s))
    if s.get("language") != "en":
        _fail("every localized property was written under 'en' [%s]: %r" % (ctx, s.get("language")))
    assert_marker_contract(s, ctx)
    if s.get("markersIncompleteReason") == "objectUnresolved":
        _fail("the exported 'Configuration' top object must resolve [%s]: %r" % (ctx, s))


# ──────────────────────────────────────────────────────────────────────────────
# get_metadata_details
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="get_metadata_details", kind="read")
def test_configuration_root_assignable_lists_only_the_settable_nine():
    name = _configuration_name(PROJECT)
    r = call("get_metadata_details", {"projectName": PROJECT,
                                      "objectFqns": ["Configuration", RU_CONFIGURATION,
                                                     "Configuration." + name],
                                      "assignable": True})
    assert_ok(r, "the root's assignable view")
    assert_not_contains(r.text, "## Errors", "every form of the root address resolves")
    if r.text.count(ROOT_HEADING) != 3:
        _fail("each of the three addresses renders the canonical root heading: %r" % r.text[:900])
    for prop in NINE:
        assert_contains(r.text, "| %s |" % prop, "the root offers %s" % prop)
    for closed in CLOSED_ROWS:
        assert_not_contains(r.text, closed, "the root never offers %s" % closed)
    assert_contains(r.text, "get_configuration_properties also reads its name, behaviour settings and "
                    "languages.", "the view says what else reads the root, claiming no more than it reads")

    ext = call("get_metadata_details", {"projectName": TESTS_PROJECT, "objectFqns": ["Configuration"],
                                        "assignable": True})
    assert_ok(ext, "the extension root's assignable view")
    assert_contains(ext.text, ROOT_HEADING, "an extension's own root is addressable")
    assert_contains(ext.text, "| version |", "an extension root offers its version")
    for closed in EXTENSION_ONLY_ROWS + CLOSED_ROWS:
        assert_not_contains(ext.text, closed, "an extension root never offers %s" % closed)
    assert_no_diff("a read must not change the project")


@e2e_test(tool="get_metadata_details", kind="read")
def test_configuration_root_default_view_routes_to_the_root_readers():
    r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": ["Configuration"]})
    assert_ok(r, "the root in the default view is a per-object row, not a whole-call error")
    assert_contains(r.text, "## Errors", "the default view does not render the root")
    assert_contains(r.text, "get_configuration_properties", "the row names the tool that reads the root")
    assert_contains(r.text, "assignable", "the row names the view that lists what is settable")
    assert_not_contains(r.text, "Invalid FQN", "the root is a valid address, not a malformed one")
    assert_no_diff("a read must not change the project")


# ──────────────────────────────────────────────────────────────────────────────
# modify_metadata: writes
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_configuration_root_takes_all_nine_properties():
    name = _configuration_name(PROJECT)
    values = _values("base")
    r = call("modify_metadata", {"projectName": PROJECT, "fqn": "Configuration." + name,
                                 "properties": _properties(values)})
    _assert_written(r, "write the nine through 'Configuration.<Name>'")

    text = poll_disk_contains_all(ROOT_MDO, _disk_needles(values),
                                  ctx="the nine must land in the root's .mdo")
    _assert_localized_on_disk(text, values, "the base root")

    props = call("get_configuration_properties", {"projectName": PROJECT})
    assert_ok(props, "read the root back")
    assert_contains(props.text, "version: %s" % values["version"], "the model holds the new version")
    assert_contains(props.text, "vendor: %s" % values["vendor"], "the model holds the new vendor")
    assert_contains(props.text, "en: %s" % values["copyright"], "the model holds the new copyright")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_configuration_root_russian_token_sets_the_version():
    r = call("modify_metadata", {"projectName": PROJECT, "fqn": RU_CONFIGURATION,
                                 "properties": [{"name": "version", "value": "7.4.1.743"}]})
    assert_ok(r, "write the version through the Russian token")
    s = r.structured if isinstance(r.structured, dict) else {}
    if s.get("fqn") != "Configuration" or s.get("applied") != ["version"]:
        _fail("the Russian token is echoed as 'Configuration' and applies the version: %r" % (s,))
    poll_disk_contains_all(ROOT_MDO, ["<version>7.4.1.743</version>"],
                           ctx="the version must land in the root's .mdo")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_configuration_root_of_an_extension_keeps_all_nine_and_its_states():
    """The issue's own scenario: an extension's version (and the rest) set through MCP. EDT's extension
    formatter writes a native property of an ADOPTED root only while no same-named property state says
    otherwise - none of the nine has one (the synonym's is exempt on a ConfigurationExtension) - so all
    nine must land; and the write must leave the root's <extension> block, its property states, as it
    was (EDT's own editor writes no state for these)."""
    before = read_fixture_file(TESTS_PROJECT_REL, ROOT_MDO)
    block_before = _EXTENSION_BLOCK.search(before)
    if not block_before:
        _fail("the extension fixture's root must carry its ConfigurationExtension block: %r" % before[:600])
    values = _values("ext")
    try:
        r = call("modify_metadata", {"projectName": TESTS_PROJECT, "fqn": "Configuration",
                                     "properties": _properties(values)})
        _assert_written(r, "write the nine on the extension root")

        text = poll_disk_contains_all(
            ROOT_MDO, _disk_needles(values) + [
                'xsi:type="mdclassExtension:ConfigurationExtension"',
                "<keepMappingToExtendedConfigurationObjectsByIDs>true"
                "</keepMappingToExtendedConfigurationObjectsByIDs>",
                "<namePrefix>tests_</namePrefix>",
                "<objectBelonging>Adopted</objectBelonging>"],
            ctx="all nine must land in the extension root's .mdo, its extension settings intact",
            fixture_rel=TESTS_PROJECT_REL)
        _assert_localized_on_disk(text, values, "the extension root")
        block_after = _EXTENSION_BLOCK.search(text)
        if not block_after:
            _fail("the extension root lost its ConfigurationExtension block:\n%s" % text[:900])
        states_before = [line.strip() for line in block_before.group(1).splitlines() if line.strip()]
        states_after = [line.strip() for line in block_after.group(1).splitlines() if line.strip()]
        if states_before != states_after:
            _fail("the write must leave the root's property states as they were: before %r, after %r"
                  % (states_before, states_after))

        props = call("get_configuration_properties", {"projectName": TESTS_PROJECT})
        assert_ok(props, "read the extension root back")
        assert_contains(props.text, "version: %s" % values["version"], "the extension's model holds it")
        assert_no_diff("the base configuration is never touched by a write to the extension's root")
    except E2ECallTimeout:
        # NO cleanup here: the timed-out call may still be writing these very files, and a git reset
        # would race it. The orchestrator aborts the run on this.
        raise
    except BaseException:
        restore_extension_fixture("a write to the extension's configuration root")
        raise
    else:
        restore_extension_fixture("a write to the extension's configuration root")


# ──────────────────────────────────────────────────────────────────────────────
# modify_metadata: refusals (nothing may change)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_configuration_root_refuses_what_it_does_not_take():
    name = _configuration_name(PROJECT)

    err = assert_error(call("modify_metadata", {"projectName": PROJECT, "fqn": "Configuration",
                                                "properties": [{"name": "compatibilityMode",
                                                                "value": "8.3.27"}]}),
                       "a behaviour setting")
    assert_error_quality(err, names=["compatibilityMode"], suggests=["configuration root", "version"],
                         ctx="a behaviour setting is refused with the settable list")

    err = assert_error(call("modify_metadata", {"projectName": PROJECT, "fqn": "Configuration",
                                                "properties": [{"name": "name", "value": "Renamed"}]}),
                       "the root's name")
    assert_error_quality(err, names=["'name'"], suggests=["configuration root", "version"],
                         ctx="the name is refused on the root")
    assert_not_contains(err, "rename_metadata_object", "the root has no cascading rename to point at")

    err = assert_error(call("modify_metadata", {
        "projectName": TESTS_PROJECT, "fqn": "Configuration",
        "properties": [{"name": "keepMappingToExtendedConfigurationObjectsByIDs", "value": False}]}),
        "an extension's own setting")
    assert_error_quality(err, names=["keepMappingToExtendedConfigurationObjectsByIDs"],
                         suggests=["configuration root", "version"],
                         ctx="an extension's own setting stays with EDT")

    err = assert_error(call("modify_metadata", {"projectName": PROJECT, "fqn": "Configuration",
                                                "content": [{"metadata": "Catalog.Catalog"}]}),
                       "a content payload")
    assert_error_quality(err, names=["'content'"], suggests=["configuration root", "'properties'"],
                         ctx="the root takes only 'properties'")

    err = assert_error(call("modify_metadata", {"projectName": PROJECT, "fqn": "Configuration.NotThisOne",
                                                "properties": [{"name": "version", "value": "1.0"}]}),
                       "another configuration's Name")
    assert_error_quality(err, names=["NotThisOne", name], suggests=["'Configuration'"],
                         ctx="another Name is refused with the real one")

    assert_no_diff("every refusal comes before anything is written")
    assert_no_diff_rel(TESTS_PROJECT_REL, "the refused extension write changed nothing")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_configuration_root_is_refused_in_an_external_objects_project():
    r = call("modify_metadata", {"projectName": EXT_OBJECTS_PROJECT, "fqn": "Configuration",
                                 "properties": [{"name": "version", "value": "7.4.1.744"}]})
    err = assert_error(r, "a root write in an external-objects project")
    if err.startswith("Project not found"):
        raise E2ESkip("the %s fixture is not loaded on this stand" % EXT_OBJECTS_PROJECT)
    assert_error_quality(err, names=[EXT_OBJECTS_PROJECT], suggests=["external-objects", "'Configuration'"],
                         ctx="an external-objects project has no root of its own")

    details = call("get_metadata_details", {"projectName": EXT_OBJECTS_PROJECT,
                                            "objectFqns": ["Configuration"], "assignable": True})
    assert_ok(details, "the root of an external-objects project is a per-object row")
    assert_contains(details.text, "## Errors", "it is not rendered")
    assert_contains(details.text, "external-objects", "the row says why")
    assert_not_contains(details.text, ROOT_HEADING, "the LINKED base is never shown as this project's root")

    assert_no_diff("the linked base configuration is never written")
    assert_no_diff_rel(EXT_OBJECTS_REL, "the external-objects project is never written")
