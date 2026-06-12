/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Tracks which EDT projects have had non-derived, non-marker file changes since
 * the last successful pre-launch preparation for them.
 *
 * <p>Drives the selective recompute in
 * {@link LaunchLifecycleUtils#recomputeAndSettleIfDirty}: only projects whose
 * workspace content actually changed need a forced {@code recomputeAll()}; the
 * rest still get the cheap {@link BuildUtils#waitForDerivedData} pass to guard
 * against any background-derived-data work already in flight.
 *
 * <p>The listener is installed lazily on the first {@link #isDirty} call and
 * lives for the rest of the plugin lifetime (never removed — Eclipse will tear
 * it down on shutdown). Installation is idempotent and thread-safe.
 *
 * <p>Conservative first-launch rule: a project that has never been through a
 * successful prepare is treated as dirty so the very first run always
 * force-rebuilds, matching the pre-regression behaviour.
 *
 * <h3>Ordering-race fix (generation counters)</h3>
 * <p>The dirty state is stored as a {@code ConcurrentHashMap<String, Long>}
 * mapping project name to the generation number at the time of the last change.
 * A global {@link AtomicLong} counter is incremented on every qualifying file
 * change. The conditional remove in {@link #markPrepared(Collection, Map)} uses
 * {@link ConcurrentHashMap#remove(Object, Object)} — which removes the entry
 * ONLY when the stored generation still equals the snapshot value — so a change
 * that arrives DURING a recompute keeps the project dirty after
 * {@code markPrepared} returns instead of being silently discarded.
 */
public final class PreLaunchChangeTracker
{
    /**
     * Per-project dirty generation. Maps project name to the generation counter
     * value at the time of the LAST qualifying file change. Absent when the
     * project is clean (was prepared and has had no subsequent change).
     */
    private static final ConcurrentHashMap<String, Long> DIRTY = new ConcurrentHashMap<>();

    /**
     * Global change counter. Every qualifying file change increments this and
     * stores the new value into {@link #DIRTY} for the affected project.
     */
    private static final AtomicLong GENERATION = new AtomicLong(0L);

    /**
     * Projects that have been through at least one successful prepare. A project
     * absent from this set is treated as dirty (conservative first-launch rule).
     */
    private static final Set<String> PREPARED_PROJECTS = ConcurrentHashMap.newKeySet();

    /** Guards the one-time listener installation. */
    private static final AtomicBoolean LISTENER_INSTALLED = new AtomicBoolean(false);

    private PreLaunchChangeTracker()
    {
        // Utility class — do not instantiate
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns {@code true} when {@code project} should be force-recomputed before
     * the next launch. A project is dirty when:
     * <ul>
     *   <li>it has never been through a successful prepare (conservative first-launch
     *       rule — safe even after plugin restart), OR</li>
     *   <li>the workspace listener observed at least one qualifying file change in
     *       it since the last successful prepare.</li>
     * </ul>
     *
     * <p>The listener is installed on the first call to this method. A {@code null}
     * project returns {@code false} without installing the listener.
     *
     * @param project the project to query (may be {@code null})
     * @return {@code true} if the project needs a forced recompute
     */
    public static boolean isDirty(IProject project)
    {
        if (project == null)
        {
            return false;
        }
        ensureListenerInstalled();
        String name = project.getName();
        // Never-prepared projects are always dirty (conservative).
        return !PREPARED_PROJECTS.contains(name) || DIRTY.containsKey(name);
    }

    /**
     * Takes a snapshot of the dirty-generation map for the given projects.
     * Returns a new {@code Map<String, Long>} containing (name, generation)
     * entries for every project in {@code projects} that is currently dirty
     * (present in {@link #DIRTY}). Projects that are clean (absent from
     * {@link #DIRTY}) and never-prepared projects (absent from
     * {@link #PREPARED_PROJECTS}) are both included: never-prepared entries get
     * generation {@code -1L} as a sentinel (distinct from any real counter value,
     * which starts at 1).
     *
     * <p>This snapshot must be taken BEFORE the recompute begins so that any
     * change arriving DURING the recompute is captured by a subsequent
     * {@code DIRTY.put} with a HIGHER generation; the conditional
     * {@link #markPrepared(Collection, Map)} will then leave that entry in place.
     *
     * @param projects scope to snapshot (may be {@code null} — returns empty map)
     * @return a mutable map of (name, generation) entries; empty when all
     *         projects are clean-and-prepared
     */
    public static Map<String, Long> snapshotDirty(Collection<IProject> projects)
    {
        Map<String, Long> snapshot = new HashMap<>();
        if (projects == null)
        {
            return snapshot;
        }
        for (IProject project : projects)
        {
            if (project == null)
            {
                continue;
            }
            String name = project.getName();
            Long gen = DIRTY.get(name);
            if (gen != null)
            {
                // Project is explicitly dirty: record its current generation.
                snapshot.put(name, gen);
            }
            else if (!PREPARED_PROJECTS.contains(name))
            {
                // Never-prepared: dirty by the conservative first-launch rule.
                // Sentinel generation -1 so markPrepared can add to PREPARED but
                // cannot accidentally discard a real dirty entry (those have gen >= 1).
                snapshot.put(name, -1L);
            }
        }
        return snapshot;
    }

    /**
     * Records that the given projects completed a successful pre-launch prepare.
     *
     * <p>For each project in {@code dirtySnapshot}:
     * <ul>
     *   <li>If the snapshot generation matches the CURRENT {@link #DIRTY} entry,
     *       the entry is removed (conditional {@code ConcurrentHashMap.remove}).
     *       If the generation already advanced (a change arrived DURING the
     *       recompute), the entry is left in place so the project stays dirty for
     *       the next launch.</li>
     *   <li>If the snapshot held the sentinel {@code -1L} (never-prepared), no
     *       {@code DIRTY} entry exists, so the conditional remove is a no-op.</li>
     * </ul>
     * Independently, ALL projects in {@code all} are unconditionally added to
     * {@link #PREPARED_PROJECTS} — this records the first-ever successful prepare
     * and clears the conservative never-prepared dirty rule, which is safe even
     * when the project re-dirtied during the recompute (the DIRTY entry remains
     * and {@link #isDirty} will still return {@code true} for it).
     *
     * @param all all projects that were in scope (dirty and clean); their
     *            "never-prepared" flag is cleared unconditionally
     * @param dirtySnapshot the snapshot returned by {@link #snapshotDirty} before
     *            the recompute started; may be {@code null} (no conditional removes)
     */
    public static void markPrepared(Collection<IProject> all, Map<String, Long> dirtySnapshot)
    {
        if (all != null)
        {
            for (IProject project : all)
            {
                if (project != null)
                {
                    PREPARED_PROJECTS.add(project.getName());
                }
            }
        }
        if (dirtySnapshot == null)
        {
            return;
        }
        for (Map.Entry<String, Long> entry : dirtySnapshot.entrySet())
        {
            String name = entry.getKey();
            long snapshotGen = entry.getValue();
            if (snapshotGen >= 0L)
            {
                // Conditional remove: only succeeds when the DIRTY map still holds
                // the SAME generation (no new change arrived during recompute).
                DIRTY.remove(name, snapshotGen);
            }
            // sentinel (-1L): never-prepared — no DIRTY entry to remove; PREPARED
            // already updated in the loop above.
        }
    }

    // =========================================================================
    // Delta classification (package-visible for unit tests)
    // =========================================================================

    /**
     * Decides whether a single resource delta represents a qualifying content
     * change that should mark the project dirty.
     *
     * <p>A delta qualifies when ALL of the following hold:
     * <ol>
     *   <li>The affected resource is a {@link IResource#FILE FILE} (not a folder
     *       or project node — those are container entries, not file content).</li>
     *   <li>The delta kind is {@link IResourceDelta#ADDED}, {@link IResourceDelta#REMOVED},
     *       or {@link IResourceDelta#CHANGED} with at least one of the content-carrying
     *       flags: {@link IResourceDelta#CONTENT}, {@link IResourceDelta#MOVED_FROM},
     *       {@link IResourceDelta#MOVED_TO}, {@link IResourceDelta#REPLACED}.</li>
     *   <li>The resource is NOT derived ({@link IResource#isDerived()} returns
     *       {@code false}). Derived resources (generated files, {@code .class} files,
     *       Tycho output) are produced by the build itself and must not be treated
     *       as "user content changed".</li>
     * </ol>
     *
     * <p>Marker-only deltas (flags == {@link IResourceDelta#MARKERS} only) are
     * ignored: marker changes are metadata bookkeeping and do not represent edited
     * source content.
     *
     * <p>Pure: operates entirely on the {@code IResourceDelta} / {@code IResource}
     * interface contract, with no static calls to Eclipse services, so it is
     * directly mockable in unit tests.
     *
     * @param delta a single resource delta (not a tree root — the visitor passes
     *            individual per-file or per-folder nodes)
     * @return {@code true} when the delta is a qualifying file-content change
     */
    static boolean deltaMakesProjectDirty(IResourceDelta delta)
    {
        if (delta == null)
        {
            return false;
        }
        IResource resource = delta.getResource();
        if (resource == null || resource.getType() != IResource.FILE)
        {
            return false;
        }
        if (resource.isDerived())
        {
            return false;
        }
        int kind = delta.getKind();
        if (kind == IResourceDelta.ADDED || kind == IResourceDelta.REMOVED)
        {
            return true;
        }
        if (kind == IResourceDelta.CHANGED)
        {
            int flags = delta.getFlags();
            // Ignore marker-only deltas.
            if (flags == IResourceDelta.MARKERS)
            {
                return false;
            }
            // Qualifying content-carrying flags:
            int contentFlags = IResourceDelta.CONTENT | IResourceDelta.MOVED_FROM
                | IResourceDelta.MOVED_TO | IResourceDelta.REPLACED;
            return (flags & contentFlags) != 0;
        }
        return false;
    }

    // =========================================================================
    // Listener installation
    // =========================================================================

    /**
     * Installs the workspace {@link IResourceChangeListener} exactly once.
     * Idempotent and thread-safe. The listener fires on
     * {@link IResourceChangeEvent#POST_CHANGE} and walks the delta to mark
     * affected open projects dirty.
     */
    static void ensureListenerInstalled()
    {
        if (LISTENER_INSTALLED.compareAndSet(false, true))
        {
            try
            {
                ResourcesPlugin.getWorkspace().addResourceChangeListener(
                    new ChangeListener(), IResourceChangeEvent.POST_CHANGE);
            }
            catch (IllegalStateException e)
            {
                // ResourcesPlugin not available (headless tests) — reset so a
                // future call in a real runtime can try again.
                LISTENER_INSTALLED.set(false);
            }
        }
    }

    // =========================================================================
    // Package-visible test helpers
    // =========================================================================

    /**
     * Clears all tracking state. Used by tests to reset the tracker between
     * test cases without a real workspace listener cycle.
     */
    static void resetForTest()
    {
        DIRTY.clear();
        PREPARED_PROJECTS.clear();
        // Reset the generation counter so each test starts from a predictable
        // baseline.  Values are always positive after the first real change
        // (incrementAndGet starts at 1), so tests that compare generation values
        // see consistent numbers.
        GENERATION.set(0L);
    }

    /**
     * Directly marks a project dirty — allows unit tests to seed dirty state
     * without firing a real workspace delta.
     */
    static void markDirtyForTest(String projectName)
    {
        if (projectName != null)
        {
            DIRTY.put(projectName, GENERATION.incrementAndGet());
        }
    }

    /**
     * Directly marks a project as prepared (not dirty) — allows unit tests to
     * check the clean-path without going through a full prepare cycle.
     */
    static void markPreparedForTest(String projectName)
    {
        if (projectName != null)
        {
            PREPARED_PROJECTS.add(projectName);
            DIRTY.remove(projectName);
        }
    }

    /**
     * Returns the current entry in the DIRTY map for the given project name, or
     * {@code null} when the project is clean. Package-visible so tests can assert
     * the generation counter directly.
     */
    static Long getDirtyGenerationForTest(String projectName)
    {
        return projectName != null ? DIRTY.get(projectName) : null;
    }

    // =========================================================================
    // Listener implementation
    // =========================================================================

    private static final class ChangeListener implements IResourceChangeListener
    {
        @Override
        public void resourceChanged(IResourceChangeEvent event)
        {
            if (event == null || event.getDelta() == null)
            {
                return;
            }
            try
            {
                event.getDelta().accept(delta -> {
                    IResource resource = delta.getResource();
                    if (resource == null)
                    {
                        return true; // keep walking
                    }
                    // Skip closed or non-existent projects entirely.
                    if (resource.getType() == IResource.PROJECT)
                    {
                        IProject project = (IProject) resource;
                        return project.exists() && project.isOpen();
                    }
                    if (deltaMakesProjectDirty(delta))
                    {
                        IProject project = resource.getProject();
                        if (project != null)
                        {
                            // Unconditional put-with-new-generation: a concurrent
                            // markPrepared conditional-remove on the old generation
                            // will fail and the project will remain dirty, which is
                            // exactly correct.
                            DIRTY.put(project.getName(), GENERATION.incrementAndGet());
                        }
                    }
                    return true; // keep walking children
                });
            }
            catch (CoreException e)
            {
                // Defensive: a delta-walk failure must never propagate into EDT's
                // resource notification chain.
                Activator.logError("PreLaunchChangeTracker: error walking resource delta", e); //$NON-NLS-1$
            }
        }
    }
}
