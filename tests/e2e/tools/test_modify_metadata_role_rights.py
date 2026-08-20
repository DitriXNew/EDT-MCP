"""
e2e tests for modify_metadata WRITING a role's access rights - the `rights` / `roleProperties`
payload on a `Role.<Name>` FQN (#454), and the regression proof for #452.

#452: a role created through create_metadata carries NO editable rights model. The writer
materialised a RoleDescription with RightsFactory and set it on the role, but never registered that
description as a BM top object, so the transaction could not build a persistable reference to it and
the commit died with `Failed to persist reference value ...RoleDescriptionImpl@<hash>` - every FIRST
rights write on a freshly created role failed, and the failure text leaked the EMF implementation's
identity to the caller. These tests drive that exact path over the wire: seed a role, write one
right, and require the cell to land in the role's own `Rights.rights` resource on disk AND to read
back through get_metadata_details. On the pre-fix build the write returns isError and
`Rights.rights` is never created, so no assertion here can be satisfied by substring accident.

WHAT THE PLATFORM DOES WITH A RIGHT VALUE (measured in RightsModelUtil, EDT 2026.2 - not assumed).
AddRightValuesTask calls `changeObjectRight(newValue, defaultValue, ...)`, and
`getDefaultRightValue` derives that default from the ROLE's own flags: `setForNewObjects` for a
top object, `setForAttributesByDefault` for an attribute / tabular section / dimension /
resource. A value EQUAL to the default is not authored at all - an existing cell is DELETED and
an emptied object node removed. A freshly created role has both flags false (a deliberate
choice: seeding them true would turn a caller's `value:"set"` on a top object into a pruned
no-op reported as success), so `set` is authored while `unset` IS the default and prunes. That
is why the round-trip test flips `setForNewObjects` to true before it denies the right: without
the flip the second write would erase the cell and "denied" could never appear, on any build.

reset: kind="write-metadata" -> reset_fixture()+reset_model() after each test.

FIXTURE TRUTH (TestConfiguration, English Names)
  - TestConfiguration ships NO `src/Roles/` folder at all. There is no role to reuse and no rights
    matrix to read, so every test here SEEDS ITS OWN role with create_metadata - which is also
    exactly the #452 path. The seeded role is reverted by the write-metadata reset.
  - The only role that exists anywhere in the fixtures (`tests_DefaultRole`) lives in the `tests`
    EXTENSION project, which the per-test reset_fixture does NOT cover and which the PROJECT_DIR-
    scoped disk helpers cannot address. Nothing here may write into it.
  - Catalog.Catalog is the guarded object: the fixture's only catalog, and a TOP object, so its
    default right value comes from `setForNewObjects` (see above), not from
    `setForAttributesByDefault`.
  - A role's rights live in their OWN resource beside the role's .mdo, at
    `src/Roles/<Name>/Rights.rights` - not inside `<Name>.mdo`. Every disk assertion names that one
    file and goes through poll_disk_contains; poll_diff_contains is never used here, because it is
    satisfied by the substring appearing in ANY changed file and a role write touches two.
  - The on-disk shape is the v8 roles XML (`http://v8.1c.ru/8.2/roles`), where the object node is
    `<object><name>FQN</name><right><name>Read</name><value>true</value></right></object>` and the
    RightValue enum literals serialize as `true` (Set) / `false` (Unset) / `provided` (Provided).
    The right's name is stored in its invariant ENGLISH form whatever language the caller used.
"""

import os

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_disk_lacks,
    assert_tree_unchanged,
    poll_disk_contains,
    split_markdown_row,
    tree_snapshot,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    PROJECT_DIR,
)

# The guarded object every test grants/denies a right on: the fixture's only Catalog, and a TOP
# object (so its default right value follows setForNewObjects - see the module docstring).
GUARDED_OBJECT = "Catalog.Catalog"

# The literal note get_metadata_details renders for a role that has NO editable rights model. This
# is the #452 precondition; it must be present before the first write and gone after it.
NO_MATRIX_NOTE = "_(this role has no editable rights model)_"

# "Read" written in Russian, spelled from code points so this source file stays pure ASCII:
# U+0427 U+0442 U+0435 U+043D U+0438 U+0435. The `right` name is bilingual on the way IN
# (RoleRightsWriter matches Right.getName() OR Right.getNameRu()), which one test drives directly.
RIGHT_READ_RU = "\u0427\u0442\u0435\u043d\u0438\u0435"

# The matrix table's header row, skipped when the rendered table is parsed back into triples.
_MATRIX_HEADER = ["Object", "Right", "Value"]


def _seed_role(name):
    """create_metadata a fresh Role and return its FQN.

    A role created this way has no rights model whatsoever - that is the #452 precondition, and it
    is asserted explicitly in the test that depends on it rather than assumed everywhere."""
    fqn = "Role." + name
    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, "seed role " + fqn)
    wait_for_project_ready()
    return fqn


def _rights_file(name):
    """The role's rights resource, relative to the project dir (the PROJECT_DIR-scoped helpers'
    address space) - a SEPARATE file from the role's own .mdo."""
    return "src/Roles/%s/Rights.rights" % name


def _rights_file_text(name):
    """The role's Rights.rights exactly as it is on disk right now, or None when the file does not
    exist yet.

    Deliberately NOT poll_disk_lacks / assert_disk_lacks: this is used for the "it is not there
    AT ALL" precondition and for a refused call's "nothing was created", where polling would only
    burn the budget and assert_disk_lacks would fail on the missing file it is meant to accept."""
    full = os.path.join(PROJECT_DIR, *_rights_file(name).split("/"))
    if not os.path.isfile(full):
        return None
    with open(full, encoding="utf-8", errors="replace") as f:
        return f.read()


def _details(fqn):
    """The default (non-full, default-language) get_metadata_details render of one Role FQN.

    For a Role FQN the whole rendered section IS the rights document, so no slicing is needed
    before the section helpers below. The read-side options (full / language /
    roleObjectOffset) belong to test_get_metadata_details_role.py and are deliberately not
    exercised from here: this file is about what the WRITE put there."""
    r = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": [fqn]})
    assert_ok(r, "get_metadata_details for " + fqn)
    return r.text or ""


def _matrix_rows(text):
    """The `## Rights matrix` table as a list of (object, right, value) triples.

    Scoped to that section - the document also renders `## Properties`, `## Row-level security
    (RLS)` and `## RLS templates`, and a whole-document scan for a 3-cell row would let another
    table's shape decide the verdict. Rows are split with the shared escape-aware parser
    (harness.split_markdown_row, pinned by test_markdown_table.py): a metadata FQN cannot contain a
    '|', but production escapes cells unconditionally and a naive split would silently reshape a
    row the moment anything rendered here ever could."""
    parts = text.split("## Rights matrix", 1)
    if len(parts) < 2:
        return []
    body = parts[1].split("\n## ", 1)[0]
    rows = []
    for line in body.splitlines():
        cells = split_markdown_row(line)
        if len(cells) != 3 or cells == _MATRIX_HEADER or set(cells) == {"---"}:
            continue
        rows.append(tuple(cells))
    return rows


# ──────────────────────────────────────────────────────────────────────────────
# THE #452 CASE — the first rights write on a role created through create_metadata
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_role_rights_first_write_on_a_freshly_created_role_lands_on_disk():
    """#452: a role created by create_metadata must accept a rights write end to end.

    The steps are ordered so that a failure cannot be misread. The role is first PROVEN to have no
    rights model (the note) and no rights resource on disk, so "the write created it" is a real
    claim rather than an observation of something that was already there. Then the call must report
    success AND `persisted` - the rights matrix is its own BM resource with its own FQN, so a write
    that reached the model but not the disk would otherwise pass. Only then is the file required to
    carry the cell, and the same read required to have changed its answer.

    On the pre-fix build the write returns isError and no Rights.rights is ever written, so every
    assertion from the modify_metadata call onwards is unreachable there."""
    name = "E2ERoleRightsFresh"
    fqn = _seed_role(name)

    before = _details(fqn)
    assert_contains(before, NO_MATRIX_NOTE,
                    "a freshly created role must have NO editable rights model yet - that is the "
                    "#452 precondition this test exists for")
    assert _rights_file_text(name) is None, (
        "setup: %s must not exist before the first rights write, otherwise the disk assertions "
        "below would be satisfied by a file this call never wrote" % _rights_file(name))

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "rights": [{"object": GUARDED_OBJECT, "right": "Read", "value": "set"}],
    })
    assert_ok(r, "the FIRST rights write on a freshly created role (#452)")
    structured = r.structured or {}
    assert structured.get("persisted") is True, (
        "the rights write must report persisted=true: the matrix lives in its own resource and is "
        "force-exported by its own FQN, so persistence is a separate claim from success: %r"
        % (structured,))
    assert (structured.get("applied") or {}).get("rights") == 1, \
        "exactly one rights entry must be reported applied: %r" % (structured,)

    poll_disk_contains(_rights_file(name), "<name>%s</name>" % GUARDED_OBJECT,
                       ctx="the guarded object must reach the role's own rights resource")
    poll_disk_contains(_rights_file(name), "<value>true</value>",
                       ctx="a granted right serializes as the 'true' RightValue literal")

    after = _details(fqn)
    assert_not_contains(after, NO_MATRIX_NOTE,
                        "the role now HAS an editable rights model, so the degraded note must be "
                        "gone from the read")
    assert_contains(after, "## Properties",
                    "a role with a rights model renders its three role-property booleans")
    assert (GUARDED_OBJECT, "Read", "allowed") in _matrix_rows(after), \
        "the granted right must read back as an 'allowed' matrix row:\n%s" % after


# ──────────────────────────────────────────────────────────────────────────────
# Round trip — grant, then deny the SAME object+right; each proven in the reader
# AND in the file on disk
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_role_rights_round_trip_grant_then_deny():
    """set -> `allowed` + `<value>true</value>`, then unset -> `denied` + `<value>false</value>`.

    The `setForNewObjects` flip in the middle is neither decoration nor a second subject: a value
    equal to the guarded object's DEFAULT right value is PRUNED by the platform instead of authored
    (RightsModelUtil.changeObjectRight deletes the existing cell and drops the emptied object node),
    and a fresh role's default for a top object is UNSET. Denying without the flip would therefore
    erase the cell on every build, and this test would be measuring the wrong thing while looking
    green in the reader ("no non-default rights" is not "denied", but a weaker assertion would not
    have noticed). The flip is asserted to have LANDED before the denial is attempted, so a failing
    denial can never be blamed on a flag that never changed.

    The second write also exercises the UPDATE branch (an existing cell whose value changes in
    place), which is why the file is required to no longer carry the granted value afterwards."""
    name = "E2ERoleRightsRoundTrip"
    fqn = _seed_role(name)
    rel = _rights_file(name)

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "rights": [{"object": GUARDED_OBJECT, "right": "Read", "value": "set"}],
    })
    assert_ok(r, "grant Read on " + GUARDED_OBJECT)
    poll_disk_contains(rel, "<value>true</value>",
                       ctx="the granted right must land in the role's rights resource")
    assert (GUARDED_OBJECT, "Read", "allowed") in _matrix_rows(_details(fqn)), \
        "the granted right must read back as 'allowed' before it is denied"

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "roleProperties": {"setForNewObjects": True},
    })
    assert_ok(r, "flip setForNewObjects so that 'unset' stops being the default value")
    poll_disk_contains(rel, "<setForNewObjects>true</setForNewObjects>",
                       ctx="the flag flip must be on disk BEFORE the denial is attempted - "
                           "otherwise the denial equals the default and the platform prunes the "
                           "cell instead of authoring it")

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "rights": [{"object": GUARDED_OBJECT, "right": "Read", "value": "unset"}],
    })
    assert_ok(r, "deny Read on " + GUARDED_OBJECT)
    poll_disk_contains(rel, "<value>false</value>",
                       ctx="a denied right serializes as the 'false' RightValue literal")
    assert_disk_lacks(rel, "<value>true</value>",
                      ctx="the existing cell must be UPDATED in place, not left standing beside a "
                          "second one (the poll above already synchronised with the export, so "
                          "this reads a settled file)")
    assert (GUARDED_OBJECT, "Read", "denied") in _matrix_rows(_details(fqn)), \
        "the denied right must read back as a 'denied' matrix row, not vanish from the matrix"


# ──────────────────────────────────────────────────────────────────────────────
# Bilingual — the `right` name is accepted in Russian and normalized on the way out
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_role_rights_accepts_a_russian_right_name():
    """A right addressed in Russian resolves to the same platform right as its English name.

    Both halves are asserted, because either alone would be weak: that the WRITE resolved the name
    is proven on disk, where the platform stores the invariant English form regardless of the
    language the caller used (so a build that stored the caller's string verbatim would fail here);
    that the READ is not merely echoing it back is proven by the default-language render, which must
    show the English name and must NOT contain the Russian one anywhere in the document."""
    name = "E2ERoleRightsBilingual"
    fqn = _seed_role(name)

    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "rights": [{"object": GUARDED_OBJECT, "right": RIGHT_READ_RU, "value": "set"}],
    })
    assert_ok(r, "grant the Russian-named Read right")
    poll_disk_contains(_rights_file(name), "<name>Read</name>",
                       ctx="the rights resource stores the invariant English right name, whichever "
                           "language the caller addressed it in")

    text = _details(fqn)
    assert (GUARDED_OBJECT, "Read", "allowed") in _matrix_rows(text), \
        "the right granted under its Russian name must read back as the English 'Read':\n%s" % text
    assert_not_contains(text, RIGHT_READ_RU,
                        "the default-language read of an 'en' configuration must render the "
                        "English right name, not the Russian one the write was addressed with")


# ──────────────────────────────────────────────────────────────────────────────
# Negative — a role payload cannot ride along with a generic properties change
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="modify_metadata", kind="write-metadata")
def test_role_rights_combined_with_properties_is_refused_and_writes_nothing():
    """A `rights` payload combined with a generic `properties` change is refused, and the refusal
    writes NOTHING - neither half of the call is applied.

    The "changed nothing" claim is measured from a snapshot taken AFTER the seed, not from HEAD:
    seeding the role legitimately dirties the tree, so plain assert_no_diff here would fail on the
    setup instead of on the operation under test (harness.tree_snapshot exists for exactly this
    shape). The rights resource is additionally required to be absent, which is the specific thing
    a half-applied call would have created."""
    name = "E2ERoleRightsMixed"
    fqn = _seed_role(name)

    before = tree_snapshot()
    r = call("modify_metadata", {
        "projectName": PROJECT, "fqn": fqn,
        "properties": [{"name": "comment", "value": "must not be applied"}],
        "rights": [{"object": GUARDED_OBJECT, "right": "Read", "value": "set"}],
    })
    e = assert_error(r, "a role rights payload combined with a generic properties change")
    assert_error_quality(e, names=["properties"], suggests=["separately"],
                         ctx="the refusal must name the payload that cannot be mixed and say what "
                             "to do instead (set the role's own properties in a separate call)")

    assert _rights_file_text(name) is None, (
        "a refused call must not have created %s - nothing may be applied when the payload is "
        "rejected" % _rights_file(name))
    assert_tree_unchanged(before,
                          "a refused role rights write must leave the project byte-for-byte as the "
                          "seed left it")
