// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.*;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.google.gson.JsonParser;

public class HostToolCapabilitiesTest
{
    @Test public void everyMovedToolDispatchesOnBothHostsWithBilingualAddresses() throws Exception {
        try (var fixture = new com.ditrix.edt.mcp.server.utils.HeadlessReadFixture()) {
            var store = new org.eclipse.jface.preference.PreferenceStore();
            store.setDefault(com.ditrix.edt.mcp.server.preferences.PreferenceConstants.PREF_DISABLED_TOOLS, "sentinel");
            store.setValue(com.ditrix.edt.mcp.server.preferences.PreferenceConstants.PREF_DISABLED_TOOLS, "");
            store.setValue(com.ditrix.edt.mcp.server.preferences.PreferenceConstants.PREF_TOOL_PREFS_MIGRATION,
                com.ditrix.edt.mcp.server.preferences.PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);
            org.mockito.Mockito.when(com.ditrix.edt.mcp.server.Activator.getDefault().getPreferenceStore()).thenReturn(store);
            for (String name : List.of("read_method_source", "get_module_structure", "get_symbol_info",
                "go_to_definition", "find_references", "get_method_call_hierarchy", "validate_query",
                "get_configuration_properties", "get_outgoing_structures", "list_subsystems", "get_subsystem_content",
                "get_platform_documentation", "create_metadata", "modify_metadata", "sort_metadata", "resync_to_disk",
                "clone_metadata_object", "rename_metadata_object", "delete_metadata", "dcs", "business_process_route_map",
                "adopt_metadata_object", "apply_quick_fix", "git", "project_checks", "merge_rules", "delete_project", "delete_infobase", "update_database", "evaluate_expression", "ask_workmate")) {
                AtomicInteger calls = new AtomicInteger();
                McpToolRegistry.getInstance().replaceAll(List.of(tool(name, calls)));
                assertTrue(name, HostToolCapabilities.available(name));
                for (HostKind kind : HostKind.values()) {
                    System.setProperty("eclipse.application", kind == HostKind.HEADLESS
                        ? HostKind.APPLICATION_ID : "org.eclipse.ui.ide.workbench");
                    for (String type : List.of("CommonModule", "ОбщийМодуль")) {
                        for (String object : List.of("EnglishName", "Код")) {
                            String address = type + "." + object;
                            String response = new McpProtocolHandler().processRequest(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                                    + "\",\"arguments\":{\"fqn\":\"" + address + "\"}}}");
                            assertTrue(name + " " + kind + " " + response, response.contains(address));
                        }
                    }
                    assertEquals(name, 1, McpToolRegistry.getInstance().getVisibleTools().size());
                }
                assertEquals(name, 8, calls.get());
            }
        }
    }

    private String previous;
    private Collection<IMcpTool> saved;
    @Before public void before() {
        previous = System.getProperty("eclipse.application");
        saved = McpToolRegistry.getInstance().getAllTools();
    }
    @After public void after() {
        if (previous == null) { System.clearProperty("eclipse.application"); }
        else { System.setProperty("eclipse.application", previous); }
        McpToolRegistry.getInstance().replaceAll(saved);
    }

    @Test public void inventoryEqualsEveryRegisteredToolWithNoImplicitDefaults() {
        BuiltInToolRegistrar.registerAll(McpToolRegistry.getInstance());
        Set<String> registered = McpToolRegistry.getInstance().getAllTools().stream().map(IMcpTool::getName).collect(Collectors.toSet());
        assertEquals(registered, HostToolCapabilities.entries().keySet());
        assertTrue(registered.size() > 100);
        HostToolCapabilities.entries().forEach((name, entry) -> {
            assertNotNull(name, entry.capability()); assertFalse(name, entry.reason().isBlank());
        });
        assertFalse(HostToolCapabilities.available("unclassified_future_tool"));
    }

    @Test public void onlyNativeUiPathsRemainWindowedOnly() {
        for (String name : List.of("open_editor", "get_content_assist", "get_form_screenshot", "get_template_screenshot")) {
            assertEquals(name, HostToolCapabilities.Capability.WINDOWED_ONLY, HostToolCapabilities.entries().get(name).capability());
        }
    }

    @Test public void catalogGenerationRejectsAnUnclassifiedFutureTool() {
        try { HostToolCapabilities.requireEntry("unclassified_future_tool"); fail("A new tool must be classified explicitly"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("unclassified_future_tool")); }
    }

    @Test public void migratedMetadataReadsHaveExplicitTestEvidence() {
        for (String name : List.of("get_metadata_objects", "get_metadata_details", "list_modules")) {
            var entry = HostToolCapabilities.requireEntry(name);
            assertEquals(name, HostToolCapabilities.Capability.HEADLESS, entry.capability());
            assertTrue(entry.reason(), entry.reason().contains("ToolTest"));
            assertTrue(entry.reason(), entry.reason().contains("without a workbench"));
        }
    }

    @Test public void codeAuditIncludesNewToolAndDoesNotCopyResearchGuesses() {
        assertEquals(HostToolCapabilities.Capability.HEADLESS, HostToolCapabilities.entries().get("sort_metadata").capability());
        assertEquals(HostToolCapabilities.Capability.HEADLESS, HostToolCapabilities.entries().get("export_configuration_to_file").capability());
        assertEquals(HostToolCapabilities.Capability.HEADLESS, HostToolCapabilities.entries().get("get_server_status").capability());
    }

    @Test public void hostIsExplicitAndNeverInferredFromAwtOrClientMode() {
        System.setProperty("eclipse.application", "org.eclipse.ui.ide.workbench");
        assertEquals(HostKind.WINDOWED, HostKind.current());
        assertTrue(HostToolCapabilities.availableOnCurrentHost("open_editor"));
        System.setProperty("eclipse.application", HostKind.APPLICATION_ID);
        assertEquals(HostKind.HEADLESS, HostKind.current());
        assertFalse(HostToolCapabilities.availableOnCurrentHost("open_editor"));
        assertTrue(HostToolCapabilities.availableOnCurrentHost("list_projects"));
    }

    @Test public void editorRefusesBeforeExecution() { assertDirectCallRefused("open_editor"); }
    @Test public void contentAssistRefusesBeforeExecution() { assertDirectCallRefused("get_content_assist"); }
    @Test public void formRenderRefusesBeforeExecution() { assertDirectCallRefused("get_form_screenshot"); }
    @Test public void templateRenderRefusesBeforeExecution() { assertDirectCallRefused("get_template_screenshot"); }

    private void assertDirectCallRefused(String name) {
        System.setProperty("eclipse.application", HostKind.APPLICATION_ID);
            AtomicInteger executions = new AtomicInteger();
            McpToolRegistry.getInstance().replaceAll(List.of(tool(name, executions)));
            String response = call(name);
            var result = JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("result");
            assertTrue(response, result.has("isError"));
            assertTrue(response, result.get("isError").getAsBoolean());
            var payload = result.has("structuredContent") ? result.getAsJsonObject("structuredContent")
                : JsonParser.parseString(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject();
            assertFalse(payload.get("success").getAsBoolean());
            assertTrue(payload.get("error").getAsString().contains("Remove -headless from .mcp.json"));
            assertTrue(payload.get("error").getAsString().contains("close the host"));
            assertTrue(payload.get("error").getAsString().contains("open the workspace in EDT"));
            assertEquals(0, executions.get());
    }

    @Test public void windowedDispatchAndVisibilityRemainAvailable() {
        System.setProperty("eclipse.application", "org.eclipse.ui.ide.workbench");
        AtomicInteger executions = new AtomicInteger();
        McpToolRegistry.getInstance().replaceAll(List.of(tool("open_editor", executions)));
        String response = call("open_editor");
        assertTrue(response.contains("sentinel")); assertEquals(1, executions.get());
        assertFalse(JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("result").has("isError"));
        assertEquals(1, McpToolRegistry.getInstance().getVisibleTools().size());
        System.setProperty("eclipse.application", HostKind.APPLICATION_ID);
        assertTrue(McpToolRegistry.getInstance().getVisibleTools().isEmpty());
        assertNotNull(McpToolRegistry.getInstance().getTool("open_editor"));
    }

    private static IMcpTool tool(String name, AtomicInteger calls) {
        return new IMcpTool() {
            public String getName() { return name; }
            public String getDescription() { return "Admission test"; }
            public String getInputSchema() { return "{\"type\":\"object\"}"; }
            public ResponseType getResponseType() { return ResponseType.JSON; }
            public String execute(Map<String, String> args) {
                calls.incrementAndGet(); return com.ditrix.edt.mcp.server.protocol.ToolResult.success()
                    .put("sentinel", true).put("fqn", args.get("fqn")).toJson();
            }
        };
    }
    private static String call(String name) {
        return new McpProtocolHandler().processRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":{}}}");
    }
}
