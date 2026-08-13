/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;


import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.WorkmateGateway.GatewayException;

/**
 * The publisher runs next to an OPTIONAL plugin, so its contract is that a missing or
 * half-started 1C:Workmate never becomes an EDT-MCP failure - it is retried instead.
 */
public class WorkmateChatSessionPublisherTest
{
    @Test
    public void testMissingWorkmateIsRetriedRatherThanThrown()
    {
        AtomicInteger attempts = new AtomicInteger();
        WorkmateChatSessionPublisher publisher =
            new WorkmateChatSessionPublisher(new WorkmateGateway()
            {
                @Override
                public String ensureChatSession() throws GatewayException
                {
                    attempts.incrementAndGet();
                    throw GatewayException.notInstalled("no com.e1c.edt.ai bundle"); //$NON-NLS-1$
                }
            });

        assertFalse(publisher.publishOnce());
        assertFalse(publisher.publishOnce());
        assertEquals(2, attempts.get());
    }

    @Test
    public void testSessionIsRepublishedAfterWorkmateEvictsIt()
    {
        AtomicInteger attempts = new AtomicInteger();
        WorkmateChatSessionPublisher publisher =
            new WorkmateChatSessionPublisher(new WorkmateGateway()
            {
                @Override
                public String ensureChatSession() throws GatewayException
                {
                    // Live, then evicted (12 h idle or pushed out), then live again.
                    if (attempts.incrementAndGet() == 2)
                    {
                        throw GatewayException.notReady("Workmate returned no JShell session"); //$NON-NLS-1$
                    }
                    return WorkmateGateway.CHAT_SESSION_ID;
                }
            });

        assertTrue(publisher.publishOnce());
        assertFalse(publisher.publishOnce());
        assertTrue(publisher.publishOnce());
    }

    @Test
    public void testStartIsIdempotentAndStopIsSafeWithoutStart()
    {
        WorkmateChatSessionPublisher publisher =
            new WorkmateChatSessionPublisher(new WorkmateGateway()
            {
                @Override
                public String ensureChatSession()
                {
                    return WorkmateGateway.CHAT_SESSION_ID;
                }
            });
        publisher.start();
        publisher.start();
        publisher.stop();
        publisher.stop();
    }

    /**
     * A session must never outlive the bundle, including one created by an attempt that was
     * already inside the gateway when shutdown began - interrupting the scheduler does not
     * stop that attempt.
     */
    @Test
    public void testNothingIsPublishedAfterStopAndWhatWasPublishedIsDiscarded()
    {
        AtomicInteger discarded = new AtomicInteger();
        AtomicInteger published = new AtomicInteger();
        WorkmateChatSessionPublisher publisher =
            new WorkmateChatSessionPublisher(new WorkmateGateway()
            {
                @Override
                public String ensureChatSession()
                {
                    published.incrementAndGet();
                    return WorkmateGateway.CHAT_SESSION_ID;
                }

                @Override
                public void discardChatSession()
                {
                    discarded.incrementAndGet();
                }
            });

        assertTrue(publisher.publishOnce());
        publisher.stop();

        assertEquals("stopping must discard the session it owns", 1, discarded.get());
        assertFalse("a later attempt must not publish again", publisher.publishOnce());
        assertEquals("and must not even reach the gateway", 1, published.get());
    }

    @Test
    public void testConstantsAreTheOnesTheProjectRulesName()
    {
        // The rules files hardcode these two literals; changing either here silently
        // breaks every chat that follows the committed instructions.
        assertEquals("edt-mcp", WorkmateGateway.CHAT_SESSION_ID); //$NON-NLS-1$
        assertEquals("jshell_edt_canonical_imports", WorkmateGateway.CHAT_MANUAL_ID); //$NON-NLS-1$
    }
}
