/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationCapability;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationOutcome;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationResult;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.ExternalInfobaseChangesPolicy;
import com.ditrix.edt.mcp.server.utils.StandaloneServerPortConflictPolicy;
import com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PreLaunchResult;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PrepInFlight;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Tests for {@link RunYaxunitTestsTool}.
 *
 * Verifies tool name, response type, schema (required fields and parameter list)
 * and validation of required parameters at the entry point. Does not exercise
 * the actual launch flow because it requires the Eclipse runtime.
 */
public class RunYaxunitTestsToolTest
{
    @Test
    public void testToolName()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        assertEquals("run_yaxunit_tests", tool.getName());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        String desc = tool.getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        RunYaxunitTestsTool tool = new RunYaxunitTestsTool();
        assertEquals(IMcpTool.ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testConnectsToInfobaseIsTrue()
    {
        // #270: the pre-launch recompute + the launch itself connect to the infobase
        // (possibly from the background prep Job) — it must arm the auth-dialog
        // suppressor's activity window.
        assertTrue(new RunYaxunitTestsTool().connectsToInfobase());
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        String guide = tool.getGuide();
        assertNotNull(guide);
        assertTrue("guide must be non-empty", guide.length() > 0);
        // Detail migrated out of the slim description/schema lives here now.
        assertTrue("guide must explain Pending/polling", guide.contains("Pending"));
        assertTrue("guide must explain updateBeforeLaunch auto-chain",
                guide.contains("updateBeforeLaunch"));
    }

    @Test
    public void testSchemaContainsRequiredFields()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        String schema = tool.getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare projectName", schema.contains("\"projectName\""));
        assertTrue("schema must declare applicationId", schema.contains("\"applicationId\""));
        assertTrue("schema must declare extensions", schema.contains("\"extensions\""));
        assertTrue("schema must declare modules", schema.contains("\"modules\""));
        assertTrue("schema must declare tests", schema.contains("\"tests\""));
        assertTrue("schema must declare tags", schema.contains("\"tags\""));
        assertTrue("schema must declare timeout", schema.contains("\"timeout\""));
        // projectName and applicationId must be in the required list
        assertTrue("projectName must be required",
                schema.contains("\"required\"") && schema.contains("projectName"));
        assertTrue("applicationId must be required",
                schema.contains("\"required\"") && schema.contains("applicationId"));
    }

    @Test
    public void testExecuteMissingProjectName()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "some-app-id");
        String result = tool.execute(params);
        assertNotNull(result);
        assertTrue(result.contains("projectName"));
        assertTrue(result.toLowerCase().contains("required") || result.contains("Error"));
    }

    @Test
    public void testExecuteMissingApplicationId()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject");
        String result = tool.execute(params);
        assertNotNull(result);
        assertTrue(result.contains("applicationId"));
        assertTrue(result.toLowerCase().contains("required") || result.contains("Error"));
    }

    @Test
    public void testExecuteEmptyParams()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        String result = tool.execute(new HashMap<String, String>());
        assertNotNull(result);
        // Genuine missing-arg failures now travel as the structured ToolResult.error
        // JSON contract ({"success":false,"error":"..."}) rather than a markdown body.
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.toLowerCase().contains("required"));
    }

    @Test
    public void testSchemaDeclaresDebugFlag()
    {
        // The merged tool gained a debug flag (debug_yaxunit_tests is now an alias).
        IMcpTool tool = new RunYaxunitTestsTool();
        assertTrue("schema must declare the debug flag", tool.getInputSchema().contains("\"debug\""));
    }

    @Test
    public void testSchemaDeclaresUpdateScope()
    {
        // updateScope controls which projects are force-recomputed +
        // updated before the run. Schema↔execute parity: execute() reads it too.
        IMcpTool tool = new RunYaxunitTestsTool();
        String schema = tool.getInputSchema();
        assertTrue("schema must declare updateScope", schema.contains("\"updateScope\""));
        assertTrue("updateScope doc must mention the extension:<Name> form",
            schema.contains("extension:"));
    }

    @Test
    public void testUpdateScopeDescriptionMentionsAllOptions()
    {
        // Pin the shared scope doc so the alias forwarding (debug_yaxunit_tests) and
        // the run tool stay aligned on the accepted values.
        String doc = RunYaxunitTestsTool.UPDATE_SCOPE_DESCRIPTION;
        assertNotNull(doc);
        assertTrue("must document 'all'", doc.contains("all"));
        assertTrue("must document 'configuration'", doc.contains("configuration"));
        assertTrue("must document the extension form", doc.contains("extension:"));
    }

    @Test
    public void testGuideExplainsDebugMode()
    {
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must explain debug mode and the wait_for_break next step",
            guide.contains("debug=true") && guide.contains("wait_for_break"));
    }

    @Test
    public void testUpdateScopeDescriptionDocumentsUnknownNameHardError()
    {
        // A typo'd extension name fails the call instead of being
        // silently skipped — the schema doc must say so.
        assertTrue("updateScope doc must document the unknown-name hard error",
            RunYaxunitTestsTool.UPDATE_SCOPE_DESCRIPTION.contains("Unknown extension names"));
    }

    @Test
    public void testGuideDocumentsNamedPendingDelivery()
    {
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must direct a known run to get_job_status",
            guide.contains("get_job_status")); //$NON-NLS-1$
        assertTrue("guide must say Pending carries jobId", guide.contains("jobId")); //$NON-NLS-1$
        assertTrue("guide must preserve fresh reruns after terminal completion",
            guide.contains("fresh run")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsHonestCancellationBoundary()
    {
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must name the BackgroundJobs commit handshake",
            guide.contains("commit handshake")); //$NON-NLS-1$
        assertTrue("auto-chain work commits before its independent Eclipse job hand-off",
            guide.contains("before the auto-chain is handed to an Eclipse background job")); //$NON-NLS-1$
        assertTrue("the no-update path commits immediately before the actual launch",
            guide.contains("immediately before `workingCopy.launch()`")); //$NON-NLS-1$
        assertTrue("guide must explain destructive committed-run termination",
            guide.contains("reports `terminated`") && guide.contains("NOT** rolled back")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must retain alreadyCommitted when no live launch can be stopped",
            guide.contains("still reports `alreadyCommitted`")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsServerApplicationDeferredUpdate()
    {
        // Ratchet: on a standalone-server application the auto-chain
        // skips its silent DB update — the update is performed by EDT's coordinated
        // launch flow (auto-confirmed around workingCopy.launch) because an out-of-band
        // pre-update started the server in RUN mode and wedged the debug restart.
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must name the ServerApplication. id prefix gate",
            guide.contains("ServerApplication.")); //$NON-NLS-1$
        assertTrue("guide must say server apps are not pre-updated out-of-band",
            guide.contains("does NOT pre-update such applications out-of-band")); //$NON-NLS-1$
        assertTrue("guide must document the coordinated launch flow performing the update",
            guide.contains("coordinated launch flow")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsDebugFreshRunTerminatesExistingClientSession()
    {
        // Ratchet: the debug variant is fresh-run — it detects and
        // non-interactively terminates an existing client session of the app — debug
        // or RUN-mode — BEFORE launching (incl. a UI-started 'Debug As' session only
        // the debug target manager tracks), so the launch delegate's blocking 'Debug
        // session already exists' (code 1003) modal can never hang an unattended call.
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must document the fresh-run terminate of an existing client session",
            guide.contains("terminates an existing client session")); //$NON-NLS-1$
        assertTrue("guide must say the sweep also covers a RUN-mode client",
            guide.contains("RUN-mode client")); //$NON-NLS-1$
        assertTrue("guide must say it is always a FRESH run",
            guide.contains("FRESH run")); //$NON-NLS-1$
        assertTrue("guide must reference the 1003 modal the sweep prevents",
            guide.contains("Debug session already exists")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsFreshRunSweepExemptsMcpOwnedLaunches()
    {
        // Follow-up ratchet: with updateBeforeLaunch=false the sweep is the only
        // guard, and it must not silently kill a concurrent MCP-owned RUN test launch
        // of the same app — the guide documents the exemption so the contract can't
        // drift back to "terminate everything".
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must document the MCP-owned-launch exemption from the fresh-run sweep",
            guide.contains("owned by other MCP tools")); //$NON-NLS-1$
        assertTrue("guide must say an owned launch is managed by the tool that spawned it",
            guide.contains("managed by the tool that spawned it")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsDebugFreshRunNeverTouchesStandaloneServer()
    {
        // Ratchet: the fresh-run sweep is thread-TYPE-aware — it
        // only ever terminates a live CLIENT session; a debug-mode standalone server
        // (live thread typed SERVER) is never matched and never terminated.
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must say only a live CLIENT session is terminated, never the server",
            guide.contains("never the standalone server")); //$NON-NLS-1$
        assertTrue("guide must document the SERVER-typed thread discriminator",
            guide.contains("typed SERVER")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsDebug1003RaceNetConfirmer()
    {
        // Ratchet: the debug launch site arms the session matcher unconditionally
        // (arm(updateBeforeLaunch, true)) as the race net behind the sweep — the
        // guide documents the 'Keep existing and start new' auto-press so the
        // contract can't drift.
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must document the 1003 'Keep existing and start new' race net",
            guide.contains("Keep existing and start new")); //$NON-NLS-1$
        assertTrue("guide must say the race net stays armed regardless of updateBeforeLaunch",
            guide.contains("regardless of `updateBeforeLaunch`")); //$NON-NLS-1$
    }

    // ============ updateBeforeLaunch gates the debug sweep and the arming ============

    @Test
    public void testDebugSweepGatedOnUpdateBeforeLaunch()
    {
        // The fresh-run sweep (ensureNoExistingClientSession) is PART of the
        // updateBeforeLaunch auto-chain: it runs with true and is SKIPPED with
        // false (legacy delegate behaviour) — sweeping after the caller opted out
        // would terminate a session the caller asked to leave alone.
        assertTrue("updateBeforeLaunch=true must run the fresh-run sweep",
            RunYaxunitTestsTool.shouldSweepExistingClientSession(true));
        assertFalse("updateBeforeLaunch=false must SKIP the fresh-run sweep",
            RunYaxunitTestsTool.shouldSweepExistingClientSession(false));
    }

    @Test
    public void testRunPathArmFlagsFollowUpdateBeforeLaunch()
    {
        // RUN path: the update matcher follows updateBeforeLaunch (auto-pressing
        // 'Update then run' after the opt-out would perform the very DB update the
        // caller disabled); the 1003 session matcher is NEVER armed here (the
        // debug-session check does not apply to a RUN-mode spawn).
        assertArrayEquals("default RUN arming is update-only",
            new boolean[] {true, false}, RunYaxunitTestsTool.runPathArmFlags(true));
        assertArrayEquals("opted-out RUN arming presses nothing",
            new boolean[] {false, false}, RunYaxunitTestsTool.runPathArmFlags(false));
    }

    @Test
    public void testDebugPathArmFlagsGateUpdateMatcherOnly()
    {
        // DEBUG path: the update matcher follows updateBeforeLaunch (same opt-out
        // contract as the RUN path, mirroring LaunchTool); the 1003 session
        // matcher stays armed UNCONDITIONALLY as the race net behind the sweep —
        // its auto-press is the non-destructive keep-button, so it never undoes
        // the opt-out.
        assertArrayEquals("default DEBUG arming covers both modals",
            new boolean[] {true, true}, RunYaxunitTestsTool.debugPathArmFlags(true));
        assertArrayEquals("opted-out DEBUG arming keeps ONLY the 1003 race net",
            new boolean[] {false, true}, RunYaxunitTestsTool.debugPathArmFlags(false));
    }

    @Test
    public void testSchemaDocumentsUpdateBeforeLaunchFalseContract()
    {
        // Ratchet: the schema must document what false actually does now — no
        // sweep, no auto-confirm, platform dialogs may appear.
        String schema = new RunYaxunitTestsTool().getInputSchema();
        assertTrue("schema must document the legacy-behaviour opt-out",
            schema.contains("legacy delegate behaviour")); //$NON-NLS-1$
        assertTrue("schema must warn that platform dialogs may appear on opt-out",
            schema.contains("platform dialogs may appear")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsDebugSweepSkippedOnOptOut()
    {
        // Ratchet: the guide must condition the fresh-run sweep on
        // updateBeforeLaunch=true and document that false skips it.
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must scope the FRESH-run sweep to updateBeforeLaunch=true",
            guide.contains("With `updateBeforeLaunch=true`")); //$NON-NLS-1$
        assertTrue("guide must document that updateBeforeLaunch=false skips the sweep",
            guide.contains("the sweep is skipped")); //$NON-NLS-1$
    }

    // ============ selective recompute + 25s pending budget (new) ============

    @Test
    public void testDescriptionDocumentsSelectiveRecompute()
    {
        // Ratchet: the description must mention that only changed projects are
        // recomputed (not all projects on every call) and that the "prepared"
        // mark outlives an EDT restart — restarting EDT is not a source change.
        String desc = new RunYaxunitTestsTool().getDescription();
        assertTrue("description must mention that only changed projects are recomputed",
            new RunYaxunitTestsTool().getGuide().contains("recomputes only projects")); //$NON-NLS-1$
        assertTrue("description must say the prepared mark survives an EDT restart",
            new RunYaxunitTestsTool().getGuide().contains("survives an EDT restart")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionDocumentsTheClampedWindowAndThePhases()
    {
        // Ratchet: the description must state the ceiling the code enforces (a window the
        // transport cannot deliver is a promise the tool cannot keep, #357) and name the phases
        // a Pending can report, since that label is the caller's only signal.
        String desc = new RunYaxunitTestsTool().getDescription();
        assertTrue("description must state the maximum window",
            new RunYaxunitTestsTool().getGuide().contains(String.valueOf(RunYaxunitTestsTool.MAX_TIMEOUT_SECONDS)));
        assertTrue("description must say a larger timeout is clamped",
            new RunYaxunitTestsTool().getGuide().contains("clamped")); //$NON-NLS-1$
        assertTrue("description must say Pending names the phase",
            new RunYaxunitTestsTool().getGuide().contains("Pending") && new RunYaxunitTestsTool().getGuide().contains("phase")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideDocumentsSelectiveRecompute()
    {
        // Ratchet: the guide must explain the dirty-tracking mechanism —
        // only changed projects are force-recomputed; others get the cheap
        // derived-data drain; the mark is content-based and outlives a restart.
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must document that only changed projects are recomputed",
            guide.contains("selective")); //$NON-NLS-1$
        assertTrue("guide must document that the prepared mark survives an EDT restart",
            guide.contains("survives an EDT restart")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocuments25sBudgetAndBackgroundPrep()
    {
        // Ratchet: the guide must explain the 25-second budget, the background
        // prep job, and the named polling contract when the budget is exceeded.
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must mention the 25-second budget",
            guide.contains("25s") || guide.contains("25-second")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must say preparation runs in a background job",
            guide.contains("background")); //$NON-NLS-1$
        assertTrue("guide must document named polling after prep outlives the call",
            guide.contains("get_job_status")); //$NON-NLS-1$
    }

    // ============ #357 — the call never outlives the MCP transport ============

    @Test
    public void testTimeoutIsClampedToTheTransportSafeCeiling()
    {
        // #357: the parameter used to accept any window while the transport cut the call at
        // ~60s, so `timeout: 240` bought a bare "operation timed out" instead of an answer.
        // A caller may ask for LESS, never for more.
        assertEquals("a window above the ceiling must be clamped, not honoured",
            RunYaxunitTestsTool.MAX_TIMEOUT_SECONDS, RunYaxunitTestsTool.clampTimeout(240));
        assertEquals("the ceiling itself is accepted unchanged",
            RunYaxunitTestsTool.MAX_TIMEOUT_SECONDS,
            RunYaxunitTestsTool.clampTimeout(RunYaxunitTestsTool.MAX_TIMEOUT_SECONDS));
        assertEquals("a shorter probe window is honoured as asked", 5,
            RunYaxunitTestsTool.clampTimeout(5));
        assertEquals("a non-positive window still waits at least one second", 1,
            RunYaxunitTestsTool.clampTimeout(0));
        assertTrue("the ceiling must sit BELOW the ~60s transport limit it exists to respect",
            RunYaxunitTestsTool.MAX_TIMEOUT_SECONDS < 60);
    }

    @Test
    public void testRemainingMillisFloorsAtZero()
    {
        long now = System.currentTimeMillis();
        assertEquals("a deadline already past leaves no time to wait", 0L,
            RunYaxunitTestsTool.remainingMillis(now - 5_000L));
        long remaining = RunYaxunitTestsTool.remainingMillis(now + 10_000L);
        assertTrue("the remainder must not exceed the distance to the deadline",
            remaining <= 10_000L && remaining > 9_000L);
    }

    @Test
    public void testPreparationWaitIsCappedByTheCallDeadlineNotTheFullBudget() throws Exception
    {
        // THE #357 guarantee, driven directly: a repeat call joins a preparation that is already
        // running and must come back inside the CALLER's window. Before the fix the wait always
        // took the full 25s preparation budget — spent AFTER resolution — so the call routinely
        // outlived the transport and the client saw nothing at all.
        //
        // The entry's latch is never counted down, so the ONLY thing that can end this wait is
        // the deadline. The test therefore also carries its own ceiling: an unbounded wait fails
        // it by the elapsed assertion rather than hanging the suite.
        // The key is the PRODUCTION one, derived from the request itself (#411) — the entry has
        // to be injected under exactly the key the method will compute, and the project name
        // carries a nonce so a concurrent test can never land on the same one.
        RunYaxunitTestsTool.PrepRequest req = new RunYaxunitTestsTool.PrepRequest(
            "TestConfiguration-" + System.nanoTime(), null, null, "TestConfiguration.SomeApp", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, ExternalInfobaseChangesPolicy.DEFAULT, "ratchet"); //$NON-NLS-1$
        String prepKey = req.prepKey();
        PrepInFlight entry = new PrepInFlight(System.currentTimeMillis());
        // Pretend the job is already running: the CAS is spent, so no Job is scheduled and this
        // call is a pure waiter — exactly the "second identical call" of the bug report.
        entry.started.set(true);
        entry.phase = LaunchLifecycleUtils.PHASE_DB_UPDATE;
        LaunchLifecycleUtils.PREP_INFLIGHT.put(prepKey, entry);
        try
        {
            RunYaxunitTestsTool.CallState phase = new RunYaxunitTestsTool.CallState();
            long budgetMs = 2_000L;

            long startedAt = System.currentTimeMillis();
            String pending = RunYaxunitTestsTool.awaitPreparedOrPending(req,
                new PreLaunchResult[1], System.currentTimeMillis() + budgetMs, phase);
            long elapsedMs = System.currentTimeMillis() - startedAt;

            assertNotNull("an unfinished preparation must answer with Pending, never nothing",
                pending);
            assertTrue("Pending must be a Pending", pending.contains("**Pending:**")); //$NON-NLS-1$
            assertTrue("the wait must end on the CALL's deadline, not the 25s preparation budget: "
                + "waited " + elapsedMs + "ms",
                elapsedMs < LaunchLifecycleUtils.PRELAUNCH_BUDGET_MS);
            assertTrue("the wait must not end before the caller's own window either: waited "
                + elapsedMs + "ms", elapsedMs >= budgetMs - 250L);
            assertTrue("the Pending must name the LIVE preparation phase with the SAME namespaced "
                + "label the description and guide enumerate — a caller matching on "
                + "`prep:db-update` must not have to know some Pendings drop the prefix",
                pending.contains("prep:" + LaunchLifecycleUtils.PHASE_DB_UPDATE));
            assertEquals("the call phase must track the preparation's live stage",
                "prep:" + LaunchLifecycleUtils.PHASE_DB_UPDATE, phase.label());
        }
        finally
        {
            LaunchLifecycleUtils.PREP_INFLIGHT.remove(prepKey);
        }
    }

    @Test
    public void testARunningPreparationIsNeverEvictedAndDuplicated()
    {
        // A preparation is expired ONLY once it has finished. Discarding a RUNNING one would
        // schedule a second job that can merely queue behind the per-infobase monitor the first
        // one holds — and a caller polling a legitimately long recompute (the guide calls forty
        // minutes normal) would stack up one more on every retry.
        PrepInFlight running = new PrepInFlight(System.currentTimeMillis() - (60L * 60L * 1000L));
        // "Running" is what production observes: a carrier still in the scheduler.
        org.eclipse.core.runtime.jobs.Job live =
            new org.eclipse.core.runtime.jobs.Job("still-running-preparation") //$NON-NLS-1$
            {
                @Override
                protected org.eclipse.core.runtime.IStatus run(
                    org.eclipse.core.runtime.IProgressMonitor monitor)
                {
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                }
            };
        try
        {
            live.schedule(10 * 60 * 1000L);
            running.trackScheduledJob(live);
            assertFalse("an hour-old preparation that is still running must NOT be replaced",
                running.isExpired());

            live.cancel();
            running.done = true;
            assertTrue("once finished, an old entry may be discarded so the next call starts fresh",
                running.isExpired());
        }
        finally
        {
            live.cancel();
        }

        PrepInFlight fresh = new PrepInFlight(System.currentTimeMillis());
        fresh.done = true;
        assertFalse("a just-finished entry is still fetchable and must not be discarded yet",
            fresh.isExpired());
    }

    @Test
    public void testPendingCarriesJobIdAndIdenticalSubmissionAttaches() throws Exception
    {
        String submissionKey = "ratchet-job-attach-" + System.nanoTime(); //$NON-NLS-1$
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        try (BackgroundJobs jobs = new BackgroundJobs(20, 2))
        {
            RunYaxunitTestsTool tool = new RunYaxunitTestsTool(jobs);
            String first = tool.startOrAttachJob(submissionKey, 0, (jobId, progress) -> {
                starts.incrementAndGet();
                entered.countDown();
                release.await();
                return "# Finished report"; //$NON-NLS-1$
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            String second = tool.startOrAttachJob(submissionKey, 0, (jobId, progress) -> {
                starts.incrementAndGet();
                return "duplicate"; //$NON-NLS-1$
            });

            String jobId = extractJobId(first);
            assertEquals("an identical in-flight submission must return the same named identity",
                jobId, extractJobId(second));
            assertTrue(first.contains("**Pending:**")); //$NON-NLS-1$
            assertTrue(first.contains("get_job_status")); //$NON-NLS-1$
            assertTrue(first.contains("| owningTool | run_yaxunit_tests |")); //$NON-NLS-1$
            assertEquals("the duplicate guard must start one body only", 1, starts.get());

            release.countDown();
            jobs.await(jobId, 2_000L);
            String fetched = new GetJobStatusTool(jobs).execute(
                Map.of("jobId", jobId, "waitSeconds", "0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue("the completed result remains fetchable by id", //$NON-NLS-1$
                fetched.contains("# Finished report")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testShortRunReturnsOriginalReportWithoutJobWrapper() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            RunYaxunitTestsTool tool = new RunYaxunitTestsTool(jobs);
            String report = "# YAXUnit Test Results\n\nshort run"; //$NON-NLS-1$
            String result = tool.startOrAttachJob(
                "ratchet-short-" + System.nanoTime(), 2, (jobId, progress) -> report); //$NON-NLS-1$
            assertEquals("the synchronous success shape must stay byte-for-byte the report",
                report, result);
        }
    }

    @Test
    public void testTerminalJobIsNotReusedButOldResultRemainsFetchable() throws Exception
    {
        String submissionKey = "ratchet-fresh-rerun-" + System.nanoTime(); //$NON-NLS-1$
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            RunYaxunitTestsTool tool = new RunYaxunitTestsTool(jobs);
            String pending = tool.startOrAttachJob(submissionKey, 0, (jobId, progress) -> {
                firstStarted.countDown();
                finishFirst.await();
                return "first report"; //$NON-NLS-1$
            });
            String oldJobId = extractJobId(pending);
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            finishFirst.countDown();
            jobs.await(oldJobId, 2_000L);

            String rerun = tool.startOrAttachJob(submissionKey, 2,
                (jobId, progress) -> "second report"); //$NON-NLS-1$
            assertEquals("a new call after terminal completion must execute afresh",
                "second report", rerun); //$NON-NLS-1$

            String old = new GetJobStatusTool(jobs).execute(
                Map.of("jobId", oldJobId, "waitSeconds", "0")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue("the prior result must remain fetchable until registry eviction",
                old.contains("first report")); //$NON-NLS-1$
        }
        finally
        {
            finishFirst.countDown();
        }
    }

    @Test
    public void testCompletedReportCanBeFetchedMoreThanOnce() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            RunYaxunitTestsTool tool = new RunYaxunitTestsTool(jobs);
            String pending = tool.startOrAttachJob(
                "ratchet-repeat-fetch-" + System.nanoTime(), 0, //$NON-NLS-1$
                (jobId, progress) -> {
                    entered.countDown();
                    release.await();
                    return "durable report"; //$NON-NLS-1$
                });
            String jobId = extractJobId(pending);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            release.countDown();
            jobs.await(jobId, 2_000L);

            GetJobStatusTool statusTool = new GetJobStatusTool(jobs);
            Map<String, String> poll = Map.of("jobId", jobId, "waitSeconds", "0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            String first = statusTool.execute(poll);
            String second = statusTool.execute(poll);
            assertTrue(first.contains("durable report")); //$NON-NLS-1$
            assertEquals("a named job replaces the old once-only pending delivery", first, second);
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testCancellationBeforeCommitStopsYaxunitJob() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch wait = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            RunYaxunitTestsTool tool = new RunYaxunitTestsTool(jobs);
            String pending = tool.startOrAttachJob(
                "ratchet-cancel-before-commit-" + System.nanoTime(), 0, //$NON-NLS-1$
                (jobId, progress) -> {
                    entered.countDown();
                    wait.await();
                    return "must not be delivered"; //$NON-NLS-1$
                });
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            String outcome = new CancelJobTool(jobs).execute(Map.of(
                "jobId", extractJobId(pending), "confirm", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue(outcome.contains("# Background job cancellation: cancelled")); //$NON-NLS-1$
            assertTrue(outcome.contains("| owningTool | run_yaxunit_tests |")); //$NON-NLS-1$
        }
        finally
        {
            wait.countDown();
        }
    }

    @Test
    public void testCommittedJobWithoutCancellationCapabilityReportsNotCancelled() throws Exception
    {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            RunYaxunitTestsTool tool = new RunYaxunitTestsTool(jobs);
            String pending = tool.startOrAttachJob(
                "ratchet-cancel-after-commit-" + System.nanoTime(), 0, //$NON-NLS-1$
                (jobId, progress) -> {
                    assertTrue(progress.tryCommit());
                    committed.countDown();
                    release.await();
                    return "finished after cancellation request"; //$NON-NLS-1$
                });
            assertTrue(committed.await(1, TimeUnit.SECONDS));
            String jobId = extractJobId(pending);

            String outcome = new CancelJobTool(jobs).execute(Map.of(
                "jobId", jobId, "confirm", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue("cancel_job must not invent a committed cancellation capability",
                outcome.contains("# Background job cancellation: alreadyCommitted")); //$NON-NLS-1$
            assertTrue(outcome.contains("The job was NOT cancelled")); //$NON-NLS-1$
            assertEquals(BackgroundJobs.Status.RUNNING, jobs.get(jobId).getStatus());

            release.countDown();
            assertEquals(BackgroundJobs.Status.DONE, jobs.await(jobId, 2_000L).getStatus());
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testAttachedCallerReceivesCommittedCancellationPartialResult() throws Exception
    {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        String cancellationResult = "The YAXUnit client process was killed and the run was " //$NON-NLS-1$
            + "stopped. The infobase was NOT rolled back; it keeps test changes.\n\n" //$NON-NLS-1$
            + "A JUnit XML report was readable, but it is partial.\n\n# Partial report"; //$NON-NLS-1$
        CancellationCapability capability = CancellationCapability.of("stop live YAXUnit", //$NON-NLS-1$
            () -> BackgroundJobs.CommittedCancellation.stopped(cancellationResult,
                cancellationResult));
        try (BackgroundJobs jobs = new BackgroundJobs(20, 2))
        {
            RunYaxunitTestsTool tool = new RunYaxunitTestsTool(jobs);
            JobSnapshot started = jobs.start(RunYaxunitTestsTool.NAME, 60_000L, "start", capability, //$NON-NLS-1$
                progress -> {
                    assertTrue(progress.tryCommit());
                    committed.countDown();
                    while (releaseWorker.getCount() > 0)
                    {
                        try
                        {
                            releaseWorker.await();
                        }
                        catch (InterruptedException e)
                        {
                            // The duplicate guard must retain this live worker despite interruption.
                            Thread.interrupted();
                        }
                    }
                    return "must not replace the cancellation result"; //$NON-NLS-1$
                });
            assertTrue(committed.await(2, TimeUnit.SECONDS));

            try
            {
                CancellationResult stopped = jobs.cancel(started.getId());
                assertEquals(CancellationOutcome.TERMINATED, stopped.getOutcome());
                assertEquals(BackgroundJobs.Status.RUNNING, stopped.getSnapshot().getStatus());
                assertNotNull("findRunningJob must retain the duplicate guard while the old " //$NON-NLS-1$
                    + "worker can still write its stable report directory", //$NON-NLS-1$
                    tool.findRunningJob(started.getId()));
            }
            finally
            {
                releaseWorker.countDown();
            }

            assertEquals(BackgroundJobs.Status.CANCELLED,
                jobs.await(started.getId(), 2_000L).getStatus());
            assertTrue(tool.findRunningJob(started.getId()) == null);
            String attached = tool.awaitExistingRun(started.getId(), ignored -> {
                // Direct seam for the attached job's progress reporter.
            });
            assertEquals("an attached caller must receive the owner's stored cancellation result", //$NON-NLS-1$
                cancellationResult, attached);
            assertTrue(attached.contains("Partial report")); //$NON-NLS-1$
            assertTrue(attached.contains("infobase was NOT rolled back")); //$NON-NLS-1$
            assertFalse(attached.contains("cancelled before launch")); //$NON-NLS-1$
        }
        finally
        {
            releaseWorker.countDown();
        }
    }

    @Test
    public void testUnverifiedTerminationRetainsEquivalentRunClaimUntilWorkerExit()
        throws Exception
    {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        String partial = "Termination was requested but not confirmed; partial report"; //$NON-NLS-1$
        CancellationCapability capability = CancellationCapability.of("stop live YAXUnit", //$NON-NLS-1$
            () -> BackgroundJobs.CommittedCancellation.stopInitiated(partial, partial));
        try (BackgroundJobs jobs = new BackgroundJobs(20, 2))
        {
            RunYaxunitTestsTool tool = new RunYaxunitTestsTool(jobs);
            JobSnapshot started = jobs.start(RunYaxunitTestsTool.NAME, 60_000L, "start", capability, //$NON-NLS-1$
                progress -> {
                    assertTrue(progress.tryCommit());
                    committed.countDown();
                    releaseWorker.await();
                    return "worker report"; //$NON-NLS-1$
                });
            assertTrue(committed.await(2, TimeUnit.SECONDS));

            try
            {
                CancellationResult requested = jobs.cancel(started.getId());
                assertEquals(CancellationOutcome.TERMINATION_REQUESTED,
                    requested.getOutcome());
                assertEquals(BackgroundJobs.Status.RUNNING,
                    requested.getSnapshot().getStatus());
                assertNotNull("an equivalent run must remain attached to the cancellation-pending " //$NON-NLS-1$
                    + "job until its worker exits", tool.findRunningJob(started.getId())); //$NON-NLS-1$
            }
            finally
            {
                releaseWorker.countDown();
            }

            JobSnapshot cancelled = jobs.await(started.getId(), 2_000L);
            assertEquals(BackgroundJobs.Status.CANCELLED, cancelled.getStatus());
            assertEquals(partial, cancelled.getResult());
            assertTrue(tool.findRunningJob(started.getId()) == null);
        }
    }

    @Test
    public void testCancellingAttachmentStopsWaitWithoutStoppingMirroredRun() throws Exception
    {
        CountDownLatch mirroredStarted = new CountDownLatch(1);
        CountDownLatch releaseMirrored = new CountDownLatch(1);
        CountDownLatch attachmentStarted = new CountDownLatch(1);
        AtomicReference<String> returnedMessage = new AtomicReference<>();
        AtomicBoolean interruptedAtReturn = new AtomicBoolean();
        AtomicInteger returns = new AtomicInteger();
        BackgroundJobs jobs = new BackgroundJobs(20, 2);
        try
        {
            RunYaxunitTestsTool tool = new RunYaxunitTestsTool(jobs);
            JobSnapshot mirrored = jobs.start(RunYaxunitTestsTool.NAME, Long.MAX_VALUE,
                "start mirrored run", progress -> { //$NON-NLS-1$
                    assertTrue(progress.tryCommit());
                    mirroredStarted.countDown();
                    releaseMirrored.await();
                    return "mirrored report"; //$NON-NLS-1$
                });
            assertNotNull(mirrored);
            assertTrue(mirroredStarted.await(1, TimeUnit.SECONDS));

            JobSnapshot attachment = jobs.start(RunYaxunitTestsTool.NAME, 60_000L,
                "start attachment", progress -> { //$NON-NLS-1$
                    attachmentStarted.countDown();
                    String message = tool.awaitExistingRun(mirrored.getId(), progress);
                    returnedMessage.set(message);
                    interruptedAtReturn.set(Thread.currentThread().isInterrupted());
                    returns.incrementAndGet();
                    return message;
                });
            assertNotNull(attachment);
            assertTrue(attachmentStarted.await(1, TimeUnit.SECONDS));

            CancellationResult cancellation = jobs.cancel(attachment.getId());
            assertNotNull(cancellation);
            assertEquals(CancellationOutcome.CANCELLED, cancellation.getOutcome());
            JobSnapshot cancelled = jobs.await(attachment.getId(), 2_000L);
            assertEquals("the attachment worker must exit so cancellation can publish", //$NON-NLS-1$
                BackgroundJobs.Status.CANCELLED, cancelled.getStatus());

            String message = returnedMessage.get();
            assertNotNull("the interrupted attachment wait must return a result", message); //$NON-NLS-1$
            assertTrue("the result must name the separate mirrored job", //$NON-NLS-1$
                message.contains(mirrored.getId()));
            assertTrue("the result must say that the mirrored job keeps running", //$NON-NLS-1$
                message.contains("keeps running")); //$NON-NLS-1$
            assertTrue("the result must provide the polling action", //$NON-NLS-1$
                message.contains("get_job_status")); //$NON-NLS-1$
            assertFalse("the result must not claim that the mirrored job was stopped", //$NON-NLS-1$
                message.toLowerCase().contains("stopped")); //$NON-NLS-1$
            assertTrue("the attachment must preserve the interrupt instead of swallowing it", //$NON-NLS-1$
                interruptedAtReturn.get());
            assertEquals("the interrupted wait must return instead of continuing its loop", //$NON-NLS-1$
                1, returns.get());
            assertEquals("cancelling the attachment must not cancel the mirrored job", //$NON-NLS-1$
                BackgroundJobs.Status.RUNNING, jobs.get(mirrored.getId()).getStatus());
        }
        finally
        {
            releaseMirrored.countDown();
            jobs.close();
        }
    }

    @Test
    public void testPendingRendersCurrentJobProgress() throws Exception
    {
        CountDownLatch phasePublished = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            RunYaxunitTestsTool tool = new RunYaxunitTestsTool(jobs);
            String key = "ratchet-progress-" + System.nanoTime(); //$NON-NLS-1$
            String first = tool.startOrAttachJob(key, 0, (jobId, progress) -> {
                progress.add("Phase: prep:db-update"); //$NON-NLS-1$
                phasePublished.countDown();
                release.await();
                return "report"; //$NON-NLS-1$
            });
            assertNotNull(extractJobId(first));
            assertTrue(phasePublished.await(1, TimeUnit.SECONDS));

            String pending = tool.startOrAttachJob(key, 0,
                (jobId, progress) -> "duplicate"); //$NON-NLS-1$
            assertTrue("Pending must expose the registry progress journal",
                pending.contains("Phase: prep:db-update")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testCancellationPreviewNamesOwningTool() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            RunYaxunitTestsTool tool = new RunYaxunitTestsTool(jobs);
            String pending = tool.startOrAttachJob(
                "ratchet-cancel-preview-" + System.nanoTime(), 0, //$NON-NLS-1$
                (jobId, progress) -> {
                    entered.countDown();
                    release.await();
                    return "report"; //$NON-NLS-1$
                });
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            String preview = new CancelJobTool(jobs).execute(Map.of(
                "jobId", extractJobId(pending))); //$NON-NLS-1$
            assertTrue(preview.contains("# Background job cancellation: preview")); //$NON-NLS-1$
            assertTrue(preview.contains("owned by `run_yaxunit_tests`")); //$NON-NLS-1$
            assertEquals(BackgroundJobs.Status.RUNNING,
                jobs.get(extractJobId(pending)).getStatus());
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testDifferentStartWaitWindowsStillAttachByNamedSubmission() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        try (BackgroundJobs jobs = new BackgroundJobs(20, 2))
        {
            RunYaxunitTestsTool tool = new RunYaxunitTestsTool(jobs);
            String key = "ratchet-wait-window-" + System.nanoTime(); //$NON-NLS-1$
            String first = tool.startOrAttachJob(key, 0, (jobId, progress) -> {
                starts.incrementAndGet();
                entered.countDown();
                release.await();
                return "report"; //$NON-NLS-1$
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            String second = tool.startOrAttachJob(key, 1,
                (jobId, progress) -> {
                    starts.incrementAndGet();
                    return "duplicate"; //$NON-NLS-1$
                });

            assertEquals(extractJobId(first), extractJobId(second));
            assertEquals("the wait window is not execution identity", 1, starts.get());
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testTheSchedulerHandsTheJobToTheEntry() throws Exception
    {
        // Pins the CALL SITE, not the setter: `PrepInFlight.isExpired` can only tell a queued
        // preparation from an abandoned one if something gives it the job. A test that called
        // trackScheduledJob itself would happily survive deleting the hand-over, which is the
        // vacuum pin this PR has already been caught by once.
        //
        // A null launch manager makes the body finish immediately with an error, so the real
        // scheduling site is exercised without EDT services.
        RunYaxunitTestsTool.PrepRequest req = new RunYaxunitTestsTool.PrepRequest(
            "TestConfiguration", null, null, "TestConfiguration.SomeApp", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, ExternalInfobaseChangesPolicy.DEFAULT, "handover-ratchet"); //$NON-NLS-1$
        PrepInFlight entry = new PrepInFlight(System.currentTimeMillis());

        RunYaxunitTestsTool.schedulePrepJob(entry, req, new PreLaunchResult[1]);

        // THE assertion: the entry must know its carrier. An entry whose job runs to completion
        // behaves identically with and without the hand-over — only the abandoned case depends
        // on it — so asserting the hand-over itself is the only thing that notices its removal.
        assertTrue("the scheduling site must hand the job to the entry, or a preparation "
            + "cancelled before it ran can never be told apart from one still queued",
            entry.hasTrackedCarrier());

        assertTrue("the scheduled body must still run to completion",
            entry.latch.await(30, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue("and complete the entry", entry.done);
    }

    @Test
    public void testPrepPhaseLabelNamespacesTheBackgroundStage()
    {
        PrepInFlight entry = new PrepInFlight(System.currentTimeMillis());
        entry.phase = LaunchLifecycleUtils.PHASE_RECOMPUTE;
        assertEquals("prep:" + LaunchLifecycleUtils.PHASE_RECOMPUTE,
            RunYaxunitTestsTool.prepPhaseLabel(entry));
        entry.phase = LaunchLifecycleUtils.PHASE_TERMINATE;
        assertEquals("prep:" + LaunchLifecycleUtils.PHASE_TERMINATE,
            RunYaxunitTestsTool.prepPhaseLabel(entry));
        assertEquals("a missing entry must degrade to the FIRST stage a prep enters, never to "
            + "one the change gate may skip entirely (#310)",
            "prep:" + LaunchLifecycleUtils.PHASE_TERMINATE,
            RunYaxunitTestsTool.prepPhaseLabel(null));
    }

    @Test
    public void testPrepPendingDoesNotClaimWorkTheGateMaySkip()
    {
        // #310: the Pending said "phase: recompute" AND "the server is rebuilding changed
        // projects" on every preparation, including the ones whose scope was unchanged and whose
        // recompute was therefore skipped. The prose must describe the PURPOSE; only the phase
        // may name the stage, and the phase is published per stage.
        //
        // This is the INTERNAL wait marker - the text the issue quotes, produced by this builder.
        // Since #417 the owning job consumes it and the caller sees the registry snapshot, so the
        // pin is on the builder, which is where the sentence lives.
        String pending = RunYaxunitTestsTool.buildPrepPendingMessage(
            25, "prep:" + LaunchLifecycleUtils.PHASE_CHECK_CHANGES); //$NON-NLS-1$

        assertTrue("the phase must be carried through verbatim: " + pending,
            pending.contains("phase: `prep:" + LaunchLifecycleUtils.PHASE_CHECK_CHANGES + "`")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the body must not assert a rebuild that the gate may have skipped: "
            + pending, pending.contains("rebuilding")); //$NON-NLS-1$
    }

    @Test
    public void testPrepJobBodyDoesNotStampAPhaseTheChainNeverReached()
    {
        // The phase used to be stamped by this body — "recompute" before the chain and
        // "db-update" AFTER it returned — so every Pending said "recompute" whatever the server
        // was doing, and "db-update" only ever appeared once there was nothing left to wait for.
        // A null launch manager makes the chain fail before any stage runs; the phase must
        // therefore still be the first stage, never the last one.
        RunYaxunitTestsTool.PrepRequest req = new RunYaxunitTestsTool.PrepRequest(
            "TestConfiguration", null, null, "TestConfiguration.SomeApp", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, ExternalInfobaseChangesPolicy.DEFAULT, "phase-ratchet"); //$NON-NLS-1$
        PrepInFlight entry = new PrepInFlight(System.currentTimeMillis());

        RunYaxunitTestsTool.runPrepJobBody(entry, req, new PreLaunchResult[1]);

        assertNotNull("a null launch manager must surface a prep error", entry.error);
        assertEquals("a chain that never started a stage must not advertise the LAST one",
            LaunchLifecycleUtils.PHASE_TERMINATE, entry.phase);
    }

    @Test
    public void testGuideDocumentsThePreFlightOrderAndTheStuckPhaseSignal()
    {
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must spell out the pre-flight order the issue asked for",
            guide.contains("get_applications") && guide.contains("update_database")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must state the clamped whole-call window",
            guide.contains("45")); //$NON-NLS-1$
        assertTrue("guide must list the phases a Pending can report",
            guide.contains("prep:recompute") && guide.contains("prep:db-update") //$NON-NLS-1$ //$NON-NLS-2$
                && guide.contains("prep:check-changes") && guide.contains("prep:settle")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must say that `prep:recompute` is CONDITIONAL - a reader who believes "
            + "it always appears reads its absence as a failure, and its presence as proof that "
            + "the tool rebuilds everything every time (#310)",
            guide.contains("`prep:recompute` appears only when the gate found something to " //$NON-NLS-1$
                + "recompute")); //$NON-NLS-1$
        assertTrue("guide must teach that an ADVANCING phase proves progress",
            guide.contains("phase that ADVANCES")); //$NON-NLS-1$
        assertTrue("guide must NOT claim a stalled phase proves a block — it cannot tell the two "
            + "apart, and saying otherwise is a claim wider than the code",
            guide.contains("when a phase stops advancing, look at EDT")); //$NON-NLS-1$
    }

    @Test
    public void testDocsDoNotClaimUpdateBeforeLaunchTrueIsDialogFree()
    {
        // The old wording said dialogs "may appear and block" only under updateBeforeLaunch=false,
        // which reads as a guarantee for true — and true is exactly what ended in a blocking
        // dialog in #357. Both surfaces must now say what the code can actually promise.
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must state that true does not make dialogs impossible",
            guide.contains("Dialogs are not impossible")); //$NON-NLS-1$
        String schema = new RunYaxunitTestsTool().getInputSchema();
        assertTrue("the updateBeforeLaunch schema text must not promise more than the code does",
            schema.contains("unlikely, NOT impossible")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDoesNotPromiseAnUnconditionalRecompute()
    {
        // #310 had two sources for the same conclusion ("the tool always rebuilds the project"):
        // the phase label, and this sentence. Since #377 the auto-chain recomputes only the
        // projects whose sources differ from their last prepared state, so a schema promising a
        // force-recompute of the project AND its extensions teaches the misconception the phase
        // fix removes - and is simply false.
        String schema = new RunYaxunitTestsTool().getInputSchema();

        assertFalse("the auto-chain is selective since #377; the schema must not describe it as "
            + "an unconditional recompute of the project and its extensions",
            schema.contains("force-recompute the project + its extensions")); //$NON-NLS-1$
        // Pinned per PARAMETER, not by a phrase they share: "whose sources" alone also matches
        // updateScope, which InputSchemaCompactor strips from the wire - so that assertion could
        // stay green while the one description a client actually receives regressed.
        assertTrue("updateBeforeLaunch is the description that reaches the wire; it must say the "
            + "recompute is selective and that the configuration goes through the same gate",
            schema.contains("recompute only the projects whose sources changed " //$NON-NLS-1$
                + "(the configuration is not exempt)")); //$NON-NLS-1$
        assertTrue("and updateScope must say the gate applies WITHIN the scope it names",
            schema.contains("Within that scope only the projects whose sources changed are " //$NON-NLS-1$
                + "recomputed")); //$NON-NLS-1$
    }

    // ============ #230 — the async prep body brackets the auth-dialog suppressor ============

    @Test
    public void testPrepJobBodyBracketsTheAuthDialogSuppressorCounter() throws Exception
    {
        // #230 regression guard: schedulePrepJob runs prepareForFreshLaunch (whose db-update
        // phase does the infobase connect that raises the blocking "Configure Infobase access
        // Settings" dialog) in a fire-and-forget background Job. execute() only blocks on it for
        // the 25s budget and then returns "pending", so the trailing grace window alone would NOT
        // cover a minutes-long prep — the in-flight COUNTER must span the whole body, exactly like
        // LaunchTool.runLaunchJobBody. This drives the extracted body seam headlessly and
        // asserts it holds the counter up for its whole duration and never leaks it.
        AtomicInteger inFlight = inFlightCounter();
        int original = inFlight.get();

        // Pre-arm one activity level so a MISSING markActivityStart is detectable: a lone
        // markActivityEnd would drop the counter BELOW this level, a leaked markActivityStart
        // would leave it ABOVE — a plain net-zero-from-idle check could tell neither apart.
        InfobaseAuthDialogSuppressor.markActivityStart();
        int armed = inFlight.get();
        assertEquals("pre-arm must raise the in-flight level by one", original + 1, armed);

        long beforeBody = System.currentTimeMillis();

        // A null launchManager makes prepareForFreshLaunch return a clean PreLaunchResult error
        // immediately (see LaunchLifecycleUtils.prepareForFreshLaunch) — a fully headless,
        // deterministic drive of the body that needs no EDT services (the real db-update phase,
        // which would raise the dialog on a live base, never runs here).
        RunYaxunitTestsTool.PrepRequest req = new RunYaxunitTestsTool.PrepRequest(
            "TestConfiguration", null, null, "TestConfiguration.SomeApp", //$NON-NLS-1$ //$NON-NLS-2$
            null, null, ExternalInfobaseChangesPolicy.DEFAULT,
            "prep-job-suppressor-ratchet"); //$NON-NLS-1$
        PrepInFlight entry = new PrepInFlight(System.currentTimeMillis());
        PreLaunchResult[] holder = new PreLaunchResult[1];

        IStatus status = RunYaxunitTestsTool.runPrepJobBody(entry, req, holder);

        // The body always ran to completion: it OKs (the real outcome rides on the entry),
        // completes the entry and counts its latch down for the awaiting caller.
        assertNotNull(status);
        assertTrue("prep body always returns OK (the outcome is carried on the entry)", status.isOK());
        assertTrue("prep body must mark the entry done", entry.done);
        assertEquals("prep body must count the entry latch down", 0L, entry.latch.getCount());
        assertNotNull("a null launch manager must surface a prep error on the entry", entry.error);

        // Bracketed, not leaked: the counter is back to EXACTLY the pre-armed level
        // (markActivityStart +1 then markActivityEnd -1 inside the body), proving it was held
        // above the idle baseline for the whole prep. A missing start would read armed-1 here;
        // a missing end would read armed+1.
        assertEquals("runPrepJobBody must leave the in-flight counter at the pre-armed level",
            armed, inFlight.get());

        // markActivityEnd stamped the trailing-grace timestamp during the body.
        assertTrue("runPrepJobBody must stamp lastActivityEndMillis via markActivityEnd",
            lastActivityEndMillis() >= beforeBody);

        // Undo the pre-arm so the shared static baseline is restored for the other tests.
        InfobaseAuthDialogSuppressor.markActivityEnd();
        assertEquals("cleanup must restore the original in-flight baseline",
            original, inFlight.get());
    }

    /**
     * Reads the package-private {@code InfobaseAuthDialogSuppressor.IN_FLIGHT} counter via
     * reflection — the field lives in the {@code utils} package, out of this test's package,
     * and the suppressor exposes no public getter (only the {@code markActivity*} mutators).
     */
    private static AtomicInteger inFlightCounter() throws Exception
    {
        Field f = InfobaseAuthDialogSuppressor.class.getDeclaredField("IN_FLIGHT"); //$NON-NLS-1$
        f.setAccessible(true);
        return (AtomicInteger)f.get(null);
    }

    /** Reads the package-private {@code InfobaseAuthDialogSuppressor.lastActivityEndMillis} via reflection. */
    private static long lastActivityEndMillis() throws Exception
    {
        Field f = InfobaseAuthDialogSuppressor.class.getDeclaredField("lastActivityEndMillis"); //$NON-NLS-1$
        f.setAccessible(true);
        return f.getLong(null);
    }

    // ---------------------------------------------------------------------
    // Tag filter (#409)
    //
    // The tool writes xUnitParams.json itself; YAXUnit reads filter.tags from it
    // (ЮТФильтрацияСлужебный.УстановитьКонтекст) and applies the filter. These tests
    // drive the two production seams the value must survive — the generated JSON and
    // the run key — through the SAME RunRequest the run path builds, so a field the
    // request stops carrying is caught here rather than at a call site.
    // ---------------------------------------------------------------------

    /** Builds the request exactly as {@code execute()} does, varying only the filter. */
    private static RunYaxunitTestsTool.RunRequest req(String extensions, String modules,
            String tests, String tags)
    {
        return new RunYaxunitTestsTool.RunRequest("Cfg", "Proj", "App", extensions, modules, tests,
            tags, 45, true, null, ExternalInfobaseChangesPolicy.OVERRIDE,
            StandaloneServerPortConflictPolicy.CANCEL, false);
    }

    @Test
    public void testTagsLandInTheGeneratedFilter()
    {
        String json = RunYaxunitTestsTool.buildParamsJson("/tmp/out/junit.xml",
            req(null, null, null, "smoke,nodb"));

        assertTrue("the tag filter must be written as filter.tags so YAXUnit can read it: " + json,
            json.contains("\"tags\""));
        assertTrue("each tag must be a separate array element: " + json, json.contains("\"smoke\""));
        assertTrue("each tag must be a separate array element: " + json, json.contains("\"nodb\""));
        assertTrue("tags alone must be enough to emit the filter object: " + json,
            json.contains("\"filter\""));
    }

    @Test
    public void testTagsCombineWithTheOtherFilterFamilies()
    {
        String json = RunYaxunitTestsTool.buildParamsJson("/tmp/out/junit.xml",
            req("MyExt", "MyModule", "MyModule.TestOne", "smoke"));

        assertTrue("extensions must survive alongside tags: " + json, json.contains("\"MyExt\""));
        assertTrue("modules must survive alongside tags: " + json, json.contains("\"MyModule\""));
        assertTrue("tests must survive alongside tags: " + json,
            json.contains("\"MyModule.TestOne\""));
        assertTrue("tags must survive alongside the other families: " + json,
            json.contains("\"smoke\""));
        // Presence alone would also pass if two families were transposed, so pin the tag to ITS
        // key: YAXUnit reads each family separately and a tag under "modules" filters nothing.
        assertTrue("the tag must sit under the tags key, not another family's: " + json,
            json.contains("\"tags\":[\"smoke\"]"));
    }

    /**
     * An empty tag list must not reach the file at all.
     *
     * <p>YAXUnit decides whether a family filters by whether its list is filled
     * ({@code ЗначениеЗаполнено}), so writing {@code "tags": []} would say "no tag filter"
     * in a way that merely LOOKS like a filter. Absent and empty must stay the same thing.
     */
    @Test
    public void testEmptyOrAbsentTagsWriteNoFilterAtAll()
    {
        String absent = RunYaxunitTestsTool.buildParamsJson("/tmp/out/junit.xml",
            req(null, null, null, null));
        assertFalse("no filter object may be written when nothing filters: " + absent,
            absent.contains("\"filter\""));

        String empty = RunYaxunitTestsTool.buildParamsJson("/tmp/out/junit.xml",
            req(null, null, null, ""));
        assertFalse("an empty tag list is not a filter: " + empty, empty.contains("\"tags\""));
        assertFalse("an empty tag list must not conjure a filter object: " + empty,
            empty.contains("\"filter\""));
    }

    /**
     * Different tag selections must be different runs.
     *
     * <p>The run key governs live-job/launch reuse and the report directory. If tags were not
     * folded into it, a run started for one tag could be joined by a call that asked for another.
     */
    @Test
    public void testRunKeyDistinguishesTagSelections()
    {
        String smoke = key(req(null, null, null, "smoke"));
        String slow = key(req(null, null, null, "slow"));
        String none = key(req(null, null, null, null));

        assertFalse("two different tag filters must not share a run key", smoke.equals(slow));
        // The load-bearing one: if tags were dropped from the formula, a tag-filtered run would
        // collapse onto the unfiltered run's key and could be served the full suite's report.
        assertFalse("a tag-filtered run must not share the unfiltered run's key",
            smoke.equals(none));
        assertTrue("the launch config name stays the key root", smoke.startsWith("Cfg:"));
        assertEquals("the same tag filter must be the same run", smoke,
            key(req(null, null, null, "smoke")));

        // The families occupy distinct, length-framed positions: the same word must mean a
        // different run depending on which family it was passed in.
        assertFalse("the same word in a different filter family must be a different run",
            smoke.equals(key(req(null, "smoke", null, null))));
    }
    // ---------------------------------------------------------------------
    // Reuse keys (#411)
    //
    // Two keys decide whether one call is served by another call's work: the RUN key
    // (RUN_JOBS, ACTIVE_LAUNCHES and the report directory, consulted before preparation) and
    // the PREPARATION key (the single in-flight
    // recompute+update job). A term missing from either is not a slow path: it is a wrong answer
    // that looks exactly like a right one.
    //
    // The two ratchets are two-WAY on purpose. A field that changes what runs but is absent from
    // a key hands one caller another caller's report; a field that changes nothing but is present
    // stops reuse from ever matching, so every call re-runs the whole suite. Both are silent.
    // ---------------------------------------------------------------------

    /** The RESOLVED launch target the keys below are built for. */
    private static final String CFG = "Cfg";
    private static final String PROJ = "Proj";
    private static final String APP = "App";

    /** A Job display name; the preparation key must not care which one. */
    private static final String JOB = "YAXUnit pre-launch preparation for Proj";

    /** Builds the request the run path builds, with every field spelled out. */
    private static RunYaxunitTestsTool.RunRequest request(String extensions, String modules, // NOSONAR the point of this helper is that every field is visible at the call site
            String tests, String tags, int timeout, boolean updateBeforeLaunch, String updateScope,
            ExternalInfobaseChangesPolicy policy, boolean debug)
    {
        return new RunYaxunitTestsTool.RunRequest(CFG, PROJ, APP, extensions, modules, tests, tags,
            timeout, updateBeforeLaunch, updateScope, policy,
            StandaloneServerPortConflictPolicy.CANCEL, debug);
    }

    /** The default request every variant below differs from in exactly one field. */
    private static RunYaxunitTestsTool.RunRequest baseline()
    {
        return request(null, null, null, null, 45, true, null,
            ExternalInfobaseChangesPolicy.OVERRIDE, false);
    }

    /** The baseline with only {@code updateScope} changed. */
    private static RunYaxunitTestsTool.RunRequest scoped(String updateScope)
    {
        return request(null, null, null, null, 45, true, updateScope,
            ExternalInfobaseChangesPolicy.OVERRIDE, false);
    }

    /** The PRODUCTION run key for the resolved target these tests share. */
    private static String key(RunYaxunitTestsTool.RunRequest req)
    {
        return RunYaxunitTestsTool.buildRunKey(CFG, PROJ, APP, req);
    }

    /** A preparation request with only the fields the key can possibly care about set. */
    private static RunYaxunitTestsTool.PrepRequest prep(String projectName, String applicationId,
            String updateScope, ExternalInfobaseChangesPolicy policy, String jobName)
    {
        return new RunYaxunitTestsTool.PrepRequest(projectName, null, null, applicationId, null,
            updateScope, policy, jobName);
    }

    /**
     * A call that asked for a FRESH run must never be served by a run that did not refresh.
     */
    @Test
    public void testRunKeyDistinguishesTheFreshnessGuarantee()
    {
        String withChain = key(baseline());
        String withoutChain = key(request(null, null, null, null, 45, false, null,
            ExternalInfobaseChangesPolicy.OVERRIDE, false));

        assertFalse("updateBeforeLaunch=true asks for a recompiled extension and an updated "
            + "infobase. A run started with false may have executed a stale .cfe, and its report "
            + "is indistinguishable from an honest fresh one, so the two must not share a run",
            withChain.equals(withoutChain));
    }

    /**
     * The rebuild scope decides WHICH projects are regenerated and loaded before the run, i.e.
     * which code the tests execute.
     */
    @Test
    public void testRunKeyDistinguishesTheRebuildScope()
    {
        String all = key(baseline());
        String extA = key(scoped("extension:ExtA"));
        String extB = key(scoped("extension:ExtB"));
        String configurationOnly = key(scoped("configuration"));

        assertFalse("a run that rebuilt only ExtA must not answer a call that asked for ExtB",
            extA.equals(extB));
        assertFalse("a narrowed rebuild must not answer a call that asked for the full one",
            extA.equals(all));
        assertFalse("'configuration' skips the extensions entirely — a different run",
            configurationOnly.equals(all));
    }

    /**
     * The scope is NORMALISED, not stringified. It has a grammar, and spellings that ask for the
     * same preparation must stay one run: keying the raw string would make an identical retry
     * start a second full test run.
     */
    @Test
    public void testRunKeyTreatsEquivalentScopesAsOneRun()
    {
        String all = key(baseline());

        assertEquals("an omitted scope IS 'all'", all, key(scoped("all")));
        assertEquals("the keyword is case-insensitive and trimmed", all, key(scoped("  ALL  ")));
        assertEquals("an empty scope is an omitted scope", all, key(scoped("")));
        assertEquals("a bare name is the same intent as extension:<name>",
            key(scoped("extension:ExtA")), key(scoped("ExtA")));
        assertEquals("the scope names a SET of projects, so the order it was written in is not a "
            + "different preparation", key(scoped("extension:ExtA,extension:ExtB")),
            key(scoped(" extension:ExtB , ExtA ")));
        assertEquals("a repeated name is still one project", key(scoped("extension:ExtA")),
            key(scoped("ExtA,extension:ExtA")));
    }

    /**
     * With the auto-chain off the scope applies to nothing, and the parameter's own contract says
     * so ("Only applies when updateBeforeLaunch=true"). Keying it anyway would split requests the
     * tool itself declares identical.
     */
    @Test
    public void testRunKeyIgnoresTheRebuildScopeWhenTheChainIsOff()
    {
        ExternalInfobaseChangesPolicy override = ExternalInfobaseChangesPolicy.OVERRIDE;
        String extA = key(request(null, null, null, null, 45, false, "extension:ExtA", override,
            false));
        String extB = key(request(null, null, null, null, 45, false, "extension:ExtB", override,
            false));
        String noScope = key(request(null, null, null, null, 45, false, null, override, false));

        assertEquals("with updateBeforeLaunch=false nothing is rebuilt, so the scope cannot make "
            + "these two different runs", extA, extB);
        assertEquals("an ignored scope must not split the key away from not passing one at all",
            extA, noScope);
    }

    /**
     * The launch configuration NAME does not pin the target. A caller may pass
     * {@code applicationId} alongside {@code launchConfigurationName}, and it overrides the
     * config's own binding, so two calls on one config can execute against two infobases.
     */
    @Test
    public void testRunKeyDistinguishesTheResolvedTarget()
    {
        RunYaxunitTestsTool.RunRequest req = baseline();
        String here = RunYaxunitTestsTool.buildRunKey(CFG, PROJ, APP, req);

        assertFalse("a run against one application must not deliver its report to a call that "
            + "asked for another", here.equals(RunYaxunitTestsTool.buildRunKey(CFG, PROJ,
                "OtherApp", req)));
        assertFalse("nor may a run in one project answer a call about another",
            here.equals(RunYaxunitTestsTool.buildRunKey(CFG, "OtherProj", APP, req)));
        assertTrue("the launch config name stays the readable key root", here.startsWith("Cfg:"));
    }

    /**
     * Key parts are LENGTH-framed, not joined by a separator character.
     *
     * <p>A run-key collision is not a slow path: it is served as a successful, wrong report. The
     * parts are caller-controlled strings, and with a literal {@code '|'} between them a value
     * ending in the separator and the next value beginning with it produce ONE key.
     */
    @Test
    public void testRunKeyPartsCannotImpersonateASeparator()
    {
        // The pair that collides under the pre-fix formula, where the filter families were
        // joined with a literal pipe: ("a|", "b") and ("a", "|b") both flatten to "a||b".
        assertFalse("a separator character inside a filter value must not merge two different "
            + "requests into one run",
            key(req("a|", "b", null, null)).equals(key(req("a", "|b", null, null))));

        // And the pair only the FRAMING defends. Every filter family carries a marker of its
        // own, so a joiner is already unforgeable across those; the resolved project and
        // application sit next to each other with nothing between them but the separator.
        // Measured, not assumed: with this assertion written on the filter families instead,
        // replacing the framing with a plain joiner broke nothing.
        assertFalse("the boundary between the resolved project and application must be a "
            + "length, not a character their values can contain",
            RunYaxunitTestsTool.buildRunKey(CFG, "Proj|", "App", baseline())
                .equals(RunYaxunitTestsTool.buildRunKey(CFG, "Proj", "|App", baseline())));
    }

    /**
     * A filter family's key term is defined by what {@code buildParamsJson} writes for it: two
     * requests that generate the same file are one run, and two that generate different files are
     * not. Both directions are asserted, because either alone would be a coincidence.
     */
    @Test
    public void testRunKeyFollowsTheGeneratedFilterExactly()
    {
        String padded = "  smoke  ";
        assertEquals("padding is stripped when the filter is generated...",
            RunYaxunitTestsTool.buildParamsJson("/tmp/j.xml", req(null, null, null, "smoke")),
            RunYaxunitTestsTool.buildParamsJson("/tmp/j.xml", req(null, null, null, padded)));
        assertEquals("...so it must not start a second, identical run", key(req(null, null, null,
            "smoke")), key(req(null, null, null, padded)));

        // The other direction: an ABSENT family and a family written as an EMPTY array are
        // different FILES. The identity stops at what this code can prove — the bytes it
        // writes — rather than at an assumption about how the framework reads them; that way
        // round the cost of being wrong is a re-run, not a wrong report. (The pre-fix formula
        // kept them apart too, so this is not a new split.)
        assertFalse("an absent tag filter and an empty one generate different files",
            RunYaxunitTestsTool.buildParamsJson("/tmp/j.xml", req(null, null, null, null))
                .equals(RunYaxunitTestsTool.buildParamsJson("/tmp/j.xml", req(null, null, null,
                    ","))));
        assertFalse("...so they must not share a run key",
            key(req(null, null, null, null)).equals(key(req(null, null, null, ","))));
    }

    /**
     * For every declared field of {@code RunRequest}: a request differing from {@link #baseline()}
     * in EXACTLY that field.
     */
    private static Map<String, RunYaxunitTestsTool.RunRequest> runRequestVariants()
    {
        ExternalInfobaseChangesPolicy override = ExternalInfobaseChangesPolicy.OVERRIDE;
        Map<String, RunYaxunitTestsTool.RunRequest> variants = new LinkedHashMap<>();
        variants.put("configName", new RunYaxunitTestsTool.RunRequest("OtherCfg", PROJ, APP, null,
            null, null, null, 45, true, null, override,
            StandaloneServerPortConflictPolicy.CANCEL, false));
        variants.put("projectName", new RunYaxunitTestsTool.RunRequest(CFG, "OtherProj", APP, null,
            null, null, null, 45, true, null, override,
            StandaloneServerPortConflictPolicy.CANCEL, false));
        variants.put("applicationId", new RunYaxunitTestsTool.RunRequest(CFG, PROJ, "OtherApp",
            null, null, null, null, 45, true, null, override,
            StandaloneServerPortConflictPolicy.CANCEL, false));
        variants.put("extensions", request("Ext", null, null, null, 45, true, null, override,
            false));
        variants.put("modules", request(null, "Mod", null, null, 45, true, null, override, false));
        variants.put("tests", request(null, null, "Mod.Test", null, 45, true, null, override,
            false));
        variants.put("tags", request(null, null, null, "smoke", 45, true, null, override, false));
        variants.put("timeout", request(null, null, null, null, 30, true, null, override, false));
        variants.put("updateBeforeLaunch", request(null, null, null, null, 45, false, null,
            override, false));
        variants.put("updateScope", request(null, null, null, null, 45, true, "configuration",
            override, false));
        variants.put("externalChanges", request(null, null, null, null, 45, true, null,
            ExternalInfobaseChangesPolicy.IMPORT, false));
        variants.put("portConflict", new RunYaxunitTestsTool.RunRequest(CFG, PROJ, APP, null,
            null, null, null, 45, true, null, override,
            StandaloneServerPortConflictPolicy.REASSIGN, false));
        variants.put("debug", request(null, null, null, null, 45, true, null, override, true));
        return variants;
    }

    /** {@code RunRequest} fields that must NOT change the run key, each with its reason. */
    private static Map<String, String> runKeyExclusions()
    {
        Map<String, String> excluded = new LinkedHashMap<>();
        excluded.put("configName", "the RESOLVED config name is the key root; the request's own is "
            + "null whenever the call addressed the run by project+application");
        excluded.put("projectName", "the RESOLVED project is keyed instead, so both call styles "
            + "that reach one target still share a run");
        excluded.put("applicationId", "the RESOLVED application is keyed instead");
        excluded.put("timeout", "the caller's waiting window, not what runs: keying it would drop "
            + "a Pending report the moment a retry asked for a longer one");
        excluded.put("debug", "the DEBUG path returns before a run key is ever built");
        return excluded;
    }

    /**
     * The ratchet: every field of {@code RunRequest} is classified, and the classification is
     * PROVED against the production formula in both directions.
     */
    @Test
    public void testEveryRunRequestFieldIsClassifiedForTheRunKey()
    {
        Map<String, RunYaxunitTestsTool.RunRequest> variants = runRequestVariants();
        Map<String, String> excluded = runKeyExclusions();
        String base = key(baseline());
        int classified = 0;
        for (Field field : RunYaxunitTestsTool.RunRequest.class.getDeclaredFields())
        {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers()))
            {
                continue;
            }
            String name = field.getName();
            assertTrue("RunRequest." + name + " is new and nothing records what it means for the "
                + "run key. Add a variant to runRequestVariants(), then either fold the field into "
                + "buildRunKey or list it in runKeyExclusions() with the reason it cannot change "
                + "what runs. Skipping that step is exactly how #409 and #411 happened.",
                variants.containsKey(name));
            assertEquals("the variant registered for RunRequest." + name + " must differ from the "
                + "baseline in EXACTLY that field, or this loop proves nothing about it", name,
                theOnlyChangedField(baseline(), variants.get(name)));
            String varied = key(variants.get(name));
            if (excluded.containsKey(name))
            {
                assertEquals("RunRequest." + name + " is excluded from the run key (" + excluded
                    .get(name) + "), so varying it must NOT split the key: a key that is too wide "
                    + "never matches, and every call re-runs the whole suite", base, varied);
            }
            else
            {
                assertFalse("RunRequest." + name + " changes what a run executes, so it must "
                    + "change the run key. Otherwise a run started with one value can be polled "
                    + "by — and have its report delivered to — a call that asked for another, and "
                    + "the answer looks exactly like an honest one", base.equals(varied));
            }
            classified++;
        }
        assertEquals("runRequestVariants() lists a name RunRequest no longer declares", classified,
            variants.size());
        assertTrue("runKeyExclusions() names a field RunRequest does not declare",
            variants.keySet().containsAll(excluded.keySet()));
    }

    @Test
    public void testSubmissionIdentityIncludesEveryExecutionFieldExceptTimeout()
    {
        RunYaxunitTestsTool.RunRequest baseRequest = baseline();
        String base = RunYaxunitTestsTool.buildSubmissionKey(baseRequest);
        int classified = 0;
        for (Map.Entry<String, RunYaxunitTestsTool.RunRequest> variant
            : runRequestVariants().entrySet())
        {
            String field = variant.getKey();
            assertEquals("each variant must still differ in exactly its named field", field,
                theOnlyChangedField(baseRequest, variant.getValue()));
            String varied = RunYaxunitTestsTool.buildSubmissionKey(variant.getValue());
            if ("timeout".equals(field)) //$NON-NLS-1$
            {
                assertEquals("timeout controls only this call's wait and must attach to the same job",
                    base, varied);
            }
            else
            {
                assertFalse("RunRequest." + field + " changes the unresolved submission and must "
                    + "not start beside a different request", base.equals(varied));
            }
            classified++;
        }
        assertEquals(runRequestVariants().size(), classified);
    }

    /**
     * Two concurrent calls asking to rebuild different things must not share one preparation.
     */
    @Test
    public void testPrepKeyDistinguishesTheRebuildScope()
    {
        ExternalInfobaseChangesPolicy override = ExternalInfobaseChangesPolicy.OVERRIDE;
        String extA = prep(PROJ, APP, "extension:ExtA", override, JOB).prepKey();
        String extB = prep(PROJ, APP, "extension:ExtB", override, JOB).prepKey();
        String all = prep(PROJ, APP, null, override, JOB).prepKey();

        assertFalse("only ONE preparation job runs per key, so two scopes under one key means the "
            + "second caller silently gets the preparation the first one asked for",
            extA.equals(extB));
        assertFalse("a narrowed rebuild is not the full one", extA.equals(all));
    }

    /** The same normalisation as the run key — and for the same reason. */
    @Test
    public void testPrepKeyTreatsEquivalentScopesAsOnePreparation()
    {
        ExternalInfobaseChangesPolicy override = ExternalInfobaseChangesPolicy.OVERRIDE;
        String all = prep(PROJ, APP, null, override, JOB).prepKey();

        assertEquals("an omitted scope IS 'all'", all, prep(PROJ, APP, "ALL", override, JOB)
            .prepKey());
        assertEquals("the scope is a set, not a spelling", prep(PROJ, APP, "extension:A,B",
            override, JOB).prepKey(), prep(PROJ, APP, " B , extension:A ", override, JOB)
                .prepKey());
    }

    /** The target and the conflict answer were already keyed; keep them pinned. */
    @Test
    public void testPrepKeyDistinguishesTheTargetAndTheConflictAnswer()
    {
        ExternalInfobaseChangesPolicy override = ExternalInfobaseChangesPolicy.OVERRIDE;
        String here = prep(PROJ, APP, null, override, JOB).prepKey();

        assertFalse("one of the answers rewrites project sources, so a piggybacking call must "
            + "never inherit a different caller's answer", here.equals(prep(PROJ, APP, null,
                ExternalInfobaseChangesPolicy.IMPORT, JOB).prepKey()));
        assertFalse("a different application is a different infobase to prepare",
            here.equals(prep(PROJ, "OtherApp", null, override, JOB).prepKey()));
        assertFalse("a different project is a different preparation",
            here.equals(prep("OtherProj", APP, null, override, JOB).prepKey()));
    }

    /**
     * The RUN and the DEBUG path differ in ONE field — the Job's display name — and they are meant
     * to share a preparation. Keying the label would give one infobase two in-flight preparations
     * and run the recompute+update twice.
     */
    @Test
    public void testPrepKeyIgnoresTheJobLabelSoRunAndDebugShareOnePreparation()
    {
        ExternalInfobaseChangesPolicy override = ExternalInfobaseChangesPolicy.OVERRIDE;
        assertEquals("the run and debug preparations of one infobase must be the same entry",
            prep(PROJ, APP, null, override, "YAXUnit pre-launch preparation for Proj").prepKey(),
            prep(PROJ, APP, null, override, "YAXUnit debug pre-launch preparation for Proj")
                .prepKey());
    }

    /**
     * For every declared field of {@code PrepRequest}: a request differing from the baseline in
     * EXACTLY that field.
     */
    private static Map<String, RunYaxunitTestsTool.PrepRequest> prepRequestVariants()
    {
        ExternalInfobaseChangesPolicy override = ExternalInfobaseChangesPolicy.OVERRIDE;
        Map<String, RunYaxunitTestsTool.PrepRequest> variants = new LinkedHashMap<>();
        variants.put("projectName", prep("OtherProj", APP, null, override, JOB));
        variants.put("launchManager", new RunYaxunitTestsTool.PrepRequest(PROJ,
            mock(ILaunchManager.class), null, APP, null, null, override, JOB));
        variants.put("project", new RunYaxunitTestsTool.PrepRequest(PROJ, null,
            mock(IProject.class), APP, null, null, override, JOB));
        variants.put("applicationId", prep(PROJ, "OtherApp", null, override, JOB));
        variants.put("appManager", new RunYaxunitTestsTool.PrepRequest(PROJ, null, null, APP,
            mock(IApplicationManager.class), null, override, JOB));
        variants.put("updateScope", prep(PROJ, APP, "configuration", override, JOB));
        variants.put("externalChanges", prep(PROJ, APP, null,
            ExternalInfobaseChangesPolicy.IMPORT, JOB));
        variants.put("jobName", prep(PROJ, APP, null, override, "another job"));
        return variants;
    }

    /** {@code PrepRequest} fields that must NOT change the preparation key, with their reasons. */
    private static Map<String, String> prepKeyExclusions()
    {
        Map<String, String> excluded = new LinkedHashMap<>();
        excluded.put("launchManager", "a platform service handle, not an input");
        excluded.put("project", "it IS ProjectContext.of(projectName).project(), so projectName "
            + "already keys it");
        excluded.put("appManager", "tracked through an OSGi ServiceTracker and legitimately a "
            + "different object between two calls; keying it would start a duplicate preparation "
            + "on a service rebind instead of joining the running one");
        excluded.put("jobName", "the only field that differs between the RUN and the DEBUG call "
            + "site, which are meant to share one preparation");
        return excluded;
    }

    /** The same two-way ratchet for the preparation key. */
    @Test
    public void testEveryPrepRequestFieldIsClassifiedForThePrepKey()
    {
        Map<String, RunYaxunitTestsTool.PrepRequest> variants = prepRequestVariants();
        Map<String, String> excluded = prepKeyExclusions();
        String base = prep(PROJ, APP, null, ExternalInfobaseChangesPolicy.OVERRIDE, JOB).prepKey();
        int classified = 0;
        for (Field field : RunYaxunitTestsTool.PrepRequest.class.getDeclaredFields())
        {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers()))
            {
                continue;
            }
            String name = field.getName();
            assertTrue("PrepRequest." + name + " is new and nothing records what it means for the "
                + "preparation key. Add a variant to prepRequestVariants(), then either fold it "
                + "into PrepRequest.prepKey() or list it in prepKeyExclusions() with the reason it "
                + "cannot change what the preparation does.", variants.containsKey(name));
            assertEquals("the variant registered for PrepRequest." + name + " must differ from the "
                + "baseline in EXACTLY that field, or this loop proves nothing about it", name,
                theOnlyChangedField(prep(PROJ, APP, null, ExternalInfobaseChangesPolicy.OVERRIDE,
                    JOB), variants.get(name)));
            String varied = variants.get(name).prepKey();
            if (excluded.containsKey(name))
            {
                assertEquals("PrepRequest." + name + " is excluded from the preparation key ("
                    + excluded.get(name) + "), so varying it must NOT split the key: only one "
                    + "preparation may be in flight per infobase", base, varied);
            }
            else
            {
                assertFalse("PrepRequest." + name + " changes what the preparation does, so it "
                    + "must change the key — one job runs per key, and the caller that arrives "
                    + "second silently receives the first one's preparation",
                    base.equals(varied));
            }
            classified++;
        }
        assertEquals("prepRequestVariants() lists a name PrepRequest no longer declares",
            classified, variants.size());
        assertTrue("prepKeyExclusions() names a field PrepRequest does not declare",
            variants.keySet().containsAll(excluded.keySet()));
    }

    private static String extractJobId(String markdown)
    {
        String marker = "| jobId | "; //$NON-NLS-1$
        int start = markdown.indexOf(marker);
        assertTrue("Expected jobId row in: " + markdown, start >= 0); //$NON-NLS-1$
        int valueStart = start + marker.length();
        int end = markdown.indexOf(" |", valueStart); //$NON-NLS-1$
        assertTrue("Expected complete jobId row in: " + markdown, end > valueStart); //$NON-NLS-1$
        return markdown.substring(valueStart, end).trim();
    }

    /**
     * The name of the ONE field in which {@code variant} differs from {@code baseline}.
     *
     * <p>Without this, a ratchet built on a hand-written variant map proves less than it looks:
     * a variant that changed some OTHER keyed field would satisfy the "must change the key" arm
     * for a field the key actually ignores, and an "excluded" variant identical to the baseline
     * would satisfy the "must not change the key" arm without varying anything at all.
     *
     * @return the single differing field name, or a description of how many differ when it is
     *         not exactly one (so the assertion message names the real problem)
     */
    private static String theOnlyChangedField(Object baseline, Object variant)
    {
        List<String> changed = new ArrayList<>();
        for (Field field : baseline.getClass().getDeclaredFields())
        {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers()))
            {
                continue;
            }
            try
            {
                field.setAccessible(true);
                if (!Objects.equals(field.get(baseline), field.get(variant)))
                {
                    changed.add(field.getName());
                }
            }
            catch (ReflectiveOperationException e)
            {
                throw new IllegalStateException("cannot read " + field.getName(), e);
            }
        }
        return changed.size() == 1 ? changed.get(0) : changed.toString();
    }
}
