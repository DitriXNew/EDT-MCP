/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IWorkspaceOrchestrator;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.CascadeEnvironment;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.NotReadyReason;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.SearchDependenciesResult;

/** Pins admission of registered, still-starting contexts across the build gate's consumers. */
public class ProjectStateCheckerStartingGateTest
{
    private Field plugin;
    private Object previousPlugin;
    private Activator activator;
    private IProject project;
    private IDtProject dtProject;
    private IDerivedDataManagerProvider provider;
    private IDerivedDataManager derivedData;

    @Before
    public void setUp() throws Exception
    {
        plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        activator = mock(Activator.class);
        plugin.set(null, activator);
        project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        dtProject = mock(IDtProject.class);
        IDtProjectManager manager = mock(IDtProjectManager.class);
        when(activator.getDtProjectManager()).thenReturn(manager);
        when(manager.getDtProject(project)).thenReturn(dtProject);
        IWorkspaceOrchestrator orchestrator = mock(IWorkspaceOrchestrator.class);
        when(activator.getWorkspaceOrchestrator()).thenReturn(orchestrator);
        when(orchestrator.isStarted(project)).thenReturn(false);
        provider = mock(IDerivedDataManagerProvider.class);
        derivedData = mock(IDerivedDataManager.class);
        when(activator.getDerivedDataManagerProvider()).thenReturn(provider);
        when(provider.get(dtProject)).thenReturn(derivedData);
    }

    @After
    public void tearDown() throws Exception
    {
        plugin.set(null, previousPlugin);
    }

    @Test
    public void aStartingContextIsRefusedWithItsMessageAndRetryAdvice()
    {
        ProjectStateResult starting = startingContext();
        String error = ProjectStateChecker.buildingErrorOrNull(starting);
        assertEquals(starting.getMessage() + ". Please wait and retry.", error);
        assertTrue(error.endsWith("Please wait and retry."));
    }

    @Test
    public void theBuildingRefusalIsByteIdentical()
    {
        assertEquals("Project is building: X. Please wait and retry.",
            ProjectStateChecker.buildingErrorOrNull(
                new ProjectStateResult(ProjectState.BUILDING, "Project is building: X")));
    }

    @Test
    public void aPlainProjectIsLeftToTheCallersResolution()
    {
        assertNotRefused(NotReadyReason.NOT_EDT_PROJECT);
    }

    @Test
    public void anUnregisteredContextIsLeftToTheCallersResolution()
    {
        assertNotRefused(NotReadyReason.CONTEXT_NOT_STARTED);
    }

    @Test
    public void aPendingRestartWithoutARegisteredContextIsLeftToTheCaller()
    {
        assertNotRefused(NotReadyReason.CONTEXT_RESTARTING);
    }

    @Test
    public void aClosedProjectIsLeftToTheCallersResolution()
    {
        assertNotRefused(NotReadyReason.CLOSED);
    }

    @Test
    public void aReadyProjectIsAdmitted()
    {
        assertNull(ProjectStateChecker.buildingErrorOrNull(
            new ProjectStateResult(ProjectState.READY, "Project is ready")));
    }

    @Test
    public void anUnknownBuildStateIsLeftToTheCallersResolution()
    {
        assertNull(ProjectStateChecker.buildingErrorOrNull(
            new ProjectStateResult(ProjectState.UNKNOWN, "Cannot determine build state")));
    }

    @Test
    public void theLiveProbeStillRefusesARegisteredStartingContextWithABusyPipeline()
    {
        assertEquals(ProjectState.BUILDING, ProjectStateChecker.checkDtProjectState(dtProject).getState());
        ProjectStateResult actual = ProjectStateChecker.checkProjectState(project);
        assertEquals(NotReadyReason.CONTEXT_STARTING, actual.getReason());
        assertEquals(actual.getMessage() + ". Please wait and retry.",
            ProjectStateChecker.buildingErrorOrNull(project));
    }

    @Test
    public void theModelGateFallbackRefusesAStartingContextWhenTheProviderIsMissing()
    {
        when(activator.getDerivedDataManagerProvider()).thenReturn(null);
        assertEquals(startingContext().getMessage() + ". Please wait and retry.",
            ProjectStateChecker.modelBuildingErrorOrNull(project));
    }

    @Test
    public void theModelGateFallbackRefusesAStartingContextWhenTheManagerIsMissing()
    {
        when(provider.get(dtProject)).thenReturn(null);
        assertEquals(startingContext().getMessage() + ". Please wait and retry.",
            ProjectStateChecker.modelBuildingErrorOrNull(project));
    }

    @Test
    public void theModelGateStillUsesItsOwnModelReadinessWhenItCanAsk()
    {
        when(derivedData.getDerivedDataStatus()).thenReturn(mock(DerivedDataStatus.class));
        when(derivedData.isComputed(org.mockito.ArgumentMatchers.<Collection<String>>any())).thenReturn(false);
        assertEquals("Project is building: the model or the reference index is still being computed. "
            + "Please wait and retry.", ProjectStateChecker.modelBuildingErrorOrNull(project));
        when(derivedData.isComputed(org.mockito.ArgumentMatchers.<Collection<String>>any())).thenReturn(true);
        assertNull(ProjectStateChecker.modelBuildingErrorOrNull(project));
    }

    @Test
    public void theDefaultCascadeProbeRefusesAStartingContext()
    {
        assertEquals(startingContext().getMessage() + ". Please wait and retry.",
            CascadeEnvironment.DEFAULT.buildingErrorOrNull(project));
        assertTrue(CascadeEnvironment.DEFAULT.isBuilding(project));
    }

    @Test
    public void cascadeSettleRefusesAStartingBaseAfterItsDeadline()
    {
        CascadeEnvironment env = mock(CascadeEnvironment.class);
        when(env.hasBmModelProjectNature(project)).thenReturn(true);
        BmModelResolver.Resolution models = availableModels(project);
        when(env.resolveModelsForRefactoring(project)).thenReturn(models);
        when(env.getOpenDtProjects()).thenReturn(Collections.singletonList(project));
        when(env.buildingErrorOrNull(project))
            .thenAnswer(invocation -> CascadeEnvironment.DEFAULT.buildingErrorOrNull(project));
        assertEquals(startingContext().getMessage() + ". Please wait and retry.",
            ProjectStateChecker.settleBeforeCascadeOrError(project, 0L, env));
    }

    @Test
    public void cascadeSettleRefusesAStartingParticipantAfterItsDeadline()
    {
        IProject base = mock(IProject.class);
        when(base.exists()).thenReturn(true);
        when(base.isOpen()).thenReturn(true);
        when(base.getName()).thenReturn("Base");
        CascadeEnvironment env = mock(CascadeEnvironment.class);
        when(env.hasBmModelProjectNature(base)).thenReturn(true);
        BmModelResolver.Resolution models = availableModels(base);
        when(env.resolveModelsForRefactoring(base)).thenReturn(models);
        when(env.getOpenDtProjects()).thenReturn(Arrays.asList(base, project));
        when(env.isExtensionProject(project)).thenReturn(true);
        when(env.resolveBaseProject(project)).thenReturn(base);
        when(env.isBuilding(project)).thenAnswer(invocation -> CascadeEnvironment.DEFAULT.isBuilding(project));
        assertEquals("Project 'Demo' extends 'Base' and is still building, so it takes part in this cascade "
            + "with an incomplete index. Please wait and retry.",
            ProjectStateChecker.settleBeforeCascadeOrError(base, 0L, env));
    }

    @Test
    public void searchDependenciesRemainNotReadyForAStartingContext()
    {
        CascadeEnvironment env = mock(CascadeEnvironment.class);
        when(env.getOpenDtProjects()).thenReturn(Collections.singletonList(project));
        when(env.getOpenDependentNatureProjects()).thenReturn(Collections.emptyList());
        when(env.getOpenExtensionNatureProjects()).thenReturn(Collections.emptyList());
        when(env.getProjectState(project)).thenReturn(startingContext());
        SearchDependenciesResult result = ProjectStateChecker.determineSearchDependencies(project, env);
        assertTrue(result.isDetermined());
        assertFalse(result.isAllReady());
    }

    private static BmModelResolver.Resolution availableModels(IProject project)
    {
        IBmModelManager models = mock(IBmModelManager.class);
        when(models.getModel(project)).thenReturn(mock(IBmModel.class));
        return BmModelResolver.resolve(project, models);
    }

    private static ProjectStateResult startingContext()
    {
        return ProjectStateChecker.classifyContext(true, true, true, true,
            () -> { throw new AssertionError("No nature lookup for a registered context"); },
            () -> false, () -> false,
            () -> { throw new AssertionError("No derived-data lookup before context start"); });
    }

    private static void assertNotRefused(NotReadyReason reason)
    {
        assertNull(ProjectStateChecker.buildingErrorOrNull(
            new ProjectStateResult(ProjectState.NOT_AVAILABLE, reason, "Unavailable")));
    }
}
