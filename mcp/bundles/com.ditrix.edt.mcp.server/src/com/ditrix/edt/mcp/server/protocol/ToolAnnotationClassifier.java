/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.util.Set;

import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;

/**
 * Central classifier that derives MCP behavioral hints
 * ({@code readOnlyHint}/{@code destructiveHint}/{@code idempotentHint}/
 * {@code openWorldHint}) for a tool from its name.
 *
 * <p>Classification is centralized here rather than per-tool: it keeps the
 * policy in one place and avoids touching every {@code tools/impl} class. A
 * tool may still override the verdict by implementing
 * {@code IMcpTool.getAnnotations()}.</p>
 *
 * <p>Policy:</p>
 * <ul>
 * <li><b>Destructive</b> (an explicit allow-list of irreversible / cascading
 * operations): {@code destructiveHint=true}, {@code readOnlyHint=false}.</li>
 * <li><b>Read-only</b> (names prefixed {@code get_}/{@code list_}/{@code read_}/
 * {@code search_}/{@code find_}/{@code validate_}): {@code readOnlyHint=true},
 * {@code idempotentHint=true} (re-reading the same state has no side effect).</li>
 * <li><b>Other writes</b> (everything else): {@code readOnlyHint=false},
 * {@code destructiveHint=false} (a non-destructive mutation).</li>
 * </ul>
 *
 * <p>{@code openWorldHint} is {@code false} for every tool: the server operates
 * entirely on the local EDT workspace and never reaches an external/open world.</p>
 */
public final class ToolAnnotationClassifier
{
    /**
     * Tools whose effect is destructive or irreversible (delete / overwrite /
     * cascade-rename / DB update / project removal). These get {@code destructiveHint=true}.
     *
     * <p>Deliberately NOT here, despite mutating state: {@code clean_project} (a rebuild /
     * revalidation - it discards only UNSAVED model changes, which is recoverable, and does not
     * delete persisted data) and {@code import_configuration_from_xml} (creates a NEW project and
     * REFUSES to overwrite an existing one, so it is recoverable by deleting the new project).
     * Marking those {@code destructiveHint=true} would mislead a client into treating safe,
     * recoverable operations as irreversible. They classify as ordinary non-destructive writes.</p>
     */
    private static final Set<String> DESTRUCTIVE_TOOLS = Set.of(
        "delete_metadata", //$NON-NLS-1$
        "update_database", //$NON-NLS-1$
        "rename_metadata_object", //$NON-NLS-1$
        "delete_project" //$NON-NLS-1$
    );

    private ToolAnnotationClassifier()
    {
        // utility class
    }

    /**
     * Classifies a tool by name into a set of MCP behavioral hints.
     *
     * @param toolName the tool name (e.g. {@code "get_edt_version"}); may be {@code null}
     * @return the derived annotations (never {@code null})
     */
    public static ToolAnnotations classify(String toolName)
    {
        // openWorldHint is always false: local workspace only, no external world.
        final Boolean openWorldHint = Boolean.FALSE;

        if (toolName == null)
        {
            // Unknown tool: be conservative — treat as a non-destructive write.
            return new ToolAnnotations(null, Boolean.FALSE, Boolean.FALSE, null, openWorldHint);
        }

        if (DESTRUCTIVE_TOOLS.contains(toolName))
        {
            return new ToolAnnotations(null, Boolean.FALSE, Boolean.TRUE, null, openWorldHint);
        }

        if (isReadOnly(toolName))
        {
            return new ToolAnnotations(null, Boolean.TRUE, null, Boolean.TRUE, openWorldHint);
        }

        // Other writes: a non-destructive mutation.
        return new ToolAnnotations(null, Boolean.FALSE, Boolean.FALSE, null, openWorldHint);
    }

    /**
     * A tool is read-only when its name uses one of the read-oriented prefixes.
     *
     * @param toolName the tool name (non-null)
     * @return {@code true} if the tool only reads state
     */
    private static boolean isReadOnly(String toolName)
    {
        return toolName.startsWith("get_") //$NON-NLS-1$
            || toolName.startsWith("list_") //$NON-NLS-1$
            || toolName.startsWith("read_") //$NON-NLS-1$
            || toolName.startsWith("search_") //$NON-NLS-1$
            || toolName.startsWith("find_") //$NON-NLS-1$
            || toolName.startsWith("validate_"); //$NON-NLS-1$
    }
}
