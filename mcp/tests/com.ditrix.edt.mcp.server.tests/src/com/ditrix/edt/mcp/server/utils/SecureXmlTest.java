/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;

/**
 * The hardening is the whole point of the helper, so every switch is asserted through BEHAVIOUR
 * (what the parser does with hostile input), not by reading the flags back.
 */
public class SecureXmlTest
{
    private static Document parse(String xml) throws Exception
    {
        DocumentBuilder builder = SecureXml.documentBuilderFactory().newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testParsesOrdinaryXmlWithNamespaces() throws Exception
    {
        Document document = parse("<mdo:Report xmlns:mdo=\"http://g5.1c.ru/v8/dt/metadata/mdo\">"
            + "<name>Report1</name></mdo:Report>");
        assertEquals("the document element must keep its qualified name", "mdo:Report",
            document.getDocumentElement().getNodeName());
        assertEquals("namespace awareness must be on", "http://g5.1c.ru/v8/dt/metadata/mdo",
            document.getDocumentElement().getNamespaceURI());
    }

    @Test
    public void testDoctypeDeclarationIsRejected()
    {
        // The XXE entry point: a file the server merely READS must not be able to declare a DOCTYPE,
        // because that is what an entity - external or billion-laughs - is declared inside.
        try
        {
            parse("<!DOCTYPE root [<!ENTITY x \"expanded\">]><root>&x;</root>");
            fail("a DOCTYPE declaration must be refused outright");
        }
        catch (Exception e)
        {
            String message = e.getMessage() == null ? "" : e.getMessage();
            assertTrue("the refusal must name DOCTYPE rather than fail for some unrelated reason: "
                + message, message.toLowerCase().contains("doctype"));
        }
    }

    @Test
    public void testEachCallGetsItsOwnFactory() throws Exception
    {
        // Callers configure the factory further (and DocumentBuilderFactory is not thread-safe), so
        // handing out one shared instance would let one call site's settings leak into another's.
        DocumentBuilderFactory first = SecureXml.documentBuilderFactory();
        DocumentBuilderFactory second = SecureXml.documentBuilderFactory();
        assertNotSame("every call must return a fresh factory", first, second);
    }
}
