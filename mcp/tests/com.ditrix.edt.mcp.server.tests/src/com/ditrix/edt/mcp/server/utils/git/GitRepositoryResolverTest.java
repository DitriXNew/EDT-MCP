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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver.Resolution;

/**
 * Tests {@link GitRepositoryResolver}: the pure {@link Resolution} value object (built via the
 * package-private test factory {@link Resolution#forTest}, added for exactly this purpose - issue #171
 * coverage - since {@link GitRepositoryResolver#resolve(String)} itself needs a live EDT workspace/
 * {@code ProjectContext} and is covered by the e2e suite) and {@link GitRepositoryResolver#discoverFromDirectory}
 * (the pure-JGit {@code .git} discovery step, also extracted as a package-private seam so it is directly
 * testable against a REAL temp git repository - no mocking of JGit/EGit/EDT anywhere here).
 */
public class GitRepositoryResolverTest
{
    // ==================== Resolution: pure accessors ====================

    @Test
    public void testSuccessfulResolutionAccessors()
    {
        Resolution resolution = Resolution.forTest(null, null, false, null);

        assertTrue("no errorJson means ok()", resolution.ok()); //$NON-NLS-1$
        assertNull(resolution.errorJson());
    }

    @Test
    public void testFailedResolutionAccessors()
    {
        String errorJson = "{\"success\":false,\"error\":\"boom\"}"; //$NON-NLS-1$
        Resolution resolution = Resolution.forTest(null, null, false, errorJson);

        assertFalse("an errorJson means NOT ok()", resolution.ok()); //$NON-NLS-1$
        assertEquals(errorJson, resolution.errorJson());
        assertNull("a failed resolution carries no project", resolution.project()); //$NON-NLS-1$
        assertNull("a failed resolution carries no repository", resolution.repository()); //$NON-NLS-1$
    }

    @Test
    public void testRepositoryAccessorRoundTrips() throws Exception
    {
        File repoDir = Files.createTempDirectory("resolver-accessor").toFile(); //$NON-NLS-1$
        try (Repository repo = Git.init().setDirectory(repoDir).call().getRepository())
        {
            Resolution resolution = Resolution.forTest(null, repo, false, null);
            assertTrue(resolution.ok());
            assertEquals("the repository must round-trip unchanged", repo, resolution.repository()); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(repoDir);
        }
    }

    // ==================== Resolution.closeIfOwned(): owned vs borrowed ====================

    @Test
    public void testCloseIfOwnedClosesAnOwnedRepository() throws Exception
    {
        File repoDir = Files.createTempDirectory("resolver-owned").toFile(); //$NON-NLS-1$
        try
        {
            Repository repo = Git.init().setDirectory(repoDir).call().getRepository();
            assertEquals("a freshly-opened repository starts with a use count of 1", 1, useCount(repo)); //$NON-NLS-1$

            Resolution.forTest(null, repo, true, null).closeIfOwned();

            assertEquals("closeIfOwned(owned=true) must call Repository.close()", 0, useCount(repo)); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(repoDir);
        }
    }

    @Test
    public void testCloseIfOwnedLeavesABorrowedRepositoryOpen() throws Exception
    {
        File repoDir = Files.createTempDirectory("resolver-borrowed").toFile(); //$NON-NLS-1$
        try (Repository repo = Git.init().setDirectory(repoDir).call().getRepository())
        {
            assertEquals(1, useCount(repo));

            Resolution.forTest(null, repo, false, null).closeIfOwned();

            assertEquals("closeIfOwned(owned=false) must NOT close an EGit-borrowed repository", 1, //$NON-NLS-1$
                useCount(repo));
        }
        finally
        {
            deleteRecursively(repoDir);
        }
    }

    @Test
    public void testCloseIfOwnedIsANoOpWhenRepositoryIsNull()
    {
        // Must not throw (an error Resolution carries owned=false/repository=null; a successful one
        // could in principle be owned=true with a still-null repository - either way, no NPE).
        Resolution.forTest(null, null, true, null).closeIfOwned();
        Resolution.forTest(null, null, false, null).closeIfOwned();
    }

    @Test
    public void testCloseIfOwnedOnAnErrorResolutionIsANoOp()
    {
        Resolution.forTest(null, null, false, "{\"success\":false}").closeIfOwned(); //$NON-NLS-1$
    }

    // ==================== discoverFromDirectory: pure JGit .git discovery ====================

    @Test
    public void testDiscoverFromDirectoryFindsGitDirWalkingUpFromANestedSubdirectory() throws Exception
    {
        File repoRoot = Files.createTempDirectory("resolver-discover").toFile(); //$NON-NLS-1$
        try
        {
            Git.init().setDirectory(repoRoot).call().close();
            File nested = new File(repoRoot, "src/Catalogs/Foo"); //$NON-NLS-1$
            assertTrue(nested.mkdirs());

            try (Repository discovered = GitRepositoryResolver.discoverFromDirectory(nested))
            {
                assertNotNull("a .git directory exists up the tree - it must be found", discovered); //$NON-NLS-1$
                assertEquals(new File(repoRoot, ".git").getCanonicalFile(), //$NON-NLS-1$
                    discovered.getDirectory().getCanonicalFile());
            }
        }
        finally
        {
            deleteRecursively(repoRoot);
        }
    }

    @Test
    public void testDiscoverFromDirectoryReturnsNullWhenNoGitDirExistsAnywhereUpTheTree() throws Exception
    {
        File notARepo = Files.createTempDirectory("resolver-no-repo").toFile(); //$NON-NLS-1$
        try
        {
            // NOTE: this only proves the "not found starting from here" branch; findGitDir also walks
            // PAST notARepo toward the filesystem root, so this assertion assumes no ancestor of the
            // system temp directory is itself a git working tree (true for every CI/dev sandbox).
            Repository discovered = GitRepositoryResolver.discoverFromDirectory(notARepo);
            assertNull(discovered);
        }
        finally
        {
            deleteRecursively(notARepo);
        }
    }

    // ==================== test helpers ====================

    /**
     * Reads {@link Repository}'s private {@code useCnt} reference-count field via reflection - the only
     * way to observe whether {@link Repository#close()} actually ran without mocking JGit: a freshly
     * opened (non-cached) repository starts at 1, and drops to 0 the moment {@code close()} is called.
     * This is JGit-internal (not public API), but it is read-only introspection of a REAL, live
     * {@link Repository} - not a mock - so it stays honest to the "no mocking JGit" rule while still
     * proving {@link Resolution#closeIfOwned()}'s behaviour.
     */
    private static int useCount(Repository repo) throws ReflectiveOperationException
    {
        Field field = Repository.class.getDeclaredField("useCnt"); //$NON-NLS-1$
        field.setAccessible(true);
        return ((AtomicInteger)field.get(repo)).get();
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
