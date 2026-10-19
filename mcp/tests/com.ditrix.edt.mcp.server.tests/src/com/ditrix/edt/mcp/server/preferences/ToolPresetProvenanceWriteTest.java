/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;

/** Retained choices, future migration promises and the current-version read fast path. */
public class ToolPresetProvenanceWriteTest
{
    private static final String PRESET_ID = "mcpToolPresetId";
    private final ToolSettingsService service = ToolSettingsService.getInstance();
    private PreferenceStore store;
    private Field plugin;
    private Object previousPlugin;

    @Before
    public void setUp() throws Exception
    {
        store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS, PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(ToolPreset.CODE_REVIEW.getDisabledTools()));
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 13);
        store.setValue(PRESET_ID, "code-review");
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        plugin.set(null, activator);
    }

    @After
    public void tearDown() throws Exception
    {
        plugin.set(null, previousPlugin);
    }

    @Test
    public void testUnchangedSnapshotMissingGroupGrownToolKeepsIdentity()
    {
        Set<String> snapshot = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
        snapshot.remove("get_job_status");
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(snapshot));
        service.setDisabledTools(snapshot);
        assertEquals("code-review", store.getString(PRESET_ID));
        assertEquals(snapshot, service.getDisabledTools());
    }

    @Test
    public void testRetainedExplicitChoiceIsIndependentOfSnapshotShape() throws Exception
    {
        Set<String> snapshot = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
        snapshot.remove("dcs");
        snapshot.remove("get_job_status");
        service.setDisabledTools(snapshot, ToolPreset.CODE_REVIEW);
        assertEquals("code-review", store.getString(PRESET_ID));
        assertEquals(snapshot, service.getDisabledTools());
    }

    @Test
    public void testOrdinaryEditPreservesUnknownStoredIdentityForFutureUpgrade()
    {
        store.setValue(PRESET_ID, "future-preset");
        service.setToolEnabled("get_edt_version", false);
        assertEquals("future-preset", store.getString(PRESET_ID));
        assertEquals("custom", service.getPresetId());
    }

    @Test
    public void testManualReenableKeepsIdentityAndRemainsEligibleForReadOnlyAddition() throws Exception
    {
        service.setToolEnabled("apply_quick_fix", true);
        assertEquals("code-review", store.getString(PRESET_ID));
        assertFalse(service.getDisabledTools().contains("apply_quick_fix"));
        // Model a future preset-owed step without implementing #641 or registering migration 14.
        Set<String> migrated = new HashSet<>(service.getDisabledTools());
        if (promise(storedPreset(), "isReadOnly"))
        {
            migrated.add("future_read_only_addition");
        }
        assertTrue(migrated.contains("future_read_only_addition"));
        assertFalse(migrated.contains("apply_quick_fix"));
    }

    @Test
    public void testFutureMigrationGateUsesStoredPromiseInBothDirections() throws Exception
    {
        store.setValue(PRESET_ID, "analysis-only");
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "user_disabled_tool");
        assertTrue(promise(storedPreset(), "isReadOnly"));
        assertTrue(promise(storedPreset(), "isNoDebug"));
        store.setValue(PRESET_ID, "custom");
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(ToolPreset.ANALYSIS_ONLY.getDisabledTools()));
        assertFalse(promise(storedPreset(), "isReadOnly"));
        assertFalse(promise(storedPreset(), "isNoDebug"));
    }

    @Test
    public void testPromisePredicatesCoverEveryPreset() throws Exception
    {
        for (ToolPreset preset : ToolPreset.values())
        {
            boolean readOnly = preset == ToolPreset.ANALYSIS_ONLY || preset == ToolPreset.CODE_REVIEW;
            assertEquals(preset.name(), readOnly, promise(preset, "isReadOnly"));
            assertEquals(preset.name(), readOnly || preset == ToolPreset.DEVELOPMENT,
                promise(preset, "isNoDebug"));
        }
    }

    @Test
    public void testPresetAccessorMigratesBeforeReadingIdentity()
    {
        store.setToDefault(PRESET_ID);
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 12);
        Set<String> snapshot = new HashSet<>(ToolPreset.ANALYSIS_ONLY.getDisabledTools());
        snapshot.remove("validate_form_model");
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(snapshot));
        assertEquals("analysis-only", service.getPresetId());
        assertEquals(13, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    @Test
    public void testCurrentVersionReadDoesNotWaitForServiceMonitor() throws Exception
    {
        Set<String> expected = service.getDisabledTools();
        FutureTask<Set<String>> read = new FutureTask<>(service::getDisabledTools);
        Thread reader = new Thread(read, "current tool preferences read");
        reader.setDaemon(true);
        try
        {
            synchronized (service)
            {
                reader.start();
                try
                {
                    assertEquals(expected, read.get(5, TimeUnit.SECONDS));
                }
                catch (TimeoutException e)
                {
                    throw new AssertionError("A migrated-store read must not acquire the service monitor", e);
                }
            }
        }
        finally
        {
            reader.join(5000);
        }
    }

    @Test
    public void testStableIdsArePinnedUniqueAndDefensive()
    {
        Map<ToolPreset, String> ids = Map.of(ToolPreset.ALL_TOOLS, "all-tools",
            ToolPreset.ANALYSIS_ONLY, "analysis-only", ToolPreset.CODE_REVIEW, "code-review",
            ToolPreset.DEVELOPMENT, "development", ToolPreset.CUSTOM, "custom");
        Set<String> unique = new HashSet<>();
        ids.forEach((preset, id) -> {
            assertEquals(id, preset.getId());
            assertTrue(unique.add(id));
            assertEquals(preset, ToolPreset.fromId(id));
        });
        assertEquals(ToolPreset.CUSTOM, ToolPreset.fromId(null));
        for (String id : Set.of("", " ", "unknown", "ANALYSIS_ONLY", " code-review "))
        {
            assertEquals(ToolPreset.CUSTOM, ToolPreset.fromId(id));
        }
    }

    // Reflection keeps these regressions executable against the round-1 production files.
    private ToolPreset storedPreset() throws Exception
    {
        try
        {
            Method method = ToolSettingsService.class.getDeclaredMethod("storedPreset", IPreferenceStore.class);
            method.setAccessible(true);
            return (ToolPreset)method.invoke(null, store);
        }
        catch (NoSuchMethodException e)
        {
            throw new AssertionError("Future steps need one stored-preset reader", e);
        }
    }

    private static boolean promise(ToolPreset preset, String name) throws Exception
    {
        try
        {
            return (Boolean)ToolPreset.class.getMethod(name).invoke(preset);
        }
        catch (NoSuchMethodException e)
        {
            throw new AssertionError("Preset promises must survive arbitrary owner edits", e);
        }
    }
}
