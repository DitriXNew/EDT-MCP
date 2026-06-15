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
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.rename.MetadataRenameService;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

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

    /** Input param: FQN of the metadata object to rename. */
    private static final String KEY_OBJECT_FQN = "objectFqn"; //$NON-NLS-1$

    /** Input param: new programmatic Name for the object. */
    private static final String KEY_NEW_NAME = "newName"; //$NON-NLS-1$

    private final MetadataRenameService service = new MetadataRenameService();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Rename a metadata object or attribute, cascading the change across all references in " + //$NON-NLS-1$
               "BSL code, forms, and other metadata. Use the two-phase workflow: call without confirm " + //$NON-NLS-1$
               "for an indexed preview of every change point, review it, then call again with " + //$NON-NLS-1$
               "confirm=true to apply. Full parameters and examples: call get_tool_guide('rename_metadata_object')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name.", true) //$NON-NLS-1$
            .stringProperty(KEY_OBJECT_FQN,
                "FQN of the object to rename, e.g. 'Catalog.Products' or " + //$NON-NLS-1$
                "'Document.SalesOrder.Attribute.Amount' (Russian type names also accepted).", true) //$NON-NLS-1$
            .stringProperty(KEY_NEW_NAME,
                "New programmatic Name for the object.", true) //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = apply the rename; default false = preview only.") //$NON-NLS-1$
            .stringProperty("disableIndices", //$NON-NLS-1$
                "Comma-separated preview '#' indices of OPTIONAL change points to skip, e.g. '2,3,5'.") //$NON-NLS-1$
            .integerProperty("maxResults", //$NON-NLS-1$
                "Max change points shown in the preview (default 20; 0 = no limit).") //$NON-NLS-1$
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
            return "rename-refactoring-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "rename-refactoring.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String objectFqn = JsonUtils.extractStringArgument(params, KEY_OBJECT_FQN);
        String newName = JsonUtils.extractStringArgument(params, KEY_NEW_NAME);
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

        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME,
            ". Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, KEY_OBJECT_FQN,
            ". Examples: 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount', " //$NON-NLS-1$
            + "'Catalog.Products.TabularSection.Prices'"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, KEY_NEW_NAME,
            ". Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        // A cascade rename rewrites every reference to the object across BSL, forms and
        // metadata. If the project's derived data (the reference index) is still building,
        // the refactoring resolves an INCOMPLETE set of references: it would rename the
        // object, miss some references, and still report success — leaving dangling old
        // references (silent partial corruption). Refuse only for that transient BUILDING
        // state; a missing/closed project falls through to the value-naming error below.
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
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
