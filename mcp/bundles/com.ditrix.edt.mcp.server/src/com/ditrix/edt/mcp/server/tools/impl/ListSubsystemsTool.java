/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;

/**
 * Tool to list 1C subsystems of a configuration as a flat table with FQN, synonym,
 * command interface flag, content/children counts. Supports recursive listing of
 * nested subsystems.
 */
public class ListSubsystemsTool implements IMcpTool
{
    public static final String NAME = "list_subsystems"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List 1C subsystems of a configuration. " //$NON-NLS-1$
            + "Returns a flat table with FQN (e.g. 'Subsystem.Sales' or 'Subsystem.Sales.Subsystem.Orders'), " //$NON-NLS-1$
            + "Synonym, Comment, IncludeInCommandInterface flag, content count (objects in subsystem) " //$NON-NLS-1$
            + "and children count (nested subsystems). " //$NON-NLS-1$
            + "Walks the whole tree by default (recursive=true) so AI sees all subsystems at once; " //$NON-NLS-1$
            + "set recursive=false to list only top-level subsystems. " //$NON-NLS-1$
            + "Use 'get_subsystem_content' to inspect a specific subsystem and its objects."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("nameFilter", //$NON-NLS-1$
                "Partial name match filter (case-insensitive, matches Name only — not Synonym)") //$NON-NLS-1$
            .booleanProperty("recursive", //$NON-NLS-1$
                "Include nested subsystems (default: true)") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum number of results. Default: from preferences (100)") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Language code for synonyms (e.g. 'en', 'ru'). Uses configuration default if not specified.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName != null && !projectName.isEmpty())
        {
            return "subsystems-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "subsystems.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String nameFilter = JsonUtils.extractStringArgument(params, "nameFilter"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }

        boolean recursive = JsonUtils.extractBooleanArgument(params, "recursive", true); //$NON-NLS-1$

        int defaultLimit = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, "limit", 100); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", defaultLimit); //$NON-NLS-1$
        limit = Math.min(Math.max(1, limit), 1000);

        AtomicReference<String> resultRef = new AtomicReference<>();
        final String filter = nameFilter;
        final boolean recursiveMode = recursive;
        final int maxResults = limit;
        final String lang = language;

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(listSubsystemsInternal(projectName, filter, recursiveMode, maxResults, lang));
            }
            catch (Exception e)
            {
                Activator.logError("Error listing subsystems", e); //$NON-NLS-1$
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
            }
        });

        return resultRef.get();
    }

    private String listSubsystemsInternal(String projectName, String nameFilter,
        boolean recursive, int limit, String language)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return "Error: Configuration provider not available"; //$NON-NLS-1$
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return "Error: Could not get configuration for project: " + projectName; //$NON-NLS-1$
        }

        String effectiveLanguage = SubsystemUtils.resolveLanguage(language, config);

        List<SubsystemRow> rows = new ArrayList<>();
        for (Subsystem subsystem : config.getSubsystems())
        {
            collect(subsystem, "Subsystem." + subsystem.getName(), nameFilter, recursive, rows); //$NON-NLS-1$
        }

        return formatOutput(projectName, rows, limit, effectiveLanguage, recursive);
    }

    private void collect(Subsystem subsystem, String fqn, String nameFilter,
        boolean recursive, List<SubsystemRow> rows)
    {
        if (matchesFilter(subsystem.getName(), nameFilter))
        {
            rows.add(toRow(subsystem, fqn));
        }

        if (recursive)
        {
            for (Subsystem child : subsystem.getSubsystems())
            {
                collect(child, fqn + ".Subsystem." + child.getName(), nameFilter, true, rows); //$NON-NLS-1$
            }
        }
    }

    private SubsystemRow toRow(Subsystem subsystem, String fqn)
    {
        SubsystemRow row = new SubsystemRow();
        row.fqn = fqn;
        row.synonyms = subsystem.getSynonym();
        row.comment = subsystem.getComment();
        row.includeInCommandInterface = subsystem.isIncludeInCommandInterface();
        row.contentCount = subsystem.getContent() != null ? subsystem.getContent().size() : 0;
        row.childrenCount = subsystem.getSubsystems() != null ? subsystem.getSubsystems().size() : 0;
        return row;
    }

    private boolean matchesFilter(String name, String filter)
    {
        if (filter == null || filter.isEmpty())
        {
            return true;
        }
        return name != null && name.toLowerCase().contains(filter.toLowerCase());
    }

    private String formatOutput(String projectName, List<SubsystemRow> rows, int limit,
        String language, boolean recursive)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Subsystems: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        int total = rows.size();
        int shown = Math.min(total, limit);

        if (!recursive)
        {
            sb.append("**Mode:** top-level only\n"); //$NON-NLS-1$
        }
        sb.append("**Total:** ").append(total).append(" subsystems"); //$NON-NLS-1$ //$NON-NLS-2$
        if (shown < total)
        {
            sb.append(" (showing ").append(shown).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        if (rows.isEmpty())
        {
            sb.append("No subsystems found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| FQN | Synonym | Comment | InCommandInterface | Content | Children |\n"); //$NON-NLS-1$
        sb.append("|-----|---------|---------|--------------------|---------|----------|\n"); //$NON-NLS-1$

        int count = 0;
        for (SubsystemRow row : rows)
        {
            if (count >= limit)
            {
                break;
            }
            String synonym = SubsystemUtils.getSynonymForLanguage(row.synonyms, language);
            String comment = row.comment != null ? row.comment : ""; //$NON-NLS-1$

            sb.append("| ").append(MarkdownUtils.escapeForTable(row.fqn)); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(synonym)); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(comment)); //$NON-NLS-1$
            sb.append(" | ").append(row.includeInCommandInterface ? "Yes" : "No"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append(" | ").append(row.contentCount); //$NON-NLS-1$
            sb.append(" | ").append(row.childrenCount); //$NON-NLS-1$
            sb.append(" |\n"); //$NON-NLS-1$
            count++;
        }
        return sb.toString();
    }

    private static class SubsystemRow
    {
        String fqn;
        EMap<String, String> synonyms;
        String comment;
        boolean includeInCommandInterface;
        int contentCount;
        int childrenCount;
    }
}
