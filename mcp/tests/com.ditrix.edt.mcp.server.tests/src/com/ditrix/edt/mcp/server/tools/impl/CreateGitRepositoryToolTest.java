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

import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link CreateGitRepositoryTool}.
 * <p>
 * Covers tool metadata, the input/output schema contract, the openWorldHint
 * annotation (clone reaches the network), and the argument-validation guards that
 * fire BEFORE any repository access, plus the honest-partial message shapes. The
 * real init/clone path (a live {@code git init} + EGit share, or a network clone)
 * needs a live EDT workspace with a real filesystem project and is a LIVE, ATTENDED
 * gate on a throwaway stand - the e2e suite is deliberately negatives-only (the CI
 * fixture project lives inside the plugin's OWN git tree, so an init on it is a
 * rejected no-op, and a happy-path init would litter that tree).
 */
public class CreateGitRepositoryToolTest
{
    private static final String NONEXISTENT_PROJECT = "NoSuchProject_cgr_zzz"; //$NON-NLS-1$

    @Test
    public void testName()
    {
        assertEquals("create_git_repository", new CreateGitRepositoryTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateGitRepositoryTool.NAME, new CreateGitRepositoryTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new CreateGitRepositoryTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new CreateGitRepositoryTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_git_repository')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresParametersLowerCamelCaseAndRequired()
    {
        String schema = new CreateGitRepositoryTool().getInputSchema();
        assertNotNull(schema);
        for (String param : new String[] {"projectName", "url", "targetPath", "remoteName", "initialBranch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "username", "token"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue("schema must declare " + param, schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        for (String optional : new String[] {"url", "targetPath", "remoteName", "initialBranch", "username", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "token"}) //$NON-NLS-1$
        {
            assertFalse(optional + " must NOT be required", requiredBlock.contains("\"" + optional + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    @Test
    public void testOutputSchemaDeclaresTheResultEnvelope()
    {
        String schema = new CreateGitRepositoryTool().getOutputSchema();
        assertNotNull(schema);
        for (String field : new String[] {"success", "mode", "project", "repositoryPath", "shared"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertTrue("outputSchema must declare " + field, schema.contains("\"" + field + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    @Test
    public void testAnnotationsAdvertiseOpenWorldNonReadOnlyNonDestructive()
    {
        // Clone mode reaches the network -> openWorldHint=true; it is still a (non-destructive) write.
        ToolAnnotations a = new CreateGitRepositoryTool().getAnnotations();
        assertNotNull("must override getAnnotations()", a); //$NON-NLS-1$
        assertEquals("openWorldHint must be true (clone reaches the network)", Boolean.TRUE, //$NON-NLS-1$
            a.getOpenWorldHint());
        assertEquals("must not be read-only", Boolean.FALSE, a.getReadOnlyHint()); //$NON-NLS-1$
        assertEquals("must not be destructive", Boolean.FALSE, a.getDestructiveHint()); //$NON-NLS-1$
    }

    @Test
    public void testDoesNotConnectToInfobase()
    {
        assertFalse("git bootstrap never opens an infobase connection", //$NON-NLS-1$
            new CreateGitRepositoryTool().connectsToInfobase());
    }

    // ==================== Argument validation (returns before any repository access) ====================

    @Test
    public void testMissingProjectNameIsRejectedActionably()
    {
        String result = new CreateGitRepositoryTool().execute(new HashMap<>());
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonexistentProjectInitModeIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        String result = new CreateGitRepositoryTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name the bad project", result.contains(NONEXISTENT_PROJECT)); //$NON-NLS-1$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a rejected call must not claim success", result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCloneWithoutTargetPathIsRejectedActionably()
    {
        // url present -> clone mode; targetPath missing must be rejected before any network access,
        // naming targetPath. (No workspace/network is touched by this path.)
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "SomeProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("url", "https://example.invalid/repo.git"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateGitRepositoryTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name targetPath", result.contains("targetPath")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a rejected call must not claim success", result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Honest-partial message shapes (pure, no workspace) ====================

    @Test
    public void testShareFailureWarningIsHonestAndActionable()
    {
        String w = CreateGitRepositoryTool.shareFailureWarning("provider unavailable"); //$NON-NLS-1$
        assertNotNull(w);
        assertTrue("must state the repository was still created", //$NON-NLS-1$
            w.toLowerCase().contains("repository was created")); //$NON-NLS-1$
        assertTrue("must carry the underlying reason", w.contains("provider unavailable")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must point at manual sharing", w.contains("Share Project")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCloneFailureMessageNamesUrlRootCauseAndAuthHint()
    {
        Throwable wrapped = new RuntimeException("outer", new RuntimeException("auth not supported")); //$NON-NLS-1$ //$NON-NLS-2$
        String m = CreateGitRepositoryTool.cloneFailureMessage("https://host/x.git", wrapped); //$NON-NLS-1$
        assertTrue("must name the url", m.contains("https://host/x.git")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must surface the ROOT cause message", m.contains("auth not supported")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must not surface the wrapper message", m.contains("outer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must give the SSH/HTTPS auth hint", m.contains("ssh-agent") && m.contains("token")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testRootMessageFallsBackToClassNameWhenNoMessage()
    {
        assertEquals("IllegalStateException", //$NON-NLS-1$
            CreateGitRepositoryTool.rootMessage(new IllegalStateException()));
    }
}
