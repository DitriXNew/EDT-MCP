/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;

import com._1c.g5.v8.dt.refactoring.core.CleanReferenceProblem;
import com._1c.g5.v8.dt.refactoring.core.INativeChangeRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to rename a metadata object or attribute with full refactoring support.
 * 
 * Two-phase workflow:
 * 1. Preview mode (confirm=false, default): Returns list of affected refactoring items and problems.
 * 2. Execute mode (confirm=true): Performs the rename with all cascading code updates.
 */
public class RenameMetadataObjectTool implements IMcpTool
{
    public static final String NAME = "rename_metadata_object"; //$NON-NLS-1$

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
        String confirmStr = JsonUtils.extractStringArgument(params, "confirm"); //$NON-NLS-1$
        String disableIndicesStr = JsonUtils.extractStringArgument(params, "disableIndices"); //$NON-NLS-1$
        boolean confirm = "true".equalsIgnoreCase(confirmStr); //$NON-NLS-1$

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

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required. " + //$NON-NLS-1$
                "Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}"; //$NON-NLS-1$
        }
        if (objectFqn == null || objectFqn.isEmpty())
        {
            return "Error: objectFqn is required. " + //$NON-NLS-1$
                "Examples: 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount', " + //$NON-NLS-1$
                "'Catalog.Products.TabularSection.Prices'"; //$NON-NLS-1$
        }
        if (newName == null || newName.isEmpty())
        {
            return "Error: newName is required. " + //$NON-NLS-1$
                "Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}"; //$NON-NLS-1$
        }

        final java.util.Set<Integer> finalDisableIndices = disableIndices;
        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(executeInternal(projectName, objectFqn, newName, confirm, finalDisableIndices));
            }
            catch (Exception e)
            {
                Activator.logError("Error in rename_metadata_object", e); //$NON-NLS-1$
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
            }
        });

        return resultRef.get();
    }

    private String executeInternal(String projectName, String objectFqn, String newName,
        boolean confirm, java.util.Set<Integer> disableIndices)
    {
        // Get project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        // Get configuration
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return "Error: Configuration provider not available"; //$NON-NLS-1$
        }
        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return "Error: Could not get configuration for project: " + projectName; //$NON-NLS-1$
        }

        // Get refactoring service
        IMdRefactoringService refactoringService = Activator.getDefault().getMdRefactoringService();
        if (refactoringService == null)
        {
            return "Error: IMdRefactoringService not available"; //$NON-NLS-1$
        }

        // Normalize and find the object
        objectFqn = MetadataTypeUtils.normalizeFqn(objectFqn);
        MdObject targetObject = resolveObject(config, objectFqn);
        if (targetObject == null)
        {
            return "Error: Object not found: " + objectFqn + ". " + //$NON-NLS-1$
                "Check the FQN format: 'Type.Name' for top-level objects (e.g. 'Catalog.Products'), " + //$NON-NLS-1$
                "'Type.Name.ChildType.ChildName' for nested (e.g. 'Document.Order.Attribute.Amount'). " + //$NON-NLS-1$
                "Supported child types: Attribute, TabularSection, Dimension, Resource."; //$NON-NLS-1$
        }

        // Create refactoring (returns collection because it may also rename in extension projects)
        Collection<IRefactoring> refactorings = refactoringService.createMdObjectRenameRefactoring(targetObject, newName);
        if (refactorings == null || refactorings.isEmpty())
        {
            return "Error: Failed to create rename refactoring for: " + objectFqn; //$NON-NLS-1$
        }

        if (!confirm)
        {
            // Preview mode - collect all items and problems
            return buildPreview(objectFqn, newName, refactorings);
        }
        else
        {
            // Execute mode - perform the rename, applying any disabled indices
            return performRename(objectFqn, newName, refactorings, disableIndices);
        }
    }

    /**
     * Builds the preview response: markdown with YAML frontmatter and a table of all change points.
     * Each change has: index, type (rename/bslRef), file (for bsl), description, enabled state.
     * The AI reviews this list and can specify disableIndices when confirming.
     */
    private String buildPreview(String objectFqn, String newName, Collection<IRefactoring> refactorings)
    {
        // Phase 1: collect all changes and problems
        List<ChangePoint> allChanges = new ArrayList<>();
        List<String> allProblems = new ArrayList<>();
        int[] indexCounter = {0};

        for (IRefactoring refactoring : refactorings)
        {
            String title = refactoring.getTitle();

            Collection<IRefactoringItem> items = refactoring.getItems();
            if (items != null)
            {
                for (IRefactoringItem item : items)
                {
                    if (item instanceof INativeChangeRefactoringItem nativeItem)
                    {
                        Change nativeChange = nativeItem.getNativeChange();
                        if (nativeChange != null)
                        {
                            collectFlatChanges(nativeChange, null, allChanges, indexCounter, title, item.isOptional());
                        }
                    }
                    else
                    {
                        allChanges.add(new ChangePoint(
                            indexCounter[0]++, "rename", null, //$NON-NLS-1$
                            item.getName(), item.isOptional(), item.isChecked(), title));
                    }
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
                        StringBuilder pb = new StringBuilder();
                        if (problem instanceof CleanReferenceProblem crp)
                        {
                            org.eclipse.emf.ecore.EObject refObj = crp.getReferencingObject();
                            if (refObj instanceof IBmObject bmObj)
                            {
                                pb.append(bmObj.bmGetFqn());
                            }
                            org.eclipse.emf.ecore.EStructuralFeature feat = crp.getReference();
                            if (feat != null)
                            {
                                pb.append(" → ").append(feat.getName()); //$NON-NLS-1$
                            }
                        }
                        org.eclipse.emf.ecore.EObject obj = problem.getObject();
                        if (obj instanceof IBmObject bmObj)
                        {
                            if (pb.length() > 0) pb.append(" | "); //$NON-NLS-1$
                            pb.append(bmObj.bmGetFqn());
                        }
                        allProblems.add(pb.toString());
                    }
                }
            }
        }

        long enabledCount = allChanges.stream().filter(c -> c.enabled).count();

        // Phase 2: build markdown with YAML frontmatter
        StringBuilder sb = new StringBuilder();
        sb.append("---\n"); //$NON-NLS-1$
        sb.append("action: preview\n"); //$NON-NLS-1$
        sb.append("objectFqn: ").append(objectFqn).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("newName: ").append(newName).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("totalChanges: ").append(allChanges.size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("enabledChanges: ").append(enabledCount).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("problems: ").append(allProblems.size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("---\n\n"); //$NON-NLS-1$

        sb.append("# Refactoring Preview: Rename `").append(objectFqn) //$NON-NLS-1$
          .append("` → `").append(newName).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        sb.append("**Total change points:** ").append(allChanges.size()) //$NON-NLS-1$
          .append(" | **Enabled by default:** ").append(enabledCount).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Change points table
        sb.append("## Change Points\n\n"); //$NON-NLS-1$
        sb.append("| # | Type | Default | Skippable | File | Description |\n"); //$NON-NLS-1$
        sb.append("|---|------|---------|-----------|------|-------------|\n"); //$NON-NLS-1$
        for (ChangePoint cp : allChanges)
        {
            String enabledMark = cp.enabled ? "\u2705" : "\u274c"; //$NON-NLS-1$ //$NON-NLS-2$ // ✅ ❌
            String optionalMark = cp.optional ? "yes" : "no"; //$NON-NLS-1$ //$NON-NLS-2$
            String file = cp.file != null ? escapeMarkdownCell(cp.file) : "\u2014"; //$NON-NLS-1$ // —
            String desc = cp.description != null ? escapeMarkdownCell(cp.description) : ""; //$NON-NLS-1$
            sb.append("| ").append(cp.index) //$NON-NLS-1$
              .append(" | ").append(cp.type) //$NON-NLS-1$
              .append(" | ").append(enabledMark) //$NON-NLS-1$
              .append(" | ").append(optionalMark) //$NON-NLS-1$
              .append(" | ").append(file) //$NON-NLS-1$
              .append(" | ").append(desc) //$NON-NLS-1$
              .append(" |\n"); //$NON-NLS-1$
        }

        sb.append("\n"); //$NON-NLS-1$

        // Problems section
        if (!allProblems.isEmpty())
        {
            sb.append("## Problems\n\n"); //$NON-NLS-1$
            for (String p : allProblems)
            {
                sb.append("- ").append(p).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append("\n"); //$NON-NLS-1$
        }

        sb.append("> To execute, call with `confirm=true`.\n"); //$NON-NLS-1$
        sb.append("> Use `disableIndices='1,2,3'` to skip specific change points (optional changes only).\n"); //$NON-NLS-1$

        return sb.toString();
    }

    /** Simple data holder for a single change point in preview. */
    private static class ChangePoint
    {
        final int index;
        final String type;
        final String file;
        final String description;
        final boolean optional;
        final boolean enabled;

        ChangePoint(int index, String type, String file, String description,
            boolean optional, boolean enabled, String ignored)
        {
            this.index = index;
            this.type = type;
            this.file = file;
            this.description = description;
            this.optional = optional;
            this.enabled = enabled;
        }
    }

    /** Escapes pipe characters in markdown table cells. */
    private static String escapeMarkdownCell(String s)
    {
        return s == null ? "" : s.replace("|", "\\|"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Recursively collects leaf changes from LTK change tree into a flat list with global indices.
     * Top-level CompositeChange names become the "file" field for their leaves.
     */
    private void collectFlatChanges(Change change, String currentFile,
        List<ChangePoint> result, int[] indexCounter, String refactoringTitle, boolean optional)
    {
        if (change instanceof CompositeChange composite)
        {
            Change[] children = composite.getChildren();
            if (children != null && children.length > 0)
            {
                // Use this composite's name as the file context if it's meaningful
                String name = composite.getName();
                String fileContext = (name != null && !name.isEmpty()) ? name : currentFile;
                for (Change child : children)
                {
                    collectFlatChanges(child, fileContext, result, indexCounter, refactoringTitle, optional);
                }
            }
            // Skip empty composites
        }
        else
        {
            // Leaf change
            result.add(new ChangePoint(
                indexCounter[0]++, "bslRef", currentFile, //$NON-NLS-1$
                change.getName(), optional, change.isEnabled(), refactoringTitle));
        }
    }

    private String performRename(String objectFqn, String newName,
        Collection<IRefactoring> refactorings, java.util.Set<Integer> disableIndices)
    {
        // Apply disableIndices by traversing items and their native changes
        if (!disableIndices.isEmpty())
        {
            int[] indexCounter = {0};
            for (IRefactoring refactoring : refactorings)
            {
                Collection<IRefactoringItem> items = refactoring.getItems();
                if (items == null)
                    continue;
                for (IRefactoringItem item : items)
                {
                    if (item instanceof INativeChangeRefactoringItem nativeItem)
                    {
                        Change nativeChange = nativeItem.getNativeChange();
                        if (nativeChange != null)
                        {
                            applyDisableToChange(nativeChange, disableIndices, indexCounter);
                        }
                        // If all leaf changes under this native item are disabled, uncheck the item itself
                        if (nativeItem.isOptional() && isCompletelyDisabled(nativeChange))
                        {
                            nativeItem.setChecked(false);
                        }
                    }
                    else
                    {
                        // Regular rename item — not skippable (non-optional), just advance index
                        indexCounter[0]++;
                    }
                }
            }
        }

        List<String> performed = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (IRefactoring refactoring : refactorings)
        {
            try
            {
                refactoring.perform();
                performed.add(refactoring.getTitle());
            }
            catch (Exception e)
            {
                Activator.logError("Error performing rename refactoring: " + refactoring.getTitle(), e); //$NON-NLS-1$
                errors.add(refactoring.getTitle() + ": " + e.getMessage()); //$NON-NLS-1$
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("---\n"); //$NON-NLS-1$
        sb.append("action: executed\n"); //$NON-NLS-1$
        sb.append("objectFqn: ").append(objectFqn).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("newName: ").append(newName).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("disabledCount: ").append(disableIndices.size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("performedCount: ").append(performed.size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("errors: ").append(errors.size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("---\n\n"); //$NON-NLS-1$

        sb.append("# Rename Completed: `").append(objectFqn) //$NON-NLS-1$
          .append("` → `").append(newName).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (!performed.isEmpty())
        {
            sb.append("## Performed\n\n"); //$NON-NLS-1$
            for (String p : performed)
            {
                sb.append("- ").append(p).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append("\n"); //$NON-NLS-1$
        }

        if (!errors.isEmpty())
        {
            sb.append("## Errors\n\n"); //$NON-NLS-1$
            for (String e : errors)
            {
                sb.append("- ").append(e).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append("\n"); //$NON-NLS-1$
        }

        if (!disableIndices.isEmpty())
        {
            sb.append("_").append(disableIndices.size()) //$NON-NLS-1$
              .append(" change point(s) were skipped as requested._\n"); //$NON-NLS-1$
        }

        return sb.toString();
    }

    /**
     * Recursively walks the LTK change tree and calls setEnabled(false) on leaves
     * whose global index is in the disableIndices set.
     */
    private void applyDisableToChange(Change change, java.util.Set<Integer> disableIndices, int[] indexCounter)
    {
        if (change instanceof CompositeChange composite)
        {
            Change[] children = composite.getChildren();
            if (children != null)
            {
                for (Change child : children)
                {
                    applyDisableToChange(child, disableIndices, indexCounter);
                }
            }
        }
        else
        {
            int idx = indexCounter[0]++;
            if (disableIndices.contains(idx))
            {
                change.setEnabled(false);
            }
        }
    }

    /**
     * Returns true if all leaf changes under the given change are disabled.
     */
    private boolean isCompletelyDisabled(Change change)
    {
        if (change instanceof CompositeChange composite)
        {
            Change[] children = composite.getChildren();
            if (children == null || children.length == 0)
                return true;
            for (Change child : children)
            {
                if (!isCompletelyDisabled(child))
                    return false;
            }
            return true;
        }
        return !change.isEnabled();
    }

    /**
     * Resolves a metadata object from FQN.
     * Supports both top-level objects (Catalog.Products) and nested objects
     * (Document.SalesOrder.Attribute.Amount, Catalog.Products.TabularSection.Prices).
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

        // Find top-level object: Type.Name
        MdObject topObject = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (topObject == null || parts.length == 2)
        {
            return topObject;
        }

        // Navigate nested: Type.Name.ChildType.ChildName
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
     * Finds a child MdObject within a parent by type and name.
     * Supports: Attribute, TabularSection.
     */
    @SuppressWarnings("unchecked")
    private MdObject findChild(MdObject parent, String childType, String childName)
    {
        String type = childType.toLowerCase();

        // Determine which getter to use based on child type
        String getterName = null;
        if ("attribute".equals(type) || "attributes".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "\u0440\u0435\u043a\u0432\u0438\u0437\u0438\u0442".equals(type) //$NON-NLS-1$ // реквизит
            || "\u0440\u0435\u043a\u0432\u0438\u0437\u0438\u0442\u044b".equals(type)) //$NON-NLS-1$ // реквизиты
        {
            getterName = "getAttributes"; //$NON-NLS-1$
        }
        else if ("tabularsection".equals(type) || "tabularsections".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "\u0442\u0430\u0431\u043b\u0438\u0447\u043d\u0430\u044f\u0447\u0430\u0441\u0442\u044c".equals(type) //$NON-NLS-1$ // табличнаячасть
            || "\u0442\u0430\u0431\u043b\u0438\u0447\u043d\u044b\u0435\u0447\u0430\u0441\u0442\u0438".equals(type)) //$NON-NLS-1$ // табличныечасти
        {
            getterName = "getTabularSections"; //$NON-NLS-1$
        }
        else if ("dimension".equals(type) || "dimensions".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "\u0438\u0437\u043c\u0435\u0440\u0435\u043d\u0438\u0435".equals(type) //$NON-NLS-1$ // измерение
            || "\u0438\u0437\u043c\u0435\u0440\u0435\u043d\u0438\u044f".equals(type)) //$NON-NLS-1$ // измерения
        {
            getterName = "getDimensions"; //$NON-NLS-1$
        }
        else if ("resource".equals(type) || "resources".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "\u0440\u0435\u0441\u0443\u0440\u0441".equals(type) //$NON-NLS-1$ // ресурс
            || "\u0440\u0435\u0441\u0443\u0440\u0441\u044b".equals(type)) //$NON-NLS-1$ // ресурсы
        {
            getterName = "getResources"; //$NON-NLS-1$
        }

        if (getterName == null)
        {
            return null;
        }

        // Use EMF reflection to get the child collection
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
