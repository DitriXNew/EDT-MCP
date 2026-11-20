/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.BuildUtils.ValidationState;
import com.ditrix.edt.mcp.server.utils.DcsAddress;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers;

/**
 * #662: a {@code dcs} write reads before it writes, and waits for EDT's validation of what it wrote.
 * <ul>
 * <li>every write into a root that already holds a schema needs {@code expectedHash}; only a write that
 * creates the schema needs none;</li>
 * <li>a write that got through its export barrier reports every marker of the schema it wrote, and an
 * unfinished validation is never reported as clean.</li>
 * </ul>
 */
public class DcsWriteValidationTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final String ROOT = "Report.Sales"; //$NON-NLS-1$
    private static final String CONTENT = "Report.Sales.Template.Main.Template"; //$NON-NLS-1$
    private static final String HASH = "0123456789abcdef0123"; //$NON-NLS-1$
    private static final String OTHER = "aaaaaaaaaaaaaaaaaaaa"; //$NON-NLS-1$

    private static DcsAddress address(String fqn)
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(fqn);
        assertTrue(parsed.isSuccess());
        return parsed.address();
    }

    @Test
    public void testAWriteIntoAnExistingSchemaWithoutAHashIsRefusedNamingTheReadAndTheCurrentHash()
    {
        DcsAddress target = address(ROOT + "#/parameters/Period"); //$NON-NLS-1$
        String refusal = DcsTool.hashRefusal(null, true, HASH, "update", target, ROOT, //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS);
        assertTrue(refusal, refusal.startsWith("Read the schema first: 'Report.Sales' already holds a DCS schema")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("action='update' at '" + target + "' requires expectedHash")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal, refusal.contains("Run dcs action='get' fqn='Report.Sales' type='schema'")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("current hash '" + HASH + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refusal, refusal.endsWith("nothing was written.")); //$NON-NLS-1$
    }

    @Test
    public void testTheRefusalNamesTheReadTypeOfEachRootKind()
    {
        String list = "Catalog.Products.Form.ListForm.Attribute.List"; //$NON-NLS-1$
        assertTrue(DcsTool.hashRefusal(null, true, HASH, "upsert", address(list), list, //$NON-NLS-1$
            TargetKind.DYNAMIC_LIST).contains("type='dynamicList'")); //$NON-NLS-1$
        String form = "Catalog.Products.Form.ListForm"; //$NON-NLS-1$
        assertTrue(DcsTool.hashRefusal(null, true, HASH, "upsert", address(form), form, //$NON-NLS-1$
            TargetKind.FORM).contains("type='conditionalAppearance'")); //$NON-NLS-1$
        String template = "CommonTemplate.Sales"; //$NON-NLS-1$
        assertTrue(DcsTool.hashRefusal(null, true, HASH, "upsert", address(template), template, //$NON-NLS-1$
            TargetKind.COMMON_TEMPLATE).contains("type='schema'")); //$NON-NLS-1$
    }

    @Test
    public void testAWriteThatCreatesTheSchemaNeedsNoHash()
    {
        assertNull(DcsTool.hashRefusal(null, false, HASH, "upsert", address(ROOT), ROOT, //$NON-NLS-1$
            TargetKind.REPORT_MAIN_DCS));
    }

    @Test
    public void testAGivenHashMustStillMatchWhetherOrNotTheRootExists()
    {
        for (boolean exists : new boolean[] { true, false })
        {
            assertNull(DcsTool.hashRefusal(HASH, exists, HASH, "upsert", address(ROOT), ROOT, //$NON-NLS-1$
                TargetKind.REPORT_MAIN_DCS));
            String stale = DcsTool.hashRefusal(OTHER, exists, HASH, "upsert", address(ROOT), ROOT, //$NON-NLS-1$
                TargetKind.REPORT_MAIN_DCS);
            assertEquals(DcsTool.expectedHashError(OTHER, HASH, address(ROOT)), stale);
        }
    }

    @Test
    public void testTheValidationBudgetIsWhatIsLeftOfTheCallBudgetButNeverBelowTheFloor()
    {
        assertEquals(45_000L, DcsTool.CALL_BUDGET_MS);
        assertEquals(45_000L, DcsTool.validationBudgetMs(1_000L, 1_000L));
        assertEquals(43_000L, DcsTool.validationBudgetMs(1_000L, 3_000L));
        assertEquals(DcsTool.MIN_VALIDATION_BUDGET_MS, DcsTool.validationBudgetMs(0L, 44_000L));
        assertEquals(DcsTool.MIN_VALIDATION_BUDGET_MS, DcsTool.validationBudgetMs(0L, 70_000L));
        assertEquals("a clock that went back never extends the budget", 45_000L, //$NON-NLS-1$
            DcsTool.validationBudgetMs(5_000L, 1_000L));
    }

    /** A scripted marker platform that records what it was asked. */
    private static final class Scripted implements WrittenObjectMarkers.Environment
    {
        ValidationState state = ValidationState.COMPLETE;
        final List<WrittenObjectMarkers.Row> rows = new ArrayList<>();
        final List<List<String>> resolved = new ArrayList<>();
        final List<Long> waitBudgets = new ArrayList<>();
        RuntimeException resolveFailure;
        int calls;

        @Override
        public Map<String, Long> resolveTopObjects(String projectName, List<String> fqns, long timeoutMs)
        {
            calls++;
            assertEquals(PROJECT, projectName);
            resolved.add(new ArrayList<>(fqns));
            if (resolveFailure != null)
            {
                throw resolveFailure;
            }
            Map<String, Long> ids = new LinkedHashMap<>();
            for (int i = 0; i < fqns.size(); i++)
            {
                ids.put(fqns.get(i), Long.valueOf(10 + i));
            }
            return ids;
        }

        @Override
        public ValidationState awaitValidation(String projectName, Collection<Long> topObjectIds, long timeoutMs)
        {
            calls++;
            waitBudgets.add(Long.valueOf(timeoutMs));
            return state;
        }

        @Override
        public WrittenObjectMarkers.ProjectMarkers read(String projectName, Map<String, Long> topObjectIds,
            int maxRows, long timeoutMs)
        {
            calls++;
            return new WrittenObjectMarkers.ProjectMarkers(rows.size(), new ArrayList<>(rows));
        }

        @Override
        public boolean checksDisabled()
        {
            return false;
        }
    }

    private static DcsTool.ValidationScope schema()
    {
        return new DcsTool.ValidationScope(PROJECT, List.of(CONTENT), List.of(ROOT), null);
    }

    @Test
    public void testASucceededWriteReportsEveryMarkerOfTheSchemaItWrote()
    {
        Scripted env = new Scripted();
        env.rows.add(new WrittenObjectMarkers.Row(CONTENT, MarkerSeverity.MINOR,
            "data-composition-variant-name-default", "The report variant name is not meaningful.", //$NON-NLS-1$ //$NON-NLS-2$
            CONTENT, false));
        String written = "**Action:** `upsert`\n\n**Export drained:** `true`"; //$NON-NLS-1$
        String result = DcsTool.appendValidation(written, schema(), env, 30_000L);

        assertTrue(result, result.startsWith(written + "\n\n**Validation:** `complete`\n\n**Markers:** `1`")); //$NON-NLS-1$
        assertTrue(result, result.contains(
            "| MINOR | data-composition-variant-name-default | The report variant name is not meaningful. | " //$NON-NLS-1$
                + CONTENT + " |")); //$NON-NLS-1$
        assertTrue(result, result.endsWith("**Validated objects:** `" + CONTENT + "`")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("only the schema's own top object is validated", List.of(List.of(CONTENT)), env.resolved); //$NON-NLS-1$
        assertTrue("the wait stays inside the budget", env.waitBudgets.get(0).longValue() <= 30_000L); //$NON-NLS-1$
    }

    @Test
    public void testAnUnfinishedValidationIsNeverCleanAndNamesTheRecheck()
    {
        Scripted env = new Scripted();
        env.state = ValidationState.PENDING;
        String result = DcsTool.appendValidation("**Export drained:** `true`", schema(), env, 5_000L); //$NON-NLS-1$

        assertTrue(result, result.contains("**Validation:** `incomplete` (validationPending)")); //$NON-NLS-1$
        assertTrue(result, result.contains("had not finished within 5s")); //$NON-NLS-1$
        assertTrue(result, result.contains("get_project_errors objectFqns=[" + ROOT + "]")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result, result.contains("no markers")); //$NON-NLS-1$
    }

    @Test
    public void testAFailedMarkerReadIsReportedIncompleteNotClean()
    {
        Scripted env = new Scripted();
        env.resolveFailure = new IllegalStateException("model gone"); //$NON-NLS-1$
        String result = DcsTool.appendValidation("**Export drained:** `true`", schema(), env, 5_000L); //$NON-NLS-1$
        assertTrue(result, result.contains("**Validation:** `incomplete` (readFailed)")); //$NON-NLS-1$
        assertFalse(result, result.contains("no markers")); //$NON-NLS-1$
    }

    @Test
    public void testAFailedOrUnconfirmedWriteAndAReadNeverWaitForValidation()
    {
        Scripted env = new Scripted();
        String refused = ToolResult.error("expectedHash 'x' does not match").toJson(); //$NON-NLS-1$
        assertEquals(refused, DcsTool.appendValidation(refused, schema(), env, 30_000L));
        String committedButNotExported = ToolResult.errorAfterMutation("export not confirmed").toJson(); //$NON-NLS-1$
        assertEquals(committedButNotExported,
            DcsTool.appendValidation(committedButNotExported, schema(), env, 30_000L));
        assertEquals("**Hash:** `" + HASH + "`", //$NON-NLS-1$ //$NON-NLS-2$
            DcsTool.appendValidation("**Hash:** `" + HASH + "`", null, env, 30_000L)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, env.calls);
    }

    @Test
    public void testAWriteThatLeftNoSchemaSaysSoInsteadOfClaimingNoProblems()
    {
        Scripted env = new Scripted();
        String result = DcsTool.appendValidation("**Export drained:** `true`", //$NON-NLS-1$
            new DcsTool.ValidationScope(PROJECT, List.of(), List.of("Catalog.Products.Form.ListForm"), null), //$NON-NLS-1$
            env, 30_000L);
        assertTrue(result, result.endsWith("**Validation:** `none` - this write left no DCS object in the model to validate.")); //$NON-NLS-1$
        assertEquals(0, env.calls);
    }

    @Test
    public void testADynamicListSaysItsMarkersAreTheFormsAndTheListSettings()
    {
        Scripted env = new Scripted();
        String form = "Catalog.Products.Form.ListForm.Form"; //$NON-NLS-1$
        String settings = form + ".Attributes.List.ExtInfo.ListSettings"; //$NON-NLS-1$
        String result = DcsTool.appendValidation("**Export drained:** `true`", //$NON-NLS-1$
            new DcsTool.ValidationScope(PROJECT, List.of(form, settings), List.of("Catalog.Products.Form.ListForm"), //$NON-NLS-1$
                "A dynamic list is validated with the form that holds it."), //$NON-NLS-1$
            env, 30_000L);
        assertEquals(List.of(List.of(form, settings)), env.resolved);
        assertTrue(result, result.endsWith("**Validated objects:** `" + form + ", " + settings //$NON-NLS-1$ //$NON-NLS-2$
            + "` A dynamic list is validated with the form that holds it.")); //$NON-NLS-1$
    }

    @Test
    public void testTheDescriptionCarriesTheReadFirstRuleAndTheValidationWait()
    {
        String description = new DcsTool().getDescription();
        assertTrue(description, description.contains("every write into an existing schema requires its hash")); //$NON-NLS-1$
        assertTrue(description, description.contains("validation problems")); //$NON-NLS-1$
        String guide = new DcsTool().getGuide();
        assertTrue(guide, guide.contains("Read the schema first")); //$NON-NLS-1$
        assertTrue(guide, guide.contains("### Validation after the write")); //$NON-NLS-1$
        assertTrue(guide, guide.contains("is never \"no problems\"")); //$NON-NLS-1$
    }
}
