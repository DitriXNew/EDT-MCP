/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.ditrix.edt.mcp.server.groups.model.Group;

/**
 * Dialog for creating or editing a group.
 * Supports editing name and multiline description.
 */
public class EditGroupDialog extends Dialog {
    
    private final String dialogTitle;
    private final String initialName;
    private final String initialDescription;
    private final IGroupNameValidator nameValidator;
    
    private Text nameText;
    private Text descriptionText;
    
    private String groupName;
    private String groupDescription;
    
    /**
     * Interface for validating group names.
     */
    public interface IGroupNameValidator {
        /**
         * Validates a group name.
         * 
         * @param name the name to validate
         * @return error message if invalid, null if valid
         */
        String validate(String name);
    }
    
    /**
     * Creates a dialog for creating a new group.
     * 
     * @param parentShell the parent shell
     * @param nameValidator validator for group name
     */
    public EditGroupDialog(Shell parentShell, IGroupNameValidator nameValidator) {
        this(parentShell, "New Group", "", "", nameValidator);
    }
    
    /**
     * Creates a dialog for editing an existing group.
     * 
     * @param parentShell the parent shell
     * @param group the group to edit
     * @param nameValidator validator for group name
     */
    public EditGroupDialog(Shell parentShell, Group group, IGroupNameValidator nameValidator) {
        this(parentShell, "Edit Group", group.getName(), 
            group.getDescription() != null ? group.getDescription() : "", nameValidator);
    }
    
    /**
     * Creates a dialog with specified parameters.
     * 
     * @param parentShell the parent shell
     * @param title the dialog title
     * @param name initial name value
     * @param description initial description value
     * @param nameValidator validator for group name
     */
    public EditGroupDialog(Shell parentShell, String title, String name, String description, 
            IGroupNameValidator nameValidator) {
        super(parentShell);
        this.dialogTitle = title;
        this.initialName = name;
        this.initialDescription = description;
        this.nameValidator = nameValidator;
        this.groupName = name;
        this.groupDescription = description;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }
    
    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText(dialogTitle);
        newShell.setMinimumSize(400, 250);
    }
    
    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        GridLayoutFactory.fillDefaults().margins(10, 10).numColumns(2).applyTo(container);
        
        // Name field
        Label nameLabel = new Label(container, SWT.NONE);
        nameLabel.setText("Name:");
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.CENTER).applyTo(nameLabel);
        
        nameText = new Text(container, SWT.BORDER);
        nameText.setText(initialName);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(nameText);
        nameText.addModifyListener(new ModifyListener() {
            @Override
            public void modifyText(ModifyEvent e) {
                validateInput();
            }
        });
        
        // Description field (multiline)
        Label descLabel = new Label(container, SWT.NONE);
        descLabel.setText("Description:");
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.TOP).applyTo(descLabel);
        
        descriptionText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP);
        descriptionText.setText(initialDescription);
        GridDataFactory.fillDefaults().grab(true, true).hint(300, 80).applyTo(descriptionText);
        
        return container;
    }
    
    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        
        // Initial validation
        validateInput();
    }
    
    /**
     * Validates the input and enables/disables OK button.
     */
    private void validateInput() {
        String name = nameText.getText().trim();
        String error = null;
        
        if (name.isEmpty()) {
            error = "Name cannot be empty";
        } else if (nameValidator != null) {
            error = nameValidator.validate(name);
        }
        
        getButton(IDialogConstants.OK_ID).setEnabled(error == null);
    }
    
    @Override
    protected void okPressed() {
        groupName = nameText.getText().trim();
        groupDescription = descriptionText.getText().trim();
        
        if (groupName.isEmpty()) {
            return;
        }
        
        super.okPressed();
    }
    
    /**
     * Gets the entered group name.
     * 
     * @return the group name
     */
    public String getGroupName() {
        return groupName;
    }
    
    /**
     * Gets the entered group description.
     * 
     * @return the group description
     */
    public String getGroupDescription() {
        return groupDescription;
    }
}
