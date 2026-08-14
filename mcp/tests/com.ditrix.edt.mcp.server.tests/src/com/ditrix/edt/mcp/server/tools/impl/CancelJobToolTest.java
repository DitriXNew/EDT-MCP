/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.Status;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Contract and commit-handshake tests for {@link CancelJobTool}. */
public class CancelJobToolTest
{
    private BackgroundJobs jobs;
    private CancelJobTool tool;

    @Before
    public void setUp()
    {
        jobs = new BackgroundJobs(20, 2);
        tool = new CancelJobTool(jobs);
    }

    @After
    public void tearDown()
    {
        jobs.close();
    }

    @Test
    public void testContractAndDestructiveAnnotation()
    {
        assertEquals(CancelJobTool.NAME, tool.getName());
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
        assertTrue(tool.getDescription().contains("get_tool_guide('cancel_job')")); //$NON-NLS-1$

        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue(properties.has("jobId")); //$NON-NLS-1$
        assertTrue(properties.has("confirm")); //$NON-NLS-1$
        assertTrue(schema.getAsJsonArray("required").contains( //$NON-NLS-1$
            JsonParser.parseString("\"jobId\""))); //$NON-NLS-1$
        assertFalse(schema.getAsJsonArray("required").contains( //$NON-NLS-1$
            JsonParser.parseString("\"confirm\""))); //$NON-NLS-1$

        ToolAnnotations annotations = ToolAnnotationClassifier.classify(CancelJobTool.NAME);
        assertEquals(Boolean.FALSE, annotations.getReadOnlyHint());
        assertEquals(Boolean.TRUE, annotations.getDestructiveHint());
    }

    @Test
    public void testMissingUnknownAndBlankJobIdsAreActionable()
    {
        assertContains(tool.execute(Map.of()), "jobId is required", "tool that started"); //$NON-NLS-1$ //$NON-NLS-2$
        assertContains(tool.execute(Map.of("jobId", "   ")), //$NON-NLS-1$ //$NON-NLS-2$
            "jobId", "non-empty", "tool that started"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertContains(tool.execute(Map.of("jobId", "expired-17")), //$NON-NLS-1$ //$NON-NLS-2$
            "Unknown or expired jobId 'expired-17'", "tool that originally created", //$NON-NLS-1$ //$NON-NLS-2$
            "cancel_job"); //$NON-NLS-1$
    }

    @Test
    public void testPreviewNamesOwnerStateAndProgressWithoutChangingJob() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobSnapshot started = jobs.start("preview_owner", 5_000L, "Accepted preview target", progress -> { //$NON-NLS-1$ //$NON-NLS-2$
            entered.countDown();
            release.await();
            return "done"; //$NON-NLS-1$
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try
        {
            String preview = tool.execute(Map.of("jobId", started.getId())); //$NON-NLS-1$
            assertContains(preview, "cancellation: preview", "No change was made", //$NON-NLS-1$ //$NON-NLS-2$
                "owned by `preview_owner`", "state is `running`", //$NON-NLS-1$ //$NON-NLS-2$
                "Accepted preview target", "confirm=true"); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(Status.RUNNING, jobs.get(started.getId()).getStatus());
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testConfirmCancelsBeforeCommit() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobSnapshot started = jobs.start("cancellable_owner", 5_000L, "Accepted", progress -> { //$NON-NLS-1$ //$NON-NLS-2$
            entered.countDown();
            release.await();
            return "must not publish"; //$NON-NLS-1$
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        String result = tool.execute(Map.of("jobId", started.getId(), "confirm", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertContains(result, "cancellation: cancelled", "was cancelled before", //$NON-NLS-1$ //$NON-NLS-2$
            "| status | cancelled |", "Cancelled before the owning tool"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Status.CANCELLED, jobs.get(started.getId()).getStatus());
        release.countDown();
    }

    @Test
    public void testConfirmNeverClaimsCommittedExternalWorkWasCancelled() throws Exception
    {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobSnapshot started = jobs.start("external_owner", 5_000L, "Accepted", progress -> { //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(progress.tryCommit());
            committed.countDown();
            release.await();
            return "external result"; //$NON-NLS-1$
        });
        assertTrue(committed.await(2, TimeUnit.SECONDS));
        try
        {
            String result = tool.execute(Map.of("jobId", started.getId(), "confirm", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertContains(result, "cancellation: alreadyCommitted", "NOT cancelled", //$NON-NLS-1$ //$NON-NLS-2$
                "already handed the work over", "cannot be recalled", //$NON-NLS-1$ //$NON-NLS-2$
                "do not start a duplicate job", "| status | running |"); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(Status.RUNNING, jobs.get(started.getId()).getStatus());
        }
        finally
        {
            release.countDown();
        }
        assertEquals(Status.DONE, jobs.await(started.getId(), 2_000L).getStatus());
    }

    @Test
    public void testConfirmReportsAlreadyTerminalWithoutClaimingNewCancellation()
    {
        JobSnapshot started = jobs.start("done_owner", 5_000L, "Accepted", progress -> "done"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Status.DONE, jobs.await(started.getId(), 2_000L).getStatus());

        String result = tool.execute(Map.of("jobId", started.getId(), "confirm", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertContains(result, "cancellation: alreadyTerminal", "No cancellation was performed", //$NON-NLS-1$ //$NON-NLS-2$
            "state `done`", "| status | done |"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertContains(String value, String... expected)
    {
        for (String part : expected)
        {
            assertTrue("Expected '" + part + "' in: " + value, value.contains(part)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
