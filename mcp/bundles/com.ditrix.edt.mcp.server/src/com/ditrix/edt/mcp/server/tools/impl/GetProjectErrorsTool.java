/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.CheckUid;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Tool to get detailed project errors with optional filters.
 * Uses EDT IMarkerManager for accessing configuration problems.
 *
 * <p>Marker presentation ({@link Marker#getObjectPresentation()}) is resolved lazily
 * against the BM model and therefore must be read inside a BM read transaction.
 * Markers restored from the persisted marker index (e.g. right after EDT startup) have
 * a {@code null} {@code resolvedDataCache}; reading their presentation outside a
 * transaction throws a {@link NullPointerException} that aborts the whole stream.
 * To avoid this, markers are collected per project inside
 * {@link IBmModel#executeReadonlyTask(AbstractBmTask)}.</p>
 */
public class GetProjectErrorsTool implements IMcpTool
{
    public static final String NAME = "get_project_errors"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get detailed configuration problems from EDT. " + //$NON-NLS-1$
               "Returns check code, description, object location, severity level (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL). " + //$NON-NLS-1$
               "Can filter by specific objects using FQN (e.g. 'Document.SalesOrder', 'Catalog.Products'). " + //$NON-NLS-1$
               "Russian type names are also supported (e.g. 'Документ.ПриходнаяНакладная', 'Справочник.Номенклатура')."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Filter by project name (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("severity", "Filter by severity: ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("checkId", "Filter by check ID substring. Matches either the symbolic check id (e.g. 'ql-temp-table-index') or the short UID (e.g. 'SU23') (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("objects", "Filter by object FQNs (e.g. ['Document.SalesOrder', 'Catalog.Products']). Russian type names supported (e.g. 'Документ.ПродажаТоваров'). Returns errors only from these objects.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Maximum number of results (default: 100, max: 1000)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String severity = JsonUtils.extractStringArgument(params, "severity"); //$NON-NLS-1$
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        String objectsJson = JsonUtils.extractStringArgument(params, "objects"); //$NON-NLS-1$
        
        // Check if project is ready for operations
        if (projectName != null && !projectName.isEmpty())
        {
            String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
            if (notReadyError != null)
            {
                return ToolResult.error(notReadyError).toJson();
            }
        }
        
        // Parse objects filter
        List<String> objects = parseObjectsList(objectsJson);
        
        int defaultLimit = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, "limit", 100); //$NON-NLS-1$

        int limit = JsonUtils.extractIntArgument(params, "limit", defaultLimit); //$NON-NLS-1$
        limit = Math.min(Math.max(1, limit), 1000);
        
        return getProjectErrors(projectName, severity, checkId, objects, limit);
    }
    
    /**
     * Parses the objects array from JSON string using Gson JsonParser.
     * 
     * @param objectsJson JSON array string like ["Document.SalesOrder", "Catalog.Products"]
     * @return list of object FQNs
     */
    private List<String> parseObjectsList(String objectsJson)
    {
        List<String> result = new ArrayList<>();
        if (objectsJson == null || objectsJson.isEmpty())
        {
            return result;
        }
        
        try
        {
            JsonElement element = JsonParser.parseString(objectsJson);
            if (element.isJsonArray())
            {
                JsonArray array = element.getAsJsonArray();
                for (JsonElement item : array)
                {
                    if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString())
                    {
                        result.add(item.getAsString());
                    }
                }
            }
        }
        catch (JsonParseException e)
        {
            Activator.logError("Error parsing objects JSON: " + objectsJson, e); //$NON-NLS-1$
        }
        return result;
    }
    
    /**
     * Gets project errors with filters using EDT IMarkerManager.
     * 
     * @param projectName filter by project name (null for all)
     * @param severity filter by severity (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL)
     * @param checkId filter by check ID substring
     * @param objects filter by object FQNs (empty list for all objects)
     * @param limit maximum number of results
     * @return Markdown formatted string with error details
     */
    public static String getProjectErrors(String projectName, String severity, String checkId, List<String> objects, int limit)
    {
        try
        {
            IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
            
            if (markerManager == null)
            {
                return "# Error\n\nIMarkerManager service is not available"; //$NON-NLS-1$
            }
            
            final ICheckRepository checkRepository = Activator.getDefault().getCheckRepository();
            IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
            
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            
            // Parse severity filter
            MarkerSeverity severityFilter = null;
            if (severity != null && !severity.isEmpty())
            {
                try
                {
                    severityFilter = MarkerSeverity.valueOf(severity.toUpperCase());
                }
                catch (IllegalArgumentException e)
                {
                    // Invalid severity, will show all
                }
            }
            final MarkerSeverity finalSeverityFilter = severityFilter;
            final String finalCheckId = checkId;
            
            // Validate project if specified
            if (projectName != null && !projectName.isEmpty())
            {
                IProject project = workspace.getRoot().getProject(projectName);
                if (project == null || !project.exists())
                {
                    return "# Error\n\nProject not found: " + projectName; //$NON-NLS-1$
                }
            }
            
            // Normalize object FQNs to support both English and Russian metadata type names.
            // For each input FQN, generate all variants (original + English + Russian, lowercased)
            // so we can match markers regardless of the configuration language.
            // Using Set for deduplication of variants.
            final Set<String> finalObjects = new HashSet<>();
            if (objects != null)
            {
                for (String fqn : objects)
                {
                    finalObjects.addAll(MetadataTypeUtils.getAllFqnVariants(fqn));
                }
            }
            
            // Determine the set of projects to scan. Marker presentation must be resolved
            // inside a BM read transaction, and a transaction is bound to a single project's
            // model, so we collect markers project by project.
            List<IProject> targetProjects = new ArrayList<>();
            if (projectName != null && !projectName.isEmpty())
            {
                targetProjects.add(workspace.getRoot().getProject(projectName));
            }
            else
            {
                // getProject() does not touch resolvedDataCache, so this pass is safe.
                Set<IProject> distinct = new LinkedHashSet<>();
                markerManager.markers().forEach(marker -> {
                    IProject markerProject = marker.getProject();
                    if (markerProject != null)
                    {
                        distinct.add(markerProject);
                    }
                });
                targetProjects.addAll(distinct);
            }
            
            final List<ErrorInfo> errors = new ArrayList<>();
            // Markers whose presentation could not be resolved even inside a transaction.
            // They are NOT dropped, but they are surfaced differently depending on context,
            // so we track the two cases separately to keep the warning text honest:
            //  - unresolvedShown: reported in the table with a "<unresolved: ...>" placeholder;
            //  - unresolvedFilteredOut: excluded from the result because an explicit objects
            //    filter is active and the location could not be resolved to test membership.
            final int[] unresolvedShown = {0};
            final int[] unresolvedFilteredOut = {0};
            final IMarkerManager finalMarkerManager = markerManager;
            
            for (IProject project : targetProjects)
            {
                if (project == null || !project.exists())
                {
                    continue;
                }
                if (errors.size() >= limit)
                {
                    break;
                }
                
                final IProject finalProject = project;
                final int remaining = limit - errors.size();
                
                // Resolve the project's BM model so getObjectPresentation() can lazily
                // resolve the marker target inside a read transaction. The getModel(IProject)
                // overload is the idiomatic path used across the plugin (FindReferencesTool,
                // AddMetadataAttributeTool, tag tools), so no IDtProjectManager is needed.
                IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(project) : null;
                
                Runnable collector = () -> finalMarkerManager.markers()
                    .filter(marker -> finalProject.equals(marker.getProject()))
                    .filter(marker -> matchesFilters(marker, finalSeverityFilter, finalCheckId,
                        finalObjects, checkRepository, unresolvedFilteredOut))
                    .limit(remaining)
                    .forEach(marker -> errors.add(toErrorInfo(marker, checkRepository, unresolvedShown)));
                
                if (bmModel != null)
                {
                    bmModel.executeReadonlyTask(new AbstractBmTask<Void>("CollectProjectErrors") //$NON-NLS-1$
                    {
                        @Override
                        public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
                        {
                            collector.run();
                            return null;
                        }
                    });
                }
                else
                {
                    // Not an EDT project (no BM model): best effort. Per-marker access is
                    // still guarded, so an unresolved marker is reported, never dropped.
                    collector.run();
                }
            }
            
            // Build Markdown response for better readability and context efficiency
            StringBuilder md = new StringBuilder();
            
            if (errors.isEmpty())
            {
                md.append("# No Errors Found\n\n"); //$NON-NLS-1$
                if (projectName != null && !projectName.isEmpty())
                {
                    md.append("Project: **").append(projectName).append("**\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (severity != null && !severity.isEmpty())
                {
                    md.append("Severity filter: ").append(severity).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (objects != null && !objects.isEmpty())
                {
                    md.append("Objects filter: ").append(String.join(", ", objects)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                md.append("\nNo configuration problems match the specified criteria."); //$NON-NLS-1$
            }
            else
            {
                md.append("# Configuration Problems\n\n"); //$NON-NLS-1$
                md.append("**Found:** ").append(errors.size()); //$NON-NLS-1$
                if (errors.size() >= limit)
                {
                    md.append("+ (limited to ").append(limit).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                md.append("\n\n"); //$NON-NLS-1$
                
                // Build table matching EDT's Configuration Problems view
                md.append("| Description | Location | Check code | Has docs |\n"); //$NON-NLS-1$
                md.append("|-------------|----------|------------|----------|\n"); //$NON-NLS-1$
                
                for (ErrorInfo error : errors)
                {
                    md.append("| ").append(MarkdownUtils.escapeForTable(error.message)); //$NON-NLS-1$
                    md.append(" | ").append(MarkdownUtils.escapeForTable(error.objectPresentation)); //$NON-NLS-1$
                    
                    // Show symbolic check ID if available, otherwise show check code
                    String displayCheckId = error.checkId != null && !error.checkId.isEmpty() 
                        ? error.checkId 
                        : error.checkCode;
                    md.append(" | `").append(displayCheckId).append("`"); //$NON-NLS-1$ //$NON-NLS-2$
                    
                    // Add documentation availability flag
                    md.append(" | ").append(error.hasDocumentation ? "true" : "false").append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                }
            }
            
            // Surface unresolved markers explicitly instead of silently dropping them.
            // Two distinct cases, reported separately so each warning matches reality.
            if (unresolvedShown[0] > 0)
            {
                md.append("\n> ⚠️ ").append(unresolvedShown[0]) //$NON-NLS-1$
                  .append(" marker(s) could not be resolved and are shown with a placeholder location. ") //$NON-NLS-1$
                  .append("Run clean_project / revalidate_objects to refresh them."); //$NON-NLS-1$
            }
            if (unresolvedFilteredOut[0] > 0)
            {
                md.append("\n> ⚠️ ").append(unresolvedFilteredOut[0]) //$NON-NLS-1$
                  .append(" marker(s) were excluded from the object filter because their location could not be resolved. ") //$NON-NLS-1$
                  .append("Run clean_project / revalidate_objects, or remove the objects filter, to include them."); //$NON-NLS-1$
            }
            
            return md.toString();
        }
        catch (Exception e)
        {
            Activator.logError("Error getting project errors", e); //$NON-NLS-1$
            return "# Error\n\nFailed to get project errors: " + e.getMessage(); //$NON-NLS-1$
        }
    }
    
    /**
     * Applies the severity, checkId and objects filters to a single marker.
     * Must be called inside a BM read transaction so that
     * {@link Marker#getObjectPresentation()} can resolve.
     */
    private static boolean matchesFilters(Marker marker, MarkerSeverity severityFilter,
        String checkId, Set<String> objects, ICheckRepository checkRepository, int[] unresolvedFilteredOut)
    {
        // Severity filter
        if (severityFilter != null && marker.getSeverity() != severityFilter)
        {
            return false;
        }
        
        // checkId filter: match either the short UID (e.g. "SU23") or the symbolic id
        // (e.g. "semicolon-missing"). The short UID alone is rarely what callers type.
        if (checkId != null && !checkId.isEmpty() && !checkIdMatches(marker, checkId, checkRepository))
        {
            return false;
        }
        
        // Objects filter (FQN matching against the resolved object presentation)
        if (!objects.isEmpty())
        {
            String objectPresentation;
            try
            {
                objectPresentation = marker.getObjectPresentation();
            }
            catch (Exception e)
            {
                // Cannot resolve the location, so we cannot decide membership for an
                // explicit object filter. The marker is excluded from the result; count it
                // separately so the caller is warned that it was filtered out, not shown.
                unresolvedFilteredOut[0]++;
                return false;
            }
            if (objectPresentation == null || objectPresentation.isEmpty())
            {
                return false;
            }
            
            String presentationLower = objectPresentation.toLowerCase();
            for (String fqnVariant : objects)
            {
                if (presentationLower.contains(fqnVariant))
                {
                    return true;
                }
            }
            return false;
        }
        
        return true;
    }
    
    /**
     * Returns true when the user supplied checkId substring matches either the marker
     * short UID or its symbolic check id.
     */
    private static boolean checkIdMatches(Marker marker, String checkId, ICheckRepository checkRepository)
    {
        String needle = checkId.toLowerCase();
        String shortUid = marker.getCheckId();
        if (shortUid != null && shortUid.toLowerCase().contains(needle))
        {
            return true;
        }
        if (checkRepository != null && shortUid != null && !shortUid.isEmpty() && marker.getProject() != null)
        {
            try
            {
                CheckUid uid = checkRepository.getUidForShortUid(shortUid, marker.getProject());
                if (uid != null && uid.getCheckId() != null
                    && uid.getCheckId().toLowerCase().contains(needle))
                {
                    return true;
                }
            }
            catch (Exception e)
            {
                // Ignore - fall back to short UID comparison result
            }
        }
        return false;
    }
    
    /**
     * Builds an {@link ErrorInfo} from a marker. Must be called inside a BM read
     * transaction. If the object presentation cannot be resolved the marker is still
     * reported with a placeholder location and counted via {@code unresolvedShown}.
     */
    private static ErrorInfo toErrorInfo(Marker marker, ICheckRepository checkRepository, int[] unresolvedShown)
    {
        ErrorInfo error = new ErrorInfo();
        String shortUid = marker.getCheckId() != null ? marker.getCheckId() : ""; //$NON-NLS-1$
        error.checkCode = shortUid;
        
        // Try to convert short UID (e.g. "SU23") to symbolic check ID (e.g. "bsl-legacy-check-expression-type")
        if (checkRepository != null && !shortUid.isEmpty() && marker.getProject() != null)
        {
            try
            {
                CheckUid checkUid = checkRepository.getUidForShortUid(shortUid, marker.getProject());
                if (checkUid != null)
                {
                    error.checkId = checkUid.getCheckId();
                }
            }
            catch (Exception e)
            {
                // Ignore - will use short UID instead
            }
        }
        
        // Check if documentation exists for this check
        error.hasDocumentation = false;
        if (error.checkId != null && !error.checkId.isEmpty())
        {
            error.hasDocumentation = GetCheckDescriptionTool.hasCheckDocumentation(error.checkId);
        }
        
        error.message = marker.getMessage() != null ? marker.getMessage() : ""; //$NON-NLS-1$
        error.objectPresentation = safeObjectPresentation(marker, unresolvedShown);
        return error;
    }
    
    /**
     * Reads {@link Marker#getObjectPresentation()} defensively. On resolution failure the
     * marker is kept (never dropped) with a placeholder location, and counted.
     */
    private static String safeObjectPresentation(Marker marker, int[] unresolvedShown)
    {
        try
        {
            String presentation = marker.getObjectPresentation();
            return presentation != null ? presentation : ""; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            unresolvedShown[0]++;
            IProject project = marker.getProject();
            return "<unresolved: " + (project != null ? project.getName() : "?") + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }
    
    /**
     * Helper class to store error info.
     */
    private static class ErrorInfo
    {
        String checkCode;          // Short UID like "SU23"
        String checkId;            // Symbolic ID like "bsl-legacy-check-expression-type"
        String message;
        String objectPresentation;
        boolean hasDocumentation;  // Whether documentation exists for this check
    }
}
