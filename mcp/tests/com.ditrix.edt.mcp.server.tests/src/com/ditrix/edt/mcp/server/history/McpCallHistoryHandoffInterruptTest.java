/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/** Interruption cannot transfer a file before its previous writer has terminated. */
public class McpCallHistoryHandoffInterruptTest
{
    @Test(timeout = 12000)
    public void preInterruptedHandoffWaitsAndRestoresFlag() throws Exception
    {
        assertHandoff(true, false, false);
    }

    @Test(timeout = 12000)
    public void interruptedHandoffKeepsWaitingAndRestoresFlag() throws Exception
    {
        assertHandoff(false, true, false);
    }

    @Test(timeout = 12000)
    public void firstOpenWaitsForActualPredecessorTermination() throws Exception
    {
        assertHandoff(false, false, true);
    }

    private static void assertHandoff(boolean preInterrupted, boolean interruptDuringWait,
        boolean openDirectly) throws Exception
    {
        Path directory = Files.createTempDirectory("sonar-handoff-interrupt");
        Path file = directory.resolve("new").resolve("history.jsonl");
        McpCallHistoryFileLog previous = new McpCallHistoryFileLog(file);
        McpCallHistoryFileLog next = new McpCallHistoryFileLog(file);
        ExecutorService previousWriter = (ExecutorService)field("writer").get(previous);
        ExecutorService nextWriter = (ExecutorService)field("writer").get(next);
        CountDownLatch previousStarted = new CountDownLatch(1);
        CountDownLatch releasePrevious = new CountDownLatch(1);
        CountDownLatch nextStarted = new CountDownLatch(1);
        AtomicReference<Thread> waitingThread = new AtomicReference<>();
        try
        {
            previousWriter.execute(() -> {
                previousStarted.countDown();
                try
                {
                    releasePrevious.await();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            assertTrue(previousStarted.await(2, TimeUnit.SECONDS));
            previousWriter.shutdown();
            field("predecessor").set(next, previous);
            Future<Boolean> opened = nextWriter.submit(() -> {
                waitingThread.set(Thread.currentThread());
                if (preInterrupted)
                {
                    Thread.currentThread().interrupt();
                }
                nextStarted.countDown();
                try
                {
                    invoke(next, openDirectly ? "ensureOpen" : "awaitPredecessor");
                    boolean interruptedOnReturn = Thread.currentThread().isInterrupted();
                    // Observe the handoff flag before any subsequent file I/O can consume it.
                    Thread.interrupted();
                    if (!openDirectly)
                    {
                        invoke(next, "ensureOpen");
                    }
                    return interruptedOnReturn;
                }
                finally
                {
                    Thread.interrupted();
                }
            });
            assertTrue(nextStarted.await(2, TimeUnit.SECONDS));
            assertStillWaiting(opened);
            if (interruptDuringWait)
            {
                waitingThread.get().interrupt();
                assertStillWaiting(opened);
            }
            assertFalse("the predecessor is still alive", previousWriter.isTerminated());
            assertFalse("no successor filesystem access before handoff", Files.exists(file.getParent()));
            assertNull("the successor has not opened a writer", field("out").get(next));
            releasePrevious.countDown();
            assertEquals(preInterrupted || interruptDuringWait, opened.get(3, TimeUnit.SECONDS));
            assertTrue("handoff requires actual executor termination", previousWriter.isTerminated());
            assertNull(field("predecessor").get(next));
            assertTrue("opening resumes after handoff", Files.exists(file));
        }
        finally
        {
            releasePrevious.countDown();
            assertTrue(previousWriter.awaitTermination(3, TimeUnit.SECONDS));
            next.close();
            assertTrue(nextWriter.awaitTermination(3, TimeUnit.SECONDS));
            // previousWriter was shut down explicitly instead of through close's cleanup seam.
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
            Files.deleteIfExists(directory);
        }
    }

    private static void assertStillWaiting(Future<?> result) throws Exception
    {
        try
        {
            result.get(150, TimeUnit.MILLISECONDS);
            fail("the successor must not pass handoff while the predecessor executor is alive");
        }
        catch (TimeoutException expected)
        {
            assertFalse(result.isDone());
        }
    }

    private static Field field(String name) throws Exception
    {
        Field result = McpCallHistoryFileLog.class.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static void invoke(McpCallHistoryFileLog sink, String name) throws Exception
    {
        Method method = McpCallHistoryFileLog.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(sink);
    }
}
