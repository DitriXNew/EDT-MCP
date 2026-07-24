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
        return "List all workspace projects with properties (name, path, type, natures). " //$NON-NLS-1$
            + "format='md' (default) returns the human Markdown table; format='json' returns the " //$NON-NLS-1$
            + "machine-readable project list in structuredContent."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .enumProperty(PARAM_FORMAT,
                "Output format: 'md' (default) renders the human Markdown table; 'json' returns " //$NON-NLS-1$
                + "{projects:[{name, state, path, open, edtProject, natures}]} in structuredContent " //$NON-NLS-1$
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
     * renders, as {@code {projects:[{name, state, path, open, edtProject, natures}]}}.
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
                row.put("state", ProjectStateChecker.checkProjectState(project).getStateValue()); //$NON-NLS-1$
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
                // Table header - added State column
                md.append("| Name | State | Path | Open | EDT Project | Natures |\n"); //$NON-NLS-1$
                md.append("|------|-------|------|------|-------------|--------|\n"); //$NON-NLS-1$
                
                for (IProject project : projects)
                {
                    md.append("| "); //$NON-NLS-1$
                    md.append(MarkdownUtils.escapeForTable(project.getName()));
                    md.append(" | "); //$NON-NLS-1$
                    
                    // Project state
                    ProjectStateResult stateResult = ProjectStateChecker.checkProjectState(project);
                    md.append(stateResult.getStateValue());
                    md.append(" | "); //$NON-NLS-1$
                    
                    md.append(MarkdownUtils.escapeForTable(project.getLocation() != null ? 
                        project.getLocation().toOSString() : "")); //$NON-NLS-1$
                    md.append(" | "); //$NON-NLS-1$
                    md.append(project.isOpen() ? "Yes" : "No"); //$NON-NLS-1$ //$NON-NLS-2$
                    md.append(" | "); //$NON-NLS-1$
                    
                    // EDT project check and natures
                    String edtStatus = "-"; //$NON-NLS-1$
                    String naturesStr = "-"; //$NON-NLS-1$

                    if (project.isOpen())
                    {
                        String[] edtAndNatures = readEdtStatusAndNatures(project);
                        edtStatus = edtAndNatures[0];
                        naturesStr = edtAndNatures[1];
                    }

                    md.append(edtStatus);
                    md.append(" | "); //$NON-NLS-1$
                    md.append(MarkdownUtils.escapeForTable(naturesStr));
                    md.append(" |\n"); //$NON-NLS-1$
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
