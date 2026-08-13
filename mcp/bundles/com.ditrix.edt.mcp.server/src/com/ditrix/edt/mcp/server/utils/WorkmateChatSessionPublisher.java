/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ditrix.edt.mcp.server.utils.WorkmateGateway.FailureKind;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.GatewayException;

/**
 * Keeps a JShell session registered under {@link WorkmateGateway#CHAT_SESSION_ID} so
 * 1C:Workmate's chat can reach this plugin's bridge.
 * <p>
 * The chat holds Workmate's {@code JShell} tool but not {@code JShellSession}, so it can
 * run code yet cannot produce the session id that running code requires. Publishing one
 * session under a constant id breaks that deadlock: the project rules can then name the
 * id literally, with nothing to write to disk and nothing for a human to pass along.
 * <p>
 * Everything here is best effort. Workmate is optional and initializes on its own
 * schedule, so a failure is logged once per state change and retried, never propagated:
 * EDT-MCP works exactly as before when Workmate is absent.
 */
public final class WorkmateChatSessionPublisher
{
    /** Delay before the first attempt; Workmate's injector is not up with our bundle. */
    private static final long INITIAL_DELAY_SECONDS = 20;

    /**
     * Re-check period. It stays short on purpose: Workmate initializes on its own
     * schedule, and a long period would leave the chat answering "Session not found"
     * for minutes after Workmate finally came up. Once the session is live each pass
     * costs one cache lookup, and re-registration only happens after Workmate evicts
     * it (12 h idle, or 16 newer sessions).
     */
    private static final long PERIOD_SECONDS = 60;

    private final WorkmateGateway gateway;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean published = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile String lastFailure;

    /** Creates a publisher driving the shared gateway. */
    public WorkmateChatSessionPublisher()
    {
        this(new WorkmateGateway());
    }

    /**
     * Creates a publisher for a specific gateway.
     *
     * @param gateway adapter used to reach Workmate
     */
    public WorkmateChatSessionPublisher(WorkmateGateway gateway)
    {
        this.gateway = gateway;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "edt-mcp-workmate-session"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /** Starts the periodic attempt. Calling it twice is a no-op. */
    public void start()
    {
        if (!started.compareAndSet(false, true))
        {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::publishOnce, INITIAL_DELAY_SECONDS,
            PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the periodic attempt AND drops the session it published.
     * <p>
     * The session must not outlive this bundle: it holds the bridge object bound by earlier
     * snippets, so leaving it alive across an update would keep the chat calling the stopped
     * bundle's bridge and class loader instead of the newly registered one.
     */
    public void stop()
    {
        scheduler.shutdownNow();
        if (published.getAndSet(false))
        {
            gateway.discardChatSession();
        }
    }

    /**
     * Runs one attempt and reports whether the constant session is live.
     *
     * @return {@code true} when the session is reachable under the constant id
     */
    public boolean publishOnce()
    {
        try
        {
            gateway.ensureChatSession();
            lastFailure = null;
            if (published.compareAndSet(false, true))
            {
                logInfo("1C:Workmate chat can now reach EDT-MCP: a JShell session is " //$NON-NLS-1$
                    + "registered as '" + WorkmateGateway.CHAT_SESSION_ID + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return true;
        }
        catch (GatewayException e)
        {
            published.set(false);
            if (e.getKind() == FailureKind.INCOMPATIBLE)
            {
                // Workmate's session manager no longer looks the way this adapter expects.
                // That cannot resolve itself, and retrying every minute would only repeat
                // the same log line forever, so stop and leave the chat without the bridge.
                logInfo("Giving up on publishing a JShell session for the 1C:Workmate chat: " //$NON-NLS-1$
                    + e.getMessage());
                stop();
                return false;
            }
            // Workmate starting up produces the same message on every pass; report a
            // given reason once so a missing plugin cannot fill the log.
            String reason = e.getMessage();
            if (reason != null && !reason.equals(lastFailure))
            {
                lastFailure = reason;
                logInfo("Not publishing a JShell session for the 1C:Workmate chat yet: " //$NON-NLS-1$
                    + reason);
            }
            return false;
        }
    }

    private static void logInfo(String message)
    {
        Log.info(message);
    }
}
