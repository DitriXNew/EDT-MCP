/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.IResourceProxyVisitor;
import org.eclipse.core.runtime.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.PreLaunchChangeTracker.PrepareSnapshot;

/**
 * Unit tests for {@link PreLaunchChangeTracker}.
 *
 * <p>All tests run headless (no OSGi runtime). The listener-installation is never
 * exercised — the workspace-listener signal is seeded directly via
 * {@link PreLaunchChangeTracker#markDirty}, and the two halves of the
 * persistent gate (what the project's content currently hashes to, and what was
 * recorded as prepared) are driven through the package-visible seams
 * {@link PreLaunchChangeTracker#setFingerprinterForTest} /
 * {@link PreLaunchChangeTracker#setStoreForTest}. The store used here is an
 * in-memory map that deliberately OUTLIVES
 * {@link PreLaunchChangeTracker#simulatePluginRestartForTest}, exactly as the
 * workspace metadata outlives an EDT restart — that is what lets the restart
 * scenarios be asserted in process.
 *
 * <p>The delta classifier is driven through Mockito mocks of
 * {@link IResourceDelta} / {@link IResource}, and the content walk through mocked
 * {@link IResourceProxy} visits. The live workspace-listener install path needs the
 * Eclipse runtime and is an integration concern; only the pure, mockable parts are
 * asserted here.
 */
public class PreLaunchChangeTrackerTest
{
    /** What each project's content currently fingerprints to (the "sources"). */
    private final Map<String, String> content = new HashMap<>();

    /** Survives {@code simulatePluginRestartForTest} — models the workspace metadata. */
    private final MapStore store = new MapStore();

    @Before
    public void setUp()
    {
        PreLaunchChangeTracker.setStoreForTest(store);
        PreLaunchChangeTracker.setFingerprinterForTest(project -> content.get(project.getName()));
    }

    @After
    public void tearDown()
    {
        PreLaunchChangeTracker.resetForTest();
    }

    // =========================================================================
    // isDirty — nothing recorded as prepared yet
    // =========================================================================

    @Test
    public void testNeverPreparedIsDirty()
    {
        // No prepared-content marker at all: the project may have been edited by
        // anyone at any time, so it must be recomputed.
        IProject project = mockProject("Config");
        content.put("Config", "fp-1");
        assertTrue("never-prepared project must be dirty", isDirty(project));
    }

    @Test
    public void testNullProjectIsNotDirty()
    {
        assertFalse("null project must not be dirty", isDirty(null));
    }

    @Test
    public void testUnreadableContentIsDirty()
    {
        // The fingerprinter could not read the project (closed, I/O failure): we
        // cannot prove it is unchanged, so it must be recomputed.
        IProject project = mockProject("Config");
        content.put("Config", "fp-1");
        prepare(project);
        assertFalse("prepared at fp-1", isDirty(project));

        content.remove("Config"); // fingerprinter now returns null
        assertTrue("an unreadable project must be dirty", isDirty(project));
    }

    // =========================================================================
    // isDirty — after a successful prepare
    // =========================================================================

    @Test
    public void testPreparedProjectIsClean()
    {
        IProject project = mockProject("Config");
        content.put("Config", "fp-1");
        prepare(project);
        assertFalse("a prepared project with no subsequent change must be clean",
            isDirty(project));
    }

    /**
     * The regression this gate was rebuilt for: the "prepared" mark must be a
     * property of the CONTENT, not of the running plugin instance. After a restart
     * of EDT (modelled by dropping the in-memory session state while the workspace
     * metadata stays) an unchanged project must NOT trigger a full recompute.
     */
    @Test
    public void testPreparedProjectStaysCleanAcrossPluginRestart()
    {
        IProject project = mockProject("Config");
        content.put("Config", "fp-1");
        prepare(project);

        PreLaunchChangeTracker.simulatePluginRestartForTest();

        assertFalse("an unchanged project must stay clean across an EDT restart",
            isDirty(project));
    }

    /**
     * The other direction, and the stale-{@code .cfe} half of the guarantee: a
     * source change made while the plugin was NOT running (no resource delta was
     * ever observed) must still be seen, because the content no longer matches what
     * was recorded as prepared.
     */
    @Test
    public void testContentChangedWhilePluginWasDownIsDirty()
    {
        IProject project = mockProject("Config");
        content.put("Config", "fp-1");
        prepare(project);

        PreLaunchChangeTracker.simulatePluginRestartForTest();
        content.put("Config", "fp-2"); // somebody edited the sources meanwhile

        assertTrue("a project edited while the plugin was down must be dirty",
            isDirty(project));
    }

    @Test
    public void testFileChangeAfterPrepareRedirties()
    {
        IProject project = mockProject("Config");
        content.put("Config", "fp-1");
        prepare(project);
        assertFalse("clean after prepare", isDirty(project));

        PreLaunchChangeTracker.markDirty("Config");
        assertTrue("dirty after a file change", isDirty(project));
    }

    @Test
    public void testMarkPreparedClearsDirtyFlag()
    {
        IProject project = mockProject("Config");
        content.put("Config", "fp-1");
        prepare(project);
        PreLaunchChangeTracker.markDirty("Config");
        assertTrue("dirty after explicit mark", isDirty(project));

        prepare(project);
        assertFalse("markPrepared must clear the dirty flag",
            isDirty(project));
    }

    @Test
    public void testMarkPreparedNullCollectionIsNoOp()
    {
        // Must not throw when null or null entries are passed.
        PreLaunchChangeTracker.markPrepared(null, null);
        PreLaunchChangeTracker.markPrepared(Collections.singletonList(null), null);
    }

    @Test
    public void testMarkPreparedWithoutSnapshotCertifiesNothing()
    {
        // A caller that has no snapshot cannot tell WHICH content state was
        // prepared, so nothing may be recorded — and any earlier record must go.
        IProject project = mockProject("Config");
        content.put("Config", "fp-1");
        prepare(project);
        assertFalse("clean after a real prepare", isDirty(project));

        PreLaunchChangeTracker.markPrepared(Collections.singletonList(project), null);
        assertTrue("a snapshot-less markPrepared must not certify the content",
            isDirty(project));
    }

    // =========================================================================
    // isDirty — multiple projects (dirty/clean partition)
    // =========================================================================

    @Test
    public void testDirtyCleanPartitionIndependent()
    {
        IProject projectA = mockProject("Config");
        IProject projectB = mockProject("Extension");
        content.put("Config", "cfg-1");
        content.put("Extension", "ext-1");

        prepare(projectA, projectB);
        assertFalse("Config must be clean", isDirty(projectA));
        assertFalse("Extension must be clean", isDirty(projectB));

        // Only the extension changed.
        content.put("Extension", "ext-2");
        assertFalse("Config must remain clean", isDirty(projectA));
        assertTrue("Extension must be dirty", isDirty(projectB));
    }

    @Test
    public void testMarkPreparedPartialListLeavesOtherDirty()
    {
        IProject projectA = mockProject("Config");
        IProject projectB = mockProject("Extension");
        content.put("Config", "cfg-1");
        content.put("Extension", "ext-1");

        prepare(projectA, projectB);
        content.put("Config", "cfg-2");
        content.put("Extension", "ext-2");

        // Prepare only Config.
        prepare(projectA);
        assertFalse("Config must be clean after its own prepare",
            isDirty(projectA));
        assertTrue("Extension must still be dirty (not in the prepare call)",
            isDirty(projectB));
    }

    // =========================================================================
    // snapshot — the scope-wide view the recompute partition uses
    // =========================================================================

    @Test
    public void testSnapshotReportsDirtyScope()
    {
        IProject projectA = mockProject("Config");
        IProject projectB = mockProject("Extension");
        content.put("Config", "cfg-1");
        content.put("Extension", "ext-1");
        prepare(projectA, projectB);
        content.put("Extension", "ext-2");

        PrepareSnapshot snapshot =
            PreLaunchChangeTracker.snapshot(Arrays.asList(projectA, projectB));

        assertFalse("unchanged project must not be in the dirty set",
            snapshot.isDirty(projectA));
        assertTrue("changed project must be in the dirty set", snapshot.isDirty(projectB));
    }

    @Test
    public void testSnapshotSeesAChangeThatLandsAfterItWasTaken()
    {
        // The caller partitions the scope AFTER taking the snapshot. A change that
        // lands in that window must be recomputed by THIS launch, not deferred.
        IProject project = mockProject("Config");
        content.put("Config", "fp-1");
        prepare(project);

        PrepareSnapshot snapshot =
            PreLaunchChangeTracker.snapshot(Collections.singletonList(project));
        assertFalse("clean when the snapshot was taken", snapshot.isDirty(project));

        PreLaunchChangeTracker.markDirty("Config");
        assertTrue("a change after the snapshot must still force this recompute",
            snapshot.isDirty(project));
    }

    @Test
    public void testSnapshotSkipsClosedProjects()
    {
        IProject closed = mock(IProject.class);
        when(closed.getName()).thenReturn("Closed");
        when(closed.exists()).thenReturn(true);
        when(closed.isOpen()).thenReturn(false);

        PrepareSnapshot snapshot = PreLaunchChangeTracker.snapshot(Arrays.asList(closed, null));
        assertFalse("a closed project must not be judged dirty", snapshot.isDirty(closed));
    }

    // =========================================================================
    // Ordering-race regression: a new dirty event arriving DURING recompute must
    // not be silently cleared by the subsequent markPrepared — and must not be
    // certified as prepared content either.
    // =========================================================================

    @Test
    public void testDirtyEventDuringRecomputeKeepsProjectDirty()
    {
        // Setup: project has been prepared once at fp-1.
        IProject project = mockProject("Config");
        content.put("Config", "fp-1");
        prepare(project);

        // Step 1: a change lands, so the next launch recomputes.
        content.put("Config", "fp-2");
        PreLaunchChangeTracker.markDirty("Config");
        assertTrue("project must be dirty before snapshot", isDirty(project));

        // Step 2: snapshot is taken (simulates what recomputeAndSettleIfDirty does).
        List<IProject> scope = Collections.singletonList(project);
        PrepareSnapshot snapshot = PreLaunchChangeTracker.snapshot(scope);
        assertNotNull("snapshot must capture the dirty entry",
            PreLaunchChangeTracker.getDirtyGenerationForTest("Config"));

        // Step 3: a NEW dirty event arrives DURING the recompute (higher generation).
        content.put("Config", "fp-3");
        PreLaunchChangeTracker.markDirty("Config");

        // Step 4: markPrepared is called with the STALE snapshot.
        PreLaunchChangeTracker.markPrepared(scope, snapshot);

        // The project must STILL be dirty — the conditional remove must have failed
        // because the stored generation is now higher than the snapshot.
        assertTrue("project must remain dirty after a change-during-recompute",
            isDirty(project));
        assertNotNull("DIRTY map entry must not have been removed",
            PreLaunchChangeTracker.getDirtyGenerationForTest("Config"));
    }

    /**
     * The persistent half of the same race: the content state that re-dirtied
     * mid-recompute was never fully prepared, so it must not be recorded — otherwise
     * the next EDT session (whose in-memory dirty map is empty) would read it as
     * prepared and ship a stale {@code .cfe}.
     */
    @Test
    public void testChangeDuringRecomputeIsNotCertifiedAcrossRestart()
    {
        IProject project = mockProject("Config");
        content.put("Config", "fp-1");
        prepare(project);

        content.put("Config", "fp-2");
        PreLaunchChangeTracker.markDirty("Config");
        List<IProject> scope = Collections.singletonList(project);
        PrepareSnapshot snapshot = PreLaunchChangeTracker.snapshot(scope);
        // A change lands while the recompute is running.
        PreLaunchChangeTracker.markDirty("Config");
        PreLaunchChangeTracker.markPrepared(scope, snapshot);

        assertNull("the unfinished content state must not be recorded as prepared",
            store.values.get("Config"));

        PreLaunchChangeTracker.simulatePluginRestartForTest();
        assertTrue("a restart must not turn an unfinished prepare into a clean project",
            isDirty(project));
    }

    // =========================================================================
    // PrepInFlight double-start test (Finding 3): only one thread wins the
    // started.compareAndSet(false, true) gate.
    // =========================================================================

    @Test
    public void testPrepInFlightOnlyOneThreadWinsStartedCas()
    {
        // Two sequential CAS calls on the same entry — only the first wins.
        LaunchLifecycleUtils.PrepInFlight entry =
            new LaunchLifecycleUtils.PrepInFlight(System.currentTimeMillis());

        AtomicBoolean firstWon = new AtomicBoolean(false);
        AtomicBoolean secondWon = new AtomicBoolean(false);

        if (entry.started.compareAndSet(false, true))
        {
            firstWon.set(true);
        }
        if (entry.started.compareAndSet(false, true))
        {
            secondWon.set(true);
        }

        assertTrue("first CAS must win", firstWon.get());
        assertFalse("second CAS must NOT win — only one Job must be scheduled", secondWon.get());
    }

    // =========================================================================
    // Content fingerprint — the walk and its folding
    // =========================================================================

    @Test
    public void testFingerprintIgnoresDerivedFiles()
    {
        // Build output is produced BY the recompute; counting it as "the user
        // changed something" would make every launch dirty forever.
        IProject project = mockProject("Config");
        stubWalk(project, proxy("/Config/src/a.bsl", 10L, IResource.FILE, false));
        String withoutDerived = PreLaunchChangeTracker.computeContentFingerprint(project);

        // A derived FOLDER must prune its whole subtree, including files that are
        // not themselves flagged derived.
        stubWalk(project, proxy("/Config/src/a.bsl", 10L, IResource.FILE, false),
            proxy("/Config/bin", 5L, IResource.FOLDER, true),
            proxy("/Config/bin/a.cfe", 77L, IResource.FILE, false));
        String withDerived = PreLaunchChangeTracker.computeContentFingerprint(project);

        assertEquals("derived output must not change the fingerprint", withoutDerived, withDerived);
    }

    /**
     * When the project folder IS the repository root, {@code .git} is part of the
     * resource tree and every ordinary Git operation rewrites files in it. Those
     * files are not 1C sources, and letting them count would force a full recompute
     * after a mere {@code git status}.
     */
    @Test
    public void testFingerprintIgnoresTheGitStore()
    {
        IProject project = mockProject("Config");
        stubWalk(project, proxy("/Config/src/a.bsl", 10L, IResource.FILE, false));
        String withoutGit = PreLaunchChangeTracker.computeContentFingerprint(project);

        stubWalk(project, proxy("/Config/src/a.bsl", 10L, IResource.FILE, false),
            proxy("/Config/.git", 1L, IResource.FOLDER, false),
            proxy("/Config/.git/index", 999L, IResource.FILE, false));
        String withGit = PreLaunchChangeTracker.computeContentFingerprint(project);

        assertEquals("the git store must not change the fingerprint", withoutGit, withGit);
    }

    @Test
    public void testGitStoreChangeDoesNotDirtyTheProject()
    {
        // Same rule on the other signal: a write inside .git is not a source change.
        IResourceDelta delta = mockFileDelta(IResourceDelta.CHANGED, IResourceDelta.CONTENT, false);
        when(delta.getFullPath()).thenReturn(new Path("/Config/.git/index"));
        assertFalse("a write inside the git store must NOT make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));

        IResourceDelta source = mockFileDelta(IResourceDelta.CHANGED, IResourceDelta.CONTENT, false);
        when(source.getFullPath()).thenReturn(new Path("/Config/src/a.bsl"));
        assertTrue("a source write must still make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(source));
    }

    @Test
    public void testFingerprintFollowsModificationStamp()
    {
        IProject project = mockProject("Config");
        stubWalk(project, proxy("/Config/src/a.bsl", 10L, IResource.FILE, false));
        String before = PreLaunchChangeTracker.computeContentFingerprint(project);

        stubWalk(project, proxy("/Config/src/a.bsl", 11L, IResource.FILE, false));
        String after = PreLaunchChangeTracker.computeContentFingerprint(project);

        assertNotEquals("an edited file must change the fingerprint", before, after);
    }

    @Test
    public void testFingerprintFollowsAddedAndRenamedFiles()
    {
        IProject project = mockProject("Config");
        stubWalk(project, proxy("/Config/src/a.bsl", 10L, IResource.FILE, false));
        String one = PreLaunchChangeTracker.computeContentFingerprint(project);

        stubWalk(project, proxy("/Config/src/a.bsl", 10L, IResource.FILE, false),
            proxy("/Config/src/b.bsl", 10L, IResource.FILE, false));
        String two = PreLaunchChangeTracker.computeContentFingerprint(project);
        assertNotEquals("an added file must change the fingerprint", one, two);

        stubWalk(project, proxy("/Config/src/renamed.bsl", 10L, IResource.FILE, false));
        String renamed = PreLaunchChangeTracker.computeContentFingerprint(project);
        assertNotEquals("a renamed file must change the fingerprint", one, renamed);
    }

    @Test
    public void testFingerprintIgnoresFoldersAndVisitOrder()
    {
        // IContainer member order is not part of the API contract, so the token
        // must not depend on it (otherwise a project would look "changed" for free).
        IProject project = mockProject("Config");
        stubWalk(project, proxy("/Config/src", 1L, IResource.FOLDER, false),
            proxy("/Config/src/a.bsl", 10L, IResource.FILE, false),
            proxy("/Config/src/b.bsl", 20L, IResource.FILE, false));
        String forward = PreLaunchChangeTracker.computeContentFingerprint(project);

        stubWalk(project, proxy("/Config/src/b.bsl", 20L, IResource.FILE, false),
            proxy("/Config/src", 99L, IResource.FOLDER, false),
            proxy("/Config/src/a.bsl", 10L, IResource.FILE, false));
        String shuffled = PreLaunchChangeTracker.computeContentFingerprint(project);

        assertEquals("visit order and folder stamps must not change the fingerprint",
            forward, shuffled);
    }

    // =========================================================================
    // Production store — the half that actually outlives the plugin
    // =========================================================================

    /**
     * The gate is only restart-proof if the mark is written to storage that
     * outlives the session. A SESSION property would compile and pass every
     * gate-logic test above while re-introducing exactly the bug this replaced,
     * so the choice of a PERSISTENT property is pinned here.
     */
    @Test
    public void testProductionStoreUsesAPersistentProperty() throws Exception
    {
        IProject project = mockProject("Config");
        when(project.getPersistentProperty(PreLaunchChangeTracker.FINGERPRINT_PROPERTY))
            .thenReturn("fp-1");
        PreLaunchChangeTracker.PersistentPropertyStore productionStore =
            new PreLaunchChangeTracker.PersistentPropertyStore();

        assertEquals("load must read the persistent property", "fp-1",
            productionStore.load(project));

        productionStore.save(project, "fp-2");
        verify(project).setPersistentProperty(PreLaunchChangeTracker.FINGERPRINT_PROPERTY, "fp-2");

        productionStore.clear(project);
        verify(project).setPersistentProperty(PreLaunchChangeTracker.FINGERPRINT_PROPERTY, null);
    }

    @Test
    public void testProductionStoreSurvivesAFailingProject() throws Exception
    {
        // A store that cannot answer must make the project look UNPREPARED (one
        // extra recompute), never prepared, and must never throw into the chain.
        IProject project = mockProject("Config");
        when(project.getPersistentProperty(PreLaunchChangeTracker.FINGERPRINT_PROPERTY))
            .thenThrow(new org.eclipse.core.runtime.CoreException(
                org.eclipse.core.runtime.Status.error("boom")));
        PreLaunchChangeTracker.PersistentPropertyStore productionStore =
            new PreLaunchChangeTracker.PersistentPropertyStore();

        assertNull("an unreadable marker must read as absent", productionStore.load(project));
    }

    @Test
    public void testFileTokenSeparatesPathAndStamp()
    {
        assertNotEquals("path must participate",
            PreLaunchChangeTracker.fileToken("/p/a.bsl", 1L),
            PreLaunchChangeTracker.fileToken("/p/b.bsl", 1L));
        assertNotEquals("stamp must participate",
            PreLaunchChangeTracker.fileToken("/p/a.bsl", 1L),
            PreLaunchChangeTracker.fileToken("/p/a.bsl", 2L));
        assertEquals("same path and stamp must give the same token",
            PreLaunchChangeTracker.fileToken("/p/a.bsl", 1L),
            PreLaunchChangeTracker.fileToken("/p/a.bsl", 1L));
    }

    @Test
    public void testCombineSeparatesFileCount()
    {
        // Two files whose tokens cancel out in the xor must still differ from one
        // file: the count is part of the fold.
        assertNotEquals(PreLaunchChangeTracker.combine(1L, 5L, 5L),
            PreLaunchChangeTracker.combine(2L, 5L, 5L));
    }

    // =========================================================================
    // deltaMakesProjectDirty — ADDED / REMOVED kind
    // =========================================================================

    @Test
    public void testAddedFileIsDirty()
    {
        IResourceDelta delta = mockFileDelta(IResourceDelta.ADDED, 0, false);
        assertTrue("ADDED non-derived file must make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    @Test
    public void testRemovedFileIsDirty()
    {
        IResourceDelta delta = mockFileDelta(IResourceDelta.REMOVED, 0, false);
        assertTrue("REMOVED non-derived file must make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    @Test
    public void testAddedDerivedFileIsNotDirty()
    {
        IResourceDelta delta = mockFileDelta(IResourceDelta.ADDED, 0, true);
        assertFalse("ADDED derived file must NOT make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    @Test
    public void testRemovedDerivedFileIsNotDirty()
    {
        IResourceDelta delta = mockFileDelta(IResourceDelta.REMOVED, 0, true);
        assertFalse("REMOVED derived file must NOT make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    // =========================================================================
    // deltaMakesProjectDirty — CHANGED with content-carrying flags
    // =========================================================================

    @Test
    public void testChangedWithContentFlagIsDirty()
    {
        IResourceDelta delta = mockFileDelta(IResourceDelta.CHANGED, IResourceDelta.CONTENT, false);
        assertTrue("CHANGED+CONTENT must make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    @Test
    public void testChangedWithMovedFromFlagIsDirty()
    {
        IResourceDelta delta = mockFileDelta(IResourceDelta.CHANGED, IResourceDelta.MOVED_FROM, false);
        assertTrue("CHANGED+MOVED_FROM must make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    @Test
    public void testChangedWithMovedToFlagIsDirty()
    {
        IResourceDelta delta = mockFileDelta(IResourceDelta.CHANGED, IResourceDelta.MOVED_TO, false);
        assertTrue("CHANGED+MOVED_TO must make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    @Test
    public void testChangedWithReplacedFlagIsDirty()
    {
        IResourceDelta delta = mockFileDelta(IResourceDelta.CHANGED, IResourceDelta.REPLACED, false);
        assertTrue("CHANGED+REPLACED must make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    @Test
    public void testChangedWithContentAndDerivedIsNotDirty()
    {
        // A derived file with CONTENT changes must never be counted as user content.
        IResourceDelta delta = mockFileDelta(IResourceDelta.CHANGED, IResourceDelta.CONTENT, true);
        assertFalse("CHANGED+CONTENT on a derived file must NOT make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    @Test
    public void testFileInsideADerivedFolderIsNotDirty()
    {
        // The file itself carries NO derived bit — only its folder does. Reading it
        // as a source change would re-dirty the project on every single build, so
        // the recompute would never be skipped no matter what the fingerprint says.
        IResource resource = mock(IResource.class);
        when(resource.getType()).thenReturn(IResource.FILE);
        when(resource.isDerived()).thenReturn(false);
        when(resource.isDerived(IResource.CHECK_ANCESTORS)).thenReturn(true);

        IResourceDelta delta = mock(IResourceDelta.class);
        when(delta.getResource()).thenReturn(resource);
        when(delta.getKind()).thenReturn(IResourceDelta.CHANGED);
        when(delta.getFlags()).thenReturn(IResourceDelta.CONTENT);

        assertFalse("build output under a derived folder must NOT make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    // =========================================================================
    // deltaMakesProjectDirty — marker-only delta (ignored)
    // =========================================================================

    @Test
    public void testChangedWithMarkersOnlyIsNotDirty()
    {
        // Marker-only change (problem markers, bookmarks, etc.) is metadata
        // bookkeeping and must not be treated as a content change.
        IResourceDelta delta = mockFileDelta(IResourceDelta.CHANGED, IResourceDelta.MARKERS, false);
        assertFalse("CHANGED+MARKERS-only must NOT make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    @Test
    public void testChangedWithMarkersAndContentIsDirty()
    {
        // Marker flag combined with a content-carrying flag — the CONTENT flag
        // dominates; the project is dirty.
        int flags = IResourceDelta.MARKERS | IResourceDelta.CONTENT;
        IResourceDelta delta = mockFileDelta(IResourceDelta.CHANGED, flags, false);
        assertTrue("CHANGED+MARKERS+CONTENT must make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    // =========================================================================
    // deltaMakesProjectDirty — non-FILE resource kinds (folders, projects)
    // =========================================================================

    @Test
    public void testFolderDeltaIsNotDirty()
    {
        // Only FILE deltas should count — a folder entry records structural
        // membership, not file content.
        IResourceDelta delta = mockResourceDelta(IResourceDelta.ADDED, 0, IResource.FOLDER, false);
        assertFalse("ADDED folder delta must NOT make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    @Test
    public void testProjectDeltaIsNotDirty()
    {
        IResourceDelta delta = mockResourceDelta(IResourceDelta.CHANGED, IResourceDelta.CONTENT,
            IResource.PROJECT, false);
        assertFalse("PROJECT-level delta must NOT make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    // =========================================================================
    // deltaMakesProjectDirty — null safety
    // =========================================================================

    @Test
    public void testNullDeltaIsNotDirty()
    {
        assertFalse("null delta must not make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(null));
    }

    @Test
    public void testDeltaWithNullResourceIsNotDirty()
    {
        IResourceDelta delta = mock(IResourceDelta.class);
        when(delta.getResource()).thenReturn(null);
        assertFalse("delta with null resource must not make the project dirty",
            PreLaunchChangeTracker.deltaMakesProjectDirty(delta));
    }

    // =========================================================================
    // PrepInFlight — state-machine basics (headless, no Job)
    // =========================================================================

    @Test
    public void testPrepInFlightElapsedSecondsNonNegative()
    {
        LaunchLifecycleUtils.PrepInFlight entry =
            new LaunchLifecycleUtils.PrepInFlight(System.currentTimeMillis());
        assertTrue("elapsedSeconds must be >= 0", entry.elapsedSeconds() >= 0);
    }

    @Test
    public void testPrepInFlightNotDoneInitially()
    {
        LaunchLifecycleUtils.PrepInFlight entry =
            new LaunchLifecycleUtils.PrepInFlight(System.currentTimeMillis());
        assertFalse("entry must not be done initially", entry.done);
    }

    @Test
    public void testPrepInFlightNotExpiredImmediately()
    {
        LaunchLifecycleUtils.PrepInFlight entry =
            new LaunchLifecycleUtils.PrepInFlight(System.currentTimeMillis());
        assertFalse("a brand-new entry must not be expired", entry.isExpired());
    }

    @Test
    public void testPrepInFlightExpiredAfterExpiryTime()
    {
        // Seed with a start time well in the past (> INFLIGHT_EXPIRY_MS = 10 min).
        long veryOld = System.currentTimeMillis() - (11 * 60 * 1000L);
        LaunchLifecycleUtils.PrepInFlight entry = new LaunchLifecycleUtils.PrepInFlight(veryOld);
        assertTrue("an entry older than 10 min must be expired", entry.isExpired());
    }

    @Test
    public void testPrepInFlightLatchCountsDown() throws InterruptedException
    {
        LaunchLifecycleUtils.PrepInFlight entry =
            new LaunchLifecycleUtils.PrepInFlight(System.currentTimeMillis());
        // Count down the latch to simulate a background job completing.
        entry.done = true;
        entry.latch.countDown();
        // Await with zero timeout — latch is already counted-down.
        boolean released = entry.latch.await(0, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue("latch must be counted down", released);
    }

    @Test
    public void testPrepKeyForNullSafe()
    {
        // prepKeyFor must not throw on null arguments.
        String key1 = LaunchLifecycleUtils.prepKeyFor(null, null);
        String key2 = LaunchLifecycleUtils.prepKeyFor("Project", null);
        String key3 = LaunchLifecycleUtils.prepKeyFor(null, "AppId");
        String key4 = LaunchLifecycleUtils.prepKeyFor("Project", "AppId");

        assertTrue("null/null key must start with the NUL separator", key1.startsWith("\u0000"));
        assertTrue("project/null key must start with project name", key2.startsWith("Project"));
        assertTrue("null/appId key must contain appId", key3.contains("AppId"));
        assertTrue("full key must contain both parts", key4.contains("Project") && key4.contains("AppId"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * The gate as production asks it: take the scope snapshot and read its verdict
     * for that project. There is no separate single-project predicate — one code
     * path, so a test cannot pass against a rule production does not use.
     */
    private static boolean isDirty(IProject project)
    {
        return PreLaunchChangeTracker.snapshot(Collections.singletonList(project)).isDirty(project);
    }

    /** Runs a full successful prepare cycle (snapshot then markPrepared) for the scope. */
    private static void prepare(IProject... projects)
    {
        List<IProject> scope = Arrays.asList(projects);
        PrepareSnapshot snapshot = PreLaunchChangeTracker.snapshot(scope);
        PreLaunchChangeTracker.markPrepared(scope, snapshot);
    }

    private static IProject mockProject(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        return project;
    }

    /**
     * Makes {@code project.accept(visitor, flags)} replay the given proxies as a
     * depth-first walk that HONOURS the visitor's return value: when a container
     * proxy is answered with {@code false}, every later proxy under its path is
     * skipped. Without that, a test could not tell "the folder itself contributed
     * nothing" from "its whole subtree was pruned" — which is the actual contract
     * for derived output and the Git store.
     */
    private static void stubWalk(IProject project, IResourceProxy... proxies)
    {
        try
        {
            doAnswer(invocation -> {
                IResourceProxyVisitor visitor = invocation.getArgument(0);
                List<String> pruned = new ArrayList<>();
                for (IResourceProxy proxy : Arrays.asList(proxies))
                {
                    String path = proxy.requestFullPath().toString();
                    if (pruned.stream().anyMatch(prefix -> path.startsWith(prefix + "/")))
                    {
                        continue; // inside a subtree the visitor refused to descend into
                    }
                    if (!visitor.visit(proxy))
                    {
                        pruned.add(path);
                    }
                }
                return null;
            }).when(project).accept(any(IResourceProxyVisitor.class), anyInt());
        }
        catch (org.eclipse.core.runtime.CoreException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static IResourceProxy proxy(String path, long stamp, int type, boolean derived)
    {
        IResourceProxy proxy = mock(IResourceProxy.class);
        when(proxy.getType()).thenReturn(type);
        when(proxy.isDerived()).thenReturn(derived);
        when(proxy.getModificationStamp()).thenReturn(stamp);
        when(proxy.requestFullPath()).thenReturn(new Path(path));
        when(proxy.getName()).thenReturn(new Path(path).lastSegment());
        return proxy;
    }

    /** Mocks an {@link IResourceDelta} for a FILE resource. */
    private static IResourceDelta mockFileDelta(int kind, int flags, boolean derived)
    {
        return mockResourceDelta(kind, flags, IResource.FILE, derived);
    }

    private static IResourceDelta mockResourceDelta(int kind, int flags, int resourceType,
        boolean derived)
    {
        IResource resource = mock(IResource.class);
        when(resource.getType()).thenReturn(resourceType);
        // Ancestor-aware: build output inside a derived FOLDER carries no derived
        // bit of its own, and the classifier must not read it as a source change.
        when(resource.isDerived(IResource.CHECK_ANCESTORS)).thenReturn(derived);

        IResourceDelta delta = mock(IResourceDelta.class);
        when(delta.getResource()).thenReturn(resource);
        when(delta.getKind()).thenReturn(kind);
        when(delta.getFlags()).thenReturn(flags);
        return delta;
    }

    /** In-memory {@link PreLaunchChangeTracker.FingerprintStore} standing in for the workspace metadata. */
    private static final class MapStore
        implements PreLaunchChangeTracker.FingerprintStore
    {
        final Map<String, String> values = new HashMap<>();

        @Override
        public String load(IProject project)
        {
            return values.get(project.getName());
        }

        @Override
        public void save(IProject project, String fingerprint)
        {
            values.put(project.getName(), fingerprint);
        }

        @Override
        public void clear(IProject project)
        {
            values.remove(project.getName());
        }
    }
}
