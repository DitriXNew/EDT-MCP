/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.ui;

import java.net.URL;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;
import org.eclipse.ui.navigator.ICommonLabelProvider;
import org.osgi.framework.Bundle;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.model.Group;

/**
 * Label provider for virtual group folders in the Navigator.
 */
public class GroupLabelProvider extends LabelProvider implements ICommonLabelProvider {
    
    private Image folderImage;
    
    @Override
    public void init(ICommonContentExtensionSite aConfig) {
        // Initialize folder icon
        try {
            Bundle bundle = Activator.getDefault().getBundle();
            URL url = bundle.getEntry("icons/group.png");
            if (url != null) {
                ImageDescriptor desc = ImageDescriptor.createFromURL(url);
                folderImage = desc.createImage();
            }
        } catch (Exception e) {
            Activator.logError("Failed to load folder icon", e);
        }
    }
    
    @Override
    public String getText(Object element) {
        if (element instanceof GroupNavigatorAdapter groupAdapter) {
            return groupAdapter.getGroup().getName();
        }
        // For real EObjects inside groups, EDT's label provider will handle them
        return null;
    }
    
    @Override
    public Image getImage(Object element) {
        if (element instanceof GroupNavigatorAdapter) {
            return folderImage;
        }
        // For real EObjects inside groups, EDT's label provider will handle them
        return null;
    }
    
    @Override
    public String getDescription(Object anElement) {
        if (anElement instanceof GroupNavigatorAdapter groupAdapter) {
            Group group = groupAdapter.getGroup();
            String desc = group.getDescription();
            if (desc != null && !desc.isEmpty()) {
                return desc;
            }
            return "Group: " + group.getFullPath();
        }
        return null;
    }
    
    @Override
    public void restoreState(IMemento aMemento) {
        // No state to restore
    }
    
    @Override
    public void saveState(IMemento aMemento) {
        // No state to save
    }
    
    @Override
    public void dispose() {
        if (folderImage != null && !folderImage.isDisposed()) {
            folderImage.dispose();
            folderImage = null;
        }
        super.dispose();
    }
}
