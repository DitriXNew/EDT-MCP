/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ditrix.edt.mcp.server.history.McpCallHistory.CallCapture;
import com.ditrix.edt.mcp.server.transport.ToolCallResponder;

import com.sun.net.httpserver.HttpExchange;

/** Selects one transport response while an interrupted EDT operation may continue in the background. */
public class ActiveToolCall
{
    private final ToolCallResponder responder;
    private final String toolName;
    private final Object requestId;
    private final long startTime;
    private final AtomicBoolean responded = new AtomicBoolean(false);
    private final CallCapture historyCapture;
    
    /**
     * Creates a new active tool call.
     * 
     * @param exchange the HTTP exchange
     * @param toolName the tool being executed
     * @param requestId the JSON-RPC request ID
     */
    public ActiveToolCall(HttpExchange exchange, String toolName, Object requestId)
    {
        this(exchange, toolName, requestId, null);
    }

    public ActiveToolCall(HttpExchange exchange, String toolName, Object requestId, CallCapture historyCapture)
    { this(new ToolCallResponder.Http(exchange), toolName, requestId, historyCapture); }

    public ActiveToolCall(ToolCallResponder responder, String toolName, Object requestId, CallCapture historyCapture)
    {
        this.responder = responder;
        this.toolName = toolName;
        this.requestId = requestId;
        this.startTime = System.currentTimeMillis();
        this.historyCapture = historyCapture;
    }

    /** Reserves the normal response against a concurrent signal before transport writes it. */
    public synchronized boolean selectNormalResponse(String response)
    {
        if (responded.getAndSet(true))
        {
            return false;
        }
        recordResponse(response, false);
        return true;
    }

    private void recordResponse(String response, boolean interrupted)
    {
        if (historyCapture != null)
        {
            historyCapture.recordResponse(response, interrupted);
        }
    }
    
    /**
     * Gets the tool name.
     * 
     * @return tool name
     */
    public String getToolName()
    {
        return toolName;
    }
    
    /**
     * Gets the request ID.
     * 
     * @return request ID
     */
    public Object getRequestId()
    {
        return requestId;
    }
    
    /**
     * Gets the elapsed time in seconds.
     * 
     * @return elapsed seconds
     */
    public long getElapsedSeconds()
    {
        return (System.currentTimeMillis() - startTime) / 1000;
    }
    
    /**
     * Checks if a response has already been sent.
     * 
     * @return true if responded
     */
    public synchronized boolean hasResponded()
    {
        return responded.get();
    }
    
    /** Sends the signal immediately if no response has been selected, returning control to the agent. */
    public synchronized boolean sendSignalResponse(UserSignal signal)
    {
        if (responded.getAndSet(true))
        {
            // Already responded
            return false;
        }
        
        try
        {
            String jsonResponse = buildSignalResponse(signal);
            
            responder.send(jsonResponse);

            recordResponse(jsonResponse, true);
            
            Activator.logInfo("User signal response sent for tool: " + toolName); //$NON-NLS-1$
            return true;
        }
        catch (IOException e)
        {
            Activator.logError("Failed to send signal response", e); //$NON-NLS-1$
            return false;
        }
        finally
        {
            responder.close();
        }
    }
    
    /**
     * Sends the normal tool response.
     * 
     * @param response the JSON response
     * @return true if response was sent successfully
     */
    public synchronized boolean sendNormalResponse(String response)
    {
        if (responded.getAndSet(true))
        {
            // Already responded (user cancelled)
            return false;
        }
        
        try
        {
            responder.send(response);
            recordResponse(response, false);
            return true;
        }
        catch (IOException e)
        {
            Activator.logError("Failed to send normal response", e); //$NON-NLS-1$
            return false;
        }
        finally
        {
            responder.close();
        }
    }
    
    private String buildSignalResponse(UserSignal signal)
    {
        com.google.gson.JsonObject response = new com.google.gson.JsonObject();
        response.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Build result object
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        com.google.gson.JsonObject textContent = new com.google.gson.JsonObject();
        textContent.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Build message text
        String messageText = String.format(
            "USER SIGNAL: %s%n%nSignal Type: %s%nTool: %s%nElapsed: %ds%n%nNote: The EDT operation may still be running in background.", //$NON-NLS-1$
            signal.getMessage(),
            signal.getType().name(),
            toolName,
            getElapsedSeconds()
        );
        textContent.addProperty("text", messageText); //$NON-NLS-1$
        content.add(textContent);
        result.add("content", content); //$NON-NLS-1$
        // isError, for two reasons that point the same way. It is TRUE: the call was interrupted
        // and never produced the tool's result, so reporting success would tell the agent the
        // opposite of what happened. And it is what keeps the interrupt working at all - this
        // response carries no structuredContent, while tools/list may have advertised the tool's
        // outputSchema, and MCP exempts an error result from that obligation. Without the flag a
        // client that enforces it (Cursor: -32600) discards the very response whose job is to hand
        // control back to the agent (#574).
        result.addProperty("isError", true); //$NON-NLS-1$
        response.add("result", result); //$NON-NLS-1$
        
        // Handle request ID (can be string or number)
        if (requestId instanceof String)
        {
            response.addProperty("id", (String) requestId); //$NON-NLS-1$
        }
        else if (requestId instanceof Number)
        {
            response.addProperty("id", (Number) requestId); //$NON-NLS-1$
        }
        
        return new com.google.gson.Gson().toJson(response);
    }
}
