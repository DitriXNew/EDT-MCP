/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.impl.CreateMetadataTool;
import com.ditrix.edt.mcp.server.tools.impl.DeleteMetadataTool;
import com.ditrix.edt.mcp.server.tools.impl.ModifyMetadataTool;
import com.ditrix.edt.mcp.server.utils.BuildUtils.DiskExportState;
import com.ditrix.edt.mcp.server.utils.BuildUtils.ValidationState;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests the post-publish marker hook of {@link AbstractMetadataWriteTool} (issue #643): it runs only
 * for an opted-in tool, only on a success, only after the export barrier, only for the objects the
 * call exported in the projects it wrote, and it never turns a success into an error.
 */
public class AbstractMetadataWriteToolMarkersTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final String EXTENSION = "TestConfiguration.tests"; //$NON-NLS-1$

    /** One timeline for both seams, so the order of the export wait and the marker phase is visible. */
    private final List<String> timeline = new ArrayList<>();

    /** A marker platform that resolves everything and records what it was asked. */
    private final class RecordingMarkers implements WrittenObjectMarkers.Environment
    {
        final List<String> resolvedFor = new ArrayList<>();
        final List<List<String>> resolvedFqns = new ArrayList<>();
        RuntimeException failure;

        @Override
        public Map<String, Long> resolveTopObjects(String projectName, List<String> fqns, long timeoutMs)
        {
            timeline.add("resolve:" + projectName); //$NON-NLS-1$
            resolvedFor.add(projectName);
            resolvedFqns.add(new ArrayList<>(fqns));
            if (failure != null)
            {
                throw failure;
            }
            Map<String, Long> ids = new LinkedHashMap<>();
            long id = 1;
            for (String fqn : fqns)
            {
                ids.put(fqn, Long.valueOf(id++));
            }
            return ids;
        }

        @Override
        public ValidationState awaitValidation(String projectName, Collection<Long> topObjectIds, long timeoutMs)
        {
            timeline.add("validate:" + projectName); //$NON-NLS-1$
            return ValidationState.COMPLETE;
        }

        @Override
        public WrittenObjectMarkers.ProjectMarkers read(String projectName, Map<String, Long> topObjectIds,
            int maxRows, long timeoutMs)
        {
            return new WrittenObjectMarkers.ProjectMarkers(0, new ArrayList<>());
        }

        @Override
        public boolean checksDisabled()
        {
            return false;
        }
    }

    /** A write tool whose export and marker seams are both scripted. */
    private class StubTool extends AbstractMetadataWriteTool
    {
        private final DiskExportState exportState;
        private final boolean optedIn;
        final RecordingMarkers markers = new RecordingMarkers();

        StubTool(DiskExportState exportState, boolean optedIn)
        {
            this.exportState = exportState;
            this.optedIn = optedIn;
        }

        @Override
        protected IExportEnvironment exportEnvironment()
        {
            return (projectName, timeoutMs) -> {
                timeline.add("export:" + projectName); //$NON-NLS-1$
                return exportState;
            };
        }

        @Override
        protected boolean reportsWrittenObjectMarkers()
        {
            return optedIn;
        }

        @Override
        protected WrittenObjectMarkers.Environment markerEnvironment()
        {
            return markers;
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

    private static Map<String, String> params(String fqn)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("fqn", fqn); //$NON-NLS-1$
        return params;
    }

    private static String success()
    {
        return ToolResult.success().put("action", "created").put("message", "Created") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .toJson();
    }

    private static WriteScope exported(String project, String... fqns)
    {
        WriteScope scope = new WriteScope();
        scope.wrote(project);
        scope.exported(project, Arrays.asList(fqns));
        return scope;
    }

    private static JsonObject json(String answer)
    {
        return JsonParser.parseString(answer).getAsJsonObject();
    }

    @Test
    public void testAnOptedInSuccessGetsTheFieldsAfterTheExportBarrier()
    {
        StubTool tool = new StubTool(DiskExportState.DRAINED, true);

        JsonObject answer = json(tool.awaitDiskExport(params("Catalog.X"), success(), //$NON-NLS-1$
            exported(PROJECT, "Catalog.X", "Configuration"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(answer.get(WrittenObjectMarkers.KEY_INCOMPLETE).getAsBoolean());
        assertEquals(0, answer.get(WrittenObjectMarkers.KEY_COUNT).getAsInt());
        assertEquals(0, answer.getAsJsonArray(WrittenObjectMarkers.KEY_MARKERS).size());
        assertTrue("the tool's own members are kept", answer.has(WriteScope.RESULT_MEMBER)); //$NON-NLS-1$
        assertEquals("created", answer.get("action").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the marker phase runs only once the export barrier is behind it", //$NON-NLS-1$
            Arrays.asList("export:" + PROJECT, "resolve:" + PROJECT, "validate:" + PROJECT), timeline); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("an incidental Configuration is not part of the slice", //$NON-NLS-1$
            Collections.singletonList(Collections.singletonList("Catalog.X")), tool.markers.resolvedFqns); //$NON-NLS-1$
    }

    @Test
    public void testConfigurationIsReportedWhenItIsTheTarget()
    {
        StubTool tool = new StubTool(DiskExportState.DRAINED, true);

        tool.awaitDiskExport(params("Configuration"), success(), //$NON-NLS-1$
            exported(PROJECT, "Configuration")); //$NON-NLS-1$

        assertEquals(Collections.singletonList(Collections.singletonList("Configuration")), //$NON-NLS-1$
            tool.markers.resolvedFqns);
    }

    @Test
    public void testAnExportRefusalNeverReachesTheMarkerPhase()
    {
        StubTool tool = new StubTool(DiskExportState.PENDING, true);

        JsonObject answer = json(tool.awaitDiskExport(params("Catalog.X"), success(), //$NON-NLS-1$
            exported(PROJECT, "Catalog.X"))); //$NON-NLS-1$

        assertFalse(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(answer.has(WrittenObjectMarkers.KEY_INCOMPLETE));
        assertTrue("the marker environment must never be called on a refusal", //$NON-NLS-1$
            tool.markers.resolvedFor.isEmpty());
    }

    @Test
    public void testAnErrorNeverReachesTheMarkerPhase()
    {
        StubTool tool = new StubTool(DiskExportState.DRAINED, true);

        JsonObject answer = json(tool.awaitDiskExport(params("Catalog.X"), //$NON-NLS-1$
            ToolResult.error("Node not found: Catalog.X").toJson(), exported(PROJECT, "Catalog.X"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(answer.has(WrittenObjectMarkers.KEY_INCOMPLETE));
        assertTrue(tool.markers.resolvedFor.isEmpty());
    }

    @Test
    public void testAToolThatDoesNotOptInGetsNoFields()
    {
        StubTool tool = new StubTool(DiskExportState.DRAINED, false);

        JsonObject answer = json(tool.awaitDiskExport(params("Catalog.X"), success(), //$NON-NLS-1$
            exported(PROJECT, "Catalog.X"))); //$NON-NLS-1$

        assertFalse(answer.has(WrittenObjectMarkers.KEY_INCOMPLETE));
        assertFalse(answer.has(WrittenObjectMarkers.KEY_MARKERS));
        assertTrue(tool.markers.resolvedFor.isEmpty());
    }

    @Test
    public void testAWriteThatExportedNothingGetsNoFields()
    {
        StubTool tool = new StubTool(DiskExportState.DRAINED, true);
        WriteScope scope = new WriteScope();
        scope.wrote(PROJECT);

        JsonObject answer = json(tool.awaitDiskExport(params("Catalog.X"), success(), scope)); //$NON-NLS-1$

        assertFalse("an empty list with false would claim a validation that never happened", //$NON-NLS-1$
            answer.has(WrittenObjectMarkers.KEY_INCOMPLETE));
        assertTrue(tool.markers.resolvedFor.isEmpty());
    }

    @Test
    public void testCascadeProjectsAreNotReported()
    {
        StubTool tool = new StubTool(DiskExportState.DRAINED, true);
        WriteScope scope = exported(PROJECT, "Catalog.X"); //$NON-NLS-1$
        scope.cascadedInto(EXTENSION);
        scope.exported(EXTENSION, Collections.singletonList("Catalog.Adopted")); //$NON-NLS-1$

        tool.awaitDiskExport(params("Catalog.X"), success(), scope); //$NON-NLS-1$

        assertEquals("only the projects this call wrote are validated", //$NON-NLS-1$
            Collections.singletonList(PROJECT), tool.markers.resolvedFor);
    }

    @Test
    public void testAFailingMarkerReadKeepsTheSuccess()
    {
        StubTool tool = new StubTool(DiskExportState.DRAINED, true);
        tool.markers.failure = new IllegalStateException("model gone"); //$NON-NLS-1$

        JsonObject answer = json(tool.awaitDiskExport(params("Catalog.X"), success(), //$NON-NLS-1$
            exported(PROJECT, "Catalog.X"))); //$NON-NLS-1$

        assertTrue("a marker failure never turns a success into an error", //$NON-NLS-1$
            answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer.get(WrittenObjectMarkers.KEY_INCOMPLETE).getAsBoolean());
        assertEquals("readFailed", answer.get(WrittenObjectMarkers.KEY_REASON).getAsString()); //$NON-NLS-1$
    }

    @Test
    public void testTheScopeRecordsExportedTopObjectsPerProjectInOrder()
    {
        WriteScope scope = new WriteScope();
        scope.exported(PROJECT, Arrays.asList("Catalog.X", "Configuration")); //$NON-NLS-1$ //$NON-NLS-2$
        scope.exported(PROJECT, Arrays.asList("Catalog.X", null, "", "Catalog.X.Form.Item.Form")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(Arrays.asList("Catalog.X", "Configuration", "Catalog.X.Form.Item.Form"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            scope.exportedTopObjects(PROJECT));
        assertTrue(scope.exportedTopObjects(EXTENSION).isEmpty());
    }

    @Test
    public void testOnlyCreateAndModifyOptIn()
    {
        assertTrue(((AbstractMetadataWriteTool)new CreateMetadataTool()).reportsWrittenObjectMarkers());
        assertTrue(((AbstractMetadataWriteTool)new ModifyMetadataTool()).reportsWrittenObjectMarkers());
        assertFalse("delete reports referrers, not markers (#643 scope)", //$NON-NLS-1$
            ((AbstractMetadataWriteTool)new DeleteMetadataTool()).reportsWrittenObjectMarkers());
        assertFalse(new DeleteMetadataTool().getOutputSchema().contains(WrittenObjectMarkers.KEY_INCOMPLETE));
        for (String schema : Arrays.asList(new CreateMetadataTool().getOutputSchema(),
            new ModifyMetadataTool().getOutputSchema()))
        {
            for (String key : Arrays.asList("markersIncomplete", "markersIncompleteReason", "markerCount", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "markers")) //$NON-NLS-1$
            {
                assertTrue(key + " declared", schema.contains('"' + key + '"')); //$NON-NLS-1$
            }
        }
    }
}
