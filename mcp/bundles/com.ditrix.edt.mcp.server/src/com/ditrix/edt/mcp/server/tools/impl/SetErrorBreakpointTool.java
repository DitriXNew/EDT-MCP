/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.model.IBreakpoint;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BreakpointUtils;

/** Creates, updates, enables, or disables EDT's workspace-wide BSL exception breakpoint. */
public class SetErrorBreakpointTool implements IMcpTool
{
    public static final String NAME = "set_error_breakpoint"; //$NON-NLS-1$

    private static final String KEY_ENABLED = "enabled"; //$NON-NLS-1$
    private static final String KEY_EXCEPTION_MESSAGE = "exceptionMessage"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create, update, enable, or disable the workspace-wide BSL break-on-error breakpoint. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('set_error_breakpoint')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty(KEY_ENABLED,
                "True creates/enables the workspace-wide breakpoint; false disables it while " //$NON-NLS-1$
                    + "preserving its filter. To delete it, call remove_breakpoint with its breakpointId", true) //$NON-NLS-1$
            .stringProperty(KEY_EXCEPTION_MESSAGE,
                "Omit to keep an existing filter (or create catch-all when none exists); pass an " //$NON-NLS-1$
                    + "empty string to catch all exceptions; non-empty text is forwarded verbatim " //$NON-NLS-1$
                    + "to the platform's break-on-error filter as a message template, so pass a " //$NON-NLS-1$
                    + "distinctive message fragment") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "created, updated, disabled, or notFound") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("breakpointId", "Eclipse marker id when the breakpoint is enabled") //$NON-NLS-1$ //$NON-NLS-2$
            .integerArrayProperty("breakpointIds", "Marker ids of EVERY exception breakpoint this call touched, on both the enable and the disable path; more than one means breakpointId alone is not the whole setting, and each id has to be removed") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("configuredCount", "How many breakpoints were touched; larger than breakpointIds when one of them has no marker to name") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("warning", "Present when some affected breakpoint has no marker and therefore cannot be removed through remove_breakpoint") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty(KEY_ENABLED, "Whether break-on-error is enabled") //$NON-NLS-1$
            .booleanProperty("workspaceWide", "Always true; EDT does not scope this breakpoint by project") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("catchAllExceptions", "Whether all BSL exceptions are matched") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_EXCEPTION_MESSAGE, "Configured exception-message filter") //$NON-NLS-1$
            .integerProperty("disabledCount", "Number of workspace-wide exception breakpoints disabled") //$NON-NLS-1$ //$NON-NLS-2$
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
        String required = JsonUtils.requireArguments(params, KEY_ENABLED);
        if (required != null)
        {
            return required;
        }
        String rawEnabled = JsonUtils.extractStringArgument(params, KEY_ENABLED);
        if (!"true".equalsIgnoreCase(rawEnabled) && !"false".equalsIgnoreCase(rawEnabled)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("Invalid enabled value '" + rawEnabled //$NON-NLS-1$
                + "'. Pass the boolean true to enable break-on-error or false to disable it.").toJson(); //$NON-NLS-1$
        }
        boolean enabled = JsonUtils.extractBooleanArgument(params, KEY_ENABLED, false);
        String exceptionMessage = JsonUtils.extractStringArgument(params, KEY_EXCEPTION_MESSAGE);

        if (!enabled && exceptionMessage != null)
        {
            // The disable path preserves each breakpoint's filter and applies none, so honouring
            // this combination silently would leave the old filter in place while the answer
            // said success. Refused with the order that works instead.
            return ToolResult.error("exceptionMessage cannot be combined with enabled=false: " //$NON-NLS-1$
                + "disabling preserves the existing filter and applies none. Set the filter " //$NON-NLS-1$
                + "with enabled=true first, or disable now and pass exceptionMessage when you " //$NON-NLS-1$
                + "re-enable.").toJson(); //$NON-NLS-1$
        }
        try
        {
            BreakpointUtils.ExceptionBreakpointChange change =
                applyChange(enabled, exceptionMessage);
            ToolResult result = ToolResult.success()
                .put("action", change.getAction()) //$NON-NLS-1$
                .put(KEY_ENABLED, enabled)
                .put("workspaceWide", true) //$NON-NLS-1$
                // On BOTH paths: disabling also touches every exception breakpoint, and a
                // caller whose toolset excludes list_breakpoints has no other way to learn
                // which markers to remove. The count travels beside the list because a
                // breakpoint without a marker has no id to contribute.
                .put("breakpointIds", change.getConfiguredIds()) //$NON-NLS-1$
                .put("configuredCount", change.getConfiguredCount()); //$NON-NLS-1$
            // ONE warning, assembled from every reason there is: the same markerless entry can
            // make the filter unverified AND leave ids missing, and two put() calls on the same
            // key would have left only the second reason visible.
            List<String> warnings = new ArrayList<>();
            if (change.getFilterAgreement() == BreakpointUtils.FilterAgreement.UNVERIFIED)
            {
                warnings.add("the filter of at least one affected breakpoint could not be read " //$NON-NLS-1$
                    + "back, so no filter is reported for this call; list_breakpoints shows each " //$NON-NLS-1$
                    + "breakpoint's own state"); //$NON-NLS-1$
            }
            int unaddressable = change.getConfiguredCount() - change.getConfiguredIds().size();
            if (unaddressable > 0)
            {
                warnings.add(unaddressable + " of the affected exception breakpoint(s) have no " //$NON-NLS-1$
                    + "marker, so they are not in breakpointIds and remove_breakpoint cannot " //$NON-NLS-1$
                    + "address them; clear those in the EDT Breakpoints view"); //$NON-NLS-1$
            }
            if (!warnings.isEmpty())
            {
                result.put("warning", String.join("; ", warnings) + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            if (enabled)
            {
                IBreakpoint breakpoint = change.getBreakpoint();
                long markerId = breakpoint != null && breakpoint.getMarker() != null
                    ? breakpoint.getMarker().getId()
                    : -1L;
                result.put("breakpointId", markerId); //$NON-NLS-1$
                // The filter is described only when every configured breakpoint carries the
                // same one. Legacy duplicates may hold different filters, and an enable that
                // omits exceptionMessage preserves each - so reporting the first member's
                // state as THE state would say "errors are filtered" while another catches
                // everything. When they disagree, breakpointIds is the honest answer.
                if (change.getFilterAgreement() == BreakpointUtils.FilterAgreement.SAME)
                {
                    result.put("catchAllExceptions", change.isCatchAll()); //$NON-NLS-1$
                    if (change.getExceptionMessage() != null)
                    {
                        result.put(KEY_EXCEPTION_MESSAGE, change.getExceptionMessage());
                    }
                }
            }
            else
            {
                result.put("disabledCount", change.getDisabledCount()); //$NON-NLS-1$
            }
            return result.toJson();
        }
        catch (Exception e)
        {
            if (e.getMessage() == null
                || !e.getMessage().contains(BreakpointUtils.BSL_BREAKPOINT_FACTORY_SERVICE))
            {
                Activator.logError("Failed to set workspace-wide error breakpoint", e); //$NON-NLS-1$
            }
            return ToolResult.error("Failed to set workspace-wide error breakpoint: " //$NON-NLS-1$
                + endSentence(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                + " Verify EDT's debug core is active, then retry.").toJson(); //$NON-NLS-1$
        }
    }

    private static String endSentence(String detail)
    {
        String text = detail == null ? "" : detail.trim(); //$NON-NLS-1$
        if (text.isEmpty())
        {
            return "(no detail reported)."; //$NON-NLS-1$
        }
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?' ? text : text + "."; //$NON-NLS-1$
    }

    /** Test seam for validation/error-shape tests; production delegates to the OSGi-backed utility. */
    protected BreakpointUtils.ExceptionBreakpointChange applyChange(boolean enabled,
        String exceptionMessage) throws Exception
    {
        return BreakpointUtils.setExceptionBreakpoint(enabled, exceptionMessage);
    }
}
