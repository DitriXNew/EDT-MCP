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
 * Tests for the pure title-prefix matchers of {@link InfobaseAuthDialogSuppressor} — EDT's
 * "Configure Infobase access Settings" dialog and Eclipse Secure Storage's "Password Hint Needed"
 * follow-up dialog (#194). The SWT install/dismiss path needs a live workbench display and is verified
 * on the e2e stand.
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

    @Test
    public void testSecureStorageHintTitleMatches()
    {
        // pswdRecoveryOptionTitle from org.eclipse.equinox.security.ui — shipped untranslated.
        assertTrue(InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle(
            "Secure Storage - Password Hint Needed")); //$NON-NLS-1$
    }

    @Test
    public void testHintMatcherIgnoresAccessSettingsAndUnrelated()
    {
        // The two matchers are disjoint: neither claims the other's dialog, nor any unrelated one.
        assertFalse(InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle(
            "Configure Infobase \"ERP\" access Settings")); //$NON-NLS-1$
        assertFalse(InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle("Secure Storage")); //$NON-NLS-1$
        assertFalse(InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle("Some other dialog")); //$NON-NLS-1$
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle(
            "Secure Storage - Password Hint Needed")); //$NON-NLS-1$
    }

    @Test
    public void testHintMatcherNullAndEmptyDoNotMatch()
    {
        assertFalse(InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle(null));
        assertFalse(InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle("")); //$NON-NLS-1$
    }
}
