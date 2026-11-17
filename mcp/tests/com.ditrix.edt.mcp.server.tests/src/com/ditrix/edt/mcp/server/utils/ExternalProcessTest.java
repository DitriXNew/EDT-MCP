package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.Test;

public class ExternalProcessTest
{
    private Process process() throws Exception
    {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        return process;
    }

    @Test(timeout = 10000)
    public void bothPipesAreDrainedBeforeWaitCompletes() throws Exception
    {
        Process process = process();
        PipedInputStream out = new PipedInputStream();
        PipedInputStream err = new PipedInputStream();
        PipedOutputStream outWriter = new PipedOutputStream(out);
        PipedOutputStream errWriter = new PipedOutputStream(err);
        when(process.getInputStream()).thenReturn(out);
        when(process.getErrorStream()).thenReturn(err);
        byte[] bytes = new byte[128 * 1024];
        when(process.waitFor(5000, TimeUnit.MILLISECONDS)).thenAnswer(call -> {
            outWriter.write(bytes);
            errWriter.write(bytes);
            outWriter.close();
            errWriter.close();
            return true;
        });
        when(process.exitValue()).thenReturn(3);
        var result = ExternalProcess.await(process, 5000, child -> child.getOutputStream().close());
        assertEquals(3, result.exitCode());
        assertFalse(result.timedOut());
        assertArrayEquals(bytes, result.stdout());
        assertArrayEquals(bytes, result.stderr());
    }

    @Test
    public void timeoutDestroysDescendantsAndParent() throws Exception
    {
        Process process = process();
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(child));
        var result = ExternalProcess.await(process, 1, p -> p.getOutputStream().close());
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        verify(child).destroyForcibly();
        verify(process).destroyForcibly();
    }

    @Test
    public void interruptionKillsTreeAndRestoresInterrupt() throws Exception
    {
        Process process = process();
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(child));
        when(process.waitFor(5, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
        try
        {
            ExternalProcess.await(process, 5, p -> p.getOutputStream().close());
            fail("Expected interruption");
        }
        catch (InterruptedException e)
        {
            assertTrue(Thread.currentThread().isInterrupted());
            verify(child).destroyForcibly();
            verify(process).destroyForcibly();
        }
        finally { Thread.interrupted(); }
    }

    @Test
    public void refusedDescendantKillStillKillsTheParent() throws Exception
    {
        Process process = process();
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(child));
        when(child.destroyForcibly()).thenThrow(new IllegalStateException("already inaccessible"));
        ExternalProcess.destroyTree(process);
        verify(process).destroyForcibly();
    }

    @Test
    public void processHandleLaunchesUseTheSameTreeCleanup()
    {
        ProcessHandle process = mock(ProcessHandle.class);
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.descendants()).thenReturn(Stream.of(child));
        when(process.onExit()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(process));
        ExternalProcess.destroyTree(process);
        var order = inOrder(child, process);
        order.verify(child).destroyForcibly();
        order.verify(process).destroyForcibly();
    }

}
