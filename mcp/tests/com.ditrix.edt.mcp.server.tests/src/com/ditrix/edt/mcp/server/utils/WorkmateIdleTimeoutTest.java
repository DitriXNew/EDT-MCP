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
}
