/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.v8.dt.check.ICheckScheduler;

/**
 * Tool to revalidate EDT project or specific objects by their FQN.
 * This performs:
 * 1. Refresh project from disk (to detect external changes)
 * 2. Find objects by FQN
 * 3. Schedule validation for those objects
 * 4. Wait for validation to complete
 */
public class RevalidateObjectsTool implements IMcpTool
{
    public static final String NAME = "revalidate_objects"; //$NON-NLS-1$

    /** Input param: list of object FQNs to revalidate (empty = full project). */
    private static final String KEY_OBJECTS = "objects"; //$NON-NLS-1$

    /** Result {@code mode} value: a targeted object-list revalidation (vs. full project). */
    private static final String MODE_OBJECTS = "objects"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Revalidate EDT project or specific objects. " + //$NON-NLS-1$
               "If objects array is empty or missing, revalidates entire project. " + //$NON-NLS-1$
               "FQN examples: 'Document.SalesOrder', 'Catalog.Products', 'CommonModule.Common'. " + //$NON-NLS-1$
               "Russian type names are also supported (e.g. 'Документ.ПриходнаяНакладная', 'Справочник.Номенклатура')."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty(KEY_OBJECTS, "FQNs to revalidate (e.g. ['Document.SalesOrder']). Russian type names supported (e.g. 'Документ.ПродажаТоваров'). Empty array = full project revalidation") //$NON-NLS-1$
            .build();
    }
    
    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$

        // Refuse only the transient BUILDING state; a missing/closed project
        // falls through to the value-naming 'Project not found' below.
        if (projectName != null && !projectName.isEmpty())
        {
            String building = ProjectStateChecker.buildingErrorOrNull(projectName);
            if (building != null)
            {
                return ToolResult.error(building).toJson();
            }
        }

        // Objects filter: accepts a JSON array (["Document.SalesOrder"]) or a
        // comma-separated string, via the shared extractArrayArgument helper.
        // null (param absent) is normalized to an empty list = full-project revalidation.
        List<String> objects = JsonUtils.extractArrayArgument(params, KEY_OBJECTS);
        if (objects == null)
        {
            objects = new ArrayList<>();
        }

        return revalidateObjects(projectName, objects);
    }
    
    /**
     * Revalidates specific objects in a project or full project.
     * 
     * @param projectName name of the project
     * @param objectFqns list of object FQNs to revalidate (empty for full project)
     * @return MARKDOWN summary on success, or a {@link ToolResult#error} JSON payload
     *     on failure (the server delivers the latter as a structured tool error)
     */
    public static String revalidateObjects(String projectName, List<String> objectFqns)
    {
        // Validate parameters
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        
        // Empty objects list = full project revalidation
        boolean fullProjectRevalidation = (objectFqns == null || objectFqns.isEmpty());
        
        try
        {
            IProgressMonitor monitor = new NullProgressMonitor();

            // Find project
            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.exists())
            {
                return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            }

            if (!ctx.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }
            IProject project = ctx.project();
            
            // Refresh from disk
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            
            if (fullProjectRevalidation)
            {
                // Full project revalidation - use INCREMENTAL_BUILD
                Activator.logInfo("Revalidating entire project: " + project.getName()); //$NON-NLS-1$
                project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
                
                // Wait for build jobs and derived data to complete
                BuildUtils.waitForBuildAndDerivedData(project, monitor);

                // Action result: revalidation status only, no round-trip ID or
                // machine-structured payload, so MARKDOWN is the right format (see the
                // "Response format policy" in README / edt-mcp-tool-conventions).
                return FrontMatter.create()
                    .put("tool", NAME) //$NON-NLS-1$
                    .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("project", projectName) //$NON-NLS-1$
                    .put("mode", "full") //$NON-NLS-1$ //$NON-NLS-2$
                    .wrapContent("# Full project revalidation completed\n\n" //$NON-NLS-1$
                        + "- Project: " + projectName + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                // Partial revalidation - find objects and schedule validation
                return revalidateSpecificObjects(project, objectFqns, monitor);
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error during project revalidation", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }
    
    /**
     * Revalidates specific objects using ICheckScheduler.
     * 
     * @param project the IProject to work with
     * @param objectFqns list of object FQNs to revalidate
     * @param monitor progress monitor
     * @return MARKDOWN summary on success, or a {@link ToolResult#error} JSON payload
     *     on failure
     * @throws CoreException on error
     */
    private static String revalidateSpecificObjects(IProject project, List<String> objectFqns,
            IProgressMonitor monitor) throws CoreException
    {
        String projectName = project.getName();

        // Resolve the required services / BM model (read-only: returns an error payload
        // when a service or the model is unavailable). No validation is scheduled here.
        RevalidateServices services = resolveServices(project, projectName);
        if (services.error != null)
        {
            return services.error;
        }
        ICheckScheduler checkScheduler = services.checkScheduler;
        IBmModel bmModel = services.bmModel;

        // Find objects by FQN (read-only lookup): classifies each requested FQN into
        // found / not-found / skipped buckets and collects the bmIds to validate.
        ObjectLookupResult lookup = lookupObjectsToValidate(bmModel, objectFqns);
        List<String> found = lookup.found;
        List<String> notFound = lookup.notFound;
        List<String> skippedNullUri = lookup.skippedNullUri;
        Collection<Object> objectsToValidate = lookup.objectsToValidate;

        // Schedule validation if we found objects
        if (!objectsToValidate.isEmpty())
        {
            // Filter out any null values (shouldn't happen but defensive coding)
            Collection<Object> validObjects = new ArrayList<>();
            for (Object obj : objectsToValidate)
            {
                if (obj != null)
                {
                    validObjects.add(obj);
                }
            }
            
            if (!validObjects.isEmpty())
            {
                // Use 4-parameter version without IBmTransaction
                // Use empty set for checkIds = validate with all checks
                checkScheduler.scheduleValidation(project, Collections.emptySet(), 
                        validObjects, monitor);
            }
        }
        
        // Wait for build jobs and derived data to complete
        BuildUtils.waitForBuildAndDerivedData(project, monitor);

        // Action result: counts + per-object outcome lists. No round-trip ID or
        // machine-structured payload, so MARKDOWN is the right format (see the
        // "Response format policy" in README / edt-mcp-tool-conventions). FQNs are
        // user-supplied and may contain '|', so they go through the shared
        // MarkdownUtils table builder, which escapes every cell.
        FrontMatter fm = FrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .put("project", projectName) //$NON-NLS-1$
            .put("mode", MODE_OBJECTS) //$NON-NLS-1$
            .put("objectsRequested", objectFqns.size()) //$NON-NLS-1$
            .put("objectsFound", found.size()); //$NON-NLS-1$

        StringBuilder body = new StringBuilder();
        body.append("# Revalidation completed\n\n"); //$NON-NLS-1$
        body.append("- Project: ").append(projectName).append('\n'); //$NON-NLS-1$
        body.append("- Objects requested: ").append(objectFqns.size()).append('\n'); //$NON-NLS-1$
        body.append("- Objects found: ").append(found.size()).append('\n'); //$NON-NLS-1$

        appendFqnSection(body, "Validated", found); //$NON-NLS-1$
        appendFqnSection(body, "Not found", notFound); //$NON-NLS-1$
        appendFqnSection(body, "Skipped (no persistent id)", skippedNullUri); //$NON-NLS-1$

        return fm.wrapContent(body.toString());
    }

    /**
     * Resolves the services and BM model needed to schedule validation (read-only). On
     * any unavailable service / model the returned holder carries an {@code error} JSON
     * payload that the caller should return as-is; no validation is scheduled here.
     *
     * @param project the project to work with
     * @param projectName the project name (used only for error messages)
     * @return a {@link RevalidateServices} holder with the model + scheduler, or an error
     */
    private static RevalidateServices resolveServices(IProject project, String projectName)
    {
        RevalidateServices services = new RevalidateServices();

        // Get services from Activator
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        ICheckScheduler checkScheduler = Activator.getDefault().getCheckScheduler();

        if (bmModelManager == null)
        {
            services.error = ToolResult.error("IBmModelManager service is not available").toJson(); //$NON-NLS-1$
            return services;
        }

        if (checkScheduler == null)
        {
            services.error = ToolResult.error("ICheckScheduler service is not available").toJson(); //$NON-NLS-1$
            return services;
        }

        // Get DtProject
        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        IDtProject dtProject = dtProjectManager != null ? dtProjectManager.getDtProject(project) : null;

        if (dtProject == null)
        {
            services.error = ToolResult.error("Not an EDT project: " + projectName).toJson(); //$NON-NLS-1$
            return services;
        }

        // Get BM model
        IBmModel bmModel = bmModelManager.getModel(dtProject);
        if (bmModel == null)
        {
            services.error = ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
            return services;
        }

        services.checkScheduler = checkScheduler;
        services.bmModel = bmModel;
        return services;
    }

    /**
     * Finds the requested objects by FQN inside a read-only BM transaction and classifies
     * each into found / not-found / skipped (transient id) buckets, collecting the bmIds to
     * validate. Read-only: looks objects up and logs, but schedules nothing.
     *
     * @param bmModel the BM model to query
     * @param objectFqns the user-supplied FQNs (normalized to English singular for lookup,
     *     reported back in their original form)
     * @return an {@link ObjectLookupResult} with the per-bucket FQN lists and the bmIds
     */
    private static ObjectLookupResult lookupObjectsToValidate(IBmModel bmModel, List<String> objectFqns)
    {
        ObjectLookupResult result = new ObjectLookupResult();

        // Normalize FQNs to English singular form (supports Russian type names),
        // but keep the original user input for result reporting
        List<String> originalFqns = new ArrayList<>(objectFqns);
        List<String> normalizedFqns = new ArrayList<>();
        for (String fqn : objectFqns)
        {
            normalizedFqns.add(MetadataTypeUtils.normalizeFqn(fqn));
        }

        BmTransactions.<Void>read(bmModel, "RevalidateObjectsLookup", (tx, pm) -> //$NON-NLS-1$
        {
            for (int i = 0; i < normalizedFqns.size(); i++)
            {
                String normalizedFqn = normalizedFqns.get(i);
                String originalFqn = originalFqns.get(i);

                IBmObject obj = tx.getTopObjectByFqn(normalizedFqn);
                if (obj != null)
                {
                    // Use bmGetId() - returns Long which is accepted by scheduleValidation
                    long bmId = obj.bmGetId();
                    if (bmId > 0)
                    {
                        Activator.logInfo("Found object: " + originalFqn + " -> bmId: " + bmId); //$NON-NLS-1$ //$NON-NLS-2$
                        result.objectsToValidate.add(Long.valueOf(bmId));
                        result.found.add(originalFqn);
                    }
                    else
                    {
                        // Object found but has invalid ID (transient object)
                        Activator.logInfo("Object has invalid bmId: " + originalFqn + " -> " + bmId); //$NON-NLS-1$ //$NON-NLS-2$
                        result.skippedNullUri.add(originalFqn);
                    }
                }
                else
                {
                    Activator.logInfo("Object not found: " + originalFqn + " (normalized: " + normalizedFqn + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    result.notFound.add(originalFqn);
                }
            }
            return null;
        });

        return result;
    }

    /**
     * Holder for {@link #resolveServices}: the resolved scheduler + BM model, or an
     * {@code error} JSON payload the caller returns as-is.
     */
    private static class RevalidateServices
    {
        ICheckScheduler checkScheduler;
        IBmModel bmModel;
        String error;
    }

    /**
     * Holder for {@link #lookupObjectsToValidate}: the per-bucket FQN lists and the bmIds
     * collected for scheduling.
     */
    private static class ObjectLookupResult
    {
        final List<String> found = new ArrayList<>();
        final List<String> notFound = new ArrayList<>();
        final List<String> skippedNullUri = new ArrayList<>();
        final Collection<Object> objectsToValidate = new ArrayList<>();
    }

    /**
     * Appends a "## {@code title}" section listing the given FQNs as a single-column
     * Markdown table. Cells are escaped through {@link MarkdownUtils} so a user-supplied
     * FQN containing {@code |} cannot break the table. An empty list emits no section.
     *
     * @param body the buffer to append to
     * @param title the section heading
     * @param fqns the FQNs to list (may be empty)
     */
    private static void appendFqnSection(StringBuilder body, String title, List<String> fqns)
    {
        if (fqns == null || fqns.isEmpty())
        {
            return;
        }
        body.append("\n## ").append(title).append(" (").append(fqns.size()).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        body.append(MarkdownUtils.tableHeader("FQN")); //$NON-NLS-1$
        for (String fqn : fqns)
        {
            body.append(MarkdownUtils.tableRow(fqn));
        }
    }
}
