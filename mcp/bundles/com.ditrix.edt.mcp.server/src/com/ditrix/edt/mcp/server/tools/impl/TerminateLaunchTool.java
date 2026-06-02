/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IDisconnect;
import org.eclipse.debug.core.model.IProcess;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;

/**
 * Terminates 1С launches started from this EDT instance. The set of
 * affectable launches is constrained by {@link ILaunchManager#getLaunches()} —
 * any 1С client started externally (Designer, ad-hoc {@code 1cv8c.exe},
 * another EDT instance) is invisible to this tool and therefore safe.
 *
 * <p>Three selection modes (mutually exclusive):
 * <ul>
 *   <li>{@code launchConfigurationName} — single live launch by exact config name.</li>
 *   <li>{@code projectName + applicationId} — single live launch by project + appId.</li>
 *   <li>{@code all=true} (requires {@code confirm=true}) — every live EDT launch,
 *       optionally narrowed by {@code projectName}.</li>
 * </ul>
 *
 * <p>Attach launches ({@code RemoteRuntime} / {@code LocalRuntime}) are
 * disconnected, not killed — the 1С cluster keeps running. Set
 * {@code includeAttach=false} to skip them entirely.
 *
 * <p>By default the tool waits up to {@code timeoutSeconds} for a polite
 * {@link ILaunch#terminate()} to take effect. With {@code force=true}, an
 * unfinished termination escalates to {@link IProcess#terminate()} on the
 * launch's processes — this can lose unsaved 1С state.
 */
public class TerminateLaunchTool implements IMcpTool
{
    public static final String NAME = "terminate_launch"; //$NON-NLS-1$

    /** Fallback polite-wait window in seconds, used when preferences cannot be read. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    /** Hard cap on polite wait, prevents accidental long blocks. */
    private static final int MAX_TIMEOUT_SECONDS = 120;

    /** Extra grace given to force-terminate after the polite window expires. */
    private static final int FORCE_GRACE_MS = 3000;

    /** Result codes (also written into the Markdown response). */
    private static final String R_TERMINATED = "terminated"; //$NON-NLS-1$
    private static final String R_FORCE_TERMINATED = "force_terminated"; //$NON-NLS-1$
    private static final String R_DETACHED = "detached"; //$NON-NLS-1$
    private static final String R_TIMEOUT = "timeout"; //$NON-NLS-1$
    private static final String R_ALREADY_TERMINATED = "already_terminated"; //$NON-NLS-1$
    private static final String R_ERROR = "error"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Terminate one or more 1С launches that were started from THIS EDT instance " //$NON-NLS-1$
            + "(runtime-client or Attach). Only launches visible via the Eclipse launch manager " //$NON-NLS-1$
            + "can be affected — 1С clients started externally (Designer, ad-hoc 1cv8c.exe, " //$NON-NLS-1$
            + "another EDT) are never touched. " //$NON-NLS-1$
            + "Select target via launchConfigurationName, or projectName+applicationId, " //$NON-NLS-1$
            + "or all=true (requires confirm=true). " //$NON-NLS-1$
            + "Attach configurations are disconnected (the 1С server keeps running) — " //$NON-NLS-1$
            + "set includeAttach=false to skip them. " //$NON-NLS-1$
            + "Use list_configurations first to see what is currently running."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact EDT launch configuration name (from list_configurations) to terminate.") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name. Combine with applicationId for a single launch, or with " //$NON-NLS-1$
                    + "all=true to narrow 'terminate all' to one project.") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application ID from get_applications. Requires projectName.") //$NON-NLS-1$
            .booleanProperty("all", //$NON-NLS-1$
                "Terminate every live EDT launch (optionally narrowed by projectName). " //$NON-NLS-1$
                    + "Requires confirm=true. Default: false.") //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "Must be true when all=true. Guard against accidental mass termination.") //$NON-NLS-1$
            .booleanProperty("force", //$NON-NLS-1$
                "If a polite ILaunch.terminate() does not finish within timeoutSeconds, " //$NON-NLS-1$
                    + "escalate to IProcess.terminate() (OS-level kill). " //$NON-NLS-1$
                    + "May lose unsaved 1С state. Default: false. Ignored for Attach.") //$NON-NLS-1$
            .integerProperty("timeoutSeconds", //$NON-NLS-1$
                "How long to wait for a polite termination per launch. " //$NON-NLS-1$
                    + "Default is configured in EDT preferences (MCP Server → Tools → terminate_launch), " //$NON-NLS-1$
                    + "factory default 10. Clamped to [1, 120].") //$NON-NLS-1$
            .booleanProperty("includeAttach", //$NON-NLS-1$
                "Whether to act on Attach configurations (RemoteRuntime/LocalRuntime). " //$NON-NLS-1$
                    + "When true, Attach launches are disconnected (the 1С cluster keeps running). " //$NON-NLS-1$
                    + "Default: true.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String name = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        if (name != null && !name.isEmpty())
        {
            String safe = name.replaceAll("[^a-zA-Z0-9._-]", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            return "terminate-" + safe + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        boolean all = JsonUtils.extractBooleanArgument(params, "all", false); //$NON-NLS-1$
        if (all)
        {
            String project = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
            if (project != null && !project.isEmpty())
            {
                String safe = project.replaceAll("[^a-zA-Z0-9._-]", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
                return "terminate-all-" + safe + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return "terminate-all.md"; //$NON-NLS-1$
        }
        // projectName + applicationId mode — include both in the filename so
        // parallel calls against different IBs don't overwrite each other's
        // result file.
        String project = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String appId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        if (project != null && !project.isEmpty() && appId != null && !appId.isEmpty())
        {
            String safeProject = project.replaceAll("[^a-zA-Z0-9._-]", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            String safeAppId = appId.replaceAll("[^a-zA-Z0-9._-]", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            // Truncate to keep the path short — agents sometimes pass long UUIDs.
            if (safeAppId.length() > 16)
            {
                safeAppId = safeAppId.substring(0, 16);
            }
            return "terminate-" + safeProject + "-" + safeAppId + ".md"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return "terminate-launch.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        boolean all = JsonUtils.extractBooleanArgument(params, "all", false); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$
        boolean force = JsonUtils.extractBooleanArgument(params, "force", false); //$NON-NLS-1$
        boolean includeAttach = JsonUtils.extractBooleanArgument(params, "includeAttach", true); //$NON-NLS-1$
        int configuredDefault = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS); //$NON-NLS-1$
        int timeoutSeconds = JsonUtils.extractIntArgument(params, "timeoutSeconds", configuredDefault); //$NON-NLS-1$
        if (timeoutSeconds < 1)
        {
            timeoutSeconds = 1;
        }
        else if (timeoutSeconds > MAX_TIMEOUT_SECONDS)
        {
            timeoutSeconds = MAX_TIMEOUT_SECONDS;
        }

        boolean hasName = configName != null && !configName.isEmpty();
        boolean hasProject = projectName != null && !projectName.isEmpty();
        boolean hasAppId = applicationId != null && !applicationId.isEmpty();

        String validationError = validateSelection(hasName, hasProject, hasAppId, all, confirm);
        if (validationError != null)
        {
            return validationError;
        }

        try
        {
            ILaunchManager launchManager = LaunchConfigUtils.getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available.").toJson(); //$NON-NLS-1$
            }

            List<ILaunch> targets = selectTargets(launchManager, configName, projectName,
                applicationId, all, hasName, hasProject, hasAppId);

            if (!includeAttach)
            {
                Iterator<ILaunch> it = targets.iterator();
                while (it.hasNext())
                {
                    if (LaunchConfigUtils.isAttachConfig(it.next().getLaunchConfiguration()))
                    {
                        it.remove();
                    }
                }
            }

            if (targets.isEmpty())
            {
                return renderNothingToTerminate(configName, projectName, applicationId, all,
                    includeAttach);
            }

            // For all=true with confirmation, additionally guard the count threshold —
            // not strictly required, but useful evidence in the response.
            List<TerminationResult> results = new ArrayList<>();
            for (ILaunch launch : targets)
            {
                results.add(terminateOne(launch, timeoutSeconds, force));
            }

            return renderResults(results, configName, projectName, applicationId, all,
                includeAttach);
        }
        catch (RuntimeException e)
        {
            Activator.logError("Error in terminate_launch", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    private static String validateSelection(boolean hasName, boolean hasProject, boolean hasAppId,
            boolean all, boolean confirm)
    {
        // Count engaged modes — each set of params that activates a selection mode.
        int modeCount = 0;
        if (hasName)
        {
            modeCount++;
        }
        if (hasProject && hasAppId)
        {
            modeCount++;
        }
        if (all)
        {
            modeCount++;
        }

        if (modeCount > 1)
        {
            return ToolResult.error("Selection modes are mutually exclusive. " //$NON-NLS-1$
                + "Choose ONE of: `launchConfigurationName`, " //$NON-NLS-1$
                + "`projectName + applicationId`, or `all=true`.").toJson(); //$NON-NLS-1$
        }
        // applicationId without all=true makes sense only paired with projectName.
        // When hasName is set, name fully determines the target — extras are ignored.
        if (hasAppId && !hasProject && !all && !hasName)
        {
            return ToolResult.error("`applicationId` requires `projectName`.").toJson(); //$NON-NLS-1$
        }
        // applicationId is meaningless with all=true — that mode is project-scoped at best.
        if (hasAppId && all)
        {
            return ToolResult.error("`applicationId` cannot be combined with `all=true`. " //$NON-NLS-1$
                + "Use `projectName + applicationId` for a single launch, or " //$NON-NLS-1$
                + "`all=true` (optionally with `projectName`) for mass termination.").toJson(); //$NON-NLS-1$
        }
        // projectName alone, without applicationId and without all=true, is ambiguous.
        if (hasProject && !hasAppId && !all && !hasName)
        {
            return ToolResult.error("`projectName` alone is not a selection. " //$NON-NLS-1$
                + "Add `applicationId` for a single launch, or `all=true` " //$NON-NLS-1$
                + "to terminate every live launch of that project.").toJson(); //$NON-NLS-1$
        }
        if (modeCount == 0)
        {
            return ToolResult.error("Provide exactly one of: `launchConfigurationName`, " //$NON-NLS-1$
                + "`projectName + applicationId`, or `all=true`.").toJson(); //$NON-NLS-1$
        }
        if (all && !confirm)
        {
            return ToolResult.error("Confirmation required: pass `confirm=true` to terminate " //$NON-NLS-1$
                + "ALL EDT-launched 1С instances. Use `list_configurations` first to see " //$NON-NLS-1$
                + "what is currently running.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    private static List<ILaunch> selectTargets(ILaunchManager launchManager, String configName,
            String projectName, String applicationId, boolean all, boolean hasName,
            boolean hasProject, boolean hasAppId)
    {
        List<ILaunch> targets = new ArrayList<>();
        if (hasName)
        {
            ILaunch launch = LaunchConfigUtils.findLiveLaunchByName(launchManager, configName);
            if (launch != null)
            {
                targets.add(launch);
            }
            return targets;
        }
        if (hasProject && hasAppId)
        {
            // Scope search to the project first, then match applicationId. Avoids a false
            // not_found if some other project happens to carry the same applicationId
            // (ATTR_APPLICATION_ID is an arbitrary EDT-assigned string with per-project
            // uniqueness only).
            for (ILaunch launch : LaunchConfigUtils.getAllLiveLaunches(launchManager, projectName))
            {
                if (applicationId.equals(LaunchConfigUtils.getApplicationIdFor(launch)))
                {
                    targets.add(launch);
                    break;
                }
            }
            return targets;
        }
        if (all)
        {
            String projectFilter = hasProject ? projectName : null;
            targets.addAll(LaunchConfigUtils.getAllLiveLaunches(launchManager, projectFilter));
        }
        return targets;
    }

    /**
     * Terminates a single launch and reports the outcome. Attach launches are
     * disconnected via {@link IDisconnect}; runtime-client launches go through
     * {@link ILaunch#terminate()} with optional {@link IProcess#terminate()}
     * escalation on timeout.
     */
    private static TerminationResult terminateOne(ILaunch launch, int timeoutSeconds, boolean force)
    {
        TerminationResult result = new TerminationResult(launch);
        if (launch.isTerminated())
        {
            result.code = R_ALREADY_TERMINATED;
            return result;
        }

        long start = System.currentTimeMillis();
        boolean attach = result.attach;
        try
        {
            if (attach)
            {
                disconnectAll(launch);
                // For attach launches, "done" means every debug target is either
                // terminated or disconnected — NOT that ILaunch.isTerminated() flips.
                // Per Eclipse Debug Platform contract, disconnect leaves the debuggee
                // running, so isTerminated may stay false indefinitely.
                if (waitForAttachDone(launch, timeoutSeconds * 1000L))
                {
                    result.code = R_DETACHED;
                }
                else
                {
                    result.code = R_TIMEOUT;
                    result.note = "Attach disconnect did not complete in time. " //$NON-NLS-1$
                        + "The debugger may still be detaching."; //$NON-NLS-1$
                }
            }
            else
            {
                launch.terminate();
                if (LaunchLifecycleUtils.waitForTerminated(launch, timeoutSeconds * 1000L))
                {
                    result.code = R_TERMINATED;
                }
                else if (force)
                {
                    forceTerminateProcesses(launch);
                    if (LaunchLifecycleUtils.waitForTerminated(launch, FORCE_GRACE_MS))
                    {
                        result.code = R_FORCE_TERMINATED;
                    }
                    else
                    {
                        result.code = R_TIMEOUT;
                        result.note = "Force-terminate sent but launch still not marked terminated."; //$NON-NLS-1$
                    }
                }
                else
                {
                    result.code = R_TIMEOUT;
                    result.note = "Still terminating in background. " //$NON-NLS-1$
                        + "Re-run with `force=true` to kill the OS process."; //$NON-NLS-1$
                }
            }
        }
        catch (DebugException e)
        {
            Activator.logError("Error terminating launch " + result.configName, e); //$NON-NLS-1$
            result.code = R_ERROR;
            result.note = e.getMessage();
        }
        result.durationMs = System.currentTimeMillis() - start;
        return result;
    }

    private static void disconnectAll(ILaunch launch) throws DebugException
    {
        boolean any = false;
        for (IDebugTarget target : launch.getDebugTargets())
        {
            if (target instanceof IDisconnect)
            {
                IDisconnect d = (IDisconnect) target;
                if (d.canDisconnect())
                {
                    d.disconnect();
                    any = true;
                }
            }
        }
        // If no debug target supports disconnect, fall back to a regular terminate —
        // for attach configs this is rare, but we should not silently no-op.
        if (!any && launch.canTerminate())
        {
            launch.terminate();
        }
    }

    private static void forceTerminateProcesses(ILaunch launch)
    {
        for (IProcess process : launch.getProcesses())
        {
            if (process == null || process.isTerminated())
            {
                continue;
            }
            try
            {
                process.terminate();
            }
            catch (DebugException e)
            {
                Activator.logError("Error force-terminating process for launch " //$NON-NLS-1$
                    + safeConfigName(launch), e);
            }
        }
    }

    /**
     * Wait criterion for attach launches: success when every debug target is
     * either terminated OR disconnected. Per Eclipse Debug Platform contract,
     * {@link IDisconnect#disconnect()} does not flip {@link ILaunch#isTerminated()};
     * the 1С server keeps running, only the debugger detaches.
     */
    private static boolean waitForAttachDone(ILaunch launch, long maxMillis)
    {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < deadline)
        {
            if (isAttachDetached(launch))
            {
                return true;
            }
            try
            {
                Thread.sleep(LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return isAttachDetached(launch);
            }
        }
        return isAttachDetached(launch);
    }

    private static boolean isAttachDetached(ILaunch launch)
    {
        if (launch.isTerminated())
        {
            return true;
        }
        IDebugTarget[] targets = launch.getDebugTargets();
        if (targets.length == 0)
        {
            // Eclipse Launch is "terminated" only when all processes AND targets
            // are terminated; with zero targets we already know the launch state.
            return false;
        }
        for (IDebugTarget target : targets)
        {
            if (target == null)
            {
                continue;
            }
            if (target.isTerminated())
            {
                continue;
            }
            if (target instanceof IDisconnect && ((IDisconnect) target).isDisconnected())
            {
                continue;
            }
            return false;
        }
        return true;
    }

    private static String safeConfigName(ILaunch launch)
    {
        ILaunchConfiguration cfg = launch.getLaunchConfiguration();
        return cfg != null ? cfg.getName() : "<unknown>"; //$NON-NLS-1$
    }

    // === Rendering ===

    private static String renderNothingToTerminate(String configName, String projectName,
            String applicationId, boolean all, boolean includeAttach)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# No Running Launches\n\n"); //$NON-NLS-1$
        sb.append("**Result:** not_found\n"); //$NON-NLS-1$
        sb.append("**Scope:** ").append(formatScope(configName, projectName, applicationId, all)) //$NON-NLS-1$
            .append('\n');
        if (!includeAttach)
        {
            sb.append("**includeAttach:** false\n"); //$NON-NLS-1$
        }
        sb.append('\n');
        sb.append("No live EDT launch matched the request. Use `list_configurations` to see " //$NON-NLS-1$
            + "what is currently running (look for entries with `running: true`)."); //$NON-NLS-1$
        return sb.toString();
    }

    private static String renderResults(List<TerminationResult> results, String configName,
            String projectName, String applicationId, boolean all, boolean includeAttach)
    {
        int terminated = 0;
        int detached = 0;
        int timedOut = 0;
        int errors = 0;
        int alreadyTerminated = 0;
        for (TerminationResult r : results)
        {
            switch (r.code)
            {
                case R_TERMINATED:
                case R_FORCE_TERMINATED:
                    terminated++;
                    break;
                case R_DETACHED:
                    detached++;
                    break;
                case R_TIMEOUT:
                    timedOut++;
                    break;
                case R_ERROR:
                    errors++;
                    break;
                case R_ALREADY_TERMINATED:
                    alreadyTerminated++;
                    break;
                default:
                    break;
            }
        }

        boolean hasIssues = timedOut > 0 || errors > 0;
        StringBuilder sb = new StringBuilder();
        if (results.size() == 1 && !hasIssues)
        {
            renderSingle(sb, results.get(0));
        }
        else
        {
            sb.append("# Launches Terminated"); //$NON-NLS-1$
            if (hasIssues)
            {
                sb.append(" (with issues)"); //$NON-NLS-1$
            }
            sb.append("\n\n"); //$NON-NLS-1$
            sb.append("**Total:** ").append(results.size()) //$NON-NLS-1$
                .append(" (terminated: ").append(terminated) //$NON-NLS-1$
                .append(", detached: ").append(detached) //$NON-NLS-1$
                .append(", timeout: ").append(timedOut) //$NON-NLS-1$
                .append(", already_terminated: ").append(alreadyTerminated) //$NON-NLS-1$
                .append(", errors: ").append(errors).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("**Scope:** ").append(formatScope(configName, projectName, applicationId, all)) //$NON-NLS-1$
                .append('\n');
            if (!includeAttach)
            {
                sb.append("**includeAttach:** false\n"); //$NON-NLS-1$
            }

            appendSection(sb, "## Terminated", results, //$NON-NLS-1$
                r -> R_TERMINATED.equals(r.code) || R_FORCE_TERMINATED.equals(r.code), false);
            appendSection(sb, "## Detached (Attach configurations)", results, //$NON-NLS-1$
                r -> R_DETACHED.equals(r.code), true);
            appendSection(sb, "## Already Terminated", results, //$NON-NLS-1$
                r -> R_ALREADY_TERMINATED.equals(r.code), false);
            appendIssueSection(sb, "## Timed Out", results, R_TIMEOUT); //$NON-NLS-1$
            appendIssueSection(sb, "## Errors", results, R_ERROR); //$NON-NLS-1$
        }

        if (detached > 0)
        {
            sb.append("\n> Attach configurations only disconnect the debugger — " //$NON-NLS-1$
                + "the 1С server (ragent/rphost) keeps running. " //$NON-NLS-1$
                + "Pass `includeAttach=false` to skip Attach launches entirely.\n"); //$NON-NLS-1$
        }
        sb.append("\n> Only launches started from this EDT instance are affected. " //$NON-NLS-1$
            + "Externally launched 1С clients are not touched by this tool.\n"); //$NON-NLS-1$
        return sb.toString();
    }

    private static void renderSingle(StringBuilder sb, TerminationResult r)
    {
        switch (r.code)
        {
            case R_TERMINATED:
            case R_FORCE_TERMINATED:
                sb.append("# Launch Terminated\n\n"); //$NON-NLS-1$
                break;
            case R_DETACHED:
                sb.append("# Launch Detached\n\n"); //$NON-NLS-1$
                break;
            case R_ALREADY_TERMINATED:
                sb.append("# Launch Already Terminated\n\n"); //$NON-NLS-1$
                break;
            case R_TIMEOUT:
                sb.append("# Launch Termination Timed Out\n\n"); //$NON-NLS-1$
                break;
            case R_ERROR:
                sb.append("# Launch Termination Failed\n\n"); //$NON-NLS-1$
                break;
            default:
                sb.append("# Launch Termination Result\n\n"); //$NON-NLS-1$
                break;
        }
        sb.append("**Result:** ").append(r.code).append('\n'); //$NON-NLS-1$
        sb.append("**Launch configuration:** ").append(r.configName).append('\n'); //$NON-NLS-1$
        if (r.projectName != null && !r.projectName.isEmpty())
        {
            sb.append("**Project:** ").append(r.projectName).append('\n'); //$NON-NLS-1$
        }
        if (r.applicationId != null && !r.applicationId.isEmpty())
        {
            sb.append("**Application ID:** ").append(r.applicationId).append('\n'); //$NON-NLS-1$
        }
        if (r.mode != null && !r.mode.isEmpty())
        {
            sb.append("**Mode:** ").append(r.mode).append('\n'); //$NON-NLS-1$
        }
        sb.append("**Attach:** ").append(r.attach ? "Yes" : "No").append('\n'); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("**Duration:** ").append(r.durationMs).append(" ms\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (r.note != null && !r.note.isEmpty())
        {
            sb.append("**Note:** ").append(r.note).append('\n'); //$NON-NLS-1$
        }
    }

    private static void appendSection(StringBuilder sb, String heading,
            List<TerminationResult> results, java.util.function.Predicate<TerminationResult> filter,
            boolean attachSection)
    {
        List<TerminationResult> matching = new ArrayList<>();
        for (TerminationResult r : results)
        {
            if (filter.test(r))
            {
                matching.add(r);
            }
        }
        if (matching.isEmpty())
        {
            return;
        }
        sb.append('\n').append(heading).append("\n\n"); //$NON-NLS-1$
        if (attachSection)
        {
            sb.append("| Configuration | Project | Debug Server URL | Infobase Alias | Duration |\n"); //$NON-NLS-1$
            sb.append("|---|---|---|---|---|\n"); //$NON-NLS-1$
            for (TerminationResult r : matching)
            {
                sb.append("| ").append(escape(r.configName)) //$NON-NLS-1$
                    .append(" | ").append(escape(r.projectName)) //$NON-NLS-1$
                    .append(" | ").append(escape(r.debugServerUrl)) //$NON-NLS-1$
                    .append(" | ").append(escape(r.infobaseAlias)) //$NON-NLS-1$
                    .append(" | ").append(r.durationMs).append(" ms |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else
        {
            sb.append("| Configuration | Project | Application ID | Mode | Result | Duration |\n"); //$NON-NLS-1$
            sb.append("|---|---|---|---|---|---|\n"); //$NON-NLS-1$
            for (TerminationResult r : matching)
            {
                sb.append("| ").append(escape(r.configName)) //$NON-NLS-1$
                    .append(" | ").append(escape(r.projectName)) //$NON-NLS-1$
                    .append(" | ").append(escape(r.applicationId)) //$NON-NLS-1$
                    .append(" | ").append(escape(r.mode)) //$NON-NLS-1$
                    .append(" | ").append(r.code) //$NON-NLS-1$
                    .append(" | ").append(r.durationMs).append(" ms |\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    private static void appendIssueSection(StringBuilder sb, String heading,
            List<TerminationResult> results, String code)
    {
        List<TerminationResult> matching = new ArrayList<>();
        for (TerminationResult r : results)
        {
            if (code.equals(r.code))
            {
                matching.add(r);
            }
        }
        if (matching.isEmpty())
        {
            return;
        }
        sb.append('\n').append(heading).append("\n\n"); //$NON-NLS-1$
        sb.append("| Configuration | Project | Application ID | Attach | Note |\n"); //$NON-NLS-1$
        sb.append("|---|---|---|---|---|\n"); //$NON-NLS-1$
        for (TerminationResult r : matching)
        {
            sb.append("| ").append(escape(r.configName)) //$NON-NLS-1$
                .append(" | ").append(escape(r.projectName)) //$NON-NLS-1$
                .append(" | ").append(escape(r.applicationId)) //$NON-NLS-1$
                .append(" | ").append(r.attach ? "Yes" : "No") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(" | ").append(escape(r.note)).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static String formatScope(String configName, String projectName, String applicationId,
            boolean all)
    {
        if (configName != null && !configName.isEmpty())
        {
            return "launchConfigurationName=" + configName; //$NON-NLS-1$
        }
        if (all)
        {
            return projectName != null && !projectName.isEmpty()
                ? "all live launches of project=" + projectName //$NON-NLS-1$
                : "all live EDT launches"; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        if (projectName != null && !projectName.isEmpty())
        {
            sb.append("project=").append(projectName); //$NON-NLS-1$
        }
        if (applicationId != null && !applicationId.isEmpty())
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append("applicationId=").append(applicationId); //$NON-NLS-1$
        }
        return sb.length() > 0 ? sb.toString() : "<empty>"; //$NON-NLS-1$
    }

    private static String escape(String value)
    {
        if (value == null || value.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        // Markdown table cell: escape pipes and collapse newlines.
        return value.replace("|", "\\|").replace("\n", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Captured snapshot of a launch's identity at termination time, plus the
     * outcome of the termination attempt. Built eagerly so the rendering code
     * can read the values after the underlying {@link ILaunch} is gone.
     */
    private static final class TerminationResult
    {
        final String configName;
        final String projectName;
        final String applicationId;
        final boolean attach;
        final String mode;
        final String debugServerUrl;
        final String infobaseAlias;
        String code;
        String note;
        long durationMs;

        TerminationResult(ILaunch launch)
        {
            ILaunchConfiguration cfg = launch.getLaunchConfiguration();
            this.configName = cfg != null ? cfg.getName() : "<unknown>"; //$NON-NLS-1$
            this.projectName = cfg != null
                ? LaunchConfigUtils.readAttribute(cfg, LaunchConfigUtils.ATTR_PROJECT_NAME, "") //$NON-NLS-1$
                : ""; //$NON-NLS-1$
            this.applicationId = LaunchConfigUtils.getApplicationIdFor(launch);
            this.attach = LaunchConfigUtils.isAttachConfig(cfg);
            this.mode = launch.getLaunchMode();
            this.debugServerUrl = cfg != null
                ? LaunchConfigUtils.readAttribute(cfg, LaunchConfigUtils.ATTR_DEBUG_SERVER_URL, "") //$NON-NLS-1$
                : ""; //$NON-NLS-1$
            this.infobaseAlias = cfg != null
                ? LaunchConfigUtils.readAttribute(cfg, LaunchConfigUtils.ATTR_DEBUG_INFOBASE_ALIAS, "") //$NON-NLS-1$
                : ""; //$NON-NLS-1$
        }
    }
}
