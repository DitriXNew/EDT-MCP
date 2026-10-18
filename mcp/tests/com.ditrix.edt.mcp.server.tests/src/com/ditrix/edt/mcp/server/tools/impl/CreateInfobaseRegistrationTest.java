/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.utils.InfobaseTarget;
import com.ditrix.edt.mcp.server.utils.ServerInfobaseTarget;
import com.ditrix.edt.mcp.server.utils.git.BranchContexts;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.IApplicationType;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.google.gson.JsonParser;

public class CreateInfobaseRegistrationTest
{
    @Test
    public void fileAndServerShareNamingCredentialsBeforeAssociationAndReadBack() throws Exception
    {
        var file = Files.createTempDirectory("mcp-registration"); //$NON-NLS-1$
        Files.createFile(file.resolve("1Cv8.1CD")); //$NON-NLS-1$
        try
        {
            for (var target : List.of(new InfobaseTarget(file, null),
                new InfobaseTarget(null, new ServerInfobaseTarget("host", "base")))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                var project = mock(IProject.class);
                var applications = mock(IApplicationManager.class);
                var infobases = recordingInfobases();
                var associations = mock(IInfobaseAssociationManager.class);
                when(infobases.generateInfobaseName()).thenReturn("generated"); //$NON-NLS-1$
                var context = BranchContexts.toContext("feat/test"); //$NON-NLS-1$
                boolean[] credentialsStored = {false};
                var tool = new CreateInfobaseTool()
                {
                    @Override
                    CredentialStoreReport storeCredentialsIfRequested(InfobaseReference ref,
                            Credentials credentials, boolean register)
                    {
                        credentialsStored[0] = true;
                        return null;
                    }
                };
                doAnswer(invocation -> {
                    assertTrue("credentials must be ready before EDT connects", credentialsStored[0]); //$NON-NLS-1$
                    var ref = (InfobaseReference)invocation.getArgument(1);
                    assertEquals("generated", ref.getName()); //$NON-NLS-1$
                    assertNotNull(ref.getUuid());
                    assertEquals(context, ((com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings)
                        invocation.getArgument(2)).getContext());
                    var type = mock(IApplicationType.class);
                    when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.infobase"); //$NON-NLS-1$
                    var app = mock(IInfobaseApplication.class);
                    when(app.getType()).thenReturn(type);
                    when(app.getInfobase()).thenReturn(ref);
                    when(app.getName()).thenReturn(ref.getName());
                    when(app.getId()).thenReturn("fresh-app"); //$NON-NLS-1$
                    when(applications.getApplications(project)).thenReturn(List.of(app));
                    return null;
                }).when(associations).associate(eq(project), any(), any());
                var result = JsonParser.parseString(tool.prepareInfobase(
                    CreateInfobaseTool.CreateContext.of(project, applications, infobases, associations),
                    context, "project", target, "  ", null, false, true, //$NON-NLS-1$ //$NON-NLS-2$
                    new CreateInfobaseTool.Credentials(null, null, null))).getAsJsonObject();
                assertTrue(result.toString(), result.get("boundToProject").getAsBoolean()); //$NON-NLS-1$
                assertEquals("fresh-app", result.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
                var registered = org.mockito.ArgumentCaptor.forClass(InfobaseReference.class);
                verify(infobases).add(registered.capture(), isNull());
                assertEquals(registered.getValue().getUuid().toString(), result.get("registrationId").getAsString()); //$NON-NLS-1$
            }
        }
        finally
        {
            Files.delete(file.resolve("1Cv8.1CD")); //$NON-NLS-1$
            Files.delete(file);
        }
    }

    @Test
    public void failedAssociationAndNotBoundBothRollbackTheOwnedRegistration() throws Exception
    {
        for (boolean associationFails : List.of(true, false))
        {
            var project = mock(IProject.class);
            var applications = mock(IApplicationManager.class);
            var infobases = recordingInfobases();
            var associations = mock(IInfobaseAssociationManager.class);
            when(applications.getApplications(project)).thenReturn(List.of());
            if (associationFails)
            {
                doThrow(new IllegalStateException("refused")).when(associations).associate(any(), any(), any()); //$NON-NLS-1$
            }
            var context = BranchContexts.toContext("feat/test"); //$NON-NLS-1$
            var result = JsonParser.parseString(new CreateInfobaseTool().prepareInfobase(
                CreateInfobaseTool.CreateContext.of(project, applications, infobases, associations),
                context, "project", new InfobaseTarget(null, new ServerInfobaseTarget("host", "base")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "test", null, false, true, new CreateInfobaseTool.Credentials(null, null, null))).getAsJsonObject(); //$NON-NLS-1$
            assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(result.get("rollbackCompleted").getAsBoolean()); //$NON-NLS-1$
            var ref = org.mockito.ArgumentCaptor.forClass(InfobaseReference.class);
            verify(infobases).add(ref.capture(), isNull());
            assertEquals(ref.getValue().getUuid().toString(), result.get("registrationId").getAsString()); //$NON-NLS-1$
            var order = inOrder(associations, infobases);
            order.verify(associations).dissociate(project, ref.getValue(), context);
            var deleted = org.mockito.ArgumentCaptor.forClass(InfobaseReference.class);
            order.verify(infobases).delete(deleted.capture());
            assertNotSame("EDT reload replaces the registered object", ref.getValue(), deleted.getValue()); //$NON-NLS-1$
            assertEquals(ref.getValue().getUuid(), deleted.getValue().getUuid());
            verify(applications, never()).setDefaultApplication(any(), any());
        }
    }

    @Test
    public void failedCredentialRollbackReturnsTheGeneratedNameAndRetainedUuid() throws Exception
    {
        var project = mock(IProject.class);
        var applications = mock(IApplicationManager.class);
        var infobases = recordingInfobases();
        var associations = mock(IInfobaseAssociationManager.class);
        when(infobases.generateInfobaseName()).thenReturn("generated-retained"); //$NON-NLS-1$
        doThrow(new IllegalStateException("refused")).when(associations).associate(any(), any(), any()); //$NON-NLS-1$
        var tool = new CreateInfobaseTool()
        {
            @Override
            CredentialStoreReport storeCredentialsIfRequested(InfobaseReference reference,
                    Credentials credentials, boolean register)
            {
                return null;
            }

            @Override
            void clearRegistrationCredentials(InfobaseReference reference,
                    com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport.CredentialWriteGuard guard)
            {
                throw new IllegalStateException("secure storage unavailable"); //$NON-NLS-1$
            }
        };
        var result = JsonParser.parseString(tool.prepareInfobase(
            CreateInfobaseTool.CreateContext.of(project, applications, infobases, associations),
            BranchContexts.toContext("feat/test"), "project", //$NON-NLS-1$ //$NON-NLS-2$
            new InfobaseTarget(null, new ServerInfobaseTarget("host", "base")), //$NON-NLS-1$ //$NON-NLS-2$
            " ", null, false, true, new CreateInfobaseTool.Credentials("Admin", "secret", null))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.get("credentialsRemoved").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.get("registrationRemoved").getAsBoolean()); //$NON-NLS-1$
        assertEquals("generated-retained", result.get("infobaseName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("project", result.get("project").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("host", result.get("infobaseServer").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("base", result.get("infobaseRef").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        var registered = org.mockito.ArgumentCaptor.forClass(InfobaseReference.class);
        verify(infobases).add(registered.capture(), isNull());
        assertEquals(registered.getValue().getUuid().toString(), result.get("registrationId").getAsString()); //$NON-NLS-1$
        assertTrue(infobases.findInfobaseByUuid(registered.getValue().getUuid()).isPresent());
        verify(infobases, never()).delete(any());
    }

    @Test
    public void duplicateAddressIsRefusedBeforeCredentialStorageOrAssociation()
    {
        var project = mock(IProject.class);
        var applications = mock(IApplicationManager.class);
        var infobases = mock(IInfobaseManager.class);
        var associations = mock(IInfobaseAssociationManager.class);
        var target = new ServerInfobaseTarget("host", "base"); //$NON-NLS-1$ //$NON-NLS-2$
        when(infobases.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        when(infobases.getAll()).thenReturn(List.of(target.reference("existing"))); //$NON-NLS-1$
        var tool = new CreateInfobaseTool()
        {
            @Override
            CredentialStoreReport storeCredentialsIfRequested(InfobaseReference ref,
                    Credentials credentials, boolean register)
            {
                fail("duplicate must not store credentials"); //$NON-NLS-1$
                return null;
            }
        };
        var result = JsonParser.parseString(tool.prepareInfobase(
            CreateInfobaseTool.CreateContext.of(project, applications, infobases, associations),
            BranchContexts.toContext("feat/test"), "project", new InfobaseTarget(null, target), //$NON-NLS-1$ //$NON-NLS-2$
            "duplicate", null, false, true, new CreateInfobaseTool.Credentials("Admin", "secret", null))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.get("error").getAsString().contains("already registered")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("duplicate", result.get("infobaseName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(result.get("registrationId")); //$NON-NLS-1$
        verifyZeroInteractions(associations, applications);
        verify(infobases, never()).add(any(), any());
    }

    private static IInfobaseManager recordingInfobases()
    {
        var manager = mock(IInfobaseManager.class);
        var records = new java.util.HashMap<java.util.UUID, InfobaseReference>();
        when(manager.getAll()).thenAnswer(invocation -> new java.util.ArrayList<>(records.values()));
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        when(manager.findInfobaseByUuid(any())).thenAnswer(invocation ->
            java.util.Optional.ofNullable(records.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            InfobaseReference reference = invocation.getArgument(0);
            records.put(reference.getUuid(), org.eclipse.emf.ecore.util.EcoreUtil.copy(reference));
            return null;
        }).when(manager).add(any(), any());
        doAnswer(invocation -> {
            InfobaseReference reference = invocation.getArgument(0);
            assertSame("delete requires the current reloaded object", records.get(reference.getUuid()), reference); //$NON-NLS-1$
            records.remove(reference.getUuid());
            return null;
        }).when(manager).delete(any());
        return manager;
    }
}
