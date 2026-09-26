/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.OperationCanceledException;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.ditrix.edt.mcp.server.utils.BackgroundJobPolling;
import com.ditrix.edt.mcp.server.utils.BackgroundJobRenderer;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.ProgressReporter;
import com.ditrix.edt.mcp.server.utils.BoundedJob;
import com.ditrix.edt.mcp.server.utils.ConfigurationFileExportSupport;
import com.ditrix.edt.mcp.server.utils.ConfigurationFileExportSupport.OutputFile;
import com.ditrix.edt.mcp.server.utils.ConfigurationFileExportSupport.PublishedFile;
import com.ditrix.edt.mcp.server.utils.ConfigurationFileExportSupport.SyncReading;
import com.ditrix.edt.mcp.server.utils.ConfigurationFileExportSupport.SyncState;
import com.ditrix.edt.mcp.server.utils.DebugServerTargetSupport;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport;
import com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.ditrix.edt.mcp.server.utils.StandaloneServerPortConflictPolicy;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Dumps the configuration ({@code .cf}) or one extension ({@code .cfe}) from an application's
 * infobase to a file, through EDT's {@code IExportConfigurationFileService} (issue #458).
 *
 * <p>The source is the INFOBASE, so the tool reads EDT's infobase-vs-project equality first and
 * refuses a known-stale dump unless the caller opts in. The file is written beside the destination
 * under a temporary name and renamed into place only after it was seen non-empty and fresh, so
 * the destination is either the verified dump or untouched.
 */
public class ExportConfigurationToFileTool implements IMcpTool
{
    public static final String NAME = "export_configuration_to_file"; //$NON-NLS-1$

    static final String KEY_OUTPUT_FILE = "outputFile"; //$NON-NLS-1$
    static final String KEY_EXTENSION_NAME = "extensionName"; //$NON-NLS-1$
    static final String KEY_ALLOW_OUT_OF_DATE = "allowOutOfDate"; //$NON-NLS-1$
    static final String KEY_WAIT_SECONDS = "waitSeconds"; //$NON-NLS-1$

    static final int DEFAULT_WAIT_SECONDS = 20;
    static final int MAX_WAIT_SECONDS = 45;

    /** Registry safety net; every phase below ends on its own well before it. */
    static final long JOB_TIMEOUT_MS = 60L * 60_000L;
    /** Waiting behind another operation on the same infobase (an update, a launch). */
    static final long LOCK_WAIT_MS = 5L * 60_000L;
    /** Starting a standalone server and connecting the project. */
    static final long PREPARE_TIMEOUT_MS = 5L * 60_000L;
    /** How long a prepare that outlived its wait still holds the infobase before it is released. */
    static final long PREPARE_END_CAP_MS = 2L * 60_000L;
    /** How long an abandoned dump still holds the infobase before it is released. */
    static final long DUMP_END_CAP_MS = 2L * 60_000L;
    /** How long a LOADING equality state may be waited out. */
    static final long SYNC_SETTLE_MS = 30_000L;
    /** How long past the settle wait EDT's synchronization service may take to answer. */
    static final long SYNC_READ_MARGIN_MS = 30_000L;
    /** The Designer dump itself. */
    static final long EXPORT_TIMEOUT_MS = 30L * 60_000L;

    /** The mode EDT's Applications-view export command prepares the application in. */
    private static final String PREPARE_MODE = "run"; //$NON-NLS-1$

    private final BackgroundJobs jobs;

    public ExportConfigurationToFileTool()
    {
        this(BackgroundJobs.shared());
    }

    ExportConfigurationToFileTool(BackgroundJobs jobs)
    {
        this.jobs = jobs;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Export the configuration (.cf) or an extension (.cfe) FROM an application's " //$NON-NLS-1$
            + "infobase to a file via the 1C thick client - the infobase's content, not the " //$NON-NLS-1$
            + "project's (for project sources use export_configuration_to_xml). An extension " //$NON-NLS-1$
            + "project in projectName exports that extension. Refused while EDT reports the " //$NON-NLS-1$
            + "infobase out of date with the project: run update_database first, or pass " //$NON-NLS-1$
            + "allowOutOfDate=true. outputFile must not exist - it is never overwritten. " //$NON-NLS-1$
            + "Returns a jobId when not finished within waitSeconds; poll get_job_status. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('" + NAME + "')."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "Configuration project, or an extension project to export that extension.", true) //$NON-NLS-1$
            .stringProperty(KEY_OUTPUT_FILE,
                "Absolute path of the file to create: .cf for a configuration, .cfe for an " //$NON-NLS-1$
                    + "extension; its folder must exist.", true) //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application from get_applications (default: the project's default application).") //$NON-NLS-1$
            .stringProperty(KEY_EXTENSION_NAME,
                "Export this extension of the configuration instead: its name in the infobase " //$NON-NLS-1$
                    + "(or its EDT project name).") //$NON-NLS-1$
            .booleanProperty(KEY_ALLOW_OUT_OF_DATE,
                "true = export even when EDT reports the infobase out of date (default false).") //$NON-NLS-1$
            .integerProperty(KEY_WAIT_SECONDS,
                "How long this call waits for the result before returning a jobId; defaults to " //$NON-NLS-1$
                    + DEFAULT_WAIT_SECONDS + ", accepts 0 to " + MAX_WAIT_SECONDS + ".") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public boolean connectsToInfobase()
    {
        // The dump starts the Designer against the infobase: the credentials dialog can appear.
        return true;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String required = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_OUTPUT_FILE);
        if (required != null)
        {
            return required;
        }
        Integer waitSeconds = BackgroundJobPolling.readWaitSeconds(params, KEY_WAIT_SECONDS,
            DEFAULT_WAIT_SECONDS, MAX_WAIT_SECONDS);
        if (waitSeconds == null)
        {
            return BackgroundJobPolling.waitSecondsError(KEY_WAIT_SECONDS,
                params.get(KEY_WAIT_SECONDS), DEFAULT_WAIT_SECONDS, MAX_WAIT_SECONDS);
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME).trim();
        String applicationId = trimToNull(JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID));
        String extensionName = trimToNull(JsonUtils.extractStringArgument(params, KEY_EXTENSION_NAME));
        boolean allowOutOfDate = JsonUtils.extractBooleanArgument(params, KEY_ALLOW_OUT_OF_DATE, false);

        OutputFile output = ConfigurationFileExportSupport.validateOutputFile(KEY_OUTPUT_FILE,
            JsonUtils.extractStringArgument(params, KEY_OUTPUT_FILE));
        if (!output.ok())
        {
            return ToolResult.error(output.error()).toJson();
        }

        Target target;
        try
        {
            target = resolveTarget(projectName, extensionName);
        }
        catch (RuntimeException e)
        {
            Activator.logError(NAME + ": could not resolve project " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Could not resolve project '" + projectName + "': " //$NON-NLS-1$ //$NON-NLS-2$
                + PlatformFailures.describe(e) + ". Check it with list_projects and retry.").toJson(); //$NON-NLS-1$
        }
        if (target.error != null)
        {
            return ToolResult.error(target.error).toJson();
        }
        String mismatch = ConfigurationFileExportSupport.kindMismatch(KEY_OUTPUT_FILE, output,
            target.extensionName, target.configurationProject.getName());
        if (mismatch != null)
        {
            return ToolResult.error(mismatch).toJson();
        }

        Request request = new Request(target, applicationId, output.path(), allowOutOfDate);
        JobSnapshot started;
        try
        {
            started = jobs.start(NAME, JOB_TIMEOUT_MS, "Accepted the export request for " //$NON-NLS-1$
                + output.path() + ".", progress -> runExport(request, progress)); //$NON-NLS-1$
        }
        catch (RejectedExecutionException e)
        {
            return ToolResult.error("Could not start " + NAME + " because the background-job " //$NON-NLS-1$ //$NON-NLS-2$
                + "registry is full or stopping: " + PlatformFailures.describe(e) //$NON-NLS-1$
                + ". Poll existing jobs with get_job_status and retry, or restart EDT if the " //$NON-NLS-1$
                + "bundle is stopping.").toJson(); //$NON-NLS-1$
        }
        JobSnapshot latest = BackgroundJobPolling.await(jobs, started.getId(), waitSeconds.intValue());
        if (latest == null)
        {
            return ToolResult.error("The export background job '" + started.getId() //$NON-NLS-1$
                + "' expired before this call could poll it. Check whether " + output.path() //$NON-NLS-1$
                + " exists before starting " + NAME + " again.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return renderSnapshot(latest);
    }

    /**
     * The caller-facing text for a job snapshot: the report when done, an error when failed, and
     * the job id to poll while it still runs.
     *
     * @param job the snapshot
     * @return the text to return
     */
    static String renderSnapshot(JobSnapshot job)
    {
        switch (job.getStatus())
        {
            case DONE:
                return String.valueOf(job.getResult());
            case FAILED:
                return ToolResult.error(job.getErrorMessage()).toJson();
            case RUNNING:
                return "**Pending:** the export continues in background job `" + job.getId() //$NON-NLS-1$
                    + "`. Poll it with `get_job_status` using `jobId=\"" + job.getId() //$NON-NLS-1$
                    + "\"`; do not call " + NAME + " again for this file.\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + BackgroundJobRenderer.render(job);
            default:
                return BackgroundJobRenderer.render(job);
        }
    }

    /**
     * Resolves what is exported: the configuration project the application belongs to, the
     * extension name (for an extension), and the project whose equality with the infobase decides
     * whether the dump is stale.
     *
     * @param projectName the caller's project
     * @param requestedExtension the caller's extensionName, or {@code null}
     * @return the target, or one carrying the refusal
     */
    private static Target resolveTarget(String projectName, String requestedExtension)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return Target.error(ProjectContext.notFoundMessage(projectName));
        }
        if (!ctx.isOpen())
        {
            return Target.error("Project is closed: " + projectName //$NON-NLS-1$
                + ". Open it in EDT, then retry."); //$NON-NLS-1$
        }
        IV8ProjectManager v8Projects = Activator.getDefault().getV8ProjectManager();
        if (v8Projects == null)
        {
            return Target.error("EDT's project manager is not available yet; retry once EDT " //$NON-NLS-1$
                + "has finished starting."); //$NON-NLS-1$
        }
        IProject project = ctx.project();
        IV8Project v8Project = v8Projects.getProject(project);
        if (v8Project instanceof IExtensionProject)
        {
            return extensionProjectTarget(project, (IExtensionProject)v8Project, requestedExtension);
        }
        if (!(v8Project instanceof IConfigurationProject))
        {
            return Target.error("Project '" + projectName + "' is not a configuration or " //$NON-NLS-1$ //$NON-NLS-2$
                + "extension project. " + NAME + " dumps a configuration (.cf) or an extension " //$NON-NLS-1$ //$NON-NLS-2$
                + "(.cfe) from an application's infobase; list_projects shows each project's kind."); //$NON-NLS-1$
        }
        if (requestedExtension == null)
        {
            return new Target(project, null, project, null);
        }
        List<ExtensionCandidate> candidates = new ArrayList<>();
        for (IExtensionProject candidate : v8Projects.getProjects(IExtensionProject.class))
        {
            IProject candidateProject = candidate.getProject();
            if (project.equals(candidate.getParentProject()) && candidateProject != null
                && candidateProject.isOpen())
            {
                candidates.add(new ExtensionCandidate(candidateProject,
                    ConfigurationFileExportSupport.configurationName(candidateProject, candidate)));
            }
        }
        return matchExtension(project, candidates, requestedExtension);
    }

    /**
     * Picks the extension {@code requested} names among the configuration's extension projects.
     * The infobase knows an extension by its configuration name; the EDT project name is accepted
     * as a fallback, since callers often pass it. An unmatched name goes to the Designer as given.
     *
     * @param configurationProject the configuration project
     * @param candidates its open extension projects
     * @param requested the caller's extensionName
     * @return the target; its extension name is always the configuration name when one matched
     */
    static Target matchExtension(IProject configurationProject, List<ExtensionCandidate> candidates,
        String requested)
    {
        ExtensionCandidate byProjectName = null;
        ExtensionCandidate unresolvedByProjectName = null;
        for (ExtensionCandidate candidate : candidates)
        {
            String name = candidate.configurationName();
            if (name == null)
            {
                if (candidate.project().getName().equalsIgnoreCase(requested))
                {
                    unresolvedByProjectName = candidate;
                }
                continue;
            }
            if (name.equalsIgnoreCase(requested))
            {
                return new Target(configurationProject, name, candidate.project(), null);
            }
            if (candidate.project().getName().equalsIgnoreCase(requested))
            {
                byProjectName = candidate;
            }
        }
        if (byProjectName != null)
        {
            return new Target(configurationProject, byProjectName.configurationName(),
                byProjectName.project(), null);
        }
        if (unresolvedByProjectName != null)
        {
            return Target.error("Extension project '" + unresolvedByProjectName.project().getName() //$NON-NLS-1$
                + "' is not loaded yet, so the configuration name the infobase knows it by is unknown. " //$NON-NLS-1$
                + "Nothing was exported. Wait for the project with wait_for_project_ready, then retry."); //$NON-NLS-1$
        }
        return new Target(configurationProject, requested, null, null);
    }

    private static Target extensionProjectTarget(IProject project, IExtensionProject extension,
        String requestedExtension)
    {
        IProject base = extension.getParentProject();
        if (base == null || !base.exists() || !base.isOpen())
        {
            return Target.error("Extension project '" + project.getName() + "' has no open base " //$NON-NLS-1$ //$NON-NLS-2$
                + "configuration project, and the infobase belongs to that project's application. " //$NON-NLS-1$
                + "Open or link the base project, then retry."); //$NON-NLS-1$
        }
        String name = ConfigurationFileExportSupport.configurationName(project, extension);
        if (name == null)
        {
            return Target.error("The extension name of project '" + project.getName() //$NON-NLS-1$
                + "' could not be read - its model is not ready. Wait until list_projects shows " //$NON-NLS-1$
                + "it ready, or pass projectName='" + base.getName() //$NON-NLS-1$
                + "' with extensionName set to the extension's name."); //$NON-NLS-1$
        }
        if (requestedExtension != null && !requestedExtension.equalsIgnoreCase(name)
            && !requestedExtension.equalsIgnoreCase(project.getName()))
        {
            return Target.error("projectName '" + project.getName() + "' is the project of " //$NON-NLS-1$ //$NON-NLS-2$
                + "extension '" + name + "', but extensionName is '" + requestedExtension //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Omit extensionName, or pass projectName='" + base.getName() //$NON-NLS-1$
                + "' to export another extension of that configuration."); //$NON-NLS-1$
        }
        return new Target(base, name, project, null);
    }

    /**
     * The background job: resolve the application, take the infobase lock, prepare, check the
     * equality, dump, publish.
     *
     * @param request the validated request
     * @param progress the job's reporter
     * @return the Markdown report
     * @throws Exception a refusal or failure; its message is what the caller sees
     */
    Object runExport(Request request, ProgressReporter progress) throws Exception
    {
        InfobaseAuthDialogSuppressor.markActivityStart();
        long dialogsBefore = InfobaseAuthDialogSuppressor.accessSettingsAutoCancelCount();
        try
        {
            return exportUnderLock(request, progress);
        }
        catch (InterruptedException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            String note = InfobaseAuthDialogSuppressor.accessSettingsDialogFailureNote(dialogsBefore,
                InfobaseAuthDialogSuppressor.accessSettingsAutoCancelCount());
            if (note.isEmpty())
            {
                throw e;
            }
            String message = e.getMessage() != null ? e.getMessage() : PlatformFailures.describe(e);
            throw new IllegalStateException(message + ' ' + note, e);
        }
        finally
        {
            InfobaseAuthDialogSuppressor.markActivityEnd();
        }
    }

    private Object exportUnderLock(Request request, ProgressReporter progress) throws Exception
    {
        IProject project = request.target.configurationProject;
        IApplicationManager manager = Activator.getDefault().getApplicationManager();
        if (manager == null)
        {
            throw new IllegalStateException("EDT's application manager is not available; retry " //$NON-NLS-1$
                + "once EDT has finished starting. Nothing was exported."); //$NON-NLS-1$
        }
        progress.add("Resolving the application of project '" + project.getName() + "'."); //$NON-NLS-1$ //$NON-NLS-2$
        String applicationId = resolveApplicationId(request, manager, progress);
        IApplication application = resolveApplication(manager, project, applicationId, progress);
        InfobaseReference infobase = InfobaseAccessSupport.resolveInfobaseReference(application);
        if (infobase == null)
        {
            throw Refusals.state("Application '" + applicationId + "' has no infobase to dump from. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Pick an infobase application with get_applications. Nothing was exported."); //$NON-NLS-1$
        }

        LaunchLifecycleUtils.LaunchLock lock = LaunchLifecycleUtils.lockFor(project.getName(), applicationId);
        long lockWait = boundedBy(progress, LOCK_WAIT_MS);
        LaunchLifecycleUtils.Acquisition acquisition = lock.tryAcquire(lockWait, null);
        if (acquisition == LaunchLifecycleUtils.Acquisition.INTERRUPTED)
        {
            throw new InterruptedException("cancelled while waiting for the infobase"); //$NON-NLS-1$
        }
        if (!acquisition.acquired())
        {
            throw Refusals.state(LaunchLifecycleUtils.lockNotAcquiredMessage(acquisition,
                project.getName(), applicationId, lockWait)
                + " Nothing was exported. Wait for that operation to finish, then call " + NAME //$NON-NLS-1$
                + " again."); //$NON-NLS-1$
        }
        try
        {
            // Prepare and the dump run on in EDT after a cancel, so cancel_job must not call the
            // job cancelled cleanly once they start.
            if (!progress.tryCommit())
            {
                throw new InterruptedException("cancelled before the export was handed to EDT"); //$NON-NLS-1$
            }
            prepare(project, application, applicationId, manager, progress);
            SyncReading sync = readSyncBounded(request, infobase, progress);
            progress.add("Infobase vs project: " + sync.state().wire() + " - " + sync.detail() + '.'); //$NON-NLS-1$ //$NON-NLS-2$
            if (sync.state() == SyncState.OUT_OF_DATE && !request.allowOutOfDate)
            {
                throw Refusals.state(outOfDateRefusal(request, applicationId, sync));
            }
            return dumpAndPublish(request, applicationId, application, infobase, sync, progress);
        }
        finally
        {
            lock.unlock();
        }
    }

    private static String resolveApplicationId(Request request, IApplicationManager manager,
        ProgressReporter progress)
    {
        if (request.applicationId != null)
        {
            return request.applicationId;
        }
        IProject project = request.target.configurationProject;
        LaunchLifecycleUtils.DefaultApplicationLookup lookup = LaunchLifecycleUtils.resolveDefaultApplication(
            project, null, manager, boundedBy(progress, ApplicationSupport.LOOKUP_TIMEOUT_MS));
        if (lookup.inconclusive())
        {
            throw new IllegalStateException("Could not resolve the default application of project '" //$NON-NLS-1$
                + project.getName() + "': " + lookup.failure() + ". Pass applicationId " //$NON-NLS-1$ //$NON-NLS-2$
                + "explicitly (get_applications lists them). Nothing was exported."); //$NON-NLS-1$
        }
        String id = trimToNull(lookup.id());
        if (id == null)
        {
            throw Refusals.state("Project '" + project.getName() + "' has no default application. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Pass applicationId (get_applications lists them), or create an infobase with " //$NON-NLS-1$
                + "create_infobase. Nothing was exported."); //$NON-NLS-1$
        }
        return id;
    }

    private static IApplication resolveApplication(IApplicationManager manager, IProject project,
        String applicationId, ProgressReporter progress) throws InterruptedException
    {
        ApplicationSupport.BoundedRead<Optional<IApplication>> read = ApplicationSupport
            .getApplicationBounded(manager, project, applicationId,
                boundedBy(progress, ApplicationSupport.LOOKUP_TIMEOUT_MS));
        if (read.interrupted())
        {
            throw new InterruptedException("cancelled while resolving the application"); //$NON-NLS-1$
        }
        if (!read.concluded())
        {
            throw new IllegalStateException("Could not resolve application '" + applicationId //$NON-NLS-1$
                + "': " + read.deadlineFailure() + ". Nothing was exported."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Optional<IApplication> application;
        try
        {
            application = read.valueOrRethrow();
        }
        catch (RuntimeException e)
        {
            throw new IllegalStateException("Could not resolve application '" + applicationId //$NON-NLS-1$
                + "' in project '" + project.getName() + "': " + PlatformFailures.describe(e) //$NON-NLS-1$ //$NON-NLS-2$
                + ". Check it with get_applications. Nothing was exported.", e); //$NON-NLS-1$
        }
        if (application.isEmpty())
        {
            throw Refusals.state("Application not found in project '" + project.getName() + "': " //$NON-NLS-1$ //$NON-NLS-2$
                + applicationId + ". Use get_applications for valid ids. Nothing was exported."); //$NON-NLS-1$
        }
        return application.get();
    }

    /**
     * Prepares the application the way EDT's export wizard does before talking to the infobase:
     * starts a standalone server if needed and connects the project, which is also what makes the
     * equality state meaningful. A port-conflict modal is cancelled and reported, never left open.
     */
    private static void prepare(IProject project, IApplication application, String applicationId,
        IApplicationManager manager, ProgressReporter progress) throws InterruptedException
    {
        progress.add("Preparing application '" + applicationId //$NON-NLS-1$
            + "' (starting its server if needed, connecting the project)."); //$NON-NLS-1$
        // No shell and so no UI-thread wait under the lock: prepare never reads one, and EDT's
        // own export command passes an empty context too.
        ExecutionContext context = new ExecutionContext();
        boolean serverApplication = DebugServerTargetSupport.isServerApplicationId(applicationId);
        String infobaseName = LaunchLifecycleUtils.conflictAttributionName(application);
        String serverName = serverApplication
            ? LaunchLifecycleUtils.attributionServerName(manager, project, applicationId) : null;
        StandaloneServerPortConflictPolicy portPolicy =
            serverApplication ? StandaloneServerPortConflictPolicy.CANCEL : null;
        long timeout = boundedBy(progress, PREPARE_TIMEOUT_MS);
        BoundedJob.Result result;
        try (LaunchUpdateDialogAutoConfirmer.ConflictWatch watch =
            LaunchUpdateDialogAutoConfirmer.beginConflictWatch(infobaseName, serverName))
        {
            boolean armed = portPolicy != null && LaunchUpdateDialogAutoConfirmer.arm(false, false,
                false, null, infobaseName, portPolicy, serverName);
            CountDownLatch prepareEnded = new CountDownLatch(1);
            try
            {
                result = BoundedJob.run(NAME + ": prepare " + applicationId, timeout, monitor -> { //$NON-NLS-1$
                    StandaloneServerStateRecovery.ensureStartable(project, application, applicationId,
                        manager);
                    manager.prepare(application, PREPARE_MODE, context, monitor);
                }, prepareEnded::countDown);
                // A prepare that outlived the wait may still start the server and raise its
                // port modal: keep the arm, the watch and the caller's infobase lock until it ends.
                if (BoundedJob.isInconclusive(result.getOutcome())
                    && !awaitEnd(prepareEnded, PREPARE_END_CAP_MS))
                {
                    progress.add("Preparing is still running " + seconds(PREPARE_END_CAP_MS) //$NON-NLS-1$
                        + " after the wait ended; releasing the infobase anyway."); //$NON-NLS-1$
                }
            }
            finally
            {
                if (armed)
                {
                    LaunchUpdateDialogAutoConfirmer.disarm(false, false, false, null, infobaseName,
                        portPolicy, serverName);
                }
            }
            if (watch.portConflicted())
            {
                throw Refusals.state(portConflictRefusal(applicationId, watch.portConflictDetail()));
            }
        }
        switch (result.getOutcome())
        {
            case COMPLETED:
                if (result.getFailure() != null)
                {
                    Activator.logError(NAME + ": preparing application " + applicationId + " failed", //$NON-NLS-1$ //$NON-NLS-2$
                        result.getFailure());
                    throw new IllegalStateException("Could not prepare application '" + applicationId //$NON-NLS-1$
                        + "' (start its server / connect the project): " //$NON-NLS-1$
                        + PlatformFailures.describeWithRootCause(result.getFailure())
                        + ". Nothing was exported. Check the application with get_applications and " //$NON-NLS-1$
                        + "retry.", result.getFailure()); //$NON-NLS-1$
                }
                return;
            case INTERRUPTED:
                throw new InterruptedException("cancelled while preparing the application"); //$NON-NLS-1$
            case TIMED_OUT:
                throw new IllegalStateException("Preparing application '" + applicationId //$NON-NLS-1$
                    + "' did not finish within " + seconds(timeout) + " and may still be running in " //$NON-NLS-1$ //$NON-NLS-2$
                    + "EDT. Nothing was exported. Check the server in EDT's Servers view, then retry."); //$NON-NLS-1$
            default:
                throw new IllegalStateException("Preparing application '" + applicationId //$NON-NLS-1$
                    + "' never started (EDT's job queue did not run it). Nothing was exported; " //$NON-NLS-1$
                    + "retrying is safe."); //$NON-NLS-1$
        }
    }

    /**
     * Waits for {@code ended} at most {@code capMs}, through interrupts (a cancelled call still
     * holds what the running work needs); the interrupt flag is restored afterwards.
     *
     * @return {@code true} when the work ended within the cap
     */
    static boolean awaitEnd(CountDownLatch ended, long capMs)
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(capMs);
        boolean interrupted = false;
        try
        {
            while (true)
            {
                long left = deadline - System.nanoTime();
                if (left <= 0)
                {
                    return ended.getCount() == 0;
                }
                try
                {
                    return ended.await(left, TimeUnit.NANOSECONDS);
                }
                catch (InterruptedException e)
                {
                    interrupted = true;
                }
            }
        }
        finally
        {
            if (interrupted)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The refusal for a standalone server whose ports are taken; the modal was cancelled. */
    static String portConflictRefusal(String applicationId, String detail)
    {
        return "Could not prepare application '" + applicationId + "': its standalone server could " //$NON-NLS-1$ //$NON-NLS-2$
            + "not start because its network ports are already in use" //$NON-NLS-1$
            + (detail == null || detail.isBlank() ? "." : ": " + detail) //$NON-NLS-1$ //$NON-NLS-2$
            + " EDT's offer to move the server to free ports was declined, because that rewrites " //$NON-NLS-1$
            + "the server configuration. Nothing was exported. Free those ports - most often an " //$NON-NLS-1$
            + "ibsrv process left from an earlier EDT session (stop it, or stop the server in " //$NON-NLS-1$
            + "EDT's Servers view) - then retry."; //$NON-NLS-1$
    }

    /** The job is committed here, so a stalled synchronization service must not hold it forever. */
    private static SyncReading readSyncBounded(Request request, InfobaseReference infobase,
        ProgressReporter progress) throws InterruptedException
    {
        long timeout = boundedBy(progress, SYNC_SETTLE_MS + SYNC_READ_MARGIN_MS);
        AtomicReference<SyncReading> reading = new AtomicReference<>();
        BoundedJob.Result result = BoundedJob.run(NAME + ": read the synchronization state", timeout, //$NON-NLS-1$
            monitor -> reading.set(readSync(request, infobase)));
        if (result.getOutcome() == BoundedJob.Outcome.INTERRUPTED)
        {
            throw new InterruptedException("cancelled while reading the synchronization state"); //$NON-NLS-1$
        }
        SyncReading value = reading.get();
        if (result.getOutcome() == BoundedJob.Outcome.COMPLETED && value != null)
        {
            return value;
        }
        return new SyncReading(SyncState.UNKNOWN, "EDT did not report the synchronization state within " //$NON-NLS-1$
            + seconds(timeout));
    }

    private static SyncReading readSync(Request request, InfobaseReference infobase)
        throws InterruptedException
    {
        IProject syncProject = request.target.syncProject;
        if (syncProject == null)
        {
            return new SyncReading(SyncState.UNKNOWN, "no project in this workspace holds extension '" //$NON-NLS-1$
                + request.target.extensionName + "'"); //$NON-NLS-1$
        }
        return ConfigurationFileExportSupport.readSyncState(syncProject, infobase, SYNC_SETTLE_MS);
    }

    static String outOfDateRefusal(Request request, String applicationId, SyncReading sync)
    {
        String what = request.target.extensionName == null ? "configuration" //$NON-NLS-1$
            : "extension '" + request.target.extensionName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        return "The infobase of application '" + applicationId + "' is out of date (" //$NON-NLS-1$ //$NON-NLS-2$
            + sync.detail() + "), so the " + what + " dump would hold the infobase's content, " //$NON-NLS-1$ //$NON-NLS-2$
            + "not the project's. Nothing was exported. Which side is newer is unknown: if the " //$NON-NLS-1$
            + "infobase was changed in the Designer, update_database would overwrite that change. " //$NON-NLS-1$
            + "To export the project, apply it first with update_database (projectName='" //$NON-NLS-1$
            + request.target.configurationProject.getName()
            + "', applicationId='" + applicationId + "'), then call " + NAME + " again - or pass " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + KEY_ALLOW_OUT_OF_DATE + "=true to export what the infobase holds now."; //$NON-NLS-1$
    }

    /**
     * Runs the dump into a partial file under a deadline, then publishes it. An abandoned dump
     * (deadline, cancel) has its monitor cancelled and its partial file removed once EDT's call
     * returns; the Designer can outlive that, so the partial file may reappear.
     */
    private Object dumpAndPublish(Request request, String applicationId, IApplication application,
        InfobaseReference infobase, SyncReading sync, ProgressReporter progress) throws Exception
    {
        Path partial = ConfigurationFileExportSupport.partialFileFor(request.outputFile,
            UUID.randomUUID().toString().substring(0, 8));
        AtomicBoolean abandoned = new AtomicBoolean();
        CountDownLatch dumpEnded = new CountDownLatch(1);
        long timeout = boundedBy(progress, EXPORT_TIMEOUT_MS);
        long startMillis = System.currentTimeMillis();
        progress.add("Dumping the " + describeSource(request) + " with the 1C Designer into " //$NON-NLS-1$ //$NON-NLS-2$
            + partial + '.');
        BoundedJob.Result result = BoundedJob.run(NAME + ": " + request.outputFile.getFileName(), //$NON-NLS-1$
            timeout,
            monitor -> {
                ConfigurationFileExportSupport.export(request.target.configurationProject, infobase,
                    request.target.extensionName, partial, monitor);
                // A cancelled platform call returns normally, leaving a partial or no file.
                if (monitor.isCanceled())
                {
                    throw new OperationCanceledException();
                }
            },
            () -> {
                if (abandoned.get())
                {
                    ConfigurationFileExportSupport.deleteQuietly(partial);
                }
                dumpEnded.countDown();
            });
        if (result.getOutcome() != BoundedJob.Outcome.COMPLETED)
        {
            abandoned.set(true);
            ConfigurationFileExportSupport.deleteQuietly(partial);
            // The caller's infobase lock and dialog suppression stay on while the dump still runs.
            if (BoundedJob.isInconclusive(result.getOutcome()) && !awaitEnd(dumpEnded, DUMP_END_CAP_MS))
            {
                progress.add("The abandoned dump is still running " + seconds(DUMP_END_CAP_MS) //$NON-NLS-1$
                    + " later; releasing the infobase anyway."); //$NON-NLS-1$
            }
            if (result.getOutcome() == BoundedJob.Outcome.INTERRUPTED)
            {
                throw new InterruptedException("cancelled while the Designer was dumping"); //$NON-NLS-1$
            }
            if (result.getOutcome() == BoundedJob.Outcome.TIMED_OUT)
            {
                throw new IllegalStateException("The dump did not finish within " + seconds(timeout) //$NON-NLS-1$
                    + " and was abandoned. " + abandonedDumpNote(request.outputFile, partial) //$NON-NLS-1$
                    + " Retry, or check the infobase in EDT."); //$NON-NLS-1$
            }
            throw new IllegalStateException("The dump never started (EDT's job queue did not run " //$NON-NLS-1$
                + "it). Nothing was written to " + request.outputFile + "; retrying is safe."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (result.getFailure() instanceof OperationCanceledException)
        {
            ConfigurationFileExportSupport.deleteQuietly(partial);
            throw new IllegalStateException("The dump was cancelled in EDT (its progress job was " //$NON-NLS-1$
                + "stopped). " + abandonedDumpNote(request.outputFile, partial) + " Call " + NAME //$NON-NLS-1$ //$NON-NLS-2$
                + " again to retry."); //$NON-NLS-1$
        }
        if (result.getFailure() != null)
        {
            ConfigurationFileExportSupport.deleteQuietly(partial);
            if (ConfigurationFileExportSupport.isDeclaredPlatformFailure(result.getFailure()))
            {
                // An operational outcome the caller is told in full; the platform logs its stack.
                Activator.logWarning(NAME + ": the dump of " + applicationId + " failed: " //$NON-NLS-1$ //$NON-NLS-2$
                    + PlatformFailures.describeWithRootCause(result.getFailure()));
            }
            else
            {
                Activator.logError(NAME + ": the dump of " + applicationId + " failed", //$NON-NLS-1$ //$NON-NLS-2$
                    result.getFailure());
            }
            throw new IllegalStateException(ConfigurationFileExportSupport.describeExportFailure(
                result.getFailure(), request.target.extensionName,
                request.target.configurationProject.getName())
                + " Nothing was written to " + request.outputFile + '.', result.getFailure()); //$NON-NLS-1$
        }
        // publish() judges success by the file.
        PublishedFile published;
        try
        {
            published = ConfigurationFileExportSupport.publish(partial, request.outputFile, startMillis);
        }
        catch (IOException e)
        {
            ConfigurationFileExportSupport.deleteQuietly(partial);
            String written = e instanceof ConfigurationFileExportSupport.LeftAtDestinationException ? "" //$NON-NLS-1$
                : " Nothing was written to " + request.outputFile + '.'; //$NON-NLS-1$
            throw new IllegalStateException("The dump was not published: " + e.getMessage() + '.' //$NON-NLS-1$
                + written + " Retry; if it repeats, check the EDT log.", e); //$NON-NLS-1$
        }
        progress.add("Wrote " + published.sizeBytes() + " bytes to " + published.path() + '.'); //$NON-NLS-1$ //$NON-NLS-2$
        return renderReport(request, applicationId, application, infobase, sync, published);
    }

    /**
     * What an abandoned dump may leave behind. EDT kills a Designer process it started, but does
     * not stop a dump in its Designer-agent session, so the temporary file can appear later.
     */
    static String abandonedDumpNote(Path outputFile, Path partial)
    {
        return outputFile + " was not created. The Designer may still be running (EDT does not " //$NON-NLS-1$
            + "stop a dump in its Designer-agent session): if " + partial + " appears later, " //$NON-NLS-1$ //$NON-NLS-2$
            + "delete it."; //$NON-NLS-1$
    }

    static String renderReport(Request request, String applicationId, IApplication application,
        InfobaseReference infobase, SyncReading sync, PublishedFile published)
    {
        String extensionName = request.target.extensionName;
        FrontMatter fm = FrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .put("source", extensionName == null ? "configuration" : "extension") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .put(McpKeys.PROJECT, request.target.configurationProject.getName());
        if (extensionName != null)
        {
            fm.put(KEY_EXTENSION_NAME, extensionName);
        }
        fm.put(McpKeys.APPLICATION_ID, applicationId)
            .put("application", safe(application.getName())) //$NON-NLS-1$
            .put("infobase", safe(infobase.getName())) //$NON-NLS-1$
            .put(KEY_OUTPUT_FILE, published.path().toString())
            .put("sizeBytes", published.sizeBytes()) //$NON-NLS-1$
            .put("infobaseSync", sync.state().wire()); //$NON-NLS-1$

        StringBuilder body = new StringBuilder();
        body.append(extensionName == null ? "# Configuration exported to a .cf file\n\n" //$NON-NLS-1$
            : "# Extension " + MarkdownUtils.inlineCode(extensionName) + " exported to a .cfe file\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        body.append("- File: ").append(MarkdownUtils.inlineCode(published.path().toString())) //$NON-NLS-1$
            .append(" - ").append(published.sizeBytes()).append(" bytes, written ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(Instant.ofEpochMilli(published.lastModifiedMillis())).append('\n');
        body.append("- Source: the infobase ").append(MarkdownUtils.inlineCode(safe(infobase.getName()))) //$NON-NLS-1$
            .append(" of application ").append(MarkdownUtils.inlineCode(applicationId)) //$NON-NLS-1$
            .append(" (project ").append(MarkdownUtils.inlineCode(request.target.configurationProject.getName())) //$NON-NLS-1$
            .append(")\n"); //$NON-NLS-1$
        body.append("- Infobase vs project: ").append(sync.state().wire()).append(" - ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(MarkdownUtils.escapeMarkdown(sync.detail())).append('\n');
        if (sync.state() == SyncState.OUT_OF_DATE)
        {
            body.append("\n> The infobase does NOT match the project (allowOutOfDate=true): this file ") //$NON-NLS-1$
                .append("holds the infobase's content, not the project's current state.\n"); //$NON-NLS-1$
        }
        else if (sync.state() == SyncState.UNKNOWN)
        {
            body.append("\n> EDT could not tell whether the infobase matches the project, so this ") //$NON-NLS-1$
                .append("file holds whatever the infobase contains. Run update_database first if it ") //$NON-NLS-1$
                .append("must reflect the project.\n"); //$NON-NLS-1$
        }
        return fm.wrapContent(body.toString());
    }

    private static String describeSource(Request request)
    {
        return request.target.extensionName == null ? "configuration" //$NON-NLS-1$
            : "extension '" + request.target.extensionName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A phase bound that never outlasts what is left of the job's own budget. */
    private static long boundedBy(ProgressReporter progress, long phaseMax)
    {
        long remaining = progress.remainingMillis();
        return Math.max(1L, Math.min(phaseMax, remaining));
    }

    private static String seconds(long millis)
    {
        return (millis / 1000L) + " s"; //$NON-NLS-1$
    }

    private static String safe(String value)
    {
        return value == null ? "" : value; //$NON-NLS-1$
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

    /**
     * An open extension project of the configuration being exported.
     *
     * @param project the extension project
     * @param configurationName its configuration's name, or {@code null} when its model is not ready
     */
    record ExtensionCandidate(IProject project, String configurationName)
    {
    }

    /** What is exported and against which project its staleness is judged. */
    static final class Target
    {
        final IProject configurationProject;
        final String extensionName;
        /** The project the dump corresponds to; {@code null} for an extension with no project. */
        final IProject syncProject;
        final String error;

        Target(IProject configurationProject, String extensionName, IProject syncProject, String error)
        {
            this.configurationProject = configurationProject;
            this.extensionName = extensionName;
            this.syncProject = syncProject;
            this.error = error;
        }

        static Target error(String message)
        {
            return new Target(null, null, null, message);
        }
    }

    /** One validated call. */
    static final class Request
    {
        final Target target;
        final String applicationId;
        final Path outputFile;
        final boolean allowOutOfDate;

        Request(Target target, String applicationId, Path outputFile, boolean allowOutOfDate)
        {
            this.target = target;
            this.applicationId = applicationId;
            this.outputFile = outputFile;
            this.allowOutOfDate = allowOutOfDate;
        }
    }
}
