/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/** A blocked I/O call can survive executor cancellation; closing must respect its ownership. */
public class McpCallHistoryFileLogSonarFindingsTest
{
    @Test(timeout = 15000)
    public void interruptedCloseLeavesFlushAndCloseWithWriter() throws Exception
    {
        assertCloseKeepsWriterOwnership(true);
    }

    @Test(timeout = 15000)
    public void timedOutCloseLeavesFlushAndCloseWithWriter() throws Exception
    {
        assertCloseKeepsWriterOwnership(false);
    }

    private static void assertCloseKeepsWriterOwnership(boolean interruptCloser) throws Exception
    {
        McpCallHistoryFileLog sink = new McpCallHistoryFileLog(Path.of("unused-sonar-history.jsonl")); //$NON-NLS-1$
        BlockingWriter output = new BlockingWriter();
        Field out = McpCallHistoryFileLog.class.getDeclaredField("out"); //$NON-NLS-1$
        out.setAccessible(true);
        out.set(sink, output);
        Field writer = McpCallHistoryFileLog.class.getDeclaredField("writer"); //$NON-NLS-1$
        writer.setAccessible(true);
        ExecutorService executor = (ExecutorService)writer.get(sink);
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try
            {
                if (interruptCloser)
                {
                    Thread.currentThread().interrupt();
                }
                sink.close();
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
            catch (Throwable failure)
            {
                closeFailure.set(failure);
            }
        }, "sonar-history-closer"); //$NON-NLS-1$
        try
        {
            sink.enqueue("first"); //$NON-NLS-1$
            assertTrue("the writer must enter the blocked I/O call", output.entered.await(2, TimeUnit.SECONDS)); //$NON-NLS-1$
            sink.enqueue("second"); //$NON-NLS-1$
            closer.start();
            closer.join(7000);

            assertFalse("close must return within its budget even when I/O ignores interruption", closer.isAlive()); //$NON-NLS-1$
            assertEquals(null, closeFailure.get());
            assertEquals(interruptCloser, interruptPreserved.get());
            assertEquals("a live writer must not be closed by the caller", 1L, output.closed.getCount()); //$NON-NLS-1$
            assertFalse("a caller must not drain concurrently with blocked I/O", output.foreignWrite.get()); //$NON-NLS-1$

            output.release.countDown();
            assertTrue("queued cleanup must finish after I/O returns", executor.awaitTermination(3, TimeUnit.SECONDS)); //$NON-NLS-1$
            assertEquals("first\nsecond\n", output.text.toString()); //$NON-NLS-1$
            assertEquals(0L, output.closed.getCount());
            assertSame(output.owner, output.closedBy);
        }
        finally
        {
            output.release.countDown();
            closer.join(7000);
            sink.close();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /** Models a filesystem write that stays blocked despite shutdownNow's interrupt. */
    private static final class BlockingWriter extends BufferedWriter
    {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicBoolean foreignWrite = new AtomicBoolean();
        final StringBuffer text = new StringBuffer();
        volatile Thread owner;
        volatile Thread closedBy;

        BlockingWriter()
        {
            super(new StringWriter());
        }

        @Override
        public void write(String value)
        {
            if (owner == null)
            {
                owner = Thread.currentThread();
                entered.countDown();
                boolean interrupted = false;
                while (release.getCount() != 0)
                {
                    try
                    {
                        release.await();
                    }
                    catch (InterruptedException e)
                    {
                        interrupted = true;
                    }
                }
                if (interrupted)
                {
                    Thread.currentThread().interrupt();
                }
            }
            foreignWrite.compareAndSet(false, Thread.currentThread() != owner);
            text.append(value);
        }

        @Override
        public void write(int value)
        {
            foreignWrite.compareAndSet(false, Thread.currentThread() != owner);
            text.append((char)value);
        }

        @Override
        public void flush()
        {
            // The test has no real filesystem buffer.
        }

        @Override
        public void close()
        {
            closedBy = Thread.currentThread();
            closed.countDown();
        }
    }
}
