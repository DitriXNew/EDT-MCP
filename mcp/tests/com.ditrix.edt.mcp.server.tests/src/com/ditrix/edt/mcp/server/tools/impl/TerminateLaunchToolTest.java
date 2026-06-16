/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.ILaunch;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tests for {@link TerminateLaunchTool}.
 *
 * Verifies tool identity, schema completeness, and the parameter validation
 * branches in {@code validateSelection}. Does not exercise the actual
 * Eclipse-runtime termination flow.
 */
public class TerminateLaunchToolTest
{
    // === Identity ===

    @Test
    public void testToolName()
    {
        IMcpTool tool = new TerminateLaunchTool();
        assertEquals("terminate_launch", tool.getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        IMcpTool tool = new TerminateLaunchTool();
        assertEquals(IMcpTool.ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        IMcpTool tool = new TerminateLaunchTool();
        String desc = tool.getDescription();
        assertNotNull(desc);
        assertTrue("description should not be empty", desc.length() > 0);
        assertTrue("description should mention EDT-only guarantee",
            desc.toLowerCase().contains("edt"));
    }

    @Test
    public void testGuideCarriesMigratedDetail()
    {
        // The exhaustive detail moved out of getDescription()/getInputSchema() and
        // into getGuide() (Wave 2). Prove it landed there, not vanished.
        IMcpTool tool = new TerminateLaunchTool();
        String guide = tool.getGuide();
        assertNotNull(guide);
        assertTrue("guide should not be empty", guide.length() > 0);
        // Detail migrated from the old description/schema prose.
        assertTrue("guide should explain Attach disconnect semantics",
            guide.contains("disconnected") && guide.toLowerCase().contains("attach"));
        assertTrue("guide should explain the force OS-level escalation",
            guide.toLowerCase().contains("force"));
        assertTrue("guide should document the mutually-exclusive selection modes",
            guide.contains("mutually exclusive"));
        // The guide must explain that a terminated/stale launch is removed
        // from the launch registry so it cannot linger and block a later run.
        assertTrue("guide should document launch-registry removal",
            guide.toLowerCase().contains("removelaunch")
                || guide.toLowerCase().contains("removes it from edt's launch registry")
                || guide.toLowerCase().contains("launch registry"));
        assertTrue("guide should mention clearing the stale already_terminated entry",
            guide.contains("already_terminated"));
        // Selection now also matches already-terminated launches lingering in
        // the registry, and batch responses report the distinct cleaned count.
        assertTrue("guide should document stale already-terminated selection",
            guide.contains("Stale already-terminated launches cleaned"));
    }

    // === Schema ===

    @Test
    public void testSchemaDeclaresAllParameters()
    {
        IMcpTool tool = new TerminateLaunchTool();
        String schema = tool.getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"launchConfigurationName\""));
        assertTrue(schema.contains("\"projectName\""));
        assertTrue(schema.contains("\"applicationId\""));
        assertTrue(schema.contains("\"all\""));
        assertTrue(schema.contains("\"confirm\""));
        assertTrue(schema.contains("\"force\""));
        // Canonical 'timeout' (aligned with run_yaxunit_tests) plus the back-compat
        // 'timeoutSeconds' alias — both declared (and both read by execute()).
        assertTrue(schema.contains("\"timeout\""));
        assertTrue(schema.contains("\"timeoutSeconds\""));
        assertTrue(schema.contains("\"includeAttach\""));
    }

    @Test
    public void testSchemaHasNoRequiredFields()
    {
        // All selection modes are optional individually — the tool validates
        // their combinations at runtime, not via JSON schema.
        IMcpTool tool = new TerminateLaunchTool();
        String schema = tool.getInputSchema();
        // Required list should be empty (i.e. "required":[])
        assertTrue("required array should be present and empty",
            schema.contains("\"required\":[]"));
    }

    @Test
    public void testFileNameByConfigName()
    {
        TerminateLaunchTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("launchConfigurationName", "My Config / ThinClient");
        String name = tool.getResultFileName(params);
        assertTrue("file name should derive from config name",
            name.startsWith("terminate-") && name.endsWith(".md"));
        // Should be sanitised — no slashes or spaces in the middle
        assertFalse("file name should be sanitised", name.contains(" "));
        assertFalse("file name should be sanitised", name.contains("/"));
    }

    @Test
    public void testFileNameForAllMode()
    {
        TerminateLaunchTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("all", "true");
        assertEquals("terminate-all.md", tool.getResultFileName(params));
    }

    @Test
    public void testFileNameForAllModeWithProject()
    {
        TerminateLaunchTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("all", "true");
        params.put("projectName", "MyProject");
        assertEquals("terminate-all-myproject.md", tool.getResultFileName(params));
    }

    @Test
    public void testFileNameForProjectPlusApplicationId()
    {
        // projectName + applicationId mode: both parts are sanitised/lowercased and
        // joined, so parallel calls against different IBs do not collide.
        TerminateLaunchTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "My Proj");
        params.put("applicationId", "App.42");
        String name = tool.getResultFileName(params);
        assertEquals("terminate-my-proj-app.42.md", name);
    }

    @Test
    public void testFileNameTruncatesLongApplicationId()
    {
        // Long UUID-style applicationIds are truncated to 16 sanitised chars to keep
        // the result-file path short.
        TerminateLaunchTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "Proj");
        params.put("applicationId", "0123456789abcdef0123456789abcdef"); // 32 chars
        String name = tool.getResultFileName(params);
        // 16-char truncated tail, then the .md suffix.
        assertEquals("terminate-proj-0123456789abcdef.md", name);
    }

    @Test
    public void testFileNameDefaultFallback()
    {
        // applicationId WITHOUT projectName matches no naming branch (it is not a valid
        // selection) and must fall back to the generic terminate-launch.md.
        TerminateLaunchTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "orphan-app-id");
        assertEquals("terminate-launch.md", tool.getResultFileName(params));
    }

    @Test
    public void testFileNameDefaultFallbackForEmptyParams()
    {
        // No selection params at all → generic default file name.
        TerminateLaunchTool tool = new TerminateLaunchTool();
        assertEquals("terminate-launch.md",
            tool.getResultFileName(new HashMap<String, String>()));
    }

    @Test
    public void testFileNameAllModeIgnoresBlankProject()
    {
        // A blank projectName must NOT produce "terminate-all-.md"; the empty-name
        // guard falls through to the plain all-mode file name.
        TerminateLaunchTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("all", "true");
        params.put("projectName", "");
        assertEquals("terminate-all.md", tool.getResultFileName(params));
    }

    @Test
    public void testFileNameByConfigNameIgnoresOtherParams()
    {
        // launchConfigurationName wins over all other selection params for naming.
        TerminateLaunchTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("launchConfigurationName", "OnlyMe");
        params.put("projectName", "Proj");
        params.put("applicationId", "app");
        params.put("all", "true");
        assertEquals("terminate-onlyme.md", tool.getResultFileName(params));
    }

    // === validateSelection — error messages from execute() ===

    @Test
    public void testEmptyParamsReturnsProvideOneOfError()
    {
        IMcpTool tool = new TerminateLaunchTool();
        String result = tool.execute(new HashMap<String, String>());
        assertNotNull(result);
        assertTrue("must be a structured error", result.contains("\"success\":false"));
        assertTrue("must mention 'Provide exactly one of'",
            result.contains("Provide exactly one of"));
    }

    @Test
    public void testApplicationIdWithoutProjectName()
    {
        IMcpTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "8e2a-fake-id");
        String result = tool.execute(params);
        assertTrue(result.contains("\"success\":false"));
        assertTrue("must explain applicationId requires projectName",
            result.contains("`applicationId` requires `projectName`"));
    }

    @Test
    public void testProjectNameAloneIsAmbiguous()
    {
        IMcpTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject");
        String result = tool.execute(params);
        assertTrue(result.contains("\"success\":false"));
        assertTrue("must explain projectName alone is not a selection",
            result.contains("`projectName` alone is not a selection"));
    }

    @Test
    public void testAllWithoutConfirm()
    {
        IMcpTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("all", "true");
        String result = tool.execute(params);
        assertTrue(result.contains("\"success\":false"));
        // The literal `confirm=true` would be unicode-escaped by Gson (it escapes
        // '='), so assert on delimiter-free substrings of the same message.
        assertTrue("must require confirmation",
            result.contains("Confirmation required")
                && result.contains("confirm"));
    }

    @Test
    public void testApplicationIdCannotBeCombinedWithAll()
    {
        IMcpTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "8e2a-fake-id");
        params.put("all", "true");
        params.put("confirm", "true");
        String result = tool.execute(params);
        assertTrue(result.contains("\"success\":false"));
        // `all=true` contains '=', which Gson unicode-escapes; assert on the
        // delimiter-free leading portion of the same message instead.
        assertTrue("must explain applicationId vs all incompatibility",
            result.contains("`applicationId` cannot be combined with `all"));
    }

    @Test
    public void testNameAndAllAreMutuallyExclusive()
    {
        IMcpTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("launchConfigurationName", "SomeConfig");
        params.put("all", "true");
        params.put("confirm", "true");
        String result = tool.execute(params);
        assertTrue(result.contains("\"success\":false"));
        assertTrue("must report mutual exclusivity",
            result.contains("mutually exclusive"));
    }

    @Test
    public void testNameAndProjectAppIdAreMutuallyExclusive()
    {
        IMcpTool tool = new TerminateLaunchTool();
        Map<String, String> params = new HashMap<>();
        params.put("launchConfigurationName", "SomeConfig");
        params.put("projectName", "Proj");
        params.put("applicationId", "app-id");
        String result = tool.execute(params);
        assertTrue(result.contains("\"success\":false"));
        assertTrue("must report mutual exclusivity",
            result.contains("mutually exclusive"));
    }

    // === selectStaleTerminated — EDT-free guard branches ===
    // This helper is package-private specifically for unit testing. Its data-driven
    // matching path needs live ILaunch/ILaunchConfiguration instances (off-limits
    // here), but the early guards — a null snapshot and an empty snapshot — are pure
    // and exercise the method's contract that it never throws and returns a fresh,
    // empty, mutable list when there is nothing to evict.

    @Test
    public void testSelectStaleTerminatedNullSnapshotReturnsEmpty()
    {
        List<ILaunch> selected = new ArrayList<>();
        List<ILaunch> stale = TerminateLaunchTool.selectStaleTerminated(
            null, selected, "Cfg", "Proj", "app", false, true, false, false);
        assertNotNull("must never return null", stale);
        assertTrue("a null snapshot yields nothing to evict", stale.isEmpty());
    }

    @Test
    public void testSelectStaleTerminatedEmptySnapshotReturnsEmpty()
    {
        // An empty manager snapshot loops zero times and returns an empty result.
        // new ILaunch[0] allocates an array but constructs no ILaunch instance.
        List<ILaunch> stale = TerminateLaunchTool.selectStaleTerminated(
            new ILaunch[0], new ArrayList<ILaunch>(), null, null, null, true, false, false,
            false);
        assertNotNull("must never return null", stale);
        assertTrue("an empty snapshot yields nothing to evict", stale.isEmpty());
    }

    @Test
    public void testSelectStaleTerminatedToleratesNullAlreadySelected()
    {
        // The identity-skip helper must tolerate a null already-selected list; with a
        // null/empty snapshot the method short-circuits before ever consulting it.
        List<ILaunch> stale = TerminateLaunchTool.selectStaleTerminated(
            null, null, "Cfg", null, null, false, true, false, false);
        assertNotNull(stale);
        assertTrue(stale.isEmpty());
    }

    @Test
    public void testSelectStaleTerminatedReturnsMutableList()
    {
        // Callers append to the returned list (targets.addAll then mutate), so it must
        // be a fresh mutable list, not an immutable/shared singleton.
        List<ILaunch> stale = TerminateLaunchTool.selectStaleTerminated(
            new ILaunch[0], new ArrayList<ILaunch>(), null, "Proj", null, true, false, false,
            false);
        stale.clear(); // must not throw on an unmodifiable list
        assertTrue(stale.isEmpty());
    }
}
