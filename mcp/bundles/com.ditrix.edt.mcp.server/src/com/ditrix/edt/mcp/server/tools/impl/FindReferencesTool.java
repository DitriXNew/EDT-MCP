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
        return "Find every place a metadata object is used: BSL code modules (with line numbers), " + //$NON-NLS-1$
               "other metadata, forms, roles, subsystems, etc. Pass the object FQN; the type token " + //$NON-NLS-1$
               "may be English or Russian (e.g. 'Catalog.Products' or its Russian spelling). " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('find_references')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object to search for, e.g. 'Catalog.Products' " + //$NON-NLS-1$
                "(type token may be English or Russian) (required)", true) //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Result-size hint (default 100, max 500); caps the overall reference count " //$NON-NLS-1$
                + "(at limit*10), not per category.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "Locates every reference to one metadata object across the whole configuration: " //$NON-NLS-1$
            + "BSL code modules (with line numbers), other metadata objects (forms, roles, " //$NON-NLS-1$
            + "subsystems, attributes that type-reference it), predefined-item usages and " //$NON-NLS-1$
            + "field references. Results are grouped by category and returned as Markdown.\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- Before renaming or deleting an object, to assess the blast radius.\n" //$NON-NLS-1$
            + "- To understand where a catalog/document/register/common module is consumed.\n" //$NON-NLS-1$
            + "- To find an identifier regardless of its ru/en spelling - unlike " //$NON-NLS-1$
            + "`search_in_code`, this is model-aware, not a literal text search.\n\n" //$NON-NLS-1$

            + "## Parameter details\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `objectFqn` (required) - fully qualified name `Type.Name` of the object to " //$NON-NLS-1$
            + "find references for. The `Name` part is the object's programmatic name (never " //$NON-NLS-1$
            + "its synonym). Only the leading TYPE token is bilingual: `Catalog.Products` and " //$NON-NLS-1$
            + "`\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Products` resolve " //$NON-NLS-1$ // Spravochnik.Products
            + "to the same object (the Russian type spelling is normalized internally). " //$NON-NLS-1$
            + "Examples: `Catalog.Products`, `Document.SalesOrder`, `CommonModule.Common`, " //$NON-NLS-1$
            + "`InformationRegister.Prices`.\n" //$NON-NLS-1$
            + "- `limit` - result-size hint; default 100, max 500 (clamped). It caps the " //$NON-NLS-1$
            + "OVERALL number of references collected (at `limit*10`) before they are grouped, " //$NON-NLS-1$
            + "NOT a per-category count - so a single busy category can consume most of the " //$NON-NLS-1$
            + "budget. Raise it when a large object truncates the result.\n\n" //$NON-NLS-1$

            + "## Result categories\n" //$NON-NLS-1$
            + "References are grouped by where they were found, e.g.:\n" //$NON-NLS-1$
            + "- `BSL Modules` - code references, each with module path and line number.\n" //$NON-NLS-1$
            + "- Metadata features - attributes, tabular sections, forms, commands, roles, " //$NON-NLS-1$
            + "subsystems and other objects that type-reference or contain the object.\n" //$NON-NLS-1$
            + "- `Predefined items` - usages of the object's predefined values.\n" //$NON-NLS-1$
            + "- `Field references` - field-level usages.\n\n" //$NON-NLS-1$

            + "## Examples\n" //$NON-NLS-1$
            + "- English FQN: `{projectName, objectFqn: \"Catalog.Products\"}`.\n" //$NON-NLS-1$
            + "- Russian type token: `{projectName, objectFqn: " //$NON-NLS-1$
            + "\"\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Products\"}` " //$NON-NLS-1$ // Spravochnik.Products
            + "(same object as above).\n" //$NON-NLS-1$
            + "- Wider budget: `{projectName, objectFqn: \"Document.SalesOrder\", limit: 500}`.\n\n" //$NON-NLS-1$

            + "## Notes & gotchas\n" //$NON-NLS-1$
            + "- An unknown or malformed `objectFqn` (no `Type.Name`, or a type/name that does " //$NON-NLS-1$
            + "not exist) returns an error - check the type token and the programmatic name.\n" //$NON-NLS-1$
            + "- Resolution is by the object's `Name`, NOT by its synonym; passing a synonym " //$NON-NLS-1$
            + "will not match.\n" //$NON-NLS-1$
            + "- The result is a hint-limited snapshot: a truncation notice means there are " //$NON-NLS-1$
            + "more references than `limit*10` - raise `limit` to see them.\n" //$NON-NLS-1$
            + "- For a literal text search (comments, messages, raw strings) use " //$NON-NLS-1$
            + "`search_in_code` instead; for call graphs of a specific method use " //$NON-NLS-1$
            + "`get_method_call_hierarchy`.\n"; //$NON-NLS-1$
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
