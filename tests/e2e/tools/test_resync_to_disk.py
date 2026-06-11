"""
e2e tests for resync_to_disk (kind: action; the repair test is kind: write-metadata).

WHAT THE TOOL DOES (ResyncToDiskTool.java)
------------------------------------------
resync_to_disk walks EVERY metadata top object of a project's BM model (read
transaction, getTopObjectIterator, keep only MdObject), computes which of them have
no .mdo on disk (missingBefore — the real desync) and force-exports ONLY that
missing subset under src/ (fullExport=true re-exports every object instead — the
export runs on the UI thread, so the subset is the default). It integrity-checks
src/<TypeDir>/<Name>/<Name>.mdo before AND after the export, and then scans the
Configuration's many-valued MdObject reference collections for UNRESOLVED PROXIES
("dangling"/"orphaned" entries with no .mdo and no BM body — the source of
md-reference-intergrity "lost reference" warnings that block update_database / XML
import). The dangling scan is REPORT-ONLY by default (danglingFound +
danglingDetails); cleanDanglingReferences=true is the destructive opt-in that
removes them and rewrites Configuration.mdo. With default parameters a run on an
in-sync project writes NOTHING (empty export list, report-only scan) and reports
missingBeforeCount: 0 / danglingFound: 0 — idempotent.

RESPONSE SHAPE (IMPORTANT)
--------------------------
ResyncToDiskTool.getResponseType() == JSON, so the real payload lands in
Result.structured (NOT Result.text). The success envelope is:
    {"success": true,
     "projectName": "<name>",
     "objectsExported": <int>, "totalTopObjects": <int>,
     "fullExport": <bool>, "revalidate": <bool>,        # request flags echoed back
     "missingBeforeCount": <int>, "missingBefore": [<fqn>, ...],
     "stillMissingCount": <int>, "stillMissing": [<fqn>, ...],
     "cleanDanglingReferences": <bool>,
     "danglingFound": <int>, "danglingRemovedCount": <int>,
     "danglingRemoved": [...], "danglingDetails": [...],
     "message": "..."}
On error the envelope is {"success": false, "error": "<message>"} and the protocol
layer marks the result isError; assert_error returns that error string.

ACTION-TOOL SAFETY — why the happy paths are safe on the committed fixture
--------------------------------------------------------------------------
The fixture (TestConfiguration) is committed in-sync: every metadata object has its
.mdo on disk and the Configuration has no dangling references. A default resync
therefore exports NOTHING (objectsExported == 0) and removes NOTHING — the committed
tree must stay byte-for-byte unchanged (assert_no_diff). A fullExport=true resync
re-exports the SAME bytes (idempotent), so the tree stays clean there too.

REPAIR PATH (kind: write-metadata)
----------------------------------
test_repair below exercises the real desync repair end-to-end: seed a Catalog via
create_metadata, delete its .mdo straight off the fixture path (simulating the
"object in the model / Configuration.mdo but no file on disk" drift), then resync —
the EXPORT side restores the file even with all-default (report-only) parameters,
and objectsExported == missingBeforeCount pins the subset decision live.

WHY THE DANGLING-REMOVAL PATH IS UNIT-COVERED, NOT E2E
------------------------------------------------------
A genuinely dangling Configuration reference is an unresolved proxy left behind
when an object's BODY is lost while its registration survives. Every public write
tool keeps the model and the Configuration consistent (delete_metadata removes the
registration together with the object), so there is no headless way to fabricate
one through the MCP surface without hand-corrupting EDT's internal BM store. The
e2e suite therefore pins the FLAG CONTRACT (report-only default, explicit-true
echo, nothing removed on a clean project) and the commit-honest removal reporting
is pinned at the unit level: ResyncToDiskToolTest.testThrowingRemovalTaskReportsNoRemovals /
testSuccessfulRemovalTaskClaimsRemovalOnlyAfterReturn / testReportOnlyTaskClaimsNoRemoval
(a throwing BM write task must surface danglingWarning and claim NO removals).

REAL ERROR / SENTINEL PATHS (read from the Java)
------------------------------------------------
  - projectName missing/empty: AbstractMetadataWriteTool.execute() runs the
    BUILDING-only pre-check (null for an absent name) then marshals to the UI thread;
    executeOnUiThread hits `if (projectName == null || projectName.isEmpty())
    return ToolResult.error("projectName is required")`.
  - projectName non-existent: resolveProjectAndConfig -> ProjectContext.notFoundMessage
    (names the bad value AND points at list_projects).
"""

import os
import time

from harness import (
    call,
    assert_ok,
    assert_error,
    assert_error_quality,
    assert_no_diff,
    e2e_test,
    wait_for_project_ready,
    PROJECT,
    PROJECT_DIR,
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


def _assert_clean_dangling_scan(sc, ctx):
    """On the committed fixture no valid reference may be mistaken for dangling."""
    if sc.get("danglingFound") != 0:
        raise AssertionError(
            "the in-sync fixture must have no dangling references [%s]: danglingFound=%r details=%r"
            % (ctx, sc.get("danglingFound"), sc.get("danglingDetails")))
    if sc.get("danglingRemovedCount") != 0:
        raise AssertionError(
            "nothing may be removed on a clean Configuration [%s]: danglingRemovedCount=%r"
            % (ctx, sc.get("danglingRemovedCount")))


def _poll(predicate, timeout=15):
    """Poll a boolean predicate (no blind sleep); returns its final value."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(0.5)
    return predicate()


# ──────────────────────────────────────────────────────────────────────────────
# HAPPY PATH (safe: the committed fixture is in-sync -> default run is a no-op)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="resync_to_disk", kind="action")
def test_resync_in_sync_fixture_is_a_noop_and_does_not_mutate():
    """The committed fixture is in-sync, so a DEFAULT resync must report it as such and
    write nothing: missingBeforeCount==0, stillMissingCount==0, danglingFound==0 — and,
    because only the MISSING subset is exported by default, objectsExported must be 0
    (there is nothing missing to export) while totalTopObjects stays positive.

    Mutation thinking: a tool that mis-classified internal non-MdObject top objects
    (content forms, BSL index objects) as missing .mdo would push missingBeforeCount>0
    and objectsExported>0; a regression back to export-everything-by-default would show
    objectsExported==totalTopObjects; a tool that wrongly flagged a VALID reference as
    dangling would report danglingFound>0. The no-op envelope plus a clean working tree
    pins all of it."""
    wait_for_project_ready()  # a slow runner may still be recomputing after a prior test
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
    _assert_clean_dangling_scan(sc, "in-sync resync")
    # Subset-export default: nothing missing -> nothing exported. The walk itself must
    # still have seen the whole configuration.
    if sc.get("objectsExported") != 0:
        raise AssertionError(
            "an in-sync default resync must export NOTHING (subset export): objectsExported=%r"
            % sc.get("objectsExported"))
    total = sc.get("totalTopObjects")
    if not isinstance(total, int) or total <= 0:
        raise AssertionError("totalTopObjects must be a positive int: %r" % total)

    # Ground truth: a default run on an in-sync project writes nothing at all, so the
    # committed tree must be unchanged.
    assert_no_diff("default resync of an in-sync project must not change any tracked file")


@e2e_test(tool="resync_to_disk", kind="action")
def test_full_export_reexports_every_top_object():
    """fullExport=true opts back in to the export-everything refresh: every walked top
    object is force-exported (objectsExported == totalTopObjects > 0), the flag is
    echoed back, and — because the fixture is in-sync — the re-export rewrites the SAME
    bytes, so the committed tree still must not change.

    Mutation thinking: a fullExport that silently stayed on the subset path would
    report objectsExported==0 here; an export that produced different bytes for an
    unchanged model would fail assert_no_diff."""
    wait_for_project_ready()  # a slow runner may still be recomputing after a prior test
    r = call("resync_to_disk", {"projectName": PROJECT, "fullExport": True})
    assert_ok(r, "resync_to_disk fullExport=true")

    sc = _success_envelope(r, "full-export resync")
    if sc.get("fullExport") is not True:
        raise AssertionError("envelope must echo fullExport=true: %r" % sc.get("fullExport"))
    exported = sc.get("objectsExported")
    total = sc.get("totalTopObjects")
    if not isinstance(exported, int) or exported <= 0:
        raise AssertionError("fullExport must export a positive count: %r" % exported)
    if exported != total:
        raise AssertionError(
            "fullExport must export EVERY walked object: objectsExported(%r) != totalTopObjects(%r)"
            % (exported, total))
    _assert_clean_dangling_scan(sc, "full-export resync")

    assert_no_diff("full re-export of an in-sync project must rewrite identical bytes")


# ──────────────────────────────────────────────────────────────────────────────
# DANGLING-SCAN FLAG CONTRACT (the removal itself is unit-covered — see module doc)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="resync_to_disk", kind="action")
def test_report_only_is_the_default():
    """A call WITHOUT cleanDanglingReferences must run the scan report-only: the
    envelope echoes cleanDanglingReferences==false and danglingRemovedCount stays 0.
    This pins the review-driven default flip (the destructive removal must be an
    explicit opt-in, never the default).

    Mutation thinking: a regression back to default-true would echo true here and (on
    a project that DID have dangling entries) silently rewrite Configuration.mdo."""
    wait_for_project_ready()  # a slow runner may still be recomputing after a prior test
    r = call("resync_to_disk", {"projectName": PROJECT})
    assert_ok(r, "resync_to_disk with all-default parameters")

    sc = _success_envelope(r, "default resync")
    if sc.get("cleanDanglingReferences") is not False:
        raise AssertionError(
            "the DEFAULT must be report-only (cleanDanglingReferences=false): %r"
            % sc.get("cleanDanglingReferences"))
    if sc.get("danglingRemovedCount") != 0:
        raise AssertionError(
            "report-only default must remove nothing: danglingRemovedCount=%r"
            % sc.get("danglingRemovedCount"))
    if sc.get("danglingRemoved"):
        raise AssertionError(
            "report-only default must list no removed entries: %r" % sc.get("danglingRemoved"))

    assert_no_diff("a default (report-only) resync must not change any tracked file")


@e2e_test(tool="resync_to_disk", kind="action")
def test_clean_dangling_true_is_echoed_and_safe_on_clean_fixture():
    """cleanDanglingReferences=true on the CLEAN fixture: the opt-in flag must be
    echoed back, but with danglingFound==0 there is nothing to remove, so
    danglingRemovedCount stays 0 and the tree stays clean (idempotent on a clean
    Configuration). Fabricating a real dangling reference headless is not possible via
    public tools (see module doc), so the actual removal outcome is pinned by the
    runRemovalWriteTask unit tests instead.

    Mutation thinking: a tool that ignored the flag would echo false; one that
    'removed' valid references on a clean project would report danglingRemovedCount>0
    and fail assert_no_diff."""
    wait_for_project_ready()  # a slow runner may still be recomputing after a prior test
    r = call("resync_to_disk", {"projectName": PROJECT, "cleanDanglingReferences": True})
    assert_ok(r, "resync_to_disk cleanDanglingReferences=true")

    sc = _success_envelope(r, "opt-in dangling cleanup")
    if sc.get("cleanDanglingReferences") is not True:
        raise AssertionError(
            "envelope must echo cleanDanglingReferences=true: %r" % sc.get("cleanDanglingReferences"))
    _assert_clean_dangling_scan(sc, "opt-in cleanup on a clean fixture")

    assert_no_diff("an opt-in cleanup with nothing dangling must not change any tracked file")


@e2e_test(tool="resync_to_disk", kind="action")
def test_revalidate_flag_is_echoed():
    """The revalidate request flag must be echoed in the envelope so a caller can
    verify what actually ran: absent -> false, explicit false -> false. (revalidate=true
    schedules a FULL clean build — deliberately not exercised here to keep the suite
    fast; the flag echo is what pins the wiring.)

    Mutation thinking: a tool that dropped the echo (or hardwired the flag) would
    return a missing/true value for a default call."""
    wait_for_project_ready()  # a slow runner may still be recomputing after a prior test
    r = call("resync_to_disk", {"projectName": PROJECT})
    sc = _success_envelope(r, "default resync (revalidate echo)")
    if sc.get("revalidate") is not False:
        raise AssertionError(
            "a default call must echo revalidate=false: %r" % sc.get("revalidate"))

    r = call("resync_to_disk", {"projectName": PROJECT, "revalidate": False})
    sc = _success_envelope(r, "explicit revalidate=false echo")
    if sc.get("revalidate") is not False:
        raise AssertionError(
            "an explicit revalidate=false must be echoed back: %r" % sc.get("revalidate"))

    assert_no_diff("the echo probes must not change any tracked file")


# ──────────────────────────────────────────────────────────────────────────────
# REPAIR PATH (the tool's reason to exist: restore a .mdo the model still has)
# ──────────────────────────────────────────────────────────────────────────────
@e2e_test(tool="resync_to_disk", kind="write-metadata")
def test_repair_restores_a_deleted_mdo_from_the_model():
    """End-to-end desync repair: create a Catalog (model + .mdo on disk), delete its
    .mdo straight off the fixture path — the exact "registered in the model /
    Configuration.mdo but no file on disk" drift the tool exists to fix — then run a
    DEFAULT resync. The export side must detect the loss (missingBeforeCount>0, the
    FQN listed in missingBefore) and restore the file (stillMissingCount==0, the .mdo
    back on disk). objectsExported == missingBeforeCount pins the subset decision
    LIVE: only the missing objects were exported, not the whole configuration.

    kind=write-metadata: the orchestrator reverts the fixture and clean_projects the
    model afterwards, so the seeded catalog never leaks into the next test."""
    name = "E2EResyncRepair"
    fqn = "Catalog." + name
    wait_for_project_ready()  # a slow runner may still be recomputing after a prior test

    r = call("create_metadata", {"projectName": PROJECT, "fqn": fqn})
    assert_ok(r, "seed: create %s" % fqn)
    mdo_abs = os.path.join(PROJECT_DIR, "src", "Catalogs", name, name + ".mdo")
    # The create's own .mdo export can lag a beat; the file must exist before we can
    # meaningfully delete it.
    if not _poll(lambda: os.path.isfile(mdo_abs)):
        raise AssertionError("setup failed: %s was never written by create_metadata" % mdo_abs)
    # Let the create's derived-data recompute settle so the resync sees a stable model.
    wait_for_project_ready()

    os.remove(mdo_abs)
    if os.path.exists(mdo_abs):
        raise AssertionError("setup failed: could not delete %s" % mdo_abs)

    r = call("resync_to_disk", {"projectName": PROJECT})
    assert_ok(r, "resync repairs the deleted .mdo")
    sc = _success_envelope(r, "repair resync")

    missing_count = sc.get("missingBeforeCount")
    if not isinstance(missing_count, int) or missing_count < 1:
        raise AssertionError(
            "the deleted .mdo must be detected as the desync: missingBeforeCount=%r" % missing_count)
    missing = sc.get("missingBefore") or []
    if fqn not in missing:
        raise AssertionError(
            "missingBefore must name the lost object %r: %r" % (fqn, missing))
    if sc.get("stillMissingCount") != 0:
        raise AssertionError(
            "the repair must leave nothing missing: stillMissingCount=%r (stillMissing=%r)"
            % (sc.get("stillMissingCount"), sc.get("stillMissing")))
    # The subset decision, observed live: exactly the missing objects were exported.
    if sc.get("objectsExported") != missing_count:
        raise AssertionError(
            "default export must cover exactly the missing subset: objectsExported=%r != "
            "missingBeforeCount=%r" % (sc.get("objectsExported"), missing_count))

    # Ground truth on disk: the .mdo is back (the flush can lag a beat — poll, no blind sleep).
    if not _poll(lambda: os.path.isfile(mdo_abs)):
        raise AssertionError("the repaired .mdo never reappeared on disk: %s" % mdo_abs)


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
