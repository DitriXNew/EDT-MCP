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
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Dialog for editing an existing tag.
 */
public class EditTagDialog extends Dialog {
    
    private static final int COLOR_ICON_SIZE = 24;
    
    private final Tag tag;
    
    private Text nameText;
    private Text descriptionText;
    private Button colorButton;
    private Image colorButtonImage;
    
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
        // Dispose old image first
        if (colorButtonImage != null && !colorButtonImage.isDisposed()) {
            colorButtonImage.dispose();
        }
        colorButtonImage = createColorIcon(tagColor);
        colorButton.setImage(colorButtonImage);
    }
    
    @Override
    public boolean close() {
        // Dispose the color button image
        if (colorButtonImage != null && !colorButtonImage.isDisposed()) {
            colorButtonImage.dispose();
            colorButtonImage = null;
        }
        return super.close();
    }
    
    private Image createColorIcon(String hexColor) {
        Display display = Display.getCurrent();
        Image image = new Image(display, COLOR_ICON_SIZE, COLOR_ICON_SIZE);
        GC gc = new GC(image);
        
        RGB rgb = hexToRgb(hexColor);
        Color color = new Color(display, rgb);
        gc.setBackground(color);
        gc.fillRectangle(0, 0, COLOR_ICON_SIZE, COLOR_ICON_SIZE);
        gc.setForeground(display.getSystemColor(SWT.COLOR_GRAY));
        gc.drawRectangle(0, 0, COLOR_ICON_SIZE - 1, COLOR_ICON_SIZE - 1);
        
        gc.dispose();
        color.dispose();
        
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
    
    private String rgbToHex(RGB rgb) {
        return String.format("#%02X%02X%02X", rgb.red, rgb.green, rgb.blue);
    }
}
