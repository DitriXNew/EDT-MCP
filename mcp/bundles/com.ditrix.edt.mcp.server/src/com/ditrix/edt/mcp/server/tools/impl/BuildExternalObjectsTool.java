/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumper;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.ExternalObjectDumpSupport;
import com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.WorkspacePaths;

/**
 * Tool that builds the external data processors/reports of an EDT external-object
 * project to {@code .epf}/{@code .erf} files on disk, unattended.
 *
 * <p>Builds ONE external object (when {@code objectName} is supplied) or ALL of them
 * (when {@code objectName} is omitted) of an {@link IExternalObjectProject}, writing
 * each to a caller-chosen {@code outputDir}. This is the headless equivalent of EDT's
 * "save external data processor/report to file" action.
 *
 * <h2>Hard precondition (variant A)</h2>
 * The platform dump pipeline ({@link IExternalObjectDumper#dump}) compiles the object
 * with an associated infobase + a resolvable 1C runtime; that runtime is a HARD
 * precondition, exactly like {@code update_database}. When the dumper service cannot be
 * resolved the tool returns a graceful "not available" sentinel; when the dump fails
 * with an authentication/connection-shaped error it appends an actionable hint pointing
 * at {@code create_infobase} / {@code set_infobase_credentials}.
 *
 * <h2>Unattended-safety</h2>
 * The dump loop runs in a background {@link Job} (never on the JSON-RPC / UI thread)
 * with a bounded timeout. The always-on {@link InfobaseAuthDialogSuppressor} and the
 * armed {@link LaunchUpdateDialogAutoConfirmer} keep any blocking EDT/1C modal from
 * hanging the call. Per-object success/failure is captured honestly: the response lists
 * each object's output path on success or its error on failure, and {@code success} is
 * {@code false} when any object failed.
 *
 * <p>The dumper resolution, the per-object output file name and the {@code .epf}/{@code
 * .erf} extension are owned by {@link ExternalObjectDumpSupport}; this tool only
 * orchestrates resolution, the BM read enumeration, the output directory and the Job.
 *
 * <p><b>Golden snapshot:</b> the {@code tools/list} golden ({@code tools_list.golden.json})
 * must be regenerated on the live stand after adding this tool — it cannot be hand-edited.
 */
public class BuildExternalObjectsTool implements IMcpTool
{
    public static final String NAME = "build_external_objects"; //$NON-NLS-1$

    /** Input param: name of a single external object to build (omit = build all). */
    private static final String KEY_OBJECT_NAME = "objectName"; //$NON-NLS-1$

    /** Input/output param: filesystem directory the built .epf/.erf files are written to. */
    private static final String KEY_OUTPUT_DIR = "outputDir"; //$NON-NLS-1$

    /** Input param: whether to stamp the build time into each object's Comment (optional, default true). */
    private static final String KEY_RECORD_BUILD_TIME = "recordBuildTime"; //$NON-NLS-1$

    /** Output key: per-object results (name -> path on success, or an error entry). */
    private static final String KEY_RESULTS = "results"; //$NON-NLS-1$

    /** Output key: count of objects built successfully. */
    private static final String KEY_BUILT = "built"; //$NON-NLS-1$

    /** Output key: count of objects that failed to build. */
    private static final String KEY_FAILED = "failed"; //$NON-NLS-1$

    /** Per-result key: external object name. */
    private static final String RES_NAME = "name"; //$NON-NLS-1$

    /** Per-result key: written file path (present on success). */
    private static final String RES_PATH = "path"; //$NON-NLS-1$

    /** Per-result key: error message (present on failure). */
    private static final String RES_ERROR = "error"; //$NON-NLS-1$

    /** Per-result key: whether this object built successfully. */
    private static final String RES_SUCCESS = "success"; //$NON-NLS-1$

    /** Per-result key: build duration for this object in milliseconds. */
    private static final String RES_DURATION_MS = "durationMs"; //$NON-NLS-1$

    /** Timeout (ms) for the background build Job (compile + dump of all objects). */
    private static final long BUILD_TIMEOUT_MS = 300_000L;

    /**
     * Prefix written into each built object's Comment so the deliverable .epf/.erf records when it was
     * built (the maintainer's "version bump"). The label is real 1C object-comment DATA ("Время сборки: "),
     * written as Unicode escapes (like {@code MetadataTypeUtils} does for its Cyrillic type tokens) for
     * non-UTF-8 Tycho build safety; the runtime string is unchanged.
     */
    private static final String STAMP_PREFIX = "\u0412\u0440\u0435\u043C\u044F \u0441\u0431\u043E\u0440\u043A\u0438: "; //$NON-NLS-1$

    /** Build-stamp timestamp format (local, human-readable — to see which build was the latest). */
    private static final DateTimeFormatter STAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Build (compile to disk) the external data processors/reports of an EDT external-object " //$NON-NLS-1$
            + "project to .epf/.erf files. Build ONE object with objectName, or ALL of them when " //$NON-NLS-1$
            + "objectName is omitted. Requires an associated infobase + a resolvable 1C runtime " //$NON-NLS-1$
            + "(like update_database): if missing, set it up with create_infobase / " //$NON-NLS-1$
            + "set_infobase_credentials. Full parameters and examples: " //$NON-NLS-1$
            + "call get_tool_guide('build_external_objects')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT external-object project name to build (required). Must be a project created with " //$NON-NLS-1$
                    + "create_project projectKind=externalObjects.", true) //$NON-NLS-1$
            .stringProperty(KEY_OBJECT_NAME,
                "Name of a single external data processor/report to build. Omit to build ALL external " //$NON-NLS-1$
                    + "objects of the project.") //$NON-NLS-1$
            .stringProperty(KEY_OUTPUT_DIR,
                "Filesystem directory the built .epf/.erf files are written to (required). Relative " //$NON-NLS-1$
                    + "paths are resolved to absolute; the directory is created if missing. If the path " //$NON-NLS-1$
                    + "exists but is a file, the call errors.", true) //$NON-NLS-1$
            .booleanProperty(KEY_RECORD_BUILD_TIME,
                "Optional, default true. When true, the build time is written into each built object's " //$NON-NLS-1$
                    + "Comment property (\"" + STAMP_PREFIX + "<yyyy-MM-dd HH:mm:ss>\") and the .mdo is " //$NON-NLS-1$ //$NON-NLS-2$
                    + "flushed to disk, so the object records when it was last built. Set false to build " //$NON-NLS-1$
                    + "without modifying the object (no Comment change, no .mdo diff); the build time is " //$NON-NLS-1$
                    + "still reported in the response message either way.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", //$NON-NLS-1$
                "true only when every requested object was built; false when any object failed.", true) //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT, "Target EDT external-object project name.") //$NON-NLS-1$
            .stringProperty(KEY_OUTPUT_DIR, "Absolute output directory the files were written to.") //$NON-NLS-1$
            .integerProperty(KEY_BUILT, "Count of objects built successfully.") //$NON-NLS-1$
            .integerProperty(KEY_FAILED, "Count of objects that failed to build.") //$NON-NLS-1$
            .objectArrayProperty(KEY_RESULTS,
                "Per-object results: {name, success, path (on success), error (on failure)}.") //$NON-NLS-1$
            .booleanProperty("outsideWorkspace", //$NON-NLS-1$
                "Present and true when outputDir is outside the EDT workspace (a warning, not a failure).") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status message.") //$NON-NLS-1$
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
        // 1. Required arguments (projectName + outputDir; objectName is optional).
        String argErr = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_OUTPUT_DIR);
        if (argErr != null)
        {
            return argErr;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String objectName = normalizeObjectName(JsonUtils.extractStringArgument(params, KEY_OBJECT_NAME));
        String outputDirStr = JsonUtils.extractStringArgument(params, KEY_OUTPUT_DIR);
        boolean recordBuildTime =
            parseRecordBuildTime(JsonUtils.extractStringArgument(params, KEY_RECORD_BUILD_TIME));

        // 2. Normalize the output directory: absolute, reject a file path, create if missing,
        //    warn (do not reject) when it is outside the workspace. Mirrors export_configuration_to_xml.
        //    Done first (pure java.nio) so a malformed destination is rejected up front.
        OutputDirResolution outputDirResolution = resolveOutputDir(outputDirStr);
        if (outputDirResolution.errorJson != null)
        {
            return outputDirResolution.errorJson;
        }
        Path outputDir = outputDirResolution.outputDir;
        boolean outsideWorkspace = outputDirResolution.outsideWorkspace;

        // 3. Refuse only the transient BUILDING state; a missing/closed project falls through
        //    to the value-naming "Project not found" below (same idiom as update_database).
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        // 4. Resolve the project and ensure it is an external-object project.
        ResolvedProject resolved = resolveExternalObjectProject(projectName);
        if (resolved.errorJson != null)
        {
            return resolved.errorJson;
        }

        // 5. Resolve the dumper (1C runtime gate). A null sentinel means the dump service/runtime
        //    is not resolvable — graceful, actionable precondition error (variant A).
        IExternalObjectDumper dumper = ExternalObjectDumpSupport.resolveDumper();
        if (dumper == null)
        {
            return ToolResult.error("External object dumper is not available: the EDT platform-services " //$NON-NLS-1$
                + "plugin or a 1C runtime is not registered. Ensure a 1C runtime is installed and an " //$NON-NLS-1$
                + "infobase is associated (create_infobase), then retry.").toJson(); //$NON-NLS-1$
        }

        // 6. Enumerate the target objects inside a BM read boundary (model reads only there).
        EnumerationResult enumeration = enumerateTargets(resolved, objectName);
        if (enumeration.errorJson != null)
        {
            return enumeration.errorJson;
        }

        // 7. Run the dump loop off the calling thread, with dialog suppressors armed and a timeout.
        return runBuild(new BuildContext(projectName, resolved.project, enumeration.bmModel, dumper,
            enumeration.targets, outputDir, outsideWorkspace, recordBuildTime));
    }

    /**
     * Parses the optional {@code recordBuildTime} argument, which defaults to {@code true}: an absent or
     * blank value, or {@code "true"} (case-insensitive), enables build-time Comment stamping; any other
     * value disables it. Keeping the default {@code true} preserves the behaviour shipped in #202 while
     * letting a caller opt out of mutating the object (issue #202 follow-up).
     *
     * @param raw the raw argument value (may be {@code null})
     * @return {@code true} to stamp the build time into each object's Comment, {@code false} to skip it
     */
    static boolean parseRecordBuildTime(String raw)
    {
        return raw == null || raw.trim().isEmpty() || "true".equalsIgnoreCase(raw.trim()); //$NON-NLS-1$
    }

    /**
     * Trims the optional {@code objectName} and collapses a now-empty value to {@code null} so the
     * enumeration falls back to "build all".
     *
     * @param objectName the raw object name (may be {@code null})
     * @return the trimmed name, or {@code null} when absent or blank
     */
    private static String normalizeObjectName(String objectName)
    {
        if (objectName == null)
        {
            return null;
        }
        String trimmed = objectName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Resolves {@code projectName} to an open {@link IExternalObjectProject}: not-found / closed /
     * service-unavailable / wrong-kind each yield a ready-to-return JSON error naming the bad value
     * and the fix.
     *
     * @param projectName the project name argument
     * @return the resolved project, or a ready-to-return JSON error
     */
    private static ResolvedProject resolveExternalObjectProject(String projectName)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ResolvedProject.failure(ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson());
        }
        if (!ctx.isOpen())
        {
            return ResolvedProject.failure(ToolResult.error("Project is closed: " + projectName //$NON-NLS-1$
                + ". Open it in EDT before building its external objects.").toJson()); //$NON-NLS-1$
        }
        IProject project = ctx.project();

        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        if (v8ProjectManager == null)
        {
            return ResolvedProject.failure(ToolResult.error(
                "IV8ProjectManager service not available. The EDT platform may not be ready.").toJson()); //$NON-NLS-1$
        }
        IV8Project v8project = v8ProjectManager.getProject(project);
        if (!(v8project instanceof IExternalObjectProject))
        {
            return ResolvedProject.failure(ToolResult.error("Project '" + projectName //$NON-NLS-1$
                + "' is not an external-object project (external data processors/reports). " //$NON-NLS-1$
                + "build_external_objects requires a project created with create_project " //$NON-NLS-1$
                + "projectKind=externalObjects.").toJson()); //$NON-NLS-1$
        }
        return ResolvedProject.ok(project, (IExternalObjectProject)v8project);
    }

    /**
     * Normalizes the output directory string to an absolute path, rejects a path that exists but is
     * a file, creates the directory if missing, and flags (does not reject) a directory outside the
     * EDT workspace. Mirrors {@code export_configuration_to_xml}.
     *
     * @param outputDirStr the raw output directory argument
     * @return the resolved directory + outside-workspace flag, or a ready-to-return JSON error
     */
    private static OutputDirResolution resolveOutputDir(String outputDirStr)
    {
        Path outputDir;
        try
        {
            outputDir = Paths.get(outputDirStr).toAbsolutePath().normalize();
            if (Files.exists(outputDir) && !Files.isDirectory(outputDir))
            {
                return OutputDirResolution.failure(
                    ToolResult.error("outputDir exists but is not a directory: " + outputDir).toJson()); //$NON-NLS-1$
            }
            Files.createDirectories(outputDir);
        }
        catch (Exception e) // NOSONAR any filesystem failure must surface as an actionable tool error
        {
            return OutputDirResolution.failure(ToolResult.error("Could not prepare outputDir '" //$NON-NLS-1$
                + outputDirStr + "': " + e.getMessage()).toJson()); //$NON-NLS-1$
        }

        boolean outsideWorkspace = WorkspacePaths.isOutsideWorkspace(outputDir);
        if (outsideWorkspace)
        {
            Activator.logWarning(NAME + ": outputDir is OUTSIDE the EDT workspace: " + outputDir //$NON-NLS-1$
                + " (trusted-caller-only — see README Security & trust model)."); //$NON-NLS-1$
        }
        return OutputDirResolution.ok(outputDir, outsideWorkspace);
    }

    /**
     * Enumerates the external objects to build inside a BM read boundary (the model is read only
     * there). When {@code objectName} is given, exactly that object is selected (a not-found error
     * naming the value otherwise, including when the project is empty). When {@code objectName} is
     * {@code null} (build ALL), every external object is selected; an empty project is NOT an error
     * here — it yields an empty target list so the build reports a clear "nothing to build" success
     * (built=0/failed=0) rather than failing, matching the headless build-all contract.
     *
     * @param resolved the resolved external-object project
     * @param objectName the single object name to build, or {@code null} for all
     * @return the resolved targets (possibly empty for build-all), or a ready-to-return JSON error
     */
    private static EnumerationResult enumerateTargets(ResolvedProject resolved, String objectName)
    {
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return EnumerationResult.failure(ToolResult.error("IBmModelManager not available").toJson()); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(resolved.project);
        if (bmModel == null)
        {
            return EnumerationResult.failure(ToolResult.error(
                "BM model not available for project: " + resolved.project.getName()).toJson()); //$NON-NLS-1$
        }

        // Read EVERY model property (name, .epf/.erf extension -> file name) INSIDE the read boundary
        // and carry it on a detached Target record. Each external object's BM id (bmGetId()) is also
        // captured here so the Job can RE-RESOLVE a fresh object handle inside a short read transaction
        // (a BM-model lookup must run in a transaction) before handing it to dump() — see dumpOne.
        return BmTransactions.read(bmModel, "BuildExternalObjects.enumerate", (tx, pm) -> //$NON-NLS-1$
        {
            Collection<MdObject> all = resolved.externalObjectProject.getExternalObjects();
            if (all == null || all.isEmpty())
            {
                if (objectName == null)
                {
                    // Build ALL of an empty project: not an error — an empty target list yields a
                    // clear "nothing to build" success (built=0/failed=0) in runBuild.
                    return EnumerationResult.ok(new ArrayList<>(), bmModel);
                }
                // A SPECIFIC object was requested but the project has none: a value-naming not-found.
                return EnumerationResult.failure(ToolResult.error("External object not found: '" //$NON-NLS-1$
                    + objectName + "'. The external-object project has no external data " //$NON-NLS-1$
                    + "processors/reports.").toJson()); //$NON-NLS-1$
            }
            List<Target> matched = new ArrayList<>();
            List<String> available = new ArrayList<>();
            for (MdObject object : all)
            {
                String name = object.getName();
                available.add(name);
                if (objectName == null || objectName.equals(name))
                {
                    // Resolve name + extension (.epf for a data processor, .erf for a report) here, in
                    // the read boundary, so the file name is fully decided before the Job runs. Capture
                    // the BM id so the Job can re-resolve the object inside its own read transaction.
                    String fileName = ExternalObjectDumpSupport.outputFileName(name,
                        ExternalObjectDumpSupport.extensionForObject(object));
                    matched.add(new Target(((IBmObject)object).bmGetId(),
                        ((IBmObject)object).bmGetFqn(), name, fileName));
                }
            }
            if (matched.isEmpty())
            {
                // Reachable only with a specific objectName that matched nothing (build-all over a
                // non-empty project always matches >=1).
                return EnumerationResult.failure(ToolResult.error("External object not found: '" //$NON-NLS-1$
                    + objectName + "'. Available external objects: " + available + ".").toJson()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return EnumerationResult.ok(matched, bmModel);
        });
    }

    /**
     * Runs the per-object dump loop in a background {@link Job} with a bounded timeout, the
     * always-on auth-dialog suppressor ensured and the launch update/restructure auto-confirmer
     * armed, so the unattended call never blocks on an EDT/1C modal. Captures per-object
     * success/failure honestly and builds the JSON response.
     *
     * @param bc the resolved build context
     * @return the JSON response
     */
    private static String runBuild(BuildContext bc)
    {
        long buildStartMs = System.currentTimeMillis();
        // Build ALL over an empty project: nothing to compile — return a clear success with
        // built=0/failed=0 (no Job, no infobase needed) rather than failing. This only happens
        // for build-all; a specific objectName that matched nothing already errored in enumeration.
        if (bc.targets.isEmpty())
        {
            return buildResponse(bc, new ArrayList<>(), System.currentTimeMillis() - buildStartMs);
        }

        // Ensure the always-on auth-dialog suppressor is installed (it auto-cancels the
        // "Configure Infobase access Settings" modal raised by EDT background jobs).
        InfobaseAuthDialogSuppressor.ensureInstalled();

        // Synchronized: the Job thread appends while the calling thread may snapshot it on timeout.
        final List<Map<String, Object>> results = Collections.synchronizedList(new ArrayList<>());
        final Throwable[] fatalHolder = new Throwable[1];
        // One timestamp per build run: every object stamped in this call shares the same build time.
        final String buildStamp = STAMP_PREFIX + LocalDateTime.now().format(STAMP_FMT);

        Job buildJob = new Job(NAME + ": " + bc.projectName) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    for (Target target : bc.targets)
                    {
                        results.add(dumpOne(bc, target, buildStamp, monitor));
                    }
                }
                catch (Throwable t) // NOSONAR a dump failure must be captured, never crash the Job
                {
                    fatalHolder[0] = t;
                }
                return Status.OK_STATUS;
            }
        };

        // Arm the launch update + restructure auto-confirmers around the whole build (the dump may
        // route through EDT's "Application update" / "Restructure data" modals). Disarm in finally.
        LaunchUpdateDialogAutoConfirmer.arm(true, true, true);
        buildJob.setUser(false);
        buildJob.schedule();
        try
        {
            buildJob.join(BUILD_TIMEOUT_MS, new NullProgressMonitor());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("External object build was interrupted.").toJson(); //$NON-NLS-1$
        }
        finally
        {
            LaunchUpdateDialogAutoConfirmer.disarm(true, true, true);
        }

        if (buildJob.getState() != Job.NONE)
        {
            buildJob.cancel();
            long builtSoFar;
            synchronized (results)
            {
                builtSoFar = results.stream().filter(r -> Boolean.TRUE.equals(r.get(RES_SUCCESS))).count();
            }
            return ToolResult.error("External object build timed out after " //$NON-NLS-1$
                + (BUILD_TIMEOUT_MS / 1000) + " seconds (" + builtSoFar + " object(s) were already built to " //$NON-NLS-1$ //$NON-NLS-2$
                + bc.outputDir + " before the timeout). The 1C runtime may be slow or an infobase may " //$NON-NLS-1$
                + "be missing — ensure one is associated (create_infobase / set_infobase_credentials) " //$NON-NLS-1$
                + "and retry.").toJson(); //$NON-NLS-1$
        }
        if (fatalHolder[0] != null)
        {
            Activator.logError(NAME + " failed for project " + bc.projectName, fatalHolder[0]); //$NON-NLS-1$
            return ToolResult.error("External object build failed: " + fatalHolder[0].getMessage() //$NON-NLS-1$
                + authHint(fatalHolder[0])).toJson();
        }

        return buildResponse(bc, results, System.currentTimeMillis() - buildStartMs);
    }

    /**
     * Dumps a single external object to {@code outputDir}, capturing success or failure as a result
     * map (never throwing — a per-object failure is recorded, not propagated, so a build of ALL
     * objects continues past one bad object).
     *
     * <p>The object is re-resolved from its captured BM id via {@code tx.getObjectById(bmId)} inside a
     * <b>SHORT</b> {@link BmTransactions#read} that does nothing else, then {@link IExternalObjectDumper#dump}
     * is called <b>OUTSIDE</b> that transaction (still on this Job thread, with the dialog suppressors armed
     * by {@link #runBuild}). The dump is NOT a passive caller-performed model read: the platform impl
     * resolves the infobase, runs {@code IApplicationManager.prepare(...)} (an infobase synchronization /
     * DB update) and launches a thick client to compile the artifact — a long external operation that can
     * itself open transactions on this same BM model. Holding a read lock across it would block any
     * concurrent writer (another metadata-edit tool, EDT's own background save) for the whole bounded
     * window and risks self-deadlock if {@code prepare()} needs a write/nested transaction. So we mirror
     * how the platform itself drives this API: EDT's own {@code ExternalObjectDumpSupport$DumpJob.run()}
     * calls {@code dumper.dump(...)} in a plain Job with NO surrounding BM transaction, and the analogous
     * {@code update_database} runs its long {@code appManager.update(...)} outside any BM transaction too.
     * The short re-resolve is the codebase canon ({@code CreateMetadataTool} / {@code ModifyMetadataTool} /
     * {@code TemplateScreenshotHelper}); holding the resolved handle past the read close is exactly what
     * {@code DumpJob} does. The name and {@code .epf}/{@code .erf} file name were already resolved in
     * {@link #enumerateTargets}.
     *
     * @param bc the build context
     * @param target the external object to dump (BM id + pre-computed file name)
     * @param monitor the Job progress monitor
     * @return a result map for this object
     */
    private static Map<String, Object> dumpOne(BuildContext bc, Target target, String buildStamp,
            IProgressMonitor monitor)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(RES_NAME, target.name);
        long startMs = System.currentTimeMillis();
        try
        {
            Path targetPath = bc.outputDir.resolve(target.fileName);
            // Delete any stale output FIRST: EDT can cache the compiled artifact, so an old .epf/.erf
            // left in place may shadow the fresh build (its old version "hangs there"). Removing it forces
            // a clean write; a deletion failure surfaces as an honest per-object error, not a silent stale
            // file.
            Files.deleteIfExists(targetPath);
            // Stamp the build time into the object's Comment (overwrite), then flush the .mdo to disk so
            // the compiled .epf/.erf records when it was built (the maintainer's "version bump"). The
            // mutation runs in a WRITE boundary; forceExportToDisk persists the .mdo OUTSIDE it. Skipped
            // when recordBuildTime=false, so the build leaves the object (and its .mdo) untouched.
            if (bc.recordBuildTime)
            {
                BmTransactions.write(bc.bmModel, "BuildExternalObjects.stampBuildTime", (tx, pm) -> //$NON-NLS-1$
                {
                    Object mo = tx.getObjectById(target.bmId);
                    if (mo instanceof MdObject)
                    {
                        ((MdObject)mo).setComment(buildStamp);
                    }
                    return Boolean.TRUE;
                });
                BmTransactions.forceExportToDisk(bc.project, target.fqn);
            }
            // Re-resolve the object handle inside a SHORT read transaction (a BM-model lookup must run in
            // a transaction), then close it and hand the handle to dump() OUTSIDE the transaction so no
            // read lock is held across the long external compile. getObjectById may return null if the
            // object was deleted between enumeration and the dump — surface that as an honest per-object
            // failure rather than an NPE.
            EObject object = BmTransactions.read(bc.bmModel, "BuildExternalObjects.resolve", //$NON-NLS-1$
                (tx, pm) -> (EObject)tx.getObjectById(target.bmId));
            if (object == null)
            {
                throw new IllegalStateException("External object '" + target.name //$NON-NLS-1$
                    + "' could not be re-resolved (it may have been deleted)."); //$NON-NLS-1$
            }
            // OUTSIDE any BM transaction (mirrors update_database's appManager.update() and EDT's own
            // DumpJob): the dump synchronizes the infobase and launches a thick client to compile.
            bc.dumper.dump(bc.project, object, targetPath, monitor);
            result.put(RES_SUCCESS, true);
            result.put(RES_PATH, targetPath.toString());
        }
        catch (Throwable t) // NOSONAR capture per-object failure honestly; never abort the whole build
        {
            Activator.logError(NAME + ": failed to build external object '" + target.name + "'", t); //$NON-NLS-1$ //$NON-NLS-2$
            result.put(RES_SUCCESS, false);
            result.put(RES_ERROR,
                (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()) + authHint(t));
        }
        result.put(RES_DURATION_MS, System.currentTimeMillis() - startMs);
        return result;
    }

    /**
     * Builds the JSON response from the per-object results: {@code success} is true only when every
     * object built, with built/failed counts, the results list, the output directory and an honest
     * status message.
     *
     * @param bc the build context
     * @param results the per-object result maps
     * @return the JSON response
     */
    private static String buildResponse(BuildContext bc, List<Map<String, Object>> results, long elapsedMs)
    {
        int built = 0;
        int failed = 0;
        for (Map<String, Object> result : results)
        {
            if (Boolean.TRUE.equals(result.get(RES_SUCCESS)))
            {
                built++;
            }
            else
            {
                failed++;
            }
        }

        boolean allOk = failed == 0;
        ToolResult tr = allOk ? ToolResult.success() : ToolResult.error("Built " + built //$NON-NLS-1$
            + " of " + (built + failed) + " external object(s) in " + elapsedMs + " ms; " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + failed + " failed."); //$NON-NLS-1$
        tr.put(McpKeys.PROJECT, bc.projectName)
            .put(KEY_OUTPUT_DIR, bc.outputDir.toString())
            .put(KEY_BUILT, built)
            .put(KEY_FAILED, failed)
            .put(KEY_RESULTS, results);
        if (bc.outsideWorkspace)
        {
            tr.put("outsideWorkspace", true); //$NON-NLS-1$
        }
        if (allOk)
        {
            String message = results.isEmpty()
                ? "The external-object project has no external data processors/reports to build; nothing to build." //$NON-NLS-1$
                : "Built " + built + " external object(s) to " + bc.outputDir + " in " + elapsedMs //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " ms (built at " + Instant.now() + ")."; //$NON-NLS-1$ //$NON-NLS-2$
            tr.put(McpKeys.MESSAGE, message);
        }
        return tr.toJson();
    }

    /**
     * Best-effort hint appended to a build-failure message when the failure looks like an infobase
     * connection/authentication problem (the dump compiles against an associated infobase, so a
     * missing/wrong credential surfaces here). Names {@code create_infobase} /
     * {@code set_infobase_credentials} so the caller can fix it. Detection keys off the cause TYPE
     * name (language-independent) plus English message keywords. Empty when unrelated.
     *
     * @param t the failure (may be {@code null})
     * @return the credentials hint, or an empty string
     */
    private static String authHint(Throwable t)
    {
        if (t == null)
        {
            return ""; //$NON-NLS-1$
        }
        String typeName = t.getClass().getSimpleName();
        Throwable cause = t.getCause();
        String causeType = cause != null ? cause.getClass().getSimpleName() : ""; //$NON-NLS-1$
        String message = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
        boolean likelyAuth = typeName.contains("Authentication") //$NON-NLS-1$
            || typeName.contains("Connection") //$NON-NLS-1$
            || causeType.contains("Synchronization") //$NON-NLS-1$
            || causeType.contains("Authentication") //$NON-NLS-1$
            || causeType.contains("Connection") //$NON-NLS-1$
            || message.contains("authenticat") //$NON-NLS-1$
            || message.contains("infobase") //$NON-NLS-1$
            || message.contains("connect"); //$NON-NLS-1$
        if (!likelyAuth)
        {
            return ""; //$NON-NLS-1$
        }
        return " The build needs an associated infobase + a resolvable 1C runtime; set one up with " //$NON-NLS-1$
            + "create_infobase and, if the infobase requires user authentication, " //$NON-NLS-1$
            + "set_infobase_credentials (user/password), then retry."; //$NON-NLS-1$
    }

    /** Outcome of {@link #resolveExternalObjectProject}: the resolved project or a JSON error. */
    private static final class ResolvedProject
    {
        final IProject project;
        final IExternalObjectProject externalObjectProject;
        final String errorJson;

        private ResolvedProject(IProject project, IExternalObjectProject externalObjectProject, String errorJson)
        {
            this.project = project;
            this.externalObjectProject = externalObjectProject;
            this.errorJson = errorJson;
        }

        static ResolvedProject ok(IProject project, IExternalObjectProject externalObjectProject)
        {
            return new ResolvedProject(project, externalObjectProject, null);
        }

        static ResolvedProject failure(String errorJson)
        {
            return new ResolvedProject(null, null, errorJson);
        }
    }

    /** Outcome of {@link #resolveOutputDir}: the absolute directory + flag, or a JSON error. */
    private static final class OutputDirResolution
    {
        final Path outputDir;
        final boolean outsideWorkspace;
        final String errorJson;

        private OutputDirResolution(Path outputDir, boolean outsideWorkspace, String errorJson)
        {
            this.outputDir = outputDir;
            this.outsideWorkspace = outsideWorkspace;
            this.errorJson = errorJson;
        }

        static OutputDirResolution ok(Path outputDir, boolean outsideWorkspace)
        {
            return new OutputDirResolution(outputDir, outsideWorkspace, null);
        }

        static OutputDirResolution failure(String errorJson)
        {
            return new OutputDirResolution(null, false, errorJson);
        }
    }

    /**
     * A single resolved build target: the BM top-object id (captured inside the BM read boundary so a
     * fresh object handle can be RE-RESOLVED inside a short read transaction on the Job thread) plus its
     * name and {@code .epf}/{@code .erf} file name (both read inside the read boundary).
     *
     * <p>The object is carried as a {@code bmGetId()} rather than a bare {@link MdObject} handle on
     * purpose: a BM-model lookup ({@code tx.getObjectById}) must run inside a transaction, so the Job
     * re-resolves the handle in a short read transaction and then passes it to
     * {@link IExternalObjectDumper#dump} OUTSIDE that transaction (see {@link #dumpOne}). Holding the id
     * and re-resolving it is the codebase canon (see {@code CreateMetadataTool}/{@code
     * ModifyMetadataTool}/{@code TemplateScreenshotHelper}).
     */
    private static final class Target
    {
        final long bmId;
        final String fqn;
        final String name;
        final String fileName;

        Target(long bmId, String fqn, String name, String fileName)
        {
            this.bmId = bmId;
            this.fqn = fqn;
            this.name = name;
            this.fileName = fileName;
        }
    }

    /**
     * Outcome of {@link #enumerateTargets}: either the targets to build (with the resolved
     * {@link IBmModel}, so the Job can open its own read transaction to re-resolve each object) or a
     * ready-to-return error.
     */
    private static final class EnumerationResult
    {
        final List<Target> targets;
        final IBmModel bmModel;
        final String errorJson;

        private EnumerationResult(List<Target> targets, IBmModel bmModel, String errorJson)
        {
            this.targets = targets;
            this.bmModel = bmModel;
            this.errorJson = errorJson;
        }

        static EnumerationResult ok(List<Target> targets, IBmModel bmModel)
        {
            return new EnumerationResult(targets, bmModel, null);
        }

        static EnumerationResult failure(String errorJson)
        {
            return new EnumerationResult(null, null, errorJson);
        }
    }

    /** Immutable bundle of the resolved inputs threaded through the build loop. */
    private static final class BuildContext
    {
        final String projectName;
        final IProject project;
        final IBmModel bmModel;
        final IExternalObjectDumper dumper;
        final List<Target> targets;
        final Path outputDir;
        final boolean outsideWorkspace;
        final boolean recordBuildTime;

        private BuildContext(String projectName, IProject project, IBmModel bmModel,
                IExternalObjectDumper dumper, List<Target> targets, Path outputDir, boolean outsideWorkspace,
                boolean recordBuildTime)
        {
            this.projectName = projectName;
            this.project = project;
            this.bmModel = bmModel;
            this.dumper = dumper;
            this.targets = targets;
            this.outputDir = outputDir;
            this.outsideWorkspace = outsideWorkspace;
            this.recordBuildTime = recordBuildTime;
        }
    }
}
