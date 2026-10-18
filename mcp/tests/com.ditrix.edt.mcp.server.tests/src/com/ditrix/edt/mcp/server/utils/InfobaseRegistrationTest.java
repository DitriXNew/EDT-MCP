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
            java.util.Optional.of(reference), java.util.Optional.of(reference),
            java.util.Optional.of(reference), java.util.Optional.empty());
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
            assertFalse("native deletion must not hold the SDK monitor while waiting for EDT's domain", Thread.holdsLock(manager)); //$NON-NLS-1$
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
    public void queuedCredentialStoreCannotRepopulateTheClearDeleteGapOrDeletedUuid() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        var access = mock(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager.class);
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        var ref = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var record = new java.util.concurrent.atomic.AtomicReference<>(ref);
        when(manager.findInfobaseByUuid(ref.getUuid())).thenAnswer(call -> java.util.Optional.ofNullable(record.get()));
        var enteredClear = new java.util.concurrent.CountDownLatch(1);
        var releaseClear = new java.util.concurrent.CountDownLatch(1);
        var storeResult = new java.util.concurrent.atomic.AtomicReference<InfobaseAccessSupport.StoreResult>();
        var cleanupFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        doAnswer(call -> {
            assertSame(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings.NOT_DEFINED,
                call.getArgument(1));
            enteredClear.countDown();
            assertTrue(releaseClear.await(20, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }).when(access).updateSettings(any(), any());
        doAnswer(call -> { record.set(null); return null; }).when(manager).delete(any());
        Thread setter = new Thread(() -> storeResult.set(InfobaseAccessSupport.storeCredentials(ref,
            "late-user", "late-password", com._1c.g5.v8.dt.platform.services.model.InfobaseAccess.INFOBASE, access))); //$NON-NLS-1$ //$NON-NLS-2$
        Thread cleanup = new Thread(() -> {
            try
            {
                InfobaseRegistration.cleanupRegistration(manager, ref.getUuid(), ref.getName(),
                    ref.getConnectionString().asConnectionString(), (target, guard) -> {
                        InfobaseAccessSupport.clearCredentials(target, guard, () -> access, 10_000L);
                        // Force the pending writer to finish in the previously unprotected gap.
                        setter.join(20_000L);
                        assertFalse(setter.isAlive());
                        assertNotNull(storeResult.get().error());
                        assertSame(ref, record.get());
                    }, () -> { });
            }
            catch (Throwable failure)
            {
                cleanupFailure.set(failure);
            }
        });
        cleanup.start();
        try
        {
            assertTrue(enteredClear.await(20, java.util.concurrent.TimeUnit.SECONDS));
            setter.start();
            releaseClear.countDown();
            cleanup.join(20_000L);
            assertFalse(cleanup.isAlive());
            if (cleanupFailure.get() != null)
            {
                throw new AssertionError(cleanupFailure.get());
            }
            assertNull(record.get());
            assertNotNull(InfobaseAccessSupport.storeCredentials(ref, "stale", "secret", //$NON-NLS-1$ //$NON-NLS-2$
                com._1c.g5.v8.dt.platform.services.model.InfobaseAccess.INFOBASE, access).error());
            try
            {
                InfobaseRegistration.cleanupRegistration(manager, ref.getUuid(), ref.getName(),
                    ref.getConnectionString().asConnectionString(), (target, guard) -> fail(), () -> { });
                fail("retry of an absent UUID must fail"); //$NON-NLS-1$
            }
            catch (IllegalArgumentException expected)
            {
                assertNotNull(InfobaseAccessSupport.storeCredentials(ref, "stale", "secret", //$NON-NLS-1$ //$NON-NLS-2$
                    com._1c.g5.v8.dt.platform.services.model.InfobaseAccess.INFOBASE, access).error());
            }
            verify(access, times(1)).updateSettings(any(), any());
        }
        finally
        {
            releaseClear.countDown();
            cleanup.join(20_000L);
            setter.join(20_000L);
        }
    }

    @Test
    public void failedClearReleasesTheClaimForAnIntentionalCredentialRepair() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        var access = mock(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager.class);
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        var ref = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(manager.findInfobaseByUuid(ref.getUuid())).thenReturn(java.util.Optional.of(ref));
        try
        {
            InfobaseRegistration.cleanupRegistration(manager, ref.getUuid(), ref.getName(),
                ref.getConnectionString().asConnectionString(),
                (target, guard) -> { throw new IllegalStateException("locked storage"); }, () -> { }); //$NON-NLS-1$
            fail();
        }
        catch (IllegalStateException expected)
        {
            assertNull(InfobaseAccessSupport.storeCredentials(ref, "repair", "secret", //$NON-NLS-1$ //$NON-NLS-2$
                com._1c.g5.v8.dt.platform.services.model.InfobaseAccess.INFOBASE, access).error());
            verify(access).updateSettings(eq(ref), any());
            verify(manager, never()).delete(any());
        }
    }

    @Test
    public void disappearanceAtInitialWorkerOrFinalReloadRetainsTombstoneEvenWhenClearFails() throws Exception
    {
        for (int disappearOn : new int[] {1, 2, 3})
        {
            for (boolean failClear : new boolean[] {false, true})
            {
                if (failClear && disappearOn == 3)
                {
                    continue; // A failed clear never reaches the final reload.
                }
                var manager = mock(IInfobaseManager.class);
                var access = mock(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager.class);
                when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
                var captured = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                var record = new java.util.concurrent.atomic.AtomicReference<>(captured);
                var reloads = new java.util.concurrent.atomic.AtomicInteger();
                when(manager.findInfobaseByUuid(captured.getUuid())).thenAnswer(call -> java.util.Optional.ofNullable(record.get()));
                int stage = disappearOn;
                doAnswer(call -> {
                    if (reloads.incrementAndGet() == stage)
                    {
                        record.set(null);
                    }
                    return null;
                }).when(manager).reload(null);
                var cleared = new java.util.concurrent.atomic.AtomicBoolean();
                doAnswer(call -> {
                    var actual = (com._1c.g5.v8.dt.platform.services.model.InfobaseReference)call.getArgument(0);
                    assertEquals(captured.getUuid(), actual.getUuid());
                    assertEquals(captured.getConnectionString().asConnectionString(), actual.getConnectionString().asConnectionString());
                    assertSame(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings.NOT_DEFINED, call.getArgument(1));
                    if (failClear)
                    {
                        throw new IllegalStateException("secure storage locked"); //$NON-NLS-1$
                    }
                    cleared.set(true);
                    return null;
                }).when(access).updateSettings(any(), any());
                try
                {
                    InfobaseRegistration.cleanupRegistration(manager, captured,
                        (target, guard) -> InfobaseAccessSupport.clearCredentials(target, guard, () -> access, 5_000L), () -> { });
                    assertFalse("only successful clear may report cleanup complete", failClear); //$NON-NLS-1$
                }
                catch (IllegalStateException expected)
                {
                    assertTrue(failClear);
                }
                assertEquals(!failClear, cleared.get());
                assertNull(record.get());
                assertNotNull(InfobaseAccessSupport.storeCredentials(captured, "late", "secret", //$NON-NLS-1$ //$NON-NLS-2$
                    com._1c.g5.v8.dt.platform.services.model.InfobaseAccess.INFOBASE, access).error());
                verify(access, times(1)).updateSettings(any(), any());
                verify(manager, never()).delete(any());
                verify(manager, never()).add(any(), any());
            }
        }
    }

    @Test
    public void absentUuidCleanupCanRetryOnlyItsRetainedVerifiedSnapshot() throws Exception
    {
        for (boolean file : new boolean[] {false, true})
        {
            var manager = mock(IInfobaseManager.class);
            when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
            var captured = file
                ? new com.ditrix.edt.mcp.server.utils.InfobaseTarget(java.nio.file.Path.of("recovery-base"), null).reference("recover") //$NON-NLS-1$ //$NON-NLS-2$
                : new ServerInfobaseTarget("host", "base").reference("recover"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            var replacement = org.eclipse.emf.ecore.util.EcoreUtil.copy(captured);
            replacement.setUuid(java.util.UUID.randomUUID());
            when(manager.findInfobaseByUuid(captured.getUuid())).thenReturn(java.util.Optional.empty());
            when(manager.findInfobaseByUuid(replacement.getUuid())).thenReturn(java.util.Optional.of(replacement));
            try
            {
                InfobaseRegistration.cleanupRegistration(manager, captured,
                    (target, guard) -> guard.run(current -> {
                        current.setName("mutated SDK argument"); //$NON-NLS-1$
                        throw new IllegalStateException("storage locked"); //$NON-NLS-1$
                    }), () -> { });
                fail("failed clear must remain recoverable"); //$NON-NLS-1$
            }
            catch (IllegalStateException expected)
            {
                assertTrue(InfobaseMutationCoordinator.isDeleting(captured.getUuid()));
            }
            var recovered = InfobaseRegistration.findCleanupRegistration(manager, captured.getUuid(), "recover"); //$NON-NLS-1$
            assertNotSame(captured, recovered);
            assertEquals(captured.getConnectionString().asConnectionString(), recovered.getConnectionString().asConnectionString());
            for (var request : java.util.List.of(captured.getUuid(), java.util.UUID.randomUUID()))
            {
                try
                {
                    InfobaseRegistration.findCleanupRegistration(manager, request, "wrong name"); //$NON-NLS-1$
                    fail("no fallback from an unverified UUID/name"); //$NON-NLS-1$
                }
                catch (IllegalArgumentException expected)
                {
                    // A UUID and its exact retained name are both required.
                }
            }
            var cleared = new java.util.concurrent.atomic.AtomicBoolean();
            InfobaseRegistration.cleanupRegistration(manager, recovered, (target, guard) ->
                guard.run(current -> {
                    assertEquals(captured.getUuid(), current.getUuid());
                    assertEquals("recover", current.getName()); //$NON-NLS-1$
                    cleared.set(true);
                }), () -> { });
            assertTrue(cleared.get());
            assertTrue(InfobaseMutationCoordinator.isDeleting(captured.getUuid()));
            try
            {
                InfobaseRegistration.findCleanupRegistration(manager, captured.getUuid(), "recover"); //$NON-NLS-1$
                fail("completed cleanup must not authorize replay of an old UUID"); //$NON-NLS-1$
            }
            catch (IllegalArgumentException expected)
            {
                assertSame(replacement, manager.findInfobaseByUuid(replacement.getUuid()).orElseThrow());
            }
            verify(manager, never()).delete(any());
            verify(manager, never()).add(any(), any());
        }
    }

    @Test
    public void absenceDiscoveredAfterTheBoundedCallerTimesOutStillPreventsStaleStores() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        var access = mock(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager.class);
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        var captured = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var record = new java.util.concurrent.atomic.AtomicReference<>(captured);
        when(manager.findInfobaseByUuid(captured.getUuid())).thenAnswer(call -> java.util.Optional.ofNullable(record.get()));
        var reloads = new java.util.concurrent.atomic.AtomicInteger();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var workerFinished = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            if (reloads.incrementAndGet() == 2)
            {
                entered.countDown();
                while (release.getCount() != 0)
                {
                    try
                    {
                        assertTrue(release.await(20, java.util.concurrent.TimeUnit.SECONDS));
                    }
                    catch (InterruptedException ignored)
                    {
                        // Native reload can finish only after its bounded caller returned.
                    }
                }
                record.set(null);
            }
            return null;
        }).when(manager).reload(null);
        try
        {
            try
            {
                InfobaseRegistration.cleanupRegistration(manager, captured,
                    (target, guard) -> InfobaseAccessSupport.clearCredentials(target, write -> {
                        try
                        {
                            guard.run(write);
                        }
                        finally
                        {
                            workerFinished.countDown();
                        }
                    }, () -> access, 1_000L), () -> { });
                fail("the blocked reload must time out"); //$NON-NLS-1$
            }
            catch (IllegalStateException expected)
            {
                assertEquals(0, entered.getCount());
                assertEquals(1, workerFinished.getCount());
            }
            release.countDown();
            assertTrue(workerFinished.await(20, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(record.get());
            assertNotNull(InfobaseAccessSupport.storeCredentials(captured, "stale", "secret", //$NON-NLS-1$ //$NON-NLS-2$
                com._1c.g5.v8.dt.platform.services.model.InfobaseAccess.INFOBASE, access).error());
            assertTrue(InfobaseMutationCoordinator.isDeleting(captured.getUuid()));
            verify(access, never()).updateSettings(any(), any());
            verify(manager, never()).delete(any());
        }
        finally
        {
            release.countDown();
            assertTrue(workerFinished.await(20, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    public void aLateAbsenceObservationSurvivesAReplacementCleanupClaim() throws Exception
    {
        for (boolean observeBeforeNewClose : new boolean[] {true, false})
        {
            var ref = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            var access = mock(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager.class);
            var old = InfobaseMutationCoordinator.beginDeletion(ref.getUuid());
            old.remember(ref);
            old.close();
            var retry = InfobaseMutationCoordinator.beginDeletion(ref.getUuid());
            var fresh = new ServerInfobaseTarget("new-host", "new-base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fresh.setUuid(ref.getUuid());
            retry.remember(fresh);
            old.remember(ref);
            if (observeBeforeNewClose)
            {
                old.observedAbsence();
                retry.close();
            }
            else
            {
                retry.close();
                old.observedAbsence();
            }
            assertEquals(fresh.getConnectionString().asConnectionString(),
                InfobaseMutationCoordinator.recoverySnapshot(ref.getUuid()).getConnectionString().asConnectionString());
            assertNotNull(InfobaseAccessSupport.storeCredentials(ref, "stale", "secret", //$NON-NLS-1$ //$NON-NLS-2$
                com._1c.g5.v8.dt.platform.services.model.InfobaseAccess.INFOBASE, access).error());
            verify(access, never()).updateSettings(any(), any());
        }
    }

    @Test
    public void failedLauncherReadIsNotTreatedAsVerifiedAbsence() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        var access = mock(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager.class);
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.CANCEL_STATUS);
        var captured = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        try
        {
            InfobaseRegistration.cleanupRegistration(manager, captured,
                (target, guard) -> InfobaseAccessSupport.clearCredentials(target, guard, () -> access, 5_000L), () -> { });
            fail();
        }
        catch (IllegalStateException expected)
        {
            assertFalse(InfobaseMutationCoordinator.isDeleting(captured.getUuid()));
        }
        verify(access, never()).updateSettings(any(), any());
        verify(manager, never()).findInfobaseByUuid(any());
        verify(manager, never()).delete(any());
    }

    @Test
    public void aNativeWriteOutlivingItsDeadlineCannotHangOtherLauncherRequests() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        var access = mock(com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager.class);
        var blocked = new ServerInfobaseTarget("host", "blocked").reference("blocked"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        var other = new ServerInfobaseTarget("host", "other").reference("other"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Different UUIDs and different write stripes: the shared coordinator alone blocks them.
        while (InfobaseAccessSupport.writeLockFor(blocked) == InfobaseAccessSupport.writeLockFor(other))
        {
            other.setUuid(java.util.UUID.randomUUID());
        }
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            assertEquals(blocked.getUuid(), ((com._1c.g5.v8.dt.platform.services.model.InfobaseReference)call.getArgument(0)).getUuid());
            entered.countDown();
            while (release.getCount() != 0)
            {
                try
                {
                    assertTrue(release.await(20, java.util.concurrent.TimeUnit.SECONDS));
                }
                catch (InterruptedException ignored)
                {
                    // Model a native call that cannot honor cooperative cancellation.
                }
            }
            return null;
        }).when(access).updateSettings(any(), any());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try
        {
            var timedOut = InfobaseAccessSupport.runBoundedCredentialStore("blocked native SDK", 1_000L, //$NON-NLS-1$
                (publish, committed, allowed) -> {
                    try
                    {
                        publish.accept(InfobaseAccessSupport.storeCredentials(blocked, "blocked", "secret", //$NON-NLS-1$ //$NON-NLS-2$
                            com._1c.g5.v8.dt.platform.services.model.InfobaseAccess.INFOBASE, access, committed, allowed));
                    }
                    finally
                    {
                        finished.countDown();
                    }
                });
            assertTrue(entered.await(20, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(BoundedJob.Outcome.TIMED_OUT, timedOut.boundedResult().getOutcome());
            var add = pool.submit(() -> InfobaseRegistration.add(manager, other));
            var lookup = pool.submit(() -> {
                try
                {
                    InfobaseRegistration.findRegistration(manager, other.getUuid(), other.getName());
                    return "unexpected success"; //$NON-NLS-1$
                }
                catch (InfobaseMutationCoordinator.Unavailable expected)
                {
                    return expected.getMessage();
                }
            });
            var remove = pool.submit(() -> {
                try
                {
                    InfobaseRegistration.remove(manager, other);
                    return "unexpected success"; //$NON-NLS-1$
                }
                catch (InfobaseMutationCoordinator.Unavailable expected)
                {
                    return expected.getMessage();
                }
            });
            var store = pool.submit(() -> InfobaseAccessSupport.storeCredentials(other, "other", "secret", //$NON-NLS-1$ //$NON-NLS-2$
                com._1c.g5.v8.dt.platform.services.model.InfobaseAccess.INFOBASE, access).error());
            for (var result : List.of(add, lookup, remove, store))
            {
                assertTrue(result.get(15, java.util.concurrent.TimeUnit.SECONDS).contains("busy")); //$NON-NLS-1$
            }
            assertEquals("native operation must still be blocked while other requests return", 1, finished.getCount()); //$NON-NLS-1$
            assertFalse(InfobaseMutationCoordinator.isDeleting(other.getUuid()));
            verify(manager, never()).reload(any());
            verify(manager, never()).add(any(), any());
            verify(manager, never()).delete(any());
            verify(access, times(1)).updateSettings(any(), any());
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(20, java.util.concurrent.TimeUnit.SECONDS));
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    public void coordinatorLeasesAreBoundedInterruptibleAndReentrant() throws Exception
    {
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var refused = new java.util.concurrent.atomic.AtomicBoolean();
        var finished = new java.util.concurrent.CountDownLatch(1);
        Thread waiting = new Thread(() -> {
            Thread.currentThread().interrupt();
            try (var ignored = InfobaseMutationCoordinator.acquire(100L))
            {
                fail("interrupted acquisition must not enter the protected operation"); //$NON-NLS-1$
            }
            catch (InfobaseMutationCoordinator.Unavailable expected)
            {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
            finally
            {
                finished.countDown();
            }
        });
        try (var outer = InfobaseMutationCoordinator.acquire())
        {
            try (var inner = InfobaseMutationCoordinator.acquire())
            {
                assertNotNull(inner);
            }
            Thread contender = new Thread(() -> {
                try (var ignored = InfobaseMutationCoordinator.acquire(50L))
                {
                    // A broken inner close could release the outer lease.
                }
                catch (InfobaseMutationCoordinator.Unavailable expected)
                {
                    refused.set(true);
                }
            });
            contender.start();
            contender.join(20_000L);
            assertFalse(contender.isAlive());
            assertTrue("inner close must leave outer ownership intact", refused.get()); //$NON-NLS-1$
            waiting.start();
            assertTrue(finished.await(20, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(interrupted.get());
        }
        try (var after = InfobaseMutationCoordinator.acquire(50L))
        {
            assertNotNull(after);
        }
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
