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

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Tests for {@link EObjectInspector}.
 * <p>
 * Covers the branches that short-circuit before any EClass feature-list walk and
 * are therefore exercisable with bare Mockito mocks of the EMF/EDT interfaces:
 * the {@code MdObject} reference ("Type.Name"), the {@code ReferenceValue}
 * unwrap, {@code getCleanClassName} (Impl stripping), the null/MdObject paths of
 * {@code getFormatStyle}, and {@code isMdObjectReference}. The generic
 * value/expand branches reflect over {@code getEAllAttributes()}/
 * {@code getEAllReferences()} and are covered by the E2E suite against a real
 * EMF model.
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
}
