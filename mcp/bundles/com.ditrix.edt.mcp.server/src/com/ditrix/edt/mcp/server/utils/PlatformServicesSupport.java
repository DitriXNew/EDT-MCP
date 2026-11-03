/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.google.inject.Injector;

/** Access to services owned by EDT's platform-services injector. */
public final class PlatformServicesSupport
{
    private PlatformServicesSupport()
    {
    }

    public static Bundle bundle()
    {
        Bundle bundle = Platform.getBundle("com._1c.g5.v8.dt.platform.services.core");
        if (bundle == null)
        {
            throw new IllegalStateException("EDT platform services are unavailable; install the platform-services plugin.");
        }
        return bundle;
    }

    public static <T> T resolve(Class<T> service) throws Exception
    {
        Bundle bundle = bundle();
        Class<?> core = bundle.loadClass("com._1c.g5.v8.dt.internal.platform.services.core.PlatformServicesCore");
        Method getDefault = core.getDeclaredMethod("getDefault");
        getDefault.setAccessible(true); // NOSONAR EDT's injector is not a public service
        Object instance = getDefault.invoke(null);
        if (instance == null)
        {
            bundle.start(Bundle.START_TRANSIENT);
            instance = getDefault.invoke(null);
        }
        if (instance == null)
        {
            throw new IllegalStateException("EDT platform services are starting; retry after EDT finishes startup.");
        }
        Method getInjector = core.getDeclaredMethod("getInjector");
        getInjector.setAccessible(true); // NOSONAR EDT's injector is not a public service
        return ((Injector)getInjector.invoke(instance)).getInstance(service);
    }
}
