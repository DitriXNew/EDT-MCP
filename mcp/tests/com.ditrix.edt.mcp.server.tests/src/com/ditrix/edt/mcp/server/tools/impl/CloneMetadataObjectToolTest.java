/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.MetadataCloneFilesFixture;
import com.ditrix.edt.mcp.server.utils.MetadataClonePlan;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class CloneMetadataObjectToolTest
{
    @Test
    public void confirmManifestAcceptsMetadataOnlySource() throws Exception
    {
        MetadataCloneFilesFixture fixture = new MetadataCloneFilesFixture();
        MetadataClonePlan preview = fixture.preview();
        assertEquals(preview.hash(), fixture.preview().hash());
        JsonArray manifest = CloneMetadataObjectTool.resourceManifest(preview, fixture.source, fixture.clone, fixture.fs);
        assertEquals(1, manifest.size());
        JsonObject root = manifest.get(0).getAsJsonObject();
        assertEquals("src/Catalogs/Copy/Copy.mdo", root.get("file").getAsString());
        assertTrue(root.get("modeled").getAsBoolean());
        assertEquals(fixture.clone.getUuid().toString(), root.get("uuid").getAsString());
    }

    @Test
    public void confirmManifestRequiresEveryPresentModule() throws Exception
    {
        MetadataCloneFilesFixture fixture = new MetadataCloneFilesFixture();
        when(fixture.objectModule.exists()).thenReturn(true);
        when(fixture.managerModule.exists()).thenReturn(true);
        MetadataClonePlan preview = fixture.preview();
        when(fixture.copiedObjectModule.exists()).thenReturn(true);
        when(fixture.copiedManagerModule.exists()).thenReturn(true);
        JsonArray manifest = CloneMetadataObjectTool.resourceManifest(preview, fixture.source, fixture.clone, fixture.fs);
        assertEquals(3, manifest.size());
        assertTrue(manifest.toString().contains("src/Catalogs/Copy/ObjectModule.bsl"));
        assertTrue(manifest.toString().contains("src/Catalogs/Copy/ManagerModule.bsl"));
        when(fixture.copiedManagerModule.exists()).thenReturn(false);
        try
        {
            CloneMetadataObjectTool.resourceManifest(preview, fixture.source, fixture.clone, fixture.fs);
            fail("An omitted source module must not pass confirmation");
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("omitted owned resource"));
            assertTrue(expected.getMessage().contains("managerModule"));
        }
    }

    @Test
    public void declaresPreviewProtocolAndNestedTransferSchema()
    {
        CloneMetadataObjectTool tool = new CloneMetadataObjectTool();
        assertEquals("clone_metadata_object", tool.getName());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertTrue(tool.getDescription().contains("without confirm"));
        assertTrue(tool.getDescription().contains("get_tool_guide('clone_metadata_object')"));
        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        assertEquals(2, schema.getAsJsonArray("required").size());
        assertEquals(6, schema.getAsJsonObject("properties").size());
        JsonObject item = schema.getAsJsonObject("properties").getAsJsonObject("transfer").getAsJsonObject("items");
        assertEquals(2, item.getAsJsonArray("required").size());
        assertFalse(item.get("additionalProperties").getAsBoolean());
        assertTrue(tool.getOutputSchema().contains("codeReferences"));
        assertTrue(tool.getOutputSchema().contains("writtenProjects"));
    }

    @Test
    public void missingArgumentsRefusedWithoutWorkbench()
    {
        String result = new CloneMetadataObjectTool().execute(Map.of());
        assertTrue(ToolResult.isErrorPayload(result));
        assertTrue(result.contains("projectName"));
    }

    @Test
    public void confirmRequiresHashBeforeProjectAccess()
    {
        String result = new CloneMetadataObjectTool().execute(Map.of("projectName", "NoProject",
            "fqn", "Catalog.Source", "confirm", "true"));
        assertTrue(ToolResult.isErrorPayload(result));
        assertTrue(result.contains("expectedHash"));
        assertTrue(result.contains("without confirm"));
    }

    @Test
    public void rejectsUnsupportedSourceBeforeWorkbench()
    {
        for (String fqn : new String[] {"Role.Source", "Configuration", "Catalog.Source.Attribute.X"})
        {
            String result = new CloneMetadataObjectTool().execute(Map.of("projectName", "NoProject", "fqn", fqn));
            assertTrue(result, ToolResult.isErrorPayload(result));
            assertTrue(result, result.contains("Catalog.Name"));
        }
    }

    @Test
    public void duplicateIdentityIsNotSuccessful()
    {
        MdObject source = mock(MdObject.class);
        MdObject clone = mock(MdObject.class);
        UUID uuid = UUID.randomUUID();
        when(source.getUuid()).thenReturn(uuid);
        when(clone.getUuid()).thenReturn(uuid);
        try
        {
            CloneMetadataObjectTool.validateClone(source, clone, null);
            fail("A shared UUID must fail the postcondition");
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("new identity"));
        }
    }

    @Test
    public void autoNameCannotLeakFromNamedClone()
    {
        MdObject source = mock(MdObject.class);
        MdObject clone = mock(MdObject.class);
        when(source.getUuid()).thenReturn(UUID.randomUUID());
        when(clone.getUuid()).thenReturn(UUID.randomUUID());
        when(clone.getName()).thenReturn("Source1");
        try
        {
            CloneMetadataObjectTool.validateClone(source, clone, "Requested");
            fail("An auto-name must fail the named-copy postcondition");
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("requested name"));
        }
    }
}
