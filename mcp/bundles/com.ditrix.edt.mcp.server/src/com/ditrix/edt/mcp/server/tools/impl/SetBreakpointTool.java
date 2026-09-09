/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.debug.core.model.IBreakpoint;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BreakpointUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Sets a 1C BSL line breakpoint via the Eclipse breakpoint framework.
 *
 * <p>Accepts either an EDT module-relative path
 * ({@code "CommonModules/MyModule/Module.bsl"}) or an absolute filesystem path
 * to a {@code .bsl} file. The tool delegates to {@link BreakpointUtils}, which
 * tries the EDT BSL breakpoint class first and falls back to a marker-based
 * implementation if the class is not available on the runtime classpath.
 */
public class SetBreakpointTool implements IMcpTool
{
    public static final String NAME = "set_breakpoint"; //$NON-NLS-1$

    /** Input/output key: legacy alias of the module identifier. */
    private static final String KEY_MODULE = "module"; //$NON-NLS-1$

    /** Input/output key: 1-based line number. */
    private static final String KEY_LINE_NUMBER = "lineNumber"; //$NON-NLS-1$

    private static final String KEY_CONDITION = "condition"; //$NON-NLS-1$
    private static final String KEY_HIT_COUNT = "hitCount"; //$NON-NLS-1$
    private static final String KEY_HIT_CONDITION = "hitCondition"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Pause BSL execution at a selected source line during debugging, optionally only " //$NON-NLS-1$
            + "while a condition holds or after a number of hits. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('set_breakpoint')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required when modulePath is module-relative)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.MODULE_PATH,
                "Module identifier — EDT module path (CommonModules/Foo/Module.bsl) or absolute file path (required)") //$NON-NLS-1$
            .stringProperty(KEY_MODULE, "Legacy alias for modulePath (deprecated)") //$NON-NLS-1$
            .integerProperty(KEY_LINE_NUMBER, "1-based line number (required)", true) //$NON-NLS-1$
            .stringProperty(KEY_CONDITION,
                "BSL boolean expression evaluated at the line; omit or pass an empty string to clear it") //$NON-NLS-1$
            .integerProperty(KEY_HIT_COUNT,
                "Positive hit count; omit or pass 0 to clear it. EDT transmits hit counts only to " //$NON-NLS-1$
                    + "1C:Enterprise 8.3.24 or newer") //$NON-NLS-1$
            .enumProperty(KEY_HIT_CONDITION,
                "Hit-count comparison; requires a positive hitCount and defaults to EQUALS", //$NON-NLS-1$
                BreakpointUtils.getValidHitConditions())
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("breakpointId", "Eclipse marker id of the created breakpoint (-1 if none)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.MODULE_PATH, "Module identifier as supplied (EDT path or absolute path)") //$NON-NLS-1$
            .stringProperty(KEY_MODULE, "Legacy alias echo of the module identifier") //$NON-NLS-1$
            .stringProperty("resolvedFile", "Workspace-relative path of the resolved .bsl file") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty(KEY_LINE_NUMBER, "1-based line number where the breakpoint was set") //$NON-NLS-1$
            .stringProperty("action", "Whether the breakpoint was created or updated") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_CONDITION, "Configured BSL condition; omitted when unset") //$NON-NLS-1$
            .integerProperty(KEY_HIT_COUNT, "Configured positive hit count; omitted when unset") //$NON-NLS-1$
            .stringProperty(KEY_HIT_CONDITION, "Configured hit-count comparison; omitted when hitCount is unset") //$NON-NLS-1$
            .booleanProperty("conditionApplied", "Whether a requested condition was applied") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("hitCountApplied", "Whether a requested hit count was applied") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("configurationFallback", //$NON-NLS-1$
                "Marker attributes written because native EDT setter methods were absent") //$NON-NLS-1$
            .integerArrayProperty("breakpointIds", //$NON-NLS-1$
                "Marker ids of every breakpoint reconciled at this line; more than one means breakpointId alone is not all of them") //$NON-NLS-1$
            .integerProperty("reconciledBreakpoints", //$NON-NLS-1$
                "Present only when this line carried more than one registered BSL breakpoint; the " //$NON-NLS-1$
                    + "same settings were requested for every one of them, and a marker-only member " //$NON-NLS-1$
                    + "takes none of them") //$NON-NLS-1$
            .booleanProperty("degraded", //$NON-NLS-1$
                "True when ANY breakpoint at this line is marker-only - one created as the fallback, " //$NON-NLS-1$
                    + "or one already registered there, which an update does not turn native") //$NON-NLS-1$
            .stringProperty("warning", //$NON-NLS-1$
                "Present when degraded; it says whether the marker-only breakpoint was created here " //$NON-NLS-1$
                    + "or already registered, and how many of the reconciled ones are native") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        // modulePath is the canonical parameter; "module" is a legacy alias kept for
        // one release. Both resolve through the same BreakpointUtils resolver.
        String modulePath = JsonUtils.extractStringArgument(params, McpKeys.MODULE_PATH);
        String module = (modulePath != null && !modulePath.isEmpty())
            ? modulePath
            : JsonUtils.extractStringArgument(params, KEY_MODULE);
        int lineNumber = JsonUtils.extractIntArgument(params, KEY_LINE_NUMBER, -1);
        String condition = JsonUtils.extractStringArgument(params, KEY_CONDITION);
        int hitCount = JsonUtils.extractIntArgument(params, KEY_HIT_COUNT, 0);
        String hitCondition = JsonUtils.extractStringArgument(params, KEY_HIT_CONDITION);
        boolean conditionProvided = params.containsKey(KEY_CONDITION);
        boolean hitCountProvided = params.containsKey(KEY_HIT_COUNT);
        String effectiveCondition = condition == null || condition.isBlank() ? "" : condition; //$NON-NLS-1$

        // A value the parser could not read comes back as the DEFAULT, which here would mean
        // "clear the hit count" - so 'abc' or 1.5 would silently reconfigure an existing
        // breakpoint and be reported as success. MCP dispatch does not validate arguments against
        // the advertised schema, so the refusal has to happen here. The sentinel is outside the
        // range this parameter accepts, so it cannot collide with a real value.
        // A BLANK value is refused too, unlike the sibling 'condition': that one is a STRING whose
        // empty value legitimately means "no condition", while hitCount is an INTEGER, for which a
        // blank is not a value at all - and letting it through would clear a configured hit count
        // and answer success, the very behaviour this guard exists to stop. A JSON null stays
        // exempt: that is a field the caller left unset, not a value it got wrong.
        String rawHitCount = JsonUtils.extractStringArgument(params, KEY_HIT_COUNT);
        if (hitCountProvided && rawHitCount != null
            && JsonUtils.extractIntArgument(params, KEY_HIT_COUNT, Integer.MIN_VALUE)
                == Integer.MIN_VALUE)
        {
            return ToolResult.error("Invalid hitCount '" + rawHitCount //$NON-NLS-1$
                + "': it must be a whole number. Use a positive integer, or 0/omit hitCount " //$NON-NLS-1$
                + "to clear it.").toJson(); //$NON-NLS-1$
        }
        if (hitCount < 0)
        {
            return ToolResult.error("Invalid hitCount " + hitCount //$NON-NLS-1$
                + ": use a positive integer, or 0/omit hitCount to clear it.").toJson(); //$NON-NLS-1$
        }
        if (hitCondition != null && hitCount <= 0)
        {
            return ToolResult.error("hitCondition '" + hitCondition //$NON-NLS-1$
                + "' requires a positive hitCount; set hitCount above 0 or omit hitCondition.").toJson(); //$NON-NLS-1$
        }
        if (hitCondition != null && !BreakpointUtils.isValidHitCondition(hitCondition))
        {
            return ToolResult.error("Unknown hitCondition '" + hitCondition //$NON-NLS-1$
                + "'. Valid values are: " + String.join(", ", BreakpointUtils.getValidHitConditions()) //$NON-NLS-1$ //$NON-NLS-2$
                + ".").toJson(); //$NON-NLS-1$
        }
        String effectiveHitCondition = hitCondition == null ? "EQUALS" : hitCondition; //$NON-NLS-1$

        ResolvedTarget target = validateAndResolve(projectName, module, lineNumber);
        if (target.error != null)
        {
            return target.error;
        }

        // Filled as the loop below walks the duplicates, and read by the failure handler: an
        // UPDATE that dies halfway leaves the members it already reached carrying the new
        // settings, and nothing withdraws them. Their ids are the only handle the caller has.
        List<Long> alreadyChanged = new ArrayList<>();
        // Every reconciled marker id, read once while the loop holds them and used to render
        // the answer - so the response never depends on a marker read after the mutation.
        List<Long> reconciledIds = new ArrayList<>();
        try
        {
            // ALL of them, not the first: an upgraded workspace can hold duplicates at one line,
            // because an earlier build created a new marker on every call. Configuring one and
            // leaving its twin unconditional would defeat the very condition just requested.
            List<IBreakpoint> existing = BreakpointUtils.findLineBreakpoints(target.file, lineNumber);
            boolean created = existing.isEmpty();
            String action = created ? "created" : "updated"; //$NON-NLS-1$ //$NON-NLS-2$
            if (created)
            {
                existing = Collections.singletonList(
                    BreakpointUtils.createLineBreakpoint(target.file, lineNumber));
            }
            IBreakpoint bp = existing.get(0);
            // Captured while the loop walks, and rendered from afterwards: reading the markers
            // again to build the response happens AFTER every setter has succeeded, so a marker
            // that goes stale in between would report the whole call as failed for a change
            // that took effect - and invite a retry that mutates it a second time.
            long representativeId = -1L;
            try
            {
                // AGGREGATED across the duplicates, not "whichever came last": with a native
                // breakpoint beside a marker-only one, keeping the final result would report
                // the condition as applied - or as degraded - depending on registration order.
                // Applied only when EVERY duplicate applied it; the fallbacks are unioned.
                boolean appliedEverywhere = true;
                List<String> fallbacks = new ArrayList<>();
                for (IBreakpoint each : existing)
                {
                    // Recorded BEFORE the mutation, not after: a member that throws halfway
                    // through may already carry the new condition, and a list of "fully done"
                    // ids would hide exactly the one the caller must look at. Only on the
                    // update path - a breakpoint this call created is withdrawn on failure.
                    IMarker marker = each.getMarker();
                    if (marker != null)
                    {
                        Long id = Long.valueOf(marker.getId());
                        if (each == bp)
                        {
                            representativeId = id.longValue();
                        }
                        reconciledIds.add(id);
                        if (!created)
                        {
                            alreadyChanged.add(id);
                        }
                    }
                    BreakpointUtils.LineBreakpointConfiguration one =
                        BreakpointUtils.configureLineBreakpoint(each, effectiveCondition,
                            hitCount, effectiveHitCondition);
                    appliedEverywhere = appliedEverywhere && one.isApplied();
                    for (String fallback : one.getMarkerFallbacks())
                    {
                        if (!fallbacks.contains(fallback))
                        {
                            fallbacks.add(fallback);
                        }
                    }
                    // A breakpoint reused at this line may have been switched OFF in EDT. Asking for
                    // a breakpoint means "stop here", so leaving it disabled would report success for
                    // one that never fires - and this tool has no 'enabled' parameter to fix that.
                    each.setEnabled(true);
                }
                BreakpointUtils.LineBreakpointConfiguration configuration = appliedEverywhere
                    ? BreakpointUtils.LineBreakpointConfiguration.appliedWith(fallbacks)
                    : BreakpointUtils.LineBreakpointConfiguration.notApplied();
                return buildSuccessResult(existing, target.file, module, lineNumber, action,
                    effectiveCondition, hitCount, effectiveHitCondition, conditionProvided,
                    hitCountProvided, configuration, representativeId, reconciledIds);
            }
            catch (Exception configurationFailure)
            {
                // A breakpoint THIS call created and could not configure must not survive the
                // failure: it is already registered, so the next attempt would find it and report
                // 'updated' on the half-configured leftover of a call that said it failed.
                if (created)
                {
                    removeQuietly(bp, configurationFailure);
                }
                throw configurationFailure;
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to set breakpoint", e); //$NON-NLS-1$
            return ToolResult.error(failureMessage(e, alreadyChanged)).toJson();
        }
    }

    /**
     * Builds the failure message, naming the breakpoints this call had already begun changing.
     * <p>
     * A creation that fails is withdrawn, so there is nothing to name. An UPDATE cannot be
     * undone that way: every duplicate the loop reached before the failure keeps the new
     * condition, hit count and enabled state. Saying only "failed" would leave those invisible,
     * and coordinate-based inspection answers with one breakpoint of several.
     * </p>
     *
     * @param cause the underlying failure
     * @param alreadyChanged marker ids this call had begun changing; empty when nothing survived
     * @return the error message
     */
    static String failureMessage(Throwable cause, List<Long> alreadyChanged)
    {
        // The class name when there is no message: a raw NullPointerException would otherwise be
        // serialized as the literal "null", and ToolResult.error cannot substitute anything of
        // its own because the composed string is not null - the caller would be told nothing.
        StringBuilder message = new StringBuilder("Failed to set breakpoint: ") //$NON-NLS-1$
            .append(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage())
            .append('.');
        if (!alreadyChanged.isEmpty())
        {
            // "Begun changing", not "changed": the failure can land before the first setter wrote
            // anything, or between the condition and the hit count. Naming a state this call did
            // not establish would be the same dishonesty as saying nothing.
            message.append(" This call had already begun changing breakpoint id(s) "); //$NON-NLS-1$
            for (int i = 0; i < alreadyChanged.size(); i++)
            {
                message.append(i == 0 ? "" : ", ").append(alreadyChanged.get(i)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            message.append(" - each may carry part of the new settings and none of them was " //$NON-NLS-1$
                + "rolled back, so inspect them."); //$NON-NLS-1$
        }
        return message.append(" Verify the breakpoint in EDT's Breakpoints view, then retry.") //$NON-NLS-1$
            .toString();
    }

    /**
     * Builds the degraded warning from what actually happened.
     * <p>
     * Worded by case rather than from the fallback that usually causes it: on the {@code updated}
     * path this call looked up no class and created nothing, and a line can carry a marker-only
     * breakpoint beside a fully native one. Announcing a failed creation there would be a false
     * diagnosis of a set that partly works.
     * </p>
     *
     * @param created whether this call created the breakpoint
     * @param markerOnly how many of the reconciled breakpoints are marker-only
     * @param total how many breakpoints were reconciled at this line
     * @param conditionProvided whether the caller asked for a condition
     * @param hitCountProvided whether the caller asked for a hit count
     * @return the warning text
     */
    static String degradedWarning(boolean created, int markerOnly, int total,
        boolean conditionProvided, boolean hitCountProvided)
    {
        boolean everyOne = markerOnly >= total;
        StringBuilder warning = new StringBuilder();
        if (created)
        {
            warning.append("EDT BSL breakpoint class not available \u2014 created a marker-only ") //$NON-NLS-1$
                .append("breakpoint that may NOT trigger debug suspend events. "); //$NON-NLS-1$
        }
        else if (everyOne)
        {
            warning.append("Every breakpoint already registered at this line is marker-only (no ") //$NON-NLS-1$
                .append("EDT BSL breakpoint class behind it) and may NOT trigger debug suspend ") //$NON-NLS-1$
                .append("events. "); //$NON-NLS-1$
        }
        else
        {
            warning.append(markerOnly).append(" of ").append(total) //$NON-NLS-1$
                .append(" breakpoints already registered at this line are marker-only and may ") //$NON-NLS-1$
                .append("NOT trigger debug suspend events; the rest are native. "); //$NON-NLS-1$
        }
        if (conditionProvided || hitCountProvided)
        {
            warning.append(conditionProvided && hitCountProvided
                ? "The condition and hit count were NOT applied" //$NON-NLS-1$
                : conditionProvided
                    ? "The condition was NOT applied" //$NON-NLS-1$
                    : "The hit count was NOT applied"); //$NON-NLS-1$
            // The aggregate flag is false for the mixed set too, so the prose has to say WHERE:
            // the native twin did take the condition, and "not applied" alone reads as nowhere.
            warning.append(everyOne ? ". " : " to the marker-only breakpoints. "); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return warning.append("Verify in EDT that the breakpoint appears in the Breakpoints view.") //$NON-NLS-1$
            .toString();
    }
    /**
     * Removes a breakpoint this call created after its configuration failed, without letting the
     * cleanup replace the failure that caused it.
     *
     * @param breakpoint the breakpoint to withdraw
     * @param failure the configuration failure being reported; cleanup trouble is attached to it
     */
    private static void removeQuietly(IBreakpoint breakpoint, Exception failure)
    {
        try
        {
            BreakpointUtils.removeBreakpointById(breakpoint.getMarker().getId());
        }
        catch (Exception cleanupFailure)
        {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Validates the input arguments and resolves the target {@code .bsl} file —
     * the side-effect-free pre-flight extracted from {@link #execute}. Returns a
     * holder carrying either the resolved {@link IFile} or the exact error JSON the
     * inline guards produced (same value, same case); the caller re-checks
     * {@code error} and returns it unchanged, leaving the mutating
     * {@code createLineBreakpoint} call inline.
     *
     * @param projectName the EDT project (required for module-relative paths)
     * @param module the module identifier (already collapsed from modulePath/alias)
     * @param lineNumber the requested 1-based line
     * @return a {@link ResolvedTarget} with a non-null {@code file} on success,
     *         or a non-null {@code error} otherwise
     */
    private static ResolvedTarget validateAndResolve(String projectName, String module, int lineNumber)
    {
        if (module == null || module.isEmpty())
        {
            return ResolvedTarget.failed(ToolResult.error("modulePath is required").toJson()); //$NON-NLS-1$
        }
        if (lineNumber < 1)
        {
            return ResolvedTarget.failed(ToolResult.error("lineNumber must be >= 1").toJson()); //$NON-NLS-1$
        }

        boolean modulePathStyle = !BreakpointUtils.looksLikeAbsolutePath(module);
        if (modulePathStyle && (projectName == null || projectName.isEmpty()))
        {
            return ResolvedTarget.failed(ToolResult.error(
                "projectName is required when modulePath is given as an EDT module path").toJson()); //$NON-NLS-1$
        }

        if (modulePathStyle)
        {
            // Refuse only the transient BUILDING state; a missing/closed project
            // falls through to the value-naming 'Project not found' below.
            String building = ProjectStateChecker.buildingErrorOrNull(projectName);
            if (building != null)
            {
                return ResolvedTarget.failed(ToolResult.error(building).toJson());
            }
        }

        IFile file = BreakpointUtils.resolveModuleFile(projectName, module);
        if (file == null || !file.exists())
        {
            return ResolvedTarget.failed(ToolResult.error("Module file not found: " + module //$NON-NLS-1$
                + (modulePathStyle ? " in project " + projectName : "")).toJson()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ResolvedTarget.of(file);
    }

    /**
     * Holder threading the validation/resolution early-return out of
     * {@link #validateAndResolve}: exactly one of {@code file} / {@code error} is
     * non-null. {@code error} carries the same error JSON (same case) the inline
     * guards returned.
     */
    private static final class ResolvedTarget
    {
        final IFile file;
        final String error;

        private ResolvedTarget(IFile file, String error)
        {
            this.file = file;
            this.error = error;
        }

        static ResolvedTarget of(IFile file)
        {
            return new ResolvedTarget(file, null);
        }

        static ResolvedTarget failed(String error)
        {
            return new ResolvedTarget(null, error);
        }
    }

    /**
     * Builds the success JSON for a created breakpoint, logging the outcome and
     * flagging the degraded (marker-only) case. Pure with respect to caller
     * state — reads only the supplied breakpoint/file/coordinates.
     *
     * @param bp the created breakpoint
     * @param file the resolved module file
     * @param module the module path/alias echoed back to the caller
     * @param lineNumber the breakpoint line
     * @return the success result JSON
     */
    private static String buildSuccessResult(List<IBreakpoint> reconciled, IFile file, String module,
        int lineNumber, String action, String condition, int hitCount, String hitCondition,
        boolean conditionProvided, boolean hitCountProvided,
        BreakpointUtils.LineBreakpointConfiguration configuration, long markerId,
        List<Long> reconciledIds)
    {
        // ANY of the reconciled breakpoints being marker-only degrades the answer: taking the
        // flag from the first one made the warning depend on registration order, so a native
        // breakpoint listed before a marker-only twin hid the degradation entirely.
        int markerOnly = 0;
        for (IBreakpoint each : reconciled)
        {
            markerOnly += each instanceof BreakpointUtils.MarkerOnlyBreakpoint ? 1 : 0;
        }
        boolean degraded = markerOnly > 0;
        Activator.logInfo("Breakpoint set: " + file.getFullPath() + ":" + lineNumber //$NON-NLS-1$ //$NON-NLS-2$
            + (degraded ? " (degraded — marker-only)" : "")); //$NON-NLS-1$ //$NON-NLS-2$
        ToolResult res = ToolResult.success()
            .put("breakpointId", markerId) //$NON-NLS-1$
            .put(McpKeys.MODULE_PATH, module)
            .put(KEY_MODULE, module)
            .put("resolvedFile", file.getFullPath().toString()) //$NON-NLS-1$
            .put(KEY_LINE_NUMBER, lineNumber)
            .put("action", action); //$NON-NLS-1$
        if (reconciled.size() > 1)
        {
            // Said out loud, because the caller could not have known: this line carried more than
            // one registered BSL breakpoint (an upgraded workspace from a build that created a new
            // one per call), and all of them were configured the same way.
            res.put("reconciledBreakpoints", reconciled.size()); //$NON-NLS-1$
            // Every reconciled marker, not just the first: an upgraded workspace can hold
            // legacy duplicates at one line, and removing the returned breakpointId alone
            // would leave the others live - coordinate-based removal takes one too. Taken from
            // what the mutation loop captured, so nothing here reads a marker again.
            res.put("breakpointIds", reconciledIds); //$NON-NLS-1$
        }
        if (configuration.isApplied())
        {
            if (conditionProvided)
            {
                if (!condition.isEmpty())
                {
                    res.put(KEY_CONDITION, condition);
                }
                res.put("conditionApplied", true); //$NON-NLS-1$
            }
            if (hitCountProvided)
            {
                if (hitCount > 0)
                {
                    res.put(KEY_HIT_COUNT, hitCount)
                        .put(KEY_HIT_CONDITION, hitCondition);
                }
                res.put("hitCountApplied", true); //$NON-NLS-1$
            }
            if (!configuration.getMarkerFallbacks().isEmpty())
            {
                res.put("configurationFallback", //$NON-NLS-1$
                    "Native EDT breakpoint setter methods were absent; wrote marker attributes for: " //$NON-NLS-1$
                        + String.join(", ", configuration.getMarkerFallbacks())); //$NON-NLS-1$
            }
        }
        if (degraded)
        {
            res.put("degraded", true); //$NON-NLS-1$
            if (conditionProvided)
            {
                res.put("conditionApplied", false); //$NON-NLS-1$
            }
            if (hitCountProvided)
            {
                res.put("hitCountApplied", false); //$NON-NLS-1$
            }
            res.put("warning", degradedWarning("created".equals(action), markerOnly, //$NON-NLS-1$ //$NON-NLS-2$
                reconciled.size(), conditionProvided, hitCountProvided));
        }
        return res.toJson();
    }
}
