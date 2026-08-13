/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;

/** Tests the transient null-model state independently of a live EDT service registry. */
public class BmModelResolverTest
{
    @Test
    public void testNullModelReturnsActionableResolutionNamingProject()
    {
        IProject project = project("TestConfiguration"); //$NON-NLS-1$
        IBmModelManager manager = mock(IBmModelManager.class);
        when(manager.getModel(project)).thenReturn(null);

        BmModelResolver.Resolution result = BmModelResolver.resolve(project, manager);

        assertFalse(result.isAvailable());
        assertEquals("TestConfiguration", result.getUnavailableProjectName()); //$NON-NLS-1$
        assertEquals("BM model is not available for project 'TestConfiguration'. Nothing was " //$NON-NLS-1$
            + "deleted. Use list_projects to check the project state, then retry delete_metadata " //$NON-NLS-1$
            + "when the project is ready.", //$NON-NLS-1$
            result.actionableError("delete_metadata", "Nothing was deleted.")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRefactoringResolutionNamesDependentProjectWithoutModel()
    {
        IProject project = project("TestConfiguration"); //$NON-NLS-1$
        IProject extension = project("TestConfiguration.tests"); //$NON-NLS-1$
        IBmModelManager manager = mock(IBmModelManager.class);
        IBmModel model = mock(IBmModel.class);
        when(manager.getModel(project)).thenReturn(model);
        when(manager.getModel(extension)).thenReturn(null);

        BmModelResolver.Resolution result = BmModelResolver.resolveForRefactoring(project, manager,
            Collections.singletonList(extension));

        assertFalse(result.isAvailable());
        assertEquals("TestConfiguration.tests", result.getUnavailableProjectName()); //$NON-NLS-1$
        assertTrue(result.actionableError("delete_metadata", "Nothing was deleted.") //$NON-NLS-1$ //$NON-NLS-2$
            .contains("project 'TestConfiguration.tests'")); //$NON-NLS-1$
    }

    @Test
    public void testRefactoringResolutionReturnsTargetModelWhenEveryModelExists()
    {
        IProject project = project("TestConfiguration"); //$NON-NLS-1$
        IProject extension = project("TestConfiguration.tests"); //$NON-NLS-1$
        IBmModelManager manager = mock(IBmModelManager.class);
        IBmModel model = mock(IBmModel.class);
        when(manager.getModel(project)).thenReturn(model);
        when(manager.getModel(extension)).thenReturn(mock(IBmModel.class));

        BmModelResolver.Resolution result = BmModelResolver.resolveForRefactoring(project, manager,
            Collections.singletonList(extension));

        assertTrue(result.isAvailable());
        assertSame(model, result.getModel());
    }

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }
}
