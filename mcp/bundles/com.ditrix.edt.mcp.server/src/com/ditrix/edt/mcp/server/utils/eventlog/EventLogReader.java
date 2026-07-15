/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.eventlog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The single entry point of the event-log parser: loads {@code 1Cv8.lgf}, walks the
 * {@code *.lgp} partitions that overlap the query window in chronological order, and
 * returns the matching {@link EventRecord}s honouring {@code offset}/{@code limit}/
 * {@code order}.
 *
 * <p>Memory is bounded two ways. A partition whose date range is clearly outside the
 * window is skipped without opening it. Only the requested page is buffered (never the
 * whole log): an ascending page walks partitions oldest-first and keeps the first
 * {@code [offset, offset+limit)} matches; a descending page walks partitions
 * newest-first and folds each partition's newest {@code offset+limit} matches into the
 * buffer, keeping the first {@code offset+limit} newest matches. CPU is bounded by
 * {@link #DEFAULT_TOTAL_SCAN_CAP}: once the scanned-record count exceeds the cap
 * iteration stops and {@link Page#truncated} is set so the caller can warn that the
 * result may be incomplete. Because a descending read visits the newest partitions
 * first, a cap-stop still yields the true-newest page rather than the oldest events
 * scanned.</p>
 *
 * <p>Only the legacy text format (ver 2.0, {@code 1Cv8.lgf} + {@code *.lgp}) is
 * supported. A directory holding a modern SQLite {@code .lgd} but no {@code .lgf} is
 * reported with an actionable {@link EventLogException} rather than a silent empty
 * result.</p>
 *
 * <p>Zero EDT/OSGi dependencies - headless-unit-testable.</p>
 */
public final class EventLogReader
{
    /** Default ceiling on the number of records scanned before the read is truncated. */
    public static final int DEFAULT_TOTAL_SCAN_CAP = 1_000_000;

    /** The reference dictionary file name every legacy text-format log carries. */
    public static final String LGF_NAME = "1Cv8.lgf";

    /**
     * A checked, actionable failure of the read (no log directory, missing/unsupported
     * references file, or an I/O error). The tool layer turns the message into a
     * {@code ToolResult.error(...)}.
     */
    public static final class EventLogException extends Exception
    {
        private static final long serialVersionUID = 1L;

        public EventLogException(String message)
        {
            super(message);
        }

        public EventLogException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    /** The result of a read: the page plus the totals needed for a paginated response. */
    public static final class Page
    {
        /** The records on the requested page (already ordered per the query). */
        public final List<EventRecord> records;

        /** The total number of records that matched the filter (across the scan). */
        public final long matchedTotal;

        /** The total number of records tokenised (including filtered-out/skipped ones). */
        public final long scanned;

        /** {@code true} when the scan cap was hit and the result may be incomplete. */
        public final boolean truncated;

        public Page(List<EventRecord> records, long matchedTotal, long scanned, boolean truncated)
        {
            this.records = records;
            this.matchedTotal = matchedTotal;
            this.scanned = scanned;
            this.truncated = truncated;
        }
    }

    /** The page window derived from the query: skip {@code offset} matches, keep {@code limit}. */
    private static final class Window
    {
        /** Number of matches to skip before the page (clamped to {@code >= 0}). */
        final long offset;

        /** Exclusive end ordinal of the page: {@code offset + limit}. */
        final long end;

        /** Window buffer cap: {@code end} clamped to {@code [1, Integer.MAX_VALUE]}. */
        final int cap;

        Window(EventLogQuery q)
        {
            this.offset = Math.max(0L, q.offset);
            long limit = Math.max(0L, q.limit);
            this.end = this.offset + limit;
            this.cap = (int)Math.min(Integer.MAX_VALUE, Math.max(1L, this.end));
        }
    }

    /** The mutable scan accumulator: the window buffer plus the matched/scanned/truncated totals. */
    private static final class ScanState
    {
        /** The window buffer (ascending: the page itself; descending: the newest window matches). */
        final List<EventRecord> buffer = new ArrayList<>();

        /** The number of records that matched the filter so far. */
        long matched;

        /** The number of records tokenised so far. */
        long scanned;

        /** Whether the scan cap stopped the walk early. */
        boolean truncated;
    }

    private final LgfParser lgfParser;
    private final int totalScanCap;

    public EventLogReader()
    {
        this(new LgfParser(), DEFAULT_TOTAL_SCAN_CAP);
    }

    public EventLogReader(LgfParser lgfParser, int totalScanCap)
    {
        this.lgfParser = lgfParser;
        this.totalScanCap = totalScanCap;
    }

    /**
     * Reads the event log rooted at {@code logDir}.
     *
     * @param logDir the {@code 1Cv8Log} directory
     * @param q the filter + pagination spec
     * @return the matching page
     * @throws EventLogException on a missing/unsupported/unreadable log
     */
    public Page read(Path logDir, EventLogQuery q) throws EventLogException
    {
        Path lgf = requireLegacyLog(logDir);
        EventLogReferences refs = parseReferences(lgf);

        // A descending ("newest first") read must walk partitions newest-first so a
        // scan-cap stop keeps the TRUE newest events; an ascending read walks oldest-first.
        List<Path> partitions =
            listPartitionsInRange(logDir, q.dateFromRaw, q.dateToRaw, q.descending);
        LgpParser lgp = new LgpParser(refs);
        Predicate<EventRecord> filter = q.asPredicate();
        Window window = new Window(q);

        ScanState state = scanPartitions(partitions, lgp, filter, q.descending, window);
        List<EventRecord> pageRecords = slicePage(state.buffer, q.descending, window);
        return new Page(pageRecords, state.matched, state.scanned, state.truncated);
    }

    /**
     * Validates the log directory and locates the legacy references file.
     *
     * @param logDir the {@code 1Cv8Log} directory
     * @return the {@code 1Cv8.lgf} path
     * @throws EventLogException when the directory or the references file is missing/unsupported
     */
    private static Path requireLegacyLog(Path logDir) throws EventLogException
    {
        if (logDir == null || !Files.isDirectory(logDir))
        {
            throw new EventLogException("Event log directory not found: " + logDir
                + " (expected an infobase 1Cv8Log directory)");
        }
        Path lgf = logDir.resolve(LGF_NAME);
        if (!Files.isRegularFile(lgf))
        {
            if (hasLgd(logDir))
            {
                throw new EventLogException("Event log is in the modern SQLite format (.lgd),"
                    + " which is not supported yet. Only the legacy text format (ver 2.0,"
                    + " 1Cv8.lgf + *.lgp) can be read. Directory: " + logDir);
            }
            throw new EventLogException("Event log references file not found: " + lgf
                + " (expected a legacy text-format 1Cv8.lgf in the log directory)");
        }
        return lgf;
    }

    /**
     * Parses the {@code 1Cv8.lgf} references dictionary.
     *
     * @param lgf the references file path
     * @return the parsed reference tables
     * @throws EventLogException when the file cannot be parsed
     */
    private EventLogReferences parseReferences(Path lgf) throws EventLogException
    {
        try
        {
            return this.lgfParser.parse(lgf);
        }
        catch (IOException e)
        {
            throw new EventLogException("Failed to parse " + lgf + ": " + e.getMessage(), e);
        }
    }

    /**
     * Walks the partitions, streaming every record through the filter and accumulating the
     * page window plus the matched/scanned/truncated totals.
     *
     * @param partitions the partitions to scan, already ordered per the walk direction
     * @param lgp the partition parser
     * @param filter the record filter
     * @param descending {@code true} when the newest events are wanted first
     * @param window the page window
     * @return the accumulated scan state
     * @throws EventLogException when a partition cannot be read
     */
    private ScanState scanPartitions(List<Path> partitions, LgpParser lgp, Predicate<EventRecord> filter,
        boolean descending, Window window) throws EventLogException
    {
        // Window buffering, both bounded to O(offset+limit) (never the whole log):
        //  - ascending: partitions oldest-first; keep the matches at ordinals
        //    [offset, offset+limit) directly;
        //  - descending: partitions newest-first, and within each partition keep only its
        //    newest (offset+limit) matches (a ring over the chronological stream); after
        //    each partition those are folded newest-first into the buffer until it holds
        //    the first (offset+limit) newest matches. A cap-stop after the newest
        //    partitions therefore still yields the true-newest page.
        ScanState state = new ScanState();
        for (Path part : partitions)
        {
            final Deque<EventRecord> partRing = descending ? new ArrayDeque<>() : null;
            try
            {
                long here = lgp.stream(part, filter, ev ->
                {
                    long ord = state.matched++;
                    bufferMatch(ev, ord, descending, partRing, state.buffer, window);
                    return true;
                });
                state.scanned += here;
            }
            catch (IOException e)
            {
                throw new EventLogException("Failed to read " + part + ": " + e.getMessage(), e);
            }
            if (descending && state.buffer.size() < window.cap)
            {
                // partRing holds this partition's newest <=windowCap matches in chronological
                // order; drain it newest-first into the buffer until the page window is full.
                drainRingNewestFirst(partRing, state.buffer, window.cap);
            }
            if (state.scanned > this.totalScanCap)
            {
                state.truncated = true;
                break;
            }
        }
        return state;
    }

    /**
     * Buffers one matched record. A descending walk keeps the partition's newest
     * {@code window.cap} matches in the ring; an ascending walk keeps the match directly
     * when its ordinal falls inside the page window.
     *
     * @param ev the matched record
     * @param ord the match ordinal (0-based, across all partitions)
     * @param descending the walk direction
     * @param partRing the per-partition ring ({@code null} on an ascending walk)
     * @param buffer the page buffer (the ascending target)
     * @param window the page window
     */
    private static void bufferMatch(EventRecord ev, long ord, boolean descending, Deque<EventRecord> partRing,
        List<EventRecord> buffer, Window window)
    {
        if (descending)
        {
            partRing.addLast(ev);
            if (partRing.size() > window.cap)
            {
                partRing.removeFirst();
            }
        }
        else if (ord >= window.offset && ord < window.end)
        {
            buffer.add(ev);
        }
    }

    /**
     * Drains a partition's ring (its newest matches, in chronological order) newest-first
     * into the buffer until the page window is full.
     *
     * @param partRing the per-partition ring
     * @param buffer the window buffer
     * @param windowCap the buffer cap
     */
    private static void drainRingNewestFirst(Deque<EventRecord> partRing, List<EventRecord> buffer, int windowCap)
    {
        Iterator<EventRecord> it = partRing.descendingIterator();
        while (it.hasNext() && buffer.size() < windowCap)
        {
            buffer.add(it.next());
        }
    }

    /**
     * Cuts the requested page out of the window buffer. A descending buffer holds the newest
     * {@code offset+limit} matches newest-first, so the page is its {@code [offset, end)}
     * slice; an ascending buffer already holds exactly the page.
     *
     * @param buffer the window buffer
     * @param descending the walk direction
     * @param window the page window
     * @return the page records
     */
    private static List<EventRecord> slicePage(List<EventRecord> buffer, boolean descending, Window window)
    {
        if (descending)
        {
            int from = (int)Math.min(window.offset, buffer.size());
            int to = (int)Math.min(window.end, buffer.size());
            return new ArrayList<>(buffer.subList(from, to));
        }
        return buffer;
    }

    private static boolean hasLgd(Path logDir)
    {
        try (Stream<Path> stream = Files.list(logDir))
        {
            return stream.anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lgd"));
        }
        catch (IOException e)
        {
            return false;
        }
    }

    /**
     * Selects the {@code *.lgp} partitions that may hold events in the window
     * {@code [fromRaw, toRaw]}. A partition file is named by the timestamp of its first
     * record, so a partition overlaps the window when its start day falls inside it, and
     * one earlier partition (the last starting on/before the window's first day) is kept
     * as a carry-over because it can hold later events.
     *
     * @param logDir the log directory
     * @param fromRaw inclusive window start ({@code YYYYMMDDhhmmss})
     * @param toRaw inclusive window end ({@code YYYYMMDDhhmmss})
     * @param descending {@code true} to return the list newest-first
     * @return the selected partition paths
     * @throws EventLogException if the directory cannot be listed
     */
    public static List<Path> listPartitionsInRange(Path logDir, long fromRaw, long toRaw, boolean descending)
        throws EventLogException
    {
        List<Path> all = new ArrayList<>();
        try (Stream<Path> stream = Files.list(logDir))
        {
            stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lgp"))
                .forEach(all::add);
        }
        catch (IOException e)
        {
            throw new EventLogException("Failed to list log directory " + logDir + ": " + e.getMessage(), e);
        }
        all.sort(Comparator.comparing(p -> p.getFileName().toString()));

        long fromDay = (fromRaw / 1000000L) * 1000000L;
        long toDay = (toRaw / 1000000L + 1) * 1000000L;
        List<Path> filtered = new ArrayList<>();
        int carryIdx = selectWindow(all, fromDay, toDay, filtered);
        if (carryIdx >= 0)
        {
            Path carry = all.get(carryIdx);
            if (filtered.isEmpty() || !filtered.get(0).equals(carry))
            {
                filtered.add(0, carry);
            }
        }
        if (descending)
        {
            Collections.reverse(filtered);
        }
        return filtered;
    }

    /**
     * Walks the name-sorted partitions, adding to {@code filtered} those whose start day
     * falls inside {@code [fromDay, toDay)} and tracking the last partition starting
     * on/before the window's first day (the carry-over candidate). Stops at the first
     * partition starting on/after {@code toDay}; a file without a leading 14-digit
     * timestamp is ignored.
     *
     * @param all every {@code *.lgp} path, sorted by file name
     * @param fromDay the window's first day, packed ({@code YYYYMMDD000000})
     * @param toDay the first day after the window, packed
     * @param filtered the output collector for the in-window partitions
     * @return the carry-over candidate index into {@code all}, or {@code -1} when there is none
     */
    private static int selectWindow(List<Path> all, long fromDay, long toDay, List<Path> filtered)
    {
        int carryIdx = -1;
        for (int i = 0; i < all.size(); i++)
        {
            Long startRaw = parsePartitionStart(all.get(i).getFileName().toString());
            if (startRaw != null)
            {
                if (startRaw.longValue() <= fromDay)
                {
                    carryIdx = i;
                }
                if (startRaw.longValue() >= toDay)
                {
                    break;
                }
                if (startRaw.longValue() >= fromDay)
                {
                    filtered.add(all.get(i));
                }
            }
        }
        return carryIdx;
    }

    /**
     * Parses the leading 14-digit {@code YYYYMMDDhhmmss} timestamp of a partition file name.
     *
     * @param name the {@code *.lgp} file name
     * @return the packed start timestamp, or {@code null} when the name does not carry one
     */
    private static Long parsePartitionStart(String name)
    {
        try
        {
            return Long.valueOf(name.substring(0, 14));
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }
}
