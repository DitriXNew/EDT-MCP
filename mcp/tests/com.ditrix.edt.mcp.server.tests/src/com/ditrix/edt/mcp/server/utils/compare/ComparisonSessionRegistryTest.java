/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

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

    /**
     * How many polls the platform-start budget is worth, mirroring the registry's own
     * {@code PLATFORM_START_BUDGET_MILLIS / PLATFORM_START_POLL_MILLIS}. Only ever used as an
     * ORDER of magnitude here - the point being pinned is one budget against three, not the
     * budget's value.
     */
    private static final int PLATFORM_START_BUDGET_POLLS = 200;

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

    /**
     * A settable elapsed-time source, so a TTL can be tested without sleeping through it.
     * <p>
     * The tests move it in MILLISECONDS, which is the scale every budget in the registry is stated
     * in, while the registry takes a NANOSECOND ticker - so {@link #ticker()} is the seam and this
     * field is the dial. Keeping the two apart is what lets a test say
     * {@code clock.now += TTL + 1} and mean it.
     */
    private static final class FakeClock
    {
        long now = 1_000L;

        ElapsedTime.Ticker ticker()
        {
            return () -> now * 1_000_000L;
        }
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

    /**
     * Answers whether EDT has BEGUN a comparison, and counts the pauses the wait spent.
     * <p>
     * The default is "begun": every test written before the hand-back learned to withhold one is
     * about a comparison EDT is running, and answering anything else would silence them all
     * instead of exercising them.
     */
    private static final class FakeLaunchProgress
        implements ComparisonSessionRegistry.LaunchProgress
    {
        Boolean begun = Boolean.TRUE;
        /** Whether EDT can be asked at all; when it cannot, there is no answer to give. */
        boolean reachable = true;
        int asked;
        /** How many times the wait may ask before the answer flips to "begun". */
        int beginsAfterAsks = -1;

        @Override
        public PlatformAnswer<Boolean> hasBegun(ComparisonSession session)
        {
            asked++;
            if (!reachable)
            {
                return PlatformAnswer.unavailable();
            }
            if (beginsAfterAsks >= 0 && asked > beginsAfterAsks)
            {
                return PlatformAnswer.of(Boolean.TRUE);
            }
            return PlatformAnswer.of(begun);
        }
    }

    /** Advances the fake clock instead of sleeping, so the wait is exercised in real time zero. */
    private static final class FakePause
        implements ComparisonSessionRegistry.Pause
    {
        final FakeClock clock;
        int paused;

        /**
         * A one-shot correction applied on the FIRST pause, so a wait can be shown to survive the
         * machine's clock moving under it - the case an absolute deadline cannot get right, because
         * the reading it was computed from no longer exists.
         */
        long stepBackOnFirstPauseMillis;

        FakePause(FakeClock clock)
        {
            this.clock = clock;
        }

        @Override
        public void millis(long millis)
        {
            paused++;
            clock.now += millis;
            if (stepBackOnFirstPauseMillis > 0L)
            {
                clock.now -= stepBackOnFirstPauseMillis;
                stepBackOnFirstPauseMillis = 0L;
            }
        }
    }

    private final FakeClock clock = new FakeClock();
    private final RecordingReleaser releaser = new RecordingReleaser();
    private final FakeLiveHandles liveHandles = new FakeLiveHandles();
    private final FakeLaunchProgress launchProgress = new FakeLaunchProgress();
    private final FakePause pause = new FakePause(clock);

    private ComparisonSessionRegistry registry()
    {
        return new ComparisonSessionRegistry(clock.ticker(), TTL, releaser, liveHandles,
            launchProgress, pause);
    }

    // ==================== The slot is CLAIMED, not merely found free ====================
    //
    // A launch used to ask activeComparisonId() and then register at the far end of its
    // preparation - two git revisions resolved, the project looked up, the batch built. Two
    // launches arriving together both read "free", both spent that minute, and both registered;
    // EDT refused the second batch, but its registration stood and named the slot as taken by a
    // comparison that had never started. These tests pin the claim that replaces the reading.

    @Test
    public void testASecondLaunchIsRefusedWhileTheFirstStillHoldsItsClaim()
    {
        ComparisonSessionRegistry registry = registry();

        SlotClaim first = registry.claimSlot("Trade"); //$NON-NLS-1$
        // Nothing has been registered yet - this is exactly the window the whole preparation used
        // to run in, with the slot free as far as every reading was concerned.
        SlotClaim second = registry.claimSlot("Erp"); //$NON-NLS-1$

        assertTrue("the first launch must get the slot", first.granted()); //$NON-NLS-1$
        assertFalse("and the second must not, even though nothing is registered yet", //$NON-NLS-1$
            second.granted());
        assertEquals("no session exists yet, so nothing may claim one does", 0, registry.size()); //$NON-NLS-1$
    }

    @Test
    public void testTheRefusedLaunchIsToldALaunchIsInFlightAndNotToCancelAComparison()
    {
        ComparisonSessionRegistry registry = registry();
        registry.claimSlot("Trade"); //$NON-NLS-1$

        String message = registry.claimSlot("Erp").refusal().toJson(); //$NON-NLS-1$

        assertTrue("the refusal must name the project being prepared: " + message, //$NON-NLS-1$
            message.contains("Trade")); //$NON-NLS-1$
        assertTrue("and say what is actually happening: " + message, //$NON-NLS-1$
            message.contains("still preparing its comparison")); //$NON-NLS-1$
        assertFalse("there is no comparison yet, so sending the caller to releaseComparisonId " //$NON-NLS-1$
            + "would name an id that answers to nothing: " + message, //$NON-NLS-1$
            message.contains("releaseComparisonId")); //$NON-NLS-1$
    }

    @Test
    public void testTheGrantedIdIsTheIdTheComparisonKeeps()
    {
        ComparisonSessionRegistry registry = registry();
        SlotClaim claim = registry.claimSlot("Trade"); //$NON-NLS-1$

        String id = registry.adoptClaim(claim.comparisonId(), handle("Trade"), batch()); //$NON-NLS-1$

        assertEquals("the caller quotes the id the slot was reserved under, or the reservation " //$NON-NLS-1$
            + "and the comparison are two different things to the reader", //$NON-NLS-1$
            claim.comparisonId(), id);
        assertNotNull("and it must resolve to the session", registry.handle(id)); //$NON-NLS-1$
    }

    @Test
    public void testAnAdoptedClaimStopsBlockingTheNextLaunchAndTheSessionTakesOver()
    {
        ComparisonSessionRegistry registry = registry();
        SlotClaim claim = registry.claimSlot("Trade"); //$NON-NLS-1$
        registry.adoptClaim(claim.comparisonId(), handle("Trade"), batch()); //$NON-NLS-1$

        String message = registry.claimSlot("Erp").refusal().toJson(); //$NON-NLS-1$

        assertTrue("now there IS a comparison, so the refusal is the one that names it: " //$NON-NLS-1$
            + message, message.contains(claim.comparisonId()));
    }

    @Test
    public void testWithdrawingAClaimGivesTheSlotToTheNextLaunch()
    {
        ComparisonSessionRegistry registry = registry();
        SlotClaim first = registry.claimSlot("Trade"); //$NON-NLS-1$

        assertTrue("the launch that took the claim gives it back", //$NON-NLS-1$
            registry.withdrawClaim(first.comparisonId()));

        assertTrue("and the slot is free again", registry.claimSlot("Erp").granted()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The invariant a claim must not be able to break: a record is dropped exactly when this server
     * KNOWS the slot came back, and withdrawing a claim is not knowledge about a comparison. It
     * must therefore not be able to touch one, however the id is spelled.
     */
    @Test
    public void testWithdrawingNeverDropsARegisteredSession()
    {
        ComparisonSessionRegistry registry = registry();
        String registered = registry.register(handle("Trade"), batch()); //$NON-NLS-1$

        assertFalse("a registered comparison is not a claim and cannot be withdrawn as one", //$NON-NLS-1$
            registry.withdrawClaim(registered));
        assertEquals("the record of a comparison EDT may be running must survive", 1, //$NON-NLS-1$
            registry.size());
        assertNotNull(registry.handle(registered));
    }

    /**
     * ...and the same for a claim that has ALREADY become a session, which is the shape the tool
     * calls this in: a {@code finally} that cannot know how far the launch got calls it with the
     * claim's id every time the hand-over did not complete, and the hand-over failing AFTER EDT was
     * reached is precisely when the record has to be kept.
     */
    @Test
    public void testWithdrawingAnAdoptedClaimDoesNothingAtAll()
    {
        ComparisonSessionRegistry registry = registry();
        SlotClaim claim = registry.claimSlot("Trade"); //$NON-NLS-1$
        registry.adoptClaim(claim.comparisonId(), handle("Trade"), batch()); //$NON-NLS-1$

        assertFalse("the claim is spent; what stands now is a session", //$NON-NLS-1$
            registry.withdrawClaim(claim.comparisonId()));
        assertEquals(1, registry.size());
    }

    @Test
    public void testAWithdrawnClaimCannotBeAdopted()
    {
        ComparisonSessionRegistry registry = registry();
        SlotClaim claim = registry.claimSlot("Trade"); //$NON-NLS-1$
        registry.withdrawClaim(claim.comparisonId());

        try
        {
            registry.adoptClaim(claim.comparisonId(), handle("Trade"), batch()); //$NON-NLS-1$
            fail("a launch that gave the slot back must not be able to register under it"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage(),
                expected.getMessage().contains("no longer holds EDT's single comparison slot")); //$NON-NLS-1$
        }
        assertEquals(0, registry.size());
    }

    @Test
    public void testAClaimIsRefusedWhileARegisteredComparisonHoldsTheSlot()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        String live = registry.register(handle, batch());

        SlotClaim claim = registry.claimSlot("Erp"); //$NON-NLS-1$

        assertFalse(claim.granted());
        assertTrue(claim.refusal().toJson(), claim.refusal().toJson().contains(live));
    }

    /**
     * A claim whose launch never came back for it is the one thing that could hold the slot with
     * nobody watching, so it has a budget of its own. Measured on the fake clock, not slept
     * through.
     */
    @Test
    public void testAClaimNobodyCameBackForIsReclaimed()
    {
        ComparisonSessionRegistry registry = registry();
        registry.claimSlot("Trade"); //$NON-NLS-1$

        assertFalse("inside the budget the claim still holds the slot", //$NON-NLS-1$
            registry.claimSlot("Erp").granted()); //$NON-NLS-1$

        clock.now += 5L * 60L * 1000L;

        assertTrue("past it, the slot goes to the launch that is actually here", //$NON-NLS-1$
            registry.claimSlot("Erp").granted()); //$NON-NLS-1$
    }

    /**
     * Two threads asking together get exactly one grant. The claim is the whole point of the
     * change, and "the check and the take are one step" is not visible in a single-threaded test.
     *
     * @throws Exception when a worker cannot be joined
     */
    @Test
    public void testTwoThreadsClaimingTogetherProduceExactlyOneGrant() throws Exception
    {
        ComparisonSessionRegistry registry = registry();
        int workers = 8;
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(workers);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger granted =
            new java.util.concurrent.atomic.AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++)
        {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try
                {
                    go.await();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (registry.claimSlot("Trade").granted()) //$NON-NLS-1$
                {
                    granted.incrementAndGet();
                }
            });
            threads.add(thread);
            thread.start();
        }
        ready.await();
        go.countDown();
        for (Thread thread : threads)
        {
            thread.join();
        }

        assertEquals("EDT runs one comparison at a time, so exactly one launch may prepare one", //$NON-NLS-1$
            1, granted.get());
    }

    /**
     * A claim and a registration draw on the same counter, so an id this registry has handed out is
     * never handed out again - the property the instance token exists to protect, extended to the
     * new minting site.
     */
    @Test
    public void testAWithdrawnClaimsIdIsNeverIssuedAgain()
    {
        ComparisonSessionRegistry registry = registry();
        SlotClaim withdrawn = registry.claimSlot("Trade"); //$NON-NLS-1$
        registry.withdrawClaim(withdrawn.comparisonId());

        String registered = registry.register(handle("Trade"), batch()); //$NON-NLS-1$

        assertNotEquals("a claim and a registration draw on the SAME counter: an id a client " //$NON-NLS-1$
            + "kept from a refused launch must never come back naming a different comparison", //$NON-NLS-1$
            withdrawn.comparisonId(), registered);
    }

    // ==================== What the two new verdicts promise ====================
    //
    // One literal per @Test on purpose: JUnit stops a method at its first failed assertion, so a
    // single method carrying five pins would only ever load the first of them, and an edit that
    // dropped the other four would still go green.

    @Test
    public void testNotStartedYetIsNotAFreedSlot()
    {
        SlotHandback handback = SlotHandbacks.of(SlotHandback.Verdict.NOT_STARTED_YET, "cmp-x-1"); //$NON-NLS-1$

        assertFalse("nothing was asked of EDT, so nothing may be claimed about its slot", //$NON-NLS-1$
            handback.slotIsFree());
    }

    @Test
    public void testNotStartedYetKeepsTheRecord()
    {
        SlotHandback handback = SlotHandbacks.of(SlotHandback.Verdict.NOT_STARTED_YET, "cmp-x-1"); //$NON-NLS-1$

        assertTrue("the record must be kept, because the comparison may still be running", //$NON-NLS-1$
            handback.recordKept());
    }

    @Test
    public void testNotStartedYetIsTheOnlyVerdictThatSaysThePlatformHasNotBegun()
    {
        assertTrue(SlotHandbacks.of(SlotHandback.Verdict.NOT_STARTED_YET, "cmp-x-1") //$NON-NLS-1$
            .platformHasNotBegun());
        assertFalse("a service gap is a different fact and a different next move", //$NON-NLS-1$
            SlotHandbacks.of(SlotHandback.Verdict.UNREACHABLE, "cmp-x-1").platformHasNotBegun()); //$NON-NLS-1$
    }

    @Test
    public void testNotStartedYetSentenceSaysNothingWasAskedOfThePlatform()
    {
        String sentence = SlotHandbacks.of(SlotHandback.Verdict.NOT_STARTED_YET, "cmp-x-1").sentence(); //$NON-NLS-1$

        assertTrue(sentence, sentence.contains("nothing was asked of the platform")); //$NON-NLS-1$
    }

    @Test
    public void testNotStartedYetSentenceWarnsThatEndingItWouldCostEdtItsComparisonSupport()
    {
        String sentence = SlotHandbacks.of(SlotHandback.Verdict.NOT_STARTED_YET, "cmp-x-1").sentence(); //$NON-NLS-1$

        assertTrue(sentence, sentence.contains("unable to run ANY comparison until it is restarted")); //$NON-NLS-1$
    }

    @Test
    public void testNotStartedYetSentenceTellsTheCallerToRepeatTheRequest()
    {
        String sentence = SlotHandbacks.of(SlotHandback.Verdict.NOT_STARTED_YET, "cmp-x-1").sentence(); //$NON-NLS-1$

        assertTrue(sentence, sentence.contains("repeat the request")); //$NON-NLS-1$
    }

    @Test
    public void testNotStartedYetSentenceSaysTheRecordIsKept()
    {
        String sentence = SlotHandbacks.of(SlotHandback.Verdict.NOT_STARTED_YET, "cmp-x-1").sentence(); //$NON-NLS-1$

        assertTrue(sentence, sentence.contains("record here is KEPT")); //$NON-NLS-1$
    }

    @Test
    public void testNeverStartedIsAFreeSlotBecauseNothingEverTookIt()
    {
        SlotHandback handback = SlotHandbacks.of(SlotHandback.Verdict.NEVER_STARTED, "cmp-x-1"); //$NON-NLS-1$

        assertTrue("a launch that never happened holds nothing", handback.slotIsFree()); //$NON-NLS-1$
        assertFalse("and it leaves no record to retry", handback.recordKept()); //$NON-NLS-1$
    }

    @Test
    public void testNeverStartedSentenceSaysTheLaunchNeverReachedEdt()
    {
        String sentence = SlotHandbacks.of(SlotHandback.Verdict.NEVER_STARTED, "cmp-x-1").sentence(); //$NON-NLS-1$

        assertTrue(sentence, sentence.contains("never reached EDT")); //$NON-NLS-1$
    }

    @Test
    public void testNeverStartedSentenceSaysTheRegistrationIsWithdrawn()
    {
        String sentence = SlotHandbacks.of(SlotHandback.Verdict.NEVER_STARTED, "cmp-x-1").sentence(); //$NON-NLS-1$

        assertTrue(sentence, sentence.contains("withdrawn")); //$NON-NLS-1$
    }

    // ==================== A comparison EDT has not begun is not ended ====================

    /**
     * EDT SCHEDULES the comparison job and only then runs it, and the method that gives EDT's own
     * slot back lives inside that job's run. Ending the comparison in between deletes the job
     * before it ever runs, so that method never happens and EDT reports a comparison as active for
     * the rest of its life. The hand-back must therefore withhold, not attempt.
     */
    @Test
    public void testHandBackIsWithheldWhileEdtHasNotBegunTheComparison()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        String id = registry.register(handle, batch());

        SlotHandback handback = registry.handBack(id, SlotHandback.Ending.CANCELLED);

        assertEquals("a comparison EDT has not begun must not be ended", //$NON-NLS-1$
            SlotHandback.Verdict.NOT_STARTED_YET, handback.verdict());
    }

    @Test
    public void testWithheldHandBackAsksThePlatformNothingAtAll()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        String id = registry.register(handle, batch());

        registry.handBack(id, SlotHandback.Ending.CANCELLED);

        assertTrue("nothing may be asked of EDT: the ask is what deletes the scheduled job", //$NON-NLS-1$
            releaser.released.isEmpty());
    }

    @Test
    public void testWithheldHandBackKeepsTheRecordSoItCanBeRepeated()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        String id = registry.register(handle, batch());

        registry.handBack(id, SlotHandback.Ending.CANCELLED);

        assertEquals("the comparison is still running, so its record must stay", 1, registry.size()); //$NON-NLS-1$
        assertTrue("and it must still be findable under its id", registry.find(id).isPresent()); //$NON-NLS-1$
    }

    /**
     * The withheld hand-back must not turn an ordinary cancellation into a refusal: a launch is
     * milliseconds old when {@code cancel_job} reaches it, so the caller is given the platform's
     * own moment to get under way before anything is decided.
     */
    @Test
    public void testHandBackWaitsForEdtToBeginAndThenEndsTheComparison()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        launchProgress.beginsAfterAsks = 3;
        String id = registry.register(handle, batch());

        SlotHandback handback = registry.handBack(id, SlotHandback.Ending.CANCELLED);

        assertEquals("once EDT is under way the comparison is ended for real", //$NON-NLS-1$
            SlotHandback.Verdict.FREED, handback.verdict());
        assertEquals("and the record goes, because the slot is confirmed free", 0, registry.size()); //$NON-NLS-1$
    }

    @Test
    public void testHandBackActuallyWaitedRatherThanAnsweringOnTheFirstReading()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        launchProgress.beginsAfterAsks = 3;
        String id = registry.register(handle, batch());

        registry.handBack(id, SlotHandback.Ending.CANCELLED);

        assertTrue("the wait has to be a wait: without a pause this answers on the first no", //$NON-NLS-1$
            pause.paused > 0);
    }

    /**
     * The wait is bounded, and the bound is what makes the verdict reachable at all: a comparison
     * that is not starting must produce an answer rather than a caller stuck in a loop.
     */
    @Test
    public void testTheWaitForEdtToBeginIsBounded()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        String id = registry.register(handle, batch());

        SlotHandback handback = registry.handBack(id, SlotHandback.Ending.CANCELLED);

        assertEquals(SlotHandback.Verdict.NOT_STARTED_YET, handback.verdict());
        assertTrue("the wait must give up rather than spin: " + pause.paused + " pauses", //$NON-NLS-1$ //$NON-NLS-2$
            pause.paused > 0 && pause.paused < 1000);
    }

    /**
     * "Could not ask whether EDT has begun" is a fact about this server's reach, and folding it
     * into "has not begun" would strand every session whenever the comparison service blinks. It
     * is also harmless to let through: the same absent service makes the hand-back itself fail
     * loudly, which is the verdict that keeps the record.
     */
    @Test
    public void testUnaskableStartQuestionStillAttemptsTheHandBack()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.reachable = false;
        String id = registry.register(handle, batch());

        SlotHandback handback = registry.handBack(id, SlotHandback.Ending.CANCELLED);

        assertEquals("an unanswered question is not evidence that EDT has not begun", //$NON-NLS-1$
            SlotHandback.Verdict.FREED, handback.verdict());
        assertEquals("and the hand-back was really attempted", 1, releaser.released.size()); //$NON-NLS-1$
    }

    /**
     * The idle sweep is the path where NOBODY is watching, so it is the one that must not brick
     * EDT's comparison support on its own.
     */
    @Test
    public void testSweepDoesNotEndAComparisonEdtHasNotBegun()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        registry.register(handle, batch());
        clock.now += TTL + 1;

        int reclaimed = registry.sweep();

        assertEquals("nothing was reclaimed, because nothing was ended", 0, reclaimed); //$NON-NLS-1$
        assertTrue("and EDT was asked for nothing", releaser.released.isEmpty()); //$NON-NLS-1$
        assertEquals("the record stays, so a later sweep can retry", 1, registry.size()); //$NON-NLS-1$
    }

    @Test
    public void testReleaseAllDoesNotEndAComparisonEdtHasNotBegun()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        registry.register(handle, batch());

        int freed = registry.releaseAll();

        assertEquals("nothing could be given back", 0, freed); //$NON-NLS-1$
        assertTrue("and the bundle stopping is not a reason to brick EDT's comparison support", //$NON-NLS-1$
            releaser.released.isEmpty());
    }

    // ==================== A launch that never reached EDT ====================

    @Test
    public void testWithdrawingAnUnstartedLaunchDropsTheRecord()
    {
        ComparisonSessionRegistry registry = registry();
        String id = registry.register(handle("Main"), batch()); //$NON-NLS-1$

        SlotHandback handback = registry.withdrawUnstartedLaunch(id);

        assertEquals(SlotHandback.Verdict.NEVER_STARTED, handback.verdict());
        assertEquals("a reservation for a launch that never happened must not survive it", //$NON-NLS-1$
            0, registry.size());
    }

    @Test
    public void testWithdrawingAnUnstartedLaunchAsksThePlatformNothing()
    {
        ComparisonSessionRegistry registry = registry();
        String id = registry.register(handle("Main"), batch()); //$NON-NLS-1$

        registry.withdrawUnstartedLaunch(id);

        assertTrue("there is nothing to end: the batch never left this process", //$NON-NLS-1$
            releaser.released.isEmpty());
        assertEquals("EDT is not even asked what it holds", 0, liveHandles.asked); //$NON-NLS-1$
    }

    @Test
    public void testWithdrawingAnUnknownIdClaimsNothing()
    {
        ComparisonSessionRegistry registry = registry();

        SlotHandback handback = registry.withdrawUnstartedLaunch("cmp-nothing"); //$NON-NLS-1$

        assertEquals(SlotHandback.Verdict.NOT_REGISTERED, handback.verdict());
        assertFalse("an unknown id is not a withdrawn reservation", handback.wasRegistered()); //$NON-NLS-1$
    }

    // ==================== A launch EDT REACHED and refused ====================
    //
    // The reservation is made before the batch is handed over, so a refusal has to decide what
    // becomes of it. The ordinary hand-back answers NOT_STARTED_YET for a comparison EDT reports
    // no status for, and that verdict KEEPS the record - right when nobody refused anything, wrong
    // after a refusal, because that launch is never going to begin and the kept record then names
    // EDT's single slot as taken by a comparison that does not exist. What must NOT change is the
    // rule underneath it: the record survives not knowing, and goes only on being told.

    @Test
    public void testARefusedLaunchIsWithdrawnWhenEdtAnswersItIsNotRunningIt()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        String id = registry.register(handle, batch());

        SlotHandback handback = registry.handBackRefusedLaunch(id, SlotHandback.Ending.CLOSED);

        assertEquals("a refusal plus an answer of 'not running it' is a refusal, not an unknown", //$NON-NLS-1$
            SlotHandback.Verdict.LAUNCH_REFUSED, handback.verdict());
        assertEquals("the reservation for a refused launch must not outlive it and refuse every " //$NON-NLS-1$
            + "later launch until the idle TTL expires", 0, registry.size()); //$NON-NLS-1$
    }

    @Test
    public void testARefusedLaunchIsWithdrawnWithoutAskingEdtToEndAnything()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        String id = registry.register(handle, batch());

        registry.handBackRefusedLaunch(id, SlotHandback.Ending.CLOSED);

        assertTrue("ending a comparison EDT has only scheduled deletes the job that gives EDT's " //$NON-NLS-1$
            + "own slot back - the withdrawal must not do it either", releaser.released.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testARefusedLaunchKeepsItsRecordWhenEdtCouldNotBeAsked()
    {
        // The rule that must NOT be weakened: "could not ask" is a fact about this server's reach,
        // not the platform answering no, and a record dropped on it would leave a comparison that
        // may be running addressable by nobody.
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.reachable = false;
        releaser.serviceGone = true;
        String id = registry.register(handle, batch());

        SlotHandback handback = registry.handBackRefusedLaunch(id, SlotHandback.Ending.CLOSED);

        assertEquals(SlotHandback.Verdict.UNREACHABLE, handback.verdict());
        assertEquals("nothing was established, so the record stays", 1, registry.size()); //$NON-NLS-1$
    }

    @Test
    public void testARefusedLaunchWhoseHandBackFailsKeepsItsRecord()
    {
        // EDT had begun it after all and then refused the hand-back. That is not "not running it"
        // either, so the ordinary answer stands and the comparison is still addressable.
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        releaser.explode = true;
        String id = registry.register(handle, batch());

        SlotHandback handback = registry.handBackRefusedLaunch(id, SlotHandback.Ending.CLOSED);

        assertEquals(SlotHandback.Verdict.NOT_FREED, handback.verdict());
        assertEquals("a failed hand-back keeps the record on this path like on every other", //$NON-NLS-1$
            1, registry.size());
    }

    @Test
    public void testARefusedLaunchEdtHadBegunIsHandedBackNormally()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        String id = registry.register(handle, batch());

        SlotHandback handback = registry.handBackRefusedLaunch(id, SlotHandback.Ending.CLOSED);

        assertEquals("EDT was running it, so it is ENDED and not merely forgotten", //$NON-NLS-1$
            SlotHandback.Verdict.FREED, handback.verdict());
        assertEquals("and the platform really was asked", 1, releaser.released.size()); //$NON-NLS-1$
    }

    @Test
    public void testWithdrawingARefusedLaunchOnAnUnknownIdClaimsNothing()
    {
        ComparisonSessionRegistry registry = registry();

        SlotHandback handback =
            registry.handBackRefusedLaunch("cmp-nothing", SlotHandback.Ending.CLOSED); //$NON-NLS-1$

        assertEquals(SlotHandback.Verdict.NOT_REGISTERED, handback.verdict());
        assertFalse("an unknown id is not a withdrawn reservation", handback.wasRegistered()); //$NON-NLS-1$
    }

    @Test
    public void testLaunchRefusedIsAFreeSlotBecauseTheRefusedLaunchHoldsNothing()
    {
        SlotHandback handback = SlotHandbacks.of(SlotHandback.Verdict.LAUNCH_REFUSED, "cmp-x-1"); //$NON-NLS-1$

        assertTrue("a launch the platform refused holds none of the slot", handback.slotIsFree()); //$NON-NLS-1$
    }

    @Test
    public void testLaunchRefusedLeavesNoRecordToRetry()
    {
        SlotHandback handback = SlotHandbacks.of(SlotHandback.Verdict.LAUNCH_REFUSED, "cmp-x-1"); //$NON-NLS-1$

        assertFalse("there is nothing to retry: the launch was refused", handback.recordKept()); //$NON-NLS-1$
    }

    @Test
    public void testLaunchRefusedSentenceSaysEdtRefusedToStartIt()
    {
        String sentence = SlotHandbacks.of(SlotHandback.Verdict.LAUNCH_REFUSED, "cmp-x-1").sentence(); //$NON-NLS-1$

        assertTrue(sentence, sentence.contains("EDT refused to start comparison")); //$NON-NLS-1$
    }

    @Test
    public void testLaunchRefusedSentenceSaysTheRegistrationIsWithdrawn()
    {
        String sentence = SlotHandbacks.of(SlotHandback.Verdict.LAUNCH_REFUSED, "cmp-x-1").sentence(); //$NON-NLS-1$

        assertTrue(sentence, sentence.contains("registration here is withdrawn")); //$NON-NLS-1$
    }

    @Test
    public void testLaunchRefusedSentenceIsNotTheNeverReachedEdtOne()
    {
        // The two withdrawals are different facts: one is "the batch never left this process",
        // this one is "EDT was reached, said no, and answers that it is not running it". A caller
        // reading the wrong sentence would look for the failure in the wrong place.
        String sentence = SlotHandbacks.of(SlotHandback.Verdict.LAUNCH_REFUSED, "cmp-x-1").sentence(); //$NON-NLS-1$

        assertFalse(sentence, sentence.contains("never reached EDT")); //$NON-NLS-1$
    }

    // ==================== Ids do not repeat across registry lives ====================

    /**
     * An id leaves this server and comes back on a later request. A bundle reinstall or an EDT
     * restart builds a new registry, and with a bare counter the very first comparison of the new
     * life reissued the id a client was still holding from the old one - so a stale
     * releaseComparisonId released, or a stale node request read, a comparison the caller had never
     * heard of.
     */
    @Test
    public void testTwoRegistryLivesDoNotIssueTheSameId()
    {
        String first = registry().register(handle("Main"), batch()); //$NON-NLS-1$
        String second = registry().register(handle("Main"), batch()); //$NON-NLS-1$

        assertNotEquals("the first id of a new registry must not repeat the first id of the old", //$NON-NLS-1$
            first, second);
    }

    @Test
    public void testIdsWithinOneRegistryShareItsTokenAndCountUp()
    {
        ComparisonSessionRegistry registry = registry();

        String first = registry.register(handle("Main"), batch()); //$NON-NLS-1$
        String second = registry.register(handle("Main"), batch()); //$NON-NLS-1$

        String token = first.substring("cmp-".length(), first.lastIndexOf('-')); //$NON-NLS-1$
        assertFalse("an id without a registry token is the collision this test exists for: " //$NON-NLS-1$
            + first, token.isEmpty());
        assertEquals("the token identifies the registry, so it is the same for both", //$NON-NLS-1$
            token, second.substring("cmp-".length(), second.lastIndexOf('-'))); //$NON-NLS-1$
        assertTrue("and the counter still counts: " + first + ", " + second, //$NON-NLS-1$ //$NON-NLS-2$
            first.endsWith("-1") && second.endsWith("-2")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The id is printed in reports and quoted back by hand, so it stays short and readable. */
    @Test
    public void testIdStaysShortAndReadable()
    {
        String id = registry().register(handle("Main"), batch()); //$NON-NLS-1$

        assertTrue("an id must read as cmp-<token>-<n>: " + id, //$NON-NLS-1$
            id.matches("cmp-[0-9a-z]+-[0-9]+")); //$NON-NLS-1$
        assertTrue("an id a human quotes must stay short: " + id, id.length() <= 20); //$NON-NLS-1$
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

    // ==================== The bundle stopping is the LAST call, so it is not an ordinary one ====================
    //
    // Everywhere else a launch EDT has accepted but not begun answers NOT_STARTED_YET, the record
    // is KEPT and the next call retries it. On the way out of the bundle there is no next call, so
    // that answer stops being a deferral and becomes a silent loss: the record went with the map
    // and EDT kept the virtual project under an id nothing can name. These pin what replaces it,
    // and - just as load-bearing - what must NOT replace it.

    /**
     * The gap between EDT accepting a launch and beginning it is an Eclipse job waiting for a
     * worker, so a bundle stopped moments after a launch is looking at a comparison that is about
     * to become perfectly endable. The shutdown gives it the same bounded moment an asked-for
     * hand-back gives it, and then ends it for real.
     */
    @Test
    public void testTheBundleStoppingGivesAQueuedLaunchTheMomentItNeedsToBegin()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        launchProgress.beginsAfterAsks = 3;
        String id = registry.register(handle, batch());

        int freed = registry.closeAndReleaseAll();

        assertEquals("a launch that got under way during the wait must be ENDED, not abandoned " //$NON-NLS-1$
            + "with its virtual project still on EDT", 1, freed); //$NON-NLS-1$
        assertEquals(Collections.singletonList(id), releaser.released);
    }

    /** The same run, pinning that the wait happened at all rather than that the answer flipped. */
    @Test
    public void testTheBundleStoppingReallyWaitsForALaunchEdtHasNotBegun()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        launchProgress.beginsAfterAsks = 3;
        registry.register(handle, batch());

        registry.closeAndReleaseAll();

        assertTrue("the shutdown must poll the platform rather than decide on one reading", //$NON-NLS-1$
            pause.paused > 0);
    }

    /**
     * ONE budget over the whole map, not one per session. A shutdown that multiplied its wait by
     * the number of records it happened to find would hold the workbench up for as long as the
     * registry is untidy, and the gap being waited on is the same Eclipse queue for all of them.
     */
    @Test
    public void testTheShutdownWaitIsNotMultipliedByTheNumberOfRecords()
    {
        ComparisonSessionRegistry registry = registry();
        launchProgress.begun = Boolean.FALSE;
        registry.register(handle("Trade"), batch()); //$NON-NLS-1$
        registry.register(handle("Erp"), batch()); //$NON-NLS-1$
        registry.register(handle("Retail"), batch()); //$NON-NLS-1$

        registry.closeAndReleaseAll();

        // Three sessions against one budget: a per-session wait would spend three of them, and the
        // fake pause advances the fake clock so the count IS the budget in poll-sized steps.
        assertTrue("three records may not cost three waits: " + pause.paused + " pauses", //$NON-NLS-1$ //$NON-NLS-2$
            pause.paused < 3 * (PLATFORM_START_BUDGET_POLLS - 1));
    }

    /**
     * The guard the whole {@code NOT_STARTED_YET} verdict exists for, restated on the one path
     * that has an excuse to break it. Ending a launch EDT has only scheduled deletes the Eclipse
     * job whose run gives EDT's own slot back, so EDT reports a comparison as active for the rest
     * of ITS life - and a bundle stopping is very often a bundle being reinstalled with the same
     * EDT carrying on. "It is our last chance" is not a reason to do damage that outlives us.
     */
    @Test
    public void testTheBundleStoppingStillRefusesToEndALaunchEdtNeverBegan()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        registry.register(handle, batch());

        int freed = registry.closeAndReleaseAll();

        assertEquals("nothing could be given back", 0, freed); //$NON-NLS-1$
        assertTrue("and the bundle stopping is not a reason to brick EDT's comparison support", //$NON-NLS-1$
            releaser.released.isEmpty());
    }

    /**
     * What could not be given back is WRITTEN DOWN, because the record goes either way and nothing
     * will retry it: without a line in the log a leaked virtual project leaves no trace at all.
     * <p>
     * Read back out of the plug-in's own {@code ILog} rather than from a message builder, so that
     * building the sentence and never logging it cannot pass.
     */
    @Test
    public void testAComparisonTheShutdownCouldNotEndIsNamedInTheEdtLog()
    {
        Bundle bundle = FrameworkUtil.getBundle(ComparisonSessionRegistry.class);
        assertNotNull("this case can only observe the log from inside OSGi; without the bundle it " //$NON-NLS-1$
            + "would 'pass' by seeing nothing at all", bundle); //$NON-NLS-1$
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        String id = registry.register(handle, batch());

        List<IStatus> recorded = record(bundle, registry::closeAndReleaseAll);

        assertNotNull("the comparison EDT kept must be named, or it is lost silently: " //$NON-NLS-1$
            + recorded, noticeAbout(recorded, id));
    }

    /** By its project, because that is what an operator looks a comparison up by in EDT. */
    @Test
    public void testTheStrandedNoticeNamesTheProject()
    {
        Bundle bundle = FrameworkUtil.getBundle(ComparisonSessionRegistry.class);
        assertNotNull(bundle);
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        String id = registry.register(handle, batch());

        IStatus about = noticeAbout(record(bundle, registry::closeAndReleaseAll), id);

        assertNotNull(about);
        assertTrue("the project must be in the line: " + about.getMessage(), //$NON-NLS-1$
            about.getMessage().contains("Main")); //$NON-NLS-1$
    }

    /** The consequence is spelled out, not left as a verdict name nobody outside this code knows. */
    @Test
    public void testTheStrandedNoticeSaysWhatEdtIsStillHolding()
    {
        Bundle bundle = FrameworkUtil.getBundle(ComparisonSessionRegistry.class);
        assertNotNull(bundle);
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        String id = registry.register(handle, batch());

        IStatus about = noticeAbout(record(bundle, registry::closeAndReleaseAll), id);

        assertNotNull(about);
        assertTrue("the operator must be told what is still allocated: " + about.getMessage(), //$NON-NLS-1$
            about.getMessage().contains("virtual project")); //$NON-NLS-1$
    }

    /**
     * SlotHandback.sentence() is written for a caller that can retry, and for this verdict it says
     * the record is kept and the request may be repeated. Publishing it here would send an
     * operator back into a bundle that has stopped, so the shutdown says its own thing.
     */
    @Test
    public void testTheStrandedNoticeDoesNotTellAnybodyToRepeatTheRequest()
    {
        Bundle bundle = FrameworkUtil.getBundle(ComparisonSessionRegistry.class);
        assertNotNull(bundle);
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Main"); //$NON-NLS-1$
        liveHandles.live = new ArrayList<>(Arrays.asList(handle));
        launchProgress.begun = Boolean.FALSE;
        String id = registry.register(handle, batch());

        IStatus about = noticeAbout(record(bundle, registry::closeAndReleaseAll), id);

        assertNotNull(about);
        assertFalse("there is nothing left to repeat the request into: " + about.getMessage(), //$NON-NLS-1$
            about.getMessage().contains("repeat the request")); //$NON-NLS-1$
    }

    /**
     * Not only the unbegun launch: a hand-back the platform REFUSED strands the comparison just as
     * completely once the record is cleared, and the shutdown is the one path that clears it
     * without a retry behind it.
     */
    @Test
    public void testAHandBackThatFailedAtShutdownIsNamedInTheEdtLogToo()
    {
        Bundle bundle = FrameworkUtil.getBundle(ComparisonSessionRegistry.class);
        assertNotNull(bundle);
        ComparisonSessionRegistry registry = registry();
        releaser.explode = true;
        String id = registry.register(handle("Trade"), batch()); //$NON-NLS-1$

        List<IStatus> recorded = record(bundle, registry::closeAndReleaseAll);

        assertTrue("the shutdown's own notice must be there, and not merely the release failure " //$NON-NLS-1$
            + "the releaser already logs: " + recorded, //$NON-NLS-1$
            recorded.stream().anyMatch(status -> status.getMessage().contains(id)
                && status.getMessage().contains("was NOT handed back"))); //$NON-NLS-1$
    }

    /** A shutdown with nothing to strand says nothing, so the notice stays a signal. */
    @Test
    public void testAShutdownThatGaveEverythingBackWritesNoNotice()
    {
        Bundle bundle = FrameworkUtil.getBundle(ComparisonSessionRegistry.class);
        assertNotNull(bundle);
        ComparisonSessionRegistry registry = registry();
        String id = registry.register(handle("Trade"), batch()); //$NON-NLS-1$

        List<IStatus> recorded = record(bundle, registry::closeAndReleaseAll);

        assertNull("a comparison that WAS given back may not be reported as stranded: " + recorded, //$NON-NLS-1$
            noticeAbout(recorded, id));
    }

    /**
     * Runs something while the plug-in's own log is listened to.
     *
     * @param bundle the plug-in bundle
     * @param work what to run
     * @return every status logged while it ran
     */
    private static List<IStatus> record(Bundle bundle, Runnable work)
    {
        ILog log = Platform.getLog(bundle);
        List<IStatus> recorded = new ArrayList<>();
        ILogListener listener = (status, plugin) -> recorded.add(status);
        log.addLogListener(listener);
        try
        {
            work.run();
        }
        finally
        {
            log.removeLogListener(listener);
        }
        return recorded;
    }

    /**
     * Matched on the comparison id ALONE, deliberately. Filtering on the notice's own wording as
     * well would make every one of these cases pass whenever the wording changed - including the
     * case that exists to reject one particular wording - by finding nothing to assert about.
     *
     * @param recorded what was logged
     * @param comparisonId the id the notice must name
     * @return the first status naming that comparison, or {@code null} when none does
     */
    private static IStatus noticeAbout(List<IStatus> recorded, String comparisonId)
    {
        for (IStatus status : recorded)
        {
            if (status.getMessage() != null && status.getMessage().contains(comparisonId))
            {
                return status;
            }
        }
        return null;
    }


    // ==================== every budget here is ELAPSED time, not a moment on a clock ====================
    //
    // The three deadlines this registry keeps - the claim, the idle TTL, the wait for a launch to
    // begin - used to be read off the system's wall clock, which is not a measure of elapsed time at
    // all: NTP corrects it, an operator sets it, and a virtual machine resumed from a snapshot hands
    // the JVM a reading from before the wait started. The two directions break it in opposite ways,
    // and only one of them is reachable through this seam, so each is pinned in the way it can be. A
    // correction BACKWARDS is scripted below. A jump FORWARD cannot be scripted at all once the
    // source is monotonic - there is no such jump to script - so what pins that half is the WIRING:
    // the ratchets at the end of this block assert that neither this class nor the engine that
    // installs it names the wall clock.

    /**
     * Mirrors {@code ComparisonSessionRegistry.CLAIM_BUDGET_MILLIS} (private): how long a claim may
     * stand before the slot is taken back from it.
     */
    private static final long CLAIM_BUDGET = 5L * 60L * 1000L;

    /** An hour, which is the size of the ordinary daylight-saving or NTP correction. */
    private static final long AN_HOUR = 3_600_000L;

    /**
     * A launch worker dies holding the slot, and then the machine's clock is corrected backwards.
     * The claim's budget is a span of REAL time, so the correction may not buy the dead claim any: a
     * whole budget of elapsed time after it, the slot belongs to whoever is actually there.
     */
    @Test
    public void testAClockCorrectedBackwardsDoesNotKeepAnAbandonedClaimAlive()
    {
        ComparisonSessionRegistry registry = registry();
        registry.claimSlot("Trade"); //$NON-NLS-1$

        // Corrected backwards, and OBSERVED - a step nobody looks at is a step nobody can decline
        // to charge for.
        clock.now -= AN_HOUR;
        assertFalse("a correction is not elapsed time, so the claim is still inside its budget", //$NON-NLS-1$
            registry.claimSlot("Erp").granted()); //$NON-NLS-1$

        clock.now += CLAIM_BUDGET + 1;

        assertTrue("a whole claim budget of REAL time has passed since the claim was taken, and " //$NON-NLS-1$
            + "that is what the budget is about - not where the clock happens to point", //$NON-NLS-1$
            registry.claimSlot("Erp").granted()); //$NON-NLS-1$
    }

    /**
     * The same correction against the idle sweep. The session is deliberately NOT looked up in
     * between: a lookup restarts its TTL and makes the arithmetic self-consistent again, which is
     * exactly what hides this.
     */
    @Test
    public void testAClockCorrectedBackwardsDoesNotPostponeTheIdleSweep()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        registry.register(handle, batch());

        clock.now -= AN_HOUR;
        assertEquals("a correction ages nothing: no time has passed", 0, registry.sweep()); //$NON-NLS-1$

        clock.now += TTL + 1;

        assertEquals("the session then sat untouched for a whole TTL of REAL time, and that is " //$NON-NLS-1$
            + "what the TTL is about", 1, registry.sweep()); //$NON-NLS-1$
    }

    /**
     * The correction arriving in the MIDDLE of a wait, which is the case an absolute deadline gets
     * wrong however carefully it was computed: the reading it was computed from is gone.
     *
     * <p>The numbers are chosen so that the broken shape is bounded too, and merely LONGER - a
     * deadline of "the reading at the start plus the budget" has to climb the whole hour back before
     * it can expire, which is some 72 000 further polls. A test that hung instead would prove
     * nothing about which shape it was measuring.</p>
     */
    @Test
    public void testAClockCorrectedBackwardsMidWaitDoesNotExtendTheWaitForALaunchToBegin()
    {
        ComparisonSessionRegistry registry = registry();
        ComparisonProcessHandle handle = handle("Trade"); //$NON-NLS-1$
        liveHandles.live = Collections.singletonList(handle);
        String id = registry.register(handle, batch());
        launchProgress.begun = Boolean.FALSE;
        pause.stepBackOnFirstPauseMillis = AN_HOUR;

        registry.handBack(id, SlotHandback.Ending.CANCELLED);

        // The first poll buys nothing, because the hour it stepped back cancels the 50ms it slept;
        // every poll after it buys its 50ms, and the budget is spent by those.
        assertEquals("a correction mid-wait must cost the wait nothing and buy it nothing", //$NON-NLS-1$
            PLATFORM_START_BUDGET_POLLS + 1, pause.paused);
    }

    /**
     * The production WIRING, pinned where no behavioural test can reach it: a scripted source proves
     * the arithmetic and cannot prove which clock the shipped registry is handed. This is the shape
     * {@code GetComparisonNodeToolTest} already uses for the call budget - the value of the rule is
     * that grepping the file for the name keeps returning nothing, so the javadoc says "the wall
     * clock" in words rather than spelling the call.
     *
     * @throws IOException when the source cannot be read
     */
    @Test
    public void testTheRegistryDoesNotNameTheSystemWallClock() throws IOException
    {
        String source = readSource("utils/compare/ComparisonSessionRegistry.java"); //$NON-NLS-1$

        // Positive control first: a scan that read the wrong file - or nothing - would pass the
        // absence assertion over an empty string and prove nothing at all.
        assertTrue("the scan did not read ComparisonSessionRegistry", //$NON-NLS-1$
            source.contains("class ComparisonSessionRegistry")); //$NON-NLS-1$
        // The BARE method name, not "System.currentTimeMillis": a mutation that put the wall clock
        // back as the method REFERENCE System::currentTimeMillis walked straight past the dotted
        // form of this check, which is the one shape the production wiring would actually use.
        assertFalse("a budget read off the wall clock is not a budget - a correction backwards keeps " //$NON-NLS-1$
            + "a dead claim alive and a jump forward reclaims a session that was just touched - so " //$NON-NLS-1$
            + "this file must not name it in any form, not even in a comment", //$NON-NLS-1$
            source.contains("currentTimeMillis")); //$NON-NLS-1$
    }

    /**
     * Its own literal, because the registry can be innocent and still be handed the wrong source:
     * the seam is a constructor parameter, and only the engine decides what goes into it.
     *
     * @throws IOException when the source cannot be read
     */
    @Test
    public void testTheEngineInstallsTheRegistryWithTheMonotonicSource() throws IOException
    {
        String source = readSource("utils/compare/ComparisonEngine.java"); //$NON-NLS-1$

        assertTrue("the scan did not read ComparisonEngine", //$NON-NLS-1$
            source.contains("class ComparisonEngine")); //$NON-NLS-1$
        assertTrue("the registry the engine installs must be measured against the monotonic source", //$NON-NLS-1$
            source.contains("new ComparisonSessionRegistry(System::nanoTime")); //$NON-NLS-1$
    }

    /**
     * Reads one bundle source file, by walking up from the working directory exactly as the
     * source-scanning ratchets elsewhere in this suite do.
     *
     * @param relative path under the bundle's {@code src/com/ditrix/edt/mcp/server}
     * @return the file's text
     * @throws IOException when it cannot be read
     */
    private static String readSource(String relative) throws IOException
    {
        String base = "bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/"; //$NON-NLS-1$
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            for (String prefix : Arrays.asList("", "mcp/")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                File candidate = new File(dir, prefix + base + relative);
                if (candidate.isFile())
                {
                    return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
                }
            }
            dir = dir.getParentFile();
        }
        fail("could not locate " + relative + " by walking up from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
            + System.getProperty("user.dir")); //$NON-NLS-1$
        return null; // unreachable
    }

    // ==================== The id token rests on nothing a machine can wind back ====================
    //
    // The token used to be a counter seeded from System.currentTimeMillis(), which held only while
    // the clock moved forward. A clock corrected backwards - NTP, or by hand - and a virtual
    // machine resumed from a snapshot both hand the next JVM a seed an earlier one already used,
    // and then an id kept from a finished job addresses SOMEBODY ELSE'S comparison.

    @Test
    public void testTwoRegistriesDoNotGetTokensOneApart()
    {
        long first = tokenValue(registry().register(handle("Trade"), batch())); //$NON-NLS-1$
        long second = tokenValue(registry().register(handle("Erp"), batch())); //$NON-NLS-1$

        assertNotEquals("a token one greater than the last one is a counter, and a counter is " //$NON-NLS-1$
            + "exactly what the wall-clock seed was there to carry across JVMs", 1L, //$NON-NLS-1$
            second - first);
    }

    @Test
    public void testTheIdTokenIsNotDrawnFromTheWallClock()
    {
        long before = System.currentTimeMillis();
        long token = tokenValue(registry().register(handle("Trade"), batch())); //$NON-NLS-1$
        long after = System.currentTimeMillis();

        // An hour either way, because the seed was taken when this class was LOADED rather than
        // when the registry was built. A drawn token falls in that window with a probability of
        // about three in a million, which is a smaller risk than the defect it pins.
        assertFalse("a token that is the wall clock is a token a clock correction reissues: " //$NON-NLS-1$
            + token, token >= before - 3_600_000L && token <= after + 3_600_000L);
    }

    /**
     * The shape the wire and the guides rely on: {@code tests/e2e/tools/test_compare_configurations.py}
     * matches ids against {@code cmp-[0-9a-z]+-\d+}, and the examples in the tool guides are
     * eight-character tokens. A token that rendered signed, or whose length wandered, would break
     * both while every other test here stayed green.
     */
    @Test
    public void testTheIdKeepsTheShapeTheWireExpects()
    {
        String id = registry().register(handle("Trade"), batch()); //$NON-NLS-1$

        assertTrue("the id must stay cmp-<eight base-36 characters>-<counter>: " + id, //$NON-NLS-1$
            id.matches("cmp-[0-9a-z]{8}-\\d+")); //$NON-NLS-1$
    }

    /** Within one JVM the tokens are not merely unlikely to repeat - they do not repeat. */
    @Test
    public void testNoTwoRegistriesInThisJvmShareAToken()
    {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 64; i++)
        {
            tokens.add(tokenOf(registry().register(handle("Trade"), batch()))); //$NON-NLS-1$
        }

        assertEquals("two registries sharing a token would issue the same ids from their two " //$NON-NLS-1$
            + "counters", 64, tokens.size()); //$NON-NLS-1$
    }

    /**
     * @param comparisonId an id this registry issued
     * @return its instance token
     */
    private static String tokenOf(String comparisonId)
    {
        String[] parts = comparisonId.split("-"); //$NON-NLS-1$
        assertEquals("cmp-<token>-<counter>, or this helper is reading the wrong field: " //$NON-NLS-1$
            + comparisonId, 3, parts.length);
        return parts[1];
    }

    /**
     * @param comparisonId an id this registry issued
     * @return its instance token as the number it renders
     */
    private static long tokenValue(String comparisonId)
    {
        return Long.parseLong(tokenOf(comparisonId), Character.MAX_RADIX);
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
