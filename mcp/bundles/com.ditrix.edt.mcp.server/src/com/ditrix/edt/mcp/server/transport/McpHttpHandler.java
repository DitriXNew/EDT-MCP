/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.impl.GetEdtVersionTool;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * MCP request handler. Implements the Streamable HTTP transport per the
 * MCP 2025-11-25 specification: admission control, CORS/Origin admission,
 * POST/OPTIONS/DELETE dispatch, SSE GET streams (offloaded to a dedicated pool),
 * and SSE-vs-plain-JSON response framing.
 *
 * <p>Extracted from {@code McpServer}: the socket lifecycle and execution state
 * stay on {@link McpServer}; this class is the per-request transport logic. It
 * reads the live executors and request/tool-call state through the {@code server}
 * reference, delegates {@code tools/call} to {@link InterruptibleToolExecutor},
 * and everything else to {@link McpProtocolHandler}.
 */
public class McpHttpHandler implements HttpHandler
{
    /** Event ID counter for SSE - AtomicLong for thread safety across concurrent SSE streams */
    private final AtomicLong eventIdCounter = new AtomicLong(0);

    private final McpServer server;
    private final McpProtocolHandler protocolHandler;
    private final InterruptibleToolExecutor interruptibleExecutor;

    public McpHttpHandler(McpServer server, McpProtocolHandler protocolHandler,
        InterruptibleToolExecutor interruptibleExecutor)
    {
        this.server = server;
        this.protocolHandler = protocolHandler;
        this.interruptibleExecutor = interruptibleExecutor;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException
    {
        // SSE GET streams are offloaded to a dedicated pool so they never
        // occupy threads in the main request pool or block the dispatcher.
        String method = exchange.getRequestMethod();

        // Optional shared-token auth — applies to every method, including SSE GET.
        // No-op when PREF_AUTH_TOKEN is empty (default), preserving prior behavior.
        if (!HttpTransport.isAuthorized(exchange))
        {
            try
            {
                HttpTransport.sendResponse(exchange, 401, JsonUtils.buildJsonRpcError(
                    McpConstants.ERROR_INVALID_REQUEST, "Unauthorized", null)); //$NON-NLS-1$
            }
            catch (IOException ignored)
            {
                // client already gone
            }
            finally
            {
                exchange.close();
            }
            return;
        }

        if ("GET".equals(method)) //$NON-NLS-1$
        {
            handleSseInDedicatedPool(exchange);
            return;
        }

        try
        {
            // Admission control: shed load before doing heavy work.
            // Unbounded queue prevents connection resets (no executor rejection),
            // and this check returns fast 503 to drain the queue under pressure.
            ThreadPoolExecutor mainExecutor = server.getMainExecutor();
            if (mainExecutor != null)
            {
                int queued = mainExecutor.getQueue().size();
                int active = mainExecutor.getActiveCount();
                if (queued + active > 50)
                {
                    Activator.logInfo("Main pool overloaded (active=" + active //$NON-NLS-1$
                        + ", queued=" + queued + "), returning 503"); //$NON-NLS-1$
                    exchange.getResponseHeaders().add("Retry-After", "2"); //$NON-NLS-1$ //$NON-NLS-2$
                    HttpTransport.sendResponse(exchange, 503,
                        JsonUtils.buildSimpleError("Server overloaded, retry later")); //$NON-NLS-1$
                    return;
                }
            }

            // Validate Origin and add CORS headers
            if (!HttpTransport.addCorsHeaders(exchange))
            {
                String origin = exchange.getRequestHeaders().getFirst("Origin"); //$NON-NLS-1$
                Activator.logInfo("Invalid Origin header rejected: " + origin); //$NON-NLS-1$
                HttpTransport.sendResponse(exchange, 403, JsonUtils.buildJsonRpcError(
                    McpConstants.ERROR_INVALID_REQUEST, "Invalid Origin", null)); //$NON-NLS-1$
                return;
            }

            // Handle CORS preflight request
            if ("OPTIONS".equals(method)) //$NON-NLS-1$
            {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("POST".equals(method)) //$NON-NLS-1$
            {
                handleMcpRequest(exchange);
            }
            else if ("DELETE".equals(method)) //$NON-NLS-1$
            {
                // Session termination - accept but we don't track sessions currently
                HttpTransport.sendResponse(exchange, 200, ""); //$NON-NLS-1$
            }
            else
            {
                HttpTransport.sendResponse(exchange, 405, JsonUtils.buildSimpleError("Method not allowed")); //$NON-NLS-1$
            }
        }
        catch (IOException e)
        {
            // Client disconnected unexpectedly - log and clean up
            Activator.logInfo("Client connection lost: " + e.getMessage()); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error handling MCP request", e); //$NON-NLS-1$
            try
            {
                HttpTransport.sendResponse(exchange, 500, JsonUtils.buildJsonRpcError(
                    McpConstants.ERROR_INTERNAL, "Internal server error", null)); //$NON-NLS-1$
            }
            catch (IOException ioe)
            {
                // Client already disconnected, nothing to do
                Activator.logInfo("Failed to send error response, client disconnected"); //$NON-NLS-1$
            }
        }
        finally
        {
            try
            {
                exchange.close();
            }
            catch (Exception ignored)
            {
                // Already closed
            }
        }
    }

    /**
     * Offloads SSE GET handling to the dedicated SSE thread pool.
     * The exchange lifecycle (including close) is managed entirely by the SSE thread,
     * so the main pool thread is released immediately.
     */
    private void handleSseInDedicatedPool(HttpExchange exchange)
    {
        ExecutorService sse = server.getSseExecutor();
        if (sse == null || sse.isShutdown())
        {
            try
            {
                HttpTransport.sendResponse(exchange, 503,
                    JsonUtils.buildSimpleError("Server is shutting down")); //$NON-NLS-1$
            }
            catch (IOException e)
            {
                // ignore
            }
            finally
            {
                exchange.close();
            }
            return;
        }

        try
        {
            sse.submit(() -> {
                try
                {
                    // Validate Origin and add CORS headers
                    if (!HttpTransport.addCorsHeaders(exchange))
                    {
                        HttpTransport.sendResponse(exchange, 403,
                            JsonUtils.buildJsonRpcError(
                                McpConstants.ERROR_INVALID_REQUEST, "Invalid Origin", null)); //$NON-NLS-1$
                        return;
                    }
                    handleSseStream(exchange);
                }
                catch (IOException e)
                {
                    Activator.logInfo("SSE client connection lost: " + e.getMessage()); //$NON-NLS-1$
                }
                catch (Exception e)
                {
                    Activator.logError("Unexpected error in SSE stream", e); //$NON-NLS-1$
                }
                finally
                {
                    try
                    {
                        exchange.close();
                    }
                    catch (Exception ignored)
                    {
                        // Already closed
                    }
                }
            });
        }
        catch (RejectedExecutionException e)
        {
            // SSE pool shutting down
            try
            {
                HttpTransport.sendResponse(exchange, 503,
                    JsonUtils.buildSimpleError("Server overloaded")); //$NON-NLS-1$
            }
            catch (IOException ioe)
            {
                // ignore
            }
            finally
            {
                exchange.close();
            }
        }
    }

    private void handleMcpRequest(HttpExchange exchange) throws IOException
    {
        // Increment request counter
        server.incrementRequestCount();

        Activator.logInfo("MCP request received from " + exchange.getRemoteAddress()); //$NON-NLS-1$

        // Read request body
        String requestBody;
        try
        {
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
            requestBody = body.toString();
        }
        catch (IOException e)
        {
            Activator.logInfo("Connection lost while reading request body: " + e.getMessage()); //$NON-NLS-1$
            return;
        }

        Activator.logDebug("MCP request body: " + requestBody); //$NON-NLS-1$

        String response;
        boolean isInitialize = requestBody.contains("\"" + McpConstants.METHOD_INITIALIZE + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        boolean isToolCall = requestBody.contains("\"" + McpConstants.METHOD_TOOLS_CALL + "\""); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            if (isToolCall)
            {
                // Handle tool calls with interruptible execution
                response = interruptibleExecutor.execute(exchange, requestBody);
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
                return;
            }

            Activator.logDebug("MCP response: " + response.substring(0, Math.min(200, response.length())) + "..."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logError("MCP request processing error", e); //$NON-NLS-1$
            response = JsonUtils.buildJsonRpcError(
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
            exchange.getResponseHeaders().add("Connection", "keep-alive"); //$NON-NLS-1$ //$NON-NLS-2$
            HttpTransport.sendResponse(exchange, 200, response);
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
        long eventId = eventIdCounter.incrementAndGet();
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
                        Thread.sleep(5000);
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
            String response = JsonUtils.buildServerInfo(
                McpConstants.SERVER_NAME,
                McpConstants.PLUGIN_VERSION,
                GetEdtVersionTool.getEdtVersion(),
                McpConstants.PROTOCOL_VERSION);
            exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
            HttpTransport.sendResponse(exchange, 200, response);
        }
    }
}
