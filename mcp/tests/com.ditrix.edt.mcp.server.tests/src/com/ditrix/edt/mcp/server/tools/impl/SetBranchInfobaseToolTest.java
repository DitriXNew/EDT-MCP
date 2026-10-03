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

import java.util.Arrays;
import java.util.Collection;
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
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link SetBranchInfobaseTool}.
 * <p>
 * Covers tool metadata, the input/output schema contract, and the argument-validation
 * guards that fire BEFORE any repository/association-manager access - including the
 * {@code action} enum guard, which (unlike project/repository resolution) is a pure
 * check reachable headlessly even against a made-up project name. Application resolution
 * needs a live EDT workspace (e2e suite); the binding change itself - which context the
 * {@code IInfobaseAssociationManager} calls use (#684) - is pinned below through
 * {@code applyResolved} against a mocked manager.
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
    public void testOutputSchemaDeclaresTheMessageTheToolReturns()
    {
        // #684: a legacy-key detach and a refused default answer with 'message'; an undeclared output key
        // is invisible to a schema-driven client.
        String schema = new SetBranchInfobaseTool().getOutputSchema();
        assertTrue("outputSchema must declare message: " + schema, schema.contains("\"message\"")); //$NON-NLS-1$ //$NON-NLS-2$
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

    // ==================== #684: the binding key is the FULL ref EDT itself reads ====================
    //
    // EDT keys a branch context by Repository.getFullBranch() ('refs/heads/<branch>') and compares
    // contexts literally. A binding written under the short name is never the current context.

    private static final String SHORT = "feature/x"; //$NON-NLS-1$

    private static final String FULL = "refs/heads/feature/x"; //$NON-NLS-1$

    /** A real Cyrillic two-segment branch name (data, kept verbatim). */
    private static final String CYRILLIC_SHORT =
        "задача/исправление"; //$NON-NLS-1$

    private static InfobaseReference infobase(String name)
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn(name);
        return ref;
    }

    /**
     * A plain (non-mock) association, so it can be built inside a {@code thenReturn(...)} argument
     * without nesting Mockito stubbing.
     */
    private static Optional<IInfobaseAssociation> association(InfobaseReference... infobases)
    {
        List<InfobaseReference> bound = Arrays.asList(infobases);
        return Optional.of(new IInfobaseAssociation()
        {
            @Override
            public IProject getProject()
            {
                return null;
            }

            @Override
            public Collection<InfobaseReference> getInfobases()
            {
                return bound;
            }

            @Override
            public InfobaseReference getDefaultInfobase()
            {
                return null;
            }
        });
    }

    private static InfobaseAssociationContext attachedContext(IInfobaseAssociationManager manager, IProject project,
        InfobaseReference ref)
    {
        ArgumentCaptor<InfobaseAssociationSettings> settings = ArgumentCaptor.forClass(InfobaseAssociationSettings.class);
        verify(manager).associate(eq(project), eq(ref), settings.capture());
        return settings.getValue().getContext();
    }

    @Test
    public void attachWritesTheBindingUnderTheFullBranchRef()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        String result = SetBranchInfobaseTool.applyResolved(manager, project, SHORT, "app1", ref, true, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        assertEquals(InfobaseAssociationContext.of(FULL), attachedContext(manager, project, ref));
    }

    @Test
    public void attachNeverWritesUnderTheShortKey()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        SetBranchInfobaseTool.applyResolved(manager, project, SHORT, "app1", ref, true, true); //$NON-NLS-1$

        InfobaseAssociationContext shortKey = InfobaseAssociationContext.of(SHORT);
        verify(manager, never()).associate(any(), any(), argThat(s -> s != null && shortKey.equals(s.getContext())));
        verify(manager, never()).setDefaultInfobase(any(), any(), eq(shortKey));
    }

    @Test
    public void setDefaultIsWrittenUnderTheFullBranchRef()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        SetBranchInfobaseTool.applyResolved(manager, project, SHORT, "app1", ref, true, true); //$NON-NLS-1$

        verify(manager).setDefaultInfobase(project, ref, InfobaseAssociationContext.of(FULL));
    }

    @Test
    public void attachReadsBackTheFullBranchRef()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("ReadBackBase"); //$NON-NLS-1$
        when(manager.getAssociation(project, InfobaseAssociationContext.of(FULL))).thenReturn(association(ref));

        String result = SetBranchInfobaseTool.applyResolved(manager, project, SHORT, "app1", ref, true, false); //$NON-NLS-1$

        assertTrue("the read-back must come from the full-ref context: " + result, //$NON-NLS-1$
            result.contains("\"infobases\":[\"ReadBackBase\"]")); //$NON-NLS-1$
    }

    @Test
    public void aFullRefInputIsNotDoubled()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        SetBranchInfobaseTool.applyResolved(manager, project, FULL, "app1", ref, true, false); //$NON-NLS-1$

        assertEquals(InfobaseAssociationContext.of(FULL), attachedContext(manager, project, ref));
    }

    @Test
    public void aCyrillicBranchNameIsBoundVerbatimUnderItsFullRef()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        SetBranchInfobaseTool.applyResolved(manager, project, CYRILLIC_SHORT, "app1", ref, true, false); //$NON-NLS-1$

        assertEquals(InfobaseAssociationContext.of("refs/heads/" + CYRILLIC_SHORT), //$NON-NLS-1$
            attachedContext(manager, project, ref));
    }

    @Test
    public void detachRemovesTheFullRefBindingFirst()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, InfobaseAssociationContext.of(FULL))).thenReturn(association(ref));
        when(manager.getAssociation(project, InfobaseAssociationContext.of(SHORT))).thenReturn(association(ref));

        String result = SetBranchInfobaseTool.applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, InfobaseAssociationContext.of(FULL));
        verify(manager, never()).dissociate(project, ref, InfobaseAssociationContext.of(SHORT));
    }

    @Test
    public void detachFallsBackToTheLegacyShortKey()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, InfobaseAssociationContext.of(FULL))).thenReturn(Optional.empty());
        when(manager.getAssociation(project, InfobaseAssociationContext.of(SHORT))).thenReturn(association(ref));

        String result = SetBranchInfobaseTool.applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, InfobaseAssociationContext.of(SHORT));
        verify(manager, never()).dissociate(project, ref, InfobaseAssociationContext.of(FULL));
    }

    @Test
    public void aLegacyDetachSaysWhichKeyItRemoved()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, InfobaseAssociationContext.of(SHORT))).thenReturn(association(ref));

        String result = SetBranchInfobaseTool.applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        // Both halves: the call succeeded AND its message names the legacy key as the one REMOVED (the
        // "not bound" refusal also mentions the legacy key, so the phrase alone would prove nothing).
        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue("the answer must say the LEGACY short key was removed: " + result, //$NON-NLS-1$
            result.contains("\"message\":\"Removed the binding stored under the legacy short key '" + SHORT + "'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aLegacyDetachIsReachableFromTheFullRefInputToo()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, InfobaseAssociationContext.of(SHORT))).thenReturn(association(ref));

        SetBranchInfobaseTool.applyResolved(manager, project, FULL, "app1", ref, false, false); //$NON-NLS-1$

        verify(manager).dissociate(project, ref, InfobaseAssociationContext.of(SHORT));
    }

    @Test
    public void aDetachWithNoBindingUnderEitherKeyIsRefusedNamingBoth()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(eq(project), any(InfobaseAssociationContext.class))).thenReturn(Optional.empty());

        String result = SetBranchInfobaseTool.applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name the full-ref context: " + result, result.contains(FULL)); //$NON-NLS-1$
        assertTrue("the refusal must say the legacy key was checked too: " + result, //$NON-NLS-1$
            result.contains("legacy short key")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void detachReadsBackTheContextItChanged()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        InfobaseReference other = infobase("StillBoundUnderTheLegacyKey"); //$NON-NLS-1$
        // First read (is it bound?) sees the base, the read-back after dissociate sees what is left.
        when(manager.getAssociation(project, InfobaseAssociationContext.of(SHORT)))
            .thenReturn(association(ref, other), association(other));

        String result = SetBranchInfobaseTool.applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue("the read-back must come from the legacy context that was changed: " + result, //$NON-NLS-1$
            result.contains("\"infobases\":[\"StillBoundUnderTheLegacyKey\"]")); //$NON-NLS-1$
    }

    // ==================== #684: the branch input is checked at the tool boundary ====================

    private static String executeWithBranch(String branch)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", NONEXISTENT_PROJECT); //$NON-NLS-1$
        params.put("branch", branch); //$NON-NLS-1$
        params.put("applicationId", "app1"); //$NON-NLS-1$ //$NON-NLS-2$
        return new SetBranchInfobaseTool().execute(params);
    }

    @Test
    public void aRemoteTrackingRefIsRejectedBeforeProjectResolution()
    {
        String result = executeWithBranch("refs/remotes/origin/feature/x"); //$NON-NLS-1$
        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must say why: " + result, result.contains("remote-tracking")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the input guard must fire before project resolution: " + result, //$NON-NLS-1$
            result.contains(NONEXISTENT_PROJECT));
    }

    @Test
    public void aTagRefIsRejectedBeforeProjectResolution()
    {
        String result = executeWithBranch("refs/tags/v1"); //$NON-NLS-1$
        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name the ref: " + result, result.contains("refs/tags/v1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result, result.contains(NONEXISTENT_PROJECT));
    }

    @Test
    public void aBlankBranchIsRejectedBeforeProjectResolution()
    {
        String result = executeWithBranch("   "); //$NON-NLS-1$
        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertFalse(result, result.contains(NONEXISTENT_PROJECT));
    }

    @Test
    public void aFullLocalRefPassesTheInputGuard()
    {
        String result = executeWithBranch(FULL);
        assertTrue("a refs/heads/ ref is a valid input and reaches project resolution: " + result, //$NON-NLS-1$
            result.contains(NONEXISTENT_PROJECT));
    }

    // ==================== #684 gate: a default EDT refuses after a done attach ====================
    //
    // EDT 2026.2.1 InfobaseAssociationManager.setDefaultInfobase checks the infobase against the binding of
    // the CHECKED-OUT branch (getAssociation(IProject)) and throws IllegalArgumentException when it lacks the
    // infobase - which is the case for any branch that is not checked out. The attach is done by then.

    /** The text EDT's lambda$4 in setDefaultInfobase throws. */
    private static final String EDT_DEFAULT_REFUSAL = "Project P is not associated with infobase Base1"; //$NON-NLS-1$

    @Test
    public void aDefaultEdtRefusesLeavesTheAttachInPlaceAndSaysSo()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, InfobaseAssociationContext.of(FULL))).thenReturn(association(ref));
        doThrow(new IllegalArgumentException(EDT_DEFAULT_REFUSAL)).when(manager)
            .setDefaultInfobase(any(), any(), any());

        String result = SetBranchInfobaseTool.applyResolved(manager, project, SHORT, "app1", ref, true, true); //$NON-NLS-1$

        assertTrue("a done attach must not be reported as a failure: " + result, //$NON-NLS-1$
            result.contains("\"success\":true")); //$NON-NLS-1$
        assertEquals(InfobaseAssociationContext.of(FULL), attachedContext(manager, project, ref));
        assertTrue("the read-back must prove the attach: " + result, //$NON-NLS-1$
            result.contains("\"infobases\":[\"Base1\"]")); //$NON-NLS-1$
        assertTrue("the message must say the default was NOT recorded, with EDT's reason: " + result, //$NON-NLS-1$
            result.contains("but EDT did not record it as the branch's default infobase (EDT: " //$NON-NLS-1$
                + EDT_DEFAULT_REFUSAL + ")")); //$NON-NLS-1$
        assertTrue("the message must say how to get the default: " + result, //$NON-NLS-1$
            result.contains("check this branch out (switch_git_branch)")); //$NON-NLS-1$
    }

    @Test
    public void aDefaultRefusedWithAnAssociationErrorIsNotReportedAsAFailedAttach()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        doThrow(new InfobaseAssociationException("store failed")).when(manager) //$NON-NLS-1$
            .setDefaultInfobase(any(), any(), any());

        String result = SetBranchInfobaseTool.applyResolved(manager, project, SHORT, "app1", ref, true, true); //$NON-NLS-1$

        assertTrue("the attach succeeded, so the call did: " + result, result.contains("\"success\":true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the attach must not be called failed: " + result, //$NON-NLS-1$
            result.contains("Failed to attach")); //$NON-NLS-1$
        assertTrue("a platform failure is reported as one, with EDT's text: " + result, //$NON-NLS-1$
            result.contains("but EDT failed to store it as the branch's default infobase: store failed.")); //$NON-NLS-1$
        assertFalse("a store failure is not cured by a checkout, so no checkout advice: " + result, //$NON-NLS-1$
            result.contains("switch_git_branch")); //$NON-NLS-1$
        assertFalse("nor the refusal's explanation: " + result, //$NON-NLS-1$
            result.contains("CHECKED-OUT")); //$NON-NLS-1$
    }

    @Test
    public void anAcceptedDefaultAddsNoMessage()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        String result = SetBranchInfobaseTool.applyResolved(manager, project, SHORT, "app1", ref, true, true); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        assertFalse("nothing to report when EDT accepted the default: " + result, //$NON-NLS-1$
            result.contains("\"message\"")); //$NON-NLS-1$
    }

    // ==================== #684 gate: a branch literally named 'refs/...' has no legacy key ====================

    @Test
    public void aDetachOfABranchNamedLikeARefNeverFallsBackToAnotherBranchsContext()
    {
        // Input 'refs/heads/refs/heads/x' is the branch literally named 'refs/heads/x'. Its bare name
        // 'refs/heads/x' is branch x's REAL context; a fallback onto it would unbind branch x.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        InfobaseAssociationContext branchX = InfobaseAssociationContext.of("refs/heads/x"); //$NON-NLS-1$
        when(manager.getAssociation(project, branchX)).thenReturn(association(ref));

        String result = SetBranchInfobaseTool.applyResolved(manager, project, "refs/heads/refs/heads/x", "app1", //$NON-NLS-1$ //$NON-NLS-2$
            ref, false, false);

        assertTrue("nothing is bound to the branch named 'refs/heads/x': " + result, //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        verify(manager, never()).dissociate(project, ref, branchX);
    }
}
