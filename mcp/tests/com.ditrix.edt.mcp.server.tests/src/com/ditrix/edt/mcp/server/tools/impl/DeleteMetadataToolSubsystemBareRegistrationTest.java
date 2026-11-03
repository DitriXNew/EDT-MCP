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
import java.util.Collections;
import java.util.Map;

import org.junit.Test;
import org.w3c.dom.Element;

import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.SecureXml;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A parent subsystem serializes its child references by bare programmatic Name. */
public class DeleteMetadataToolSubsystemBareRegistrationTest
{
    private static Element xml(String text) throws Exception
    {
        return SecureXml.documentBuilderFactory().newDocumentBuilder()
            .parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }

    @Test
    public void testBareNameRegistrationIsMatchedIgnoringCase() throws Exception
    {
        Element parent = xml("<Subsystem><subsystems> b </subsystems><subsystems>C</subsystems></Subsystem>");
        assertTrue(DeleteMetadataTool.containsRegistration(parent, "Subsystem.A", "Subsystem.A.Subsystem.B"));
        assertFalse(DeleteMetadataTool.containsRegistration(parent, "Subsystem.A", "Subsystem.A.Subsystem.D"));
    }

    @Test
    public void testDeepParentUsesTheImmediateChildsBareName() throws Exception
    {
        Element parent = xml("<Subsystem><subsystems>C</subsystems></Subsystem>");
        assertTrue(DeleteMetadataTool.containsRegistration(parent,
            "Subsystem.A.Subsystem.B", "Subsystem.A.Subsystem.B.Subsystem.C"));
    }

    @Test
    public void testRussianNameAndTypeMatchTheBareRegistration() throws Exception
    {
        Element parent = xml("<Subsystem><subsystems>\u0417\u0430\u043a\u0430\u0437</subsystems></Subsystem>");
        assertTrue(DeleteMetadataTool.containsRegistration(parent, "Subsystem.A",
            "Subsystem.A.\u041f\u043e\u0434\u0441\u0438\u0441\u0442\u0435\u043c\u0430.\u0437\u0430\u043a\u0430\u0437"));
    }

    @Test
    public void testStaleBareRegistrationMakesForcedDeletePartial() throws Exception
    {
        Element parent = xml("<Subsystem><subsystems>B</subsystems></Subsystem>");
        DeleteMetadataTool tool = new DeleteMetadataTool((name, preview) -> ConsentDecision.ALLOW,
            (name, timeout) -> null, (project, container) -> true, base -> Collections.emptyList(),
            (project, file, container, target) -> DeleteMetadataTool.containsRegistration(parent, container, target)
                ? DeleteMetadataTool.RegistrationState.PRESENT : DeleteMetadataTool.RegistrationState.ABSENT);
        String executed = "{\"success\":true,\"action\":\"executed\",\"forced\":true,"
            + "\"fqn\":\"Subsystem.A.Subsystem.B\",\"registeringContainer\":\"Subsystem.A\","
            + "\"registeringFile\":\"src/Subsystems/A/A.mdo\",\"message\":\"Deleted.\"}";
        String refreshed = tool.refreshAfterExportAwait(Map.of("projectName", "TestConfiguration"), executed, true);
        JsonObject result = JsonParser.parseString(refreshed).getAsJsonObject();
        assertTrue(refreshed, result.has("persisted"));
        assertFalse(refreshed, result.get("persisted").getAsBoolean());
        assertEquals("src/Subsystems/A/A.mdo", result.get("registeringFile").getAsString());
    }

    @Test
    public void testFullChainRegistrationRemainsTolerated() throws Exception
    {
        Element parent = xml("<Subsystem><subsystems>Subsystem.A.Subsystem.B</subsystems></Subsystem>");
        assertTrue(DeleteMetadataTool.containsRegistration(parent, "Subsystem.A", "Subsystem.A.Subsystem.B"));
    }

    @Test
    public void testBareNameToleranceDoesNotApplyToOtherFeatures() throws Exception
    {
        Element parent = xml("<Catalog><attributes>B</attributes></Catalog>");
        assertFalse(DeleteMetadataTool.containsRegistration(parent, "Catalog.A", "Catalog.A.Attribute.B"));
    }
}
