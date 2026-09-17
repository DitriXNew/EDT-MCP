/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.ditrix.edt.mcp.server.utils.ExtensionOriginUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Tool to get list of applications for a project.
 * Applications are required for database update and debug launch operations.
 */
public class GetApplicationsTool implements IMcpTool
{
    public static final String NAME = "get_applications"; //$NON-NLS-1$

    /** Output key: array of applications (infobases) for the project. */
    private static final String KEY_APPLICATIONS = "applications"; //$NON-NLS-1$

    /** Output key: number of applications found. */
    private static final String KEY_COUNT = "count"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Discover infobases connected to an EDT project. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('get_applications')."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name (required)", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.PROJECT, "EDT project name the applications belong to") //$NON-NLS-1$
            .objectArrayProperty(KEY_APPLICATIONS, "Applications with id, name, type and updateState. " //$NON-NLS-1$
                + "updateState is a cached infobase-equality comparison refreshed asynchronously; " //$NON-NLS-1$
                + "immediately after update_database it can still show the pre-update value, while " //$NON-NLS-1$
                + "update_database stateAfter is the authoritative post-update answer. " //$NON-NLS-1$
                + "updateState=UNKNOWN (with updateStateError) means that read did not conclude - " //$NON-NLS-1$
                + "it is NOT a claim that the infobase is up to date.") //$NON-NLS-1$
            .integerProperty(KEY_COUNT, "Number of applications found") //$NON-NLS-1$
            .stringProperty("message", "Informational message: no applications were found, or the " //$NON-NLS-1$ //$NON-NLS-2$
                + "applications were read but the default application could not be determined " //$NON-NLS-1$
                + "(defaultApplicationId is then absent because it is UNKNOWN, not because there " //$NON-NLS-1$
                + "is none).") //$NON-NLS-1$
            .stringProperty("defaultApplicationId", "Id of the project's default application") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("inheritedFromProject", //$NON-NLS-1$
                "Base/parent project the applications are inherited from (present only for " //$NON-NLS-1$
                    + "external-objects/extension projects whose applications come from their base project).") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public boolean connectsToInfobase()
    {
        // The per-application appManager.getUpdateState(app) read-back (see
        // updateStatesBounded) is the async background recompute that historically raised the
        // auth dialog (issue #230's history); gate on it here too (issue #270).
        return true;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);

        // Validate project name
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        
        // Refuse only the transient BUILDING state; a missing/closed project
        // falls through to the value-naming 'Project not found' below.
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }
        
        return getApplications(projectName);
    }
    
    /**
     * Gets list of applications for the specified project.
     * 
     * @param projectName name of the project
     * @return JSON string with result
     */
    private String getApplications(String projectName)
    {
        try
        {
            ApplicationSupport.ManagerResult mr = ApplicationSupport.resolveManager(projectName);
            if (!mr.ok())
            {
                return mr.errorJson();
            }
            IProject project = mr.project();
            IApplicationManager appManager = mr.manager();

            // Get applications for the named project, falling back to the base
            // configuration project for dependent projects with an empty list.
            ResolvedApplications resolved = resolveApplications(appManager, project);
            List<IApplication> applications = resolved.applications;

            if (applications == null || applications.isEmpty())
            {
                return ToolResult.success()
                    .put(McpKeys.PROJECT, projectName)
                    .put(KEY_APPLICATIONS, new JsonArray())
                    .put(KEY_COUNT, 0)
                    .put("message", "No applications found for project") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }

            // Build applications array
            JsonArray appsArray = buildApplicationsArray(appManager, applications);

            // Get default application from whichever project supplied the applications
            DefaultApplication defaultApp =
                resolveDefaultApplicationId(appManager, resolved.applicationsProject,
                    ApplicationSupport.LOOKUP_TIMEOUT_MS);

            ToolResult result = ToolResult.success()
                .put(McpKeys.PROJECT, projectName)
                .put(KEY_APPLICATIONS, appsArray)
                .put(KEY_COUNT, applications.size());

            applyDefaultApplication(result, defaultApp);

            // Present only on the inherited branch (dependent project whose applications
            // come from its base configuration project).
            if (resolved.baseProjectName != null)
            {
                result.put("inheritedFromProject", resolved.baseProjectName); //$NON-NLS-1$
            }

            return result.toJson();
        }
        catch (ApplicationListDeadline e)
        {
            // Not "no applications": the read never concluded, so nothing was measured. Reported as
            // an error precisely so the caller cannot read an empty list as an answer.
            Activator.logError("Error getting applications for project: " + projectName //$NON-NLS-1$
                + " — " + e.getMessage(), null); //$NON-NLS-1$
            return ToolResult.error("Error getting applications: " + e.getMessage() //$NON-NLS-1$
                + ". This says nothing about whether project '" + projectName //$NON-NLS-1$
                + "' has applications — retry once EDT is responsive.").toJson(); //$NON-NLS-1$
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error getting applications for project: " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Error getting applications: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Writes the default-application answer into the result: its ID, or the note saying why the
     * id is UNKNOWN — never both.
     *
     * <p>Extracted so the SERIALIZED payload can be pinned rather than the record in isolation.
     * Omitting {@code defaultApplicationId} is how this tool says "this project records no
     * default", so the one-line mistake of letting an unanswered lookup produce that same silence
     * has no other symptom: the caller reads an unasked question as an answer.
     *
     * @param result the success payload being built
     * @param defaultApp what the bounded default-application lookup established
     * @return the same {@code result}, for chaining
     */
    static ToolResult applyDefaultApplication(ToolResult result, DefaultApplication defaultApp)
    {
        if (defaultApp.id() != null)
        {
            result.put("defaultApplicationId", defaultApp.id()); //$NON-NLS-1$
        }
        else if (defaultApp.note() != null)
        {
            result.put("message", defaultApp.note()); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * Resolves the applications for the named project. For a configuration project this is always
     * the named project's own list. For a dependent project (external-objects / extension) whose
     * own application list is empty, falls back to the BASE configuration project the applications
     * are inherited from.
     *
     * <p>Both listings are BOUNDED (#622): this tool is the recovery path every other application
     * error points the agent at ("Use get_applications to list available application IDs"), so it
     * has to answer even when EDT does not. What that buys is a FINITE worst case, not a fast one —
     * these two listings, the default-application lookup and the per-application update-state reads
     * are four separate budgets that ADD UP. The claim this class makes is the one it can keep:
     * every {@link IApplicationManager} read it performs is bounded.
     *
     * @param appManager the application manager
     * @param project the named project
     * @return the resolved applications together with the supplying project and (for the inherited
     *         branch only) the base project name
     * @throws ApplicationException if the application list cannot be read
     */
    private ResolvedApplications resolveApplications(IApplicationManager appManager, IProject project)
        throws ApplicationException
    {
        List<IApplication> applications =
            listBounded(appManager, project, ApplicationSupport.LOOKUP_TIMEOUT_MS);
        if (applications != null && !applications.isEmpty())
        {
            return new ResolvedApplications(applications, project, null);
        }

        IProject base = ExtensionOriginUtils.resolveBaseProject(project);
        if (base != null && base.exists() && base.isOpen())
        {
            List<IApplication> baseApplications =
                listBounded(appManager, base, ApplicationSupport.LOOKUP_TIMEOUT_MS);
            if (baseApplications != null && !baseApplications.isEmpty())
            {
                return new ResolvedApplications(baseApplications, base, base.getName());
            }
        }

        return new ResolvedApplications(applications, project, null);
    }

    /**
     * One bounded application listing.
     *
     * @param appManager the application manager
     * @param project the project to list
     * @param timeoutMs the caller-side deadline for this one read
     * @return the project's applications
     * @throws ApplicationListDeadline when the read did not conclude — never an empty list, which
     *     is the one answer this tool must not invent, because the caller acts on "no applications"
     */
    static List<IApplication> listBounded(IApplicationManager appManager, IProject project,
        long timeoutMs)
    {
        ApplicationSupport.BoundedRead<List<IApplication>> read =
            ApplicationSupport.getApplicationsBounded(appManager, project, timeoutMs);
        if (!read.concluded())
        {
            throw new ApplicationListDeadline(read.deadlineFailure());
        }
        return read.valueOrRethrow();
    }

    /** An application listing that did not conclude inside its deadline (#622). */
    static final class ApplicationListDeadline extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        ApplicationListDeadline(String message)
        {
            super(message);
        }
    }

    /**
     * Builds the JSON array describing the given applications, preserving the original
     * per-application property order (id, name, type, update state, required version).
     *
     * @param appManager the application manager
     * @param applications the applications to render
     * @return the JSON array of applications
     */
    private JsonArray buildApplicationsArray(IApplicationManager appManager, List<IApplication> applications)
    {
        UpdateStates updateStates =
            updateStatesBounded(appManager, applications, ApplicationSupport.LOOKUP_TIMEOUT_MS);
        JsonArray appsArray = new JsonArray();
        for (int i = 0; i < applications.size(); i++)
        {
            IApplication app = applications.get(i);
            JsonObject appObj = new JsonObject();
            appObj.addProperty("id", app.getId()); //$NON-NLS-1$
            appObj.addProperty("name", app.getName()); //$NON-NLS-1$

            // Add type info
            if (app.getType() != null)
            {
                appObj.addProperty("type", app.getType().getId()); //$NON-NLS-1$
            }

            // Add update state (or say it is unknown — never silently omit it)
            updateStates.mergeInto(appObj, i);

            // Add required version if present
            app.getRequiredVersion().ifPresent(version ->
                appObj.addProperty("requiredVersion", version)); //$NON-NLS-1$

            appsArray.add(appObj);
        }
        return appsArray;
    }

    /**
     * Reads the update state of EVERY application under ONE deadline.
     *
     * <p>{@code getUpdateState} is an {@link IApplicationManager} call — this file's own comment on
     * {@code connectsToInfobase()} calls it the dangerous one — and it ran once per application
     * with no bound at all, which made the tool every other error points the agent at the tool that
     * could park indefinitely. One budget for the whole loop, not one per application: they queue
     * behind the same wedged monitor, so N deadlines would multiply the wait by the application
     * count for no extra information.
     *
     * <p>Cells are published through an {@link AtomicReferenceArray} because the job may still be
     * running when the deadline returns: each entry is filled completely and then set, so a reader
     * either sees a finished object or nothing, never a half-built one.
     *
     * @param appManager the application manager
     * @param applications the applications, in the order the result renders them
     * @param timeoutMs the budget for ALL the update-state reads together
     * @return the filled cells plus the reason any of them are missing; never {@code null}
     */
    static UpdateStates updateStatesBounded(IApplicationManager appManager,
        List<IApplication> applications, long timeoutMs)
    {
        AtomicReferenceArray<JsonObject> cells = new AtomicReferenceArray<>(applications.size());
        ApplicationSupport.BoundedRead<Void> read = ApplicationSupport.readBounded(
            "Read EDT application update states", //$NON-NLS-1$
            "the EDT update-state read for the project's applications", //$NON-NLS-1$
            timeoutMs, () -> {
                for (int i = 0; i < applications.size(); i++)
                {
                    cells.set(i, readUpdateState(appManager, applications.get(i)));
                }
                return null;
            });
        if (read.concluded())
        {
            return new UpdateStates(cells, null);
        }
        Activator.logError("get_applications: " + read.deadlineFailure(), null); //$NON-NLS-1$
        return new UpdateStates(cells, read.deadlineFailure());
    }

    /**
     * The per-application update states of ONE call, plus the reason the missing ones are missing.
     *
     * <p>A local value, not tool state: the registrar keeps ONE {@link GetApplicationsTool}
     * instance and concurrent calls share it, so a field here would let one call's deadline
     * describe another call's applications.
     */
    static final class UpdateStates
    {
        private final AtomicReferenceArray<JsonObject> cells;
        private final String unknownReason;

        UpdateStates(AtomicReferenceArray<JsonObject> cells, String unknownReason)
        {
            this.cells = cells;
            this.unknownReason = unknownReason;
        }

        /**
         * Copies application {@code index}'s update-state properties into {@code appObj}, or says
         * they are UNKNOWN.
         *
         * <p>A missing cell is REPORTED, not omitted: the caller reads {@code updateState} to
         * decide whether to run {@code update_database}, and an absent field would read as
         * "nothing to say" about a question that was never answered.
         *
         * @param appObj the application's JSON object
         * @param index the application's position in the rendered list
         */
        void mergeInto(JsonObject appObj, int index)
        {
            JsonObject cell = cells.get(index);
            if (cell != null)
            {
                for (Map.Entry<String, JsonElement> entry : cell.entrySet())
                {
                    appObj.add(entry.getKey(), entry.getValue());
                }
                return;
            }
            appObj.addProperty("updateState", "UNKNOWN"); //$NON-NLS-1$ //$NON-NLS-2$
            appObj.addProperty("updateStateDescription", //$NON-NLS-1$
                "The update state could not be read, so whether this infobase is behind the model " //$NON-NLS-1$
                    + "is unknown - it is NOT a claim that it is up to date."); //$NON-NLS-1$
            appObj.addProperty("updateStateError", unknownReason != null //$NON-NLS-1$
                ? unknownReason : "the update-state read produced no answer for this application"); //$NON-NLS-1$
        }
    }

    /**
     * Holds the applications resolved for a project together with the project that supplied them
     * and, only when inherited from a base configuration project, that base project's name.
     */
    private static final class ResolvedApplications
    {
        final List<IApplication> applications;
        final IProject applicationsProject;
        final String baseProjectName;

        ResolvedApplications(List<IApplication> applications, IProject applicationsProject, String baseProjectName)
        {
            this.applications = applications;
            this.applicationsProject = applicationsProject;
            this.baseProjectName = baseProjectName;
        }
    }

    /**
     * Reads the update-state properties of a single application into their own JSON object.
     * On error the state is reported as {@code "ERROR"} together with the error message,
     * preserving the original inline behaviour.
     *
     * <p>Returns a self-contained object rather than writing into the application's own: it runs
     * on the bounded read's job thread, and the result is published through an
     * {@link AtomicReferenceArray} only once it is complete.
     *
     * @param appManager the application manager
     * @param app the application whose update state is read
     * @return the update-state properties; empty when the manager reported no state at all
     */
    private static JsonObject readUpdateState(IApplicationManager appManager, IApplication app)
    {
        JsonObject stateObj = new JsonObject();
        try
        {
            ApplicationUpdateState updateState = appManager.getUpdateState(app);
            if (updateState != null)
            {
                stateObj.addProperty("updateState", updateState.name()); //$NON-NLS-1$

                // Add human-readable description
                String stateDescription = getUpdateStateDescription(updateState);
                stateObj.addProperty("updateStateDescription", stateDescription); //$NON-NLS-1$
            }
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error getting update state for application: " + app.getId(), e); //$NON-NLS-1$
            stateObj.addProperty("updateState", "ERROR"); //$NON-NLS-1$ //$NON-NLS-2$
            stateObj.addProperty("updateStateError", e.getMessage()); //$NON-NLS-1$
        }
        return stateObj;
    }

    /**
     * Resolves the id of the project's default application.
     *
     * <p>BOUNDED (#622), and the deadline is not swallowed. Omitting {@code defaultApplicationId}
     * is how this tool says "this project has no default application", so a lookup that never
     * concluded must not produce the same silence — the caller would read an unanswered question
     * as an answer. The deadline is carried out in {@link DefaultApplication#note()} for the
     * existing {@code message} field instead.
     *
     * @param appManager the application manager
     * @param project the project
     * @param timeoutMs the caller-side deadline for this one read
     * @return the resolved id, or the reason it is unknown; never both
     */
    static DefaultApplication resolveDefaultApplicationId(IApplicationManager appManager,
        IProject project, long timeoutMs)
    {
        ApplicationSupport.BoundedRead<Optional<IApplication>> read =
            ApplicationSupport.getDefaultApplicationBounded(appManager, project, timeoutMs);
        if (!read.concluded())
        {
            Activator.logError("Error getting default application: " + read.deadlineFailure(), null); //$NON-NLS-1$
            return new DefaultApplication(null, "The default application is UNKNOWN: " //$NON-NLS-1$
                + read.deadlineFailure() + ". The applications above were read successfully."); //$NON-NLS-1$
        }
        try
        {
            IApplication defaultApp = read.valueOrRethrow().orElse(null);
            if (defaultApp != null)
            {
                return new DefaultApplication(defaultApp.getId(), null);
            }
        }
        catch (ApplicationException e)
        {
            // A RAISED lookup establishes exactly as little as an expired one, so it must not fall
            // into the silence below, which is this tool's way of saying "there is no default".
            Activator.logError("Error getting default application", e); //$NON-NLS-1$
            return new DefaultApplication(null, "The default application is UNKNOWN: the lookup " //$NON-NLS-1$
                + "failed (" + e.getMessage() //$NON-NLS-1$
                + "). The applications above were read successfully."); //$NON-NLS-1$
        }
        // The one branch that IS a measured answer: the lookup concluded and reported no default.
        return new DefaultApplication(null, null);
    }

    /**
     * The default-application answer: its id, or the note saying why the id is unknown.
     *
     * @param id the resolved default application id, or {@code null}
     * @param note why the id is unknown, or {@code null} when there simply is no default
     */
    record DefaultApplication(String id, String note)
    {
    }

    /**
     * Returns human-readable description for update state.
     *
     * <p>{@code UNKNOWN} is spelled out rather than echoed as "Unknown state" (#433): EDT's
     * application delegates return it when they have no live connection to the infobase
     * ({@code getUpdateState(equalityState, !isConnected(project, infobase))} — the disconnected
     * branch wins before the equality state is even looked at), so the actionable reading is
     * "not connected", not "something is wrong with the configuration". The wording stays
     * hedged because a connected infobase whose equality state EDT does not recognise lands
     * here too.
     *
     * @param state the update state
     * @return description string
     */
    private static String getUpdateStateDescription(ApplicationUpdateState state)
    {
        switch (state)
        {
            case UNKNOWN:
                return "Unknown - EDT could not read the state, typically because it is not " //$NON-NLS-1$
                    + "connected to this infobase (for a standalone server the server may not " //$NON-NLS-1$
                    + "be running); a connected infobase whose equality state EDT does not " //$NON-NLS-1$
                    + "recognise lands here too"; //$NON-NLS-1$
            case INCREMENTAL_UPDATE_REQUIRED:
                return "Incremental update required"; //$NON-NLS-1$
            case FULL_UPDATE_REQUIRED:
                return "Full update required"; //$NON-NLS-1$
            case UPDATED:
                return "Up to date"; //$NON-NLS-1$
            case BEING_UPDATED:
                return "Currently being updated"; //$NON-NLS-1$
            default:
                return state.name();
        }
    }
}
