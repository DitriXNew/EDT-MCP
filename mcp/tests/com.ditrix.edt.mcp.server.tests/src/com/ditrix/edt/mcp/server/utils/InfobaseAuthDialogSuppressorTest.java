/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link InfobaseAuthDialogSuppressor#isAuthDialogTitle(String)} — the pure title-prefix
 * matcher for EDT's "Configure Infobase access Settings" dialog (#194). The SWT install/cancel path
 * needs a live workbench display and is verified on the e2e stand.
 */
public class InfobaseAuthDialogSuppressorTest
{
    @Test
    public void testEnglishTitleWithInfobaseNameMatches()
    {
        assertTrue(InfobaseAuthDialogSuppressor.isAuthDialogTitle(
            "Configure Infobase \"ERP\" access Settings")); //$NON-NLS-1$
    }

    @Test
    public void testEnglishTitleNoNameMatches()
    {
        assertTrue(InfobaseAuthDialogSuppressor.isAuthDialogTitle(
            "Configure Infobase Access Settings")); //$NON-NLS-1$
    }

    @Test
    public void testRussianTitleWithInfobaseNameMatches()
    {
        // "Сконфигурируйте доступ к информационной базе \"ERP\""
        assertTrue(InfobaseAuthDialogSuppressor.isAuthDialogTitle(
            "Сконфигурируйте доступ к информационной базе \"ERP\"")); //$NON-NLS-1$
    }

    @Test
    public void testRussianTitleNoNameMatches()
    {
        assertTrue(InfobaseAuthDialogSuppressor.isAuthDialogTitle(
            "Сконфигурируйте доступ к информационной базе")); //$NON-NLS-1$
    }

    @Test
    public void testUnrelatedTitlesDoNotMatch()
    {
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle("Application update")); //$NON-NLS-1$
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle("Restructure data")); //$NON-NLS-1$
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle("Question")); //$NON-NLS-1$
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle("Some other dialog")); //$NON-NLS-1$
    }

    @Test
    public void testNullAndEmptyDoNotMatch()
    {
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle(null));
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle("")); //$NON-NLS-1$
    }
}
