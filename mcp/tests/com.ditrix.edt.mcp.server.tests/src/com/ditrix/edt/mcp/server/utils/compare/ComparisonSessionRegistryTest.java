/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSourceDescriptor;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonSessionRegistry.ComparisonSession;

/**
 * Unit tests for {@link ComparisonSessionRegistry}.
 * <p>
 * The registry exists because a comparison's resources - a virtual project and a private BM store -
 * are handed back only by {@code cancel}/{@code stop}, and the obvious place to park the handle
 * cannot own that: background-job records are evicted by a bare map removal with no dispose hook.
 * These tests pin the ONE hand-back the three paths share - a caller asking, the idle sweep, the
 * bundle stopping - the invariant that a record is dropped exactly when the slot is confirmed free,
 * and, separately, that liveness is ASKED of EDT rather than remembered.
 */
public class ComparisonSessionRegistryTest
{
    private static final long TTL = 10_000L;

    /** The whole of {@code IComparisonDataSourceDescriptor} is one method, so a fake is honest. */
    private static final class FakeDescriptor
        implements IComparisonDataSourceDescriptor
    {
        private final String projectName;

        FakeDescriptor(String projectName)
        {
            this.projectName = projectName;
        }

        @Override
        public String getProjectName()
        {
            return projectName;
        }
    }

    private static CompareMergeProcessBatch batch()
    {
        return new CompareMergeProcessBatch(Collections.emptyList());
    }

    private static ComparisonProcessHandle handle(String name)
    {
        return new ComparisonProcessHandle(new FakeDescriptor(name), new FakeDescriptor(name + "-other"), //$NON-NLS-1$
            ComparisonScope.EMPTY_SCOPE);
    }

    /** A settable clock, so a TTL can be tested without sleeping through it. */
    private static final class FakeClock
    {
        long now = 1_000L;
    }

    /** Records what the registry ended, how, and can be told to fail either way. */
    private static final class RecordingReleaser
        implements ComparisonSessionRegistry.Releaser
    {
        final List<String> released = new ArrayList<>();
        final List<SlotHandback.Ending> endings = new ArrayList<>();
        boolean explode;
        /**
         * Whether the failure is the platform saying "I was never asked". It is a DIFFERENT fact
         * from a refusal and the registry has to keep them apart, so the fake can produce both.
         */
        boolean serviceGone;

        @Override
        public void release(ComparisonSession session, SlotHandback.Ending ending)
        {
            released.add(session.comparisonId());
            endings.add(ending);
            if (serviceGone)
            {
                throw new ComparisonEngine.ServiceUnavailableException("ending a comparison"); //$NON-NLS-1$
            }
            if (explode)
            {
                throw new IllegalStateException("release refused"); //$NON-NLS-1$
            }
        }
    }

    /** Answers what EDT "currently holds", and counts how often it was consulted. */
    private static final class FakeLiveHandles
        implements ComparisonSessionRegistry.LiveHandles
    {
        List<ComparisonProcessHandle> live = new ArrayList<>();
        int asked;
        RuntimeException failure;
        /** Whether EDT can be reached at all; when it cannot, there is no answer to give. */
        boolean reachable = true;

        @Override
        public PlatformAnswer<List<ComparisonProcessHandle>> forProject(String projectName)
        {
            asked++;
            if (failure != null)
            {
                throw failure;
            }
            return reachable ? PlatformAnswer.of(live) : PlatformAnswer.unavailable();
        }
    }

    private final FakeClock clock = new FakeClock();
    private final RecordingReleaser releaser = new RecordingReleaser();
    private final FakeLiveHandles liveHandles = new FakeLiveHandles();

    private ComparisonSessionRegistry registry()
    {
        return new ComparisonSessionRegistry(() -> clock.now, TTL, releaser, liveHandles);
    }

    @Test
    public void aRegisteredSessionIsFoundByItsId()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);

        String id = registry.register(handle, batch());

        Optional<ComparisonSession> found = registry.find(id);
        assertTrue(found.isPresent());
        assertSame(handle, found.get().handle());
        assertEquals("Trade", found.get().projectName()); //$NON-NLS-1$
    }

    @Test
    public void everyRegistrationGetsItsOwnId()
    {
        ComparisonSessionRegistry registry = registry();

        String first = registry.register(handle("Trade"), batch()); //$NON-NLS-1$
        String second = registry.register(handle("Trade"), batch()); //$NON-NLS-1$

        assertFalse(first.equals(second));
        assertEquals(2, registry.size());
    }

    /**
     * The TTL is the whole reason abandoned comparisons do not pin a virtual project for the life
     * of the workbench. A session untouched for longer than the TTL is released - which is the
     * platform call that actually gives the resources back.
     */
    @Test
    public void theSweepReleasesASessionThatSatIdlePastItsTtl()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        clock.now += TTL + 1;
        int swept = registry.sweep();

        assertEquals(1, swept);
        assertEquals(Collections.singletonList(id), releaser.released);
        assertEquals(0, registry.size());
        assertFalse(registry.find(id).isPresent());
    }

    @Test
    public void theSweepLeavesASessionThatIsStillWithinItsTtl()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        clock.now += TTL - 1;

        assertEquals(0, registry.sweep());
        assertTrue(releaser.released.isEmpty());
        assertTrue(registry.find(id).isPresent());
    }

    /** Using a comparison must keep it alive; otherwise a long read would be swept mid-way. */
    @Test
    public void aLookupResetsTheIdleClock()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        clock.now += TTL - 1;
        assertTrue(registry.find(id).isPresent());
        clock.now += TTL - 1;

        assertEquals(0, registry.sweep());
        assertTrue(releaser.released.isEmpty());
    }

    /**
     * The bundle stopping is the third release path. Everything goes back, in one call, so a
     * comparison cannot outlive the server that started it.
     */
    @Test
    public void releaseAllReleasesEverySession()
    {
        ComparisonSessionRegistry registry = registry();
        String first = registry.register(handle("Trade"), batch()); //$NON-NLS-1$
        String second = registry.register(handle("Erp"), batch()); //$NON-NLS-1$

        assertEquals(2, registry.releaseAll());

        assertEquals(Arrays.asList(first, second), releaser.released);
        assertEquals(0, registry.size());
    }

    /**
     * A release that throws must not strand the sessions behind it: {@code releaseAll} runs on the
     * way out of the bundle, and one bad handle would otherwise leak every later one.
     * <p>
     * It counts NONE of them, and that is the half worth pinning: the count is "how many were
     * confirmed free", so a hand-back that failed may not be added to it. The map is still cleared,
     * because keeping a record so a later call can retry means nothing when there will be no later
     * call.
     */
    @Test
    public void aFailingReleaseDoesNotStopTheRest()
    {
        ComparisonSessionRegistry registry = registry();
        releaser.explode = true;
        String first = registry.register(handle("Trade"), batch()); //$NON-NLS-1$
        String second = registry.register(handle("Erp"), batch()); //$NON-NLS-1$

        assertEquals("a hand-back that failed is not a session confirmed free", 0, //$NON-NLS-1$
            registry.releaseAll());

        assertEquals(Arrays.asList(first, second), releaser.released);
        assertEquals(0, registry.size());
    }

    @Test
    public void releasingOneLeavesTheOthersAlone()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle keptHandle = handle("Erp"); //$NON-NLS-1$
        String first = registry.register(handle("Trade"), batch()); //$NON-NLS-1$
        String second = registry.register(keptHandle, batch());
        liveHandles.live = Collections.singletonList(keptHandle);

        assertEquals(SlotHandback.Verdict.FREED,
            registry.handBack(first, SlotHandback.Ending.CLOSED).verdict());

        assertEquals(Collections.singletonList(first), releaser.released);
        assertTrue(registry.find(second).isPresent());
    }

    // ============ A shut-down registry owns nothing and may not be given anything ============

    /**
     * Shutting the bundle down releases the sessions it can SEE; a launch worker still in flight -
     * stuck resolving a revision while the executor's grace ran out - reaches register() after
     * that walk. Its session would land in a registry nobody sweeps again, so the comparison it is
     * about to start would hold EDT's single slot until the JVM exits under an id nothing can
     * name. The refusal is the registry's own, so it cannot be lost to timing.
     */
    @Test
    public void registeringIsRefusedOnceTheRegistryHasBeenShutDown()
    {
        ComparisonSessionRegistry registry = registry();
        registry.closeAndReleaseAll();

        try
        {
            registry.register(handle("Trade"), batch()); //$NON-NLS-1$
            fail("a shut-down registry must refuse to own a comparison"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            assertTrue("the refusal must say nothing was started", //$NON-NLS-1$
                expected.getMessage().contains("Nothing was started")); //$NON-NLS-1$
        }
        assertEquals(0, registry.size());
    }

    /** Shutting down still releases what was there: closing is added to releaseAll, not instead. */
    @Test
    public void shuttingDownReleasesEverySessionItHolds()
    {
        ComparisonSessionRegistry registry = registry();
        String first = registry.register(handle("Trade"), batch()); //$NON-NLS-1$
        String second = registry.register(handle("Erp"), batch()); //$NON-NLS-1$

        assertEquals(2, registry.closeAndReleaseAll());

        assertEquals(Arrays.asList(first, second), releaser.released);
        assertEquals(0, registry.size());
    }

    @Test
    public void releasingAnUnknownIdReleasesNothing()
    {
        ComparisonSessionRegistry registry = registry();

        assertEquals(SlotHandback.Verdict.NOT_REGISTERED,
            registry.handBack("cmp-does-not-exist", SlotHandback.Ending.CLOSED).verdict()); //$NON-NLS-1$
        assertEquals(SlotHandback.Verdict.NOT_REGISTERED,
            registry.handBack(null, SlotHandback.Ending.CLOSED).verdict());
        assertTrue(releaser.released.isEmpty());
    }

    /**
     * Liveness is ASKED of EDT, not remembered. This is the difference between the registry and the
     * cached job result it replaces: a comparison can end without going through this server - EDT
     * restarts a session, a user cancels one in the workbench - and a lookup that trusted its own
     * map would hand back a handle whose store is already closed.
     */
    @Test
    public void aLookupAsksEdtWhichHandlesItStillHolds()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        assertTrue(registry.find(id).isPresent());

        assertEquals("the answer must come from EDT on every lookup, not from the map", 1, //$NON-NLS-1$
            liveHandles.asked);
    }

    /**
     * When EDT no longer lists the handle, the record is dropped - and deliberately NOT released:
     * there is nothing left to give back, and asking the platform to cancel a handle it has already
     * forgotten is not a no-op everywhere.
     */
    @Test
    public void aSessionEdtHasForgottenIsDroppedWithoutBeingReleased()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        // Seen alive FIRST: an absence is a disappearance only after a presence, so the sequence
        // this test is about - EDT had it, EDT lost it - has to include the "had it" half.
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        assertTrue(registry.find(id).isPresent());

        liveHandles.live = Collections.emptyList();

        assertFalse(registry.find(id).isPresent());

        assertTrue(releaser.released.isEmpty());
        assertEquals(0, registry.size());
    }

    /**
     * The defect this rule exists for: EDT's {@code startComparison} SCHEDULES the launch, so the
     * handle can be missing from {@code getHandles} for a moment after the registration. A poll
     * that lands in that window used to take the session's ownership away and report the
     * comparison cancelled - while the platform went on to start it, unowned, holding EDT's single
     * slot with nothing left able to reach it.
     */
    @Test
    public void aSessionEdtHasNotListedYetIsNotDeclaredGone()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        String id = registry.register(handle, batch());
        liveHandles.live = Collections.emptyList();

        assertTrue("a launch that has not surfaced yet is not a launch that ended", //$NON-NLS-1$
            registry.find(id).isPresent());
        assertEquals(1, registry.size());
        assertTrue("and nothing may be handed back on the strength of it", //$NON-NLS-1$
            releaser.released.isEmpty());

        // ... and the moment EDT does list it, the ordinary rule applies again.
        liveHandles.live = Collections.singletonList(handle);
        assertTrue(registry.find(id).isPresent());
        liveHandles.live = Collections.emptyList();
        assertFalse("once seen, an absence IS a disappearance", registry.find(id).isPresent()); //$NON-NLS-1$
    }

    /**
     * The same window, asked the question that decides whether a second launch is refused. A
     * scheduled comparison holds EDT's single slot as surely as a running one, so it has to be
     * NAMED here - answering "nothing is running" would let a second launch start on top of it.
     */
    @Test
    public void aSessionEdtHasNotListedYetStillHoldsTheSlot()
    {
        ComparisonSessionRegistry registry = registry();
        String id = registry.register(handle("Trade"), batch()); //$NON-NLS-1$
        liveHandles.live = Collections.emptyList();

        assertEquals(id, registry.activeComparisonId());
        assertEquals(Collections.singletonList(id), registry.ids());
    }

    /**
     * "Could not ask" is not "not there". When the lookup itself fails, the session stays and the
     * next real call reports the platform's own message - rather than this registry inventing the
     * conclusion that the comparison is gone.
     */
    @Test
    public void aLookupThatCannotAskDoesNotDeclareTheSessionGone()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        String id = registry.register(handle, batch());
        liveHandles.failure = new IllegalStateException("workspace is closed"); //$NON-NLS-1$

        Optional<ComparisonSession> found = registry.find(id);

        assertTrue(found.isPresent());
        assertSame(handle, found.get().handle());
        assertEquals(1, registry.size());
    }

    @Test
    public void anUnknownIdIsSimplyNotFound()
    {
        ComparisonSessionRegistry registry = registry();

        assertFalse(registry.find("cmp-999").isPresent()); //$NON-NLS-1$
        assertFalse(registry.find(null).isPresent());
        assertEquals("an unknown id must not consult EDT at all", 0, liveHandles.asked); //$NON-NLS-1$
    }

    @Test
    public void idsAndListReportTheRegisteredSessionsOldestFirst()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle firstHandle = handle("Trade"); //$NON-NLS-1$
        ComparisonProcessHandle secondHandle = handle("Erp"); //$NON-NLS-1$
        String first = registry.register(firstHandle, batch());
        String second = registry.register(secondHandle, batch());
        liveHandles.live = Arrays.asList(firstHandle, secondHandle);

        assertEquals(Arrays.asList(first, second), registry.ids());
        assertEquals(Arrays.asList(first, second),
            Arrays.asList(registry.list().get(0).comparisonId(), registry.list().get(1).comparisonId()));
    }

    /**
     * {@code ids()} is what an "unknown comparison" refusal quotes back to the caller, so it must
     * answer the same liveness question as every other lookup. A comparison that ended in the
     * workbench is still in our map; naming it would send the caller to re-quote an id EDT has
     * already forgotten.
     */
    @Test
    public void idsDoNotNameASessionEdtHasForgotten()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle forgotten = handle("Trade"); //$NON-NLS-1$
        ComparisonProcessHandle held = handle("Erp"); //$NON-NLS-1$
        liveHandles.live = Arrays.asList(forgotten, held);
        String forgottenId = registry.register(forgotten, batch());
        String heldId = registry.register(held, batch());
        assertEquals(Arrays.asList(forgottenId, heldId), registry.ids());
        // EDT stops listing the first one - it ended without going through us.
        liveHandles.live = Collections.singletonList(held);

        assertEquals(Collections.singletonList(heldId), registry.ids());
        assertEquals("the forgotten session must not stay in the map either", 1, registry.size()); //$NON-NLS-1$
        assertFalse(registry.find(forgottenId).isPresent());
    }

    /**
     * Naming a session in an error message is not use of it. If listing the ids counted as a touch,
     * a comparison nobody can reach any more would postpone its own TTL for as long as callers kept
     * quoting bad ids at it.
     */
    @Test
    public void listingTheIdsDoesNotPostponeTheTtl()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        clock.now += TTL - 1;
        assertEquals(Collections.singletonList(id), registry.ids());
        clock.now += 2;

        assertEquals(1, registry.sweep());
        assertEquals(Collections.singletonList(id), releaser.released);
    }

    /**
     * "Could not ask" is not "not there" here either: a failing liveness lookup must not silently
     * shorten the list of ids a refusal offers.
     */
    @Test
    public void idsThatCannotBeCheckedAreStillNamed()
    {
        ComparisonSessionRegistry registry = registry();
        String id = registry.register(handle("Trade"), batch()); //$NON-NLS-1$
        liveHandles.failure = new IllegalStateException("workspace is closed"); //$NON-NLS-1$

        assertEquals(Collections.singletonList(id), registry.ids());
        assertEquals(1, registry.size());
    }

    @Test
    public void theHandleAndTheBatchComeBackById()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        CompareMergeProcessBatch batch = batch();
        liveHandles.live = Collections.singletonList(handle);

        String id = registry.register(handle, batch);

        assertSame(handle, registry.handle(id));
        assertSame(batch, registry.batch(id));
    }

    /**
     * A handle EDT has forgotten must not come back out of the map. The batch goes with it: a poll
     * that still had the batch would keep reading a failure cause for a comparison that no longer
     * exists.
     */
    @Test
    public void aForgottenSessionYieldsNeitherHandleNorBatch()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        assertNotNull(registry.handle(id));

        liveHandles.live = Collections.emptyList();

        assertNull(registry.handle(id));
        assertNull(registry.batch(id));
    }

    @Test
    public void anUnknownIdYieldsNeitherHandleNorBatch()
    {
        ComparisonSessionRegistry registry = registry();

        assertNull(registry.handle("cmp-999")); //$NON-NLS-1$
        assertNull(registry.batch("cmp-999")); //$NON-NLS-1$
    }

    /**
     * EDT runs one comparison per instance, so a refusal has to be able to NAME the one in the way.
     * The answer is the most recently registered session EDT still holds; the ones it has forgotten
     * are dropped on the way past.
     */
    @Test
    public void theActiveComparisonIsTheMostRecentOneEdtStillHolds()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle first = handle("Trade"); //$NON-NLS-1$
        ComparisonProcessHandle second = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Arrays.asList(first, second);
        String firstId = registry.register(first, batch());
        String secondId = registry.register(second, batch());
        assertEquals(secondId, registry.activeComparisonId());
        liveHandles.live = Collections.singletonList(second);

        assertEquals(secondId, registry.activeComparisonId());

        assertFalse("the session EDT forgot must not stay in the map", //$NON-NLS-1$
            registry.ids().contains(firstId));
    }

    /**
     * No live session means no id - and that is information rather than a gap: when EDT reports a
     * comparison active while this returns {@code null}, the comparison was started outside this
     * server and only EDT can end it. Inventing an id here would send the caller to cancel_job with
     * something that names nothing.
     */
    @Test
    public void thereIsNoActiveComparisonWhenEdtHoldsNone()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        registry.register(handle, batch());
        assertNotNull(registry.activeComparisonId());

        liveHandles.live = Collections.emptyList();

        assertNull(registry.activeComparisonId());
        assertEquals(0, registry.size());
    }

    // === the sweep is reached, not merely available ===

    /**
     * The TTL only reclaims anything if something actually runs it, and nothing in production
     * calls a sweep by hand: the reclamation is part of the lookup. A comparison that finished,
     * was read once and then abandoned is given back by the NEXT question asked of the registry -
     * here, a lookup of an entirely different id.
     */
    @Test
    public void aLookupOfAnotherIdReleasesTheSessionThatSatIdlePastItsTtl()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle abandoned = handle("Trade"); //$NON-NLS-1$
        ComparisonProcessHandle fresh = handle("Erp"); //$NON-NLS-1$
        liveHandles.live = Arrays.asList(abandoned, fresh);
        String abandonedId = registry.register(abandoned, batch());

        clock.now += TTL + 1;
        String freshId = registry.register(fresh, batch());

        assertTrue(registry.find(freshId).isPresent());
        assertEquals("the abandoned session must be released by the lookup itself", //$NON-NLS-1$
            Collections.singletonList(abandonedId), releaser.released);
        assertEquals(1, registry.size());
    }

    /**
     * Looking a session up cannot revive it. If the touch came first, a caller returning after the
     * TTL - or a poll that arrived late - would buy the abandoned comparison another full TTL, and
     * an id nobody uses would keep EDT's single slot forever by being asked about.
     */
    @Test
    public void aLookupThatArrivesAfterTheTtlDoesNotReviveTheSession()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        clock.now += TTL + 1;

        assertFalse(registry.find(id).isPresent());
        assertEquals(Collections.singletonList(id), releaser.released);
        assertEquals(0, registry.size());
    }

    /**
     * The refusal path. EDT runs one comparison per instance, so the id this answers with is the
     * one a second launch is refused for; an abandoned session must be handed back HERE rather
     * than named, or it blocks every later launch for as long as EDT runs.
     */
    @Test
    public void namingTheActiveComparisonReleasesAnExpiredOneInsteadOfQuotingIt()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        clock.now += TTL + 1;

        assertNull(registry.activeComparisonId());
        assertEquals(Collections.singletonList(id), releaser.released);
    }

    /**
     * {@code ids()} is what an "unknown comparison" refusal offers the caller, so it must not send
     * them to re-quote an id whose session this very call was entitled to release.
     */
    @Test
    public void listingTheIdsReleasesAnExpiredSessionInsteadOfOfferingIt()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        clock.now += TTL + 1;

        assertTrue(registry.ids().isEmpty());
        assertEquals(Collections.singletonList(id), releaser.released);
    }

    /**
     * The negative control for the three above: a lookup reclaims what EXPIRED and nothing else.
     * Without this, a sweep-on-every-lookup that simply released everything would pass them all
     * while destroying live comparisons mid-read.
     */
    @Test
    public void aLookupLeavesASessionThatIsStillWithinItsTtlAlone()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        clock.now += TTL - 1;

        assertTrue(registry.find(id).isPresent());
        assertEquals(id, registry.activeComparisonId());
        assertEquals(Collections.singletonList(id), registry.ids());
        assertTrue(releaser.released.isEmpty());
        assertEquals(1, registry.size());
    }

    // === a release says what it achieved, not merely that a record existed ===

    /**
     * The defect: {@code release} dropped the map entry, swallowed whatever the stop threw and
     * answered {@code true} regardless, and the tool turned that into "EDT's single comparison slot
     * is free again". A caller acting on that sentence launches into a comparison that never
     * stopped.
     * <p>
     * The record now STAYS. That reverses what this test used to assert, and the reversal is the
     * invariant: a record is dropped exactly when the slot is CONFIRMED free. Dropping it here left
     * a comparison EDT still held with no id able to address it - not even by
     * {@code releaseComparisonId}, the one remedy - while the refusal that names the live
     * comparison could no longer name this one either.
     */
    @Test
    public void aReleaseWhoseStopFailedIsNotReportedAsAStop()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        releaser.explode = true;

        SlotHandback handback = registry.handBack(id, SlotHandback.Ending.CLOSED);

        assertEquals(SlotHandback.Verdict.NOT_FREED, handback.verdict());
        assertFalse("a failed hand-back is not a free slot", handback.slotIsFree()); //$NON-NLS-1$
        assertTrue("and the record is kept so the caller can retry", handback.recordKept()); //$NON-NLS-1$
        assertEquals("the stop was attempted", Collections.singletonList(id), releaser.released); //$NON-NLS-1$
        assertEquals("the session is still registered, so it can still be named and retried", 1, //$NON-NLS-1$
            registry.size());
        assertEquals("and it still holds EDT's single slot as far as anybody here knows", id, //$NON-NLS-1$
            registry.activeComparisonId());
    }

    /**
     * The hand-back that never reached the platform, which is the finding this whole construction
     * was rebuilt around.
     * <p>
     * The tool's cancellation path used to drop the record unconditionally in its
     * SERVICE-UNAVAILABLE branch - a comparison EDT was still running lost the only id that could
     * reach it, and the sentence beside it argued that dropping it was the safe thing to do. It is
     * told apart from an ordinary refusal because the caller's move differs: this one is retried
     * once EDT has finished starting.
     */
    @Test
    public void aHandBackThatNeverReachedThePlatformKeepsTheRecordAndSaysSo()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        releaser.serviceGone = true;

        SlotHandback handback = registry.handBack(id, SlotHandback.Ending.CANCELLED);

        assertEquals(SlotHandback.Verdict.UNREACHABLE, handback.verdict());
        assertFalse("nothing reached EDT, so nothing may be claimed about its slot", //$NON-NLS-1$
            handback.slotIsFree());
        assertTrue(handback.recordKept());
        assertEquals("the session must survive a request the platform never received", 1, //$NON-NLS-1$
            registry.size());
        assertTrue("and stay addressable, because retrying is the only way back", //$NON-NLS-1$
            registry.find(id).isPresent());
    }

    /**
     * The sentence is the value's, not the caller's - so a failure cannot be lost by a caller
     * writing nothing about it. Each verdict is pinned separately: JUnit stops a method at its
     * first failed assertion, so one method would only ever load-bear on its first pin.
     */
    @Test
    public void aFailedHandBackSaysTheSlotMayStillBeHeld()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        releaser.explode = true;

        String sentence = registry.handBack(id, SlotHandback.Ending.CLOSED).sentence();

        assertTrue("it must not claim the slot is free: " + sentence, //$NON-NLS-1$
            sentence.contains("do NOT assume")); //$NON-NLS-1$
        assertTrue("it must name the remedy: " + sentence, //$NON-NLS-1$
            sentence.contains("releaseComparisonId=" + '\'' + id)); //$NON-NLS-1$
        assertTrue("it must name the comparison: " + sentence, sentence.contains(id)); //$NON-NLS-1$
    }

    @Test
    public void aHandBackThatNeverReachedThePlatformSaysToRetryIt()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        releaser.serviceGone = true;

        String sentence = registry.handBack(id, SlotHandback.Ending.CLOSED).sentence();

        assertTrue("it must say the comparison was NOT ended: " + sentence, //$NON-NLS-1$
            sentence.contains("was NOT ended")); //$NON-NLS-1$
        assertTrue("it must say the record is kept: " + sentence, //$NON-NLS-1$
            sentence.contains("KEPT")); //$NON-NLS-1$
    }

    /**
     * The positive control for the two above: a hand-back that worked says the slot is free, so the
     * pins on the failures are pins on a difference rather than on a constant.
     */
    @Test
    public void aHandBackThatWorkedSaysTheSlotIsFree()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        SlotHandback handback = registry.handBack(id, SlotHandback.Ending.CLOSED);

        assertTrue(handback.slotIsFree());
        assertFalse(handback.recordKept());
        assertTrue("it must say the slot is free: " + handback.sentence(), //$NON-NLS-1$
            handback.sentence().contains("slot is free again")); //$NON-NLS-1$
    }

    /**
     * The ending picks EDT's verb and nothing else. Both verbs are the same platform operation -
     * measured from ComparisonManager bytecode, they differ in tracing, a telemetry string and a
     * status stamp on the session being discarded - so the ACCOUNTING must not vary with it.
     */
    @Test
    public void theEndingReachesThePlatformAndChangesNothingElse()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle first = handle("Trade"); //$NON-NLS-1$
        ComparisonProcessHandle second = handle("Erp"); //$NON-NLS-1$
        liveHandles.live = Arrays.asList(first, second);
        String cancelled = registry.register(first, batch());
        String closed = registry.register(second, batch());

        SlotHandback afterCancel = registry.handBack(cancelled, SlotHandback.Ending.CANCELLED);
        SlotHandback afterClose = registry.handBack(closed, SlotHandback.Ending.CLOSED);

        assertEquals(Arrays.asList(SlotHandback.Ending.CANCELLED, SlotHandback.Ending.CLOSED),
            releaser.endings);
        assertEquals(afterClose.verdict(), afterCancel.verdict());
        assertEquals(SlotHandback.Verdict.FREED, afterCancel.verdict());
    }

    /**
     * The other way a release achieves no stop: EDT no longer holds the handle. Nothing is asked of
     * the platform then - cancelling a handle it has already forgotten is not a no-op everywhere -
     * so a stop is not claimed either.
     */
    @Test
    public void releasingASessionEdtHasForgottenStopsNothingAndSaysSo()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        assertTrue(registry.find(id).isPresent());
        liveHandles.live = Collections.emptyList();

        SlotHandback handback = registry.handBack(id, SlotHandback.Ending.CLOSED);

        assertEquals(SlotHandback.Verdict.ALREADY_FREE, handback.verdict());
        assertTrue("a comparison EDT has already forgotten leaves the slot free", //$NON-NLS-1$
            handback.slotIsFree());
        assertTrue("a handle the platform has forgotten must not be handed back to it", //$NON-NLS-1$
            releaser.released.isEmpty());
        assertEquals(0, registry.size());
    }

    /**
     * What ALREADY_FREE actually observed is the absence of ONE handle - ours. The slot is
     * EDT-wide: the platform drops its active batch the moment any comparison ends, and a
     * comparison launched from EDT's own comparison window is never registered here, so it would
     * hold the slot under no id this server knows. The sentence used to close with "EDT's single
     * comparison slot is free", which is the one clause a caller ACTS on, and it was reached from
     * an observation that cannot support it. Three separate methods, because JUnit stops a method
     * at its first failed assertion.
     */
    @Test
    public void anAlreadyFreeHandBackDoesNotClaimTheSlotItselfIsFree()
    {
        String sentence = alreadyFreeSentence();

        assertFalse("absence of OUR handle is not a reading of the slot: " + sentence, //$NON-NLS-1$
            sentence.contains("slot is free")); //$NON-NLS-1$
    }

    @Test
    public void anAlreadyFreeHandBackSaysWhichComparisonStoppedOccupyingTheSlot()
    {
        String sentence = alreadyFreeSentence();

        assertTrue("it must still say what WAS established: " + sentence, //$NON-NLS-1$
            sentence.contains("does not occupy")); //$NON-NLS-1$
    }

    @Test
    public void anAlreadyFreeHandBackSaysTheSlotItselfWasNotAskedAbout()
    {
        String sentence = alreadyFreeSentence();

        assertTrue("the unasked question has to be named, not left to be assumed: " + sentence, //$NON-NLS-1$
            sentence.contains("NOT asked")); //$NON-NLS-1$
    }

    /** @return the sentence of a hand-back for a session EDT has already forgotten */
    private String alreadyFreeSentence()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        // The lookup is part of the fixture, not decoration: "gone" means EDT was seen holding the
        // handle FIRST, so without one reading that saw it live, a later absence is read as "not
        // listed yet" and the hand-back reaches the platform instead.
        assertTrue(registry.find(id).isPresent());
        liveHandles.live = Collections.emptyList();

        SlotHandback handback = registry.handBack(id, SlotHandback.Ending.CLOSED);

        assertEquals(SlotHandback.Verdict.ALREADY_FREE, handback.verdict());
        return handback.sentence();
    }

    /** The positive control: a live session that stops cleanly is the one case that IS a release. */
    @Test
    public void releasingALiveSessionReportsItReleased()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        assertEquals(SlotHandback.Verdict.FREED,
            registry.handBack(id, SlotHandback.Ending.CLOSED).verdict());

        assertEquals(Collections.singletonList(id), releaser.released);
        assertEquals(0, registry.size());
    }

    /**
     * With no facade installed there is nobody to release a session, so the stand-in REFUSES to
     * take one rather than accepting it into a map that {@code EdtServices.dispose()} will never
     * see. Silently accepting would leak the comparison's virtual project for the life of EDT -
     * exactly the failure this registry exists to prevent.
     */
    @Test
    public void theDetachedRegistryFindsNothingAndRefusesToRegister()
    {
        ComparisonEngine.uninstall();
        ComparisonSessionRegistry shared = ComparisonSessionRegistry.shared();

        assertNull(shared.handle("cmp-1")); //$NON-NLS-1$
        assertNull(shared.activeComparisonId());
        assertEquals(SlotHandback.Verdict.NOT_REGISTERED,
            shared.handBack("cmp-1", SlotHandback.Ending.CLOSED).verdict()); //$NON-NLS-1$

        try
        {
            shared.register(handle("Trade"), batch()); //$NON-NLS-1$
            fail("the detached registry must refuse to own a session"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue("the refusal must say why: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("facade")); //$NON-NLS-1$
        }
    }

    // ==================== "Could not ask" is not "it is gone" ====================

    /**
     * The defect this pins, measured: {@code ManagerBackend.handles} answered an EMPTY LIST when
     * EDT's comparison service was unregistered or the project did not resolve, and that reading
     * arrived here indistinguishable from EDT saying "I no longer hold that handle". The registry
     * then treated a momentary service gap as proof the comparison had ended: it dropped the
     * record WITHOUT stopping anything, and the comparison went on holding EDT's single slot with
     * no id left able to address it.
     */
    @Test
    public void aSessionSurvivesAPlatformThatCouldNotBeAskedAboutIt()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        // Seen alive once, so a LATER absence would count - this is the state in which the defect
        // fired, and without it the never-seen latch would mask the answer being tested.
        assertTrue(registry.find(id).isPresent());

        liveHandles.reachable = false;

        assertTrue("the session must survive a question nobody could ask", //$NON-NLS-1$
            registry.find(id).isPresent());
        assertEquals(id, registry.activeComparisonId());
        assertEquals(Collections.singletonList(id), registry.ids());
        assertEquals(1, registry.size());
        assertTrue("and nothing may be handed back on the strength of an unasked question", //$NON-NLS-1$
            releaser.released.isEmpty());
    }

    /**
     * The control for the test above, and the reason it is not satisfied by a registry that simply
     * never reclaims: EDT ANSWERING that it no longer holds the handle still drops the record.
     */
    @Test
    public void aSessionEdtAnsweredAboutAndDoesNotHoldIsStillDropped()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        assertTrue(registry.find(id).isPresent());

        liveHandles.live = Collections.emptyList();

        assertFalse(registry.find(id).isPresent());
        assertEquals(0, registry.size());
    }

    /**
     * A release attempt that could not be made must not be reported as an already-ended comparison
     * either: with EDT unreachable the liveness question is unanswered, so the handle IS handed
     * back rather than assumed gone, and the failing hand-back is what the verdict names.
     * <p>
     * Attempting it is deliberate and not an accident of ordering. The liveness question also goes
     * unanswered when the PROJECT fails to resolve, with EDT's service perfectly well registered;
     * skipping the hand-back on an unanswered question would strand a comparison the platform
     * would have ended without complaint.
     */
    @Test
    public void aReleaseWhilePlatformIsUnreachableIsNotClaimedAsAnAlreadyEndedComparison()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        assertTrue(registry.find(id).isPresent());
        liveHandles.reachable = false;
        releaser.explode = true;

        SlotHandback handback = registry.handBack(id, SlotHandback.Ending.CLOSED);

        assertEquals(SlotHandback.Verdict.NOT_FREED, handback.verdict());
        assertEquals("the hand-back was attempted, not skipped as 'already gone'", //$NON-NLS-1$
            Collections.singletonList(id), releaser.released);
        assertEquals("and the record is kept, because nothing was confirmed free", 1, //$NON-NLS-1$
            registry.size());
    }

    // ============ a read in flight is not idle ============

    /**
     * The finding: a tree read is ONE lookup followed by minutes of BM work, and the sweep measures
     * idleness from that lookup. A comparison big enough to outlast the idle TTL was therefore
     * ended by the sweep underneath the transaction walking it - a failure the caller sees as the
     * platform throwing inside its own read.
     */
    @Test
    public void aSweepDoesNotEndAComparisonThatIsBeingRead()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        try (ComparisonSessionRegistry.Lease lease = registry.lease(id))
        {
            assertTrue(lease.held());
            assertEquals(handle, lease.handle());
            clock.now += TTL + 1;

            assertEquals("a leased session is not idle", 0, registry.sweep()); //$NON-NLS-1$
            assertTrue("and must not have been handed back", releaser.released.isEmpty()); //$NON-NLS-1$
            assertEquals(1, registry.size());
        }
    }

    /**
     * The control: the very same session, at the very same clock, IS reclaimed once the read ends.
     * Without this the test above would pass on a registry that had simply stopped sweeping.
     */
    @Test
    public void aSweepReclaimsTheSameSessionOnceTheReadHasEnded()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        ComparisonSessionRegistry.Lease lease = registry.lease(id);
        clock.now += TTL + 1;
        lease.close();
        // Closing TOUCHES the session, so the TTL restarts from the end of the read: a read that
        // outlasted the TTL has just proved the comparison is in use, and reclaiming it on the very
        // next sweep would only move the defect one call later.
        assertEquals("the TTL restarts when the read ends", 0, registry.sweep()); //$NON-NLS-1$

        clock.now += TTL + 1;

        assertEquals(1, registry.sweep());
        assertEquals(Collections.singletonList(id), releaser.released);
    }

    /** An unknown id leases nothing, and says so rather than pretending to hold something. */
    @Test
    public void leasingAnUnknownComparisonHoldsNothing()
    {
        ComparisonSessionRegistry registry = registry();

        try (ComparisonSessionRegistry.Lease lease = registry.lease("cmp-nope")) //$NON-NLS-1$
        {
            assertFalse(lease.held());
            assertNull(lease.handle());
            assertNull(lease.comparisonId());
        }
    }

    /**
     * Closing twice must not decrement the count twice: a try-with-resources around an explicit
     * close is ordinary, and a second decrement would expose a read that is still running.
     */
    @Test
    public void closingALeaseTwiceDoesNotReleaseSomebodyElsesHold()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        ComparisonSessionRegistry.Lease outer = registry.lease(id);
        ComparisonSessionRegistry.Lease inner = registry.lease(id);
        inner.close();
        inner.close();
        clock.now += TTL + 1;

        assertEquals("the outer read still holds it", 0, registry.sweep()); //$NON-NLS-1$

        outer.close();
        clock.now += TTL + 1;

        assertEquals(1, registry.sweep());
    }

    // ============ The sweep may not lose what it could not give back ============

    /**
     * The defect: the sweep removed every expired record unconditionally and discarded what the
     * hand-back reported. A TTL that fell while the service was away, or a stop that threw, made
     * the session vanish from this map while its virtual project and private BM store could still
     * be open - {@link ComparisonSessionRegistry#activeComparisonId()} then answered "nothing
     * holds the slot", the next launch was let through, and EDT's one-comparison-per-instance
     * assertion refused it with no sentence anybody could act on.
     */
    @Test
    public void anExpiredSessionThatCouldNotBeGivenBackStaysRegistered()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        assertTrue(registry.find(id).isPresent());
        releaser.explode = true;
        clock.now += TTL + 1L;

        assertEquals("nothing was reclaimed, so nothing may be counted", 0, registry.sweep()); //$NON-NLS-1$

        assertEquals("the stop WAS attempted", Collections.singletonList(id), releaser.released); //$NON-NLS-1$
        assertEquals("and the record stays: it may still hold the slot", 1, registry.size()); //$NON-NLS-1$
        assertEquals("so a refusal can still name it, with a remedy attached", id, //$NON-NLS-1$
            registry.activeComparisonId());
    }

    /** The next sweep retries, so a session stranded by a passing failure reclaims itself. */
    @Test
    public void aSweepRetriesAHandBackThatFailedBefore()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        assertTrue(registry.find(id).isPresent());
        releaser.explode = true;
        clock.now += TTL + 1L;
        assertEquals(0, registry.sweep());

        releaser.explode = false;

        assertEquals(1, registry.sweep());
        assertEquals(0, registry.size());
        assertNull(registry.activeComparisonId());
    }

    /**
     * The positive control for both: a sweep that CAN give the session back still reclaims it, so
     * the tests above are not passed by a sweep that stopped reclaiming anything at all.
     */
    @Test
    public void anExpiredSessionThatWasGivenBackIsStillReclaimed()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        assertTrue(registry.find(id).isPresent());
        clock.now += TTL + 1L;

        assertEquals(1, registry.sweep());

        assertEquals(Collections.singletonList(id), releaser.released);
        assertEquals(0, registry.size());
    }

    /**
     * An expired session EDT has already forgotten is dropped with NO hand-back: there is nothing
     * to give back, and asking the platform to stop a handle it no longer knows is not a no-op
     * everywhere.
     */
    @Test
    public void anExpiredSessionEdtHasForgottenIsDroppedWithoutBeingStopped()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        assertTrue(registry.find(id).isPresent());
        liveHandles.live = Collections.emptyList();
        clock.now += TTL + 1L;

        assertEquals(1, registry.sweep());

        assertTrue(releaser.released.isEmpty());
        assertEquals(0, registry.size());
        assertNull(registry.activeComparisonId());
    }

    // ============ "Not yet started" is visible to a poll loop ============

    /**
     * A poll loop has to tell "EDT has not begun the comparison yet" from "EDT will not answer for
     * this comparison", and the latch is the only authority on the first. It is exposed on the
     * session because a loop that spends its unreadable-tick budget on a scheduled-but-unstarted
     * launch cancels a perfectly healthy comparison.
     */
    @Test
    public void aSessionSaysWhetherEdtHasEverListedIt()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.emptyList();
        String id = registry.register(handle, batch());

        assertFalse("EDT has not listed it, so the launch has not surfaced yet", //$NON-NLS-1$
            registry.find(id).get().seenAliveByEdt());

        liveHandles.live = Collections.singletonList(handle);

        assertTrue(registry.find(id).get().seenAliveByEdt());
    }
}
