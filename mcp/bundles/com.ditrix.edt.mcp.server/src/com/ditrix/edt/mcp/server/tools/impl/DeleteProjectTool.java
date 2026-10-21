/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter.ProjectReleaseWaiter;

/**
 * Tool to remove an EDT project from the workspace, optionally deleting its
 * files from disk.
 *
 * <p>Destructive: guarded by a confirm-preview (mirroring
 * {@link DeleteMetadataTool}). A bare call
 * resolves the project and reports what would be removed WITHOUT changing
 * anything; only {@code confirm:true} performs the deletion.
 *
 * <p>It is the inverse of {@link ImportConfigurationFromXmlTool} (which creates a
 * project) and the cleanup step for an import round-trip.
 */
public class DeleteProjectTool implements IMcpTool
{
    public static final String NAME = "delete_project"; //$NON-NLS-1$

    /** Input/output param: whether the project's disk files are deleted too. */
    private static final String KEY_DELETE_CONTENT = "deleteContent"; //$NON-NLS-1$

    /** Bound on asynchronous EDT context release after the Eclipse resource is deleted. */
    static final long RELEASE_TIMEOUT_MS = 30_000L;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Remove an EDT project from the workspace, optionally with its sources on disk. DESTRUCTIVE " //$NON-NLS-1$
            + "and IRREVERSIBLE. Two-phase: call once WITHOUT confirm to preview, then again with " //$NON-NLS-1$
            + "confirm=true to apply. Parameters and examples: get_tool_guide('delete_project')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "Name of the EDT project to remove from the workspace.", true) //$NON-NLS-1$
            .booleanProperty(KEY_DELETE_CONTENT,
                "true = also delete the project's files from disk; default false = only unregister " //$NON-NLS-1$
                + "the project from the workspace (files stay on disk).") //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = perform the removal; default false = preview only (what would be removed, " //$NON-NLS-1$
                + "no change).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION, "Either 'preview' (nothing changed) or 'deleted' (removed).") //$NON-NLS-1$
            .booleanProperty("confirmationRequired", //$NON-NLS-1$
                "true on a preview (no change made); absent/false once deleted.") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT, "Name of the targeted project.") //$NON-NLS-1$
            .booleanProperty(KEY_DELETE_CONTENT, "Whether disk files were (or would be) deleted too.") //$NON-NLS-1$
            .stringProperty("state", "EDT context release: released, releasing, or unverified; absent on preview.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status message.") //$NON-NLS-1$
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
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        boolean deleteContent = JsonUtils.extractBooleanArgument(params, KEY_DELETE_CONTENT, false);
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$

        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        // Confirm-preview gate (mirrors delete_metadata): a bare call
        // resolves the project and reports what would be removed WITHOUT changing anything; only
        // confirm=true performs the removal. The project is confirmed to exist above, so the
        // preview is trustworthy.
        if (!confirm)
        {
            return ToolResult.success()
                .put(McpKeys.ACTION, "preview") //$NON-NLS-1$
                .put("confirmationRequired", true) //$NON-NLS-1$
                .put(McpKeys.PROJECT, projectName)
                .put(KEY_DELETE_CONTENT, deleteContent)
                .put(McpKeys.MESSAGE, "PREVIEW: this would remove project '" + projectName //$NON-NLS-1$
                    + "' from the workspace" //$NON-NLS-1$
                    + (deleteContent ? " AND delete its files from disk (IRREVERSIBLE)" : " (files kept on disk)") //$NON-NLS-1$ //$NON-NLS-2$
                    + ". Re-call with confirm=true to apply it.") //$NON-NLS-1$
                .toJson();
        }

        // The final consent check before deletion. A refusal leaves the project untouched.
        // Headless / env-bypass / non-ASK never block. The project exists, so the preview names
        // a real target; after consent, the release listener is armed before the deletion.
        ConsentPreview preview = new ConsentPreview(
            "Delete project", //$NON-NLS-1$
            deleteContent
                ? "This removes project '" + projectName //$NON-NLS-1$
                    + "' from the workspace AND deletes its files from disk (irreversible)." //$NON-NLS-1$
                : "This removes project '" + projectName //$NON-NLS-1$
                    + "' from the workspace (files kept on disk).", //$NON-NLS-1$
            1, java.util.Collections.singletonList(projectName));
        DestructiveConsentGate.ConsentDecision consentDecision =
            DestructiveConsentGate.getInstance().requireConsent(NAME, preview);
        if (consentDecision != DestructiveConsentGate.ConsentDecision.ALLOW)
        {
            return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(consentDecision, NAME)).toJson();
        }

        return deleteProject(project, projectName, deleteContent,
            () -> {
                ProjectReleaseWaiter waiter = LifecycleWaiter.prepareForRelease(project, projectName);
                return waiter == null ? null : new IReleaseWait()
                {
                    @Override
                    public boolean await(long timeoutMs)
                    {
                        return waiter.await(timeoutMs);
                    }

                    @Override
                    public void cleanup()
                    {
                        waiter.cleanup();
                    }
                };
            }, () -> project.delete(deleteContent, true, new NullProgressMonitor()));
    }

    /** The platform mutation and its pre-armed release wait, shared with headless tests. */
    static String deleteProject(IProject project, String projectName, boolean deleteContent,
        Supplier<IReleaseWait> prepare, IProjectDeleter deleter)
    {
        IReleaseWait waiter;
        try
        {
            waiter = prepare.get();
        }
        catch (Exception e)
        {
            String message = "Failed to prepare EDT context release verification for project '" + projectName //$NON-NLS-1$
                + "': " + e.getMessage() + ". Nothing was deleted; retry delete_project when lifecycle " //$NON-NLS-1$ //$NON-NLS-2$
                + "services are available."; //$NON-NLS-1$
            Activator.logWarning(message);
            return ToolResult.error(message).toJson();
        }
        try
        {
            Activator.logInfo("Delete project: name=" + projectName //$NON-NLS-1$
                + ", deleteContent=" + deleteContent); //$NON-NLS-1$
            deleter.delete();
            boolean released = waiter != null && waiter.await(RELEASE_TIMEOUT_MS);
            return deleteOutcome(projectName, deleteContent, released, waiter != null);
        }
        catch (Exception e)
        {
            Activator.logError("Error deleting project: " + projectName, e); //$NON-NLS-1$
            String message = "Failed to delete project '" + projectName //$NON-NLS-1$
                + "': " + e.getMessage(); //$NON-NLS-1$
            // Derive the strongest honest answer from the workspace. Absence proves the delete
            // landed; presence cannot prove that Eclipse did not remove part of the project first.
            return (!project.exists() ? ToolResult.errorAfterMutation(message)
                : ToolResult.errorWithUnknownMutationOutcome(message)).toJson();
        }
        finally
        {
            if (waiter != null)
            {
                waiter.cleanup();
            }
        }
    }

    /** Reports resource deletion separately from the platform's asynchronous context release. */
    static String deleteOutcome(String projectName, boolean deleteContent, boolean released, boolean verifiable)
    {
        String state;
        String message;
        if (verifiable && released)
        {
            state = "released"; //$NON-NLS-1$
            message = "Project '" + projectName + "' removed from the workspace" //$NON-NLS-1$ //$NON-NLS-2$
                + (deleteContent ? " (files deleted from disk)." : " (files kept on disk).") //$NON-NLS-1$ //$NON-NLS-2$
                + " EDT has released its project context."; //$NON-NLS-1$
        }
        else
        {
            state = verifiable ? "releasing" : "unverified"; //$NON-NLS-1$ //$NON-NLS-2$
            message = "Project '" + projectName + "' files were " + (deleteContent ? "deleted" : "kept") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + " and the project is gone from the workspace, but " //$NON-NLS-1$
                + (verifiable ? "EDT is still releasing its project context after " //$NON-NLS-1$
                    + TimeUnit.MILLISECONDS.toSeconds(RELEASE_TIMEOUT_MS) + " s - it was not released yet. " //$NON-NLS-1$
                    : "EDT context release could not be verified because lifecycle services are unavailable. ") //$NON-NLS-1$
                + "Do not clean_project its base or create_project the same name yet: EDT may try to restart " //$NON-NLS-1$
                + "the deleted project and fail. list_projects shows workspace resources, so absence there " //$NON-NLS-1$
                + "does not prove context release. Let EDT finish its background work; if release cannot be " //$NON-NLS-1$
                + "verified, restart EDT before retrying."; //$NON-NLS-1$
            Activator.logWarning(message);
        }
        return ToolResult.success()
            .put(McpKeys.ACTION, "deleted") //$NON-NLS-1$
            .put(McpKeys.PROJECT, projectName)
            .put(KEY_DELETE_CONTENT, deleteContent)
            .put("state", state) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, message)
            .toJson();
    }

    @FunctionalInterface
    interface IProjectDeleter
    {
        void delete() throws Exception;
    }

    interface IReleaseWait
    {
        boolean await(long timeoutMs);

        void cleanup();
    }
}
