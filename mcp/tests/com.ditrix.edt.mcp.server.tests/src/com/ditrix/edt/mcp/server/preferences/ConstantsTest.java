/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import org.junit.Test;

import com.ditrix.edt.mcp.server.groups.GroupConstants;
import com.ditrix.edt.mcp.server.tags.TagConstants;

/**
 * Tests for {@link PreferenceConstants}, {@link GroupConstants}, and {@link TagConstants}.
 * Verifies that constant values are properly defined and internally consistent.
 */
public class ConstantsTest
{
    // ========== PreferenceConstants ==========

    @Test
    public void testPreferenceKeysNotNull()
    {
        assertNotNull(PreferenceConstants.PREF_PORT);
        assertNotNull(PreferenceConstants.PREF_AUTO_START);
        assertNotNull(PreferenceConstants.PREF_CHECKS_FOLDER);
        assertNotNull(PreferenceConstants.PREF_DEFAULT_LIMIT);
        assertNotNull(PreferenceConstants.PREF_MAX_LIMIT);
        assertNotNull(PreferenceConstants.PREF_PLAIN_TEXT_MODE);
    }

    @Test
    public void testPreferenceKeysNotEmpty()
    {
        assertFalse(PreferenceConstants.PREF_PORT.isEmpty());
        assertFalse(PreferenceConstants.PREF_AUTO_START.isEmpty());
        assertFalse(PreferenceConstants.PREF_DEFAULT_LIMIT.isEmpty());
        assertFalse(PreferenceConstants.PREF_MAX_LIMIT.isEmpty());
        assertFalse(PreferenceConstants.PREF_PLAIN_TEXT_MODE.isEmpty());
    }

    @Test
    public void testDefaultPort()
    {
        assertTrue("Port should be positive", PreferenceConstants.DEFAULT_PORT > 0);
        assertTrue("Port should be valid", PreferenceConstants.DEFAULT_PORT <= 65535);
    }

    @Test
    public void testDefaultLimits()
    {
        assertTrue("Default limit should be positive",
            PreferenceConstants.DEFAULT_DEFAULT_LIMIT > 0);
        assertTrue("Max limit should be positive",
            PreferenceConstants.DEFAULT_MAX_LIMIT > 0);
        assertTrue("Max limit should be >= default limit",
            PreferenceConstants.DEFAULT_MAX_LIMIT >= PreferenceConstants.DEFAULT_DEFAULT_LIMIT);
    }

    @Test
    public void testTagDecorationStyles()
    {
        assertNotNull(PreferenceConstants.TAGS_STYLE_SUFFIX);
        assertNotNull(PreferenceConstants.TAGS_STYLE_FIRST_TAG);
        assertNotNull(PreferenceConstants.TAGS_STYLE_COUNT);

        // Styles should be distinct
        assertNotEquals(PreferenceConstants.TAGS_STYLE_SUFFIX, PreferenceConstants.TAGS_STYLE_FIRST_TAG);
        assertNotEquals(PreferenceConstants.TAGS_STYLE_SUFFIX, PreferenceConstants.TAGS_STYLE_COUNT);
        assertNotEquals(PreferenceConstants.TAGS_STYLE_FIRST_TAG, PreferenceConstants.TAGS_STYLE_COUNT);
    }

    @Test
    public void testTagPreferenceKeys()
    {
        assertNotNull(PreferenceConstants.PREF_TAGS_SHOW_IN_NAVIGATOR);
        assertNotNull(PreferenceConstants.PREF_TAGS_DECORATION_STYLE);
        assertFalse(PreferenceConstants.PREF_TAGS_SHOW_IN_NAVIGATOR.isEmpty());
        assertFalse(PreferenceConstants.PREF_TAGS_DECORATION_STYLE.isEmpty());
    }

    @Test
    public void testDefaultDecorationStyle()
    {
        assertEquals("Default decoration style should be suffix",
            PreferenceConstants.TAGS_STYLE_SUFFIX,
            PreferenceConstants.DEFAULT_TAGS_DECORATION_STYLE);
    }

    // ========== GroupConstants ==========

    @Test
    public void testGroupSettingsFolder()
    {
        assertEquals(".settings", GroupConstants.SETTINGS_FOLDER);
    }

    @Test
    public void testGroupFileName()
    {
        assertEquals("groups.yaml", GroupConstants.GROUPS_FILE);
    }

    @Test
    public void testGroupPathConsistency()
    {
        assertEquals(GroupConstants.SETTINGS_FOLDER + "/" + GroupConstants.GROUPS_FILE,
            GroupConstants.GROUPS_PATH);
    }

    // ========== TagConstants ==========

    @Test
    public void testTagSettingsFolder()
    {
        assertEquals(".settings", TagConstants.SETTINGS_FOLDER);
    }

    @Test
    public void testTagFileName()
    {
        assertEquals("metadata-tags.yaml", TagConstants.TAGS_FILE);
    }

    @Test
    public void testTagDefaultColor()
    {
        assertEquals("#808080", TagConstants.DEFAULT_TAG_COLOR);
    }

    @Test
    public void testTagIconSizes()
    {
        assertTrue(TagConstants.COLOR_ICON_SIZE_SMALL > 0);
        assertTrue(TagConstants.COLOR_ICON_SIZE_NORMAL > TagConstants.COLOR_ICON_SIZE_SMALL);
        assertTrue(TagConstants.COLOR_ICON_SIZE_LARGE > TagConstants.COLOR_ICON_SIZE_NORMAL);
    }

    @Test
    public void testTagBmUriScheme()
    {
        assertEquals("bm://", TagConstants.BM_URI_SCHEME);
    }

    @Test
    public void testTagNavigatorViewId()
    {
        assertNotNull(TagConstants.NAVIGATOR_VIEW_ID);
        assertFalse(TagConstants.NAVIGATOR_VIEW_ID.isEmpty());
    }

    @Test
    public void testTagReflectionMethodNames()
    {
        assertEquals("getModel", TagConstants.METHOD_GET_MODEL);
        assertEquals("getProject", TagConstants.METHOD_GET_PROJECT);
    }
}
