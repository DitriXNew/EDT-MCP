/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.debug.core.model.IBreakpoint;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BreakpointUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Sets a 1C BSL line breakpoint via the Eclipse breakpoint framework.
 *
 * <p>Accepts either an EDT module-relative path
 * ({@code "CommonModules/MyModule/Module.bsl"}) or an absolute filesystem path
 * to a {@code .bsl} file. The tool delegates to {@link BreakpointUtils}, which
 * tries the EDT BSL breakpoint class first and falls back to a marker-based
 * implementation if the class is not available on the runtime classpath.
 */
public class SetBreakpointTool implements IMcpTool
{
    public static final String NAME = "set_breakpoint"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Set a line breakpoint on a 1C BSL module. " //$NON-NLS-1$
            + "Accepts either an EDT module-relative path " //$NON-NLS-1$
            + "(e.g. 'CommonModules/Foo/Module.bsl') or an absolute filesystem path. " //$NON-NLS-1$
            + "Use wait_for_break afterwards to block until the breakpoint is hit."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required when module is module-relative)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("module", //$NON-NLS-1$
                "Module identifier — EDT module path (CommonModules/Foo/Module.bsl) or absolute file path (required)", true) //$NON-NLS-1$
            .integerProperty("lineNumber", "1-based line number (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
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
        String module = JsonUtils.extractStringArgument(params, "module"); //$NON-NLS-1$
        int lineNumber = JsonUtils.extractIntArgument(params, "lineNumber", -1); //$NON-NLS-1$

        if (module == null || module.isEmpty())
        {
            return ToolResult.error("module is required").toJson(); //$NON-NLS-1$
        }
        if (lineNumber < 1)
        {
            return ToolResult.error("lineNumber must be >= 1").toJson(); //$NON-NLS-1$
        }

        boolean modulePathStyle = !BreakpointUtils.looksLikeAbsolutePath(module);
        if (modulePathStyle && (projectName == null || projectName.isEmpty()))
        {
            return ToolResult.error(
                    "projectName is required when module is given as an EDT module path").toJson(); //$NON-NLS-1$
        }

        if (modulePathStyle)
        {
            String notReady = ProjectStateChecker.checkReadyOrError(projectName);
            if (notReady != null)
            {
                return ToolResult.error(notReady).toJson();
            }
        }

        IFile file = BreakpointUtils.resolveModuleFile(projectName, module);
        if (file == null || !file.exists())
        {
            return ToolResult.error("Module file not found: " + module //$NON-NLS-1$
                    + (modulePathStyle ? " in project " + projectName : "")).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        try
        {
            IBreakpoint bp = BreakpointUtils.createLineBreakpoint(file, lineNumber);
            long markerId = bp.getMarker() != null ? bp.getMarker().getId() : -1L;
            Activator.logInfo("Breakpoint set: " + file.getFullPath() + ":" + lineNumber); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.success()
                .put("breakpointId", markerId) //$NON-NLS-1$
                .put("module", module) //$NON-NLS-1$
                .put("resolvedFile", file.getFullPath().toString()) //$NON-NLS-1$
                .put("lineNumber", lineNumber) //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Failed to set breakpoint", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set breakpoint: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
