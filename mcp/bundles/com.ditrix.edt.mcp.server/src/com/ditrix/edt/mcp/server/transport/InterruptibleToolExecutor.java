/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import com.ditrix.edt.mcp.server.ActiveToolCall;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.JsonRpcRequest;
import com.sun.net.httpserver.HttpExchange;

/**
 * Runs a {@code tools/call} on a background thread while the request thread polls
 * for a user interrupt signal. If the user interrupts (via {@link McpServer#interruptToolCall}),
 * the signal response is sent on the active call and this returns {@code null}
 * (the caller must not send another response); otherwise it returns the tool's
 * normal JSON-RPC response. The EDT operation itself is not cancelled — it may
 * keep running in the background after an interrupt.
 *
 * <p>Extracted from {@code McpServer} so the transport's interruption concern is
 * isolated from socket lifecycle. JSON id/name extraction reuses
 * {@link McpProtocolHandler#parse}/{@link McpProtocolHandler#normalizeId} (the
 * same path the dispatch uses) rather than re-parsing the body independently.
 */
public class InterruptibleToolExecutor
{
    private final McpServer server;
    private final McpProtocolHandler protocolHandler;

    public InterruptibleToolExecutor(McpServer server, McpProtocolHandler protocolHandler)
    {
        this.server = server;
        this.protocolHandler = protocolHandler;
    }

    /**
     * Handles a tool call with support for user interruption. Runs tool execution
     * in a separate thread and monitors for user signals.
     *
     * @param exchange the HTTP exchange
     * @param requestBody the request body
     * @return the response, or {@code null} if the response was already sent (interrupted)
     * @throws Exception if tool execution failed (propagated to the caller for error mapping)
     */
    public String execute(HttpExchange exchange, String requestBody) throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        // Extract request ID and tool name for ActiveToolCall via the shared parser.
        JsonRpcRequest request = protocolHandler.parse(requestBody);
        Object requestId = request != null ? McpProtocolHandler.normalizeId(request.getId()) : null;
        String toolName = request != null && request.getToolName() != null ? request.getToolName() : "unknown"; //$NON-NLS-1$

        // Create and register active tool call
        ActiveToolCall activeCall = new ActiveToolCall(exchange, toolName, requestId);
        server.setActiveToolCall(activeCall);

        // Use a container to hold the result from the background thread
        final String[] resultContainer = new String[1];
        final Exception[] errorContainer = new Exception[1];
        final boolean[] completedFlag = new boolean[1];

        // Run tool execution in background thread
        Thread executionThread = new Thread(() -> {
            try
            {
                resultContainer[0] = protocolHandler.processRequest(requestBody);
            }
            catch (Exception e)
            {
                errorContainer[0] = e;
            }
            finally
            {
                synchronized (completedFlag)
                {
                    completedFlag[0] = true;
                    completedFlag.notifyAll();
                }
            }
        }, "MCP-Tool-Executor"); //$NON-NLS-1$

        // Daemon: a tool call still running (or stuck) at EDT shutdown must
        // not keep the JVM alive after the workbench has closed (#135).
        executionThread.setDaemon(true);
        executionThread.start();

        // Wait for completion or user signal
        synchronized (completedFlag)
        {
            while (!completedFlag[0])
            {
                try
                {
                    // Check every 100ms for signals
                    completedFlag.wait(100);

                    // Check if user sent an interrupt signal
                    if (activeCall.hasResponded())
                    {
                        // User already sent a response, don't send another
                        server.clearActiveToolCall();
                        return null;
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Clear active tool call
        server.clearActiveToolCall();

        // Check if response was already sent while we were waiting
        if (activeCall.hasResponded())
        {
            return null;
        }

        // Return result or throw error
        if (errorContainer[0] != null)
        {
            throw errorContainer[0];
        }

        return resultContainer[0];
    }
}
