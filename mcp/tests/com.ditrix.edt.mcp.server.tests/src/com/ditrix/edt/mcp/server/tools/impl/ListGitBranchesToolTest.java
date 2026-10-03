/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;

/**
 * Tests for {@link ListGitBranchesTool}.
 * <p>
 * Covers tool metadata, the input schema contract, and the argument-validation
 * guards that fire BEFORE any repository access. The real read path (resolving a
 * repository, listing branches, reading infobase bindings) needs a live EDT
 * workspace with a real git working tree and is covered by the e2e suite.
 * <p>
 * {@link ListGitBranchesTool#renderBranchTable} - the pure ref-name-shortening/current-branch-marking
 * table renderer - is covered directly below against hand-built {@link Ref}s
 * ({@link ObjectIdRef.Unpeeled}, a real public JGit type - NOT a mock; the object id itself is
 * irrelevant to this renderer, only {@link Ref#getName()} is read).
 */
public class ListGitBranchesToolTest
{
    private static final String NONEXISTENT_PROJECT = "NoSuchProject_lgb_zzz"; //$NON-NLS-1$

    private static Ref ref(String name)
    {
        return new ObjectIdRef.Unpeeled(Ref.Storage.LOOSE, name, ObjectId.zeroId());
    }

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

    // ==================== renderBranchTable: pure ref-name shortening / current-branch marking ====================

    @Test
    public void testRenderBranchTableEmptyRefsShowsNoBranchesFound()
    {
        String table = ListGitBranchesTool.renderBranchTable(Collections.emptyList(), null, true);
        assertTrue(table.contains("*No branches found.*")); //$NON-NLS-1$
    }

    @Test
    public void testRenderBranchTableMarksTheCurrentLocalBranch()
    {
        List<Ref> refs = Arrays.asList(ref("refs/heads/main"), ref("refs/heads/feature/x")); //$NON-NLS-1$ //$NON-NLS-2$
        String table = ListGitBranchesTool.renderBranchTable(refs, "refs/heads/main", false); //$NON-NLS-1$

        assertTrue("the current local branch's row must be marked Yes", //$NON-NLS-1$
            table.contains(MarkdownUtils.tableRow("main", "local", "Yes"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("a non-current local branch's Current cell must be empty", //$NON-NLS-1$
            table.contains(MarkdownUtils.tableRow("feature/x", "local", ""))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testRenderBranchTableDetachedHeadNeverMarksAnyRowCurrent()
    {
        // detached=true: even though the ref name equals fullBranch, isCurrent must stay false - the
        // detached-HEAD short SHA is reported separately above the table, not via this row flag.
        List<Ref> refs = Collections.singletonList(ref("refs/heads/main")); //$NON-NLS-1$
        String table = ListGitBranchesTool.renderBranchTable(refs, "refs/heads/main", true); //$NON-NLS-1$

        assertTrue(table.contains(MarkdownUtils.tableRow("main", "local", ""))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testRenderBranchTableShortensRemoteTrackingRefs()
    {
        List<Ref> refs = Collections.singletonList(ref("refs/remotes/origin/main")); //$NON-NLS-1$
        String table = ListGitBranchesTool.renderBranchTable(refs, null, true);

        assertTrue(table.contains(MarkdownUtils.tableRow("origin/main", "remote", ""))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testRenderBranchTableFallsBackToOtherForANonBranchRef()
    {
        // A tag (or any ref outside refs/heads//refs/remotes) keeps its FULL name and is typed "other".
        List<Ref> refs = Collections.singletonList(ref("refs/tags/v1")); //$NON-NLS-1$
        String table = ListGitBranchesTool.renderBranchTable(refs, null, true);

        assertTrue(table.contains(MarkdownUtils.tableRow("refs/tags/v1", "other", ""))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ==================== #684: binding rows name the branch, legacy keys are marked ====================

    /** Renders the bindings section for the given contexts, each bound to one infobase named after it. */
    private static String bindingsFor(InfobaseAssociationContext... contexts)
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        when(manager.getAssociationContexts(project)).thenReturn(Arrays.asList(contexts));
        IInfobaseAssociation association = mock(IInfobaseAssociation.class);
        InfobaseReference base = mock(InfobaseReference.class);
        when(base.getName()).thenReturn("Base1"); //$NON-NLS-1$
        when(association.getInfobases()).thenReturn(Collections.singletonList(base));
        when(manager.getAssociation(eq(project), any(InfobaseAssociationContext.class)))
            .thenReturn(Optional.of(association));
        return ListGitBranchesTool.renderBindings(manager, project);
    }

    /** The table row whose first cell is exactly {@code firstCell}, or {@code null}. */
    private static String rowStartingWith(String md, String firstCell)
    {
        for (String line : md.split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith("| " + firstCell + " |")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return line;
            }
        }
        return null;
    }

    @Test
    public void aFullBranchRefContextRendersTheShortBranchName()
    {
        String md = bindingsFor(InfobaseAssociationContext.of("refs/heads/feature/x")); //$NON-NLS-1$
        assertNotNull("the row must name the branch 'feature/x': " + md, rowStartingWith(md, "feature/x")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theBindingsSectionNeverPrintsTheRawHeadsPrefix()
    {
        String md = bindingsFor(InfobaseAssociationContext.of("refs/heads/feature/x"), //$NON-NLS-1$
            InfobaseAssociationContext.of("refs/heads/main")); //$NON-NLS-1$
        assertFalse("a branch context must be shown by its short name: " + md, md.contains("refs/heads/")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aFullBranchRefRowCarriesNoLegacyMarker()
    {
        String md = bindingsFor(InfobaseAssociationContext.of("refs/heads/feature/x")); //$NON-NLS-1$
        assertFalse("a real branch context is not legacy: " + md, md.contains("legacy")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aLegacyShortKeyIsMarkedAsMatchingNoBranch()
    {
        String md = bindingsFor(InfobaseAssociationContext.of("feature/y")); //$NON-NLS-1$
        String row = null;
        for (String line : md.split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith("| feature/y")) //$NON-NLS-1$
            {
                row = line;
            }
        }
        assertNotNull("the legacy key is still listed verbatim: " + md, row); //$NON-NLS-1$
        assertTrue("the legacy key must be marked: " + row, row.contains("legacy short key")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the marker must point at the cleanup: " + row, row.contains("set_branch_infobase")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("the legacy key must not look like a branch row: " + md, rowStartingWith(md, "feature/y")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aFullRefAndALegacyKeyOfTheSameBranchAreDistinguishable()
    {
        String md = bindingsFor(InfobaseAssociationContext.of("refs/heads/feature/x"), //$NON-NLS-1$
            InfobaseAssociationContext.of("feature/x")); //$NON-NLS-1$
        assertNotNull(md, rowStartingWith(md, "feature/x")); //$NON-NLS-1$
        assertTrue("the legacy twin must be marked: " + md, md.contains("feature/x (legacy short key")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aRemoteRefContextIsMarkedAsNotALocalBranch()
    {
        String md = bindingsFor(InfobaseAssociationContext.of("refs/remotes/origin/feature/x")); //$NON-NLS-1$
        assertTrue("a remote ref is shown as is, marked: " + md, //$NON-NLS-1$
            md.contains("refs/remotes/origin/feature/x (not a local branch ref")); //$NON-NLS-1$
    }

    @Test
    public void theDefaultContextRendersAsDefault()
    {
        String md = bindingsFor(InfobaseAssociationContext.empty());
        assertNotNull(md, rowStartingWith(md, "(default)")); //$NON-NLS-1$
    }

    @Test
    public void aCyrillicBranchRefRendersItsShortNameVerbatim()
    {
        String name = "задача/исправление"; //$NON-NLS-1$
        String md = bindingsFor(InfobaseAssociationContext.of("refs/heads/" + name)); //$NON-NLS-1$
        assertNotNull("the Cyrillic branch name must be shown verbatim: " + md, rowStartingWith(md, name)); //$NON-NLS-1$
    }
}
