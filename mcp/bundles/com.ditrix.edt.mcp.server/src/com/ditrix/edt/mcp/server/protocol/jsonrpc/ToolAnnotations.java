/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol.jsonrpc;

/**
 * MCP tool annotations (behavioral hints) attached to a tool in the
 * {@code tools/list} response, as defined by the MCP specification.
 *
 * <p>All hint fields use boxed types so the shared Gson (which omits null
 * fields) leaves out any hint that was not set. Field names are serialized
 * verbatim and MUST match the MCP spec exactly: {@code title},
 * {@code readOnlyHint}, {@code destructiveHint}, {@code idempotentHint},
 * {@code openWorldHint}.</p>
 */
public class ToolAnnotations
{
    private String title;
    private Boolean readOnlyHint;
    private Boolean destructiveHint;
    private Boolean idempotentHint;
    private Boolean openWorldHint;

    /**
     * Creates a new set of tool annotations. Any argument may be {@code null}
     * to omit that hint from the serialized output.
     *
     * @param title an optional human-readable display title for the tool
     * @param readOnlyHint {@code true} if the tool does not modify its environment
     * @param destructiveHint {@code true} if the tool may perform destructive updates
     * @param idempotentHint {@code true} if repeated calls with the same arguments have no additional effect
     * @param openWorldHint {@code true} if the tool interacts with an external/open world
     */
    public ToolAnnotations(String title, Boolean readOnlyHint, Boolean destructiveHint,
        Boolean idempotentHint, Boolean openWorldHint)
    {
        this.title = title;
        this.readOnlyHint = readOnlyHint;
        this.destructiveHint = destructiveHint;
        this.idempotentHint = idempotentHint;
        this.openWorldHint = openWorldHint;
    }

    public String getTitle()
    {
        return title;
    }

    public Boolean getReadOnlyHint()
    {
        return readOnlyHint;
    }

    public Boolean getDestructiveHint()
    {
        return destructiveHint;
    }

    public Boolean getIdempotentHint()
    {
        return idempotentHint;
    }

    public Boolean getOpenWorldHint()
    {
        return openWorldHint;
    }
}
