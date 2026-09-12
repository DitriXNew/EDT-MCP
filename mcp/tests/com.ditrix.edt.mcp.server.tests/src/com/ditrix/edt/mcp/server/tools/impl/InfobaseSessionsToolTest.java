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

import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.ReadResult;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.SessionInfo;
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
        assertTrue(InfobaseSessionsTool.validate("terminate", "42", false, false) //$NON-NLS-1$ //$NON-NLS-2$
            .contains("confirm=true")); //$NON-NLS-1$
        assertTrue(InfobaseSessionsTool.validate("terminate", null, false, true) //$NON-NLS-1$
            .contains("exactly one")); //$NON-NLS-1$
        assertTrue(InfobaseSessionsTool.validate("terminate", "42", true, true) //$NON-NLS-1$ //$NON-NLS-2$
            .contains("exactly one")); //$NON-NLS-1$
        assertTrue(InfobaseSessionsTool.validate("terminate", "not-an-id", false, true) //$NON-NLS-1$ //$NON-NLS-2$
            .contains("full UUID")); //$NON-NLS-1$
        assertNull(InfobaseSessionsTool.validate("terminate", "42", false, true)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(InfobaseSessionsTool.validate("terminate", null, true, true)); //$NON-NLS-1$
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
    public void failedFirstTerminationReportsUnknownOutcomeWithoutClaimingACompletion()
    {
        JsonObject result = JsonParser.parseString(
            InfobaseSessionsTool.firstTerminationAttemptFailedResult("Demo", //$NON-NLS-1$
                "ServerApplication.Demo", "Command timed out.")) //$NON-NLS-1$ //$NON-NLS-2$
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
}
