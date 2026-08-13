/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.BuildUtils.DiskExportState;
import com.google.gson.JsonObject;

/**
 * Tests for the post-write disk-export barrier in {@link AbstractMetadataWriteTool} (issue #406).
 * <p>
 * The behaviour under test is the DECISION: a metadata write whose {@code .mdo} export has not
 * reached disk must not answer "done", because the two files a top-object change touches are
 * exported as independent tasks, so the working tree passes through a state where the
 * configuration references an object whose file is already gone.
 * <p>
 * These tests drive {@link AbstractMetadataWriteTool#awaitDiskExport} directly rather than
 * {@code execute}: the latter marshals onto the SWT UI thread, which no headless test has. The
 * export environment is stubbed through the package-visible seam, which is also what lets the
 * false-refusal cases be asserted at all - "the wait could not observe anything" has to be
 * distinguishable from "the wait observed a pending export".
 */
public class AbstractMetadataWriteToolTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final String EXTENSION = "TestConfiguration.tests"; //$NON-NLS-1$

    /** Records what the barrier asked about, so a wait on the WRONG project is visible. */
    private static final class RecordingEnvironment implements AbstractMetadataWriteTool.IExportEnvironment
    {
        private final DiskExportState answer;
        String askedFor;
        long deadlineMs;
        int calls;

        RecordingEnvironment(DiskExportState answer)
        {
            this.answer = answer;
        }

        @Override
        public DiskExportState waitForDiskExport(String projectName, long timeoutMs)
        {
            askedFor = projectName;
            deadlineMs = timeoutMs;
            calls++;
            return answer;
        }
    }

    /** A minimal concrete write tool; only the barrier's inputs matter here. */
    private static class StubTool extends AbstractMetadataWriteTool
    {
        private final RecordingEnvironment environment;
        private final String projectResultKey;
        private final Predicate<JsonObject> wrote;

        StubTool(RecordingEnvironment environment)
        {
            this(environment, null, r -> true);
        }

        StubTool(RecordingEnvironment environment, String projectResultKey, Predicate<JsonObject> wrote)
        {
            this.environment = environment;
            this.projectResultKey = projectResultKey;
            this.wrote = wrote;
        }

        @Override
        IExportEnvironment exportEnvironment()
        {
            return environment;
        }

        @Override
        protected String exportProjectResultKey()
        {
            return projectResultKey;
        }

        @Override
        protected boolean wroteToDisk(Map<String, String> params, JsonObject result)
        {
            return wrote.test(result);
        }

        @Override
        public String getName()
        {
            return "stub_write_tool"; //$NON-NLS-1$
        }

        @Override
        public String getDescription()
        {
            return "stub"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{}"; //$NON-NLS-1$
        }

        @Override
        protected String executeOnUiThread(Map<String, String> params)
        {
            return ToolResult.success().toJson();
        }
    }

    private static Map<String, String> params(String projectName)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", projectName); //$NON-NLS-1$
        return params;
    }

    private static String successJson()
    {
        return ToolResult.success().put("action", "executed").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPendingExportTurnsASuccessIntoAnActionableRefusal()
    {
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        String answer = new StubTool(environment).awaitDiskExport(params(PROJECT), successJson());

        assertTrue("a pending export must not be reported as success", //$NON-NLS-1$
            answer.contains("\"success\":false")); //$NON-NLS-1$
        // The caller's next move depends on knowing nothing was undone - a refusal that let them
        // assume a rollback would be worse than the raw success it replaced.
        assertTrue("the refusal must say nothing was rolled back: " + answer, //$NON-NLS-1$
            answer.contains("Nothing was rolled back")); //$NON-NLS-1$
        assertTrue("the refusal must warn against committing the tree: " + answer, //$NON-NLS-1$
            answer.contains("Do not commit")); //$NON-NLS-1$
        assertTrue("the refusal must name the project: " + answer, answer.contains(PROJECT)); //$NON-NLS-1$
        assertTrue("the refusal must name a way forward: " + answer, //$NON-NLS-1$
            answer.contains("resync_to_disk")); //$NON-NLS-1$
    }

    @Test
    public void testDrainedExportReturnsTheToolsOwnResultUntouched()
    {
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.DRAINED);
        String result = successJson();

        assertSame("a drained export must not rewrite the tool's answer", result, //$NON-NLS-1$
            new StubTool(environment).awaitDiskExport(params(PROJECT), result));
        assertEquals(1, environment.calls);
    }

    @Test
    public void testTheWaitIsGivenABoundedDeadlineAndTheRefusalQuotesTheSameOne()
    {
        // Two things at once: the barrier must not be handed an unbounded wait, and the number it
        // waits for must be the number its refusal names - a message quoting a deadline the code
        // does not use is how an operator gets sent to look in the wrong place.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        String answer = new StubTool(environment).awaitDiskExport(params(PROJECT), successJson());

        // The exact value, not merely "finite": an unbounded or wildly large deadline would pass a
        // `> 0` check while defeating the unattended-safety reason the bound exists at all.
        assertEquals("the export wait must be given the declared 60s deadline", 60_000L, //$NON-NLS-1$
            environment.deadlineMs);
        assertTrue("the refusal must quote the deadline the barrier actually used: " + answer, //$NON-NLS-1$
            answer.contains("60s")); //$NON-NLS-1$
    }

    @Test
    public void testUnobservableExportDoesNotRefuse()
    {
        // The guard against a false refusal: "no derived-data service / not a DT project" is not
        // evidence that anything is pending, and refusing on it would break healthy callers. This
        // is why the seam is tri-state and not a boolean.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.UNOBSERVABLE);
        String result = successJson();

        assertSame("an unobservable export state must not produce a refusal", result, //$NON-NLS-1$
            new StubTool(environment).awaitDiskExport(params(PROJECT), result));
    }

    @Test
    public void testAnErrorResultIsNeverWaitedOn()
    {
        // An error is a well-formed JSON object too. Waiting on one would spend the whole deadline
        // on a call that wrote nothing, and then re-report a rejected argument as a disk problem.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        String result = ToolResult.error("Node not found: Catalog.Nope").toJson(); //$NON-NLS-1$

        assertSame(result, new StubTool(environment).awaitDiskExport(params(PROJECT), result));
        assertEquals("an error result must not reach the export wait", 0, environment.calls); //$NON-NLS-1$
    }

    @Test
    public void testANonMutatingCallIsNeverWaitedOn()
    {
        // A preview has no export of its own; making it wait would only let unrelated background
        // export work refuse it.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        String result = successJson();

        assertSame(result,
            new StubTool(environment, null, r -> false).awaitDiskExport(params(PROJECT), result));
        assertEquals("a preview must not reach the export wait", 0, environment.calls); //$NON-NLS-1$
    }

    @Test
    public void testTheSameToolWaitsOrNotDependingOnWhatTheRESULTSays()
    {
        // The no-op exemption has to discriminate on the RESULT, not on the tool or the arguments.
        // adopt_metadata_object is the real case: "alreadyAdopted" is a SUCCESS that queued no
        // export, while "adopted" on the very same tool, with the very same arguments, did. A hook
        // that only saw the parameters could not tell these two apart, so it would either wait on
        // a no-op (and let unrelated background work refuse a healthy call) or skip a real write.
        Predicate<JsonObject> wroteUnlessAlreadyAdopted =
            r -> !"alreadyAdopted".equals(AbstractMetadataWriteTool.resultString(r, "action")); //$NON-NLS-1$ //$NON-NLS-2$

        RecordingEnvironment onNoOp = new RecordingEnvironment(DiskExportState.PENDING);
        String noOp = ToolResult.success().put("action", "alreadyAdopted").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame("a successful no-op must not be refused over somebody else's pending export", //$NON-NLS-1$
            noOp, new StubTool(onNoOp, null, wroteUnlessAlreadyAdopted)
                .awaitDiskExport(params(PROJECT), noOp));
        assertEquals("a no-op must not even reach the export wait", 0, onNoOp.calls); //$NON-NLS-1$

        RecordingEnvironment onRealWrite = new RecordingEnvironment(DiskExportState.PENDING);
        String wrote = ToolResult.success().put("action", "adopted").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = new StubTool(onRealWrite, null, wroteUnlessAlreadyAdopted)
            .awaitDiskExport(params(PROJECT), wrote);
        assertEquals("a real write MUST still be waited on", 1, onRealWrite.calls); //$NON-NLS-1$
        assertTrue("and a real write with a pending export must still be refused: " + answer, //$NON-NLS-1$
            answer.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testTheWaitFollowsTheProjectTheToolActuallyWroteTo()
    {
        // adopt_metadata_object takes the BASE configuration by contract and writes into the
        // EXTENSION. A barrier keyed on projectName would wait for a project with nothing queued
        // and pass while the real target is still exporting - so it must follow the result.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.DRAINED);
        String result = ToolResult.success().put("extensionProject", EXTENSION).toJson(); //$NON-NLS-1$

        new StubTool(environment, "extensionProject", r -> true).awaitDiskExport(params(PROJECT), result); //$NON-NLS-1$

        assertEquals("the barrier must wait for the project that was written, not the one asked for", //$NON-NLS-1$
            EXTENSION, environment.askedFor);
    }

    @Test
    public void testTheWaitFallsBackToProjectNameWhenTheDeclaredKeyIsAbsent()
    {
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.DRAINED);
        // Declared key, but this particular branch did not report it.
        String result = successJson();

        new StubTool(environment, "extensionProject", r -> true).awaitDiskExport(params(PROJECT), result); //$NON-NLS-1$

        assertEquals(PROJECT, environment.askedFor);
    }

    @Test
    public void testAnUnreadableResultIsNotTurnedIntoARefusal()
    {
        // A payload we cannot parse is not evidence of a disk problem, and the mutation already
        // happened: degrade to "do not gate", never to a refusal built on a guess.
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        String result = "not json at all"; //$NON-NLS-1$

        assertSame(result, new StubTool(environment).awaitDiskExport(params(PROJECT), result));
        assertEquals(0, environment.calls);
        assertNull(environment.askedFor);
    }

    @Test
    public void testAMissingProjectNameSkipsTheWaitInsteadOfRefusing()
    {
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        String result = successJson();

        assertSame(result, new StubTool(environment).awaitDiskExport(new HashMap<>(), result));
        assertEquals(0, environment.calls);
    }

    /** Overrides ONLY what the base class leaves abstract, plus the seam - nothing else. */
    private static final class InheritedDefaultsTool extends AbstractMetadataWriteTool
    {
        private final RecordingEnvironment environment;

        InheritedDefaultsTool(RecordingEnvironment environment)
        {
            this.environment = environment;
        }

        @Override
        IExportEnvironment exportEnvironment()
        {
            return environment;
        }

        @Override
        public String getName()
        {
            return "inherited_defaults_tool"; //$NON-NLS-1$
        }

        @Override
        public String getDescription()
        {
            return "stub"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{}"; //$NON-NLS-1$
        }

        @Override
        protected String executeOnUiThread(Map<String, String> params)
        {
            return ToolResult.success().toJson();
        }
    }

    @Test
    public void testAWriteToolThatOverridesNothingStillGetsTheBarrier()
    {
        // The reason the barrier lives in the base class rather than at the ~34 export call sites:
        // a tool added later inherits it without doing anything. This pins the two defaults that
        // make that true - "this call mutates" and "wait for projectName".
        RecordingEnvironment environment = new RecordingEnvironment(DiskExportState.PENDING);
        InheritedDefaultsTool plain = new InheritedDefaultsTool(environment);

        String answer = plain.awaitDiskExport(params(PROJECT), successJson());

        assertFalse("a tool that overrides nothing must still refuse on a pending export", //$NON-NLS-1$
            answer.contains("\"success\":true")); //$NON-NLS-1$
        assertEquals("the inherited default must consult the export wait exactly once", 1, //$NON-NLS-1$
            environment.calls);
        assertEquals("the inherited default must wait for projectName", PROJECT, //$NON-NLS-1$
            environment.askedFor);
    }
}
