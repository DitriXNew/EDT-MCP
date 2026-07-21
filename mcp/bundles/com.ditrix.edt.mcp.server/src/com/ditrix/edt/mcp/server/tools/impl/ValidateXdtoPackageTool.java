/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Read-only "validate this XDTO package" verb: runs EDT's OWN validation (the same
 * configuration-problem markers {@code get_project_errors} exposes) scoped to a single
 * {@code XDTOPackage.<Name>} top object, and reframes the result with a one-line verdict.
 * <p>
 * This is a THIN wrapper over {@link GetProjectErrorsTool#getProjectErrors}: it does not
 * hand-roll any XDTO validation rule of its own. The {@code objects} filter of
 * {@code get_project_errors}, when passed an {@code XDTOPackage.<Name>} FQN, already scopes to
 * that package's problems (a dangling XDTO type reference surfaces as a marker located at
 * {@code XDTOPackage.<Name>.Package}, which the substring-matched {@code objects} filter picks
 * up), so the only work this tool adds is: resolve + type-check the FQN, delegate, and prepend
 * a verdict line to the returned Markdown table.
 */
public class ValidateXdtoPackageTool implements IMcpTool
{
    public static final String NAME = "validate_xdto_package"; //$NON-NLS-1$

    /** Default result cap when {@code limit} is omitted (mirrors get_project_errors). */
    private static final int DEFAULT_LIMIT = 100;

    /** Markdown heading {@code get_project_errors} emits when no problem matched the filters. */
    private static final String NO_ERRORS_MARKER = "# No Errors Found"; //$NON-NLS-1$

    /**
     * Fragment of the {@code get_project_errors} warning shown when some marker's location could not
     * be resolved (so its package membership could not be decided). When it appears alongside "no
     * problems", the "valid" verdict must be qualified rather than asserted.
     */
    private static final String UNRESOLVED_WARNING_FRAGMENT = "could not be resolved"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Validate a single XDTO package by running EDT's OWN configuration validation " //$NON-NLS-1$
            + "(the same check engine behind get_project_errors) scoped to that package, and " //$NON-NLS-1$
            + "return a pass/fail verdict plus any problems found (e.g. a dangling reference to a " //$NON-NLS-1$
            + "deleted ObjectType). It reflects the LATEST validation state already computed by " //$NON-NLS-1$
            + "EDT (reads existing markers) rather than forcing a fresh compile; run " //$NON-NLS-1$
            + "revalidate_objects first if you need up-to-the-second results. Does not implement " //$NON-NLS-1$
            + "any XDTO-specific rule itself - it is a scoped view over get_project_errors. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('validate_xdto_package')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty("fqn", //$NON-NLS-1$
                "FQN of the XDTO package to validate, as 'XDTOPackage.<Name>' (required). Must " //$NON-NLS-1$
                + "already exist; check with get_metadata_objects.", true) //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT, "Max problem rows to report; default 100, max 1000 (optional)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, "fqn"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String fqn = JsonUtils.extractStringArgument(params, "fqn"); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, McpKeys.LIMIT, DEFAULT_LIMIT);
        limit = Pagination.clampLimit(limit, 1000);

        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        ProjectContext.ConfigurationResult resolved = ProjectContext.resolveConfiguration(projectName);
        if (!resolved.ok())
        {
            return resolved.errorJson();
        }
        Configuration config = resolved.configuration();

        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, normFqn);
        if (node == null || !(node.object instanceof XDTOPackage))
        {
            return ToolResult.error("Not an XDTOPackage: '" + fqn + "'. validate_xdto_package only " //$NON-NLS-1$ //$NON-NLS-2$
                + "validates an existing XDTO package top object, addressed as 'XDTOPackage.<Name>'. " //$NON-NLS-1$
                + "Check the name with get_metadata_objects, or call get_project_errors directly for " //$NON-NLS-1$
                + "a general (not XDTO-scoped) problem query.").toJson(); //$NON-NLS-1$
        }

        // Scope by the RESOLVED package's canonical FQN (not the raw input): this ignores a
        // malformed-but-resolvable literal (e.g. a stray trailing dot) that would otherwise be used
        // verbatim as a filter and match nothing (a false "valid"). Report ALL severities (a
        // severity-filtered "no matches" is NOT a validity guarantee), with EXACT per-package scope
        // (exactScope=true) so a prefix-sharing sibling package's problems are not attributed here.
        String pkgFqn = "XDTOPackage." + ((XDTOPackage)node.object).getName(); //$NON-NLS-1$
        String problems = GetProjectErrorsTool.getProjectErrors(projectName, null, null,
            List.of(pkgFqn), limit, false, true);
        return withVerdict(pkgFqn, problems);
    }

    /**
     * Prepends a one-line pass/fail verdict to the {@code get_project_errors} Markdown result,
     * derived from whether it opens with the "No Errors Found" heading. A JSON error payload
     * (e.g. the marker manager being unavailable) is returned verbatim - it is a whole-call
     * failure, not a validation outcome, so it gets no verdict wrapping.
     *
     * @param fqn the validated package FQN (already normalized), echoed in the verdict
     * @param problems the Markdown (or JSON error) returned by {@code getProjectErrors}
     * @return the final Markdown response, or the untouched JSON error
     */
    private static String withVerdict(String fqn, String problems)
    {
        if (problems == null || problems.startsWith("{")) //$NON-NLS-1$
        {
            // A ready JSON error (ToolResult.error(...).toJson()) from the delegate: propagate
            // unchanged rather than wrapping a failure in a "valid"/"problems found" verdict.
            return problems;
        }

        StringBuilder md = new StringBuilder();
        if (problems.startsWith(NO_ERRORS_MARKER))
        {
            if (problems.contains(UNRESOLVED_WARNING_FRAGMENT))
            {
                // No problem matched, but at least one marker's location was unresolvable, so
                // membership in this package could not be decided - do not assert validity.
                md.append("**XDTO package `").append(fqn).append("`: no problems matched, but some " //$NON-NLS-1$ //$NON-NLS-2$
                    + "markers could not be checked** — run revalidate_objects and validate again.\n\n"); //$NON-NLS-1$
            }
            else
            {
                md.append("**XDTO package `").append(fqn).append("` is valid** — no problems reported.\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else
        {
            md.append("**XDTO package `").append(fqn).append("`: problems found**\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        md.append(problems);
        return md.toString();
    }
}
