/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Content-level backstop: compiled regexes that detect structured personal data
 * embedded in a free-form string VALUE (as opposed to
 * {@link AttributeNameDictionary}, which classifies a NAME). Each detected span is
 * replaced with a stable pseudonym via {@link Pseudonymizer}.
 * <p>
 * All patterns are structural (digit groupings, e-mail, phone) and need no Cyrillic
 * literals; if a Cyrillic-anchored pattern is ever added here it MUST use a unicode
 * escape, never a raw Cyrillic byte (project rule 7).
 * <p>
 * The bare INN rule matches any standalone 10- or 12-digit number, so an unrelated
 * order/barcode number can be over-masked. That is a deliberate fail-closed
 * trade-off for this backstop layer: over-masking is acceptable because the redactor
 * runs ONLY when the operator has explicitly turned it on.
 */
public final class ContentPatterns
{
    /** E-mail address. */
    private static final Pattern EMAIL =
        Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** SNILS (Russian insurance number): 123-456-789 01. */
    private static final Pattern SNILS =
        Pattern.compile("\\b\\d{3}-\\d{3}-\\d{3}\\s?\\d{2}\\b");

    /** Russian internal passport: 4-digit series + 6-digit number, space-separated. */
    private static final Pattern PASSPORT =
        Pattern.compile("\\b\\d{4}\\s\\d{6}\\b");

    /** Russian phone: +7 / 8 prefix with common separators, 10 significant digits. */
    private static final Pattern PHONE =
        Pattern.compile("(?:\\+7|8)[\\s\\-()]*\\d{3}[\\s\\-()]*\\d{3}[\\s\\-()]*\\d{2}[\\s\\-()]*\\d{2}\\b");

    /** INN: 10 digits (legal entity) or 12 digits (natural person), as a standalone token. */
    private static final Pattern INN =
        Pattern.compile("\\b\\d{10}\\b|\\b\\d{12}\\b");

    /**
     * Application order: the structured patterns run first and the broad INN
     * digit-net runs last, so a passport / SNILS / phone span is consumed by its
     * specific rule before the bare 10/12-digit rule can mislabel it.
     */
    private static final Pattern[] PATTERNS = {EMAIL, SNILS, PASSPORT, PHONE, INN};

    private ContentPatterns()
    {
        // Utility class
    }

    /**
     * Reports whether {@code text} contains any detectable structured personal data.
     *
     * @param text the value to scan (may be {@code null})
     * @return {@code true} if at least one pattern matches
     */
    public static boolean containsPii(String text)
    {
        if (text == null || text.isEmpty())
        {
            return false;
        }
        for (Pattern p : PATTERNS)
        {
            if (p.matcher(text).find())
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces every matched personal-data span in {@code text} with a stable
     * pseudonym (all content matches are {@link Sensitivity#NORMAL}). Returns the
     * rewritten text and the number of spans replaced; a zero count means no match
     * (the caller then keeps the original value reference unchanged).
     *
     * @param text the value to redact (may be {@code null}/empty)
     * @param pseudo the pseudonymiser producing the replacement tokens
     * @return the result holding the (possibly rewritten) text and the match count
     */
    static RedactionResult redact(String text, Pseudonymizer pseudo)
    {
        if (text == null || text.isEmpty())
        {
            return new RedactionResult(text, 0);
        }
        int[] count = {0};
        String out = text;
        for (Pattern p : PATTERNS)
        {
            Matcher m = p.matcher(out);
            out = m.replaceAll((MatchResult mr) -> {
                count[0]++;
                return pseudo.token(Sensitivity.NORMAL, mr.group());
            });
        }
        return new RedactionResult(out, count[0]);
    }

    /** Immutable carrier for {@link #redact}: the rewritten text and the match count. */
    static final class RedactionResult
    {
        final String text;
        final int count;

        RedactionResult(String text, int count)
        {
            this.text = text;
            this.count = count;
        }
    }
}
