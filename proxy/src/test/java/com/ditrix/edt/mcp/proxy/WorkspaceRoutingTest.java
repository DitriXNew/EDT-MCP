/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Compatibility of workspace identity across the supported MCP content channels. */
public class WorkspaceRoutingTest
{
    @Test
    public void plainTextMachinePayloadRetainsWorkspaceIdentity()
    {
        String path = "C:\\Work\\Workspace | branch";
        JsonObject payload = Json.parseObject("{\"success\":true,\"projects\":[{\"name\":\"ERP\"}]}");
        payload.addProperty("workspacePath", path);
        JsonObject result = new JsonObject();
        result.add("content", FanOut.textContent(payload.toString()));
        assertEquals(path, WorkspaceRouting.workspacePath(BackendRegistry.machinePayload(result)));
        JsonObject envelope = new JsonObject();
        envelope.add("result", result);
        JsonObject merged = Json.parseObject(FanOut.mergeListProjects(List.of(envelope.toString()), 1, true));
        JsonObject textPayload = Json.parseObject(merged.getAsJsonObject("result").getAsJsonArray("content")
            .get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(path, textPayload.getAsJsonArray("projects").get(0).getAsJsonObject()
            .get("workspacePath").getAsString());
    }

    @Test
    public void legacyPayloadNeverInheritsIdentityFromAnotherBackend()
    {
        JsonObject modern = Json.parseObject("{\"result\":{\"structuredContent\":{\"success\":true,"
            + "\"workspacePath\":\"/first\",\"projects\":[{\"name\":\"ERP\"}]}}}");
        JsonObject legacy = Json.parseObject("{\"result\":{\"structuredContent\":{\"success\":true,"
            + "\"projects\":[{\"name\":\"ERP\",\"workspacePath\":\"/stale\"}]}}}");
        JsonObject merged = Json.parseObject(FanOut.mergeListProjects(
            List.of(modern.toString(), legacy.toString()), 1, true));
        JsonObject payload = merged.getAsJsonObject("result").getAsJsonObject("structuredContent");
        assertFalse(payload.has("workspacePath"));
        JsonArray projects = payload.getAsJsonArray("projects");
        assertEquals("/first", projects.get(0).getAsJsonObject().get("workspacePath").getAsString());
        assertFalse(projects.get(1).getAsJsonObject().has("workspacePath"));
    }

    @Test
    public void absentSelectorPreservesRawRequestAndSelectedCallPreservesOtherArguments()
    {
        String raw = " {\"params\":{\"arguments\":{\"projectName\":\"ERP\",\"options\":{\"x\":7}}}} ";
        JsonObject request = Json.parseObject(raw);
        assertSame(raw, WorkspaceRouting.backendRequest(raw, request));
        WorkspaceRouting.arguments(request).addProperty("workspacePath", "/selected");
        JsonObject stripped = Json.parseObject(WorkspaceRouting.backendRequest(request.toString(), request));
        assertEquals(Json.parseObject(raw), stripped);
        assertTrue("the caller's request stays intact", WorkspaceRouting.arguments(request).has("workspacePath"));
    }

    @Test
    public void workspaceIdentityRequiresAStringAndPreservesItsExactValue()
    {
        JsonObject object = new JsonObject();
        for (var invalid : List.of(new JsonPrimitive(12), new JsonPrimitive(true), new JsonPrimitive(" "),
            new JsonObject()))
        {
            object.add("workspacePath", invalid);
            assertNull(WorkspaceRouting.workspacePath(object));
        }
        object.addProperty("workspacePath", " /exact/case ");
        assertEquals(" /exact/case ", WorkspaceRouting.workspacePath(object));
    }

    @Test
    public void descriptorDecorationPreservesBackendConstraints()
    {
        JsonObject tool = Json.parseObject("{\"inputSchema\":{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"required\":[\"projectName\"],\"properties\":{\"projectName\":{\"type\":\"string\"}}}}");
        WorkspaceRouting.decorateTool(tool);
        JsonObject schema = tool.getAsJsonObject("inputSchema");
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        assertEquals("projectName", schema.getAsJsonArray("required").get(0).getAsString());
        assertEquals(2, schema.getAsJsonObject("properties").size());
        assertEquals("string", schema.getAsJsonObject("properties").getAsJsonObject("projectName")
            .get("type").getAsString());
    }
}
