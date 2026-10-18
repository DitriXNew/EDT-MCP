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
                    new CreateInfobaseTool.Credentials(null, null, null), () -> context)).getAsJsonObject();
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
            boolean[] cleared = {false};
            var tool = new CreateInfobaseTool() {
                @Override
                void clearRegistrationCredentials(InfobaseReference ref,
                        com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport.CredentialWriteGuard guard) throws Exception
                {
                    guard.run(current -> cleared[0] = true);
                }
            };
            var result = JsonParser.parseString(tool.prepareInfobase(
                CreateInfobaseTool.CreateContext.of(project, applications, infobases, associations),
                context, "project", new InfobaseTarget(null, new ServerInfobaseTarget("host", "base")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "test", null, false, true, new CreateInfobaseTool.Credentials(null, null, null), () -> context)).getAsJsonObject(); //$NON-NLS-1$
            assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(result.get("rollbackCompleted").getAsBoolean());
            assertTrue("rollback must clear credentials even when create supplied none", cleared[0]); //$NON-NLS-1$
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
            " ", null, false, true, new CreateInfobaseTool.Credentials("Admin", "secret", null), () -> BranchContexts.toContext("feat/test"))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
            "duplicate", null, false, true, new CreateInfobaseTool.Credentials("Admin", "secret", null), () -> BranchContexts.toContext("feat/test"))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.get("error").getAsString().contains("already registered")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("duplicate", result.get("infobaseName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(result.get("registrationId")); //$NON-NLS-1$
        verifyZeroInteractions(associations, applications);
        verify(infobases, never()).add(any(), any());
    }

    @Test
    public void branchChangesBeforeAssociationOrDuringReadBackCompensateBothModesAndKeepFiles() throws Exception
    {
        for (boolean register : new boolean[] {false, true})
        {
            for (int phase : new int[] {0, 1, 2})
            {
                var file = Files.createTempDirectory("mcp-context-change"); //$NON-NLS-1$
                var database = file.resolve("1Cv8.1CD"); //$NON-NLS-1$
                if (register)
                {
                    Files.writeString(database, "existing database sentinel"); //$NON-NLS-1$
                }
                try
                {
                    var project = mock(IProject.class);
                    var applications = mock(IApplicationManager.class);
                    var infobases = recordingInfobases();
                    var associations = mock(IInfobaseAssociationManager.class);
                    var original = BranchContexts.toContext("feat/original"); //$NON-NLS-1$
                    var changed = BranchContexts.toContext("feat/changed"); //$NON-NLS-1$
                    var current = new java.util.concurrent.atomic.AtomicReference<>(original);
                    int switchPhase = phase;
                    if (register && phase == 0)
                    {
                        doAnswer(call -> {
                            current.set(changed);
                            return null;
                        }).when(infobases).reload(null);
                    }
                    var stored = new java.util.concurrent.atomic.AtomicBoolean();
                    var cleared = new java.util.concurrent.atomic.AtomicBoolean();
                    var tool = new CreateInfobaseTool()
                    {
                        @Override
                        String createNewInfobase(InfobaseReference ref, String platform,
                                String name, java.nio.file.Path directory)
                        {
                            try
                            {
                                Files.writeString(database, "new database sentinel"); //$NON-NLS-1$
                                infobases.add(ref, null);
                                if (switchPhase == 0)
                                {
                                    current.set(changed);
                                }
                                return null;
                            }
                            catch (Exception failure)
                            {
                                throw new AssertionError(failure);
                            }
                        }

                        @Override
                        CredentialStoreReport storeCredentialsIfRequested(InfobaseReference ref,
                                Credentials credentials, boolean existing)
                        {
                            stored.set(true);
                            if (switchPhase == 1)
                            {
                                current.set(changed);
                            }
                            return null;
                        }

                        @Override
                        void clearRegistrationCredentials(InfobaseReference ref,
                                com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport.CredentialWriteGuard guard)
                                throws Exception
                        {
                            guard.run(target -> cleared.set(true));
                        }
                    };
                    doAnswer(call -> {
                        var ref = (InfobaseReference)call.getArgument(1);
                        var type = mock(IApplicationType.class);
                        when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.infobase"); //$NON-NLS-1$
                        var app = mock(IInfobaseApplication.class);
                        when(app.getType()).thenReturn(type);
                        when(app.getInfobase()).thenReturn(ref);
                        when(app.getId()).thenReturn("new-app"); //$NON-NLS-1$
                        when(applications.getApplications(project)).thenAnswer(read -> {
                            current.set(changed);
                            return List.of(app);
                        });
                        return null;
                    }).when(associations).associate(any(), any(), any());
                    var result = JsonParser.parseString(tool.prepareInfobase(
                        CreateInfobaseTool.CreateContext.of(project, applications, infobases, associations),
                        original, "project", new InfobaseTarget(file, null), "context-test", null, true, //$NON-NLS-1$ //$NON-NLS-2$
                        register, new CreateInfobaseTool.Credentials("Admin", "secret", null), current::get)) //$NON-NLS-1$ //$NON-NLS-2$
                        .getAsJsonObject();
                    assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
                    assertTrue(result.toString(), result.get("rollbackCompleted").getAsBoolean()); //$NON-NLS-1$
                    assertTrue(result.get("error").getAsString().contains("context changed")); //$NON-NLS-1$ //$NON-NLS-2$
                    assertEquals(phase != 0, stored.get());
                    assertTrue(cleared.get());
                    verify(associations, times(phase == 2 ? 1 : 0)).associate(any(), any(), any());
                    var removed = org.mockito.ArgumentCaptor.forClass(InfobaseReference.class);
                    verify(infobases).delete(removed.capture());
                    var unbound = org.mockito.ArgumentCaptor.forClass(InfobaseReference.class);
                    verify(associations).dissociate(eq(project), unbound.capture(), eq(original));
                    assertEquals(removed.getValue().getUuid(), unbound.getValue().getUuid());
                    verify(applications, never()).setDefaultApplication(any(), any());
                    assertEquals(register ? "existing database sentinel" : "new database sentinel", Files.readString(database)); //$NON-NLS-1$ //$NON-NLS-2$
                }
                finally
                {
                    Files.deleteIfExists(database);
                    Files.delete(file);
                }
            }
        }
    }

    @Test
    public void contextRollbackRestoresOnlyItsOwnDefaultBeforeRemovingTheApplication() throws Exception
    {
        for (boolean register : new boolean[] {false, true})
        {
            for (boolean hadExplicitDefault : new boolean[] {false, true})
            {
                for (int choice : new int[] {0, 1, 2, 3, 4, 5})
                {
                    if ((!hadExplicitDefault && choice >= 3) || (choice == 5 && !register))
                    {
                        continue;
                    }
                    boolean newerChoice = choice == 1;
                    boolean unavailable = choice == 2;
                    boolean priorRemoved = choice == 3;
                    boolean priorLookupFailed = choice == 4;
                    boolean pending = choice == 5;
                    boolean retain = unavailable || priorRemoved || priorLookupFailed || pending;
                    var releaseRestore = new java.util.concurrent.CompletableFuture<Void>();
                    var nativeReturned = new java.util.concurrent.CompletableFuture<Void>();
                    var file = Files.createTempDirectory("mcp-default-rollback"); //$NON-NLS-1$
                    var database = file.resolve("1Cv8.1CD"); //$NON-NLS-1$
                    if (register)
                    {
                        Files.writeString(database, "existing default sentinel"); //$NON-NLS-1$
                    }
                    try
                    {
                        var project = mock(IProject.class);
                        when(project.getName()).thenReturn("project"); //$NON-NLS-1$
                        var applications = mock(IApplicationManager.class);
                        var infobases = recordingInfobases();
                        var associations = mock(IInfobaseAssociationManager.class);
                        var original = BranchContexts.toContext("feat/original"); //$NON-NLS-1$
                        var changed = BranchContexts.toContext("feat/changed"); //$NON-NLS-1$
                        var current = new java.util.concurrent.atomic.AtomicReference<>(original);
                        var prior = mock(com.e1c.g5.dt.applications.IApplication.class);
                        when(prior.getId()).thenReturn("prior-app"); //$NON-NLS-1$
                        when(applications.getApplication(project, "prior-app")).thenAnswer(call -> { //$NON-NLS-1$
                            if (current.get().equals(changed))
                            {
                                if (priorRemoved)
                                {
                                    return java.util.Optional.empty();
                                }
                                if (priorLookupFailed)
                                {
                                    throw new IllegalStateException("prior application lookup unavailable"); //$NON-NLS-1$
                                }
                            }
                            return java.util.Optional.of(prior);
                        });
                        var rawDefault = new java.util.concurrent.atomic.AtomicReference<String>(hadExplicitDefault ? "prior-app" : null); //$NON-NLS-1$
                        // Emulate EDT's trap: resolving a default after a branch switch clears the
                        // stale raw preference and may return an implicit, unrelated application.
                        when(applications.getDefaultApplication(project)).thenAnswer(call -> {
                            rawDefault.set(null);
                            return java.util.Optional.of(prior);
                        });
                        var tool = new CreateInfobaseTool()
                        {
                            @Override
                            com.ditrix.edt.mcp.server.utils.ApplicationDefaultChange captureDefaultChange(
                                    IApplicationManager manager, IProject target)
                            {
                                return com.ditrix.edt.mcp.server.utils.ApplicationDefaultChange.capture(manager, target, () -> {
                                    if (unavailable && current.get().equals(changed))
                                    {
                                        throw new IllegalStateException("preference unavailable"); //$NON-NLS-1$
                                    }
                                    return rawDefault.get();
                                });
                            }

                            @Override
                            String createNewInfobase(InfobaseReference ref, String platform,
                                    String name, java.nio.file.Path directory)
                            {
                                try
                                {
                                    Files.writeString(database, "new default sentinel"); //$NON-NLS-1$
                                    infobases.add(ref, null);
                                    return null;
                                }
                                catch (Exception failure)
                                {
                                    throw new AssertionError(failure);
                                }
                            }

                            @Override
                            void clearRegistrationCredentials(InfobaseReference ref,
                                    com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport.CredentialWriteGuard guard)
                                    throws Exception
                            {
                                guard.run(target -> { });
                            }
                        };
                        doAnswer(call -> {
                            var ref = (InfobaseReference)call.getArgument(1);
                            var type = mock(IApplicationType.class);
                            when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.infobase"); //$NON-NLS-1$
                            var app = mock(IInfobaseApplication.class);
                            when(app.getType()).thenReturn(type);
                            when(app.getInfobase()).thenReturn(ref);
                            when(app.getId()).thenReturn("owned-app"); //$NON-NLS-1$
                            when(applications.getApplications(project)).thenReturn(List.of(app));
                            return null;
                        }).when(associations).associate(any(), any(), any());
                        doAnswer(call -> {
                            com.e1c.g5.dt.applications.IApplication app = call.getArgument(1);
                            if (pending && app == prior)
                            {
                                releaseRestore.join(); // The admitted native write ignores cancellation.
                            }
                            rawDefault.set(app == null ? null : app.getId());
                            if (pending && app == prior)
                            {
                                nativeReturned.complete(null);
                            }
                            if (app != null && "owned-app".equals(app.getId())) //$NON-NLS-1$
                            {
                                current.set(changed);
                                if (newerChoice)
                                {
                                    rawDefault.set("concurrent-choice"); //$NON-NLS-1$
                                }
                            }
                            return null;
                        }).when(applications).setDefaultApplication(eq(project), any());
                        String expectedDefault = retain ? "owned-app" : newerChoice ? "concurrent-choice" : hadExplicitDefault ? "prior-app" : null; //$NON-NLS-1$ //$NON-NLS-2$
                        doAnswer(call -> {
                            assertEquals("restore must precede removal, without a stale-clearing default lookup", expectedDefault, rawDefault.get()); //$NON-NLS-1$
                            return null;
                        }).when(associations).dissociate(any(), any(), any());
                        var result = JsonParser.parseString(tool.prepareInfobase(
                            CreateInfobaseTool.CreateContext.of(project, applications, infobases, associations),
                            original, "project", new InfobaseTarget(file, null), "default-test", null, true, //$NON-NLS-1$ //$NON-NLS-2$
                            register, new CreateInfobaseTool.Credentials(null, null, null), current::get)).getAsJsonObject();
                        assertFalse(result.toString(), result.get("success").getAsBoolean()); //$NON-NLS-1$
                        assertEquals(result.toString(), !retain, result.get("rollbackCompleted").getAsBoolean()); //$NON-NLS-1$
                        assertEquals(pending ? "PENDING" : retain ? "UNVERIFIED" : newerChoice ? "PRESERVED_OTHER" : "RESTORED", result.get("defaultRollback").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        assertEquals(pending, result.get("defaultWriteInProgress").getAsBoolean()); //$NON-NLS-1$
                        assertEquals(expectedDefault, rawDefault.get());
                        verify(applications, never()).getDefaultApplication(any());
                        verify(applications, times(pending ? 2 : newerChoice || retain ? 1 : 2)).setDefaultApplication(eq(project), any());
                        verify(infobases, times(retain ? 0 : 1)).delete(any());
                        verify(associations, times(retain ? 0 : 1)).dissociate(any(), any(), any());
                        if (retain)
                        {
                            assertTrue(result.get("error").getAsString().contains("retained")); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        if (pending)
                        {
                            assertFalse(nativeReturned.isDone());
                            String error = result.get("error").getAsString(); //$NON-NLS-1$
                            assertTrue(error, error.contains("native write may complete after this response")); //$NON-NLS-1$
                            assertTrue(error, error.contains("Wait") && error.contains("before inspecting/changing")); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        assertEquals(register ? "existing default sentinel" : "new default sentinel", Files.readString(database)); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    finally
                    {
                        releaseRestore.complete(null);
                        if (pending)
                        {
                            nativeReturned.get(3, java.util.concurrent.TimeUnit.SECONDS);
                        }
                        Files.deleteIfExists(database);
                        Files.delete(file);
                    }
                }
            }
        }
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
