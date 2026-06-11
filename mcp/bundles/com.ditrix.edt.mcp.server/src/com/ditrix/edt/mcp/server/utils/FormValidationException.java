/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

/**
 * Thrown out of a form read/write transaction (or its pre-resolution scaffold, see
 * {@link FormElementWriter#resolveForEdit}) when the operation must fail with a READY
 * {@code ToolResult.error(...).toJson()} payload: the caller surfaces the actionable JSON directly
 * (via {@link #jsonOf}) instead of wrapping it in a generic failure message. Throwing BEFORE any
 * {@code eSet} rolls the enclosing BM transaction back with no partial mutation.
 */
public final class FormValidationException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final String json;

    /**
     * @param json the ready {@code ToolResult.error(...).toJson()} payload to surface verbatim
     */
    public FormValidationException(String json)
    {
        super("form validation failed"); //$NON-NLS-1$
        this.json = json;
    }

    /** @return the ready JSON error payload carried by this exception */
    public String json()
    {
        return json;
    }

    /**
     * Finds a {@link FormValidationException} in the cause chain (the BM task runner may wrap the
     * thrown exception) and returns its JSON, or {@code null} when none is present.
     *
     * @param t the caught exception (may be {@code null})
     * @return the ready JSON error, or {@code null}
     */
    public static String jsonOf(Throwable t)
    {
        for (Throwable c = t; c != null; c = c.getCause())
        {
            if (c instanceof FormValidationException)
            {
                return ((FormValidationException)c).json;
            }
        }
        return null;
    }
}
