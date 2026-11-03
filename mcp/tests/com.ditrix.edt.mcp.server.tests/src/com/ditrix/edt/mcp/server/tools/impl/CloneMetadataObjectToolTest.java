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
import com.ditrix.edt.mcp.server.utils.MetadataCloneDiskVerifier;
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
        JsonArray pending = CloneMetadataObjectTool.resourceManifest(preview, fixture.source, fixture.clone, fixture.fs);
        assertEquals("Destination paths must be planned even while files await export", 3, pending.size());
        try
        {
            MetadataCloneDiskVerifier.verifyCopiedFile(fixture.copiedManagerModule,
                preview.files.get("ManagerModule.bsl"));
            fail("A file still missing after export must not pass verification");
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("after export"));
            assertTrue(expected.getMessage().contains("ManagerModule.bsl"));
        }
    }

    @Test
    public void pendingExternalFormIsPlannedBeforeExportAndVerifiedAfterwards() throws Exception
    {
        MetadataCloneFilesFixture fixture = new MetadataCloneFilesFixture();
        var sourceForm = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCatalogForm();
        sourceForm.setName("ItemForm");
        fixture.source.getForms().add(sourceForm);
        var content = com._1c.g5.v8.dt.form.model.FormFactory.eINSTANCE.createForm();
        content.setMdForm(sourceForm);
        sourceForm.setForm(content);
        content.getAttributes().add(com._1c.g5.v8.dt.form.model.FormFactory.eINSTANCE.createFormAttribute());
        var sourceFile = MetadataCloneFilesFixture.file("Source/Forms/ItemForm/Form.form", true, "<Form/>");
        when(fixture.fs.getFile(content)).thenReturn(sourceFile);
        when(fixture.fs.getFile(sourceForm,
            com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.BASIC_FORM__FORM)).thenReturn(sourceFile);
        MetadataClonePlan preview = fixture.preview();

        var holder = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCatalog();
        var copiedForm = com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCatalogForm();
        copiedForm.setName("ItemForm");
        holder.getForms().add(copiedForm);
        when(fixture.clone.eGet(com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.CATALOG__FORMS))
            .thenReturn(holder.getForms());
        var copiedContent = mock(com._1c.g5.v8.dt.form.model.Form.class,
            withSettings().extraInterfaces(com._1c.g5.v8.bm.core.IBmObject.class));
        copiedForm.setForm(copiedContent);
        when(copiedContent.eClass()).thenReturn(com._1c.g5.v8.dt.form.model.FormPackage.Literals.FORM);
        when(copiedContent.eIsSet(any(org.eclipse.emf.ecore.EStructuralFeature.class))).thenReturn(true);
        var top = (com._1c.g5.v8.bm.core.IBmObject)copiedContent;
        when(top.bmIsTop()).thenReturn(true);
        when(top.bmGetFqn()).thenReturn("Catalog.Copy.Form.ItemForm.Form");
        var destination = MetadataCloneFilesFixture.file("Copy/Forms/ItemForm/Form.form", false, "<Form/>");
        when(fixture.fs.getFile(copiedContent)).thenReturn(destination);
        when(fixture.fs.getFile(copiedForm,
            com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.BASIC_FORM__FORM)).thenReturn(destination);

        JsonArray manifest = CloneMetadataObjectTool.resourceManifest(preview, fixture.source, fixture.clone, fixture.fs);
        JsonObject form = java.util.stream.StreamSupport.stream(manifest.spliterator(), false)
            .map(value -> value.getAsJsonObject()).filter(row -> row.get("file").getAsString().endsWith("Form.form"))
            .findFirst().orElseThrow();
        assertTrue(form.get("modeled").getAsBoolean());
        assertEquals("Catalog.Copy.Form.ItemForm.Form", form.get("modelFqn").getAsString());
        verify(destination, never()).getContents(true);
        try
        {
            MetadataCloneDiskVerifier.verifyCopiedFile(destination, null);
            fail("The export barrier cannot excuse a missing form file");
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("after export"));
            assertFalse(expected.getMessage().contains("source"));
        }
        when(destination.exists()).thenReturn(true);
        assertArrayEquals("<Form/>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            MetadataCloneDiskVerifier.verifyCopiedFile(destination, null));
    }

    @Test
    public void partialApplyUsesCloneRecoveryWordingWithoutDoublePeriod()
    {
        String response = CloneMetadataObjectTool.partial("Catalog.Copy", "Missing clone file 'Form.form' after export.");
        assertTrue(response.contains("Clone apply"));
        assertTrue(response.contains("Catalog.Copy"));
        assertTrue(response.contains("resync_to_disk"));
        assertFalse(response.contains(".."));
        assertFalse(response.contains("previewing again"));
        assertFalse(response.contains("refresh the source"));
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
