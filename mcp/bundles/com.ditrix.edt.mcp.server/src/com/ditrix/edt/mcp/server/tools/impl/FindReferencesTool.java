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
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService;

/**
 * Tool to find all references to a metadata object.
 * Returns all places where the object is used: in other metadata objects and BSL code.
 */
@SuppressWarnings("restriction")
public class FindReferencesTool implements IMcpTool
{
    public static final String NAME = "find_references"; //$NON-NLS-1$

    /** Input param: FQN of the metadata object to find references to. */
    private static final String KEY_OBJECT_FQN = "objectFqn"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Find every place a metadata object is used: BSL code modules (with line numbers), " + //$NON-NLS-1$
               "other metadata, forms, roles, subsystems, etc. Pass the object FQN; the type token " + //$NON-NLS-1$
               "may be English or Russian (e.g. 'Catalog.Products' or its Russian spelling). " + //$NON-NLS-1$
               "Use this for all USAGES of a symbol; for where it is DEFINED use go_to_definition, " + //$NON-NLS-1$
               "for a literal (non-symbol) text scan use search_in_code. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('find_references')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty(KEY_OBJECT_FQN,
                "FQN of the object to search for, e.g. 'Catalog.Products' " + //$NON-NLS-1$
                "(type token may be English or Russian) (required)", true) //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Result-size hint (default 100, max 500); caps the overall reference count " //$NON-NLS-1$
                + "(at limit*10), not per category.") //$NON-NLS-1$
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
        String objectFqn = JsonUtils.extractStringArgument(params, KEY_OBJECT_FQN);
        if (objectFqn != null && !objectFqn.isEmpty())
        {
            String safeName = objectFqn.replace(".", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            return "references-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "references.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Validate required parameters via the shared guard (canonical reference
        // for the broader required-guard migration).
        String missing = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (missing != null)
        {
            return missing;
        }
        missing = JsonUtils.requireArgument(params, KEY_OBJECT_FQN);
        if (missing != null)
        {
            return missing;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String objectFqn = JsonUtils.extractStringArgument(params, KEY_OBJECT_FQN);

        // Shared typed accessor (handles the "42.0" form and invalid/missing -> default),
        // replacing the inline Double.parseDouble. Default 100, upper clamp 500 preserved.
        int limit = Math.min(JsonUtils.extractIntArgument(params, "limit", 100), 500); //$NON-NLS-1$

        // Execute on UI thread
        AtomicReference<String> resultRef = new AtomicReference<>();
        final int maxResults = limit;

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = new MetadataReferenceService().findReferences(projectName, objectFqn, maxResults);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error finding references", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        return resultRef.get();
    }
}
