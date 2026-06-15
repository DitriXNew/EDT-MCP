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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.BasicEMap;
import org.eclipse.emf.common.util.EMap;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.StandardAttribute;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.TypeDescription;

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

    // ==================== formatType — TypeDescription rendering (S2) ====================

    @Test
    public void testFormatTypeNullDescriptionReturnsDash()
    {
        // A null TypeDescription (an unset attribute type) must render as a dash, never NPE.
        assertEquals("-", f.formatType(null)); //$NON-NLS-1$
    }

    @Test
    public void testFormatTypeEmptyDescriptionReturnsDash()
    {
        // A TypeDescription with no type items (e.g. a freshly-created, untyped attribute)
        // has nothing to name, so it renders as a dash rather than an empty string.
        com._1c.g5.v8.dt.mcore.TypeDescription td =
            com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createTypeDescription();
        assertEquals("-", f.formatType(td)); //$NON-NLS-1$
    }

    @Test
    public void testFormatTypeSingleTypeReturnsItsName()
    {
        // McoreUtil.getTypeName returns getName() directly for a non-proxy (in-memory) TypeItem,
        // so a single named Type renders as exactly that name.
        com._1c.g5.v8.dt.mcore.TypeDescription td =
            com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createTypeDescription();
        td.getTypes().add(newType("String")); //$NON-NLS-1$
        assertEquals("String", f.formatType(td)); //$NON-NLS-1$
    }

    @Test
    public void testFormatTypeMultipleTypesJoinedByComma()
    {
        // A composite type (several TypeItems) is rendered as the names joined by ", ",
        // in the order they appear in the description.
        com._1c.g5.v8.dt.mcore.TypeDescription td =
            com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createTypeDescription();
        td.getTypes().add(newType("String")); //$NON-NLS-1$
        td.getTypes().add(newType("Number")); //$NON-NLS-1$
        assertEquals("String, Number", f.formatType(td)); //$NON-NLS-1$
    }

    /** Creates an in-memory, non-proxy {@link com._1c.g5.v8.dt.mcore.Type} with the given name. */
    private static com._1c.g5.v8.dt.mcore.TypeItem newType(String name)
    {
        com._1c.g5.v8.dt.mcore.Type type = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createType();
        type.setName(name);
        return type;
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

    // ==================== addPropertyRow overloads ====================

    @Test
    public void testAddPropertyRowStringValue()
    {
        // The String overload prints the value as-is in the second cell.
        StringBuilder sb = new StringBuilder();
        f.addPropertyRow(sb, "Name", "Products"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("| Name | Products |\n", sb.toString()); //$NON-NLS-1$
    }

    @Test
    public void testAddPropertyRowNullStringValueRendersDash()
    {
        // A null String value falls back to the DASH placeholder, never a literal "null".
        StringBuilder sb = new StringBuilder();
        f.addPropertyRow(sb, "Comment", (String) null); //$NON-NLS-1$
        assertEquals("| Comment | - |\n", sb.toString()); //$NON-NLS-1$
    }

    @Test
    public void testAddPropertyRowBooleanValue()
    {
        // The boolean overload renders Yes / No via formatBoolean.
        StringBuilder sb = new StringBuilder();
        f.addPropertyRow(sb, "Indexed", true); //$NON-NLS-1$
        f.addPropertyRow(sb, "Indexed", false); //$NON-NLS-1$
        assertEquals("| Indexed | Yes |\n| Indexed | No |\n", sb.toString()); //$NON-NLS-1$
    }

    @Test
    public void testAddPropertyRowIntValue()
    {
        // The int overload renders the decimal string of the value.
        StringBuilder sb = new StringBuilder();
        f.addPropertyRow(sb, "Code Length", 9); //$NON-NLS-1$
        assertEquals("| Code Length | 9 |\n", sb.toString()); //$NON-NLS-1$
    }

    // ==================== header helpers ====================

    @Test
    public void testAddSectionHeaderFormatsLevel3()
    {
        StringBuilder sb = new StringBuilder();
        f.addSectionHeader(sb, "Basic Properties"); //$NON-NLS-1$
        assertEquals("\n### Basic Properties\n\n", sb.toString()); //$NON-NLS-1$
    }

    @Test
    public void testAddMainHeaderFormatsTypeAndName()
    {
        StringBuilder sb = new StringBuilder();
        f.addMainHeader(sb, "Catalog", "Products"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("## Catalog: Products\n\n", sb.toString()); //$NON-NLS-1$
    }

    // ==================== formatFeatureName — camelCase splitting ====================

    @Test
    public void testFormatFeatureNameSplitsCamelCaseAndCapitalizes()
    {
        // "codeLength" -> "Code Length": the leading char is upper-cased and a space is
        // inserted before every interior upper-case char.
        assertEquals("Code Length", f.formatFeatureName("codeLength")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatFeatureNameSingleWordJustCapitalizes()
    {
        assertEquals("Name", f.formatFeatureName("name")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatFeatureNameNullAndEmptyArePassedThrough()
    {
        assertEquals("", f.formatFeatureName("")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(null, f.formatFeatureName(null));
    }

    // ==================== formatEObjectReference ====================

    @Test
    public void testFormatEObjectReferenceNullReturnsDash()
    {
        assertEquals("-", f.formatEObjectReference(null)); //$NON-NLS-1$
    }

    @Test
    public void testFormatEObjectReferenceMdObjectAsTypeDotName()
    {
        // An MdObject reference renders as EClass.Name (e.g. "Catalog.Products").
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.setName("Products"); //$NON-NLS-1$
        assertEquals("Catalog.Products", f.formatEObjectReference(cat)); //$NON-NLS-1$
    }

    // ==================== formatDynamicValue — type dispatch ====================

    @Test
    public void testFormatDynamicValueNullReturnsDash()
    {
        assertEquals("-", f.formatDynamicValue(null, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatDynamicValueBoolean()
    {
        assertEquals("Yes", f.formatDynamicValue(Boolean.TRUE, "en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("No", f.formatDynamicValue(Boolean.FALSE, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatDynamicValueEnum()
    {
        // A raw Java enum is rendered via formatEnum (toString of the literal).
        assertEquals("MONDAY", f.formatDynamicValue(java.time.DayOfWeek.MONDAY, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatDynamicValueTypeDescription()
    {
        // A TypeDescription value is delegated to formatType (single named type -> its name).
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        td.getTypes().add(newType("String")); //$NON-NLS-1$
        assertEquals("String", f.formatDynamicValue(td, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatDynamicValueEMapPicksByLanguageCode()
    {
        // An EMap value (e.g. a synonym map) is resolved by language CODE, like getSynonym.
        BasicEMap<String, String> syn = new BasicEMap<>();
        syn.put("en", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        syn.put("ru", "Tovary"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Goods", f.formatDynamicValue(syn, "en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Tovary", f.formatDynamicValue(syn, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatDynamicValueMdObjectReference()
    {
        // An MdObject value renders as a Type.Name reference.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.setName("Partners"); //$NON-NLS-1$
        assertEquals("Catalog.Partners", f.formatDynamicValue(cat, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatDynamicValueEmptyCollectionReturnsDash()
    {
        assertEquals("-", f.formatDynamicValue(new ArrayList<>(), "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatDynamicValueSmallCollectionInlinedAsCommaList()
    {
        // A small (<= 5) collection of MdObjects is inlined as "Type.Name, Type.Name".
        Catalog a = MdClassFactory.eINSTANCE.createCatalog();
        a.setName("A"); //$NON-NLS-1$
        Catalog b = MdClassFactory.eINSTANCE.createCatalog();
        b.setName("B"); //$NON-NLS-1$
        List<MdObject> list = new ArrayList<>();
        list.add(a);
        list.add(b);
        assertEquals("Catalog.A, Catalog.B", f.formatDynamicValue(list, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatDynamicValueLargeCollectionShowsCountOnly()
    {
        // A collection larger than 5 is summarised as "[N items]" rather than inlined.
        List<MdObject> list = new ArrayList<>();
        for (int i = 0; i < 6; i++)
        {
            Catalog c = MdClassFactory.eINSTANCE.createCatalog();
            c.setName("C" + i); //$NON-NLS-1$
            list.add(c);
        }
        assertEquals("[6 items]", f.formatDynamicValue(list, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatDynamicValueStringFallsBackToToString()
    {
        // A plain non-EMF value falls through to toString().
        assertEquals("plain", f.formatDynamicValue("plain", "en")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ==================== formatReferenceCollection ====================

    @Test
    public void testFormatReferenceCollectionMdObjectsRenderFqnTable()
    {
        // A collection of MdObjects renders a section header with the count and an FQN/Synonym table.
        Catalog a = MdClassFactory.eINSTANCE.createCatalog();
        a.setName("Products"); //$NON-NLS-1$
        a.getSynonym().put("en", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        Catalog b = MdClassFactory.eINSTANCE.createCatalog();
        b.setName("Partners"); //$NON-NLS-1$
        List<MdObject> list = new ArrayList<>();
        list.add(a);
        list.add(b);

        StringBuilder sb = new StringBuilder();
        f.formatReferenceCollection(sb, "content", list, "en"); //$NON-NLS-1$ //$NON-NLS-2$
        String out = sb.toString();

        assertTrue("header must carry the camelCased name and count, got:\n" + out, //$NON-NLS-1$
            out.contains("### Content (2)")); //$NON-NLS-1$
        assertTrue("the FQN/Synonym table header must be present", out.contains("| FQN | Synonym |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("first MdObject row shows its FQN and synonym", //$NON-NLS-1$
            out.contains("| Catalog.Products | Goods |")); //$NON-NLS-1$
        // The second catalog has no synonym -> its synonym cell is empty.
        assertTrue("second MdObject row shows its FQN", out.contains("| Catalog.Partners |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatReferenceCollectionEMapEntriesRenderLanguageTable()
    {
        // A collection whose items are Map.Entry (an EMap like a synonym) renders a Language/Value table.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.getSynonym().put("en", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        cat.getSynonym().put("ru", "Tovary"); //$NON-NLS-1$ //$NON-NLS-2$

        StringBuilder sb = new StringBuilder();
        f.formatReferenceCollection(sb, "synonym", cat.getSynonym(), "en"); //$NON-NLS-1$ //$NON-NLS-2$
        String out = sb.toString();

        assertTrue("header must show the EMap entry count, got:\n" + out, //$NON-NLS-1$
            out.contains("### Synonym (2)")); //$NON-NLS-1$
        assertTrue("the Language/Value table header must be present", out.contains("| Language | Value |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an en entry row must be present", out.contains("| en | Goods |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a ru entry row must be present", out.contains("| ru | Tovary |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== formatStandardAttributes ====================

    @Test
    public void testFormatStandardAttributesEmitsTableForPopulatedList()
    {
        // A Catalog (a BasicDbObject) exposes getStandardAttributes(); a populated entry must produce
        // the StandardAttributes section with the attribute's name in a row.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        StandardAttribute attr = MdClassFactory.eINSTANCE.createStandardAttribute();
        attr.setName("Code"); //$NON-NLS-1$
        attr.getSynonym().put("en", "Code"); //$NON-NLS-1$ //$NON-NLS-2$
        cat.getStandardAttributes().add(attr);

        StringBuilder sb = new StringBuilder();
        f.formatStandardAttributes(sb, cat, "en"); //$NON-NLS-1$
        String out = sb.toString();

        assertTrue("a populated list must emit the StandardAttributes section, got:\n" + out, //$NON-NLS-1$
            out.contains("### StandardAttributes")); //$NON-NLS-1$
        assertTrue("the standard-attribute name must appear in a row", out.contains("| Code | Code |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatStandardAttributesEmptyListEmitsNothing()
    {
        // A Catalog with no standard attributes set must not emit the section at all.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        StringBuilder sb = new StringBuilder();
        f.formatStandardAttributes(sb, cat, "en"); //$NON-NLS-1$
        assertEquals("", sb.toString()); //$NON-NLS-1$
    }

    @Test
    public void testFormatStandardAttributesNoSuchMethodIsSilent()
    {
        // An object lacking getStandardAttributes() (a plain TypeDescription) is a no-op, not an error.
        StringBuilder sb = new StringBuilder();
        f.formatStandardAttributes(sb, McoreFactory.eINSTANCE.createTypeDescription(), "en"); //$NON-NLS-1$
        assertEquals("", sb.toString()); //$NON-NLS-1$
    }

    // ==================== formatDynamicPropertiesSeparated ====================

    @Test
    public void testFormatDynamicPropertiesSeparatedRendersSetScalarsAsProperties()
    {
        // Only features that are actually eIsSet are rendered; a Catalog with name + comment set
        // produces a Properties section carrying those scalar rows.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.setName("Products"); //$NON-NLS-1$
        cat.setComment("A catalog"); //$NON-NLS-1$

        StringBuilder sb = new StringBuilder();
        f.formatDynamicPropertiesSeparated(sb, cat, "en"); //$NON-NLS-1$
        String out = sb.toString();

        assertTrue("a Properties section must be present, got:\n" + out, out.contains("### Properties")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the set name scalar must be rendered", out.contains("| Name | Products |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the set comment scalar must be rendered", out.contains("| Comment | A catalog |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== formatAllDynamicProperties ====================

    @Test
    public void testFormatAllDynamicPropertiesEmitsTitledTable()
    {
        // The reflection dump emits the given section title, a Property/Value table, and a row for a
        // set scalar (Name). Containment children (attributes) are NOT dumped here.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.setName("Products"); //$NON-NLS-1$

        StringBuilder sb = new StringBuilder();
        f.formatAllDynamicProperties(sb, cat, "en", "All Properties"); //$NON-NLS-1$ //$NON-NLS-2$
        String out = sb.toString();

        assertTrue("the section title must be emitted, got:\n" + out, out.contains("### All Properties")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a Property/Value table must be opened", out.contains("| Property | Value |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the set Name scalar must appear", out.contains("| Name | Products |")); //$NON-NLS-1$ //$NON-NLS-2$
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
