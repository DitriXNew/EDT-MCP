/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.preferences.ToolPreset;
import com.ditrix.edt.mcp.server.preferences.ToolSettingsService;

/** Exercises the actual getVisibleTools path used to serialize tools/list, with all real tools. */
public class ReadOnlyPresetVisibilityTest
{
    private static final Set<String> WRITERS = Set.of("switch_git_branch", "create_git_branch",
        "set_branch_infobase", "create_project", "resync_to_disk", "clean_project");

    private final McpToolRegistry registry = McpToolRegistry.getInstance();
    private final ToolSettingsService service = ToolSettingsService.getInstance();
    private Field plugin;
    private Object previousPlugin;

    @Before
    public void setUp() throws Exception
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION,
            PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);
        store.setValue(PreferenceConstants.PREF_TOOL_PRESET_ID, "custom");
        store.setValue(PreferenceConstants.PREF_PROGRESSIVE_DISCLOSURE, false);
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        plugin.set(null, activator);
        ToolsetState.getInstance().reset();
        BuiltInToolRegistrar.registerAll(registry);
    }

    @After
    public void tearDown() throws Exception
    {
        try
        {
            registry.clear();
            ToolsetState.getInstance().reset();
        }
        finally
        {
            if (plugin != null)
            {
                plugin.set(null, previousPlugin);
            }
        }
    }

    @Test
    public void testAnalysisOnlyHidesTheWritersAndKeepsTheAnalysisTools()
    {
        assertReadOnlyVisibility(ToolPreset.ANALYSIS_ONLY);
    }

    @Test
    public void testCodeReviewHidesTheWritersAndKeepsTheAnalysisTools()
    {
        assertReadOnlyVisibility(ToolPreset.CODE_REVIEW);
    }

    @Test
    public void testDevelopmentStillAdvertisesAllSixWriters()
    {
        service.applyPreset(ToolPreset.DEVELOPMENT);
        assertTrue(visibleNames().containsAll(WRITERS));
        assertTrue(registry.isToolEnabled("switch_git_branch"));
        assertFalse("the default-off git command stays off", visibleNames().contains("git"));
    }

    private void assertReadOnlyVisibility(ToolPreset preset)
    {
        service.applyPreset(preset);
        Set<String> visible = visibleNames();
        for (String name : WRITERS)
        {
            assertNotNull("writer must be registered for a meaningful test: " + name, registry.getTool(name));
            assertFalse(preset + " must not advertise " + name, visible.contains(name));
            assertFalse(preset + " must refuse calls to " + name, registry.isToolEnabled(name));
        }
        assertTrue(preset + " must keep the analysis and visibility tools",
            visible.containsAll(Set.of("revalidate_objects", "list_git_branches",
                "compare_configurations", "enable_toolset", "get_comparison_node")));
    }

    private Set<String> visibleNames()
    {
        return registry.getVisibleTools().stream().map(IMcpTool::getName).collect(Collectors.toSet());
    }
}
