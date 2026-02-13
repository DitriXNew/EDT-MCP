/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.tags.ui;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.TagService.ITagChangeListener;
import com.ditrix.edt.mcp.server.tags.TagUtils;
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Label decorator that shows tags assigned to metadata objects.
 * Tags are displayed as badges/suffixes next to the object name.
 * 
 * <p>Uses {@link TagUtils} for FQN and project extraction to avoid code duplication.</p>
 * 
 * <p>Implements debouncing for refresh requests to avoid excessive updates
 * when multiple tag changes occur in quick succession.</p>
 */
public class TagLabelDecorator implements ILightweightLabelDecorator, ITagChangeListener {
    
    /** Debounce delay in milliseconds */
    private static final long REFRESH_DEBOUNCE_MS = 100;
    
    private final TagService tagService;
    
    /** Timestamp of last scheduled refresh */
    private final AtomicLong lastRefreshRequest = new AtomicLong(0);
    
    /** Flag to track if a refresh is pending */
    private volatile boolean refreshPending = false;
    
    /**
     * Creates a new decorator.
     */
    public TagLabelDecorator() {
        this.tagService = TagService.getInstance();
        this.tagService.addTagChangeListener(this);
    }
    
    @Override
    public void decorate(Object element, IDecoration decoration) {
        if (!isDecorationEnabled()) {
            return;
        }
        
        if (element instanceof EObject eobj) {
            IProject project = TagUtils.extractProject(eobj);
            String fqn = TagUtils.extractFqn(eobj);
            
            if (project != null && fqn != null) {
                Set<Tag> tags = tagService.getObjectTags(project, fqn);
                if (!tags.isEmpty()) {
                    String suffix = formatTags(tags);
                    decoration.addSuffix(suffix);
                }
            }
        }
    }
    
    private boolean isDecorationEnabled() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        return store.getBoolean(PreferenceConstants.PREF_TAGS_SHOW_IN_NAVIGATOR);
    }
    
    private String getDecorationStyle() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        return store.getString(PreferenceConstants.PREF_TAGS_DECORATION_STYLE);
    }
    
    private String formatTags(Set<Tag> tags) {
        if (tags.isEmpty()) {
            return "";
        }
        
        String style = getDecorationStyle();
        
        if (PreferenceConstants.TAGS_STYLE_COUNT.equals(style)) {
            return " [" + tags.size() + " tags]";
        }
        
        if (PreferenceConstants.TAGS_STYLE_FIRST_TAG.equals(style)) {
            Tag first = tags.iterator().next();
            return " [" + first.getName() + "]";
        }
        
        // Default: STYLE_SUFFIX - show all tags
        StringBuilder sb = new StringBuilder(" [");
        boolean first = true;
        for (Tag tag : tags) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(tag.getName());
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }
    
    @Override
    public void onTagsChanged(IProject project) {
        scheduleRefresh();
    }
    
    @Override
    public void onAssignmentsChanged(IProject project, String objectFqn) {
        scheduleRefresh();
    }
    
    /**
     * Schedules a debounced decoration refresh.
     * Multiple calls within REFRESH_DEBOUNCE_MS will be coalesced into a single refresh.
     */
    private void scheduleRefresh() {
        long now = System.currentTimeMillis();
        lastRefreshRequest.set(now);
        
        if (!refreshPending) {
            refreshPending = true;
            org.eclipse.swt.widgets.Display.getDefault().timerExec((int) REFRESH_DEBOUNCE_MS, () -> {
                executeRefresh();
            });
        }
    }
    
    /**
     * Executes the actual decoration refresh.
     * Checks if more refresh requests came in during the debounce period.
     */
    private void executeRefresh() {
        long lastRequest = lastRefreshRequest.get();
        long elapsed = System.currentTimeMillis() - lastRequest;
        
        if (elapsed < REFRESH_DEBOUNCE_MS) {
            // More requests came in, reschedule
            org.eclipse.swt.widgets.Display.getDefault().timerExec((int) (REFRESH_DEBOUNCE_MS - elapsed), () -> {
                executeRefresh();
            });
        } else {
            // Debounce period passed, execute refresh
            refreshPending = false;
            try {
                org.eclipse.ui.PlatformUI.getWorkbench()
                    .getDecoratorManager()
                    .update("com.ditrix.edt.mcp.server.tags.decorator");
            } catch (Exception e) {
                Activator.logDebug("Failed to refresh decorations: " + e.getMessage());
            }
        }
    }
    
    @Override
    public void addListener(ILabelProviderListener listener) {
        // Not needed for lightweight decorators
    }
    
    @Override
    public void removeListener(ILabelProviderListener listener) {
        // Not needed for lightweight decorators
    }
    
    @Override
    public boolean isLabelProperty(Object element, String property) {
        return false;
    }
    
    @Override
    public void dispose() {
        tagService.removeTagChangeListener(this);
    }
}
