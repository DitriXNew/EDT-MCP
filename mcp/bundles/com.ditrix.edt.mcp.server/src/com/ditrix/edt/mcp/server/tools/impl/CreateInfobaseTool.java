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
 * <p>The two steps below are two separate FACTS, and the second one is not implied by the first:
 * {@code associate} returning without an exception does not mean the project has the application.
 * The tool therefore reads the applications back and reports what that read-back established —
 * bound, measured absent (an error that still says the database exists), or unverified (issue #412).
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

    /**
     * Output key: whether the infobase actually surfaced as an application of the project, as
     * ESTABLISHED by the post-association read-back (issue #412). Omitted when the read-back itself
     * could not be completed — a failed read does not establish absence.
     */
    private static final String KEY_BOUND_TO_PROJECT = "boundToProject"; //$NON-NLS-1$

    /** {@code action} value (and message verb) for mode='create'. */
    private static final String ACTION_CREATED = "created"; //$NON-NLS-1$

    /** {@code action} value (and message verb) for mode='register'. */
    private static final String ACTION_REGISTERED = "registered"; //$NON-NLS-1$

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

    /** Common prefix of a standalone-server create/register failure message. */
    private static final String STANDALONE_SERVER_MSG_PREFIX = "Standalone-server "; //$NON-NLS-1$

    /** mode='register' word used in standalone-server failure/timeout messages. */
    private static final String VERB_REGISTRATION = "registration"; //$NON-NLS-1$

    /** mode='create' word used in standalone-server failure/timeout messages. */
    private static final String VERB_CREATION = "creation"; //$NON-NLS-1$

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

    /**
     * FQN of the create-template file database ({@code FileCreateTemplateDatabase extends FileDatabase
     * implements ICreateTemplateDatabase}; javap-verified IDENTICAL on EDT 2025.2 and 2026.1, bundle
     * {@code com.e1c.g5.v8.dt.platform.standaloneserver.core}). Loaded via the LIVE database object's
     * own classloader (same bundle) — never {@code Class.forName}: this plugin has no dependency on
     * that bundle. See {@link #ssEnsureCreateTemplateDatabase}.
     */
    private static final String CREATE_TEMPLATE_DATABASE_CLASS =
        "com.e1c.g5.v8.dt.platform.standaloneserver.core.config.FileCreateTemplateDatabase"; //$NON-NLS-1$

    /**
     * SIMPLE name of the create-template marker interface
     * ({@code com.e1c.g5.v8.dt.platform.standaloneserver.core.config.ICreateTemplateDatabase}).
     * {@link #ssIsCreateTemplateDatabase} matches it by simple name so the decision logic is
     * unit-testable with headless stub interfaces (the platform ships no other type with this name).
     */
    private static final String CREATE_TEMPLATE_DATABASE_INTERFACE = "ICreateTemplateDatabase"; //$NON-NLS-1$

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
            + "(up to 120 s). user/password/access store connection credentials for " //$NON-NLS-1$
            + "applicationKind='infobase', and for applicationKind='standaloneServer' with " //$NON-NLS-1$
            + "mode='register' — rejected for a newly created standalone server " //$NON-NLS-1$
            + "(mode='create'). Full parameters and examples: call get_tool_guide('create_infobase')."; //$NON-NLS-1$
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
                + "mode='register' (the existing base already has users). Omit to store no credentials. " //$NON-NLS-1$
                + "Accepted for applicationKind='infobase', and for applicationKind='standaloneServer' " //$NON-NLS-1$
                + "with mode='register'; rejected for a newly created standalone server " //$NON-NLS-1$
                + "(mode='create').") //$NON-NLS-1$
            .stringProperty("password", //$NON-NLS-1$
                "Password for 'user'. Optional; default empty (demo bases use an empty password). " //$NON-NLS-1$
                + "Same applicationKind/mode restriction as 'user'.") //$NON-NLS-1$
            .enumProperty(KEY_ACCESS,
                "Authentication kind for the stored credentials: 'INFOBASE' (default, 1C user auth) " //$NON-NLS-1$
                + "or 'OS'. Credentials are stored when ANY of user/password/access is given; " //$NON-NLS-1$
                + "access='OS' on its own stores OS-authentication settings (no 1C user/password). " //$NON-NLS-1$
                + "Applies to applicationKind='infobase' (a file infobase), and to " //$NON-NLS-1$
                + "applicationKind='standaloneServer' with mode='register'; rejected for " //$NON-NLS-1$
                + "a newly created standalone server (mode='create').", //$NON-NLS-1$
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
                "applicationKind='standaloneServer' only: the ACTUAL web port EDT allocated, parsed " //$NON-NLS-1$
                + "from the resolved webUrl (the endpoint is read from EDT's configuration, not " //$NON-NLS-1$
                + "probed). Absent if webUrl could not be resolved.") //$NON-NLS-1$
            .objectArrayProperty(KEY_APPLICATIONS,
                "Applications bound to the project after creation (same shape as get_applications).") //$NON-NLS-1$
            .booleanProperty(KEY_BOUND_TO_PROJECT,
                "Whether the infobase actually appeared as an application of the project, as " //$NON-NLS-1$
                + "ESTABLISHED by the read-back that follows the association. true = the application " //$NON-NLS-1$
                + "is there (applicationId is echoed whenever the platform gave it one). false = the " //$NON-NLS-1$
                + "read-back completed and the " //$NON-NLS-1$
                + "application is NOT there, so the call is reported as an error - the database " //$NON-NLS-1$
                + "itself still exists (see 'action' and 'infobaseFile'). ABSENT = the applications " //$NON-NLS-1$
                + "could not be compared (the read failed, the call was interrupted before the poll " //$NON-NLS-1$
                + "budget was spent, or an application's identity was unreadable), so the binding " //$NON-NLS-1$
                + "is unverified: call get_applications to confirm it.") //$NON-NLS-1$
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

        // Validate mode (default 'create'). Parsed BEFORE the credentials guard below (issue #275
        // needs to know register to decide whether standaloneServer+credentials is allowed).
        FlagResult mode = parseMode(modeStr);
        if (mode.error != null)
        {
            return mode.error;
        }
        boolean register = mode.value;

        // Credentials (#194) always apply to a FILE infobase's access settings. A newly created
        // standalone server (mode='create') has no existing infobase reference to store them
        // against at this point and would silently drop them — reject up front (before any
        // workspace/service access) rather than no-op. mode='register' wraps an EXISTING file
        // infobase that already has users, so credentials DO apply there (issue #275): the
        // read-back wst-server application is adapted to an InfobaseReference via the widened
        // InfobaseAccessSupport.storeCredentials(IApplication, ...), which is exactly what EDT's
        // own launch path (ServerApplicationBehaviourDelegate) resolves against later.
        if (standaloneServer && !register && credentials.any())
        {
            return ToolResult.error("user/password/access are supported only with " //$NON-NLS-1$
                + "applicationKind='infobase' (a file infobase), or with applicationKind=" //$NON-NLS-1$
                + "'standaloneServer' AND mode='register' (a standalone server wrapping an existing, " //$NON-NLS-1$
                + "already-registered infobase). A newly created standalone server " //$NON-NLS-1$
                + "(mode='create') has no existing infobase users yet — omit these parameters or " //$NON-NLS-1$
                + "use mode='register'.").toJson(); //$NON-NLS-1$
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
            // the database materialization. credentials (issue #275) are only ever non-empty here
            // when register==true — the guard above already rejected mode='create'+credentials.
            return createStandaloneServer(projectName, infobaseDir, infobaseName, platform,
                setDefault, register, credentials);
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

    /**
     * The infobase connection credentials (#194); any field may be {@code null}/empty when not
     * supplied. Package-visible so the unit tests can drive the result builders.
     */
    static final class Credentials
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
        String associateError =
            associateInfobase(assocManager, project, ibRef, infobaseDir, projectName, register);
        if (associateError != null)
        {
            return associateError;
        }

        // --- 8. Optionally store infobase connection credentials (#194) ---
        String credNote = storeCredentialsIfRequested(ibRef, credentials, register);

        // --- 9. Read back, apply setDefault to what the read-back FOUND, and return ---
        // setDefault is applied AFTER the read-back on purpose (issue #412): it used to run its own
        // one-shot lookup BEFORE the bounded re-poll, so it could report "could not be set as default"
        // for an application the re-poll then found. One observation feeds one report.
        ResultContext rc = new ResultContext(projectName, infobaseDir, infobaseName, appManager, project);
        return buildSuccessResult(rc, ibRef, setDefault, register, credNote);
    }

    /**
     * Stores infobase connection credentials on the freshly-built {@code ibRef} when the caller
     * supplied any of {@code user}/{@code password}/{@code access} (#194), returning a note to append
     * to the result message — a success note, a non-fatal WARNING when the store failed, or
     * {@code null} when no credentials were requested. Credential storage never fails the
     * infobase creation itself (the base is already created; whether it is BOUND is established
     * later, by the read-back).
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
     *
     * <p>A {@code null} return means only that {@code associate} did not throw — NOT that the project
     * now has the application. Whether the binding materialized is established later, by the read-back
     * in {@link #buildSuccessResult} (issue #412), so neither the log line nor the return value claims
     * more than that here.
     */
    private static String associateInfobase(IInfobaseAssociationManager assocManager, IProject project,
            InfobaseReference ibRef, Path infobaseDir, String projectName, boolean register)
    {
        try
        {
            assocManager.associate(project, ibRef, InfobaseAssociationSettings.notSynchronized());
        }
        catch (Exception e)
        {
            Activator.logError("create_infobase: association failed for project " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Infobase at '" + infobaseDir //$NON-NLS-1$
                + "' was " + (register ? ACTION_REGISTERED : ACTION_CREATED) //$NON-NLS-1$
                + " but could not be associated with project '" + projectName //$NON-NLS-1$
                + "': " + e.getMessage() //$NON-NLS-1$
                + ". The database itself is intact - do not create it again.").toJson(); //$NON-NLS-1$
        }
        Activator.logInfo("create_infobase: associate() returned without error for project " //$NON-NLS-1$
            + projectName + " (the binding is verified by the read-back)"); //$NON-NLS-1$
        return null;
    }

    /**
     * Optionally sets the new infobase as the project's default application, using the application the
     * read-back actually FOUND (issue #412) instead of a second, earlier lookup of its own. Non-fatal:
     * returns a note to append to the message, or {@code null} when the default was set.
     *
     * <p>Every note here is about {@code setDefault} ONLY. The reason there is no application to set —
     * and whether that is even established — is reported by the caller, not blamed on this flag.
     */
    private static String applySetDefault(IApplicationManager appManager, IProject project,
            ApplicationReadBack readBack)
    {
        if (readBack.app == null)
        {
            Activator.logInfo("create_infobase: setDefault not applied - no application to set (" //$NON-NLS-1$
                + readBack.outcome + ")"); //$NON-NLS-1$
            return readBack.outcome == BindingOutcome.NOT_BOUND
                ? " setDefault was not applied: the project has no application for this infobase." //$NON-NLS-1$
                : " setDefault was not applied: the new application could not be read back - check " //$NON-NLS-1$
                    + "get_applications and set it manually."; //$NON-NLS-1$
        }
        try
        {
            appManager.setDefaultApplication(project, readBack.app);
        }
        catch (Exception e)
        {
            // Non-fatal: the infobase was created and is bound; only the default-setting failed. Say so
            // instead of swallowing it — the failure was established, and the caller asked for it.
            Activator.logError("create_infobase: setDefault failed", e); //$NON-NLS-1$
            return " setDefault failed: " + e.getMessage() + " - set it manually or retry."; //$NON-NLS-1$ //$NON-NLS-2$
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
     * @param credentials connection credentials to store against the read-back wst-server application
     *            (issue #275); non-empty only when {@code register} is {@code true} — {@link #execute}
     *            already rejects credentials with mode='create'
     * @return the tool result JSON
     */
    private String createStandaloneServer(String projectName, Path infobaseDir,
            String infobaseName, String platform, boolean setDefault, boolean register,
            Credentials credentials)
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
        return buildStandaloneServerResult(rc, actualPort, webUrl, setDefault, register, credentials);
    }

    /**
     * Best-effort marks a just-registered {@code StandaloneServerInfobase} module as ALREADY existing
     * for mode='register' — {@code setExist(true)} (mirrors the EDT wizard's existing-infobase branch).
     * Never called with the create-flag setter ({@link #ssSetCreateFlag}): the served {@code 1Cv8.1CD}
     * already exists on disk, so no database is materialized. {@code setExist} exists only on EDT
     * 2025.2 — it was REMOVED on 2026.1 with no replacement, so there the reflective {@link #ssInvoke}
     * call below resolves to a silent no-op (method not found -> returns {@code null}, no exception, no
     * log) and this method degrades to a harmless no-op. Any OTHER reflective failure (e.g. an actual
     * invocation error on 2025.2) is logged and swallowed — the WST server registration already
     * succeeded and must not be failed by a best-effort flag.
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
        String prefix = STANDALONE_SERVER_MSG_PREFIX + (register ? VERB_REGISTRATION : VERB_CREATION)
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
                String verb = register ? VERB_REGISTRATION : VERB_CREATION;
                String proc = register
                    ? "The server-registration step may still be running. " //$NON-NLS-1$
                    : "The ibcmd process may still be running. "; //$NON-NLS-1$
                return ToolResult.error(STANDALONE_SERVER_MSG_PREFIX + verb + " timed out after " //$NON-NLS-1$
                    + CREATE_TIMEOUT_SECONDS + " seconds. " + proc //$NON-NLS-1$
                    + "Check the EDT log and the directory '" + infobaseDir //$NON-NLS-1$
                    + "'.").toJson(); //$NON-NLS-1$
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            String verb = register ? VERB_REGISTRATION : VERB_CREATION;
            return ToolResult.error(STANDALONE_SERVER_MSG_PREFIX + verb + " was interrupted.").toJson(); //$NON-NLS-1$
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
     * with the create-new-infobase flag {@code false}, so {@code ibcmd infobase create} (which writes
     * {@code 1Cv8.1CD}) never runs and the server fails to start with "Информационная база не обнаружена". This
     * flips that flag to {@code true} on the returned LIVE module — the same flag the EDT new-server wizard sets,
     * named {@code setCreate} on EDT 2025.2 and renamed (no back-compat alias) to {@code setCreateNewInfobase} on
     * 2026.1, resolved version-tolerantly by {@link #ssSetCreateFlag} — and invokes the WST behaviour delegate's
     * {@code createStandaloneServerInfobase} DIRECTLY (the only place that runs the create, gated by the flag's
     * getter; verified against EDT 2025.2 bytecode — no start/publish path creates the DB, and re-adding the
     * module via {@code modifyModules} is blocked by an "already have module" guard).
     *
     * <p>On 2026.1 there is a SECOND drift layer past the setter rename: {@code createServerWithInfobase}
     * builds the module config with a PLAIN {@code FileDatabase}, but the behaviour delegate's
     * {@code createStandaloneServerInfobase} now CASTS the config's database to
     * {@code ICreateTemplateDatabase} (live error: "FileDatabase cannot be cast to class
     * ...ICreateTemplateDatabase"). {@link #ssEnsureCreateTemplateDatabase} swaps in a
     * {@code FileCreateTemplateDatabase} (identical on both versions, harmless on 2025.2 — it IS a
     * {@code FileDatabase}) before the delegate runs.
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
        // Flip the create-new-infobase flag to true (the flag that gates the physical creation) on the
        // live module — version-tolerant name resolution, see ssSetCreateFlag. setExist=false mirrors the
        // EDT wizard's "new infobase" branch on 2025.2 and stays best-effort/optional: setExist was
        // REMOVED on 2026.1 with no replacement, and ssInvoke resolves a missing method to a silent
        // no-op (returns null, no exception, no log) — matching the clean log observed on a live 2026.1
        // register run.
        ssSetCreateFlag(infobase, true);
        ssInvoke(infobase, "setExist", 1, Boolean.FALSE); //$NON-NLS-1$

        // 2026.1's behaviour delegate casts the config's database to ICreateTemplateDatabase — make sure
        // it is one BEFORE invoking the delegate (a no-op when it already is; safe on 2025.2 too).
        ssEnsureCreateTemplateDatabase(infobase);

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
     * First public method on {@code cls} (incl. inherited) matching any of {@code names} (tried in
     * order) and the given parameter count — a version-tolerant lookup for an API whose method name was
     * RENAMED across EDT platform versions with no back-compat alias (e.g. {@code setCreate} on 2025.2 /
     * {@code setCreateNewInfobase} on 2026.1). Returns the first match by name-priority order, or
     * {@code null} when none of {@code names} resolve. Package-private for direct unit testing with stub
     * classes exposing one name, the other, or neither.
     */
    static Method ssMethodAny(Class<?> cls, int paramCount, String... names)
    {
        for (String name : names)
        {
            Method m = ssMethod(cls, name, paramCount);
            if (m != null)
            {
                return m;
            }
        }
        return null;
    }

    /**
     * Resolves and invokes the version-tolerant "create a new infobase" flag setter on the live
     * {@code StandaloneServerInfobase} module: {@code setCreate(boolean)} on EDT 2025.2, renamed with no
     * back-compat alias to {@code setCreateNewInfobase(boolean)} on EDT 2026.1 ({@code isCreate}/
     * {@code getInfobaseId} renames are the sibling cases of the same 2026.1 API drift — see
     * {@link StandaloneServerSupport#infobaseIdOf}). Package-private for direct unit testing with stub
     * classes exposing one name, the other, or neither.
     *
     * @param infobase the live {@code StandaloneServerInfobase} module returned by
     *            {@link #ssCreateServerWithInfobase}
     * @param value the flag value to set (always {@code true} from {@link #ssMaterializeInfobase})
     * @throws IllegalStateException when NEITHER setter name resolves — names both tried methods so the
     *             failure is diagnosable without a javap session
     */
    static void ssSetCreateFlag(Object infobase, boolean value)
        throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        Method setter = ssMethodAny(infobase.getClass(), 1, "setCreate", "setCreateNewInfobase"); //$NON-NLS-1$ //$NON-NLS-2$
        if (setter == null)
        {
            throw new IllegalStateException(
                "Neither StandaloneServerInfobase.setCreate nor setCreateNewInfobase was found — " //$NON-NLS-1$
                    + "the standalone-server API may have changed; the served infobase could not be " //$NON-NLS-1$
                    + "created."); //$NON-NLS-1$
        }
        try
        {
            setter.invoke(infobase, Boolean.valueOf(value));
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
     * Ensures the module config's database IS a create-template one before the behaviour delegate runs
     * the physical create. On EDT 2026.1 {@code createServerWithInfobase} builds the config with a PLAIN
     * {@code FileDatabase}, but the delegate's {@code createStandaloneServerInfobase} casts the config's
     * database to {@code ICreateTemplateDatabase} — a live {@code ClassCastException} without this swap.
     * When needed, a {@code FileCreateTemplateDatabase} (javap-verified identical on 2025.2 and 2026.1;
     * it {@code extends FileDatabase}, so the swap is harmless on 2025.2 too) is instantiated via the
     * live database object's OWN classloader (same bundle — never {@code Class.forName}), the directory
     * is copied across the {@code getConfigDirectory}/{@code getPath} rename
     * ({@link #ssCopyDatabaseDirectory}), and {@code Config.setDatabase} (present on both versions)
     * installs it. ONLY the mode='create' materialization path calls this — the register path must
     * NEVER get a create-template database.
     *
     * <p>Decision logic ({@code null} database untouched — the delegate then fails with its own honest
     * error; already-a-create-template database untouched — the 2025.2-compatible/future-proof path) is
     * split into the headless-testable {@link #ssIsCreateTemplateDatabase}/{@link #ssCopyDatabaseDirectory};
     * the bundle class-loading step itself is live-verified only. BEST-EFFORT: any failure is logged and
     * swallowed — on 2025.2 a plain {@code FileDatabase} still materializes fine (never regress that),
     * and on 2026.1 the delegate then surfaces its own cast error.
     *
     * @param infobase the live {@code StandaloneServerInfobase} module returned by
     *            {@link #ssCreateServerWithInfobase}
     */
    static void ssEnsureCreateTemplateDatabase(Object infobase)
    {
        try
        {
            Object cfg = ssInvoke(infobase, "getStandaloneServerConfiguration", 0); //$NON-NLS-1$
            if (cfg == null)
            {
                return;
            }
            Object db = ssInvoke(cfg, "getDatabase", 0); //$NON-NLS-1$
            if (db == null)
            {
                // Leave as-is: the behaviour delegate fails with its own honest error on a missing DB.
                return;
            }
            if (ssIsCreateTemplateDatabase(db.getClass()))
            {
                // Already create-template capable — nothing to do (also future-proof).
                return;
            }
            Class<?> templateClass =
                db.getClass().getClassLoader().loadClass(CREATE_TEMPLATE_DATABASE_CLASS);
            Object templateDb = templateClass.getDeclaredConstructor().newInstance();
            ssCopyDatabaseDirectory(db, templateDb);
            ssInvoke(cfg, "setDatabase", 1, templateDb); //$NON-NLS-1$
        }
        catch (Throwable t) // NOSONAR deliberate catch-all at a reflective/best-effort boundary
        {
            Activator.logError("create_infobase: could not swap the standalone-server database to a " //$NON-NLS-1$
                + "FileCreateTemplateDatabase (best-effort; on 2026.1 the create may fail with an " //$NON-NLS-1$
                + "ICreateTemplateDatabase cast error)", t); //$NON-NLS-1$
        }
    }

    /**
     * Whether {@code cls} (or any superclass / (super)interface of it) is the create-template marker
     * interface {@code ICreateTemplateDatabase} — matched by SIMPLE name (the live FQN is
     * {@code com.e1c.g5.v8.dt.platform.standaloneserver.core.config.ICreateTemplateDatabase}; the type
     * is intentionally not imported, so {@code instanceof} is impossible, and the simple-name match
     * keeps the check unit-testable with stub interfaces). Package-private for direct unit testing.
     */
    static boolean ssIsCreateTemplateDatabase(Class<?> cls)
    {
        if (cls == null)
        {
            return false;
        }
        if (cls.isInterface() && CREATE_TEMPLATE_DATABASE_INTERFACE.equals(cls.getSimpleName()))
        {
            return true;
        }
        for (Class<?> iface : cls.getInterfaces())
        {
            if (ssIsCreateTemplateDatabase(iface))
            {
                return true;
            }
        }
        return ssIsCreateTemplateDatabase(cls.getSuperclass());
    }

    /**
     * Copies the file database's on-disk directory from {@code from} to {@code to}, version-tolerant
     * across the {@code FileDatabase} 2025.2 -> 2026.1 accessor rename: read via
     * {@code getConfigDirectory()} (2025.2) OR {@code getPath()} (2026.1), write via
     * {@code setConfigDirectory(String)} OR {@code setPath(String)} — each side resolved independently
     * with {@link #ssMethodAny}. A missing accessor on either side, or a {@code null} directory value,
     * degrades to a no-op (best-effort — the caller already treats the whole swap as best-effort).
     * Package-private for direct unit testing with stubs exposing either accessor generation.
     */
    static void ssCopyDatabaseDirectory(Object from, Object to)
        throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        Method read = ssMethodAny(from.getClass(), 0, "getConfigDirectory", "getPath"); //$NON-NLS-1$ //$NON-NLS-2$
        Method write = ssMethodAny(to.getClass(), 1, "setConfigDirectory", "setPath"); //$NON-NLS-1$ //$NON-NLS-2$
        if (read == null || write == null)
        {
            return;
        }
        try
        {
            Object dir = read.invoke(from);
            if (dir != null)
            {
                write.invoke(to, dir);
            }
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

    /**
     * Builds the "no standalone-server runtime registered" error message. Behaviour-identical to the
     * former inline string concatenation, including the optional {@code for version '...'} fragment.
     *
     * @param platform the requested platform version mask (may be {@code null}/empty)
     * @return the error message
     */
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
     * builds the result for the standalone-server path. Uses the same short bounded re-poll as the
     * file path to absorb the provision-delegate listener race, and the same three-way report
     * (issue #412): bound, measured absent (an ERROR that keeps the payload and the endpoint), or
     * unverified. Package-visible so the unit tests can drive all three outcomes.
     */
    static String buildStandaloneServerResult(ResultContext rc, int actualPort, String webUrl,
            boolean setDefault, boolean register, Credentials credentials)
    {
        // Read back the applications (bounded re-poll) and locate the just-created wst-server.
        ApplicationReadBack readBack = pollForNewApplication(rc.appManager, rc.project,
            app -> isMatchingNewServerApp(app, rc.infobaseName));

        // Optionally set the new standalone server as the project's default application. A wst-server
        // application is an ordinary IApplication, so the same setDefaultApplication API the file path
        // uses works here too. Non-fatal: the server itself is already created.
        String setDefaultNote = setDefault ? applySetDefault(rc.appManager, rc.project, readBack) : null;

        // Optionally store infobase connection credentials (issue #275): standaloneServer +
        // mode='register' ONLY — execute() already rejects credentials with mode='create'. Targets
        // the READ-BACK wst-server IApplication (not the FILE ibRef built earlier for the create
        // call) — InfobaseAccessSupport.storeCredentials(IApplication, ...) adapts IT to the
        // InfobaseReference that EDT's own launch path (ServerApplicationBehaviourDelegate) resolves.
        String credNote = register ? storeStandaloneCredentialsIfRequested(readBack.app, credentials) : null;

        String note = concatNotes(setDefaultNote, credNote);
        String verb = register ? ACTION_REGISTERED : ACTION_CREATED;
        String subject = standaloneServerSubject(rc.infobaseName, rc.infobaseDir, register);

        // Same three-way report as the file path (issue #412): the standalone server surfaces through
        // the SAME provision-delegate listener, so it has the same "requested, never appeared" state.
        if (readBack.outcome == BindingOutcome.NOT_BOUND)
        {
            ToolResult error = notBoundResult(rc, verb, readBack, subject,
                notBoundMessage(subject, rc.projectName, register) + note)
                    .put(KEY_APPLICATION_KIND, KIND_STANDALONE_SERVER);
            putStandaloneEndpoint(error, actualPort, webUrl);
            return error.toJson();
        }

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, verb)
            .put(KEY_APPLICATION_KIND, KIND_STANDALONE_SERVER)
            .put(McpKeys.PROJECT, rc.projectName)
            .put(KEY_INFOBASE_FILE, rc.infobaseDir.toAbsolutePath().toString())
            .put(KEY_INFOBASE_NAME, rc.infobaseName);
        putApplications(result, readBack);

        putStandaloneEndpoint(result, actualPort, webUrl);
        String newAppId = readBack.appId();
        if (newAppId != null)
        {
            result.put(McpKeys.APPLICATION_ID, newAppId);
        }

        String message;
        if (readBack.outcome == BindingOutcome.BOUND)
        {
            result.put(KEY_BOUND_TO_PROJECT, true);
            message = buildStandaloneServerMessage(rc.projectName, subject, actualPort, webUrl,
                note.isEmpty() ? null : note, register);
        }
        else
        {
            // UNVERIFIED: boundToProject is deliberately ABSENT — we did not establish either answer.
            message = unverifiedMessage(standaloneServerSubjectWithEndpoint(subject, actualPort, webUrl),
                rc.projectName, readBack.unverifiedReason) + note;
        }
        result.put(McpKeys.MESSAGE, message);

        return result.toJson();
    }

    /**
     * Adds the standalone server's endpoint to a result: the ACTUAL auto-allocated port (only when it
     * could be resolved from the web URL — the requested/hint port is never echoed, EDT ignores it for
     * a FILE-backed standalone server) and the web URL itself. Also carried on the not-bound error, so
     * a caller keeps the endpoint of a server that was registered but has no project application.
     * Resolved, not probed: the URL is what EDT configured, not proof that anything answers on it.
     */
    private static void putStandaloneEndpoint(ToolResult result, int actualPort, String webUrl)
    {
        if (actualPort > 0)
        {
            result.put("port", actualPort); //$NON-NLS-1$
        }
        if (webUrl != null)
        {
            result.put("webUrl", webUrl); //$NON-NLS-1$
        }
    }

    /**
     * Whether the application is the JUST-created standalone server, identified by the wst-server type
     * plus an exact name match against the new infobase — NOT merely the first wst-server app, which
     * could be a pre-existing standalone server already bound to this project. Read-only.
     */
    private static MatchResult isMatchingNewServerApp(IApplication app, String infobaseName)
    {
        String typeId = app.getType() != null ? app.getType().getId() : null;
        // Always decidable: the identity is the type plus the name, both plain reads that cannot
        // half-fail, so there is no UNDECIDABLE case here.
        return StandaloneServerSupport.WST_SERVER_APP_TYPE.equals(typeId)
            && infobaseName.equals(app.getName()) ? MatchResult.MATCH : MatchResult.NO_MATCH;
    }

    /**
     * Stores infobase connection credentials against the READ-BACK wst-server application (issue
     * #275) when the caller supplied any of {@code user}/{@code password}/{@code access}, returning
     * a note to append to the result message — a success note, a non-fatal WARNING when the store
     * failed (or the read-back produced no application to store them against), or
     * {@code null} when no credentials were requested. Credential storage never fails the
     * standalone-server registration itself (the server itself is already registered). Mirrors
     * {@link #storeCredentialsIfRequested(InfobaseReference, Credentials, boolean)}, the plain
     * file-infobase equivalent.
     *
     * @param application the read-back wst-server application ({@code null} when the read-back
     *            produced none — measured absent, unreadable or interrupted)
     * @param credentials the requested connection credentials (any field may be {@code null}/empty)
     * @return a message note, or {@code null} when no credentials were requested
     */
    private static String storeStandaloneCredentialsIfRequested(IApplication application,
            Credentials credentials)
    {
        if (!credentials.any())
        {
            return null;
        }
        if (application == null)
        {
            // Why it was not available (measured absent, unreadable, interrupted) is the read-back's
            // story, told by the message this note is appended to — do not restate it as a fact here.
            return " WARNING: connection credentials were NOT stored: the new standalone-server " //$NON-NLS-1$
                + "application was not available from the read-back - check get_applications and " //$NON-NLS-1$
                + "store them with set_infobase_credentials."; //$NON-NLS-1$
        }
        String error = InfobaseAccessSupport.storeCredentials(application, credentials.user, credentials.password,
            InfobaseAccessSupport.parseAccess(credentials.access));
        if (error != null)
        {
            return " WARNING: connection credentials were NOT stored: " + error; //$NON-NLS-1$
        }
        return " Stored connection credentials for user '" + (credentials.user == null ? "" : credentials.user) //$NON-NLS-1$ //$NON-NLS-2$
            + "' (change them later with set_infobase_credentials)."; //$NON-NLS-1$
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
     * What happened to the DATABASE on the standalone-server path, without any claim about the project
     * binding: for mode='create' the server CREATED a new infobase, for mode='register' it was
     * REGISTERED over the EXISTING one (no new database was written). The binding clause is added by
     * the caller only when the read-back established it (issue #412).
     */
    private static String standaloneServerSubject(String infobaseName, Path infobaseDir, boolean register)
    {
        return register
            ? "Registered a standalone server over the existing infobase '" + infobaseName //$NON-NLS-1$
                + "' at '" + infobaseDir.toAbsolutePath() + "'" //$NON-NLS-1$ //$NON-NLS-2$
            : "Standalone server for infobase '" + infobaseName //$NON-NLS-1$
                + "' created at '" + infobaseDir.toAbsolutePath() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The same subject plus the resolved endpoint, so the unverified-binding wording still reports the
     * ACTUAL auto-allocated web port and URL (the registration happened even when the project binding
     * could not be read back). The endpoint is the one EDT resolved, not one this tool probed.
     */
    private static String standaloneServerSubjectWithEndpoint(String subject, int actualPort, String webUrl)
    {
        return subject
            + (actualPort > 0 ? " (web port " + actualPort + ")" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (webUrl != null ? ", web URL " + webUrl : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Builds the human-readable status message for the standalone-server result when the read-back
     * ESTABLISHED the binding (read-only string assembly). Appends {@code note} (the setDefault and
     * credentials notes, already joined) when non-null.
     */
    private static String buildStandaloneServerMessage(String projectName, String subject,
            int actualPort, String webUrl, String note, boolean register)
    {
        String lead = subject
            + (register ? " and bound it to project '" : " and bound to project '") //$NON-NLS-1$ //$NON-NLS-2$
            + projectName + "'"; //$NON-NLS-1$
        return lead
            + (actualPort > 0 ? " (web port " + actualPort + ")" : "") + "." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + (webUrl != null ? " Web URL for HTTP testing: " + webUrl + "." : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " To load the configuration, use the coordinated launch flow (debug_launch or " //$NON-NLS-1$
            + "run_yaxunit_tests with updateBeforeLaunch=true) rather than a bare update_database, " //$NON-NLS-1$
            + "which would start the server in RUN mode." //$NON-NLS-1$
            + (note != null ? note : ""); //$NON-NLS-1$
    }

    /**
     * Immutable parameter-object bundling the identity + read-back inputs shared by the success-result
     * builders ({@link #buildSuccessResult} and {@link #buildStandaloneServerResult}): the project
     * name/handle, the infobase directory and display name, and the {@link IApplicationManager} used
     * for the application read-back. Keeps those builders under the parameter-count threshold without
     * changing what they compute. Package-visible so the unit tests can drive the result builders.
     */
    static final class ResultContext
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

    /** Maximum re-poll attempts for the provision-delegate listener race after associate(). */
    private static final int READ_BACK_MAX_POLLS = 5;

    /** Delay between read-back re-poll attempts (ms). */
    private static final long READ_BACK_POLL_DELAY_MS = 300;

    /**
     * What the post-association read-back ESTABLISHED about the binding (issue #412). The tool used to
     * collapse all three into one unconditional success that claimed the infobase was "bound to
     * project" even when its own read-back had just shown it was not.
     */
    private enum BindingOutcome
    {
        /** The new application was read back: the infobase IS an application of the project. */
        BOUND,
        /**
         * The poll budget was spent and the LAST read compared every application without finding
         * this one: absence is MEASURED, not assumed. (Only the last read has to be fully
         * comparable — each read is a whole snapshot, so an earlier one that could not identify
         * something is superseded, not carried forward.)
         */
        NOT_BOUND,
        /**
         * Nothing was established: the read-back failed, or was cut short before the budget was
         * spent, or it listed an application whose identity could not be compared with the new
         * infobase. None of those is evidence of a missing application.
         */
        UNVERIFIED
    }

    /**
     * What comparing ONE application against the new infobase produced. A comparison that could not be
     * made is deliberately NOT folded into {@link #NO_MATCH}: since the read-back now decides whether
     * the call succeeds, "I could not tell" must not be reported as "it is not there" (issue #412).
     */
    private enum MatchResult
    {
        /** This application IS the one that was just created/registered. */
        MATCH,
        /** This application is decidably a different one. */
        NO_MATCH,
        /** The comparison could not be made (missing/unreadable identity), so nothing was established. */
        UNDECIDABLE
    }

    /** Identifies the JUST-created application among the project's applications (tri-state). */
    private interface ApplicationMatcher
    {
        MatchResult match(IApplication app);
    }

    /**
     * Outcome of the bounded application read-back: the {@code applications} echo (the LAST snapshot a
     * read actually produced — {@code null} when no read produced one), the matched new application
     * ({@code null} unless {@link BindingOutcome#BOUND}), and what that read-back established.
     */
    private static final class ApplicationReadBack
    {
        final JsonArray appsArray;
        final IApplication app;
        final BindingOutcome outcome;
        /** Why nothing was established; {@code null} unless {@link BindingOutcome#UNVERIFIED}. */
        final String unverifiedReason;

        ApplicationReadBack(JsonArray appsArray, IApplication app, BindingOutcome outcome,
                String unverifiedReason)
        {
            this.appsArray = appsArray;
            this.app = app;
            this.outcome = outcome;
            this.unverifiedReason = unverifiedReason;
        }

        /**
         * The matched application's id, or {@code null} when nothing matched OR the platform gave the
         * application no id. A matched application with a {@code null} id is still {@link
         * BindingOutcome#BOUND}: the binding is established by the MATCH, not by the id.
         */
        String appId()
        {
            return app != null ? app.getId() : null;
        }
    }

    /**
     * Reads back the applications for the project and builds the result for the file-infobase path.
     * Uses a short bounded re-poll to absorb the provision-delegate listener race that can keep the
     * new application invisible immediately after associate().
     *
     * <p>The report follows what the read-back ESTABLISHED (issue #412), instead of claiming the
     * infobase is "bound to project" unconditionally:
     * <ul>
     *   <li>{@link BindingOutcome#BOUND} — success, {@code boundToProject:true}, and
     *       {@code applicationId} whenever the platform gave the application one;</li>
     *   <li>{@link BindingOutcome#NOT_BOUND} — an ERROR carrying {@code boundToProject:false} and the
     *       full payload (what happened to the database, where it is, its name), because the database
     *       exists and the caller must not be told otherwise;</li>
     *   <li>{@link BindingOutcome#UNVERIFIED} — success WITHOUT {@code boundToProject}: a read that
     *       failed, a poll cut short by an interrupt, or an application whose identity could not be
     *       compared does not establish absence, and a false refusal costs more than a missing
     *       claim.</li>
     * </ul>
     *
     * <p>Package-visible so the unit tests can drive all three outcomes through a stubbed
     * {@link IApplicationManager}.
     *
     * @param setDefault whether the caller asked for the new application to become the project default
     * @param credNote optional credentials note to append to the message ({@code null} = none)
     */
    static String buildSuccessResult(ResultContext rc, InfobaseReference ibRef, boolean setDefault,
            boolean register, String credNote)
    {
        ApplicationReadBack readBack = pollForNewApplication(rc.appManager, rc.project,
            app -> isMatchingNewInfobaseApp(app, ibRef));

        String setDefaultNote = setDefault ? applySetDefault(rc.appManager, rc.project, readBack) : null;
        String note = concatNotes(setDefaultNote, credNote);
        String verb = register ? ACTION_REGISTERED : ACTION_CREATED;
        String subject = "Infobase '" + rc.infobaseName + "' " + verb + " at '" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + rc.infobaseDir.toAbsolutePath() + "'"; //$NON-NLS-1$

        if (readBack.outcome == BindingOutcome.NOT_BOUND)
        {
            return notBoundResult(rc, verb, readBack, subject,
                notBoundMessage(subject, rc.projectName, register) + note).toJson();
        }

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, verb)
            .put(McpKeys.PROJECT, rc.projectName)
            .put(KEY_INFOBASE_FILE, rc.infobaseDir.toAbsolutePath().toString())
            .put(KEY_INFOBASE_NAME, rc.infobaseName);
        putApplications(result, readBack);

        String newAppId = readBack.appId();
        if (newAppId != null)
        {
            result.put(McpKeys.APPLICATION_ID, newAppId);
        }

        String message;
        if (readBack.outcome == BindingOutcome.BOUND)
        {
            result.put(KEY_BOUND_TO_PROJECT, true);
            // The chaining advice needs an applicationId to chain WITH. It is present in every case
            // the platform gives the application an id (so this text is unchanged for real callers),
            // but a bound application without one cannot be handed to update_database yet.
            message = subject + " and bound to project '" + rc.projectName //$NON-NLS-1$
                + (newAppId != null
                    ? "'. Use update_database to push the configuration into the infobase." //$NON-NLS-1$
                    : "'. The read-back reported no id for the application - look it up with " //$NON-NLS-1$
                        + "get_applications before update_database."); //$NON-NLS-1$
        }
        else
        {
            // UNVERIFIED: boundToProject is deliberately ABSENT — we did not establish either answer.
            message = unverifiedMessage(subject, rc.projectName, readBack.unverifiedReason);
        }
        result.put(McpKeys.MESSAGE, message + note);

        return result.toJson();
    }

    /**
     * Runs the bounded read-back and classifies what it established (issue #412). The loop is the
     * original one — same budget, same early exits — but its exits are now told apart: a match is
     * {@link BindingOutcome#BOUND}, a spent budget whose LAST read compared every application
     * without a match is {@link BindingOutcome#NOT_BOUND}, and a failed or interrupted read is
     * {@link BindingOutcome#UNVERIFIED} (it did not measure the full budget, so absence is not
     * established) — with the reason carried along, because a failed read and an interrupted poll
     * are different stories.
     *
     * @param matcher identifies the JUST-created application among the project's applications
     */
    private static ApplicationReadBack pollForNewApplication(IApplicationManager appManager,
            IProject project, ApplicationMatcher matcher)
    {
        // Set when SOME application could not be compared with the new infobase: the reads then
        // listed applications we could not identify, so "none of them is ours" was never established.
        boolean[] undecidable = new boolean[1];
        // The echo is the LAST snapshot a read actually produced, and stays null while there is
        // none: a failed read must not be reported as an empty list of applications — that would
        // re-encode "could not look" as "looked and found nothing", the very confusion this fixes.
        JsonArray appsArray = null;
        IApplication[] newAppHolder = new IApplication[1];
        boolean readCompleted = false;
        boolean cutShort = false;

        // Short bounded re-poll: the provision-delegate listener fires asynchronously after
        // associate(), so the new application may not be visible on the first read.
        for (int poll = 0; poll < READ_BACK_MAX_POLLS; poll++) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
        {
            JsonArray attempt = new JsonArray();
            newAppHolder[0] = null;

            undecidable[0] = false;
            readCompleted =
                readBackApplications(appManager, project, matcher, attempt, newAppHolder, undecidable);
            if (readCompleted)
            {
                appsArray = attempt;
            }
            else
            {
                break; // Read failed (logged) — stop re-polling; absence is NOT established.
            }
            if (newAppHolder[0] != null)
            {
                break; // Found the new application — no need to re-poll.
            }
            if (poll < READ_BACK_MAX_POLLS - 1 && !sleepBetweenPolls())
            {
                cutShort = true; // Interrupted — the budget was not spent, so absence is NOT established.
                break;
            }
        }

        if (newAppHolder[0] != null)
        {
            return new ApplicationReadBack(appsArray, newAppHolder[0], BindingOutcome.BOUND, null);
        }
        if (!readCompleted)
        {
            return new ApplicationReadBack(appsArray, null, BindingOutcome.UNVERIFIED,
                "the application read-back could not be completed - the failure is in the EDT " //$NON-NLS-1$
                    + "error log"); //$NON-NLS-1$
        }
        if (cutShort)
        {
            // Interrupted: the reads that DID run saw no application, but the budget that absorbs the
            // listener race was not spent — and nothing failed, so there is no log entry to point at.
            return new ApplicationReadBack(appsArray, null, BindingOutcome.UNVERIFIED,
                "the read-back was interrupted before its budget was spent"); //$NON-NLS-1$
        }
        if (undecidable[0])
        {
            // The last read listed an application that could not be compared with the new infobase,
            // so it was never established that none of them is ours.
            return new ApplicationReadBack(appsArray, null, BindingOutcome.UNVERIFIED,
                "one of the project's applications could not be compared with the new infobase " //$NON-NLS-1$
                    + "(an identity could not be read)"); //$NON-NLS-1$
        }
        return new ApplicationReadBack(appsArray, null, BindingOutcome.NOT_BOUND, null);
    }

    /**
     * Builds the ERROR result for {@link BindingOutcome#NOT_BOUND}: the infobase exists but the project
     * has no application for it. The payload is deliberately kept (issue #412) — {@code action} says
     * what happened to the DATABASE, {@code infobaseFile}/{@code infobaseName} say which one, and
     * {@code applications} is the evidence — so that "this failed" can never be read as "nothing
     * happened".
     *
     * @param subject what happened to the database, for the log line
     * @param errorText the full error text (the not-bound wording plus any setDefault/credentials
     *            notes); it is carried as {@code error}, which is also what the client shows as text —
     *            no second {@code message} copy to drift from it
     */
    private static ToolResult notBoundResult(ResultContext rc, String verb,
            ApplicationReadBack readBack, String subject, String errorText)
    {
        Activator.logError("create_infobase: " + subject //$NON-NLS-1$
            + " but no application appeared for project " + rc.projectName //$NON-NLS-1$
            + " within the read-back budget", null); //$NON-NLS-1$
        ToolResult result = ToolResult.error(errorText)
            .put(McpKeys.ACTION, verb)
            .put(McpKeys.PROJECT, rc.projectName)
            .put(KEY_INFOBASE_FILE, rc.infobaseDir.toAbsolutePath().toString())
            .put(KEY_INFOBASE_NAME, rc.infobaseName)
            .put(KEY_BOUND_TO_PROJECT, false);
        putApplications(result, readBack);
        return result;
    }

    /**
     * Echoes the applications the read-back saw — and OMITS the key entirely when no read produced a
     * snapshot at all. An empty array would say "the project has no applications", which is a claim
     * a failed read never made.
     */
    private static void putApplications(ToolResult result, ApplicationReadBack readBack)
    {
        if (readBack.appsArray != null)
        {
            result.put(KEY_APPLICATIONS, readBack.appsArray);
        }
    }

    /**
     * The {@link BindingOutcome#NOT_BOUND} wording: both facts (the database is there, the application
     * is not), what it blocks, and what to do next. It does NOT offer {@code delete_infobase} as the
     * cure — that tool resolves its target only among the project's applications, so it cannot address
     * an infobase that has none.
     */
    private static String notBoundMessage(String subject, String projectName, boolean register)
    {
        return subject + ", but it did NOT appear as an application of project '" + projectName //$NON-NLS-1$
            + "': the association was requested and raised no error, yet the project's applications " //$NON-NLS-1$
            + "were read back " + READ_BACK_MAX_POLLS + " times over " //$NON-NLS-1$ //$NON-NLS-2$
            + (READ_BACK_MAX_POLLS - 1) * READ_BACK_POLL_DELAY_MS
            + " ms, and the last read compared every one of them without finding it. " //$NON-NLS-1$
            + (register
                ? "The existing database is untouched. " //$NON-NLS-1$
                : "The database files were created and are intact - do NOT create them again. ") //$NON-NLS-1$
            + "Nothing can target this infobase until its application appears: update_database / " //$NON-NLS-1$
            + "create_launch_config / debug_launch address an application by applicationId, and " //$NON-NLS-1$
            + "this infobase has none. Call " //$NON-NLS-1$
            + "get_applications('" + projectName //$NON-NLS-1$
            + "') to re-check (applications surface asynchronously); if it stays absent, check the " //$NON-NLS-1$
            + "EDT error log - the registration completed, but the project's application provider " //$NON-NLS-1$
            + "did not surface it."; //$NON-NLS-1$
    }

    /**
     * The {@link BindingOutcome#UNVERIFIED} wording: the binding was neither confirmed nor refuted. It
     * claims nothing about the application, states the actual reason the read-back settled nothing
     * (a failed read and an interrupted poll are different, and only one of them leaves a log entry),
     * and names the one call that settles it.
     */
    private static String unverifiedMessage(String subject, String projectName, String reason)
    {
        return subject + ". The association with project '" + projectName //$NON-NLS-1$
            + "' was requested and raised no error, but " + reason //$NON-NLS-1$
            + ", so the binding is UNVERIFIED - this call did not establish whether the application " //$NON-NLS-1$
            + "is there. Call get_applications('" + projectName //$NON-NLS-1$
            + "') to confirm it before chaining into update_database / create_launch_config / " //$NON-NLS-1$
            + "debug_launch."; //$NON-NLS-1$
    }

    /** Joins the two optional message notes, either of which may be {@code null}. */
    private static String concatNotes(String first, String second)
    {
        return (first != null ? first : "") + (second != null ? second : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reads the project's applications once, populating {@code appsArray} with one JSON object per
     * application (same shape as {@code get_applications}) and storing the first application the
     * {@code matcher} accepts in {@code newAppHolder[0]} (left {@code null} when not yet visible).
     * Read-only.
     *
     * @return {@code true} if the read produced a snapshot to inspect (whether or not the new
     *         application was in it), {@code false} if reading the applications failed or produced no
     *         snapshot at all (already logged) so the caller stops re-polling and reports UNVERIFIED
     */
    private static boolean readBackApplications(IApplicationManager appManager, IProject project,
            ApplicationMatcher matcher, JsonArray appsArray, IApplication[] newAppHolder,
            boolean[] undecidable)
    {
        try
        {
            List<IApplication> applications = appManager.getApplications(project);
            if (applications == null)
            {
                // No snapshot at all is not the same as an empty one: it establishes nothing, so it
                // must not be counted as a read that MEASURED the application to be absent.
                Activator.logError("create_infobase: application read-back returned no list", null); //$NON-NLS-1$
                return false;
            }
            for (IApplication app : applications)
            {
                appsArray.add(toApplicationJson(appManager, app));
                // Identify the newly created application (by infobase reference for a file
                // infobase, by type + name for a standalone server).
                if (newAppHolder[0] == null)
                {
                    MatchResult match = matcher.match(app);
                    if (match == MatchResult.MATCH)
                    {
                        newAppHolder[0] = app;
                    }
                    else if (match == MatchResult.UNDECIDABLE)
                    {
                        undecidable[0] = true;
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
     * Whether the application is the newly created FILE infobase application, identified by type and
     * a connection-string match against {@code ibRef}. Read-only.
     *
     * <p>A different type is a decidable {@link MatchResult#NO_MATCH}; an infobase application whose
     * identity cannot be read is {@link MatchResult#UNDECIDABLE}, so an unreadable connection string
     * cannot masquerade as "this is not the one" and turn into a refusal (issue #412).
     */
    private static MatchResult isMatchingNewInfobaseApp(IApplication app, InfobaseReference ibRef)
    {
        if (!(app instanceof IInfobaseApplication)
            || !INFOBASE_APP_TYPE.equals(app.getType() != null ? app.getType().getId() : null))
        {
            return MatchResult.NO_MATCH;
        }
        InfobaseReference appRef = ((IInfobaseApplication)app).getInfobase();
        if (appRef == null)
        {
            return MatchResult.UNDECIDABLE;
        }
        return matchesRef(appRef, ibRef);
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
     * comparing their connection-string file path. A miss is what makes the read-back report
     * NOT_BOUND (it no longer merely skips the applicationId echo), so a comparison that could not be
     * made - an absent or unreadable connection string, or a failure while reading one - returns
     * {@link MatchResult#UNDECIDABLE} rather than a confident "different".
     */
    private static MatchResult matchesRef(InfobaseReference a, InfobaseReference b)
    {
        try
        {
            if (a.getConnectionString() == null || b.getConnectionString() == null)
            {
                return MatchResult.UNDECIDABLE;
            }
            String ca = a.getConnectionString().asConnectionString();
            String cb = b.getConnectionString().asConnectionString();
            if (ca == null || cb == null)
            {
                return MatchResult.UNDECIDABLE;
            }
            return ca.equalsIgnoreCase(cb) ? MatchResult.MATCH : MatchResult.NO_MATCH;
        }
        catch (Exception e)
        {
            Activator.logError("create_infobase: could not compare an application's infobase " //$NON-NLS-1$
                + "reference with the new one", e); //$NON-NLS-1$
            return MatchResult.UNDECIDABLE;
        }
    }
}
