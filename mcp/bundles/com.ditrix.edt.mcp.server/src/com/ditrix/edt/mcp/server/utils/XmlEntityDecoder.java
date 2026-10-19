/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decodes predefined XML entities and character references without parsing a document. */
public final class XmlEntityDecoder
{
    private static final Pattern REFERENCE =
        Pattern.compile("&(amp|lt|gt|quot|apos|#[0-9]+|#x[0-9A-Fa-f]+);"); //$NON-NLS-1$

    private XmlEntityDecoder()
    {
        // Utility class.
    }

    /**
     * Decodes references once in a source line. Unknown entities and invalid character references
     * remain literal; document declarations and external entities are never resolved.
     *
     * @param line the source line
     * @return the decoded text, which may contain a newline from a character reference
     */
    public static String decode(String line)
    {
        if (line.indexOf('&') < 0)
        {
            return line;
        }
        Matcher matcher = REFERENCE.matcher(line);
        StringBuilder decoded = new StringBuilder();
        while (matcher.find())
        {
            String replacement = switch (matcher.group(1))
            {
                case "amp" -> "&"; //$NON-NLS-1$ //$NON-NLS-2$
                case "lt" -> "<"; //$NON-NLS-1$ //$NON-NLS-2$
                case "gt" -> ">"; //$NON-NLS-1$ //$NON-NLS-2$
                case "quot" -> "\""; //$NON-NLS-1$ //$NON-NLS-2$
                case "apos" -> "'"; //$NON-NLS-1$ //$NON-NLS-2$
                default -> decodeCharacterReference(matcher.group(1), matcher.group());
            };
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(decoded);
        return decoded.toString();
    }

    private static String decodeCharacterReference(String entity, String original)
    {
        boolean hex = entity.startsWith("#x"); //$NON-NLS-1$
        try
        {
            int codePoint = Integer.parseInt(entity.substring(hex ? 2 : 1), hex ? 16 : 10);
            if (isXmlCharacter(codePoint))
            {
                return new String(Character.toChars(codePoint));
            }
        }
        catch (NumberFormatException e)
        {
            // An out-of-range reference remains searchable as written.
        }
        return original;
    }

    private static boolean isXmlCharacter(int codePoint)
    {
        return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
            || (codePoint >= 0x20 && codePoint <= 0xD7FF)
            || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
            || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }
}
