/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.lifecycle.IServicesOrchestrator;
import com._1c.g5.v8.dt.md.MdPlugin;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.navigator.providers.INavigatorContentProviderStateProvider;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.v8.dt.check.ICheckScheduler;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.google.inject.Injector;

/**
 * Holds the OSGi {@link ServiceTracker}s for the EDT platform services the MCP
 * tools consume, plus the typed getters around them.
 * <p>
 * Extracted from {@link Activator} so the activator only orchestrates lifecycle.
 * {@link Activator} keeps its static/instance {@code getXxx} convenience methods
 * and delegates each one to this class, so existing call sites are unchanged.
 */
public class EdtServices
{
    /** Service trackers */
    private ServiceTracker<IV8ProjectManager, IV8ProjectManager> v8ProjectManagerTracker;
    private ServiceTracker<IDtProjectManager, IDtProjectManager> dtProjectManagerTracker;
    private ServiceTracker<IConfigurationProvider, IConfigurationProvider> configurationProviderTracker;
    private ServiceTracker<IMarkerManager, IMarkerManager> markerManagerTracker;
    private ServiceTracker<ICheckScheduler, ICheckScheduler> checkSchedulerTracker;
    private ServiceTracker<ICheckRepository, ICheckRepository> checkRepositoryTracker;
    private ServiceTracker<IBmModelManager, IBmModelManager> bmModelManagerTracker;
    private ServiceTracker<IDerivedDataManagerProvider, IDerivedDataManagerProvider> derivedDataManagerProviderTracker;
    private ServiceTracker<IServicesOrchestrator, IServicesOrchestrator> servicesOrchestratorTracker;
    private ServiceTracker<BmAwareResourceSetProvider, BmAwareResourceSetProvider> resourceSetProviderTracker;
    private ServiceTracker<IApplicationManager, IApplicationManager> applicationManagerTracker;
    private ServiceTracker<INavigatorContentProviderStateProvider, INavigatorContentProviderStateProvider> navigatorStateProviderTracker;
    private ServiceTracker<IMdRefactoringService, IMdRefactoringService> mdRefactoringServiceTracker;
    /**
     * EDT workspace CLI APIs are tracked by String class name and invoked via
     * reflection from the tools, keeping this bundle build-independent of
     * com._1c.g5.v8.dt.cli.api.
     */
    private ServiceTracker<Object, Object> exportConfigurationFilesApiTracker;
    private ServiceTracker<Object, Object> importConfigurationFilesApiTracker;

    /**
     * LanguageTool CLI APIs are tracked by String class name to keep this
     * bundle build-independent of the com.e1c.langtool.* bundles (LanguageTool
     * is installed separately via Help -&gt; Install New Software on both EDT
     * 2025.x and 2026.1; not bundled with the EDT base distribution). All
     * invocations on the returned services go through reflection — see
     * GenerateTranslationStringsTool, TranslateConfigurationTool, and
     * GetTranslationProjectInfoTool.
     */
    private ServiceTracker<Object, Object> generateTranslationStringsApiTracker;
    private ServiceTracker<Object, Object> synchronizeProjectApiTracker;
    private ServiceTracker<Object, Object> projectInformationApiTracker;

    /**
     * Opens all service trackers in the same order the activator used to open
     * them. Behaviour-preserving extraction of the tracker initialization block
     * from {@code Activator.start(BundleContext)}.
     *
     * @param context the bundle context, must not be {@code null}
     */
    public void init(BundleContext context)
    {
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

        checkRepositoryTracker = new ServiceTracker<>(context, ICheckRepository.class, null);
        checkRepositoryTracker.open();

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

        mdRefactoringServiceTracker = new ServiceTracker<>(context, IMdRefactoringService.class, null);
        mdRefactoringServiceTracker.open();

        exportConfigurationFilesApiTracker = new ServiceTracker<>(
            context, "com._1c.g5.v8.dt.cli.api.workspace.IExportConfigurationFilesApi", null); //$NON-NLS-1$
        exportConfigurationFilesApiTracker.open();

        importConfigurationFilesApiTracker = new ServiceTracker<>(
            context, "com._1c.g5.v8.dt.cli.api.workspace.IImportConfigurationFilesApi", null); //$NON-NLS-1$
        importConfigurationFilesApiTracker.open();

        generateTranslationStringsApiTracker = new ServiceTracker<>(
            context, "com.e1c.langtool.v8.dt.cli.api.IGenerateTranslationStringsApi", null); //$NON-NLS-1$
        generateTranslationStringsApiTracker.open();

        synchronizeProjectApiTracker = new ServiceTracker<>(
            context, "com.e1c.langtool.v8.dt.cli.api.ISynchronizeProjectApi", null); //$NON-NLS-1$
        synchronizeProjectApiTracker.open();

        projectInformationApiTracker = new ServiceTracker<>(
            context, "com.e1c.langtool.v8.dt.cli.api.IProjectInformationApi", null); //$NON-NLS-1$
        projectInformationApiTracker.open();
    }

    /**
     * Closes all service trackers in the same order and the same way the
     * activator used to close them. Behaviour-preserving extraction of the
     * tracker close block from {@code Activator.stop(BundleContext)}.
     */
    public void dispose()
    {
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
        if (checkRepositoryTracker != null)
        {
            checkRepositoryTracker.close();
            checkRepositoryTracker = null;
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
        if (mdRefactoringServiceTracker != null)
        {
            mdRefactoringServiceTracker.close();
            mdRefactoringServiceTracker = null;
        }
        if (exportConfigurationFilesApiTracker != null)
        {
            exportConfigurationFilesApiTracker.close();
            exportConfigurationFilesApiTracker = null;
        }
        if (importConfigurationFilesApiTracker != null)
        {
            importConfigurationFilesApiTracker.close();
            importConfigurationFilesApiTracker = null;
        }
        if (generateTranslationStringsApiTracker != null)
        {
            generateTranslationStringsApiTracker.close();
            generateTranslationStringsApiTracker = null;
        }
        if (synchronizeProjectApiTracker != null)
        {
            synchronizeProjectApiTracker.close();
            synchronizeProjectApiTracker = null;
        }
        if (projectInformationApiTracker != null)
        {
            projectInformationApiTracker.close();
            projectInformationApiTracker = null;
        }
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
     * Returns the ICheckRepository service for accessing check registry.
     * Used for converting short UIDs to symbolic check IDs.
     *
     * @return check repository or null if not available
     */
    public ICheckRepository getCheckRepository()
    {
        if (checkRepositoryTracker == null)
        {
            return null;
        }
        return checkRepositoryTracker.getService();
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
     * Returns the IMdRefactoringService for metadata rename/delete refactoring.
     *
     * @return refactoring service or null if not available
     */
    public IMdRefactoringService getMdRefactoringService()
    {
        if (mdRefactoringServiceTracker == null)
        {
            return null;
        }
        return mdRefactoringServiceTracker.getService();
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
        try
        {
            MdPlugin mdPlugin = MdPlugin.getDefault();
            if (mdPlugin != null)
            {
                Injector injector = mdPlugin.getInjector();
                if (injector != null)
                {
                    return injector.getInstance(IModelObjectFactory.class);
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to obtain MD IModelObjectFactory from MdPlugin injector", e); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Returns the com._1c.g5.v8.dt.cli.api.workspace.IExportConfigurationFilesApi
     * (EDT "Export → Configuration to XML Files" action) — typed as
     * {@code Object}, callers invoke via reflection. Returns null when
     * the underlying CLI API plugin is not installed.
     *
     * @return export configuration files API (as Object) or null if not available
     */
    public Object getExportConfigurationFilesApi()
    {
        if (exportConfigurationFilesApiTracker == null)
        {
            return null;
        }
        return exportConfigurationFilesApiTracker.getService();
    }

    /**
     * Returns the com._1c.g5.v8.dt.cli.api.workspace.IImportConfigurationFilesApi
     * (EDT "Import → Configuration from XML Files" action) — typed as
     * {@code Object}, callers invoke via reflection. Returns null when
     * the underlying CLI API plugin is not installed.
     *
     * @return import configuration files API (as Object) or null if not available
     */
    public Object getImportConfigurationFilesApi()
    {
        if (importConfigurationFilesApiTracker == null)
        {
            return null;
        }
        return importConfigurationFilesApiTracker.getService();
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
        if (generateTranslationStringsApiTracker == null)
        {
            return null;
        }
        return generateTranslationStringsApiTracker.getService();
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
        if (synchronizeProjectApiTracker == null)
        {
            return null;
        }
        return synchronizeProjectApiTracker.getService();
    }

    /**
     * Returns the com.e1c.langtool.v8.dt.cli.api.IProjectInformationApi —
     * typed as {@code Object}, callers invoke via reflection. Returns null
     * when LanguageTool is not installed.
     *
     * @return project information API (as Object) or null if not available
     */
    public Object getProjectInformationApi()
    {
        if (projectInformationApiTracker == null)
        {
            return null;
        }
        return projectInformationApiTracker.getService();
    }
}
