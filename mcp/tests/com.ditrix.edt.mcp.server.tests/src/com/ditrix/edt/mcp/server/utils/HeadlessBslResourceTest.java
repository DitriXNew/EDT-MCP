// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import org.junit.Test;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Native Eclipse BSL services; standalone parser registrations are not certification. */
public class HeadlessBslResourceTest
{
    private static IResourceServiceProvider nativeProvider() throws Exception
    {
        for (var element : org.eclipse.core.runtime.Platform.getExtensionRegistry()
            .getConfigurationElementsFor("org.eclipse.xtext.extension_resourceServiceProvider"))
        {
            if ("com._1c.g5.v8.dt.bsl".equals(element.getContributor().getName())
                && "bsl".equals(element.getAttribute("uriExtension")))
            {
                Object provider = element.createExecutableExtension("class");
                assertTrue(provider.toString(), provider instanceof IResourceServiceProvider);
                return (IResourceServiceProvider)provider;
            }
        }
        throw new AssertionError("Missing native Eclipse BSL resource-service contribution");
    }

    @Test public void nativeResourceProviderLoadsWithoutWorkbench() throws Exception
    {
        assertFalse("The native probe must not have a workbench", org.eclipse.ui.PlatformUI.isWorkbenchRunning());
        IResourceServiceProvider provider = nativeProvider();
        assertNotNull("Native BSL resource service provider", provider);
        assertNotNull("Native BM resource set provider", provider.get(BmAwareResourceSetProvider.class));
    }

    @Test public void unitRuntimeLacksVersionServiceButDoesNotClassifyRealHosts() throws Exception
    {
        var provider = nativeProvider();
        var set = provider.get(BmAwareResourceSetProvider.class).get((IProject)null);
        set.getResourceFactoryRegistry().getExtensionToFactoryMap().put("bsl",
            provider.get(org.eclipse.xtext.resource.IResourceFactory.class));
        var resource = set.createResource(URI.createURI("file:/headless-probe/Module.bsl"));
        assertNotNull("Native BSL resource factory", resource);
        resource.load(new ByteArrayInputStream("Function Add(A, B) Export\nReturn A + B;\nEndFunction"
            .getBytes(StandardCharsets.UTF_8)), Map.of());
        assertTrue(resource.getErrors().toString(), resource.getErrors().isEmpty());
        var failure = assertThrows(com._1c.g5.wiring.ServiceUnavailableException.class,
            () -> resource.getContents().get(0));
        var trace = java.util.Arrays.asList(failure.getStackTrace());
        assertTrue(trace.toString(), trace.stream().anyMatch(frame -> frame.getClassName()
            .contains("IRuntimeVersionSupport") && frame.getMethodName().equals("getRuntimeVersionOrDefault")));
        assertTrue(trace.toString(), trace.stream().anyMatch(frame -> frame.getClassName()
            .endsWith("BslDerivedStateComputer") && frame.getMethodName().equals("installEnvironments")));
        for (String tool : new String[] { "read_method_source", "get_module_structure" })
        {
            assertEquals(tool, com.ditrix.edt.mcp.server.tools.HostToolCapabilities.Capability.HEADLESS,
                com.ditrix.edt.mcp.server.tools.HostToolCapabilities.requireEntry(tool).capability());
            assertTrue(com.ditrix.edt.mcp.server.tools.HostToolCapabilities.requireEntry(tool)
                .reason().contains("real headless probe"));
        }
        assertEquals(com.ditrix.edt.mcp.server.tools.HostToolCapabilities.Capability.HEADLESS_DEGRADED,
            com.ditrix.edt.mcp.server.tools.HostToolCapabilities.requireEntry("get_symbol_info").capability());
    }

    @Test public void standaloneRegistrationCannotReplaceTheNativeProbe() throws Exception
    {
        var providers = IResourceServiceProvider.Registry.INSTANCE.getExtensionToFactoryMap();
        var standalone = org.mockito.Mockito.mock(IResourceServiceProvider.class);
        Object previous = providers.put("bsl", standalone);
        try
        {
            var provider = nativeProvider();
            assertNotSame(standalone, provider);
            assertNotNull(provider.get(BmAwareResourceSetProvider.class));
        }
        finally
        {
            if (previous == null) { providers.remove("bsl"); }
            else { providers.put("bsl", previous); }
        }
    }
}
