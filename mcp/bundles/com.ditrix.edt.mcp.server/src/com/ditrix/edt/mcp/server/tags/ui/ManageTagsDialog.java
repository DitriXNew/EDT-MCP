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
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;

import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Dialog for managing tags on a metadata object.
 * Allows viewing, assigning, unassigning, and creating new tags.
 */
public class ManageTagsDialog extends Dialog {
    
    private final IProject project;
    private final String objectFqn;
    private final TagService tagService;
    
    private CheckboxTableViewer tagsViewer;
    private Text newTagNameText;
    private Button newTagColorButton;
    private String newTagColor = "#3399FF";
    private ResourceManager resourceManager;
    
    private List<Tag> allTags;
    private Set<Tag> assignedTags;
    
    /**
     * Creates a new dialog.
     * 
     * @param parentShell the parent shell
     * @param project the project
     * @param objectFqn the FQN of the metadata object
     */
    public ManageTagsDialog(Shell parentShell, IProject project, String objectFqn) {
        super(parentShell);
        this.project = project;
        this.objectFqn = objectFqn;
        this.tagService = TagService.getInstance();
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }
    
    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("Manage Tags - " + objectFqn);
        newShell.setMinimumSize(400, 450);
    }
    
    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        GridLayoutFactory.fillDefaults().margins(10, 10).applyTo(container);
        
        // Create resource manager tied to container lifecycle
        resourceManager = new LocalResourceManager(TagColorIconFactory.getJFaceResources(), container);
        
        // Object info
        Label objectLabel = new Label(container, SWT.NONE);
        objectLabel.setText("Object: " + objectFqn);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(objectLabel);
        
        // Tags table
        createTagsTable(container);
        
        // New tag section
        createNewTagSection(container);
        
        // Load data
        refreshTags();
        
        return container;
    }
    
    private void createTagsTable(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Available Tags");
        GridDataFactory.fillDefaults().grab(true, true).applyTo(group);
        GridLayoutFactory.fillDefaults().margins(5, 5).numColumns(2).applyTo(group);
        
        // Table with checkboxes and full row selection
        Table table = new Table(group, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(300, 200).applyTo(table);
        
        tagsViewer = new CheckboxTableViewer(table);
        tagsViewer.setContentProvider(ArrayContentProvider.getInstance());
        
        // Color column
        TableViewerColumn colorColumn = new TableViewerColumn(tagsViewer, SWT.NONE);
        colorColumn.getColumn().setText("");
        colorColumn.getColumn().setWidth(30);
        colorColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public Image getImage(Object element) {
                if (element instanceof Tag tag) {
                    return resourceManager.get(
                        TagColorIconFactory.getColorIcon(tag.getColor()));
                }
                return null;
            }
            
            @Override
            public String getText(Object element) {
                return "";
            }
        });
        
        // Hotkey column (shows Ctrl+Alt+N for first 10 tags)
        TableViewerColumn hotkeyColumn = new TableViewerColumn(tagsViewer, SWT.CENTER);
        hotkeyColumn.getColumn().setText("#");
        hotkeyColumn.getColumn().setWidth(30);
        hotkeyColumn.getColumn().setToolTipText("Hotkey index (Ctrl+Alt+N)");
        hotkeyColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Tag tag) {
                    int index = tagService.getTagHotkeyIndex(project, tag.getName());
                    if (index >= 0) {
                        return String.valueOf(index);
                    }
                }
                return "";
            }
        });
        
        // Name column (wider)
        TableViewerColumn nameColumn = new TableViewerColumn(tagsViewer, SWT.NONE);
        nameColumn.getColumn().setText("Tag");
        nameColumn.getColumn().setWidth(170);
        nameColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Tag tag) {
                    return tag.getName();
                }
                return "";
            }
        });
        
        // Description column
        TableViewerColumn descColumn = new TableViewerColumn(tagsViewer, SWT.NONE);
        descColumn.getColumn().setText("Description");
        descColumn.getColumn().setWidth(200);
        descColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Tag tag) {
                    return tag.getDescription();
                }
                return "";
            }
        });
        
        // Buttons on the right
        Composite buttonsComposite = new Composite(group, SWT.NONE);
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.TOP).applyTo(buttonsComposite);
        GridLayoutFactory.fillDefaults().applyTo(buttonsComposite);
        
        Button moveUpButton = new Button(buttonsComposite, SWT.PUSH);
        moveUpButton.setText("Move Up");
        moveUpButton.setToolTipText("Move tag up (affects Ctrl+Alt+1-0 hotkeys)");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(moveUpButton);
        moveUpButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                moveSelectedTagUp();
            }
        });
        
        Button moveDownButton = new Button(buttonsComposite, SWT.PUSH);
        moveDownButton.setText("Move Down");
        moveDownButton.setToolTipText("Move tag down (affects Ctrl+Alt+1-0 hotkeys)");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(moveDownButton);
        moveDownButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                moveSelectedTagDown();
            }
        });
        
        // Separator
        Label separator = new Label(buttonsComposite, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(separator);
        
        Button editButton = new Button(buttonsComposite, SWT.PUSH);
        editButton.setText("Edit...");
        editButton.setToolTipText("Edit selected tag");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(editButton);
        editButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                editSelectedTag();
            }
        });
        
        Button deleteButton = new Button(buttonsComposite, SWT.PUSH);
        deleteButton.setText("Delete");
        deleteButton.setToolTipText("Delete selected tag");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(deleteButton);
        deleteButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                deleteSelectedTag();
            }
        });
    }
    
    private void createNewTagSection(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Create New Tag");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(group);
        GridLayoutFactory.fillDefaults().margins(5, 5).numColumns(4).applyTo(group);
        
        // Name
        Label nameLabel = new Label(group, SWT.NONE);
        nameLabel.setText("Name:");
        
        newTagNameText = new Text(group, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(newTagNameText);
        
        // Color button
        newTagColorButton = new Button(group, SWT.PUSH);
        newTagColorButton.setToolTipText("Select color");
        updateColorButton();
        newTagColorButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                ColorDialog colorDialog = new ColorDialog(getShell());
                colorDialog.setRGB(hexToRgb(newTagColor));
                RGB rgb = colorDialog.open();
                if (rgb != null) {
                    newTagColor = rgbToHex(rgb);
                    updateColorButton();
                }
            }
        });
        
        // Add button
        Button addButton = new Button(group, SWT.PUSH);
        addButton.setText("Add");
        addButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                createNewTag();
            }
        });
    }
    
    private void refreshTags() {
        allTags = new ArrayList<>(tagService.getTags(project));
        assignedTags = tagService.getObjectTags(project, objectFqn);
        
        tagsViewer.setInput(allTags);
        
        // Check assigned tags
        for (Tag tag : allTags) {
            tagsViewer.setChecked(tag, assignedTags.contains(tag));
        }
    }
    
    private void editSelectedTag() {
        Object selection = tagsViewer.getStructuredSelection().getFirstElement();
        if (selection instanceof Tag tag) {
            EditTagDialog dialog = new EditTagDialog(getShell(), tag);
            if (dialog.open() == Dialog.OK) {
                tagService.updateTag(project, tag.getName(), 
                    dialog.getTagName(), dialog.getTagColor(), dialog.getTagDescription());
                refreshTags();
            }
        }
    }
    
    private void deleteSelectedTag() {
        Object selection = tagsViewer.getStructuredSelection().getFirstElement();
        if (selection instanceof Tag tag) {
            tagService.deleteTag(project, tag.getName());
            refreshTags();
        }
    }
    
    private void moveSelectedTagUp() {
        Object selection = tagsViewer.getStructuredSelection().getFirstElement();
        if (selection instanceof Tag tag) {
            if (tagService.moveTagUp(project, tag.getName())) {
                refreshTags();
                // Re-select the moved tag
                tagsViewer.setSelection(new org.eclipse.jface.viewers.StructuredSelection(tag));
            }
        }
    }
    
    private void moveSelectedTagDown() {
        Object selection = tagsViewer.getStructuredSelection().getFirstElement();
        if (selection instanceof Tag tag) {
            if (tagService.moveTagDown(project, tag.getName())) {
                refreshTags();
                // Re-select the moved tag
                tagsViewer.setSelection(new org.eclipse.jface.viewers.StructuredSelection(tag));
            }
        }
    }
    
    private void createNewTag() {
        String name = newTagNameText.getText().trim();
        if (name.isEmpty()) {
            return;
        }
        
        Tag newTag = tagService.createTag(project, name, newTagColor, "");
        if (newTag != null) {
            newTagNameText.setText("");
            refreshTags();
        }
    }
    
    @Override
    protected void okPressed() {
        // Apply tag assignments based on checkboxes
        for (Tag tag : allTags) {
            boolean isChecked = tagsViewer.getChecked(tag);
            boolean wasAssigned = assignedTags.contains(tag);
            
            if (isChecked && !wasAssigned) {
                tagService.assignTag(project, objectFqn, tag.getName());
            } else if (!isChecked && wasAssigned) {
                tagService.unassignTag(project, objectFqn, tag.getName());
            }
        }
        
        super.okPressed();
    }
    
    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Apply", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }
    
    private void updateColorButton() {
        newTagColorButton.setImage(resourceManager.get(
            TagColorIconFactory.getColorIcon(newTagColor)));
    }
    
    @Override
    public boolean close() {
        // ResourceManager is tied to container lifecycle, no explicit disposal needed
        return super.close();
    }
    
    private RGB hexToRgb(String hex) {
        return TagColorIconFactory.hexToRgb(hex);
    }
    
    private String rgbToHex(RGB rgb) {
        return TagColorIconFactory.rgbToHex(rgb);
    }
}
