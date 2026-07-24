"""
e2e tests for ChartOfCalculationTypes (ПланВидовРасчета) PREDEFINED-item authoring
(#293 / PR #296, Phase 2): create_metadata / modify_metadata / get_metadata_details /
delete_metadata on the shared predefined FQN grammar

    ChartOfCalculationTypes.<OwnerName>.Predefined.<ItemName>

This is a deliberately CROSS-TOOL depth file (the documented one-file-per-tool exception,
like test_predefined_items.py / test_modify_metadata_dcs.py): the feature spans four
existing tools, each already covered by its own primary per-tool file, so this file's
tool= tags reuse those already-covered names and add DEPTH for THIS owner kind rather than
duplicating each tool's general contract - the e2e coverage ratchet counts by tool NAME,
so reusing existing names keeps it green.

WHAT THIS SLICE GATES (the sole mechanical gate on the genuinely new mechanic): a
ChartOfCalculationTypes predefined item is FLAT (no folders / no parent nesting) and its
base / displaced / leading properties are NON-containment REFERENCES to OTHER predefined
calc-type items in the SAME chart, resolved by NAME to a live in-resource EObject inside
the write transaction. These tests prove:
  - the three reference arrays each resolve every named entry to a SIBLING item and RENDER
    the resolved target names back through get_metadata_details (reference detection +
    rendering), and full-replace on modify;
  - a non-existent sibling name is an actionable, non-mutating error;
  - the FLAT model rejects isFolder and parent with an actionable owner-scoped error, and
    the CoCalcT-only properties are rejected for any other owner (the owner-gate, mirroring
    Phase 1's applyValueType gate);
  - the delete incoming-reference check surfaces a base/displaced/leading cross-item
    reference: deleting a calc type that a sibling still cites is BLOCKED unless force=true
    (verified live per the delete_metadata guide, which names base/displaced/leading as a
    metadata cross-reference the check must catch). A timeout on that check is a real
    regression in the reference scan, so it FAILS (not skips).

SCOPE NOTE - the numeric code Value path (getCodeType()==Number → precision/scale/
non-negative validation, reusing the Catalog BigDecimal-hardened path) is exercised HERE
only positively with a code valid under either code type; its rejection edges (over-length /
fractional / negative) depend on the chart's codeType, which the top-level create wizard
fixes, so those edges are owned by the writer's UNIT tests (PredefinedWriter reuses the
already-unit-tested Catalog code path verbatim). This e2e file's gate is the reference
mechanic + rendering, not numeric-code arithmetic.

reset: kind="write-metadata" -> the orchestrator runs reset_fixture()+reset_model() after
each test, so every test SEEDS its own fresh ChartOfCalculationTypes top object first.
"""

import time

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_tree_unchanged,
    poll_diff_contains,
    tree_snapshot,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    _fail,
)


def _seed_calc_types(name):
    """Create a fresh ChartOfCalculationTypes top object (the EDT 'New'-wizard defaults) and
    wait for the derived-data rebuild to settle, so the predefined-item creates right after
    do not hit the transient BUILDING write-guard."""
    fqn = "ChartOfCalculationTypes." + name
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, "seed " + fqn)
    wait_for_project_ready()
    return fqn


def _seed_calc_item(owner, item_name, properties=None):
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined." + item_name,
        "properties": properties or [],
    })
    assert_ok(r, "seed predefined calc type " + item_name)
    return r


# ══════════════════════════════════════════════════════════════════════════════
# Happy path: seed -> create sibling calc types -> create a main item that REFERENCES
# them via base/displaced/leading -> details renders the resolved names -> modify
# full-replaces one reference list -> toggle actionPeriodIsBase.
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_cocalc_predefined_reference_cycle():
    owner = _seed_calc_types("E2ECalcRef")

    # Four flat sibling calc types (no folders on a ChartOfCalculationTypes). Distinct,
    # mutually non-substring names so a reference-render assertion cannot be satisfied by
    # an accidental substring of another item's name.
    for sibling in ("SickLeave", "Vacation", "Bonus", "Advance"):
        _seed_calc_item(owner, sibling)

    # The main item references three DIFFERENT siblings, one per list, plus a code valid
    # under either code type (Number 1 or String "1") and actionPeriodIsBase.
    main_fqn = owner + ".Predefined.Salary"
    rc = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": main_fqn,
        "properties": [
            {"name": "code", "value": "1"},
            {"name": "actionPeriodIsBase", "value": True},
            {"name": "base", "value": ["SickLeave"]},
            {"name": "displaced", "value": ["Vacation"]},
            {"name": "leading", "value": ["Bonus"]},
        ],
    })
    assert_ok(rc, "create predefined calc type with base/displaced/leading references")
    assert rc.structured.get("action") == "created", "must report created: %r" % (rc.structured,)
    assert rc.structured.get("kind") == "ChartOfCalculationTypesPredefinedItem", \
        "kind must be the concrete predefined-item EClass: %r" % (rc.structured,)
    poll_diff_contains("Salary", ctx="the new calc-type item must land on disk under the owner")

    # get_metadata_details on the OWNER lists every item (the main one and all four siblings).
    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details, "get_metadata_details on the ChartOfCalculationTypes owner")
    for name in ("Salary", "SickLeave", "Vacation", "Bonus"):
        assert_contains(details.text, name, "owner details must list predefined item " + name)

    # get_metadata_details on the main ITEM renders the resolved reference TARGET names -
    # this is the reference-rendering gate (each list shows its sibling by name).
    item_details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [main_fqn]})
    assert_ok(item_details, "get_metadata_details on the item FQN")
    assert_contains(item_details.text, "## Predefined item:",
                    "must render the single-item view (not the owner section)")
    assert_contains(item_details.text, "SickLeave", "the base reference target name must render")
    assert_contains(item_details.text, "Vacation", "the displaced reference target name must render")
    assert_contains(item_details.text, "Bonus", "the leading reference target name must render")

    # modify FULL-REPLACES the base list ([SickLeave] -> [Advance]); displaced/leading are
    # omitted, so they stay untouched. The single-item view then shows the new base target
    # and no longer the old one (Salary referenced SickLeave ONLY through base).
    rm = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": main_fqn,
        "properties": [{"name": "base", "value": ["Advance"]}],
    })
    assert_ok(rm, "modify base reference list (full-replace)")
    assert "base" in (rm.structured.get("applied") or []), \
        "base must be reported applied: %r" % (rm.structured,)

    item_after = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [main_fqn]})
    assert_ok(item_after, "get_metadata_details after the base full-replace")
    assert_contains(item_after.text, "Advance", "the NEW base reference target must render")
    assert_not_contains(item_after.text, "SickLeave",
                        "the REPLACED base reference must be gone (full-replace semantics)")
    # displaced/leading were untouched by an omitted key.
    assert_contains(item_after.text, "Vacation", "an omitted displaced list must stay untouched")
    assert_contains(item_after.text, "Bonus", "an omitted leading list must stay untouched")

    # actionPeriodIsBase is a plain boolean toggled via modify (applied reports it).
    rt = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": main_fqn,
        "properties": [{"name": "actionPeriodIsBase", "value": False}],
    })
    assert_ok(rt, "modify actionPeriodIsBase")
    assert "actionPeriodIsBase" in (rt.structured.get("applied") or []), \
        "actionPeriodIsBase must be reported applied: %r" % (rt.structured,)


# ══════════════════════════════════════════════════════════════════════════════
# Negatives
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_cocalc_missing_sibling_reference_rejected():
    owner = _seed_calc_types("E2ECalcMissing")

    # The tree is already dirty from the seeded chart - prove the refused create added
    # nothing via a before/after snapshot (assert_no_diff would fail on the seed's own diff).
    snap = tree_snapshot()
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Overtime",
        "properties": [{"name": "base", "value": ["NoSuchCalc"]}],
    })
    err = assert_error(r, "a base reference to a non-existent sibling must be rejected")
    assert_error_quality(err, names=["NoSuchCalc"], suggests=["does not exist"],
                         ctx="the error must name the unresolvable sibling")
    assert_tree_unchanged(snap, "a refused create must not mutate the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_cocalc_is_flat_no_folders_and_no_parent():
    # A ChartOfCalculationTypes predefined tree is FLAT: isFolder and parent are both
    # rejected with an actionable owner-scoped error (never silently dropped).
    owner = _seed_calc_types("E2ECalcFlat")

    # Seed one real sibling up front so the parent-nesting refusal below fails on the FLAT
    # model, not on a missing parent. Snapshot AFTER this seed so the refused-create checks
    # assert the refusals added nothing on top of the already-dirty (seeded) tree.
    _seed_calc_item(owner, "Head")
    snap = tree_snapshot()

    r_folder = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Group",
        "properties": [{"name": "isFolder", "value": True}],
    })
    err_folder = assert_error(r_folder, "isFolder on a ChartOfCalculationTypes item must be rejected (flat)")
    assert_error_quality(err_folder, suggests=["folder"],
                         ctx="the flat-model refusal must mention folders are unsupported")

    r_parent = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Child",
        "properties": [{"name": "parent", "value": "Head"}],
    })
    err_parent = assert_error(r_parent, "parent nesting on a ChartOfCalculationTypes item must be rejected (flat)")
    assert_error_quality(err_parent, suggests=["parent"],
                         ctx="the flat-model refusal must mention parent nesting is unsupported")

    assert_tree_unchanged(snap, "the refused flat-model creates must not mutate the project")


@e2e_test(tool="create_metadata", kind="write-metadata")
def test_cocalc_only_properties_rejected_for_other_owner():
    # The CoCalcT-only properties (base/displaced/leading/actionPeriodIsBase) are rejected
    # for any non-ChartOfCalculationTypes owner - the owner-gate, mirroring Phase 1's
    # applyValueType (ChartOfCharacteristicTypes-only) refusal.
    catalog = "E2ECalcGateCatalog"
    seed = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog." + catalog})
    assert_ok(seed, "seed a Catalog to host the rejected properties")
    wait_for_project_ready()
    owner = "Catalog." + catalog

    snap = tree_snapshot()
    gated = [
        ("base", ["X"]),
        ("displaced", ["X"]),
        ("leading", ["X"]),
        ("actionPeriodIsBase", True),
    ]
    for i, (prop, value) in enumerate(gated):
        r = call("create_metadata", {
            "projectName": PROJECT,
            "fqn": "%s.Predefined.GateItem%d" % (owner, i),
            "properties": [{"name": prop, "value": value}],
        })
        err = assert_error(r, "%s must be rejected on a Catalog predefined item" % prop)
        assert_error_quality(err, suggests=["ChartOfCalculationTypes"],
                             ctx="the owner-gate refusal for '%s' must name the required owner type" % prop)
    assert_tree_unchanged(snap, "the refused owner-gated creates must not mutate the project")


# ══════════════════════════════════════════════════════════════════════════════
# Delete incoming-reference safety (the base/displaced/leading cross-item reference):
# a calc type still cited by a sibling's base list must BLOCK a non-forced delete, and
# force=true must override it. The delete_metadata guide states the reference check
# catches "a ChartOfCalculationTypes item's base/displaced/leading pointing at a sibling
# item", so a timeout is a real REGRESSION in the reference scan, not a skip.
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_cocalc_base_reference_blocks_delete_without_force():
    owner = _seed_calc_types("E2ECalcDelRef")
    target_name = "Basis"          # the item that gets referenced
    target_fqn = owner + ".Predefined." + target_name
    citing_name = "Accrual"        # a sibling whose base list cites the target

    _seed_calc_item(owner, target_name)
    _seed_calc_item(owner, citing_name, [{"name": "base", "value": [target_name]}])
    wait_for_project_ready()

    # Poll with PREVIEW calls (confirm omitted → NON-mutating) until the reference index
    # reports the incoming base reference as blocking. Deliberately NOT polling with
    # confirm=true (that would delete the item the moment it is not yet blocked).
    timeout = 120
    deadline = time.time() + timeout
    found_blocking = False
    while time.time() < deadline:
        preview = call("delete_metadata", {"projectName": PROJECT, "fqn": target_fqn})
        assert_ok(preview, "preview delete while polling for the base cross-reference")
        if preview.structured.get("blocking") is True:
            found_blocking = True
            break
        time.sleep(3)

    if not found_blocking:
        _fail(
            "the base reference to predefined calc type '%s' (from sibling '%s') was not "
            "reported as blocking within %ds. The delete_metadata guide states this metadata "
            "cross-reference must be caught, so a timeout is a regression in the predefined-item "
            "reference scan, NOT an expected skip." % (target_name, citing_name, timeout)
        )

    # confirm=true WITHOUT force must block (a single call now that the index has caught up).
    blocked = call("delete_metadata", {"projectName": PROJECT, "fqn": target_fqn, "confirm": True})
    err = assert_error(blocked, "a referenced calc type delete without force must be blocked")
    assert_error_quality(err, names=[target_name], suggests=["force"],
                         ctx="a blocked delete names the target and points at force=true")
    assert blocked.structured.get("action") == "blocked", \
        "a referenced calc type delete without force must be blocked: %r" % (blocked.structured,)
    assert blocked.structured.get("blocking") is True, \
        "a referenced calc type delete must report blocking=true: %r" % (blocked.structured,)
    assert (blocked.structured.get("blockingReferencesCount") or 0) >= 1, \
        "the blocked delete must report at least one blocking reference: %r" % (blocked.structured,)
    refs = blocked.structured.get("blockingReferences") or []
    assert any(citing_name in str(row) for row in refs), \
        "the blocking reference list must name the citing sibling %r: %r" % (citing_name, refs)

    # The target must survive the blocked delete.
    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details, "get_metadata_details after a blocked delete")
    assert_contains(details.text, target_name,
                    "a blocked delete must NOT remove the still-referenced calc type")

    # force=true overrides the block and executes the delete (leaving the sibling's base
    # reference dangling, as documented).
    forced = call("delete_metadata", {
        "projectName": PROJECT, "fqn": target_fqn, "confirm": True, "force": True,
    })
    assert_ok(forced, "force=true must override the reference block")
    assert forced.structured.get("action") == "executed", \
        "force=true delete must execute despite the blocking reference: %r" % (forced.structured,)
    assert forced.structured.get("forced") is True, \
        "a forced delete must echo forced=true: %r" % (forced.structured,)

    details_after = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details_after, "get_metadata_details after the forced delete")
    assert_not_contains(details_after.text, target_name,
                        "force=true must remove the referenced calc type")
    # The citing sibling itself survives (only its dangling reference was affected).
    assert_contains(details_after.text, citing_name,
                    "the citing sibling must survive its target's forced delete")
