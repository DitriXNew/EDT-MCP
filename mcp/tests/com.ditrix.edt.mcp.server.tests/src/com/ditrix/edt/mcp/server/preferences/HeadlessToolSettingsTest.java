// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.HeadlessReadFixture;

public class HeadlessToolSettingsTest
{
    @Test public void headlessIgnoresStoredDisablingAndBothReadOnlyPresetsWithoutWritingThem() throws Exception
    {
        var registry = McpToolRegistry.getInstance();
        var savedTools = registry.getAllTools();
        var settings = ToolSettingsService.getInstance();
        var savedEnvironment = settings.environment;
        Field preset = ToolSettingsService.class.getDeclaredField("sessionPreset");
        preset.setAccessible(true);
        Object savedPreset = preset.get(settings);
        try (var fixture = new HeadlessReadFixture())
        {
            PreferenceStore store = new PreferenceStore();
            store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "create_metadata");
            store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);
            store.setValue(PreferenceConstants.PREF_PROGRESSIVE_DISCLOSURE, true);
            when(Activator.getDefault().getPreferenceStore()).thenReturn(store);
            AtomicInteger calls = new AtomicInteger();
            IMcpTool tool = new IMcpTool() {
                public String getName() { return "create_metadata"; }
                public String getDescription() { return "Host settings sentinel"; }
                public String getInputSchema() { return "{\"type\":\"object\"}"; }
                public ResponseType getResponseType() { return ResponseType.JSON; }
                public String execute(Map<String, String> params) {
                    calls.incrementAndGet(); return ToolResult.success().put("sentinel", true).toJson();
                }
            };
            registry.replaceAll(List.of(tool));
            for (String choice : new String[] { "custom", "analysis-only", "code-review" })
            {
                settings.environment = name -> choice;
                preset.set(settings, null);
                assertTrue(choice, settings.getDisabledTools().contains("create_metadata"));
                System.setProperty("eclipse.application", HostKind.APPLICATION_ID);
                assertTrue(choice, registry.isToolEnabled("create_metadata"));
                assertEquals(List.of(tool), List.copyOf(registry.getVisibleTools()));
                assertEquals(List.of(tool), List.copyOf(registry.getEnabledTools()));
                String response = new McpProtocolHandler().processRequest(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"create_metadata\",\"arguments\":{}}}");
                assertTrue(response, response.contains("sentinel"));
                System.setProperty("eclipse.application", "org.eclipse.ui.ide.workbench");
                assertFalse(choice, registry.isToolEnabled("create_metadata"));
                assertTrue(registry.getEnabledTools().isEmpty());
                assertEquals("create_metadata", store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
            }
            assertEquals(3, calls.get());
        }
        finally
        {
            settings.environment = savedEnvironment;
            preset.set(settings, savedPreset);
            registry.replaceAll(savedTools);
        }
    }
}
