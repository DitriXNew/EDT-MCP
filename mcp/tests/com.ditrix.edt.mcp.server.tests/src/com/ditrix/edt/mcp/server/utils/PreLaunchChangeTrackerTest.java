/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for {@link PreLaunchChangeTracker}.
 *
 * <p>All tests run headless (no OSGi runtime). The listener-installation is never
 * exercised — we seed state directly via the package-visible test helpers
 * ({@link PreLaunchChangeTracker#markDirtyForTest} /
 * {@link PreLaunchChangeTracker#markPreparedForTest} /
 * {@link PreLaunchChangeTracker#resetForTest}) and drive
 * {@link PreLaunchChangeTracker#deltaMakesProjectDirty} through Mockito mocks of
 * {@link IResourceDelta} and {@link IResource}. The live workspace-listener
 * install path needs the Eclipse runtime and is an integration concern; only the
 * pure, mockable parts are asserted here.
 */
public class PreLaunchChangeTrackerTest
{
    @After
    public void tearDown()
    {
        PreLaunchChangeTracker.resetForTest();
    }

    // =========================================================================
    // isDirty — never-prepared (conservative first-launch rule)
    // =========================================================================

    @Test
    public void testNeverPreparedIsDirty()
    {
        // A project that has never been through a successful prepare is always
        // treated as dirty — conservative first-launch rule.
        IProject project = mockProject("Config");
        assertTrue("never-prepared project must be dirty", PreLaunchChangeTracker.isDirty(project));
    }

    @Test
    public void testNullProjectIsNotDirty()
    {
        assertFalse("null project must not be dirty", PreLaunchChangeTracker.isDirty(null));
    }

    // =========================================================================
    // isDirty — after markPrepared (clean baseline)
    // =========================================================================

    @Test
    public void testPreparedProjectIsClean()
    {
        IProject project = mockProject("Config");
        java.util.List<IProject> list = Collections.singletonList(project);
        Map<String, Long> snapshot = PreLaunchChangeTracker.snapshotDirty(list);
        PreLaunchChangeTracker.markPrepared(list, snapshot);
        assertFalse("a prepared project with no subsequent change must be clean",
            PreLaunchChangeTracker.isDirty(project));
    }

    @Test
    public void testMarkPreparedNullCollectionIsNoOp()
    {
        // Must not throw when null or null entries are passed.
        PreLaunchChangeTracker.markPrepared(null, null);
        PreLaunchChangeTracker.markPrepared(Collections.singletonList(null), null);
    }

    // =========================================================================
    // isDirty — after markPrepared then markDirtyForTest (file change re-dirties)
    // =========================================================================

    @Test
    public void testFileChangeAfterPrepareRedirties()
    {
        IProject project = mockProject("Config");
        java.util.List<IProject> list = Collections.singletonList(project);
        Map<String, Long> snapshot = PreLaunchChangeTracker.snapshotDirty(list);
        PreLaunchChangeTracker.markPrepared(list, snapshot);
        assertFalse("clean after prepare", PreLaunchChangeTracker.isDirty(project));

        PreLaunchChangeTracker.markDirtyForTest("Config");
        assertTrue("dirty after a file change", PreLaunchChangeTracker.isDirty(project));
    }

    @Test
    public void testMarkPreparedClearsDirtyFlag()
    {
        IProject project = mockProject("Config");
        PreLaunchChangeTracker.markDirtyForTest("Config");
        // Seed it as prepared too so it is in the prepared set (otherwise it's
        // dirty solely because it's never-prepared, not because of the dirty flag).
        PreLaunchChangeTracker.markPreparedForTest("Config");
        PreLaunchChangeTracker.markDirtyForTest("Config");
        assertTrue("dirty after explicit mark", PreLaunchChangeTracker.isDirty(project));

        java.util.List<IProject> list = Collections.singletonList(project);
        Map<String, Long> snapshot = PreLaunchChangeTracker.snapshotDirty(list);
        PreLaunchChangeTracker.markPrepared(list, snapshot);
        assertFalse("markPrepared must clear the dirty flag", PreLaunchChangeTracker.isDirty(project));
    }

    // =========================================================================
    // isDirty — multiple projects (dirty/clean partition)
    // =========================================================================

    @Test
    public void testDirtyCleanPartitionIndependent()
    {
        IProject projectA = mockProject("Config");
        IProject projectB = mockProject("Extension");

        // Prepare both first so neither is "never-prepared".
        java.util.List<IProject> both = Arrays.asList(projectA, projectB);
        Map<String, Long> snapshot = PreLaunchChangeTracker.snapshotDirty(both);
        PreLaunchChangeTracker.markPrepared(both, snapshot);
        assertFalse("Config must be clean", PreLaunchChangeTracker.isDirty(projectA));
        assertFalse("Extension must be clean", PreLaunchChangeTracker.isDirty(projectB));

        // Mark only one dirty.
        PreLaunchChangeTracker.markDirtyForTest("Extension");
        assertFalse("Config must remain clean", PreLaunchChangeTracker.isDirty(projectA));
        assertTrue("Extension must be dirty", PreLaunchChangeTracker.isDirty(projectB));
    }

    @Test
    public void testMarkPreparedPartialListLeavesOtherDirty()
    {
        IProject projectA = mockProject("Config");
        IProject projectB = mockProject("Extension");

        java.util.List<IProject> both = Arrays.asList(projectA, projectB);
        Map<String, Long> snapshotBoth = PreLaunchChangeTracker.snapshotDirty(both);
        PreLaunchChangeTracker.markPrepared(both, snapshotBoth);
        PreLaunchChangeTracker.markDirtyForTest("Config");
        PreLaunchChangeTracker.markDirtyForTest("Extension");

        // Prepare only Config.
        java.util.List<IProject> onlyA = Collections.singletonList(projectA);
        Map<String, Long> snapshotA = PreLaunchChangeTracker.snapshotDirty(onlyA);
        PreLaunchChangeTracker.markPrepared(onlyA, snapshotA);
        assertFalse("Config must be clean after its own prepare", PreLaunchChangeTracker.isDirty(projectA));
        assertTrue("Extension must still be dirty (not in the prepare call)", PreLaunchChangeTracker.isDirty(projectB));
    }

    // =========================================================================
    // Ordering-race regression (Finding 1): a new dirty event arriving DURING
    // recompute must not be silently cleared by the subsequent markPrepared.
    // =========================================================================

    @Test
    public void testDirtyEventDuringRecomputeKeepsProjectDirty()
    {
        // Setup: project has been prepared once (not never-prepared).
        IProject project = mockProject("Config");
        PreLaunchChangeTracker.markPreparedForTest("Config");

        // Step 1: project is dirty before the recompute starts.
        PreLaunchChangeTracker.markDirtyForTest("Config");
        assertTrue("project must be dirty before snapshot", PreLaunchChangeTracker.isDirty(project));

        // Step 2: snapshot is taken (simulates what recomputeAndSettleIfDirty does).
        java.util.List<IProject> scope = Collections.singletonList(project);
        Map<String, Long> snapshot = PreLaunchChangeTracker.snapshotDirty(scope);
        assertNotNull("snapshot must capture the dirty entry", snapshot.get("Config"));

        // Step 3: a NEW dirty event arrives DURING the recompute (higher generation).
        PreLaunchChangeTracker.markDirtyForTest("Config");
        Long genAfterNewChange = PreLaunchChangeTracker.getDirtyGenerationForTest("Config");
        assertNotNull("DIRTY map must still hold the new generation", genAfterNewChange);
        assertTrue("new generation must be higher than snapshot", genAfterNewChange > snapshot.get("Config"));

        // Step 4: markPrepared is called with the STALE snapshot.
        PreLaunchChangeTracker.markPrepared(scope, snapshot);

        // The project must STILL be dirty — the conditional remove must have failed
        // because the stored generation is now higher than the snapshot.
        assertTrue("project must remain dirty after a change-during-recompute", PreLaunchChangeTracker.isDirty(project));
        assertNotNull("DIRTY map entry must not have been removed", PreLaunchChangeTracker.getDirtyGenerationForTest("Config"));
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

    private static IProject mockProject(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        return project;
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
        when(resource.isDerived()).thenReturn(derived);

        IResourceDelta delta = mock(IResourceDelta.class);
        when(delta.getResource()).thenReturn(resource);
        when(delta.getKind()).thenReturn(kind);
        when(delta.getFlags()).thenReturn(flags);
        return delta;
    }
}
