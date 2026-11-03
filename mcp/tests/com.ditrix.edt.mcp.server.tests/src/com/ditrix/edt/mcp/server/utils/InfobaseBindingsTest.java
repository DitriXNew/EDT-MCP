/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class InfobaseBindingsTest
{
    /** Shared service fixture for helper and tool pipeline regressions. */
    public static final class State
    {
        public final IProject project = mock(IProject.class);
        public final IApplicationManager manager = mock(IApplicationManager.class);
        public final IApplication prior = mock(IApplication.class);
        public final AtomicReference<String> defaultId = new AtomicReference<>("prior");
        public final AtomicReference<String> launchId = new AtomicReference<>("prior");
        public final ILaunchConfiguration configuration = mock(ILaunchConfiguration.class);
        public final ILaunchConfigurationWorkingCopy copy = mock(ILaunchConfigurationWorkingCopy.class);
        public boolean failDefault;
        public boolean failLaunch;

        public State() throws Exception
        {
            when(project.getName()).thenReturn("project");
            when(project.isOpen()).thenReturn(true);
            when(prior.getId()).thenReturn("prior");
            when(manager.getApplication(project, "prior")).thenReturn(Optional.of(prior));
            doAnswer(call -> {
                if (failDefault) { throw new IllegalStateException("default store refused"); }
                IApplication application = call.getArgument(1);
                defaultId.set(application == null ? null : application.getId());
                return null;
            }).when(manager).setDefaultApplication(eq(project), any());
            var type = mock(ILaunchConfigurationType.class);
            when(type.getIdentifier()).thenReturn(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID);
            when(configuration.getType()).thenReturn(type);
            when(configuration.getName()).thenReturn("Thin client");
            when(configuration.exists()).thenReturn(true);
            when(configuration.getAttribute(eq(LaunchConfigUtils.ATTR_PROJECT_NAME), nullable(String.class)))
                .thenReturn("project");
            when(configuration.getAttribute(eq(LaunchConfigUtils.ATTR_APPLICATION_ID), nullable(String.class)))
                .thenAnswer(call -> launchId.get());
            when(configuration.getWorkingCopy()).thenReturn(copy);
            when(copy.getAttribute(eq(LaunchConfigUtils.ATTR_PROJECT_NAME), nullable(String.class)))
                .thenReturn("project");
            when(copy.getAttribute(eq(LaunchConfigUtils.ATTR_APPLICATION_ID), nullable(String.class)))
                .thenAnswer(call -> launchId.get());
            var pending = new AtomicReference<String>();
            doAnswer(call -> { pending.set(call.getArgument(1)); return null; })
                .when(copy).setAttribute(eq(LaunchConfigUtils.ATTR_APPLICATION_ID), nullable(String.class));
            when(copy.doSave()).thenAnswer(call -> {
                if (failLaunch) { throw new IllegalStateException("launch save refused"); }
                launchId.set(pending.get());
                return configuration;
            });
        }

        public InfobaseBindings snapshot()
        {
            return InfobaseBindings.capture(manager, new IProject[] {project},
                new ILaunchConfiguration[] {configuration}, p -> defaultId.get());
        }

        public void reset()
        {
            defaultId.set(null);
            launchId.set(null);
        }
    }

    @Test
    public void reloadOfOneProjectRestoresAllWorkspaceProjectsAndEveryClientConfiguration() throws Exception
    {
        var first = new State();
        var second = new State();
        when(second.project.getName()).thenReturn("other-project");
        when(first.manager.getApplication(second.project, "prior")).thenReturn(Optional.of(second.prior));
        doAnswer(call -> { second.defaultId.set(second.prior.getId()); return null; })
            .when(first.manager).setDefaultApplication(second.project, second.prior);
        var snapshot = InfobaseBindings.capture(first.manager, new IProject[] {first.project, second.project},
            new ILaunchConfiguration[] {first.configuration, second.configuration},
            project -> project == first.project ? first.defaultId.get() : second.defaultId.get());
        var result = parse(snapshot.run(() -> {
            first.reset();
            second.reset();
            return ToolResult.success().toJson();
        }, "project", false, false));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals(4, result.getAsJsonArray("restoredBindings").size());
        assertEquals("prior", first.defaultId.get());
        assertEquals("prior", first.launchId.get());
        assertEquals("prior", second.defaultId.get());
        assertEquals("prior", second.launchId.get());
    }

    @Test
    public void noExplicitDefaultRemainsUnsetInsteadOfPromotingTheSingleApplication() throws Exception
    {
        var state = new State();
        state.defaultId.set(null);
        var result = parse(state.snapshot().run(() -> {
            state.launchId.set(null);
            return ToolResult.success().toJson();
        }, "project", false, false));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNull(state.defaultId.get());
        assertEquals("prior", state.launchId.get());
        verify(state.manager, never()).getDefaultApplication(any());
        verify(state.manager, never()).setDefaultApplication(any(), any());
    }

    @Test
    public void pendingNativeMutationDoesNotClaimPreservation() throws Exception
    {
        var state = new State();
        var result = parse(state.snapshot().run(() -> ToolResult.error("native mutation pending")
            .put("mutationPending", true).toJson(), "project", false, false));
        assertFalse(result.get("success").getAsBoolean());
        assertEquals(0, result.getAsJsonArray("preservedBindings").size());
        assertEquals(3, result.getAsJsonArray("unrestoredBindings").size());
        assertTrue(result.getAsJsonArray("unrestoredBindings").toString().contains("prior"));
    }

    @Test
    public void resetThenRestoreUsesNativeServicesAndVerifiesBothBindings() throws Exception
    {
        var state = new State();
        var result = parse(state.snapshot().run(() -> {
            state.reset();
            return ToolResult.success().put("registrationId", "retained-handle").toJson();
        }, "project", false, false));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("prior", state.defaultId.get());
        assertEquals("prior", state.launchId.get());
        assertEquals(2, result.getAsJsonArray("restoredBindings").size());
        assertEquals(0, result.getAsJsonArray("unrestoredBindings").size());
        assertEquals("retained-handle", result.get("registrationId").getAsString());
        verify(state.manager).setDefaultApplication(state.project, state.prior);
        verify(state.copy).doSave();
        verify(state.manager, never()).getDefaultApplication(any());
    }

    @Test
    public void restorationFailureIsAnErrorAndKeepsOperationIdentityAndOtherRestorations() throws Exception
    {
        for (boolean defaultFails : new boolean[] {true, false})
        {
            var state = new State();
            state.failDefault = defaultFails;
            state.failLaunch = !defaultFails;
            var result = parse(state.snapshot().run(() -> {
                state.reset();
                return ToolResult.success().put("applicationId", "new-app").toJson();
            }, "project", false, false));
            assertFalse(result.toString(), result.get("success").getAsBoolean());
            assertEquals("new-app", result.get("applicationId").getAsString());
            assertEquals(1, result.getAsJsonArray("unrestoredBindings").size());
            assertEquals("", result.getAsJsonArray("unrestoredBindings").get(0)
                .getAsJsonObject().get("observedApplicationId").getAsString());
            assertEquals(1, result.getAsJsonArray("restoredBindings").size());
            assertEquals(defaultFails ? null : "prior", state.defaultId.get());
            assertEquals(defaultFails ? "prior" : null, state.launchId.get());
            assertTrue(result.get("error").getAsString().contains("EDT"));
        }
    }

    @Test
    public void knownReadOnlyBindingRefusesBeforeTheMutation() throws Exception
    {
        var state = new State();
        when(state.configuration.isReadOnly()).thenReturn(true);
        var result = parse(state.snapshot().run(() -> {
            fail("known impossible restoration must be refused before the operation");
            return null;
        }, "project", false, false));
        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("read-only: Thin client"));
        assertEquals("prior", state.defaultId.get());
        assertEquals("prior", state.launchId.get());
    }

    @Test
    public void originalOperationErrorAndRecoveryInstructionsSurviveRestorationFailure() throws Exception
    {
        var state = new State();
        state.failDefault = true;
        var result = parse(state.snapshot().run(() -> {
            state.reset();
            return ToolResult.error("Registration retained; wait for native cleanup before retrying.").toJson();
        }, "project", false, false));
        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("Registration retained"));
        assertTrue(result.get("error").getAsString().contains("unrestoredBindings"));
        assertEquals("Registration retained; wait for native cleanup before retrying.",
            result.get("operationError").getAsString());
    }

    @Test
    public void unmodifiedBindingsArePreservedWithoutWrites() throws Exception
    {
        var state = new State();
        var result = parse(state.snapshot().run(() -> ToolResult.success().toJson(), "project", false, false));
        assertEquals(2, result.getAsJsonArray("preservedBindings").size());
        verify(state.manager, never()).setDefaultApplication(any(), any());
        verify(state.copy, never()).doSave();
    }

    @Test
    public void failedOperationAlsoRestoresAndKeepsItsError() throws Exception
    {
        var state = new State();
        var result = parse(state.snapshot().run(() -> {
            state.reset();
            return ToolResult.error("already registered; use get_applications").toJson();
        }, "project", false, false));
        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("already registered"));
        assertEquals(2, result.getAsJsonArray("restoredBindings").size());
    }

    @Test
    public void failedSnapshotRefusesBeforeAnyOperation() throws Exception
    {
        var state = new State();
        when(state.manager.getApplication(state.project, "prior")).thenReturn(Optional.empty());
        String result = state.snapshot().run(() -> { fail("mutation must not start"); return null; },
            "project", false, false);
        assertFalse(parse(result).get("success").getAsBoolean());
        assertTrue(result.contains("not started"));
    }

    @Test
    public void explicitSetDefaultReplacesExistingChoiceAfterReset() throws Exception
    {
        var state = new State();
        var next = mock(IApplication.class);
        when(next.getId()).thenReturn("next");
        when(state.manager.getApplication(state.project, "next")).thenReturn(Optional.of(next));
        var result = parse(state.snapshot().run(() -> {
            state.reset();
            return ToolResult.success().put("applicationId", "next").toJson();
        }, "project", true, false));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("next", state.defaultId.get());
        assertEquals("prior", state.launchId.get());
    }

    @Test
    public void deletedApplicationBindingsAreReportedAsIntentionalRemovals() throws Exception
    {
        var state = new State();
        var result = parse(state.snapshot().run(() -> {
            state.reset();
            return ToolResult.success().put("applicationId", "prior").put("action", "deleted").toJson();
        }, "project", false, true));
        assertTrue(result.get("success").getAsBoolean());
        assertEquals(2, result.getAsJsonArray("removedBindings").size());
        verify(state.manager, never()).setDefaultApplication(any(), any());
        verify(state.copy, never()).doSave();
    }

    @Test
    public void newerChoiceIsKeptAndMismatchIsReported() throws Exception
    {
        var state = new State();
        var result = parse(state.snapshot().run(() -> {
            state.defaultId.set("human-choice");
            state.launchId.set("human-choice");
            return ToolResult.success().toJson();
        }, "project", false, false));
        assertFalse(result.get("success").getAsBoolean());
        assertEquals(2, result.getAsJsonArray("unrestoredBindings").size());
        assertEquals("human-choice", state.defaultId.get());
        assertEquals("human-choice", state.launchId.get());
    }

    @Test
    public void aNativeWriteThatReturnsWithoutSavingIsNotReportedRestored() throws Exception
    {
        var state = new State();
        doNothing().when(state.manager).setDefaultApplication(any(), any());
        var result = parse(state.snapshot().run(() -> {
            state.reset();
            return ToolResult.success().toJson();
        }, "project", false, false));
        assertFalse(result.get("success").getAsBoolean());
        assertEquals(1, result.getAsJsonArray("unrestoredBindings").size());
    }

    private static JsonObject parse(String result)
    {
        return JsonParser.parseString(result).getAsJsonObject();
    }
}
