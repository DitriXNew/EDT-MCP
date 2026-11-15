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
 * <li>the target's PARENT exists - this tool creates only the target directory itself, so a failed
 * clone has exactly one directory of its own to take back;</li>
 * <li>the target is new, or an existing EMPTY directory - a clone never lands over files;</li>
 * <li>it is not inside, and does not contain, the folder of an EDT workspace project;</li>
 * <li>it is not inside a git work tree, judged by the same walk {@code git init} uses
 * ({@link GitRepositoryResolver#enclosingRepository}): a clone there nests one repository inside
 * another.</li>
 * </ul>
 * Paths are compared on their REAL location as well as their spelling, so a link or junction on the way
 * cannot carry a target past a rule; anything that cannot be resolved refuses (fail closed). None of it
 * is atomic with the clone that follows: a directory another program fills in between is not seen, which
 * is why the run claims the target ({@link #claim}) and creates it itself before git starts.
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
                + "exist. This tool creates only the target directory itself: create '" + parent //$NON-NLS-1$
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

    // ==================== cleanup ====================

    /**
     * Takes back what a failed clone left in {@code target}: everything in it, and the directory itself
     * when this tool created it ({@code removeTarget}). Nothing outside it is touched: links are deleted
     * as links, never followed, and a directory whose real location is not under the target's (a
     * junction) is removed as an entry without being entered. Read-only files (git writes its objects
     * read-only) are made writable first. An entry Windows still holds a handle on is retried briefly.
     *
     * @param target the clone target
     * @param removeTarget {@code true} when this tool created the target and it goes too
     * @return {@code true} when nothing of the clone is left
     */
    public static boolean cleanUp(Path target, boolean removeTarget)
    {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        {
            return true;
        }
        Path root;
        try
        {
            root = target.toRealPath();
        }
        catch (IOException | RuntimeException e)
        {
            Activator.logError(GitFailureLog.typesOnly("git clone: resolving the clone target for cleanup failed", e), //$NON-NLS-1$
                null);
            return false;
        }
        for (int attempt = 1; attempt <= CLEANUP_ATTEMPTS; attempt++)
        {
            deleteContents(target, root, removeTarget);
            if (isClean(target, removeTarget))
            {
                return true;
            }
            if (attempt < CLEANUP_ATTEMPTS && !pause())
            {
                break;
            }
        }
        return isClean(target, removeTarget);
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

    private static boolean isClean(Path target, boolean removeTarget)
    {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        {
            return true;
        }
        if (removeTarget)
        {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(target))
        {
            return !entries.iterator().hasNext();
        }
        catch (IOException | RuntimeException e) // NOSONAR an unreadable target is not a clean one
        {
            return false;
        }
    }

    private static void deleteContents(Path target, Path root, boolean removeTarget)
    {
        try
        {
            Files.walkFileTree(target, new SimpleFileVisitor<Path>()
            {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                {
                    if (dir.equals(target))
                    {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!insideRoot(dir, root))
                    {
                        // A junction (or anything else that leads out): remove the entry, not what it names.
                        deleteQuietly(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                {
                    deleteQuietly(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc)
                {
                    deleteQuietly(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                {
                    if (!dir.equals(target) || removeTarget)
                    {
                        deleteQuietly(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException | RuntimeException e)
        {
            Activator.logError(GitFailureLog.typesOnly("git clone: cleaning up a failed clone failed", e), null); //$NON-NLS-1$
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
