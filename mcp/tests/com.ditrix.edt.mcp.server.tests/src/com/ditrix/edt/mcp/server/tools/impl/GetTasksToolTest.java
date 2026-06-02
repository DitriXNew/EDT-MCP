/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetTasksTool}.
 * <p>
 * All parameters are optional and {@code execute()} goes straight to the live
 * {@code ResourcesPlugin} workspace (task markers), so there is no
 * argument-validation branch reachable before live access. The headless surface
 * is the static contract (the tool uses the {@code IMcpTool} default MARKDOWN
 * response type); the task listing is covered by the E2E suite.
 */
public class GetTasksToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_tasks", new GetTasksTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetTasksTool.NAME, new GetTasksTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetTasksTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetTasksTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetTasksTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"filePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"priority\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaPriorityIsEnum()
    {
        // priority is a closed vocabulary, so it is advertised as a JSON Schema enum.
        String schema = new GetTasksTool().getInputSchema();
        assertTrue(schema.contains("\"enum\":[\"high\",\"normal\",\"low\"]")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidPriorityRejected()
    {
        // An out-of-set priority is rejected up front (before any live workspace
        // access), so the validation branch is reachable headlessly.
        Map<String, String> params = new HashMap<>();
        params.put("priority", "urgent"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetTasksTool().execute(params);
        // Delimiter-free substrings only: Gson HTML-escapes apostrophes / '>=' in
        // JSON tool output, but "priority must be one of" and the bare values are safe.
        assertTrue(result.contains("priority must be one of")); //$NON-NLS-1$
        assertTrue(result.contains("high")); //$NON-NLS-1$
        assertTrue(result.contains("normal")); //$NON-NLS-1$
        assertTrue(result.contains("low")); //$NON-NLS-1$
    }

    // --- taskKey dedup helper (A4: a BSL TODO/FIXME surfacing under both the
    // base task marker and the Xtext task marker subtype must be counted once) ---

    @Test
    public void testTaskKeyIsStableForSameTask()
    {
        // Two markers for the same task (one from each marker type) carry the
        // same path/line/message/priority, so they must produce the same key.
        String a = GetTasksTool.taskKey("/P/src/Module.bsl", 42, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String b = GetTasksTool.taskKey("/P/src/Module.bsl", 42, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(a, b);
    }

    @Test
    public void testTaskKeyDiffersByLine()
    {
        String a = GetTasksTool.taskKey("/P/src/Module.bsl", 42, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String b = GetTasksTool.taskKey("/P/src/Module.bsl", 43, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotEquals(a, b);
    }

    @Test
    public void testTaskKeyDiffersByPath()
    {
        String a = GetTasksTool.taskKey("/P/src/A.bsl", 42, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String b = GetTasksTool.taskKey("/P/src/B.bsl", 42, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotEquals(a, b);
    }

    @Test
    public void testTaskKeyDiffersByMessage()
    {
        String a = GetTasksTool.taskKey("/P/src/Module.bsl", 42, "TODO: refactor", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String b = GetTasksTool.taskKey("/P/src/Module.bsl", 42, "FIXME: broken", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotEquals(a, b);
    }

    @Test
    public void testTaskKeyDiffersByPriority()
    {
        String a = GetTasksTool.taskKey("/P/src/Module.bsl", 42, "TODO: refactor", "high"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String b = GetTasksTool.taskKey("/P/src/Module.bsl", 42, "TODO: refactor", "low"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotEquals(a, b);
    }

    @Test
    public void testTaskKeyHandlesNulls()
    {
        // Null path/message/priority must not throw and must be stable.
        String a = GetTasksTool.taskKey(null, -1, null, null);
        String b = GetTasksTool.taskKey(null, -1, null, null);
        assertNotNull(a);
        assertEquals(a, b);
    }

    @Test
    public void testTaskKeyFieldsDoNotBleedAcrossBoundary()
    {
        // Without a field separator, ("ab","") and ("a","b") could collide.
        // The key uses a delimiter, so adjacent fields stay distinct.
        String a = GetTasksTool.taskKey("/P/ab", 1, "", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String b = GetTasksTool.taskKey("/P/a", 1, "b", "normal"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotEquals(a, b);
    }
}
