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

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetApplicationsTool}.
 * <p>
 * Covers tool metadata, the input and output schemas, and the pure
 * {@code projectName} required-argument validation that returns before the
 * first {@code ProjectStateChecker}/{@code ResourcesPlugin} access. The EDT
 * boundary is {@code execute()}'s call to
 * {@code ProjectStateChecker.buildingErrorOrNull(projectName)}, which touches
 * {@code ResourcesPlugin.getWorkspace()}; only the missing/blank-projectName
 * branch returns before that. Enumerating applications needs a live project
 * model and the {@code IApplicationManager} service and is covered by the E2E
 * suite.
 */
public class GetApplicationsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_applications", new GetApplicationsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetApplicationsTool.NAME, new GetApplicationsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetApplicationsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetApplicationsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testResponseTypeJsonConstantSurface()
    {
        // The tool overrides getResponseType() explicitly to JSON; pin it so a future
        // edit cannot silently drop to the MARKDOWN default of IMcpTool.
        assertEquals(ResponseType.JSON, new GetApplicationsTool().getResponseType());
    }

    @Test
    public void testConnectsToInfobaseIsTrue()
    {
        // #270: the update-state read-back (appManager.getUpdateState) is the async
        // recompute that historically raised the auth dialog (#230's history) — it must
        // arm the auth-dialog suppressor's activity window.
        assertTrue(new GetApplicationsTool().connectsToInfobase());
    }

    @Test
    public void testDescriptionMentionsConsumingTools()
    {
        // The description must steer callers to the tools that consume the application id.
        String desc = new GetApplicationsTool().getDescription();
        assertTrue("description must point at update_database", desc.contains("update_database")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description must point at debug_launch", desc.contains("debug_launch")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetApplicationsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaMarksProjectNameRequired()
    {
        // projectName is the only input and it is mandatory.
        String schema = new GetApplicationsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresAllFields()
    {
        String schema = new GetApplicationsTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare project", schema.contains("\"project\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare applications", schema.contains("\"applications\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare count", schema.contains("\"count\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare message", schema.contains("\"message\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare defaultApplicationId", //$NON-NLS-1$
            schema.contains("\"defaultApplicationId\"")); //$NON-NLS-1$
        // inheritedFromProject is emitted only when applications are resolved via the base
        // project of a dependent (external-objects/extension) project; the schema must still
        // declare it so the wire contract advertises the field for every consumer.
        assertTrue("outputSchema must declare inheritedFromProject", //$NON-NLS-1$
            schema.contains("\"inheritedFromProject\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetApplicationsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameCarriesDiscoveryHint()
    {
        // The shared required-argument guard appends the list_projects discovery hint.
        String result = new GetApplicationsTool().execute(new HashMap<>());
        assertTrue("missing projectName must steer to list_projects", //$NON-NLS-1$
            result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testBlankProjectNameIsAlsoMissing()
    {
        // An empty value is treated as absent by requireArgument and hits the same branch
        // BEFORE any ResourcesPlugin/workspace access.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetApplicationsTool().execute(params);
        assertTrue("blank projectName must hit the required branch", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameIsStructuredErrorEnvelope()
    {
        // The validation error must be the structured {"success":false,...} envelope
        // recognised by the protocol's error diversion, not a partial success body.
        String result = new GetApplicationsTool().execute(new HashMap<>());
        assertTrue("error must be a structured failure envelope", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertFalse("a validation error must not emit a success envelope", //$NON-NLS-1$
            result.contains("\"success\":true")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameOmitsInheritedFromProject()
    {
        // inheritedFromProject is a marker emitted ONLY on the base-project (inherited)
        // branch of a dependent project. A pure argument-error envelope returns before any
        // application lookup, so the marker must be absent here.
        String result = new GetApplicationsTool().execute(new HashMap<>());
        assertFalse("a validation error must not emit inheritedFromProject", //$NON-NLS-1$
            result.contains("inheritedFromProject")); //$NON-NLS-1$
    }
}
