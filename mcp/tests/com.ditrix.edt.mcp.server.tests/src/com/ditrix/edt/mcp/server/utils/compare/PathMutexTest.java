/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/**
 * Unit tests for {@link PathMutex}.
 * <p>
 * Two properties, and neither is visible from the caller that uses it: that a second caller on the
 * SAME path really waits - which is the whole point, because an unserialised read-modify-write
 * loses one caller's work silently - and that a holder does not outlive the callers of its path,
 * because a map of holders that only grows is a leak keyed by every file this server has written.
 * <p>
 * Nothing here touches the filesystem: the holder is keyed by the path as a value, and the paths
 * below deliberately name no real file.
 */
public class PathMutexTest
{
    private static final Path PATH = Paths.get("path-mutex-test", "rules.xml").toAbsolutePath(); //$NON-NLS-1$ //$NON-NLS-2$

    private static final Path OTHER = Paths.get("path-mutex-test", "other.xml").toAbsolutePath(); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Its OWN path, because the test below compares a count before and after. Sharing a path with
     * the tests above makes the comparison blind: a holder they had already left behind is counted
     * on both sides of it, and the count then does not move whether or not this one is forgotten.
     */
    private static final Path OWN = Paths.get("path-mutex-test", "forgotten.xml").toAbsolutePath(); //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void testASecondCallerOnTheSamePathWaits() throws InterruptedException
    {
        PathMutex held = PathMutex.take(PATH);
        AtomicBoolean entered = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        Thread second = new Thread(() -> {
            PathMutex mine = PathMutex.take(PATH);
            entered.set(true);
            mine.release();
            done.countDown();
        });
        try
        {
            second.start();

            assertFalse("the second caller must not get in while the first one holds the path", //$NON-NLS-1$
                done.await(200, TimeUnit.MILLISECONDS));
            assertFalse("and it must not have run its body either", entered.get()); //$NON-NLS-1$
        }
        finally
        {
            held.release();
        }

        assertTrue("and it must get in as soon as the first one leaves", //$NON-NLS-1$
            done.await(10, TimeUnit.SECONDS));
        second.join(10_000L);
    }

    @Test
    public void testADifferentPathIsNotSerialisedAgainstThisOne() throws InterruptedException
    {
        // A single global lock would be correct and useless: two callers collide only when they
        // name the same file.
        PathMutex held = PathMutex.take(PATH);
        CountDownLatch done = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            PathMutex mine = PathMutex.take(OTHER);
            mine.release();
            done.countDown();
        });
        try
        {
            other.start();

            assertTrue("another path must not wait on this one", done.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
        }
        finally
        {
            held.release();
        }
        other.join(10_000L);
    }

    @Test
    public void testTheHolderIsForgottenWhenTheLastCallerLeaves()
    {
        int before = PathMutex.heldCount();

        PathMutex.take(OWN).release();

        assertEquals("a holder nobody wants must not stay in the map", before, //$NON-NLS-1$
            PathMutex.heldCount());
    }
}
