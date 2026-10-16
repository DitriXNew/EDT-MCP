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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.junit.After;
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
import com.ditrix.edt.mcp.server.utils.TemporaryDirectories;
import com.ditrix.edt.mcp.server.utils.git.BlockingJob;
import com.ditrix.edt.mcp.server.utils.git.BranchBindingLock;

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

    // ==================== #656: the persisted binding files EDT deletes whole ====================
    //
    // EDT keeps a project's bindings of one context in
    // <project.getWorkingLocation("com._1c.g5.v8.dt.platform.services.core")>/<context path>/AssociationData.properties
    // (keys Infobases - UUIDs joined by ';' - and DefaultInfobase). A test whose detach EDT would carry out by
    // deleting that whole file gives the project such a store, holding what EDT itself writes.

    /** EDT's bundle id, the working-location key of its association store (data, as EDT spells it). */
    private static final String STORE_BUNDLE = "com._1c.g5.v8.dt.platform.services.core"; //$NON-NLS-1$

    /** A UUID no registered infobase has: EDT's read drops it, the file keeps it. */
    private static final UUID STALE = UUID.fromString("5a1e0000-0000-0000-0000-00000000dead"); //$NON-NLS-1$

    private final List<Path> stores = new ArrayList<>();

    @After
    public void deleteStores() throws IOException, InterruptedException
    {
        for (Path store : stores)
        {
            TemporaryDirectories.deleteRecursively(store);
        }
    }

    /** Gives {@code project} an EDT working location for the association store, in a fresh temporary directory. */
    private Path storeOf(IProject project) throws IOException
    {
        Path store = Files.createTempDirectory("sbi-store"); //$NON-NLS-1$
        stores.add(store);
        when(project.getWorkingLocation(STORE_BUNDLE))
            .thenReturn(org.eclipse.core.runtime.Path.fromOSString(store.toString()));
        return store;
    }

    /** Writes {@code ctx}'s binding file under {@code store}, as the lines of a properties file. */
    private static Path bindingFile(Path store, InfobaseAssociationContext ctx, String... lines) throws IOException
    {
        Path file = store.resolve(ctx.getContext().orElse(".default")).resolve("AssociationData.properties"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(file.getParent());
        Files.write(file, Arrays.asList(lines), StandardCharsets.ISO_8859_1);
        return file;
    }

    /** The {@code Infobases} line EDT writes for these bases. */
    private static String infobasesLine(InfobaseReference... refs)
    {
        List<String> uuids = new ArrayList<>();
        for (InfobaseReference ref : refs)
        {
            uuids.add(ref.getUuid().toString());
        }
        return "Infobases=" + String.join(";", uuids); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The file EDT writes when {@code ref} is the first base bound to {@code ctx}: Infobases and DefaultInfobase. */
    private Path onlyBinding(IProject project, InfobaseAssociationContext ctx, InfobaseReference ref) throws IOException
    {
        return bindingFile(storeOf(project), ctx, "#Infobase association data of the project", //$NON-NLS-1$
            infobasesLine(ref), "DefaultInfobase=" + ref.getUuid()); //$NON-NLS-1$
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
    public void detachRemovesTheFullRefBindingFirst() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        onlyBinding(project, InfobaseAssociationContext.of(FULL), ref);
        when(manager.getAssociation(project, InfobaseAssociationContext.of(FULL))).thenReturn(association(ref));
        when(manager.getAssociation(project, InfobaseAssociationContext.of(SHORT))).thenReturn(association(ref));

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, InfobaseAssociationContext.of(FULL));
        verify(manager, never()).dissociate(project, ref, InfobaseAssociationContext.of(SHORT));
    }

    @Test
    public void detachFallsBackToTheLegacyShortKey() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        onlyBinding(project, InfobaseAssociationContext.of(SHORT), ref);
        when(manager.getAssociation(project, InfobaseAssociationContext.of(FULL))).thenReturn(Optional.empty());
        when(manager.getAssociation(project, InfobaseAssociationContext.of(SHORT))).thenReturn(association(ref));

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, InfobaseAssociationContext.of(SHORT));
        verify(manager, never()).dissociate(project, ref, InfobaseAssociationContext.of(FULL));
    }

    @Test
    public void aLegacyDetachSaysWhichKeyItRemoved() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        onlyBinding(project, InfobaseAssociationContext.of(SHORT), ref);
        when(manager.getAssociation(project, InfobaseAssociationContext.of(SHORT))).thenReturn(association(ref));

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        // Both halves: the call succeeded AND its message names the legacy key as the one REMOVED (the
        // "not bound" refusal also mentions the legacy key, so the phrase alone would prove nothing).
        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue("the answer must say the LEGACY short key was removed: " + result, //$NON-NLS-1$
            result.contains("\"message\":\"Removed the binding stored under the legacy short key '" + SHORT + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the note must say the key's file held nothing else: " + result, //$NON-NLS-1$
            result.contains("That key's binding file held no other binding, so no other binding was removed.")); //$NON-NLS-1$
    }

    @Test
    public void aLegacyDetachIsReachableFromTheFullRefInputToo() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        onlyBinding(project, InfobaseAssociationContext.of(SHORT), ref);
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
        // not follow it (another repository), so "removes only this binding" would be false advice here - and
        // that detach is refused too, so the advice must not promise it goes through.
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
                + "checked-out branch EDT keeps this project's other bindings, but it still deletes the binding file " //$NON-NLS-1$
                + "of a dependent extension project listed above that does not follow the checkout (one in another " //$NON-NLS-1$
                + "repository), so that detach is refused as well unless the extension reads this branch too.")); //$NON-NLS-1$
        assertFalse("no 'only this binding' claim when a dependent is listed: " + result, //$NON-NLS-1$
            result.contains("removes only this binding")); //$NON-NLS-1$
        assertFalse("no claim that the detach from the checked-out branch loses the dependent's binding: " + result, //$NON-NLS-1$
            result.contains("still loses its binding")); //$NON-NLS-1$
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
    public void aDetachOfTheOnlyBindingOfABranchNotCheckedOutGoesThroughAndSaysSo() throws IOException
    {
        // The file names the base twice - in Infobases and as DefaultInfobase - and holds a comment: still
        // nothing but this binding.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);

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
    public void aDependentExtensionThatReadsTheBranchItselfKeepsItsFileSoTheDetachGoesThrough() throws IOException
    {
        // EDT deletes a dependent's file only when the DEPENDENT's current context differs from the one
        // dissociated in; an extension that has this branch checked out keeps its file.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        InfobaseReference extBase = infobase("ExtBase"); //$NON-NLS-1$
        when(manager.getAssociation(extension, BRANCH)).thenReturn(association(extBase));
        bindingFile(storeOf(extension), BRANCH, infobasesLine(extBase), "LeftByEdt=1"); //$NON-NLS-1$
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
    public void aLegacyDetachThatWouldTakeOtherBindingsIsRefusedNamingThem() throws IOException
    {
        // EDT never reads a legacy key, so it always removes a binding from it by deleting the key's whole
        // file: with other bindings in it, there is no detach that removes this one alone.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        InfobaseAssociationContext legacy = InfobaseAssociationContext.of(SHORT);
        InfobaseReference legacySibling = infobase("LegacySibling"); //$NON-NLS-1$
        when(manager.getAssociation(project, legacy)).thenReturn(association(ref, legacySibling));
        bindingFile(storeOf(project), legacy, infobasesLine(ref, legacySibling));
        InfobaseReference extLegacy = infobase("ExtLegacy"); //$NON-NLS-1$
        when(manager.getAssociation(extension, legacy)).thenReturn(association(extLegacy));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name what would go with the key: " + result, //$NON-NLS-1$
            result.contains("detaching it would also remove: infobase 'LegacySibling'; the binding of extension " //$NON-NLS-1$
                + "project 'Ext' under this key (ExtLegacy).")); //$NON-NLS-1$
        assertTrue("the refusal must say a legacy entry is dead and harmless: " + result, //$NON-NLS-1$
            result.contains("EDT never reads legacy entries: they are dead and harmless where they are.")); //$NON-NLS-1$
        assertTrue("the refusal must say no detach removes it alone: " + result, //$NON-NLS-1$
            result.contains("There is no way to remove one binding of such a file without the others, so nothing " //$NON-NLS-1$
                + "was changed; leave the legacy entry in place.")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aLegacyDetachWithOnlyADependentBindingUnderTheKeyIsRefused() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        InfobaseAssociationContext legacy = InfobaseAssociationContext.of(SHORT);
        when(manager.getAssociation(project, legacy)).thenReturn(association(ref));
        onlyBinding(project, legacy, ref);
        InfobaseReference extLegacy = infobase("ExtLegacy"); //$NON-NLS-1$
        when(manager.getAssociation(extension, legacy)).thenReturn(association(extLegacy));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("the binding of extension project 'Ext' under this key (ExtLegacy)")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aLegacyDetachWithAStaleEntryUnderTheKeyIsRefused() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        InfobaseAssociationContext legacy = InfobaseAssociationContext.of(SHORT);
        when(manager.getAssociation(project, legacy)).thenReturn(association(ref));
        bindingFile(storeOf(project), legacy, infobasesLine(ref) + ";" + STALE); //$NON-NLS-1$

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("an entry EDT does not show in this key's binding file: infobase entry " //$NON-NLS-1$
            + STALE));
        assertTrue(result, result.contains("There is no way to remove one binding of such a file without the others")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    // ==================== #656: what EDT's read does not show still goes with the file ====================

    @Test
    public void aStaleEntryEdtDoesNotShowRefusesADetachFromABranchNotCheckedOut() throws IOException
    {
        // EDT's read drops a UUID no registered infobase has, so it shows the target alone; the file EDT would
        // delete still holds the entry.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        bindingFile(storeOf(project), BRANCH, infobasesLine(ref) + ";" + STALE, "DefaultInfobase=" + ref.getUuid()); //$NON-NLS-1$ //$NON-NLS-2$

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name the entry EDT does not show: " + result, //$NON-NLS-1$
            result.contains("may also remove: an entry EDT does not show in this branch's binding file: infobase " //$NON-NLS-1$
                + "entry " + STALE + ". Nothing was changed.")); //$NON-NLS-1$
        assertTrue("the refusal must say what such an entry is: " + result, //$NON-NLS-1$
            result.contains("An entry EDT does not show is still a record in the file: an infobase entry is a UUID " //$NON-NLS-1$
                + "no infobase registered in EDT has")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aKeyEdtDoesNotWriteRefusesTheDetach() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        bindingFile(storeOf(project), BRANCH, infobasesLine(ref), "SourceBranch=refs/heads/master"); //$NON-NLS-1$

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("an entry EDT does not show in this branch's binding file: entry " //$NON-NLS-1$
            + "'SourceBranch'")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aDefaultEntryForAnotherBaseRefusesTheDetach() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        bindingFile(storeOf(project), BRANCH, infobasesLine(ref), "DefaultInfobase=" + STALE); //$NON-NLS-1$

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        // The plan names it (the re-check would refuse too, but with another text).
        assertTrue(result, result.contains("may also remove: an entry EDT does not show in this branch's binding " //$NON-NLS-1$
            + "file: default-infobase entry " + STALE + ". Nothing was changed.")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aProjectWithoutAWorkingLocationRefusesTheDetach()
    {
        // EDT shows the binding, so a missing store means the layout is not what this tool knows: fail closed.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("Could not check what else a detach of application 'app1' from '" + FULL //$NON-NLS-1$
            + "' would delete: EDT shows the binding there, but the project has no EDT working location.")); //$NON-NLS-1$
        assertTrue(result, result.contains("Nothing was changed.")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aBindingFileNotWhereEdtKeepsItRefusesTheDetach() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        storeOf(project);

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("its binding file is not where EDT keeps it (")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aStaleEntryInADependentsFileRefusesTheDetach() throws IOException
    {
        // The extension shows no binding to the branch, but EDT would still delete its file with the entry.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        bindingFile(storeOf(extension), BRANCH, "Infobases=" + STALE); //$NON-NLS-1$

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name the extension's entry: " + result, //$NON-NLS-1$
            result.contains("an entry EDT does not show in the binding file of extension project 'Ext' to this " //$NON-NLS-1$
                + "branch: infobase entry " + STALE)); //$NON-NLS-1$
        assertTrue("a dependent entry gets the dependent remedy: " + result, //$NON-NLS-1$
            result.contains("so that detach is refused as well unless the extension reads this branch too.")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aStaleEntryOfADependentThatReadsTheBranchItselfDoesNotRefuse() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        bindingFile(storeOf(extension), BRANCH, "Infobases=" + STALE); //$NON-NLS-1$
        IInfobaseAssociationContextProvider provider = currentIs(CHECKED_OUT);
        when(provider.get(extension)).thenReturn(BRANCH);

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(extension), project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, BRANCH);
    }

    @Test
    public void aDependentThatKeepsTheBranchAsAReferenceRefusesTheDetach() throws IOException
    {
        // EDT follows the extension's reference and deletes the REFERENCED file of the extension.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        when(manager.getAssociationContexts(extension))
            .thenReturn(List.of(InfobaseAssociationContext.of(FULL, CHECKED_OUT)));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("the binding of extension project 'Ext' to this branch, which EDT keeps " //$NON-NLS-1$
            + "as a reference to branch 'master'")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    // ==================== #656: this tool's binding changes are serialized and re-checked ====================

    private static void awaitQuietly(CountDownLatch latch)
    {
        try
        {
            latch.await(30, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Starts an attach of another project and asserts it does not reach EDT's write while the other call holds
     * on, and does once {@code release} opens.
     */
    private static void assertAnAttachWaitsUntil(CountDownLatch release) throws InterruptedException
    {
        IInfobaseAssociationManager attachManager = mock(IInfobaseAssociationManager.class);
        IProject other = mock(IProject.class);
        InfobaseReference otherRef = infobase("Base2"); //$NON-NLS-1$
        CountDownLatch attached = new CountDownLatch(1);
        doAnswer(invocation -> {
            attached.countDown();
            return null;
        }).when(attachManager).associate(any(), any(), any());
        Thread attach = new Thread(() -> applyResolved(attachManager, other, "feature/y", "app2", otherRef, true, //$NON-NLS-1$ //$NON-NLS-2$
            false));
        attach.start();
        try
        {
            assertFalse("an attach must wait while another call is changing bindings", //$NON-NLS-1$
                attached.await(500, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue("the attach must go on once the other call is done", //$NON-NLS-1$
                attached.await(10, TimeUnit.SECONDS));
        }
        finally
        {
            release.countDown();
            attach.join(10_000);
        }
    }

    @Test(timeout = 60_000)
    public void anAttachWaitsWhileADetachIsStillBeingPlanned() throws Exception
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        CountDownLatch planning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<Collection<IProject>> blockingDependents = () -> {
            planning.countDown();
            awaitQuietly(release);
            return List.of();
        };
        Thread detach = new Thread(() -> SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT),
            blockingDependents, project, SHORT, "app1", ref, false, false)); //$NON-NLS-1$
        detach.start();
        try
        {
            assertTrue("the detach must reach its plan", planning.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
            assertAnAttachWaitsUntil(release);
        }
        finally
        {
            release.countDown();
            detach.join(10_000);
        }
        verify(manager).dissociate(project, ref, BRANCH);
    }

    @Test(timeout = 60_000)
    public void anAttachWaitsWhileADetachIsStillBeingWritten() throws Exception
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            awaitQuietly(release);
            return null;
        }).when(manager).dissociate(any(), any(), any());
        Thread detach = new Thread(() -> SetBranchInfobaseTool.applyResolved(manager, currentIs(BRANCH),
            Collections::emptyList, project, SHORT, "app1", ref, false, false)); //$NON-NLS-1$
        detach.start();
        try
        {
            assertTrue("the detach must reach EDT's write", writing.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
            assertAnAttachWaitsUntil(release);
        }
        finally
        {
            release.countDown();
            detach.join(10_000);
        }
    }

    @Test(timeout = 60_000)
    public void anAttachWaitsWhileADetachIsBeingReChecked() throws Exception
    {
        // The re-check of a detach from the checked-out branch asks EDT for the current context a second time;
        // that second answer is held, and the lock must still be held with it.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        CountDownLatch rechecking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IInfobaseAssociationContextProvider provider = mock(IInfobaseAssociationContextProvider.class);
        when(provider.get(project)).thenReturn(BRANCH).thenAnswer(invocation -> {
            rechecking.countDown();
            awaitQuietly(release);
            return BRANCH;
        });
        Thread detach = new Thread(() -> SetBranchInfobaseTool.applyResolved(manager, provider,
            Collections::emptyList, project, SHORT, "app1", ref, false, false)); //$NON-NLS-1$
        detach.start();
        try
        {
            assertTrue("the detach must reach its re-check", rechecking.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
            assertAnAttachWaitsUntil(release);
        }
        finally
        {
            release.countDown();
            detach.join(10_000);
        }
        verify(manager).dissociate(project, ref, BRANCH);
    }

    @Test(timeout = 60_000)
    public void anAttachWaitsWhileACheckoutHoldsTheSharedLock() throws Exception
    {
        // switch_git_branch, create_git_branch's checkout and git's checkout/switch/branch hold the same lock as this
        // tool's binding changes: a binding change waits for a checkout, as it waits for another binding change.
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread checkout = new Thread(() -> {
            if (BranchBindingLock.tryLock())
            {
                try
                {
                    held.countDown();
                    awaitQuietly(release);
                }
                finally
                {
                    BranchBindingLock.unlock();
                }
            }
        });
        checkout.start();
        try
        {
            assertTrue("the checkout must take the lock", held.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
            assertAnAttachWaitsUntil(release);
        }
        finally
        {
            release.countDown();
            checkout.join(10_000);
        }
    }

    @Test
    public void aBindingFileThatGainsAnEntryWhileTheDetachIsCheckedRefusesIt() throws IOException
    {
        // EDT's UI is outside this tool's lock: an entry written into the file after the plan read it must still
        // stop the deletion. The extension's store lookup comes after the project's own file was read.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        Path store = storeOf(project);
        bindingFile(store, BRANCH, infobasesLine(ref));
        when(extension.getWorkingLocation(STORE_BUNDLE)).thenAnswer(invocation -> {
            bindingFile(store, BRANCH, infobasesLine(ref) + ";" + STALE); //$NON-NLS-1$
            return null;
        });

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must name what changed: " + result, //$NON-NLS-1$
            result.contains("The bindings changed while this detach from '" + FULL + "' was being checked: its " //$NON-NLS-1$ //$NON-NLS-2$
                + "binding file now also holds: infobase entry " + STALE)); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aDependentsFileThatGainsAnEntryWhileTheDetachIsCheckedRefusesIt() throws IOException
    {
        // The dependent's same-named file was empty when the plan read it; an entry written into it afterwards must
        // still stop the deletion. The second dependent's store lookup comes after the first one's file was read.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        IProject later = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        Path extensionStore = storeOf(extension);
        when(later.getWorkingLocation(STORE_BUNDLE)).thenAnswer(invocation -> {
            bindingFile(extensionStore, BRANCH, "Infobases=" + STALE); //$NON-NLS-1$
            return null;
        });

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT),
            () -> List.of(extension, later), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must name the dependent's new entry: " + result, //$NON-NLS-1$
            result.contains("The bindings changed while this detach from '" + FULL + "' was being checked: the " //$NON-NLS-1$ //$NON-NLS-2$
                + "binding file of extension project 'Ext' now holds: infobase entry " + STALE)); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aBranchThatStartsSharingTheBindingWhileTheDetachIsCheckedRefusesIt() throws IOException
    {
        // create_git_branch with EDT's source-branch option (or EDT's UI) adds a reference to this branch after
        // the plan scanned the references; deleting the file would now break the new branch's binding.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        when(manager.getAssociationContexts(project)).thenReturn(List.of(BRANCH),
            List.of(BRANCH, InfobaseAssociationContext.of("refs/heads/sharer", BRANCH))); //$NON-NLS-1$

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must name the new sharer: " + result, //$NON-NLS-1$
            result.contains("The bindings changed while this detach from '" + FULL + "' was being checked: branch " //$NON-NLS-1$ //$NON-NLS-2$
                + "'sharer' now keeps its binding as a reference to this binding")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aBranchWhoseBindingBecomesAReferenceWhileTheDetachIsCheckedRefusesIt() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        when(manager.getAssociationContexts(project)).thenReturn(List.of(BRANCH),
            List.of(InfobaseAssociationContext.of(FULL, CHECKED_OUT)));

        String result = applyResolved(manager, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must name the reference: " + result, //$NON-NLS-1$
            result.contains("The bindings changed while this detach from '" + FULL + "' was being checked: its " //$NON-NLS-1$ //$NON-NLS-2$
                + "binding is now kept as a reference to branch 'master'")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aDependentThatStartsKeepingTheBranchAsAReferenceWhileTheDetachIsCheckedRefusesIt() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        storeOf(extension);
        when(manager.getAssociationContexts(extension)).thenReturn(List.of(),
            List.of(InfobaseAssociationContext.of(FULL, CHECKED_OUT)));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must name the dependent's new reference: " + result, //$NON-NLS-1$
            result.contains("The bindings changed while this detach from '" + FULL + "' was being checked: " //$NON-NLS-1$ //$NON-NLS-2$
                + "extension project 'Ext' now keeps its binding here as a reference to branch 'master'")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aCheckoutWhileADetachFromTheCheckedOutBranchIsCheckedRefusesIt()
    {
        // From the branch EDT reads, EDT rewrites the file; once another branch is checked out the same call
        // deletes it whole, sibling and all.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        InfobaseReference sibling = infobase("SiblingBase"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref, sibling));
        IInfobaseAssociationContextProvider provider = mock(IInfobaseAssociationContextProvider.class);
        when(provider.get(project)).thenReturn(BRANCH, CHECKED_OUT);

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, Collections::emptyList, project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must name the checkout: " + result, //$NON-NLS-1$
            result.contains("the branch EDT reads for this project changed from '" + SHORT + "' to 'master'")); //$NON-NLS-1$ //$NON-NLS-2$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    // ==================== #656: dependents on every detach, and the dependents EDT spares ====================
    //
    // EDT's handler runs for the project AND each dependent extension project, and deletes the same-named file of
    // each one whose OWN current context differs from the one dissociated in - on a detach from the checked-out
    // branch too. A dependent it spares because it reads that context is spared only while it still reads it.

    /** A provider under which {@code project} has the branch under test checked out and everything else 'master'. */
    private static IInfobaseAssociationContextProvider projectReadsTheBranch(IProject project)
    {
        IInfobaseAssociationContextProvider provider = currentIs(CHECKED_OUT);
        when(provider.get(project)).thenReturn(BRANCH);
        return provider;
    }

    @Test
    public void aDetachFromTheCheckedOutBranchThatWouldDeleteADependentsBindingIsRefusedNamingIt()
    {
        // The extension lives in another repository: it reads 'master', not the branch detached from.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        InfobaseReference extBase = infobase("ExtBase"); //$NON-NLS-1$
        when(manager.getAssociation(extension, BRANCH)).thenReturn(association(extBase));

        String result = SetBranchInfobaseTool.applyResolved(manager, projectReadsTheBranch(project),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must say why: " + result, //$NON-NLS-1$
            result.contains("Branch '" + SHORT + "' is the branch EDT reads now, so EDT keeps this project's other " //$NON-NLS-1$ //$NON-NLS-2$
                + "bindings to it, but it deletes the same-named binding file of each dependent extension project " //$NON-NLS-1$
                + "that does not read this branch now")); //$NON-NLS-1$
        assertTrue("the refusal must name the extension and its base: " + result, //$NON-NLS-1$
            result.contains("would also remove: the binding of extension project 'Ext' to this branch (ExtBase).")); //$NON-NLS-1$
        assertTrue("the refusal must say when EDT keeps the file: " + result, //$NON-NLS-1$
            result.contains("Nothing was changed. EDT keeps such a file only while the extension project reads this " //$NON-NLS-1$
                + "same branch (the branch checked out in its own repository).")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aStaleEntryInADependentsFileRefusesADetachFromTheCheckedOutBranch() throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        bindingFile(storeOf(extension), BRANCH, "Infobases=" + STALE); //$NON-NLS-1$

        String result = SetBranchInfobaseTool.applyResolved(manager, projectReadsTheBranch(project),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name the extension's entry: " + result, //$NON-NLS-1$
            result.contains("an entry EDT does not show in the binding file of extension project 'Ext' to this " //$NON-NLS-1$
                + "branch: infobase entry " + STALE)); //$NON-NLS-1$
        assertTrue("the refusal must explain the hidden entry: " + result, //$NON-NLS-1$
            result.contains("An entry EDT does not show is still a record in the file")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aDependentThatReadsTheCheckedOutBranchTooKeepsItsFileSoTheDetachGoesThrough()
    {
        // The extension is in the same repository: it follows the checkout and EDT keeps its file.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        InfobaseReference extBase = infobase("ExtBase"); //$NON-NLS-1$
        when(manager.getAssociation(extension, BRANCH)).thenReturn(association(extBase));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(BRANCH), () -> List.of(extension),
            project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, BRANCH);
        assertFalse("a detach in the checked-out branch needs no note: " + result, //$NON-NLS-1$
            result.contains("\"message\"")); //$NON-NLS-1$
    }

    @Test
    public void aDependentThatReadsAnotherBranchWithNoFileUnderThisOneLetsACheckedOutBranchDetachGoThrough()
        throws IOException
    {
        // The extension reads 'master', so EDT would delete its file under the branch - but it has none there:
        // nothing would be lost, so a detach from the checked-out branch is not refused for it.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        storeOf(extension);

        String result = SetBranchInfobaseTool.applyResolved(manager, projectReadsTheBranch(project),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, BRANCH);
    }

    @Test
    public void aDependentThatReadsAnotherBranchWithAnEmptyFileUnderThisOneLetsACheckedOutBranchDetachGoThrough()
        throws IOException
    {
        // EDT deletes the extension's file under the branch, but the file holds only EDT's header comment.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        bindingFile(storeOf(extension), BRANCH, "#Infobase association data of the project"); //$NON-NLS-1$

        String result = SetBranchInfobaseTool.applyResolved(manager, projectReadsTheBranch(project),
            () -> List.of(extension), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, BRANCH);
    }

    @Test
    public void aDependentWhoseCurrentContextCannotBeReadRefusesADetachFromTheCheckedOutBranch()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        InfobaseReference extBase = infobase("ExtBase"); //$NON-NLS-1$
        when(manager.getAssociation(extension, BRANCH)).thenReturn(association(extBase));
        IInfobaseAssociationContextProvider provider = projectReadsTheBranch(project);
        when(provider.get(extension)).thenThrow(new InfobaseAssociationException("no context")); //$NON-NLS-1$

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(extension), project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("no context")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void dependentsThatCannotBeListedRefuseADetachFromTheCheckedOutBranch()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(BRANCH), () -> {
            throw new IllegalStateException("IV8ProjectManager service is not available"); //$NON-NLS-1$
        }, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("IV8ProjectManager service is not available")); //$NON-NLS-1$
        assertTrue(result, result.contains("Nothing was changed.")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aDependentsFileThatGainsAnEntryWhileACheckedOutBranchDetachIsCheckedRefusesIt() throws IOException
    {
        // The dependent's file was empty when the plan read it; the second dependent's store lookup, which comes
        // after, writes an entry into it. The re-check before EDT's write must see it.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        IProject later = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        Path extensionStore = storeOf(extension);
        when(later.getWorkingLocation(STORE_BUNDLE)).thenAnswer(invocation -> {
            bindingFile(extensionStore, BRANCH, "Infobases=" + STALE); //$NON-NLS-1$
            return null;
        });

        String result = SetBranchInfobaseTool.applyResolved(manager, projectReadsTheBranch(project),
            () -> List.of(extension, later), project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must name the dependent's new entry: " + result, //$NON-NLS-1$
            result.contains("The bindings changed while this detach from '" + FULL + "' was being checked: the " //$NON-NLS-1$ //$NON-NLS-2$
                + "binding file of extension project 'Ext' now holds: infobase entry " + STALE)); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aDependentThatChecksOutAnotherBranchWhileTheDetachIsCheckedRefusesIt() throws IOException
    {
        // EDT spares the extension's non-empty file because the extension reads this branch at plan time; a
        // checkout of the extension (switch_git_branch, EDT's UI - neither takes this tool's lock) before EDT's
        // write would make EDT delete it.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        InfobaseReference extBase = infobase("ExtBase"); //$NON-NLS-1$
        when(manager.getAssociation(extension, BRANCH)).thenReturn(association(extBase));
        IInfobaseAssociationContextProvider provider = currentIs(CHECKED_OUT);
        when(provider.get(extension)).thenReturn(BRANCH, CHECKED_OUT);

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(extension), project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must name the extension's checkout: " + result, //$NON-NLS-1$
            result.contains("The bindings changed while this detach from '" + FULL + "' was being checked: " //$NON-NLS-1$ //$NON-NLS-2$
                + "extension project 'Ext' no longer reads this branch (it reads 'master' now), so EDT would delete " //$NON-NLS-1$
                + "the binding it keeps for this branch")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aDependentThatChecksOutAnotherBranchWhileACheckedOutBranchDetachIsCheckedRefusesIt()
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        InfobaseReference extBase = infobase("ExtBase"); //$NON-NLS-1$
        when(manager.getAssociation(extension, BRANCH)).thenReturn(association(extBase));
        IInfobaseAssociationContextProvider provider = projectReadsTheBranch(project);
        when(provider.get(extension)).thenReturn(BRANCH, CHECKED_OUT);

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(extension), project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must name the extension's checkout: " + result, //$NON-NLS-1$
            result.contains("The bindings changed while this detach from '" + FULL + "' was being checked: " //$NON-NLS-1$ //$NON-NLS-2$
                + "extension project 'Ext' no longer reads this branch (it reads 'master' now)")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aDependentThatStillReadsTheBranchAtTheReCheckLetsTheDetachGoThrough()
    {
        // The re-check reads the extension's context again and finds it unchanged: no refusal.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        InfobaseReference extBase = infobase("ExtBase"); //$NON-NLS-1$
        when(manager.getAssociation(extension, BRANCH)).thenReturn(association(extBase));
        IInfobaseAssociationContextProvider provider = projectReadsTheBranch(project);
        when(provider.get(extension)).thenReturn(BRANCH);

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(extension), project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, BRANCH);
        verify(provider, times(2)).get(extension);
    }

    @Test
    public void anExtensionThatAppearsWhileTheDetachIsCheckedRefusesIt() throws IOException
    {
        // The plan checked no dependent; one listed by the re-check was never checked, and EDT may delete its file.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext"); //$NON-NLS-1$
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        int[] listed = {0};
        Supplier<Collection<IProject>> growing = () -> listed[0]++ == 0 ? List.of() : List.of(extension);

        String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(CHECKED_OUT), growing, project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must name the new dependent: " + result, //$NON-NLS-1$
            result.contains("The bindings changed while this detach from '" + FULL + "' was being checked: " //$NON-NLS-1$ //$NON-NLS-2$
                + "extension project 'Ext' now depends on this project")); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    // ==================== #656: the order of the re-check ====================
    //
    // The re-check reads every dependent's current context first - the slow part, a question to EDT per project -
    // then each file EDT would delete, and on a detach from the checked-out branch the branch the project reads
    // now, last: what was read earlier has the longest time to change before EDT's write.

    /** A dependent extension project named {@code name} with base {@code baseName} bound under the branch. */
    private static IProject boundDependent(IInfobaseAssociationManager manager, String name, String baseName)
    {
        IProject dependent = mock(IProject.class);
        when(dependent.getName()).thenReturn(name);
        InfobaseReference base = infobase(baseName);
        when(manager.getAssociation(dependent, BRANCH)).thenReturn(association(base));
        return dependent;
    }

    /** A dependent extension project named {@code name} whose file under the branch holds only EDT's header. */
    private Path emptyDependentFile(IProject dependent, String name) throws IOException
    {
        when(dependent.getName()).thenReturn(name);
        return bindingFile(storeOf(dependent), BRANCH, "#Infobase association data of the project"); //$NON-NLS-1$
    }

    @Test
    public void anEmptyDependentsFileFilledWhileAnotherDependentsBranchIsReadRefusesTheDetach() throws IOException
    {
        // 'Empty' reads 'master', so EDT deletes its file under the branch; the file was empty at the plan. The
        // re-check then asks EDT which branch 'Spared' reads, and an attach fills Empty's file meanwhile: the file
        // must be read after that question, not before it.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        IProject empty = mock(IProject.class);
        Path emptyFile = emptyDependentFile(empty, "Empty"); //$NON-NLS-1$
        IProject spared = boundDependent(manager, "Spared", "SparedBase"); //$NON-NLS-1$ //$NON-NLS-2$
        IInfobaseAssociationContextProvider provider = currentIs(CHECKED_OUT);
        when(provider.get(spared)).thenReturn(BRANCH).thenAnswer(invocation -> {
            Files.write(emptyFile, Arrays.asList("Infobases=" + STALE), StandardCharsets.ISO_8859_1); //$NON-NLS-1$
            return BRANCH;
        });

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(empty, spared),
            project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must see the entry written meanwhile: " + result, //$NON-NLS-1$
            result.contains("The bindings changed while this detach from '" + FULL + "' was being checked: the " //$NON-NLS-1$ //$NON-NLS-2$
                + "binding file of extension project 'Empty' now holds: infobase entry " + STALE)); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void anEmptyDependentsFileFilledWhileAnotherDependentsBranchIsReadRefusesACheckedOutBranchDetach()
        throws IOException
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        IProject empty = mock(IProject.class);
        Path emptyFile = emptyDependentFile(empty, "Empty"); //$NON-NLS-1$
        IProject spared = boundDependent(manager, "Spared", "SparedBase"); //$NON-NLS-1$ //$NON-NLS-2$
        IInfobaseAssociationContextProvider provider = projectReadsTheBranch(project);
        when(provider.get(spared)).thenReturn(BRANCH).thenAnswer(invocation -> {
            Files.write(emptyFile, Arrays.asList("Infobases=" + STALE), StandardCharsets.ISO_8859_1); //$NON-NLS-1$
            return BRANCH;
        });

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(empty, spared),
            project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must see the entry written meanwhile: " + result, //$NON-NLS-1$
            result.contains("The bindings changed while this detach from '" + FULL + "' was being checked: the " //$NON-NLS-1$ //$NON-NLS-2$
                + "binding file of extension project 'Empty' now holds: infobase entry " + STALE)); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void anEmptyDependentThatReadsTheBranchAtTheReCheckKeepsItsFileSoTheDetachGoesThrough() throws IOException
    {
        // The plan did not need Empty's branch: its file held nothing. The re-check asks for it, and Empty reads the
        // branch now - EDT keeps its file whatever it holds, so an entry written meanwhile loses nothing, exactly as
        // for a dependent the plan spared.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        IProject empty = mock(IProject.class);
        Path emptyFile = emptyDependentFile(empty, "Empty"); //$NON-NLS-1$
        IProject spared = boundDependent(manager, "Spared", "SparedBase"); //$NON-NLS-1$ //$NON-NLS-2$
        IInfobaseAssociationContextProvider provider = currentIs(CHECKED_OUT);
        when(provider.get(empty)).thenReturn(BRANCH);
        when(provider.get(spared)).thenReturn(BRANCH).thenAnswer(invocation -> {
            Files.write(emptyFile, Arrays.asList("Infobases=" + STALE), StandardCharsets.ISO_8859_1); //$NON-NLS-1$
            return BRANCH;
        });

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(empty, spared),
            project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, BRANCH);
        verify(provider).get(empty);
    }

    @Test
    public void anEmptyDependentWhoseBranchCannotBeReadAtTheReCheckIsJudgedByItsFile() throws IOException
    {
        // The file must hold nothing whichever branch the extension reads, so an unreadable branch does not refuse.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        onlyBinding(project, BRANCH, ref);
        IProject empty = mock(IProject.class);
        emptyDependentFile(empty, "Empty"); //$NON-NLS-1$
        IInfobaseAssociationContextProvider provider = currentIs(CHECKED_OUT);
        when(provider.get(empty)).thenThrow(new InfobaseAssociationException("no context")); //$NON-NLS-1$

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(empty), project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, BRANCH);
    }

    @Test
    public void theProjectsOwnFileFilledWhileADependentsBranchIsReadRefusesTheDetach() throws IOException
    {
        // EDT deletes the project's whole file under a branch that is not checked out. An entry written into it while
        // the re-check asks EDT about a dependent must still stop the deletion: the file the write deletes is read
        // last.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref));
        Path own = onlyBinding(project, BRANCH, ref);
        IProject spared = boundDependent(manager, "Spared", "SparedBase"); //$NON-NLS-1$ //$NON-NLS-2$
        IInfobaseAssociationContextProvider provider = currentIs(CHECKED_OUT);
        when(provider.get(spared)).thenReturn(BRANCH).thenAnswer(invocation -> {
            Files.write(own, Arrays.asList(infobasesLine(ref) + ";" + STALE), StandardCharsets.ISO_8859_1); //$NON-NLS-1$
            return BRANCH;
        });

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(spared), project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must see the entry written meanwhile: " + result, //$NON-NLS-1$
            result.contains("The bindings changed while this detach from '" + FULL + "' was being checked: its " //$NON-NLS-1$ //$NON-NLS-2$
                + "binding file now also holds: infobase entry " + STALE)); //$NON-NLS-1$
        verify(manager, never()).dissociate(any(), any(), any());
    }

    @Test
    public void aCheckoutOfTheProjectWhileTheDependentsAreReCheckedRefusesACheckedOutBranchDetach()
    {
        // From the branch EDT reads, EDT rewrites the project's file; once another branch is checked out it deletes
        // it whole, sibling and all. The checkout lands while the re-check asks EDT about a dependent, so the
        // project's own branch must be read after the dependents, as the last read before EDT's write.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        InfobaseReference sibling = infobase("SiblingBase"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref, sibling));
        IProject spared = boundDependent(manager, "Spared", "SparedBase"); //$NON-NLS-1$ //$NON-NLS-2$
        AtomicReference<InfobaseAssociationContext> projectReads = new AtomicReference<>(BRANCH);
        IInfobaseAssociationContextProvider provider = mock(IInfobaseAssociationContextProvider.class);
        when(provider.get(project)).thenAnswer(invocation -> projectReads.get());
        when(provider.get(spared)).thenReturn(BRANCH).thenAnswer(invocation -> {
            projectReads.set(CHECKED_OUT);
            return BRANCH;
        });

        String result = SetBranchInfobaseTool.applyResolved(manager, provider, () -> List.of(spared), project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the re-check must name the checkout: " + result, //$NON-NLS-1$
            result.contains("the branch EDT reads for this project changed from '" + SHORT + "' to 'master'")); //$NON-NLS-1$ //$NON-NLS-2$
        verify(manager, never()).dissociate(any(), any(), any());
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

    // ==================== #656 round 5: a branch change of this server still running refuses a detach ====================
    //
    // A checkout Job that outlived its bounded wait (or the refresh after a git checkout that outlived its own) is
    // recorded as still running until it ends, instead of the lock being held across it. When it lands it changes the
    // branch EDT reads, and with it what EDT's write deletes, so a detach for its project or a dependent refuses
    // until it ends.

    /** The detach of the base bound to the checked-out branch, next to a sibling base, of project {@code name}. */
    private static IProject projectWithASiblingOnTheBranch(IInfobaseAssociationManager manager, String name,
        InfobaseReference ref)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        InfobaseReference sibling = infobase("SiblingBase"); //$NON-NLS-1$
        when(manager.getAssociation(project, BRANCH)).thenReturn(association(ref, sibling));
        return project;
    }

    @Test(timeout = 60_000)
    public void aDetachRefusesWhileACheckoutOfThisServerIsStillRunningForTheProjectAndGoesThroughOnceItEnds()
        throws Exception
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        IProject project = projectWithASiblingOnTheBranch(manager, "Running656", ref); //$NON-NLS-1$
        BlockingJob checkout = BlockingJob.running();
        try
        {
            BlockingJob.record(checkout, List.of("Running656")); //$NON-NLS-1$

            String refused = SetBranchInfobaseTool.applyResolved(manager, currentIs(BRANCH), Collections::emptyList,
                project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

            assertTrue(refused, refused.contains("\"success\":false")); //$NON-NLS-1$
            assertTrue("the refusal must name the project and the way out: " + refused, //$NON-NLS-1$
                refused.contains("Did not detach: a branch change made by this server is still running for " //$NON-NLS-1$
                    + "'Running656' - retry once it ends.")); //$NON-NLS-1$
            verify(manager, never()).dissociate(any(), any(), any());
        }
        finally
        {
            checkout.finish();
        }
        BlockingJob.awaitNotInFlight("Running656"); //$NON-NLS-1$

        String done = SetBranchInfobaseTool.applyResolved(manager, currentIs(BRANCH), Collections::emptyList, project,
            SHORT, "app1", ref, false, false); //$NON-NLS-1$

        assertTrue("once the checkout ended the detach goes through: " + done, //$NON-NLS-1$
            done.contains("\"success\":true")); //$NON-NLS-1$
        verify(manager).dissociate(project, ref, BRANCH);
    }

    @Test(timeout = 60_000)
    public void aDetachRefusesWhileABranchChangeOfThisServerIsStillRunningForADependent() throws Exception
    {
        // The extension follows the checkout of the branch (it reads it too), so EDT would keep its file - but a
        // branch change of its repository is still landing.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        IProject project = projectWithASiblingOnTheBranch(manager, "Base656", ref); //$NON-NLS-1$
        IProject extension = mock(IProject.class);
        when(extension.getName()).thenReturn("Ext656"); //$NON-NLS-1$
        InfobaseReference extBase = infobase("ExtBase"); //$NON-NLS-1$
        when(manager.getAssociation(extension, BRANCH)).thenReturn(association(extBase));
        BlockingJob refresh = BlockingJob.running();
        try
        {
            BlockingJob.record(refresh, List.of("Ext656")); //$NON-NLS-1$

            String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(BRANCH), () -> List.of(extension),
                project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

            assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
            assertTrue("the refusal must name the dependent: " + result, //$NON-NLS-1$
                result.contains("still running for 'Ext656' - retry once it ends")); //$NON-NLS-1$
            verify(manager, never()).dissociate(any(), any(), any());
        }
        finally
        {
            refresh.finish();
        }
        BlockingJob.awaitNotInFlight("Ext656"); //$NON-NLS-1$
    }

    @Test(timeout = 60_000)
    public void aBranchChangeStillRunningForAnotherProjectDoesNotRefuseTheDetach() throws Exception
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        IProject project = projectWithASiblingOnTheBranch(manager, "Quiet656", ref); //$NON-NLS-1$
        BlockingJob elsewhere = BlockingJob.running();
        try
        {
            BlockingJob.record(elsewhere, List.of("Elsewhere656")); //$NON-NLS-1$

            String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(BRANCH), Collections::emptyList,
                project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

            assertTrue(result, result.contains("\"success\":true")); //$NON-NLS-1$
            verify(manager).dissociate(project, ref, BRANCH);
        }
        finally
        {
            elsewhere.finish();
        }
        BlockingJob.awaitNotInFlight("Elsewhere656"); //$NON-NLS-1$
    }

    @Test(timeout = 60_000)
    public void dependentsThatCannotBeListedWhileABranchChangeIsStillRunningRefuseTheDetach() throws Exception
    {
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        IProject project = projectWithASiblingOnTheBranch(manager, "Unlisted656", ref); //$NON-NLS-1$
        BlockingJob elsewhere = BlockingJob.running();
        try
        {
            BlockingJob.record(elsewhere, List.of("Elsewhere656b")); //$NON-NLS-1$

            String result = SetBranchInfobaseTool.applyResolved(manager, currentIs(BRANCH), () -> {
                throw new IllegalStateException("dependents unavailable"); //$NON-NLS-1$
            }, project, SHORT, "app1", ref, false, false); //$NON-NLS-1$

            assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
            assertTrue("a dependent that cannot be listed cannot be ruled out: " + result, //$NON-NLS-1$
                result.contains("still running for a project this detach could not rule out")); //$NON-NLS-1$
            verify(manager, never()).dissociate(any(), any(), any());
        }
        finally
        {
            elsewhere.finish();
        }
        BlockingJob.awaitNotInFlight("Elsewhere656b"); //$NON-NLS-1$
    }

    @Test(timeout = 60_000)
    public void aBranchChangeRecordedWhileTheDetachIsPlannedRefusesItAtTheReCheck() throws Exception
    {
        // Recorded by the planning thread itself (the lock is reentrant), after the first look and before the
        // re-check: the re-check looks again.
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        InfobaseReference ref = infobase("Base1"); //$NON-NLS-1$
        IProject project = projectWithASiblingOnTheBranch(manager, "Planned656", ref); //$NON-NLS-1$
        BlockingJob late = BlockingJob.running();
        IInfobaseAssociationContextProvider provider = mock(IInfobaseAssociationContextProvider.class);
        when(provider.get(project)).thenAnswer(invocation -> {
            BlockingJob.record(late, List.of("Planned656")); //$NON-NLS-1$
            return BRANCH;
        });
        try
        {
            String result = SetBranchInfobaseTool.applyResolved(manager, provider, Collections::emptyList, project,
                SHORT, "app1", ref, false, false); //$NON-NLS-1$

            assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
            assertTrue("the re-check must look for it again: " + result, //$NON-NLS-1$
                result.contains("still running for 'Planned656' - retry once it ends")); //$NON-NLS-1$
            verify(manager, never()).dissociate(any(), any(), any());
        }
        finally
        {
            late.finish();
        }
        BlockingJob.awaitNotInFlight("Planned656"); //$NON-NLS-1$
    }
}
