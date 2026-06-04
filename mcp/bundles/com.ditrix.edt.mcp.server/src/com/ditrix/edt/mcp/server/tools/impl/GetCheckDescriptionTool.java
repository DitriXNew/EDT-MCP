/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.IPreferenceStore;

import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to get check description by check ID.
 * Reads markdown files from the configured checks folder.
 */
public class GetCheckDescriptionTool implements IMcpTool
{
    public static final String NAME = "get_check_description"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get detailed description of an EDT check by its ID. " + //$NON-NLS-1$
               "Returns markdown content with check explanation, examples, and how to fix. " + //$NON-NLS-1$
               "Accepts the symbolic check id OR the short UID code shown by get_project_errors " + //$NON-NLS-1$
               "(pass projectName so the UID can be resolved). " + //$NON-NLS-1$
               "Requires a configured check-descriptions folder (MCP preferences); " + //$NON-NLS-1$
               "without it the tool returns a configuration error."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("checkId", //$NON-NLS-1$
                "Check id: the symbolic dash-cased id (e.g. 'begin-transaction', " //$NON-NLS-1$
                + "'ql-temp-table-index') OR the short UID code from get_project_errors " //$NON-NLS-1$
                + "(e.g. 'SU23'); a UID is resolved when projectName is also supplied. " //$NON-NLS-1$
                + "Precondition: a check-descriptions folder must be configured in MCP " //$NON-NLS-1$
                + "preferences, else the tool returns a configuration error.", true) //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "Optional EDT project name. Required only to resolve a short UID checkId " //$NON-NLS-1$
                + "(e.g. 'SU23') to its symbolic id; ignored when checkId is already symbolic.") //$NON-NLS-1$
            .build();
    }
    
    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        if (checkId != null && !checkId.isEmpty())
        {
            return checkId + ".md"; //$NON-NLS-1$
        }
        return getName() + ".md"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        return getCheckDescription(checkId, projectName);
    }
    
    /**
     * Finds the documentation file for a given check ID.
     * 
     * @param checkId the check ID
     * @return Path to the documentation file, or null if not found or invalid
     */
    private static Path findCheckDocumentationFile(String checkId)
    {
        if (checkId == null || checkId.isEmpty())
        {
            return null;
        }
        
        try
        {
            // Get checks folder from preferences
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            String checksFolder = store.getString(PreferenceConstants.PREF_CHECKS_FOLDER);
            
            if (checksFolder == null || checksFolder.isEmpty())
            {
                return null;
            }
            
            Path folderPath = Paths.get(checksFolder);
            if (!Files.exists(folderPath) || !Files.isDirectory(folderPath))
            {
                return null;
            }
            
            // Sanitize checkId to prevent path traversal
            String sanitizedCheckId = checkId.replaceAll("[^a-zA-Z0-9_-]", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (!sanitizedCheckId.equals(checkId))
            {
                return null;
            }
            
            // Try to find the file with .md extension
            Path checkFile = folderPath.resolve(checkId + ".md"); //$NON-NLS-1$
            if (Files.exists(checkFile))
            {
                return checkFile;
            }
            
            // Try lowercase version
            Path checkFileLower = folderPath.resolve(checkId.toLowerCase() + ".md"); //$NON-NLS-1$
            if (Files.exists(checkFileLower))
            {
                return checkFileLower;
            }
            
            return null;
        }
        catch (Exception e)
        {
            return null;
        }
    }
    
    /**
     * Checks if documentation exists for a given check ID.
     * 
     * @param checkId the check ID
     * @return true if documentation file exists, false otherwise
     */
    public static boolean hasCheckDocumentation(String checkId)
    {
        return findCheckDocumentationFile(checkId) != null;
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
            // Get checks folder from preferences for error messages
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            String checksFolder = store.getString(PreferenceConstants.PREF_CHECKS_FOLDER);

            if (checksFolder == null || checksFolder.isEmpty())
            {
                return ToolResult.error("Check descriptions folder is not configured.\n\n" + //$NON-NLS-1$
                       "Please set it in Preferences -> MCP Server.").toJson(); //$NON-NLS-1$
            }

            Path folderPath = Paths.get(checksFolder);
            if (!Files.exists(folderPath) || !Files.isDirectory(folderPath))
            {
                return ToolResult.error("Check descriptions folder does not exist: " + checksFolder).toJson(); //$NON-NLS-1$
            }

            // Find the documentation file (checkId assumed symbolic).
            Path checkFile = findCheckDocumentationFile(checkId);

            // Direct lookup missed: checkId may be a short UID (e.g. "SU23"). When a
            // project is known, resolve the UID to its symbolic id and retry, so the
            // get_project_errors -> get_check_description chain works for UID-only codes.
            if (checkFile == null && projectName != null && !projectName.isEmpty())
            {
                String symbolic = resolveSymbolicViaUid(checkId, projectName);
                if (symbolic != null && !symbolic.equals(checkId))
                {
                    checkFile = findCheckDocumentationFile(symbolic);
                }
            }

            if (checkFile == null)
            {
                return ToolResult.error("Check description not found for: " + checkId).toJson(); //$NON-NLS-1$
            }

            // Read and return file content directly (it's already Markdown)
            return Files.readString(checkFile, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            Activator.logError("Error reading check description for: " + checkId, e); //$NON-NLS-1$
            return ToolResult.error("Failed to read check description: " + e.getMessage()).toJson(); //$NON-NLS-1$
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
