"""
e2e tests for PREDEFINED-item authoring (#293): create_metadata / modify_metadata /
delete_metadata / get_metadata_details on a dedicated FQN grammar

    <OwnerType>.<OwnerName>.Predefined.<ItemName>

OwnerType in {Catalog, ChartOfCharacteristicTypes}. This is a deliberately CROSS-TOOL
file (the documented one-file-per-tool exception, like test_extension_coverage.py /
test_modify_metadata_dcs.py): the feature spans four existing tools, each of which
already has its own primary per-tool file — this file's tool= tags match those real,
already-covered tool names, so it adds DEPTH for this one feature rather than
duplicating each tool's general contract tests.

ARCHITECTURE (see the frozen spec, .claude/work/293-predefined/spec.md,
"PLAN-REVIEW CORRECTIONS"): the predefined content is a PLAIN EMF CONTAINMENT on the
owner (`MdClass.xcore`: `contains CatalogPredefined predefined`), not a separate
external-property top object. There is therefore no separate content resource to
attach; the owner's OWN FQN is force-exported. The owner's xcore comment notes the
original XML layout "is held in another resource" — the platform serializer MAY still
split the predefined items into a sibling `Predefined.xml` next to the owner's `.mdo`,
or inline them — this is the serializer's business. The disk assertion below therefore
does NOT hardcode a filename: it walks the owner's whole `src/Catalogs/<Name>/`
subtree and accepts a hit in ANY file (tolerates either layout).

No autonumbering: an omitted `code` is left UNSET (never invented). `code` is STRICT
JSON-typed, matched to the owner's code type (a Catalog's String/Number `codeType`; a
ChartOfCharacteristicTypes' plain String). A fresh Catalog created via create_metadata
gets the SAME "New"-wizard defaults create_metadata's top-level path always produces
(codeType=String, codeLength=9) — the happy-path code value below is chosen to fit.

Fixture: NO Catalog / ChartOfCharacteristicTypes with predefined items ships in
TestConfiguration, so every test SEEDS its own fresh top object with create_metadata
first (reverted by the write-metadata reset like every other seeded-object test in
this suite).

reset: kind="write-metadata" -> the orchestrator runs reset_fixture()+reset_model()
after each test.
"""

import os
import time

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    assert_tree_unchanged,
    poll_diff_contains,
    tree_snapshot,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    PROJECT_DIR,
    _fail,
)


def _seed_catalog(name):
    """Creates a fresh Catalog top object (the EDT 'New'-wizard defaults: codeType=String,
    codeLength=9) and waits for the derived-data rebuild to settle, so the predefined-item
    creates right after it do not hit the transient BUILDING write-guard."""
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog." + name})
    assert_ok(r, "seed catalog Catalog." + name)
    wait_for_project_ready()
    return "Catalog." + name


def _seed_characteristic_types(name):
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "ChartOfCharacteristicTypes." + name})
    assert_ok(r, "seed ChartOfCharacteristicTypes." + name)
    wait_for_project_ready()
    return "ChartOfCharacteristicTypes." + name


def _poll_name_under_catalog_dir(catalog_name, needle, timeout=15):
    """Polls the owner's WHOLE on-disk subtree (src/Catalogs/<Name>/) for `needle` in ANY
    file — tolerates the predefined content landing inline in the owner's own .mdo OR split
    into a sibling Predefined.xml (the serializer's choice, per the xcore comment); the item
    name must appear in SOME file either way. Never hardcodes a filename."""
    base = os.path.join(PROJECT_DIR, "src", "Catalogs", catalog_name)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if os.path.isdir(base):
            for root, _dirs, files in os.walk(base):
                for fn in files:
                    full = os.path.join(root, fn)
                    try:
                        with open(full, encoding="utf-8", errors="replace") as f:
                            if needle in f.read():
                                return
                    except OSError:
                        pass
        time.sleep(0.5)
    _fail("expected %r to appear in SOME file under src/Catalogs/%s/ but it did not "
          "(checked for %ds)" % (needle, catalog_name, timeout))


# ══════════════════════════════════════════════════════════════════════════════
# Happy path: seed -> create folder -> create item (parent + code + description) ->
# details (owner section + item FQN) -> modify -> delete -> details no longer shows it
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_full_predefined_item_cycle():
    catalog = "E2EPredefCatalog"
    owner = _seed_catalog(catalog)

    # 1) Create a FOLDER.
    rf = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Warm",
        "properties": [{"name": "isFolder", "value": True}],
    })
    assert_ok(rf, "create predefined FOLDER Warm")
    assert rf.structured.get("action") == "created", "must report created: %r" % (rf.structured,)
    assert rf.structured.get("kind") == "CatalogPredefinedItem", \
        "kind must be the concrete predefined-item EClass: %r" % (rf.structured,)

    # 2) Create an item INTO that folder, with code + description.
    rc = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Red",
        "properties": [
            {"name": "parent", "value": "Warm"},
            {"name": "code", "value": "001"},
            {"name": "description", "value": "Bright red"},
        ],
    })
    assert_ok(rc, "create predefined item Red under Warm")
    assert rc.structured.get("action") == "created", "must report created: %r" % (rc.structured,)

    # The predefined content is a PLAIN containment on the owner (#293) -> only the OWNER's
    # own .mdo/on-disk subtree is dirtied; force-export targets the owner FQN. Tolerates either
    # an inlined .mdo or a sibling Predefined.xml (the serializer's choice).
    poll_diff_contains("Warm", ctx="the new folder must land on disk somewhere under the owner")
    _poll_name_under_catalog_dir(catalog, "Red")
    _poll_name_under_catalog_dir(catalog, "001")

    # 3) get_metadata_details on the OWNER renders the "Predefined items" section (both items,
    # the child's Parent column pointing at the folder).
    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details, "get_metadata_details on the owner")
    assert_contains(details.text, "Predefined items", "owner details must render the section header")
    assert_contains(details.text, "Warm", "owner details must list the folder")
    assert_contains(details.text, "Red", "owner details must list the nested item")
    assert_contains(details.text, "Bright red", "owner details must show the item's description")

    # Full mode must NOT carry less (issue #288 lesson) - the section renders there too.
    details_full = call("get_metadata_details",
                        {"projectName": PROJECT, "objectFqns": [owner], "full": True})
    assert_ok(details_full, "get_metadata_details full mode")
    assert_contains(details_full.text, "Predefined items", "full mode must still render the section")

    # 4) get_metadata_details on the ITEM FQN renders just that one item's properties.
    item_fqn = owner + ".Predefined.Red"
    item_details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [item_fqn]})
    assert_ok(item_details, "get_metadata_details on the item FQN")
    # The exact single-item HEADING, not the bare words: "Predefined item" is a substring of the
    # owner section's "Predefined items", so a dispatch regression to the owner render would
    # otherwise slip through this assertion.
    assert_contains(item_details.text, "## Predefined item:", "must render the single-item view")
    assert_contains(item_details.text, "001", "must show the item's code")
    assert_contains(item_details.text, "Bright red", "must show the item's description")
    assert_contains(item_details.text, "Warm", "must show the item's parent")

    # 5) modify_metadata changes the description.
    rm = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": item_fqn,
        "properties": [{"name": "description", "value": "Crimson red"}],
    })
    assert_ok(rm, "modify predefined item description")
    assert rm.structured.get("action") == "modified", "must report modified: %r" % (rm.structured,)

    item_details_after_modify = call("get_metadata_details",
                                     {"projectName": PROJECT, "objectFqns": [item_fqn]})
    assert_ok(item_details_after_modify, "get_metadata_details after modify")
    assert_contains(item_details_after_modify.text, "Crimson red",
                    "the modified description must be visible")
    assert_not_contains(item_details_after_modify.text, "Bright red",
                        "the OLD description must be gone")

    # 6) delete_metadata: preview first (no mutation), then confirm. The tree is already dirty
    # from the seeding above, so no-mutation is proven by a before/after SNAPSHOT around the
    # preview call, not by assert_no_diff (which demands a fully clean tree).
    snap_before_preview = tree_snapshot()
    preview = call("delete_metadata", {"projectName": PROJECT, "fqn": item_fqn})
    assert_ok(preview, "preview delete of the predefined item")
    assert preview.structured.get("action") == "preview", \
        "a bare call must preview, not execute: %r" % (preview.structured,)
    assert_tree_unchanged(snap_before_preview,
                          "a preview (confirm absent) must not mutate the model")

    confirm = call("delete_metadata", {"projectName": PROJECT, "fqn": item_fqn, "confirm": True})
    assert_ok(confirm, "confirm delete of the predefined item")
    assert confirm.structured.get("action") == "executed", \
        "confirm=true must execute: %r" % (confirm.structured,)

    # 7) get_metadata_details on the owner no longer shows the deleted item, but the folder
    # (a sibling, untouched) survives.
    details_after_delete = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details_after_delete, "get_metadata_details after delete")
    assert_not_contains(details_after_delete.text, "Red",
                        "the deleted item must no longer be listed")
    assert_contains(details_after_delete.text, "Warm",
                    "the sibling folder must survive a targeted item delete")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_folder_delete_cascades_children_preview_reports_count():
    catalog = "E2EPredefCascade"
    owner = _seed_catalog(catalog)

    call("create_metadata", {
        "projectName": PROJECT, "fqn": owner + ".Predefined.Group",
        "properties": [{"name": "isFolder", "value": True}],
    })
    for name in ("Item1", "Item2"):
        r = call("create_metadata", {
            "projectName": PROJECT, "fqn": owner + ".Predefined." + name,
            "properties": [{"name": "parent", "value": "Group"}],
        })
        assert_ok(r, "seed child " + name)

    preview = call("delete_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Group"})
    assert_ok(preview, "preview folder delete")
    assert preview.structured.get("action") == "preview", \
        "a bare call must preview: %r" % (preview.structured,)
    # The preview message must warn about the cascade with the exact count PHRASE, anchored on
    # both sides ("its 2 nested item(s)") - a bare "2" would match the '2' in this owner's own
    # name, and an unanchored "2 nested item" would still match a wrong "12 nested item(s)".
    msg = (preview.structured or {}).get("message", "")
    assert_contains(msg, "its 2 nested item(s)",
                    "the preview must report the exact nested-item cascade count")
    # And the structured items must list the folder AND every cascaded descendant by name.
    item_names = [row.get("name") for row in (preview.structured or {}).get("items", [])]
    assert item_names == ["Group", "Item1", "Item2"], \
        "preview items must list the folder and both cascaded children: %r" % (item_names,)

    confirm = call("delete_metadata",
                   {"projectName": PROJECT, "fqn": owner + ".Predefined.Group", "confirm": True})
    assert_ok(confirm, "confirm folder delete cascades children")

    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details, "get_metadata_details after cascade delete")
    assert_not_contains(details.text, "Item1", "cascaded child Item1 must be gone")
    assert_not_contains(details.text, "Item2", "cascaded child Item2 must be gone")
    assert_not_contains(details.text, "Group", "the deleted folder itself must be gone")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_characteristic_types_predefined_item():
    # ChartOfCharacteristicTypes' code is a plain String (no codeType matching needed).
    types = "E2EPredefTypes"
    owner = _seed_characteristic_types(types)

    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Weight",
        "properties": [{"name": "code", "value": "W1"}],
    })
    assert_ok(r, "create predefined item on ChartOfCharacteristicTypes")
    assert r.structured.get("kind") == "ChartOfCharacteristicTypesPredefinedItem", \
        "kind must be the concrete EClass: %r" % (r.structured,)

    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details, "get_metadata_details on ChartOfCharacteristicTypes owner")
    assert_contains(details.text, "Weight", "must list the new item")
    assert_contains(details.text, "W1", "must show its code")


# ══════════════════════════════════════════════════════════════════════════════
# Negatives
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_chart_of_accounts_predefined_is_deferred_not_faked():
    # The owner-TYPE gate runs before any owner-existence lookup, so a non-existent
    # ChartOfAccounts name still gets the actionable "not yet supported" refusal (never a
    # false "owner not found", and never silently faked support).
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "ChartOfAccounts.NoSuchChart.Predefined.Cash",
    })
    err = assert_error(r, "ChartOfAccounts predefined item must be refused")
    assert_error_quality(err, suggests=["not yet supported"])
    assert_no_diff("a refused create must not mutate the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_document_owner_kind_is_rejected():
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "Document.NoSuchDoc.Predefined.X",
    })
    err = assert_error(r, "a Document owner must be rejected (no predefined items)")
    assert_error_quality(err, suggests=["predefined items"])
    assert_no_diff("a refused create must not mutate the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_duplicate_predefined_item_is_rejected():
    catalog = "E2EPredefDup"
    owner = _seed_catalog(catalog)
    r1 = call("create_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Blue"})
    assert_ok(r1, "seed the first item")

    r2 = call("create_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Blue"})
    err = assert_error(r2, "an exact-name duplicate must be rejected")
    assert_error_quality(err, names=["Blue"], suggests=["already exists"])


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_bad_parent_is_rejected():
    catalog = "E2EPredefBadParent"
    owner = _seed_catalog(catalog)

    # The tree is already dirty from the seeded catalog - prove the refused create added nothing
    # via a before/after snapshot (assert_no_diff would fail on the seed's own diff).
    snap = tree_snapshot()
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Child",
        "properties": [{"name": "parent", "value": "NoSuchFolder"}],
    })
    err = assert_error(r, "an unknown parent folder must be rejected")
    assert_error_quality(err, names=["NoSuchFolder"], suggests=["not found"])
    assert_tree_unchanged(snap, "a refused create must not mutate the project")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_rename_via_modify_is_refused():
    catalog = "E2EPredefRename"
    owner = _seed_catalog(catalog)
    call("create_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Blue"})

    r = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Blue",
        "properties": [{"name": "name", "value": "Navy"}],
    })
    err = assert_error(r, "renaming a predefined item must be refused")
    assert_error_quality(err, suggests=["not supported"])


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_parent_move_via_modify_is_refused():
    catalog = "E2EPredefMove"
    owner = _seed_catalog(catalog)
    call("create_metadata", {
        "projectName": PROJECT, "fqn": owner + ".Predefined.Group",
        "properties": [{"name": "isFolder", "value": True}],
    })
    call("create_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Blue"})

    r = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Blue",
        "properties": [{"name": "parent", "value": "Group"}],
    })
    err = assert_error(r, "moving a predefined item via modify must be refused")
    assert_error_quality(err, suggests=["not yet supported"])


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_delete_nonexistent_predefined_item_is_rejected():
    catalog = "E2EPredefDelMissing"
    owner = _seed_catalog(catalog)

    r = call("delete_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.NoSuchItem"})
    err = assert_error(r, "deleting a non-existent predefined item must fail")
    assert_error_quality(err, names=["NoSuchItem"], suggests=["not found"])


# ══════════════════════════════════════════════════════════════════════════════
# CCT valueType (issue #296 P2): a ChartOfCharacteristicTypesPredefinedItem's VALUE TYPE
# (getType()/setType(TypeDescription)), built via the SAME {types:[{kind, ...}]} payload
# shape an mdclass attribute's `type` property uses. CCT-only - rejected outright for a
# Catalog predefined item.
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_characteristic_types_value_type_create_details_modify():
    types = "E2EPredefValueType"
    owner = _seed_characteristic_types(types)
    item_fqn = owner + ".Predefined.Weight"

    # 1) Create with a String(50) value type.
    rc = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": item_fqn,
        "properties": [{"name": "valueType", "value": {"types": [{"kind": "String", "length": 50}]}}],
    })
    assert_ok(rc, "create CCT predefined item with a valueType")
    poll_diff_contains("Weight", ctx="the new item must land on disk")

    # 2) The owner's "Predefined items" table renders a Type column carrying the value type.
    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details, "get_metadata_details on the owner")
    assert_contains(details.text, "Weight", "owner details must list the new item")
    assert_contains(details.text, "String", "owner Predefined items table must show the value type")

    # 3) The single-item view renders a "Value type" row.
    item_details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [item_fqn]})
    assert_ok(item_details, "get_metadata_details on the item FQN")
    assert_contains(item_details.text, "Value type", "single-item view must render the Value type row")
    assert_contains(item_details.text, "String", "single-item view must show the String value type")

    # 4) modify_metadata re-sets it (alias 'type', same shape) to a different kind.
    rm = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": item_fqn,
        "properties": [{"name": "type", "value": {"types": [{"kind": "Boolean"}]}}],
    })
    assert_ok(rm, "modify predefined item valueType via the 'type' alias")
    assert "valueType" in (rm.structured.get("applied") or []), \
        "valueType must be reported applied: %r" % (rm.structured,)

    item_details_after = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [item_fqn]})
    assert_ok(item_details_after, "get_metadata_details after modify")
    assert_contains(item_details_after.text, "Boolean", "the NEW value type must be visible")

    # 5) An explicit JSON null CLEARS the value type.
    r_clear = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": item_fqn,
        "properties": [{"name": "valueType", "value": None}],
    })
    assert_ok(r_clear, "clear the value type with an explicit null")

    item_details_cleared = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [item_fqn]})
    assert_ok(item_details_cleared, "get_metadata_details after clearing valueType")
    assert_not_contains(item_details_cleared.text, "Boolean",
                        "the cleared value type must no longer be shown")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_value_type_rejected_for_catalog_item():
    catalog = "E2EPredefValueTypeCatalog"
    owner = _seed_catalog(catalog)

    # The tree is already dirty from the seeded catalog - prove the refused create added
    # nothing via a before/after snapshot (assert_no_diff would fail on the seed's own diff).
    snap = tree_snapshot()
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Blue",
        "properties": [{"name": "valueType", "value": {"types": [{"kind": "String"}]}}],
    })
    err = assert_error(r, "valueType on a Catalog predefined item must be rejected")
    assert_error_quality(err, suggests=["ChartOfCharacteristicTypes"])
    assert_tree_unchanged(snap, "a refused create must not mutate the project")

    # Same refusal on modify_metadata, against an already-existing plain item.
    call("create_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Blue"})
    rm = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Blue",
        "properties": [{"name": "valueType", "value": {"types": [{"kind": "String"}]}}],
    })
    err_mod = assert_error(rm, "valueType via modify_metadata on a Catalog item must be rejected too")
    assert_error_quality(err_mod, suggests=["ChartOfCharacteristicTypes"])


# ══════════════════════════════════════════════════════════════════════════════
# Incoming-reference check (fix-round on the #293 predefined-item delete safety check):
# collectPredefinedItemBlockingReferences reuses MetadataReferenceService's metadata+BSL
# reference-collection engine (the same one find_references uses). These tests cover:
#   - a deterministic NEGATIVE: a pristine, unreferenced item deletes cleanly, blocking=false
#     (the reference check must never false-positive on the item's own structural
#     owner-source linkage - the narrowed isOwnerSelfReference exclusion).
#   - a REAL incoming reference (a scratch CommonModule's BSL code reading the item via its
#     owner's manager member, e.g. `Catalogs.X.Item`) must BLOCK a non-forced delete and be
#     overridable with force=true. This capability is CONFIRMED on the live stand: the Xtext
#     scope provider resolves the usage to the predefined item's own EMF URI, so the scan's
#     collectBslReferences finds it (~5s). The test therefore FAILS (not skips) if the
#     reference is not reported within the poll budget - a timeout is a real regression in the
#     reference scan's BSL URI resolution / indexing, which would let a referenced item be deleted.
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_unreferenced_predefined_item_deletes_without_blocking():
    # Deterministic baseline: the reference check must never false-positive on a pristine
    # item - it must report blocking=false and delete cleanly with no force needed.
    catalog = "E2EPredefNoRef"
    owner = _seed_catalog(catalog)
    item_fqn = owner + ".Predefined.Lonely"

    rc = call("create_metadata", {"projectName": PROJECT, "fqn": item_fqn})
    assert_ok(rc, "seed an unreferenced predefined item")

    preview = call("delete_metadata", {"projectName": PROJECT, "fqn": item_fqn})
    assert_ok(preview, "preview delete of an unreferenced predefined item")
    assert preview.structured.get("blocking") is False, \
        "a pristine, unreferenced predefined item must never be reported as blocking: %r" % (preview.structured,)
    assert not (preview.structured.get("blockingReferences") or []), \
        "blockingReferences must be empty for an unreferenced item: %r" % (preview.structured,)

    confirm = call("delete_metadata", {"projectName": PROJECT, "fqn": item_fqn, "confirm": True})
    assert_ok(confirm, "confirm delete of an unreferenced predefined item")
    assert confirm.structured.get("action") == "executed", \
        "an unreferenced item's delete must execute cleanly (no force needed): %r" % (confirm.structured,)


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_predefined_item_bsl_reference_blocks_delete_without_force():
    catalog = "E2EPredefBslRef"
    owner = _seed_catalog(catalog)
    item_name = "Anchor"
    item_fqn = owner + ".Predefined." + item_name
    module_name = "E2EPredefBslRefModule"
    module_fqn = "CommonModule." + module_name

    rc = call("create_metadata", {"projectName": PROJECT, "fqn": item_fqn})
    assert_ok(rc, "seed the predefined item " + item_name)

    rm = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": module_fqn,
        "commonModuleKind": "ServerCall",
    })
    assert_ok(rm, "seed a scratch CommonModule to hold the referencing BSL")
    wait_for_project_ready()

    try:
        # A real incoming BSL reference: the module reads the predefined item via its
        # owner's dynamic manager member (English dialect keeps the source ASCII).
        source = (
            "Function GetAnchor() Export\n"
            "\tReturn Catalogs.%s.%s;\n"
            "EndFunction\n" % (catalog, item_name)
        )
        rw = call("write_module_source", {
            "projectName": PROJECT,
            "objectName": module_fqn,
            "moduleType": "Module",
            "mode": "replace",
            "overwrite": True,
            "source": source,
        })
        assert_ok(rw, "write BSL referencing the predefined item")
        poll_diff_contains("GetAnchor", ctx="the referencing module source must land on disk")
        wait_for_project_ready()

        # Poll with PREVIEW calls (confirm omitted) - NON-mutating - until the Xtext BSL index
        # has (re)indexed the new module and the reference-collection engine reports blocking.
        # Deliberately NOT polling with confirm=true: that call EXECUTES immediately whenever
        # it is not (yet) blocked, which would delete the item for real on the very first
        # attempt and defeat the whole point of waiting for the index to catch up.
        timeout = 120
        deadline = time.time() + timeout
        found_blocking = False
        while time.time() < deadline:
            preview = call("delete_metadata", {"projectName": PROJECT, "fqn": item_fqn})
            assert_ok(preview, "preview delete while polling for the BSL reference")
            if preview.structured.get("blocking") is True:
                found_blocking = True
                break
            time.sleep(3)

        if not found_blocking:
            # This capability is CONFIRMED on the live stand (the Xtext scope provider resolves
            # `Catalogs.X.Item` to the predefined item's own EMF URI, so collectBslReferences on that
            # URI finds the module reference in ~5s). A timeout here is therefore a real REGRESSION
            # (a URI-resolution / indexing break that would let a referenced item be deleted), NOT a
            # skip - fail hard so the pre-push suite catches it.
            _fail(
                "the BSL reference to the predefined item (Catalogs.%s.%s from CommonModule.%s) was "
                "not reported as blocking within %ds. This capability is verified live, so a timeout "
                "means a regression in the predefined-item reference scan (BSL URI resolution / index)."
                % (catalog, item_name, module_name, timeout)
            )

        # Now prove the confirm=true (no force) path ALSO blocks - a single call, now that the
        # preview above confirmed the index has caught the reference.
        blocked = call("delete_metadata", {"projectName": PROJECT, "fqn": item_fqn, "confirm": True})
        err = assert_error(blocked, "a BSL-referenced predefined item delete without force must be blocked")
        assert_error_quality(err, names=[item_name], suggests=["force"],
                             ctx="a blocked predefined-item delete names the target and points at force=true")
        assert blocked.structured.get("action") == "blocked", \
            "a BSL-referenced predefined item delete without force must be blocked: %r" % (blocked.structured,)
        assert blocked.structured.get("blocking") is True, \
            "a BSL-referenced predefined item delete must report blocking=true: %r" % (blocked.structured,)
        assert (blocked.structured.get("blockingReferencesCount") or 0) >= 1, \
            "the blocked delete must report at least one blocking reference: %r" % (blocked.structured,)
        refs = blocked.structured.get("blockingReferences") or []
        assert any(module_name in str(row) for row in refs), \
            "the blocking reference list must name the referencing module %r: %r" % (module_name, refs)
        # The item must survive an unforced, blocked delete.
        details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
        assert_ok(details, "get_metadata_details after a blocked delete")
        assert_contains(details.text, item_name,
                        "a blocked delete must NOT remove the still-referenced predefined item")

        # force=true must override the block and execute the delete.
        rf = call("delete_metadata", {
            "projectName": PROJECT, "fqn": item_fqn, "confirm": True, "force": True,
        })
        assert_ok(rf, "force=true must override the reference block")
        assert rf.structured.get("action") == "executed", \
            "force=true delete must execute despite the blocking reference: %r" % (rf.structured,)
        assert rf.structured.get("forced") is True, "a forced delete must echo forced=true: %r" % (rf.structured,)

        details_after_force = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
        assert_ok(details_after_force, "get_metadata_details after the forced delete")
        assert_not_contains(details_after_force.text, item_name,
                            "force=true must remove the predefined item")
    finally:
        # Clean up the scratch module (the seeded catalog is reverted by the write-metadata
        # reset like every other seeded-object test in this file). Best-effort: the item/module
        # may already be gone depending on which branch above ran.
        call("delete_metadata", {"projectName": PROJECT, "fqn": module_fqn, "confirm": True})
