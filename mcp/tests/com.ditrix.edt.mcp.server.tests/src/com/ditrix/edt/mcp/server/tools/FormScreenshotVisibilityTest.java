/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Map;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.preferences.ToolPreset;
import com.ditrix.edt.mcp.server.preferences.ToolSettingsService;
import com.ditrix.edt.mcp.server.tools.impl.EnableToolsetTool;

/** Pins the actual tools/list filter and the explicit preference opt-in. */
public class FormScreenshotVisibilityTest
{
    private final McpToolRegistry registry = McpToolRegistry.getInstance();
    private Field plugin;
    private Object previousPlugin;

    @Before
    public void setUp() throws Exception
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION,
            PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION);
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
            plugin.set(null, previousPlugin);
        }
    }

    @Test
    public void testDefaultToolsListOmitsScreenshotAndCallsAreDisabled()
    {
        assertNotNull(registry.getTool("get_form_screenshot"));
        assertFalse(visible());
        assertFalse(registry.isToolEnabled("get_form_screenshot"));
    }

    @Test
    public void testRevealingFormsCannotBypassPreferenceOptIn()
    {
        new EnableToolsetTool().execute(Map.of("toolsets", "[\"forms\"]"));
        assertFalse(visible());
        assertFalse(registry.isToolEnabled("get_form_screenshot"));
    }

    @Test
    public void testToolsPreferenceCanExplicitlyEnableAndDisableScreenshot()
    {
        assertFalse(visible());
        ToolSettingsService.getInstance().setToolEnabled("get_form_screenshot", true);
        assertTrue(visible());
        assertTrue(registry.isToolEnabled("get_form_screenshot"));
        ToolSettingsService.getInstance().setToolEnabled("get_form_screenshot", false);
        assertFalse(visible());
    }

    @Test
    public void testAllToolsPresetExplicitlyEnablesScreenshot()
    {
        ToolSettingsService.getInstance().applyPreset(ToolPreset.ALL_TOOLS);
        assertTrue(visible());
        assertTrue(registry.isToolEnabled("get_form_screenshot"));
    }

    private boolean visible()
    {
        return registry.getVisibleTools().stream().anyMatch(tool -> "get_form_screenshot".equals(tool.getName()));
    }
}
