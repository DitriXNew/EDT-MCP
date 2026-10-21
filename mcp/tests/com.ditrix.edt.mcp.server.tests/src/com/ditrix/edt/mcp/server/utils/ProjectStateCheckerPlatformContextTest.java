/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IWorkspaceOrchestrator;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.NotReadyReason;

/** Exercises the platform adapter and the configuration gate, with service snapshots under our control. */
public class ProjectStateCheckerPlatformContextTest
{
    private Field plugin;
    private Object previousPlugin;
    private Activator activator;
    private IProject project;
    private IDtProjectManager manager;
    private IWorkspaceOrchestrator orchestrator;

    @Before
    public void setUp() throws Exception
    {
        plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        activator = mock(Activator.class);
        plugin.set(null, activator);
        project = mock(IProject.class);
        manager = mock(IDtProjectManager.class);
        orchestrator = mock(IWorkspaceOrchestrator.class);
        when(activator.getDtProjectManager()).thenReturn(manager);
        when(activator.getWorkspaceOrchestrator()).thenReturn(orchestrator);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IProjectDescription description = mock(IProjectDescription.class);
        when(project.getDescription()).thenReturn(description);
        when(description.getNatureIds()).thenReturn(new String[] {
            "com._1c.g5.v8.dt.core.V8ConfigurationNature" });
    }

    @After
    public void tearDown() throws Exception
    {
        plugin.set(null, previousPlugin);
    }

    @Test
    public void theAdapterUsesThePersistentNatureWhenTheDtProjectIsMissing()
    {
        assertEquals(NotReadyReason.CONTEXT_NOT_STARTED,
            ProjectStateChecker.checkProjectState(project).getReason());
        verifyZeroInteractions(orchestrator);
    }

    @Test
    public void theAdapterConsultsTheOrchestratorBeforeDerivedData()
    {
        when(manager.getDtProject(project)).thenReturn(mock(IDtProject.class));
        when(orchestrator.isStarted(project)).thenReturn(false);
        assertEquals(NotReadyReason.CONTEXT_STARTING,
            ProjectStateChecker.checkProjectState(project).getReason());
        verify(activator, never()).getDerivedDataManagerProvider();
    }

    @Test
    public void aClosedAdapterDoesNotConsultServicesOrTheDescription() throws Exception
    {
        when(project.isOpen()).thenReturn(false);
        assertEquals(NotReadyReason.CLOSED, ProjectStateChecker.checkProjectState(project).getReason());
        verifyZeroInteractions(activator, manager, orchestrator);
        verify(project, never()).getDescription();
    }

    @Test
    public void aNullProjectHasTheMissingReason()
    {
        assertEquals(NotReadyReason.MISSING, ProjectStateChecker.checkProjectState(null).getReason());
    }

    @Test
    public void aMissingOrchestratorReturnsAnUnknownStartedAnswer()
    {
        when(activator.getWorkspaceOrchestrator()).thenReturn(null);
        assertNull(ProjectStateChecker.readStarted(activator, project));
    }

    @Test
    public void anUnavailableActivatorReturnsAnUnknownStartedAnswer()
    {
        assertNull(ProjectStateChecker.readStarted(null, project));
    }

    @Test
    public void anOrchestratorExceptionReturnsAnUnknownStartedAnswer()
    {
        when(orchestrator.isStarted(project)).thenThrow(new IllegalStateException("service stopping"));
        assertNull(ProjectStateChecker.readStarted(activator, project));
    }

    @Test
    public void aServiceLookupExceptionReturnsAnUnknownStartedAnswer()
    {
        when(activator.getWorkspaceOrchestrator()).thenThrow(new IllegalStateException("service unwired"));
        assertNull(ProjectStateChecker.readStarted(activator, project));
    }

    @Test
    public void aFailedStartedSnapshotFallsBackToTheExistingDerivedDataPath()
    {
        when(manager.getDtProject(project)).thenReturn(mock(IDtProject.class));
        when(orchestrator.isStarted(project)).thenThrow(new IllegalStateException("service stopping"));
        assertEquals(NotReadyReason.BUILD_STATE_UNKNOWN,
            ProjectStateChecker.checkProjectState(project).getReason());
        verify(activator).getDerivedDataManagerProvider();
    }

    @Test
    public void theConfigurationResolverAppendsTheActualContextReason() throws Exception
    {
        when(activator.getConfigurationProvider()).thenReturn(mock(IConfigurationProvider.class));
        Constructor<ProjectContext> constructor =
            ProjectContext.class.getDeclaredConstructor(String.class, IProject.class);
        constructor.setAccessible(true);
        ProjectContext context = constructor.newInstance("Demo", project);
        ProjectContext.ConfigurationResult result = context.resolveConfiguration();
        assertTrue(result.errorJson(), result.errorJson().contains("Could not get configuration for project: Demo."));
        assertTrue(result.errorJson(), result.errorJson().contains("context is not started"));
        assertTrue(result.errorJson(), result.errorJson().contains("close and reopen"));
    }

    @Test
    public void theSiblingAdapterOnlyAddsAdviceForAKnownEdtNature() throws Exception
    {
        String refusal = "Not an EDT project: Demo";
        String actual = ProjectStateChecker.withUnstartedContextReason(project, refusal);
        assertTrue(actual, actual.startsWith(refusal + ". EDT project whose context is not started"));
        IProjectDescription plain = mock(IProjectDescription.class);
        when(plain.getNatureIds()).thenReturn(new String[0]);
        when(project.getDescription()).thenReturn(plain);
        assertEquals(refusal, ProjectStateChecker.withUnstartedContextReason(project, refusal));
    }
}
