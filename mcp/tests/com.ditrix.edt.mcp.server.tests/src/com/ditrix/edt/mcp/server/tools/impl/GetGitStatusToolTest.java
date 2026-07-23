/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;

/**
 * Tests for {@link GetGitStatusTool}.
 * <p>
 * Covers tool metadata, the input-schema contract, the output-schema contract (a
 * MARKDOWN tool declares NO {@code outputSchema}), the read-only classification, and
 * the argument-validation guards that fire BEFORE any repository access. The pure
 * {@link GetGitStatusTool#renderStatus} renderer - clean vs dirty porcelain sets - is
 * covered directly against hand-built path maps (no live git repo needed). The real
 * read path (resolving a repository, running {@code StatusCommand}) needs a live EDT
 * workspace with a real git working tree and is covered by the e2e suite.
 */
public class GetGitStatusToolTest
{
    private static final String NONEXISTENT_PROJECT = "NoSuchProject_ggs_zzz"; //$NON-NLS-1$

    private static Set<String> setOf(String... values)
    {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    /** An all-empty change-set map, in the tool's canonical report order. */
    private static Map<String, Set<String>> emptySets()
    {
        Map<String, Set<String>> sets = new LinkedHashMap<>();
        sets.put(GetGitStatusTool.STATE_ADDED, Collections.emptySet());
        sets.put(GetGitStatusTool.STATE_CHANGED, Collections.emptySet());
        sets.put(GetGitStatusTool.STATE_MODIFIED, Collections.emptySet());
        sets.put(GetGitStatusTool.STATE_MISSING, Collections.emptySet());
        sets.put(GetGitStatusTool.STATE_REMOVED, Collections.emptySet());
        sets.put(GetGitStatusTool.STATE_UNTRACKED, Collections.emptySet());
        sets.put(GetGitStatusTool.STATE_CONFLICTING, Collections.emptySet());
        return sets;
    }

    // ==================== Tool metadata / contract ====================

    @Test
    public void testName()
    {
        assertEquals("get_git_status", new GetGitStatusTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetGitStatusTool.NAME, new GetGitStatusTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetGitStatusTool().getResponseType());
    }

    @Test
    public void testMarkdownToolDeclaresNoOutputSchema()
    {
        // The output-schema contract: only JSON tools declare an outputSchema; a MARKDOWN
        // tool returns content (not structuredContent) and must leave it null.
        assertNull(new GetGitStatusTool().getOutputSchema());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new GetGitStatusTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('get_git_status')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresProjectNameLowerCamelCaseAndRequired()
    {
        String schema = new GetGitStatusTool().getInputSchema();
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
        String fileName = new GetGitStatusTool().getResultFileName(params);
        assertTrue(fileName.toLowerCase().contains("myconfig")); //$NON-NLS-1$
        assertTrue(fileName.endsWith(".md")); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameFallsBackWithoutProjectName()
    {
        String fileName = new GetGitStatusTool().getResultFileName(new HashMap<>());
        assertEquals("git-status.md", fileName); //$NON-NLS-1$
    }

    // ==================== Read-only classification ====================

    @Test
    public void testUsesTheCentralClassifier()
    {
        // getAnnotations() must return null so the central name-prefix classifier is used.
        assertNull("get_git_status relies on the get_ prefix classifier, not explicit annotations", //$NON-NLS-1$
            new GetGitStatusTool().getAnnotations());
    }

    @Test
    public void testGetPrefixIsClassifiedReadOnly()
    {
        ToolAnnotations annotations = ToolAnnotationClassifier.classify(GetGitStatusTool.NAME);
        assertEquals("the get_ prefix must classify the tool readOnlyHint=true", //$NON-NLS-1$
            Boolean.TRUE, annotations.getReadOnlyHint());
        assertNull("a read-only tool is not destructive", annotations.getDestructiveHint()); //$NON-NLS-1$
    }

    // ==================== Argument validation (returns before any repository access) ====================

    @Test
    public void testMissingProjectNameIsRejectedActionably()
    {
        String result = new GetGitStatusTool().execute(new HashMap<>());
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
        String result = new GetGitStatusTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name the bad project", result.contains(NONEXISTENT_PROJECT)); //$NON-NLS-1$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== renderStatus: pure clean/dirty porcelain rendering ====================

    @Test
    public void testRenderStatusCleanTreeReportsCleanYesAndNoTable()
    {
        String md = GetGitStatusTool.renderStatus("Cfg", "master", false, emptySets()); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("clean tree must report Clean: Yes", md.contains("**Clean:** Yes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("clean tree must show the clean note", //$NON-NLS-1$
            md.contains("*Working tree clean - nothing to commit.*")); //$NON-NLS-1$
        assertTrue("the current branch is reported", md.contains("**Current:** master")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a clean tree renders no change table", !md.contains("| Path | State |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderStatusDirtyTreePlacesEachPathInItsPorcelainSet()
    {
        Map<String, Set<String>> sets = emptySets();
        sets.put(GetGitStatusTool.STATE_MODIFIED, setOf("src/mod.bsl")); //$NON-NLS-1$
        sets.put(GetGitStatusTool.STATE_UNTRACKED, setOf("src/new.bsl")); //$NON-NLS-1$
        sets.put(GetGitStatusTool.STATE_CONFLICTING, setOf("src/clash.bsl")); //$NON-NLS-1$

        String md = GetGitStatusTool.renderStatus("Cfg", "feature/x", false, sets); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("dirty tree must report Clean: No", md.contains("**Clean:** No")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the change table header is present", md.contains("| Path | State |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("total change count is reported", md.contains("**Changed entries:** 3")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a modified file lands in the modified set", //$NON-NLS-1$
            md.contains(MarkdownUtils.tableRow("src/mod.bsl", "modified"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an untracked file lands in the untracked set", //$NON-NLS-1$
            md.contains(MarkdownUtils.tableRow("src/new.bsl", "untracked"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a conflicting file lands in the conflicting set", //$NON-NLS-1$
            md.contains(MarkdownUtils.tableRow("src/clash.bsl", "conflicting"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("no clean note on a dirty tree", //$NON-NLS-1$
            !md.contains("*Working tree clean")); //$NON-NLS-1$
    }

    @Test
    public void testRenderStatusDetachedHeadReportsTheCommit()
    {
        String md = GetGitStatusTool.renderStatus("Cfg", "abc1234", true, emptySets()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("detached HEAD is flagged with the commit", //$NON-NLS-1$
            md.contains("(detached HEAD at abc1234)")); //$NON-NLS-1$
    }
}
