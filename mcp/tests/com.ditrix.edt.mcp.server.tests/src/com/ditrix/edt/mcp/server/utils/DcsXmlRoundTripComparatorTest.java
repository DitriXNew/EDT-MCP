/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests the pure asymmetric DCS XML round-trip loss detector. */
public class DcsXmlRoundTripComparatorTest
{
    @Test
    public void testAllowsDefaultsFormattingAttributeOrderAndNamespacePrefixChanges()
    {
        String submitted = "<d:DataCompositionSchema xmlns:d='urn:dcs' xmlns:cfg='urn:cfg' " //$NON-NLS-1$
            + "xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>" //$NON-NLS-1$
            + "<d:dataSet><d:type xsi:type='cfg:CatalogRef'>" //$NON-NLS-1$
            + "cfg:CatalogRef.Users</d:type></d:dataSet></d:DataCompositionSchema>"; //$NON-NLS-1$
        String serialized = "<q:DataCompositionSchema xmlns:q='urn:dcs' xmlns:p='urn:cfg' " //$NON-NLS-1$
            + "xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>\n" //$NON-NLS-1$
            + "  <q:autoFillFields>false</q:autoFillFields>\n" //$NON-NLS-1$
            + "  <q:dataSet added='true'><q:type other='x' xsi:type='p:CatalogRef'>\n" //$NON-NLS-1$
            + " p:CatalogRef.Users </q:type><q:addedDefault/></q:dataSet>\n" //$NON-NLS-1$
            + "</q:DataCompositionSchema>"; //$NON-NLS-1$

        assertNull(DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
    }

    @Test
    public void testAllowsRepeatedElementsToReorderWithoutGreedyFalseRefusal()
    {
        String submitted = "<root><item><name>A</name></item><item><name>B</name></item></root>"; //$NON-NLS-1$
        String serialized = "<root><item><name>B</name><default/></item>" //$NON-NLS-1$
            + "<item><name>A</name></item><added/></root>"; //$NON-NLS-1$

        assertNull(DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
    }

    @Test
    public void testFindsValueThatDeserializerTurnedIntoNil()
    {
        String submitted = "<d:root xmlns:d='urn:dcs' " //$NON-NLS-1$
            + "xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>" //$NON-NLS-1$
            + "<d:appearance><d:value xsi:type='style:StyleColor'>" //$NON-NLS-1$
            + "style:FieldErrorBackground</d:value></d:appearance></d:root>"; //$NON-NLS-1$
        String serialized = "<x:root xmlns:x='urn:dcs' " //$NON-NLS-1$
            + "xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>" //$NON-NLS-1$
            + "<x:appearance><x:value xsi:nil='true'/></x:appearance></x:root>"; //$NON-NLS-1$

        String missing = DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized);
        assertNotNull(missing);
        assertTrue(missing, missing.startsWith("/root/appearance/value")); //$NON-NLS-1$
    }

    @Test
    public void testFindsMissingRepeatedElementByStableIndex()
    {
        String submitted = "<root><item>A</item><item>B</item></root>"; //$NON-NLS-1$
        String serialized = "<root><item>A</item></root>"; //$NON-NLS-1$

        String missing = DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized);
        assertNotNull(missing);
        assertTrue(missing, missing.startsWith("/root/item[2]")); //$NON-NLS-1$
    }

    @Test
    public void testDeepAppearanceLossDoesNotBlameInnocentFilterSibling()
    {
        String submitted = "<DataCompositionSchema><settingsVariant><settings>" //$NON-NLS-1$
            + "<conditionalAppearance><item><filter><item><right>First</right></item></filter>" //$NON-NLS-1$
            + "<appearance><item><value>Kept</value></item></appearance></item>" //$NON-NLS-1$
            + "<item><filter><item><right>Innocent</right></item></filter>" //$NON-NLS-1$
            + "<appearance><item><value>Retyped</value></item></appearance></item>" //$NON-NLS-1$
            + "</conditionalAppearance></settings></settingsVariant></DataCompositionSchema>"; //$NON-NLS-1$
        String serialized = "<DataCompositionSchema><settingsVariant><settings>" //$NON-NLS-1$
            + "<conditionalAppearance><item><filter><item><right>First</right></item></filter>" //$NON-NLS-1$
            + "<appearance><item><value>Kept</value></item></appearance></item>" //$NON-NLS-1$
            + "<item><filter><item><right>Innocent</right></item></filter>" //$NON-NLS-1$
            + "<appearance><item/></appearance></item>" //$NON-NLS-1$
            + "</conditionalAppearance></settings></settingsVariant></DataCompositionSchema>"; //$NON-NLS-1$

        String missing = DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized);

        assertEquals("/DataCompositionSchema/settingsVariant/settings/conditionalAppearance/item[2]" //$NON-NLS-1$
            + "/appearance/item/value", missing); //$NON-NLS-1$
        assertFalse(missing, missing.contains("/filter/")); //$NON-NLS-1$
    }
}
