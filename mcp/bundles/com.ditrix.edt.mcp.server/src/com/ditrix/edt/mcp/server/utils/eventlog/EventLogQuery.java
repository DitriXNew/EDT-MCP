/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.eventlog;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The filter + pagination spec consumed by {@link EventLogReader}. Fluent setters
 * return {@code this} so a tool can assemble it in one expression.
 *
 * <p>Text filters are case-insensitive. Severity input is bilingual and forgiving:
 * a one-letter code ({@code I}/{@code W}/{@code E}/{@code N}), an English word
 * ({@code Information}/{@code Warning}/{@code Error}/{@code Note}) or the Russian word
 * (the localized severities) all normalise to the canonical one-letter code the raw
 * records carry.</p>
 *
 * <p>Zero EDT/OSGi dependencies - headless-unit-testable.</p>
 */
public final class EventLogQuery
{
    // Localized (Russian) severity words, kept as unicode escapes so the source stays
    // ASCII and survives a non-UTF-8 build. Transliterations: informaciya /
    // preduprezhdenie / oshibka / primechanie.
    private static final String SEV_INFORMATION_RU =
        "\u0438\u043d\u0444\u043e\u0440\u043c\u0430\u0446\u0438\u044f";
    private static final String SEV_WARNING_RU =
        "\u043f\u0440\u0435\u0434\u0443\u043f\u0440\u0435\u0436\u0434\u0435\u043d\u0438\u0435";
    private static final String SEV_ERROR_RU = "\u043e\u0448\u0438\u0431\u043a\u0430";
    private static final String SEV_NOTE_RU =
        "\u043f\u0440\u0438\u043c\u0435\u0447\u0430\u043d\u0438\u0435";

    /** Inclusive lower bound, {@code YYYYMMDDhhmmss}; {@code 0} = unbounded. */
    public long dateFromRaw = 0L;

    /** Inclusive upper bound, {@code YYYYMMDDhhmmss}; the max is "unbounded". */
    public long dateToRaw = 99999999999999L;

    /** Canonical one-letter severity codes to keep; empty = any. */
    public Set<String> severities = Collections.emptySet();

    /** Exact user display names to keep; empty = any. */
    public Set<String> users = Collections.emptySet();

    /** Exact application names to keep; empty = any. */
    public Set<String> applications = Collections.emptySet();

    /** Exact event names to keep; empty = any. */
    public Set<String> events = Collections.emptySet();

    /** Case-insensitive substring the event name must contain; {@code null}/empty = any. */
    public String eventContains;

    /** Case-insensitive substring the comment must contain; {@code null}/empty = any. */
    public String commentContains;

    /** Case-insensitive substring the metadata full name must contain; {@code null}/empty = any. */
    public String metadataContains;

    /** Session numbers to keep; empty = any. */
    public Set<Long> sessions = Collections.emptySet();

    /** Page size (records to return). */
    public int limit = 100;

    /** Number of matching records to skip before the page. */
    public int offset = 0;

    /** {@code true} = newest first; {@code false} (default) = oldest first. */
    public boolean descending = false;

    /**
     * Sets {@link #dateFromRaw} (inclusive lower bound) from an ISO date/datetime. A
     * {@code null}/blank value leaves the bound unset (unbounded below); a bare date
     * ({@code YYYY-MM-DD}) is the start of that day (midnight).
     */
    public EventLogQuery from(String iso)
    {
        Long raw = parseBound(iso, false);
        if (raw != null)
        {
            this.dateFromRaw = raw.longValue();
        }
        return this;
    }

    /**
     * Sets {@link #dateToRaw} (inclusive upper bound) from an ISO date/datetime. A
     * {@code null}/blank value leaves the bound unset (unbounded above). A bare date
     * ({@code YYYY-MM-DD}) is the END of that day ({@code 23:59:59}), so a bare {@code to}
     * bound is inclusive of the whole day - "up to the 8th" keeps every event on the 8th,
     * not just those at midnight.
     */
    public EventLogQuery to(String iso)
    {
        Long raw = parseBound(iso, true);
        if (raw != null)
        {
            this.dateToRaw = raw.longValue();
        }
        return this;
    }

    /**
     * Parses an ISO date/datetime into a packed {@code YYYYMMDDhhmmss} bound, or
     * {@code null} for a {@code null}/blank input (the caller then leaves its default).
     * A bare date ({@code YYYY-MM-DD}, length 10) snaps to the start of the day for a lower
     * bound (midnight, via {@link LgpParser#fromIso(String)}) and to the end of the day for
     * an upper bound ({@code 23:59:59}), making a bare upper bound inclusive of that day.
     *
     * @param iso the ISO date/datetime (may be {@code null}/blank)
     * @param upper {@code true} to snap a bare date to end-of-day (inclusive upper bound)
     * @return the packed bound, or {@code null} when {@code iso} is null/blank
     */
    private static Long parseBound(String iso, boolean upper)
    {
        if (iso == null)
        {
            return null;
        }
        String s = iso.trim();
        if (s.isEmpty())
        {
            return null;
        }
        if (upper && s.length() == 10)
        {
            s = s + "T23:59:59";
        }
        return Long.valueOf(LgpParser.fromIso(s));
    }

    /** Sets the severity filter, canonicalising each value to a one-letter code. */
    public EventLogQuery severity(List<String> vals)
    {
        this.severities = normalise(vals, EventLogQuery::canonicalSeverity);
        return this;
    }

    /** Sets the exact-user filter. */
    public EventLogQuery user(List<String> vals)
    {
        this.users = normalise(vals, null);
        return this;
    }

    /** Sets the exact-application filter. */
    public EventLogQuery application(List<String> vals)
    {
        this.applications = normalise(vals, null);
        return this;
    }

    /** Sets the exact-event filter. */
    public EventLogQuery event(List<String> vals)
    {
        this.events = normalise(vals, null);
        return this;
    }

    /** Sets the session filter. */
    public EventLogQuery session(List<Long> vals)
    {
        if (vals == null || vals.isEmpty())
        {
            this.sessions = Collections.emptySet();
            return this;
        }
        Set<Long> s = new HashSet<>(vals);
        s.remove(null);
        this.sessions = s;
        return this;
    }

    /**
     * Normalises a user-facing severity ({@code "Error"}, {@code "warning"}, {@code "I"},
     * or the localized Russian word) to the one-letter code stored in the raw records.
     * An unrecognised value is passed through unchanged so the matcher simply fails to
     * match it (fail-closed).
     *
     * @param s the raw severity input
     * @return the one-letter code, or the trimmed input if unrecognised
     */
    public static String canonicalSeverity(String s)
    {
        if (s == null)
        {
            return null;
        }
        switch (s.trim().toLowerCase(Locale.ROOT))
        {
            case "i":
            case "info":
            case "information":
            case SEV_INFORMATION_RU:
                return "I";
            case "w":
            case "warn":
            case "warning":
            case SEV_WARNING_RU:
                return "W";
            case "e":
            case "error":
            case SEV_ERROR_RU:
                return "E";
            case "n":
            case "note":
            case SEV_NOTE_RU:
                return "N";
            default:
                return s.trim();
        }
    }

    private static Set<String> normalise(List<String> vals, UnaryOperator<String> tx)
    {
        if (vals == null || vals.isEmpty())
        {
            return Collections.emptySet();
        }
        Set<String> s = new HashSet<>();
        for (String v : vals)
        {
            if (v == null)
            {
                continue;
            }
            s.add(tx == null ? v : tx.apply(v));
        }
        return s;
    }

    /**
     * @return a predicate evaluating every configured filter against a decoded record.
     *         Empty/unset filters are pass-through.
     */
    public Predicate<EventRecord> asPredicate()
    {
        return ev ->
        {
            if (ev.dateRaw < this.dateFromRaw || ev.dateRaw > this.dateToRaw)
            {
                return false;
            }
            if (!this.severities.isEmpty() && !this.severities.contains(ev.severityCode))
            {
                return false;
            }
            if (!this.users.isEmpty() && (ev.user == null || !this.users.contains(ev.user)))
            {
                return false;
            }
            if (!this.applications.isEmpty()
                && (ev.application == null || !this.applications.contains(ev.application)))
            {
                return false;
            }
            if (!this.events.isEmpty() && (ev.event == null || !this.events.contains(ev.event)))
            {
                return false;
            }
            if (!containsIgnoreCase(ev.event, this.eventContains))
            {
                return false;
            }
            if (!containsIgnoreCase(ev.comment, this.commentContains))
            {
                return false;
            }
            if (!containsIgnoreCase(ev.metadata, this.metadataContains))
            {
                return false;
            }
            return this.sessions.isEmpty() || this.sessions.contains(Long.valueOf(ev.session));
        };
    }

    /**
     * @return {@code true} when {@code needle} is null/empty (no constraint) or
     *         {@code haystack} contains it case-insensitively; {@code false} when a
     *         non-empty needle is set but {@code haystack} is null or lacks it.
     */
    private static boolean containsIgnoreCase(String haystack, String needle)
    {
        if (needle == null || needle.isEmpty())
        {
            return true;
        }
        if (haystack == null)
        {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
