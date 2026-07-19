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
 * Tests for {@link SetBranchInfobaseTool}.
 * <p>
 * Covers tool metadata, the input/output schema contract, and the argument-validation
 * guards that fire BEFORE any repository/association-manager access - including the
 * {@code action} enum guard, which (unlike project/repository resolution) is a pure
 * check reachable headlessly even against a made-up project name. The real bind/detach
 * path (application resolution, {@code IInfobaseAssociationManager} calls) needs a
 * live EDT workspace and is covered by the e2e suite.
 */
public class SetBranchInfobaseToolTest
{
    private static final String NONEXISTENT_PROJECT = "NoSuchProject_sbi_zzz"; //$NON-NLS-1$

    @Test
    public void testName()
    {
        assertEquals("set_branch_infobase", new SetBranchInfobaseTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetBranchInfobaseTool.NAME, new SetBranchInfobaseTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new SetBranchInfobaseTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new SetBranchInfobaseTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('set_branch_infobase')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresParametersLowerCamelCaseAndRequired()
    {
        String schema = new SetBranchInfobaseTool().getInputSchema();
        assertNotNull(schema);
        for (String param : new String[] {"projectName", "branch", "applicationId", "action", "setDefault"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertTrue("schema must declare " + param, schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        String requiredBlock = schema.substring(open, close + 1);
        for (String required : new String[] {"projectName", "branch", "applicationId"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue(required + " must be required", requiredBlock.contains("\"" + required + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        assertTrue("action must NOT be required (it defaults to attach)", //$NON-NLS-1$
            !requiredBlock.contains("\"action\"")); //$NON-NLS-1$
        assertTrue("setDefault must NOT be required (it defaults to false)", //$NON-NLS-1$
            !requiredBlock.contains("\"setDefault\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresTheResultEnvelope()
    {
        String schema = new SetBranchInfobaseTool().getOutputSchema();
        assertNotNull(schema);
        for (String field : new String[] {"success", "action", "branch", "applicationId", "bound"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertTrue("outputSchema must declare " + field, schema.contains("\"" + field + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    // ==================== Argument validation (returns before any repository/manager access) ====================

    @Test
    public void testMissingProjectNameIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("branch", "feature/x"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBranchInfobaseTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingBranchIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "SomeProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBranchInfobaseTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name branch", result.toLowerCase().contains("branch")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingApplicationIdIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "SomeProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("branch", "feature/x"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBranchInfobaseTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name applicationId", result.contains("applicationId")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must steer to get_applications", result.contains("get_applications")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidActionIsRejectedActionablyBeforeAnyRepositoryAccess()
    {
        // The action-enum guard is a pure check that fires BEFORE GitRepositoryResolver, so it is
        // reachable headlessly even against a made-up project name (no live EDT needed).
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        params.put("branch", "feature/x"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("action", "bogus"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBranchInfobaseTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name the bad action value", result.contains("bogus")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must list the allowed actions", //$NON-NLS-1$
            result.contains("attach") && result.contains("detach")); //$NON-NLS-1$ //$NON-NLS-2$
        // Must NOT mention the (never-reached) nonexistent-project resolution.
        assertTrue("the action guard must fire before project resolution", //$NON-NLS-1$
            !result.contains(NONEXISTENT_PROJECT));
    }

    @Test
    public void testNonexistentProjectIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        params.put("branch", "feature/x"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBranchInfobaseTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name the bad project", result.contains(NONEXISTENT_PROJECT)); //$NON-NLS-1$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== validateAction: every value that must PASS the pure guard ====================
    //
    // The "bogus" case above covers the guard's rejection branch; these cover every acceptance branch
    // (null / empty / 'attach' case-insensitively / 'detach') by observing that each one reaches PAST the
    // action guard to project resolution instead of being rejected as an invalid action - reachable
    // headlessly, no live service needed, same technique the existing pre-check tests already use.

    @Test
    public void testMissingActionDefaultsToAttachAndReachesProjectResolution()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        params.put("branch", "feature/x"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        // action omitted entirely.
        String result = new SetBranchInfobaseTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertFalse("a missing action must NOT be rejected as invalid", //$NON-NLS-1$
            result.contains("Invalid action")); //$NON-NLS-1$
        assertTrue("the guard must pass through to project resolution", //$NON-NLS-1$
            result.contains(NONEXISTENT_PROJECT));
    }

    @Test
    public void testEmptyActionDefaultsToAttachAndReachesProjectResolution()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        params.put("branch", "feature/x"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("action", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBranchInfobaseTool().execute(params);
        assertFalse("an empty action must NOT be rejected as invalid", //$NON-NLS-1$
            result.contains("Invalid action")); //$NON-NLS-1$
        assertTrue(result.contains(NONEXISTENT_PROJECT));
    }

    @Test
    public void testAttachActionIsAcceptedCaseInsensitively()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        params.put("branch", "feature/x"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("action", "ATTACH"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBranchInfobaseTool().execute(params);
        assertFalse("'ATTACH' (any case) must NOT be rejected as invalid", //$NON-NLS-1$
            result.contains("Invalid action")); //$NON-NLS-1$
        assertTrue(result.contains(NONEXISTENT_PROJECT));
    }

    @Test
    public void testDetachActionIsAccepted()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        params.put("branch", "feature/x"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("action", "detach"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBranchInfobaseTool().execute(params);
        assertFalse("'detach' must NOT be rejected as invalid", result.contains("Invalid action")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.contains(NONEXISTENT_PROJECT));
    }
}
