/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumper;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Shared backend for {@code build_external_objects} (issue #122): resolves the
 * EDT {@link IExternalObjectDumper} (the service that builds an external data
 * processor / external report into an {@code .epf} / {@code .erf} file on disk)
 * and computes the on-disk file name for a dumped object.
 *
 * <p>The {@link IExternalObjectDumper} is resolved from the platform-services
 * Guice injector via reflection — the exact same pattern as
 * {@link InfobaseAccessSupport#resolveAccessManager()}: the internal
 * {@code PlatformServicesCore} singleton is not exported, so it is loaded through
 * its owning bundle's class loader and queried for the injector. The injector
 * binds {@link IExternalObjectDumper} to its internal implementation as a
 * singleton, so {@code getInstance(IExternalObjectDumper.class)} returns the
 * same instance EDT itself uses.
 *
 * <p>{@link #resolveDumper()} is null-safe: it never throws and returns
 * {@code null} when the platform-services plugin is not loaded or the
 * injector/binding is unavailable (e.g. headless unit tests). The extension /
 * file-name helpers are pure and static — no {@code PlatformUI},
 * {@code ResourcesPlugin} or live-model access — so they are unit-testable.
 */
public final class ExternalObjectDumpSupport
{
    /** Symbolic name of the bundle that owns the internal PlatformServicesCore (and its Guice injector). */
    private static final String PLATFORM_SERVICES_CORE_BUNDLE_ID =
        "com._1c.g5.v8.dt.platform.services.core"; //$NON-NLS-1$

    /** Internal singleton holding the platform-services Guice injector (loaded via the owning bundle). */
    private static final String PLATFORM_SERVICES_CORE_CLASS =
        "com._1c.g5.v8.dt.internal.platform.services.core.PlatformServicesCore"; //$NON-NLS-1$

    /** File extension of a dumped external data processor. */
    public static final String EXTENSION_EPF = "epf"; //$NON-NLS-1$

    /** File extension of a dumped external report. */
    public static final String EXTENSION_ERF = "erf"; //$NON-NLS-1$

    /** EClass name of an external data processor (used as a class-loader-independent fallback check). */
    private static final String ECLASS_EXTERNAL_DATA_PROCESSOR = "ExternalDataProcessor"; //$NON-NLS-1$

    /** EClass name of an external report (used as a class-loader-independent fallback check). */
    private static final String ECLASS_EXTERNAL_REPORT = "ExternalReport"; //$NON-NLS-1$

    private ExternalObjectDumpSupport()
    {
    }

    /**
     * Resolves {@link IExternalObjectDumper} from the platform-services Guice injector via reflection
     * (the internal {@code PlatformServicesCore} singleton is not exported, so it is loaded through its
     * owning bundle's class loader — the same pattern as
     * {@link InfobaseAccessSupport#resolveAccessManager()}).
     *
     * @return the external-object dumper, or {@code null} when the platform-services plugin is not
     *         loaded or the injector/binding is unavailable (this method never throws)
     */
    public static IExternalObjectDumper resolveDumper()
    {
        try
        {
            Bundle psCoreBundle = Platform.getBundle(PLATFORM_SERVICES_CORE_BUNDLE_ID);
            if (psCoreBundle == null)
            {
                Activator.logError("external object dump: bundle '" + PLATFORM_SERVICES_CORE_BUNDLE_ID //$NON-NLS-1$
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
            return ((com.google.inject.Injector)injector).getInstance(IExternalObjectDumper.class);
        }
        catch (Exception e) // NOSONAR probe must never crash the tool
        {
            Activator.logError("external object dump: could not resolve IExternalObjectDumper " //$NON-NLS-1$
                + "(the EDT platform-services plugin may not be ready)", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Returns the on-disk file extension for a dumped external object: {@code "epf"} for an
     * {@link ExternalDataProcessor}, {@code "erf"} for an {@link ExternalReport}. The decision is made
     * first by {@code instanceof}, then by the EObject's {@link EClass} name (so a model object loaded
     * through a different class loader is still classified correctly). External reports are matched
     * before external data processors because the two interfaces are siblings (neither extends the
     * other), making the order purely informational.
     *
     * @param object the external object to classify (may be {@code null})
     * @return {@code "erf"} for an external report, otherwise {@code "epf"} (the safe default — an
     *         external data processor is the more general external object)
     */
    public static String extensionForObject(EObject object)
    {
        if (object instanceof ExternalReport)
        {
            return EXTENSION_ERF;
        }
        if (object instanceof ExternalDataProcessor)
        {
            return EXTENSION_EPF;
        }
        String eClassName = eClassName(object);
        if (ECLASS_EXTERNAL_REPORT.equals(eClassName))
        {
            return EXTENSION_ERF;
        }
        return EXTENSION_EPF;
    }

    /**
     * Builds the on-disk file name for a dumped external object: {@code "<name>.<ext>"}. Both arguments
     * are tolerated when {@code null}/blank so a caller can build a name before the object's real name
     * is known without risking a {@link NullPointerException}.
     *
     * @param name the external object's name; when {@code null} or blank, {@code "ExternalObject"} is
     *            used as a placeholder so the result is always a valid file name
     * @param extension the file extension without a leading dot (e.g. {@code "epf"}); when {@code null}
     *            or blank, {@link #EXTENSION_EPF} is used
     * @return the file name {@code "<name>.<extension>"} (never {@code null})
     */
    public static String outputFileName(String name, String extension)
    {
        String safeName = (name == null || name.trim().isEmpty()) ? "ExternalObject" : name.trim(); //$NON-NLS-1$
        String safeExt = (extension == null || extension.trim().isEmpty()) ? EXTENSION_EPF : extension.trim();
        return safeName + "." + safeExt; //$NON-NLS-1$
    }

    private static String eClassName(EObject object)
    {
        if (object == null)
        {
            return null;
        }
        EClass eClass = object.eClass();
        return eClass == null ? null : eClass.getName();
    }
}
