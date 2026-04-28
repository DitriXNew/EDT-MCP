/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.common.util.BasicEMap;
import org.eclipse.emf.common.util.EMap;
import org.junit.Test;

/**
 * Tests for {@link SubsystemUtils}.
 *
 * <p>Direct unit coverage for: type-token recognition ({@code isSubsystemTypeToken}),
 * FQN parsing ({@code parseSubsystemPath}), synonym lookup with language fallback
 * ({@code getSynonymForLanguage}, exercised via {@link BasicEMap}), and language
 * resolution ({@code resolveLanguage}) without a {@code Configuration}.</p>
 *
 * <p>The full {@code resolveByFqn} method needs an EMF {@code Configuration}
 * with live {@code Subsystem} children, so it is covered through e2e tests
 * against {@code TestConfiguration} rather than here.</p>
 */
public class SubsystemUtilsTest
{
    // ========== isSubsystemTypeToken ==========

    @Test
    public void testTypeTokenEnglishSingular()
    {
        assertTrue(SubsystemUtils.isSubsystemTypeToken("Subsystem")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenEnglishPlural()
    {
        assertTrue(SubsystemUtils.isSubsystemTypeToken("Subsystems")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenCaseInsensitive()
    {
        assertTrue(SubsystemUtils.isSubsystemTypeToken("subsystem")); //$NON-NLS-1$
        assertTrue(SubsystemUtils.isSubsystemTypeToken("SUBSYSTEM")); //$NON-NLS-1$
        assertTrue(SubsystemUtils.isSubsystemTypeToken("SubSystem")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenRussianSingular()
    {
        // Подсистема
        assertTrue(SubsystemUtils.isSubsystemTypeToken("Подсистема")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenRussianPlural()
    {
        // Подсистемы
        assertTrue(SubsystemUtils.isSubsystemTypeToken("Подсистемы")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenWithWhitespace()
    {
        assertTrue(SubsystemUtils.isSubsystemTypeToken(" Subsystem ")); //$NON-NLS-1$
        assertTrue(SubsystemUtils.isSubsystemTypeToken("\tSubsystem")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenNotASubsystem()
    {
        assertFalse(SubsystemUtils.isSubsystemTypeToken("Catalog")); //$NON-NLS-1$
        assertFalse(SubsystemUtils.isSubsystemTypeToken("Document")); //$NON-NLS-1$
        assertFalse(SubsystemUtils.isSubsystemTypeToken("Role")); //$NON-NLS-1$
        assertFalse(SubsystemUtils.isSubsystemTypeToken("Справочник")); // Справочник //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenNullOrEmpty()
    {
        assertFalse(SubsystemUtils.isSubsystemTypeToken(null));
        assertFalse(SubsystemUtils.isSubsystemTypeToken("")); //$NON-NLS-1$
        assertFalse(SubsystemUtils.isSubsystemTypeToken("   ")); //$NON-NLS-1$
    }

    @Test
    public void testTypeTokenGarbage()
    {
        assertFalse(SubsystemUtils.isSubsystemTypeToken("Sub")); //$NON-NLS-1$
        assertFalse(SubsystemUtils.isSubsystemTypeToken("System")); //$NON-NLS-1$
        assertFalse(SubsystemUtils.isSubsystemTypeToken("foo bar")); //$NON-NLS-1$
    }

    // ========== parseSubsystemPath ==========

    @Test
    public void testParseTopLevel()
    {
        assertArrayEquals(new String[] { "Sales" }, //$NON-NLS-1$
            SubsystemUtils.parseSubsystemPath("Subsystem.Sales")); //$NON-NLS-1$
    }

    @Test
    public void testParseNested()
    {
        assertArrayEquals(new String[] { "Sales", "Orders" }, //$NON-NLS-1$ //$NON-NLS-2$
            SubsystemUtils.parseSubsystemPath("Subsystem.Sales.Subsystem.Orders")); //$NON-NLS-1$
    }

    @Test
    public void testParseDeeplyNested()
    {
        assertArrayEquals(new String[] { "Sales", "Orders", "Backlog" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            SubsystemUtils.parseSubsystemPath("Subsystem.Sales.Subsystem.Orders.Subsystem.Backlog")); //$NON-NLS-1$
    }

    @Test
    public void testParseRussianTopLevel()
    {
        // Подсистема.Продажи
        String fqn = "Подсистема.Продажи"; //$NON-NLS-1$
        assertArrayEquals(new String[] { "Продажи" }, // Продажи //$NON-NLS-1$
            SubsystemUtils.parseSubsystemPath(fqn));
    }

    @Test
    public void testParseRussianNested()
    {
        // Подсистема.Продажи.Подсистема.Заказы
        String fqn = "Подсистема.Продажи.Подсистема.Заказы"; //$NON-NLS-1$
        assertArrayEquals(
            new String[] { "Продажи", "Заказы" }, // Продажи, Заказы //$NON-NLS-1$ //$NON-NLS-2$
            SubsystemUtils.parseSubsystemPath(fqn));
    }

    @Test
    public void testParseMixedEnglishRussian()
    {
        // Подсистема.Продажи.Subsystem.Orders
        String fqn = "Подсистема.Продажи.Subsystem.Orders"; //$NON-NLS-1$
        assertArrayEquals(
            new String[] { "Продажи", "Orders" }, // Продажи, Orders //$NON-NLS-1$ //$NON-NLS-2$
            SubsystemUtils.parseSubsystemPath(fqn));
    }

    @Test
    public void testParseLowercaseTypeToken()
    {
        assertArrayEquals(new String[] { "Sales" }, //$NON-NLS-1$
            SubsystemUtils.parseSubsystemPath("subsystem.Sales")); //$NON-NLS-1$
    }

    @Test
    public void testParsePluralTypeToken()
    {
        assertArrayEquals(new String[] { "Sales" }, //$NON-NLS-1$
            SubsystemUtils.parseSubsystemPath("Subsystems.Sales")); //$NON-NLS-1$
    }

    @Test
    public void testParseLeadingTrailingWhitespace()
    {
        assertArrayEquals(new String[] { "Sales" }, //$NON-NLS-1$
            SubsystemUtils.parseSubsystemPath("  Subsystem.Sales  ")); //$NON-NLS-1$
    }

    @Test
    public void testParseNameTrimmed()
    {
        // Each name segment is trimmed individually after splitting on '.'
        assertArrayEquals(new String[] { "Sales", "Orders" }, //$NON-NLS-1$ //$NON-NLS-2$
            SubsystemUtils.parseSubsystemPath("Subsystem. Sales .Subsystem. Orders ")); //$NON-NLS-1$
    }

    @Test
    public void testParseWrongTypeToken()
    {
        assertNull(SubsystemUtils.parseSubsystemPath("Catalog.Products")); //$NON-NLS-1$
        assertNull(SubsystemUtils.parseSubsystemPath("Document.SalesOrder")); //$NON-NLS-1$
        assertNull(SubsystemUtils.parseSubsystemPath("Role.FullAccess")); //$NON-NLS-1$
    }

    @Test
    public void testParseWrongTypeTokenInNestedSegment()
    {
        // Second segment has a wrong type token (Catalog instead of Subsystem)
        assertNull(SubsystemUtils.parseSubsystemPath("Subsystem.Sales.Catalog.Products")); //$NON-NLS-1$
    }

    @Test
    public void testParseOddNumberOfParts()
    {
        // "Subsystem.Sales.Subsystem" — name missing for the second segment
        assertNull(SubsystemUtils.parseSubsystemPath("Subsystem.Sales.Subsystem")); //$NON-NLS-1$
    }

    @Test
    public void testParseSingleToken()
    {
        assertNull(SubsystemUtils.parseSubsystemPath("Subsystem")); //$NON-NLS-1$
    }

    @Test
    public void testParseEmptyName()
    {
        // "Subsystem." or "Subsystem. " — empty name segment
        assertNull(SubsystemUtils.parseSubsystemPath("Subsystem.")); //$NON-NLS-1$
        assertNull(SubsystemUtils.parseSubsystemPath("Subsystem. ")); //$NON-NLS-1$
    }

    @Test
    public void testParseNullOrBlank()
    {
        assertNull(SubsystemUtils.parseSubsystemPath(null));
        assertNull(SubsystemUtils.parseSubsystemPath("")); //$NON-NLS-1$
        assertNull(SubsystemUtils.parseSubsystemPath("   ")); //$NON-NLS-1$
    }

    @Test
    public void testParseGarbage()
    {
        assertNull(SubsystemUtils.parseSubsystemPath("not a fqn at all")); //$NON-NLS-1$
        assertNull(SubsystemUtils.parseSubsystemPath(".Subsystem.Sales")); // leading dot //$NON-NLS-1$
    }

    // ========== getSynonymForLanguage ==========

    @Test
    public void testGetSynonymNullMap()
    {
        assertEquals("", SubsystemUtils.getSynonymForLanguage(null, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymEmptyMap()
    {
        EMap<String, String> empty = new BasicEMap<>();
        assertEquals("", SubsystemUtils.getSynonymForLanguage(empty, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymPreferredLanguage()
    {
        EMap<String, String> synonyms = new BasicEMap<>();
        synonyms.put("ru", "Продажи"); // Продажи //$NON-NLS-1$ //$NON-NLS-2$
        synonyms.put("en", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Продажи", SubsystemUtils.getSynonymForLanguage(synonyms, "ru")); // Продажи //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Sales", SubsystemUtils.getSynonymForLanguage(synonyms, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymFallbackWhenLanguageMissing()
    {
        // Map has only English; user asks for Russian — fallback returns English
        EMap<String, String> synonyms = new BasicEMap<>();
        synonyms.put("en", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Sales", SubsystemUtils.getSynonymForLanguage(synonyms, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymFallbackSkipsEmptyValues()
    {
        EMap<String, String> synonyms = new BasicEMap<>();
        synonyms.put("ru", ""); //$NON-NLS-1$ //$NON-NLS-2$
        synonyms.put("en", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$

        // ru is empty — should skip and return en
        assertEquals("Sales", SubsystemUtils.getSynonymForLanguage(synonyms, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymNullLanguageFallsBack()
    {
        EMap<String, String> synonyms = new BasicEMap<>();
        synonyms.put("en", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$

        // null language — skip preferred lookup, go to fallback
        assertEquals("Sales", SubsystemUtils.getSynonymForLanguage(synonyms, null)); //$NON-NLS-1$
    }

    @Test
    public void testGetSynonymEmptyLanguageFallsBack()
    {
        EMap<String, String> synonyms = new BasicEMap<>();
        synonyms.put("en", "Sales"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Sales", SubsystemUtils.getSynonymForLanguage(synonyms, "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymAllValuesEmpty()
    {
        EMap<String, String> synonyms = new BasicEMap<>();
        synonyms.put("ru", ""); //$NON-NLS-1$ //$NON-NLS-2$
        synonyms.put("en", ""); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("", SubsystemUtils.getSynonymForLanguage(synonyms, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ========== resolveLanguage ==========

    @Test
    public void testResolveLanguageExplicitWins()
    {
        // Explicit non-empty value is returned regardless of config
        assertEquals("en", SubsystemUtils.resolveLanguage("en", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testResolveLanguageNullConfigAndExplicit()
    {
        // No explicit, no config → null (caller falls back via getSynonymForLanguage)
        assertNull(SubsystemUtils.resolveLanguage(null, null));
        assertNull(SubsystemUtils.resolveLanguage("", null)); //$NON-NLS-1$
    }
}
