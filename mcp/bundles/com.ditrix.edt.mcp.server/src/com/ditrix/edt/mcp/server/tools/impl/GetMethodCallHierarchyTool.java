/**
 * Copyright (c) 2026 Diversus
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;

/**
 * Tool to find method call hierarchy - who calls this method (callers)
 * or what this method calls (callees).
 * Uses IReferenceFinder and BSL AST for semantic analysis.
 */
@SuppressWarnings("restriction")
public class GetMethodCallHierarchyTool implements IMcpTool
{
    public static final String NAME = "get_method_call_hierarchy"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Find method call hierarchy: who calls this method (callers) " + //$NON-NLS-1$
               "or what this method calls (callees). " + //$NON-NLS-1$
               "Uses semantic BSL analysis via BM-index, not text search."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path from src/ folder, e.g. 'CommonModules/MyModule/Module.bsl' (required)", true) //$NON-NLS-1$
            .stringProperty("methodName", //$NON-NLS-1$
                "Name of the procedure/function (case-insensitive, required)", true) //$NON-NLS-1$
            .stringProperty("direction", //$NON-NLS-1$
                "Direction: 'callers' (who calls this method, default) or 'callees' (what this method calls)") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum number of results. Default: 100, max: 500") //$NON-NLS-1$
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
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        String direction = JsonUtils.extractStringArgument(params, "direction"); //$NON-NLS-1$
        if (methodName != null && !methodName.isEmpty())
        {
            return "call-hierarchy-" + methodName.toLowerCase() + //$NON-NLS-1$
                   "-" + (direction != null ? direction : "callers") + ".md"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return "call-hierarchy.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        String direction = JsonUtils.extractStringArgument(params, "direction"); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", 100); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (modulePath == null || modulePath.isEmpty())
        {
            return "Error: modulePath is required"; //$NON-NLS-1$
        }
        if (methodName == null || methodName.isEmpty())
        {
            return "Error: methodName is required"; //$NON-NLS-1$
        }

        if (direction == null || direction.isEmpty())
        {
            direction = "callers"; //$NON-NLS-1$
        }
        direction = direction.toLowerCase();

        if (!"callers".equals(direction) && !"callees".equals(direction)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return "Error: direction must be 'callers' or 'callees'"; //$NON-NLS-1$
        }

        limit = Math.min(Math.max(1, limit), 500);

        AtomicReference<String> resultRef = new AtomicReference<>();
        final String dir = direction;
        final int maxResults = limit;

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result;
                if ("callers".equals(dir)) //$NON-NLS-1$
                {
                    result = findCallers(projectName, modulePath, methodName, maxResults);
                }
                else
                {
                    result = findCallees(projectName, modulePath, methodName, maxResults);
                }
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error finding call hierarchy", e); //$NON-NLS-1$
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
            }
        });

        return resultRef.get();
    }

    /**
     * Finds all callers of the specified method using IReferenceFinder.
     */
    private String findCallers(String projectName, String modulePath, String methodName, int limit)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        Module module = BslModuleUtils.loadModule(project, modulePath);
        if (module == null)
        {
            return "Error: Could not load EMF model for " + modulePath + //$NON-NLS-1$
                   ". Call hierarchy requires BSL AST (EMF). Check EDT Error Log for details."; //$NON-NLS-1$
        }

        Method method = BslModuleUtils.findMethod(module, methodName);
        if (method == null)
        {
            return BslModuleUtils.buildMethodNotFoundResponse(module, modulePath, methodName);
        }

        // Get URI of the method
        URI methodUri = EcoreUtil.getURI(method);

        // Find references using IReferenceFinder
        IResourceServiceProvider resourceServiceProvider =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BslModuleUtils.BSL_LOOKUP_URI);
        if (resourceServiceProvider == null)
        {
            return "Error: BSL resource service provider not available"; //$NON-NLS-1$
        }

        IReferenceFinder finder = resourceServiceProvider.get(IReferenceFinder.class);
        if (finder == null)
        {
            return "Error: Reference finder not available"; //$NON-NLS-1$
        }

        // Collect references — reuse a single ResourceSet for resolving all references
        List<CallerInfo> callers = new ArrayList<>();
        List<URI> targetURIs = new ArrayList<>();
        targetURIs.add(methodUri);
        final int[] totalReferences = {0};

        // Create shared ResourceSet and configure BSL factory once
        final org.eclipse.emf.ecore.resource.ResourceSet sharedResourceSet =
            new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl();
        try
        {
            org.eclipse.xtext.resource.XtextResourceFactory factory =
                resourceServiceProvider.get(org.eclipse.xtext.resource.XtextResourceFactory.class);
            if (factory != null)
            {
                sharedResourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap()
                    .put("bsl", factory); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to configure XtextResourceFactory: " + e.getMessage()); //$NON-NLS-1$
        }

        try
        {
            finder.findAllReferences(targetURIs, null, refDesc -> {
                totalReferences[0]++;
                if (callers.size() < limit)
                {
                    CallerInfo caller = extractCallerInfo(refDesc, sharedResourceSet);
                    if (caller != null)
                    {
                        callers.add(caller);
                    }
                }
            }, new NullProgressMonitor());
        }
        catch (Exception e)
        {
            Activator.logError("Error finding callers", e); //$NON-NLS-1$
        }
        finally
        {
            // Clean up shared ResourceSet to prevent memory leaks
            for (org.eclipse.emf.ecore.resource.Resource res : sharedResourceSet.getResources())
            {
                try
                {
                    res.unload();
                }
                catch (Exception e)
                {
                    // Ignore cleanup errors
                }
            }
            sharedResourceSet.getResources().clear();
        }

        return formatCallersOutput(modulePath, methodName, callers, limit, totalReferences[0]);
    }

    /**
     * Finds all callees from the specified method by traversing its AST.
     */
    private String findCallees(String projectName, String modulePath, String methodName, int limit)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        Module module = BslModuleUtils.loadModule(project, modulePath);
        if (module == null)
        {
            return "Error: Could not load EMF model for " + modulePath + //$NON-NLS-1$
                   ". Call hierarchy requires BSL AST (EMF). Check EDT Error Log for details."; //$NON-NLS-1$
        }

        Method method = BslModuleUtils.findMethod(module, methodName);
        if (method == null)
        {
            return BslModuleUtils.buildMethodNotFoundResponse(module, modulePath, methodName);
        }

        // Traverse AST of this method to find invocations
        List<CalleeInfo> callees = new ArrayList<>();
        int totalInvocations = 0;

        Iterator<EObject> iter = method.eAllContents();
        while (iter.hasNext())
        {
            EObject obj = iter.next();

            String calledName = null;
            int line = 0;

            if (obj instanceof Invocation)
            {
                Invocation inv = (Invocation) obj;
                EObject methodAccess = inv.getMethodAccess();
                if (methodAccess instanceof StaticFeatureAccess)
                {
                    calledName = ((StaticFeatureAccess) methodAccess).getName();
                }
                else if (methodAccess instanceof DynamicFeatureAccess)
                {
                    calledName = ((DynamicFeatureAccess) methodAccess).getName();
                }
                line = BslModuleUtils.getStartLine(inv);
            }

            if (calledName != null && !calledName.isEmpty())
            {
                totalInvocations++;

                if (callees.size() < limit)
                {
                    CalleeInfo callee = new CalleeInfo();
                    callee.calledMethodName = calledName;
                    callee.line = line;

                    // Get source text around the invocation
                    INode node = NodeModelUtils.findActualNodeFor(obj);
                    if (node != null)
                    {
                        String text = node.getText();
                        if (text != null)
                        {
                            text = stripCommentLines(text);
                            if (text.length() > 100)
                            {
                                text = smartTruncateCall(text, calledName);
                            }
                            callee.callCode = text;
                        }
                    }

                    callees.add(callee);
                }
            }
        }

        return formatCalleesOutput(modulePath, methodName, callees, limit, totalInvocations);
    }

    // ========== Helper methods ==========

    private CallerInfo extractCallerInfo(IReferenceDescription refDesc,
                                         org.eclipse.emf.ecore.resource.ResourceSet sharedResourceSet)
    {
        URI sourceUri = refDesc.getSourceEObjectUri();
        if (sourceUri == null)
        {
            return null;
        }

        CallerInfo caller = new CallerInfo();

        // Extract module path
        String path = sourceUri.path();
        caller.modulePath = BslModuleUtils.extractModulePath(path);

        // Try to get line number using shared ResourceSet (caches loaded resources)
        try
        {
            URI resourceUri = sourceUri.trimFragment();
            org.eclipse.emf.ecore.resource.Resource resource =
                sharedResourceSet.getResource(resourceUri, true);

            if (resource != null && sourceUri.fragment() != null)
            {
                EObject eObject = resource.getEObject(sourceUri.fragment());
                if (eObject != null)
                {
                    // Get method name from reference
                    String refName = null;
                    if (eObject instanceof StaticFeatureAccess)
                    {
                        refName = ((StaticFeatureAccess) eObject).getName();
                    }
                    else if (eObject instanceof DynamicFeatureAccess)
                    {
                        refName = ((DynamicFeatureAccess) eObject).getName();
                    }

                    // Navigate to parent Invocation for full call text
                    EObject invocationObj = eObject;
                    while (invocationObj != null && !(invocationObj instanceof Invocation))
                    {
                        invocationObj = invocationObj.eContainer();
                    }

                    INode callNode = (invocationObj instanceof Invocation)
                        ? NodeModelUtils.findActualNodeFor(invocationObj)
                        : NodeModelUtils.findActualNodeFor(eObject);

                    if (callNode != null)
                    {
                        caller.line = callNode.getStartLine();

                        // Get call source text (filter out comment lines)
                        String text = callNode.getText();
                        if (text != null)
                        {
                            text = stripCommentLines(text);
                            if (text.length() > 100)
                            {
                                text = smartTruncateCall(text, refName);
                            }
                            caller.callCode = text;
                        }
                    }

                    // Find containing method
                    EObject parent = eObject;
                    while (parent != null && !(parent instanceof Method))
                    {
                        parent = parent.eContainer();
                    }
                    if (parent instanceof Method)
                    {
                        caller.callerMethodName = ((Method) parent).getName();
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error extracting caller info", e); //$NON-NLS-1$
        }

        return caller;
    }

    /**
     * Removes single-line comment lines (// ...) from multi-line node text.
     * Prevents comments from merging with code when displayed in table cells.
     */
    private String stripCommentLines(String text)
    {
        if (text == null || text.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }

        String[] lines = text.split("\\r?\\n"); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (String line : lines)
        {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//")) //$NON-NLS-1$
            {
                if (sb.length() > 0)
                {
                    sb.append(' ');
                }
                sb.append(trimmed);
            }
        }
        return sb.length() > 0 ? sb.toString() : text.trim();
    }

    /**
     * Smart truncation for long call expressions.
     * Short calls shown as-is: "Foo(arg1, arg2)".
     * Long calls: "MethodName(...)".
     */
    private String smartTruncateCall(String text, String methodName)
    {
        if (methodName != null && !methodName.isEmpty())
        {
            int nameIdx = text.indexOf(methodName);
            if (nameIdx >= 0)
            {
                return text.substring(0, nameIdx + methodName.length()) + "(...)"; //$NON-NLS-1$
            }
        }
        return text.substring(0, Math.min(text.length(), 100)) + "..."; //$NON-NLS-1$
    }

    private String formatCallersOutput(String modulePath, String methodName,
                                        List<CallerInfo> callers, int limit, int totalReferences)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Call Hierarchy: ").append(modulePath).append(" :: ").append(methodName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Direction:** Callers (who calls this method)\n"); //$NON-NLS-1$
        sb.append("**Total references found:** ").append(totalReferences); //$NON-NLS-1$
        if (callers.size() < totalReferences)
        {
            sb.append(" (showing first ").append(callers.size()).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        if (callers.isEmpty())
        {
            sb.append("No callers found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| # | Module | Method | Line | Call Code |\n"); //$NON-NLS-1$
        sb.append("|---|--------|--------|------|-----------|\n"); //$NON-NLS-1$

        int idx = 1;
        for (CallerInfo caller : callers)
        {
            sb.append("| ").append(idx++); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable( //$NON-NLS-1$
                caller.modulePath != null ? caller.modulePath : "-")); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable( //$NON-NLS-1$
                caller.callerMethodName != null ? caller.callerMethodName : "-")); //$NON-NLS-1$
            sb.append(" | ").append(caller.line > 0 ? String.valueOf(caller.line) : "-"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(" | `").append(MarkdownUtils.escapeForTable( //$NON-NLS-1$
                caller.callCode != null ? caller.callCode : "-")).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return sb.toString();
    }

    private String formatCalleesOutput(String modulePath, String methodName,
                                        List<CalleeInfo> callees, int limit, int totalInvocations)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Call Hierarchy: ").append(modulePath).append(" :: ").append(methodName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Direction:** Callees (what this method calls)\n"); //$NON-NLS-1$
        sb.append("**Total calls found:** ").append(totalInvocations); //$NON-NLS-1$
        if (callees.size() < totalInvocations)
        {
            sb.append(" (showing first ").append(callees.size()).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        if (callees.isEmpty())
        {
            sb.append("No calls found in this method.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| # | Called Method | Line | Call Code |\n"); //$NON-NLS-1$
        sb.append("|---|--------------|------|-----------|\n"); //$NON-NLS-1$

        int idx = 1;
        for (CalleeInfo callee : callees)
        {
            sb.append("| ").append(idx++); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(callee.calledMethodName)); //$NON-NLS-1$
            sb.append(" | ").append(callee.line > 0 ? String.valueOf(callee.line) : "-"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(" | `").append(MarkdownUtils.escapeForTable( //$NON-NLS-1$
                callee.callCode != null ? callee.callCode : "-")).append("` |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return sb.toString();
    }

    // ========== Data structures ==========

    private static class CallerInfo
    {
        String modulePath;
        String callerMethodName;
        int line;
        String callCode;
    }

    private static class CalleeInfo
    {
        String calledMethodName;
        int line;
        String callCode;
    }
}
