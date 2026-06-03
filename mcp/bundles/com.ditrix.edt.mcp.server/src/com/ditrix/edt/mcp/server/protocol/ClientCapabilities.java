/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Immutable holder for the capabilities a client declared during
 * {@code initialize}. It captures the raw {@code capabilities} object from the
 * initialize params and exposes a small, conservative API so future protocol
 * features (elicitation, tasks, resources, etc.) can gate on what the client
 * said it supports.
 * <p>
 * <b>Single-client assumption.</b> This EDT MCP server is effectively a
 * single-client server over localhost (one EDT workbench, one connected MCP
 * client at a time). The handler stores a single instance of this holder rather
 * than tracking capabilities per session; that is acceptable for this transport.
 * </p>
 * <p>
 * <b>No-regression default.</b> The capabilities object is OPTIONAL in the MCP
 * initialize request and most clients send an empty object (or nothing). The
 * gating predicates here therefore default to the permissive answer: when a
 * client sends no capabilities, or does not explicitly restrict a feature, the
 * feature is treated as ALLOWED. In particular {@link #allowsStructuredContent()}
 * returns {@code true} for the absent / empty / unrestricted case so the
 * long-standing observable behaviour (structuredContent emitted on every
 * JSON-responseType tool result) is unchanged.
 * </p>
 */
public final class ClientCapabilities
{
    /**
     * The raw {@code capabilities} object exactly as the client sent it, or
     * {@code null} when the client sent no capabilities object at all. Kept so
     * future features can read fields (roots, sampling, elicitation, ...) without
     * this class having to model every one of them up front.
     */
    private final JsonObject raw;

    private ClientCapabilities(JsonObject raw)
    {
        this.raw = raw;
    }

    /**
     * The capabilities used when a client sent no capabilities object (or before
     * any {@code initialize} has been processed). Behaves permissively: every
     * gating predicate returns its default ALLOW answer.
     */
    public static final ClientCapabilities ABSENT = new ClientCapabilities(null);

    /**
     * Builds a holder from the raw {@code capabilities} value of an initialize
     * request. Tolerant of {@code null} and of a non-object value (a malformed
     * client): both yield {@link #ABSENT} so a bad capabilities field never
     * changes the default behaviour and never throws.
     *
     * @param capabilities the raw {@code capabilities} JSON element from the
     *            initialize params (may be {@code null})
     * @return a holder; {@link #ABSENT} when the value is missing or not an object
     */
    public static ClientCapabilities from(JsonElement capabilities)
    {
        if (capabilities == null || !capabilities.isJsonObject())
        {
            return ABSENT;
        }
        return new ClientCapabilities(capabilities.getAsJsonObject());
    }

    /**
     * Whether the client actually declared a capabilities object. {@code false}
     * for {@link #ABSENT}. Most clients send an empty object, which is still
     * "present" (and still permissive).
     *
     * @return {@code true} when a capabilities object was supplied
     */
    public boolean isPresent()
    {
        return raw != null;
    }

    /**
     * The raw capabilities object the client supplied, or {@code null} when none
     * was supplied. Exposed so future protocol features can inspect specific
     * fields without growing this class for every capability.
     *
     * @return the raw capabilities object, or {@code null}
     */
    public JsonObject getRaw()
    {
        return raw;
    }

    /**
     * Whether the client declared a top-level capability with the given name
     * (e.g. {@code "elicitation"}, {@code "roots"}, {@code "sampling"}). The
     * value's shape is not inspected here; presence of the key is the signal MCP
     * uses for an object-valued capability.
     *
     * @param name the capability name (may be {@code null})
     * @return {@code true} when the capabilities object carries that key
     */
    public boolean has(String name)
    {
        return raw != null && name != null && raw.has(name);
    }

    /**
     * Whether structuredContent may be emitted for JSON-responseType tool
     * results. This is the gating substrate for the structuredContent contract.
     * <p>
     * It returns {@code true} by DEFAULT and suppresses structuredContent ONLY
     * when the client EXPLICITLY opts out via
     * {@code capabilities.experimental.structuredContent == false}. The MCP spec
     * defines no standard "cannot accept structuredContent" flag, so an explicit
     * experimental boolean is the single, narrow opt-out; everything else
     * (absent capabilities, empty capabilities, no experimental block, a missing
     * or non-boolean flag, or the flag set to {@code true}) keeps the
     * long-standing default of emitting structuredContent.
     * </p>
     *
     * @return {@code true} unless the client explicitly opted out
     */
    public boolean allowsStructuredContent()
    {
        if (raw == null)
        {
            return true;
        }
        JsonElement experimental = raw.get("experimental"); //$NON-NLS-1$
        if (experimental == null || !experimental.isJsonObject())
        {
            return true;
        }
        JsonElement flag = experimental.getAsJsonObject().get("structuredContent"); //$NON-NLS-1$
        if (flag == null || !flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean())
        {
            return true;
        }
        // Only an explicit false suppresses structuredContent.
        return flag.getAsBoolean();
    }
}
