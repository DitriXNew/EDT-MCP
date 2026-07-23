/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
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
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Domain service backing {@code rename_metadata_object}: resolves the target object, builds the
 * LTK refactoring, renders the preview, and performs the rename (applying disabled change-point
 * indices).
 * <p>
 * Two-phase workflow:
 * 1. Preview mode (confirm=false, default): Returns list of affected refactoring items and problems.
 * 2. Execute mode (confirm=true): Performs the rename with all cascading code updates.
 * <p>
 * All model/EMF/LTK access here MUST run inside the caller's UI-thread {@code Display.syncExec}
 * scope; the tool adapter is responsible for that boundary.
 */
public class MetadataRenameService
{
    /** Em dash placeholder rendered in markdown table cells with no value. */
    private static final String DASH = "\u2014"; //$NON-NLS-1$

    /** Change-point type tag for BSL reference changes. */
    private static final String BSL_REF = "bslRef"; //$NON-NLS-1$

    /** Reflective method name: Guice injector instance lookup. */
    private static final String GET_INSTANCE = "getInstance"; //$NON-NLS-1$

    /** Reflective method name: file accessor on a modified element / search match. */
    private static final String GET_FILE = "getFile"; //$NON-NLS-1$

    /** Xtext rename element context interface class name. */
    private static final String IRENAME_ELEMENT_CONTEXT_CLASS =
        "org.eclipse.xtext.ui.refactoring.ui.IRenameElementContext"; //$NON-NLS-1$

    /** EDT search-core SearchIn enum class name. */
    private static final String SEARCH_IN_CLASS = "com._1c.g5.v8.dt.search.core.SearchIn"; //$NON-NLS-1$

    public String rename(String projectName, String objectFqn, String newName,
        boolean confirm, java.util.Set<Integer> disableIndices, int maxResults)
    {
        // Resolve the project and its configuration
        ProjectContext.ConfigurationResult resolved = ProjectContext.resolveConfiguration(projectName);
        if (!resolved.ok())
        {
            return resolved.errorJson();
        }
        IProject project = resolved.project();
        Configuration config = resolved.configuration();

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

        // NB: an ADOPTED object in a configuration extension CAN be renamed. Its link to the base
        // object is held by UUID in a separate field (extendedConfigurationObject), not by name
        // (keepMappingToExtendedConfigurationObjectsByIDs), so the EDT rename refactoring below keeps
        // the adoption intact - exactly as the EDT UI rename does. (Do NOT refuse adopted objects.)

        // Create refactoring (returns collection because it may also rename in extension projects)
        Collection<IRefactoring> refactorings = refactoringService.createMdObjectRenameRefactoring(targetObject, newName);
        if (refactorings == null || refactorings.isEmpty())
        {
            return ToolResult.error("Failed to create rename refactoring for: " + objectFqn).toJson(); //$NON-NLS-1$
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
        List<ChangePoint> edtBslPreviewChanges = buildEdtBslPreviewChanges(targetObject, newName, exactMatches);
        String oldName = targetObject.getName();

        // Phase 1: collect all changes and problems
        List<ChangePoint> allChanges = new ArrayList<>();
        List<String> allProblems = new ArrayList<>();
        collectChangesAndProblems(refactorings, exactMatches, oldName, allChanges, allProblems);

        applyEdtBslPreviewData(allChanges, edtBslPreviewChanges);

        long enabledCount = allChanges.stream().filter(c -> c.enabled).count();
        int shown = (maxResults > 0) ? Math.min(allChanges.size(), maxResults) : allChanges.size();

        // Phase 2: build markdown with YAML frontmatter
        StringBuilder sb = new StringBuilder();
        PreviewSummary summary = new PreviewSummary(objectFqn, newName, allChanges.size(), enabledCount,
            allProblems.size(), exactMatches.size(), shown);
        appendFrontmatter(sb, summary);
        appendChangePointsTable(sb, allChanges, shown);
        appendCodeContext(sb, allChanges, shown);
        appendProblemsSection(sb, allProblems);
        appendFooter(sb);

        return sb.toString();
    }

    /**
     * Phase 1 of the preview: walks every refactoring, flattening its items into change points and
     * accumulating its status problems, preserving the original collection (and index) ordering.
     */
    private void collectChangesAndProblems(Collection<IRefactoring> refactorings,
        Map<String, ExactMatchInfo> exactMatches, String oldName, List<ChangePoint> allChanges,
        List<String> allProblems)
    {
        int[] indexCounter = {0};
        for (IRefactoring refactoring : refactorings)
        {
            String title = refactoring.getTitle();
            collectRefactoringItems(refactoring, title, exactMatches, oldName, allChanges, indexCounter);
            collectRefactoringProblems(refactoring, allProblems);
        }
    }

    /**
     * Flattens one refactoring's items into change points: native items recurse through the LTK change
     * tree, while plain rename items each consume one global index. Preserves the original ordering.
     */
    private void collectRefactoringItems(IRefactoring refactoring, String title,
        Map<String, ExactMatchInfo> exactMatches, String oldName, List<ChangePoint> allChanges, int[] indexCounter)
    {
        Collection<IRefactoringItem> items = refactoring.getItems();
        if (items == null)
        {
            return;
        }
        for (IRefactoringItem item : items)
        {
            if (item instanceof INativeChangeRefactoringItem nativeItem)
            {
                Change nativeChange = nativeItem.getNativeChange();
                if (nativeChange != null)
                {
                    ScanContext ctx = new ScanContext(exactMatches, allChanges, indexCounter, title,
                        item.isOptional(), oldName);
                    collectFlatChanges(nativeChange, null, null, ctx);
                }
            }
            else
            {
                allChanges.add(new ChangePoint(
                    indexCounter[0]++, "rename", //$NON-NLS-1$
                    item.getName(), item.isOptional(), item.isChecked(),
                    CodeLocation.of(null, null)));
            }
        }
    }

    /** Appends one formatted problem line per problem in the refactoring's status (if any). */
    private void collectRefactoringProblems(IRefactoring refactoring, List<String> allProblems)
    {
        RefactoringStatus status = refactoring.getStatus();
        if (status == null)
        {
            return;
        }
        Collection<IRefactoringProblem> problems = status.getProblems();
        if (problems == null)
        {
            return;
        }
        for (IRefactoringProblem problem : problems)
        {
            allProblems.add(formatProblem(problem));
        }
    }

    /**
     * Formats a single refactoring problem as a one-line string: clean-reference source/feature
     * (when applicable) joined with the problem object's FQN, matching the original rendering.
     */
    private static String formatProblem(IRefactoringProblem problem)
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
        return pb.toString();
    }

    /**
     * Immutable bundle of the preview header totals: the rename identity ({@code objectFqn} /
     * {@code newName}) and the four counts rendered in the YAML frontmatter (total / enabled change
     * points, problems, debug exact matches) plus {@code shown}. Carried so {@link #appendFrontmatter}
     * reads exactly the same values in the same order while staying within the parameter limit.
     */
    private static final class PreviewSummary
    {
        final String objectFqn;
        final String newName;
        final int totalChanges;
        final long enabledCount;
        final int problemCount;
        final int exactMatchCount;
        final int shown;

        PreviewSummary(String objectFqn, String newName, int totalChanges, long enabledCount,
            int problemCount, int exactMatchCount, int shown)
        {
            this.objectFqn = objectFqn;
            this.newName = newName;
            this.totalChanges = totalChanges;
            this.enabledCount = enabledCount;
            this.problemCount = problemCount;
            this.exactMatchCount = exactMatchCount;
            this.shown = shown;
        }
    }

    /** Appends the YAML frontmatter, title, totals and (when truncated) the "showing N of M" note. */
    private void appendFrontmatter(StringBuilder sb, PreviewSummary summary)
    {
        sb.append("---\n"); //$NON-NLS-1$
        sb.append("action: preview\n"); //$NON-NLS-1$
        sb.append("objectFqn: ").append(summary.objectFqn).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("newName: ").append(summary.newName).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("totalChanges: ").append(summary.totalChanges).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("enabledChanges: ").append(summary.enabledCount).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("problems: ").append(summary.problemCount).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("debugExactMatches: ").append(summary.exactMatchCount).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("---\n\n"); //$NON-NLS-1$

        sb.append("# Refactoring Preview: Rename `").append(summary.objectFqn) //$NON-NLS-1$
          .append("` \u2192 `").append(summary.newName).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        sb.append("**Total change points:** ").append(summary.totalChanges) //$NON-NLS-1$
          .append(" | **Enabled by default:** ").append(summary.enabledCount).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (summary.totalChanges > summary.shown)
        {
            sb.append("_Showing ").append(summary.shown).append(" of ").append(summary.totalChanges) //$NON-NLS-1$ //$NON-NLS-2$
              .append(" changes. Pass `maxResults=").append(summary.totalChanges) //$NON-NLS-1$
              .append("` to see all._\n\n"); //$NON-NLS-1$
        }
    }

    /** Appends the change-points markdown table (header, the first {@code shown} rows, and the overflow row). */
    private void appendChangePointsTable(StringBuilder sb, List<ChangePoint> allChanges, int shown)
    {
        sb.append("## Change Points\n\n"); //$NON-NLS-1$
                sb.append("| # | Type | Description | Line | Col | Default | Skippable | Project | FQN |\n"); //$NON-NLS-1$
                sb.append("|---|------|-------------|------|-----|---------|-----------|---------|-----|\n"); //$NON-NLS-1$
        for (int i = 0; i < shown; i++)
        {
            appendChangePointRow(sb, allChanges.get(i));
        }
        if (allChanges.size() > shown)
        {
                        sb.append("| ... | | | | | | | | _").append(allChanges.size() - shown) //$NON-NLS-1$
              .append(" more_ |\n"); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$
    }

    /** Appends one markdown table row for a single change point, rendering empty cells as an em dash. */
    private void appendChangePointRow(StringBuilder sb, ChangePoint cp)
    {
        String enabledMark = cp.enabled ? "\u2705" : "\u274c"; //$NON-NLS-1$ //$NON-NLS-2$
        String optionalMark = cp.optional ? "yes" : "no"; //$NON-NLS-1$ //$NON-NLS-2$
        String fqnCell = cp.fqn != null ? escapeMarkdownCell(cp.fqn) : DASH;
        String projectCell = cp.project != null ? escapeMarkdownCell(cp.project) : DASH;
        String line = cp.lineNumber > 0 ? String.valueOf(cp.lineNumber) : DASH;
                    String column = cp.columnNumber > 0 ? String.valueOf(cp.columnNumber) : DASH;
        String description = cp.description != null ? escapeMarkdownCell(cp.description) : DASH;
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

    /**
     * Appends the code-context section (one block per shown change point that carries a snippet),
     * but only when at least one of the shown change points actually has a code context.
     */
    private void appendCodeContext(StringBuilder sb, List<ChangePoint> allChanges, int shown)
    {
        boolean hasContext = false;
        for (int i = 0; i < shown; i++)
        {
            if (allChanges.get(i).codeContext != null)
            {
                hasContext = true;
                break;
            }
        }
        if (!hasContext)
        {
            return;
        }
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

    /** Appends the problems section (one bullet per problem) when any problems were collected. */
    private void appendProblemsSection(StringBuilder sb, List<String> allProblems)
    {
        if (allProblems.isEmpty())
        {
            return;
        }
        sb.append("## Problems\n\n"); //$NON-NLS-1$
        for (String p : allProblems)
        {
            sb.append("- ").append(p).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n"); //$NON-NLS-1$
    }

    /** Appends the trailing usage hint (confirm/disableIndices) shared by every preview. */
    private void appendFooter(StringBuilder sb)
    {
        sb.append("> To execute, call with `confirm=true`.\n"); //$NON-NLS-1$
        sb.append("> Use `disableIndices='1,2,3'` to skip change points by their `#` index " //$NON-NLS-1$
            + "(optional only; one index may span several context rows - skipping it skips them all).\n"); //$NON-NLS-1$
    }

    /**
     * Immutable holder for the source-location coordinates of a preview row / exact match:
     * the owning object FQN and project plus the (1-based) line/column, the surrounding code
     * snippet and the containing method name. Bundled so the {@link ChangePoint} and
     * {@link ExactMatchInfo} constructors stay within the parameter limit while reading the
     * same values in the same order. A "no location" instance uses {@code -1}/{@code null}.
     */
    private static final class CodeLocation
    {
        final String fqn;
        final String project;
        final int lineNumber;
        final int columnNumber;
        final String codeContext;
        final String methodName;

        CodeLocation(String fqn, String project, int lineNumber, int columnNumber, String codeContext,
            String methodName)
        {
            this.fqn = fqn;
            this.project = project;
            this.lineNumber = lineNumber;
            this.columnNumber = columnNumber;
            this.codeContext = codeContext;
            this.methodName = methodName;
        }

        /** Location carrying only fqn/project (no line/column/context/method). */
        static CodeLocation of(String fqn, String project)
        {
            return new CodeLocation(fqn, project, -1, -1, null, null);
        }
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

        ChangePoint(int index, String type, String description,
            boolean optional, boolean enabled, CodeLocation location)
        {
            this.index = index;
            this.type = type;
            this.fqn = location.fqn;
            this.project = location.project;
            this.description = description;
            this.optional = optional;
            this.enabled = enabled;
            this.lineNumber = location.lineNumber;
            this.columnNumber = location.columnNumber;
            this.codeContext = location.codeContext;
            this.methodName = location.methodName;
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

        ExactMatchInfo(String filePath, int matchOffset, CodeLocation location)
        {
            this.filePath = filePath;
            this.matchOffset = matchOffset;
            this.lineNumber = location.lineNumber;
            this.columnNumber = location.columnNumber;
            this.codeContext = location.codeContext;
            this.methodName = location.methodName;
            this.fqn = location.fqn;
            this.project = location.project;
        }
    }

    /** Escapes pipe characters in markdown table cells. */
    private static String escapeMarkdownCell(String s)
    {
        return s == null ? "" : s.replace("|", "\\|"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Mutable accumulator threaded through the per-leaf scan helpers so they can
     * report back the resolved {@code fqn}/{@code project} (refined from the change
     * itself) and whether they already added one or more change points for the leaf.
     */
    private static final class LeafScan
    {
        String fqn;
        String project;
        boolean addedFallbackChange;

        LeafScan(String fqn, String project)
        {
            this.fqn = fqn;
            this.project = project;
        }
    }

    /**
     * Immutable per-scan context threaded through the change-tree walk and the per-leaf scan helpers.
     * Bundles the state that stays constant for one top-level refactoring item: the exact-match index,
     * the change-point accumulator, the shared leaf-index counter, the refactoring title, the optional
     * flag and the old object name. Carried so the recursive/scan helpers stay within the parameter
     * limit while reading exactly the same values in the same order as before.
     */
    private static final class ScanContext
    {
        final Map<String, ExactMatchInfo> exactMatches;
        final List<ChangePoint> result;
        final int[] indexCounter;
        final String refactoringTitle;
        final boolean optional;
        final String oldName;

        ScanContext(Map<String, ExactMatchInfo> exactMatches, List<ChangePoint> result, int[] indexCounter,
            String refactoringTitle, boolean optional, String oldName)
        {
            this.exactMatches = exactMatches;
            this.result = result;
            this.indexCounter = indexCounter;
            this.refactoringTitle = refactoringTitle;
            this.optional = optional;
            this.oldName = oldName;
        }
    }

    /**
     * Recursively collects leaf changes from LTK change tree into a flat list with global indices.
     * Extracts line number and code context (±3 lines + containing method) for TextChange leaves.
     */
    private void collectFlatChanges(Change change, String currentFqn, String currentProject, ScanContext ctx)
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
            recurseComposite(composite, currentFqn, currentProject, ctx);
        }
        else
        {
            collectLeafChange(change, currentFqn, currentProject, ctx);
        }
    }

    /** Recurses into the children of a composite change, preserving the inherited fqn/project context. */
    private void recurseComposite(CompositeChange composite, String currentFqn, String currentProject,
        ScanContext ctx)
    {
        Change[] children = composite.getChildren();
        if (children != null && children.length > 0)
        {
            for (Change child : children)
            {
                collectFlatChanges(child, currentFqn, currentProject, ctx);
            }
        }
    }

    /**
     * Handles a single leaf (non-composite) change: consumes exactly one global index, then renders
     * either the exact-match rows, the BSL/source extracted rows, or the bare fallback row. Mirrors
     * the leaf numbering of {@link #walkLeafChanges} so a preview {@code #index} stays a stable handle.
     */
    private void collectLeafChange(Change change, String currentFqn, String currentProject, ScanContext ctx)
    {
        // One index per leaf change - this MUST match the leaf numbering in
        // walkLeafChanges()/applyDisableToChange() so a preview index stays a
        // stable cross-call handle for disableIndices. A leaf may render as
        // several display rows (multiple exact matches / text edits) or none
        // (suppressed), but it always consumes exactly one index. (card A2)
        int leafIndex = ctx.indexCounter[0]++;
        boolean hasExactMatches = ctx.exactMatches != null && !ctx.exactMatches.isEmpty();
        boolean isBslReferenceChange = isBslReferenceChange(change, currentFqn);
        boolean isFullTextSearchChange = isFullTextSearchSourceFileChange(change);
        LeafScan scan = new LeafScan(currentFqn, currentProject);
        List<ExactMatchInfo> exactMatchInfos = findExactMatchInfos(change, ctx.exactMatches);
        logPreviewMapping(change, scan.fqn, scan.project, exactMatchInfos.size());
        if (!exactMatchInfos.isEmpty())
        {
            addExactMatchChangePoints(change, leafIndex, scan, exactMatchInfos, ctx);
            return;
        }
        else if (change instanceof BmObjectTextContentChange<?> bmChange)
        {
            scanBmTextContentChange(change, bmChange, leafIndex, scan, ctx);
        }
        else
        {
            scanSourceFileChange(change, leafIndex, scan, ctx);
        }

        if (scan.addedFallbackChange)
        {
            return;
        }

        if (hasExactMatches && isBslReferenceChange && isFullTextSearchChange)
        {
            return;
        }

        ctx.result.add(new ChangePoint(
            ctx.indexCounter[0]++, BSL_REF,
            change.getName(), ctx.optional, change.isEnabled(),
            CodeLocation.of(scan.fqn, scan.project)));
    }

    /** Emits one change point per resolved exact-match, defaulting fqn/project to the leaf context. */
    private void addExactMatchChangePoints(Change change, int leafIndex, LeafScan scan,
        List<ExactMatchInfo> exactMatchInfos, ScanContext ctx)
    {
        for (ExactMatchInfo exactMatch : exactMatchInfos)
        {
            String exactFqn = exactMatch.fqn != null ? exactMatch.fqn : scan.fqn;
            String exactProject = exactMatch.project != null ? exactMatch.project : scan.project;
            ctx.result.add(new ChangePoint(
                leafIndex, BSL_REF,
                change.getName(), ctx.optional, change.isEnabled(),
                new CodeLocation(exactFqn, exactProject, exactMatch.lineNumber, exactMatch.columnNumber,
                    exactMatch.codeContext, exactMatch.methodName)));
        }
    }

    /**
     * Extracts line/column/context for a BM model text-content change, refining scan.fqn/project and
     * adding one change point per leaf edit. Swallows extraction errors exactly as before (logs only).
     */
    private void scanBmTextContentChange(Change change, BmObjectTextContentChange<?> bmChange, int leafIndex,
        LeafScan scan, ScanContext ctx)
    {
        try
        {
            scan.project = bmChange.getProjectName();
            Object modifiedElement = bmChange.getModifiedElement();
            EObject bmObj = modifiedElement instanceof EObject ? (EObject) modifiedElement : null;
            if (modifiedElement instanceof IBmObject ibm)
            {
                scan.fqn = ibm.bmGetFqn();
            }
            String content = bmChange.getCurrentContent(new NullProgressMonitor());
            TextEdit edit = bmChange.getEdit();
            if (content != null && !content.isEmpty() && edit != null)
            {
                List<TextEdit> leafEdits = getLeafEdits(edit);
                if (!leafEdits.isEmpty())
                {
                    Module module = bmObj instanceof Module bslModule ? bslModule : null;
                    addLeafEditChangePoints(change, leafIndex, scan, leafEdits, content, module, ctx);
                    scan.addedFallbackChange = true;
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error extracting BSL change location", e); //$NON-NLS-1$
        }
    }

    /**
     * Extracts line/column/context for a source-file change, refining scan.fqn/project and adding one
     * change point per leaf edit. Swallows extraction errors exactly as before (logs only).
     */
    private void scanSourceFileChange(Change change, int leafIndex, LeafScan scan, ScanContext ctx)
    {
        try
        {
            IFile file = getIFile(change);
            if (file != null)
            {
                scan.project = file.getProject().getName();
                String resolvedFqn = getBslFqn(file);
                if (resolvedFqn != null && !resolvedFqn.isEmpty())
                {
                    scan.fqn = resolvedFqn;
                }
                String content = BslModuleUtils.readFileText(file);
                TextEdit edit = getChangeEdit(change);
                if (content != null && !content.isEmpty() && edit != null)
                {
                    List<TextEdit> leafEdits = getLeafEdits(edit);
                    if (!leafEdits.isEmpty())
                    {
                        Module module = BslModuleUtils.loadModule(file.getProject(),
                            BslModuleUtils.extractModulePath(file.getFullPath().toString()));
                        addLeafEditChangePoints(change, leafIndex, scan, leafEdits, content, module, ctx);
                        scan.addedFallbackChange = true;
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error extracting source file change location", e); //$NON-NLS-1$
        }
    }

    /**
     * Shared leaf-edit loop: for each edit computes line/column/context and the containing method, then
     * appends a change point carrying the leaf's shared global index and the refined fqn/project.
     */
    private void addLeafEditChangePoints(Change change, int leafIndex, LeafScan scan, List<TextEdit> leafEdits,
        String content, Module module, ScanContext ctx)
    {
        for (TextEdit leafEdit : leafEdits)
        {
            int matchedLineNumber = computeLineNumber(content, leafEdit.getOffset());
            int matchedColumnNumber = computeColumnNumber(content, leafEdit.getOffset());
            String matchedCodeContext = extractContext(content, matchedLineNumber);
            String matchedMethodName = resolveContainingMethod(module, content, matchedLineNumber);
            ctx.result.add(new ChangePoint(
                leafIndex, BSL_REF,
                change.getName(), ctx.optional, change.isEnabled(),
                new CodeLocation(scan.fqn, scan.project, matchedLineNumber, matchedColumnNumber,
                    matchedCodeContext, matchedMethodName)));
        }
    }

    /**
     * Resolves the containing method name for a line, preferring the BSL AST (when a module is
     * available) and falling back to the regex-based text search, matching the original order.
     */
    private static String resolveContainingMethod(Module module, String content, int lineNumber)
    {
        String methodName = null;
        if (module != null)
        {
            methodName = findContainingMethodAst(module, lineNumber);
        }
        if (methodName == null)
        {
            methodName = findContainingMethodText(content, lineNumber);
        }
        return methodName;
    }

    private Map<String, ExactMatchInfo> buildExactMatchInfo(IProject project, MdObject targetObject, String newName)
    {
        try
        {
            Object bslInjector = getBslInjector();
            Object renameProvider = invokeMethod(bslInjector, GET_INSTANCE, new Class<?>[] {Class.class},
                getClassOrThrow("com._1c.g5.v8.dt.bsl.bm.ui.refactoring.BslBmRenameRefactoringProvider")); //$NON-NLS-1$
            Object renameContext = createRenameElementContext(targetObject);
            Object refactoring = invokeMethod(renameProvider, "getRenameRefactoring", new Class<?>[] { //$NON-NLS-1$
                getClassOrThrow(IRENAME_ELEMENT_CONTEXT_CLASS)}, renameContext);
            Object processor = refactoring != null ? invokeNoArg(refactoring, "getProcessor") : null; //$NON-NLS-1$
            if (processor == null)
            {
                processor = invokeMethod(renameProvider, "getRenameProcessor", new Class<?>[] { //$NON-NLS-1$
                getClassOrThrow(IRENAME_ELEMENT_CONTEXT_CLASS)}, renameContext);
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

            Object supplier = invokeMethod(bslInjector, GET_INSTANCE, new Class<?>[] {Class.class},
                getClassOrThrow("com._1c.g5.v8.dt.bsl.bm.ui.refactoring.BslTextSearchRefactoringSupplier")); //$NON-NLS-1$

            Object searchInjector = getSearchCoreInjector();
            Object factory = invokeMethod(searchInjector, GET_INSTANCE, new Class<?>[] {Class.class},
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
            Object modules = getEnumConstant(SEARCH_IN_CLASS, "MODULES"); //$NON-NLS-1$
            Object dcs = getEnumConstant(SEARCH_IN_CLASS, "DCS"); //$NON-NLS-1$
            Object dynamicListQuery = getEnumConstant(SEARCH_IN_CLASS, "DYNAMIC_LIST_QUERY"); //$NON-NLS-1$
            invokeMethod(searchScopeSettings, "addSearchIn", new Class<?>[] {modules.getClass().arrayType()}, //$NON-NLS-1$
                arrayOf(modules.getClass(), modules, dcs, dynamicListQuery));
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
            return toExactMatchMap(matches);
        }
        catch (Exception e)
        {
            Activator.logError("Error collecting exact rename matches", e); //$NON-NLS-1$
            return Map.of();
        }
    }

    private List<ChangePoint> buildEdtBslPreviewChanges(MdObject targetObject, String newName,
        Map<String, ExactMatchInfo> exactMatches)
    {
        try
        {
            Object bslInjector = getBslInjector();
            Object renameProvider = invokeMethod(bslInjector, GET_INSTANCE, new Class<?>[] {Class.class},
                getClassOrThrow("com._1c.g5.v8.dt.bsl.bm.ui.refactoring.BslBmRenameRefactoringProvider")); //$NON-NLS-1$
            Object renameContext = createRenameElementContext(targetObject);
            Object refactoring = invokeMethod(renameProvider, "getRenameRefactoring", new Class<?>[] { //$NON-NLS-1$
                getClassOrThrow(IRENAME_ELEMENT_CONTEXT_CLASS)}, renameContext);
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
            ScanContext ctx = new ScanContext(exactMatches, edtChanges, indexCounter, "edt-preview", false, //$NON-NLS-1$
                targetObject.getName());
            collectFlatChanges(edtChange, null, null, ctx);
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
            if (BSL_REF.equals(allChanges.get(i).type))
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
                original.description,
                original.optional,
                original.enabled,
                new CodeLocation(
                    edt.fqn != null ? edt.fqn : original.fqn,
                    edt.project != null ? edt.project : original.project,
                    edt.lineNumber,
                    edt.columnNumber,
                    edt.codeContext,
                    edt.methodName)));
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
                    IFile file = (IFile) invokeNoArg(match, GET_FILE);
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
            IFile file = (IFile) invokeNoArg(match, GET_FILE);
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
            return new ExactMatchInfo(file.getFullPath().toString(), fileOffset,
                new CodeLocation(fqn, project, lineNumber, columnNumber, codeContext, methodName));
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
            return new ExactMatchInfo(null, textOffset,
                new CodeLocation(fqn, project, lineNumber, columnNumber, extractContext(content, lineNumber), null));
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
                collectBmChangeMatches(bmChange, bmObject, exactMatches, matches);
            }
        }
        IFile file = getIFile(change);
        TextEdit edit = getChangeEdit(change);
        if (file != null && edit != null)
        {
            collectFileChangeMatches(file, edit, exactMatches, matches);
        }
        return new ArrayList<>(matches.values());
    }

    /**
     * Collects the exact matches that belong to a {@link BmObjectTextContentChange}: model matches
     * keyed by (object, feature, offset, length) and file-backed matches whose project / FQN equal the
     * change's and whose offset falls inside one of the change's leaf edits. Pure lookup - adds the
     * hits into {@code matches} keyed by their identity (no model mutation).
     */
    private void collectBmChangeMatches(BmObjectTextContentChange<?> bmChange, IBmObject bmObject,
        Map<String, ExactMatchInfo> exactMatches, Map<String, ExactMatchInfo> matches)
    {
        EStructuralFeature feature = getBmChangeFeature(bmChange);
        if (feature != null)
        {
            collectBmModelKeyMatches(bmChange, bmObject, feature, exactMatches, matches);
        }
        collectBmFileOffsetMatches(bmChange, bmObject, exactMatches, matches);
    }

    /**
     * Adds the file-backed exact matches whose (object, feature, offset, length) key equals a leaf edit
     * of the BM change. Pure lookup - no model mutation.
     */
    private void collectBmModelKeyMatches(BmObjectTextContentChange<?> bmChange, IBmObject bmObject,
        EStructuralFeature feature, Map<String, ExactMatchInfo> exactMatches, Map<String, ExactMatchInfo> matches)
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

    /**
     * Adds the file-backed exact matches whose project / FQN equal the BM change's and whose offset
     * falls inside one of the change's leaf edits. Pure lookup - no model mutation.
     */
    private void collectBmFileOffsetMatches(BmObjectTextContentChange<?> bmChange, IBmObject bmObject,
        Map<String, ExactMatchInfo> exactMatches, Map<String, ExactMatchInfo> matches)
    {
        String projectName = bmChange.getProjectName();
        String objectFqn = bmObject.bmGetFqn();
        for (ExactMatchInfo info : exactMatches.values()) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
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

    /**
     * Collects the exact matches that belong to a plain source-file change: file-backed matches whose
     * path equals {@code file} and whose offset falls inside one of the change's leaf edits. Pure
     * lookup - adds the hits into {@code matches} keyed by their identity (no model mutation).
     */
    private void collectFileChangeMatches(IFile file, TextEdit edit,
        Map<String, ExactMatchInfo> exactMatches, Map<String, ExactMatchInfo> matches)
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
                field.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
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
        throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        java.lang.reflect.Method method = findMethod(target.getClass(), methodName, parameterTypes);
        if (!method.canAccess(target))
        {
            method.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
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

    private static Object getBslInjector() throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        Class<?> activatorClass = getClassOrThrow("com._1c.g5.v8.dt.bsl.ui.internal.BslActivator"); //$NON-NLS-1$
        Object activator = activatorClass.getMethod(GET_INSTANCE).invoke(null);
        return activatorClass.getMethod("getInjector", String.class).invoke(activator, //$NON-NLS-1$
            "com._1c.g5.v8.dt.bsl.Bsl"); //$NON-NLS-1$
    }

    private static Object getSearchCoreInjector() throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
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
        constructor.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
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
        Object fileObject = invokeNoArg(modifiedElement, GET_FILE);
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
                method.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
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
            sb.append(String.format("%4d: %s %s", i + 1, prefix, //$NON-NLS-1$
                lines[i].replace("\r", ""))).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
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
        // Destructive-operation consent gate: the LAST check before the cascade rename mutates the
        // model (rewriting every reference across BSL, forms and metadata). Built from the refactorings
        // the tool already resolved; on ALLOW the behaviour is byte-identical, on REJECT nothing is
        // mutated. This runs inside the tool's UI-thread syncExec scope, so the dialog (when armed)
        // opens directly. Headless / env-bypass / non-ASK never block.
        ConsentPreview preview = new ConsentPreview("Rename metadata object", //$NON-NLS-1$
            "This renames '" + objectFqn + "' to '" + newName //$NON-NLS-1$ //$NON-NLS-2$
                + "' and cascades the change across all references in BSL code, forms and metadata.", //$NON-NLS-1$
            countRefactoringItems(refactorings), collectRefactoringTitles(refactorings));
        // The tool NAME literal is intentionally inlined here (matching the frozen value in
        // DestructiveConsentGate.GATED_TOOLS, asserted by DestructiveConsentGateTest) rather than
        // read from RenameMetadataObjectTool.NAME, so this domain service in tools.rename does not
        // take a reverse dependency on the tool adapter in tools.impl.
        DestructiveConsentGate.ConsentDecision consentDecision =
            DestructiveConsentGate.getInstance().requireConsent("rename_metadata_object", preview); //$NON-NLS-1$
        if (consentDecision != DestructiveConsentGate.ConsentDecision.ALLOW)
        {
            return ToolResult.error(
                DestructiveConsentGate.consentDeniedMessage(consentDecision, "rename_metadata_object")) //$NON-NLS-1$
                .toJson();
        }

        // Apply disableIndices by traversing items and their native changes
        if (!disableIndices.isEmpty())
        {
            applyDisableIndices(refactorings, disableIndices);
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

        return renderExecutedReport(objectFqn, newName, disableIndices, performed, errors);
    }

    /**
     * Totals the refactoring items across the collection for the consent preview's count (how many
     * change groups the rename will apply). Pure; tolerant of a null {@code getItems()}.
     */
    private static int countRefactoringItems(Collection<IRefactoring> refactorings)
    {
        int total = 0;
        for (IRefactoring refactoring : refactorings)
        {
            Collection<IRefactoringItem> items = refactoring.getItems();
            if (items != null)
            {
                total += items.size();
            }
        }
        return total;
    }

    /**
     * Collects the refactoring titles for the consent preview's top-names list (each refactoring is one
     * rename group, e.g. the base configuration plus any extension). Pure; skips null/blank titles.
     */
    private static List<String> collectRefactoringTitles(Collection<IRefactoring> refactorings)
    {
        List<String> titles = new ArrayList<>();
        for (IRefactoring refactoring : refactorings)
        {
            String title = refactoring.getTitle();
            if (title != null && !title.isEmpty())
            {
                titles.add(title);
            }
        }
        return titles;
    }

    /**
     * Toggles the LTK change-tree flags for the requested {@code disableIndices} BEFORE the rename is
     * performed: walks every refactoring item, disables the matching leaf changes (via
     * {@link #applyDisableToChange}) and unchecks an optional native item whose leaves are all
     * disabled. Mutates the in-memory refactoring objects' enabled/checked state only - it does NOT
     * perform the rename. Extracted verbatim from {@link #performRename} so it runs at the same point
     * under the same {@code !disableIndices.isEmpty()} guard.
     */
    private void applyDisableIndices(Collection<IRefactoring> refactorings,
        java.util.Set<Integer> disableIndices)
    {
        int[] indexCounter = {0};
        for (IRefactoring refactoring : refactorings)
        {
            Collection<IRefactoringItem> items = refactoring.getItems();
            if (items == null)
                continue;
            for (IRefactoringItem item : items)
            {
                applyDisableToItem(item, disableIndices, indexCounter);
            }
        }
    }

    /**
     * Applies {@code disableIndices} to a single refactoring item, advancing {@code indexCounter} by the
     * same amount as the preview-side walk: native items disable their matching leaf changes (and uncheck
     * the optional item when every leaf is disabled), while plain rename items consume one index. Extracted
     * verbatim from {@link #applyDisableIndices} so it mutates the same in-memory state at the same point.
     */
    private void applyDisableToItem(IRefactoringItem item, java.util.Set<Integer> disableIndices,
        int[] indexCounter)
    {
        if (item instanceof INativeChangeRefactoringItem nativeItem)
        {
            Change nativeChange = nativeItem.getNativeChange();
            if (nativeChange != null)
            {
                applyDisableToChange(nativeChange, disableIndices, indexCounter);
            }
            // If all leaf changes under this native item are disabled, uncheck the item itself
            if (nativeChange != null && nativeItem.isOptional() && isCompletelyDisabled(nativeChange))
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

    /**
     * Renders the markdown report for a performed rename: the YAML front matter (counts), the
     * "Rename Completed" header, the performed-titles and error lists, and the skipped-change-points
     * note. Pure string building with no side effects; extracted verbatim from {@link #performRename}.
     */
    private static String renderExecutedReport(String objectFqn, String newName,
        java.util.Set<Integer> disableIndices, List<String> performed, List<String> errors)
    {
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
        walkLeafChanges(change, indexCounter, (leaf, idx) -> {
            if (disableIndices.contains(idx))
            {
                leaf.setEnabled(false);
            }
        });
    }

    /**
     * Walks the LTK change tree in canonical order, invoking {@code leafConsumer}
     * for every leaf (non-{@link CompositeChange}) change with its global index.
     * Composites are recursed into but never assigned an index - this is the single
     * source of truth for the change-point numbering. The preview side
     * ({@link #collectFlatChanges}) MUST assign exactly one index per leaf in the
     * same order, so that a preview {@code #index} maps back to the same leaf here
     * when {@code disableIndices} is applied on execute. (card A2)
     * <p>
     * Public and static so the leaf numbering can be unit-tested headless.
     */
    public static void walkLeafChanges(Change change, int[] indexCounter,
        java.util.function.ObjIntConsumer<Change> leafConsumer)
    {
        if (change instanceof CompositeChange composite)
        {
            Change[] children = composite.getChildren();
            if (children != null)
            {
                for (Change child : children)
                {
                    walkLeafChanges(child, indexCounter, leafConsumer);
                }
            }
        }
        else
        {
            leafConsumer.accept(change, indexCounter[0]++);
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
        String getterName = resolveChildGetterName(type);

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

    /**
     * Maps a (lower-cased) child-type token - English singular/plural or its Russian
     * equivalent - to the EMF collection getter that holds children of that kind, or
     * {@code null} when the token names no supported child collection.
     */
    private String resolveChildGetterName(String type)
    {
        String getterName = null;
        if ("attribute".equals(type) || "attributes".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "реквизит".equals(type) //$NON-NLS-1$ // реквизит
            || "реквизиты".equals(type)) //$NON-NLS-1$ // реквизиты
        {
            getterName = "getAttributes"; //$NON-NLS-1$
        }
        else if ("tabularsection".equals(type) || "tabularsections".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "табличнаячасть".equals(type) //$NON-NLS-1$ // табличнаячасть
            || "табличныечасти".equals(type)) //$NON-NLS-1$ // табличныечасти
        {
            getterName = "getTabularSections"; //$NON-NLS-1$
        }
        else if ("dimension".equals(type) || "dimensions".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "измерение".equals(type) //$NON-NLS-1$ // измерение
            || "измерения".equals(type)) //$NON-NLS-1$ // измерения
        {
            getterName = "getDimensions"; //$NON-NLS-1$
        }
        else if ("resource".equals(type) || "resources".equals(type) //$NON-NLS-1$ //$NON-NLS-2$
            || "ресурс".equals(type) //$NON-NLS-1$ // ресурс
            || "ресурсы".equals(type)) //$NON-NLS-1$ // ресурсы
        {
            getterName = "getResources"; //$NON-NLS-1$
        }
        return getterName;
    }
}
