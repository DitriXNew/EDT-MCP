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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Label;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;

/** Executes the real tab edit/save methods with mocked widgets and an in-memory store. */
public class ToolsTabProvenanceTest
{
    private static final String PRESET_ID = "mcpToolPresetId";

    private PreferenceStore store;
    private ToolsTab tab;
    private Combo presetCombo;
    private Set<String> selection;
    private Field plugin;
    private Object previousPlugin;

    @Before
    public void setUp() throws Exception
    {
        store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_DISABLED_TOOLS,
            PreferenceConstants.DEFAULT_DISABLED_TOOLS);
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS,
            ToolSettingsService.serializeDisabledTools(ToolPreset.CODE_REVIEW.getDisabledTools()));
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 12);
        store.setValue(PRESET_ID, "code-review");
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        plugin = Activator.class.getDeclaredField("plugin");
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        plugin.set(null, activator);

        // Skip widget construction; the edit, defaults and save paths below are real methods.
        tab = mock(ToolsTab.class, CALLS_REAL_METHODS);
        selection = new HashSet<>(ToolPreset.CODE_REVIEW.getDisabledTools());
        setField("disabledTools", selection);
        setField("destructiveAllowedTools", new HashSet<String>());
        setField("currentSpinners", new ArrayList<>());
        setField("pendingValues", new LinkedHashMap<>());
        setField("treeViewer", mock(CheckboxTreeViewer.class));
        presetCombo = mock(Combo.class);
        setField("presetCombo", presetCombo);
        setField("countLabel", mock(Label.class));
        setField("paramSettings", ToolParameterSettings.getInstance());
    }

    @After
    public void tearDown() throws Exception
    {
        plugin.set(null, previousPlugin);
    }

    @Test
    public void testCheckboxEditIsLocalUntilSaveAndThenBecomesCustom() throws Exception
    {
        edit("get_edt_version", false);
        assertEquals("pending edits must not write preferences", "code-review", store.getString(PRESET_ID));
        assertFalse(ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS)).contains("get_edt_version"));
        tab.performOk();
        assertEquals("custom", store.getString(PRESET_ID));
        assertTrue(ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS)).contains("get_edt_version"));
    }

    @Test
    public void testReturningCheckboxesToAPresetShapeDoesNotRestoreProvenance() throws Exception
    {
        edit("get_edt_version", false);
        edit("get_edt_version", true);
        tab.performOk();
        assertEquals(ToolPreset.CODE_REVIEW.getDisabledTools(), selection);
        assertEquals("custom", store.getString(PRESET_ID));
    }

    @Test
    public void testGroupCheckboxEditBecomesCustomOnSave() throws Exception
    {
        edit(ToolGroup.DEBUG, true);
        tab.performOk();
        assertEquals("custom", store.getString(PRESET_ID));
        assertFalse(selection.contains("set_breakpoint"));
    }

    @Test
    public void testRestoreDefaultsClearsPresetOnSave()
    {
        tab.performDefaults();
        assertEquals("defaults are pending until save", "code-review", store.getString(PRESET_ID));
        assertEquals(Set.of("git", "ask_workmate"), selection);
        tab.performOk();
        assertEquals("custom", store.getString(PRESET_ID));
        assertEquals(Set.of("git", "ask_workmate"), ToolSettingsService.parseDisabledTools(
            store.getString(PreferenceConstants.PREF_DISABLED_TOOLS)));
    }

    @Test
    public void testEnableAllButtonRecordsCustomRatherThanInferringAllTools() throws Exception
    {
        clickPresetBarListener(1);
        assertTrue(selection.isEmpty());
        verify(presetCombo).select(ToolPreset.CUSTOM.ordinal());
        tab.performOk();
        assertEquals("custom", store.getString(PRESET_ID));
    }

    @Test
    public void testDisableAllButtonRecordsCustom() throws Exception
    {
        clickPresetBarListener(2);
        Set<String> allTools = new HashSet<>();
        for (ToolGroup group : ToolGroup.values())
        {
            allTools.addAll(group.getToolNames());
        }
        assertEquals(allTools, selection);
        tab.performOk();
        assertEquals("custom", store.getString(PRESET_ID));
    }

    @Test
    public void testPresetDropdownRecordsTheChosenPresetOnlyOnSave() throws Exception
    {
        when(presetCombo.getSelectionIndex()).thenReturn(ToolPreset.DEVELOPMENT.ordinal());
        clickPresetBarListener(3);
        assertEquals(ToolPreset.DEVELOPMENT.getDisabledTools(), selection);
        assertEquals("code-review", store.getString(PRESET_ID));
        tab.performOk();
        assertEquals("development", store.getString(PRESET_ID));
    }

    @Test
    public void testCustomDropdownClearsProvenanceWithoutChangingCheckboxes() throws Exception
    {
        when(presetCombo.getSelectionIndex()).thenReturn(ToolPreset.CUSTOM.ordinal());
        clickPresetBarListener(3);
        assertEquals(ToolPreset.CODE_REVIEW.getDisabledTools(), selection);
        tab.performOk();
        assertEquals("custom", store.getString(PRESET_ID));
    }

    /** The first three anonymous listeners are Enable All, Disable All, and the preset combo. */
    private void clickPresetBarListener(int index) throws Exception
    {
        Class<?> type = Class.forName(ToolsTab.class.getName() + "$" + index);
        var constructor = type.getDeclaredConstructor(ToolsTab.class);
        constructor.setAccessible(true);
        ((SelectionAdapter)constructor.newInstance(tab)).widgetSelected(null);
    }

    private void edit(Object element, boolean checked) throws Exception
    {
        Method edit = ToolsTab.class.getDeclaredMethod("applyCheckChange", Object.class, boolean.class);
        edit.setAccessible(true);
        edit.invoke(tab, element, checked);
    }

    private void setField(String name, Object value) throws Exception
    {
        Field field = ToolsTab.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(tab, value);
    }
}
