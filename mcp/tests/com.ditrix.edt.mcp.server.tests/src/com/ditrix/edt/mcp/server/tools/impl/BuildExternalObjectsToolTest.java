/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.AutoConfirmerArmCounts;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;

/**
 * Ratchet tests for {@link BuildExternalObjectsTool} that exercise tool metadata, the
 * JSON input/output schemas (including schema/execute parameter parity and the
 * lowerCamelCase convention), and the argument/path-validation guards that fire BEFORE
 * any live external-object dump — so they run headless.
 * <p>
 * {@code execute()} validates the required arguments and normalizes {@code outputDir}
 * (a pure {@code java.nio.file} check that rejects a file path) up front, then resolves
 * the project (an unresolved name yields a value-naming "Project not found" error). The
 * real build needs a live workspace + 1C runtime and is covered by the E2E suite.
 */
public class BuildExternalObjectsToolTest
{
    /** The exact set of input parameters {@code execute()} reads. Keep in lockstep with the schema. */
    private static final String[] EXECUTE_PARAMS =
        {"projectName", "objectName", "outputDir", "recordBuildTime"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** The input parameters that are required. */
    private static final String[] REQUIRED_PARAMS = {"projectName", "outputDir"}; //$NON-NLS-1$ //$NON-NLS-2$

    // ==================== Metadata ====================

    @Test
    public void testName()
    {
        assertEquals("build_external_objects", new BuildExternalObjectsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(BuildExternalObjectsTool.NAME, new BuildExternalObjectsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        // The tool returns a structured object->path payload, so it must be JSON.
        assertEquals(ResponseType.JSON, new BuildExternalObjectsTool().getResponseType());
    }

    @Test
    public void testConnectsToInfobaseIsTrue()
    {
        // #270: the background dump Job connects to the infobase to read external objects
        // back — it must arm the auth-dialog suppressor's activity window.
        assertTrue(new BuildExternalObjectsTool().connectsToInfobase());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new BuildExternalObjectsTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new BuildExternalObjectsTool().getDescription();
        assertTrue(desc.contains("get_tool_guide('build_external_objects')")); //$NON-NLS-1$
    }

    @Test
    public void testGuideNotEmpty()
    {
        String guide = new BuildExternalObjectsTool().getGuide();
        assertNotNull(guide);
        assertFalse(guide.isEmpty());
    }

    @Test
    public void testGuideNotesGoldenRegeneration()
    {
        // The golden tools_list snapshot must be regenerated on the stand; the guide records this.
        String guide = new BuildExternalObjectsTool().getGuide();
        assertTrue("guide must note the golden snapshot must be regenerated", //$NON-NLS-1$
            guide.toLowerCase().contains("golden")); //$NON-NLS-1$
    }

    // ==================== Input schema ====================

    @Test
    public void testInputSchemaDeclaresAllExecuteParams()
    {
        String schema = new BuildExternalObjectsTool().getInputSchema();
        assertNotNull(schema);
        for (String param : EXECUTE_PARAMS)
        {
            assertTrue("input schema must declare execute() param: " + param, //$NON-NLS-1$
                schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testInputSchemaDeclaresNoExtraParams()
    {
        // Parity (schema -> execute): every property name in the schema must be one execute() reads.
        String schema = new BuildExternalObjectsTool().getInputSchema();
        for (String declared : declaredPropertyNames(schema))
        {
            assertTrue("input schema declares a param execute() does not read: " + declared, //$NON-NLS-1$
                contains(EXECUTE_PARAMS, declared));
        }
    }

    @Test
    public void testRequiredParams()
    {
        String schema = new BuildExternalObjectsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        for (String required : REQUIRED_PARAMS)
        {
            assertTrue(required + " must be required", tail.contains("\"" + required + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        // objectName is optional (omit = build all): it must NOT be in the required array.
        assertFalse("objectName must be optional (omit = build all)", //$NON-NLS-1$
            tail.contains("\"objectName\"")); //$NON-NLS-1$
        // recordBuildTime is optional (default true): it must NOT be in the required array.
        assertFalse("recordBuildTime must be optional (default true)", //$NON-NLS-1$
            tail.contains("\"recordBuildTime\"")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresRecordBuildTime()
    {
        String schema = new BuildExternalObjectsTool().getInputSchema();
        assertTrue("input schema must declare recordBuildTime", //$NON-NLS-1$
            schema.contains("\"recordBuildTime\"")); //$NON-NLS-1$
    }

    // ==================== recordBuildTime parsing (default true, opt-out) ====================

    @Test
    public void testRecordBuildTimeDefaultsTrueWhenAbsentOrBlank()
    {
        // Absent (null) and blank both keep the #202 behaviour: stamp the build time.
        assertTrue(BuildExternalObjectsTool.parseRecordBuildTime(null));
        assertTrue(BuildExternalObjectsTool.parseRecordBuildTime("")); //$NON-NLS-1$
        assertTrue(BuildExternalObjectsTool.parseRecordBuildTime("   ")); //$NON-NLS-1$
    }

    @Test
    public void testRecordBuildTimeTrueValuesEnableStamping()
    {
        assertTrue(BuildExternalObjectsTool.parseRecordBuildTime("true")); //$NON-NLS-1$
        assertTrue(BuildExternalObjectsTool.parseRecordBuildTime("TRUE")); //$NON-NLS-1$
        assertTrue(BuildExternalObjectsTool.parseRecordBuildTime(" True ")); //$NON-NLS-1$
    }

    @Test
    public void testRecordBuildTimeFalseValuesDisableStamping()
    {
        // false (or any non-true token) opts out of mutating the object's Comment.
        assertFalse(BuildExternalObjectsTool.parseRecordBuildTime("false")); //$NON-NLS-1$
        assertFalse(BuildExternalObjectsTool.parseRecordBuildTime("FALSE")); //$NON-NLS-1$
        assertFalse(BuildExternalObjectsTool.parseRecordBuildTime("0")); //$NON-NLS-1$
        assertFalse(BuildExternalObjectsTool.parseRecordBuildTime("no")); //$NON-NLS-1$
    }

    @Test
    public void testAllParamsLowerCamelCase()
    {
        String schema = new BuildExternalObjectsTool().getInputSchema();
        for (String declared : declaredPropertyNames(schema))
        {
            assertTrue("param must be lowerCamelCase: " + declared, isLowerCamelCase(declared)); //$NON-NLS-1$
        }
    }

    // ==================== Output schema ====================

    @Test
    public void testOutputSchemaShape()
    {
        String schema = new BuildExternalObjectsTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare project", schema.contains("\"project\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare outputDir", schema.contains("\"outputDir\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare results", schema.contains("\"results\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare built", schema.contains("\"built\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare failed", schema.contains("\"failed\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaIsPermissive()
    {
        // An outputSchema must stay permissive so a conformant client never rejects a valid payload.
        String schema = new BuildExternalObjectsTool().getOutputSchema();
        assertFalse("outputSchema must not set additionalProperties:false", //$NON-NLS-1$
            schema.replace(" ", "").contains("\"additionalProperties\":false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // ==================== Early validation (returns before any live dump) ====================

    @Test
    public void testExecuteMissingProjectNameIsError()
    {
        // No projectName -> required-argument error, returned before any EDT access.
        Map<String, String> params = new HashMap<>();
        params.put("outputDir", "C:/tmp/out"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new BuildExternalObjectsTool().execute(params);
        assertTrue("missing projectName must produce a 'projectName is required' error", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue("error payload must be JSON", result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExecuteMissingOutputDirIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyExternalObjects"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new BuildExternalObjectsTool().execute(params);
        assertTrue("missing outputDir must produce an 'outputDir is required' error", //$NON-NLS-1$
            result.contains("outputDir is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteOutputDirIsAFileIsError() throws IOException
    {
        // The "exists but is not a directory" guard runs purely on java.nio.file, before any EDT
        // lookup — so it is reachable headless. outputDir is validated first.
        Path tempFile = Files.createTempFile("build-ext-not-a-dir", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "MyExternalObjects"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("outputDir", tempFile.toString()); //$NON-NLS-1$
            String result = new BuildExternalObjectsTool().execute(params);
            assertTrue("a file outputDir must be rejected as not a directory", //$NON-NLS-1$
                result.contains("is not a directory")); //$NON-NLS-1$
            assertTrue("error payload must be JSON", result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testExecuteUnknownProjectIsNamedError() throws IOException
    {
        // A non-external (here: nonexistent) project resolves to a value-naming error that names
        // the bad project AND points at the discovery tool — reached after the pure outputDir check,
        // before any live dump.
        Path tempDir = Files.createTempDirectory("build-ext-out"); //$NON-NLS-1$
        try
        {
            String badProject = "NoSuchExternalObjectsProject_" + System.nanoTime(); //$NON-NLS-1$
            Map<String, String> params = new HashMap<>();
            params.put("projectName", badProject); //$NON-NLS-1$
            params.put("outputDir", tempDir.toString()); //$NON-NLS-1$
            String result = new BuildExternalObjectsTool().execute(params);
            assertTrue("error must name the bad project value", result.contains(badProject)); //$NON-NLS-1$
            assertTrue("error payload must be JSON", result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("error must indicate failure", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            Files.deleteIfExists(tempDir);
        }
    }

    // ==================== helpers ====================

    /** Extracts the property names declared under the schema's {@code "properties"} object. */
    private static java.util.List<String> declaredPropertyNames(String schema)
    {
        java.util.List<String> names = new java.util.ArrayList<>();
        int propsIdx = schema.indexOf("\"properties\""); //$NON-NLS-1$
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        if (propsIdx < 0)
        {
            return names;
        }
        // The "properties" object precedes "required" in JsonSchemaBuilder.build(); scope the scan.
        String propsBlock = requiredIdx > propsIdx ? schema.substring(propsIdx, requiredIdx)
            : schema.substring(propsIdx);
        // Each declared property is a key immediately followed by an object opening: "name":{
        Matcher matcher = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\\{").matcher(propsBlock); //$NON-NLS-1$
        // Skip the leading "properties":{ match itself.
        boolean first = true;
        while (matcher.find())
        {
            if (first)
            {
                first = false;
                continue;
            }
            names.add(matcher.group(1));
        }
        return names;
    }

    // ==================== #618: release the auto-confirmer only when it armed ====================

    /**
     * A headless arm is a no-op. Releasing after it anyway would take one count from a concurrent
     * launch that did arm, and that launch's modals would stop being auto-answered.
     */
    @Test
    public void testANoOpArmIsNotReleased()
    {
        List<String> events = new ArrayList<>();

        String interrupted = BuildExternalObjectsTool.scheduleAndJoinArmed(arm(events, false),
            disarm(events), schedule(events, null), join(events, false), onInterrupted(events));

        assertNull("a wait that ended normally reports no interruption", interrupted);
        assertEquals("a no-op arm must not be released", Arrays.asList("arm", "schedule", "join"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            events);
    }

    /** An arm that took effect is released exactly once, after the wait. */
    @Test
    public void testAnArmThatTookEffectIsReleasedOnceAfterTheWait()
    {
        List<String> events = new ArrayList<>();

        BuildExternalObjectsTool.scheduleAndJoinArmed(arm(events, true), disarm(events),
            schedule(events, null), join(events, false), onInterrupted(events));

        assertEquals("arm, then schedule and wait, then release once",
            Arrays.asList("arm", "schedule", "join", "disarm"), events); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Scheduling stands inside the {@code try} the release guards: when it throws, the arm it
     * followed is still released instead of being held for the rest of the session.
     */
    @Test
    public void testASchedulingFailureStillReleasesTheArm()
    {
        List<String> events = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("job manager is shut down"); //$NON-NLS-1$

        try
        {
            BuildExternalObjectsTool.scheduleAndJoinArmed(arm(events, true), disarm(events),
                schedule(events, failure), join(events, false), onInterrupted(events));
            fail("the scheduling failure must propagate"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertSame(failure, e);
        }

        assertEquals("a scheduling failure must still release the arm",
            Arrays.asList("arm", "schedule", "disarm"), events); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * An interrupted wait builds its response and restores the interrupt flag, then releases the
     * arm — the order the inline code had.
     */
    @Test
    public void testAnInterruptedWaitReportsAndThenReleases()
    {
        List<String> events = new ArrayList<>();

        String interrupted = BuildExternalObjectsTool.scheduleAndJoinArmed(arm(events, true),
            disarm(events), schedule(events, null), join(events, true), onInterrupted(events));
        boolean flagRestored = Thread.interrupted();

        assertEquals("interrupted-response", interrupted); //$NON-NLS-1$
        assertTrue("the interrupt flag must be restored", flagRestored);
        assertEquals("respond to the interruption, then release",
            Arrays.asList("arm", "schedule", "join", "interrupted", "disarm"), events); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    /**
     * The production arm hands back what the auto-confirmer reported instead of assuming success:
     * no workbench runs here, so it must report that nothing was armed.
     */
    @Test
    public void testTheProductionArmReportsAHeadlessNoOp()
    {
        assertFalse("the build's arm must report the auto-confirmer's own result",
            BuildExternalObjectsTool.armLaunchDialogs());
    }

    /**
     * The pair {@code runBuild} hands the seam, end to end: the build's own arm is a headless
     * no-op, so its release must leave alone the arms a concurrent launch holds. A pair that
     * assumed its arm succeeded would take one count of each matcher from that launch.
     */
    @Test
    public void testTheBuildLeavesAConcurrentLaunchsArmsAlone()
    {
        int[] before = AutoConfirmerArmCounts.counts();
        AutoConfirmerArmCounts.arm(true, true, true);
        try
        {
            List<String> events = new ArrayList<>();

            String interrupted = BuildExternalObjectsTool.scheduleAndJoinBuild(
                schedule(events, null), join(events, false), onInterrupted(events));

            assertNull("a wait that ended normally reports no interruption", interrupted);
            assertEquals("the build is scheduled and awaited", Arrays.asList("schedule", "join"), //$NON-NLS-1$ //$NON-NLS-2$
                events);
            assertArrayEquals("the concurrent launch's arms must survive the build's release",
                new int[] { before[0] + 1, before[1] + 1, before[2] + 1 },
                AutoConfirmerArmCounts.counts());
        }
        finally
        {
            // Give back only what the simulated launch still holds.
            int[] after = AutoConfirmerArmCounts.counts();
            LaunchUpdateDialogAutoConfirmer.disarm(after[0] > before[0], after[1] > before[1],
                after[2] > before[2]);
        }
    }

    /**
     * The build's release gives back every matcher its arm takes when a display is present —
     * update, session and restructure. A release narrower than the arm would leave the rest armed
     * for every later modal of the session.
     */
    @Test
    public void testTheBuildsReleaseGivesBackEveryMatcherItsArmTakes()
    {
        int[] before = AutoConfirmerArmCounts.counts();
        // What armLaunchDialogs() takes when it does arm; headless it cannot, so take it here.
        AutoConfirmerArmCounts.arm(true, true, true);
        try
        {
            BuildExternalObjectsTool.disarmLaunchDialogs();

            assertArrayEquals("the release must give back the update, session and restructure arms",
                before, AutoConfirmerArmCounts.counts());
        }
        finally
        {
            int[] after = AutoConfirmerArmCounts.counts();
            LaunchUpdateDialogAutoConfirmer.disarm(after[0] > before[0], after[1] > before[1],
                after[2] > before[2]);
        }
    }

    private static BooleanSupplier arm(List<String> events, boolean result)
    {
        return () -> {
            events.add("arm"); //$NON-NLS-1$
            return result;
        };
    }

    private static Runnable disarm(List<String> events)
    {
        return () -> events.add("disarm"); //$NON-NLS-1$
    }

    private static Runnable schedule(List<String> events, RuntimeException failure)
    {
        return () -> {
            events.add("schedule"); //$NON-NLS-1$
            if (failure != null)
            {
                throw failure;
            }
        };
    }

    private static BuildExternalObjectsTool.InterruptibleJoin join(List<String> events,
        boolean interrupt)
    {
        return () -> {
            events.add("join"); //$NON-NLS-1$
            if (interrupt)
            {
                throw new InterruptedException("test"); //$NON-NLS-1$
            }
        };
    }

    private static Supplier<String> onInterrupted(List<String> events)
    {
        return () -> {
            events.add("interrupted"); //$NON-NLS-1$
            return "interrupted-response"; //$NON-NLS-1$
        };
    }

    private static boolean isLowerCamelCase(String name)
    {
        return name.matches("[a-z][a-zA-Z0-9]*"); //$NON-NLS-1$
    }

    private static boolean contains(String[] array, String value)
    {
        for (String item : array)
        {
            if (item.equals(value))
            {
                return true;
            }
        }
        return false;
    }
}
