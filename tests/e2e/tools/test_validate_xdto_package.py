"""
e2e tests for validate_xdto_package (kind: read tool, but two tests SEED via
create_metadata/delete_metadata and are therefore declared kind="write-metadata" so the
orchestrator resets the fixture + model afterward -- see test_get_metadata_details.py for
the same pattern of a read tool needing a write-metadata SETUP).

validate_xdto_package is a THIN wrapper over get_project_errors: it resolves + type-checks
the `fqn` (must be an existing XDTOPackage top object), then delegates to
GetProjectErrorsTool.getProjectErrors(projectName, null, null, [pkgFqn], limit, false,
exactScope=true) -- ALL severities, EXACT per-package scope, using the resolved package's
canonical FQN -- and prepends a one-line pass/fail verdict to the returned Markdown.

Happy paths:
  - a freshly created, empty XDTO package validates clean ("is valid" verdict).
  - deleting an ObjectType that another Property references (same-package reference) leaves
    a DANGLING reference; delete_metadata does NOT cascade into the xdto.model content (see
    DeleteMetadataTool's own "Cross-references to it ... are NOT cleaned up" note), so EDT's
    own validator reports it as a problem once revalidated -- proving the tool's `objects`
    scoping and verdict wiring both actually work (a broken/no-op tool would report "is
    valid" here).

Negative matrix:
  - fqn resolves to something other than an XDTOPackage (an existing Catalog) -> rejected.
  - fqn does not resolve at all (unknown package name) -> rejected (same error path).
  - non-existent projectName -> "Project not found: <name>".
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_contains,
    assert_not_contains,
    assert_error_quality,
    assert_no_diff,
    wait_for_project_ready,
    e2e_test,
    PROJECT,
)


def _create_ok(fqn, properties=None, ctx=""):
    args = {"projectName": PROJECT, "fqn": fqn}
    if properties is not None:
        args["properties"] = properties
    r = call("create_metadata", args)
    assert_ok(r, ctx or ("create " + fqn))
    wait_for_project_ready()
    return r


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATHS
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="validate_xdto_package", kind="write-metadata")
def test_fresh_empty_package_is_valid():
    pkg = "VXPCleanPkg"
    fqn = "XDTOPackage." + pkg
    _create_ok(fqn, ctx="seed a fresh XDTO package")

    r = call("validate_xdto_package", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, "validate a freshly created, unmodified XDTO package")
    assert_contains(r.text, "is valid", "a clean package must get the pass verdict")
    assert_contains(r.text, fqn, "the verdict must name the validated package")
    assert_not_contains(r.text, "problems found", "a clean package must not report problems")


@e2e_test(tool="validate_xdto_package", kind="write-metadata")
def test_dangling_object_type_reference_is_reported_after_delete():
    pkg = "VXPDanglingPkg"
    pkg_fqn = "XDTOPackage." + pkg
    type_a = "VXPObjTypeA"
    type_b = "VXPObjTypeB"
    ref_prop = "VXPRefToA"

    _create_ok(pkg_fqn, ctx="seed the XDTO package")
    _create_ok(pkg_fqn + ".ObjectType." + type_a, ctx="seed ObjectType A")
    _create_ok(pkg_fqn + ".ObjectType." + type_b, ctx="seed ObjectType B")
    # A same-package reference: a Property's 'type' value is the EXACT name of another
    # ObjectType already in the package (resolves to a same-package QName - no {nsUri,name}
    # object needed).
    _create_ok(pkg_fqn + ".ObjectType." + type_b + ".Property." + ref_prop,
               properties=[{"name": "type", "value": type_a}],
               ctx="seed Property B.%s referencing ObjectType A" % ref_prop)

    # Sanity: before the delete, the package must validate clean (proves the NEXT check is
    # really caused by the delete, not a pre-existing unrelated problem in this package).
    before = call("validate_xdto_package", {"projectName": PROJECT, "fqn": pkg_fqn})
    assert_ok(before, "validate before the delete")
    assert_contains(before.text, "is valid", "package must be valid before the dangling delete")

    # Delete ObjectType A. The xdto.model content is NOT covered by the mdclass refactoring
    # service, so Property B.RefToA's type reference is left dangling on purpose.
    d = call("delete_metadata", {
        "projectName": PROJECT, "fqn": pkg_fqn + ".ObjectType." + type_a, "confirm": True})
    assert_ok(d, "delete ObjectType A (confirm=true)")
    wait_for_project_ready()

    # validate_xdto_package documents that it reads EXISTING markers rather than forcing a
    # fresh compile; force one so the assertion below is deterministic.
    reval = call("revalidate_objects", {"projectName": PROJECT, "objects": [pkg_fqn]})
    assert_ok(reval, "revalidate the package after the dangling delete")

    r = call("validate_xdto_package", {"projectName": PROJECT, "fqn": pkg_fqn})
    assert_ok(r, "validate after the dangling-reference delete")
    assert_contains(r.text, "problems found", "a dangling type reference must fail validation")
    assert_contains(r.text, pkg_fqn, "the verdict must name the validated package")
    assert_not_contains(r.text, "is valid", "a broken package must not get the pass verdict")
    # The delegated get_project_errors table must actually be present (not a bare verdict
    # line) - proves the tool really scoped + rendered EDT's own problem list.
    assert_contains(r.text, "Check code", "the underlying problem table must be rendered")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────

@e2e_test(tool="validate_xdto_package", kind="read")
def test_non_xdto_fqn_is_rejected():
    """An FQN that resolves but is NOT an XDTOPackage (an existing Catalog) must be rejected
    with an actionable error, not silently validated as if it were the package."""
    r = call("validate_xdto_package", {"projectName": PROJECT, "fqn": "Catalog.Catalog"})
    err = assert_error(r, "a non-XDTOPackage fqn")
    assert_error_quality(
        err,
        names=["Catalog.Catalog"],
        suggests=["XDTOPackage", "get_metadata_objects"],
        ctx="non-XDTOPackage fqn names the bad value and explains the expected shape",
    )
    assert_no_diff("a rejected read must not touch the project on disk")


@e2e_test(tool="validate_xdto_package", kind="read")
def test_unknown_package_fqn_is_rejected():
    """An fqn that does not resolve to ANY existing node hits the same not-an-XDTOPackage
    guard and must still name the bad value."""
    bad_fqn = "XDTOPackage.NoSuchXdtoPackage_e2e_zzz"
    r = call("validate_xdto_package", {"projectName": PROJECT, "fqn": bad_fqn})
    err = assert_error(r, "an unknown XDTOPackage fqn")
    assert_error_quality(
        err,
        names=[bad_fqn],
        suggests=["get_metadata_objects"],
        ctx="unknown package fqn names the bad value and points at get_metadata_objects",
    )
    assert_no_diff("a rejected read must not touch the project on disk")


@e2e_test(tool="validate_xdto_package", kind="read")
def test_nonexistent_project_is_rejected():
    """A non-existent projectName must error and name the bad value (not silently validate
    against the wrong / default project)."""
    bad = "NoSuchProject_e2e_xyz_vxp"
    r = call("validate_xdto_package", {"projectName": bad, "fqn": "XDTOPackage.Whatever"})
    err = assert_error(r, "non-existent projectName")
    assert_error_quality(
        err,
        names=[bad],
        suggests=["list_projects"],
        ctx="non-existent project names the bad value and points at list_projects",
    )
    assert_no_diff("a rejected read must not touch the project on disk")


@e2e_test(tool="validate_xdto_package", kind="read")
def test_missing_fqn_is_rejected():
    """The required `fqn` parameter must be enforced at the live wire level too (not just
    the headless JUnit contract test)."""
    r = call("validate_xdto_package", {"projectName": PROJECT})
    err = assert_error(r, "missing fqn")
    assert_error_quality(
        err,
        names=["fqn"],
        suggests=["required"],
        ctx="missing fqn must say which parameter is required",
    )
    assert_no_diff("a rejected read must not touch the project on disk")
