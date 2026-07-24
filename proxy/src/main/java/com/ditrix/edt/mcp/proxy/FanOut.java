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
    private static final String KEY_CONTENT = "content"; //$NON-NLS-1$
    private static final String KEY_RESOURCE = "resource"; //$NON-NLS-1$
    private static final String KEY_TEXT = "text"; //$NON-NLS-1$
    private static final String KEY_BLOB = "blob"; //$NON-NLS-1$
    private static final String KEY_TYPE = "type"; //$NON-NLS-1$
    /** The {@code type} value of a plain text content item (Cursor plain-text mode). */
    private static final String TYPE_TEXT = "text"; //$NON-NLS-1$
    private static final String KEY_URI = "uri"; //$NON-NLS-1$
    private static final String KEY_MIME_TYPE = "mimeType"; //$NON-NLS-1$
    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_STATE = "state"; //$NON-NLS-1$
    private static final String KEY_PATH = "path"; //$NON-NLS-1$
    private static final String KEY_OPEN = "open"; //$NON-NLS-1$
    private static final String KEY_EDT_PROJECT = "edtProject"; //$NON-NLS-1$
    private static final String KEY_NATURES = "natures"; //$NON-NLS-1$
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
                JsonObject result = Json.obj(envelope, KEY_RESULT);
                if (!appendStructuredProjects(result, mergedProjects))
                {
                    // A content-only backend (legacy build / failed structured generation): recover its
                    // projects with their FULL columns from the Markdown table so its live projects are
                    // not hidden from the merged structuredContent.projects AND the rebuilt human content
                    // keeps its state/path/open/EDT/natures (not just the name). Both channels then come
                    // from the same table parse, so they stay consistent.
                    for (JsonElement project : BackendRegistry.projectsFromMarkdownTable(result))
                    {
                        mergedProjects.add(project);
                    }
                }
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
        // Rebuild the human `content` from the COMPLETE merged projects (the uncapped structuredContent
        // source, so a large table is not truncated mid-row like the transport-capped content markdown
        // would be), always, so a client reading `content` sees ALL backends. A structured backend
        // renders rich rows from its full objects; a legacy backend's projects render NAME-only (their
        // only cross-fleet, cap-safe field) - the inherent limit of a content-only backend.
        rebuildContent(result, renderProjectsTable(mergedProjects));
        writeId(firstEnvelope, requestId);
        return Json.compact(firstEnvelope);
    }

    /**
     * Writes {@code markdown} into {@code result.content}, PRESERVING the first content item's SHAPE so
     * a plain-text-mode backend (Cursor compatibility) keeps its {@code type:"text"} block and a
     * {@code text/markdown} embedded resource stays a resource. Only a TEXT resource (has {@code text},
     * no {@code blob}) or a {@code text} block is rewritten in place; a blob/image resource, an image
     * item, or a missing/malformed item gets a fresh embedded {@code text/markdown} resource.
     *
     * @param result the merged JSON-RPC {@code result} object
     * @param markdown the rebuilt table Markdown
     */
    private static void rebuildContent(JsonObject result, String markdown)
    {
        JsonObject item = firstContentItem(result);
        if (item != null)
        {
            JsonObject resource = Json.obj(item, KEY_RESOURCE);
            if (resource != null && resource.has(KEY_TEXT) && !resource.has(KEY_BLOB))
            {
                resource.addProperty(KEY_TEXT, markdown); // a TEXT embedded resource
                return;
            }
            if (resource == null && TYPE_TEXT.equals(Json.str(item, KEY_TYPE)))
            {
                item.addProperty(KEY_TEXT, markdown); // a plain text block (Cursor plain-text mode)
                return;
            }
        }
        result.add(KEY_CONTENT, freshResourceContent(markdown));
    }

    /** The first {@code result.content} item when it is a JSON object, else {@code null}. */
    private static JsonObject firstContentItem(JsonObject result)
    {
        JsonElement content = result.get(KEY_CONTENT);
        if (content != null && content.isJsonArray() && content.getAsJsonArray().size() > 0
            && content.getAsJsonArray().get(0).isJsonObject())
        {
            return content.getAsJsonArray().get(0).getAsJsonObject();
        }
        return null;
    }

    /**
     * Renders the merged projects as the same Markdown table {@code list_projects} produces, so the
     * aggregated human view mirrors a single backend's columns. A missing structured field renders a
     * {@code "-"} cell; {@code open} and {@code edtProject} render Yes/No, and an ABSENT {@code
     * edtProject} renders {@code "-"} (a closed/uninspected, or legacy name-only, project).
     *
     * @param projects the merged projects array
     * @return the table Markdown (the {@code *No projects found.*} body when empty)
     */
    private static String renderProjectsTable(JsonArray projects)
    {
        StringBuilder md = new StringBuilder();
        md.append("## Workspace Projects\n\n"); //$NON-NLS-1$
        md.append("**Total:** ").append(projects.size()).append(" projects\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (projects.size() == 0)
        {
            md.append("*No projects found.*\n"); //$NON-NLS-1$
            return md.toString();
        }
        md.append("| Name | State | Path | Open | EDT Project | Natures |\n"); //$NON-NLS-1$
        md.append("|------|-------|------|------|-------------|--------|\n"); //$NON-NLS-1$
        for (JsonElement element : projects)
        {
            if (!element.isJsonObject())
            {
                continue;
            }
            JsonObject p = element.getAsJsonObject();
            md.append("| ").append(cell(Json.str(p, KEY_NAME))) //$NON-NLS-1$
                .append(" | ").append(cell(Json.str(p, KEY_STATE))) //$NON-NLS-1$
                .append(" | ").append(cell(Json.str(p, KEY_PATH))) //$NON-NLS-1$
                .append(" | ").append(boolCell(p, KEY_OPEN)) //$NON-NLS-1$
                .append(" | ").append(edtCell(p)) //$NON-NLS-1$
                .append(" | ").append(cell(Json.str(p, KEY_NATURES))) //$NON-NLS-1$
                .append(" |\n"); //$NON-NLS-1$
        }
        return md.toString();
    }

    /** A table cell: {@code "-"} for a missing value, with {@code |}/newlines escaped (mirrors escapeForTable). */
    private static String cell(String value)
    {
        if (value == null || value.isEmpty())
        {
            return "-"; //$NON-NLS-1$
        }
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    /** Renders a boolean field as Yes/No, or {@code "-"} when absent/not-a-boolean. */
    private static String boolCell(JsonObject p, String key)
    {
        JsonElement el = p.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean())
        {
            return "-"; //$NON-NLS-1$
        }
        return el.getAsBoolean() ? "Yes" : "No"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** edtProject: Yes/No when present, {@code "-"} when ABSENT (a closed/uninspected or legacy project). */
    private static String edtCell(JsonObject p)
    {
        return p.has(KEY_EDT_PROJECT) ? boolCell(p, KEY_EDT_PROJECT) : "-"; //$NON-NLS-1$
    }

    /** A one-item {@code content} array carrying {@code markdown} as an embedded {@code text/markdown} resource. */
    private static JsonArray freshResourceContent(String markdown)
    {
        JsonObject resource = new JsonObject();
        resource.addProperty(KEY_URI, "embedded://list-projects.md"); //$NON-NLS-1$
        resource.addProperty(KEY_MIME_TYPE, "text/markdown"); //$NON-NLS-1$
        resource.addProperty(KEY_TEXT, markdown);
        JsonObject item = new JsonObject();
        item.addProperty(KEY_TYPE, KEY_RESOURCE);
        item.add(KEY_RESOURCE, resource);
        JsonArray content = new JsonArray();
        content.add(item);
        return content;
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
     * Appends the entries of {@code result.structuredContent.projects} to the merged array.
     *
     * @param result the usable backend response's {@code result} object
     * @param mergedProjects the accumulator to append to
     * @return {@code true} when the backend exposed a {@code structuredContent.projects} ARRAY (even
     *         empty), {@code false} when it was content-only (legacy / failed structured generation) -
     *         the caller then recovers its names from the Markdown table and marks the merge as not
     *         all-structured (so the human content is not rebuilt from an incomplete field set)
     */
    private static boolean appendStructuredProjects(JsonObject result, JsonArray mergedProjects)
    {
        JsonObject structured = Json.obj(result, KEY_STRUCTURED_CONTENT);
        if (structured == null)
        {
            return false;
        }
        JsonElement projects = structured.get(KEY_PROJECTS);
        if (projects == null || !projects.isJsonArray())
        {
            return false;
        }
        for (JsonElement project : projects.getAsJsonArray())
        {
            mergedProjects.add(project);
        }
        return true;
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
