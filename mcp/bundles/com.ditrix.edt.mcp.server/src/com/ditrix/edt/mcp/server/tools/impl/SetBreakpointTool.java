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
import com.ditrix.edt.mcp.server.protocol.McpKeys;
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

    /** Input/output key: legacy alias of the module identifier. */
    private static final String KEY_MODULE = "module"; //$NON-NLS-1$

    /** Input/output key: 1-based line number. */
    private static final String KEY_LINE_NUMBER = "lineNumber"; //$NON-NLS-1$

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
            .stringProperty("projectName", "EDT project name (required when modulePath is module-relative)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.MODULE_PATH,
                "Module identifier — EDT module path (CommonModules/Foo/Module.bsl) or absolute file path (required)") //$NON-NLS-1$
            .stringProperty(KEY_MODULE, "Legacy alias for modulePath (deprecated)") //$NON-NLS-1$
            .integerProperty(KEY_LINE_NUMBER, "1-based line number (required)", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("breakpointId", "Eclipse marker id of the created breakpoint (-1 if none)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.MODULE_PATH, "Module identifier as supplied (EDT path or absolute path)") //$NON-NLS-1$
            .stringProperty(KEY_MODULE, "Legacy alias echo of the module identifier") //$NON-NLS-1$
            .stringProperty("resolvedFile", "Workspace-relative path of the resolved .bsl file") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty(KEY_LINE_NUMBER, "1-based line number where the breakpoint was set") //$NON-NLS-1$
            .booleanProperty("degraded", "True when only a marker-only breakpoint could be created") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("warning", "Warning text when the breakpoint is degraded/marker-only") //$NON-NLS-1$ //$NON-NLS-2$
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
        // modulePath is the canonical parameter; "module" is a legacy alias kept for
        // one release. Both resolve through the same BreakpointUtils resolver.
        String modulePath = JsonUtils.extractStringArgument(params, McpKeys.MODULE_PATH);
        String module = (modulePath != null && !modulePath.isEmpty())
            ? modulePath
            : JsonUtils.extractStringArgument(params, KEY_MODULE);
        int lineNumber = JsonUtils.extractIntArgument(params, KEY_LINE_NUMBER, -1);

        if (module == null || module.isEmpty())
        {
            return ToolResult.error("modulePath is required").toJson(); //$NON-NLS-1$
        }
        if (lineNumber < 1)
        {
            return ToolResult.error("lineNumber must be >= 1").toJson(); //$NON-NLS-1$
        }

        boolean modulePathStyle = !BreakpointUtils.looksLikeAbsolutePath(module);
        if (modulePathStyle && (projectName == null || projectName.isEmpty()))
        {
            return ToolResult.error(
                    "projectName is required when modulePath is given as an EDT module path").toJson(); //$NON-NLS-1$
        }

        if (modulePathStyle)
        {
            // Refuse only the transient BUILDING state; a missing/closed project
            // falls through to the value-naming 'Project not found' below.
            String building = ProjectStateChecker.buildingErrorOrNull(projectName);
            if (building != null)
            {
                return ToolResult.error(building).toJson();
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
            boolean degraded = bp instanceof BreakpointUtils.MarkerOnlyBreakpoint;
            Activator.logInfo("Breakpoint set: " + file.getFullPath() + ":" + lineNumber //$NON-NLS-1$ //$NON-NLS-2$
                + (degraded ? " (degraded — marker-only)" : "")); //$NON-NLS-1$ //$NON-NLS-2$
            ToolResult res = ToolResult.success()
                .put("breakpointId", markerId) //$NON-NLS-1$
                .put(McpKeys.MODULE_PATH, module)
                .put(KEY_MODULE, module)
                .put("resolvedFile", file.getFullPath().toString()) //$NON-NLS-1$
                .put(KEY_LINE_NUMBER, lineNumber);
            if (degraded)
            {
                res.put("degraded", true); //$NON-NLS-1$
                res.put("warning", "EDT BSL breakpoint class not available — created a marker-only " //$NON-NLS-1$ //$NON-NLS-2$
                    + "breakpoint that may NOT trigger debug suspend events. " //$NON-NLS-1$
                    + "Verify in EDT that the breakpoint appears in the Breakpoints view."); //$NON-NLS-1$
            }
            return res.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Failed to set breakpoint", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set breakpoint: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
