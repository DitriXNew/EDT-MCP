/** MCP Server for EDT - Tests. Copyright (C) 2026 DitriX. AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.ditrix.edt.mcp.server.tools.impl.GetProfilingResultsToolTest.line;
import static com.ditrix.edt.mcp.server.tools.impl.GetProfilingResultsToolTest.measurement;

import java.util.List;
import org.junit.Test;
import org.eclipse.core.resources.IProject;
import com._1c.g5.v8.dt.debug.model.base.data.BSLModuleIdInternal;
import com._1c.g5.v8.dt.profiling.core.ILineProfilingResult;
import com._1c.g5.v8.dt.profiling.core.compare.*;

public class ProfilingReportTest
{
    private static ILineProfilingResult unresolved(String label, String object)
    {
        var row = line(label, "<method not found>", 7, 3, 1234567, 1234567, "<line not found>");
        BSLModuleIdInternal id = mock(BSLModuleIdInternal.class);
        when(id.getObjectID()).thenReturn(object);
        when(id.getPropertyID()).thenReturn("property-1");
        when(row.getModuleID()).thenReturn(id);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("TestConfiguration.tests");
        when(row.getProject()).thenReturn(project);
        return row;
    }

    @Test public void unresolvedModulesHaveDistinctStableNamesInBothLocales()
    {
        var one = unresolved("<module not found>", "object-one");
        var two = unresolved("<модуль не найден>", "object-two");
        var result = measurement("Missing modules", 1, List.of(one, two,
            line("CommonModule.Код.Module", "Run()", 1, 1, 1000, 1000, "Run();")));
        var rows = ProfilingReport.rows(result);
        assertEquals("project=TestConfiguration.tests; objectId=object-one; propertyId=property-1", rows.get(0).module());
        assertEquals("n/a", rows.get(0).method());
        assertEquals("n/a", rows.get(0).source());
        assertEquals(3, ProfilingReport.rollups(rows, false).size());
        assertEquals(3, ProfilingReport.rollups(rows, true).size());
        assertEquals(1, ProfilingReport.filter(rows, "object-two", null, 1).size());
        String summary = ProfilingReport.summary(result, rows, rows, 10);
        assertTrue(summary, summary.contains("2 of 3 line records belong to modules not found in the workspace"));
        assertTrue(summary, summary.contains("object-one") && summary.contains("object-two") && summary.contains("CommonModule.Код.Module"));
        assertFalse(summary, summary.contains("<module not found>") || summary.contains("<модуль не найден>"));
    }

    @Test public void compareUsesStableModuleIdsInMethodAndLineRows()
    {
        var row = unresolved("<module not found>", "object-one");
        var result = measurement("Missing", 1, List.of(row));
        var moduleId = row.getModuleID();
        var project = row.getProject();
        ILineCompareResult comparedLine = mock(ILineCompareResult.class);
        when(comparedLine.getModuleName()).thenReturn("<module not found>");
        when(comparedLine.getModuleId()).thenReturn(moduleId);
        when(comparedLine.getProject()).thenReturn(project);
        when(comparedLine.getMethodName()).thenReturn("<method not found>");
        when(comparedLine.getLine()).thenReturn("<line not found>");
        when(comparedLine.getFrequency(CompareSide.BASE)).thenReturn(3L);
        when(comparedLine.getFrequency(CompareSide.COMPARED)).thenReturn(4L);
        IMethodCompareResult method = mock(IMethodCompareResult.class);
        when(method.getModuleName()).thenReturn("<module not found>");
        when(method.getModuleId()).thenReturn(moduleId);
        when(method.getProject()).thenReturn(project);
        when(method.getMethodName()).thenReturn("<method not found>");
        when(method.getPureTime(CompareSide.COMPARED)).thenReturn(2000000L);
        when(method.getLinesCompareResults()).thenReturn(List.of(comparedLine));
        ICompareResult comparison = mock(ICompareResult.class);
        when(comparison.getMethodsCompareResults()).thenReturn(List.of(method));
        String output = ProfilingReport.compare(result, result, comparison, "object-one", null, 1, 10, 0, 10);
        assertTrue(output, output.contains("objectId=object-one; propertyId=property-1 | n/a |"));
        assertTrue(output, output.contains("| 3 | 4 |"));
        assertFalse(output, output.contains("<module not found>") || output.contains("<method not found>") || output.contains("<line not found>"));
        assertFalse(output, output.contains("performance ticks") || output.contains("Durations"));
        assertTrue(output, output.contains("Total time sums inclusive line times; pure time is own time."));
        assertTrue(output, output.substring(output.indexOf("## Compared lines")).contains("\n\nn/a"));
        assertTrue(output, output.contains("not a defect of the measurement"));
    }

    @Test public void pageCountsAreSentencesAndOnlyOfferAnotherPageWhenNeeded()
    {
        assertEquals("Showing 1-2 of 2.\n\n", ProfilingReport.pageCount(0, 2, 2));
        assertEquals("Showing 1-1 of 2; next page: offset=1.\n\n", ProfilingReport.pageCount(0, 1, 2));
        assertEquals("Showing 2-2 of 2.\n\n", ProfilingReport.pageCount(1, 1, 2));
        assertEquals("Showing 0 of 0.\n\n", ProfilingReport.pageCount(0, 0, 0));
        assertEquals("Showing 0 of 2.\n\n", ProfilingReport.pageCount(Integer.MAX_VALUE, 0, 2));
    }

    @Test public void millisecondsUseThreeDecimalsAndPercentagesUseTwo()
    {
        assertEquals("1.235 ms", ProfilingReport.ms(1.234567));
        assertEquals("12.35", ProfilingReport.percentage(12.345));
        assertEquals("0.000 ms (0.00%)", ProfilingReport.change(0, 0));
        var measured = line("Module", "Run()", 1, 1, 1234567, 1234567, "Run();");
        when(measured.getPercentage()).thenReturn(12.345);
        when(measured.getPurePercentage()).thenReturn(4.567);
        String table = ProfilingReport.lineTable(ProfilingReport.rows(measurement("Numbers", 1, List.of(measured))), true);
        assertTrue(table, table.contains("| 1.235 | 1.235 | 1.235 | 12.35 | 4.57 |"));
    }
}
