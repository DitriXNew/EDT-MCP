/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Where {@code git clone} may create a repository (#934), and the cleanup of one that did not finish.
 * <p>
 * The rules, judged on the file system before anything runs (and again after the consent prompt):
 * <ul>
 * <li>the target's PARENT exists - this tool creates nothing above it, only its staging directory beside
 * the target and, at the end, the target itself;</li>
 * <li>the target is new, or an existing EMPTY directory - a clone never lands over files;</li>
 * <li>it is not inside, and does not contain, the folder of an EDT workspace project;</li>
 * <li>it is not inside a git work tree, judged by the same walk {@code git init} uses
 * ({@link GitRepositoryResolver#enclosingRepository}): a clone there nests one repository inside
 * another.</li>
 * </ul>
 * Paths are compared on their REAL location as well as their spelling, so a link or junction on the way
 * cannot carry a target past a rule; anything that cannot be resolved refuses (fail closed). None of it
 * is atomic with the clone that follows: a directory another program fills in between is not seen, which
 * is why the run claims the target ({@link #claim}) and clones into a staging directory of its own
 * ({@link Staging}), moving the result into the target only once git has succeeded.
 */
public final class GitCloneTarget
{
    /** How many times a cleanup retries an entry Windows still holds a handle on. */
    private static final int CLEANUP_ATTEMPTS = 10;

    /** Pause between those attempts. */
    private static final long CLEANUP_PAUSE_MILLIS = 200;

    /** The clone targets of this server that are being set up, run or cleaned up right now. */
    private static final List<Claim> CLAIMS = new ArrayList<>();

    private GitCloneTarget()
    {
        // Utility class
    }

    /** A workspace project and the folder it lives in. */
    public static final class ProjectFolder
    {
        private final String name;
        private final Path location;

        /**
         * @param name the project's name
         * @param location its folder on disk
         */
        public ProjectFolder(String name, Path location)
        {
            this.name = name;
            this.location = location;
        }

        /** @return the project's name */
        public String name()
        {
            return name;
        }

        /** @return its folder */
        public Path location()
        {
            return location;
        }
    }

    /** The verdict of {@link #check}: a target to clone into, or the refusal. */
    public static final class Checked
    {
        private final Path target;
        private final Path real;
        private final boolean existed;
        private final String refusal;

        private Checked(Path target, Path real, boolean existed, String refusal)
        {
            this.target = target;
            this.real = real;
            this.existed = existed;
            this.refusal = refusal;
        }

        private static Checked refused(String refusal)
        {
            return new Checked(null, null, false, refusal);
        }

        /** @return {@code true} when the clone may go there */
        public boolean ok()
        {
            return refusal == null;
        }

        /** @return the refusal text, or {@code null} */
        public String refusal()
        {
            return refusal;
        }

        /** @return the target as it was spelled (absolute) */
        public Path target()
        {
            return target;
        }

        /** @return the target's real location: what git is given and what the consent prompt names */
        public Path real()
        {
            return real;
        }

        /** @return {@code true} when the target already existed (empty); {@code false} when it is new */
        public boolean existed()
        {
            return existed;
        }
    }

    /**
     * Every workspace project's folder, open or closed - a closed project's files are still its own.
     *
     * @return the folders (a project without a location on disk is skipped)
     */
    public static List<ProjectFolder> workspaceProjectFolders()
    {
        List<ProjectFolder> folders = new ArrayList<>();
        for (IProject project : ProjectContext.allProjects())
        {
            IPath location = project.getLocation();
            if (location != null)
            {
                folders.add(new ProjectFolder(project.getName(), location.toFile().toPath()));
            }
        }
        return folders;
    }

    /**
     * Judges {@code target} against the rules in the class comment.
     *
     * @param target the absolute clone target the command named
     * @param projects the workspace project folders it must stay clear of
     * @return the target to use, or the refusal
     */
    public static Checked check(Path target, List<ProjectFolder> projects)
    {
        Path spelled = target.toAbsolutePath();
        Path parent = spelled.getParent();
        if (parent == null || spelled.getFileName() == null)
        {
            return Checked.refused("The clone target '" + spelled + "' is a filesystem root. Name a new " //$NON-NLS-1$ //$NON-NLS-2$
                + "directory under it instead."); //$NON-NLS-1$
        }
        if (!Files.isDirectory(parent))
        {
            return Checked.refused("The parent directory of the clone target, '" + parent + "', does not " //$NON-NLS-1$ //$NON-NLS-2$
                + "exist. This tool creates nothing above the target: create '" + parent //$NON-NLS-1$
                + "' first, or name a target whose parent exists."); //$NON-NLS-1$
        }
        boolean existed = Files.exists(spelled, LinkOption.NOFOLLOW_LINKS);
        if (existed)
        {
            String occupied = occupiedRefusal(spelled);
            if (occupied != null)
            {
                return Checked.refused(occupied);
            }
        }
        Path real;
        try
        {
            real = existed ? spelled.toRealPath() : parent.toRealPath().resolve(spelled.getFileName().toString());
        }
        catch (IOException | RuntimeException e)
        {
            Activator.logError(GitFailureLog.typesOnly(
                "git clone: resolving the real path failed for the clone target", e), null); //$NON-NLS-1$
            return Checked.refused("The real location of the clone target '" + spelled + "' could not be " //$NON-NLS-1$ //$NON-NLS-2$
                + "resolved, so this tool cannot tell where the clone would land. The clone is refused " //$NON-NLS-1$
                + "rather than run blind: check the path (and any link or junction on it) in a terminal."); //$NON-NLS-1$
        }
        String projectRefusal = projectRefusal(spelled, real, projects);
        if (projectRefusal != null)
        {
            return Checked.refused(projectRefusal);
        }
        File start = existed ? spelled.toFile() : parent.toFile();
        GitRepositoryResolver.Enclosing enclosing =
            GitRepositoryResolver.enclosingRepository(start, "git clone", "the clone target"); //$NON-NLS-1$ //$NON-NLS-2$
        switch (enclosing.kind())
        {
            case REAL_PATH_UNRESOLVED:
                return Checked.refused("The real location of the clone target '" + spelled + "' could not " //$NON-NLS-1$ //$NON-NLS-2$
                    + "be resolved, so this tool cannot tell whether it lies inside a git repository. The " //$NON-NLS-1$
                    + "clone is refused rather than run blind: check the path in a terminal."); //$NON-NLS-1$
            case UNREADABLE:
                return Checked.refused("A git repository was found above the clone target '" + spelled //$NON-NLS-1$
                    + "', but it could not be opened, so this tool cannot tell whether the target lies " //$NON-NLS-1$
                    + "inside it. The clone is refused rather than run blind: check that repository and " //$NON-NLS-1$
                    + "the git configuration in a terminal."); //$NON-NLS-1$
            case ENTRY:
            case FOUND:
                return Checked.refused("The clone target '" + spelled + "' lies inside the git repository at '" //$NON-NLS-1$ //$NON-NLS-2$
                    + enclosing.root() + "'. A clone there would create a repository nested inside it (the " //$NON-NLS-1$
                    + "outer one would see it as untracked files), so it is refused. Name a directory " //$NON-NLS-1$
                    + "outside every git work tree."); //$NON-NLS-1$
            default:
                return new Checked(spelled, real, existed, null);
        }
    }

    /** Why an existing {@code target} cannot take a clone, or {@code null} when it is an empty directory. */
    private static String occupiedRefusal(Path target)
    {
        if (Files.isSymbolicLink(target) && !Files.exists(target))
        {
            return "The clone target '" + target + "' is a link to something that does not exist. Name a " //$NON-NLS-1$ //$NON-NLS-2$
                + "new directory, or an existing empty one."; //$NON-NLS-1$
        }
        if (!Files.isDirectory(target))
        {
            return "The clone target '" + target + "' exists and is not a directory. 'git clone' needs a new " //$NON-NLS-1$ //$NON-NLS-2$
                + "directory or an existing empty one: name another path."; //$NON-NLS-1$
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(target))
        {
            if (entries.iterator().hasNext())
            {
                return "The clone target '" + target + "' exists and is not empty. This tool never clones " //$NON-NLS-1$ //$NON-NLS-2$
                    + "over existing files: name a new directory, or an existing empty one."; //$NON-NLS-1$
            }
            return null;
        }
        catch (IOException | RuntimeException e)
        {
            Activator.logError(GitFailureLog.typesOnly(
                "git clone: listing the existing clone target failed", e), null); //$NON-NLS-1$
            return "The existing clone target '" + target + "' could not be read, so this tool cannot tell " //$NON-NLS-1$ //$NON-NLS-2$
                + "whether it is empty. The clone is refused: check the directory in a terminal."; //$NON-NLS-1$
        }
    }

    /** Why {@code target} collides with a workspace project's folder, or {@code null}. */
    private static String projectRefusal(Path spelled, Path real, List<ProjectFolder> projects)
    {
        for (ProjectFolder project : projects)
        {
            Path lexical = project.location().toAbsolutePath().normalize();
            Path physical = bestEffortRealPath(lexical);
            for (Path folder : List.of(physical, lexical))
            {
                if (real.startsWith(folder) || spelled.startsWith(folder))
                {
                    return "The clone target '" + spelled + "' lies inside the folder of EDT project '" //$NON-NLS-1$ //$NON-NLS-2$
                        + project.name() + "' (" + lexical + "). A clone there would put a second " //$NON-NLS-1$ //$NON-NLS-2$
                        + "repository among the project's own files, so it is refused. Name a directory " //$NON-NLS-1$
                        + "outside every workspace project folder."; //$NON-NLS-1$
                }
                if (folder.startsWith(real) || folder.startsWith(spelled))
                {
                    return "The folder of EDT project '" + project.name() + "' (" + lexical //$NON-NLS-1$ //$NON-NLS-2$
                        + ") lies inside the clone target '" + spelled + "', so the clone is refused. " //$NON-NLS-1$ //$NON-NLS-2$
                        + "Name a directory that holds no workspace project."; //$NON-NLS-1$
                }
            }
        }
        return null;
    }

    /** The real path of what still exists of {@code path}, with the rest appended as spelled. */
    private static Path bestEffortRealPath(Path path)
    {
        for (Path existing = path; existing != null; existing = existing.getParent())
        {
            try
            {
                Path physical = existing.toRealPath();
                Path missing = existing.relativize(path);
                return missing.toString().isEmpty() ? physical : physical.resolve(missing);
            }
            catch (IOException | RuntimeException e) // NOSONAR walk further up: this ancestor is gone too
            {
                continue;
            }
        }
        return path;
    }

    // ==================== the claim on a target ====================

    /**
     * A clone target this server is setting up, running or cleaning up. Two clones into one directory
     * would race each other's cleanup - the second could remove what the first created - so a target
     * (or one that contains it, or lies inside it) is held by one clone at a time.
     */
    public static final class Claim implements AutoCloseable
    {
        private final Path real;
        private volatile BooleanSupplier alive;
        private boolean released;

        private Claim(Path real)
        {
            this.real = real;
        }

        /**
         * Ties the claim to the job that runs the clone. Until it is tied the claim is held
         * unconditionally (its owner is still setting the clone up on the calling thread); afterwards a
         * claim whose job can no longer run - a job cancelled before it started never reaches its own
         * release - is given up by the next clone that meets it.
         *
         * @param stillRunning whether the job still owns the clone
         */
        public void bindTo(BooleanSupplier stillRunning)
        {
            alive = stillRunning;
        }

        boolean live()
        {
            BooleanSupplier check = alive;
            return check == null || check.getAsBoolean();
        }

        /** Releases the claim; idempotent. */
        @Override
        public void close()
        {
            synchronized (CLAIMS)
            {
                if (!released)
                {
                    released = true;
                    CLAIMS.remove(this);
                }
            }
        }
    }

    /**
     * Claims {@code real} for one clone.
     *
     * @param real the target's real location
     * @return the claim, or {@code null} when another clone of this server holds the target, a
     *         directory inside it, or one that contains it
     */
    public static Claim claim(Path real)
    {
        synchronized (CLAIMS)
        {
            for (Iterator<Claim> it = CLAIMS.iterator(); it.hasNext();)
            {
                Claim held = it.next();
                if (!held.real.startsWith(real) && !real.startsWith(held.real))
                {
                    continue;
                }
                if (held.live())
                {
                    return null;
                }
                held.released = true;
                it.remove();
            }
            Claim claim = new Claim(real);
            CLAIMS.add(claim);
            return claim;
        }
    }

    // ==================== the staging directory ====================

    /**
     * The directory a clone is written into before it reaches its target (#934, review round 1).
     * <p>
     * git clones into {@code <container>/repo}, where the container is a fresh, uniquely named sibling of
     * the target that THIS run creates - never into the target itself. Everything a failed, cancelled or
     * timed-out clone left behind is then inside a directory that is provably this run's own, so taking
     * it back never touches the target - in particular not an existing empty target into which another
     * program may have written meanwhile. Only a clone that succeeded is moved into place
     * ({@link #install}).
     * <p>
     * The container's identity is fixed when it is created: a marker file holding a random token, and -
     * where the platform has one (POSIX) - its file key, both read WITHOUT following links.
     * {@link #remove} and {@link #install} act only on a container that is still a plain directory (not
     * a link, not a junction or other reparse point) carrying that marker: anything swapped in for it is
     * left alone, never followed. A creation time would not do: NTFS "tunnelling" hands a directory
     * re-created under the same name the old one's creation time.
     * <p>
     * Declared residual: what another program writes INTO the container while the clone runs is treated
     * as the clone's own; and the identity is checked, then the walk opens the directory - an exchange in
     * between is not seen (no portable open-by-handle walk exists in {@code java.nio}).
     */
    public static final class Staging
    {
        /** The marker file in the container, holding {@link #token}. */
        static final String MARKER = ".edt-mcp-clone"; //$NON-NLS-1$

        private final Path container;
        private final Object fileKey;
        private final String token;

        private Staging(Path container, Object fileKey, String token)
        {
            this.container = container;
            this.fileKey = fileKey;
            this.token = token;
        }

        /** @return the container directory this run created (a real path) */
        public Path path()
        {
            return container;
        }

        /** @return where git writes the clone: {@code <container>/repo} */
        public Path repository()
        {
            return container.resolve("repo"); //$NON-NLS-1$
        }

        /**
         * Whether the container is still the directory this run created: a plain directory (neither a
         * link nor a junction) with the recorded file key, holding the marker with this run's token.
         */
        boolean unchanged()
        {
            try
            {
                BasicFileAttributes now = Files.readAttributes(container, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
                if (!isPlainDirectory(now) || !java.util.Objects.equals(fileKey, now.fileKey()))
                {
                    return false;
                }
                String held = readMarker(container.resolve(MARKER));
                return token.equals(held);
            }
            catch (IOException | RuntimeException e) // NOSONAR gone or unreadable: not provably ours
            {
                return false;
            }
        }

        /** Test seam: runs before every removal attempt after the first. */
        volatile Runnable beforeRetry = () -> { };

        /**
         * Removes the container and everything in it. Ownership is proved again before EVERY attempt
         * ({@link #unchanged}), and the marker - the proof - is deleted LAST, only once the container
         * holds nothing else, so a retry after a partial attempt can still prove it. The container itself
         * then goes with a non-recursive delete, which cannot remove a directory that has content, so even
         * a directory swapped in at that last moment loses nothing. Anything that leads out of it (a link,
         * a junction) is removed as an entry and never entered. Read-only files are made writable first;
         * an entry Windows still holds a handle on is retried briefly.
         *
         * @return {@code true} when it is gone; {@code false} when something is left, or as soon as
         *         ownership can no longer be proved
         */
        public boolean remove()
        {
            for (int attempt = 1; attempt <= CLEANUP_ATTEMPTS; attempt++)
            {
                if (!Files.exists(container, LinkOption.NOFOLLOW_LINKS))
                {
                    return true;
                }
                if (attempt > 1)
                {
                    beforeRetry.run();
                }
                if (!unchanged())
                {
                    Activator.logWarning("git clone: the staging directory " + container //$NON-NLS-1$
                        + " can no longer be proved to be the one this clone created; it is left in place."); //$NON-NLS-1$
                    return false;
                }
                deleteContentsExceptMarker(container);
                if (removeEmptyContainer(this))
                {
                    return true;
                }
                if (attempt < CLEANUP_ATTEMPTS && !pause())
                {
                    break;
                }
            }
            return !Files.exists(container, LinkOption.NOFOLLOW_LINKS);
        }
    }

    /** The prefix of a staging directory's name - hidden on POSIX, and recognisable as this tool's. */
    static final String STAGING_PREFIX = ".edt-mcp-clone-"; //$NON-NLS-1$

    /**
     * Creates the staging container for a clone into {@code checked}: a new, uniquely named directory
     * beside the target, created atomically (it fails if anything is there already), with its marker.
     *
     * @param checked the checked target
     * @return the staging directory
     * @throws IOException when it cannot be created or its identity cannot be recorded
     */
    public static Staging createStaging(Checked checked) throws IOException
    {
        return createStaging(checked, GitCloneTarget::writeMarker);
    }

    /** How the marker is written; a test passes one that fails. */
    @FunctionalInterface
    interface MarkerWriter
    {
        void write(Path marker, String token) throws IOException;
    }

    /**
     * {@link #createStaging(Checked)} with the marker write as a seam. When anything after the
     * directory's creation fails, the half-made container is taken back before the failure propagates:
     * the caller has no handle on it to do that.
     */
    static Staging createStaging(Checked checked, MarkerWriter writer) throws IOException
    {
        Path parent = checked.real().getParent();
        String name = STAGING_PREFIX + java.util.UUID.randomUUID().toString().substring(0, 8) + "-" //$NON-NLS-1$
            + checked.real().getFileName();
        Path created = Files.createDirectory(parent.resolve(name));
        String token = java.util.UUID.randomUUID().toString();
        try
        {
            writer.write(created.resolve(Staging.MARKER), token);
            BasicFileAttributes attrs = Files.readAttributes(created, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new Staging(created, attrs.fileKey(), token);
        }
        catch (IOException | RuntimeException e)
        {
            // Only what this call just made: the marker, if it was written, then the directory, with a
            // non-recursive delete that fails rather than remove anything another program put there.
            try
            {
                Files.deleteIfExists(created.resolve(Staging.MARKER));
                Files.deleteIfExists(created);
            }
            catch (IOException | RuntimeException again) // NOSONAR the original failure is what propagates
            {
                Activator.logWarning("git clone: the half-made staging directory " + created //$NON-NLS-1$
                    + " could not be removed"); //$NON-NLS-1$
            }
            throw e;
        }
    }

    private static void writeMarker(Path marker, String token) throws IOException
    {
        Files.write(marker, token.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
    }

    /** The longest marker read: a token is 36 ASCII characters. */
    private static final int MAX_MARKER_BYTES = 256;

    /**
     * The marker's content, read without following a link: the file is opened with
     * {@code NOFOLLOW_LINKS} (a link in its place fails to open) and read up to
     * {@link #MAX_MARKER_BYTES} - a regular file is required first, so neither a link, a directory nor
     * a device is read, and a huge file is not read through.
     *
     * @return the content, or {@code null} when it is not a small regular file
     */
    static String readMarker(Path marker) throws IOException
    {
        BasicFileAttributes attrs = Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.size() > MAX_MARKER_BYTES)
        {
            return null;
        }
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(marker,
            java.util.Set.<java.nio.file.OpenOption>of(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))
        {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(MAX_MARKER_BYTES + 1);
            while (buffer.hasRemaining() && channel.read(buffer) > 0)
            {
                // read up to the bound
            }
            if (buffer.position() > MAX_MARKER_BYTES)
            {
                return null;
            }
            return new String(buffer.array(), 0, buffer.position(), java.nio.charset.StandardCharsets.US_ASCII);
        }
    }
    /**
     * Moves a successful clone from its staging directory to the target, without ever replacing
     * anything:
     * <ul>
     * <li>a NEW target - the clone ({@code <container>/repo}) is renamed to it in one step; that fails if a
     * directory with content appeared there meanwhile (an empty one is replaced on POSIX, which loses
     * nothing);</li>
     * <li>an existing EMPTY target - its own directory is kept, and the clone's top-level entries are
     * moved into it one by one, never over an entry of the same name; on a collision the entries
     * already moved go back, and the target is left as it was.</li>
     * </ul>
     * Declared residual: on POSIX a non-replacing move checks for the name and then renames, so a file
     * of the SAME name created in between is overwritten; and the target is checked to be a plain
     * directory just before the moves, not during them.
     *
     * @param staging the staging directory holding the clone
     * @param checked the checked target
     * @return {@code null} when the clone is in place, else why not (the clone is still in staging)
     */
    public static String install(Staging staging, Checked checked)
    {
        return install(staging, checked, moved -> { });
    }

    /** One entry moved into an existing target, and what it was right after the move. */
    private static final class Moved
    {
        final Path destination;
        final EntryIdentity identity;

        Moved(Path destination, EntryIdentity identity)
        {
            this.destination = destination;
            this.identity = identity;
        }
    }

    /**
     * What an entry is, read without following links: its kind, file key (where the platform has one),
     * size and modification time. Enough to tell an entry this run moved from one another program put
     * in its place.
     */
    static final class EntryIdentity
    {
        private final boolean directory;
        private final boolean regular;
        private final boolean link;
        private final boolean other;
        private final Object fileKey;
        private final long size;
        private final java.nio.file.attribute.FileTime modified;

        private EntryIdentity(BasicFileAttributes attrs)
        {
            directory = attrs.isDirectory();
            regular = attrs.isRegularFile();
            link = attrs.isSymbolicLink();
            other = attrs.isOther();
            fileKey = attrs.fileKey();
            size = attrs.size();
            modified = attrs.lastModifiedTime();
        }

        /** @return the identity, or {@code null} when it cannot be read (then nothing can match it) */
        static EntryIdentity ofOrNull(Path path)
        {
            try
            {
                return of(path);
            }
            catch (IOException | RuntimeException e) // NOSONAR unreadable: not provable later either
            {
                return null;
            }
        }

        static EntryIdentity of(Path path) throws IOException
        {
            return new EntryIdentity(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        }

        /** @return {@code true} when {@code path} is still this entry */
        boolean matches(Path path)
        {
            try
            {
                EntryIdentity now = of(path);
                return directory == now.directory && regular == now.regular && link == now.link && other == now.other
                    && java.util.Objects.equals(fileKey, now.fileKey) && size == now.size && modified.equals(now.modified);
            }
            catch (IOException | RuntimeException e) // NOSONAR gone or unreadable: not provably the moved entry
            {
                return false;
            }
        }
    }

    /** {@link #install(Staging, Checked)} with a seam run after each entry moved into an existing target. */
    static String install(Staging staging, Checked checked, java.util.function.Consumer<Path> afterMove)
    {
        Path target = checked.real();
        if (!staging.unchanged())
        {
            return "The staging directory '" + staging.path() + "' is no longer the one this clone created, " //$NON-NLS-1$ //$NON-NLS-2$
                + "so the clone was not moved to '" + target + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!checked.existed())
        {
            try
            {
                Files.move(staging.repository(), target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                removeEmptyContainer(staging);
                return null;
            }
            catch (IOException | RuntimeException e)
            {
                return "The clone could not be moved to '" + target + "' (" + e.getClass().getSimpleName() //$NON-NLS-1$ //$NON-NLS-2$
                    + "): something was created there while it ran."; //$NON-NLS-1$
            }
        }
        try
        {
            BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!isPlainDirectory(attrs))
            {
                return "The clone target '" + target + "' is no longer a plain directory (it was replaced while " //$NON-NLS-1$ //$NON-NLS-2$
                    + "the clone ran), so the clone was not moved into it."; //$NON-NLS-1$
            }
        }
        catch (IOException | RuntimeException e)
        {
            return "The clone target '" + target + "' could not be read any more, so the clone was not moved " //$NON-NLS-1$ //$NON-NLS-2$
                + "into it."; //$NON-NLS-1$
        }
        List<Moved> moved = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(staging.repository()))
        {
            for (Path entry : entries)
            {
                Path destination = target.resolve(entry.getFileName().toString());
                Files.move(entry, destination); // no REPLACE_EXISTING: never over an entry of that name
                moved.add(new Moved(destination, EntryIdentity.ofOrNull(destination)));
                afterMove.accept(destination);
            }
        }
        catch (IOException | RuntimeException e)
        {
            List<Path> leftInTarget = new ArrayList<>();
            for (Moved back : moved)
            {
                // Only an entry that is provably still the one moved goes back; another program's
                // replacement stays where it is, named for recovery by hand.
                if (back.identity == null || !back.identity.matches(back.destination))
                {
                    leftInTarget.add(back.destination);
                    continue;
                }
                try
                {
                    Files.move(back.destination, staging.repository().resolve(back.destination.getFileName().toString()));
                }
                catch (IOException | RuntimeException again) // NOSONAR reported through the message below
                {
                    leftInTarget.add(back.destination);
                }
            }
            String why = "The clone could not be moved into '" + target + "' (" + e.getClass().getSimpleName() //$NON-NLS-1$ //$NON-NLS-2$
                + "): something was written there while it ran."; //$NON-NLS-1$
            if (leftInTarget.isEmpty())
            {
                return why + " The target was left as it was."; //$NON-NLS-1$
            }
            return why + " These entries could not be taken back, because they could not be moved or are no " //$NON-NLS-1$
                + "longer the ones this clone put there - check and remove them by hand: " //$NON-NLS-1$
                + String.join(", ", leftInTarget.stream().map(Path::toString).toArray(String[]::new)) + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            Files.delete(staging.repository());
        }
        catch (IOException | RuntimeException e) // NOSONAR an empty leftover is harmless; logged
        {
            Activator.logWarning("git clone: the emptied staging directory " + staging.repository() + " could not be removed"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        removeEmptyContainer(staging);
        return null;
    }

    /**
     * Removes the container once nothing but its marker is left in it: the marker - the proof of
     * ownership - goes last, then the directory with a NON-recursive delete, which fails on a directory
     * that has content, so a directory swapped in at that moment loses nothing.
     *
     * @return {@code true} when the container is gone
     */
    private static boolean removeEmptyContainer(Staging staging)
    {
        Path container = staging.path();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(container))
        {
            for (Path entry : entries)
            {
                if (!Staging.MARKER.equals(entry.getFileName().toString()))
                {
                    return false; // more than the marker: keep the proof for the next attempt
                }
            }
        }
        catch (IOException | RuntimeException e) // NOSONAR not readable now: keep the proof, report a leftover
        {
            return false;
        }
        try
        {
            Files.deleteIfExists(container.resolve(Staging.MARKER));
        }
        catch (AccessDeniedException e)
        {
            deleteQuietly(container.resolve(Staging.MARKER));
        }
        catch (IOException | RuntimeException e) // NOSONAR kept for the next attempt
        {
            return false;
        }
        for (int attempt = 1; attempt <= CLEANUP_ATTEMPTS; attempt++)
        {
            try
            {
                Files.deleteIfExists(container);
                return true;
            }
            catch (IOException | RuntimeException e) // NOSONAR a handle Windows has not released yet, or content
            {
                if (attempt == CLEANUP_ATTEMPTS || !pause())
                {
                    break;
                }
            }
        }
        Activator.logWarning("git clone: the staging directory " + container + " could not be removed"); //$NON-NLS-1$ //$NON-NLS-2$
        return false;
    }

    /** A real directory: not a link, not a junction or other reparse point (Java reports those as "other"). */
    private static boolean isPlainDirectory(BasicFileAttributes attrs)
    {
        return attrs.isDirectory() && !attrs.isSymbolicLink() && !attrs.isOther();
    }

    private static boolean pause()
    {
        try
        {
            Thread.sleep(CLEANUP_PAUSE_MILLIS);
            return true;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Deletes what {@code root} (a real path) holds - everything but its marker, which stays as the proof
     * of ownership until the container is otherwise empty. A directory that is not a plain directory (a
     * link, a junction) or whose real location is not under {@code root}'s is removed as an entry, never
     * entered; the root itself, whose identity the caller has checked just before, is not entered unless
     * it is a plain directory, and is not deleted here.
     */
    private static void deleteContentsExceptMarker(Path root)
    {
        Path marker = root.resolve(Staging.MARKER);
        try
        {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>()
            {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                {
                    if (dir.equals(root) && !isPlainDirectory(attrs))
                    {
                        // Swapped after the identity check: not this run's to touch at all.
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!isPlainDirectory(attrs) || !insideRoot(dir, root))
                    {
                        // Leads out (a junction, or anything swapped in): remove the entry, not what it names.
                        deleteQuietly(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                {
                    if (!file.equals(marker))
                    {
                        deleteQuietly(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc)
                {
                    if (!file.equals(marker))
                    {
                        deleteQuietly(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                {
                    if (!dir.equals(root))
                    {
                        deleteQuietly(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException | RuntimeException e)
        {
            Activator.logError(GitFailureLog.typesOnly("git clone: removing a failed clone failed", e), null); //$NON-NLS-1$
        }
    }

    private static boolean insideRoot(Path dir, Path root)
    {
        try
        {
            return dir.toRealPath().startsWith(root);
        }
        catch (IOException | RuntimeException e) // NOSONAR cannot tell where it leads: do not enter it
        {
            return false;
        }
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (AccessDeniedException e)
        {
            // git writes its object files read-only, and Windows refuses to delete a read-only file.
            try
            {
                Files.setAttribute(path, "dos:readonly", Boolean.FALSE, LinkOption.NOFOLLOW_LINKS); //$NON-NLS-1$
                Files.deleteIfExists(path);
            }
            catch (IOException | RuntimeException again) // NOSONAR retried by the caller, reported as left over
            {
                // left for the next attempt
            }
        }
        catch (IOException | RuntimeException e) // NOSONAR retried by the caller, reported as left over
        {
            // left for the next attempt (a handle Windows has not released yet)
        }
    }

    // ==================== what EDT can read ====================

    /**
     * What the repository just cloned into {@code target} is that the git integration bundled with EDT
     * (JGit 6.8) cannot fully use - read from its {@code .git/config}. A clone takes the remote's format,
     * so this is only known once it exists.
     *
     * @param target the clone target
     * @return the warnings, empty when there is nothing to say (or the configuration cannot be read)
     */
    public static List<String> formatWarnings(Path target)
    {
        List<String> warnings = new ArrayList<>();
        File config = target.resolve(Constants.DOT_GIT).resolve("config").toFile(); //$NON-NLS-1$
        if (!config.isFile())
        {
            return warnings;
        }
        try
        {
            FileBasedConfig stored = new FileBasedConfig(config, FS.DETECTED);
            stored.load();
            String objectFormat = stored.getString("extensions", null, "objectformat"); //$NON-NLS-1$ //$NON-NLS-2$
            if (objectFormat != null && !"sha1".equals(objectFormat.trim().toLowerCase(Locale.ROOT))) //$NON-NLS-1$
            {
                warnings.add("The remote is a " + objectFormat.trim() + " repository: the git integration " //$NON-NLS-1$ //$NON-NLS-2$
                    + "bundled with EDT (JGit 6.8) opens one but cannot read its objects, so EDT's Team " //$NON-NLS-1$
                    + "features will fail on it. Work with it through this tool or a terminal."); //$NON-NLS-1$
            }
            if (stored.getString("extensions", null, "partialclone") != null) //$NON-NLS-1$ //$NON-NLS-2$
            {
                warnings.add("This is a partial clone (--filter): objects it left out are fetched on demand " //$NON-NLS-1$
                    + "by git, but not by the git integration bundled with EDT (JGit 6.8), so EDT can fail " //$NON-NLS-1$
                    + "on history it has not got. Run such commands through this tool instead."); //$NON-NLS-1$
            }
        }
        catch (IOException | RuntimeException | org.eclipse.jgit.errors.ConfigInvalidException e)
        {
            Activator.logError(GitFailureLog.typesOnly("git clone: reading the cloned configuration failed", e), null); //$NON-NLS-1$
        }
        return warnings;
    }
}
