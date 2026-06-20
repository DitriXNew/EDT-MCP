/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAccessSettings;
import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.Activator;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * Shared backend for storing/reading an application's <em>infobase connection
 * credentials</em> — the user/password EDT uses to authenticate the designer
 * agent that performs a pre-launch DB update or a {@code debug_launch}.
 *
 * <p>Fixes issue #194: when an infobase has a user list, the update agent is
 * started without the infobase user and fails to authenticate (an
 * {@code AuthenticationException} deep in {@code DesignerSessionPool}, and an
 * interactive "Configure Infobase access Settings" dialog that blocks the
 * unattended call). The agent reads the user/password from
 * {@link IInfobaseAccessSettings} (resolved via {@link IInfobaseAccessManager}),
 * so persisting them here makes the headless update authenticate.
 *
 * <p>These credentials say "connect AS this <b>existing</b> infobase user"; they
 * do NOT create infobase users. The user must already exist in the infobase.
 *
 * <p>The {@link IInfobaseAccessManager} is resolved from the platform-services
 * Guice injector via reflection (the same pattern as
 * {@code CreateInfobaseTool.resolveCreationOperation} — EDT internals, no
 * Require-Bundle). The target {@link InfobaseReference} is
 * {@link IInfobaseApplication#getInfobase()}.
 */
public final class InfobaseAccessSupport
{
    /** Symbolic name of the bundle that owns the internal PlatformServicesCore (and its Guice injector). */
    private static final String PLATFORM_SERVICES_CORE_BUNDLE_ID =
        "com._1c.g5.v8.dt.platform.services.core"; //$NON-NLS-1$

    /** Internal singleton holding the platform-services Guice injector (loaded via the owning bundle). */
    private static final String PLATFORM_SERVICES_CORE_CLASS =
        "com._1c.g5.v8.dt.internal.platform.services.core.PlatformServicesCore"; //$NON-NLS-1$

    private InfobaseAccessSupport()
    {
    }

    /**
     * Parses the {@code access} argument into an {@link InfobaseAccess} literal.
     * {@code "OS"} (any case) selects OS authentication; everything else (incl.
     * {@code null}/empty) defaults to {@link InfobaseAccess#INFOBASE} (1C user
     * authentication — the case that needs a user/password).
     *
     * @param access the raw access argument (may be {@code null})
     * @return the resolved access literal (never {@code null})
     */
    public static InfobaseAccess parseAccess(String access)
    {
        return "OS".equalsIgnoreCase(access) ? InfobaseAccess.OS : InfobaseAccess.INFOBASE; //$NON-NLS-1$
    }

    /**
     * Stores the infobase connection credentials for an application's infobase.
     *
     * @param application the target application (must be an {@link IInfobaseApplication})
     * @param user the infobase user name (empty allowed — demo bases use an empty password user)
     * @param password the infobase user password (empty allowed)
     * @param access the access kind (INFOBASE = 1C user auth, OS = OS auth)
     * @return {@code null} on success, otherwise an actionable error message describing why the
     *         credentials could not be stored (not an infobase application, infobase unresolved,
     *         access manager unavailable, or the {@code updateSettings} call failed)
     */
    public static String storeCredentials(IApplication application, String user, String password,
            InfobaseAccess access)
    {
        if (!(application instanceof IInfobaseApplication))
        {
            return "Application '" + application.getId() //$NON-NLS-1$
                + "' is not a file/server infobase application — credentials apply only to infobases."; //$NON-NLS-1$
        }
        InfobaseReference ref;
        try
        {
            ref = ((IInfobaseApplication)application).getInfobase();
        }
        catch (Exception e) // NOSONAR EDT model access — report verbatim, never crash the tool
        {
            Activator.logError("set credentials: getInfobase() failed for " + application.getId(), e); //$NON-NLS-1$
            return "Could not resolve the infobase for application '" + application.getId() + "': " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage();
        }
        if (ref == null)
        {
            return "Could not resolve the infobase for application '" + application.getId() + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return storeCredentials(ref, user, password, access);
    }

    /**
     * Stores the infobase connection credentials directly against an
     * {@link InfobaseReference} (the key the update agent's access settings are
     * resolved by). Used by {@code create_infobase}, which already holds the
     * freshly-built reference and needs no application read-back.
     *
     * @param ref the target infobase reference (must not be {@code null})
     * @param user the infobase user name (empty allowed)
     * @param password the infobase user password (empty allowed)
     * @param access the access kind (INFOBASE = 1C user auth, OS = OS auth)
     * @return {@code null} on success, otherwise an actionable error message
     */
    public static String storeCredentials(InfobaseReference ref, String user, String password,
            InfobaseAccess access)
    {
        if (ref == null)
        {
            return "No infobase reference to store credentials for."; //$NON-NLS-1$
        }
        IInfobaseAccessManager manager = resolveAccessManager();
        if (manager == null)
        {
            return "EDT infobase access manager is not available (the platform-services plugin may " //$NON-NLS-1$
                + "not be ready)."; //$NON-NLS-1$
        }
        try
        {
            manager.updateSettings(ref, new InfobaseAccessSettings(access,
                user == null ? "" : user, password == null ? "" : password, "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        catch (Exception e) // NOSONAR a StorageException (secure-storage) or CoreException must surface
        {
            Activator.logError("set credentials: updateSettings failed", e); //$NON-NLS-1$
            return "Failed to store infobase access settings: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Reads back the currently stored credentials for an application's infobase, best-effort.
     *
     * @param application the target application
     * @return the resolved settings, or {@code null} when unavailable (not an infobase app,
     *         infobase unresolved, manager unavailable, or the read failed)
     */
    public static IInfobaseAccessSettings readCredentials(IApplication application)
    {
        if (!(application instanceof IInfobaseApplication))
        {
            return null;
        }
        IInfobaseAccessManager manager = resolveAccessManager();
        if (manager == null)
        {
            return null;
        }
        try
        {
            InfobaseReference ref = ((IInfobaseApplication)application).getInfobase();
            return ref == null ? null : manager.resolveSettings(ref);
        }
        catch (Exception e) // NOSONAR best-effort read
        {
            Activator.logError("read credentials failed for " + application.getId(), e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Resolves {@link IInfobaseAccessManager} from the platform-services Guice injector via
     * reflection (the internal {@code PlatformServicesCore} singleton is not exported, so it is
     * loaded through its owning bundle's class loader — the same pattern as
     * {@code CreateInfobaseTool.resolveCreationOperation}).
     *
     * @return the access manager, or {@code null} when the platform-services plugin is not loaded
     *         or the injector/binding is unavailable
     */
    public static IInfobaseAccessManager resolveAccessManager()
    {
        try
        {
            Bundle psCoreBundle = Platform.getBundle(PLATFORM_SERVICES_CORE_BUNDLE_ID);
            if (psCoreBundle == null)
            {
                Activator.logError("infobase access: bundle '" + PLATFORM_SERVICES_CORE_BUNDLE_ID //$NON-NLS-1$
                    + "' not found — the EDT platform-services plugin is not installed", null); //$NON-NLS-1$
                return null;
            }
            Class<?> coreClass = psCoreBundle.loadClass(PLATFORM_SERVICES_CORE_CLASS);
            Method getDefault = coreClass.getDeclaredMethod("getDefault"); //$NON-NLS-1$
            getDefault.setAccessible(true); // NOSONAR reflective access required (EDT internals, no Require-Bundle)
            Object coreInstance = getDefault.invoke(null);
            if (coreInstance == null)
            {
                psCoreBundle.start(Bundle.START_TRANSIENT);
                coreInstance = getDefault.invoke(null);
                if (coreInstance == null)
                {
                    return null;
                }
            }
            Method getInjector = coreClass.getDeclaredMethod("getInjector"); //$NON-NLS-1$
            getInjector.setAccessible(true); // NOSONAR reflective access required (EDT internals, no Require-Bundle)
            Object injector = getInjector.invoke(coreInstance);
            if (injector == null)
            {
                return null;
            }
            return ((com.google.inject.Injector)injector).getInstance(IInfobaseAccessManager.class);
        }
        catch (Exception e) // NOSONAR probe must never crash the tool
        {
            Activator.logError("infobase access: could not resolve IInfobaseAccessManager " //$NON-NLS-1$
                + "(a 1C platform may not be registered in EDT)", e); //$NON-NLS-1$
            return null;
        }
    }
}
