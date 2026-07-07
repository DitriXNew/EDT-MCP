/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic, non-reversible pseudonymiser for {@link Sensitivity#NORMAL}
 * personal data. Produces a short, stable token of the form {@code PREFIX#hex}
 * (the "natural person" prefix plus an 8-hex-digit HMAC-SHA256 suffix), so the SAME
 * input yields the SAME token within one JVM run without keeping any
 * token-to-value table. Special-category / biometric data is fully masked instead
 * (a stable token would still be a linkable identifier).
 * <p>
 * The HMAC key is drawn once from {@link SecureRandom} at construction, i.e. once per
 * JVM run: tokens are stable within a run but NOT linkable across runs (a fresh key
 * each restart). The Cyrillic prefix is a unicode escape (project rule 7).
 */
public final class Pseudonymizer
{
    /** Token prefix for pseudonymised NORMAL data ("natural person"). */
    static final String PREFIX = "\u0424\u0438\u0437\u043b\u0438\u0446\u043e";

    /** Full mask for special-category / biometric data (never pseudonymised). */
    public static final String MASK = "[redacted]";

    /** Cyrillic small letter YO (folded to IE during normalisation). */
    private static final char YO = '\u0451';

    /** Cyrillic small letter IE. */
    private static final char IE = '\u0435';

    private final byte[] key;

    /** Creates a pseudonymiser with a fresh random per-run key. */
    public Pseudonymizer()
    {
        this.key = randomKey();
    }

    private static byte[] randomKey()
    {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    /**
     * Returns the redaction token for {@code value} at the given sensitivity:
     * {@link #MASK} for full-mask classes, a stable {@code PREFIX#hex} pseudonym for
     * {@link Sensitivity#NORMAL}, and the value unchanged for a {@code null} class.
     *
     * @param sensitivity the value's sensitivity (may be {@code null})
     * @param value the raw value
     * @return the token to emit in place of {@code value}
     */
    public String token(Sensitivity sensitivity, String value)
    {
        if (sensitivity == null)
        {
            return value;
        }
        if (sensitivity.isFullMask())
        {
            return MASK;
        }
        return PREFIX + "#" + shortHmac(normalize(value));
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
            return "";
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
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x%02x%02x%02x", h[0], h[1], h[2], h[3]);
        }
        catch (GeneralSecurityException e)
        {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
