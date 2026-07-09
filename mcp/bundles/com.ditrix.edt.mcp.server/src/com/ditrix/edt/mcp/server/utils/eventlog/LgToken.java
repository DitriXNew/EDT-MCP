/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.eventlog;

import java.util.List;

/**
 * A single token produced by {@link LgTokenizer} while reading a 1C event-log file
 * ({@code 1Cv8.lgf} or a {@code *.lgp} partition).
 *
 * <p>A log "record" is a small tree of these: either a primitive
 * ({@link Kind#ATOM} - a bare int/identifier, {@link Kind#STRING} - a quoted string)
 * or a {@link Kind#LIST} of children (the elements of a {@code {...}} group).</p>
 *
 * <p>This type has zero EDT/OSGi dependencies so the whole {@code eventlog} package
 * is headless-unit-testable.</p>
 */
public final class LgToken
{
    /** The three shapes a token can take. */
    public enum Kind
    {
        ATOM, STRING, LIST
    }

    /** The token shape - never {@code null}. */
    public final Kind kind;

    /** Raw ATOM text (no quotes) or the unescaped STRING body; {@code null} for a LIST. */
    public final String text;

    /** Child tokens of a LIST; {@code null} for ATOM/STRING. */
    public final List<LgToken> items;

    private LgToken(Kind kind, String text, List<LgToken> items)
    {
        this.kind = kind;
        this.text = text;
        this.items = items;
    }

    /** Creates a bare-atom token (an int or an unquoted identifier). */
    public static LgToken atom(String s)
    {
        return new LgToken(Kind.ATOM, s, null);
    }

    /** Creates a string token (the already-unescaped body of a {@code "..."} literal). */
    public static LgToken string(String s)
    {
        return new LgToken(Kind.STRING, s, null);
    }

    /** Creates a list token wrapping the given children. */
    public static LgToken list(List<LgToken> items)
    {
        return new LgToken(Kind.LIST, null, items);
    }

    /** @return {@code true} when this token is a bare atom. */
    public boolean isAtom()
    {
        return this.kind == Kind.ATOM;
    }

    /** @return {@code true} when this token is a quoted string. */
    public boolean isString()
    {
        return this.kind == Kind.STRING;
    }

    /** @return {@code true} when this token is a {@code {...}} list. */
    public boolean isList()
    {
        return this.kind == Kind.LIST;
    }

    /**
     * @return the text of an ATOM or STRING, or {@code null} for a LIST. A LIST
     *         yielding {@code null} here is what lets a positional decoder treat a
     *         nested-tree data field as "no scalar value" without a crash.
     */
    public String asString()
    {
        if (this.kind == Kind.STRING || this.kind == Kind.ATOM)
        {
            return this.text;
        }
        return null;
    }

    /**
     * @return the atom parsed as a {@code Long}, or {@code null} when this is not an
     *         atom or does not parse (never throws).
     */
    public Long asLong()
    {
        if (this.kind != Kind.ATOM)
        {
            return null;
        }
        try
        {
            return Long.valueOf(Long.parseLong(this.text));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
