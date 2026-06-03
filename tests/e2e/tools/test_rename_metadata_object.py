"""
e2e tests for rename_metadata_object (kind: write-metadata).

The tool renames a metadata object (or a nested attribute) with full refactoring:
it updates references in BSL code, forms and other metadata. ResponseType is
MARKDOWN, so the tool body is in r.text (NOT r.structured).

TWO-PHASE CONTRACT (RenameMetadataObjectTool + MetadataRenameService):
  * confirm absent/false  -> PREVIEW only. Emits YAML "action: preview", a
    "## Change Points" table and "> To execute, call with `confirm=true`."
    The model is NOT mutated.
  * confirm=true          -> EXECUTE. Performs every enabled change point. Emits
    YAML "action: executed" and "# Rename Completed".

HOW THE EFFECT IS VERIFIED (this batch is DIFFERENT):
  Metadata-write tools mutate EDT's IN-MEMORY BM model but do NOT flush the rename
  synchronously to disk, so `git diff` is empty/partial and CANNOT prove the
  rename. We therefore verify VIA MODEL READ-BACK over the wire:
    - object rename  -> get_metadata_objects: NEW name present, OLD name absent.
    - attribute rename -> get_metadata_details(full=true): the renamed attribute
      row appears in the Attributes table, the old one is gone.
  assert_no_diff() is NOT the happy-path guardrail here (the model changed while
  disk did not, so git is trivially clean -> it would prove nothing). It IS used on
  PREVIEW / REJECTED / NEGATIVE calls: a call that must not mutate must leave the
  working tree clean.

  The orchestrator runs reset_model() (clean_project, which refreshes the model
  from disk and discards the unsaved rename) AFTER each write-metadata test, so
  every test starts from the committed baseline. This test does NOT manage reset.

Whole-call error matrix (server sets isError via ToolResult.error):
  - missing projectName / objectFqn / newName -> "<name> is required" (+ usage)
  - non-existent project                      -> "Project not found: <name>"
  - non-existent / malformed object FQN       -> "Object not found: <fqn>. ..."

NOTE on substring matching: ToolResult.toJson() HTML-escapes the apostrophe and
'>' in the JSON error channel, so negative assertions only match delimiter-free
substrings (e.g. "is required", "not found", "Attribute"), never raw quoted
fragments such as 'Catalog.Products'.

Fixture (TestConfiguration, English Names): Catalog.Catalog (attribute "Attribute"),
CommonModule.Error / OK / Calc.
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


# ──────────────────────────────────────────────────────────────────────────────
# Read-back helpers (model truth over the wire — the primary verification)
# ──────────────────────────────────────────────────────────────────────────────

def _commonmodule_names(name_filter=None):
    """Return the get_metadata_objects MARKDOWN body for the commonModules family.

    Filtered to commonModules so only CommonModule rows appear; the Name column is
    the first cell of each row, so a row marker "| <Name> " uniquely identifies a
    present object regardless of substring collisions in type tokens.
    """
    args = {"projectName": PROJECT, "metadataType": "commonModules"}
    if name_filter is not None:
        args["nameFilter"] = name_filter
    r = call("get_metadata_objects", args)
    assert_ok(r, "read-back: list commonModules")
    return r.text


def _catalog_names():
    r = call("get_metadata_objects", {"projectName": PROJECT, "metadataType": "catalogs"})
    assert_ok(r, "read-back: list catalogs")
    return r.text


# ──────────────────────────────────────────────────────────────────────────────
# Happy path — EXECUTE (confirm=true): object rename, verified by model read-back
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_confirm_renames_common_module_and_readback_shows_new_name():
    # Rename CommonModule.Calc -> Compute. The new name deliberately shares NO
    # substring with "Calc", so an "old name absent" check on the row marker "| Calc "
    # is unambiguous. A broken/no-op rename would leave "Calc" present and "Compute"
    # absent -> this test FAILS.
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "CommonModule.Calc",
        "newName": "Compute",
        "confirm": True,
    })
    assert_ok(r, "execute rename CommonModule.Calc -> Compute")
    # Execute-mode markers (performRename): YAML action + completion header.
    assert_contains(r.text, "action: executed", "execute mode must emit YAML action: executed")
    assert_contains(r.text, "Rename Completed", "execute mode must emit the completion header")

    # PRIMARY proof: the in-memory model now reports the new module, not the old one.
    after_new = _commonmodule_names(name_filter="Compute")
    assert_contains(after_new, "Compute", "model read-back must show the renamed module 'Compute'")
    after_old = _commonmodule_names(name_filter="Calc")
    # The Name cell renders as "| Calc " at the start of a row; its absence proves
    # the old object is gone from the model (not merely filtered out — a still-present
    # 'Calc' would match nameFilter='Calc' and re-appear here).
    assert_not_contains(after_old, "| Calc ", "the old module 'Calc' must be ABSENT after the rename")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_confirm_renames_catalog_and_readback_shows_new_name():
    # Rename Catalog.Catalog -> Goods. New name shares no substring with "Catalog",
    # so the row-marker absence check is clean.
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "Catalog.Catalog",
        "newName": "Goods",
        "confirm": True,
    })
    assert_ok(r, "execute rename Catalog.Catalog -> Goods")
    assert_contains(r.text, "action: executed", "execute mode must emit YAML action: executed")
    assert_contains(r.text, "Rename Completed", "execute mode must emit the completion header")

    after = _catalog_names()
    assert_contains(after, "Goods", "model read-back must show the renamed catalog 'Goods'")
    # Robust 'old is gone' check that does NOT false-match the Type column ('Catalog' is
    # the metadata TYPE of the renamed object, so a name-cell substring is unsafe): the
    # NEW fqn resolves and the OLD fqn no longer does.
    new_ok = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": ["Catalog.Goods"]})
    assert_contains(new_ok.text, "Catalog: Goods", "the renamed object Catalog.Goods resolves in the model")
    old_gone = call("get_metadata_details", {"projectName": PROJECT, "objectFqns": ["Catalog.Catalog"]})
    assert_contains(old_gone.text.lower(), "not found", "the old fqn Catalog.Catalog must not resolve after the rename")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_confirm_renames_catalog_attribute_and_details_readback_shows_new_name():
    # Nested rename: Catalog.Catalog.Attribute.Attribute -> Title. Verified through a
    # DIFFERENT read tool (get_metadata_details full=true), whose ### Attributes table
    # lists attribute Names. The new attribute name must appear and the old one must
    # be gone from the table.
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "Catalog.Catalog.Attribute.Attribute",
        "newName": "Title",
        "confirm": True,
    })
    assert_ok(r, "execute rename of the Catalog attribute 'Attribute' -> 'Title'")
    assert_contains(r.text, "action: executed", "attribute rename must execute")
    assert_contains(r.text, "Rename Completed", "attribute rename must report completion")

    details = call("get_metadata_details", {
        "projectName": PROJECT,
        "objectFqns": ["Catalog.Catalog"],
        "full": True,
    })
    assert_ok(details, "read-back details for Catalog.Catalog after attribute rename")
    # The renamed attribute must now be listed.
    assert_contains(details.text, "Title", "details read-back must list the renamed attribute 'Title'")
    # And the old attribute name must no longer be a table row. The Attributes table
    # renders the Name as a leading cell "| Attribute "; assert that exact marker is gone.
    assert_not_contains(details.text, "| Attribute ",
                        "the old attribute 'Attribute' must be ABSENT from the Attributes table")


# ──────────────────────────────────────────────────────────────────────────────
# Happy path — PREVIEW (no confirm): lists change points AND does NOT mutate
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_preview_lists_change_points_and_does_not_mutate_module():
    # Without confirm the tool PREVIEWS: it must render the change-points table and
    # the "confirm=true" instruction, and the model must stay UNCHANGED (Calc still
    # present, Compute absent). A preview that secretly renamed would fail the
    # read-back below; a preview that produced nothing would fail the markers above.
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "CommonModule.Calc",
        "newName": "Compute",
        # confirm omitted -> preview
    })
    assert_ok(r, "preview rename CommonModule.Calc")
    assert_contains(r.text, "action: preview", "preview must emit YAML action: preview")
    assert_contains(r.text, "Refactoring Preview", "preview must emit the preview header")
    assert_contains(r.text, "Change Points", "preview must render the change-points table")
    assert_contains(r.text, "confirm=true", "preview must instruct how to execute (confirm=true)")

    # The model must be UNCHANGED by a preview.
    still = _commonmodule_names(name_filter="Calc")
    assert_contains(still, "| Calc ", "preview must NOT rename: 'Calc' must still be present")
    absent = _commonmodule_names(name_filter="Compute")
    assert_not_contains(absent, "Compute", "preview must NOT create the target name 'Compute'")
    # Rejected/preview guardrail: a non-executing call must not touch disk.
    assert_no_diff("a preview (confirm=false) must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_preview_for_catalog_does_not_mutate_catalog():
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "Catalog.Catalog",
        "newName": "Goods",
        "confirm": False,  # explicit preview
    })
    assert_ok(r, "preview rename Catalog.Catalog")
    assert_contains(r.text, "action: preview", "preview must emit YAML action: preview")
    assert_contains(r.text, "Change Points", "preview must render the change-points table")

    after = _catalog_names()
    assert_contains(after, "| Catalog ", "preview must NOT rename: 'Catalog' must still be present")
    assert_not_contains(after, "Goods", "preview must NOT create the target name 'Goods'")
    assert_no_diff("a preview (confirm=false) must not change the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — whole-call errors (server sets isError) + error quality
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_nonexistent_object_errors_and_is_actionable():
    bad = "Catalog.NoSuchCatalog_e2e"
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": bad,
        "newName": "Whatever",
        "confirm": True,  # even with confirm, an unresolved object must error, not mutate
    })
    err = assert_error(r, "rename of a non-existent object")
    # MetadataRenameService: "Object not found: <fqn>. Check the FQN format ...
    # Supported child types: Attribute, TabularSection, Dimension, Resource."
    # Names the bad value AND is actionable (states the expected format + child types).
    assert_error_quality(err, names=[bad], suggests=["not found", "format"],
                         ctx="non-existent object names value + states FQN format")
    assert_no_diff("a rejected rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_malformed_fqn_without_dot_errors():
    # No "Type.Name" separator -> resolveObject returns null -> "Object not found".
    bad = "JustAName"
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": bad,
        "newName": "Whatever",
    })
    err = assert_error(r, "malformed FQN (no dot)")
    assert_error_quality(err, names=[bad], suggests=["not found", "format"],
                         ctx="malformed FQN names value + states the expected format")
    assert_no_diff("a rejected rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_nonexistent_child_attribute_errors():
    # Top-level resolves but the nested Attribute does not -> findChild returns null
    # -> resolveObject null -> "Object not found".
    bad = "Catalog.Catalog.Attribute.NoSuchAttr_e2e"
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": bad,
        "newName": "Whatever",
        "confirm": True,
    })
    err = assert_error(r, "rename of a non-existent child attribute")
    assert_error_quality(err, names=[bad], suggests=["not found"],
                         ctx="non-existent child attribute names the full FQN")
    assert_no_diff("a rejected rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_nonexistent_project_errors_and_names_value():
    bogus = "NoSuchProject_zzz_e2e"
    r = call("rename_metadata_object", {
        "projectName": bogus,
        "objectFqn": "Catalog.Catalog",
        "newName": "Goods",
        "confirm": True,
    })
    err = assert_error(r, "non-existent project")
    # ProjectContext.exists() == false -> "Project not found: <name>".
    assert_error_quality(err, names=[bogus], suggests=["not found"],
                         ctx="non-existent project names the bad value")
    # AUDIT: "Project not found: <name>" names the bad project but offers no next step
    # (e.g. "use list_projects to see available projects"). Names-but-not-actionable.
    # Fix-card: append a list_projects discovery hint to MetadataRenameService.rename.
    assert_no_diff("a rejected rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_missing_projectname_errors():
    r = call("rename_metadata_object", {
        # projectName omitted on purpose
        "objectFqn": "Catalog.Catalog",
        "newName": "Goods",
        "confirm": True,
    })
    err = assert_error(r, "missing required projectName")
    # JsonUtils.requireArgument -> "projectName is required" (+ a verbatim usage hint).
    # The usage hint contains apostrophes which the JSON channel HTML-escapes, so we
    # only assert on the delimiter-free "is required" suggestion.
    assert_error_quality(err, names=["projectName"], suggests=["is required"],
                         ctx="missing projectName names the param")
    assert_no_diff("a rejected rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_missing_objectfqn_errors():
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        # objectFqn omitted on purpose
        "newName": "Goods",
        "confirm": True,
    })
    err = assert_error(r, "missing required objectFqn")
    assert_error_quality(err, names=["objectFqn"], suggests=["is required"],
                         ctx="missing objectFqn names the param")
    assert_no_diff("a rejected rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_missing_newname_errors():
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "Catalog.Catalog",
        # newName omitted on purpose
        "confirm": True,
    })
    err = assert_error(r, "missing required newName")
    assert_error_quality(err, names=["newName"], suggests=["is required"],
                         ctx="missing newName names the param")
    assert_no_diff("a rejected rename must not change the project on disk")
