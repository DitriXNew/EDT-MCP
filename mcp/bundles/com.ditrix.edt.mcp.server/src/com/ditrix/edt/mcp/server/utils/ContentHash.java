/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes a short, stable revision token used for optimistic-lock preconditions.
 * <p>
 * A read tool emits {@code contentHash = ContentHash.of(text)} in its frontmatter;
 * a write tool accepts an {@code expectedHash} and recomputes {@code ContentHash.of}
 * over the current content. A mismatch means the content changed since the agent last
 * read it (a lost-update risk), so the write is rejected with a "read it again" steer —
 * the same optimistic-locking shape as {@code write_module_source}'s {@code expectedSource}
 * guard, only cheaper (a fixed-size token instead of the whole file).
 * <p>
 * The token is the lowercase hex of the first {@value #HEX_LENGTH} hex characters of the
 * SHA-256 of the {@code \n}-normalized UTF-8 text (64 bits — ample to detect an edit).
 * Both sides MUST call this method so they normalize and truncate identically; a
 * CRLF/LF difference alone is therefore never a spurious mismatch. The exact algorithm
 * is an implementation detail — callers treat the token as opaque and round-trip it
 * verbatim.
 */
public final class ContentHash
{
    /** Hex characters kept from the digest (64 bits — ample for an edit-collision guard). */
    private static final int HEX_LENGTH = 16;

    private ContentHash()
    {
        // utility class
    }

    /**
     * Computes the opaque revision token for the given text.
     *
     * @param text the content to hash (a {@code null} is treated as empty)
     * @return a {@value #HEX_LENGTH}-character lowercase hex token
     */
    public static String of(String text)
    {
        String normalized = (text == null ? "" : text).replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(HEX_LENGTH);
            for (int i = 0; hex.length() < HEX_LENGTH && i < digest.length; i++)
            {
                hex.append(String.format("%02x", digest[i] & 0xFF)); //$NON-NLS-1$
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            // SHA-256 is mandated on every conformant JRE, so this cannot happen.
            throw new IllegalStateException("SHA-256 algorithm not available", e); //$NON-NLS-1$
        }
    }

    /**
     * Returns whether {@code expected} (the token an agent carried over from its read)
     * still matches the live {@code currentText}. Tolerant of surrounding whitespace,
     * surrounding double-quotes (a YAML scalar an agent may have copied verbatim) and
     * letter case, so a faithfully round-tripped token always matches.
     *
     * @param currentText the live content
     * @param expected the token to check (a {@code null}/blank never matches)
     * @return {@code true} when both refer to the same content
     */
    public static boolean matches(String currentText, String expected)
    {
        if (expected == null)
        {
            return false;
        }
        String want = expected.trim();
        if (want.length() >= 2 && want.charAt(0) == '"' && want.charAt(want.length() - 1) == '"')
        {
            want = want.substring(1, want.length() - 1).trim();
        }
        if (want.isEmpty())
        {
            return false;
        }
        return of(currentText).equalsIgnoreCase(want);
    }
}
