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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
     * A reader who finds only this constant should not be left thinking git would have failed
     * too - nor that it would have carried on. This code does not know, and no longer says.
     */
    private static final int MAX_COMMON_DIR_BYTES = 64 * 1024;

    /**
     * Every way a {@code commondir} can be unusable.
     * <p>
     * This enumeration is the single source. The operator-facing refusal in the {@code git} tool
     * names the ONE fault that fired, reading its words from here rather than repeating them, and
     * {@code GitCommonDirectoryTest} fails if any member has no fixture. Three review rounds in a row
     * found the same defect - a refusal added to the code and to none of the places that listed the
     * refusals - and the answer to that is not another careful edit, it is having one list.
     * <p>
     * <b>What this deliberately does NOT carry is whose limit a fault is.</b> It did, briefly, as a
     * boolean and then as a three-valued type, and both were wrong - not in their values but in
     * their existence. Measured against git 2.35.1 on Windows: a {@code commondir} of
     * {@code \shared} kills git ({@code fatal: not a git repository}), while on POSIX the same
     * bytes are an ordinary relative path git resolves and uses. The same file, opposite answers.
     * Whose limit a refusal is depends on the platform and the git version, so it is not a property
     * of the fault and no constant here can honestly record it. Five of the declared values turned
     * out to disagree with a real git before the claim was dropped; the sixth was not going to be
     * the last. What a refusal says now is only what this code observed.
     */
    public enum Fault
    {
        /**
         * The file is there and cannot be read - denied, a dangling symbolic link, a loop. git
         * dies too ({@code fatal: failed to read .../commondir}).
         */
        UNREADABLE("it could not be read", true), //$NON-NLS-1$

        /**
         * The git directory's layout could not be examined at all - the path could not be spelled,
         * or the very look that would have said whether a {@code commondir} exists failed.
         * <p>
         * Its own member because of what the CALLER may then say. Every other fault here has
         * established that this is a linked worktree with a {@code commondir} in it; this one has
         * established neither, so a refusal built on it must not assert either. {@link #confirmed()}
         * is what carries that, and it is {@code false} only here.
         */
        LAYOUT_UNREADABLE("the git directory's layout could not be read", false), //$NON-NLS-1$

        /**
         * It is not a regular file.
         * <p>
         * The reason needs no git outcome at all, and deliberately does not reach for one: opening
         * a named pipe with no writer never returns HERE, and this runs before the call that would
         * have had a deadline. Nothing in this plug-in may make an unattended request wait without
         * a bound. (What git does with a FIFO in this position was never probed - no fixture for it
         * exists on the platform this was written on - so nothing is said about it.)
         */
        NOT_A_REGULAR_FILE("it is not a regular file", true), //$NON-NLS-1$

        /**
         * Longer than this tool reads. Measured: a file of {@code ../..} plus 70 KiB of line
         * terminators strips down to a valid pointer and git carries on past it - so this refusal
         * is not one git shares, on that platform, in that version.
         */
        TOO_LARGE("it is larger than this tool will read", true), //$NON-NLS-1$

        /**
         * Not decodable as UTF-8. git takes path bytes literally, so on a POSIX filesystem it can
         * use a name this JVM cannot spell; on Windows the same pointer was measured to kill it.
         * Either way the lenient decoding would not fail - it would silently name a DIFFERENT
         * directory, which is the reason to refuse and does not depend on what git does.
         */
        NOT_UTF_8("it is not valid UTF-8", true), //$NON-NLS-1$

        /**
         * A Windows spelling whose ROOT the two readings disagree about: {@code \shared} and
         * {@code /shared} (git roots them on the current drive, {@link File#isAbsolute} calls them
         * relative), and {@code C:foo} or a bare {@code C:} (drive-RELATIVE - git resolves those
         * against that drive's current directory, {@link File#isAbsolute} again says relative).
         * <p>
         * What the second reading then does differs, and both endings are wrong: the first two
         * resolve UNDERNEATH the git directory - a real, different directory - while the drive forms
         * produce a composite with a colon in the middle that the platform will not spell at all, so
         * they used to arrive as "this cannot be a path" about a path git uses perfectly well.
         * <p>
         * Measured on Windows: git answers {@code fatal: not a git repository} for all three - and
         * on POSIX a leading backslash is an ordinary filename character git resolves happily. The
         * same bytes, opposite outcomes, which is exactly why nothing here claims a side.
         * Reproducing "the current directory of drive C:" is not something to guess at, so the
         * pointer is refused and said to be refused.
         */
        AMBIGUOUS_WINDOWS_ROOT("Windows roots it somewhere this tool cannot reproduce", true), //$NON-NLS-1$

        /**
         * Zero bytes. Measured: git dies on this
         * ({@code fatal: failed to read .../commondir}).
         * <p>
         * NOT the same as content that strips to nothing - a lone line terminator. That one git
         * resolves to the git directory itself and carries on, so this code does too; the two were
         * conflated until a fixture that could actually show the difference was built.
         */
        EMPTY("it is empty", true), //$NON-NLS-1$

        /** What it names is not a directory. git dies too ({@code fatal: not a git repository}). */
        NOT_A_DIRECTORY("what it names is not a directory", true), //$NON-NLS-1$

        /**
         * It holds a NUL byte.
         * <p>
         * The measurement behind the split lives here, in evidence, and NOT in {@link #reason()},
         * which is operator-facing text: git treats a NUL as the end of the string, resolves the
         * {@code ../..} in front of it and carries on. Refusing anyway is this code's decision -
         * truncating a pointer at a byte would resolve a DIFFERENT directory than the file names. We refuse, because silently
         * truncating a pointer at a byte would resolve a DIFFERENT directory than the one the file
         * names - the failure this whole class exists to stop.
         * <p>
         * Split from {@link #UNSPELLABLE_PATH} because one boolean cannot honestly cover both: the
         * two arrive through the same {@code InvalidPathException} and git was measured to survive
         * this one and to die on that one.
         */
        PATH_HOLDS_NUL("it holds a NUL byte", true), //$NON-NLS-1$

        /**
         * The platform will not accept it as a path for some other reason - on Windows, a trailing
         * tab.
         */
        UNSPELLABLE_PATH("the platform cannot use it as a path", true), //$NON-NLS-1$

        /**
         * What it names is there but could not be looked at. Distinct from {@link #NOT_A_DIRECTORY}
         * because {@link File#isDirectory} answers {@code false} to both and would send an operator
         * hunting for a missing directory that is in fact right there - and distinct from
         * {@link #UNREADABLE}, which is about the POINTER rather than its target.
         * <p>
         * Nobody has run git against this one, and it no longer matters that nobody has: a refusal
         * here describes what this code could not do, and leaves what git can do to the terminal.
         */
        TARGET_UNREADABLE("what it names could not be looked at", true); //$NON-NLS-1$

        private final String reason;

        private final boolean confirmed;

        Fault(String reason, boolean confirmed)
        {
            this.reason = reason;
            this.confirmed = confirmed;
        }

        /**
         * @return the fault in words, for an operator - it describes the FILE and never quotes its
         *         content, so it is safe to put in a response or a log
         */
        public String reason()
        {
            return reason;
        }

        /**
         * Whether reaching this fault ESTABLISHED that this is a linked worktree carrying a
         * {@code commondir} file.
         * <p>
         * True for every fault about the pointer's content, because getting that far proved the
         * file was there. False for {@link #LAYOUT_UNREADABLE}, which is the failure of the look
         * itself - and a caller that says "this is a linked worktree, and its commondir is at
         * fault" on the strength of it would be asserting two things nobody checked.
         *
         * @return {@code true} when a message may speak of this worktree's {@code commondir}
         */
        public boolean confirmed()
        {
            return confirmed;
        }
    }

    /**
     * An unusable {@code commondir}, carrying WHICH way it is unusable so a caller can say so
     * without re-deriving it from a message. An {@link IOException}, so every existing handler on
     * this path catches it unchanged.
     */
    public static final class FaultException
        extends IOException
    {
        private static final long serialVersionUID = 1L;

        private final Fault fault;

        FaultException(Fault fault, Throwable cause)
        {
            // The cause is KEPT, not dropped: GitFailureLog renders a cause chain by TYPE, and
            // AccessDeniedException / FileSystemLoopException are exactly the distinctions that
            // make an EDT log entry worth reading. They carry no file content, so nothing leaks
            // by keeping them - only the MESSAGE is withheld, and this exception's own message
            // is the fault's fixed words.
            super("commondir: " + fault.reason(), cause); //$NON-NLS-1$
            this.fault = fault;
        }

        FaultException(Fault fault)
        {
            this(fault, null);
        }

        /** @return which way the pointer is unusable */
        public Fault fault()
        {
            return fault;
        }
    }

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
     * This is git's own test ({@code get_common_dir_noenv} returns 1 iff the file exists), and it
     * is deliberately NOT a comparison of paths - the two are different questions. A pointer whose
     * content strips to nothing resolves back to the git directory, so {@link #directory()} equals
     * the git directory while this still answers {@code true}: a {@code commondir} was read, and
     * that is what a caller needs to know.
     * <p>
     * What it does NOT mean, and used to say it did, is that {@code <git dir>/config} goes unread.
     * In the ordinary linked layout git ignores that file; in the strips-to-nothing case git reads
     * exactly it. Callers that care must compare {@link #directory()} with the git directory rather
     * than read that into this flag.
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
     * <li><b>Zero bytes is fatal</b>, as it is to git ({@code fatal: failed to read
     * .../commondir}). <b>Content that STRIPS to nothing is not</b>: git resolves the empty
     * remainder against the git directory, lands on the git directory itself, and reads the
     * configuration there - so this returns that directory rather than refusing.
     * <p>
     * The difference took three attempts to establish, and the middle one is worth keeping as a
     * warning. A probe appeared to show git failing on a lone terminator too - but its worktree's
     * admin directory was not repository-like, so the resolved directory was unusable for a reason
     * that had nothing to do with terminators. A fixture that cannot produce the positive outcome
     * cannot be evidence against it. With {@code objects/}, {@code refs/} and a {@code config} in
     * place, git answers 0 and prints the remote from that config.</li>
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
     * <li><b>the pointer is unusable as a pointer</b> - empty, nothing but a line terminator,
     * naming something that is not a directory, or not readable at all;</li>
     * <li><b>this code will not follow it</b> - it cannot be decoded, it is larger than this code
     * reads, it is not a regular file, or it is a Windows spelling whose root this code cannot
     * reproduce. Each buys something the alternative cannot: inspecting a DIFFERENT directory,
     * reading unbounded untrusted content, or blocking for ever on a named pipe are all worse than
     * declining.</li>
     * </ul>
     * The two groups are about the POINTER and about THIS CODE. Neither is about git: which of
     * these git survives was measured to depend on the platform (see the class javadoc), so it is
     * not a division this enumeration can make.
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
     * @throws FaultException when a {@code commondir} file is there but cannot be turned into a
     *             usable directory. The ways that can happen are {@link Fault}, which is the single
     *             list of them - deliberately not repeated here, because a copy is what drifts
     */
    public static GitCommonDirectory of(File gitDir) throws FaultException
    {
        if (gitDir == null)
        {
            return new GitCommonDirectory(null, false);
        }
        Path commonDirFile;
        try
        {
            commonDirFile = new File(gitDir, COMMON_DIR_FILE).toPath();
        }
        catch (InvalidPathException e)
        {
            // The LAST unchecked way out of this method. A git directory the platform cannot spell
            // as a path is not something to let escape as a RuntimeException past a contract that
            // says every failure carries a Fault - even though the one caller catches those too.
            //
            // LAYOUT_UNREADABLE, not UNSPELLABLE_PATH: nothing has been established here. We have
            // not looked at a commondir, so no message built on this may claim there is one.
            throw new FaultException(Fault.LAYOUT_UNREADABLE, e);
        }
        try
        {
            Files.readAttributes(commonDirFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        catch (NoSuchFileException e) // NOSONAR the ordinary repository: git's file_exists() is false
        {
            return new GitCommonDirectory(gitDir, false);
        }
        catch (IOException e)
        {
            // Anything OTHER than "not there" - denied, a loop, a filesystem that will not answer.
            // It is deliberately NOT the ordinary-repository branch above: that one branch is the
            // only way to report "no linked worktree", which is what stops a failure to look from
            // being mistaken for having looked.
            //
            // And it is LAYOUT_UNREADABLE rather than UNREADABLE: this IS the look that would have
            // told us whether a commondir exists, so it has established nothing to speak of.
            throw new FaultException(Fault.LAYOUT_UNREADABLE, e);
        }
        // Past the existence test, and now FOLLOWING links, because what has to be a regular file is
        // what will be OPENED. A symbolic link to one is fine - git reads through it too, measured
        // - but a FIFO is not: opening a named pipe with no writer blocks for ever HERE, and this
        // runs before the command that would have had a deadline. Nothing in this plug-in may make
        // an unattended call wait without a bound, which settles it without asking what git does.
        //
        // What this does NOT close, said plainly rather than left to be discovered: the file is
        // STATTED here and OPENED below, and Java has no non-blocking open to fuse the two. Swap a
        // regular file for a FIFO in between and the open still blocks. That closes the accidental
        // and the stale case, not a racing adversary - and an adversary who can atomically replace
        // files inside this repository's admin directory can already do far worse to the git command
        // this check is guarding.
        if (!attributesOf(commonDirFile).isRegularFile())
        {
            throw new FaultException(Fault.NOT_A_REGULAR_FILE);
        }
        String raw = readBounded(commonDirFile);
        if (raw.isEmpty())
        {
            // ZERO BYTES, which is not the same thing as "strips to nothing" - measured, git dies
            // here (fatal: failed to read .../commondir) and carries on in the other case.
            throw new FaultException(Fault.EMPTY);
        }
        String value = stripLineTerminators(raw);
        if (value.isEmpty())
        {
            // Non-empty content that strips to nothing - a lone terminator. git resolves the empty
            // remainder against the git directory and ends up at the git directory ITSELF, then
            // reads its configuration and its legacy files from there. Measured on git 2.35.1 with
            // an admin directory made repository-like: rev-parse --git-common-dir returns that
            // directory and remote -v prints the remote declared in its own config.
            //
            // Refusing here would have been the fail-open this class exists to remove, wearing a
            // refusal's clothes: the tool would decline every remote command WITHOUT ever reading
            // the configuration git would read. An earlier probe seemed to show git failing on this
            // too, but that fixture's admin directory was not repository-like, so the pointer had
            // nowhere to land - the failure was the fixture's, not the terminators'.
            return new GitCommonDirectory(gitDir, true);
        }
        // BEFORE the ambiguity test, because a pointer can be both and the NUL is the more
        // specific fact about it. Filing it under a Windows-rooting ambiguity would name a fault
        // whose repair is "fix the prefix" for a file whose actual problem is a NUL anywhere in it.
        if (value.indexOf('\0') >= 0)
        {
            throw new FaultException(Fault.PATH_HOLDS_NUL);
        }
        File named = new File(value);
        // Spelled-ness BEFORE rooting, so that WHICH fault fires does not turn on an irrelevant
        // prefix. '\shared<TAB>' is unusable for exactly the reason '../..<TAB>' is, and reporting
        // it as a rooting ambiguity would name the wrong fault and offer the wrong repair - the tab
        // is what has to be removed, not the prefix.
        try
        {
            named.toPath();
        }
        catch (InvalidPathException e)
        {
            throw new FaultException(Fault.UNSPELLABLE_PATH, e);
        }
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
            throw new FaultException(Fault.AMBIGUOUS_WINDOWS_ROOT);
        }
        File resolved = named.isAbsolute() ? named : new File(gitDir, value);
        // Not File.isDirectory(), which answers false for "it is not one" AND for "I could not
        // find out" alike - so a directory that exists and cannot be statted would be reported as
        // one that is missing, and an operator sent to look for it. Both still refuse; what changes
        // is which fault they are told about. (Found while making this list the single source - it
        // had been hiding inside NOT_A_DIRECTORY.)
        if (!resolvesToDirectory(resolved, value))
        {
            throw new FaultException(Fault.NOT_A_DIRECTORY);
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
     * @throws FaultException with {@link Fault#UNREADABLE}, {@link Fault#TOO_LARGE} or
     *             {@link Fault#NOT_UTF_8}
     */
    private static String readBounded(Path file) throws FaultException
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
        catch (IOException e)
        {
            throw new FaultException(Fault.UNREADABLE, e);
        }
        if (read > MAX_COMMON_DIR_BYTES)
        {
            throw new FaultException(Fault.TOO_LARGE);
        }
        // STRICT, not new String(bytes, UTF_8): that one replaces a malformed byte with U+FFFD
        // silently, and the result is a DIFFERENT path. git takes these bytes literally, so a
        // pointer this JVM cannot decode would send the two of us to two different directories -
        // and if the substituted one happens to exist and be clean, the check would approve a
        // repository it never read. Refusing a path we cannot decode is the only honest answer:
        // guessing at it is not "best effort", it is inspecting somewhere else.
        try
        {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(buffer, 0, read))
                .toString();
        }
        catch (CharacterCodingException e)
        {
            throw new FaultException(Fault.NOT_UTF_8, e);
        }
    }

    /**
     * Whether the resolved pointer names a directory, telling "it is not one" apart from "it could
     * not be looked at" - two faults, and {@link File#isDirectory} answers {@code false} to both.
     * <p>
     * Both still refuse, so this changes no outcome; what it changes is which fault the operator is
     * told about. Reporting a directory that exists but cannot be statted as "what it names is not
     * a directory" would send someone to look for a missing directory that is in fact right there.
     * <p>
     * The {@link File#toPath} conversion is inside the guard on purpose: a value the platform cannot
     * even spell as a path ({@code ../..} with a trailing tab, on Windows) makes it throw
     * {@link InvalidPathException} - unchecked, and it escaped {@link #of} entirely until the
     * {@link Fault} ratchet caught it. Something the operating system will not name is not a
     * directory, so that is the fault it gets.
     *
     * @param resolved where the pointer resolved to
     * @param pointer the pointer's own text, which decides WHICH unspellable-path fault applies
     * @return {@code true} when it is a directory
     * @throws FaultException when it is unreadable, or cannot be named at all
     */
    private static boolean resolvesToDirectory(File resolved, String pointer)
        throws FaultException
    {
        Path path;
        try
        {
            path = resolved.toPath();
        }
        catch (InvalidPathException e)
        {
            // Two different faults arrive here and they are NOT the same trade: git carries on past
            // a NUL (measured) and dies on a trailing tab (measured). One boolean cannot describe
            // both, so the byte itself decides.
            throw new FaultException(unspellableFault(pointer), e);
        }
        try
        {
            return Files.readAttributes(path, BasicFileAttributes.class).isDirectory();
        }
        catch (NoSuchFileException e) // NOSONAR nothing there at all: not a directory, plainly
        {
            throw new FaultException(Fault.NOT_A_DIRECTORY, e);
        }
        catch (IOException e)
        {
            // NOT Fault.UNREADABLE: that one is about the POINTER, and telling an operator their
            // commondir could not be read when the file read perfectly well would send them to the
            // wrong file. (A POSIX ENOTDIR from an intermediate component lands here too, which is
            // why this fault's words say "could not be looked at" rather than naming a cause.)
            throw new FaultException(Fault.TARGET_UNREADABLE, e);
        }
    }

    /**
     * Reads a path's attributes, FOLLOWING links, and turns any failure into
     * {@link Fault#UNREADABLE} - so every way out of {@link #of} carries a {@link Fault} and the
     * enumeration really is complete.
     *
     * @param file the path to stat
     * @return its attributes
     * @throws FaultException when they cannot be read
     */
    private static BasicFileAttributes attributesOf(Path file) throws FaultException
    {
        try
        {
            return Files.readAttributes(file, BasicFileAttributes.class);
        }
        catch (IOException e)
        {
            throw new FaultException(Fault.UNREADABLE, e);
        }
    }

    /**
     * Which fault a path the platform will not accept deserves - the one whose cause is a NUL
     * byte, or the rest.
     *
     * @param value the pointer's content
     * @return {@link Fault#PATH_HOLDS_NUL} when it holds a NUL, {@link Fault#UNSPELLABLE_PATH}
     *         otherwise
     */
    private static Fault unspellableFault(String value)
    {
        return value.indexOf('\0') >= 0 ? Fault.PATH_HOLDS_NUL : Fault.UNSPELLABLE_PATH;
    }

    /**
     * Whether {@code value} is a Windows spelling whose ROOT git and {@link File#isAbsolute}
     * disagree about - the spellings that would otherwise resolve underneath the git directory.
     * <p>
     * <b>Windows only, and that is the correction that matters.</b> The first version of this test
     * was platform-INDEPENDENT, and it was a false refusal waiting on the CI: on POSIX a leading
     * backslash is not a separator at all, it is an ORDINARY FILENAME CHARACTER, so
     * {@code \shared} is a perfectly good relative path that git resolves and uses. Refusing it
     * would have taken every {@code remote}, {@code push}, {@code fetch} and {@code pull} off a
     * repository native git is happy with - the same shape as the {@code URL:} prefix that blocked
     * a healthy legacy file before it was measured rather than assumed.
     * <p>
     * A leading {@code /} needs no handling on POSIX either: there it is absolute to BOTH readings,
     * so {@link File#isAbsolute} already said yes and this is never consulted.
     * <p>
     * The drive-RELATIVE form {@code C:foo} is here for the same reason and was arriving by
     * accident before: {@code File.isAbsolute()} calls it relative, so the pointer was joined onto
     * the git directory, and the colon in the middle of the result made {@code toPath()} throw -
     * a refusal, but one that reported the platform could not spell a path git uses perfectly well.
     * Named rather than stumbled into.
     *
     * @param value the pointer's content
     * @return {@code true} when the two readings of it would disagree
     */
    private static boolean isRooted(String value)
    {
        if (File.separatorChar != '\\' || value.isEmpty())
        {
            return false;
        }
        if (value.charAt(0) == '/' || value.charAt(0) == '\\')
        {
            return true;
        }
        // 'C:foo' and the bare 'C:' - an ASCII drive letter, a colon, and NOT a separator after
        // it. ASCII deliberately: Windows drive letters are A-Z, and Character.isLetter would accept
        // a Cyrillic one and label a plainly invalid path as an ambiguity this tool owns. The bare
        // 'C:' is included because it IS the drive's current directory to git - excluding it left it
        // falling through to whatever the platform threw, which is how 'C:foo' used to be handled.
        if (value.length() < 2 || value.charAt(1) != ':')
        {
            return false;
        }
        char drive = value.charAt(0);
        boolean asciiLetter = (drive >= 'a' && drive <= 'z') || (drive >= 'A' && drive <= 'Z');
        return asciiLetter
            && (value.length() == 2 || (value.charAt(2) != '/' && value.charAt(2) != '\\'));
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
