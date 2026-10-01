"""
VENDOR-SUPPORT guard coverage - issue #642.

EDT enforces vendor support only in its UI (read-only editors, hidden actions) and in its
refactorings (as bypassable problems); a BM write below that succeeds silently. The writing tools
therefore ask EDT's own editing-support check (IModelEditingSupport) before they write, and refuse
a change to an object whose support rule does not allow it. These tests prove that guard against
EDT's REAL verdict on a real support file, which no unit test can: the verdict is the platform's.

Fixture ground truth (tests/SupportedConfiguration, src/Configuration/Configuration.distr in
per-object mode - the rules allow changes, and each item without userMode is locked):
  locked:   the Configuration itself (so no new top-level object, and no top object deletable),
            Catalog.Locked (+ its attribute Weight), Catalog.<Russian "Closed"> (+ its attribute
            Code2), CommonModule.Locked, Report.LockedReport (no DCS yet), Role.LockedRole;
  editable: Catalog.Open (+ its attribute Note), CommonModule.Open, Role.OpenRole, the language.
  Role.LockedRole holds an attribute right on Catalog.Open.Attribute.Note, so deleting that
  (editable) attribute has to change a locked role - the delete's support-derived prohibition.
  Deleting ANY attribute of a top object also removes that object's derived field, which EDT
  judges as deleting the object itself: with the configuration locked, no such delete passes.

Anti-cheat: every refusal is checked for its wording AND for the fixture being byte-identical
afterwards (a content hash of every file, not only git status), and the editable controls must
succeed and reach disk - a guard that refused everything would fail those, one that refused
nothing would fail the rest.
"""

import hashlib
import os
import time

from harness import (
    PROJECT, REPO_ROOT, SUPPORTED_PROJECT, SUPPORTED_REL, _fail, assert_contains, assert_error,
    assert_error_quality, assert_no_diff, assert_no_diff_rel, assert_not_contains, assert_ok,
    call, e2e_test, poll_diff_contains_rel, read_fixture_file, settle_or_fail,
)

# Built from escapes so a non-UTF-8 checkout cannot corrupt the tokens.
RU_CATALOG = "Справочник"
RU_ATTRIBUTE = "Реквизит"
RU_CLOSED = "Закрытый"

SUPPORTED_DIR = os.path.join(REPO_ROOT, *SUPPORTED_REL.split("/"))

# The shared part of every lock refusal: says why, says nothing changed, names the way out.
LOCK_SUGGESTS = ["vendor support", "Nothing was changed", "adopt_metadata_object"]


def _tree_hashes():
    """sha1 of every file under the fixture, keyed by its relative path."""
    hashes = {}
    for root, _dirs, files in os.walk(SUPPORTED_DIR):
        for name in files:
            path = os.path.join(root, name)
            with open(path, "rb") as f:
                hashes[os.path.relpath(path, SUPPORTED_DIR).replace(os.sep, "/")] = \
                    hashlib.sha1(f.read()).hexdigest()
    return hashes


def _settled_hashes(timeout=8.0):
    """Two consecutive equal samples: an async export still landing is waited out, not raced."""
    deadline = time.time() + timeout
    previous = _tree_hashes()
    while time.time() < deadline:
        time.sleep(0.75)
        current = _tree_hashes()
        if current == previous:
            return current
        previous = current
    return previous


def _assert_byte_identical(before, ctx):
    after = _settled_hashes()
    if after != before:
        changed = sorted(p for p in set(before) | set(after) if before.get(p) != after.get(p))
        _fail("a refused write must leave the vendor-support fixture byte-identical [%s]; "
              "changed: %s" % (ctx, ", ".join(changed)[:600]))
    assert_no_diff_rel(SUPPORTED_REL, ctx)


def _refused(tool, args, names, ctx):
    """Call, expect the lock refusal, and return its text."""
    err = assert_error(call(tool, dict(args, projectName=SUPPORTED_PROJECT)), ctx)
    assert_error_quality(err, names=names, suggests=LOCK_SUGGESTS, ctx=ctx)
    return err


def _start():
    settle_or_fail("the vendor-support guard test")
    return _settled_hashes()


# ==================== refusals: nothing may change ====================

@e2e_test(tool="create_metadata", kind="write")
def test_vendor_create_refused_on_a_locked_configuration_and_owner():
    """A new top-level object changes the (locked) configuration; a new member its (locked) owner.

    Mutation thinking: a guard that asked the NEW object (which does not exist) would allow both;
    one that asked only the configuration would allow the member on a locked catalog - the
    Russian-token call pins the bilingual FQN path through the same owner."""
    before = _start()
    err = _refused("create_metadata", {"fqn": "Catalog.E2EVendorNew"},
                   ["Catalog.E2EVendorNew", "SupportedConfiguration"], "create a top object")
    assert_contains(err, "cannot be created", "the refusal names the operation")
    _refused("create_metadata", {"fqn": "Catalog.Locked.Attribute.E2EVendorAttr"},
             ["Catalog.Locked"], "create a member of a locked catalog")
    err = _refused("create_metadata",
                   {"fqn": "%s.%s.%s.E2EVendorRu" % (RU_CATALOG, RU_CLOSED, RU_ATTRIBUTE)},
                   [RU_CLOSED], "create a member through Russian tokens")
    assert_contains(err, "itself is locked", "the locked configuration is named as well")
    _assert_byte_identical(before, "refused creates")
    listed = call("get_metadata_objects", {"projectName": SUPPORTED_PROJECT})
    assert_ok(listed, "list the fixture's objects")
    assert_not_contains(listed.text, "E2EVendorNew", "the refused object must not be in the model")
    assert_no_diff("the base project is never touched")


@e2e_test(tool="modify_metadata", kind="write")
def test_vendor_modify_refused_on_a_locked_object_and_member():
    before = _start()
    _refused("modify_metadata",
             {"fqn": "Catalog.Locked",
              "properties": [{"name": "synonym", "value": "E2E vendor", "language": "en"}]},
             ["Catalog.Locked"], "modify a locked catalog")
    err = _refused("modify_metadata",
                   {"fqn": "Catalog.Locked.Attribute.Weight",
                    "properties": [{"name": "comment", "value": "E2E vendor"}]},
                   ["Catalog.Locked.Attribute.Weight"], "modify a locked attribute")
    assert_contains(err, "cannot be changed", "the refusal names the operation")
    _assert_byte_identical(before, "refused modifies")
    details = call("get_metadata_details",
                   {"projectName": SUPPORTED_PROJECT, "objectFqns": ["Catalog.Locked"]})
    assert_ok(details, "re-read the locked catalog")
    assert_not_contains(details.text, "E2E vendor", "the refused change must not be in the model")


@e2e_test(tool="delete_metadata", kind="write")
def test_vendor_delete_refused_in_preview_and_with_force():
    """The lock is checked before any refactoring exists, for the preview and confirm alike, and
    force=true does not override it (owner decision for #642: a support lock is not a block
    force may lift)."""
    before = _start()
    _refused("delete_metadata", {"fqn": "Catalog.Locked"}, ["Catalog.Locked"],
             "delete preview of a locked catalog")
    err = _refused("delete_metadata", {"fqn": "Catalog.Locked", "confirm": True, "force": True},
                   ["Catalog.Locked"], "forced delete of a locked catalog")
    assert_contains(err, "cannot be deleted", "the refusal names the operation")
    _refused("delete_metadata",
             {"fqn": "%s.%s.%s.Code2" % (RU_CATALOG, RU_CLOSED, RU_ATTRIBUTE),
              "confirm": True, "force": True},
             ["Code2"], "forced delete of a locked attribute through Russian tokens")
    # Catalog.Open is editable, but deleting a top object changes the locked configuration.
    _refused("delete_metadata", {"fqn": "Catalog.Open", "confirm": True, "force": True},
             ["Catalog.Open"], "forced delete of a top object of a locked configuration")
    _assert_byte_identical(before, "refused deletes")


@e2e_test(tool="delete_metadata", kind="write")
def test_vendor_force_does_not_lift_a_support_prohibition_on_a_referencing_object():
    """The attribute itself may be deleted, but EDT's refactoring would have to change the locked
    role that grants a right on it (and remove Catalog.Open's derived field): support-derived platform
    prohibitions, marked supportLock and not forceable. Preview first, so an EDT that reported
    nothing fails here without deleting."""
    before = _start()
    fqn = "Catalog.Open.Attribute.Note"
    preview = call("delete_metadata", {"projectName": SUPPORTED_PROJECT, "fqn": fqn})
    assert_ok(preview, "preview the delete of an editable attribute a locked role references")
    sc = preview.structured or {}
    locks = [p for p in (sc.get("platformProhibitions") or []) if p.get("supportLock") is True]
    if not locks or sc.get("blocking") is not True:
        _fail("the preview must list the locked role as a supportLock prohibition: %r" % sc)
    assert_contains(sc.get("message", ""), "force=true does not override vendor support",
                    "the preview must not advise force for a support lock")
    blocked = call("delete_metadata",
                   {"projectName": SUPPORTED_PROJECT, "fqn": fqn, "confirm": True, "force": True})
    err = assert_error(blocked, "forced delete past a support prohibition")
    assert_error_quality(err, names=[fqn], suggests=["force=true does not override vendor support",
                                                     "Nothing was deleted"], ctx="blocked delete")
    if (blocked.structured or {}).get("action") != "blocked":
        _fail("the forced delete must answer action='blocked': %r" % blocked.structured)
    _assert_byte_identical(before, "a blocked forced delete")


@e2e_test(tool="rename_metadata_object", kind="write")
def test_vendor_rename_refused_before_the_refactoring():
    """EDT's own rename checks only the REFERENCING objects, never the renamed one."""
    before = _start()
    for confirm in (False, True):
        err = _refused("rename_metadata_object",
                       {"objectFqn": "Catalog.Locked", "newName": "E2EVendorRenamed",
                        "confirm": confirm},
                       ["Catalog.Locked"], "rename a locked catalog (confirm=%s)" % confirm)
        assert_contains(err, "cannot be renamed", "the refusal names the operation")
    _assert_byte_identical(before, "refused renames")


@e2e_test(tool="write_module_source", kind="write")
def test_vendor_module_write_refused_for_a_locked_owner():
    before = _start()
    _refused("write_module_source",
             {"modulePath": "CommonModules/Locked/Module.bsl", "mode": "append",
              "source": "// e2e vendor\n"},
             ["CommonModule.Locked"], "append to a locked common module")
    err = _refused("write_module_source",
                   {"modulePath": "Catalogs/Locked/ObjectModule.bsl", "mode": "replace",
                    "source": "// e2e vendor\n", "overwrite": True},
                   ["Catalog.Locked"], "replace a locked catalog's object module")
    assert_contains(err, "cannot be written", "the refusal names the operation")
    _assert_byte_identical(before, "refused module writes")


@e2e_test(tool="write_module_source", kind="write")
def test_vendor_module_write_through_another_projects_name_refused():
    """An absolute modulePath resolves across the whole workspace: naming another (unsupported)
    project must not judge that project while the write lands in the locked module."""
    before = _start()
    target = os.path.join(SUPPORTED_DIR, "src", "CommonModules", "Locked", "Module.bsl")
    r = call("write_module_source", {"projectName": PROJECT, "modulePath": target, "mode": "replace",
                                     "source": "// e2e vendor\n", "overwrite": True})
    err = assert_error(r, "write a locked module under another project's name")
    assert_error_quality(err, names=[SUPPORTED_PROJECT, PROJECT], suggests=["Nothing was changed"],
                         ctx="a module of another project")
    _assert_byte_identical(before, "a module write under another project's name")
    assert_no_diff("the named project is not touched either")


@e2e_test(tool="rename_metadata_object", kind="write")
def test_vendor_rename_refused_when_it_would_update_a_locked_referrer():
    """The attribute is editable, but its rename would also update the locked role that grants a
    right on it: EDT records that as a problem and performs the edit anyway. The preview is asked
    first, so an EDT that reports no problem fails here without renaming anything."""
    before = _start()
    fqn = "Catalog.Open.Attribute.Note"
    for confirm in (False, True):
        err = _refused("rename_metadata_object",
                       {"objectFqn": fqn, "newName": "E2EVendorNote", "confirm": confirm},
                       [fqn, "LockedRole"], "rename past a locked referrer (confirm=%s)" % confirm)
        assert_contains(err, "would update references inside", "the refusal names the cascade")
    _assert_byte_identical(before, "refused renames past a locked referrer")


@e2e_test(tool="dcs", kind="write")
def test_vendor_dcs_write_refused_for_a_locked_report():
    """The report has no DCS yet, so the write would CREATE its main template - on a locked report."""
    before = _start()
    _refused("dcs",
             {"fqn": "Report.LockedReport", "action": "upsert", "type": "schema",
              "body": {"dataSets": [{"name": "DataSet1", "type": "query", "query": "SELECT 1 AS A",
                                     "autoFillFields": False, "fields": [{"dataPath": "A"}]}]}},
             ["Report.LockedReport"], "author a DCS on a locked report")
    _assert_byte_identical(before, "a refused DCS write")


@e2e_test(tool="translate_configuration", kind="write-metadata")
def test_vendor_in_place_translation_refused_for_a_locked_configuration():
    """The fixture declares English, so synchronizing 'en' writes the locked configuration's own
    objects: refused before LanguageTool is touched (so the verdict holds whether or not it is
    installed). A language the configuration does not declare can only go to a dependent
    translation project and is not a vendor-support question."""
    before = _start()
    r = call("translate_configuration", {"projectName": SUPPORTED_PROJECT, "targetLanguages": ["en"]})
    err = assert_error(r, "translate a locked configuration in place")
    assert_error_quality(err, names=[SUPPORTED_PROJECT, "in place"],
                         suggests=["vendor support", "Nothing was changed", "dependent translation project"],
                         ctx="an in-place translation of a locked configuration")
    _assert_byte_identical(before, "a refused in-place translation")
    other = call("translate_configuration", {"projectName": SUPPORTED_PROJECT, "targetLanguages": ["de"]})
    text = other.error_text() if other.is_error else (other.text or "")
    assert_not_contains(text, "vendor support",
                        "an undeclared target language is not refused for vendor support")


# ==================== the read side ====================

@e2e_test(tool="get_metadata_details", kind="read")
def test_vendor_details_line_only_for_a_supported_object():
    r = call("get_metadata_details", {"projectName": SUPPORTED_PROJECT,
                                      "objectFqns": ["Catalog.Locked", "Catalog.Open"]})
    assert_ok(r, "details of a locked and an editable catalog")
    text = r.text or ""
    # One line per object. The editable catalog still gets one - it is not deletable, because
    # deleting a top object changes the locked configuration - and it must say "editable".
    counts = (text.count("**Vendor support:** not editable, not deletable"),
              text.count("**Vendor support:** editable, not deletable"))
    if counts != (1, 1):
        _fail("expected one locked and one editable-but-not-deletable line, got %r:\n%s"
              % (counts, text[:1500]))
    assert_contains(text, "(the configuration itself is locked)", "the configuration lock is named")
    base = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": ["Catalog.Catalog"]})
    assert_ok(base, "details of an object in a configuration without support")
    assert_not_contains(base.text, "Vendor support", "no support, no line - it costs nothing")
    assert_no_diff_rel(SUPPORTED_REL, "a read tool must not touch the fixture")


# ==================== editable controls: the guard must not refuse these ====================

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_vendor_editable_objects_still_take_writes():
    settle_or_fail("the editable-object controls")
    assert_ok(call("modify_metadata", {
        "projectName": SUPPORTED_PROJECT, "fqn": "Catalog.Open",
        "properties": [{"name": "synonym", "value": "E2E vendor open", "language": "en"}]}),
        "modify an editable catalog of a locked configuration")
    poll_diff_contains_rel(SUPPORTED_REL, "E2E vendor open", ctx="the synonym reaches Open.mdo")

    fqn = "Catalog.Open.Attribute.E2EVendorOpenAttr"
    assert_ok(call("create_metadata", {"projectName": SUPPORTED_PROJECT, "fqn": fqn}),
              "create a member of an editable catalog")
    poll_diff_contains_rel(SUPPORTED_REL, "E2EVendorOpenAttr", ctx="the attribute reaches Open.mdo")
    # Deleting it again is NOT an editable control: the delete also removes Catalog.Open's derived
    # field for the attribute, and EDT judges that removal as deleting Catalog.Open, which the
    # locked configuration root forbids (EDT's own delete wizard cannot finish it either).
    before = _settled_hashes()
    blocked = call("delete_metadata",
                   {"projectName": SUPPORTED_PROJECT, "fqn": fqn, "confirm": True})
    err = assert_error(blocked, "delete a member of an editable catalog of a locked configuration")
    assert_error_quality(err, names=[fqn], suggests=["force=true does not override vendor support",
                                                     "Nothing was deleted"], ctx="blocked member delete")
    locks = [(p.get("problemType"), p.get("targetObject"))
             for p in ((blocked.structured or {}).get("platformProhibitions") or [])
             if p.get("supportLock") is True]
    if locks != [("DeletionForbiddenProblem", "Catalog.Open")]:
        _fail("the only lock must be EDT's deletion prohibition on Catalog.Open: %r" % blocked.structured)
    if _settled_hashes() != before:
        _fail("a blocked delete must leave the fixture as it was")
    assert_contains(read_fixture_file(SUPPORTED_REL, "src/Catalogs/Open/Open.mdo"), "E2EVendorOpenAttr",
                    "the blocked delete keeps the attribute")

    assert_ok(call("write_module_source", {
        "projectName": SUPPORTED_PROJECT, "modulePath": "CommonModules/Open/Module.bsl",
        "mode": "append", "source": "// e2e vendor open\n"}),
        "append to an editable common module")
    assert_contains(read_fixture_file(SUPPORTED_REL, "src/CommonModules/Open/Module.bsl"),
                    "// e2e vendor open", "the module write reaches disk")
    assert_no_diff("the base project is never touched")


# ==================== resync_to_disk: a locked role keeps its orphans ====================

def _seed_orphan(role):
    rel = "src/Roles/%s/Rights.rights" % role
    text = read_fixture_file(SUPPORTED_REL, rel)
    entry = ("\t<object>\n\t\t<name>Catalog.E2EVendorGone</name>\n\t\t<right>\n\t\t\t<name>Read</name>"
             "\n\t\t\t<value>true</value>\n\t\t</right>\n\t</object>\n")
    if "</Rights>" not in text:
        _fail("setup failed: %s has no closing Rights element" % rel)
    with open(os.path.join(SUPPORTED_DIR, *rel.split("/")), "w", encoding="utf-8", newline="") as f:
        f.write(text.replace("</Rights>", entry + "</Rights>"))


@e2e_test(tool="resync_to_disk", kind="write-metadata")
def test_vendor_orphan_sweep_skips_a_locked_role():
    """An orphaned entry in each role; the sweep removes the editable role's and keeps the locked
    one's, reports why and warns - never an error, and never a write into the locked role."""
    settle_or_fail("the orphan sweep")
    for role in ("LockedRole", "OpenRole"):
        _seed_orphan(role)
    assert_ok(call("clean_project", {"projectName": SUPPORTED_PROJECT}), "re-read the seeded roles")
    settle_or_fail("the re-read")

    r = call("resync_to_disk", {"projectName": SUPPORTED_PROJECT, "cleanOrphanRoleRights": True})
    assert_ok(r, "sweep the orphans")
    sc = r.structured or {}
    entries = {e.get("role"): e for e in (sc.get("orphanRoleRights") or [])
               if e.get("target") == "Catalog.E2EVendorGone"}
    kept = entries.get("Role.LockedRole") or {}
    assert_contains(kept.get("reason") or "", "vendor support", "the kept entry says why")
    assert_contains(sc.get("orphanRoleRightsWarning") or "", "Role.LockedRole",
                    "the warning names the locked role")
    if sc.get("orphanRoleRightsRemovedCount") != 1:
        _fail("exactly the editable role's orphan must be removed: %r" % sc)
    deadline = time.time() + 30
    while "E2EVendorGone" in read_fixture_file(SUPPORTED_REL, "src/Roles/OpenRole/Rights.rights"):
        if time.time() > deadline:
            _fail("the editable role still holds the orphan on disk")
        time.sleep(0.5)
    assert_contains(read_fixture_file(SUPPORTED_REL, "src/Roles/LockedRole/Rights.rights"),
                    "E2EVendorGone", "the locked role's file must keep its entry")
