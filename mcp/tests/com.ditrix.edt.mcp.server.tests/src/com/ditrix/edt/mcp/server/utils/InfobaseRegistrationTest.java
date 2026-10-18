/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationContextProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com.ditrix.edt.mcp.server.utils.git.BranchContexts;

public class InfobaseRegistrationTest
{
    @Test
    public void bindingUsesTheSameBranchDetachedOrUnsharedContextAsEdt()
    {
        IProject project = mock(IProject.class);
        IInfobaseAssociationContextProvider provider = mock(IInfobaseAssociationContextProvider.class);
        IInfobaseAssociationManager manager = mock(IInfobaseAssociationManager.class);
        var reference = new ServerInfobaseTarget("host", "base").reference("test"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (var expected : List.of(BranchContexts.toContext("feat/register"), //$NON-NLS-1$
            InfobaseAssociationContext.of("1234567890123456789012345678901234567890"), //$NON-NLS-1$
            InfobaseAssociationContext.empty()))
        {
            when(provider.get(project)).thenReturn(expected);
            var actual = InfobaseRegistration.currentContext(provider, project);
            assertEquals(expected, actual);
            InfobaseRegistration.associate(manager, project, reference, actual);
            var settings = ArgumentCaptor.forClass(InfobaseAssociationSettings.class);
            verify(manager).associate(eq(project), eq(reference), settings.capture());
            assertEquals(expected, settings.getValue().getContext());
            clearInvocations(manager);
        }
    }

    @Test
    public void duplicateInNestedLauncherGroupIsRefusedBeforeAdd()
    {
        IInfobaseManager manager = mock(IInfobaseManager.class);
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        var existing = new ServerInfobaseTarget("HOST:1541", "base").reference("existing"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var group = ModelFactory.eINSTANCE.createGroup();
        group.getSubsections().add(existing);
        when(manager.getAll()).thenReturn(List.of(group));
        var fresh = new ServerInfobaseTarget("host:1541", "base").reference("fresh"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotEquals(existing.getUuid(), fresh.getUuid());
        assertTrue(InfobaseRegistration.add(manager, fresh).contains("already registered")); //$NON-NLS-1$
        verify(manager, never()).add(any(), any());
        verify(manager, never()).delete(any());
    }

    @Test
    public void rollbackUsesCapturedContextAndStillDeletesWhenDissociationFails() throws Exception
    {
        IInfobaseManager infobases = mock(IInfobaseManager.class);
        IInfobaseAssociationManager associations = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        var reference = new ServerInfobaseTarget("host", "base").reference("test"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var context = BranchContexts.toContext("feat/original"); //$NON-NLS-1$
        when(infobases.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        when(infobases.findInfobaseByUuid(reference.getUuid())).thenReturn(
            java.util.Optional.of(reference), java.util.Optional.of(reference), java.util.Optional.empty());
        doThrow(new IllegalStateException("secret")).when(associations).dissociate(project, reference, context); //$NON-NLS-1$
        boolean[] cleared = {false};
        var result = InfobaseRegistration.rollback(associations, infobases, project, reference, context,
            (ref, guard) -> guard.run(current -> { assertSame(reference, current); cleared[0] = true; }));
        assertFalse(result.associationRemoved());
        assertTrue(result.registrationRemoved());
        assertTrue(result.credentialsRemoved());
        assertTrue(cleared[0]);
        verify(infobases).delete(reference);
        assertFalse(result.message().contains("secret")); //$NON-NLS-1$
        assertTrue(result.message().contains("could NOT be removed")); //$NON-NLS-1$
    }

    @Test
    public void rollbackReportsCredentialAndLauncherFailuresWithoutClaimingSuccess() throws Exception
    {
        IInfobaseManager infobases = mock(IInfobaseManager.class);
        IInfobaseAssociationManager associations = mock(IInfobaseAssociationManager.class);
        IProject project = mock(IProject.class);
        var reference = new ServerInfobaseTarget("host", "base").reference("test"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var context = InfobaseAssociationContext.empty();
        when(infobases.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        when(infobases.findInfobaseByUuid(reference.getUuid())).thenReturn(java.util.Optional.of(reference));
        doThrow(new IllegalStateException("secret")).when(infobases).delete(reference); //$NON-NLS-1$
        var result = InfobaseRegistration.rollback(associations, infobases, project, reference, context,
            (ref, guard) -> { throw new IllegalStateException("secret"); }); //$NON-NLS-1$
        assertTrue(result.associationRemoved());
        assertFalse(result.registrationRemoved());
        assertFalse(result.credentialsRemoved());
        verify(associations).dissociate(project, reference, context);
        verify(infobases, never()).delete(any());
        assertFalse(result.message().contains("secret")); //$NON-NLS-1$
    }

    @Test
    public void referenceFactoryStampsBothFileAndServerBeforeUse()
    {
        var file = new InfobaseTarget(java.nio.file.Path.of("test"), null).reference("file"); //$NON-NLS-1$ //$NON-NLS-2$
        var server = new ServerInfobaseTarget("host", "base").reference("server"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("file", file.getName()); //$NON-NLS-1$
        assertEquals("server", server.getName()); //$NON-NLS-1$
        assertNotNull(file.getUuid());
        assertNotNull(server.getUuid());
        assertNotEquals(file.getUuid(), server.getUuid());
    }

    @Test
    public void removeResolvesTheReloadedUuidAndDetectsASwallowedSaveFailure() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        var detached = new ServerInfobaseTarget("host", "base").reference("test"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var loaded = org.eclipse.emf.ecore.util.EcoreUtil.copy(detached);
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        // A swallowed save failure leaves the same UUID on disk after reload.
        when(manager.findInfobaseByUuid(detached.getUuid())).thenReturn(java.util.Optional.of(loaded));
        try
        {
            InfobaseRegistration.remove(manager, detached);
            fail("a surviving UUID must refuse the deletion claim"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("from disk")); //$NON-NLS-1$
        }
        verify(manager).delete(loaded);
        verify(manager, never()).delete(detached);
        verify(manager, times(2)).reload(null);
    }

    @Test
    public void anAddExceptionAfterPersistenceIsCompensatedByFreshUuid() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        var reference = new ServerInfobaseTarget("host", "base").reference("test"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var loaded = org.eclipse.emf.ecore.util.EcoreUtil.copy(reference);
        when(manager.getAll()).thenReturn(List.of());
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        doThrow(new IllegalStateException("listener failed")).when(manager).add(reference, null); //$NON-NLS-1$
        when(manager.findInfobaseByUuid(reference.getUuid())).thenReturn(
            java.util.Optional.of(loaded), java.util.Optional.empty());
        var error = InfobaseRegistration.add(manager, reference);
        assertNotNull(error);
        assertTrue(error, error.contains("entry removed")); //$NON-NLS-1$
        verify(manager).delete(loaded);
        verify(manager, never()).delete(reference);
    }

    @Test
    public void launcherCleanupNeverSelectsASameNamedReplacementForAStaleUuid() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        var server = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(manager.findInfobaseByUuid(server.getUuid())).thenReturn(java.util.Optional.of(server));
        assertSame(server, InfobaseRegistration.findRegistration(manager, server.getUuid(), "probe")); //$NON-NLS-1$
        var replacement = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(manager.getAll()).thenReturn(List.of(replacement));
        when(manager.findInfobaseByUuid(server.getUuid())).thenReturn(java.util.Optional.empty());
        try
        {
            InfobaseRegistration.cleanupRegistration(manager, server.getUuid(), "probe", //$NON-NLS-1$
                server.getConnectionString().asConnectionString(),
                (ref, guard) -> fail("stale UUID must not clear credentials"), () -> { }); //$NON-NLS-1$
            fail("a stale UUID must never select a same-name replacement"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage().contains("registrationId")); //$NON-NLS-1$
        }
        verify(manager, never()).delete(any());
    }

    @Test
    public void failedCredentialCleanupRetainsTheUuidAndSuccessfulRetryRemovesIt() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        var reference = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var record = new java.util.concurrent.atomic.AtomicReference<>(reference);
        when(manager.findInfobaseByUuid(reference.getUuid())).thenAnswer(call -> java.util.Optional.ofNullable(record.get()));
        boolean[] credentialsPresent = {true};
        doAnswer(call -> {
            assertFalse("remove must follow successful credential cleanup", credentialsPresent[0]); //$NON-NLS-1$
            assertEquals(reference.getUuid(), ((com._1c.g5.v8.dt.platform.services.model.InfobaseReference)call.getArgument(0)).getUuid());
            record.set(null);
            return null;
        }).when(manager).delete(any());
        String connection = reference.getConnectionString().asConnectionString();
        try
        {
            InfobaseRegistration.cleanupRegistration(manager, reference.getUuid(), "probe", connection, //$NON-NLS-1$
                (ref, guard) -> { throw new IllegalStateException("secure storage locked"); }, () -> { }); //$NON-NLS-1$
            fail("credential failure must retain the registration"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            assertSame(reference, record.get());
            assertTrue(credentialsPresent[0]);
            verify(manager, never()).delete(any());
        }
        InfobaseRegistration.cleanupRegistration(manager, reference.getUuid(), "probe", connection, //$NON-NLS-1$
            (ref, guard) -> guard.run(current -> credentialsPresent[0] = false), () -> { });
        assertNull(record.get());
        verify(manager).delete(reference);
    }

    @Test
    public void changedNameOrConnectionNeverRemovesTheSelectedRegistration() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        var ref = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(manager.findInfobaseByUuid(ref.getUuid())).thenReturn(java.util.Optional.of(ref));
        try
        {
            InfobaseRegistration.findRegistration(manager, ref.getUuid(), "other"); //$NON-NLS-1$
            fail("a different name must be refused"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage().contains("infobaseName")); //$NON-NLS-1$
        }
        String original = ref.getConnectionString().asConnectionString();
        try
        {
            InfobaseRegistration.cleanupRegistration(manager, ref.getUuid(), "probe", original, //$NON-NLS-1$
                (target, guard) -> guard.run(current -> current.setConnectionString(
                    new ServerInfobaseTarget("other-host", "base") //$NON-NLS-1$ //$NON-NLS-2$
                        .reference("probe").getConnectionString())), () -> { }); //$NON-NLS-1$
            fail("an address changed while cleanup waited must require a new preview"); //$NON-NLS-1$
        }
        catch (IllegalStateException expected)
        {
            assertTrue(expected.getMessage().contains("changed after preview")); //$NON-NLS-1$
        }
        verify(manager, never()).delete(any());
    }

    @Test
    public void queuedCredentialClearRevalidatesNameAddressAndContextInsideTheWorker() throws Exception
    {
        for (int change = 0; change < 3; change++)
        {
            var manager = mock(IInfobaseManager.class);
            var access = mock(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager.class);
            when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
            var ref = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            when(manager.findInfobaseByUuid(ref.getUuid())).thenReturn(java.util.Optional.of(ref));
            String connection = ref.getConnectionString().asConnectionString();
            var contextValid = new java.util.concurrent.atomic.AtomicBoolean(true);
            int mutation = change;
            try
            {
                InfobaseRegistration.cleanupRegistration(manager, ref.getUuid(), "probe", connection, //$NON-NLS-1$
                    (target, guard) -> InfobaseAccessSupport.clearCredentials(target, guard, () -> {
                        if (mutation == 0)
                        {
                            ref.setName("replacement"); //$NON-NLS-1$
                        }
                        else if (mutation == 1)
                        {
                            ref.setConnectionString(new ServerInfobaseTarget("other", "base") //$NON-NLS-1$ //$NON-NLS-2$
                                .reference("probe").getConnectionString()); //$NON-NLS-1$
                        }
                        else
                        {
                            contextValid.set(false);
                        }
                        return access;
                    }, 5_000L), () -> {
                        if (!contextValid.get())
                        {
                            throw new IllegalStateException("context changed"); //$NON-NLS-1$
                        }
                    });
                fail("the worker must reject state changed after initial validation"); //$NON-NLS-1$
            }
            catch (IllegalStateException expected)
            {
                assertNotNull(expected.getCause());
            }
            verify(access, never()).updateSettings(any(), any());
            verify(manager, never()).delete(any());
        }
    }

    @Test
    public void finalReloadRevalidatesIdentityAndContextBeforeDelete() throws Exception
    {
        for (int change = 0; change < 3; change++)
        {
            var manager = mock(IInfobaseManager.class);
            var access = mock(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager.class);
            when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
            var ref = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            when(manager.findInfobaseByUuid(ref.getUuid())).thenReturn(java.util.Optional.of(ref));
            String connection = ref.getConnectionString().asConnectionString();
            var reloads = new java.util.concurrent.atomic.AtomicInteger();
            var contextValid = new java.util.concurrent.atomic.AtomicBoolean(true);
            int mutation = change;
            doAnswer(call -> {
                if (reloads.incrementAndGet() == 3)
                {
                    if (mutation == 0)
                    {
                        ref.setName("replacement"); //$NON-NLS-1$
                    }
                    else if (mutation == 1)
                    {
                        ref.setConnectionString(new ServerInfobaseTarget("other", "base") //$NON-NLS-1$ //$NON-NLS-2$
                            .reference("probe").getConnectionString()); //$NON-NLS-1$
                    }
                    else
                    {
                        contextValid.set(false);
                    }
                }
                return null;
            }).when(manager).reload(null);
            try
            {
                InfobaseRegistration.cleanupRegistration(manager, ref.getUuid(), "probe", connection, //$NON-NLS-1$
                    (target, guard) -> InfobaseAccessSupport.clearCredentials(target, guard, () -> access, 5_000L),
                    () -> {
                        if (!contextValid.get())
                        {
                            throw new IllegalStateException("context changed"); //$NON-NLS-1$
                        }
                    });
                fail("the final reload must not delete changed state"); //$NON-NLS-1$
            }
            catch (IllegalStateException expected)
            {
                assertTrue(expected.getMessage().contains("changed")); //$NON-NLS-1$
            }
            verify(access).updateSettings(ref,
                com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings.NOT_DEFINED);
            verify(manager, never()).delete(any());
        }
    }

    @Test
    public void guardedNativeClearUsesFreshReferenceWithoutHoldingTheManagerMonitor() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        var access = mock(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager.class);
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        var original = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var record = new java.util.concurrent.atomic.AtomicReference<>(original);
        when(manager.findInfobaseByUuid(original.getUuid())).thenAnswer(call -> java.util.Optional.ofNullable(record.get()));
        doAnswer(call -> {
            if (record.get() != null)
            {
                record.set(org.eclipse.emf.ecore.util.EcoreUtil.copy(record.get()));
            }
            return null;
        }).when(manager).reload(null);
        doAnswer(call -> {
            assertFalse("native settings writes must not invert manager/EDT domain locks", Thread.holdsLock(manager)); //$NON-NLS-1$
            assertSame(record.get(), call.getArgument(0));
            assertNotSame(original, call.getArgument(0));
            return null;
        }).when(access).updateSettings(any(), any());
        doAnswer(call -> {
            assertSame(record.get(), call.getArgument(0));
            record.set(null);
            return null;
        }).when(manager).delete(any());
        InfobaseRegistration.cleanupRegistration(manager, original.getUuid(), "probe", //$NON-NLS-1$
            original.getConnectionString().asConnectionString(),
            (ref, guard) -> InfobaseAccessSupport.clearCredentials(ref, guard, () -> access, 5_000L), () -> { });
        assertNull(record.get());
        var order = inOrder(access, manager);
        order.verify(access).updateSettings(any(),
            eq(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings.NOT_DEFINED));
        order.verify(manager).delete(any());
    }

    @Test
    public void fileDuplicateDetectionUsesTheActualFilesystemCaseRules() throws Exception
    {
        var root = java.nio.file.Files.createTempDirectory("registration-case"); //$NON-NLS-1$
        var upper = java.nio.file.Files.createDirectories(root.resolve("Base")); //$NON-NLS-1$
        var lower = java.nio.file.Files.createDirectories(root.resolve("base")); //$NON-NLS-1$
        try
        {
            var manager = mock(IInfobaseManager.class);
            when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
            var existing = new InfobaseTarget(upper, null).reference("existing"); //$NON-NLS-1$
            var fresh = new InfobaseTarget(lower, null).reference("fresh"); //$NON-NLS-1$
            when(manager.getAll()).thenReturn(List.of(existing));
            when(manager.findInfobaseByUuid(fresh.getUuid())).thenReturn(java.util.Optional.of(fresh));
            String result = InfobaseRegistration.add(manager, fresh);
            if (java.nio.file.Files.isSameFile(upper, lower))
            {
                assertTrue(result, result.contains("already registered")); //$NON-NLS-1$
                verify(manager, never()).add(any(), any());
            }
            else
            {
                assertNull(result);
                verify(manager).add(fresh, null);
            }
        }
        finally
        {
            java.nio.file.Files.deleteIfExists(lower);
            java.nio.file.Files.deleteIfExists(upper);
            java.nio.file.Files.delete(root);
        }
    }

    @Test
    public void duplicateDetectionReloadsBeforeReadingTheCachedList() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        var existing = new ServerInfobaseTarget("host", "base").reference("existing"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        when(manager.getAll()).thenReturn(List.of());
        doAnswer(invocation -> {
            when(manager.getAll()).thenReturn(List.of(existing));
            return null;
        }).when(manager).reload(null);
        assertTrue(InfobaseRegistration.add(manager,
            new ServerInfobaseTarget("host", "base").reference("fresh")).contains("already registered")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        var order = inOrder(manager);
        order.verify(manager).reload(null);
        order.verify(manager).getAll();
        verify(manager, never()).add(any(), any());
    }
}
