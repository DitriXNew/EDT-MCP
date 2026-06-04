"""
e2e tests for delete_metadata (kind: write-metadata).

delete_metadata deletes a metadata node (a top-level object or a subordinate member:
attribute / tabular section / dimension / resource / enum value) addressed by a 1C
full-name FQN, via EDT's refactoring service, cleaning up every reference in BSL code,
forms and other metadata. It folds the former delete_metadata_object onto the unified
`fqn` parameter and the shared MetadataNodeResolver.

JSON-responseType tool (payload in r.structured). Two-phase:
  * confirm absent/false -> PREVIEW: action="preview", refactoringTitle, items,
    affectedReferences, affectedReferencesCount, message. Model NOT mutated.
  * confirm=true         -> EXECUTE: action="executed"; the node is gone and its
    references are cleaned.

reset: kind="write-metadata" -> the orchestrator runs reset_model() (clean_project,
discarding the unsaved delete) AFTER each test, so each test starts clean.

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
    poll_disk_path_gone,
    poll_disk_lacks,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)


def _list_commonmodules():
    r = call("get_metadata_objects", {"projectName": PROJECT, "metadataType": "commonModules"})
    assert_ok(r, "read-back: list commonModules")
    return r.text


def _list_catalogs():
    r = call("get_metadata_objects", {"projectName": PROJECT, "metadataType": "catalogs"})
    assert_ok(r, "read-back: list catalogs")
    return r.text


# ──────────────────────────────────────────────────────────────────────────────
# Happy — CONFIRM deletes (verified by model read-back + disk)
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_confirm_deletes_top_object_gone_from_model_and_disk():
    assert_contains(_list_commonmodules(), "Calc", "baseline: CommonModule.Calc must exist")

    r = call("delete_metadata", {"projectName": PROJECT, "fqn": "CommonModule.Calc", "confirm": True})
    assert_ok(r, "delete CommonModule.Calc (confirm=true)")
    assert r.structured is not None, "a JSON tool must return structuredContent"
    assert r.structured.get("action") == "executed", \
        "confirm=true must take the execute branch (action=executed): %r" % (r.structured,)
    assert r.structured.get("fqn") == "CommonModule.Calc", "must echo the target fqn"

    after = _list_commonmodules()
    assert_not_contains(after, "| Calc ", "CommonModule.Calc must be GONE from the model")
    assert_contains(after, "OK", "sibling CommonModule.OK must survive a targeted delete")
    poll_disk_path_gone("src/CommonModules/Calc/Calc.mdo",
                        ctx="delete must remove the object's own .mdo from disk")
    poll_disk_lacks("src/Configuration/Configuration.mdo", "CommonModule.Calc",
                    ctx="delete must remove the Configuration.mdo collection reference")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_confirm_deletes_member_attribute():
    # Create a uniquely-named attribute, let the model settle, then delete it by FQN.
    attr = "E2EDelAttr"
    cr = call("create_metadata", {"projectName": PROJECT, "fqn": "Catalog.Catalog.Attribute." + attr})
    assert_ok(cr, "seed attribute to delete")
    wait_for_project_ready()

    r = call("delete_metadata", {
        "projectName": PROJECT,
        "fqn": "Catalog.Catalog.Attribute." + attr,
        "confirm": True,
    })
    assert_ok(r, "delete the seeded attribute")
    assert r.structured.get("action") == "executed", "member delete must execute: %r" % (r.structured,)
    # The parent catalog must survive a member delete.
    assert_contains(_list_catalogs(), "| Catalog ",
                    "a member delete must NOT delete the parent Catalog.Catalog")


# ──────────────────────────────────────────────────────────────────────────────
# Happy — PREVIEW (confirm absent) lists change points and does NOT mutate
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_preview_without_confirm_lists_changepoints_and_does_not_mutate():
    assert_contains(_list_commonmodules(), "Calc", "baseline: CommonModule.Calc must exist")

    r = call("delete_metadata", {"projectName": PROJECT, "fqn": "CommonModule.Calc"})
    assert_ok(r, "preview delete CommonModule.Calc (no confirm)")
    assert r.structured.get("action") == "preview", \
        "absent confirm must produce a preview: %r" % (r.structured,)
    assert r.structured.get("fqn") == "CommonModule.Calc", "preview must echo the target fqn"
    assert "items" in r.structured, "preview must list refactoring items"
    assert "affectedReferencesCount" in r.structured, "preview must report the affected-reference count"
    assert_contains(r.structured.get("message", ""), "confirm=true",
                    "preview message must instruct re-calling with confirm=true")

    assert_contains(_list_commonmodules(), "Calc", "preview must NOT delete CommonModule.Calc")
    assert_no_diff("a preview must not touch the project on disk")


# ──────────────────────────────────────────────────────────────────────────────
# Negative matrix — bad input must error clearly AND change nothing
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_missing_project_name_is_error():
    r = call("delete_metadata", {"fqn": "CommonModule.Calc", "confirm": True})
    e = assert_error(r, "missing required projectName")
    assert_error_quality(e, names=["projectName"], suggests=["required", "list_projects"])
    assert_contains(_list_commonmodules(), "Calc", "a rejected call must not delete CommonModule.Calc")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_missing_fqn_is_error():
    r = call("delete_metadata", {"projectName": PROJECT, "confirm": True})
    e = assert_error(r, "missing required fqn")
    assert_error_quality(e, names=["fqn"], suggests=["required"])
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_nonexistent_project_is_error():
    bogus = "NoSuchProject_ZZZ_e2e"
    r = call("delete_metadata", {"projectName": bogus, "fqn": "CommonModule.Calc", "confirm": True})
    e = assert_error(r, "non-existent project")
    assert_error_quality(e, names=[bogus], suggests=["not found", "list_projects"])
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_nonexistent_node_is_error():
    bad = "CommonModule.DoesNotExist_e2e"
    r = call("delete_metadata", {"projectName": PROJECT, "fqn": bad, "confirm": True})
    e = assert_error(r, "non-existent node")
    assert_error_quality(e, names=[bad], suggests=["Type.Name", "Catalog.Products"])
    assert_contains(e, "EnumValue", "the not-found message lists supported member kinds")
    assert_contains(_list_commonmodules(), "OK", "a rejected lookup must not delete the sibling OK")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_malformed_fqn_is_error_and_parent_survives():
    # A well-formed nested FQN whose CHILD does not exist -> resolveExisting returns null.
    # The parent must NOT be deleted as a side effect (the arity/child guard prevents that).
    bad = "Catalog.Catalog.Attribute.NoSuchAttr_e2e"
    r = call("delete_metadata", {"projectName": PROJECT, "fqn": bad, "confirm": True})
    e = assert_error(r, "non-existent nested attribute")
    assert_error_quality(e, names=[bad], suggests=["not found"])
    assert_contains(_list_catalogs(), "| Catalog ",
                    "a failed nested-attribute delete must NOT delete the parent Catalog.Catalog")
    assert_no_diff("a rejected call must not touch the project on disk")


@e2e_test(tool="delete_metadata", kind="write-metadata")
def test_bare_token_without_dot_is_error():
    bad = "JustAName"
    r = call("delete_metadata", {"projectName": PROJECT, "fqn": bad, "confirm": True})
    e = assert_error(r, "malformed FQN (no dot)")
    assert_error_quality(e, names=[bad], suggests=["Type.Name"])
    assert_no_diff("a rejected call must not touch the project on disk")
