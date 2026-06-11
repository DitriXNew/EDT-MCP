/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;

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
        // Ratchet: the debug launch site arms the session matcher
        // (arm(true, true)) as the race net behind the sweep — the guide documents the
        // 'Keep existing and start new' auto-press so the contract can't drift.
        String guide = new RunYaxunitTestsTool().getGuide();
        assertTrue("guide must document the 1003 'Keep existing and start new' race net",
            guide.contains("Keep existing and start new")); //$NON-NLS-1$
    }
}
