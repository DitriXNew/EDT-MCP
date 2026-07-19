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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link SwitchGitBranchTool}.
 * <p>
 * Covers tool metadata, the input/output schema contract, and the argument-validation
 * guards that fire BEFORE any repository access. The real checkout path (resolving a
 * repository, pre-checks, running the bounded Job, mapping {@code CheckoutResult})
 * needs a live EDT workspace with a real git working tree and is covered by the e2e
 * suite — deliberately negatives-only there (no happy-path switch against the CI
 * fixture, which lives inside the EDT-MCP clone itself).
 */
public class SwitchGitBranchToolTest
{
    private static final String NONEXISTENT_PROJECT = "NoSuchProject_sgb_zzz"; //$NON-NLS-1$

    @Test
    public void testName()
    {
        assertEquals("switch_git_branch", new SwitchGitBranchTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SwitchGitBranchTool.NAME, new SwitchGitBranchTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new SwitchGitBranchTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new SwitchGitBranchTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('switch_git_branch')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresParametersLowerCamelCaseAndRequired()
    {
        String schema = new SwitchGitBranchTool().getInputSchema();
        assertNotNull(schema);
        for (String param : new String[] {"projectName", "branch"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue("schema must declare " + param, schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("branch must be required", requiredBlock.contains("\"branch\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresTheResultEnvelope()
    {
        String schema = new SwitchGitBranchTool().getOutputSchema();
        assertNotNull(schema);
        for (String field : new String[] {"success", "previousBranch", "branch", "bindings"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertTrue("outputSchema must declare " + field, schema.contains("\"" + field + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    // ==================== Argument validation (returns before any repository access) ====================

    @Test
    public void testMissingProjectNameIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("branch", "feature/x"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SwitchGitBranchTool().execute(params);
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
        String result = new SwitchGitBranchTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name branch", result.toLowerCase().contains("branch")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEmptyParamsRejectsOnProjectNameFirst()
    {
        // requireArguments checks in order: projectName is checked before branch,
        // so an entirely-empty call must fail on projectName first (not branch).
        String result = new SwitchGitBranchTool().execute(new HashMap<>());
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonexistentProjectIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        params.put("branch", "main"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SwitchGitBranchTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name the bad project", result.contains(NONEXISTENT_PROJECT)); //$NON-NLS-1$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a rejected call must not claim success", result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== joinBounded: pure list-to-string joiner (used in error messages) ====================

    @Test
    public void testJoinBoundedNullIsNone()
    {
        assertEquals("(none)", SwitchGitBranchTool.joinBounded(null)); //$NON-NLS-1$
    }

    @Test
    public void testJoinBoundedEmptyIsNone()
    {
        assertEquals("(none)", SwitchGitBranchTool.joinBounded(Collections.emptyList())); //$NON-NLS-1$
    }

    @Test
    public void testJoinBoundedJoinsAllPathsWhenUnderTheCap()
    {
        assertEquals("a, b, c", SwitchGitBranchTool.joinBounded(Arrays.asList("a", "b", "c"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testJoinBoundedCapsAtTwentyAndReportsTheRemainderCount()
    {
        List<String> paths = new ArrayList<>();
        for (int i = 1; i <= 25; i++)
        {
            paths.add("path" + i); //$NON-NLS-1$
        }

        String joined = SwitchGitBranchTool.joinBounded(paths);

        assertTrue("the first path must be listed", joined.startsWith("path1, path2")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("exactly the first 20 paths must be listed", joined.contains("path20")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the 21st path exceeds the cap and must NOT be listed", joined.contains("path21")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the remainder count must be reported", joined.endsWith(" ...and 5 more")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testJoinBoundedExactlyAtTheCapReportsNoRemainder()
    {
        List<String> paths = new ArrayList<>();
        for (int i = 1; i <= 20; i++)
        {
            paths.add("path" + i); //$NON-NLS-1$
        }

        String joined = SwitchGitBranchTool.joinBounded(paths);

        assertTrue(joined.contains("path20")); //$NON-NLS-1$
        assertFalse("exactly 20 paths need no '...and N more' suffix", joined.contains("...and")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
