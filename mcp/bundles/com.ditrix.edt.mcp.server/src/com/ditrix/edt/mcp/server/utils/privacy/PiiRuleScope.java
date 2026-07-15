/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

/**
 * Where a {@link PiiRule} applies: to a NAME (a JSON key / attribute / variable name),
 * to a value's content (VALUE), or to {@link #BOTH}. This replaces the old two-class
 * split (the name dictionary vs the content patterns) with a per-rule scope, so a
 * user-authored rule can target either surface.
 * <p>
 * A NAME-scope rule classifies a value by its enclosing key (and replaces the whole
 * value); a VALUE-scope rule scans the value content and replaces only the matched
 * spans. {@link #BOTH} does both and is the fail-closed default for a rule whose scope
 * is omitted.
 */
public enum PiiRuleScope
{
    /** Matches against a NAME (JSON key / attribute / variable name); replaces the whole value. */
    NAME,

    /** Matches against a value's content; replaces the matched spans in place. */
    VALUE,

    /** Matches against both the enclosing name and the value content. */
    BOTH;

    /**
     * Whether this scope covers the NAME surface (a JSON key / attribute name).
     *
     * @return {@code true} for {@link #NAME} and {@link #BOTH}
     */
    public boolean appliesToName()
    {
        return this != VALUE;
    }

    /**
     * Whether this scope covers the value-content surface.
     *
     * @return {@code true} for {@link #VALUE} and {@link #BOTH}
     */
    public boolean appliesToValue()
    {
        return this != NAME;
    }
}
