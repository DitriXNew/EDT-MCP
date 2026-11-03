/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.EditorNavigation.Request;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class OpenEditorToolTest
{
    private static Map<String, String> module()
    {
        return new HashMap<>(Map.of("projectName", "Project", "modulePath", "CommonModules/OK/Module.bsl"));
    }

    private static void errorContains(String result, String... fragments)
    {
        JsonObject error = JsonParser.parseString(result).getAsJsonObject();
        assertFalse(result, error.get("success").getAsBoolean());
        for (String fragment : fragments)
        {
            assertTrue(result, result.contains(fragment));
        }
    }

    private static OpenEditorTool accepting()
    {
        return new OpenEditorTool(() -> true, request -> { });
    }

    @Test
    public void contract()
    {
        OpenEditorTool tool = new OpenEditorTool();
        assertEquals("open_editor", tool.getName());
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
        assertNull(tool.getOutputSchema());
        assertTrue(tool.getDescription().contains("get_tool_guide('open_editor')"));
        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        assertEquals(5, schema.getAsJsonObject("properties").size());
        assertEquals("[\"projectName\"]", schema.getAsJsonArray("required").toString());
        assertFalse(PreferenceConstants.DEFAULT_ALLOW_UI_NAVIGATION);
        assertEquals(Boolean.FALSE, tool.getAnnotations().getDestructiveHint());
    }

    @Test
    public void disabledNeverCallsUi()
    {
        OpenEditorTool tool = new OpenEditorTool(() -> false, request -> fail("Must not dispatch"));
        errorContains(tool.execute(module()), "disabled", "Allow UI navigation", "Preferences");
    }

    @Test
    public void productionConstructorIsOffByDefault()
    {
        errorContains(new OpenEditorTool().execute(module()), "disabled", "Allow UI navigation");
    }

    @Test
    public void missingProject()
    {
        errorContains(accepting().execute(Map.of("fqn", "Catalog.Products")), "projectName");
    }

    @Test
    public void targetXor()
    {
        errorContains(accepting().execute(Map.of("projectName", "P")), "exactly one", "fqn", "modulePath");
        Map<String, String> params = module();
        params.put("fqn", "Catalog.Products");
        errorContains(accepting().execute(params), "exactly one", "fqn", "modulePath");
    }

    @Test
    public void lineRequiresModule()
    {
        errorContains(accepting().execute(Map.of("projectName", "P", "fqn", "Catalog.Products", "line", "2")),
            "line", "require modulePath");
    }

    @Test
    public void methodRequiresModule()
    {
        errorContains(accepting().execute(Map.of("projectName", "P", "fqn", "Catalog.Products", "methodName", "M")),
            "methodName", "require modulePath");
    }

    @Test
    public void lineAndMethodAreExclusive()
    {
        Map<String, String> params = module();
        params.put("line", "2");
        params.put("methodName", "M");
        errorContains(accepting().execute(params), "mutually exclusive", "line", "methodName");
    }

    @Test
    public void invalidLines()
    {
        for (String value : new String[] {"0", "-1", "1.5", "wrong", "99999999999999"})
        {
            Map<String, String> params = module();
            params.put("line", value);
            errorContains(accepting().execute(params), value, "positive", "line");
        }
    }

    @Test
    public void blankMethodAndTarget()
    {
        Map<String, String> params = module();
        params.put("methodName", " ");
        errorContains(accepting().execute(params), "methodName", "get_module_structure");
        params.remove("methodName");
        params.put("modulePath", " ");
        errorContains(accepting().execute(params), "blank", "valid target");
    }

    @Test
    public void moduleAndLineReachNavigator()
    {
        AtomicReference<Request> received = new AtomicReference<>();
        OpenEditorTool tool = new OpenEditorTool(() -> true, received::set);
        Map<String, String> params = module();
        params.put("line", "17");
        String result = tool.execute(params);
        assertTrue(result, result.contains("status: opened"));
        assertEquals(new Request("Project", null, "CommonModules/OK/Module.bsl", 17, null), received.get());
    }

    @Test
    public void methodAndMetadataReachNavigator()
    {
        AtomicReference<Request> received = new AtomicReference<>();
        OpenEditorTool tool = new OpenEditorTool(() -> true, received::set);
        Map<String, String> params = module();
        params.put("methodName", "Calculate");
        assertTrue(tool.execute(params).contains("status: opened"));
        assertEquals("Calculate", received.get().methodName());
        assertTrue(tool.execute(Map.of("projectName", "P", "fqn", "Catalog.Products.Attribute.Price"))
            .contains("status: opened"));
        assertEquals("Catalog.Products.Attribute.Price", received.get().fqn());
        assertNull(received.get().modulePath());
    }

    @Test
    public void headlessAndTimeoutAreErrors()
    {
        OpenEditorTool headless = new OpenEditorTool(() -> true, request -> {
            throw new IllegalStateException("EDT UI is unavailable (headless). Start EDT with a workbench.");
        });
        errorContains(headless.execute(module()), "headless", "Start EDT");
        OpenEditorTool timeout = new OpenEditorTool(() -> true, request -> {
            throw new TimeoutException("UI timeout. Check EDT before retrying.");
        });
        errorContains(timeout.execute(module()), "timeout", "Check EDT");
    }

    @Test
    public void interruptionPreservesFlag()
    {
        try
        {
            OpenEditorTool tool = new OpenEditorTool(() -> true, request -> { throw new InterruptedException(); });
            errorContains(tool.execute(module()), "interrupted", "Check EDT");
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally
        {
            Thread.interrupted();
        }
    }
}
