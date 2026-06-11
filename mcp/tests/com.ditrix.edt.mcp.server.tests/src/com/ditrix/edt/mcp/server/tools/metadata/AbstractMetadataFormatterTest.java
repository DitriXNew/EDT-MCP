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

    // ==================== StyleItem value rendering ====================

    @Test
    public void testFormatStyleItemRendersExplicitColorValue()
    {
        // A StyleItem with a ColorValue must render a "Value" section showing the Style Type and the
        // RGB color (the value is a single-valued containment ref, so only this explicit branch shows it).
        com._1c.g5.v8.dt.metadata.mdclass.StyleItem item =
            com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createStyleItem();
        item.setName("MyColor"); //$NON-NLS-1$
        item.setType(com._1c.g5.v8.dt.metadata.mdclass.StyleElementType.COLOR);
        com._1c.g5.v8.dt.mcore.ColorValue cv =
            com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createColorValue();
        com._1c.g5.v8.dt.mcore.ColorDef def =
            com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createColorDef();
        def.setRed(255);
        def.setGreen(0);
        def.setBlue(0);
        cv.setValue(def);
        item.setValue(cv);

        String out = f.format(item, false, "en"); //$NON-NLS-1$
        assertTrue("a StyleItem render must have a Value section", out.contains("### Value")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the Style Type row must show Color", out.contains("| Style Type | Color |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the Color row must show the RGB value, got:\n" + out, //$NON-NLS-1$
            out.contains("| Color | RGB(255, 0, 0) |")); //$NON-NLS-1$
    }

    @Test
    public void testFormatStyleItemRendersAutoColorNotRgb()
    {
        // The AutoColor-first ordering through the formatter: an automatic color renders as "Auto",
        // never as "RGB(0, 0, 0)" (AutoColor extends ColorDef).
        com._1c.g5.v8.dt.metadata.mdclass.StyleItem item =
            com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createStyleItem();
        item.setName("AutoColor"); //$NON-NLS-1$
        item.setType(com._1c.g5.v8.dt.metadata.mdclass.StyleElementType.COLOR);
        com._1c.g5.v8.dt.mcore.ColorValue cv =
            com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createColorValue();
        cv.setValue(com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createAutoColor());
        item.setValue(cv);

        String out = f.format(item, false, "en"); //$NON-NLS-1$
        assertTrue("an automatic color must render as Auto, got:\n" + out, //$NON-NLS-1$
            out.contains("| Color | Auto |")); //$NON-NLS-1$
        assertFalse("an automatic color must NOT render as RGB(0, 0, 0)", //$NON-NLS-1$
            out.contains("RGB(0, 0, 0)")); //$NON-NLS-1$
    }

    @Test
    public void testFormatStyleItemRendersFontValue()
    {
        com._1c.g5.v8.dt.metadata.mdclass.StyleItem item =
            com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createStyleItem();
        item.setName("MyFont"); //$NON-NLS-1$
        item.setType(com._1c.g5.v8.dt.metadata.mdclass.StyleElementType.FONT);
        com._1c.g5.v8.dt.mcore.FontValue fv =
            com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createFontValue();
        com._1c.g5.v8.dt.mcore.FontDef def =
            com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createFontDef();
        def.setFaceName("Arial"); //$NON-NLS-1$
        def.setHeight(12f);
        def.setBold(true);
        fv.setValue(def);
        item.setValue(fv);

        String out = f.format(item, false, "en"); //$NON-NLS-1$
        assertTrue("the Style Type row must show Font", out.contains("| Style Type | Font |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the Font row must show face / height / bold, got:\n" + out, //$NON-NLS-1$
            out.contains("face='Arial'") && out.contains("height=12") && out.contains("bold")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
    public void testEscapeTableCellStripsCarriageReturn()
    {
        // A CRLF value must not leave a stray '\r' (which would break the table row);
        // the '\n' becomes a space and the '\r' is removed entirely.
        String out = f.escapeTableCell("a\r\nb"); //$NON-NLS-1$
        assertEquals("a b", out); //$NON-NLS-1$
        assertFalse(out.contains("\r")); //$NON-NLS-1$
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

    // ==================== section/depth cap on the reflection dump ====================

    @Test
    public void testMaxDynamicRowsIsBounded()
    {
        // The reflection dumps (formatAllDynamicProperties / formatReferenceCollection)
        // are capped per section so a heavily-referenced object cannot produce an
        // unbounded dump even below the global output guard.
        assertTrue("the per-section row cap must be a small positive bound",
            AbstractMetadataFormatter.MAX_DYNAMIC_ROWS > 0
                && AbstractMetadataFormatter.MAX_DYNAMIC_ROWS <= 1000);
    }

    @Test
    public void testAppendTruncatedRowIsActionableAndTableSafe()
    {
        // The section-truncation notice row must be a single, table-safe row that
        // tells the agent how many rows were omitted, the cap, and how to see more.
        StringBuilder sb = new StringBuilder();
        f.appendTruncatedRow(sb, 37, "references"); //$NON-NLS-1$
        String row = sb.toString();

        assertTrue("must be a single markdown table row",
            row.startsWith("| ") && row.endsWith(" |\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must report the omitted count", row.contains("37")); //$NON-NLS-1$
        assertTrue("must name the omitted unit", row.contains("references")); //$NON-NLS-1$
        assertTrue("must report the cap", //$NON-NLS-1$
            row.contains(String.valueOf(AbstractMetadataFormatter.MAX_DYNAMIC_ROWS)));
        assertTrue("must point to a narrowing action", row.contains("fqn")); //$NON-NLS-1$
        // Exactly one row: a lone trailing newline, no embedded ones that would
        // split the table.
        assertEquals("the notice must be exactly one row", 1, countChar(row, '\n'));
    }

    private static int countChar(String s, char c)
    {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
        {
            if (s.charAt(i) == c)
            {
                n++;
            }
        }
        return n;
    }
}
