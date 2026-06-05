/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.refactoring.core.CleanReferenceProblem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Deletes a metadata node (a top-level object or a subordinate member) addressed by a 1C full-name
 * FQN, cascading the cleanup of every reference (BSL code, forms, other metadata) via EDT's
 * md-refactoring service. Two-phase: a bare call previews the affected references; {@code confirm=true}
 * performs the delete. Replaces the former {@code delete_metadata_object}.
 */
public class DeleteMetadataTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "delete_metadata"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Delete a metadata node (object or member) addressed by a 1C full-name FQN, cascading " //$NON-NLS-1$
            + "the cleanup of all references in BSL code, forms and other metadata. Two-phase: call " //$NON-NLS-1$
            + "without confirm to preview affected references, then confirm=true to apply (deletion " //$NON-NLS-1$
            + "is hard to reverse). Full parameters and examples: call get_tool_guide('delete_metadata')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty("fqn", //$NON-NLS-1$
                "Full-name FQN of the node to delete (required), e.g. 'Catalog.Products' or " //$NON-NLS-1$
                + "'Document.SalesOrder.Attribute.Amount' (type / kind tokens may be English or " //$NON-NLS-1$
                + "Russian; the Name parts are the programmatic Name, not the synonym).", true) //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = execute the deletion; default false = preview only.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the request succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "Either 'preview' or 'executed'") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("fqn", "FQN of the node targeted for deletion") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("refactoringTitle", "Title of the delete refactoring (preview)") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("items", "Metadata items the deletion would remove (preview)") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("affectedReferences", "References that would be affected (preview)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("affectedReferencesCount", "Count of affected references (preview)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable description of the result") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# delete_metadata\n\n" //$NON-NLS-1$
            + "Deletes one metadata node (a top-level object or one of its members) addressed by a 1C " //$NON-NLS-1$
            + "full-name FQN, and cascades the cleanup to every reference across the configuration: " //$NON-NLS-1$
            + "BSL code, forms and other metadata. Backed by EDT's md-refactoring service, so the same " //$NON-NLS-1$
            + "reference cleanup EDT computes for the IDE delete is what gets applied. The target's " //$NON-NLS-1$
            + "identity is its programmatic Name (not its synonym). Replaces the former " //$NON-NLS-1$
            + "delete_metadata_object.\n\n" //$NON-NLS-1$
            + "## Think twice\n" //$NON-NLS-1$
            + "This is a CASCADING, hard-to-reverse deletion: a wrong target can mass-edit BSL, forms " //$NON-NLS-1$
            + "and metadata across the whole configuration. Always preview first, run it on a " //$NON-NLS-1$
            + "configuration you can revert (version control), and do not execute without an explicit " //$NON-NLS-1$
            + "request. After execute, verify with get_project_errors.\n\n" //$NON-NLS-1$
            + "## When to use\n" //$NON-NLS-1$
            + "Use to remove an existing node and have all references cleaned automatically. To rename " //$NON-NLS-1$
            + "instead use rename_metadata_object; to create use create_metadata.\n\n" //$NON-NLS-1$
            + "## Two-phase workflow\n" //$NON-NLS-1$
            + "1. Preview (confirm omitted / false): returns the refactoring title, the refactoring " //$NON-NLS-1$
            + "items, and the affected references (referencingObject, reference feature, targetObject " //$NON-NLS-1$
            + "FQN) plus a count. Nothing is modified.\n" //$NON-NLS-1$
            + "2. Execute (confirm=true): performs the delete refactoring. Returns action='executed'.\n\n" //$NON-NLS-1$
            + "## Parameters\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `fqn` (required) - the delete target. Top object: 'Type.Name' (e.g. 'Catalog.Products' " //$NON-NLS-1$
            + "deletes the whole catalog). Member: 'Type.Name.Kind.Name', including a NESTED member " //$NON-NLS-1$
            + "(e.g. 'Catalog.X.TabularSection.T.Attribute.A'). Any node create_metadata can address - " //$NON-NLS-1$
            + "an attribute / tabular section / dimension / resource / enum value / command / template " //$NON-NLS-1$
            + "/ recalculation / type-specific child - can be deleted by its FQN.\n" //$NON-NLS-1$
            + "- `confirm` (optional, default false) - false previews, true applies.\n\n" //$NON-NLS-1$
            + "## Bilingual (ru/en)\n" //$NON-NLS-1$
            + "Resolves by the programmatic Name; only the leading TYPE token and the child KIND tokens " //$NON-NLS-1$
            + "are dialect-aware (English or Russian). The synonym is never used to locate the target.\n\n" //$NON-NLS-1$
            + "## Examples\n" //$NON-NLS-1$
            + "- Preview: `{projectName: 'P', fqn: 'Catalog.Products'}`\n" //$NON-NLS-1$
            + "- Execute: `{projectName: 'P', fqn: 'Catalog.Products', confirm: true}`\n" //$NON-NLS-1$
            + "- Delete one attribute: `{projectName: 'P', fqn: 'Document.SalesOrder.Attribute.Amount', " //$NON-NLS-1$
            + "confirm: true}`\n\n" //$NON-NLS-1$
            + "## Gotchas\n" //$NON-NLS-1$
            + "- A malformed nested FQN with an odd trailing token (e.g. 'Catalog.Products.Attribute') " //$NON-NLS-1$
            + "is rejected as not found, so a nested delete never silently falls back to the parent.\n" //$NON-NLS-1$
            + "- Deletion targets the programmatic Name; passing a synonym will not resolve."; //$NON-NLS-1$
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, "projectName", "fqn"); //$NON-NLS-1$ //$NON-NLS-2$
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String fqn = JsonUtils.extractStringArgument(params, "fqn"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        Configuration config = ctx.config;

        IMdRefactoringService refactoringService = Activator.getDefault().getMdRefactoringService();
        if (refactoringService == null)
        {
            return ToolResult.error("IMdRefactoringService not available").toJson(); //$NON-NLS-1$
        }

        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, normFqn);
        if (node == null)
        {
            return ToolResult.error("Node not found: " + fqn + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Check the FQN: 'Type.Name' for a top object (e.g. 'Catalog.Products'), " //$NON-NLS-1$
                + "'Type.Name.Kind.Name' for a member (e.g. 'Document.Order.Attribute.Amount'). " //$NON-NLS-1$
                + "Any node create_metadata can address can be deleted; see " //$NON-NLS-1$
                + "get_tool_guide('create_metadata') for the kinds. " //$NON-NLS-1$
                + "Use get_metadata_objects to find an object's FQN.").toJson(); //$NON-NLS-1$
        }

        IRefactoring refactoring = refactoringService.createMdObjectDeleteRefactoring(
            Collections.singletonList(node.object));
        if (refactoring == null)
        {
            return ToolResult.error("Failed to create delete refactoring for: " + normFqn).toJson(); //$NON-NLS-1$
        }

        return confirm ? performDelete(normFqn, refactoring) : buildPreview(normFqn, refactoring);
    }

    private String buildPreview(String fqn, IRefactoring refactoring)
    {
        List<Map<String, Object>> allItems = new ArrayList<>();
        List<Map<String, Object>> allProblems = new ArrayList<>();

        String title = refactoring.getTitle();

        Collection<IRefactoringItem> items = refactoring.getItems();
        if (items != null)
        {
            for (IRefactoringItem item : items)
            {
                Map<String, Object> itemMap = new java.util.LinkedHashMap<>();
                itemMap.put("name", item.getName()); //$NON-NLS-1$
                itemMap.put("optional", item.isOptional()); //$NON-NLS-1$
                itemMap.put("checked", item.isChecked()); //$NON-NLS-1$
                allItems.add(itemMap);
            }
        }

        RefactoringStatus status = refactoring.getStatus();
        if (status != null)
        {
            Collection<IRefactoringProblem> problems = status.getProblems();
            if (problems != null)
            {
                for (IRefactoringProblem problem : problems)
                {
                    Map<String, Object> problemMap = new java.util.LinkedHashMap<>();
                    if (problem instanceof CleanReferenceProblem crp)
                    {
                        EObject refObj = crp.getReferencingObject();
                        if (refObj instanceof IBmObject bmObj)
                        {
                            problemMap.put("referencingObject", bmObj.bmGetFqn()); //$NON-NLS-1$
                        }
                        EStructuralFeature feat = crp.getReference();
                        if (feat != null)
                        {
                            problemMap.put("reference", feat.getName()); //$NON-NLS-1$
                        }
                    }
                    EObject obj = problem.getObject();
                    if (obj instanceof IBmObject bmObj)
                    {
                        problemMap.put("targetObject", bmObj.bmGetFqn()); //$NON-NLS-1$
                    }
                    if (!problemMap.isEmpty())
                    {
                        allProblems.add(problemMap);
                    }
                }
            }
        }

        return ToolResult.success()
            .put("action", "preview") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", fqn) //$NON-NLS-1$
            .put("refactoringTitle", title) //$NON-NLS-1$
            .put("items", allItems) //$NON-NLS-1$
            .put("affectedReferences", allProblems) //$NON-NLS-1$
            .put("affectedReferencesCount", allProblems.size()) //$NON-NLS-1$
            .put("message", "Preview of delete refactoring. References listed above will be cleaned " //$NON-NLS-1$ //$NON-NLS-2$
                + "up. Call with confirm=true to apply.") //$NON-NLS-1$
            .toJson();
    }

    private String performDelete(String fqn, IRefactoring refactoring)
    {
        try
        {
            refactoring.perform();
            return ToolResult.success()
                .put("action", "executed") //$NON-NLS-1$ //$NON-NLS-2$
                .put("fqn", fqn) //$NON-NLS-1$
                .put("message", "Delete refactoring completed successfully.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error performing delete refactoring", e); //$NON-NLS-1$
            return ToolResult.error("Delete failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
