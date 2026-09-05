/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.git.GitRevisionResolver.Revision;

/**
 * Tests {@link GitRevisionResolver} against REAL temporary repositories - no mocking of JGit
 * anywhere, exactly as {@link GitRepositoryResolverTest} does it. {@link GitRevisionResolver#resolve}
 * itself needs a live EDT workspace, so the tests drive the package-private
 * {@link GitRevisionResolver#resolveIn} seam, which is the whole of the revision logic.
 * <p>
 * The shallow-clone cases are not decoration: this project's own e2e workflow checks out with the
 * default {@code fetch-depth: 1}, so {@code HEAD~1} genuinely does not exist there - and JGit reports
 * that missing ancestor by returning {@code null}, byte-for-byte the way it reports a misspelling.
 * Both cases therefore have to be pinned, or a CI failure would read as a typo.
 */
public class GitRevisionResolverTest
{
    /** The identity every fixture commit is made with: a CI runner has no global git identity. */
    private static final String WHO = "fixture"; //$NON-NLS-1$

    /** The e-mail every fixture commit is made with. */
    private static final String MAIL = "fixture@example.invalid"; //$NON-NLS-1$

    // ==================== the value object ====================

    @Test
    public void testFailedRevisionAccessors()
    {
        String errorJson = "{\"success\":false,\"error\":\"boom\"}"; //$NON-NLS-1$
        Revision revision = Revision.forTest("HEAD", null, null, errorJson); //$NON-NLS-1$

        assertFalse("an errorJson means NOT ok()", revision.ok()); //$NON-NLS-1$
        assertEquals(errorJson, revision.errorJson());
        assertEquals("HEAD", revision.requested()); //$NON-NLS-1$
        assertNull("a failed revision names no commit", revision.commitId()); //$NON-NLS-1$
        assertNull("a failed revision names no work tree", revision.workTree()); //$NON-NLS-1$
    }

    // ==================== resolution ====================

    @Test
    public void testHeadResolvesToTheFullCommitId() throws Exception
    {
        File dir = Files.createTempDirectory("revision-head").toFile(); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(dir).call())
        {
            RevCommit head = commit(git, dir, "a.txt", "1"); //$NON-NLS-1$ //$NON-NLS-2$

            Revision revision = GitRevisionResolver.resolveIn(git.getRepository(), "HEAD"); //$NON-NLS-1$

            assertTrue(revision.errorJson(), revision.ok());
            // The FULL 40-hex id is the point of the class: the platform's git data source parses only
            // a ref name or a full hash, so anything shorter would fail inside the descriptor.
            assertEquals(head.name(), revision.commitId());
            assertEquals(40, revision.commitId().length());
            assertEquals("HEAD", revision.requested()); //$NON-NLS-1$
            assertEquals(dir.getCanonicalFile(), revision.workTree().toFile().getCanonicalFile());
        }
        finally
        {
            deleteRecursively(dir);
        }
    }

    @Test
    public void testABranchNameAndAShortHashResolveToTheSameCommit() throws Exception
    {
        File dir = Files.createTempDirectory("revision-forms").toFile(); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(dir).call())
        {
            RevCommit head = commit(git, dir, "a.txt", "1"); //$NON-NLS-1$ //$NON-NLS-2$
            String branch = git.getRepository().getBranch();

            assertEquals(head.name(), GitRevisionResolver.resolveIn(git.getRepository(), branch).commitId());
            assertEquals("an abbreviation must be expanded, not passed through", head.name(), //$NON-NLS-1$
                GitRevisionResolver.resolveIn(git.getRepository(), head.name().substring(0, 8)).commitId());
        }
        finally
        {
            deleteRecursively(dir);
        }
    }

    @Test
    public void testAnAnnotatedTagIsPeeledToItsCommit() throws Exception
    {
        File dir = Files.createTempDirectory("revision-tag").toFile(); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(dir).call())
        {
            RevCommit head = commit(git, dir, "a.txt", "1"); //$NON-NLS-1$ //$NON-NLS-2$
            git.tag()
                .setName("v1") //$NON-NLS-1$
                .setAnnotated(true)
                .setMessage("release") //$NON-NLS-1$
                .setTagger(new PersonIdent(WHO, MAIL))
                .call();

            Revision revision = GitRevisionResolver.resolveIn(git.getRepository(), "v1"); //$NON-NLS-1$

            assertTrue(revision.errorJson(), revision.ok());
            // Measured: an annotated tag resolves to the TAG object, not to the commit. Passing the tag
            // id on as a "revision" would only fail later, and less legibly.
            assertEquals(head.name(), revision.commitId());
        }
        finally
        {
            deleteRecursively(dir);
        }
    }

    @Test
    public void testARevisionThatNamesATreeIsRefused() throws Exception
    {
        File dir = Files.createTempDirectory("revision-tree").toFile(); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(dir).call())
        {
            commit(git, dir, "a.txt", "1"); //$NON-NLS-1$ //$NON-NLS-2$

            Revision revision = GitRevisionResolver.resolveIn(git.getRepository(), "HEAD^{tree}"); //$NON-NLS-1$

            assertFalse(revision.ok());
            assertNull(revision.commitId());
            assertTrue("the refusal must say what it actually is: " + revision.errorJson(), //$NON-NLS-1$
                revision.errorJson().contains("tree")); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(dir);
        }
    }

    // ==================== refusals ====================

    @Test
    public void testUnknownRevisionIsRefusedNamingTheBadValue() throws Exception
    {
        File dir = Files.createTempDirectory("revision-unknown").toFile(); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(dir).call())
        {
            commit(git, dir, "a.txt", "1"); //$NON-NLS-1$ //$NON-NLS-2$

            Revision revision = GitRevisionResolver.resolveIn(git.getRepository(), "no-such-revision-xyz"); //$NON-NLS-1$

            assertFalse(revision.ok());
            assertNull(revision.commitId());
            String error = revision.errorJson();
            assertTrue("the refusal must quote the revision that failed: " + error, //$NON-NLS-1$
                error.contains("no-such-revision-xyz")); //$NON-NLS-1$
            assertTrue("the refusal must name the sibling tool that lists branches: " + error, //$NON-NLS-1$
                error.contains("list_git_branches")); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(dir);
        }
    }

    @Test
    public void testEmptyAndNullRevisionsAreRefused() throws Exception
    {
        File dir = Files.createTempDirectory("revision-empty").toFile(); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(dir).call())
        {
            commit(git, dir, "a.txt", "1"); //$NON-NLS-1$ //$NON-NLS-2$

            assertFalse(GitRevisionResolver.resolveIn(git.getRepository(), null).ok());
            assertFalse(GitRevisionResolver.resolveIn(git.getRepository(), "   ").ok()); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(dir);
        }
    }

    // ==================== the shallow clone, i.e. what CI actually hands us ====================

    @Test
    public void testShallowCloneRefusesTheMissingAncestorAndSaysWhy() throws Exception
    {
        File origin = Files.createTempDirectory("revision-origin").toFile(); //$NON-NLS-1$
        File shallow = newCloneTarget("revision-shallow"); //$NON-NLS-1$
        try
        {
            seedTwoCommits(origin);
            try (Git clone = shallowCloneOf(origin, shallow))
            {
                Repository repository = clone.getRepository();

                assertTrue("HEAD must resolve even on a depth-1 clone", //$NON-NLS-1$
                    GitRevisionResolver.resolveIn(repository, "HEAD").ok()); //$NON-NLS-1$

                Revision ancestor = GitRevisionResolver.resolveIn(repository, "HEAD~1"); //$NON-NLS-1$
                assertFalse("a depth-1 clone has no parent commit", ancestor.ok()); //$NON-NLS-1$
                assertTrue("the refusal must name the value: " + ancestor.errorJson(), //$NON-NLS-1$
                    ancestor.errorJson().contains("HEAD~1")); //$NON-NLS-1$
                // Measured: JGit answers a missing ancestor with a plain null, exactly like a typo. If
                // the refusal did not mention the shallow case, a CI failure would read as a misspelling
                // and send the reader hunting for a revision that is spelled perfectly well.
                assertTrue("the refusal must name the shallow case: " + ancestor.errorJson(), //$NON-NLS-1$
                    ancestor.errorJson().toLowerCase().contains("shallow")); //$NON-NLS-1$
            }
        }
        finally
        {
            deleteRecursively(shallow);
            deleteRecursively(origin);
        }
    }

    @Test
    public void testShallowCloneWithALocalCommitResolvesBothHeadAndItsParent() throws Exception
    {
        File origin = Files.createTempDirectory("revision-origin2").toFile(); //$NON-NLS-1$
        File shallow = newCloneTarget("revision-shallow2"); //$NON-NLS-1$
        try
        {
            seedTwoCommits(origin);
            try (Git clone = shallowCloneOf(origin, shallow))
            {
                RevCommit fetched = clone.getRepository().parseCommit(clone.getRepository().resolve("HEAD")); //$NON-NLS-1$
                RevCommit local = commit(clone, shallow, "local.txt", "made here"); //$NON-NLS-1$ //$NON-NLS-2$

                Revision head = GitRevisionResolver.resolveIn(clone.getRepository(), "HEAD"); //$NON-NLS-1$
                Revision parent = GitRevisionResolver.resolveIn(clone.getRepository(), "HEAD~1"); //$NON-NLS-1$

                // This is the shape an e2e must manufacture for itself: a commit made INSIDE the run
                // gives the depth-1 checkout both a HEAD and a HEAD~1 without depending on history the
                // runner never fetched.
                assertTrue(head.errorJson(), head.ok());
                assertEquals(local.name(), head.commitId());
                assertTrue(parent.errorJson(), parent.ok());
                assertEquals(fetched.name(), parent.commitId());
            }
        }
        finally
        {
            deleteRecursively(shallow);
            deleteRecursively(origin);
        }
    }

    // ==================== read-only ====================

    @Test
    public void testResolvingNeverMovesTheCheckedOutBranch() throws Exception
    {
        File dir = Files.createTempDirectory("revision-readonly").toFile(); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(dir).call())
        {
            commit(git, dir, "a.txt", "1"); //$NON-NLS-1$ //$NON-NLS-2$
            git.branchCreate().setName("other").call(); //$NON-NLS-1$
            String branchBefore = git.getRepository().getFullBranch();
            String headBefore = git.getRepository().resolve("HEAD").name(); //$NON-NLS-1$

            GitRevisionResolver.resolveIn(git.getRepository(), "HEAD"); //$NON-NLS-1$
            GitRevisionResolver.resolveIn(git.getRepository(), "no-such-revision"); //$NON-NLS-1$

            assertEquals("resolving a revision must never switch branches", branchBefore, //$NON-NLS-1$
                git.getRepository().getFullBranch());
            assertEquals("resolving a revision must never move HEAD", headBefore, //$NON-NLS-1$
                git.getRepository().resolve("HEAD").name()); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(dir);
        }
    }

    // ==================== fixtures ====================

    /**
     * Writes a file and commits it with a fixed identity (a CI runner has no global git identity).
     *
     * @param git the repository handle
     * @param workTree its work tree
     * @param name the file to write
     * @param content the content to write
     * @return the created commit
     * @throws Exception on any JGit or I/O failure - a broken fixture must fail loudly
     */
    private static RevCommit commit(Git git, File workTree, String name, String content) throws Exception
    {
        Files.write(new File(workTree, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(name).call();
        return git.commit().setMessage(name).setAuthor(WHO, MAIL).setCommitter(WHO, MAIL).call();
    }

    /**
     * Builds an origin repository with TWO commits, so a depth-1 clone of it provably leaves one
     * behind.
     *
     * @param dir the directory to build it in
     * @throws Exception on any JGit or I/O failure
     */
    private static void seedTwoCommits(File dir) throws Exception
    {
        try (Git git = Git.init().setDirectory(dir).call())
        {
            commit(git, dir, "a.txt", "first"); //$NON-NLS-1$ //$NON-NLS-2$
            commit(git, dir, "a.txt", "second"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Clones {@code origin} with {@code depth=1} over the local file transport.
     *
     * @param origin the repository to clone
     * @param target the (non-existent) directory to clone into
     * @return the opened clone; the caller closes it
     * @throws Exception on any JGit failure
     */
    private static Git shallowCloneOf(File origin, File target) throws Exception
    {
        Git clone = Git.cloneRepository()
            .setURI(origin.toURI().toString())
            .setDirectory(target)
            .setDepth(1)
            .call();
        assertNotNull(clone.getRepository());
        assertTrue("fixture: the clone must really be shallow, or these cases prove nothing", //$NON-NLS-1$
            new File(clone.getRepository().getDirectory(), "shallow").exists()); //$NON-NLS-1$
        return clone;
    }

    /**
     * @param prefix the temp-directory prefix
     * @return a path that does NOT exist yet - {@code CloneCommand} wants to create it itself
     * @throws Exception on any I/O failure
     */
    private static File newCloneTarget(String prefix) throws Exception
    {
        File dir = Files.createTempDirectory(prefix).toFile();
        assertTrue("fixture: the clone target must not exist yet", dir.delete()); //$NON-NLS-1$
        return dir;
    }

    /** Recursively deletes a temp directory tree (best-effort test cleanup). */
    private static void deleteRecursively(File file)
    {
        if (file == null)
        {
            return;
        }
        File[] children = file.listFiles();
        if (children != null)
        {
            for (File child : children)
            {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
