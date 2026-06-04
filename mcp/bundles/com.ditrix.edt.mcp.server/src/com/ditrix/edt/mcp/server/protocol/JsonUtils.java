/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JSON utility methods for parameter extraction and JSON building.
 * Uses shared Gson instance from {@link GsonProvider}.
 */
public final class JsonUtils
{
    private JsonUtils()
    {
        // Utility class
    }
    
    /**
     * Builds a JSON-RPC 2.0 error response.
     * 
     * @param code the error code
     * @param message the error message
     * @param requestId the request ID (can be null)
     * @return JSON-RPC error response string
     */
    public static String buildJsonRpcError(int code, String message, Object requestId)
    {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        
        JsonObject error = new JsonObject();
        error.addProperty("code", code); //$NON-NLS-1$
        error.addProperty("message", message != null ? message : "Unknown error"); //$NON-NLS-1$ //$NON-NLS-2$
        response.add("error", error); //$NON-NLS-1$
        
        if (requestId == null)
        {
            response.add("id", null); //$NON-NLS-1$
        }
        else if (requestId instanceof String)
        {
            response.addProperty("id", (String) requestId); //$NON-NLS-1$
        }
        else if (requestId instanceof Number)
        {
            response.addProperty("id", (Number) requestId); //$NON-NLS-1$
        }
        
        return GsonProvider.toJson(response);
    }
    
    /**
     * Builds a simple JSON error response (non-JSON-RPC).
     * 
     * @param message the error message
     * @return JSON error response string
     */
    public static String buildSimpleError(String message)
    {
        JsonObject response = new JsonObject();
        response.addProperty("error", message != null ? message : "Unknown error"); //$NON-NLS-1$ //$NON-NLS-2$
        return GsonProvider.toJson(response);
    }
    
    /**
     * Builds a server info JSON response.
     * 
     * @param name server name
     * @param version plugin version
     * @param edtVersion EDT version
     * @param protocolVersion MCP protocol version
     * @return JSON response string
     */
    public static String buildServerInfo(String name, String version, String edtVersion, String protocolVersion)
    {
        JsonObject response = new JsonObject();
        response.addProperty("name", name); //$NON-NLS-1$
        response.addProperty("version", version); //$NON-NLS-1$
        response.addProperty("edt_version", edtVersion); //$NON-NLS-1$
        response.addProperty("protocol_version", protocolVersion); //$NON-NLS-1$
        response.addProperty("status", "running"); //$NON-NLS-1$ //$NON-NLS-2$
        return GsonProvider.toJson(response);
    }
    
    /** Readiness {@code status}: server running and all core services present. */
    public static final String HEALTH_STATUS_OK = "ok"; //$NON-NLS-1$

    /** Readiness {@code status}: MCP server not yet running (startup race). */
    public static final String HEALTH_STATUS_STARTING = "starting"; //$NON-NLS-1$

    /** Readiness {@code status}: server running but a core service is missing. */
    public static final String HEALTH_STATUS_DEGRADED = "degraded"; //$NON-NLS-1$

    /**
     * Builds a legacy health check JSON response (no readiness probing).
     * Retained for back-compatibility; prefer the readiness-aware overload
     * {@link #buildHealthResponse(String, boolean, Map)}.
     *
     * @param edtVersion EDT version
     * @return JSON response string
     */
    public static String buildHealthResponse(String edtVersion)
    {
        JsonObject response = new JsonObject();
        response.addProperty("status", HEALTH_STATUS_OK); //$NON-NLS-1$
        response.addProperty("live", true); //$NON-NLS-1$
        response.addProperty("edt_version", edtVersion); //$NON-NLS-1$
        return GsonProvider.toJson(response);
    }

    /**
     * Builds a readiness-aware health check JSON response.
     * <p>
     * The response is ADDITIVE and back-compatible: the top-level {@code status}
     * stays a string and {@code edt_version} is preserved, so monitors that check
     * for HTTP 200 + a {@code status} field keep working. New fields:
     * <ul>
     * <li>{@code live} — a cheap always-true liveness ping (the process answered),
     * present regardless of readiness so a simple liveness check still works;</li>
     * <li>{@code ready} — boolean readiness flag;</li>
     * <li>{@code missingServices} — the names of the unavailable core services
     * (empty when ready).</li>
     * </ul>
     * The {@code status} is {@link #HEALTH_STATUS_OK} only when {@code running}
     * is true AND every reference in {@code coreServices} is non-null; it is
     * {@link #HEALTH_STATUS_STARTING} when the server is not yet running, and
     * {@link #HEALTH_STATUS_DEGRADED} when the server runs but a core service
     * reference is null.
     * <p>
     * IMPORTANT: readiness is decided purely from the supplied {@code running}
     * flag and the non-null-ness of the {@code coreServices} references. This
     * method never reads/queries the EDT model and opens no transaction — a
     * health probe must stay cheap.
     *
     * @param edtVersion the EDT version string (may be null)
     * @param running whether the MCP server reports itself running
     * @param coreServices ordered map of core service name to its reference
     *            (a null value means the service is not yet available); may be null
     * @return JSON response string
     */
    public static String buildHealthResponse(String edtVersion, boolean running,
        Map<String, Object> coreServices)
    {
        List<String> missing = computeMissingServices(coreServices);
        boolean ready = running && missing.isEmpty();

        String status;
        if (ready)
        {
            status = HEALTH_STATUS_OK;
        }
        else if (!running)
        {
            status = HEALTH_STATUS_STARTING;
        }
        else
        {
            status = HEALTH_STATUS_DEGRADED;
        }

        JsonObject response = new JsonObject();
        response.addProperty("status", status); //$NON-NLS-1$
        // Always-true liveness ping: the process is up and answered this request.
        response.addProperty("live", true); //$NON-NLS-1$
        response.addProperty("ready", ready); //$NON-NLS-1$
        response.addProperty("running", running); //$NON-NLS-1$
        response.addProperty("edt_version", edtVersion); //$NON-NLS-1$

        JsonArray missingArray = new JsonArray();
        for (String name : missing)
        {
            missingArray.add(name);
        }
        response.add("missingServices", missingArray); //$NON-NLS-1$

        return GsonProvider.toJson(response);
    }

    /**
     * Pure, side-effect-free readiness helper: returns the names of the core
     * services whose reference is {@code null} (i.e. not yet available), in the
     * iteration order of {@code coreServices}. An empty result means every core
     * service reference is present.
     * <p>
     * This is the testable core of the readiness decision (see
     * {@link #buildHealthResponse(String, boolean, Map)}); it only inspects
     * non-null-ness of the supplied references and never touches the EDT model.
     *
     * @param coreServices map of core service name to its reference (null value
     *            means missing); may be null or empty
     * @return the ordered list of missing service names (never null; empty when
     *         all present)
     */
    public static List<String> computeMissingServices(Map<String, Object> coreServices)
    {
        List<String> missing = new ArrayList<>();
        if (coreServices == null)
        {
            return missing;
        }
        for (Map.Entry<String, Object> entry : coreServices.entrySet())
        {
            if (entry.getValue() == null)
            {
                missing.add(entry.getKey());
            }
        }
        return missing;
    }
    
    /**
     * Extracts a string argument from params map.
     * 
     * @param params the params map
     * @param argumentName the argument name to extract
     * @return value or null if not found
     */
    public static String extractStringArgument(Map<String, String> params, String argumentName)
    {
        if (params == null || argumentName == null)
        {
            return null;
        }
        return params.get(argumentName);
    }
    
    /**
     * Extracts an array argument from params map.
     * The value can be a JSON array string like ["a", "b"] or a comma-separated string.
     * 
     * @param params the params map
     * @param argumentName the argument name to extract
     * @return list of strings or null if not found
     */
    public static List<String> extractArrayArgument(Map<String, String> params, String argumentName)
    {
        if (params == null || argumentName == null)
        {
            return null;
        }
        
        String value = params.get(argumentName);
        if (value == null || value.isEmpty())
        {
            return null;
        }
        
        value = value.trim();
        
        // Check if it's a JSON array
        if (value.startsWith("[")) //$NON-NLS-1$
        {
            try
            {
                JsonElement element = JsonParser.parseString(value);
                if (element.isJsonArray())
                {
                    JsonArray array = element.getAsJsonArray();
                    List<String> result = new ArrayList<>(array.size());
                    for (JsonElement el : array)
                    {
                        if (el.isJsonPrimitive())
                        {
                            result.add(el.getAsString());
                        }
                    }
                    return result;
                }
            }
            catch (Exception e)
            {
                // Fall through to comma-separated parsing
            }
        }
        
        // Parse as comma-separated
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) //$NON-NLS-1$
        {
            String trimmed = part.trim();
            if (!trimmed.isEmpty())
            {
                result.add(trimmed);
            }
        }
        
        return result.isEmpty() ? null : result;
    }
    
    /**
     * Extracts a boolean argument from params map.
     * 
     * @param params the params map
     * @param argumentName the argument name to extract
     * @param defaultValue the default value if not found or invalid
     * @return boolean value or default
     */
    public static boolean extractBooleanArgument(Map<String, String> params, String argumentName, boolean defaultValue)
    {
        if (params == null || argumentName == null)
        {
            return defaultValue;
        }
        
        String value = params.get(argumentName);
        if (value == null || value.isEmpty())
        {
            return defaultValue;
        }
        
        value = value.trim().toLowerCase();
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return true;
        }
        else if ("false".equals(value) || "0".equals(value) || "no".equals(value)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return false;
        }
        
        return defaultValue;
    }
    
    /**
     * Extracts a long argument from params map.
     * Handles JSON number strings like "42.0" by parsing via double first.
     *
     * @param params the params map
     * @param argumentName the argument name to extract
     * @param defaultValue the default value if not found or invalid
     * @return long value or default
     */
    public static long extractLongArgument(Map<String, String> params, String argumentName, long defaultValue)
    {
        if (params == null || argumentName == null)
        {
            return defaultValue;
        }

        String value = params.get(argumentName);
        if (value == null || value.isEmpty())
        {
            return defaultValue;
        }

        try
        {
            double d = Double.parseDouble(value.trim());
            if (d != Math.floor(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE)
            {
                return defaultValue;
            }
            return (long) d;
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    /**
     * Extracts an integer argument from params map.
     * 
     * @param params the params map
     * @param argumentName the argument name to extract
     * @param defaultValue the default value if not found or invalid
     * @return integer value or default
     */
    public static int extractIntArgument(Map<String, String> params, String argumentName, int defaultValue)
    {
        if (params == null || argumentName == null)
        {
            return defaultValue;
        }
        
        String value = params.get(argumentName);
        if (value == null || value.isEmpty())
        {
            return defaultValue;
        }
        
        try
        {
            // Handle both "1" and "1.0" (Gson converts JSON numbers to Double.toString())
            // "1" and "1.0" → 1
            // "1.1" → defaultValue
            // "abc" → defaultValue
            double d = Double.parseDouble(value.trim());
            if (d != Math.floor(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE)
            {
                return defaultValue;
            }
            return (int) d;
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    /**
     * Shared required-argument guard. Returns a structured error payload
     * ({@code {"success":false,"error":"<name> is required"}}) when the named
     * argument is missing or blank, or {@code null} when it is present. Usage:
     * <pre>
     * String err = JsonUtils.requireArgument(params, "projectName");
     * if (err != null) return err;
     * </pre>
     * The error is recognised by the protocol's {@code isJsonErrorPayload}
     * diversion and delivered as a structured {@code isError} regardless of the
     * tool's normal response type, so this works for MARKDOWN/TEXT/JSON tools alike.
     *
     * @param params the params map
     * @param argumentName the required argument name
     * @return the error JSON to return, or {@code null} when the argument is present
     */
    public static String requireArgument(Map<String, String> params, String argumentName)
    {
        String value = extractStringArgument(params, argumentName);
        if (value == null || value.isEmpty())
        {
            return ToolResult.error(argumentName + " is required" + discoveryHint(argumentName)).toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Returns an actionable {@code ". Use <tool> to ..."} discovery suffix for a small set of
     * canonical, ENUMERABLE parameter names, so a bare {@code "<name> is required"} error points the
     * caller at the tool that lists valid values; returns an empty string for any other name. Only
     * parameters whose valid values are discoverable via one specific tool are mapped - free-form
     * values (a query, an expression, a path) get no hint, since a wrong hint is worse than none.
     * The variadic {@link #requireArguments} inherits this automatically (it delegates here), and the
     * two-arg {@link #requireArgument(Map, String, String)} keeps its caller-supplied detail instead.
     *
     * @param argumentName the required-argument name (may be {@code null})
     * @return the discovery suffix (with a leading {@code ". "}) or {@code ""} when there is no
     *     unambiguous discovery source
     */
    private static String discoveryHint(String argumentName)
    {
        if (argumentName == null)
        {
            return ""; //$NON-NLS-1$
        }
        switch (argumentName)
        {
            case "projectName": //$NON-NLS-1$
                return ". Use list_projects to see available project names."; //$NON-NLS-1$
            case "modulePath": //$NON-NLS-1$
                return ". Use list_modules to find a module path in the project."; //$NON-NLS-1$
            case "methodName": //$NON-NLS-1$
                return ". Use get_module_structure to list the module's procedures and functions."; //$NON-NLS-1$
            case "objectFqn": //$NON-NLS-1$
                return ". Use get_metadata_objects to find an object's FQN."; //$NON-NLS-1$
            case "applicationId": //$NON-NLS-1$
                return ". Use get_applications to list available application IDs."; //$NON-NLS-1$
            default:
                return ""; //$NON-NLS-1$
        }
    }

    /**
     * Variant of {@link #requireArgument(Map, String)} for tools that append a
     * usage hint after the canonical "<name> is required" text. The {@code detail}
     * is concatenated VERBATIM (it must include its own leading separator, e.g.
     * ". Example: ..." or " (e.g. ...)"), so the produced message is identical to
     * the previous inline guard.
     *
     * @param params the params map
     * @param argumentName the required argument name
     * @param detail the exact suffix to append after "<name> is required" (may be empty)
     * @return the error JSON to return, or {@code null} when the argument is present
     */
    public static String requireArgument(Map<String, String> params, String argumentName, String detail)
    {
        String value = extractStringArgument(params, argumentName);
        if (value == null || value.isEmpty())
        {
            return ToolResult.error(argumentName + " is required" + (detail == null ? "" : detail)).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Variadic form of {@link #requireArgument(Map, String)} for tools with
     * several required arguments. Checks the names in order and returns the
     * error for the FIRST missing/blank one (matching the behaviour of
     * sequential {@code requireArgument} guards), or {@code null} when all are
     * present. Usage:
     * <pre>
     * String err = JsonUtils.requireArguments(params, "projectName", "modulePath", "methodName");
     * if (err != null) return err;
     * </pre>
     *
     * @param params the params map
     * @param argumentNames the required argument names, in check order
     * @return the error JSON for the first missing argument, or {@code null} when all are present
     */
    public static String requireArguments(Map<String, String> params, String... argumentNames)
    {
        if (argumentNames == null)
        {
            return null;
        }
        for (String argumentName : argumentNames)
        {
            String err = requireArgument(params, argumentName);
            if (err != null)
            {
                return err;
            }
        }
        return null;
    }
}
