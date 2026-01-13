/**
 * Copyright (c) 2025 DitriX
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
import com.ditrix.edt.mcp.server.tools.impl.FindReferencesTool;
import com.ditrix.edt.mcp.server.tools.impl.GetCheckDescriptionTool;
import com.ditrix.edt.mcp.server.tools.impl.GetConfigurationPropertiesTool;
import com.ditrix.edt.mcp.server.tools.impl.GetContentAssistTool;
import com.ditrix.edt.mcp.server.tools.impl.GetEdtVersionTool;
import com.ditrix.edt.mcp.server.tools.impl.GetMetadataDetailsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetMetadataObjectsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetPlatformDocumentationTool;
import com.ditrix.edt.mcp.server.tools.impl.GetProblemSummaryTool;
import com.ditrix.edt.mcp.server.tools.impl.GetProjectErrorsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetTasksTool;
import com.ditrix.edt.mcp.server.tools.impl.ListProjectsTool;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool;
import com.ditrix.edt.mcp.server.tools.impl.RevalidateObjectsTool;
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
                sendResponse(exchange, 403, "{\"jsonrpc\": \"2.0\", \"error\": {\"code\": -32600, \"message\": \"Invalid Origin\"}}"); //$NON-NLS-1$
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
                sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}"); //$NON-NLS-1$
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

            try
            {
                response = protocolHandler.processRequest(requestBody);
                
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
                response = "{\"jsonrpc\": \"2.0\", \"error\": {\"code\": -32603, \"message\": \"" //$NON-NLS-1$
                    + e.getMessage() + "\"}, \"id\": null}"; //$NON-NLS-1$
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
         * As per spec: server MAY initiate SSE stream or return 405.
         */
        private void handleSseStream(HttpExchange exchange) throws IOException
        {
            String acceptHeader = exchange.getRequestHeaders().getFirst("Accept"); //$NON-NLS-1$
            
            if (acceptHeader != null && acceptHeader.contains("text/event-stream")) //$NON-NLS-1$
            {
                // Client wants SSE stream - we could support server-initiated messages here
                // For now, return 405 as we don't use server-initiated notifications
                Activator.logInfo("SSE GET request received - returning 405 (not supported)"); //$NON-NLS-1$
                sendResponse(exchange, 405, "{\"error\": \"Server-initiated SSE not supported\"}"); //$NON-NLS-1$
            }
            else
            {
                // Return server info for plain GET requests
                String response = String.format(
                    "{\"name\": \"%s\", \"version\": \"%s\", \"edt_version\": \"%s\", \"protocol_version\": \"%s\", \"status\": \"running\"}", //$NON-NLS-1$
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
            
            String response = "{\"status\": \"ok\", \"edt_version\": \"" + GetEdtVersionTool.getEdtVersion() + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$
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
