/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.w3c.dom.Element;

import com.ditrix.edt.mcp.server.utils.SecureXml;

/** Regressions: the existing path and registration helpers miss subsystem references. */
public class DeleteMetadataToolSubsystemRegistrationTest
{
    @Test
    public void testRegisteringFilesIncludeTheImmediateSubsystemParent()
    {
        assertEquals("src/Subsystems/A/A.mdo", DeleteMetadataTool.registeringFilePath("Subsystem.A"));
        assertEquals("src/Subsystems/A/Subsystems/B/B.mdo",
            DeleteMetadataTool.registeringFilePath("Subsystem.A.Subsystem.B"));
    }

    @Test
    public void testParentRegistrationTracksOnlyTheAddressedChild() throws Exception
    {
        Element parent = xml("<Subsystem><subsystems>Subsystem.A.Subsystem.B</subsystems>"
            + "<subsystems>Subsystem.A.Subsystem.C</subsystems></Subsystem>");
        assertTrue(DeleteMetadataTool.containsRegistration(parent, "Subsystem.A", "Subsystem.A.Subsystem.B"));
        Element after = xml("<Subsystem><subsystems>Subsystem.A.Subsystem.C</subsystems></Subsystem>");
        assertFalse(DeleteMetadataTool.containsRegistration(after, "Subsystem.A", "Subsystem.A.Subsystem.B"));
        assertTrue(DeleteMetadataTool.containsRegistration(after, "Subsystem.A", "Subsystem.A.Subsystem.C"));
    }

    private static Element xml(String text) throws Exception
    {
        return SecureXml.documentBuilderFactory().newDocumentBuilder()
            .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }
}
