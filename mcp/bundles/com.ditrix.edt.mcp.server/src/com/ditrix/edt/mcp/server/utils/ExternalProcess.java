/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Bounded process completion with concurrent pipe draining and tree cleanup. */
public final class ExternalProcess
{
    private static final long CLEANUP_MS = 2000;
    private ExternalProcess() { }

    @FunctionalInterface
    public interface InputWriter
    {
        void write(Process process) throws IOException;
    }

    public record Result(int exitCode, byte[] stdout, byte[] stderr, boolean timedOut) { }

    public static Result await(Process process, long timeoutMs, InputWriter input) throws Exception
    {
        FutureTask<byte[]> stdout = drain(process.getInputStream());
        FutureTask<byte[]> stderr = drain(process.getErrorStream());
        try
        {
            input.write(process);
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) { destroyTree(process); }
            return new Result(finished ? process.exitValue() : -1,
                stdout.get(CLEANUP_MS, TimeUnit.MILLISECONDS),
                stderr.get(CLEANUP_MS, TimeUnit.MILLISECONDS), !finished);
        }
        catch (InterruptedException e)
        {
            destroyTree(process);
            Thread.currentThread().interrupt();
            throw e;
        }
        finally
        {
            if (process.isAlive()) { destroyTree(process); }
            stdout.cancel(true);
            stderr.cancel(true);
            process.getInputStream().close();
            process.getErrorStream().close();
        }
    }

    private static FutureTask<byte[]> drain(InputStream stream)
    {
        FutureTask<byte[]> task = new FutureTask<>(stream::readAllBytes);
        Thread thread = new Thread(task, "EDT-MCP process output reader");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    public static void destroyTree(Process process)
    {
        destroyDescendants(process::descendants);
        process.destroyForcibly();
        try { process.waitFor(CLEANUP_MS, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Launch Services exposes a ProcessHandle rather than a Java Process. */
    public static void destroyTree(ProcessHandle process)
    {
        destroyDescendants(process::descendants);
        process.destroyForcibly();
        try { process.onExit().get(CLEANUP_MS, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the external process to terminate.", e);
        }
        catch (Exception e) { throw new IllegalStateException("The external process did not terminate; retry terminate_launch.", e); }
    }

    private static void destroyDescendants(java.util.function.Supplier<java.util.stream.Stream<ProcessHandle>> children)
    {
        List<ProcessHandle> descendants;
        try (var stream = children.get()) { descendants = stream.toList(); }
        catch (UnsupportedOperationException e) { descendants = List.of(); }
        catch (RuntimeException e)
        {
            com.ditrix.edt.mcp.server.Activator.logWarning("Could not enumerate external process descendants: " + e);
            descendants = List.of();
        }
        for (int i = descendants.size() - 1; i >= 0; i--)
        {
            try { descendants.get(i).destroyForcibly(); }
            catch (RuntimeException e)
            {
                com.ditrix.edt.mcp.server.Activator.logWarning("Could not terminate an external process descendant: " + e);
            }
        }
    }
}
