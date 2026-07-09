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
        this.title = title;
        this.subtitle = subtitle;
        this.totalCount = Math.max(0, totalCount);
        this.topNames = topNames == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(topNames));
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
