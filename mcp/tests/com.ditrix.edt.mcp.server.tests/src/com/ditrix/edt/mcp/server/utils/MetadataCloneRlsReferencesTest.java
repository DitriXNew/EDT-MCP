/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

public class MetadataCloneRlsReferencesTest
{
    private static final String FILE = "src/Roles/Reader/Rights.rights";

    @Test
    public void onlyCopiedRowConditionsAreReportedAtPhysicalLines() throws Exception
    {
        String xml = String.join("\n",
            "<Rights xmlns=\"http://v8.1c.ru/8.2/roles\">",
            "<object><name>Catalog.Source</name><right><restrictionByCondition><condition>Catalog.Source</condition></restrictionByCondition></right></object>",
            "<object>",
            "<right><name>Read</name><restrictionByCondition><field>Catalog.Source</field><condition>",
            "WHERE Ref IN (SELECT Ref FROM Catalog.Source)",
            "</condition></restrictionByCondition></right>",
            "<name>Catalog.Clone</name></object>",
            "<object><name>Document.Other</name><right><restrictionByCondition><condition>Catalog.Source</condition></restrictionByCondition></right></object>",
            "<restrictionTemplate><condition>Catalog.Source</condition></restrictionTemplate>",
            "</Rights>");
        var refs = MetadataCloneRlsReferences.find(FILE, xml, "Catalog.Source", "Catalog.Clone");
        assertEquals(1, refs.size());
        assertEquals(FILE, refs.get(0).getAsJsonObject().get("file").getAsString());
        assertEquals(5, refs.get(0).getAsJsonObject().get("line").getAsInt());
    }

    @Test
    public void bomCrlfNamespacesEscapesAndCdataKeepPhysicalLineNumbers() throws Exception
    {
        String xml = "\ufeff" + String.join("\r\n",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<r:Rights xmlns:r=\"http://v8.1c.ru/8.2/roles\">",
            "<r:object><r:name>Catalog.Clone</r:name><r:right><r:restrictionByCondition><r:condition>",
            "Ref &lt;&gt; Catalog.Source AND Ref &lt;&gt; Catalog.Source",
            "<![CDATA[OR Ref = Catalog.Source]]>",
            "</r:condition></r:restrictionByCondition></r:right></r:object>",
            "</r:Rights>");
        var refs = MetadataCloneRlsReferences.find(FILE, xml, "Catalog.Source", "Catalog.Clone");
        assertEquals(2, refs.size());
        assertEquals(4, refs.get(0).getAsJsonObject().get("line").getAsInt());
        assertEquals(5, refs.get(1).getAsJsonObject().get("line").getAsInt());
    }

    @Test
    public void sourceRowAndFieldNamesOnTheSameLineDoNotCreateFalseReferences() throws Exception
    {
        String xml = "<Rights><object><name>Catalog.Source</name><right><restrictionByCondition>"
            + "<condition>Catalog.Source</condition></restrictionByCondition></right></object>"
            + "<object><name>Catalog.Clone</name><right><restrictionByCondition><field>Catalog.Source</field>"
            + "<condition>Catalog.SourceOther</condition></restrictionByCondition></right></object></Rights>";
        assertEquals(0, MetadataCloneRlsReferences.find(FILE, xml, "Catalog.Source", "Catalog.Clone").size());
    }

    @Test
    public void missingCopiedRowIsNotReportedAsACompleteEmptyScan() throws Exception
    {
        try
        {
            MetadataCloneRlsReferences.find(FILE, "<Rights/>", "Catalog.Source", "Catalog.Clone");
            fail("A missing selected rights row must be diagnosed");
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("Catalog.Clone"));
            assertTrue(expected.getMessage().contains(FILE));
        }
    }

    @Test
    public void doctypeAndExternalEntitiesAreRefused() throws Exception
    {
        try
        {
            MetadataCloneRlsReferences.find(FILE,
                "<!DOCTYPE Rights [<!ENTITY payload SYSTEM 'file:///not-readable'>]><Rights>&payload;</Rights>",
                "Catalog.Source", "Catalog.Clone");
            fail("The scanner must never follow external XML resources");
        }
        catch (javax.xml.stream.XMLStreamException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("DOCTYPE"));
        }
    }
}
