/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.NullChange;
import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings.ParameterDef;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.rename.MetadataRenameService;
import com.ditrix.edt.mcp.server.tools.rename.RenameProgress;

/**
 * Tests for {@link RenameMetadataObjectTool}.
 * <p>
 * This is a cascade/destructive refactoring tool. The tests only exercise the
 * projectName/objectFqn/newName required-argument sentinels, which all return
 * (as {@link com.ditrix.edt.mcp.server.protocol.ToolResult#error} JSON payloads)
 * before {@code PlatformUI.getWorkbench()}
 * and before any refactoring is computed or applied — so no rename ever runs.
 * The actual cascade is covered by the E2E suite (and must be run on a test
 * configuration).
 * <p>
 * The cascade's DEADLINE is the exception: it is reachable through the
 * {@link RenameMetadataObjectTool.IRenameAction} seam, so the bound that keeps a wedged rename from
 * holding the MCP call open forever (issue #365) — and the phase-specific wording that tells the
 * caller whether the model may be half renamed — are tested here with a controllable action and no
 * live workbench.
 */
public class RenameMetadataObjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("rename_metadata_object", new RenameMetadataObjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(RenameMetadataObjectTool.NAME, new RenameMetadataObjectTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new RenameMetadataObjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new RenameMetadataObjectTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testFormElementRenamePropagatesDependentModelSettleRefusal()
    {
        AtomicBoolean settled = new AtomicBoolean(false);
        String settleError = "BM model is not available for project 'DemoExtension'."; //$NON-NLS-1$
        RenameMetadataObjectTool tool = new RenameMetadataObjectTool(
            (projectName, timeoutMs) ->
            {
                assertEquals("Demo", projectName); //$NON-NLS-1$
                settled.set(true);
                return settleError;
            });
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectFqn", "Catalog.Products.Form.ItemForm.Field.Price"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("newName", "Cost"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);

        assertTrue("the caller-thread settle must run before the workbench hand-off", settled.get()); //$NON-NLS-1$
        assertTrue("a dependent-model refusal must stop the form rename before the UI thread: " + result, //$NON-NLS-1$
            result.contains(settleError));
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new RenameMetadataObjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"newName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"disableIndices\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"expectedHash\"")); //$NON-NLS-1$
        assertTrue("the cascade bound must be reachable from the wire", //$NON-NLS-1$
            schema.contains("\"timeout\"")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        // The slim description must steer callers to the on-demand guide channel.
        String desc = new RenameMetadataObjectTool().getDescription();
        assertTrue(desc.contains("get_tool_guide('rename_metadata_object')")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        // The exhaustive detail moved out of description/schema into getGuide();
        // assert it is non-empty and still carries the key migrated topics.
        String guide = new RenameMetadataObjectTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("disableIndices")); //$NON-NLS-1$
        assertTrue(guide.contains("contentHash")); //$NON-NLS-1$
        assertTrue(guide.contains("expectedHash")); //$NON-NLS-1$
        assertTrue(guide.contains("Attribute")); //$NON-NLS-1$
        assertTrue(guide.contains("preview")); //$NON-NLS-1$
    }

    // ==================== Argument validation (returns before any rename) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new RenameMetadataObjectTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingObjectFqn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new RenameMetadataObjectTool().execute(params);
        assertTrue(result.contains("objectFqn is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingNewName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectFqn", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new RenameMetadataObjectTool().execute(params);
        assertTrue(result.contains("newName is required")); //$NON-NLS-1$
    }

    @Test
    public void testNonNumericDisableIndexIsRefusedBeforeAnythingRuns()
    {
        Map<String, String> params = validRenameParams();
        params.put("confirm", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("disableIndices", "abc"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new RenameMetadataObjectTool().execute(params);

        assertTrue(result.contains("could not be read as a change-point index")); //$NON-NLS-1$
        assertTrue(result.contains("Nothing was renamed")); //$NON-NLS-1$
        assertTrue(result.contains("preview")); //$NON-NLS-1$
    }

    @Test
    public void testNegativeDisableIndexIsRefusedBeforeAnythingRuns()
    {
        Map<String, String> params = validRenameParams();
        params.put("confirm", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("disableIndices", "-1"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new RenameMetadataObjectTool().execute(params);

        assertTrue(result.contains("index -1 below the first preview index (0)")); //$NON-NLS-1$
        assertTrue(result.contains("Nothing was renamed")); //$NON-NLS-1$
    }

    @Test
    public void testConfirmWithDisableIndicesRequiresExpectedHashBeforeAnythingRuns()
    {
        Map<String, String> params = validRenameParams();
        params.put("confirm", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("disableIndices", "0"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new RenameMetadataObjectTool().execute(params);

        assertTrue(result.contains("expectedHash is required")); //$NON-NLS-1$
        assertTrue(result.contains("contentHash")); //$NON-NLS-1$
        assertTrue(result.contains("Nothing was renamed")); //$NON-NLS-1$
    }

    private static Map<String, String> validRenameParams()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectFqn", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("newName", "Goods"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }

    // ================= Change-point numbering: the EXECUTE side of the walk only =================
    //
    // On execute, disableIndices is applied by walking the change tree with walkLeafChanges:
    // composites are recursed but never counted, and every leaf gets exactly one sequential
    // index in depth-first order. The tests below pin THAT walk, and nothing else.
    //
    // They do NOT prove the A2 contract (a preview #index maps back to the same leaf on
    // execute): the preview mirrors this numbering in its own walk, and these tests never run
    // it, so a preview-side drift passes right through them - which is exactly what happened
    // in issue #388 (the preview's fallback row took a SECOND index for a leaf that had
    // already taken one). The parity of the two walks is pinned where both are reachable:
    // MetadataRenameNumberingParityTest#testPreviewAndExecuteNumberTheSameLeavesIdentically.

    @Test
    public void testWalkLeafChangesNumbersLeavesDepthFirst()
    {
        CompositeChange root = new CompositeChange("root"); //$NON-NLS-1$
        CompositeChange mid = new CompositeChange("mid"); //$NON-NLS-1$
        Change a = new NullChange("a"); //$NON-NLS-1$
        Change b = new NullChange("b"); //$NON-NLS-1$
        Change c = new NullChange("c"); //$NON-NLS-1$
        Change d = new NullChange("d"); //$NON-NLS-1$
        mid.add(b);
        mid.add(c);
        root.add(a);
        root.add(mid);
        root.add(d);

        List<String> visitedNames = new ArrayList<>();
        List<Integer> visitedIndices = new ArrayList<>();
        int[] counter = {0};
        MetadataRenameService.walkLeafChanges(root, counter, (leaf, idx) -> {
            visitedNames.add(leaf.getName());
            visitedIndices.add(idx);
        });

        // Leaves only, depth-first; composites (root, mid) are not counted.
        assertEquals(List.of("a", "b", "c", "d"), visitedNames); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals(List.of(0, 1, 2, 3), visitedIndices);
        assertEquals(4, counter[0]);
    }

    @Test
    public void testWalkLeafChangesSingleLeafGetsIndexZero()
    {
        Change leaf = new NullChange("only"); //$NON-NLS-1$
        int[] counter = {0};
        List<Integer> indices = new ArrayList<>();
        MetadataRenameService.walkLeafChanges(leaf, counter, (c, idx) -> indices.add(idx));
        assertEquals(List.of(0), indices);
        assertEquals(1, counter[0]);
    }

    @Test
    public void testWalkLeafChangesEmptyCompositeCountsNothing()
    {
        CompositeChange empty = new CompositeChange("empty"); //$NON-NLS-1$
        int[] counter = {0};
        int[] visits = {0};
        MetadataRenameService.walkLeafChanges(empty, counter, (c, idx) -> visits[0]++);
        assertEquals(0, visits[0]);
        assertEquals(0, counter[0]);
    }

    // ==================== The cascade deadline (issue #365) ====================

    /**
     * Deadline for the wedged-rename tests. The deadline starts when the job is scheduled, so it
     * must stay well above the job-start latency — a job cancelled before it ran would never report
     * a phase, and the phase-specific assertions would have nothing to see.
     */
    private static final long SHORT_TIMEOUT_MS = 2000;

    /** Ceiling on the wedged action — finite, so a lost deadline fails instead of hanging the suite. */
    private static final long WEDGE_CEILING_MS = 60_000;

    /** A deliberately LARGER bound, so a hard-coded wait cannot pass for the requested one. */
    private static final long LONGER_TIMEOUT_MS = 8000;

    /** Bound that a bounded call must beat comfortably. */
    private static final long SANE_RETURN_MS = 30_000;

    /**
     * What a wedged action returns if it is ever allowed to finish. Distinctive on purpose: the
     * timeout error must not contain it, and a substring that could occur in ordinary English prose
     * would make that assertion pass or fail for the wrong reason.
     */
    private static final String WEDGED_PAYLOAD = "WEDGED-ACTION-PAYLOAD"; //$NON-NLS-1$

    @Test
    public void testWedgedRenameFailsOnItsDeadlineWithAnActionableError() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try
        {
            long startMs = System.currentTimeMillis();
            String error = RenameMetadataObjectTool.runRenameBounded("Catalog.Products", "Goods", //$NON-NLS-1$ //$NON-NLS-2$
                true, SHORT_TIMEOUT_MS, progress -> {
                    progress.enter(RenameProgress.Phase.PREPARING);
                    started.countDown();
                    awaitQuietly(release);
                    return WEDGED_PAYLOAD;
                });
            long elapsedMs = System.currentTimeMillis() - startMs;

            // Asserted first: a stalled scheduler must report as "the rename never started", not as
            // "the error text is wrong".
            assertTrue("the rename action must have started for this test to judge its message", //$NON-NLS-1$
                started.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));

            // Without the deadline this returns only when the action's own ceiling expires.
            assertTrue("a wedged rename must fail on its deadline, not on the action's ceiling " //$NON-NLS-1$
                + "(waited " + elapsedMs + "ms)", elapsedMs < SANE_RETURN_MS); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull("a missed deadline must produce an error payload", error); //$NON-NLS-1$
            assertFalse("the wedged action's own payload must NOT be reported as the answer: " //$NON-NLS-1$
                + error, error.contains(WEDGED_PAYLOAD));
            assertTrue("the error must say what did not finish: " + error, //$NON-NLS-1$
                error.contains("did not finish within")); //$NON-NLS-1$
            assertTrue("the error must name the object it was renaming: " + error, //$NON-NLS-1$
                error.contains("Catalog.Products")); //$NON-NLS-1$
            assertTrue("the error must name the requested new Name: " + error, //$NON-NLS-1$
                error.contains("Goods")); //$NON-NLS-1$
            assertTrue("the error must name the lever that raises the bound: " + error, //$NON-NLS-1$
                error.contains("timeout")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    /**
     * The safety-critical distinction. A rename that timed out BEFORE the cascade started rewriting
     * anything and one that timed out MID-cascade need opposite reactions from the caller, so the
     * error must not give them the same sentence: the second one has to say the configuration may
     * be partially renamed, because cancellation does not stop a rename already inside EDT.
     */
    @Test
    public void testTimeoutWhileApplyingWarnsThatTheModelMayBePartiallyRenamed() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch applying = new CountDownLatch(1);
        try
        {
            String error = RenameMetadataObjectTool.runRenameBounded("Catalog.Products", "Goods", //$NON-NLS-1$ //$NON-NLS-2$
                true, SHORT_TIMEOUT_MS, progress -> {
                    progress.enter(RenameProgress.Phase.PREPARING);
                    progress.enter(RenameProgress.Phase.APPLYING);
                    applying.countDown();
                    awaitQuietly(release);
                    return WEDGED_PAYLOAD;
                });

            assertTrue("the action must have reached the applying phase", //$NON-NLS-1$
                applying.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
            assertNotNull("a missed deadline must produce an error payload", error); //$NON-NLS-1$
            assertTrue("a timeout mid-cascade must warn that the model may be partially renamed: " //$NON-NLS-1$
                + error, error.contains("PARTIALLY renamed")); //$NON-NLS-1$
            assertTrue("it must name the way back to a consistent model: " + error, //$NON-NLS-1$
                error.contains("clean_project")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    /**
     * A timeout's recovery advice has to name a tool that can actually SHOW the target. It used to
     * say {@code get_metadata_objects} for everything, and that tool enumerates top-level metadata
     * COLLECTIONS: a managed-form element is in none of them (issue #381), and a MEMBER is not a
     * collection entry either. At the moment the caller must decide whether the old or the new name
     * now exists - right after a cascade that may have half-applied - being pointed at a listing
     * that cannot contain the target is what turns into a repeat of a destructive call.
     * <p>
     * All three target shapes are asserted, in both directions. Pinning only the form case would be
     * satisfied by advice switched over wholesale; pinning only the presence of the right tool name
     * would be satisfied by advice that pointed it at the wrong place.
     */
    @Test
    public void testTimeoutAdviceNamesAnInspectorThatCanSeeTheTarget() throws Exception
    {
        String formError = timeoutErrorFor("Catalog.Products.Form.ItemForm.Field.Price"); //$NON-NLS-1$
        assertTrue("a form element is not in any metadata collection, so the advice must send " //$NON-NLS-1$
            + "the caller to the form's own structure: " + formError, //$NON-NLS-1$
            formError.contains("get_metadata_details on its form")); //$NON-NLS-1$

        String memberError = timeoutErrorFor("Document.SalesOrder.Attribute.Amount"); //$NON-NLS-1$
        assertTrue("a member is not a collection entry either, so it must be pointed at its " //$NON-NLS-1$
            + "owner: " + memberError, memberError.contains("on its owner for a member")); //$NON-NLS-1$

        String objectError = timeoutErrorFor("Catalog.Products"); //$NON-NLS-1$
        assertTrue("a top object is answered by the same details tool: " + objectError, //$NON-NLS-1$
            objectError.contains("get_metadata_details")); //$NON-NLS-1$

        for (String error : new String[] {formError, memberError, objectError})
        {
            assertFalse("no branch may send the caller to the collection listing, which cannot " //$NON-NLS-1$
                + "show a form element, a member, or a top object of an unlisted type: " + error, //$NON-NLS-1$
                error.contains("get_metadata_objects")); //$NON-NLS-1$
        }
    }

    /** The same wedged-rename timeout as the tests above, for an arbitrary target FQN. */
    private static String timeoutErrorFor(String objectFqn) throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try
        {
            String error = RenameMetadataObjectTool.runRenameBounded(objectFqn, "Goods", //$NON-NLS-1$
                true, SHORT_TIMEOUT_MS, progress -> {
                    progress.enter(RenameProgress.Phase.PREPARING);
                    started.countDown();
                    awaitQuietly(release);
                    return WEDGED_PAYLOAD;
                });
            assertTrue("the action must have started for its message to be judged", //$NON-NLS-1$
                started.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
            assertNotNull("a missed deadline must produce an error payload", error); //$NON-NLS-1$
            return error;
        }
        finally
        {
            release.countDown();
        }
    }

    /**
     * The counterpart of the test above: a timeout while the refactoring was still being BUILT must
     * NOT claim the model may be half renamed. One message for both phases would either scare a
     * caller whose configuration is untouched or, far worse, reassure one whose configuration is not.
     */
    @Test
    public void testTimeoutWhilePreparingDoesNotClaimTheModelWasRewritten() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try
        {
            String error = RenameMetadataObjectTool.runRenameBounded("Catalog.Products", "Goods", //$NON-NLS-1$ //$NON-NLS-2$
                true, SHORT_TIMEOUT_MS, progress -> {
                    progress.enter(RenameProgress.Phase.PREPARING);
                    started.countDown();
                    awaitQuietly(release);
                    return WEDGED_PAYLOAD;
                });

            assertTrue("the action must have started", //$NON-NLS-1$
                started.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
            assertNotNull("a missed deadline must produce an error payload", error); //$NON-NLS-1$
            assertTrue("it must say the cascade had not started rewriting: " + error, //$NON-NLS-1$
                error.contains("had not started")); //$NON-NLS-1$
            assertFalse("it must not raise a partial-rename alarm for a cascade that never began: " //$NON-NLS-1$
                + error, error.contains("PARTIALLY renamed")); //$NON-NLS-1$
            assertTrue("it must still say the rename is not cancelled and may apply: " + error, //$NON-NLS-1$
                error.contains("may still apply")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testRenameThatFinishesReturnsItsOwnPayloadUnchanged()
    {
        String payload = "# Rename Completed"; //$NON-NLS-1$
        String result = RenameMetadataObjectTool.runRenameBounded("Catalog.Products", "Goods", //$NON-NLS-1$ //$NON-NLS-2$
            true, WEDGE_CEILING_MS, progress -> {
                progress.enter(RenameProgress.Phase.APPLIED);
                return payload;
            });

        assertEquals("a completed rename must return the service's own report", payload, result); //$NON-NLS-1$
    }

    @Test
    public void testHandOffFailureIsReportedWithItsOwnMessage()
    {
        String error = RenameMetadataObjectTool.runRenameBounded("Catalog.Products", "Goods", //$NON-NLS-1$ //$NON-NLS-2$
            true, WEDGE_CEILING_MS, progress -> {
                throw new IllegalStateException("workbench has not been created"); //$NON-NLS-1$
            });

        assertNotNull("a failed hand-off must produce an error payload", error); //$NON-NLS-1$
        assertTrue("the platform's own message must survive: " + error, //$NON-NLS-1$
            error.contains("workbench has not been created")); //$NON-NLS-1$
        assertTrue("a real failure must not be dressed up as a timeout: " + error, //$NON-NLS-1$
            !error.contains("did not finish within")); //$NON-NLS-1$
    }

    /**
     * The configurable default and the tool's accepted range are two statements of one rule.
     * Pinned in both directions so moving either one alone reddens.
     */
    @Test
    public void testConfigurableTimeoutMatchesTheToolsOwnBounds()
    {
        List<ParameterDef> params =
            ToolParameterSettings.getInstance().getParametersForTool(RenameMetadataObjectTool.NAME);
        assertEquals("rename_metadata_object publishes exactly one configurable parameter", //$NON-NLS-1$
            1, params.size());
        ParameterDef timeout = params.get(0);
        assertEquals(RenameMetadataObjectTool.KEY_TIMEOUT, timeout.getName());
        assertEquals("the configured default must be the tool's own default", //$NON-NLS-1$
            RenameMetadataObjectTool.DEFAULT_RENAME_TIMEOUT_SECONDS, timeout.getDefaultValue());

        assertEquals("the settings minimum must be the smallest value the tool accepts", //$NON-NLS-1$
            timeout.getMinValue(), RenameMetadataObjectTool.clampTimeoutSeconds(timeout.getMinValue()));
        assertEquals("anything below it is raised to the minimum", //$NON-NLS-1$
            timeout.getMinValue(), RenameMetadataObjectTool.clampTimeoutSeconds(timeout.getMinValue() - 1));
        assertEquals("the settings maximum must be the largest value the tool accepts", //$NON-NLS-1$
            timeout.getMaxValue(), RenameMetadataObjectTool.clampTimeoutSeconds(timeout.getMaxValue()));
        assertEquals("anything above it is lowered to the maximum", //$NON-NLS-1$
            timeout.getMaxValue(), RenameMetadataObjectTool.clampTimeoutSeconds(timeout.getMaxValue() + 1));
    }

    /**
     * The default must clear the worst LEGITIMATE wait on record — #320's 301-second rename, which
     * completed successfully. A bound below that would report a rename as failed while EDT went on
     * to apply it, which is the one outcome worse than the hang this bound replaces.
     */
    @Test
    public void testDefaultTimeoutClearsTheWorstMeasuredLegitimateWait()
    {
        assertTrue("the default bound must exceed the measured 301s rename (was " //$NON-NLS-1$
            + RenameMetadataObjectTool.DEFAULT_RENAME_TIMEOUT_SECONDS + "s)", //$NON-NLS-1$
            RenameMetadataObjectTool.DEFAULT_RENAME_TIMEOUT_SECONDS > 301);
    }

    /**
     * The wire parameter must actually be READ. A tool that declared {@code timeout} in its schema
     * but never parsed it would pass every other test here — this one fails.
     *
     * <p>Honest limit: this pins the resolver, not the wiring. That {@code execute()} hands the
     * resolved value to {@code runRenameBounded} cannot be exercised here, because everything after
     * the argument guards needs a live workbench — the e2e suite only proves the parameter is
     * ACCEPTED on the wire, not that it is the number that expires.
     */
    @Test
    public void testWireTimeoutIsTheOneThatBoundsTheRename()
    {
        Map<String, String> params = new HashMap<>();
        params.put(RenameMetadataObjectTool.KEY_TIMEOUT, "600"); //$NON-NLS-1$
        assertEquals("an explicit timeout must be the bound that is used", //$NON-NLS-1$
            600_000L, RenameMetadataObjectTool.resolveRenameTimeoutMs(params));

        assertEquals("no argument falls back to the configured default", //$NON-NLS-1$
            RenameMetadataObjectTool.DEFAULT_RENAME_TIMEOUT_SECONDS * 1000L,
            RenameMetadataObjectTool.resolveRenameTimeoutMs(new HashMap<>()));
    }

    /**
     * Out-of-range values are clamped, not rejected (the same contract as clean_project), and the
     * schema says so. Pinned here so a silent change of that contract is visible.
     */
    @Test
    public void testOutOfRangeWireTimeoutIsClampedIntoTheAcceptedRange()
    {
        List<ParameterDef> params =
            ToolParameterSettings.getInstance().getParametersForTool(RenameMetadataObjectTool.NAME);
        ParameterDef def = params.get(0);

        Map<String, String> tooSmall = new HashMap<>();
        tooSmall.put(RenameMetadataObjectTool.KEY_TIMEOUT, String.valueOf(def.getMinValue() - 1));
        assertEquals("a value below the range is raised to the minimum, not rejected", //$NON-NLS-1$
            def.getMinValue() * 1000L, RenameMetadataObjectTool.resolveRenameTimeoutMs(tooSmall));

        Map<String, String> tooLarge = new HashMap<>();
        tooLarge.put(RenameMetadataObjectTool.KEY_TIMEOUT, String.valueOf(def.getMaxValue() + 1));
        assertEquals("a value above the range is lowered to the maximum, not rejected", //$NON-NLS-1$
            def.getMaxValue() * 1000L, RenameMetadataObjectTool.resolveRenameTimeoutMs(tooLarge));

        assertTrue("the schema must not promise rejection when the tool clamps", //$NON-NLS-1$
            new RenameMetadataObjectTool().getInputSchema().contains("clamped")); //$NON-NLS-1$
    }

    /** The guide must carry the new parameter and what a timeout leaves the model in. */
    @Test
    public void testGuideDocumentsTheTimeoutAndItsConsequences()
    {
        String guide = new RenameMetadataObjectTool().getGuide();
        assertTrue("the guide must document the timeout parameter", guide.contains("timeout")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the guide must say a timeout can leave a PARTIALLY renamed configuration", //$NON-NLS-1$
            guide.contains("PARTIALLY renamed")); //$NON-NLS-1$
    }

    /**
     * A PREVIEW cannot rename anything, whatever phase it timed out in — so its error must not
     * carry the "may still apply" warning the execute path needs. Without the confirm flag the
     * phase alone would say PREPARING for both, and a preview timeout would raise a false alarm
     * about a rename that can never happen.
     */
    @Test
    public void testTimedOutPreviewIsNotReportedAsARenameThatMayStillApply() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try
        {
            String error = RenameMetadataObjectTool.runRenameBounded("Catalog.Products", "Goods", //$NON-NLS-1$ //$NON-NLS-2$
                false, SHORT_TIMEOUT_MS, progress -> {
                    progress.enter(RenameProgress.Phase.PREPARING);
                    started.countDown();
                    awaitQuietly(release);
                    return WEDGED_PAYLOAD;
                });

            assertTrue("the action must have started", //$NON-NLS-1$
                started.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
            assertNotNull("a missed deadline must produce an error payload", error); //$NON-NLS-1$
            assertTrue("a preview timeout must say it was a preview: " + error, //$NON-NLS-1$
                error.contains("PREVIEW")); //$NON-NLS-1$
            assertFalse("a preview cannot apply anything, so it must not warn that it may: " //$NON-NLS-1$
                + error, error.contains("may still apply")); //$NON-NLS-1$
            assertFalse("a preview can never leave a partial rename: " + error, //$NON-NLS-1$
                error.contains("PARTIALLY renamed")); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    /**
     * The REQUESTED bound must be the one that expires. Every other test here would still pass if
     * the code ignored its argument and hard-coded some fixed wait below {@link #SANE_RETURN_MS},
     * so this one runs the SAME wedged action twice with different bounds and compares: a
     * hard-coded wait cannot make the second run take measurably longer than the first.
     */
    @Test
    public void testTheRequestedBoundIsTheOneThatExpires() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            long shortMs = elapsedForWedgedRename(SHORT_TIMEOUT_MS, release);
            long longMs = elapsedForWedgedRename(LONGER_TIMEOUT_MS, release);

            assertTrue("a larger bound must actually wait longer (short=" + shortMs //$NON-NLS-1$
                + "ms, long=" + longMs + "ms)", //$NON-NLS-1$ //$NON-NLS-2$
                longMs - shortMs >= (LONGER_TIMEOUT_MS - SHORT_TIMEOUT_MS) / 2);
        }
        finally
        {
            release.countDown();
        }
    }

    /**
     * Runs a wedged rename under {@code timeoutMs} and returns how long the CALL took.
     *
     * @param timeoutMs the bound to request
     * @param release the shared latch the caller releases when the test is done
     * @return wall-clock milliseconds the bounded call took
     */
    private static long elapsedForWedgedRename(long timeoutMs, CountDownLatch release)
    {
        long startMs = System.currentTimeMillis();
        String error = RenameMetadataObjectTool.runRenameBounded("Catalog.Products", "Goods", //$NON-NLS-1$ //$NON-NLS-2$
            true, timeoutMs, progress -> {
                progress.enter(RenameProgress.Phase.PREPARING);
                awaitQuietly(release);
                return WEDGED_PAYLOAD;
            });
        long elapsedMs = System.currentTimeMillis() - startMs;
        assertNotNull("the wedged run must have timed out, not returned a payload", error); //$NON-NLS-1$
        assertTrue("the wedged run must have timed out: " + error, //$NON-NLS-1$
            error.contains("did not finish within")); //$NON-NLS-1$
        return elapsedMs;
    }

    /**
     * A rename the deadline caught while it was still QUEUED never ran, and cancelling it is what
     * kept it from running — so the error must say the model is UNTOUCHED, not that the rename
     * "may still apply". That sentence is what an agent uses to decide whether to go inspecting
     * (or re-renaming) a configuration nothing ever touched.
     *
     * <p>The job is held before the work by an {@code aboutToRun} listener that puts THIS job (by
     * name) to sleep — the platform's own way of refusing a start, and the same lever
     * {@code BoundedJobTest} uses.
     */
    @Test
    public void testRenameThatNeverStartedSaysTheModelIsUntouched()
    {
        // A UNIQUE target, so the name-matching listener below cannot reach into a foreign job:
        // the job name is derived from the FQN, and 'Catalog.Products' is a value other tests (and
        // a real EDT) genuinely use — putting THAT job to sleep would wedge something else.
        String objectFqn = "Catalog.NeverStarted" + System.nanoTime(); //$NON-NLS-1$
        String jobName = RenameMetadataObjectTool.NAME + ": " + objectFqn; //$NON-NLS-1$
        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicBoolean held = new AtomicBoolean(false);
        IJobChangeListener sleeper = new JobChangeAdapter()
        {
            @Override
            public void aboutToRun(IJobChangeEvent event)
            {
                if (jobName.equals(event.getJob().getName()))
                {
                    held.set(event.getJob().sleep());
                }
            }
        };
        Job.getJobManager().addJobChangeListener(sleeper);
        String error;
        try
        {
            error = RenameMetadataObjectTool.runRenameBounded(objectFqn, "Goods", //$NON-NLS-1$
                true, SHORT_TIMEOUT_MS, progress -> {
                    ran.set(true);
                    progress.enter(RenameProgress.Phase.APPLYING);
                    return WEDGED_PAYLOAD;
                });
        }
        finally
        {
            Job.getJobManager().removeJobChangeListener(sleeper);
        }

        // Asserted first, and about the LISTENER rather than only the effect: an ambient scheduler
        // stall would also leave the action unrun, and the test would pass without ever producing
        // the scenario it claims to judge.
        assertTrue("this test must be the reason the rename was held, not ambient scheduler luck", //$NON-NLS-1$
            held.get());
        assertFalse("the rename must have been held before it started", ran.get()); //$NON-NLS-1$
        assertNotNull("a rename that never started must still answer", error); //$NON-NLS-1$
        assertTrue("it must say the rename did not START: " + error, //$NON-NLS-1$
            error.contains("did not START")); //$NON-NLS-1$
        assertTrue("it must say the model is untouched: " + error, //$NON-NLS-1$
            error.contains("NOTHING was renamed and the model is untouched")); //$NON-NLS-1$
        assertFalse("a rename that our own cancel stopped must NOT be advertised as one that may " //$NON-NLS-1$
            + "still apply: " + error, error.contains("may still apply")); //$NON-NLS-1$
    }

    /**
     * Blocks until released or the wedge ceiling expires — the stand-in for a rename that stopped
     * making progress. The ceiling is what keeps THIS test finite: with the deadline removed the
     * call returns here instead of hanging the suite, and the assertions fail honestly.
     *
     * @param release the latch the test releases in its finally block
     */
    private static void awaitQuietly(CountDownLatch release)
    {
        try
        {
            release.await(WEDGE_CEILING_MS, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
