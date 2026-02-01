/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.groups.ui;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.model.IWorkbenchAdapter;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;
import org.eclipse.ui.navigator.ICommonLabelProvider;
import org.osgi.framework.Bundle;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.ui.GroupNavigatorAdapter.GroupedObjectPlaceholder;

/**
 * Label provider for virtual group folders in the Navigator.
 */
public class GroupLabelProvider extends LabelProvider implements ICommonLabelProvider {
    
    private Image folderImage;
    // Image cache to prevent memory leaks from repeated createImage() calls
    private final Map<ImageDescriptor, Image> imageCache = new HashMap<>();
    
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
        if (element instanceof GroupedObjectPlaceholder placeholder) {
            // Delegate to resolved object for proper display name
            EObject resolved = placeholder.getResolvedObject();
            if (resolved != null) {
                IWorkbenchAdapter adapter = Platform.getAdapterManager().getAdapter(resolved, IWorkbenchAdapter.class);
                if (adapter != null) {
                    return adapter.getLabel(resolved);
                }
            }
            // Fallback to simple name from FQN
            String fqn = placeholder.getObjectFqn();
            int lastDot = fqn.lastIndexOf('.');
            return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
        }
        return null;
    }
    
    @Override
    public Image getImage(Object element) {
        if (element instanceof GroupNavigatorAdapter) {
            return folderImage;
        }
        if (element instanceof GroupedObjectPlaceholder placeholder) {
            // Delegate to resolved object for proper icon
            EObject resolved = placeholder.getResolvedObject();
            if (resolved != null) {
                IWorkbenchAdapter adapter = Platform.getAdapterManager().getAdapter(resolved, IWorkbenchAdapter.class);
                if (adapter != null) {
                    ImageDescriptor desc = adapter.getImageDescriptor(resolved);
                    if (desc != null) {
                        // Use cache to prevent memory leaks
                        return getCachedImage(desc);
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Gets an image from cache or creates and caches it.
     */
    private Image getCachedImage(ImageDescriptor descriptor) {
        return imageCache.computeIfAbsent(descriptor, desc -> {
            try {
                return desc.createImage();
            } catch (Exception e) {
                return null;
            }
        });
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
        // Dispose all cached images
        for (Image image : imageCache.values()) {
            if (image != null && !image.isDisposed()) {
                image.dispose();
            }
        }
        imageCache.clear();
        super.dispose();
    }
}
