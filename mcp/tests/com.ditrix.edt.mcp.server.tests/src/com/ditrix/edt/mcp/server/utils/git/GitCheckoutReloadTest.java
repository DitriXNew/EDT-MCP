/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.api.CheckoutResult;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.git.GitCheckoutSupport.CheckoutOutcome;

/**
 * Issue #922: after a checkout, every project of the repository's work tree is refreshed (not only the addressed
 * one), and the checkout answers with a note naming the projects whose model has not taken the new branch in yet.
 */
public class GitCheckoutReloadTest
{
    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    private static List<String> names(List<IProject> projects)
    {
        List<String> names = new ArrayList<>();
        projects.forEach(p -> names.add(p.getName()));
        return names;
    }

    /** The sibling in the same work tree is refreshed too - the project #922 lost its write in. */
    @Test
    public void theSiblingsOfTheWorkTreeAreRefreshedWithTheAddressedProject()
    {
        IProject extension = project("Extension"); //$NON-NLS-1$
        IProject tests = project("Tests"); //$NON-NLS-1$

        List<IProject> refreshed = GitCheckoutSupport.projectsToRefresh(extension, List.of(extension, tests));

        assertEquals(List.of("Extension", "Tests"), names(refreshed)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The addressed project comes first even when the work-tree scan lists it later, and is refreshed once. */
    @Test
    public void theAddressedProjectComesFirstAndNoProjectTwice()
    {
        IProject a = project("A"); //$NON-NLS-1$
        IProject b = project("B"); //$NON-NLS-1$
        IProject c = project("C"); //$NON-NLS-1$

        List<IProject> refreshed = GitCheckoutSupport.projectsToRefresh(b, List.of(a, b, project("A"), c)); //$NON-NLS-1$

        assertEquals(List.of("B", "A", "C"), names(refreshed)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A project outside the scanned work tree (the scan found nothing) is still refreshed itself. */
    @Test
    public void theAddressedProjectIsRefreshedWhenTheScanFindsNothing()
    {
        assertEquals(List.of("Only"), //$NON-NLS-1$
            names(GitCheckoutSupport.projectsToRefresh(project("Only"), List.of()))); //$NON-NLS-1$
    }

    /** Every project settled: no note at all, so a clean switch keeps its result unchanged. */
    @Test
    public void noNoteWhenEveryProjectSettled()
    {
        assertNull(GitCheckoutSupport.modelReloadNote(List.of()));
        assertNull(GitCheckoutSupport.modelReloadNote(null));
    }

    /** The note names every project still loading and says what that means for reads and writes. */
    @Test
    public void theNoteNamesTheProjectsStillLoadingAndTheRefusal()
    {
        String note = GitCheckoutSupport.modelReloadNote(List.of("Extension", "Tests")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(note, note.contains("Extension, Tests")); //$NON-NLS-1$
        assertTrue(note, note.contains("within " + GitCheckoutSupport.MODEL_RELOAD_WAIT_SECONDS + " seconds")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(note, note.contains("metadata writes are refused as 'Project is building'")); //$NON-NLS-1$
        assertTrue(note, note.contains("list_projects")); //$NON-NLS-1$
    }

    /** A refresh warning and a reload note are both kept, in that order. */
    @Test
    public void notesAreJoinedNullSafely()
    {
        assertNull(GitCheckoutSupport.joinNotes(null, null));
        assertEquals("a", GitCheckoutSupport.joinNotes("a", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("b", GitCheckoutSupport.joinNotes(null, "b")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a b", GitCheckoutSupport.joinNotes("a", "b")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** An outcome built without a refresh names no project to wait for. */
    @Test
    public void anOutcomeWithoutARefreshNamesNoProject()
    {
        CheckoutOutcome outcome = CheckoutOutcome.forTest(CheckoutResult.NOT_TRIED_RESULT, null, false, false, null);

        assertTrue(outcome.refreshedProjects().isEmpty());
    }
}
