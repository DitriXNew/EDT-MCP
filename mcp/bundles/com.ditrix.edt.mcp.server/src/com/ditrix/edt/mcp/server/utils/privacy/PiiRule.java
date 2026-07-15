/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

import java.util.Objects;

/**
 * One immutable PII detection rule in the user-configurable rule table (replacing the
 * hardcoded {@code AttributeNameDictionary} / {@code ContentPatterns}). A rule is a
 * plain value object; the redaction engine (a separate layer) compiles {@link #regex}
 * and decides how to emit {@link #representation} based on {@link #countable}.
 * <ul>
 * <li>{@link #enabled} - whether the rule participates at all.</li>
 * <li>{@link #regex} - the detection pattern. Under a {@link PiiRuleScope#NAME} scope
 * it is matched (case-insensitively) against a JSON key / attribute name; under
 * {@link PiiRuleScope#VALUE} it is matched against the value content; a name-dictionary
 * stem (a literal attribute-name fragment) is itself a valid regex.</li>
 * <li>{@link #scope} - which surface(s) the rule targets.</li>
 * <li>{@link #representation} - the token stem emitted in place of a match (e.g. the
 * "natural person" pseudonym stem, or a flat mask like {@code [redacted]}).</li>
 * <li>{@link #countable} - when {@code true} the engine appends a stable, per-value
 * pseudonym suffix so distinct values stay distinguishable (a "countable" pseudonym);
 * when {@code false} the representation is emitted verbatim as a flat, non-linkable
 * mask.</li>
 * </ul>
 * The class carries NO {@code mask} field: whether a match becomes a flat mask or a
 * distinguishable pseudonym is expressed entirely by {@link #countable}.
 */
public final class PiiRule
{
    private final boolean enabled;
    private final String regex;
    private final PiiRuleScope scope;
    private final String representation;
    private final boolean countable;

    /**
     * Creates an immutable rule.
     *
     * @param enabled whether the rule participates
     * @param regex the detection pattern (never {@code null})
     * @param scope which surface(s) the rule targets (never {@code null})
     * @param representation the token stem emitted in place of a match (never {@code null})
     * @param countable {@code true} to emit a distinguishable per-value pseudonym,
     *            {@code false} to emit {@code representation} verbatim as a flat mask
     */
    public PiiRule(boolean enabled, String regex, PiiRuleScope scope, String representation, boolean countable)
    {
        this.enabled = enabled;
        this.regex = Objects.requireNonNull(regex, "regex"); //$NON-NLS-1$
        this.scope = Objects.requireNonNull(scope, "scope"); //$NON-NLS-1$
        this.representation = Objects.requireNonNull(representation, "representation"); //$NON-NLS-1$
        this.countable = countable;
    }

    /**
     * @return whether the rule participates in detection
     */
    public boolean isEnabled()
    {
        return enabled;
    }

    /**
     * @return the detection pattern (a stem for a NAME scope, a full regex for a VALUE scope)
     */
    public String getRegex()
    {
        return regex;
    }

    /**
     * @return which surface(s) the rule targets
     */
    public PiiRuleScope getScope()
    {
        return scope;
    }

    /**
     * @return the token stem emitted in place of a match
     */
    public String getRepresentation()
    {
        return representation;
    }

    /**
     * @return {@code true} to emit a distinguishable per-value pseudonym; {@code false}
     *         to emit the representation verbatim as a flat mask
     */
    public boolean isCountable()
    {
        return countable;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof PiiRule))
        {
            return false;
        }
        PiiRule other = (PiiRule)o;
        return enabled == other.enabled
            && countable == other.countable
            && regex.equals(other.regex)
            && scope == other.scope
            && representation.equals(other.representation);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(Boolean.valueOf(enabled), regex, scope, representation, Boolean.valueOf(countable));
    }

    @Override
    public String toString()
    {
        return "PiiRule[enabled=" + enabled //$NON-NLS-1$
            + ", scope=" + scope //$NON-NLS-1$
            + ", countable=" + countable //$NON-NLS-1$
            + ", representation=" + representation //$NON-NLS-1$
            + ", regex=" + regex + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
