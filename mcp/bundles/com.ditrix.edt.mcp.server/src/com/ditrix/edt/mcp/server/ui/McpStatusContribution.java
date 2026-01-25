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
import com.ditrix.edt.mcp.server.UserSignal;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.McpConstants;

/**
 * Status bar contribution showing MCP server status.
 * Displays a colored circle (grey=stopped, green=running, yellow=executing), "MCP" text and request counter [N].
 * When a tool is executing, shows tool name and blinks yellow.
 * Click on circle shows popup menu with Start/Stop/Restart options.
 */
public class McpStatusContribution extends WorkbenchWindowControlContribution
{
    /** Width hint for the status label to accommodate full tool names */
    private static final int STATUS_LABEL_WIDTH_HINT = 200;
    
    /** Maximum length for tool name display before truncation */
    private static final int TOOL_NAME_MAX_LENGTH = 25;
    
    /** Size hint for the circle indicator image */
    private static final int CIRCLE_SIZE_HINT = 14;
    
    /** Update interval in milliseconds for status refresh and blinking effect */
    private static final int STATUS_UPDATE_INTERVAL_MS = 500;
    
    /** Font size scaling factor for status bar text */
    private static final double FONT_SIZE_SCALE = 0.9;
    
    private Composite container;
    private Label circleLabel;
    private Label statusLabel;
    private Label counterLabel;
    private Menu popupMenu;
    private Font font;
    
    private Image greenImage;
    private Image greyImage;
    private Image yellowImage;
    
    /** For blinking effect during tool execution */
    private boolean blinkState = false;
    
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
        circleGd.widthHint = CIRCLE_SIZE_HINT;
        circleGd.heightHint = CIRCLE_SIZE_HINT;
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
        fontData.setHeight((int)(fontData.getHeight() * FONT_SIZE_SCALE));
        font = new Font(originalFont.getDevice(), fontData);
        statusLabel.setFont(font);
        
        GridData statusGd = new GridData(SWT.CENTER, SWT.CENTER, true, true);
        statusGd.widthHint = STATUS_LABEL_WIDTH_HINT;
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
        
        // Create yellow circle image (executing tool) with transparent background
        yellowImage = createCircleImage(display, 255, 215, 0, 184, 134, 11); // Gold with dark goldenrod border
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
        
        // Signal menu items (shown only when tool is executing)
        MenuItem cancelItem = new MenuItem(popupMenu, SWT.PUSH);
        cancelItem.setText("Cancel Operation"); //$NON-NLS-1$
        cancelItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                sendSignal(UserSignal.SignalType.CANCEL, "Cancel Operation");
            }
        });
        
        MenuItem retryItem = new MenuItem(popupMenu, SWT.PUSH);
        retryItem.setText("Retry"); //$NON-NLS-1$
        retryItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                sendSignal(UserSignal.SignalType.RETRY, "Retry");
            }
        });
        
        MenuItem backgroundItem = new MenuItem(popupMenu, SWT.PUSH);
        backgroundItem.setText("Continue in Background"); //$NON-NLS-1$
        backgroundItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                sendSignal(UserSignal.SignalType.BACKGROUND, "Continue in Background");
            }
        });
        
        MenuItem expertItem = new MenuItem(popupMenu, SWT.PUSH);
        expertItem.setText("Ask Expert"); //$NON-NLS-1$
        expertItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                sendSignal(UserSignal.SignalType.EXPERT, "Ask Expert");
            }
        });
        
        MenuItem customItem = new MenuItem(popupMenu, SWT.PUSH);
        customItem.setText("Send Custom Message..."); //$NON-NLS-1$
        customItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                sendSignal(UserSignal.SignalType.CUSTOM, "Custom Message");
            }
        });
        
        // Separator
        new MenuItem(popupMenu, SWT.SEPARATOR);
        
        // Server control items
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
    
    private void sendSignal(UserSignal.SignalType type, String title)
    {
        McpServer server = Activator.getDefault() != null ? 
            Activator.getDefault().getMcpServer() : null;
        
        if (server == null || !server.isToolExecuting())
        {
            return;
        }
        
        // Show dialog to edit message
        UserSignalDialog dialog = new UserSignalDialog(
            container.getShell(), type, title);
        
        if (dialog.open() == org.eclipse.jface.window.Window.OK)
        {
            UserSignal signal = new UserSignal(type, dialog.getMessage());
            
            // Interrupt the tool call and send response immediately
            boolean interrupted = server.interruptToolCall(signal);
            if (interrupted)
            {
                Activator.logInfo("Tool call interrupted with signal: " + type.name()); //$NON-NLS-1$
            }
            else
            {
                // Fallback: store signal for when tool completes
                server.setUserSignal(signal);
                Activator.logInfo("User signal queued: " + type.name()); //$NON-NLS-1$
            }
        }
    }

    private void updateMenuItems()
    {
        McpServer server = Activator.getDefault() != null ? 
            Activator.getDefault().getMcpServer() : null;
        boolean running = server != null && server.isRunning();
        boolean isExecuting = server != null && server.isToolExecuting();
        
        // Menu structure: 
        // 0: Cancel Operation
        // 1: Retry
        // 2: Continue in Background
        // 3: Ask Expert  
        // 4: Send Custom Message
        // 5: Separator
        // 6: Start
        // 7: Restart
        // 8: Stop
        MenuItem[] items = popupMenu.getItems();
        if (items.length >= 9)
        {
            // Signal items - only enabled when tool is executing
            items[0].setEnabled(isExecuting); // Cancel Operation
            items[1].setEnabled(isExecuting); // Retry
            items[2].setEnabled(isExecuting); // Continue in Background
            items[3].setEnabled(isExecuting); // Ask Expert
            items[4].setEnabled(isExecuting); // Send Custom Message
            // items[5] is separator
            
            // Server control items
            items[6].setEnabled(!running); // Start
            items[7].setEnabled(running);  // Restart
            items[8].setEnabled(running);  // Stop
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
                    Thread.sleep(STATUS_UPDATE_INTERVAL_MS);
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
        String currentTool = server != null ? server.getCurrentToolName() : null;
        boolean isExecuting = currentTool != null;
        long executionSeconds = server != null ? server.getToolExecutionSeconds() : 0;
        
        // Toggle blink state for animation effect
        blinkState = !blinkState;
        
        // Update circle image - yellow blinking when executing, green when running, grey when stopped
        if (circleLabel != null && !circleLabel.isDisposed())
        {
            if (isExecuting)
            {
                // Blink between yellow and green during execution
                circleLabel.setImage(blinkState ? yellowImage : greenImage);
            }
            else
            {
                circleLabel.setImage(running ? greenImage : greyImage);
            }
        }
        
        // Update status label - show tool name when executing
        if (statusLabel != null && !statusLabel.isDisposed())
        {
            if (isExecuting)
            {
                // Add MCP: prefix and truncate tool name if too long
                String displayName = currentTool.length() > TOOL_NAME_MAX_LENGTH 
                    ? "MCP: " + currentTool.substring(0, TOOL_NAME_MAX_LENGTH - 3) + "..." //$NON-NLS-1$ //$NON-NLS-2$
                    : "MCP: " + currentTool; //$NON-NLS-1$
                statusLabel.setText(displayName);
            }
            else
            {
                statusLabel.setText("MCP"); //$NON-NLS-1$
            }
        }
        
        // Update counter - show elapsed time during execution, otherwise request count
        if (counterLabel != null && !counterLabel.isDisposed())
        {
            if (isExecuting)
            {
                long minutes = executionSeconds / 60;
                long seconds = executionSeconds % 60;
                String timeStr = String.format("%02d:%02d        ", minutes, seconds); //$NON-NLS-1$
                counterLabel.setText(timeStr);
            }
            else
            {
                counterLabel.setText("[" + requestCount + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        
        // Update tooltip
        String tooltip;
        if (isExecuting)
        {
            tooltip = "MCP Server: Executing " + currentTool +
                "\nPort: " + port + "\nRequests: " + requestCount + 
                "\nVersion: " + McpConstants.PLUGIN_VERSION + "\nAuthor: " + McpConstants.AUTHOR;
        }
        else if (running)
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
        
        if (yellowImage != null && !yellowImage.isDisposed())
        {
            yellowImage.dispose();
        }
        
        if (popupMenu != null && !popupMenu.isDisposed())
        {
            popupMenu.dispose();
        }
        
        super.dispose();
    }
}
