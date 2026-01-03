/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.ui;

import java.io.IOException;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.McpConstants;

/**
 * Status bar contribution showing MCP server status.
 * Displays a colored circle (grey=stopped, green=running), "MCP" text and request counter [N].
 * Click on circle shows popup menu with Start/Stop/Restart options.
 */
public class McpStatusContribution extends WorkbenchWindowControlContribution
{
    private Composite container;
    private Label circleLabel;
    private Label statusLabel;
    private Label counterLabel;
    private Menu popupMenu;
    private Font font;
    
    private Image greenImage;
    private Image greyImage;
    
    private volatile boolean disposed = false;
    private Thread updateThread;

    @Override
    protected Control createControl(Composite parent)
    {
        container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 2;
        layout.marginHeight = -5;
        layout.marginBottom = -5;
        container.setLayout(layout);
        
        // Create colored circle indicator
        createStatusImages(parent.getDisplay());
        
        circleLabel = new Label(container, SWT.NONE);
        circleLabel.setImage(greyImage);
        GridData circleGd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        circleGd.widthHint = 14;
        circleGd.heightHint = 14;
        circleLabel.setLayoutData(circleGd);
        
        // Create popup menu on circle click
        createPopupMenu();
        circleLabel.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseUp(MouseEvent e)
            {
                if (e.button == 1) // Left click
                {
                    updateMenuItems();
                    popupMenu.setVisible(true);
                }
            }
        });
        
        // Create "MCP" label
        statusLabel = new Label(container, SWT.NONE);
        statusLabel.setText("MCP"); //$NON-NLS-1$
        
        // Make font smaller
        Font originalFont = statusLabel.getFont();
        FontData fontData = originalFont.getFontData()[0];
        fontData.setHeight((int)(fontData.getHeight() * 0.9));
        font = new Font(originalFont.getDevice(), fontData);
        statusLabel.setFont(font);
        
        GridData statusGd = new GridData(SWT.CENTER, SWT.CENTER, true, true);
        statusLabel.setLayoutData(statusGd);
        
        // Create counter label [N]
        counterLabel = new Label(container, SWT.NONE);
        counterLabel.setText("[0]"); //$NON-NLS-1$
        counterLabel.setFont(font);
        
        GridData counterGd = new GridData(SWT.CENTER, SWT.CENTER, true, true);
        counterLabel.setLayoutData(counterGd);
        
        // Force redraw
        parent.getParent().setRedraw(true);
        
        // Update initial status
        updateStatus();
        
        // Start update thread
        startUpdateThread();
        
        return container;
    }

    private void createStatusImages(Display display)
    {
        // Create green circle image (running) with transparent background
        greenImage = createCircleImage(display, 50, 205, 50, 34, 139, 34); // Lime green with dark green border
        
        // Create grey circle image (stopped) with transparent background
        greyImage = createCircleImage(display, 128, 128, 128, 64, 64, 64); // Grey with dark grey border
    }
    
    /**
     * Creates a circle image with transparent background.
     */
    private Image createCircleImage(Display display, int r, int g, int b, int borderR, int borderG, int borderB)
    {
        int size = 12;
        
        // Create image data with alpha channel for transparency
        org.eclipse.swt.graphics.ImageData imageData = new org.eclipse.swt.graphics.ImageData(size, size, 24, 
            new org.eclipse.swt.graphics.PaletteData(0xFF0000, 0x00FF00, 0x0000FF));
        
        // Set transparent pixel (using magenta as transparent color)
        imageData.transparentPixel = imageData.palette.getPixel(new org.eclipse.swt.graphics.RGB(255, 0, 255));
        
        // Fill with transparent color first
        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                imageData.setPixel(x, y, imageData.transparentPixel);
            }
        }
        
        // Draw filled circle
        int centerX = size / 2;
        int centerY = size / 2;
        int radius = (size / 2) - 1;
        
        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                double distance = Math.sqrt((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY));
                if (distance <= radius - 0.5)
                {
                    // Inside circle - fill color
                    imageData.setPixel(x, y, imageData.palette.getPixel(new org.eclipse.swt.graphics.RGB(r, g, b)));
                }
                else if (distance <= radius + 0.5)
                {
                    // Border
                    imageData.setPixel(x, y, imageData.palette.getPixel(new org.eclipse.swt.graphics.RGB(borderR, borderG, borderB)));
                }
                // else: stays transparent
            }
        }
        
        return new Image(display, imageData);
    }

    private void createPopupMenu()
    {
        popupMenu = new Menu(circleLabel);
        
        MenuItem startItem = new MenuItem(popupMenu, SWT.PUSH);
        startItem.setText("Start"); //$NON-NLS-1$
        startItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                startServer();
            }
        });
        
        MenuItem restartItem = new MenuItem(popupMenu, SWT.PUSH);
        restartItem.setText("Restart"); //$NON-NLS-1$
        restartItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                restartServer();
            }
        });
        
        MenuItem stopItem = new MenuItem(popupMenu, SWT.PUSH);
        stopItem.setText("Stop"); //$NON-NLS-1$
        stopItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                stopServer();
            }
        });
    }

    private void updateMenuItems()
    {
        McpServer server = Activator.getDefault() != null ? 
            Activator.getDefault().getMcpServer() : null;
        boolean running = server != null && server.isRunning();
        
        // Enable/disable menu items based on state
        MenuItem[] items = popupMenu.getItems();
        if (items.length >= 3)
        {
            items[0].setEnabled(!running); // Start
            items[1].setEnabled(running);  // Restart
            items[2].setEnabled(running);  // Stop
        }
    }

    private void startServer()
    {
        McpServer server = Activator.getDefault() != null ? 
            Activator.getDefault().getMcpServer() : null;
        
        if (server == null || server.isRunning())
        {
            return;
        }
        
        try
        {
            int port = Activator.getDefault().getPreferenceStore()
                .getInt(PreferenceConstants.PREF_PORT);
            server.start(port);
            updateStatus();
        }
        catch (IOException e)
        {
            Activator.logError("Failed to start MCP server from status bar", e); //$NON-NLS-1$
        }
    }

    private void restartServer()
    {
        McpServer server = Activator.getDefault() != null ? 
            Activator.getDefault().getMcpServer() : null;
        
        if (server == null)
        {
            return;
        }
        
        try
        {
            int port = Activator.getDefault().getPreferenceStore()
                .getInt(PreferenceConstants.PREF_PORT);
            server.restart(port);
            updateStatus();
        }
        catch (IOException e)
        {
            Activator.logError("Failed to restart MCP server from status bar", e); //$NON-NLS-1$
        }
    }

    private void stopServer()
    {
        McpServer server = Activator.getDefault() != null ? 
            Activator.getDefault().getMcpServer() : null;
        
        if (server == null || !server.isRunning())
        {
            return;
        }
        
        server.stop();
        updateStatus();
    }

    @Override
    public boolean isDynamic()
    {
        return true;
    }

    private void startUpdateThread()
    {
        updateThread = new Thread(() -> {
            while (!disposed && !Thread.currentThread().isInterrupted())
            {
                try
                {
                    Thread.sleep(1000); // Update every second
                    Display display = Display.getDefault();
                    if (display != null && !display.isDisposed())
                    {
                        display.asyncExec(this::updateStatus);
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "MCP-Status-Update"); //$NON-NLS-1$
        updateThread.setDaemon(true);
        updateThread.start();
    }

    private void updateStatus()
    {
        if (disposed || container == null || container.isDisposed())
        {
            return;
        }
        
        McpServer server = Activator.getDefault() != null ? 
            Activator.getDefault().getMcpServer() : null;
        
        boolean running = server != null && server.isRunning();
        long requestCount = server != null ? server.getRequestCount() : 0;
        int port = server != null ? server.getPort() : 0;
        
        // Update circle image
        if (circleLabel != null && !circleLabel.isDisposed())
        {
            circleLabel.setImage(running ? greenImage : greyImage);
        }
        
        // Update counter
        if (counterLabel != null && !counterLabel.isDisposed())
        {
            counterLabel.setText("[" + requestCount + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        // Update tooltip
        String tooltip;
        if (running)
        {
            tooltip = "MCP Server: Running on port " + port + "\nRequests: " + requestCount + 
                "\nVersion: " + McpConstants.PLUGIN_VERSION + "\nAuthor: " + McpConstants.AUTHOR +
                "\nClick for options";
        }
        else
        {
            tooltip = "MCP Server: Stopped\nClick to start";
        }
        
        if (circleLabel != null && !circleLabel.isDisposed())
        {
            circleLabel.setToolTipText(tooltip);
        }
        if (statusLabel != null && !statusLabel.isDisposed())
        {
            statusLabel.setToolTipText(tooltip);
        }
        if (counterLabel != null && !counterLabel.isDisposed())
        {
            counterLabel.setToolTipText(tooltip);
        }
        
        container.layout(true);
    }

    @Override
    public void dispose()
    {
        disposed = true;
        
        if (updateThread != null)
        {
            updateThread.interrupt();
        }
        
        if (font != null && !font.isDisposed())
        {
            font.dispose();
        }
        
        if (greenImage != null && !greenImage.isDisposed())
        {
            greenImage.dispose();
        }
        
        if (greyImage != null && !greyImage.isDisposed())
        {
            greyImage.dispose();
        }
        
        if (popupMenu != null && !popupMenu.isDisposed())
        {
            popupMenu.dispose();
        }
        
        super.dispose();
    }
}
