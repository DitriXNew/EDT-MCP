/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;

/**
 * Tool to get detailed content of a specific 1C subsystem: properties, the list of
 * metadata objects included in the subsystem, and nested subsystems.
 */
public class GetSubsystemContentTool implements IMcpTool
{
    public static final String NAME = "get_subsystem_content"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Get one 1C subsystem's content: properties, its metadata objects " //$NON-NLS-1$
            + "(Type/Name/Synonym/FQN) and child subsystems, identified by FQN " //$NON-NLS-1$
            + "(e.g. 'Subsystem.Sales.Subsystem.Orders'). " //$NON-NLS-1$
            + "By default lists only this subsystem's objects; set recursive=true to fold in nested ones. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('get_subsystem_content')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("subsystemFqn", //$NON-NLS-1$
                "Subsystem FQN (required), e.g. 'Subsystem.Sales.Subsystem.Orders'", //$NON-NLS-1$
                true)
            .booleanProperty("recursive", //$NON-NLS-1$
                "Also include objects from nested subsystems (default: false)") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Synonym language code, e.g. 'en'/'ru' (default: configuration default)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# get_subsystem_content\n\n" //$NON-NLS-1$
            + "Inspect a single 1C subsystem in depth: its properties, the metadata objects " //$NON-NLS-1$
            + "it contains and its direct child subsystems. Output is Markdown with a " //$NON-NLS-1$
            + "`## Properties` table, a `## Content` table (Type/Name/Synonym/FQN of each " //$NON-NLS-1$
            + "included object) and a `## Child Subsystems` table.\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- You have a subsystem FQN (typically from `list_subsystems`) and want the " //$NON-NLS-1$
            + "objects assigned to it plus its settings.\n" //$NON-NLS-1$
            + "- To map an entire configuration's subsystem tree first, use `list_subsystems`; " //$NON-NLS-1$
            + "this tool drills into one node.\n\n" //$NON-NLS-1$

            + "## Parameters\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `subsystemFqn` (required) - the subsystem FQN (see FQN format below).\n" //$NON-NLS-1$
            + "- `recursive` - default `false`: the Content table lists only the objects " //$NON-NLS-1$
            + "directly assigned to this subsystem, keeping the response compact. Set `true` " //$NON-NLS-1$
            + "to also fold in objects from all nested subsystems; duplicates that appear in " //$NON-NLS-1$
            + "more than one nested subsystem are deduplicated. The Content header is marked " //$NON-NLS-1$
            + "`(recursive)` in that mode.\n" //$NON-NLS-1$
            + "- `language` - language code for the Synonym/Explanation columns (e.g. `en`, " //$NON-NLS-1$
            + "`ru`). Defaults to the configuration's default language.\n\n" //$NON-NLS-1$

            + "## FQN format\n" //$NON-NLS-1$
            + "Top-level: `Subsystem.Sales`. Nested subsystems repeat the segment: " //$NON-NLS-1$
            + "`Subsystem.Sales.Subsystem.Orders`. The child-subsystem table emits exactly " //$NON-NLS-1$
            + "these FQNs, so you can chain another `get_subsystem_content` call on a child.\n\n" //$NON-NLS-1$

            + "## Output sections\n" //$NON-NLS-1$
            + "- **Properties** - FQN, Name, Synonym, optional Comment, the " //$NON-NLS-1$
            + "IncludeInCommandInterface / IncludeHelpInContents / UseOneCommand flags, " //$NON-NLS-1$
            + "optional Explanation and the parent subsystem.\n" //$NON-NLS-1$
            + "- **Content** - one row per included object (Type, Name, Synonym, FQN), sorted " //$NON-NLS-1$
            + "by Type then Name. The header shows the object count.\n" //$NON-NLS-1$
            + "- **Child Subsystems** - direct children with FQN, Synonym and content/children " //$NON-NLS-1$
            + "counts (omitted when there are none).\n\n" //$NON-NLS-1$

            + "## Examples\n" //$NON-NLS-1$
            + "- Direct content only: " //$NON-NLS-1$
            + "`{projectName: \"MyProject\", subsystemFqn: \"Subsystem.Sales\"}`.\n" //$NON-NLS-1$
            + "- Include nested objects: " //$NON-NLS-1$
            + "`{projectName: \"MyProject\", subsystemFqn: \"Subsystem.Sales\", recursive: true}`.\n" //$NON-NLS-1$
            + "- Nested subsystem with Russian synonyms: `{projectName: \"MyProject\", " //$NON-NLS-1$
            + "subsystemFqn: \"Subsystem.Sales.Subsystem.Orders\", language: \"ru\"}`.\n\n" //$NON-NLS-1$

            + "## Notes & gotchas\n" //$NON-NLS-1$
            + "- The object Name is the programmatic identifier; the Synonym is rendered for " //$NON-NLS-1$
            + "the chosen `language` only. An unconfigured language yields an empty synonym, " //$NON-NLS-1$
            + "not an error.\n" //$NON-NLS-1$
            + "- A subsystem with no assigned objects renders an explicit " //$NON-NLS-1$
            + "*No objects in this subsystem.* line rather than an empty table.\n" //$NON-NLS-1$
            + "- Output is Markdown; table cells are escaped so a `|` in a synonym/comment " //$NON-NLS-1$
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
        String fqn = JsonUtils.extractStringArgument(params, "subsystemFqn"); //$NON-NLS-1$
        if (fqn != null && !fqn.isEmpty())
        {
            String safe = fqn.replace('.', '-').toLowerCase();
            return "subsystem-" + safe + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "subsystem-content.md"; //$NON-NLS-1$
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
        String subsystemFqn = JsonUtils.extractStringArgument(params, "subsystemFqn"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        err = JsonUtils.requireArgument(params, "subsystemFqn", " (e.g. 'Subsystem.Sales')"); //$NON-NLS-1$ //$NON-NLS-2$
        if (err != null)
        {
            return err;
        }

        boolean recursive = JsonUtils.extractBooleanArgument(params, "recursive", false); //$NON-NLS-1$

        AtomicReference<String> resultRef = new AtomicReference<>();
        final String fqn = subsystemFqn;
        final boolean recursiveMode = recursive;
        final String lang = language;

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(getSubsystemContentInternal(projectName, fqn, recursiveMode, lang));
            }
            catch (Exception e)
            {
                Activator.logError("Error getting subsystem content", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        return resultRef.get();
    }

    private String getSubsystemContentInternal(String projectName, String subsystemFqn,
        boolean recursive, String language)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
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

        Subsystem subsystem = SubsystemUtils.resolveByFqn(config, subsystemFqn);
        if (subsystem == null)
        {
            return ToolResult.error("Subsystem not found: " + subsystemFqn).toJson(); //$NON-NLS-1$
        }

        String effectiveLanguage = SubsystemUtils.resolveLanguage(language, config);

        List<MdObject> contentObjects = new ArrayList<>();
        if (recursive)
        {
            collectContentRecursive(subsystem, contentObjects, new HashSet<>());
        }
        else if (subsystem.getContent() != null)
        {
            contentObjects.addAll(subsystem.getContent());
        }

        return formatOutput(subsystem, subsystemFqn, contentObjects, recursive, effectiveLanguage);
    }

    private void collectContentRecursive(Subsystem subsystem, List<MdObject> result,
        Set<MdObject> seen)
    {
        if (subsystem.getContent() != null)
        {
            for (MdObject obj : subsystem.getContent())
            {
                if (obj != null && seen.add(obj))
                {
                    result.add(obj);
                }
            }
        }
        if (subsystem.getSubsystems() != null)
        {
            for (Subsystem child : subsystem.getSubsystems())
            {
                collectContentRecursive(child, result, seen);
            }
        }
    }

    private String formatOutput(Subsystem subsystem, String fqn, List<MdObject> contentObjects,
        boolean recursive, String language)
    {
        StringBuilder sb = new StringBuilder();

        String synonym = SubsystemUtils.getSynonymForLanguage(subsystem.getSynonym(), language);
        sb.append("# Subsystem: ").append(subsystem.getName()); //$NON-NLS-1$
        if (!synonym.isEmpty())
        {
            sb.append(" (").append(synonym).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        appendProperties(sb, subsystem, fqn, synonym, language);
        appendContent(sb, contentObjects, recursive, language);
        appendChildren(sb, subsystem, fqn, language);

        return sb.toString();
    }

    private void appendProperties(StringBuilder sb, Subsystem subsystem, String fqn,
        String synonym, String language)
    {
        sb.append("## Properties\n\n"); //$NON-NLS-1$
        sb.append("| Property | Value |\n"); //$NON-NLS-1$
        sb.append("|----------|-------|\n"); //$NON-NLS-1$
        appendRow(sb, "FQN", fqn); //$NON-NLS-1$
        appendRow(sb, "Name", subsystem.getName()); //$NON-NLS-1$
        appendRow(sb, "Synonym", synonym); //$NON-NLS-1$

        String comment = subsystem.getComment();
        if (comment != null && !comment.isEmpty())
        {
            appendRow(sb, "Comment", comment); //$NON-NLS-1$
        }

        appendRow(sb, "Include In Command Interface", //$NON-NLS-1$
            subsystem.isIncludeInCommandInterface() ? "Yes" : "No"); //$NON-NLS-1$ //$NON-NLS-2$
        appendRow(sb, "Include Help In Contents", //$NON-NLS-1$
            subsystem.isIncludeHelpInContents() ? "Yes" : "No"); //$NON-NLS-1$ //$NON-NLS-2$
        appendRow(sb, "Use One Command", //$NON-NLS-1$
            subsystem.isUseOneCommand() ? "Yes" : "No"); //$NON-NLS-1$ //$NON-NLS-2$

        String explanation = SubsystemUtils.getSynonymForLanguage(subsystem.getExplanation(), language);
        if (!explanation.isEmpty())
        {
            appendRow(sb, "Explanation", explanation); //$NON-NLS-1$
        }

        Subsystem parent = subsystem.getParentSubsystem();
        if (parent != null)
        {
            appendRow(sb, "Parent Subsystem", parent.getName()); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$
    }

    private void appendContent(StringBuilder sb, List<MdObject> contentObjects,
        boolean recursive, String language)
    {
        sb.append("## Content"); //$NON-NLS-1$
        if (recursive)
        {
            sb.append(" (recursive)"); //$NON-NLS-1$
        }
        sb.append(" — ").append(contentObjects.size()).append(" objects\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (contentObjects.isEmpty())
        {
            sb.append("*No objects in this subsystem.*\n\n"); //$NON-NLS-1$
            return;
        }

        List<MdObject> sorted = new ArrayList<>(contentObjects);
        sorted.sort(Comparator
            .comparing((MdObject o) -> o.eClass().getName())
            .thenComparing(o -> o.getName() != null ? o.getName() : "")); //$NON-NLS-1$

        sb.append("| Type | Name | Synonym | FQN |\n"); //$NON-NLS-1$
        sb.append("|------|------|---------|-----|\n"); //$NON-NLS-1$

        for (MdObject obj : sorted)
        {
            String type = obj.eClass().getName();
            String name = obj.getName() != null ? obj.getName() : ""; //$NON-NLS-1$
            String objSynonym = SubsystemUtils.getSynonymForLanguage(obj.getSynonym(), language);
            String objFqn = type + "." + name; //$NON-NLS-1$

            sb.append("| ").append(MarkdownUtils.escapeForTable(type)); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(name)); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(objSynonym)); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(objFqn)); //$NON-NLS-1$
            sb.append(" |\n"); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$
    }

    private void appendChildren(StringBuilder sb, Subsystem subsystem, String parentFqn, String language)
    {
        List<Subsystem> children = subsystem.getSubsystems();
        if (children == null || children.isEmpty())
        {
            return;
        }
        sb.append("## Child Subsystems — ").append(children.size()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| FQN | Synonym | Content | Children |\n"); //$NON-NLS-1$
        sb.append("|-----|---------|---------|----------|\n"); //$NON-NLS-1$

        for (Subsystem child : children)
        {
            String childFqn = parentFqn + ".Subsystem." + child.getName(); //$NON-NLS-1$
            String childSynonym = SubsystemUtils.getSynonymForLanguage(child.getSynonym(), language);
            int contentCount = child.getContent() != null ? child.getContent().size() : 0;
            int grandchildren = child.getSubsystems() != null ? child.getSubsystems().size() : 0;

            sb.append("| ").append(MarkdownUtils.escapeForTable(childFqn)); //$NON-NLS-1$
            sb.append(" | ").append(MarkdownUtils.escapeForTable(childSynonym)); //$NON-NLS-1$
            sb.append(" | ").append(contentCount); //$NON-NLS-1$
            sb.append(" | ").append(grandchildren); //$NON-NLS-1$
            sb.append(" |\n"); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$
    }

    private void appendRow(StringBuilder sb, String key, String value)
    {
        sb.append("| ").append(key); //$NON-NLS-1$
        sb.append(" | ").append(MarkdownUtils.escapeForTable(value != null ? value : "")); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(" |\n"); //$NON-NLS-1$
    }
}
