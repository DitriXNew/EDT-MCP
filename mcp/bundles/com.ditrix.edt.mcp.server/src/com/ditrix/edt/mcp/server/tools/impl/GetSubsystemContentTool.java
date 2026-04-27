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
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;

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
        return "Get content of a specific 1C subsystem: basic properties, " //$NON-NLS-1$
            + "list of metadata objects included in the subsystem (Type/Name/Synonym/FQN), " //$NON-NLS-1$
            + "and nested child subsystems. Subsystem is identified by FQN " //$NON-NLS-1$
            + "(e.g. 'Subsystem.Sales' or 'Subsystem.Sales.Subsystem.Orders'). " //$NON-NLS-1$
            + "Returns content of the requested subsystem only by default (recursive=false) " //$NON-NLS-1$
            + "to keep the response compact; set recursive=true to also include " //$NON-NLS-1$
            + "objects from nested subsystems (deduplicated)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("subsystemFqn", //$NON-NLS-1$
                "Subsystem FQN (required), e.g. 'Subsystem.Sales' or 'Subsystem.Sales.Subsystem.Orders'", //$NON-NLS-1$
                true)
            .booleanProperty("recursive", //$NON-NLS-1$
                "Include objects from nested subsystems in the Content section (default: false)") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String subsystemFqn = JsonUtils.extractStringArgument(params, "subsystemFqn"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (subsystemFqn == null || subsystemFqn.isEmpty())
        {
            return "Error: subsystemFqn is required (e.g. 'Subsystem.Sales')"; //$NON-NLS-1$
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
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
            }
        });

        return resultRef.get();
    }

    private String getSubsystemContentInternal(String projectName, String subsystemFqn,
        boolean recursive, String language)
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

        Subsystem subsystem = SubsystemUtils.resolveByFqn(config, subsystemFqn);
        if (subsystem == null)
        {
            return "Error: Subsystem not found: " + subsystemFqn; //$NON-NLS-1$
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
