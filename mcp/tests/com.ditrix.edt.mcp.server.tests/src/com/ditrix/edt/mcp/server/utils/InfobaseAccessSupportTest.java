/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;

/**
 * Tests for {@link InfobaseAccessSupport#parseAccess(String)} — the access-kind argument parser
 * (#194). The store/read path (Guice injector -&gt; {@code IInfobaseAccessManager} -&gt;
 * {@code updateSettings}) needs a live EDT and is verified on the e2e stand.
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
}
