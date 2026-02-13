/**
 * Copyright (c) 2026 Diversus
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import org.eclipse.xtext.resource.IResourceServiceProvider;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Utility class for loading BSL modules and working with BSL AST.
 * Shared between get_module_structure, read_method_source, and get_method_call_hierarchy tools.
 */
public final class BslModuleUtils
{
    private BslModuleUtils()
    {
        // Utility class
    }

    /** Dummy BSL URI for IResourceServiceProvider lookup */
    public static final URI BSL_LOOKUP_URI = URI.createURI("/nopr/module.bsl"); //$NON-NLS-1$

    /** Regex for BSL method start (Russian and English). Group 1 = method name, group 2 = params text after '(' */
    public static final Pattern METHOD_START_PATTERN = Pattern.compile(
        "^\\s*(?:\u041f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u0430|\u0424\u0443\u043d\u043a\u0446\u0438\u044f|Procedure|Function)\\s+(\\S+?)\\s*\\((.*)$", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Regex for BSL method end (Russian and English) */
    public static final Pattern METHOD_END_PATTERN = Pattern.compile(
        "^\\s*(?:\u041a\u043e\u043d\u0435\u0446\u041f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u044b|\u041a\u043e\u043d\u0435\u0446\u0424\u0443\u043d\u043a\u0446\u0438\u0438|EndProcedure|EndFunction)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Regex for function keyword check */
    public static final Pattern FUNC_KEYWORD_PATTERN = Pattern.compile(
        "^\\s*(?:\u0424\u0443\u043d\u043a\u0446\u0438\u044f|Function)\\s", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Loads BSL Module EMF model via BmAwareResourceSetProvider.
     * Tries ServiceTracker first, falls back to IResourceServiceProvider (Guice injector).
     *
     * @param project the EDT project
     * @param modulePath path from src/, e.g. "CommonModules/MyModule/Module.bsl"
     * @return loaded Module or null if not found
     */
    public static Module loadModule(IProject project, String modulePath)
    {
        // Try to obtain BmAwareResourceSetProvider
        BmAwareResourceSetProvider resourceSetProvider = Activator.getDefault().getResourceSetProvider();

        // Fallback: obtain via IResourceServiceProvider (Guice injector) —
        // BmAwareResourceSetProvider may not be registered as OSGi service
        if (resourceSetProvider == null)
        {
            Activator.logInfo("BmAwareResourceSetProvider not found via ServiceTracker, trying IResourceServiceProvider"); //$NON-NLS-1$
            try
            {
                IResourceServiceProvider rsp =
                    IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BSL_LOOKUP_URI);
                if (rsp != null)
                {
                    resourceSetProvider = rsp.get(BmAwareResourceSetProvider.class);
                }
            }
            catch (Exception e)
            {
                Activator.logError("Failed to get BmAwareResourceSetProvider via IResourceServiceProvider", e); //$NON-NLS-1$
            }
        }

        if (resourceSetProvider == null)
        {
            Activator.logWarning("BmAwareResourceSetProvider not available (neither ServiceTracker nor IResourceServiceProvider)"); //$NON-NLS-1$
            return null;
        }

        ResourceSet resourceSet = resourceSetProvider.get(project);
        if (resourceSet == null)
        {
            Activator.logWarning("ResourceSet is null for project: " + project.getName()); //$NON-NLS-1$
            return null;
        }

        // Use createPlatformResourceURI for proper encoding (handles Cyrillic paths)
        URI uri = URI.createPlatformResourceURI(project.getName() + "/src/" + modulePath, true); //$NON-NLS-1$
        Activator.logInfo("Loading BSL module: " + uri.toString()); //$NON-NLS-1$

        try
        {
            Resource resource = resourceSet.getResource(uri, true);
            if (resource == null)
            {
                Activator.logWarning("Resource is null for URI: " + uri); //$NON-NLS-1$
                return null;
            }
            if (resource.getContents().isEmpty())
            {
                Activator.logWarning("Resource contents empty for URI: " + uri); //$NON-NLS-1$
                return null;
            }
            EObject root = resource.getContents().get(0);
            if (root instanceof Module)
            {
                return (Module) root;
            }
            Activator.logWarning("Resource root is " + root.getClass().getName() + ", not Module for: " + uri); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Activator.logError("Failed to load BSL module: " + uri, e); //$NON-NLS-1$
        }

        return null;
    }

    /**
     * Finds a method by name (case-insensitive) in a Module.
     *
     * @param module the BSL module
     * @param methodName the method name to find
     * @return found Method or null
     */
    public static Method findMethod(Module module, String methodName)
    {
        if (module == null || methodName == null)
        {
            return null;
        }

        for (Method method : module.allMethods())
        {
            if (methodName.equalsIgnoreCase(method.getName()))
            {
                return method;
            }
        }

        return null;
    }

    /**
     * Reads all lines from an IFile with UTF-8 BOM detection.
     * BSL files in EDT are typically saved as UTF-8 with BOM.
     *
     * @param file the IFile to read
     * @return list of lines
     * @throws Exception if reading fails
     */
    public static List<String> readFileLines(IFile file) throws Exception
    {
        // Try IFile.getContents() first (workspace API); fall back to filesystem
        // if workspace is not synchronized (common in large projects)
        InputStream rawIs;
        try
        {
            rawIs = file.getContents();
        }
        catch (Exception e)
        {
            // Fallback: read directly from filesystem, bypassing workspace sync
            java.io.File fsFile = file.getLocation() != null
                ? file.getLocation().toFile() : null;
            if (fsFile == null || !fsFile.exists())
            {
                throw e; // Re-throw original if filesystem path not available
            }
            rawIs = new FileInputStream(fsFile);
        }

        List<String> lines = new ArrayList<>();
        // Wrap in BufferedInputStream to support mark/reset for BOM detection;
        // rawIs is closed by try-with-resources since BufferedInputStream wraps it
        try (InputStream input = new BufferedInputStream(rawIs))
        {
            // Detect UTF-8 BOM (EF BB BF)
            input.mark(3);
            byte[] bom = new byte[3];
            int bomRead = input.read(bom);
            boolean isUtf8Bom = bomRead == 3
                && (bom[0] & 0xFF) == 0xEF
                && (bom[1] & 0xFF) == 0xBB
                && (bom[2] & 0xFF) == 0xBF;
            if (!isUtf8Bom)
            {
                input.reset();
            }
            // BSL files in EDT are always UTF-8
            String charset = "UTF-8"; //$NON-NLS-1$
            if (!isUtf8Bom)
            {
                try
                {
                    charset = file.getCharset();
                }
                catch (Exception ce)
                {
                    charset = "UTF-8"; //$NON-NLS-1$
                }
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, charset)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    /**
     * Extracts module path from EMF URI (removes /src/ prefix).
     *
     * @param path URI path string
     * @return module path relative to src/
     */
    public static String extractModulePath(String path)
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

        return path;
    }

    /**
     * Gets the start line number of an EObject via NodeModelUtils.
     *
     * @param eObject the EObject
     * @return 1-based start line, or 0 if not found
     */
    public static int getStartLine(EObject eObject)
    {
        if (eObject == null)
        {
            return 0;
        }

        INode node = NodeModelUtils.findActualNodeFor(eObject);
        if (node != null)
        {
            return node.getStartLine();
        }

        return 0;
    }

    /**
     * Gets the end line number of an EObject via NodeModelUtils.
     *
     * @param eObject the EObject
     * @return 1-based end line, or 0 if not found
     */
    public static int getEndLine(EObject eObject)
    {
        if (eObject == null)
        {
            return 0;
        }

        INode node = NodeModelUtils.findActualNodeFor(eObject);
        if (node != null)
        {
            return node.getEndLine();
        }

        return 0;
    }

    /**
     * Gets the source text of an EObject via NodeModelUtils.
     *
     * @param eObject the EObject
     * @return source text or null if not found
     */
    public static String getSourceText(EObject eObject)
    {
        if (eObject == null)
        {
            return null;
        }

        INode node = NodeModelUtils.findActualNodeFor(eObject);
        if (node != null)
        {
            return node.getText();
        }

        return null;
    }

    /**
     * Builds an error response when a method is not found, listing all available methods.
     *
     * @param module the BSL module
     * @param modulePath the module path for display
     * @param methodName the method name that was not found
     * @return formatted error message with available methods
     */
    public static String buildMethodNotFoundResponse(Module module, String modulePath, String methodName)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Error: Method '").append(methodName).append("' not found in ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<String> methodNames = new ArrayList<>();
        for (Method m : module.allMethods())
        {
            methodNames.add(m.getName());
        }

        sb.append("**Available methods** (").append(methodNames.size()).append("):\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : methodNames)
        {
            sb.append("- ").append(name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return sb.toString();
    }

    /**
     * Builds a full method signature string from EMF Method model.
     * E.g. "Function MyFunc(Param1, Val Param2 = 0) Export"
     *
     * @param method the BSL method
     * @return formatted signature string
     */
    public static String buildSignature(Method method)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(method instanceof Function ? "Function " : "Procedure "); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(method.getName()).append("("); //$NON-NLS-1$
        sb.append(buildParamsString(method));
        sb.append(")"); //$NON-NLS-1$
        if (method.isExport())
        {
            sb.append(" Export"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Builds a parameters string from EMF Method model.
     * E.g. "Param1, Val Param2 = 0"
     *
     * @param method the BSL method
     * @return formatted parameters string, or "-" if no parameters
     */
    public static String buildParamsString(Method method)
    {
        StringBuilder paramsBuilder = new StringBuilder();
        EList<FormalParam> formalParams = method.getFormalParams();
        if (formalParams != null)
        {
            for (int i = 0; i < formalParams.size(); i++)
            {
                FormalParam param = formalParams.get(i);
                if (i > 0)
                {
                    paramsBuilder.append(", "); //$NON-NLS-1$
                }
                if (param.isByValue())
                {
                    paramsBuilder.append("Val "); //$NON-NLS-1$
                }
                paramsBuilder.append(param.getName());
                if (param.getDefaultValue() != null)
                {
                    String defaultText = getSourceText(param.getDefaultValue());
                    if (defaultText != null)
                    {
                        paramsBuilder.append(" = ").append(defaultText.trim()); //$NON-NLS-1$
                    }
                }
            }
        }
        return paramsBuilder.length() > 0 ? paramsBuilder.toString() : "-"; //$NON-NLS-1$
    }
}
