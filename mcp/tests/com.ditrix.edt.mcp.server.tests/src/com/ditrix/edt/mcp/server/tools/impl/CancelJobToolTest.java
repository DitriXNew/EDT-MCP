/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.debug.core.ILaunch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationCapability;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.Status;
import com.ditrix.edt.mcp.server.utils.YaxunitJobCancellation;
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
        JobSnapshot started = jobs.start(AskWorkmateTool.NAME, 5_000L, "Accepted", progress -> { //$NON-NLS-1$
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
                "ask_workmate", "already handed the work over", "cannot be recalled", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
    public void testCommittedYaxunitCapabilityTerminatesLaunchAndWarnsNoRollback()
        throws Exception
    {
        AtomicBoolean terminated = new AtomicBoolean();
        AtomicBoolean trackingCleared = new AtomicBoolean();
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenAnswer(invocation -> terminated.get());
        doAnswer(invocation -> {
            terminated.set(true);
            return null;
        }).when(launch).terminate();

        YaxunitJobCancellation cancellation =
            new YaxunitJobCancellation(ignored -> trackingCleared.set(true), 1);
        Path reportDir = Files.createTempDirectory("edt-mcp-yaxunit-cancel-test-"); //$NON-NLS-1$
        Files.writeString(reportDir.resolve("junit.xml"), //$NON-NLS-1$
            "<testsuite name=\"partial\" tests=\"1\"><testcase classname=\"Sample\" " //$NON-NLS-1$
                + "name=\"finished\"/></testsuite>"); //$NON-NLS-1$
        cancellation.track(launch, reportDir);
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobSnapshot started = jobs.start(RunYaxunitTestsTool.NAME, 5_000L, "Accepted", //$NON-NLS-1$
            cancellation.capability(), progress -> {
                assertTrue(progress.tryCommit());
                committed.countDown();
                release.await();
                return "must not publish as a clean result"; //$NON-NLS-1$
            });
        assertTrue(committed.await(2, TimeUnit.SECONDS));

        String result = tool.execute(Map.of("jobId", started.getId(), "confirm", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertContains(result, "cancellation: terminated", "client process was killed", //$NON-NLS-1$ //$NON-NLS-2$
            "run was stopped", "infobase was NOT rolled back", //$NON-NLS-1$ //$NON-NLS-2$
            "JUnit XML report was readable", "it is partial", "YAXUnit Test Results", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Never treat a terminated run as a clean outcome", //$NON-NLS-1$
            "| status | cancelled |"); //$NON-NLS-1$
        verify(launch).terminate();
        assertTrue("the owning tool must clear its launch tracking after termination", //$NON-NLS-1$
            trackingCleared.get());
        assertEquals(Status.CANCELLED, jobs.get(started.getId()).getStatus());
        release.countDown();
        Files.deleteIfExists(reportDir.resolve("junit.xml")); //$NON-NLS-1$
        Files.deleteIfExists(reportDir);
    }

    @Test
    public void testYaxunitPreviewExplainsDestructiveEffectsAndInvokesNothing() throws Exception
    {
        AtomicBoolean handlerInvoked = new AtomicBoolean();
        CancellationCapability capability = CancellationCapability.of(
            YaxunitJobCancellation.PREVIEW_WARNING, () -> {
                handlerInvoked.set(true);
                return BackgroundJobs.CommittedCancellation.stopped("stopped", "stopped"); //$NON-NLS-1$ //$NON-NLS-2$
            });
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobSnapshot started = jobs.start(DebugYaxunitTestsTool.NAME, 5_000L, "phase: run", //$NON-NLS-1$
            capability, progress -> {
                assertTrue(progress.tryCommit());
                committed.countDown();
                release.await();
                return "done"; //$NON-NLS-1$
            });
        assertTrue(committed.await(2, TimeUnit.SECONDS));
        try
        {
            String preview = tool.execute(Map.of("jobId", started.getId())); //$NON-NLS-1$
            assertContains(preview, "cancellation: preview", "No change was made", //$NON-NLS-1$ //$NON-NLS-2$
                "owned by `debug_yaxunit_tests`", "client process", "infobase keeps", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "not rolled back", "partial or absent", "phase: run"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertFalse("preview must not invoke the destructive cancellation handler", //$NON-NLS-1$
                handlerInvoked.get());
            assertEquals(Status.RUNNING, jobs.get(started.getId()).getStatus());
        }
        finally
        {
            release.countDown();
        }
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
