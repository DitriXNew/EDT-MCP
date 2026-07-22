/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

/**
 * Thrown out of an XDTO member create/modify/delete write transaction (issue #183 stream 1) when the
 * operation must fail with a READY {@code ToolResult.error(...).toJson()} payload: the caller ({@code
 * CreateMetadataTool} / {@code ModifyMetadataTool} / {@code DeleteMetadataTool}) surfaces the actionable
 * JSON directly (via {@link #jsonOf}) instead of wrapping it in a generic failure message. Throwing
 * BEFORE any model mutation rolls the enclosing BM write transaction back with no partial mutation.
 * Mirrors {@link FormValidationException} (the same shape for a form read/write transaction) and
 * {@code ModifyMetadataTool.TemplateWriteException} (the same shape for the template/DCS content write) -
 * a small, per-feature exception rather than reusing one whose name would misleadingly suggest form
 * validation.
 */
public final class XdtoWriteException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final String json;

    /**
     * @param json the ready {@code ToolResult.error(...).toJson()} payload to surface verbatim
     */
    public XdtoWriteException(String json)
    {
        super("xdto write failed"); //$NON-NLS-1$
        this.json = json;
    }

    /** @return the ready JSON error payload carried by this exception */
    public String json()
    {
        return json;
    }

    /**
     * Finds an {@link XdtoWriteException} in the cause chain (the BM task runner may wrap the thrown
     * exception) and returns its JSON, or {@code null} when none is present.
     *
     * @param t the caught exception (may be {@code null})
     * @return the ready JSON error, or {@code null}
     */
    public static String jsonOf(Throwable t)
    {
        for (Throwable c = t; c != null; c = c.getCause())
        {
            if (c instanceof XdtoWriteException)
            {
                return ((XdtoWriteException)c).json;
            }
        }
        return null;
    }
}
