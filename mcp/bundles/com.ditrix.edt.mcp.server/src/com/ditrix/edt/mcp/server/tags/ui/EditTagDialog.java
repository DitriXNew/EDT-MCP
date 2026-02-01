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

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Dialog for editing an existing tag.
 */
public class EditTagDialog extends Dialog {
    
    private final Tag tag;
    
    private Text nameText;
    private Text descriptionText;
    private Button colorButton;
    private ResourceManager resourceManager;
    
    private String tagName;
    private String tagColor;
    private String tagDescription;
    
    /**
     * Creates a new dialog.
     * 
     * @param parentShell the parent shell
     * @param tag the tag to edit
     */
    public EditTagDialog(Shell parentShell, Tag tag) {
        super(parentShell);
        this.tag = tag;
        this.tagName = tag.getName();
        this.tagColor = tag.getColor();
        this.tagDescription = tag.getDescription();
    }
    
    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("Edit Tag");
    }
    
    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        GridLayoutFactory.fillDefaults().margins(10, 10).numColumns(3).applyTo(container);
        
        // Create resource manager tied to container lifecycle
        resourceManager = new LocalResourceManager(TagColorIconFactory.getJFaceResources(), container);
        
        // Name
        Label nameLabel = new Label(container, SWT.NONE);
        nameLabel.setText("Name:");
        
        nameText = new Text(container, SWT.BORDER);
        nameText.setText(tag.getName());
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).applyTo(nameText);
        
        // Color
        Label colorLabel = new Label(container, SWT.NONE);
        colorLabel.setText("Color:");
        
        colorButton = new Button(container, SWT.PUSH);
        colorButton.setToolTipText("Select color");
        updateColorButton();
        GridDataFactory.fillDefaults().span(2, 1).applyTo(colorButton);
        colorButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                ColorDialog colorDialog = new ColorDialog(getShell());
                colorDialog.setRGB(hexToRgb(tagColor));
                RGB rgb = colorDialog.open();
                if (rgb != null) {
                    tagColor = rgbToHex(rgb);
                    updateColorButton();
                }
            }
        });
        
        // Description
        Label descLabel = new Label(container, SWT.NONE);
        descLabel.setText("Description:");
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.TOP).applyTo(descLabel);
        
        descriptionText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP);
        descriptionText.setText(tag.getDescription() != null ? tag.getDescription() : "");
        GridDataFactory.fillDefaults().grab(true, true).hint(250, 60).span(2, 1).applyTo(descriptionText);
        
        return container;
    }
    
    @Override
    protected void okPressed() {
        tagName = nameText.getText().trim();
        tagDescription = descriptionText.getText().trim();
        
        if (tagName.isEmpty()) {
            return;
        }
        
        super.okPressed();
    }
    
    public String getTagName() {
        return tagName;
    }
    
    public String getTagColor() {
        return tagColor;
    }
    
    public String getTagDescription() {
        return tagDescription;
    }
    
    private void updateColorButton() {
        colorButton.setImage(resourceManager.get(
            TagColorIconFactory.getColorIcon(tagColor, 24)));
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
