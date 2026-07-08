/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic, non-reversible pseudonymiser driving how a {@link PiiRule} hit is
 * rendered. It turns a matched value (or span) into the rule's token per its
 * {@link PiiRule#isCountable()} flag:
 * <ul>
 * <li>countable &rarr; {@code representation + "#" + shortHmac} - a stable
 * {@code PREFIX#hex} pseudonym (an 8-hex-digit HMAC-SHA256 suffix) so equal inputs map
 * to the SAME token and stay correlatable, without keeping any token-to-value table;</li>
 * <li>not countable &rarr; {@code representation} verbatim - a flat, non-linkable mask
 * (e.g. {@link #MASK} for special-category / biometric data that must never be
 * pseudonymised, since even a stable token would be a linkable identifier).</li>
 * </ul>
 * <p>
 * The HMAC key is DERIVED from the caller-supplied {@code salt} (SHA-256 of its UTF-8
 * bytes): two pseudonymisers built with the SAME non-blank salt produce identical
 * tokens (stable across calls, restarts and stands), so an operator who configures a
 * salt gets cross-run-linkable pseudonyms. A blank / {@code null} salt falls back to a
 * fresh {@link SecureRandom} key PER INSTANCE - tokens are then stable only within the
 * one pass that shares this instance and are unlinkable across passes. There is NO
 * process-global random key: the engine constructs one pseudonymiser per redaction pass.
 * <p>
 * {@link #PREFIX} ("natural person") and {@link #MASK} are the canonical default
 * representation stems reused by {@code pii-defaults.json} / {@code PiiRuleCodec}; the
 * Cyrillic {@link #PREFIX} is a unicode escape (project rule 7).
 */
public final class Pseudonymizer
{
    /** Canonical token stem for ordinary pseudonymised data ("natural person", Fizlico). */
    public static final String PREFIX = "\u0424\u0438\u0437\u043b\u0438\u0446\u043e"; //$NON-NLS-1$

    /** Canonical flat mask for special-category / biometric data (never pseudonymised). */
    public static final String MASK = "[redacted]"; //$NON-NLS-1$

    /** Separator between a pseudonym stem and its short HMAC suffix. */
    private static final String SUFFIX_SEPARATOR = "#"; //$NON-NLS-1$

    /** Cyrillic small letter YO (folded to IE during normalisation). */
    private static final char YO = '\u0451';

    /** Cyrillic small letter IE. */
    private static final char IE = '\u0435';

    private final byte[] key;

    /**
     * Creates a pseudonymiser whose HMAC key is derived from {@code salt}.
     *
     * @param salt the pseudonym salt: a non-blank value derives a STABLE key (equal
     *            salts &rarr; equal tokens); a {@code null} / blank value falls back to a
     *            fresh per-instance {@link SecureRandom} key (tokens unlinkable across
     *            instances)
     */
    public Pseudonymizer(String salt)
    {
        this.key = deriveKey(salt);
    }

    /**
     * Derives a 32-byte HMAC key: {@code SHA-256(salt)} for a non-blank salt (stable),
     * or a fresh {@link SecureRandom} block for a {@code null} / blank salt (per-instance).
     */
    private static byte[] deriveKey(String salt)
    {
        if (salt == null || salt.trim().isEmpty())
        {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            return random;
        }
        try
        {
            return MessageDigest.getInstance("SHA-256").digest(salt.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 unavailable", e); //$NON-NLS-1$
        }
    }

    /**
     * Renders the redaction token for a matched value at a rule's settings.
     *
     * @param representation the token stem (the pseudonym prefix, or the whole mask);
     *            a {@code null} is treated as empty
     * @param countable {@code true} to append a stable {@code #hmac} pseudonym suffix;
     *            {@code false} to return {@code representation} verbatim (a flat mask)
     * @param value the raw matched value (hashed only when {@code countable})
     * @return the token to emit in place of {@code value}
     */
    public String token(String representation, boolean countable, String value)
    {
        String stem = representation != null ? representation : ""; //$NON-NLS-1$
        if (!countable)
        {
            return stem;
        }
        return stem + SUFFIX_SEPARATOR + shortHmac(normalize(value));
    }

    /**
     * Canonicalises a value so trivially different spellings map to the same token:
     * trim, collapse internal whitespace to a single space, lower-case, fold YO to IE.
     *
     * @param v the raw value (may be {@code null})
     * @return the normalised form (never {@code null})
     */
    static String normalize(String v)
    {
        if (v == null)
        {
            return ""; //$NON-NLS-1$
        }
        String lower = v.trim().toLowerCase(Locale.ROOT).replace(YO, IE);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean prevSpace = false;
        for (int i = 0; i < lower.length(); i++)
        {
            char c = lower.charAt(i);
            if (Character.isWhitespace(c))
            {
                if (!prevSpace)
                {
                    sb.append(' ');
                    prevSpace = true;
                }
            }
            else
            {
                sb.append(c);
                prevSpace = false;
            }
        }
        return sb.toString().trim();
    }

    private String shortHmac(String normalized)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256"); //$NON-NLS-1$
            mac.init(new SecretKeySpec(key, "HmacSHA256")); //$NON-NLS-1$
            byte[] h = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x%02x%02x%02x", h[0], h[1], h[2], h[3]); //$NON-NLS-1$
        }
        catch (GeneralSecurityException e)
        {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e); //$NON-NLS-1$
        }
    }
}
