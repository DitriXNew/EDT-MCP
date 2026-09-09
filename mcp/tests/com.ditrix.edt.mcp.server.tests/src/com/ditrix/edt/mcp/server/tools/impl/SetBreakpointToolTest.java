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

import java.util.Arrays;
import java.util.Collections;
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

    /**
     * A creation that degraded says so; an UPDATE must not, because it looked up no class and
     * created nothing. The wrong spelling is asserted ABSENT, since both texts contain
     * "marker-only" and a substring check for it would pass either way.
     */
    @Test
    public void testDegradedWarningDoesNotClaimACreationOnTheUpdatePath()
    {
        String created = SetBreakpointTool.degradedWarning(true, 1, 1, false, false);
        assertTrue("a created fallback must say it was created: " + created,
            created.contains("created a marker-only breakpoint"));

        String updated = SetBreakpointTool.degradedWarning(false, 1, 1, false, false);
        assertFalse("an update created nothing, so it must not claim a creation: " + updated,
            updated.contains("created a marker-only"));
        assertFalse("an update looked up no class, so it must not blame one: " + updated,
            updated.contains("class not available"));
        assertTrue("an all-degraded update must say every registered breakpoint is: " + updated,
            updated.contains("Every breakpoint already registered at this line is marker-only"));
    }

    /**
     * A native breakpoint beside a marker-only twin is a MIXED set: the count must be named and
     * the surviving native member acknowledged, instead of condemning the whole line.
     */
    @Test
    public void testDegradedWarningNamesTheMixedSetInsteadOfCondemningTheLine()
    {
        String mixed = SetBreakpointTool.degradedWarning(false, 1, 2, true, false);
        assertTrue("a mixed set must count the degraded members: " + mixed,
            mixed.contains("1 of 2 breakpoints"));
        assertTrue("a mixed set must say the others still work: " + mixed,
            mixed.contains("the rest are native"));
        assertFalse("a mixed set is not an all-degraded one: " + mixed,
            mixed.contains("Every breakpoint already registered"));
        // The condition DID reach the native twin, so an unqualified "not applied" is wrong.
        assertTrue("a mixed set must say where the condition failed to land: " + mixed,
            mixed.contains("The condition was NOT applied to the marker-only breakpoints."));
        assertFalse("a mixed set must not report a hit count nobody asked for: " + mixed,
            mixed.contains("hit count"));

        String all = SetBreakpointTool.degradedWarning(false, 2, 2, true, true);
        assertTrue("an all-degraded set applied neither, with no qualifier: " + all,
            all.contains("The condition and hit count were NOT applied. "));
    }

    /**
     * A half-finished UPDATE leaves real mutations behind: the failure must hand back the ids of
     * what it had begun changing, or the caller cannot find them. A failed CREATION is withdrawn,
     * so its message must stay clean of that clause.
     */
    @Test
    public void testFailureMessageNamesTheBreakpointsAlreadyChanged()
    {
        String partial = SetBreakpointTool.failureMessage("marker no longer exists",
            Arrays.asList(Long.valueOf(12L), Long.valueOf(15L)));
        assertTrue("the failure must carry the cause: " + partial,
            partial.contains("marker no longer exists"));
        assertTrue("both mutated ids must be named: " + partial,
            partial.contains("id(s) 12, 15"));
        // "may carry PART of", not "keep": the failure can land before the first setter wrote
        // anything, so a message asserting the settled state would claim what nobody knows.
        assertTrue("the caller must be told what is uncertain about them: " + partial,
            partial.contains("each may carry part of the new settings"));
        assertFalse("the message must not assert a state the call never established: " + partial,
            partial.contains("keep the new settings"));

        String clean = SetBreakpointTool.failureMessage("boom", Collections.emptyList());
        assertFalse("nothing survived a failed creation, so nothing may be claimed: " + clean,
            clean.contains("already begun changing"));
        assertTrue("every failure still points at the Breakpoints view: " + clean,
            clean.contains("Breakpoints view"));
    }
}
