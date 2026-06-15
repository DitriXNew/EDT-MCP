/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.CommandGroupCategory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Tests for {@link EObjectInspector}.
 * <p>
 * The first group covers the branches that short-circuit before any EClass
 * feature-list walk, exercisable with bare Mockito mocks of the EMF/EDT
 * interfaces: the {@code MdObject} reference ("Type.Name"), the
 * {@code ReferenceValue} unwrap, {@code getCleanClassName} (Impl stripping), the
 * null/MdObject paths of {@code getFormatStyle}, and {@code isMdObjectReference}.
 * <p>
 * The second group (issue #171) covers the generic value/expand branches that
 * reflect over {@code getEAllAttributes()}/{@code getEAllReferences()} —
 * {@code getPrimaryValue}'s priority cascade (enum / name / class-name fallback),
 * {@code countMeaningfulAttributes}, {@code hasNonEmptyContainment},
 * {@code isSimpleValueHolder} and the {@code getFormatStyle}
 * SIMPLE_VALUE/EXPAND outcomes — using small, in-memory, NON-proxy EObjects built
 * from the EMF factories ({@link MdClassFactory}/{@link McoreFactory}). No live
 * project, injector, workspace or Mockito is involved, so these run headlessly
 * under Tycho Surefire.
 */
public class EObjectInspectorTest
{
    private static MdObject mockMdObject(String typeName, String name)
    {
        EClass ec = mock(EClass.class);
        when(ec.getName()).thenReturn(typeName);
        MdObject md = mock(MdObject.class);
        when(md.eClass()).thenReturn(ec);
        when(md.getName()).thenReturn(name);
        return md;
    }

    // ==================== formatReference ====================

    @Test
    public void testFormatReferenceNullReturnsEmpty()
    {
        assertEquals("", EObjectInspector.formatReference(null)); //$NON-NLS-1$
    }

    @Test
    public void testFormatReferenceMdObjectShowsTypeDotName()
    {
        MdObject md = mockMdObject("Catalog", "Products"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalog.Products", EObjectInspector.formatReference(md)); //$NON-NLS-1$
    }

    @Test
    public void testFormatReferenceUnwrapsReferenceValue()
    {
        MdObject inner = mockMdObject("Document", "Invoice"); //$NON-NLS-1$ //$NON-NLS-2$
        ReferenceValue rv = mock(ReferenceValue.class);
        when(rv.getValue()).thenReturn(inner);
        assertEquals("Document.Invoice", EObjectInspector.formatReference(rv)); //$NON-NLS-1$
    }

    @Test
    public void testFormatReferenceReferenceValueWithNullInnerReturnsEmpty()
    {
        ReferenceValue rv = mock(ReferenceValue.class);
        when(rv.getValue()).thenReturn(null);
        assertEquals("", EObjectInspector.formatReference(rv)); //$NON-NLS-1$
    }

    // ==================== getCleanClassName ====================

    @Test
    public void testGetCleanClassNameStripsImplSuffix()
    {
        EClass ec = mock(EClass.class);
        when(ec.getName()).thenReturn("CatalogImpl"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject e = mock(EObject.class);
        when(e.eClass()).thenReturn(ec);
        assertEquals("Catalog", EObjectInspector.getCleanClassName(e)); //$NON-NLS-1$
    }

    @Test
    public void testGetCleanClassNameWithoutSuffixUnchanged()
    {
        EClass ec = mock(EClass.class);
        when(ec.getName()).thenReturn("Color"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject e = mock(EObject.class);
        when(e.eClass()).thenReturn(ec);
        assertEquals("Color", EObjectInspector.getCleanClassName(e)); //$NON-NLS-1$
    }

    @Test
    public void testGetCleanClassNameNullReturnsEmpty()
    {
        assertEquals("", EObjectInspector.getCleanClassName(null)); //$NON-NLS-1$
    }

    // ==================== getFormatStyle / isMdObjectReference ====================

    @Test
    public void testGetFormatStyleNullReturnsSimpleValue()
    {
        assertEquals(EObjectInspector.FormatStyle.SIMPLE_VALUE, EObjectInspector.getFormatStyle(null));
    }

    @Test
    public void testGetFormatStyleMdObjectReturnsReference()
    {
        MdObject md = mock(MdObject.class);
        assertEquals(EObjectInspector.FormatStyle.REFERENCE, EObjectInspector.getFormatStyle(md));
    }

    @Test
    public void testIsMdObjectReference()
    {
        assertTrue(EObjectInspector.isMdObjectReference(mock(MdObject.class)));
        assertFalse(EObjectInspector.isMdObjectReference(mock(EObject.class)));
    }

    // ====================================================================================
    // In-memory EMF coverage (issue #171): the reflective branches that walk
    // getEAllAttributes()/getEAllReferences() — exercised with real, non-proxy EObjects
    // built from the EMF factories (no live project / injector / Mockito), so the priority
    // cascade of getPrimaryValue and the containment / class-name paths are covered
    // headlessly under Tycho Surefire.
    // ====================================================================================

    /** A StandardCommandGroup is the canonical "simple wrapper" whose category enum is the value. */
    private static StandardCommandGroup commandGroup(CommandGroupCategory category)
    {
        StandardCommandGroup g = McoreFactory.eINSTANCE.createStandardCommandGroup();
        g.setCategory(category);
        return g;
    }

    // -------------------- getPrimaryValue: priority 1 (enum attribute) --------------------

    @Test
    public void testGetPrimaryValueReturnsEnumAttributeFirst()
    {
        // StandardCommandGroup.category is an enum attribute -> highest-priority branch.
        StandardCommandGroup g = commandGroup(CommandGroupCategory.FORM_COMMAND_BAR);
        Object value = EObjectInspector.getPrimaryValue(g);
        assertSame("the category enum literal must be returned verbatim", //$NON-NLS-1$
            CommandGroupCategory.FORM_COMMAND_BAR, value);
        assertTrue("an EMF EEnum literal must be a real java enum", value.getClass().isEnum()); //$NON-NLS-1$
    }

    @Test
    public void testGetPrimaryValueAsStringRendersEnumLiteral()
    {
        StandardCommandGroup g = commandGroup(CommandGroupCategory.NAVIGATION_PANEL);
        assertEquals(CommandGroupCategory.NAVIGATION_PANEL.toString(),
            EObjectInspector.getPrimaryValueAsString(g));
    }

    @Test
    public void testGetPrimaryValueNullReturnsNull()
    {
        assertNull(EObjectInspector.getPrimaryValue(null));
    }

    @Test
    public void testGetPrimaryValueAsStringNullReturnsEmpty()
    {
        assertEquals("", EObjectInspector.getPrimaryValueAsString(null)); //$NON-NLS-1$
    }

    // -------------------- getPrimaryValue: priority 3 (name feature) --------------------

    @Test
    public void testGetPrimaryValueFallsBackToNameFeature()
    {
        // A mcore Parameter has NO enum attribute, and none of its category/group/type/value
        // features are set (its 'type' list is empty on a fresh object), but it carries a 'name'
        // feature -> the name branch (priority 3) wins over the lower priorities.
        Parameter p = McoreFactory.eINSTANCE.createParameter();
        p.setName("Source"); //$NON-NLS-1$
        assertEquals("Source", EObjectInspector.getPrimaryValue(p)); //$NON-NLS-1$
    }

    // -------------------- getPrimaryValue: priority 5 (class-name fallback) --------------------

    @Test
    public void testGetPrimaryValueClassNameFallbackWhenNothingSet()
    {
        // A freshly-created Parameter has nothing set: no enum value, no category/group/type/value,
        // an unset name, no other set attribute -> the cascade falls through to the EClass name.
        Parameter p = McoreFactory.eINSTANCE.createParameter();
        assertEquals("Parameter", EObjectInspector.getPrimaryValue(p)); //$NON-NLS-1$
    }

    // -------------------- countMeaningfulAttributes --------------------

    @Test
    public void testCountMeaningfulAttributesNullIsZero()
    {
        assertEquals(0, EObjectInspector.countMeaningfulAttributes(null));
    }

    @Test
    public void testCountMeaningfulAttributesCountsSetAttribute()
    {
        // priority is unset on a fresh group; setting it must make it count as a meaningful attribute.
        StandardCommandGroup before = McoreFactory.eINSTANCE.createStandardCommandGroup();
        int baseline = EObjectInspector.countMeaningfulAttributes(before);

        StandardCommandGroup after = McoreFactory.eINSTANCE.createStandardCommandGroup();
        after.setPriority(7);
        int withPriority = EObjectInspector.countMeaningfulAttributes(after);

        assertTrue("setting an attribute must increase the meaningful-attribute count, baseline=" //$NON-NLS-1$
            + baseline + " withPriority=" + withPriority, withPriority > baseline); //$NON-NLS-1$
    }

    // -------------------- hasNonEmptyContainment --------------------

    @Test
    public void testHasNonEmptyContainmentNullIsFalse()
    {
        assertFalse(EObjectInspector.hasNonEmptyContainment(null));
    }

    @Test
    public void testHasNonEmptyContainmentFalseForEmptyObject()
    {
        // A bare Catalog owns no attributes/forms yet -> no non-empty containment.
        Catalog empty = MdClassFactory.eINSTANCE.createCatalog();
        assertFalse(EObjectInspector.hasNonEmptyContainment(empty));
    }

    @Test
    public void testHasNonEmptyContainmentTrueWhenChildAdded()
    {
        // Catalog.attributes is a containment list; adding a child makes containment non-empty.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        cat.getAttributes().add(MdClassFactory.eINSTANCE.createCatalogAttribute());
        assertTrue(EObjectInspector.hasNonEmptyContainment(cat));
    }

    // -------------------- isSimpleValueHolder(EObject) --------------------

    @Test
    public void testIsSimpleValueHolderTrueForCommandGroup()
    {
        // No containment references and only a couple of attributes -> a simple value holder.
        assertTrue(EObjectInspector.isSimpleValueHolder(commandGroup(CommandGroupCategory.ACTIONS_PANEL)));
    }

    @Test
    public void testIsSimpleValueHolderFalseForObjectWithContainment()
    {
        // A Catalog owns containment references (attributes/forms/...) -> never a simple holder.
        Catalog cat = MdClassFactory.eINSTANCE.createCatalog();
        assertFalse(EObjectInspector.isSimpleValueHolder(cat));
    }

    @Test
    public void testIsSimpleValueHolderNullEObjectIsFalse()
    {
        assertFalse(EObjectInspector.isSimpleValueHolder((EObject) null));
    }

    @Test
    public void testIsSimpleValueHolderNullEClassIsFalse()
    {
        assertFalse(EObjectInspector.isSimpleValueHolder((EClass) null));
    }

    // -------------------- getFormatStyle (reflective branches) --------------------

    @Test
    public void testGetFormatStyleSimpleValueForFewAttributes()
    {
        // Not an MdObject, no containment, only the single category attribute set
        // (<= MAX_ATTRIBUTES_FOR_SIMPLE_VALUE) -> SIMPLE_VALUE.
        assertEquals(EObjectInspector.FormatStyle.SIMPLE_VALUE,
            EObjectInspector.getFormatStyle(commandGroup(CommandGroupCategory.FORM_NAVIGATION_PANEL)));
    }

    @Test
    public void testGetFormatStyleExpandWhenContainmentPresent()
    {
        // ColorValue is NOT an MdObject and its 'value' is a containment reference. With a ColorDef
        // contained, the non-empty-containment branch (3) of getFormatStyle wins -> EXPAND, before any
        // attribute counting.
        ColorValue cv = McoreFactory.eINSTANCE.createColorValue();
        ColorDef def = McoreFactory.eINSTANCE.createColorDef();
        def.setRed(1);
        cv.setValue(def);
        assertEquals(EObjectInspector.FormatStyle.EXPAND, EObjectInspector.getFormatStyle(cv));
    }

    // -------------------- getCleanClassName / formatReference on a real EObject --------------------

    @Test
    public void testGetCleanClassNameOnRealEObjectReturnsModelName()
    {
        // On a real EObject getCleanClassName reads eClass().getName(), which is already the
        // un-suffixed model name "Type" (the Impl suffix lives only on the Java class, not the
        // EClass) -> the clean name path returns "Type" without any stripping needed.
        Type t = McoreFactory.eINSTANCE.createType();
        assertEquals("Type", EObjectInspector.getCleanClassName(t)); //$NON-NLS-1$
    }

    @Test
    public void testFormatReferenceSimpleHolderShowsPrimaryValue()
    {
        // A simple value holder formats to its primary (enum) value, not a Type.Name string.
        StandardCommandGroup g = commandGroup(CommandGroupCategory.FORM_COMMAND_BAR);
        assertEquals(CommandGroupCategory.FORM_COMMAND_BAR.toString(),
            EObjectInspector.formatReference(g));
    }
}
