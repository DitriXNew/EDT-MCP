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
 * and a private BM store alive, and only {@code cancel}/{@code stop} give them back. The obvious
 * place to park the handle — the background job's result — cannot own that lifetime:
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
 * <h2>Release paths</h2>
 * There are exactly three, and all of them go through {@link #releaser}:
 * <ul>
 *   <li>{@link #release(String)} — the caller asked. It answers WHAT IT OBSERVED
 *       ({@link ReleaseOutcome}) rather than merely whether a record existed: dropping the record
 *       is not stopping the comparison, and a caller told "the slot is free" acts on a slot
 *       somebody may still hold;</li>
 *   <li>{@link #sweep()} — the session sat idle past its TTL. An abandoned comparison otherwise
 *       pins its virtual project for as long as EDT runs. A session it could NOT give back stays
 *       registered, because nobody is watching this path and a silent loss would report the slot
 *       as free while the comparison still held it;</li>
 *   <li>{@link #releaseAll()} — the bundle is stopping ({@code EdtServices.dispose}).</li>
 * </ul>
 * A session that EDT has already forgotten is dropped from the map WITHOUT being released: asking
 * the platform to cancel a handle it no longer knows is not a no-op everywhere, and there is
 * nothing left to give back.
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

    /**
     * What a release attempt actually observed.
     * <p>
     * Three states and not a {@code boolean}, because the boolean it replaces answered a question
     * nobody was asking - "was a record present?" - and every caller published the answer to a
     * different one: "is EDT's single comparison slot free again?". Dropping the map entry always
     * succeeds; the stop behind it does not.
     */
    public enum ReleaseOutcome
    {
        /** The record was dropped and the platform was asked to stop the comparison, without error. */
        RELEASED,
        /** Nothing was registered under that id, so nothing was released and nothing was stopped. */
        NOT_REGISTERED,
        /**
         * The record was dropped and no stop was asked for, because EDT no longer held the handle:
         * the comparison had already ended, so there was nothing left to give back.
         * <p>
         * This used to share one literal with {@link #STOP_FAILED}, and the two were told apart
         * nowhere - the javadoc said so in as many words. They had to be split because a caller
         * that has just cancelled a comparison ITSELF gets this outcome on the ordinary path (its
         * own cancel is what made EDT forget the handle), and reporting that as a failed stop
         * would turn every successful cancellation into a warning.
         */
        ALREADY_GONE,
        /**
         * The record was dropped, EDT still held the handle, and the hand-back was attempted and
         * did NOT complete - the failure is in the EDT error log. The slot may still be taken.
         */
        STOP_FAILED
    }

    /** Gives a live handle back to EDT. */
    @FunctionalInterface
    interface Releaser
    {
        /**
         * @param session the session being released
         */
        void release(ComparisonSession session);
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
     * The stand-in returned by {@link #shared()} when no facade is installed - before the bundle
     * starts and after it stops. It answers every LOOKUP with "nothing" and every RELEASE with a
     * no-op, both of which are true, and it REFUSES to register: a session recorded here would be
     * owned by nobody and would leak the comparison it names.
     */
    private static final ComparisonSessionRegistry DETACHED = new ComparisonSessionRegistry(
        System::currentTimeMillis, DEFAULT_IDLE_TTL_MILLIS, session -> {
            // nothing to release: nothing can be registered here
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
     * @param releaser gives a handle back to EDT
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
     * releasable across such a gap.
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
            if (hasVanishedFromEdt(session))
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
        if (hasVanishedFromEdt(session))
        {
            // Gone on EDT's side, and gone means it was HERE first: drop the record, do NOT
            // release a handle the platform has already forgotten.
            sessions.remove(comparisonId);
            return Optional.empty();
        }
        session.lastTouchedMillis = clock.getAsLong();
        return Optional.of(session);
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
     * Releases one comparison and says what that actually achieved.
     * <p>
     * The record is dropped in every case a record existed - leaving it behind would pin EDT's
     * single slot on a comparison nobody can reach - but dropping it is bookkeeping, not a stop.
     * The verdict separates the two so the caller can state which one happened.
     *
     * @param comparisonId the id issued by {@link #register}
     * @return what was observed; never {@code null}
     */
    public synchronized ReleaseOutcome release(String comparisonId)
    {
        ComparisonSession session = comparisonId == null ? null : sessions.get(comparisonId);
        if (session == null)
        {
            return ReleaseOutcome.NOT_REGISTERED;
        }
        // Asked BEFORE the record goes, and asked at all - which release() never did: it stopped
        // handles the platform had already forgotten and reported every one of them as a stop.
        boolean vanished = hasVanishedFromEdt(session);
        // Dropped in every case a record existed, and deliberately unlike the sweep below: the
        // caller ASKED, is told exactly what happened, and gets the follow-up to act on. Leaving
        // the record would make the id resolve again for somebody who was just told it is closed.
        sessions.remove(comparisonId);
        if (vanished)
        {
            // Nothing left to give back, and asking the platform to stop a handle it no longer
            // knows is not a no-op everywhere. The record is gone; a stop is not claimed.
            return ReleaseOutcome.ALREADY_GONE;
        }
        return releaseQuietly(session) ? ReleaseOutcome.RELEASED : ReleaseOutcome.STOP_FAILED;
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
     *
     * <h2>A hand-back that failed does not drop the record</h2>
     * This is the one place where a record is dropped with NOBODY watching, and it used to drop
     * every expired one unconditionally while discarding what the hand-back reported. When the TTL
     * fell during a service gap, or the stop threw, the session vanished from this map while its
     * virtual project and private BM store could still be open: {@link #activeComparisonId()} then
     * answered "nothing holds the slot", the next launch was allowed through, and EDT's
     * one-comparison-per-instance assertion refused it with no sentence anybody could act on.
     * <p>
     * So an unreclaimed session STAYS registered. That is the honest state - it may still hold the
     * slot - and it has two effects that are both wanted: a refusal can still name it, with the
     * {@code releaseComparisonId} remedy attached, and the next sweep retries the hand-back, which
     * is what makes a session stranded by a momentary service gap reclaim itself once the service
     * is back. It differs from {@link #release(String)} on purpose: there a caller asked and is
     * told what happened, here nobody would ever learn of a silent loss.
     *
     * @return how many were reclaimed
     */
    private int sweepExpired()
    {
        long deadline = clock.getAsLong() - idleTtlMillis;
        List<ComparisonSession> expired = new ArrayList<>();
        for (ComparisonSession session : sessions.values())
        {
            if (session.lastTouchedMillis <= deadline)
            {
                expired.add(session);
            }
        }
        int reclaimed = 0;
        for (ComparisonSession session : expired)
        {
            if (hasVanishedFromEdt(session))
            {
                // EDT has already forgotten it, so there is nothing to give back and nothing to
                // ask for. Dropping the record here loses nothing.
                sessions.remove(session.comparisonId());
                reclaimed++;
                continue;
            }
            if (releaseQuietly(session))
            {
                sessions.remove(session.comparisonId());
                reclaimed++;
            }
        }
        return reclaimed;
    }

    /**
     * Releases everything. Called when the bundle stops, so that a comparison left open does not
     * outlive the server that started it.
     *
     * @return how many were released
     */
    public synchronized int releaseAll()
    {
        List<ComparisonSession> all = new ArrayList<>(sessions.values());
        sessions.clear();
        for (ComparisonSession session : all)
        {
            releaseQuietly(session);
        }
        return all.size();
    }

    /**
     * Whether EDT has stopped holding a handle it was once holding.
     * <p>
     * Three answers are folded into this one boolean and each of them is a decision:
     * <ul>
     *   <li>EDT lists the handle - not vanished, and the latch is raised so a LATER absence counts;</li>
     *   <li>EDT does not list it and never did - not vanished. The launch is scheduled and has not
     *       surfaced; "not yet" is not "no more";</li>
     *   <li>the question THREW - not vanished. The session stays and the next real call fails with
     *       the platform's own message;</li>
     *   <li>the question could not be ASKED at all ({@link PlatformAnswer#unavailable()}) - not
     *       vanished. That is not the same statement as "not there", and it is where this method
     *       used to be wrong: an unregistered service answered an EMPTY LIST, which read exactly
     *       like EDT saying it no longer holds the handle.</li>
     * </ul>
     *
     * @param session the session to check
     * @return {@code true} only when EDT once listed the handle and does not any more
     */
    private boolean hasVanishedFromEdt(ComparisonSession session)
    {
        PlatformAnswer<List<ComparisonProcessHandle>> answer;
        try
        {
            answer = liveHandles.forProject(session.projectName());
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not list live comparisons for project " + session.projectName(), e); //$NON-NLS-1$
            return false;
        }
        if (answer == null || answer.isUnavailable())
        {
            // The question was not asked - EDT's comparison service was unregistered, or the
            // project did not resolve. That is a fact about this server's reach, not about the
            // comparison, and it used to arrive here as an empty list indistinguishable from
            // EDT's own "I do not hold that handle". Believing it dropped a live session
            // WITHOUT stopping it, and the comparison went on holding EDT's single slot with
            // nothing left able to address it.
            return false;
        }
        List<ComparisonProcessHandle> live = answer.orElse(null);
        if (live != null && live.contains(session.handle()))
        {
            session.seenAliveByEdt = true;
            return false;
        }
        return session.seenAliveByEdt;
    }

    /**
     * Hands one session back, turning a failed hand-back into an answer instead of a log line.
     *
     * @param session the session to release
     * @return {@code true} when the releaser returned without complaint
     */
    private boolean releaseQuietly(ComparisonSession session)
    {
        try
        {
            releaser.release(session);
            return true;
        }
        catch (RuntimeException e)
        {
            // A failed release must not stop the remaining ones - releaseAll() runs on the way out
            // of the bundle, where a thrown exception would strand every later session.
            Activator.logError("Could not release comparison " + session.comparisonId(), e); //$NON-NLS-1$
            return false;
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
            if (hasVanishedFromEdt(session))
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
