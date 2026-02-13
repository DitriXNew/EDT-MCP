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

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import com.ditrix.edt.mcp.server.tags.TagConstants;

/**
 * Factory for creating color icon ImageDescriptors used by tag UI components.
 * 
 * <p>This factory provides static methods that return ImageDescriptors.
 * Use with a ResourceManager for proper image lifecycle management.</p>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * // In a dialog or view
 * private ResourceManager resourceManager;
 * 
 * protected Control createDialogArea(Composite parent) {
 *     resourceManager = new LocalResourceManager(
 *         TagColorIconFactory.getJFaceResources(), parent);
 *     
 *     Image icon = resourceManager.get(
 *         TagColorIconFactory.getColorIcon("#FF0000", 16));
 *     // Use icon...
 * }
 * // ResourceManager auto-disposes when parent is disposed
 * </pre>
 * 
 * <p>All static methods return ImageDescriptor instances that can be used
 * with ResourceManager.get() for proper resource management.</p>
 */
public final class TagColorIconFactory {
    
    private TagColorIconFactory() {
        // Utility class - no instantiation
    }
    
    /**
     * Gets the JFace resources manager for the default display.
     * Use this to create a LocalResourceManager.
     * 
     * @return the JFace ResourceManager
     */
    public static ResourceManager getJFaceResources() {
        return JFaceResources.getResources(Display.getDefault());
    }
    
    /**
     * Gets a color icon descriptor with the default size.
     * 
     * @param hexColor the color in hex format (e.g., "#FF0000")
     * @return the image descriptor
     */
    public static ImageDescriptor getColorIcon(String hexColor) {
        return getColorIcon(hexColor, TagConstants.COLOR_ICON_SIZE_NORMAL);
    }
    
    /**
     * Gets a color icon descriptor of the specified size.
     * 
     * <p>The icon is a simple filled square with a border.</p>
     * 
     * @param hexColor the color in hex format (e.g., "#FF0000")
     * @param size the icon size in pixels
     * @return the image descriptor
     */
    public static ImageDescriptor getColorIcon(String hexColor, int size) {
        return new ColorIconDescriptor(hexColor, size, false);
    }
    
    /**
     * Gets a circular color icon descriptor of the specified size.
     * 
     * @param hexColor the color in hex format (e.g., "#FF0000")
     * @param size the icon size in pixels
     * @return the image descriptor
     */
    public static ImageDescriptor getCircularColorIcon(String hexColor, int size) {
        return new ColorIconDescriptor(hexColor, size, true);
    }
    
    /**
     * Gets a circular color icon descriptor with a checkmark overlay.
     * Used for checked tag items in menus.
     * 
     * @param hexColor the color in hex format
     * @param size the icon size in pixels
     * @param checked whether to draw a checkmark
     * @return the image descriptor
     */
    public static ImageDescriptor getCircularColorIconWithCheck(String hexColor, int size, boolean checked) {
        return new CheckableColorIconDescriptor(hexColor, size, checked);
    }
    
    // ===== Color Conversion Utilities =====
    
    /**
     * Converts a hex color string to RGB.
     * 
     * @param hex the hex color string (e.g., "#FF0000" or "FF0000")
     * @return the RGB value
     */
    public static RGB hexToRgb(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new RGB(128, 128, 128); // Default gray
        }
        
        hex = hex.replace("#", "");
        try {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            return new RGB(r, g, b);
        } catch (Exception e) {
            return new RGB(128, 128, 128); // Default gray
        }
    }
    
    /**
     * Converts RGB to a hex color string.
     * 
     * @param rgb the RGB value
     * @return the hex color string with # prefix (e.g., "#FF0000")
     */
    public static String rgbToHex(RGB rgb) {
        if (rgb == null) {
            return TagConstants.DEFAULT_TAG_COLOR;
        }
        return String.format("#%02X%02X%02X", rgb.red, rgb.green, rgb.blue);
    }
    
    // ===== Image Descriptors =====
    
    /**
     * ImageDescriptor for creating colored square icons.
     */
    private static class ColorIconDescriptor extends ImageDescriptor {
        
        private final String hexColor;
        private final int size;
        private final boolean circular;
        
        public ColorIconDescriptor(String hexColor, int size, boolean circular) {
            this.hexColor = hexColor;
            this.size = size;
            this.circular = circular;
        }
        
        @Override
        public ImageData getImageData(int zoom) {
            return createImageData(zoom);
        }
        
        @Override
        public ImageData getImageData() {
            return createImageData(100);
        }
        
        private ImageData createImageData(int zoom) {
            int scaledSize = size * zoom / 100;
            
            // Create a 24-bit image with transparency
            PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
            ImageData data = new ImageData(scaledSize, scaledSize, 24, palette);
            data.transparentPixel = -1;
            
            // Create the image and draw on it
            Display display = Display.getDefault();
            Image image = new Image(display, scaledSize, scaledSize);
            GC gc = new GC(image);
            
            try {
                RGB rgb = hexToRgb(hexColor);
                Color color = new Color(display, rgb);
                Color borderColor = display.getSystemColor(SWT.COLOR_DARK_GRAY);
                
                gc.setBackground(color);
                gc.setForeground(borderColor);
                
                if (circular) {
                    gc.fillOval(1, 1, scaledSize - 2, scaledSize - 2);
                    gc.drawOval(1, 1, scaledSize - 2, scaledSize - 2);
                } else {
                    gc.fillRectangle(0, 0, scaledSize, scaledSize);
                    gc.drawRectangle(0, 0, scaledSize - 1, scaledSize - 1);
                }
                
                color.dispose();
                
                // Get the image data
                ImageData result = image.getImageData();
                return result;
                
            } finally {
                gc.dispose();
                image.dispose();
            }
        }
    }
    
    /**
     * ImageDescriptor for creating circular colored icons with optional checkmark.
     */
    private static class CheckableColorIconDescriptor extends ImageDescriptor {
        
        private final String hexColor;
        private final int size;
        private final boolean checked;
        
        public CheckableColorIconDescriptor(String hexColor, int size, boolean checked) {
            this.hexColor = hexColor;
            this.size = size;
            this.checked = checked;
        }
        
        @Override
        public ImageData getImageData(int zoom) {
            return createImageData(zoom);
        }
        
        @Override
        public ImageData getImageData() {
            return createImageData(100);
        }
        
        private ImageData createImageData(int zoom) {
            int scaledSize = size * zoom / 100;
            
            Display display = Display.getDefault();
            Image image = new Image(display, scaledSize, scaledSize);
            GC gc = new GC(image);
            
            try {
                RGB rgb = hexToRgb(hexColor);
                Color color = new Color(display, rgb);
                Color borderColor = display.getSystemColor(SWT.COLOR_DARK_GRAY);
                Color checkColor = display.getSystemColor(SWT.COLOR_WHITE);
                
                // Fill background
                gc.setBackground(color);
                gc.fillOval(2, 2, scaledSize - 4, scaledSize - 4);
                
                // Draw border
                gc.setForeground(borderColor);
                gc.drawOval(2, 2, scaledSize - 4, scaledSize - 4);
                
                // Draw checkmark if checked
                if (checked) {
                    gc.setForeground(checkColor);
                    gc.setLineWidth(2);
                    // Scale checkmark positions
                    int x1 = scaledSize / 4;
                    int y1 = scaledSize / 2;
                    int x2 = scaledSize * 7 / 16;
                    int y2 = scaledSize * 11 / 16;
                    int x3 = scaledSize * 3 / 4;
                    int y3 = scaledSize * 5 / 16;
                    gc.drawLine(x1, y1, x2, y2);
                    gc.drawLine(x2, y2, x3, y3);
                }
                
                color.dispose();
                
                // Get the image data
                ImageData result = image.getImageData();
                return result;
                
            } finally {
                gc.dispose();
                image.dispose();
            }
        }
    }
}
