/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.CheckoutReloads.State;

/** #922: the registry that scopes the reload refusal to the projects of this server's own checkout. */
public class CheckoutReloadsTest
{
    private static final long T0 = 1_000_000_000L;

    private static final java.util.function.BooleanSupplier IDLE = () -> false;

    private static long ms(long millis)
    {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    @Before
    @After
    public void forget()
    {
        CheckoutReloads.clear();
    }

    @Test
    public void anUnregisteredProjectIsSettled()
    {
        assertFalse(CheckoutReloads.isPending("Never", T0)); //$NON-NLS-1$
        assertEquals(State.SETTLED, CheckoutReloads.observe("Never", true, T0)); //$NON-NLS-1$
    }

    /** Idle must hold for STABLE_MS without interruption; a loading sample restarts the count. */
    @Test
    public void aReloadEndsOnlyAfterAnUninterruptedIdleSpan()
    {
        CheckoutReloads.register(List.of("P"), IDLE, T0); //$NON-NLS-1$

        assertEquals(State.SETTLING, CheckoutReloads.observe("P", false, T0)); //$NON-NLS-1$
        assertEquals(State.LOADING, CheckoutReloads.observe("P", true, T0 + ms(1_000))); //$NON-NLS-1$
        assertEquals(State.SETTLING, CheckoutReloads.observe("P", false, T0 + ms(1_500))); //$NON-NLS-1$
        assertEquals("the earlier idle sample does not count after a loading one", State.SETTLING, //$NON-NLS-1$
            CheckoutReloads.observe("P", false, T0 + ms(2_500))); //$NON-NLS-1$
        assertTrue(CheckoutReloads.isPending("P", T0 + ms(2_500))); //$NON-NLS-1$
        assertEquals(State.SETTLED,
            CheckoutReloads.observe("P", false, T0 + ms(1_500 + CheckoutReloads.STABLE_MS))); //$NON-NLS-1$
        assertFalse("a settled reload is unregistered", CheckoutReloads.isPending("P", T0 + ms(4_000))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A reload that never shows its end cannot hold writes back past EXPIRY_MS. */
    @Test
    public void aRegistrationExpires()
    {
        CheckoutReloads.register(List.of("P"), IDLE, T0); //$NON-NLS-1$

        assertTrue(CheckoutReloads.isPending("P", T0 + ms(CheckoutReloads.EXPIRY_MS - 1))); //$NON-NLS-1$
        assertFalse(CheckoutReloads.isPending("P", T0 + ms(CheckoutReloads.EXPIRY_MS))); //$NON-NLS-1$
        assertEquals(State.SETTLED, CheckoutReloads.observe("P", true, T0 + ms(CheckoutReloads.EXPIRY_MS))); //$NON-NLS-1$
    }

    /** A second checkout restarts the wait of its projects. */
    @Test
    public void aNewCheckoutRestartsTheWait()
    {
        CheckoutReloads.register(List.of("P"), IDLE, T0); //$NON-NLS-1$
        CheckoutReloads.observe("P", false, T0); //$NON-NLS-1$
        CheckoutReloads.register(List.of("P"), IDLE, T0 + ms(1_900)); //$NON-NLS-1$

        assertEquals(State.SETTLING, CheckoutReloads.observe("P", false, T0 + ms(2_100))); //$NON-NLS-1$
    }

    /**
     * Codex P1 on #928: while this server's own post-checkout refresh still runs, the registration is loading
     * whatever the other signals say - a refresh still scanning has reported nothing yet.
     */
    @Test
    public void aRegistrationCannotSettleWhileItsRefreshRuns()
    {
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        CheckoutReloads.register(List.of("P"), running::get, T0); //$NON-NLS-1$

        assertEquals(State.LOADING, CheckoutReloads.observe("P", false, T0)); //$NON-NLS-1$
        assertEquals(State.LOADING, CheckoutReloads.observe("P", false, T0 + ms(5 * CheckoutReloads.STABLE_MS))); //$NON-NLS-1$
        assertTrue(CheckoutReloads.isPending("P", T0 + ms(5 * CheckoutReloads.STABLE_MS))); //$NON-NLS-1$

        running.set(false);
        long t = T0 + ms(6 * CheckoutReloads.STABLE_MS);
        assertEquals("the stable span starts only once the refresh ended", State.SETTLING, //$NON-NLS-1$
            CheckoutReloads.observe("P", false, t)); //$NON-NLS-1$
        assertEquals(State.SETTLED, CheckoutReloads.observe("P", false, t + ms(CheckoutReloads.STABLE_MS))); //$NON-NLS-1$
    }

    /**
     * Codex P2 on #928: a newer checkout that replaces the registration while an observation is about to release the
     * old one is never reported settled - the newer registration is observed instead.
     */
    @Test
    public void aRegistrationReplacedDuringTheObservationIsNotReportedSettled()
    {
        CheckoutReloads.register(List.of("P"), IDLE, T0); //$NON-NLS-1$
        CheckoutReloads.observe("P", false, T0); //$NON-NLS-1$
        long settleAt = T0 + ms(CheckoutReloads.STABLE_MS);
        CheckoutReloads.beforeSettle = () -> {
            CheckoutReloads.beforeSettle = null;
            CheckoutReloads.register(List.of("P"), IDLE, settleAt); //$NON-NLS-1$
        };

        assertEquals(State.SETTLING, CheckoutReloads.observe("P", false, settleAt)); //$NON-NLS-1$
        assertTrue("the newer registration stays", CheckoutReloads.isPending("P", settleAt)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Codex P3 on #928: expired registrations that nobody asks about are dropped on the next registration. */
    @Test
    public void expiredRegistrationsArePurgedOnTheNextRegistration()
    {
        CheckoutReloads.register(List.of("NeverAsked1", "NeverAsked2"), IDLE, T0); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, CheckoutReloads.size());

        CheckoutReloads.register(List.of("Fresh"), IDLE, T0 + ms(CheckoutReloads.EXPIRY_MS)); //$NON-NLS-1$

        assertEquals(1, CheckoutReloads.size());
    }

    /** Only the registered projects are pending. */
    @Test
    public void registrationIsPerProject()
    {
        CheckoutReloads.register(List.of("A", "B"), IDLE, T0); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(CheckoutReloads.isPending("A", T0)); //$NON-NLS-1$
        assertTrue(CheckoutReloads.isPending("B", T0)); //$NON-NLS-1$
        assertFalse(CheckoutReloads.isPending("C", T0)); //$NON-NLS-1$
    }
}
