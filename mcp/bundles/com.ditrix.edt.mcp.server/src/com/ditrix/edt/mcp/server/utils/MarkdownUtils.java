/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

/**
 * Utility methods for Markdown formatting.
 */
public final class MarkdownUtils
{
    private MarkdownUtils()
    {
        // Utility class - no instantiation
    }
    
    /**
     * Escapes special Markdown characters in text for use in tables.
     * Handles pipe characters and line breaks that would break table formatting.
     * 
     * @param text the text to escape
     * @return escaped text safe for Markdown tables
     */
    public static String escapeForTable(String text)
    {
        if (text == null)
        {
            return ""; //$NON-NLS-1$
        }
        return text.replace("|", "\\|") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\n", " ") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\r", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Escapes special Markdown characters in text.
     * Useful for displaying text in Markdown without formatting issues.
     * 
     * @param text the text to escape
     * @return escaped text safe for Markdown
     */
    public static String escapeMarkdown(String text)
    {
        if (text == null)
        {
            return ""; //$NON-NLS-1$
        }
        // Escape common Markdown special characters
        return text.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("*", "\\*") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("_", "\\_") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("`", "\\`") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("[", "\\[") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("]", "\\]") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("<", "\\<") //$NON-NLS-1$ //$NON-NLS-2$
            .replace(">", "\\>"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
