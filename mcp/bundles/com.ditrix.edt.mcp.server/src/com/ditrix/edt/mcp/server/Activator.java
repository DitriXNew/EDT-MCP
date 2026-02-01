/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.lifecycle.IServicesOrchestrator;
import com._1c.g5.v8.dt.navigator.providers.INavigatorContentProviderStateProvider;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.v8.dt.check.ICheckScheduler;

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
    private ServiceTracker<ICheckScheduler, ICheckScheduler> checkSchedulerTracker;
    private ServiceTracker<IBmModelManager, IBmModelManager> bmModelManagerTracker;
    private ServiceTracker<IDerivedDataManagerProvider, IDerivedDataManagerProvider> derivedDataManagerProviderTracker;
    private ServiceTracker<IServicesOrchestrator, IServicesOrchestrator> servicesOrchestratorTracker;
    private ServiceTracker<BmAwareResourceSetProvider, BmAwareResourceSetProvider> resourceSetProviderTracker;
    private ServiceTracker<IApplicationManager, IApplicationManager> applicationManagerTracker;
    private ServiceTracker<INavigatorContentProviderStateProvider, INavigatorContentProviderStateProvider> navigatorStateProviderTracker;

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
        
        checkSchedulerTracker = new ServiceTracker<>(context, ICheckScheduler.class, null);
        checkSchedulerTracker.open();
        
        bmModelManagerTracker = new ServiceTracker<>(context, IBmModelManager.class, null);
        bmModelManagerTracker.open();
        
        derivedDataManagerProviderTracker = new ServiceTracker<>(context, IDerivedDataManagerProvider.class, null);
        derivedDataManagerProviderTracker.open();
        
        servicesOrchestratorTracker = new ServiceTracker<>(context, IServicesOrchestrator.class, null);
        servicesOrchestratorTracker.open();
        
        resourceSetProviderTracker = new ServiceTracker<>(context, BmAwareResourceSetProvider.class, null);
        resourceSetProviderTracker.open();
        
        applicationManagerTracker = new ServiceTracker<>(context, IApplicationManager.class, null);
        applicationManagerTracker.open();
        
        navigatorStateProviderTracker = new ServiceTracker<>(context, INavigatorContentProviderStateProvider.class, null);
        navigatorStateProviderTracker.open();
        
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
        if (checkSchedulerTracker != null)
        {
            checkSchedulerTracker.close();
            checkSchedulerTracker = null;
        }
        if (bmModelManagerTracker != null)
        {
            bmModelManagerTracker.close();
            bmModelManagerTracker = null;
        }
        if (derivedDataManagerProviderTracker != null)
        {
            derivedDataManagerProviderTracker.close();
            derivedDataManagerProviderTracker = null;
        }
        if (servicesOrchestratorTracker != null)
        {
            servicesOrchestratorTracker.close();
            servicesOrchestratorTracker = null;
        }
        if (resourceSetProviderTracker != null)
        {
            resourceSetProviderTracker.close();
            resourceSetProviderTracker = null;
        }
        if (applicationManagerTracker != null)
        {
            applicationManagerTracker.close();
            applicationManagerTracker = null;
        }
        if (navigatorStateProviderTracker != null)
        {
            navigatorStateProviderTracker.close();
            navigatorStateProviderTracker = null;
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
     * Returns the ICheckScheduler service for scheduling EDT validations.
     * 
     * @return check scheduler or null if not available
     */
    public ICheckScheduler getCheckScheduler()
    {
        if (checkSchedulerTracker == null)
        {
            return null;
        }
        return checkSchedulerTracker.getService();
    }
    
    /**
     * Returns the IBmModelManager service for BM model operations.
     * 
     * @return BM model manager or null if not available
     */
    public IBmModelManager getBmModelManager()
    {
        if (bmModelManagerTracker == null)
        {
            return null;
        }
        return bmModelManagerTracker.getService();
    }
    
    /**
     * Returns the IDerivedDataManagerProvider service for derived data operations.
     * Used for waiting for validation and other derived data computations.
     * 
     * @return derived data manager provider or null if not available
     */
    public IDerivedDataManagerProvider getDerivedDataManagerProvider()
    {
        if (derivedDataManagerProviderTracker == null)
        {
            return null;
        }
        return derivedDataManagerProviderTracker.getService();
    }
    
    /**
     * Returns the IServicesOrchestrator service for lifecycle management.
     * Used for waiting for project context lifecycle events.
     * 
     * @return services orchestrator or null if not available
     */
    public IServicesOrchestrator getServicesOrchestrator()
    {
        if (servicesOrchestratorTracker == null)
        {
            return null;
        }
        return servicesOrchestratorTracker.getService();
    }
    
    /**
     * Returns the BmAwareResourceSetProvider service for resolving EMF proxies.
     * Used for resolving platform type proxies in content assist.
     * 
     * @return resource set provider or null if not available
     */
    public BmAwareResourceSetProvider getResourceSetProvider()
    {
        if (resourceSetProviderTracker == null)
        {
            return null;
        }
        return resourceSetProviderTracker.getService();
    }
    
    /**
     * Returns the IApplicationManager service for managing applications.
     * Used for application lifecycle operations (update, start, etc.).
     * 
     * @return application manager or null if not available
     */
    public IApplicationManager getApplicationManager()
    {
        if (applicationManagerTracker == null)
        {
            return null;
        }
        return applicationManagerTracker.getService();
    }
    
    /**
     * Returns the INavigatorContentProviderStateProvider service.
     * Used for controlling navigator content filtering state.
     * 
     * @return navigator state provider or null if not available
     */
    public INavigatorContentProviderStateProvider getNavigatorStateProvider()
    {
        if (navigatorStateProviderTracker == null)
        {
            return null;
        }
        return navigatorStateProviderTracker.getService();
    }

    /**
     * Returns the default result limit for tools from preferences.
     * 
     * @return default limit
     */
    public int getDefaultLimit()
    {
        return getPreferenceStore().getInt(
            com.ditrix.edt.mcp.server.preferences.PreferenceConstants.PREF_DEFAULT_LIMIT);
    }

    /**
     * Returns the maximum result limit for tools from preferences.
     * 
     * @return max limit
     */
    public int getMaxLimit()
    {
        return getPreferenceStore().getInt(
            com.ditrix.edt.mcp.server.preferences.PreferenceConstants.PREF_MAX_LIMIT);
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
     * Logs a debug message.
     * Only logs if debug mode is enabled (via .options file or preference).
     * 
     * @param message the debug message
     */
    public static void logDebug(String message)
    {
        // Debug logging is currently disabled to avoid excessive output.
        // Uncomment for troubleshooting:
        // if (plugin != null && plugin.isDebugging())
        // {
        //     plugin.getLog().log(new Status(IStatus.INFO, PLUGIN_ID, "[DEBUG] " + message));
        // }
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
