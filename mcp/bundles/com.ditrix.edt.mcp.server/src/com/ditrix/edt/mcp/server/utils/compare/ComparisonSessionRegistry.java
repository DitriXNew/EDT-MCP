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
 * comparison or drops its record, and {@link SlotHandback} is what it answers with. Every other
 * path here — the idle {@link #sweep()}, {@link #releaseAll()} on the way out of the bundle — goes
 * through the same private step, so all three obey one invariant:
 * <p>
 * <b>The record is dropped exactly when the slot is CONFIRMED free.</b>
 * <p>
 * A session that could not be given back therefore STAYS registered, whether the hand-back failed
 * or could not be attempted at all. That is the honest state - it may still hold the slot - and it
 * has three effects that are all wanted: a refusal can still name it, {@code releaseComparisonId}
 * can still address it, and the next sweep retries the hand-back, which is what makes a session
 * stranded by a momentary service gap reclaim itself once the service is back. The reasoning
 * behind the invariant, and everything it does NOT promise, is written down once on
 * {@link SlotHandback}.
 * <p>
 * A session that EDT has already forgotten is dropped WITHOUT being ended: asking the platform to
 * end a handle it no longer knows is not a no-op everywhere, and there is nothing left to give
 * back. That is the {@link SlotHandback.Verdict#ALREADY_FREE} answer, and it is a free slot.
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
 * <p>Every mutating method is {@code synchronized}: the sweep runs from whichever call happens to
 * touch the registry next, so it races with ordinary lookups by construction.
 */
public final class ComparisonSessionRegistry
{
    /**
     * How long a comparison may sit untouched before the sweep releases it. Thirty minutes is long
     * enough for a human-paced read-expand-read loop over a large configuration and short enough
     * that a forgotten comparison does not outlive the working day.
     */
    public static final long DEFAULT_IDLE_TTL_MILLIS = 30L * 60L * 1000L;

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
        }, projectName -> PlatformAnswer.of(Collections.emptyList()), false);

    private final Map<String, ComparisonSession> sessions = new LinkedHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);
    private final LongSupplier clock;
    private final long idleTtlMillis;
    private final Releaser releaser;
    private final LiveHandles liveHandles;
    private final boolean attached;

    /**
     * @param clock the millisecond clock (injected so the TTL is testable without sleeping)
     * @param idleTtlMillis how long a session may sit untouched
     * @param releaser ends one comparison on the platform
     * @param liveHandles asks EDT what it currently holds
     */
    ComparisonSessionRegistry(LongSupplier clock, long idleTtlMillis, Releaser releaser, LiveHandles liveHandles)
    {
        this(clock, idleTtlMillis, releaser, liveHandles, true);
    }

    private ComparisonSessionRegistry(LongSupplier clock, long idleTtlMillis, Releaser releaser,
        LiveHandles liveHandles, boolean attached)
    {
        this.clock = clock;
        this.idleTtlMillis = idleTtlMillis;
        this.releaser = releaser;
        this.liveHandles = liveHandles;
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
        if (!attached)
        {
            throw new IllegalStateException("No comparison facade is installed, so a comparison " //$NON-NLS-1$
                + "cannot be registered - it would be owned by nobody and would leak its virtual " //$NON-NLS-1$
                + "project. Check ComparisonEngine.get() before starting one."); //$NON-NLS-1$
        }
        long now = clock.getAsLong();
        String comparisonId = "cmp-" + idGenerator.getAndIncrement(); //$NON-NLS-1$
        String projectName = handle == null || handle.getMainDescriptor() == null
            ? null
            : handle.getMainDescriptor().getProjectName();
        sessions.put(comparisonId, new ComparisonSession(comparisonId, projectName, handle, batch, now));
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
     * <b>The one owner.</b> Nothing else in this bundle ends a comparison or drops its record: the
     * platform's two lifetime verbs are package-scoped on {@link ComparisonEngine} and the session
     * map is private here, so a caller has no other door. It gets a {@link SlotHandback} back and
     * publishes {@link SlotHandback#sentence()}; it does not decide what happened, and it cannot
     * lose a failure by writing nothing, because the failure is inside the sentence it prints.
     * <p>
     * The record is dropped exactly when the slot is confirmed free - see the class javadoc for
     * the invariant and {@link SlotHandback} for what it does not promise.
     *
     * @param comparisonId the id issued by {@link #register}
     * @param ending why the comparison is ending; it selects EDT's verb and nothing else
     * @return what was observed; never {@code null}
     */
    public synchronized SlotHandback handBack(String comparisonId, SlotHandback.Ending ending)
    {
        ComparisonSession session = comparisonId == null ? null : sessions.get(comparisonId);
        if (session == null)
        {
            return SlotHandback.of(SlotHandback.Verdict.NOT_REGISTERED, comparisonId);
        }
        return handBackNow(session, ending);
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
     * handle ({@link Liveness}, which is {@link PlatformAnswer} folded into three named cases), and
     * whether the platform accepted the hand-back. The order is load-bearing - liveness FIRST,
     * because asking EDT to end a handle it no longer knows is not a no-op everywhere, and the
     * answer decides whether there is anything to attempt at all.
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
