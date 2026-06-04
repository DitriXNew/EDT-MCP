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
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;

/**
 * Tool to delete a metadata object or attribute with full refactoring support.
 * 
 * Two-phase workflow:
 * 1. Preview mode (confirm=false, default): Returns list of affected references and problems.
 * 2. Execute mode (confirm=true): Performs the deletion with reference cleanup.
 */
public class DeleteMetadataObjectTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "delete_metadata_object"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Delete a metadata object or attribute, cascading the cleanup of all references in BSL " + //$NON-NLS-1$
               "code, forms, and other metadata. Use the two-phase workflow: call without confirm to " + //$NON-NLS-1$
               "preview affected references, review, then call with confirm=true to apply (deletion is " + //$NON-NLS-1$
               "hard to reverse). Full parameters and examples: call get_tool_guide('delete_metadata_object')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name.", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object to delete, e.g. 'Catalog.Products' or " + //$NON-NLS-1$
                "'Document.SalesOrder.Attribute.Amount' (Russian type token also accepted; " + //$NON-NLS-1$
                "name is the programmatic Name, not the synonym).", true) //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = execute the deletion; default false = preview only.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        // Two success branches: action="preview" (rich) and action="executed" (terse).
        // The schema is the union; only success is always present.
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the request succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "Either 'preview' or 'executed'") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectFqn", "FQN of the object targeted for deletion") //$NON-NLS-1$ //$NON-NLS-2$
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
        return "Deletes one metadata object or one of its child members and cascades the cleanup to " //$NON-NLS-1$
            + "every reference across the configuration: BSL code, forms, and other metadata. It is " //$NON-NLS-1$
            + "backed by EDT's md-refactoring service (IMdRefactoringService delete refactoring), so the " //$NON-NLS-1$
            + "same reference cleanup EDT computes for the IDE delete is what gets applied. The target's " //$NON-NLS-1$
            + "identity is its programmatic Name (not its synonym / display name).\n\n" //$NON-NLS-1$
            + "## Think twice\n" //$NON-NLS-1$
            + "This is a CASCADING, hard-to-reverse deletion: a wrong target can mass-edit BSL, forms and " //$NON-NLS-1$
            + "metadata across the whole configuration. Always preview first, run it on a configuration " //$NON-NLS-1$
            + "you can revert (version control), and do not execute without an explicit request. After " //$NON-NLS-1$
            + "execute, verify with get_project_errors.\n\n" //$NON-NLS-1$
            + "## When to use\n" //$NON-NLS-1$
            + "Use to remove an existing object or member and have all references cleaned automatically. " //$NON-NLS-1$
            + "To rename instead use rename_metadata_object; to create use create_metadata_object; to add " //$NON-NLS-1$
            + "a member use add_metadata_attribute.\n\n" //$NON-NLS-1$
            + "## Two-phase workflow\n" //$NON-NLS-1$
            + "1. Preview (confirm omitted / false, the default): returns the refactoring title, the list " //$NON-NLS-1$
            + "of refactoring items, and the affected references (referencingObject, reference feature, " //$NON-NLS-1$
            + "and targetObject FQN) plus a count. Nothing is modified.\n" //$NON-NLS-1$
            + "2. Execute (confirm=true): performs the delete refactoring, removing the object and " //$NON-NLS-1$
            + "cleaning up every reference. Returns action='executed' on success.\n\n" //$NON-NLS-1$
            + "## Parameter details\n" //$NON-NLS-1$
            + "- projectName (required): EDT project name.\n" //$NON-NLS-1$
            + "- objectFqn (required): FQN of the delete target. Top object: 'Type.Name' " //$NON-NLS-1$
            + "(e.g. 'Catalog.Products' deletes the whole catalog). Child member: " //$NON-NLS-1$
            + "'Type.Name.ChildType.ChildName' (e.g. 'Document.SalesOrder.Attribute.Amount' deletes one " //$NON-NLS-1$
            + "attribute; 'Catalog.Products.TabularSection.Prices' deletes a tabular section). Supported " //$NON-NLS-1$
            + "child types: Attribute, TabularSection, Dimension, Resource.\n" //$NON-NLS-1$
            + "- confirm (optional, default false): false previews, true applies.\n\n" //$NON-NLS-1$
            + "## Bilingual notes (ru/en)\n" //$NON-NLS-1$
            + "- objectFqn resolves by the object's programmatic Name; in the FQN only the leading TYPE " //$NON-NLS-1$
            + "token may be bilingual (e.g. 'Catalog' or the Russian " //$NON-NLS-1$
            + "'\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a'). The synonym is never used " //$NON-NLS-1$
            + "to locate the target.\n" //$NON-NLS-1$
            + "- Child-type tokens are also bilingual: e.g. Attribute / " //$NON-NLS-1$
            + "'\u0420\u0435\u043a\u0432\u0438\u0437\u0438\u0442', TabularSection / " //$NON-NLS-1$
            + "'\u0422\u0430\u0431\u043b\u0438\u0447\u043d\u0430\u044f\u0427\u0430\u0441\u0442\u044c', " //$NON-NLS-1$
            + "Dimension / '\u0418\u0437\u043c\u0435\u0440\u0435\u043d\u0438\u0435', Resource / " //$NON-NLS-1$
            + "'\u0420\u0435\u0441\u0443\u0440\u0441'.\n\n" //$NON-NLS-1$
            + "## Examples\n" //$NON-NLS-1$
            + "- Preview deleting a catalog: {projectName: 'MyProject', objectFqn: 'Catalog.Products'}\n" //$NON-NLS-1$
            + "- Execute it: {projectName: 'MyProject', objectFqn: 'Catalog.Products', confirm: true}\n" //$NON-NLS-1$
            + "- Delete one attribute: {projectName: 'MyProject', " //$NON-NLS-1$
            + "objectFqn: 'Document.SalesOrder.Attribute.Amount', confirm: true}\n" //$NON-NLS-1$
            + "- Russian type token: {projectName: 'MyProject', objectFqn: '\u0421\u043f\u0440\u0430" //$NON-NLS-1$
            + "\u0432\u043e\u0447\u043d\u0438\u043a.Products'}\n\n" //$NON-NLS-1$
            + "## Gotchas\n" //$NON-NLS-1$
            + "- A malformed nested FQN with an odd trailing token (e.g. 'Catalog.Products.Attribute') is " //$NON-NLS-1$
            + "rejected as not found, so a nested delete never silently falls back to deleting the parent " //$NON-NLS-1$
            + "object.\n" //$NON-NLS-1$
            + "- An unsupported child type or a name that does not resolve is rejected with guidance on " //$NON-NLS-1$
            + "the accepted 'Type.Name' / 'Type.Name.ChildType.ChildName' shapes.\n" //$NON-NLS-1$
            + "- Deletion targets the programmatic Name; passing a synonym / display name will not " //$NON-NLS-1$
            + "resolve."; //$NON-NLS-1$
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$

        String err = JsonUtils.requireArgument(params, "projectName", //$NON-NLS-1$
            ". Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "objectFqn", //$NON-NLS-1$
            ". Examples: 'Catalog.Products' (delete whole catalog), " //$NON-NLS-1$
            + "'Document.SalesOrder.Attribute.Amount' (delete attribute), " //$NON-NLS-1$
            + "'Catalog.Products.TabularSection.Prices' (delete tabular section)"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        return executeInternal(projectName, objectFqn, confirm);
    }

    private String executeInternal(String projectName, String objectFqn, boolean confirm)
    {
        // Get project and configuration
        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        Configuration config = ctx.config;

        // Get refactoring service
        IMdRefactoringService refactoringService = Activator.getDefault().getMdRefactoringService();
        if (refactoringService == null)
        {
            return ToolResult.error("IMdRefactoringService not available").toJson(); //$NON-NLS-1$
        }

        // Normalize and find the object
        objectFqn = MetadataTypeUtils.normalizeFqn(objectFqn);
        MdObject targetObject = resolveObject(config, objectFqn);
        if (targetObject == null)
        {
            return ToolResult.error("Object not found: " + objectFqn + ". " + //$NON-NLS-1$
                "Check the FQN format: 'Type.Name' for top-level objects (e.g. 'Catalog.Products'), " + //$NON-NLS-1$
                "'Type.Name.ChildType.ChildName' for nested (e.g. 'Document.Order.Attribute.Amount'). " + //$NON-NLS-1$
                "Supported child types: Attribute, TabularSection, Dimension, Resource.").toJson(); //$NON-NLS-1$
        }

        // Create delete refactoring
        IRefactoring refactoring = refactoringService.createMdObjectDeleteRefactoring(
            Collections.singletonList(targetObject));
        if (refactoring == null)
        {
            return ToolResult.error("Failed to create delete refactoring for: " + objectFqn).toJson(); //$NON-NLS-1$
        }

        if (!confirm)
        {
            return buildPreview(objectFqn, refactoring);
        }
        else
        {
            return performDelete(objectFqn, refactoring);
        }
    }

    private String buildPreview(String objectFqn, IRefactoring refactoring)
    {
        List<Map<String, Object>> allItems = new ArrayList<>();
        List<Map<String, Object>> allProblems = new ArrayList<>();

        String title = refactoring.getTitle();

        // Collect refactoring items
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

        // Collect problems (references that will be cleaned)
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

        ToolResult result = ToolResult.success()
            .put("action", "preview") //$NON-NLS-1$ //$NON-NLS-2$
            .put("objectFqn", objectFqn) //$NON-NLS-1$
            .put("refactoringTitle", title) //$NON-NLS-1$
            .put("items", allItems) //$NON-NLS-1$
            .put("affectedReferences", allProblems) //$NON-NLS-1$
            .put("affectedReferencesCount", allProblems.size()) //$NON-NLS-1$
            .put("message", "Preview of delete refactoring. " + //$NON-NLS-1$ //$NON-NLS-2$
                 "References listed above will be cleaned up. " + //$NON-NLS-1$
                 "Call with confirm=true to apply."); //$NON-NLS-1$

        return result.toJson();
    }

    private String performDelete(String objectFqn, IRefactoring refactoring)
    {
        try
        {
            refactoring.perform();
            return ToolResult.success()
                .put("action", "executed") //$NON-NLS-1$ //$NON-NLS-2$
                .put("objectFqn", objectFqn) //$NON-NLS-1$
                .put("message", "Delete refactoring completed successfully.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error performing delete refactoring", e); //$NON-NLS-1$
            return ToolResult.error("Delete failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Resolves a metadata object from its fully qualified name (FQN).
     * Uses {@link MetadataTypeUtils#findObject(Configuration, String, String)}
     * to locate the top-level object, then traverses nested metadata objects
     * via {@link #findChild(MdObject, String, String)} to resolve deeper paths.
     * Supports both top-level (e.g. 'Catalog.Products') and nested objects
     * (e.g. 'Document.SalesOrder.Attribute.Amount').
     */
    private MdObject resolveObject(Configuration config, String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }

        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (!isValidFqnArity(parts.length))
        {
            // A malformed nested FQN (an odd trailing token) must NOT fall through
            // to the parent object: that would delete a broader object than asked.
            // Return the same "not found" outcome the caller already handles.
            return null;
        }

        // Find top-level object
        MdObject topObject = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (topObject == null || parts.length == 2)
        {
            return topObject;
        }

        // Navigate nested path
        MdObject current = topObject;
        for (int i = 2; i + 1 < parts.length; i += 2)
        {
            String childType = parts[i];
            String childName = parts[i + 1];
            MdObject child = findChild(current, childType, childName);
            if (child == null)
            {
                return null;
            }
            current = child;
        }
        return current;
    }

    /**
     * Checks that an FQN's dot-separated part count is a valid arity.
     * <p>
     * A top-level FQN is {@code Type.Name} (2 parts); each nested level adds a
     * complete {@code .ChildType.ChildName} pair. Any other count (notably an
     * odd trailing token after the leading {@code Type.Name}) is malformed and
     * must be rejected so a nested delete cannot silently fall back to the
     * parent object.
     *
     * @param partCount number of dot-separated tokens in the FQN
     * @return {@code true} for 2, 4, 6, ... parts; {@code false} otherwise
     */
    static boolean isValidFqnArity(int partCount)
    {
        return partCount >= 2 && (partCount - 2) % 2 == 0;
    }

    @SuppressWarnings("unchecked")
    private MdObject findChild(MdObject parent, String childType, String childName)
    {
        String type = childType.toLowerCase();

        String getterName = null;
        if ("attribute".equals(type) || "attributes".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "\u0440\u0435\u043a\u0432\u0438\u0437\u0438\u0442".equals(type) //$NON-NLS-1$
            || "\u0440\u0435\u043a\u0432\u0438\u0437\u0438\u0442\u044b".equals(type)) //$NON-NLS-1$
        {
            getterName = "getAttributes"; //$NON-NLS-1$
        }
        else if ("tabularsection".equals(type) || "tabularsections".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "\u0442\u0430\u0431\u043b\u0438\u0447\u043d\u0430\u044f\u0447\u0430\u0441\u0442\u044c".equals(type) //$NON-NLS-1$
            || "\u0442\u0430\u0431\u043b\u0438\u0447\u043d\u044b\u0435\u0447\u0430\u0441\u0442\u0438".equals(type)) //$NON-NLS-1$
        {
            getterName = "getTabularSections"; //$NON-NLS-1$
        }
        else if ("dimension".equals(type) || "dimensions".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "\u0438\u0437\u043c\u0435\u0440\u0435\u043d\u0438\u0435".equals(type) //$NON-NLS-1$
            || "\u0438\u0437\u043c\u0435\u0440\u0435\u043d\u0438\u044f".equals(type)) //$NON-NLS-1$
        {
            getterName = "getDimensions"; //$NON-NLS-1$
        }
        else if ("resource".equals(type) || "resources".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "\u0440\u0435\u0441\u0443\u0440\u0441".equals(type) //$NON-NLS-1$
            || "\u0440\u0435\u0441\u0443\u0440\u0441\u044b".equals(type)) //$NON-NLS-1$
        {
            getterName = "getResources"; //$NON-NLS-1$
        }

        if (getterName == null)
        {
            return null;
        }

        try
        {
            java.lang.reflect.Method method = parent.getClass().getMethod(getterName);
            Object result = method.invoke(parent);
            if (result instanceof org.eclipse.emf.common.util.EList)
            {
                org.eclipse.emf.common.util.EList<? extends MdObject> children =
                    (org.eclipse.emf.common.util.EList<? extends MdObject>) result;
                for (MdObject child : children)
                {
                    if (childName.equalsIgnoreCase(child.getName()))
                    {
                        return child;
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error finding child " + childType + "." + childName, e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }
}
