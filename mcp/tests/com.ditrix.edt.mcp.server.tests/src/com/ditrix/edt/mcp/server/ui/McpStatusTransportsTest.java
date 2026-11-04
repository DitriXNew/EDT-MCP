/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.ditrix.edt.mcp.server.ipc.EndpointRegistry;
import com.ditrix.edt.mcp.server.ipc.PrivatePaths;
import com.ditrix.edt.mcp.server.transport.IpcListener;
import com.ditrix.edt.mcp.server.transport.StdioPluginSupport;

public class McpStatusTransportsTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void ipcOnlyHasGreenIndicatorAndHonestTooltip() throws Exception
    {
        assumeTrue("SWT UI tests require Windows or an available display",
            System.getProperty("os.name").startsWith("Windows")
                || (System.getenv("DISPLAY") != null && !System.getenv("DISPLAY").isBlank())
                || (System.getenv("WAYLAND_DISPLAY") != null && !System.getenv("WAYLAND_DISPLAY").isBlank()));
        Display display = Display.getCurrent();
        boolean ownsDisplay = display == null;
        if (ownsDisplay) { display = new Display(); }
        Shell shell = new Shell(display);
        McpStatusContribution contribution = new McpStatusContribution();
        PrivatePaths.directory(temporary.getRoot().toPath());
        EndpointRegistry registry = new EndpointRegistry(PrivatePaths.directory(temporary.newFolder().toPath()),
            temporary.newFolder().toPath());
        Field listenerField = StdioPluginSupport.class.getDeclaredField("listener");
        listenerField.setAccessible(true);
        Object previous = listenerField.get(null);
        try (IpcListener listener = new IpcListener(registry, "test"))
        {
            listenerField.set(null, listener);
            contribution.createControl(new Composite(shell, SWT.NONE));
            Field current = McpStatusContribution.class.getDeclaredField("currentImage");
            Field green = McpStatusContribution.class.getDeclaredField("greenImage");
            current.setAccessible(true);
            green.setAccessible(true);
            assertSame(green.get(contribution), current.get(contribution));
            Field canvasField = McpStatusContribution.class.getDeclaredField("statusCanvas");
            canvasField.setAccessible(true);
            Canvas canvas = (Canvas)canvasField.get(contribution);
            String text = canvas.getToolTipText();
            assertTrue(text.contains(Messages.McpStatus_StdioReady));
            assertTrue(text.contains(Messages.McpStatus_HttpStopped));
            assertFalse(text.contains("MCP Server: Stopped"));
            assertFalse(text.contains("8765"));
            listener.close();
            Method update = McpStatusContribution.class.getDeclaredMethod("updateStatus");
            update.setAccessible(true);
            update.invoke(contribution);
            assertTrue(canvas.getToolTipText().contains(Messages.McpStatus_StdioUnavailable));
        }
        finally
        {
            listenerField.set(null, previous);
            contribution.dispose();
            shell.dispose();
            if (ownsDisplay) { display.dispose(); }
        }
    }
}
