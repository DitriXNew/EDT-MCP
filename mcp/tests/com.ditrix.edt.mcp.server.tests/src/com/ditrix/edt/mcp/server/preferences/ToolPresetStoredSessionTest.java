/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Label;
import org.junit.Test;

/** Exercises the tab model constructor, comparison and save path without native SWT controls. */
public class ToolPresetStoredSessionTest extends ToolPresetSessionTestSupport
{
    @Test
    public void testToolsTabLoadAndSavePreservesStoredBytesAndPresetId() throws Exception
    {
        launch("analysis-only");
        byte[] before = serializedStore();
        withTab(tab -> {
            tab.performOk();
            assertArrayEquals("unchanged tab save must preserve every stored preference byte", before,
                serializedStore());
            assertEquals("custom", store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(ToolPreset.ANALYSIS_ONLY.getDisabledTools(), service.getDisabledTools());
        });
    }

    @Test
    public void testStoredViewRoundTripPreservesOriginalSerialization() throws Exception
    {
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            "list_projects, git, get_edt_version,ask_workmate");
        store.setValue(PreferenceConstants.PREF_TOOL_PRESET_ID, "development");
        launch("analysis-only");
        byte[] before = serializedStore();
        service.setDisabledTools(storedDisabledView(), ToolPreset.fromId(storedPresetView()));
        assertArrayEquals("unchanged stored-view save must preserve spacing and ordering", before,
            serializedStore());
        assertEquals("development", store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
    }

    @Test
    public void testToolsTabHasChangesUsesStoredView() throws Exception
    {
        launch("analysis-only");
        withTab(tab -> {
            assertFalse("loading stored settings is not an edit", tab.hasChanges());
            tab.selectPreset(ToolPreset.ALL_TOOLS);
            assertTrue("pending checkbox changes must be detected", tab.hasChanges());
            tab.performOk();
            assertFalse("a saved edit must compare equal to the stored selection", tab.hasChanges());
            assertEquals(ToolPreset.ANALYSIS_ONLY.getDisabledTools(), service.getDisabledTools());
        });
    }

    @Test
    public void testToolsTabShowsLaunchPresetAsReadOnlyNote() throws Exception
    {
        launch("analysis-only");
        withTab(tab -> {
            String text = "Launch preset Analysis Only is active via EDT_MCP_TOOL_PRESET; "
                + "saved changes apply on a launch without it.";
            String actual = null;
            try
            {
                Method note = ToolsTab.class.getDeclaredMethod("getSessionPresetNote");
                note.setAccessible(true);
                actual = (String) note.invoke(tab);
            }
            catch (NoSuchMethodException e)
            {
                // Round one and HEAD have no session note.
            }
            assertEquals("the tab must explain why edits do not affect this launch", text, actual);
            Field selected = ToolsTab.class.getDeclaredField("selectedPreset");
            selected.setAccessible(true);
            assertEquals(ToolPreset.CUSTOM, selected.get(tab));
        });
    }

    @Test
    public void testStoredDisabledViewMigratesEvenWithLaunchPreset() throws Exception
    {
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "list_projects");
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 0);
        launch("all-tools");
        Set<String> migrated = storedDisabledView();
        assertEquals("stored view must return the migrated store", storedDisabled(), migrated);
        assertTrue("known migrations must retain the owner selection and add default-off tools",
            migrated.containsAll(Set.of("list_projects", "git", "ask_workmate")));
        assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
            store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        assertTrue(service.getDisabledTools().isEmpty());
    }

    @Test
    public void testStoredPresetViewMigratesEvenWithLaunchPreset() throws Exception
    {
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(ToolSettingsService.CODE_REVIEW_RECOGNITION_SHAPE));
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 12);
        store.setToDefault(PreferenceConstants.PREF_TOOL_PRESET_ID);
        launch("all-tools");
        assertEquals("code-review", storedPresetView());
        assertEquals("code-review", store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
        assertEquals(PreferenceConstants.TOOL_PREFS_MIGRATION_VERSION,
            store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
    }

    @Test
    public void testPresetIdAccessorIgnoresLaunchPreset() throws Exception
    {
        launch("analysis-only");
        assertEquals("custom", service.getPresetId());
    }

    @Test
    public void testSetToolEnabledAddsOnlyToStoredSelection() throws Exception
    {
        Set<String> expected = new HashSet<>(storedDisabled());
        expected.add("get_markers");
        launch("analysis-only");
        service.setToolEnabled("get_markers", false);
        assertEquals("old stored set plus the edited tool", expected, storedDisabled());
        assertEquals("custom", store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
        assertEquals(ToolPreset.ANALYSIS_ONLY.getDisabledTools(), service.getDisabledTools());
    }

    @Test
    public void testSetGroupEnabledAddsOnlyToStoredSelection() throws Exception
    {
        Set<String> expected = new HashSet<>(storedDisabled());
        expected.addAll(ToolGroup.BSL_CODE.getToolNames());
        launch("analysis-only");
        service.setGroupEnabled(ToolGroup.BSL_CODE, false);
        assertEquals(expected, storedDisabled());
        assertEquals(ToolPreset.ANALYSIS_ONLY.getDisabledTools(), service.getDisabledTools());
    }

    @Test
    public void testCustomPresetKeepsStoredSelection() throws Exception
    {
        store.setValue(PreferenceConstants.PREF_TOOL_PRESET_ID, "development");
        Set<String> before = storedDisabled();
        launch("analysis-only");
        service.applyPreset(ToolPreset.CUSTOM);
        assertEquals(before, storedDisabled());
        assertEquals("custom", store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
        assertEquals(ToolPreset.ANALYSIS_ONLY.getDisabledTools(), service.getDisabledTools());
    }

    private void withTab(TabCheck check) throws Exception
    {
        ToolsTab tab;
        try
        {
            // This is the same model constructor called by the public SWT constructor.
            tab = ToolsTab.class.getDeclaredConstructor().newInstance();
        }
        catch (NoSuchMethodException e)
        {
            // Red-on-base compatibility only: reproduce that version's two constructor reads.
            tab = mock(ToolsTab.class, CALLS_REAL_METHODS);
            setField(tab, "disabledTools", new HashSet<>(service.getDisabledTools()));
            setField(tab, "selectedPreset", ToolPreset.fromId(service.getPresetId()));
            setField(tab, "destructiveAllowedTools", new HashSet<String>());
            setField(tab, "currentSpinners", new ArrayList<>());
            setField(tab, "pendingValues", new LinkedHashMap<>());
            setField(tab, "paramSettings", ToolParameterSettings.getInstance());
        }
        setField(tab, "treeViewer", mock(CheckboxTreeViewer.class));
        setField(tab, "presetCombo", mock(Combo.class));
        setField(tab, "countLabel", mock(Label.class));
        check.run(tab);
    }

    private static void setField(ToolsTab tab, String name, Object value) throws Exception
    {
        Field field = ToolsTab.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(tab, value);
    }

    @FunctionalInterface
    private interface TabCheck
    {
        void run(ToolsTab tab) throws Exception;
    }
}
