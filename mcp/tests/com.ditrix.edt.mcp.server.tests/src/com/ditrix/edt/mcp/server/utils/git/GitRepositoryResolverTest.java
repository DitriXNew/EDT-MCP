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
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

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
    /** The fake credential the malformed-configuration fixture carries; a log line naming it leaked. */
    private static final String SECRET = "s3cr3t-token"; //$NON-NLS-1$

    /** The section header that makes that fixture unparseable. */
    private static final String UNPARSEABLE_MARKER = "unparseable-marker-xyz123"; //$NON-NLS-1$

    /**
     * The project name the failure branch is asked to report. Deliberately unlike the temporary
     * directory's name, so "the log line names the project" and "the log line does not name the
     * configuration file" are two different assertions.
     */
    private static final String PROJECT_NAME = "SomeProject"; //$NON-NLS-1$

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

    // ==================== discoverFromLocation: the failure branch that LOGS ====================

    @Test
    public void testAMalformedConfigurationIsLoggedWithoutTheExceptionText() throws Exception
    {
        // The fallback path opens the repository through FileRepositoryBuilder.build(), which loads
        // the repository, user and system configuration. A configuration that is already malformed
        // when the first call arrives therefore fails HERE - before any check the caller would run on
        // the opened repository - and what it throws quotes the configuration (its own file for this
        // shape; the offending line, credential included, when the failing file is the user config).
        // Handing that throwable to the EDT error log would write it into a permanent file, so the
        // branch logs types only. What this pins is what THIS branch hands over; JGit logs a
        // malformed user config through its own logger before it throws, which is the platform's.
        File repoRoot = Files.createTempDirectory("resolver-log-leak").toFile(); //$NON-NLS-1$
        try
        {
            Git.init().setDirectory(repoRoot).call().close();
            Files.write(new File(new File(repoRoot, ".git"), "config").toPath(), //$NON-NLS-1$ //$NON-NLS-2$
                ("[remote \"origin\"]\n\turl = https://user:" + SECRET + "@example.com/r.git\n[" //$NON-NLS-1$ //$NON-NLS-2$
                    + UNPARSEABLE_MARKER + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

            // Positive control: the very call the production branch makes really does throw, and its
            // message really does carry configuration - here the path of the file at fault. Without
            // this, "the log line does not contain it" would pass on an exception that said nothing.
            String thrownText = failureTextOf(repoRoot);
            assertTrue("fixture: the exception must quote the configuration file: " + thrownText, //$NON-NLS-1$
                thrownText.contains(repoRoot.getName()));

            Bundle bundle = FrameworkUtil.getBundle(GitRepositoryResolver.class);
            assertNotNull("this case can only observe the log from inside OSGi; without the bundle " //$NON-NLS-1$
                + "it would 'pass' by seeing nothing at all", bundle); //$NON-NLS-1$
            ILog log = Platform.getLog(bundle);
            List<IStatus> recorded = new ArrayList<>();
            ILogListener listener = (status, plugin) -> recorded.add(status);
            Repository discovered;
            log.addLogListener(listener);
            try
            {
                discovered = GitRepositoryResolver.discoverFromLocation(repoRoot, PROJECT_NAME);
            }
            finally
            {
                log.removeLogListener(listener);
            }

            assertNull("a repository that cannot be opened resolves to nothing, it does not throw", //$NON-NLS-1$
                discovered);
            // Only OUR entry is judged: another thread may log during the window, and this case is
            // about what THIS branch hands over.
            List<IStatus> ours = new ArrayList<>();
            for (IStatus status : recorded)
            {
                if (status.getMessage() != null && status.getMessage().contains(PROJECT_NAME))
                {
                    ours.add(status);
                }
            }
            // Positive control: a listener that recorded nothing would make everything below vacuous.
            assertFalse("the failure branch must really log, or nothing here was observed", //$NON-NLS-1$
                ours.isEmpty());
            for (IStatus status : ours)
            {
                assertNull("no throwable may be attached - Eclipse writes its whole cause chain, " //$NON-NLS-1$
                    + "and JGit puts configuration text in it: " + status.getMessage(), //$NON-NLS-1$
                    status.getException());
                assertFalse("nor may the configuration reach the message: " + status.getMessage(), //$NON-NLS-1$
                    status.getMessage().contains(repoRoot.getName()));
                assertFalse("nor the credential the file holds: " + status.getMessage(), //$NON-NLS-1$
                    status.getMessage().contains(SECRET));
                // ...and it must still be a usable report: what failed, for which project, and the
                // exception type - a type name can carry no configuration.
                assertTrue("the log line must name the project: " + status.getMessage(), //$NON-NLS-1$
                    status.getMessage().contains(PROJECT_NAME));
                assertTrue("...and the exception type behind the failure: " + status.getMessage(), //$NON-NLS-1$
                    status.getMessage().contains("Exception")); //$NON-NLS-1$
            }
        }
        finally
        {
            deleteRecursively(repoRoot);
        }
    }

    // ==================== test helpers ====================

    /**
     * Runs the same call the failure branch wraps and returns the text of what it threw (message plus
     * cause messages), so a case can assert what the branch had to withhold.
     *
     * @param dir the directory to discover from
     * @return the thrown exception's message chain
     */
    private static String failureTextOf(File dir)
    {
        try (Repository repo = GitRepositoryResolver.discoverFromDirectory(dir))
        {
            throw new AssertionError("fixture: this repository opened fine, so the failure branch " //$NON-NLS-1$
                + "is never reached"); //$NON-NLS-1$
        }
        catch (IOException | IllegalArgumentException expected)
        {
            StringBuilder text = new StringBuilder();
            for (Throwable link = expected; link != null; link = link.getCause())
            {
                text.append(link.getMessage()).append('\n');
            }
            return text.toString();
        }
    }


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
