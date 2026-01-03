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
import com.ditrix.edt.mcp.server.tools.impl.GetCheckDescriptionTool;
import com.ditrix.edt.mcp.server.tools.impl.GetConfigurationPropertiesTool;
import com.ditrix.edt.mcp.server.tools.impl.GetEdtVersionTool;
import com.ditrix.edt.mcp.server.tools.impl.GetProblemSummaryTool;
import com.ditrix.edt.mcp.server.tools.impl.GetProjectErrorsTool;
import com.ditrix.edt.mcp.server.tools.impl.GetTasksTool;
import com.ditrix.edt.mcp.server.tools.impl.ListProjectsTool;
import com.ditrix.edt.mcp.server.tools.impl.RevalidateProjectTool;
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
        registry.register(new RevalidateProjectTool());
        registry.register(new GetProblemSummaryTool());
        registry.register(new GetProjectErrorsTool());
        registry.register(new GetBookmarksTool());
        registry.register(new GetTasksTool());
        registry.register(new GetCheckDescriptionTool());
        
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
     */
    private class McpHandler implements HttpHandler
    {
        @Override
        public void handle(HttpExchange exchange) throws IOException
        {
            String method = exchange.getRequestMethod();
            
            if ("POST".equals(method)) //$NON-NLS-1$
            {
                handleMcpRequest(exchange);
            }
            else if ("GET".equals(method)) //$NON-NLS-1$
            {
                handleMcpInfo(exchange);
            }
            else
            {
                sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}"); //$NON-NLS-1$
            }
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

            try
            {
                response = protocolHandler.processRequest(requestBody);
                Activator.logInfo("MCP response: " + response.substring(0, Math.min(200, response.length())) + "..."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (Exception e)
            {
                Activator.logError("MCP request processing error", e); //$NON-NLS-1$
                response = "{\"jsonrpc\": \"2.0\", \"error\": {\"code\": -32603, \"message\": \"" //$NON-NLS-1$
                    + e.getMessage() + "\"}, \"id\": null}"; //$NON-NLS-1$
            }

            exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
            sendResponse(exchange, 200, response);
        }

        private void handleMcpInfo(HttpExchange exchange) throws IOException
        {
            String response = String.format(
                "{\"name\": \"%s\", \"version\": \"%s\", \"edt_version\": \"%s\", \"status\": \"running\"}", //$NON-NLS-1$
                McpConstants.SERVER_NAME,
                McpConstants.PLUGIN_VERSION,
                GetEdtVersionTool.getEdtVersion());
            exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
            sendResponse(exchange, 200, response);
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
