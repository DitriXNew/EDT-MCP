/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

/**
 * Canonical MCP key names shared across many tools.
 *
 * <p>Holds only the literals that recur as the SAME concept in three or more tool
 * classes — the canonical input-parameter names and the common output-result keys.
 * Keeping them here gives a single source of truth and prevents the per-tool drift
 * that {@code JsonUtils.discoveryHint(...)} already has to canonicalize after the fact.
 *
 * <p>File-specific keys and family-local vocabulary (e.g. the YAXUnit
 * {@code tests}/{@code modules}/{@code extensions} pair, or the step/wait
 * {@code timeout}/{@code reason} pair) stay as {@code private} constants in their own
 * tool — they are not promoted here until they prove genuinely cross-cutting.
 *
 * <p>The {@code error}/{@code success} result keys are owned by {@link ToolResult} and
 * must be written through it; {@link #ERROR} is only for the few sites that hand-build
 * an {@code "error"} field outside {@code ToolResult}.
 */
public final class McpKeys
{
    private McpKeys()
    {
        // Constants holder
    }

    // --- Canonical input-parameter names ---

    /** Input param: EDT workspace project name (the canonical project identifier). */
    public static final String PROJECT_NAME = "projectName"; //$NON-NLS-1$

    /** Input param: application (infobase) id, from {@code get_applications}. */
    public static final String APPLICATION_ID = "applicationId"; //$NON-NLS-1$

    /** Input param: BSL module path (the canonical module identifier). */
    public static final String MODULE_PATH = "modulePath"; //$NON-NLS-1$

    /** Input param: pagination page size shared by the paginated tools. */
    public static final String LIMIT = "limit"; //$NON-NLS-1$

    // --- Common output-result keys ---

    /** Output key: human-readable confirmation/result message. */
    public static final String MESSAGE = "message"; //$NON-NLS-1$

    /** Output key: the action a write tool performed (e.g. {@code created}, {@code deleted}). */
    public static final String ACTION = "action"; //$NON-NLS-1$

    /** Output key: the EDT project name a result pertains to (distinct from {@link #PROJECT_NAME}). */
    public static final String PROJECT = "project"; //$NON-NLS-1$

    /** Output key: an error field hand-built OUTSIDE {@link ToolResult} (see class javadoc). */
    public static final String ERROR = "error"; //$NON-NLS-1$
}
