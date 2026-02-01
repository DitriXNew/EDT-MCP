/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server;

import org.eclipse.ui.IStartup;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;

/**
 * Startup class for auto-starting MCP server on EDT startup.
 */
public class McpServerStartup implements IStartup
{
    @Override
    public void earlyStartup()
    {
        
        // Check auto-start preference
        boolean autoStart = Activator.getDefault().getPreferenceStore()
            .getBoolean(PreferenceConstants.PREF_AUTO_START);
        
        if (autoStart)
        {
            int port = Activator.getDefault().getPreferenceStore()
                .getInt(PreferenceConstants.PREF_PORT);
            
            try
            {
                Activator.getDefault().getMcpServer().start(port);
                Activator.logInfo("MCP Server auto-started on port " + port);
            }
            catch (Exception e)
            {
                Activator.logError("Failed to auto-start MCP Server", e);
            }
        }
    }
}
