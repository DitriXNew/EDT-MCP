// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

/** Shared workspace host with no workbench or client-facing standard streams. */
public class StdioHostApplication implements IApplication
{
    interface Runtime
    {
        boolean lockWorkspace() throws Exception;
        void initialize() throws Exception;
        /**
         * Arms the client-requested shutdown channel on the running MCP server. Called before the
         * IPC listener exists, so a request that arrives during workspace loading is not lost, and
         * before {@link #startCore}, so the channel is never live but unarmed.
         *
         * @param shutdown accepts a callback to receive the result of workspace cleanup
         */
        void armHostShutdown(Consumer<Consumer<Boolean>> shutdown);
        default void stopTransport() { }
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
    private volatile Consumer<Boolean> shutdownCompletion;

    public StdioHostApplication() { this(new StdioHostPlatform()); }
    StdioHostApplication(Runtime runtime) { this.runtime = runtime; }

    /**
     * The longest idle timeout {@link HeadlessLifetime} can arm: it schedules the expiry in
     * nanoseconds, and a {@code long} holds about 292 years of them.
     */
    static final long MAX_IDLE_MINUTES = Long.MAX_VALUE / TimeUnit.MINUTES.toNanos(1);

    static Duration idleDuration(String value)
    {
        long minutes = value == null ? 30 : Long.parseLong(value);
        if (minutes < 0) { throw new IllegalArgumentException("edt.mcp.headlessIdleMinutes must be nonnegative"); }
        if (minutes > MAX_IDLE_MINUTES) {
            throw new IllegalArgumentException("edt.mcp.headlessIdleMinutes must be at most " + MAX_IDLE_MINUTES
                + " (0 disables the idle shutdown)");
        }
        return Duration.ofMinutes(minutes);
    }

    @Override public Object start(IApplicationContext context)
    {
        boolean locked = false;
        boolean initialized = false;
        int result = 0;
        try
        {
            Duration idle = idleDuration(System.getProperty("edt.mcp.headlessIdleMinutes"));
            locked = runtime.lockWorkspace();
            if (!locked) {
                throw new IllegalStateException("Workspace is unavailable or in use; close its existing EDT host before retrying");
            }
            initialized = true;
            runtime.initialize();
            try (HeadlessLifetime lifetime = new HeadlessLifetime(idle, this::stop)) {
                runtime.armHostShutdown(completion -> lifetime.requestShutdown(() -> {
                    shutdownCompletion = completion;
                    stop();
                }));
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
            // Idle and framework shutdown keep their original transport teardown order.
            // A command keeps its requesting socket open just long enough for the receipt.
            var completion = shutdownCompletion;
            if (initialized && completion == null) {
                result |= finish(runtime::stopTransport, "Cannot stop headless transport");
            }
            if (initialized) {
                result |= finish(runtime::stopContexts, "Cannot stop headless project contexts");
                result |= finish(runtime::waitForContexts, "Cannot await headless project contexts");
                result |= finish(runtime::save, "Cannot save headless workspace during shutdown");
            }
            if (locked) { result |= finish(runtime::release, "Cannot release headless workspace lock"); }
            try {
                if (completion != null) { completion.accept(result == 0); }
            } catch (RuntimeException e) {
                runtime.failure("Cannot deliver headless shutdown result", e);
            } finally {
                if (completion != null) { result |= finish(runtime::stopTransport, "Cannot stop headless transport"); }
            }
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
