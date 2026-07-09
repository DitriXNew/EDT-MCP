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

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.IStatus;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor;
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
        // recomputed (not all projects on every call) and that the first call
        // after EDT start always recomputes fully.
        String desc = new RunYaxunitTestsTool().getDescription();
        assertTrue("description must mention that only changed projects are recomputed",
            desc.contains("recomputes only projects")); //$NON-NLS-1$
        assertTrue("description must note the full recompute on first call after EDT start",
            desc.contains("first call after EDT starts")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionDocuments25sPendingBudget()
    {
        // Ratchet: the description must mention the 25s pre-launch budget and
        // that the tool returns Pending when it is exceeded.
        String desc = new RunYaxunitTestsTool().getDescription();
        assertTrue("description must mention the 25s budget",
            desc.contains("25s")); //$NON-NLS-1$
        assertTrue("description must say Pending is returned when budget exceeded",
            desc.contains("Pending")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsSelectiveRecompute()
    {
        // Ratchet: the guide must explain the dirty-tracking mechanism —
        // only file-changed projects are force-recomputed; others get the cheap
        // derived-data drain; first launch is always full.
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must document that only changed projects are recomputed",
            guide.contains("selective")); //$NON-NLS-1$
        assertTrue("guide must document the conservative first-launch recompute",
            guide.contains("first call after EDT starts")); //$NON-NLS-1$
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
            null, null, "prep-job-suppressor-ratchet"); //$NON-NLS-1$
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
