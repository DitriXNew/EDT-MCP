/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.tools.BuiltInToolRegistrar;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.transport.HealthHandler;
import com.ditrix.edt.mcp.server.transport.HttpTransport;
import com.ditrix.edt.mcp.server.transport.InterruptibleToolExecutor;
import com.ditrix.edt.mcp.server.transport.McpHttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * MCP Server for EDT.
 * Provides HTTP endpoint for MCP clients.
 */
public class McpServer
{
    private HttpServer server;
    private int port;
    private volatile boolean running = false;

    /**
     * Whether the LISTENER that is up was bound to every interface. It is the snapshot that
     * passed {@link #remoteBindRefusal}, not a preference read: the preference page persists a
     * changed token without restarting the server, so the live value can no longer describe the
     * socket that is open. The authorizer needs this one, or a token edited to nothing would
     * turn authentication off underneath a listener the network can reach.
     */
    private volatile boolean boundRemotely = false;
    
    /** Request counter - use AtomicLong for thread safety */
    private final AtomicLong requestCount = new AtomicLong(0);
    
    /** Current executing tool name */
    private volatile String currentToolName = null;
    
    /** Timestamp when current tool execution started (milliseconds) */
    private volatile long toolExecutionStartTime = 0;
    
    /** User signal for current operation (cancel, retry, background, expert) */
    private final AtomicReference<UserSignal> userSignal = new AtomicReference<>();

    /** Currently active tool call that can be interrupted */
    private final AtomicReference<ActiveToolCall> activeToolCall = new AtomicReference<>();

    /** Main thread pool for POST/OPTIONS/DELETE requests */
    private ThreadPoolExecutor mainExecutor;

    /** Dedicated thread pool for long-lived SSE connections (isolated from main request pool) */
    private ExecutorService sseExecutor;

    /**
     * Starts the MCP server on the specified port.
     * 
     * @param port the port number
     * @throws IOException if startup fails, or if the configuration would expose an
     *             unauthenticated server to the network (see {@link #remoteBindRefusal})
     */
    public synchronized void start(int port) throws IOException
    {
        // ONE read of the configuration decides both the refusal and the bind address below.
        // Reading the preference twice would let a flip in between bind every interface on a
        // snapshot that had just been validated as loopback.
        BindConfig config = readBindConfig();

        // Fail CLOSED, and before anything is torn down or bound: a running server keeps
        // running, and a refused start never reaches the listening socket.
        String refusal = remoteBindRefusal(config.allowRemote, config.authToken, port);
        if (refusal != null)
        {
            throw new IOException(refusal);
        }

        if (running)
        {
            stop();
        }

        // Register tools
        registerTools();
        
        // Create protocol handler
        McpProtocolHandler protocolHandler = new McpProtocolHandler();

        this.port = port;

        // Configure HTTP server idle interval (seconds) to prevent premature connection drops
        // This sets the time the server waits before closing idle connections
        System.setProperty("sun.net.httpserver.idleInterval", "300"); //$NON-NLS-1$ //$NON-NLS-2$
        // Increase max idle connections to handle concurrent MCP clients
        System.setProperty("sun.net.httpserver.maxIdleConnections", "32"); //$NON-NLS-1$ //$NON-NLS-2$
        // Increase max request time to allow long-running tool operations (10 minutes)
        System.setProperty("sun.net.httpserver.maxReqTime", "600"); //$NON-NLS-1$ //$NON-NLS-2$
        // Increase max response time to allow large responses (10 minutes)
        System.setProperty("sun.net.httpserver.maxRspTime", "600"); //$NON-NLS-1$ //$NON-NLS-2$

        // Bind to loopback only by default: the tool surface includes arbitrary-BSL
        // (evaluate_expression) and destructive tools, so it must not be reachable
        // from the network unless explicitly opted in. Set PREF_ALLOW_REMOTE_ACCESS
        // to expose on all interfaces (it REQUIRES PREF_AUTH_TOKEN - see the refusal above).
        // This is the snapshot that passed that refusal, not a fresh read.
        boolean allowRemote = config.allowRemote;
        InetSocketAddress bindAddress = allowRemote
            ? new InetSocketAddress(port)
            : new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        server = HttpServer.create(bindAddress, 0);
        boundRemotely = allowRemote;
        Activator.logInfo("MCP Server binding to " //$NON-NLS-1$
            + (allowRemote ? "all interfaces (remote access enabled)" : "loopback only") //$NON-NLS-1$ //$NON-NLS-2$
            + " on port " + port); //$NON-NLS-1$

        // MCP endpoints. The transport handlers read the live executors and
        // execution state back through this server instance.
        InterruptibleToolExecutor interruptibleExecutor =
            new InterruptibleToolExecutor(this, protocolHandler);
        server.createContext("/mcp", new McpHttpHandler(this, protocolHandler, interruptibleExecutor)); //$NON-NLS-1$
        server.createContext("/health", new HealthHandler()); //$NON-NLS-1$

        // Main thread pool for POST/OPTIONS/DELETE requests (finite-duration only).
        // Two-level overload protection:
        //   1. Admission control in handle() at threshold 50 — returns 503 instantly
        //   2. Bounded queue (200) — memory safety net; gap of 150 between admission
        //      threshold and queue capacity ensures admission control always drains
        //      the queue before executor rejection can occur.
        // SSE (the only infinite-duration request type) is handled by the dedicated
        // sseExecutor and never enters this pool.
        // Daemon threads: a worker still handling a request at EDT shutdown
        // must not keep the JVM alive after the workbench has closed (#135).
        AtomicInteger mainThreadCounter = new AtomicInteger();
        mainExecutor = new ThreadPoolExecutor(
            8, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "MCP-Main-" + mainThreadCounter.incrementAndGet()); //$NON-NLS-1$
                t.setDaemon(true);
                return t;
            });
        mainExecutor.allowCoreThreadTimeOut(true);
        server.setExecutor(mainExecutor);

        // Dedicated bounded pool for SSE streams (long-lived heartbeat connections).
        // Hard limit of 10 concurrent SSE connections prevents unbounded thread growth.
        // SynchronousQueue ensures immediate handoff; rejection is caught in
        // handleSseInDedicatedPool() and returned as HTTP 503.
        sseExecutor = new ThreadPoolExecutor(
            0, 10, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "MCP-SSE-" + System.currentTimeMillis()); //$NON-NLS-1$
                t.setDaemon(true);
                return t;
            });
        server.start();
        running = true;
        
        Activator.logInfo("MCP Server started on port " + port); //$NON-NLS-1$
    }

    /**
     * Why a start must be refused, or {@code null} when this configuration is safe to start.
     * <p>
     * Remote access binds every interface, and the tool surface includes arbitrary BSL
     * ({@code evaluate_expression}) and destructive tools. The shared-token check in
     * {@code HttpTransport.isAuthorized} is the only thing standing in front of it, and that check
     * is a no-op while the token is empty - so an opt-in to remote access with no token used to
     * degrade, on a log warning nobody reads, into an unauthenticated remote shell. It is refused
     * instead. A loopback bind is unaffected: the token stays optional there.
     * <p>
     * Pure and package-visible so the decision is unit-testable without a preference store.
     *
     * @param allowRemote whether {@code PREF_ALLOW_REMOTE_ACCESS} is set
     * @param authToken the configured {@code PREF_AUTH_TOKEN} (may be {@code null})
     * @param port the port the server would listen on, named in the message
     * @return the actionable refusal, or {@code null} when the start may proceed
     */
    static String remoteBindRefusal(boolean allowRemote, String authToken, int port)
    {
        // What counts as "a token is set" is HttpTransport's definition, not a second one:
        // a value this refusal accepted but the authorizer could never match would bind the
        // port remotely and then reject every request.
        if (!allowRemote || !HttpTransport.normalizeToken(authToken).isEmpty())
        {
            return null;
        }
        // Both callers frame this message themselves ("Failed to start MCP Server: ..."), so it
        // states the cause and the remedy without restating that the start failed.
        return "remote access is enabled but no auth token is set, so port " //$NON-NLS-1$
            + port + " would accept every tool call - including arbitrary BSL execution - from any " //$NON-NLS-1$
            + "host that can reach it. Fix it in EDT Preferences \u2192 MCP Server \u2192 General: " //$NON-NLS-1$
            + "set an auth token, or turn 'Allow remote access' off to listen on loopback only."; //$NON-NLS-1$
    }

    /** The bind-relevant configuration, read together so one decision cannot mix two reads. */
    static final class BindConfig
    {
        final boolean allowRemote;
        final String authToken;

        BindConfig(boolean allowRemote, String authToken)
        {
            this.allowRemote = allowRemote;
            this.authToken = authToken;
        }
    }

    /**
     * Reads the bind-relevant preferences in one go. Package-visible and overridable so a test can
     * supply a configuration without a preference store - {@link #start} and {@link #restart} are
     * otherwise undrivable headlessly, and what they owe is an ORDER, not a value.
     *
     * @return the current configuration; {@code allowRemote} is false and the token {@code null}
     *         when there is no preference store (headless, or a shutdown race)
     */
    BindConfig readBindConfig()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return new BindConfig(false, null);
        }
        return new BindConfig(
            activator.getPreferenceStore().getBoolean(PreferenceConstants.PREF_ALLOW_REMOTE_ACCESS),
            activator.getPreferenceStore().getString(PreferenceConstants.PREF_AUTH_TOKEN));
    }

    /**
     * Registers all MCP tools. Package-private so Activator can call it early
     * to populate descriptions for the preferences UI even if the server hasn't started.
     */
    void registerTools()
    {
        BuiltInToolRegistrar.registerAll(McpToolRegistry.getInstance());
    }

    /**
     * Stops the MCP server.
     */
    public synchronized void stop()
    {
        // Cleared unconditionally: no listener is open after this, whichever way the last start
        // ended, and a stale "bound remotely" must not outlive the socket it described.
        boundRemotely = false;
        if (server != null)
        {
            server.stop(1);
            server = null;
            running = false;
            if (mainExecutor != null)
            {
                mainExecutor.shutdownNow();
                mainExecutor = null;
            }
            if (sseExecutor != null)
            {
                sseExecutor.shutdownNow();
                sseExecutor = null;
            }
            Activator.logInfo("MCP Server stopped"); //$NON-NLS-1$
        }
    }

    /**
     * Restarts the MCP server. A configuration that {@link #remoteBindRefusal} rejects is refused
     * here, with the running server untouched.
     *
     * @param port the port number
     * @throws IOException if restart fails, or if the new configuration would expose an
     *             unauthenticated server to the network - in which case nothing was stopped
     */
    public void restart(int port) throws IOException
    {
        // Decided BEFORE stop(), not inside start(): the preferences page persists the new
        // settings and then restarts, so a reconfiguration that cannot be started would
        // otherwise take a healthy loopback listener offline and report the refusal afterwards.
        // start() checks again on its own snapshot; this one only keeps the running server alive.
        BindConfig config = readBindConfig();
        String refusal = remoteBindRefusal(config.allowRemote, config.authToken, port);
        if (refusal != null)
        {
            throw new IOException(refusal);
        }

        stop();
        start(port);
    }

    /**
     * Checks if the server is running.
     * 
     * @return true if server is running
     */
    public boolean isRunning()
    {
        return running;
    }

    /**
     * Whether the open listener accepts connections from other hosts.
     *
     * @return true if the running server was bound to every interface
     */
    public boolean isBoundRemotely()
    {
        return boundRemotely;
    }

    /**
     * Returns the current port.
     * 
     * @return port number
     */
    public int getPort()
    {
        return port;
    }

    /**
     * Returns the main request thread pool (POST/OPTIONS/DELETE). Used by the
     * transport handler for admission control. May be {@code null} when stopped.
     *
     * @return the main executor or null
     */
    public ThreadPoolExecutor getMainExecutor()
    {
        return mainExecutor;
    }

    /**
     * Returns the dedicated SSE thread pool. Used by the transport handler to
     * offload long-lived SSE streams. May be {@code null} when stopped.
     *
     * @return the SSE executor or null
     */
    public ExecutorService getSseExecutor()
    {
        return sseExecutor;
    }

    /**
     * Returns the request count.
     * 
     * @return number of requests processed
     */
    public long getRequestCount()
    {
        return requestCount.get();
    }

    /**
     * Increments the request counter.
     */
    public void incrementRequestCount()
    {
        requestCount.incrementAndGet();
    }

    /**
     * Returns the currently executing tool name.
     * 
     * @return tool name or null if no tool is executing
     */
    public String getCurrentToolName()
    {
        return currentToolName;
    }

    /**
     * Sets the currently executing tool name.
     * Also records the start time when a tool begins execution.
     * 
     * @param toolName the tool name or null when execution completes
     */
    public void setCurrentToolName(String toolName)
    {
        this.currentToolName = toolName;
        this.toolExecutionStartTime = toolName != null ? System.currentTimeMillis() : 0;
    }

    /**
     * Checks if a tool is currently executing.
     * 
     * @return true if a tool is executing
     */
    public boolean isToolExecuting()
    {
        return currentToolName != null;
    }

    /**
     * Returns the elapsed time in seconds since tool execution started.
     * 
     * @return elapsed seconds or 0 if no tool is executing
     */
    public long getToolExecutionSeconds()
    {
        if (toolExecutionStartTime == 0)
        {
            return 0;
        }
        return (System.currentTimeMillis() - toolExecutionStartTime) / 1000;
    }

    /**
     * Sets a user signal for the current operation.
     * This signal will be included in the tool response.
     * 
     * @param signal the user signal
     */
    public void setUserSignal(UserSignal signal)
    {
        this.userSignal.set(signal);
    }

    /**
     * Gets and clears the current user signal.
     * Returns null if no signal is pending.
     * 
     * @return the user signal or null
     */
    public UserSignal consumeUserSignal()
    {
        // Atomic get-and-clear so a signal is consumed exactly once even under concurrent callers.
        return this.userSignal.getAndSet(null);
    }

    /**
     * Sets the active tool call.
     * 
     * @param toolCall the active tool call
     */
    public void setActiveToolCall(ActiveToolCall toolCall)
    {
        this.activeToolCall.set(toolCall);
    }

    /**
     * Gets the active tool call.
     * 
     * @return the active tool call or null
     */
    public ActiveToolCall getActiveToolCall()
    {
        return activeToolCall.get();
    }

    /**
     * Clears the active tool call.
     */
    public void clearActiveToolCall()
    {
        this.activeToolCall.set(null);
    }

    /**
     * Interrupts the current tool call with a user signal.
     * Sends the signal response immediately and returns control to the agent.
     * This method is thread-safe.
     * 
     * @param signal the user signal
     * @return true if the call was interrupted successfully
     */
    public synchronized boolean interruptToolCall(UserSignal signal)
    {
        ActiveToolCall call = this.activeToolCall.get();
        if (call != null && !call.hasResponded())
        {
            boolean sent = call.sendSignalResponse(signal);
            if (sent)
            {
                // Clear tool execution state atomically
                this.currentToolName = null;
                this.toolExecutionStartTime = 0;
                this.activeToolCall.set(null);
            }
            return sent;
        }
        return false;
    }
}
