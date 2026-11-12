/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;

/** Empty group fields are omitted by EDT; other explicit XML remains loss-checked. */
public class DcsEmptyGroupItemsRoundTripTest
{
    private static final String SCHEMA = "http://v8.1c.ru/8.1/data-composition-system/schema";
    private static final String SETTINGS = "http://v8.1c.ru/8.1/data-composition-system/settings";

    @Test
    public void testReporterShapePassesWhenEdtOmitsEmptyGroupItems()
    {
        assertNull(DcsXmlRoundTripComparator.firstMissingPath(
            reporterXml("<dcsset:groupItems/>"), reporterXml("")));
    }

    @Test
    public void testReporterSchemaPassesRealEdtRoundTrip() throws Exception
    {
        String submitted = reporterXml("<dcsset:groupItems/>");
        var parsed = DcsStoredXmlTestSupport.deserialize(submitted);
        var group = (DataCompositionGroup)parsed.getSettingsVariants().get(0).getSettings().getItems().get(0);
        assertTrue(group.getGroupFields().getItems().isEmpty());
        String serialized = DcsStoredXmlTestSupport.serialize(parsed);

        assertFalse(serialized, serialized.contains("groupItems"));
        assertTrue(serialized, serialized.contains("Поле"));
        assertNull(DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
        assertNull(DcsXmlRoundTripComparator.firstMissingPath(reporterXml(""), serialized));
    }

    @Test
    public void testNamespaceDeclarationsCommentsAndWhitespaceCarryNoGroupFields()
    {
        assertNull(DcsXmlRoundTripComparator.firstMissingPath(
            group("<dcsset:groupItems xmlns:unused='urn:unused'> \n<!-- empty -->\t</dcsset:groupItems>"),
            group("")));
    }

    @Test
    public void testRealEdtDroppingGroupItemsTextStillRefuses() throws Exception
    {
        String submitted = reporterXml("<dcsset:groupItems>Lost904</dcsset:groupItems>");
        String serialized = DcsStoredXmlTestSupport.serialize(DcsStoredXmlTestSupport.deserialize(submitted));
        assertFalse(serialized, serialized.contains("Lost904"));
        assertEquals("/DataCompositionSchema/settingsVariant/settings/item/groupItems",
            DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
    }

    @Test
    public void testGroupItemsWithAnyContentCannotDisappear()
    {
        for (String content : new String[] {
            "<dcsset:groupItems flag=''/>",
            "<dcsset:groupItems xsi:nil='true'/>",
            "<dcsset:groupItems xsi:nil='false'/>",
            "<dcsset:groupItems xsi:type='dcsset:Unknown'/>",
            "<dcsset:groupItems><dcsset:item/></dcsset:groupItems>",
            "<dcsset:groupItems>Amount</dcsset:groupItems>",
            "<dcsset:groupItems><![CDATA[Amount]]></dcsset:groupItems>"})
        {
            assertEquals(content, "/root/item/groupItems",
                DcsXmlRoundTripComparator.firstMissingPath(group(content), group("")));
        }
    }

    @Test
    public void testOmissionDoesNotHideSurvivingNilCounterpart()
    {
        assertEquals("/root/item/groupItems/@nil", DcsXmlRoundTripComparator.firstMissingPath(
            group("<dcsset:groupItems/>"), group("<dcsset:groupItems xsi:nil='true'/>")));
    }

    @Test
    public void testAnyContentFreeElementMayBeOmittedButNotOneWithContent()
    {
        for (String content : new String[] {"<dcsset:selection/>", "<dcsset:order/>",
            "<dcsset:unknown/>", "<groupItems/>", "<x:groupItems xmlns:x='urn:wrong'/>"})
        {
            assertNull(content, DcsXmlRoundTripComparator.firstMissingPath(group(content), group("")));
        }
        assertNull(DcsXmlRoundTripComparator.firstMissingPath("<root><groupItems/></root>", "<root/>"));
        assertEquals("/root/item/selection", DcsXmlRoundTripComparator.firstMissingPath(
            group("<dcsset:selection><dcsset:item/></dcsset:selection>"), group("")));
        assertNotNull(DcsXmlRoundTripComparator.firstMissingPath(
            group("<dcsset:unknown xsi:type='dcsset:X'/>"), group("")));
    }

    @Test
    public void testEmptySelectionAndOrderPassRealEdtRoundTrip() throws Exception
    {
        String submitted = reporterXml("<dcsset:groupItems/><dcsset:order/><dcsset:selection/>");
        // Replace the reporter's auto-filled containers with explicit empty containers.
        submitted = submitted.replace("<dcsset:order><dcsset:item xsi:type='dcsset:OrderItemAuto'/></dcsset:order>", "")
            .replace("<dcsset:selection><dcsset:item xsi:type='dcsset:SelectedItemAuto'/></dcsset:selection>", "");
        String serialized = DcsStoredXmlTestSupport.serialize(DcsStoredXmlTestSupport.deserialize(submitted));
        assertNull(serialized, DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
    }

    @Test
    public void testPrettyPrintedEmptyContainerMatchesButBlankValueStaysCompared()
    {
        assertNull(DcsXmlRoundTripComparator.firstMissingPath(
            group("<dcsset:order/>"), group("<dcsset:order>\n\t\t</dcsset:order>")));
        assertNotNull(DcsXmlRoundTripComparator.firstMissingPath(
            group("<dcsset:value> </dcsset:value>"), group("<dcsset:value/>")));
    }

    @Test
    public void testSingleSpaceValueCannotDisappear()
    {
        assertEquals("/root/value", DcsXmlRoundTripComparator.firstMissingPath(
            "<root><value> </value></root>", "<root/>"));
    }

    @Test
    public void testTypedNewlineValueCannotBecomeEmpty()
    {
        String value = "<value xmlns:xs='http://www.w3.org/2001/XMLSchema' xsi:type='xs:string'>"
            + "&#10;</value>";
        assertNotNull(DcsXmlRoundTripComparator.firstMissingPath(group(value),
            group(value.replace("&#10;", ""))));
        assertNull(DcsXmlRoundTripComparator.firstMissingPath(group(value),
            group(value.replace("&#10;", "\n"))));
    }

    @Test
    public void testSubmittedPrettyPrintedEmptyContainerMatchesDifferentIndentation()
    {
        String submitted = group("<dcsset:order>\n\t\t</dcsset:order>");
        assertNull(DcsXmlRoundTripComparator.firstMissingPath(submitted,
            group("<dcsset:order>\n    </dcsset:order>")));
        assertNull(DcsXmlRoundTripComparator.firstMissingPath(submitted, group("")));
    }

    @Test
    public void testIgnoredEmptySiblingDoesNotHideLostContentOrChangeItsPath()
    {
        assertEquals("/root/item/selection/item/field", DcsXmlRoundTripComparator.firstMissingPath(
            group("<dcsset:groupItems/><dcsset:selection><dcsset:item><dcsset:field>Lost</dcsset:field>"
                + "</dcsset:item></dcsset:selection>"),
            group("<dcsset:selection><dcsset:item/></dcsset:selection>")));
    }

    @Test
    public void testTableAndChartGroupingsMayOmitEmptyGroupItems()
    {
        for (String grouping : new String[] {"row", "column", "point", "series"})
        {
            String submitted = "<root xmlns:dcsset='" + SETTINGS + "'><dcsset:" + grouping
                + "><dcsset:groupItems/><dcsset:order/></dcsset:" + grouping + "></root>";
            String serialized = "<root xmlns:dcsset='" + SETTINGS + "'><dcsset:" + grouping
                + "><dcsset:order/></dcsset:" + grouping + "></root>";
            assertNull(grouping, DcsXmlRoundTripComparator.firstMissingPath(submitted, serialized));
            assertNotNull(grouping, DcsXmlRoundTripComparator.firstMissingPath(
                submitted.replace("<dcsset:groupItems/>",
                    "<dcsset:groupItems><dcsset:item/></dcsset:groupItems>"), serialized));
        }
    }

    private static String group(String content)
    {
        return "<root xmlns:dcsset='" + SETTINGS + "' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>"
            + "<dcsset:item xsi:type='dcsset:StructureItemGroup'>" + content + "</dcsset:item></root>";
    }

    private static String reporterXml(String groupItems)
    {
        return "<DataCompositionSchema xmlns='" + SCHEMA + "' xmlns:dcsset='" + SETTINGS
            + "' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>"
            + "<dataSource><name>ИсточникДанных1</name><dataSourceType>Local</dataSourceType></dataSource>"
            + "<dataSet xsi:type='DataSetQuery'><name>НаборДанных1</name>"
            + "<field xsi:type='DataSetFieldField'><dataPath>Поле</dataPath><field>Поле</field></field>"
            + "<dataSource>ИсточникДанных1</dataSource><query>ВЫБРАТЬ 1 КАК Поле</query></dataSet>"
            + "<settingsVariant><dcsset:name>Основной</dcsset:name><dcsset:settings>"
            + "<dcsset:selection><dcsset:item xsi:type='dcsset:SelectedItemField'>"
            + "<dcsset:field>Поле</dcsset:field></dcsset:item></dcsset:selection>"
            + "<dcsset:item xsi:type='dcsset:StructureItemGroup'>" + groupItems
            + "<dcsset:order><dcsset:item xsi:type='dcsset:OrderItemAuto'/></dcsset:order>"
            + "<dcsset:selection><dcsset:item xsi:type='dcsset:SelectedItemAuto'/></dcsset:selection>"
            + "</dcsset:item></dcsset:settings></settingsVariant></DataCompositionSchema>";
    }
}
