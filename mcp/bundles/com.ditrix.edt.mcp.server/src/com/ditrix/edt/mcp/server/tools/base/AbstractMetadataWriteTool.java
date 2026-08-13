/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.base;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Base class for metadata write tools that mutate the EDT model
 * (create / add / delete) and therefore must run on the UI thread.
 * <p>
 * Centralizes the boilerplate shared by all such tools:
 * <ul>
 * <li>JSON response type;</li>
 * <li>marshalling the call onto the SWT UI thread via {@link Display#syncExec}
 * with unified error handling (logs and returns a {@link ToolResult} error);</li>
 * <li>resolving the {@link IProject} and its {@link Configuration};</li>
 * <li>unwrapping the underlying cause message thrown from a BM write task.</li>
 * </ul>
 * Subclasses implement {@link #executeOnUiThread(Map)}, which is already invoked
 * on the UI thread.
 */
public abstract class AbstractMetadataWriteTool implements IMcpTool
{
    /**
     * How long to wait for a write's {@code .mdo} export to reach disk before refusing.
     * <p>
     * The same value {@code rename_metadata_object} already uses to let the pipeline settle, and
     * the headroom is measured rather than guessed: on a healthy workspace the bytes land 0.02s
     * (create) to 0.15s (delete) after the tool returns, so this is ~400x the observed worst case,
     * and it is 6x the 10s the e2e suite polls for - a window CI has been seen to exceed.
     */
    private static final long EXPORT_DEADLINE_MS = 60_000L;

    /** The result member every tool sets to say whether it succeeded. */
    private static final String KEY_SUCCESS = "success"; //$NON-NLS-1$

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public final String execute(Map<String, String> params)
    {
        String preUiError = beforeUiThreadOrError(params);
        if (preUiError != null)
        {
            return ToolResult.error(preUiError).toJson();
        }

        // Refuse to mutate the model while the project's derived data is still building:
        // a delete cascade would resolve an incomplete reference set (silently missing
        // affected references), and a create/add would see a stale duplicate/parent
        // lookup. Only the transient BUILDING state is refused here; a missing/closed
        // project falls through to resolveProjectAndConfig's value-naming error. Checked
        // on the calling thread before marshalling onto the UI thread.
        String building = ProjectStateChecker.buildingErrorOrNull(params.get("projectName")); //$NON-NLS-1$
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        AtomicReference<String> resultRef = new AtomicReference<>();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                resultRef.set(executeOnUiThread(params));
            }
            catch (Exception e)
            {
                Activator.logError("Error in " + getName(), e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        // Deliberately AFTER syncExec returns, i.e. off the UI thread: the export runs on EDT's
        // derived-data pipeline, and waiting for it while holding the UI thread is how a headless
        // MCP call turns into a hung workbench.
        return awaitDiskExport(params, resultRef.get());
    }

    /**
     * Turns a model change whose export has not reached disk into a refusal.
     * <p>
     * A write tool that answers "done" while its {@code .mdo} export is still queued makes the
     * caller's next step unsafe: the two files a top-object change touches (the object's own
     * {@code .mdo} and the owning {@code Configuration.mdo}) are separate export tasks with no
     * ordering between them, so the working tree passes through a state where the configuration
     * references an object whose file is already gone. An agent that commits there commits a
     * broken configuration.
     * <p>
     * The refusal is not a false alarm: it fires only once the wait has actually been made and has
     * failed to establish that the queue drained, so the disk is not known to be what the response
     * would have claimed. It says outright that nothing was undone, because the alternative -
     * staying silent about work that already happened - is the failure this whole path exists to
     * prevent.
     *
     * @param params the tool parameters
     * @param result the JSON the tool produced
     * @return {@code result} unchanged, or an actionable error when the export is still pending
     */
    // Protected, not package-visible: a subclass's own test drives the barrier through this entry
    // to pin that its post-barrier work is ordered AFTER the drain, and that ordering is not
    // observable from anywhere else.
    protected String awaitDiskExport(Map<String, String> params, String result)
    {
        // Parsed once, and only a SUCCESS is parsed at all: an error is a well-formed JSON object
        // too, and treating one as a write would make a rejected argument wait out the whole
        // deadline and then be re-reported as a disk problem.
        JsonObject success = successObject(result);
        if (success == null)
        {
            return result;
        }
        Collection<String> projects = exportProjectsToAwait(params, success);
        if (projects == null || projects.isEmpty())
        {
            // Nothing to WAIT for, but the post-wait step still runs: a tool may have work that
            // must not start until the barrier is behind it, and skipping it here would silently
            // drop that work for exactly the calls that queued nothing. Reported as established:
            // this call put nothing in the queue, so there is nothing about it left unfinished.
            return refreshAfterExportAwait(params, result, true);
        }
        // ONE budget for the whole set, not one per project: a cascade that touches the base and
        // three extensions must not be able to take four deadlines to answer.
        long deadlineAtMs = System.currentTimeMillis() + EXPORT_DEADLINE_MS;
        // Tracked, because DRAINED and UNOBSERVABLE are not the same news for a tool whose next
        // step reads the disk: only the first says the export finished. Passing them on as one
        // would be the "wider than the code" mistake this PR keeps finding.
        boolean drainEstablished = true;
        for (String projectName : projects)
        {
            if (projectName == null || projectName.isEmpty())
            {
                // A named-but-unusable entry is not a project we established anything about, so it
                // must not leave the verdict at its optimistic initial value: skipping the wait is
                // not the same as the wait having succeeded.
                drainEstablished = false;
                continue;
            }
            long remainingMs = Math.max(1L, deadlineAtMs - System.currentTimeMillis());
            BuildUtils.DiskExportState state =
                exportEnvironment().waitForDiskExport(projectName, remainingMs);
            if (state == BuildUtils.DiskExportState.PENDING)
            {
                return exportNotConfirmed(projectName);
            }
            drainEstablished &= state == BuildUtils.DiskExportState.DRAINED;
        }
        return refreshAfterExportAwait(params, result, drainEstablished);
    }

    /**
     * The refusal for an export this call queued and could not see finish.
     * <p>
     * Every clause is kept to what PENDING actually establishes. It says "not confirmed", not
     * "timed out", because PENDING also covers a wait that failed outright rather than running out
     * of time; it says the files MAY be inconsistent, not that they are, because an unconfirmed
     * export is unknown rather than known-bad; and it says "nothing was rolled back" rather than
     * "the model change stands", because this base class is also inherited by a tool that reports
     * on disk without changing the model at all.
     *
     * @param projectName the project whose export did not confirm
     * @return the JSON error
     */
    private static String exportNotConfirmed(String projectName)
    {
        return ToolResult.error("The operation completed, but its export to disk was not confirmed " //$NON-NLS-1$
            + "within a " + (EXPORT_DEADLINE_MS / 1000) + "s budget, so the files of project '" //$NON-NLS-1$ //$NON-NLS-2$
            + projectName + "' may not be consistent on disk: an object's own .mdo can already be " //$NON-NLS-1$
            + "written or deleted while Configuration.mdo still holds the old collection. Nothing was " //$NON-NLS-1$
            + "rolled back. Do not commit the working tree in this state. Check the project with " //$NON-NLS-1$
            + "list_projects, wait for it to report ready, then use resync_to_disk to write out what " //$NON-NLS-1$
            + "is still pending.").toJson(); //$NON-NLS-1$
    }

    /**
     * Which export queues this call asks to see drained before it answers - the projects it
     * CLAIMS to have written. An empty collection means "await nothing".
     * <p>
     * Deliberately a claim rather than a guarantee: a tool may knowingly under-claim, and
     * {@code delete_metadata} does for the cascade case. The barrier is only ever as complete as
     * the answer it is given.
     * <p>
     * The single question the barrier asks. It is deliberately asked of the RESULT and returns a
     * SET rather than a yes/no, because both halves of "wait for what" vary per call and neither
     * can be read off the arguments or off the class:
     * <ul>
     * <li>{@code apply_quick_fix} rewrites BSL source and queues no {@code .mdo} export at all, so
     * waiting could only let unrelated work in the same project refuse a healthy edit;</li>
     * <li>{@code adopt_metadata_object} is called with the BASE configuration by contract and
     * writes into the EXTENSION;</li>
     * <li>a confirmed {@code delete_metadata} cascade also cleans references in the dependent
     * extensions, whose exports this hook does NOT currently claim - see the reason and the cost
     * at that tool's override;</li>
     * <li>an adoption of an already-adopted object, a resync of an in-sync project and a delete
     * PREVIEW are successes that queued nothing.</li>
     * </ul>
     * Asking "did it write?" and "whose project?" separately is what produced those as four
     * separate defects; asking which exports THIS call queued puts them all to one question, so a
     * tool added later answers it in one place instead of becoming a fifth. It does not follow that
     * every answer is complete - a tool can still under-claim, and {@code delete_metadata} knowingly
     * does for the cascade case.
     * <p>
     * The default is the project named in {@code projectName}: a tool that says nothing is assumed
     * to have written where it was asked to.
     *
     * @param params the tool parameters
     * @param result the tool's own result, already known to be a success
     * @return the projects to await; empty to skip the wait entirely
     */
    protected Collection<String> exportProjectsToAwait(Map<String, String> params, JsonObject result)
    {
        String projectName = params.get(McpKeys.PROJECT_NAME);
        return projectName == null || projectName.isEmpty() ? Collections.emptyList()
            : Collections.singletonList(projectName);
    }

    /**
     * Lets a tool restate anything in its result that the export wait has just made out of date.
     * <p>
     * A tool that reports on DISK state samples it inside {@code executeOnUiThread}, which is
     * before the barrier ran - so a field like "these files are still missing" can describe a
     * moment that no longer exists by the time the caller reads it. The default changes nothing;
     * only a tool that reports disk state has anything to restate.
     *
     * @param params the tool parameters
     * @param result the JSON the tool produced
     * @param drainEstablished whether the export was actually observed to finish. {@code false}
     *     means the barrier could not observe the export state at all - NOT that anything failed,
     *     and NOT that the disk is current. Work that only makes sense on exported bytes must
     *     check this rather than assume the wait proved something.
     * @return the result to return, possibly updated
     */
    protected String refreshAfterExportAwait(Map<String, String> params, String result,
        boolean drainEstablished)
    {
        return result;
    }

    /**
     * Reads a string member of a result, for a subclass deciding {@link #exportProjectsToAwait}.
     *
     * @param result the tool's own result
     * @param member the member to read
     * @return the value, or {@code null} when absent or not a primitive
     */
    protected static String resultString(JsonObject result, String member)
    {
        JsonElement value = result.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /**
     * Parses a tool result and returns it only when it is a SUCCESS.
     * <p>
     * Success is decided by the explicit {@code success} boolean rather than by "the payload
     * parsed", because an error is a well-formed JSON object too.
     *
     * @param result the JSON the tool produced
     * @return the parsed object, or {@code null} when it is not a successful JSON object
     */
    private static JsonObject successObject(String result)
    {
        if (result == null)
        {
            return null;
        }
        try
        {
            JsonElement parsed = JsonParser.parseString(result);
            if (!parsed.isJsonObject())
            {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            JsonElement success = object.get(KEY_SUCCESS);
            boolean ok = success != null && success.isJsonPrimitive()
                && success.getAsJsonPrimitive().isBoolean() && success.getAsBoolean();
            return ok ? object : null;
        }
        catch (RuntimeException e)
        {
            // A payload we cannot read is not evidence of a disk problem, and the work already
            // happened - degrade to "do not gate", never to a refusal built on a guess.
            Activator.logError("Could not read a metadata write result while checking its export", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Seam so the export barrier can be exercised without a live derived-data pipeline. It takes
     * the project NAME rather than an {@link IProject} so the workspace lookup lives behind it too
     * - otherwise the decision could not be tested without a running workspace, which is exactly
     * the part worth pinning.
     */
    @FunctionalInterface
    protected interface IExportEnvironment
    {
        /**
         * @param projectName the name of the project whose export queue to drain
         * @param timeoutMs the deadline in milliseconds
         * @return how the wait ended
         */
        BuildUtils.DiskExportState waitForDiskExport(String projectName, long timeoutMs);
    }

    /** The production environment: resolve the project, then run the bounded platform wait. */
    private static final IExportEnvironment PLATFORM_EXPORT_ENVIRONMENT = (projectName, timeoutMs) -> {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        // An absent project has no queue of its own; reporting PENDING for it would refuse a call
        // over a condition that cannot be cured by waiting.
        return project.exists() ? BuildUtils.waitForDiskExport(project, timeoutMs)
            : BuildUtils.DiskExportState.UNOBSERVABLE;
    };

    /**
     * @return the environment the export barrier runs against; overridden only by tests. Protected
     *     rather than package-visible so a subclass's OWN test can observe the barrier - pinning
     *     that a tool's post-barrier work really happens after the drain needs both ends visible
     *     from one place.
     */
    protected IExportEnvironment exportEnvironment()
    {
        return PLATFORM_EXPORT_ENVIRONMENT;
    }

    /**
     * Optional bounded pre-flight that must run before the SWT UI-thread handoff. Most metadata
     * writes need no additional work here; cascade tools may wait on EDT services that themselves
     * need the UI thread while settling.
     *
     * @param params the tool parameters
     * @return an error message, or {@code null} to continue
     */
    protected String beforeUiThreadOrError(Map<String, String> params)
    {
        return null;
    }

    /**
     * Performs the tool logic. Always invoked on the SWT UI thread, so model
     * mutations are safe here. Any thrown exception is logged and converted to a
     * {@link ToolResult} error by {@link #execute(Map)}.
     *
     * @param params the tool parameters
     * @return the JSON result string
     * @throws Exception on unexpected failure
     */
    protected abstract String executeOnUiThread(Map<String, String> params) throws Exception; // NOSONAR propagates checked exceptions across the reflective boundary by design

    /**
     * Holds the resolved project and configuration, or a ready-to-return JSON
     * error string when resolution failed.
     */
    protected static final class ProjectContext
    {
        /** Resolved project; non-null only when {@link #error} is null. */
        public IProject project;
        /** Resolved configuration; non-null only when {@link #error} is null. */
        public Configuration config;
        /** Non-null when resolution failed: a JSON error to return verbatim. */
        public String error;

        public boolean hasError()
        {
            return error != null;
        }
    }

    /**
     * Resolves the EDT project and its configuration, applying the same
     * validation and error messages used across the metadata write tools.
     *
     * @param projectName the project name from the tool parameters
     * @return a {@link ProjectContext}; check {@link ProjectContext#error} first
     */
    protected ProjectContext resolveProjectAndConfig(String projectName)
    {
        ProjectContext ctx = new ProjectContext();

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            // FQN: this class has its own nested ProjectContext, so the shared resolver's
            // standard not-found message is referenced fully-qualified.
            ctx.error = ToolResult.error(
                com.ditrix.edt.mcp.server.utils.ProjectContext.notFoundMessage(projectName)).toJson();
            return ctx;
        }

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            ctx.error = ToolResult.error("Configuration provider not available").toJson(); //$NON-NLS-1$
            return ctx;
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            ctx.error = ToolResult.error("Could not get configuration for project: " + projectName).toJson(); //$NON-NLS-1$
            return ctx;
        }

        ctx.project = project;
        ctx.config = config;
        return ctx;
    }

    /**
     * Returns the most specific failure message from an exception thrown by a BM
     * write task: the cause message when present, otherwise the exception's own.
     *
     * @param e the caught exception
     * @return the resolved message
     */
    protected static String unwrapCauseMessage(Exception e)
    {
        String msg = e.getMessage();
        if (e.getCause() != null && e.getCause().getMessage() != null)
        {
            msg = e.getCause().getMessage();
        }
        return msg;
    }
}
