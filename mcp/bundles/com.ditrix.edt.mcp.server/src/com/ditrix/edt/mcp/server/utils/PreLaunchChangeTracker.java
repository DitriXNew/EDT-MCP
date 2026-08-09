/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.QualifiedName;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Decides whether an EDT project still matches the content state of its last
 * successful pre-launch preparation, i.e. whether the expensive forced
 * derived-data recompute has to run again before the next launch.
 *
 * <p>Drives the selective recompute in
 * {@link LaunchLifecycleUtils#recomputeAndSettleIfDirty}: only projects whose
 * workspace content actually changed need a forced {@code recomputeAll()}; the
 * rest still get the cheap {@link BuildUtils#waitForDerivedData} pass to guard
 * against any background-derived-data work already in flight.
 *
 * <h3>Two signals, one of which outlives the plugin</h3>
 * <ol>
 *   <li><strong>Content fingerprint (persistent).</strong> A short token derived
 *       from the paths and workspace modification stamps of every non-derived
 *       file in the project. The fingerprint of the content that was prepared is
 *       written to the project as a {@linkplain IResource#setPersistentProperty
 *       persistent property}, so it survives an EDT restart, a workspace reload
 *       and a project close/open. A project whose current fingerprint equals the
 *       stored one has already been prepared at exactly this content — nothing to
 *       recompute. A project with no stored fingerprint (never prepared, or a
 *       preparation that did not complete) is dirty.</li>
 *   <li><strong>Workspace listener (session-scoped).</strong> A {@code POST_CHANGE}
 *       listener stamps a generation counter on every qualifying file change, which
 *       keeps a project dirty when a change lands <em>during</em> the recompute —
 *       a window the fingerprint alone cannot describe, because that fingerprint is
 *       taken before the recompute starts.</li>
 * </ol>
 *
 * <p>The listener is installed lazily on the first {@link #snapshot} call and lives
 * for the rest of the plugin lifetime (never removed — Eclipse will tear it down on
 * shutdown). Installation is idempotent and thread-safe.
 *
 * <h3>Why the fingerprint, and not "prepared during this session"</h3>
 * <p>The gate used to treat every project that had not been prepared <em>by this
 * plugin instance</em> as dirty. That made the signal a restart of the plugin, not
 * a change of the sources: the very first launch after EDT started always paid a
 * full {@code recomputeAll()} over the whole configuration even when the infobase
 * was demonstrably in sync. Restarting EDT says nothing about whether anybody
 * edited the sources; the fingerprint does, and it says it across restarts.
 *
 * <h3>Ordering-race handling (generation counters)</h3>
 * <p>The session-scoped dirty state is stored as a
 * {@code ConcurrentHashMap<String, Long>} mapping project name to the generation
 * number at the time of the last change. A global {@link AtomicLong} counter is
 * incremented on every qualifying file change. The conditional remove in
 * {@link #markPrepared(Collection, PrepareSnapshot)} uses
 * {@link ConcurrentHashMap#remove(Object, Object)} — which removes the entry ONLY
 * when the stored generation still equals the snapshot value — so a change that
 * arrives DURING a recompute keeps the project dirty after {@code markPrepared}
 * returns instead of being silently discarded. In that case the stored fingerprint
 * is <em>erased</em> as well, so the unfinished state cannot be certified by a
 * later session either.
 */
public final class PreLaunchChangeTracker
{
    /**
     * Per-project dirty generation. Maps project name to the generation counter
     * value at the time of the LAST qualifying file change. Absent when no change
     * was observed since the last successful prepare.
     */
    private static final ConcurrentHashMap<String, Long> DIRTY = new ConcurrentHashMap<>();

    /**
     * Global change counter. Every qualifying file change increments this and
     * stores the new value into {@link #DIRTY} for the affected project.
     */
    private static final AtomicLong GENERATION = new AtomicLong(0L);

    /** Guards the one-time listener installation. */
    private static final AtomicBoolean LISTENER_INSTALLED = new AtomicBoolean(false);

    /**
     * Key of the persistent property holding the content fingerprint of the last
     * successful pre-launch preparation of a project. Persistent properties live in
     * the workspace metadata, so the value survives an EDT restart and a project
     * close/open — which is the whole point: it lets a fresh plugin instance tell
     * "nothing changed since the last prepared run" from "we simply were not
     * running when it changed".
     */
    static final QualifiedName FINGERPRINT_PROPERTY =
        new QualifiedName(Activator.PLUGIN_ID, "prelaunch.contentFingerprint"); //$NON-NLS-1$

    /**
     * Name of the Git store, excluded from BOTH change signals. When a project's
     * own folder is the repository root, {@code .git} is part of the resource tree
     * (a folder, or a file in a worktree checkout) and every ordinary Git operation
     * rewrites files in it — which has nothing to do with the 1C sources the
     * derived data is computed from, and would otherwise make the project look
     * changed after a mere {@code git status}.
     */
    private static final String GIT_STORE_NAME = ".git"; //$NON-NLS-1$

    /** FNV-1a 64-bit offset basis. */
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;

    /** FNV-1a 64-bit prime. */
    private static final long FNV_PRIME = 0x100000001b3L;

    /**
     * Computes the content fingerprint of a project. Replaced in unit tests so the
     * gate can be exercised without a real workspace.
     */
    @FunctionalInterface
    interface ProjectFingerprinter
    {
        /**
         * @param project the project to fingerprint
         * @return an opaque token, or {@code null} when the content cannot be read
         *         (the caller then treats the project as dirty)
         */
        String fingerprintOf(IProject project);
    }

    /**
     * Reads and writes the fingerprint of the last successful preparation. The
     * production implementation is a project persistent property; unit tests swap
     * in an in-memory map that outlives {@link #simulatePluginRestartForTest} so a
     * plugin restart can be modelled honestly.
     */
    interface FingerprintStore
    {
        /** @return the stored fingerprint, or {@code null} when there is none */
        String load(IProject project);

        /** Records {@code fingerprint} as the prepared content state of {@code project}. */
        void save(IProject project, String fingerprint);

        /** Drops any stored fingerprint, so the project reads as never prepared. */
        void clear(IProject project);
    }

    /** Live fingerprinter (production: the workspace walk below). */
    private static volatile ProjectFingerprinter fingerprinter =
        PreLaunchChangeTracker::computeContentFingerprint;

    /** Live store (production: the project persistent property). */
    private static volatile FingerprintStore store = new PersistentPropertyStore();

    private PreLaunchChangeTracker()
    {
        // Utility class — do not instantiate
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Takes the pre-recompute snapshot of the whole scope: for every accessible
     * project it records the dirty decision, the content fingerprint observed
     * BEFORE the recompute, and the dirty generation (when the project carries one).
     *
     * <p>A project is judged dirty when:
     * <ul>
     *   <li>its current content fingerprint differs from the one recorded by the
     *       last successful prepare — or no fingerprint is recorded at all
     *       (never prepared, an incomplete prepare, or a project whose content
     *       cannot be read), OR</li>
     *   <li>the workspace listener observed at least one qualifying file change in
     *       it since the last successful prepare.</li>
     * </ul>
     *
     * <p>The snapshot must be taken BEFORE the recompute begins for two reasons:
     * <ul>
     *   <li>a change arriving DURING the recompute is captured by a subsequent
     *       {@code DIRTY.put} with a HIGHER generation, so the conditional
     *       {@link #markPrepared(Collection, PrepareSnapshot)} leaves that entry in
     *       place (and erases the stored fingerprint);</li>
     *   <li>the fingerprint that gets certified is the content the recompute was
     *       started on, never a later one.</li>
     * </ul>
     *
     * <p>Installs the workspace listener (this is the entry point of the pre-launch
     * chain, so the listener must be live from here on).
     *
     * @param projects scope to snapshot (may be {@code null} — returns an empty snapshot)
     * @return the snapshot to hand to {@link #markPrepared(Collection, PrepareSnapshot)}
     */
    public static PrepareSnapshot snapshot(Collection<IProject> projects)
    {
        ensureListenerInstalled();
        Map<String, Long> generations = new HashMap<>();
        Map<String, String> fingerprints = new HashMap<>();
        Set<String> dirtyNames = new HashSet<>();
        if (projects == null)
        {
            return new PrepareSnapshot(generations, fingerprints, dirtyNames);
        }
        for (IProject project : projects)
        {
            if (project == null || !project.exists() || !project.isOpen())
            {
                continue;
            }
            String name = project.getName();
            Evaluation evaluation = evaluate(project);
            fingerprints.put(name, evaluation.fingerprint);
            if (evaluation.dirty)
            {
                dirtyNames.add(name);
                Long generation = DIRTY.get(name);
                // A dirty project with no DIRTY entry is dirty by fingerprint alone;
                // the -1 sentinel records "there is nothing to conditionally remove"
                // (real generations start at 1).
                generations.put(name, generation != null ? generation : -1L);
            }
        }
        return new PrepareSnapshot(generations, fingerprints, dirtyNames);
    }

    /**
     * Marks a project dirty at a fresh generation, exactly as an observed file
     * change would. Used for a change the workspace listener cannot report — most
     * importantly a forced recompute that did NOT run (the EDT service was missing,
     * or {@code recomputeAll()} threw and was swallowed): the content is then not
     * regenerated, so it must not be certified as prepared. Because the generation
     * is newer than the one in the snapshot taken before the recompute, the
     * conditional remove in {@link #markPrepared} fails, the stored fingerprint is
     * erased, and the next launch recomputes.
     *
     * @param projectName the project to mark ({@code null} is ignored)
     */
    public static void markDirty(String projectName)
    {
        if (projectName != null)
        {
            DIRTY.put(projectName, GENERATION.incrementAndGet());
        }
    }

    /**
     * Records that the given projects completed a successful pre-launch prepare.
     *
     * <p>For each project:
     * <ul>
     *   <li>when the snapshot carried a real generation, the {@link #DIRTY} entry is
     *       removed conditionally ({@link ConcurrentHashMap#remove(Object, Object)}):
     *       if the generation already advanced (a change arrived DURING the
     *       recompute) the entry is left in place so the project stays dirty;</li>
     *   <li>the snapshot fingerprint is stored as the prepared content state — but
     *       ONLY when the project is clean afterwards. If a change landed during the
     *       recompute (or the content could not be fingerprinted at all) the stored
     *       fingerprint is ERASED instead: that content state was never fully
     *       prepared, and a later session must not read it as prepared.</li>
     * </ul>
     *
     * @param all all projects that were in scope (dirty and clean)
     * @param snapshot the snapshot returned by {@link #snapshot} before the recompute
     *            started; {@code null} certifies nothing and erases the stored
     *            fingerprints of {@code all}
     */
    public static void markPrepared(Collection<IProject> all, PrepareSnapshot snapshot)
    {
        if (all == null)
        {
            return;
        }
        for (IProject project : all)
        {
            if (project == null)
            {
                continue;
            }
            String name = project.getName();
            Long snapshotGeneration = snapshot != null ? snapshot.generations.get(name) : null;
            if (snapshotGeneration != null && snapshotGeneration >= 0L)
            {
                // Conditional remove: only succeeds when the DIRTY map still holds
                // the SAME generation (no new change arrived during the recompute).
                DIRTY.remove(name, snapshotGeneration);
            }
            String fingerprint = snapshot != null ? snapshot.fingerprints.get(name) : null;
            if (fingerprint == null || DIRTY.containsKey(name))
            {
                // Either we never managed to read the content, or the project
                // re-dirtied while it was being recomputed. Both mean "this content
                // state is not prepared" — erase, so no session certifies it.
                store.clear(project);
            }
            else
            {
                store.save(project, fingerprint);
            }
        }
    }

    /**
     * Immutable pre-recompute view of a launch scope: which projects were judged
     * dirty, the fingerprint each project had at that moment, and the dirty
     * generation to conditionally clear on success.
     */
    public static final class PrepareSnapshot
    {
        private final Map<String, Long> generations;
        private final Map<String, String> fingerprints;
        private final Set<String> dirtyNames;

        PrepareSnapshot(Map<String, Long> generations, Map<String, String> fingerprints,
                Set<String> dirtyNames)
        {
            this.generations = Collections.unmodifiableMap(generations);
            this.fingerprints = Collections.unmodifiableMap(fingerprints);
            this.dirtyNames = Collections.unmodifiableSet(dirtyNames);
        }

        /**
         * @param project a project of the snapshot scope (may be {@code null})
         * @return {@code true} when the project needs the forced recompute: it was
         *         judged dirty when the snapshot was taken, OR the workspace
         *         listener has seen a change since. The live half matters because
         *         the caller partitions the scope AFTER taking the snapshot — a
         *         change landing in between must still be recomputed by THIS
         *         launch, not deferred to the next one.
         */
        public boolean isDirty(IProject project)
        {
            if (project == null)
            {
                return false;
            }
            String name = project.getName();
            return dirtyNames.contains(name) || DIRTY.containsKey(name);
        }

    }

    // =========================================================================
    // Dirty decision
    // =========================================================================

    /** The fingerprint observed for a project together with the dirty verdict. */
    private static final class Evaluation
    {
        /** Current content fingerprint, or {@code null} when it could not be read. */
        final String fingerprint;
        /** {@code true} when the project needs the forced recompute. */
        final boolean dirty;

        Evaluation(String fingerprint, boolean dirty)
        {
            this.fingerprint = fingerprint;
            this.dirty = dirty;
        }
    }

    /**
     * Single implementation of the dirty rule, used by {@link #snapshot} for every
     * project of the scope.
     */
    private static Evaluation evaluate(IProject project)
    {
        String fingerprint = fingerprinter.fingerprintOf(project);
        String prepared = store.load(project);
        boolean contentMatchesPrepared = fingerprint != null && fingerprint.equals(prepared);
        boolean dirty = !contentMatchesPrepared || DIRTY.containsKey(project.getName());
        return new Evaluation(fingerprint, dirty);
    }

    // =========================================================================
    // Content fingerprint
    // =========================================================================

    /**
     * Computes the content fingerprint of a project: a token over the path and the
     * workspace modification stamp of every non-derived file it contains.
     *
     * <p>{@link IResource#getModificationStamp()} is a workspace-side counter that
     * changes on every modification of the resource and is written into the saved
     * workspace tree ({@code ResourceInfo.writeTo} persists {@code modStamp}), so
     * the same unchanged content yields the same token in the next EDT session.
     * Where it cannot (an unsaved workspace after a hard kill, a re-created
     * workspace) the stamps differ, the token differs, and the project is judged
     * dirty — the safe direction.
     *
     * <p>Derived resources are skipped, and a derived folder is not descended into:
     * build output is produced BY the recompute and must never be read as "the user
     * changed something". The traversal uses the proxy visitor (no {@code IResource}
     * materialisation) and combines the per-file tokens order-independently
     * (count + sum + xor), because {@code IContainer} member order is not part of
     * the API contract and must not decide whether a project looks changed.
     *
     * <p>Empty folders are invisible to this fingerprint (a folder carrying no file
     * contributes nothing); they carry no content that could reach the infobase.
     *
     * @param project the project to fingerprint (may be {@code null})
     * @return the token, or {@code null} when the project is inaccessible or the
     *         traversal failed — the caller then treats the project as dirty
     */
    static String computeContentFingerprint(IProject project)
    {
        if (project == null || !project.exists() || !project.isOpen())
        {
            return null;
        }
        long startedAt = System.currentTimeMillis();
        // [0] file count, [1] sum of file tokens, [2] xor of file tokens.
        long[] accumulator = new long[3];
        try
        {
            project.accept(proxy -> visitForFingerprint(proxy, accumulator), IResource.NONE);
        }
        catch (CoreException | RuntimeException e)
        {
            Activator.logError("Pre-launch: could not fingerprint project " //$NON-NLS-1$
                + project.getName() + " - it will be recomputed", e); //$NON-NLS-1$
            return null;
        }
        String token = combine(accumulator[0], accumulator[1], accumulator[2]);
        Activator.logInfo("Pre-launch: content fingerprint of " + project.getName() //$NON-NLS-1$
            + " = " + token + " (" + accumulator[0] + " files, " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (System.currentTimeMillis() - startedAt) + " ms)"); //$NON-NLS-1$
        return token;
    }

    /**
     * Proxy-visitor body of {@link #computeContentFingerprint}: folds one non-derived
     * file into {@code accumulator} and reports whether the traversal should descend.
     *
     * @return {@code false} for a derived resource (prunes derived subtrees), else {@code true}
     */
    private static boolean visitForFingerprint(IResourceProxy proxy, long[] accumulator)
    {
        if (proxy.isDerived() || GIT_STORE_NAME.equals(proxy.getName()))
        {
            return false;
        }
        if (proxy.getType() != IResource.FILE)
        {
            return true;
        }
        long token = fileToken(proxy.requestFullPath().toString(), proxy.getModificationStamp());
        accumulator[0]++;
        accumulator[1] += token;
        accumulator[2] ^= token;
        return true;
    }

    /**
     * FNV-1a 64 over the file path followed by the eight bytes of its modification
     * stamp. Deterministic across JVM runs (no {@code Object.hashCode}, no locale,
     * no iteration order).
     *
     * @param path workspace-absolute path of the file
     * @param modificationStamp the file's {@link IResource#getModificationStamp()}
     * @return the per-file token folded into the project fingerprint
     */
    static long fileToken(String path, long modificationStamp)
    {
        long hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < path.length(); i++)
        {
            hash = (hash ^ path.charAt(i)) * FNV_PRIME;
        }
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE)
        {
            hash = (hash ^ ((modificationStamp >>> shift) & 0xFFL)) * FNV_PRIME;
        }
        return hash;
    }

    /**
     * Folds the order-independent accumulators into the opaque token that is stored
     * and compared. Reuses {@link ContentHash} so the token has the same short,
     * comparison-only shape the rest of the plugin already uses for revision
     * tokens — {@code ContentHash} hashes a string, the walk above is what turns a
     * project into one.
     *
     * @param fileCount number of non-derived files folded in
     * @param sum sum of the per-file tokens
     * @param xor xor of the per-file tokens
     * @return the project fingerprint token
     */
    static String combine(long fileCount, long sum, long xor)
    {
        return ContentHash.of(fileCount + ":" + sum + ":" + xor); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Production {@link FingerprintStore}: the fingerprint of the prepared content
     * lives in a project persistent property, which the workspace keeps in its own
     * metadata across sessions.
     *
     * <p>Every failure is logged and swallowed: a store that cannot answer must make
     * the project look UNPREPARED (an extra recompute), never prepared.
     */
    static final class PersistentPropertyStore
        implements FingerprintStore
    {
        @Override
        public String load(IProject project)
        {
            try
            {
                return project.exists() && project.isOpen()
                    ? project.getPersistentProperty(FINGERPRINT_PROPERTY) : null;
            }
            catch (CoreException e)
            {
                Activator.logError("Pre-launch: could not read the prepared-content marker of " //$NON-NLS-1$
                    + project.getName(), e);
                return null;
            }
        }

        @Override
        public void save(IProject project, String fingerprint)
        {
            write(project, fingerprint);
        }

        @Override
        public void clear(IProject project)
        {
            write(project, null);
        }

        private static void write(IProject project, String value)
        {
            try
            {
                if (project.exists() && project.isOpen())
                {
                    project.setPersistentProperty(FINGERPRINT_PROPERTY, value);
                }
            }
            catch (CoreException e)
            {
                // Not fatal: the next launch just recomputes this project again.
                Activator.logError("Pre-launch: could not record the prepared-content marker of " //$NON-NLS-1$
                    + project.getName(), e);
            }
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
     *   <li>The resource is NOT derived, ancestors included
     *       ({@link IResource#isDerived(int)} with {@link IResource#CHECK_ANCESTORS}).
     *       Derived resources (generated files, {@code .class} files, Tycho output)
     *       are produced by the build itself and must not be treated as "user content
     *       changed". The ancestor check matters: a file inside a derived FOLDER does
     *       not carry the derived bit itself, so the plain query would classify build
     *       output the recompute just rewrote as a source change — and the project
     *       would be dirty again after every single launch.</li>
     *   <li>The resource is not inside the Git store ({@link #isInsideGitStore}).</li>
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
        if (resource.isDerived(IResource.CHECK_ANCESTORS) || isInsideGitStore(delta.getFullPath()))
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

    /**
     * @param path a workspace path (may be {@code null})
     * @return {@code true} when {@code path} is the Git store or lives inside it —
     *         see {@link #GIT_STORE_NAME}. The fingerprint walk prunes the same
     *         subtree by name, so both change signals agree on what Git owns.
     */
    static boolean isInsideGitStore(IPath path)
    {
        if (path == null)
        {
            return false;
        }
        for (int i = 0; i < path.segmentCount(); i++)
        {
            if (GIT_STORE_NAME.equals(path.segment(i)))
            {
                return true;
            }
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
     * Clears all tracking state AND restores the production fingerprinter/store.
     * Used by tests to reset the tracker between test cases without a real
     * workspace listener cycle.
     */
    static void resetForTest()
    {
        simulatePluginRestartForTest();
        fingerprinter = PreLaunchChangeTracker::computeContentFingerprint;
        store = new PersistentPropertyStore();
    }

    /**
     * Drops exactly the state a plugin restart would lose — the in-memory dirty map
     * and the generation counter — while leaving the fingerprint store (in
     * production: the workspace's own metadata) untouched. This is the in-process
     * analogue of restarting EDT, and the gate must survive it.
     */
    static void simulatePluginRestartForTest()
    {
        DIRTY.clear();
        // Reset the generation counter so each test starts from a predictable
        // baseline.  Values are always positive after the first real change
        // (incrementAndGet starts at 1), so tests that compare generation values
        // see consistent numbers.
        GENERATION.set(0L);
    }

    /** Test hook: replaces the project fingerprinter. */
    static void setFingerprinterForTest(ProjectFingerprinter testFingerprinter)
    {
        fingerprinter = testFingerprinter;
    }

    /** Test hook: replaces the prepared-content store. */
    static void setStoreForTest(FingerprintStore testStore)
    {
        store = testStore;
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
