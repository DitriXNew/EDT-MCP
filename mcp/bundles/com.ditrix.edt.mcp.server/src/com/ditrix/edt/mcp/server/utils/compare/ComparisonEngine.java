/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
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
     * What EDT writes in the ancestor's place when it names a comparison that has no ancestor.
     * <p>
     * The platform's own literal, not a stand-in of ours: {@code getComparisonSessionStringId}
     * formats {@code NONE} into the third slot for a two-way comparison, so an id built any other
     * way would not be the one a saved archive was named after.
     */
    private static final String TWO_WAY_ANCESTOR_ID = "NONE"; //$NON-NLS-1$

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
         * @return whether a comparison is already running in this EDT instance, or
         *     {@link PlatformAnswer#unavailable()} when the service could not be asked
         */
        PlatformAnswer<Boolean> hasActiveComparison();

        /**
         * @param projectName the project to ask about
         * @return the handles EDT currently holds for it - possibly an EMPTY list, which is an
         *     answer - or {@link PlatformAnswer#unavailable()} when the service is not registered
         *     or the project could not be resolved, in which case nothing was asked
         */
        PlatformAnswer<List<ComparisonProcessHandle>> handles(String projectName);

        /**
         * @param handle the comparison
         * @return its status - possibly {@code null}, which is EDT's answer when it no longer
         *     holds the handle's session - or {@link PlatformAnswer#unavailable()} when the
         *     service could not be asked
         */
        PlatformAnswer<ComparisonProcessStatus> status(ComparisonProcessHandle handle);

        /**
         * @param handle the comparison
         * @return its session - possibly {@code null}, which is EDT's answer when it no longer
         *     knows the handle - or {@link PlatformAnswer#unavailable()} when the service could
         *     not be asked
         */
        PlatformAnswer<IComparisonSession> session(ComparisonProcessHandle handle);

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
     * This type is the fix for a defect this facade used to have. The lifetime calls simply
     * RETURNED when the service had gone, so a caller that had checked availability a moment
     * earlier published "the comparison was started" and "the comparison was cancelled" for work
     * no platform ever saw - the failure was indistinguishable from success at the call site. A
     * {@code boolean} answer states the same fact, but it can be dropped by writing nothing, which
     * is exactly how that defect would come back; a throw cannot be ignored by omission. Both call
     * sites already own an honest state to map this onto - the shared "service unavailable"
     * refusal for a launch, and {@link SlotHandback.Verdict#UNREACHABLE} for a hand-back, which is
     * the verdict that keeps the session registered so the attempt can be repeated - so naming the
     * failure costs them no new vocabulary.
     * <p>
     * It is deliberately NOT thrown by the reading calls: a poll loop that died on one unlucky
     * tick would end a healthy comparison. They say the same thing without throwing, through
     * {@link PlatformAnswer} - which exists because answering {@code null}/empty instead made "we
     * could not ask" indistinguishable from "we asked and there is nothing there", and a consumer
     * turned the second reading into a verdict.
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
        private final boolean statusAsked;

        Progress(Phase phase, ComparisonProcessStatus status, Throwable failure,
            Throwable statusReadFailure, boolean statusAsked)
        {
            this.phase = phase;
            this.status = status;
            this.failure = failure;
            this.statusReadFailure = statusReadFailure;
            this.statusAsked = statusAsked;
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
         * Whether the platform was reached at all for this reading.
         * <p>
         * A THIRD fact, distinct from both of the above, and the reason it is here: an unreadable
         * tick has three causes and a caller that describes it has to pick the right one. The
         * read threw ({@link #statusReadFailure()} names it); or EDT answered nothing, which its
         * manager does when it no longer holds the session; or the comparison service was not
         * registered when the question was asked, in which case EDT said nothing because nobody
         * asked it. Saying the second when the third happened credits the platform with a report
         * it never made.
         *
         * @return {@code false} when EDT's comparison service could not be asked at all
         */
        public boolean statusWasAsked()
        {
            return statusAsked;
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
            this::end, backend::handles, this::hasBegunOnPlatform, Thread::sleep);
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
     * <h2>Why the registry is CLOSED and not merely emptied</h2>
     * Clearing the singleton stops NEW work from finding the facade; it does nothing about work
     * already in flight. {@code BackgroundJobs.close()} waits two seconds and interrupts, and a
     * launch worker stuck in a git revision resolution or a project lookup goes on running with
     * the OLD engine in hand — so it can reach {@code sessions().register(...)} after this method
     * has walked the map. Emptying alone would leave that session in a registry nobody will sweep
     * again, and the comparison it is about to start would hold EDT's single slot until the JVM
     * exits under an id nothing can name. The registry therefore refuses registration from this
     * point on, and the refusal reaches the worker as a failed launch that started nothing —
     * an answer rather than a race.
     *
     * @return how many comparisons were released
     */
    public static int uninstall()
    {
        ComparisonEngine engine = INSTANCE.getAndSet(null);
        return engine == null ? 0 : engine.sessions.closeAndReleaseAll();
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
     * The installed facade, WHETHER OR NOT EDT's comparison service is registered right now.
     *
     * <h2>Why a second accessor and not a flag on the first</h2>
     * {@link #get()} answers "can anything useful be asked of the platform", and refusing on it is
     * right for a call that is about to WRITE - a launch, a hand-back - because the alternative is
     * reporting work that never reached EDT. It is wrong for a POLL. A poll that could not reach
     * the service has observed nothing, and this facade already has a way to say so
     * ({@link PlatformAnswer#unavailable()} flowing to {@link Phase#UNKNOWN}, which a loop absorbs
     * as one unreadable tick out of its budget). Going through {@code get()} skipped all of that:
     * an empty Optional arrived before any question was asked, the caller turned it into a
     * verdict, and one momentary gap ended a healthy comparison and stranded it holding EDT's
     * single slot.
     * <p>
     * So: a caller that is about to ask the platform to DO something uses {@link #get()}; a caller
     * that is READING uses this and lets the reading answer for itself. The reading calls on the
     * returned facade behave correctly with the service absent - every one of them answers
     * {@code unavailable()} rather than throwing.
     *
     * @return the installed facade, or empty only when the bundle is not started
     */
    public static Optional<ComparisonEngine> attached()
    {
        return Optional.ofNullable(INSTANCE.get());
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
     * @return whether a comparison is active, or {@link PlatformAnswer#unavailable()} when EDT's
     *     comparison service could not be asked - which is NOT the same claim as "no comparison
     *     is active", and callers must not spell it that way
     */
    public PlatformAnswer<Boolean> hasActiveComparison()
    {
        return backend.hasActiveComparison();
    }

    /**
     * The comparisons EDT currently holds for a project.
     * <p>
     * An EMPTY list and an UNAVAILABLE answer are different facts and the difference decides
     * whether a session may be reclaimed: the first says EDT has forgotten the comparison, the
     * second says this server could not reach EDT to ask. Folding them together is what let
     * {@link ComparisonSessionRegistry} drop a live session - without stopping it - during a
     * momentary service gap.
     *
     * @param projectName the project to ask about
     * @return the handles, possibly an empty list, or {@link PlatformAnswer#unavailable()}
     */
    public PlatformAnswer<List<ComparisonProcessHandle>> handles(String projectName)
    {
        PlatformAnswer<List<ComparisonProcessHandle>> found = backend.handles(projectName);
        if (found.isUnavailable())
        {
            return found;
        }
        List<ComparisonProcessHandle> handles = found.orElse(null);
        return PlatformAnswer.of(handles == null ? Collections.emptyList() : handles);
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
     * @return the platform's raw status - possibly {@code null}, which is EDT's answer when it no
     *     longer knows the handle - or {@link PlatformAnswer#unavailable()} when the service
     *     could not be asked
     */
    public PlatformAnswer<ComparisonProcessStatus> status(ComparisonProcessHandle handle)
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
        boolean statusAsked = true;
        try
        {
            PlatformAnswer<ComparisonProcessStatus> answer = backend.status(handle);
            statusAsked = answer.isAnswered();
            status = answer.orElse(null);
        }
        catch (RuntimeException e)
        {
            // "Could not ask" is not "not running", and it is not a status either: keep the
            // status null, carry WHY, and leave the decision to the caller. Inventing a phase
            // here is how one unlucky read ends a healthy comparison.
            Activator.logError("Could not read the status of a comparison", e); //$NON-NLS-1$
            statusReadFailure = e;
            statusAsked = false;
        }
        if (failure != null)
        {
            return new Progress(Phase.FAILED, status, failure, statusReadFailure, statusAsked);
        }
        return new Progress(phaseOf(status), status, null, statusReadFailure, statusAsked);
    }

    /**
     * A read-only window onto a live comparison.
     *
     * @param handle the comparison
     * @return the view - or an ANSWERED {@code null} when EDT no longer knows the handle, which
     *     is the one case a caller may report as "the comparison is gone" - or
     *     {@link PlatformAnswer#unavailable()} when the service could not be asked, which is a
     *     fact about this server's reach and not about the comparison
     */
    public PlatformAnswer<ComparisonView> view(ComparisonProcessHandle handle)
    {
        PlatformAnswer<IComparisonSession> answer = backend.session(handle);
        if (answer.isUnavailable())
        {
            return PlatformAnswer.unavailable();
        }
        IComparisonSession session = answer.orElse(null);
        return PlatformAnswer.of(session == null ? null : new ComparisonView(handle, session));
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
     * Ends one comparison and gives its virtual project and private BM store back.
     *
     * <h2>Package-scoped, and reached from exactly one caller</h2>
     * The only code that calls this is {@link ComparisonSessionRegistry}'s hand-back step, which
     * it is wired into as the registry's releaser. That is not tidiness: ending a comparison and
     * dropping its record are two halves of ONE decision, and every defect this feature has had in
     * this area came from a site performing one half and reporting the other. A tool cannot reach
     * this method - it cannot even name it - so it cannot perform half of anything.
     *
     * <h2>One platform call, not two</h2>
     * EDT offers {@code stop} and {@code cancel} and they are the same operation. Read from
     * {@code ComparisonManager} bytecode (2026.2, {@code com._1c.g5.v8.dt.compare} 29.0.0): both
     * stop the running comparison job when the batch is under active comparison, both return early
     * under an active merge - unreachable here - and both discard the session; they differ in the
     * tracing call, the telemetry string, and a status literal {@code cancel} stamps onto the
     * session it is discarding. So {@link SlotHandback.Ending} picks the name EDT records the
     * hand-back under and changes nothing else. The previous code called cancel and THEN stop, and
     * the second call always found a session the first had already discarded.
     *
     * @param session the session to end
     * @param ending which of the two verbs to use
     * @throws ServiceUnavailableException when EDT's comparison service is not registered, so the
     *     comparison was NOT ended and may still hold EDT's single slot
     */
    void end(ComparisonSessionRegistry.ComparisonSession session, SlotHandback.Ending ending)
    {
        ComparisonProcessHandle handle = session == null ? null : session.handle();
        if (handle == null)
        {
            return;
        }
        if (ending == SlotHandback.Ending.CANCELLED)
        {
            backend.cancel(handle);
        }
        else
        {
            backend.stop(handle);
        }
    }

    /**
     * Whether EDT has BEGUN a comparison, read from the status it reports for the handle.
     * <p>
     * A status is the evidence, and it is evidence of exactly the right thing. Measured from
     * {@code ComparisonManager} bytecode (EDT 2026.2, {@code com._1c.g5.v8.dt.compare} 29.0.0):
     * the session is created with a {@code null} status by the launch thread, and the FIRST status
     * on it - {@code COMPARISON_PROCESS_INITIALIZATION_STARTED} - is stamped by
     * {@code performComparisonProcess}, which nothing but the scheduled Eclipse job's own
     * {@code run} reaches. So any status at all proves the job is past the point where cancelling
     * it would skip {@code run} entirely, and no status leaves that unproven.
     * <p>
     * An UNAVAILABLE reading is passed through as unavailable rather than as "not begun": the
     * registry treats the two differently, and the missing service that produces it is the same
     * one that makes the hand-back itself fail loudly.
     *
     * @param session the session to ask about
     * @return whether the comparison is under way, or {@link PlatformAnswer#unavailable()} when
     *     EDT's comparison service could not be asked
     */
    private PlatformAnswer<Boolean> hasBegunOnPlatform(ComparisonSessionRegistry.ComparisonSession session)
    {
        ComparisonProcessHandle handle = session == null ? null : session.handle();
        if (handle == null)
        {
            return PlatformAnswer.unavailable();
        }
        PlatformAnswer<ComparisonProcessStatus> answer = backend.status(handle);
        if (answer == null || answer.isUnavailable())
        {
            return PlatformAnswer.unavailable();
        }
        return PlatformAnswer.of(Boolean.valueOf(answer.orElse(null) != null));
    }

    /**
     * Reads a merge-rules file into the decisions EDT will start the comparison with.
     * <p>
     * This is a COMPARISON-side operation despite its name: it restores what a merge WOULD do, and
     * it is what lets a caller prepare the whole decision set up front instead of answering a
     * dialog per object. It lives here because it is a call on the comparison manager, and the
     * manager is not handed to anyone.
     *
     * <h2>A zip is refused when it holds nothing for THIS comparison</h2>
     * The platform reads a zip by looking for one entry and ignoring the rest, and when no entry
     * matches it logs a warning and answers {@code null} - the comparison then starts with NO
     * decisions while the caller, who named a file, has every reason to believe theirs were
     * applied. That is a report of work that did not happen, so the file is looked at here first
     * and a zip that cannot address this comparison is refused instead. The refusal is raised
     * BEFORE {@link Backend#restoreMergeSettings} is called, so the platform is never asked and
     * the launch that would have taken EDT's single comparison slot never happens.
     * <p>
     * Only a zip is examined. An {@code .xml} file is the document itself and carries no address,
     * so there is nothing to disprove about it and its path through here is unchanged. That is a
     * statement about ADDRESSING and not about readability: EDT 2026.2 refuses an {@code .xml}
     * rules file outright ({@code Can read merge settings from a zip file}), which is a loud
     * failure from the platform itself and needs no help from here - see {@link MergeRulesCodec}.
     *
     * @param handle the comparison the decisions belong to
     * @param fileName the rules file, {@code .xml} or {@code .zip}
     * @return the restored decisions, to be handed to the process settings before the launch
     * @throws IllegalStateException when the file cannot be read, is not a rules file, or is a zip
     *     that holds no entry this comparison would restore from - the message names the file,
     *     because that is the thing the caller can fix
     */
    public RestoredMergeSettings restoreMergeSettings(ComparisonProcessHandle handle, String fileName)
    {
        String unaddressed = zipHoldsNothingFor(handle, fileName);
        if (unaddressed != null)
        {
            throw new IllegalStateException(unaddressed);
        }
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

    /**
     * The name EDT looks for inside a zipped merge-rules archive when it restores the rules of one
     * comparison.
     * <p>
     * Measured from {@code ComparisonManager.getComparisonSessionStringId} - byte for byte the
     * same method in {@code com._1c.g5.v8.dt.compare} 28.0.1 (EDT 2026.1.2) and 29.0.0 (EDT
     * 2026.2.0): {@code String.format("%s_%s_%s", main, other, ancestor)} over the three
     * descriptors' {@code getProjectName()}, with the literal {@code NONE} in the ancestor's place
     * when the comparison is not three-way. The same method names the entry the comparison editor
     * WRITES ({@code <id>.xml} inside the zip) and the entry a launch looks for, which is why a
     * launch restores exactly the settings saved under its own three names.
     *
     * <h2>The name is an ADDRESS, not an identity - the separator is not injective</h2>
     * {@code String.format("%s_%s_%s", ...)} concatenates over {@code _}, and {@code _} is a legal
     * character in an Eclipse project name, so different triples collide: {@code (A_B, C, D)} and
     * {@code (A, B_C, D)} both produce {@code A_B_C_D}. Nor is it a SET - the three names are
     * positional, so swapping main and other produces a different address. Callers may therefore
     * be told the direction that holds ("a comparison whose names spell a different string finds
     * nothing here") and must not be told the converse ("no other comparison can find this file"),
     * which this construction does not support. Widening it is not open to us: the name has to be
     * the one EDT computes, or the launch restores nothing at all.
     * <p>
     * Derived from the handle's own descriptors rather than from the tool's arguments: the project
     * names are what the platform put in the descriptors - a workspace project name on the main
     * side, a name read out of the {@code .project} file of a checked-out revision on the others -
     * and reconstructing them from a request would be guessing at the platform's answer.
     *
     * @param handle the comparison
     * @return the entry id, without an extension
     */
    public static String mergeRulesEntryId(ComparisonProcessHandle handle)
    {
        String ancestor = handle.isThreeWay()
            ? handle.getCommonAncestorDescriptor().getProjectName()
            : TWO_WAY_ANCESTOR_ID;
        return handle.getMainDescriptor().getProjectName() + "_" //$NON-NLS-1$
            + handle.getOtherDescriptor().getProjectName() + "_" + ancestor; //$NON-NLS-1$
    }

    /**
     * Whether a zipped merge-rules file carries nothing this comparison would restore from.
     *
     * <h2>Three ways to answer, and only one of them refuses</h2>
     * <ul>
     *   <li>The file is not a zip - nothing is claimed, because an xml file is the document
     *       itself and has no entry to address.</li>
     *   <li>The archive could not be opened or read - nothing is claimed either. The platform
     *       opens the SAME path with its own {@code ZipFile} and fails the launch naming the file,
     *       so a refusal invented here would put this plugin's words on a file it did not manage
     *       to look at.</li>
     *   <li>The archive was read and the entry is not in it - refused, naming the id that was
     *       looked for and what the archive holds instead.</li>
     * </ul>
     *
     * @param handle the comparison the decisions would belong to
     * @param fileName the caller's path
     * @return the refusal, or {@code null} when nothing was disproved
     */
    private static String zipHoldsNothingFor(ComparisonProcessHandle handle, String fileName)
    {
        if (handle == null || fileName == null)
        {
            return null;
        }
        Path path;
        try
        {
            path = Paths.get(fileName);
        }
        catch (InvalidPathException e)
        {
            // A spelling that is not even a path was refused by the tool long before a handle
            // existed; nothing is claimed about one that somehow arrives here.
            return null;
        }
        if (!MergeRulesCodec.isZip(path))
        {
            return null;
        }
        String entryId = mergeRulesEntryId(handle);
        MergeRulesCodec.ZipEntryLookup lookup;
        try
        {
            lookup = MergeRulesCodec.lookUpEntry(path, entryId);
        }
        catch (IOException e)
        {
            // Not read is not "does not address this comparison"; see the third case above.
            return null;
        }
        if (lookup.found())
        {
            return null;
        }
        return "The merge-rules file '" + fileName //$NON-NLS-1$
            + "' is a zip that holds nothing for THIS comparison, so starting with it would have " //$NON-NLS-1$
            + "applied none of its decisions and said nothing about it. A zip keeps one entry per " //$NON-NLS-1$
            + "PROJECT TRIPLE and EDT restores the one whose name, minus its extension, is '" //$NON-NLS-1$
            + entryId + "' - the main, other and ancestor project names of this comparison; " //$NON-NLS-1$
            + "this archive holds " + lookup.describeContents() //$NON-NLS-1$
            + ". Pass the zip that THIS comparison saved, or write the decisions with merge_rules " //$NON-NLS-1$
            + "(mode 'write' naming this comparison produces a zip whose entry is '" + entryId //$NON-NLS-1$
            + "', which is the name this launch looks for) and pass that file."; //$NON-NLS-1$
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
     * asymmetry is the point: a READ answers {@link PlatformAnswer#unavailable()}, which says "the
     * question was not asked" in a form no caller can mistake for "there is nothing there"; a
     * LIFETIME call throws {@link ServiceUnavailableException}, because returning quietly from one
     * would leave its caller reporting a start or a stop that never happened.
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
        public PlatformAnswer<Boolean> hasActiveComparison()
        {
            IComparisonManager manager = manager();
            return manager == null
                ? PlatformAnswer.unavailable()
                : PlatformAnswer.of(Boolean.valueOf(manager.hasActiveComparison()));
        }

        @Override
        public PlatformAnswer<List<ComparisonProcessHandle>> handles(String projectName)
        {
            IComparisonManager manager = manager();
            IV8Project v8Project = resolveV8Project(projectName);
            if (manager == null || v8Project == null)
            {
                // NOT an empty list. Neither the missing service nor the unresolved project is
                // evidence about the comparison, and a consumer that read the empty list as
                // "EDT has forgotten this handle" dropped a live session without stopping it.
                return PlatformAnswer.unavailable();
            }
            List<ComparisonProcessHandle> found = manager.getHandles(v8Project);
            return PlatformAnswer.of(found == null ? Collections.emptyList() : found);
        }

        @Override
        public PlatformAnswer<ComparisonProcessStatus> status(ComparisonProcessHandle handle)
        {
            IComparisonManager manager = manager();
            return manager == null
                ? PlatformAnswer.unavailable()
                : PlatformAnswer.of(manager.getStatus(handle));
        }

        @Override
        public PlatformAnswer<IComparisonSession> session(ComparisonProcessHandle handle)
        {
            IComparisonManager manager = manager();
            return manager == null
                ? PlatformAnswer.unavailable()
                : PlatformAnswer.of(manager.getComparisonSession(handle));
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
