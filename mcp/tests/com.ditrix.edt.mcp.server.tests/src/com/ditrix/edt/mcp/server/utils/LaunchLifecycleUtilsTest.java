/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.Test;
import org.mockito.Mockito;

import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.LaunchLock;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PreLaunchResult;

/**
 * Tests for {@link LaunchLifecycleUtils}.
 *
 * <p>Focus is on {@link PreLaunchResult} construction and {@code summary()}
 * formatting — the parts that don't require an Eclipse runtime. The actual
 * {@code prepareForFreshLaunch} / {@code terminateAndWait} / {@code
 * updateApplicationIfNeeded} flows need {@code ILaunchManager} +
 * {@code IApplicationManager} and are covered by mvn verify compilation
 * against the EDT target platform.
 */
public class LaunchLifecycleUtilsTest
{
    @Test
    public void testFailedResultSummaryIncludesError()
    {
        PreLaunchResult result = newResult(false, 0, "boom");
        assertFalse(result.isOk());
        assertEquals("boom", result.getError());
        assertTrue("summary should expose the error",
            result.summary().contains("failed") && result.summary().contains("boom"));
    }

    @Test
    public void testOkZeroTerminatedIsNoOp()
    {
        PreLaunchResult result = newResult(true, 0, null);
        assertTrue(result.isOk());
        assertNull(result.getError());
        assertEquals(0, result.getTerminatedCount());
        String summary = result.summary();
        assertTrue("no-op summary should say so",
            summary.contains("no-op") && summary.contains("DB ready"));
    }

    @Test
    public void testOkOneTerminatedSingularPhrasing()
    {
        PreLaunchResult result = newResult(true, 1, null);
        String summary = result.summary();
        assertTrue("must mention terminated count",
            summary.contains("terminated 1 live launch"));
        // singular: no trailing "es"
        assertFalse("singular should not say 'launches'",
            summary.contains("launches"));
        assertTrue("DB readiness phrased", summary.contains("DB ready"));
    }

    @Test
    public void testOkMultipleTerminatedPluralPhrasing()
    {
        PreLaunchResult result = newResult(true, 3, null);
        String summary = result.summary();
        assertTrue("must use plural 'launches'",
            summary.contains("terminated 3 live launches"));
        assertTrue("DB readiness phrased", summary.contains("DB ready"));
    }

    @Test
    public void testTerminateTimeoutPreferenceFallback()
    {
        // Outside the Eclipse OSGi runtime the preference store is unavailable,
        // so this must fall back to the parameter's static default (10s).
        int value = LaunchLifecycleUtils.getDefaultTerminateTimeoutSeconds();
        assertTrue("timeout should be a sane positive value",
            value >= 1 && value <= 120);
    }

    @Test
    public void testTerminateAndWaitNullLaunchIsNoOp()
    {
        // No Eclipse mock needed for this branch — null short-circuits immediately.
        assertTrue("null launch must be treated as already terminated",
            LaunchLifecycleUtils.terminateAndWait(null, 1));
    }

    @Test
    public void testRegisterOwnedLaunchNullIsNoOp()
    {
        // Should not throw.
        LaunchLifecycleUtils.registerOwnedLaunch(null);
        LaunchLifecycleUtils.unregisterOwnedLaunch(null);
    }

    @Test
    public void testLockForReturnsSameInstanceForSameKey()
    {
        LaunchLock a = LaunchLifecycleUtils.lockFor("MyProject", "app-1");
        LaunchLock b = LaunchLifecycleUtils.lockFor("MyProject", "app-1");
        assertSame("same (project, appId) must return same lock instance", a, b);
    }

    @Test
    public void testLockForDistinguishesProjectsWithSpaces()
    {
        // Guards against printable-delimiter collisions:
        //   {project='My', appId='Project x'} vs {project='My Project', appId='x'}.
        LaunchLock a = LaunchLifecycleUtils.lockFor("My", "Project x");
        LaunchLock b = LaunchLifecycleUtils.lockFor("My Project", "x");
        assertNotSame("locks for distinct (project, appId) must not collide", a, b);
    }

    @Test
    public void testFreeLockIsAcquiredWithoutBurningTheDeadline()
    {
        // A bug that always waits the full timeout would still "succeed" - so the cost is pinned,
        // not just the outcome.
        LaunchLock lock = LaunchLifecycleUtils.lockFor("PromptProject", "app-prompt");
        long startedAt = System.nanoTime();
        assertTrue("a free lock must be acquired", lock.tryAcquire(5_000L, null));
        try
        {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertTrue("an uncontended acquisition must not wait out the bound: " + elapsedMs
                + " ms", elapsedMs < 500L);
        }
        finally
        {
            lock.unlock();
        }
    }

    @Test
    public void testLockIsReentrantForTheSameThread() throws Exception
    {
        // Load-bearing: a caller holding this lock around its spawn sequence re-enters it through
        // prepareForFreshLaunch. Each acquisition owns its own unlock.
        LaunchLock lock = LaunchLifecycleUtils.lockFor("ReentrantProject", "app-reentrant");
        AtomicInteger worked = new AtomicInteger();
        assertTrue("outer acquisition must succeed", lock.tryAcquire(2_000L, null));
        try
        {
            assertTrue("a re-entrant acquisition must not fail on the deadline",
                lock.tryAcquire(0L, null));
            try
            {
                worked.incrementAndGet();
            }
            finally
            {
                lock.unlock();
            }
            assertTrue("the outer hold must survive the inner release",
                lock.isHeldByCurrentThread());
        }
        finally
        {
            lock.unlock();
        }

        assertEquals("the guarded work must have run once", 1, worked.get());
        assertFalse("the counts must balance back to zero", lock.isHeldByCurrentThread());
        // Proven from another thread: isHeldByCurrentThread on this one cannot see a leaked hold.
        AtomicBoolean free = new AtomicBoolean();
        Thread probe = new Thread(() -> {
            if (lock.tryAcquire(1_000L, null))
            {
                try
                {
                    free.set(true);
                }
                finally
                {
                    lock.unlock();
                }
            }
        }, "test: reentrancy balance probe");
        probe.start();
        probe.join(5_000L);
        assertTrue("balanced unlocks must leave the lock free for another thread", free.get());
    }

    @Test
    public void testHeldLockIsRefusedAtTheDeadline() throws Exception
    {
        LaunchLock lock = LaunchLifecycleUtils.lockFor("ContendedProject", "app-contended");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> LaunchLifecycleUtils.holdLockForTest("ContendedProject",
            "app-contended", () -> {
                held.countDown();
                try
                {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }), "test: held launch lock");
        holder.start();
        try
        {
            assertTrue("the holder must be provably holding the lock", held.await(5,
                TimeUnit.SECONDS));
            assertFalse("a held lock must be refused at the deadline, not waited on forever",
                lock.tryAcquire(200L, null));
        }
        finally
        {
            release.countDown();
            holder.join(5_000L);
            assertFalse(holder.isAlive());
        }
    }

    @Test
    public void testPrepareForFreshLaunchRefusesWhenTheLockIsHeld() throws Exception
    {
        IProject project = Mockito.mock(IProject.class);
        Mockito.when(project.getName()).thenReturn("LockedPrepProject");
        ILaunchManager launchManager = Mockito.mock(ILaunchManager.class);
        List<String> phases = new ArrayList<>();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> LaunchLifecycleUtils.holdLockForTest("LockedPrepProject",
            "app-prep", () -> {
                held.countDown();
                try
                {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }), "test: held launch lock (prepare)");
        holder.start();
        PreLaunchResult result;
        try
        {
            assertTrue("the holder must be provably holding the lock", held.await(5,
                TimeUnit.SECONDS));
            result = LaunchLifecycleUtils.prepareForFreshLaunch(launchManager, project, "app-prep",
                null, 1, null, null, phases::add, 200L);
        }
        finally
        {
            release.countDown();
            holder.join(5_000L);
            assertFalse(holder.isAlive());
        }

        assertFalse("a contended preparation must not report success", result.isOk());
        assertEquals("no guarded stage may have been entered", 0, phases.size());
        Mockito.verify(launchManager, Mockito.never()).getLaunches();
        assertEquals("the refusal must name the reason verbatim",
            "The launch lock for application 'app-prep' in project 'LockedPrepProject' did not "
                + "become available within 200 ms: another operation on that infobase (a database "
                + "update, a launch or a test run) is still holding it. Nothing was terminated, "
                + "recomputed or updated; retry once that operation finishes.",
            result.getError());
        // The broken spellings this could have degraded to: the production bound leaking in
        // instead of the supplied one, and the generic pre-lock validation refusals.
        assertFalse("the supplied bound must be reported, not the production constant",
            result.getError().contains("15 minutes"));
        assertFalse("this must not be reported as a missing launch manager",
            result.getError().contains("Launch manager is not available"));
    }

    @Test
    public void testLockUnavailableMessageRendersEachBound()
    {
        assertEquals("The launch lock for application 'a' in project 'p' did not become available "
            + "within 15 minutes: another operation on that infobase (a database update, a launch "
            + "or a test run) is still holding it.",
            LaunchLifecycleUtils.lockUnavailableMessage("p", "a",
                LaunchLifecycleUtils.OPERATION_LOCK_TIMEOUT_MS));
        assertEquals("15 minutes",
            LaunchLifecycleUtils.describeLockTimeout(LaunchLifecycleUtils.OPERATION_LOCK_TIMEOUT_MS));
        assertEquals("30 seconds",
            LaunchLifecycleUtils.describeLockTimeout(LaunchLifecycleUtils.SESSIONS_LOCK_TIMEOUT_MS));
        assertEquals("5 seconds", LaunchLifecycleUtils.describeLockTimeout(5_000L));
        assertEquals("1 minute", LaunchLifecycleUtils.describeLockTimeout(60_000L));
        assertEquals("1 second", LaunchLifecycleUtils.describeLockTimeout(1_000L));
        assertEquals("200 ms", LaunchLifecycleUtils.describeLockTimeout(200L));
        // The broken spelling a naive seconds-only renderer produces for a sub-second bound.
        assertFalse("a sub-second bound must not read as zero seconds",
            LaunchLifecycleUtils.describeLockTimeout(200L).contains("0 second"));
    }

    /**
     * Reflective constructor invocation because PreLaunchResult's constructor
     * is private (the class is only instantiated by {@code prepareForFreshLaunch}).
     */
    private static PreLaunchResult newResult(boolean ok, int terminated, String error)
    {
        try
        {
            Constructor<PreLaunchResult> ctor = PreLaunchResult.class.getDeclaredConstructor(
                boolean.class, int.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(ok, terminated, error);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Failed to instantiate PreLaunchResult via reflection", e);
        }
    }

    @Test
    public void testPreLaunchResultFieldAccessors()
    {
        PreLaunchResult result = newResult(true, 2, null);
        assertEquals(2, result.getTerminatedCount());
        assertTrue(result.isOk());
        assertNull(result.getError());
    }
    // ---------------------------------------------------------------------
    // canonicalUpdateScope (#411)
    //
    // The reuse keys of run_yaxunit_tests carry the update scope. The scope has a GRAMMAR, so a
    // raw string in a key would split requests that ask for exactly the same preparation — and
    // splitting them is not a slow path either: it means a retry starts a second full test run
    // instead of joining the one already in flight.
    //
    // These pin the equivalences against the very grammar resolveUpdateScope resolves with.
    // ---------------------------------------------------------------------

    @Test
    public void testCanonicalUpdateScopeCollapsesEveryWayOfSayingAll()
    {
        assertEquals("an omitted scope IS 'all'", "all",
            LaunchLifecycleUtils.canonicalUpdateScope(null));
        assertEquals("an empty scope is an omitted scope", "all",
            LaunchLifecycleUtils.canonicalUpdateScope(""));
        assertEquals("blank is empty", "all", LaunchLifecycleUtils.canonicalUpdateScope("   "));
        assertEquals("the keyword is case-insensitive and trimmed", "all",
            LaunchLifecycleUtils.canonicalUpdateScope("  ALL "));
        assertEquals("the other keyword too", "configuration",
            LaunchLifecycleUtils.canonicalUpdateScope(" Configuration "));
    }

    @Test
    public void testCanonicalUpdateScopeIsASetOfNamesNotASpelling()
    {
        // resolveUpdateScope tests MEMBERSHIP in the parsed set and takes its ordering from the
        // project list, so order and repetition cannot make two different preparations.
        assertEquals("order is not part of the request",
            LaunchLifecycleUtils.canonicalUpdateScope("extension:A,extension:B"),
            LaunchLifecycleUtils.canonicalUpdateScope("extension:B, extension:A"));
        assertEquals("a bare token is the same intent as extension:<name>",
            LaunchLifecycleUtils.canonicalUpdateScope("extension:A"),
            LaunchLifecycleUtils.canonicalUpdateScope(" A "));
        assertEquals("a repeated name is one project",
            LaunchLifecycleUtils.canonicalUpdateScope("extension:A"),
            LaunchLifecycleUtils.canonicalUpdateScope("A,extension:A"));
    }

    @Test
    public void testCanonicalUpdateScopeKeepsDifferentRequestsApart()
    {
        assertFalse("two different extensions are two different preparations",
            LaunchLifecycleUtils.canonicalUpdateScope("extension:A")
                .equals(LaunchLifecycleUtils.canonicalUpdateScope("extension:B")));
        assertFalse("'configuration' skips the extensions, 'all' does not",
            LaunchLifecycleUtils.canonicalUpdateScope("configuration")
                .equals(LaunchLifecycleUtils.canonicalUpdateScope("all")));
        // Project names are case-SENSITIVE where resolveUpdateScope compares them, so they must
        // not be folded here either.
        assertFalse("an extension name differing only in case is a different name",
            LaunchLifecycleUtils.canonicalUpdateScope("extension:Ext")
                .equals(LaunchLifecycleUtils.canonicalUpdateScope("extension:ext")));
    }

    /**
     * A scope that parses to no usable name always FAILS validation, and the error quotes the
     * caller's own string — so two such scopes must not collapse into one key and hand one caller
     * the other caller's message.
     */
    @Test
    public void testCanonicalUpdateScopeKeepsUnusableScopesApart()
    {
        assertFalse("two unusable scopes are not interchangeable",
            LaunchLifecycleUtils.canonicalUpdateScope("extension:")
                .equals(LaunchLifecycleUtils.canonicalUpdateScope(",")));
        // And an unusable scope can never be mistaken for a usable one: the four output spaces
        // ('all', 'configuration', 'extension:<names>', 'raw:<value>') are disjoint.
        assertTrue("an unusable scope lands in its own namespace",
            LaunchLifecycleUtils.canonicalUpdateScope("extension:").startsWith("raw:"));
        assertFalse("a name that merely looks like the namespace is still a name",
            LaunchLifecycleUtils.canonicalUpdateScope("raw:").startsWith("raw:"));
    }
}
