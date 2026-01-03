/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;

/**
 * EDT MCP Server plugin activator.
 * Uses OSGi ServiceTracker to obtain EDT platform services.
 */
public class Activator extends AbstractUIPlugin
{
    /** Plugin ID */
    public static final String PLUGIN_ID = "com.ditrix.edt.mcp.server"; //$NON-NLS-1$

    /** Singleton instance */
    private static Activator plugin;

    /** MCP Server instance */
    private McpServer mcpServer;

    /** Service trackers */
    private ServiceTracker<IV8ProjectManager, IV8ProjectManager> v8ProjectManagerTracker;
    private ServiceTracker<IDtProjectManager, IDtProjectManager> dtProjectManagerTracker;
    private ServiceTracker<IConfigurationProvider, IConfigurationProvider> configurationProviderTracker;
    private ServiceTracker<IMarkerManager, IMarkerManager> markerManagerTracker;

    @Override
    public void start(BundleContext context) throws Exception
    {
        super.start(context);
        plugin = this;
        mcpServer = new McpServer();
        
        // Initialize service trackers
        v8ProjectManagerTracker = new ServiceTracker<>(context, IV8ProjectManager.class, null);
        v8ProjectManagerTracker.open();
        
        dtProjectManagerTracker = new ServiceTracker<>(context, IDtProjectManager.class, null);
        dtProjectManagerTracker.open();
        
        configurationProviderTracker = new ServiceTracker<>(context, IConfigurationProvider.class, null);
        configurationProviderTracker.open();
        
        markerManagerTracker = new ServiceTracker<>(context, IMarkerManager.class, null);
        markerManagerTracker.open();
        
        logInfo("EDT MCP Server plugin started"); //$NON-NLS-1$
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
        if (mcpServer != null && mcpServer.isRunning())
        {
            mcpServer.stop();
        }
        
        // Close service trackers
        if (v8ProjectManagerTracker != null)
        {
            v8ProjectManagerTracker.close();
            v8ProjectManagerTracker = null;
        }
        if (dtProjectManagerTracker != null)
        {
            dtProjectManagerTracker.close();
            dtProjectManagerTracker = null;
        }
        if (configurationProviderTracker != null)
        {
            configurationProviderTracker.close();
            configurationProviderTracker = null;
        }
        if (markerManagerTracker != null)
        {
            markerManagerTracker.close();
            markerManagerTracker = null;
        }
        
        logInfo("EDT MCP Server plugin stopped"); //$NON-NLS-1$
        plugin = null;
        super.stop(context);
    }

    /**
     * Returns the singleton activator instance.
     * 
     * @return activator
     */
    public static Activator getDefault()
    {
        return plugin;
    }

    /**
     * Returns the IV8ProjectManager service.
     * 
     * @return project manager or null if not available
     */
    public IV8ProjectManager getV8ProjectManager()
    {
        if (v8ProjectManagerTracker == null)
        {
            return null;
        }
        return v8ProjectManagerTracker.getService();
    }

    /**
     * Returns the IDtProjectManager service.
     * 
     * @return DT project manager or null if not available
     */
    public IDtProjectManager getDtProjectManager()
    {
        if (dtProjectManagerTracker == null)
        {
            return null;
        }
        return dtProjectManagerTracker.getService();
    }

    /**
     * Returns the IConfigurationProvider service.
     * 
     * @return configuration provider or null if not available
     */
    public IConfigurationProvider getConfigurationProvider()
    {
        if (configurationProviderTracker == null)
        {
            return null;
        }
        return configurationProviderTracker.getService();
    }

    /**
     * Returns the MCP Server.
     * 
     * @return MCP server
     */
    public McpServer getMcpServer()
    {
        return mcpServer;
    }
    
    /**
     * Returns the IMarkerManager service for accessing EDT configuration problems.
     * 
     * @return marker manager or null if not available
     */
    public IMarkerManager getMarkerManager()
    {
        if (markerManagerTracker == null)
        {
            return null;
        }
        return markerManagerTracker.getService();
    }

    /**
     * Logs an info message.
     * 
     * @param message the message
     */
    public static void logInfo(String message)
    {
        if (plugin != null)
        {
            plugin.getLog().log(new Status(IStatus.INFO, PLUGIN_ID, message));
        }
    }

    /**
     * Logs an error.
     * 
     * @param message the message
     * @param e the exception
     */
    public static void logError(String message, Throwable e)
    {
        if (plugin != null)
        {
            plugin.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, message, e));
        }
    }

    /**
     * Creates an error status.
     * 
     * @param message the message
     * @param e the exception
     * @return error status
     */
    public static IStatus createErrorStatus(String message, Throwable e)
    {
        return new Status(IStatus.ERROR, PLUGIN_ID, message, e);
    }
}
