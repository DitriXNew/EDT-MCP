/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.*;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import org.w3c.dom.Element;
import org.junit.Test;

public class DeleteMetadataToolExternalDataSourceTest
{
    @Test public void registeringFileForAFieldIsTheTablesOwnMdo()
    {
        assertEquals("src/ExternalDataSources/Eds/Tables/T/T.mdo", DeleteMetadataTool.registeringFilePath(TABLE));
        assertEquals("src/ExternalDataSources/Eds/Cubes/C/C.mdo", DeleteMetadataTool.registeringFilePath(CUBE));
    }

    @Test public void tableReferenceRegistrationIsStructurallyRecognized() throws Exception
    {
        assertTrue(DeleteMetadataTool.containsRegistration(xml("<ExternalDataSource><tables>" + TABLE
            + "</tables></ExternalDataSource>"), EDS, TABLE));
        assertFalse(DeleteMetadataTool.containsRegistration(xml("<ExternalDataSource/>"), EDS, TABLE));
    }

    @Test public void inlineFieldRegistrationIsRecognizedInTheTableFile() throws Exception
    {
        assertTrue(DeleteMetadataTool.containsRegistration(xml("<Table><tableFields><name>F</name>"
            + "</tableFields></Table>"), TABLE, FIELD));
        assertFalse(DeleteMetadataTool.containsRegistration(xml("<Table/>"), TABLE, FIELD));
    }

    @Test public void notFoundHintNamesTheEdsShapes()
    {
        String text = DeleteMetadataTool.nodeNotFound(TABLE + ".Missing");
        assertTrue(text, text.contains("ExternalDataSource.<X>.Table.<T>"));
    }

    @Test public void cubeMembersUseTheirRegisteringFileAndActualFieldFeature() throws Exception
    {
        assertEquals("src/ExternalDataSources/Eds/Cubes/C/DimensionTables/DT/DT.mdo",
            DeleteMetadataTool.registeringFilePath(DIMENSION_TABLE));
        assertTrue(DeleteMetadataTool.containsRegistration(xml("<Cube><dimensionTables>" + DIMENSION_TABLE
            + "</dimensionTables></Cube>"), CUBE, DIMENSION_TABLE));
        assertTrue(DeleteMetadataTool.containsRegistration(xml("<Cube><dimensions><name>D</name>"
            + "</dimensions></Cube>"), CUBE, DIMENSION));
        assertTrue(DeleteMetadataTool.containsRegistration(xml("<Cube><resources><name>R</name>"
            + "</resources></Cube>"), CUBE, RESOURCE));
        assertTrue(DeleteMetadataTool.containsRegistration(xml("<DimensionTable><fields><name>DF</name>"
            + "</fields></DimensionTable>"), DIMENSION_TABLE, DIMENSION_FIELD));
        assertFalse(DeleteMetadataTool.containsRegistration(xml("<DimensionTable><tableFields><name>DF</name>"
            + "</tableFields></DimensionTable>"), DIMENSION_TABLE, DIMENSION_FIELD));
        assertFalse(DeleteMetadataTool.containsRegistration(xml("<DimensionTable><fields><name>Sibling</name>"
            + "</fields></DimensionTable>"), DIMENSION_TABLE, DIMENSION_FIELD));
    }

    @Test public void russianDeleteUsesCanonicalRegistrationText() throws Exception
    {
        var f = new com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures();
        String requested = RU_EDS + ".Eds." + RU_TABLE + ".T";
        var node = com.ditrix.edt.mcp.server.utils.MetadataNodeResolver.resolveExisting(f.config, requested);
        assertNotNull(node);
        var method = DeleteMetadataTool.class.getDeclaredMethod("deleteTargetFqn",
            com.ditrix.edt.mcp.server.utils.MetadataNodeResolver.MetadataNode.class, String.class);
        method.setAccessible(true);
        String canonical = (String)method.invoke(null, node, requested);
        assertEquals(TABLE, canonical);
        assertTrue(DeleteMetadataTool.containsRegistration(xml("<ExternalDataSource><tables>" + TABLE
            + "</tables></ExternalDataSource>"), EDS, canonical));
    }

    private static Element xml(String text) throws Exception
    {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new InputSource(new StringReader(text))).getDocumentElement();
    }
}
