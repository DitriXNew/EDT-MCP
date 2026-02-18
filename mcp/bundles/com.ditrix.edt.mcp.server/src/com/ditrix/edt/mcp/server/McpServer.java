/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.tools.impl.GetBookmarksTool;
import com.ditrix.edt.mcp.server.tools.impl.DebugLaunchTool;
import com.ditrix.edt.mcp.server.tools.impl.FindReferencesTool;
import com.ditrix.edt.mcp.server.tools.impl.GetApplicationsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetCheckDescriptionTool;
import com.ditrix.edt.mcp.server.tools.impl.GetConfigurationPropertiesTool;
import com.ditrix.edt.mcp.server.tools.impl.GetContentAssistTool;
import com.ditrix.edt.mcp.server.tools.impl.GetEdtVersionTool;
import com.ditrix.edt.mcp.server.tools.impl.GetFormScreenshotTool;
import com.ditrix.edt.mcp.server.tools.impl.GoToDefinitionTool;
import com.ditrix.edt.mcp.server.tools.impl.GetMetadataDetailsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetMetadataObjectsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetPlatformDocumentationTool;
import com.ditrix.edt.mcp.server.tools.impl.GetProblemSummaryTool;
import com.ditrix.edt.mcp.server.tools.impl.GetProjectErrorsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetTagsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetObjectsByTagsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetTasksTool;
import com.ditrix.edt.mcp.server.tools.impl.ListProjectsTool;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool;
import com.ditrix.edt.mcp.server.tools.impl.RevalidateObjectsTool;
import com.ditrix.edt.mcp.server.tools.impl.UpdateDatabaseTool;
import com.ditrix.edt.mcp.server.tools.impl.ReadModuleSourceTool;
import com.ditrix.edt.mcp.server.tools.impl.GetModuleStructureTool;
import com.ditrix.edt.mcp.server.tools.impl.ListModulesTool;
import com.ditrix.edt.mcp.server.tools.impl.SearchInCodeTool;
import com.ditrix.edt.mcp.server.tools.impl.ReadMethodSourceTool;
import com.ditrix.edt.mcp.server.tools.impl.GetMethodCallHierarchyTool;
import com.ditrix.edt.mcp.server.tools.impl.ValidateQueryTool;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
        server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // MCP endpoints
        server.createContext("/mcp", new McpHandler()); //$NON-NLS-1$
        server.createContext("/health", new HealthHandler()); //$NON-NLS-1$
        
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        running = true;
        
        Activator.logInfo("MCP Server started on port " + port); //$NON-NLS-1$
    }

    /**
     * Registers all MCP tools.
     */
    private void registerTools()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        
        // Clear existing tools
        registry.clear();
        
        // Register built-in tools
        registry.register(new GetEdtVersionTool());
        registry.register(new ListProjectsTool());
        registry.register(new GetConfigurationPropertiesTool());
        registry.register(new CleanProjectTool());
        registry.register(new RevalidateObjectsTool());
        registry.register(new GetProblemSummaryTool());
        registry.register(new GetProjectErrorsTool());
        registry.register(new GetBookmarksTool());
        registry.register(new GetTasksTool());
        registry.register(new GetCheckDescriptionTool());
        registry.register(new GetContentAssistTool());
        registry.register(new GetPlatformDocumentationTool());
        registry.register(new GetMetadataObjectsTool());
        registry.register(new GetMetadataDetailsTool());
        registry.register(new FindReferencesTool());
        
        // Tag tools
        registry.register(new GetTagsTool());
        registry.register(new GetObjectsByTagsTool());
        
        // Application tools
        registry.register(new GetApplicationsTool());
        registry.register(new UpdateDatabaseTool());
        registry.register(new DebugLaunchTool());

        // BSL code analysis tools
        registry.register(new ReadModuleSourceTool());
        registry.register(new GetModuleStructureTool());
        registry.register(new ListModulesTool());
        registry.register(new SearchInCodeTool());
        registry.register(new ReadMethodSourceTool());
        registry.register(new GetMethodCallHierarchyTool());
        registry.register(new GoToDefinitionTool());
        registry.register(new GetFormScreenshotTool());
        registry.register(new ValidateQueryTool());

        Activator.logInfo("Registered " + registry.getToolCount() + " MCP tools"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Registers a custom tool.
     * 
     * @param tool the tool to register
     */
    public void registerTool(IMcpTool tool)
    {
        McpToolRegistry.getInstance().register(tool);
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
     * Resets the request counter.
     */
    public void resetRequestCount()
    {
        requestCount.set(0);
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
     * Checks if a user signal is pending.
     * 
     * @return true if a signal is pending
     */
    public boolean hasUserSignal()
    {
        return userSignal != null;
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

    /**
     * MCP request handler.
     * Implements Streamable HTTP transport as per MCP 2025-11-25 specification.
     */
    private class McpHandler implements HttpHandler
    {
        /** Event ID counter for SSE */
        private long eventIdCounter = 0;
        
        @Override
        public void handle(HttpExchange exchange) throws IOException
        {
            String method = exchange.getRequestMethod();
            
            // Validate Origin header for security (DNS rebinding prevention)
            String origin = exchange.getRequestHeaders().getFirst("Origin"); //$NON-NLS-1$
            if (origin != null && !isValidOrigin(origin))
            {
                Activator.logInfo("Invalid Origin header rejected: " + origin); //$NON-NLS-1$
                sendResponse(exchange, 403, com.ditrix.edt.mcp.server.protocol.JsonUtils.buildJsonRpcError(
                    McpConstants.ERROR_INVALID_REQUEST, "Invalid Origin", null)); //$NON-NLS-1$
                return;
            }
            
            // Add CORS headers for valid origins
            if (origin != null)
            {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin); //$NON-NLS-1$
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS"); //$NON-NLS-1$ //$NON-NLS-2$
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Accept"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            // Handle CORS preflight request
            if ("OPTIONS".equals(method)) //$NON-NLS-1$
            {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            
            if ("POST".equals(method)) //$NON-NLS-1$
            {
                handleMcpRequest(exchange);
            }
            else if ("GET".equals(method)) //$NON-NLS-1$
            {
                handleSseStream(exchange);
            }
            else if ("DELETE".equals(method)) //$NON-NLS-1$
            {
                // Session termination - accept but we don't track sessions currently
                sendResponse(exchange, 200, ""); //$NON-NLS-1$
            }
            else
            {
                sendResponse(exchange, 405, com.ditrix.edt.mcp.server.protocol.JsonUtils.buildSimpleError("Method not allowed")); //$NON-NLS-1$
            }
        }
        
        /**
         * Validates Origin header for security.
         */
        private boolean isValidOrigin(String origin)
        {
            // Allow localhost origins, file:// origins, and "null" (for local file HTML)
            return origin.startsWith("http://localhost") || //$NON-NLS-1$
                   origin.startsWith("http://127.0.0.1") || //$NON-NLS-1$
                   origin.startsWith("https://localhost") || //$NON-NLS-1$
                   origin.startsWith("https://127.0.0.1") || //$NON-NLS-1$
                   origin.startsWith("file://") || //$NON-NLS-1$
                   origin.equals("null") || //$NON-NLS-1$ // Local HTML files send "null" as origin
                   origin.startsWith("vscode-webview://"); //$NON-NLS-1$
        }

        private void handleMcpRequest(HttpExchange exchange) throws IOException
        {
            // Increment request counter
            incrementRequestCount();
            
            Activator.logInfo("MCP request received from " + exchange.getRemoteAddress()); //$NON-NLS-1$
            
            // Read request body
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    body.append(line);
                }
            }

            String requestBody = body.toString();
            Activator.logInfo("MCP request body: " + requestBody); //$NON-NLS-1$
            
            String response;
            boolean isInitialize = requestBody.contains("\"" + McpConstants.METHOD_INITIALIZE + "\""); //$NON-NLS-1$ //$NON-NLS-2$
            boolean isToolCall = requestBody.contains("\"" + McpConstants.METHOD_TOOLS_CALL + "\""); //$NON-NLS-1$ //$NON-NLS-2$

            try
            {
                if (isToolCall)
                {
                    // Handle tool calls with interruptible execution
                    response = handleInterruptibleToolCall(exchange, requestBody);
                    if (response == null)
                    {
                        // Response was already sent (user interrupted)
                        return;
                    }
                }
                else
                {
                    response = protocolHandler.processRequest(requestBody);
                }
                
                // null response means notification (no response needed)
                if (response == null)
                {
                    Activator.logInfo("MCP notification processed, returning 202"); //$NON-NLS-1$
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                    return;
                }
                
                Activator.logInfo("MCP response: " + response.substring(0, Math.min(200, response.length())) + "..."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (Exception e)
            {
                Activator.logError("MCP request processing error", e); //$NON-NLS-1$
                response = com.ditrix.edt.mcp.server.protocol.JsonUtils.buildJsonRpcError(
                    McpConstants.ERROR_INTERNAL, e.getMessage(), null);
            }

            // Check if client accepts SSE
            String acceptHeader = exchange.getRequestHeaders().getFirst("Accept"); //$NON-NLS-1$
            boolean acceptsSse = acceptHeader != null && acceptHeader.contains("text/event-stream"); //$NON-NLS-1$
            
            if (acceptsSse)
            {
                // Send response as SSE event
                sendSseResponse(exchange, response, isInitialize);
            }
            else
            {
                // Send as plain JSON - add session header for initialize
                if (isInitialize)
                {
                    exchange.getResponseHeaders().add(McpConstants.HEADER_SESSION_ID, generateSessionId());
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
                sendResponse(exchange, 200, response);
            }
        }
        
        /**
         * Handles tool call with support for user interruption.
         * Runs tool execution in a separate thread and monitors for user signals.
         * 
         * @param exchange the HTTP exchange
         * @param requestBody the request body
         * @return the response, or null if response was already sent (interrupted)
         */
        private String handleInterruptibleToolCall(HttpExchange exchange, String requestBody) throws Exception
        {
            // Extract request ID and tool name for ActiveToolCall
            Object requestId = extractRequestId(requestBody);
            String toolName = extractToolName(requestBody);
            
            // Create and register active tool call
            ActiveToolCall activeCall = new ActiveToolCall(exchange, toolName, requestId);
            setActiveToolCall(activeCall);
            
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
                            clearActiveToolCall();
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
            clearActiveToolCall();
            
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
        
        /**
         * Extracts request ID from JSON-RPC request using Gson.
         */
        private Object extractRequestId(String requestBody)
        {
            try
            {
                com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(requestBody);
                if (element.isJsonObject())
                {
                    com.google.gson.JsonObject jsonObject = element.getAsJsonObject();
                    if (jsonObject.has("id")) //$NON-NLS-1$
                    {
                        com.google.gson.JsonElement idElement = jsonObject.get("id"); //$NON-NLS-1$
                        if (idElement.isJsonPrimitive())
                        {
                            com.google.gson.JsonPrimitive primitive = idElement.getAsJsonPrimitive();
                            if (primitive.isString())
                            {
                                return primitive.getAsString();
                            }
                            else if (primitive.isNumber())
                            {
                                return primitive.getAsLong();
                            }
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Activator.logError("Failed to extract request ID", e); //$NON-NLS-1$
            }
            return null;
        }
        
        /**
         * Extracts tool name from tools/call request using Gson.
         */
        private String extractToolName(String requestBody)
        {
            try
            {
                com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(requestBody);
                if (element.isJsonObject())
                {
                    com.google.gson.JsonObject jsonObject = element.getAsJsonObject();
                    if (jsonObject.has("params")) //$NON-NLS-1$
                    {
                        com.google.gson.JsonObject params = jsonObject.getAsJsonObject("params"); //$NON-NLS-1$
                        if (params.has("name")) //$NON-NLS-1$
                        {
                            return params.get("name").getAsString(); //$NON-NLS-1$
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Activator.logError("Failed to extract tool name", e); //$NON-NLS-1$
            }
            return "unknown"; //$NON-NLS-1$
        }
        
        /**
         * Generates a simple session ID.
         */
        private String generateSessionId()
        {
            return java.util.UUID.randomUUID().toString();
        }
        
        /**
         * Sends response as SSE event stream.
         * As per MCP 2025-11-25: should include event ID for reconnection.
         */
        private void sendSseResponse(HttpExchange exchange, String response, boolean isInitialize) throws IOException
        {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.getResponseHeaders().add("Cache-Control", "no-cache"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.getResponseHeaders().add("Connection", "keep-alive"); //$NON-NLS-1$ //$NON-NLS-2$
            
            // Add session ID for initialize response
            if (isInitialize)
            {
                exchange.getResponseHeaders().add(McpConstants.HEADER_SESSION_ID, generateSessionId());
            }
            
            // Build SSE message with event ID (per 2025-11-25 spec)
            long eventId = ++eventIdCounter;
            StringBuilder sseMessage = new StringBuilder();
            sseMessage.append("event: message\n"); //$NON-NLS-1$
            sseMessage.append("id: ").append(eventId).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sseMessage.append("data: ").append(response).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            
            byte[] bytes = sseMessage.toString().getBytes(StandardCharsets.UTF_8);
            
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody())
            {
                os.write(bytes);
                os.flush();
            }
        }
        
        /**
         * Handles GET request for SSE stream.
         * As per MCP Streamable HTTP spec: supports SSE GET for clients like LM Studio
         * that require an established SSE stream before sending POST requests.
         * The server keeps the connection alive with periodic heartbeats.
         */
        private void handleSseStream(HttpExchange exchange) throws IOException
        {
            String acceptHeader = exchange.getRequestHeaders().getFirst("Accept"); //$NON-NLS-1$
            
            if (acceptHeader != null && acceptHeader.contains("text/event-stream")) //$NON-NLS-1$
            {
                Activator.logInfo("SSE GET request received - opening SSE stream"); //$NON-NLS-1$
                
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); //$NON-NLS-1$ //$NON-NLS-2$
                exchange.getResponseHeaders().add("Cache-Control", "no-cache"); //$NON-NLS-1$ //$NON-NLS-2$
                exchange.getResponseHeaders().add("Connection", "keep-alive"); //$NON-NLS-1$ //$NON-NLS-2$
                exchange.sendResponseHeaders(200, 0);
                
                // Keep SSE stream open with periodic heartbeat comments
                try (java.io.OutputStream os = exchange.getResponseBody())
                {
                    while (!Thread.currentThread().isInterrupted())
                    {
                        try
                        {
                            byte[] heartbeat = ": keep-alive\n\n".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
                            os.write(heartbeat);
                            os.flush();
                            Thread.sleep(15000);
                        }
                        catch (InterruptedException e)
                        {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        catch (IOException e)
                        {
                            // Client disconnected
                            break;
                        }
                    }
                }
                catch (IOException e)
                {
                    // Client disconnected before headers were sent
                }
                Activator.logInfo("SSE stream closed"); //$NON-NLS-1$
            }
            else
            {
                // Return server info for plain GET requests
                String response = com.ditrix.edt.mcp.server.protocol.JsonUtils.buildServerInfo(
                    McpConstants.SERVER_NAME,
                    McpConstants.PLUGIN_VERSION,
                    GetEdtVersionTool.getEdtVersion(),
                    McpConstants.PROTOCOL_VERSION);
                exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
                sendResponse(exchange, 200, response);
            }
        }
    }

    /**
     * Server health check handler.
     */
    private class HealthHandler implements HttpHandler
    {
        @Override
        public void handle(HttpExchange exchange) throws IOException
        {
            // Add CORS headers for health check
            String origin = exchange.getRequestHeaders().getFirst("Origin"); //$NON-NLS-1$
            if (origin != null)
            {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin); //$NON-NLS-1$
            }
            
            // Handle OPTIONS preflight
            if ("OPTIONS".equals(exchange.getRequestMethod())) //$NON-NLS-1$
            {
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS"); //$NON-NLS-1$ //$NON-NLS-2$
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            
            String response = com.ditrix.edt.mcp.server.protocol.JsonUtils.buildHealthResponse(GetEdtVersionTool.getEdtVersion());
            exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
            sendResponse(exchange, 200, response);
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException
    {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody())
        {
            os.write(bytes);
        }
    }
}
