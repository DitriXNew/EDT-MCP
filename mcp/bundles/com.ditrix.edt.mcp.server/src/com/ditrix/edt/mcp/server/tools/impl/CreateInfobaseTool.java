/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.common.Pair;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.core.operations.IInfobaseCreationOperation;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService;
import com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerInfobase;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Creates a new FILE infobase (1C:Enterprise database) and binds it to a configuration
 * project so it appears as an application in {@code get_applications}.
 *
 * <p>The operation decomposes into two distinct steps:
 * <ol>
 *   <li>Create the infobase on disk via {@code IInfobaseCreationOperation} (which shells out to
 *       the 1C thick client {@code 1cv8 CREATEINFOBASE}) — requires a registered 1C platform
 *       runtime. This step runs in a background Eclipse Job with a bounded timeout (120 s).</li>
 *   <li>Associate the infobase with the project via {@code IInfobaseAssociationManager.associate},
 *       which causes {@code InfobaseApplicationProvisionDelegate} to surface a new
 *       {@code IInfobaseApplication} of type {@code com.e1c.g5.dt.applications.type.infobase}.</li>
 * </ol>
 *
 * <p><strong>Unattended-safety:</strong> the create operation runs entirely in a background Job;
 * no SWT / UI-thread code is executed. A fast platform-availability probe fires before the Job
 * is submitted — if no 1C platform runtime is registered the tool fails immediately with an
 * actionable message instead of hanging.
 *
 * <p><strong>Scope: FILE infobases only.</strong> SERVER and WEB infobases require additional
 * DBMS / cluster parameters and are rejected with a clear "not yet supported" message.
 */
public class CreateInfobaseTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "create_infobase"; //$NON-NLS-1$

    /** Background-Job timeout for the actual infobase creation (1cv8 process). */
    private static final long CREATE_TIMEOUT_SECONDS = 120;

    /** Infobase application type ID as defined in the applications.infobases plugin.xml. */
    private static final String INFOBASE_APP_TYPE = "com.e1c.g5.dt.applications.type.infobase"; //$NON-NLS-1$

    /**
     * Standalone-server (WST) application type ID as defined in the applications.wst plugin.xml.
     * The application framework surfaces an {@code IServerApplication} of this type once a WST
     * standalone server with a project-bound infobase module exists.
     */
    private static final String WST_SERVER_APP_TYPE = "com.e1c.g5.dt.applications.type.wst-server"; //$NON-NLS-1$

    /** {@code applicationKind} value for the standalone-server path (autonomous server). */
    private static final String KIND_STANDALONE_SERVER = "standaloneServer"; //$NON-NLS-1$

    /** {@code applicationKind} value for the default file-infobase path. */
    private static final String KIND_INFOBASE = "infobase"; //$NON-NLS-1$

    /**
     * Default cluster/server listen port for a standalone server (the value {@code generateDefaultConfig}
     * uses). Confirmed live: a server created with this default returned a working web URL on the EDT
     * 2025.2 stand.
     */
    private static final int DEFAULT_STANDALONE_SERVER_PORT = 8314;

    /** Symbolic name of the bundle that owns the standalone-server WST service. */
    private static final String STANDALONE_SERVER_WST_CORE_BUNDLE_ID =
        "com.e1c.g5.v8.dt.platform.standaloneserver.wst.core"; //$NON-NLS-1$

    /** Symbolic name of the bundle that owns the internal PlatformServicesCore (and its Guice injector). */
    private static final String PLATFORM_SERVICES_CORE_BUNDLE_ID =
        "com._1c.g5.v8.dt.platform.services.core"; //$NON-NLS-1$

    /** Internal singleton holding the platform-services Guice injector (loaded via the owning bundle). */
    private static final String PLATFORM_SERVICES_CORE_CLASS =
        "com._1c.g5.v8.dt.internal.platform.services.core.PlatformServicesCore"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create a new FILE infobase (1C database) OR register an existing one, and bind it to " //$NON-NLS-1$
            + "a configuration project so it appears in get_applications. mode='create' (default) " //$NON-NLS-1$
            + "makes a new database (requires a registered 1C platform runtime); mode='register' " //$NON-NLS-1$
            + "adds an already-existing infobase at the given path without launching the platform. " //$NON-NLS-1$
            + "applicationKind='infobase' (default) makes a plain file infobase; " //$NON-NLS-1$
            + "applicationKind='standaloneServer' creates an autonomous (standalone) server that also " //$NON-NLS-1$
            + "exposes a web URL for HTTP testing (requires a registered 1C standalone-server runtime, " //$NON-NLS-1$
            + "platform >= 8.3.23). FILE type only (server/web rejected). Runs in a background Job " //$NON-NLS-1$
            + "(up to 120 s). Full parameters and examples: call get_tool_guide('create_infobase')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT configuration project to bind the new infobase to (required).", true) //$NON-NLS-1$
            .enumProperty("mode", //$NON-NLS-1$
                "'create' (default) = make a new file infobase at infobaseFile (launches the 1C " //$NON-NLS-1$
                + "platform); 'register' = add an EXISTING infobase already present at infobaseFile " //$NON-NLS-1$
                + "(no platform launch).", //$NON-NLS-1$
                "create", "register") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("infobaseFile", //$NON-NLS-1$
                "Absolute path to the infobase directory (required). For mode='create' the directory " //$NON-NLS-1$
                + "is created if absent and the 1Cv8.1CD files are written into it; for mode='register' " //$NON-NLS-1$
                + "it must already contain an existing file infobase.", //$NON-NLS-1$
                true)
            .stringProperty("infobaseName", //$NON-NLS-1$
                "Display name for the new infobase. If omitted, a name is auto-generated by EDT.") //$NON-NLS-1$
            .stringProperty("platform", //$NON-NLS-1$
                "1C platform version mask to use for creation (e.g. '8.3.25'). If omitted, EDT " //$NON-NLS-1$
                + "resolves the best available installed version automatically.") //$NON-NLS-1$
            .booleanProperty("setDefault", //$NON-NLS-1$
                "Set the new infobase as the default application for the project after creation " //$NON-NLS-1$
                + "(default false).") //$NON-NLS-1$
            .enumProperty("applicationKind", //$NON-NLS-1$
                "'infobase' (default) = a plain file infobase via the configurator; " //$NON-NLS-1$
                + "'standaloneServer' = an autonomous (standalone) server that creates and serves a " //$NON-NLS-1$
                + "new file infobase and exposes a web URL for HTTP testing (requires a registered 1C " //$NON-NLS-1$
                + "standalone-server runtime, platform >= 8.3.23).", //$NON-NLS-1$
                KIND_INFOBASE, KIND_STANDALONE_SERVER)
            .integerProperty("port", //$NON-NLS-1$
                "applicationKind='standaloneServer' only: the cluster/server listen port. " //$NON-NLS-1$
                + "Default 8314.") //$NON-NLS-1$
            .stringProperty("publicationName", //$NON-NLS-1$
                "applicationKind='standaloneServer' only: the web publication base/path. If omitted, " //$NON-NLS-1$
                + "a sanitized infobaseName (or the project name) is used.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "'created' (mode=create) or 'registered' (mode=register).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationKind", //$NON-NLS-1$
                "'infobase' or 'standaloneServer' — the kind of application created.") //$NON-NLS-1$
            .stringProperty("project", "Name of the configuration project.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("infobaseFile", "Path of the created infobase directory.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("infobaseName", "Display name of the created infobase.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("webUrl", //$NON-NLS-1$
                "applicationKind='standaloneServer' only: the infobase web URL for HTTP testing.") //$NON-NLS-1$
            .objectArrayProperty("applications", //$NON-NLS-1$
                "Applications bound to the project after creation (same shape as get_applications).") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "ID of the newly created application (for chaining into update_database).") //$NON-NLS-1$
            .stringProperty("message", "Human-readable status message.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Required parameters
        String err = JsonUtils.requireArgument(params, "projectName"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        String errFile = JsonUtils.requireArgument(params, "infobaseFile"); //$NON-NLS-1$
        if (errFile != null)
        {
            return errFile;
        }

        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String infobaseFileStr = JsonUtils.extractStringArgument(params, "infobaseFile"); //$NON-NLS-1$
        String infobaseName = JsonUtils.extractStringArgument(params, "infobaseName"); //$NON-NLS-1$
        String platform = JsonUtils.extractStringArgument(params, "platform"); //$NON-NLS-1$
        boolean setDefault = JsonUtils.extractBooleanArgument(params, "setDefault", false); //$NON-NLS-1$
        String modeStr = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        String applicationKind = JsonUtils.extractStringArgument(params, "applicationKind"); //$NON-NLS-1$
        String publicationName = JsonUtils.extractStringArgument(params, "publicationName"); //$NON-NLS-1$
        int port = JsonUtils.extractIntArgument(params, "port", DEFAULT_STANDALONE_SERVER_PORT); //$NON-NLS-1$

        // Validate applicationKind (default 'infobase'). When absent or 'infobase' the behaviour is
        // byte-identical to the original file-infobase tool.
        boolean standaloneServer;
        if (applicationKind == null || applicationKind.isEmpty() || KIND_INFOBASE.equals(applicationKind))
        {
            standaloneServer = false;
        }
        else if (KIND_STANDALONE_SERVER.equals(applicationKind))
        {
            standaloneServer = true;
        }
        else
        {
            return ToolResult.error("Invalid applicationKind: '" + applicationKind //$NON-NLS-1$
                + "'. Allowed values: '" + KIND_INFOBASE + "', '" + KIND_STANDALONE_SERVER //$NON-NLS-1$ //$NON-NLS-2$
                + "'.").toJson(); //$NON-NLS-1$
        }

        // Validate mode (default 'create').
        boolean register;
        if (modeStr == null || modeStr.isEmpty() || "create".equals(modeStr)) //$NON-NLS-1$
        {
            register = false;
        }
        else if ("register".equals(modeStr)) //$NON-NLS-1$
        {
            register = true;
        }
        else
        {
            return ToolResult.error("Invalid mode: '" + modeStr //$NON-NLS-1$
                + "'. Allowed values: 'create', 'register'.").toJson(); //$NON-NLS-1$
        }

        // Validate and normalize the infobase path early (before acquiring services)
        Path infobaseDir;
        try
        {
            infobaseDir = Paths.get(infobaseFileStr);
        }
        catch (InvalidPathException e)
        {
            return ToolResult.error("infobaseFile is not a valid path: '" + infobaseFileStr //$NON-NLS-1$
                + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // Refuse only the transient BUILDING state; missing/closed project falls through below.
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        if (standaloneServer)
        {
            // The standalone-server path always CREATES a new infobase (served by the server); it has
            // no register analogue, so reject mode='register' for clarity.
            if (register)
            {
                return ToolResult.error("mode='register' is not supported with " //$NON-NLS-1$
                    + "applicationKind='standaloneServer'. A standalone server creates and serves a " //$NON-NLS-1$
                    + "new infobase; omit mode (or use mode='create').").toJson(); //$NON-NLS-1$
            }
            return createStandaloneServer(projectName, infobaseDir, infobaseName, platform, port,
                publicationName);
        }

        return createInfobase(projectName, infobaseDir, infobaseName, platform, setDefault, register);
    }

    private String createInfobase(String projectName, Path infobaseDir,
            String infobaseName, String platform, boolean setDefault, boolean register)
    {
        // --- 1. Resolve project ---
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        if (!ctx.isOpen())
        {
            return ToolResult.error("Project is closed: " + projectName //$NON-NLS-1$
                + ". Open the project in EDT first.").toJson(); //$NON-NLS-1$
        }
        IProject project = ctx.project();

        // --- 2. Acquire services ---
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return ToolResult.error("IApplicationManager service is not available").toJson(); //$NON-NLS-1$
        }

        IInfobaseManager ibManager = Activator.getDefault().getInfobaseManager();
        if (ibManager == null)
        {
            return ToolResult.error("IInfobaseManager service is not available. " //$NON-NLS-1$
                + "Ensure EDT platform-services are running.").toJson(); //$NON-NLS-1$
        }

        IInfobaseAssociationManager assocManager =
            Activator.getDefault().getInfobaseAssociationManager();
        if (assocManager == null)
        {
            return ToolResult.error("IInfobaseAssociationManager service is not available. " //$NON-NLS-1$
                + "Ensure EDT platform-services are running.").toJson(); //$NON-NLS-1$
        }

        // --- 3. Auto-generate infobase name if omitted ---
        if (infobaseName == null || infobaseName.isEmpty())
        {
            try
            {
                infobaseName = ibManager.generateInfobaseName();
            }
            catch (Exception e)
            {
                infobaseName = projectName + "_infobase"; //$NON-NLS-1$
            }
        }

        // --- 4. Prepare the directory ---
        if (register)
        {
            // mode=register: the infobase must already exist on disk; do NOT create it.
            if (!Files.isDirectory(infobaseDir)
                || !Files.isRegularFile(infobaseDir.resolve("1Cv8.1CD"))) //$NON-NLS-1$
            {
                return ToolResult.error("No file infobase found at '" + infobaseDir //$NON-NLS-1$
                    + "' (expected a 1Cv8.1CD file). For mode='register' the path must point to an " //$NON-NLS-1$
                    + "existing file infobase; use mode='create' to make a new one.").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            // mode=create: create the target directory if it does not exist yet.
            try
            {
                Files.createDirectories(infobaseDir);
            }
            catch (Exception e)
            {
                return ToolResult.error("Cannot create infobase directory '" + infobaseDir //$NON-NLS-1$
                    + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
            }
        }

        // --- 5. Build the FILE infobase reference ---
        InfobaseReference ibRef =
            InfobaseReferences.newFileInfobaseReference(infobaseDir.toAbsolutePath().toString());
        ibRef.setName(infobaseName);
        // The reference MUST carry a UUID before perform(): the creation operation locks the
        // infobase by its UUID very early (LockManager.getLock), which NPEs on a null id.
        ibRef.setUuid(java.util.UUID.randomUUID());

        // --- 6. Create the database (create) or register the existing one (register) ---
        if (register)
        {
            // mode=register: add the existing infobase to EDT directly. No 1cv8 launch, no
            // platform runtime needed — this is a fast, synchronous EMF registration.
            try
            {
                ibManager.add(ibRef, null);
            }
            catch (Exception e)
            {
                Activator.logError("create_infobase: register failed for " + infobaseDir, e); //$NON-NLS-1$
                return ToolResult.error("Could not register the infobase at '" + infobaseDir //$NON-NLS-1$
                    + "': " + e.getMessage() //$NON-NLS-1$
                    + ". Ensure it is a valid file infobase that is not already registered.").toJson(); //$NON-NLS-1$
            }
            Activator.logInfo("create_infobase: registered existing infobase at " + infobaseDir); //$NON-NLS-1$
        }
        else
        {
            // mode=create: a brand-new database via the platform creation operation. This shells
            // out to 1cv8, so probe for a registered platform runtime first (fail fast, no hang)
            // and run perform() in a bounded background Job (never on the UI thread).
            IInfobaseCreationOperation creationOp = resolveCreationOperation();
            if (creationOp == null)
            {
                return ToolResult.error("No 1C platform runtime is registered in EDT - cannot " //$NON-NLS-1$
                    + "create a new infobase. Register a 1C:Enterprise platform installation in EDT " //$NON-NLS-1$
                    + "(Window -> Preferences -> 1C:Enterprise -> Installed Installations) and retry, " //$NON-NLS-1$
                    + "or use mode='register' for an existing infobase.").toJson(); //$NON-NLS-1$
            }

            IInfobaseCreationOperation.Builder builder = new IInfobaseCreationOperation.Builder()
                .infobaseReference(ibRef)
                .createNew(true)
                .addReference(true)
                .arguments(ModelFactory.eINSTANCE.createCreateInfobaseArguments());
            if (platform != null && !platform.isEmpty())
            {
                builder.platform(platform);
            }
            final IInfobaseCreationOperation.Descriptor descriptor = builder.build();

            final IInfobaseCreationOperation finalOp = creationOp;
            final AtomicReference<Exception> jobError = new AtomicReference<>();
            final String jobInfobaseName = infobaseName;

            Job createJob = new Job("Create infobase: " + jobInfobaseName) //$NON-NLS-1$
            {
                @Override
                protected org.eclipse.core.runtime.IStatus run(
                        org.eclipse.core.runtime.IProgressMonitor monitor)
                {
                    try
                    {
                        finalOp.perform(descriptor, monitor);
                    }
                    catch (Exception e)
                    {
                        jobError.set(e);
                    }
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                }
            };
            createJob.setUser(false);
            createJob.setSystem(true);
            createJob.schedule();

            try
            {
                boolean finished = createJob.join(
                    TimeUnit.SECONDS.toMillis(CREATE_TIMEOUT_SECONDS), null);
                if (!finished)
                {
                    createJob.cancel();
                    return ToolResult.error("Infobase creation timed out after " //$NON-NLS-1$
                        + CREATE_TIMEOUT_SECONDS + " seconds. The 1cv8 process may still be running. " //$NON-NLS-1$
                        + "Check the EDT log and the target directory '" + infobaseDir //$NON-NLS-1$
                        + "' for partial results.").toJson(); //$NON-NLS-1$
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return ToolResult.error("Infobase creation was interrupted.").toJson(); //$NON-NLS-1$
            }

            if (jobError.get() != null)
            {
                Exception ex = jobError.get();
                Activator.logError("create_infobase: creation failed for " + infobaseDir, ex); //$NON-NLS-1$
                return ToolResult.error("Infobase creation failed: " + ex.getMessage() //$NON-NLS-1$
                    + ". Verify that a compatible 1C platform is installed and that the " //$NON-NLS-1$
                    + "target directory '" + infobaseDir + "' is accessible.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }

            Activator.logInfo("create_infobase: infobase created at " + infobaseDir); //$NON-NLS-1$
        }

        // --- 7. Associate with the project ---
        try
        {
            assocManager.associate(project, ibRef, InfobaseAssociationSettings.notSynchronized());
        }
        catch (Exception e)
        {
            Activator.logError("create_infobase: association failed for project " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Infobase was created at '" + infobaseDir //$NON-NLS-1$
                + "' but could not be associated with project '" + projectName //$NON-NLS-1$
                + "': " + e.getMessage() //$NON-NLS-1$
                + ". Use delete_infobase to clean up if needed.").toJson(); //$NON-NLS-1$
        }

        Activator.logInfo("create_infobase: associated with project " + projectName); //$NON-NLS-1$

        // --- 8. Optionally set as default ---
        String setDefaultNote = null;
        if (setDefault)
        {
            try
            {
                IApplication newApp = findNewApplication(appManager, project, ibRef);
                if (newApp == null)
                {
                    setDefaultNote = "; the new infobase was created but could not be set as " //$NON-NLS-1$
                        + "default yet - set it manually or retry"; //$NON-NLS-1$
                    Activator.logError("create_infobase: setDefault skipped — new app not found yet", //$NON-NLS-1$
                        null);
                }
                else
                {
                    appManager.setDefaultApplication(project, newApp);
                }
            }
            catch (Exception e)
            {
                // Non-fatal: the infobase was created and associated; only the default-setting failed.
                Activator.logError("create_infobase: setDefault failed", e); //$NON-NLS-1$
            }
        }

        // --- 9. Read back and return ---
        return buildSuccessResult(projectName, infobaseDir, infobaseName,
            appManager, project, ibRef, setDefaultNote, register);
    }

    /**
     * Creates an autonomous (standalone) server that creates and serves a new file infobase, binds
     * it to the project, and exposes a web URL for HTTP testing.
     *
     * <p>This is a fully separate path from {@link #createInfobase}: instead of the configurator
     * ({@code 1cv8}) it goes through the EDT WST standalone-server layer
     * ({@link IStandaloneServerService#createServerWithInfobase}), which shells out to {@code ibcmd}
     * to create the infobase and registers a WST {@code IServer}. The application framework then
     * surfaces an {@code IServerApplication} of type {@link #WST_SERVER_APP_TYPE} automatically via
     * the same {@code IApplicationManager.getApplications(project)} read-back we already use.
     *
     * <p><strong>Unattended-safety:</strong> the runtime probe ({@code findRuntime}) fires BEFORE
     * the Job so "no runtime" fails instantly; the {@code ibcmd} shell-out runs entirely inside a
     * bounded background Job — never on the UI thread, no modal.
     *
     * @param projectName the configuration project to bind the new server to
     * @param infobaseDir the infobase / server working directory
     * @param infobaseName the display name (auto-generated from the directory if absent)
     * @param platform the platform version mask (may be {@code null}/empty = any)
     * @param port the cluster/server listen port (default {@value #DEFAULT_STANDALONE_SERVER_PORT})
     * @param publicationName the web publication base/path (defaults to a sanitized name)
     * @return the tool result JSON
     */
    private String createStandaloneServer(String projectName, Path infobaseDir,
            String infobaseName, String platform, int port, String publicationName)
    {
        // --- 1. Resolve project ---
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        if (!ctx.isOpen())
        {
            return ToolResult.error("Project is closed: " + projectName //$NON-NLS-1$
                + ". Open the project in EDT first.").toJson(); //$NON-NLS-1$
        }
        IProject project = ctx.project();

        // --- 2. Acquire services ---
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return ToolResult.error("IApplicationManager service is not available").toJson(); //$NON-NLS-1$
        }

        IStandaloneServerService serverService = acquireStandaloneServerService();
        if (serverService == null)
        {
            return ToolResult.error("Standalone-server service is not available; the EDT " //$NON-NLS-1$
                + "standalone-server feature is missing. Install a 1C platform >= 8.3.23 with the " //$NON-NLS-1$
                + "standalone server and ensure the EDT standalone-server plugins are present.") //$NON-NLS-1$
                .toJson();
        }

        // --- 3. Fail-fast runtime probe (BEFORE the Job, so "no runtime" fails instantly) ---
        final String versionMask = platform != null ? platform : ""; //$NON-NLS-1$
        boolean hasRuntime;
        try
        {
            hasRuntime = serverService.findRuntime(versionMask, null).isPresent();
        }
        catch (Exception e)
        {
            Activator.logError("create_infobase: standalone-server runtime probe failed", e); //$NON-NLS-1$
            hasRuntime = false;
        }
        if (!hasRuntime)
        {
            return ToolResult.error("No standalone-server runtime registered" //$NON-NLS-1$
                + (platform != null && !platform.isEmpty() ? " for version '" + platform + "'" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ". Install a 1C platform >= 8.3.23 with the standalone server (ibsrv/ibcmd) and " //$NON-NLS-1$
                + "register it in EDT (Window -> Preferences -> 1C:Enterprise -> Installed " //$NON-NLS-1$
                + "Installations), or pass a matching platform=...").toJson(); //$NON-NLS-1$
        }

        // --- 4. Auto-generate the infobase name from the directory if omitted ---
        if (infobaseName == null || infobaseName.isEmpty())
        {
            Path fileName = infobaseDir.getFileName();
            infobaseName = fileName != null ? fileName.toString() : projectName;
        }

        // --- 5. Build the FILE infobase reference for the new IB ---
        InfobaseReference ibRef =
            InfobaseReferences.newFileInfobaseReference(infobaseDir.toAbsolutePath().toString());
        ibRef.setName(infobaseName);
        ibRef.setUuid(java.util.UUID.randomUUID());

        // --- 6. Defaults for the standalone-server-specific arguments ---
        final int clusterPort = port; // default DEFAULT_STANDALONE_SERVER_PORT, applied in execute()
        // Confirmed live: the given infobase directory is reused as the server working/registry dir.
        final String clusterRegistryDirectory = infobaseDir.toAbsolutePath().toString();
        // Confirmed live: the publication path defaults to a sanitized infobase name (alnum), else the
        // project name. Pass an explicit publicationName to disambiguate multiple unnamed servers in one project.
        final String publicationPath = effectivePublicationPath(publicationName, infobaseName, projectName);

        // --- 7. Run the one-shot create in a bounded background Job (ibcmd shell-out) ---
        final IStandaloneServerService finalService = serverService;
        final InfobaseReference finalIbRef = ibRef;
        final String jobInfobaseName = infobaseName;
        final AtomicReference<Pair<?, StandaloneServerInfobase>> jobResult = new AtomicReference<>();
        final AtomicReference<Exception> jobError = new AtomicReference<>();

        Job createJob = new Job("Create standalone server: " + jobInfobaseName) //$NON-NLS-1$
        {
            @Override
            protected org.eclipse.core.runtime.IStatus run(
                    org.eclipse.core.runtime.IProgressMonitor monitor)
            {
                try
                {
                    Pair<?, StandaloneServerInfobase> pair = finalService.createServerWithInfobase(
                        versionMask, projectName, finalIbRef, clusterPort, clusterRegistryDirectory,
                        publicationPath, monitor);
                    jobResult.set(pair);
                }
                catch (Exception e)
                {
                    jobError.set(e);
                }
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }
        };
        createJob.setUser(false);
        createJob.setSystem(true);
        createJob.schedule();

        try
        {
            boolean finished = createJob.join(
                TimeUnit.SECONDS.toMillis(CREATE_TIMEOUT_SECONDS), null);
            if (!finished)
            {
                createJob.cancel();
                return ToolResult.error("Standalone-server creation timed out after " //$NON-NLS-1$
                    + CREATE_TIMEOUT_SECONDS + " seconds. The ibcmd process may still be running. " //$NON-NLS-1$
                    + "Check the EDT log and the directory '" + infobaseDir //$NON-NLS-1$
                    + "' for partial results.").toJson(); //$NON-NLS-1$
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Standalone-server creation was interrupted.").toJson(); //$NON-NLS-1$
        }

        if (jobError.get() != null)
        {
            Exception ex = jobError.get();
            Activator.logError("create_infobase: standalone-server creation failed for " //$NON-NLS-1$
                + infobaseDir, ex);
            return ToolResult.error("Standalone-server creation failed: " + ex.getMessage() //$NON-NLS-1$
                + ". Verify that a compatible 1C standalone-server runtime (platform >= 8.3.23) is " //$NON-NLS-1$
                + "registered and that the directory '" + infobaseDir + "' is accessible.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        Pair<?, StandaloneServerInfobase> pair = jobResult.get();
        if (pair == null || pair.getSecond() == null)
        {
            return ToolResult.error("Standalone-server creation returned no infobase handle.").toJson(); //$NON-NLS-1$
        }

        Activator.logInfo("create_infobase: standalone server created at " + infobaseDir); //$NON-NLS-1$

        // --- 8. Resolve the web URL (best-effort) ---
        String webUrl = null;
        try
        {
            URI url = finalService.getInfobaseUrl(pair.getSecond());
            if (url != null)
            {
                webUrl = url.toString();
            }
        }
        catch (Exception e)
        {
            // Non-fatal: the server was created; only the URL lookup failed.
            Activator.logError("create_infobase: could not resolve standalone-server web URL", e); //$NON-NLS-1$
        }

        // --- 9. Read back applications and return ---
        return buildStandaloneServerResult(projectName, infobaseDir, infobaseName, clusterPort,
            webUrl, appManager, project);
    }

    /**
     * Acquires the {@link IStandaloneServerService} from the OSGi service registry (it is published
     * via the {@code com._1c.g5.wiring.serviceProvider} wiring of the standalone-server WST bundle).
     * Returns {@code null} if the bundle or service is unavailable so the caller can fail gracefully.
     *
     * @return the service, or {@code null} when unavailable
     */
    private static IStandaloneServerService acquireStandaloneServerService()
    {
        try
        {
            Bundle bundle = Platform.getBundle(STANDALONE_SERVER_WST_CORE_BUNDLE_ID);
            if (bundle == null)
            {
                Activator.logError("create_infobase: bundle '" //$NON-NLS-1$
                    + STANDALONE_SERVER_WST_CORE_BUNDLE_ID
                    + "' not found — the EDT standalone-server feature is not installed", null); //$NON-NLS-1$
                return null;
            }
            BundleContext context = bundle.getBundleContext();
            if (context == null)
            {
                // The bundle is not active yet — start it transiently so its services register.
                try
                {
                    bundle.start(Bundle.START_TRANSIENT);
                    context = bundle.getBundleContext();
                }
                catch (Exception startEx)
                {
                    Activator.logError("create_infobase: could not start standalone-server bundle", //$NON-NLS-1$
                        startEx);
                }
            }
            if (context == null)
            {
                return null;
            }
            ServiceReference<IStandaloneServerService> ref =
                context.getServiceReference(IStandaloneServerService.class);
            return ref != null ? context.getService(ref) : null;
        }
        catch (Exception e)
        {
            Activator.logError("create_infobase: could not acquire the standalone-server service", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Computes the publication path for a standalone server: the explicit {@code publicationName}
     * if given, otherwise a sanitized (alphanumeric) infobase name, falling back to the project name.
     *
     * @param publicationName the user-provided publication name (may be {@code null}/empty)
     * @param infobaseName the infobase display name
     * @param projectName the project name (fallback)
     * @return the publication path
     */
    private static String effectivePublicationPath(String publicationName, String infobaseName,
            String projectName)
    {
        if (publicationName != null && !publicationName.isEmpty())
        {
            return publicationName;
        }
        // Sanitize the infobase name to an alnum web path; fall back to the project name.
        String sanitized = infobaseName != null ? infobaseName.replaceAll("[^A-Za-z0-9]", "") : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!sanitized.isEmpty())
        {
            return sanitized;
        }
        String fromProject = projectName != null ? projectName.replaceAll("[^A-Za-z0-9]", "") : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return fromProject.isEmpty() ? "infobase" : fromProject; //$NON-NLS-1$
    }

    /**
     * Reads back the applications for the project, finds the new {@code wst-server} application, and
     * builds the success JSON for the standalone-server path. Uses the same short bounded re-poll as
     * the file path to absorb the provision-delegate listener race.
     */
    private static String buildStandaloneServerResult(String projectName, Path infobaseDir,
            String infobaseName, int port, String webUrl, IApplicationManager appManager,
            IProject project)
    {
        JsonArray appsArray = new JsonArray();
        String newAppId = null;

        for (int poll = 0; poll < READ_BACK_MAX_POLLS; poll++)
        {
            appsArray = new JsonArray();
            newAppId = null;

            try
            {
                List<IApplication> applications = appManager.getApplications(project);
                if (applications != null)
                {
                    for (IApplication app : applications)
                    {
                        JsonObject appObj = new JsonObject();
                        appObj.addProperty("id", app.getId()); //$NON-NLS-1$
                        appObj.addProperty("name", app.getName()); //$NON-NLS-1$
                        String typeId = app.getType() != null ? app.getType().getId() : null;
                        if (typeId != null)
                        {
                            appObj.addProperty("type", typeId); //$NON-NLS-1$
                        }
                        try
                        {
                            ApplicationUpdateState updateState = appManager.getUpdateState(app);
                            if (updateState != null)
                            {
                                appObj.addProperty("updateState", updateState.name()); //$NON-NLS-1$
                            }
                        }
                        catch (ApplicationException e)
                        {
                            appObj.addProperty("updateState", "UNKNOWN"); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        // The new standalone server surfaces as a wst-server application.
                        if (newAppId == null && WST_SERVER_APP_TYPE.equals(typeId))
                        {
                            newAppId = app.getId();
                        }
                        appsArray.add(appObj);
                    }
                }
            }
            catch (ApplicationException e)
            {
                Activator.logError("create_infobase: error reading back applications", e); //$NON-NLS-1$
                break;
            }

            if (newAppId != null)
            {
                break;
            }

            if (poll < READ_BACK_MAX_POLLS - 1)
            {
                try
                {
                    Thread.sleep(READ_BACK_POLL_DELAY_MS);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        ToolResult result = ToolResult.success()
            .put("action", "created") //$NON-NLS-1$ //$NON-NLS-2$
            .put("applicationKind", KIND_STANDALONE_SERVER) //$NON-NLS-1$
            .put("project", projectName) //$NON-NLS-1$
            .put("infobaseFile", infobaseDir.toAbsolutePath().toString()) //$NON-NLS-1$
            .put("infobaseName", infobaseName) //$NON-NLS-1$
            .put("port", port) //$NON-NLS-1$
            .put("applications", appsArray); //$NON-NLS-1$

        if (webUrl != null)
        {
            result.put("webUrl", webUrl); //$NON-NLS-1$
        }
        if (newAppId != null)
        {
            result.put("applicationId", newAppId); //$NON-NLS-1$
        }

        String message = "Standalone server for infobase '" + infobaseName //$NON-NLS-1$
            + "' created at '" + infobaseDir.toAbsolutePath() //$NON-NLS-1$
            + "' and bound to project '" + projectName + "' (port " + port + ")." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (webUrl != null ? " Web URL for HTTP testing: " + webUrl + "." : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " Use update_database to push the configuration into the infobase."; //$NON-NLS-1$
        result.put("message", message); //$NON-NLS-1$

        return result.toJson();
    }

    /**
     * Attempts to resolve an {@link IInfobaseCreationOperation} instance from the
     * ps-core Guice injector via reflection.
     *
     * <p>This is the standard pattern for non-OSGi-service Guice prototype operations
     * (mirrors {@code EdtServices.getModelObjectFactory()} which does the same for the
     * MD language injector). Returns {@code null} if the platform-services plugin is
     * not loaded, if the injector is not available, or if the class is not bound — so
     * the caller can treat {@code null} as "platform not ready" and return an actionable
     * error without crashing.
     *
     * @return operation instance, or {@code null} when unavailable
     */
    private static IInfobaseCreationOperation resolveCreationOperation()
    {
        try
        {
            // PlatformServicesCore is an INTERNAL class of the platform-services.core bundle;
            // it is not exported, so Class.forName via OUR bundle classloader cannot see it.
            // Load it through the OWNING bundle's classloader instead (the same pattern
            // EdtServices uses for the form bundle's internal service class).
            Bundle psCoreBundle = Platform.getBundle(PLATFORM_SERVICES_CORE_BUNDLE_ID);
            if (psCoreBundle == null)
            {
                Activator.logError("create_infobase: bundle '" + PLATFORM_SERVICES_CORE_BUNDLE_ID //$NON-NLS-1$
                    + "' not found — the EDT platform-services plugin is not installed", null); //$NON-NLS-1$
                return null;
            }
            // Touching a class trips the bundle's lazy activation so getDefault() is populated.
            Class<?> coreClass = psCoreBundle.loadClass(PLATFORM_SERVICES_CORE_CLASS);
            java.lang.reflect.Method getDefault = coreClass.getDeclaredMethod("getDefault"); //$NON-NLS-1$
            getDefault.setAccessible(true);
            Object coreInstance = getDefault.invoke(null);
            if (coreInstance == null)
            {
                // Bundle not active yet — start it transiently and retry once.
                try
                {
                    psCoreBundle.start(Bundle.START_TRANSIENT);
                }
                catch (Exception startEx)
                {
                    Activator.logError("create_infobase: could not start platform-services.core bundle", //$NON-NLS-1$
                        startEx);
                }
                coreInstance = getDefault.invoke(null);
                if (coreInstance == null)
                {
                    return null;
                }
            }
            java.lang.reflect.Method getInjector =
                coreClass.getDeclaredMethod("getInjector"); //$NON-NLS-1$
            getInjector.setAccessible(true);
            Object injector = getInjector.invoke(coreInstance);
            if (injector == null)
            {
                return null;
            }
            com.google.inject.Injector guiceInjector = (com.google.inject.Injector) injector;
            return guiceInjector.getInstance(IInfobaseCreationOperation.class);
        }
        catch (Exception e)
        {
            Activator.logError(
                "create_infobase: platform probe failed — could not resolve the infobase " //$NON-NLS-1$
                    + "creation operation (a 1C platform may not be registered in EDT)", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Finds the newly created infobase application by matching the infobase reference.
     * Best-effort: returns {@code null} if not found.
     */
    private static IApplication findNewApplication(IApplicationManager appManager,
            IProject project, InfobaseReference ibRef)
    {
        try
        {
            Optional<IApplication> found =
                appManager.findApplicationByInfobaseAndProject(ibRef, project);
            return found.orElse(null);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Maximum re-poll attempts for the provision-delegate listener race after associate(). */
    private static final int READ_BACK_MAX_POLLS = 5;

    /** Delay between read-back re-poll attempts (ms). */
    private static final long READ_BACK_POLL_DELAY_MS = 300;

    /**
     * Reads back the applications for the project and builds the success JSON.
     * Uses a short bounded re-poll to handle the provision-delegate listener race
     * that can cause the new application to not yet appear immediately after associate().
     *
     * @param setDefaultNote optional note appended to the message when setDefault could not be
     *        completed (null = no note)
     */
    private static String buildSuccessResult(String projectName, Path infobaseDir,
            String infobaseName, IApplicationManager appManager,
            IProject project, InfobaseReference ibRef, String setDefaultNote, boolean register)
    {
        JsonArray appsArray = new JsonArray();
        String newAppId = null;

        // Short bounded re-poll: the provision-delegate listener fires asynchronously after
        // associate(), so the new IInfobaseApplication may not be visible on the first read.
        for (int poll = 0; poll < READ_BACK_MAX_POLLS; poll++)
        {
            appsArray = new JsonArray();
            newAppId = null;

            try
            {
                List<IApplication> applications = appManager.getApplications(project);
                if (applications != null)
                {
                    for (IApplication app : applications)
                    {
                        JsonObject appObj = new JsonObject();
                        appObj.addProperty("id", app.getId()); //$NON-NLS-1$
                        appObj.addProperty("name", app.getName()); //$NON-NLS-1$
                        if (app.getType() != null)
                        {
                            appObj.addProperty("type", app.getType().getId()); //$NON-NLS-1$
                        }
                        try
                        {
                            ApplicationUpdateState updateState = appManager.getUpdateState(app);
                            if (updateState != null)
                            {
                                appObj.addProperty("updateState", updateState.name()); //$NON-NLS-1$
                            }
                        }
                        catch (ApplicationException e)
                        {
                            appObj.addProperty("updateState", "UNKNOWN"); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        // Identify the newly created application by matching the infobase reference.
                        if (newAppId == null
                            && app instanceof IInfobaseApplication
                            && INFOBASE_APP_TYPE.equals(
                                app.getType() != null ? app.getType().getId() : null))
                        {
                            IInfobaseApplication ibApp = (IInfobaseApplication) app;
                            if (ibApp.getInfobase() != null
                                && matchesRef(ibApp.getInfobase(), ibRef))
                            {
                                newAppId = app.getId();
                            }
                        }
                        appsArray.add(appObj);
                    }
                }
            }
            catch (ApplicationException e)
            {
                Activator.logError("create_infobase: error reading back applications", e); //$NON-NLS-1$
                break;
            }

            if (newAppId != null)
            {
                break; // Found the new application — no need to re-poll.
            }

            if (poll < READ_BACK_MAX_POLLS - 1)
            {
                try
                {
                    Thread.sleep(READ_BACK_POLL_DELAY_MS);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String verb = register ? "registered" : "created"; //$NON-NLS-1$ //$NON-NLS-2$
        ToolResult result = ToolResult.success()
            .put("action", verb) //$NON-NLS-1$
            .put("project", projectName) //$NON-NLS-1$
            .put("infobaseFile", infobaseDir.toAbsolutePath().toString()) //$NON-NLS-1$
            .put("infobaseName", infobaseName) //$NON-NLS-1$
            .put("applications", appsArray); //$NON-NLS-1$

        if (newAppId != null)
        {
            result.put("applicationId", newAppId); //$NON-NLS-1$
        }

        String message = "Infobase '" + infobaseName //$NON-NLS-1$
            + "' " + verb + " at '" + infobaseDir.toAbsolutePath() //$NON-NLS-1$ //$NON-NLS-2$
            + "' and bound to project '" + projectName //$NON-NLS-1$
            + "'. Use update_database to push the configuration into the infobase." //$NON-NLS-1$
            + (setDefaultNote != null ? setDefaultNote : ""); //$NON-NLS-1$
        result.put("message", message); //$NON-NLS-1$

        return result.toJson();
    }

    /**
     * Checks whether two infobase references point to the same FILE infobase by
     * comparing their connection-string file path. Best-effort: returns false on any
     * failure so a match-miss only skips the applicationId echo.
     */
    private static boolean matchesRef(InfobaseReference a, InfobaseReference b)
    {
        try
        {
            if (a.getConnectionString() == null || b.getConnectionString() == null)
            {
                return false;
            }
            String ca = a.getConnectionString().asConnectionString();
            String cb = b.getConnectionString().asConnectionString();
            return ca != null && ca.equalsIgnoreCase(cb);
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
