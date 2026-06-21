/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.util.tracker.ServiceTracker;

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
import com._1c.g5.v8.dt.md.MdPlugin;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.md.ui.presentation.IPresentationService;
import com._1c.g5.v8.dt.navigator.providers.INavigatorContentProviderStateProvider;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.wiring.ServiceProperties;
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
    private ServiceTracker<IInfobaseManager, IInfobaseManager> infobaseManagerTracker;
    private ServiceTracker<IInfobaseAssociationManager, IInfobaseAssociationManager> infobaseAssociationManagerTracker;
    private ServiceTracker<INavigatorContentProviderStateProvider, INavigatorContentProviderStateProvider> navigatorStateProviderTracker;
    private ServiceTracker<IMdRefactoringService, IMdRefactoringService> mdRefactoringServiceTracker;
    private ServiceTracker<IPresentationService, IPresentationService> presentationServiceTracker;
    private ServiceTracker<ITopObjectFqnGenerator, ITopObjectFqnGenerator> topObjectFqnGeneratorTracker;
    private ServiceTracker<IExtensionProjectManager, IExtensionProjectManager> extensionProjectManagerTracker;
    private ServiceTracker<IConfigurationProjectManager, IConfigurationProjectManager> configurationProjectManagerTracker;
    private ServiceTracker<IExternalObjectProjectManager, IExternalObjectProjectManager> externalObjectProjectManagerTracker;

    /**
     * The FORM-model {@link IModelObjectFactory}, tracked with an LDAP filter on the EDT wiring
     * service-name property: {@code FormPlugin.start()} registers the form bundle's factory as an
     * OSGi {@code IModelObjectFactory} service with
     * {@code ServiceProperties.named("FormModelObjectFactory")} (= {@code service.name}); the filter
     * selects exactly that one among the many per-language {@code IModelObjectFactory} services. See
     * {@link #getFormModelObjectFactory()}.
     */
    private ServiceTracker<IModelObjectFactory, IModelObjectFactory> formModelObjectFactoryTracker;
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
     * EDT's debug-server target manager, tracked by String class name and called
     * via reflection — keeps this bundle independent of the (possibly internal)
     * debug-core manager type and degrades gracefully when the service is not
     * registered. Enumerates 1C debug-server targets, including sessions started
     * from the EDT UI ("Debug As") that the generic {@code ILaunchManager} view
     * does not surface.
     */
    private ServiceTracker<Object, Object> runtimeDebugClientTargetManagerTracker;

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

        infobaseManagerTracker = new ServiceTracker<>(context, IInfobaseManager.class, null);
        infobaseManagerTracker.open();

        infobaseAssociationManagerTracker =
            new ServiceTracker<>(context, IInfobaseAssociationManager.class, null);
        infobaseAssociationManagerTracker.open();

        navigatorStateProviderTracker = new ServiceTracker<>(context, INavigatorContentProviderStateProvider.class, null);
        navigatorStateProviderTracker.open();

        mdRefactoringServiceTracker = new ServiceTracker<>(context, IMdRefactoringService.class, null);
        mdRefactoringServiceTracker.open();

        presentationServiceTracker = new ServiceTracker<>(context, IPresentationService.class, null);
        presentationServiceTracker.open();

        topObjectFqnGeneratorTracker = new ServiceTracker<>(context, ITopObjectFqnGenerator.class, null);
        topObjectFqnGeneratorTracker.open();

        extensionProjectManagerTracker = new ServiceTracker<>(context, IExtensionProjectManager.class, null);
        extensionProjectManagerTracker.open();

        configurationProjectManagerTracker = new ServiceTracker<>(context, IConfigurationProjectManager.class, null);
        configurationProjectManagerTracker.open();

        externalObjectProjectManagerTracker = new ServiceTracker<>(context, IExternalObjectProjectManager.class, null);
        externalObjectProjectManagerTracker.open();

        try
        {
            Filter formFactoryFilter = context.createFilter("(&(objectClass=" //$NON-NLS-1$
                + IModelObjectFactory.class.getName() + ")(" + ServiceProperties.SERVICE_NAME //$NON-NLS-1$
                + "=" + FORM_MODEL_OBJECT_FACTORY_SERVICE_NAME + "))"); //$NON-NLS-1$ //$NON-NLS-2$
            formModelObjectFactoryTracker = new ServiceTracker<>(context, formFactoryFilter, null);
            formModelObjectFactoryTracker.open();
        }
        catch (InvalidSyntaxException e)
        {
            Activator.logError("Invalid form IModelObjectFactory service filter", e); //$NON-NLS-1$
        }

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

        runtimeDebugClientTargetManagerTracker = new ServiceTracker<>(
            context, "com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTargetManager", null); //$NON-NLS-1$
        runtimeDebugClientTargetManagerTracker.open();
    }

    /**
     * Closes all service trackers in the same order and the same way the
     * activator used to close them. Behaviour-preserving extraction of the
     * tracker close block from {@code Activator.stop(BundleContext)}.
     */
    public void dispose()
    {
        // Close service trackers (each closeTracker() closes when non-null and returns null,
        // exactly reproducing the former "if (t != null) { t.close(); t = null; }" per-field block). // NOSONAR explanatory comment, not commented-out code
        v8ProjectManagerTracker = closeTracker(v8ProjectManagerTracker);
        dtProjectManagerTracker = closeTracker(dtProjectManagerTracker);
        configurationProviderTracker = closeTracker(configurationProviderTracker);
        markerManagerTracker = closeTracker(markerManagerTracker);
        checkSchedulerTracker = closeTracker(checkSchedulerTracker);
        checkRepositoryTracker = closeTracker(checkRepositoryTracker);
        bmModelManagerTracker = closeTracker(bmModelManagerTracker);
        derivedDataManagerProviderTracker = closeTracker(derivedDataManagerProviderTracker);
        servicesOrchestratorTracker = closeTracker(servicesOrchestratorTracker);
        resourceSetProviderTracker = closeTracker(resourceSetProviderTracker);
        applicationManagerTracker = closeTracker(applicationManagerTracker);
        infobaseManagerTracker = closeTracker(infobaseManagerTracker);
        infobaseAssociationManagerTracker = closeTracker(infobaseAssociationManagerTracker);
        navigatorStateProviderTracker = closeTracker(navigatorStateProviderTracker);
        mdRefactoringServiceTracker = closeTracker(mdRefactoringServiceTracker);
        presentationServiceTracker = closeTracker(presentationServiceTracker);
        topObjectFqnGeneratorTracker = closeTracker(topObjectFqnGeneratorTracker);
        extensionProjectManagerTracker = closeTracker(extensionProjectManagerTracker);
        configurationProjectManagerTracker = closeTracker(configurationProjectManagerTracker);
        externalObjectProjectManagerTracker = closeTracker(externalObjectProjectManagerTracker);
        formModelObjectFactoryTracker = closeTracker(formModelObjectFactoryTracker);
        exportConfigurationFilesApiTracker = closeTracker(exportConfigurationFilesApiTracker);
        importConfigurationFilesApiTracker = closeTracker(importConfigurationFilesApiTracker);
        generateTranslationStringsApiTracker = closeTracker(generateTranslationStringsApiTracker);
        synchronizeProjectApiTracker = closeTracker(synchronizeProjectApiTracker);
        projectInformationApiTracker = closeTracker(projectInformationApiTracker);
        runtimeDebugClientTargetManagerTracker = closeTracker(runtimeDebugClientTargetManagerTracker);
    }

    /**
     * Closes the given service tracker when it is non-{@code null} and returns {@code null} so the
     * caller can clear its field. Behaviour-identical to the former inline
     * {@code if (tracker != null) { tracker.close(); tracker = null; }} block.
     *
     * @param <S> the tracker's tracked-service type
     * @param <T> the tracker's tracked-object type
     * @param tracker the tracker to close, may be {@code null}
     * @return {@code null} always
     */
    private static <S, T> ServiceTracker<S, T> closeTracker(ServiceTracker<S, T> tracker)
    {
        if (tracker != null)
        {
            tracker.close();
        }
        return null;
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
     * Returns the IInfobaseManager service for managing the global infobases list.
     * Used by create_infobase and delete_infobase to generate names and
     * deregister infobases from the EDT Infobases list.
     *
     * @return infobase manager or null if not available
     */
    public IInfobaseManager getInfobaseManager()
    {
        if (infobaseManagerTracker == null)
        {
            return null;
        }
        return infobaseManagerTracker.getService();
    }

    /**
     * Returns the IInfobaseAssociationManager service for binding infobases to
     * configuration projects (and removing those bindings).
     * Used by create_infobase and delete_infobase.
     *
     * @return infobase association manager or null if not available
     */
    public IInfobaseAssociationManager getInfobaseAssociationManager()
    {
        if (infobaseAssociationManagerTracker == null)
        {
            return null;
        }
        return infobaseAssociationManagerTracker.getService();
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
     * Returns the moxel {@link IPresentationService} used to build a SpreadsheetDocument render
     * control (style/picture lookup). It is the md.ui Guice singleton, registered as an OSGi service
     * by the EDT wiring framework. Used by the template screenshot to render a SpreadsheetDocument
     * off-screen without opening an editor.
     *
     * @return the presentation service, or {@code null} if not available
     */
    public IPresentationService getPresentationService()
    {
        if (presentationServiceTracker == null)
        {
            return null;
        }
        return presentationServiceTracker.getService();
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

    /** Symbolic name of the EDT form bundle that contributes the form-model object factory. */
    private static final String FORM_BUNDLE_ID = "com._1c.g5.v8.dt.form"; //$NON-NLS-1$

    /**
     * The wiring service name ({@code service.name} OSGi property / Guice binding annotation) the
     * form bundle registers its {@link IModelObjectFactory} under (see
     * {@code FormRuntimeModule.configureIModelObjectFactory} and {@code FormPlugin.start()}).
     */
    private static final String FORM_MODEL_OBJECT_FACTORY_SERVICE_NAME = "FormModelObjectFactory"; //$NON-NLS-1$

    /**
     * Public, exported service class that lives in the form bundle. Loading it
     * <em>through the form bundle</em> trips the bundle's lazy activation
     * ({@code Bundle-ActivationPolicy: lazy}), so {@code FormPlugin.start()} runs
     * and registers the form services (including the form-model object factory).
     */
    private static final String FORM_SERVICE_CLASS =
        "com._1c.g5.v8.dt.form.service.FormItemInformationService"; //$NON-NLS-1$

    /**
     * Returns the {@link IModelObjectFactory} that creates <em>form-model</em>
     * objects (everything under {@code com._1c.g5.v8.dt.form.model}: the content
     * {@code Form}, {@code AutoCommandBar}, {@code FormCommandInterface}, …) with
     * EDT default content — the same factory
     * ({@code com._1c.g5.v8.dt.form.FormObjectFactory}) the "New form" wizard uses.
     * <p>
     * This is a <strong>different</strong> factory from
     * {@link #getModelObjectFactory()}: that one is bound in the MD language
     * injector and only knows the {@code mdclass} EPackage (Catalog, Document, …);
     * it cannot create form-model objects. A {@code Form} built with the MD factory
     * (or a bare EFactory create on the form EPackage) is missing the
     * predefined {@code autoCommandBar} that EDT's WYSIWYG layout generator
     * ({@code HippoGenerator.readElement}) unconditionally reads for a
     * {@code CommandBarHolder}; when it is {@code null} the generator throws and the
     * form never renders. Creating the form through this factory (or, as a fallback,
     * seeding the command bar manually — see {@code FormElementWriter.createForm})
     * makes it render.
     * <p>
     * The factory is resolved as the OSGi {@code IModelObjectFactory} service carrying
     * {@code service.name=FormModelObjectFactory} (the property {@code FormPlugin.start()}
     * registers it with) via the filtered {@link #formModelObjectFactoryTracker}. The form
     * injector binds {@code IModelObjectFactory} <em>only</em> with the
     * {@code Names.named("FormModelObjectFactory")} annotation — a plain
     * {@code injector.getInstance(IModelObjectFactory.class)} throws
     * {@code ConfigurationException}, which is why the previous injector-based lookup always
     * failed and the manual command-bar fallback was used for every created form. The service
     * route needs no reflection on the internal {@code FormPlugin} at all. Returns {@code null}
     * when the tracker is not initialized or the form bundle/service is not available.
     *
     * @return the form-model object factory, or {@code null} if unavailable
     */
    public IModelObjectFactory getFormModelObjectFactory()
    {
        if (formModelObjectFactoryTracker == null)
        {
            return null;
        }
        IModelObjectFactory factory = formModelObjectFactoryTracker.getService();
        if (factory == null)
        {
            // The form bundle is lazily activated and registers its services in start(); // NOSONAR explanatory comment, not commented-out code
            // trip the activation, then re-read the tracker.
            Bundle formBundle = Platform.getBundle(FORM_BUNDLE_ID);
            if (formBundle == null)
            {
                Activator.logError("form bundle '" + FORM_BUNDLE_ID //$NON-NLS-1$
                    + "' not found in the running platform", null); //$NON-NLS-1$
                return null;
            }
            ensureFormBundleActive(formBundle);
            factory = formModelObjectFactoryTracker.getService();
        }
        if (factory == null)
        {
            Activator.logError("Form IModelObjectFactory service (" + ServiceProperties.SERVICE_NAME //$NON-NLS-1$
                + "=" + FORM_MODEL_OBJECT_FACTORY_SERVICE_NAME + ") is not available", null); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return factory;
    }

    /**
     * Ensures the lazily-activated form bundle is started. First loads an exported
     * class through the bundle (the standard lazy-activation trigger), then, if the
     * bundle is still not {@code ACTIVE}, calls {@code start(START_TRANSIENT)}.
     *
     * @param formBundle the form bundle
     */
    private static void ensureFormBundleActive(Bundle formBundle)
    {
        try
        {
            // Touch an exported class to trip Bundle-ActivationPolicy: lazy.
            formBundle.loadClass(FORM_SERVICE_CLASS);
        }
        catch (Throwable e)
        {
            // Fall through to the explicit start below.
        }
        if (formBundle.getState() != Bundle.ACTIVE)
        {
            try
            {
                formBundle.start(Bundle.START_TRANSIENT);
            }
            catch (Throwable e)
            {
                // Best-effort: getFormModelObjectFactory reports a clear null below.
            }
        }
    }

    /**
     * Returns the {@link ITopObjectFqnGenerator} service used to compute the
     * canonical BM top-object FQN for external-property objects (e.g. the content
     * {@code Form} referenced by a {@code BasicForm}).
     * <p>
     * This is the same generator EDT's own form infrastructure uses (see
     * {@code com._1c.g5.v8.dt.form.service.common.impl.ExtInfoManagementService}).
     * Using it guarantees the content form is attached under the FQN the BM
     * namespace/store layer expects, so the object resolves on subsequent lookups
     * instead of failing with "No store with '&lt;id&gt;' is assigned to namespace".
     *
     * @return the top-object FQN generator, or {@code null} if not available
     */
    public ITopObjectFqnGenerator getTopObjectFqnGenerator()
    {
        if (topObjectFqnGeneratorTracker == null)
        {
            return null;
        }
        return topObjectFqnGeneratorTracker.getService();
    }

    /**
     * Returns the {@link IExtensionProjectManager} service used to create
     * 1C configuration extension projects programmatically (the MCP counterpart
     * of the EDT "New Extension" wizard).
     *
     * @return extension project manager or null if not available
     */
    public IExtensionProjectManager getExtensionProjectManager()
    {
        if (extensionProjectManagerTracker == null)
        {
            return null;
        }
        return extensionProjectManagerTracker.getService();
    }

    /**
     * Returns the {@link IConfigurationProjectManager} service used to create
     * 1C standalone configuration projects programmatically (the MCP counterpart
     * of the EDT "New Configuration" wizard).
     *
     * @return configuration project manager or null if not available
     */
    public IConfigurationProjectManager getConfigurationProjectManager()
    {
        if (configurationProjectManagerTracker == null)
        {
            return null;
        }
        return configurationProjectManagerTracker.getService();
    }

    /**
     * Returns the {@link IExternalObjectProjectManager} service used to create
     * 1C external data processors/reports projects programmatically (the MCP
     * counterpart of the EDT "New External Data Processor" wizard).
     *
     * @return external object project manager or null if not available
     */
    public IExternalObjectProjectManager getExternalObjectProjectManager()
    {
        if (externalObjectProjectManagerTracker == null)
        {
            return null;
        }
        return externalObjectProjectManagerTracker.getService();
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

    /**
     * Returns the EDT {@code IRuntimeDebugClientTargetManager} — typed as
     * {@code Object} to avoid a compile-time / Import-Package dependency on the
     * (possibly internal) debug-core manager type — looked up as an OSGi service
     * by class name and called via reflection ({@code listDebugTargets()}).
     * <p>
     * EDT's wiring framework registers its Guice singletons as OSGi services, so
     * the state-bearing manager singleton is reachable via the service tracker
     * without touching the non-exported {@code DebugCorePlugin} (which, unlike
     * {@code FormPlugin}/{@code MdPlugin}, exposes no {@code getInjector()}). This
     * manager enumerates debug-server targets, including sessions a user started
     * from the EDT UI ("Debug As"), which the generic
     * {@link org.eclipse.debug.core.ILaunchManager} view does not surface.
     *
     * @return the manager instance (as Object), or {@code null} if the service is
     *         not registered (e.g. headless test runtime or an EDT build that does
     *         not register it)
     */
    public Object getRuntimeDebugClientTargetManager()
    {
        if (runtimeDebugClientTargetManagerTracker == null)
        {
            return null;
        }
        return runtimeDebugClientTargetManagerTracker.getService();
    }
}
