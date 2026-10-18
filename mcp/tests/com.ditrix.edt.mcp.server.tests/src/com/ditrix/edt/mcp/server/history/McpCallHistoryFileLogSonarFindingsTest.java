/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.google.gson.JsonObject;

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

    @Test(timeout = 25000)
    public void reopenedFileWaitsForTimedOutPredecessorAndPreservesJsonlOrder() throws Exception
    {
        Path tmp = Files.createTempDirectory("sonar-history-reopen"); //$NON-NLS-1$
        Path xFile = tmp.resolve("X").resolve(McpCallHistoryFileLog.LOG_FILE_NAME); //$NON-NLS-1$
        Path yFile = tmp.resolve("Y").resolve(McpCallHistoryFileLog.LOG_FILE_NAME); //$NON-NLS-1$
        Files.createDirectories(xFile.getParent());
        String header = McpCallHistoryFileLog.warningHeaderLine();
        Files.writeString(xFile, header + "\n", StandardCharsets.UTF_8); //$NON-NLS-1$
        BlockingWriter output = new BlockingWriter(Files.newBufferedWriter(xFile, StandardCharsets.UTF_8,
            StandardOpenOption.APPEND));
        McpCallHistoryFileLog firstX = new McpCallHistoryFileLog(xFile);
        McpCallHistoryFileLog y = new McpCallHistoryFileLog(yFile);
        // The same normalization must govern both unchanged configurations and predecessor lookup.
        McpCallHistoryFileLog secondX = new McpCallHistoryFileLog(tmp.resolve("X/../X") //$NON-NLS-1$
            .resolve(McpCallHistoryFileLog.LOG_FILE_NAME));
        field("out").set(firstX, output); //$NON-NLS-1$
        ExecutorService firstExecutor = executor(firstX);
        ExecutorService secondExecutor = executor(secondX);
        AtomicReference<Throwable> switchFailure = new AtomicReference<>();
        Thread toY = null;
        Thread backToX = null;
        String first = McpCallHistoryFileLog.toJsonLine(Map.of("sink", "first", "n", 1, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "payload", "line 1\nline 2 with a \" quote")); //$NON-NLS-1$ //$NON-NLS-2$
        String firstLast = McpCallHistoryFileLog.toJsonLine(Map.of("sink", "first", "n", 2)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String second = McpCallHistoryFileLog.toJsonLine(Map.of("sink", "second", "n", 1)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String secondLast = McpCallHistoryFileLog.toJsonLine(Map.of("sink", "second", "n", 2)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        try
        {
            swapActive(firstX);
            firstX.enqueue(first);
            assertTrue("first X must be stalled inside a disk write", output.entered.await(2, TimeUnit.SECONDS)); //$NON-NLS-1$
            firstX.enqueue(firstLast);

            toY = swapOnThread(y, switchFailure);
            toY.join(7000);
            assertFalse("switching away from X must retain the bounded close wait", toY.isAlive()); //$NON-NLS-1$
            assertNull(switchFailure.get());
            assertFalse("first X must still be stalled after the close timeout", firstExecutor.isTerminated()); //$NON-NLS-1$

            y.enqueue("{\"sink\":\"Y\"}"); //$NON-NLS-1$
            executor(y).submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals("a different file must remain writable while X is stalled", //$NON-NLS-1$
                2, Files.readAllLines(yFile, StandardCharsets.UTF_8).size());

            backToX = swapOnThread(secondX, switchFailure);
            backToX.join(2000);
            assertFalse("returning to X must not wait for its predecessor on the preference thread", backToX.isAlive()); //$NON-NLS-1$
            assertNull(switchFailure.get());
            secondX.enqueue(second);
            secondX.enqueue(secondLast);
            CountDownLatch secondDrained = new CountDownLatch(1);
            secondExecutor.execute(secondDrained::countDown);

            assertFalse("second X must not finish a disk drain before the first X executor terminates", //$NON-NLS-1$
                secondDrained.await(500, TimeUnit.MILLISECONDS));
            assertNull("second X must not even open the file while first X is stalled", field("out").get(secondX)); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("accepted records must stay queued while waiting for first X", //$NON-NLS-1$
                2, ((BlockingQueue<?>)field("queue").get(secondX)).size()); //$NON-NLS-1$
            assertEquals(List.of(header), Files.readAllLines(xFile, StandardCharsets.UTF_8));

            output.release.countDown();
            assertTrue(firstExecutor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue("second X must resume after its predecessor terminates", secondDrained.await(3, TimeUnit.SECONDS)); //$NON-NLS-1$
            swapActive(null);
            assertTrue(secondExecutor.isTerminated());
            List<String> lines = Files.readAllLines(xFile, StandardCharsets.UTF_8);
            assertEquals("all first-sink records must precede all second-sink records, each on an intact line", //$NON-NLS-1$
                List.of(header, first, firstLast, second, secondLast), lines);
            for (String line : lines)
            {
                assertNotNull(GsonProvider.fromJson(line, JsonObject.class));
            }
        }
        finally
        {
            output.release.countDown();
            if (toY != null)
            {
                toY.join(7000);
            }
            if (backToX != null)
            {
                backToX.join(7000);
            }
            swapActive(null);
            firstX.close();
            y.close();
            secondX.close();
            assertTrue(firstExecutor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(secondExecutor.awaitTermination(3, TimeUnit.SECONDS));
            output.close();
            Files.deleteIfExists(xFile);
            Files.deleteIfExists(yFile);
            Files.deleteIfExists(xFile.getParent());
            Files.deleteIfExists(yFile.getParent());
            Files.deleteIfExists(tmp);
        }
    }

    private static void assertCloseKeepsWriterOwnership(boolean interruptCloser) throws Exception
    {
        Path logFile = Path.of("unused-sonar-history-" + interruptCloser + ".jsonl").toAbsolutePath().normalize(); //$NON-NLS-1$ //$NON-NLS-2$
        McpCallHistoryFileLog sink = new McpCallHistoryFileLog(logFile);
        BlockingWriter output = new BlockingWriter();
        field("out").set(sink, output); //$NON-NLS-1$
        ExecutorService executor = executor(sink);
        Bundle bundle = FrameworkUtil.getBundle(McpCallHistoryFileLog.class);
        assertNotNull("the warning must be observed through the actual OSGi bundle log", bundle); //$NON-NLS-1$
        ILog log = Platform.getLog(bundle);
        List<IStatus> warnings = new CopyOnWriteArrayList<>();
        ILogListener listener = (status, plugin) -> {
            if (status.getSeverity() == IStatus.WARNING && status.getMessage().contains(logFile.toString()))
            {
                warnings.add(status);
            }
        };
        log.addLogListener(listener);
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
        Thread repeatedCloser = new Thread(sink::close, "sonar-history-repeated-closer"); //$NON-NLS-1$
        try
        {
            sink.enqueue("first"); //$NON-NLS-1$
            assertTrue("the writer must enter the blocked I/O call", output.entered.await(2, TimeUnit.SECONDS)); //$NON-NLS-1$
            sink.enqueue("second"); //$NON-NLS-1$
            closer.start();
            if (!interruptCloser)
            {
                repeatedCloser.start();
            }
            closer.join(7000);
            repeatedCloser.join(2000);

            assertFalse("close must return within its budget even when I/O ignores interruption", closer.isAlive()); //$NON-NLS-1$
            assertFalse(repeatedCloser.isAlive());
            assertEquals(null, closeFailure.get());
            assertEquals(interruptCloser, interruptPreserved.get());
            assertEquals("a live writer must not be closed by the caller", 1L, output.closed.getCount()); //$NON-NLS-1$
            assertFalse("a caller must not drain concurrently with blocked I/O", output.foreignWrite.get()); //$NON-NLS-1$
            assertEquals("a timed-out sink must warn exactly once even with repeated close calls", //$NON-NLS-1$
                interruptCloser ? 0 : 1, warnings.size());
            if (!interruptCloser)
            {
                String warning = warnings.get(0).getMessage();
                assertTrue(warning.contains("may be lost")); //$NON-NLS-1$
                assertTrue(warning.contains("EDT exits")); //$NON-NLS-1$
                assertTrue(warning.contains("stalled")); //$NON-NLS-1$
            }

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
            repeatedCloser.join(7000);
            sink.close();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            log.removeLogListener(listener);
        }
    }

    private static Field field(String name) throws ReflectiveOperationException
    {
        Field field = McpCallHistoryFileLog.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static ExecutorService executor(McpCallHistoryFileLog sink) throws ReflectiveOperationException
    {
        return (ExecutorService)field("writer").get(sink); //$NON-NLS-1$
    }

    private static void swapActive(McpCallHistoryFileLog next) throws ReflectiveOperationException
    {
        Method swap = McpCallHistoryFileLog.class.getDeclaredMethod("swapActive", McpCallHistoryFileLog.class); //$NON-NLS-1$
        swap.setAccessible(true);
        swap.invoke(null, next);
    }

    private static Thread swapOnThread(McpCallHistoryFileLog next, AtomicReference<Throwable> failure)
    {
        Thread thread = new Thread(() -> {
            try
            {
                swapActive(next);
            }
            catch (Throwable e)
            {
                failure.set(e);
            }
        }, "sonar-history-preference-apply"); //$NON-NLS-1$
        thread.start();
        return thread;
    }

    /** Models a filesystem write that stays blocked despite shutdownNow's interrupt. */
    private static final class BlockingWriter extends BufferedWriter
    {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicBoolean foreignWrite = new AtomicBoolean();
        final StringBuffer text = new StringBuffer();
        private final BufferedWriter disk;
        volatile Thread owner;
        volatile Thread closedBy;

        BlockingWriter()
        {
            this(null);
        }

        BlockingWriter(BufferedWriter disk)
        {
            super(new StringWriter());
            this.disk = disk;
        }

        @Override
        public void write(String value) throws IOException
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
            if (disk != null)
            {
                disk.write(value);
            }
        }

        @Override
        public void write(int value) throws IOException
        {
            foreignWrite.compareAndSet(false, Thread.currentThread() != owner);
            text.append((char)value);
            if (disk != null)
            {
                disk.write(value);
            }
        }

        @Override
        public void flush() throws IOException
        {
            if (disk != null)
            {
                disk.flush();
            }
        }

        @Override
        public void close() throws IOException
        {
            if (disk != null)
            {
                disk.close();
            }
            closedBy = Thread.currentThread();
            closed.countDown();
        }
    }
}
