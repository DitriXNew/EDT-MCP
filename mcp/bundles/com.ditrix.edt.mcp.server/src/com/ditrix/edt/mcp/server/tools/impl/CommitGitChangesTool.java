/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.UserConfig;
import org.eclipse.jgit.revwalk.RevCommit;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;

/**
 * Commits a project's ON-DISK git changes: stages the requested content
 * ({@code all} tracked-modified files, and/or explicit {@code paths}), then records
 * a commit and returns its SHA - the write half of the {@code get_git_status}
 * (read) / {@code commit_git_changes} / {@code push_git_branch} git dev-loop.
 * <p>
 * <b>ON-DISK content only.</b> This tool commits what is written to disk, exactly as
 * {@code git} sees it. It does NOT flush EDT's in-memory BM model: a metadata/BSL
 * edit that has not yet been saved/exported to disk is invisible to the commit. Save
 * (or {@code resync_to_disk}) the EDT model FIRST, then commit.
 * <p>
 * The two non-exceptional failures the spec calls out are mapped to actionable errors,
 * never swallowed as a fake success:
 * <ul>
 * <li><b>Nothing staged</b> - after staging there is no index change relative to HEAD.
 * A commit is REFUSED (never an empty commit); the caller is told to pass {@code all}
 * or {@code paths}.</li>
 * <li><b>Missing identity</b> - {@code user.name}/{@code user.email} is not configured
 * for the repository (JGit would otherwise silently synthesise a machine identity). The
 * caller is told exactly which key to set.</li>
 * </ul>
 * Needs NO authentication, NO background {@link org.eclipse.core.runtime.jobs.Job}, and
 * NO workspace refresh (committing changes nothing on disk that EDT reads back), so
 * unlike {@code push_git_branch}/{@code pull_git_branch} it runs inline on the calling
 * thread. It never opens a 1C infobase connection ({@link #connectsToInfobase()} stays
 * the default {@code false}).
 */
public class CommitGitChangesTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "commit_git_changes"; //$NON-NLS-1$

    /** Input param: the commit message (required). */
    private static final String KEY_MESSAGE = "message"; //$NON-NLS-1$

    /** Input param: stage all tracked-modified (and deleted) files before committing. */
    private static final String KEY_ALL = "all"; //$NON-NLS-1$

    /** Input param: explicit repo-relative paths to stage before committing. */
    private static final String KEY_PATHS = "paths"; //$NON-NLS-1$

    /** Output key: the full 40-hex SHA-1 of the new commit. */
    private static final String KEY_COMMIT_ID = "commitId"; //$NON-NLS-1$

    /** Output key: the branch the commit landed on (or the commit SHA when HEAD is detached). */
    private static final String KEY_BRANCH = "branch"; //$NON-NLS-1$

    /** Output key: the number of index entries the commit recorded. */
    private static final String KEY_STAGED_FILES = "stagedFiles"; //$NON-NLS-1$

    /** The {@code git add .} filepattern that matches every path in the working tree. */
    private static final String ALL_FILES_PATTERN = "."; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Commit a project's ON-DISK git changes: stage all tracked-modified files (all=true) " //$NON-NLS-1$
            + "and/or explicit paths[], then record a commit and return its SHA. Commits only what is " //$NON-NLS-1$
            + "written to disk - save/resync the EDT model first. 'Nothing to commit' is an error, not a " //$NON-NLS-1$
            + "silent no-op. Full parameters and examples: call get_tool_guide('commit_git_changes')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project whose git repository to commit into (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_MESSAGE,
                "Commit message (required); must not be blank. Cyrillic is preserved.", true) //$NON-NLS-1$
            .booleanProperty(KEY_ALL,
                "Stage all TRACKED, modified or deleted files before committing (git commit -a for " //$NON-NLS-1$
                + "tracked files; new/untracked files are NOT added). Default false.") //$NON-NLS-1$
            .stringArrayProperty(KEY_PATHS,
                "Explicit repo-relative paths to stage before committing (new, modified, or deleted). " //$NON-NLS-1$
                + "Combine with all, or use instead of it. If neither all nor paths is given, only the " //$NON-NLS-1$
                + "already-staged index is committed.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the commit was recorded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_COMMIT_ID, "The full 40-hex SHA-1 of the new commit.") //$NON-NLS-1$
            .stringProperty(KEY_BRANCH, "The branch the commit landed on (the commit SHA when HEAD is detached).") //$NON-NLS-1$
            .integerProperty(KEY_STAGED_FILES, "The number of index entries the commit recorded.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_MESSAGE);
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String message = JsonUtils.extractStringArgument(params, KEY_MESSAGE);
        // requireArguments rejects null/empty; also reject a whitespace-only message (a real message
        // is required - a blank commit subject is never useful and would confuse `git log`).
        if (message.trim().isEmpty())
        {
            return ToolResult.error("message is required and must not be blank.").toJson(); //$NON-NLS-1$
        }
        boolean all = JsonUtils.extractBooleanArgument(params, KEY_ALL, false);
        List<String> paths = JsonUtils.extractArrayArgument(params, KEY_PATHS);

        GitRepositoryResolver.Resolution resolution = GitRepositoryResolver.resolve(projectName);
        if (!resolution.ok())
        {
            return resolution.errorJson();
        }

        try
        {
            return commit(resolution.repository(), message, all, paths);
        }
        catch (Exception e) // NOSONAR unattended-safety: no exception may escape the tool (CLAUDE.md #8)
        {
            Activator.logError("commit_git_changes: failed for project '" + projectName + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to commit: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        finally
        {
            resolution.closeIfOwned();
        }
    }

    /**
     * Stages the requested content and records a commit against {@code repo}, returning the success
     * envelope (with the new commit SHA) or an actionable error JSON. A pure JGit operation with NO
     * EDT/{@code ProjectContext} dependency, so it is directly unit-testable against a REAL temporary
     * git repository (mirrors the seams in {@code GetGitStatusTool}/{@code GitRepositoryResolver}).
     * Package-visible for exactly that reason.
     * <p>
     * Order of operations: (1) refuse up front when the committer identity is not configured; (2) stage
     * {@code paths} (each staged for add AND for update/delete) and/or, when {@code all}, every
     * tracked-modified file; (3) refuse when nothing ended up staged (never an empty commit); (4) commit.
     *
     * @param repo the resolved repository (never {@code null})
     * @param message the (already non-blank) commit message
     * @param all whether to stage all tracked-modified/deleted files
     * @param paths explicit paths to stage, or {@code null}/empty for none
     * @return the success or error JSON
     */
    static String commit(Repository repo, String message, boolean all, List<String> paths)
    {
        UserConfig userConfig = repo.getConfig().get(UserConfig.KEY);
        String committerName = userConfig.isCommitterNameImplicit() ? null : userConfig.getCommitterName();
        String committerEmail = userConfig.isCommitterEmailImplicit() ? null : userConfig.getCommitterEmail();
        String identityError = committerIdentityError(committerName, committerEmail);
        if (identityError != null)
        {
            return identityError;
        }

        try
        {
            Git git = Git.wrap(repo);
            stage(git, all, paths);

            int staged = countStaged(git.status().call());
            if (staged == 0)
            {
                return nothingToCommitError();
            }

            // setAllowEmpty(false) is a belt-and-suspenders backstop to the staged==0 gate above:
            // together they guarantee this tool never records an empty commit.
            RevCommit revCommit = git.commit().setMessage(message).setAllowEmpty(false).call();

            return ToolResult.success()
                .put(KEY_COMMIT_ID, revCommit.getName())
                .put(KEY_BRANCH, repo.getBranch())
                .put(KEY_STAGED_FILES, staged)
                .toJson();
        }
        catch (EmptyCommitException e)
        {
            return nothingToCommitError();
        }
        catch (GitAPIException | IOException e)
        {
            Activator.logError("commit_git_changes: commit failed", e); //$NON-NLS-1$
            return ToolResult.error("Failed to commit: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Stages the requested content into the index. {@code paths} are each staged twice - once for
     * additions/modifications and once with {@code setUpdate(true)} for modifications/deletions - so
     * an explicit path behaves like {@code git add <path>} (new, modified, and deleted alike). When
     * {@code all} is set, every tracked, modified or deleted file is staged ({@code git add -u}); new
     * untracked files are deliberately NOT swept in by {@code all}.
     */
    private static void stage(Git git, boolean all, List<String> paths) throws GitAPIException
    {
        if (paths != null)
        {
            for (String path : paths)
            {
                if (path == null || path.trim().isEmpty())
                {
                    continue;
                }
                String pattern = path.trim();
                git.add().addFilepattern(pattern).call();
                git.add().addFilepattern(pattern).setUpdate(true).call();
            }
        }
        if (all)
        {
            git.add().addFilepattern(ALL_FILES_PATTERN).setUpdate(true).call();
        }
    }

    /** @return the count of staged (index-vs-HEAD) entries: added + changed + removed. */
    private static int countStaged(Status status)
    {
        return status.getAdded().size() + status.getChanged().size() + status.getRemoved().size();
    }

    /**
     * Returns an actionable error JSON when the committer identity is missing (either
     * {@code user.name} or {@code user.email} is unset/blank), or {@code null} when both are present.
     * Package-visible so the identity contract is directly unit-testable without a repository.
     *
     * @param committerName the configured committer name, or {@code null} when unset/implicit
     * @param committerEmail the configured committer email, or {@code null} when unset/implicit
     * @return the error JSON, or {@code null} when the identity is fully configured
     */
    static String committerIdentityError(String committerName, String committerEmail)
    {
        boolean noName = committerName == null || committerName.trim().isEmpty();
        boolean noEmail = committerEmail == null || committerEmail.trim().isEmpty();
        if (!noName && !noEmail)
        {
            return null;
        }
        StringBuilder missing = new StringBuilder();
        if (noName)
        {
            missing.append("user.name"); //$NON-NLS-1$
        }
        if (noEmail)
        {
            if (missing.length() > 0)
            {
                missing.append(" and "); //$NON-NLS-1$
            }
            missing.append("user.email"); //$NON-NLS-1$
        }
        return ToolResult.error("Cannot commit: git " + missing //$NON-NLS-1$
            + " is not configured for this repository. Set it with 'git config user.name \"<name>\"' and " //$NON-NLS-1$
            + "'git config user.email \"<email>\"' (add --global to set it once for all repositories), " //$NON-NLS-1$
            + "then retry.").toJson(); //$NON-NLS-1$
    }

    /** The canonical, actionable "nothing to commit" error (shared by the gate and the JGit backstop). */
    private static String nothingToCommitError()
    {
        return ToolResult.error("Nothing to commit: no staged changes for this repository. Stage changes " //$NON-NLS-1$
            + "first - pass all=true to stage every tracked-modified file, or list explicit paths[]. " //$NON-NLS-1$
            + "Remember only ON-DISK content is committed: save or resync_to_disk the EDT model before " //$NON-NLS-1$
            + "committing. Use get_git_status to see the working-tree state.").toJson(); //$NON-NLS-1$
    }
}
