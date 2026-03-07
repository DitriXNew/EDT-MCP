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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
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
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to delete a metadata object or attribute with full refactoring support.
 * 
 * Two-phase workflow:
 * 1. Preview mode (confirm=false, default): Returns list of affected references and problems.
 * 2. Execute mode (confirm=true): Performs the deletion with reference cleanup.
 */
public class DeleteMetadataObjectTool implements IMcpTool
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
        return "Delete a metadata object or attribute with full refactoring support. " + //$NON-NLS-1$
               "Cleans up all references in BSL code, forms, and other metadata. " + //$NON-NLS-1$
               "First call without confirm to preview affected locations, then call with confirm=true to apply. " + //$NON-NLS-1$
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
                "FQN of the object to delete " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount'). " + //$NON-NLS-1$
                "Russian names supported.", true) //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "Set to true to execute the deletion. " + //$NON-NLS-1$
                "Default false = preview only.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required. " + //$NON-NLS-1$
                "Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products'}").toJson(); //$NON-NLS-1$
        }
        if (objectFqn == null || objectFqn.isEmpty())
        {
            return ToolResult.error("objectFqn is required. " + //$NON-NLS-1$
                "Examples: 'Catalog.Products' (delete whole catalog), " + //$NON-NLS-1$
                "'Document.SalesOrder.Attribute.Amount' (delete attribute), " + //$NON-NLS-1$
                "'Catalog.Products.TabularSection.Prices' (delete tabular section)").toJson(); //$NON-NLS-1$
        }

        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(executeInternal(projectName, objectFqn, confirm));
            }
            catch (Exception e)
            {
                Activator.logError("Error in delete_metadata_object", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        return resultRef.get();
    }

    private String executeInternal(String projectName, String objectFqn, boolean confirm)
    {
        // Get project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Get configuration
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return ToolResult.error("Configuration provider not available").toJson(); //$NON-NLS-1$
        }
        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return ToolResult.error("Could not get configuration for project: " + projectName).toJson(); //$NON-NLS-1$
        }

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
        if (parts.length < 2)
        {
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
