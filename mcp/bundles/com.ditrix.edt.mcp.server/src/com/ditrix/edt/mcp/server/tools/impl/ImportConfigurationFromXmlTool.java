/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.dt.core.lifecycle.IDtProjectResourceLifecycleBootstrap;
import com._1c.g5.v8.dt.core.lifecycle.ProjectStartType;
import com._1c.g5.v8.dt.core.lifecycle.WorkspaceProjectStartRequest;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IWorkspaceOrchestrator;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BoundedJob;
import com.ditrix.edt.mcp.server.utils.CliReflectionErrors;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.McpJobs;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.ditrix.edt.mcp.server.utils.WorkspacePaths;

/**
 * Tool that wraps the EDT "Import &rarr; Configuration from XML Files" action.
 *
 * <p>Imports a configuration from a directory of XML source files into a new
 * EDT project in the workspace. The reverse of
 * {@link ExportConfigurationToXmlTool}.
 *
 * <p>Wraps {@code com._1c.g5.v8.dt.cli.api.workspace.IImportConfigurationFilesApi}
 * via reflection so this bundle has no build-time dependency on the API
 * package.
 *
 * <h2>Why the import alone does not produce a usable project (issue #647)</h2>
 *
 * <p>The CLI API creates and opens the {@link IProject}, then calls
 * {@code IDtProjectResourceLifecycleBootstrap.registerImport(project)}, which parks a MANUAL
 * start latch with {@code startAllowed = false} on it, and runs the import operation
 * synchronously. For a CONFIGURATION that operation is {@code ImportObjectsOperation} - the one
 * EDT import operation that never calls {@code permitImport}, and the CLI API does not call it
 * either. Nothing therefore releases the latch: the project's context is never started,
 * {@code IDtProjectManager.getDtProject(p)} stays {@code null} forever, {@code list_projects}
 * reports {@code not_available}, and - because a blocked latch also stops EDT's
 * {@code DefaultContextsStartJob} from being scheduled at all - context startup of every OTHER
 * project in the workspace stalls with it.
 *
 * <p>{@code setRefreshProject(false)}, which the CLI API also sets, is a SEPARATE and much
 * smaller thing: it skips only the platform's own {@code IProject.refreshLocal(DEPTH_INFINITE)}
 * after the import. It has nothing to do with DtProject registration - the javadoc that used to
 * blame it for the {@code not_available} state was wrong, and so was the close/open/refresh
 * sequence added on the strength of it: a close removes the latch, but the re-open only starts
 * the project when Eclipse emits a POST_CHANGE delta carrying the {@code OPEN} flag, which a
 * batched close+open does not produce.
 *
 * <p>So the project is started EXPLICITLY, exactly the way EDT's own headless command for this
 * API ({@code ImportConfigurationFilesCmd}) does it: refresh the workspace tree and - only when
 * the project is not already started - ask
 * {@link IWorkspaceOrchestrator#startWorkspaceProjects} for a {@link ProjectStartType#CLEAN_IMPORT}
 * start, then wait for {@code isStarted} within a bounded budget. The answer states what was
 * observed: {@code state: ready} when EDT started the project, {@code state: importing} when the
 * budget ran out first (a success, because the files ARE imported and the project name IS taken -
 * an error would push the caller into a re-import that can only fail).
 *
 * <p><b>{@code permitImport} is NOT part of that happy path</b>, and EDT's own command does not
 * call it either. Releasing the latch BEFORE the start request arms a second, competing start:
 * {@code manuallyLockedProjectsExist()} turns false, EDT's watchdog schedules
 * {@code DefaultContextsStartJob}, and that job issues its own {@code startWorkspaceProjects} for
 * the same project - which the {@code !isStarted} gate cannot see, because it has already
 * answered. It is therefore called only as a RECOVERY, when no start of ours is coming: after the
 * post-import step itself threw - the workspace refresh, or the start request (the
 * {@code isStarted} gate or {@code startWorkspaceProjects}) - or when the bounded job that runs
 * the step never entered it at all. The watchdog is then the only
 * remaining route to a started project, and the release also un-freezes context startup for every
 * OTHER project in the workspace.
 */
public class ImportConfigurationFromXmlTool implements IMcpTool
{
    public static final String NAME = "import_configuration_from_xml"; //$NON-NLS-1$

    /** Input param: path of the source directory of XML files. */
    private static final String KEY_IMPORT_PATH = "importPath"; //$NON-NLS-1$

    /**
     * How long the post-import project start is waited for before the tool answers
     * {@code state: importing} (5 minutes).
     *
     * <p>EDT's own CLI command allows 10 minutes for the same wait; half of that is picked here
     * because an MCP call is answered synchronously and a caller that waits ten minutes on one
     * tool call has already lost. The budget is not a deadline on the IMPORT - that has returned
     * by then - only on how long this call watches EDT start the project; when it elapses the
     * start keeps running in EDT and the caller is told to poll {@code list_projects}.
     *
     * <p>Computed rather than written as a literal so it is NOT a compile-time constant: a
     * literal would be inlined into every class that reads it, and the test that holds this number
     * and the guide's number together could then pass against an inlined stale copy.
     */
    static final long PROJECT_START_BUDGET_MS = TimeUnit.SECONDS.toMillis(300);

    /** How often {@code isStarted} is polled while waiting for the project start. */
    private static final long START_POLL_PERIOD_MS = 250L;

    /**
     * The sentence every error answer that released the import latch ends with: EDT's own
     * watchdog may start the project by itself now, stated as the secondary possibility it is,
     * not as the thing to wait for.
     */
    private static final String LATCH_RELEASED_HINT = "The import latch has been released, so EDT " //$NON-NLS-1$
        + "may also recover on its own - `list_projects` reporting the project `ready` would show " //$NON-NLS-1$
        + "that - but do not wait on it."; //$NON-NLS-1$

    /**
     * Project names whose import is running right now, mapped to the millisecond instant the
     * import was claimed.
     *
     * <p>The "project already exists" guard runs BEFORE the CLI API creates the project, so two
     * calls for one name both pass it and then race inside EDT: the second one's
     * {@code setUpProject} fails with "resource already exists" and the FIRST call's rollback
     * deletes the project both of them were building. Observed in the field (issue #647) when a
     * client retried an import that was still running. A name claimed here is refused up front.
     */
    private static final ConcurrentMap<String, Long> IMPORTS_IN_FLIGHT = new ConcurrentHashMap<>();

    /** The production import: the reflective CLI API call this tool has always made. */
    private static final IImportRunner PLATFORM_IMPORT_RUNNER = new CliImportRunner();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create an EDT project from exported 1C configuration XML files. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('import_configuration_from_xml')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_IMPORT_PATH,
                "Path of the source directory of XML files.", true) //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT_NAME,
                "Name of the NEW EDT project to create (must not already exist).", true) //$NON-NLS-1$
            .stringProperty("projectNature", //$NON-NLS-1$
                "Optional EDT nature ID, e.g. 'com._1c.g5.v8.dt.core.V8ConfigurationNature'; empty = auto-detect.") //$NON-NLS-1$
            .stringProperty("xmlVersion", //$NON-NLS-1$
                "Optional XML format version, e.g. '8.3.20'; empty = auto-detect.") //$NON-NLS-1$
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
        return executeWith(params, PLATFORM_IMPORT_RUNNER, null, PROJECT_START_BUDGET_MS);
    }

    /**
     * The whole tool with the import, the project lifecycle and the start budget supplied rather
     * than resolved, so a headless test can drive it end to end - including the concurrency guard,
     * the ORDER of the post-import steps, and the two verdicts the answer can carry.
     *
     * @param params the raw tool arguments
     * @param runner the import to perform (production: the reflective CLI API call)
     * @param lifecycle the platform lifecycle to drive, or {@code null} to resolve the live EDT
     *     services - the production path, which also produces the "service not available" refusal
     * @param startBudgetMs how long to wait for the project start, in milliseconds
     * @return the MARKDOWN answer, or an error JSON
     */
    static String executeWith(Map<String, String> params, IImportRunner runner,
        IImportLifecycle lifecycle, long startBudgetMs)
    {
        String err = JsonUtils.requireArguments(params, KEY_IMPORT_PATH, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }

        String importPathStr = JsonUtils.extractStringArgument(params, KEY_IMPORT_PATH);
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String projectNature = JsonUtils.extractStringArgument(params, "projectNature"); //$NON-NLS-1$
        String xmlVersion = JsonUtils.extractStringArgument(params, "xmlVersion"); //$NON-NLS-1$

        // projectNature and xmlVersion are optional - pass null on empty
        if (projectNature != null && projectNature.isEmpty())
        {
            projectNature = null;
        }
        if (xmlVersion != null && xmlVersion.isEmpty())
        {
            xmlVersion = null;
        }

        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        boolean importApiEntered = false;
        boolean importApiReturned = false;
        boolean claimed = false;
        try
        {
            // Normalize to an absolute path so the underlying CLI API isn't
            // surprised by relative paths resolved against an unexpected
            // working directory. Reject early if the path is missing or is
            // a file (not a directory) so failures are deterministic and
            // the AI agent gets a clear error instead of an opaque API
            // exception.
            Path importPath = Paths.get(importPathStr).toAbsolutePath().normalize();
            boolean outsideWorkspace = WorkspacePaths.isOutsideWorkspace(importPath);
            if (outsideWorkspace)
            {
                Activator.logWarning("import_configuration_from_xml: importPath is OUTSIDE the EDT workspace: " //$NON-NLS-1$
                    + importPath + " (trusted-caller-only — see README Security & trust model)."); //$NON-NLS-1$
            }
            if (!Files.exists(importPath))
            {
                return ToolResult.error(
                    "importPath does not exist: " + importPath).toJson(); //$NON-NLS-1$
            }
            if (!Files.isDirectory(importPath))
            {
                return ToolResult.error(
                    "importPath is not a directory: " + importPath).toJson(); //$NON-NLS-1$
            }

            // Claimed BEFORE the existence guard below, because that guard is exactly what a
            // concurrent import slips past: it answers about a project the other call has not
            // created yet.
            String concurrent = claimImport(projectName);
            if (concurrent != null)
            {
                return concurrent;
            }
            claimed = true;

            // The tool's contract is "import into a NEW project", so reject early
            // if a workspace project with this name already exists. Without this
            // check the underlying EDT API still throws (with a less direct
            // message) and we'd surface it via the catch block - but a clean
            // up-front error is friendlier and matches the validation pattern
            // used elsewhere (DeleteMetadataTool, CleanProjectTool, etc.).
            IProject existing = workspace.getRoot().getProject(projectName);
            if (existing != null && existing.exists())
            {
                return ToolResult.error(
                    "Project already exists in workspace: " + projectName //$NON-NLS-1$
                  + ". Import requires a new project name.").toJson(); //$NON-NLS-1$
            }

            Object api = runner.importApi();
            if (api == null)
            {
                return ToolResult.error(
                    "IImportConfigurationFilesApi is not available. " //$NON-NLS-1$
                  + "Required EDT plugin com._1c.g5.v8.dt.cli.api is not installed.").toJson(); //$NON-NLS-1$
            }

            IImportLifecycle effectiveLifecycle = lifecycle;
            if (effectiveLifecycle == null)
            {
                // Resolved BEFORE the import, not after: a missing lifecycle service would
                // otherwise be discovered with the project already created and unstartable.
                String missing = missingLifecycleServiceError();
                if (missing != null)
                {
                    return missing;
                }
                effectiveLifecycle = platformLifecycle();
            }

            importApiEntered = true;
            runner.runImport(api, importPath, projectName, projectNature, xmlVersion);
            importApiReturned = true;

            return reportImportedProject(workspace, projectName, importPath, outsideWorkspace,
                effectiveLifecycle, startBudgetMs);
        }
        catch (Exception e)
        {
            String error = CliReflectionErrors.toErrorJson(e, "Import", "CLI"); //$NON-NLS-1$ //$NON-NLS-2$
            IProject created = workspace.getRoot().getProject(projectName);
            // Derived from the workspace after the failure: the reflective import may have
            // succeeded and only the post-import lifecycle step failed.
            if (importApiReturned || created != null && created.exists())
            {
                return ToolResult.markErrorAfterMutation(error);
            }
            return importApiEntered ? ToolResult.markErrorWithUnknownMutationOutcome(error) : error;
        }
        finally
        {
            if (claimed)
            {
                IMPORTS_IN_FLIGHT.remove(projectName);
            }
        }
    }

    /**
     * Claims {@code projectName} for this import.
     *
     * @param projectName the project the caller wants to create
     * @return {@code null} when the claim was taken, otherwise the refusal JSON for a caller whose
     *     import of that same project is still running
     */
    private static String claimImport(String projectName)
    {
        long now = System.currentTimeMillis();
        // Eclipse project names are case-sensitive on the resource layer, so the claim compares
        // the name exactly - folding case here would refuse two imports that really are distinct.
        Long startedAt = IMPORTS_IN_FLIGHT.putIfAbsent(projectName, Long.valueOf(now));
        if (startedAt == null)
        {
            return null;
        }
        long seconds = Math.max(0L, (now - startedAt.longValue()) / 1000L);
        return ToolResult.error("An import into project `" + projectName //$NON-NLS-1$
            + "` is already in progress (started " + seconds //$NON-NLS-1$
            + (seconds == 1 ? " second ago). " : " seconds ago). ") //$NON-NLS-1$ //$NON-NLS-2$
            + "Wait for it to finish, then poll `list_projects`; do not retry the import - a " //$NON-NLS-1$
            + "second import of the same name races the first and can destroy both.").toJson(); //$NON-NLS-1$
    }

    /**
     * Starts the freshly imported project and renders the answer that states what was observed.
     *
     * @param workspace the workspace the import wrote into
     * @param projectName the imported project name
     * @param importPath the normalized source directory
     * @param outsideWorkspace whether {@code importPath} lies outside the EDT workspace
     * @param lifecycle the platform lifecycle to drive
     * @param startBudgetMs how long to wait for the project start, in milliseconds
     * @return the MARKDOWN answer, or an error JSON marked as "after mutation"
     */
    private static String reportImportedProject(IWorkspace workspace, String projectName,
        Path importPath, boolean outsideWorkspace, IImportLifecycle lifecycle, long startBudgetMs)
    {
        IProject project = workspace.getRoot().getProject(projectName);
        if (!lifecycle.projectExists(project))
        {
            // The API returned normally, so something WAS done; there is simply no project to
            // report on. Staying silent here (as the old refresh helper did) told the caller
            // "success" about a project that does not exist.
            return ToolResult.markErrorAfterMutation(ToolResult.error(
                "The import of '" + projectName + "' from " + importPath //$NON-NLS-1$ //$NON-NLS-2$
                + " returned without an error, but the workspace holds no project of that name, " //$NON-NLS-1$
                + "so there is nothing to start and nothing to report on. Check the EDT error " //$NON-NLS-1$
                + "log, then retry the import.").toJson()); //$NON-NLS-1$
        }

        StartOutcome outcome = startImportedProject(project, projectName, lifecycle, startBudgetMs);
        return renderImportAnswer(projectName, importPath, outsideWorkspace, outcome,
            startBudgetMs);
    }

    /**
     * Renders the answer for one observed post-import outcome.
     *
     * <p>Separate from the step that produces the outcome so every one of the seven
     * {@link StartPhase}s the step can end in is exercisable headless - including the three that a
     * live run only reaches by missing a five-minute deadline.
     *
     * @param projectName the imported project name
     * @param importPath the normalized source directory
     * @param outsideWorkspace whether {@code importPath} lies outside the EDT workspace
     * @param outcome what the post-import step observed
     * @param startBudgetMs the budget the wait was given, in milliseconds
     * @return the MARKDOWN answer, or an error JSON marked as "after mutation"
     */
    static String renderImportAnswer(String projectName, Path importPath, boolean outsideWorkspace,
        StartOutcome outcome, long startBudgetMs)
    {
        long budgetSeconds = startBudgetMs / 1000L;
        if (outcome.phase == StartPhase.REFRESH_FAILED)
        {
            // No start request was ever issued, so "poll list_projects until ready" is not a
            // recovery here - only the manual close/reopen is. The latch WAS released on this
            // path (see refresh()), so EDT's own watchdog may still start the project, and that is
            // stated as the secondary possibility it is, not as the thing to wait for.
            return ToolResult.markErrorAfterMutation(ToolResult.error(
                "The XML files were imported into project '" + projectName //$NON-NLS-1$
                + "', but the workspace refresh failed before any start was requested: " //$NON-NLS-1$
                + PlatformFailures.describe(outcome.failure)
                + ". The project exists on disk and its name is taken, so do NOT re-import it; " //$NON-NLS-1$
                + "close and reopen the project in EDT to start it. " + LATCH_RELEASED_HINT).toJson()); //$NON-NLS-1$
        }
        if (outcome.phase == StartPhase.FAILED)
        {
            return ToolResult.markErrorAfterMutation(ToolResult.error(
                "The XML files were imported into project '" + projectName //$NON-NLS-1$
                + "', but EDT could not be asked to start it: " //$NON-NLS-1$
                + PlatformFailures.describe(outcome.failure)
                + ". The project exists on disk and its name is taken, so do NOT re-run the " //$NON-NLS-1$
                + "import; poll `list_projects` until it reports `ready`, and if it never does, " //$NON-NLS-1$
                + "close and reopen the project in EDT.").toJson()); //$NON-NLS-1$
        }
        if (outcome.phase == StartPhase.NOT_SCHEDULED)
        {
            // No start was ever requested here either, and the latch WAS released (see
            // releaseLatchAfterAStartThatNeverRan): the same shape as REFRESH_FAILED - the manual
            // recovery first, the watchdog as the secondary possibility.
            return ToolResult.markErrorAfterMutation(ToolResult.error(
                "The XML files were imported into project '" + projectName //$NON-NLS-1$
                + "', but the start could not be scheduled: EDT's job scheduler never ran it, so " //$NON-NLS-1$
                + "nothing has asked EDT to start the project. The project exists on disk and " //$NON-NLS-1$
                + "its name is taken, so do NOT re-import it; close and reopen the project in " //$NON-NLS-1$
                + "EDT to start it. " + LATCH_RELEASED_HINT).toJson()); //$NON-NLS-1$
        }

        // Action result: status + the source path, the created project name and the state EDT
        // left it in. There is no round-trip ID, machine-structured position, declared
        // outputSchema, or UI-bound payload here, so MARKDOWN is the right format
        // (see the "Response format policy" in README / edt-mcp-tool-conventions).
        boolean ready = outcome.phase == StartPhase.STARTED;
        FrontMatter fm = FrontMatter.create()
            .put("tool", NAME) //$NON-NLS-1$
            .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
            .put(McpKeys.PROJECT, projectName)
            .put(KEY_IMPORT_PATH, importPath.toString())
            .put("state", ready ? "ready" : "importing") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .put("projectReady", ready); //$NON-NLS-1$
        if (!ready)
        {
            fm.put("startWaitSeconds", budgetSeconds); //$NON-NLS-1$
        }
        if (outsideWorkspace)
        {
            fm.put("outsideWorkspace", true); //$NON-NLS-1$
        }

        StringBuilder body = new StringBuilder();
        body.append("# Configuration imported from XML files\n\n"); //$NON-NLS-1$
        body.append("- Project: ").append(projectName).append('\n'); //$NON-NLS-1$
        body.append("- Import path: ").append(importPath).append('\n'); //$NON-NLS-1$
        if (ready)
        {
            body.append("- Project state: EDT has started this project. `list_projects` may " //$NON-NLS-1$
                + "still report it `building` for a short while, until EDT finishes computing " //$NON-NLS-1$
                + "its derived data.\n"); //$NON-NLS-1$
        }
        else if (outcome.phase == StartPhase.REFRESHING)
        {
            body.append("- Project state: after ").append(budgetSeconds) //$NON-NLS-1$
                .append(" seconds the workspace refresh of the imported files was STILL running, ") //$NON-NLS-1$
                .append("so the start request has not been issued yet. That refresh keeps running ") //$NON-NLS-1$
                .append("in the background and issues the start request when it finishes. Poll ") //$NON-NLS-1$
                .append("`list_projects` until this project reports `ready`; if it never does, ") //$NON-NLS-1$
                .append("close and reopen the project in EDT. Do not repeat the import.\n"); //$NON-NLS-1$
        }
        else if (outcome.phase == StartPhase.START_REQUESTED)
        {
            body.append("- Project state: the start request was handed to EDT after the workspace ") //$NON-NLS-1$
                .append("refresh finished, and EDT had still not returned from it after ") //$NON-NLS-1$
                .append(budgetSeconds).append(" seconds. The import itself is done and the files ") //$NON-NLS-1$
                .append("are on disk. Poll `list_projects` until this project reports `ready` ") //$NON-NLS-1$
                .append("before calling model tools on it, and do not repeat the import.\n"); //$NON-NLS-1$
        }
        else
        {
            // START_ISSUED: the request returned (or the project was already started) and the
            // wait for isStarted ran out.
            body.append("- Project state: the start request was issued and EDT is still starting ") //$NON-NLS-1$
                .append("this project - it had not finished after ").append(budgetSeconds) //$NON-NLS-1$
                .append(" seconds. The import itself is done and the files are on disk. Poll ") //$NON-NLS-1$
                .append("`list_projects` until this project reports `ready` before calling model ") //$NON-NLS-1$
                .append("tools on it, and do not repeat the import.\n"); //$NON-NLS-1$
        }
        if (outsideWorkspace)
        {
            body.append('\n')
                .append("> Note: importPath is outside the EDT workspace; ") //$NON-NLS-1$
                .append("ensure the caller is trusted.\n"); //$NON-NLS-1$
        }

        return fm.wrapContent(body.toString());
    }

    /**
     * The post-import lifecycle step on the production job scheduler.
     *
     * @param project the imported project
     * @param projectName the imported project name, for the job label
     * @param lifecycle the platform lifecycle to drive
     * @param budgetMs how long to wait for the start, in milliseconds
     * @return what was observed
     * @see #startImportedProject(IProject, String, IImportLifecycle, long, BoundedJob.IJobScheduler)
     */
    static StartOutcome startImportedProject(IProject project, String projectName,
        IImportLifecycle lifecycle, long budgetMs)
    {
        return startImportedProject(project, projectName, lifecycle, budgetMs, McpJobs::schedule);
    }

    /**
     * The post-import lifecycle step, with the platform injected so a headless test can drive it.
     *
     * <p>Order is load-bearing and is what the pins protect: the workspace refresh the CLI API
     * disabled happens FIRST, so the start sees the imported files rather than an empty tree; and
     * the start request is issued ONLY for a project EDT has not started, because
     * {@code startWorkspaceProjects} begins by STOPPING the contexts of dependent projects.
     * {@code permitImport} is deliberately absent from this path - see the class javadoc - and is
     * called only when no start of ours is coming: the refresh or the start request itself threw,
     * or the bounded job never entered the step at all
     * ({@link #releaseLatchAfterAStartThatNeverRan}).
     *
     * <p>All of it runs inside a {@link BoundedJob}, because
     * {@code refreshLocal(DEPTH_INFINITE)} over a large configuration and
     * {@code startWorkspaceProjects} are both minutes of platform work that must never hold the
     * MCP dispatcher thread.
     *
     * <p><b>But the deadline must not CANCEL that work.</b> {@link BoundedJob} cancels the job on
     * expiry, which only sets its monitor's flag - the body keeps running - so a platform call
     * that polls the monitor would unwind with {@code OperationCanceledException} and the steps
     * behind it would never run at all. On a large dump the refresh alone can outlive the budget,
     * and cancelling it there would mean the start request is never issued while the answer still
     * tells the caller to wait for a project that will never start: issue #647, reinstated and
     * hidden. The platform calls therefore receive a monitor that never reports cancellation, and
     * ONLY the poll loop is bounded; the job runs the refresh and the start to completion in the
     * background. What the answer says is decided by how far the step had got
     * ({@link StartPhase}), not by how the wait ended - and the phase is advanced when a platform
     * call is ENTERED, not when it returns, because either call can be the one still running at
     * the deadline.
     *
     * <p>A failure the step raises AFTER the deadline has nowhere to go: the caller has already
     * been answered {@code state: importing} and told to poll, and {@link BoundedJob} never reads
     * its failure holder on that path. It is therefore logged from the job thread itself - with
     * the project and the step it failed in - and rethrown unchanged. Before the deadline nothing
     * is logged: the failure reaches the caller as the {@link StartPhase#FAILED} /
     * {@link StartPhase#REFRESH_FAILED} verdict, which is the caller's to act on.
     *
     * @param project the imported project
     * @param projectName the imported project name, for the job label
     * @param lifecycle the platform lifecycle to drive
     * @param budgetMs how long to wait for the start, in milliseconds
     * @param scheduler what hands the bounded job to the job manager; production passes
     *     {@code McpJobs::schedule}, a test may hold the job back to produce a start that never
     *     runs
     * @return what was observed
     */
    static StartOutcome startImportedProject(IProject project, String projectName,
        IImportLifecycle lifecycle, long budgetMs, BoundedJob.IJobScheduler scheduler)
    {
        AtomicReference<StartPhase> phase = new AtomicReference<>(StartPhase.REFRESHING);
        // Set the moment the refresh returned: a failure raised while it is still false came from
        // the refresh, so no start request was ever issued (see REFRESH_FAILED).
        AtomicBoolean refreshDone = new AtomicBoolean();
        BoundedJob.Result result = BoundedJob.run(NAME + ": start " + projectName, budgetMs, //$NON-NLS-1$
            monitor -> {
                try
                {
                    IProgressMonitor platformMonitor = new UncancellableMonitor(monitor);
                    refresh(project, lifecycle, platformMonitor);
                    refreshDone.set(true);
                    // Advanced BEFORE the start request is handed to EDT, not when it returns:
                    // startWorkspaceProjects can outlive the budget on its own, and the answer
                    // composed at the deadline must not then blame a refresh that has finished.
                    phase.set(StartPhase.START_REQUESTED);
                    issueStart(project, lifecycle, platformMonitor);
                    phase.set(StartPhase.START_ISSUED);
                    while (!monitor.isCanceled() && !lifecycle.isStarted(project))
                    {
                        Thread.sleep(START_POLL_PERIOD_MS);
                    }
                }
                catch (Throwable t) // NOSONAR rethrown unchanged; caught only to log the late case
                {
                    // The job's own monitor is cancelled by BoundedJob exactly when the deadline
                    // elapses (or the wait is interrupted), and not before. A failure raised while
                    // it is cancelled is one the caller will never hear about otherwise.
                    if (monitor.isCanceled())
                    {
                        Activator.logError(
                            lateFailureMessage(projectName, refreshDone.get(), phase.get(), budgetMs), t);
                    }
                    throw t;
                }
            }, null, scheduler);

        // A timed-out run may still be writing the failure holder, so BoundedJob reports none for
        // it - which is right here: a start that is still running is not a start that failed.
        Throwable failure = result.getFailure();
        if (failure != null)
        {
            return new StartOutcome(
                refreshDone.get() ? StartPhase.FAILED : StartPhase.REFRESH_FAILED, failure);
        }
        if (startNeverRan(result.getOutcome()))
        {
            releaseLatchAfterAStartThatNeverRan(project, projectName, lifecycle);
            return new StartOutcome(StartPhase.NOT_SCHEDULED, null);
        }
        // Asked again on the calling thread: whether the project is usable is a fact about EDT
        // now, not about how this wait ended.
        if (lifecycle.isStarted(project) && lifecycle.hasDtProject(project))
        {
            return new StartOutcome(StartPhase.STARTED, null);
        }
        return new StartOutcome(phase.get(), null);
    }

    /**
     * Releases the import latch after a bounded run that never entered its work.
     *
     * <p>{@link BoundedJob.Outcome#NOT_RUN} and {@link BoundedJob.Outcome#TIMED_OUT_BEFORE_START}
     * both guarantee that the job body did not run and will not: no refresh, no start request -
     * and no release of the latch either, which only the two failure paths ({@link #refresh},
     * {@link #issueStart}) perform. Left alone, the MANUAL latch the CLI import parked would stand
     * for the rest of the EDT session, and while it stands EDT's watchdog schedules
     * {@code DefaultContextsStartJob} for NO project in the workspace. So this is the third and
     * last release site, and the same class as the other two: no start of ours is coming, the
     * watchdog is the only route left, and the release cannot arm a start that competes with ours
     * because ours was never issued.
     *
     * <p>A release that itself throws does not change the verdict. The answer already sends the
     * caller to close and reopen the project in EDT, which drops the latch on close regardless;
     * the failure is logged so an operator can see why the watchdog did not recover by itself.
     *
     * @param project the imported project
     * @param projectName the imported project name, for the log line
     * @param lifecycle the platform lifecycle to drive
     */
    private static void releaseLatchAfterAStartThatNeverRan(IProject project, String projectName,
        IImportLifecycle lifecycle)
    {
        try
        {
            lifecycle.permitImport(project);
        }
        catch (RuntimeException e)
        {
            Activator.logError(NAME + ": the post-import start of project '" + projectName //$NON-NLS-1$
                + "' was never scheduled, and releasing the import latch afterwards failed too; " //$NON-NLS-1$
                + "the project stays unstarted - and EDT starts no other project either - until " //$NON-NLS-1$
                + "it is closed and reopened in EDT.", e); //$NON-NLS-1$
        }
    }

    /**
     * Performs the workspace refresh the CLI API disabled, releasing the import latch when it
     * throws.
     *
     * <p>A refresh that fails leaves the project with NO start request issued, and the caller is
     * told exactly that ({@link StartPhase#REFRESH_FAILED}). Releasing the latch here is the same
     * recovery as in {@link #issueStart} and {@link #releaseLatchAfterAStartThatNeverRan}: EDT's
     * own watchdog is then the only route to a started project, and it cannot take it while the
     * latch is blocked.
     *
     * @param project the imported project
     * @param lifecycle the platform lifecycle to drive
     * @param monitor the monitor handed to the platform call
     * @throws CoreException when the platform refuses the refresh
     */
    private static void refresh(IProject project, IImportLifecycle lifecycle,
        IProgressMonitor monitor) throws CoreException
    {
        try
        {
            lifecycle.refreshLocal(project, monitor);
        }
        catch (CoreException | RuntimeException e)
        {
            lifecycle.permitImport(project);
            throw e;
        }
    }

    /**
     * Issues the start request - only for a project EDT has not started yet - releasing the
     * import latch when the request throws.
     *
     * <p>The request is the {@code isStarted} gate AND {@code startWorkspaceProjects}, both inside
     * one guarded block: a gate that throws has issued no start either, so it is the same failure
     * as a start request that throws, and it must release the latch just the same.
     *
     * <p>{@code permitImport} here, in {@link #refresh}'s failure path and in
     * {@link #releaseLatchAfterAStartThatNeverRan} - the three cases in which no start of ours is
     * coming - and nowhere else: EDT's own watchdog is then the only remaining route to a started
     * project, and it cannot take that route while the latch the CLI import parked is still
     * blocked - which also keeps context startup frozen for every OTHER project in the workspace.
     * On the happy path, and on a wait that ran out while our start is still in EDT's hands, the
     * same call would arm a SECOND, competing start (see the class javadoc), so it must not be
     * made there; nor after a failure raised by the poll that FOLLOWS a returned request.
     *
     * @param project the imported project
     * @param lifecycle the platform lifecycle to drive
     * @param monitor the monitor handed to the platform call
     */
    private static void issueStart(IProject project, IImportLifecycle lifecycle,
        IProgressMonitor monitor)
    {
        try
        {
            if (!lifecycle.isStarted(project))
            {
                lifecycle.startWorkspaceProjects(
                    Collections.singletonList(new WorkspaceProjectStartRequest(project,
                        ProjectStartType.CLEAN_IMPORT)),
                    monitor);
            }
        }
        catch (RuntimeException e)
        {
            lifecycle.permitImport(project);
            throw e;
        }
    }

    /**
     * The log line for a post-import failure raised after the caller had already been answered.
     *
     * <p>Names the project and the step it failed in, because that is what an operator reading
     * the EDT log needs to match it to the {@code state: importing} answer the caller got.
     *
     * <p>{@link StartPhase#START_REQUESTED} with the refresh done is the START step - the request
     * itself was in EDT's hands - and shares its text with the instant before the phase advanced;
     * only {@link StartPhase#START_ISSUED} is the wait AFTER the request returned.
     *
     * @param projectName the imported project name
     * @param refreshDone whether the workspace refresh had returned when the failure was raised
     * @param phase how far the step had got
     * @param budgetMs the wait the caller was given, in milliseconds
     * @return the message
     */
    static String lateFailureMessage(String projectName, boolean refreshDone, StartPhase phase,
        long budgetMs)
    {
        String step;
        if (!refreshDone)
        {
            step = "while refreshing the workspace"; //$NON-NLS-1$
        }
        else if (phase == StartPhase.START_ISSUED)
        {
            step = "while waiting for EDT to start it"; //$NON-NLS-1$
        }
        else
        {
            step = "while asking EDT to start it"; //$NON-NLS-1$
        }
        return NAME + ": the post-import start of project '" + projectName + "' failed " + step //$NON-NLS-1$ //$NON-NLS-2$
            + ", AFTER the " + (budgetMs / 1000L) + "-second wait had already answered the caller " //$NON-NLS-1$ //$NON-NLS-2$
            + "with 'state: importing'. That answer told the caller to poll list_projects for a " //$NON-NLS-1$
            + "project that may now never start; if it does not, close and reopen the project in " //$NON-NLS-1$
            + "EDT."; //$NON-NLS-1$
    }

    /**
     * Whether a bounded run never entered its work at all.
     *
     * <p>The one case that must NOT be reported as "EDT is still starting it": nothing asked EDT
     * to start anything, so no amount of polling will ever make the project ready.
     *
     * @param outcome how the bounded run ended
     * @return {@code true} when the job never ran and will not run
     */
    static boolean startNeverRan(BoundedJob.Outcome outcome)
    {
        return outcome == BoundedJob.Outcome.NOT_RUN
            || outcome == BoundedJob.Outcome.TIMED_OUT_BEFORE_START;
    }

    /**
     * Names the platform lifecycle service EDT did not publish.
     *
     * @return {@code null} when every service the post-import start needs is available, otherwise
     *     the error JSON naming the missing one
     */
    private static String missingLifecycleServiceError()
    {
        Activator activator = Activator.getDefault();
        if (activator == null || activator.getWorkspaceOrchestrator() == null)
        {
            return unavailableServiceError("IWorkspaceOrchestrator"); //$NON-NLS-1$
        }
        if (activator.getDtProjectLifecycleBootstrap() == null)
        {
            return unavailableServiceError("IDtProjectResourceLifecycleBootstrap"); //$NON-NLS-1$
        }
        if (activator.getDtProjectManager() == null)
        {
            return unavailableServiceError("IDtProjectManager"); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Builds the refusal for a platform service EDT has not published.
     *
     * @param serviceName the missing service
     * @return the error JSON
     */
    private static String unavailableServiceError(String serviceName)
    {
        return ToolResult.error(serviceName + " is not available, so an imported project could " //$NON-NLS-1$
            + "not be started and would sit unusable in the workspace. Nothing was imported. " //$NON-NLS-1$
            + "Wait for EDT to finish starting up and retry.").toJson(); //$NON-NLS-1$
    }

    /**
     * Binds the post-import step to the live EDT services.
     *
     * @return the lifecycle backed by {@code IWorkspaceOrchestrator},
     *     {@code IDtProjectResourceLifecycleBootstrap} and {@code IDtProjectManager}
     */
    private static IImportLifecycle platformLifecycle()
    {
        Activator activator = Activator.getDefault();
        IWorkspaceOrchestrator orchestrator = activator.getWorkspaceOrchestrator();
        IDtProjectResourceLifecycleBootstrap bootstrap = activator.getDtProjectLifecycleBootstrap();
        IDtProjectManager dtProjectManager = activator.getDtProjectManager();
        return new IImportLifecycle()
        {
            @Override
            public boolean projectExists(IProject project)
            {
                return project != null && project.exists();
            }

            @Override
            public void refreshLocal(IProject project, IProgressMonitor monitor) throws CoreException
            {
                project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            }

            @Override
            public void permitImport(IProject project)
            {
                bootstrap.permitImport(project);
            }

            @Override
            public boolean isStarted(IProject project)
            {
                return orchestrator.isStarted(project);
            }

            @Override
            public void startWorkspaceProjects(Collection<WorkspaceProjectStartRequest> requests,
                IProgressMonitor monitor)
            {
                orchestrator.startWorkspaceProjects(requests, monitor);
            }

            @Override
            public boolean hasDtProject(IProject project)
            {
                return dtProjectManager.getDtProject(project) != null;
            }
        };
    }

    /** How far the post-import step had got when the answer was composed. */
    enum StartPhase
    {
        /**
         * The workspace refresh of the imported files is still running, so the start request has
         * not been issued yet. The job keeps running and will issue it.
         */
        REFRESHING,

        /**
         * The workspace refresh has returned and the start request has been handed to EDT (the
         * {@code isStarted} gate, then {@code startWorkspaceProjects} for a project not started
         * yet) but has not returned yet. On a large configuration that call alone can outlive the
         * budget; it keeps running and the poll follows it.
         */
        START_REQUESTED,

        /**
         * The start request was issued (or the project was already started) and EDT has not
         * finished starting the project yet.
         */
        START_ISSUED,

        /** EDT reports the project started AND {@code IDtProjectManager} knows a DtProject. */
        STARTED,

        /**
         * The step raised AFTER the workspace refresh had returned - while asking EDT to start
         * the project, or while waiting for it; {@link StartOutcome#failure} carries what.
         */
        FAILED,

        /**
         * The workspace refresh itself raised, so no start request was ever issued;
         * {@link StartOutcome#failure} carries what. Kept apart from {@link #FAILED} because the
         * recovery differs: with no start requested there is nothing to poll for.
         */
        REFRESH_FAILED,

        /**
         * The bounded job never entered its work, so nothing asked EDT to start anything. The
         * one outcome that must not be reported as "still starting". The import latch is
         * released on this path ({@code releaseLatchAfterAStartThatNeverRan}), exactly as after
         * a failed refresh: with no start of ours coming, the watchdog is the only route left.
         */
        NOT_SCHEDULED
    }

    /** What the post-import start observed. */
    static final class StartOutcome
    {
        /** How far the step had got. */
        final StartPhase phase;

        /** The failure the step raised, or {@code null} when it raised none. */
        final Throwable failure;

        StartOutcome(StartPhase phase, Throwable failure)
        {
            this.phase = phase;
            this.failure = failure;
        }
    }

    /**
     * A monitor that forwards progress but never reports cancellation.
     *
     * <p>Handed to the platform calls of the post-import step so the bounded wait's deadline
     * cannot abort them. {@link BoundedJob}'s expiry cancels the job, which only flags its
     * monitor; a platform call that polls the flag would unwind and leave the remaining steps
     * undone, while the answer still told the caller to wait. The bound belongs on how long this
     * CALL waits, not on whether EDT finishes the work.
     */
    private static final class UncancellableMonitor implements IProgressMonitor
    {
        private final IProgressMonitor delegate;

        UncancellableMonitor(IProgressMonitor delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void beginTask(String name, int totalWork)
        {
            delegate.beginTask(name, totalWork);
        }

        @Override
        public void done()
        {
            delegate.done();
        }

        @Override
        public void internalWorked(double work)
        {
            delegate.internalWorked(work);
        }

        @Override
        public boolean isCanceled()
        {
            return false;
        }

        @Override
        public void setCanceled(boolean canceled)
        {
            // Deliberately dropped: the only cancellation this monitor can receive is the
            // deadline's, and honouring it is exactly the defect this class exists to prevent.
        }

        @Override
        public void setTaskName(String name)
        {
            delegate.setTaskName(name);
        }

        @Override
        public void subTask(String name)
        {
            delegate.subTask(name);
        }

        @Override
        public void worked(int work)
        {
            delegate.worked(work);
        }
    }

    /**
     * The import itself, injected so the whole tool - guard, import, post-import contract - can be
     * exercised without an EDT CLI API and without importing anything.
     */
    interface IImportRunner
    {
        /**
         * Resolves the CLI import API.
         *
         * @return the API, or {@code null} when the EDT plugin that provides it is not installed
         */
        Object importApi();

        /**
         * Runs the import. Entering this method is what makes the workspace possibly mutated.
         *
         * @param api the API returned by {@link #importApi()}
         * @param importPath the normalized source directory of XML files
         * @param projectName the project to create
         * @param projectNature the EDT nature id, or {@code null} for auto-detect
         * @param xmlVersion the XML format version, or {@code null} for auto-detect
         * @throws Exception whatever the CLI API or the reflection raises
         */
        void runImport(Object api, Path importPath, String projectName, String projectNature,
            String xmlVersion) throws Exception; // NOSONAR the CLI API is reached by reflection
    }

    /**
     * The platform lifecycle operations the post-import step performs on the imported project,
     * injected so a headless test can drive the step and its ORDER.
     */
    interface IImportLifecycle
    {
        /**
         * Whether the workspace really holds the project the import claimed to create.
         *
         * @param project the project handle
         * @return {@code true} when the project exists
         */
        boolean projectExists(IProject project);

        /**
         * Performs the workspace refresh the CLI API disabled with {@code setRefreshProject(false)}.
         *
         * @param project the project to refresh
         * @param monitor the bounded job's monitor
         * @throws CoreException when the platform refuses the refresh
         */
        void refreshLocal(IProject project, IProgressMonitor monitor) throws CoreException;

        /**
         * Releases the MANUAL start latch {@code registerImport} parked on the project.
         *
         * @param project the imported project
         */
        void permitImport(IProject project);

        /**
         * Whether EDT has started the project's context.
         *
         * @param project the imported project
         * @return {@code true} when the context is started
         */
        boolean isStarted(IProject project);

        /**
         * Asks EDT to start the projects the requests name.
         *
         * @param requests the start requests
         * @param monitor the bounded job's monitor
         */
        void startWorkspaceProjects(Collection<WorkspaceProjectStartRequest> requests,
            IProgressMonitor monitor);

        /**
         * Whether {@code IDtProjectManager} now knows a DtProject for the project - the fact
         * {@code list_projects} reports as a state other than {@code not_available}.
         *
         * @param project the imported project
         * @return {@code true} when a DtProject exists
         */
        boolean hasDtProject(IProject project);
    }

    /** The production import: {@code IImportConfigurationFilesApi.importProject} by reflection. */
    private static final class CliImportRunner implements IImportRunner
    {
        @Override
        public Object importApi()
        {
            Activator activator = Activator.getDefault();
            return activator == null ? null : activator.getImportConfigurationFilesApi();
        }

        @Override
        public void runImport(Object api, Path importPath, String projectName, String projectNature,
            String xmlVersion) throws Exception // NOSONAR the CLI API is reached by reflection
        {
            // importProject(Path importSource, String projectName, String nature, String xmlVersion)
            Method method = api.getClass().getMethod("importProject", //$NON-NLS-1$
                Path.class, String.class, String.class, String.class);
            method.invoke(api, importPath, projectName, projectNature, xmlVersion);
        }
    }
}
