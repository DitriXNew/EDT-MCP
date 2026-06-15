/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCommand;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogTabularSection;
import com._1c.g5.v8.dt.metadata.mdclass.FormType;
import com._1c.g5.v8.dt.metadata.mdclass.Indexing;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.TabularSectionAttribute;

/**
 * Tests the PURE formatting paths of {@link UniversalMetadataFormatter} - the universal,
 * EMF-reflection-driven markdown renderer - using only IN-MEMORY, NON-proxy EMF objects built
 * with {@link MdClassFactory} / {@link McoreFactory}. No live project, injector or service is
 * touched: a {@code Catalog} graph (attributes, a tabular section, a form, a command) and a
 * {@code TypeDescription} are constructed in memory and run through the public
 * {@link UniversalMetadataFormatter#format(com._1c.g5.v8.dt.metadata.mdclass.MdObject, boolean,
 * String)} entry point, which drives the (otherwise private) collection formatters.
 * <p>
 * The construction pattern mirrors {@link AbstractMetadataFormatterTest} and
 * {@code MetadataPropertyIntrospectorTest}; assertions match markdown substrings / exact rows
 * against the formatter's literal output. {@code McoreUtil.getTypeName} returns {@code getName()}
 * for a non-proxy in-memory {@link Type}, so type rendering needs no injector.
 * <p>
 * Methods that require a live service / Guice injector / {@code IV8ProjectManager} are NOT
 * exercised here - the formatter itself is service-free, so its full surface is reachable purely.
 */
public class UniversalMetadataFormatterTest
{
    /** The singleton under test (its collection formatters are private, reached via format()). */
    private final UniversalMetadataFormatter f = UniversalMetadataFormatter.getInstance();

    private static final String EN = "en"; //$NON-NLS-1$

    // ==================== helpers: in-memory builders ====================

    /** A non-proxy in-memory {@link Type} whose name is returned verbatim by McoreUtil.getTypeName. */
    private static Type newType(String name)
    {
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(name);
        return type;
    }

    /** A {@link TypeDescription} wrapping a single named primitive type. */
    private static TypeDescription typeOf(String name)
    {
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        td.getTypes().add(newType(name));
        return td;
    }

    /** A catalog attribute with a name, an "en" synonym and a single-type TypeDescription. */
    private static CatalogAttribute newAttribute(String name, String synonymEn, String typeName)
    {
        CatalogAttribute attr = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attr.setName(name);
        attr.getSynonym().put(EN, synonymEn);
        attr.setType(typeOf(typeName));
        return attr;
    }

    /** A tabular-section column (the element type of a tabular section's attributes list). */
    private static TabularSectionAttribute newTabularSectionAttribute(String name, String synonymEn,
        String typeName)
    {
        TabularSectionAttribute attr = MdClassFactory.eINSTANCE.createTabularSectionAttribute();
        attr.setName(name);
        attr.getSynonym().put(EN, synonymEn);
        attr.setType(typeOf(typeName));
        return attr;
    }

    // ==================== format() null / header guards ====================

    @Test
    public void testFormatNullReturnsErrorString()
    {
        // The null guard must return a stable error sentinel, never NPE.
        assertEquals("Error: MdObject is null", f.format(null, false, EN)); //$NON-NLS-1$
    }

    @Test
    public void testFormatEmptyCatalogRendersTypedMainHeaderAndBasicProperties()
    {
        // The main header uses the EMF eClass name as the type, and basic mode emits a
        // Basic Properties section with the object's Name row.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.setName("Products"); //$NON-NLS-1$
        cat.getSynonym().put(EN, "Goods"); //$NON-NLS-1$ //$NON-NLS-2$

        String md = f.format(cat, false, EN);
        assertTrue("main header must be '## Catalog: Products', got:\n" + md, //$NON-NLS-1$
            md.contains("## Catalog: Products")); //$NON-NLS-1$
        assertTrue("must have a Basic Properties section", md.contains("### Basic Properties")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the Name row must render", md.contains("| Name | Products |")); //$NON-NLS-1$
        assertTrue("the Synonym row must render the en value", md.contains("| Synonym | Goods |")); //$NON-NLS-1$
    }

    // ==================== formatAttributesCollection - compact (full=false) ====================

    @Test
    public void testCompactAttributesTableHasThreeColumnRowsWithTypeName()
    {
        // Basic mode renders each attribute as a compact Name | Synonym | Type row. The single
        // in-memory Type's name flows through formatType -> McoreUtil.getTypeName verbatim.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.setName("Cat"); //$NON-NLS-1$
        cat.getAttributes().add(newAttribute("Price", "Price", "Number")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        cat.getAttributes().add(newAttribute("Note", "Note", "String")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String md = f.format(cat, false, EN);
        // The containment collection becomes an "Attributes" section (formatFeatureName of "attributes").
        assertTrue("must have an Attributes section", md.contains("### Attributes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("compact header is Name | Synonym | Type", //$NON-NLS-1$
            md.contains("| Name | Synonym | Type |")); //$NON-NLS-1$
        assertTrue("compact table has a 3-col separator", md.contains("|---|---|---|\n")); //$NON-NLS-1$
        assertTrue("the Price row shows its type name", md.contains("| Price | Price | Number |")); //$NON-NLS-1$
        assertTrue("the Note row shows its type name", md.contains("| Note | Note | String |")); //$NON-NLS-1$
        // No extended columns in compact mode.
        assertFalse("compact mode must not emit the Indexing column", md.contains("Indexing")); //$NON-NLS-1$
    }

    // ==================== formatAttributesCollection - extended (full=true) ====================

    @Test
    public void testExtendedAttributesTableHasTenColumnHeaderAndIndexing()
    {
        // Full mode renders the 10-column extended attribute table; a CatalogAttribute is a
        // DbObjectAttribute, so the Indexing / Full Text Search cells come from its real enums.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.setName("Cat"); //$NON-NLS-1$
        CatalogAttribute attr = newAttribute("Price", "Price", "Number"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        attr.setIndexing(Indexing.INDEX);
        cat.getAttributes().add(attr);

        String md = f.format(cat, true, EN);
        assertTrue("extended header must list all 10 columns, got:\n" + md, //$NON-NLS-1$
            md.contains("| Name | Synonym | Type | Indexing | Fill Checking | Full Text Search | " //$NON-NLS-1$
                + "Password Mode | Multi Line | Quick Choice | Create On Input |")); //$NON-NLS-1$
        assertTrue("extended table has a 10-col separator", //$NON-NLS-1$
            md.contains("|---|---|---|---|---|---|---|---|---|---|\n")); //$NON-NLS-1$
        // Indexing was set to INDEX -> the EMF literal toString is "Index".
        assertTrue("the Indexing cell must reflect the set enum literal, got:\n" + md, //$NON-NLS-1$
            md.contains("| Price | Price | Number | Index |")); //$NON-NLS-1$
        // Password Mode / Multi Line are read by reflection and default to No for a flagless attribute.
        assertTrue("password / multi-line flags default to No", md.contains("| No | No |")); //$NON-NLS-1$
    }

    // ==================== formatTabularSectionsExtended ====================

    @Test
    public void testTabularSectionRendersSubHeaderPropertiesAndNestedAttributes()
    {
        // A tabular section is rendered with its own #### sub-header, a Property/Value table and a
        // nested "Attributes:" block for its columns.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.setName("Cat"); //$NON-NLS-1$
        CatalogTabularSection ts = MdClassFactory.eINSTANCE.createCatalogTabularSection();
        ts.setName("Lines"); //$NON-NLS-1$
        ts.getSynonym().put(EN, "Rows"); //$NON-NLS-1$ //$NON-NLS-2$
        ts.setComment("a comment"); //$NON-NLS-1$
        TabularSectionAttribute col = newTabularSectionAttribute("Qty", "Quantity", "Number"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ts.getAttributes().add(col);
        cat.getTabularSections().add(ts);

        String md = f.format(cat, false, EN);
        assertTrue("the tabular-sections collection must be a 'Tabular Sections' section", //$NON-NLS-1$
            md.contains("### Tabular Sections")); //$NON-NLS-1$
        assertTrue("the TS must have its own #### sub-header", md.contains("#### Lines")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the TS Name property row", md.contains("| Name | Lines |")); //$NON-NLS-1$
        assertTrue("the TS Synonym property row", md.contains("| Synonym | Rows |")); //$NON-NLS-1$
        assertTrue("the TS Comment property row", md.contains("| Comment | a comment |")); //$NON-NLS-1$
        assertTrue("the nested attributes block label", md.contains("**Attributes:**")); //$NON-NLS-1$
        assertTrue("the TS column renders as a compact attribute row", //$NON-NLS-1$
            md.contains("| Qty | Quantity | Number |")); //$NON-NLS-1$
    }

    // ==================== formatFormsCollection ====================

    @Test
    public void testFormsCollectionRendersNameSynonymFormType()
    {
        // A form collection (no adopted members -> no Origin column) renders Name | Synonym | Form Type.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.setName("Cat"); //$NON-NLS-1$
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("ListForm"); //$NON-NLS-1$
        form.getSynonym().put(EN, "List"); //$NON-NLS-1$ //$NON-NLS-2$
        form.setFormType(FormType.MANAGED);
        cat.getForms().add(form);

        String md = f.format(cat, false, EN);
        assertTrue("must have a Forms section", md.contains("### Forms")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a base form collection has no Origin column", //$NON-NLS-1$
            md.contains("| Name | Synonym | Form Type |")); //$NON-NLS-1$
        assertFalse("a base (non-adopted) collection must not add an Origin column", //$NON-NLS-1$
            md.contains("Origin")); //$NON-NLS-1$
        // FormType.MANAGED toStrings to the EMF literal "Managed".
        assertTrue("the form row must show name / synonym / form type, got:\n" + md, //$NON-NLS-1$
            md.contains("| ListForm | List | Managed |")); //$NON-NLS-1$
    }

    // ==================== formatCommandsCollection ====================

    @Test
    public void testCommandsCollectionRendersNameSynonymGroupDashWhenUnset()
    {
        // A command collection renders Name | Synonym | Group; an unset group -> a dash cell
        // (formatCommandGroup(null) -> DASH), with no service lookup.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.setName("Cat"); //$NON-NLS-1$
        CatalogCommand cmd = MdClassFactory.eINSTANCE.createCatalogCommand();
        cmd.setName("Refill"); //$NON-NLS-1$
        cmd.getSynonym().put(EN, "Refill"); //$NON-NLS-1$ //$NON-NLS-2$
        cat.getCommands().add(cmd);

        String md = f.format(cat, false, EN);
        assertTrue("must have a Commands section", md.contains("### Commands")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("commands header is Name | Synonym | Group", //$NON-NLS-1$
            md.contains("| Name | Synonym | Group |")); //$NON-NLS-1$
        assertTrue("an unset command group renders as a dash, got:\n" + md, //$NON-NLS-1$
            md.contains("| Refill | Refill | - |")); //$NON-NLS-1$
    }

    // ==================== formatType direct (single + composite + empty) ====================

    @Test
    public void testFormatTypeCompositeJoinsNamesAndEmptyIsDash()
    {
        // Direct formatType coverage: a multi-type description joins names by ", "; an empty one
        // (no types) and null both render as a dash.
        TypeDescription composite = McoreFactory.eINSTANCE.createTypeDescription();
        composite.getTypes().add(newType("String")); //$NON-NLS-1$
        composite.getTypes().add(newType("Number")); //$NON-NLS-1$
        assertEquals("String, Number", f.formatType(composite)); //$NON-NLS-1$

        assertEquals("-", f.formatType(McoreFactory.eINSTANCE.createTypeDescription())); //$NON-NLS-1$
        assertEquals("-", f.formatType(null)); //$NON-NLS-1$
    }

    // ==================== StyleItem value branches via format() ====================

    @Test
    public void testStyleItemWithNoValueRendersDashValueRow()
    {
        // A StyleItem with no Color/Font value set must still render a Value section, falling to the
        // else-branch: a "Value | -" row (no NPE on the null containment value).
        com._1c.g5.v8.dt.metadata.mdclass.StyleItem item =
            MdClassFactory.eINSTANCE.createStyleItem();
        item.setName("Empty"); //$NON-NLS-1$
        item.setType(com._1c.g5.v8.dt.metadata.mdclass.StyleElementType.COLOR);

        String md = f.format(item, false, EN);
        assertTrue("a StyleItem render must have a Value section", md.contains("### Value")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the Style Type row must show Color", md.contains("| Style Type | Color |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a value-less style item falls to the dash Value row, got:\n" + md, //$NON-NLS-1$
            md.contains("| Value | - |")); //$NON-NLS-1$
    }

    @Test
    public void testStyleItemColorValueRendersRgbThroughFormat()
    {
        // A StyleItem holding an explicit ColorValue renders the RGB triple in its Value section
        // (the single-valued containment value is only shown by the explicit StyleItem branch).
        com._1c.g5.v8.dt.metadata.mdclass.StyleItem item =
            MdClassFactory.eINSTANCE.createStyleItem();
        item.setName("Accent"); //$NON-NLS-1$
        item.setType(com._1c.g5.v8.dt.metadata.mdclass.StyleElementType.COLOR);
        com._1c.g5.v8.dt.mcore.ColorValue cv = McoreFactory.eINSTANCE.createColorValue();
        com._1c.g5.v8.dt.mcore.ColorDef def = McoreFactory.eINSTANCE.createColorDef();
        def.setRed(1);
        def.setGreen(2);
        def.setBlue(3);
        cv.setValue(def);
        item.setValue(cv);

        String md = f.format(item, false, EN);
        assertTrue("the Color row must show the RGB triple, got:\n" + md, //$NON-NLS-1$
            md.contains("| Color | RGB(1, 2, 3) |")); //$NON-NLS-1$
    }

    // ==================== full-mode dynamic dump (formatAllDynamicProperties) ====================

    @Test
    public void testFullModeEmitsAllPropertiesDumpSection()
    {
        // Full mode replaces Basic Properties with the reflective All Properties dump; the Name row
        // (a stored scalar) must appear there, proving the dynamic dump ran over the real eClass.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.setName("Products"); //$NON-NLS-1$

        String md = f.format(cat, true, EN);
        assertTrue("full mode must emit the All Properties section", //$NON-NLS-1$
            md.contains("### All Properties")); //$NON-NLS-1$
        assertTrue("the reflective dump must include the Name property row", //$NON-NLS-1$
            md.contains("| Name | Products |")); //$NON-NLS-1$
        assertFalse("full mode replaces the Basic Properties section", //$NON-NLS-1$
            md.contains("### Basic Properties")); //$NON-NLS-1$
    }

    // ==================== empty collections are silent ====================

    @Test
    public void testEmptyCollectionsProduceNoSectionHeaders()
    {
        // A catalog with no attributes / forms / commands must not emit those empty section
        // headers (the collection loop skips empty collections).
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.setName("Bare"); //$NON-NLS-1$

        String md = f.format(cat, false, EN);
        assertFalse("no Attributes section for an attribute-less catalog", //$NON-NLS-1$
            md.contains("### Attributes")); //$NON-NLS-1$
        assertFalse("no Forms section for a form-less catalog", md.contains("### Forms")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("no Commands section for a command-less catalog", //$NON-NLS-1$
            md.contains("### Commands")); //$NON-NLS-1$
    }
}
