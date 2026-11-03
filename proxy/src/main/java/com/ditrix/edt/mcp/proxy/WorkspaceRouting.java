/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.proxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Proxy-only workspace selector shared by discovery, routing and schema decoration. */
final class WorkspaceRouting
{
    static final String WORKSPACE_PATH = "workspacePath"; //$NON-NLS-1$

    private WorkspaceRouting()
    {
    }

    static String workspacePath(JsonObject object)
    {
        JsonElement value = object == null ? null : object.get(WORKSPACE_PATH);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            && !value.getAsString().isBlank() ? value.getAsString() : null;
    }

    static JsonObject arguments(JsonObject request)
    {
        return Json.obj(Json.obj(request, "params"), "arguments"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static String argumentError(JsonObject request)
    {
        JsonObject arguments = arguments(request);
        return arguments != null && arguments.has(WORKSPACE_PATH) && workspacePath(arguments) == null
            ? "Invalid workspacePath " + arguments.get(WORKSPACE_PATH) //$NON-NLS-1$
                + ": pass a non-blank string copied exactly from list_projects or router_status." //$NON-NLS-1$
            : null;
    }

    static String backendRequest(String raw, JsonObject request)
    {
        JsonObject arguments = arguments(request);
        if (arguments == null || !arguments.has(WORKSPACE_PATH))
        {
            return raw;
        }
        JsonObject copy = request.deepCopy();
        arguments(copy).remove(WORKSPACE_PATH);
        return Json.compact(copy);
    }

    static void decorateTool(JsonObject tool)
    {
        JsonObject schema = Json.obj(tool, "inputSchema"); //$NON-NLS-1$
        if (schema == null)
        {
            return;
        }
        JsonObject properties = Json.obj(schema, "properties"); //$NON-NLS-1$
        if (properties == null)
        {
            properties = new JsonObject();
            schema.add("properties", properties); //$NON-NLS-1$
        }
        JsonObject selector = new JsonObject();
        selector.addProperty("type", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        selector.addProperty("description", //$NON-NLS-1$
            "Copy exactly from list_projects or router_status. Required to select a workspace when project names repeat."); //$NON-NLS-1$
        properties.add(WORKSPACE_PATH, selector);
    }
}
