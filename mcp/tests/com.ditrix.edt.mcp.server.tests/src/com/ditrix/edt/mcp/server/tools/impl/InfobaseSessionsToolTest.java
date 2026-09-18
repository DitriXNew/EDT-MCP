/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.InfobaseSessionsTool.ResolvedApplication;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.ReadResult;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.SessionInfo;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.TerminationResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Pins the public session-tool contract and its pure target-selection safety rules. */
public class InfobaseSessionsToolTest
{
    private static final SessionInfo DESIGNER = new SessionInfo(
        "11111111-1111-1111-1111-111111111111", 1L, "Designer", "agent", "host", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "2026-01-01T10:00:00", "2026-01-01T10:01:00", true); //$NON-NLS-1$ //$NON-NLS-2$

    private static final SessionInfo CLIENT = new SessionInfo(
        "22222222-2222-2222-2222-222222222222", 42L, "1CV8C", "User", "desk", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "2026-01-01T10:02:00", "2026-01-01T10:03:00", false); //$NON-NLS-1$ //$NON-NLS-2$

    private static final SessionInfo SECOND_CLIENT = new SessionInfo(
        "33333333-3333-3333-3333-333333333333", 43L, "1CV8C", "Other", "desk2", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "2026-01-01T10:04:00", "2026-01-01T10:05:00", false); //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void metadataDeclaresJsonInfobaseDataTool()
    {
        InfobaseSessionsTool tool = new InfobaseSessionsTool();
        assertEquals("infobase_sessions", tool.getName()); //$NON-NLS-1$
        assertEquals(InfobaseSessionsTool.NAME, tool.getName());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertTrue(tool.returnsInfobaseData());
        assertTrue(tool.getDescription().contains("get_tool_guide('infobase_sessions')")); //$NON-NLS-1$
        assertTrue(tool.getDescription().contains("Designer")); //$NON-NLS-1$
    }

    @Test
    public void inputSchemaDeclaresOnlyProjectAsRequired()
    {
        JsonObject schema = JsonParser.parseString(new InfobaseSessionsTool().getInputSchema())
            .getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        for (String property : List.of("projectName", "applicationId", "action", "sessionId", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "all", "confirm", "message")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue(property, properties.has(property));
        }
        JsonArray required = schema.getAsJsonArray("required"); //$NON-NLS-1$
        assertEquals(1, required.size());
        assertEquals("projectName", required.get(0).getAsString()); //$NON-NLS-1$
        assertEquals("list", properties.getAsJsonObject("action").getAsJsonArray("enum") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get(0).getAsString());
        assertEquals("terminate", properties.getAsJsonObject("action").getAsJsonArray("enum") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get(1).getAsString());
    }

    @Test
    public void outputSchemaNamesReachabilityAndSessionPayloads()
    {
        String schema = new InfobaseSessionsTool().getOutputSchema();
        assertNotNull(schema);
        for (String key : List.of("reachable", "unreachableReason", "sessions", "count", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "terminatedCount", "attemptedCount", "verification", "verificationReason", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "mutationOutcomeUnknown", "verified", "mismatched", "not_verifiable")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertTrue(key, schema.contains("\"" + key + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertTrue(schema.contains("applicationKindIsDesigner")); //$NON-NLS-1$
        assertFalse(schema.contains("isEdtAgent")); //$NON-NLS-1$
        String actionDescription = JsonParser.parseString(schema).getAsJsonObject()
            .getAsJsonObject("properties").getAsJsonObject("action") //$NON-NLS-1$ //$NON-NLS-2$
            .get("description").getAsString(); //$NON-NLS-1$
        assertTrue(actionDescription.contains("requested action that this result refers to")); //$NON-NLS-1$
        assertFalse(actionDescription.contains("completed action")); //$NON-NLS-1$
    }

    @Test
    public void outputSchemaDeclaresEveryFieldEmittedByRealResultPaths()
    {
        JsonObject properties = JsonParser.parseString(
            new InfobaseSessionsTool().getOutputSchema()).getAsJsonObject()
            .getAsJsonObject("properties"); //$NON-NLS-1$
        JsonObject success = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.readable(List.of()))).getAsJsonObject();
        JsonObject refusal = JsonParser.parseString(new InfobaseSessionsTool().execute(Map.of(
            "projectName", "Demo", "action", "terminate", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "sessionId", "42"))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject failure = JsonParser.parseString(
            InfobaseSessionsTool.terminationSequenceStoppedResult("Demo", //$NON-NLS-1$
                "ServerApplication.Demo", 1, "second command failed.")) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject();
        JsonObject beforeLaunchFailure = JsonParser.parseString(
            InfobaseSessionsTool.firstTerminationFailureResult("Demo", //$NON-NLS-1$
                "ServerApplication.Demo", TerminationResult.notStarted( //$NON-NLS-1$
                    "Preparation timed out."))) //$NON-NLS-1$
            .getAsJsonObject();
        JsonObject partialReadBack = JsonParser.parseString(
            InfobaseSessionsTool.terminationReadBackResult("Demo", //$NON-NLS-1$
                "ServerApplication.Demo", List.of(DESIGNER, CLIENT), //$NON-NLS-1$
                ReadResult.readable(List.of(CLIENT))))
            .getAsJsonObject();

        JsonObject incomplete = JsonParser.parseString(
            InfobaseSessionsTool.bulkTerminationIncompleteResult("Demo", //$NON-NLS-1$
                "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$
                ReadResult.readable(List.of(SECOND_CLIENT)), List.of(SECOND_CLIENT), 1))
            .getAsJsonObject();

        for (JsonObject payload : List.of(success, refusal, failure, beforeLaunchFailure,
            partialReadBack, incomplete))
        {
            for (String emitted : payload.keySet())
            {
                assertTrue("outputSchema omits emitted field: " + emitted, //$NON-NLS-1$
                    properties.has(emitted));
            }
        }
    }

    @Test
    public void guideExplainsUnreachableAndExactClearingCall()
    {
        String guide = new InfobaseSessionsTool().getGuide();
        assertTrue("the guide must name the unreachable outcome", //$NON-NLS-1$
            guide.contains("`reachable=false` with `unreachableReason`")); //$NON-NLS-1$
        assertTrue("the guide must forbid reading unreachable as an empty list", //$NON-NLS-1$
            guide.contains("Never interpret `reachable=false` as an empty session list")); //$NON-NLS-1$
        assertTrue("the guide must state that an empty readable list IS proof", //$NON-NLS-1$
            guide.contains("proves there are no sessions")); //$NON-NLS-1$
        assertTrue("the guide must spell out the exact clearing call", //$NON-NLS-1$
            guide.contains("all=true, confirm=true")); //$NON-NLS-1$
        assertTrue("the guide must document the ambiguous Designer session", //$NON-NLS-1$
            guide.contains("app-id: Designer")); //$NON-NLS-1$
        assertTrue("the guide must name the human Configurator ambiguity", //$NON-NLS-1$
            guide.contains("OR a human Configurator")); //$NON-NLS-1$
        assertTrue("the guide must say the tool cannot distinguish Designer ownership", //$NON-NLS-1$
            guide.contains("cannot tell them apart")); //$NON-NLS-1$
        assertTrue("the guide must name the truthful Designer-kind field", //$NON-NLS-1$
            guide.contains("`applicationKindIsDesigner=true`")); //$NON-NLS-1$
        assertFalse("the guide must not expose the misleading ownership field", //$NON-NLS-1$
            guide.contains("`isEdtAgent")); //$NON-NLS-1$
        assertTrue("the guide must keep bulk Designer termination disabled", //$NON-NLS-1$
            guide.contains("all=true` always skips it")); //$NON-NLS-1$
        assertTrue("the guide must require an exact id for explicit Designer termination", //$NON-NLS-1$
            guide.contains("exact full session UUID")); //$NON-NLS-1$
        assertTrue("the guide must say a terminate is verified by a re-read, not assumed", //$NON-NLS-1$
            guide.contains("Termination is verified, not assumed")); //$NON-NLS-1$
        assertTrue("the guide must warn that ibcmd exits 0 for a session that is already gone", //$NON-NLS-1$
            guide.contains("exits 0 even for a session UUID that no longer exists")); //$NON-NLS-1$
        assertTrue("the guide must limit mismatched blockers to non-Designer survivors", //$NON-NLS-1$
            guide.contains("Surviving non-Designer sessions still block an update")); //$NON-NLS-1$
        assertFalse("the guide must not claim every mismatched survivor blocks an update", //$NON-NLS-1$
            guide.contains("those sessions still block an update")); //$NON-NLS-1$
        assertTrue("the guide must report an unverified stopped sequence as unknown", //$NON-NLS-1$
            guide.contains("`mutationOutcomeUnknown=true`")); //$NON-NLS-1$
        assertFalse("the guide must not claim an unverified stopped sequence committed", //$NON-NLS-1$
            guide.contains("error carries `mutationCommitted=true`")); //$NON-NLS-1$
    }

    @Test
    public void terminationReadBackMatchesTheSessionUuidCaseInsensitively()
    {
        SessionInfo upperCase = new SessionInfo(
            "024CDB6E-D089-45D5-B9EC-9ACB561CD9F1", 10L, "1CV8C", "User", "desk", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "2026-01-01T10:02:00", "2026-01-01T10:03:00", false); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a UUID differing only in case is the SAME live session", //$NON-NLS-1$
            InfobaseSessionsTool.containsSessionId(List.of(upperCase),
                "024cdb6e-d089-45d5-b9ec-9acb561cd9f1")); //$NON-NLS-1$
        assertFalse("an absent UUID must read as gone, which is what lets a terminate succeed", //$NON-NLS-1$
            InfobaseSessionsTool.containsSessionId(List.of(upperCase),
                "00000000-0000-0000-0000-000000000000")); //$NON-NLS-1$
        assertFalse("an empty list reports every targeted session as gone", //$NON-NLS-1$
            InfobaseSessionsTool.containsSessionId(List.of(),
                "024cdb6e-d089-45d5-b9ec-9acb561cd9f1")); //$NON-NLS-1$
    }

    @Test
    public void terminateRequiresConfirmationAndExactlyOneSelector()
    {
        assertTrue(InfobaseSessionsTool.validate("terminate", "42", false, false, null) //$NON-NLS-1$ //$NON-NLS-2$
            .contains("confirm=true")); //$NON-NLS-1$
        assertTrue(InfobaseSessionsTool.validate("terminate", null, false, true, null) //$NON-NLS-1$
            .contains("exactly one")); //$NON-NLS-1$
        assertTrue(InfobaseSessionsTool.validate("terminate", "42", true, true, null) //$NON-NLS-1$ //$NON-NLS-2$
            .contains("exactly one")); //$NON-NLS-1$
        assertTrue(InfobaseSessionsTool.validate("terminate", "not-an-id", false, true, null) //$NON-NLS-1$ //$NON-NLS-2$
            .contains("full UUID")); //$NON-NLS-1$
        assertNull(InfobaseSessionsTool.validate("terminate", "42", false, true, null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(InfobaseSessionsTool.validate("terminate", null, true, true, null)); //$NON-NLS-1$
    }

    @Test
    public void numericSelectorResolvesClientButRefusesDesigner()
    {
        InfobaseSessionsTool.Selection client = InfobaseSessionsTool.selectSessions(
            List.of(DESIGNER, CLIENT), "42", false); //$NON-NLS-1$
        assertNull(client.error);
        assertEquals(List.of(CLIENT), client.sessions);

        InfobaseSessionsTool.Selection designer = InfobaseSessionsTool.selectSessions(
            List.of(DESIGNER, CLIENT), "1", false); //$NON-NLS-1$
        assertTrue(designer.sessions.isEmpty());
        assertTrue(designer.error.contains("exact full UUID")); //$NON-NLS-1$
    }

    @Test
    public void missingSessionErrorKeepsNormalValidatedIdentifiersUnchanged()
    {
        InfobaseSessionsTool.Selection selection = InfobaseSessionsTool.selectSessions(
            List.of(DESIGNER, CLIENT), "99", false); //$NON-NLS-1$

        assertEquals("Session '99' was not found in the readable session list. Available session " //$NON-NLS-1$
            + "identifiers: 11111111-1111-1111-1111-111111111111 (session-id 1), " //$NON-NLS-1$
            + "22222222-2222-2222-2222-222222222222 (session-id 42).", selection.error); //$NON-NLS-1$
    }

    @Test
    public void missingSessionErrorOmitsInvalidParsedIdentifiers()
    {
        SessionInfo partlyInvalid = new SessionInfo(
            "Ivanov", 42L, "Ivanov Ivanovich <ivanov@corp>", "User", "desk", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "started yesterday", "active whenever", false); //$NON-NLS-1$ //$NON-NLS-2$
        SessionInfo entirelyInvalid = new SessionInfo(
            "Petrov", -7L, "Petrov Petr <petrov@corp>", "User", "desk", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "unknown", "unknown", false); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseSessionsTool.Selection selection = InfobaseSessionsTool.selectSessions(
            List.of(partlyInvalid, entirelyInvalid), "99", false); //$NON-NLS-1$

        assertEquals("Session '99' was not found in the readable session list. Available session " //$NON-NLS-1$
            + "identifiers: session-id 42.", selection.error); //$NON-NLS-1$
        assertFalse(selection.error.contains("Ivanov")); //$NON-NLS-1$
        assertFalse(selection.error.contains("Petrov")); //$NON-NLS-1$
        assertFalse(selection.error.contains("-7")); //$NON-NLS-1$

        InfobaseSessionsTool.Selection noValidatedIdentifiers =
            InfobaseSessionsTool.selectSessions(List.of(entirelyInvalid), "99", false); //$NON-NLS-1$
        assertTrue(noValidatedIdentifiers.error.endsWith("Available session identifiers: none.")); //$NON-NLS-1$
    }

    @Test
    public void exactUuidSelectorAllowsDesignerAndVerifiedMessagePreservesOwnershipAmbiguity()
    {
        InfobaseSessionsTool.Selection selection = InfobaseSessionsTool.selectSessions(
            List.of(DESIGNER, CLIENT), DESIGNER.sessionId(), false);

        assertNull(selection.error);
        assertEquals(List.of(DESIGNER), selection.sessions);
        JsonObject result = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", selection.sessions, //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.readable(List.of()))).getAsJsonObject();
        String message = result.get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message.contains("session that reported app-id: Designer")); //$NON-NLS-1$
        assertTrue(message.contains("may have been EDT's own update agent or a human Configurator")); //$NON-NLS-1$
        assertTrue(message.contains("If it was EDT's, EDT re-creates its agent on its next connect")); //$NON-NLS-1$
        assertTrue(message.contains("a person's Configurator session will simply have been closed")); //$NON-NLS-1$
        assertTrue(message.contains("update running at the moment of termination can fail")); //$NON-NLS-1$
        assertFalse(message.contains("Terminated EDT's")); //$NON-NLS-1$
    }

    @Test
    public void allAlwaysExcludesDesigner()
    {
        InfobaseSessionsTool.Selection selection = InfobaseSessionsTool.selectSessions(
            List.of(DESIGNER, CLIENT), null, true);
        assertNull(selection.error);
        assertEquals(List.of(CLIENT), selection.sessions);
        assertFalse(selection.sessions.get(0).applicationKindIsDesigner());
    }

    @Test
    public void emptyBulkSelectionDoesNotInventADesignerAgent()
    {
        String noSessions = InfobaseSessionsTool.emptySelectionMessage(List.of());
        assertEquals("There were no sessions to terminate.", noSessions); //$NON-NLS-1$
        assertFalse(noSessions.contains("Designer")); //$NON-NLS-1$
        assertFalse(noSessions.contains("agent")); //$NON-NLS-1$

        String designerSkipped = InfobaseSessionsTool.emptySelectionMessage(List.of(DESIGNER));
        assertEquals("A session reporting app-id: Designer was skipped because bulk termination " //$NON-NLS-1$
            + "never selects that kind.", designerSkipped); //$NON-NLS-1$
        assertFalse(designerSkipped.contains("EDT Designer agent")); //$NON-NLS-1$
        assertFalse(designerSkipped.contains("remains protected")); //$NON-NLS-1$
    }

    @Test
    public void notVerifiableTerminationReportsOnlyAttemptedCount()
    {
        JsonObject result = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.unreachable("server stopped before verification"))).getAsJsonObject(); //$NON-NLS-1$
        JsonObject expected = JsonParser.parseString("{" //$NON-NLS-1$
            + "\"success\":true,\"action\":\"terminate\",\"project\":\"Demo\"," //$NON-NLS-1$
            + "\"applicationId\":\"ServerApplication.Demo\",\"reachable\":true," //$NON-NLS-1$
            + "\"attemptedCount\":1,\"verification\":\"not_verifiable\"," //$NON-NLS-1$
            + "\"verificationReason\":\"server stopped before verification\"," //$NON-NLS-1$
            + "\"message\":\"ibcmd accepted 1 termination attempt(s), but the list could not " //$NON-NLS-1$
            + "be re-read to confirm they are gone. List again before treating the infobase as " //$NON-NLS-1$
            + "clear.\"}") //$NON-NLS-1$
            .getAsJsonObject();

        assertEquals(expected, result);
        assertEquals("not_verifiable", result.get("verification").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, result.get("attemptedCount").getAsInt()); //$NON-NLS-1$
        assertFalse(result.has("terminatedCount")); //$NON-NLS-1$
        assertFalse(result.has("sessions")); //$NON-NLS-1$
        assertTrue(result.get("message").getAsString().contains("List again")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void stoppedTerminationSequenceReportsAcceptedAttemptsWithoutUnverifiedCompletions()
    {
        JsonObject result = JsonParser.parseString(
            InfobaseSessionsTool.terminationSequenceStoppedResult("Demo", //$NON-NLS-1$
                "ServerApplication.Demo", 1, "second command failed.")) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject();
        JsonObject expected = JsonParser.parseString("{" //$NON-NLS-1$
            + "\"success\":false," //$NON-NLS-1$
            + "\"error\":\"Session termination stopped after 1 accepted attempt(s): second " //$NON-NLS-1$
            + "command failed. The session list was not re-read, so no attempted termination is " //$NON-NLS-1$
            + "reported as completed. Run infobase_sessions(action='list', projectName='Demo', " //$NON-NLS-1$
            + "applicationId='ServerApplication.Demo') to see who still holds sessions.\"," //$NON-NLS-1$
            + "\"mutationOutcomeUnknown\":true," //$NON-NLS-1$
            + "\"action\":\"terminate\",\"project\":\"Demo\"," //$NON-NLS-1$
            + "\"applicationId\":\"ServerApplication.Demo\",\"reachable\":false," //$NON-NLS-1$
            + "\"unreachableReason\":\"second command failed.\",\"attemptedCount\":1," //$NON-NLS-1$
            + "\"verification\":\"not_verifiable\"," //$NON-NLS-1$
            + "\"verificationReason\":\"The session list was not re-read because the " //$NON-NLS-1$
            + "termination sequence stopped after a later command failed.\"}") //$NON-NLS-1$
            .getAsJsonObject();

        assertEquals(expected, result);
        assertTrue(result.has("attemptedCount")); //$NON-NLS-1$
        assertTrue(result.has("verificationReason")); //$NON-NLS-1$
        assertTrue(result.has("mutationOutcomeUnknown")); //$NON-NLS-1$
        assertFalse(result.has("mutationCommitted")); //$NON-NLS-1$
        assertFalse(result.has("terminatedCount")); //$NON-NLS-1$
        assertFalse(result.has("sessions")); //$NON-NLS-1$
    }

    @Test
    public void failureBeforeFirstTerminationLaunchIsAnOrdinaryError()
    {
        JsonObject result = JsonParser.parseString(
            InfobaseSessionsTool.firstTerminationFailureResult("Demo", //$NON-NLS-1$
                "ServerApplication.Demo", TerminationResult.notStarted( //$NON-NLS-1$
                    "Preparation exceeded its bounded worker deadline."))) //$NON-NLS-1$
            .getAsJsonObject();

        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.has("mutationOutcomeUnknown")); //$NON-NLS-1$
        assertFalse(result.has("mutationCommitted")); //$NON-NLS-1$
        assertFalse(result.get("reachable").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Preparation exceeded its bounded worker deadline.", //$NON-NLS-1$
            result.get("unreachableReason").getAsString()); //$NON-NLS-1$
        assertTrue(result.get("error").getAsString().contains( //$NON-NLS-1$
            "No terminate command was launched")); //$NON-NLS-1$
        assertTrue(result.get("error").getAsString().contains( //$NON-NLS-1$
            "this call did not remove a session")); //$NON-NLS-1$
        assertFalse(result.get("error").getAsString().contains("may have been removed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.has("verification")); //$NON-NLS-1$
        assertFalse(result.has("verificationReason")); //$NON-NLS-1$
    }

    @Test
    public void failedFirstTerminationReportsUnknownOutcomeWithoutClaimingACompletion()
    {
        JsonObject result = JsonParser.parseString(
            InfobaseSessionsTool.firstTerminationFailureResult("Demo", //$NON-NLS-1$
                "ServerApplication.Demo", //$NON-NLS-1$
                TerminationResult.outcomeUnknown("Command timed out."))) //$NON-NLS-1$
            .getAsJsonObject();

        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.get("mutationOutcomeUnknown").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.get("reachable").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Command timed out.", result.get("unreachableReason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("not_verifiable", result.get("verification").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.get("verificationReason").getAsString().contains( //$NON-NLS-1$
            "unknown whether the targeted session was removed")); //$NON-NLS-1$
        assertTrue(result.get("error").getAsString().contains( //$NON-NLS-1$
            "unknown whether the targeted session was removed")); //$NON-NLS-1$
        assertTrue(result.get("error").getAsString().contains( //$NON-NLS-1$
            "infobase_sessions(action='list', projectName='Demo', " //$NON-NLS-1$
                + "applicationId='ServerApplication.Demo')")); //$NON-NLS-1$
        assertFalse(result.has("attemptedCount")); //$NON-NLS-1$
        assertFalse(result.has("mutationCommitted")); //$NON-NLS-1$
        assertFalse(result.has("terminatedCount")); //$NON-NLS-1$
        assertFalse(result.has("sessions")); //$NON-NLS-1$
    }

    @Test
    public void readBackWithNoDisappearanceDoesNotClaimMutationCommitted()
    {
        JsonObject result = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.readable(List.of(CLIENT)))).getAsJsonObject();
        JsonObject expected = JsonParser.parseString("{" //$NON-NLS-1$
            + "\"success\":false," //$NON-NLS-1$
            + "\"error\":\"ibcmd accepted every termination, but 1 of 1 session(s) are still " //$NON-NLS-1$
            + "present after a terminate command that reported success: " + CLIENT.sessionId()
            + " (session-id 42). Non-Designer sessions in that list still block a database " //$NON-NLS-1$
            + "update. Run infobase_sessions(action='list', projectName='Demo', " //$NON-NLS-1$
            + "applicationId='ServerApplication.Demo') to see who holds them.\"," //$NON-NLS-1$
            + "\"action\":\"terminate\",\"project\":\"Demo\"," //$NON-NLS-1$
            + "\"applicationId\":\"ServerApplication.Demo\",\"reachable\":true," //$NON-NLS-1$
            + "\"sessions\":[],\"terminatedCount\":0,\"verification\":\"mismatched\"," //$NON-NLS-1$
            + "\"verificationReason\":\"The session list still reports them after a terminate " //$NON-NLS-1$
            + "command that reported success.\"}") //$NON-NLS-1$
            .getAsJsonObject();

        assertEquals(expected, result);
        assertFalse(result.has("mutationCommitted")); //$NON-NLS-1$
    }

    @Test
    public void survivingDesignerSessionIsReportedNeutrally()
    {
        JsonObject result = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", List.of(DESIGNER), //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.readable(List.of(DESIGNER)))).getAsJsonObject();
        JsonObject expected = JsonParser.parseString("{" //$NON-NLS-1$
            + "\"success\":false," //$NON-NLS-1$
            + "\"error\":\"ibcmd accepted every termination, but 1 of 1 Designer session(s) " //$NON-NLS-1$
            + "are still present after a terminate command that reported success: " //$NON-NLS-1$
            + DESIGNER.sessionId()
            + " (session-id 1). Designer sessions are not treated as blockers by update_database. " //$NON-NLS-1$
            + "Run infobase_sessions(action='list', projectName='Demo', " //$NON-NLS-1$
            + "applicationId='ServerApplication.Demo') to see who holds them.\"," //$NON-NLS-1$
            + "\"action\":\"terminate\",\"project\":\"Demo\"," //$NON-NLS-1$
            + "\"applicationId\":\"ServerApplication.Demo\",\"reachable\":true," //$NON-NLS-1$
            + "\"sessions\":[],\"terminatedCount\":0,\"verification\":\"mismatched\"," //$NON-NLS-1$
            + "\"verificationReason\":\"The session list still reports them after a terminate " //$NON-NLS-1$
            + "command that reported success.\"}") //$NON-NLS-1$
            .getAsJsonObject();

        assertEquals(expected, result);
        assertFalse(result.get("error").getAsString().contains("still block")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void mixedTerminationErrorContainsOnlyNonPersonalSessionFields()
    {
        JsonObject result = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", List.of(DESIGNER, CLIENT), //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.readable(List.of(CLIENT)))).getAsJsonObject();
        JsonObject expected = JsonParser.parseString("{" //$NON-NLS-1$
            + "\"success\":false," //$NON-NLS-1$
            + "\"error\":\"ibcmd accepted every termination, but 1 of 2 session(s) are still " //$NON-NLS-1$
            + "present after a terminate command that reported success: " + CLIENT.sessionId()
            + " (session-id 42). Non-Designer sessions in that list still block a database " //$NON-NLS-1$
            + "update. Run infobase_sessions(action='list', projectName='Demo', " //$NON-NLS-1$
            + "applicationId='ServerApplication.Demo') to see who holds them.\"," //$NON-NLS-1$
            + "\"mutationCommitted\":true," //$NON-NLS-1$
            + "\"action\":\"terminate\",\"project\":\"Demo\"," //$NON-NLS-1$
            + "\"applicationId\":\"ServerApplication.Demo\",\"reachable\":true," //$NON-NLS-1$
            + "\"sessions\":[{\"sessionId\":\"" + DESIGNER.sessionId() + "\"," //$NON-NLS-1$ //$NON-NLS-2$
            + "\"sessionNumber\":1,\"applicationKind\":\"Designer\"," //$NON-NLS-1$
            + "\"startedAt\":\"2026-01-01T10:00:00\"," //$NON-NLS-1$
            + "\"lastActiveAt\":\"2026-01-01T10:01:00\"}]," //$NON-NLS-1$
            + "\"terminatedCount\":1,\"verification\":\"mismatched\"," //$NON-NLS-1$
            + "\"verificationReason\":\"The session list still reports them after a terminate " //$NON-NLS-1$
            + "command that reported success.\"}") //$NON-NLS-1$
            .getAsJsonObject();

        assertEquals(expected, result);
        JsonObject session = result.getAsJsonArray("sessions").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertTrue(session.has("sessionId")); //$NON-NLS-1$
        assertTrue(session.has("sessionNumber")); //$NON-NLS-1$
        assertTrue(session.has("applicationKind")); //$NON-NLS-1$
        assertTrue(session.has("startedAt")); //$NON-NLS-1$
        assertTrue(session.has("lastActiveAt")); //$NON-NLS-1$
        assertFalse(session.has("userName")); //$NON-NLS-1$
        assertFalse(session.has("host")); //$NON-NLS-1$
    }

    @Test
    public void successfulSessionPayloadKeepsPersonalFieldsForTheWireRedactor()
    {
        JsonObject session = JsonParser.parseString(
            com.ditrix.edt.mcp.server.protocol.ToolResult.toJsonStatic(
                InfobaseSessionsTool.sessionMaps(List.of(CLIENT))))
            .getAsJsonArray().get(0).getAsJsonObject();

        assertEquals("User", session.get("userName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("desk", session.get("host").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(session.get("applicationKindIsDesigner").getAsBoolean()); //$NON-NLS-1$
        assertFalse(session.has("isEdtAgent")); //$NON-NLS-1$
    }

    @Test
    public void successfulSessionPayloadDoesNotApplyErrorFieldValidation()
    {
        SessionInfo observed = new SessionInfo(
            "Ivanov", 42L, "Ivanov Ivanovich <ivanov@corp>", "User", "desk", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "started yesterday", "active whenever", false, "+42"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject session = JsonParser.parseString(
            com.ditrix.edt.mcp.server.protocol.ToolResult.toJsonStatic(
                InfobaseSessionsTool.sessionMaps(List.of(observed))))
            .getAsJsonArray().get(0).getAsJsonObject();

        assertEquals("Ivanov", session.get("sessionId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(42, session.get("sessionNumber").getAsInt()); //$NON-NLS-1$
        assertEquals("Ivanov Ivanovich <ivanov@corp>", //$NON-NLS-1$
            session.get("applicationKind").getAsString()); //$NON-NLS-1$
        assertEquals("started yesterday", session.get("startedAt").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("active whenever", session.get("lastActiveAt").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("User", session.get("userName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("desk", session.get("host").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(session.has("applicationKindIsDesigner")); //$NON-NLS-1$
        assertFalse(session.has("isEdtAgent")); //$NON-NLS-1$
    }

    @Test
    public void verifiedTerminationStillReportsObservedSessionsAndNoAttemptedCount()
    {
        JsonObject result = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.readable(List.of()))).getAsJsonObject();

        assertEquals("verified", result.get("verification").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, result.get("terminatedCount").getAsInt()); //$NON-NLS-1$
        assertEquals(1, result.getAsJsonArray("sessions").size()); //$NON-NLS-1$
        assertFalse(result.has("attemptedCount")); //$NON-NLS-1$
    }

    /**
     * ProcessBuilder.start() refuses a NUL-bearing argument before it creates a process, so a
     * message carrying one can only fail - and it would fail late, as an unknown mutation
     * outcome. The refusal belongs on the way in, where the answer is still definitive.
     */
    @Test
    public void aMessageWithANulCharacterIsRefusedBeforeAnythingIsAttempted()
    {
        String refusal = InfobaseSessionsTool.validate("terminate", //$NON-NLS-1$
            "11111111-1111-1111-1111-111111111111", false, true, "bye\u0000there"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull("a NUL-bearing message must be refused, not attempted", refusal); //$NON-NLS-1$
        assertTrue("the refusal must name the character and the fix: " + refusal, //$NON-NLS-1$
            refusal.contains("U+0000") && refusal.contains("Remove it")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anOrdinaryMessageIsStillAccepted()
    {
        assertNull(InfobaseSessionsTool.validate("terminate", //$NON-NLS-1$
            "11111111-1111-1111-1111-111111111111", false, true, //$NON-NLS-1$
            "Maintenance window, please reconnect in 5 minutes")); //$NON-NLS-1$
    }
    /**
     * Each terminate command is bounded on its own, but all=true lets the caller choose how
     * many run. Without an aggregate budget the call lasts as long as the session count
     * demands, which is the one thing the bounded worker exists to prevent.
     */
    @Test
    public void theBulkLoopStopsOnceItsAggregateBudgetIsSpent()
    {
        long spent = TimeUnit.SECONDS.toNanos(61);
        long fresh = TimeUnit.SECONDS.toNanos(1);

        assertTrue("an attempt within the budget must run", //$NON-NLS-1$
            InfobaseSessionsTool.bulkBudgetAllowsAnotherAttempt(1, fresh));
        assertFalse("the loop must stop once the budget is spent", //$NON-NLS-1$
            InfobaseSessionsTool.bulkBudgetAllowsAnotherAttempt(1, spent));
    }

    /**
     * The budget must never turn a legitimate request into a silent no-op: the first attempt
     * always runs, and the per-command bound already caps how long that one can take.
     */
    @Test
    public void theFirstBulkAttemptRunsEvenWithTheBudgetAlreadySpent()
    {
        assertTrue(InfobaseSessionsTool.bulkBudgetAllowsAnotherAttempt(0,
            TimeUnit.HOURS.toNanos(1)));
    }



    /**
     * ibcmd exits 0 for a session that had already disconnected, so a re-read establishes the
     * state but never who ended it. The wording must not claim authorship the tool cannot have.
     */
    @Test
    public void aVerifiedTerminationReportsTheStateWithoutClaimingAuthorship()
    {
        JsonObject result = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.readable(List.of()))).getAsJsonObject();

        String message = result.get("message").getAsString(); //$NON-NLS-1$
        assertTrue(message.contains("are gone, confirmed by re-reading the session list")); //$NON-NLS-1$
        assertFalse("absence does not establish who ended the session: " + message, //$NON-NLS-1$
            message.contains("Terminated ")); //$NON-NLS-1$
    }




    /**
     * all=true asks for a clear infobase, so the verdict is what the authoritative re-read
     * showed. A session that joined while the loop ran blocks an update exactly like one the
     * budget never reached, and answering from the pre-loop selection would miss it entirely.
     */
    @Test
    public void aSessionThatJoinedDuringTheLoopStillBlocksTheBulkVerdict()
    {
        JsonObject result = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.readable(List.of(SECOND_CLIENT)), List.of(), true)).getAsJsonObject();

        assertFalse("a newcomer still holds the infobase", //$NON-NLS-1$
            result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, result.get("outstandingCount").getAsInt()); //$NON-NLS-1$
        assertEquals(SECOND_CLIENT.sessionId(),
            result.getAsJsonArray("outstandingSessionIds").get(0).getAsString()); //$NON-NLS-1$
    }

    /**
     * The mirror case: a session the budget never reached left on its own, so nothing is
     * outstanding and the call is not a partial run at all.
     */
    @Test
    public void anUnreachedTailThatLeftOnItsOwnDoesNotMakeTheCallPartial()
    {
        JsonObject result = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.readable(List.of()), List.of(SECOND_CLIENT), true)).getAsJsonObject();

        assertTrue(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, result.get("terminatedCount").getAsInt()); //$NON-NLS-1$
        assertFalse(result.has("outstandingSessionIds")); //$NON-NLS-1$
    }

    /** A surviving agent session is not outstanding work: bulk never targets one. */
    @Test
    public void aSurvivingAgentSessionDoesNotFailTheBulkVerdict()
    {
        JsonObject result = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.readable(List.of(DESIGNER)), List.of(), true)).getAsJsonObject();

        assertTrue("bulk never targets the agent, so it cannot be outstanding", //$NON-NLS-1$
            result.get("success").getAsBoolean()); //$NON-NLS-1$
    }

    /**
     * An unreadable list settles nothing, so the verdict falls back to what the loop knows it
     * skipped rather than to a read that did not happen.
     */
    @Test
    public void anUnreadableListFallsBackToWhatTheLoopKnowsItSkipped()
    {
        JsonObject result = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.unreachable("server stopped before verification"), //$NON-NLS-1$
            List.of(DESIGNER, SECOND_CLIENT), true)).getAsJsonObject();

        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.get("mutationOutcomeUnknown").getAsBoolean()); //$NON-NLS-1$
        assertEquals(2, result.get("outstandingCount").getAsInt()); //$NON-NLS-1$
        assertEquals(2, result.getAsJsonArray("outstandingSessionIds").size()); //$NON-NLS-1$
        assertFalse("an unread list cannot count terminations", //$NON-NLS-1$
            result.has("terminatedCount")); //$NON-NLS-1$
    }

    /**
     * A by-id terminate is not a request to clear the infobase, so a stranger being connected
     * is none of its business - only the session it targeted decides its verdict.
     */
    @Test
    public void aByIdTerminateIgnoresSessionsItWasNeverAskedToTouch()
    {
        JsonObject result = JsonParser.parseString(InfobaseSessionsTool.terminationReadBackResult(
            "Demo", "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$ //$NON-NLS-2$
            ReadResult.readable(List.of(SECOND_CLIENT)))).getAsJsonObject();

        assertTrue(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, result.get("terminatedCount").getAsInt()); //$NON-NLS-1$
    }

    /**
     * The continuation must be by id: a session that survives a terminate keeps its place in
     * the list, so re-running all=true could spend the next budget on it and never reach the
     * rest. The result must not advise the loop that cannot finish.
     */
    @Test
    public void anIncompleteBulkHandsBackIdsRatherThanALoopThatCannotFinish()
    {
        JsonObject result = JsonParser.parseString(
            InfobaseSessionsTool.bulkTerminationIncompleteResult("Demo", //$NON-NLS-1$
                "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$
                ReadResult.readable(List.of(CLIENT, SECOND_CLIENT)), List.of(SECOND_CLIENT), 2))
            .getAsJsonObject();

        JsonArray ids = result.getAsJsonArray("outstandingSessionIds"); //$NON-NLS-1$
        assertEquals(result.get("outstandingCount").getAsInt(), ids.size()); //$NON-NLS-1$
        assertEquals(SECOND_CLIENT.sessionId(), ids.get(0).getAsString());
        String error = result.get("error").getAsString(); //$NON-NLS-1$
        assertTrue("the budget note must survive: " + error, //$NON-NLS-1$
            error.contains("stopped 2 selection(s) short")); //$NON-NLS-1$
        assertFalse("advising all=true here would never finish: " + error, //$NON-NLS-1$
            error.contains("all=true, confirm=true) to continue")); //$NON-NLS-1$
    }

    /**
     * Nothing was observed gone, so no mutation may be claimed - the same evidence rule the
     * ordinary read-back follows.
     */
    @Test
    public void anIncompleteBulkThatChangedNothingDoesNotClaimAMutation()
    {
        JsonObject result = JsonParser.parseString(
            InfobaseSessionsTool.bulkTerminationIncompleteResult("Demo", //$NON-NLS-1$
                "ServerApplication.Demo", List.of(CLIENT), //$NON-NLS-1$
                ReadResult.readable(List.of(CLIENT)), List.of(CLIENT), 0))
            .getAsJsonObject();

        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(0, result.get("terminatedCount").getAsInt()); //$NON-NLS-1$
        assertFalse("nothing was observed gone, so no mutation may be claimed", //$NON-NLS-1$
            result.has("mutationCommitted")); //$NON-NLS-1$
        assertEquals("mismatched", result.get("verification").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("no budget note belongs here: the loop reached everything", //$NON-NLS-1$
            result.get("error").getAsString().contains("short to stay bounded")); //$NON-NLS-1$
    }

    // ==================== #622: the seconds-budget tool refuses, it never hangs ================

    @Test
    public void testTheApplicationLookupDeadlineIsSecondsNotTheSharedDefault()
    {
        // The whole point of a separate constant here: this tool advertises a bounded answer, so
        // it must not inherit the longer shared lookup budget.
        assertTrue("infobase_sessions must bound its lookup in seconds", //$NON-NLS-1$
            InfobaseSessionsTool.APPLICATION_LOOKUP_TIMEOUT_MS <= 15_000L);
    }

    @Test
    public void testAWedgedApplicationLookupRefusesWithoutTouchingSessions() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(manager.getApplication(project, "Infobase.Wedged")).thenAnswer(inv -> { //$NON-NLS-1$
            try
            {
                release.await(30, TimeUnit.SECONDS);
                return Optional.empty();
            }
            finally
            {
                finished.countDown();
            }
        });

        try
        {
            ResolvedApplication resolved = InfobaseSessionsTool.resolveApplication(manager,
                project, "Proj", "Infobase.Wedged", 250L); //$NON-NLS-1$ //$NON-NLS-2$

            assertNotNull("a wedged lookup must be refused", resolved.errorJson); //$NON-NLS-1$
            JsonObject json = JsonParser.parseString(resolved.errorJson).getAsJsonObject();
            assertFalse(json.get("success").getAsBoolean()); //$NON-NLS-1$
            String error = json.get("error").getAsString(); //$NON-NLS-1$
            assertTrue("the refusal must carry the deadline diagnosis", //$NON-NLS-1$
                error.contains("the EDT application lookup for application 'Infobase.Wedged'")); //$NON-NLS-1$
            assertTrue("the refusal must state that no session was touched", //$NON-NLS-1$
                error.contains("No session was listed or terminated")); //$NON-NLS-1$
            assertFalse("an unread lookup must not be reported as a measured not-found", //$NON-NLS-1$
                error.contains("Application not found")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAWedgedDefaultLookupIsNotReportedAsNoDefaultApplication() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(manager.getDefaultApplication(project)).thenAnswer(inv -> {
            try
            {
                release.await(30, TimeUnit.SECONDS);
                return Optional.empty();
            }
            finally
            {
                finished.countDown();
            }
        });

        try
        {
            ResolvedApplication resolved = InfobaseSessionsTool.resolveApplication(manager,
                project, "Proj", null, 250L); //$NON-NLS-1$

            assertNotNull(resolved.errorJson);
            String error = JsonParser.parseString(resolved.errorJson).getAsJsonObject()
                .get("error").getAsString(); //$NON-NLS-1$
            assertFalse("a wedged default lookup must not claim the project has no default", //$NON-NLS-1$
                error.contains("has no default application")); //$NON-NLS-1$
            assertTrue("it must name the default-application lookup and its deadline", //$NON-NLS-1$
                error.contains("the EDT default-application lookup for project")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAConcludedEmptyDefaultLookupStillSaysThereIsNoDefault() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(manager.getDefaultApplication(project)).thenReturn(Optional.empty());

        ResolvedApplication resolved = InfobaseSessionsTool.resolveApplication(manager, project,
            "Proj", null, 60_000L); //$NON-NLS-1$

        assertNotNull(resolved.errorJson);
        String error = JsonParser.parseString(resolved.errorJson).getAsJsonObject()
            .get("error").getAsString(); //$NON-NLS-1$
        assertTrue("a measured absence must keep its own wording", //$NON-NLS-1$
            error.contains("Project 'Proj' has no default application.")); //$NON-NLS-1$
        assertFalse("a measured absence must not be dressed up as a deadline", //$NON-NLS-1$
            error.contains("did not finish within")); //$NON-NLS-1$
    }

    @Test
    public void testAResolvedApplicationIsHandedBackUnchanged() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        IApplication application = mock(IApplication.class);
        when(manager.getApplication(project, "Infobase.Ok")).thenReturn(Optional.of(application)); //$NON-NLS-1$

        ResolvedApplication resolved = InfobaseSessionsTool.resolveApplication(manager, project,
            "Proj", "Infobase.Ok", 60_000L); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("a healthy resolution must not produce an error", resolved.errorJson); //$NON-NLS-1$
        assertEquals(application, resolved.application);
    }

    /**
     * #622/L: the DEFAULT-application route is the one with a cheap way round the wedge — passing
     * an applicationId skips that lookup entirely — and its sibling {@code create_launch_config}
     * already names it. An explicit id has no such alternative, so it must not be offered one.
     */
    @Test
    public void testOnlyTheDefaultRouteNamesTheApplicationIdRecovery() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Proj"); //$NON-NLS-1$
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        when(manager.getDefaultApplication(project)).thenAnswer(invocation -> {
            try
            {
                release.await(30, TimeUnit.SECONDS);
                return Optional.empty();
            }
            finally
            {
                finished.countDown();
            }
        });
        when(manager.getApplication(project, "Infobase.Wedged")).thenAnswer(invocation -> { //$NON-NLS-1$
            try
            {
                release.await(30, TimeUnit.SECONDS);
                return Optional.empty();
            }
            finally
            {
                finished.countDown();
            }
        });

        try
        {
            String byDefault = errorOf(InfobaseSessionsTool.resolveApplication(manager, project,
                "Proj", null, 250L)); //$NON-NLS-1$
            String byId = errorOf(InfobaseSessionsTool.resolveApplication(manager, project,
                "Proj", "Infobase.Wedged", 250L)); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue("the default route must name the cheap recovery", //$NON-NLS-1$
                byDefault.contains("Pass applicationId explicitly")); //$NON-NLS-1$
            assertTrue("and still say nothing was touched", //$NON-NLS-1$
                byDefault.contains("No session was listed or terminated")); //$NON-NLS-1$
            assertFalse("an explicit id has no such alternative to offer", //$NON-NLS-1$
                byId.contains("Pass applicationId explicitly")); //$NON-NLS-1$
            assertTrue("it still names the retry", //$NON-NLS-1$
                byId.contains("Retry once EDT is responsive")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    private static String errorOf(ResolvedApplication resolved)
    {
        assertNotNull("the resolution must have refused", resolved.errorJson); //$NON-NLS-1$
        return JsonParser.parseString(resolved.errorJson).getAsJsonObject()
            .get("error").getAsString(); //$NON-NLS-1$
    }
}
