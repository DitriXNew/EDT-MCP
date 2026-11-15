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
     * Codex P1 on #944 (rounds 2 and 3): an install rollback moves back only entries that are still the
     * ones it moved - here the first entry is replaced while the SECOND is being moved, and the third
     * collides; the replacement stays in the target, named, and is reported as left there.
     */
    @Test
    public void testAnInstallRollbackLeavesAReplacedEntryInTheTarget() throws Exception
    {
        Path parent = freeFolder();
        Path target = Files.createDirectory(parent.resolve("repo")); //$NON-NLS-1$
        GitCloneTarget.Checked checked = checked(target);
        GitCloneTarget.Staging staging = threeEntryClone(checked);
        List<Path> movedSoFar = new ArrayList<>();
        List<Path> clashes = new ArrayList<>();
        GitCloneTarget.Installed installed = GitCloneTarget.install(staging, checked, moved -> {
            movedSoFar.add(moved);
            if (movedSoFar.size() != 2)
            {
                return;
            }
            try
            {
                // Another program replaces the FIRST entry while the second one is moved in...
                replace(movedSoFar.get(0));
                // ...and takes the name of the entry still to come, so the install collides.
                try (Stream<Path> rest = Files.list(staging.repository()))
                {
                    Path next = rest.findFirst().orElseThrow();
                    clashes.add(Files.write(target.resolve(next.getFileName().toString()),
                        "their clash".getBytes(StandardCharsets.UTF_8))); //$NON-NLS-1$
                }
            }
            catch (IOException e)
            {
                throw new IllegalStateException(e);
            }
        });

        assertFalse("the collision fails the install", installed.ok()); //$NON-NLS-1$
        Path replaced = movedSoFar.get(0);
        assertTrue(installed.refusal(), installed.refusal().contains("remove them by hand")); //$NON-NLS-1$
        assertEquals("the replacement is reported as left in the target", List.of(replaced), installed.leftInTarget()); //$NON-NLS-1$
        assertEquals("the replacement stays in the target", "theirs, and longer", //$NON-NLS-1$ //$NON-NLS-2$
            new String(Files.readAllBytes(replaced), StandardCharsets.UTF_8));
        assertEquals("their clash", new String(Files.readAllBytes(clashes.get(0)), StandardCharsets.UTF_8)); //$NON-NLS-1$
        assertFalse("the replacement was not pulled into the staging", //$NON-NLS-1$
            Files.exists(staging.repository().resolve(replaced.getFileName().toString())));
        assertTrue("the second entry, still the clone's, went back", //$NON-NLS-1$
            Files.exists(staging.repository().resolve(movedSoFar.get(1).getFileName().toString())));
    }

    /**
     * Codex P1 on #944 (round 3): the identity of a moved entry is taken from the SOURCE before the move;
     * an entry replaced right after it landed is never recorded as the clone's - the install stops there.
     */
    @Test
    public void testAnEntryReplacedRightAfterItsMoveIsNotRecordedAsTheClones() throws Exception
    {
        Path parent = freeFolder();
        Path target = Files.createDirectory(parent.resolve("repo")); //$NON-NLS-1$
        GitCloneTarget.Checked checked = checked(target);
        GitCloneTarget.Staging staging = threeEntryClone(checked);
        List<Path> movedSoFar = new ArrayList<>();
        GitCloneTarget.Installed installed = GitCloneTarget.install(staging, checked, moved -> {
            movedSoFar.add(moved);
            if (movedSoFar.size() == 1)
            {
                try
                {
                    replace(moved);
                }
                catch (IOException e)
                {
                    throw new IllegalStateException(e);
                }
            }
        });

        assertFalse("the install stops at an entry it cannot prove", installed.ok()); //$NON-NLS-1$
        assertEquals(List.of(movedSoFar.get(0)), installed.leftInTarget());
        assertEquals("nothing more was moved", 1, movedSoFar.size()); //$NON-NLS-1$
        assertEquals("theirs, and longer", new String(Files.readAllBytes(movedSoFar.get(0)), StandardCharsets.UTF_8)); //$NON-NLS-1$
        assertFalse(Files.exists(staging.repository().resolve(movedSoFar.get(0).getFileName().toString())));
    }

    /**
     * Codex P1 on #944 (round 3): the fresh container is proved a plain directory before anything is
     * written into it - one swapped for a link right after its creation is refused, and nothing is
     * written where the link points.
     */
    @Test
    public void testAContainerSwappedRightAfterCreationIsRefusedBeforeTheMarker() throws Exception
    {
        Path parent = freeFolder();
        Path outside = Files.createDirectory(parent.resolve("outside")); //$NON-NLS-1$
        try
        {
            GitCloneTarget.createStaging(checked(parent.resolve("repo")), created -> swapForLink(created, outside), //$NON-NLS-1$
                (marker, token) -> Files.write(marker, token.getBytes(StandardCharsets.US_ASCII)));
            fail("a swapped container must be refused"); //$NON-NLS-1$
        }
        catch (GitCloneTarget.StagingReplacedException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("was replaced")); //$NON-NLS-1$
        }
        try (Stream<Path> written = Files.list(outside))
        {
            assertEquals("nothing is written where the link points", 0, written.count()); //$NON-NLS-1$
        }
    }

    /**
     * Codex P1 on #944 (round 3): a marker write that fails because the container was swapped for a link
     * to a directory that already holds a marker leaves that marker alone - the cleanup proves the
     * container first and touches nothing when it cannot.
     */
    @Test
    public void testTheCleanupOfAFailedMarkerWriteDoesNotFollowASwappedContainer() throws Exception
    {
        Path parent = freeFolder();
        Path outside = Files.createDirectory(parent.resolve("outside")); //$NON-NLS-1$
        Path theirMarker = Files.write(outside.resolve(GitCloneTarget.Staging.MARKER),
            "another clone's token".getBytes(StandardCharsets.US_ASCII)); //$NON-NLS-1$
        try
        {
            GitCloneTarget.createStaging(checked(parent.resolve("repo")), created -> { }, (marker, token) -> { //$NON-NLS-1$
                swapForLink(marker.getParent(), outside);
                Files.write(marker, token.getBytes(StandardCharsets.US_ASCII),
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            });
            fail("the marker write must fail"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            // the replaced container is reported, nothing removed
            assertTrue(e.getClass().getName(), e instanceof GitCloneTarget.StagingReplacedException);
        }
        assertEquals("another program's marker is untouched", "another clone's token", //$NON-NLS-1$ //$NON-NLS-2$
            new String(Files.readAllBytes(theirMarker), StandardCharsets.US_ASCII));
    }

    /**
     * Codex P2 on #944 (round 3): once the marker is gone the container is deleted ONCE - a failure is
     * reported, never retried without a proof of ownership.
     */
    @Test
    public void testTheEmptiedContainerIsDeletedOnceWithoutRetry() throws Exception
    {
        Path parent = freeFolder();
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked(parent.resolve("repo"))); //$NON-NLS-1$
        java.util.concurrent.atomic.AtomicInteger tries = new java.util.concurrent.atomic.AtomicInteger();
        staging.containerDelete = container -> {
            tries.incrementAndGet();
            throw new IOException("busy"); //$NON-NLS-1$
        };

        assertFalse("a failed last delete is a leftover, reported", staging.remove()); //$NON-NLS-1$
        assertEquals("tried once: the proof was gone, so no retry", 1, tries.get()); //$NON-NLS-1$
    }

    /** A container swapped while its marker is written is refused before anyone uses it. */
    @Test
    public void testAContainerSwappedDuringTheMarkerWriteIsRefused() throws Exception
    {
        Path parent = freeFolder();
        Path outside = Files.createDirectory(parent.resolve("outside")); //$NON-NLS-1$
        try
        {
            GitCloneTarget.createStaging(checked(parent.resolve("repo")), created -> { }, (marker, token) -> { //$NON-NLS-1$
                Files.write(marker, token.getBytes(StandardCharsets.US_ASCII));
                // ...and the container, marker included, is moved away and a link to elsewhere put there.
                Files.copy(marker, outside.resolve(marker.getFileName()));
                swapForLink(marker.getParent(), outside);
            });
            fail("a container swapped during the marker write must be refused"); //$NON-NLS-1$
        }
        catch (GitCloneTarget.StagingReplacedException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("was replaced")); //$NON-NLS-1$
        }
        assertTrue("nothing where the link points is removed", //$NON-NLS-1$
            Files.exists(outside.resolve(GitCloneTarget.Staging.MARKER)));
    }

    /** A staging directory whose clone holds three files. */
    private static GitCloneTarget.Staging threeEntryClone(GitCloneTarget.Checked checked) throws IOException
    {
        GitCloneTarget.Staging staging = GitCloneTarget.createStaging(checked);
        Files.createDirectories(staging.repository());
        for (String name : List.of("a.txt", "b.txt", "c.txt")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Files.write(staging.repository().resolve(name), "clone".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        }
        return staging;
    }

    /** What another program does: deletes {@code path} and writes a different file there. */
    private static void replace(Path path) throws IOException
    {
        Files.delete(path);
        Files.write(path, "theirs, and longer".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
    }

    /** Replaces the (empty) directory {@code dir} with a link to {@code to}: a junction on Windows. */
    private static void swapForLink(Path dir, Path to)
    {
        try
        {
            deleteTree(dir);
            if (java.io.File.separatorChar == '\\')
            {
                Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", dir.toString(), to.toString()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    .redirectErrorStream(true).start();
                mklink.getInputStream().readAllBytes();
                Assume.assumeTrue("mklink /J failed", mklink.waitFor() == 0); //$NON-NLS-1$
            }
            else
            {
                Files.createSymbolicLink(dir, to);
            }
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
