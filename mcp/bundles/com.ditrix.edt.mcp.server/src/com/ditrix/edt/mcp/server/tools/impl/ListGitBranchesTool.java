/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;

/**
 * Lists a project's git branches (local + remote-tracking, current branch marked,
 * detached HEAD flagged) and, best-effort, the 1C application/infobase each branch
 * <em>context</em> is bound to via {@link IInfobaseAssociationManager} - the
 * read-only counterpart of {@link SwitchGitBranchTool} (issue #281).
 * <p>
 * The application binding is DERIVED live from the repository's checked-out
 * branch ({@code GitRepositoryAssociationContextManager} reads
 * {@code Repository.getFullBranch()} on demand), so this section is informational:
 * it reflects whatever bindings EDT/the association manager already track, and is
 * degraded to a "bindings unavailable" note (never a hard tool error) when the
 * manager is absent, the project is not EGit-shared, or the lookup throws - the
 * branch list itself must stay useful even when bindings cannot be read.
 * <p>
 * Read-only ({@code list_} prefix); no BM model transaction, no UI thread - a pure
 * JGit/EGit read.
 */
public class ListGitBranchesTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "list_git_branches"; //$NON-NLS-1$

    private static final String REFS_HEADS = "refs/heads/"; //$NON-NLS-1$

    private static final String REFS_REMOTES = "refs/remotes/"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List a project's git branches: local and remote-tracking, with the CURRENT branch " //$NON-NLS-1$
            + "marked (detached HEAD flagged), plus the 1C application/infobase each branch context is " //$NON-NLS-1$
            + "bound to (best-effort). Precedes switch_git_branch. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('list_git_branches')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project to list git branches for (required).", true) //$NON-NLS-1$
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
            return "git-branches-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "git-branches.md"; //$NON-NLS-1$
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
            return render(projectName, resolution.project(), resolution.repository());
        }
        catch (IOException | GitAPIException e)
        {
            Activator.logError("list_git_branches: failed for project '" + projectName + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to read git branches for '" + projectName //$NON-NLS-1$
                + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        finally
        {
            resolution.closeIfOwned();
        }
    }

    private String render(String projectName, IProject project, Repository repo)
        throws IOException, GitAPIException
    {
        StringBuilder md = new StringBuilder();
        md.append("## Git Branches: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // The short branch name is the 40-hex commit SHA when the HEAD is detached; the full
        // ref is refs/heads/... on a named branch, or the same SHA when detached.
        String currentShort = repo.getBranch();
        String fullBranch = repo.getFullBranch();
        boolean detached = fullBranch == null || !fullBranch.startsWith(REFS_HEADS);

        md.append("**Current:** ") //$NON-NLS-1$
            .append(detached ? "(detached HEAD at " + currentShort + ")" : currentShort) //$NON-NLS-1$ //$NON-NLS-2$
            .append("\n\n"); //$NON-NLS-1$

        List<Ref> refs = Git.wrap(repo).branchList().setListMode(ListMode.ALL).call();
        md.append(renderBranchTable(refs, fullBranch, detached));

        md.append("\n### Application Bindings\n\n"); //$NON-NLS-1$
        md.append(renderBindings(project));

        return md.toString();
    }

    /** Package-visible (not private) so it is directly unit-testable with hand-built {@link Ref}s. */
    static String renderBranchTable(List<Ref> refs, String fullBranch, boolean detached)
    {
        StringBuilder md = new StringBuilder();
        md.append("| Branch | Type | Current |\n"); //$NON-NLS-1$
        md.append("|--------|------|---------|\n"); //$NON-NLS-1$
        if (refs.isEmpty())
        {
            md.append("*No branches found.*\n"); //$NON-NLS-1$
            return md.toString();
        }
        for (Ref ref : refs)
        {
            String refName = ref.getName();
            String type;
            String shortName;
            if (refName.startsWith(REFS_HEADS))
            {
                type = "local"; //$NON-NLS-1$
                shortName = refName.substring(REFS_HEADS.length());
            }
            else if (refName.startsWith(REFS_REMOTES))
            {
                type = "remote"; //$NON-NLS-1$
                shortName = refName.substring(REFS_REMOTES.length());
            }
            else
            {
                type = "other"; //$NON-NLS-1$
                shortName = refName;
            }
            boolean isCurrent = !detached && refName.equals(fullBranch);
            md.append(MarkdownUtils.tableRow(shortName, type, isCurrent ? "Yes" : "")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return md.toString();
    }

    /**
     * Best-effort application-binding section: enumerates
     * {@link IInfobaseAssociationManager#getAssociationContexts(IProject)} and, for
     * each context, the bound infobases + the default one. Any failure (manager
     * absent, not EGit-shared, {@link InfobaseAssociationException}) degrades to a
     * "bindings unavailable" note rather than a tool error - the branch list above
     * must stay useful regardless.
     */
    private String renderBindings(IProject project)
    {
        IInfobaseAssociationManager assocManager = Activator.getDefault().getInfobaseAssociationManager();
        if (assocManager == null)
        {
            return "*bindings unavailable: IInfobaseAssociationManager service is not available.*\n"; //$NON-NLS-1$
        }

        Collection<InfobaseAssociationContext> contexts;
        try
        {
            contexts = assocManager.getAssociationContexts(project);
        }
        catch (RuntimeException e)
        {
            return "*bindings unavailable: " //$NON-NLS-1$
                + MarkdownUtils.escapeMarkdown(String.valueOf(e.getMessage())) + "*\n"; //$NON-NLS-1$
        }
        if (contexts == null || contexts.isEmpty())
        {
            return "*No application bindings recorded for this project.*\n"; //$NON-NLS-1$
        }

        StringBuilder md = new StringBuilder();
        md.append("| Branch Context | Infobases | Default |\n"); //$NON-NLS-1$
        md.append("|-----------------|-----------|---------|\n"); //$NON-NLS-1$
        for (InfobaseAssociationContext ctx : contexts)
        {
            md.append(renderBindingRow(assocManager, project, ctx));
        }
        return md.toString();
    }

    private String renderBindingRow(IInfobaseAssociationManager assocManager, IProject project,
        InfobaseAssociationContext ctx)
    {
        String branchName = ctx.getContext().orElse("(default)"); //$NON-NLS-1$
        String infobasesCell;
        String defaultCell = ""; //$NON-NLS-1$
        try
        {
            Optional<IInfobaseAssociation> assoc = assocManager.getAssociation(project, ctx);
            if (assoc.isPresent())
            {
                Collection<InfobaseReference> infobases = assoc.get().getInfobases();
                InfobaseReference def = assoc.get().getDefaultInfobase();
                List<String> names = new ArrayList<>();
                if (infobases != null)
                {
                    for (InfobaseReference ib : infobases)
                    {
                        names.add(ib.getName());
                    }
                }
                infobasesCell = names.isEmpty() ? "(none)" : String.join(", ", names); //$NON-NLS-1$ //$NON-NLS-2$
                defaultCell = def != null ? def.getName() : ""; //$NON-NLS-1$
            }
            else
            {
                infobasesCell = "(none)"; //$NON-NLS-1$
            }
        }
        catch (RuntimeException e)
        {
            infobasesCell = "unavailable: " + e.getMessage(); //$NON-NLS-1$
        }
        return MarkdownUtils.tableRow(branchName, infobasesCell, defaultCell);
    }
}
