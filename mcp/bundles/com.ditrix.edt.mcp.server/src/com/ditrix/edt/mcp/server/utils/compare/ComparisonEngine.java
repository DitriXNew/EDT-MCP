/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.prefs.InvalidPreferencesFormatException;

import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmTask;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.settings.model.RestoredMergeSettings;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * The ONE place in this bundle that talks to EDT's comparison engine.
 *
 * <h2>What it is for</h2>
 * EDT drives configuration comparison through {@code IComparisonManager}, an interface that can
 * both COMPARE and MERGE. This facade exposes the comparing half and never hands the manager — or
 * the {@code IComparisonSession} behind it — to a caller. That is not decoration: it is the second
 * of the three independent layers that make merging impossible here.
 * <ol>
 *   <li>{@code MANIFEST.MF} does not import {@code com._1c.g5.v8.dt.compare.merge} or
 *       {@code com._1c.g5.v8.dt.compare.git.merge}, so OSGi cannot load those classes at all — not
 *       even reflectively.</li>
 *   <li>No tool ever receives {@code IComparisonManager}. It is not even held here: the private
 *       backend below resolves it per call from a supplier {@code EdtServices} hands in, and the
 *       only field of that type in the whole bundle is that class's service tracker. Its merging
 *       entry points are not reachable through {@link Backend}, which is the only shape the rest of
 *       this class can see.</li>
 *   <li>{@code NoMergeStarterRatchetTest} fails the build if the names of those entry points appear
 *       ANYWHERE under the bundle source root — a comment counts — pins the set of files allowed to
 *       name {@code IComparisonManager} and {@code IComparisonSession}, and fails on a platform
 *       rule-setting call in ANY of them.</li>
 * </ol>
 * <p>
 * <b>This facade never writes to a comparison.</b> Every operation below either asks the engine
 * a question or governs a comparison's LIFETIME (start, prioritise, cancel, stop); none of them
 * changes what the comparison says. Merge decisions are recorded into EDT's merge-rules FILE by
 * {@code merge_rules}, and the platform re-applies that file when a comparison is launched with
 * it — so nothing here needs the session's rule-setting call, and the ratchet's allow-list for
 * that call is EMPTY rather than "this file only".
 *
 * <h2>Constraints this facade encodes (measured, not assumed)</h2>
 * <ul>
 *   <li><b>One comparison per EDT instance.</b> {@code ComparisonManager} asserts that no batch is
 *       already active, so a second launch does not queue — it fails. Callers ask
 *       {@link #hasActiveComparison()} first and refuse honestly, naming the live comparison and
 *       how to end it (see {@code ComparisonFailures}).</li>
 *   <li><b>{@link ComparisonProcessStatus} has no failure literal.</b> A failed comparison keeps
 *       reporting whatever phase it reached; the only evidence is
 *       {@code CompareMergeProcessBatch.getFailureCause()}. {@link #progress} therefore reads the
 *       failure FIRST and lets it override the status, so a poll loop that uses it cannot report a
 *       dead comparison as "still running".</li>
 *   <li><b>A status that could not be read is not a status.</b> EDT's manager answers nothing when
 *       it no longer holds the handle's session, and the read itself can throw. Either way
 *       {@link #progress} answers {@link Phase#UNKNOWN} and carries the read failure, rather than
 *       folding the absence into a phase — a caller that quotes it as a platform literal is
 *       putting words in EDT's mouth, and one that treats it as terminal kills a live
 *       comparison over a single unlucky tick.</li>
 *   <li><b>The tree is lazy.</b> Reading a node the engine has not compared yet yields an empty
 *       child list that renders as "no differences". Call {@link #prioritize} and wait on the
 *       NODE's own status ({@link ComparisonView#topNodeStatus}) before reading it.</li>
 *   <li><b>The tree is in the COMPARISON's BM store</b>, not the project's, so
 *       {@code BmTransactions.read(project, …)} is the wrong boundary (CLAUDE.md don't #1). Use
 *       {@link #read(ComparisonView, String, BmTransactions.BmOperation)}.</li>
 *   <li><b>A session is not a job.</b> Its resources — a virtual project and a private BM store —
 *       are given back only by {@link #cancel}/{@link #stop}, so {@link ComparisonSessionRegistry}
 *       owns the lifetime rather than any background-job record. The registry reclaims expired
 *       sessions from its own lookups, so every comparison-tool call sweeps and no call site has to
 *       remember to; with no comparison tool called again, the last session is released when the
 *       bundle stops ({@link #uninstall()}) and not before — there is no timer.</li>
 * </ul>
 */
public final class ComparisonEngine
{
    /**
     * The installed facade. Written only by {@link #install} / {@link #uninstall}, which
     * {@code EdtServices} calls when the bundle starts and stops.
     */
    private static final AtomicReference<ComparisonEngine> INSTANCE = new AtomicReference<>();

    /**
     * The merge-free shape of {@code IComparisonManager} that the rest of this class sees.
     * <p>
     * Package-scoped on purpose. It exists for two reasons at once: nothing outside this package
     * can name it, so no tool can be handed one; and it is a plain interface with no EDT service
     * behind it, so {@code ComparisonEngineTest} can drive the whole facade headlessly. Only the
     * comparing operations are declared here — the merging ones are simply absent, which is a
     * stronger statement than a comment saying we will not call them.
     */
    interface Backend
    {
        /**
         * @return {@code true} when EDT's comparison service is currently registered
         */
        boolean isAvailable();

        /**
         * @param batch the prepared comparison batch
         * @throws ServiceUnavailableException when EDT's comparison service is not registered, so
         *     nothing was started
         */
        void startComparison(CompareMergeProcessBatch batch);

        /**
         * @param handle the comparison to cancel
         * @throws ServiceUnavailableException when EDT's comparison service is not registered, so
         *     nothing was cancelled
         */
        void cancel(ComparisonProcessHandle handle);

        /**
         * @param handle the comparison to stop
         * @throws ServiceUnavailableException when EDT's comparison service is not registered, so
         *     nothing was stopped
         */
        void stop(ComparisonProcessHandle handle);

        /**
         * @return {@code true} when a comparison is already running in this EDT instance
         */
        boolean hasActiveComparison();

        /**
         * @param projectName the project to ask about
         * @return the handles EDT currently holds for it (never {@code null})
         */
        List<ComparisonProcessHandle> handles(String projectName);

        /**
         * @param handle the comparison
         * @return its status, or {@code null} when EDT no longer knows the handle
         */
        ComparisonProcessStatus status(ComparisonProcessHandle handle);

        /**
         * @param handle the comparison
         * @return its session, or {@code null} when EDT no longer knows the handle
         */
        IComparisonSession session(ComparisonProcessHandle handle);

        /**
         * @param handle the comparison the decisions will be restored onto
         * @param fileName the rules file to read
         * @return the restored decisions
         * @throws IOException when the file cannot be read
         * @throws InvalidPreferencesFormatException when it is not a rules file
         */
        RestoredMergeSettings restoreMergeSettings(ComparisonProcessHandle handle, String fileName)
            throws IOException, InvalidPreferencesFormatException;
    }

    /**
     * Raised when a lifetime call could NOT be performed because EDT's comparison service was not
     * registered at the moment it was attempted.
     *
     * <h2>Why a throw and not a boolean</h2>
     * This type is the fix for a defect this facade used to have. The three lifetime calls simply
     * RETURNED when the service had gone, so a caller that had checked availability a moment
     * earlier published "the comparison was started" and "the comparison was cancelled" for work
     * no platform ever saw - the failure was indistinguishable from success at the call site. A
     * {@code boolean} answer states the same fact, but it can be dropped by writing nothing, which
     * is exactly how that defect would come back; a throw cannot be ignored by omission. Both call
     * sites already own an honest state to map this onto - the shared "service unavailable"
     * refusal for a launch, and the {@code SERVICE_UNAVAILABLE} stop verdict for a cancellation -
     * so naming the failure costs them no new vocabulary.
     * <p>
     * It is deliberately NOT thrown by the reading calls: a read that cannot be made answers
     * {@code null}/empty, which every caller here already treats as "could not ask" rather than as
     * an answer.
     */
    public static final class ServiceUnavailableException
        extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;

        /**
         * @param operation what was attempted, named as the caller would name it
         */
        ServiceUnavailableException(String operation)
        {
            super("EDT's comparison service is not registered, so " + operation //$NON-NLS-1$
                + " did not reach the platform."); //$NON-NLS-1$
        }
    }

    /** Where a comparison has got to, with failure as a first-class answer. */
    public enum Phase
    {
        /** The engine is still setting the comparison up. */
        INITIALIZING,
        /** Objects are being matched and compared. */
        COMPARING,
        /** The comparison completed; the tree can be read (subtree by subtree). */
        FINISHED,
        /** Somebody cancelled it. */
        CANCELLED,
        /** It failed. {@link Progress#failure()} carries the reason. */
        FAILED,
        /**
         * The status could NOT be read this tick: either the read threw — then
         * {@link Progress#statusReadFailure()} carries what was logged — or EDT answered nothing
         * at all, which its manager does whenever it no longer holds the handle's session.
         * <p>
         * This is an ABSENCE of information, not a phase the platform reported, and the two must
         * not be confused: quoting it as a status credits EDT with saying something it never said,
         * and treating one such tick as terminal ends a comparison that is perfectly healthy. A
         * poll loop decides how many CONSECUTIVE ones it will tolerate; a single one settles
         * nothing, which is why this phase is not {@link Progress#isTerminal() terminal}.
         */
        UNKNOWN,
        /**
         * The platform reported a status this feature does not expect — every remaining literal of
         * {@link ComparisonProcessStatus} belongs to merging, which cannot happen here. Reported
         * rather than mapped onto a comparison phase, so the raw literal reaches the caller instead
         * of a guess. There is always a literal to quote here; when there is none, the phase is
         * {@link #UNKNOWN}.
         */
        UNEXPECTED
    }

    /** One reading of a running comparison: the phase, the raw status, and the failure if any. */
    public static final class Progress
    {
        private final Phase phase;
        private final ComparisonProcessStatus status;
        private final Throwable failure;
        private final Throwable statusReadFailure;

        Progress(Phase phase, ComparisonProcessStatus status, Throwable failure,
            Throwable statusReadFailure)
        {
            this.phase = phase;
            this.status = status;
            this.failure = failure;
            this.statusReadFailure = statusReadFailure;
        }

        /**
         * @return the phase, with {@link Phase#FAILED} winning over whatever the status says
         */
        public Phase phase()
        {
            return phase;
        }

        /**
         * @return the platform's own status literal, or {@code null} when it could not be read
         */
        public ComparisonProcessStatus status()
        {
            return status;
        }

        /**
         * @return the failure, non-{@code null} exactly when the phase is {@link Phase#FAILED}
         */
        public Throwable failure()
        {
            return failure;
        }

        /**
         * Why the status could not be READ — a different fact from {@link #failure()}, which is
         * the comparison's own failure. This one is the exception the status read threw, already
         * logged, and it is what a caller names instead of quoting a status it never got.
         *
         * @return the read failure, or {@code null} when the status was read, and also when EDT
         *     simply answered nothing (there is no exception to name then)
         */
        public Throwable statusReadFailure()
        {
            return statusReadFailure;
        }

        /**
         * @return {@code true} when nothing further will happen without a new request
         */
        public boolean isTerminal()
        {
            return phase == Phase.FINISHED || phase == Phase.CANCELLED || phase == Phase.FAILED;
        }
    }

    private final Backend backend;
    private final ComparisonSessionRegistry sessions;

    private ComparisonEngine(Backend backend, long idleTtlMillis)
    {
        this.backend = backend;
        this.sessions = new ComparisonSessionRegistry(System::currentTimeMillis, idleTtlMillis,
            session -> stop(session.handle()), backend::handles);
    }

    /**
     * Installs the facade over EDT's comparison service. The ONLY caller is {@code EdtServices},
     * from its tracker-opening block; there is deliberately no getter anywhere that returns the
     * manager itself.
     *
     * @param managerSupplier yields the tracked service, or {@code null} before the tracker is open
     *     and after it is closed
     */
    public static void install(Supplier<IComparisonManager> managerSupplier)
    {
        INSTANCE.set(new ComparisonEngine(managerBackend(managerSupplier),
            ComparisonSessionRegistry.DEFAULT_IDLE_TTL_MILLIS));
    }

    /**
     * The PRODUCTION backend over a caller-supplied service supplier.
     * <p>
     * Package-scoped as a test seam, and a seam onto the REAL one rather than onto a fake: "the
     * service went away between the availability check and the call" is a property of this class
     * and of nothing else, so a fake asserting it would only be testing itself.
     *
     * @param managerSupplier yields the tracked service, or {@code null} when it is not registered
     * @return the backend
     */
    static Backend managerBackend(Supplier<IComparisonManager> managerSupplier)
    {
        return new ManagerBackend(managerSupplier);
    }

    /**
     * Releases every live comparison and uninstalls the facade. The ONLY caller is
     * {@code EdtServices.dispose()}, and it must run BEFORE the service tracker is closed —
     * releasing a session needs the very service that is about to go away.
     *
     * @return how many comparisons were released
     */
    public static int uninstall()
    {
        ComparisonEngine engine = INSTANCE.getAndSet(null);
        return engine == null ? 0 : engine.sessions.releaseAll();
    }

    /**
     * @return the facade, or empty when the bundle is not started or EDT's comparison service is
     *     not registered
     */
    public static Optional<ComparisonEngine> get()
    {
        ComparisonEngine engine = INSTANCE.get();
        if (engine == null || !engine.backend.isAvailable())
        {
            return Optional.empty();
        }
        return Optional.of(engine);
    }

    /**
     * Builds a facade over a caller-supplied backend. Package-scoped: it exists so the unit tests
     * can exercise every path headlessly, and nothing outside this package can name {@link Backend}
     * to call it.
     *
     * @param backend the backend to drive
     * @param idleTtlMillis the session idle TTL
     * @return a facade that is NOT installed as the singleton
     */
    static ComparisonEngine forTesting(Backend backend, long idleTtlMillis)
    {
        return new ComparisonEngine(backend, idleTtlMillis);
    }

    /**
     * @return the registry that owns the lifetime of every comparison this server started
     */
    public ComparisonSessionRegistry sessions()
    {
        return sessions;
    }

    /**
     * The installed facade's registry, REGARDLESS of whether EDT's service is currently registered.
     * <p>
     * {@link #get()} deliberately reports "unavailable" when the service is missing, because
     * nothing useful can be asked of the platform then. A registered session, however, must stay
     * findable and releasable across such a gap - it still owns a virtual project - so
     * {@link ComparisonSessionRegistry#shared()} reaches the registry through here instead.
     *
     * @return the installed registry, or {@code null} when the bundle is not started
     */
    static ComparisonSessionRegistry installedSessions()
    {
        ComparisonEngine engine = INSTANCE.get();
        return engine == null ? null : engine.sessions;
    }

    /**
     * Whether EDT already has a comparison running. There is one slot per EDT instance: a second
     * launch FAILS, it does not queue.
     * <p>
     * This asks EDT and nothing else - in particular it does NOT reclaim expired sessions, because
     * it cannot: EDT's answer covers comparisons this server never started. A caller deciding
     * whether to refuse a launch must therefore put the registry's question
     * ({@link ComparisonSessionRegistry#activeComparisonId()}, which reclaims) FIRST, or it refuses
     * on the strength of a session the same call was entitled to release.
     *
     * @return {@code true} when a comparison is active
     */
    public boolean hasActiveComparison()
    {
        return backend.hasActiveComparison();
    }

    /**
     * @param projectName the project to ask about
     * @return the comparisons EDT currently holds for it (never {@code null})
     */
    public List<ComparisonProcessHandle> handles(String projectName)
    {
        List<ComparisonProcessHandle> found = backend.handles(projectName);
        return found == null ? Collections.emptyList() : found;
    }

    /**
     * Starts a comparison. Sweeps expired sessions first, so a forgotten one does not hold the
     * single slot against a caller who is entitled to it. A launch is the one path that reads
     * nothing before it writes, so the sweep is explicit here; every other path reaches it through
     * the registry's own lookups.
     * <p>
     * This is a COMPARISON launch and nothing else: the batch describes what to compare, and no
     * merging step is reachable from here.
     *
     * @param batch the prepared batch
     * @throws ServiceUnavailableException when EDT's comparison service is not registered, so
     *     nothing was started - a caller that swallowed this would report a comparison that does
     *     not exist
     */
    public void start(CompareMergeProcessBatch batch)
    {
        sessions.sweep();
        backend.startComparison(batch);
    }

    /**
     * @param handle the comparison
     * @return the platform's raw status, or {@code null} when EDT no longer knows the handle
     */
    public ComparisonProcessStatus status(ComparisonProcessHandle handle)
    {
        return backend.status(handle);
    }

    /**
     * The failure a batch is carrying, if any.
     * <p>
     * This is the ONLY evidence a comparison failed: {@link ComparisonProcessStatus} has no failure
     * literal, so a failed run keeps reporting the phase it died in. A poll loop that does not read
     * this on every tick reports a dead comparison as running until it times out.
     *
     * @param batch the batch that was started
     * @return the failure, or {@code null}
     */
    public Throwable failureCause(CompareMergeProcessBatch batch)
    {
        return batch == null ? null : batch.getFailureCause();
    }

    /**
     * One honest reading of a running comparison: the failure is consulted FIRST and overrides the
     * status, because the status alone can never say "failed".
     *
     * @param batch the batch that was started (may be {@code null} when the caller re-attached to a
     *     comparison it did not launch, in which case only the status is available)
     * @param handle the comparison
     * @return the phase, the raw status, and the failure if there is one
     */
    public Progress progress(CompareMergeProcessBatch batch, ComparisonProcessHandle handle)
    {
        Throwable failure = failureCause(batch);
        ComparisonProcessStatus status = null;
        RuntimeException statusReadFailure = null;
        try
        {
            status = backend.status(handle);
        }
        catch (RuntimeException e)
        {
            // "Could not ask" is not "not running", and it is not a status either: keep the
            // status null, carry WHY, and leave the decision to the caller. Inventing a phase
            // here is how one unlucky read ends a healthy comparison.
            Activator.logError("Could not read the status of a comparison", e); //$NON-NLS-1$
            statusReadFailure = e;
        }
        if (failure != null)
        {
            return new Progress(Phase.FAILED, status, failure, statusReadFailure);
        }
        return new Progress(phaseOf(status), status, null, statusReadFailure);
    }

    /**
     * A read-only window onto a live comparison.
     *
     * @param handle the comparison
     * @return the view, or {@code null} when EDT no longer knows the handle
     */
    public ComparisonView view(ComparisonProcessHandle handle)
    {
        IComparisonSession session = backend.session(handle);
        return session == null ? null : new ComparisonView(handle, session);
    }

    /**
     * Runs a task inside the comparison tree's OWN read transaction.
     * <p>
     * This is the correct boundary and {@code BmTransactions.read(project, …)} is not: the nodes
     * are objects of the comparison's private BM store, and reading them through the project's
     * store is the class of defect CLAUDE.md don't #1 names.
     *
     * @param <T> the result type
     * @param view the view to read
     * @param task the work
     * @return whatever the task returns
     */
    public <T> T read(ComparisonView view, IBmTask<T> task)
    {
        return view.session().runComparisonTreeReadonlyTask(task);
    }

    /**
     * Lambda-shaped {@link #read(ComparisonView, IBmTask)}, using the same operation shape as
     * {@link BmTransactions} so a reader moving between the two sees one idiom.
     *
     * @param <T> the result type
     * @param view the view to read
     * @param taskName a short task name for diagnostics
     * @param operation the work
     * @return whatever the operation returns
     */
    public <T> T read(ComparisonView view, String taskName, BmTransactions.BmOperation<T> operation)
    {
        return read(view, new AbstractBmTask<T>(taskName)
        {
            @Override
            public T execute(IBmTransaction tx, IProgressMonitor monitor)
            {
                return operation.execute(tx, monitor);
            }
        });
    }

    /**
     * Asks the engine to compare the named nodes next.
     * <p>
     * The tree is built lazily, and an unfinished node reads back as having no children — which
     * renders as "no differences" for a subtree nobody has looked at. Prioritise the node, then
     * wait until {@link ComparisonView#topNodeStatus} reports it FINISHED, and only then read it.
     * <p>
     * This one call needs NO read boundary, and the asymmetry is deliberate rather than an
     * oversight: prioritising only reorders the engine's own work queue and touches no model
     * object, whereas {@link ComparisonView#topNodeStatus} resolves the id against the comparison's
     * BM store and reads a feature off the node it finds - so the status read belongs inside
     * {@link #read(ComparisonView, String, BmTransactions.BmOperation)} and this one does not.
     *
     * @param view the view to prime
     * @param nodeIds the nodes to compare next
     */
    public void prioritize(ComparisonView view, List<Long> nodeIds)
    {
        if (view != null && nodeIds != null && !nodeIds.isEmpty())
        {
            view.session().prioritize(nodeIds);
        }
    }

    /**
     * Cancels a comparison and gives its virtual project and private BM store back.
     *
     * @param handle the comparison
     * @throws ServiceUnavailableException when EDT's comparison service is not registered, so the
     *     comparison was NOT cancelled and may still hold EDT's single slot
     */
    public void cancel(ComparisonProcessHandle handle)
    {
        if (handle != null)
        {
            backend.cancel(handle);
        }
    }

    /**
     * Stops a comparison and gives its virtual project and private BM store back.
     *
     * @param handle the comparison
     * @throws ServiceUnavailableException when EDT's comparison service is not registered, so the
     *     comparison was NOT stopped and its virtual project was not given back
     */
    public void stop(ComparisonProcessHandle handle)
    {
        if (handle != null)
        {
            backend.stop(handle);
        }
    }

    /**
     * Reads a merge-rules file into the decisions EDT will start the comparison with.
     * <p>
     * This is a COMPARISON-side operation despite its name: it restores what a merge WOULD do, and
     * it is what lets a caller prepare the whole decision set up front instead of answering a
     * dialog per object. It lives here because it is a call on the comparison manager, and the
     * manager is not handed to anyone.
     *
     * @param handle the comparison the decisions belong to
     * @param fileName the rules file, {@code .xml} or {@code .zip}
     * @return the restored decisions, to be handed to the process settings before the launch
     * @throws IllegalStateException when the file cannot be read or is not a rules file - the
     *     message names the file, because that is the thing the caller can fix
     */
    public RestoredMergeSettings restoreMergeSettings(ComparisonProcessHandle handle, String fileName)
    {
        try
        {
            return backend.restoreMergeSettings(handle, fileName);
        }
        catch (IOException | InvalidPreferencesFormatException e)
        {
            throw new IllegalStateException("Could not read the merge-rules file '" + fileName //$NON-NLS-1$
                + "': " + ComparisonFailures.describe(e), e); //$NON-NLS-1$
        }
    }

    private static Phase phaseOf(ComparisonProcessStatus status)
    {
        if (status == null)
        {
            // No status was READ - the read threw, or EDT answered nothing because it no longer
            // holds the handle's session. UNEXPECTED would report a platform status EDT never
            // gave, and callers refuse a comparison outright on that.
            return Phase.UNKNOWN;
        }
        switch (status)
        {
            case COMPARISON_PROCESS_INITIALIZATION_STARTED:
            case COMPARISON_PROCESS_INITIALIZATION_FINISHED:
                return Phase.INITIALIZING;
            case COMPARISON_PROCESS_TOP_OBJECTS_MATCHED:
                return Phase.COMPARING;
            case COMPARISON_PROCESS_FINISHED:
                return Phase.FINISHED;
            case COMPARISON_MERGE_PROCESS_CANCELLED:
                return Phase.CANCELLED;
            default:
                return Phase.UNEXPECTED;
        }
    }

    /**
     * The production backend: the one field in this bundle that holds EDT's comparison service.
     * <p>
     * It resolves the service on every call rather than caching it, so the facade behaves
     * correctly across an unregister/register cycle. When the service is absent — the state before
     * the bundle starts and after it stops — the two kinds of call answer differently, and the
     * asymmetry is the point: a READ answers {@code null}/empty, which its callers already read as
     * "could not ask"; a LIFETIME call throws {@link ServiceUnavailableException}, because
     * returning quietly from one would leave its caller reporting a start or a stop that never
     * happened.
     */
    private static final class ManagerBackend
        implements Backend
    {
        private final Supplier<IComparisonManager> managerSupplier;

        ManagerBackend(Supplier<IComparisonManager> managerSupplier)
        {
            this.managerSupplier = managerSupplier;
        }

        private IComparisonManager manager()
        {
            return managerSupplier == null ? null : managerSupplier.get();
        }

        @Override
        public boolean isAvailable()
        {
            return manager() != null;
        }

        @Override
        public void startComparison(CompareMergeProcessBatch batch)
        {
            // The service can disappear between the availability check the caller made and this
            // line. Returning quietly here is what let a launch report "Comparison ... started"
            // for a comparison the platform was never asked to run.
            manager("starting a comparison").startComparison(batch);
        }

        @Override
        public void cancel(ComparisonProcessHandle handle)
        {
            manager("cancelling a comparison").cancel(handle);
        }

        @Override
        public void stop(ComparisonProcessHandle handle)
        {
            manager("stopping a comparison").stop(handle);
        }

        /**
         * The service, or a failure naming what could not be done with it.
         *
         * @param operation what the caller was attempting
         * @return the registered service, never {@code null}
         * @throws ServiceUnavailableException when the service is not registered
         */
        private IComparisonManager manager(String operation)
        {
            IComparisonManager manager = manager();
            if (manager == null)
            {
                throw new ServiceUnavailableException(operation);
            }
            return manager;
        }

        @Override
        public boolean hasActiveComparison()
        {
            IComparisonManager manager = manager();
            return manager != null && manager.hasActiveComparison();
        }

        @Override
        public List<ComparisonProcessHandle> handles(String projectName)
        {
            IComparisonManager manager = manager();
            IV8Project v8Project = resolveV8Project(projectName);
            if (manager == null || v8Project == null)
            {
                return Collections.emptyList();
            }
            List<ComparisonProcessHandle> found = manager.getHandles(v8Project);
            return found == null ? Collections.emptyList() : found;
        }

        @Override
        public ComparisonProcessStatus status(ComparisonProcessHandle handle)
        {
            IComparisonManager manager = manager();
            return manager == null ? null : manager.getStatus(handle);
        }

        @Override
        public IComparisonSession session(ComparisonProcessHandle handle)
        {
            IComparisonManager manager = manager();
            return manager == null ? null : manager.getComparisonSession(handle);
        }

        @Override
        public RestoredMergeSettings restoreMergeSettings(ComparisonProcessHandle handle, String fileName)
            throws IOException, InvalidPreferencesFormatException
        {
            IComparisonManager manager = manager();
            if (manager == null)
            {
                throw new IOException("EDT's comparison service is not available"); //$NON-NLS-1$
            }
            return manager.deserializeMergeSettings(handle, fileName);
        }

        private static IV8Project resolveV8Project(String projectName)
        {
            ProjectContext context = ProjectContext.of(projectName);
            if (!context.exists())
            {
                return null;
            }
            Activator activator = Activator.getDefault();
            IV8ProjectManager projectManager = activator == null ? null : activator.getV8ProjectManager();
            return projectManager == null ? null : projectManager.getProject(context.project());
        }
    }
}
