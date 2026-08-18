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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

/**
 * Covers {@link GitCommonDirectory}: where a linked git worktree keeps the part of the repository it
 * SHARES with the worktree it was added to - the configuration, and git's legacy {@code remotes/}
 * and {@code branches/} files (issue #366).
 *
 * <p>Most expectations below are git's own behaviour, measured on git 2.35.1 against a real linked
 * worktree rather than read off the manual, because several are surprising: a trailing SPACE is NOT
 * stripped (it is part of the path, and git answers {@code fatal: not a git repository}), and a
 * ZERO-BYTE file is fatal ({@code fatal: failed to read .../commondir}) rather than "no common
 * directory". Where a refusal here matches git's, it cannot be a false refusal: the repository is
 * already unusable.
 *
 * <p>One expectation in this class was WRONG for two rounds, and the reason is worth more than the
 * correction. A file holding nothing but a line terminator was recorded as fatal to git, on the
 * strength of a probe whose worktree admin directory was not repository-like: the pointer resolved
 * back there, git could not use that directory, and the failure was read as being about the
 * terminator. With {@code objects/} and {@code refs/} present, git answers 0, resolves the common
 * directory to the admin directory itself, and reads the configuration in it. <b>A fixture that
 * cannot produce the positive outcome is not evidence against it</b> - which is why
 * {@code linkedWorktreeOf} now builds a repository-like admin directory for every test here.
 *
 * <p>The rest are DELIBERATE over-refusals - the {@link GitCommonDirectory.Fault} members whose
 * this code refuses on whatever git would do - and they are kept in their own section below
 * rather than mixed in
 * with the first kind. They are NOT listed again in this sentence, and that is deliberate: this
 * paragraph used to carry a copy of the list, and copies of it drifted three review rounds running.
 * Calling them "what git does" would be the comfortable lie that hides the trade.
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

    @Test
    public void testTheResolvedDirectoryIsCanonicalSoIncludesResolveWhereGitResolvesThem()
        throws Exception
    {
        // git canonicalizes the common directory (get_common_dir_noenv ends in
        // strbuf_add_real_path), and a relative [include] in the configuration under it resolves
        // from that config file's directory. JGit resolves a relative include from the LEXICAL
        // parent of the file it opened - so if this handed back an uncanonical spelling, the
        // pre-flight and the git process it guards would read DIFFERENT include files.
        //
        // What this pins is the property that removes the divergence: the directory handed back is
        // the physical one. The divergence itself needs a symbolic link to observe, and native
        // symbolic links cannot be created on the machine this was written on - stated in the
        // javadoc of GitCommonDirectory#canonical rather than faked with a fixture that would
        // "confirm" whatever it was asked.
        Linked linked = newLinkedWorktree("common-dir-canonical", "../..\n"); //$NON-NLS-1$ //$NON-NLS-2$

        GitCommonDirectory common = GitCommonDirectory.of(linked.adminDir);

        String path = common.directory().getPath();
        assertFalse("the resolved directory must carry no '..' segments - an uncanonical spelling " //$NON-NLS-1$
            + "is exactly what makes a relative include resolve somewhere git does not look: " //$NON-NLS-1$
            + path, path.contains("..")); //$NON-NLS-1$
        assertEquals("...and it must equal the physical path", //$NON-NLS-1$
            common.directory().getCanonicalFile(), common.directory());
        assertEquals("...which is still the shared repository", //$NON-NLS-1$
            linked.sharedDir.getCanonicalFile(), common.directory());
    }

    // ==================== fails CLOSED: the pointer is unusable ====================

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

        // On Windows the tab makes the JOINED path unspellable, and that is the non-NUL half of the
        // UNSPELLABLE_PATH split - the half git was measured to fail on too. Asserting only "it
        // threw" left the two halves indistinguishable, so the wrong label went unnoticed for two
        // rounds. Elsewhere the tab is an ordinary character and some other fault fires first, so
        // the fault itself is only pinned where the platform makes it reachable.
        if (File.separatorChar == '\\')
        {
            try
            {
                GitCommonDirectory.of(linked.adminDir);
                fail("already asserted above"); //$NON-NLS-1$
            }
            catch (GitCommonDirectory.FaultException e)
            {
                assertEquals("a trailing tab is the non-NUL half of the split", //$NON-NLS-1$
                    GitCommonDirectory.Fault.UNSPELLABLE_PATH, e.fault());
                // What git does with the same file is measured in the PR body and deliberately
                // NOT asserted here: it differs by platform, which is why this code stopped
                // claiming a side at all.
            }
        }
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
    public void testAPointerThatIsNothingButALineTerminatorResolvesToTheGitDirectory()
        throws Exception
    {
        // git reads the byte, strips it, resolves the empty remainder against the git directory and
        // lands on the git directory ITSELF - measured on git 2.35.1 with an admin directory made
        // repository-like: rev-parse --git-common-dir returns that directory, and remote -v prints
        // the remote from the config sitting in it.
        //
        // This test used to assert the opposite, on the strength of a probe whose admin directory
        // was NOT repository-like. The pointer resolved somewhere git could not use, git failed for
        // that reason, and the failure was read as being about the terminators. A fixture that
        // cannot produce the positive outcome is not evidence against it - the same shape of false
        // proof this class keeps turning up, found this time in our own measurement.
        for (String terminator : List.of("\n", "\r\n")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Linked linked = newLinkedWorktree("common-dir-eol-only", terminator); //$NON-NLS-1$

            GitCommonDirectory common = GitCommonDirectory.of(linked.adminDir);

            assertTrue("a commondir file is present, so this IS a linked worktree", //$NON-NLS-1$
                common.linked());
            assertEquals("...and the shared directory is the git directory itself, which is where " //$NON-NLS-1$
                + "git reads the configuration from in this case", //$NON-NLS-1$
                linked.adminDir.getCanonicalFile(), common.directory().getCanonicalFile());
        }
    }

    @Test
    public void testAZeroBYTEPointerIsStillRefused() throws Exception
    {
        // The other half of the split, and the reason the two may not be conflated: git dies on a
        // zero-byte commondir (fatal: failed to read .../commondir) and carries on when the file
        // has content that merely strips away.
        Linked linked = newLinkedWorktree("common-dir-zero-bytes", ""); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            GitCommonDirectory.of(linked.adminDir);
            fail("a zero-byte commondir must be refused"); //$NON-NLS-1$
        }
        catch (GitCommonDirectory.FaultException e)
        {
            assertEquals("...as EMPTY, which is now ONLY the zero-byte case", //$NON-NLS-1$
                GitCommonDirectory.Fault.EMPTY, e.fault());
        }
    }

    @Test
    public void testAPointerThatIsNotValidUtf8IsRefusedRatherThanGuessedAt() throws Exception
    {
        // The lenient decoding would not FAIL here - it would SUCCEED, on a different path: an
        // invalid byte becomes U+FFFD, which is an ordinary character that names an ordinary (other)
        // directory, while git takes the bytes literally. So the fixture makes that substituted
        // directory EXIST, which is the only shape in which the two decoders disagree observably.
        //
        // Without it this test proves nothing: '../..<0xFF>' decodes leniently to a path that does
        // not exist, isDirectory() refuses it anyway, and the test passes with either decoder. That
        // is exactly how it was written first, and the mutation run caught it - so the fixture below
        // is the assertion, not the decoration.
        File root = newDirectory("common-dir-bad-utf8"); //$NON-NLS-1$
        File sharedDir = new File(root, ".git"); //$NON-NLS-1$
        File adminDir = new File(new File(sharedDir, "worktrees"), "wt"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fixture: the worktree admin directory must be created", adminDir.mkdirs()); //$NON-NLS-1$
        // What a lenient UTF-8 decode of the bytes below would name - and it is a real directory.
        // Built numerically, with no escape and no raw byte: this file is compiled by Tycho,
        // whose source encoding is not guaranteed to be UTF-8.
        char replacement = (char)0xFFFD;
        File substituted = new File(sharedDir, "shared" + replacement); //$NON-NLS-1$
        assertTrue("fixture: the directory a lenient decode would land on must EXIST, or the " //$NON-NLS-1$
            + "isDirectory() guard refuses this on its own and the decoder is not under test", //$NON-NLS-1$
            substituted.mkdirs());
        // '../../shared<0xFF>' - 0xFF is not a legal UTF-8 byte anywhere, in any position.
        byte[] pointer = new byte[]{'.', '.', '/', '.', '.', '/', 's', 'h', 'a', 'r', 'e', 'd',
            (byte)0xFF, '\n'};
        Files.write(new File(adminDir, COMMON_DIR).toPath(), pointer);

        assertRefused(adminDir, "a pointer this JVM cannot decode names a path we do not know - " //$NON-NLS-1$
            + "substituting U+FFFD for the byte would inspect a DIFFERENT directory and approve " //$NON-NLS-1$
            + "on the strength of it"); //$NON-NLS-1$
    }

    @Test
    public void testAPointerOfExactlyTheMaximumSizeIsStillRead() throws Exception
    {
        // The other side of the bound. A bound that is off by one refuses a file it promised to
        // accept, and "refuses one byte early" is invisible unless both sides are pinned.
        Linked linked = newLinkedWorktree("common-dir-at-bound", "../..\n"); //$NON-NLS-1$ //$NON-NLS-2$
        // Padded to EXACTLY the bound with line terminators, which are stripped back off, so the
        // path itself stays valid while the file is as large as it is allowed to be.
        StringBuilder atBound = new StringBuilder("../.."); //$NON-NLS-1$
        while (atBound.length() < 64 * 1024)
        {
            atBound.append('\n');
        }
        assertEquals("fixture: the file must be exactly at the bound", 64 * 1024, atBound.length()); //$NON-NLS-1$
        Files.write(new File(linked.adminDir, COMMON_DIR).toPath(),
            atBound.toString().getBytes(StandardCharsets.UTF_8));

        GitCommonDirectory common = GitCommonDirectory.of(linked.adminDir);

        assertEquals("a file exactly at the bound is still read", //$NON-NLS-1$
            linked.sharedDir.getCanonicalFile(), common.directory().getCanonicalFile());
    }

    @Test
    public void testACommonDirFileThatIsASymbolicLinkToARegularFileIsStillRead() throws Exception
    {
        // The POSITIVE half of the "must be a regular file" guard, and the only test that can tell
        // the two stats apart. The first one deliberately does NOT follow links (that is git's
        // lstat, and following it would make a dangling link read as "absent"); the second one
        // deliberately DOES, because what must be a regular file is what will be opened - and git
        // reads through a symbolic link here too.
        //
        // Without this test, adding NOFOLLOW_LINKS to the second stat would silently start refusing
        // a layout git accepts, and every other test in this class would stay green.
        //
        // Creating a symbolic link needs a privilege Windows does not grant by default, so this is
        // SKIPPED there and runs on the Linux CI. A skip is visible in the run's Skipped count; a
        // test that quietly passed without exercising anything would not be.
        Linked linked = newLinkedWorktree("common-dir-symlink", "placeholder\n"); //$NON-NLS-1$ //$NON-NLS-2$
        File real = new File(linked.adminDir, "commondir.real"); //$NON-NLS-1$
        Files.write(real.toPath(), "../..\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        File link = new File(linked.adminDir, COMMON_DIR);
        assertTrue("fixture: the placeholder must be removed before the link takes its place", //$NON-NLS-1$
            link.delete());
        try
        {
            Files.createSymbolicLink(link.toPath(), real.toPath());
        }
        catch (IOException | UnsupportedOperationException e) // NOSONAR no privilege: see above
        {
            Assume.assumeNoException("symbolic links are not available to this user", e); //$NON-NLS-1$
        }

        GitCommonDirectory common = GitCommonDirectory.of(linked.adminDir);

        assertTrue("a symlinked commondir is still a linked worktree", common.linked()); //$NON-NLS-1$
        assertEquals("...and it must be read THROUGH, exactly as git reads it", //$NON-NLS-1$
            linked.sharedDir.getCanonicalFile(), common.directory().getCanonicalFile());
    }

    // ==================== fails CLOSED: our own bounds, on purpose ====================

    @Test
    public void testAPointerRootedWithoutADriveIsRefusedRatherThanResolvedUnderTheGitDir()
        throws Exception
    {
        // Windows only, and unreachable elsewhere by construction: git's is_absolute_path() calls a
        // leading '/' or '\' absolute (rooted on the current drive), File.isAbsolute() calls it
        // relative because it names no drive. Taking the second reading resolves the pointer
        // UNDERNEATH the git directory - and the fixture below makes that directory EXIST, which is
        // the only shape in which the disagreement is observable: without it, isDirectory() refuses
        // the pointer anyway and the test would pass with or without the guard.
        //
        // On POSIX a leading '/' is absolute to both, File.isAbsolute() is already true, and the
        // guard is never consulted; a leading '\' there is an ordinary filename character and must
        // be ACCEPTED (testABackslashPointerIsAnORDINARYRelativePathOnPosix). So the whole method
        // is Windows-only, and says so once rather than testing the platform per iteration.
        Assume.assumeTrue("these spellings are only ambiguous on Windows", //$NON-NLS-1$
            File.separatorChar == '\\');
        // 'C:foo' is the third of them and it used to arrive here by ACCIDENT: File.isAbsolute()
        // calls it relative, so it was joined onto the git directory, and the colon in the middle of
        // the result made toPath() throw - a refusal that reported the platform could not spell a
        // path git uses perfectly well. Named now, with the others.
        // The bare "C:" is here because excluding it (length() > 2) left it falling through to
        // whatever the platform threw - the same accident "C:foo" used to have.
        for (String rooted : List.of("/shared-elsewhere", "\\shared-elsewhere", //$NON-NLS-1$ //$NON-NLS-2$
            "C:shared-elsewhere", "C:")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Linked linked = newLinkedWorktree("common-dir-rooted", rooted + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
            // The directory the WRONG reading lands on. For 'C:foo' there is none to make - the
            // wrong reading cannot even be spelled - so only the first two get one, and only they
            // need it: without it isDirectory() would refuse on its own and prove nothing.
            if (rooted.charAt(0) != 'C' && rooted.length() > 1)
            {
                assertTrue("fixture: the directory the WRONG reading lands on must exist", //$NON-NLS-1$
                    new File(linked.adminDir, rooted.substring(1)).mkdirs());
            }

            try
            {
                GitCommonDirectory.of(linked.adminDir);
                fail("'" + rooted + "' must be refused: git roots it somewhere this tool cannot " //$NON-NLS-1$ //$NON-NLS-2$
                    + "reproduce, and resolving it under the git directory would inspect a " //$NON-NLS-1$
                    + "different repository"); //$NON-NLS-1$
            }
            catch (GitCommonDirectory.FaultException e)
            {
                assertEquals("'" + rooted + "' must be refused AS the ambiguous-root fault - " //$NON-NLS-1$ //$NON-NLS-2$
                    + "falling through to whatever the platform happens to throw reports the wrong " //$NON-NLS-1$
                    + "thing to the operator", //$NON-NLS-1$
                    GitCommonDirectory.Fault.AMBIGUOUS_WINDOWS_ROOT, e.fault());
                // Measured on Windows: git answers 'fatal: not a git repository' for all
                // three. On POSIX the backslash form is an ordinary path git uses. The fault is
                // what this pins; whose limit it is is not a property of the fault.
            }
        }
    }

    @Test
    public void testARootedButUNSPELLABLEPointerIsNotARootingAmbiguity() throws Exception
    {
        // Which fault fires must not turn on an irrelevant prefix. A trailing tab makes a pointer
        // unusable whether or not it happens to start with a separator - so '\shared<TAB>' has the
        // same problem as '../..<TAB>', and reporting it as a rooting ambiguity would name the
        // wrong fault and offer the wrong repair: the tab is what has to go, not the prefix.
        //
        // Testing rooting before spelling produced exactly that, which is why the path is validated
        // first now.
        Assume.assumeTrue("a leading backslash only roots on Windows", //$NON-NLS-1$
            File.separatorChar == '\\');
        // Built from characters rather than written as escapes: every attempt to write this literal
        // through a shell layer lost a backslash, which is its own small lesson.
        String pointer = new String(new char[]{'\\', 's', 'h', 'a', 'r', 'e', 'd', '\t', '\n'});
        Linked linked = newLinkedWorktree("common-dir-rooted-tab", pointer); //$NON-NLS-1$

        try
        {
            GitCommonDirectory.of(linked.adminDir);
            fail("a pointer the platform cannot spell must be refused"); //$NON-NLS-1$
        }
        catch (GitCommonDirectory.FaultException e)
        {
            assertEquals("the tab makes it unspellable, and that outranks the rooted prefix", //$NON-NLS-1$
                GitCommonDirectory.Fault.UNSPELLABLE_PATH, e.fault());
            // What git makes of either spelling is not asserted here and is not this test's
            // business - it was measured separately and lives in the PR body.
            // The FAULT is what matters; ownership turned out to be a property of the
            // platform rather than of the pointer, and is no longer claimed.
        }
    }

    @Test
    public void testANonAsciiDriveLetterIsNotADriveAtAll() throws Exception
    {
        // Windows drive letters are A-Z. Character.isLetter() accepts a Cyrillic one, and reading
        // it as a drive would name a rooting ambiguity - and send the operator to fix a prefix -
        // for a path the platform will not accept at all. Which fault fires decides which repair
        // the operator is offered, so it has to be the right one.
        Assume.assumeTrue("only Windows has drive letters to mistake", File.separatorChar == '\\'); //$NON-NLS-1$
        char cyrillicZhe = (char)0x0416;
        Linked linked =
            newLinkedWorktree("common-dir-cyrillic-drive", cyrillicZhe + ":shared\n"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            GitCommonDirectory.of(linked.adminDir);
            fail("the platform cannot use this path, so it must be refused"); //$NON-NLS-1$
        }
        catch (GitCommonDirectory.FaultException e)
        {
            // The exact outcome is known, so it is asserted exactly: merely ruling out the
            // ambiguity would accept any other wrong fault.
            assertEquals("the platform cannot spell this, and git cannot use it either", //$NON-NLS-1$
                GitCommonDirectory.Fault.UNSPELLABLE_PATH, e.fault());
            // Ownership deliberately not asserted - see the class javadoc. What must hold is
            // that the tab outranks the rooted prefix, and that is asserted above.
        }
    }

    @Test
    public void testANulBeatsTheRootingAmbiguityWhenAPointerIsBoth() throws Exception
    {
        // Precedence, and it is not cosmetic: both faults are OURS, but they carry different words
        // and different repairs. The NUL is the more specific fact - git was measured to read it as
        // the end of the path - so it must win. Move the NUL test back below the ambiguity branch
        // and this goes red; every other NUL fixture starts with "../.." and would not notice.
        Assume.assumeTrue("a leading slash is only ambiguous on Windows", //$NON-NLS-1$
            File.separatorChar == '\\');
        Linked linked = newLinkedWorktree("common-dir-rooted-nul", "placeholder\n"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(new File(linked.adminDir, COMMON_DIR).toPath(),
            new byte[]{'\\', 's', 'h', 'a', 'r', 'e', 'd', 0, 'x', '\n'});

        try
        {
            GitCommonDirectory.of(linked.adminDir);
            fail("a pointer holding a NUL must be refused"); //$NON-NLS-1$
        }
        catch (GitCommonDirectory.FaultException e)
        {
            assertEquals("the NUL is the more specific fact and must win over the rooting " //$NON-NLS-1$
                + "ambiguity", GitCommonDirectory.Fault.PATH_HOLDS_NUL, e.fault()); //$NON-NLS-1$
        }
    }

    @Test
    public void testABackslashPointerIsAnORDINARYRelativePathOnPosix() throws Exception
    {
        // The false refusal this guard nearly shipped, and the reason it is platform-DEPENDENT.
        //
        // On POSIX a backslash is not a separator, it is an ordinary character in a filename. So
        // '\shared' is a perfectly good RELATIVE path, git resolves it against the git directory and
        // uses it - and a platform-independent "starts with a slash or a backslash" test refuses it,
        // taking every remote, push, fetch and pull off a repository native git is happy with. That
        // is the more expensive mistake of the two, and it is the same shape as the 'URL:' prefix
        // that blocked a healthy legacy file until it was measured instead of assumed.
        //
        // This runs FOR REAL on the Linux CI, where the branch exists; on Windows the name cannot
        // even be created, and there the guard is right to refuse (asserted separately above).
        Assume.assumeTrue("a backslash is a separator here, not a filename character", //$NON-NLS-1$
            File.separatorChar != '\\');
        Linked linked = newLinkedWorktree("common-dir-backslash", "\\shared\n"); //$NON-NLS-1$ //$NON-NLS-2$
        File named = new File(linked.adminDir, "\\shared"); //$NON-NLS-1$
        assertTrue("fixture: the directory git would resolve to must exist", named.mkdirs()); //$NON-NLS-1$

        GitCommonDirectory common = GitCommonDirectory.of(linked.adminDir);

        assertTrue("a linked worktree, and not refused", common.linked()); //$NON-NLS-1$
        assertEquals("a leading backslash is an ordinary character here - the pointer must resolve " //$NON-NLS-1$
            + "exactly as git resolves it, against the git directory", //$NON-NLS-1$
            named.getCanonicalFile(), common.directory().getCanonicalFile());
    }

    @Test
    public void testAPointerHoldingANulByteIsOursToRefuseAndSaysSo() throws Exception
    {
        // Truncating a pointer at a byte would resolve a DIFFERENT directory than the file names,
        // which is the failure this class exists to stop - and that reason stands on its own, with
        // no reference to what git does with the same file.
        Linked linked = newLinkedWorktree("common-dir-nul", "placeholder\n"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(new File(linked.adminDir, COMMON_DIR).toPath(),
            new byte[]{'.', '.', '/', '.', '.', 0, 'j', 'u', 'n', 'k', '\n'});

        try
        {
            GitCommonDirectory.of(linked.adminDir);
            fail("a pointer the platform cannot spell must be refused"); //$NON-NLS-1$
        }
        catch (GitCommonDirectory.FaultException e)
        {
            assertEquals("...as PATH_HOLDS_NUL, its OWN fault: it arrives through the same " //$NON-NLS-1$
                + "InvalidPathException as the trailing-tab case but NOT with the same " //$NON-NLS-1$
                + "ownership - git survives this one and was measured to die on that one, " //$NON-NLS-1$
                + "and one boolean cannot describe both", //$NON-NLS-1$
                GitCommonDirectory.Fault.PATH_HOLDS_NUL, e.fault());
            // Measured: git reads a NUL as the end of the path and carries on. Recorded in the
            // PR body rather than asserted here, because the refusal no longer says so.
        }
    }

    @Test
    public void testAnOversizePointerIsRefusedRatherThanRead() throws Exception
    {
        // A deliberate, stated bound on untrusted repository content - a genuine commondir holds one
        // short relative path. The bound is refused, not truncated: a truncated path would name some
        // OTHER directory, and reading a repository's remotes out of the wrong place is worse than
        // declining to read them at all.
        //
        // The padding is line terminators, and that is the whole point of the fixture: they are
        // stripped back off, so an implementation that TRUNCATED at the bound instead of refusing
        // would be left with a perfectly good '../..' and would resolve it. Padding with 'x' would
        // not discriminate - the truncated path would not exist and the test would pass either way,
        // which is the same vacuum the malformed-UTF-8 test was written into first.
        Linked linked = newLinkedWorktree("common-dir-huge", "../..\n"); //$NON-NLS-1$ //$NON-NLS-2$
        StringBuilder huge = new StringBuilder("../.."); //$NON-NLS-1$
        while (huge.length() <= 64 * 1024)
        {
            huge.append('\n');
        }
        Files.write(new File(linked.adminDir, COMMON_DIR).toPath(),
            huge.toString().getBytes(StandardCharsets.UTF_8));

        assertRefused(linked.adminDir,
            "content this size will not be read to find out whether it is a path"); //$NON-NLS-1$
    }

    @Test
    public void testADirectoryNamedCommonDirIsRefusedRatherThanTakenForAbsent() throws Exception
    {
        // The failure mode this class exists to avoid, in miniature: anything that is not "the entry
        // is not there" has to fail closed, or a broken worktree reads as an ordinary clone and the
        // check that follows approves a repository it never looked at.
        //
        // This is also the only observable half of the "must be a regular file" guard on Windows.
        // The half that guard exists FOR is a FIFO: on Linux a named pipe passes every existence
        // test and then blocks the open() for ever, with no deadline anywhere near this code. That
        // case cannot be built here - Windows has no mkfifo - so it is stated as unverified rather
        // than claimed. A directory exercises the same branch.
        File adminDir = newDirectory("common-dir-is-a-directory"); //$NON-NLS-1$
        assertTrue("fixture: commondir must be a DIRECTORY here", //$NON-NLS-1$
            new File(adminDir, COMMON_DIR).mkdirs());

        assertRefused(adminDir, "a directory is not an absent file"); //$NON-NLS-1$
    }

    // ==================== the ratchet: the enumeration cannot drift from the code ====================

    @Test
    public void testEveryFaultsWordsArePinnedLiterally()
    {
        // reason() is not a label - it is the sentence an operator reads in the refusal, so it is
        // contract text and gets pinned like any other.
        //
        // It also closes a hole a reviewer found downstream: the test that checks the remaining
        // faults all render in the ordinary shape builds its expectation with the LIVE reason(),
        // exactly as production does. Append a phrase to a reason and both sides moved together and
        // stayed green. Pinned here, that append fails before it can cancel out anywhere else.
        Map<GitCommonDirectory.Fault, String> words =
            new EnumMap<>(GitCommonDirectory.Fault.class);
        words.put(GitCommonDirectory.Fault.UNREADABLE, "it could not be read"); //$NON-NLS-1$
        words.put(GitCommonDirectory.Fault.LAYOUT_UNREADABLE,
            "the git directory's layout could not be read"); //$NON-NLS-1$
        words.put(GitCommonDirectory.Fault.NOT_A_REGULAR_FILE, "it is not a regular file"); //$NON-NLS-1$
        words.put(GitCommonDirectory.Fault.TOO_LARGE, "it is larger than this tool will read"); //$NON-NLS-1$
        words.put(GitCommonDirectory.Fault.NOT_UTF_8, "it is not valid UTF-8"); //$NON-NLS-1$
        words.put(GitCommonDirectory.Fault.AMBIGUOUS_WINDOWS_ROOT,
            "Windows roots it somewhere this tool cannot reproduce"); //$NON-NLS-1$
        words.put(GitCommonDirectory.Fault.EMPTY, "it is empty"); //$NON-NLS-1$
        words.put(GitCommonDirectory.Fault.NOT_A_DIRECTORY, "what it names is not a directory"); //$NON-NLS-1$
        words.put(GitCommonDirectory.Fault.PATH_HOLDS_NUL, "it holds a NUL byte"); //$NON-NLS-1$
        words.put(GitCommonDirectory.Fault.UNSPELLABLE_PATH,
            "the platform cannot use it as a path"); //$NON-NLS-1$
        words.put(GitCommonDirectory.Fault.TARGET_UNREADABLE,
            "what it names could not be looked at"); //$NON-NLS-1$

        assertEquals("every Fault's words must be pinned - an unpinned one can be reworded, and " //$NON-NLS-1$
            + "it is what the operator reads", //$NON-NLS-1$
            EnumSet.allOf(GitCommonDirectory.Fault.class), words.keySet());
        for (Map.Entry<GitCommonDirectory.Fault, String> e : words.entrySet())
        {
            assertEquals(e.getKey() + ": its words are contract text", //$NON-NLS-1$
                e.getValue(), e.getKey().reason());
            // Not the word "git" - LAYOUT_UNREADABLE names the git DIRECTORY, a filesystem object,
            // and that is a fact about the repository rather than a claim about the program. What
            // may not appear is git as a SUBJECT doing something, because reason() goes straight
            // into the refusal.
            for (String verb : new String[]{"git reads", "git can", "git will", "git would", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "git carries", "git fails", "git rejects", "git dies", "native git"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            {
                assertFalse(e.getKey() + ": its words may not say what git DOES - that is the " //$NON-NLS-1$
                    + "claim this change removed, and reason() goes straight into the refusal", //$NON-NLS-1$
                    e.getValue().contains(verb));
            }
        }
    }



    @Test
    public void testEveryFaultIsReachableAndEveryReachableFaultIsEnumerated() throws Exception
    {
        // The reason this test exists is worth more than the test. THREE review rounds in a row
        // found the same defect: a refusal added to the code and to none of the places that listed
        // the refusals - a javadoc, a constant's javadoc, the test prose, the operator message and
        // two guides. Each was fixed by hand, and each fix bought the next thread.
        //
        // So the list became one object (Fault), the operator message is derived from it, and this
        // pins the last gap: a member with no fixture, or a fixture the code can no longer produce.
        // Add a Fault and forget everything else, and this goes red before the review does.
        Map<GitCommonDirectory.Fault, File> fixtures = new EnumMap<>(GitCommonDirectory.Fault.class);

        fixtures.put(GitCommonDirectory.Fault.NOT_A_REGULAR_FILE, directoryNamedCommonDir());
        fixtures.put(GitCommonDirectory.Fault.TOO_LARGE,
            newLinkedWorktree("ratchet-too-large", oversizePointer()).adminDir); //$NON-NLS-1$
        fixtures.put(GitCommonDirectory.Fault.NOT_UTF_8, undecodablePointer());
        fixtures.put(GitCommonDirectory.Fault.PATH_HOLDS_NUL, nulBytePointer());
        fixtures.put(GitCommonDirectory.Fault.EMPTY,
            newLinkedWorktree("ratchet-empty", "").adminDir); //$NON-NLS-1$ //$NON-NLS-2$
        fixtures.put(GitCommonDirectory.Fault.NOT_A_DIRECTORY,
            newLinkedWorktree("ratchet-not-a-dir", "../nowhere-at-all\n").adminDir); //$NON-NLS-1$ //$NON-NLS-2$
        // AMBIGUOUS_WINDOWS_ROOT exists only where the two readings of a leading slash DISAGREE. On
        // POSIX they agree, the branch is unreachable, and a fixture for it would resolve or trip a
        // different fault - which is exactly how the first version of this ratchet would have gone
        // red on the Linux CI while passing here.
        if (!new File("/nowhere").isAbsolute()) //$NON-NLS-1$
        {
            fixtures.put(GitCommonDirectory.Fault.AMBIGUOUS_WINDOWS_ROOT,
                newLinkedWorktree("ratchet-rooted", "/nowhere\n").adminDir); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // The members with no fixture are NAMED, not skipped by a null nobody has to justify. A
        // Fault added tomorrow lands in neither set and fails the assertion below, which is the
        // whole job of this test.
        // LAYOUT_UNREADABLE was declared unconstructible here and it is NOT: a git directory whose
        // own path the platform cannot spell reaches the first catch, and that is trivial to build.
        // Excusing it left the production routing untested - both catches could have regressed to
        // UNREADABLE with every test still green.
        fixtures.put(GitCommonDirectory.Fault.LAYOUT_UNREADABLE,
            new File(new String(new char[]{0})));
        Set<GitCommonDirectory.Fault> notConstructible =
            EnumSet.of(GitCommonDirectory.Fault.UNREADABLE, GitCommonDirectory.Fault.TARGET_UNREADABLE);
        if (File.separatorChar == '\\')
        {
            // The trailing tab reaches it here; elsewhere a tab is an ordinary path character.
            fixtures.put(GitCommonDirectory.Fault.UNSPELLABLE_PATH,
                newLinkedWorktree("ratchet-unspellable", "../..\t\n").adminDir); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            notConstructible.add(GitCommonDirectory.Fault.UNSPELLABLE_PATH);
        }
        if (new File("/nowhere").isAbsolute()) //$NON-NLS-1$
        {
            notConstructible.add(GitCommonDirectory.Fault.AMBIGUOUS_WINDOWS_ROOT);
        }
        Set<GitCommonDirectory.Fault> accounted = EnumSet.copyOf(fixtures.keySet());
        accounted.addAll(notConstructible);
        assertEquals("every Fault must either have a fixture that produces it, or be named as one " //$NON-NLS-1$
            + "this platform cannot construct - a member in neither set is a refusal nobody has " //$NON-NLS-1$
            + "shown the code can actually make", //$NON-NLS-1$
            EnumSet.allOf(GitCommonDirectory.Fault.class), accounted);

        for (Map.Entry<GitCommonDirectory.Fault, File> entry : fixtures.entrySet())
        {
            try
            {
                GitCommonDirectory.of(entry.getValue());
                fail(entry.getKey() + ": the fixture must be refused"); //$NON-NLS-1$
            }
            catch (GitCommonDirectory.FaultException e)
            {
                assertEquals("the fixture for " + entry.getKey() + " must produce THAT fault - " //$NON-NLS-1$ //$NON-NLS-2$
                    + "a fixture that happens to trip a different one proves nothing about the " //$NON-NLS-1$
                    + "member it is filed under", entry.getKey(), e.fault()); //$NON-NLS-1$
                assertFalse("a fault's reason must never be empty: it is what the operator reads", //$NON-NLS-1$
                    e.fault().reason().isEmpty());
            }
        }
    }


    /** A git directory whose {@code commondir} is a DIRECTORY - not a regular file. */
    private File directoryNamedCommonDir() throws IOException
    {
        File adminDir = newDirectory("ratchet-not-regular"); //$NON-NLS-1$
        assertTrue("fixture: commondir must be a directory here", //$NON-NLS-1$
            new File(adminDir, COMMON_DIR).mkdirs());
        return adminDir;
    }

    /** A git directory whose {@code commondir} holds a NUL - no platform accepts one in a path. */
    private File nulBytePointer() throws IOException
    {
        Linked linked = newLinkedWorktree("ratchet-nul", "placeholder\n"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(new File(linked.adminDir, COMMON_DIR).toPath(),
            new byte[]{'.', '.', '/', '.', '.', 0, 'x', '\n'});
        return linked.adminDir;
    }

    /** A git directory whose {@code commondir} holds a byte that is not legal UTF-8 anywhere. */
    private File undecodablePointer() throws IOException
    {
        Linked linked = newLinkedWorktree("ratchet-bad-utf8", "placeholder\n"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(new File(linked.adminDir, COMMON_DIR).toPath(),
            new byte[]{'.', '.', '/', '.', '.', (byte)0xFF, '\n'});
        return linked.adminDir;
    }

    /** A {@code commondir} one byte past the bound, padded with strippable terminators. */
    private static String oversizePointer()
    {
        StringBuilder huge = new StringBuilder("../.."); //$NON-NLS-1$
        while (huge.length() <= 64 * 1024)
        {
            huge.append('\n');
        }
        return huge.toString();
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
