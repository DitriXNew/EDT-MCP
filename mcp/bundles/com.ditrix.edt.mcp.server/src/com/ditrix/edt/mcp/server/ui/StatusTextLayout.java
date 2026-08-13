/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.ui;

import java.util.function.ToIntFunction;

/**
 * Fits the status-bar texts into the width the trim actually gave the item.
 * <p>
 * The item asks for a fixed width, but the text it paints is not fixed: a long running tool
 * name, a larger UI font or display scaling all make it wider, and the counter is drawn AFTER
 * the status. Without a reservation the counter is the part that falls off the edge - the very
 * thing the caller wants to keep visible. So the counter is measured first, the status gets
 * what is left, and only the status is shortened.
 * <p>
 * Deliberately free of SWT types: the measuring is passed in, which lets the arithmetic be
 * tested without a display.
 */
final class StatusTextLayout
{
    /** Appended to a shortened status; plain ASCII, so no encoding can damage it. */
    static final String ELLIPSIS = "..."; //$NON-NLS-1$

    private StatusTextLayout()
    {
        // Utility.
    }

    /**
     * Shortens {@code text} until it fits, appending {@link #ELLIPSIS} when anything was cut.
     *
     * @param text text to fit, may be {@code null}
     * @param room pixels available for it
     * @param measure width of a given string in the target font
     * @return text that measures no wider than {@code room}, possibly empty
     */
    static String elide(String text, int room, ToIntFunction<String> measure)
    {
        if (text == null || text.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        if (room <= 0)
        {
            return ""; //$NON-NLS-1$
        }
        if (measure.applyAsInt(text) <= room)
        {
            return text;
        }
        // Longest prefix that still fits WITH the ellipsis. Cutting one character at a time is
        // fine here: the status is a couple of dozen characters and this runs on repaint only.
        for (int length = text.length() - 1; length > 0; length--)
        {
            String candidate = text.substring(0, length) + ELLIPSIS;
            if (measure.applyAsInt(candidate) <= room)
            {
                return candidate;
            }
        }
        // Not even one character plus the ellipsis fits - drop the status entirely rather than
        // paint a fragment over the counter.
        return ""; //$NON-NLS-1$
    }

    /**
     * Places the counter after the status without letting it run past the right edge.
     *
     * @param textX left edge of the text area
     * @param statusWidth width the status actually took
     * @param gap gap between status and counter
     * @param counterWidth width of the counter
     * @param canvasWidth full width of the canvas
     * @return x coordinate to draw the counter at
     */
    static int counterX(int textX, int statusWidth, int gap, int counterWidth, int canvasWidth)
    {
        int flow = textX + statusWidth + gap;
        // Pulling the counter left is the lesser evil: a counter drawn past the edge is simply
        // lost, while a slightly smaller gap is still readable.
        int rightAligned = canvasWidth - counterWidth;
        return Math.max(textX, Math.min(flow, rightAligned));
    }

    /**
     * Pixels the status may use once the counter has its share.
     *
     * @param canvasWidth full width of the canvas
     * @param textX left edge of the text area
     * @param gap gap between status and counter
     * @param counterWidth width of the counter, {@code 0} when there is none
     * @return pixels left for the status, never negative
     */
    static int statusRoom(int canvasWidth, int textX, int gap, int counterWidth)
    {
        int reserved = counterWidth <= 0 ? 0 : counterWidth + gap;
        return Math.max(0, canvasWidth - textX - reserved);
    }
}
