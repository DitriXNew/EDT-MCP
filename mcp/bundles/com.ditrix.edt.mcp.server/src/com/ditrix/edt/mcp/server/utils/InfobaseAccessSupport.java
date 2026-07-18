/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAccessManager;
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
     * Validates an {@code access} argument against the closed {@code INFOBASE | OS} enum the schema
     * declares. {@code null}/empty is accepted (it defaults to {@link InfobaseAccess#INFOBASE} in
     * {@link #parseAccess(String)}); a non-empty value outside the enum is rejected so a typo (e.g.
     * {@code access=OOPS}) cannot silently store a different authentication mode — MCP clients are
     * not required to validate against the schema before sending.
     *
     * @param access the raw access argument (may be {@code null})
     * @return an actionable error message when {@code access} is a non-empty out-of-enum value, else
     *         {@code null}
     */
    public static String accessError(String access)
    {
        if (access == null || access.isEmpty()
            || "INFOBASE".equalsIgnoreCase(access) || "OS".equalsIgnoreCase(access)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return null;
        }
        return "Invalid access: '" + access + "'. Allowed values: 'INFOBASE', 'OS'."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Stores the infobase connection credentials for an application.
     *
     * <p>Two shapes are supported:
     * <ul>
     *   <li>an {@link IInfobaseApplication} (a file/server infobase) — the direct fast path, via
     *   its own {@link IInfobaseApplication#getInfobase()} (unchanged since issue #194);</li>
     *   <li><b>issue #275:</b> any OTHER application that ADAPTS to an {@link InfobaseReference} —
     *   most notably a standalone-server ({@code wst-server}) application wrapping a registered
     *   infobase ({@code create_infobase}'s {@code applicationKind='standaloneServer'} +
     *   {@code mode='register'}). EDT's own launch path for such an application
     *   ({@code ServerApplicationBehaviourDelegate}) resolves the infobase to authenticate against
     *   via {@code org.eclipse.core.runtime.Adapters.adapt(<the application/module>,
     *   InfobaseReference.class)} — the platform's internal (unexported)
     *   {@code StandaloneServerAdapterFactory} adapts a {@code StandaloneServerInfobase} module (or
     *   the application itself) into a WEB {@link InfobaseReference} keyed by the config's
     *   {@code Infobase.getId()}. Storing credentials against THAT SAME adapted reference is
     *   exactly what the launch resolves later, so this method tries the adapter on the
     *   application first, then — if that misses — on its module (see
     *   {@link #moduleOfApplication(IApplication)}, a small local mirror of
     *   {@code StandaloneServerSupport.moduleOfApplication}; that class is package-private in
     *   {@code tools.impl}, so it is mirrored here rather than widened just for this one call).</li>
     * </ul>
     *
     * @param application the target application
     * @param user the infobase user name (empty allowed — demo bases use an empty password user)
     * @param password the infobase user password (empty allowed)
     * @param access the access kind (INFOBASE = 1C user auth, OS = OS auth)
     * @return {@code null} on success, otherwise an actionable error message describing why the
     *         credentials could not be stored (no infobase reference could be resolved for this
     *         application, access manager unavailable, or the {@code updateSettings} call failed)
     */
    public static String storeCredentials(IApplication application, String user, String password,
            InfobaseAccess access)
    {
        InfobaseReference ref = resolveInfobaseReference(application);
        if (ref != null)
        {
            return storeCredentials(ref, user, password, access);
        }
        return "Application '" + application.getId() //$NON-NLS-1$
            + "' exposes no infobase reference — credentials apply to infobases and to standalone " //$NON-NLS-1$
            + "servers wrapping a registered infobase (issue #275); this application is neither."; //$NON-NLS-1$
    }

    /**
     * Resolves the {@link InfobaseReference} an application binds to — the target the update/launch
     * agent authenticates against ({@link #storeCredentials(IApplication, String, String,
     * InfobaseAccess)}), and (issue #281 phase 2) the value {@code set_branch_infobase} /
     * {@code create_git_branch} associate with a git branch context. Extracted from
     * {@code storeCredentials} so there is ONE resolver; mirrors its exact resolution order:
     * <ol>
     *   <li>{@link IInfobaseApplication#getInfobase()} — the direct fast path for a file/server
     *   infobase application (unchanged since issue #194);</li>
     *   <li>otherwise, {@link #adaptToInfobaseReference(Object)} on the application itself, then — if
     *   that misses — on its module ({@link #moduleOfApplication(IApplication)}) — the
     *   standalone-server ({@code wst-server}) path (issue #275); see the field-level javadoc on the
     *   original {@code storeCredentials} overload for the full rationale of this adapter path.</li>
     * </ol>
     * Never throws: a {@code getInfobase()} failure is logged and treated the same as "no reference" —
     * the caller cannot distinguish a resolution error from a genuine absence, and for every current
     * caller both mean "nothing to bind/authenticate against".
     *
     * @param application the target application (must not be {@code null})
     * @return the resolved {@link InfobaseReference}, or {@code null} when none resolves
     */
    public static InfobaseReference resolveInfobaseReference(IApplication application)
    {
        if (application instanceof IInfobaseApplication)
        {
            try
            {
                return ((IInfobaseApplication)application).getInfobase();
            }
            catch (Exception e) // NOSONAR EDT model access — degrade to "no reference", never crash the caller
            {
                Activator.logError("resolve infobase reference: getInfobase() failed for " + application.getId(), //$NON-NLS-1$
                    e);
                return null;
            }
        }

        // Not an IInfobaseApplication (issue #275): try the SAME adapter path EDT's own launch
        // uses — first the application itself, then (if that misses) its module.
        InfobaseReference adapted = adaptToInfobaseReference(application);
        if (adapted == null)
        {
            Object module = moduleOfApplication(application);
            if (module != null)
            {
                adapted = adaptToInfobaseReference(module);
            }
        }
        return adapted;
    }

    /**
     * Adapts {@code adaptable} (an {@link IApplication} or one of its modules) to an
     * {@link InfobaseReference} via {@link Adapters#adapt(Object, Class)}, the same mechanism
     * EDT's {@code ServerApplicationBehaviourDelegate} launch path uses to resolve the infobase to
     * authenticate against (issue #275). Never throws — a probe failure (no adapter registered, or
     * any adapter-manager error) degrades to {@code null}, which the caller treats as "no match".
     *
     * @param adaptable the object to adapt (application or module); may be {@code null}
     * @return the adapted {@link InfobaseReference}, or {@code null} when none adapts
     */
    private static InfobaseReference adaptToInfobaseReference(Object adaptable)
    {
        try
        {
            return Adapters.adapt(adaptable, InfobaseReference.class);
        }
        catch (Exception e) // NOSONAR the adapter-registry probe must never crash the tool
        {
            return null;
        }
    }

    /**
     * Reflective {@code IServerApplication.getModule()} — a small local mirror of
     * {@code StandaloneServerSupport.moduleOfApplication} (tools.impl). That class (and the
     * method) is package-private there; rather than widening its visibility for this one call
     * (issue #275), the same tiny reflective probe is duplicated here. Returns {@code null} on any
     * failure — in particular for a plain {@link IInfobaseApplication} (already handled by the fast
     * path above) or any other application with no {@code getModule()}.
     *
     * @param application the target application
     * @return the module object (typically a {@code StandaloneServerInfobase}), or {@code null}
     */
    static Object moduleOfApplication(IApplication application)
    {
        try
        {
            Method m = application.getClass().getMethod("getModule"); //$NON-NLS-1$
            return m.invoke(application);
        }
        catch (Exception e) // NOSONAR reflective probe — an application with no module returns null here
        {
            return null;
        }
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
