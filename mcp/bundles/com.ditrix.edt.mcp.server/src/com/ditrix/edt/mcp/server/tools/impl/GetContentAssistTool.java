/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.XtextSourceViewer;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

import io.github.furstenheim.CopyDown;

/**
 * Tool to get content assist (code completion) proposals at a specific position in a BSL file.
 * Opens the file in EDT editor, sets cursor position, and retrieves completion proposals.
 */
public class GetContentAssistTool implements IMcpTool
{
    public static final String NAME = "get_content_assist"; //$NON-NLS-1$

    /** Param/result key: 1-based column position. */
    private static final String KEY_COLUMN = "column"; //$NON-NLS-1$

    /**
     * Maximum total time (ms) to wait for the Xtext editor/resource to become ready before
     * computing content assist. Bounded so the call can never block forever; on timeout the
     * tool returns a ToolResult.error so the caller can retry instead of seeing an empty success.
     */
    private static final long READINESS_TIMEOUT_MS = 1500L;

    /**
     * Delay (ms) between readiness polls. The UI event loop is pumped between polls so the
     * Xtext reconciler (which loads/parses the resource) can make progress.
     */
    private static final long READINESS_POLL_DELAY_MS = 50L;

    /**
     * Maximum total time (ms) to spend stabilizing the proposal set. Even after the resource
     * has parsed, the BSL global scope/index can still be warming, so the content-assist
     * processor may return an empty or growing set on the first compute(s). We re-poll until the
     * count stops growing, bounded by this cap (then accept whatever we have).
     */
    private static final long PROPOSAL_STABILIZE_TIMEOUT_MS = 2500L;

    /** Delay (ms) between proposal-stabilization re-computes; the UI loop is pumped between them. */
    private static final long PROPOSAL_STABILIZE_POLL_MS = 75L;

    /** Shared empty result for the stabilization loop (null/exception compute -> "not ready yet"). */
    private static final ICompletionProposal[] EMPTY_PROPOSALS = new ICompletionProposal[0];

    /**
     * Deterministic proposal ordering. The engine can return proposals in a warm-up-dependent
     * order, so a caller paginating with {@code offset} would otherwise see different items per
     * call. Order by display string (case-insensitive, then case-sensitive as a stable tie-break)
     * so {@code offset}/{@code limit} page reproducibly. Null display strings sort last.
     */
    private static final Comparator<ICompletionProposal> PROPOSAL_ORDER =
        Comparator.comparing(GetContentAssistTool::displayStringOf,
            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(GetContentAssistTool::displayStringOf,
                Comparator.nullsLast(Comparator.<String>naturalOrder()));

    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get code-completion proposals at a 1-based line/column in a BSL module - the members, " //$NON-NLS-1$
             + "globals and variables valid at that caret (e.g. after a '.'). May return a 'not ready' " //$NON-NLS-1$
             + "error while the editor loads; just retry. " //$NON-NLS-1$
             + "Full parameters and examples: call get_tool_guide('get_content_assist')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name", true) //$NON-NLS-1$
            .stringProperty("modulePath", "BSL module path under src (e.g. 'CommonModules/MyModule/Module.bsl'); alias: filePath") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("filePath", "Deprecated alias for modulePath") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("line", "Line number (1-based)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty(KEY_COLUMN, "Column number (1-based)", true) //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT, "Max proposals to return (default: from preferences, max 1000)") //$NON-NLS-1$
            .integerProperty("offset", "Skip first N matching proposals for pagination (default: 0)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("contains", "Keep proposals whose display string contains any of these substrings (comma-separated, e.g. 'Insert,Add')") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("extendedDocumentation", "Include full documentation per proposal (default: false)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("file", "Workspace-relative path of the module the proposals are for") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("line", "1-based line where proposals were computed") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty(KEY_COLUMN, "1-based column where proposals were computed") //$NON-NLS-1$
            .integerProperty("totalProposals", "Total proposals offered before any filter") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("filteredOut", "Proposals removed by the contains filter") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("skipped", "Proposals consumed by the offset") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("returnedProposals", "Count of proposals returned in this page") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("proposals", "Proposals as { displayString[, documentation] }") //$NON-NLS-1$ //$NON-NLS-2$
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
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        // Canonical modulePath; accept the deprecated filePath alias for back-compat.
        String filePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        if (filePath == null || filePath.isEmpty())
        {
            filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
        }
        String lineStr = JsonUtils.extractStringArgument(params, "line"); //$NON-NLS-1$
        String columnStr = JsonUtils.extractStringArgument(params, KEY_COLUMN);
        String containsFilter = JsonUtils.extractStringArgument(params, "contains"); //$NON-NLS-1$
        String extendedDocStr = JsonUtils.extractStringArgument(params, "extendedDocumentation"); //$NON-NLS-1$
        
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        if (filePath == null || filePath.isEmpty())
        {
            return ToolResult.error("modulePath is required (or the deprecated alias filePath)").toJson(); //$NON-NLS-1$
        }

        int line;
        int column;
        try
        {
            // Handle both integer ("33") and double ("33.0") formats
            line = (int) Double.parseDouble(lineStr);
            column = (int) Double.parseDouble(columnStr);
        }
        catch (NumberFormatException | NullPointerException e)
        {
            return ToolResult.error("Invalid line or column number").toJson(); //$NON-NLS-1$
        }
        
        if (line < 1 || column < 1)
        {
            return ToolResult.error("Line and column must be >= 1").toJson(); //$NON-NLS-1$
        }
        
        int defaultLimit = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, McpKeys.LIMIT, 100);
        int limit = JsonUtils.extractIntArgument(params, McpKeys.LIMIT, defaultLimit);
        limit = Pagination.clampLimit(limit, 1000);

        // Read offset the SAME way as limit (declare-and-read consistency, CLAUDE.md don't #6):
        // the schema declares offset as integer, so parse it via extractIntArgument (accepts
        // "5"/"5.0", rejects "5.7"/"abc" -> 0) rather than the old extractStringArgument +
        // (int) Double.parseDouble path, which silently truncated "5.7" to 5 while limit rejected it.
        int offset = Math.max(0, JsonUtils.extractIntArgument(params, "offset", 0)); //$NON-NLS-1$
        
        boolean extendedDocumentation = "true".equalsIgnoreCase(extendedDocStr); //$NON-NLS-1$
        
        return getContentAssist(projectName, filePath, line, column, limit, offset, containsFilter, extendedDocumentation);
    }
    
    /**
     * Gets content assist proposals at the specified position.
     * Must run on UI thread to access editors.
     * 
     * @param projectName EDT project name
     * @param filePath relative path from project's src folder (e.g. 'CommonModules/MyModule/Module.bsl')
     * @param line line number (1-based)
     * @param column column number (1-based)
     * @param limit maximum proposals to return
     * @param offset number of proposals to skip (for pagination)
     * @param containsFilter comma-separated substrings to filter proposals
     * @param extendedDocumentation whether to include full documentation
     * @return JSON result
     */
    private String getContentAssist(String projectName, String filePath, int line, int column, 
                                    int limit, int offset, String containsFilter, boolean extendedDocumentation)
    {
        // Find the project
        ProjectContext ctx = ProjectContext.of(projectName);

        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }

        if (!ctx.isOpen())
        {
            return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
        }

        IProject project = ctx.project();
        
        // Build the full path: project/src/filePath
        IFile file = BslModuleUtils.resolveModuleFile(project, filePath);

        if (!file.exists())
        {
            return ToolResult.error("File not found: " + file.getProjectRelativePath().toString() + " in project " + projectName).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        final IFile targetFile = file;
        final int targetLine = line;
        final int targetColumn = column;
        final int maxProposals = limit;
        final int proposalOffset = offset;
        final String filter = containsFilter;
        final boolean extendedDoc = extendedDocumentation;
        
        AtomicReference<String> resultRef = new AtomicReference<>();
        
        // Execute on UI thread
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = executeOnUiThread(targetFile, targetLine, targetColumn, maxProposals, 
                                                   proposalOffset, filter, extendedDoc);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error getting content assist", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson()); //$NON-NLS-1$
            }
        });
        
        return resultRef.get();
    }
    
    /**
     * Executes content assist on UI thread.
     */
    private String executeOnUiThread(IFile file, int line, int column, int maxProposals, 
                                     int proposalOffset, String containsFilter, boolean extendedDocumentation) throws Exception
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
        {
            return ToolResult.error("No active workbench window").toJson(); //$NON-NLS-1$
        }
        
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
        {
            return ToolResult.error("No active workbench page").toJson(); //$NON-NLS-1$
        }
        
        // Open or activate the editor
        IEditorPart editorPart = IDE.openEditor(page, file, true);
        if (editorPart == null)
        {
            return ToolResult.error("Could not open editor for file").toJson(); //$NON-NLS-1$
        }
        
        // Check if it's an Xtext editor
        XtextEditor xtextEditor = editorPart.getAdapter(XtextEditor.class);
        if (xtextEditor == null)
        {
            return ToolResult.error("File is not a BSL module (not an Xtext editor)").toJson(); //$NON-NLS-1$
        }
        
        ISourceViewer sourceViewer = xtextEditor.getInternalSourceViewer();
        if (sourceViewer == null)
        {
            return ToolResult.error("Could not get source viewer").toJson(); //$NON-NLS-1$
        }
        
        IDocument document = sourceViewer.getDocument();
        if (document == null)
        {
            return ToolResult.error("Could not get document").toJson(); //$NON-NLS-1$
        }
        
        // Calculate offset from line and column (1-based to 0-based)
        int offset;
        try
        {
            int lineOffset = document.getLineOffset(line - 1);
            offset = lineOffset + column - 1;
            
            // Validate offset is within document
            if (offset < 0 || offset > document.getLength())
            {
                return ToolResult.error("Position is outside document bounds").toJson(); //$NON-NLS-1$
            }
        }
        catch (BadLocationException e)
        {
            return ToolResult.error("Invalid line number: " + line).toJson(); //$NON-NLS-1$
        }
        
        // Set cursor position
        xtextEditor.selectAndReveal(offset, 0);
        
        // Get content assist processor from the source viewer configuration
        // The XtextSourceViewer has content assist configured
        if (!(sourceViewer instanceof XtextSourceViewer))
        {
            return ToolResult.error("Source viewer is not XtextSourceViewer").toJson(); //$NON-NLS-1$
        }
        
        XtextSourceViewer xtextSourceViewer = (XtextSourceViewer) sourceViewer;

        // DEFENSIVE readiness guard: on a rapid/cold call the editor's Xtext resource may not yet be
        // parsed/linked (the reconciler runs asynchronously), and content assist would then return an
        // empty proposal list. Wait briefly (bounded) for the resource to load+parse before computing.
        // If it never becomes ready, return an error so the caller retries instead of getting an empty
        // success that looks like "no proposals".
        if (!waitForResourceReadiness(xtextSourceViewer))
        {
            return ToolResult.error(
                "Xtext editor not ready for content assist (resource still loading). Please retry.").toJson(); //$NON-NLS-1$
        }

        // Get content assist processor
        // We need to get it from the content assistant that's configured in the viewer
        ContentAssistant contentAssistant = (ContentAssistant) xtextSourceViewer.getContentAssistant();
        if (contentAssistant == null)
        {
            return ToolResult.error("Content assistant not available").toJson(); //$NON-NLS-1$
        }
        
        // Get content type at offset
        String contentType;
        try
        {
            contentType = document.getContentType(offset);
        }
        catch (Exception e)
        {
            contentType = IDocument.DEFAULT_CONTENT_TYPE;
        }
        
        // Get completion proposals
        IContentAssistProcessor processor = contentAssistant.getContentAssistProcessor(contentType);
        if (processor == null)
        {
            return ToolResult.error("No content assist processor for content type: " + contentType).toJson(); //$NON-NLS-1$
        }
        
        ICompletionProposal[] proposals = computeStableProposals(processor, sourceViewer, offset);

        // Format results
        return formatProposals(proposals, maxProposals, proposalOffset, containsFilter, extendedDocumentation,
                               line, column, file.getFullPath().toString());
    }

    /**
     * Computes the content-assist proposals, stabilizing the set against the global-scope/index
     * warm-up race. Even after the resource has parsed, the Xtext processor can return an empty or
     * partial proposal list on the first compute(s) while the BSL scope is still warming, which made
     * {@code totalProposals}/{@code skipped} non-deterministic across calls (a position with
     * proposals would intermittently report 0). We re-compute (pumping the UI loop between tries),
     * keep the LARGEST set seen, and stop once the count is non-empty and no longer growing, bounded
     * by {@link #PROPOSAL_STABILIZE_TIMEOUT_MS}. A genuinely empty position keeps returning empty and
     * is accepted when the bound elapses.
     *
     * @param processor the content-assist processor for the content type at the offset
     * @param viewer the source viewer
     * @param offset the document offset to complete at
     * @return the stabilized proposal array (never null; empty only if the position truly has none)
     */
    private ICompletionProposal[] computeStableProposals(IContentAssistProcessor processor,
        ISourceViewer viewer, int offset)
    {
        long deadline = System.currentTimeMillis() + PROPOSAL_STABILIZE_TIMEOUT_MS;
        ICompletionProposal[] best = safeCompute(processor, viewer, offset);
        while (System.currentTimeMillis() < deadline)
        {
            int bestCount = best.length;
            pumpUi(viewer);
            sleepQuietly(PROPOSAL_STABILIZE_POLL_MS);
            ICompletionProposal[] next = safeCompute(processor, viewer, offset);
            if (next.length > bestCount)
            {
                best = next; // still warming - more proposals appeared; keep the larger set and retry
                continue;
            }
            if (bestCount > 0)
            {
                break; // non-empty and no longer growing -> warmed and stable
            }
            // still empty -> keep polling until the bound, then accept the (genuinely-empty) result
        }
        return best;
    }

    /**
     * Computes proposals once, never returning null and never throwing - a null result or an
     * exception becomes an empty array so the stabilization loop treats it as "not ready yet".
     */
    private static ICompletionProposal[] safeCompute(IContentAssistProcessor processor,
        ISourceViewer viewer, int offset)
    {
        try
        {
            ICompletionProposal[] proposals = processor.computeCompletionProposals(viewer, offset);
            return proposals != null ? proposals : EMPTY_PROPOSALS;
        }
        catch (Exception e)
        {
            Activator.logError("Error computing content-assist proposals", e); //$NON-NLS-1$
            return EMPTY_PROPOSALS;
        }
    }

    /** Drains queued UI events so the asynchronous Xtext scope/index build can make progress. */
    private static void pumpUi(ISourceViewer viewer)
    {
        Display display = viewer.getTextWidget() != null ? viewer.getTextWidget().getDisplay()
            : Display.getCurrent();
        if (display != null)
        {
            while (display.readAndDispatch())
            {
                // drain queued events
            }
        }
    }

    /** Sleeps without propagating interruption as a checked exception (restores the interrupt flag). */
    private static void sleepQuietly(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /** Display-string accessor for {@link #PROPOSAL_ORDER}; may be null (the comparator sorts those last). */
    private static String displayStringOf(ICompletionProposal proposal)
    {
        return proposal == null ? null : proposal.getDisplayString();
    }

    /**
     * Bounded, best-effort wait for the editor's Xtext resource to be loaded and parsed so content
     * assist can produce proposals. Runs on the UI thread (the caller is inside Display.syncExec):
     * polls readiness, pumping the UI event loop between polls so the asynchronous reconciler can
     * make progress. Never blocks forever - capped by {@link #READINESS_TIMEOUT_MS}.
     *
     * @param viewer the Xtext source viewer of the open editor
     * @return true if the resource became ready within the timeout, false otherwise
     */
    private boolean waitForResourceReadiness(XtextSourceViewer viewer)
    {
        IXtextDocument document = viewer.getXtextDocument();
        if (document == null)
        {
            // No Xtext document available; let the caller proceed and surface any downstream error.
            return true;
        }

        Display display = viewer.getTextWidget() != null ? viewer.getTextWidget().getDisplay() : Display.getCurrent();
        long deadline = System.currentTimeMillis() + READINESS_TIMEOUT_MS;

        while (true)
        {
            if (isResourceReady(document))
            {
                return true;
            }

            if (System.currentTimeMillis() >= deadline)
            {
                return false;
            }

            // Pump pending UI events so the reconciler/loader can advance, then sleep briefly.
            if (display != null)
            {
                while (display.readAndDispatch())
                {
                    // drain queued events
                }
            }

            try
            {
                Thread.sleep(READINESS_POLL_DELAY_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                // Stop waiting; let the readiness check decide the final answer.
                return isResourceReady(document);
            }
        }
    }

    /**
     * Checks whether the Xtext resource backing the document is loaded and has parsed content.
     * Uses a non-blocking read access: if the resource is not yet available (state null or read
     * lock not grantable) the default {@code false} is returned, which is treated as "not ready".
     *
     * @param document the Xtext document of the open editor
     * @return true if the resource is loaded and has at least one root element
     */
    private boolean isResourceReady(IXtextDocument document)
    {
        try
        {
            Boolean ready = document.tryReadOnly((XtextResource resource) -> {
                if (resource == null || !resource.isLoaded())
                {
                    return Boolean.FALSE;
                }
                return Boolean.valueOf(!resource.getContents().isEmpty());
            }, () -> Boolean.FALSE);
            return Boolean.TRUE.equals(ready);
        }
        catch (Exception e)
        {
            // Treat any read failure as "not ready yet" so we keep polling within the bound.
            return false;
        }
    }

    /**
     * Formats completion proposals as JSON.
     * 
     * @param proposals all proposals from content assist
     * @param maxProposals maximum proposals to return
     * @param proposalOffset number of proposals to skip
     * @param containsFilter comma-separated substrings to filter by (case-insensitive)
     * @param extendedDocumentation whether to include full documentation
     * @param line original line number
     * @param column original column number
     * @param filePath file path for result
     * @return JSON string
     */
    static String formatProposals(ICompletionProposal[] proposals, int maxProposals, int proposalOffset,
                                   String containsFilter, boolean extendedDocumentation,
                                   int line, int column, String filePath)
    {
        // Deterministic page order (see PROPOSAL_ORDER): sort BEFORE applying offset/limit so a
        // caller paginating with offset sees the same items per call, independent of the engine's
        // warm-up-dependent return order. totalProposals (= proposals.length) is unaffected.
        if (proposals != null)
        {
            Arrays.sort(proposals, PROPOSAL_ORDER);
        }

        // Parse contains filter into lowercase parts
        String[] filterParts = parseContainsFilter(containsFilter);

        List<Map<String, Object>> proposalList = new ArrayList<>();
        int count = 0;
        int skipped = 0;
        int filteredOut = 0;

        if (proposals != null)
        {
            for (ICompletionProposal proposal : proposals)
            {
                String displayString = proposal.getDisplayString();

                // Apply contains filter
                if (filterParts != null && !matchesFilter(displayString, filterParts))
                {
                    filteredOut++;
                    continue;
                }

                // Apply offset (skip first N matching proposals)
                if (skipped < proposalOffset)
                {
                    skipped++;
                    continue;
                }

                // Check limit
                if (count >= maxProposals)
                {
                    break;
                }

                proposalList.add(buildProposalObject(proposal, displayString, extendedDocumentation));
                count++;
            }
        }

        int totalProposals = proposals != null ? proposals.length : 0;
        return ToolResult.success()
            .put("file", filePath) //$NON-NLS-1$
            .put("line", line) //$NON-NLS-1$
            .put(KEY_COLUMN, column)
            .put("totalProposals", totalProposals) //$NON-NLS-1$
            .put("filteredOut", filteredOut) //$NON-NLS-1$
            .put("skipped", skipped) //$NON-NLS-1$
            .put("returnedProposals", count) //$NON-NLS-1$
            .put("proposals", proposalList) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Parses the comma-separated {@code contains} filter into trimmed, lowercased parts.
     * Returns {@code null} when no filter is supplied (caller treats null as "no filter"),
     * preserving the original behavior where an empty/absent filter keeps all proposals.
     *
     * @param containsFilter the raw comma-separated filter, possibly null/empty
     * @return the lowercased trimmed parts, or null when no filter was supplied
     */
    @SuppressWarnings("java:S1168")
    private static String[] parseContainsFilter(String containsFilter)
    {
        if (containsFilter == null || containsFilter.isEmpty())
        {
            return null;
        }
        String[] filterParts = containsFilter.toLowerCase().split(","); //$NON-NLS-1$
        for (int i = 0; i < filterParts.length; i++)
        {
            filterParts[i] = filterParts[i].trim();
        }
        return filterParts;
    }

    /**
     * Returns true when the display string contains any non-empty filter part
     * (case-insensitive), matching the original inline filter loop.
     *
     * @param displayString the proposal display string
     * @param filterParts the lowercased trimmed filter parts (non-null)
     * @return true if at least one non-empty part is a substring of the display string
     */
    private static boolean matchesFilter(String displayString, String[] filterParts)
    {
        String displayLower = displayString.toLowerCase();
        for (String part : filterParts)
        {
            if (!part.isEmpty() && displayLower.contains(part))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the per-proposal map ({@code displayString} plus, when requested and
     * available, cleaned {@code documentation}). Uses a LinkedHashMap to keep a stable,
     * readable field order, exactly as the original inline block did.
     *
     * @param proposal the completion proposal
     * @param displayString the proposal's already-read display string
     * @param extendedDocumentation whether to include documentation
     * @return the proposal object map
     */
    private static Map<String, Object> buildProposalObject(ICompletionProposal proposal,
        String displayString, boolean extendedDocumentation)
    {
        // LinkedHashMap to keep a stable, readable field order per proposal.
        Map<String, Object> proposalObj = new LinkedHashMap<>();
        proposalObj.put("displayString", displayString); //$NON-NLS-1$

        // Only get documentation if extendedDocumentation is true
        if (extendedDocumentation)
        {
            String additionalInfo = extractAdditionalInfo(proposal);
            if (additionalInfo != null && !additionalInfo.isEmpty())
            {
                // Strip HTML tags and CSS styles for cleaner output
                String cleanInfo = cleanHtmlContent(additionalInfo);
                if (!cleanInfo.isEmpty())
                {
                    proposalObj.put("documentation", cleanInfo); //$NON-NLS-1$
                }
            }
        }
        return proposalObj;
    }

    /**
     * Reads a proposal's additional info (documentation), using
     * {@link ICompletionProposalExtension5} with a {@link NullProgressMonitor} for
     * async-capable proposals and the plain accessor otherwise. May return null.
     *
     * @param proposal the completion proposal
     * @return the additional-info string, or null when none is available
     */
    private static String extractAdditionalInfo(ICompletionProposal proposal)
    {
        // Get additional info (documentation)
        // Use ICompletionProposalExtension5 for async-capable proposals
        if (proposal instanceof ICompletionProposalExtension5)
        {
            // Get documentation using progress monitor
            Object info = ((ICompletionProposalExtension5) proposal)
                .getAdditionalProposalInfo(new NullProgressMonitor());
            return info != null ? info.toString() : null;
        }
        return proposal.getAdditionalProposalInfo();
    }

    /**
     * Converts HTML content to Markdown format using CopyDown library.
     * 
     * @param html the HTML content
     * @return cleaned text in Markdown format
     */
    private static String cleanHtmlContent(String html)
    {
        if (html == null || html.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        
        try
        {
            // Remove <style> blocks before conversion (CopyDown doesn't handle CSS well)
            String cleaned = html.replaceAll("(?s)<style[^>]*>.*?</style>", ""); //$NON-NLS-1$ //$NON-NLS-2$
            
            // Convert HTML to Markdown using CopyDown library
            CopyDown converter = new CopyDown();
            String markdown = converter.convert(cleaned);
            
            // Normalize excessive line breaks
            markdown = markdown.replaceAll("\n{3,}", "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            
            return markdown.trim();
        }
        catch (Exception e)
        {
            Activator.logError("Error converting HTML to Markdown", e); //$NON-NLS-1$
            // Fallback: just strip tags
            return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
    }
}
