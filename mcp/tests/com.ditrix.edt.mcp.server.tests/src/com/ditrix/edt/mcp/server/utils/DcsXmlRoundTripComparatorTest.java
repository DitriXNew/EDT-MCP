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

import java.util.concurrent.TimeUnit;

import org.junit.Test;

/** Tests the pure asymmetric DCS XML round-trip loss detector. */
public class DcsXmlRoundTripComparatorTest
{
    @Test
    public void testEmptySubmittedChartMatchesGenuineEnrichedSerialization()
    {
        String submitted = "<DataCompositionSchema " //$NON-NLS-1$
            + "xmlns='http://v8.1c.ru/8.1/data-composition-system/schema' " //$NON-NLS-1$
            + "xmlns:dcsset='http://v8.1c.ru/8.1/data-composition-system/settings' " //$NON-NLS-1$
            + "xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'><defaultSettings>" //$NON-NLS-1$
            + "<dcsset:item xsi:type='dcsset:StructureItemChart'/>" //$NON-NLS-1$
            + "</defaultSettings></DataCompositionSchema>"; //$NON-NLS-1$
        // Shape copied from a real EDT chart serialization: order and auto-selection belong to
        // the point group; the chart also has its own selection, output parameters and view mode.
        String serialized = "<DataCompositionSchema " //$NON-NLS-1$
            + "xmlns='http://v8.1c.ru/8.1/data-composition-system/schema' " //$NON-NLS-1$
            + "xmlns:dcsset='http://v8.1c.ru/8.1/data-composition-system/settings' " //$NON-NLS-1$
            + "xmlns:dcscor='http://v8.1c.ru/8.1/data-composition-system/core' " //$NON-NLS-1$
            + "xmlns:v8ui='http://v8.1c.ru/8.1/data/ui' " //$NON-NLS-1$
            + "xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'><defaultSettings>" //$NON-NLS-1$
            + "<dcsset:item xsi:type='dcsset:StructureItemChart'><dcsset:point>" //$NON-NLS-1$
            + "<dcsset:order><dcsset:item xsi:type='dcsset:OrderItemAuto'/></dcsset:order>" //$NON-NLS-1$
            + "<dcsset:selection><dcsset:item xsi:type='dcsset:SelectedItemAuto'/>" //$NON-NLS-1$
            + "</dcsset:selection></dcsset:point><dcsset:selection>" //$NON-NLS-1$
            + "<dcsset:item xsi:type='dcsset:SelectedItemField'><dcsset:field>Amount</dcsset:field>" //$NON-NLS-1$
            + "</dcsset:item></dcsset:selection><dcsset:outputParameters>" //$NON-NLS-1$
            + "<dcscor:item xsi:type='dcsset:SettingsParameterValue'>" //$NON-NLS-1$
            + "<dcscor:parameter>ChartType</dcscor:parameter>" //$NON-NLS-1$
            + "<dcscor:value xsi:type='v8ui:ChartType'>Column3D</dcscor:value>" //$NON-NLS-1$
            + "</dcscor:item></dcsset:outputParameters><dcsset:viewMode>Normal</dcsset:viewMode>" //$NON-NLS-1$
            + "</dcsset:item></defaultSettings></DataCompositionSchema>"; //$NON-NLS-1$

        assertNull(DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
    }

    @Test
    public void testAllowsSubmittedElementToGainAttributes()
    {
        String submitted = "<root><item kind='chart'/></root>"; //$NON-NLS-1$
        String serialized = "<root generated='true'><item kind='chart' default='true' " //$NON-NLS-1$
            + "version='2'/></root>"; //$NON-NLS-1$

        assertNull(DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
    }

    @Test
    public void testAllowsMixedAugmentedAndExactSameNameSiblings()
    {
        String submitted = "<root><item key='A'/><item key='B'><value>B</value></item>" //$NON-NLS-1$
            + "<item key='C'/><item key='D'><value>D</value></item></root>"; //$NON-NLS-1$
        String serialized = "<root><item key='D'><value>D</value><default/></item>" //$NON-NLS-1$
            + "<item key='B'><value>B</value></item><item key='A'><default/></item>" //$NON-NLS-1$
            + "<item key='C'/></root>"; //$NON-NLS-1$

        assertNull(DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
    }

    @Test
    public void testAugmentedSiblingsDoNotHideGenuineRemovalPath()
    {
        String submitted = "<root><item id='kept'><selection><field>Kept</field></selection>" //$NON-NLS-1$
            + "</item><item id='lost'><selection><field>Missing</field></selection></item>" //$NON-NLS-1$
            + "<item id='chart'/></root>"; //$NON-NLS-1$
        String serialized = "<root><item id='chart'><viewMode>Auto</viewMode><order/></item>" //$NON-NLS-1$
            + "<item id='lost'><selection/></item><item id='kept'>" //$NON-NLS-1$
            + "<selection><field>Kept</field></selection></item></root>"; //$NON-NLS-1$

        assertEquals("/root/item[2]/selection/field", //$NON-NLS-1$
            DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
    }

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
            + "  <q:dataSet added='true'><q:type other='x' xsi:type='p:CatalogRef'>" //$NON-NLS-1$
            + "p:CatalogRef.Users</q:type><q:addedDefault/></q:dataSet>\n" //$NON-NLS-1$
            + "</q:DataCompositionSchema>"; //$NON-NLS-1$

        assertNull(DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
    }

    @Test
    public void testPreservesContentWhitespaceButIgnoresPrettyPrintingIndentation()
    {
        String submitted = "<root><query>SELECT 'a  b'</query><fields><field>A</field>" //$NON-NLS-1$
            + "<field>B</field></fields></root>"; //$NON-NLS-1$
        String prettyPrinted = "<root>\n  <query>SELECT 'a  b'</query>\n  <fields>\n" //$NON-NLS-1$
            + "    <field>A</field>\n    <field>B</field>\n  </fields>\n</root>"; //$NON-NLS-1$
        String changedQuery = "<root><query>SELECT 'a b'</query><fields><field>A</field>" //$NON-NLS-1$
            + "<field>B</field></fields></root>"; //$NON-NLS-1$

        assertNull(DcsXmlRoundTripComparator.firstMissingPath(submitted, prettyPrinted));
        assertEquals("/root/query", //$NON-NLS-1$
            DcsXmlRoundTripComparator.firstMissingPath(submitted, changedQuery));
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
    public void testFindsNewNilMarkerWhenSubmittedElementWasEmpty()
    {
        String submitted = "<root xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>" //$NON-NLS-1$
            + "<expression/></root>"; //$NON-NLS-1$
        String serialized = "<root xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>" //$NON-NLS-1$
            + "<expression xsi:nil='true'/></root>"; //$NON-NLS-1$

        assertNull(DcsXmlRoundTripComparator.firstMissingPath(submitted, submitted));
        assertEquals("/root/expression/@nil", //$NON-NLS-1$
            DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
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

    @Test
    public void testSubsetSiblingDoesNotStealRicherSurvivor()
    {
        String submitted = "<root><item><a/></item><item><a/><b/></item></root>"; //$NON-NLS-1$
        String serialized = "<root><item><a/><b/></item></root>"; //$NON-NLS-1$

        assertEquals("/root/item[1]", //$NON-NLS-1$
            DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
    }

    @Test(timeout = 15_000)
    public void testHundredThousandNearIdenticalNodesCompleteWithinTenSeconds()
    {
        int siblingCount = 12_000;
        StringBuilder submitted = new StringBuilder(2_500_000).append("<root>"); //$NON-NLS-1$
        StringBuilder serialized = new StringBuilder(2_700_000).append("<root>"); //$NON-NLS-1$
        for (int i = 0; i < siblingCount; i++) appendSyntheticItem(submitted, i, false);
        for (int i = siblingCount - 1; i >= 0; i--) appendSyntheticItem(serialized, i, true);
        submitted.append("</root>"); //$NON-NLS-1$
        serialized.append("</root>"); //$NON-NLS-1$

        long started = System.nanoTime();
        String missing = DcsXmlRoundTripComparator.firstMissingPath(submitted.toString(),
            serialized.toString());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        System.out.println("DCS XML 108,001-node submitted document comparison: " //$NON-NLS-1$
            + elapsedMs + " ms"); //$NON-NLS-1$
        assertNull(missing);
        assertTrue("108,001-node comparison took " + elapsedMs + " ms", elapsedMs < 10_000L); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void appendSyntheticItem(StringBuilder xml, int key, boolean addDefault)
    {
        xml.append("<item><key>").append(key).append("</key><a/><b/><c/><d/><e/><f/><g/>"); //$NON-NLS-1$
        if (addDefault) xml.append("<default/>"); //$NON-NLS-1$
        xml.append("</item>"); //$NON-NLS-1$
    }
}
