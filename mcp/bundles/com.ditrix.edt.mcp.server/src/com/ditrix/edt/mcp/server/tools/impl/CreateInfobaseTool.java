/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
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
import org.eclipse.core.runtime.IProgressMonitor;
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
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
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


    /** {@code applicationKind} value for the standalone-server path (autonomous server). */
    private static final String KIND_STANDALONE_SERVER = "standaloneServer"; //$NON-NLS-1$

    /** {@code applicationKind} value for the default file-infobase path. */
    private static final String KIND_INFOBASE = "infobase"; //$NON-NLS-1$

    /** Input/output key: the kind of application to create (file infobase vs standalone server). */
    private static final String KEY_APPLICATION_KIND = "applicationKind"; //$NON-NLS-1$

    /** Output key: applications bound to the project after creation. */
    private static final String KEY_APPLICATIONS = "applications"; //$NON-NLS-1$

    /** Output key: the application update state. */
    private static final String KEY_UPDATE_STATE = "updateState"; //$NON-NLS-1$

    /** Input/output key: absolute path to the infobase directory. */
    private static final String KEY_INFOBASE_FILE = "infobaseFile"; //$NON-NLS-1$

    /** Input/output key: display name of the infobase. */
    private static final String KEY_INFOBASE_NAME = "infobaseName"; //$NON-NLS-1$

    /** Input key: infobase connection user to store as access credentials (#194). */
    private static final String KEY_USER = "user"; //$NON-NLS-1$

    /** Input key: authentication kind (INFOBASE / OS) for the stored credentials (#194). */
    private static final String KEY_ACCESS = "access"; //$NON-NLS-1$

    /**
     * Port HINT passed to {@code createServerWithInfobase} for a standalone server. For a FILE-backed
     * standalone server EDT does NOT honour a requested port: {@code generateDefaultConfig} uses this
     * value only as the START of a free-port search ({@code allocatePort(8314, ...)}), so the ACTUAL
     * web port is auto-allocated and may differ. The real port is read back from the resolved web URL
     * and reported as {@code port}/{@code webUrl} in the result (verified live on EDT 2025.2: a server
     * created with this hint was published on an auto-allocated port).
     */
    private static final int DEFAULT_STANDALONE_SERVER_PORT = 8314;

    /** Symbolic name of the bundle that owns the standalone-server WST service. */
    private static final String STANDALONE_SERVER_WST_CORE_BUNDLE_ID =
        "com.e1c.g5.v8.dt.platform.standaloneserver.wst.core"; //$NON-NLS-1$

    /**
     * FQN of the standalone-server service interface. The standalone-server bundles are resolved
     * REFLECTIVELY (not a MANIFEST Require-Bundle): they pull in a transitive dependency (snakeyaml)
     * that a minimal headless EDT does not ship, so a hard dependency would make THIS plugin fail to
     * resolve there. Reflection keeps the plugin loadable everywhere; the standalone-server path then
     * fails fast with an actionable error when the feature is absent.
     */
    private static final String STANDALONE_SERVER_SERVICE_CLASS =
        "com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService"; //$NON-NLS-1$
    /** FQN of the StandaloneServerInfobase type (the Pair's second element; getInfobaseUrl's argument). */
    private static final String STANDALONE_SERVER_INFOBASE_CLASS =
        "com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.StandaloneServerInfobase"; //$NON-NLS-1$

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
            + "applicationKind='standaloneServer' creates (or, with mode='register', wraps an EXISTING " //$NON-NLS-1$
            + "file infobase with) an autonomous (standalone) server that also exposes a web URL for " //$NON-NLS-1$
            + "HTTP testing (requires a registered 1C standalone-server runtime, " //$NON-NLS-1$
            + "platform >= 8.3.23). FILE type only (server/web rejected). Runs in a background Job " //$NON-NLS-1$
            + "(up to 120 s). Full parameters and examples: call get_tool_guide('create_infobase')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT configuration project to bind the new infobase to (required).", true) //$NON-NLS-1$
            .enumProperty("mode", //$NON-NLS-1$
                "'create' (default) = make a new file infobase at infobaseFile (launches the 1C " //$NON-NLS-1$
                + "platform); 'register' = add an EXISTING infobase already present at infobaseFile " //$NON-NLS-1$
                + "(no platform launch). With applicationKind='standaloneServer', mode='register' " //$NON-NLS-1$
                + "wraps an EXISTING file infobase (a 1Cv8.1CD must be present) with a standalone " //$NON-NLS-1$
                + "server instead of creating a new one.", //$NON-NLS-1$
                "create", "register") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_INFOBASE_FILE,
                "Absolute path to the infobase directory (required). For mode='create' the directory " //$NON-NLS-1$
                + "is created if absent and the 1Cv8.1CD files are written into it; for mode='register' " //$NON-NLS-1$
                + "it must already contain an existing file infobase.", //$NON-NLS-1$
                true)
            .stringProperty(KEY_INFOBASE_NAME,
                "Display name for the new infobase. If omitted, a name is auto-generated by EDT.") //$NON-NLS-1$
            .stringProperty("platform", //$NON-NLS-1$
                "1C platform version mask to use for creation (e.g. '8.3.25'). If omitted, EDT " //$NON-NLS-1$
                + "resolves the best available installed version automatically.") //$NON-NLS-1$
            .booleanProperty("setDefault", //$NON-NLS-1$
                "Set the new infobase as the default application for the project after creation " //$NON-NLS-1$
                + "(default false).") //$NON-NLS-1$
            .enumProperty(KEY_APPLICATION_KIND,
                "'infobase' (default) = a plain file infobase via the configurator; " //$NON-NLS-1$
                + "'standaloneServer' = an autonomous (standalone) server that creates and serves a " //$NON-NLS-1$
                + "new file infobase and exposes a web URL for HTTP testing (requires a registered 1C " //$NON-NLS-1$
                + "standalone-server runtime, platform >= 8.3.23). With mode='register' the server " //$NON-NLS-1$
                + "instead wraps an EXISTING file infobase (a 1Cv8.1CD must be present at infobaseFile) " //$NON-NLS-1$
                + "rather than creating a new one. The web port is auto-allocated by " //$NON-NLS-1$
                + "EDT and reported back as 'port'/'webUrl' in the result.", //$NON-NLS-1$
                KIND_INFOBASE, KIND_STANDALONE_SERVER)
            .stringProperty(KEY_USER,
                "Infobase connection user to store so update_database / debug_launch can authenticate " //$NON-NLS-1$
                + "the update agent (issue #194). Selects an EXISTING user; most useful with " //$NON-NLS-1$
                + "mode='register' (the existing base already has users). Omit to store no credentials.") //$NON-NLS-1$
            .stringProperty("password", //$NON-NLS-1$
                "Password for 'user'. Optional; default empty (demo bases use an empty password).") //$NON-NLS-1$
            .enumProperty(KEY_ACCESS,
                "Authentication kind for the stored credentials: 'INFOBASE' (default, 1C user auth) " //$NON-NLS-1$
                + "or 'OS'. Credentials are stored when ANY of user/password/access is given; " //$NON-NLS-1$
                + "access='OS' on its own stores OS-authentication settings (no 1C user/password). " //$NON-NLS-1$
                + "Applies only to applicationKind='infobase' (a file infobase).", //$NON-NLS-1$
                "INFOBASE", "OS") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION, "'created' (mode=create) or 'registered' (mode=register).") //$NON-NLS-1$
            .stringProperty(KEY_APPLICATION_KIND,
                "'infobase' or 'standaloneServer' — the kind of application created.") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT, "Name of the configuration project.") //$NON-NLS-1$
            .stringProperty(KEY_INFOBASE_FILE, "Path of the created infobase directory.") //$NON-NLS-1$
            .stringProperty(KEY_INFOBASE_NAME, "Display name of the created infobase.") //$NON-NLS-1$
            .stringProperty("webUrl", //$NON-NLS-1$
                "applicationKind='standaloneServer' only: the infobase web URL for HTTP testing. " //$NON-NLS-1$
                + "Best-effort: absent if EDT could not resolve the URL (the server is still created).") //$NON-NLS-1$
            .integerProperty("port", //$NON-NLS-1$
                "applicationKind='standaloneServer' only: the ACTUAL web port the server was " //$NON-NLS-1$
                + "published on (parsed from webUrl; EDT auto-allocates it). Absent if webUrl could " //$NON-NLS-1$
                + "not be resolved.") //$NON-NLS-1$
            .objectArrayProperty(KEY_APPLICATIONS,
                "Applications bound to the project after creation (same shape as get_applications).") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "ID of the newly created application (for chaining into update_database).") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status message.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    /**
     * {@code create_infobase} launches the 1C platform / standalone-server runtime and reads the
     * project's applications back, either of which can open an infobase connection and raise EDT's
     * access-settings dialog — so it opts into the auth-dialog-suppressor scope (issue #270).
     */
    @Override
    public boolean connectsToInfobase()
    {
        return true;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Required parameters
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        String errFile = JsonUtils.requireArgument(params, KEY_INFOBASE_FILE);
        if (errFile != null)
        {
            return errFile;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String infobaseFileStr = JsonUtils.extractStringArgument(params, KEY_INFOBASE_FILE);
        String infobaseName = JsonUtils.extractStringArgument(params, KEY_INFOBASE_NAME);
        String platform = JsonUtils.extractStringArgument(params, "platform"); //$NON-NLS-1$
        boolean setDefault = JsonUtils.extractBooleanArgument(params, "setDefault", false); //$NON-NLS-1$
        String modeStr = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        String applicationKind = JsonUtils.extractStringArgument(params, KEY_APPLICATION_KIND);
        String credUser = JsonUtils.extractStringArgument(params, KEY_USER);
        String credPassword = JsonUtils.extractStringArgument(params, "password"); //$NON-NLS-1$
        String credAccess = JsonUtils.extractStringArgument(params, KEY_ACCESS);
        Credentials credentials = new Credentials(credUser, credPassword, credAccess);

        // Reject an out-of-enum access value before doing any work (the schema enum is advisory).
        String credAccessError = InfobaseAccessSupport.accessError(credAccess);
        if (credAccessError != null)
        {
            return ToolResult.error(credAccessError).toJson();
        }

        // Validate applicationKind (default 'infobase'). When absent or 'infobase' the behaviour is
        // byte-identical to the original file-infobase tool.
        FlagResult kind = parseApplicationKind(applicationKind);
        if (kind.error != null)
        {
            return kind.error;
        }
        boolean standaloneServer = kind.value;

        // Credentials (#194) target a FILE infobase's access settings; the standalone-server path
        // manages its own infobase/auth and would silently drop them. Reject up front (before any
        // workspace/service access) rather than no-op so the caller is not misled.
        if (standaloneServer && credentials.any())
        {
            return ToolResult.error("user/password/access are supported only with " //$NON-NLS-1$
                + "applicationKind='infobase' (a file infobase). A standalone server manages its " //$NON-NLS-1$
                + "own infobase and authentication — omit these parameters.").toJson(); //$NON-NLS-1$
        }

        // Validate mode (default 'create').
        FlagResult mode = parseMode(modeStr);
        if (mode.error != null)
        {
            return mode.error;
        }
        boolean register = mode.value;

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

        // Standalone server + mode='register': the served infobase must ALREADY exist on disk. Validate
        // the 1Cv8.1CD up front — the SAME check the plain register path uses — BEFORE the (workspace-
        // touching) building-state check and before any service lookup or Job, so a wrong path fails
        // fast and cleanly. (The plain register path runs this same check later, inside createInfobase.)
        if (standaloneServer && register)
        {
            String registerError = validateRegisterPath(infobaseDir);
            if (registerError != null)
            {
                return registerError;
            }
        }

        // Refuse only the transient BUILDING state; missing/closed project falls through below.
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        if (standaloneServer)
        {
            // mode='create' creates and serves a NEW infobase; mode='register' wraps an EXISTING file
            // infobase (1Cv8.1CD already on disk) with a standalone server — same registration, minus
            // the database materialization.
            return createStandaloneServer(projectName, infobaseDir, infobaseName, platform,
                setDefault, register);
        }

        return createInfobase(projectName, infobaseDir, infobaseName, platform, setDefault, register,
            credentials);
    }

    /**
     * Validates {@code applicationKind} (default {@code 'infobase'}): {@link FlagResult#value} is
     * {@code true} for {@code 'standaloneServer'}, {@code false} for {@code 'infobase'} / absent, and
     * {@link FlagResult#error} carries a ready JSON error for any other value.
     */
    private static FlagResult parseApplicationKind(String applicationKind)
    {
        if (applicationKind == null || applicationKind.isEmpty() || KIND_INFOBASE.equals(applicationKind))
        {
            return FlagResult.of(false);
        }
        if (KIND_STANDALONE_SERVER.equals(applicationKind))
        {
            return FlagResult.of(true);
        }
        return FlagResult.failed(ToolResult.error("Invalid applicationKind: '" + applicationKind //$NON-NLS-1$
            + "'. Allowed values: '" + KIND_INFOBASE + "', '" + KIND_STANDALONE_SERVER //$NON-NLS-1$ //$NON-NLS-2$
            + "'.").toJson()); //$NON-NLS-1$
    }

    /**
     * Validates {@code mode} (default {@code 'create'}): {@link FlagResult#value} is {@code true} for
     * {@code 'register'}, {@code false} for {@code 'create'} / absent, and {@link FlagResult#error} carries
     * a ready JSON error for any other value.
     */
    private static FlagResult parseMode(String modeStr)
    {
        if (modeStr == null || modeStr.isEmpty() || "create".equals(modeStr)) //$NON-NLS-1$
        {
            return FlagResult.of(false);
        }
        if ("register".equals(modeStr)) //$NON-NLS-1$
        {
            return FlagResult.of(true);
        }
        return FlagResult.failed(ToolResult.error("Invalid mode: '" + modeStr //$NON-NLS-1$
            + "'. Allowed values: 'create', 'register'.").toJson()); //$NON-NLS-1$
    }

    /**
     * A parsed boolean flag, or a ready JSON {@code error} when the input value was out of range. Lets the
     * mode / applicationKind validations early-return out of {@link #execute} without inflating its
     * cognitive complexity.
     */
    private static final class FlagResult
    {
        final boolean value;
        final String error;

        private FlagResult(boolean value, String error)
        {
            this.value = value;
            this.error = error;
        }

        static FlagResult of(boolean value)
        {
            return new FlagResult(value, null);
        }

        static FlagResult failed(String error)
        {
            return new FlagResult(false, error);
        }
    }

    /** The infobase connection credentials (#194); any field may be {@code null}/empty when not supplied. */
    private static final class Credentials
    {
        final String user;
        final String password;
        final String access;

        Credentials(String user, String password, String access)
        {
            this.user = user;
            this.password = password;
            this.access = access;
        }

        /** Whether the caller supplied any connection-credential argument (user / password / access). */
        boolean any()
        {
            return (user != null && !user.isEmpty())
                || (password != null && !password.isEmpty())
                || (access != null && !access.isEmpty());
        }
    }

    private String createInfobase(String projectName, Path infobaseDir,
            String infobaseName, String platform, boolean setDefault, boolean register,
            Credentials credentials)
    {
        // --- 1-2. Resolve project + services ---
        CreateContext context = resolveCreateContext(projectName);
        if (context.error != null)
        {
            return context.error;
        }
        IProject project = context.project;
        IApplicationManager appManager = context.appManager;
        IInfobaseManager ibManager = context.ibManager;
        IInfobaseAssociationManager assocManager = context.assocManager;

        // --- 3. Auto-generate infobase name if omitted ---
        if (infobaseName == null || infobaseName.isEmpty())
        {
            infobaseName = generateDefaultInfobaseName(ibManager, projectName);
        }

        // --- 4. Prepare the directory ---
        String prepareError = prepareInfobaseDirectory(infobaseDir, register);
        if (prepareError != null)
        {
            return prepareError;
        }

        // --- 5. Build the FILE infobase reference ---
        InfobaseReference ibRef = buildInfobaseReference(infobaseDir, infobaseName);

        // --- 6. Create the database (create) or register the existing one (register) ---
        String databaseError = register
            ? registerExistingInfobase(ibManager, ibRef, infobaseDir)
            : createNewInfobase(ibRef, platform, infobaseName, infobaseDir);
        if (databaseError != null)
        {
            return databaseError;
        }

        // --- 7. Associate with the project ---
        String associateError = associateInfobase(assocManager, project, ibRef, infobaseDir, projectName);
        if (associateError != null)
        {
            return associateError;
        }

        // --- 8. Optionally set as default ---
        String setDefaultNote = setDefault ? applySetDefault(appManager, project, ibRef) : null;

        // --- 8.5 Optionally store infobase connection credentials (#194) ---
        String credNote = storeCredentialsIfRequested(ibRef, credentials, register);

        // --- 9. Read back and return ---
        ResultContext rc = new ResultContext(projectName, infobaseDir, infobaseName, appManager, project);
        String note = (setDefaultNote != null ? setDefaultNote : "") //$NON-NLS-1$
            + (credNote != null ? credNote : ""); //$NON-NLS-1$
        return buildSuccessResult(rc, ibRef, note.isEmpty() ? null : note, register);
    }

    /**
     * Stores infobase connection credentials on the freshly-built {@code ibRef} when the caller
     * supplied any of {@code user}/{@code password}/{@code access} (#194), returning a note to append
     * to the result message — a success note, a non-fatal WARNING when the store failed, or
     * {@code null} when no credentials were requested. Credential storage never fails the
     * infobase creation itself (the base is already created and bound).
     *
     * @param ibRef the new infobase reference (the access-settings key)
     * @param credentials the requested connection credentials (any field may be {@code null}/empty)
     * @param register {@code true} for mode='register' (an existing base that already has users),
     *            {@code false} for mode='create' (a brand-new empty base with no users yet)
     * @return a message note, or {@code null} when no credentials were requested
     */
    private static String storeCredentialsIfRequested(InfobaseReference ibRef, Credentials credentials,
            boolean register)
    {
        if (!credentials.any())
        {
            return null;
        }
        String error = InfobaseAccessSupport.storeCredentials(ibRef, credentials.user, credentials.password,
            InfobaseAccessSupport.parseAccess(credentials.access));
        if (error != null)
        {
            return " WARNING: connection credentials were NOT stored: " + error; //$NON-NLS-1$
        }
        // mode='create' makes a brand-new EMPTY infobase with NO users, so credentials for a named
        // user authenticate only once a MATCHING user is added (via the configurator / БСЛ
        // ПользователиИнформационнойБазы). Surface that so the caller is not surprised when a later
        // update prompts for credentials (which the MCP server now auto-cancels).
        String userNote = register
            ? "" //$NON-NLS-1$
            : " NOTE: a newly created infobase has no users yet — these credentials authenticate " //$NON-NLS-1$
                + "only after a matching infobase user is added."; //$NON-NLS-1$
        return " Stored connection credentials for user '" + (credentials.user == null ? "" : credentials.user) //$NON-NLS-1$ //$NON-NLS-2$
            + "' (change them later with set_infobase_credentials)." + userNote; //$NON-NLS-1$
    }

    /**
     * Prepares the infobase directory for the create/register step (step 4). For mode='register' this
     * is the read-only {@link #validateRegisterPath(Path)} check; for mode='create' it creates the
     * target directory ({@code Files.createDirectories}) at the SAME sequence point as before. Returns a
     * ready error tool-result JSON on the SAME early-return cases the inline code produced, else
     * {@code null}.
     */
    private static String prepareInfobaseDirectory(Path infobaseDir, boolean register)
    {
        if (register)
        {
            // mode=register: the infobase must already exist on disk; do NOT create it.
            return validateRegisterPath(infobaseDir);
        }
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
        return null;
    }

    /**
     * Registers an EXISTING infobase with EDT (mode='register', step 6): adds the reference to EDT
     * directly via {@code IInfobaseManager.add} — a fast, synchronous EMF registration, no 1cv8 launch.
     * The {@code add} side effect stays inline here at the SAME sequence point. Returns a ready error
     * tool-result JSON on the SAME failure case the inline code produced, else {@code null}.
     */
    private static String registerExistingInfobase(IInfobaseManager ibManager, InfobaseReference ibRef,
            Path infobaseDir)
    {
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
        return null;
    }

    /**
     * Creates a brand-new database via the platform creation operation (mode='create', step 6). Probes
     * for a registered platform runtime first (fail fast, no hang), then runs {@code perform()} in a
     * bounded background Job (never on the UI thread) — the {@code perform} side effect and the Job
     * schedule stay inline here at the SAME sequence point. Returns a ready error tool-result JSON on
     * the SAME early-return cases (no runtime / timeout / interrupted / job error) the inline code
     * produced, else {@code null}.
     */
    private static String createNewInfobase(InfobaseReference ibRef, String platform,
            String infobaseName, Path infobaseDir)
    {
        IInfobaseCreationOperation creationOp = resolveCreationOperation();
        if (creationOp == null)
        {
            return ToolResult.error("No 1C platform runtime is registered in EDT - cannot " //$NON-NLS-1$
                + "create a new infobase. Register a 1C:Enterprise platform installation in EDT " //$NON-NLS-1$
                + "(Window -> Preferences -> 1C:Enterprise -> Installed Installations) and retry, " //$NON-NLS-1$
                + "or use mode='register' for an existing infobase.").toJson(); //$NON-NLS-1$
        }

        final IInfobaseCreationOperation.Descriptor descriptor =
            buildCreationDescriptor(ibRef, platform);

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

        String waitError = awaitCreateJob(createJob, infobaseDir);
        if (waitError != null)
        {
            return waitError;
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
        return null;
    }

    /**
     * Joins the create Job with the bounded {@link #CREATE_TIMEOUT_SECONDS} timeout and maps the
     * outcome to the SAME error tool-result JSON the inline code produced: a timeout message (Job
     * cancelled) when it did not finish, or an interrupted message (interrupt flag restored) when the
     * join threw. Returns {@code null} when the Job finished within the budget. The {@code InterruptedException}
     * from {@code join} is handled inline here (not propagated) exactly as before.
     */
    private static String awaitCreateJob(Job createJob, Path infobaseDir)
    {
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
        return null;
    }

    /**
     * Associates the infobase with the project (step 7): the {@code associate} side effect stays inline
     * here at the SAME sequence point. Returns a ready error tool-result JSON on the SAME failure case
     * the inline code produced, else {@code null}.
     */
    private static String associateInfobase(IInfobaseAssociationManager assocManager, IProject project,
            InfobaseReference ibRef, Path infobaseDir, String projectName)
    {
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
        return null;
    }

    /**
     * Optionally sets the new infobase as the project's default application (step 8): the
     * {@code setDefaultApplication} side effect stays inline here at the SAME sequence point. Non-fatal —
     * returns the SAME nullable {@code setDefaultNote} the inline code produced ({@code null} when the
     * default was set or the failure was swallowed; a note when the new app was not found yet).
     */
    private static String applySetDefault(IApplicationManager appManager, IProject project,
            InfobaseReference ibRef)
    {
        try
        {
            IApplication newApp = findNewApplication(appManager, project, ibRef);
            if (newApp == null)
            {
                Activator.logError("create_infobase: setDefault skipped — new app not found yet", //$NON-NLS-1$
                    null);
                return "; the new infobase was created but could not be set as " //$NON-NLS-1$
                    + "default yet - set it manually or retry"; //$NON-NLS-1$
            }
            appManager.setDefaultApplication(project, newApp);
        }
        catch (Exception e)
        {
            // Non-fatal: the infobase was created and associated; only the default-setting failed.
            Activator.logError("create_infobase: setDefault failed", e); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Resolves the configuration project and the platform services needed for a file-infobase
     * creation (read-only). Returns a {@link CreateContext} whose {@code error} field carries the
     * tool-result JSON for the SAME early-return cases the inline code produced (project missing /
     * closed; {@code IApplicationManager} / {@code IInfobaseManager} / {@code IInfobaseAssociationManager}
     * unavailable); otherwise {@code error} is {@code null} and the service fields are populated.
     */
    private static CreateContext resolveCreateContext(String projectName)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return CreateContext.failed(
                ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson());
        }
        if (!ctx.isOpen())
        {
            return CreateContext.failed(ToolResult.error("Project is closed: " + projectName //$NON-NLS-1$
                + ". Open the project in EDT first.").toJson()); //$NON-NLS-1$
        }

        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return CreateContext.failed(
                ToolResult.error("IApplicationManager service is not available").toJson()); //$NON-NLS-1$
        }

        IInfobaseManager ibManager = Activator.getDefault().getInfobaseManager();
        if (ibManager == null)
        {
            return CreateContext.failed(ToolResult.error("IInfobaseManager service is not available. " //$NON-NLS-1$
                + "Ensure EDT platform-services are running.").toJson()); //$NON-NLS-1$
        }

        IInfobaseAssociationManager assocManager =
            Activator.getDefault().getInfobaseAssociationManager();
        if (assocManager == null)
        {
            return CreateContext.failed(ToolResult.error(
                "IInfobaseAssociationManager service is not available. " //$NON-NLS-1$
                + "Ensure EDT platform-services are running.").toJson()); //$NON-NLS-1$
        }

        return CreateContext.of(ctx.project(), appManager, ibManager, assocManager);
    }

    /**
     * Auto-generates a display name for a new infobase when none was supplied: EDT's
     * {@code IInfobaseManager.generateInfobaseName()}, falling back to {@code <projectName>_infobase}
     * on any failure. Read-only — name generation has no side effect on the infobase.
     */
    private static String generateDefaultInfobaseName(IInfobaseManager ibManager, String projectName)
    {
        try
        {
            return ibManager.generateInfobaseName();
        }
        catch (Exception e)
        {
            return projectName + "_infobase"; //$NON-NLS-1$
        }
    }

    /**
     * Validates (read-only) that {@code infobaseDir} already contains a file infobase, for
     * mode='register'. Returns the SAME error tool-result JSON the inline check produced when no
     * {@code 1Cv8.1CD} is present, or {@code null} when the path is a valid existing file infobase.
     */
    private static String validateRegisterPath(Path infobaseDir)
    {
        if (!Files.isDirectory(infobaseDir)
            || !Files.isRegularFile(infobaseDir.resolve("1Cv8.1CD"))) //$NON-NLS-1$
        {
            return ToolResult.error("No file infobase found at '" + infobaseDir //$NON-NLS-1$
                + "' (expected a 1Cv8.1CD file). For mode='register' the path must point to an " //$NON-NLS-1$
                + "existing file infobase; use mode='create' to make a new one.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Builds the FILE {@link InfobaseReference} for the new infobase (read-only construction). The
     * reference MUST carry a UUID before {@code perform()}: the creation operation locks the infobase
     * by its UUID very early ({@code LockManager.getLock}), which NPEs on a null id.
     */
    private static InfobaseReference buildInfobaseReference(Path infobaseDir, String infobaseName)
    {
        InfobaseReference ibRef =
            InfobaseReferences.newFileInfobaseReference(infobaseDir.toAbsolutePath().toString());
        ibRef.setName(infobaseName);
        ibRef.setUuid(java.util.UUID.randomUUID());
        return ibRef;
    }

    /**
     * Builds the {@link IInfobaseCreationOperation.Descriptor} for the create Job (read-only
     * construction — no platform call is made here). The optional platform mask is applied only when
     * non-empty, identical to the inline builder.
     */
    private static IInfobaseCreationOperation.Descriptor buildCreationDescriptor(InfobaseReference ibRef,
            String platform)
    {
        IInfobaseCreationOperation.Builder builder = new IInfobaseCreationOperation.Builder()
            .infobaseReference(ibRef)
            .createNew(true)
            .addReference(true)
            .arguments(ModelFactory.eINSTANCE.createCreateInfobaseArguments());
        if (platform != null && !platform.isEmpty())
        {
            builder.platform(platform);
        }
        return builder.build();
    }

    /**
     * Holder for the project + platform services resolved by {@link #resolveCreateContext(String)}.
     * When {@code error} is non-null it carries a ready tool-result JSON and the other fields are unset;
     * otherwise {@code error} is {@code null} and the fields are populated.
     */
    private static final class CreateContext
    {
        final String error;
        final IProject project;
        final IApplicationManager appManager;
        final IInfobaseManager ibManager;
        final IInfobaseAssociationManager assocManager;

        private CreateContext(String error, IProject project, IApplicationManager appManager,
                IInfobaseManager ibManager, IInfobaseAssociationManager assocManager)
        {
            this.error = error;
            this.project = project;
            this.appManager = appManager;
            this.ibManager = ibManager;
            this.assocManager = assocManager;
        }

        static CreateContext failed(String error)
        {
            return new CreateContext(error, null, null, null, null);
        }

        static CreateContext of(IProject project, IApplicationManager appManager,
                IInfobaseManager ibManager, IInfobaseAssociationManager assocManager)
        {
            return new CreateContext(null, project, appManager, ibManager, assocManager);
        }
    }

    /**
     * Creates an autonomous (standalone) server that creates and serves a new file infobase, binds
     * it to the project, and exposes a web URL for HTTP testing.
     *
     * <p>This is a fully separate path from {@link #createInfobase}: instead of the configurator
     * ({@code 1cv8}) it goes through the EDT WST standalone-server layer
     * ({@code IStandaloneServerService.createServerWithInfobase}, resolved reflectively), which shells out to {@code ibcmd}
     * to create the infobase and registers a WST {@code IServer}. The application framework then
     * surfaces an {@code IServerApplication} of type {@link StandaloneServerSupport#WST_SERVER_APP_TYPE} automatically via
     * the same {@code IApplicationManager.getApplications(project)} read-back we already use.
     *
     * <p><strong>Unattended-safety:</strong> the runtime probe ({@code findRuntime}) fires BEFORE
     * the Job so "no runtime" fails instantly; the {@code ibcmd} shell-out runs entirely inside a
     * bounded background Job — never on the UI thread, no modal.
     *
     * <p>With {@code register == true} the served file infobase already exists on disk: the WST
     * server registration is performed exactly as for the create path but the database materialization
     * ({@link #ssMaterializeInfobase}) is SKIPPED (a best-effort {@code setExist(true)} marks the module
     * as existing instead). The presence of a {@code 1Cv8.1CD} at {@code infobaseDir} is validated by
     * {@link #execute(Map)} up front (fail fast, before this method) using the same check the plain
     * register path uses.
     *
     * @param projectName the configuration project to bind the new server to
     * @param infobaseDir the infobase / server working directory
     * @param infobaseName the display name (auto-generated from the directory if absent)
     * @param platform the platform version mask (may be {@code null}/empty = any)
     * @param setDefault set the new server as the project's default application after creation
     * @param register {@code true} to wrap an EXISTING file infobase (mode='register'); {@code false} to
     *            create and serve a new one (mode='create')
     * @return the tool result JSON
     */
    private String createStandaloneServer(String projectName, Path infobaseDir,
            String infobaseName, String platform, boolean setDefault, boolean register)
    {
        // --- 1-2. Resolve project + services --- (register-path validation ran in execute() already)
        StandaloneContext sctx = resolveStandaloneContext(projectName);
        if (sctx.error != null)
        {
            return sctx.error;
        }
        IProject project = sctx.project;
        IApplicationManager appManager = sctx.appManager;
        Object serverService = sctx.serverService;

        // --- 3. Fail-fast runtime probe (BEFORE the Job, so "no runtime" fails instantly) ---
        final String versionMask = orEmpty(platform);
        boolean hasRuntime = ssHasRuntime(serverService, versionMask);
        if (!hasRuntime)
        {
            return ToolResult.error(noRuntimeError(platform)).toJson();
        }

        // --- 4. Auto-generate the infobase name from the directory if omitted ---
        infobaseName = effectiveStandaloneInfobaseName(infobaseName, infobaseDir, projectName);

        // --- 5. Build the FILE infobase reference for the new IB ---
        InfobaseReference ibRef =
            InfobaseReferences.newFileInfobaseReference(infobaseDir.toAbsolutePath().toString());
        ibRef.setName(infobaseName);
        ibRef.setUuid(java.util.UUID.randomUUID());

        // --- 6. Defaults for the standalone-server-specific arguments ---
        // EDT does NOT honour a requested port for a FILE-backed standalone server (the FILE branch of
        // generateConfig ignores it; the port is auto-allocated from this hint). We therefore pass a
        // fixed hint and report the ACTUAL port read back from the web URL — see DEFAULT_STANDALONE_SERVER_PORT.
        final int clusterPort = DEFAULT_STANDALONE_SERVER_PORT;
        // Confirmed live: the given infobase directory is reused as the server working/registry dir.
        final String clusterRegistryDirectory = infobaseDir.toAbsolutePath().toString();
        // A non-empty publication path is required by the 7-arg API; for a FILE infobase EDT ignores it
        // (the publication base is hard-coded to "/"), so we derive a sane sanitized value internally.
        final String publicationPath = effectivePublicationPath(infobaseName, projectName);

        // --- 7. Run the one-shot create in a bounded background Job (ibcmd shell-out) ---
        final Object finalService = serverService;
        final ServerCreateArgs createArgs = new ServerCreateArgs(versionMask, projectName, ibRef,
            clusterPort, clusterRegistryDirectory, publicationPath);
        final String jobInfobaseName = infobaseName;
        final AtomicReference<Object> jobResult = new AtomicReference<>();
        final AtomicReference<Exception> jobError = new AtomicReference<>();

        Job createJob = new Job("Create standalone server: " + jobInfobaseName) //$NON-NLS-1$
        {
            @Override
            protected org.eclipse.core.runtime.IStatus run(
                    org.eclipse.core.runtime.IProgressMonitor monitor)
            {
                try
                {
                    Object pair = ssCreateServerWithInfobase(finalService, createArgs, monitor);
                    // createServerWithInfobase registers the server with the module's create flag = false,
                    // so the served file infobase (1Cv8.1CD) is never physically written.
                    if (pair instanceof Pair)
                    {
                        if (register)
                        {
                            // mode='register': the 1Cv8.1CD already exists on disk (validated up front),
                            // so do NOT materialize a new DB — that would fail / overwrite. Best-effort
                            // mark the module as existing (mirrors the EDT wizard's existing-infobase
                            // branch; a no-op if the API lacks setExist). Never call setCreate(true).
                            markInfobaseExisting(((Pair<?, ?>)pair).getSecond());
                        }
                        else
                        {
                            // mode='create': the server would otherwise fail to start ("Информационная база
                            // не обнаружена"), so materialize the DB now (same step the EDT wizard performs).
                            ssMaterializeInfobase(finalService, ((Pair<?, ?>)pair).getFirst(),
                                ((Pair<?, ?>)pair).getSecond(), monitor);
                        }
                    }
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

        String waitError = awaitStandaloneJob(createJob, infobaseDir, register);
        if (waitError != null)
        {
            return waitError;
        }

        if (jobError.get() != null)
        {
            Exception ex = jobError.get();
            Activator.logError("create_infobase: standalone-server creation failed for " //$NON-NLS-1$
                + infobaseDir, ex);
            return ToolResult.error(standaloneJobErrorMessage(ex, infobaseDir, register)).toJson();
        }

        Pair<?, ?> pair = asPair(jobResult.get());
        if (!hasInfobaseHandle(pair))
        {
            return ToolResult.error("Standalone-server creation returned no infobase handle.").toJson(); //$NON-NLS-1$
        }

        Activator.logInfo("create_infobase: standalone server created at " + infobaseDir); //$NON-NLS-1$

        // Use the name EDT actually assigned to the StandaloneServerInfobase (read back from the create
        // result), NOT the requested name, for the application read-back match and the reported
        // infobaseName. On EDT 2025.2 the name is set verbatim — there is no de-duplication
        // (suggestNewApplicationName does not exist in the 2025.2 platform jars), so this equals the
        // requested name; reading the actual value future-proofs the read-back / applicationId / reported
        // name against any platform that de-duplicates a colliding name. Falls back to the requested name.
        String actualName = ssGetInfobaseName(pair.getSecond());
        String effectiveName = firstNonEmpty(actualName, infobaseName);

        // --- 8. Resolve the web URL (best-effort; ssGetInfobaseUrl returns null on any failure) ---
        // The ACTUAL port is read back from the resolved URL (EDT auto-allocates it), not the hint we
        // passed in. When the URL cannot be resolved we report no port rather than echoing a fiction.
        URI url = ssGetInfobaseUrl(finalService, pair.getSecond());
        String webUrl = urlToString(url);
        int actualPort = urlToPort(url);

        // --- 9. Read back applications and return ---
        ResultContext rc = new ResultContext(projectName, infobaseDir, effectiveName, appManager, project);
        return buildStandaloneServerResult(rc, actualPort, webUrl, setDefault, register);
    }

    /**
     * Best-effort marks a just-registered {@code StandaloneServerInfobase} module as ALREADY existing
     * for mode='register' — {@code setExist(true)} (mirrors the EDT wizard's existing-infobase branch).
     * Never called with {@code setCreate(true)}: the served {@code 1Cv8.1CD} already exists on disk, so
     * no database is materialized. Any reflective failure is logged and swallowed — the WST server
     * registration already succeeded and must not be failed by a best-effort flag.
     *
     * @param standaloneServerInfobase the module returned by {@link #ssCreateServerWithInfobase}
     */
    private static void markInfobaseExisting(Object standaloneServerInfobase)
    {
        if (standaloneServerInfobase == null)
        {
            return;
        }
        try
        {
            ssInvoke(standaloneServerInfobase, "setExist", 1, Boolean.TRUE); //$NON-NLS-1$
        }
        catch (Exception e) // NOSONAR best-effort: the server is registered; the flag is non-fatal
        {
            Activator.logError("create_infobase: best-effort setExist(true) failed for the " //$NON-NLS-1$
                + "registered standalone server (non-fatal — the server is registered)", e); //$NON-NLS-1$
        }
    }

    /**
     * Builds the "standalone-server creation failed" error message, honestly worded for the mode. For
     * mode='create' it notes the server may have been registered WITHOUT its database; for
     * mode='register' the database already existed (validated up front), so it must NOT suggest the DB
     * may have been created — it points at the existing-infobase wrap instead.
     *
     * @param ex the failure cause
     * @param infobaseDir the served infobase / working directory
     * @param register {@code true} for mode='register', {@code false} for mode='create'
     * @return the error message
     */
    private static String standaloneJobErrorMessage(Exception ex, Path infobaseDir, boolean register)
    {
        String prefix = "Standalone-server " + (register ? "registration" : "creation") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " failed: " + ex.getMessage() //$NON-NLS-1$
            + ". Verify that a compatible 1C standalone-server runtime (platform >= 8.3.23) is " //$NON-NLS-1$
            + "registered and that the directory '" + infobaseDir + "' is accessible."; //$NON-NLS-1$ //$NON-NLS-2$
        String tail = register
            ? " The server may have been registered over the existing infobase; if so, use " //$NON-NLS-1$
                + "delete_infobase to remove the server registration (the database is left in place)." //$NON-NLS-1$
            : " The server may have been registered without its database; if so, use delete_infobase " //$NON-NLS-1$
                + "to remove it."; //$NON-NLS-1$
        return prefix + tail;
    }

    /**
     * Resolves the configuration project, the {@link IApplicationManager} and the (reflective)
     * standalone-server service for {@link #createStandaloneServer} (steps 1-2; read-only). Returns a
     * {@link StandaloneContext} whose {@code error} field carries the tool-result JSON for the SAME
     * early-return cases the inline code produced (project missing / closed; {@code IApplicationManager}
     * unavailable; standalone-server service unavailable); otherwise {@code error} is {@code null} and
     * the {@code project}/{@code appManager}/{@code serverService} fields are populated.
     */
    private static StandaloneContext resolveStandaloneContext(String projectName)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return StandaloneContext.failed(
                ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson());
        }
        if (!ctx.isOpen())
        {
            return StandaloneContext.failed(ToolResult.error("Project is closed: " + projectName //$NON-NLS-1$
                + ". Open the project in EDT first.").toJson()); //$NON-NLS-1$
        }

        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return StandaloneContext.failed(
                ToolResult.error("IApplicationManager service is not available").toJson()); //$NON-NLS-1$
        }

        Object serverService = acquireStandaloneServerService();
        if (serverService == null)
        {
            return StandaloneContext.failed(ToolResult.error("Standalone-server service is not available; the EDT " //$NON-NLS-1$
                + "standalone-server feature is missing. Install a 1C platform >= 8.3.23 with the " //$NON-NLS-1$
                + "standalone server and ensure the EDT standalone-server plugins are present.") //$NON-NLS-1$
                .toJson());
        }

        return StandaloneContext.of(ctx.project(), appManager, serverService);
    }

    /**
     * Joins the standalone-server create Job with the bounded {@link #CREATE_TIMEOUT_SECONDS} timeout
     * and maps the outcome to the SAME error tool-result JSON the inline code produced: a timeout
     * message (Job cancelled) when it did not finish, or an interrupted message (interrupt flag
     * restored) when the join threw. Returns {@code null} when the Job finished within the budget. The
     * {@code InterruptedException} from {@code join} is handled inline here (not propagated) exactly as
     * before. The wording is register-aware: mode='register' materializes no database, so the timeout
     * must not imply a partial DB was written.
     */
    private static String awaitStandaloneJob(Job createJob, Path infobaseDir, boolean register)
    {
        try
        {
            boolean finished = createJob.join(
                TimeUnit.SECONDS.toMillis(CREATE_TIMEOUT_SECONDS), null);
            if (!finished)
            {
                createJob.cancel();
                String verb = register ? "registration" : "creation"; //$NON-NLS-1$ //$NON-NLS-2$
                String proc = register
                    ? "The server-registration step may still be running. " //$NON-NLS-1$
                    : "The ibcmd process may still be running. "; //$NON-NLS-1$
                return ToolResult.error("Standalone-server " + verb + " timed out after " //$NON-NLS-1$ //$NON-NLS-2$
                    + CREATE_TIMEOUT_SECONDS + " seconds. " + proc //$NON-NLS-1$
                    + "Check the EDT log and the directory '" + infobaseDir //$NON-NLS-1$
                    + "'.").toJson(); //$NON-NLS-1$
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            String verb = register ? "registration" : "creation"; //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult.error("Standalone-server " + verb + " was interrupted.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Holder for the project + {@link IApplicationManager} + standalone-server service resolved by
     * {@link #resolveStandaloneContext(String)}. When {@code error} is non-null it carries a ready
     * tool-result JSON and the other fields are unset; otherwise {@code error} is {@code null} and the
     * fields are populated.
     */
    private static final class StandaloneContext
    {
        final String error;
        final IProject project;
        final IApplicationManager appManager;
        final Object serverService;

        private StandaloneContext(String error, IProject project, IApplicationManager appManager,
                Object serverService)
        {
            this.error = error;
            this.project = project;
            this.appManager = appManager;
            this.serverService = serverService;
        }

        static StandaloneContext failed(String error)
        {
            return new StandaloneContext(error, null, null, null);
        }

        static StandaloneContext of(IProject project, IApplicationManager appManager,
                Object serverService)
        {
            return new StandaloneContext(null, project, appManager, serverService);
        }
    }

    /**
     * Acquires the standalone-server service ({@code IStandaloneServerService}) from the OSGi service
     * registry BY CLASS NAME (reflectively, so this plugin has no compile/bundle dependency on the
     * standalone-server feature — see {@link #STANDALONE_SERVER_SERVICE_CLASS}). It is published via the
     * {@code com._1c.g5.wiring.serviceProvider} wiring of the standalone-server WST bundle. Returns
     * {@code null} (the caller fails gracefully) when the bundle or service is unavailable.
     *
     * @return the service object (call it reflectively), or {@code null} when unavailable
     */
    private static Object acquireStandaloneServerService()
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
                if (startBundleTransiently(bundle,
                    "create_infobase: could not start standalone-server bundle")) //$NON-NLS-1$
                {
                    context = bundle.getBundleContext();
                }
            }
            if (context == null)
            {
                return null;
            }
            // Look up by class NAME (String) — no compile-time reference to the service interface.
            ServiceReference<?> ref = context.getServiceReference(STANDALONE_SERVER_SERVICE_CLASS);
            return ref != null ? context.getService(ref) : null;
        }
        catch (Throwable t)
        {
            Activator.logError("create_infobase: could not acquire the standalone-server service", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Starts {@code bundle} transiently (so its services register / its activator runs) and reports
     * whether the start succeeded. On failure {@code errorMessage} is logged and {@code false} is
     * returned; the caller decides how to proceed.
     *
     * @param bundle       the bundle to start (must not be {@code null})
     * @param errorMessage message logged when the start throws
     * @return {@code true} when the bundle started without error, {@code false} otherwise
     */
    private static boolean startBundleTransiently(Bundle bundle, String errorMessage)
    {
        try
        {
            bundle.start(Bundle.START_TRANSIENT);
            return true;
        }
        catch (Exception startEx)
        {
            Activator.logError(errorMessage, startEx);
            return false;
        }
    }

    /**
     * Reflective {@code IStandaloneServerService.findRuntime(versionMask, monitor).isPresent()} — true
     * when a matching standalone-server runtime is registered. Any reflective/availability failure is
     * treated as "no runtime" (the caller then fails fast).
     */
    private static boolean ssHasRuntime(Object service, String versionMask)
    {
        try
        {
            Method m = service.getClass().getMethod("findRuntime", String.class, IProgressMonitor.class); //$NON-NLS-1$
            Object opt = m.invoke(service, versionMask, null);
            return (opt instanceof Optional) && ((Optional<?>)opt).isPresent();
        }
        catch (Throwable t) // NOSONAR deliberate catch-all at a reflective/best-effort boundary
        {
            Activator.logError("create_infobase: standalone-server runtime probe failed", t); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Reflective {@code IStandaloneServerService.createServerWithInfobase(...)}. Returns the resulting
     * {@code Pair<IServer, StandaloneServerInfobase>} as an {@code Object} (cast to {@code Pair} by the
     * caller). Unwraps and rethrows the real failure cause so the caller reports an honest error.
     */
    private static Object ssCreateServerWithInfobase(Object service, ServerCreateArgs args,
            IProgressMonitor monitor) throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        Method m = service.getClass().getMethod("createServerWithInfobase", //$NON-NLS-1$
            String.class, String.class, InfobaseReference.class, int.class, String.class, String.class,
            IProgressMonitor.class);
        try
        {
            return m.invoke(service, args.versionMask, args.projectName, args.ib, args.clusterPort,
                args.clusterRegistryDirectory, args.publicationPath, monitor);
        }
        catch (java.lang.reflect.InvocationTargetException ite)
        {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception)
            {
                throw (Exception)cause;
            }
            throw new IllegalStateException(cause != null ? cause : ite);
        }
    }

    /**
     * Immutable parameter-object bundling the six positional arguments forwarded to the reflective
     * {@code IStandaloneServerService.createServerWithInfobase(...)} call (everything except the service
     * receiver and the progress monitor). Keeps {@link #ssCreateServerWithInfobase} under the
     * parameter-count threshold without changing the reflective invocation.
     */
    private static final class ServerCreateArgs
    {
        final String versionMask;
        final String projectName;
        final InfobaseReference ib;
        final int clusterPort;
        final String clusterRegistryDirectory;
        final String publicationPath;

        ServerCreateArgs(String versionMask, String projectName, InfobaseReference ib, int clusterPort,
                String clusterRegistryDirectory, String publicationPath)
        {
            this.versionMask = versionMask;
            this.projectName = projectName;
            this.ib = ib;
            this.clusterPort = clusterPort;
            this.clusterRegistryDirectory = clusterRegistryDirectory;
            this.publicationPath = publicationPath;
        }
    }

    /**
     * Physically creates the served file infobase for a standalone server that
     * {@link #ssCreateServerWithInfobase} just registered. That call builds the {@code StandaloneServerInfobase}
     * with {@code create=false}, so {@code ibcmd infobase create} (which writes {@code 1Cv8.1CD}) never runs and
     * the server fails to start with "Информационная база не обнаружена". This flips {@code create=true} on the
     * returned LIVE module — the same flag the EDT new-server wizard sets — and invokes the WST behaviour
     * delegate's {@code createStandaloneServerInfobase} DIRECTLY (the only place that runs the create, gated by
     * {@code isCreate()}; verified against EDT 2025.2 bytecode — no start/publish path creates the DB, and
     * re-adding the module via {@code modifyModules} is blocked by an "already have module" guard).
     *
     * <p>Runs inside the create Job (with its monitor). Throws on failure so the caller reports an honest
     * error (the server is then registered without a DB; {@code delete_infobase} can clean it up).
     */
    private static void ssMaterializeInfobase(Object service, Object server, Object infobase,
            IProgressMonitor monitor) throws Exception
    {
        if (infobase == null)
        {
            throw new IllegalStateException("createServerWithInfobase returned no infobase handle; " //$NON-NLS-1$
                + "the served infobase could not be created."); //$NON-NLS-1$
        }
        // Flip create=true (the flag that gates the physical creation) on the live module. setExist=false
        // mirrors the EDT wizard's "new infobase" branch and is optional (a no-op if the API lacks it).
        if (ssMethod(infobase.getClass(), "setCreate", 1) == null) //$NON-NLS-1$
        {
            throw new IllegalStateException("StandaloneServerInfobase.setCreate not found — the standalone-" //$NON-NLS-1$
                + "server API may have changed; the served infobase could not be created."); //$NON-NLS-1$
        }
        ssInvoke(infobase, "setCreate", 1, Boolean.TRUE); //$NON-NLS-1$
        ssInvoke(infobase, "setExist", 1, Boolean.FALSE); //$NON-NLS-1$

        // Resolve the WST behaviour delegate for this server and run the (otherwise publish-time) create now.
        Object delegate = ssInvoke(service, "findBehaviourDelegate", 1, server); //$NON-NLS-1$
        if (delegate == null)
        {
            throw new IllegalStateException("Standalone-server behaviour delegate is not available; " //$NON-NLS-1$
                + "the served infobase could not be created."); //$NON-NLS-1$
        }
        Method create = ssMethod(delegate.getClass(), "createStandaloneServerInfobase", 2); //$NON-NLS-1$
        if (create == null)
        {
            throw new IllegalStateException("createStandaloneServerInfobase was not found on the standalone-" //$NON-NLS-1$
                + "server behaviour delegate — the standalone-server API may have changed."); //$NON-NLS-1$
        }
        try
        {
            create.invoke(delegate, infobase, monitor);
        }
        catch (java.lang.reflect.InvocationTargetException ite)
        {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception)
            {
                throw (Exception)cause;
            }
            throw new IllegalStateException(cause != null ? cause : ite);
        }
    }

    /** Reflectively invokes the first public method of {@code target} matching name + arg count. */
    private static Object ssInvoke(Object target, String name, int argCount, Object... args)
        throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        Method m = ssMethod(target.getClass(), name, argCount);
        if (m == null)
        {
            return null;
        }
        try
        {
            return m.invoke(target, args);
        }
        catch (java.lang.reflect.InvocationTargetException ite)
        {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception)
            {
                throw (Exception)cause;
            }
            throw new IllegalStateException(cause != null ? cause : ite);
        }
    }

    /** First public method on {@code cls} (incl. inherited) with the given name and parameter count. */
    private static Method ssMethod(Class<?> cls, String name, int paramCount)
    {
        for (Method m : cls.getMethods())
        {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount)
            {
                return m;
            }
        }
        return null;
    }

    /**
     * Reflective {@code IStandaloneServerService.getInfobaseUrl(standaloneServerInfobase)} — the web URL
     * for HTTP testing. Returns {@code null} on any failure (non-fatal: the server is already created).
     */
    private static URI ssGetInfobaseUrl(Object service, Object standaloneServerInfobase)
    {
        try
        {
            Bundle bundle = Platform.getBundle(STANDALONE_SERVER_WST_CORE_BUNDLE_ID);
            Class<?> ssInfobaseClass = bundle.loadClass(STANDALONE_SERVER_INFOBASE_CLASS);
            Method m = service.getClass().getMethod("getInfobaseUrl", ssInfobaseClass); //$NON-NLS-1$
            Object url = m.invoke(service, standaloneServerInfobase);
            return (url instanceof URI) ? (URI)url : null;
        }
        catch (Throwable t)
        {
            Activator.logError("create_infobase: could not resolve standalone-server web URL", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reflective {@code StandaloneServerInfobase.getName()} — the name EDT actually assigned to the new
     * standalone-server infobase (read back from the create result). Used for the application read-back
     * match and the reported {@code infobaseName} so they reflect what EDT stored, not what was
     * requested. Returns {@code null} on any failure (the caller then falls back to the requested name).
     */
    private static String ssGetInfobaseName(Object standaloneServerInfobase)
    {
        try
        {
            Method m = standaloneServerInfobase.getClass().getMethod("getName"); //$NON-NLS-1$
            Object name = m.invoke(standaloneServerInfobase);
            return (name instanceof String) ? (String)name : null;
        }
        catch (Throwable t) // NOSONAR deliberate catch-all at a reflective/best-effort boundary
        {
            Activator.logError("create_infobase: could not read standalone-server infobase name", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Computes a non-empty publication path for the standalone-server create call: a sanitized
     * (alphanumeric) infobase name, falling back to the project name (and finally a literal). EDT
     * ignores this value for a FILE-backed infobase (the publication base is hard-coded to "/"), but
     * the 7-arg API requires a non-empty string, so we always derive a sane one.
     *
     * @param infobaseName the infobase display name
     * @param projectName the project name (fallback)
     * @return the publication path (never {@code null}/empty)
     */
    private static String effectivePublicationPath(String infobaseName, String projectName)
    {
        // Sanitize the infobase name to an alnum web path; fall back to the project name.
        String sanitized = infobaseName != null ? infobaseName.replaceAll("[^A-Za-z0-9]", "") : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!sanitized.isEmpty())
        {
            return sanitized;
        }
        String fromProject = projectName != null ? projectName.replaceAll("[^A-Za-z0-9]", "") : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return fromProject.isEmpty() ? KIND_INFOBASE : fromProject;
    }

    /**
     * Builds the "no standalone-server runtime registered" error message. Behaviour-identical to the
     * former inline string concatenation, including the optional {@code for version '...'} fragment.
     *
     * @param platform the requested platform version mask (may be {@code null}/empty)
     * @return the error message
     */
    /**
     * Returns the given string, or the empty string when it is {@code null}. Behaviour-identical to the
     * former inline {@code platform != null ? platform : ""}.
     *
     * @param value the value (may be {@code null})
     * @return {@code value}, or {@code ""} when {@code null}
     */
    private static String orEmpty(String value)
    {
        return value != null ? value : ""; //$NON-NLS-1$
    }

    private static String noRuntimeError(String platform)
    {
        return "No standalone-server runtime registered" //$NON-NLS-1$
            + (platform != null && !platform.isEmpty() ? " for version '" + platform + "'" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ". Install a 1C platform >= 8.3.23 with the standalone server (ibsrv/ibcmd) and " //$NON-NLS-1$
            + "register it in EDT (Window -> Preferences -> 1C:Enterprise -> Installed " //$NON-NLS-1$
            + "Installations), or pass a matching platform=..."; //$NON-NLS-1$
    }

    /**
     * Resolves the effective infobase name when the caller omitted it: derives it from the infobase
     * directory's file name, falling back to the project name. Behaviour-identical to the former inline
     * {@code if (infobaseName == null || infobaseName.isEmpty()) { ... }} block — returns the original
     * name unchanged when it is non-empty.
     *
     * @param infobaseName the requested infobase name (may be {@code null}/empty)
     * @param infobaseDir the infobase directory
     * @param projectName the project name (final fallback)
     * @return the effective infobase name
     */
    private static String effectiveStandaloneInfobaseName(String infobaseName, Path infobaseDir,
            String projectName)
    {
        if (infobaseName == null || infobaseName.isEmpty())
        {
            Path fileName = infobaseDir.getFileName();
            return fileName != null ? fileName.toString() : projectName;
        }
        return infobaseName;
    }

    /**
     * Casts the create-job result to a {@link Pair} when it is one, else {@code null}. Behaviour-identical
     * to the former inline {@code (result instanceof Pair) ? (Pair<?, ?>)result : null}.
     *
     * @param result the value read back from the create job
     * @return the result as a {@link Pair}, or {@code null}
     */
    private static Pair<?, ?> asPair(Object result)
    {
        return (result instanceof Pair) ? (Pair<?, ?>)result : null;
    }

    /**
     * Tells whether the create-job pair carries a usable infobase handle. Behaviour-identical to the
     * former inline {@code !(pair == null || pair.getSecond() == null)} guard.
     *
     * @param pair the create-job result pair (may be {@code null})
     * @return {@code true} when the pair and its second element are both non-{@code null}
     */
    private static boolean hasInfobaseHandle(Pair<?, ?> pair)
    {
        return pair != null && pair.getSecond() != null;
    }

    /**
     * Returns the first argument when it is non-empty, else the second. Behaviour-identical to the former
     * inline {@code (actualName != null && !actualName.isEmpty()) ? actualName : infobaseName}.
     *
     * @param preferred the preferred value (used when non-{@code null}/non-empty)
     * @param fallback the fallback value
     * @return {@code preferred} when non-empty, else {@code fallback}
     */
    private static String firstNonEmpty(String preferred, String fallback)
    {
        return (preferred != null && !preferred.isEmpty()) ? preferred : fallback;
    }

    /**
     * The URL as a string, or {@code null} when the URL is {@code null}. Behaviour-identical to the former
     * inline {@code (url != null) ? url.toString() : null}.
     *
     * @param url the resolved web URL (may be {@code null})
     * @return the string form, or {@code null}
     */
    private static String urlToString(URI url)
    {
        return (url != null) ? url.toString() : null;
    }

    /**
     * The URL's port, or {@code -1} when the URL is {@code null}. Behaviour-identical to the former inline
     * {@code (url != null) ? url.getPort() : -1}.
     *
     * @param url the resolved web URL (may be {@code null})
     * @return the port, or {@code -1}
     */
    private static int urlToPort(URI url)
    {
        return (url != null) ? url.getPort() : -1;
    }

    /**
     * Reads back the applications for the project, finds the new {@code wst-server} application, and
     * builds the success JSON for the standalone-server path. Uses the same short bounded re-poll as
     * the file path to absorb the provision-delegate listener race.
     */
    private static String buildStandaloneServerResult(ResultContext rc, int actualPort, String webUrl,
            boolean setDefault, boolean register)
    {
        // Read back the applications (bounded re-poll) and locate the just-created wst-server.
        ServerReadBack readBack =
            pollForNewServerApplication(rc.appManager, rc.project, rc.infobaseName);
        JsonArray appsArray = readBack.appsArray;
        String newAppId = readBack.newAppId;
        IApplication newApp = readBack.newApp;

        // Optionally set the new standalone server as the project's default application. A wst-server
        // application is an ordinary IApplication, so the same setDefaultApplication API the file path
        // uses works here too. Non-fatal: the server is already created and bound.
        String setDefaultNote = null;
        if (setDefault)
        {
            if (newApp != null)
            {
                try
                {
                    rc.appManager.setDefaultApplication(rc.project, newApp);
                }
                catch (Exception e)
                {
                    Activator.logError("create_infobase: setDefault failed", e); //$NON-NLS-1$
                    setDefaultNote = "; the server was created but could not be set as default - " //$NON-NLS-1$
                        + "set it manually or retry"; //$NON-NLS-1$
                }
            }
            else
            {
                setDefaultNote = "; the server was created but could not be set as default yet " //$NON-NLS-1$
                    + "(the application was not visible yet) - set it manually or retry"; //$NON-NLS-1$
                Activator.logError("create_infobase: setDefault skipped — new server app not found yet", //$NON-NLS-1$
                    null);
            }
        }

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, register ? "registered" : "created") //$NON-NLS-1$ //$NON-NLS-2$
            .put(KEY_APPLICATION_KIND, KIND_STANDALONE_SERVER)
            .put(McpKeys.PROJECT, rc.projectName)
            .put(KEY_INFOBASE_FILE, rc.infobaseDir.toAbsolutePath().toString())
            .put(KEY_INFOBASE_NAME, rc.infobaseName)
            .put(KEY_APPLICATIONS, appsArray);

        // Report the ACTUAL auto-allocated port only when we could resolve it from the web URL; // NOSONAR explanatory comment, not commented-out code
        // never echo the requested/hint port (EDT ignores it for a FILE-backed standalone server).
        if (actualPort > 0)
        {
            result.put("port", actualPort); //$NON-NLS-1$
        }
        if (webUrl != null)
        {
            result.put("webUrl", webUrl); //$NON-NLS-1$
        }
        if (newAppId != null)
        {
            result.put(McpKeys.APPLICATION_ID, newAppId);
        }

        result.put(McpKeys.MESSAGE, buildStandaloneServerMessage(rc.projectName, rc.infobaseDir,
            rc.infobaseName, actualPort, webUrl, setDefaultNote, register));

        return result.toJson();
    }

    /**
     * Reads back the project's applications with the same short bounded re-poll the file path uses
     * (to absorb the provision-delegate listener race) and locates the JUST-created standalone server
     * — a {@code wst-server} application whose name matches {@code infobaseName} (NOT merely the first
     * wst-server, which could be a pre-existing one). Read-only: it only reads applications and builds
     * the {@code appsArray} echo. Returns a {@link ServerReadBack} carrying the JSON array plus the new
     * application id/handle ({@code null} when not found within the poll budget).
     */
    private static ServerReadBack pollForNewServerApplication(IApplicationManager appManager,
            IProject project, String infobaseName)
    {
        JsonArray appsArray = new JsonArray();
        IApplication[] newAppHolder = new IApplication[1];

        for (int poll = 0; poll < READ_BACK_MAX_POLLS; poll++) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
        {
            appsArray = new JsonArray();
            newAppHolder[0] = null;

            if (!readBackServerApplications(appManager, project, infobaseName, appsArray, newAppHolder))
            {
                break; // Read failed (logged) — stop re-polling.
            }

            if (newAppHolder[0] != null)
            {
                break; // Found the new server — no need to re-poll.
            }

            if (poll < READ_BACK_MAX_POLLS - 1 && !sleepBetweenPolls())
            {
                break; // Interrupted — stop re-polling.
            }
        }

        IApplication newApp = newAppHolder[0];
        String newAppId = newApp != null ? newApp.getId() : null;
        return new ServerReadBack(appsArray, newAppId, newApp);
    }

    /**
     * Reads the project's applications once, populating {@code appsArray} with one JSON object per
     * application (same shape as {@code get_applications}) and storing the JUST-created standalone
     * server — a {@code wst-server} application whose name matches {@code infobaseName} (NOT merely the
     * first wst-server, which could be a pre-existing one) — in {@code newAppHolder[0]} (left
     * {@code null} when not yet visible). Read-only.
     *
     * @return {@code true} if the read completed (whether or not the new server was found),
     *         {@code false} if reading the applications failed (already logged) so the caller stops
     *         re-polling
     */
    private static boolean readBackServerApplications(IApplicationManager appManager, IProject project,
            String infobaseName, JsonArray appsArray, IApplication[] newAppHolder)
    {
        try
        {
            List<IApplication> applications = appManager.getApplications(project);
            if (applications != null)
            {
                for (IApplication app : applications)
                {
                    appsArray.add(toApplicationJson(appManager, app));
                    // Identify the JUST-created standalone server by its name (a wst-server app whose
                    // name matches the new infobase) — NOT merely the first wst-server app, which could
                    // be a pre-existing standalone server already bound to this project.
                    String typeId = app.getType() != null ? app.getType().getId() : null;
                    if (newAppHolder[0] == null && StandaloneServerSupport.WST_SERVER_APP_TYPE.equals(typeId)
                        && infobaseName.equals(app.getName()))
                    {
                        newAppHolder[0] = app;
                    }
                }
            }
            return true;
        }
        catch (ApplicationException e)
        {
            Activator.logError("create_infobase: error reading back applications", e); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Builds the {@code applications} echo entry for a single application: id, name, optional type and
     * update state — the same shape as {@code get_applications}. Read-only.
     */
    private static JsonObject toApplicationJson(IApplicationManager appManager, IApplication app)
    {
        JsonObject appObj = new JsonObject();
        appObj.addProperty("id", app.getId()); //$NON-NLS-1$
        appObj.addProperty("name", app.getName()); //$NON-NLS-1$
        String typeId = app.getType() != null ? app.getType().getId() : null;
        if (typeId != null)
        {
            appObj.addProperty("type", typeId); //$NON-NLS-1$
        }
        addUpdateState(appObj, appManager, app);
        return appObj;
    }

    /**
     * Builds the human-readable status message for the standalone-server success result (read-only
     * string assembly). For mode='create' it says the server CREATED a new infobase; for mode='register'
     * it says the server was REGISTERED over the EXISTING infobase (no new database was written).
     * Appends {@code setDefaultNote} when non-null.
     */
    private static String buildStandaloneServerMessage(String projectName, Path infobaseDir,
            String infobaseName, int actualPort, String webUrl, String setDefaultNote, boolean register)
    {
        String lead = register
            ? "Registered a standalone server over the existing infobase '" + infobaseName //$NON-NLS-1$
                + "' at '" + infobaseDir.toAbsolutePath() //$NON-NLS-1$
                + "' and bound it to project '" + projectName + "'" //$NON-NLS-1$ //$NON-NLS-2$
            : "Standalone server for infobase '" + infobaseName //$NON-NLS-1$
                + "' created at '" + infobaseDir.toAbsolutePath() //$NON-NLS-1$
                + "' and bound to project '" + projectName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        return lead
            + (actualPort > 0 ? " (web port " + actualPort + ")" : "") + "." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + (webUrl != null ? " Web URL for HTTP testing: " + webUrl + "." : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " To load the configuration, use the coordinated launch flow (debug_launch or " //$NON-NLS-1$
            + "run_yaxunit_tests with updateBeforeLaunch=true) rather than a bare update_database, " //$NON-NLS-1$
            + "which would start the server in RUN mode." //$NON-NLS-1$
            + (setDefaultNote != null ? setDefaultNote : ""); //$NON-NLS-1$
    }

    /**
     * Holder for the standalone-server read-back: the {@code applications} JSON echo plus the
     * just-created server's id/handle ({@code null} when it was not found within the poll budget).
     */
    private static final class ServerReadBack
    {
        final JsonArray appsArray;
        final String newAppId;
        final IApplication newApp;

        ServerReadBack(JsonArray appsArray, String newAppId, IApplication newApp)
        {
            this.appsArray = appsArray;
            this.newAppId = newAppId;
            this.newApp = newApp;
        }
    }

    /**
     * Immutable parameter-object bundling the identity + read-back inputs shared by the success-result
     * builders ({@link #buildSuccessResult} and {@link #buildStandaloneServerResult}): the project
     * name/handle, the infobase directory and display name, and the {@link IApplicationManager} used
     * for the application read-back. Keeps those builders under the parameter-count threshold without
     * changing what they compute.
     */
    private static final class ResultContext
    {
        final String projectName;
        final Path infobaseDir;
        final String infobaseName;
        final IApplicationManager appManager;
        final IProject project;

        ResultContext(String projectName, Path infobaseDir, String infobaseName,
                IApplicationManager appManager, IProject project)
        {
            this.projectName = projectName;
            this.infobaseDir = infobaseDir;
            this.infobaseName = infobaseName;
            this.appManager = appManager;
            this.project = project;
        }
    }

    /**
     * Adds the application's update state to {@code appObj} under {@link #KEY_UPDATE_STATE}.
     * On {@link ApplicationException} (state could not be read) the value is recorded as
     * {@code "UNKNOWN"}; a {@code null} state is omitted.
     */
    private static void addUpdateState(JsonObject appObj, IApplicationManager appManager,
            IApplication app)
    {
        try
        {
            ApplicationUpdateState updateState = appManager.getUpdateState(app);
            if (updateState != null)
            {
                appObj.addProperty(KEY_UPDATE_STATE, updateState.name());
            }
        }
        catch (ApplicationException e)
        {
            appObj.addProperty(KEY_UPDATE_STATE, "UNKNOWN"); //$NON-NLS-1$
        }
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
            // PlatformServicesCore is an INTERNAL class of the platform-services.core bundle; // NOSONAR explanatory comment, not commented-out code
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
            getDefault.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
            Object coreInstance = getDefault.invoke(null);
            if (coreInstance == null)
            {
                // Bundle not active yet — start it transiently and retry once.
                startBundleTransiently(psCoreBundle,
                    "create_infobase: could not start platform-services.core bundle"); //$NON-NLS-1$
                coreInstance = getDefault.invoke(null);
                if (coreInstance == null)
                {
                    return null;
                }
            }
            java.lang.reflect.Method getInjector =
                coreClass.getDeclaredMethod("getInjector"); //$NON-NLS-1$
            getInjector.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
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
    private static String buildSuccessResult(ResultContext rc, InfobaseReference ibRef,
            String setDefaultNote, boolean register)
    {
        JsonArray appsArray = new JsonArray();
        String newAppId = null;

        // Short bounded re-poll: the provision-delegate listener fires asynchronously after
        // associate(), so the new IInfobaseApplication may not be visible on the first read.
        for (int poll = 0; poll < READ_BACK_MAX_POLLS; poll++) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
        {
            appsArray = new JsonArray();
            newAppId = null;
            String[] newAppIdHolder = new String[1];

            if (!readBackApplications(rc.appManager, rc.project, ibRef, appsArray, newAppIdHolder))
            {
                break; // Read failed (logged) — stop re-polling.
            }
            newAppId = newAppIdHolder[0];

            if (newAppId != null)
            {
                break; // Found the new application — no need to re-poll.
            }

            if (poll < READ_BACK_MAX_POLLS - 1 && !sleepBetweenPolls())
            {
                break; // Interrupted — stop re-polling.
            }
        }

        String verb = register ? "registered" : "created"; //$NON-NLS-1$ //$NON-NLS-2$
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, verb)
            .put(McpKeys.PROJECT, rc.projectName)
            .put(KEY_INFOBASE_FILE, rc.infobaseDir.toAbsolutePath().toString())
            .put(KEY_INFOBASE_NAME, rc.infobaseName)
            .put(KEY_APPLICATIONS, appsArray);

        if (newAppId != null)
        {
            result.put(McpKeys.APPLICATION_ID, newAppId);
        }

        String message = "Infobase '" + rc.infobaseName //$NON-NLS-1$
            + "' " + verb + " at '" + rc.infobaseDir.toAbsolutePath() //$NON-NLS-1$ //$NON-NLS-2$
            + "' and bound to project '" + rc.projectName //$NON-NLS-1$
            + "'. Use update_database to push the configuration into the infobase." //$NON-NLS-1$
            + (setDefaultNote != null ? setDefaultNote : ""); //$NON-NLS-1$
        result.put(McpKeys.MESSAGE, message);

        return result.toJson();
    }

    /**
     * Reads the project's applications once, populating {@code appsArray} with one JSON object per
     * application and storing the matched new-infobase application id in {@code newAppIdHolder[0]}
     * (left {@code null} when not yet visible). Read-only.
     *
     * @return {@code true} if the read completed (whether or not the new application was found),
     *         {@code false} if reading the applications failed (already logged) so the caller stops
     *         re-polling
     */
    private static boolean readBackApplications(IApplicationManager appManager, IProject project,
            InfobaseReference ibRef, JsonArray appsArray, String[] newAppIdHolder)
    {
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
                    addUpdateState(appObj, appManager, app);
                    // Identify the newly created application by matching the infobase reference.
                    if (newAppIdHolder[0] == null && isMatchingNewInfobaseApp(app, ibRef))
                    {
                        newAppIdHolder[0] = app.getId();
                    }
                    appsArray.add(appObj);
                }
            }
            return true;
        }
        catch (ApplicationException e)
        {
            Activator.logError("create_infobase: error reading back applications", e); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Whether the application is the newly created FILE infobase application, identified by type and
     * a connection-string match against {@code ibRef}. Read-only.
     */
    private static boolean isMatchingNewInfobaseApp(IApplication app, InfobaseReference ibRef)
    {
        if (!(app instanceof IInfobaseApplication)
            || !INFOBASE_APP_TYPE.equals(app.getType() != null ? app.getType().getId() : null))
        {
            return false;
        }
        IInfobaseApplication ibApp = (IInfobaseApplication) app;
        return ibApp.getInfobase() != null && matchesRef(ibApp.getInfobase(), ibRef);
    }

    /**
     * Sleeps {@link #READ_BACK_POLL_DELAY_MS} between read-back polls. Returns {@code false} (with
     * the thread's interrupt flag restored) if interrupted, so the caller stops re-polling — mirrors
     * the original inline {@code Thread.sleep} / interrupt handling.
     */
    private static boolean sleepBetweenPolls()
    {
        try
        {
            Thread.sleep(READ_BACK_POLL_DELAY_MS);
            return true;
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return false;
        }
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
