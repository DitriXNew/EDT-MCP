/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.lifecycle.IServicesOrchestrator;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.navigator.providers.INavigatorContentProviderStateProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.utils.Log;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.v8.dt.check.ICheckScheduler;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

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

    /**
     * EDT platform service access (OSGi service trackers + their getters).
     * The activator delegates each {@code getXxx} convenience method to this.
     */
    private final EdtServices services = new EdtServices();

    /**
     * Startup/shutdown orchestration (group service, tag filter, navigator
     * toolbar, update checker scheduler). Owns the group service reference;
     * the activator delegates {@link #getGroupService()} to it.
     */
    private final StartupOrchestrator orchestrator = new StartupOrchestrator();

    @Override
    @SuppressWarnings("java:S2696")
    public void start(BundleContext context) throws Exception
    {
        super.start(context);
        plugin = this;
        mcpServer = new McpServer();

        // In Tycho headless test runtime, avoid eager workspace/UI/platform initialization.
        // This prevents background platform startup races that can fail the test process.
        if (isHeadless())
        {
            logInfo("EDT MCP Server plugin started in headless mode (startup integrations skipped)"); //$NON-NLS-1$
            return;
        }

        // Register tools eagerly so descriptions are available in the preferences UI
        // even if the MCP server has not been started yet.
        mcpServer.registerTools();

        // Initialize service trackers
        services.init(context);

        // Run startup orchestration (group service + UI integrations) in the
        // same order as before.
        orchestrator.start(isHeadless());

        logInfo("EDT MCP Server plugin started"); //$NON-NLS-1$
    }

    @Override
    @SuppressWarnings("java:S2696")
    public void stop(BundleContext context) throws Exception
    {
        if (mcpServer != null && mcpServer.isRunning())
        {
            mcpServer.stop();
        }
        
        // Close service trackers
        services.dispose();

        // Run shutdown orchestration (UI teardown + group service + update
        // checker scheduler) in the same order as before.
        orchestrator.stop(isHeadless());

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
        return services.getV8ProjectManager();
    }

    /**
     * Returns the IDtProjectManager service.
     * 
     * @return DT project manager or null if not available
     */
    public IDtProjectManager getDtProjectManager()
    {
        return services.getDtProjectManager();
    }

    /**
     * Returns the IConfigurationProvider service.
     * 
     * @return configuration provider or null if not available
     */
    public IConfigurationProvider getConfigurationProvider()
    {
        return services.getConfigurationProvider();
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
        return services.getMarkerManager();
    }
    
    /**
     * Returns the ICheckScheduler service for scheduling EDT validations.
     * 
     * @return check scheduler or null if not available
     */
    public ICheckScheduler getCheckScheduler()
    {
        return services.getCheckScheduler();
    }
    
    /**
     * Returns the ICheckRepository service for accessing check registry.
     * Used for converting short UIDs to symbolic check IDs.
     * 
     * @return check repository or null if not available
     */
    public ICheckRepository getCheckRepository()
    {
        return services.getCheckRepository();
    }
    
    /**
     * Returns the IBmModelManager service for BM model operations.
     * 
     * @return BM model manager or null if not available
     */
    public IBmModelManager getBmModelManager()
    {
        return services.getBmModelManager();
    }
    
    /**
     * Returns the IDerivedDataManagerProvider service for derived data operations.
     * Used for waiting for validation and other derived data computations.
     * 
     * @return derived data manager provider or null if not available
     */
    public IDerivedDataManagerProvider getDerivedDataManagerProvider()
    {
        return services.getDerivedDataManagerProvider();
    }
    
    /**
     * Returns the IServicesOrchestrator service for lifecycle management.
     * Used for waiting for project context lifecycle events.
     * 
     * @return services orchestrator or null if not available
     */
    public IServicesOrchestrator getServicesOrchestrator()
    {
        return services.getServicesOrchestrator();
    }
    
    /**
     * Returns the BmAwareResourceSetProvider service for resolving EMF proxies.
     * Used for resolving platform type proxies in content assist.
     * 
     * @return resource set provider or null if not available
     */
    public BmAwareResourceSetProvider getResourceSetProvider()
    {
        return services.getResourceSetProvider();
    }
    
    /**
     * Returns the IApplicationManager service for managing applications.
     * Used for application lifecycle operations (update, start, etc.).
     *
     * @return application manager or null if not available
     */
    public IApplicationManager getApplicationManager()
    {
        return services.getApplicationManager();
    }

    /**
     * Returns the IInfobaseManager service for managing the global infobases list.
     * Used by create_infobase and delete_infobase.
     *
     * @return infobase manager or null if not available
     */
    public IInfobaseManager getInfobaseManager()
    {
        return services.getInfobaseManager();
    }

    /**
     * Returns the IInfobaseAssociationManager service for binding/unbinding infobases
     * to configuration projects.
     * Used by create_infobase and delete_infobase.
     *
     * @return infobase association manager or null if not available
     */
    public IInfobaseAssociationManager getInfobaseAssociationManager()
    {
        return services.getInfobaseAssociationManager();
    }

    /**
     * Returns the INavigatorContentProviderStateProvider service.
     * Used for controlling navigator content filtering state.
     * 
     * @return navigator state provider or null if not available
     */
    public INavigatorContentProviderStateProvider getNavigatorStateProvider()
    {
        return services.getNavigatorStateProvider();
    }
    
    /**
     * Returns the IMdRefactoringService for metadata rename/delete refactoring.
     * 
     * @return refactoring service or null if not available
     */
    public IMdRefactoringService getMdRefactoringService()
    {
        return services.getMdRefactoringService();
    }

    /**
     * Returns the {@link IExtensionProjectManager} service used to create
     * 1C configuration extension projects programmatically.
     *
     * @return extension project manager or null if not available
     */
    public IExtensionProjectManager getExtensionProjectManager()
    {
        return services.getExtensionProjectManager();
    }

    /**
     * Returns the {@link IConfigurationProjectManager} service used to create
     * 1C standalone configuration projects programmatically.
     *
     * @return configuration project manager or null if not available
     */
    public IConfigurationProjectManager getConfigurationProjectManager()
    {
        return services.getConfigurationProjectManager();
    }

    /**
     * Returns the {@link IExternalObjectProjectManager} service used to create
     * 1C external data processors/reports projects programmatically.
     *
     * @return external object project manager or null if not available
     */
    public IExternalObjectProjectManager getExternalObjectProjectManager()
    {
        return services.getExternalObjectProjectManager();
    }

    /**
     * Returns the IModelObjectFactory used to create metadata (mdclass) objects
     * with EDT default content (the same factory the "New" wizards use).
     * <p>
     * IMPORTANT: {@link IModelObjectFactory} is contributed by several language
     * plugins (one factory per language/EPackage). A plain OSGi service lookup
     * (ServiceTracker / ServiceAccess) returns an arbitrary implementation —
     * in practice the GeographicalSchemaObjectFactory — which cannot create
     * mdclass objects (Catalog, Document, CommonModule, ...) and throws an
     * uncaught "not a valid classifier" exception. We therefore resolve the
     * factory strictly from the MD language Guice injector, which binds
     * IModelObjectFactory to com._1c.g5.v8.dt.md.model.MdObjectFactory.
     *
     * @return MD model object factory or null if not available
     */
    public IModelObjectFactory getModelObjectFactory()
    {
        return services.getModelObjectFactory();
    }

    /**
     * Returns the IModelObjectFactory that creates form-model objects (the content
     * {@code Form}, {@code AutoCommandBar}, …) with EDT default content — the same
     * factory the "New form" wizard uses. Distinct from
     * {@link #getModelObjectFactory()} (which only knows the mdclass EPackage).
     *
     * @return form-model object factory or null if not available
     */
    public IModelObjectFactory getFormModelObjectFactory()
    {
        return services.getFormModelObjectFactory();
    }

    /**
     * Returns the {@link ITopObjectFqnGenerator} used to compute the canonical BM
     * top-object FQN for external-property objects (e.g. a {@code BasicForm}'s
     * content {@code Form}).
     *
     * @return the top-object FQN generator, or null if not available
     */
    public ITopObjectFqnGenerator getTopObjectFqnGenerator()
    {
        return services.getTopObjectFqnGenerator();
    }

    /**
     * Returns the com._1c.g5.v8.dt.cli.api.workspace.IExportConfigurationFilesApi
     * (EDT "Export → Configuration to XML Files" action) — typed as
     * {@code Object}, callers invoke via reflection. Returns null when
     * the underlying CLI API plugin is not installed.
     */
    public Object getExportConfigurationFilesApi()
    {
        return services.getExportConfigurationFilesApi();
    }

    /**
     * Returns the com._1c.g5.v8.dt.cli.api.workspace.IImportConfigurationFilesApi
     * (EDT "Import → Configuration from XML Files" action) — typed as
     * {@code Object}, callers invoke via reflection. Returns null when
     * the underlying CLI API plugin is not installed.
     */
    public Object getImportConfigurationFilesApi()
    {
        return services.getImportConfigurationFilesApi();
    }

    /**
     * Returns the com.e1c.langtool.v8.dt.cli.api.IGenerateTranslationStringsApi
     * used to invoke the LanguageTool translation-strings generator. The
     * action is invoked on the configuration project (V8ConfigurationNature)
     * and writes placeholder keys into the .lstr/.trans/.dict storages
     * declared on the project (each storage routes to either an external
     * dictionary storage project — a plain Eclipse project with the
     * dependentProjectNature — or to the configuration itself).
     *
     * <p>Typed as {@code Object} — callers invoke via reflection so this bundle
     * has no build-time dependency on com.e1c.langtool.*, which is not shipped
     * with EDT 2026.1. Returns null when LanguageTool is not installed.
     *
     * @return generator API (as Object) or null if not available
     */
    public Object getGenerateTranslationStringsApi()
    {
        return services.getGenerateTranslationStringsApi();
    }

    /**
     * Returns the com.e1c.langtool.v8.dt.cli.api.ISynchronizeProjectApi used to
     * invoke the LanguageTool "Translate configuration" action (propagates
     * dictionary changes from the source project to all its dependent
     * translation projects, producing the translated artifacts).
     *
     * <p>Typed as {@code Object} — callers invoke via reflection so this bundle
     * has no build-time dependency on com.e1c.langtool.*, which is not shipped
     * with EDT 2026.1. Returns null when LanguageTool is not installed.
     *
     * @return synchronize project API (as Object) or null if not available
     */
    public Object getSynchronizeProjectApi()
    {
        return services.getSynchronizeProjectApi();
    }

    /**
     * Returns the com.e1c.langtool.v8.dt.cli.api.IProjectInformationApi —
     * typed as {@code Object}, callers invoke via reflection. Returns null
     * when LanguageTool is not installed.
     */
    public Object getProjectInformationApi()
    {
        return services.getProjectInformationApi();
    }

    /**
     * Returns the EDT {@code IRuntimeDebugClientTargetManager} — typed as
     * {@code Object}, callers invoke {@code listDebugTargets()} via reflection.
     * Used by {@code DebugServerTargetSupport} to surface 1C debug-server targets
     * (including EDT-UI-started "Debug As" sessions) that the generic
     * {@link org.eclipse.debug.core.ILaunchManager} view does not expose. Returns
     * {@code null} when the service is not registered.
     *
     * @return the manager instance (as Object), or {@code null} if not available
     */
    public Object getRuntimeDebugClientTargetManager()
    {
        return services.getRuntimeDebugClientTargetManager();
    }

    /**
     * Returns the IGroupService for group operations.
     * Used for virtual folder groups in the Navigator.
     * 
     * @return group service or null if not available
     */
    public IGroupService getGroupService()
    {
        return orchestrator.getGroupService();
    }
    
    /**
     * Static convenience method to get the group service.
     * 
     * @return group service or null if not available
     */
    public static IGroupService getGroupServiceStatic()
    {
        Activator activator = getDefault();
        return activator != null ? activator.getGroupService() : null;
    }

    /**
     * Logs an info message.
     * 
     * @param message the message
     */
    public static void logInfo(String message)
    {
        Log.info(message);
    }
    
    /**
     * Logs a debug message.
     * Only logs if debug mode is enabled (via .options file or preference).
     * 
     * @param message the debug message
     */
    public static void logDebug(String message)
    {
        Log.debug(message);
    }

    /**
     * Logs a warning message.
     * 
     * @param message the warning message
     */
    public static void logWarning(String message)
    {
        Log.warning(message);
    }

    /**
     * Logs an error.
     * 
     * @param message the message
     * @param e the exception
     */
    public static void logError(String message, Throwable e)
    {
        Log.error(message, e);
    }

    /**
     * Checks if the application is running in headless mode (no UI).
     * 
     * @return true if headless, false otherwise
     */
    private static boolean isHeadless()
    {
        // Check headless indicators without accessing Display.
        // (Display.getDefault() initializes GTK and fails in headless environments.)

        // 1) Eclipse test mode property
        String testSuite = System.getProperty("org.eclipse.ui.testsuite"); //$NON-NLS-1$
        if ("true".equals(testSuite)) //$NON-NLS-1$
        {
            return true;
        }

        // 2) Eclipse application type (Tycho uses headlesstest)
        String eclipseApplication = System.getProperty("eclipse.application"); //$NON-NLS-1$
        if (eclipseApplication != null && eclipseApplication.contains("headless")) //$NON-NLS-1$
        {
            return true;
        }

        // 3) Standard AWT headless flag (if provided by runtime)
        String awtHeadless = System.getProperty("java.awt.headless"); //$NON-NLS-1$
        // Default to false (assume UI is available) unless the AWT headless flag is set.
        return "true".equalsIgnoreCase(awtHeadless); //$NON-NLS-1$
    }
}
