/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.ui.navigator.INavigatorActivationService;
import org.eclipse.ui.navigator.INavigatorContentService;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;

/**
 * Unit tests for the workbench-free Navigator activation seam.
 */
public class NavigatorEnhancementManagerTest
{
    private static final String[] EXPECTED_STRUCTURE_EXTENSION_IDS = {
        "com.ditrix.edt.mcp.server.groups.navigatorContent", //$NON-NLS-1$
        "com.ditrix.edt.mcp.server.groups.groupedObjectsFilter" //$NON-NLS-1$
    };

    @Test
    public void testDefaultIsTrueAndActivatesTreeContributions()
    {
        PreferenceStore store = new PreferenceStore();
        assertTrue("Enhance Navigator must default to true", //$NON-NLS-1$
            PreferenceConstants.DEFAULT_ENHANCE_NAVIGATOR);
        assertTrue("an uninitialized preference store must use the true default", //$NON-NLS-1$
            NavigatorEnhancementManager.isEnabled(store));

        INavigatorActivationService activationService = mock(INavigatorActivationService.class);
        INavigatorContentService contentService = mock(INavigatorContentService.class);
        when(contentService.getActivationService()).thenReturn(activationService);
        when(activationService.isNavigatorExtensionActive(anyString())).thenReturn(false);

        NavigatorEnhancementManager.applyPreference(store, contentService);

        ArgumentCaptor<String[]> ids = ArgumentCaptor.forClass(String[].class);
        verify(activationService).activateExtensions(ids.capture(), eq(false));
        assertArrayEquals(EXPECTED_STRUCTURE_EXTENSION_IDS, ids.getValue());
        verify(contentService).update();
    }

    @Test
    public void testFalseDeactivatesOnlyTreeStructureContributions()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_ENHANCE_NAVIGATOR,
            PreferenceConstants.DEFAULT_ENHANCE_NAVIGATOR);
        store.setValue(PreferenceConstants.PREF_ENHANCE_NAVIGATOR, false);
        assertFalse("the stored false value must override the true default", //$NON-NLS-1$
            NavigatorEnhancementManager.isEnabled(store));

        INavigatorActivationService activationService = mock(INavigatorActivationService.class);
        INavigatorContentService contentService = mock(INavigatorContentService.class);
        when(contentService.getActivationService()).thenReturn(activationService);
        when(activationService.isNavigatorExtensionActive(anyString())).thenReturn(true);

        NavigatorEnhancementManager.applyPreference(store, contentService);

        ArgumentCaptor<String[]> ids = ArgumentCaptor.forClass(String[].class);
        verify(activationService).deactivateExtensions(ids.capture(), eq(false));
        assertArrayEquals(EXPECTED_STRUCTURE_EXTENSION_IDS, ids.getValue());
        verify(activationService, never()).activateExtensions(any(String[].class), anyBoolean());
        verify(contentService).update();
    }
}
