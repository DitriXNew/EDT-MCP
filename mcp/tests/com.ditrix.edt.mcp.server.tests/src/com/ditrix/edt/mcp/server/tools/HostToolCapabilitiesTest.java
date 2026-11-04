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

    @Test public void consentToolsAreAlwaysWindowedOnly() {
        for (String name : List.of("delete_project", "delete_infobase", "evaluate_expression", "update_database")) {
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
        assertEquals(HostToolCapabilities.Capability.WINDOWED_ONLY, HostToolCapabilities.entries().get("sort_metadata").capability());
        assertEquals(HostToolCapabilities.Capability.HEADLESS, HostToolCapabilities.entries().get("export_configuration_to_file").capability());
        assertEquals(HostToolCapabilities.Capability.HEADLESS, HostToolCapabilities.entries().get("get_server_status").capability());
    }

    @Test public void hostIsExplicitAndNeverInferredFromAwtOrClientMode() {
        System.setProperty("eclipse.application", "org.eclipse.ui.ide.workbench");
        assertEquals(HostKind.WINDOWED, HostKind.current());
        assertTrue(HostToolCapabilities.availableOnCurrentHost("delete_project"));
        System.setProperty("eclipse.application", HostKind.APPLICATION_ID);
        assertEquals(HostKind.HEADLESS, HostKind.current());
        assertFalse(HostToolCapabilities.availableOnCurrentHost("delete_project"));
        assertTrue(HostToolCapabilities.availableOnCurrentHost("list_projects"));
    }

    @Test public void deleteProjectRefusesBeforeExecution() { assertDirectCallRefused("delete_project"); }
    @Test public void deleteInfobaseRefusesBeforeExecution() { assertDirectCallRefused("delete_infobase"); }
    @Test public void evaluateExpressionRefusesBeforeExecution() { assertDirectCallRefused("evaluate_expression"); }
    @Test public void updateDatabaseRefusesBeforeExecution() { assertDirectCallRefused("update_database"); }

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
        McpToolRegistry.getInstance().replaceAll(List.of(tool("delete_project", executions)));
        String response = call("delete_project");
        assertTrue(response.contains("sentinel")); assertEquals(1, executions.get());
        assertFalse(JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("result").has("isError"));
        assertEquals(1, McpToolRegistry.getInstance().getVisibleTools().size());
        System.setProperty("eclipse.application", HostKind.APPLICATION_ID);
        assertTrue(McpToolRegistry.getInstance().getVisibleTools().isEmpty());
        assertNotNull(McpToolRegistry.getInstance().getTool("delete_project"));
    }

    private static IMcpTool tool(String name, AtomicInteger calls) {
        return new IMcpTool() {
            public String getName() { return name; }
            public String getDescription() { return "Admission test"; }
            public String getInputSchema() { return "{\"type\":\"object\"}"; }
            public ResponseType getResponseType() { return ResponseType.JSON; }
            public String execute(Map<String, String> args) {
                calls.incrementAndGet(); return com.ditrix.edt.mcp.server.protocol.ToolResult.success().put("sentinel", true).toJson();
            }
        };
    }
    private static String call(String name) {
        return new McpProtocolHandler().processRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":{}}}");
    }
}
