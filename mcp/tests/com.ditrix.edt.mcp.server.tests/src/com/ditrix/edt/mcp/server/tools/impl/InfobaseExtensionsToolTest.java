/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.ReadResult;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.SessionInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class InfobaseExtensionsToolTest
{
    private final InfobaseExtensionsTool tool = new InfobaseExtensionsTool();

    @Test public void declaresInfobaseDataAndDestructiveConsent()
    {
        assertEquals("infobase_extensions", tool.getName());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertTrue(tool.connectsToInfobase());
        assertTrue(tool.returnsInfobaseData());
        assertTrue(tool.getDescription().contains("get_tool_guide('infobase_extensions')"));
        assertTrue(tool.getDescription().contains("WITHOUT confirm"));
        assertTrue(tool.getDescription().contains("expectedHash"));
        assertTrue(tool.getDescription().contains("contentHash"));
        assertTrue(ToolAnnotationClassifier.classify(tool.getName()).getDestructiveHint());
        assertTrue(DestructiveConsentGate.GATED_TOOLS.contains(tool.getName()));
    }

    @Test public void requiresExplicitApplicationAndProject()
    {
        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        assertEquals(2, schema.getAsJsonArray("required").size());
        for (String key : List.of("projectName", "applicationId", "action", "extensionName", "cfePath",
            "safeMode", "protectionFromDangerousActions", "expectedVersion", "confirm", "expectedHash", "timeoutSeconds"))
        {
            assertTrue(key, schema.getAsJsonObject("properties").has(key));
        }
        assertError(Map.of(), "projectName");
        assertError(Map.of("projectName", "P"), "applicationId");
    }

    @Test public void declaresActualStateAndVerificationOutputs()
    {
        var properties = JsonParser.parseString(tool.getOutputSchema()).getAsJsonObject().getAsJsonObject("properties");
        for (String key : List.of("extensions", "actualState", "extensionInstalled", "verification", "mutationOutcomeUnknown", "contentHash"))
        {
            assertTrue(key, properties.has(key));
        }
        assertFalse(properties.has("expectedHash"));
    }

    @Test public void rejectsInvalidActionsAndMissingNamesBeforeApplicationResolution()
    {
        assertError(params("action", "replace"), "Allowed values: list, install, update, uninstall");
        assertError(params("action", "uninstall"), "extensionName is required");
        var parameters = params("action", "update");
        parameters.put("extensionName", "Runtime");
        assertError(parameters, "cfePath is required");
    }

    @Test public void listRejectsWriteParameters()
    {
        assertError(params("confirm", "true"), "remove write parameters");
        assertError(params("safeMode", "false"), "remove write parameters");
        assertError(params("cfePath", "anything.cfe"), "remove write parameters");
        assertError(params("expectedHash", "stale"), "remove write parameters");
    }

    @Test public void confirmationRequiresExpectedHashBeforeApplicationResolution()
    {
        var parameters = params("action", "uninstall");
        parameters.put("extensionName", "Runtime");
        parameters.put("confirm", "true");
        assertError(parameters, "expectedHash is required");
        parameters.put("confirm", "false");
        parameters.put("expectedHash", "stale");
        assertError(parameters, "expectedHash is accepted only with confirm=true");
    }

    @Test public void rejectsUnsafeBooleanAndTimeoutCoercion()
    {
        assertError(params("safeMode", "maybe"), "safeMode must be a JSON boolean");
        assertError(params("confirm", "yes"), "confirm must be a JSON boolean");
        assertError(params("timeoutSeconds", "NaN"), "timeoutSeconds must be an integer");
        assertError(params("timeoutSeconds", "0"), "10 to 600");
        assertError(params("timeoutSeconds", "601"), "10 to 600");
    }

    @Test public void standaloneSessionAdmissionPreservesUnreachableAndBusyReasons()
    {
        assertNull(InfobaseExtensionsTool.sessionRefusal(ReadResult.readable(List.of())));
        assertTrue(InfobaseExtensionsTool.sessionRefusal(ReadResult.unreachable("ibcmd authentication refused"))
            .contains("ibcmd authentication refused"));
        SessionInfo designer = new SessionInfo("1", 1L, "Designer", "agent", "host", null, null, true);
        assertNull(InfobaseExtensionsTool.sessionRefusal(ReadResult.readable(List.of(designer))));
        SessionInfo client = new SessionInfo("2", 2L, "1CV8C", "user", "host", null, null, false);
        String refusal = InfobaseExtensionsTool.sessionRefusal(ReadResult.readable(List.of(designer, client)));
        assertTrue(refusal, refusal.contains("active non-Designer sessions"));
        assertTrue(refusal, refusal.contains("infobase_sessions"));
        assertTrue(refusal, refusal.contains("separate consent"));
        assertFalse(refusal, refusal.contains("user"));
    }

    private static Map<String, String> params(String key, String value)
    {
        var params = new HashMap<>(Map.of("projectName", "P", "applicationId", "explicit-target"));
        params.put(key, value);
        return params;
    }

    private void assertError(Map<String, String> params, String text)
    {
        String result = tool.execute(params);
        assertTrue(result, ToolResult.isErrorPayload(result));
        assertTrue(result, result.contains(text));
    }
}
