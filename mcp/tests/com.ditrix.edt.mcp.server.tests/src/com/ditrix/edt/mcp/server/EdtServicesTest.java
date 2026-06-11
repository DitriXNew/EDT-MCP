/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Headless-testable contracts of {@link EdtServices}: the service getters must be null-safe before
 * {@link EdtServices#init} ran (and after {@link EdtServices#dispose}), returning {@code null}
 * without touching the OSGi platform. The form-model object factory lookup itself (the OSGi
 * {@code IModelObjectFactory} service filtered on {@code service.name=FormModelObjectFactory}, the
 * fix for the always-failing form-injector {@code getInstance(IModelObjectFactory.class)} lookup)
 * needs a running EDT and is verified on the stand.
 */
public class EdtServicesTest
{
    @Test
    public void testFormModelObjectFactoryNullSafeBeforeInit()
    {
        // The tracker is not open: the getter must short-circuit to null without activating any
        // bundle or logging through a half-initialized platform.
        assertNull(new EdtServices().getFormModelObjectFactory());
    }

    @Test
    public void testFormModelObjectFactoryNullSafeAfterDispose()
    {
        EdtServices services = new EdtServices();
        // dispose() on a never-initialized instance must be a no-op, and the getter stays null-safe.
        services.dispose();
        assertNull(services.getFormModelObjectFactory());
    }
}
