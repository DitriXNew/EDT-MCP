/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.common.util.URI;
import org.mockito.Mockito;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;

/** Restores the registry after supplying color descriptions to the stripped headless runtime. */
public final class PlatformColorTestRuntime implements AutoCloseable
{
    private final Field providers;
    private final Object previous;

    @SuppressWarnings("unchecked")
    public PlatformColorTestRuntime(String... names) throws Exception
    {
        IEObjectProvider colors = AppearanceValueBuilderTest.catalogue("v8:/Colors/Test/v8.3.27", names);
        for (var description : colors.getEObjectDescriptions(null))
        {
            String name = description.getName().toString();
            // Real EDT color loaders register the qualified name as the fragment, without '/'.
            URI uri = URI.createURI("v8:/Colors/" + name.substring(0, name.indexOf('.')) + "/v8.3.27")
                .appendFragment(name);
            Mockito.doReturn(uri).when(description).getEObjectURI();
            Mockito.doAnswer(invocation ->
            {
                var proxy = McoreFactory.eINSTANCE.createColorDef();
                ((InternalEObject)proxy).eSetProxyURI(uri);
                return proxy;
            }).when(description).getEObjectOrProxy();
            Mockito.doReturn(description).when(colors).getEObjectDescription(name);
        }
        var registry = IEObjectProvider.Registry.INSTANCE;
        registry.get(McorePackage.Literals.COLOR, DcsStoredXmlTestSupport.VERSION);
        providers = registry.getClass().getDeclaredField("providerByClass");
        providers.setAccessible(true);
        previous = providers.get(registry);
        var replacement = new HashMap<>((Map<EClass, Map<Version, IEObjectProvider>>)previous);
        replacement.put(McorePackage.Literals.COLOR, Map.of(DcsStoredXmlTestSupport.VERSION, colors));
        providers.set(registry, replacement);
    }

    @Override
    public void close() throws Exception
    {
        providers.set(IEObjectProvider.Registry.INSTANCE, previous);
    }
}
