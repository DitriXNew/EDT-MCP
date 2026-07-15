/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Unit tests for {@link FanOut#mergeListProjects}: merging usable {@code list_projects}
 * responses in the given order, rewriting the {@code id} to the caller's request id,
 * skipping unusable responses (unparseable body, JSON-RPC error, tool-level error), and the
 * zero-usable-responses JSON-RPC error.
 */
public class FanOutTest
{
    private static String listProjectsResponse(Object id, String... projectNames)
    {
        JsonArray projects = new JsonArray();
        for (String name : projectNames)
        {
            JsonObject project = new JsonObject();
            project.addProperty("name", name); //$NON-NLS-1$
            projects.add(project);
        }
        JsonObject structured = new JsonObject();
        structured.addProperty("success", true); //$NON-NLS-1$
        structured.add("projects", projects); //$NON-NLS-1$

        JsonObject textItem = new JsonObject();
        textItem.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
        textItem.addProperty("text", "projects listed"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray content = new JsonArray();
        content.add(textItem);

        JsonObject result = new JsonObject();
        result.add("content", content); //$NON-NLS-1$
        result.addProperty("isError", false); //$NON-NLS-1$
        result.add("structuredContent", structured); //$NON-NLS-1$

        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        FanOut.writeId(envelope, id);
        envelope.add("result", result); //$NON-NLS-1$
        return Json.compact(envelope);
    }

    private static List<String> projectNamesOf(JsonObject response)
    {
        JsonObject structured = Json.obj(Json.obj(response, "result"), "structuredContent"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> names = new ArrayList<>();
        for (JsonElement project : structured.getAsJsonArray("projects")) //$NON-NLS-1$
        {
            names.add(project.getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$
        }
        return names;
    }

    // ---- merging 2 valid responses ----

    @Test
    public void testMergesTwoValidResponsesInGivenOrder()
    {
        String responseA = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$
        String responseB = listProjectsResponse(2, "ProjectB", "ProjectC"); //$NON-NLS-1$ //$NON-NLS-2$

        String merged = FanOut.mergeListProjects(Arrays.asList(responseA, responseB), 99);

        assertEquals(List.of("ProjectA", "ProjectB", "ProjectC"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            projectNamesOf(Json.parseObject(merged)));
    }

    @Test
    public void testMergePreservesTheOrderOfTheGivenResponseList()
    {
        String responseA = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$
        String responseB = listProjectsResponse(2, "ProjectB"); //$NON-NLS-1$

        // Reversed input order -> reversed merged order: the merge is order-preserving,
        // not sorting - callers (the handler) are responsible for passing port order.
        String merged = FanOut.mergeListProjects(Arrays.asList(responseB, responseA), 1);

        assertEquals(List.of("ProjectB", "ProjectA"), projectNamesOf(Json.parseObject(merged))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMergeRewritesIdToTheGivenNumericRequestId()
    {
        String responseA = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(List.of(responseA), 42);

        assertEquals(42, Json.parseObject(merged).get("id").getAsInt()); //$NON-NLS-1$
    }

    @Test
    public void testMergeRewritesIdToNullRequestId()
    {
        String responseA = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(List.of(responseA), null);

        // writeId stamps JsonNull for a null request id, but the shared Gson (no
        // serializeNulls) drops an explicit JsonNull member on serialization - so the
        // key is either absent or JSON null. Both are acceptable: the live call path
        // always carries a real id (a tools/call request); what matters is that the
        // ORIGINAL backend id (1) never leaks through.
        com.google.gson.JsonElement id = Json.parseObject(merged).get("id"); //$NON-NLS-1$
        assertTrue(id == null || id.isJsonNull());
    }

    // ---- skipping one broken/unusable response ----

    @Test
    public void testMergeSkipsAnUnparseableResponse()
    {
        String good = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$
        String broken = "this is not json"; //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(Arrays.asList(good, broken), 1);

        assertEquals(List.of("ProjectA"), projectNamesOf(Json.parseObject(merged))); //$NON-NLS-1$
    }

    @Test
    public void testMergeSkipsAJsonRpcErrorResponse()
    {
        String good = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$
        String errorResponse = FanOut.jsonRpcError(-32000, "boom", 2); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(Arrays.asList(good, errorResponse), 1);

        assertEquals(List.of("ProjectA"), projectNamesOf(Json.parseObject(merged))); //$NON-NLS-1$
    }

    @Test
    public void testMergeSkipsAToolLevelErrorResponse()
    {
        String good = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$
        JsonObject result = new JsonObject();
        result.addProperty("isError", true); //$NON-NLS-1$
        JsonObject toolError = new JsonObject();
        toolError.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        toolError.addProperty("id", 2); //$NON-NLS-1$
        toolError.add("result", result); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(Arrays.asList(good, Json.compact(toolError)), 1);

        assertEquals(List.of("ProjectA"), projectNamesOf(Json.parseObject(merged))); //$NON-NLS-1$
    }

    // ---- zero usable responses -> the -32000 "No running EDT backends" error ----

    @Test
    public void testZeroUsableResponsesYieldsNoBackendsError()
    {
        assertNoBackendsError(FanOut.mergeListProjects(List.of("garbage", "also garbage"), 7), 7); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNullResponseListYieldsNoBackendsError()
    {
        assertNoBackendsError(FanOut.mergeListProjects(null, 1), 1);
    }

    @Test
    public void testEmptyResponseListYieldsNoBackendsError()
    {
        assertNoBackendsError(FanOut.mergeListProjects(List.of(), 1), 1);
    }

    private static void assertNoBackendsError(String merged, int expectedId)
    {
        JsonObject parsed = Json.parseObject(merged);
        assertTrue(parsed.has("error")); //$NON-NLS-1$
        JsonObject error = parsed.getAsJsonObject("error"); //$NON-NLS-1$
        assertEquals(FanOut.ERROR_NO_BACKENDS, error.get("code").getAsInt()); //$NON-NLS-1$
        assertEquals(-32000, error.get("code").getAsInt()); //$NON-NLS-1$
        assertEquals(FanOut.MSG_NO_BACKENDS, error.get("message").getAsString()); //$NON-NLS-1$
        assertEquals("No running EDT backends", error.get("message").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(expectedId, parsed.get("id").getAsInt()); //$NON-NLS-1$
    }
}
