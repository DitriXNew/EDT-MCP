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
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.google.gson.JsonArray;
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

    /**
     * The smallest slice of the shared budget still worth spending on a wait.
     * <p>
     * Not a tuning knob: below this a wait cannot plausibly observe a pipeline drain, while it still
     * starts an un-cancellable platform wait and takes out a per-project claim that lapses after
     * {@code 3x} its own timeout - i.e. it buys nothing and spends the only guard against a second
     * un-cancellable wait for the same project.
     */
    private static final long MIN_USEFUL_WAIT_MS = 1_000L;

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
        WriteScope scope = new WriteScope();
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> WriteScope.runWithScope(scope, () -> {
            // Bound around the tool's own work so that submitting an export IS declaring one: the
            // single place this plugin hands save tasks to the platform records into whatever scope
            // is bound.
            try
            {
                resultRef.set(executeOnUiThread(params));
            }
            catch (Exception e)
            {
                Activator.logError("Error in " + getName(), e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        }));

        // Deliberately AFTER syncExec returns, i.e. off the UI thread: the export runs on EDT's
        // derived-data pipeline, and waiting for it while holding the UI thread is how a headless
        // MCP call turns into a hung workbench.
        return awaitDiskExport(params, resultRef.get(), scope);
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
     * @param scope what the call itself said about where it wrote
     * @return {@code result} unchanged, or an actionable error when the export is still pending
     */
    // Protected, not package-visible: a subclass's own test drives the barrier through this entry
    // to pin that its post-barrier work is ordered AFTER the drain, and that ordering is not
    // observable from anywhere else.
    protected String awaitDiskExport(Map<String, String> params, String result, WriteScope scope)
    {
        // Parsed once, and only a SUCCESS is parsed at all: an error is a well-formed JSON object
        // too, and treating one as a write would make a rejected argument wait out the whole
        // deadline and then be re-reported as a disk problem.
        JsonObject success = successObject(result);
        if (success == null)
        {
            return result;
        }
        WriteScope.Verdict verdict = scope.verdict(defaultProjectsToAwait(params));
        if (verdict.written().isEmpty() && verdict.cascaded().isEmpty())
        {
            // Nothing to WAIT for, but the post-wait step still runs: a tool may have work that
            // must not start until the barrier is behind it, and skipping it here would silently
            // drop that work for exactly the calls that queued nothing. Reported as established:
            // this call put nothing in the queue, so there is nothing about it left unfinished.
            return publish(verdict, refreshAfterExportAwait(params, result, true));
        }
        // ONE budget for the whole set, not one per project: a cascade that touches the base and
        // three extensions must not be able to take four deadlines to answer.
        long deadlineAtMs = System.currentTimeMillis() + exportDeadlineMs();
        // Tracked, because DRAINED and UNOBSERVABLE are not the same news for a tool whose next
        // step reads the disk: only the first says the export finished. Passing them on as one
        // would be the "wider than the code" mistake this PR keeps finding.
        boolean drainEstablished = true;
        // Written first, so the projects a stall can be blamed on get the budget rather than the
        // ones it cannot.
        for (String projectName : verdict.written())
        {
            BuildUtils.DiskExportState state = waitWithin(deadlineAtMs, projectName);
            if (state == BuildUtils.DiskExportState.PENDING)
            {
                return exportNotConfirmed(projectName);
            }
            drainEstablished &= state == BuildUtils.DiskExportState.DRAINED;
        }
        for (String projectName : verdict.cascaded())
        {
            // A stall here can never refuse: this call never went near that project's export path,
            // so a queue that will not drain there is not evidence about this call. Awaiting it is
            // still worth doing - it is the difference between answering over a half-written
            // extension and not - which is why the outcome only clears the "established" flag.
            drainEstablished &= waitWithin(deadlineAtMs, projectName) == BuildUtils.DiskExportState.DRAINED;
        }
        return publish(verdict, refreshAfterExportAwait(params, result, drainEstablished));
    }

    /**
     * @return the shared budget for the whole awaited set; overridden only by tests, so the
     *     "a slice too small to be worth starting" guard can be reached without a 60-second test
     */
    protected long exportDeadlineMs()
    {
        return EXPORT_DEADLINE_MS;
    }

    /**
     * Runs one bounded wait inside the shared budget, or declines to start it.
     * <p>
     * A slice too small to drain anything is not a cheap wait, it is a harmful one: the platform
     * wait it starts cannot be cancelled, while the per-project claim that keeps a second
     * un-cancellable wait from being scheduled lapses after {@code 3x} the timeout - so a 1ms slice
     * buys nothing and gives up the one guard there is.
     * <p>
     * Declining is reported as UNOBSERVABLE, which is the truth - nothing was observed about that
     * project - and which for a project this call WROTE in means a success that establishes nothing
     * about it rather than a refusal. That is deliberate: a refusal has to rest on an observation,
     * and there was none. It is reachable only for the second and later entries of a set, i.e. only
     * since a call could name more than one project.
     *
     * @param deadlineAtMs when the shared budget runs out
     * @param projectName the project to wait for
     * @return how the wait ended, or {@link BuildUtils.DiskExportState#UNOBSERVABLE} when it was not
     *     worth starting
     */
    private BuildUtils.DiskExportState waitWithin(long deadlineAtMs, String projectName)
    {
        if (projectName == null || projectName.isEmpty())
        {
            // A named-but-unusable entry is not a project we established anything about, so it must
            // not leave the verdict at its optimistic initial value: skipping the wait is not the
            // same as the wait having succeeded.
            return BuildUtils.DiskExportState.UNOBSERVABLE;
        }
        long remainingMs = deadlineAtMs - System.currentTimeMillis();
        if (remainingMs < MIN_USEFUL_WAIT_MS)
        {
            return BuildUtils.DiskExportState.UNOBSERVABLE;
        }
        return exportEnvironment().waitForDiskExport(projectName, remainingMs);
    }

    /**
     * Adds the call's own account of where it wrote to the response it is about to return.
     * <p>
     * Done in ONE place, from the very value the barrier waited on, so what the caller is told and
     * what the tool waited for cannot drift apart. Left out entirely - rather than published as an
     * empty list - when the call did not state its scope: an empty list is a finding ("this call
     * wrote in no project of its own"), and showing it for a call that merely could not tell would
     * be the same over-claim this whole path exists to remove.
     *
     * @param verdict the barrier's reading of the call
     * @param result the JSON about to be returned
     * @return the JSON to return
     */
    private static String publish(WriteScope.Verdict verdict, String result)
    {
        Collection<String> projects = verdict.publishable();
        if (projects == null)
        {
            return result;
        }
        JsonObject success = successObject(result);
        if (success == null)
        {
            // The post-wait hook may have turned the success into a refusal; a refusal has no write
            // scope to report.
            return result;
        }
        JsonArray array = new JsonArray();
        for (String project : projects)
        {
            array.add(project);
        }
        success.add(WriteScope.RESULT_MEMBER, array);
        return GsonProvider.toJson(success);
    }

    /**
     * What to wait for when the call said nothing about where it wrote: the project it was asked
     * about. Kept so a tool that declares nothing behaves exactly as it did before #408 rather than
     * silently starting to wait for everything or for nothing.
     *
     * @param params the tool parameters
     * @return the default wait set
     */
    private static Collection<String> defaultProjectsToAwait(Map<String, String> params)
    {
        String projectName = params.get(McpKeys.PROJECT_NAME);
        return projectName == null || projectName.isEmpty() ? Collections.emptyList()
            : Collections.singletonList(projectName);
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
     * Reads a string member of a result, for a subclass restating part of its own answer.
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
        /**
         * Resolved configuration; non-null when {@link #error} is null - EXCEPT for an
         * external-objects project linked to no base configuration, which resolves successfully
         * with a null configuration and a non-null {@link #scope} (issue #309).
         */
        public Configuration config;
        /**
         * The ROOT a metadata FQN resolves against for this project: the configuration, or an
         * external-objects project's own root objects. Non-null whenever {@link #error} is null;
         * use it - not {@link #config} - to resolve an FQN.
         */
        public MetadataScope scope;
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
        return resolveProjectRoot(projectName, false);
    }

    /**
     * Resolves the project and the ROOT a metadata FQN resolves against for it - the configuration,
     * or an external-objects project's own root objects (issue #309).
     *
     * <p>The difference from {@link #resolveProjectAndConfig(String)} is one case: an
     * external-objects project linked to NO base configuration resolves successfully here, with a
     * null {@code config} and a usable {@code scope}. Only a tool that resolves everything through
     * the scope may use this entry; one that dereferences {@code config} must keep the other, which
     * still refuses that case rather than handing it a null.</p>
     *
     * @param projectName the project name from the tool parameters
     * @return a {@link ProjectContext}; check {@link ProjectContext#error} first
     */
    protected ProjectContext resolveProjectAndScope(String projectName)
    {
        return resolveProjectRoot(projectName, true);
    }

    /**
     * {@link #resolveProjectAndScope(String)} plus the TYPE/ROOT check: refuses when {@code fqn}
     * names a type this project kind cannot hold at all - a configuration type addressed at an
     * external-objects project, or a standalone external type addressed at a configuration.
     *
     * <p>Bound to context resolution ON PURPOSE. Every write tool has specialized dispatches
     * that resolve their own context and read the {@code Configuration} directly, and each one
     * that lacked this check answered about the WRONG project: a real owner found in the LINKED
     * base configuration and then "Owner object not found in transaction" when its BM id was
     * used in this project's model, or advice to "create it first" for something this project
     * can never hold. Making the check part of GETTING the context is what stops the next such
     * branch from having to remember it (issue #309).</p>
     *
     * @param projectName the project name from the tool parameters
     * @param fqn the FQN this call addresses; {@code null}/empty checks nothing extra
     * @return a {@link ProjectContext}; check {@link ProjectContext#error} first
     */
    protected ProjectContext resolveProjectAndScope(String projectName, String fqn)
    {
        ProjectContext ctx = resolveProjectAndScope(projectName);
        if (ctx.hasError() || fqn == null || fqn.isEmpty())
        {
            return ctx;
        }
        String hint = ctx.scope.addressingHint(fqn);
        if (!hint.isEmpty())
        {
            ctx.error = ToolResult.error("'" + fqn + "' cannot be addressed in project " //$NON-NLS-1$ //$NON-NLS-2$
                + "'" + projectName + "'." + hint).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ctx;
    }

    private ProjectContext resolveProjectRoot(String projectName, boolean allowNoConfiguration)
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
        MetadataScope scope = MetadataScope.of(project, config);
        // The project HAS no readable root (EDT never started it), so every FQN below would come
        // back "not found" about a project that is simply not up. Refused on BOTH paths - see
        // ProjectContext.unreadableExternalRootMessage.
        if (scope.externalRootUnavailable())
        {
            ctx.error = ToolResult.error(
                com.ditrix.edt.mcp.server.utils.ProjectContext.unreadableExternalRootMessage(
                    projectName)).toJson();
            return ctx;
        }
        // An EXTERNAL-OBJECTS project has no configuration of its own - its roots are its external
        // data processors / reports, and the provider answers with the linked BASE configuration
        // (null when there is none). A scope-driven caller can work without one; a caller that
        // dereferences the configuration cannot, and is refused with the reason. Issue #309.
        if (config == null && !(allowNoConfiguration && scope.isExternalObjects()))
        {
            ctx.error = ToolResult.error(
                com.ditrix.edt.mcp.server.utils.ProjectContext.noConfigurationMessage(
                    projectName, scope.isExternalObjects())).toJson();
            return ctx;
        }

        ctx.project = project;
        ctx.config = config;
        ctx.scope = scope;
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
