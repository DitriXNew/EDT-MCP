/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Headless-testable contracts of the role-rights service wiring added to {@link EdtServices} for
 * #211 ("Роли"): {@link EdtServices#getRightInfosService()},
 * {@link EdtServices#getEventBroker()} and {@link EdtServices#getCollectionOrderSorter()}.
 * <p>
 * Like the other {@link EdtServices} getters, these must not throw before {@link EdtServices#init}
 * ran (the OSGi {@code ServiceTracker}s are not open) or after {@link EdtServices#dispose}. In
 * particular {@link EdtServices#getRightInfosService()} carries a {@code RightsPlugin} injector
 * fallback (used when the OSGi service is not registered): in this test runtime the rights bundle is
 * on the target platform, so the fallback resolves a REAL service (non-null); where the bundle is
 * absent it degrades to {@code null} without throwing. {@link EdtServices#getEventBroker()} and
 * {@link EdtServices#getCollectionOrderSorter()} have no injector fallback, so they short-circuit to
 * {@code null} before init. The live resolution of the actual services needs a running EDT and is
 * verified on the stand.
 */
public class EdtServicesRightsWiringTest
{
    @Test
    public void testRightInfosServiceResolvesViaInjectorBeforeInit()
    {
        // Tracker not open: getRightInfosService falls back to the RightsPlugin injector, which is
        // available in this runtime (the rights bundle is on the test target platform) -> non-null,
        // and it must resolve WITHOUT throwing even though EdtServices.init has not run.
        assertNotNull(new EdtServices().getRightInfosService());
    }

    @Test
    public void testEventBrokerNullSafeBeforeInit()
    {
        // The tracker is not open: the getter must short-circuit to null.
        assertNull(new EdtServices().getEventBroker());
    }

    @Test
    public void testCollectionOrderSorterNullSafeBeforeInit()
    {
        // The tracker is not open: the getter must short-circuit to null.
        assertNull(new EdtServices().getCollectionOrderSorter());
    }

    @Test
    public void testRightInfosServiceResolvesViaInjectorAfterDispose()
    {
        EdtServices services = new EdtServices();
        // dispose() on a never-initialized instance is a no-op; the injector fallback (RightsPlugin's,
        // not owned by EdtServices) still resolves the service without throwing.
        services.dispose();
        assertNotNull(services.getRightInfosService());
    }

    @Test
    public void testEventBrokerNullSafeAfterDispose()
    {
        EdtServices services = new EdtServices();
        services.dispose();
        assertNull(services.getEventBroker());
    }

    @Test
    public void testCollectionOrderSorterNullSafeAfterDispose()
    {
        EdtServices services = new EdtServices();
        services.dispose();
        assertNull(services.getCollectionOrderSorter());
    }
}
