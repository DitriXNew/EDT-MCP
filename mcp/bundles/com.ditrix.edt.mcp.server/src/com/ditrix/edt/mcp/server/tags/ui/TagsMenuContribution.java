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

import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;

import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Dynamic menu contribution that shows tags with checkboxes.
 * Allows assigning/unassigning tags directly from the context menu.
 */
public class TagsMenuContribution extends CompoundContributionItem {
    
    private TagService tagService;
    
    public TagsMenuContribution() {
        this.tagService = TagService.getInstance();
    }
    
    public TagsMenuContribution(String id) {
        super(id);
        this.tagService = TagService.getInstance();
    }
    
    @Override
    protected IContributionItem[] getContributionItems() {
        // Get current selection
        ISelectionService selService = PlatformUI.getWorkbench()
            .getActiveWorkbenchWindow().getSelectionService();
        ISelection selection = selService.getSelection();
        
        if (!(selection instanceof IStructuredSelection) || selection.isEmpty()) {
            return new IContributionItem[0];
        }
        
        IStructuredSelection structuredSelection = (IStructuredSelection) selection;
        Object firstElement = structuredSelection.getFirstElement();
        
        if (!(firstElement instanceof EObject)) {
            return new IContributionItem[0];
        }
        
        EObject eObject = (EObject) firstElement;
        IProject project = extractProject(eObject);
        String fqn = extractFqn(eObject);
        
        if (project == null || fqn == null) {
            return new IContributionItem[0];
        }
        
        List<Tag> allTags = tagService.getTags(project);
        Set<Tag> assignedTags = tagService.getObjectTags(project, fqn);
        
        if (allTags.isEmpty()) {
            return new IContributionItem[0];
        }
        
        // Create menu items for each tag
        IContributionItem[] items = new IContributionItem[allTags.size() + 1];
        
        for (int i = 0; i < allTags.size(); i++) {
            Tag tag = allTags.get(i);
            boolean isChecked = assignedTags.stream()
                .anyMatch(t -> t.getName().equals(tag.getName()));
            items[i] = new TagMenuItem(tag, project, fqn, isChecked);
        }
        
        // Add separator before "Manage Tags..."
        items[allTags.size()] = new SeparatorItem();
        
        return items;
    }
    
    private IProject extractProject(EObject eobj) {
        if (eobj.eResource() != null && eobj.eResource().getURI() != null) {
            org.eclipse.emf.common.util.URI uri = eobj.eResource().getURI();
            
            String uriPath = uri.toPlatformString(true);
            if (uriPath != null && uriPath.length() > 1) {
                String projectName = uriPath.split("/")[1];
                return org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                    .getRoot().getProject(projectName);
            }
            
            String uriString = uri.toString();
            if (uriString.startsWith("bm://")) {
                String[] parts = uriString.substring(5).split("/");
                if (parts.length > 0) {
                    return org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                        .getRoot().getProject(parts[0]);
                }
            }
        }
        return null;
    }
    
    private String extractFqn(EObject mdObject) {
        if (mdObject == null) {
            return null;
        }
        
        try {
            StringBuilder fqnBuilder = new StringBuilder();
            EObject current = mdObject;
            
            while (current != null) {
                String typeName = current.eClass().getName();
                
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
    
    /**
     * Menu item for a tag with checkbox and colored icon.
     */
    private class TagMenuItem extends ContributionItem {
        private final Tag tag;
        private final IProject project;
        private final String fqn;
        private final boolean isChecked;
        
        public TagMenuItem(Tag tag, IProject project, String fqn, boolean isChecked) {
            this.tag = tag;
            this.project = project;
            this.fqn = fqn;
            this.isChecked = isChecked;
        }
        
        @Override
        public void fill(Menu menu, int index) {
            MenuItem item = new MenuItem(menu, SWT.CHECK, index);
            item.setText(tag.getName());
            item.setSelection(isChecked);
            item.setImage(createColorIcon(tag.getColor(), isChecked));
            
            item.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    if (item.getSelection()) {
                        // Assign tag
                        tagService.assignTag(project, fqn, tag.getName());
                    } else {
                        // Unassign tag
                        tagService.unassignTag(project, fqn, tag.getName());
                    }
                }
            });
        }
        
        private Image createColorIcon(String hex, boolean checked) {
            if (hex == null || hex.isEmpty()) {
                hex = "#808080"; // Default gray
            }
            
            int size = 16;
            Image image = new Image(Display.getCurrent(), size, size);
            GC gc = new GC(image);
            
            try {
                RGB rgb = hexToRgb(hex);
                Color color = new Color(Display.getCurrent(), rgb);
                
                // Fill background with color
                gc.setBackground(color);
                gc.fillOval(2, 2, size - 4, size - 4);
                
                // Draw border
                gc.setForeground(Display.getCurrent().getSystemColor(SWT.COLOR_DARK_GRAY));
                gc.drawOval(2, 2, size - 4, size - 4);
                
                // Draw checkmark if checked
                if (checked) {
                    gc.setForeground(Display.getCurrent().getSystemColor(SWT.COLOR_WHITE));
                    gc.setLineWidth(2);
                    // Draw checkmark inside circle
                    gc.drawLine(4, 8, 7, 11);
                    gc.drawLine(7, 11, 12, 5);
                }
                
                color.dispose();
            } finally {
                gc.dispose();
            }
            
            return image;
        }
        
        private RGB hexToRgb(String hex) {
            hex = hex.replace("#", "");
            try {
                int r = Integer.parseInt(hex.substring(0, 2), 16);
                int g = Integer.parseInt(hex.substring(2, 4), 16);
                int b = Integer.parseInt(hex.substring(4, 6), 16);
                return new RGB(r, g, b);
            } catch (Exception e) {
                return new RGB(128, 128, 128);
            }
        }
    }
    
    /**
     * Separator menu item.
     */
    private static class SeparatorItem extends ContributionItem {
        @Override
        public void fill(Menu menu, int index) {
            new MenuItem(menu, SWT.SEPARATOR, index);
        }
        
        @Override
        public boolean isSeparator() {
            return true;
        }
    }
}
