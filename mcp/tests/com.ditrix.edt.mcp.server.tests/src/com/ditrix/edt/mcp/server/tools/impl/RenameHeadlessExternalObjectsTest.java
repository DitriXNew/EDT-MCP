/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.SearchFixtureProject;

/**
 * Issue #994: a headless rename confirms its write by exporting the top object that registers the
 * target. A configuration project exports {@code Configuration} and checks the registration in
 * {@code Configuration.mdo}; an external-objects project has no {@code Configuration} at all, so it
 * exports its own root object and the drained export is the confirmation - without that, every confirmed
 * rename there would answer "the model changed but Configuration.mdo does not confirm it".
 */
public class RenameHeadlessExternalObjectsTest
{
    /** "ВнешняяОбработка" (ExternalDataProcessor). */
    private static final String RU_EXTERNAL_DATA_PROCESSOR = cp(0x0412, 0x043d, 0x0435, 0x0448, 0x043d, 0x044f,
        0x044f, 0x041e, 0x0431, 0x0440, 0x0430, 0x0431, 0x043e, 0x0442, 0x043a, 0x0430);
    /** "Реквизит" (Attribute). */
    private static final String RU_ATTRIBUTE = cp(0x0420, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442);

    private static String cp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    @Test
    public void testAMalformedAddressIsRefusedAmongTheArgumentGuards()
    {
        // Before the settle and before a preview job is started: such an address can never resolve.
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("projectName", "NoSuchProject994"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectFqn", "Subsystem. Sales"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("newName", "Trade"); //$NON-NLS-1$ //$NON-NLS-2$
        // waitSeconds 0: a preview that got past the argument guards would answer Pending with a jobId.
        params.put("waitSeconds", "0"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new RenameMetadataObjectTool().execute(params);

        assertTrue(result, result.contains("padded segment")); //$NON-NLS-1$
        assertFalse("refused before any preview job is started", result.contains("jobId")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAConfigurationProjectExportsTheConfiguration()
    {
        assertEquals("Configuration", //$NON-NLS-1$
            RenameMetadataObjectTool.headlessExportFqn("Catalog.Goods.Attribute.Weight", "Mass", false, top -> top)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnExternalObjectsProjectExportsTheRootOfTheMember()
    {
        assertEquals("ExternalDataProcessor.Import", RenameMetadataObjectTool.headlessExportFqn( //$NON-NLS-1$
            "ExternalDataProcessor.Import.Attribute.Path", "Folder", true, top -> top)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ExternalDataProcessor.Import", RenameMetadataObjectTool.headlessExportFqn( //$NON-NLS-1$
            RU_EXTERNAL_DATA_PROCESSOR + ".Import." + RU_ATTRIBUTE + ".Path", "Folder", true, top -> top)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testARenamedRootIsExportedUnderItsNewName()
    {
        assertEquals("ExternalReport.Totals", //$NON-NLS-1$
            RenameMetadataObjectTool.headlessExportFqn("ExternalReport.Sums", "Totals", true, top -> "unused")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAMemberOfACaseDifferentRootIsExportedUnderTheStoredRootFqn()
    {
        // The address resolves the root's Name ignoring case; BM's top-object lookup does not.
        assertEquals("ExternalDataProcessor.Import", RenameMetadataObjectTool.headlessExportFqn( //$NON-NLS-1$
            "ExternalDataProcessor.import.Attribute.Path", "Folder", true, //$NON-NLS-1$ //$NON-NLS-2$
            top -> "ExternalDataProcessor.import".equals(top) ? "ExternalDataProcessor.Import" : "WRONG " + top)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testADrainedExternalExportConfirmsWithoutAConfigurationMdo() throws Exception    {
        try (SearchFixtureProject files = SearchFixtureProject.create())
        {
            assertEquals("the root's drained export is the confirmation", "success", //$NON-NLS-1$ //$NON-NLS-2$
                RenameMetadataObjectTool.finishHeadlessRename(files.name(), "ExternalDataProcessor.Import", //$NON-NLS-1$
                    "Loader", "success", true, true, true)); //$NON-NLS-1$ //$NON-NLS-2$
            String configurationPath = RenameMetadataObjectTool.finishHeadlessRename(files.name(),
                "ExternalDataProcessor.Import", "Loader", "success", true, true, false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue("control: the configuration path looks for Configuration.mdo and finds none",
                ToolResult.isErrorPayload(configurationPath));
        }
    }

    @Test
    public void testAnUnconfirmedExternalExportIsStillReportedAfterTheMutation()
    {
        String notQueued = RenameMetadataObjectTool.finishHeadlessRename("ExternalObjects", //$NON-NLS-1$
            "ExternalDataProcessor.Import", "Loader", "success", false, false, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String notDrained = RenameMetadataObjectTool.finishHeadlessRename("ExternalObjects", //$NON-NLS-1$
            "ExternalDataProcessor.Import", "Loader", "success", true, false, true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        for (String result : new String[] { notQueued, notDrained })
        {
            assertTrue(result, ToolResult.isErrorPayload(result));
            assertTrue(result, com.google.gson.JsonParser.parseString(result).getAsJsonObject()
                .get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
        }
    }
}
