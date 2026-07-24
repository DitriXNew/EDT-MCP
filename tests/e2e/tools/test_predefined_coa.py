"""
e2e tests for ChartOfAccounts (ПланСчетов) PREDEFINED-item authoring (#293 / PR #296,
Phase 3): create_metadata / modify_metadata / get_metadata_details / delete_metadata on
the shared predefined FQN grammar

    ChartOfAccounts.<OwnerName>.Predefined.<ItemName>

Like test_predefined_cocalc.py / test_predefined_items.py this is a deliberately CROSS-TOOL
depth file (the documented one-file-per-tool exception): the feature spans four existing
tools, so this file's tool= tags reuse those already-covered names and add DEPTH for the
ChartOfAccounts owner kind. The e2e coverage ratchet counts by tool NAME, so reusing
existing names keeps it green.

WHAT THIS SLICE GATES: a ChartOfAccounts predefined item carries a String code and a
String order, an accountType enum (ACTIVE / PASSIVE / ACTIVE_PASSIVE, parsed by EXACT
token - never contains() - plus bilingual RU aliases), an offBalance boolean, an
accountingFlags NON-containment reference list (to the chart's own AccountingFlag child
objects, resolved by name), and a CONTAINED childItems account hierarchy (nested via the
create-time 'parent' property, the same mechanic Phase 1's Catalog folders use, but
carried on getChildItems()). These tests prove:
  - the full happy path (code / order / accountType / offBalance / accountingFlags) creates,
    renders through get_metadata_details, and modifies (accountType full-replace);
  - accountType is matched by EXACT token: a substring of a valid token ('ACTIV') is
    REJECTED, and a bilingual RU alias is accepted;
  - the childItems hierarchy nests a child account under an existing parent and a
    parent-account delete CASCADES its child accounts (containment) - the preview's items
    array lists the parent AND every cascaded child;
  - a bad parent name is an actionable, non-mutating error;
  - the ChartOfAccounts-only properties (accountType / offBalance / order / accountingFlags /
    extDimensionTypes) are rejected for any other owner (the owner-gate, mirroring Phase 1's
    applyValueType gate).

SCOPE NOTES:
  - code/order LENGTH validation (getCodeLength()/getOrderLength(), error on overflow, never
    truncate) depends on the chart's wizard-default lengths, so its overflow edge is owned by
    the writer's UNIT tests; this file exercises code/order only with values that fit.
  - the extDimensionTypes CONTAINED rows (each with a characteristicType reference to a
    predefined item of the LINKED ChartOfCharacteristicTypes) require the chart's linked-CCT
    reference to be wired first, which a fresh create_metadata chart does not set and no tool
    in this PR sets deterministically; that FULL happy path + its ExtDimensionType
    .characteristicType delete-ref-safety are proven LIVE/manually per the spec's follow-up
    note. Here extDimensionTypes is covered by its deterministic OWNER-GATE (rejected for a
    non-ChartOfAccounts owner), so the parse key + gate are still ratcheted.

reset: kind="write-metadata" -> reset_fixture()+reset_model() after each test; every test
SEEDS its own fresh ChartOfAccounts top object first.
"""

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
)

# RU accountType alias, built from Unicode code points so this source file stays ASCII
# (mirrors the plugin's own \uXXXX discipline for Cyrillic tokens). "Пассивный" == PASSIVE.
PASSIVE_RU = "".join(chr(c) for c in (
    0x041f, 0x0430, 0x0441, 0x0441, 0x0438, 0x0432, 0x043d, 0x044b, 0x0439))


def _seed_accounts(name):
    """Create a fresh ChartOfAccounts top object (the EDT 'New'-wizard defaults) and wait for
    the derived-data rebuild to settle, so the predefined-item creates right after do not hit
    the transient BUILDING write-guard."""
    fqn = "ChartOfAccounts." + name
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, "seed " + fqn)
    wait_for_project_ready()
    return fqn


def _seed_accounting_flag(owner_fqn, flag_name):
    """Create an AccountingFlag child object on the chart (an inline member, seeded with a bare
    create exactly like test_create_metadata.py does) so a predefined account can reference it
    by name through its accountingFlags list."""
    r = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": "%s.AccountingFlag.%s" % (owner_fqn, flag_name),
    })
    assert_ok(r, "seed AccountingFlag " + flag_name)
    wait_for_project_ready()


# ══════════════════════════════════════════════════════════════════════════════
# Happy path: seed chart + an AccountingFlag -> create a predefined account carrying the
# full deterministic payload -> details renders it -> modify the accountType.
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_coa_predefined_full_cycle():
    owner = _seed_accounts("E2EAccounts")
    _seed_accounting_flag(owner, "Quantity")

    item_fqn = owner + ".Predefined.Cash"
    rc = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": item_fqn,
        "properties": [
            {"name": "code", "value": "51"},
            {"name": "order", "value": "42"},
            {"name": "accountType", "value": "PASSIVE"},
            {"name": "offBalance", "value": True},
            {"name": "accountingFlags", "value": ["Quantity"]},
        ],
    })
    assert_ok(rc, "create predefined account with the full CoA payload")
    assert rc.structured.get("action") == "created", "must report created: %r" % (rc.structured,)
    assert rc.structured.get("kind") == "ChartOfAccountsPredefinedItem", \
        "kind must be the concrete predefined-item EClass: %r" % (rc.structured,)
    poll_diff_contains("Cash", ctx="the new account must land on disk under the owner")

    # get_metadata_details on the OWNER lists the account.
    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details, "get_metadata_details on the ChartOfAccounts owner")
    assert_contains(details.text, "Cash", "owner details must list the new account")

    # Full mode must not carry less (issue #288 lesson) - the section renders there too.
    details_full = call("get_metadata_details",
                        {"projectName": PROJECT, "objectFqns": [owner], "full": True})
    assert_ok(details_full, "get_metadata_details full mode")
    assert_contains(details_full.text, "Cash", "full mode must still render the account")

    # get_metadata_details on the ITEM renders its properties + the resolved accounting-flag name.
    item_details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [item_fqn]})
    assert_ok(item_details, "get_metadata_details on the item FQN")
    assert_contains(item_details.text, "## Predefined item:",
                    "must render the single-item view (not the owner section)")
    assert_contains(item_details.text, "Passive", "the account type must render")
    assert_contains(item_details.text, "42", "the order must render")
    assert_contains(item_details.text, "51", "the code must render")
    assert_contains(item_details.text, "Quantity", "the resolved accounting-flag reference must render")

    # modify the accountType (ACTIVE replaces PASSIVE).
    rm = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": item_fqn,
        "properties": [{"name": "accountType", "value": "ACTIVE"}],
    })
    assert_ok(rm, "modify the account type")
    assert "accountType" in (rm.structured.get("applied") or []), \
        "accountType must be reported applied: %r" % (rm.structured,)

    item_after = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [item_fqn]})
    assert_ok(item_after, "get_metadata_details after the accountType modify")
    assert_contains(item_after.text, "Active", "the NEW account type must render")
    assert_not_contains(item_after.text, "Passive",
                        "the OLD account type must be gone (Passive is not a substring of Active)")


# ══════════════════════════════════════════════════════════════════════════════
# accountType is EXACT-token, never contains() - and accepts a bilingual RU alias.
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_coa_account_type_exact_token_and_bilingual_alias():
    owner = _seed_accounts("E2EAccType")

    # 'ACTIV' is a SUBSTRING of the valid token 'ACTIVE' - a contains()-based parse would
    # wrongly accept it. It must be REJECTED (exact-token match), naming the bad value and
    # pointing at the valid tokens.
    snap = tree_snapshot()
    r_bad = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.BadType",
        "properties": [{"name": "accountType", "value": "ACTIV"}],
    })
    err = assert_error(r_bad, "a substring of a valid accountType token must be rejected (exact match)")
    assert_error_quality(err, names=["ACTIV"], suggests=["ACTIVE"],
                         ctx="the refusal must echo the bad token and list the valid ones")
    assert_tree_unchanged(snap, "a refused create must not mutate the project")

    # A bilingual RU alias ('Пассивный' == PASSIVE) is accepted.
    r_ru = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.RuTyped",
        "properties": [{"name": "accountType", "value": PASSIVE_RU}],
    })
    assert_ok(r_ru, "a Russian accountType alias must be accepted")
    assert r_ru.structured.get("kind") == "ChartOfAccountsPredefinedItem", \
        "kind must be the concrete predefined-item EClass: %r" % (r_ru.structured,)
    poll_diff_contains("RuTyped", ctx="the RU-typed account must land on disk")


# ══════════════════════════════════════════════════════════════════════════════
# childItems hierarchy (contained) + parent-account delete cascade.
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_coa_childitems_hierarchy_and_cascade():
    owner = _seed_accounts("E2EAccHier")

    # A parent account, then a child account nested under it via the create-time 'parent'
    # property (ChartOfAccounts has no folders - any account may be a parent).
    rp = call("create_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Assets"})
    assert_ok(rp, "create the parent account")
    rc = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Cash",
        "properties": [{"name": "parent", "value": "Assets"}],
    })
    assert_ok(rc, "create a child account nested under the parent")

    # The owner listing shows both, and the child's single-item view shows its parent.
    details = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details, "get_metadata_details on the owner")
    assert_contains(details.text, "Assets", "owner details must list the parent account")
    assert_contains(details.text, "Cash", "owner details must list the child account")

    child_details = call("get_metadata_details",
                         {"projectName": PROJECT, "objectFqns": [owner + ".Predefined.Cash"]})
    assert_ok(child_details, "get_metadata_details on the child account")
    assert_contains(child_details.text, "Assets", "the child's view must show its parent account")

    # A bad parent name is an actionable, non-mutating error.
    snap = tree_snapshot()
    r_bad = call("create_metadata", {
        "projectName": PROJECT,
        "fqn": owner + ".Predefined.Orphan",
        "properties": [{"name": "parent", "value": "NoSuchAccount"}],
    })
    err = assert_error(r_bad, "an unknown parent account must be rejected")
    assert_error_quality(err, names=["NoSuchAccount"], suggests=["not found"],
                         ctx="the error must name the unresolvable parent account")
    assert_tree_unchanged(snap, "a refused create must not mutate the project")

    # Deleting the parent account CASCADES its child (getChildItems() containment). The
    # preview's items array lists the parent AND the cascaded child by name.
    preview = call("delete_metadata", {"projectName": PROJECT, "fqn": owner + ".Predefined.Assets"})
    assert_ok(preview, "preview parent-account delete")
    assert preview.structured.get("action") == "preview", \
        "a bare call must preview: %r" % (preview.structured,)
    item_names = [row.get("name") for row in (preview.structured or {}).get("items", [])]
    assert item_names == ["Assets", "Cash"], \
        "the preview items must list the parent and its cascaded child: %r" % (item_names,)
    # The human-readable message must ALSO honestly report the cascade: a ChartOfAccounts parent
    # account is not a folder (isFolder=false) yet its childItems cascade, so the prose must state
    # the nested count and must NOT mislabel the account as a FOLDER.
    preview_msg = (preview.structured or {}).get("message", "")
    assert_contains(preview_msg, "would remove it AND its 1 nested item(s)",
                    "the preview message must report the parent-account cascade count")
    assert_not_contains(preview_msg, "FOLDER",
                        "a ChartOfAccounts account is not a folder and must not be labelled one")

    confirm = call("delete_metadata",
                   {"projectName": PROJECT, "fqn": owner + ".Predefined.Assets", "confirm": True})
    assert_ok(confirm, "confirm parent-account delete cascades its child")

    details_after = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [owner]})
    assert_ok(details_after, "get_metadata_details after the cascade delete")
    assert_not_contains(details_after.text, "Assets", "the deleted parent account must be gone")
    assert_not_contains(details_after.text, "Cash", "the cascaded child account must be gone")


# ══════════════════════════════════════════════════════════════════════════════
# Owner-gate: the ChartOfAccounts-only properties are rejected for any other owner.
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="create_metadata", kind="write-metadata")
def test_coa_only_properties_rejected_for_other_owner():
    catalog = "E2EAccGateCatalog"
    seed = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog." + catalog})
    assert_ok(seed, "seed a Catalog to host the rejected properties")
    wait_for_project_ready()
    owner = "Catalog." + catalog

    snap = tree_snapshot()
    gated = [
        ("accountType", "ACTIVE"),
        ("offBalance", True),
        ("order", "1"),
        ("accountingFlags", ["X"]),
        ("extDimensionTypes", [{"characteristicType": "X", "turnover": True}]),
    ]
    for i, (prop, value) in enumerate(gated):
        r = call("create_metadata", {
            "projectName": PROJECT,
            "fqn": "%s.Predefined.GateItem%d" % (owner, i),
            "properties": [{"name": prop, "value": value}],
        })
        err = assert_error(r, "%s must be rejected on a Catalog predefined item" % prop)
        assert_error_quality(err, suggests=["ChartOfAccounts"],
                             ctx="the owner-gate refusal for '%s' must name the required owner type" % prop)
    assert_tree_unchanged(snap, "the refused owner-gated creates must not mutate the project")
