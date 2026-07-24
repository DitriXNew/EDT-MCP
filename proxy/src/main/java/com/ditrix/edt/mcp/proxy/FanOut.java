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

    /**
     * Budget (characters) for the REBUILT human content, mirroring the plugin's own content-text cap
     * ({@code OutputSizeGuard.MAX_CONTENT_CHARS}). A backend caps its content before sending it, but
     * the merged table is re-rendered here from the (uncapped) structured projects of EVERY backend,
     * so it must be capped again - otherwise a large fleet could return a human channel bigger than a
     * direct {@code list_projects} would ever produce.
     */
    static final int MAX_CONTENT_CHARS = 100_000;

    private static final String KEY_JSONRPC = "jsonrpc"; //$NON-NLS-1$
    private static final String KEY_ID = "id"; //$NON-NLS-1$
    private static final String KEY_ERROR = "error"; //$NON-NLS-1$
    private static final String KEY_RESULT = "result"; //$NON-NLS-1$
    private static final String KEY_IS_ERROR = "isError"; //$NON-NLS-1$
    private static final String KEY_STRUCTURED_CONTENT = "structuredContent"; //$NON-NLS-1$
    private static final String KEY_PROJECTS = "projects"; //$NON-NLS-1$
    private static final String KEY_SUCCESS = "success"; //$NON-NLS-1$
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
        return mergeListProjects(backendResponses, requestId, false);
    }

    /**
     * As {@link #mergeListProjects(List, Object)}, but shaping the merged response to the format the
     * CALLER asked for: {@code jsonFormat=true} keeps the machine payload
     * ({@code structuredContent.projects}), {@code false} (the {@code format=md} default) returns the
     * human table only, exactly like a direct {@code list_projects} call of that format. The fan-out
     * always QUERIES the backends with {@code format=json} (the merge needs the machine list); this
     * flag only decides what the client receives.
     *
     * @param backendResponses raw JSON-RPC response strings, one per backend (may be {@code null})
     * @param requestId the client's request id to echo (String, Number, or {@code null})
     * @param jsonFormat whether the caller asked for {@code format=json}
     * @return the merged JSON-RPC response, never {@code null}
     */
    public static String mergeListProjects(List<String> backendResponses, Object requestId,
        boolean jsonFormat)
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
                // Only the MACHINE contract is merged: the fan-out asks every backend for
                // format=json, so a response without structuredContent.projects comes from a plugin
                // too old to support it. Such a backend contributes nothing here and is reported as
                // an unsupported version by the registry - the proxy deliberately does NOT scrape the
                // human Markdown as a fallback.
                appendStructuredProjects(Json.obj(envelope, KEY_RESULT), mergedProjects);
            }
        }

        if (firstEnvelope == null)
        {
            return jsonRpcError(ERROR_NO_BACKENDS, MSG_NO_BACKENDS, requestId);
        }

        JsonObject result = Json.obj(firstEnvelope, KEY_RESULT);
        // The merged human table, rebuilt from the COMPLETE merged projects (the uncapped structured
        // source, so a large table is not truncated mid-row like a transport-capped content markdown
        // would be) and capped, so a client reading `content` sees ALL backends - not just the first
        // envelope's.
        // A backend queried with format=json answers with a JSON envelope, so its content SHAPE says
        // nothing about how a markdown call would look. Mirror a DIRECT call instead: markdown is an
        // embedded text/markdown resource, unless the backend runs in plain-text mode (Cursor), which
        // a direct call would also deliver as a text block.
        boolean plainText = isPlainTextEnvelope(result);
        String markdown = capMarkdown(renderProjectsTable(mergedProjects), jsonFormat);
        if (jsonFormat)
        {
            rebuildContent(result, markdown);
        }
        else if (plainText)
        {
            result.add(KEY_CONTENT, freshTextContent(markdown));
        }
        else
        {
            result.add(KEY_CONTENT, freshResourceContent(markdown));
        }
        if (jsonFormat)
        {
            // format=json: the caller wants the machine payload, exactly like a direct call.
            JsonObject structured = Json.obj(result, KEY_STRUCTURED_CONTENT);
            if (structured == null)
            {
                structured = new JsonObject();
                result.add(KEY_STRUCTURED_CONTENT, structured);
            }
            // Keep the success flag a direct format=json call carries, even when the donating
            // envelope came from a backend that answered without structuredContent.
            structured.addProperty(KEY_SUCCESS, true);
            structured.add(KEY_PROJECTS, mergedProjects);
        }
        else
        {
            // format=md (the default): the human table ONLY, mirroring a direct markdown call - a
            // markdown response carries no structuredContent.
            result.remove(KEY_STRUCTURED_CONTENT);
        }
        writeId(firstEnvelope, requestId);
        return Json.compact(firstEnvelope);
    }

    /**
     * Caps the rebuilt Markdown to {@link #MAX_CONTENT_CHARS}, mirroring the plugin's own content-text
     * guard so the proxy never returns a human channel bigger than a direct {@code list_projects}
     * would. The cut falls on a LINE boundary (so the table never ends mid-row) and a self-describing
     * truncation notice is appended; the returned string stays within the budget. Text that fits is
     * returned unchanged (the same instance).
     *
     * @param markdown the rendered table Markdown
     * @return the capped (or unchanged) Markdown
     */
    static String capMarkdown(String markdown, boolean jsonFormat)
    {
        if (markdown == null || markdown.length() <= MAX_CONTENT_CHARS)
        {
            return markdown;
        }
        // Point the caller at something it can actually use: the machine list is present in THIS
        // response only when format=json was requested; a markdown caller must re-ask for it.
        String where = jsonFormat
            ? "read the full list from structuredContent.projects" //$NON-NLS-1$
            : "call list_projects again with format='json' for the full list"; //$NON-NLS-1$
        String notice = "\n\n_[truncated: the merged table exceeded " + MAX_CONTENT_CHARS //$NON-NLS-1$
            + " characters; " + where + "]_\n"; //$NON-NLS-1$ //$NON-NLS-2$
        int keep = Math.max(0, MAX_CONTENT_CHARS - notice.length());
        // Cut back to the last complete line so the table never ends mid-row.
        int lastNewline = markdown.lastIndexOf('\n', keep);
        String kept = lastNewline > 0 ? markdown.substring(0, lastNewline) : markdown.substring(0, keep);
        return kept + notice;
    }

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

    /**
     * Whether this backend answered in PLAIN-TEXT mode: it carries no {@code structuredContent} yet a
     * machine project list was recoverable from its content text. A direct markdown call to such a
     * backend also returns a text block, so the merged markdown must keep that shape.
     *
     * @param result the donating backend's {@code result} object
     * @return {@code true} when the backend runs in plain-text mode
     */
    private static boolean isPlainTextEnvelope(JsonObject result)
    {
        return Json.obj(result, KEY_STRUCTURED_CONTENT) == null
            && BackendRegistry.machineProjects(result) != null;
    }

    /** A one-item {@code content} array carrying {@code markdown} as a plain {@code text} block. */
    private static JsonArray freshTextContent(String markdown)
    {
        JsonObject item = new JsonObject();
        item.addProperty(KEY_TYPE, TYPE_TEXT);
        item.addProperty(KEY_TEXT, markdown);
        JsonArray content = new JsonArray();
        content.add(item);
        return content;
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
     * Appends the entries of {@code result.structuredContent.projects} to the merged array. A
     * response WITHOUT that array comes from a backend whose plugin does not support
     * {@code format=json} (the registry reports it as an unsupported version) and contributes
     * nothing - the proxy never scrapes the human Markdown as a fallback.
     *
     * @param result the usable backend response's {@code result} object
     * @param mergedProjects the accumulator to append to
     */
    private static void appendStructuredProjects(JsonObject result, JsonArray mergedProjects)
    {
        // Shared extractor: accepts the machine list from structuredContent AND from a plain-text-mode
        // backend (the same JSON payload delivered as content text), but never scrapes the Markdown.
        JsonArray projects = BackendRegistry.machineProjects(result);
        if (projects == null)
        {
            return;
        }
        for (JsonElement project : projects)
        {
            mergedProjects.add(project);
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
