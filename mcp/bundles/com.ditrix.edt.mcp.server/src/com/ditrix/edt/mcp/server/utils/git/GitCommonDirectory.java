/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * git's COMMON directory - where a linked worktree keeps everything that is shared with the
 * repository it was added to, and where JGit 6.8 never looks.
 * <p>
 * {@code git worktree add} gives the new worktree a git directory of its own,
 * {@code .git/worktrees/<name>}, holding only what is per-worktree ({@code HEAD}, {@code index},
 * {@code config.worktree}). Everything else - the configuration, the object store, the refs, and
 * git's legacy {@code remotes/} and {@code branches/} files - stays in the repository's original
 * {@code .git}, and the worktree finds it through a {@code commondir} file. Measured on git 2.35.1
 * from inside such a worktree:
 * <ul>
 * <li>{@code git rev-parse --git-path config} / {@code remotes} / {@code branches} all resolve into
 * the COMMON directory, and {@code git remote get-url <name>} prints what stands in a legacy file
 * there, credential and all;</li>
 * <li>the same files placed in the worktree's OWN git directory are IGNORED - {@code git remote
 * get-url} answers {@code No such remote} - which is why anything read through here must be read
 * from {@link #directory()} and from nowhere else. Reading both would refuse a repository over a
 * file git never looks at;</li>
 * <li>{@code config.worktree} is the exception that proves it: {@code --git-path} keeps that one in
 * the worktree's own directory, and the remote it declares is visible from the linked worktree and
 * NOT from the main one.</li>
 * </ul>
 * JGit 6.8 has no notion of any of this: {@code commondir} occurs nowhere in its sources, and
 * {@code FileRepository} reads its configuration from {@code getDirectory()/config} - a file that
 * does not exist in a linked worktree - so such a repository reports no format version and no
 * remotes whatsoever. Hence this class.
 * <p>
 * <b>What it does NOT do.</b> It answers where the shared directory is, and nothing else. It does
 * not decide whether what it points at is a repository (see {@link #of}), and it does not read the
 * {@code GIT_COMMON_DIR} environment variable - deliberately: that variable is stripped from the
 * environment of every git process this plug-in starts, so a value inherited from the IDE's own
 * environment would make this class and the git it is guarding disagree about which repository they
 * are talking about.
 */
public final class GitCommonDirectory
{
    /** The file a linked worktree's git directory carries, naming the shared one. */
    private static final String COMMON_DIR_FILE = "commondir"; //$NON-NLS-1$

    /**
     * Most bytes read from a {@code commondir} file.
     * <p>
     * A deliberate bound on untrusted repository content, and a deliberate divergence from git,
     * which reads the file whole. It is measured against the file's RAW SIZE, before any stripping,
     * and that is the whole of the trade: a file of {@code .} followed by 64 KiB of line
     * terminators strips down to a perfectly valid pointer, and git resolves it and carries on,
     * while this refuses it. So the bound is NOT "anything this large cannot be a path" - it can be
     * - it is "content this large will not be read to find out".
     * <p>
     * The alternative, streaming to a memory bound while allowing an unbounded removable suffix,
     * buys the ability to accept a file no tool writes: {@code git worktree add} writes
     * {@code ../..} and nothing else, and nothing else in git produces this file at all. Paying
     * complexity for that, in the one place whose job is to be simple enough to be obviously right,
     * is the worse trade - but it is a trade, and it is recorded here rather than dressed up as a
     * fact about paths. No claim is made about any operating system's path limit either.
     * <p>
     * This is one of the three refusals listed as ours-by-choice on {@link #of}; a reader who finds
     * only this constant should not be left thinking git would have failed too.
     */
    private static final int MAX_COMMON_DIR_BYTES = 64 * 1024;

    /** The resolved shared directory; equal to the git directory when there is no linked worktree. */
    private final File directory;

    /** Whether a {@code commondir} file was there at all. */
    private final boolean linked;

    private GitCommonDirectory(File directory, boolean linked)
    {
        this.directory = directory;
        this.linked = linked;
    }

    /**
     * The directory holding the SHARED part of the repository - the configuration, the legacy
     * {@code remotes/} and {@code branches/} files, the object store and the refs.
     *
     * @return the shared directory; the git directory itself when {@link #linked()} is
     *         {@code false}, and {@code null} exactly when the git directory handed in was
     *         {@code null}
     */
    public File directory()
    {
        return directory;
    }

    /**
     * Whether this git directory belongs to a LINKED worktree - that is, whether a
     * {@code commondir} file was found in it.
     * <p>
     * This is git's own test ({@code get_common_dir_noenv} returns 1 iff the file exists), not a
     * comparison of paths: a caller may need to know that git reads {@code <git dir>/config} NOT AT
     * ALL here, which no path comparison can tell it.
     *
     * @return {@code true} when this is a linked worktree
     */
    public boolean linked()
    {
        return linked;
    }

    /**
     * Resolves the common directory of {@code gitDir}, following git's
     * {@code get_common_dir_noenv} where the two can agree - each step below was measured against
     * git 2.35.1 rather than read off the manual - and diverging where it must, deliberately and in
     * writing (the second list further down).
     * <ul>
     * <li><b>Existence is tested the way git tests it</b>, with an {@code lstat} that does not
     * follow a symbolic link ({@link LinkOption#NOFOLLOW_LINKS}). Opening the file instead would
     * follow the link, and a DANGLING one would arrive as "no such file" - a linked worktree
     * silently mistaken for an ordinary clone, which is precisely the fail-open this class exists
     * to remove. To git that entry exists and reading it then kills the command.</li>
     * <li><b>Absent means ordinary.</b> This is the only branch that reports "not linked", so an
     * access failure can never be mistaken for one.</li>
     * <li><b>Only {@code \r} and {@code \n} come off the END.</b> Not {@link String#trim}, which
     * removes every character up to {@code U+0020}: git strips exactly those two, and a trailing
     * SPACE was measured to stay part of the path - {@code ../.. } made git answer
     * {@code fatal: not a git repository}. Swallowing it here would resolve a directory git cannot.
     * (What the operating system then does with the byte is its own business, and Windows differs:
     * its path layer drops a trailing space from a component, so such a pointer resolves there
     * whatever this class does. Harmless in the direction it goes - git refuses to run at all, so
     * there is no output for the check to have missed - and the reason the test for this pins a
     * TAB, which nothing but {@code trim} would remove.)</li>
     * <li><b>Empty is fatal</b>, as it is to git ({@code fatal: failed to read .../commondir}) -
     * and so is a pointer that is nothing BUT a line terminator, which is the more interesting of
     * the two. Reading git's source alone suggests the second one should be accepted (it reads a
     * byte, strips it, and is left with a path that resolves back to the git directory), so it was
     * measured instead: a {@code commondir} holding a single line feed makes git answer
     * {@code fatal: not a git repository}, because the directory it then resolves to is the
     * worktree's own and that is not a repository. Refusing it here therefore agrees with git -
     * whereas "resolve it to the git directory" would have this check inspect a directory git
     * refuses to use, and approve on the strength of it.</li>
     * <li><b>A relative path is resolved against the git directory</b>, an absolute one is used as
     * it stands - git's rule.</li>
     * <li><b>Not canonicalized.</b> git real-paths the result for its own bookkeeping; nothing here
     * needs that - the files are simply opened underneath it, and {@code ..} is resolved by the
     * operating system - while {@code getCanonicalFile()} would add a whole class of failure (UNC
     * paths, junctions, an unreachable network component) that git does not have, and every failure
     * this class does not need is one fewer way to refuse a repository git is happy with.</li>
     * <li><b>The target must be a directory.</b> git answers {@code fatal: not a git repository}
     * when it is not.</li>
     * </ul>
     * <b>Fails CLOSED past the existence test</b>, in two kinds of case that are worth keeping
     * apart rather than blurring into one comfortable claim:
     * <ul>
     * <li><b>where git dies too</b> - an empty pointer, one that is nothing but a line terminator,
     * one naming something that is not a directory, one that cannot be read at all. These were
     * measured, and a refusal on them cannot be a false refusal: the repository is already unusable,
     * and the command would have failed anyway;</li>
     * <li><b>where WE choose to refuse and git might not</b> - a pointer this JVM cannot decode
     * (git takes path bytes literally, so on a POSIX filesystem it can use a name we cannot spell),
     * one over {@link #MAX_COMMON_DIR_BYTES}, and one that is not a regular file. These are
     * deliberate over-refusals, and they are written down as such instead of being dressed up as
     * git's own failure. Each buys something the alternative cannot: inspecting a DIFFERENT
     * directory, reading unbounded untrusted content, and blocking for ever on a named pipe are all
     * worse than declining.</li>
     * </ul>
     * What it does NOT do is
     * judge whether the target is a REPOSITORY. An existing directory that is not one is accepted
     * here, and whatever {@code config} / {@code remotes} / {@code branches} happen to sit in it are
     * read; git cannot run there at all, so it prints nothing and there is nothing to leak, and
     * inventing a repository-shaped predicate is how a check starts refusing healthy files.
     * <p>
     * The bytes are decoded as UTF-8 STRICTLY - a malformed byte is an error, not a
     * {@code U+FFFD}. git takes a path as raw bytes, so a pointer this JVM cannot decode is one
     * where the two of us would disagree about which directory is meant, and the lenient decoding
     * would hand back a real, different path: if that one happens to exist and be clean, the check
     * approves a repository it never opened. So it is refused instead. {@code git worktree add}
     * writes {@code ../..} and nothing else, so no layout git itself produced can reach this - a
     * hand-built or third-party one on a non-UTF-8 platform can, and it is written down rather than
     * called impossible.
     *
     * @param gitDir the repository's git directory ({@code Repository.getDirectory()}); may be
     *            {@code null}, in which case the result is "not linked" with a {@code null}
     *            {@link #directory()} and the caller's own null handling decides
     * @return where the shared part of the repository lives, and whether this is a linked worktree
     * @throws IOException when a {@code commondir} file is there but cannot be turned into a usable
     *             directory - unreadable, not a regular file, over {@link #MAX_COMMON_DIR_BYTES},
     *             not valid UTF-8, empty (or nothing but a line terminator), or naming something
     *             that is not a directory
     */
    public static GitCommonDirectory of(File gitDir) throws IOException
    {
        if (gitDir == null)
        {
            return new GitCommonDirectory(null, false);
        }
        Path commonDirFile = new File(gitDir, COMMON_DIR_FILE).toPath();
        try
        {
            Files.readAttributes(commonDirFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        catch (NoSuchFileException e) // NOSONAR the ordinary repository: git's file_exists() is false
        {
            return new GitCommonDirectory(gitDir, false);
        }
        // Past the existence test, and now FOLLOWING links, because what has to be a regular file is
        // what will be OPENED. A symbolic link to one is fine - git reads through it too - but a
        // FIFO is not: opening a named pipe with no writer blocks for ever, and this runs before the
        // command that would have had a deadline. git blocks there as well; that is not a licence to,
        // because nothing in this plug-in may make an unattended call wait without a bound.
        //
        // What this does NOT close, said plainly rather than left to be discovered: the file is
        // STATTED here and OPENED below, and Java has no non-blocking open to fuse the two. Swap a
        // regular file for a FIFO in between and the open still blocks. That closes the accidental
        // and the stale case, not a racing adversary - and an adversary who can atomically replace
        // files inside this repository's admin directory can already do far worse to the git command
        // this check is guarding.
        if (!Files.readAttributes(commonDirFile, BasicFileAttributes.class).isRegularFile())
        {
            throw new IOException("commondir is not a regular file"); //$NON-NLS-1$
        }
        String value = stripLineTerminators(readBounded(commonDirFile));
        if (value.isEmpty())
        {
            throw new IOException("commondir is empty"); //$NON-NLS-1$
        }
        File named = new File(value);
        if (!named.isAbsolute() && isRooted(value))
        {
            // A spelling this JVM and git do not agree on, so it is refused rather than guessed at.
            // git's is_absolute_path() calls a leading '/' or '\' ABSOLUTE on Windows (rooted on the
            // current drive); File.isAbsolute() calls it relative, because it names no drive. Take
            // the second reading and the pointer resolves UNDERNEATH the git directory - a real,
            // different directory - while git reads the drive-rooted one. If ours happens to exist
            // and be clean, the check approves a repository it never opened: the same fail-open as
            // the lenient decoder, reached by a different route.
            //
            // On a POSIX filesystem this branch is unreachable: there a leading '/' IS absolute to
            // both, File.isAbsolute() is already true, and the ordinary absolute path below handles
            // it. So nothing git itself produces is affected - 'git worktree add' writes '../..'.
            throw new IOException("commondir is rooted without naming a drive"); //$NON-NLS-1$
        }
        File resolved = named.isAbsolute() ? named : new File(gitDir, value);
        if (!resolved.isDirectory())
        {
            throw new IOException("commondir does not name a directory"); //$NON-NLS-1$
        }
        return new GitCommonDirectory(resolved, true);
    }

    /**
     * Reads at most {@link #MAX_COMMON_DIR_BYTES}, refusing anything longer instead of holding it.
     * <p>
     * Streamed rather than {@link Files#readAllBytes}: the size has to be known before the content
     * is in memory, and asking for it first and reading afterwards would answer about a different
     * file than the one read.
     *
     * @param file the file to read
     * @return its bytes, decoded as UTF-8
     * @throws IOException when it cannot be read, is longer than the bound, or is not valid UTF-8
     *             ({@code MalformedInputException} / {@code UnmappableCharacterException}, both of
     *             which are {@code IOException}s, so the caller's one handler covers them)
     */
    private static String readBounded(Path file) throws IOException
    {
        byte[] buffer = new byte[MAX_COMMON_DIR_BYTES + 1];
        int read = 0;
        try (InputStream in = Files.newInputStream(file))
        {
            int chunk;
            while (read < buffer.length && (chunk = in.read(buffer, read, buffer.length - read)) > 0)
            {
                read += chunk;
            }
        }
        if (read > MAX_COMMON_DIR_BYTES)
        {
            throw new IOException("commondir is too large to be a path"); //$NON-NLS-1$
        }
        // STRICT, not new String(bytes, UTF_8): that one replaces a malformed byte with U+FFFD
        // silently, and the result is a DIFFERENT path. git takes these bytes literally, so a
        // pointer this JVM cannot decode would send the two of us to two different directories -
        // and if the substituted one happens to exist and be clean, the check would approve a
        // repository it never read. Refusing a path we cannot decode is the only honest answer:
        // guessing at it is not "best effort", it is inspecting somewhere else.
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(buffer, 0, read))
            .toString();
    }

    /**
     * Whether {@code value} begins with a path separator, which git's {@code is_absolute_path}
     * treats as ABSOLUTE (rooted on the current drive) while {@link File#isAbsolute} does not,
     * because it names no drive.
     * <p>
     * Only ever consulted when {@link File#isAbsolute} already said no, so on a POSIX filesystem -
     * where a leading {@code /} is absolute to both - it never decides anything.
     *
     * @param value the pointer's content
     * @return {@code true} when the two readings of it would disagree
     */
    private static boolean isRooted(String value)
    {
        return !value.isEmpty() && (value.charAt(0) == '/' || value.charAt(0) == '\\');
    }

    /**
     * Removes the line terminators git removes, and nothing else - see {@link #of} for why
     * {@link String#trim} is not what is wanted here.
     *
     * @param value the file's content
     * @return it without any trailing {@code \r} / {@code \n}
     */
    private static String stripLineTerminators(String value)
    {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r'))
        {
            end--;
        }
        return value.substring(0, end);
    }
}
