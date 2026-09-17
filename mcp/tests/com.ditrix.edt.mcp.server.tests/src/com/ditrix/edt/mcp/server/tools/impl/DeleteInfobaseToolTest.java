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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.StandaloneServerSupport;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link DeleteInfobaseTool}.
 * <p>
 * Covers tool metadata, schema, the confirm-preview gate, and the argument-validation
 * guards that execute BEFORE any workspace or platform-services access. The real
 * dissociate/deregister path needs a live EDT workspace and is covered by the e2e suite.
 */
public class DeleteInfobaseToolTest
{
    @Test
    public void testName()
    {
        assertEquals("delete_infobase", new DeleteInfobaseTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DeleteInfobaseTool.NAME, new DeleteInfobaseTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new DeleteInfobaseTool().getResponseType());
    }

    @Test
    public void testConnectsToInfobaseIsTrue()
    {
        // #270: stopping a standalone server / dissociating an infobase reaches the
        // application/infobase connection layer — it must arm the auth-dialog
        // suppressor's activity window.
        assertTrue(new DeleteInfobaseTool().connectsToInfobase());
    }

    @Test
    public void testDescriptionNotEmptyAndMentionsConfirmPreview()
    {
        String desc = new DeleteInfobaseTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must advertise the confirm-preview gate", //$NON-NLS-1$
            desc.toLowerCase().contains("confirm")); //$NON-NLS-1$
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('delete_infobase')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresAllParameters()
    {
        String schema = new DeleteInfobaseTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare projectName", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare applicationId", schema.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare infobaseName", schema.contains("\"infobaseName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare deleteRegistration", //$NON-NLS-1$
            schema.contains("\"deleteRegistration\"")); //$NON-NLS-1$
        assertTrue("schema must declare deleteDatabaseFiles", //$NON-NLS-1$
            schema.contains("\"deleteDatabaseFiles\"")); //$NON-NLS-1$
        assertTrue("schema must declare the confirm gate", schema.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRequiredParametersInSchema()
    {
        String schema = new DeleteInfobaseTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // Optional parameters must NOT be in the required array.
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        if (open >= 0 && close > open)
        {
            String requiredBlock = schema.substring(open, close + 1);
            assertTrue("applicationId must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"applicationId\"")); //$NON-NLS-1$
            assertTrue("infobaseName must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"infobaseName\"")); //$NON-NLS-1$
            assertTrue("deleteRegistration must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"deleteRegistration\"")); //$NON-NLS-1$
            assertTrue("deleteDatabaseFiles must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"deleteDatabaseFiles\"")); //$NON-NLS-1$
            assertTrue("confirm must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"confirm\"")); //$NON-NLS-1$
        }
    }

    @Test
    public void testOutputSchemaDeclaresConfirmPreviewFields()
    {
        String schema = new DeleteInfobaseTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare confirmationRequired", //$NON-NLS-1$
            schema.contains("\"confirmationRequired\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare applicationId", schema.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare infobaseName", schema.contains("\"infobaseName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare deleteRegistration", //$NON-NLS-1$
            schema.contains("\"deleteRegistration\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare databaseFilesDeleted", //$NON-NLS-1$
            schema.contains("\"databaseFilesDeleted\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare applicationKind (standalone-server removals)", //$NON-NLS-1$
            schema.contains("\"applicationKind\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresEveryKeepReasonTheCodeCanProduce()
    {
        // databaseFilesDeleted=false is six different answers, and until now only the prose told
        // them apart - so a client could not separate a MEASURED co-owner from a shared check that
        // never concluded. The declared vocabulary must cover exactly the reasons the code emits:
        // no invented value, and no omitted one (deregistrationFailed is a real branch).
        String schema = new DeleteInfobaseTool().getOutputSchema();
        assertTrue("outputSchema must declare databaseFilesKept", //$NON-NLS-1$
            schema.contains("\"databaseFilesKept\"")); //$NON-NLS-1$
        assertTrue("it must be a closed enum, not a free string", schema.contains("\"enum\"")); //$NON-NLS-1$ //$NON-NLS-2$
        for (String value : new String[] { DeleteInfobaseTool.KEPT_NOT_REQUESTED,
            DeleteInfobaseTool.KEPT_NO_DIRECTORY, DeleteInfobaseTool.KEPT_SHARED,
            DeleteInfobaseTool.KEPT_UNKNOWN, DeleteInfobaseTool.KEPT_DELETE_FAILED,
            DeleteInfobaseTool.KEPT_DEREGISTRATION_FAILED })
        {
            assertTrue("outputSchema must declare the keep reason " + value, //$NON-NLS-1$
                schema.contains("\"" + value + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // The values themselves are the wire vocabulary; pin the spelling.
        assertEquals("notRequested", DeleteInfobaseTool.KEPT_NOT_REQUESTED); //$NON-NLS-1$
        assertEquals("noDirectory", DeleteInfobaseTool.KEPT_NO_DIRECTORY); //$NON-NLS-1$
        assertEquals("shared", DeleteInfobaseTool.KEPT_SHARED); //$NON-NLS-1$
        assertEquals("unknown", DeleteInfobaseTool.KEPT_UNKNOWN); //$NON-NLS-1$
        assertEquals("deleteFailed", DeleteInfobaseTool.KEPT_DELETE_FAILED); //$NON-NLS-1$
        assertEquals("deregistrationFailed", DeleteInfobaseTool.KEPT_DEREGISTRATION_FAILED); //$NON-NLS-1$
    }

    @Test
    public void testApplicationKindIsOutputOnlyNotAnInputParam()
    {
        // The application kind is AUTO-DETECTED from the resolved application — it must NOT be an input
        // parameter (a future edit must not add an input the tool ignores). It IS an output field.
        DeleteInfobaseTool tool = new DeleteInfobaseTool();
        assertTrue("applicationKind must NOT be an input parameter", //$NON-NLS-1$
            !tool.getInputSchema().contains("\"applicationKind\"")); //$NON-NLS-1$
        assertTrue("applicationKind must be declared in the output schema", //$NON-NLS-1$
            tool.getOutputSchema().contains("\"applicationKind\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputApplicationKindNamesBothKinds()
    {
        // Pin the wire vocabulary agents key off: the output applicationKind must name both kinds.
        String schema = new DeleteInfobaseTool().getOutputSchema();
        assertTrue("output applicationKind must mention 'infobase'", schema.contains("infobase")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("output applicationKind must mention 'standaloneServer'", //$NON-NLS-1$
            schema.contains("standaloneServer")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionAndGuideCoverStandaloneServer()
    {
        // delete_infobase is the inverse of create_infobase for BOTH kinds; it must advertise the
        // standalone-server deletion path in its description and guide.
        DeleteInfobaseTool tool = new DeleteInfobaseTool();
        String desc = tool.getDescription();
        assertTrue("description must mention the standalone server path", //$NON-NLS-1$
            desc.toLowerCase().contains("standalone")); //$NON-NLS-1$
        String guide = tool.getGuide();
        assertTrue("guide must document the standalone-server deletion", //$NON-NLS-1$
            guide.toLowerCase().contains("standalone")); //$NON-NLS-1$
        assertTrue("guide must note the served database is removed for a server", //$NON-NLS-1$
            guide.toLowerCase().contains("database")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsConfirmPreviewAndDeletion()
    {
        String guide = new DeleteInfobaseTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue("guide must document the preview phase", //$NON-NLS-1$
            guide.toLowerCase().contains("preview")); //$NON-NLS-1$
        assertTrue("guide must document the confirm parameter", guide.contains("confirm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document deleteRegistration", guide.contains("deleteRegistration")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument validation (returns before any workspace access) ====================

    @Test
    public void testMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "someApp"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DeleteInfobaseTool().execute(params);
        assertTrue("missing projectName must produce an error containing 'projectName is required'", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingBothApplicationIdAndInfobaseNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        // Neither applicationId nor infobaseName provided.
        String result = new DeleteInfobaseTool().execute(params);
        assertTrue("missing both applicationId and infobaseName must produce an error", //$NON-NLS-1$
            result.contains("applicationId") && result.contains("infobaseName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== #622: a wedged lookup refuses before anything is deleted ==============

    @Test
    public void testAWedgedApplicationLookupRefusesWithoutDeletingAnything() throws Exception
    {
        // This resolution names what a DESTRUCTIVE call is about to delete, so a lookup that
        // never concluded must refuse in its own words - "Application not found" would be a
        // claim about the project that nothing measured.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(mgr.getApplication(project, "Infobase.Doomed")).thenAnswer(invocation -> { //$NON-NLS-1$
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
            DeleteInfobaseTool.TargetApplication target = DeleteInfobaseTool
                .resolveTargetApplication(mgr, project, "Proj", "Infobase.Doomed", null, 250L); //$NON-NLS-1$ //$NON-NLS-2$

            assertNull("a wedged lookup must not resolve a deletion target", target.app); //$NON-NLS-1$
            assertNotNull("a wedged lookup must be refused", target.error); //$NON-NLS-1$
            assertTrue("the refusal must carry the deadline diagnosis", //$NON-NLS-1$
                target.error.contains(
                    "the EDT application lookup for application 'Infobase.Doomed'")); //$NON-NLS-1$
            assertTrue("the refusal must state that nothing was deleted", //$NON-NLS-1$
                target.error.contains("Nothing was deleted")); //$NON-NLS-1$
            assertFalse("an unread lookup must not be reported as a measured not-found", //$NON-NLS-1$
                target.error.contains("Application not found")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAConcludedEmptyLookupIsStillAMeasuredNotFound() throws Exception
    {
        // The other edge: bounding the read must not have turned a real not-found into a
        // deadline-flavoured refusal.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(project, "Infobase.Missing")).thenReturn(Optional.empty()); //$NON-NLS-1$

        DeleteInfobaseTool.TargetApplication target = DeleteInfobaseTool
            .resolveTargetApplication(mgr, project, "Proj", "Infobase.Missing", null, 60_000L); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(target.error);
        assertTrue("a measured absence must keep the not-found wording", //$NON-NLS-1$
            target.error.contains("Application not found")); //$NON-NLS-1$
        assertFalse("a measured absence must not be dressed up as a deadline", //$NON-NLS-1$
            target.error.contains("did not finish within")); //$NON-NLS-1$
    }

    // ============ #622: an unreadable read-back is "unknown", not a confirmed deletion ==========

    @Test
    public void testAnUnreadableReadBackIsNotAConfirmedRemoval() throws Exception
    {
        // The read-back used to map "could not read" straight onto "removed". #622 gave that read
        // a 30 s deadline on exactly the wedge this work exists for, so on a DESTRUCTIVE tool the
        // likeliest new outcome was a claim nobody measured.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project))
            .thenThrow(new ApplicationException("delegates are not up")); //$NON-NLS-1$

        DeleteInfobaseTool.ReadBack outcome =
            DeleteInfobaseTool.confirmApplicationRemoved(mgr, project, "app-doomed", 1); //$NON-NLS-1$

        assertEquals("an unreadable read-back establishes nothing", //$NON-NLS-1$
            DeleteInfobaseTool.ReadBack.UNREADABLE, outcome);
    }

    @Test
    public void testAnUnknownBeforeCountDoesNotTurnASuccessfulTwinDeletionIntoAFailure()
        throws Exception
    {
        // The twin case (2 -> 1) is recognised by `now < beforeCount`. With beforeCount unknown
        // (-1) that comparison can never hold, so a successful deletion was reported unconfirmed.
        // With no baseline the honest answer is UNKNOWN, not "still listed".
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        // Built BEFORE the outer stubbing: app() stubs its own mock, and a nested when(...)
        // inside thenReturn(...) is an UnfinishedStubbingException.
        List<IApplication> twin = Collections.singletonList(app("app-twin")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(twin);

        DeleteInfobaseTool.ReadBack outcome = DeleteInfobaseTool.confirmApplicationRemoved(mgr,
            project, "app-twin", DeleteInfobaseTool.COUNT_UNKNOWN); //$NON-NLS-1$

        assertEquals("with no baseline a surviving twin is unknown, not a failure", //$NON-NLS-1$
            DeleteInfobaseTool.ReadBack.UNREADABLE, outcome);
    }

    @Test
    public void testAMeasuredCountDropStillConfirms() throws Exception
    {
        // The other edge: the tri-state must not have cost the ordinary confirmations.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> twin = Collections.singletonList(app("app-twin")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(twin);

        assertEquals("2 -> 1 is a confirmed twin deletion", //$NON-NLS-1$
            DeleteInfobaseTool.ReadBack.CONFIRMED,
            DeleteInfobaseTool.confirmApplicationRemoved(mgr, project, "app-twin", 2)); //$NON-NLS-1$

        IApplicationManager empty = mock(IApplicationManager.class);
        when(empty.getApplications(project)).thenReturn(Collections.emptyList());
        assertEquals("a measured zero confirms even with no baseline", //$NON-NLS-1$
            DeleteInfobaseTool.ReadBack.CONFIRMED,
            DeleteInfobaseTool.confirmApplicationRemoved(empty, project, "app-gone", //$NON-NLS-1$
                DeleteInfobaseTool.COUNT_UNKNOWN));
    }

    @Test
    public void testACountThatNeverDropsIsStillListed() throws Exception
    {
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> stuck = Collections.singletonList(app("app-stuck")); //$NON-NLS-1$
        when(mgr.getApplications(project)).thenReturn(stuck);

        assertEquals("a count read every time that never dropped is still listed", //$NON-NLS-1$
            DeleteInfobaseTool.ReadBack.STILL_LISTED,
            DeleteInfobaseTool.confirmApplicationRemoved(mgr, project, "app-stuck", 1)); //$NON-NLS-1$
    }

    @Test
    public void testEachReadBackStateGetsItsOwnNote()
    {
        assertEquals("a confirmed removal adds nothing", //$NON-NLS-1$
            "", DeleteInfobaseTool.readBackNote(DeleteInfobaseTool.ReadBack.CONFIRMED)); //$NON-NLS-1$
        assertTrue("a surviving entry keeps the 'briefly' note", //$NON-NLS-1$
            DeleteInfobaseTool.readBackNote(DeleteInfobaseTool.ReadBack.STILL_LISTED)
                .contains("may still appear in get_applications briefly")); //$NON-NLS-1$
        String unreadable = DeleteInfobaseTool.readBackNote(DeleteInfobaseTool.ReadBack.UNREADABLE);
        assertTrue("an unreadable read-back must say the removal is unconfirmed", //$NON-NLS-1$
            unreadable.contains("could not be CONFIRMED")); //$NON-NLS-1$
        // The wrong answers: silence (which reads as a confirmation) and the "briefly" note (which
        // claims the entry was SEEN, and nothing was).
        assertFalse("an unreadable read-back must not pass silently", unreadable.isEmpty()); //$NON-NLS-1$
        assertFalse("an unreadable read-back must not claim the entry was seen", //$NON-NLS-1$
            unreadable.contains("may still appear in get_applications briefly")); //$NON-NLS-1$
    }

    // ========== #622/D: an expired shared-infobase check is not measured co-ownership ==========

    @Test
    public void testAnUnconcludedSharedCheckIsUnknownNotShared() throws Exception
    {
        // The enumeration cannot finish inside the deadline, so nothing about co-ownership was
        // established. The conservative KEEP is right; calling it SHARED is the claim that is not.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try
        {
            DeleteInfobaseTool.SharedDatabase shared = DeleteInfobaseTool.sharedCheckBounded(250L,
                () -> {
                    try
                    {
                        release.await(30, TimeUnit.SECONDS);
                        return Boolean.FALSE;
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        return Boolean.FALSE;
                    }
                    finally
                    {
                        finished.countDown();
                    }
                });

            assertEquals(DeleteInfobaseTool.SharedDatabase.UNKNOWN, shared);
            assertTrue("an unconcluded check must still keep the files", shared.keepsFiles()); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAConcludedSharedCheckKeepsBothMeasuredAnswers()
    {
        assertEquals("a concluded 'yes' is SHARED", DeleteInfobaseTool.SharedDatabase.SHARED, //$NON-NLS-1$
            DeleteInfobaseTool.sharedCheckBounded(30_000L, () -> Boolean.TRUE));
        assertEquals("a concluded 'no' is NOT_SHARED", //$NON-NLS-1$
            DeleteInfobaseTool.SharedDatabase.NOT_SHARED,
            DeleteInfobaseTool.sharedCheckBounded(30_000L, () -> Boolean.FALSE));
        assertFalse("only a measured 'no' may delete the files", //$NON-NLS-1$
            DeleteInfobaseTool.SharedDatabase.NOT_SHARED.keepsFiles());
        assertTrue(DeleteInfobaseTool.SharedDatabase.SHARED.keepsFiles());
    }

    @Test
    public void testAnUnknownSharedCheckIsNotReportedAsCoOwnership()
    {
        Path dbDir = Paths.get("C:", "bases", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String unknown = DeleteInfobaseTool.databaseResultNote(new DeleteInfobaseTool.DbFileOutcome(
            true, dbDir, false, DeleteInfobaseTool.SharedDatabase.UNKNOWN));
        String shared = DeleteInfobaseTool.databaseResultNote(new DeleteInfobaseTool.DbFileOutcome(
            true, dbDir, false, DeleteInfobaseTool.SharedDatabase.SHARED));

        assertTrue("it must say the files were kept", unknown.contains("were KEPT")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("it must say the check could not be completed", //$NON-NLS-1$
            unknown.contains("could not be completed")); //$NON-NLS-1$
        assertTrue("it must say the sharing itself is unknown", //$NON-NLS-1$
            unknown.contains("is shared is UNKNOWN")); //$NON-NLS-1$
        // The sentence this replaced, which stated an unmeasured fact as a measured one.
        assertFalse("an unconcluded check must not claim another project uses the database", //$NON-NLS-1$
            unknown.contains("still used by other project(s)")); //$NON-NLS-1$
        // And the genuinely-shared case must keep saying exactly that.
        assertTrue("a measured co-owner must still be named as one", //$NON-NLS-1$
            shared.contains("still used by other project(s)")); //$NON-NLS-1$
        assertFalse(shared.contains("UNKNOWN")); //$NON-NLS-1$
    }

    @Test
    public void testAnUnknownPreviewSaysTheConfirmReRunsTheCheck()
    {
        Path dbDir = Paths.get("C:", "bases", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String unknown = DeleteInfobaseTool.databasePreviewNote(true, dbDir,
            DeleteInfobaseTool.SharedDatabase.UNKNOWN);
        String shared = DeleteInfobaseTool.databasePreviewNote(true, dbDir,
            DeleteInfobaseTool.SharedDatabase.SHARED);

        // Preview and confirm are separate calls, each running its OWN bounded enumeration, so a
        // deadline that flips between them makes the preview non-binding. The preview has to say so.
        assertTrue("the preview must say the files would be kept", unknown.contains("KEPT")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the preview must say the check did not complete", //$NON-NLS-1$
            unknown.contains("did not complete")); //$NON-NLS-1$
        assertTrue("the preview must say confirm=true re-runs the check", //$NON-NLS-1$
            unknown.contains("confirm=true re-runs that check")); //$NON-NLS-1$
        assertTrue("the preview must admit the files may be deleted after all", //$NON-NLS-1$
            unknown.contains("may be DELETED after all")); //$NON-NLS-1$
        assertFalse("an unconcluded preview must not claim other projects use it", //$NON-NLS-1$
            unknown.contains("still used by other projects")); //$NON-NLS-1$
        assertTrue("the measured-shared preview keeps its own wording", //$NON-NLS-1$
            shared.contains("still used by other projects")); //$NON-NLS-1$
    }

    // ===== #622: the keep reason is DECLARED on the payload, not only spelled out in the prose =====

    /** The identity every payload test reuses; the names are irrelevant to the discriminator. */
    private static DeleteInfobaseTool.IbIdentity ibId()
    {
        return new DeleteInfobaseTool.IbIdentity("Proj", "app-1", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static final Path DB_DIR = Paths.get("C:", "bases", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static JsonObject parse(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String keptOf(JsonObject payload)
    {
        return payload.has("databaseFilesKept") //$NON-NLS-1$
            ? payload.get("databaseFilesKept").getAsString() : null; //$NON-NLS-1$
    }

    @Test
    public void testEveryKeepReasonThatCanReachADeletionPayloadIsNamedOnIt()
    {
        // One assertion per branch databaseResultNote can take - the serialized payload, because
        // that is what a client reads. A record or a sentence proves nothing about the wire.
        JsonObject notRequested = parse(DeleteInfobaseTool.buildDeleteSuccessResult(ibId(), true,
            new DeleteInfobaseTool.DbFileOutcome(false, DB_DIR, false,
                DeleteInfobaseTool.SharedDatabase.NOT_SHARED)));
        JsonObject noDirectory = parse(DeleteInfobaseTool.buildDeleteSuccessResult(ibId(), true,
            new DeleteInfobaseTool.DbFileOutcome(true, null, false,
                DeleteInfobaseTool.SharedDatabase.NOT_SHARED)));
        JsonObject shared = parse(DeleteInfobaseTool.buildDeleteSuccessResult(ibId(), true,
            new DeleteInfobaseTool.DbFileOutcome(true, DB_DIR, false,
                DeleteInfobaseTool.SharedDatabase.SHARED)));
        JsonObject unknown = parse(DeleteInfobaseTool.buildDeleteSuccessResult(ibId(), true,
            new DeleteInfobaseTool.DbFileOutcome(true, DB_DIR, false,
                DeleteInfobaseTool.SharedDatabase.UNKNOWN)));
        JsonObject deleteFailed = parse(DeleteInfobaseTool.buildDeleteSuccessResult(ibId(), true,
            new DeleteInfobaseTool.DbFileOutcome(true, DB_DIR, false,
                DeleteInfobaseTool.SharedDatabase.NOT_SHARED)));

        assertEquals("notRequested", keptOf(notRequested)); //$NON-NLS-1$
        assertEquals("noDirectory", keptOf(noDirectory)); //$NON-NLS-1$
        assertEquals("shared", keptOf(shared)); //$NON-NLS-1$
        assertEquals("unknown", keptOf(unknown)); //$NON-NLS-1$
        assertEquals("deleteFailed", keptOf(deleteFailed)); //$NON-NLS-1$

        // The distinction the whole change exists for: a measured co-owner and an unconcluded
        // check keep the files for OPPOSITE reasons and must never serialize to the same value.
        assertFalse("an unconcluded check must not serialize as measured co-ownership", //$NON-NLS-1$
            "shared".equals(keptOf(unknown))); //$NON-NLS-1$
    }

    @Test
    public void testADeletionThatWentThroughCarriesNoKeepReason()
    {
        // The over-correction guard for the delete side: a reason always attached would make
        // "kept" meaningless, and would contradict databaseFilesDeleted=true in the same payload.
        JsonObject deleted = parse(DeleteInfobaseTool.buildDeleteSuccessResult(ibId(), true,
            new DeleteInfobaseTool.DbFileOutcome(true, DB_DIR, true,
                DeleteInfobaseTool.SharedDatabase.NOT_SHARED)));

        assertTrue("the files went", deleted.get("databaseFilesDeleted").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a completed deletion has no keep reason", keptOf(deleted)); //$NON-NLS-1$
    }

    @Test
    public void testKeptAndDeletedAreTwoHalvesOfOneAnswer()
    {
        // The invariant the shared writer exists to guarantee: databaseFilesDeleted=false is never
        // reported without naming why, and deleted=true never carries a keep reason. Both keys are
        // written in ONE place so a future builder cannot emit half the answer.
        for (boolean requested : new boolean[] { true, false })
        {
            for (Path dir : new Path[] { DB_DIR, null })
            {
                for (DeleteInfobaseTool.SharedDatabase state : DeleteInfobaseTool.SharedDatabase
                    .values())
                {
                    for (boolean deleted : new boolean[] { true, false })
                    {
                        JsonObject payload =
                            parse(DeleteInfobaseTool.buildDeleteSuccessResult(ibId(), true,
                                new DeleteInfobaseTool.DbFileOutcome(requested, dir, deleted,
                                    state)));
                        boolean reportedDeleted =
                            payload.get("databaseFilesDeleted").getAsBoolean(); //$NON-NLS-1$
                        assertEquals("deleted=" + reportedDeleted + " must decide whether a keep " //$NON-NLS-1$ //$NON-NLS-2$
                            + "reason is present", reportedDeleted, keptOf(payload) == null); //$NON-NLS-1$
                    }
                }
            }
        }
    }

    @Test
    public void testAFailedDeregistrationNamesItselfAsTheKeepReason()
    {
        // This branch returns BEFORE the deletion step, so no shared-infobase answer applies to
        // it: reporting it as notRequested or deleteFailed would name a cause that never ran.
        JsonObject wanted = parse(DeleteInfobaseTool.buildDeregisterFailedResult(ibId(), true,
            new IllegalStateException("registry locked"))); //$NON-NLS-1$
        JsonObject notWanted = parse(DeleteInfobaseTool.buildDeregisterFailedResult(ibId(), false,
            new IllegalStateException("registry locked"))); //$NON-NLS-1$

        assertFalse("the files were not deleted", //$NON-NLS-1$
            wanted.get("databaseFilesDeleted").getAsBoolean()); //$NON-NLS-1$
        assertEquals("deregistrationFailed", keptOf(wanted)); //$NON-NLS-1$
        assertEquals("notRequested", keptOf(notWanted)); //$NON-NLS-1$
    }

    @Test
    public void testAPreviewDeclaresTheReasonItWouldKeepTheFiles()
    {
        // The preview is where the agent decides whether to call confirm=true, so the shared /
        // unknown distinction matters MOST here - and it was prose-only.
        JsonObject shared = parse(DeleteInfobaseTool.buildPreviewResult(ibId(), true, true, DB_DIR,
            DeleteInfobaseTool.SharedDatabase.SHARED));
        JsonObject unknown = parse(DeleteInfobaseTool.buildPreviewResult(ibId(), true, true, DB_DIR,
            DeleteInfobaseTool.SharedDatabase.UNKNOWN));
        JsonObject wouldDelete = parse(DeleteInfobaseTool.buildPreviewResult(ibId(), true, true,
            DB_DIR, DeleteInfobaseTool.SharedDatabase.NOT_SHARED));
        JsonObject serverUnknown = parse(DeleteInfobaseTool.buildStandaloneServerPreview(false,
            ibId(), true, true, DB_DIR, DeleteInfobaseTool.SharedDatabase.UNKNOWN));

        assertEquals("shared", keptOf(shared)); //$NON-NLS-1$
        assertEquals("unknown", keptOf(unknown)); //$NON-NLS-1$
        assertNull("a preview that would delete names no keep reason", keptOf(wouldDelete)); //$NON-NLS-1$
        assertEquals("the standalone-server preview answers the same way", //$NON-NLS-1$
            "unknown", keptOf(serverUnknown)); //$NON-NLS-1$
        // A preview changes nothing, so it must not claim an outcome.
        assertFalse("a preview must not report databaseFilesDeleted", //$NON-NLS-1$
            unknown.has("databaseFilesDeleted")); //$NON-NLS-1$
    }

    @Test
    public void testTheStandaloneServerDeletionDeclaresTheSameReasons()
    {
        // The second deletion path had its own result-builder; both must answer identically or the
        // field means something different depending on the application kind.
        JsonObject unknown = parse(DeleteInfobaseTool.buildDeletedResult(ibId(), true,
            new DeleteInfobaseTool.DbFileOutcome(true, DB_DIR, false,
                DeleteInfobaseTool.SharedDatabase.UNKNOWN),
            StandaloneServerSupport.RegistryCleanup.REMOVED, DeleteInfobaseTool.ReadBack.CONFIRMED));
        JsonObject deleted = parse(DeleteInfobaseTool.buildDeletedResult(ibId(), true,
            new DeleteInfobaseTool.DbFileOutcome(true, DB_DIR, true,
                DeleteInfobaseTool.SharedDatabase.NOT_SHARED),
            StandaloneServerSupport.RegistryCleanup.REMOVED, DeleteInfobaseTool.ReadBack.CONFIRMED));

        assertEquals("unknown", keptOf(unknown)); //$NON-NLS-1$
        assertNull("a completed deletion has no keep reason", keptOf(deleted)); //$NON-NLS-1$
    }

    private static IApplication app(String id)
    {
        IApplication application = mock(IApplication.class);
        when(application.getId()).thenReturn(id);
        return application;
    }
}
