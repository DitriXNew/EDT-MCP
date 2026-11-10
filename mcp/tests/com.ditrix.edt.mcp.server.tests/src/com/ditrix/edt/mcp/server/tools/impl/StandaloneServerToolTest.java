/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.ReadResult;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.SessionInfo;
import com.ditrix.edt.mcp.server.utils.StandaloneServerPortConflictPolicy;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecoveryTest.ServerBackedApplication;
import com.ditrix.edt.mcp.server.utils.StandaloneServerSupport;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.IApplicationType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Contract and behaviour tests for {@link StandaloneServerTool}: the surface, the validation that
 * runs before any platform access, the type refusal, the preview that stops nothing, the
 * process-exit verification after a stop, and the hold a stop leaves for later starts.
 */
public class StandaloneServerToolTest
{
    private static final String APPLICATION_ID = "ServerApplication.Test"; //$NON-NLS-1$

    private final StandaloneServerTool tool = new StandaloneServerTool();
    private final List<IProject> heldProjects = new ArrayList<>();
    private final long originalExitTimeout = StandaloneServerTool.processExitTimeoutMs;

    @After
    public void tearDown()
    {
        StandaloneServerTool.processExitTimeoutMs = originalExitTimeout;
        for (IProject project : heldProjects)
        {
            StandaloneServerStateRecovery.releaseHold(project, APPLICATION_ID);
        }
    }

    // ==================== surface ====================

    @Test
    public void testSurfaceDeclaresADestructiveConsentGatedJsonTool()
    {
        assertEquals("standalone_server", StandaloneServerTool.NAME); //$NON-NLS-1$
        assertEquals(StandaloneServerTool.NAME, tool.getName());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertTrue(tool.returnsInfobaseData());
        String description = tool.getDescription();
        assertTrue(description, description.contains("get_tool_guide('standalone_server')")); //$NON-NLS-1$
        assertTrue("the preview-then-confirm protocol must stay in the description", //$NON-NLS-1$
            description.contains("WITHOUT confirm") && description.contains("confirm=true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(description, description.contains("DESTRUCTIVE")); //$NON-NLS-1$
        assertTrue(ToolAnnotationClassifier.classify(StandaloneServerTool.NAME).getDestructiveHint());
        assertTrue(DestructiveConsentGate.GATED_TOOLS.contains(StandaloneServerTool.NAME));
    }

    @Test
    public void testInputSchemaHasExactlyTheFiveParameters()
    {
        JsonObject schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        Set<String> keys = new TreeSet<>(schema.getAsJsonObject("properties").keySet()); //$NON-NLS-1$
        assertEquals(new TreeSet<>(Set.of("projectName", "applicationId", "action", "confirm", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "standaloneServerPortConflict")), keys); //$NON-NLS-1$
        JsonArray required = schema.getAsJsonArray("required"); //$NON-NLS-1$
        assertEquals(1, required.size());
        assertEquals("projectName", required.get(0).getAsString()); //$NON-NLS-1$
        JsonArray actions = schema.getAsJsonObject("properties").getAsJsonObject("action") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonArray("enum"); //$NON-NLS-1$
        List<String> values = new ArrayList<>();
        actions.forEach(value -> values.add(value.getAsString()));
        assertEquals(List.of("status", "stop", "start", "restart"), values); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testOutputSchemaDeclaresTheReportedFields()
    {
        JsonObject properties = JsonParser.parseString(tool.getOutputSchema()).getAsJsonObject()
            .getAsJsonObject("properties"); //$NON-NLS-1$
        for (String key : List.of("success", "action", "state", "liveLaunch", "processAlive", "pid", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "heldStopped", "databaseDirectory", "infobaseUrl", "sessions", "clientLaunches", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "debugTargets", "confirmationRequired", "alreadyStopped", "alreadyRunning", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "portsReassigned", "mutationCommitted", "mutationOutcomeUnknown")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue(key, properties.has(key));
        }
        assertFalse("an unprovable lock claim must not be advertised", properties.has("infobaseLocked")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== validation (before any platform access) ====================

    @Test
    public void testStopAndRestartWithoutConfirmAreThePreviewNotAnError()
    {
        assertNull(StandaloneServerTool.validate("stop", false, null)); //$NON-NLS-1$
        assertNull(StandaloneServerTool.validate("restart", false, null)); //$NON-NLS-1$
        assertNull(StandaloneServerTool.validate("stop", true, null)); //$NON-NLS-1$
        assertNull(StandaloneServerTool.validate("status", false, null)); //$NON-NLS-1$
        assertNull(StandaloneServerTool.validate("start", false, "reassign")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testValidationRefusesWhatCannotApply()
    {
        assertEquals("confirm applies only to action='stop' and action='restart'.", //$NON-NLS-1$
            StandaloneServerTool.validate("status", true, null)); //$NON-NLS-1$
        assertEquals("confirm applies only to action='stop' and action='restart'.", //$NON-NLS-1$
            StandaloneServerTool.validate("start", true, null)); //$NON-NLS-1$
        assertEquals("Unknown action: 'kill'. Allowed values: status, stop, start, restart.", //$NON-NLS-1$
            StandaloneServerTool.validate("kill", false, null)); //$NON-NLS-1$
        String badPolicy = StandaloneServerTool.validate("start", false, "ignore"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(badPolicy, badPolicy.contains("'ignore'") && badPolicy.contains("cancel, reassign")); //$NON-NLS-1$ //$NON-NLS-2$
        String policyOnStop = StandaloneServerTool.validate("stop", false, "cancel"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(policyOnStop, policyOnStop.contains("only to action='start'")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteRefusesBeforeTouchingThePlatform()
    {
        assertError(tool.execute(Map.of()), "projectName"); //$NON-NLS-1$
        Map<String, String> params = new HashMap<>(Map.of("projectName", "P", "action", "kill")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertError(tool.execute(params), "Allowed values: status, stop, start, restart"); //$NON-NLS-1$
        params = new HashMap<>(Map.of("projectName", "P", "action", "start", "confirm", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertError(tool.execute(params), "confirm applies only"); //$NON-NLS-1$
    }

    @Test
    public void testANonServerApplicationIsRefusedWithItsType()
    {
        IApplication application = Mockito.mock(IApplication.class);
        IApplicationType type = Mockito.mock(IApplicationType.class);
        Mockito.when(type.getId()).thenReturn("com.e1c.g5.dt.applications.type.file-infobase"); //$NON-NLS-1$
        Mockito.when(application.getType()).thenReturn(type);
        Mockito.when(application.getId()).thenReturn("InfobaseApplication.Test"); //$NON-NLS-1$
        String refusal = StandaloneServerTool.typeRefusal("P", application); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("InfobaseApplication.Test")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("file-infobase")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains(StandaloneServerSupport.WST_SERVER_APP_TYPE));
        assertTrue(refusal, refusal.contains("get_applications")); //$NON-NLS-1$
    }

    // ==================== stop ====================

    @Test
    public void testThePreviewStopsNothingAndNamesWhatWouldEnd()
    {
        Fixture fixture = new Fixture("StandaloneServerPreviewProject", 2, true); //$NON-NLS-1$
        String result = StandaloneServerTool.run(request("stop", false), fixture.manager, //$NON-NLS-1$
            fixture.project, fixture.application, fixture.env);

        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        assertTrue(result, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("preview", json.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("confirmationRequired").getAsBoolean()); //$NON-NLS-1$
        assertEquals(2, json.getAsJsonObject("sessions").get("count").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, json.getAsJsonObject("sessions").get("designerCount").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, json.get("clientLaunches").getAsInt()); //$NON-NLS-1$
        assertEquals(1, json.get("debugTargets").getAsInt()); //$NON-NLS-1$
        assertFalse("a preview holds nothing", json.get("heldStopped").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("session rows in the preview carry no user names", result.contains("alice")); //$NON-NLS-1$ //$NON-NLS-2$
        Mockito.verify(fixture.manager, Mockito.never()).cleanup(Mockito.any(), Mockito.any(),
            Mockito.any());
        assertEquals("a preview asks nobody for consent", 0, fixture.env.consents.get()); //$NON-NLS-1$
        assertEquals(2, fixture.server.getServerState());
    }

    @Test
    public void testAStopWhoseProcessOutlivesItIsReportedAsAFailureWithThePid() throws Exception
    {
        StandaloneServerTool.processExitTimeoutMs = 300L;
        Fixture fixture = new Fixture("StandaloneServerLingeringProject", 2, true); //$NON-NLS-1$
        fixture.env.alive = true;
        String result = StandaloneServerTool.run(request("stop", true), fixture.manager, //$NON-NLS-1$
            fixture.project, fixture.application, fixture.env);

        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        assertFalse(result, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json.get("processAlive").getAsBoolean()); //$NON-NLS-1$
        assertEquals(4242L, json.get("pid").getAsLong()); //$NON-NLS-1$
        assertTrue(result, json.get("error").getAsString().contains("pid 4242")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, json.get("error").getAsString().contains("Do not kill it with OS tools")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
        assertTrue("a lingering process keeps the server held", json.get("heldStopped").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, fixture.cleanups.get());
    }

    @Test
    public void testAConfirmedStopVerifiesTheExitAndHoldsTheServerUntilStart()
    {
        Fixture fixture = new Fixture("StandaloneServerStopStartProject", 2, true); //$NON-NLS-1$
        String stopped = StandaloneServerTool.run(request("stop", true), fixture.manager, //$NON-NLS-1$
            fixture.project, fixture.application, fixture.env);

        JsonObject json = JsonParser.parseString(stopped).getAsJsonObject();
        assertTrue(stopped, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("stop", json.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(json.get("processAlive").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json.get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json.get("heldStopped").getAsBoolean()); //$NON-NLS-1$
        assertEquals("STOPPED", json.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, fixture.env.consents.get());
        try
        {
            StandaloneServerStateRecovery.ensureStartable(fixture.project, fixture.application,
                APPLICATION_ID, fixture.manager);
            throw new AssertionError("a held server must refuse update_database/launch starts"); //$NON-NLS-1$
        }
        catch (ApplicationException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("standalone_server(action='start'")); //$NON-NLS-1$
        }

        String started = StandaloneServerTool.run(request("start", false), fixture.manager, //$NON-NLS-1$
            fixture.project, fixture.application, fixture.env);
        JsonObject start = JsonParser.parseString(started).getAsJsonObject();
        assertTrue(started, start.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(start.get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
        assertFalse(start.get("heldStopped").getAsBoolean()); //$NON-NLS-1$
        assertEquals("STARTED", start.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        StandaloneServerStateRecovery.ensureStartable(fixture.project, fixture.application,
            APPLICATION_ID, fixture.manager);
    }

    @Test
    public void testAnAlreadyStoppedServerIsHeldWithoutConsentOrCleanup()
    {
        Fixture fixture = new Fixture("StandaloneServerAlreadyStoppedProject", 4, false); //$NON-NLS-1$
        String result = StandaloneServerTool.run(request("stop", true), fixture.manager, //$NON-NLS-1$
            fixture.project, fixture.application, fixture.env);

        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        assertTrue(result, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json.get("alreadyStopped").getAsBoolean()); //$NON-NLS-1$
        assertTrue(json.get("heldStopped").getAsBoolean()); //$NON-NLS-1$
        assertEquals(0, fixture.cleanups.get());
        assertEquals(0, fixture.env.consents.get());
    }

    @Test
    public void testADeniedConsentStopsNothing()
    {
        Fixture fixture = new Fixture("StandaloneServerDeniedProject", 2, true); //$NON-NLS-1$
        fixture.env.decision = ConsentDecision.REJECT;
        String result = StandaloneServerTool.run(request("stop", true), fixture.manager, //$NON-NLS-1$
            fixture.project, fixture.application, fixture.env);
        assertTrue(result, ToolResult.isErrorPayload(result));
        assertEquals(0, fixture.cleanups.get());
        assertNull(StandaloneServerStateRecovery.heldSince(fixture.project, APPLICATION_ID));
    }

    @Test
    public void testStatusReleasesAHoldOnAServerStartedOutsideTheTool()
    {
        Fixture fixture = new Fixture("StandaloneServerStatusReleaseProject", 2, true); //$NON-NLS-1$
        StandaloneServerStateRecovery.holdStopped(fixture.project, APPLICATION_ID);
        String result = StandaloneServerTool.run(request("status", false), fixture.manager, //$NON-NLS-1$
            fixture.project, fixture.application, fixture.env);

        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        assertTrue(result, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("STARTED", json.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("liveLaunch").getAsBoolean()); //$NON-NLS-1$
        assertEquals(4242L, json.get("pid").getAsLong()); //$NON-NLS-1$
        assertFalse(json.get("heldStopped").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result, json.get("message").getAsString().contains("hold released")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, fixture.cleanups.get());
    }

    @Test
    public void testStatusDuringAnInFlightStopKeepsTheHold() throws Exception
    {
        Fixture fixture = new Fixture("StandaloneServerInFlightStatusProject", 2, true); //$NON-NLS-1$
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            fixture.cleanups.incrementAndGet();
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            fixture.server.state = 4;
            fixture.server.launch = null;
            return null;
        }).when(fixture.manager).cleanup(Mockito.same(fixture.application),
            Mockito.any(ExecutionContext.class), Mockito.any(IProgressMonitor.class));
        AtomicReference<String> stopped = new AtomicReference<>();
        Thread stopper = new Thread(() -> stopped.set(StandaloneServerTool.run(request("stop", true), //$NON-NLS-1$
            fixture.manager, fixture.project, fixture.application, fixture.env)),
            "test: explicit stop in flight"); //$NON-NLS-1$
        stopper.setDaemon(true);
        stopper.start();
        try
        {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            // The server still reads STARTED under a live launch while EDT's cleanup runs - the
            // very state a start from EDT's Servers view leaves. The hold must not be released.
            String status = StandaloneServerTool.run(request("status", false), fixture.manager, //$NON-NLS-1$
                fixture.project, fixture.application, fixture.env);
            JsonObject json = JsonParser.parseString(status).getAsJsonObject();
            assertTrue(status, json.get("heldStopped").getAsBoolean()); //$NON-NLS-1$
            assertFalse(status, status.contains("hold released")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            stopper.join(30_000L);
        }
        assertTrue(stopped.get(), JsonParser.parseString(stopped.get()).getAsJsonObject()
            .get("success").getAsBoolean()); //$NON-NLS-1$
        assertNotNull("the concluded stop must leave the server held", //$NON-NLS-1$
            StandaloneServerStateRecovery.heldSince(fixture.project, APPLICATION_ID));
    }

    @Test
    public void testStatusOmitsProcessAliveWhenNoProcessIdWasRead()
    {
        Fixture fixture = new Fixture("StandaloneServerStaleStatusProject", 2, false); //$NON-NLS-1$
        String result = StandaloneServerTool.run(request("status", false), fixture.manager, //$NON-NLS-1$
            fixture.project, fixture.application, fixture.env);
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        assertTrue(result, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("STARTED", json.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a stale server may still run an ibsrv nobody can name", //$NON-NLS-1$
            json.has("processAlive")); //$NON-NLS-1$
        assertFalse(json.has("pid")); //$NON-NLS-1$
    }

    @Test
    public void testAStopEdtRanAndFailedDoesNotClaimNothingWasDispatched()
    {
        Fixture fixture = new Fixture("StandaloneServerDispatchedFailureProject", 2, true); //$NON-NLS-1$
        Mockito.doAnswer(invocation -> {
            fixture.cleanups.incrementAndGet();
            throw new ApplicationException("EDT refused to stop it"); //$NON-NLS-1$
        }).when(fixture.manager).cleanup(Mockito.same(fixture.application),
            Mockito.any(ExecutionContext.class), Mockito.any(IProgressMonitor.class));
        String result = StandaloneServerTool.run(request("stop", true), fixture.manager, //$NON-NLS-1$
            fixture.project, fixture.application, fixture.env);
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        assertFalse(result, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result, result.contains("Nothing was dispatched")); //$NON-NLS-1$
        assertTrue(result, result.contains("ran and failed")); //$NON-NLS-1$
        assertTrue(json.get("mutationOutcomeUnknown").getAsBoolean()); //$NON-NLS-1$
        assertFalse("still running, so nothing may stay held", json.get("heldStopped").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, fixture.cleanups.get());
    }

    @Test
    public void testAStopRefusedBeforeDispatchSaysSo()
    {
        Fixture fixture = new Fixture("StandaloneServerRefusedStopProject", 2, true); //$NON-NLS-1$
        StandaloneServerStateRecovery.StartClaim claim = StandaloneServerStateRecovery
            .claimStartWithinBound(fixture.project, APPLICATION_ID, (IProgressMonitor)null);
        assertNotNull(claim);
        try
        {
            String result = StandaloneServerTool.run(request("stop", true), fixture.manager, //$NON-NLS-1$
                fixture.project, fixture.application, fixture.env);
            assertTrue(result, ToolResult.isErrorPayload(result));
            assertTrue(result, result.contains("Nothing was dispatched to EDT")); //$NON-NLS-1$
            assertEquals(0, fixture.cleanups.get());
        }
        finally
        {
            claim.close();
        }
    }

    @Test
    public void testAServerFoundStoppedUnderTheGuardHasTheAlreadyStoppedShape()
    {
        Fixture fixture = new Fixture("StandaloneServerStoppedMeanwhileProject", 2, true); //$NON-NLS-1$
        fixture.env.onConsent = () -> {
            fixture.server.state = 4;
            fixture.server.launch = null;
        };
        String result = StandaloneServerTool.run(request("stop", true), fixture.manager, //$NON-NLS-1$
            fixture.project, fixture.application, fixture.env);
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        assertTrue(result, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result, json.get("alreadyStopped").getAsBoolean()); //$NON-NLS-1$
        assertFalse("nothing was stopped by this call", json.has("mutationCommitted")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("heldStopped").getAsBoolean()); //$NON-NLS-1$
        assertEquals(0, fixture.cleanups.get());
    }

    @Test
    public void testHoldingAnAlreadyStoppedServerRespectsAStartClaim()
    {
        Fixture fixture = new Fixture("StandaloneServerClaimedStoppedProject", 4, false); //$NON-NLS-1$
        StandaloneServerStateRecovery.StartClaim claim = StandaloneServerStateRecovery
            .claimStartWithinBound(fixture.project, APPLICATION_ID, (IProgressMonitor)null);
        assertNotNull(claim);
        try
        {
            String result = StandaloneServerTool.run(request("stop", true), fixture.manager, //$NON-NLS-1$
                fixture.project, fixture.application, fixture.env);
            assertTrue(result, ToolResult.isErrorPayload(result));
            assertTrue(result, result.contains("another standalone start currently claims")); //$NON-NLS-1$
            assertNull("a claimed server must not be held behind its start", //$NON-NLS-1$
                StandaloneServerStateRecovery.heldSince(fixture.project, APPLICATION_ID));
        }
        finally
        {
            claim.close();
        }
    }

    // ==================== helpers ====================

    private StandaloneServerTool.Request request(String action, boolean confirm)
    {
        return new StandaloneServerTool.Request("P", action, confirm, //$NON-NLS-1$
            StandaloneServerPortConflictPolicy.CANCEL);
    }

    private static void assertError(String result, String text)
    {
        assertTrue(result, ToolResult.isErrorPayload(result));
        assertTrue(result, result.contains(text));
    }

    private static ILaunch launch(boolean terminated)
    {
        return (ILaunch)Proxy.newProxyInstance(ILaunch.class.getClassLoader(),
            new Class<?>[] { ILaunch.class }, (proxy, method, args) -> {
                if ("isTerminated".equals(method.getName())) //$NON-NLS-1$
                {
                    return Boolean.valueOf(terminated);
                }
                if ("getProcesses".equals(method.getName())) //$NON-NLS-1$
                {
                    return null;
                }
                if ("hashCode".equals(method.getName())) //$NON-NLS-1$
                {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("equals".equals(method.getName())) //$NON-NLS-1$
                {
                    return Boolean.valueOf(proxy == args[0]);
                }
                return null;
            });
    }

    /** A wst-server application over a fake WST server whose cleanup stops it. */
    private final class Fixture
    {
        final IProject project = Mockito.mock(IProject.class);
        final IApplication application;
        final ToolFakeServer server;
        final IApplicationManager manager = Mockito.mock(IApplicationManager.class);
        final AtomicInteger cleanups = new AtomicInteger();
        final FakeEnvironment env;

        Fixture(String projectName, int state, boolean liveLaunch)
        {
            heldProjects.add(project);
            Mockito.when(project.getName()).thenReturn(projectName);
            server = new ToolFakeServer(state, liveLaunch ? launch(false) : null);
            IApplicationType type = Mockito.mock(IApplicationType.class);
            Mockito.when(type.getId()).thenReturn(StandaloneServerSupport.WST_SERVER_APP_TYPE);
            application = Mockito.mock(IApplication.class,
                Mockito.withSettings().extraInterfaces(ServerBackedApplication.class));
            Mockito.when(application.getType()).thenReturn(type);
            Mockito.when(application.getId()).thenReturn(APPLICATION_ID);
            Mockito.when(application.getName()).thenReturn("Test"); //$NON-NLS-1$
            Mockito.when(((ServerBackedApplication)application).getServer()).thenReturn(server);
            Mockito.doAnswer(invocation -> {
                cleanups.incrementAndGet();
                server.state = 4;
                server.launch = null;
                return null;
            }).when(manager).cleanup(Mockito.same(application), Mockito.any(ExecutionContext.class),
                Mockito.any(IProgressMonitor.class));
            env = new FakeEnvironment(server);
        }
    }

    /** A WST server as the tool reads it: state, launch and name. */
    public static final class ToolFakeServer
    {
        volatile int state;
        volatile Object launch;

        ToolFakeServer(int state, Object launch)
        {
            this.state = state;
            this.launch = launch;
        }

        public int getServerState()
        {
            return state;
        }

        public Object getLaunch()
        {
            return launch;
        }

        public String getName()
        {
            return "Test server"; //$NON-NLS-1$
        }
    }

    /** A standalone-server service whose start brings the fake server up under a live launch. */
    public static final class FakeService
    {
        private final ToolFakeServer server;

        FakeService(ToolFakeServer server)
        {
            this.server = server;
        }

        public IStatus startServer(Object passedServer, String launchMode, Object monitor)
        {
            server.state = 2;
            server.launch = launch(false);
            return Status.OK_STATUS;
        }
    }

    /** Scripted platform reads; counts the consent prompts. */
    private static final class FakeEnvironment implements StandaloneServerTool.Environment
    {
        private final ToolFakeServer server;
        final AtomicInteger consents = new AtomicInteger();
        volatile boolean alive;
        volatile ConsentDecision decision = ConsentDecision.ALLOW;
        volatile Runnable onConsent;

        FakeEnvironment(ToolFakeServer server)
        {
            this.server = server;
        }

        @Override
        public StandaloneServerTool.LiveProcess liveProcess(Object passedServer)
        {
            return server.launch == null ? null
                : new StandaloneServerTool.LiveProcess(4242L, () -> alive);
        }

        @Override
        public ReadResult listSessions(IApplication application)
        {
            return ReadResult.readable(List.of(
                new SessionInfo("11111111-1111-1111-1111-111111111111", 1L, "Designer", "agent", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "host", null, null, true), //$NON-NLS-1$
                new SessionInfo("22222222-2222-2222-2222-222222222222", 2L, "1CV8C", "alice", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "host", null, null, false))); //$NON-NLS-1$
        }

        @Override
        public int clientLaunches(String applicationId)
        {
            return 1;
        }

        @Override
        public int debugTargets(String applicationId)
        {
            return 1;
        }

        @Override
        public ConsentDecision consent(ConsentPreview preview)
        {
            consents.incrementAndGet();
            Runnable hook = onConsent;
            if (hook != null)
            {
                hook.run();
            }
            return decision;
        }

        @Override
        public Object service()
        {
            return new FakeService(server);
        }

        @Override
        public String databaseDirectory(IApplication application)
        {
            return "C:\\bases\\Test"; //$NON-NLS-1$
        }

        @Override
        public String infobaseUrl(IApplication application)
        {
            return null;
        }
    }
}
