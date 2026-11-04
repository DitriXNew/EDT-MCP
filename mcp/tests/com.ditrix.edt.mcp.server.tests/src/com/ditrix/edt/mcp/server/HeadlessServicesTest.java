// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

public class HeadlessServicesTest
{
    @Test public void coreOpensModelTrackersWithoutOpeningUiTrackers() throws Exception {
        List<String> filters = new ArrayList<>();
        BundleContext context = context(filters);
        EdtServices services = new EdtServices();
        try {
            services.init(context, false);
            assertTrue(filters.toString(), filters.stream().anyMatch(value -> value.contains("IBmModelManager")));
            assertTrue(filters.toString(), filters.stream().anyMatch(value -> value.contains("IWorkspaceOrchestrator")));
            assertFalse(filters.toString(), filters.stream().anyMatch(value -> value.contains("INavigatorContentProviderStateProvider")));
            assertFalse(filters.toString(), filters.stream().anyMatch(value -> value.contains("IPresentationService")));
        }
        finally { services.dispose(); }
    }

    @Test public void windowedEntryStillOpensTheOriginalUiTrackers() throws Exception {
        List<String> filters = new ArrayList<>();
        BundleContext context = context(filters);
        EdtServices services = new EdtServices();
        try {
            services.init(context);
            assertTrue(filters.toString(), filters.stream().anyMatch(value -> value.contains("INavigatorContentProviderStateProvider")));
            assertTrue(filters.toString(), filters.stream().anyMatch(value -> value.contains("IPresentationService")));
        }
        finally { services.dispose(); }
    }

    private static BundleContext context(List<String> filters) {
        return (BundleContext) java.lang.reflect.Proxy.newProxyInstance(BundleContext.class.getClassLoader(),
            new Class<?>[]{BundleContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("createFilter")) {
                    String filter = (String) args[0]; filters.add(filter); return FrameworkUtil.createFilter(filter);
                }
                if (method.getName().equals("toString")) { return "Headless service-tracker context"; }
                if (method.getName().equals("hashCode")) { return System.identityHashCode(proxy); }
                if (method.getName().equals("equals")) { return proxy == args[0]; }
                if (method.getReturnType() == boolean.class) { return false; }
                return null;
            });
    }

    @Test public void activatorRecognizesOurApplicationAndRetainsTheTestSkipPath() throws Exception {
        String previous = System.getProperty("eclipse.application");
        var method = Activator.class.getDeclaredMethod("isHeadless"); method.setAccessible(true);
        try {
            System.setProperty("eclipse.application", HostKind.APPLICATION_ID); assertEquals(true, method.invoke(null));
            System.setProperty("eclipse.application", "org.eclipse.tycho.surefire.osgibooter.headlesstest"); assertEquals(true, method.invoke(null));
        }
        finally { if (previous == null) { System.clearProperty("eclipse.application"); } else { System.setProperty("eclipse.application", previous); } }
    }
}
