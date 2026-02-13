/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResourceFactory;
import org.eclipse.xtext.ui.editor.findrefs.IReferenceFinder;

import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils.MethodInfo;

/**
 * Tool to find all callers of a specific procedure/function through EDT's semantic
 * BM index. Not a text search - uses the real call graph through the AST.
 */
@SuppressWarnings("restriction")
public class GetMethodCallHierarchyTool implements IMcpTool
{
    public static final String NAME = "get_method_call_hierarchy"; //$NON-NLS-1$

    /** URI for getting BSL resource service provider */
    private static final URI BSL_URI = URI.createURI("/nopr/module.bsl"); //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Find all callers of a specific procedure/function using EDT's semantic BM index. " + //$NON-NLS-1$
               "This is NOT a text search - it uses the real call graph through the AST. " + //$NON-NLS-1$
               "Returns module paths, calling method names, and line numbers for each call site."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path to BSL file relative to project's src folder " + //$NON-NLS-1$
                "(e.g. 'CommonModules/MyModule/Module.bsl')", true) //$NON-NLS-1$
            .stringProperty("methodName", //$NON-NLS-1$
                "Name of the procedure or function to find callers for (case-insensitive)", true) //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum number of results. Default: 100") //$NON-NLS-1$
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
        if (methodName != null && !methodName.isEmpty())
        {
            return "callers-" + methodName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "call-hierarchy.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$

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

        int limit = 100;
        if (limitStr != null && !limitStr.isEmpty())
        {
            try
            {
                limit = Math.min((int) Double.parseDouble(limitStr), 500);
            }
            catch (NumberFormatException e)
            {
                // Use default
            }
        }

        // Execute on UI thread (IReferenceFinder may need UI thread context)
        AtomicReference<String> resultRef = new AtomicReference<>();
        final int maxResults = limit;

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = findCallersInternal(projectName, modulePath, methodName, maxResults);
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
     * Internal implementation that finds all callers.
     */
    private String findCallersInternal(String projectName, String modulePath,
        String methodName, int limit)
    {
        IProject project = BslModuleUtils.resolveProject(projectName);
        if (project == null)
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
        if (file == null)
        {
            return "Error: Module not found: " + modulePath; //$NON-NLS-1$
        }

        // Load BSL module AST
        Module module = BslModuleUtils.loadBslModule(file);
        if (module == null)
        {
            return "Error: Failed to load BSL module AST for: " + modulePath; //$NON-NLS-1$
        }

        // Find the target method
        List<MethodInfo> methods = BslModuleUtils.extractMethods(module);
        MethodInfo targetMethodInfo = null;
        Method targetMethod = null;

        int methodIndex = 0;
        for (Method m : module.allMethods())
        {
            if (m.getName().equalsIgnoreCase(methodName))
            {
                targetMethod = m;
                // Find corresponding MethodInfo
                for (MethodInfo mi : methods)
                {
                    if (mi.name.equalsIgnoreCase(methodName))
                    {
                        targetMethodInfo = mi;
                        break;
                    }
                }
                break;
            }
            methodIndex++;
        }

        if (targetMethod == null || targetMethodInfo == null)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("Error: Method '").append(methodName) //$NON-NLS-1$
                .append("' not found in ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

            if (!methods.isEmpty())
            {
                sb.append("**Available methods:**\n"); //$NON-NLS-1$
                for (MethodInfo m : methods)
                {
                    sb.append("- ").append(m.name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            return sb.toString();
        }

        // Get IReferenceFinder
        IResourceServiceProvider rsp =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BSL_URI);
        if (rsp == null)
        {
            return "Error: BSL resource service provider not available"; //$NON-NLS-1$
        }

        IReferenceFinder finder = rsp.get(IReferenceFinder.class);
        if (finder == null)
        {
            return "Error: Reference finder not available"; //$NON-NLS-1$
        }

        // Get URI of the target method
        URI targetUri = EcoreUtil.getURI(targetMethod);
        List<URI> targetURIs = new ArrayList<>();
        targetURIs.add(targetUri);

        // Collect references
        List<CallerInfo> callers = new ArrayList<>();
        final int maxCallers = limit;

        try
        {
            finder.findAllReferences(targetURIs, null, refDesc -> {
                if (callers.size() >= maxCallers)
                {
                    return;
                }
                processReferenceDescription(refDesc, callers);
            }, new NullProgressMonitor());
        }
        catch (Exception e)
        {
            Activator.logError("Error finding references for method", e); //$NON-NLS-1$
            return "Error finding references: " + e.getMessage(); //$NON-NLS-1$
        }

        // Format output
        return formatOutput(modulePath, targetMethodInfo, callers, limit);
    }

    /**
     * Processes a single reference description.
     */
    private void processReferenceDescription(IReferenceDescription refDesc,
        List<CallerInfo> callers)
    {
        URI sourceUri = refDesc.getSourceEObjectUri();
        if (sourceUri == null)
        {
            return;
        }

        // Extract module path from URI
        String callerModulePath = extractModulePath(sourceUri.path());

        // Extract line number
        int line = extractLineNumber(sourceUri);

        callers.add(new CallerInfo(callerModulePath, line));
    }

    /**
     * Extracts module path from URI path.
     * Follows the same pattern as FindReferencesTool.extractModulePath().
     */
    private String extractModulePath(String path)
    {
        if (path == null)
        {
            return "Unknown module"; //$NON-NLS-1$
        }

        int srcIdx = path.indexOf("/src/"); //$NON-NLS-1$
        if (srcIdx >= 0)
        {
            return path.substring(srcIdx + 5);
        }

        // Return last segments
        String[] parts = path.split("/"); //$NON-NLS-1$
        if (parts.length >= 3)
        {
            return parts[parts.length - 3] + "/" + parts[parts.length - 2] //$NON-NLS-1$
                + "/" + parts[parts.length - 1]; //$NON-NLS-1$
        }

        return path;
    }

    /**
     * Extracts line number from sourceEObjectUri by loading the EObject
     * and using NodeModelUtils.
     * Follows the same pattern as FindReferencesTool.extractLineNumberFromSourceUri().
     */
    private int extractLineNumber(URI sourceUri)
    {
        if (sourceUri == null)
        {
            return 0;
        }

        try
        {
            ResourceSet resourceSet = new ResourceSetImpl();

            // Configure resource factory
            IResourceServiceProvider rsp =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(sourceUri);
            if (rsp != null)
            {
                XtextResourceFactory factory = rsp.get(XtextResourceFactory.class);
                if (factory != null)
                {
                    resourceSet.getResourceFactoryRegistry()
                        .getExtensionToFactoryMap().put("bsl", factory); //$NON-NLS-1$
                }
            }

            URI resourceUri = sourceUri.trimFragment();
            Resource resource = resourceSet.getResource(resourceUri, true);

            if (resource != null && sourceUri.fragment() != null)
            {
                EObject eObject = resource.getEObject(sourceUri.fragment());
                if (eObject != null)
                {
                    INode node = NodeModelUtils.findActualNodeFor(eObject);
                    if (node != null)
                    {
                        return node.getStartLine();
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error extracting line number from URI: " + sourceUri, e); //$NON-NLS-1$
        }

        return 0;
    }

    /**
     * Formats the output as markdown.
     */
    private String formatOutput(String modulePath, MethodInfo targetMethod,
        List<CallerInfo> callers, int limit)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("## Call Hierarchy: ").append(targetMethod.name).append(" (callers)\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Module:** ").append(modulePath).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Method:** ").append(targetMethod.signature).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Callers found:** ").append(callers.size()); //$NON-NLS-1$
        if (callers.size() >= limit)
        {
            sb.append(" (limit reached)"); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        if (callers.isEmpty())
        {
            sb.append("No callers found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        // Group by module
        Map<String, List<Integer>> callersByModule = new TreeMap<>();
        for (CallerInfo caller : callers)
        {
            callersByModule.computeIfAbsent(caller.modulePath, k -> new ArrayList<>())
                .add(caller.line);
        }

        // Output grouped by module
        sb.append("| # | Module | Lines |\n"); //$NON-NLS-1$
        sb.append("|---|--------|-------|\n"); //$NON-NLS-1$

        int index = 1;
        for (Map.Entry<String, List<Integer>> entry : callersByModule.entrySet())
        {
            String callerModule = entry.getKey();
            List<Integer> lines = entry.getValue();
            lines.sort(Integer::compareTo);

            // Format lines
            StringBuilder linesStr = new StringBuilder();
            for (int i = 0; i < lines.size(); i++)
            {
                if (i > 0)
                {
                    linesStr.append(", "); //$NON-NLS-1$
                }
                linesStr.append(lines.get(i));
            }

            sb.append("| ").append(index); //$NON-NLS-1$
            sb.append(" | ").append(callerModule); //$NON-NLS-1$
            sb.append(" | ").append(linesStr); //$NON-NLS-1$
            sb.append(" |\n"); //$NON-NLS-1$

            index++;
        }

        return sb.toString();
    }

    /**
     * Holds information about a caller.
     */
    private static class CallerInfo
    {
        final String modulePath;
        final int line;

        CallerInfo(String modulePath, int line)
        {
            this.modulePath = modulePath;
            this.line = line;
        }
    }
}
