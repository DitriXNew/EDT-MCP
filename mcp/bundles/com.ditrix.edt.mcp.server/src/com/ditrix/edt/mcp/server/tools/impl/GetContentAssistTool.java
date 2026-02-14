/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
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
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.XtextSourceViewer;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.furstenheim.CopyDown;

/**
 * Tool to get content assist (code completion) proposals at a specific position in a BSL file.
 * Opens the file in EDT editor, sets cursor position, and retrieves completion proposals.
 */
public class GetContentAssistTool implements IMcpTool
{
    public static final String NAME = "get_content_assist"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get content assist (code completion) proposals at a specific position in a BSL file. " + //$NON-NLS-1$
               "Opens the file in EDT editor and retrieves available completions at the given line and column."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("filePath", "Path to BSL file relative to project's src folder (e.g. 'CommonModules/MyModule/Module.bsl')", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("line", "Line number (1-based)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("column", "Column number (1-based)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Maximum number of proposals to return (default: from preferences)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("offset", "Skip first N proposals (default: 0, for pagination)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("contains", "Filter proposals by display string containing these substrings (comma-separated, e.g. 'Insert,Add')") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("extendedDocumentation", "Return full documentation (default: false, only display string)") //$NON-NLS-1$ //$NON-NLS-2$
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
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
        String lineStr = JsonUtils.extractStringArgument(params, "line"); //$NON-NLS-1$
        String columnStr = JsonUtils.extractStringArgument(params, "column"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$
        String offsetStr = JsonUtils.extractStringArgument(params, "offset"); //$NON-NLS-1$
        String containsFilter = JsonUtils.extractStringArgument(params, "contains"); //$NON-NLS-1$
        String extendedDocStr = JsonUtils.extractStringArgument(params, "extendedDocumentation"); //$NON-NLS-1$
        
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        
        if (filePath == null || filePath.isEmpty())
        {
            return ToolResult.error("filePath is required").toJson(); //$NON-NLS-1$
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
        
        int limit = Activator.getDefault().getDefaultLimit();
        if (limitStr != null && !limitStr.isEmpty())
        {
            try
            {
                limit = Math.min((int) Double.parseDouble(limitStr), Activator.getDefault().getMaxLimit());
            }
            catch (NumberFormatException e)
            {
                // Use default
            }
        }
        
        int offset = 0;
        if (offsetStr != null && !offsetStr.isEmpty())
        {
            try
            {
                offset = Math.max(0, (int) Double.parseDouble(offsetStr));
            }
            catch (NumberFormatException e)
            {
                // Use default 0
            }
        }
        
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
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject(projectName);
        
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        
        if (!project.isOpen())
        {
            return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
        }
        
        // Build the full path: project/src/filePath
        IPath relativePath = new Path("src").append(filePath); //$NON-NLS-1$
        IFile file = project.getFile(relativePath);
        
        if (!file.exists())
        {
            return ToolResult.error("File not found: " + relativePath.toString() + " in project " + projectName).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
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
                resultRef.set(ToolResult.error("Error: " + e.getMessage()).toJson()); //$NON-NLS-1$
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
        
        ICompletionProposal[] proposals = processor.computeCompletionProposals(sourceViewer, offset);
        
        // Format results
        return formatProposals(proposals, maxProposals, proposalOffset, containsFilter, extendedDocumentation,
                               line, column, file.getFullPath().toString());
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
    private String formatProposals(ICompletionProposal[] proposals, int maxProposals, int proposalOffset,
                                   String containsFilter, boolean extendedDocumentation,
                                   int line, int column, String filePath)
    {
        JsonObject result = new JsonObject();
        result.addProperty("success", true); //$NON-NLS-1$
        result.addProperty("file", filePath); //$NON-NLS-1$
        result.addProperty("line", line); //$NON-NLS-1$
        result.addProperty("column", column); //$NON-NLS-1$
        
        // Parse contains filter into lowercase parts
        String[] filterParts = null;
        if (containsFilter != null && !containsFilter.isEmpty())
        {
            filterParts = containsFilter.toLowerCase().split(","); //$NON-NLS-1$
            for (int i = 0; i < filterParts.length; i++)
            {
                filterParts[i] = filterParts[i].trim();
            }
        }
        
        JsonArray proposalsArray = new JsonArray();
        int count = 0;
        int skipped = 0;
        int filteredOut = 0;
        
        if (proposals != null)
        {
            for (ICompletionProposal proposal : proposals)
            {
                String displayString = proposal.getDisplayString();
                
                // Apply contains filter
                if (filterParts != null)
                {
                    boolean matches = false;
                    String displayLower = displayString.toLowerCase();
                    for (String part : filterParts)
                    {
                        if (!part.isEmpty() && displayLower.contains(part))
                        {
                            matches = true;
                            break;
                        }
                    }
                    if (!matches)
                    {
                        filteredOut++;
                        continue;
                    }
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
                
                JsonObject proposalObj = new JsonObject();
                proposalObj.addProperty("displayString", displayString); //$NON-NLS-1$
                
                // Only get documentation if extendedDocumentation is true
                if (extendedDocumentation)
                {
                    // Get additional info (documentation)
                    // Use ICompletionProposalExtension5 for async-capable proposals
                    String additionalInfo = null;
                    if (proposal instanceof ICompletionProposalExtension5)
                    {
                        // Get documentation using progress monitor
                        Object info = ((ICompletionProposalExtension5) proposal)
                            .getAdditionalProposalInfo(new NullProgressMonitor());
                        if (info != null)
                        {
                            additionalInfo = info.toString();
                        }
                    }
                    else
                    {
                        additionalInfo = proposal.getAdditionalProposalInfo();
                    }
                    
                    if (additionalInfo != null && !additionalInfo.isEmpty())
                    {
                        // Strip HTML tags and CSS styles for cleaner output
                        String cleanInfo = cleanHtmlContent(additionalInfo);
                        if (!cleanInfo.isEmpty())
                        {
                            proposalObj.addProperty("documentation", cleanInfo); //$NON-NLS-1$
                        }
                    }
                }
                
                proposalsArray.add(proposalObj);
                count++;
            }
        }
        
        int totalProposals = proposals != null ? proposals.length : 0;
        result.addProperty("totalProposals", totalProposals); //$NON-NLS-1$
        result.addProperty("filteredOut", filteredOut); //$NON-NLS-1$
        result.addProperty("skipped", skipped); //$NON-NLS-1$
        result.addProperty("returnedProposals", count); //$NON-NLS-1$
        result.add("proposals", proposalsArray); //$NON-NLS-1$
        
        return result.toString();
    }
    
    /**
     * Converts HTML content to Markdown format using CopyDown library.
     * 
     * @param html the HTML content
     * @return cleaned text in Markdown format
     */
    private String cleanHtmlContent(String html)
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
