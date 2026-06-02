/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link RenameMetadataObjectTool}.
 * <p>
 * This is a cascade/destructive refactoring tool. The tests only exercise the
 * projectName/objectFqn/newName required-argument sentinels, which all return
 * (as {@link com.ditrix.edt.mcp.server.protocol.ToolResult#error} JSON payloads)
 * before {@code PlatformUI.getWorkbench()}
 * and before any refactoring is computed or applied — so no rename ever runs.
 * The actual cascade is covered by the E2E suite (and must be run on a test
 * configuration).
 */
public class RenameMetadataObjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("rename_metadata_object", new RenameMetadataObjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(RenameMetadataObjectTool.NAME, new RenameMetadataObjectTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new RenameMetadataObjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new RenameMetadataObjectTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new RenameMetadataObjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"newName\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (returns before any rename) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new RenameMetadataObjectTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingObjectFqn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new RenameMetadataObjectTool().execute(params);
        assertTrue(result.contains("objectFqn is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingNewName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectFqn", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new RenameMetadataObjectTool().execute(params);
        assertTrue(result.contains("newName is required")); //$NON-NLS-1$
    }

    // ============ Change-point numbering (A2: preview #index must equal execute index) ============
    //
    // The preview assigns one #index per leaf change; on execute, disableIndices is
    // applied by re-walking the change tree with the SAME numbering. walkLeafChanges
    // is that single source of truth: composites are recursed but never counted, and
    // every leaf gets exactly one sequential index in depth-first order. If this
    // drifts, a previewed "skip #N" would disable a different change on execute
    // (the A2 bug: preview expanded a leaf into N rows while execute counted it once).

    @Test
    public void testWalkLeafChangesNumbersLeavesDepthFirst()
    {
        CompositeChange root = new CompositeChange("root"); //$NON-NLS-1$
        CompositeChange mid = new CompositeChange("mid"); //$NON-NLS-1$
        Change a = new NullChange("a"); //$NON-NLS-1$
        Change b = new NullChange("b"); //$NON-NLS-1$
        Change c = new NullChange("c"); //$NON-NLS-1$
        Change d = new NullChange("d"); //$NON-NLS-1$
        mid.add(b);
        mid.add(c);
        root.add(a);
        root.add(mid);
        root.add(d);

        List<String> visitedNames = new ArrayList<>();
        List<Integer> visitedIndices = new ArrayList<>();
        int[] counter = {0};
        RenameMetadataObjectTool.walkLeafChanges(root, counter, (leaf, idx) -> {
            visitedNames.add(leaf.getName());
            visitedIndices.add(idx);
        });

        // Leaves only, depth-first; composites (root, mid) are not counted.
        assertEquals(List.of("a", "b", "c", "d"), visitedNames); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(List.of(0, 1, 2, 3), visitedIndices);
        assertEquals(4, counter[0]);
    }

    @Test
    public void testWalkLeafChangesSingleLeafGetsIndexZero()
    {
        Change leaf = new NullChange("only"); //$NON-NLS-1$
        int[] counter = {0};
        List<Integer> indices = new ArrayList<>();
        RenameMetadataObjectTool.walkLeafChanges(leaf, counter, (c, idx) -> indices.add(idx));
        assertEquals(List.of(0), indices);
        assertEquals(1, counter[0]);
    }

    @Test
    public void testWalkLeafChangesEmptyCompositeCountsNothing()
    {
        CompositeChange empty = new CompositeChange("empty"); //$NON-NLS-1$
        int[] counter = {0};
        int[] visits = {0};
        RenameMetadataObjectTool.walkLeafChanges(empty, counter, (c, idx) -> visits[0]++);
        assertEquals(0, visits[0]);
        assertEquals(0, counter[0]);
    }
}
