/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.IImportLifecycle;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.StartOutcome;
import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.StartPhase;
import com.ditrix.edt.mcp.server.utils.BackgroundJobPolling;
import com.ditrix.edt.mcp.server.utils.BackgroundJobRenderer;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.MarkedFailure;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.ProgressReporter;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.ConversionException;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.IThickClient;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.IThickClientProvider;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.SourceKind;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.WorkspacePaths;

/**
 * Creates a NEW EDT project from a 1C binary file: a configuration ({@code .cf}), an extension
 * ({@code .cfe}), an external data processor ({@code .epf}) or an external report ({@code .erf}).
 *
 * <p>EDT has no file import of its own for these, so the file is first converted to XML by the
 * thick client of an installed 1C:Enterprise platform ({@link BinaryToXmlConverter}), then imported
 * through the same CLI API as {@link ImportConfigurationFromXmlTool}, and started the same way.
 *
 * <p>The work runs as a background job. Everything before the project is created - the conversion
 * and the check that the dump holds what the extension promised - can still be abandoned and
 * leaves nothing behind; the job commits only then. An import that fails after that deletes
 * nothing and names what the workspace holds, without claiming it as the import's own.
 */
public class ImportProjectFromFileTool implements IMcpTool
{
    public static final String NAME = "import_project_from_file"; //$NON-NLS-1$

    private static final String KEY_FILE_PATH = "filePath"; //$NON-NLS-1$
    private static final String KEY_BASE_PROJECT_NAME = "baseProjectName"; //$NON-NLS-1$
    private static final String KEY_PLATFORM_VERSION = "platformVersion"; //$NON-NLS-1$
    private static final String KEY_WAIT_SECONDS = "waitSeconds"; //$NON-NLS-1$

    /** How long the call waits for the job before answering with its id. */
    static final int DEFAULT_WAIT_SECONDS = 30;

    /** The longest per-call wait, kept under common client request timeouts. */
    static final int MAX_WAIT_SECONDS = 45;

    /** How long the conversion may take: a large configuration needs tens of minutes. */
    static final long CONVERSION_BUDGET_MS = TimeUnit.HOURS.toMillis(2);

    /** Kept between the conversion deadline and the job's own, so the conversion's fires first. */
    private static final long COMMIT_MARGIN_MS = TimeUnit.MINUTES.toMillis(10);

    /** The error-contract markers, as {@link ToolResult} spells them. */
    private static final String MARKER_COMMITTED = "mutationCommitted"; //$NON-NLS-1$
    private static final String MARKER_UNKNOWN = "mutationOutcomeUnknown"; //$NON-NLS-1$

    private static final String NATURE_CONFIGURATION = "com._1c.g5.v8.dt.core.V8ConfigurationNature"; //$NON-NLS-1$
    private static final String NATURE_EXTENSION = "com._1c.g5.v8.dt.core.V8ExtensionNature"; //$NON-NLS-1$
    private static final String NATURE_EXTERNAL_OBJECTS = "com._1c.g5.v8.dt.core.V8ExternalObjectsNature"; //$NON-NLS-1$

    private final BackgroundJobs jobs;
    private final IThickClientProvider thickClients;
    private final IProjectImporter importer;
    private final IImportLifecycle lifecycle;
    private final long startBudgetMs;
    private final long conversionBudgetMs;
    private final Path scratchRoot;

    /** The production tool. */
    public ImportProjectFromFileTool()
    {
        this(BackgroundJobs.shared(), BinaryToXmlConverter.installedPlatforms(), new CliProjectImporter(),
            null, ImportConfigurationFromXmlTool.PROJECT_START_BUDGET_MS, CONVERSION_BUDGET_MS,
            null);
    }

    /**
     * The tool with its platform supplied, so a headless test can drive the whole pipeline.
     *
     * @param jobs the job registry
     * @param thickClients resolves the thick client that converts the file
     * @param importer the XML import into a new project
     * @param lifecycle the project lifecycle, or {@code null} for the live EDT services
     * @param startBudgetMs how long the project start is waited for
     * @param conversionBudgetMs how long the conversion may take
     * @param scratchRoot where the scratch directory is created, or {@code null} for
     *     {@code java.io.tmpdir}
     */
    ImportProjectFromFileTool(BackgroundJobs jobs, IThickClientProvider thickClients,
        IProjectImporter importer, IImportLifecycle lifecycle, long startBudgetMs,
        long conversionBudgetMs, Path scratchRoot)
    {
        this.jobs = jobs;
        this.thickClients = thickClients;
        this.importer = importer;
        this.lifecycle = lifecycle;
        this.startBudgetMs = startBudgetMs;
        this.conversionBudgetMs = conversionBudgetMs;
        this.scratchRoot = scratchRoot;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create a NEW EDT project from a 1C binary file: .cf (configuration), .cfe (extension; " //$NON-NLS-1$
            + "baseProjectName required) or .epf/.erf (external data processor/report). Needs an " //$NON-NLS-1$
            + "installed 1C:Enterprise platform. Runs in background: an unfinished call returns a " //$NON-NLS-1$
            + "jobId - poll get_job_status, do not call again. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('import_project_from_file')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_FILE_PATH,
                "Absolute path of the .cf, .cfe, .epf or .erf file.", true) //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT_NAME,
                "Name of the NEW EDT project to create (must not already exist).", true) //$NON-NLS-1$
            .stringProperty(KEY_BASE_PROJECT_NAME,
                "Configuration project the result depends on: REQUIRED for .cfe, optional for " //$NON-NLS-1$
                    + ".epf/.erf, refused for .cf.") //$NON-NLS-1$
            .stringProperty(KEY_PLATFORM_VERSION,
                "Installed platform version or mask used for the conversion, e.g. '8.3.24'; " //$NON-NLS-1$
                    + "default: the best match.") //$NON-NLS-1$
            .integerProperty(KEY_WAIT_SECONDS,
                "Seconds this call may wait before returning the job; defaults to " //$NON-NLS-1$
                    + DEFAULT_WAIT_SECONDS + ", accepts 0 to " + MAX_WAIT_SECONDS + ".") //$NON-NLS-1$ //$NON-NLS-2$
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
        String err = JsonUtils.requireArgument(params, KEY_FILE_PATH,
            ": the absolute path of the .cf, .cfe, .epf or .erf file to import."); //$NON-NLS-1$
        if (err == null)
        {
            err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME,
                ": the name of the NEW project to create."); //$NON-NLS-1$
        }
        if (err != null)
        {
            return err;
        }
        String filePathText = JsonUtils.extractStringArgument(params, KEY_FILE_PATH).trim();
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME).trim();
        String baseProjectName = trimToNull(JsonUtils.extractStringArgument(params, KEY_BASE_PROJECT_NAME));
        String platformVersion = trimToNull(JsonUtils.extractStringArgument(params, KEY_PLATFORM_VERSION));

        Integer waitSeconds = BackgroundJobPolling.readWaitSeconds(params, KEY_WAIT_SECONDS,
            DEFAULT_WAIT_SECONDS, MAX_WAIT_SECONDS);
        if (waitSeconds == null)
        {
            return BackgroundJobPolling.waitSecondsError(KEY_WAIT_SECONDS, params.get(KEY_WAIT_SECONDS),
                DEFAULT_WAIT_SECONDS, MAX_WAIT_SECONDS);
        }

        Path file;
        try
        {
            file = Paths.get(filePathText).toAbsolutePath().normalize();
        }
        catch (InvalidPathException e)
        {
            return ToolResult.error("filePath is not a valid path: '" + filePathText + "' (" //$NON-NLS-1$ //$NON-NLS-2$
                + e.getReason() + "). Pass the absolute path of a .cf, .cfe, .epf or .erf file.").toJson(); //$NON-NLS-1$
        }
        String fileError = validateFile(file);
        if (fileError != null)
        {
            return ToolResult.error(fileError).toJson();
        }
        SourceKind kind = SourceKind.ofFile(file);

        String nameError = validateNewProjectName(projectName);
        if (nameError != null)
        {
            return ToolResult.error(nameError).toJson();
        }

        AtomicReference<IProject> baseProject = new AtomicReference<>();
        String baseError = validateBaseProject(kind, baseProjectName, baseProject);
        if (baseError != null)
        {
            return ToolResult.error(baseError).toJson();
        }

        if (!importer.isAvailable())
        {
            return ToolResult.error("IImportConfigurationFilesApi is not available: the EDT plugin " //$NON-NLS-1$
                + "com._1c.g5.v8.dt.cli.api is not installed, so no project can be imported.").toJson(); //$NON-NLS-1$
        }
        IImportLifecycle effectiveLifecycle = lifecycle;
        if (effectiveLifecycle == null)
        {
            // Checked before anything runs: a project created without them could not be started.
            String missing = ImportConfigurationFromXmlTool.missingLifecycleServiceError();
            if (missing != null)
            {
                return missing;
            }
            effectiveLifecycle = ImportConfigurationFromXmlTool.platformLifecycle();
        }

        Path root = scratchRoot != null ? scratchRoot
            : Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath(); //$NON-NLS-1$
        String rootError = kind.isExternalObject() ? null
            : BinaryToXmlConverter.infobasePathProblem(root, File.separatorChar == '\\');
        if (rootError != null)
        {
            return ToolResult.error("The temporary infobase of a ." + kind.extension() //$NON-NLS-1$
                + " conversion is created under " + root + ", and " + rootError //$NON-NLS-1$ //$NON-NLS-2$
                + ". Start EDT with -Djava.io.tmpdir=<a directory whose path has no spaces> " //$NON-NLS-1$
                + "(after -vmargs in 1cedt.ini) and retry. No project was created.").toJson(); //$NON-NLS-1$
        }

        IThickClient client;
        try
        {
            client = thickClients.resolve(platformVersion, baseProject.get());
        }
        catch (ConversionException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }

        boolean outsideWorkspace = WorkspacePaths.isOutsideWorkspace(file);
        if (outsideWorkspace)
        {
            Activator.logWarning(NAME + ": filePath is OUTSIDE the EDT workspace: " + file //$NON-NLS-1$
                + " (trusted-caller-only - see README Security & trust model)."); //$NON-NLS-1$
        }

        Request request = new Request(kind, file, outsideWorkspace, projectName, baseProjectName,
            baseProject.get() == null ? null : runtimeVersionOf(baseProject.get()), client,
            effectiveLifecycle, root);
        JobSnapshot started;
        try
        {
            started = jobs.start(NAME, conversionBudgetMs + COMMIT_MARGIN_MS,
                "Queued: import " + file.getFileName() + " into new project " + projectName + ".", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                progress -> runImport(request, progress));
        }
        catch (RejectedExecutionException e)
        {
            return ToolResult.error("Could not start the import because the background-job registry " //$NON-NLS-1$
                + "is full or stopping: " + PlatformFailures.describe(e) //$NON-NLS-1$
                + ". Poll existing jobs with get_job_status and retry.").toJson(); //$NON-NLS-1$
        }
        JobSnapshot latest = BackgroundJobPolling.await(jobs, started.getId(), waitSeconds.intValue());
        if (latest == null)
        {
            return ToolResult.error("The import job '" + started.getId() + "' expired before this call " //$NON-NLS-1$ //$NON-NLS-2$
                + "could read it. Check list_projects for project '" + projectName //$NON-NLS-1$
                + "' before importing again.").toJson(); //$NON-NLS-1$
        }
        return renderStart(latest);
    }

    /**
     * @param file the normalized source path
     * @return {@code null} when it is a readable file of a supported kind, otherwise the refusal
     */
    static String validateFile(Path file)
    {
        if (!Files.exists(file))
        {
            return "filePath does not exist: " + file //$NON-NLS-1$
                + ". Pass the absolute path of a .cf, .cfe, .epf or .erf file."; //$NON-NLS-1$
        }
        if (!Files.isRegularFile(file))
        {
            return "filePath is not a file: " + file + ". Pass a .cf, .cfe, .epf or .erf file; " //$NON-NLS-1$ //$NON-NLS-2$
                + "for a directory of XML files use import_configuration_from_xml."; //$NON-NLS-1$
        }
        if (SourceKind.ofFile(file) == null)
        {
            return "filePath has an unsupported extension: " + file.getFileName() + ". Supported: " //$NON-NLS-1$ //$NON-NLS-2$
                + SourceKind.supportedExtensions()
                + "; for a directory of XML files use import_configuration_from_xml."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * @param projectName the requested name
     * @return {@code null} when a new project of that name can be created, otherwise the refusal
     */
    private static String validateNewProjectName(String projectName)
    {
        String problem = WorkspacePaths.projectNameProblem(projectName);
        if (problem != null)
        {
            return "projectName '" + projectName + "' is not a legal project name: " + problem //$NON-NLS-1$ //$NON-NLS-2$
                + ". Pass another name."; //$NON-NLS-1$
        }
        if (ProjectContext.of(projectName).exists())
        {
            return "Project already exists in workspace: " + projectName //$NON-NLS-1$
                + ". The import creates a NEW project: pass a new project name, or remove the old " //$NON-NLS-1$
                + "one with delete_project first."; //$NON-NLS-1$
        }
        Path folder = WorkspacePaths.defaultProjectFolder(projectName);
        if (folder != null && Files.exists(folder))
        {
            return "The workspace folder " + folder + " already exists, so project '" + projectName //$NON-NLS-1$ //$NON-NLS-2$
                + "' cannot be created there. Pass a new project name, or remove that folder."; //$NON-NLS-1$
        }
        Long claimedAt = ImportConfigurationFromXmlTool.importClaimedAt(projectName);
        if (claimedAt != null)
        {
            return ImportConfigurationFromXmlTool.importInProgressMessage(projectName, claimedAt.longValue());
        }
        return null;
    }

    /**
     * Applies the base-project rule of each file kind.
     *
     * @param kind what the file holds
     * @param baseProjectName the requested base, or {@code null}
     * @param resolved receives the base project when one is valid
     * @return {@code null} when the rule is met, otherwise the refusal
     */
    private static String validateBaseProject(SourceKind kind, String baseProjectName,
        AtomicReference<IProject> resolved)
    {
        if (kind == SourceKind.CONFIGURATION && baseProjectName != null)
        {
            return "baseProjectName does not apply to a .cf file: a configuration depends on no " //$NON-NLS-1$
                + "other project. Omit baseProjectName."; //$NON-NLS-1$
        }
        if (kind == SourceKind.EXTENSION && baseProjectName == null)
        {
            return "baseProjectName is required for a .cfe file: an extension project is linked to " //$NON-NLS-1$
                + "the configuration project it extends. Use list_projects to find it."; //$NON-NLS-1$
        }
        if (baseProjectName == null)
        {
            return null;
        }
        if (WorkspacePaths.projectNameProblem(baseProjectName) != null
            || !ProjectContext.of(baseProjectName).exists())
        {
            return ProjectContext.notFoundMessage(baseProjectName);
        }
        ProjectContext base = ProjectContext.of(baseProjectName);
        if (!base.isOpen())
        {
            return "Base project '" + baseProjectName + "' is closed. Open it in EDT first."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        Set<String> natures = ProjectContext.naturesOf(base.project());
        if (natures == null || !natures.contains(NATURE_CONFIGURATION))
        {
            return "Base project '" + baseProjectName + "' is not a configuration project. Pass the " //$NON-NLS-1$ //$NON-NLS-2$
                + "configuration project the " + kind.label() //$NON-NLS-1$
                + " belongs to, not an extension or external-objects project."; //$NON-NLS-1$
        }
        resolved.set(base.project());
        return null;
    }

    /**
     * @param baseProject the configuration project the import depends on
     * @return its runtime version, so the new project matches it; {@code null} lets EDT derive one
     *     from the XML
     */
    private static String runtimeVersionOf(IProject baseProject)
    {
        try
        {
            Activator activator = Activator.getDefault();
            IV8ProjectManager manager = activator == null ? null : activator.getV8ProjectManager();
            IV8Project v8Project = manager == null ? null : manager.getProject(baseProject);
            return v8Project == null || v8Project.getVersion() == null ? null
                : v8Project.getVersion().toString();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * The job. A failure carries the mutation marker the job could establish, so polling the job
     * and the starting call report the same outcome.
     *
     * @param request the validated request
     * @param progress the job's reporter
     * @return the Markdown answer
     * @throws Exception a {@link MarkedFailure} for every failure once the import was entered
     */
    private Object runImport(Request request, ProgressReporter progress) throws Exception
    {
        AtomicReference<Mutation> mutation = new AtomicReference<>(Mutation.NONE);
        try
        {
            return importAndStart(request, progress, mutation);
        }
        catch (ImportFailure e)
        {
            throw new MarkedFailure(e.getMessage(), marker(mutation.get()));
        }
        catch (RuntimeException e)
        {
            if (mutation.get() == Mutation.NONE)
            {
                throw e;
            }
            throw new MarkedFailure(PlatformFailures.describeWithRootCause(e), marker(mutation.get()));
        }
    }

    /**
     * Convert, check, commit, import, start, observe.
     *
     * @param request the validated request
     * @param progress the job's reporter
     * @param mutation set to what the workspace holds as the job goes
     * @return the Markdown answer
     * @throws Exception an {@link ImportFailure} for every expected failure
     */
    private Object importAndStart(Request request, ProgressReporter progress,
        AtomicReference<Mutation> mutation) throws Exception
    {
        String name = request.projectName;
        Long otherStartedAt = ImportConfigurationFromXmlTool.tryClaimImport(name);
        if (otherStartedAt != null)
        {
            throw new ImportFailure(
                ImportConfigurationFromXmlTool.importInProgressMessage(name, otherStartedAt.longValue()));
        }
        Path workDir = null;
        try
        {
            workDir = Files.createTempDirectory(request.scratchRoot, "edt-mcp-import-"); //$NON-NLS-1$
            long budgetMs = Math.min(conversionBudgetMs, progress.remainingMillis() - COMMIT_MARGIN_MS);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, budgetMs));
            progress.add("Converting with 1C:Enterprise " + request.client.platformVersion() + "."); //$NON-NLS-1$ //$NON-NLS-2$
            Path xmlDir;
            try
            {
                xmlDir = BinaryToXmlConverter.convert(request.kind, request.file, workDir, request.client,
                    deadline, progress::add);
            }
            catch (ConversionException e)
            {
                throw new ImportFailure(e.getMessage());
            }
            String problem = BinaryToXmlConverter.verifyDump(request.kind, xmlDir);
            if (problem != null)
            {
                throw new ImportFailure(problem + " No project was created."); //$NON-NLS-1$
            }
            if (!progress.tryCommit())
            {
                throw new ImportFailure("The job ended before the project was created. No project " //$NON-NLS-1$
                    + "was created."); //$NON-NLS-1$
            }
            // Asked again: the conversion took time, and the name was free only when the call came.
            Path folder = WorkspacePaths.defaultProjectFolder(name);
            if (ProjectContext.of(name).exists() || folder != null && Files.exists(folder))
            {
                throw new ImportFailure("A project or workspace folder named '" + name + "' appeared " //$NON-NLS-1$ //$NON-NLS-2$
                    + "while the file was being converted, so nothing was imported. Pass a new project " //$NON-NLS-1$
                    + "name."); //$NON-NLS-1$
            }
            mutation.set(Mutation.UNKNOWN);
            progress.add("Importing the XML files into new project " + name + "."); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                importer.importProject(xmlDir, name, request.runtimeVersion, request.baseProjectName);
            }
            catch (Exception e) // NOSONAR the CLI API is reached by reflection
            {
                throw importFailure(name, e, mutation);
            }
            IProject project = ProjectContext.of(name).project();
            if (!request.lifecycle.projectExists(project))
            {
                throw noProjectAfterImport(name, mutation);
            }
            mutation.set(Mutation.COMMITTED);
            progress.add("Starting project " + name + " in EDT."); //$NON-NLS-1$ //$NON-NLS-2$
            StartOutcome outcome = ImportConfigurationFromXmlTool.startImportedProject(project, name,
                request.lifecycle, startBudgetMs);
            String startFailure = startFailureMessage(name, outcome);
            if (startFailure != null)
            {
                throw new ImportFailure(startFailure);
            }
            boolean ready = outcome.phase == StartPhase.STARTED;
            return renderResult(request, ready, observe(project, ready));
        }
        finally
        {
            ImportConfigurationFromXmlTool.releaseImport(name);
            if (workDir != null && !BinaryToXmlConverter.deleteQuietly(workDir))
            {
                Activator.logWarning(NAME + ": could not delete the scratch directory " + workDir); //$NON-NLS-1$
            }
        }
    }

    /**
     * Reports a failed import by what it left behind. Nothing is deleted and no start latch is
     * released: EDT's latch is keyed by the project alone, so no check can prove a leftover is this
     * import's own rather than another operation's.
     *
     * @param name the project the import was creating
     * @param failure what the import raised
     * @param mutation set to {@link Mutation#NONE} when nothing of that name is left
     * @return the failure to report
     */
    private static ImportFailure importFailure(String name, Exception failure,
        AtomicReference<Mutation> mutation)
    {
        String head = "EDT could not import the converted XML files into project '" + name + "': " //$NON-NLS-1$ //$NON-NLS-2$
            + PlatformFailures.describeWithRootCause(failure) + ". "; //$NON-NLS-1$
        String whose = " This import may have created it before failing, or another operation may " //$NON-NLS-1$
            + "have created it meanwhile: check what it holds before "; //$NON-NLS-1$
        IProject project = ProjectContext.of(name).project();
        if (project.exists())
        {
            return new ImportFailure(head + "A project of that name exists now and is left as it is." //$NON-NLS-1$
                + whose + "deleting it with delete_project (deleteContent=true) or importing again." //$NON-NLS-1$
                + " If this import created it, EDT's import start latch on it may still be set, and " //$NON-NLS-1$
                + "while it is - until project '" + name + "' is deleted, closed or started - EDT " //$NON-NLS-1$ //$NON-NLS-2$
                + "starts no project on its own (an opened or added project stays unstarted). " //$NON-NLS-1$
                + "delete_project, closing the project in EDT or restarting EDT clears it; it is not " //$NON-NLS-1$
                + "released here, because nothing proves the project is this import's."); //$NON-NLS-1$
        }
        Path folder = WorkspacePaths.defaultProjectFolder(name);
        if (folder != null && Files.exists(folder))
        {
            return new ImportFailure(head + "No project exists, but the workspace folder " + folder //$NON-NLS-1$
                + " does now." + whose + "removing it or importing again."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        mutation.set(Mutation.NONE);
        return new ImportFailure(head + "No project was created."); //$NON-NLS-1$
    }

    /**
     * Reports an import that returned normally but left no project to start.
     *
     * @param name the project the import was creating
     * @param mutation set to what the workspace holds afterwards
     * @return the failure to report
     */
    private static ImportFailure noProjectAfterImport(String name, AtomicReference<Mutation> mutation)
    {
        String head = "The import of '" + name + "' returned without an error, but the workspace holds " //$NON-NLS-1$ //$NON-NLS-2$
            + "no project of that name. "; //$NON-NLS-1$
        Path folder = WorkspacePaths.defaultProjectFolder(name);
        if (folder != null && Files.exists(folder))
        {
            mutation.set(Mutation.COMMITTED);
            return new ImportFailure(head + "Its workspace folder " + folder + " exists; remove it and " //$NON-NLS-1$ //$NON-NLS-2$
                + "check the EDT error log before importing again."); //$NON-NLS-1$
        }
        // The import claimed success and nothing is visible: whether it left anything is not known.
        return new ImportFailure(head + "Check the EDT error log and list_projects before importing " //$NON-NLS-1$
            + "again."); //$NON-NLS-1$
    }

    /**
     * @param name the imported project
     * @param outcome what the post-import start observed
     * @return the failure for an outcome that left no start of ours running, otherwise {@code null}
     */
    static String startFailureMessage(String name, StartOutcome outcome)
    {
        String head = "The file was imported into project '" + name + "', but "; //$NON-NLS-1$ //$NON-NLS-2$
        String taken = " The project exists and its name is taken, so do NOT import it again; "; //$NON-NLS-1$
        if (outcome.phase == StartPhase.REFRESH_FAILED)
        {
            return head + "the workspace refresh failed before any start was requested: " //$NON-NLS-1$
                + PlatformFailures.describe(outcome.failure) + "." + taken //$NON-NLS-1$
                + "close and reopen the project in EDT to start it."; //$NON-NLS-1$
        }
        if (outcome.phase == StartPhase.FAILED)
        {
            return head + "EDT could not be asked to start it: " + PlatformFailures.describe(outcome.failure) //$NON-NLS-1$
                + "." + taken + "poll list_projects until it reports ready, and if it never does, " //$NON-NLS-1$ //$NON-NLS-2$
                + "close and reopen the project in EDT."; //$NON-NLS-1$
        }
        if (outcome.phase == StartPhase.NOT_SCHEDULED)
        {
            return head + "its start could not be scheduled, so nothing has asked EDT to start it." //$NON-NLS-1$
                + taken + "close and reopen the project in EDT to start it."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Reads back what EDT made of the import. Best effort: a project still starting has no model
     * to read yet, and a failed read costs a row of the answer, not the answer.
     *
     * @param project the imported project
     * @param ready whether EDT has started it
     * @return what was observed
     */
    private static Observation observe(IProject project, boolean ready)
    {
        Observation observation = new Observation();
        Set<String> natures = ProjectContext.naturesOf(project);
        if (natures != null && natures.contains(NATURE_EXTENSION))
        {
            observation.projectKind = "extension"; //$NON-NLS-1$
        }
        else if (natures != null && natures.contains(NATURE_EXTERNAL_OBJECTS))
        {
            observation.projectKind = "external objects"; //$NON-NLS-1$
        }
        else if (natures != null && natures.contains(NATURE_CONFIGURATION))
        {
            observation.projectKind = "configuration"; //$NON-NLS-1$
        }
        if (!ready)
        {
            return observation;
        }
        try
        {
            Activator activator = Activator.getDefault();
            IV8ProjectManager manager = activator == null ? null : activator.getV8ProjectManager();
            IV8Project v8Project = manager == null ? null : manager.getProject(project);
            if (v8Project instanceof IDependentProject)
            {
                IProject parent = ((IDependentProject)v8Project).getParentProject();
                observation.baseProject = parent == null ? "none" : parent.getName(); //$NON-NLS-1$
            }
            IBmModelManager bmManager = activator == null ? null : activator.getBmModelManager();
            IBmModel bmModel = bmManager == null ? null : bmManager.getModel(project);
            if (bmModel != null && v8Project instanceof IConfigurationAware)
            {
                IConfigurationAware aware = (IConfigurationAware)v8Project;
                observation.configurationName = BmTransactions.read(bmModel, NAME + ".observe", //$NON-NLS-1$
                    (tx, pm) -> {
                        Configuration configuration = aware.getConfiguration();
                        return configuration == null ? null : configuration.getName();
                    });
            }
            if (bmModel != null && v8Project instanceof IExternalObjectProject)
            {
                IExternalObjectProject external = (IExternalObjectProject)v8Project;
                observation.externalObjects = BmTransactions.read(bmModel, NAME + ".observe", //$NON-NLS-1$
                    (tx, pm) -> {
                        List<String> names = new ArrayList<>();
                        Collection<MdObject> objects = external.getExternalObjects();
                        for (MdObject object : objects == null ? Collections.<MdObject> emptyList() : objects)
                        {
                            names.add(object.eClass().getName() + "." + object.getName()); //$NON-NLS-1$
                        }
                        return names;
                    });
            }
        }
        catch (RuntimeException e)
        {
            observation.readError = PlatformFailures.describe(e);
        }
        return observation;
    }

    /**
     * @param request the request
     * @param ready whether EDT has started the project
     * @param observation what was read back
     * @return the Markdown answer
     */
    static String renderResult(Request request, boolean ready, Observation observation)
    {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Project", request.projectName); //$NON-NLS-1$
        rows.put("State", ready ? "ready" : "starting"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        rows.put("Project kind", observation.projectKind == null ? "unknown" : observation.projectKind); //$NON-NLS-1$ //$NON-NLS-2$
        if (observation.configurationName != null)
        {
            rows.put("Configuration name", observation.configurationName); //$NON-NLS-1$
        }
        if (observation.baseProject != null)
        {
            rows.put("Base project", observation.baseProject); //$NON-NLS-1$
        }
        else if (request.baseProjectName != null)
        {
            rows.put("Base project", request.baseProjectName + " (requested; read back once EDT has started the project)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (observation.externalObjects != null)
        {
            rows.put("External objects", //$NON-NLS-1$
                observation.externalObjects.isEmpty() ? "none" : String.join(", ", observation.externalObjects)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        rows.put("Source file", request.file.toString()); //$NON-NLS-1$
        if (request.outsideWorkspace)
        {
            rows.put("Outside workspace", "yes: the source file lies outside the EDT workspace"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        rows.put("Source kind", request.kind.label() + " (." + request.kind.extension() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        rows.put("Converted with", "1C:Enterprise " + request.client.platformVersion()); //$NON-NLS-1$ //$NON-NLS-2$

        StringBuilder body = new StringBuilder("# Project imported from file\n\n"); //$NON-NLS-1$
        body.append(MarkdownUtils.keyValueTable("Field", "Value", rows)).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
        if (ready)
        {
            body.append("EDT has started the project. `list_projects` may still report it `building` " //$NON-NLS-1$
                + "for a short while, until EDT finishes computing its derived data.\n"); //$NON-NLS-1$
        }
        else
        {
            body.append("The import is done, but EDT had not finished starting the project within the " //$NON-NLS-1$
                + "wait. Poll `list_projects` until it reports `ready` before calling model tools on " //$NON-NLS-1$
                + "it, and do not import it again.\n"); //$NON-NLS-1$
        }
        if (observation.readError != null)
        {
            body.append("\nThe project could not be read back: ") //$NON-NLS-1$
                .append(MarkdownUtils.escapeMarkdown(observation.readError)).append('\n');
        }
        return body.toString();
    }

    /**
     * @param mutation what the workspace holds after a failure
     * @return the error-contract marker for it, or {@code null}
     */
    private static String marker(Mutation mutation)
    {
        if (mutation == Mutation.COMMITTED)
        {
            return MARKER_COMMITTED;
        }
        return mutation == Mutation.UNKNOWN ? MARKER_UNKNOWN : null;
    }

    /**
     * @param job the snapshot after this call's wait
     * @return the answer for this call; a failure's marker is the one the job published
     */
    static String renderStart(JobSnapshot job)
    {
        if (job.getStatus() == BackgroundJobs.Status.DONE)
        {
            return String.valueOf(job.getResult());
        }
        if (job.getStatus() == BackgroundJobs.Status.FAILED)
        {
            String message = job.getErrorMessage();
            if (MARKER_COMMITTED.equals(job.getErrorMarker()))
            {
                return ToolResult.errorAfterMutation(message).toJson();
            }
            if (MARKER_UNKNOWN.equals(job.getErrorMarker()))
            {
                return ToolResult.errorWithUnknownMutationOutcome(message).toJson();
            }
            return ToolResult.error(message).toJson();
        }
        if (job.getStatus() == BackgroundJobs.Status.CANCELLED)
        {
            return BackgroundJobRenderer.render(job);
        }
        return "**Pending:** the import continues in background job `" + job.getId() //$NON-NLS-1$
            + "`. Poll it with `get_job_status` using `jobId=\"" + job.getId() //$NON-NLS-1$
            + "\"`; do not call " + NAME + " again for this project.\n\n" //$NON-NLS-1$ //$NON-NLS-2$
            + BackgroundJobRenderer.render(job);
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** What a job did to the workspace, for the markers of an error answer. */
    enum Mutation
    {
        /** Nothing is left behind. */
        NONE,
        /** The import API was entered and whether it left anything is not known. */
        UNKNOWN,
        /** The import API returned normally, so it created the project. */
        COMMITTED
    }

    /** A validated request, handed to the job. */
    static final class Request
    {
        final SourceKind kind;
        final Path file;
        final boolean outsideWorkspace;
        final String projectName;
        final String baseProjectName;
        final String runtimeVersion;
        final IThickClient client;
        final IImportLifecycle lifecycle;
        final Path scratchRoot;

        Request(SourceKind kind, Path file, boolean outsideWorkspace, String projectName,
            String baseProjectName, String runtimeVersion, IThickClient client,
            IImportLifecycle lifecycle, Path scratchRoot)
        {
            this.kind = kind;
            this.file = file;
            this.outsideWorkspace = outsideWorkspace;
            this.projectName = projectName;
            this.baseProjectName = baseProjectName;
            this.runtimeVersion = runtimeVersion;
            this.client = client;
            this.lifecycle = lifecycle;
            this.scratchRoot = scratchRoot;
        }
    }

    /** What was read back from the imported project. */
    static final class Observation
    {
        String projectKind;
        String configurationName;
        String baseProject;
        List<String> externalObjects;
        String readError;
    }

    /** An expected failure of the job; its message is the caller's answer. */
    static final class ImportFailure extends Exception
    {
        private static final long serialVersionUID = 1L;

        ImportFailure(String message)
        {
            super(message);
        }
    }

    /** The import of a directory of XML files into a new project. */
    interface IProjectImporter
    {
        /** @return whether the import API is installed */
        boolean isAvailable();

        /**
         * @param xmlDir the XML files
         * @param projectName the project to create
         * @param runtimeVersion the project's runtime version, or {@code null} to derive it
         * @param baseProjectName the project to link to, or {@code null}
         * @throws Exception whatever the import raises
         */
        void importProject(Path xmlDir, String projectName, String runtimeVersion, String baseProjectName)
            throws Exception; // NOSONAR the CLI API is reached by reflection
    }

    /** {@code IImportConfigurationFilesApi.importProject(Path, String, String, String)} by reflection. */
    private static final class CliProjectImporter implements IProjectImporter
    {
        @Override
        public boolean isAvailable()
        {
            return api() != null;
        }

        @Override
        public void importProject(Path xmlDir, String projectName, String runtimeVersion,
            String baseProjectName) throws Exception
        {
            Object api = api();
            if (api == null)
            {
                throw new IllegalStateException("IImportConfigurationFilesApi is not available"); //$NON-NLS-1$
            }
            // (location, projectName, version, baseProjectName): the API derives the nature itself.
            Method method = api.getClass().getMethod("importProject", //$NON-NLS-1$
                Path.class, String.class, String.class, String.class);
            try
            {
                method.invoke(api, xmlDir, projectName, runtimeVersion, baseProjectName);
            }
            catch (InvocationTargetException e)
            {
                Throwable cause = e.getCause();
                if (cause instanceof Exception)
                {
                    throw (Exception)cause;
                }
                throw e;
            }
        }

        private static Object api()
        {
            Activator activator = Activator.getDefault();
            return activator == null ? null : activator.getImportConfigurationFilesApi();
        }
    }
}
