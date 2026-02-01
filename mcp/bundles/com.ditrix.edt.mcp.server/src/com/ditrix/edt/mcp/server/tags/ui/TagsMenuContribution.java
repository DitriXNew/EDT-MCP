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
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;

import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.TagUtils;
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Dynamic menu contribution that shows tags with checkboxes.
 * Allows assigning/unassigning tags directly from the context menu.
 * 
 * <p>Uses {@link TagColorIconFactory} for proper image resource management
 * and {@link TagUtils} for FQN extraction.</p>
 */
public class TagsMenuContribution extends CompoundContributionItem {
    
    private final TagService tagService;
    
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
        
        EObject eObject = TagUtils.extractMdObject(firstElement);
        if (eObject == null) {
            return new IContributionItem[0];
        }
        
        IProject project = TagUtils.extractProject(eObject);
        String fqn = TagUtils.extractFqn(eObject);
        
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
    
    /**
     * Menu item for a tag with checkbox and colored icon.
     * Uses ResourceManager for proper image lifecycle management.
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
            
            // Create image and dispose it when menu item is disposed
            Image image = TagColorIconFactory.getCircularColorIconWithCheck(
                tag.getColor(), 16, isChecked).createImage();
            item.setImage(image);
            item.addDisposeListener(e -> image.dispose());
            
            item.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    Display.getCurrent().asyncExec(() -> {
                        if (item.getSelection()) {
                            tagService.assignTag(project, fqn, tag.getName());
                        } else {
                            tagService.unassignTag(project, fqn, tag.getName());
                        }
                    });
                }
            });
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
