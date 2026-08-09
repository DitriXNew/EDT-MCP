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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.IStatus;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ExternalInfobaseChangesPolicy;
import com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PreLaunchResult;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PrepInFlight;

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
    public void testGuideDocumentsOnceOnlyPendingDelivery()
    {
        // #136/#137: there is NO time-based result cache — a completed result is
        // delivered to the matching identical call exactly once (the Pending
        // re-call contract); every later identical call re-runs the tests. The
        // guide pins the once-only delivery and the abandoned-Pending caveat so
        // the contract can't silently drift back to a stale read cache.
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must state there is no time-based result cache",
            guide.contains("NO time-based result cache"));
        assertTrue("guide must document the once-only delivery of a Pending result",
            guide.contains("exactly once"));
        assertTrue("guide must document the abandoned-Pending caveat",
            guide.contains("abandoned Pending"));
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
        // contract as the RUN path, mirroring DebugLaunchTool); the 1003 session
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
            desc.contains("recomputes only projects")); //$NON-NLS-1$
        assertTrue("description must say the prepared mark survives an EDT restart",
            desc.contains("survives an EDT restart")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionDocumentsTheClampedWindowAndThePhases()
    {
        // Ratchet: the description must state the ceiling the code enforces (a window the
        // transport cannot deliver is a promise the tool cannot keep, #357) and name the phases
        // a Pending can report, since that label is the caller's only signal.
        String desc = new RunYaxunitTestsTool().getDescription();
        assertTrue("description must state the maximum window",
            desc.contains(String.valueOf(RunYaxunitTestsTool.MAX_TIMEOUT_SECONDS)));
        assertTrue("description must say a larger timeout is clamped",
            desc.contains("clamped")); //$NON-NLS-1$
        assertTrue("description must say Pending names the phase",
            desc.contains("Pending") && desc.contains("phase")); //$NON-NLS-1$ //$NON-NLS-2$
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
        // prep job, and the pending-retry contract when the budget is exceeded.
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must mention the 25-second budget",
            guide.contains("25s") || guide.contains("25-second")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must say preparation runs in a background job",
            guide.contains("background")); //$NON-NLS-1$
        assertTrue("guide must document the pending-retry contract for prep",
            guide.contains("same arguments")); //$NON-NLS-1$
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
    public void testRemainingSecondsFloorsAtZeroAndNeverOvershootsTheDeadline()
    {
        long now = System.currentTimeMillis();
        assertEquals("a deadline already past leaves no time to wait", 0,
            RunYaxunitTestsTool.remainingSeconds(now - 5_000L));
        assertEquals("a deadline already past leaves no millis to wait", 0L,
            RunYaxunitTestsTool.remainingMillis(now - 5_000L));
        int remaining = RunYaxunitTestsTool.remainingSeconds(now + 10_000L);
        assertTrue("the remainder must not exceed the distance to the deadline",
            remaining <= 10 && remaining >= 9);
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
        String prepKey = "ratchet-357-" + System.nanoTime(); //$NON-NLS-1$
        PrepInFlight entry = new PrepInFlight(System.currentTimeMillis());
        // Pretend the job is already running: the CAS is spent, so no Job is scheduled and this
        // call is a pure waiter — exactly the "second identical call" of the bug report.
        entry.started.set(true);
        entry.phase = LaunchLifecycleUtils.PHASE_DB_UPDATE;
        LaunchLifecycleUtils.PREP_INFLIGHT.put(prepKey, entry);
        try
        {
            RunYaxunitTestsTool.PrepRequest req = new RunYaxunitTestsTool.PrepRequest(
                "TestConfiguration", null, null, "TestConfiguration.SomeApp", //$NON-NLS-1$ //$NON-NLS-2$
                null, null, ExternalInfobaseChangesPolicy.DEFAULT, "ratchet"); //$NON-NLS-1$
            RunYaxunitTestsTool.CallState phase = new RunYaxunitTestsTool.CallState();
            long budgetMs = 2_000L;

            long startedAt = System.currentTimeMillis();
            String pending = RunYaxunitTestsTool.awaitPreparedOrPending(prepKey, req,
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
        assertFalse("an hour-old preparation that is still running must NOT be replaced",
            running.isExpired());

        running.done = true;
        assertTrue("once finished, an old entry may be discarded so the next call starts fresh",
            running.isExpired());

        PrepInFlight fresh = new PrepInFlight(System.currentTimeMillis());
        fresh.done = true;
        assertFalse("a just-finished entry is still fetchable and must not be discarded yet",
            fresh.isExpired());
    }

    @Test
    public void testWorkThatNeverStartedIsReportedAsAnErrorNotAsPending()
    {
        // BoundedJob distinguishes "still running" from "never left the scheduler". Reporting the
        // second as Pending would be a lie in the one sentence the caller uses to decide whether
        // to wait: nothing was launched, so there is nothing to wait for.
        String message = RunYaxunitTestsTool.buildStalledPendingMessage("spawn", 12);
        assertTrue("the still-running case stays a Pending", message.contains("**Pending:**")); //$NON-NLS-1$
        assertTrue("and says the work was not cancelled",
            message.contains("nothing was cancelled")); //$NON-NLS-1$
        // The never-ran case is a separate branch in runBounded; its wording is pinned by the
        // e2e/description contract, and the distinction itself is what this asserts.
        assertFalse("the still-running message must not claim the work never started",
            message.contains("did not start")); //$NON-NLS-1$
    }

    @Test
    public void testAResultFinishedAfterThePendingStaysFetchable()
    {
        // The backstop stops WAITING for the worker, it does not stop the worker. A worker that
        // finishes afterwards runs the normal success path, which consumes the pending-fetch
        // marker and hands the report to a holder nobody reads. Without the re-arm the finished
        // report is unreachable: the next identical call sees no active launch and no pending
        // fetch, starts a fresh run, and wipes the report directory on the way — the opposite of
        // "call again to pick up where you left off".
        String runKey = "ratchet-357-late-" + System.nanoTime(); //$NON-NLS-1$
        try
        {
            RunYaxunitTestsTool.CallState state = new RunYaxunitTestsTool.CallState();
            // The run armed its marker before polling, then the success path consumed it.
            RunYaxunitTestsTool.armUndeliveredResult(runKey);
            state.consumeResultFor(runKey);
            assertFalse("the success path consumes the marker",
                RunYaxunitTestsTool.hasUndeliveredResult(runKey));

            // The backstop answered first: the caller already holds a Pending.
            assertTrue("the backstop must win the answer when the worker has not published yet",
                state.claimAnswer());

            // ...and only now does the worker come back with a real report.
            assertFalse("a worker that lost the race must not claim the answer",
                state.publishResult());
            assertTrue("a result finished after the Pending must stay fetchable by the next call",
                RunYaxunitTestsTool.hasUndeliveredResult(runKey));
        }
        finally
        {
            RunYaxunitTestsTool.forgetUndeliveredResult(runKey);
        }
    }

    @Test
    public void testADeliveredResultDoesNotLeaveAMarkerBehind()
    {
        // The ordinary case must be untouched: when the caller IS listening, the worker owns the
        // answer and the consumed marker stays consumed. Re-arming here would make the next
        // identical call serve the same report again instead of re-running the tests.
        String runKey = "ratchet-357-ontime-" + System.nanoTime(); //$NON-NLS-1$
        try
        {
            RunYaxunitTestsTool.CallState state = new RunYaxunitTestsTool.CallState();
            RunYaxunitTestsTool.armUndeliveredResult(runKey);
            state.consumeResultFor(runKey);

            assertTrue("the worker must own the answer when it finishes in time",
                state.publishResult());
            assertFalse("a delivered result must not be left marked as undelivered",
                RunYaxunitTestsTool.hasUndeliveredResult(runKey));
            assertFalse("the backstop must not answer after the worker already did",
                state.claimAnswer());
        }
        finally
        {
            RunYaxunitTestsTool.forgetUndeliveredResult(runKey);
        }
    }

    @Test
    public void testNoMarkerIsInventedForACallThatNeverReachedARun()
    {
        // A backstop that fires during resolve or preparation consumed no marker, because there
        // was no run behind the key yet. Re-arming there would let the next call serve a report
        // left over from an EARLIER run as if it were this one's — a false success, worse than
        // the lost report the re-arm exists to prevent.
        String runKey = "ratchet-357-norun-" + System.nanoTime(); //$NON-NLS-1$
        try
        {
            RunYaxunitTestsTool.CallState state = new RunYaxunitTestsTool.CallState();
            assertTrue(state.claimAnswer());
            assertFalse(state.publishResult());
            assertFalse("a call that never consumed a marker must not arm one",
                RunYaxunitTestsTool.hasUndeliveredResult(runKey));
        }
        finally
        {
            RunYaxunitTestsTool.forgetUndeliveredResult(runKey);
        }
    }

    @Test
    public void testAPendingFetchThatFoundNoReportReleasesOwnership() throws Exception
    {
        // Drives the REAL fall-through in tryDeliverPendingResult (empty report directory, so
        // findJunitXml returns nothing) rather than the helper it calls — a test that only
        // exercised releaseConsumed() directly would still pass if the call site were deleted.
        //
        // The marker this call consumed referred to no report. If ownership survived that, the
        // call would go on to start a FRESH run and could later re-arm the key on the strength of
        // a report that never existed — by which time the key belongs to that fresh run, whose
        // result another caller may already have delivered.
        String runKey = "ratchet-357-noreport-" + System.nanoTime(); //$NON-NLS-1$
        java.nio.file.Path emptyDir = java.nio.file.Files.createTempDirectory("yaxunit-ratchet"); //$NON-NLS-1$
        try
        {
            RunYaxunitTestsTool.armUndeliveredResult(runKey);
            RunYaxunitTestsTool.CallState state = new RunYaxunitTestsTool.CallState();

            String delivered = new RunYaxunitTestsTool().tryDeliverPendingResult(runKey, emptyDir,
                "TestConfiguration", "TestConfiguration.SomeApp", state); //$NON-NLS-1$ //$NON-NLS-2$

            assertNull("an empty report directory must fall through to a fresh run", delivered);
            assertFalse("the consumed marker referred to nothing, so ownership must be released",
                state.consumed(runKey));

            // ...and the proof that it matters: a later loss must NOT resurrect the key.
            assertTrue(state.claimAnswer());
            assertFalse(state.publishResult());
            assertFalse("a call holding no result must not re-arm the key",
                RunYaxunitTestsTool.hasUndeliveredResult(runKey));
        }
        finally
        {
            RunYaxunitTestsTool.forgetUndeliveredResult(runKey);
            java.nio.file.Files.deleteIfExists(emptyDir);
        }
    }

    @Test
    public void testACallThatLostTheRaceForTheMarkerDoesNotResurrectIt()
    {
        // Two calls poll the SAME launch: A timed out and is still inside its read, B is
        // listening and delivers the report, consuming the shared marker. When A finally
        // finishes it must NOT put the marker back — its remove was a no-op, the result was
        // already handed over, and resurrecting it would serve the same report twice and
        // suppress a genuine re-run.
        String runKey = "ratchet-357-loser-" + System.nanoTime(); //$NON-NLS-1$
        try
        {
            RunYaxunitTestsTool.armUndeliveredResult(runKey);
            RunYaxunitTestsTool.CallState a = new RunYaxunitTestsTool.CallState();
            RunYaxunitTestsTool.CallState b = new RunYaxunitTestsTool.CallState();

            b.consumeResultFor(runKey);                 // B took it and delivered
            assertTrue("B owns the consumed result", b.consumed(runKey));
            assertTrue(b.publishResult());

            a.consumeResultFor(runKey);                 // A's remove finds nothing left
            assertFalse("A must not claim a result it never took", a.consumed(runKey));
            assertTrue("A's caller gave up", a.claimAnswer());
            assertFalse(a.publishResult());

            assertFalse("a delivered result must not be resurrected by the loser",
                RunYaxunitTestsTool.hasUndeliveredResult(runKey));
        }
        finally
        {
            RunYaxunitTestsTool.forgetUndeliveredResult(runKey);
        }
    }

    @Test
    public void testTheAnswerIsOwnedByExactlyOneSide()
    {
        // Both sides settle through the same compare-and-set, so the result can never be both
        // returned and reported as still pending.
        RunYaxunitTestsTool.CallState state = new RunYaxunitTestsTool.CallState();
        assertTrue("first claim wins", state.publishResult());
        assertFalse("second claim loses", state.claimAnswer());
        assertFalse("and stays lost", state.publishResult());
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
        assertEquals("a missing entry must still name a phase, never null",
            "prep:" + LaunchLifecycleUtils.PHASE_RECOMPUTE,
            RunYaxunitTestsTool.prepPhaseLabel(null));
    }

    @Test
    public void testStalledPendingNamesThePhaseAndSaysTheWorkIsStillRunning()
    {
        String message = RunYaxunitTestsTool.buildStalledPendingMessage("prep:recompute", 47);
        assertTrue("the message replacing the transport error must be a Pending",
            message.contains("**Pending:**")); //$NON-NLS-1$
        assertTrue("it must name the phase — that is the whole information the bare timeout lacked",
            message.contains("prep:recompute")); //$NON-NLS-1$
        assertTrue("it must report how long the call waited", message.contains("47s")); //$NON-NLS-1$
        assertTrue("it must say the work was NOT cancelled",
            message.contains("nothing was cancelled")); //$NON-NLS-1$
        assertTrue("it must tell the caller how to keep waiting",
            message.contains("same arguments")); //$NON-NLS-1$
        assertTrue("it must name the one case where retrying is pointless",
            message.contains("modal dialog")); //$NON-NLS-1$
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
            guide.contains("prep:recompute") && guide.contains("prep:db-update")); //$NON-NLS-1$ //$NON-NLS-2$
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

    // ============ #230 — the async prep body brackets the auth-dialog suppressor ============

    @Test
    public void testPrepJobBodyBracketsTheAuthDialogSuppressorCounter() throws Exception
    {
        // #230 regression guard: schedulePrepJob runs prepareForFreshLaunch (whose db-update
        // phase does the infobase connect that raises the blocking "Configure Infobase access
        // Settings" dialog) in a fire-and-forget background Job. execute() only blocks on it for
        // the 25s budget and then returns "pending", so the trailing grace window alone would NOT
        // cover a minutes-long prep — the in-flight COUNTER must span the whole body, exactly like
        // DebugLaunchTool.runLaunchJobBody. This drives the extracted body seam headlessly and
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
}
