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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link CreateGitBranchTool}.
 * <p>
 * Covers tool metadata, the input/output schema contract, and the argument-validation
 * guards that fire BEFORE any repository access. The real creation path (the
 * {@code findRef} already-exists pre-check, {@code Git.branchCreate()}, the optional
 * checkout/binding) needs a live EDT workspace with a real git working tree and is
 * covered by the e2e suite - deliberately negatives-only there (a happy-path create
 * would litter the plugin's own git repository, which is the CI fixture's backing
 * repo - see {@code list_git_branches}/{@code switch_git_branch}'s test modules).
 */
public class CreateGitBranchToolTest
{
    private static final String NONEXISTENT_PROJECT = "NoSuchProject_cgb_zzz"; //$NON-NLS-1$

    @Test
    public void testName()
    {
        assertEquals("create_git_branch", new CreateGitBranchTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateGitBranchTool.NAME, new CreateGitBranchTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new CreateGitBranchTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new CreateGitBranchTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_git_branch')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresParametersLowerCamelCaseAndRequired()
    {
        String schema = new CreateGitBranchTool().getInputSchema();
        assertNotNull(schema);
        for (String param : new String[] {"projectName", "branch", "startPoint", "checkout", "applicationId", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "setDefault"}) //$NON-NLS-1$
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
        for (String optional : new String[] {"startPoint", "checkout", "applicationId", "setDefault"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertFalse(optional + " must NOT be required", requiredBlock.contains("\"" + optional + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    @Test
    public void testOutputSchemaDeclaresTheResultEnvelope()
    {
        String schema = new CreateGitBranchTool().getOutputSchema();
        assertNotNull(schema);
        for (String field : new String[] {"success", "branch", "created", "checkedOut", "startPoint", "bound"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
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
        String result = new CreateGitBranchTool().execute(params);
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
        String result = new CreateGitBranchTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name branch", result.toLowerCase().contains("branch")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEmptyParamsRejectsOnProjectNameFirst()
    {
        // requireArguments checks in order: projectName is checked before branch, so an
        // entirely-empty call must fail on projectName first (not branch).
        String result = new CreateGitBranchTool().execute(new HashMap<>());
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name projectName", result.contains("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNonexistentProjectIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        params.put("branch", "feature/x"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateGitBranchTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("error must name the bad project", result.contains(NONEXISTENT_PROJECT)); //$NON-NLS-1$
        assertTrue("error must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a rejected call must not claim success", result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== #684: the binding at creation is keyed by the created FULL ref ====================

    private static final String SHORT = "feature/x"; //$NON-NLS-1$

    private static final String FULL = "refs/heads/feature/x"; //$NON-NLS-1$

    private static InfobaseReference infobase(String name)
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn(name);
        return ref;
    }

    private static InfobaseAssociationContext attachedContext(IInfobaseAssociationManager manager, IProject project,
        InfobaseReference ref)
    {
        ArgumentCaptor<InfobaseAssociationSettings> settings = ArgumentCaptor.forClass(InfobaseAssociationSettings.class);
        verify(manager).associate(eq(project), eq(ref), settings.capture());
        return settings.getValue().getContext();
    }

    @Test
    public void theBindingAtCreationUsesTheFullBranchRef()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        List<String> warnings = new ArrayList<>();

        CreateGitBranchTool.bindResolved(manager, project, SHORT, FULL, "app1", ref, false, //$NON-NLS-1$
            ToolResult.success(), warnings);

        assertTrue("no warning expected: " + warnings, warnings.isEmpty()); //$NON-NLS-1$
        assertEquals(InfobaseAssociationContext.of(FULL), attachedContext(manager, project, ref));
    }

    @Test
    public void theBindingAtCreationNeverUsesTheShortKey()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        CreateGitBranchTool.bindResolved(manager, project, SHORT, FULL, "app1", ref, true, //$NON-NLS-1$
            ToolResult.success(), new ArrayList<>());

        InfobaseAssociationContext shortKey = InfobaseAssociationContext.of(SHORT);
        verify(manager, never()).associate(any(), any(), argThat(s -> s != null && shortKey.equals(s.getContext())));
        verify(manager, never()).setDefaultInfobase(any(), any(), eq(shortKey));
    }

    @Test
    public void setDefaultAtCreationUsesTheFullBranchRef()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        CreateGitBranchTool.bindResolved(manager, project, SHORT, FULL, "app1", ref, true, //$NON-NLS-1$
            ToolResult.success(), new ArrayList<>());

        verify(manager).setDefaultInfobase(project, ref, InfobaseAssociationContext.of(FULL));
    }

    @Test
    public void theBindingFollowsTheRefThatWasActuallyCreated()
    {
        // JGit creates refs/heads/<name> for ANY name, so a caller passing 'refs/heads/x' gets the branch
        // refs/heads/refs/heads/x - the binding must land on THAT ref, not on a different branch 'x'.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        CreateGitBranchTool.bindResolved(manager, project, "refs/heads/x", "refs/heads/refs/heads/x", "app1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ref, false, ToolResult.success(), new ArrayList<>());

        assertEquals(InfobaseAssociationContext.of("refs/heads/refs/heads/x"), //$NON-NLS-1$
            attachedContext(manager, project, ref));
    }

    @Test
    public void theBindingAtCreationReadsBackTheFullBranchRef()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("ReadBackBase"); //$NON-NLS-1$
        IInfobaseAssociation association = mock(IInfobaseAssociation.class);
        when(association.getInfobases()).thenReturn(Arrays.asList(ref));
        when(manager.getAssociation(project, InfobaseAssociationContext.of(FULL))).thenReturn(Optional.of(association));
        ToolResult ok = ToolResult.success();

        CreateGitBranchTool.bindResolved(manager, project, SHORT, FULL, "app1", ref, false, ok, //$NON-NLS-1$
            new ArrayList<>());

        String json = ok.toJson();
        assertTrue("the read-back must come from the full-ref context: " + json, //$NON-NLS-1$
            json.contains("\"infobases\":[\"ReadBackBase\"]")); //$NON-NLS-1$
    }

    // ==================== #684 gate: a default EDT refuses after a done attach ====================
    //
    // EDT records a default only for an infobase bound to the CHECKED-OUT branch; with checkout=false the
    // new branch is not checked out, so setDefaultInfobase throws IllegalArgumentException after the attach.

    private static ToolResult bindWithRefusedDefault(RuntimeException refusal, InfobaseReference ref,
        List<String> warnings, IInfobaseAssociationManager manager, IProject project)
    {
        IInfobaseAssociation association = mock(IInfobaseAssociation.class);
        when(association.getInfobases()).thenReturn(Arrays.asList(ref));
        when(manager.getAssociation(project, InfobaseAssociationContext.of(FULL))).thenReturn(Optional.of(association));
        doThrow(refusal).when(manager).setDefaultInfobase(any(), any(), any());
        ToolResult ok = ToolResult.success();
        CreateGitBranchTool.bindResolved(manager, project, SHORT, FULL, "app1", ref, true, ok, warnings); //$NON-NLS-1$
        return ok;
    }

    @Test
    public void aDefaultEdtRefusesIsAWarningAndTheAttachIsStillReadBack()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        List<String> warnings = new ArrayList<>();

        ToolResult ok = bindWithRefusedDefault(
            new IllegalArgumentException("Project P is not associated with infobase Base1"), ref, warnings, //$NON-NLS-1$
            manager, project);

        assertEquals(InfobaseAssociationContext.of(FULL), attachedContext(manager, project, ref));
        assertEquals("exactly one warning, about the default: " + warnings, 1, warnings.size()); //$NON-NLS-1$
        assertTrue(warnings.get(0), warnings.get(0).contains(
            "but EDT did not record it as the branch's default infobase (EDT: Project P is not associated " //$NON-NLS-1$
                + "with infobase Base1)")); //$NON-NLS-1$
        String json = ok.toJson();
        assertTrue("the attach stands, so its read-back is reported: " + json, //$NON-NLS-1$
            json.contains("\"infobases\":[\"Base1\"]")); //$NON-NLS-1$
    }

    @Test
    public void aDefaultRefusedWithAnAssociationErrorAtCreationIsNotAFailedAttach()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        List<String> warnings = new ArrayList<>();

        ToolResult ok = bindWithRefusedDefault(new InfobaseAssociationException("store failed"), ref, warnings, //$NON-NLS-1$
            manager, project);

        assertEquals(warnings.toString(), 1, warnings.size());
        assertFalse("the attach did not fail: " + warnings, warnings.get(0).contains("attaching application")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(warnings.get(0), warnings.get(0).contains(
            "but EDT failed to store it as the branch's default infobase: store failed.")); //$NON-NLS-1$
        assertFalse("a store failure is not cured by a checkout, so no checkout advice: " + warnings, //$NON-NLS-1$
            warnings.get(0).contains("switch_git_branch")); //$NON-NLS-1$
        assertFalse("nor the refusal's explanation: " + warnings, warnings.get(0).contains("CHECKED-OUT")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(ok.toJson(), ok.toJson().contains("\"bound\"")); //$NON-NLS-1$
    }
}
