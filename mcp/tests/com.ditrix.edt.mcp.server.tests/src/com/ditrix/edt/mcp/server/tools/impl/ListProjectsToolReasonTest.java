/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.NotReadyReason;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;

/** The reason is optional JSON and the last, escaped column of the human table. */
public class ListProjectsToolReasonTest
{
    @Test
    public void aReadyRowCarriesNoReasonKeyAndAnEmptyCell()
    {
        Map<String, Object> row = new LinkedHashMap<>();
        ProjectStateResult ready = new ProjectStateResult(ProjectState.READY, "Project is ready");
        ListProjectsTool.putReason(row, ready);
        assertFalse(row.containsKey("reason"));
        assertEquals("", ListProjectsTool.reasonCell(ready));
    }

    @Test
    public void aNotReadyRowCarriesTheRawMessageInJson()
    {
        Map<String, Object> row = new LinkedHashMap<>();
        ProjectStateResult result = new ProjectStateResult(ProjectState.NOT_AVAILABLE,
            NotReadyReason.CONTEXT_NOT_STARTED, "a|b\r\nc");
        ListProjectsTool.putReason(row, result);
        assertEquals("a|b\r\nc", row.get("reason"));
    }

    @Test
    public void aNotReadyRowCarriesTheMessageEscapedForTheTable()
    {
        ProjectStateResult result = new ProjectStateResult(ProjectState.NOT_AVAILABLE,
            NotReadyReason.CONTEXT_NOT_STARTED, "a|b\r\nc");
        assertEquals(MarkdownUtils.escapeForTable("a|b\r\nc"), ListProjectsTool.reasonCell(result));
    }

    @Test
    public void buildingAndUnknownRowsAlsoCarryTheirReasons()
    {
        for (ProjectState state : new ProjectState[] { ProjectState.BUILDING, ProjectState.UNKNOWN })
        {
            Map<String, Object> row = new LinkedHashMap<>();
            ProjectStateResult result = new ProjectStateResult(state, "Explanation");
            ListProjectsTool.putReason(row, result);
            assertEquals("Explanation", row.get("reason"));
            assertEquals("Explanation", ListProjectsTool.reasonCell(result));
        }
    }

    @Test
    public void theReasonColumnIsAppendedLast()
    {
        assertEquals("| Name | State | Path | Open | EDT Project | Natures | Reason |",
            ListProjectsTool.TABLE_HEADER);
    }

    @Test
    public void theRenderedRowKeepsReasonAfterAllSixExistingCells()
    {
        ProjectStateResult result = new ProjectStateResult(ProjectState.NOT_AVAILABLE,
            NotReadyReason.CONTEXT_NOT_STARTED, "Context needs recovery");
        assertEquals("| Demo | not_available | /workspace/Demo | Yes | Yes | V8ConfigurationNature | "
            + "Context needs recovery |\n",
            ListProjectsTool.tableRow("Demo", result, "/workspace/Demo", true, "Yes", "V8ConfigurationNature"));
    }

    @Test
    public void theRenderedReadyRowHasAnEmptySeventhCell()
    {
        ProjectStateResult ready = new ProjectStateResult(ProjectState.READY, "Project is ready");
        assertEquals("| Demo | ready | /Demo | No | - | - |  |\n",
            ListProjectsTool.tableRow("Demo", ready, "/Demo", false, "-", "-"));
    }

    @Test
    public void theRenderedRowEscapesTheActualReasonCell()
    {
        ProjectStateResult result = new ProjectStateResult(ProjectState.NOT_AVAILABLE,
            NotReadyReason.CONTEXT_NOT_STARTED, "a|b\r\nc");
        String row = ListProjectsTool.tableRow("Demo", result, "/Demo", true, "Yes", "Nature");
        assertEquals("| Demo | not_available | /Demo | Yes | Yes | Nature | "
            + MarkdownUtils.escapeForTable("a|b\r\nc") + " |\n", row);
    }

    @Test
    public void theSeparatorHasTheSameSevenColumnsAsTheHeaderAndRows()
    {
        assertEquals(7, ListProjectsTool.TABLE_SEPARATOR.split("\\|", -1).length - 2);
        assertEquals(7, ListProjectsTool.TABLE_HEADER.split("\\|", -1).length - 2);
    }

    @Test
    public void aBusyStartingContextShowsBuildingWithTheStartingContextReason()
    {
        ProjectStateResult result = new ProjectStateResult(ProjectState.BUILDING,
            NotReadyReason.CONTEXT_STARTING, "Project is building: stage SYNC", "EDT is starting it");
        Map<String, Object> row = new LinkedHashMap<>();
        ListProjectsTool.putReason(row, result);
        assertEquals("Project is building: stage SYNC. EDT is starting it", row.get("reason"));
        assertEquals("| Demo | building | /Demo | Yes | Yes | Nature | "
            + "Project is building: stage SYNC. EDT is starting it |\n",
            ListProjectsTool.tableRow("Demo", result, "/Demo", true, "Yes", "Nature"));
    }
}
