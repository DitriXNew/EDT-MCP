/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetMetadataDetailsTool}.
 * <p>
 * Covers tool metadata, the input schema, and the projectName/objectFqns
 * required-argument validation that returns before the first
 * {@code PlatformUI.getWorkbench()} call. Resolving the objects needs a live
 * configuration and is covered by the E2E suite.
 */
public class GetMetadataDetailsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_metadata_details", new GetMetadataDetailsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetMetadataDetailsTool.NAME, new GetMetadataDetailsTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetMetadataDetailsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetMetadataDetailsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetMetadataDetailsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqns\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetMetadataDetailsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingObjectFqns()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMetadataDetailsTool().execute(params);
        assertTrue(result.contains("objectFqns is required")); //$NON-NLS-1$
    }

    // ==================== Per-object failure channel (no live workbench needed) ====================
    //
    // Object resolution needs a live configuration, but the dual-channel contract
    // (a per-object failure must be machine-distinguishable from data, never prose
    // mixed into the success body) is enforced by the pure formatting helpers below.

    @Test
    public void testResolutionFailureReasonForMalformedFqn()
    {
        GetMetadataDetailsTool tool = new GetMetadataDetailsTool();
        String reason = tool.describeResolutionFailure("NotAnFqn"); //$NON-NLS-1$
        assertTrue(reason.contains("Invalid FQN")); //$NON-NLS-1$
    }

    @Test
    public void testResolutionFailureReasonForMissingObject()
    {
        GetMetadataDetailsTool tool = new GetMetadataDetailsTool();
        String reason = tool.describeResolutionFailure("Catalog.NoSuchObject"); //$NON-NLS-1$
        assertEquals("Object not found", reason); //$NON-NLS-1$
    }

    /**
     * A batch with one valid FQN (its formatted data already in the body) and one
     * broken FQN: the broken outcome must be machine-differentiable (a dedicated
     * {@code ## Errors} section with an ERROR status row carrying the FQN) and the
     * success body must contain no {@code **Error:**} prose.
     */
    @Test
    public void testBrokenObjectIsMachineDistinguishableNotProse()
    {
        GetMetadataDetailsTool tool = new GetMetadataDetailsTool();

        // The valid FQN's data, as the formatter would emit it into the body.
        StringBuilder body = new StringBuilder();
        body.append("# Metadata Details: MyProject\n\n"); //$NON-NLS-1$
        body.append("## Catalog.Products\n\nSome data.\n"); //$NON-NLS-1$
        body.append("\n---\n\n"); //$NON-NLS-1$

        // The broken FQN goes into the dedicated machine-readable failures section.
        List<String[]> failures = new ArrayList<>();
        failures.add(new String[] { "Catalog.NoSuchObject", "Object not found" }); //$NON-NLS-1$ //$NON-NLS-2$
        body.append(tool.formatFailures(failures));

        String result = body.toString();

        // Machine-differentiable: a delimited section and a structured ERROR row.
        assertTrue(result.contains("## Errors")); //$NON-NLS-1$
        assertTrue(result.contains("| ERROR |")); //$NON-NLS-1$
        assertTrue(result.contains("Catalog.NoSuchObject")); //$NON-NLS-1$
        // The valid object's data is still present.
        assertTrue(result.contains("Catalog.Products")); //$NON-NLS-1$
        // No prose error line buried in the success body.
        assertFalse(result.contains("**Error:**")); //$NON-NLS-1$
    }

    /**
     * A pipe in an FQN or reason must not break the failures table — the shared
     * table builder escapes every cell.
     */
    @Test
    public void testFailuresTableEscapesPipes()
    {
        GetMetadataDetailsTool tool = new GetMetadataDetailsTool();
        List<String[]> failures = new ArrayList<>();
        failures.add(new String[] { "Catalog.A|B", "bad | reason" }); //$NON-NLS-1$ //$NON-NLS-2$
        String section = tool.formatFailures(failures);
        assertTrue(section.contains("Catalog.A\\|B")); //$NON-NLS-1$
        assertTrue(section.contains("bad \\| reason")); //$NON-NLS-1$
        assertFalse(section.contains("**Error:**")); //$NON-NLS-1$
    }
}
