"""
e2e tests for resync_to_disk (kind: action).

WHAT THE TOOL DOES (ResyncToDiskTool.java)
------------------------------------------
resync_to_disk walks EVERY metadata top object of a project's BM model (read
transaction, getTopObjectIterator, keep only MdObject) and force-exports each
object's .mdo under src/, so objects that exist in the model / Configuration.mdo
but have no .mdo on disk are written out. It integrity-checks
src/<TypeDir>/<Name>/<Name>.mdo before AND after the export (the before-set is the
real desync), and then scans the Configuration's many-valued MdObject reference
collections for UNRESOLVED PROXIES ("dangling"/"orphaned" entries with no .mdo and
no BM body — the source of md-reference-intergrity "lost reference" warnings that
block update_database / XML import) and, when cleanDanglingReferences is true
(default), removes them and re-exports Configuration.mdo. It is read-safe and
IDEMPOTENT: on an already-in-sync project it re-exports the same bytes (no model
change) and reports missingBeforeCount: 0 / danglingFound: 0.

RESPONSE SHAPE (IMPORTANT)
--------------------------
ResyncToDiskTool.getResponseType() == JSON, so the real payload lands in
Result.structured (NOT Result.text). The success envelope is:
    {"success": true,
     "projectName": "<name>",
     "objectsExported": <int>, "totalTopObjects": <int>,
     "missingBeforeCount": <int>, "missingBefore": [<fqn>, ...],
     "stillMissingCount": <int>, "stillMissing": [<fqn>, ...],
     "cleanDanglingReferences": <bool>,
     "danglingFound": <int>, "danglingRemovedCount": <int>,
     "danglingRemoved": [...], "danglingDetails": [...],
     "message": "..."}
On error the envelope is {"success": false, "error": "<message>"} and the protocol
layer marks the result isError; assert_error returns that error string.

ACTION-TOOL SAFETY — why the happy path is safe on the committed fixture
-----------------------------------------------------------------------
The fixture (TestConfiguration) is committed in-sync: every metadata object has its
.mdo on disk and the Configuration has no dangling references. So a happy resync
re-exports the SAME bytes (idempotent) and removes NOTHING -> the committed tree
must stay byte-for-byte unchanged. The correct happy assertion is therefore
assert_ok + assert the in-sync envelope (missingBeforeCount==0, danglingFound==0)
+ assert_no_diff(). A real desync repair (delete a .mdo, restore it; or leave a
dangling ref, clear it) is a manual live-workspace verification, not exercised
against the read-only committed fixture.

REAL ERROR / SENTINEL PATHS (read from the Java)
------------------------------------------------
  - projectName missing/empty: AbstractMetadataWriteTool.execute() runs the
    BUILDING-only pre-check (null for an absent name) then marshals to the UI thread;
    executeOnUiThread hits `if (projectName == null || projectName.isEmpty())
    return ToolResult.error("projectName is required")`.
  - projectName non-existent: resolveProjectAndConfig -> ProjectContext.notFoundMessage
    (names the bad value AND points at list_projects).
"""

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    PROJECT,
)


def _success_envelope(r, ctx):
    """Validate the JSON success envelope and return the structured dict.

    A JSON tool MUST populate structuredContent; a missing/typed-wrong envelope means
    the tool returned the wrong shape (a real regression), so we hard-fail."""
    sc = r.structured
    if not isinstance(sc, dict):
        raise AssertionError("expected structuredContent dict [%s]: %r" % (ctx, sc))
    if sc.get("success") is not True:
        raise AssertionError("resync envelope must set success=true [%s]: %r" % (ctx, sc))
    if "error" in sc:
        raise AssertionError("success envelope must NOT carry an 'error' field [%s]: %r" % (ctx, sc))
    return sc


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH (safe: the committed fixture is in-sync -> idempotent re-export)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="resync_to_disk", kind="action")
def test_resync_in_sync_fixture_is_idempotent_and_does_not_mutate():
    """The committed fixture is in-sync, so resync must report it as such and leave the
    tree untouched: missingBeforeCount==0, stillMissingCount==0, danglingFound==0, and
    objectsExported==totalTopObjects (every metadata top object was force-exported).

    Mutation thinking: a tool that mis-classified internal non-MdObject top objects
    (content forms, BSL index objects) as missing .mdo would push missingBeforeCount>0;
    a tool that wrongly flagged a VALID reference as dangling would report
    danglingFound>0 and (worse) rewrite Configuration.mdo, failing assert_no_diff; a
    no-op that exported nothing would report objectsExported==0. The in-sync envelope
    plus a clean working tree pins all three."""
    r = call("resync_to_disk", {"projectName": PROJECT})
    assert_ok(r, "resync_to_disk on the in-sync fixture")

    sc = _success_envelope(r, "in-sync resync")
    if sc.get("projectName") != PROJECT:
        raise AssertionError("envelope must echo the project %r: %r" % (PROJECT, sc.get("projectName")))
    # An in-sync fixture: nothing was missing on disk before or after.
    if sc.get("missingBeforeCount") != 0:
        raise AssertionError(
            "in-sync fixture must report missingBeforeCount==0: %r (missingBefore=%r)"
            % (sc.get("missingBeforeCount"), sc.get("missingBefore")))
    if sc.get("stillMissingCount") != 0:
        raise AssertionError(
            "nothing must remain missing after the export: %r" % sc.get("stillMissingCount"))
    # No valid reference must be mistaken for dangling on a clean Configuration.
    if sc.get("danglingFound") != 0:
        raise AssertionError(
            "in-sync fixture must have no dangling references: danglingFound=%r details=%r"
            % (sc.get("danglingFound"), sc.get("danglingDetails")))
    # The walk force-exports every metadata top object; the count must be positive and
    # equal to the total walked (a real configuration has many objects).
    exported = sc.get("objectsExported")
    total = sc.get("totalTopObjects")
    if not isinstance(exported, int) or exported <= 0:
        raise AssertionError("objectsExported must be a positive int: %r" % exported)
    if exported != total:
        raise AssertionError(
            "objectsExported(%r) must equal totalTopObjects(%r) on a successful export"
            % (exported, total))

    # Ground truth: an idempotent re-export of an in-sync project rewrites the SAME
    # bytes and removes nothing, so the committed tree must be unchanged.
    assert_no_diff("resync of an in-sync project must not change any tracked file")


@e2e_test(tool="resync_to_disk", kind="action")
def test_report_only_mode_does_not_mutate():
    """cleanDanglingReferences=false is report-only: it scans but removes nothing, so
    danglingRemovedCount must be 0 and the tree must stay clean. The envelope must echo
    the requested flag back (cleanDanglingReferences==false).

    Mutation thinking: a tool that ignored the flag and removed entries anyway would
    report danglingRemovedCount>0 or fail assert_no_diff."""
    r = call("resync_to_disk", {"projectName": PROJECT, "cleanDanglingReferences": False})
    assert_ok(r, "resync_to_disk report-only mode")

    sc = _success_envelope(r, "report-only resync")
    if sc.get("cleanDanglingReferences") is not False:
        raise AssertionError(
            "envelope must echo cleanDanglingReferences=false: %r" % sc.get("cleanDanglingReferences"))
    if sc.get("danglingRemovedCount") != 0:
        raise AssertionError(
            "report-only mode must remove nothing: danglingRemovedCount=%r"
            % sc.get("danglingRemovedCount"))

    assert_no_diff("report-only resync must not change any tracked file")


# ──────────────────────────────────────────────────────────────────────────────
# NEGATIVE MATRIX
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="resync_to_disk", kind="action")
def test_missing_project_name_errors_clearly():
    """Missing required projectName -> the executeOnUiThread guard fires with
    "projectName is required". Must be a clean, named required-arg error, not a NPE."""
    r = call("resync_to_disk", {})
    e = assert_error(r, "missing projectName")
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="missing projectName names the param")
    assert_no_diff("a rejected call must not touch the project tree")


@e2e_test(tool="resync_to_disk", kind="action")
def test_empty_project_name_errors_clearly():
    """Boundary: projectName present but the EMPTY string -> same "projectName is
    required" guard. An empty name must NOT silently resolve to a default project."""
    r = call("resync_to_disk", {"projectName": ""})
    e = assert_error(r, "empty-string projectName")
    assert_error_quality(e, names=["projectName"], suggests=[],
                         ctx="empty projectName rejected by the required-arg guard")
    assert_no_diff("a rejected call must not touch the project tree")


@e2e_test(tool="resync_to_disk", kind="action")
def test_nonexistent_project_errors_clearly():
    """Valid-shaped args, but the project does not exist -> resolveProjectAndConfig
    surfaces the shared ProjectContext.notFoundMessage(name): it echoes the bad value
    AND points at list_projects to discover a valid project."""
    bad = "NoSuchProject_ZZZ_e2e"
    r = call("resync_to_disk", {"projectName": bad})
    e = assert_error(r, "non-existent project")
    assert_error_quality(e, names=[bad], suggests=["list_projects"],
                         ctx="non-existent project names the bad value and points at list_projects")
    assert_no_diff("a rejected call must not touch the project tree")
