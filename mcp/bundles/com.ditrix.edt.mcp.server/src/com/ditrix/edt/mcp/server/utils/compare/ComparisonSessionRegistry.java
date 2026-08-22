/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Owns the lifetime of every live comparison this server started, keyed by a {@code comparisonId}
 * the caller can quote back on a later request.
 *
 * <h2>Why a registry rather than the job record</h2>
 * A comparison is not a computation that ends: while it is registered EDT keeps a VIRTUAL PROJECT
 * and a private BM store alive, and only ending the comparison gives them back. The obvious place
 * to park the handle — the background job's result — cannot own that lifetime:
 * {@code BackgroundJobs} evicts the oldest completed record when the map fills up, and that
 * eviction is a bare map removal with NO dispose hook. The handle would vanish with the record and
 * the resources behind it would stay allocated until EDT is restarted. So the registry, and only
 * the registry, owns the session; a job record may quote the id but never the handle.
 *
 * <h2>Liveness is asked of EDT, not remembered</h2>
 * {@link #find(String)} does not merely look the id up: it re-asks EDT for the handles it currently
 * holds for that project ({@code IComparisonManager.getHandles}) and reports the session as GONE
 * when EDT no longer lists it. A comparison can end without going through us — EDT restarts a
 * session, a user cancels one in the UI — and a registry that trusted its own map would then hand
 * out a handle whose store is closed. {@link #activeComparisonId()} and {@link #ids()} ask the same
 * question: the ids a refusal offers the caller are the ids EDT still holds, not the ids we once
 * issued.
 *
 * <h2>"Not yet visible" is not "already gone"</h2>
 * EDT's {@code startComparison} SCHEDULES the launch, so it can return before the handle turns up
 * in {@code getHandles}. A lookup that lands in that window sees an absence that means "not yet",
 * and reading it as "gone" took the session's ownership away: the tool reported the comparison
 * cancelled while the platform went on to start it, unowned and holding EDT's single slot. So
 * absence is only believed AFTER presence: each session carries a latch
 * ({@link ComparisonSession#seenAliveByEdt}) that is raised the first time EDT lists its handle,
 * and until it is raised an absence changes nothing.
 * <p>
 * The honest boundary of that rule, stated rather than left to be discovered: a session EDT NEVER
 * lists is not reclaimed by the liveness check at all. It is reclaimed by the idle sweep below,
 * which is bounded by the TTL rather than by the next lookup. The alternative - a short grace
 * measured from registration - was rejected because it also shields a session that WAS seen and
 * then died inside the window, which is the one absence that is real.
 *
 * <h2>ONE owner of the decision to give the slot back</h2>
 * {@link #handBack(String, SlotHandback.Ending)} is the only code in this bundle that ends a
 * comparison, and {@link SlotHandback} is what it answers with. Every other path here — the idle
 * {@link #sweep()}, {@link #releaseAll()} on the way out of the bundle — goes through the same
 * private step, so all three obey one invariant:
 * <p>
 * <b>The record is dropped exactly when the slot is CONFIRMED free.</b>
 * <p>
 * Two further methods drop a record without ending anything, and both are ROLLBACKS of a launch
 * that failed rather than ways to end a comparison:
 * {@link #withdrawUnstartedLaunch(String)}, reachable only when the platform was demonstrably
 * never asked to start anything, and
 * {@link #handBackRefusedLaunch(String, SlotHandback.Ending)}, which goes through the hand-back
 * above and drops the record only on the reading where EDT itself answers that it is not running
 * the comparison. Neither weakens the invariant: both leave the named comparison holding nothing,
 * which is what {@link SlotHandback#slotIsFree()} reports for them.
 * <p>
 * A session that could not be given back therefore STAYS registered, whether the hand-back failed
 * or could not be attempted at all. That is the honest state - it may still hold the slot - and it
 * has three effects that are all wanted: a refusal can still name it, {@code releaseComparisonId}
 * can still address it, and the next sweep retries the hand-back, which is what makes a session
 * stranded by a momentary service gap reclaim itself once the service is back. The reasoning
 * behind the invariant, and everything it does NOT promise, is written down once on
 * {@link SlotHandback}.
 * <p>
 * A session EDT has accepted but NOT BEGUN is not ended either, and it is not dropped: the
 * hand-back is WITHHELD, because both of EDT's ending verbs delete the Eclipse job the launch only
 * scheduled, and the method that gives EDT's own slot back runs inside that job. That is
 * {@link SlotHandback.Verdict#NOT_STARTED_YET}, and the reasoning is written down once on the
 * verdict. {@link #handBack(String, SlotHandback.Ending)} - the path with a caller waiting on it -
 * gives the platform a bounded moment to begin before it decides, so an ordinary cancellation of a
 * fresh launch still cancels.
 * <p>
 * A session that EDT has already forgotten is dropped WITHOUT being ended: asking the platform to
 * end a handle it no longer knows is not a no-op everywhere, and there is nothing left to give
 * back. That is the {@link SlotHandback.Verdict#ALREADY_FREE} answer: this comparison holds
 * nothing, which is what lets the record go - not a reading that EDT's slot stands empty.
 *
 * <h2>Nothing has to remember to sweep</h2>
 * The TTL sweep is not a separate chore a caller may forget: {@link #find(String)},
 * {@link #activeComparisonId()} and {@link #ids()} run it FIRST, so every comparison-tool call
 * reclaims what expired before it reads anything. That ordering is load-bearing in the refusal
 * path - EDT runs one comparison per instance, so an abandoned session would otherwise block every
 * later launch for as long as EDT runs, and the refusal would name a comparison nobody is using.
 * <p>
 * The honest boundary: reclamation is driven by the next question, and there is no timer. A
 * workbench where nobody touches a comparison tool again keeps its last session until
 * {@link #releaseAll()} runs on the way out of the bundle - bounded by this server's lifetime, not
 * reclaimed earlier. That is said here rather than left to be assumed, because a reader who
 * expects a sweeper thread would be expecting something that does not exist.
 *
 * <h2>A read in flight is not idle</h2>
 * The sweep measures idleness from the last LOOKUP, and a lookup is a moment while reading a
 * comparison tree is not: walking a large configuration can outlast the TTL between one
 * {@code find} and the next, and the sweep would then end the comparison underneath the BM read
 * that is running on it. {@link #lease(String)} is how a reader says "this one is in use": while
 * a lease is open the sweep passes the session over, and closing the lease touches it, so the TTL
 * restarts from the end of the read rather than from its beginning.
 *
 * <p>Every method that touches the map does so under this object's monitor: the sweep runs from
 * whichever call happens to touch the registry next, so it races with ordinary lookups by
 * construction. The ONE deliberate exception is the wait inside
 * {@link #handBack(String, SlotHandback.Ending)}, which takes and releases the monitor per reading
 * rather than holding it - a hand-back that held it for the length of its wait would stall the poll
 * of the very launch it is waiting for.
 */
public final class ComparisonSessionRegistry
{
    /**
     * How long a comparison may sit untouched before the sweep releases it. Thirty minutes is long
     * enough for a human-paced read-expand-read loop over a large configuration and short enough
     * that a forgotten comparison does not outlive the working day.
     */
    public static final long DEFAULT_IDLE_TTL_MILLIS = 30L * 60L * 1000L;

    /**
     * How long {@link #handBack(String, SlotHandback.Ending)} gives EDT to BEGIN a comparison it
     * has accepted, before answering {@link SlotHandback.Verdict#NOT_STARTED_YET}.
     * <p>
     * The gap it covers is an Eclipse job waiting for a worker thread, which is ordinarily
     * milliseconds and on a loaded machine is seconds; ten of them is generous for that and well
     * inside the thirty seconds a committed cancellation handler is given. It is only ever spent
     * in full when the comparison really is not starting, and then the answer says so instead of
     * ending something EDT never began.
     */
    private static final long PLATFORM_START_BUDGET_MILLIS = 10_000L;

    /** How often EDT is re-asked while {@link #PLATFORM_START_BUDGET_MILLIS} runs. */
    private static final long PLATFORM_START_POLL_MILLIS = 50L;

    /**
     * How long a {@link SlotClaim} may stand before the slot is taken back from it.
     * <p>
     * A claim is held for exactly as long as one launch spends preparing - resolving two git
     * revisions, looking the project up, building the batch - and it is given up either way at the
     * end of that. This budget is the backstop for the launch that never reaches either end: a job
     * thread killed outright, or a bundle torn down under it. Five minutes is far longer than the
     * preparation takes even on a large repository, and far shorter than the idle TTL a stranded
     * claim would otherwise borrow - which is what makes it a backstop rather than a deadline the
     * work has to beat.
     */
    private static final long CLAIM_BUDGET_MILLIS = 5L * 60L * 1000L;

    /**
     * Hands every registry instance a token no other instance in this JVM will use, seeded from
     * the wall clock so that a LATER JVM does not reissue an earlier one's tokens.
     * <p>
     * An id leaves this server and comes back on a later request, and it outlives the registry
     * that issued it: a client keeps the {@code comparisonId} from a finished job, the bundle is
     * reinstalled or EDT is restarted, and the id is quoted at a registry that started counting
     * from one again. With a plain counter that id addressed a DIFFERENT comparison - the caller
     * released, or read the tree of, something it had never heard of. The token makes the collision
     * unrepresentable within a JVM, because the counter only ever moves forward; across JVMs it
     * rests on the clock having advanced further than the number of registries the previous one
     * created, which for an OSGi bundle that installs one registry per start it always has.
     */
    private static final AtomicLong INSTANCE_TOKENS = new AtomicLong(System.currentTimeMillis());

    /** Ends one comparison on the platform, giving its virtual project and BM store back. */
    @FunctionalInterface
    interface Releaser
    {
        /**
         * @param session the session being ended
         * @param ending which of EDT's two hand-back verbs to use - they are the same operation,
         *     see {@link SlotHandback.Ending}
         */
        void release(ComparisonSession session, SlotHandback.Ending ending);
    }

    /** Asks EDT which comparisons it currently holds for a project. */
    @FunctionalInterface
    interface LiveHandles
    {
        /**
         * @param projectName the project the comparison was started for
         * @return the handles EDT lists right now - possibly an EMPTY list, which is an answer -
         *     or {@link PlatformAnswer#unavailable()} when EDT could not be asked at all, which
         *     is not
         */
        PlatformAnswer<List<ComparisonProcessHandle>> forProject(String projectName);
    }

    /**
     * Asks EDT whether it has BEGUN a comparison, as opposed to merely accepting it.
     * <p>
     * The two are days apart in consequence and milliseconds apart in time, which is why this is a
     * question of its own rather than a shade of {@link LiveHandles}: EDT lists a handle from the
     * moment the launch thread registers its session, and only starts comparing when the Eclipse
     * job it scheduled gets a worker. See {@link SlotHandback.Verdict#NOT_STARTED_YET} for what
     * ending a comparison in between costs.
     */
    @FunctionalInterface
    interface LaunchProgress
    {
        /**
         * @param session the session to ask about
         * @return {@code TRUE} once EDT reports that the comparison is under way, {@code FALSE}
         *     when EDT answers and reports that it is not, or {@link PlatformAnswer#unavailable()}
         *     when the question could not be asked at all - which is not the same statement
         */
        PlatformAnswer<Boolean> hasBegun(ComparisonSession session);
    }

    /**
     * Waits, so that {@link #handBack(String, SlotHandback.Ending)} can give EDT the moment it
     * needs to begin a comparison somebody has just asked to end. Injected so the wait is
     * exercised by the tests without them sleeping through it.
     */
    @FunctionalInterface
    interface Pause
    {
        /**
         * @param millis how long to wait
         * @throws InterruptedException when the waiting thread is interrupted
         */
        void millis(long millis) throws InterruptedException;
    }

    /**
     * What EDT says about a handle it was given, as THREE answers rather than two.
     * <p>
     * The middle one is the whole reason this is not a boolean: "could not ask" is a fact about
     * this server's reach and it used to arrive folded into "not there", which is a fact about the
     * comparison. Every one of the six findings this construction closes was some site acting on
     * that fold.
     */
    private enum Liveness
    {
        /** EDT lists the handle, or has never listed it and the launch has not surfaced yet. */
        HELD,
        /** EDT listed the handle once and does not any more. */
        GONE,
        /**
         * The question could not be asked, or asking it threw. Nothing was established - in
         * particular this is NOT {@link #GONE}, so no session is ever dropped on it. A hand-back
         * is still ATTEMPTED on this reading: the question also goes unanswered when the project
         * fails to resolve, and only {@link #GONE} is evidence that there is nothing to end.
         */
        UNKNOWN
    }

    /** One registered comparison. */
    public static final class ComparisonSession
    {
        private final String comparisonId;
        private final String projectName;
        private final ComparisonProcessHandle handle;
        private final CompareMergeProcessBatch batch;
        private final long startedAtMillis;
        private long lastTouchedMillis;
        /**
         * How many reads are running on this session right now. The idle sweep skips a session
         * with an open lease: a tree walk is use, and measuring idleness from the lookup that
         * STARTED it would reclaim a comparison out from under the BM read on it.
         */
        private int leases;
        /**
         * Whether EDT has ever listed this handle. Until it has, an absence from
         * {@code getHandles} is "the scheduled launch has not surfaced yet" and not "the
         * comparison is gone" — see the class javadoc.
         */
        private boolean seenAliveByEdt;

        ComparisonSession(String comparisonId, String projectName, ComparisonProcessHandle handle,
            CompareMergeProcessBatch batch, long nowMillis)
        {
            this.comparisonId = comparisonId;
            this.projectName = projectName;
            this.handle = handle;
            this.batch = batch;
            this.startedAtMillis = nowMillis;
            this.lastTouchedMillis = nowMillis;
        }

        /**
         * @return the id this server issued for the comparison
         */
        public String comparisonId()
        {
            return comparisonId;
        }

        /**
         * @return the project the comparison was started for
         */
        public String projectName()
        {
            return projectName;
        }

        /**
         * @return EDT's handle for the comparison
         */
        public ComparisonProcessHandle handle()
        {
            return handle;
        }

        /**
         * The batch the comparison was launched with. It is kept because it is the ONLY carrier of
         * a failure: {@code ComparisonProcessStatus} has no failure literal, so a poll that lost
         * the batch could never tell a dead comparison from a running one.
         *
         * @return the batch, or {@code null} when the caller re-attached to a comparison it did not
         *     launch
         */
        public CompareMergeProcessBatch batch()
        {
            return batch;
        }

        /**
         * @return when the comparison was registered
         */
        public long startedAtMillis()
        {
            return startedAtMillis;
        }

        /**
         * @return when the comparison was last looked up
         */
        public long lastTouchedMillis()
        {
            return lastTouchedMillis;
        }

        /**
         * Whether EDT has EVER listed this handle.
         * <p>
         * Exposed because it is the only authority on "the scheduled launch has not surfaced yet",
         * and a poll loop has to tell that apart from "EDT will not answer for this comparison".
         * {@code startComparison} SCHEDULES the launch, so between it and the first listing EDT
         * answers no status at all - readings that look exactly like an unreadable comparison. A
         * loop that spends its unreadable-tick budget on them cancels a correctly queued
         * comparison whenever Eclipse's scheduler is busy for a few seconds.
         *
         * @return {@code true} once EDT has listed the handle at least once
         */
        public boolean seenAliveByEdt()
        {
            return seenAliveByEdt;
        }
    }

    /**
     * A read in progress on one comparison: while it is open the idle sweep leaves that session
     * alone, and closing it restarts the TTL from the end of the read.
     * <p>
     * It carries the handle it leased rather than making the caller look one up again. That is not
     * convenience: a second lookup is a second liveness question, and the two can disagree - which
     * is exactly how a tool once reported a cancellation the platform never performed. The handle
     * is valid for as long as the lease is open and no longer.
     * <p>
     * A lease deliberately does NOT protect against a caller who ASKS for the comparison to end:
     * {@code releaseComparisonId} and {@code cancel_job} still take effect, and a read that dies
     * because of one fails with the platform's own message. Guarding is for the unattended path.
     */
    public static final class Lease
        implements AutoCloseable
    {
        private final ComparisonSessionRegistry registry;
        private final ComparisonSession session;
        private boolean open;

        Lease(ComparisonSessionRegistry registry, ComparisonSession session)
        {
            this.registry = registry;
            this.session = session;
            this.open = session != null;
        }

        /**
         * @return {@code true} when a live session was found and is now held against the sweep
         */
        public boolean held()
        {
            return session != null;
        }

        /**
         * @return the leased comparison's handle, or {@code null} when nothing was leased
         */
        public ComparisonProcessHandle handle()
        {
            return session == null ? null : session.handle();
        }

        /**
         * @return the leased comparison's id, or {@code null} when nothing was leased
         */
        public String comparisonId()
        {
            return session == null ? null : session.comparisonId();
        }

        /**
         * Ends the lease. Idempotent, so a try-with-resources that also closes explicitly cannot
         * decrement the count twice and expose a live read to the sweep.
         */
        @Override
        public void close()
        {
            if (open)
            {
                open = false;
                registry.endLease(session);
            }
        }
    }

    /**
     * The stand-in returned by {@link #shared()} when no facade is installed - before the bundle
     * starts and after it stops. It answers every LOOKUP with "nothing" and ends nothing, both of
     * which are true, and it REFUSES to register: a session recorded here would be owned by nobody
     * and would leak the comparison it names.
     */
    private static final ComparisonSessionRegistry DETACHED = new ComparisonSessionRegistry(
        System::currentTimeMillis, DEFAULT_IDLE_TTL_MILLIS, (session, ending) -> {
            // nothing to end: nothing can be registered here
        }, projectName -> PlatformAnswer.of(Collections.emptyList()),
        session -> PlatformAnswer.unavailable(), millis -> {
            // nothing can be registered here, so nothing is ever waited for
        }, false);

    private final Map<String, ComparisonSession> sessions = new LinkedHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);
    private final String instanceToken = Long.toString(INSTANCE_TOKENS.getAndIncrement(), Character.MAX_RADIX);
    private final LongSupplier clock;
    private final long idleTtlMillis;
    private final Releaser releaser;
    private final LiveHandles liveHandles;
    private final LaunchProgress launchProgress;
    private final Pause pause;
    private final boolean attached;

    /**
     * The id claimed by a launch that is preparing but has not registered its session yet, or
     * {@code null} when nobody is preparing one. See {@link SlotClaim}.
     */
    private String claimedComparisonId;

    /** The project the standing claim was taken for, for the refusal a second launch gets. */
    private String claimedProjectName;

    /** When the standing claim was taken, measured by {@link #clock}. */
    private long claimedAtMillis;

    /** Set once by {@link #closeAndReleaseAll()}; from then on nothing may be registered. */
    private boolean closed;

    /**
     * @param clock the millisecond clock (injected so the TTL is testable without sleeping)
     * @param idleTtlMillis how long a session may sit untouched
     * @param releaser ends one comparison on the platform
     * @param liveHandles asks EDT what it currently holds
     * @param launchProgress asks EDT whether it has BEGUN a comparison, not merely accepted it
     * @param pause how {@link #handBack(String, SlotHandback.Ending)} waits between two of those
     *     questions
     */
    ComparisonSessionRegistry(LongSupplier clock, long idleTtlMillis, Releaser releaser,
        LiveHandles liveHandles, LaunchProgress launchProgress, Pause pause)
    {
        this(clock, idleTtlMillis, releaser, liveHandles, launchProgress, pause, true);
    }

    private ComparisonSessionRegistry(LongSupplier clock, long idleTtlMillis, Releaser releaser,
        LiveHandles liveHandles, LaunchProgress launchProgress, Pause pause, boolean attached)
    {
        this.clock = clock;
        this.idleTtlMillis = idleTtlMillis;
        this.releaser = releaser;
        this.liveHandles = liveHandles;
        this.launchProgress = launchProgress;
        this.pause = pause;
        this.attached = attached;
    }

    /**
     * The registry of the installed facade.
     * <p>
     * There is one per {@link ComparisonEngine}, and one engine per bundle, so this is the registry
     * - not merely a registry. It is reached through the facade's INSTALLED instance rather than
     * through {@link ComparisonEngine#get()}, because {@code get()} also reports "unavailable"
     * while EDT's service is momentarily unregistered, and a live session must stay findable and
     * givable-back across such a gap.
     *
     * @return the installed registry, or a detached stand-in that finds nothing and refuses to
     *     register anything when the bundle is not started
     */
    public static ComparisonSessionRegistry shared()
    {
        ComparisonSessionRegistry installed = ComparisonEngine.installedSessions();
        return installed == null ? DETACHED : installed;
    }

    /**
     * Claims EDT's single comparison slot for a launch that is about to PREPARE one, in one
     * indivisible step.
     *
     * <h2>Why this exists, and why it is taken first</h2>
     * A launch used to ask {@link #activeComparisonId()} and then register its session at the far
     * end of its preparation - two git revisions resolved, the project looked up, the batch built.
     * The whole of that ran between the question and the answer being acted on, so two launches
     * arriving together both read "nothing is running", both prepared, and both registered. EDT
     * refused the second batch, but its registration stood, and this registry's own refusals are
     * computed from registrations: the slot was recorded as taken by a comparison that had never
     * started, and every later launch was refused until the idle TTL expired.
     * <p>
     * Claiming stakes the INTENT under this object's monitor, before any of that work. Of two
     * launches exactly one is granted, and the loser is refused with a sentence rather than
     * discovering the conflict from the platform a minute of git later.
     *
     * <h2>What a claim costs, and what it does not</h2>
     * A claim is given up either way - {@link #adoptClaim} turns it into the session, in the same
     * step as the registration, and {@link #withdrawClaim} drops it when the preparation failed.
     * Withdrawing touches ONLY the claim: it cannot drop the record of a comparison the platform
     * may be running, because it does not look at the session map at all. The rule that a record
     * survives whenever this server does not know what became of the comparison lives in
     * {@link #handBack(String, SlotHandback.Ending)} and is untouched here.
     * <p>
     * A claim that neither happens to is reclaimed after {@link #CLAIM_BUDGET_MILLIS} - the
     * backstop for a launch thread that died between the two.
     *
     * @param projectName the project the launch is being prepared for, for the refusal a second
     *     launch is given (may be {@code null})
     * @return a granted claim carrying the id the launch must register under, or a refused one
     *     carrying the owner's own sentence about what holds the slot
     */
    public synchronized SlotClaim claimSlot(String projectName)
    {
        if (!attached)
        {
            return SlotClaim.refused(ComparisonFailures.serviceUnavailable());
        }
        if (closed)
        {
            return SlotClaim.refused(ToolResult.error("This server's comparison support has " //$NON-NLS-1$
                + "been shut down, so a comparison cannot be started - nothing would own it and " //$NON-NLS-1$
                + "its virtual project would outlive the server. Nothing was started. Restart " //$NON-NLS-1$
                + "EDT and try again.")); //$NON-NLS-1$
        }
        expireStaleClaim();
        if (claimedComparisonId != null)
        {
            return SlotClaim.refused(ComparisonFailures.launchInFlight(claimedProjectName));
        }
        // Asked here and not before: this reclaims every session past its TTL, so a slot held by
        // an abandoned comparison is given back to the caller entitled to it in the same
        // indivisible step that hands out the claim.
        String active = activeComparisonId();
        if (active != null)
        {
            return SlotClaim.refused(ComparisonFailures.alreadyRunning(active));
        }
        claimedComparisonId = nextComparisonId();
        claimedProjectName = projectName;
        claimedAtMillis = clock.getAsLong();
        return SlotClaim.granted(claimedComparisonId);
    }

    /**
     * Turns a standing claim into the registration for the comparison about to be handed to EDT,
     * keeping the id the claim was granted under.
     *
     * @param comparisonId the id from {@link SlotClaim#comparisonId()}
     * @param handle EDT's handle
     * @param batch the batch the comparison is launched with
     * @return the same id, now a registered session
     * @throws IllegalStateException when the facade is gone, the registry is closed, or the claim
     *     is no longer standing - all three mean nothing may be started under this id, and this is
     *     called before the batch leaves this process
     */
    public synchronized String adoptClaim(String comparisonId, ComparisonProcessHandle handle,
        CompareMergeProcessBatch batch)
    {
        requireRegistrable();
        if (comparisonId == null || !comparisonId.equals(claimedComparisonId))
        {
            // Reachable two ways, and neither may become a session: the claim was withdrawn by the
            // launch's own failure path, or it outlived CLAIM_BUDGET_MILLIS and the slot went to
            // somebody else. Registering anyway would put two sessions in a one-comparison
            // registry, which is the state the claim exists to make unrepresentable.
            throw new IllegalStateException("The comparison was not started: this launch no " //$NON-NLS-1$
                + "longer holds EDT's single comparison slot, so nothing was handed to the " //$NON-NLS-1$
                + "platform. Start compare_configurations again."); //$NON-NLS-1$
        }
        claimedComparisonId = null;
        claimedProjectName = null;
        return put(comparisonId, handle, batch);
    }

    /**
     * Gives up a claim whose launch never reached the platform.
     * <p>
     * It touches the CLAIM and nothing else. A claim names no handle and no comparison EDT knows
     * about, so dropping one cannot lose a comparison that may be running - the case
     * {@link #handBack(String, SlotHandback.Ending)} keeps a record for. Once the claim has been
     * adopted this does nothing at all, which is what makes it safe to call from a {@code finally}
     * that cannot know how far the launch got.
     *
     * @param comparisonId the id from {@link SlotClaim#comparisonId()} (may be {@code null})
     * @return {@code true} when this call withdrew the claim, {@code false} when there was no such
     *     claim to withdraw - because it was adopted, was already withdrawn, or had expired
     */
    public synchronized boolean withdrawClaim(String comparisonId)
    {
        if (comparisonId == null || !comparisonId.equals(claimedComparisonId))
        {
            return false;
        }
        claimedComparisonId = null;
        claimedProjectName = null;
        return true;
    }

    /**
     * Drops a claim whose launch never came back for it.
     * <p>
     * Called from {@link #claimSlot(String)} alone, because that is the only answer a stale claim
     * can distort: everything else in this registry reasons about SESSIONS, and a claim is not one.
     */
    private void expireStaleClaim()
    {
        if (claimedComparisonId != null
            && clock.getAsLong() - claimedAtMillis >= CLAIM_BUDGET_MILLIS)
        {
            claimedComparisonId = null;
            claimedProjectName = null;
        }
    }

    /**
     * Registers a comparison that is about to be started.
     * <p>
     * The project name is taken from the handle's MAIN descriptor rather than passed in: that is
     * the project EDT keys its own {@code getHandles} answer on, so deriving it here removes the
     * chance of registering a session under a name the liveness check can never match.
     *
     * @param handle EDT's handle
     * @param batch the batch the comparison is launched with (may be {@code null} when
     *     re-attaching to a comparison this server did not launch)
     * @return the id the caller quotes on later requests
     */
    public synchronized String register(ComparisonProcessHandle handle, CompareMergeProcessBatch batch)
    {
        requireRegistrable();
        return put(nextComparisonId(), handle, batch);
    }

    /**
     * The two reasons nothing may be registered at all, asked in one place so a claim adopted and
     * a session registered cannot answer them differently.
     *
     * @throws IllegalStateException when the facade is not installed or the registry is closed
     */
    private void requireRegistrable()
    {
        if (!attached)
        {
            throw new IllegalStateException("No comparison facade is installed, so a comparison " //$NON-NLS-1$
                + "cannot be registered - it would be owned by nobody and would leak its virtual " //$NON-NLS-1$
                + "project. Check ComparisonEngine.get() before starting one."); //$NON-NLS-1$
        }
        if (closed)
        {
            // Refused by the registry itself rather than prevented by timing. The bundle's
            // shutdown releases the sessions it can SEE, and a worker still holding the old
            // facade - stuck resolving a revision while the executor's two-second grace ran out -
            // reaches this line afterwards. Its session would be registered in a registry nobody
            // will sweep again, so the comparison it is about to start would hold EDT's single
            // slot until the JVM exits with no id able to name it.
            throw new IllegalStateException("This server's comparison support has been shut " //$NON-NLS-1$
                + "down, so a comparison cannot be registered - nothing would own it and its " //$NON-NLS-1$
                + "virtual project would outlive the server. Nothing was started."); //$NON-NLS-1$
        }
    }

    /**
     * @return the next id, minted for a claim or for a registration - never for both, so no id
     *     this registry has issued is ever issued a second time
     */
    private String nextComparisonId()
    {
        // Instance token FIRST, so the varying part a human tracks across a report stays at the
        // end and the ids of one session share a prefix. See INSTANCE_TOKENS for what the token
        // rules out.
        return "cmp-" + instanceToken + '-' + idGenerator.getAndIncrement(); //$NON-NLS-1$
    }

    /**
     * Puts one session in the map under an id already minted.
     *
     * @param comparisonId the id
     * @param handle EDT's handle
     * @param batch the batch
     * @return the id
     */
    private String put(String comparisonId, ComparisonProcessHandle handle,
        CompareMergeProcessBatch batch)
    {
        String projectName = handle == null || handle.getMainDescriptor() == null
            ? null
            : handle.getMainDescriptor().getProjectName();
        sessions.put(comparisonId,
            new ComparisonSession(comparisonId, projectName, handle, batch, clock.getAsLong()));
        return comparisonId;
    }

    /**
     * @param comparisonId the id issued by {@link #register}
     * @return the handle, or {@code null} when the id is unknown or EDT no longer holds it
     */
    public ComparisonProcessHandle handle(String comparisonId)
    {
        return find(comparisonId).map(ComparisonSession::handle).orElse(null);
    }

    /**
     * @param comparisonId the id issued by {@link #register}
     * @return the batch, or {@code null} when the id is unknown, EDT no longer holds it, or the
     *     session was re-attached rather than launched here
     */
    public CompareMergeProcessBatch batch(String comparisonId)
    {
        return find(comparisonId).map(ComparisonSession::batch).orElse(null);
    }

    /**
     * The comparison holding EDT's single slot, as far as this server knows.
     * <p>
     * EDT runs one comparison per instance, so a refusal has to be able to name the one in the way.
     * A {@code null} answer while EDT reports a comparison active is itself information: the
     * comparison was started outside this server (from the workbench, say) and only EDT can end it.
     *
     * @return the id of the most recently registered comparison EDT still holds, or {@code null}
     */
    public synchronized String activeComparisonId()
    {
        // Reclaim first. This answer decides whether a launch is refused, and a session that sat
        // idle past its TTL must not hold EDT's single slot against a caller entitled to it.
        sweepExpired();
        String active = null;
        for (ComparisonSession session : new ArrayList<>(sessions.values()))
        {
            if (liveness(session) == Liveness.GONE)
            {
                sessions.remove(session.comparisonId());
            }
            else
            {
                // Including a session EDT has not listed yet: its launch is scheduled, so it holds
                // the slot as surely as a running one, and a launch refused on its account is
                // refused correctly.
                active = session.comparisonId();
            }
        }
        return active;
    }

    /**
     * Looks a comparison up and, in the same breath, re-checks with EDT that it is still live.
     *
     * @param comparisonId the id issued by {@link #register}
     * @return the session, or empty when the id is unknown OR EDT no longer holds the handle
     */
    public synchronized Optional<ComparisonSession> find(String comparisonId)
    {
        // Reclaim BEFORE the lookup, never after: a session already past its TTL when the call
        // arrived is expired, and touching it first would let a late arrival revive it for another
        // full TTL - and would let a lookup of some OTHER id leave it pinned.
        sweepExpired();
        ComparisonSession session = comparisonId == null ? null : sessions.get(comparisonId);
        if (session == null)
        {
            return Optional.empty();
        }
        if (liveness(session) == Liveness.GONE)
        {
            // Gone on EDT's side, and gone means it was HERE first: drop the record, do NOT
            // ask the platform to end a handle it has already forgotten.
            sessions.remove(comparisonId);
            return Optional.empty();
        }
        session.lastTouchedMillis = clock.getAsLong();
        return Optional.of(session);
    }

    /**
     * Takes a session out of the idle sweep's reach for the length of a read.
     * <p>
     * Looking the session up is part of the lease and not a step before it: a caller that resolved
     * the handle first and leased afterwards would be asking the liveness question twice, and the
     * two answers can differ. {@link Lease#handle()} carries the handle that was leased.
     *
     * @param comparisonId the id issued by {@link #register}
     * @return an open lease when the comparison is live, otherwise a lease that holds nothing;
     *     always close it, and {@link Lease#held()} says which one you got
     */
    public synchronized Lease lease(String comparisonId)
    {
        ComparisonSession session = find(comparisonId).orElse(null);
        if (session != null)
        {
            session.leases++;
        }
        return new Lease(this, session);
    }

    /**
     * @return every registered session, oldest first (a snapshot, safe to iterate)
     */
    public synchronized List<ComparisonSession> list()
    {
        return new ArrayList<>(sessions.values());
    }

    /**
     * @return the number of registered sessions
     */
    public synchronized int size()
    {
        return sessions.size();
    }

    /**
     * Ends one comparison, gives EDT's single slot back, and says what that actually achieved.
     * <p>
     * <b>The one owner.</b> Nothing else in this bundle ends a comparison: the platform's two
     * lifetime verbs are package-scoped on {@link ComparisonEngine} and the session map is private
     * here, so a caller has no other door. The two other methods that drop a record -
     * {@link #withdrawUnstartedLaunch(String)} and
     * {@link #handBackRefusedLaunch(String, SlotHandback.Ending)} - are both rollbacks of a launch
     * that failed: the first ends nothing, because it is only reachable when the platform was
     * demonstrably never asked to start anything; the second goes through this one and drops the
     * record only on the reading where EDT itself answers that it is not running the comparison.
     * <p>
     * A caller gets a {@link SlotHandback} back and publishes {@link SlotHandback#sentence()}; it
     * does not decide what happened, and it cannot lose a failure by writing nothing, because the
     * failure is inside the sentence it prints.
     * <p>
     * The record is dropped exactly when the slot is confirmed free - see the class javadoc for
     * the invariant and {@link SlotHandback} for what it does not promise.
     *
     * <b>Somebody asked, so EDT is given time to begin.</b> This is the one hand-back path with a
     * caller waiting on it, and the one where the comparison it names may have been launched
     * milliseconds ago - {@code cancel_job} on a fresh launch is exactly that. A comparison EDT has
     * accepted but not begun cannot be ended safely
     * ({@link SlotHandback.Verdict#NOT_STARTED_YET}), so rather than refuse a cancellation that
     * would have worked a moment later, this waits up to {@link #PLATFORM_START_BUDGET_MILLIS} for
     * the platform to get under way and then decides. The wait is OUTSIDE this object's monitor:
     * holding it would stall every other comparison call for the length of the wait, including the
     * poll of the very launch being waited for.
     *
     * @param comparisonId the id issued by {@link #register}
     * @param ending why the comparison is ending; it selects EDT's verb and nothing else
     * @return what was observed; never {@code null}
     */
    public SlotHandback handBack(String comparisonId, SlotHandback.Ending ending)
    {
        awaitPlatformStart(comparisonId);
        return handBackWhenAsked(comparisonId, ending);
    }

    /**
     * The asked-for hand-back once the waiting is over, with the monitor taken.
     *
     * @param comparisonId the id issued by {@link #register}
     * @param ending which platform verb to use
     * @return what was observed
     */
    private synchronized SlotHandback handBackWhenAsked(String comparisonId, SlotHandback.Ending ending)
    {
        ComparisonSession session = comparisonId == null ? null : sessions.get(comparisonId);
        if (session == null)
        {
            return SlotHandback.of(SlotHandback.Verdict.NOT_REGISTERED, comparisonId);
        }
        return handBackNow(session, ending);
    }

    /**
     * Rolls back the registration made for a launch the PLATFORM REFUSED, in the way the
     * platform's own answers allow.
     * <p>
     * <b>Why the ordinary hand-back is not enough here.</b> The registration is made before the
     * batch is handed over, so a hand-over that fails has to decide what becomes of it. The
     * hand-back is built for NOT KNOWING and it answers
     * {@link SlotHandback.Verdict#NOT_STARTED_YET} for a comparison EDT reports no status for -
     * which deliberately KEEPS the record, because ending a comparison EDT has merely scheduled
     * costs EDT its comparison support for the rest of the session. That is right when nobody
     * refused anything. It is wrong after a refusal: the launch is never going to begin, so the
     * kept record names EDT's single slot as taken by a comparison that does not exist and refuses
     * every later launch until the idle TTL expires. Measured live: a comparison started from EDT's
     * own interface between a launch's slot check and its hand-over produces exactly that.
     * <p>
     * <b>What the caller supplies, and what it may not.</b> The one fact this object cannot
     * establish for itself is that the platform REFUSED - that is the caller's throw, the same
     * shape as {@link SlotHandback.Ending}. Everything else is asked of EDT here, and the
     * withdrawal happens only on EDT's own definite answer: it is the {@code NOT_STARTED_YET}
     * reading, taken under this monitor, that says the comparison is not running. A caller cannot
     * assert the withdrawal, and a reading that establishes nothing -
     * {@link SlotHandback.Verdict#UNREACHABLE} when EDT could not be asked,
     * {@link SlotHandback.Verdict#NOT_FREED} when the hand-back itself failed - keeps the record
     * exactly as it does on every other path. The rule "keep it when we do not know" is not
     * weakened; what changes is that "the platform said no" stops being filed under not knowing.
     * <p>
     * The reading and the drop share ONE monitor hold, so a comparison that begins in the last
     * millisecond of the wait is decided by one reading rather than by two that can disagree.
     *
     * @param comparisonId the id issued by {@link #register} for the launch the platform refused
     * @param ending why the comparison is ending; it selects EDT's verb and nothing else, and is
     *     used only on the readings where a hand-back is actually attempted
     * @return what was observed; never {@code null}
     */
    public SlotHandback handBackRefusedLaunch(String comparisonId, SlotHandback.Ending ending)
    {
        awaitPlatformStart(comparisonId);
        return withdrawRefusedWhenAsked(comparisonId, ending);
    }

    /**
     * The refused-launch rollback once the waiting is over, with the monitor taken.
     *
     * @param comparisonId the id the caller quoted
     * @param ending which platform verb to use where one is used at all
     * @return what was observed
     */
    private synchronized SlotHandback withdrawRefusedWhenAsked(String comparisonId,
        SlotHandback.Ending ending)
    {
        ComparisonSession session = comparisonId == null ? null : sessions.get(comparisonId);
        if (session == null)
        {
            return SlotHandback.of(SlotHandback.Verdict.NOT_REGISTERED, comparisonId);
        }
        SlotHandback handback = handBackNow(session, ending);
        if (handback.verdict() != SlotHandback.Verdict.NOT_STARTED_YET)
        {
            // EDT either took the hand-back, or had already forgotten the handle, or could not be
            // asked, or refused the hand-back. None of those is the platform answering that it is
            // not running the comparison, so none of them earns a withdrawal - the ordinary
            // answer stands, record and all.
            return handback;
        }
        sessions.remove(comparisonId);
        return SlotHandback.of(SlotHandback.Verdict.LAUNCH_REFUSED, comparisonId);
    }

    /**
     * Gives EDT a bounded chance to BEGIN a comparison before it is asked to end one.
     * <p>
     * It returns as soon as anything is settled, and every one of those outcomes is a reason to
     * stop waiting rather than a reason to keep going: the platform has begun; the id answers to
     * nothing here; EDT could not be asked at all; or the budget is spent. Nothing is decided here
     * - {@link #handBackNow} asks the question again under the monitor and produces the verdict, so
     * a comparison that begins during the last millisecond of the wait is still decided by one
     * reading rather than by two that can disagree.
     *
     * @param comparisonId the id the caller quoted
     */
    private void awaitPlatformStart(String comparisonId)
    {
        long deadline = clock.getAsLong() + PLATFORM_START_BUDGET_MILLIS;
        while (true)
        {
            ComparisonSession session = sessionFor(comparisonId);
            if (session == null || hasBegun(session) != Boolean.FALSE)
            {
                return;
            }
            if (clock.getAsLong() >= deadline)
            {
                return;
            }
            try
            {
                pause.millis(PLATFORM_START_POLL_MILLIS);
            }
            catch (InterruptedException e)
            {
                // The caller is being torn down. Restore the flag and stop waiting; the hand-back
                // still runs and still refuses to end a comparison EDT has not begun, so an
                // interrupt costs a retry and cannot cost EDT's comparison support.
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * @param comparisonId the id the caller quoted
     * @return the registered session, or {@code null} - read under the monitor, because the wait
     *     around it deliberately does not hold it
     */
    private synchronized ComparisonSession sessionFor(String comparisonId)
    {
        return comparisonId == null ? null : sessions.get(comparisonId);
    }

    /**
     * Withdraws the registration made for a launch that NEVER REACHED EDT.
     * <p>
     * <b>Why this is not a hand-back.</b> A hand-back reasons about a comparison the platform may
     * be running, so when it cannot reach the platform it keeps the record - it does not know. Here
     * there is nothing to know: the registration is made BEFORE the batch is handed over, and this
     * is called only when the hand-over itself failed in a way that proves the platform was never
     * asked. Routing that through {@link #handBack(String, SlotHandback.Ending)} produced
     * {@link SlotHandback.Verdict#UNREACHABLE} - the absent service cannot be asked to end anything
     * either - and left a registration that named EDT's single slot as taken by a comparison that
     * had never been started, refusing every later launch until the idle TTL expired.
     * <p>
     * <b>The proof is the caller's and cannot be invented.</b> The one thing this method cannot
     * establish for itself is that nothing was started, so the caller supplies it - the same shape
     * as {@link SlotHandback.Ending}, which is the one fact a caller knows that the owner cannot.
     * In this bundle the only such proof is
     * {@link ComparisonEngine.ServiceUnavailableException} out of {@link ComparisonEngine#start},
     * which the facade throws precisely so that a launch that reached nothing cannot be mistaken
     * for one that succeeded quietly. A caller that is merely UNSURE must use the hand-back, which
     * is built to be unsure.
     *
     * @param comparisonId the id issued by {@link #register} for the launch that failed
     * @return {@link SlotHandback.Verdict#NEVER_STARTED}, or
     *     {@link SlotHandback.Verdict#NOT_REGISTERED} when nothing answers to the id
     */
    public synchronized SlotHandback withdrawUnstartedLaunch(String comparisonId)
    {
        if (comparisonId == null || sessions.remove(comparisonId) == null)
        {
            return SlotHandback.of(SlotHandback.Verdict.NOT_REGISTERED, comparisonId);
        }
        return SlotHandback.of(SlotHandback.Verdict.NEVER_STARTED, comparisonId);
    }

    /**
     * Releases every session that has sat untouched longer than the TTL.
     * <p>
     * Public for the tests and for a caller that wants to reclaim without asking anything else;
     * production never has to call it, because every liveness-checking lookup already does.
     *
     * @return how many sessions were RECLAIMED - given back, or proven already gone. A session
     *     whose hand-back failed is not counted, because it was not reclaimed
     */
    public synchronized int sweep()
    {
        return sweepExpired();
    }

    /**
     * The sweep itself, run with this object's monitor already held.
     * <p>
     * Every lookup that re-checks liveness starts here, so an expired session is given back BEFORE
     * the lookup can touch it back to life or name it to a caller.
     * <p>
     * It reaches the platform through {@link #handBackNow}, the same step
     * {@link #handBack(String, SlotHandback.Ending)} and {@link #releaseAll()} use, so the
     * drop-only-when-confirmed-free invariant holds here without this method restating it. That
     * matters most on this path: it is the one where NOBODY is watching, and it used to drop every
     * expired session unconditionally while discarding what the hand-back reported.
     * <p>
     * A session with an open {@link Lease} is passed over entirely. Idleness is measured from the
     * last lookup, and a tree read is a single lookup followed by minutes of work; without this a
     * large comparison would be ended underneath the BM read walking it.
     *
     * @return how many were reclaimed
     */
    private int sweepExpired()
    {
        long deadline = clock.getAsLong() - idleTtlMillis;
        List<ComparisonSession> expired = new ArrayList<>();
        for (ComparisonSession session : sessions.values())
        {
            if (session.leases == 0 && session.lastTouchedMillis <= deadline)
            {
                expired.add(session);
            }
        }
        int reclaimed = 0;
        for (ComparisonSession session : expired)
        {
            if (handBackNow(session, SlotHandback.Ending.CLOSED).slotIsFree())
            {
                reclaimed++;
            }
        }
        return reclaimed;
    }

    /**
     * Ends everything and refuses to register anything again. Called when the bundle stops, so
     * that a comparison left open does not outlive the server that started it, and so that a
     * worker still in flight cannot register one into a registry nobody will sweep again.
     * <p>
     * The order is load-bearing: the registry is CLOSED first and emptied afterwards. Emptying
     * first would leave a window in which a late worker registers a session that this call has
     * already walked past.
     *
     * @return how many sessions were confirmed free
     */
    public synchronized int closeAndReleaseAll()
    {
        closed = true;
        // A standing claim goes with them. It names no handle, so there is nothing to end - but
        // leaving it would let a launch still in flight adopt it into a registry nobody will sweep
        // again, which is the very thing the closed flag exists to refuse.
        claimedComparisonId = null;
        claimedProjectName = null;
        return releaseAll();
    }

    /**
     * Ends everything. Called when the bundle stops, so that a comparison left open does not
     * outlive the server that started it.
     * <p>
     * It goes through {@link #handBackNow} like every other path, and then clears the map whatever
     * the verdicts were: keeping a record so a later call can retry means nothing when there will
     * be no later call. What CANNOT be given back here is not given back at all - if EDT's
     * comparison service is already unregistered every session answers
     * {@link SlotHandback.Verdict#UNREACHABLE} and the virtual projects go away with the JVM. That
     * boundary is stated on {@link SlotHandback}.
     *
     * @return how many sessions were confirmed free
     */
    public synchronized int releaseAll()
    {
        int freed = 0;
        for (ComparisonSession session : new ArrayList<>(sessions.values()))
        {
            if (handBackNow(session, SlotHandback.Ending.CLOSED).slotIsFree())
            {
                freed++;
            }
        }
        sessions.clear();
        return freed;
    }

    /**
     * The hand-back itself, with this object's monitor already held: the ONE place a comparison is
     * ended and the ONE place a record is dropped.
     * <p>
     * Three inputs, one answer, no room for a caller to recombine them: what EDT says about the
     * handle ({@link Liveness}, which is {@link PlatformAnswer} folded into three named cases),
     * whether EDT has BEGUN the comparison, and whether the platform accepted the hand-back. The
     * order is load-bearing, and both questions come before the attempt for the same kind of
     * reason: asking EDT to end a handle it no longer knows is not a no-op everywhere, and asking
     * it to end one it has not started yet costs EDT its comparison support for the rest of the
     * session ({@link SlotHandback.Verdict#NOT_STARTED_YET}).
     *
     * @param session the session to end
     * @param ending which platform verb to use
     * @return what was observed
     */
    private SlotHandback handBackNow(ComparisonSession session, SlotHandback.Ending ending)
    {
        String comparisonId = session.comparisonId();
        if (liveness(session) == Liveness.GONE)
        {
            // Nothing left to give back, and asking the platform to end a handle it has already
            // forgotten is not a no-op everywhere. The record goes because the slot IS free.
            sessions.remove(comparisonId);
            return SlotHandback.of(SlotHandback.Verdict.ALREADY_FREE, comparisonId);
        }
        if (hasBegun(session) == Boolean.FALSE)
        {
            // EDT holds the comparison and has NOT begun running it. Asked to end it now, EDT
            // would delete the Eclipse job it had only scheduled, and the method that gives EDT's
            // own slot back lives inside that job - so EDT would report a comparison as active for
            // the rest of its life. Nothing is asked of the platform and the record is kept, which
            // is what lets this be repeated a moment later. See
            // SlotHandback.Verdict.NOT_STARTED_YET.
            return SlotHandback.of(SlotHandback.Verdict.NOT_STARTED_YET, comparisonId);
        }
        // Attempted on BOTH remaining readings, HELD and UNKNOWN alike. "Could not ask whether EDT
        // still holds it" is not a reason to leave a comparison running: the liveness question
        // also goes unanswered when the PROJECT fails to resolve, and skipping the hand-back then
        // would strand a session that the platform would have ended perfectly well.
        SlotHandback.Verdict verdict = attemptEnd(session, ending);
        if (verdict == SlotHandback.Verdict.FREED)
        {
            sessions.remove(comparisonId);
        }
        return SlotHandback.of(verdict, comparisonId);
    }

    /**
     * Whether EDT has BEGUN this comparison, as three answers folded into a nullable Boolean.
     * <p>
     * Only a definite {@code FALSE} withholds a hand-back, and the two readings that are not one
     * both answer {@code null} - "nothing was established" - on purpose:
     * <ul>
     *   <li>the session carries no handle, so there is no platform job to protect and no hand-back
     *       to withhold;</li>
     *   <li>EDT could not be asked. That is the same absent service that makes the hand-back itself
     *       throw {@link ComparisonEngine.ServiceUnavailableException}, so letting it through costs
     *       nothing - it arrives at {@link SlotHandback.Verdict#UNREACHABLE}, which keeps the
     *       record too - while treating it as "not begun" would put a fact about this server's
     *       reach into a statement about the comparison, the fold this whole class exists to
     *       undo.</li>
     * </ul>
     * <b>The honest boundary.</b> "Begun" is read from EDT reporting a status for the handle, and
     * EDT stamps the first one inside the job's own run. A job that has started but not reached
     * that stamp - it is still opening the workspace operation - therefore reads as NOT begun, and
     * a hand-back is withheld although it would have been safe. That direction is the harmless one:
     * it costs a repeat, while the other direction costs EDT's comparison support until EDT is
     * restarted.
     *
     * @param session the session to ask about
     * @return {@link Boolean#TRUE} when the comparison is under way, {@link Boolean#FALSE} when EDT
     *     answers that it is not, or {@code null} when nothing was established
     */
    private Boolean hasBegun(ComparisonSession session)
    {
        if (session == null || session.handle() == null)
        {
            return null;
        }
        PlatformAnswer<Boolean> answer;
        try
        {
            answer = launchProgress.hasBegun(session);
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not ask whether EDT has started comparison " //$NON-NLS-1$
                + session.comparisonId(), e);
            return null;
        }
        if (answer == null || answer.isUnavailable())
        {
            return null;
        }
        return answer.orElse(null);
    }

    /**
     * What EDT says about a handle, as three answers.
     * <p>
     * Four readings collapse into them and each collapse is a decision:
     * <ul>
     *   <li>EDT lists the handle - {@link Liveness#HELD}, and the latch is raised so a LATER
     *       absence counts;</li>
     *   <li>EDT does not list it and never did - {@link Liveness#HELD}. The launch is scheduled and
     *       has not surfaced; "not yet" is not "no more";</li>
     *   <li>the question THREW - {@link Liveness#UNKNOWN}. Nothing was established, and the caller
     *       must not act as though something was;</li>
     *   <li>the question could not be ASKED at all ({@link PlatformAnswer#unavailable()}) -
     *       {@link Liveness#UNKNOWN}. That is not the same statement as "not there", and it is
     *       where this used to be wrong: an unregistered service answered an EMPTY LIST, which read
     *       exactly like EDT saying it no longer holds the handle.</li>
     * </ul>
     *
     * @param session the session to ask about
     * @return what was established, which may be nothing
     */
    private Liveness liveness(ComparisonSession session)
    {
        PlatformAnswer<List<ComparisonProcessHandle>> answer;
        try
        {
            answer = liveHandles.forProject(session.projectName());
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not list live comparisons for project " + session.projectName(), e); //$NON-NLS-1$
            return Liveness.UNKNOWN;
        }
        if (answer == null || answer.isUnavailable())
        {
            // The question was not asked - EDT's comparison service was unregistered, or the
            // project did not resolve. That is a fact about this server's reach, not about the
            // comparison, and it used to arrive here as an empty list indistinguishable from
            // EDT's own "I do not hold that handle".
            return Liveness.UNKNOWN;
        }
        List<ComparisonProcessHandle> live = answer.orElse(null);
        if (live != null && live.contains(session.handle()))
        {
            session.seenAliveByEdt = true;
            return Liveness.HELD;
        }
        return session.seenAliveByEdt ? Liveness.GONE : Liveness.HELD;
    }

    /**
     * Ends one comparison on the platform, turning a failure into an answer instead of a log line.
     * <p>
     * The two failures are told apart, and that is the difference between the two verdicts that
     * keep the record: a service that is not registered NEVER RECEIVED the request - the facade
     * throws {@code ServiceUnavailableException} precisely so that this cannot be mistaken for a
     * quiet success - while any other failure means EDT was reached and refused. A caller acts
     * differently on the two: the first is retried when EDT has finished starting, the second is
     * looked up in the EDT error log.
     *
     * @param session the session to end
     * @param ending which platform verb to use
     * @return {@link SlotHandback.Verdict#FREED}, {@link SlotHandback.Verdict#UNREACHABLE} or
     *     {@link SlotHandback.Verdict#NOT_FREED}
     */
    private SlotHandback.Verdict attemptEnd(ComparisonSession session, SlotHandback.Ending ending)
    {
        try
        {
            releaser.release(session, ending);
            return SlotHandback.Verdict.FREED;
        }
        catch (ComparisonEngine.ServiceUnavailableException e)
        {
            // Caught BEFORE RuntimeException below, and it is a subclass, so the order is
            // load-bearing: EDT's comparison service was not registered at the moment of the call,
            // so nothing reached the platform at all.
            Activator.logError("Could not end comparison " + session.comparisonId(), e); //$NON-NLS-1$
            return SlotHandback.Verdict.UNREACHABLE;
        }
        catch (RuntimeException e)
        {
            // A failure must not stop the remaining ones - releaseAll() runs on the way out of the
            // bundle, where a thrown exception would strand every later session.
            Activator.logError("Could not end comparison " + session.comparisonId(), e); //$NON-NLS-1$
            return SlotHandback.Verdict.NOT_FREED;
        }
    }

    /**
     * Ends one lease and restarts the TTL from now.
     * <p>
     * Touching on CLOSE and not on open is the point: a read that took longer than the TTL has
     * just proved the comparison is in use, and leaving {@code lastTouchedMillis} at the moment the
     * read began would make the very next sweep reclaim it.
     *
     * @param session the leased session (may be {@code null} when nothing was leased)
     */
    private synchronized void endLease(ComparisonSession session)
    {
        if (session != null && session.leases > 0)
        {
            session.leases--;
            session.lastTouchedMillis = clock.getAsLong();
        }
    }

    /**
     * The ids a caller may still quote, oldest first.
     * <p>
     * This is the list an "unknown comparison" refusal offers the caller, so it goes through the
     * same liveness question as every other lookup: each id is re-checked against EDT and the ones
     * EDT no longer holds are dropped from the map rather than named. Answering from the map alone
     * would send the caller back with an id belonging to a comparison that ended in the workbench.
     * <p>
     * Unlike {@link #find(String)} this does NOT count as a touch: naming a session in an error
     * message is not use of it, and letting it postpone the TTL would keep an abandoned comparison
     * pinned by nothing more than repeated failures.
     *
     * @return an unmodifiable list of the ids EDT still holds, oldest first
     */
    public synchronized List<String> ids()
    {
        // Reclaim first, so an error message cannot offer the caller an id whose session this very
        // call was entitled to release.
        sweepExpired();
        List<String> live = new ArrayList<>();
        for (ComparisonSession session : new ArrayList<>(sessions.values()))
        {
            if (liveness(session) == Liveness.GONE)
            {
                sessions.remove(session.comparisonId());
            }
            else
            {
                live.add(session.comparisonId());
            }
        }
        return Collections.unmodifiableList(live);
    }
}
