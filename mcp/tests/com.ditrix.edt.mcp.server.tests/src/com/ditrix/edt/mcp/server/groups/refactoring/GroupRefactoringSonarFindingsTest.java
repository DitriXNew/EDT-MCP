/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.refactoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;

/** Exercises the optional-service hooks called by EDT's refactoring participants. */
public class GroupRefactoringSonarFindingsTest
{
    private static final String FQN = "Catalog.Items"; //$NON-NLS-1$
    private static final String RENAMED = "Catalog.Products"; //$NON-NLS-1$

    private final GroupDeleteRefactoringContributor delete = new GroupDeleteRefactoringContributor();
    private final GroupRenameRefactoringContributor rename = new GroupRenameRefactoringContributor();
    private Field plugin;
    private Object previousPlugin;
    private Activator activator;
    private IGroupService service;
    private IProject project;

    @Before
    public void setUp() throws Exception
    {
        plugin = Activator.class.getDeclaredField("plugin"); //$NON-NLS-1$
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        activator = mock(Activator.class);
        service = mock(IGroupService.class);
        project = mock(IProject.class);
        plugin.set(null, activator);
    }

    @After
    public void tearDown() throws Exception
    {
        plugin.set(null, previousPlugin);
    }

    @Test
    public void deleteHasNoAssociationWithoutGroupService()
    {
        assertFalse(delete.hasAssociation(project, FQN));
    }

    @Test
    public void renameHasNoAssociationWithoutGroupService()
    {
        assertFalse(rename.hasAssociation(project, FQN));
    }

    @Test
    public void deletePostProcessToleratesServiceDisappearing()
    {
        associate();
        assertTrue(delete.hasAssociation(project, FQN));
        when(activator.getGroupService()).thenReturn(null);

        assertFalse(delete.performRemove(project, FQN));
    }

    @Test
    public void deleteLogCaptureToleratesServiceDisappearing()
    {
        associate();
        assertTrue(delete.hasAssociation(project, FQN));
        when(activator.getGroupService()).thenReturn(null);

        assertEquals("Removed deleted object from group null: " + FQN, //$NON-NLS-1$
            delete.removeLogMessage(project, FQN));
    }

    @Test
    public void renamePostChangeToleratesServiceDisappearing()
    {
        associate();
        assertTrue(rename.hasAssociation(project, FQN));
        when(activator.getGroupService()).thenReturn(null);

        assertFalse(rename.performRename(project, FQN, RENAMED));
    }

    @Test
    public void availableServiceStillReceivesRefactoringOperations()
    {
        associate();
        when(service.removeObjectFromGroup(project, FQN)).thenReturn(true);
        when(service.renameObject(project, FQN, RENAMED)).thenReturn(true);

        assertTrue(delete.hasAssociation(project, FQN));
        assertTrue(rename.hasAssociation(project, FQN));
        assertTrue(delete.performRemove(project, FQN));
        assertTrue(rename.performRename(project, FQN, RENAMED));
        verify(service).removeObjectFromGroup(project, FQN);
        verify(service).renameObject(project, FQN, RENAMED);
    }

    private void associate()
    {
        when(activator.getGroupService()).thenReturn(service);
        when(service.findGroupForObject(project, FQN)).thenReturn(mock(Group.class));
    }
}
