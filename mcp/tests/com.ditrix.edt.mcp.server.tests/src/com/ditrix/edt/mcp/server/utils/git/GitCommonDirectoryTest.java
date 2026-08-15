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
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

/**
 * Covers {@link GitCommonDirectory}: where a linked git worktree keeps the part of the repository it
 * SHARES with the worktree it was added to - the configuration, and git's legacy {@code remotes/}
 * and {@code branches/} files (issue #366).
 *
 * <p>Every expectation below is git's own behaviour, measured on git 2.35.1 against a real linked
 * worktree rather than read off the manual, because two of them are surprising: a trailing SPACE is
 * NOT stripped (it is part of the path, and git answers {@code fatal: not a git repository}), and an
 * EMPTY file is fatal ({@code fatal: failed to read .../commondir}) rather than "no common
 * directory". That is what makes failing closed here safe: every condition this class throws on is
 * one where git itself refuses to run, so a refusal built on it cannot be a false refusal.
 */
public class GitCommonDirectoryTest
{
    /** The file a linked worktree carries. */
    private static final String COMMON_DIR = "commondir"; //$NON-NLS-1$

    /** Temporary directories created by a test, deleted in {@link #deleteTemporaries()}. */
    private final List<File> temporaries = new ArrayList<>();

    @After
    public void deleteTemporaries()
    {
        for (File directory : temporaries)
        {
            deleteRecursively(directory);
        }
        temporaries.clear();
    }

    // ==================== an ordinary repository: nothing happens at all ====================

    @Test
    public void testARepositoryWithoutACommonDirFileIsNotLinked() throws Exception
    {
        // The branch every ordinary clone, and every submodule, takes. It is the ONLY branch that
        // reports "not linked", which is what keeps an unreadable file from quietly selecting it.
        File gitDir = newDirectory("common-dir-plain"); //$NON-NLS-1$

        GitCommonDirectory common = GitCommonDirectory.of(gitDir);

        assertFalse("no commondir file means no linked worktree", common.linked()); //$NON-NLS-1$
        assertEquals("...and the git directory is where everything is read from", //$NON-NLS-1$
            gitDir, common.directory());
    }

    @Test
    public void testANullGitDirectoryIsNotLinked() throws Exception
    {
        // A Repository may have no git directory at all. The callers guard on it themselves; this
        // must not be the thing that throws, and must not hand back a relative path that would
        // address the process working directory.
        GitCommonDirectory common = GitCommonDirectory.of(null);

        assertFalse("a repository without a git directory is not a linked worktree", //$NON-NLS-1$
            common.linked());
        assertNull("...and there is no directory to name", common.directory()); //$NON-NLS-1$
    }

    // ==================== the pointer, read the way git reads it ====================

    @Test
    public void testARelativePointerResolvesAgainstTheGitDirectory() throws Exception
    {
        // What 'git worktree add' actually writes: '../..', relative to '.git/worktrees/<name>'.
        Linked linked = newLinkedWorktree("common-dir-relative", "../..\n"); //$NON-NLS-1$ //$NON-NLS-2$

        GitCommonDirectory common = GitCommonDirectory.of(linked.adminDir);

        assertTrue("a commondir file means a linked worktree", common.linked()); //$NON-NLS-1$
        assertEquals("...and a relative pointer is resolved against the git directory", //$NON-NLS-1$
            linked.sharedDir.getCanonicalFile(), common.directory().getCanonicalFile());
    }

    @Test
    public void testTheLineTerminatorComesOffInBothSpellings() throws Exception
    {
        // git strips trailing '\r' and '\n'. Measured: a CRLF-terminated commondir resolves fine, so
        // a file written on Windows by another tool must not take the repository off the air.
        for (String terminator : List.of("\n", "\r\n", "")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Linked linked =
                newLinkedWorktree("common-dir-eol", "../.." + terminator); //$NON-NLS-1$ //$NON-NLS-2$

            GitCommonDirectory common = GitCommonDirectory.of(linked.adminDir);

            assertEquals("a '" + terminator.replace("\r", "\\r").replace("\n", "\\n") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                + "' terminator must not change where the shared repository is", //$NON-NLS-1$
                linked.sharedDir.getCanonicalFile(), common.directory().getCanonicalFile());
        }
    }

    @Test
    public void testAnAbsolutePointerIsUsedAsItStands() throws Exception
    {
        // git's rule: an absolute path is not resolved against anything.
        Linked linked = newLinkedWorktree("common-dir-absolute", "placeholder\n"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(new File(linked.adminDir, COMMON_DIR).toPath(),
            (linked.sharedDir.getAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        GitCommonDirectory common = GitCommonDirectory.of(linked.adminDir);

        assertEquals("an absolute pointer names the directory outright", //$NON-NLS-1$
            linked.sharedDir.getCanonicalFile(), common.directory().getCanonicalFile());
    }

    // ==================== fails CLOSED, exactly where git dies ====================

    @Test
    public void testTrailingWhitespaceIsNotStrippedTheWayTrimWouldStripIt() throws Exception
    {
        // The one that would be got wrong by reaching for trim(): it removes EVERY character up to
        // U+0020, while git removes exactly '\r' and '\n'. Measured - '../.. ' made git answer
        // 'fatal: not a git repository', so a trailing space is part of the path to git.
        //
        // A TAB is what this asserts on, and deliberately so. The space cannot carry the assertion
        // on Windows: the Win32 path layer strips a trailing space from a path component itself, so
        // '../.. ' resolves there no matter what this class does, and a test written on it would be
        // pinning the operating system rather than this code. (That divergence is harmless in the
        // direction it goes: git refuses to run at all, so it prints nothing and there is nothing
        // this check could have failed to mask.) A tab is stripped by trim(), by git never, and by
        // no filesystem here - so it isolates exactly the decision under test.
        Linked linked = newLinkedWorktree("common-dir-tab", "../..\t\n"); //$NON-NLS-1$ //$NON-NLS-2$

        assertRefused(linked.adminDir,
            "trailing whitespace other than a line terminator is part of the path to git"); //$NON-NLS-1$
    }

    @Test
    public void testAnEmptyPointerIsRefused() throws Exception
    {
        // git: 'fatal: failed to read .../commondir'. It does not fall back to "no common
        // directory" - it dies - so neither may this.
        Linked linked = newLinkedWorktree("common-dir-empty", ""); //$NON-NLS-1$ //$NON-NLS-2$

        assertRefused(linked.adminDir, "an empty commondir kills git too"); //$NON-NLS-1$
    }

    @Test
    public void testAPointerNamingNothingIsRefused() throws Exception
    {
        // git: 'fatal: not a git repository'.
        Linked linked = newLinkedWorktree("common-dir-nowhere", "../nowhere-at-all\n"); //$NON-NLS-1$ //$NON-NLS-2$

        assertRefused(linked.adminDir, "a pointer to nothing kills git too"); //$NON-NLS-1$
    }

    @Test
    public void testAPointerNamingAFileRatherThanADirectoryIsRefused() throws Exception
    {
        // The shared part of a repository is a DIRECTORY. Anything else is a broken worktree.
        Linked linked = newLinkedWorktree("common-dir-file", "../../a-file\n"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(new File(linked.sharedDir.getParentFile(), "a-file").toPath(), //$NON-NLS-1$
            "not a directory\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        assertRefused(linked.adminDir, "the shared repository is a directory or it is broken"); //$NON-NLS-1$
    }

    @Test
    public void testAnOversizePointerIsRefusedRatherThanRead() throws Exception
    {
        // A deliberate, stated bound on untrusted repository content - a genuine commondir holds one
        // short relative path. The bound is refused, not truncated: a truncated path would name some
        // OTHER directory, and reading a repository's remotes out of the wrong place is worse than
        // declining to read them at all.
        Linked linked = newLinkedWorktree("common-dir-huge", "../..\n"); //$NON-NLS-1$ //$NON-NLS-2$
        StringBuilder huge = new StringBuilder("../.."); //$NON-NLS-1$
        while (huge.length() <= 64 * 1024)
        {
            huge.append('x');
        }
        Files.write(new File(linked.adminDir, COMMON_DIR).toPath(),
            huge.toString().getBytes(StandardCharsets.UTF_8));

        assertRefused(linked.adminDir, "a file this size is not a path"); //$NON-NLS-1$
    }

    @Test
    public void testADirectoryNamedCommonDirIsRefusedRatherThanTakenForAbsent() throws Exception
    {
        // The failure mode this class exists to avoid, in miniature: anything that is not "the entry
        // is not there" has to fail closed, or a broken worktree reads as an ordinary clone and the
        // check that follows approves a repository it never looked at.
        File adminDir = newDirectory("common-dir-is-a-directory"); //$NON-NLS-1$
        assertTrue("fixture: commondir must be a DIRECTORY here", //$NON-NLS-1$
            new File(adminDir, COMMON_DIR).mkdirs());

        assertRefused(adminDir, "a directory is not an absent file"); //$NON-NLS-1$
    }

    // ==================== helpers ====================

    /** A shared repository directory and the admin directory of a linked worktree of it. */
    private static final class Linked
    {
        final File sharedDir;

        final File adminDir;

        Linked(File sharedDir, File adminDir)
        {
            this.sharedDir = sharedDir;
            this.adminDir = adminDir;
        }
    }

    /**
     * Builds the directory layout {@code git worktree add} produces - {@code <shared>/worktrees/wt}
     * with a {@code commondir} in it - and writes {@code pointer} into that file.
     *
     * @param prefix the temporary directory name prefix
     * @param pointer the exact bytes to put in {@code commondir}, terminator and all
     * @return the shared directory and the worktree's admin directory
     * @throws IOException when the layout cannot be created
     */
    private Linked newLinkedWorktree(String prefix, String pointer) throws IOException
    {
        File root = newDirectory(prefix);
        File sharedDir = new File(root, ".git"); //$NON-NLS-1$
        File adminDir = new File(new File(sharedDir, "worktrees"), "wt"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fixture: the worktree admin directory must be created", adminDir.mkdirs()); //$NON-NLS-1$
        Files.write(new File(adminDir, COMMON_DIR).toPath(),
            pointer.getBytes(StandardCharsets.UTF_8));
        return new Linked(sharedDir, adminDir);
    }

    /**
     * Asserts that {@code gitDir} cannot be resolved, and that it fails rather than silently
     * reporting an ordinary repository - the two outcomes a caller must be able to tell apart.
     */
    private static void assertRefused(File gitDir, String why) throws Exception
    {
        try
        {
            GitCommonDirectory common = GitCommonDirectory.of(gitDir);
            fail(why + " - but it resolved to " + common.directory() //$NON-NLS-1$
                + " (linked=" + common.linked() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (IOException e)
        {
            // Expected: fails closed, so the caller refuses instead of running blind.
        }
    }

    private File newDirectory(String prefix) throws IOException
    {
        File directory = Files.createTempDirectory(prefix).toFile();
        temporaries.add(directory);
        return directory;
    }

    /** Recursively deletes a temporary directory tree (best-effort test cleanup). */
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
