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
import com.ditrix.edt.mcp.server.utils.compare.ComparisonSessionRegistry.ReleaseOutcome;

/**
 * Unit tests for {@link ComparisonSessionRegistry}.
 * <p>
 * The registry exists because a comparison's resources - a virtual project and a private BM store -
 * are handed back only by {@code cancel}/{@code stop}, and the obvious place to park the handle
 * cannot own that: background-job records are evicted by a bare map removal with no dispose hook.
 * These tests pin the three release paths and, separately, that liveness is ASKED of EDT rather
 * than remembered.
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

    /** Records what the registry released, and can be told to fail. */
    private static final class RecordingReleaser
        implements ComparisonSessionRegistry.Releaser
    {
        final List<String> released = new ArrayList<>();
        boolean explode;

        @Override
        public void release(ComparisonSession session)
        {
            released.add(session.comparisonId());
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

        @Override
        public List<ComparisonProcessHandle> forProject(String projectName)
        {
            asked++;
            if (failure != null)
            {
                throw failure;
            }
            return live;
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
     */
    @Test
    public void aFailingReleaseDoesNotStopTheRest()
    {
        ComparisonSessionRegistry registry = registry();
        releaser.explode = true;
        String first = registry.register(handle("Trade"), batch()); //$NON-NLS-1$
        String second = registry.register(handle("Erp"), batch()); //$NON-NLS-1$

        assertEquals(2, registry.releaseAll());

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

        assertEquals(ReleaseOutcome.RELEASED, registry.release(first));

        assertEquals(Collections.singletonList(first), releaser.released);
        assertTrue(registry.find(second).isPresent());
    }

    @Test
    public void releasingAnUnknownIdReleasesNothing()
    {
        ComparisonSessionRegistry registry = registry();

        assertEquals(ReleaseOutcome.NOT_REGISTERED, registry.release("cmp-does-not-exist")); //$NON-NLS-1$
        assertEquals(ReleaseOutcome.NOT_REGISTERED, registry.release(null));
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
     */
    @Test
    public void aReleaseWhoseStopFailedIsNotReportedAsAStop()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        releaser.explode = true;

        assertEquals(ReleaseOutcome.NOT_STOPPED, registry.release(id));

        assertEquals("the stop was attempted", Collections.singletonList(id), releaser.released); //$NON-NLS-1$
        assertEquals("and the record still goes, or it would pin the slot for its whole TTL", 0, //$NON-NLS-1$
            registry.size());
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

        assertEquals(ReleaseOutcome.NOT_STOPPED, registry.release(id));

        assertTrue("a handle the platform has forgotten must not be handed back to it", //$NON-NLS-1$
            releaser.released.isEmpty());
        assertEquals(0, registry.size());
    }

    /** The positive control: a live session that stops cleanly is the one case that IS a release. */
    @Test
    public void releasingALiveSessionReportsItReleased()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());

        assertEquals(ReleaseOutcome.RELEASED, registry.release(id));

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
        assertEquals(ReleaseOutcome.NOT_REGISTERED, shared.release("cmp-1")); //$NON-NLS-1$

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
}
