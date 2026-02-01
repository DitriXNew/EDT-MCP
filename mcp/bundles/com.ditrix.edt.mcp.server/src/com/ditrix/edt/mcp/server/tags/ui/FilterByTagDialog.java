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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.SelectionDialog;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Dialog for filtering navigator by tags.
 * Shows a tree of projects with their tags as checkboxes.
 * Similar to EDT's "Filter by Subsystems" dialog.
 */
public class FilterByTagDialog extends SelectionDialog {
    
    private static final int TURN_OFF_ID = 1024;
    private static final int COLOR_ICON_SIZE = 12;
    
    private CheckboxTreeViewer treeViewer;
    private final TagService tagService;
    private final IV8ProjectManager v8ProjectManager;
    
    // Selected tags per project
    private Map<IProject, Set<Tag>> selectedTags = new HashMap<>();
    
    // Result flags
    private boolean isTurnedOn = false;
    private boolean isTurnedOff = false;
    
    // Color icons cache
    private List<Image> colorIcons = new ArrayList<>();
    
    /**
     * Creates a new filter dialog.
     * 
     * @param parentShell the parent shell
     * @param v8ProjectManager the V8 project manager
     */
    public FilterByTagDialog(Shell parentShell, IV8ProjectManager v8ProjectManager) {
        super(parentShell);
        this.tagService = TagService.getInstance();
        this.v8ProjectManager = v8ProjectManager;
        setTitle(Messages.FilterByTagDialog_Title);
    }
    
    /**
     * Returns whether filter was turned on.
     */
    public boolean isTurnedOn() {
        return isTurnedOn;
    }
    
    /**
     * Returns whether filter was turned off.
     */
    public boolean isTurnedOff() {
        return isTurnedOff;
    }
    
    /**
     * Returns the selected tags per project.
     */
    public Map<IProject, Set<Tag>> getSelectedTags() {
        return selectedTags;
    }
    
    /**
     * Sets the initial selection (currently selected tags).
     */
    public void setInitialSelection(Map<IProject, Set<Tag>> initialSelection) {
        if (initialSelection != null) {
            this.selectedTags = new HashMap<>(initialSelection);
        }
    }
    
    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == TURN_OFF_ID) {
            isTurnedOff = true;
            turnOffPressed();
        } else if (buttonId == IDialogConstants.OK_ID) {
            isTurnedOn = true;
            okPressed();
        } else {
            cancelPressed();
        }
    }
    
    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, Messages.FilterByTagDialog_SetButton, true);
        createButton(parent, TURN_OFF_ID, Messages.FilterByTagDialog_TurnOffButton, false);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }
    
    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        GridLayoutFactory.fillDefaults().margins(10, 10).applyTo(container);
        
        // Info label
        Label infoLabel = new Label(container, SWT.WRAP);
        infoLabel.setText(Messages.FilterByTagDialog_Description);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(infoLabel);
        
        // Tree viewer with checkboxes
        treeViewer = new CheckboxTreeViewer(container, SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        GridDataFactory.fillDefaults().grab(true, true).hint(SWT.DEFAULT, 300).applyTo(treeViewer.getTree());
        
        treeViewer.setContentProvider(new TagTreeContentProvider());
        treeViewer.setLabelProvider(new TagTreeLabelProvider());
        treeViewer.setInput(ResourcesPlugin.getWorkspace().getRoot());
        
        // Set initial checked state
        applyInitialSelection();
        
        // Handle check state changes
        treeViewer.addCheckStateListener(new ICheckStateListener() {
            @Override
            public void checkStateChanged(CheckStateChangedEvent event) {
                handleCheckStateChange(event);
            }
        });
        
        // Expand all
        treeViewer.expandAll();
        
        // Select All / Deselect All buttons
        Composite buttonBar = new Composite(container, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(buttonBar);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(buttonBar);
        
        Button selectAllButton = new Button(buttonBar, SWT.PUSH);
        selectAllButton.setText(Messages.FilterByTagDialog_SelectAll);
        selectAllButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                selectAll(true);
            }
        });
        
        Button deselectAllButton = new Button(buttonBar, SWT.PUSH);
        deselectAllButton.setText(Messages.FilterByTagDialog_DeselectAll);
        deselectAllButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                selectAll(false);
            }
        });
        
        return container;
    }
    
    @Override
    protected Point getInitialSize() {
        return new Point(400, 500);
    }
    
    @Override
    protected void okPressed() {
        // Collect selected tags
        collectSelectedTags();
        super.okPressed();
    }
    
    protected void turnOffPressed() {
        selectedTags.clear();
        super.okPressed();
    }
    
    @Override
    public boolean close() {
        // Dispose color icons
        for (Image img : colorIcons) {
            if (img != null && !img.isDisposed()) {
                img.dispose();
            }
        }
        colorIcons.clear();
        return super.close();
    }
    
    private void applyInitialSelection() {
        for (Map.Entry<IProject, Set<Tag>> entry : selectedTags.entrySet()) {
            for (Tag tag : entry.getValue()) {
                TagEntry tagEntry = new TagEntry(entry.getKey(), tag);
                treeViewer.setChecked(tagEntry, true);
            }
            // Update project grayed state
            updateProjectCheckState(entry.getKey());
        }
    }
    
    private void handleCheckStateChange(CheckStateChangedEvent event) {
        Object element = event.getElement();
        boolean checked = event.getChecked();
        
        if (element instanceof IProject project) {
            // Check/uncheck all tags in this project
            List<Tag> tags = tagService.getTags(project);
            for (Tag tag : tags) {
                TagEntry tagEntry = new TagEntry(project, tag);
                treeViewer.setChecked(tagEntry, checked);
            }
            treeViewer.setGrayed(project, false);
        } else if (element instanceof TagEntry tagEntry) {
            // Update parent project grayed state
            updateProjectCheckState(tagEntry.project);
        }
    }
    
    private void updateProjectCheckState(IProject project) {
        List<Tag> tags = tagService.getTags(project);
        if (tags.isEmpty()) {
            treeViewer.setChecked(project, false);
            treeViewer.setGrayed(project, false);
            return;
        }
        
        int checkedCount = 0;
        for (Tag tag : tags) {
            TagEntry tagEntry = new TagEntry(project, tag);
            if (treeViewer.getChecked(tagEntry)) {
                checkedCount++;
            }
        }
        
        if (checkedCount == 0) {
            treeViewer.setChecked(project, false);
            treeViewer.setGrayed(project, false);
        } else if (checkedCount == tags.size()) {
            treeViewer.setChecked(project, true);
            treeViewer.setGrayed(project, false);
        } else {
            treeViewer.setChecked(project, true);
            treeViewer.setGrayed(project, true);
        }
    }
    
    private void selectAll(boolean select) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        for (IProject project : root.getProjects()) {
            if (!project.isOpen()) continue;
            IV8Project v8Project = v8ProjectManager.getProject(project);
            if (v8Project == null) continue;
            
            treeViewer.setChecked(project, select);
            treeViewer.setGrayed(project, false);
            
            List<Tag> tags = tagService.getTags(project);
            for (Tag tag : tags) {
                TagEntry tagEntry = new TagEntry(project, tag);
                treeViewer.setChecked(tagEntry, select);
            }
        }
    }
    
    private void collectSelectedTags() {
        selectedTags.clear();
        
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        for (IProject project : root.getProjects()) {
            if (!project.isOpen()) continue;
            IV8Project v8Project = v8ProjectManager.getProject(project);
            if (v8Project == null) continue;
            
            List<Tag> tags = tagService.getTags(project);
            Set<Tag> selected = new HashSet<>();
            
            for (Tag tag : tags) {
                TagEntry tagEntry = new TagEntry(project, tag);
                if (treeViewer.getChecked(tagEntry) && !treeViewer.getGrayed(tagEntry)) {
                    selected.add(tag);
                }
            }
            
            if (!selected.isEmpty()) {
                selectedTags.put(project, selected);
            }
        }
    }
    
    private Image createColorIcon(String hexColor) {
        Display display = Display.getCurrent();
        Image image = new Image(display, COLOR_ICON_SIZE, COLOR_ICON_SIZE);
        
        try {
            int r = Integer.parseInt(hexColor.substring(1, 3), 16);
            int g = Integer.parseInt(hexColor.substring(3, 5), 16);
            int b = Integer.parseInt(hexColor.substring(5, 7), 16);
            
            GC gc = new GC(image);
            Color color = new Color(display, new RGB(r, g, b));
            gc.setBackground(color);
            gc.fillRectangle(0, 0, COLOR_ICON_SIZE, COLOR_ICON_SIZE);
            gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_GRAY));
            gc.drawRectangle(0, 0, COLOR_ICON_SIZE - 1, COLOR_ICON_SIZE - 1);
            color.dispose();
            gc.dispose();
        } catch (Exception e) {
            // Invalid color, return gray
            GC gc = new GC(image);
            gc.setBackground(display.getSystemColor(SWT.COLOR_GRAY));
            gc.fillRectangle(0, 0, COLOR_ICON_SIZE, COLOR_ICON_SIZE);
            gc.dispose();
        }
        
        colorIcons.add(image);
        return image;
    }
    
    /**
     * Wrapper class for tag entries in the tree.
     */
    private static class TagEntry {
        final IProject project;
        final Tag tag;
        
        TagEntry(IProject project, Tag tag) {
            this.project = project;
            this.tag = tag;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof TagEntry other)) return false;
            return project.equals(other.project) && tag.getName().equals(other.tag.getName());
        }
        
        @Override
        public int hashCode() {
            return project.hashCode() * 31 + tag.getName().hashCode();
        }
    }
    
    /**
     * Content provider for the tag tree.
     */
    private class TagTreeContentProvider implements ITreeContentProvider {
        
        @Override
        public Object[] getElements(Object inputElement) {
            if (inputElement instanceof IWorkspaceRoot root) {
                List<IProject> projects = new ArrayList<>();
                for (IProject project : root.getProjects()) {
                    if (!project.isOpen()) continue;
                    IV8Project v8Project = v8ProjectManager.getProject(project);
                    if (v8Project == null) continue;
                    
                    // Only show projects that have tags
                    List<Tag> tags = tagService.getTags(project);
                    if (!tags.isEmpty()) {
                        projects.add(project);
                    }
                }
                return projects.toArray();
            }
            return new Object[0];
        }
        
        @Override
        public Object[] getChildren(Object parentElement) {
            if (parentElement instanceof IProject project) {
                List<Tag> tags = tagService.getTags(project);
                List<TagEntry> entries = new ArrayList<>();
                for (Tag tag : tags) {
                    entries.add(new TagEntry(project, tag));
                }
                return entries.toArray();
            }
            return new Object[0];
        }
        
        @Override
        public Object getParent(Object element) {
            if (element instanceof TagEntry tagEntry) {
                return tagEntry.project;
            }
            if (element instanceof IProject) {
                return ResourcesPlugin.getWorkspace().getRoot();
            }
            return null;
        }
        
        @Override
        public boolean hasChildren(Object element) {
            if (element instanceof IProject project) {
                return !tagService.getTags(project).isEmpty();
            }
            return false;
        }
    }
    
    /**
     * Label provider for the tag tree.
     */
    private class TagTreeLabelProvider extends LabelProvider {
        
        @Override
        public String getText(Object element) {
            if (element instanceof IProject project) {
                return project.getName();
            }
            if (element instanceof TagEntry tagEntry) {
                return tagEntry.tag.getName();
            }
            return super.getText(element);
        }
        
        @Override
        public Image getImage(Object element) {
            if (element instanceof TagEntry tagEntry) {
                return createColorIcon(tagEntry.tag.getColor());
            }
            return null;
        }
    }
}
