/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * When the silence rule may end a turn, and when it must stand down.
 *
 * <p>The activity it reads is process-wide, because Workmate's bridge call carries no
 * conversation identity. That is fine while one job runs and misleading while two do, which is
 * the distinction these tests pin.
 */
public class WorkmateIdleTimeoutTest
{
    @Before
    public void shrinkIdleWindow()
    {
        WorkmateGateway.setIdleTimingsForTest(200L, 20L);
    }

    @After
    public void restoreIdleWindow()
    {
        WorkmateGateway.resetIdleTimingsForTest();
    }

    @Test
    public void testASilentTurnIsEndedWhenItIsTheOnlyOne() throws Exception
    {
        CompletableFuture<Object> neverCompletes = new CompletableFuture<>();
        try
        {
            WorkmateGateway.awaitTurn(neverCompletes, 5_000L);
            fail("a silent turn must not be waited out to the end of the budget");
        }
        catch (TimeoutException e)
        {
            assertTrue("silence, not the budget, must be what ended it",
                WorkmateGateway.isIdleTimeout(e));
        }
    }

    @Test
    public void testTheIdleRuleStandsDownWhileASecondTurnIsAwaited() throws Exception
    {
        // Review of #440: the counters cannot say WHICH turn was active, so with two turns in
        // flight, activity from one would keep the other alive - or its silence would end a
        // conversation that was working. The budget is the honest bound there.
        CompletableFuture<Object> otherTurn = new CompletableFuture<>();
        Thread other = new Thread(() -> {
            try
            {
                WorkmateGateway.awaitTurn(otherTurn, 10_000L);
            }
            catch (Exception ignored) // NOSONAR the second turn only needs to occupy a slot
            {
                Thread.currentThread().interrupt();
            }
        });
        other.setDaemon(true);
        other.start();
        long deadline = System.currentTimeMillis() + 5_000L;
        while (WorkmateGateway.awaitedTurns() < 1 && System.currentTimeMillis() < deadline)
        {
            Thread.yield();
        }

        CompletableFuture<Object> mine = new CompletableFuture<>();
        try
        {
            WorkmateGateway.awaitTurn(mine, 600L);
            fail("the wait must still end - on the budget");
        }
        catch (TimeoutException e)
        {
            assertFalse("with a second turn awaited, silence proves nothing about this one",
                WorkmateGateway.isIdleTimeout(e));
        }
        finally
        {
            otherTurn.complete("done");
            other.join(2_000L);
        }
    }

    @Test
    public void testTheIdleClockRestartsWhenTheSecondTurnEnds() throws Exception
    {
        // Review of #440: the silence measured while two turns overlapped is not evidence
        // about either of them. If it were kept, the survivor would be cut the moment the
        // other turn ended - without ever having been watched alone for a full window.
        CompletableFuture<Object> otherTurn = new CompletableFuture<>();
        Thread other = new Thread(() -> {
            try
            {
                WorkmateGateway.awaitTurn(otherTurn, 10_000L);
            }
            catch (Exception ignored) // NOSONAR the second turn only needs to occupy a slot
            {
                Thread.currentThread().interrupt();
            }
        });
        other.setDaemon(true);
        other.start();
        while (WorkmateGateway.awaitedTurns() < 1)
        {
            Thread.yield();
        }

        // The overlap lasts far longer than the idle window; the survivor must still get a
        // FULL window to itself afterwards, so a budget this size cannot be reached.
        CompletableFuture<Object> mine = new CompletableFuture<>();
        Thread ender = new Thread(() -> {
            try
            {
                Thread.sleep(600L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            otherTurn.complete("done");
        });
        ender.setDaemon(true);
        ender.start();

        long started = System.nanoTime();
        try
        {
            WorkmateGateway.awaitTurn(mine, 5_000L);
            fail("the survivor must still be cut once it has been silent on its own");
        }
        catch (TimeoutException e)
        {
            assertTrue("silence, once measurable again, must be what ends it",
                WorkmateGateway.isIdleTimeout(e));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertTrue("the idle window must be measured AFTER the overlap ended, not across"
                + " it (elapsed " + elapsedMs + "ms)", elapsedMs >= 700L);
        }
        finally
        {
            other.join(2_000L);
            ender.join(2_000L);
        }
    }
}
