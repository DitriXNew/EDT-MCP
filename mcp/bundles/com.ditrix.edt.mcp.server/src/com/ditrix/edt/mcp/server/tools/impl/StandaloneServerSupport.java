/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com.ditrix.edt.mcp.server.Activator;
import com.e1c.g5.dt.applications.IApplication;

/**
 * Reflective access to the EDT WST standalone-server feature, shared by the standalone-server
 * paths of {@code create_infobase} and {@code delete_infobase}.
 *
 * <p>Everything here is resolved REFLECTIVELY (OSGi service lookup by class NAME + {@code Method.invoke}),
 * with NO {@code Require-Bundle}/{@code Import-Package} on the standalone-server bundles. A hard
 * dependency on {@code com.e1c.g5.v8.dt.platform.standaloneserver.wst.core} would make the whole MCP
 * plugin fail to resolve on a minimal headless EDT (that bundle pulls a transitive snakeyaml the headless
 * CI does not ship — see {@link CreateInfobaseTool}'s history). Reflection keeps the plugin loadable
 * everywhere; the standalone-server paths degrade gracefully with an actionable error when the feature is
 * absent. All call wrappers return {@code null}/{@code false} (never throw) on a missing bundle/service.
 *
 * <p>Method signatures used here are {@code javap}-verified against EDT 2025.2
 * ({@code com.e1c.g5.v8.dt.platform.standaloneserver.wst.core_3.0.0}):
 * {@code IStandaloneServerService#deleteServer(IServer, IProgressMonitor):IStatus},
 * {@code #getServers():List<IServer>}; {@code IServerApplication#getServer():IServer},
 * {@code #getModule():IModule}; {@code StandaloneServerInfobase#getInfobaseId():UUID}.
 */
final class StandaloneServerSupport
{
    /** Application type id of a standalone (WST) server application. */
    static final String WST_SERVER_APP_TYPE = "com.e1c.g5.dt.applications.type.wst-server"; //$NON-NLS-1$

    /** Symbolic name of the bundle that owns the standalone-server WST service. */
    private static final String WST_CORE_BUNDLE_ID =
        "com.e1c.g5.v8.dt.platform.standaloneserver.wst.core"; //$NON-NLS-1$

    /** FQN of the standalone-server service interface (looked up by NAME in the OSGi registry). */
    private static final String SERVICE_CLASS =
        "com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService"; //$NON-NLS-1$

    /** Symbolic name of the WST server-core bundle (owns {@code ServerPlugin}/{@code ModuleFactory}). */
    private static final String WST_SERVER_CORE_BUNDLE_ID = "org.eclipse.wst.server.core"; //$NON-NLS-1$

    /** Internal WST class exposing the module-factory registry. */
    private static final String SERVER_PLUGIN_CLASS =
        "org.eclipse.wst.server.core.internal.ServerPlugin"; //$NON-NLS-1$

    /** Module-factory id of the standalone-server infobase registry (owner of infobases.yaml). */
    private static final String MODULE_FACTORY_ID =
        "com.e1c.g5.v8.dt.platform.standaloneserver.moduleFactoryDelegate"; //$NON-NLS-1$

    /** Plugin id used for synthesized error statuses. */
    private static final String PLUGIN_ID = "com.ditrix.edt.mcp.server"; //$NON-NLS-1$

    /** Outcome of the best-effort infobases.yaml registry cleanup. */
    enum RegistryCleanup
    {
        /** The stale entry was found and removed from the registry. */
        REMOVED,
        /** There was nothing to remove (no entry for this infobase / no infobaseId) — not an error. */
        NOT_PRESENT,
        /** The cleanup could not run (reflective failure); the orphan self-heals on the next restart. */
        FAILED
    }

    private StandaloneServerSupport()
    {
    }

    /**
     * Acquires the {@code IStandaloneServerService} from the OSGi registry BY CLASS NAME (reflectively).
     * Mirrors {@code CreateInfobaseTool.acquireStandaloneServerService()} so the delete path has no
     * compile/bundle dependency on the standalone-server feature. Returns {@code null} when the bundle or
     * service is unavailable (the caller then fails gracefully with an actionable error).
     *
     * @return the service object (call it reflectively), or {@code null} when unavailable
     */
    static Object acquireService()
    {
        try
        {
            Bundle bundle = Platform.getBundle(WST_CORE_BUNDLE_ID);
            if (bundle == null)
            {
                Activator.logError("standalone-server: bundle '" + WST_CORE_BUNDLE_ID //$NON-NLS-1$
                    + "' not found — the EDT standalone-server feature is not installed", null); //$NON-NLS-1$
                return null;
            }
            BundleContext context = bundle.getBundleContext();
            if (context == null)
            {
                try
                {
                    bundle.start(Bundle.START_TRANSIENT);
                    context = bundle.getBundleContext();
                }
                catch (Exception startEx)
                {
                    Activator.logError("standalone-server: could not start the standalone-server bundle", //$NON-NLS-1$
                        startEx);
                }
            }
            if (context == null)
            {
                return null;
            }
            ServiceReference<?> ref = context.getServiceReference(SERVICE_CLASS);
            return ref != null ? context.getService(ref) : null;
        }
        catch (Throwable t)
        {
            Activator.logError("standalone-server: could not acquire the standalone-server service", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reflective {@code IStandaloneServerService.deleteServer(IServer, IProgressMonitor) -> IStatus}.
     * This one call stops the server, removes the WST {@code IServer} registration (servers.xml) and
     * deletes the server config folder (the {@code Серверы/…-config} project folder). It does NOT touch
     * the infobases.yaml registry — see {@link #removeFromInfobaseRegistry}.
     *
     * @return the resulting {@link IStatus} (inspect {@code isOK()}); an ERROR status when the
     *         {@code deleteServer} method itself could not be resolved (so a reflective miss is NOT
     *         mistaken for success). May be {@code null} only if the call returned a non-{@code IStatus}.
     * @throws Exception the real failure cause if {@code deleteServer} itself threw
     */
    static IStatus deleteServer(Object service, Object server, IProgressMonitor monitor) throws Exception
    {
        Method del = findMethod(service.getClass(), "deleteServer", 2); //$NON-NLS-1$
        if (del == null)
        {
            Activator.logError("delete_infobase: IStandaloneServerService.deleteServer not found", null); //$NON-NLS-1$
            return new Status(IStatus.ERROR, PLUGIN_ID,
                "IStandaloneServerService.deleteServer(IServer, IProgressMonitor) was not found in this " //$NON-NLS-1$
                    + "EDT — the standalone-server API may have changed."); //$NON-NLS-1$
        }
        try
        {
            Object status = del.invoke(service, server, monitor);
            return (status instanceof IStatus) ? (IStatus)status : null;
        }
        catch (InvocationTargetException ite)
        {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception)
            {
                throw (Exception)cause;
            }
            throw new IllegalStateException(cause != null ? cause : ite);
        }
    }

    /**
     * Reflective {@code IServerApplication.getServer()} on a wst-server {@link IApplication} — the WST
     * {@code IServer} backing the application. Returns {@code null} on any failure (the caller then falls
     * back to a name scan via {@link #findServerByModuleName}).
     */
    static Object serverOfApplication(IApplication app)
    {
        try
        {
            Method m = app.getClass().getMethod("getServer"); //$NON-NLS-1$
            return m.invoke(app);
        }
        catch (Throwable t)
        {
            Activator.logError("delete_infobase: IServerApplication.getServer() refl failed", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reflective {@code IServerApplication.getModule()} — the {@code StandaloneServerInfobase} module of a
     * wst-server application (used to read the infobaseId for the infobases.yaml cleanup). May be
     * {@code null}.
     */
    static Object moduleOfApplication(IApplication app)
    {
        try
        {
            Method m = app.getClass().getMethod("getModule"); //$NON-NLS-1$
            return m.invoke(app);
        }
        catch (Throwable t)
        {
            Activator.logError("delete_infobase: IServerApplication.getModule() refl failed", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reflective {@code StandaloneServerInfobase.getInfobaseId().toString()} — the key under which the
     * entry is stored in infobases.yaml. Returns {@code null} on failure.
     */
    static String infobaseIdOf(Object standaloneServerInfobaseModule)
    {
        try
        {
            Object id = standaloneServerInfobaseModule.getClass().getMethod("getInfobaseId") //$NON-NLS-1$
                .invoke(standaloneServerInfobaseModule);
            return id != null ? id.toString() : null;
        }
        catch (Throwable t)
        {
            Activator.logError("delete_infobase: could not read standalone-server infobaseId", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reflective {@code StandaloneServerInfobase.getStandaloneServerConfiguration().getDatabase()} →, for a
     * FILE-backed standalone server, {@code FileDatabase.getConfigDirectory()} — the on-disk directory of
     * the served database (= {@code database.path} in config.yaml = the {@code infobaseFile} that was
     * passed to {@code create_infobase}). This is SEPARATE from the {@code Серверы/…-config} folder that
     * {@code deleteServer} removes — {@code deleteServer} never deletes the served database, so this is how
     * {@code deleteDatabaseFiles=true} finds the directory to remove. Must be read BEFORE {@code deleteServer}
     * (the config is torn down after). Returns {@code null} for an RDBMS-backed server (no local directory)
     * or on any reflective failure.
     */
    static String databaseDirOf(Object standaloneServerInfobaseModule)
    {
        try
        {
            Object cfg = standaloneServerInfobaseModule.getClass()
                .getMethod("getStandaloneServerConfiguration").invoke(standaloneServerInfobaseModule); //$NON-NLS-1$
            if (cfg == null)
            {
                return null;
            }
            Object db = cfg.getClass().getMethod("getDatabase").invoke(cfg); //$NON-NLS-1$
            if (db == null)
            {
                return null;
            }
            // A FILE database (and its create-template subclass FileCreateTemplateDatabase) carries the
            // on-disk directory in getConfigDirectory(); an RDBMS database has neither that method nor a
            // local directory. Detect the file kind by the PRESENCE of getConfigDirectory() rather than
            // by the class name: the type is platform-internal and intentionally not imported (no
            // Require-Bundle), so instanceof is impossible, and a name match would be fragile.
            Object dir;
            try
            {
                dir = db.getClass().getMethod("getConfigDirectory").invoke(db); //$NON-NLS-1$
            }
            catch (NoSuchMethodException e)
            {
                // Not a file-backed database — nothing on the local disk to resolve.
                return null;
            }
            return (dir instanceof String) ? (String)dir : null;
        }
        catch (Throwable t)
        {
            Activator.logError("delete_infobase: could not read standalone-server database directory", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Fallback lookup: scans {@code IStandaloneServerService.getServers()} for the {@code IServer} whose
     * module display name equals {@code appName}. Used when {@link #serverOfApplication} fails.
     *
     * @return the matching {@code IServer} object, or {@code null} if none matched
     */
    static Object findServerByModuleName(Object service, String appName)
    {
        try
        {
            Method getServers = findMethod(service.getClass(), "getServers", 0); //$NON-NLS-1$
            if (getServers == null)
            {
                return null;
            }
            Object result = getServers.invoke(service);
            if (!(result instanceof List))
            {
                return null;
            }
            for (Object server : (List<?>)result)
            {
                if (server == null)
                {
                    continue;
                }
                Object modules = server.getClass().getMethod("getModules").invoke(server); //$NON-NLS-1$
                if (modules instanceof Object[])
                {
                    for (Object module : (Object[])modules)
                    {
                        Object name = module.getClass().getMethod("getName").invoke(module); //$NON-NLS-1$
                        if (appName.equals(name))
                        {
                            return server;
                        }
                    }
                }
            }
        }
        catch (Throwable t)
        {
            Activator.logError("delete_infobase: server-by-name scan failed", t); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Best-effort removal of the orphaned infobases.yaml entry that {@code deleteServer} leaves behind
     * (EDT never removes it — a confirmed platform gap). Reflectively reaches the
     * {@code StandaloneServerInfobaseModuleFactoryDelegate} via the WST module-factory registry, removes
     * the entry from its in-memory {@code modules} map by {@code infobaseId}, and re-serializes the
     * remaining entries to infobases.yaml exactly as the delegate's own {@code saveModule} does.
     *
     * <p>NON-FATAL: any failure is logged and ignored — the server itself is already deleted, the app is
     * gone from {@code get_applications}, and the orphan self-heals on the next workbench start (the
     * delegate's {@code initialize()} drops config-less entries from its map).
     *
     * @return {@link RegistryCleanup#REMOVED} if a stale entry was removed, {@link RegistryCleanup#NOT_PRESENT}
     *         when there was nothing to clean (no infobaseId / no matching entry), or
     *         {@link RegistryCleanup#FAILED} on a reflective failure
     */
    static RegistryCleanup removeFromInfobaseRegistry(String infobaseId, IProgressMonitor monitor)
    {
        if (infobaseId == null)
        {
            // We could not read the infobaseId, so we cannot target the entry — report FAILED (honest:
            // "could not clean, self-heals on restart") rather than implying there was nothing to clean.
            return RegistryCleanup.FAILED;
        }
        try
        {
            Bundle wst = Platform.getBundle(WST_SERVER_CORE_BUNDLE_ID);
            if (wst == null)
            {
                return RegistryCleanup.FAILED;
            }
            Class<?> serverPlugin = wst.loadClass(SERVER_PLUGIN_CLASS);
            Object[] factories = (Object[])serverPlugin.getMethod("getModuleFactories").invoke(null); //$NON-NLS-1$
            Object factory = null;
            for (Object f : factories)
            {
                Object id = f.getClass().getMethod("getId").invoke(f); //$NON-NLS-1$
                if (MODULE_FACTORY_ID.equals(id))
                {
                    factory = f;
                    break;
                }
            }
            if (factory == null)
            {
                return RegistryCleanup.FAILED;
            }
            Method getDelegate = findMethod(factory.getClass(), "getDelegate", 1); //$NON-NLS-1$
            if (getDelegate == null)
            {
                return RegistryCleanup.FAILED;
            }
            Object delegate = getDelegate.invoke(factory, monitor);
            if (delegate == null)
            {
                return RegistryCleanup.FAILED;
            }

            Field modulesF = findField(delegate.getClass(), "modules"); //$NON-NLS-1$
            Field mapperF = findField(delegate.getClass(), "objectMapper"); //$NON-NLS-1$
            Field locF = findField(delegate.getClass(), "location"); //$NON-NLS-1$
            if (modulesF == null || mapperF == null || locF == null)
            {
                return RegistryCleanup.FAILED;
            }
            modulesF.setAccessible(true);
            mapperF.setAccessible(true);
            locF.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<Object, Object> modules = (Map<Object, Object>)modulesF.get(delegate);
            if (modules == null)
            {
                return RegistryCleanup.FAILED;
            }
            if (modules.remove(infobaseId) == null)
            {
                // Already gone (e.g. the delegate's initialize() self-healed it) — not an error.
                return RegistryCleanup.NOT_PRESENT;
            }

            Object mapper = mapperF.get(delegate);
            Object location = locF.get(delegate); // java.nio.file.Path
            File file = (File)location.getClass().getMethod("toFile").invoke(location); //$NON-NLS-1$
            List<Object> remaining = new ArrayList<>(modules.values());
            mapper.getClass().getMethod("writeValue", File.class, Object.class) //$NON-NLS-1$
                .invoke(mapper, file, remaining);
            return RegistryCleanup.REMOVED;
        }
        catch (Throwable t)
        {
            Activator.logError("delete_infobase: best-effort infobases.yaml cleanup failed " //$NON-NLS-1$
                + "(non-fatal — the server is deleted; the orphan self-heals on restart)", t); //$NON-NLS-1$
            return RegistryCleanup.FAILED;
        }
    }

    /** First public method on {@code cls} (incl. inherited) with the given name and parameter count. */
    private static Method findMethod(Class<?> cls, String name, int paramCount)
    {
        for (Method m : cls.getMethods())
        {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount)
            {
                return m;
            }
        }
        return null;
    }

    /** Declared field {@code name} on {@code cls} or any superclass; {@code null} if absent. */
    private static Field findField(Class<?> cls, String name)
    {
        for (Class<?> c = cls; c != null; c = c.getSuperclass())
        {
            try
            {
                return c.getDeclaredField(name);
            }
            catch (NoSuchFieldException e)
            {
                // keep climbing
            }
        }
        return null;
    }
}
