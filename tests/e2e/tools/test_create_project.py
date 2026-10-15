"""
e2e tests for create_project (kind: action).

THE TOOL: creates a NEW project in the EDT workspace. The projectKind parameter
selects: configuration | extension | externalObjects.

HAPPY PATH:
- extension round-trip: creates a fresh extension from the fixture base config,
  verifies it appears in list_projects, then deletes it via delete_project (cleanup).
- configuration round-trip: creates a fresh standalone configuration, verifies it
  appears in list_projects, then deletes it.
- externalObjects round-trips: a seeded project exposes its root through the metadata
  read tools, while omitting externalObject still creates an empty import target.

NEGATIVE: missing projectKind, invalid projectKind, missing baseProjectName for
extension, baseProjectName rejected for configuration, invalid externalObject shapes,
duplicate name guard.

THE EXTENSION ROOT (issue #706): an extension's root is the base root ADOPTED the way EDT's own
new-extension wizard builds it - the ConfigurationExtension block with the adopter's property
states, keepMappingToExtendedConfigurationObjectsByIDs, the adopted default language linked to
the base language by ID, the BASE's compatibility mode, and the two command interface files - and
no base-only properties. The wizard-made fixture extension (tests/tests) is the oracle: it extends
the same base, so its extension block must be equal to a created one. An adoption into a root that
does not keep mapping by IDs - one an earlier create_project made - is mapped by name, and
adopt_metadata_object says so; it judges the COPY, which EDT links by ID only at adoption, so turning
the root's flag on or off later moves no copy adopted before. And a create no longer waits out a 60 s
lifecycle timeout: EDT starts the context inside create(), so the answer is 'ready' in seconds. The
created projects' paths come from list_projects (the workspace differs per machine and CI runs on
Linux), and every created project is removed in a finally block.
"""

import os
import re
import shutil
import tempfile
import time

from harness import (
    E2ESkip,
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    read_disk,
    read_fixture_file,
    settle_or_fail,
    split_markdown_row,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    TESTS_PROJECT_REL,
)

# Unique project names unlikely to collide with anything in the workspace.
NEW_EXT = "CreateProjTest_Ext_e2e"
NEW_CONFIG = "CreateProjTest_Config_e2e"
NEW_EXT_OBJ = "CreateProjTest_ExtObj_e2e"
NEW_EXT_OBJ_SEEDED = "CreateProjTest_ExtObjSeeded_e2e"
SEEDED_ROOT = "ExternalDataProcessor.CreateProjSeededRoot_e2e"


# Extensions whose ROOT is inspected (issue #706), one per test so a crashed run cannot leak into
# another test, and the throwaway base whose compatibility mode differs from the runtime version.
NEW_EXT_WIZARD = "CreateProjTest_ExtWizard_e2e"
NEW_EXT_ADOPT = "CreateProjTest_ExtAdopt_e2e"
NEW_EXT_BY_NAME = "CreateProjTest_ExtByName_e2e"
NEW_EXT_LINKED = "CreateProjTest_ExtLinked_e2e"
NEW_EXT_COMPAT = "CreateProjTest_ExtCompat_e2e"
NEW_EXT_REFUSED = "CreateProjTest_ExtRefused_e2e"
NEW_BASE_8327 = "CreateProjTest_Base8327_e2e"
NEW_EXT_8327 = "CreateProjTest_Ext8327_e2e"

ROOT_MDO = "src/Configuration/Configuration.mdo"
COMMAND_INTERFACES = ("src/Configuration/CommandInterface.cmi",
                      "src/Configuration/MainSectionCommandInterface.cmi")

# A create that waited out the old lifecycle timeout took 60.2-60.3 s on CI (issue #706): EDT starts
# the context INSIDE create(), so the STARTED event had always fired before anyone listened.
READY_WITHIN = 45.0

# The root's own block - the adopted language carries a LanguageExtension block of its own, later.
_EXTENSION_BLOCK_RE = re.compile(
    r'<extension xsi:type="mdclassExtension:ConfigurationExtension">(.*?)</extension>', re.S)

# Properties of a CONFIGURATION that a factory-made root serialized into the extension (the #706
# report) and that a root EDT's adopter builds never carries.
_BASE_ONLY_PROPERTIES = ("dataLockControlMode", "objectAutonumerationMode", "modalityUseMode",
                         "usedMobileApplicationFunctionalities",
                         "synchronousPlatformExtensionAndAddInCallUseMode")

_EXPORT_API_ABSENT = "IExportConfigurationFilesApi is not available"


def _ensure_absent(name):
    """Best-effort pre/post cleanup: remove a leftover project from a prior crashed run."""
    call("delete_project", {"projectName": name, "deleteContent": True, "confirm": True})


def _project_dir(project):
    """The project's directory as list_projects reports it (its Path column).

    Never derived from the fixture: EDT places a created project in ITS workspace, which differs per
    machine, and CI runs on Linux."""
    lp = call("list_projects", {})
    assert_ok(lp, "list_projects for the created project's path")
    columns = None
    for line in (lp.text or "").splitlines():
        cells = split_markdown_row(line)
        if not cells:
            continue
        if columns is None:
            lowered = [cell.lower() for cell in cells]
            if "name" in lowered and "path" in lowered:
                columns = (lowered.index("name"), lowered.index("path"))
            continue
        if len(cells) > max(columns) and cells[columns[0]] == project and cells[columns[1]]:
            return cells[columns[1]]
    raise AssertionError("list_projects has no row with a path for %r:\n%s" % (project, lp.text))


def _poll_file(path, needles, timeout=30, ctx=""):
    """Polls ONE file until a single read holds every needle, and returns that read.

    Local on purpose: harness.poll_disk_contains_all resolves paths under the repository's fixtures,
    and a created project lives in EDT's workspace instead."""
    deadline = time.time() + timeout
    last = "(not read yet)"
    while True:
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                last = f.read()
            if all(needle in last for needle in needles):
                return last
        except FileNotFoundError:
            last = "(file does not exist yet)"
        except OSError as e:
            last = "(file could not be read yet: %s)" % e
        if time.time() >= deadline:
            missing = [needle for needle in needles if needle not in last]
            raise AssertionError("expected %s to contain %r [%s]; it holds:\n%s"
                                 % (path, missing, ctx, last[:1500]))
        time.sleep(0.5)


def _extension_block(text, where):
    """The root's ConfigurationExtension block, whitespace-normalized."""
    match = _EXTENSION_BLOCK_RE.search(text)
    if match is None:
        raise AssertionError("no ConfigurationExtension block in %s:\n%s" % (where, text[:1500]))
    return " ".join(match.group(1).split())


def _base_root_facts():
    """What the base fixture's root says: its compatibility mode literal ('8.5.1'), its default
    language reference ('Language.English') and that language's uuid - read, never hard-coded."""
    text = read_disk(ROOT_MDO)
    compat = re.search(r"<compatibilityMode>([^<]+)</compatibilityMode>", text)
    default_language = re.search(r"<defaultLanguage>([^<]+)</defaultLanguage>", text)
    assert compat and default_language, "the base root must name its compatibility mode and default language"
    language_name = default_language.group(1).split(".", 1)[1]
    language = re.search(r'<languages uuid="([^"]+)">\s*<name>%s</name>' % re.escape(language_name), text)
    assert language, "the base root must hold its default language %r" % language_name
    return compat.group(1), default_language.group(1), language.group(1)


def _mode_name(literal):
    """The CompatibilityMode enum name for a literal: '8.5.1' -> 'Version8_5_1'."""
    return "Version" + literal.replace(".", "_")


_KEEP_MAPPING_LINE_RE = re.compile(
    r"[ \t]*<keepMappingToExtendedConfigurationObjectsByIDs>true</keepMappingToExtendedConfigurationObjectsByIDs>"
    r"\r?\n")


def _drop_keep_mapping(root_path):
    """Removes the mapping flag's line from a root file, every other byte kept, line ends included -
    the root an earlier create_project made. Returns (the removed line, the line that followed it), what
    _restore_keep_mapping needs to put it back, or None when the flag was never set."""
    with open(root_path, encoding="utf-8", newline="") as f:
        text = f.read()
    match = _KEEP_MAPPING_LINE_RE.search(text)
    if match is None:
        return None
    rest = text[match.end():]
    following = rest.splitlines(True)[0] if rest else ""
    with open(root_path, "w", encoding="utf-8", newline="") as f:
        f.write(text[:match.start()] + rest)
    return match.group(0), following


def _restore_keep_mapping(root_path, removed):
    """Puts a line _drop_keep_mapping removed back before the line that followed it, in the root as EDT has
    rewritten it since (an adoption adds the copy to the root's lists, so the old text cannot be written back)."""
    line, following = removed
    with open(root_path, encoding="utf-8", newline="") as f:
        text = f.read()
    at = text.find(following) if following else -1
    if at < 0:
        raise AssertionError("cannot restore the mapping flag: the line %r that followed it is no longer in %s:\n%s"
                             % (following, root_path, text[:1500]))
    with open(root_path, "w", encoding="utf-8", newline="") as f:
        f.write(text[:at] + line + text[at:])


def _create_extension(name, base=PROJECT, **extra):
    """Creates an extension and returns (response, seconds the call took)."""
    args = {"projectKind": "extension", "name": name, "baseProjectName": base}
    args.update(extra)
    started = time.time()
    r = call("create_project", args)
    return r, time.time() - started


# ── NEGATIVE ──────────────────────────────────────────────────────────────────

@e2e_test(tool="create_project", kind="action")
def test_missing_project_kind_errors():
    """No arguments at all — the first required arg 'projectKind' is reported."""
    r = call("create_project", {})
    e = assert_error(r, "no args")
    assert_error_quality(e, names=["projectKind"], suggests=[],
                         ctx="missing 'projectKind' is named in the error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_project", kind="action")
def test_missing_name_errors():
    """'projectKind' present but 'name' missing."""
    r = call("create_project", {"projectKind": "extension"})
    e = assert_error(r, "missing name")
    assert_error_quality(e, names=["name"], suggests=[],
                         ctx="missing 'name' is named in the error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_project", kind="action")
def test_invalid_project_kind_errors():
    """An unknown projectKind value must be rejected with a clear error."""
    r = call("create_project", {"projectKind": "bogusKind", "name": "MyProject"})
    e = assert_error(r, "invalid projectKind")
    assert_error_quality(e, names=["bogusKind"], suggests=[],
                         ctx="invalid projectKind value is named in the error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_project", kind="action")
def test_missing_base_project_name_for_extension_errors():
    """Extension kind without baseProjectName must fail with a clear error."""
    r = call("create_project", {"projectKind": "extension", "name": NEW_EXT})
    e = assert_error(r, "missing baseProjectName for extension")
    assert_error_quality(e, names=["baseProjectName"], suggests=["list_projects"],
                         ctx="missing baseProjectName is named in the error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_project", kind="action")
def test_base_project_name_rejected_for_configuration():
    """baseProjectName supplied with kind=configuration must be rejected."""
    r = call("create_project", {
        "projectKind": "configuration",
        "name": NEW_CONFIG,
        "baseProjectName": PROJECT,
    })
    e = assert_error(r, "baseProjectName rejected for configuration")
    assert_error_quality(e, names=["baseProjectName"], suggests=[],
                         ctx="baseProjectName is named in the error")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_project", kind="action")
def test_nonexistent_base_project_errors():
    """A base project name that does not exist in the workspace."""
    bad = "NoSuchBase_cep_zzz"
    r = call("create_project",
             {"projectKind": "extension", "name": NEW_EXT, "baseProjectName": bad})
    e = assert_error(r, "nonexistent base project")
    assert_error_quality(e, names=[bad], suggests=["list_projects"],
                         ctx="nonexistent base project named + list_projects hint")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_project", kind="action")
def test_external_object_bare_name_errors_with_expected_shape():
    """A root must include its bilingual type token; a bare Name is never guessed."""
    bad = "BareExternalRoot"
    r = call("create_project", {
        "projectKind": "externalObjects",
        "name": NEW_EXT_OBJ,
        "externalObject": bad,
    })
    e = assert_error(r, "bare externalObject Name")
    assert_error_quality(e, names=[bad],
                         suggests=["ExternalDataProcessor.<Name>", "ExternalReport.<Name>"],
                         ctx="bare root error names the value and both accepted shapes")
    assert_no_diff("a rejected call must not touch the fixture")


@e2e_test(tool="create_project", kind="action")
def test_external_object_unknown_type_errors_with_valid_kinds():
    """An unknown root type is rejected with the two kinds EDT can attach."""
    bad = "UnknownExternalRoot.MyObject"
    r = call("create_project", {
        "projectKind": "externalObjects",
        "name": NEW_EXT_OBJ,
        "externalObject": bad,
    })
    e = assert_error(r, "unknown externalObject type")
    assert_error_quality(e, names=[bad],
                         suggests=["ExternalDataProcessor", "ExternalReport"],
                         ctx="unknown root type names the value and both valid kinds")
    assert_no_diff("a rejected call must not touch the fixture")


# ── HAPPY PATH + DUPLICATE GUARD (extension) ──────────────────────────────────

@e2e_test(tool="create_project", kind="action")
def test_create_extension_then_delete():
    """Create a fresh extension from the fixture base, verify it, then clean up."""
    effective_name = PROJECT + "." + NEW_EXT
    _ensure_absent(effective_name)
    try:
        r, elapsed = _create_extension(NEW_EXT)
        assert_ok(r, "create_project extension happy path")
        assert r.structured is not None, "response must carry structuredContent"
        assert r.structured.get("action") == "created", \
            "action must be 'created', got %r" % (r.structured,)
        # #706: EDT started the context inside create() and the root reached the disk, so the
        # answer is 'ready' - and in seconds, not after a 60 s wait for an event already fired.
        assert r.structured.get("state") == "ready", \
            "state must be 'ready', got %r" % (r.structured,)
        assert elapsed < READY_WITHIN, \
            "create must answer well under the old 60 s lifecycle wait, took %.1f s" % elapsed
        assert r.structured.get("project") is not None, \
            "project must be present in the response"
        assert r.structured.get("projectKind") == "extension", \
            "projectKind must be 'extension', got %r" % r.structured.get("projectKind")
        ext_project_name = r.structured["project"]
        assert ext_project_name == PROJECT + "." + NEW_EXT, \
            "project must default to '<base>.<name>', got %r" % ext_project_name

        # Verify it appears in list_projects
        wait_for_project_ready()
        lp = call("list_projects", {})
        assert_contains(lp.text, ext_project_name,
                        "new extension must appear in list_projects")

        # Duplicate guard: calling again with the same computed name must fail
        r2 = call("create_project",
                  {"projectKind": "extension", "name": NEW_EXT, "baseProjectName": PROJECT})
        e2 = assert_error(r2, "duplicate extension name")
        assert_error_quality(e2, names=[ext_project_name], suggests=[],
                             ctx="duplicate project name is named in the error")

    finally:
        # The extension's effective project name is always '<base>.<name>'; clean up
        # only that. Deleting the bare NEW_EXT would risk an unrelated same-named project.
        _ensure_absent(PROJECT + "." + NEW_EXT)

    assert_no_diff("the fixture must be untouched after the round-trip")


# ── THE EXTENSION ROOT IS THE WIZARD'S (issue #706) ───────────────────────────

@e2e_test(tool="create_project", kind="action")
def test_extension_root_is_adopted_from_the_base_like_edt_wizard():
    """The created root equals what EDT's new-extension wizard makes over the same base.

    Mutation thinking: a factory-made root (the #706 defect) has no ConfigurationExtension block, no
    keepMapping flag, no adopted language linked to the base language, the base-only properties and
    no command interface files - each of the disk assertions below fails on it. Its compatibility
    modes are the RUNTIME version, which on these fixtures equals the base's mode, so the two mode
    assertions cannot tell it apart here - the default-mode test over a base at 8.3.27 does. The
    disk is read BEFORE the answer's fields, so a jar without #706 fails on the root itself, not on
    its answer. The oracle is the wizard-made fixture extension over this very base, so the block
    equality is exact, not a guess."""
    effective = PROJECT + "." + NEW_EXT_WIZARD
    compat, default_language, language_uuid = _base_root_facts()
    _ensure_absent(effective)
    try:
        r, elapsed = _create_extension(NEW_EXT_WIZARD)
        assert_ok(r, "create a wizard-shaped extension")

        directory = _project_dir(effective)
        root = _poll_file(os.path.join(directory, *ROOT_MDO.split("/")),
                          ["mdclassExtension:ConfigurationExtension",
                           "<keepMappingToExtendedConfigurationObjectsByIDs>true"
                           "</keepMappingToExtendedConfigurationObjectsByIDs>"],
                          ctx="the root a 'ready' answer vouched for")

        oracle = read_fixture_file(TESTS_PROJECT_REL, ROOT_MDO)
        assert _extension_block(root, effective) == _extension_block(oracle, "the wizard-made fixture"), \
            ("the property states must equal those EDT's wizard produced over the same base:\n"
             "created: %s\nwizard:  %s" % (_extension_block(root, effective),
                                           _extension_block(oracle, "the wizard-made fixture")))
        assert_contains(root, "<defaultLanguage>%s</defaultLanguage>" % default_language,
                        "the base's default language is adopted as the root's")
        assert_contains(root, 'extendedConfigurationObject="%s"' % language_uuid,
                        "the adopted language is linked to the base language by ID")
        assert_contains(root, "<compatibilityMode>%s</compatibilityMode>" % compat,
                        "the native compatibility mode is the base's, checked")
        assert_contains(root, "<configurationExtensionCompatibilityMode>%s"
                        "</configurationExtensionCompatibilityMode>" % compat,
                        "the extension compatibility mode defaults to the base's")
        for prop in _BASE_ONLY_PROPERTIES:
            assert ("<%s" % prop) not in root, \
                "a base-only property %r must not be serialized into the extension root:\n%s" % (prop, root)

        for cmi in COMMAND_INTERFACES:
            _poll_file(os.path.join(directory, *cmi.split("/")), ["cmi:CommandInterface"],
                       ctx="the command interface EDT's wizard creates")

        # Only now the answer's fields: the root they describe has been seen on disk.
        s = r.structured
        assert s.get("state") == "ready", "state must be 'ready', got %r" % (s,)
        assert elapsed < READY_WITHIN, \
            "create must answer well under the old 60 s lifecycle wait, took %.1f s" % elapsed
        assert s.get("compatibilityMode") == _mode_name(compat), \
            "the effective mode is the BASE's %r, got %r" % (_mode_name(compat), s.get("compatibilityMode"))
        assert_contains(s.get("message") or "", "adopted from the root of '%s'" % PROJECT,
                        "the answer says how the root was built")
        # The command interface files are looked for in the same bounded check as the root: both are on disk
        # (read above), so the answer names neither as unsaved or unseen.
        assert "CommandInterface.cmi" not in (s.get("message") or ""), \
            "both command interface files are on disk, so the answer says nothing of them: %r" % (s.get("message"),)
    finally:
        _ensure_absent(effective)

    assert_no_diff("creating an extension only READS the base fixture")


@e2e_test(tool="create_project", kind="write-metadata")
def test_adopt_into_a_created_extension_links_to_the_base_by_id():
    """An object adopted into a created extension carries the by-ID link to the base object.

    EDT writes extendedConfigurationObject only when the extension ROOT keeps mapping by IDs at
    adoption time, so on a factory-made root (#706) the copy below has no link - and nothing ever
    adds it later. kind=write-metadata: the adoption names the base fixture as projectName."""
    effective = PROJECT + "." + NEW_EXT_ADOPT
    base_calc = read_disk("src/CommonModules/Calc/Calc.mdo")
    base_uuid = re.search(r'<mdclass:CommonModule [^>]*uuid="([^"]+)"', base_calc)
    assert base_uuid, "the base fixture's CommonModule.Calc must carry its uuid"
    _ensure_absent(effective)
    try:
        r, _ = _create_extension(NEW_EXT_ADOPT)
        assert_ok(r, "create the extension to adopt into")

        adopted = call("adopt_metadata_object", {
            "projectName": PROJECT,
            "fqn": "CommonModule.Calc",
            # Two extensions extend the base now - the fixture's and this one.
            "extensionProjectName": effective,
        })
        assert_ok(adopted, "adopt a base object into the created extension")
        a = adopted.structured
        assert a.get("action") == "adopted", "expected action=adopted, got %r" % (a,)
        assert "message" not in a, \
            "a root that keeps mapping by IDs needs no by-name note, got %r" % (a.get("message"),)

        listed = call("get_metadata_objects", {"projectName": effective, "metadataType": "CommonModule"})
        assert_ok(listed, "read the adopted object back from the extension's model")
        assert_contains(listed.text, "Calc", "the adopted copy is in the extension's model")

        _poll_file(os.path.join(_project_dir(effective), "src", "CommonModules", "Calc", "Calc.mdo"),
                   ['extendedConfigurationObject="%s"' % base_uuid.group(1),
                    "<extendedConfigurationObject>Checked</extendedConfigurationObject>"],
                   ctx="the adopted copy is linked to the base object by ID")
    finally:
        _ensure_absent(effective)

    assert_no_diff("adopting reads the base; the copy lands in the created extension only")


def _adopt_into(effective, fqn, ctx):
    """Adopts a base fixture object into the created extension `effective` and returns the response."""
    adopted = call("adopt_metadata_object", {
        "projectName": PROJECT,
        "fqn": fqn,
        # Two extensions extend the base now - the fixture's and this one.
        "extensionProjectName": effective,
    })
    assert_ok(adopted, ctx)
    return adopted


def _adopted_copy_path(directory, module):
    return os.path.join(directory, "src", "CommonModules", module, module + ".mdo")


def _reimport(effective, ctx):
    """clean_project on the created extension - the deterministic re-import of its edited root - then settles."""
    assert_ok(call("clean_project", {"projectName": effective}), "re-import the extension root " + ctx)
    settle_or_fail("the extension after its root was re-imported " + ctx)


@e2e_test(tool="adopt_metadata_object", kind="write-metadata")
def test_adopt_into_an_extension_that_does_not_keep_mapping_by_ids_says_it_maps_by_name():
    """An extension root that does not keep mapping by IDs - the root an earlier create_project made -
    gets its adoptions mapped by NAME, and adopt_metadata_object says so in `message`. And the COPY
    decides that, not the root's flag now: once the flag is turned back on, the copy adopted without a
    link still has none - EDT writes the link only at adoption - and is still reported mapped by name.

    The flag is removed from the disk of a freshly created extension and brought into its model with
    clean_project, the deterministic re-import. That checks live what the unit tests only mock: under
    such a root EDT's copier writes no extendedConfigurationObject link, and the tool reads the copy.
    The disk is read first - the copy without a link holds on any jar; the note is what a jar without
    #706 lacks, and the note after the flag is restored is what a jar that judges by the flag lacks.
    kind=write-metadata: the adoption names the base fixture as projectName."""
    effective = PROJECT + "." + NEW_EXT_BY_NAME
    _ensure_absent(effective)
    try:
        r, _ = _create_extension(NEW_EXT_BY_NAME)
        assert_ok(r, "create the extension whose mapping flag is then removed")
        directory = _project_dir(effective)
        root_path = os.path.join(directory, *ROOT_MDO.split("/"))
        _poll_file(root_path, ["<mdclass:Configuration"], ctx="the created root on disk")
        settle_or_fail("the created extension before its root is edited on disk")
        # None means the root never kept mapping by IDs - the state the first half needs anyway; the
        # second half, which turns the flag back on, needs the removed line and checks for it there.
        removed = _drop_keep_mapping(root_path)
        _reimport(effective, "without its mapping flag")

        adopted = _adopt_into(effective, "CommonModule.Calc",
                              "adopt a base object into the extension that maps by name")

        copy = _poll_file(_adopted_copy_path(directory, "Calc"),
                          ["<objectBelonging>Adopted</objectBelonging>"], ctx="the adopted copy on disk")
        if "extendedConfigurationObject" in copy:
            raise AssertionError(
                "premise failed: the copy is linked to the base by ID, so EDT's model of the re-imported "
                "root still keeps mapping by IDs - the flag never left the model:\n%s" % copy[:1500])
        listed = call("get_metadata_objects", {"projectName": effective, "metadataType": "CommonModule"})
        assert_ok(listed, "read the adopted object back from the extension's model")
        assert_contains(listed.text, "Calc", "the adopted copy is in the extension's model")

        message = (adopted.structured or {}).get("message") or ""
        assert_contains(message, "by NAME, not by ID", "the result says the copy is mapped by name")
        # The copy THIS call adopted ran under the flag read just before it, so the flag is its reason, not a
        # likely one: a jar that judged it as a copy adopted earlier says "adopted earlier ... - the likely reason".
        assert_contains(message, ", and here the root's keepMappingToExtendedConfigurationObjectsByIDs is false",
                        "the result names the flag this adoption ran under as the reason")
        if "adopted earlier" in message:
            raise AssertionError("a copy adopted by this very call has no earlier history: %r" % message)

        # The second adoption takes the alreadyAdopted branch, which carries the same note.
        again = _adopt_into(effective, "CommonModule.Calc", "adopt the same object again")
        again_s = again.structured or {}
        if again_s.get("action") != "alreadyAdopted":
            raise AssertionError("expected action=alreadyAdopted; got %r" % again_s.get("action"))
        again_message = again_s.get("message") or ""
        assert_contains(again_message, "'CommonModule.Calc' is already adopted in extension",
                        "the second answer is the alreadyAdopted one")
        assert_contains(again_message, "by NAME, not by ID",
                        "the alreadyAdopted answer carries the by-name note too")

        # Turn the flag back on: the copy adopted without it gains no link, and is still mapped by name.
        if removed is None:
            raise AssertionError("premise failed: the created root never kept mapping by IDs, so there is no "
                                 "flag to turn back on")
        _poll_file(root_path, ["<commonModules>CommonModule.Calc</commonModules>"],
                   ctx="the root lists the adopted copy before the flag is written back")
        settle_or_fail("the extension before its root's flag is written back")
        _restore_keep_mapping(root_path, removed)
        _reimport(effective, "with its mapping flag back")
        # Premise: the flag is back in EDT's model - a fresh adoption is linked by ID again.
        _adopt_into(effective, "CommonModule.OK", "adopt another object under the restored flag")
        linked = _poll_file(_adopted_copy_path(directory, "OK"), ["<objectBelonging>Adopted</objectBelonging>"],
                            ctx="the copy adopted under the restored flag")
        if "extendedConfigurationObject" not in linked:
            raise AssertionError("premise failed: a copy adopted after the flag was written back has no link, so "
                                 "the flag never came back into EDT's model:\n%s" % linked[:1500])

        later = _adopt_into(effective, "CommonModule.Calc", "adopt the copy made without a link once more")
        later_s = later.structured or {}
        if later_s.get("action") != "alreadyAdopted":
            raise AssertionError("expected action=alreadyAdopted; got %r" % later_s.get("action"))
        copy = _poll_file(_adopted_copy_path(directory, "Calc"), ["<objectBelonging>Adopted</objectBelonging>"],
                          ctx="the copy made without a link, after the flag came back")
        if "extendedConfigurationObject" in copy:
            raise AssertionError("EDT added a link to a copy adopted without one - nothing should:\n%s" % copy[:1500])
        later_message = later_s.get("message") or ""
        assert_contains(later_message, "by NAME, not by ID",
                        "the copy decides, not the root's flag now: still mapped by name")
        assert_contains(later_message, "it was most likely adopted while the extension mapped by name",
                        "with the flag on and the version fine, the copy's likely history is the reason")
        if "is false" in later_message:
            raise AssertionError("the flag is on now and must not be blamed: %r" % later_message)
    finally:
        _ensure_absent(effective)

    assert_no_diff("adopting reads the base; the copy lands in the created extension only")


@e2e_test(tool="adopt_metadata_object", kind="write-metadata")
def test_a_copy_adopted_while_the_root_kept_mapping_keeps_its_link_after_the_flag_is_dropped():
    """A copy adopted while the extension root kept mapping by IDs carries its by-ID link from then on,
    whatever the root's flag says later - EDT's rename of the base object still carries such a copy
    along - so adopting it again under a root that no longer keeps mapping says nothing of a mapping by
    name. adopt_metadata_object reads the COPY: a jar that judges by the root's flag appends the
    by-name note here.

    The flag is removed on disk after the adoption and brought into the model with clean_project; a
    fresh adoption of another object then gets no link, which proves the flag left the model. The disk
    is read before the answer's message. kind=write-metadata: the adoption names the base fixture as
    projectName."""
    effective = PROJECT + "." + NEW_EXT_LINKED
    base_calc = read_disk("src/CommonModules/Calc/Calc.mdo")
    base_uuid = re.search(r'<mdclass:CommonModule [^>]*uuid="([^"]+)"', base_calc)
    assert base_uuid, "the base fixture's CommonModule.Calc must carry its uuid"
    link = 'extendedConfigurationObject="%s"' % base_uuid.group(1)
    _ensure_absent(effective)
    try:
        r, _ = _create_extension(NEW_EXT_LINKED)
        assert_ok(r, "create the extension to adopt into")
        directory = _project_dir(effective)
        root_path = os.path.join(directory, *ROOT_MDO.split("/"))

        first = _adopt_into(effective, "CommonModule.Calc", "adopt while the root keeps mapping by IDs")
        _poll_file(_adopted_copy_path(directory, "Calc"), [link], ctx="the copy is linked to the base by ID")
        if "message" in (first.structured or {}):
            raise AssertionError("a copy linked by ID needs no by-name note, got %r" % (first.structured["message"],))
        _poll_file(root_path, ["<commonModules>CommonModule.Calc</commonModules>"],
                   ctx="the root lists the adopted copy before its flag is removed")
        settle_or_fail("the extension before its root is edited on disk")
        if _drop_keep_mapping(root_path) is None:
            raise AssertionError("premise failed: the created root never kept mapping by IDs")
        _reimport(effective, "without its mapping flag")

        # Premise: the flag left EDT's model - a fresh adoption is no longer linked by ID.
        _adopt_into(effective, "CommonModule.OK", "adopt another object without the flag")
        unlinked = _poll_file(_adopted_copy_path(directory, "OK"), ["<objectBelonging>Adopted</objectBelonging>"],
                              ctx="the copy adopted without the flag")
        if "extendedConfigurationObject" in unlinked:
            raise AssertionError("premise failed: a copy adopted after the flag was removed is linked by ID, so the "
                                 "flag never left EDT's model:\n%s" % unlinked[:1500])

        again = _adopt_into(effective, "CommonModule.Calc", "adopt the linked copy again")
        s = again.structured or {}
        if s.get("action") != "alreadyAdopted":
            raise AssertionError("expected action=alreadyAdopted; got %r" % s.get("action"))
        _poll_file(_adopted_copy_path(directory, "Calc"), [link],
                   ctx="the copy keeps its link after the root's flag was removed")
        expected = "'CommonModule.Calc' is already adopted in extension '%s'." % effective
        if s.get("message") != expected:
            raise AssertionError("a copy linked by ID gets no by-name note, whatever the root's flag says now: "
                                 "expected %r; got %r" % (expected, s.get("message")))
    finally:
        _ensure_absent(effective)

    assert_no_diff("adopting reads the base; the copies land in the created extension only")


@e2e_test(tool="create_project", kind="action")
def test_explicit_extension_compatibility_mode_keeps_the_base_mode_checked():
    """A requested mode moves only the EXTENSION mode; the native mode stays the base's, Checked.

    The disk is read BEFORE the answer's fields: a factory-made root (#706) has the requested
    extension mode too, but no extension block, so it fails on the missing Checked state."""
    effective = PROJECT + "." + NEW_EXT_COMPAT
    compat, _, _ = _base_root_facts()
    _ensure_absent(effective)
    try:
        r, _ = _create_extension(NEW_EXT_COMPAT, compatibilityMode="Version8_3_27")
        assert_ok(r, "create an extension with an explicit compatibility mode")

        root = _poll_file(os.path.join(_project_dir(effective), *ROOT_MDO.split("/")),
                          ["<configurationExtensionCompatibilityMode>8.3.27"
                           "</configurationExtensionCompatibilityMode>"],
                          ctx="the requested extension mode")
        assert_contains(root, "<compatibilityMode>%s</compatibilityMode>" % compat,
                        "the native mode stays the base's")
        assert_contains(_extension_block(root, effective), "<compatibilityMode>Checked</compatibilityMode>",
                        "the native mode keeps its Checked state")

        # Only now the answer's fields: the root they describe has been seen on disk.
        s = r.structured
        assert s.get("compatibilityMode") == "Version8_3_27", "got %r" % (s,)
        assert_contains(s.get("message") or "", "Version8_3_27 (requested; the base configuration's is %s)"
                        % _mode_name(compat), "the answer names both modes")
    finally:
        _ensure_absent(effective)

    assert_no_diff("creating an extension only READS the base fixture")


@e2e_test(tool="create_project", kind="action")
def test_extension_mode_8313_or_lower_over_a_newer_base_is_refused_before_anything_is_created():
    """EDT's adopter derives the root's default-roles state from the BASE mode; an extension mode of
    8.3.13 or lower over a base above it is a combination the wizard never produces."""
    effective = PROJECT + "." + NEW_EXT_REFUSED
    compat, _, _ = _base_root_facts()
    _ensure_absent(effective)
    try:
        r, _ = _create_extension(NEW_EXT_REFUSED, compatibilityMode="Version8_3_13")
        e = assert_error(r, "an extension mode of 8.3.13 or lower over a newer base")
        assert_error_quality(e, names=["Version8_3_13", _mode_name(compat), PROJECT],
                             suggests=["Omit compatibilityMode"],
                             ctx="the refusal names both modes, the base and the way out")
        assert_contains(e, "Nothing was created.", "the refusal says nothing was created")
        lp = call("list_projects", {})
        assert effective not in (lp.text or ""), "a refused create must leave no project behind"
    finally:
        # A server that does NOT refuse creates the extension; leaving it would give the base a third
        # extension and break every later adoption that relies on the base having exactly one.
        _ensure_absent(effective)
    assert_no_diff("a refused create must not touch the fixture")


@e2e_test(tool="create_project", kind="write-metadata")
def test_extension_compatibility_defaults_to_the_base_mode_not_the_runtime():
    """The default is the BASE configuration's mode, not the project's platform version.

    Every fixture has compatibility mode == runtime version, so only a base whose mode differs can
    tell the two apart: export the fixture, set its CompatibilityMode to 8.3.27, import it as a new
    base, and extend that. A factory-made root answers the imported base's runtime version for both
    modes; should that version be 8.3.27 itself, the two cannot be told apart and the test skips,
    saying so. The disk is read BEFORE the answer's fields. kind=write-metadata: importing the patched
    base is a fixture-model write, as in the import round-trip test."""
    effective = NEW_BASE_8327 + "." + NEW_EXT_8327
    fixture_compat, _, _ = _base_root_facts()
    assert fixture_compat != "8.3.27", \
        "precondition: the fixture's own mode must differ from the 8.3.27 the patched base gets"
    _ensure_absent(effective)
    _ensure_absent(NEW_BASE_8327)
    dump_dir = tempfile.mkdtemp(prefix="edt_706_e2e_")
    try:
        ex = call("export_configuration_to_xml", {"projectName": PROJECT, "outputPath": dump_dir})
        if ex.is_error and _EXPORT_API_ABSENT in (ex.error_text() or ""):
            raise E2ESkip("the CLI export API is not installed in this EDT; no base with a lower "
                          "compatibility mode can be manufactured")
        assert_ok(ex, "export the fixture to XML")
        configuration_xml = os.path.join(dump_dir, "Configuration.xml")
        with open(configuration_xml, encoding="utf-8-sig") as f:
            text = f.read()
        modes = re.findall(r"<CompatibilityMode>([^<]+)</CompatibilityMode>", text)
        assert len(modes) == 1, "the dump must hold one CompatibilityMode, found %r" % (modes,)
        text = text.replace("<CompatibilityMode>%s</CompatibilityMode>" % modes[0],
                            "<CompatibilityMode>Version8_3_27</CompatibilityMode>")
        with open(configuration_xml, "w", encoding="utf-8") as f:
            f.write(text)

        im = call("import_configuration_from_xml", {"importPath": dump_dir, "projectName": NEW_BASE_8327})
        assert_ok(im, "import the patched dump as a new base")
        settle_or_fail("the imported base before it is extended")

        r, _ = _create_extension(NEW_EXT_8327, base=NEW_BASE_8327)
        assert_ok(r, "extend the 8.3.27 base")
        runtime = (r.structured or {}).get("version")
        if runtime == "8.3.27":
            raise E2ESkip("the imported base runs on platform 8.3.27, the mode it was given, so a root built "
                          "from the runtime version would answer 8.3.27 as well: this environment cannot tell "
                          "the base's mode from the runtime version")

        root = _poll_file(os.path.join(_project_dir(effective), *ROOT_MDO.split("/")),
                          ["<configurationExtensionCompatibilityMode>8.3.27"
                           "</configurationExtensionCompatibilityMode>",
                           "<compatibilityMode>8.3.27</compatibilityMode>"],
                          ctx="both modes are the base's, not the runtime version %s" % runtime)
        runtime_literal = runtime or fixture_compat
        for element in ("compatibilityMode", "configurationExtensionCompatibilityMode"):
            assert "<%s>%s</%s>" % (element, runtime_literal, element) not in root, \
                "%s must not come from the runtime version %s:\n%s" % (element, runtime_literal, root)

        # Only now the answer's field: the root it describes has been seen on disk.
        assert r.structured.get("compatibilityMode") == "Version8_3_27", \
            "the default must be the base's 8.3.27, got %r" % (r.structured,)
    finally:
        _ensure_absent(effective)
        _ensure_absent(NEW_BASE_8327)
        shutil.rmtree(dump_dir, ignore_errors=True)

    assert_no_diff("the round trip only READS the fixture")


# ── HAPPY PATH (configuration) ────────────────────────────────────────────────

@e2e_test(tool="create_project", kind="action")
def test_create_configuration_then_delete():
    """Create a fresh standalone configuration, verify it, then clean up."""
    _ensure_absent(NEW_CONFIG)
    try:
        started = time.time()
        r = call("create_project", {"projectKind": "configuration", "name": NEW_CONFIG})
        elapsed = time.time() - started
        assert_ok(r, "create_project configuration happy path")
        assert r.structured is not None, "response must carry structuredContent"
        assert r.structured.get("action") == "created", \
            "action must be 'created', got %r" % (r.structured,)
        # #706: the lifecycle wait asks EDT whether the context already started (it did, inside
        # create()) instead of waiting out 60 s for an event that fired before anyone listened.
        assert r.structured.get("state") == "ready", \
            "state must be 'ready', got %r" % (r.structured,)
        assert elapsed < READY_WITHIN, \
            "create must answer well under the old 60 s lifecycle wait, took %.1f s" % elapsed
        assert r.structured.get("projectKind") == "configuration", \
            "projectKind must be 'configuration', got %r" % r.structured.get("projectKind")
        proj_name = r.structured.get("project")
        assert proj_name == NEW_CONFIG, \
            "project must default to 'name', got %r" % proj_name

        # Verify it appears in list_projects
        wait_for_project_ready()
        lp = call("list_projects", {})
        assert_contains(lp.text, NEW_CONFIG,
                        "new configuration must appear in list_projects")

    finally:
        _ensure_absent(NEW_CONFIG)

    assert_no_diff("the fixture must be untouched after the round-trip")


# ── HAPPY PATH (externalObjects) ──────────────────────────────────────────────

@e2e_test(tool="create_project", kind="action")
def test_create_external_objects_with_seeded_root_then_delete():
    """Seed the project root and resolve it through both metadata read routes."""
    _ensure_absent(NEW_EXT_OBJ_SEEDED)
    try:
        r = call("create_project", {
            "projectKind": "externalObjects",
            "name": NEW_EXT_OBJ_SEEDED,
            "externalObject": SEEDED_ROOT,
        })
        assert_ok(r, "create_project externalObjects with seeded root")
        assert r.structured is not None, "response must carry structuredContent"
        assert r.structured.get("project") == NEW_EXT_OBJ_SEEDED, \
            "seeded project must use the requested project name, got %r" % r.structured
        settle_or_fail("reading the root seeded by create_project")

        roots = call("get_metadata_objects", {"projectName": NEW_EXT_OBJ_SEEDED})
        assert_ok(roots, "list the newly seeded external-object root")
        assert_contains(roots.text, "CreateProjSeededRoot_e2e",
                        "get_metadata_objects must see the seeded root Name")
        assert_contains(roots.text, "ExternalDataProcessor",
                        "get_metadata_objects must see the seeded root type")

        details = call("get_metadata_details", {
            "projectName": NEW_EXT_OBJ_SEEDED,
            "objectFqns": [SEEDED_ROOT],
        })
        assert_ok(details, "read the newly seeded external-object root")
        assert_contains(details.text, "ExternalDataProcessor: CreateProjSeededRoot_e2e",
                        "get_metadata_details must resolve the seeded root FQN")
    finally:
        _ensure_absent(NEW_EXT_OBJ_SEEDED)

    assert_no_diff("the fixture must be untouched after the seeded-project round-trip")

@e2e_test(tool="create_project", kind="action")
def test_create_external_objects_then_delete():
    """Omitting externalObject must preserve the empty-project import workflow."""
    _ensure_absent(NEW_EXT_OBJ)
    try:
        started = time.time()
        r = call("create_project", {"projectKind": "externalObjects", "name": NEW_EXT_OBJ})
        elapsed = time.time() - started
        assert_ok(r, "create_project externalObjects happy path")
        assert r.structured is not None, "response must carry structuredContent"
        assert r.structured.get("action") == "created", \
            "action must be 'created', got %r" % (r.structured,)
        # #706: the same lifecycle wait serves every project kind.
        assert r.structured.get("state") == "ready", \
            "state must be 'ready', got %r" % (r.structured,)
        assert elapsed < READY_WITHIN, \
            "create must answer well under the old 60 s lifecycle wait, took %.1f s" % elapsed
        assert r.structured.get("projectKind") == "externalObjects", \
            "projectKind must be 'externalObjects', got %r" % r.structured.get("projectKind")
        proj_name = r.structured.get("project")
        assert proj_name == NEW_EXT_OBJ, \
            "project must default to 'name', got %r" % proj_name

        # Verify it appears in list_projects and that the model remains empty.
        settle_or_fail("checking an unseeded external-objects project")
        lp = call("list_projects", {})
        assert_contains(lp.text, NEW_EXT_OBJ,
                        "new externalObjects project must appear in list_projects")

        roots = call("get_metadata_objects", {"projectName": NEW_EXT_OBJ})
        assert_ok(roots, "list roots of an unseeded external-objects project")
        assert_contains(roots.text, "**Total:** 0 objects",
                        "omitting externalObject must leave the project empty")
        assert_contains(roots.text, "No metadata objects found.",
                        "the empty project must report that it has no roots")

    finally:
        _ensure_absent(NEW_EXT_OBJ)

    assert_no_diff("the fixture must be untouched after the round-trip")
