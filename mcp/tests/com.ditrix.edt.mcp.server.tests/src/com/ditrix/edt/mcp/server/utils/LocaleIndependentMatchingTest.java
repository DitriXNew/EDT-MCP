// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.util.Locale;
import org.junit.Test;

/** Exercises production lookup keys and bilingual folding under a Turkish host locale. */
public class LocaleIndependentMatchingTest
{
    @Test public void metadataTokensAndCyrillicNamesSurviveDefaultLocaleChanges()
    {
        Locale original = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try
        {
            for (Locale locale : new Locale[] { Locale.ENGLISH, Locale.forLanguageTag("tr-TR") })
            {
                Locale.setDefault(locale);
                assertEquals("InformationRegister", MetadataTypeUtils.toEnglishSingular("INFORMATIONREGISTERS"));
                assertEquals("InformationRegister", MetadataTypeUtils.toEnglishSingular("РЕГИСТРСВЕДЕНИЙ"));
                assertEquals("InformationRegister.Имена", MetadataTypeUtils.normalizeFqn("РЕГИСТРСВЕДЕНИЙ.Имена"));
                assertTrue(MetadataTypeUtils.getAllFqnVariants("InformationRegister.Имена")
                    .contains("informationregister.имена"));
            }
        }
        finally
        {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
        assertEquals(original, Locale.getDefault());
        assertEquals(display, Locale.getDefault(Locale.Category.DISPLAY));
        assertEquals(format, Locale.getDefault(Locale.Category.FORMAT));
    }
}
