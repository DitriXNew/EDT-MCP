/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.BasicEMap;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Tests for {@link MetadataFormatterRegistry} (the static facade) and the
 * end-to-end basic-mode rendering of {@link UniversalMetadataFormatter}.
 * <p>
 * The end-to-end test stubs only {@code eClass().getName()} and an empty
 * {@code getEAllStructuralFeatures()} (so the containment loop is a no-op and the
 * reflective {@code getStandardAttributes()} probe throws
 * {@code NoSuchMethodException} and is swallowed), then asserts the synonym row
 * is rendered by language CODE through the public {@code format(...)} path. The
 * full ({@code full=true}) dynamic-reflection path needs a real EMF model and is
 * covered by the E2E suite.
 */
public class MetadataFormatterRegistryTest
{
    @Test
    public void testGetFormatterReturnsUniversalSingleton()
    {
        IMetadataFormatter formatter = MetadataFormatterRegistry.getFormatter();
        assertNotNull(formatter);
        assertSame(formatter, MetadataFormatterRegistry.getFormatter());
        assertEquals("*", formatter.getMetadataType()); //$NON-NLS-1$
    }

    @Test
    public void testCanFormatRejectsNullAcceptsMdObject()
    {
        IMetadataFormatter formatter = MetadataFormatterRegistry.getFormatter();
        assertFalse(formatter.canFormat(null));
        assertTrue(formatter.canFormat(mock(MdObject.class)));
    }

    @Test
    public void testFormatNullReturnsErrorString()
    {
        assertEquals("Error: MdObject is null", MetadataFormatterRegistry.format(null, false, "ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Error: MdObject is null", MetadataFormatterRegistry.format(null, true, "en")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFormatBasicRendersHeaderAndCodeKeyedSynonym()
    {
        EClass ec = mock(EClass.class);
        when(ec.getName()).thenReturn("Catalog"); //$NON-NLS-1$ //$NON-NLS-2$
        when(ec.getEAllStructuralFeatures()).thenReturn(new BasicEList<EStructuralFeature>());

        MdObject md = mock(MdObject.class);
        when(md.eClass()).thenReturn(ec);
        when(md.getName()).thenReturn("Products"); //$NON-NLS-1$ //$NON-NLS-2$
        when(md.getComment()).thenReturn(null);
        BasicEMap<String, String> syn = new BasicEMap<>();
        syn.put("en", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        syn.put("ru", "Tovary"); //$NON-NLS-1$ //$NON-NLS-2$
        when(md.getSynonym()).thenReturn(syn);

        String enOut = MetadataFormatterRegistry.format(md, false, "en"); //$NON-NLS-1$
        assertTrue(enOut.contains("## Catalog: Products")); //$NON-NLS-1$
        assertTrue(enOut.contains("| Synonym | Goods |")); //$NON-NLS-1$
        assertFalse(enOut.contains("Tovary")); //$NON-NLS-1$

        String ruOut = MetadataFormatterRegistry.format(md, false, "ru"); //$NON-NLS-1$
        assertTrue(ruOut.contains("| Synonym | Tovary |")); //$NON-NLS-1$
    }
}
