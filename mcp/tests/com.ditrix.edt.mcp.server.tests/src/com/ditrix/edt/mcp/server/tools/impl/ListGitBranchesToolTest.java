/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link ListGitBranchesTool}.
 * <p>
 * Covers tool metadata, the input schema contract, and the argument-validation
 * guards that fire BEFORE any repository access. The real read path (resolving a
 * repository, listing branches, reading infobase bindings) needs a live EDT
 * workspace with a real git working tree and is covered by the e2e suite.
 */
public class ListGitBranchesToolTest
{
    private static final String NONEXISTENT_PROJECT = "NoSuchProject_lgb_zzz"; //$NON-NLS-1$

    @Test
    public void testName()
    {
        assertEquals("list_git_branches", new ListGitBranchesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ListGitBranchesTool.NAME, new ListGitBranchesTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new ListGitBranchesTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new ListGitBranchesTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('list_git_branches')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresProjectNameLowerCamelCaseAndRequired()
    {
        String schema = new ListGitBranchesTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare projectName", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        assertTrue("projectName must be required", //$NON-NLS-1$
            schema.substring(requiredIdx).contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameIncludesProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        String fileName = new ListGitBranchesTool().getResultFileName(params);
        assertTrue(fileName.toLowerCase().contains("myconfig")); //$NON-NLS-1$
        assertTrue(fileName.endsWith(".md")); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameFallsBackWithoutProjectName()
    {
        String fileName = new ListGitBranchesTool().getResultFileName(new HashMap<>());
        assertEquals("git-branches.md", fileName); //$NON-NLS-1$
    }

    // ==================== Argument validation (returns before any repository access) ====================

    @Test
    public void testMissingProjectNameIsRejectedActionably()
    {
        String result = new ListGitBranchesTool().execute(new HashMap<>());
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonexistentProjectIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        String result = new ListGitBranchesTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name the bad project", result.contains(NONEXISTENT_PROJECT)); //$NON-NLS-1$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
