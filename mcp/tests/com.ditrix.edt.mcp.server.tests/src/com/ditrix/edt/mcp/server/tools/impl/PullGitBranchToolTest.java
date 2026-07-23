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

import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link PullGitBranchTool}.
 * <p>
 * Covers tool metadata, the input/output schema contract, the MCP annotations
 * ({@code openWorldHint=true}), the argument-validation guards that fire BEFORE any
 * repository access (required {@code projectName}/{@code remote}/{@code branch} - none
 * of them defaulted), and the pure conflict-result mapping ({@link
 * PullGitBranchTool#conflictError} / {@link PullGitBranchTool#joinBounded}). The real
 * network path (resolving a repository, running the bounded Job, a live {@code
 * PullResult}) needs a live EDT workspace with a real remote and is a live/attended
 * e2e gate - deliberately not automated against the CI fixture (which lives inside the
 * EDT-MCP plugin's own git working tree).
 */
public class PullGitBranchToolTest
{
    private static final String NONEXISTENT_PROJECT = "NoSuchProject_pgb_zzz"; //$NON-NLS-1$

    @Test
    public void testName()
    {
        assertEquals("pull_git_branch", new PullGitBranchTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(PullGitBranchTool.NAME, new PullGitBranchTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new PullGitBranchTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new PullGitBranchTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('pull_git_branch')")); //$NON-NLS-1$
    }

    // ==================== MCP annotations ====================

    @Test
    public void testAnnotationsAdvertiseOpenWorld()
    {
        ToolAnnotations annotations = new PullGitBranchTool().getAnnotations();
        assertNotNull("pull_git_branch must override getAnnotations (reaches an external remote)", //$NON-NLS-1$
            annotations);
        assertEquals("a pull reaches an external world -> openWorldHint=true", //$NON-NLS-1$
            Boolean.TRUE, annotations.getOpenWorldHint());
        assertEquals("a pull is not read-only", Boolean.FALSE, annotations.getReadOnlyHint()); //$NON-NLS-1$
        assertEquals("a pull is recoverable, not destructive", Boolean.FALSE, //$NON-NLS-1$
            annotations.getDestructiveHint());
    }

    // ==================== Schema contract ====================

    @Test
    public void testInputSchemaDeclaresParametersLowerCamelCaseAndRequired()
    {
        String schema = new PullGitBranchTool().getInputSchema();
        assertNotNull(schema);
        for (String param : new String[] {"projectName", "remote", "branch", "rebase", "username", "token"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            assertTrue("schema must declare " + param, schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("remote must be required", requiredBlock.contains("\"remote\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("branch must be required", requiredBlock.contains("\"branch\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("rebase must NOT be required (default false)", requiredBlock.contains("\"rebase\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRebaseParameterIsDeclaredAsBoolean()
    {
        String schema = new PullGitBranchTool().getInputSchema();
        int rebaseIdx = schema.indexOf("\"rebase\""); //$NON-NLS-1$
        assertTrue("schema must declare rebase", rebaseIdx >= 0); //$NON-NLS-1$
        // the property block after "rebase" must type it as boolean
        int typeIdx = schema.indexOf("\"boolean\"", rebaseIdx); //$NON-NLS-1$
        assertTrue("rebase must be declared type boolean", typeIdx >= 0); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresTheResultEnvelope()
    {
        String schema = new PullGitBranchTool().getOutputSchema();
        assertNotNull(schema);
        for (String field : new String[] {"success", "remote", "branch", "rebase", "fetchedFrom", "status"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            assertTrue("outputSchema must declare " + field, schema.contains("\"" + field + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    // ==================== Argument validation (returns before any repository access) ====================

    @Test
    public void testMissingProjectNameIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("remote", "origin"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("branch", "main"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new PullGitBranchTool().execute(params);
        assertRejected(result);
        assertTrue("error must name projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingRemoteIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "SomeProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("branch", "main"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new PullGitBranchTool().execute(params);
        assertRejected(result);
        assertTrue("error must name remote", result.toLowerCase().contains("remote")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingBranchIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "SomeProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("remote", "origin"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new PullGitBranchTool().execute(params);
        assertRejected(result);
        assertTrue("error must name branch", result.toLowerCase().contains("branch")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEmptyParamsRejectsOnProjectNameFirst()
    {
        // requireArguments checks in order: projectName is checked before remote/branch,
        // so an entirely-empty call must fail on projectName first.
        String result = new PullGitBranchTool().execute(new HashMap<>());
        assertRejected(result);
        assertTrue("error must name projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonexistentProjectIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        params.put("remote", "origin"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("branch", "main"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new PullGitBranchTool().execute(params);
        assertRejected(result);
        assertTrue("error must name the bad project", result.contains(NONEXISTENT_PROJECT)); //$NON-NLS-1$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== conflictError: mapping a non-clean pull to an actionable error ====================

    @Test
    public void testConflictErrorMergeNamesTheRemoteBranchAndPaths()
    {
        String err = PullGitBranchTool.conflictError("origin", "main", false, //$NON-NLS-1$ //$NON-NLS-2$
            Arrays.asList("src/A.bsl", "src/B.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a conflict must NEVER be a success", err.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must be a failure result", err.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must name remote/branch", err.contains("origin/main")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("merge wording", err.toLowerCase().contains("merge")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must echo the conflicting paths", //$NON-NLS-1$
            err.contains("src/A.bsl") && err.contains("src/B.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testConflictErrorRebaseUsesRebaseRecoveryWording()
    {
        String err = PullGitBranchTool.conflictError("origin", "dev", true, //$NON-NLS-1$ //$NON-NLS-2$
            Collections.singletonList("src/C.bsl")); //$NON-NLS-1$
        assertTrue("error must be a failure result", err.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("rebase wording", err.toLowerCase().contains("rebase")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("rebase recovery mentions pausing/aborting", //$NON-NLS-1$
            err.toLowerCase().contains("abort") || err.toLowerCase().contains("paused")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must echo the conflicting path", err.contains("src/C.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testConflictErrorWithNoPathsStillReportsFailure()
    {
        String err = PullGitBranchTool.conflictError("origin", "main", false, Collections.emptyList()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must be a failure result", err.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("empty conflict list renders as (none)", err.contains("(none)")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== joinBounded: pure list-to-string joiner (used in error messages) ====================

    @Test
    public void testJoinBoundedNullIsNone()
    {
        assertEquals("(none)", PullGitBranchTool.joinBounded(null)); //$NON-NLS-1$
    }

    @Test
    public void testJoinBoundedEmptyIsNone()
    {
        assertEquals("(none)", PullGitBranchTool.joinBounded(Collections.emptyList())); //$NON-NLS-1$
    }

    @Test
    public void testJoinBoundedJoinsAllPathsWhenUnderTheCap()
    {
        assertEquals("a, b, c", PullGitBranchTool.joinBounded(Arrays.asList("a", "b", "c"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testJoinBoundedCapsAtTwentyAndReportsTheRemainderCount()
    {
        List<String> paths = new ArrayList<>();
        for (int i = 1; i <= 25; i++)
        {
            paths.add("path" + i); //$NON-NLS-1$
        }

        String joined = PullGitBranchTool.joinBounded(paths);

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

        String joined = PullGitBranchTool.joinBounded(paths);

        assertTrue(joined.contains("path20")); //$NON-NLS-1$
        assertFalse("exactly 20 paths need no '...and N more' suffix", joined.contains("...and")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRebaseUncommittedChangesIsNotReportedAsAConflict()
    {
        // A rebase refused for uncommitted changes has NO conflicting paths - it must be reported as its
        // real status with a commit/stash remedy, never as a conflict ("conflicts: (none)").
        String result = PullGitBranchTool.rebaseFailure("origin", "main", //$NON-NLS-1$ //$NON-NLS-2$
            org.eclipse.jgit.api.RebaseResult.Status.UNCOMMITTED_CHANGES, null,
            java.util.Arrays.asList("a.bsl", "b.bsl"), null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must name the real status", result.contains("UNCOMMITTED_CHANGES")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must steer to commit/stash", result.toLowerCase().contains("stash")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must list the offending paths", result.contains("a.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a non-conflict failure must not claim conflicting paths", //$NON-NLS-1$
            result.toLowerCase().contains("conflicting paths")); //$NON-NLS-1$
    }

    @Test
    public void testRebaseStoppedIsReportedAsAConflict()
    {
        String result = PullGitBranchTool.rebaseFailure("origin", "main", //$NON-NLS-1$ //$NON-NLS-2$
            org.eclipse.jgit.api.RebaseResult.Status.STOPPED, java.util.Arrays.asList("x.bsl"), null, null); //$NON-NLS-1$
        assertTrue("a stopped rebase is a conflict", result.toLowerCase().contains("conflict")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must list the conflict path", result.contains("x.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMergeCheckoutConflictIsNotCalledAMergeConflict()
    {
        // CHECKOUT_CONFLICT means local changes would be overwritten - NOT conflict markers to resolve.
        String result = PullGitBranchTool.mergeFailure("origin", "main", //$NON-NLS-1$ //$NON-NLS-2$
            org.eclipse.jgit.api.MergeResult.MergeStatus.CHECKOUT_CONFLICT, null,
            java.util.Arrays.asList("c.bsl"), null); //$NON-NLS-1$
        assertTrue("must name the real status", result.contains("CHECKOUT_CONFLICT")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must steer to stash/overwrite remedy", //$NON-NLS-1$
            result.toLowerCase().contains("overwritten") || result.toLowerCase().contains("stash")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must list the blocking path", result.contains("c.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIncompleteMergeStatusesAreDetected()
    {
        // Squashed / not-committed merges are "successful" in JGit but leave no integration commit.
        assertTrue(PullGitBranchTool.isIncompleteMerge(
            org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED_NOT_COMMITTED));
        assertTrue(PullGitBranchTool.isIncompleteMerge(
            org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED_SQUASHED));
        assertTrue(PullGitBranchTool.isIncompleteMerge(
            org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED_SQUASHED_NOT_COMMITTED));
        assertTrue(PullGitBranchTool.isIncompleteMerge(
            org.eclipse.jgit.api.MergeResult.MergeStatus.FAST_FORWARD_SQUASHED));
        // A normal completed integration is NOT flagged.
        assertFalse(PullGitBranchTool.isIncompleteMerge(org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED));
        assertFalse(PullGitBranchTool.isIncompleteMerge(org.eclipse.jgit.api.MergeResult.MergeStatus.FAST_FORWARD));
        assertFalse(PullGitBranchTool.isIncompleteMerge(
            org.eclipse.jgit.api.MergeResult.MergeStatus.ALREADY_UP_TO_DATE));
    }

    @Test
    public void testIncompleteMergeErrorSteersToCommitBeforePush()
    {
        String result = PullGitBranchTool.incompleteMergeError("origin", "main", "MERGED_SQUASHED"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("must be an error", result.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must not claim success", result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must name the status", result.contains("MERGED_SQUASHED")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must steer to commit", result.toLowerCase().contains("commit")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStashApplyConflictIsAnErrorNotSuccess()
    {
        // STASH_APPLY_CONFLICTS has PullResult.isSuccessful()==true, so it must be caught specially or the
        // tool would report success with unresolved conflicts from the re-applied autostash.
        String result = PullGitBranchTool.stashApplyConflictError("origin", "main"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must be an error", result.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("must not claim success", result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must name the status", result.contains("STASH_APPLY_CONFLICTS")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMergeFailedSteersToResolvePaths()
    {
        String result = PullGitBranchTool.mergeFailure("origin", "main", //$NON-NLS-1$ //$NON-NLS-2$
            org.eclipse.jgit.api.MergeResult.MergeStatus.FAILED, null, null, java.util.Arrays.asList("d.bsl")); //$NON-NLS-1$
        assertTrue("must name the status", result.contains("FAILED")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must list the failing path", result.contains("d.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertRejected(String result)
    {
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertFalse("a rejected call must not claim success", result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
