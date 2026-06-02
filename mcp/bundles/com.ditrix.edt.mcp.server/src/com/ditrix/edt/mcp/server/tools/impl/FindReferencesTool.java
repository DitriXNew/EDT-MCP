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

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Find all references to a metadata object. " + //$NON-NLS-1$
               "Returns all places where the object is used: in other metadata objects, " + //$NON-NLS-1$
               "in BSL code modules with line numbers, forms, roles, subsystems, etc. " + //$NON-NLS-1$
               "Supports both English and Russian metadata type names " + //$NON-NLS-1$
               "(e.g., '\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u041D\u043E\u043C\u0435\u043D\u043A\u043B\u0430\u0442\u0443\u0440\u0430', " + //$NON-NLS-1$ // Справочник.Номенклатура
               "'\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u0417\u0430\u043A\u0430\u0437')."; //$NON-NLS-1$ // Документ.Заказ
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "Fully qualified name of the object to find references for " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products', 'Document.SalesOrder', 'CommonModule.Common'). " + //$NON-NLS-1$
                "Russian type names are also supported (e.g. '\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u041D\u043E\u043C\u0435\u043D\u043A\u043B\u0430\u0442\u0443\u0440\u0430')", true) //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Result-size hint (default 100, max 500). Caps the overall number of " //$NON-NLS-1$
                + "references collected before grouping at limit*10, not per category.") //$NON-NLS-1$
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
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
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
        String missing = JsonUtils.requireArgument(params, "projectName"); //$NON-NLS-1$
        if (missing != null)
        {
            return missing;
        }
        missing = JsonUtils.requireArgument(params, "objectFqn"); //$NON-NLS-1$
        if (missing != null)
        {
            return missing;
        }

        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$

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
