// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/** Process adapter keeps Eclipse's process monitor and terminate_launch on their normal path. */
public final class OwnedClientProcess extends Process
{
    private final IsolatedClientLauncher.OwnedClient owner;
    private final ProcessHandle handle;
    private boolean closed;
    private int code;

    public OwnedClientProcess(IsolatedClientLauncher.OwnedClient owner)
    {
        this.owner = owner;
        this.handle = ProcessHandle.of(owner.pid()).orElse(null);
    }
    @Override public long pid() { return owner.pid(); }
    @Override public ProcessHandle toHandle()
    {
        if (handle == null) { throw new UnsupportedOperationException("The client exited before its process handle could be captured."); }
        return handle;
    }
    @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
    @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    @Override public synchronized boolean isAlive() { return !closed && owner.alive(); }
    @Override public synchronized int exitValue()
    {
        if (closed) { return code; }
        if (owner.alive()) { throw new IllegalThreadStateException("The isolated client is running."); }
        code = owner.exitCode();
        owner.close();
        closed = true;
        return code;
    }
    @Override public int waitFor() throws InterruptedException
    {
        while (isAlive()) { Thread.sleep(100); }
        return exitValue();
    }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
    {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (isAlive())
        {
            if (System.nanoTime() >= deadline) { return false; }
            Thread.sleep(20);
        }
        exitValue();
        return true;
    }
    @Override public synchronized void destroy()
    {
        if (closed) { return; }
        code = owner.alive() ? 1 : owner.exitCode();
        owner.close();
        closed = true;
    }
    @Override public Process destroyForcibly() { destroy(); return this; }
}
