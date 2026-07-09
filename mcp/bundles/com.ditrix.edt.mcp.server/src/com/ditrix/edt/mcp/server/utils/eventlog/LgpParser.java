/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.eventlog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Predicate;

/**
 * Streams events from one {@code *.lgp} partition file, resolving reference indices
 * against a pre-loaded {@link EventLogReferences}.
 *
 * <p>Each record is a {@code {...}} list of positional fields (0-based here):</p>
 * <pre>
 *   0  dateRaw          YYYYMMDDhhmmss
 *   1  txStatus         N|C|U|R
 *   2  txId             {hexTimestamp, hexId}
 *   3  user             seq -&gt; users
 *   4  computer         seq -&gt; computers
 *   5  application      seq -&gt; applications
 *   6  connectionId     int
 *   7  event            seq -&gt; events
 *   8  severity         I|W|E|N
 *   9  comment          string
 *  10  metadata         seq -&gt; metadata
 *  11  data             {"type", value} or a nested tree
 *  12  dataPresentation string
 *  13  server           seq -&gt; servers
 *  14  mainPort         seq -&gt; mainPorts
 *  15  secondaryPort    seq -&gt; secondaryPorts
 *  16  session          int
 * </pre>
 *
 * <p>Real logs append trailing fields after index 16 (a 19-field record is common);
 * they are tolerated and ignored. A record with fewer than 17 fields - e.g. a truncated
 * trailing record from a live-write cluster - decodes to {@code null} and is skipped.
 * The data field may be a nested tree; only its leading scalar type is captured, and a
 * {@code null} data field never fails decoding.</p>
 *
 * <p>Zero EDT/OSGi dependencies - headless-unit-testable.</p>
 */
public final class LgpParser
{
    /** The minimum positional field count of a decodable record. */
    private static final int MIN_FIELDS = 17;

    /** Receives decoded events; return {@code false} to stop iteration early. */
    @FunctionalInterface
    public interface Sink
    {
        boolean accept(EventRecord rec);
    }

    private final EventLogReferences refs;

    public LgpParser(EventLogReferences refs)
    {
        this.refs = refs;
    }

    /**
     * Streams every record from {@code lgp}, applying an optional filter and feeding the
     * survivors to {@code sink}.
     *
     * @param lgp the partition file
     * @param filter an optional predicate (may be {@code null} = accept all)
     * @param sink the consumer of matching records
     * @return the number of records tokenised (including those skipped/filtered out)
     * @throws IOException on a stream failure
     */
    public long stream(Path lgp, Predicate<EventRecord> filter, Sink sink) throws IOException
    {
        try (InputStream in = Files.newInputStream(lgp, StandardOpenOption.READ);
            BufferedReader br =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 64 * 1024))
        {
            return stream(br, filter, sink);
        }
    }

    /**
     * Streams records from an already-open reader (used by tests).
     *
     * @param br a reader over the {@code .lgp} content
     * @param filter an optional predicate (may be {@code null} = accept all)
     * @param sink the consumer of matching records
     * @return the number of records tokenised
     * @throws IOException on a stream failure
     */
    public long stream(BufferedReader br, Predicate<EventRecord> filter, Sink sink) throws IOException
    {
        LgTokenizer.skipBomAndHeader(br);
        LgTokenizer tk = new LgTokenizer(br);
        long total = 0;
        LgToken rec;
        while ((rec = tk.nextRecord()) != null)
        {
            total++;
            EventRecord ev = decode(rec);
            if (ev == null)
            {
                continue;
            }
            if (filter != null && !filter.test(ev))
            {
                continue;
            }
            if (!sink.accept(ev))
            {
                break;
            }
        }
        return total;
    }

    /**
     * Decodes one tokenised record into an {@link EventRecord}.
     *
     * @param rec a tokenised {@code {...}} record
     * @return the decoded event, or {@code null} when the record is not a decodable event
     *         (a non-list, a short/truncated record, or one whose date does not parse)
     */
    public EventRecord decode(LgToken rec)
    {
        if (!rec.isList() || rec.items.size() < MIN_FIELDS)
        {
            return null;
        }
        List<LgToken> a = rec.items;
        Long date = a.get(0).asLong();
        if (date == null)
        {
            return null;
        }
        EventRecord ev = new EventRecord();
        ev.dateRaw = date.longValue();
        ev.dateIso = toIso(ev.dateRaw);

        ev.txStatus = a.get(1).isAtom() ? a.get(1).text : null;
        ev.txId = decodeTxId(a.get(2));

        ev.userSeq = intOf(a.get(3));
        ev.user = this.refs.userName(ev.userSeq);
        ev.userUuid = this.refs.userUuid(ev.userSeq);

        ev.computerSeq = intOf(a.get(4));
        ev.computer = this.refs.computers.get(Integer.valueOf(ev.computerSeq));

        ev.applicationSeq = intOf(a.get(5));
        ev.application = this.refs.applications.get(Integer.valueOf(ev.applicationSeq));

        Long conn = a.get(6).asLong();
        ev.connectionId = conn == null ? 0L : conn.longValue();

        ev.eventSeq = intOf(a.get(7));
        ev.event = this.refs.events.get(Integer.valueOf(ev.eventSeq));

        ev.severityCode = a.get(8).isAtom() ? a.get(8).text : null;
        ev.severity = decodeSeverity(ev.severityCode);

        ev.comment = a.get(9).asString();

        ev.metadataSeq = intOf(a.get(10));
        ev.metadata = this.refs.metadataName(ev.metadataSeq);
        ev.metadataUuid = this.refs.metadataUuid(ev.metadataSeq);

        decodeData(a.get(11), ev);
        ev.dataPresentation = a.get(12).asString();

        ev.serverSeq = intOf(a.get(13));
        ev.server = this.refs.servers.get(Integer.valueOf(ev.serverSeq));

        ev.mainPortSeq = intOf(a.get(14));
        ev.mainPort = this.refs.mainPorts.get(Integer.valueOf(ev.mainPortSeq));

        ev.secondaryPortSeq = intOf(a.get(15));
        ev.secondaryPort = this.refs.secondaryPorts.get(Integer.valueOf(ev.secondaryPortSeq));

        Long session = a.get(16).asLong();
        ev.session = session == null ? 0L : session.longValue();

        return ev;
    }

    private static String decodeTxId(LgToken t)
    {
        if (t.isList() && t.items.size() == 2)
        {
            String h0 = t.items.get(0).asString();
            String h1 = t.items.get(1).asString();
            if (h0 != null && h1 != null && !("0".equals(h0) && "0".equals(h1)))
            {
                return h0 + "/" + h1;
            }
        }
        return null;
    }

    /**
     * Captures the data field. Only the leading scalar type code (a quoted string such
     * as {@code "S"}/{@code "R"}/{@code "B"}) and, when present, the following scalar
     * value are kept. A nested tree yields a {@code null} value; the {@code {0}} "no
     * data" sentinel yields neither type nor value.
     */
    private static void decodeData(LgToken data, EventRecord ev)
    {
        if (!data.isList() || data.items.isEmpty())
        {
            return;
        }
        LgToken head = data.items.get(0);
        if (head.isString())
        {
            ev.dataType = head.asString();
            if (data.items.size() > 1)
            {
                ev.dataValue = data.items.get(1).asString();
            }
        }
    }

    private static int intOf(LgToken t)
    {
        Long v = t.asLong();
        return v == null ? 0 : v.intValue();
    }

    /** Maps a raw one-letter severity code to its canonical English name. */
    public static String decodeSeverity(String code)
    {
        if (code == null)
        {
            return null;
        }
        switch (code)
        {
            case "I":
                return "Information";
            case "W":
                return "Warning";
            case "E":
                return "Error";
            case "N":
                return "Note";
            default:
                return code;
        }
    }

    /** Maps a raw one-letter transaction-status code to its canonical English name. */
    public static String decodeTxStatus(String code)
    {
        if (code == null)
        {
            return null;
        }
        switch (code)
        {
            case "N":
                return "NotTransactional";
            case "C":
                return "Committed";
            case "U":
                return "Unfinished";
            case "R":
                return "RolledBack";
            default:
                return code;
        }
    }

    /** Decodes {@code YYYYMMDDhhmmss} into ISO {@code YYYY-MM-DDThh:mm:ss}. */
    public static String toIso(long ts)
    {
        int yyyy = (int)(ts / 10000000000L);
        int mm = (int)((ts / 100000000L) % 100);
        int dd = (int)((ts / 1000000L) % 100);
        int hh = (int)((ts / 10000L) % 100);
        int mi = (int)((ts / 100L) % 100);
        int ss = (int)(ts % 100);
        return String.format("%04d-%02d-%02dT%02d:%02d:%02d",
            Integer.valueOf(yyyy), Integer.valueOf(mm), Integer.valueOf(dd),
            Integer.valueOf(hh), Integer.valueOf(mi), Integer.valueOf(ss));
    }

    /**
     * Encodes an ISO datetime back to {@code YYYYMMDDhhmmss}. Accepts a bare date
     * {@code YYYY-MM-DD} (treated as midnight) or a full {@code YYYY-MM-DDThh:mm:ss}.
     *
     * @param iso the ISO datetime (or date)
     * @return the packed {@code YYYYMMDDhhmmss} value
     */
    public static long fromIso(String iso)
    {
        String s = iso.trim();
        if (s.length() == 10)
        {
            s = s + "T00:00:00";
        }
        int yyyy = Integer.parseInt(s.substring(0, 4));
        int mm = Integer.parseInt(s.substring(5, 7));
        int dd = Integer.parseInt(s.substring(8, 10));
        int hh = Integer.parseInt(s.substring(11, 13));
        int mi = Integer.parseInt(s.substring(14, 16));
        int ss = Integer.parseInt(s.substring(17, 19));
        return (long)yyyy * 10000000000L
            + (long)mm * 100000000L
            + (long)dd * 1000000L
            + (long)hh * 10000L
            + (long)mi * 100L
            + (long)ss;
    }
}
