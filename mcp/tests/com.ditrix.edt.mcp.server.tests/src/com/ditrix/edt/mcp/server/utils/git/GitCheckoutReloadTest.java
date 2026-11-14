/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    // ==================== Codex P2 on #928: the refresh does not turn a checkout into a timeout ====================

    /**
     * A refresh that outlives its wait is a NOTE on the successful checkout - never a timed-out checkout - and it
     * stays recorded as a running branch change until it ends.
     */
    @Test(timeout = 60_000)
    public void aRefreshThatOutlivesItsWaitIsANoteAndStaysRecorded() throws Exception
    {
        BlockingJob job = BlockingJob.running();
        String note;
        try
        {
            assertTrue(BranchBindingLock.tryLock());
            try
            {
                note = GitCheckoutSupport.awaitRefresh(job, 100, () -> List.of("SlowRefresh"), //$NON-NLS-1$
                    new java.util.concurrent.atomic.AtomicReference<>());
            }
            finally
            {
                BranchBindingLock.unlock();
            }
            assertTrue(note, note != null && note.contains("still running") //$NON-NLS-1$
                && note.contains("The checkout itself succeeded")); //$NON-NLS-1$
            assertEquals("SlowRefresh", BranchBindingLock.inFlightAmong(List.of("SlowRefresh"))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            job.finish();
        }
        BlockingJob.awaitNotInFlight("SlowRefresh"); //$NON-NLS-1$
    }

    /** A refresh that ends within its wait answers its own failure note, or nothing. */
    @Test(timeout = 60_000)
    public void aRefreshThatEndsAnswersItsOwnNote() throws Exception
    {
        BlockingJob job = BlockingJob.running();
        new Thread(() -> {
            try
            {
                Thread.sleep(100);
                job.finish();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }).start();
        java.util.concurrent.atomic.AtomicReference<String> failure =
            new java.util.concurrent.atomic.AtomicReference<>("refresh failed for X"); //$NON-NLS-1$
        assertTrue(BranchBindingLock.tryLock());
        try
        {
            assertEquals("refresh failed for X", //$NON-NLS-1$
                GitCheckoutSupport.awaitRefresh(job, 10_000, () -> List.of("Fast"), failure)); //$NON-NLS-1$
        }
        finally
        {
            BranchBindingLock.unlock();
        }
        failure.set(null);
        assertNull(GitCheckoutSupport.awaitRefresh(job, 10_000, () -> List.of("Fast"), failure)); //$NON-NLS-1$
    }

    // ==================== Codex P2 on #928: nested repositories are not part of the checkout ====================

    /** A project under a nested repository (its own .git, or a submodule's .git file) is not refreshed. */
    @Test
    public void aProjectOfANestedRepositoryDoesNotBelongToTheWorkTree()
    {
        java.nio.file.Path root = java.nio.file.Paths.get("C:/repo"); //$NON-NLS-1$
        java.util.Set<java.nio.file.Path> withGit = java.util.Set.of(java.nio.file.Paths.get("C:/repo/sub")); //$NON-NLS-1$

        assertTrue(WorkTreeProjects.belongsToWorkTree(java.nio.file.Paths.get("C:/repo/A"), root, withGit::contains)); //$NON-NLS-1$
        assertTrue("the root's own .git is the repository itself", //$NON-NLS-1$
            WorkTreeProjects.belongsToWorkTree(root, root, p -> true));
        assertFalse(WorkTreeProjects.belongsToWorkTree(java.nio.file.Paths.get("C:/repo/sub/B"), root, //$NON-NLS-1$
            withGit::contains));
        assertFalse("a project that is itself a nested repository root", //$NON-NLS-1$
            WorkTreeProjects.belongsToWorkTree(java.nio.file.Paths.get("C:/repo/sub"), root, withGit::contains)); //$NON-NLS-1$
    }

    /** Real directories: a nested clone under the work tree is excluded, a plain folder is not. */
    @Test
    public void nestedRepositoriesAreFoundOnDisk() throws Exception
    {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("wt922"); //$NON-NLS-1$
        try
        {
            java.nio.file.Path plain = java.nio.file.Files.createDirectories(root.resolve("plain/Project")); //$NON-NLS-1$
            java.nio.file.Path nested = java.nio.file.Files.createDirectories(root.resolve("nested/Project")); //$NON-NLS-1$
            java.nio.file.Files.writeString(root.resolve("nested/.git"), "gitdir: ../.git/modules/nested"); //$NON-NLS-1$ //$NON-NLS-2$
            java.util.function.Predicate<java.nio.file.Path> hasGit =
                dir -> java.nio.file.Files.exists(dir.resolve(".git")); //$NON-NLS-1$

            assertTrue(WorkTreeProjects.belongsToWorkTree(plain, root, hasGit));
            assertFalse(WorkTreeProjects.belongsToWorkTree(nested, root, hasGit));
        }
        finally
        {
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    // ==================== Codex P2 on #928: the branch is read again after the unlocked wait ====================

    /** After another checkout moved the repository, the answer names the branch it is on now. */
    @Test
    public void aRepositoryMovedDuringTheWaitIsReported() throws Exception
    {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("wt922git"); //$NON-NLS-1$
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.init().setDirectory(dir.toFile())
            .setInitialBranch("master").call()) //$NON-NLS-1$
        {
            git.commit().setMessage("base").setAllowEmpty(true) //$NON-NLS-1$
                .setAuthor("t", "t@example.invalid").setCommitter("t", "t@example.invalid").call(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            git.branchCreate().setName("feature").call(); //$NON-NLS-1$
            git.checkout().setName("feature").call(); //$NON-NLS-1$
            org.eclipse.jgit.lib.Repository repo = git.getRepository();

            assertNull("still on the switched-to branch", //$NON-NLS-1$
                GitCheckoutSupport.movedAwayFrom(repo, "refs/heads/feature")); //$NON-NLS-1$

            git.checkout().setName("master").call(); //$NON-NLS-1$
            assertEquals("master", GitCheckoutSupport.movedAwayFrom(repo, "refs/heads/feature")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** An outcome built without a refresh names no project to wait for. */
    @Test
    public void anOutcomeWithoutARefreshNamesNoProject()
    {
        CheckoutOutcome outcome = CheckoutOutcome.forTest(CheckoutResult.NOT_TRIED_RESULT, null, false, false, null);

        assertTrue(outcome.refreshedProjects().isEmpty());
    }
}
