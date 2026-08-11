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

HOW THE EFFECT IS VERIFIED (two ways — the model AND the disk):
  PRIMARY: MODEL READ-BACK over the wire —
    - object rename  -> get_metadata_objects: NEW name present, OLD name absent.
    - attribute rename -> get_metadata_details(full=true): the renamed attribute
      row appears in the Attributes table, the old one is gone.
  ON DISK: a rename persists richly (the folder/.mdo are renamed Calc/ -> Compute/,
  the old .mdo deleted, Configuration.mdo's collection reference updated). The
  object-rename happy test additionally asserts WHAT changed on disk via
  poll_diff_contains — the Configuration collection element gains the new FQN AND the
  renamed object's own .mdo carries <name>Compute</name> (the export lags ~1-2s, hence
  poll). assert_no_diff() is NOT the happy-path guardrail (a rename legitimately
  changes the tree); it IS used on PREVIEW / REJECTED / NEGATIVE calls — a call that
  must not mutate must leave the working tree clean.

  The orchestrator runs reset_model() (clean_project, which refreshes the model
  from disk and discards the unsaved rename) AFTER each write-metadata test, so
  every test starts from the committed baseline. This test does NOT manage reset.

BOUNDED WAIT (issue #365): the cascade runs on EDT's UI thread and the call waits for
it with a deadline, exposed as `timeout` (seconds, default 420, clamped 60..3600). On
expiry the call returns an error naming the stage the rename reached instead of hanging
the wire. Only the wire contract is testable here — see the section near the bottom.

Whole-call error matrix (server sets isError via ToolResult.error):
  - missing projectName / objectFqn / newName -> "<name> is required" (+ usage)
  - non-existent project                      -> "Project not found: <name>"
  - non-existent / malformed object FQN       -> "Object not found: <fqn>. ..."

NOTE on substring matching: ToolResult.toJson() HTML-escapes the apostrophe and
'>' in the JSON error channel, so negative assertions only match delimiter-free
substrings (e.g. "is required", "not found", "Attribute"), never raw quoted
fragments such as 'Catalog.Products'.

Fixture (TestConfiguration, English Names): Catalog.Catalog (attribute "Attribute"),
CommonModule.Error / OK / Calc. Cascade-only modules (dedicated, do not perturb the
count-asserted objects): CommonModule.CascadeEn (English Name) and CommonModule.Вычисление
(Russian Name), both called from CommonModule.CascadeUser — see the cascade section.
"""

import time
import xml.etree.ElementTree as ET

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_contains,
    assert_not_contains,
    assert_no_diff,
    poll_diff_contains,
    poll_disk_contains,
    poll_disk_lacks,
    read_disk,
    wait_for_project_ready,
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
    # ON DISK: the rename persists (the folder/.mdo were renamed Calc/ -> Compute/, and
    # Configuration.mdo's reference updated). Assert WHAT changed: the Configuration
    # collection element gains the new FQN, and the renamed object's own .mdo carries
    # <name>Compute</name>. The export lags a beat, so poll. ("Compute" appears nowhere
    # in the fixture before the rename, so both substrings can only come from the rename.)
    poll_diff_contains("<commonModules>CommonModule.Compute</commonModules>",
                       ctx="rename must update the Configuration.mdo reference to the new name on disk")
    poll_diff_contains("<name>Compute</name>",
                       ctx="rename must write the renamed object's own .mdo (<name>Compute</name>) on disk")


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
# Cascade — the #1 corruption risk: a rename must rewrite BSL references too
#
# CascadeUser (a dedicated fixture module) calls CascadeEn.Marker() and
# Вычисление.Маркер(). Renaming the OBJECT must update those call sites. The card
# (e2e-rename-cascade-verification) calls this out as the single most valuable
# missing test: a rename that updates the object but NOT the BSL reference is exactly
# the silent corruption the tool exists to prevent. Verified the card's way —
# search_in_code old-vs-new — plus a direct read of the rewritten source line.
#
# Timing assumption: the rename goes through EDT's LTK engine, which applies the
# BSL text change SYNCHRONOUSLY inside the tool call (perform() completes before the
# call returns), and the server is single-threaded, so the very next search/read sees
# the rewrite without a poll. If a future EDT made that flush async and this flakes,
# wrap the post-rename reads in a poll_* helper — do NOT weaken these assertions.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_cascade_rewrites_english_named_module_reference_in_bsl():
    # New name "Reckoner" shares no substring with "CascadeEn", so the search
    # assertions are unambiguous. A rename that left the BSL reference untouched would
    # keep "CascadeEn.Marker" in the code and FAIL every cascade assertion below.
    base = call("search_in_code", {"projectName": PROJECT,
                                    "query": "CascadeEn.Marker", "outputMode": "files"})
    assert_ok(base, "baseline search for CascadeEn.Marker")
    assert_contains(base.text, "CommonModules/CascadeUser/Module.bsl",
                    "fixture precondition: CascadeUser references CascadeEn.Marker before the rename")

    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "CommonModule.CascadeEn",
        "newName": "Reckoner",
        "confirm": True,
    })
    assert_ok(r, "execute rename CommonModule.CascadeEn -> Reckoner")
    assert_contains(r.text, "action: executed", "the cascade rename must execute")

    # CASCADE PROOF #1 (the card's chosen verifier): the OLD token is gone project-wide,
    # the NEW token now appears in the caller.
    gone = call("search_in_code", {"projectName": PROJECT,
                                    "query": "CascadeEn.Marker", "outputMode": "count"})
    assert_contains(gone.text, "Total matches:** 0",
                    "after the cascade rename the old reference CascadeEn.Marker must be gone everywhere")
    moved = call("search_in_code", {"projectName": PROJECT,
                                    "query": "Reckoner.Marker", "outputMode": "files"})
    assert_contains(moved.text, "CommonModules/CascadeUser/Module.bsl",
                    "the rewritten reference Reckoner.Marker must appear in CascadeUser")
    # CASCADE PROOF #2: the exact source line was rewritten (strongest single check).
    src = call("read_module_source", {"projectName": PROJECT,
                                      "modulePath": "CommonModules/CascadeUser/Module.bsl"})
    assert_ok(src, "read CascadeUser source after the cascade rename")
    assert_contains(src.text, "Reckoner.Marker()",
                    "CascadeUser must now call the renamed module Reckoner.Marker()")
    assert_not_contains(src.text, "CascadeEn",
                        "CascadeUser must retain no trace of the old module name CascadeEn")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_cascade_rewrites_russian_named_module_reference_in_bsl():
    # Bilingual cascade: the renamed object has a RUSSIAN (Cyrillic) Name. A cascade
    # that mishandled the Cyrillic identifier — the exact failure mode this case exists
    # to catch — would leave the old reference and FAIL. "Вычислитель" is not a
    # substring of "Вычисление" past the shared "Вычисл" stem, so the dotted-method
    # searches are unambiguous.
    base = call("search_in_code", {"projectName": PROJECT,
                                    "query": "Вычисление.Маркер", "outputMode": "files"})
    assert_ok(base, "baseline search for Вычисление.Маркер")
    assert_contains(base.text, "CommonModules/CascadeUser/Module.bsl",
                    "fixture precondition: CascadeUser references Вычисление.Маркер before the rename")

    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "CommonModule.Вычисление",
        "newName": "Вычислитель",
        "confirm": True,
    })
    assert_ok(r, "execute rename CommonModule.Вычисление -> Вычислитель")
    assert_contains(r.text, "action: executed", "the bilingual cascade rename must execute")

    gone = call("search_in_code", {"projectName": PROJECT,
                                   "query": "Вычисление.Маркер", "outputMode": "count"})
    assert_contains(gone.text, "Total matches:** 0",
                    "after the cascade rename the old Cyrillic reference Вычисление.Маркер must be gone")
    moved = call("search_in_code", {"projectName": PROJECT,
                                    "query": "Вычислитель.Маркер", "outputMode": "files"})
    assert_contains(moved.text, "CommonModules/CascadeUser/Module.bsl",
                    "the rewritten Cyrillic reference Вычислитель.Маркер must appear in CascadeUser")
    src = call("read_module_source", {"projectName": PROJECT,
                                      "modulePath": "CommonModules/CascadeUser/Module.bsl"})
    assert_ok(src, "read CascadeUser source after the bilingual cascade rename")
    assert_contains(src.text, "Вычислитель.Маркер()",
                    "CascadeUser must now call the renamed module Вычислитель.Маркер()")
    assert_not_contains(src.text, "Вычисление",
                        "CascadeUser must retain no trace of the old Cyrillic module name Вычисление")


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
# The cascade bound (issue #365)
#
# The rename runs on EDT's UI thread; nothing in that hand-off had an upper bound,
# so a wedged cascade held the MCP call open until the CLIENT gave up — which aborted
# the whole run and skipped ~188 tests. The bound now lives on our side, exposed as
# the `timeout` parameter (seconds, clamped 60..3600).
#
# What is verifiable HERE is the wire contract: the parameter is accepted and the
# rename still works with an explicit value. The expiry itself is NOT reproducible on
# demand (it needs a wedged EDT), so it is pinned by the unit test that drives the
# deadline through the IRenameAction seam — see RenameMetadataObjectToolTest.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_explicit_timeout_is_accepted_and_preview_still_works():
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "CommonModule.Calc",
        "newName": "Compute",
        "timeout": 900,  # inside the accepted 60..3600 range
    })
    assert_ok(r, "preview rename with an explicit timeout")
    assert_contains(r.text, "action: preview", "an explicit timeout must not change the preview contract")
    assert_no_diff("a preview (confirm=false) must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_out_of_range_timeout_is_clamped_not_rejected():
    # The schema promises clamping, not rejection: 1 second is below the 60s floor and
    # must be raised, so the call behaves exactly like the default one above.
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "CommonModule.Calc",
        "newName": "Compute",
        "timeout": 1,
    })
    assert_ok(r, "an out-of-range timeout must be clamped, not rejected")
    assert_contains(r.text, "action: preview", "a clamped timeout must not change the preview contract")
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


# ──────────────────────────────────────────────────────────────────────────────
# Managed-form ELEMENTS (issue #381)
#
# A form-element FQN is dispatched to its own branch BEFORE the mdclass path and
# renamed through EDT's OWN form refactoring, so the two-phase contract, the '#'
# indices and the confirm gate are the same ones the tests above pin. What is NOT
# the same is the cascade: it is scoped to the form (its model plus its module),
# which is exactly what the module test below measures rather than assumes.
#
# Fixture: Catalog.Catalog has form "ItemForm" carrying fields Code / Description /
# Attribute, a Decoration1, and the designer's own children (CodeExtendedTooltip,
# CodeContextMenu, ...) that no rename may touch directly.
# ──────────────────────────────────────────────────────────────────────────────

_FORM = "src/Catalogs/Catalog/Forms/ItemForm/Form.form"
# The module tools address a module RELATIVE TO src/, while read_disk / poll_disk_* address
# it relative to the PROJECT. Passing the project-relative path to write_module_source is not
# an error - it silently writes src/src/... beside the real tree - so the two spellings are
# named apart here rather than derived from one another.
_FORM_MODULE_SRC_REL = "Catalogs/Catalog/Forms/ItemForm/Module.bsl"


def _form_fqn(kind, name):
    return "Catalog.Catalog.Form.ItemForm.%s.%s" % (kind, name)


def _seed_form_attribute(attr):
    r = call("create_metadata", {"projectName": PROJECT, "fqn": _form_fqn("Attribute", attr)})
    assert_ok(r, "seed form attribute " + attr)
    wait_for_project_ready()


def _seed_form_group(grp):
    r = call("create_metadata", {"projectName": PROJECT, "fqn": _form_fqn("Group", grp)})
    assert_ok(r, "seed form group " + grp)
    wait_for_project_ready()


def _await_preview_change_point(object_fqn, new_name, marker, timeout=90):
    """Poll the (side-effect-free) rename PREVIEW until its text contains `marker`.

    `marker` should identify the SPECIFIC change point being waited for (the rendered source
    line, say), never just its type tag: a tag is shared by unrelated change points and would
    let the gate open on one of those instead.

    Fails with the last preview attached rather than skipping: a preview without the change
    point means the cascade would not have covered it, and that is the thing under test.
    """
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        r = call("rename_metadata_object", {
            "projectName": PROJECT, "objectFqn": object_fqn, "newName": new_name})
        assert_ok(r, "preview the rename while waiting for the %s change point" % marker)
        last = r.text
        if marker in last:
            return last
        time.sleep(3)
    raise AssertionError(
        "the preview never listed a %s change point within %ss, so the cascade under test "
        "was never planned; last preview:\n%s" % (marker, timeout, last))


def _form_item_titles(name):
    """The (languageKey, text) pairs of a form ITEM's <title> entries on disk; [] when untitled."""
    root = ET.fromstring(read_disk(_FORM))
    for item in root.iter("items"):
        child = item.find("name")
        if child is not None and child.text == name:
            return [(t.findtext("key"), t.findtext("value")) for t in item.findall("title")]
    raise AssertionError("form item %s is not in %s" % (name, _FORM))


def _form_module_source():
    """ItemForm's module, read through read_module_source.

    NB that tool reads the exported FILE (BslModuleUtils.readFileLines on the IFile), so this is
    disk truth reached over the wire - not the model. Anything asserting a change that a write has
    to export must therefore POLL; see _poll_form_module_contains.
    """
    r = call("read_module_source", {
        "projectName": PROJECT, "modulePath": _FORM_MODULE_SRC_REL})
    assert_ok(r, "read the form module back")
    return r.text


def _poll_form_module_contains(substr, timeout=30, ctx=""):
    """Poll the form module until it contains substr; returns the module text that satisfied it.

    A rename exports the .form and the module SEPARATELY, so seeing the renamed element in
    Form.form says nothing about whether Module.bsl has been written yet. Reading it once right
    after would fail on export ordering alone - a flake that looks exactly like a missing cascade.
    """
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        last = _form_module_source()
        if substr in last:
            return last
        time.sleep(1)
    raise AssertionError("the form module never came to contain %r within %ss [%s]; it holds:\n%s"
                         % (substr, timeout, ctx, last))


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_form_element_preview_lists_change_points_and_does_not_mutate():
    # The preview half of the two-phase contract on a FORM target: same YAML action, same
    # "Change Points" table, same confirm instruction - and nothing written.
    _seed_form_attribute("RNPreviewAttr")
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": _form_fqn("Attribute", "RNPreviewAttr"),
        "newName": "RNPreviewAttrRenamed",
    })
    assert_ok(r, "preview a form-attribute rename")
    assert_contains(r.text, "action: preview", "a form preview must emit the preview action")
    assert_contains(r.text, "Change Points", "a form preview must render the change-points table")
    assert_contains(r.text, "confirm=true", "a form preview must instruct how to execute")
    # The heading and the footer are emitted UNCONDITIONALLY, so asserting them alone would also
    # pass on a preview that found nothing to change - the exact shape a broken form branch would
    # produce. The count and the rename row are what say the refactoring really resolved a target.
    assert "totalChanges: 0" not in r.text, \
        "a form preview that found no change point has not previewed anything: %r" % r.text
    assert_contains(r.text, "RNPreviewAttrRenamed",
                    "the change-points table must name what the rename would produce")
    assert_contains(read_disk(_FORM), "RNPreviewAttr",
                    "the seeded attribute must still be there under its OLD name")
    assert_not_contains(read_disk(_FORM), "RNPreviewAttrRenamed",
                        "a preview must not write the new name into the form")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_confirm_renames_a_form_attribute_and_the_form_on_disk_follows():
    _seed_form_attribute("RNAttr")
    poll_disk_contains(_FORM, "<name>RNAttr</name>", ctx="the seeded attribute must be on disk")
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": _form_fqn("Attribute", "RNAttr"),
        "newName": "RNAttrRenamed",
        "confirm": True,
    })
    assert_ok(r, "rename a form attribute (confirm)")
    assert_contains(r.text, "action: executed", "confirm must execute")
    poll_disk_contains(_FORM, "<name>RNAttrRenamed</name>",
                       ctx="the new attribute name must land in the form's .form on disk")
    poll_disk_lacks(_FORM, "<name>RNAttr</name>",
                    ctx="the old attribute name must be gone from the .form")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_confirm_renames_a_form_group_and_the_module_reference_follows():
    """The CASCADE, measured rather than assumed: a form-module reference to the element
    must be rewritten by the rename.

    This is the whole reason the branch calls EDT's own form refactoring instead of writing
    the name itself - a plain name write would leave the module's reference pointing at
    nothing, which is worse than refusing the rename. The probe is planted and READ BACK
    before the rename, so a module that never made it into the model fails the test here
    instead of turning the cascade assertion vacuous.
    """
    grp = "RNProbeGroup"
    _seed_form_group(grp)
    probe = (
        "&НаКлиенте\n"
        "Процедура E2ERenameProbe()\n"
        "\tЭлементы." + grp
        + ".Видимость = "
        "Ложь;\n"
        "КонецПроцедуры\n"
    )
    w = call("write_module_source", {
        "projectName": PROJECT, "modulePath": _FORM_MODULE_SRC_REL,
        "mode": "replace", "overwrite": True, "source": probe,
    })
    assert_ok(w, "plant a module reference to the group")
    wait_for_project_ready()
    _poll_form_module_contains(grp, ctx="setup: the planted reference must be in the module")
    # GATE, not a sleep: a module written seconds ago may not be in EDT's index yet, and a
    # rename that runs first simply finds no BSL reference to rewrite. The preview is the
    # observable that says the cascade WILL include the module, so poll it until it does.
    # The marker is the PLANTED LINE as the preview's code context renders it - not the
    # change-point TYPE tag `bslRef`, which several unrelated native changes also carry and
    # which would therefore let the gate open on something else entirely.
    _await_preview_change_point(_form_fqn("Group", grp), "RNProbeGroupRenamed",
                                "Элементы." + grp)

    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": _form_fqn("Group", grp),
        "newName": "RNProbeGroupRenamed",
        "confirm": True,
    })
    assert_ok(r, "rename the group (confirm)")
    poll_disk_contains(_FORM, "<name>RNProbeGroupRenamed</name>",
                       ctx="the renamed group must land in the .form")
    # Polled, and polled SEPARATELY from the .form: the two are exported independently, so the
    # form landing proves nothing about the module. read_module_source is a file read behind the
    # wire (see _form_module_source), which is why a single read here would be an export-ordering
    # race dressed up as a cascade assertion.
    module = _poll_form_module_contains(
        "RNProbeGroupRenamed",
        ctx="the module reference must follow the rename - this is the cascade")
    assert ("%s." % grp) not in module.replace("RNProbeGroupRenamed", ""), \
        "no reference to the OLD group name may survive in the module: %r" % module


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_renaming_a_form_element_also_refreshes_its_derived_title():
    """A form rename is NOT a name-only edit, and the guide says so because of this test.

    EDT's form refactoring also refreshes the element's derived title: an untitled group renamed
    to `RNTitleGroupRenamed` comes back titled "Rn title group renamed". That is EDT's behaviour,
    not something this branch asks for - but the guide claimed "only this identifier changes", and
    an unpinned doc claim is how that sentence survived being false. Pinning it here means the day
    EDT stops doing it, the sentence gets revisited instead of quietly rotting.
    """
    grp = "RNTitleGroup"
    _seed_form_group(grp)
    before = _form_item_titles(grp)
    assert before == [], "the seeded group must start untitled, got %r" % (before,)

    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": _form_fqn("Group", grp),
        "newName": grp + "Renamed",
        "confirm": True,
    })
    assert_ok(r, "rename the untitled group (confirm)")
    poll_disk_contains(_FORM, "<name>%sRenamed</name>" % grp,
                       ctx="the renamed group must land in the .form")
    after = _form_item_titles(grp + "Renamed")
    assert after, ("EDT is documented to derive a title on a form rename; the renamed group "
                   "carries none, so the guide's claim needs revisiting: %r" % (after,))
    # Not merely "a title appeared": it must be derived from the NEW name. A title carrying the
    # old name, an empty value or arbitrary text would satisfy a non-emptiness check while making
    # the documented behaviour something else entirely. EDT's exact casing is not pinned here -
    # over-fitting its word-splitting would turn a cosmetic change upstream into a red test.
    values = [text for _, text in after if text]
    assert values, "the derived title must carry a value, got %r" % (after,)
    assert any("renamed" in text.lower() for text in values), \
        ("the title must be derived from the NEW name (it should read like 'Rn title group "
         "renamed'), got %r" % (values,))


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_form_handler_address_is_refused_with_the_rebind_path():
    # A handler FQN names an EVENT BINDING; its leaf is the event name, which the platform
    # owns. Refusing it as "not found" would send an agent hunting for an element that is
    # right there, so the refusal has to name the operation the caller actually wants.
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "Catalog.Catalog.Form.ItemForm.Handler.OnOpen",
        "newName": "OnOpened",
        "confirm": True,
    })
    err = assert_error(r, "renaming a form event handler")
    assert_error_quality(err, names=["handler"], suggests=["modify_metadata", "procedure"],
                         ctx="a handler address is refused with the rebind path")
    assert_no_diff("a refused rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_bare_column_address_is_refused_with_the_owner_shape():
    # A column belongs to a collection form ATTRIBUTE, so a bare Column.X addresses nothing.
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": _form_fqn("Column", "RNBareColumn"),
        "newName": "RNBareColumnRenamed",
        "confirm": True,
    })
    err = assert_error(r, "renaming through a bare column address")
    # The suggestion has to be a phrase the OLD generic "Object not found: <fqn>. Check the FQN
    # format ..." could never carry: that message quotes the FQN back, so asserting on the word
    # "Column" alone passed against the very defect this test is named for (measured by pinning
    # the stand to the pre-fix jar).
    assert_error_quality(err, names=["RNBareColumn"],
                         suggests=["belongs to a collection form attribute"],
                         ctx="a bare column address is refused with the owner-bearing shape")
    assert_no_diff("a refused rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_designer_owned_child_is_refused_and_points_at_its_owner():
    # CodeExtendedTooltip IS addressable (an ExtendedTooltip is a Decoration), which is
    # precisely why the refusal cannot be made by address - it is made by the resolved
    # element's ECLASS. The fixture carries it, so nothing is seeded here.
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": _form_fqn("Decoration", "CodeExtendedTooltip"),
        "newName": "RenamedTooltip",
        "confirm": True,
    })
    err = assert_error(r, "renaming a designer-owned extended tooltip")
    assert_error_quality(err, names=["CodeExtendedTooltip", "ExtendedTooltip"],
                         suggests=["OWNING"],
                         ctx="a designer-owned child is refused and points at its owner")
    assert_no_diff("a refused rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_a_name_a_sibling_already_bears_is_refused():
    _seed_form_attribute("RNDupA")
    _seed_form_attribute("RNDupB")
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": _form_fqn("Attribute", "RNDupA"),
        "newName": "RNDupB",
        "confirm": True,
    })
    err = assert_error(r, "renaming onto a taken sibling name")
    assert_error_quality(err, names=["RNDupB"], suggests=["already exists"],
                         ctx="a duplicate form-element name is refused")
    assert_contains(read_disk(_FORM), "RNDupA",
                    "the refused rename must leave the original name in place")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_a_missing_form_element_is_reported_as_such():
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": _form_fqn("Field", "RNNoSuchField_e2e"),
        "newName": "Whatever",
        "confirm": True,
    })
    err = assert_error(r, "renaming a form element that does not exist")
    # "Form element not found", not the mdclass path's "Object not found": the FQN did reach the
    # form branch and the branch is what failed to resolve it. Asserting on the bare words "not
    # found" would be satisfied by the old code, which never reached a form at all.
    assert_error_quality(err, names=[], suggests=["Form element not found"],
                         ctx="a missing form element is reported by the FORM branch")
    assert_no_diff("a refused rename must not change the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# newName must be a legal 1C identifier - ONE rule, both branches
#
# The verdict is the platform's own predicate (StringUtils.isValidName), applied
# before anything is resolved, so the mdclass path and the form path answer alike.
# Without it a rename to "Bad.Name" SUCCEEDS: the write lands, but the result is
# addressable by no FQN (the dot is the FQN separator) and the cascade rewrites the
# module into something that no longer parses - with the old name already gone.
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_a_dotted_new_name_is_refused_on_the_mdclass_path():
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "CommonModule.Calc",
        "newName": "Bad.Name",
        "confirm": True,
    })
    err = assert_error(r, "renaming a module to a dotted name")
    assert_error_quality(err, names=["Bad.Name"], suggests=["letters, digits and underscores"],
                         ctx="a dotted new name names the bad value and states the rule")
    assert_contains(_commonmodule_names("Calc"), "Calc",
                    "the module must keep its original name")
    assert_no_diff("a refused rename must not change the project on disk")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_a_dotted_new_name_is_refused_on_the_form_path_too():
    # Same rule, other branch: one point of judgment, so the form path cannot drift.
    _seed_form_attribute("RNBadNameAttr")
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": _form_fqn("Attribute", "RNBadNameAttr"),
        "newName": "Bad.Name",
        "confirm": True,
    })
    err = assert_error(r, "renaming a form attribute to a dotted name")
    assert_error_quality(err, names=["Bad.Name"], suggests=["letters, digits and underscores"],
                         ctx="the form branch refuses the same illegal name")
    assert_contains(read_disk(_FORM), "RNBadNameAttr",
                    "the attribute must keep its original name")


@e2e_test(tool="rename_metadata_object", kind="write-metadata")
def test_a_cyrillic_new_name_is_accepted():
    # The other half: the rule must not become a blanket refusal. A Cyrillic name is legal
    # 1C, and a hand-written ASCII rule would have passed every assertion above while
    # rejecting half the configurations in the country.
    _seed_form_attribute("RNCyrillicAttr")
    new_name = "РеквизитE2E"
    r = call("rename_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": _form_fqn("Attribute", "RNCyrillicAttr"),
        "newName": new_name,
        "confirm": True,
    })
    assert_ok(r, "rename a form attribute to a Cyrillic name")
    poll_disk_contains(_FORM, "<name>%s</name>" % new_name,
                       ctx="a Cyrillic name is a legal 1C name and must land on disk")
