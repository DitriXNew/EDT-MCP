/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.emf.common.util.BasicEMap;
import org.eclipse.emf.common.util.EMap;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Tests for the protected helpers of {@link AbstractMetadataFormatter}, reached
 * through the concrete {@link UniversalMetadataFormatter} singleton (this test
 * lives in the same package, so protected members are accessible).
 * <p>
 * The load-bearing case is {@link AbstractMetadataFormatter#getSynonym} and the
 * "Synonym" row of {@code formatBasicProperties}: the synonym value must be
 * selected by the language <em>CODE</em> ("ru"/"en"), never by the language
 * NAME — this is the headless port of CLAUDE.md don't #2 / the e2e
 * {@code _assert_synonym_language_code} invariant. ASCII synonym values are
 * used deliberately: the contract is about the map KEY (code vs name), not
 * about the value being Cyrillic (so no {@code \\uXXXX} is needed here).
 */
public class AbstractMetadataFormatterTest
{
    /** Concrete subclass instance; protected helpers are reached via same-package access. */
    private final UniversalMetadataFormatter f = UniversalMetadataFormatter.getInstance();

    // ==================== getSynonym — the language-CODE contract ====================

    @Test
    public void testGetSynonymPicksValueByLanguageCode()
    {
        BasicEMap<String, String> syn = new BasicEMap<>();
        syn.put("en", "Catalog_EN"); //$NON-NLS-1$ //$NON-NLS-2$
        syn.put("ru", "Catalog_RU"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog_EN", f.getSynonym(syn, "en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog_RU", f.getSynonym(syn, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymUsesCodeKeyNotNameKey()
    {
        // Both a language-NAME key and a language-CODE key are present; the code
        // key must win (the don't #2 bug would surface as picking the name entry).
        BasicEMap<String, String> syn = new BasicEMap<>();
        syn.put("Russian", "WRONG_name_keyed"); //$NON-NLS-1$ //$NON-NLS-2$
        syn.put("ru", "RIGHT_code_keyed"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("RIGHT_code_keyed", f.getSynonym(syn, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymMissingCodeFallsBackToFirstNonEmpty()
    {
        BasicEMap<String, String> syn = new BasicEMap<>();
        syn.put("ru", "OnlyRu"); //$NON-NLS-1$ //$NON-NLS-2$
        // Code "de" is absent -> fall back to the first non-empty value, not an error.
        assertEquals("OnlyRu", f.getSynonym(syn, "de")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetSynonymNullMapReturnsEmptyNotNull()
    {
        assertEquals("", f.getSynonym((EMap<String, String>) null, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== formatBasicProperties — synonym row by code ====================

    @Test
    public void testFormatBasicPropertiesRendersNameAndCodeKeyedSynonym()
    {
        MdObject md = mock(MdObject.class);
        when(md.getName()).thenReturn("Products"); //$NON-NLS-1$ //$NON-NLS-2$
        when(md.getComment()).thenReturn(null);
        BasicEMap<String, String> syn = new BasicEMap<>();
        syn.put("en", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        syn.put("ru", "Tovary"); //$NON-NLS-1$ //$NON-NLS-2$
        when(md.getSynonym()).thenReturn(syn);

        StringBuilder en = new StringBuilder();
        f.formatBasicProperties(en, md, "en"); //$NON-NLS-1$
        String enOut = en.toString();
        assertTrue(enOut.contains("| Name | Products |")); //$NON-NLS-1$
        assertTrue(enOut.contains("| Synonym | Goods |")); //$NON-NLS-1$
        assertFalse(enOut.contains("Tovary")); //$NON-NLS-1$

        StringBuilder ru = new StringBuilder();
        f.formatBasicProperties(ru, md, "ru"); //$NON-NLS-1$
        assertTrue(ru.toString().contains("| Synonym | Tovary |")); //$NON-NLS-1$
    }

    // ==================== pure markdown/cell helpers ====================

    @Test
    public void testEscapeTableCellEscapesPipe()
    {
        assertEquals("a\\|b", f.escapeTableCell("a|b")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeTableCellReplacesNewlineWithSpace()
    {
        assertEquals("a b", f.escapeTableCell("a\nb")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEscapeTableCellNullReturnsDash()
    {
        assertEquals("-", f.escapeTableCell(null)); //$NON-NLS-1$
    }

    @Test
    public void testAddTableRowEscapesEachCell()
    {
        StringBuilder sb = new StringBuilder();
        f.addTableRow(sb, "x|y", "z"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("| x\\|y | z |\n", sb.toString()); //$NON-NLS-1$
    }

    @Test
    public void testStartTableEmitsHeaderAndSeparator()
    {
        StringBuilder sb = new StringBuilder();
        f.startTable(sb, "Property", "Value"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("| Property | Value |\n|---|---|\n", sb.toString()); //$NON-NLS-1$
    }

    @Test
    public void testFormatBoolean()
    {
        assertEquals("Yes", f.formatBoolean(true)); //$NON-NLS-1$
        assertEquals("No", f.formatBoolean(false)); //$NON-NLS-1$
    }

    @Test
    public void testFormatEnumNullReturnsDash()
    {
        assertEquals("-", f.formatEnum(null)); //$NON-NLS-1$
    }

    @Test
    public void testFormatEnumReturnsToString()
    {
        assertEquals("MONDAY", f.formatEnum(java.time.DayOfWeek.MONDAY)); //$NON-NLS-1$
    }
}
