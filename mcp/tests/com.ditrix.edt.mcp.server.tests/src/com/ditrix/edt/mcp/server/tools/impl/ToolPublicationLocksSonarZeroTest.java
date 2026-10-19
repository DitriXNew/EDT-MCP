/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery.StartClaim;

/** Both sides of each publication boundary must contend on its owner's one monitor. */
public class ToolPublicationLocksSonarZeroTest
{
    @Test(timeout = 10000)
    public void standalonePublisherAndCloserUseTheSameMonitor() throws Exception
    {
        LaunchTool.PreparationPublicationLock lock = new LaunchTool.PreparationPublicationLock();
        Object monitor = monitor(lock);
        AtomicReference<LaunchTool.StandalonePreparation> prepared = new AtomicReference<>();
        AtomicBoolean accepting = new AtomicBoolean(true);
        LaunchTool.StandalonePreparation candidate = LaunchTool.StandalonePreparation.failed("prepared");
        AtomicReference<LaunchTool.StandalonePreparation> closed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Method publish = method(LaunchTool.class, "publishStandalonePreparation", AtomicReference.class,
            LaunchTool.PreparationPublicationLock.class, AtomicBoolean.class, LaunchTool.StandalonePreparation.class);
        Method close = method(LaunchTool.class, "closeStandalonePreparationPublication", AtomicReference.class,
            LaunchTool.PreparationPublicationLock.class, AtomicBoolean.class);
        Thread publisher = thread(() -> publish.invoke(null, prepared, lock, accepting, candidate), failure);
        Thread closer = thread(() -> closed.set((LaunchTool.StandalonePreparation)
            close.invoke(null, prepared, lock, accepting)), failure);
        try
        {
            synchronized (monitor)
            {
                publisher.start();
                closer.start();
                assertBlockedOn(publisher, monitor);
                assertBlockedOn(closer, monitor);
                assertNull(prepared.get());
                assertTrue(accepting.get());
            }
        }
        finally
        {
            publisher.join(2000);
            closer.join(2000);
        }
        assertFalse(publisher.isAlive());
        assertFalse(closer.isAlive());
        assertNull(failure.get());
        assertFalse(accepting.get());
        assertSame("closure sees exactly the preparation that won publication", prepared.get(), closed.get());
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("SonarPublicationLockProject");
        String applicationId = "ServerApplication.SonarPublication";
        try (StartClaim lateClaim = StandaloneServerStateRecovery.claimStartWithinBound(project, applicationId, null))
        {
            assertNotNull(lateClaim);
            assertNull("the late preparation owns the start claim before publication",
                StandaloneServerStateRecovery.claimStartWithinBound(project, applicationId, null));
            LaunchTool.StandalonePreparation late = LaunchTool.StandalonePreparation.ready(project, applicationId,
                null, null, null, false, lateClaim);
            publish.invoke(null, prepared, lock, accepting, late);
            try (StartClaim replacement = StandaloneServerStateRecovery.claimStartWithinBound(project, applicationId, null))
            {
                assertNotNull("rejecting a late result releases its unstarted claim", replacement);
            }
            assertSame("late results cannot replace the closed publication", closed.get(), prepared.get());
        }
    }

    @Test(timeout = 10000)
    public void gitDrainAndCaptureUseTheSameMonitorAndKeepTheCap() throws Exception
    {
        GitTool.OutputBuffer output = new GitTool.OutputBuffer();
        Object monitor = monitor(output);
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(
            "x".repeat(GitTool.MAX_OUTPUT_CHARS + 4096).getBytes(StandardCharsets.UTF_8)));
        Method drain = method(GitTool.class, "drainBounded", Process.class, GitTool.OutputBuffer.class);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread drainer = thread(() -> drain.invoke(null, process, output), failure);
        Thread capturer = thread(() -> GitTool.capture(output, true), failure);
        try
        {
            synchronized (monitor)
            {
                drainer.start();
                capturer.start();
                assertBlockedOn(drainer, monitor);
                assertBlockedOn(capturer, monitor);
                assertEquals(0, output.text.length());
                assertFalse(output.truncated);
            }
        }
        finally
        {
            drainer.join(2000);
            capturer.join(2000);
        }
        assertFalse(drainer.isAlive());
        assertFalse(capturer.isAlive());
        assertNull(failure.get());
        GitTool.Capture captured = GitTool.capture(output, true);
        assertEquals("x".repeat(GitTool.MAX_OUTPUT_CHARS), captured.text);
        assertTrue(captured.truncated);
    }

    private static Object monitor(Object owner) throws Exception
    {
        Field field = owner.getClass().getDeclaredField("monitor");
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws Exception
    {
        Method method = owner.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Thread thread(CheckedRunnable work, AtomicReference<Throwable> failure)
    {
        Thread result = new Thread(() -> {
            try
            {
                work.run();
            }
            catch (Throwable e)
            {
                failure.compareAndSet(null, e);
            }
        });
        result.setDaemon(true);
        return result;
    }

    private static void assertBlockedOn(Thread thread, Object monitor) throws Exception
    {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (thread.isAlive() && System.nanoTime() < deadline)
        {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor))
            {
                return;
            }
            Thread.sleep(1);
        }
        fail("both participants must block on the owner's same monitor; state=" + thread.getState());
    }

    @FunctionalInterface
    private interface CheckedRunnable
    {
        void run() throws Exception;
    }
}
