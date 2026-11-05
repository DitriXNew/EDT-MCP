/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;

import com._1c.g5.designer.ssh.client.IDesignerSession;
import com._1c.g5.designer.ssh.client.operation.IConfigureQuery;
import com._1c.g5.designer.ssh.client.operation.IConfigurationStep;
import com._1c.g5.designer.ssh.client.operation.IDesignerOperation;
import com._1c.g5.designer.ssh.client.operation.IExtensionsQuery;
import com._1c.g5.designer.ssh.client.operation.IExtensionProperties;
import com._1c.g5.designer.ssh.client.operation.IExtensionPropertiesStep;
import com._1c.g5.designer.ssh.client.operation.IExtensionStep;
import com._1c.g5.designer.ssh.client.operation.ISingleExtensionStep;
import com._1c.g5.designer.ssh.client.operation.ISetExtensionPropertiesStep;
import com._1c.g5.designer.ssh.client.operation.IUpdateConfigurationStep;
import com._1c.g5.designer.ssh.client.operation.IDbUpdateConfirm;
import com._1c.g5.designer.ssh.client.operation.IUpdateConfigurationStep.SessionTermination;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionArguments;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;

/** Explicit SDK steps avoid Mockito 2's erased-generic deep-stub casts. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class DesignerExtensionBackendTest
{
    @Test public void newerArgumentsReceiveTheExactAdditionalParametersIncludingEmptyValues() throws Exception
    {
        for (String value : new String[] {"/WA- /N\"Other user\" /Z tenant", "", null})
        {
            CurrentArguments arguments = new CurrentArguments();
            InfobaseReference reference = mock(InfobaseReference.class);
            DesignerExtensionBackend.applyAdditionalInfobaseParameters(arguments, reference, value);
            assertEquals(1, arguments.calls);
            assertEquals(value, arguments.parameters);
            verifyZeroInteractions(reference);
        }
    }

    @Test public void olderArgumentsSkipEmptySettingsWithoutReadingOrChangingTheReference() throws Exception
    {
        InfobaseReference reference = mock(InfobaseReference.class);
        DesignerExtensionBackend.applyAdditionalInfobaseParameters(new LegacyArguments(), reference, null);
        DesignerExtensionBackend.applyAdditionalInfobaseParameters(new LegacyArguments(), reference, "");
        verifyZeroInteractions(reference);
    }

    @Test public void olderDesignerCarriesNonEmptySettingsThroughTheInfobaseReference() throws Exception
    {
        String value = "/WA- /N\"Other user\" /Z tenant";
        InfobaseReference reference = mock(InfobaseReference.class);
        when(reference.getAdditionalParameters()).thenReturn(value);
        DesignerExtensionBackend.applyAdditionalInfobaseParameters(new LegacyArguments(), reference, value);
        verify(reference).getAdditionalParameters();
        verifyNoMoreInteractions(reference);
    }

    @Test public void olderDesignerRefusesSettingsThatTheReferenceWouldDrop() throws Exception
    {
        for (String stored : new String[] {null, "", "/Z different"})
        {
            InfobaseReference reference = mock(InfobaseReference.class);
            when(reference.getAdditionalParameters()).thenReturn(stored);
            try
            {
                DesignerExtensionBackend.applyAdditionalInfobaseParameters(new LegacyArguments(), reference,
                    "/Z tenant");
                fail("Additional parameters would be lost");
            }
            catch (IllegalArgumentException expected)
            {
                assertTrue(expected.getMessage().contains("additionalProperties"));
                assertTrue(expected.getMessage().contains("clear Additional parameters"));
            }
            verify(reference).getAdditionalParameters();
            verifyNoMoreInteractions(reference);
        }
    }

    @Test public void reflectiveSetterFailurePreservesItsCauseAndNeverFallsBack() throws Exception
    {
        CurrentArguments arguments = new CurrentArguments();
        arguments.failure = new IllegalStateException("setter failed");
        InfobaseReference reference = mock(InfobaseReference.class);
        try
        {
            DesignerExtensionBackend.applyAdditionalInfobaseParameters(arguments, reference, "/Z tenant");
            fail("Expected the setter failure");
        }
        catch (IllegalStateException expected)
        {
            assertSame(arguments.failure, expected);
        }
        assertEquals(1, arguments.calls);
        verifyZeroInteractions(reference);
    }

    @Test public void reflectiveSetterErrorPropagatesUnchangedAndNeverFallsBack() throws Exception
    {
        for (Error setterError : new Error[] {new NoSuchMethodError("setter dependency"),
            new OutOfMemoryError("fatal setter failure")})
        {
            CurrentArguments arguments = new CurrentArguments()
            {
                @Override public void setAdditionalInfobaseParameters(String parameters)
                {
                    super.setAdditionalInfobaseParameters(parameters);
                    throw setterError;
                }
            };
            InfobaseReference reference = mock(InfobaseReference.class);
            try
            {
                DesignerExtensionBackend.applyAdditionalInfobaseParameters(arguments, reference, "/Z tenant");
                fail("Expected the setter error");
            }
            catch (Error expected)
            {
                assertSame(setterError, expected);
            }
            assertEquals(1, arguments.calls);
            verifyZeroInteractions(reference);
        }
    }

    public static class LegacyArguments
    {
    }

    public static class CurrentArguments extends LegacyArguments
    {
        int calls;
        String parameters;
        RuntimeException failure;

        public void setAdditionalInfobaseParameters(String parameters)
        {
            calls++;
            if (failure != null)
            {
                throw failure;
            }
            this.parameters = parameters;
        }
    }

    @Test public void cachedConnectionSignaturesExistInTheResolvedEdtPlatform() throws Exception
    {
        String pkg = "com._1c.g5.v8.dt.internal.platform.services.core.runtimes.execution.";
        var bundle = PlatformServicesSupport.bundle();
        Class<?> pool = bundle.loadClass(pkg + "DesignerSessionPool");
        Class<?> connection = bundle.loadClass(pkg + "DesignerAgentConnection");
        Class<?> action = bundle.loadClass(pkg + "DesignerAgentConnection$DesignerAgentConnectionAction");
        assertEquals(connection, pool.getMethod("acquire", RuntimeInstallation.class,
            InfobaseReference.class, RuntimeExecutionArguments.class).getReturnType());
        assertEquals(IDesignerSession.class, connection.getMethod("getSession").getReturnType());
        assertNotNull(connection.getMethod("use", action));
        assertTrue(action.isInterface());
        assertNotNull(action.getMethod("run"));
    }

    private static class Fixture
    {
        final IDesignerSession session = mock(IDesignerSession.class);
        final IConfigureQuery configure = mock(IConfigureQuery.class);
        final IExtensionsQuery extensions = mock(IExtensionsQuery.class);
        final IExtensionPropertiesStep properties = mock(IExtensionPropertiesStep.class);
        final IConfigurationStep load = mock(IConfigurationStep.class);
        final IConfigurationStep namedLoad = mock(IConfigurationStep.class);
        final IUpdateConfigurationStep update = mock(IUpdateConfigurationStep.class);
        final IUpdateConfigurationStep namedUpdate = mock(IUpdateConfigurationStep.class);
        final IExtensionStep delete = mock(IExtensionStep.class);
        final IDesignerOperation<Void> namedDelete = mock(IDesignerOperation.class);
        final IExtensionStep get = mock(IExtensionStep.class);
        final IDesignerOperation<List<IExtensionProperties>> inventory = mock(IDesignerOperation.class);
        final ISingleExtensionStep set = mock(ISingleExtensionStep.class);
        final ISetExtensionPropertiesStep flags = mock(ISetExtensionPropertiesStep.class);
        final DesignerExtensionBackend backend = new DesignerExtensionBackend(session, new NullProgressMonitor());

        Fixture()
        {
            when(session.configure()).thenReturn(configure);
            when(session.extensions()).thenReturn(extensions);
            when(extensions.properties()).thenReturn(properties);
            when(configure.importCfToInfobase(any(Path.class))).thenReturn(load);
            when(load.extension("Runtime")).thenReturn(namedLoad);
            when(configure.updateDatabaseConfiguration()).thenReturn(update);
            when(update.extension("Runtime")).thenReturn(namedUpdate);
            when(namedUpdate.onInfobaseLockError(SessionTermination.DISABLE)).thenReturn(namedUpdate);
            when(namedUpdate.onConfirm(any(IDbUpdateConfirm.class))).thenReturn(namedUpdate);
            when(extensions.delete()).thenReturn(delete);
            when(delete.extension("Runtime")).thenReturn(namedDelete);
            when(properties.get()).thenReturn(get);
            when(get.allExtensions()).thenReturn(inventory);
            when(properties.set()).thenReturn(set);
            when(set.extension("Runtime")).thenReturn(flags);
            when(flags.safeMode(anyBoolean())).thenReturn(flags);
            when(flags.unsafeActionProtection(anyBoolean())).thenReturn(flags);
            when(flags.active(anyBoolean())).thenReturn(flags);
        }
    }

    @Test public void loadsOnlyTheNamedExtensionNeverTheBaseConfiguration() throws Exception
    {
        Fixture fixture = new Fixture();
        Path file = Path.of("extension.cfe");
        fixture.backend.load(file, "Runtime");
        verify(fixture.configure).importCfToInfobase(file);
        verify(fixture.load).extension("Runtime");
        verify(fixture.namedLoad).exec();
        verify(fixture.load, never()).exec();
    }

    @Test public void deleteIsScopedAndNeverDeletesAllExtensions() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.backend.delete("Runtime");
        verify(fixture.delete).extension("Runtime");
        verify(fixture.namedDelete).exec();
        verify(fixture.delete, never()).allExtensions();
    }

    @Test public void flagsPreserveExplicitFalseAndInactive() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.backend.flags("Runtime", false, false, false);
        verify(fixture.set).extension("Runtime");
        verify(fixture.flags).safeMode(false);
        verify(fixture.flags).unsafeActionProtection(false);
        verify(fixture.flags).active(false);
        verify(fixture.flags).exec();
    }

    @Test public void readMapsActualRuntimeProperties() throws Exception
    {
        Fixture fixture = new Fixture();
        IExtensionProperties properties = mock(IExtensionProperties.class);
        when(properties.getName()).thenReturn("Runtime");
        when(properties.getVersion()).thenReturn("2.5");
        when(properties.isActive()).thenReturn(false);
        when(properties.isSafeMode()).thenReturn(true);
        when(properties.isUnsafeActionProtected()).thenReturn(false);
        when(fixture.inventory.exec()).thenReturn(List.of(properties));
        var states = fixture.backend.read();
        assertEquals(1, states.size());
        assertEquals("Runtime", states.get(0).name());
        assertEquals("2.5", states.get(0).version());
        assertFalse(states.get(0).active());
        assertTrue(states.get(0).safeMode());
        assertFalse(states.get(0).protectionFromDangerousActions());
    }

    @Test public void updateDisablesAutomaticSessionTermination() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.backend.update("Runtime");
        verify(fixture.update).extension("Runtime");
        verify(fixture.namedUpdate).onInfobaseLockError(SessionTermination.DISABLE);
        verify(fixture.namedUpdate).exec();
        verify(fixture.update, never()).exec();
    }

    @Test public void cancelledMonitorPreventsEveryNativeOperation() throws Exception
    {
        IDesignerSession session = mock(IDesignerSession.class);
        NullProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        DesignerExtensionBackend backend = new DesignerExtensionBackend(session, monitor);
        try
        {
            backend.load(Path.of("extension.cfe"), "Runtime");
            fail("cancelled load succeeded");
        }
        catch (OperationCanceledException expected)
        {
            assertTrue(expected.getMessage().contains("action=list"));
        }
        verifyZeroInteractions(session);
    }
}
