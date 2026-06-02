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
import java.util.concurrent.atomic.AtomicLong;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.tools.BuiltInToolRegistrar;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.transport.HealthHandler;
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
    
    /** Request counter - use AtomicLong for thread safety */
    private final AtomicLong requestCount = new AtomicLong(0);
    
    /** Current executing tool name */
    private volatile String currentToolName = null;
    
    /** Timestamp when current tool execution started (milliseconds) */
    private volatile long toolExecutionStartTime = 0;
    
    /** User signal for current operation (cancel, retry, background, expert) */
    private volatile UserSignal userSignal = null;
    
    /** Currently active tool call that can be interrupted */
    private volatile ActiveToolCall activeToolCall = null;
    
    /** Protocol handler */
    private McpProtocolHandler protocolHandler;

    /** Main thread pool for POST/OPTIONS/DELETE requests */
    private ThreadPoolExecutor mainExecutor;

    /** Dedicated thread pool for long-lived SSE connections (isolated from main request pool) */
    private ExecutorService sseExecutor;

    /**
     * Starts the MCP server on the specified port.
     * 
     * @param port the port number
     * @throws IOException if startup fails
     */
    public synchronized void start(int port) throws IOException
    {
        if (running)
        {
            stop();
        }

        // Register tools
        registerTools();
        
        // Create protocol handler
        protocolHandler = new McpProtocolHandler();

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
        // to expose on all interfaces (pair it with PREF_AUTH_TOKEN).
        boolean allowRemote = false;
        Activator activator = Activator.getDefault();
        if (activator != null)
        {
            allowRemote = activator.getPreferenceStore()
                .getBoolean(PreferenceConstants.PREF_ALLOW_REMOTE_ACCESS);
        }
        InetSocketAddress bindAddress = allowRemote
            ? new InetSocketAddress(port)
            : new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        server = HttpServer.create(bindAddress, 0);
        Activator.logInfo("MCP Server binding to " //$NON-NLS-1$
            + (allowRemote ? "all interfaces (remote access enabled)" : "loopback only") //$NON-NLS-1$ //$NON-NLS-2$
            + " on port " + port); //$NON-NLS-1$
        if (allowRemote)
        {
            String authToken = activator != null
                ? activator.getPreferenceStore().getString(PreferenceConstants.PREF_AUTH_TOKEN)
                : ""; //$NON-NLS-1$
            if (authToken == null || authToken.isEmpty())
            {
                Activator.logWarning("SECURITY: MCP server is bound to all interfaces with NO auth token. " //$NON-NLS-1$
                    + "Any host that can reach this port can invoke every tool (including arbitrary BSL). " //$NON-NLS-1$
                    + "Set an auth token in MCP preferences."); //$NON-NLS-1$
            }
        }

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
        mainExecutor = new ThreadPoolExecutor(
            8, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200));
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
     * Restarts the MCP server.
     * 
     * @param port the port number
     * @throws IOException if restart fails
     */
    public void restart(int port) throws IOException
    {
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
        this.userSignal = signal;
    }

    /**
     * Gets and clears the current user signal.
     * Returns null if no signal is pending.
     * 
     * @return the user signal or null
     */
    public UserSignal consumeUserSignal()
    {
        UserSignal signal = this.userSignal;
        this.userSignal = null;
        return signal;
    }

    /**
     * Sets the active tool call.
     * 
     * @param toolCall the active tool call
     */
    public void setActiveToolCall(ActiveToolCall toolCall)
    {
        this.activeToolCall = toolCall;
    }

    /**
     * Gets the active tool call.
     * 
     * @return the active tool call or null
     */
    public ActiveToolCall getActiveToolCall()
    {
        return activeToolCall;
    }

    /**
     * Clears the active tool call.
     */
    public void clearActiveToolCall()
    {
        this.activeToolCall = null;
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
        ActiveToolCall call = this.activeToolCall;
        if (call != null && !call.hasResponded())
        {
            boolean sent = call.sendSignalResponse(signal);
            if (sent)
            {
                // Clear tool execution state atomically
                this.currentToolName = null;
                this.toolExecutionStartTime = 0;
                this.activeToolCall = null;
            }
            return sent;
        }
        return false;
    }
}
