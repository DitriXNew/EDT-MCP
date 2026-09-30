/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.base;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.AdoptMetadataObjectTool;
import com.ditrix.edt.mcp.server.tools.impl.ApplyQuickFixTool;
import com.ditrix.edt.mcp.server.tools.impl.CreateMetadataTool;
import com.ditrix.edt.mcp.server.tools.impl.DeleteMetadataTool;
import com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataTool;
import com.ditrix.edt.mcp.server.tools.impl.ResyncToDiskTool;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard.Intent;

/**
 * Which metadata write tool is checked against vendor support, and as what (issue #642). The
 * create/modify/delete trio is guarded where the context is resolved; the others write into an
 * extension (adopt), apply EDT's own fix, or sweep orphans per role themselves.
 */
public class MetadataWriteIntentTest
{
    private static Intent intentOf(AbstractMetadataWriteTool tool)
    {
        return tool.writeIntent();
    }

    @Test
    public void testTheCrudToolsDeclareTheirIntent()
    {
        assertEquals(Intent.CREATE, intentOf(new CreateMetadataTool()));
        assertEquals(Intent.MODIFY, intentOf(new ModifyMetadataTool()));
        assertEquals(Intent.DELETE, intentOf(new DeleteMetadataTool()));
    }

    @Test
    public void testTheOtherWriteToolsAreNotCheckedAtContextResolution()
    {
        assertEquals(Intent.NONE, intentOf(new AdoptMetadataObjectTool()));
        assertEquals(Intent.NONE, intentOf(new ApplyQuickFixTool()));
        assertEquals(Intent.NONE, intentOf(new ResyncToDiskTool()));
    }
}
