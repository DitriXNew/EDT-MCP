/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.metadata.MetadataFormatterRegistry;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to get detailed properties of metadata objects from 1C configuration.
 * Supports sections: basic, attributes, tabular, forms, commands.
 */
public class GetMetadataDetailsTool implements IMcpTool
{
    public static final String NAME = "get_metadata_details"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get detailed properties of one or more 1C metadata objects (basic info by default, " + //$NON-NLS-1$
               "or every reflected section with 'full: true'). Use it after get_metadata_objects to " + //$NON-NLS-1$
               "inspect a known object's attributes/forms/commands; in full mode each section is " + //$NON-NLS-1$
               "capped so request fewer FQNs to keep the response small. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('get_metadata_details')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringArrayProperty("objectFqns", //$NON-NLS-1$
                "Required. FQNs as Type.Name, e.g. ['Catalog.Products', 'Document.SalesOrder']; " + //$NON-NLS-1$
                "Russian type tokens also work (e.g. '\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Products').", //$NON-NLS-1$
                true)
            .booleanProperty("full", //$NON-NLS-1$
                "All reflected properties (true) or only key info (false). Default: false") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Synonym language code, e.g. 'en'/'ru' (default: configuration default)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "Return the detailed properties of one or more 1C metadata objects. By default you " //$NON-NLS-1$
            + "get a compact basic view; with `full: true` every reflected section (attributes, " //$NON-NLS-1$
            + "tabular sections, forms, commands, and other reflected properties) is rendered.\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- After `get_metadata_objects` (or any tool that gave you a Type.Name), to inspect a " //$NON-NLS-1$
            + "specific object's structure.\n" //$NON-NLS-1$
            + "- Batch several objects in one call by passing multiple FQNs in `objectFqns`.\n" //$NON-NLS-1$
            + "- Prefer the default (basic) view first; reach for `full: true` only when you need the " //$NON-NLS-1$
            + "exhaustive reflection.\n\n" //$NON-NLS-1$

            + "## Parameter details\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `objectFqns` (required) - array of fully-qualified names in `Type.Name` form, e.g. " //$NON-NLS-1$
            + "`Catalog.Products`, `Document.SalesOrder`. Only the **Type** token may be bilingual: " //$NON-NLS-1$
            + "the English or Russian, singular or plural type is accepted (e.g. " //$NON-NLS-1$
            + "`\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Products` resolves the same as `Catalog.Products`). The " //$NON-NLS-1$
            + "**Name** part is the programmatic object Name, never the synonym.\n" //$NON-NLS-1$
            + "- `full` - `true` returns every reflected section, `false` (default) returns only key " //$NON-NLS-1$
            + "info. In full mode each section is capped and a `[truncated]` row marks omitted rows.\n" //$NON-NLS-1$
            + "- `language` - language **code** (`en`/`ru`) used for the synonym columns. Defaults to " //$NON-NLS-1$
            + "the configuration's default language; the synonym map is keyed by code, not by the " //$NON-NLS-1$
            + "language's display name.\n\n" //$NON-NLS-1$

            + "## Output\n" //$NON-NLS-1$
            + "- Markdown, one section per resolved object, separated by `---`.\n" //$NON-NLS-1$
            + "- Per-object failures (malformed FQN or object not found) do NOT fail the whole call. " //$NON-NLS-1$
            + "They are collected into a dedicated `## Errors` table at the end with an `ERROR` status " //$NON-NLS-1$
            + "row carrying the FQN and reason, so a client can tell a failed object from data.\n\n" //$NON-NLS-1$

            + "## Examples\n" //$NON-NLS-1$
            + "- Basic, one object: `{projectName: \"MyProject\", objectFqns: [\"Catalog.Products\"]}`.\n" //$NON-NLS-1$
            + "- Full details, several objects: `{projectName: \"MyProject\", objectFqns: " //$NON-NLS-1$
            + "[\"Catalog.Products\", \"Document.SalesOrder\"], full: true}`.\n" //$NON-NLS-1$
            + "- Russian type token + Russian synonyms: `{projectName: \"MyProject\", objectFqns: " //$NON-NLS-1$
            + "[\"\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Products\"], language: \"ru\"}`.\n\n" //$NON-NLS-1$

            + "## Notes & gotchas\n" //$NON-NLS-1$
            + "- Only the type token is bilingual; the object Name must match the programmatic Name, " //$NON-NLS-1$
            + "not a translated synonym.\n" //$NON-NLS-1$
            + "- `full: true` over many FQNs can be large; even capped sections add up - request fewer " //$NON-NLS-1$
            + "FQNs to keep the response small.\n" //$NON-NLS-1$
            + "- An unconfigured `language` yields empty synonyms, not an error.\n" //$NON-NLS-1$
            + "- A malformed FQN (no `.`) is reported as `Invalid FQN`; a well-formed but unknown one " //$NON-NLS-1$
            + "as `Object not found` - both in the `## Errors` table, never as prose in the body.\n"; //$NON-NLS-1$
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
            return "metadata-details-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "metadata-details.md"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        List<String> objectFqns = JsonUtils.extractArrayArgument(params, "objectFqns"); //$NON-NLS-1$
        String fullStr = JsonUtils.extractStringArgument(params, "full"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        
        // Validate required parameters
        String err = JsonUtils.requireArgument(params, "projectName"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        if (objectFqns == null || objectFqns.isEmpty())
        {
            return ToolResult.error("objectFqns is required (array of FQNs like 'Catalog.Products')").toJson(); //$NON-NLS-1$
        }
        
        boolean full = "true".equalsIgnoreCase(fullStr); //$NON-NLS-1$
        
        // Execute on UI thread
        AtomicReference<String> resultRef = new AtomicReference<>();
        final List<String> fqns = objectFqns;
        final boolean fullMode = full;
        final String lang = language;
        
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = getMetadataDetailsInternal(projectName, fqns, fullMode, lang);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error getting metadata details", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });
        
        return resultRef.get();
    }
    
    /**
     * Internal implementation that runs on UI thread.
     */
    private String getMetadataDetailsInternal(String projectName, List<String> objectFqns,
                                               boolean full, String language)
    {
        // Get project
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        IProject project = ctx.project();
        
        // Get configuration
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
        
        // Determine language CODE for synonyms (the synonym map is keyed by code,
        // e.g. "ru"/"en", not by the Language object's name). May be null when the
        // configuration has no languages; downstream synonym lookup tolerates that.
        String effectiveLanguage = MetadataLanguageUtils.resolveLanguageCode(config, language);
        
        StringBuilder sb = new StringBuilder();
        sb.append("# Metadata Details: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Per-object outcomes are split into two channels so a structural client
        // can tell a failed object from data: successfully resolved objects render
        // as data in the body, while failures are collected and emitted as a
        // dedicated, clearly-delimited machine-readable table at the end. A
        // per-object failure is NOT a whole-call failure, so it stays in this
        // success body (the top-level ToolResult.error channel above is reserved
        // for whole-call failures such as a missing project or configuration).
        List<String[]> failures = new ArrayList<>();

        // Process each FQN
        for (String fqn : objectFqns)
        {
            MdObject mdObject = resolveObject(config, fqn);
            if (mdObject == null)
            {
                failures.add(new String[] { fqn, describeResolutionFailure(fqn) });
                continue;
            }
            sb.append(MetadataFormatterRegistry.format(mdObject, full, effectiveLanguage));
            sb.append("\n---\n\n"); //$NON-NLS-1$
        }

        if (!failures.isEmpty())
        {
            sb.append(formatFailures(failures));
        }

        return sb.toString();
    }

    /**
     * Resolves a single FQN to its metadata object, or {@code null} when the FQN
     * is malformed or the object does not exist. A {@code null} result is a
     * per-object failure (recorded in the machine-readable failures table), never
     * a whole-call failure.
     */
    private MdObject resolveObject(Configuration config, String fqn)
    {
        // Parse FQN: Type.Name
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }

        String mdType = parts[0];
        String mdName = parts[1];

        // Normalize metadata type to English singular form (supports Russian and plural forms)
        String normalized = MetadataTypeUtils.toEnglishSingular(mdType);
        if (normalized != null)
        {
            mdType = normalized;
        }

        return MetadataTypeUtils.findObject(config, mdType, mdName);
    }

    /**
     * Builds the machine-readable reason for a FQN that {@link #resolveObject}
     * could not resolve. The reason becomes data in the failures table, never
     * prose mixed into the data body.
     */
    String describeResolutionFailure(String fqn)
    {
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return "Invalid FQN. Expected format: Type.Name (e.g. Catalog.Products)"; //$NON-NLS-1$
        }
        return "Object not found"; //$NON-NLS-1$
    }

    /**
     * Renders the per-object failures as a dedicated, clearly-delimited
     * machine-readable section. Every cell goes through the shared table builder,
     * so an FQN or reason containing '|' or a newline cannot break the table. The
     * heading marker {@code ## Errors} lets a structural client locate failed
     * objects without scraping prose out of the data body.
     */
    String formatFailures(List<String[]> failures)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Errors\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("FQN", "Status", "Reason")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String[] failure : failures)
        {
            sb.append(MarkdownUtils.tableRow(failure[0], "ERROR", failure[1])); //$NON-NLS-1$
        }
        return sb.toString();
    }

}
