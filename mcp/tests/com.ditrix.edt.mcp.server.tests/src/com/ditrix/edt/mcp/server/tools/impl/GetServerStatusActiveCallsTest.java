/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.InFlightToolCalls;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** {@code get_server_status} reports the running calls and background jobs (#993). */
public class GetServerStatusActiveCallsTest
{
    @Test
    public void testInjectedCallsAndJobsRenderAsSpecified() throws Exception
    {
        InFlightToolCalls.Report calls = new InFlightToolCalls.Report(List.of(
            new InFlightToolCalls.Snapshot("validate_query", "P", 1_763_630_000_000L, 4210L, false), //$NON-NLS-1$ //$NON-NLS-2$
            new InFlightToolCalls.Snapshot("update_database", null, 1_763_630_000_500L, 3700L, true)), 0); //$NON-NLS-1$
        try (BackgroundJobs jobs = new BackgroundJobs(10, 1))
        {
            CountDownLatch release = new CountDownLatch(1);
            JobSnapshot running = jobs.start("rename_metadata_object", 60_000L, "start", progress -> { //$NON-NLS-1$ //$NON-NLS-2$
                release.await(10, TimeUnit.SECONDS);
                return "done"; //$NON-NLS-1$
            });
            try
            {
                JsonObject status = execute(calls, jobs);

                JsonArray active = status.getAsJsonArray("activeCalls"); //$NON-NLS-1$
                assertEquals(2, active.size());
                JsonObject first = active.get(0).getAsJsonObject();
                assertEquals("validate_query", first.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals("P", first.get("projectName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(1_763_630_000_000L, first.get("startedAtMs").getAsLong()); //$NON-NLS-1$
                assertEquals(4210L, first.get("elapsedMs").getAsLong()); //$NON-NLS-1$
                assertFalse("responseSent is reported only when true", first.has("responseSent")); //$NON-NLS-1$
                JsonObject second = active.get(1).getAsJsonObject();
                assertEquals("update_database", second.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
                assertFalse("a call without a projectName reports none", second.has("projectName")); //$NON-NLS-1$
                assertTrue(second.get("responseSent").getAsBoolean()); //$NON-NLS-1$
                assertFalse("nothing over the cap, so no untracked count", status.has("activeCallsUntracked")); //$NON-NLS-1$

                JsonArray jobsJson = status.getAsJsonArray("runningJobs"); //$NON-NLS-1$
                assertEquals(1, jobsJson.size());
                JsonObject job = jobsJson.get(0).getAsJsonObject();
                assertEquals(running.getId(), job.get("jobId").getAsString()); //$NON-NLS-1$
                assertEquals("rename_metadata_object", job.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(running.getStartedAtMs(), job.get("startedAtMs").getAsLong()); //$NON-NLS-1$
                assertTrue(job.get("elapsedMs").getAsLong() >= 0L); //$NON-NLS-1$
                assertEquals(java.util.Set.of("jobId", "tool", "startedAtMs", "elapsedMs"), job.keySet()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
            finally
            {
                release.countDown();
            }
            JobSnapshot done = jobs.await(running.getId(), 10_000L);
            assertEquals(BackgroundJobs.Status.DONE, done.getStatus());
            assertEquals("a finished job is no longer running", 0,
                execute(calls, jobs).getAsJsonArray("runningJobs").size()); //$NON-NLS-1$
        }
    }

    @Test
    public void testEmptyListsArePresent()
    {
        JsonObject status = JsonParser.parseString(new GetServerStatusTool(() -> null, () -> false,
            () -> new InFlightToolCalls.Report(List.of(), 0), List::of).execute(Map.of())).getAsJsonObject();
        assertEquals("an empty array is the statement 'nothing is running'", 0,
            status.getAsJsonArray("activeCalls").size()); //$NON-NLS-1$
        assertEquals(0, status.getAsJsonArray("runningJobs").size()); //$NON-NLS-1$
    }

    @Test
    public void testUntrackedCallsAreCounted()
    {
        JsonObject status = JsonParser.parseString(new GetServerStatusTool(() -> null, () -> false,
            () -> new InFlightToolCalls.Report(List.of(), 3), List::of).execute(Map.of())).getAsJsonObject();
        assertEquals(3, status.get("activeCallsUntracked").getAsInt()); //$NON-NLS-1$
    }

    @Test
    public void testTheDefaultToolLeavesItsOwnCallOutAndListsOthers()
    {
        String other = "other_" + UUID.randomUUID(); //$NON-NLS-1$
        try (InFlightToolCalls.Handle otherCall = InFlightToolCalls.begin(other, "OtherProject", null); //$NON-NLS-1$
            InFlightToolCalls.Handle ownCall = InFlightToolCalls.begin(GetServerStatusTool.NAME, null, null))
        {
            // The production wiring: the registry and the shared job registry, no injected lists.
            JsonObject status = JsonParser.parseString(new GetServerStatusTool(() -> null, () -> false)
                .execute(Map.of())).getAsJsonObject();
            JsonArray active = status.getAsJsonArray("activeCalls"); //$NON-NLS-1$
            boolean sawOther = false;
            for (int i = 0; i < active.size(); i++)
            {
                String tool = active.get(i).getAsJsonObject().get("tool").getAsString(); //$NON-NLS-1$
                assertFalse("the caller's own get_server_status call must not be listed",
                    GetServerStatusTool.NAME.equals(tool));
                sawOther |= other.equals(tool);
            }
            assertTrue("a concurrent call is listed", sawOther);
        }
    }

    @Test
    public void testOutputSchemaDeclaresTheRunningWork()
    {
        JsonObject properties = JsonParser.parseString(new GetServerStatusTool().getOutputSchema())
            .getAsJsonObject().getAsJsonObject("properties"); //$NON-NLS-1$
        assertEquals("array", properties.getAsJsonObject("activeCalls").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("array", properties.getAsJsonObject("runningJobs").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("integer", properties.getAsJsonObject("activeCallsUntracked").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testTheDescriptionSaysItShowsRunningWork()
    {
        assertTrue(new GetServerStatusTool().getDescription().contains("running right now")); //$NON-NLS-1$
    }

    private static JsonObject execute(InFlightToolCalls.Report calls, BackgroundJobs jobs)
    {
        return JsonParser.parseString(new GetServerStatusTool(() -> null, () -> false, () -> calls,
            jobs::running).execute(Map.of())).getAsJsonObject();
    }
}
