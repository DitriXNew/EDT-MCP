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
            java.util.Optional.of(reference), java.util.Optional.empty());
        doThrow(new IllegalStateException("secret")).when(associations).dissociate(project, reference, context); //$NON-NLS-1$
        boolean[] cleared = {false};
        var result = InfobaseRegistration.rollback(associations, infobases, project, reference, context,
            ref -> { assertSame(reference, ref); cleared[0] = true; });
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
            ref -> { throw new IllegalStateException("secret"); }); //$NON-NLS-1$
        assertTrue(result.associationRemoved());
        assertFalse(result.registrationRemoved());
        assertFalse(result.credentialsRemoved());
        verify(associations).dissociate(project, reference, context);
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
    public void launcherOnlyCleanupSelectsOnlyAUniqueServerEntry() throws Exception
    {
        var manager = mock(IInfobaseManager.class);
        when(manager.getLoadStatus()).thenReturn(org.eclipse.core.runtime.Status.OK_STATUS);
        var server = new ServerInfobaseTarget("host", "base").reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(manager.getAll()).thenReturn(List.of(server));
        assertSame(server, InfobaseRegistration.findUniqueServer(manager, "probe").orElseThrow()); //$NON-NLS-1$
        assertTrue(InfobaseRegistration.findUniqueServer(manager, "missing").isEmpty()); //$NON-NLS-1$
        var file = new InfobaseTarget(java.nio.file.Path.of("test"), null).reference("probe"); //$NON-NLS-1$ //$NON-NLS-2$
        when(manager.getAll()).thenReturn(List.of(file));
        assertTrue(InfobaseRegistration.findUniqueServer(manager, "probe").isEmpty()); //$NON-NLS-1$
        when(manager.getAll()).thenReturn(List.of(file, server));
        try
        {
            InfobaseRegistration.findUniqueServer(manager, "probe"); //$NON-NLS-1$
            fail("an ambiguous launcher name must never select an arbitrary entry"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage().contains("Multiple launcher entries")); //$NON-NLS-1$
        }
        verify(manager, never()).delete(any());
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
