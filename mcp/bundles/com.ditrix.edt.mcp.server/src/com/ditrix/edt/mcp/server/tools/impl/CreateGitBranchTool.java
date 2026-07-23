/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.jgit.api.CheckoutResult;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport;
import com.ditrix.edt.mcp.server.utils.git.GitCheckoutSupport;
import com.ditrix.edt.mcp.server.utils.git.GitRepositoryResolver;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Creates a new local git branch, optionally checks it out, and optionally
 * attaches an EXISTING infobase (application) to the new branch's context
 * (issue #281 phase 2) - the non-UI sibling of EDT's own "New Branch" command.
 * <p>
 * Once the branch itself is created (the ONLY step that can fail the whole
 * call), an optional checkout failure or an optional binding failure is
 * reported as a warning in the result {@code message} rather than a total
 * failure - the branch genuinely exists either way, and reporting that
 * dishonestly as an error would leave the caller unsure whether to retry the
 * (idempotent-breaking) creation. {@code checkedOut} / the absence of
 * {@code bound} tell the caller exactly what did NOT happen.
 * <p>
 * NOT gated by the destructive-consent dialog: creating a local branch is
 * reversible (delete it, or simply never use it), and never opens or
 * authenticates against a 1C infobase, so it needs no infobase-connection
 * bookkeeping ({@link #connectsToInfobase()} stays the default {@code false}).
 */
public class CreateGitBranchTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "create_git_branch"; //$NON-NLS-1$

    /** Input param: the new branch's short name. */
    private static final String KEY_BRANCH = "branch"; //$NON-NLS-1$

    /** Input param: optional start point (branch/ref/commit); defaults to HEAD. */
    private static final String KEY_START_POINT = "startPoint"; //$NON-NLS-1$

    /** Input param: whether to check out the new branch immediately. */
    private static final String KEY_CHECKOUT = "checkout"; //$NON-NLS-1$

    /** Input param: attach this application to the new branch's context. */
    private static final String KEY_SET_DEFAULT = "setDefault"; //$NON-NLS-1$

    /** Output key: whether the branch was created (always true on a non-error result). */
    private static final String KEY_CREATED = "created"; //$NON-NLS-1$

    /** Output key: whether the branch ended up checked out. */
    private static final String KEY_CHECKED_OUT = "checkedOut"; //$NON-NLS-1$

    /** Output key: branch-infobase binding read-back, present only when applicationId was given. */
    private static final String KEY_BOUND = "bound"; //$NON-NLS-1$

    /** Read-back key: the infobase names bound to the context. */
    private static final String KEY_INFOBASES = "infobases"; //$NON-NLS-1$

    /** Read-back key: the context's default infobase name (may be {@code null}). */
    private static final String KEY_DEFAULT_INFOBASE = "defaultInfobase"; //$NON-NLS-1$

    private static final String REFS_HEADS = "refs/heads/"; //$NON-NLS-1$

    /** Literal reported as {@link #KEY_START_POINT} when no explicit start point was given. */
    private static final String HEAD_LITERAL = "HEAD"; //$NON-NLS-1$

    /** Common prefix of every {@link #performCheckout} warning. */
    private static final String CHECKOUT_OF_NEW_BRANCH = "Checkout of the newly created branch '"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create a new local git branch, optionally check it out, and optionally attach an " //$NON-NLS-1$
            + "EXISTING infobase (application, from get_applications) to the new branch's context. " //$NON-NLS-1$
            + "Rejects a name that already exists (use switch_git_branch instead). " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('create_git_branch')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project whose git repository to branch (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_BRANCH,
                "New branch's short name (required); must not already exist.", true) //$NON-NLS-1$
            .stringProperty(KEY_START_POINT,
                "Existing branch, ref, or commit to start the new branch from. Optional; defaults " //$NON-NLS-1$
                + "to the current HEAD.") //$NON-NLS-1$
            .booleanProperty(KEY_CHECKOUT,
                "Check out the new branch immediately after creating it (bounded background Job, " //$NON-NLS-1$
                + "up to 120 s). Default false.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Optional: application id from get_applications to attach to the new branch's " //$NON-NLS-1$
                + "context (the base is FOR this branch).") //$NON-NLS-1$
            .booleanProperty(KEY_SET_DEFAULT,
                "Only with applicationId: also make it the DEFAULT infobase for the new branch's " //$NON-NLS-1$
                + "context. Default false.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the branch was created", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_BRANCH, "The new branch's short name.") //$NON-NLS-1$
            .booleanProperty(KEY_CREATED, "Always true on a non-error result.") //$NON-NLS-1$
            .booleanProperty(KEY_CHECKED_OUT,
                "Whether the branch ended up checked out. False when checkout was not requested, " //$NON-NLS-1$
                + "or was requested but did not complete cleanly (see message).") //$NON-NLS-1$
            .stringProperty(KEY_START_POINT, "The start point actually used ('HEAD' when none was given).") //$NON-NLS-1$
            .objectProperty(KEY_BOUND,
                "Branch-infobase binding read-back: {infobases: [...], defaultInfobase}. Present " //$NON-NLS-1$
                + "only when applicationId was given and the binding succeeded.") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE,
                "Present only when checkout or the infobase binding did not fully succeed: a short " //$NON-NLS-1$
                + "warning. The branch itself was still created (success stays true).") //$NON-NLS-1$
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
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_BRANCH);
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String branch = JsonUtils.extractStringArgument(params, KEY_BRANCH);
        String startPoint = JsonUtils.extractStringArgument(params, KEY_START_POINT);
        boolean checkout = JsonUtils.extractBooleanArgument(params, KEY_CHECKOUT, false);
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        boolean setDefault = JsonUtils.extractBooleanArgument(params, KEY_SET_DEFAULT, false);

        GitRepositoryResolver.Resolution resolution = GitRepositoryResolver.resolve(projectName);
        if (!resolution.ok())
        {
            return resolution.errorJson();
        }

        try
        {
            return createBranch(resolution.project(), resolution.repository(), branch, startPoint, checkout,
                applicationId, setDefault);
        }
        catch (Exception e) // NOSONAR unattended-safety: no exception may escape the tool (CLAUDE.md #8)
        {
            Activator.logError("create_git_branch: failed for project '" + projectName //$NON-NLS-1$
                + "', branch '" + branch + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to create branch: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        finally
        {
            resolution.closeIfOwned();
        }
    }

    private String createBranch(IProject project, Repository repo, String branch, String startPoint,
        boolean checkout, String applicationId, boolean setDefault) throws Exception
    {
        // --- pre-check (before ANY mutation) ---

        Ref existing = repo.findRef(branch);
        if (existing != null)
        {
            return ToolResult.error("Branch already exists: '" + branch + "' (resolves to '" //$NON-NLS-1$ //$NON-NLS-2$
                + existing.getName() + "'). Use switch_git_branch to check it out, or choose a " //$NON-NLS-1$
                + "different name.").toJson(); //$NON-NLS-1$
        }

        boolean hasStartPoint = startPoint != null && !startPoint.isEmpty();
        try
        {
            CreateBranchCommand cmd = Git.wrap(repo).branchCreate().setName(branch);
            if (hasStartPoint)
            {
                cmd.setStartPoint(startPoint);
            }
            cmd.call();
        }
        catch (RefAlreadyExistsException e)
        {
            return ToolResult.error("Branch already exists: '" + branch + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (RefNotFoundException e)
        {
            return ToolResult.error("startPoint not found: '" + (hasStartPoint ? startPoint : "(none)") //$NON-NLS-1$ //$NON-NLS-2$
                + "'. It must be an existing branch, tag, or commit.").toJson(); //$NON-NLS-1$
        }
        catch (InvalidRefNameException e)
        {
            return ToolResult.error("Invalid branch name: '" + branch + "': " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (GitAPIException e)
        {
            Activator.logError("create_git_branch: branchCreate failed for '" + branch + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Failed to create branch '" + branch + "': " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // --- the branch now exists: everything below is reported honestly, never as a total failure ---

        List<String> warnings = new ArrayList<>();
        boolean checkedOut = checkout && performCheckout(project, repo, branch, warnings);

        ToolResult ok = ToolResult.success()
            .put(KEY_BRANCH, branch)
            .put(KEY_CREATED, true)
            .put(KEY_CHECKED_OUT, checkedOut)
            .put(KEY_START_POINT, hasStartPoint ? startPoint : HEAD_LITERAL);

        if (applicationId != null && !applicationId.isEmpty())
        {
            bindApplication(project, branch, applicationId, setDefault, ok, warnings);
        }

        if (!warnings.isEmpty())
        {
            ok.put(McpKeys.MESSAGE, String.join(" ", warnings)); //$NON-NLS-1$
        }
        return ok.toJson();
    }

    /**
     * Runs the shared bounded checkout ({@link GitCheckoutSupport}) for the
     * newly created branch. A non-OK outcome never fails the call - the branch
     * already exists - it is recorded as a warning and {@code false} is
     * returned.
     */
    private static boolean performCheckout(IProject project, Repository repo, String branch, List<String> warnings)
    {
        String fullRef = REFS_HEADS + branch;
        GitCheckoutSupport.CheckoutOutcome outcome = GitCheckoutSupport.checkout(project, repo, fullRef);

        if (outcome.timedOut())
        {
            warnings.add(CHECKOUT_OF_NEW_BRANCH + branch + "' timed out after " //$NON-NLS-1$
                + GitCheckoutSupport.CHECKOUT_TIMEOUT_SECONDS
                + " seconds; the branch was created but is not checked out."); //$NON-NLS-1$
            return false;
        }
        if (outcome.interrupted())
        {
            warnings.add(CHECKOUT_OF_NEW_BRANCH + branch + "' was interrupted; the " //$NON-NLS-1$
                + "branch was created but is not checked out."); //$NON-NLS-1$
            return false;
        }
        if (outcome.jobError() != null)
        {
            Activator.logError("create_git_branch: checkout failed for branch '" + branch + "'", //$NON-NLS-1$ //$NON-NLS-2$
                outcome.jobError());
            warnings.add(CHECKOUT_OF_NEW_BRANCH + branch + "' failed: " //$NON-NLS-1$
                + outcome.jobError().getMessage() + "; the branch was created but is not checked out."); //$NON-NLS-1$
            return false;
        }

        CheckoutResult result = outcome.result();
        CheckoutResult.Status status = result != null ? result.getStatus() : CheckoutResult.Status.ERROR;
        if (status == CheckoutResult.Status.OK)
        {
            if (outcome.refreshWarning() != null)
            {
                warnings.add(outcome.refreshWarning());
            }
            return true;
        }
        warnings.add(CHECKOUT_OF_NEW_BRANCH + branch + "' did not complete cleanly " //$NON-NLS-1$
            + "(status: " + status + "); the branch was created but is not checked out."); //$NON-NLS-1$ //$NON-NLS-2$
        return false;
    }

    /**
     * Attaches {@code applicationId} to the new branch's context
     * ({@code InfobaseAssociationContext.of(branch)} - the same context whether or
     * not the branch was also checked out). A resolution or association failure
     * never fails the already-successful branch creation - it is appended to
     * {@code warnings} and {@link #KEY_BOUND} is simply omitted.
     */
    private static void bindApplication(IProject project, String branch, String applicationId, boolean setDefault,
        ToolResult ok, List<String> warnings)
    {
        IInfobaseAssociationManager assocManager = Activator.getDefault().getInfobaseAssociationManager();
        if (assocManager == null)
        {
            warnings.add("Could not attach application '" + applicationId + "' to branch '" + branch //$NON-NLS-1$ //$NON-NLS-2$
                + "': IInfobaseAssociationManager service is not available."); //$NON-NLS-1$
            return;
        }

        ApplicationReferenceResolution appRef = resolveApplicationReference(project, applicationId);
        if (appRef.warning() != null)
        {
            warnings.add(appRef.warning());
            return;
        }

        InfobaseAssociationContext ctx = InfobaseAssociationContext.of(branch);
        try
        {
            assocManager.associate(project, appRef.reference(), InfobaseAssociationSettings.alreadySynchronized(ctx));
            if (setDefault)
            {
                assocManager.setDefaultInfobase(project, appRef.reference(), ctx);
            }
        }
        catch (InfobaseAssociationException e)
        {
            Activator.logError("create_git_branch: attach failed for branch '" + branch + "', application '" //$NON-NLS-1$ //$NON-NLS-2$
                + applicationId + "'", e); //$NON-NLS-1$
            warnings.add("Branch '" + branch + "' was created, but attaching application '" + applicationId //$NON-NLS-1$ //$NON-NLS-2$
                + "' failed: " + e.getMessage()); //$NON-NLS-1$
            return;
        }

        Map<String, Object> bound = readBack(assocManager, project, ctx);
        if (!bound.isEmpty())
        {
            ok.put(KEY_BOUND, bound);
        }
    }

    private static Map<String, Object> readBack(IInfobaseAssociationManager assocManager, IProject project,
        InfobaseAssociationContext ctx)
    {
        try
        {
            Optional<IInfobaseAssociation> assoc = assocManager.getAssociation(project, ctx);
            List<String> names = new ArrayList<>();
            String defaultName = null;
            if (assoc.isPresent())
            {
                if (assoc.get().getInfobases() != null)
                {
                    for (InfobaseReference ib : assoc.get().getInfobases())
                    {
                        if (ib != null)
                        {
                            names.add(ib.getName());
                        }
                    }
                }
                InfobaseReference def = assoc.get().getDefaultInfobase();
                defaultName = def != null ? def.getName() : null;
            }
            Map<String, Object> bound = new LinkedHashMap<>();
            bound.put(KEY_INFOBASES, names);
            bound.put(KEY_DEFAULT_INFOBASE, defaultName);
            return bound;
        }
        catch (RuntimeException e)
        {
            Activator.logError("create_git_branch: bindings read-back failed for project '" //$NON-NLS-1$
                + project.getName() + "'", e); //$NON-NLS-1$
            return Collections.emptyMap();
        }
    }

    /**
     * Resolves {@code applicationId} to an {@link IApplication} (mirrors how
     * {@code SetInfobaseCredentialsTool} / {@code CreateLaunchConfigTool} resolve an
     * applicationId) and then its {@link InfobaseReference} via
     * {@link InfobaseAccessSupport#resolveInfobaseReference(IApplication)}. Unlike
     * {@code SetBranchInfobaseTool}'s equivalent, failures here are plain warning
     * text (not a JSON error) - a resolution failure here must not fail the
     * already-successful branch creation.
     */
    private static ApplicationReferenceResolution resolveApplicationReference(IProject project, String applicationId)
    {
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return ApplicationReferenceResolution.warning(
                "Could not attach application '" + applicationId + "': IApplicationManager service is not " //$NON-NLS-1$ //$NON-NLS-2$
                + "available."); //$NON-NLS-1$
        }
        Optional<IApplication> appOpt;
        try
        {
            appOpt = appManager.getApplication(project, applicationId);
        }
        catch (Exception e) // NOSONAR EDT application lookup — surface as an actionable warning
        {
            Activator.logError("create_git_branch: error resolving application '" + applicationId + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ApplicationReferenceResolution.warning(
                "Could not resolve application '" + applicationId + "': " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (appOpt.isEmpty())
        {
            return ApplicationReferenceResolution.warning("Application not found: " + applicationId //$NON-NLS-1$
                + ". Use get_applications to get valid application IDs."); //$NON-NLS-1$
        }
        InfobaseReference ref = InfobaseAccessSupport.resolveInfobaseReference(appOpt.get());
        if (ref == null)
        {
            return ApplicationReferenceResolution.warning("Application '" + applicationId //$NON-NLS-1$
                + "' has no infobase reference — it cannot be bound to a branch context."); //$NON-NLS-1$
        }
        return ApplicationReferenceResolution.resolved(ref);
    }

    /**
     * Outcome of {@link #resolveApplicationReference}: either the resolved
     * {@link InfobaseReference}, or a plain warning string (NOT a JSON error - a
     * binding failure never fails the already-successful branch creation).
     */
    private static final class ApplicationReferenceResolution
    {
        private final InfobaseReference reference;

        private final String warning;

        private ApplicationReferenceResolution(InfobaseReference reference, String warning)
        {
            this.reference = reference;
            this.warning = warning;
        }

        static ApplicationReferenceResolution resolved(InfobaseReference reference)
        {
            return new ApplicationReferenceResolution(reference, null);
        }

        static ApplicationReferenceResolution warning(String warning)
        {
            return new ApplicationReferenceResolution(null, warning);
        }

        InfobaseReference reference()
        {
            return reference;
        }

        String warning()
        {
            return warning;
        }
    }
}
