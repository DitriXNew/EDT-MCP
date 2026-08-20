/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.WorkmateResponse;

/**
 * Tests that a Workmate answer says whether Workmate itself called it finished.
 *
 * <p>The adapter asks for an explicit end-of-answer marker on every request. When it never
 * arrives - the conversation went quiet, or the continuations ran out - the text is still
 * reported, because it is what Workmate produced; but reporting it as an ANSWER would be the
 * same false confidence issue #427 was about. So the report says so, above the text.
 */
public class WorkmateFinalityNoteTest
{
    private static String renderAnswer(WorkmateResponse response) throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 2))
        {
            JobSnapshot started = jobs.start(TimeUnit.SECONDS.toMillis(30), "accepted",
                progress -> response);
            JobSnapshot done = jobs.await(started.getId(), 5_000L);
            return BackgroundJobRenderer.render(done);
        }
    }

    @Test
    public void testADeclaredAnswerCarriesNoWarning() throws Exception
    {
        String rendered = renderAnswer(
            new WorkmateResponse("ValueTable holds rows and typed columns.", null, null, 1, true,
                false));
        assertTrue(rendered.contains("ValueTable holds rows and typed columns."));
        assertFalse("a declared final answer must not be hedged",
            rendered.contains("Completion not confirmed"));
    }

    @Test
    public void testAnUndeclaredAnswerIsMarkedAsUnconfirmed() throws Exception
    {
        String rendered = renderAnswer(
            new WorkmateResponse("Partial notes on ValueTable.", null, null, 5, false, false));
        assertTrue("the caller must see that this was not declared final",
            rendered.contains("Completion not confirmed"));
        assertTrue(rendered.contains("never sent its end-of-answer marker"));
        assertTrue("the text itself is still reported",
            rendered.contains("Partial notes on ValueTable."));
    }

    @Test
    public void testAQuietConversationSaysThatItWentQuiet() throws Exception
    {
        String rendered = renderAnswer(
            new WorkmateResponse("Half an answer.", null, null, 2, false, true));
        assertTrue(rendered.contains("Completion not confirmed"));
        assertTrue("the reason matters: silence is not the same as running out of turns",
            rendered.contains("no sign of work"));
        assertTrue("and the report must say what it actually measured",
            rendered.contains("only the calls Workmate makes back into it"));
    }

    @Test
    public void testAPlanLeftBehindBySilenceIsNotCalledAnAnswer() throws Exception
    {
        // Review of #440: when the conversation goes quiet with nothing but an announcement in
        // hand, the text is still worth reporting - it says where Workmate stopped - but it was
        // never accepted as an answer, and "may be partial" is far too mild for that.
        String rendered = renderAnswer(new WorkmateResponse(
            "I will inspect the module and report back.", null, null, 5, false, true, false));
        assertTrue(rendered.contains("Completion not confirmed"));
        assertTrue("a plan must be named as a plan", rendered.contains("Not an answer"));
        assertTrue("and the caller must be told the work may be half-done",
            rendered.contains("inspect the project"));
    }

    @Test
    public void testAnAcceptedAnswerIsNotCalledAPlan() throws Exception
    {
        // The same note must NOT appear over a real answer that merely lacks the marker.
        String rendered = renderAnswer(new WorkmateResponse(
            "ValueTable holds rows and typed columns.", null, null, 2, false, true));
        assertTrue(rendered.contains("Completion not confirmed"));
        assertFalse("this text WAS accepted as an answer", rendered.contains("Not an answer"));
    }
}
