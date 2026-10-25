/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.junit.Test;

/** Pins the launch diagnostics through the existing Eclipse log listener and environment seam. */
public class ToolPresetLaunchDiagnosticsTest extends ToolPresetSessionTestSupport
{
    @Test
    public void testUnknownIdWarnsWithOnlyPredefinedIdsAndStoredSelectionHint() throws Exception
    {
        configureLaunch("analysis_only_typo");
        assertNull(service.getSessionPreset());
        assertEquals(storedDisabled(), service.getDisabledTools());
        assertNull(service.getSessionPreset());
        List<IStatus> messages = presetMessages();
        assertEquals("one diagnostic for repeated reads", 1, messages.size());
        assertEquals(IStatus.WARNING, messages.get(0).getSeverity());
        assertEquals("Invalid EDT_MCP_TOOL_PRESET value: analysis_only_typo. Valid preset ids: "
            + "all-tools, analysis-only, code-review, development. "
            + "Unset or custom uses the stored tool selection. Ignoring launch override.",
            messages.get(0).getMessage());
        assertEquals("resolution must be cached", 1, environmentReads.get());
    }

    @Test
    public void testCustomUsesStoredSelectionAndLogsInfoOnce() throws Exception
    {
        assertCustomDiagnostic("custom");
    }

    @Test
    public void testUppercaseCustomUsesStoredSelectionAndLogsInfoOnce() throws Exception
    {
        assertCustomDiagnostic("CUSTOM");
    }

    private void assertCustomDiagnostic(String value) throws Exception
    {
        Map<String, String> before = snapshot();
        configureLaunch(value);
        assertNull("custom must not activate a launch override", service.getSessionPreset());
        assertEquals(storedDisabled(), service.getDisabledTools());
        assertNull(service.getSessionPreset());
        assertEquals("custom must retain stored preferences", before, snapshot());
        List<IStatus> messages = presetMessages();
        assertEquals("one diagnostic for repeated reads", 1, messages.size());
        assertEquals(IStatus.INFO, messages.get(0).getSeverity());
        assertEquals("EDT_MCP_TOOL_PRESET=custom uses the stored tool selection; no launch override.",
            messages.get(0).getMessage());
        assertTrue("custom must not warn", presetWarnings().isEmpty());
        assertEquals("resolution must be cached", 1, environmentReads.get());
    }
}
