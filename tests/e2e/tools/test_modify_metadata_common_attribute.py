"""
e2e tests for modify_metadata attaching / detaching an owner in a CommonAttribute's content list.

A common attribute (`CommonAttribute.<Name>`) is shared across many objects; which objects carry it
- its OWNERS - live in the common attribute's `content` list, each with a per-owner `use` flag. This
is edited through a sibling `content` payload (mirroring the Role `rights[]` precedent), NOT through
`properties`:

    content=[{op?:'add'|'remove' (default add), metadata:'Catalog.X', use?:'Use'|'DontUse'|'Auto'}]

Adding is idempotent (an already-listed owner has its `use` UPDATED, counted under `updated` rather
than `added`); removing detaches by the owner FQN. The change goes through a BM write transaction and
force-exports the CommonAttribute `.mdo`. The success payload carries a `content` counts object
{added, updated, removed}. Read the current content with get_metadata_details on the CommonAttribute
FQN (a "Content" section whose Metadata / Use rows are round-trippable into this payload).

reset: kind="write-metadata" -> reset_fixture()+reset_model() after each test.

Fixture (shipped TestConfiguration): CommonAttribute.CommonAttribute has NO <content> yet and uses
dataSeparation=DontUse; Catalog.Catalog is a valid owner for that simple common attribute;
CommonModule.OK is a NON-owner kind. The fixture has no Constant and no separator common attribute,
so the separator/Constant scenario creates both inside its test.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    poll_diff_contains,
    poll_disk_lacks,
    read_disk,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)

# The shipped common attribute (no content in the fixture) and the on-disk file that owns its content.
CA = "CommonAttribute.CommonAttribute"
CA_MDO = "src/CommonAttributes/CommonAttribute/CommonAttribute.mdo"
OWNER = "Catalog.Catalog"                       # a valid owner kind present in the fixture
# The structural on-disk element that pins WHICH owner is attached (never the bare name, which would
# false-match a collection reference elsewhere). This is the load-bearing add/remove proof.
OWNER_MDO_TOKEN = "<metadata>Catalog.Catalog</metadata>"


def _details_text(fqn=CA):
    r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
    assert_ok(r, "get_metadata_details for " + fqn)
    return r.text


def _create_ok(fqn, ctx):
    """Create setup metadata and wait until the dependent write guard permits the next mutation."""
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, ctx)
    wait_for_project_ready()
    return r


def _common_attribute_mdo(name):
    return "src/CommonAttributes/%s/%s.mdo" % (name, name)


# ──────────────────────────────────────────────────────────────────────────────
# Happy — attach an owner: the <content> block lands on disk AND the model shows it
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_add_owner_persists_and_get_details_renders_content_columns():
    # Precondition (anti-cheat): the owner is NOT already attached, so a no-op would FAIL the diff.
    before = _details_text()
    assert "### Content" not in before, \
        "the fixture common attribute must start with an EMPTY content list:\n%s" % before[:400]

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"op": "add", "metadata": OWNER, "use": "Use"}],
    })
    assert_ok(r, "attach Catalog.Catalog as an owner of the common attribute")
    assert r.structured.get("action") == "modified", "must report modified: %r" % (r.structured,)
    counts = r.structured.get("content") or {}
    assert counts.get("added") == 1, "exactly one owner must be added: %r" % (r.structured,)
    assert counts.get("updated") == 0 and counts.get("removed") == 0, \
        "a fresh add must not update / remove: %r" % (r.structured,)

    # (1) ON-DISK structure: the owner FQN lands inside a <content> block in the CommonAttribute .mdo.
    poll_diff_contains(OWNER_MDO_TOKEN,
                       ctx="the attached owner must land in a <content> block on disk")
    # (2) MODEL read-back: the Content table exposes the round-trippable owner FQN and its use in
    # the correct columns. The final assertion is the anti-cheat: the pre-fix generic EObject
    # formatter emitted "| Use | CommonAttributeContentItem |" instead.
    after = _details_text()
    assert_contains(after, "### Content", "the model read-back must show the Content section")
    assert_contains(after, "| Metadata | Use |",
                    "the Content table must name the owner and use columns")
    assert_contains(after, "| Catalog.Catalog | Use |",
                    "the attached owner FQN and use must render in the matching columns")
    assert_not_contains(after, "CommonAttributeContentItem",
                        "the generic EObject EClass value must not leak into Content")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — a separator common attribute accepts a Constant owner (#507)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_separator_accepts_constant_owner():
    separator_name = "E2EDataSeparator507"
    constant_name = "E2EDataSeparatorConstant507"
    separator_fqn = "CommonAttribute." + separator_name
    constant_fqn = "Constant." + constant_name
    owner_token = "<metadata>%s</metadata>" % constant_fqn

    _create_ok(constant_fqn, "create the Constant owner for the separator")
    _create_ok(separator_fqn, "create the common attribute that will become a separator")

    make_separator = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": separator_fqn,
        "properties": [{"name": "dataSeparation", "value": "Separate"}],
    })
    assert_ok(make_separator, "set the target common attribute dataSeparation to Separate")
    assert make_separator.structured.get("persisted") is True, \
        "the separator property must persist before attaching an owner: %r" % (make_separator.structured,)
    wait_for_project_ready()

    before = _details_text(separator_fqn)
    assert "CommonAttributeContentItem" not in before, \
        "the new separator must start with no content rows:\n%s" % before[:500]

    attach = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": separator_fqn,
        "content": [{"metadata": constant_fqn, "use": "Use"}],
    })
    assert_ok(attach, "attach a Constant owner to a separator common attribute")
    counts = attach.structured.get("content") or {}
    # THE assertion of #507: a Constant is only in EDT's data-separable owner set, so an accepted
    # attach proves the writer read the target's live dataSeparation and picked the separator
    # predicate. Before the fix this call was refused outright.
    assert counts.get("added") == 1 and counts.get("updated") == 0 and counts.get("removed") == 0, \
        "the Constant must be attached as one fresh owner: %r" % (attach.structured,)
    assert attach.structured.get("persisted") is True, \
        "the Constant owner must persist to disk: %r" % (attach.structured,)

    # DISK FIRST: modify_metadata submitted and drained this target's export. The exact owner element
    # proves the Constant landed in this common attribute's content rather than merely existing.
    on_disk = read_disk(_common_attribute_mdo(separator_name))
    # NOT asserted: a literal "<dataSeparation>Separate</dataSeparation>" line. EDT omits the
    # property when it equals the serialization default, and Separate IS that default - the whole
    # ERP configuration shows the same shape, where every real separator has no <dataSeparation>
    # line at all while the four language common attributes write <dataSeparation>DontUse</...>
    # explicitly. What the disk CAN prove is the negative: the target is not a simple attribute.
    assert "<dataSeparation>DontUse</dataSeparation>" not in on_disk, \
        "the target must not have fallen back to a simple (DontUse) common attribute:\n%s" \
        % on_disk[:600]
    assert owner_token in on_disk, \
        "the Constant owner must land in the separator's <content> block on disk"

    # MODEL read-back: the Content table names the attached owner and its use. Written against the
    # pre-#505 output this asserted one raw "CommonAttributeContentItem" EClass row; #505 replaced
    # that fallback with the named row, so the anti-cheat is now the reverse - the EClass name must
    # be gone AND exactly one owner row must carry this Constant.
    after = _details_text(separator_fqn)
    assert after.count("| %s | Use |" % constant_fqn) == 1, \
        "get_metadata_details must read back exactly one attached owner row:\n%s" % after[:700]
    assert_not_contains(after, "CommonAttributeContentItem",
                        "the generic EObject EClass value must not leak into Content")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — remove remains a cleanup operation after the target kind makes the row illegal
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_remove_owner_illegal_for_current_target_kind_succeeds():
    attribute_name = "E2EIllegalOwnerCleanup507"
    constant_name = "E2EIllegalOwnerCleanupConstant507"
    attribute_fqn = "CommonAttribute." + attribute_name
    constant_fqn = "Constant." + constant_name
    owner_token = "<metadata>%s</metadata>" % constant_fqn
    attribute_mdo = _common_attribute_mdo(attribute_name)

    _create_ok(constant_fqn, "create the separator-only Constant owner")
    _create_ok(attribute_fqn, "create the common attribute for the cleanup case")

    make_separator = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": attribute_fqn,
        "properties": [{"name": "dataSeparation", "value": "Separate"}],
    })
    assert_ok(make_separator, "make the target a separator before attaching the Constant")
    wait_for_project_ready()

    attach = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": attribute_fqn,
        "content": [{"op": "add", "metadata": constant_fqn, "use": "Use"}],
    })
    assert_ok(attach, "attach the Constant while its class is legal for the target")
    assert (attach.structured.get("content") or {}).get("added") == 1, \
        "the setup must attach exactly one Constant owner: %r" % (attach.structured,)

    make_simple = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": attribute_fqn,
        "properties": [{"name": "dataSeparation", "value": "DontUse"}],
    })
    assert_ok(make_simple, "change the populated target to the simple common-attribute kind")
    wait_for_project_ready()

    before_remove = read_disk(attribute_mdo)
    assert "<dataSeparation>DontUse</dataSeparation>" in before_remove, \
        "the target must now be simple before exercising the cleanup path:\n%s" % before_remove[:700]
    assert owner_token in before_remove, \
        "the now-illegal Constant row must still exist before the removal call"

    # WHY: an add must reject this Constant for a simple target, but applying that same predicate to
    # remove creates a dead end: the only operation that can repair the invalid row would reject it.
    # Removal therefore still resolves the real owner and listed row, while deliberately skipping
    # the owner-class rule selected by the target's CURRENT dataSeparation.
    remove = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": attribute_fqn,
        "content": [{"op": "remove", "metadata": constant_fqn}],
    })
    assert_ok(remove, "remove the Constant that is illegal for the target's current simple kind")
    counts = remove.structured.get("content") or {}
    assert counts.get("removed") == 1 and counts.get("added") == 0 and counts.get("updated") == 0, \
        "the cleanup call must remove exactly the obsolete row: %r" % (remove.structured,)
    poll_disk_lacks(attribute_mdo, owner_token,
                    ctx="the now-illegal Constant owner row must be removable from disk")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — op defaults to 'add' when omitted (the doc's default), owner still attached
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_add_owner_op_defaults_to_add():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"metadata": OWNER}],           # no op -> defaults to add; no use -> defaults to Use
    })
    assert_ok(r, "attach an owner with op/use omitted (defaults apply)")
    counts = r.structured.get("content") or {}
    assert counts.get("added") == 1, "the omitted op must default to add: %r" % (r.structured,)
    poll_diff_contains(OWNER_MDO_TOKEN,
                       ctx="the default-add owner must land in a <content> block on disk")
    # the default use is Use -> the item carries a <use>Use</use> in the same content block.
    poll_diff_contains("<use>Use</use>",
                       ctx="the default per-owner use must serialize as Use")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — idempotent re-add: no duplicate; a second add of the same owner UPDATES its use
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_readd_is_idempotent_and_updates_use():
    first = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"op": "add", "metadata": OWNER, "use": "Use"}],
    })
    assert_ok(first, "first attach")
    assert (first.structured.get("content") or {}).get("added") == 1, \
        "first attach must add: %r" % (first.structured,)

    # Re-add the SAME owner with a DIFFERENT use -> no duplicate, counted as an update.
    second = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"op": "add", "metadata": OWNER, "use": "DontUse"}],
    })
    assert_ok(second, "re-attach the same owner (idempotent)")
    counts = second.structured.get("content") or {}
    assert counts.get("added") == 0 and counts.get("updated") == 1, \
        "a re-add of a listed owner must UPDATE (not add) its use: %r" % (second.structured,)

    # The use flip landed on disk (Use -> DontUse) for the single, un-duplicated owner.
    poll_diff_contains("<use>DontUse</use>",
                       ctx="the re-add must update the owner's use to DontUse on disk")
    # anti-cheat: exactly ONE owner row exists (no duplicate in the model read-back), and it exposes
    # the updated use rather than the generic EObject EClass name.
    text = _details_text()
    assert text.count("| Catalog.Catalog | DontUse |") == 1, \
        "the idempotent re-add must NOT duplicate the owner:\n%s" % text[:500]
    assert_not_contains(text, "CommonAttributeContentItem",
                        "the content read-back must not use the generic EObject fallback")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — remove: seed the owner, then detach it; the <content> owner token is gone
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_remove_owner_detaches_it():
    seed = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"op": "add", "metadata": OWNER, "use": "Use"}],
    })
    assert_ok(seed, "seed the owner to later remove")
    poll_diff_contains(OWNER_MDO_TOKEN, ctx="the seeded owner must be on disk before removal")

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"op": "remove", "metadata": OWNER}],
    })
    assert_ok(r, "detach the owner from the common attribute")
    counts = r.structured.get("content") or {}
    assert counts.get("removed") == 1, "exactly one owner must be removed: %r" % (r.structured,)
    assert counts.get("added") == 0 and counts.get("updated") == 0, \
        "a pure remove must not add / update: %r" % (r.structured,)

    # The owner FQN is gone from the content list on disk (the whole <content> block for it is removed).
    poll_disk_lacks(CA_MDO, OWNER_MDO_TOKEN,
                    ctx="the detached owner's <content> block must be gone from the .mdo")
    # The model read-back no longer lists a Content section (back to an empty content list).
    after = _details_text()
    assert "### Content" not in after, \
        "the model read-back must show the content item removed:\n%s" % after[:500]


# ──────────────────────────────────────────────────────────────────────────────
# Happy — a Russian bilingual FQN type token resolves the owner (only the token is bilingual)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_add_owner_by_russian_type_token():
    # 'Справочник.Catalog' - the Russian type token 'Справочник' (= Catalog), same programmatic Name.
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        # use='DontUse' (a NON-default use that serializes): 'Auto' is the CommonAttributeUse EMF
        # default, so setUse(Auto) writes no <use> element (like an ExchangePlan's default 'Deny').
        "content": [{"op": "add", "metadata": "Справочник.Catalog", "use": "DontUse"}],
    })
    assert_ok(r, "attach an owner addressed by the Russian type token")
    counts = r.structured.get("content") or {}
    assert counts.get("added") == 1, \
        "the bilingual type token must resolve the same owner and add it: %r" % (r.structured,)
    # It resolves to the SAME owner -> the English-canonical FQN + the DontUse use land on disk.
    poll_diff_contains(OWNER_MDO_TOKEN,
                       ctx="the Russian-token owner must serialize to the canonical Catalog.Catalog")
    poll_diff_contains("<use>DontUse</use>",
                       ctx="the non-default DontUse use must serialize for the bilingual-token owner")


# ──────────────────────────────────────────────────────────────────────────────
# Negative — an unresolvable owner FQN is a clean, actionable error (nothing written)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_add_unresolvable_owner_is_error():
    bad = "Catalog.NoSuchOwner_e2e"
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"op": "add", "metadata": bad, "use": "Use"}],
    })
    e = assert_error(r, "attach an owner that does not exist")
    assert_error_quality(e, names=[bad],
                         ctx="an unresolvable owner FQN names the bad value and is actionable")
    assert_no_diff("a rejected content add must change nothing")


# ──────────────────────────────────────────────────────────────────────────────
# Negative — a valid object that is NOT a common-attribute owner kind is rejected
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_add_non_owner_kind_is_error():
    # A CommonModule exists but cannot OWN a common attribute (not a common-attribute owner kind).
    not_owner = "CommonModule.OK"
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"op": "add", "metadata": not_owner, "use": "Use"}],
    })
    e = assert_error(r, "attach an object that is not a valid owner kind")
    assert_error_quality(e, names=[not_owner], suggests=["owner"],
                         ctx="a non-owner kind is rejected with the object named + an owner hint")
    assert_no_diff("a rejected non-owner add must change nothing")


# NOTE: a content payload against a genuinely non-dispatch FQN kind (one with no membership list) is
# covered by test_content_payload_on_non_dispatch_kind_is_error in test_modify_metadata_membership.py.
# In #174 v2 a Catalog / ExchangePlan / Document FQN IS a valid content-dispatch target (owners /
# content / register records), so a "Catalog is not a CommonAttribute -> error" check no longer holds.


# ──────────────────────────────────────────────────────────────────────────────
# Negative — remove still requires a real owner and a row that is actually attached
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_remove_unresolvable_owner_is_error():
    missing = "Catalog.NoSuchOwnerToRemove_e2e"
    # Removal skips only the class predicate; it must still name a real metadata object so a typo
    # cannot masquerade as a successful cleanup.
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"op": "remove", "metadata": missing}],
    })
    e = assert_error(r, "detach an owner object that does not exist")
    assert_error_quality(e, names=[missing],
                         ctx="removing a non-existent owner still names the unresolved FQN")
    assert_no_diff("a rejected removal of a non-existent owner must change nothing")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_remove_not_attached_owner_is_error():
    # Removal skips only the class predicate; a real owner must still have an attached row. This
    # preserves the existing typo/stale-state signal instead of turning remove into a silent no-op.
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"op": "remove", "metadata": OWNER}],
    })
    e = assert_error(r, "detach an owner that is not in the content list")
    assert_error_quality(e, names=[OWNER],
                         ctx="removing a non-listed owner names it and is actionable")
    assert_no_diff("a rejected remove must change nothing")


# ──────────────────────────────────────────────────────────────────────────────
# Negative — an empty content entry (no metadata FQN) is rejected with the shape
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_content_entry_without_metadata_is_error():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"op": "add", "use": "Use"}],   # no 'metadata' -> the required field is missing
    })
    e = assert_error(r, "content entry missing the required metadata FQN")
    assert_error_quality(e, names=["metadata"],
                         ctx="a content entry with no metadata FQN is rejected naming the field")
    assert_no_diff("a rejected malformed content entry must change nothing")


# ──────────────────────────────────────────────────────────────────────────────
# Negative — a bad 'use' value is rejected and lists the accepted literals
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_bad_use_value_lists_allowed():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"op": "add", "metadata": OWNER, "use": "Maybe_zz"}],
    })
    e = assert_error(r, "a bad per-owner use value")
    # the error names the bad value AND lists the three accepted literals.
    assert_error_quality(e, names=["Maybe_zz"], suggests=["Use", "DontUse", "Auto"],
                         ctx="an unrecognized use value lists Use / DontUse / Auto")
    assert_no_diff("a rejected bad-use add must change nothing")


# ──────────────────────────────────────────────────────────────────────────────
# Negative — a content payload cannot be combined with a generic 'properties' change
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_content_mixed_with_properties_is_error():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": CA,
        "content": [{"op": "add", "metadata": OWNER, "use": "Use"}],
        "properties": [{"name": "comment", "value": "x"}],
    })
    e = assert_error(r, "content payload mixed with a properties change")
    assert_error_quality(e, suggests=["cannot be combined", "separately"],
                         ctx="a content change cannot be combined with a generic properties change")
    assert_no_diff("a rejected mixed content+properties call must change nothing")
