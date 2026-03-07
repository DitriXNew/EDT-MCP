/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext;
import org.eclipse.text.edits.TextEdit;

import com._1c.g5.v8.dt.refactoring.core.ltk.BmObjectTextContentCompositeChange;
import com._1c.g5.v8.dt.refactoring.core.ltk.BmObjectTextContentChange;
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
        String confirmStr = JsonUtils.extractStringArgument(params, "confirm"); //$NON-NLS-1$
        String disableIndicesStr = JsonUtils.extractStringArgument(params, "disableIndices"); //$NON-NLS-1$
        boolean confirm = "true".equalsIgnoreCase(confirmStr); //$NON-NLS-1$
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
                resultRef.set(executeInternal(projectName, objectFqn, newName, confirm, finalDisableIndices, maxResults));
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
        boolean confirm, java.util.Set<Integer> disableIndices, int maxResults)
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
            return buildPreview(project, objectFqn, newName, targetObject, refactorings, maxResults);
        }
        else
        {
            // Execute mode - perform the rename, applying any disabled indices
            return performRename(objectFqn, newName, refactorings, disableIndices);
        }
    }

    /**
     * Builds the preview response: markdown with YAML frontmatter, change points table with line
     * numbers, and code context snippets (±3 lines + containing method name).
     */
    private String buildPreview(IProject project, String objectFqn, String newName, MdObject targetObject,
        Collection<IRefactoring> refactorings, int maxResults)
    {
        Map<String, ExactMatchInfo> exactMatches = buildExactMatchInfo(project, targetObject, newName);
        List<ChangePoint> edtBslPreviewChanges = buildEdtBslPreviewChanges(project, targetObject, newName, exactMatches);
        String oldName = targetObject.getName();

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
                            collectFlatChanges(nativeChange, null, null, exactMatches, allChanges, indexCounter, title,
                                item.isOptional(), oldName);
                        }
                    }
                    else
                    {
                        allChanges.add(new ChangePoint(
                            indexCounter[0]++, "rename", null, null, //$NON-NLS-1$
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
                                pb.append(" \u2192 ").append(feat.getName()); //$NON-NLS-1$
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

        applyEdtBslPreviewData(allChanges, edtBslPreviewChanges);

        long enabledCount = allChanges.stream().filter(c -> c.enabled).count();
        int shown = (maxResults > 0) ? Math.min(allChanges.size(), maxResults) : allChanges.size();

        // Phase 2: build markdown with YAML frontmatter
        StringBuilder sb = new StringBuilder();
        sb.append("---\n"); //$NON-NLS-1$
        sb.append("action: preview\n"); //$NON-NLS-1$
        sb.append("objectFqn: ").append(objectFqn).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("newName: ").append(newName).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("totalChanges: ").append(allChanges.size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("enabledChanges: ").append(enabledCount).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("problems: ").append(allProblems.size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("debugExactMatches: ").append(exactMatches.size()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("---\n\n"); //$NON-NLS-1$

        sb.append("# Refactoring Preview: Rename `").append(objectFqn) //$NON-NLS-1$
          .append("` \u2192 `").append(newName).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        sb.append("**Total change points:** ").append(allChanges.size()) //$NON-NLS-1$
          .append(" | **Enabled by default:** ").append(enabledCount).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (allChanges.size() > shown)
        {
            sb.append("_Showing ").append(shown).append(" of ").append(allChanges.size()) //$NON-NLS-1$ //$NON-NLS-2$
              .append(" changes. Pass `maxResults=").append(allChanges.size()) //$NON-NLS-1$
              .append("` to see all._\n\n"); //$NON-NLS-1$
        }

        // Change points table
        sb.append("## Change Points\n\n"); //$NON-NLS-1$
                sb.append("| # | Type | Description | Line | Col | Default | Skippable | Project | FQN |\n"); //$NON-NLS-1$
                sb.append("|---|------|-------------|------|-----|---------|-----------|---------|-----|\n"); //$NON-NLS-1$
        for (int i = 0; i < shown; i++)
        {
            ChangePoint cp = allChanges.get(i);
            String enabledMark = cp.enabled ? "\u2705" : "\u274c"; //$NON-NLS-1$ //$NON-NLS-2$
            String optionalMark = cp.optional ? "yes" : "no"; //$NON-NLS-1$ //$NON-NLS-2$
            String fqnCell = cp.fqn != null ? escapeMarkdownCell(cp.fqn) : "\u2014"; //$NON-NLS-1$
            String projectCell = cp.project != null ? escapeMarkdownCell(cp.project) : "\u2014"; //$NON-NLS-1$
            String line = cp.lineNumber > 0 ? String.valueOf(cp.lineNumber) : "\u2014"; //$NON-NLS-1$
                        String column = cp.columnNumber > 0 ? String.valueOf(cp.columnNumber) : "\u2014"; //$NON-NLS-1$
            String description = cp.description != null ? escapeMarkdownCell(cp.description) : "\u2014"; //$NON-NLS-1$
            sb.append("| ").append(cp.index) //$NON-NLS-1$
              .append(" | ").append(cp.type) //$NON-NLS-1$
              .append(" | ").append(description) //$NON-NLS-1$
              .append(" | ").append(line) //$NON-NLS-1$
                            .append(" | ").append(column) //$NON-NLS-1$
              .append(" | ").append(enabledMark) //$NON-NLS-1$
              .append(" | ").append(optionalMark) //$NON-NLS-1$
              .append(" | ").append(projectCell) //$NON-NLS-1$
              .append(" | ").append(fqnCell) //$NON-NLS-1$
              .append(" |\n"); //$NON-NLS-1$
        }
        if (allChanges.size() > shown)
        {
                        sb.append("| ... | | | | | | | | _").append(allChanges.size() - shown) //$NON-NLS-1$
              .append(" more_ |\n"); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$

        // Code context section
        boolean hasContext = false;
        for (int i = 0; i < shown; i++)
        {
            if (allChanges.get(i).codeContext != null)
            {
                hasContext = true;
                break;
            }
        }
        if (hasContext)
        {
            sb.append("## Code Context\n\n"); //$NON-NLS-1$
            for (int i = 0; i < shown; i++)
            {
                ChangePoint cp = allChanges.get(i);
                if (cp.codeContext == null)
                    continue;
                sb.append("### #").append(cp.index); //$NON-NLS-1$
                if (cp.methodName != null)
                    sb.append(" \u2014 `").append(escapeMarkdownCell(cp.methodName)).append("`"); //$NON-NLS-1$ //$NON-NLS-2$
                if (cp.fqn != null && cp.lineNumber > 0)
                    sb.append(" \u00b7 ").append(escapeMarkdownCell(cp.fqn)) //$NON-NLS-1$
                      .append(":").append(cp.lineNumber); //$NON-NLS-1$
                sb.append("\n```bsl\n").append(cp.codeContext).append("```\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

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
        final String fqn;
        final String project;
        final String description;
        final boolean optional;
        final boolean enabled;
        final int lineNumber;
        final int columnNumber;
        final String codeContext;
        final String methodName;

        ChangePoint(int index, String type, String fqn, String project, String description,
            boolean optional, boolean enabled, String ignored)
        {
            this(index, type, fqn, project, description, optional, enabled, ignored, -1, -1, null, null);
        }

        ChangePoint(int index, String type, String fqn, String project, String description,
            boolean optional, boolean enabled, String ignored,
            int lineNumber, int columnNumber, String codeContext, String methodName)
        {
            this.index = index;
            this.type = type;
            this.fqn = fqn;
            this.project = project;
            this.description = description;
            this.optional = optional;
            this.enabled = enabled;
            this.lineNumber = lineNumber;
            this.columnNumber = columnNumber;
            this.codeContext = codeContext;
            this.methodName = methodName;
        }
    }

    private static class ExactMatchInfo
    {
        final String filePath;
        final int matchOffset;
        final int lineNumber;
        final int columnNumber;
        final String codeContext;
        final String methodName;
        final String fqn;
        final String project;

        ExactMatchInfo(String filePath, int matchOffset, int lineNumber, int columnNumber, String codeContext,
            String methodName, String fqn, String project)
        {
            this.filePath = filePath;
            this.matchOffset = matchOffset;
            this.lineNumber = lineNumber;
            this.columnNumber = columnNumber;
            this.codeContext = codeContext;
            this.methodName = methodName;
            this.fqn = fqn;
            this.project = project;
        }
    }

    /** Escapes pipe characters in markdown table cells. */
    private static String escapeMarkdownCell(String s)
    {
        return s == null ? "" : s.replace("|", "\\|"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Recursively collects leaf changes from LTK change tree into a flat list with global indices.
     * Extracts line number and code context (±3 lines + containing method) for TextChange leaves.
     */
    private void collectFlatChanges(Change change, String currentFqn, String currentProject,
        Map<String, ExactMatchInfo> exactMatches, List<ChangePoint> result, int[] indexCounter,
        String refactoringTitle, boolean optional, String oldName)
    {
        if (change instanceof BmObjectTextContentCompositeChange<?> bmComposite)
        {
            currentProject = bmComposite.getProjectName();
            Object modifiedElement = bmComposite.getModifiedElement();
            if (modifiedElement instanceof IBmObject bmObj)
            {
                currentFqn = bmObj.bmGetFqn();
            }
        }
        if (change instanceof CompositeChange composite)
        {
            Change[] children = composite.getChildren();
            if (children != null && children.length > 0)
            {
                for (Change child : children)
                {
                    collectFlatChanges(child, currentFqn, currentProject, exactMatches, result, indexCounter,
                        refactoringTitle, optional, oldName);
                }
            }
        }
        else
        {
            boolean hasExactMatches = exactMatches != null && !exactMatches.isEmpty();
            boolean isBslReferenceChange = isBslReferenceChange(change, currentFqn);
            boolean isFullTextSearchChange = isFullTextSearchSourceFileChange(change);
            boolean addedFallbackChange = false;
            int lineNumber = -1;
            int columnNumber = -1;
            String codeContext = null;
            String methodName = null;
            String fqn = currentFqn;
            String project = currentProject;
            List<ExactMatchInfo> exactMatchInfos = findExactMatchInfos(change, exactMatches);
            logPreviewMapping(change, fqn, project, exactMatchInfos.size());
            if (!exactMatchInfos.isEmpty())
            {
                for (ExactMatchInfo exactMatch : exactMatchInfos)
                {
                    String exactFqn = exactMatch.fqn != null ? exactMatch.fqn : fqn;
                    String exactProject = exactMatch.project != null ? exactMatch.project : project;
                    result.add(new ChangePoint(
                        indexCounter[0]++, "bslRef", exactFqn, exactProject, //$NON-NLS-1$
                        change.getName(), optional, change.isEnabled(), refactoringTitle,
                        exactMatch.lineNumber, exactMatch.columnNumber, exactMatch.codeContext, exactMatch.methodName));
                }
                return;
            }
            else if (change instanceof BmObjectTextContentChange<?> bmChange)
            {
                try
                {
                    project = bmChange.getProjectName();
                    Object modifiedElement = bmChange.getModifiedElement();
                    EObject bmObj = modifiedElement instanceof EObject ? (EObject) modifiedElement : null;
                    if (modifiedElement instanceof IBmObject ibm)
                    {
                        fqn = ibm.bmGetFqn();
                    }
                    String content = bmChange.getCurrentContent(new NullProgressMonitor());
                    TextEdit edit = bmChange.getEdit();
                    if (content != null && !content.isEmpty() && edit != null)
                    {
                        List<TextEdit> leafEdits = getLeafEdits(edit);
                        if (!leafEdits.isEmpty())
                        {
                            for (TextEdit leafEdit : leafEdits)
                            {
                                int matchedLineNumber = computeLineNumber(content, leafEdit.getOffset());
                                int matchedColumnNumber = computeColumnNumber(content, leafEdit.getOffset());
                                String matchedCodeContext = extractContext(content, matchedLineNumber);
                                String matchedMethodName = null;
                                if (bmObj instanceof Module module)
                                {
                                    matchedMethodName = findContainingMethodAst(module, matchedLineNumber);
                                }
                                if (matchedMethodName == null)
                                {
                                    matchedMethodName = findContainingMethodText(content, matchedLineNumber);
                                }
                                result.add(new ChangePoint(
                                    indexCounter[0]++, "bslRef", fqn, project, //$NON-NLS-1$
                                    change.getName(), optional, change.isEnabled(), refactoringTitle,
                                    matchedLineNumber, matchedColumnNumber, matchedCodeContext, matchedMethodName));
                            }
                            addedFallbackChange = true;
                        }
                    }
                }
                catch (Exception e)
                {
                    Activator.logError("Error extracting BSL change location", e); //$NON-NLS-1$
                }
            }
            else
            {
                try
                {
                    IFile file = getIFile(change);
                    if (file != null)
                    {
                        project = file.getProject().getName();
                        String resolvedFqn = getBslFqn(file);
                        if (resolvedFqn != null && !resolvedFqn.isEmpty())
                        {
                            fqn = resolvedFqn;
                        }
                        String content = BslModuleUtils.readFileText(file);
                        TextEdit edit = getChangeEdit(change);
                        if (content != null && !content.isEmpty() && edit != null)
                        {
                            List<TextEdit> leafEdits = getLeafEdits(edit);
                            if (!leafEdits.isEmpty())
                            {
                                Module module = BslModuleUtils.loadModule(file.getProject(), BslModuleUtils.extractModulePath(file.getFullPath().toString()));
                                for (TextEdit leafEdit : leafEdits)
                                {
                                    int matchedLineNumber = computeLineNumber(content, leafEdit.getOffset());
                                    int matchedColumnNumber = computeColumnNumber(content, leafEdit.getOffset());
                                    String matchedCodeContext = extractContext(content, matchedLineNumber);
                                    String matchedMethodName = null;
                                    if (module != null)
                                    {
                                        matchedMethodName = findContainingMethodAst(module, matchedLineNumber);
                                    }
                                    if (matchedMethodName == null)
                                    {
                                        matchedMethodName = findContainingMethodText(content, matchedLineNumber);
                                    }
                                    result.add(new ChangePoint(
                                        indexCounter[0]++, "bslRef", fqn, project, //$NON-NLS-1$
                                        change.getName(), optional, change.isEnabled(), refactoringTitle,
                                        matchedLineNumber, matchedColumnNumber, matchedCodeContext, matchedMethodName));
                                }
                                addedFallbackChange = true;
                            }
                        }
                    }
                }
                catch (Exception e)
                {
                    Activator.logError("Error extracting source file change location", e); //$NON-NLS-1$
                }
            }

            if (addedFallbackChange)
            {
                return;
            }

            if (hasExactMatches && isBslReferenceChange && isFullTextSearchChange)
            {
                return;
            }

            result.add(new ChangePoint(
                indexCounter[0]++, "bslRef", fqn, project, //$NON-NLS-1$
                change.getName(), optional, change.isEnabled(), refactoringTitle,
                lineNumber, columnNumber, codeContext, methodName));
        }
    }

    private Map<String, ExactMatchInfo> buildExactMatchInfo(IProject project, MdObject targetObject, String newName)
    {
        try
        {
            Object bslInjector = getBslInjector();
            Object renameProvider = invokeMethod(bslInjector, "getInstance", new Class<?>[] {Class.class}, //$NON-NLS-1$
                getClassOrThrow("com._1c.g5.v8.dt.bsl.bm.ui.refactoring.BslBmRenameRefactoringProvider")); //$NON-NLS-1$
            Object renameContext = createRenameElementContext(targetObject);
            Object refactoring = invokeMethod(renameProvider, "getRenameRefactoring", new Class<?>[] { //$NON-NLS-1$
                getClassOrThrow("org.eclipse.xtext.ui.refactoring.ui.IRenameElementContext")}, renameContext); //$NON-NLS-1$
            Object processor = refactoring != null ? invokeNoArg(refactoring, "getProcessor") : null; //$NON-NLS-1$
            if (processor == null)
            {
                processor = invokeMethod(renameProvider, "getRenameProcessor", new Class<?>[] { //$NON-NLS-1$
                getClassOrThrow("org.eclipse.xtext.ui.refactoring.ui.IRenameElementContext")}, renameContext); //$NON-NLS-1$
            }
            if (processor == null)
            {
                Activator.logWarning("rename_metadata_object: exact match pipeline got null rename processor"); //$NON-NLS-1$
                return Map.of();
            }

            Change normalChange = createRenameChange(refactoring, processor, newName);
            NullProgressMonitor progressMonitor = new NullProgressMonitor();

            String oldName = (String) invokeMethod(processor, "getOriginalName", new Class<?>[0]); //$NON-NLS-1$
            EObject contextElement = (EObject) invokeMethod(processor, "getContextElement", new Class<?>[0]); //$NON-NLS-1$
            if (normalChange == null || oldName == null || contextElement == null)
            {
                Activator.logWarning("rename_metadata_object: exact match pipeline missing base state: " //$NON-NLS-1$
                    + "normalChange=" + (normalChange != null) + ", oldName=" + oldName //$NON-NLS-1$ //$NON-NLS-2$
                    + ", contextElement=" + (contextElement != null)); //$NON-NLS-1$
                return Map.of();
            }

            Object supplier = invokeMethod(bslInjector, "getInstance", new Class<?>[] {Class.class}, //$NON-NLS-1$
                getClassOrThrow("com._1c.g5.v8.dt.bsl.bm.ui.refactoring.BslTextSearchRefactoringSupplier")); //$NON-NLS-1$

            Object searchInjector = getSearchCoreInjector();
            Object factory = invokeMethod(searchInjector, "getInstance", new Class<?>[] {Class.class}, //$NON-NLS-1$
                getClassOrThrow("com._1c.g5.v8.dt.search.core.refactoring.TextSearchRefactoringParticipantFactory")); //$NON-NLS-1$
            Object participant = invokeMethod(factory, "create", new Class<?>[] {String.class, EObject.class, //$NON-NLS-1$
                getClassOrThrow("com._1c.g5.v8.dt.search.core.refactoring.ITextSearchRefactoringSupplier")}, //$NON-NLS-1$
                oldName, contextElement, supplier);

            Object collector = getClassOrThrow("com._1c.g5.v8.dt.search.core.refactoring.TextSearchRefactoringResultCollector") //$NON-NLS-1$
                .getConstructor(String.class)
                .newInstance(oldName);
            Object searchScopeSettings = getClassOrThrow("com._1c.g5.v8.dt.search.core.TextSearchScopeSettings") //$NON-NLS-1$
                .getConstructor()
                .newInstance();
            Object modules = getEnumConstant("com._1c.g5.v8.dt.search.core.SearchIn", "MODULES"); //$NON-NLS-1$ //$NON-NLS-2$
            Object dcs = getEnumConstant("com._1c.g5.v8.dt.search.core.SearchIn", "DCS"); //$NON-NLS-1$ //$NON-NLS-2$
            Object dynamicListQuery = getEnumConstant("com._1c.g5.v8.dt.search.core.SearchIn", "DYNAMIC_LIST_QUERY"); //$NON-NLS-1$ //$NON-NLS-2$
            invokeMethod(searchScopeSettings, "addSearchIn", new Class<?>[] {modules.getClass().arrayType()}, //$NON-NLS-1$
                (Object) arrayOf(modules.getClass(), modules, dcs, dynamicListQuery));
            @SuppressWarnings("unchecked")
            Collection<IProject> projects = (Collection<IProject>) invokeMethod(participant, "getProjects", //$NON-NLS-1$
                new Class<?>[] {IProject.class}, project);
            invokeMethod(searchScopeSettings, "addProjects", new Class<?>[] {Collection.class}, projects); //$NON-NLS-1$

            Object textSearchIndexProvider = getFieldValue(participant, "textSearchIndexProvider"); //$NON-NLS-1$
            Object managerRegistry = getFieldValue(participant, "managerRegistry"); //$NON-NLS-1$
            Object hostResourceManager = getFieldValue(participant, "hostResourceManager"); //$NON-NLS-1$

            @SuppressWarnings("unchecked")
            Collection<String> searchStrings = (Collection<String>) invokeMethod(supplier, "getSearchStrings", //$NON-NLS-1$
                new Class<?>[] {EObject.class, String.class}, contextElement, oldName);
            Class<?> textSearcherClass = getClassOrThrow("com._1c.g5.v8.dt.search.core.TextSearcher"); //$NON-NLS-1$
            Class<?> searchScopeClass = getClassOrThrow("com._1c.g5.v8.dt.search.core.TextSearchScopeSettings"); //$NON-NLS-1$
            Class<?> collectorClass = getClassOrThrow("com._1c.g5.v8.dt.search.core.ISearchResultCollector"); //$NON-NLS-1$
            Class<?> indexProviderClass = getClassOrThrow("com._1c.g5.v8.dt.search.core.text.ITextSearchIndexProvider"); //$NON-NLS-1$
            Class<?> propertyRegistryClass = getClassOrThrow("com._1c.g5.v8.dt.md.IExternalPropertyManagerRegistry"); //$NON-NLS-1$
            Class<?> hostResourceManagerClass = getClassOrThrow("com._1c.g5.v8.dt.core.platform.management.IDtHostResourceManager"); //$NON-NLS-1$
            for (String searchString : searchStrings)
            {
                Object searcher = textSearcherClass.getConstructor(String.class, boolean.class, searchScopeClass,
                    collectorClass, getClassOrThrow("com._1c.g5.v8.dt.core.platform.IBmModelManager"), //$NON-NLS-1$
                    indexProviderClass, propertyRegistryClass, hostResourceManagerClass)
                    .newInstance(searchString, false, searchScopeSettings, collector,
                        Activator.getDefault().getBmModelManager(), textSearchIndexProvider, managerRegistry,
                        hostResourceManager);
                invokeMethod(searcher, "search", //$NON-NLS-1$
                    new Class<?>[] {getClassOrThrow("org.eclipse.core.runtime.IProgressMonitor")}, progressMonitor); //$NON-NLS-1$
            }

            Collection<?> matches = (Collection<?>) invokeMethod(supplier, "getMatches", //$NON-NLS-1$
                new Class<?>[] {Change.class, getClassOrThrow("com._1c.g5.v8.dt.search.core.SimpleSearchResultCollector")}, //$NON-NLS-1$
                normalChange, collector);
            Map<String, ExactMatchInfo> exactMatchMap = toExactMatchMap(matches);
            return exactMatchMap;
        }
        catch (Exception e)
        {
            Activator.logError("Error collecting exact rename matches", e); //$NON-NLS-1$
            return Map.of();
        }
    }

    private List<ChangePoint> buildEdtBslPreviewChanges(IProject project, MdObject targetObject, String newName,
        Map<String, ExactMatchInfo> exactMatches)
    {
        try
        {
            Object bslInjector = getBslInjector();
            Object renameProvider = invokeMethod(bslInjector, "getInstance", new Class<?>[] {Class.class}, //$NON-NLS-1$
                getClassOrThrow("com._1c.g5.v8.dt.bsl.bm.ui.refactoring.BslBmRenameRefactoringProvider")); //$NON-NLS-1$
            Object renameContext = createRenameElementContext(targetObject);
            Object refactoring = invokeMethod(renameProvider, "getRenameRefactoring", new Class<?>[] { //$NON-NLS-1$
                getClassOrThrow("org.eclipse.xtext.ui.refactoring.ui.IRenameElementContext")}, renameContext); //$NON-NLS-1$
            if (refactoring == null)
            {
                return List.of();
            }
            Object processor = invokeNoArg(refactoring, "getProcessor"); //$NON-NLS-1$
            if (processor == null)
            {
                return List.of();
            }

            Change edtChange = createRenameChange(refactoring, processor, newName);
            if (edtChange == null)
            {
                return List.of();
            }

            List<ChangePoint> edtChanges = new ArrayList<>();
            int[] indexCounter = {0};
            collectFlatChanges(edtChange, null, null, exactMatches, edtChanges, indexCounter, "edt-preview", false, //$NON-NLS-1$
                targetObject.getName());
            return edtChanges;
        }
        catch (Exception e)
        {
            Activator.logError("Error building EDT BSL preview changes", e); //$NON-NLS-1$
            return List.of();
        }
    }

    private Change createRenameChange(Object refactoring, Object processor, String newName) throws Exception
    {
        invokeMethod(processor, "setNewName", new Class<?>[] {String.class}, newName); //$NON-NLS-1$
        NullProgressMonitor progressMonitor = new NullProgressMonitor();
        Class<?> monitorClass = getClassOrThrow("org.eclipse.core.runtime.IProgressMonitor"); //$NON-NLS-1$

        if (refactoring != null)
        {
            invokeMethod(refactoring, "checkInitialConditions", new Class<?>[] {monitorClass}, progressMonitor); //$NON-NLS-1$
            invokeMethod(refactoring, "checkFinalConditions", new Class<?>[] {monitorClass}, progressMonitor); //$NON-NLS-1$
            return (Change) invokeMethod(refactoring, "createChange", new Class<?>[] {monitorClass}, progressMonitor); //$NON-NLS-1$
        }

        invokeMethod(processor, "checkInitialConditions", new Class<?>[] {monitorClass}, progressMonitor); //$NON-NLS-1$
        invokeMethod(processor, "checkFinalConditions", new Class<?>[] {monitorClass, CheckConditionsContext.class}, //$NON-NLS-1$
            progressMonitor, new CheckConditionsContext());
        return (Change) invokeMethod(processor, "createChange", new Class<?>[] {monitorClass}, progressMonitor); //$NON-NLS-1$
    }

    private void applyEdtBslPreviewData(List<ChangePoint> allChanges, List<ChangePoint> edtBslPreviewChanges)
    {
        if (allChanges == null || edtBslPreviewChanges == null || edtBslPreviewChanges.isEmpty())
        {
            return;
        }

        List<Integer> bslIndices = new ArrayList<>();
        for (int i = 0; i < allChanges.size(); i++)
        {
            if ("bslRef".equals(allChanges.get(i).type)) //$NON-NLS-1$
            {
                bslIndices.add(Integer.valueOf(i));
            }
        }
        if (bslIndices.size() != edtBslPreviewChanges.size())
        {
            return;
        }

        for (int i = 0; i < bslIndices.size(); i++)
        {
            int changeIndex = bslIndices.get(i).intValue();
            ChangePoint original = allChanges.get(changeIndex);
            ChangePoint edt = edtBslPreviewChanges.get(i);
            allChanges.set(changeIndex, new ChangePoint(
                original.index, original.type,
                edt.fqn != null ? edt.fqn : original.fqn,
                edt.project != null ? edt.project : original.project,
                original.description,
                original.optional,
                original.enabled,
                null,
                edt.lineNumber,
                edt.columnNumber,
                edt.codeContext,
                edt.methodName));
        }
    }

    private Map<String, ExactMatchInfo> toExactMatchMap(Collection<?> matches)
    {
        Map<String, ExactMatchInfo> result = new HashMap<>();
        for (Object match : matches)
        {
            if (isInstanceOf(match, "com._1c.g5.v8.dt.search.core.text.TextSearchFileMatch")) //$NON-NLS-1$
            {
                ExactMatchInfo info = createFileExactMatchInfo(match);
                if (info != null)
                {
                    IFile file = (IFile) invokeNoArg(match, "getFile"); //$NON-NLS-1$
                    int fileOffset = ((Number) invokeNoArg(match, "getFileOffset")).intValue(); //$NON-NLS-1$
                    int textLength = ((Number) invokeNoArg(match, "getTextLength")).intValue(); //$NON-NLS-1$
                    result.put(getFileMatchKey(file, fileOffset, textLength), info);
                }
            }
            else if (isInstanceOf(match, "com._1c.g5.v8.dt.search.core.text.TextSearchModelMatch")) //$NON-NLS-1$
            {
                ExactMatchInfo info = createModelExactMatchInfo(match);
                if (info != null)
                {
                    long objectId = ((Number) invokeNoArg(match, "getObjectId")).longValue(); //$NON-NLS-1$
                    EStructuralFeature feature = (EStructuralFeature) invokeNoArg(match, "getFeature"); //$NON-NLS-1$
                    int textOffset = ((Number) invokeNoArg(match, "getTextOffset")).intValue(); //$NON-NLS-1$
                    int textLength = ((Number) invokeNoArg(match, "getTextLength")).intValue(); //$NON-NLS-1$
                    result.put(getModelMatchKey(objectId, feature, textOffset, textLength), info);
                }
            }
        }
        return result;
    }

    private ExactMatchInfo createFileExactMatchInfo(Object match)
    {
        try
        {
            IFile file = (IFile) invokeNoArg(match, "getFile"); //$NON-NLS-1$
            if (file == null)
            {
                return null;
            }
            int lineNumber = ((Number) invokeNoArg(match, "getLineNumber")).intValue(); //$NON-NLS-1$
            String fqn = getBslFqn(file);
            String project = file.getProject().getName();
            String content = BslModuleUtils.readFileText(file);
            int fileOffset = ((Number) invokeNoArg(match, "getFileOffset")).intValue(); //$NON-NLS-1$
            int columnNumber = computeColumnNumber(content, fileOffset);
            String codeContext = extractContext(content, lineNumber);
            String methodName = null;
            Module module = BslModuleUtils.loadModule(file.getProject(), BslModuleUtils.extractModulePath(file.getFullPath().toString()));
            if (module != null)
            {
                methodName = findContainingMethodAst(module, lineNumber);
            }
            if (methodName == null)
            {
                methodName = findContainingMethodText(content, lineNumber);
            }
            return new ExactMatchInfo(file.getFullPath().toString(), fileOffset, lineNumber, columnNumber,
                codeContext, methodName, fqn, project);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private ExactMatchInfo createModelExactMatchInfo(Object match)
    {
        try
        {
            Object optional = invokeNoArg(match, "resolveMatchObject"); //$NON-NLS-1$
            boolean present = Boolean.TRUE.equals(invokeNoArg(optional, "isPresent")); //$NON-NLS-1$
            if (!present)
            {
                return null;
            }
            Object resolved = invokeNoArg(optional, "get"); //$NON-NLS-1$
            if (!(resolved instanceof EObject object))
            {
                return null;
            }
            EStructuralFeature feature = (EStructuralFeature) invokeNoArg(match, "getFeature"); //$NON-NLS-1$
            String content = getFeatureText(object, feature);
            if (content == null)
            {
                return null;
            }
            int textOffset = ((Number) invokeNoArg(match, "getTextOffset")).intValue(); //$NON-NLS-1$
            int lineNumber = computeLineNumber(content, textOffset);
            int columnNumber = computeColumnNumber(content, textOffset);
            String project = object instanceof IBmObject bmObject ? bmObject.bmGetEngine().getId() : null;
            String fqn = object instanceof IBmObject bmObject ? bmObject.bmGetTopObject().bmGetFqn() : null;
            return new ExactMatchInfo(null, textOffset, lineNumber, columnNumber, extractContext(content, lineNumber),
                null, fqn, project);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private List<ExactMatchInfo> findExactMatchInfos(Change change, Map<String, ExactMatchInfo> exactMatches)
    {
        if (exactMatches == null || exactMatches.isEmpty())
        {
            return List.of();
        }
        Map<String, ExactMatchInfo> matches = new LinkedHashMap<>();
        if (change instanceof BmObjectTextContentChange<?> bmChange)
        {
            Object modifiedElement = bmChange.getModifiedElement();
            if (modifiedElement instanceof IBmObject bmObject)
            {
                EStructuralFeature feature = getBmChangeFeature(bmChange);
                if (feature != null)
                {
                    for (TextEdit leafEdit : getLeafEdits(bmChange.getEdit()))
                    {
                        ExactMatchInfo info = exactMatches.get(getModelMatchKey(bmObject.bmGetId(), feature,
                            leafEdit.getOffset(), leafEdit.getLength()));
                        if (info != null)
                        {
                            matches.put(getExactMatchIdentity(info), info);
                        }
                    }
                }

                String projectName = bmChange.getProjectName();
                String objectFqn = bmObject.bmGetFqn();
                for (ExactMatchInfo info : exactMatches.values())
                {
                    if (info == null || info.filePath == null)
                    {
                        continue;
                    }
                    if (!Objects.equals(info.project, projectName) || !Objects.equals(info.fqn, objectFqn))
                    {
                        continue;
                    }
                    for (TextEdit leafEdit : getLeafEdits(bmChange.getEdit()))
                    {
                        if (containsOffset(leafEdit, info.matchOffset))
                        {
                            matches.put(getExactMatchIdentity(info), info);
                            break;
                        }
                    }
                }
            }
        }
        IFile file = getIFile(change);
        TextEdit edit = getChangeEdit(change);
        if (file != null && edit != null)
        {
            for (ExactMatchInfo info : exactMatches.values())
            {
                if (info == null || info.filePath == null || !info.filePath.equals(file.getFullPath().toString()))
                {
                    continue;
                }
                for (TextEdit leafEdit : getLeafEdits(edit))
                {
                    if (containsOffset(leafEdit, info.matchOffset))
                    {
                        matches.put(getExactMatchIdentity(info), info);
                        break;
                    }
                }
            }
        }
        return new ArrayList<>(matches.values());
    }

    private void logPreviewMapping(Change change, String fqn, String project, int exactMatchesCount)
    {
        if (change == null)
        {
            return;
        }
        try
        {
            if (!isCustomSourceFileChange(change) || exactMatchesCount != 0)
            {
                return;
            }
            int leafEditsCount = 0;
            StringBuilder offsets = new StringBuilder();
            TextEdit edit = getChangeEdit(change);
            if (edit != null)
            {
                List<TextEdit> leafEdits = getLeafEdits(edit);
                leafEditsCount = leafEdits.size();
                for (TextEdit leafEdit : leafEdits)
                {
                    if (offsets.length() > 0)
                    {
                        offsets.append(';');
                    }
                    offsets.append(leafEdit.getOffset()).append(',').append(leafEdit.getLength());
                }
            }
            IFile file = getIFile(change);
            String filePath = file != null ? file.getFullPath().toString() : null;
            Activator.logInfo("rename_metadata_object: preview mapping custom changeType=" + change.getClass().getSimpleName() //$NON-NLS-1$
                + ", exactMatches=" + exactMatchesCount //$NON-NLS-1$
                + ", leafEdits=" + leafEditsCount //$NON-NLS-1$
                + ", offsets=" + offsets //$NON-NLS-1$
                + ", project=" + project //$NON-NLS-1$
                + ", fqn=" + fqn //$NON-NLS-1$
                + ", file=" + filePath //$NON-NLS-1$
                + ", name=" + change.getName()); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error logging preview mapping diagnostics", e); //$NON-NLS-1$
        }
    }

    private static String getExactMatchIdentity(ExactMatchInfo info)
    {
        return info.project + "|" + info.fqn + "|" + info.matchOffset; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isCustomSourceFileChange(Change change)
    {
        return isInstanceOf(change, "com._1c.g5.v8.dt.lcore.refactoring.CustomSourceFileChange") //$NON-NLS-1$
            && !isFullTextSearchSourceFileChange(change);
    }

    private static boolean containsOffset(TextEdit edit, int offset)
    {
        if (edit == null || edit.getOffset() < 0)
        {
            return false;
        }
        int start = edit.getOffset();
        int end = edit.getLength() > 0 ? start + edit.getLength() : start + 1;
        return offset >= start && offset < end;
    }

    private static String getFileMatchKey(IFile file, int offset, int length)
    {
        return file.getFullPath().toString() + "[" + offset + "," + length + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String getModelMatchKey(long objectId, EStructuralFeature feature, int offset, int length)
    {
        return "(" + objectId + "," + feature.getFeatureID() + ")[" + offset + "," + length + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static List<TextEdit> getLeafEdits(TextEdit edit)
    {
        List<TextEdit> result = new ArrayList<>();
        collectLeafEdits(edit, result);
        return result;
    }

    private static void collectLeafEdits(TextEdit edit, List<TextEdit> result)
    {
        if (edit == null)
        {
            return;
        }
        TextEdit[] children = edit.getChildren();
        if (children == null || children.length == 0)
        {
            if (edit.getOffset() >= 0 && edit.getLength() >= 0)
            {
                result.add(edit);
            }
            return;
        }
        for (TextEdit child : children)
        {
            collectLeafEdits(child, result);
        }
    }

    private static EStructuralFeature getBmChangeFeature(BmObjectTextContentChange<?> change)
    {
        Object feature = getFieldValue(change, "feature"); //$NON-NLS-1$
        return feature instanceof EStructuralFeature structuralFeature ? structuralFeature : null;
    }

    private static String getFeatureText(EObject object, EStructuralFeature feature)
    {
        Object value = object.eGet(feature);
        return value instanceof String text ? text : null;
    }

    private static Object getFieldValue(Object target, String fieldName)
    {
        if (target == null)
        {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                java.lang.reflect.Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            }
            catch (Exception e)
            {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object invokeMethod(Object target, String methodName, Class<?>[] parameterTypes, Object... args)
        throws Exception
    {
        java.lang.reflect.Method method = findMethod(target.getClass(), methodName, parameterTypes);
        if (!method.canAccess(target))
        {
            method.setAccessible(true);
        }
        return method.invoke(target, args);
    }

    private static Class<?> getClassOrThrow(String className) throws ClassNotFoundException
    {
        try
        {
            return Class.forName(className);
        }
        catch (ClassNotFoundException e)
        {
            Bundle bundle = getOwningBundle(className);
            if (bundle != null)
            {
                return bundle.loadClass(className);
            }
            Activator.logWarning("rename_metadata_object: no owning bundle mapping for class " + className); //$NON-NLS-1$
            throw e;
        }
    }

    private static Bundle getOwningBundle(String className)
    {
        String bundleId = null;
        if (className.startsWith("com._1c.g5.v8.dt.bsl.ui.")) //$NON-NLS-1$
        {
            bundleId = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$
        }
        else if (className.startsWith("com._1c.g5.v8.dt.bsl.bm.ui.")) //$NON-NLS-1$
        {
            bundleId = "com._1c.g5.v8.dt.bsl.bm.ui"; //$NON-NLS-1$
        }
        else if (className.startsWith("com._1c.g5.v8.dt.search.core.")) //$NON-NLS-1$
        {
            bundleId = "com._1c.g5.v8.dt.search.core"; //$NON-NLS-1$
        }
        else if (className.startsWith("com._1c.g5.v8.dt.internal.search.core.")) //$NON-NLS-1$
        {
            bundleId = "com._1c.g5.v8.dt.search.core"; //$NON-NLS-1$
        }
        else if (className.startsWith("com._1c.g5.v8.dt.core.platform.management.")) //$NON-NLS-1$
        {
            bundleId = "com._1c.g5.v8.dt.core"; //$NON-NLS-1$
        }
        if (bundleId == null)
        {
            return null;
        }
        Bundle bundle = Platform.getBundle(bundleId);
        if (bundle == null)
        {
            Activator.logWarning("rename_metadata_object: Platform.getBundle returned null for " + bundleId); //$NON-NLS-1$
        }
        return bundle;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object getEnumConstant(String className, String constantName) throws ClassNotFoundException
    {
        Class enumClass = getClassOrThrow(className);
        return Enum.valueOf(enumClass, constantName);
    }

    private static Object arrayOf(Class<?> componentType, Object... values)
    {
        Object array = java.lang.reflect.Array.newInstance(componentType, values.length);
        for (int i = 0; i < values.length; i++)
        {
            java.lang.reflect.Array.set(array, i, values[i]);
        }
        return array;
    }

    private static boolean isInstanceOf(Object value, String className)
    {
        try
        {
            return getClassOrThrow(className).isInstance(value);
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }

    private static Object getBslInjector() throws Exception
    {
        Class<?> activatorClass = getClassOrThrow("com._1c.g5.v8.dt.bsl.ui.internal.BslActivator"); //$NON-NLS-1$
        Object activator = activatorClass.getMethod("getInstance").invoke(null); //$NON-NLS-1$
        return activatorClass.getMethod("getInjector", String.class).invoke(activator, //$NON-NLS-1$
            "com._1c.g5.v8.dt.bsl.Bsl"); //$NON-NLS-1$
    }

    private static Object getSearchCoreInjector() throws Exception
    {
        Class<?> pluginClass = getClassOrThrow("com._1c.g5.v8.dt.internal.search.core.SearchCorePlugin"); //$NON-NLS-1$
        Object plugin = pluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
        return pluginClass.getMethod("getInjector").invoke(plugin); //$NON-NLS-1$
    }

    private static Object createRenameElementContext(MdObject targetObject) throws Exception
    {
        Class<?> contextClass = getClassOrThrow(
            "com._1c.g5.v8.dt.bsl.bm.ui.refactoring.ConfigurationObjectRenameElementContext"); //$NON-NLS-1$
        java.lang.reflect.Constructor<?> constructor = contextClass.getDeclaredConstructor(
            getClassOrThrow("org.eclipse.emf.common.util.URI"), //$NON-NLS-1$
            getClassOrThrow("org.eclipse.emf.ecore.EClass"), //$NON-NLS-1$
            getClassOrThrow("com._1c.g5.v8.bm.core.IBmObject")); //$NON-NLS-1$
        constructor.setAccessible(true);
        return constructor.newInstance(EcoreUtil.getURI(targetObject), targetObject.eClass(), targetObject);
    }

    private static IFile getIFile(Change change)
    {
        Object modifiedElement = invokeNoArg(change, "getModifiedElement"); //$NON-NLS-1$
        IFile file = getIFileFromModifiedElement(modifiedElement);
        if (file != null)
        {
            return file;
        }
        Object affected = invokeNoArg(change, "getAffectedObjects"); //$NON-NLS-1$
        Object[] affectedObjects = affected instanceof Object[] ? (Object[]) affected : null;
        if (affectedObjects != null && affectedObjects.length == 1 && affectedObjects[0] instanceof IFile affectedFile)
        {
            return affectedFile;
        }
        return null;
    }

    private static IFile getIFileFromModifiedElement(Object modifiedElement)
    {
        if (modifiedElement == null)
        {
            return null;
        }
        Object fileObject = invokeNoArg(modifiedElement, "getFile"); //$NON-NLS-1$
        return fileObject instanceof IFile file ? file : null;
    }

    private static TextEdit getChangeEdit(Change change)
    {
        Object edit = invokeNoArg(change, "getEdit"); //$NON-NLS-1$
        return edit instanceof TextEdit textEdit ? textEdit : null;
    }

    private static Object invokeNoArg(Object target, String methodName)
    {
        try
        {
            java.lang.reflect.Method method = findMethod(target.getClass(), methodName, new Class<?>[0]);
            if (!method.canAccess(target))
            {
                method.setAccessible(true);
            }
            return method.invoke(target);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static java.lang.reflect.Method findMethod(Class<?> type, String methodName, Class<?>[] parameterTypes)
        throws NoSuchMethodException
    {
        Class<?> current = type;
        while (current != null)
        {
            try
            {
                return current.getDeclaredMethod(methodName, parameterTypes);
            }
            catch (NoSuchMethodException e)
            {
                current = current.getSuperclass();
            }
        }
        for (Class<?> iface : type.getInterfaces())
        {
            try
            {
                return iface.getMethod(methodName, parameterTypes);
            }
            catch (NoSuchMethodException e)
            {
                // try next interface
            }
        }
        return type.getMethod(methodName, parameterTypes);
    }

    private static String getBslFqn(IFile file)
    {
        return fallbackBslFqn(file);
    }

    private static boolean isBslReferenceChange(Change change, String currentFqn)
    {
        if (currentFqn != null && !currentFqn.isBlank())
        {
            return true;
        }
        if (change instanceof BmObjectTextContentChange<?>)
        {
            return true;
        }
        IFile file = getIFile(change);
        return file != null && getBslFqn(file) != null;
    }

    private static boolean isFullTextSearchSourceFileChange(Change change)
    {
        return isInstanceOf(change, "com._1c.g5.v8.dt.lcore.refactoring.FullTextSearchSourceFileChange"); //$NON-NLS-1$
    }

    private static String fallbackBslFqn(IFile file)
    {
        IPath path = file.getProjectRelativePath();
        if (path == null || path.segmentCount() < 4 || !"src".equals(path.segment(0))) //$NON-NLS-1$
        {
            return null;
        }
        String topLevelFolder = path.segment(1);
        String objectName = path.segment(2);
        String topLevelType = switch (topLevelFolder)
        {
        case "CommonModules" -> "CommonModule"; //$NON-NLS-1$ //$NON-NLS-2$
        case "Catalogs" -> "Catalog"; //$NON-NLS-1$ //$NON-NLS-2$
        case "Documents" -> "Document"; //$NON-NLS-1$ //$NON-NLS-2$
        case "Enums" -> "Enum"; //$NON-NLS-1$ //$NON-NLS-2$
        case "Reports" -> "Report"; //$NON-NLS-1$ //$NON-NLS-2$
        case "DataProcessors" -> "DataProcessor"; //$NON-NLS-1$ //$NON-NLS-2$
        case "CommonForms" -> "CommonForm"; //$NON-NLS-1$ //$NON-NLS-2$
        case "CommonCommands" -> "CommonCommand"; //$NON-NLS-1$ //$NON-NLS-2$
        case "HTTPServices" -> "HTTPService"; //$NON-NLS-1$ //$NON-NLS-2$
        case "WebServices" -> "WebService"; //$NON-NLS-1$ //$NON-NLS-2$
        case "WSReferences" -> "WSReference"; //$NON-NLS-1$ //$NON-NLS-2$
        case "InformationRegisters" -> "InformationRegister"; //$NON-NLS-1$ //$NON-NLS-2$
        case "AccumulationRegisters" -> "AccumulationRegister"; //$NON-NLS-1$ //$NON-NLS-2$
        case "AccountingRegisters" -> "AccountingRegister"; //$NON-NLS-1$ //$NON-NLS-2$
        case "CalculationRegisters" -> "CalculationRegister"; //$NON-NLS-1$ //$NON-NLS-2$
        case "BusinessProcesses" -> "BusinessProcess"; //$NON-NLS-1$ //$NON-NLS-2$
        case "Tasks" -> "Task"; //$NON-NLS-1$ //$NON-NLS-2$
        default -> null;
        };
        return topLevelType != null ? topLevelType + "." + objectName : null; //$NON-NLS-1$
    }

    private static int computeLineNumber(String content, int offset)
    {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++)
        {
            if (content.charAt(i) == '\n')
                line++;
        }
        return line;
    }

    private static int computeColumnNumber(String content, int offset)
    {
        int normalizedOffset = Math.max(0, Math.min(offset, content.length()));
        int lastLineBreak = Math.max(content.lastIndexOf('\n', normalizedOffset - 1),
            content.lastIndexOf('\r', normalizedOffset - 1));
        return normalizedOffset - lastLineBreak;
    }

    private static String extractContext(String content, int lineNumber)
    {
        String[] lines = content.split("\n", -1); //$NON-NLS-1$
        if (lineNumber < 1 || lineNumber > lines.length)
            return null;
        int lineIdx = lineNumber - 1;
        int startIdx = Math.max(0, lineIdx - 3);
        int endIdx = Math.min(lines.length - 1, lineIdx + 3);
        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i <= endIdx; i++)
        {
            String prefix = (i == lineIdx) ? ">>>" : "   "; //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(String.format("%4d: %s %s\n", i + 1, prefix, //$NON-NLS-1$
                lines[i].replace("\r", ""))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }

    /** Uses BSL AST (via BslModuleUtils + Xtext NodeModel) to find the method containing the given line. */
    private static String findContainingMethodAst(Module module, int lineNumber)
    {
        for (Method method : module.allMethods())
        {
            int startLine = BslModuleUtils.getStartLine(method);
            int endLine = BslModuleUtils.getEndLine(method);
            if (startLine > 0 && lineNumber >= startLine && lineNumber <= endLine)
            {
                return BslModuleUtils.buildSignature(method);
            }
        }
        return null;
    }

    /** Fallback: regex-based method search using BslModuleUtils patterns. */
    private static String findContainingMethodText(String content, int lineNumber)
    {
        String[] lines = content.split("\n", -1); //$NON-NLS-1$
        if (lineNumber < 1 || lineNumber > lines.length)
            return null;
        int lineIdx = lineNumber - 1;
        for (int i = lineIdx - 1; i >= 0; i--)
        {
            String trimmed = lines[i].trim().replace("\r", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (BslModuleUtils.METHOD_START_PATTERN.matcher(trimmed).find())
            {
                StringBuilder method = new StringBuilder();
                int k = i - 1;
                while (k >= 0 && lines[k].trim().startsWith("&")) //$NON-NLS-1$
                {
                    method.insert(0, lines[k].trim() + " "); //$NON-NLS-1$
                    k--;
                }
                method.append(trimmed);
                return method.toString();
            }
            if (BslModuleUtils.METHOD_END_PATTERN.matcher(trimmed).find())
                break;
        }
        return null;
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
