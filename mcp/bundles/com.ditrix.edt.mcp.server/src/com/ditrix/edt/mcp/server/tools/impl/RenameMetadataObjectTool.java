/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.rename.MetadataRenameService;

/**
 * Tool to rename a metadata object or attribute with full refactoring support.
 *
 * Two-phase workflow:
 * 1. Preview mode (confirm=false, default): Returns list of affected refactoring items and problems.
 * 2. Execute mode (confirm=true): Performs the rename with all cascading code updates.
 * <p>
 * Thin adapter: parameter parsing, the required-argument guards and the UI-thread
 * {@code Display.syncExec} boundary live here; all domain logic lives in
 * {@link MetadataRenameService}.
 */
public class RenameMetadataObjectTool implements IMcpTool
{
    public static final String NAME = "rename_metadata_object"; //$NON-NLS-1$

    private final MetadataRenameService service = new MetadataRenameService();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Rename a metadata object or attribute with full refactoring support. " + //$NON-NLS-1$
               "Updates all references in BSL code, forms, and other metadata. " + //$NON-NLS-1$
               "WORKFLOW: 1) Call without confirm to get a preview with all change points and their indices. " + //$NON-NLS-1$
               "2) Review the preview — each change has an index, file, description, and enabled state. " + //$NON-NLS-1$
               "3) Call with confirm=true. Optionally pass disableIndices to skip specific change points. " + //$NON-NLS-1$
               "Supports FQNs like 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount'. " + //$NON-NLS-1$
               "Russian type names are also supported."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object to rename " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount'). " + //$NON-NLS-1$
                "Russian names supported.", true) //$NON-NLS-1$
            .stringProperty("newName", //$NON-NLS-1$
                "New name for the object (required)", true) //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "Set to true to execute the rename. Default false = preview only.") //$NON-NLS-1$
            .stringProperty("disableIndices", //$NON-NLS-1$
                "Comma-separated indices of change points to SKIP (from the preview list). " + //$NON-NLS-1$
                "Only optional changes can be disabled. Example: '2,3,5'") //$NON-NLS-1$
            .integerProperty("maxResults", //$NON-NLS-1$
                "Max number of change points to show in preview (default 20). 0 = no limit.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName != null && !projectName.isEmpty())
        {
            return "rename-refactoring-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "rename-refactoring.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        String newName = JsonUtils.extractStringArgument(params, "newName"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$
        String disableIndicesStr = JsonUtils.extractStringArgument(params, "disableIndices"); //$NON-NLS-1$
        final int maxResults = Math.max(0, JsonUtils.extractIntArgument(params, "maxResults", 20)); //$NON-NLS-1$

        // Parse disable indices
        java.util.Set<Integer> disableIndices = new java.util.HashSet<>();
        if (disableIndicesStr != null && !disableIndicesStr.isEmpty())
        {
            for (String part : disableIndicesStr.split(",")) //$NON-NLS-1$
            {
                try
                {
                    disableIndices.add(Integer.parseInt(part.trim()));
                }
                catch (NumberFormatException e)
                {
                    // ignore invalid entries
                }
            }
        }

        String err = JsonUtils.requireArgument(params, "projectName", //$NON-NLS-1$
            ". Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "objectFqn", //$NON-NLS-1$
            ". Examples: 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount', " //$NON-NLS-1$
            + "'Catalog.Products.TabularSection.Prices'"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "newName", //$NON-NLS-1$
            ". Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        final java.util.Set<Integer> finalDisableIndices = disableIndices;
        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(service.rename(projectName, objectFqn, newName, confirm, finalDisableIndices, maxResults));
            }
            catch (Exception e)
            {
                Activator.logError("Error in rename_metadata_object", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        return resultRef.get();
    }
}
