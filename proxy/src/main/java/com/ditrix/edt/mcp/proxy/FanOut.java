/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Merges the {@code list_projects} results of every live backend into ONE JSON-RPC response.
 *
 * <p>Each backend answers {@code list_projects} with the plugin's JSON tool envelope
 * ({@code result.structuredContent.projects} = array of {@code {"name": ...}} objects). The
 * proxy fans the call out to all live backends and this class concatenates the
 * {@code projects} arrays in the order the responses are supplied (the handler passes them in
 * ascending backend port order), keeping the FIRST usable response's envelope shape so the
 * merged response is indistinguishable from a single backend's response to the client.</p>
 */
public final class FanOut
{
    /** JSON-RPC error code used when not a single backend produced a usable response. */
    static final int ERROR_NO_BACKENDS = -32000;

    /** Error message for the zero-usable-responses case. */
    static final String MSG_NO_BACKENDS = "No running EDT backends"; //$NON-NLS-1$

    private static final String KEY_JSONRPC = "jsonrpc"; //$NON-NLS-1$
    private static final String KEY_ID = "id"; //$NON-NLS-1$
    private static final String KEY_ERROR = "error"; //$NON-NLS-1$
    private static final String KEY_RESULT = "result"; //$NON-NLS-1$
    private static final String KEY_IS_ERROR = "isError"; //$NON-NLS-1$
    private static final String KEY_STRUCTURED_CONTENT = "structuredContent"; //$NON-NLS-1$
    private static final String KEY_PROJECTS = "projects"; //$NON-NLS-1$
    private static final String KEY_CODE = "code"; //$NON-NLS-1$
    private static final String KEY_MESSAGE = "message"; //$NON-NLS-1$
    private static final String JSONRPC_VERSION = "2.0"; //$NON-NLS-1$

    private FanOut()
    {
        // utility class
    }

    /**
     * Merges raw {@code list_projects} JSON-RPC responses from several backends into one.
     *
     * <p>The {@code result.structuredContent.projects} arrays are concatenated in the order
     * the responses are given. The first usable response donates the envelope (content
     * digest, structuredContent shape); its {@code id} is rewritten to {@code requestId}.
     * Unparseable responses, JSON-RPC error responses, and tool-level errors
     * ({@code result.isError == true}) are skipped. When not a single usable response
     * remains, a JSON-RPC error response (code {@link #ERROR_NO_BACKENDS}, message
     * {@value #MSG_NO_BACKENDS}) is returned.</p>
     *
     * @param backendResponses raw JSON-RPC response strings, one per backend (may be {@code null})
     * @param requestId the client's request id to echo (String, Number, or {@code null})
     * @return the merged JSON-RPC response, never {@code null}
     */
    public static String mergeListProjects(List<String> backendResponses, Object requestId)
    {
        JsonArray mergedProjects = new JsonArray();
        JsonObject firstEnvelope = null;

        if (backendResponses != null)
        {
            for (String raw : backendResponses)
            {
                JsonObject envelope = Json.parseObject(raw);
                if (!isUsable(envelope))
                {
                    continue;
                }
                if (firstEnvelope == null)
                {
                    firstEnvelope = envelope;
                }
                appendProjects(envelope, mergedProjects);
            }
        }

        if (firstEnvelope == null)
        {
            return jsonRpcError(ERROR_NO_BACKENDS, MSG_NO_BACKENDS, requestId);
        }

        JsonObject result = Json.obj(firstEnvelope, KEY_RESULT);
        JsonObject structured = Json.obj(result, KEY_STRUCTURED_CONTENT);
        if (structured == null)
        {
            structured = new JsonObject();
            result.add(KEY_STRUCTURED_CONTENT, structured);
        }
        structured.add(KEY_PROJECTS, mergedProjects);
        writeId(firstEnvelope, requestId);
        return Json.compact(firstEnvelope);
    }

    /**
     * A response is usable when it parses to a JSON object, is not a JSON-RPC error response,
     * carries a {@code result} object, and that result is not a tool-level error
     * ({@code isError == true}).
     */
    private static boolean isUsable(JsonObject envelope)
    {
        if (envelope == null || envelope.has(KEY_ERROR))
        {
            return false;
        }
        JsonObject result = Json.obj(envelope, KEY_RESULT);
        if (result == null)
        {
            return false;
        }
        JsonElement isError = result.get(KEY_IS_ERROR);
        return isError == null || !isError.isJsonPrimitive() || !isError.getAsJsonPrimitive().isBoolean()
            || !isError.getAsBoolean();
    }

    /**
     * Appends the entries of {@code result.structuredContent.projects} (when present) to the
     * merged array. A usable response without a projects array simply contributes nothing.
     */
    private static void appendProjects(JsonObject envelope, JsonArray mergedProjects)
    {
        JsonObject structured = Json.obj(Json.obj(envelope, KEY_RESULT), KEY_STRUCTURED_CONTENT);
        if (structured == null)
        {
            return;
        }
        JsonElement projects = structured.get(KEY_PROJECTS);
        if (projects != null && projects.isJsonArray())
        {
            for (JsonElement project : projects.getAsJsonArray())
            {
                mergedProjects.add(project);
            }
        }
    }

    /**
     * Writes a JSON-RPC {@code id} member into an envelope, mirroring the plugin's id
     * handling: a {@code null} id is serialized as an explicit {@code "id":null} (required by
     * JSON-RPC 2.0 for undeterminable ids), numbers stay numbers, everything else becomes a
     * string. Shared by the fan-out merge, {@code RouterTools}, and {@code McpProxyHandler}.
     *
     * @param envelope the response envelope to stamp
     * @param requestId the request id (String, Number, or {@code null})
     */
    static void writeId(JsonObject envelope, Object requestId)
    {
        if (requestId == null)
        {
            envelope.add(KEY_ID, JsonNull.INSTANCE);
        }
        else if (requestId instanceof Number)
        {
            envelope.addProperty(KEY_ID, (Number)requestId);
        }
        else
        {
            envelope.addProperty(KEY_ID, String.valueOf(requestId));
        }
    }

    /**
     * Builds a JSON-RPC error response, mirroring the plugin's
     * {@code JsonUtils.buildJsonRpcError} shape:
     * {@code {"jsonrpc":"2.0","error":{"code":N,"message":"..."},"id":...}}.
     *
     * @param code the JSON-RPC error code
     * @param message the error message ({@code null} becomes {@code "Unknown error"})
     * @param requestId the request id to echo (String, Number, or {@code null})
     * @return the serialized error response
     */
    static String jsonRpcError(int code, String message, Object requestId)
    {
        JsonObject envelope = new JsonObject();
        envelope.addProperty(KEY_JSONRPC, JSONRPC_VERSION);
        JsonObject error = new JsonObject();
        error.addProperty(KEY_CODE, code);
        error.addProperty(KEY_MESSAGE, message != null ? message : "Unknown error"); //$NON-NLS-1$
        envelope.add(KEY_ERROR, error);
        writeId(envelope, requestId);
        return Json.compact(envelope);
    }
}
