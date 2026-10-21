/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.Log;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;

/**
 * Tool to list all workspace projects.
 */
public class ListProjectsTool implements IMcpTool
{
    public static final String NAME = "list_projects"; //$NON-NLS-1$

    /** Existing positional consumers require Reason to remain the last column. */
    static final String TABLE_HEADER = "| Name | State | Path | Open | EDT Project | Natures | Reason |"; //$NON-NLS-1$
    static final String TABLE_SEPARATOR = "|------|-------|------|------|-------------|--------|--------|"; //$NON-NLS-1$

    /** Output-format parameter: {@code md} (default, the human table) or {@code json}. */
    private static final String PARAM_FORMAT = "format"; //$NON-NLS-1$

    /** {@link #PARAM_FORMAT} value selecting the machine payload in {@code structuredContent}. */
    private static final String FORMAT_JSON = "json"; //$NON-NLS-1$

    /** {@link #PARAM_FORMAT} value (the default) selecting the human Markdown table. */
    private static final String FORMAT_MD = "md"; //$NON-NLS-1$


    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Discover projects available in the EDT workspace. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('list_projects')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .enumProperty(PARAM_FORMAT,
                "Output format: 'md' (default) renders the human Markdown table; 'json' returns " //$NON-NLS-1$
                + "{projects:[{name, state, reason?, path, open, edtProject, natures}]} in structuredContent " //$NON-NLS-1$
                + "for a programmatic consumer.", //$NON-NLS-1$
                FORMAT_MD, FORMAT_JSON)
            .build();
    }

    @Override
    public ResponseType getResponseType(Map<String, String> params)
    {
        return isJsonFormat(params) ? ResponseType.JSON : ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        return isJsonFormat(params) ? listProjectsJson() : listProjects();
    }

    /** Whether this call asked for the machine payload ({@code format=json}); default is Markdown. */
    private static boolean isJsonFormat(Map<String, String> params)
    {
        String format = JsonUtils.extractStringArgument(params, PARAM_FORMAT);
        return format != null && FORMAT_JSON.equalsIgnoreCase(format.trim());
    }

    /**
     * The machine-readable project list ({@code format=json}): the SAME rows the Markdown table
     * renders, as {@code {projects:[{name, state, reason?, path, open, edtProject, natures}]}}.
     * {@code edtProject} is a boolean ONLY when the EDT nature was actually determined (an open
     * project whose read succeeded); a closed project, or one whose nature read failed, OMITS it -
     * mirroring the table's Yes/No/- tri-state, so a consumer can tell "not an EDT project" from
     * "not inspected".
     *
     * @return the JSON payload for {@code structuredContent}
     */
    public static String listProjectsJson()
    {
        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (IProject project : workspace.getRoot().getProjects())
            {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", project.getName()); //$NON-NLS-1$
                ProjectStateResult stateResult = ProjectStateChecker.checkProjectState(project);
                row.put("state", stateResult.getStateValue()); //$NON-NLS-1$
                putReason(row, stateResult);
                row.put("path", project.getLocation() != null //$NON-NLS-1$
                    ? project.getLocation().toOSString() : ""); //$NON-NLS-1$
                row.put("open", project.isOpen()); //$NON-NLS-1$

                String edtStatus = "-"; //$NON-NLS-1$
                String naturesStr = "-"; //$NON-NLS-1$
                if (project.isOpen())
                {
                    String[] edtAndNatures = readEdtStatusAndNatures(project);
                    edtStatus = edtAndNatures[0];
                    naturesStr = edtAndNatures[1];
                }
                if ("Yes".equals(edtStatus)) //$NON-NLS-1$
                {
                    row.put("edtProject", Boolean.TRUE); //$NON-NLS-1$
                }
                else if ("No".equals(edtStatus)) //$NON-NLS-1$
                {
                    row.put("edtProject", Boolean.FALSE); //$NON-NLS-1$
                }
                row.put("natures", naturesStr); //$NON-NLS-1$
                rows.add(row);
            }
            return ToolResult.success().put("projects", rows).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Failed to list projects", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }
    
    /**
     * Returns list of workspace projects with their properties.
     * 
     * @return Markdown string with project list
     */
    public static String listProjects()
    {
        StringBuilder md = new StringBuilder();
        
        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject[] projects = workspace.getRoot().getProjects();
            
            md.append("## Workspace Projects\n\n"); //$NON-NLS-1$
            md.append("**Total:** ").append(projects.length).append(" projects\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            
            if (projects.length == 0)
            {
                md.append("*No projects found.*\n"); //$NON-NLS-1$
            }
            else
            {
                md.append(TABLE_HEADER).append('\n');
                md.append(TABLE_SEPARATOR).append('\n');
                
                for (IProject project : projects)
                {
                    ProjectStateResult stateResult = ProjectStateChecker.checkProjectState(project);

                    // EDT project check and natures
                    String edtStatus = "-"; //$NON-NLS-1$
                    String naturesStr = "-"; //$NON-NLS-1$

                    if (project.isOpen())
                    {
                        String[] edtAndNatures = readEdtStatusAndNatures(project);
                        edtStatus = edtAndNatures[0];
                        naturesStr = edtAndNatures[1];
                    }

                    md.append(tableRow(project.getName(), stateResult,
                        project.getLocation() == null ? "" : project.getLocation().toOSString(), //$NON-NLS-1$
                        project.isOpen(), edtStatus, naturesStr));
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to list projects", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }

        return md.toString();
    }

    /** Renders the seven columns in the same order as the header, with Reason last. */
    static String tableRow(String name, ProjectStateResult state, String path, boolean open,
        String edtStatus, String natures)
    {
        return "| " + MarkdownUtils.escapeForTable(name) + " | " + state.getStateValue() //$NON-NLS-1$ //$NON-NLS-2$
            + " | " + MarkdownUtils.escapeForTable(path) + " | " + (open ? "Yes" : "No") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + " | " + edtStatus + " | " + MarkdownUtils.escapeForTable(natures) //$NON-NLS-1$ //$NON-NLS-2$
            + " | " + reasonCell(state) + " |\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** JSON keeps the raw explanation, and ready rows omit the optional field. */
    static void putReason(Map<String, Object> row, ProjectStateResult result)
    {
        if (!result.isReady())
        {
            row.put("reason", result.getReasonText()); //$NON-NLS-1$
        }
    }

    /** The trailing reason must never introduce another table row or column. */
    static String reasonCell(ProjectStateResult result)
    {
        return result.isReady() ? "" : MarkdownUtils.escapeForTable(result.getReasonText()); //$NON-NLS-1$
    }

    /**
     * Reads the EDT-project flag and an abbreviated nature list for an open project.
     * Returns a two-element array {@code [edtStatus, naturesStr]}. A per-project failure
     * is non-fatal: it is logged at WARNING and the corresponding "-" placeholders are
     * returned so the project row still renders.
     *
     * @param project the open project to inspect
     * @return {@code [edtStatus, naturesStr]}
     */
    private static String[] readEdtStatusAndNatures(IProject project)
    {
        String edtStatus = "-"; //$NON-NLS-1$
        String naturesStr = "-"; //$NON-NLS-1$
        try
        {
            boolean isEdtProject = project.hasNature("com._1c.g5.v8.dt.core.V8ConfigurationNature") || //$NON-NLS-1$
                                   project.hasNature("com._1c.g5.v8.dt.core.V8ExtensionNature"); //$NON-NLS-1$
            edtStatus = isEdtProject ? "Yes" : "No"; //$NON-NLS-1$ //$NON-NLS-2$

            String[] natures = project.getDescription().getNatureIds();
            if (natures.length > 0)
            {
                // Show abbreviated natures
                StringBuilder naturesBuilder = new StringBuilder();
                for (int i = 0; i < Math.min(natures.length, 3); i++)
                {
                    if (i > 0)
                    {
                        naturesBuilder.append(", "); //$NON-NLS-1$
                    }
                    // Get short nature name
                    String nature = natures[i];
                    int lastDot = nature.lastIndexOf('.');
                    naturesBuilder.append(lastDot > 0 ? nature.substring(lastDot + 1) : nature);
                }
                if (natures.length > 3)
                {
                    naturesBuilder.append("...+").append(natures.length - 3); //$NON-NLS-1$
                }
                naturesStr = naturesBuilder.toString();
            }
        }
        catch (Exception e)
        {
            // Per-project failure is non-fatal (the row still lists
            // the project with "-" placeholders), but log it at WARNING
            // so a swallowed failure leaves a traceable server-side line.
            Log.warning("list_projects: failed to read nature/state for project '" //$NON-NLS-1$
                + project.getName() + "': " + e.getMessage()); //$NON-NLS-1$
        }
        return new String[] { edtStatus, naturesStr };
    }
}
