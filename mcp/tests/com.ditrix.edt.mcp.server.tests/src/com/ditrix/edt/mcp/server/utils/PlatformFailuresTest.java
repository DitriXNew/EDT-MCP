/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationException;

/**
 * Tests for {@link PlatformFailures}: an EDT failure must never reach a caller as an empty
 * sentence, the literal "null", or a generic wrapper message when the real reason is one hop
 * away in the {@link IStatus} tree.
 *
 * <p>The cases are the shapes the platform actually produces on the standalone-server update
 * path — a cancelled server operation carries {@code Status.CANCEL_STATUS}, whose message is the
 * empty string, and EDT's publish results are {@code MultiStatus} trees whose reason sits in a
 * child.
 */
public class PlatformFailuresTest
{
    private static final String PLUGIN = "com.ditrix.edt.mcp.server";

    @Test
    public void testOwnMessageWins()
    {
        assertEquals("a plain message is used as-is", "Database is locked",
            PlatformFailures.describe(new ApplicationException("Database is locked")));
    }

    @Test
    public void testBlankMessageFallsBackToTheStatusTree()
    {
        // ApplicationException(IStatus) copies status.getMessage() — the EMPTY STRING for a
        // cancelled server operation — so the exception's own message must not be trusted.
        MultiStatus status = new MultiStatus(PLUGIN, 0, "", null);
        status.add(new Status(IStatus.ERROR, PLUGIN, "Server \"S\" start attempt failed."));
        String described = PlatformFailures.describe(new ApplicationException(status));
        assertEquals("the child status carries the real reason",
            "Server \"S\" start attempt failed.", described);
    }

    @Test
    public void testSpecificChildBeatsTheGenericWrapperHeadline()
    {
        // The shape EDT actually produces: a MultiStatus whose own message is the headline the tool
        // already prints, with the reason in a child. Returning the headline would make this helper
        // hand back exactly the text it exists to replace.
        MultiStatus status = new MultiStatus(PLUGIN, 0, "Database update failed", null);
        status.add(new Status(IStatus.ERROR, PLUGIN, "port 8429 is already in use"));
        assertEquals("the child names the cause and must win", "port 8429 is already in use",
            PlatformFailures.describe(new ApplicationException(status)));
    }

    @Test
    public void testWrapperHeadlineIsStillUsedWhenNoChildCarriesText()
    {
        MultiStatus status = new MultiStatus(PLUGIN, 0, "Database update failed", null);
        status.add(new Status(IStatus.ERROR, PLUGIN, ""));
        assertEquals("with nothing better available the headline is the answer",
            "Database update failed", PlatformFailures.describe(new ApplicationException(status)));
    }

    @Test
    public void testStatusExceptionMessageIsUsedWhenTheStatusItselfIsBlank()
    {
        IStatus status = new Status(IStatus.ERROR, PLUGIN, "", new IllegalStateException("no shell"));
        assertEquals("the status's own exception is consulted", "no shell",
            PlatformFailures.describe(new ApplicationException(status)));
    }

    @Test
    public void testCauseChainIsWalked()
    {
        CoreException cause = new CoreException(new Status(IStatus.ERROR, PLUGIN, "ports busy"));
        ApplicationException outer = new ApplicationException(new Status(IStatus.ERROR, PLUGIN, "", cause));
        assertEquals("the cause's status message is reached", "ports busy",
            PlatformFailures.describe(outer));
    }

    @Test
    public void testTextlessCancelIsNamedByItsSeverity()
    {
        // The exact shape of an auto-cancelled standalone-server operation: nothing anywhere in
        // the failure carries text, so the severity IS the diagnosis — and "Database update
        // failed: " with nothing after it is what this replaces.
        String described = PlatformFailures.describe(new ApplicationException(Status.CANCEL_STATUS));
        assertTrue("a textless failure must name the exception type",
            described.contains("ApplicationException"));
        assertTrue("a textless failure must report the status severity",
            described.contains("CANCEL"));
        assertFalse("nothing may render as the literal null", described.contains("null"));
    }

    @Test
    public void testNullFailureIsDescribedNotThrown()
    {
        assertFalse("a null failure must not produce the literal null",
            PlatformFailures.describe(null).contains("null"));
    }

    @Test
    public void testMessagelessExceptionWithoutAStatus()
    {
        String described = PlatformFailures.describe(new IllegalStateException());
        assertTrue("the type is the only thing known", described.contains("IllegalStateException"));
        assertFalse("no severity may be claimed without a status", described.contains("severity"));
    }

    @Test
    public void testDescriptionIsTrimmed()
    {
        assertEquals("surrounding whitespace is not part of the reason", "boom",
            PlatformFailures.describe(new ApplicationException("  boom  ")));
    }
}
