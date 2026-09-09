/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link SetBreakpointTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation
 * branches that return (as {@code ToolResult.error(...).toJson()}) before any
 * live workspace/debug access. Project/file resolution and breakpoint creation
 * need a live workspace and are covered by the E2E suite.
 */
public class SetBreakpointToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_breakpoint", new SetBreakpointTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetBreakpointTool.NAME, new SetBreakpointTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new SetBreakpointTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetBreakpointTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new SetBreakpointTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"module\"")); // legacy alias //$NON-NLS-1$
        assertTrue(schema.contains("\"lineNumber\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"condition\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"hitCount\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"hitCondition\"")); //$NON-NLS-1$
        assertTrue(schema.contains("EQUALS")); //$NON-NLS-1$
        assertTrue(schema.contains("EQUAL_OR_LESS")); //$NON-NLS-1$
        assertTrue(schema.contains("EQUAL_OR_HIGHER")); //$NON-NLS-1$
        assertTrue(schema.contains("MULTIPLIER")); //$NON-NLS-1$
        assertTrue(schema.contains("8.3.24")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workspace needed) ====================

    @Test
    public void testMissingModule()
    {
        Map<String, String> params = new HashMap<>();
        params.put("lineNumber", "10"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBreakpointTool().execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingLineNumber()
    {
        Map<String, String> params = new HashMap<>();
        params.put("module", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        // lineNumber omitted -> defaults to -1 -> "lineNumber must be >= 1".
        // Assert on a prefix without '>': Gson escapes '>' as \u003e in the
        // serialized JSON, so matching the literal ">=" would never hit.
        String result = new SetBreakpointTool().execute(params);
        assertTrue(result.contains("lineNumber must be")); //$NON-NLS-1$
    }

    @Test
    public void testModuleRelativePathRequiresProjectName()
    {
        Map<String, String> params = new HashMap<>();
        // a module-relative (non-absolute) path with no projectName, via the legacy 'module' alias
        params.put("module", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("lineNumber", "10"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBreakpointTool().execute(params);
        assertTrue(result.contains("projectName is required when modulePath is given as an EDT module path")); //$NON-NLS-1$
    }

    @Test
    public void testModulePathPrimaryIsRead()
    {
        Map<String, String> params = new HashMap<>();
        // canonical 'modulePath' (module-relative) with no projectName reaches the same
        // projectName-required guard, proving modulePath is read as the primary param.
        params.put("modulePath", "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("lineNumber", "10"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBreakpointTool().execute(params);
        assertTrue(result.contains("projectName is required when modulePath is given as an EDT module path")); //$NON-NLS-1$
    }

    @Test
    public void testNegativeHitCountIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("hitCount", "-3"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBreakpointTool().execute(params);
        assertTrue(result.contains("Invalid hitCount -3")); //$NON-NLS-1$
        assertTrue(result.contains("positive integer")); //$NON-NLS-1$
        assertTrue(result.contains("0/omit hitCount")); //$NON-NLS-1$
    }

    /**
     * A value the shared parser cannot read comes back as the DEFAULT, and the default here means
     * "clear the hit count" - so without this refusal a malformed raw call would quietly
     * reconfigure an existing breakpoint and be answered with success. Dispatch does not validate
     * arguments against the advertised schema, so nothing upstream catches it either.
     */
    @Test
    public void testUnparseableHitCountIsRejectedInsteadOfClearingTheSetting()
    {
        for (String malformed : new String[] {"abc", "1.5", "12abc"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Map<String, String> params = new HashMap<>();
            params.put("hitCount", malformed); //$NON-NLS-1$
            String result = new SetBreakpointTool().execute(params);
            assertTrue("a malformed hitCount must be named back: " + result,
                result.contains("Invalid hitCount '" + malformed + "'")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("and the fix must be stated: " + result,
                result.contains("whole number")); //$NON-NLS-1$
        }
    }

    /**
     * A blank value for an INTEGER parameter is not a value, it is a value the caller got wrong,
     * and letting it through would clear a configured hit count and answer success - the same
     * silent reconfiguration the malformed cases above refuse. The sibling {@code condition} is a
     * STRING, where blank legitimately means "no condition"; that asymmetry is deliberate and the
     * next test pins its other half.
     */
    @Test
    public void testBlankHitCountIsRejectedRatherThanClearingTheSetting()
    {
        for (String blank : new String[] {"", "   "}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Map<String, String> params = new HashMap<>();
            params.put("hitCount", blank); //$NON-NLS-1$
            String result = new SetBreakpointTool().execute(params);
            assertTrue("a blank hitCount must be refused, not treated as clear: " + result,
                result.contains("Invalid hitCount")); //$NON-NLS-1$
            assertTrue("and the fix must be stated: " + result,
                result.contains("whole number")); //$NON-NLS-1$
        }
    }

    @Test
    public void testBlankConditionStillMeansClearNotAnError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("condition", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBreakpointTool().execute(params);
        assertFalse("an empty condition clears it; only the module is missing here: " + result,
            result.contains("Invalid")); //$NON-NLS-1$
        assertTrue(result, result.contains("modulePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testExplicitZeroHitCountIsNotMistakenForAParseFailure()
    {
        // The mirror direction: 0 is the documented way to CLEAR the hit count, so it must not be
        // rejected as malformed. It gets past this validation and fails later, on the module.
        Map<String, String> params = new HashMap<>();
        params.put("hitCount", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBreakpointTool().execute(params);
        assertFalse("0 is a valid value, not a malformed one: " + result,
            result.contains("Invalid hitCount")); //$NON-NLS-1$
    }

    @Test
    public void testHitConditionWithoutPositiveHitCountIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("hitCondition", "MULTIPLIER"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBreakpointTool().execute(params);
        assertTrue(result.contains("hitCondition 'MULTIPLIER' requires a positive hitCount")); //$NON-NLS-1$
        assertTrue(result.contains("omit hitCondition")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownHitConditionNamesAllValidLiterals()
    {
        Map<String, String> params = new HashMap<>();
        params.put("hitCount", "2"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("hitCondition", "AFTER"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetBreakpointTool().execute(params);
        assertTrue(result.contains("Unknown hitCondition 'AFTER'")); //$NON-NLS-1$
        assertTrue(result.contains("EQUALS")); //$NON-NLS-1$
        assertTrue(result.contains("EQUAL_OR_LESS")); //$NON-NLS-1$
        assertTrue(result.contains("EQUAL_OR_HIGHER")); //$NON-NLS-1$
        assertTrue(result.contains("MULTIPLIER")); //$NON-NLS-1$
    }
}
