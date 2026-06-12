/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
 */
public final class PreLaunchChangeTracker
{
    /**
     * Projects known to have had at least one qualifying file change since the
     * last successful prepare. Concurrent so listener and tool threads do not
     * need broader synchronization.
     */
    private static final Set<String> DIRTY_PROJECTS = ConcurrentHashMap.newKeySet();

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
        return !PREPARED_PROJECTS.contains(name) || DIRTY_PROJECTS.contains(name);
    }

    /**
     * Records that the given projects completed a successful pre-launch prepare.
     * Clears their dirty flags and registers them as "prepared" so future calls
     * without intervening file changes skip the expensive recompute.
     *
     * @param projects projects whose dirty flags should be cleared (may be
     *            {@code null} or contain {@code null} entries — all skipped)
     */
    public static void markPrepared(Collection<IProject> projects)
    {
        if (projects == null)
        {
            return;
        }
        for (IProject project : projects)
        {
            if (project == null)
            {
                continue;
            }
            String name = project.getName();
            PREPARED_PROJECTS.add(name);
            DIRTY_PROJECTS.remove(name);
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
        DIRTY_PROJECTS.clear();
        PREPARED_PROJECTS.clear();
    }

    /**
     * Directly marks a project dirty — allows unit tests to seed dirty state
     * without firing a real workspace delta.
     */
    static void markDirtyForTest(String projectName)
    {
        if (projectName != null)
        {
            DIRTY_PROJECTS.add(projectName);
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
            DIRTY_PROJECTS.remove(projectName);
        }
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
                            DIRTY_PROJECTS.add(project.getName());
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
