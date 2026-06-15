/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

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

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Remove an EDT project from the workspace, optionally deleting its files from disk " //$NON-NLS-1$
            + "(deleteContent). Destructive: guarded by a confirm-preview - call without confirm to " //$NON-NLS-1$
            + "preview what would be removed (no change), then confirm=true to delete. The inverse of " //$NON-NLS-1$
            + "import_configuration_from_xml. Full parameters: call get_tool_guide('delete_project')."; //$NON-NLS-1$
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

        try
        {
            Activator.logInfo("Delete project: name=" + projectName //$NON-NLS-1$
                + ", deleteContent=" + deleteContent); //$NON-NLS-1$
            // force=true so a project that is slightly out of sync with disk still deletes.
            project.delete(deleteContent, true, new NullProgressMonitor());

            return ToolResult.success()
                .put(McpKeys.ACTION, "deleted") //$NON-NLS-1$
                .put(McpKeys.PROJECT, projectName)
                .put(KEY_DELETE_CONTENT, deleteContent)
                .put(McpKeys.MESSAGE, "Project '" + projectName + "' removed from the workspace" //$NON-NLS-1$ //$NON-NLS-2$
                    + (deleteContent ? " (files deleted from disk)." : " (files kept on disk).")) //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error deleting project: " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Failed to delete project '" + projectName + "': " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
