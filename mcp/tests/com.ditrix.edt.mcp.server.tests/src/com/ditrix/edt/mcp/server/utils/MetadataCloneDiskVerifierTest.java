/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

public class MetadataCloneDiskVerifierTest
{
    @Test
    public void copiedFileBytesMustMatchAfterExport() throws Exception
    {
        var file = MetadataCloneFilesFixture.file("Copy/CommandModule.bsl", true, "// copied code");
        byte[] expected = "// copied code".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(expected, MetadataCloneDiskVerifier.verifyCopiedFile(file, MetadataClonePlan.bytesHash(expected)));
        try
        {
            MetadataCloneDiskVerifier.verifyCopiedFile(file, MetadataClonePlan.bytesHash(new byte[] {1}));
            fail("A readable but corrupt copied module must fail verification");
        }
        catch (IllegalStateException failure)
        {
            assertTrue(failure.getMessage().contains("Copied bytes differ"));
            assertTrue(failure.getMessage().contains("CommandModule.bsl"));
        }
    }
}
