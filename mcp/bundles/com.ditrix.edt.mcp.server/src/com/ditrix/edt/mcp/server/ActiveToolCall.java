/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpExchange;

/**
 * Represents an active MCP tool call that can be interrupted by user.
 * When user sends a signal (Cancel, Retry, etc.), the response is sent immediately
 * and the HTTP exchange is closed, while the EDT operation may continue in background.
 */
public class ActiveToolCall
{
    private final HttpExchange exchange;
    private final String toolName;
    private final Object requestId;
    private final long startTime;
    private final AtomicBoolean responded = new AtomicBoolean(false);
    
    /**
     * Creates a new active tool call.
     * 
     * @param exchange the HTTP exchange
     * @param toolName the tool being executed
     * @param requestId the JSON-RPC request ID
     */
    public ActiveToolCall(HttpExchange exchange, String toolName, Object requestId)
    {
        this.exchange = exchange;
        this.toolName = toolName;
        this.requestId = requestId;
        this.startTime = System.currentTimeMillis();
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
    public boolean hasResponded()
    {
        return responded.get();
    }
    
    /**
     * Sends a user signal response and closes the exchange.
     * This interrupts the MCP call and returns control to the agent.
     * 
     * @param signal the user signal to send
     * @return true if response was sent successfully
     */
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
            
            exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            
            try (OutputStream os = exchange.getResponseBody())
            {
                os.write(responseBytes);
            }
            
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
            // Always close exchange to prevent resource leak
            exchange.close();
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
            exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            
            try (OutputStream os = exchange.getResponseBody())
            {
                os.write(responseBytes);
            }
            return true;
        }
        catch (IOException e)
        {
            Activator.logError("Failed to send normal response", e); //$NON-NLS-1$
            return false;
        }
        finally
        {
            // Always close exchange to prevent resource leak
            exchange.close();
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
