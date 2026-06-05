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
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

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
}
