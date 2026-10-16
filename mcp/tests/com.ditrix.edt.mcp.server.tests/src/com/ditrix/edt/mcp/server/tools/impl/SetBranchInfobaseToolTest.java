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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
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

    /** A base with a UUID derived from its name: distinct names never share a UUID (EDT matches by UUID). */
    private static InfobaseReference infobase(String name)
    {
        return infobase(name, UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    }

    private static InfobaseReference infobase(String name, UUID uuid)
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn(name);
        when(ref.getUuid()).thenReturn(uuid);
        return ref;
    }

    /** The branch EDT reads by default in these tests: never the branch under test unless a test says so. */
    private static final InfobaseAssociationContext CHECKED_OUT = InfobaseAssociationContext.of("refs/heads/master"); //$NON-NLS-1$

    /** A context provider that answers {@code current} for every project. */
    private static IInfobaseAssociationContextProvider currentIs(InfobaseAssociationContext current)
    {
        IInfobaseAssociationContextProvider provider = mock(IInfobaseAssociationContextProvider.class);
        when(provider.get(any())).thenReturn(current);
        return provider;
    }

    /** {@link SetBranchInfobaseTool#applyResolved} with 'master' checked out and no dependent extension. */
    private static String applyResolved(IInfobaseAssociationManager manager, IProject project, String branch,
        String applicationId, InfobaseReference ref, boolean attach, boolean setDefault)
    {
        return SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT), Collections::emptyList, project,
            branch, applicationId, ref, attach, setDefault);
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

        String result = applyResolved(manager, project, SHORT, "app1", ref, true, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        assertEquals(InfobaseAssociationContext.of(FULL), attachedContext(manager, project, ref));
    }

    @Test
    public void attachNeverWritesUnderTheShortKey()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        applyResolved(manager, project, SHORT, "app1", ref, true, true); //$NON-NLS-1$

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

        applyResolved(manager, project, SHORT, "app1", ref, true, true); //$NON-NLS-1$

        verify(manager).setDefaultInfobase(project, ref, InfobaseAssociationContext.of(FULL));
    }

    @Test
    public void attachReadsBackTheFullBranchRef()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("ReadBackBase"); //$NON-NLS-1$
        when(manager.getAssociation(project, InfobaseAssociationContext.of(FULL))).thenReturn(association(ref));

        String result = applyResolved(manager, project, SHORT, "app1", ref, true, false); //$NON-NLS-1$

        assertTrue("the read-back must come from the full-ref context: " + result, //$NON-NLS-1$
            result.contains("\"infobases\":[\"ReadBackBase\"]")); //$NON-NLS-1$
    }

    @Test
    public void aFullRefInputIsNotDoubled()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        applyResolved(manager, project, FULL, "app1", ref, true, false); //$NON-NLS-1$

        assertEquals(InfobaseAssociationContext.of(FULL), attachedContext(manager, project, ref));
    }

    @Test
    public void aCyrillicBranchNameIsBoundVerbatimUnderItsFullRef()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        applyResolved(manager, project, CYRILLIC_SHORT, "app1", ref, true, false); //$NON-NLS-1$

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

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

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

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

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

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

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

        applyResolved(manager, project, FULL, "app1", ref, false, false); //$NON-NLS-1$

        verify(manager).dissociate(project, ref, InfobaseAssociationContext.of(SHORT));
    }

    @Test
    public void aDetachWithNoBindingUnderEitherKeyIsRefusedNamingBoth()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(eq(project), any(InfobaseAssociationContext.class))).thenReturn(Optional.empty());

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name the full-ref context: " + result, result.contains(FULL)); //$NON-NLS-1$
        assertTrue("the refusal must say the legacy key was checked too: " + result, //$NON-NLS-1$
            result.contains("legacy short key")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void detachReadsBackTheContextItChanged()
    {
        // #656: only in the CHECKED-OUT branch does EDT rewrite the binding file and keep the other bases;
        // from any other context it may delete the whole file. So the sibling survives here, where the branch
        // under test is the one EDT reads now.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        InfobaseReference other = infobase("StillBoundInTheCheckedOutBranch"); //$NON-NLS-1$
        // First read (is it bound?) sees the base, the read-back after dissociate sees what is left.
        when(manager.getAssociation(project, InfobaseAssociationContext.of(FULL)))
            .thenReturn(association(ref, other), association(other));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(InfobaseAssociationContext.of(FULL)),
            Collections::emptyList, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, InfobaseAssociationContext.of(FULL));
        assertTrue("the read-back must come from the context that was changed: " + result, //$NON-NLS-1$
            result.contains("\"infobases\":[\"StillBoundInTheCheckedOutBranch\"]")); //$NON-NLS-1$
    }

    // ==================== #656: a detach from a branch that is not checked out ====================
    //
    // EDT's AssociationChangedConnectionHandler removes a binding from a context other than the one
    // IInfobaseAssociationContextProvider.get(project) returns by deleting that context's whole
    // AssociationData.properties - and each dependent extension's same-named file whose own current context
    // differs. Probed live on the stand (2026-10-15): a sibling bound to the same branch was lost.

    private static final InfobaseAssociationContext BRANCH = InfobaseAssociationContext.of(FULL);

    @Test
    public void aDetachFromABranchNotCheckedOutThatHoldsAnotherBaseIsRefusedNamingIt()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        InfobaseReference sibling = infobase("SiblingBase"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref, sibling));

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name the base that would be lost: " + result, //$NON-NLS-1$
            result.contains("infobase 'SiblingBase'")); //$NON-NLS-1$
        assertTrue("the refusal must say why: " + result, //$NON-NLS-1$
            result.contains("Branch '" + SHORT + "' is not checked out. EDT may remove a binding from a branch " //$NON-NLS-1$ //$NON-NLS-2$
                + "that is not checked out by deleting the branch's whole binding file")); //$NON-NLS-1$
        assertTrue("the refusal must give the remedy: " + result, //$NON-NLS-1$
            result.contains("Check the branch out (switch_git_branch '" + SHORT + "') and detach again: from the " //$NON-NLS-1$ //$NON-NLS-2$
                + "checked-out branch EDT removes only this binding.")); //$NON-NLS-1$
        assertTrue(result, result.contains("Nothing was changed.")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aRefusalThatListsADependentNamesWhatTheCheckoutDoesNotSave()
    {
        // After the checkout EDT still deletes the same-named file of a dependent whose current context does
        // not follow it (another repository), so "removes only this binding" would be false advice here.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        InfobaseReference extBase = infobase("ExtBase"); //$NON-NLS-1$
        when(manager.getAssociation(extension, BRANCH)).thenReturn(association(extBase));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the remedy must name the dependent residual: " + result, //$NON-NLS-1$
            result.contains("Check the branch out (switch_git_branch '" + SHORT + "') and detach again: from the " //$NON-NLS-1$ //$NON-NLS-2$
                + "checked-out branch EDT keeps this project's other bindings, but a dependent extension project " //$NON-NLS-1$
                + "listed above that does not follow the checkout (one in another repository) still loses its " //$NON-NLS-1$
                + "binding to this branch.")); //$NON-NLS-1$
        assertFalse("no 'only this binding' claim when a dependent is listed: " + result, //$NON-NLS-1$
            result.contains("removes only this binding")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aRefusalForAProjectEdtReadsNoBranchOfGivesNoCheckoutAdvice()
    {
        // provider.get answers empty() for a project EGit does not map: EDT reads the project-level bindings,
        // no checkout ever makes the branch current, so "check the branch out" would refuse forever.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        InfobaseReference sibling = infobase("SiblingBase"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref, sibling));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(InfobaseAssociationContext.empty()),
            Collections::emptyList, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("infobase 'SiblingBase'")); //$NON-NLS-1$
        assertTrue("the refusal must say EDT does not see the repository: " + result, //$NON-NLS-1$
            result.contains("EDT does not see a git repository for this project (it reads the project's own " //$NON-NLS-1$
                + "bindings, not a branch's), so no checkout makes branch '" + SHORT + "' the one it reads")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("no checkout advice that cannot work: " + result, result.contains("switch_git_branch")); //$NON-NLS-1$ //$NON-NLS-2$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aDetachFromTheCheckedOutBranchRemovesTheBindingEvenWithOtherBasesThere()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        InfobaseReference siblingBase = infobase("SiblingBase"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref, siblingBase));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(BRANCH), Collections::emptyList,
            project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, BRANCH);
        assertFalse("a detach in the checked-out branch needs no note: " + result, //$NON-NLS-1$
            result.contains("\"message\"")); //$NON-NLS-1$
    }

    @Test
    public void aDetachOfTheOnlyBindingOfABranchNotCheckedOutGoesThroughAndSaysSo()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, BRANCH);
        assertTrue("the note must say the whole file may have gone and held no other binding: " + result, //$NON-NLS-1$
            result.contains("\"message\":\"Branch '" + SHORT + "' is not checked out, and EDT may remove a binding " //$NON-NLS-1$ //$NON-NLS-2$
                + "from such a branch by deleting its whole binding file; that file held no other binding, so no " //$NON-NLS-1$
                + "other binding was removed.\"")); //$NON-NLS-1$
    }

    @Test
    public void aDependentExtensionBindingUnderABranchNotCheckedOutRefusesTheDetach()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        InfobaseReference extBase = infobase("ExtBase"); //$NON-NLS-1$
        when(manager.getAssociation(extension, BRANCH)).thenReturn(association(extBase));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name the extension and its base: " + result, //$NON-NLS-1$
            result.contains("the binding of extension project 'Ext' to this branch (ExtBase)")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aDependentExtensionThatReadsTheBranchItselfKeepsItsFileSoTheDetachGoesThrough()
    {
        // EDT deletes a dependent's file only when the DEPENDENT's current context differs from the one
        // dissociated in; an extension that has this branch checked out keeps its file.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        InfobaseReference extBase = infobase("ExtBase"); //$NON-NLS-1$
        when(manager.getAssociation(extension, BRANCH)).thenReturn(association(extBase));
        IInfobaseAssociationContextProvider provider = currentIs(CHECKED_OUT);
        when(provider.get(extension)).thenReturn(BRANCH);

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(extension), project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, BRANCH);
    }

    @Test
    public void aDependentExtensionWhoseCurrentContextCannotBeReadRefusesTheDetach()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        InfobaseReference extBase = infobase("ExtBase"); //$NON-NLS-1$
        when(manager.getAssociation(extension, BRANCH)).thenReturn(association(extBase));
        IInfobaseAssociationContextProvider provider = currentIs(CHECKED_OUT);
        when(provider.get(extension)).thenThrow(new InfobaseAssociationException("no context")); //$NON-NLS-1$

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(extension), project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("no context")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void dependentsThatCannotBeListedRefuseTheDetach()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT), () -> {
            throw new IllegalStateException("IV8ProjectManager service is not available"); //$NON-NLS-1$
        }, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("IV8ProjectManager service is not available")); //$NON-NLS-1$
        assertTrue(result, result.contains("Nothing was changed.")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aBranchReferencedByAnotherBranchRefusesTheDetachNamingIt()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        when(manager.getAssociationContexts(project)).thenReturn(List.of(BRANCH,
            InfobaseAssociationContext.of("refs/heads/sharer", BRANCH))); //$NON-NLS-1$

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name the sharing branch: " + result, //$NON-NLS-1$
            result.contains("the binding of branch 'sharer', which shares this branch's binding file")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aBranchThatReferencesAnotherBranchsBindingRefusesWithItsOwnRemedy()
    {
        // C5: branch X was created with EDT's source-branch infobase option, so its binding IS master's. With X
        // checked out EDT reads master's context, so "check the branch out" would be wrong advice.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        when(manager.getAssociationContexts(project)).thenReturn(List.of(CHECKED_OUT,
            InfobaseAssociationContext.of(FULL, CHECKED_OUT)));

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name the referenced branch and point there: " + result, //$NON-NLS-1$
            result.contains("EDT keeps its binding as a reference to branch 'master'")); //$NON-NLS-1$
        assertTrue(result, result.contains("Detach it from branch 'master' instead, the binding EDT reads now.")); //$NON-NLS-1$
        assertFalse("the branch is checked out already, so no checkout advice: " + result, //$NON-NLS-1$
            result.contains("switch_git_branch")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void unreadableStoredContextsRefuseTheDetach()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("P"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        when(manager.getAssociationContexts(project)).thenThrow(new InfobaseAssociationException("store broken")); //$NON-NLS-1$

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("store broken")); //$NON-NLS-1$
        assertTrue(result, result.contains("Nothing was changed.")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aMissingContextProviderRefusesADetachFromTheBranchContext()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));

        String result = SetBranchInfobaseTool.applyResolved(manager, null, Collections::emptyList, project, SHORT,
            "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("IInfobaseAssociationContextProvider")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void anAttachNeedsNoContextProvider()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$

        String result = SetBranchInfobaseTool.applyResolved(manager, null, () -> {
            throw new IllegalStateException("not needed"); //$NON-NLS-1$
        }, project, SHORT, "app1", ref, true, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        assertEquals(BRANCH, attachedContext(manager, project, ref));
    }

    @Test
    public void aLegacyDetachNamesTheOtherBindingsStoredUnderTheKey()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        InfobaseAssociationContext legacy = InfobaseAssociationContext.of(SHORT);
        InfobaseReference legacySibling = infobase("LegacySibling"); //$NON-NLS-1$
        when(manager.getAssociation(project, legacy)).thenReturn(association(ref, legacySibling));
        InfobaseReference extLegacy = infobase("ExtLegacy"); //$NON-NLS-1$
        when(manager.getAssociation(extension, legacy)).thenReturn(association(extLegacy));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue("a legacy key is dead, so its detach goes through: " + result, //$NON-NLS-1$
            result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, legacy);
        assertTrue("the note must name what may have gone with the key: " + result, //$NON-NLS-1$
            result.contains("EDT may remove such a key by deleting its whole binding file, so these other bindings " //$NON-NLS-1$
                + "stored under it may be gone too (EDT never read them either): infobase 'LegacySibling'; the " //$NON-NLS-1$
                + "binding of extension project 'Ext' under it (ExtLegacy).")); //$NON-NLS-1$
    }

    @Test
    public void aSameNamedBaseWithAnotherUuidIsNotBound()
    {
        // EDT's dissociate matches by UUID; a name match would answer "detached" for a no-op.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1", UUID.fromString("00000000-0000-0000-0000-000000000001")); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseReference namesake = infobase("Base1", UUID.fromString("00000000-0000-0000-0000-000000000002")); //$NON-NLS-1$ //$NON-NLS-2$
        when(manager.getAssociation(eq(project), any(InfobaseAssociationContext.class)))
            .thenReturn(association(namesake));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(BRANCH), Collections::emptyList,
            project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("is not bound to branch")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void anUnreadableBranchBindingRefusesInsteadOfAssumingBound()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("P"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenThrow(new InfobaseAssociationException("read failed")); //$NON-NLS-1$

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(BRANCH), Collections::emptyList,
            project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("read failed")); //$NON-NLS-1$
        assertTrue(result, result.contains("Nothing was changed.")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aBaseWithoutAUuidIsRefused()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1", null); //$NON-NLS-1$
        when(manager.getAssociation(eq(project), any(InfobaseAssociationContext.class)))
            .thenReturn(association(ref));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(BRANCH), Collections::emptyList,
            project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("without a UUID")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
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

        String result = applyResolved(manager, project, SHORT, "app1", ref, true, true); //$NON-NLS-1$

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

        String result = applyResolved(manager, project, SHORT, "app1", ref, true, true); //$NON-NLS-1$

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

        String result = applyResolved(manager, project, SHORT, "app1", ref, true, true); //$NON-NLS-1$

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

        String result = applyResolved(manager, project, "refs/heads/refs/heads/x", "app1", //$NON-NLS-1$ //$NON-NLS-2$
            ref, false, false);

        assertTrue("nothing is bound to the branch named 'refs/heads/x': " + result, //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        verify(manager, never()).dissociate(project, ref, branchX);
    }
}
