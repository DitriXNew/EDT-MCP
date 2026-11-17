// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.Locale;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.ditrix.edt.mcp.server.utils.MarkerRows;

/** Marker check identity resolution and matching. */
public final class MarkerCheckIds
{
    private MarkerCheckIds() { }

    /**
     * Resolves the symbolic check id (e.g. "bsl-legacy-check-expression-type") for a marker's
     * short UID (e.g. "SU23") exactly once. Returns {@code null} when it cannot be resolved.
     */
    public static String resolveSymbolicCheckId(Marker marker, String shortUid, ICheckRepository checkRepository)
    {
        CheckUid uid = resolveCheckUid(marker, shortUid, checkRepository);
        return uid != null ? uid.getCheckId() : null;
    }

    /** @see MarkerRows#resolveCheckUid(Marker, String, ICheckRepository) */
    public static CheckUid resolveCheckUid(Marker marker, String shortUid, ICheckRepository checkRepository)
    {
        return MarkerRows.resolveCheckUid(marker, shortUid, checkRepository);
    }

    /** @see MarkerRows#checkIdMatches(String, String, String) */
    public static boolean checkIdMatches(String shortUid, String symbolicCheckId, String checkId)
    {
        return MarkerRows.checkIdMatches(shortUid, symbolicCheckId, checkId);
    }

    /**
     * Returns true when {@code checkId} is EXACTLY (case-insensitively) either the marker short
     * UID or its resolved symbolic check id. Unlike {@link #checkIdMatches}, this is NOT a
     * substring match: a read-only filter (get_project_errors) can afford to over-match and show
     * extra rows, but a mutation locator (apply_quick_fix) cannot - a loose needle like "doc"
     * would substring-match several unrelated checks, and if only one marker among them currently
     * exists the tool would silently auto-fix it without ever surfacing the ambiguity.
     */
    public static boolean checkIdMatchesExact(String shortUid, String symbolicCheckId, String checkId)
    {
        String needle = checkId.toLowerCase(Locale.ROOT);
        if (shortUid != null && shortUid.toLowerCase(Locale.ROOT).equals(needle))
        {
            return true;
        }
        return symbolicCheckId != null && symbolicCheckId.toLowerCase(Locale.ROOT).equals(needle);
    }
}
