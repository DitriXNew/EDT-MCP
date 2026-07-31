/**
 * Copyright (C) 2024, DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import com.ditrix.edt.mcp.server.Activator;

import io.github.furstenheim.CopyDown;

/**
 * Converts an HTML fragment to Markdown (via the CopyDown converter), for surfacing platform hover
 * documentation, content-assist tooltips and authored object help as readable text. Shared so the
 * "clean HTML to Markdown" logic lives in one place (previously duplicated in the content-assist and
 * symbol-info paths).
 */
public final class HtmlToMarkdown
{
    private HtmlToMarkdown()
    {
    }

    /**
     * Converts {@code html} to trimmed Markdown: strips {@code <style>} blocks (CopyDown does not
     * handle CSS), runs the converter and collapses runs of blank lines. On any converter failure it
     * falls back to a plain tag-strip so the caller always gets readable text. {@code null} / empty in
     * yields an empty string.
     */
    public static String convert(String html)
    {
        if (html == null || html.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        try
        {
            // Remove <style> blocks before conversion (CopyDown does not handle CSS well).
            String cleaned = html.replaceAll("(?s)<style[^>]*>.*?</style>", ""); //$NON-NLS-1$ //$NON-NLS-2$
            String markdown = new CopyDown().convert(cleaned);
            // Normalize excessive blank lines.
            return markdown.replaceAll("\n{3,}", "\n\n").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logError("Error converting HTML to Markdown", e); //$NON-NLS-1$
            // Fallback: strip tags and collapse whitespace.
            return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
    }
}
