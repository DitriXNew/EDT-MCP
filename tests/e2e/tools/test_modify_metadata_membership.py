"""
e2e tests for modify_metadata's generic `content[]` payload on the three membership-list kinds it
gained in #174 v2 (beyond the v1 CommonAttribute owners - those live in
test_modify_metadata_common_attribute.py):

  * ExchangePlan.content     - which objects a node exchanges; each entry carries an `autoRecord`
                               (Allow / Deny) change-registration flag.
                               content=[{op?:'add'|'remove', metadata:'Catalog.X', autoRecord?:'Allow'|'Deny'}]
  * Catalog.owners           - the objects a subordinate catalog belongs to (plain refs, no flag).
                               content=[{op?:'add'|'remove', metadata:'Catalog.Owner'}]
  * Document.registerRecords - the registers a document posts движения to (plain refs, no flag).
                               content=[{op?:'add'|'remove', metadata:'InformationRegister.Reg'}]

ONE generic `content[]` param dispatched by the resolved FQN's kind (the author's "generic content[]"
intent). Adding is idempotent; removing detaches by FQN. Each change goes through a BM write
transaction and force-exports the top object's `.mdo`. The success payload carries the same
`content` counts object {added, updated, removed} + action="modified" as the v1 CommonAttribute path.

reset: kind="write-metadata" -> reset_fixture()+reset_model() after each test.

FIXTURE NOTE: the shipped TestConfiguration has NO ExchangePlan, no subordinate Catalog and no
Document with a register, so each test CREATES the objects it needs via create_metadata in its own
setup (with a UNIQUE name per test - a created top object is not guaranteed to be reverted before the
next test, like the StyleItem / DynamicList e2e tests). Because the setup legitimately dirties the
tree, the negative "nothing written" assertions snapshot the tree AFTER the setup
(tree_snapshot / assert_tree_unchanged) rather than assert_no_diff; the wrong-target rejections that
need no seeding of the container use the fixture Catalog.Catalog directly and assert_no_diff.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_no_diff,
    assert_tree_unchanged,
    tree_snapshot,
    poll_diff_contains,
    poll_disk_lacks,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)


def _details_text(fqn):
    r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
    assert_ok(r, "get_metadata_details for " + fqn)
    return r.text


def _create_ok(fqn, ctx):
    """Seed a metadata object and wait for the derived-data rebuild to settle (a dependent create /
    modify would otherwise hit the BUILDING write-guard)."""
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, ctx)
    wait_for_project_ready()
    return r


def _content_counts(r, ctx):
    """The success payload's {added, updated, removed} counts (the same shape as the v1 path)."""
    assert r.structured is not None, "%s: JSON tool must return structuredContent: %r" % (ctx, r.raw)
    assert r.structured.get("action") == "modified", "%s: must report modified: %r" % (ctx, r.structured)
    return r.structured.get("content") or {}


# ══════════════════════════════════════════════════════════════════════════════
# ExchangePlan.content — a wrapper list with a per-entry autoRecord (Allow / Deny)
# ══════════════════════════════════════════════════════════════════════════════

# The .mdo folder / file that owns an ExchangePlan's content list.
def _plan_mdo(plan):
    return "src/ExchangePlans/%s/%s.mdo" % (plan, plan)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_exchange_plan_content_add_idempotent_update_remove():
    # Seed an ExchangePlan (the node) + a Catalog it will exchange (a valid content member).
    plan, member = "E2EXPContent", "E2EXPMemberCat"
    plan_fqn = "ExchangePlan." + plan
    member_fqn = "Catalog." + member
    _create_ok(member_fqn, "seed the exchanged catalog")
    _create_ok(plan_fqn, "seed the exchange plan")

    # Precondition (anti-cheat): the plan starts with an EMPTY content list, so a no-op FAILS the diff.
    assert "ExchangePlanContentItem" not in _details_text(plan_fqn), \
        "the fresh exchange plan must start with no content"

    # (1) ADD with autoRecord=Allow.
    add = call("modify_metadata", {
        "projectName": PROJECT, "fqn": plan_fqn,
        "content": [{"op": "add", "metadata": member_fqn, "autoRecord": "Allow"}],
    })
    assert_ok(add, "add the catalog to the exchange plan content")
    counts = _content_counts(add, "exchange-plan add")
    assert counts.get("added") == 1, "exactly one member must be added: %r" % (add.structured,)
    assert counts.get("updated") == 0 and counts.get("removed") == 0, \
        "a fresh add must not update / remove: %r" % (add.structured,)
    # ON-DISK: the member FQN lands in the plan's content block, with the Allow auto-record flag.
    poll_diff_contains(member_fqn, ctx="the exchanged member FQN must land in the content on disk")
    poll_diff_contains("<autoRecord>Allow</autoRecord>",
                       ctx="the autoRecord=Allow flag must serialize for the added member")
    # MODEL read-back: the plan now carries a content item (empty -> one row).
    assert_contains(_details_text(plan_fqn), "ExchangePlanContentItem",
                    "the model read-back must show the new content item")

    # (2) IDEMPOTENT re-add with a DIFFERENT autoRecord -> UPDATE (not add), no duplicate row.
    upd = call("modify_metadata", {
        "projectName": PROJECT, "fqn": plan_fqn,
        "content": [{"op": "add", "metadata": member_fqn, "autoRecord": "Deny"}],
    })
    assert_ok(upd, "re-add the same member with a different autoRecord (idempotent)")
    ucounts = _content_counts(upd, "exchange-plan re-add")
    assert ucounts.get("added") == 0 and ucounts.get("updated") == 1, \
        "a re-add of a listed member must UPDATE (not add) its autoRecord: %r" % (upd.structured,)
    poll_diff_contains("<autoRecord>Deny</autoRecord>",
                       ctx="the re-add must flip autoRecord to Deny on disk")
    assert _details_text(plan_fqn).count("ExchangePlanContentItem") == 1, \
        "the idempotent re-add must NOT duplicate the member"

    # (3) REMOVE -> the member is detached from the content.
    rem = call("modify_metadata", {
        "projectName": PROJECT, "fqn": plan_fqn,
        "content": [{"op": "remove", "metadata": member_fqn}],
    })
    assert_ok(rem, "remove the member from the exchange plan content")
    rcounts = _content_counts(rem, "exchange-plan remove")
    assert rcounts.get("removed") == 1, "exactly one member must be removed: %r" % (rem.structured,)
    assert rcounts.get("added") == 0 and rcounts.get("updated") == 0, \
        "a pure remove must not add / update: %r" % (rem.structured,)
    poll_disk_lacks(_plan_mdo(plan), member_fqn,
                    ctx="the detached member must be gone from the exchange plan .mdo")
    assert "ExchangePlanContentItem" not in _details_text(plan_fqn), \
        "the model read-back must show the content item removed"


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_exchange_plan_content_russian_token():
    # The TYPE token is bilingual: 'ПланОбмена' (= ExchangePlan) and 'Справочник' (= Catalog)
    # resolve the same node + member; only the token is bilingual, the Name is never translated.
    plan, member = "E2EXPRu", "E2EXPRuCat"
    plan_fqn = "ExchangePlan." + plan
    _create_ok("Catalog." + member, "seed the exchanged catalog")
    _create_ok(plan_fqn, "seed the exchange plan")

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "ПланОбмена." + plan,
        "content": [{"op": "add",
                     "metadata": "Справочник." + member,
                     "autoRecord": "Allow"}],
    })
    assert_ok(r, "add a member addressed by the Russian type tokens")
    assert _content_counts(r, "exchange-plan RU add").get("added") == 1, \
        "the bilingual tokens must resolve the same node/member and add it: %r" % (r.structured,)
    poll_diff_contains("Catalog." + member,
                       ctx="the Russian-token member must serialize to the canonical Catalog FQN")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_content_payload_on_non_dispatch_kind_is_error():
    # A content payload aimed at an FQN kind that has NO membership list (an InformationRegister is
    # neither a CommonAttribute nor an ExchangePlan/Catalog/Document) must be a clean, actionable
    # error - it must NOT silently fall through to the generic property path.
    reg = "E2ERegNoContent"
    reg_fqn = "InformationRegister." + reg
    _create_ok(reg_fqn, "seed a register that carries no content list")
    before = tree_snapshot()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": reg_fqn,
        "content": [{"op": "add", "metadata": reg_fqn}],
    })
    e = assert_error(r, "content payload aimed at a kind with no membership list")
    assert_error_quality(e, names=["content"],
                         suggests=["CommonAttribute", "ExchangePlan", "Catalog", "Document"],
                         ctx="a content payload lists the four kinds that accept it")
    assert_tree_unchanged(before, "a rejected content payload on the wrong kind must change nothing")


# ══════════════════════════════════════════════════════════════════════════════
# Catalog.owners — plain owner refs (a subordinate catalog belongs to its owners)
# ══════════════════════════════════════════════════════════════════════════════

def _catalog_mdo(cat):
    return "src/Catalogs/%s/%s.mdo" % (cat, cat)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_catalog_owners_add_idempotent_remove():
    # Seed the subordinate catalog + its owner catalog.
    sub, owner = "E2ESubCat", "E2EOwnerCat"
    sub_fqn = "Catalog." + sub
    owner_fqn = "Catalog." + owner
    _create_ok(owner_fqn, "seed the owner catalog")
    _create_ok(sub_fqn, "seed the subordinate catalog")

    # (1) ADD the owner.
    add = call("modify_metadata", {
        "projectName": PROJECT, "fqn": sub_fqn,
        "content": [{"op": "add", "metadata": owner_fqn}],
    })
    assert_ok(add, "attach the owner to the subordinate catalog")
    counts = _content_counts(add, "catalog owners add")
    assert counts.get("added") == 1, "exactly one owner must be added: %r" % (add.structured,)
    assert counts.get("removed") == 0 and counts.get("updated") == 0, \
        "a fresh owner add must not remove / update: %r" % (add.structured,)
    # ON-DISK: the owner FQN lands in the subordinate catalog's <owners> block.
    poll_diff_contains("<owners>%s</owners>" % owner_fqn,
                       ctx="the owner FQN must land in the catalog's <owners> block on disk")

    # (2) IDEMPOTENT re-add (owners carry no flag) -> nothing added, no duplicate.
    again = call("modify_metadata", {
        "projectName": PROJECT, "fqn": sub_fqn,
        "content": [{"op": "add", "metadata": owner_fqn}],
    })
    assert_ok(again, "re-add the same owner (idempotent)")
    acounts = _content_counts(again, "catalog owners re-add")
    assert acounts.get("added") == 0 and acounts.get("removed") == 0 and acounts.get("updated") == 0, \
        "a flagless re-add of a listed owner must be a pure no-op: %r" % (again.structured,)

    # (3) REMOVE -> the owner is detached.
    rem = call("modify_metadata", {
        "projectName": PROJECT, "fqn": sub_fqn,
        "content": [{"op": "remove", "metadata": owner_fqn}],
    })
    assert_ok(rem, "detach the owner from the subordinate catalog")
    rcounts = _content_counts(rem, "catalog owners remove")
    assert rcounts.get("removed") == 1, "exactly one owner must be removed: %r" % (rem.structured,)
    poll_disk_lacks(_catalog_mdo(sub), "<owners>%s</owners>" % owner_fqn,
                    ctx="the detached owner must be gone from the catalog .mdo")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_catalog_owners_russian_token():
    # 'Справочник' (= Catalog) type token resolves the same subordinate + owner.
    sub, owner = "E2ESubCatRu", "E2EOwnerCatRu"
    sub_fqn = "Catalog." + sub
    owner_fqn = "Catalog." + owner
    _create_ok(owner_fqn, "seed the owner catalog")
    _create_ok(sub_fqn, "seed the subordinate catalog")

    r = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": "Справочник." + sub,
        "content": [{"op": "add",
                     "metadata": "Справочник." + owner}],
    })
    assert_ok(r, "attach an owner addressed by the Russian type token")
    assert _content_counts(r, "catalog owners RU add").get("added") == 1, \
        "the Russian token must resolve the same owner and add it: %r" % (r.structured,)
    poll_diff_contains("<owners>%s</owners>" % owner_fqn,
                       ctx="the Russian-token owner must serialize to the canonical Catalog FQN")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_catalog_owners_reject_non_owner_kind():
    # A CommonModule cannot OWN a subordinate catalog (it is not a valid CatalogOwner). The reject
    # must name the bad FQN and NOT write anything. The subordinate catalog is seeded; the reject is a
    # resolution/kind failure so nothing is added on top of the seeding.
    sub = "E2ESubCatBad"
    sub_fqn = "Catalog." + sub
    not_owner = "CommonModule.OK"      # a fixture common module - not a CatalogOwner kind
    _create_ok(sub_fqn, "seed the subordinate catalog")
    before = tree_snapshot()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": sub_fqn,
        "content": [{"op": "add", "metadata": not_owner}],
    })
    e = assert_error(r, "attach an object that is not a valid catalog owner")
    assert_error_quality(e, names=[not_owner], suggests=["owner"],
                         ctx="a non-CatalogOwner is rejected with the object named + an owner hint")
    assert_tree_unchanged(before, "a rejected non-owner add must change nothing")


# ══════════════════════════════════════════════════════════════════════════════
# Document.registerRecords — plain register refs (a document posts движения to them)
# ══════════════════════════════════════════════════════════════════════════════

def _document_mdo(doc):
    return "src/Documents/%s/%s.mdo" % (doc, doc)


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_document_register_records_add_idempotent_remove():
    # Seed the document + an InformationRegister it posts to.
    doc, reg = "E2ERecDoc", "E2ERecReg"
    doc_fqn = "Document." + doc
    reg_fqn = "InformationRegister." + reg
    _create_ok(reg_fqn, "seed the target register")
    _create_ok(doc_fqn, "seed the document")

    # (1) ADD the register record.
    add = call("modify_metadata", {
        "projectName": PROJECT, "fqn": doc_fqn,
        "content": [{"op": "add", "metadata": reg_fqn}],
    })
    assert_ok(add, "attach the register to the document's registerRecords")
    counts = _content_counts(add, "document registerRecords add")
    assert counts.get("added") == 1, "exactly one register must be added: %r" % (add.structured,)
    assert counts.get("removed") == 0 and counts.get("updated") == 0, \
        "a fresh register add must not remove / update: %r" % (add.structured,)
    # ON-DISK: the register FQN lands in the document's <registerRecords> block.
    poll_diff_contains("<registerRecords>%s</registerRecords>" % reg_fqn,
                       ctx="the register FQN must land in the document's <registerRecords> block on disk")

    # (2) IDEMPOTENT re-add (flagless) -> nothing added, no duplicate.
    again = call("modify_metadata", {
        "projectName": PROJECT, "fqn": doc_fqn,
        "content": [{"op": "add", "metadata": reg_fqn}],
    })
    assert_ok(again, "re-add the same register (idempotent)")
    acounts = _content_counts(again, "document registerRecords re-add")
    assert acounts.get("added") == 0 and acounts.get("removed") == 0 and acounts.get("updated") == 0, \
        "a flagless re-add of a listed register must be a pure no-op: %r" % (again.structured,)

    # (3) REMOVE -> the register record is detached.
    rem = call("modify_metadata", {
        "projectName": PROJECT, "fqn": doc_fqn,
        "content": [{"op": "remove", "metadata": reg_fqn}],
    })
    assert_ok(rem, "detach the register from the document's registerRecords")
    rcounts = _content_counts(rem, "document registerRecords remove")
    assert rcounts.get("removed") == 1, "exactly one register must be removed: %r" % (rem.structured,)
    poll_disk_lacks(_document_mdo(doc), "<registerRecords>%s</registerRecords>" % reg_fqn,
                    ctx="the detached register must be gone from the document .mdo")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_document_register_records_russian_token():
    # 'Документ' (= Document) + 'РегистрСведений' (= InformationRegister) type tokens resolve the
    # same document + register.
    doc, reg = "E2ERecDocRu", "E2ERecRegRu"
    doc_fqn = "Document." + doc
    reg_fqn = "InformationRegister." + reg
    _create_ok(reg_fqn, "seed the target register")
    _create_ok(doc_fqn, "seed the document")

    r = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": "Документ." + doc,
        "content": [{"op": "add",
                     "metadata": "РегистрСведений." + reg}],
    })
    assert_ok(r, "attach a register addressed by the Russian type tokens")
    assert _content_counts(r, "document registerRecords RU add").get("added") == 1, \
        "the Russian tokens must resolve the same register and add it: %r" % (r.structured,)
    poll_diff_contains("<registerRecords>%s</registerRecords>" % reg_fqn,
                       ctx="the Russian-token register must serialize to the canonical FQN")


@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_document_register_records_reject_non_register():
    # A Catalog is not a register - it cannot be a document's registerRecords target. The reject must
    # name the bad FQN and write nothing. The document is seeded; the reject is a kind failure so
    # nothing is added on top of the seeding.
    doc = "E2ERecDocBad"
    doc_fqn = "Document." + doc
    not_register = "Catalog.Catalog"       # a fixture catalog - not a register
    _create_ok(doc_fqn, "seed the document")
    before = tree_snapshot()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": doc_fqn,
        "content": [{"op": "add", "metadata": not_register}],
    })
    e = assert_error(r, "attach a non-register to the document's registerRecords")
    assert_error_quality(e, names=[not_register], suggests=["register"],
                         ctx="a non-register is rejected with the object named + a register hint")
    assert_tree_unchanged(before, "a rejected non-register add must change nothing")


# ══════════════════════════════════════════════════════════════════════════════
# Cross-kind negative — a content payload cannot be mixed with a generic properties change
# (the same policy the v1 CommonAttribute path enforces; no seeding needed - the fixture
#  Catalog.Catalog is a real Catalog and the mix is rejected up front)
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_membership_content_mixed_with_properties_is_error():
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": "Catalog.Catalog",
        "content": [{"op": "add", "metadata": "Catalog.Catalog"}],
        "properties": [{"name": "comment", "value": "x"}],
    })
    e = assert_error(r, "a content payload mixed with a properties change")
    assert_error_quality(e, suggests=["cannot be combined", "separately"],
                         ctx="a content change cannot be combined with a generic properties change")
    assert_no_diff("a rejected mixed content+properties call must change nothing")
