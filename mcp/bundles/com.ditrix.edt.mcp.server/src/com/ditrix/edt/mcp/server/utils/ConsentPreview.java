/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, compact preview of a destructive operation shown by the
 * {@link DestructiveConsentGate} confirmation dialog.
 *
 * <p>Each gated tool builds a {@code ConsentPreview} from data it has ALREADY
 * computed (the reference list for a delete, the change points for a rename, the
 * update plan for a database update, the target name for a project/infobase
 * delete), so the preview adds no extra model work. It carries just enough to let
 * a human decide: a short {@link #getTitle() title}, a one-line
 * {@link #getSubtitle() subtitle}, a {@link #getTotalCount() total count} of
 * affected items, and a bounded list of {@link #getTopNames() top names} (the
 * dialog renders the remainder as "and M more").
 *
 * <p>Instances are effectively immutable: the {@code topNames} list is defensively
 * copied and wrapped unmodifiable in the constructor, and all fields are final.
 */
public final class ConsentPreview
{
    private final String title;
    private final String subtitle;
    private final int totalCount;
    private final List<String> topNames;
    private final boolean namesLoggable;

    /**
     * Creates a preview.
     *
     * @param title a short heading (e.g. {@code "Delete metadata node"}); may be {@code null}
     * @param subtitle a one-line description of the effect; may be {@code null}
     * @param totalCount the total number of affected items (never negative in
     *            practice; a negative value is clamped to {@code 0})
     * @param topNames a bounded list of the most relevant item names to show;
     *            {@code null} is treated as empty. Defensively copied.
     */
    public ConsentPreview(String title, String subtitle, int totalCount, List<String> topNames)
    {
        this(title, subtitle, totalCount, topNames, true);
    }

    /**
     * Creates a preview whose item names are the CALLER'S OWN TEXT rather than identifiers this
     * server chose - {@code evaluate_expression} is the case: its one "name" is the BSL the
     * caller sent.
     * <p>
     * A human approving the operation must still see it, so the dialog is unchanged. What
     * changes is the unattended-bypass AUDIT LINE, which goes to
     * {@code <workspace>/.metadata/.log} - a file that outlives the process, rotates into
     * {@code .bak_*.log} and is what people attach to bug reports. A short expression can carry
     * a password, a token or a connection string, and bounding and sanitising it (which the
     * audit already does) does not stop it from being written down. So for such a preview the
     * audit records the SHAPE of what was allowed - how many items, how long - and never the
     * text.
     * </p>
     *
     * @param title a short heading; may be {@code null}
     * @param subtitle a one-line description of the effect; may be {@code null}
     * @param totalCount the total number of affected items
     * @param topNames the caller-supplied names to show a human; {@code null} is treated as empty
     * @return a preview whose names must not reach the log
     */
    public static ConsentPreview withUnloggableNames(String title, String subtitle, int totalCount,
        List<String> topNames)
    {
        return new ConsentPreview(title, subtitle, totalCount, topNames, false);
    }

    private ConsentPreview(String title, String subtitle, int totalCount, List<String> topNames,
        boolean namesLoggable)
    {
        this.title = title;
        this.subtitle = subtitle;
        this.totalCount = Math.max(0, totalCount);
        this.topNames = topNames == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(topNames));
        this.namesLoggable = namesLoggable;
    }

    /**
     * Whether {@link #getTopNames()} may be written to a log. {@code true} for the ordinary
     * preview, whose names are metadata identifiers this server produced; {@code false} for one
     * built by {@link #withUnloggableNames} - see there.
     *
     * @return {@code true} when the names are safe to record
     */
    public boolean areNamesLoggable()
    {
        return namesLoggable;
    }

    /**
     * @return the short heading (may be {@code null})
     */
    public String getTitle()
    {
        return title;
    }

    /**
     * @return the one-line effect description (may be {@code null})
     */
    public String getSubtitle()
    {
        return subtitle;
    }

    /**
     * @return the total number of affected items (never negative)
     */
    public int getTotalCount()
    {
        return totalCount;
    }

    /**
     * @return the bounded list of top item names (never {@code null}, unmodifiable)
     */
    public List<String> getTopNames()
    {
        return topNames;
    }
}
