/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.bridge;

import java.util.Comparator;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Public OSGi implementation of {@link IEdtMcpBridge}.
 * <p>
 * Tool calls are deliberately sent through
 * {@link McpProtocolHandler#processRequest(String)} rather than invoking
 * {@link IMcpTool#execute(java.util.Map)} directly. This keeps the in-process
 * bridge on the same dispatch, enablement, result-shaping, redaction and logging
 * path as HTTP {@code tools/call}, without copying transport, SSE or interruption
 * responsibilities into this service.
 * <p>
 * The class also implements {@link BiFunction} and {@link Supplier} so the same
 * instance can be published under those JDK types, giving callers that cannot
 * see {@link IEdtMcpBridge} a typed handle instead of a reflective one.
 */
public class EdtMcpBridge implements IEdtMcpBridge, BiFunction<String, String, String>, Supplier<String>
{
    private static final long BRIDGE_REQUEST_ID = 1L;

    private final McpToolRegistry registry;
    private final McpProtocolHandler protocolHandler;

    /** Creates a bridge backed by the live singleton tool registry. */
    public EdtMcpBridge()
    {
        this(McpToolRegistry.getInstance(), new McpProtocolHandler());
    }

    /** Package-private seam for focused headless tests. */
    EdtMcpBridge(McpToolRegistry registry, McpProtocolHandler protocolHandler)
    {
        this.registry = registry;
        this.protocolHandler = protocolHandler;
    }

    @Override
    public String listTools()
    {
        BridgeActivity.callStarted();
        try
        {
            return renderToolList();
        }
        finally
        {
            BridgeActivity.callFinished();
        }
    }

    private String renderToolList()
    {
        JsonArray result = new JsonArray();
        registry.getAllTools().stream()
            .sorted(Comparator.comparing(IMcpTool::getName))
            .forEach(tool -> {
                JsonObject item = new JsonObject();
                item.addProperty("name", tool.getName()); //$NON-NLS-1$
                item.addProperty("description", tool.getDescription()); //$NON-NLS-1$
                result.add(item);
            });
        return GsonProvider.toJson(result);
    }

    @Override
    public String callTool(String toolName, String argsJson)
    {
        // Bracketed, not just counted: a caller waiting on an assistant turn needs to see that
        // work is under way, and one tool that runs for minutes ticks the counter only once.
        BridgeActivity.callStarted();
        try
        {
            return dispatchToolCall(toolName, argsJson);
        }
        finally
        {
            BridgeActivity.callFinished();
        }
    }

    private String dispatchToolCall(String toolName, String argsJson)
    {
        JsonElement arguments;
        try
        {
            arguments = argsJson == null || argsJson.trim().isEmpty()
                ? new JsonObject() : JsonParser.parseString(argsJson);
        }
        catch (JsonSyntaxException e)
        {
            return JsonUtils.buildJsonRpcError(McpConstants.ERROR_INVALID_PARAMS,
                "Invalid argsJson: expected a JSON object with tool arguments. " //$NON-NLS-1$
                    + "Fix the JSON syntax and retry callTool.", BRIDGE_REQUEST_ID); //$NON-NLS-1$
        }

        if (!arguments.isJsonObject())
        {
            return JsonUtils.buildJsonRpcError(McpConstants.ERROR_INVALID_PARAMS,
                "Invalid argsJson: expected a JSON object with tool arguments, but got " //$NON-NLS-1$
                    + jsonKind(arguments) + ". Pass an object such as {} and retry callTool.", //$NON-NLS-1$ //$NON-NLS-2$
                BRIDGE_REQUEST_ID);
        }

        JsonObject params = new JsonObject();
        params.addProperty("name", toolName); //$NON-NLS-1$
        params.add("arguments", arguments); //$NON-NLS-1$

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", McpConstants.JSONRPC_VERSION); //$NON-NLS-1$
        request.addProperty("id", BRIDGE_REQUEST_ID); //$NON-NLS-1$
        request.addProperty("method", McpConstants.METHOD_TOOLS_CALL); //$NON-NLS-1$
        request.add("params", params); //$NON-NLS-1$

        return protocolHandler.processRequest(GsonProvider.toJson(request));
    }

    /**
     * {@link Supplier} face of {@link #listTools()} for callers that hold this
     * service under its JDK-type alias.
     */
    @Override
    public String get()
    {
        return listTools();
    }

    /**
     * {@link BiFunction} face of {@link #callTool(String, String)} for callers
     * that hold this service under its JDK-type alias.
     */
    @Override
    public String apply(String toolName, String argsJson)
    {
        return callTool(toolName, argsJson);
    }

    private static String jsonKind(JsonElement element)
    {
        if (element == null || element.isJsonNull())
        {
            return "null"; //$NON-NLS-1$
        }
        if (element.isJsonArray())
        {
            return "an array"; //$NON-NLS-1$
        }
        return "a primitive value"; //$NON-NLS-1$
    }
}
