/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

import java.util.Locale;

/**
 * Classifies a NAME - a JSON key or a 1C attribute/variable name - into a
 * {@link Sensitivity} by a case-insensitive substring match against a small
 * dictionary of Russian personal-data terms. Returns {@code null} when the name
 * carries no personal-data signal.
 * <p>
 * The dictionary keys are Russian, so every Cyrillic literal is written with a
 * unicode escape (project rule 7: raw Cyrillic in source risks corruption under a
 * non-UTF-8 Tycho build). The needles are lowercase stems (a substring match catches
 * inflections). Each needle is annotated in transliteration so the intent is
 * readable without decoding the escapes.
 */
public final class AttributeNameDictionary
{
    /** Cyrillic small letter YO (folded to IE before matching). */
    private static final char YO = '\u0451';

    /** Cyrillic small letter IE (the target of the YO fold). */
    private static final char IE = '\u0435';

    /**
     * Special-category terms (152-FZ Art. 10): health / diagnosis / disability /
     * criminal record.
     */
    private static final String[] SPECIAL = {
        "\u0434\u0438\u0430\u0433\u043d\u043e\u0437", // diagnoz
        "\u0437\u0434\u043e\u0440\u043e\u0432", // zdorov (health)
        "\u0431\u043e\u043b\u0435\u0437\u043d", // bolezn (illness)
        "\u0438\u043d\u0432\u0430\u043b\u0438\u0434" // invalid (disability)
    };

    /** Biometric terms (152-FZ Art. 11): photo / biometric / fingerprint. */
    private static final String[] BIOMETRIC = {
        "\u0444\u043e\u0442\u043e", // foto (photo)
        "\u0431\u0438\u043e\u043c\u0435\u0442\u0440", // biometr
        "\u043e\u0442\u043f\u0435\u0447\u0430\u0442\u043e\u043a" // otpechatok (fingerprint)
    };

    /**
     * Ordinary personal-data terms: identity documents, names, contacts, address,
     * dates of birth. "email" is Latin (kept as-is); the rest are Cyrillic stems.
     */
    private static final String[] NORMAL = {
        "\u0441\u043d\u0438\u043b\u0441", // snils
        "\u043f\u0430\u0441\u043f\u043e\u0440\u0442", // pasport
        "\u0438\u043d\u043d", // inn
        "\u0444\u0438\u043e", // fio (full name)
        "\u0444\u0430\u043c\u0438\u043b", // famil (surname)
        "\u043e\u0442\u0447\u0435\u0441\u0442\u0432", // otchestv (patronymic)
        "\u0430\u0434\u0440\u0435\u0441", // adres (address)
        "\u0442\u0435\u043b\u0435\u0444\u043e\u043d", // telefon (phone)
        "\u043f\u043e\u0447\u0442", // pocht (mail)
        "\u0440\u043e\u0436\u0434\u0435\u043d", // rozhden (birth)
        "\u043f\u0440\u043e\u043f\u0438\u0441\u043a", // propisk (registration address)
        "email"
    };

    private AttributeNameDictionary()
    {
        // Utility class
    }

    /**
     * Classifies {@code name} by a case-insensitive, YO-folded substring match.
     * Most-sensitive class wins (special, then biometric, then normal).
     *
     * @param name the JSON key or attribute/variable name (may be {@code null})
     * @return the matched {@link Sensitivity}, or {@code null} when nothing matches
     */
    public static Sensitivity classify(String name)
    {
        if (name == null || name.isEmpty())
        {
            return null;
        }
        String needle = name.toLowerCase(Locale.ROOT).replace(YO, IE);
        if (containsAny(needle, SPECIAL))
        {
            return Sensitivity.SPECIAL;
        }
        if (containsAny(needle, BIOMETRIC))
        {
            return Sensitivity.BIOMETRIC;
        }
        if (containsAny(needle, NORMAL))
        {
            return Sensitivity.NORMAL;
        }
        return null;
    }

    private static boolean containsAny(String haystack, String[] needles)
    {
        for (String s : needles)
        {
            if (haystack.contains(s))
            {
                return true;
            }
        }
        return false;
    }
}
