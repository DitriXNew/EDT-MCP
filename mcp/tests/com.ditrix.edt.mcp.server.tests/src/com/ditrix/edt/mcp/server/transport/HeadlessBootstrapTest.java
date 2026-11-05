// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import com.ditrix.edt.mcp.server.ipc.*;
import com.ditrix.edt.mcp.server.tools.HostToolCapabilities;
import com.google.gson.*;

public class HeadlessBootstrapTest extends StdioTestSupport
{
    @Test public void explicitArgumentAloneSelectsHeadless() {
        assertTrue(StdioBootstrap.headlessRequested(new String[]{"-headless"}));
        assertFalse(StdioBootstrap.headlessRequested(new String[]{"-data", "headless"}));
        assertFalse(StdioBootstrap.headlessRequested(new String[]{}));
    }

    @Test public void headlessStarterKeepsDetachedMechanismAndSelectsOurApplication() {
        Path install = temporary.getRoot().toPath(), workspace = install.resolve("workspace"), config = install.resolve("configuration");
        List<String> headless = new WindowsEdtStarter(install, workspace, config, true).command();
        assertEquals(install.resolve("1cedtc.exe").toString(), headless.get(0));
        assertTrue(headless.contains("-nosplash")); assertTrue(headless.contains("-Dedt.mcp.stdioOnly=true"));
        assertEquals("com.ditrix.edt.mcp.server.stdioHost", headless.get(headless.indexOf("-application") + 1));
        assertEquals(workspace.toString(), headless.get(headless.indexOf("-data") + 1));
        assertEquals(config.toString(), headless.get(headless.indexOf("-configuration") + 1));
        assertFalse(headless.contains("-startup"));
        List<String> windowed = new WindowsEdtStarter(install, workspace, config).command();
        assertEquals(install.resolve("1cedt.exe").toString(), windowed.get(0));
        assertFalse(windowed.contains("-application"));
        assertEquals(Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString(), windowed.get(windowed.indexOf("-vm") + 1));
    }

    @Test public void offlineListFiltersHeadlessOnlyAndNeverStartsEdt() throws Exception {
        JsonObject catalog = catalog();
        JsonArray tools = catalog.getAsJsonObject("tools").getAsJsonArray("tools");
        tools.get(0).getAsJsonObject().addProperty("name", "list_projects");
        JsonObject windowed = tools.get(0).deepCopy().getAsJsonObject(); windowed.addProperty("name", "open_editor"); tools.add(windowed);
        JsonObject degraded = windowed.deepCopy(); degraded.addProperty("name", "git"); tools.add(degraded);
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("list_projects", "HEADLESS"); capabilities.addProperty("open_editor", "WINDOWED_ONLY"); capabilities.addProperty("git", "HEADLESS_DEGRADED");
        catalog.add("hostCapabilities", capabilities);
        for (boolean headless : new boolean[]{false, true}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (var bootstrap = new StdioBootstrap(() -> { fail("Offline discovery cannot open registry"); return null; },
                () -> { fail("Offline discovery cannot start EDT"); return null; }, output, catalog, headless)) {
                bootstrap.accept(init(1, false)); bootstrap.accept(initialized());
                bootstrap.accept("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
                var frames = new ByteArrayInputStream(output.toByteArray()); JsonFrames.read(frames);
                var result = JsonParser.parseString(JsonFrames.read(frames)).getAsJsonObject().getAsJsonObject("result");
                assertEquals(headless ? 2 : 3, result.getAsJsonArray("tools").size());
                assertEquals(headless, !result.toString().contains("open_editor"));
                assertTrue(result.toString().contains("git")); assertFalse(result.has("hostCapabilities"));
                assertNull(JsonFrames.read(frames));
            }
        }
    }

    @Test public void bothMixedModeAttachOrderingsReuseTheHostAndUseItsCapabilities() throws Exception {
        var registry = com.ditrix.edt.mcp.server.tools.McpToolRegistry.getInstance(); var saved = registry.getAllTools();
        String previous = System.getProperty("eclipse.application");
        try {
            for (boolean hostHeadless : new boolean[]{false, true}) {
                System.setProperty("eclipse.application", hostHeadless ? com.ditrix.edt.mcp.server.HostKind.APPLICATION_ID : "org.eclipse.ui.ide.workbench");
                registry.replaceAll(List.of(new com.ditrix.edt.mcp.server.tools.IMcpTool() {
                    public String getName() { return "open_editor"; }
                    public String getDescription() { return "Mixed-mode sentinel"; }
                    public String getInputSchema() { return "{\"type\":\"object\"}"; }
                    public String execute(java.util.Map<String, String> args) { return "windowed-sentinel"; }
                }));
                var endpoint = registry(); var catalog = catalog(); var output = new ByteArrayOutputStream();
                catalog.getAsJsonObject("tools").getAsJsonArray("tools").get(0).getAsJsonObject().addProperty("name", "open_editor");
                JsonObject caps = new JsonObject(); caps.addProperty("open_editor", "WINDOWED_ONLY"); catalog.add("hostCapabilities", caps);
                try (var listener = new IpcListener(endpoint, "test");
                     var bootstrap = new StdioBootstrap(() -> endpoint, () -> { fail("Never start a second EDT"); return null; }, output, catalog, !hostHeadless)) {
                    bootstrap.accept(init(1, false)); bootstrap.accept(initialized()); bootstrap.accept(call(2, "open_editor"));
                    awaitOutput(output, hostHeadless ? "Remove -headless" : "windowed-sentinel");
                    bootstrap.accept("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");
                    awaitOutput(output, "\"id\":3");
                    var frames = new ByteArrayInputStream(output.toByteArray()); String frame; JsonObject live = null;
                    while ((frame = JsonFrames.read(frames)) != null) {
                        JsonObject value = JsonParser.parseString(frame).getAsJsonObject();
                        if (value.has("id") && value.get("id").getAsInt() == 3) { live = value.getAsJsonObject("result"); }
                    }
                    assertNotNull(live); assertEquals(hostHeadless ? 0 : 1, live.getAsJsonArray("tools").size());
                    assertTrue(listener.isRunning());
                }
            }
        }
        finally {
            registry.replaceAll(saved);
            if (previous == null) { System.clearProperty("eclipse.application"); } else { System.setProperty("eclipse.application", previous); }
        }
    }

    @Test public void packagedBootstrapFailureKeepsStdoutEmpty() throws Exception {
        Path directory = temporary.newFolder().toPath(), jar = directory.resolve("runtime.jar");
        try (var input = getClass().getResourceAsStream("/bootstrap/edt-mcp-runtime.jar")) {
            assertNotNull(input); java.nio.file.Files.copy(input, jar);
        }
        Path stdout = directory.resolve("stdout"), stderr = directory.resolve("stderr");
        String javaCommand = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var builder = new ProcessBuilder(javaCommand, "-jar", jar.toString(), "-headless").redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) { builder.environment().remove(variable); }
        Process process = builder.start(); process.getOutputStream().close();
        assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, process.exitValue()); assertEquals("", java.nio.file.Files.readString(stdout));
        assertTrue(java.nio.file.Files.readString(stderr).startsWith("EDT MCP bootstrap: Explicit -install is required"));
    }

    @Test public void shippedCatalogContainsTheWholeExplicitClassification() throws Exception {
        try (var jar = new java.util.jar.JarInputStream(getClass().getResourceAsStream("/bootstrap/edt-mcp-runtime.jar"))) {
            java.util.jar.JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (!entry.getName().equals("bootstrap-catalog.json")) { continue; }
                JsonObject catalog = JsonParser.parseString(new String(jar.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject capabilities = catalog.getAsJsonObject("hostCapabilities"); assertNotNull(capabilities);
                for (String name : List.of("get_metadata_objects", "get_metadata_details", "list_modules")) {
                    assertEquals(name, "HEADLESS", capabilities.get(name).getAsString());
                }
                assertEquals(HostToolCapabilities.entries().keySet(), capabilities.keySet());
                for (var tool : catalog.getAsJsonObject("tools").getAsJsonArray("tools")) {
                    String name = tool.getAsJsonObject().get("name").getAsString();
                    assertEquals(name, HostToolCapabilities.entries().get(name).capability().name(), capabilities.get(name).getAsString());
                    assertFalse(tool.getAsJsonObject().has("hostCapabilities"));
                }
                return;
            }
            fail("Missing catalog");
        }
    }
}
