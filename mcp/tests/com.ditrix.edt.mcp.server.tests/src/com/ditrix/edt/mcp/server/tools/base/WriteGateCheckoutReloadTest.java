/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * #922 (Codex P1 on #928): the writers on the STRICT gate ({@code delete_metadata}, {@code apply_quick_fix}) are
 * refused after this server's branch switch exactly like the writers on the model gate.
 */
public class WriteGateCheckoutReloadTest
{
    private static final String RELOAD = "reload"; //$NON-NLS-1$

    @Test
    public void aStrictWriterAsksTheCheckoutReloadFirst()
    {
        assertEquals(RELOAD, AbstractMetadataWriteTool.writeGateError(true, () -> RELOAD, () -> null, () -> "model")); //$NON-NLS-1$
    }

    @Test
    public void aStrictWriterWithoutAReloadUsesTheStrictGate()
    {
        assertEquals("strict", AbstractMetadataWriteTool.writeGateError(true, () -> null, () -> "strict", //$NON-NLS-1$ //$NON-NLS-2$
            () -> "model")); //$NON-NLS-1$
        assertNull(AbstractMetadataWriteTool.writeGateError(true, () -> null, () -> null, () -> "model")); //$NON-NLS-1$
    }

    /** The model gate carries the reload check itself; it is not asked twice. */
    @Test
    public void aModelGateWriterUsesTheModelGateOnly()
    {
        assertEquals("model", AbstractMetadataWriteTool.writeGateError(false, () -> { //$NON-NLS-1$
            throw new AssertionError("asked twice"); //$NON-NLS-1$
        }, () -> "strict", () -> "model")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Codex round 5 on #928: the reload is asked again on the UI thread, right before the write - a branch change
     * that registered while the call waited for the UI thread refuses the write instead of being written into.
     */
    @Test
    public void aReloadRegisteredWhileWaitingForTheUiThreadRefusesTheWrite() throws Exception
    {
        String refused = AbstractMetadataWriteTool.recheckedOnUiThread(() -> RELOAD, () -> {
            throw new AssertionError("written into a reloading model"); //$NON-NLS-1$
        });
        assertTrue(refused, refused.contains(RELOAD) && refused.contains("\"success\":false")); //$NON-NLS-1$

        assertEquals("written", AbstractMetadataWriteTool.recheckedOnUiThread(() -> null, () -> "written")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The write tools' shared execute path makes that UI-thread re-check, for the project the call names. */
    @Test
    public void theUiThreadWorkReChecksTheReload() throws Exception
    {
        java.io.File dir = new java.io.File(System.getProperty("user.dir")); //$NON-NLS-1$
        java.io.File source = null;
        for (int i = 0; i < 12 && dir != null && source == null; i++)
        {
            java.io.File candidate = new java.io.File(dir, "mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/" //$NON-NLS-1$
                + "mcp/server/tools/base/AbstractMetadataWriteTool.java"); //$NON-NLS-1$
            source = candidate.isFile() ? candidate : null;
            dir = dir.getParentFile();
        }
        assertTrue("AbstractMetadataWriteTool.java not found", source != null); //$NON-NLS-1$
        String text = java.nio.file.Files.readString(source.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("recheckedOnUiThread(() -> ProjectStateChecker.checkoutReloadErrorOrNull(projectName)")); //$NON-NLS-1$
    }
}
