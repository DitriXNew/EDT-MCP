/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

        // The human content table a list_projects response also carries (the fan-out row-merges these).
        StringBuilder table = new StringBuilder();
        table.append("## Workspace Projects\n\n**Total:** ").append(projectNames.length) //$NON-NLS-1$
            .append(" projects\n\n") //$NON-NLS-1$
            .append("| Name | State | Path | Open | EDT Project | Natures |\n") //$NON-NLS-1$
            .append("|------|-------|------|------|-------------|--------|\n"); //$NON-NLS-1$
        for (String name : projectNames)
        {
            table.append("| ").append(name).append(" | ready | /ws/").append(name) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" | Yes | Yes | Nature |\n"); //$NON-NLS-1$
        }
        JsonObject textItem = new JsonObject();
        textItem.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
        textItem.addProperty("text", table.toString()); //$NON-NLS-1$
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
    public void testMergedContentReflectsAllBackendsNotJustFirst()
    {
        // The human `content` channel must show ALL merged projects, not only the first backend's
        // envelope (issue #302): the rebuilt table's total and rows cover every backend.
        String responseA = listProjectsResponse(1, "ProjectA", "ProjectB"); //$NON-NLS-1$ //$NON-NLS-2$
        String responseB = listProjectsResponse(2, "ProjectC"); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(Arrays.asList(responseA, responseB), 99);

        String content = contentTextOf(Json.parseObject(merged));
        assertTrue("total must reflect all backends: " + content, //$NON-NLS-1$
            content.contains("**Total:** 3 projects")); //$NON-NLS-1$
        for (String name : new String[] { "ProjectA", "ProjectB", "ProjectC" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("content must list " + name + ": " + content, //$NON-NLS-1$ //$NON-NLS-2$
                content.contains("| " + name + " |")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String contentTextOf(JsonObject response)
    {
        JsonObject item = firstContentItemOf(response);
        JsonObject resource = Json.obj(item, "resource"); //$NON-NLS-1$
        return resource != null ? Json.str(resource, "text") : Json.str(item, "text"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static JsonObject firstContentItemOf(JsonObject response)
    {
        return Json.obj(response, "result").getAsJsonArray("content").get(0).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMergedContentIsCappedLikeADirectCall()
    {
        // The merged table is re-rendered from the UNCAPPED structured projects of every backend, so it
        // must be capped again - the proxy must not return a human channel bigger than a direct
        // list_projects would. structuredContent keeps the full list.
        String[] many = new String[4000];
        for (int i = 0; i < many.length; i++)
        {
            many[i] = "AVeryLongProjectNameForCapTesting" + i; //$NON-NLS-1$
        }
        String backend = listProjectsResponse(1, many);

        JsonObject parsed = Json.parseObject(FanOut.mergeListProjects(List.of(backend), 1));

        String content = contentTextOf(parsed);
        assertTrue("merged content must respect the cap: " + content.length(), //$NON-NLS-1$
            content.length() <= FanOut.MAX_CONTENT_CHARS);
        assertTrue("a capped table must say so", content.contains("truncated")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the last kept line must be a complete table row", //$NON-NLS-1$
            content.contains("| AVeryLongProjectNameForCapTesting0 |")); //$NON-NLS-1$
        // The full list is still available to a machine consumer.
        assertEquals(many.length, projectNamesOf(parsed).size());
    }

    @Test
    public void testMergedContentUnderTheCapIsNotTruncated()
    {
        String backend = listProjectsResponse(1, "ProjectA"); //$NON-NLS-1$

        String content = contentTextOf(Json.parseObject(FanOut.mergeListProjects(List.of(backend), 1)));

        assertFalse("a small table must not be truncated", content.contains("truncated")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMergedContentKeepsPlainTextShapeForCursor()
    {
        // A backend in Cursor plain-text mode returns content as a type:"text" block. The merged
        // content MUST stay a text block, not become a resource Cursor cannot consume.
        String backend = listProjectsResponse(1, "ProjectA"); // content is a type:"text" block //$NON-NLS-1$

        JsonObject item = firstContentItemOf(Json.parseObject(FanOut.mergeListProjects(List.of(backend), 1)));

        assertEquals("text", Json.str(item, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the text block is rewritten from the merged projects", //$NON-NLS-1$
            Json.str(item, "text").contains("| ProjectA |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMergedContentKeepsResourceShape()
    {
        // A backend whose content is a text/markdown embedded resource keeps the resource shape.
        String backend = listProjectsResourceResponse(2, "ProjectB"); //$NON-NLS-1$

        JsonObject item = firstContentItemOf(Json.parseObject(FanOut.mergeListProjects(List.of(backend), 2)));

        assertEquals("resource", Json.str(item, "type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the resource text is rewritten from the merged projects", //$NON-NLS-1$
            Json.str(Json.obj(item, "resource"), "text").contains("| ProjectB |")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A structured list_projects response whose content[0] is a text/markdown embedded resource. */
    private static String listProjectsResourceResponse(Object id, String... projectNames)
    {
        JsonArray projects = new JsonArray();
        for (String name : projectNames)
        {
            JsonObject project = new JsonObject();
            project.addProperty("name", name); //$NON-NLS-1$
            projects.add(project);
        }
        JsonObject structured = new JsonObject();
        structured.add("projects", projects); //$NON-NLS-1$

        JsonObject resource = new JsonObject();
        resource.addProperty("uri", "embedded://list-projects.md"); //$NON-NLS-1$ //$NON-NLS-2$
        resource.addProperty("mimeType", "text/markdown"); //$NON-NLS-1$ //$NON-NLS-2$
        resource.addProperty("text", "backend table"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject item = new JsonObject();
        item.addProperty("type", "resource"); //$NON-NLS-1$ //$NON-NLS-2$
        item.add("resource", resource); //$NON-NLS-1$
        JsonArray content = new JsonArray();
        content.add(item);

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

    @Test
    public void testMixedFleetContentCoversAllBackendsKeepingLegacyColumns()
    {
        // A mixed fan-out (a content-only legacy backend + a structured one): BOTH backends' projects
        // appear in structuredContent.projects AND in the rebuilt human content, and the legacy
        // backend's FULL columns (state/path/...) are recovered from its table - not downgraded to
        // name-only - so a client reading the human channel does not lose the properties list_projects
        // is meant to show.
        String legacyTable = "## Workspace Projects\n\n**Total:** 1 projects\n\n" //$NON-NLS-1$
            + "| Name | State | Path | Open | EDT Project | Natures |\n" //$NON-NLS-1$
            + "|------|-------|------|------|-------------|--------|\n" //$NON-NLS-1$
            + "| LegacyProj | ready | /ws/Legacy | Yes | Yes | V8ConfigurationNature |\n"; //$NON-NLS-1$
        String legacy = legacyContentOnlyResponse(1, legacyTable);
        String structured = listProjectsResponse(2, "ProjectA"); //$NON-NLS-1$

        String merged = FanOut.mergeListProjects(Arrays.asList(legacy, structured), 99);

        JsonObject parsed = Json.parseObject(merged);
        assertEquals(List.of("LegacyProj", "ProjectA"), projectNamesOf(parsed)); //$NON-NLS-1$ //$NON-NLS-2$
        String content = contentTextOf(parsed);
        assertTrue("content total must cover all backends: " + content, //$NON-NLS-1$
            content.contains("**Total:** 2 projects")); //$NON-NLS-1$
        // The legacy backend's OWN columns are preserved (path + state), not rendered as "-".
        assertTrue("legacy path preserved: " + content, content.contains("/ws/Legacy")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("structured project listed: " + content, content.contains("| ProjectA |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A usable list_projects response with a human content table but NO structuredContent. */
    private static String legacyContentOnlyResponse(Object id, String text)
    {
        JsonObject textItem = new JsonObject();
        textItem.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
        textItem.addProperty("text", text); //$NON-NLS-1$
        JsonArray content = new JsonArray();
        content.add(textItem);
        JsonObject result = new JsonObject();
        result.add("content", content); //$NON-NLS-1$
        result.addProperty("isError", false); //$NON-NLS-1$
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        FanOut.writeId(envelope, id);
        envelope.add("result", result); //$NON-NLS-1$
        return Json.compact(envelope);
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
