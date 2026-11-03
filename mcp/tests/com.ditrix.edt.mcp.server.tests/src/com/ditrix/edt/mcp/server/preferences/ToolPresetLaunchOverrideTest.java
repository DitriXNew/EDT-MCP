/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IStatus;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/** Launch-to-registry checks through the lazy service seam, without earlyStartup side effects. */
public class ToolPresetLaunchOverrideTest extends ToolPresetSessionTestSupport
{
    private static final Set<String> WRITERS = Set.of("switch_git_branch", "create_git_branch",
        "set_branch_infobase", "create_project", "resync_to_disk", "clean_project");
    private static final Set<String> KEEPS = Set.of("compare_configurations", "enable_toolset",
        "revalidate_objects");

    @Test
    public void testLaunchParsesEveryPresetId() throws Exception
    {
        for (ToolPreset preset : ToolPreset.values())
        {
            launch(preset.getId());
            assertEquals(preset == ToolPreset.CUSTOM ? null : preset, activePreset());
            Set<String> expected = preset.getDisabledTools();
            assertEquals(preset.name(), expected == null ? storedDisabled() : expected, service.getDisabledTools());
        }
        assertTrue("valid enum IDs must not warn", presetWarnings().isEmpty());
    }

    @Test
    public void testLaunchTrimsThePresetId() throws Exception
    {
        launch("  code-review  ");
        assertEquals(ToolPreset.CODE_REVIEW, activePreset());
        assertEquals(ToolPreset.CODE_REVIEW.getDisabledTools(), service.getDisabledTools());
    }

    @Test
    public void testUnknownIdIsIgnoredWithWarningAndValidIds() throws Exception
    {
        launch("analysis_only_typo");
        assertEquals(storedDisabled(), service.getDisabledTools());
        assertEquals("custom", service.getPresetId());
        List<IStatus> warnings = presetWarnings();
        assertEquals("one launch warning", 1, warnings.size());
        assertEquals(IStatus.WARNING, warnings.get(0).getSeverity());
        String ids = Arrays.stream(ToolPreset.values()).filter(preset -> preset != ToolPreset.CUSTOM)
            .map(ToolPreset::getId).collect(Collectors.joining(", "));
        assertEquals("Invalid EDT_MCP_TOOL_PRESET value: analysis_only_typo. Valid preset ids: "
            + ids + ". Unset or custom uses the stored tool selection. Ignoring launch override.",
            warnings.get(0).getMessage());
    }

    @Test
    public void testPresetIdsAreCaseInsensitive() throws Exception
    {
        launch("CODE-REVIEW");
        assertEquals(ToolPreset.CODE_REVIEW, activePreset());
        assertEquals(ToolPreset.CODE_REVIEW.getDisabledTools(), service.getDisabledTools());
        assertTrue("case-insensitive enum IDs must not warn", presetWarnings().isEmpty());
    }

    @Test
    public void testAnalysisOnlySessionAppliesToVisibleTools() throws Exception
    {
        assertReadOnlyVisibility(ToolPreset.ANALYSIS_ONLY);
        assertFalse(visibleNames().contains("read_module_source"));
    }

    @Test
    public void testCodeReviewSessionAppliesToVisibleTools() throws Exception
    {
        assertReadOnlyVisibility(ToolPreset.CODE_REVIEW);
        assertTrue(visibleNames().contains("read_module_source"));
    }

    @Test
    public void testLaunchPresetDoesNotWriteOrMigrateStoredPreferences() throws Exception
    {
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        Map<String, String> before = snapshot();
        List<String> writes = new ArrayList<>();
        store.addPropertyChangeListener(event -> writes.add(event.getProperty()));
        launch("analysis-only");
        assertEquals(ToolPreset.ANALYSIS_ONLY.getDisabledTools(), service.getDisabledTools());
        assertEquals(ToolPreset.ANALYSIS_ONLY, activePreset());
        assertTrue(visibleNames().contains("list_projects"));
        assertEquals("launch and registry reads must not save or migrate the user's store", before, snapshot());
        assertTrue("no preference changes: " + writes, writes.isEmpty());
    }

    @Test
    public void testAllToolsSessionReplacesStoredExclusions() throws Exception
    {
        launch("all-tools");
        assertEquals(ToolPreset.ALL_TOOLS, activePreset());
        assertTrue(service.getDisabledTools().isEmpty());
        assertEquals(names(registry.getAllTools()), visibleNames());
        assertTrue(registry.isToolEnabled("git"));
    }

    @Test
    public void testDevelopmentSessionKeepsWritersAndDisablesDebugging() throws Exception
    {
        launch("development");
        assertEquals(ToolPreset.DEVELOPMENT, activePreset());
        assertEquals(ToolPreset.DEVELOPMENT.getDisabledTools(), service.getDisabledTools());
        assertTrue(visibleNames().containsAll(WRITERS));
        assertFalse(visibleNames().contains("set_breakpoint"));
        assertFalse(visibleNames().contains("git"));
    }

    @Test
    public void testUnsetBlankAndCustomUseStoredSelection() throws Exception
    {
        for (String value : new String[] {null, "", "  ", "custom"})
        {
            launch("analysis-only");
            assertEquals(ToolPreset.ANALYSIS_ONLY.getDisabledTools(), service.getDisabledTools());
            launch(value);
            assertEquals(storedDisabled(), service.getDisabledTools());
            assertEquals("custom", service.getPresetId());
        }
        assertTrue("unset, blank and custom are not invalid", presetWarnings().isEmpty());
    }

    private void assertReadOnlyVisibility(ToolPreset preset) throws Exception
    {
        launch(preset.getId());
        Set<String> expected = names(registry.getAllTools());
        expected.removeAll(preset.getDisabledTools());
        Set<String> visible = visibleNames();
        assertEquals("session must replace stored exclusions with the preset set", expected, visible);
        assertTrue(visible.containsAll(KEEPS));
        for (String writer : WRITERS)
        {
            assertNotNull("registered writer: " + writer, registry.getTool(writer));
            assertFalse(preset + " advertises " + writer, visible.contains(writer));
            assertFalse(preset + " permits calls to " + writer, registry.isToolEnabled(writer));
        }
        for (IMcpTool tool : registry.getVisibleTools())
        {
            ToolAnnotations own = tool.getAnnotations();
            ToolAnnotations annotations = own != null ? own : ToolAnnotationClassifier.classify(tool.getName());
            assertTrue(preset + " advertises writer " + tool.getName(),
                Boolean.TRUE.equals(annotations.getReadOnlyHint()) || KEEPS.contains(tool.getName()));
        }
    }

}
