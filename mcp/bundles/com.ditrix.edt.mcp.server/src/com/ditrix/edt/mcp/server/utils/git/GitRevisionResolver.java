/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Resolves a user-written git revision - a branch, a tag, a full or abbreviated hash, {@code HEAD},
 * {@code HEAD~1}, {@code @{u}} - to the FULL 40-hex id of a COMMIT, on top of the repository
 * {@link GitRepositoryResolver} already knows how to find.
 * <p>
 * It exists because the platform's own git comparison data source does far less than its
 * {@code String revision} parameter suggests. {@code GitCompareUtils.getRevCommit} tries
 * {@code Repository.findRef(revision)} and, failing that, {@code ObjectId.fromString(revision)} -
 * so it understands a ref name and a full hash, and NOTHING else. {@code HEAD~1} is neither: it is
 * not a ref, and it is not 40 hex digits, so it reaches {@code ObjectId.fromString} and blows up
 * inside the descriptor. Handing that descriptor an already-resolved 40-hex id turns every revision
 * expression git accepts into one it accepts too, and removes the ambiguity of a short hash at the
 * same time.
 * <p>
 * Three properties are deliberate:
 * <ul>
 * <li><b>A commit, or a refusal.</b> {@code Repository.resolve} answers about OBJECTS: an annotated
 * tag resolves to the tag object, {@code HEAD^{tree}} to a tree (both measured). The result is peeled
 * with a {@link RevWalk} and anything that is not a {@link RevCommit} is refused by name, because a
 * tree id passed on as a "revision" would fail much later and much less legibly.</li>
 * <li><b>Read-only, and thread-safe.</b> Like {@link GitRepositoryResolver}, this is a pure
 * filesystem/JGit read: it opens no transaction, touches no BM model, and never moves HEAD or the
 * checked-out branch.</li>
 * <li><b>Ownership is honoured.</b> {@link #resolve(String, String)} closes the repository if and
 * only if the resolution owned it ({@link GitRepositoryResolver.Resolution#closeIfOwned()}); a
 * repository borrowed from EGit's cache is left alone.</li>
 * </ul>
 */
public final class GitRevisionResolver
{
    private GitRevisionResolver()
    {
        // Utility class
    }

    /**
     * The outcome of resolving one revision: the commit it names (plus the work tree it was resolved
     * in), or a ready {@link ToolResult} error JSON.
     */
    public static final class Revision
    {
        private final String requested;

        private final String commitId;

        private final Path workTree;

        private final String errorJson;

        private Revision(String requested, String commitId, Path workTree, String errorJson)
        {
            this.requested = requested;
            this.commitId = commitId;
            this.workTree = workTree;
            this.errorJson = errorJson;
        }

        /**
         * Package-private test-only factory, mirroring {@link GitRepositoryResolver.Resolution#forTest}:
         * it builds a {@link Revision} directly so the pure accessors are unit-testable without a live
         * EDT workspace.
         *
         * @param requested the revision string as asked for
         * @param commitId the resolved full commit id, or {@code null}
         * @param workTree the work tree, or {@code null}
         * @param errorJson the refusal, or {@code null}
         * @return the value object
         */
        static Revision forTest(String requested, String commitId, Path workTree, String errorJson)
        {
            return new Revision(requested, commitId, workTree, errorJson);
        }

        /** @return {@code true} when the revision resolved to a commit (no error). */
        public boolean ok()
        {
            return errorJson == null;
        }

        /**
         * @return the revision exactly as the caller wrote it (trimmed), so a report can say what was
         *         asked for next to what it turned out to be. Never {@code null} once a caller-supplied
         *         string reached the resolver.
         */
        public String requested()
        {
            return requested;
        }

        /**
         * @return the resolved commit's full 40-hex id, or {@code null} on error. This is the value to
         *         hand to the platform's git data source - see the class javadoc for why the caller's
         *         own string is not.
         */
        public String commitId()
        {
            return commitId;
        }

        /**
         * @return the repository's work tree, or {@code null} on error. Captured while the repository
         *         was still open, because the caller needs it after {@link #resolve(String, String)}
         *         has closed an owned repository - and re-opening the repository just to ask again
         *         would be a second, independent answer to a question already answered.
         */
        public Path workTree()
        {
            return workTree;
        }

        /** @return the error JSON to return from {@code execute}, or {@code null} on success. */
        public String errorJson()
        {
            return errorJson;
        }
    }

    /**
     * Resolves {@code revision} inside the git repository backing {@code projectName}.
     *
     * @param projectName the MCP {@code projectName} argument
     * @param revision the revision expression to resolve
     * @return the resolved commit, or an actionable error
     */
    public static Revision resolve(String projectName, String revision)
    {
        GitRepositoryResolver.Resolution resolution = GitRepositoryResolver.resolve(projectName);
        if (!resolution.ok())
        {
            // The repository resolution already produced a ToolResult error JSON: pass it through as
            // JSON. Wrapping it in ToolResult.error a second time would bury a structured refusal
            // inside the message field of another one.
            return failedJson(revision == null ? "" : revision.trim(), resolution.errorJson()); //$NON-NLS-1$
        }
        try
        {
            return resolveIn(resolution.repository(), revision);
        }
        finally
        {
            resolution.closeIfOwned();
        }
    }

    /**
     * The resolution step WITHOUT the project lookup - the mechanics behind {@link #resolve}, split
     * out so it is directly unit-testable against a REAL temporary repository, exactly as
     * {@link GitRepositoryResolver#discoverFromDirectory} is. No mocking of JGit anywhere.
     * <p>
     * Note what {@code Repository.resolve} does and does not throw, all of it measured against JGit
     * 6.8: an unknown revision, a syntactically odd one and a revision whose parent is missing from a
     * SHALLOW clone all return {@code null} rather than throwing - which is why the "unknown" refusal
     * names the shallow case. Only a genuinely ambiguous abbreviation throws
     * {@link AmbiguousObjectException}.
     *
     * @param repository the opened repository; must not be {@code null}
     * @param revision the revision expression
     * @return the resolved commit, or an actionable error
     */
    static Revision resolveIn(Repository repository, String revision)
    {
        String wanted = revision == null ? "" : revision.trim(); //$NON-NLS-1$
        if (wanted.isEmpty())
        {
            return failed(wanted, "No git revision was given. Name a branch, a tag, a commit hash or an " //$NON-NLS-1$
                + "expression such as 'HEAD' or 'HEAD~1'."); //$NON-NLS-1$
        }

        Path workTree = workTreeOf(repository);
        if (workTree == null)
        {
            return failed(wanted, "The git repository has no work tree (it is a bare repository), so a " //$NON-NLS-1$
                + "revision cannot be compared against a project inside it. Point the tool at a project " //$NON-NLS-1$
                + "in a normal (non-bare) clone."); //$NON-NLS-1$
        }

        try
        {
            ObjectId resolved = repository.resolve(wanted);
            if (resolved == null)
            {
                return failed(wanted, unknownRevisionMessage(wanted, workTree));
            }
            try (RevWalk walk = new RevWalk(repository))
            {
                RevObject peeled = walk.peel(walk.parseAny(resolved));
                if (!(peeled instanceof RevCommit))
                {
                    return failed(wanted, "Git revision '" + wanted + "' names a " //$NON-NLS-1$ //$NON-NLS-2$
                        + Constants.typeString(peeled.getType())
                        + ", not a commit. A comparison side has to be a commit: use a branch, a tag or a " //$NON-NLS-1$
                        + "commit hash."); //$NON-NLS-1$
                }
                return new Revision(wanted, peeled.name(), workTree, null);
            }
        }
        catch (AmbiguousObjectException e)
        {
            return failed(wanted, ambiguousRevisionMessage(wanted, e.getCandidates()));
        }
        catch (MissingObjectException e)
        {
            // Named, not folded into the generic IO branch: "the id is known but the object is not in
            // this clone" is what a SHALLOW or partial clone looks like, and telling that operator to
            // go repair their git configuration would send them after the wrong thing entirely.
            return failed(wanted, "Git revision '" + wanted + "' names an object this clone does not " //$NON-NLS-1$ //$NON-NLS-2$
                + "contain. That is what a shallow or partial clone looks like: the id is known, the " //$NON-NLS-1$
                + "object itself was never fetched. Deepen the clone ('git fetch --unshallow') or pick a " //$NON-NLS-1$
                + "revision it does contain."); //$NON-NLS-1$
        }
        catch (RevisionSyntaxException e)
        {
            return failed(wanted, "Git revision '" + wanted + "' is not a revision expression git " //$NON-NLS-1$ //$NON-NLS-2$
                + "understands. Use a branch name, a tag, a commit hash, or an expression such as " //$NON-NLS-1$
                + "'HEAD' or 'HEAD~1'."); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            // Sanitized for the reason spelled out on GitRepositoryResolver.discoverFromLocation:
            // reading refs and objects can surface a configuration-parse failure, whose message quotes
            // the offending line - credentials included. Only the exception types are logged.
            Activator.logError(GitFailureLog.typesOnly(
                "git revision resolution failed for '" + wanted + "'", e), null); //$NON-NLS-1$ //$NON-NLS-2$
            return failed(wanted, "Git revision '" + wanted + "' could not be read from the repository. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Check the repository in a terminal ('git rev-parse " + wanted + "'); this plug-in logs " //$NON-NLS-1$ //$NON-NLS-2$
                + "only the failure's exception types, because the message itself can quote the git " //$NON-NLS-1$
                + "configuration, credentials included."); //$NON-NLS-1$
        }
    }

    /**
     * The refusal for a revision nothing in the repository answers to. It names the shallow clone
     * explicitly because that case is neither hypothetical nor visibly different: a depth-1 checkout -
     * the default of {@code actions/checkout}, and what this project's own CI does - has no parent
     * commit, and {@code resolve("HEAD~1")} there simply returns {@code null}, exactly like a typo.
     *
     * @param wanted the revision as asked for
     * @param workTree the repository's work tree, named so the reader knows WHICH clone was searched
     * @return the actionable message
     */
    private static String unknownRevisionMessage(String wanted, Path workTree)
    {
        return "Unknown git revision '" + wanted + "' in repository '" + workTree //$NON-NLS-1$ //$NON-NLS-2$
            + "'. No branch, tag or commit there answers to it. Call list_git_branches to see the " //$NON-NLS-1$
            + "branches this clone has; if the revision is an ancestor expression such as 'HEAD~1', the " //$NON-NLS-1$
            + "clone may be SHALLOW and simply not contain the ancestor - a shallow clone reports a " //$NON-NLS-1$
            + "missing parent the same way it reports a misspelling."; //$NON-NLS-1$
    }

    /**
     * The refusal for an abbreviation several objects answer to. The candidate ids are quoted in full:
     * they are the actionable content (the caller picks one and lengthens the prefix), and an object
     * id carries nothing that has to be withheld.
     *
     * @param wanted the ambiguous revision as asked for
     * @param candidates the ids JGit found, as reported by {@link AmbiguousObjectException}
     * @return the actionable message
     */
    private static String ambiguousRevisionMessage(String wanted, Collection<ObjectId> candidates)
    {
        StringBuilder names = new StringBuilder();
        if (candidates != null)
        {
            for (ObjectId candidate : candidates)
            {
                if (names.length() > 0)
                {
                    names.append(", "); //$NON-NLS-1$
                }
                names.append(candidate.name());
            }
        }
        String tail = names.length() == 0 ? "" //$NON-NLS-1$
            : " It matches: " + names + '.'; //$NON-NLS-1$
        return "Git revision '" + wanted + "' is ambiguous - more than one object starts with that " //$NON-NLS-1$ //$NON-NLS-2$
            + "prefix." + tail + " Use a longer prefix or the full 40-character hash."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param repository the opened repository
     * @return its work tree, or {@code null} when the repository is bare and therefore has none
     */
    private static Path workTreeOf(Repository repository)
    {
        try
        {
            return repository.getWorkTree().toPath();
        }
        catch (NoWorkTreeException e)
        {
            return null;
        }
    }

    /**
     * @param requested the revision as asked for
     * @param errorJson a ready error JSON
     * @return the refusing outcome
     */
    private static Revision failedJson(String requested, String errorJson)
    {
        return new Revision(requested, null, null, errorJson);
    }

    /**
     * @param requested the revision as asked for
     * @param message the actionable refusal text
     * @return the refusing outcome
     */
    private static Revision failed(String requested, String message)
    {
        return failedJson(requested, ToolResult.error(message).toJson());
    }
}
