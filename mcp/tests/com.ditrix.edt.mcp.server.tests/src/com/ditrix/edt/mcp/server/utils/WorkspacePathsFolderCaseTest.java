/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Test;

/** Folder identity follows Eclipse, with explicit flags also exercising both rules on CI. */
public class WorkspacePathsFolderCaseTest
{
    private final List<IProject> projects = new ArrayList<>();

    @After
    public void tearDown() throws Exception
    {
        for (IProject project : projects)
        {
            if (project.exists())
            {
                project.delete(true, true, new NullProgressMonitor());
            }
        }
    }

    @Test
    public void testNamesDifferingOnlyByCaseAreOneFolderOnACaseInsensitiveFileSystem() throws Exception
    {
        assertTrue(same("Sales", "sales", false));
        assertTrue(same("Sales", "Sales", false));
        assertFalse(same("Sales", "SalesOld", false));
    }

    @Test
    public void testNamesDifferingOnlyByCaseAreDistinctOnACaseSensitiveFileSystem() throws Exception
    {
        assertFalse(same("Sales", "sales", true));
        assertTrue(same("Sales", "Sales", true));
    }

    @Test
    public void testTheFoldIsEclipsesEqualsIgnoreCaseNotALocaleFold() throws Exception
    {
        assertFalse(same("stra" + (char)0x00df + "e", "STRASSE", false));
        assertTrue(same(String.valueOf((char)0x0131), "I", false));
    }

    @Test
    public void testExistingProjectVariantFindsACaseVariantOnlyWhenTheFileSystemFoldsCase() throws Exception
    {
        assertVariantLookup(createProject(false));
    }

    @Test
    public void testExistingProjectVariantIncludesHiddenProjects() throws Exception
    {
        IProject hidden = createProject(true);
        assertTrue(hidden.isHidden());
        assertVariantLookup(hidden);
    }

    @Test
    public void testTheProductionPredicateReadsTheLocalFileSystem() throws Exception
    {
        assertEquals(EFS.getLocalFileSystem().isCaseSensitive(), WorkspacePaths.projectFoldersAreCaseSensitive());
    }

    @Test
    public void testPlatformOverloadsUseTheLocalFileSystemRule() throws Exception
    {
        IProject project = createProject(false);
        String variant = project.getName().toLowerCase(Locale.ROOT);
        boolean sensitive = EFS.getLocalFileSystem().isCaseSensitive();
        assertEquals(!sensitive, WorkspacePaths.sameProjectFolder(project.getName(), variant));
        assertEquals(sensitive ? null : project, WorkspacePaths.existingProjectVariant(variant));
    }

    private void assertVariantLookup(IProject project) throws Exception
    {
        String name = project.getName();
        String variant = name.toLowerCase(Locale.ROOT);
        assertEquals(project, existing(variant, false));
        assertNull(existing(variant, true));
        assertEquals(project, existing(name, false));
        assertEquals(project, existing(name, true));
        assertNull(existing(name + "Missing", false));
    }

    private IProject createProject(boolean hidden) throws Exception
    {
        String name = "Zz678Var" + UUID.randomUUID().toString().replace("-", "");
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        projects.add(project);
        project.create(ResourcesPlugin.getWorkspace().newProjectDescription(name),
            hidden ? IResource.HIDDEN : IResource.NONE, new NullProgressMonitor());
        return project;
    }

    private static boolean same(String first, String second, boolean sensitive)
    {
        return WorkspacePaths.sameProjectFolder(first, second, sensitive);
    }

    private static IProject existing(String name, boolean sensitive)
    {
        return WorkspacePaths.existingProjectVariant(name, sensitive);
    }
}
