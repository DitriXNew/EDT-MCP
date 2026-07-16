/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com.e1c.g5.dt.applications.IApplication;

/**
 * Tests for {@link InfobaseAccessSupport#parseAccess(String)} — the access-kind argument parser
 * (#194) — and for the {@link InfobaseAccessSupport#storeCredentials(IApplication, String, String,
 * InfobaseAccess)} adapter-fallback decision added by issue #275. The full store/read path (Guice
 * injector -&gt; {@code IInfobaseAccessManager} -&gt; {@code updateSettings}) needs a live EDT and
 * is verified on the e2e stand.
 */
public class InfobaseAccessSupportTest
{
    @Test
    public void testOsAnyCaseSelectsOs()
    {
        assertEquals(InfobaseAccess.OS, InfobaseAccessSupport.parseAccess("OS")); //$NON-NLS-1$
        assertEquals(InfobaseAccess.OS, InfobaseAccessSupport.parseAccess("os")); //$NON-NLS-1$
        assertEquals(InfobaseAccess.OS, InfobaseAccessSupport.parseAccess("Os")); //$NON-NLS-1$
    }

    @Test
    public void testInfobaseExplicit()
    {
        assertEquals(InfobaseAccess.INFOBASE, InfobaseAccessSupport.parseAccess("INFOBASE")); //$NON-NLS-1$
        assertEquals(InfobaseAccess.INFOBASE, InfobaseAccessSupport.parseAccess("infobase")); //$NON-NLS-1$
    }

    @Test
    public void testNullEmptyAndUnknownDefaultToInfobase()
    {
        assertEquals(InfobaseAccess.INFOBASE, InfobaseAccessSupport.parseAccess(null));
        assertEquals(InfobaseAccess.INFOBASE, InfobaseAccessSupport.parseAccess("")); //$NON-NLS-1$
        assertEquals(InfobaseAccess.INFOBASE, InfobaseAccessSupport.parseAccess("whatever")); //$NON-NLS-1$
    }

    @Test
    public void testAccessErrorAcceptsNullEmptyAndEnumValues()
    {
        assertNull(InfobaseAccessSupport.accessError(null));
        assertNull(InfobaseAccessSupport.accessError("")); //$NON-NLS-1$
        assertNull(InfobaseAccessSupport.accessError("INFOBASE")); //$NON-NLS-1$
        assertNull(InfobaseAccessSupport.accessError("infobase")); //$NON-NLS-1$
        assertNull(InfobaseAccessSupport.accessError("OS")); //$NON-NLS-1$
        assertNull(InfobaseAccessSupport.accessError("os")); //$NON-NLS-1$
    }

    @Test
    public void testAccessErrorRejectsOutOfEnumValue()
    {
        String err = InfobaseAccessSupport.accessError("OOPS"); //$NON-NLS-1$
        assertNotNull("a non-empty out-of-enum access must be rejected", err); //$NON-NLS-1$
        assertTrue("error must name the bad value", err.contains("OOPS")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must list the allowed kinds", //$NON-NLS-1$
            err.contains("INFOBASE") && err.contains("OS")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== #275: storeCredentials(IApplication) adapter fallback ====================
    // A non-IInfobaseApplication (e.g. a wst-server standalone-server application) must be adapted
    // to an InfobaseReference — first the application itself, then its module (moduleOfApplication)
    // — before being rejected. In this headless unit JVM no adapter factory is registered (no OSGi
    // platform running), so Adapters.adapt always misses; this exercises the adapter-miss rejection
    // path with its new, more actionable wording. The success path (adapter hits, updateSettings
    // commits) needs a live EDT and is verified on the e2e stand.

    @Test
    public void testStoreCredentialsForNonInfobaseApplicationWithNoAdapterRejectsActionably()
    {
        // A stub application that is NOT an IInfobaseApplication and exposes no getModule() (so the
        // module-fallback probe also misses) — mirrors a wst-server application in an environment
        // where Adapters.adapt cannot resolve it (no StandaloneServerAdapterFactory registered).
        IApplication app = mock(IApplication.class);
        when(app.getId()).thenReturn("wst-app-1"); //$NON-NLS-1$

        String error = InfobaseAccessSupport.storeCredentials(app, "Admin", "", InfobaseAccess.INFOBASE); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("must reject when neither the app nor its module adapts to an InfobaseReference", //$NON-NLS-1$
            error);
        assertTrue("error must name the application id", error.contains("wst-app-1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must mention infobases", error.toLowerCase().contains("infobase")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must mention standalone servers", error.toLowerCase().contains("standalone")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testModuleOfApplicationReturnsNullWhenNoGetModuleMethod()
    {
        // A plain IApplication (no getModule()) — the reflective probe must degrade to null, not throw.
        IApplication app = mock(IApplication.class);
        assertNull(InfobaseAccessSupport.moduleOfApplication(app));
    }

    @Test
    public void testModuleOfApplicationInvokesGetModuleWhenPresent()
    {
        // A stub exposing getModule() (mirrors a wst-server IServerApplication) — the reflective
        // probe must find and invoke it, returning the module verbatim.
        IApplication app = mock(IApplication.class, withSettings().extraInterfaces(StubModuleAccessor.class));
        Object module = new Object();
        when(((StubModuleAccessor)app).getModule()).thenReturn(module);

        assertSame(module, InfobaseAccessSupport.moduleOfApplication(app));
    }

    /** Stands in for {@code IServerApplication}'s {@code getModule()} accessor (not on {@link IApplication}). */
    public interface StubModuleAccessor
    {
        Object getModule();
    }
}
