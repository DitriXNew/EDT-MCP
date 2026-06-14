/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.InvocationTargetException;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Maps a failure from a reflection-wrapped EDT/LanguageTool CLI-API call to an
 * actionable {@code ToolResult} error JSON, shared by the tools that invoke a CLI
 * API by reflection ({@code export_configuration_to_xml},
 * {@code import_configuration_from_xml}, {@code translate_configuration},
 * {@code get_translation_project_info}, {@code generate_translation_strings}).
 *
 * <p>The three failure shapes those tools all distinguish are collapsed here so
 * each tool keeps a single {@code catch (Exception e)} around its reflective call.
 */
public final class CliReflectionErrors
{
    private CliReflectionErrors()
    {
    }

    /**
     * Logs the failure and returns the matching error JSON, routing exactly as the
     * tools used to do inline:
     * <ul>
     *   <li>{@link InvocationTargetException} — the API itself threw; unwrap the real
     *       cause and report {@code "<actionLabel> failed: <cause message>"}.</li>
     *   <li>{@link NoSuchMethodException} / {@link IllegalAccessException} — the CLI API
     *       shape differs from what the reflection expects; report
     *       {@code "<apiLabel> API mismatch: <message>"}.</li>
     *   <li>anything else — unexpected; report the raw {@code e.getMessage()}.</li>
     * </ul>
     *
     * @param e the exception caught around the reflective CLI-API call
     * @param actionLabel human action name used in the "failed" message, e.g.
     *        {@code "Export"} / {@code "Translate configuration"}
     * @param apiLabel the CLI API family used in the "API mismatch" message, e.g.
     *        {@code "CLI"} / {@code "LanguageTool"}
     * @return the error JSON the tool should return from {@code execute}
     */
    public static String toErrorJson(Exception e, String actionLabel, String apiLabel)
    {
        if (e instanceof InvocationTargetException)
        {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Activator.logError(actionLabel + " failed", cause); //$NON-NLS-1$
            return ToolResult.error(actionLabel + " failed: " + cause.getMessage()).toJson(); //$NON-NLS-1$
        }
        if (e instanceof NoSuchMethodException || e instanceof IllegalAccessException)
        {
            Activator.logError(apiLabel + " API mismatch", e); //$NON-NLS-1$
            return ToolResult.error(apiLabel + " API mismatch: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        Activator.logError("Unexpected error: " + actionLabel, e); //$NON-NLS-1$
        return ToolResult.error(e.getMessage()).toJson();
    }
}
