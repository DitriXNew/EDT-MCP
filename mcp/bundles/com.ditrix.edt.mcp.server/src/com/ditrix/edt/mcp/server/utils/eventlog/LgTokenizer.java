/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.eventlog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming tokenizer for 1C event-log files ({@code 1Cv8.lgf}, {@code *.lgp}).
 *
 * <p>The on-disk format is a chain of top-level S-expression-like groups separated
 * by commas. Each group is a {@code {...}} list whose elements are atoms (bare ints
 * or identifiers like {@code N}, {@code C}, {@code I}), strings ({@code "..."}, where
 * a doubled {@code ""} is the sole escape - an embedded double-quote), or nested
 * {@code {...}} lists.</p>
 *
 * <p>{@link #nextRecord()} reads ONE top-level record per call and returns {@code null}
 * at end of input. The caller supplies a {@link Reader} already positioned past the
 * BOM/header (see {@link LgfParser} / {@link LgpParser}).</p>
 *
 * <p><b>Tolerance is the point.</b> A file-mode cluster can be actively writing the
 * log while we read, so the last record may be truncated mid-string or mid-list. This
 * tokenizer therefore NEVER throws on malformed/truncated input: it returns whatever it
 * managed to read as a (possibly short) list and reports end-of-input on the next call.
 * A truncated trailing record then simply fails the positional field-count check in the
 * decoder and is skipped, instead of aborting the entire read.</p>
 *
 * <p>Zero EDT/OSGi dependencies - headless-unit-testable.</p>
 */
public final class LgTokenizer
{
    private final Reader in;
    private int peeked = NONE;
    private long pos;

    /** Sentinel for "nothing peeked yet" ({@code -2}, distinct from {@code -1} = EOF). */
    private static final int NONE = -2;

    public LgTokenizer(Reader in)
    {
        this.in = in;
    }

    /** @return the number of characters consumed so far (for diagnostics). */
    public long pos()
    {
        return this.pos;
    }

    /**
     * Positions the reader just before the first record, past a UTF-8 BOM and the
     * two-line {@code 1CV8LOG(ver 2.0)} / file-uuid header that both {@code .lgf} and
     * {@code .lgp} files carry. Uses {@code mark}/{@code reset} so the first record is
     * left intact. Safe on a headerless stream (e.g. a test fixture) - it stops at the
     * first line that begins with {@code '{'}.
     *
     * @param br a markable reader over the file content
     * @throws IOException on a stream failure
     */
    public static void skipBomAndHeader(BufferedReader br) throws IOException
    {
        br.mark(8);
        int first = br.read();
        if (first != 0xFEFF)
        {
            // Not a BOM - push the char back so the header scan sees the whole first line.
            br.reset();
        }
        for (int i = 0; i < 5; i++)
        {
            br.mark(256);
            String line = br.readLine();
            if (line == null)
            {
                return;
            }
            if (line.trim().startsWith("{"))
            {
                br.reset();
                return;
            }
        }
    }

    /**
     * Reads the next top-level {@code {...}} record.
     *
     * @return the record token, or {@code null} at end of input
     * @throws IOException only on an underlying stream failure (never on malformed content)
     */
    public LgToken nextRecord() throws IOException
    {
        while (true)
        {
            skipWhitespaceAndCommas();
            int c = peek();
            if (c < 0)
            {
                return null;
            }
            if (c == '{')
            {
                return readList();
            }
            // Tolerate stray bytes between records (e.g. a partial header line or a
            // torn separator from a concurrent writer): skip and resynchronise on '{'.
            read();
        }
    }

    private LgToken readList() throws IOException
    {
        // Caller guarantees the next char is '{'.
        read();
        List<LgToken> items = new ArrayList<>();
        while (true)
        {
            skipWhitespace();
            int c = peek();
            if (c < 0)
            {
                // EOF inside a list - tolerate a truncated trailing record.
                return LgToken.list(items);
            }
            if (c == '}')
            {
                read();
                return LgToken.list(items);
            }
            LgToken t;
            if (c == '{')
            {
                t = readList();
            }
            else if (c == '"')
            {
                t = readString();
            }
            else
            {
                t = readAtom();
            }
            items.add(t);
            skipWhitespace();
            int n = read();
            if (n == ',')
            {
                continue;
            }
            if (n == '}')
            {
                return LgToken.list(items);
            }
            // EOF or an unexpected separator on a live-write boundary: close the list
            // gracefully with what we have rather than crashing the whole read.
            return LgToken.list(items);
        }
    }

    private LgToken readString() throws IOException
    {
        // Caller guarantees the next char is '"'.
        read();
        StringBuilder sb = new StringBuilder();
        while (true)
        {
            int c = read();
            if (c < 0)
            {
                // EOF inside a string - return the partial body (truncated trailing record).
                return LgToken.string(sb.toString());
            }
            if (c == '"')
            {
                if (peek() == '"')
                {
                    read();
                    sb.append('"');
                    continue;
                }
                return LgToken.string(sb.toString());
            }
            sb.append((char)c);
        }
    }

    private LgToken readAtom() throws IOException
    {
        StringBuilder sb = new StringBuilder();
        while (true)
        {
            int c = peek();
            if (c < 0 || c == ',' || c == '}' || c == '{' || c == '"'
                || c == ' ' || c == '\r' || c == '\n' || c == '\t')
            {
                break;
            }
            sb.append((char)read());
        }
        return LgToken.atom(sb.toString());
    }

    private void skipWhitespace() throws IOException
    {
        while (true)
        {
            int c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n')
            {
                read();
            }
            else
            {
                return;
            }
        }
    }

    private void skipWhitespaceAndCommas() throws IOException
    {
        while (true)
        {
            int c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ',')
            {
                read();
            }
            else
            {
                return;
            }
        }
    }

    private int peek() throws IOException
    {
        if (this.peeked == NONE)
        {
            this.peeked = this.in.read();
        }
        return this.peeked;
    }

    private int read() throws IOException
    {
        int c;
        if (this.peeked == NONE)
        {
            c = this.in.read();
        }
        else
        {
            c = this.peeked;
            this.peeked = NONE;
        }
        if (c >= 0)
        {
            this.pos++;
        }
        return c;
    }
}
