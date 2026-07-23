/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link PushGitBranchTool}.
 * <p>
 * Covers tool metadata, the input/output schema contract (required remote+refspec =
 * the no-autonomous-push guard, force opt-in, optional username/token), the
 * {@code openWorldHint=true} annotation, the argument-validation guards that fire
 * BEFORE any repository access, and the pure helpers ({@link PushGitBranchTool#buildRefSpec},
 * {@link PushGitBranchTool#isSuccessStatus}, {@link PushGitBranchTool#describeUpdate} - the
 * rejected-push mapping). The real network push (a live remote, a bounded Job, mapping
 * JGit's {@code PushResult}) needs a live EDT workspace with a real git working tree and a
 * reachable remote and is exercised by a LIVE, ATTENDED e2e gate - never automated against
 * the CI fixture, whose repository is the EDT-MCP plugin's OWN clone (see
 * {@code test_push_git_branch.py}).
 */
public class PushGitBranchToolTest
{
    private static final String NONEXISTENT_PROJECT = "NoSuchProject_pgb_zzz"; //$NON-NLS-1$

    @Test
    public void testName()
    {
        assertEquals("push_git_branch", new PushGitBranchTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(PushGitBranchTool.NAME, new PushGitBranchTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new PushGitBranchTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new PushGitBranchTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('push_git_branch')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresParametersLowerCamelCaseAndRequired()
    {
        String schema = new PushGitBranchTool().getInputSchema();
        assertNotNull(schema);
        for (String param : new String[] {"projectName", "remote", "refspec", "force", "username", "token"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            assertTrue("schema must declare " + param, schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        String requiredBlock = schema.substring(open, close + 1);
        // The no-autonomous-push guard: remote AND refspec are required (no defaulting).
        for (String req : new String[] {"projectName", "remote", "refspec"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue(req + " must be required", requiredBlock.contains("\"" + req + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        for (String optional : new String[] {"force", "username", "token"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertFalse(optional + " must NOT be required", requiredBlock.contains("\"" + optional + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    @Test
    public void testOutputSchemaDeclaresTheResultEnvelope()
    {
        String schema = new PushGitBranchTool().getOutputSchema();
        assertNotNull(schema);
        for (String field : new String[] {"success", "remote", "resolvedRefspec", "forced", "pushed", "updates"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            assertTrue("outputSchema must declare " + field, schema.contains("\"" + field + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    // ==================== Annotations: openWorldHint=true, non-destructive write ====================

    @Test
    public void testAnnotationsMarkOpenWorldNonDestructive()
    {
        ToolAnnotations ann = new PushGitBranchTool().getAnnotations();
        assertNotNull("push_git_branch must override getAnnotations", ann); //$NON-NLS-1$
        assertEquals("a push reaches an external remote -> openWorldHint=true", //$NON-NLS-1$
            Boolean.TRUE, ann.getOpenWorldHint());
        assertEquals("a push is not read-only", Boolean.FALSE, ann.getReadOnlyHint()); //$NON-NLS-1$
        assertEquals("a push is a non-destructive write (NOT in the destructive-consent gate)", //$NON-NLS-1$
            Boolean.FALSE, ann.getDestructiveHint());
    }

    // ==================== Argument validation (returns before any repository/network access) =========

    @Test
    public void testMissingProjectNameIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("remote", "origin"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("refspec", "main"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new PushGitBranchTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingRemoteIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "SomeProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("refspec", "main"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new PushGitBranchTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name remote (the no-autonomous-push guard)", result.contains("remote")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a rejected call must not claim success", result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingRefspecIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "SomeProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("remote", "origin"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new PushGitBranchTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name refspec (the no-autonomous-push guard)", result.contains("refspec")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a rejected call must not claim success", result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEmptyParamsRejectsOnProjectNameFirst()
    {
        // requireArguments checks in order: projectName is checked before remote/refspec, so an
        // entirely-empty call must fail on projectName first.
        String result = new PushGitBranchTool().execute(new HashMap<>());
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonexistentProjectIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        params.put("remote", "origin"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("refspec", "main"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new PushGitBranchTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name the bad project", result.contains(NONEXISTENT_PROJECT)); //$NON-NLS-1$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a rejected call must not claim success", result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== buildRefSpec: user refspec -> concrete src:dst (pure) ====================

    @Test
    public void testBuildRefSpecExpandsShortBranchName()
    {
        assertEquals("refs/heads/feature/x:refs/heads/feature/x", //$NON-NLS-1$
            PushGitBranchTool.buildRefSpec("feature/x")); //$NON-NLS-1$
    }

    @Test
    public void testBuildRefSpecPassesExplicitSrcDstThrough()
    {
        assertEquals("refs/heads/a:refs/heads/b", //$NON-NLS-1$
            PushGitBranchTool.buildRefSpec("refs/heads/a:refs/heads/b")); //$NON-NLS-1$
    }

    @Test
    public void testBuildRefSpecMirrorsAFullRefWithoutColon()
    {
        assertEquals("refs/heads/main:refs/heads/main", //$NON-NLS-1$
            PushGitBranchTool.buildRefSpec("refs/heads/main")); //$NON-NLS-1$
    }

    @Test
    public void testBuildRefSpecTrimsWhitespace()
    {
        assertEquals("refs/heads/main:refs/heads/main", //$NON-NLS-1$
            PushGitBranchTool.buildRefSpec("  main  ")); //$NON-NLS-1$
    }

    // ==================== isSuccessStatus / describeUpdate: rejected-push mapping (pure) ==========

    @Test
    public void testOkAndUpToDateAreTheOnlySuccessStatuses()
    {
        assertTrue(PushGitBranchTool.isSuccessStatus(RemoteRefUpdate.Status.OK));
        assertTrue(PushGitBranchTool.isSuccessStatus(RemoteRefUpdate.Status.UP_TO_DATE));
    }

    @Test
    public void testRejectionStatusesAreNotSuccess()
    {
        assertFalse("a non-fast-forward rejection must NOT count as success", //$NON-NLS-1$
            PushGitBranchTool.isSuccessStatus(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD));
        assertFalse(PushGitBranchTool.isSuccessStatus(RemoteRefUpdate.Status.REJECTED_OTHER_REASON));
        assertFalse(PushGitBranchTool.isSuccessStatus(RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED));
        assertFalse(PushGitBranchTool.isSuccessStatus(RemoteRefUpdate.Status.NON_EXISTING));
        assertFalse(PushGitBranchTool.isSuccessStatus(RemoteRefUpdate.Status.AWAITING_REPORT));
        assertFalse(PushGitBranchTool.isSuccessStatus(RemoteRefUpdate.Status.NOT_ATTEMPTED));
    }

    @Test
    public void testNullStatusIsNotSuccess()
    {
        assertFalse(PushGitBranchTool.isSuccessStatus(null));
    }

    @Test
    public void testDescribeUpdateNamesNonFastForwardAndTheRef()
    {
        String described = PushGitBranchTool.describeUpdate(
            RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, "refs/heads/main", null); //$NON-NLS-1$
        assertTrue("must name the rejected ref", described.contains("refs/heads/main")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must explain the non-fast-forward cause", //$NON-NLS-1$
            described.toLowerCase().contains("non-fast-forward")); //$NON-NLS-1$
    }

    @Test
    public void testDescribeUpdateAppendsTheRemoteMessage()
    {
        String described = PushGitBranchTool.describeUpdate(
            RemoteRefUpdate.Status.REJECTED_OTHER_REASON, "refs/heads/main", "pre-receive hook declined"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must carry the remote-supplied message", //$NON-NLS-1$
            described.contains("pre-receive hook declined")); //$NON-NLS-1$
    }

    @Test
    public void testValidateRefspecRejectsWildcard()
    {
        // A wildcard that matched nothing would silently fall back to the remote's configured push
        // refspec (JGit Transport.push), pushing something the caller never asked for.
        String result = PushGitBranchTool.validateRefspec("refs/heads/*:refs/heads/*"); //$NON-NLS-1$
        assertNotNull("a wildcard refspec must be rejected", result); //$NON-NLS-1$
        assertTrue("rejection must be an error", result.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must explain the wildcard risk", result.toLowerCase().contains("wildcard")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testValidateRefspecRejectsForceMarker()
    {
        // A leading '+' encodes forceUpdate on the RefSpec; setForce(false) does not clear it, so a
        // '+' would force-overwrite remote history while force=false and the result claims forced=false.
        String result = PushGitBranchTool.validateRefspec("+refs/heads/main:refs/heads/main"); //$NON-NLS-1$
        assertNotNull("a '+' force-marker refspec must be rejected", result); //$NON-NLS-1$
        assertTrue("must steer to the explicit force parameter", result.contains("force")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testValidateRefspecRejectsMatchingColonForm()
    {
        // ':' has no literal '*' but JGit parses it as the matching form (push every matching branch).
        String result = PushGitBranchTool.validateRefspec(":"); //$NON-NLS-1$
        assertNotNull("the ':' matching form must be rejected", result); //$NON-NLS-1$
        assertTrue("rejection must be an error", result.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testValidateRefspecAcceptsPlainBranchAndExplicitRefspec()
    {
        assertNull("a plain branch name is accepted", PushGitBranchTool.validateRefspec("main")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("an explicit non-force src:dst is accepted", //$NON-NLS-1$
            PushGitBranchTool.validateRefspec("refs/heads/main:refs/heads/main")); //$NON-NLS-1$
    }
}
