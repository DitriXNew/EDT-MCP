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
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;

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
        return "List 1C subsystems of a configuration as a flat table (FQN, Synonym, Comment, " //$NON-NLS-1$
            + "InCommandInterface, content count, children count). " //$NON-NLS-1$
            + "Walks the whole tree by default (recursive=true); use get_subsystem_content for one subsystem's objects. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('list_subsystems')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("nameFilter", //$NON-NLS-1$
                "Case-insensitive partial match on Name only (not Synonym)") //$NON-NLS-1$
            .booleanProperty("recursive", //$NON-NLS-1$
                "Include nested subsystems (default: true)") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Max results (default from preferences: 100, max 1000)") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Synonym language code, e.g. 'en'/'ru' (default: configuration default)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# list_subsystems\n\n" //$NON-NLS-1$
            + "List the subsystems of a configuration as a flat table. Each row carries the " //$NON-NLS-1$
            + "subsystem FQN, its synonym in the chosen language, comment, the " //$NON-NLS-1$
            + "IncludeInCommandInterface flag, the number of objects in the subsystem " //$NON-NLS-1$
            + "(content count) and the number of direct child subsystems (children count).\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- Get a configuration-wide map of subsystems before drilling into one.\n" //$NON-NLS-1$
            + "- Find a subsystem by a partial Name (`nameFilter`).\n" //$NON-NLS-1$
            + "- To inspect the objects inside a specific subsystem, follow up with " //$NON-NLS-1$
            + "`get_subsystem_content` using the FQN from this table.\n\n" //$NON-NLS-1$

            + "## Parameters\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `nameFilter` - case-insensitive substring matched against the subsystem " //$NON-NLS-1$
            + "**Name only**, not its Synonym. Omit to list everything.\n" //$NON-NLS-1$
            + "- `recursive` - default `true`: walk the whole tree so the AI sees every " //$NON-NLS-1$
            + "subsystem at once. Set `false` to list only top-level subsystems.\n" //$NON-NLS-1$
            + "- `limit` - max rows returned; default from preferences (100), clamped to " //$NON-NLS-1$
            + "1000. A truncation notice is appended when results are capped.\n" //$NON-NLS-1$
            + "- `language` - language code for the Synonym column (e.g. `en`, `ru`). " //$NON-NLS-1$
            + "Defaults to the configuration's default language.\n\n" //$NON-NLS-1$

            + "## FQN format\n" //$NON-NLS-1$
            + "Top-level: `Subsystem.Sales`. Nested subsystems repeat the segment: " //$NON-NLS-1$
            + "`Subsystem.Sales.Subsystem.Orders`. These FQNs are the IDs you pass to " //$NON-NLS-1$
            + "`get_subsystem_content`.\n\n" //$NON-NLS-1$

            + "## Examples\n" //$NON-NLS-1$
            + "- Whole tree: `{projectName: \"MyProject\"}`.\n" //$NON-NLS-1$
            + "- Top-level only: `{projectName: \"MyProject\", recursive: false}`.\n" //$NON-NLS-1$
            + "- Filter by name: `{projectName: \"MyProject\", nameFilter: \"Sales\"}`.\n" //$NON-NLS-1$
            + "- Russian synonyms: `{projectName: \"MyProject\", language: \"ru\"}`.\n\n" //$NON-NLS-1$

            + "## Notes & gotchas\n" //$NON-NLS-1$
            + "- `nameFilter` matches the programmatic Name, never the localized synonym; " //$NON-NLS-1$
            + "searching by a translated caption will not match.\n" //$NON-NLS-1$
            + "- The Synonym column is rendered for `language` only; an unconfigured " //$NON-NLS-1$
            + "language yields an empty synonym, not an error.\n" //$NON-NLS-1$
            + "- Output is Markdown; table cells are escaped so `|` in a comment/synonym " //$NON-NLS-1$
            + "does not break the table.\n"; //$NON-NLS-1$
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
        String err = JsonUtils.requireArgument(params, "projectName"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String nameFilter = JsonUtils.extractStringArgument(params, "nameFilter"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        boolean recursive = JsonUtils.extractBooleanArgument(params, "recursive", true); //$NON-NLS-1$

        int defaultLimit = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, "limit", 100); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", defaultLimit); //$NON-NLS-1$
        limit = Pagination.clampLimit(limit, 1000);

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
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        return resultRef.get();
    }

    private String listSubsystemsInternal(String projectName, String nameFilter,
        boolean recursive, int limit, String language)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return ToolResult.error("Configuration provider not available").toJson(); //$NON-NLS-1$
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return ToolResult.error("Could not get configuration for project: " + projectName).toJson(); //$NON-NLS-1$
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
        sb.append(Pagination.truncationNotice(shown, total));
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
