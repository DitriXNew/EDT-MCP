/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An immutable, ordered collection of {@link PiiRule}s - the whole PII detection table.
 * Order is significant: the redaction engine may apply rules first-match-wins, so the
 * bundled defaults list the most-sensitive (flat-mask) name rules before the ordinary
 * pseudonymised ones, and the content patterns in their original precedence.
 * <p>
 * Instances are created from a defensive, unmodifiable copy of the supplied list, so a
 * {@link PiiRuleSet} never observes later mutation of the caller's list.
 */
public final class PiiRuleSet
{
    /** The empty rule set (no redaction). */
    public static final PiiRuleSet EMPTY = new PiiRuleSet(List.of());

    private final List<PiiRule> rules;

    /**
     * Creates a rule set from a defensive copy of {@code rules}.
     *
     * @param rules the ordered rules (never {@code null}, no {@code null} elements)
     */
    public PiiRuleSet(List<PiiRule> rules)
    {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules")); //$NON-NLS-1$
    }

    /**
     * Convenience factory from a rule array.
     *
     * @param rules the ordered rules
     * @return a rule set over a copy of {@code rules}
     */
    public static PiiRuleSet of(PiiRule... rules)
    {
        return new PiiRuleSet(Arrays.asList(rules));
    }

    /**
     * @return the ordered rules, as an unmodifiable list
     */
    public List<PiiRule> getRules()
    {
        return rules;
    }

    /**
     * @return {@code true} if the set holds no rules
     */
    public boolean isEmpty()
    {
        return rules.isEmpty();
    }

    /**
     * @return the number of rules
     */
    public int size()
    {
        return rules.size();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof PiiRuleSet))
        {
            return false;
        }
        return rules.equals(((PiiRuleSet)o).rules);
    }

    @Override
    public int hashCode()
    {
        return rules.hashCode();
    }

    @Override
    public String toString()
    {
        return "PiiRuleSet" + rules; //$NON-NLS-1$
    }
}
