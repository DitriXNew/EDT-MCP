/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.bsl.model.Module;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils.MethodInfo;

/**
 * Tool to extract a specific procedure/function source code from a BSL module.
 * Instead of reading 60,000 lines, reads only the needed 50 lines of a specific method.
 */
@SuppressWarnings("restriction")
public class ReadMethodSourceTool implements IMcpTool
{
    public static final String NAME = "read_method_source"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Extract a specific procedure or function source code from a BSL module. " + //$NON-NLS-1$
               "Instead of reading the entire module (which can be tens of thousands of lines), " + //$NON-NLS-1$
               "reads only the specific method by name."; //$NON-NLS-1$
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
                "Name of the procedure or function to extract (case-insensitive)", true) //$NON-NLS-1$
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
            return "method-" + methodName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "method-source.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$

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

        // Extract all methods
        List<MethodInfo> methods = BslModuleUtils.extractMethods(module);

        // Find method by name (case-insensitive)
        MethodInfo targetMethod = null;
        for (MethodInfo method : methods)
        {
            if (method.name.equalsIgnoreCase(methodName))
            {
                targetMethod = method;
                break;
            }
        }

        if (targetMethod == null)
        {
            // Method not found - provide list of available methods
            StringBuilder sb = new StringBuilder();
            sb.append("Error: Method '").append(methodName) //$NON-NLS-1$
                .append("' not found in ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

            if (!methods.isEmpty())
            {
                sb.append("**Available methods:**\n"); //$NON-NLS-1$
                for (MethodInfo m : methods)
                {
                    sb.append("- ").append(m.name); //$NON-NLS-1$
                    sb.append(" (").append(m.isFunction ? "Function" : "Procedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    sb.append(", lines ").append(m.startLine).append("-").append(m.endLine); //$NON-NLS-1$ //$NON-NLS-2$
                    sb.append(")\n"); //$NON-NLS-1$
                }
            }
            else
            {
                sb.append("The module contains no methods.\n"); //$NON-NLS-1$
            }

            return sb.toString();
        }

        // Read the method source
        String content = BslModuleUtils.readFileContent(file,
            targetMethod.startLine, targetMethod.endLine);
        if (content == null)
        {
            return "Error: Failed to read method source"; //$NON-NLS-1$
        }

        // Format output
        StringBuilder sb = new StringBuilder();
        sb.append("## Method: ").append(targetMethod.name).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        sb.append("**Module:** ").append(modulePath).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Type:** ").append(targetMethod.isFunction ? "Function" : "Procedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append(" | **Export:** ").append(targetMethod.isExport ? "Yes" : "No"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        if (targetMethod.pragmas != null && !targetMethod.pragmas.isEmpty())
        {
            sb.append(" | **Context:** ").append(targetMethod.pragmas); //$NON-NLS-1$
        }
        if (targetMethod.regionName != null)
        {
            sb.append(" | **Region:** ").append(targetMethod.regionName); //$NON-NLS-1$
        }

        sb.append("\n"); //$NON-NLS-1$
        sb.append("**Lines:** ").append(targetMethod.startLine) //$NON-NLS-1$
            .append("-").append(targetMethod.endLine).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        sb.append("```bsl\n"); //$NON-NLS-1$
        sb.append(content);
        sb.append("```\n"); //$NON-NLS-1$

        return sb.toString();
    }
}
