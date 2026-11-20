/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.base.WriteExportBarrier;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.BuildUtils.DiskExportState;
import com.ditrix.edt.mcp.server.utils.DcsAddress;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.ExportRole;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.ExportTarget;
import com.google.gson.JsonParser;

/**
 * #662 review: one {@code dcs} write call shares one budget - its export barrier waits only what the
 * mutation left of {@link DcsTool#CALL_BUDGET_MS} (never below {@link DcsTool#MIN_EXPORT_BUDGET_MS}),
 * and the shared {@link WriteExportBarrier} keeps its 60 s deadline for every other tool.
 */
public class DcsCallBudgetTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$

    @Test
    public void testTheExportGetsWhatIsLeftOfTheCallBudgetButNeverBelowTheFloor()
    {
        assertEquals(45_000L, DcsTool.exportBudgetMs(1_000L, 1_000L));
        assertEquals(35_000L, DcsTool.exportBudgetMs(0L, 10_000L));
        assertEquals(DcsTool.MIN_EXPORT_BUDGET_MS, DcsTool.exportBudgetMs(0L, 41_000L));
        assertEquals(DcsTool.MIN_EXPORT_BUDGET_MS, DcsTool.exportBudgetMs(0L, 70_000L));
        assertEquals("a clock that went back never extends the budget", 45_000L, //$NON-NLS-1$
            DcsTool.exportBudgetMs(5_000L, 1_000L));
        assertEquals("a negative monotonic reading is an ordinary start time, not 'no budget'", 35_000L, //$NON-NLS-1$
            DcsTool.exportBudgetMs(-50_000L, -40_000L));
    }

    private static long dispatchedExportBudget(long startedAtMs)
    {
        WriteScope scope = new WriteScope();
        scope.wrote(PROJECT);
        AtomicReference<String> result = new AtomicReference<>("**Export scheduled:** `true`"); //$NON-NLS-1$
        List<Long> asked = new ArrayList<>();
        String response = DcsTool.dispatchResult("upsert", DcsAddress.parse("Report.Sales").address(), scope, //$NON-NLS-1$ //$NON-NLS-2$
            result, () -> { }, startedAtMs, (project, timeoutMs) -> {
                asked.add(Long.valueOf(timeoutMs));
                return DiskExportState.DRAINED;
            });
        assertTrue(response, response.endsWith("**Export drained:** `true`")); //$NON-NLS-1$
        assertEquals(1, asked.size());
        return asked.get(0).longValue();
    }

    @Test
    public void testTheDcsDispatchBoundsTheExportByWhatTheCallHasLeft()
    {
        long now = DcsTool.monotonicMs();
        long budget = dispatchedExportBudget(now - 30_000L);
        assertTrue("the export waits at most the 15 s the call has left, not the shared 60 s: " + budget, //$NON-NLS-1$
            budget <= 15_000L && budget > 13_000L);
        long floor = dispatchedExportBudget(now - 44_000L);
        assertTrue("a call whose mutation spent the budget still confirms its export: " + floor, //$NON-NLS-1$
            floor <= DcsTool.MIN_EXPORT_BUDGET_MS && floor > DcsTool.MIN_EXPORT_BUDGET_MS - 2_000L);
    }

    @Test
    public void testAPendingExportNamesTheBudgetItWasGiven()
    {
        WriteScope scope = new WriteScope();
        scope.wrote(PROJECT);
        String result = DcsTool.finalizeWriteResult(scope, "**Export scheduled:** `true`", //$NON-NLS-1$
            (project, timeoutMs) -> DiskExportState.PENDING, 12_000L);
        String error = JsonParser.parseString(result).getAsJsonObject().get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("within a 12s budget")); //$NON-NLS-1$
        assertTrue(JsonParser.parseString(result).getAsJsonObject().get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void testOtherToolsKeepTheSharedSixtySecondBarrier()
    {
        assertEquals(60_000L, WriteExportBarrier.DEADLINE_MS);
        assertTrue(WriteExportBarrier.notConfirmed(PROJECT).contains("within a 60s budget")); //$NON-NLS-1$
        assertEquals(WriteExportBarrier.notConfirmed(PROJECT),
            WriteExportBarrier.notConfirmed(PROJECT, WriteExportBarrier.DEADLINE_MS));
    }

    @Test
    public void testTheCallBudgetRunsOnTheMonotonicClock()
    {
        // A wall-clock change must neither grant nor take export or validation time.
        assertEquals(System.nanoTime() / 1_000_000L, DcsTool.monotonicMs(), 1_000L);
    }

    private static ExportTarget export(ExportRole role, String fqn) throws Exception
    {
        Constructor<ExportTarget> constructor = ExportTarget.class.getDeclaredConstructor(ExportRole.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(role, fqn);
    }

    @Test
    public void testASchemaWriteExportsTheContentItWroteNotTheDescriptorsOne() throws Exception
    {
        // The descriptor saw template A as the report's main schema; the write transaction wrote B.
        List<ExportTarget> targets = List.of(export(ExportRole.OWNER_TOP_OBJECT, "Report.Sales"), //$NON-NLS-1$
            export(ExportRole.DCS_CONTENT, "Report.Sales.Template.A.Template")); //$NON-NLS-1$
        assertEquals(List.of("Report.Sales", "Report.Sales.Template.B.Template"), //$NON-NLS-1$ //$NON-NLS-2$
            DcsTool.schemaWriteExports(targets, "Report.Sales.Template.B.Template")); //$NON-NLS-1$
        assertEquals(List.of("Report.Sales", "Report.Sales.Template.A.Template"), //$NON-NLS-1$ //$NON-NLS-2$
            DcsTool.schemaWriteExports(targets, "Report.Sales.Template.A.Template")); //$NON-NLS-1$
        assertEquals("a write that names no content keeps the resolved one", //$NON-NLS-1$
            List.of("Report.Sales", "Report.Sales.Template.A.Template"), //$NON-NLS-1$ //$NON-NLS-2$
            DcsTool.schemaWriteExports(targets, null));
        assertEquals("a report without a main schema exports the one the write created", //$NON-NLS-1$
            List.of("Report.Sales", "Report.Sales.Template.Main.Template"), //$NON-NLS-1$ //$NON-NLS-2$
            DcsTool.schemaWriteExports(List.of(export(ExportRole.OWNER_TOP_OBJECT, "Report.Sales"), //$NON-NLS-1$
                export(ExportRole.DCS_CONTENT, null)), "Report.Sales.Template.Main.Template")); //$NON-NLS-1$
    }
}