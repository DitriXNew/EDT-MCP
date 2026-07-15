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

/**
 * Parses {@code 1Cv8.lgf} (the event-log reference dictionary) into an
 * {@link EventLogReferences}.
 *
 * <p>File layout:</p>
 * <pre>
 *   1CV8LOG(ver 2.0)
 *   &lt;file-uuid&gt;
 *
 *   {1,&lt;userUuid&gt;,"&lt;name&gt;",&lt;seq&gt;},
 *   {2,"&lt;computer&gt;",&lt;seq&gt;},
 *   ...
 * </pre>
 *
 * <p>The file is UTF-8, usually with a leading BOM. A doubled {@code ""} is the sole
 * in-string escape (an embedded double-quote). Files are opened read-only with the
 * platform's default share semantics, so parsing works even while the cluster is
 * writing to the log. Parsing is tolerant: an unrecognised or short record is skipped
 * rather than aborting the load.</p>
 *
 * <p>Zero EDT/OSGi dependencies - headless-unit-testable.</p>
 */
public final class LgfParser
{
    /**
     * Parses the given {@code 1Cv8.lgf} file.
     *
     * @param lgf the path to {@code 1Cv8.lgf}
     * @return the resolved reference tables
     * @throws IOException on a stream failure
     */
    public EventLogReferences parse(Path lgf) throws IOException
    {
        try (InputStream in = Files.newInputStream(lgf, StandardOpenOption.READ);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            return parse(br);
        }
    }

    /**
     * Parses the reference dictionary from an already-open reader (used by tests).
     *
     * @param br a reader over the {@code .lgf} content
     * @return the resolved reference tables
     * @throws IOException on a stream failure
     */
    public EventLogReferences parse(BufferedReader br) throws IOException
    {
        EventLogReferences out = new EventLogReferences();
        LgTokenizer.skipBomAndHeader(br);
        LgTokenizer tk = new LgTokenizer(br);
        LgToken rec;
        while ((rec = tk.nextRecord()) != null)
        {
            consume(rec, out);
        }
        return out;
    }

    private void consume(LgToken rec, EventLogReferences out)
    {
        if (!rec.isList() || rec.items.isEmpty())
        {
            return;
        }
        Long typeBoxed = rec.items.get(0).asLong();
        if (typeBoxed == null)
        {
            return;
        }
        int type = typeBoxed.intValue();
        List<LgToken> items = rec.items;
        switch (type)
        {
            case 1: // User record: uuid, name, trailing seq
                if (items.size() >= 4)
                {
                    Integer seq = trailingSeq(items);
                    if (seq != null)
                    {
                        out.users.put(seq,
                            new EventLogReferences.User(items.get(1).asString(), items.get(2).asString()));
                    }
                }
                break;
            case 2: // Computer record: name, trailing seq
                putName(items, out.computers);
                break;
            case 3: // Application record: name, trailing seq
                putName(items, out.applications);
                break;
            case 4: // Event record: name, trailing seq
                putName(items, out.events);
                break;
            case 5: // Metadata record: uuid, fullName, trailing seq
                if (items.size() >= 4)
                {
                    Integer seq = trailingSeq(items);
                    if (seq != null)
                    {
                        out.metadata.put(seq,
                            new EventLogReferences.Metadata(items.get(1).asString(), items.get(2).asString()));
                    }
                }
                break;
            case 6: // WorkServer record: name, trailing seq
                putName(items, out.servers);
                break;
            case 7: // MainPort record: port, trailing seq
                putPort(items, out.mainPorts);
                break;
            case 8: // SecondaryPort record: port, trailing seq
                putPort(items, out.secondaryPorts);
                break;
            default:
                // 9 (DataSeparator), 10-13 = internal housekeeping - intentionally ignored.
                break;
        }
    }

    private void putName(List<LgToken> items, java.util.Map<Integer, String> target)
    {
        if (items.size() >= 3)
        {
            Integer seq = trailingSeq(items);
            if (seq != null)
            {
                target.put(seq, items.get(1).asString());
            }
        }
    }

    private void putPort(List<LgToken> items, java.util.Map<Integer, Integer> target)
    {
        if (items.size() >= 3)
        {
            Long port = items.get(1).asLong();
            Integer seq = trailingSeq(items);
            if (seq != null && port != null)
            {
                target.put(seq, Integer.valueOf(port.intValue()));
            }
        }
    }

    /** The reference index is the LAST integer in the record (names/UUIDs sit before it). */
    private Integer trailingSeq(List<LgToken> items)
    {
        for (int i = items.size() - 1; i >= 0; i--)
        {
            Long v = items.get(i).asLong();
            if (v != null)
            {
                return Integer.valueOf(v.intValue());
            }
        }
        return null;
    }
}
