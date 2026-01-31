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
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Label decorator that shows tags assigned to metadata objects.
 * Tags are displayed as badges/suffixes next to the object name.
 */
public class TagLabelDecorator implements ILightweightLabelDecorator, ITagChangeListener {
    
    private final TagService tagService;
    
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
            IProject project = extractProject(eobj);
            String fqn = extractFqn(eobj);
            
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
    
    private IProject extractProject(EObject eobj) {
        if (eobj.eResource() != null && eobj.eResource().getURI() != null) {
            org.eclipse.emf.common.util.URI uri = eobj.eResource().getURI();
            
            // Try platform string first
            String uriPath = uri.toPlatformString(true);
            if (uriPath != null && uriPath.length() > 1) {
                String projectName = uriPath.split("/")[1];
                return org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                    .getRoot().getProject(projectName);
            }
            
            // Try bm:// URI scheme
            String uriString = uri.toString();
            if (uriString.startsWith("bm://")) {
                // Format: bm://projectName/...
                String[] parts = uriString.substring(5).split("/");
                if (parts.length > 0) {
                    return org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                        .getRoot().getProject(parts[0]);
                }
            }
        }
        return null;
    }
    
    /**
     * Extracts the fully qualified name (FQN) by traversing the parent hierarchy.
     * For nested objects like Document.SalesOrder.DocumentAttribute.CustomerName,
     * this builds the complete FQN path.
     */
    private String extractFqn(EObject mdObject) {
        if (mdObject == null) {
            return null;
        }
        
        try {
            StringBuilder fqnBuilder = new StringBuilder();
            EObject current = mdObject;
            
            while (current != null) {
                String typeName = current.eClass().getName();
                
                // Stop at Configuration or internal model objects
                if ("Configuration".equals(typeName) || typeName.startsWith("Md")) {
                    break;
                }
                
                String name = getObjectName(current);
                
                if (name != null && !name.isEmpty()) {
                    String part = typeName + "." + name;
                    if (fqnBuilder.length() > 0) {
                        fqnBuilder.insert(0, ".");
                    }
                    fqnBuilder.insert(0, part);
                }
                
                current = current.eContainer();
            }
            
            return fqnBuilder.length() > 0 ? fqnBuilder.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Gets object name using reflection.
     */
    private String getObjectName(EObject eObject) {
        try {
            for (java.lang.reflect.Method m : eObject.getClass().getMethods()) {
                if ("getName".equals(m.getName()) && m.getParameterCount() == 0) {
                    Object name = m.invoke(eObject);
                    return name != null ? name.toString() : null;
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    @Override
    public void onTagsChanged(IProject project) {
        // Refresh all decorations when tags change
        refreshDecorations();
    }
    
    @Override
    public void onAssignmentsChanged(IProject project, String objectFqn) {
        // Refresh all decorations when assignments change
        refreshDecorations();
    }
    
    private void refreshDecorations() {
        // Request decorator refresh through the PlatformUI
        org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
            try {
                org.eclipse.ui.PlatformUI.getWorkbench()
                    .getDecoratorManager()
                    .update("com.ditrix.edt.mcp.server.tags.decorator");
            } catch (Exception e) {
                // Ignore - workbench may not be running
            }
        });
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
