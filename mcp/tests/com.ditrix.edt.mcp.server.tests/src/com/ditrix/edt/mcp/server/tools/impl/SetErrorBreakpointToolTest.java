/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.BreakpointUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Headless contract and refusal tests for {@link SetErrorBreakpointTool}. */
public class SetErrorBreakpointToolTest
{
    @Test
    public void testContract()
    {
        SetErrorBreakpointTool tool = new SetErrorBreakpointTool();
        assertEquals("set_error_breakpoint", tool.getName()); //$NON-NLS-1$
        assertEquals(SetErrorBreakpointTool.NAME, tool.getName());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertTrue(tool.getDescription().contains("workspace-wide")); //$NON-NLS-1$
        assertTrue(tool.getDescription().contains("get_tool_guide('set_error_breakpoint')")); //$NON-NLS-1$

        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue(properties.has("enabled")); //$NON-NLS-1$
        assertTrue(properties.has("exceptionMessage")); //$NON-NLS-1$
        assertEquals(1, schema.getAsJsonArray("required").size()); //$NON-NLS-1$
        assertEquals("enabled", schema.getAsJsonArray("required").get(0).getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(properties.getAsJsonObject("exceptionMessage").has("required")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Omit to keep an existing filter (or create catch-all when none exists); " //$NON-NLS-1$
            + "pass an empty string to catch all exceptions; non-empty text is forwarded verbatim " //$NON-NLS-1$
            + "to the platform's break-on-error filter as a message template, so pass a " //$NON-NLS-1$
            + "distinctive message fragment", properties.getAsJsonObject("exceptionMessage") //$NON-NLS-1$ //$NON-NLS-2$
                .get("description").getAsString()); //$NON-NLS-1$
        String enabledDescription = properties.getAsJsonObject("enabled") //$NON-NLS-1$
            .get("description").getAsString(); //$NON-NLS-1$
        assertTrue(enabledDescription.contains("preserving its filter")); //$NON-NLS-1$
        assertTrue(enabledDescription.contains("remove_breakpoint")); //$NON-NLS-1$

        JsonObject output = JsonParser.parseString(tool.getOutputSchema()).getAsJsonObject()
            .getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue(output.has("disabledCount")); //$NON-NLS-1$
        assertFalse(output.has("removedCount")); //$NON-NLS-1$
    }

    @Test
    public void testMissingEnabledIsRejected()
    {
        String result = new SetErrorBreakpointTool().execute(Map.of());
        assertTrue(result.contains("enabled")); //$NON-NLS-1$
        assertTrue(result.contains("success")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidEnabledNamesValueAndFix()
    {
        Map<String, String> params = new HashMap<>();
        params.put("enabled", "sometimes"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetErrorBreakpointTool().execute(params);
        assertTrue(result.contains("Invalid enabled value 'sometimes'")); //$NON-NLS-1$
        assertTrue(result.contains("true")); //$NON-NLS-1$
        assertTrue(result.contains("false")); //$NON-NLS-1$
        assertTrue(result.contains("disable")); //$NON-NLS-1$
    }

    @Test
    public void testDisableReportsCountAndPreservesBreakpoint()
    {
        SetErrorBreakpointTool tool = toolReturning(BreakpointUtils.ExceptionBreakpointChange.disabled(2));
        JsonObject result = JsonParser.parseString(tool.execute(Map.of("enabled", "false"))) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject();

        assertEquals("disabled", result.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, result.get("disabledCount").getAsInt()); //$NON-NLS-1$
        assertFalse(result.get("enabled").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void testDisableWithNoBreakpointReportsNotFound()
    {
        SetErrorBreakpointTool tool = toolReturning(BreakpointUtils.ExceptionBreakpointChange.disabled(0));
        JsonObject result = JsonParser.parseString(tool.execute(Map.of("enabled", "false"))) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject();

        assertEquals("notFound", result.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, result.get("disabledCount").getAsInt()); //$NON-NLS-1$
    }

    @Test
    public void testExceptionMessageOmittedEmptyAndTextRemainDistinct()
    {
        String[] observed = new String[1];
        SetErrorBreakpointTool tool = new SetErrorBreakpointTool()
        {
            @Override
            protected BreakpointUtils.ExceptionBreakpointChange applyChange(boolean enabled,
                String exceptionMessage)
            {
                observed[0] = exceptionMessage;
                return BreakpointUtils.ExceptionBreakpointChange.disabled(0);
            }
        };

        tool.execute(Map.of("enabled", "true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(null, observed[0]);

        tool.execute(Map.of("enabled", "true", "exceptionMessage", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("", observed[0]); //$NON-NLS-1$

        tool.execute(Map.of("enabled", "true", "exceptionMessage", "Exact text")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("Exact text", observed[0]); //$NON-NLS-1$
    }

    @Test
    public void testFailureDetailIsTerminatedBeforeAdvice()
    {
        SetErrorBreakpointTool tool = new SetErrorBreakpointTool()
        {
            @Override
            protected BreakpointUtils.ExceptionBreakpointChange applyChange(boolean enabled,
                String exceptionMessage)
            {
                throw new IllegalStateException("bare detail"); //$NON-NLS-1$
            }
        };

        String result = tool.execute(Map.of("enabled", "true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.contains("bare detail. Verify EDT")); //$NON-NLS-1$
    }

    @Test
    public void testServiceUnavailableErrorNamesLookupAndFix()
    {
        SetErrorBreakpointTool tool = new SetErrorBreakpointTool()
        {
            @Override
            protected BreakpointUtils.ExceptionBreakpointChange applyChange(boolean enabled,
                String exceptionMessage)
            {
                throw new IllegalStateException(BreakpointUtils.breakpointFactoryUnavailableMessage());
            }
        };
        String result = tool.execute(Map.of("enabled", "true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.contains(BreakpointUtils.BSL_BREAKPOINT_FACTORY_SERVICE));
        assertTrue(result.contains("installed and active")); //$NON-NLS-1$
        assertTrue(result.contains("retry")); //$NON-NLS-1$
    }

    private static SetErrorBreakpointTool toolReturning(
        BreakpointUtils.ExceptionBreakpointChange change)
    {
        return new SetErrorBreakpointTool()
        {
            @Override
            protected BreakpointUtils.ExceptionBreakpointChange applyChange(boolean enabled,
                String exceptionMessage)
            {
                return change;
            }
        };
    }
}
