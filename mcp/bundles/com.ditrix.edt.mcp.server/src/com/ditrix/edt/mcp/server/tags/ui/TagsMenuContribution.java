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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.Parameterization;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.keys.KeySequence;
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
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.keys.IBindingService;

import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.TagUtils;
import com.ditrix.edt.mcp.server.tags.handlers.ToggleTagByIndexHandler;
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Dynamic menu contribution that shows tags with checkboxes.
 * Allows assigning/unassigning tags directly from the context menu.
 * 
 * <p>Supports multiselection: when multiple objects are selected,
 * shows union of all tags from all projects. The checkbox state is:
 * - Checked: all selected objects have this tag
 * - Unchecked: no selected objects have this tag  
 * - Grayed (indeterminate): some objects have this tag
 * Note: SWT.CHECK doesn't support grayed state, so we just toggle.</p>
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
        
        if (!(selection instanceof IStructuredSelection structuredSelection) || selection.isEmpty()) {
            return new IContributionItem[0];
        }
        
        // Collect all selected objects grouped by project
        Map<IProject, List<String>> objectsByProject = new HashMap<>();
        for (Iterator<?> it = structuredSelection.iterator(); it.hasNext();) {
            Object element = it.next();
            EObject eObject = TagUtils.extractMdObject(element);
            if (eObject == null) {
                continue;
            }
            
            IProject project = TagUtils.extractProject(eObject);
            String fqn = TagUtils.extractFqn(eObject);
            
            if (project != null && fqn != null) {
                objectsByProject.computeIfAbsent(project, k -> new ArrayList<>()).add(fqn);
            }
        }
        
        if (objectsByProject.isEmpty()) {
            return new IContributionItem[0];
        }
        
        // Collect all unique tags from all projects and their assignment state
        // Also track the minimum index for each tag across all projects
        Map<String, TagState> tagStates = new HashMap<>();
        
        for (Map.Entry<IProject, List<String>> entry : objectsByProject.entrySet()) {
            IProject project = entry.getKey();
            List<String> fqns = entry.getValue();
            
            List<Tag> projectTags = tagService.getTags(project);
            
            for (int tagIndex = 0; tagIndex < projectTags.size(); tagIndex++) {
                Tag tag = projectTags.get(tagIndex);
                String tagName = tag.getName();
                TagState state = tagStates.computeIfAbsent(tagName, 
                    k -> new TagState(tag.getColor()));
                
                // Track the minimum storage index (for hotkey display)
                // Use 1-based index (1-10), convert 10 to 0 for display
                int hotkeyIndex = tagIndex + 1;
                if (hotkeyIndex <= 10 && (state.hotkeyIndex == -1 || hotkeyIndex < state.hotkeyIndex)) {
                    state.hotkeyIndex = hotkeyIndex;
                }
                
                for (String fqn : fqns) {
                    Set<Tag> assignedTags = tagService.getObjectTags(project, fqn);
                    boolean hasTag = assignedTags.stream()
                        .anyMatch(t -> t.getName().equals(tagName));
                    if (hasTag) {
                        state.assignedCount++;
                    }
                    state.totalCount++;
                }
            }
        }
        
        if (tagStates.isEmpty()) {
            return new IContributionItem[0];
        }
        
        // Sort tags by name
        List<String> sortedTagNames = new ArrayList<>(tagStates.keySet());
        sortedTagNames.sort(String::compareToIgnoreCase);
        
        // Create menu items for each tag
        IContributionItem[] items = new IContributionItem[sortedTagNames.size() + 1];
        
        for (int i = 0; i < sortedTagNames.size(); i++) {
            String tagName = sortedTagNames.get(i);
            TagState state = tagStates.get(tagName);
            // Consider "checked" if all objects have this tag
            boolean isChecked = state.assignedCount == state.totalCount && state.assignedCount > 0;
            items[i] = new MultiTagMenuItem(tagName, state.color, objectsByProject, 
                isChecked, state.hotkeyIndex);
        }
        
        // Add separator before "Manage Tags..."
        items[sortedTagNames.size()] = new SeparatorItem();
        
        return items;
    }
    
    /**
     * State for tracking tag assignment across multiple objects.
     */
    private static class TagState {
        final String color;
        int assignedCount = 0;  // How many objects have this tag
        int totalCount = 0;     // Total objects in projects that have this tag
        int hotkeyIndex = -1;   // Storage index (1-10), -1 if not in first 10
        
        TagState(String color) {
            this.color = color;
        }
    }
    
    /**
     * Menu item for a tag that handles multiple selected objects from multiple projects.
     * Toggles the tag for all objects that have the tag defined in their project.
     */
    private class MultiTagMenuItem extends ContributionItem {
        private final String tagName;
        private final String color;
        private final Map<IProject, List<String>> objectsByProject;
        private final boolean isChecked;
        private final int hotkeyIndex;
        
        public MultiTagMenuItem(String tagName, String color, 
                Map<IProject, List<String>> objectsByProject, boolean isChecked, int hotkeyIndex) {
            this.tagName = tagName;
            this.color = color;
            this.objectsByProject = new HashMap<>(objectsByProject);
            this.isChecked = isChecked;
            this.hotkeyIndex = hotkeyIndex;
        }
        
        @Override
        public void fill(Menu menu, int index) {
            MenuItem item = new MenuItem(menu, SWT.CHECK, index);
            
            // Build text with optional hotkey
            String text = tagName;
            if (hotkeyIndex >= 1 && hotkeyIndex <= 10) {
                String hotkey = getHotkeyString(hotkeyIndex);
                if (hotkey != null && !hotkey.isEmpty()) {
                    text = tagName + "\t" + hotkey;
                }
            }
            item.setText(text);
            item.setSelection(isChecked);
            
            // Create image and dispose it when menu item is disposed
            Image image = TagColorIconFactory.getCircularColorIconWithCheck(
                color, 16, isChecked).createImage();
            item.setImage(image);
            item.addDisposeListener(e -> image.dispose());
            
            item.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    // Capture selection value before asyncExec since menu item may be disposed
                    boolean selected = item.getSelection();
                    Display.getCurrent().asyncExec(() -> {
                        // Apply to all objects in all projects
                        for (Map.Entry<IProject, List<String>> entry : objectsByProject.entrySet()) {
                            IProject project = entry.getKey();
                            List<String> fqns = entry.getValue();
                            
                            // Check if this project has this tag
                            Tag tag = tagService.getTagStorage(project).getTagByName(tagName);
                            if (tag == null) {
                                // This project doesn't have this tag, skip
                                continue;
                            }
                            
                            for (String fqn : fqns) {
                                if (selected) {
                                    tagService.assignTag(project, fqn, tagName);
                                } else {
                                    tagService.unassignTag(project, fqn, tagName);
                                }
                            }
                        }
                    });
                }
            });
        }
    }
    
    /**
     * Menu item for a tag with checkbox and colored icon.
     * Uses ResourceManager for proper image lifecycle management.
     * @deprecated Use {@link MultiTagMenuItem} instead
     */
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
    
    /**
     * Gets the hotkey string for the toggleTag command with the given index.
     * Retrieves the actual keybinding from Eclipse's IBindingService.
     * 
     * @param hotkeyIndex the tag index (1-10, where 10 is displayed as 0)
     * @return the formatted key sequence (e.g., "Ctrl+Alt+1"), or null if no binding
     */
    private String getHotkeyString(int hotkeyIndex) {
        try {
            IBindingService bindingService = PlatformUI.getWorkbench().getService(IBindingService.class);
            if (bindingService == null) {
                return null;
            }
            
            // Convert index 10 to 0 (as used in keybindings)
            String indexParam = String.valueOf(hotkeyIndex == 10 ? 0 : hotkeyIndex);
            
            // Create parameterized command
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService == null) {
                return null;
            }
            
            var command = commandService.getCommand(ToggleTagByIndexHandler.COMMAND_ID);
            if (command == null || !command.isDefined()) {
                return null;
            }
            
            Parameterization parameterization = new Parameterization(
                command.getParameter(ToggleTagByIndexHandler.PARAM_TAG_INDEX), indexParam);
            ParameterizedCommand paramCommand = new ParameterizedCommand(
                command, new Parameterization[] { parameterization });
            
            // Get active bindings for this command
            Binding[] bindings = bindingService.getBindings();
            for (Binding binding : bindings) {
                ParameterizedCommand boundCommand = binding.getParameterizedCommand();
                if (boundCommand != null && boundCommand.equals(paramCommand)) {
                    KeySequence keySequence = binding.getTriggerSequence() instanceof KeySequence ? 
                        (KeySequence) binding.getTriggerSequence() : null;
                    if (keySequence != null) {
                        return keySequence.format();
                    }
                }
            }
            
            return null;
        } catch (NotDefinedException e) {
            return null;
        }
    }
}
