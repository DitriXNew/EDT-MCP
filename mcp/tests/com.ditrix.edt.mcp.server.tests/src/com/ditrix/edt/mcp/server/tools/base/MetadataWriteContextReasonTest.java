/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IWorkspaceOrchestrator;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool.ProjectContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Drives the actual metadata-write gate without a workspace lookup or SWT dispatch. */
public class MetadataWriteContextReasonTest
{
    private Field plugin;
    private Object previousPlugin;
    private Activator activator;
    private IProject project;
    private IProjectDescription description;
    private IDtProjectManager manager;
    private AbstractMetadataWriteTool tool;

    @Before
    public void setUp() throws Exception
    {
        plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        activator = mock(Activator.class);
        plugin.set(null, activator);
        when(activator.getConfigurationProvider()).thenReturn(mock(IConfigurationProvider.class));
        manager = mock(IDtProjectManager.class);
        when(activator.getDtProjectManager()).thenReturn(manager);
        project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        description = mock(IProjectDescription.class);
        when(description.getNatureIds()).thenReturn(new String[] {
            "com._1c.g5.v8.dt.core.V8ConfigurationNature" });
        when(project.getDescription()).thenReturn(description);
        tool = mock(AbstractMetadataWriteTool.class, CALLS_REAL_METHODS);
    }

    @After
    public void tearDown() throws Exception
    {
        plugin.set(null, previousPlugin);
    }

    private String refusal(boolean allowNoConfiguration)
    {
        ProjectContext context = tool.resolveProjectRoot("Demo", project, allowNoConfiguration);
        assertTrue(context.hasError());
        JsonObject error = JsonParser.parseString(context.error).getAsJsonObject();
        assertFalse(error.get("success").getAsBoolean());
        return error.get("error").getAsString();
    }

    @Test
    public void theScopeWriteGateNamesTheUnstartedContextAndRemedy()
    {
        String error = refusal(true);
        assertTrue(error, error.startsWith("Could not get configuration for project: Demo."));
        assertTrue(error, error.contains("context is not started"));
        assertTrue(error, error.contains("close and reopen"));
    }

    @Test
    public void theConfigurationWriteGateAlsoNamesTheUnstartedContext()
    {
        assertTrue(refusal(false).contains("context is not started"));
    }

    @Test
    public void aPlainProjectKeepsTheGenericWriteRefusal()
    {
        when(description.getNatureIds()).thenReturn(new String[0]);
        assertEquals("Could not get configuration for project: Demo", refusal(true));
    }

    @Test
    public void aRegisteredStartingContextNamesCleanProjectRecovery()
    {
        when(manager.getDtProject(project)).thenReturn(mock(IDtProject.class));
        when(activator.getWorkspaceOrchestrator()).thenReturn(mock(IWorkspaceOrchestrator.class));
        String error = refusal(true);
        assertTrue(error, error.contains("services are starting"));
        assertTrue(error, error.contains("clean_project"));
    }

    @Test
    public void aRegisteredStartingContextWriteRefusalEndsWithRetryAdvice()
    {
        when(manager.getDtProject(project)).thenReturn(mock(IDtProject.class));
        when(activator.getWorkspaceOrchestrator()).thenReturn(mock(IWorkspaceOrchestrator.class));
        for (boolean allowNoConfiguration : new boolean[] { true, false })
        {
            String error = refusal(allowNoConfiguration);
            assertTrue(error, error.startsWith("Could not get configuration for project: Demo. EDT is starting"));
            assertTrue(error, error.endsWith("close and reopen the project in EDT. Please wait and retry."));
        }
    }

    @Test
    public void aQuietRegisteredStartingContextKeepsTheBaseWriteRefusal()
    {
        IDtProject dtProject = mock(IDtProject.class);
        when(manager.getDtProject(project)).thenReturn(dtProject);
        IWorkspaceOrchestrator orchestrator = mock(IWorkspaceOrchestrator.class);
        when(activator.getWorkspaceOrchestrator()).thenReturn(orchestrator);
        IDerivedDataManagerProvider provider = mock(IDerivedDataManagerProvider.class);
        IDerivedDataManager derivedData = mock(IDerivedDataManager.class);
        when(activator.getDerivedDataManagerProvider()).thenReturn(provider);
        when(provider.get(dtProject)).thenReturn(derivedData);
        when(derivedData.isIdle()).thenReturn(true);
        when(derivedData.isAllComputed()).thenReturn(true);
        for (boolean allowNoConfiguration : new boolean[] { true, false })
        {
            assertEquals("Could not get configuration for project: Demo", refusal(allowNoConfiguration));
        }
    }

    @Test
    public void anUnstartedContextWriteRefusalHasNoRetryAdvice()
    {
        for (boolean allowNoConfiguration : new boolean[] { true, false })
        {
            String error = refusal(allowNoConfiguration);
            assertTrue(error, error.endsWith("EDT does not restart it by itself."));
            assertFalse(error, error.contains("Please wait and retry"));
        }
    }
}
