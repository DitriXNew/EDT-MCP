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

        Subsystem subsystem = SubsystemUtils.resolveByFqn(config, subsystemFqn);
        if (subsystem == null)
        {
            return ToolResult.error("Subsystem not found: " + subsystemFqn //$NON-NLS-1$
                + ". Check the FQN is 'Subsystem.<Name>' (type token must be 'Subsystem'); " //$NON-NLS-1$
                + "use list_subsystems to see available subsystems.").toJson(); //$NON-NLS-1$
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
