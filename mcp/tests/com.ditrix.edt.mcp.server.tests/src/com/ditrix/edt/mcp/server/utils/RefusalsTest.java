/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IStatus;
import org.junit.After;
import org.junit.Test;

/**
 * Tests for {@link Refusals}, which decides whether a failed operation is OUR validation refusing
 * bad input (INFO, no stack) or a genuine failure (ERROR, with its stack).
 * <p>
 * Both directions are pinned, because the change is about WHICH THINGS QUALIFY: a test that only
 * proved refusals go quiet would pass for an implementation that silenced everything. The central
 * case is {@link #testSameExceptionClassOppositeVerdict()}: two {@code IllegalStateException}s with
 * the SAME message get opposite severities, which is only possible if the discriminator is the
 * marker set at the throw site and not the exception class.
 */
public class RefusalsTest
{
    /** A realistic refusal message, in the shape the form writer produces. */
    private static final String REFUSAL =
        "Form attribute 'NoSuchAttr_zz' not found - create it first, then bind the field to it."; //$NON-NLS-1$

    /** The log context a call site passes. */
    private static final String CONTEXT = "Error creating form member"; //$NON-NLS-1$

    // ========== the refusal direction: INFO, no stack ==========

    @Test
    public void testMarkedRefusalLogsAtInfoWithoutAStack()
    {
        IStatus status = Refusals.statusFor(CONTEXT, Refusals.state(REFUSAL));

        // INFO, not WARNING: McpProtocolHandler already logs the caller-visible message at
        // WARNING for every failed tools/call, so a second WARNING would just move the noise.
        assertEquals("a marked refusal must not be logged at ERROR", //$NON-NLS-1$
            IStatus.INFO, status.getSeverity());
        assertNull("a refusal must carry NO stack - the caller already has the message", //$NON-NLS-1$
            status.getException());
        assertTrue("the refusal text must survive into the log entry, or the entry is useless", //$NON-NLS-1$
            status.getMessage().contains(REFUSAL));
        assertTrue("the call site's context must survive too", //$NON-NLS-1$
            status.getMessage().contains(CONTEXT));
    }

    @Test
    public void testMarkedArgumentRefusalAlsoGoesQuiet()
    {
        IStatus status = Refusals.statusFor(CONTEXT, Refusals.argument(REFUSAL));

        assertEquals(IStatus.INFO, status.getSeverity());
        assertNull(status.getException());
    }

    @Test
    public void testFormValidationExceptionIsARefusal()
    {
        // The ready-JSON refusal normally short-circuits before the log; when a path does not
        // short-circuit it must still not land at ERROR.
        IStatus status = Refusals.statusFor(CONTEXT, new FormValidationException("{\"error\":\"x\"}")); //$NON-NLS-1$

        assertEquals(IStatus.INFO, status.getSeverity());
        assertNull(status.getException());
    }

    @Test
    public void testFormValidationExceptionLogsItsPayloadNotItsFixedMessage()
    {
        // getMessage() is the constant "form validation failed"; the actionable text is the JSON.
        // Without logDetail() this entry would carry no information at all.
        IStatus status =
            Refusals.statusFor(CONTEXT, new FormValidationException("{\"error\":\"bad dataPath\"}")); //$NON-NLS-1$

        assertTrue("the ready JSON must reach the log entry", //$NON-NLS-1$
            status.getMessage().contains("bad dataPath")); //$NON-NLS-1$
        assertFalse("the fixed placeholder must not be what gets logged", //$NON-NLS-1$
            status.getMessage().contains("form validation failed")); //$NON-NLS-1$
    }

    // ========== the failure direction: ERROR, WITH the stack ==========

    @Test
    public void testAGenuineFailureWrappingARefusalStaysLoud()
    {
        // The demotion looks at the OUTERMOST throwable only. A cause-chain scan would discard this
        // wrapper - its message AND its stack - because something further down happens to be marked,
        // which is the silent-swallow this change exists to prevent. Measured: in all 48 entries of
        // the cited run the marked exception IS the outermost throwable, so strictness costs nothing.
        Exception wrapped = new RuntimeException("BM commit failed writing the form", //$NON-NLS-1$
            Refusals.state(REFUSAL));

        IStatus status = Refusals.statusFor(CONTEXT, wrapped);

        assertEquals("a genuine failure must not be demoted by what it wraps", //$NON-NLS-1$
            IStatus.ERROR, status.getSeverity());
        assertSame("...and it must keep its own stack", wrapped, status.getException()); //$NON-NLS-1$
    }

    @Test
    public void testUnmarkedExceptionKeepsErrorAndItsStack()
    {
        IllegalStateException platform = new IllegalStateException("BM object is not attached"); //$NON-NLS-1$

        IStatus status = Refusals.statusFor(CONTEXT, platform);

        assertEquals("an UNMARKED exception must stay loud", IStatus.ERROR, status.getSeverity()); //$NON-NLS-1$
        assertSame("the exception must be attached, so the log keeps its stack", //$NON-NLS-1$
            platform, status.getException());
        assertEquals("an error keeps the call site's context as its message", //$NON-NLS-1$
            CONTEXT, status.getMessage());
    }

    @Test
    public void testSameExceptionClassOppositeVerdict()
    {
        // The whole point: IllegalStateException is raised BOTH by our validation and by the
        // platform. Identical class, identical message - only the marker differs.
        IStatus refusal = Refusals.statusFor(CONTEXT, Refusals.state(REFUSAL));
        IStatus failure = Refusals.statusFor(CONTEXT, new IllegalStateException(REFUSAL));

        assertTrue("the marked refusal must still BE an IllegalStateException", //$NON-NLS-1$
            Refusals.state(REFUSAL) instanceof IllegalStateException);
        assertEquals(IStatus.INFO, refusal.getSeverity());
        assertEquals("demoting by CLASS would make a genuine platform defect invisible", //$NON-NLS-1$
            IStatus.ERROR, failure.getSeverity());
        assertNotNull(failure.getException());
    }

    @Test
    public void testUnmarkedCauseChainStaysLoud()
    {
        Exception wrapped = new RuntimeException("BM task failed", //$NON-NLS-1$
            new IllegalArgumentException("platform rejected the reference")); //$NON-NLS-1$

        IStatus status = Refusals.statusFor(CONTEXT, wrapped);

        assertEquals(IStatus.ERROR, status.getSeverity());
        assertSame(wrapped, status.getException());
    }

    @Test
    public void testNullExceptionStaysLoud()
    {
        // Nothing to inspect is not evidence of a refusal - default to ERROR.
        assertEquals(IStatus.ERROR, Refusals.statusFor(CONTEXT, null).getSeverity());
    }

    // ========== the client-visible message must not change ==========

    @Test
    public void testRefusalCarriesItsMessageVerbatim()
    {
        // Every converted throw site feeds its message to the caller through the exception. If the
        // marked type altered it in any way, the client-visible error would change - a regression
        // the e2e suite asserts against.
        assertEquals(REFUSAL, Refusals.state(REFUSAL).getMessage());
        assertEquals(REFUSAL, Refusals.argument(REFUSAL).getMessage());
        assertEquals(new IllegalStateException(REFUSAL).getMessage(),
            Refusals.state(REFUSAL).getMessage());
    }

    @Test
    public void testMarkedTypesStayAssignableToTheTypesTheyReplaced()
    {
        // Existing `catch (IllegalStateException)` / `catch (IllegalArgumentException)` clauses
        // must keep catching these, or the control flow changes with the log level.
        assertTrue(Refusals.state(REFUSAL) instanceof IllegalStateException);
        assertTrue(Refusals.argument(REFUSAL) instanceof IllegalArgumentException);
        assertTrue(Refusals.state(REFUSAL) instanceof RuntimeException);
    }

    // ========== messageOf, the decision on its own ==========

    @Test
    public void testMessageOfAnswersNullForAnythingUnmarked()
    {
        assertNull(Refusals.messageOf(null));
        assertNull(Refusals.messageOf(new IllegalStateException(REFUSAL)));
        assertNull(Refusals.messageOf(new RuntimeException("boom"))); //$NON-NLS-1$
        assertEquals(REFUSAL, Refusals.messageOf(Refusals.state(REFUSAL)));
        // A marker BELOW the surface does not count: the outer throwable is the real failure.
        assertNull(Refusals.messageOf(new RuntimeException("outer", Refusals.state(REFUSAL))));
    }

    // ========== log() must actually emit what statusFor decided ==========

    @After
    public void restoreTheProductionSink()
    {
        Refusals.setSink(null);
    }

    @Test
    public void testLogEmitsExactlyWhatStatusForDecided()
    {
        // Without this, a log() rewritten to call logError directly would disable the whole fix
        // while every statusFor test stayed green - the "the fix silently disabled itself" shape.
        List<IStatus> seen = new ArrayList<>();
        Refusals.setSink(seen::add);

        Refusals.log(CONTEXT, Refusals.state(REFUSAL));
        Refusals.log(CONTEXT, new IllegalStateException("unmarked")); //$NON-NLS-1$

        assertEquals("log() must emit one status per call", 2, seen.size()); //$NON-NLS-1$
        assertEquals("the refusal must reach the log as INFO", //$NON-NLS-1$
            IStatus.INFO, seen.get(0).getSeverity());
        assertNull("...with no stack", seen.get(0).getException()); //$NON-NLS-1$
        assertTrue(seen.get(0).getMessage().contains(REFUSAL));
        assertEquals("the unmarked failure must reach the log as ERROR", //$NON-NLS-1$
            IStatus.ERROR, seen.get(1).getSeverity());
        assertNotNull("...with its stack", seen.get(1).getException()); //$NON-NLS-1$
    }

    @Test
    public void testLogReachesThePlatformLogWhenNoSinkIsSet()
    {
        // The production path: no sink, so the status goes to the bundle ILog. Must not throw
        // regardless of activation state; the output itself is verified live, not headlessly.
        Refusals.log(CONTEXT, Refusals.state(REFUSAL));
        Refusals.log(CONTEXT, new IllegalStateException("unmarked")); //$NON-NLS-1$
        Refusals.log(CONTEXT, null);
    }
}
