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
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

/**
 * The staging directory of a {@code git clone} (#934) against another local process racing it - the
 * second round of review on #944: the marker is never read through a link, a half-made container is
 * not orphaned, a retry proves ownership again, and an install rollback takes back only what it moved.
 */
public class GitCloneTargetStagingTest
{
    private final List<Path> folders = new ArrayList<>();

    @After
    public void tearDown() throws IOException
    {
        for (Path folder : folders)
        {
            deleteTree(folder);
        }
    }

    /** Codex P2 on #944 (round 2): a marker swapped for a link is not read through it. */
    @Test
    public void testAMarkerReplacedByALinkIsNotFollowed() throws Exception
    {
        Path parent = freeFolder();
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked(parent.resolve("repo"))); //$NON-NLS-1$
        Path marker = staging.path().resolve(GitCloneTarget.Staging.MARKER);
        Path outside = parent.resolve("outside-token.txt"); //$NON-NLS-1$
        Files.copy(marker, outside); // the right token, but in a file elsewhere
        Files.delete(marker);
        try
        {
            Files.createSymbolicLink(marker, outside);
        }
        catch (IOException | UnsupportedOperationException e)
        {
            Assume.assumeNoException("symbolic links cannot be created here", e); //$NON-NLS-1$
        }
        assertFalse("a link in place of the marker proves nothing", staging.unchanged()); //$NON-NLS-1$
        assertFalse(staging.remove());
        assertTrue(Files.exists(outside));
    }

    /** The marker read is bounded and wants a small regular file. */
    @Test
    public void testOnlyASmallRegularFileIsReadAsTheMarker() throws Exception
    {
        Path parent = freeFolder();
        Path big = Files.write(parent.resolve("big"), new byte[4096]); //$NON-NLS-1$
        assertNull(GitCloneTarget.readMarker(big));
        Path dir = Files.createDirectory(parent.resolve("dir")); //$NON-NLS-1$
        assertNull(GitCloneTarget.readMarker(dir));
        Path small = Files.write(parent.resolve("small"), "token".getBytes(StandardCharsets.US_ASCII)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("token", GitCloneTarget.readMarker(small)); //$NON-NLS-1$
    }

    /** Codex P2 on #944 (round 2): a container whose marker could not be written is not left behind. */
    @Test
    public void testAContainerWhoseMarkerFailsIsTakenBack() throws Exception
    {
        Path parent = freeFolder();
        GitCloneTarget.Checked checked = checked(parent.resolve("repo")); //$NON-NLS-1$
        try
        {
            GitCloneTarget.createStaging(checked, (marker, token) -> {
                Files.write(marker, "half".getBytes(StandardCharsets.US_ASCII)); //$NON-NLS-1$
                throw new IOException("disk full"); //$NON-NLS-1$
            });
            fail("the marker failure must propagate"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            assertEquals("disk full", e.getMessage()); //$NON-NLS-1$
        }
        try (Stream<Path> left = Files.list(parent))
        {
            assertFalse("no half-made staging directory is left", //$NON-NLS-1$
                left.anyMatch(p -> p.getFileName().toString().startsWith(GitCloneTarget.STAGING_PREFIX)));
        }
    }

    /**
     * Codex P1 on #944 (round 2): ownership is proved again before EVERY removal attempt - here the
     * first attempt is blocked by an open file, and between the attempts another program replaces the
     * container with a directory of its own.
     */
    @Test
    public void testARetryProvesOwnershipAgain() throws Exception
    {
        Assume.assumeTrue("an open file blocks a delete only on Windows", java.io.File.separatorChar == '\\'); //$NON-NLS-1$
        Path parent = freeFolder();
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked(parent.resolve("repo"))); //$NON-NLS-1$
        Path held = Files.createDirectories(staging.repository()).resolve("held.pack"); //$NON-NLS-1$
        Files.write(held, "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        RandomAccessFile lock = new RandomAccessFile(held.toFile(), "rw"); //$NON-NLS-1$
        Path theirs = staging.path().resolve("theirs.txt"); //$NON-NLS-1$
        AtomicBoolean swapped = new AtomicBoolean();
        staging.beforeRetry = () -> {
            if (swapped.getAndSet(true))
            {
                return;
            }
            try
            {
                lock.close();
                deleteTree(staging.path());
                Files.createDirectory(staging.path());
                Files.write(theirs, "theirs".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            }
            catch (IOException e)
            {
                throw new IllegalStateException(e);
            }
        };
        try
        {
            assertFalse("a replaced container must not be removed", staging.remove()); //$NON-NLS-1$
        }
        finally
        {
            lock.close();
        }
        assertTrue("fixture: the first attempt was blocked, so a retry ran", swapped.get()); //$NON-NLS-1$
        assertTrue("another program's file survives the retry", Files.exists(theirs)); //$NON-NLS-1$
    }

    /** The marker goes last: an attempt that leaves anything behind keeps the proof for the next one. */
    @Test
    public void testAPartialAttemptKeepsTheMarker() throws Exception
    {
        Assume.assumeTrue("an open file blocks a delete only on Windows", java.io.File.separatorChar == '\\'); //$NON-NLS-1$
        Path parent = freeFolder();
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked(parent.resolve("repo"))); //$NON-NLS-1$
        Path held = Files.createDirectories(staging.repository()).resolve("held.pack"); //$NON-NLS-1$
        Files.write(held, "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        AtomicBoolean provedAtRetry = new AtomicBoolean();
        try (RandomAccessFile lock = new RandomAccessFile(held.toFile(), "rw")) //$NON-NLS-1$
        {
            staging.beforeRetry = () -> {
                provedAtRetry.set(staging.unchanged());
                try
                {
                    lock.close();
                }
                catch (IOException e)
                {
                    throw new IllegalStateException(e);
                }
            };
            assertTrue("once the file is released the retry finishes the job", staging.remove()); //$NON-NLS-1$
        }
        assertTrue("after a partial attempt the container is still provably this run's", provedAtRetry.get()); //$NON-NLS-1$
        assertFalse(Files.exists(staging.path(), LinkOption.NOFOLLOW_LINKS));
    }

    /**
     * Codex P1 on #944 (round 2): an install rollback moves back only entries that are still the ones it
     * moved - a replacement another program made in the target stays there, named for manual recovery.
     */
    @Test
    public void testAnInstallRollbackLeavesAReplacedEntryInTheTarget() throws Exception
    {
        Path parent = freeFolder();
        Path target = Files.createDirectory(parent.resolve("repo")); //$NON-NLS-1$
        GitCloneTarget.Checked checked = checked(target);
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked);
        Files.createDirectories(staging.repository());
        for (String name : List.of("a.txt", "b.txt", "c.txt")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Files.write(staging.repository().resolve(name), "clone".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        }
        List<Path> replaced = new ArrayList<>();
        List<Path> clashes = new ArrayList<>();
        String refusal = GitCloneTarget.install(staging, checked, moved -> {
            if (!replaced.isEmpty())
            {
                return;
            }
            try
            {
                // Another program replaces what was just moved in...
                Files.delete(moved);
                Files.write(moved, "theirs, and longer".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
                replaced.add(moved);
                // ...and takes the name of one of the entries still to come, so the install collides.
                try (Stream<Path> rest = Files.list(staging.repository()))
                {
                    Path next = rest.findFirst().orElseThrow();
                    Path clash = Files.write(target.resolve(next.getFileName().toString()),
                        "their clash".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
                    clashes.add(clash);
                }
            }
            catch (IOException e)
            {
                throw new IllegalStateException(e);
            }
        });

        assertNotNull("the collision fails the install", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("remove them by hand")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains(replaced.get(0).toString()));
        assertEquals("the replacement stays in the target", "theirs, and longer", //$NON-NLS-1$ //$NON-NLS-2$
            new String(Files.readAllBytes(replaced.get(0)), StandardCharsets.UTF_8));
        assertEquals("their clash", new String(Files.readAllBytes(clashes.get(0)), StandardCharsets.UTF_8)); //$NON-NLS-1$
        assertFalse("the replacement was not pulled into the staging", //$NON-NLS-1$
            Files.exists(staging.repository().resolve(replaced.get(0).getFileName().toString())));
    }

    // ---- helpers ----

    private static GitCloneTarget.Checked checked(Path target)
    {
        GitCloneTarget.Checked checked = GitCloneTarget.check(target, List.of());
        assertTrue(String.valueOf(checked.refusal()), checked.ok());
        return checked;
    }

    private Path freeFolder() throws IOException
    {
        Path folder = Files.createTempDirectory("git-clone-staging").toRealPath(); //$NON-NLS-1$
        folders.add(folder);
        Assume.assumeTrue("the temporary directory lies inside a git repository on this machine", //$NON-NLS-1$
            GitRepositoryResolver.existingRepositoryRefusal(folder.toFile(), "scratch") == null); //$NON-NLS-1$
        return folder;
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS))
        {
            return;
        }
        try (Stream<Path> paths = Files.walk(root))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                path.toFile().setWritable(true);
                path.toFile().delete();
            });
        }
    }
}
