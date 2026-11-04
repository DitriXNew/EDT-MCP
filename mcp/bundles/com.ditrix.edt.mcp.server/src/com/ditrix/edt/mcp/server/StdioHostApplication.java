// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

/** Shared workspace host with no workbench or client-facing standard streams. */
public class StdioHostApplication implements IApplication
{
    interface Runtime
    {
        boolean lockWorkspace() throws Exception;
        void initialize() throws Exception;
        void startCore(HeadlessLifetime lifetime) throws Exception;
        boolean ipcRunning();
        void stopContexts() throws Exception;
        void waitForContexts() throws Exception;
        void save() throws Exception;
        void release();
        void failure(String message, Throwable error);
    }

    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Runtime runtime;

    public StdioHostApplication() { this(new StdioHostPlatform()); }
    StdioHostApplication(Runtime runtime) { this.runtime = runtime; }

    static Duration idleDuration(String value)
    {
        long minutes = value == null ? 30 : Long.parseLong(value);
        if (minutes < 0) { throw new IllegalArgumentException("edt.mcp.headlessIdleMinutes must be nonnegative"); }
        Duration duration = Duration.ofMinutes(minutes);
        duration.toNanos();
        return duration;
    }

    @Override public Object start(IApplicationContext context)
    {
        boolean locked = false;
        boolean initialized = false;
        int result = 0;
        try
        {
            Duration idle = idleDuration(System.getProperty("edt.mcp.headlessIdleMinutes"));
            if (!(locked = runtime.lockWorkspace())) {
                throw new IllegalStateException("Workspace is unavailable or in use; close its existing EDT host before retrying");
            }
            initialized = true;
            runtime.initialize();
            try (HeadlessLifetime lifetime = new HeadlessLifetime(idle, this::stop)) {
                // Startup counts as work, including project restoration and import.
                try (var startup = lifetime.tool()) { runtime.startCore(lifetime); }
                if (!runtime.ipcRunning()) { throw new IllegalStateException("Headless IPC listener failed to start; inspect the EDT workspace log"); }
                context.applicationRunning();
                stopped.await();
            }
        }
        catch (Exception | LinkageError e)
        {
            runtime.failure("EDT MCP headless host failed", e);
            result = 1;
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
        }
        finally
        {
            if (initialized) {
                result |= finish(runtime::stopContexts, "Cannot stop headless project contexts");
                result |= finish(runtime::waitForContexts, "Cannot await headless project contexts");
                result |= finish(runtime::save, "Cannot save headless workspace during shutdown");
            }
            if (locked) { result |= finish(runtime::release, "Cannot release headless workspace lock"); }
        }
        return Integer.valueOf(result);
    }

    @FunctionalInterface private interface Step { void run() throws Exception; }
    private int finish(Step step, String message)
    {
        try { step.run(); return 0; }
        catch (Exception | LinkageError e) { runtime.failure(message, e); return 1; }
    }

    @Override public void stop() { stopped.countDown(); }
}
