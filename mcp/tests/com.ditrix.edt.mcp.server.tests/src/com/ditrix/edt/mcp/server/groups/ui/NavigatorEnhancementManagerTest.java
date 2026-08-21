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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.ui.navigator.ICommonFilterDescriptor;
import org.eclipse.ui.navigator.INavigatorActivationService;
import org.eclipse.ui.navigator.INavigatorContentService;
import org.eclipse.ui.navigator.INavigatorFilterService;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;

/**
 * Unit tests for the workbench-free Navigator content/filter seam.
 */
public class NavigatorEnhancementManagerTest
{
    private static final String GROUPS_CONTENT_ID =
        "com.ditrix.edt.mcp.server.groups.navigatorContent"; //$NON-NLS-1$
    private static final String GROUPED_OBJECTS_FILTER_ID =
        "com.ditrix.edt.mcp.server.groups.groupedObjectsFilter"; //$NON-NLS-1$
    private static final String EDT_FILTER_ID = "com.example.edtFilter"; //$NON-NLS-1$
    private static final String COMFORT_FILTER_ID = "com.example.comfortFilter"; //$NON-NLS-1$
    private static final String INACTIVE_FILTER_ID = "com.example.inactiveFilter"; //$NON-NLS-1$

    private static final String[] EXPECTED_CONTENT_EXTENSION_IDS = {
        GROUPS_CONTENT_ID
    };

    @Test
    public void testDefaultActivatesGroupsContentAndFilter()
    {
        PreferenceStore store = new PreferenceStore();
        assertTrue("Enhance Navigator must default to true", //$NON-NLS-1$
            PreferenceConstants.DEFAULT_ENHANCE_NAVIGATOR);
        assertTrue("an uninitialized preference store must use the true default", //$NON-NLS-1$
            NavigatorEnhancementManager.isEnabled(store));

        INavigatorActivationService activationService = mock(INavigatorActivationService.class);
        INavigatorFilterService filterService = mock(INavigatorFilterService.class);
        INavigatorContentService contentService = mock(INavigatorContentService.class);
        ViewerFilter directlyInstalledFilter = mock(ViewerFilter.class);
        ViewerFilter groupedObjectsFilter = mock(ViewerFilter.class);
        StructuredViewer viewer = viewerWithFilters();
        viewer.addFilter(directlyInstalledFilter);
        when(contentService.getActivationService()).thenReturn(activationService);
        when(contentService.getFilterService()).thenReturn(filterService);
        when(activationService.isNavigatorExtensionActive(anyString())).thenReturn(false);
        when(filterService.isActive(anyString())).thenAnswer(invocation -> {
            String filterId = invocation.getArgument(0);
            return EDT_FILTER_ID.equals(filterId) || COMFORT_FILTER_ID.equals(filterId);
        });
        ICommonFilterDescriptor groupedObjectsDescriptor = descriptor(GROUPED_OBJECTS_FILTER_ID);
        ICommonFilterDescriptor[] descriptors = {
            descriptor(EDT_FILTER_ID), descriptor(COMFORT_FILTER_ID),
            descriptor(INACTIVE_FILTER_ID), groupedObjectsDescriptor
        };
        when(filterService.getVisibleFilterDescriptors()).thenReturn(descriptors);
        when(filterService.getViewerFilter(groupedObjectsDescriptor))
            .thenReturn(groupedObjectsFilter);
        when(filterService.getVisibleFilters(true))
            .thenReturn(new ViewerFilter[] { groupedObjectsFilter });

        NavigatorEnhancementManager.applyPreference(store, contentService, viewer);

        ArgumentCaptor<String[]> contentIds = ArgumentCaptor.forClass(String[].class);
        verify(activationService).activateExtensions(contentIds.capture(), eq(false));
        assertArrayEquals(EXPECTED_CONTENT_EXTENSION_IDS, contentIds.getValue());

        ArgumentCaptor<String[]> filterIds = ArgumentCaptor.forClass(String[].class);
        verify(filterService).setActiveFilterIds(filterIds.capture());
        assertArrayEquals(new String[] {
            EDT_FILTER_ID, COMFORT_FILTER_ID, GROUPED_OBJECTS_FILTER_ID
        }, filterIds.getValue());
        verify(filterService, never()).activateFilterIdsAndUpdateViewer(any(String[].class));
        verify(filterService, never()).persistFilterActivationState();
        verify(filterService, never()).getVisibleFilters(anyBoolean());
        verify(viewer).addFilter(groupedObjectsFilter);
        verify(viewer, never()).setFilters(any(ViewerFilter[].class));
        assertArrayEquals("directly installed filters must survive enabling", //$NON-NLS-1$
            new ViewerFilter[] { directlyInstalledFilter, groupedObjectsFilter },
            viewer.getFilters());
        verify(contentService).update();
    }

    @Test
    public void testFalseDeactivatesGroupsContentAndFilterPreservingOthers()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_ENHANCE_NAVIGATOR,
            PreferenceConstants.DEFAULT_ENHANCE_NAVIGATOR);
        store.setValue(PreferenceConstants.PREF_ENHANCE_NAVIGATOR, false);
        assertFalse("the stored false value must override the true default", //$NON-NLS-1$
            NavigatorEnhancementManager.isEnabled(store));

        INavigatorActivationService activationService = mock(INavigatorActivationService.class);
        INavigatorFilterService filterService = mock(INavigatorFilterService.class);
        INavigatorContentService contentService = mock(INavigatorContentService.class);
        ViewerFilter directlyInstalledFilter = mock(ViewerFilter.class);
        ViewerFilter groupedObjectsFilter = mock(ViewerFilter.class);
        StructuredViewer viewer = viewerWithFilters();
        viewer.addFilter(directlyInstalledFilter);
        viewer.addFilter(groupedObjectsFilter);
        when(contentService.getActivationService()).thenReturn(activationService);
        when(contentService.getFilterService()).thenReturn(filterService);
        when(activationService.isNavigatorExtensionActive(anyString())).thenReturn(true);
        when(filterService.isActive(anyString())).thenAnswer(invocation -> {
            String filterId = invocation.getArgument(0);
            return !INACTIVE_FILTER_ID.equals(filterId);
        });
        ICommonFilterDescriptor groupedObjectsDescriptor = descriptor(GROUPED_OBJECTS_FILTER_ID);
        ICommonFilterDescriptor[] descriptors = {
            groupedObjectsDescriptor, descriptor(EDT_FILTER_ID),
            descriptor(COMFORT_FILTER_ID), descriptor(INACTIVE_FILTER_ID)
        };
        when(filterService.getVisibleFilterDescriptors()).thenReturn(descriptors);
        when(filterService.getViewerFilter(groupedObjectsDescriptor))
            .thenReturn(groupedObjectsFilter);
        when(filterService.getVisibleFilters(true))
            .thenReturn(new ViewerFilter[] { groupedObjectsFilter });

        NavigatorEnhancementManager.applyPreference(store, contentService, viewer);

        ArgumentCaptor<String[]> contentIds = ArgumentCaptor.forClass(String[].class);
        verify(activationService).deactivateExtensions(contentIds.capture(), eq(false));
        assertArrayEquals(EXPECTED_CONTENT_EXTENSION_IDS, contentIds.getValue());
        verify(activationService, never()).activateExtensions(any(String[].class), anyBoolean());

        ArgumentCaptor<String[]> filterIds = ArgumentCaptor.forClass(String[].class);
        verify(filterService).setActiveFilterIds(filterIds.capture());
        assertArrayEquals(new String[] { EDT_FILTER_ID, COMFORT_FILTER_ID },
            filterIds.getValue());
        verify(filterService, never()).activateFilterIdsAndUpdateViewer(any(String[].class));
        verify(filterService, never()).persistFilterActivationState();
        verify(filterService, never()).getVisibleFilters(anyBoolean());
        verify(viewer).removeFilter(groupedObjectsFilter);
        verify(viewer, never()).setFilters(any(ViewerFilter[].class));
        assertArrayEquals("directly installed filters must survive disabling", //$NON-NLS-1$
            new ViewerFilter[] { directlyInstalledFilter }, viewer.getFilters());
        verify(contentService).update();
    }

    @Test
    public void testFalseRemovesStaleViewerFilterWithoutUpdatingService()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_ENHANCE_NAVIGATOR,
            PreferenceConstants.DEFAULT_ENHANCE_NAVIGATOR);
        store.setValue(PreferenceConstants.PREF_ENHANCE_NAVIGATOR, false);

        INavigatorActivationService activationService = mock(INavigatorActivationService.class);
        INavigatorFilterService filterService = mock(INavigatorFilterService.class);
        INavigatorContentService contentService = mock(INavigatorContentService.class);
        ViewerFilter groupedObjectsFilter = mock(ViewerFilter.class);
        StructuredViewer viewer = viewerWithFilters();
        viewer.addFilter(groupedObjectsFilter);
        when(contentService.getActivationService()).thenReturn(activationService);
        when(contentService.getFilterService()).thenReturn(filterService);
        when(activationService.isNavigatorExtensionActive(anyString())).thenReturn(false);
        when(filterService.isActive(anyString())).thenReturn(false);
        ICommonFilterDescriptor groupedObjectsDescriptor = descriptor(GROUPED_OBJECTS_FILTER_ID);
        when(filterService.getVisibleFilterDescriptors())
            .thenReturn(new ICommonFilterDescriptor[] { groupedObjectsDescriptor });
        when(filterService.getViewerFilter(groupedObjectsDescriptor))
            .thenReturn(groupedObjectsFilter);

        NavigatorEnhancementManager.applyPreference(store, contentService, viewer);

        verify(filterService, never()).setActiveFilterIds(any(String[].class));
        verify(viewer).removeFilter(groupedObjectsFilter);
        assertArrayEquals("the stale filter must be removed from the viewer", //$NON-NLS-1$
            new ViewerFilter[0], viewer.getFilters());
        verify(contentService).update();
    }

    @Test
    public void testMissingGroupedDescriptorRetriesWithoutStrandingFilterState()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PreferenceConstants.PREF_ENHANCE_NAVIGATOR,
            PreferenceConstants.DEFAULT_ENHANCE_NAVIGATOR);
        store.setValue(PreferenceConstants.PREF_ENHANCE_NAVIGATOR, false);

        INavigatorActivationService activationService = mock(INavigatorActivationService.class);
        INavigatorFilterService filterService = mock(INavigatorFilterService.class);
        INavigatorContentService contentService = mock(INavigatorContentService.class);
        ViewerFilter groupedObjectsFilter = mock(ViewerFilter.class);
        StructuredViewer viewer = viewerWithFilters();
        viewer.addFilter(groupedObjectsFilter);
        when(contentService.getActivationService()).thenReturn(activationService);
        when(contentService.getFilterService()).thenReturn(filterService);
        when(activationService.isNavigatorExtensionActive(anyString())).thenReturn(true);
        when(filterService.isActive(anyString())).thenReturn(true);
        ICommonFilterDescriptor edtDescriptor = descriptor(EDT_FILTER_ID);
        when(filterService.getVisibleFilterDescriptors())
            .thenReturn(new ICommonFilterDescriptor[] { edtDescriptor });

        NavigatorEnhancementManager.applyPreference(store, contentService, viewer);

        verify(activationService, never()).activateExtensions(any(String[].class), anyBoolean());
        verify(activationService, never()).deactivateExtensions(any(String[].class), anyBoolean());
        verify(filterService, never()).setActiveFilterIds(any(String[].class));
        verify(viewer, never()).removeFilter(groupedObjectsFilter);
        verify(contentService, never()).update();
        assertArrayEquals("the installed filter must remain until it can be resolved", //$NON-NLS-1$
            new ViewerFilter[] { groupedObjectsFilter }, viewer.getFilters());

        ICommonFilterDescriptor groupedObjectsDescriptor = descriptor(GROUPED_OBJECTS_FILTER_ID);
        when(filterService.getVisibleFilterDescriptors()).thenReturn(new ICommonFilterDescriptor[] {
            edtDescriptor, groupedObjectsDescriptor
        });
        when(filterService.getViewerFilter(groupedObjectsDescriptor))
            .thenReturn(groupedObjectsFilter);

        NavigatorEnhancementManager.applyPreference(store, contentService, viewer);

        ArgumentCaptor<String[]> contentIds = ArgumentCaptor.forClass(String[].class);
        verify(activationService).deactivateExtensions(contentIds.capture(), eq(false));
        assertArrayEquals(EXPECTED_CONTENT_EXTENSION_IDS, contentIds.getValue());
        ArgumentCaptor<String[]> filterIds = ArgumentCaptor.forClass(String[].class);
        verify(filterService).setActiveFilterIds(filterIds.capture());
        assertArrayEquals(new String[] { EDT_FILTER_ID }, filterIds.getValue());
        verify(viewer).removeFilter(groupedObjectsFilter);
        assertArrayEquals("the retry must remove the resolved filter", //$NON-NLS-1$
            new ViewerFilter[0], viewer.getFilters());
        verify(contentService).update();
    }

    private static StructuredViewer viewerWithFilters()
    {
        StructuredViewer viewer = mock(StructuredViewer.class);
        List<ViewerFilter> filters = new ArrayList<>();
        when(viewer.getFilters()).thenAnswer(invocation -> filters.toArray(ViewerFilter[]::new));
        doAnswer(invocation -> {
            filters.add(invocation.getArgument(0));
            return null;
        }).when(viewer).addFilter(any(ViewerFilter.class));
        doAnswer(invocation -> {
            ViewerFilter filter = invocation.getArgument(0);
            filters.removeIf(installedFilter -> installedFilter == filter);
            return null;
        }).when(viewer).removeFilter(any(ViewerFilter.class));
        doAnswer(invocation -> {
            ViewerFilter[] replacementFilters = invocation.getArgument(0);
            filters.clear();
            filters.addAll(Arrays.asList(replacementFilters));
            return null;
        }).when(viewer).setFilters(any(ViewerFilter[].class));
        return viewer;
    }

    private static ICommonFilterDescriptor descriptor(String id)
    {
        ICommonFilterDescriptor descriptor = mock(ICommonFilterDescriptor.class);
        when(descriptor.getId()).thenReturn(id);
        return descriptor;
    }
}
