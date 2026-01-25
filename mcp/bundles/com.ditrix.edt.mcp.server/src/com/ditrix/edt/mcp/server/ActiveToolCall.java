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
            exchange.close();
            
            Activator.logInfo("User signal response sent for tool: " + toolName); //$NON-NLS-1$
            return true;
        }
        catch (IOException e)
        {
            Activator.logError("Failed to send signal response", e); //$NON-NLS-1$
            return false;
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
            exchange.close();
            return true;
        }
        catch (IOException e)
        {
            Activator.logError("Failed to send normal response", e); //$NON-NLS-1$
            return false;
        }
    }
    
    private String buildSignalResponse(UserSignal signal)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\": \"2.0\", \"result\": {\"content\": [{\"type\": \"text\", \"text\": \"");
        sb.append("USER SIGNAL: ").append(escapeJson(signal.getMessage()));
        sb.append("\\n\\nSignal Type: ").append(signal.getType().name());
        sb.append("\\nTool: ").append(toolName);
        sb.append("\\nElapsed: ").append(getElapsedSeconds()).append("s");
        sb.append("\\n\\nNote: The EDT operation may still be running in background.");
        sb.append("\"}]}, \"id\": ");
        
        // Handle request ID (can be string or number)
        if (requestId instanceof String)
        {
            sb.append("\"").append(requestId).append("\"");
        }
        else
        {
            sb.append(requestId);
        }
        sb.append("}");
        
        return sb.toString();
    }
    
    private String escapeJson(String text)
    {
        if (text == null)
        {
            return "";
        }
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
