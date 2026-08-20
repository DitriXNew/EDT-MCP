"""
e2e tests for get_metadata_details' ROLE branch - the access-rights matrix READ side (#454).

A Role FQN does not render the generic property block: GetMetadataDetailsTool.processFqn routes it
to RoleRightsReader, which renders one document - `# Role Rights: <fqn>`, then `## Properties`
(the three role booleans), `## Rights matrix` (Object | Right | Value), `## Row-level security
(RLS)` and `## RLS templates` - followed by the shared `**Origin:**` footer. A role whose
`Role.getRights()` is not a concrete RoleDescription renders the single note
`_(this role has no editable rights model)_` instead, and nothing else.

This file covers the five things only the wire can prove about that reader:

  * the empty state - a role created by create_metadata carries no editable rights model, and the
    note is what a caller sees (the stable read-side pin the #452 fix is measured against);
  * the VALUE labels - `allowed` (RightValue.SET) and `denied` (RightValue.UNSET) for two
    authored objects;
  * the TRI-STATE - `provided` is a third value, NOT a synonym for "denied": it is dropped from
    the default view (RoleRightsReader.isNonDefault is the eIsSet filter) and shows as `default`
    only under `full: true`. Any assertion about a PROVIDED cell therefore REQUIRES `full: true` -
    without it the assertion passes vacuously, because the row is absent either way;
  * `roleObjectOffset` - the matrix is paged by OBJECT in the default view and the offset is
    ignored under `full: true`;
  * `language` - for a Role FQN the language CODE also selects the language of the RIGHT NAMES
    (RoleRightsReader.rightNameOf reads Right.getNameRu() for "ru", Right.getName() otherwise).
    Both fixtures declare a single Language.English with code `en`, so this bilingual read is the
    only assertion in the whole suite that can observe that wiring at all. The language is always
    chosen by CODE (`en` / `ru`), never by a language display name (CLAUDE.md don't #2).

The whole-call negative matrix for this tool (missing projectName, missing/empty objectFqns, a
non-existent project, the per-object `## Errors` channel) lives in test_get_metadata_details.py and
is deliberately not repeated here; this file is the role-specific READ contract.

reset: kind="write-metadata" -> reset_fixture()+reset_model() after each test. These are reads, but
every one of them has to SEED the role it reads (see the fixture note), so they mutate the model and
must pay the full reset - a `kind="read"` test would leave the seeded role behind for the next test.

RUNNING THIS SLICE: `--filter role_rights`. run_all.py matches the filter against the TEST
NAME or the TOOL NAME, never the file name, so every test here is named
`test_role_rights_read_*`; the tool name `get_metadata_details` would otherwise be the only
handle and would drag in the whole base file. The same `role_rights` marker also selects the
write-side file, which is intended: the two are the two halves of #454 and the runner
executes them serially anyway.

FIXTURE TRUTH: the shipped TestConfiguration has NO Roles/ folder and no role at all, and only one
Catalog (Catalog.Catalog). Every test seeds what it needs under a unique name via create_metadata,
so nothing here depends on a role or a second object pre-existing.

PLATFORM TRUTH behind test_role_rights_read_matrix_renders_allowed_and_denied, read out of the
2026.2 bytecode of RightsModelUtil rather than assumed: AddRightValuesTask applies every cell
through `changeObjectRight(newValue, getDefaultRightValue(object, role), ...)`, which REFUSES
to create a cell whose value equals that default, and REMOVES an existing cell that drops onto
it (removeEmptyObjectRights then drops the whole `<object>` node once it empties). For a
NON-adopted top object in a base configuration that default is
`getRightValue(RoleDescription.isSetForNewObjects())`: with the flag false a `set` write lands
and an `unset` write is pruned to nothing, and with the flag true it is the other way round.
So a role cannot hold an `allowed` AND a `denied` cell written under one setting of that flag,
and the test flips `setForNewObjects` BETWEEN its two writes - which is why it spends four
modify_metadata calls instead of one. Ordering inside a single call cannot substitute:
RoleRightsWriter.apply runs rights[] BEFORE roleProperties, so a flag sent alongside the cells
arrives too late to change their default.
"""

import os

from harness import (
    call,
    assert_ok,
    assert_contains,
    assert_not_contains,
    poll_disk_contains,
    split_markdown_row,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
    PROJECT_DIR,
)


# The note RoleRightsReader.render emits INSTEAD of the whole document when Role.getRights() is not
# a concrete RoleDescription. Asserted literally: it is what a caller reads.
_NO_MATRIX_NOTE = "_(this role has no editable rights model)_"

_MATRIX_HEADING = "## Rights matrix"

# Right names as the platform spells them. The Russian one is built from CODE POINTS so this source
# file stays pure ASCII (CLAUDE.md don't #7): U+0427 U+0442 U+0435 U+043D U+0438 U+0435 is the
# Cyrillic "Chtenie" - the nameRu of the very same Right whose name is "Read".
_RIGHT_READ = "Read"
_RIGHT_READ_RU = "\u0427\u0442\u0435\u043d\u0438\u0435"
_RIGHT_UPDATE = "Update"

# Value labels (RoleRightsReader.rightValueLabel): SET / UNSET / PROVIDED.
_ALLOWED = "allowed"
_DENIED = "denied"
_DEFAULT = "default"

# The rights matrix is its own BM resource next to Role.mdo, named by EDT's own
# ROLES_FOLDER_NAME / RIGHTS_FILE_NAME constants (Roles/<Name>/Rights.rights).
def _rights_file(role_name):
    return "src/Roles/%s/Rights.rights" % role_name


def _rights_file_text(role_name):
    """The role's Rights.rights exactly as it is on disk right now, or None when the file does not
    exist at all.

    Deliberately a plain read rather than one of the export-ordered disk helpers: the "no rights
    model yet" assertion is made after a READ call that touched no file and submitted no export,
    so assert_disk_path_gone's precondition (the same call removed the path or queued the export
    that removes it) does not hold and its failure text would blame an export race that is not the
    defect. poll_disk_lacks / assert_disk_lacks are wrong for the opposite reasons - one would only
    burn the polling budget on a file nothing is going to create, the other requires the file to
    exist. The sibling write-side file keeps the same helper for the same reason."""
    full = os.path.join(PROJECT_DIR, *_rights_file(role_name).split("/"))
    if not os.path.isfile(full):
        return None
    with open(full, encoding="utf-8", errors="replace") as f:
        return f.read()


# ---------------------------------------------------------------------------
# Seeding helpers
# ---------------------------------------------------------------------------

def _seed_role(name):
    """Creates Role.<name> and returns its FQN. A role created this way has NO rights model."""
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Role." + name})
    assert_ok(r, "seed Role.%s" % name)
    wait_for_project_ready()
    return "Role." + name


def _seed_catalog(name):
    """Creates a SECOND rights-bearing object. Catalog.Catalog is the fixture's only one, and the
    matrix is paged BY OBJECT, so the paging / two-value tests need one more. A Catalog is used
    (rather than a subsystem or session parameter) so both objects carry the very same right names,
    which keeps the assertions about Read / Chtenie applicable to either of them."""
    r = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog." + name})
    assert_ok(r, "seed Catalog.%s" % name)
    wait_for_project_ready()
    return "Catalog." + name


def _write_rights(role_fqn, entries):
    r = call("modify_metadata", {"projectName": PROJECT, "fqn": role_fqn, "rights": entries})
    assert_ok(r, "modify_metadata rights %r on %s" % (entries, role_fqn))
    return r


def _set_rights_for_new_objects(role_fqn, value):
    """Sets the role flag that decides the DEFAULT right value for every object in the role (see
    the PLATFORM TRUTH paragraph in the module docstring)."""
    r = call("modify_metadata", {"projectName": PROJECT, "fqn": role_fqn,
                                 "roleProperties": {"setForNewObjects": value}})
    assert_ok(r, "modify_metadata roleProperties setForNewObjects=%r on %s" % (value, role_fqn))
    return r


def _read_role(role_fqn, **options):
    """Reads one Role FQN. `options` are passed through verbatim (full / language /
    roleObjectOffset), so a test never hides which view it asked for."""
    args = {"projectName": PROJECT, "objectFqns": [role_fqn]}
    args.update(options)
    r = call("get_metadata_details", args)
    assert_ok(r, "get_metadata_details %s %r" % (role_fqn, options))
    return r.text or ""


# ---------------------------------------------------------------------------
# Rendered-document parsing
# ---------------------------------------------------------------------------

def _matrix_section(text):
    """The text of the `## Rights matrix` section only.

    Scoped deliberately: the object FQN of a restricted object is ALSO printed in the RLS section,
    so a whole-document substring search could not tell a matrix row from an RLS row - and a
    pagination assertion built on that could not fail when paging broke."""
    idx = text.find(_MATRIX_HEADING)
    if idx < 0:
        raise AssertionError("the rendered role document has no %r section:\n%s"
                             % (_MATRIX_HEADING, text[:600]))
    rest = text[idx + len(_MATRIX_HEADING):]
    end = rest.find("\n## ")
    return rest if end < 0 else rest[:end]


def _matrix_rows(text):
    """The matrix's data rows as (object, right, value) tuples, in rendered order.

    Rows go through the shared escape-aware harness parser, never a naive split('|'): production
    escapes a literal '|' inside a cell as '\\|' precisely so it is not a delimiter, and a naive
    split would cut such a row into four cells, which an exact-3 filter would then DROP - a real
    row, silently invisible to the assertion."""
    rows = []
    for line in _matrix_section(text).splitlines():
        cells = split_markdown_row(line)
        if len(cells) != 3:
            continue
        if cells == ["Object", "Right", "Value"] or set(cells) == {"---"}:
            continue
        rows.append(tuple(cells))
    return rows


def _matrix_objects(rows):
    """The distinct object FQNs of the matrix rows, in rendered order (the paged unit is the
    OBJECT, not the row, so paging assertions are made on this)."""
    seen = []
    for obj, _right, _value in rows:
        if obj not in seen:
            seen.append(obj)
    return seen


def _cell(rows, obj, right):
    """The value rendered for one object+right, or None when that cell is not in the view."""
    for row_obj, row_right, row_value in rows:
        if row_obj == obj and row_right == right:
            return row_value
    return None


# ---------------------------------------------------------------------------
# The empty state
# ---------------------------------------------------------------------------

@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_role_rights_read_note_when_role_has_no_rights_model():
    """A role created through create_metadata has no rights model, and the reader says exactly
    that - it does not fabricate an empty matrix and it does not fail the object.

    This is the read-side pin the #452 flip is measured against: the same call renders this note
    before the first rights write and the full document after it (see
    test_role_rights_read_matrix_renders_allowed_and_denied). Both halves are asserted - the MODEL
    (the note, and the absence of every section the note replaces) and the DISK (no Rights.rights
    resource exists yet) - so a build that quietly created a rights model at object-creation time,
    or a reader that rendered the note over a model that does have one, both fail here."""
    name = "E2ERoleNoMatrix"
    role_fqn = _seed_role(name)

    text = _read_role(role_fqn)
    assert_contains(text, "# Role Rights: " + role_fqn,
                    "a Role FQN renders the rights document, headed by the normalized FQN")
    assert_contains(text, _NO_MATRIX_NOTE,
                    "a role with no editable rights model renders the note verbatim")
    assert_not_contains(text, "## Properties",
                        "the note REPLACES the document: no role-property table may be rendered")
    assert_not_contains(text, _MATRIX_HEADING,
                        "the note REPLACES the document: no matrix section may be rendered")
    assert_contains(text, "**Origin:** core",
                    "the role still resolved as a base object, so the origin footer is rendered")
    assert_not_contains(text, "## Errors",
                        "a role without a rights model is NOT a per-object resolution failure")

    assert _rights_file_text(name) is None, (
        "a role created with no rights write must carry no Rights.rights resource - that absence "
        "is the on-disk half of the note above; %s exists" % _rights_file(name))


# ---------------------------------------------------------------------------
# The value labels
# ---------------------------------------------------------------------------

@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_role_rights_read_matrix_renders_allowed_and_denied():
    """The default view lists one row per authored cell, grouped by object, with the tri-state
    label of its value: SET -> `allowed`, UNSET -> `denied`.

    The two writes are made under OPPOSITE settings of `setForNewObjects` on purpose - see the
    PLATFORM TRUTH paragraph in the module docstring: a cell whose value equals the role's current
    default is never created (and an existing one is removed), so a role cannot carry both an
    `allowed` and a `denied` cell written under one setting of that flag. The flip is what makes
    the `denied` half a real cell rather than a pruned no-op.

    Both halves are verified: the matrix as the reader renders it (MODEL) and the two `<object>`
    entries in the role's own Rights.rights (DISK). Without the disk check a reader that invented
    rows from an unflushed model would still pass; without the model check a correct file rendered
    wrongly would."""
    name = "E2ERoleMatrix"
    role_fqn = _seed_role(name)
    other = _seed_catalog("E2ERightsCatalog")

    # Default = UNSET while the flag is false, so this SET cell is non-default and is kept.
    _set_rights_for_new_objects(role_fqn, False)
    _write_rights(role_fqn, [{"object": "Catalog.Catalog", "right": _RIGHT_READ, "value": "set"}])
    # Default = SET once the flag is true, so this UNSET cell is non-default and is kept too.
    _set_rights_for_new_objects(role_fqn, True)
    _write_rights(role_fqn, [{"object": other, "right": _RIGHT_READ, "value": "unset"}])

    poll_disk_contains(_rights_file(name), "<name>Catalog.Catalog</name>",
                       ctx="the allowed cell's object must reach the role's rights resource")
    poll_disk_contains(_rights_file(name), "<name>%s</name>" % other,
                       ctx="the denied cell's object must reach the role's rights resource")

    text = _read_role(role_fqn)
    rows = _matrix_rows(text)
    assert _cell(rows, "Catalog.Catalog", _RIGHT_READ) == _ALLOWED, (
        "a SET cell must render as %r; matrix rows were %r" % (_ALLOWED, rows))
    assert _cell(rows, other, _RIGHT_READ) == _DENIED, (
        "an UNSET cell must render as %r - a label that collapsed the tri-state onto a boolean "
        "would render it as %r; matrix rows were %r" % (_DENIED, _ALLOWED, rows))
    assert_contains(text, "**Objects with non-default rights:** 2",
                    "both objects carry an authored cell, so the summary count is 2")


# ---------------------------------------------------------------------------
# The tri-state
# ---------------------------------------------------------------------------

@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_role_rights_read_provided_hidden_by_default_shown_in_full():
    """`provided` is the THIRD RightValue, not a spelling of "denied": the right falls back to its
    inherited/default value. RoleRightsReader treats it as the untouched metamodel default (the
    eIsSet filter), so its row is dropped from the default view and rendered as `default` only
    under `full: true`.

    Both directions are asserted, which is what makes this test able to fail: an assertion made
    WITHOUT `full: true` would pass vacuously (the row is absent whether or not the reader can
    render it), and an assertion made only WITH it could not tell the filter from a reader that
    renders everything always.

    Both cells are written on the SAME object, deliberately: the object then qualifies for the
    default view through its `set` cell, so the `provided` cell's absence there is the row filter
    at work and not merely the object-level filter that would drop the object either way.

    The on-disk shape of a `provided` cell is NOT asserted - `Provided` is first in the
    RightValue enum and therefore the EMF attribute default, so the saver omits it; pinning that
    absence would pin a shape 1C itself never writes (see the branch's PR body)."""
    name = "E2ERoleTriState"
    role_fqn = _seed_role(name)
    _write_rights(role_fqn, [
        {"object": "Catalog.Catalog", "right": _RIGHT_READ, "value": "set"},
        {"object": "Catalog.Catalog", "right": _RIGHT_UPDATE, "value": "provided"},
    ])
    poll_disk_contains(_rights_file(name), "<name>Catalog.Catalog</name>",
                       ctx="the authored object must reach the role's rights resource")

    default_rows = _matrix_rows(_read_role(role_fqn))
    assert _cell(default_rows, "Catalog.Catalog", _RIGHT_READ) == _ALLOWED, (
        "the authored SET cell must still be visible in the default view; rows were %r"
        % (default_rows,))
    assert _cell(default_rows, "Catalog.Catalog", _RIGHT_UPDATE) is None, (
        "a PROVIDED cell is the untouched default and must NOT be listed in the default view; "
        "rows were %r" % (default_rows,))
    assert [r for r in default_rows if r[2] == _DEFAULT] == [], (
        "no %r-valued row may appear in the default view at all; rows were %r"
        % (_DEFAULT, default_rows))

    full_rows = _matrix_rows(_read_role(role_fqn, full=True))
    assert _cell(full_rows, "Catalog.Catalog", _RIGHT_UPDATE) == _DEFAULT, (
        "under full:true the PROVIDED cell must render, and as %r - not as %r and not as %r; "
        "rows were %r" % (_DEFAULT, _ALLOWED, _DENIED, full_rows))
    assert _cell(full_rows, "Catalog.Catalog", _RIGHT_READ) == _ALLOWED, (
        "full:true adds the default cells, it does not replace the authored ones; rows were %r"
        % (full_rows,))


# ---------------------------------------------------------------------------
# Pagination
# ---------------------------------------------------------------------------

@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_role_rights_read_object_offset_pages_the_matrix():
    """`roleObjectOffset` skips whole OBJECTS in the default view, and is ignored under
    `full: true`.

    The expected page-1 object is taken from the page-0 rendering rather than hardcoded: the
    matrix collection is run through EDT's runtime-order sorter after every write, so which
    object sorts first is the platform's business, not this test's. What the test pins is the
    RELATION - page 1 shows the object page 0 showed second, and not the one it showed first -
    which is exactly what an offset that stopped being applied would break."""
    name = "E2ERolePaging"
    role_fqn = _seed_role(name)
    other = _seed_catalog("E2EPagingCatalog")
    _write_rights(role_fqn, [
        {"object": "Catalog.Catalog", "right": _RIGHT_READ, "value": "set"},
        {"object": other, "right": _RIGHT_READ, "value": "set"},
    ])
    poll_disk_contains(_rights_file(name), "<name>%s</name>" % other,
                       ctx="both authored objects must reach the role's rights resource")

    page0 = _matrix_objects(_matrix_rows(_read_role(role_fqn, roleObjectOffset=0)))
    assert len(page0) == 2, (
        "both authored objects must be listed at offset 0 - the paging assertions below mean "
        "nothing otherwise; objects were %r" % (page0,))
    first, second = page0

    page1_text = _read_role(role_fqn, roleObjectOffset=1)
    page1 = _matrix_objects(_matrix_rows(page1_text))
    assert page1 == [second], (
        "offset 1 must render exactly the SECOND object (%r) and skip the first (%r); objects "
        "were %r" % (second, first, page1))
    assert_contains(page1_text, "(showing objects 2-2 of 2)",
                    "the window notice must report the object window actually rendered")

    full1 = _matrix_objects(_matrix_rows(_read_role(role_fqn, full=True, roleObjectOffset=1)))
    assert sorted(full1) == sorted(page0), (
        "full:true renders every object and ignores roleObjectOffset, so the same two objects "
        "must come back at offset 1; objects were %r" % (full1,))


# ---------------------------------------------------------------------------
# Language
# ---------------------------------------------------------------------------

@e2e_test(tool="get_metadata_details", kind="write-metadata")
def test_role_rights_read_language_code_selects_right_names():
    """For a Role FQN the `language` CODE also picks the language of the RIGHT NAMES: the reader
    reads Right.getNameRu() for "ru" and Right.getName() otherwise.

    This is the only assertion in the suite that can observe that wiring. Both fixtures declare a
    single Language.English whose code is `en`, so every synonym-based bilingual assertion
    elsewhere reads the same text in both languages; the right names come from the PLATFORM, which
    ships both spellings whatever the configuration declares. A build that stopped forwarding the
    resolved code into the reader would keep every other test green and fail only here.

    The language is chosen by CODE, never by a language display name (CLAUDE.md don't #2), and the
    two readings are compared cell-for-cell, so a `ru` read that silently fell back to English
    fails instead of passing on a substring that is present either way."""
    name = "E2ERoleLanguage"
    role_fqn = _seed_role(name)
    _write_rights(role_fqn, [{"object": "Catalog.Catalog", "right": _RIGHT_READ, "value": "set"}])
    poll_disk_contains(_rights_file(name), "<name>Catalog.Catalog</name>",
                       ctx="the authored object must reach the role's rights resource")

    # No `language`: the configuration default code, which TestConfiguration declares as "en".
    default_names = [right for obj, right, _v in _matrix_rows(_read_role(role_fqn))
                     if obj == "Catalog.Catalog"]
    assert default_names == [_RIGHT_READ], (
        "the default-language read must name the right %r; it named %r"
        % (_RIGHT_READ, default_names))

    ru_names = [right for obj, right, _v in _matrix_rows(_read_role(role_fqn, language="ru"))
                if obj == "Catalog.Catalog"]
    assert ru_names == [_RIGHT_READ_RU], (
        "the language='ru' read must name the same right by its Russian platform name; it named "
        "%r (a fallback to the English %r means the code never reached the reader)"
        % (ru_names, _RIGHT_READ))
