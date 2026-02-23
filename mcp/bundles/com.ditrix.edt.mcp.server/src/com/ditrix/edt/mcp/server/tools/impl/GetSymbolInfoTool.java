/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
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
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextHoverExtension2;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ReflectionUtils;

import io.github.furstenheim.CopyDown;

/**
 * Tool to get type/hover information about a symbol at a specific position in a BSL file.
 * Returns the same information that EDT shows on mouse hover, including inferred types.
 * Falls back to structural EObject analysis if hover is not available.
 */
public class GetSymbolInfoTool implements IMcpTool
{
    public static final String NAME = "get_symbol_info"; //$NON-NLS-1$

    // Dummy URI with .bsl extension used to look up the BSL IResourceServiceProvider from Xtext registry
    private static final URI BSL_LOOKUP_URI = URI.createURI("dummy.bsl"); //$NON-NLS-1$

    // Lazy-initialized CopyDown instance for HTML-to-Markdown conversion (thread-confined to UI thread)
    private CopyDown copyDown; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Get type and hover information about a symbol at a specific position in a BSL module. " + //$NON-NLS-1$
               "Returns inferred types, signatures, and documentation — the same info that EDT shows on mouse hover. " + //$NON-NLS-1$
               "Useful for understanding variable types in dynamically-typed BSL code."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("filePath", //$NON-NLS-1$
                "Path to BSL file relative to project's src folder " + //$NON-NLS-1$
                "(e.g. 'CommonModules/MyModule/Module.bsl')", true) //$NON-NLS-1$
            .integerProperty("line", "Line number (1-based)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("column", "Column number (1-based)", true) //$NON-NLS-1$ //$NON-NLS-2$
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
        String lineStr = JsonUtils.extractStringArgument(params, "line"); //$NON-NLS-1$
        String columnStr = JsonUtils.extractStringArgument(params, "column"); //$NON-NLS-1$
        return "symbol-info-" + (lineStr != null ? lineStr : "0") + //$NON-NLS-1$ //$NON-NLS-2$
               "-" + (columnStr != null ? columnStr : "0") + ".md"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
        String lineStr = JsonUtils.extractStringArgument(params, "line"); //$NON-NLS-1$
        String columnStr = JsonUtils.extractStringArgument(params, "column"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }

        if (filePath == null || filePath.isEmpty())
        {
            return "Error: filePath is required"; //$NON-NLS-1$
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
            return "Error: Invalid line or column number"; //$NON-NLS-1$
        }

        if (line < 1 || column < 1)
        {
            return "Error: Line and column must be >= 1"; //$NON-NLS-1$
        }

        return getSymbolInfo(projectName, filePath, line, column);
    }

    /**
     * Gets symbol info at the specified position.
     * Must run on UI thread to access editors.
     */
    private String getSymbolInfo(String projectName, String filePath, int line, int column)
    {
        // Find the project
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject(projectName);

        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        if (!project.isOpen())
        {
            return "Error: Project is closed: " + projectName; //$NON-NLS-1$
        }

        // Build the full path: project/src/filePath
        IPath relativePath = new Path("src").append(filePath); //$NON-NLS-1$
        IFile file = project.getFile(relativePath);

        if (!file.exists())
        {
            return "Error: File not found: " + relativePath.toString() + //$NON-NLS-1$
                   " in project " + projectName; //$NON-NLS-1$
        }

        final IFile targetFile = file;
        final int targetLine = line;
        final int targetColumn = column;
        final String targetFilePath = filePath;

        AtomicReference<String> resultRef = new AtomicReference<>();

        // Execute on UI thread
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = executeOnUiThread(targetFile, targetLine, targetColumn, targetFilePath);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error getting symbol info", e); //$NON-NLS-1$
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
            }
        });

        String result = resultRef.get();

        // If editor-based approach failed, try EMF fallback
        if (result == null || result.startsWith("Error: No active workbench") //$NON-NLS-1$
            || result.startsWith("Error: Could not open editor")) //$NON-NLS-1$
        {
            String emfResult = getSymbolInfoViaEmf(project, filePath, line, column);
            if (emfResult != null)
            {
                return emfResult;
            }
        }

        return result != null ? result : "Error: Could not get symbol info"; //$NON-NLS-1$
    }

    /**
     * Executes symbol info retrieval on UI thread.
     */
    private String executeOnUiThread(IFile file, int line, int column, String filePath) throws Exception
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
        {
            return "Error: No active workbench window"; //$NON-NLS-1$
        }

        IWorkbenchPage page = window.getActivePage();
        if (page == null)
        {
            return "Error: No active workbench page"; //$NON-NLS-1$
        }

        // Check if the editor is already open to avoid leaving extra tabs
        IEditorInput editorInput = new org.eclipse.ui.part.FileEditorInput(file);
        boolean wasAlreadyOpen = page.findEditor(editorInput) != null;

        // Open or activate the editor
        IEditorPart editorPart = IDE.openEditor(page, file, true);
        if (editorPart == null)
        {
            return "Error: Could not open editor for file"; //$NON-NLS-1$
        }

        try
        {
            // Check if it's an Xtext editor
            XtextEditor xtextEditor = editorPart.getAdapter(XtextEditor.class);
            if (xtextEditor == null)
            {
                return "Error: File is not a BSL module (not an Xtext editor)"; //$NON-NLS-1$
            }

            ISourceViewer sourceViewer = xtextEditor.getInternalSourceViewer();
            if (sourceViewer == null)
            {
                return "Error: Could not get source viewer"; //$NON-NLS-1$
            }

            IDocument document = sourceViewer.getDocument();
            if (document == null)
            {
                return "Error: Could not get document"; //$NON-NLS-1$
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
                    return "Error: Position is outside document bounds"; //$NON-NLS-1$
                }
            }
            catch (BadLocationException e)
            {
                return "Error: Invalid line number: " + line; //$NON-NLS-1$
            }

            // Pre-check: verify there is a meaningful token at the position
            // This prevents EDT hover from returning contextual info for empty positions
            // (spaces, tabs, end of line, comment markers) which confuses AI agents
            if (!hasTokenAtPosition(document, offset, line))
            {
                return "No symbol at this position."; //$NON-NLS-1$
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## Symbol Info at ").append(filePath).append(":") //$NON-NLS-1$ //$NON-NLS-2$
              .append(line).append(":").append(column).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

            // === Level 1: Try hover ===
            String hoverResult = tryGetHoverInfo(sourceViewer, offset);
            if (hoverResult != null && !hoverResult.isEmpty())
            {
                sb.append(hoverResult).append("\n\n"); //$NON-NLS-1$
                sb.append("*Source: editor hover*\n"); //$NON-NLS-1$
                return sb.toString();
            }

            // === Level 2: EObject analysis ===
            IXtextDocument xtextDocument = xtextEditor.getDocument();
            if (xtextDocument != null)
            {
                String eobjectResult = xtextDocument.readOnly(new IUnitOfWork<String, XtextResource>()
                {
                    @Override
                    public String exec(XtextResource resource) throws Exception
                    {
                        if (resource == null)
                        {
                            return null;
                        }
                        return resolveEObjectInfo(resource, offset);
                    }
                });

                if (eobjectResult != null && !eobjectResult.isEmpty())
                {
                    sb.append(eobjectResult).append("\n\n"); //$NON-NLS-1$
                    sb.append("*Source: eobject analysis*\n"); //$NON-NLS-1$
                    return sb.toString();
                }
            }

            // Nothing found at this position
            sb.append("No symbol found at this position.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        finally
        {
            // Close the editor if we opened it (avoid tab pollution)
            if (!wasAlreadyOpen)
            {
                page.closeEditor(editorPart, false);
            }
        }
    }

    /**
     * Tries to get hover information from the editor's text hover mechanism.
     * Uses reflection to access the configured ITextHover from the source viewer.
     */
    private String tryGetHoverInfo(ISourceViewer sourceViewer, int offset)
    {
        try
        {
            // Try to get ITextHover from source viewer internals
            // TextViewer stores hovers in fTextHoverManager or via SourceViewerConfiguration
            ITextHover textHover = getTextHoverViaReflection(sourceViewer, offset);
            if (textHover == null)
            {
                return null;
            }

            IRegion hoverRegion = textHover.getHoverRegion(sourceViewer, offset);
            if (hoverRegion == null)
            {
                return null;
            }

            // Try ITextHoverExtension2 first (richer info)
            if (textHover instanceof ITextHoverExtension2)
            {
                Object info2 = ((ITextHoverExtension2) textHover).getHoverInfo2(sourceViewer, hoverRegion);
                if (info2 != null)
                {
                    String infoStr = extractHoverContent(info2);
                    if (infoStr != null && !infoStr.isEmpty())
                    {
                        return cleanHtmlToMarkdown(infoStr);
                    }
                }
            }

            // Fallback to basic getHoverInfo
            @SuppressWarnings("deprecation")
            String hoverInfo = textHover.getHoverInfo(sourceViewer, hoverRegion);
            if (hoverInfo != null && !hoverInfo.isEmpty())
            {
                return cleanHtmlToMarkdown(hoverInfo);
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not get hover info: " + e.getMessage()); //$NON-NLS-1$
        }

        return null;
    }

    /**
     * Gets the ITextHover from the source viewer via reflection.
     * The TextViewer class stores hovers internally and provides a protected getTextHover method.
     */
    private ITextHover getTextHoverViaReflection(ISourceViewer sourceViewer, int offset)
    {
        try
        {
            // Try to invoke the protected getTextHover(int, int) method
            java.lang.reflect.Method getTextHoverMethod =
                ReflectionUtils.findMethod(sourceViewer.getClass(),
                    "getTextHover", int.class, int.class); //$NON-NLS-1$
            if (getTextHoverMethod != null)
            {
                getTextHoverMethod.setAccessible(true);
                Object result = getTextHoverMethod.invoke(sourceViewer, offset, 0);
                if (result instanceof ITextHover)
                {
                    return (ITextHover) result;
                }
            }

            // Alternative: try to get fTextHover field
            Object hoverField = ReflectionUtils.getFieldValue(sourceViewer, "fTextHover"); //$NON-NLS-1$
            if (hoverField instanceof ITextHover)
            {
                return (ITextHover) hoverField;
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Reflection access to text hover failed: " + e.getMessage()); //$NON-NLS-1$
        }

        return null;
    }

    /**
     * Extracts string content from hover info object.
     * The object may be a String, or an Xtext-specific hover information object
     * that contains HTML content.
     */
    private String extractHoverContent(Object hoverInfo)
    {
        if (hoverInfo instanceof String)
        {
            return (String) hoverInfo;
        }

        // Try to get HTML content from Xtext hover objects via reflection
        try
        {
            // XtextBrowserInformationControlInput has getHtml() method
            Object html = ReflectionUtils.invokeMethod(hoverInfo, "getHtml"); //$NON-NLS-1$
            if (html instanceof String)
            {
                return (String) html;
            }
        }
        catch (Exception e)
        {
            // Try toString as last resort
        }

        // Try getInputElement and then toString
        try
        {
            Object inputElement = ReflectionUtils.invokeMethod(hoverInfo, "getInputElement"); //$NON-NLS-1$
            if (inputElement != null)
            {
                return inputElement.toString();
            }
        }
        catch (Exception e)
        {
            // Ignore
        }

        return hoverInfo.toString();
    }

    /**
     * Resolves the EObject at the given offset and builds info string.
     * Called within IXtextDocument.readOnly() context.
     */
    private String resolveEObjectInfo(XtextResource resource, int offset)
    {
        try
        {
            // Get EObjectAtOffsetHelper from the language service provider
            EObjectAtOffsetHelper offsetHelper = getOffsetHelper();

            if (offsetHelper != null)
            {
                // Try resolveElementAt first (resolves cross-references)
                EObject element = offsetHelper.resolveElementAt(resource, offset);
                if (element != null)
                {
                    return buildEObjectInfo(element);
                }

                // Try resolveContainedElementAt (gets containing element)
                element = offsetHelper.resolveContainedElementAt(resource, offset);
                if (element != null)
                {
                    return buildEObjectInfo(element);
                }
            }

            // Fallback: find leaf node directly
            ICompositeNode rootNode = resource.getParseResult() != null
                ? resource.getParseResult().getRootNode() : null;
            if (rootNode != null)
            {
                ILeafNode leafNode = NodeModelUtils.findLeafNodeAtOffset(rootNode, offset);
                if (leafNode != null)
                {
                    EObject semanticElement = NodeModelUtils.findActualSemanticObjectFor(leafNode);
                    if (semanticElement != null)
                    {
                        return buildEObjectInfo(semanticElement);
                    }

                    // Return at least token info
                    StringBuilder sb = new StringBuilder();
                    sb.append("| Property | Value |\n"); //$NON-NLS-1$
                    sb.append("|----------|-------|\n"); //$NON-NLS-1$
                    sb.append("| **Token** | `").append(leafNode.getText()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    sb.append("| **Grammar** | ").append(leafNode.getGrammarElement() != null //$NON-NLS-1$
                        ? leafNode.getGrammarElement().eClass().getName() : "-").append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    return sb.toString();
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("EObject resolution failed: " + e.getMessage()); //$NON-NLS-1$
        }

        return null;
    }

    /**
     * Gets EObjectAtOffsetHelper from the Xtext language service provider.
     */
    private EObjectAtOffsetHelper getOffsetHelper()
    {
        try
        {
            IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(BSL_LOOKUP_URI);
            if (rsp != null)
            {
                return rsp.get(EObjectAtOffsetHelper.class);
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not get EObjectAtOffsetHelper: " + e.getMessage()); //$NON-NLS-1$
        }

        // Fallback: create new instance
        return new EObjectAtOffsetHelper();
    }

    /**
     * Builds a markdown info table for the given EObject.
     */
    private String buildEObjectInfo(EObject element)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("| Property | Value |\n"); //$NON-NLS-1$
        sb.append("|----------|-------|\n"); //$NON-NLS-1$

        if (element instanceof Method)
        {
            Method method = (Method) element;
            boolean isFunction = element instanceof Function;

            sb.append("| **Symbol** | `").append(method.getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("| **Kind** | ").append(isFunction ? "Function" : "Procedure").append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append("| **Signature** | `").append(BslModuleUtils.buildSignature(method)).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("| **Export** | ").append(method.isExport() ? "Yes" : "No").append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            int startLine = BslModuleUtils.getStartLine(method);
            int endLine = BslModuleUtils.getEndLine(method);
            if (startLine > 0)
            {
                sb.append("| **Lines** | ").append(startLine); //$NON-NLS-1$
                if (endLine > startLine)
                {
                    sb.append(" - ").append(endLine); //$NON-NLS-1$
                }
                sb.append(" |\n"); //$NON-NLS-1$
            }

            // Parameters
            String params = BslModuleUtils.buildParamsString(method);
            if (params != null && !params.isEmpty())
            {
                sb.append("| **Parameters** | ").append(params).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else if (element instanceof FormalParam)
        {
            FormalParam param = (FormalParam) element;
            sb.append("| **Symbol** | `").append(param.getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("| **Kind** | Parameter |\n"); //$NON-NLS-1$
            sb.append("| **ByValue** | ").append(param.isByValue() ? "Yes" : "No").append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            // Show containing method
            EObject container = param.eContainer();
            if (container instanceof Method)
            {
                sb.append("| **In method** | `").append(((Method) container).getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else if (element instanceof StaticFeatureAccess)
        {
            StaticFeatureAccess sfa = (StaticFeatureAccess) element;
            sb.append("| **Symbol** | `").append(sfa.getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("| **Kind** | StaticFeatureAccess |\n"); //$NON-NLS-1$
            sb.append("| **EMF type** | ").append(sfa.eClass().getName()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$

            // Show containing method
            EObject container = findContainingMethod(sfa);
            if (container instanceof Method)
            {
                sb.append("| **In method** | `").append(((Method) container).getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else if (element instanceof DynamicFeatureAccess)
        {
            DynamicFeatureAccess dfa = (DynamicFeatureAccess) element;
            sb.append("| **Symbol** | `").append(dfa.getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("| **Kind** | DynamicFeatureAccess |\n"); //$NON-NLS-1$
            sb.append("| **EMF type** | ").append(dfa.eClass().getName()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$

            // Show containing method
            EObject container = findContainingMethod(dfa);
            if (container instanceof Method)
            {
                sb.append("| **In method** | `").append(((Method) container).getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else if (element instanceof Invocation)
        {
            Invocation invocation = (Invocation) element;
            sb.append("| **Kind** | Invocation |\n"); //$NON-NLS-1$
            sb.append("| **EMF type** | ").append(invocation.eClass().getName()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$

            // Try to get the method name from the feature access
            EObject methodAccess = invocation.getMethodAccess();
            if (methodAccess instanceof StaticFeatureAccess)
            {
                sb.append("| **Symbol** | `").append(((StaticFeatureAccess) methodAccess).getName()) //$NON-NLS-1$
                  .append("` |\n"); //$NON-NLS-1$
            }
            else if (methodAccess instanceof DynamicFeatureAccess)
            {
                sb.append("| **Symbol** | `").append(((DynamicFeatureAccess) methodAccess).getName()) //$NON-NLS-1$
                  .append("` |\n"); //$NON-NLS-1$
            }
        }
        else if (element instanceof Module)
        {
            sb.append("| **Kind** | Module |\n"); //$NON-NLS-1$
            sb.append("| **EMF type** | ").append(element.eClass().getName()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            // Generic EObject info
            sb.append("| **Kind** | ").append(element.eClass().getName()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$

            // Try to get name via reflection
            try
            {
                Object name = ReflectionUtils.invokeMethod(element, "getName"); //$NON-NLS-1$
                if (name instanceof String && !((String) name).isEmpty())
                {
                    sb.append("| **Symbol** | `").append(name).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            catch (Exception e)
            {
                // No getName method — that's OK
            }

            // Show containing method
            EObject container = findContainingMethod(element);
            if (container instanceof Method)
            {
                sb.append("| **In method** | `").append(((Method) container).getName()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            int startLine = BslModuleUtils.getStartLine(element);
            if (startLine > 0)
            {
                sb.append("| **Line** | ").append(startLine).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        return sb.toString();
    }

    /**
     * Finds the containing Method for an EObject by walking up the containment hierarchy.
     */
    private EObject findContainingMethod(EObject element)
    {
        EObject current = element.eContainer();
        while (current != null)
        {
            if (current instanceof Method)
            {
                return current;
            }
            current = current.eContainer();
        }
        return null;
    }

    /**
     * Checks whether there is a meaningful token at the given document position.
     * Returns false for whitespace, end-of-line, and comment lines — positions where
     * EDT hover would return contextual info unrelated to any actual symbol.
     *
     * @param document the document
     * @param offset the character offset in the document
     * @param line the 1-based line number
     * @return true if there is a token (letter, digit, underscore, #) at the position
     */
    private boolean hasTokenAtPosition(IDocument document, int offset, int line)
    {
        try
        {
            // Check if offset is at or past end of document
            if (offset >= document.getLength())
            {
                return false;
            }

            char ch = document.getChar(offset);

            // Whitespace or control characters — no token here
            if (Character.isWhitespace(ch))
            {
                return false;
            }

            // Check if the position is inside a line comment (// ...)
            // Get the line text up to (and including) the current position
            int lineIndex = line - 1;
            int lineOffset = document.getLineOffset(lineIndex);
            int lineLength = document.getLineLength(lineIndex);
            String lineText = document.get(lineOffset, lineLength);

            // Find first // that is not inside a string literal
            int commentStart = findCommentStart(lineText);
            if (commentStart >= 0)
            {
                int columnInLine = offset - lineOffset;
                if (columnInLine >= commentStart)
                {
                    return false;
                }
            }

            // The character is a letter, digit, underscore, #, or other meaningful token
            return true;
        }
        catch (BadLocationException e)
        {
            return false;
        }
    }

    /**
     * Finds the start position of a line comment (//) in BSL code,
     * ignoring // inside string literals.
     *
     * @param lineText the full line text
     * @return index of the comment start, or -1 if no comment found
     */
    private int findCommentStart(String lineText)
    {
        boolean inString = false;
        for (int i = 0; i < lineText.length() - 1; i++)
        {
            char ch = lineText.charAt(i);
            if (ch == '"')
            {
                inString = !inString;
            }
            else if (!inString && ch == '/' && lineText.charAt(i + 1) == '/')
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * Fallback: gets symbol info via EMF model without opening editor.
     */
    private String getSymbolInfoViaEmf(IProject project, String filePath, int line, int column)
    {
        try
        {
            Module module = BslModuleUtils.loadModule(project, filePath);
            if (module == null)
            {
                return null;
            }

            // Read original file content to calculate correct offset
            // (readFileLines strips line terminators, so we read raw content instead)
            IPath relativePath = new Path("src").append(filePath); //$NON-NLS-1$
            IFile file = project.getFile(relativePath);
            String content = null;
            try (java.io.InputStream is = file.getContents())
            {
                content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            // Strip UTF-8 BOM if present
            if (content != null && content.length() > 0 && content.charAt(0) == '\uFEFF')
            {
                content = content.substring(1);
            }

            if (content == null)
            {
                return null;
            }

            // Calculate offset using actual line terminators from the file content
            int offset = 0;
            int currentLine = 1;
            for (int i = 0; i < content.length() && currentLine < line; i++)
            {
                char ch = content.charAt(i);
                if (ch == '\r')
                {
                    currentLine++;
                    // Skip \n after \r (CRLF)
                    if (i + 1 < content.length() && content.charAt(i + 1) == '\n')
                    {
                        i++;
                    }
                }
                else if (ch == '\n')
                {
                    currentLine++;
                }
                offset = i + 1;
            }
            offset += Math.max(0, column - 1);

            // Find node at offset
            ICompositeNode rootNode = NodeModelUtils.getNode(module);
            if (rootNode == null)
            {
                return null;
            }

            ILeafNode leafNode = NodeModelUtils.findLeafNodeAtOffset(rootNode, offset);
            if (leafNode == null)
            {
                return null;
            }

            EObject semanticElement = NodeModelUtils.findActualSemanticObjectFor(leafNode);

            StringBuilder sb = new StringBuilder();
            sb.append("## Symbol Info at ").append(filePath).append(":") //$NON-NLS-1$ //$NON-NLS-2$
              .append(line).append(":").append(column).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

            if (semanticElement != null)
            {
                sb.append(buildEObjectInfo(semanticElement));
            }
            else
            {
                sb.append("| Property | Value |\n"); //$NON-NLS-1$
                sb.append("|----------|-------|\n"); //$NON-NLS-1$
                sb.append("| **Token** | `").append(leafNode.getText()).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            sb.append("\n*Source: emf fallback*\n"); //$NON-NLS-1$
            return sb.toString();
        }
        catch (Exception e)
        {
            Activator.logWarning("EMF fallback failed: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Converts HTML content to Markdown format using CopyDown library.
     * Follows the same pattern as GetContentAssistTool.cleanHtmlContent().
     */
    private String cleanHtmlToMarkdown(String html)
    {
        if (html == null || html.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }

        try
        {
            // Remove <style> blocks before conversion
            String cleaned = html.replaceAll("(?s)<style[^>]*>.*?</style>", ""); //$NON-NLS-1$ //$NON-NLS-2$

            // Convert HTML to Markdown using CopyDown library (lazy init)
            if (copyDown == null)
            {
                copyDown = new CopyDown();
            }
            String markdown = copyDown.convert(cleaned);

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
