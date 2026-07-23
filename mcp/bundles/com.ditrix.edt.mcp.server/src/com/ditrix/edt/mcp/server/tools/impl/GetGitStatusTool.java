/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;

/**
 * Reports a project's git working-tree status: the current branch (detached HEAD
 * flagged), whether the tree is clean, and the porcelain change sets - the
 * read-only counterpart of the {@code commit_git_changes}/{@code push_git_branch}
 * write tools.
 * <p>
 * The change sets mirror JGit's {@link Status} one-to-one:
 * {@code added}/{@code changed}/{@code modified}/{@code missing}/{@code removed}/
 * {@code untracked}/{@code conflicting} (see
 * <a href="https://git-scm.com/docs/git-status">git-status</a> porcelain semantics).
 * A path may legitimately appear under more than one state (e.g. a file staged
 * ({@code added}) and then edited again ({@code modified})), exactly as
 * {@code git status} reports both.
 * <p>
 * Read-only ({@code get_} prefix - the central classifier derives
 * {@code readOnlyHint=true}): no authentication, no background {@link
 * org.eclipse.core.runtime.jobs.Job}, no workspace refresh, and no BM model
 * transaction - a pure JGit read. The repository is located through the shared
 * {@link GitRepositoryResolver} and, when the resolver owns it (the git-dir
 * discovery fallback), closed in a {@code finally} via
 * {@link GitRepositoryResolver.Resolution#closeIfOwned()}.
 */
public class GetGitStatusTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "get_git_status"; //$NON-NLS-1$

    private static final String REFS_HEADS = "refs/heads/"; //$NON-NLS-1$

    // Porcelain state labels, in a stable report order. Each maps one-to-one onto a
    // JGit Status change set and is the value rendered in the report's "State" column.
    static final String STATE_ADDED = "added"; //$NON-NLS-1$

    static final String STATE_CHANGED = "changed"; //$NON-NLS-1$

    static final String STATE_MODIFIED = "modified"; //$NON-NLS-1$

    static final String STATE_MISSING = "missing"; //$NON-NLS-1$

    static final String STATE_REMOVED = "removed"; //$NON-NLS-1$

    static final String STATE_UNTRACKED = "untracked"; //$NON-NLS-1$

    static final String STATE_CONFLICTING = "conflicting"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Report a project's git working-tree status: the current branch (detached HEAD flagged), " //$NON-NLS-1$
            + "whether the tree is clean, and the porcelain change sets (added/changed/modified/missing/" //$NON-NLS-1$
            + "removed/untracked/conflicting). Read-only; precedes commit_git_changes. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('get_git_status')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project to report git status for (required).", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        if (projectName != null && !projectName.isEmpty())
        {
            return "git-status-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "git-status.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);

        GitRepositoryResolver.Resolution resolution = GitRepositoryResolver.resolve(projectName);
        if (!resolution.ok())
        {
            return resolution.errorJson();
        }

        try
        {
            return render(projectName, resolution.repository());
        }
        catch (Exception e) // NOSONAR unattended-safety: no exception may escape the tool (CLAUDE.md #8) - JGit's
                            // StatusCommand can throw unchecked JGitInternalException / NoWorkTreeException too
        {
            Activator.logError("get_git_status: failed for project '" + projectName + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to read git status for '" + projectName //$NON-NLS-1$
                + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        finally
        {
            resolution.closeIfOwned();
        }
    }

    private String render(String projectName, Repository repo) throws IOException, GitAPIException
    {
        // The short name is the 40-hex commit SHA when HEAD is detached; the full ref is
        // refs/heads/... on a named branch (mirrors ListGitBranchesTool's detection).
        String currentShort = repo.getBranch();
        String fullBranch = repo.getFullBranch();
        boolean detached = fullBranch == null || !fullBranch.startsWith(REFS_HEADS);

        Status status = Git.wrap(repo).status().call();
        return renderStatus(projectName, currentShort, detached, categorize(status));
    }

    /**
     * Snapshots JGit's {@link Status} into a stable-order map of state-label -&gt; paths,
     * so {@link #renderStatus} (the pure renderer) has no {@link Status} dependency and is
     * directly unit-testable.
     */
    private static Map<String, Set<String>> categorize(Status status)
    {
        Map<String, Set<String>> sets = new LinkedHashMap<>();
        sets.put(STATE_ADDED, status.getAdded());
        sets.put(STATE_CHANGED, status.getChanged());
        sets.put(STATE_MODIFIED, status.getModified());
        sets.put(STATE_MISSING, status.getMissing());
        sets.put(STATE_REMOVED, status.getRemoved());
        sets.put(STATE_UNTRACKED, status.getUntracked());
        sets.put(STATE_CONFLICTING, status.getConflicting());
        return sets;
    }

    /**
     * Pure Markdown renderer for a git-status snapshot, with NO JGit/EDT dependency so it is
     * directly unit-testable. Package-visible for exactly that reason.
     * <p>
     * The tree is reported clean iff every change set is empty (this matches
     * {@link Status#isClean()}, which is the union of the same seven sets being empty). Every
     * path/state cell goes through {@link MarkdownUtils} so a path containing {@code |} or a
     * newline cannot break the table.
     *
     * @param projectName the project the report is about
     * @param currentBranch the current branch short name, or the commit SHA when detached
     * @param detached {@code true} when HEAD does not point at a branch tip
     * @param sets state-label -&gt; the paths in that state, in report order (see {@link #categorize})
     * @return the Markdown report
     */
    static String renderStatus(String projectName, String currentBranch, boolean detached,
        Map<String, Set<String>> sets)
    {
        StringBuilder md = new StringBuilder();
        md.append("## Git Status: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        md.append("**Current:** ") //$NON-NLS-1$
            .append(detached ? "(detached HEAD at " + currentBranch + ")" : currentBranch) //$NON-NLS-1$ //$NON-NLS-2$
            .append("\n\n"); //$NON-NLS-1$

        int total = 0;
        for (Set<String> paths : sets.values())
        {
            if (paths != null)
            {
                total += paths.size();
            }
        }
        boolean clean = total == 0;

        md.append("**Clean:** ").append(clean ? "Yes" : "No").append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (clean)
        {
            md.append("*Working tree clean - nothing to commit.*\n"); //$NON-NLS-1$
            return md.toString();
        }

        md.append("**Changed entries:** ").append(total).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        md.append(MarkdownUtils.tableHeader("Path", "State")); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, Set<String>> entry : sets.entrySet())
        {
            Set<String> paths = entry.getValue();
            if (paths == null || paths.isEmpty())
            {
                continue;
            }
            List<String> sorted = new ArrayList<>(paths);
            Collections.sort(sorted);
            for (String path : sorted)
            {
                md.append(MarkdownUtils.tableRow(path, entry.getKey()));
            }
        }
        return md.toString();
    }
}
