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
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name.", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object to rename, e.g. 'Catalog.Products' or " + //$NON-NLS-1$
                "'Document.SalesOrder.Attribute.Amount' (Russian type names also accepted).", true) //$NON-NLS-1$
            .stringProperty("newName", //$NON-NLS-1$
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
    public String getGuide()
    {
        return "Renames one metadata object or one of its child members and cascades the rename to " //$NON-NLS-1$
            + "every reference across the configuration: BSL code, forms, and other metadata. It is " //$NON-NLS-1$
            + "backed by LTK refactoring, so the same change set EDT computes for the IDE rename is " //$NON-NLS-1$
            + "what gets applied. The object's identity is its programmatic Name (not its synonym), " //$NON-NLS-1$
            + "and only newName is renamed.\n\n" //$NON-NLS-1$
            + "## Think twice\n" //$NON-NLS-1$
            + "This is a CASCADING, hard-to-reverse refactoring: a wrong target or newName can mass-edit " //$NON-NLS-1$
            + "BSL, forms and metadata across the whole configuration. Always preview first, run it on a " //$NON-NLS-1$
            + "configuration you can revert (version control), and do not execute without an explicit " //$NON-NLS-1$
            + "request. After execute, verify with get_project_errors.\n\n" //$NON-NLS-1$
            + "## When to use\n" //$NON-NLS-1$
            + "Use to rename an existing object or member and have all callers updated automatically. " //$NON-NLS-1$
            + "To create an object use create_metadata_object; to add a member use " //$NON-NLS-1$
            + "add_metadata_attribute; to delete use delete_metadata_object.\n\n" //$NON-NLS-1$
            + "## Two-phase workflow\n" //$NON-NLS-1$
            + "1. Preview (confirm omitted / false, the default): returns a Markdown report with a " //$NON-NLS-1$
            + "change-points table. Each row has a '#' index, the file/location, a description, whether " //$NON-NLS-1$
            + "the change is Optional, and whether it is Enabled by default. Nothing is modified.\n" //$NON-NLS-1$
            + "2. Execute (confirm=true): re-walks the SAME change tree with the SAME '#' numbering and " //$NON-NLS-1$
            + "applies the rename, skipping any indices you pass in disableIndices.\n\n" //$NON-NLS-1$
            + "## Parameters\n" //$NON-NLS-1$
            + "- projectName (required): EDT project name.\n" //$NON-NLS-1$
            + "- objectFqn (required): FQN of the rename target. Top object: 'Type.Name' " //$NON-NLS-1$
            + "(e.g. 'Catalog.Products'). Child member: 'Type.Name.ChildType.ChildName' " //$NON-NLS-1$
            + "(e.g. 'Document.SalesOrder.Attribute.Amount'). Supported child types: Attribute, " //$NON-NLS-1$
            + "TabularSection, Dimension, Resource.\n" //$NON-NLS-1$
            + "- newName (required): the new programmatic Name. Only this identifier changes.\n" //$NON-NLS-1$
            + "- confirm (optional, default false): false previews, true applies.\n" //$NON-NLS-1$
            + "- disableIndices (optional): comma-separated '#' indices from the preview to skip, e.g. " //$NON-NLS-1$
            + "'2,3,5'. Only OPTIONAL change points can be disabled; required ones are always applied. " //$NON-NLS-1$
            + "One '#' index may span several context rows in the table - skipping it skips them all.\n" //$NON-NLS-1$
            + "- maxResults (optional, default 20): caps how many change points the preview lists; 0 = " //$NON-NLS-1$
            + "no limit. This only trims the preview display, never what execute actually changes.\n\n" //$NON-NLS-1$
            + "## Bilingual notes (ru/en)\n" //$NON-NLS-1$
            + "- objectFqn resolves by the object's programmatic Name; in the FQN only the leading TYPE " //$NON-NLS-1$
            + "token may be bilingual (e.g. 'Catalog' or the Russian " //$NON-NLS-1$
            + "'\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A'). The synonym is never " //$NON-NLS-1$
            + "used to locate the target.\n" //$NON-NLS-1$
            + "- This renames the Name only; it does not touch synonyms. Synonyms stay keyed by language " //$NON-NLS-1$
            + "code and are unaffected by the rename.\n\n" //$NON-NLS-1$
            + "## Examples\n" //$NON-NLS-1$
            + "- Preview a top-object rename: {projectName: 'MyProject', objectFqn: 'Catalog.Products', " //$NON-NLS-1$
            + "newName: 'Goods'}\n" //$NON-NLS-1$
            + "- Execute it: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods', " //$NON-NLS-1$
            + "confirm: true}\n" //$NON-NLS-1$
            + "- Rename an attribute, skipping two optional change points: {projectName: 'MyProject', " //$NON-NLS-1$
            + "objectFqn: 'Document.SalesOrder.Attribute.Amount', newName: 'Total', confirm: true, " //$NON-NLS-1$
            + "disableIndices: '3,4'}\n" //$NON-NLS-1$
            + "- Russian type token: {projectName: 'MyProject', objectFqn: '\u0421\u043F\u0440\u0430" //$NON-NLS-1$
            + "\u0432\u043E\u0447\u043D\u0438\u043A.Products', newName: 'Goods'}\n\n" //$NON-NLS-1$
            + "## Gotchas\n" //$NON-NLS-1$
            + "- A '#' index is a stable cross-call handle: the index you see in preview is the same one " //$NON-NLS-1$
            + "execute uses, so 'skip #N' disables exactly that change. Always read disableIndices from a " //$NON-NLS-1$
            + "fresh preview of the same rename.\n" //$NON-NLS-1$
            + "- disableIndices is ignored for required (non-optional) change points; you cannot skip a " //$NON-NLS-1$
            + "change the refactoring deems mandatory.\n" //$NON-NLS-1$
            + "- maxResults only narrows the preview list; it has no effect when confirm=true.\n" //$NON-NLS-1$
            + "- An unsupported child type or a malformed FQN is rejected with guidance on the accepted " //$NON-NLS-1$
            + "'Type.Name' / 'Type.Name.ChildType.ChildName' shapes."; //$NON-NLS-1$
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
