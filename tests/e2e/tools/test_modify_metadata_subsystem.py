"""
e2e tests for modify_metadata's additive content[] payload extended to SUBSYSTEMS (#239).

A Subsystem groups metadata objects; which objects belong to it — its content — is a plain
EList<MdObject> serialized on disk as one <content>Type.Name</content> ref per member (exactly like
Catalog.owners / Document.registerRecords, the two plain-reference kinds ReferenceMembershipWriter
already served). #239 adds a THIRD Kind.SUBSYSTEM_CONTENT so ONE object can be attached / detached
without a full-list replace:

    modify_metadata(fqn="Subsystem.X" | "Подсистема.X" | nested,
                    content=[{op?:'add'|'remove' (default add), metadata:'Constant.Y'}])

Adding is idempotent (an already-listed object is a no-op, `added` stays 0, no duplicate row); removing
detaches by FQN; removing an object that is not in the content is a clean, actionable error that rolls
the whole write back (never a silent no-op). The change goes through a BM write transaction and
force-exports the SUBSYSTEM's own .mdo (the content lives there). The success payload carries the same
{added, updated, removed} counts + action="modified" as the other content[] kinds (`updated` is always 0
for a subsystem — a plain reference list has no per-entry flag). A Subsystem itself is NOT a valid
content member (subsystems nest via <subsystems>, never <content>), so adding one is rejected UP FRONT,
naming the bad FQN, and writes nothing.

reset: kind="write-metadata" -> reset_fixture()+reset_model() after each test.

FIXTURE NOTE: the shipped TestConfiguration has a single EMPTY Subsystem.Subsystem and no Constant, so
each test CREATES the Subsystem + Constant it needs via create_metadata under a UNIQUE name (a created
top object is not guaranteed to be reverted before the next test, like the membership / StyleItem e2e
tests). Because the setup legitimately dirties the tree, the negative "nothing written" assertions
snapshot the tree AFTER the setup (tree_snapshot / assert_tree_unchanged) rather than assert_no_diff.

The NESTED case (test_subsystem_content_nested) seeds a child UNDER the fixture's Subsystem.Subsystem
as its FIRST write, so a build that cannot create a nested subsystem (create_metadata does not yet
create one) commits nothing and the test SKIPS cleanly instead of leaking model state; when nested
creation is supported it exercises the nested / bilingual force-export target for real.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_tree_unchanged,
    tree_snapshot,
    poll_diff_contains,
    poll_disk_lacks,
    read_disk,
    wait_for_project_ready,
    e2e_test,
    E2ESkip,
    PROJECT,
)


# The .mdo file that owns a top-level subsystem's <content> list.
def _subsystem_mdo(name):
    return "src/Subsystems/%s/%s.mdo" % (name, name)


# The .mdo file that owns a NESTED subsystem's own <content> list: a nested subsystem is serialized
# into its own file under the parent's Subsystems/ folder (src/Subsystems/<Parent>/Subsystems/<Child>/
# <Child>.mdo). That the <content> ref lands in the CHILD's OWN file — not the parent's — is the
# load-bearing proof that the force-export target resolved the nested subsystem (persisted on disk),
# not just an in-memory commit that is silently discarded on refresh.
def _nested_subsystem_mdo(parent, child):
    return "src/Subsystems/%s/Subsystems/%s/%s.mdo" % (parent, child, child)


# The structural on-disk element that pins WHICH object is attached (a subsystem's content is a plain
# reference list, serialized one <content>Type.Name</content> per member — never a bare name, which
# would false-match a collection reference elsewhere). This is the load-bearing add/remove proof.
def _content_token(member_fqn):
    return "<content>%s</content>" % member_fqn


def _create_ok(fqn, ctx):
    """Seed a metadata object and wait for the derived-data rebuild to settle (a dependent modify would
    otherwise hit the BUILDING write-guard)."""
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, ctx)
    wait_for_project_ready()
    return r


def _content_counts(r, ctx):
    """The success payload's {added, updated, removed} counts (the shared content[] shape)."""
    assert r.structured is not None, "%s: JSON tool must return structuredContent: %r" % (ctx, r.raw)
    assert r.structured.get("action") == "modified", "%s: must report modified: %r" % (ctx, r.structured)
    return r.structured.get("content") or {}


# ══════════════════════════════════════════════════════════════════════════════
# Happy — add -> idempotent re-add -> remove one object in a subsystem's content
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_subsystem_content_add_idempotent_remove():
    # Seed a subsystem + a constant it will contain.
    sub, const = "E2ESubContent", "E2ESubConst"
    sub_fqn = "Subsystem." + sub
    const_fqn = "Constant." + const
    token = _content_token(const_fqn)
    _create_ok(const_fqn, "seed the constant to attach")
    _create_ok(sub_fqn, "seed the subsystem")

    # (1) ADD the constant to the subsystem content.
    add = call("modify_metadata", {
        "projectName": PROJECT, "fqn": sub_fqn,
        "content": [{"op": "add", "metadata": const_fqn}],
    })
    assert_ok(add, "add the constant to the subsystem content")
    counts = _content_counts(add, "subsystem content add")
    assert counts.get("added") == 1, "exactly one object must be added: %r" % (add.structured,)
    assert counts.get("removed") == 0 and counts.get("updated", 0) == 0, \
        "a fresh add must not remove / update: %r" % (add.structured,)
    # The change must be PERSISTED to disk (force-export succeeded), not committed in-memory only
    # (persisted=false would mean the change is silently discarded on a refresh / clean_project).
    assert add.structured.get("persisted") is True, \
        "the content change must persist to disk, not commit in-memory only: %r" % (add.structured,)
    # ON-DISK: the object FQN lands in the subsystem's OWN .mdo <content> block.
    poll_diff_contains(token,
                       ctx="the added object must land in the subsystem's <content> block on disk")

    # (2) IDEMPOTENT re-add (a plain ref carries no flag) -> nothing added, no duplicate row.
    again = call("modify_metadata", {
        "projectName": PROJECT, "fqn": sub_fqn,
        "content": [{"op": "add", "metadata": const_fqn}],
    })
    assert_ok(again, "re-add the same object (idempotent)")
    acounts = _content_counts(again, "subsystem content re-add")
    assert acounts.get("added") == 0 and acounts.get("removed") == 0 and acounts.get("updated", 0) == 0, \
        "a flagless re-add of a listed object must be a pure no-op: %r" % (again.structured,)
    # Exactly ONE content entry references the object on disk (the added==0 above already proves the
    # re-add created no new item; this pins it on disk).
    assert read_disk(_subsystem_mdo(sub)).count(token) == 1, \
        "the idempotent re-add must NOT duplicate the object in the subsystem .mdo"

    # (3) REMOVE -> the object is detached from the content.
    rem = call("modify_metadata", {
        "projectName": PROJECT, "fqn": sub_fqn,
        "content": [{"op": "remove", "metadata": const_fqn}],
    })
    assert_ok(rem, "remove the object from the subsystem content")
    rcounts = _content_counts(rem, "subsystem content remove")
    assert rcounts.get("removed") == 1, "exactly one object must be removed: %r" % (rem.structured,)
    assert rcounts.get("added") == 0 and rcounts.get("updated", 0) == 0, \
        "a pure remove must not add / update: %r" % (rem.structured,)
    assert rem.structured.get("persisted") is True, \
        "the detach must persist to disk, not commit in-memory only: %r" % (rem.structured,)
    poll_disk_lacks(_subsystem_mdo(sub), token,
                    ctx="the detached object must be gone from the subsystem .mdo")


# ══════════════════════════════════════════════════════════════════════════════
# Happy — the bilingual TYPE token resolves the same subsystem + member
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_subsystem_content_russian_token():
    # The TYPE token is bilingual: 'Подсистема' (= Subsystem) and 'Константа' (= Constant) resolve the
    # same subsystem + member; only the token is bilingual, the Name is never translated.
    sub, const = "E2ESubContentRu", "E2ESubConstRu"
    const_fqn = "Constant." + const
    _create_ok(const_fqn, "seed the constant")
    _create_ok("Subsystem." + sub, "seed the subsystem")

    r = call("modify_metadata", {
        "projectName": PROJECT,
        "fqn": "Подсистема." + sub,
        "content": [{"op": "add", "metadata": "Константа." + const}],
    })
    assert_ok(r, "add a member addressed by the Russian type tokens")
    assert _content_counts(r, "subsystem content RU add").get("added") == 1, \
        "the bilingual tokens must resolve the same subsystem/member and add it: %r" % (r.structured,)
    poll_diff_contains(_content_token(const_fqn),
                       ctx="the Russian-token object must serialize to the canonical Constant FQN")


# ══════════════════════════════════════════════════════════════════════════════
# Negative — removing an object that is not in the content is a clean error
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_subsystem_content_remove_absent_is_error():
    # Seed the subsystem + a constant but do NOT attach it; removing an unlisted object is a clean,
    # actionable error that names the FQN and writes nothing on top of the seeding.
    sub, const = "E2ESubRemoveAbsent", "E2ESubAbsentConst"
    sub_fqn = "Subsystem." + sub
    const_fqn = "Constant." + const
    _create_ok(const_fqn, "seed the constant")
    _create_ok(sub_fqn, "seed the subsystem")
    before = tree_snapshot()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": sub_fqn,
        "content": [{"op": "remove", "metadata": const_fqn}],
    })
    e = assert_error(r, "remove an object that is not in the subsystem content")
    assert_error_quality(e, names=[const_fqn],
                         ctx="removing a non-listed object names it and is actionable")
    assert_tree_unchanged(before, "a rejected remove must change nothing on top of the seeding")


# ══════════════════════════════════════════════════════════════════════════════
# Negative — a Subsystem is not a valid content member (it nests via <subsystems>)
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_subsystem_content_reject_subsystem_member():
    # A Subsystem cannot be a content member — subsystems nest via <subsystems>, never <content>. Adding
    # one is rejected UP FRONT (a kind failure), naming the bad FQN with a subsystem-content hint, and
    # writing nothing on top of the seeding. The parent subsystem is seeded; the reject adds nothing.
    parent, child = "E2ESubParent", "E2ESubChildBad"
    parent_fqn = "Subsystem." + parent
    child_fqn = "Subsystem." + child
    _create_ok(child_fqn, "seed a second subsystem (the invalid member)")
    _create_ok(parent_fqn, "seed the parent subsystem")
    before = tree_snapshot()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": parent_fqn,
        "content": [{"op": "add", "metadata": child_fqn}],
    })
    e = assert_error(r, "add a Subsystem as a content member")
    assert_error_quality(e, names=[child_fqn], suggests=["subsystem"],
                         ctx="a Subsystem is rejected as a content member with the object named + a hint")
    assert_tree_unchanged(before, "a rejected non-member add must change nothing")


# ══════════════════════════════════════════════════════════════════════════════
# Happy — a NESTED subsystem: content lands in the CHILD's OWN .mdo, addressed by the
# compound FQN in both English and a RUSSIAN non-leading type token
# ══════════════════════════════════════════════════════════════════════════════

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_subsystem_content_nested():
    # Nested subsystems are the headline case of this feature (the tool description advertises a
    # nested 'Subsystem.Parent.Subsystem.Child'). The force-export target is the crux: a nested
    # subsystem is serialized into its OWN .mdo, and the change must actually reach that file, not
    # commit in-memory only. The compound FQN may carry a Russian type token in a NON-LEADING
    # segment (e.g. 'Subsystem.Parent.Подсистема.Child') — MetadataTypeUtils.normalizeFqn
    # canonicalizes only the LEADING token, so that mixed-language FQN is NOT a valid force-export
    # key; the tool must derive the export target from the resolved subsystem's BM identity (the
    # canonical all-English top-object FQN) instead, or the committed change is silently discarded
    # on the next refresh. This test pins both: persisted=true AND the <content> ref present in the
    # CHILD's OWN .mdo, for the English and the Russian nested address.
    #
    # PRECONDITION: a nested subsystem must exist to target. It is seeded UNDER the fixture's
    # pre-existing empty 'Subsystem.Subsystem' via create_metadata — the FIRST write in this test,
    # so a create that this build cannot perform commits nothing and the test SKIPS cleanly (no
    # leaked model state), rather than failing. create_metadata does not (yet) create a nested
    # subsystem — subsystems are top-object-shaped and are excluded from the generic child-feature
    # map; when that gains support this test starts exercising the nested force-export for real.
    # Until then the nested force-export target stays covered structurally by the BM-identity FQN
    # derivation + the SubsystemUtils.resolveByFqn unit tests (bilingual + nested).
    parent, child = "Subsystem", "E2ENestedChild"
    child_fqn = "Subsystem.%s.Subsystem.%s" % (parent, child)
    child_fqn_ru = "Subsystem.%s.Подсистема.%s" % (parent, child)  # Russian NON-leading type token
    child_mdo = _nested_subsystem_mdo(parent, child)

    # Seed the nested child (first write): skip cleanly if this build cannot create a nested subsystem.
    seed = call("create_metadata", {"projectName": PROJECT, "fqn": child_fqn})
    if seed.is_error:
        raise E2ESkip(
            "nested-subsystem seeding is unsupported by create_metadata (%s); the nested "
            "force-export target stays covered by the bmGetTopObject().bmGetFqn() derivation + the "
            "SubsystemUtils.resolveByFqn unit tests" % (seed.error_text()[:200],))
    wait_for_project_ready()

    const_en, const_ru = "E2ENestedConstEn", "E2ENestedConstRu"
    const_en_fqn = "Constant." + const_en
    const_ru_fqn = "Constant." + const_ru
    _create_ok(const_en_fqn, "seed the constant added via the English nested FQN")
    _create_ok(const_ru_fqn, "seed the constant added via the Russian nested token")

    # (1) ADD via the English nested FQN -> lands in the CHILD's OWN .mdo, persisted on disk.
    add_en = call("modify_metadata", {
        "projectName": PROJECT, "fqn": child_fqn,
        "content": [{"op": "add", "metadata": const_en_fqn}],
    })
    assert_ok(add_en, "add a member to the nested child via the English nested FQN")
    assert _content_counts(add_en, "nested subsystem content add (en)").get("added") == 1, \
        "exactly one object must be added to the nested child: %r" % (add_en.structured,)
    assert add_en.structured.get("persisted") is True, \
        "the nested content change must persist to disk, not commit in-memory only: %r" % (add_en.structured,)
    token_en = _content_token(const_en_fqn)
    poll_diff_contains(token_en, ctx="the nested child's content must flush to disk")
    assert token_en in read_disk(child_mdo), \
        "the added object must land in the CHILD subsystem's OWN .mdo (%s), proving the nested " \
        "force-export FQN resolved and persisted: %r" % (child_mdo, add_en.structured)

    # (2) ADD via a RUSSIAN non-leading type token (Subsystem.Subsystem.Подсистема.Child) -> the
    #     same child .mdo. This is exactly the mixed-language FQN whose export key must come from BM
    #     identity, not the raw address; it, too, must persist into the child's own .mdo.
    add_ru = call("modify_metadata", {
        "projectName": PROJECT, "fqn": child_fqn_ru,
        "content": [{"op": "add", "metadata": const_ru_fqn}],
    })
    assert_ok(add_ru, "add a member to the nested child via a Russian non-leading subsystem token")
    assert _content_counts(add_ru, "nested subsystem content add (ru)").get("added") == 1, \
        "the Russian nested token must resolve the same child and add the member: %r" % (add_ru.structured,)
    assert add_ru.structured.get("persisted") is True, \
        "the mixed-language nested change must persist (the export key must be canonicalized): %r" \
        % (add_ru.structured,)
    token_ru = _content_token(const_ru_fqn)
    poll_diff_contains(token_ru, ctx="the Russian-nested-token content must flush to disk")
    assert token_ru in read_disk(child_mdo), \
        "the object added via a Russian non-leading type token must land in the CHILD's OWN .mdo " \
        "(%s), proving the mixed-language nested FQN was canonicalized for force-export: %r" \
        % (child_mdo, add_ru.structured)
