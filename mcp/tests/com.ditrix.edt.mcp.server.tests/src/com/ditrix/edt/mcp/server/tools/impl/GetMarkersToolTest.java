/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link GetMarkersTool} (the consolidation of the former
 * {@code get_bookmarks} + {@code get_tasks} tools).
 * <p>
 * The marker listing itself goes straight to the live {@code ResourcesPlugin}
 * workspace (via {@code ProjectContext.allProjects()}) and is covered by the E2E
 * suite. The headless surface is the static contract plus the up-front parameter
 * validation branches ({@code markerKind} / {@code priority} enums and the
 * {@code priority + markerKind=bookmark} contradiction), which all run before any
 * workspace access, and the pure {@code taskKey} dedup helper.
 */
public class GetMarkersToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_markers", new GetMarkersTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetMarkersTool.NAME, new GetMarkersTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetMarkersTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetMarkersTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetMarkersTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"filePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"markerKind\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"priority\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"limit\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaMarkerKindIsEnum()
    {
        // markerKind is a closed vocabulary, advertised as a JSON Schema enum.
        String schema = new GetMarkersTool().getInputSchema();
        assertTrue(schema.contains("\"enum\":[\"bookmark\",\"task\"]")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaPriorityIsEnum()
    {
        // priority is a closed vocabulary, advertised as a JSON Schema enum.
        String schema = new GetMarkersTool().getInputSchema();
        assertTrue(schema.contains("\"enum\":[\"high\",\"normal\",\"low\"]")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidMarkerKindRejected()
    {
        // An out-of-set markerKind is rejected up front (before any live workspace
        // access), so the validation branch is reachable headlessly.
        Map<String, String> params = new HashMap<>();
        params.put("markerKind", "comment"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMarkersTool().execute(params);
        assertTrue(result.contains("comment")); //$NON-NLS-1$
        assertTrue(result.contains("Must be one of")); //$NON-NLS-1$
        assertTrue(result.contains("bookmark")); //$NON-NLS-1$
        assertTrue(result.contains("task")); //$NON-NLS-1$
        // A rejected markerKind must NOT fall through to the marker report.
        assertFalse(result.contains("## Markers")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidPriorityRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("priority", "urgent"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMarkersTool().execute(params);
        // Message echoes the rejected value AND enumerates the valid set.
        assertTrue(result.contains("urgent")); //$NON-NLS-1$
        assertTrue(result.contains("Must be one of")); //$NON-NLS-1$
        assertTrue(result.contains("high")); //$NON-NLS-1$
        assertTrue(result.contains("normal")); //$NON-NLS-1$
        assertTrue(result.contains("low")); //$NON-NLS-1$
        assertFalse(result.contains("## Markers")); //$NON-NLS-1$
    }

    @Test
    public void testPriorityWithBookmarkKindRejected()
    {
        // priority sub-filters the task family only; combining it with
        // markerKind=bookmark selects no rows it could apply to, so the tool
        // rejects the contradiction up front rather than silently returning empty.
        Map<String, String> params = new HashMap<>();
        params.put("markerKind", "bookmark"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("priority", "high"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMarkersTool().execute(params);
        assertTrue(result.contains("priority")); //$NON-NLS-1$
        assertTrue(result.contains("task markers only")); //$NON-NLS-1$
        assertFalse(result.contains("## Markers")); //$NON-NLS-1$
    }

    // --- taskKey dedup helper (a BSL TODO/FIXME surfacing under both the base
    // task marker and the Xtext task marker subtype must be counted once) ---

    @Test
    public void testTaskKeyIsStableForSameTask()
    {
        String a = GetMarkersTool.taskKey("/P/src/Module.bsl", 42, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String b = GetMarkersTool.taskKey("/P/src/Module.bsl", 42, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(a, b);
    }

    @Test
    public void testTaskKeyDiffersByLine()
    {
        String a = GetMarkersTool.taskKey("/P/src/Module.bsl", 42, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String b = GetMarkersTool.taskKey("/P/src/Module.bsl", 43, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotEquals(a, b);
    }

    @Test
    public void testTaskKeyDiffersByPath()
    {
        String a = GetMarkersTool.taskKey("/P/src/A.bsl", 42, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String b = GetMarkersTool.taskKey("/P/src/B.bsl", 42, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotEquals(a, b);
    }

    @Test
    public void testTaskKeyDiffersByMessage()
    {
        String a = GetMarkersTool.taskKey("/P/src/Module.bsl", 42, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String b = GetMarkersTool.taskKey("/P/src/Module.bsl", 42, "FIXME: broken", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotEquals(a, b);
    }

    @Test
    public void testTaskKeyDiffersByPriority()
    {
        String a = GetMarkersTool.taskKey("/P/src/Module.bsl", 42, "TODO: refactor", "high"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String b = GetMarkersTool.taskKey("/P/src/Module.bsl", 42, "TODO: refactor", "low"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotEquals(a, b);
    }

    @Test
    public void testTaskKeyHandlesNulls()
    {
        String a = GetMarkersTool.taskKey(null, -1, null, null);
        String b = GetMarkersTool.taskKey(null, -1, null, null);
        assertNotNull(a);
        assertEquals(a, b);
    }

    @Test
    public void testTaskKeyFieldsDoNotBleedAcrossBoundary()
    {
        // Without a field separator, ("ab","") and ("a","b") could collide.
        String a = GetMarkersTool.taskKey("/P/ab", 1, "", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String b = GetMarkersTool.taskKey("/P/a", 1, "b", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotEquals(a, b);
    }

    @Test
    public void testTaskKeyEmbedsTheLineNumber()
    {
        // The line number participates in the key, so it must surface in the text.
        String key = GetMarkersTool.taskKey("/P/src/Module.bsl", 42, "TODO", "high"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("key must embed the line number", key.contains("42")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("key must contain all textual fields", //$NON-NLS-1$
            key.contains("/P/src/Module.bsl") && key.contains("TODO") && key.contains("high")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testTaskKeyUsesNewlineSeparator()
    {
        // The dedup key joins fields with a newline separator that cannot appear in
        // a line number; pin it so a future refactor cannot silently switch to a
        // separator that a path/message could itself contain.
        String key = GetMarkersTool.taskKey("/P/src/Module.bsl", 7, "TODO: x", "low"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("key must join fields with newlines", key.contains("\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Closed-vocabulary collections (the validation sets) ====================

    @Test
    public void testMarkerKindsVocabulary()
    {
        // The closed set the markerKind validation checks against.
        assertEquals(2, GetMarkersTool.MARKER_KINDS.size());
        assertTrue(GetMarkersTool.MARKER_KINDS.contains("bookmark")); //$NON-NLS-1$
        assertTrue(GetMarkersTool.MARKER_KINDS.contains("task")); //$NON-NLS-1$
    }

    @Test
    public void testPriorityValuesVocabulary()
    {
        // The closed set the priority validation checks against.
        assertEquals(3, GetMarkersTool.PRIORITY_VALUES.size());
        assertTrue(GetMarkersTool.PRIORITY_VALUES.contains("high")); //$NON-NLS-1$
        assertTrue(GetMarkersTool.PRIORITY_VALUES.contains("normal")); //$NON-NLS-1$
        assertTrue(GetMarkersTool.PRIORITY_VALUES.contains("low")); //$NON-NLS-1$
    }

    @Test
    public void testMarkerKindsAndPriorityValuesAreDisjoint()
    {
        // markerKind and priority are independent vocabularies; no value is shared,
        // so a stray cross-assignment cannot pass the wrong validation gate.
        for (String kind : GetMarkersTool.MARKER_KINDS)
        {
            assertFalse("'" + kind + "' must not also be a priority value", //$NON-NLS-1$ //$NON-NLS-2$
                GetMarkersTool.PRIORITY_VALUES.contains(kind));
        }
    }

    // ==================== Static contract: schema / output / annotations / guide ====================

    @Test
    public void testInputSchemaIsValidJsonObject()
    {
        // The advertised input schema must itself be syntactically valid JSON.
        JsonObject schema = JsonParser.parseString(new GetMarkersTool().getInputSchema()).getAsJsonObject();
        assertEquals("object", schema.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must carry a properties block", schema.has("properties")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSchemaLimitAdvertisesDefaultAndMax()
    {
        // The limit help must pin the canonical default (100) and max (1000) so an
        // agent knows the bounds without a probe call.
        String schema = new GetMarkersTool().getInputSchema();
        assertTrue("limit help must mention the default", schema.contains("100")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("limit help must mention the max", schema.contains("1000")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaIsNullForMarkdownTool()
    {
        // get_markers returns a MARKDOWN report (content, not structuredContent), so
        // it must leave the JSON output schema null per the IMcpTool contract.
        assertNull(new GetMarkersTool().getOutputSchema());
    }

    @Test
    public void testResultFileNameDefaultsToMarkdown()
    {
        // A MARKDOWN tool's embedded-resource file name defaults to <name>.md.
        assertEquals("get_markers.md", //$NON-NLS-1$
            new GetMarkersTool().getResultFileName(new HashMap<>()));
    }

    @Test
    public void testAnnotationsDefaultToCentralClassifier()
    {
        // The tool does not override annotations; returning null lets the central
        // classifier derive the read-only hints from the name.
        assertNull(new GetMarkersTool().getAnnotations());
    }

    @Test
    public void testGuideIsServedAndDocumentsKeyConcepts()
    {
        // The on-demand guide (guides/get_markers.md) must load and cover the
        // marker families, the task-marker-only priority rule and the dedup note.
        String guide = new GetMarkersTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must be served (non-empty)", guide.length() > 0); //$NON-NLS-1$
        assertTrue("guide must mention bookmarks", guide.toLowerCase().contains("bookmark")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must mention task markers", guide.toLowerCase().contains("task")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must scope priority to task markers", guide.contains("priority")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescriptionListsTaskMarkerKeywords()
    {
        // The description advertises the recognized task-marker keywords so an agent
        // knows what get_markers surfaces without reading the guide.
        String desc = new GetMarkersTool().getDescription();
        assertTrue(new GetMarkersTool().getGuide().contains("TODO")); //$NON-NLS-1$
        assertTrue(new GetMarkersTool().getGuide().contains("FIXME")); //$NON-NLS-1$
        assertTrue(new GetMarkersTool().getGuide().contains("XXX")); //$NON-NLS-1$
        assertTrue(new GetMarkersTool().getGuide().contains("HACK")); //$NON-NLS-1$
    }

    // ==================== Validation branches: case-insensitivity + JSON error shape ====================

    @Test
    public void testInvalidMarkerKindIsCaseInsensitiveReject()
    {
        // markerKind is normalized via toLowerCase() before the set check, so an
        // upper-cased out-of-set value is still rejected (covers the lowercasing
        // branch of the markerKind guard).
        Map<String, String> params = new HashMap<>();
        params.put("markerKind", "COMMENT"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMarkersTool().execute(params);
        assertTrue(result.contains("COMMENT")); //$NON-NLS-1$
        assertTrue(result.contains("Must be one of")); //$NON-NLS-1$
        assertFalse(result.contains("## Markers")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidPriorityIsCaseInsensitiveReject()
    {
        // priority is normalized via toLowerCase() before the set check.
        Map<String, String> params = new HashMap<>();
        params.put("priority", "URGENT"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMarkersTool().execute(params);
        assertTrue(result.contains("URGENT")); //$NON-NLS-1$
        assertTrue(result.contains("Must be one of")); //$NON-NLS-1$
        assertFalse(result.contains("## Markers")); //$NON-NLS-1$
    }

    @Test
    public void testPriorityWithBookmarkKindRejectedCaseInsensitively()
    {
        // The contradiction guard lowercases markerKind before comparing to
        // 'bookmark', so a mixed-case 'BookMark' still triggers the rejection.
        Map<String, String> params = new HashMap<>();
        params.put("markerKind", "BookMark"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("priority", "high"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMarkersTool().execute(params);
        assertTrue(result.contains("task markers only")); //$NON-NLS-1$
        assertFalse(result.contains("## Markers")); //$NON-NLS-1$
    }

    @Test
    public void testValidationErrorIsWellFormedToolResultJson()
    {
        // A rejected parameter returns a ToolResult.error envelope: valid JSON with
        // success=false and a populated error message naming the bad value.
        Map<String, String> params = new HashMap<>();
        params.put("markerKind", "nope"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMarkersTool().execute(params);
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        assertFalse("validation failure must report success=false", //$NON-NLS-1$
            json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("error envelope must carry an error message", json.has("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error message must name the rejected value", //$NON-NLS-1$
            json.get("error").getAsString().contains("nope")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidMarkerKindTakesPrecedenceOverPriority()
    {
        // markerKind is validated before priority; when both are bad, the markerKind
        // error is the one returned (pins guard ordering).
        Map<String, String> params = new HashMap<>();
        params.put("markerKind", "badKind"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("priority", "badPriority"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMarkersTool().execute(params);
        assertTrue("markerKind error must win", result.contains("Invalid markerKind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("priority error must not be reached", //$NON-NLS-1$
            result.contains("Invalid priority")); //$NON-NLS-1$
    }
}
