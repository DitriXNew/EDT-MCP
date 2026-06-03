"""
e2e tests for delete_metadata_object (kind: write-metadata).

The tool deletes a metadata object (or a nested attribute / tabular section /
dimension / resource) via the EDT refactoring service, cleaning up every
reference in BSL code, forms and other metadata. It is a JSON-responseType tool
(AbstractMetadataWriteTool -> ResponseType.JSON), so the payload lives in
``r.structured`` (``r.text`` is only the "Done"/"Error" placeholder). Errors come
through ``ToolResult.error(...)`` (``success:false`` + ``error``); the protocol's
isJsonErrorPayload diversion flags them ``isError:true`` and the harness surfaces
the message via ``r.error_text()`` (which reads ``structured["error"]``).

Two-phase contract (DeleteMetadataObjectTool):
  * confirm absent/false  -> PREVIEW: returns action="preview", refactoringTitle,
    items, affectedReferences, affectedReferencesCount, message. The model is NOT
    mutated.
  * confirm=true          -> EXECUTE: refactoring.perform() runs, returns
    action="executed", message "Delete refactoring completed successfully.";
    the object is gone from the model and its references are cleaned.

HOW THE EFFECT IS VERIFIED (this batch differs from the on-disk batches):
metadata-write tools mutate EDT's IN-MEMORY BM model but do NOT flush the delete
to disk synchronously, so git-diff is empty/partial and CANNOT prove the effect.
We therefore verify VIA MODEL READ-BACK over the wire: after a confirmed delete we
call get_metadata_objects and assert the object is GONE from the model (and its
siblings remain). For a preview (no mutation) we assert the object is STILL present
AND assert_no_diff() (a preview must never touch disk). assert_no_diff() stays a
meaningful guardrail for preview/rejected/negative calls (a rejected write must
change nothing); it is NOT used as the happy guardrail for a confirmed delete
(the model changed while disk did not, so a clean git proves nothing there).

reset: kind="write-metadata" -> the orchestrator calls reset_model() (clean_project,
which refreshes the model from disk and discards the unsaved delete) AFTER each
test, so every test starts from a clean model. This file never resets the model
itself; it may freely delete Catalog.Catalog / CommonModule.Calc.

Fixture inventory (TestConfiguration, English Names):
  Catalog.Catalog (attribute "Attribute", form ItemForm), CommonModule.Error/OK/Calc,
  CommonForm.Form, Subsystem.Subsystem, CommonAttribute.CommonAttribute,
  SessionParameter.SessionParameter.
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
# Read-back helper (model truth over the wire, via the sibling read tool)
# ──────────────────────────────────────────────────────────────────────────────

def _list_commonmodules():
    """Return the get_metadata_objects markdown for the configuration's common
    modules. The Name column is the discriminator we assert presence/absence on."""
    r = call("get_metadata_objects",
             {"projectName": PROJECT, "metadataType": "commonModules"})
    assert_ok(r, "read-back: list commonModules")
    return r.text


def _list_catalogs():
    r = call("get_metadata_objects",
             {"projectName": PROJECT, "metadataType": "catalogs"})
    assert_ok(r, "read-back: list catalogs")
    return r.text


# ──────────────────────────────────────────────────────────────────────────────
# Happy path — CONFIRM deletes the object (verified by model read-back)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_metadata_object", kind="write-metadata")
def test_confirm_deletes_commonmodule_gone_from_model():
    # Pre-condition: CommonModule.Calc exists in the model right now. If it did not,
    # the "gone after delete" assertion below would be vacuously true -> assert the
    # baseline first so the test is honest.
    before = _list_commonmodules()
    assert_contains(before, "Calc", "baseline: CommonModule.Calc must exist before delete")

    r = call("delete_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "CommonModule.Calc",
        "confirm": True,
    })
    assert_ok(r, "delete CommonModule.Calc (confirm=true)")
    # The execute branch reports action="executed" (NOT "preview").
    assert r.structured is not None, "a JSON tool must return structuredContent"
    assert r.structured.get("action") == "executed", \
        "confirm=true must take the execute branch (action=executed), got: %r" % (r.structured,)

    # PRIMARY verification: model read-back shows the object is GONE. A broken
    # delete (no-op / preview-instead-of-execute) leaves Calc present -> FAILS here.
    after = _list_commonmodules()
    assert_not_contains(after, "| Calc ",
                        "CommonModule.Calc must be GONE from the model after a confirmed delete")
    # Siblings must survive -> proves the delete was targeted, not a wipe of the family.
    assert_contains(after, "OK", "sibling CommonModule.OK must still exist after deleting Calc")
    assert_contains(after, "Error", "sibling CommonModule.Error must still exist after deleting Calc")


@e2e_test(tool="delete_metadata_object", kind="write-metadata")
def test_confirm_deletes_catalog_and_cleans_form_reference_cascade():
    # Catalog.Catalog owns a form (ItemForm) and an attribute and is wired by
    # producedTypes; deleting it exercises the reference-cleanup cascade. Verify both
    # the deletion AND that the catalog no longer appears in the model.
    before = _list_catalogs()
    assert_contains(before, "| Catalog ", "baseline: Catalog.Catalog must exist before delete")

    # Preview FIRST (same call, no confirm) to characterize the cascade scope: the
    # delete refactoring of a catalog with a form must enumerate affected references.
    prev = call("delete_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "Catalog.Catalog",
    })
    assert_ok(prev, "preview delete Catalog.Catalog")
    assert prev.structured.get("action") == "preview", "no confirm must be a preview"
    # The preview must surface the change-point set (the cleanup machinery). A broken
    # preview that returned an empty stub would carry no items. (refactoringTitle is
    # empty for a simple object with no inbound references, so assert on `items`, which
    # always lists the delete change point, e.g. {"name": "Delete Catalog", ...}.)
    assert prev.structured.get("items"), \
        "preview must carry the change-point set (items): %r" % (prev.structured,)
    assert "affectedReferencesCount" in prev.structured, \
        "preview must report the affected-reference count (the cascade scope)"
    # A preview must NOT mutate the model: the catalog is still present afterwards.
    assert_contains(_list_catalogs(), "| Catalog ",
                    "preview must NOT delete Catalog.Catalog (still present)")
    assert_no_diff("a preview must not touch the project on disk")

    # Now CONFIRM the delete.
    r = call("delete_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "Catalog.Catalog",
        "confirm": True,
    })
    assert_ok(r, "delete Catalog.Catalog (confirm=true)")
    assert r.structured.get("action") == "executed", \
        "confirm=true must execute (action=executed), got: %r" % (r.structured,)

    # PRIMARY verification: the catalog is GONE from the model. A no-op delete would
    # leave the "| Catalog " row present here and FAIL.
    after = _list_catalogs()
    assert_not_contains(after, "| Catalog ",
                        "Catalog.Catalog must be GONE from the model after a confirmed delete")


# ──────────────────────────────────────────────────────────────────────────────
# Happy path — PREVIEW (confirm absent) lists change points and does NOT mutate
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_metadata_object", kind="write-metadata")
def test_preview_without_confirm_lists_changepoints_and_does_not_mutate():
    before = _list_commonmodules()
    assert_contains(before, "Calc", "baseline: CommonModule.Calc must exist")

    r = call("delete_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": "CommonModule.Calc",
        # confirm omitted on purpose -> default false -> PREVIEW only
    })
    assert_ok(r, "preview delete CommonModule.Calc (no confirm)")
    assert r.structured is not None, "a JSON tool must return structuredContent"
    # The preview contract: action="preview", echoes the FQN, carries the change-point
    # listing (refactoringTitle + items + affectedReferences[/Count]) and a message
    # telling the caller to re-call with confirm=true.
    assert r.structured.get("action") == "preview", \
        "absent confirm must produce a preview, got action=%r" % (r.structured.get("action"),)
    assert r.structured.get("objectFqn") == "CommonModule.Calc", \
        "preview must echo the target FQN"
    assert "items" in r.structured, "preview must list refactoring items (the change points)"
    assert "affectedReferencesCount" in r.structured, \
        "preview must report the affected-reference count"
    assert_contains(r.structured.get("message", ""), "confirm=true",
                    "preview message must instruct the caller to re-call with confirm=true")

    # MUTATION GUARD: a preview must NOT delete anything. Read-back shows Calc still
    # present, and disk is untouched. A tool that deleted during preview FAILS here.
    after = _list_commonmodules()
    assert_contains(after, "Calc",
                    "preview must NOT delete CommonModule.Calc (it must still be in the model)")
    assert_no_diff("a preview must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix (mandatory) — bad input must error clearly AND change nothing
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_metadata_object", kind="write-metadata")
def test_missing_project_name_is_error_and_no_mutation():
    r = call("delete_metadata_object", {
        # projectName omitted on purpose
        "objectFqn": "CommonModule.Calc",
        "confirm": True,
    })
    e = assert_error(r, "missing required projectName")
    # JsonUtils.requireArgument(params, "projectName", " . Usage: {...}") ->
    # "projectName is required. Usage: {projectName: ..., objectFqn: ...}".
    # AUDIT: the message names the missing param and shows a usage shape, but does NOT
    # point at a discovery tool (list_projects) to obtain a valid project name. Weakly
    # actionable on the discovery axis. Fix-card: mention list_projects.
    assert_error_quality(e, names=["projectName"], suggests=["required"],
                         ctx="missing projectName names the param")
    # A rejected call must not have deleted anything and must not touch disk.
    assert_contains(_list_commonmodules(), "Calc",
                    "a rejected call must not delete CommonModule.Calc")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata_object", kind="write-metadata")
def test_missing_object_fqn_is_error_and_no_mutation():
    r = call("delete_metadata_object", {
        "projectName": PROJECT,
        # objectFqn omitted on purpose
        "confirm": True,
    })
    e = assert_error(r, "missing required objectFqn")
    # "objectFqn is required. Examples: 'Catalog.Products' (delete whole catalog), ...".
    # Names the param AND shows concrete example FQNs -> actionable. (Catalog.Products
    # is asserted WITHOUT surrounding quotes: Gson HTML-escapes the apostrophes, but the
    # dotted token itself is delimiter-free.)
    assert_error_quality(e, names=["objectFqn"], suggests=["required", "Catalog.Products"],
                         ctx="missing objectFqn names the param and shows example FQNs")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata_object", kind="write-metadata")
def test_nonexistent_project_is_error_and_no_mutation():
    bogus = "NoSuchProject_ZZZ_e2e"
    r = call("delete_metadata_object", {
        "projectName": bogus,
        "objectFqn": "CommonModule.Calc",
        "confirm": True,
    })
    e = assert_error(r, "non-existent project")
    # resolveProjectAndConfig -> ToolResult.error("Project not found: <name>").
    # AUDIT: names the bad project but offers NO next step (no list_projects hint to
    # enumerate valid project names). Names-but-not-actionable. Fix-card: append a
    # list_projects hint to "Project not found".
    assert_error_quality(e, names=[bogus], suggests=["not found"],
                         ctx="non-existent project names the bad value")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata_object", kind="write-metadata")
def test_nonexistent_object_is_error_and_no_mutation():
    # A well-formed FQN whose Name does not exist -> resolveObject returns null ->
    # "Object not found: <fqn>. Check the FQN format: ... Supported child types: ...".
    bad = "CommonModule.DoesNotExist_e2e"
    r = call("delete_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": bad,
        "confirm": True,
    })
    e = assert_error(r, "non-existent object")
    # This error IS actionable: it names the bad FQN, states the expected 'Type.Name'
    # format with an example, and lists supported child types. (Type.Name and
    # Catalog.Products are delimiter-free; "Attribute" is one of the listed child
    # types -> a concrete next-step token.)
    assert_error_quality(e, names=[bad], suggests=["Type.Name", "Catalog.Products"],
                         ctx="non-existent object names the bad FQN and states the format")
    assert_contains(e, "Attribute",
                    "the not-found message lists supported child types (Attribute, ...)")
    # The valid sibling must NOT have been touched by the failed lookup.
    assert_contains(_list_commonmodules(), "OK",
                    "a rejected lookup must not delete the sibling CommonModule.OK")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata_object", kind="write-metadata")
def test_unknown_type_token_is_error_and_no_mutation():
    # An unknown metadata TYPE token (not a real metadata class) cannot resolve ->
    # MetadataTypeUtils.findObject returns null -> "Object not found: <fqn>".
    bad = "BogusType.Catalog"
    r = call("delete_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": bad,
        "confirm": True,
    })
    e = assert_error(r, "unknown type token")
    assert_error_quality(e, names=[bad], suggests=["not found"],
                         ctx="unknown type token names the bad FQN")
    # The real Catalog.Catalog (the Name happens to match) must NOT have been deleted
    # by this malformed-type call.
    assert_contains(_list_catalogs(), "| Catalog ",
                    "a rejected unknown-type call must not delete Catalog.Catalog")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata_object", kind="write-metadata")
def test_malformed_fqn_without_dot_is_error_and_no_mutation():
    # A bare token with no 'Type.Name' separator -> resolveObject: isValidFqnArity(1)
    # is false -> null -> "Object not found". A nested delete must never fall back to a
    # broader object, so this must be a clean rejection, not a partial delete.
    bad = "JustAName"
    r = call("delete_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": bad,
        "confirm": True,
    })
    e = assert_error(r, "malformed FQN (no dot)")
    # The message names the bad value and states the expected 'Type.Name' format.
    assert_error_quality(e, names=[bad], suggests=["Type.Name"],
                         ctx="malformed FQN names the bad value and states the format")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata_object", kind="write-metadata")
def test_nonexistent_nested_attribute_is_error_and_parent_survives():
    # A well-formed nested FQN whose CHILD does not exist -> findChild returns null ->
    # "Object not found: <fqn>". The PARENT (Catalog.Catalog) must NOT be deleted as a
    # side effect (the arity/child guard exists precisely to prevent that).
    bad = "Catalog.Catalog.Attribute.NoSuchAttr_e2e"
    r = call("delete_metadata_object", {
        "projectName": PROJECT,
        "objectFqn": bad,
        "confirm": True,
    })
    e = assert_error(r, "non-existent nested attribute")
    assert_error_quality(e, names=[bad], suggests=["not found"],
                         ctx="non-existent nested attribute names the bad FQN")
    # The parent catalog must survive a failed child lookup.
    assert_contains(_list_catalogs(), "| Catalog ",
                    "a failed nested-attribute delete must NOT delete the parent Catalog.Catalog")
    assert_no_diff("a rejected call must not touch the project on disk")
