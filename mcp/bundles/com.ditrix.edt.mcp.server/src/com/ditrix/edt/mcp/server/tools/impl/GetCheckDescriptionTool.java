/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IProject;

import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.CheckDescriptionLoader;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to get check description by check ID.
 * <p>
 * The descriptions ship with the plugin; see {@link CheckDescriptionLoader}, which also honours
 * the optional checks-folder preference as a per-file override.
 * </p>
 */
public class GetCheckDescriptionTool implements IMcpTool
{
    public static final String NAME = "get_check_description"; //$NON-NLS-1$

    /** Input param: the check id (symbolic dash-cased id or short UID code). */
    private static final String KEY_CHECK_ID = "checkId"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Understand an EDT validation rule and how to fix its diagnostic. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('get_check_description')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_CHECK_ID,
                "Check id: the symbolic dash-cased id (e.g. 'begin-transaction', " //$NON-NLS-1$
                + "'ql-temp-table-index') OR the short UID code from get_project_errors " //$NON-NLS-1$
                + "(e.g. 'SU23'); a UID is resolved when projectName is also supplied.", true) //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Optional EDT project name. Required only to resolve a short UID checkId " //$NON-NLS-1$
                + "(e.g. 'SU23') to its symbolic id; ignored when checkId is already symbolic.") //$NON-NLS-1$
            .build();
    }
    
    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String checkId = JsonUtils.extractStringArgument(params, KEY_CHECK_ID);
        if (checkId != null && !checkId.isEmpty())
        {
            return checkId + ".md"; //$NON-NLS-1$
        }
        return getName() + ".md"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String checkId = JsonUtils.extractStringArgument(params, KEY_CHECK_ID);
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        return getCheckDescription(checkId, projectName);
    }
    
    /**
     * Checks if documentation exists for a given check ID.
     *
     * @param checkId the check ID
     * @return true if a description is available (shipped or overridden), false otherwise
     */
    public static boolean hasCheckDocumentation(String checkId)
    {
        return CheckDescriptionLoader.has(checkId);
    }
    
    /**
     * Gets check description from the configured folder, by symbolic check id only.
     *
     * @param checkId the symbolic check ID
     * @return Markdown string with check description or error
     */
    public static String getCheckDescription(String checkId)
    {
        return getCheckDescription(checkId, null);
    }

    /**
     * Gets check description from the configured folder.
     * <p>
     * {@code checkId} may be the symbolic dash-cased id (looked up directly as
     * {@code <id>.md}) or a short UID code (e.g. {@code "SU23"}) as emitted by
     * get_project_errors. When the direct lookup misses and {@code projectName} is
     * supplied, the id is treated as a UID and resolved to its symbolic id via the
     * check repository (the same mechanism get_project_errors uses) before retrying.
     *
     * @param checkId the symbolic check ID or a short UID code
     * @param projectName optional project name; required only to resolve a UID
     * @return Markdown string with check description or error
     */
    public static String getCheckDescription(String checkId, String projectName)
    {
        // Validate checkId parameter
        if (checkId == null || checkId.isEmpty())
        {
            return ToolResult.error("checkId is required").toJson(); //$NON-NLS-1$
        }

        try
        {
            // Direct lookup, checkId assumed symbolic.
            String body = CheckDescriptionLoader.load(checkId);

            // Missed: checkId may be a short UID (e.g. "SU23"). When a project is known,
            // resolve the UID to its symbolic id and retry, so the get_project_errors ->
            // get_check_description chain works for UID-only codes.
            if (body == null && projectName != null && !projectName.isEmpty())
            {
                String symbolic = resolveSymbolicViaUid(checkId, projectName);
                if (symbolic != null && !symbolic.equals(checkId))
                {
                    body = CheckDescriptionLoader.load(symbolic);
                }
            }

            if (body == null)
            {
                // The descriptions ship with the plugin, so a miss is about THIS id and not
                // about a setup step the operator skipped - name the id and the two ways to
                // get a usable one, rather than sending the caller to Preferences.
                return ToolResult.error("No check description for: " + checkId //$NON-NLS-1$
                    + ". Use the symbolic dash-cased id (e.g. 'begin-transaction'); a short UID " //$NON-NLS-1$
                    + "code from get_project_errors (e.g. 'SU23') resolves only when projectName " //$NON-NLS-1$
                    + "is supplied too. Not every EDT check has a description written for it.") //$NON-NLS-1$
                    .toJson();
            }

            // The body is already Markdown.
            return body;
        }
        catch (Exception e)
        {
            Activator.logError("Error getting check description", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    /**
     * Resolves a short check UID to its symbolic id, fetching the project (by name)
     * and the check repository from the runtime. Returns {@code null} when the
     * project is not open, the repository is unavailable, or the UID does not resolve.
     *
     * @param checkId the short UID code (e.g. {@code "SU23"})
     * @param projectName the EDT project to resolve against
     * @return the symbolic check id, or {@code null}
     */
    private static String resolveSymbolicViaUid(String checkId, String projectName)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.isOpen())
        {
            return null;
        }
        ICheckRepository repo = Activator.getDefault().getCheckRepository();
        return resolveSymbolicCheckUid(checkId, ctx.project(), repo);
    }

    /**
     * Pure UID -> symbolic check-id resolution via {@link ICheckRepository}, mirroring
     * {@code GetProjectErrorsTool.resolveSymbolicCheckId}. Separated from runtime
     * service lookup so it is unit-testable with a mocked repository. Returns
     * {@code null} when anything is missing or the UID does not resolve.
     *
     * @param shortUid the short UID code
     * @param project the project the UID belongs to
     * @param repo the check repository
     * @return the symbolic check id, or {@code null}
     */
    static String resolveSymbolicCheckUid(String shortUid, IProject project, ICheckRepository repo)
    {
        if (repo == null || project == null || shortUid == null || shortUid.isEmpty())
        {
            return null;
        }
        try
        {
            CheckUid uid = repo.getUidForShortUid(shortUid, project);
            return uid != null ? uid.getCheckId() : null;
        }
        catch (Exception e)
        {
            // Ignore - caller falls back to the original checkId / not-found error.
            return null;
        }
    }
}
