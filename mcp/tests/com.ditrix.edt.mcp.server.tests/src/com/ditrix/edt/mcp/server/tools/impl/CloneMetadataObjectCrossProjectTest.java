/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.junit.Test;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MetadataCloneDestination;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Issue #933 PR-3: the request contract and the postcondition of a clone into another project. */
public class CloneMetadataObjectCrossProjectTest
{
    private static final List<JsonObject> NO_TRANSFER = List.of();

    @Test
    public void aDifferentTargetIsCrossProjectAndTheSameNameIsTodaysClone()
    {
        assertTrue(CloneMetadataObjectTool.crossProject("Base", "Base.ext"));
        assertFalse("The same project behaves exactly as without the parameter",
            CloneMetadataObjectTool.crossProject("Base", "Base"));
        assertFalse(CloneMetadataObjectTool.crossProject("Base", null));
        assertFalse(CloneMetadataObjectTool.crossProject("Base", " "));
    }

    @Test
    public void aBlankTargetAndACrossProjectTransferAreRefusedBeforeTheWorkbench()
    {
        String blank = CloneMetadataObjectTool.targetRequestError("Base", true, " ", NO_TRANSFER);
        assertNotNull(blank);
        assertTrue(blank, blank.contains("targetProjectName is blank"));
        assertTrue(blank, blank.contains("list_projects"));
        assertNull(CloneMetadataObjectTool.targetRequestError("Base", false, null, NO_TRANSFER));
        assertNull(CloneMetadataObjectTool.targetRequestError("Base", true, "Other", NO_TRANSFER));

        JsonObject selection = JsonParser.parseString("{\"kind\":\"subsystem\",\"owner\":\"Subsystem.S\"}")
            .getAsJsonObject();
        String transfer = CloneMetadataObjectTool.targetRequestError("Base", true, "Other", List.of(selection));
        assertNotNull(transfer);
        assertTrue(transfer, transfer.contains("transfer is not supported with targetProjectName 'Other'"));
        assertTrue(transfer, transfer.contains("modify_metadata"));
        assertNull("Inside one project transfer keeps working",
            CloneMetadataObjectTool.targetRequestError("Base", true, "Base", List.of(selection)));

        String viaExecute = new CloneMetadataObjectTool().execute(Map.of("projectName", "NoProject",
            "fqn", "Catalog.Source", "targetProjectName", "NoTarget",
            "transfer", "[{\"kind\":\"subsystem\",\"owner\":\"Subsystem.S\"}]"));
        assertTrue(viaExecute, ToolResult.isErrorPayload(viaExecute));
        assertTrue(viaExecute, viaExecute.contains("transfer is not supported"));
        String blankViaExecute = new CloneMetadataObjectTool().execute(Map.of("projectName", "NoProject",
            "fqn", "Catalog.Source", "targetProjectName", ""));
        assertTrue(blankViaExecute, blankViaExecute.contains("targetProjectName is blank"));
    }

    @Test
    public void inAnotherProjectNoReferenceMayStayInTheSourceNamespace()
    {
        URI sourceAddress = BmUriUtil.createTopBmObjectUri("Dst", "Catalog.Source");
        URI member = BmUriUtil.createContainedBmObjectUri("Dst", "Catalog.Source", "/attributes/A");
        URI leftBehind = BmUriUtil.createTopBmObjectUri("Src", "Catalog.Other");
        URI elsewhere = BmUriUtil.createTopBmObjectUri("Dst", "Catalog.Other");
        // Same project: the source's own address is the original; the namespace is not asked.
        assertTrue(CloneMetadataObjectTool.targetsSource(sourceAddress, "Catalog.Source", null, false));
        assertTrue(CloneMetadataObjectTool.targetsSource(member, "Catalog.Source", null, true));
        assertFalse(CloneMetadataObjectTool.targetsSource(leftBehind, "Catalog.Source", null, false));
        // Another project: a clone that kept the name owns that address there.
        assertFalse(CloneMetadataObjectTool.targetsSource(sourceAddress, "Catalog.Source", "Src", true));
        assertFalse(CloneMetadataObjectTool.targetsSource(member, "Catalog.Source", "Src", true));
        assertTrue("A renamed clone must not keep the source address",
            CloneMetadataObjectTool.targetsSource(member, "Catalog.Source", "Src", false));
        assertTrue("Nothing may stay in the source project",
            CloneMetadataObjectTool.targetsSource(leftBehind, "Catalog.Source", "Src", true));
        assertFalse(CloneMetadataObjectTool.targetsSource(elsewhere, "Catalog.Source", "Src", false));
    }

    @Test
    public void aMissingReferenceRefusalCarriesTheCompleteList()
    {
        MetadataCloneDestination.MissingReferences refusal = missing(List.of("AccumulationRegister.Stock",
            "Catalog.Units"));
        String result = CloneMetadataObjectTool.previewError("Document.Order", new IllegalStateException(refusal));
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        assertFalse(json.get("success").getAsBoolean());
        assertEquals(2, json.getAsJsonArray("missingReferences").size());
        assertEquals("AccumulationRegister.Stock", json.getAsJsonArray("missingReferences").get(0).getAsString());
        assertTrue(result, json.get("error").getAsString().startsWith("Cannot clone 'Document.Order': "));
        assertFalse("A missing reference is final, not a stale source", result.contains("resync_to_disk"));
    }

    private static MetadataCloneDestination.MissingReferences missing(List<String> references)
    {
        return new MetadataCloneDestination.MissingReferences("the clone would reference " + references, references);
    }
}
