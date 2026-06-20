/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
}
