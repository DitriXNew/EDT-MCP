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
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class InfobaseBindingsTest
{
    /** Shared service fixture for helper and tool pipeline regressions. */
    public static final class State
    {
        public final IProject project = mock(IProject.class);
        public final IApplicationManager manager = mock(IApplicationManager.class);
        public final IApplication prior = mock(IInfobaseApplication.class);
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
            when(prior.getProject()).thenReturn(project);
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
        for (String concurrent : new String[] {null, "human-choice"})
        {
            var state = new State();
            var next = mock(IApplication.class);
            when(next.getId()).thenReturn("next");
            when(state.manager.getApplication(state.project, "next")).thenReturn(Optional.of(next));
            var result = parse(state.snapshot().run(() -> {
                state.reset();
                state.defaultId.set(concurrent);
                return ToolResult.success().put("applicationId", "next").toJson();
            }, "project", true, false));
            assertTrue(result.toString(), result.get("success").getAsBoolean());
            assertEquals("next", state.defaultId.get());
            assertEquals("prior", state.launchId.get());
        }
    }

    @Test
    public void deletedApplicationBindingsAreReportedAsIntentionalRemovals() throws Exception
    {
        var state = new State();
        var result = parse(state.snapshot().run(() -> {
            state.reset();
            return ToolResult.success().put("applicationId", "prior").put("action", "deleted").put("deleteRegistration", true).toJson();
        }, "project", false, true));
        assertTrue(result.get("success").getAsBoolean());
        assertEquals(2, result.getAsJsonArray("removedBindings").size());
        verify(state.manager, never()).setDefaultApplication(any(), any());
        verify(state.copy, never()).doSave();
    }

    @Test
    public void newerNonNullChoicesAreReportedKeptWithoutWrites() throws Exception
    {
        var state = new State();
        var result = parse(state.snapshot().run(() -> {
            state.defaultId.set("human-choice");
            state.launchId.set("human-choice");
            return ToolResult.success().toJson();
        }, "project", false, false));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals(0, result.getAsJsonArray("unrestoredBindings").size());
        assertEquals(2, result.getAsJsonArray("preservedBindings").size());
        for (var binding : result.getAsJsonArray("preservedBindings"))
        {
            assertEquals("human-choice", binding.getAsJsonObject().get("applicationId").getAsString());
        }
        verify(state.manager, never()).setDefaultApplication(any(), any());
        verify(state.copy, never()).doSave();
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

    @Test
    public void concurrentManualClearsAreRestoredUnderTheSnapshotRule() throws Exception
    {
        var state = new State();
        var result = parse(state.snapshot().run(() -> {
            state.defaultId.set(null);
            state.launchId.set(null);
            return ToolResult.success().toJson();
        }, "project", false, false));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("prior", state.defaultId.get());
        assertEquals("prior", state.launchId.get());
        assertEquals(2, result.getAsJsonArray("restoredBindings").size());
    }

    @Test
    public void registrationUuidDeletionExcludesItsCapturedApplicationBindings() throws Exception
    {
        var state = new State();
        var reference = new ServerInfobaseTarget("host", "base").reference("base");
        when(((IInfobaseApplication)state.prior).getInfobase()).thenReturn(reference);
        var result = parse(state.snapshot().run(() -> {
            state.reset();
            when(state.manager.getApplication(state.project, "prior")).thenReturn(Optional.empty());
            return ToolResult.success().put("action", "deleted").put("deleteRegistration", true)
                .put("registrationId", reference.getUuid().toString()).toJson();
        }, "project", false, true));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertNull(state.defaultId.get());
        assertNull("never save a dangling deleted application ID", state.launchId.get());
        assertEquals(2, result.getAsJsonArray("removedBindings").size());
        verify(state.manager, never()).setDefaultApplication(any(), any());
        verify(state.copy, never()).doSave();
    }

    @Test
    public void invalidOperationResultsReturnErrorsAfterRecoveringBindings() throws Exception
    {
        for (String raw : new String[] {null, "null", "[]", "true", "\"text\"", "malformed", "{}",
            "{\"success\":\"true\"}", "{\"success\":true,\"mutationPending\":{}}"})
        {
            var state = new State();
            var result = parse(state.snapshot().run(() -> {
                state.reset();
                return raw;
            }, "project", true, false));
            assertFalse("invalid result must be an error: " + raw, result.get("success").getAsBoolean());
            assertEquals("prior", state.defaultId.get());
            assertEquals("prior", state.launchId.get());
            assertEquals(2, result.getAsJsonArray("restoredBindings").size());
            assertTrue(result.get("error").getAsString().contains("operation result"));
        }
    }

    @Test
    public void successlessPendingResultIsNormalizedWithoutSkippingRecoveryReport() throws Exception
    {
        var state = new State();
        var result = parse(state.snapshot().run(() -> {
            state.reset();
            return "{\"mutationPending\":true,\"registrationId\":\"retained\"}";
        }, "project", true, false));
        assertFalse(result.get("success").getAsBoolean());
        assertEquals("retained", result.get("registrationId").getAsString());
        assertTrue(result.getAsJsonArray("unrestoredBindings").size() > 0);
        verify(state.manager, never()).setDefaultApplication(any(), any());
        verify(state.copy, never()).doSave();
    }

    @Test
    public void uuidDeletionRestoresUnrelatedRegistrationsAndExcludesOtherProjectsSharingTheUuid() throws Exception
    {
        var first = new State();
        var shared = new State();
        var unrelated = new State();
        var target = new ServerInfobaseTarget("host", "base").reference("base");
        var other = new ServerInfobaseTarget("host", "other").reference("other");
        when(((IInfobaseApplication)first.prior).getInfobase()).thenReturn(target);
        when(((IInfobaseApplication)shared.prior).getInfobase()).thenReturn(target);
        when(((IInfobaseApplication)unrelated.prior).getInfobase()).thenReturn(other);
        when(shared.project.getName()).thenReturn("shared");
        when(unrelated.project.getName()).thenReturn("unrelated");
        when(shared.configuration.getAttribute(eq(LaunchConfigUtils.ATTR_PROJECT_NAME), nullable(String.class)))
            .thenReturn("shared");
        when(unrelated.configuration.getAttribute(eq(LaunchConfigUtils.ATTR_PROJECT_NAME), nullable(String.class)))
            .thenReturn("unrelated");
        when(unrelated.copy.getAttribute(eq(LaunchConfigUtils.ATTR_PROJECT_NAME), nullable(String.class)))
            .thenReturn("unrelated");
        when(first.manager.getApplication(shared.project, "prior")).thenReturn(Optional.of(shared.prior));
        when(first.manager.getApplication(unrelated.project, "prior")).thenReturn(Optional.of(unrelated.prior));
        doAnswer(call -> { unrelated.defaultId.set("prior"); return null; })
            .when(first.manager).setDefaultApplication(unrelated.project, unrelated.prior);
        var snapshot = InfobaseBindings.capture(first.manager,
            new IProject[] {first.project, shared.project, unrelated.project},
            new ILaunchConfiguration[] {first.configuration, shared.configuration, unrelated.configuration},
            project -> project == first.project ? first.defaultId.get()
                : project == shared.project ? shared.defaultId.get() : unrelated.defaultId.get());
        var result = parse(snapshot.run(() -> {
            first.reset();
            shared.reset();
            unrelated.reset();
            return ToolResult.success().put("action", "deleted").put("deleteRegistration", true)
                .put("registrationId", target.getUuid().toString()).toJson();
        }, "project", false, true));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals(4, result.getAsJsonArray("removedBindings").size());
        assertEquals(2, result.getAsJsonArray("restoredBindings").size());
        assertNull(first.launchId.get());
        assertNull(shared.launchId.get());
        assertEquals("prior", unrelated.defaultId.get());
        assertEquals("prior", unrelated.launchId.get());
    }

    @Test
    public void registrationUuidPreviewAndFailureDoNotExcludeRestoration() throws Exception
    {
        for (boolean preview : new boolean[] {true, false})
        {
            var state = new State();
            var reference = new ServerInfobaseTarget("host", "base").reference("base");
            when(((IInfobaseApplication)state.prior).getInfobase()).thenReturn(reference);
            var result = parse(state.snapshot().run(() -> {
                state.reset();
                return (preview ? ToolResult.success().put("action", "preview")
                    : ToolResult.error("cleanup refused"))
                    .put("registrationId", reference.getUuid().toString()).toJson();
            }, "project", false, true));
            assertEquals(preview, result.get("success").getAsBoolean());
            assertEquals(0, result.getAsJsonArray("removedBindings").size());
            assertEquals(2, result.getAsJsonArray("restoredBindings").size());
            assertEquals("prior", state.defaultId.get());
            assertEquals("prior", state.launchId.get());
        }
    }

    @Test
    public void invalidOperationResultStillReportsFailedRestoration() throws Exception
    {
        var state = new State();
        state.failLaunch = true;
        var result = parse(state.snapshot().run(() -> {
            state.reset();
            return "[]";
        }, "project", false, false));
        assertFalse(result.get("success").getAsBoolean());
        assertEquals(1, result.getAsJsonArray("restoredBindings").size());
        assertEquals(1, result.getAsJsonArray("unrestoredBindings").size());
        assertTrue(result.get("operationError").getAsString().contains("operation result"));
    }

    @Test
    public void deletionClearsAndVerifiesBindingsThatEdtLeftDangling() throws Exception
    {
        for (boolean uuidDeletion : new boolean[] {true, false})
        {
            for (int alreadyCleared = 0; alreadyCleared < 4; alreadyCleared++)
            {
                var state = new State();
                var reference = new ServerInfobaseTarget("host", "base").reference("base");
                when(((IInfobaseApplication)state.prior).getInfobase()).thenReturn(reference);
                int cleared = alreadyCleared;
                var result = parse(state.snapshot().run(() -> {
                    if ((cleared & 1) != 0) { state.defaultId.set(null); }
                    if ((cleared & 2) != 0) { state.launchId.set(null); }
                    when(state.manager.getApplication(state.project, "prior")).thenReturn(Optional.empty());
                    return ToolResult.success().put("action", "deleted").put("deleteRegistration", true)
                        .put(uuidDeletion ? "registrationId" : "applicationId",
                            uuidDeletion ? reference.getUuid().toString() : "prior").toJson();
                }, "project", false, true));
                assertTrue(result.toString(), result.get("success").getAsBoolean());
                assertNull("clear the deleted application's stale default", state.defaultId.get());
                assertNull("clear the saved RuntimeClient binding, not just the response", state.launchId.get());
                assertEquals(2, result.getAsJsonArray("removedBindings").size());
                for (var binding : result.getAsJsonArray("removedBindings"))
                {
                    assertEquals("", binding.getAsJsonObject().get("observedApplicationId").getAsString());
                }
                verify(state.manager, times((cleared & 1) == 0 ? 1 : 0))
                    .setDefaultApplication(state.project, null);
                verify(state.copy, times((cleared & 2) == 0 ? 1 : 0)).doSave();
            }
        }
    }

    @Test
    public void failedOrUnsavedDeletionClearsAreReportedAsDangling() throws Exception
    {
        for (boolean defaultFails : new boolean[] {true, false})
        {
            for (boolean throwsFailure : new boolean[] {true, false})
            {
                var state = new State();
                var reference = new ServerInfobaseTarget("host", "base").reference("base");
                when(((IInfobaseApplication)state.prior).getInfobase()).thenReturn(reference);
                if (throwsFailure)
                {
                    state.failDefault = defaultFails;
                    state.failLaunch = !defaultFails;
                }
                else if (defaultFails)
                {
                    doNothing().when(state.manager).setDefaultApplication(any(), any());
                }
                else
                {
                    doReturn(state.configuration).when(state.copy).doSave();
                }
                var result = parse(state.snapshot().run(() -> ToolResult.success().put("action", "deleted").put("deleteRegistration", true)
                    .put("registrationId", reference.getUuid().toString()).toJson(), "project", false, true));
                assertFalse("a dangling binding must prevent plain success: defaultFails=" + defaultFails
                    + ", throwsFailure=" + throwsFailure + ": " + result, result.get("success").getAsBoolean());
                assertEquals(1, result.getAsJsonArray("removedBindings").size());
                assertEquals(1, result.getAsJsonArray("unrestoredBindings").size());
                var dangling = result.getAsJsonArray("unrestoredBindings").get(0).getAsJsonObject();
                assertEquals(defaultFails ? "defaultApplicationId" : "Thin client", dangling.get("setting").getAsString());
                assertEquals("prior", dangling.get("observedApplicationId").getAsString());
                assertTrue(dangling.get("requiredAction").getAsString().contains("Clear"));
                assertEquals(defaultFails ? "prior" : null, state.defaultId.get());
                assertEquals(defaultFails ? null : "prior", state.launchId.get());
                assertTrue(result.get("error").getAsString().contains("EDT"));
            }
        }
    }

    @Test
    public void deletionPreservesNewerApplicationChoicesInsteadOfClearingThem() throws Exception
    {
        var state = new State();
        var reference = new ServerInfobaseTarget("host", "base").reference("base");
        when(((IInfobaseApplication)state.prior).getInfobase()).thenReturn(reference);
        var result = parse(state.snapshot().run(() -> {
            state.defaultId.set("human-choice");
            state.launchId.set("human-choice");
            return ToolResult.success().put("action", "deleted").put("deleteRegistration", true)
                .put("registrationId", reference.getUuid().toString()).toJson();
        }, "project", false, true));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("human-choice", state.defaultId.get());
        assertEquals("human-choice", state.launchId.get());
        assertEquals(0, result.getAsJsonArray("removedBindings").size());
        assertEquals(2, result.getAsJsonArray("preservedBindings").size());
        for (var binding : result.getAsJsonArray("preservedBindings"))
        {
            assertEquals("human-choice", binding.getAsJsonObject().get("applicationId").getAsString());
        }
        verify(state.manager, never()).setDefaultApplication(any(), any());
        verify(state.copy, never()).doSave();
    }

    @Test
    public void pendingDeletionDoesNotClaimClearsOrRecommendDeletedIds() throws Exception
    {
        var state = new State();
        var reference = new ServerInfobaseTarget("host", "base").reference("base");
        when(((IInfobaseApplication)state.prior).getInfobase()).thenReturn(reference);
        var result = parse(state.snapshot().run(() -> ToolResult.success().put("action", "deleted").put("deleteRegistration", true)
            .put("registrationId", reference.getUuid().toString()).put("mutationPending", true).toJson(),
            "project", false, true));
        assertFalse(result.get("success").getAsBoolean());
        assertEquals(0, result.getAsJsonArray("removedBindings").size());
        assertEquals(3, result.getAsJsonArray("unrestoredBindings").size());
        for (int index = 0; index < 2; index++)
        {
            assertEquals("", result.getAsJsonArray("unrestoredBindings").get(index)
                .getAsJsonObject().get("applicationId").getAsString());
        }
        verify(state.manager, never()).setDefaultApplication(any(), any());
        verify(state.copy, never()).doSave();
    }

    private static JsonObject parse(String result)
    {
        return JsonParser.parseString(result).getAsJsonObject();
    }
}
