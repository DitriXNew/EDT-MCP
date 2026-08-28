/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Builder for MCP tool results.
 * Uses GsonProvider for JSON serialization to avoid manual string building.
 */
public class ToolResult
{
    private final Map<String, Object> data = new HashMap<>();
    
    private ToolResult()
    {
    }
    
    /**
     * Creates a new success result.
     */
    public static ToolResult success()
    {
        ToolResult result = new ToolResult();
        result.data.put("success", true);
        return result;
    }
    
    /**
     * Creates a new error result.
     * 
     * @param message error message
     */
    public static ToolResult error(String message)
    {
        ToolResult result = new ToolResult();
        result.data.put("success", false);
        // Always carry a non-null error message: the default Gson omits null fields,
        // so error(null) would otherwise drop the "error" key entirely (e.g. an
        // exception whose getMessage() is null, like a raw NPE). Tools pass
        // e.getMessage() directly, so coalesce here once for the whole contract.
        result.data.put("error", message != null ? message : "Unknown error"); //$NON-NLS-1$
        return result;
    }

    /**
     * Creates an error returned after the tool has already committed a model mutation.
     *
     * <p>The boolean is a machine contract, independent of the human error wording. Test harnesses
     * and clients can therefore require a reset after any such result without maintaining a list of
     * phrases used by individual tools.</p>
     *
     * @param message error message
     * @return a failed result carrying {@code mutationCommitted:true}
     */
    public static ToolResult errorAfterMutation(String message)
    {
        return error(message).put("mutationCommitted", true); //$NON-NLS-1$
    }

    /**
     * Creates an error from an opaque mutating API whose failure does not report whether it rolled
     * back. This is intentionally distinct from {@code mutationCommitted}: callers must reset, but
     * the tool must not claim a commit it could not observe.
     *
     * @param message error message
     * @return a failed result carrying {@code mutationOutcomeUnknown:true}
     */
    public static ToolResult errorWithUnknownMutationOutcome(String message)
    {
        return error(message).put("mutationOutcomeUnknown", true); //$NON-NLS-1$
    }

    /**
     * Adds the post-mutation marker to an already-built error result.
     *
     * <p>This is the return-path counterpart of {@link #errorAfterMutation(String)}. A writer may
     * build or catch an ordinary error deep below the line that knows whether its transaction has
     * committed. Rebuilding the message there is both lossy and easy to forget; the request's write
     * scope instead calls this once, at the result choke point. Only an explicit
     * {@code success:false} JSON object is changed. Successes, Markdown payloads and malformed
     * strings pass through byte-for-byte.</p>
     *
     * @param result the tool result about to be returned
     * @return {@code result}, or the same error object carrying {@code mutationCommitted:true}
     */
    public static String markErrorAfterMutation(String result)
    {
        return markError(result, "mutationCommitted"); //$NON-NLS-1$
    }

    /** Adds {@code mutationOutcomeUnknown:true} to an already-built explicit error result. */
    public static String markErrorWithUnknownMutationOutcome(String result)
    {
        return markError(result, "mutationOutcomeUnknown"); //$NON-NLS-1$
    }

    private static String markError(String result, String marker)
    {
        if (result == null)
        {
            return null;
        }
        try
        {
            JsonElement parsed = JsonParser.parseString(result);
            if (!parsed.isJsonObject())
            {
                return result;
            }
            JsonObject object = parsed.getAsJsonObject();
            JsonElement success = object.get("success"); //$NON-NLS-1$
            if (success == null || !success.isJsonPrimitive()
                || !success.getAsJsonPrimitive().isBoolean() || success.getAsBoolean())
            {
                return result;
            }
            // The markers are a strongest-known-state classification, not independent flags.
            // A known commit outranks uncertainty (for example: project 1 cleaned, project 2 then
            // failed opaquely), and a later uncertainty stamp must never weaken an existing fact.
            if ("mutationCommitted".equals(marker)) //$NON-NLS-1$
            {
                object.remove("mutationOutcomeUnknown"); //$NON-NLS-1$
            }
            else
            {
                JsonElement committed = object.get("mutationCommitted"); //$NON-NLS-1$
                if (committed != null && committed.isJsonPrimitive()
                    && committed.getAsJsonPrimitive().isBoolean() && committed.getAsBoolean())
                {
                    return GsonProvider.toJson(object);
                }
            }
            object.addProperty(marker, true);
            return GsonProvider.toJson(object);
        }
        catch (RuntimeException e)
        {
            return result;
        }
    }
    
    /**
     * Adds a string field.
     */
    public ToolResult put(String key, String value)
    {
        data.put(key, value);
        return this;
    }
    
    /**
     * Adds an integer field.
     */
    public ToolResult put(String key, int value)
    {
        data.put(key, value);
        return this;
    }
    
    /**
     * Adds a long field.
     */
    public ToolResult put(String key, long value)
    {
        data.put(key, value);
        return this;
    }
    
    /**
     * Adds a boolean field.
     */
    public ToolResult put(String key, boolean value)
    {
        data.put(key, value);
        return this;
    }
    
    /**
     * Adds a list field.
     */
    public ToolResult put(String key, List<?> value)
    {
        data.put(key, value);
        return this;
    }
    
    /**
     * Adds any object field (will be serialized by Gson).
     */
    public ToolResult put(String key, Object value)
    {
        data.put(key, value);
        return this;
    }
    
    /**
     * Converts to JSON string.
     */
    public String toJson()
    {
        return GsonProvider.toJson(data);
    }
    
    /**
     * Static helper to serialize any object to JSON.
     */
    public static String toJsonStatic(Object obj)
    {
        return GsonProvider.toJson(obj);
    }
}
