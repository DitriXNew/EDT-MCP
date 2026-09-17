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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.GetApplicationsTool.ApplicationListDeadline;
import com.ditrix.edt.mcp.server.tools.impl.GetApplicationsTool.DefaultApplication;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link GetApplicationsTool}.
 * <p>
 * Covers tool metadata, the input and output schemas, and the pure
 * {@code projectName} required-argument validation that returns before the
 * first {@code ProjectStateChecker}/{@code ResourcesPlugin} access. The EDT
 * boundary is {@code execute()}'s call to
 * {@code ProjectStateChecker.buildingErrorOrNull(projectName)}, which touches
 * {@code ResourcesPlugin.getWorkspace()}; only the missing/blank-projectName
 * branch returns before that. Enumerating applications needs a live project
 * model and the {@code IApplicationManager} service and is covered by the E2E
 * suite.
 */
public class GetApplicationsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_applications", new GetApplicationsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetApplicationsTool.NAME, new GetApplicationsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetApplicationsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetApplicationsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testResponseTypeJsonConstantSurface()
    {
        // The tool overrides getResponseType() explicitly to JSON; pin it so a future
        // edit cannot silently drop to the MARKDOWN default of IMcpTool.
        assertEquals(ResponseType.JSON, new GetApplicationsTool().getResponseType());
    }

    @Test
    public void testConnectsToInfobaseIsTrue()
    {
        // #270: the update-state read-back (appManager.getUpdateState) is the async
        // recompute that historically raised the auth dialog (#230's history) — it must
        // arm the auth-dialog suppressor's activity window.
        assertTrue(new GetApplicationsTool().connectsToInfobase());
    }

    @Test
    public void testDescriptionMentionsConsumingTools()
    {
        // The description must steer callers to the tools that consume the application id.
        String desc = new GetApplicationsTool().getDescription();
        assertTrue("description must point at update_database", new GetApplicationsTool().getGuide().contains("update_database")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description must point at launch", new GetApplicationsTool().getGuide().contains("launch")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetApplicationsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaMarksProjectNameRequired()
    {
        // projectName is the only input and it is mandatory.
        String schema = new GetApplicationsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresAllFields()
    {
        String schema = new GetApplicationsTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare project", schema.contains("\"project\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare applications", schema.contains("\"applications\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare count", schema.contains("\"count\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare message", schema.contains("\"message\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare defaultApplicationId", //$NON-NLS-1$
            schema.contains("\"defaultApplicationId\"")); //$NON-NLS-1$
        // inheritedFromProject is emitted only when applications are resolved via the base
        // project of a dependent (external-objects/extension) project; the schema must still
        // declare it so the wire contract advertises the field for every consumer.
        assertTrue("outputSchema must declare inheritedFromProject", //$NON-NLS-1$
            schema.contains("\"inheritedFromProject\"")); //$NON-NLS-1$
        assertTrue("updateState must be described as asynchronously refreshed cached state", //$NON-NLS-1$
            schema.contains("cached infobase-equality comparison") //$NON-NLS-1$
                && schema.contains("refreshed asynchronously")); //$NON-NLS-1$
        assertTrue("schema must warn that updateState can lag and point to stateAfter", //$NON-NLS-1$
            schema.contains("pre-update value") && schema.contains("stateAfter")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetApplicationsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameCarriesDiscoveryHint()
    {
        // The shared required-argument guard appends the list_projects discovery hint.
        String result = new GetApplicationsTool().execute(new HashMap<>());
        assertTrue("missing projectName must steer to list_projects", //$NON-NLS-1$
            result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testBlankProjectNameIsAlsoMissing()
    {
        // An empty value is treated as absent by requireArgument and hits the same branch
        // BEFORE any ResourcesPlugin/workspace access.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetApplicationsTool().execute(params);
        assertTrue("blank projectName must hit the required branch", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameIsStructuredErrorEnvelope()
    {
        // The validation error must be the structured {"success":false,...} envelope
        // recognised by the protocol's error diversion, not a partial success body.
        String result = new GetApplicationsTool().execute(new HashMap<>());
        assertTrue("error must be a structured failure envelope", //$NON-NLS-1$
            result.contains("\"success\":false")); //$NON-NLS-1$
        assertFalse("a validation error must not emit a success envelope", //$NON-NLS-1$
            result.contains("\"success\":true")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameOmitsInheritedFromProject()
    {
        // inheritedFromProject is a marker emitted ONLY on the base-project (inherited)
        // branch of a dependent project. A pure argument-error envelope returns before any
        // application lookup, so the marker must be absent here.
        String result = new GetApplicationsTool().execute(new HashMap<>());
        assertFalse("a validation error must not emit inheritedFromProject", //$NON-NLS-1$
            result.contains("inheritedFromProject")); //$NON-NLS-1$
    }

    // ==================== #622: a wedged read never passes for an answer ====================
    //
    // get_applications is the recovery path every other application error names, so it is the one
    // tool that must not hang - and the one whose silences are read as facts: an empty list means
    // "this project has no applications" and a missing defaultApplicationId means "there is no
    // default". Neither may be produced by a read that never concluded.

    /** Small, round deadline so a wedged read is answered fast and its sentence pinned. */
    private static final long SHORT_DEADLINE_MS = 250L;

    /** Deadline for the reads that are expected to answer on their own. */
    private static final long GENEROUS_DEADLINE_MS = 60_000L;

    @Test
    public void testAWedgedListingIsRaisedRatherThanReadAsAnEmptyProject() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Wedged"); //$NON-NLS-1$
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(manager.getApplications(project)).thenAnswer(invocation -> {
            try
            {
                release.await(30, TimeUnit.SECONDS);
                return Collections.emptyList();
            }
            finally
            {
                finished.countDown();
            }
        });

        try
        {
            GetApplicationsTool.listBounded(manager, project, SHORT_DEADLINE_MS);
            fail("a wedged listing must not return a list at all"); //$NON-NLS-1$
        }
        catch (ApplicationListDeadline expected)
        {
            assertTrue("the refusal must name the project and the deadline", //$NON-NLS-1$
                expected.getMessage().contains("the EDT application list for project 'Wedged'")); //$NON-NLS-1$
            assertFalse("a wedged listing must not be worded as an empty project", //$NON-NLS-1$
                expected.getMessage().contains("No applications found")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAWedgedDefaultLookupIsReportedInsteadOfSilentlyOmittingTheKey() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Wedged"); //$NON-NLS-1$
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
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

        try
        {
            DefaultApplication resolved =
                GetApplicationsTool.resolveDefaultApplicationId(manager, project, SHORT_DEADLINE_MS);

            assertNull("a wedged lookup must not invent an id", resolved.id()); //$NON-NLS-1$
            assertNotNull("the deadline must be reported, not swallowed", resolved.note()); //$NON-NLS-1$
            assertTrue("the note must say the default is UNKNOWN", //$NON-NLS-1$
                resolved.note().startsWith("The default application is UNKNOWN: ")); //$NON-NLS-1$
            assertTrue("the note must carry the deadline diagnosis", //$NON-NLS-1$
                resolved.note().contains(
                    "the EDT default-application lookup for project 'Wedged'")); //$NON-NLS-1$
            assertFalse("UNKNOWN must not be worded as 'there is no default application'", //$NON-NLS-1$
                resolved.note().contains("has no default application")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAMeasuredAbsenceOfADefaultStaysSilent() throws Exception
    {
        // The other edge of the same change: a lookup that CONCLUDED with no default must keep
        // producing the plain "no id, no note" answer, or every healthy project would now carry
        // an explanation it does not need.
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(manager.getDefaultApplication(project)).thenReturn(Optional.empty());

        DefaultApplication resolved = GetApplicationsTool.resolveDefaultApplicationId(manager,
            project, GENEROUS_DEADLINE_MS);

        assertNull(resolved.id());
        assertNull("a measured absence must not be explained away as unknown", resolved.note()); //$NON-NLS-1$
    }

    @Test
    public void testAResolvedDefaultIsReturnedUnchanged() throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        IApplication application = mock(IApplication.class);
        when(application.getId()).thenReturn("Infobase.Main"); //$NON-NLS-1$
        when(manager.getDefaultApplication(project)).thenReturn(Optional.of(application));

        DefaultApplication resolved = GetApplicationsTool.resolveDefaultApplicationId(manager,
            project, GENEROUS_DEADLINE_MS);

        assertEquals("Infobase.Main", resolved.id()); //$NON-NLS-1$
        assertNull("a resolved default needs no note", resolved.note()); //$NON-NLS-1$
    }

    @Test
    public void testAWedgedListingIsAnsweredOnTheDeadlineNotOnTheWedge() throws Exception
    {
        // Without this, an implementation that waited out the WHOLE wedge would satisfy every
        // assertion above - it would still end up raising, just minutes later.
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Wedged"); //$NON-NLS-1$
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(manager.getApplications(project)).thenAnswer(invocation -> {
            try
            {
                release.await(30, TimeUnit.SECONDS);
                return Collections.emptyList();
            }
            finally
            {
                finished.countDown();
            }
        });

        try
        {
            long startMs = System.currentTimeMillis();
            try
            {
                GetApplicationsTool.listBounded(manager, project, SHORT_DEADLINE_MS);
                fail("a wedged listing must not return"); //$NON-NLS-1$
            }
            catch (ApplicationListDeadline expected)
            {
                assertTrue("the caller must return on its own deadline, not on the wedge", //$NON-NLS-1$
                    System.currentTimeMillis() - startMs < 20_000L);
            }
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    // ===== #622/H: the per-application update-state read is bounded and says when it is unknown =====

    @Test
    public void testAWedgedUpdateStateReadIsAnsweredOnTheDeadlineAsUNKNOWN() throws Exception
    {
        // getUpdateState is an IApplicationManager call, and it ran once per application with no
        // bound at all - which made the tool every other application error points the agent at the
        // one that could park indefinitely.
        IApplicationManager manager = mock(IApplicationManager.class);
        IApplication app = mock(IApplication.class);
        when(app.getId()).thenReturn("Infobase.Wedged"); //$NON-NLS-1$
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(manager.getUpdateState(app)).thenAnswer(invocation -> {
            try
            {
                release.await(30, TimeUnit.SECONDS);
                return null;
            }
            finally
            {
                finished.countDown();
            }
        });

        try
        {
            long startMs = System.currentTimeMillis();
            GetApplicationsTool.UpdateStates states = GetApplicationsTool.updateStatesBounded(
                manager, Collections.singletonList(app), SHORT_DEADLINE_MS);
            long elapsedMs = System.currentTimeMillis() - startMs;
            JsonObject appObj = new JsonObject();
            states.mergeInto(appObj, 0);

            assertTrue("the read must be answered on the deadline, not on the wedge: " //$NON-NLS-1$
                + elapsedMs, elapsedMs < 20_000L);
            assertEquals("an unread state must be reported, not omitted", "UNKNOWN", //$NON-NLS-1$ //$NON-NLS-2$
                appObj.get("updateState").getAsString()); //$NON-NLS-1$
            assertTrue("it must carry the deadline diagnosis", appObj.get("updateStateError") //$NON-NLS-1$ //$NON-NLS-2$
                .getAsString().contains("the EDT update-state read")); //$NON-NLS-1$
            assertTrue("and it must not read as 'up to date'", //$NON-NLS-1$
                appObj.get("updateStateDescription").getAsString().contains("is NOT a claim")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAReadUpdateStateStillRendersExactlyAsBefore() throws Exception
    {
        // The other edge: bounding the read must not change what a healthy project reports.
        IApplicationManager manager = mock(IApplicationManager.class);
        IApplication app = mock(IApplication.class);
        when(app.getId()).thenReturn("Infobase.Main"); //$NON-NLS-1$
        when(manager.getUpdateState(app)).thenReturn(ApplicationUpdateState.UPDATED);

        GetApplicationsTool.UpdateStates states = GetApplicationsTool.updateStatesBounded(
            manager, Collections.singletonList(app), GENEROUS_DEADLINE_MS);
        JsonObject appObj = new JsonObject();
        states.mergeInto(appObj, 0);

        assertEquals("UPDATED", appObj.get("updateState").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a read state carries no error", appObj.has("updateStateError")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testARaisedDefaultLookupIsAlsoReportedAsUnknown() throws Exception
    {
        // The branch right below the deadline one returned the SAME silence as "there is no
        // default" - and a raised lookup establishes exactly as little as an expired one.
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Raising"); //$NON-NLS-1$
        when(manager.getDefaultApplication(project))
            .thenThrow(new ApplicationException("registry unavailable")); //$NON-NLS-1$

        DefaultApplication resolved = GetApplicationsTool.resolveDefaultApplicationId(manager,
            project, GENEROUS_DEADLINE_MS);

        assertNull(resolved.id());
        assertNotNull("a raised lookup must not pass as 'there is no default'", resolved.note()); //$NON-NLS-1$
        assertTrue(resolved.note().startsWith("The default application is UNKNOWN: ")); //$NON-NLS-1$
        assertTrue("the note must carry the failure", //$NON-NLS-1$
            resolved.note().contains("registry unavailable")); //$NON-NLS-1$
    }

    @Test
    public void testTheThreeDefaultOutcomesAreDistinguishableOnTheSerializedPayload()
    {
        // Asserting on the DefaultApplication record alone proves nothing about what the caller
        // receives: the record is right and the payload can still collapse two outcomes, because
        // "no defaultApplicationId" is this tool's way of saying BOTH "there is none" and - if the
        // wiring slips - "the lookup never answered".
        JsonObject resolved = payloadFor(new DefaultApplication("Infobase.Main", null)); //$NON-NLS-1$
        JsonObject unknown = payloadFor(new DefaultApplication(null,
            "The default application is UNKNOWN: the lookup expired.")); //$NON-NLS-1$
        JsonObject absent = payloadFor(new DefaultApplication(null, null));

        assertEquals("Infobase.Main", resolved.get("defaultApplicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a resolved default needs no message", resolved.has("message")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("an UNKNOWN default must not be given an id", //$NON-NLS-1$
            unknown.has("defaultApplicationId")); //$NON-NLS-1$
        assertTrue("an UNKNOWN default must say so in the payload", unknown.has("message")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(unknown.get("message").getAsString() //$NON-NLS-1$
            .startsWith("The default application is UNKNOWN: ")); //$NON-NLS-1$

        assertFalse("a measured absence carries no id", absent.has("defaultApplicationId")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("and no explanation either", absent.has("message")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static JsonObject payloadFor(DefaultApplication defaultApp)
    {
        ToolResult result = ToolResult.success().put(McpKeys.PROJECT, "P"); //$NON-NLS-1$
        return JsonParser.parseString(
            GetApplicationsTool.applyDefaultApplication(result, defaultApp).toJson())
            .getAsJsonObject();
    }
}
