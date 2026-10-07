/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.platform.IEObjectProvider;

/**
 * {@link PlatformCatalogue}: the one lookup over a versioned platform catalogue, and the line it draws
 * between "the catalogue has no such name" and "nothing is known about the name".
 */
public class PlatformCatalogueTest
{
    private static final String COLORS = "v8:/Colors/Web/v8.3.27"; //$NON-NLS-1$

    @Test
    public void testTheExactNameIsFound()
    {
        PlatformCatalogue.Lookup found = PlatformCatalogue.find(
            AppearanceValueBuilderTest.catalogue(COLORS, "Web.AliceBlue"), "Web.AliceBlue", //$NON-NLS-1$ //$NON-NLS-2$
            McorePackage.Literals.COLOR);
        assertSame(PlatformCatalogue.Status.FOUND, found.status);
        assertTrue("the value is the catalogue's unresolved proxy", found.proxy.eIsProxy()); //$NON-NLS-1$
        assertEquals("Web.AliceBlue", found.name); //$NON-NLS-1$
    }

    @Test
    public void testAnyCaseFindsTheRegisteredSpelling()
    {
        PlatformCatalogue.Lookup found = PlatformCatalogue.find(
            AppearanceValueBuilderTest.catalogue(COLORS, "Web.AliceBlue"), "WEB.aliceblue", //$NON-NLS-1$ //$NON-NLS-2$
            McorePackage.Literals.COLOR);
        assertSame(PlatformCatalogue.Status.FOUND, found.status);
        assertEquals("Web.AliceBlue", found.name); //$NON-NLS-1$
    }

    @Test
    public void testAProxyOfAnotherClassIsNotFound()
    {
        // The stub hands back colour proxies: a font lookup must not take them.
        assertSame(PlatformCatalogue.Status.NOT_FOUND, PlatformCatalogue.find(
            AppearanceValueBuilderTest.catalogue(COLORS, "Web.AliceBlue"), "Web.AliceBlue", //$NON-NLS-1$ //$NON-NLS-2$
            McorePackage.Literals.FONT).status);
    }

    @Test
    public void testAnUnknownNameIsNotFound()
    {
        assertSame(PlatformCatalogue.Status.NOT_FOUND, PlatformCatalogue.find(
            AppearanceValueBuilderTest.catalogue(COLORS, "Web.AliceBlue"), "Web.Nope", //$NON-NLS-1$ //$NON-NLS-2$
            McorePackage.Literals.COLOR).status);
    }

    @Test
    public void testNoCatalogueAndAThrowingCatalogueAreUnavailable()
    {
        assertSame(PlatformCatalogue.Status.UNAVAILABLE,
            PlatformCatalogue.find(null, "Web.AliceBlue", McorePackage.Literals.COLOR).status); //$NON-NLS-1$
        IEObjectProvider broken = Mockito.mock(IEObjectProvider.class);
        Mockito.doThrow(new IllegalStateException("index not ready")).when(broken) //$NON-NLS-1$
            .getEObjectDescriptions(Mockito.any());
        assertSame(PlatformCatalogue.Status.UNAVAILABLE,
            PlatformCatalogue.find(broken, "Web.AliceBlue", McorePackage.Literals.COLOR).status); //$NON-NLS-1$
        assertNull("an unavailable catalogue has no name list, not an empty one", //$NON-NLS-1$
            PlatformCatalogue.names(broken, n -> true));
        assertNull(PlatformCatalogue.names(null, n -> true));
    }

    @Test
    public void testNamesAreFilteredAndDistinct()
    {
        List<String> names = PlatformCatalogue.names(AppearanceValueBuilderTest.catalogue(COLORS,
            "Web.AliceBlue", "Style.FormBackColor", "Web.AliceBlue"), n -> n.startsWith("Web.")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(List.of("Web.AliceBlue"), names); //$NON-NLS-1$
    }

    @Test
    public void testNoVersionGivesNoCatalogue()
    {
        assertNull(PlatformCatalogue.providerFor(McorePackage.Literals.COLOR, null));
    }
}
