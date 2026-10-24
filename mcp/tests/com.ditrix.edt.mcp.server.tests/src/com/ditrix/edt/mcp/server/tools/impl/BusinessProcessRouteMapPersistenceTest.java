/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BuildUtils.ValidationState;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Disk observations after the export barrier, independent of EDT's marker service. */
public class BusinessProcessRouteMapPersistenceTest
{
    private static final String OWNER = "BusinessProcess.Process";
    private static final String SCHEME = OWNER + ".Flowchart";
    private static final String FILE = "src/BusinessProcesses/Process/Flowchart.scheme";

    private IProject project;
    private Path file;
    private WrittenObjectMarkers.Environment markers;
    private BusinessProcessRouteMapTool tool;

    @Before
    public void setUp() throws Exception
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("RouteMapPersistence" + UUID.randomUUID());
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        file = project.getLocation().toFile().toPath().resolve(FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "committed scheme");
        markers = mock(WrittenObjectMarkers.Environment.class);
        when(markers.resolveTopObjects(anyString(), anyList(), anyLong()))
            .thenReturn(Map.of(OWNER, 1L, SCHEME, 2L));
        when(markers.awaitValidation(anyString(), anyCollection(), anyLong())).thenReturn(ValidationState.COMPLETE);
        when(markers.read(anyString(), anyMap(), anyInt(), anyLong()))
            .thenReturn(new WrittenObjectMarkers.ProjectMarkers(0, List.of()));
        tool = new BusinessProcessRouteMapTool()
        {
            @Override
            protected WrittenObjectMarkers.Environment markerEnvironment()
            {
                return markers;
            }
        };
    }

    @After
    public void tearDown() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
    }

    @Test
    public void testRemoveConfirmsDeletionEvenWhenDetachedSchemeExportWasFalse() throws Exception
    {
        Files.delete(file);
        JsonObject result = refresh("remove", false, project.getName(), true, true);
        assertTrue(result.toString(), result.get("persisted").getAsBoolean());
        assertFalse(result.get("routeMapExists").getAsBoolean());
        assertFalse(result.get("markersIncomplete").getAsBoolean());
        assertFalse(result.has("markersIncompleteReason"));
        verify(markers).resolveTopObjects(eq(project.getName()), eq(List.of(OWNER)), anyLong());
    }

    @Test
    public void testRemoveCannotClaimPersistenceWhileTheSchemeFileStillExists()
    {
        JsonObject result = refresh("remove", true, project.getName(), true, true);
        assertTrue(Files.exists(file));
        assertFalse(result.toString(), result.get("persisted").getAsBoolean());
    }

    @Test
    public void testRemoveCannotClaimPersistenceWhenTheProjectIsUnobservable()
    {
        JsonObject result = refresh("remove", true, "Missing" + UUID.randomUUID(), true, true);
        assertFalse(result.toString(), result.get("persisted").getAsBoolean());
    }

    @Test
    public void testRemoveCannotClaimPersistenceWithoutAResolvedFile()
    {
        JsonObject result = refresh("remove", true, project.getName(), true, false);
        assertFalse(result.toString(), result.get("persisted").getAsBoolean());
    }

    @Test
    public void testObservedAbsenceProvesRemovalEvenIfTheExportWaitWasUnobservable() throws Exception
    {
        Files.delete(file);
        JsonObject result = refresh("remove", false, project.getName(), false, true);
        assertTrue(result.toString(), result.get("persisted").getAsBoolean());
    }

    @Test
    public void testReplacementKeepsTheExportSubmissionResultAndBothMarkerSlices()
    {
        for (boolean submitted : new boolean[] {false, true})
        {
            JsonObject result = refresh("replace", submitted, project.getName(), true, true);
            assertEquals(submitted, result.get("persisted").getAsBoolean());
        }
        verify(markers, times(2)).resolveTopObjects(eq(project.getName()), eq(List.of(OWNER, SCHEME)), anyLong());
    }

    @Test
    public void testGetWithoutAWriteResultIsReturnedUnchanged()
    {
        String result = "{\"success\":true,\"action\":\"get\",\"routeMapExists\":false}";
        assertEquals(result, tool.refreshAfterExportAwait(Map.of("projectName", project.getName()), result, true));
        verifyZeroInteractions(markers);
    }

    private JsonObject refresh(String action, boolean submitted, String projectName, boolean drained,
        boolean includeFile)
    {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("action", action);
        result.addProperty("fqn", OWNER);
        result.addProperty("routeMapFqn", SCHEME);
        result.addProperty("routeMapExists", !action.equals("remove"));
        result.addProperty("persisted", submitted);
        if (includeFile)
        {
            result.addProperty("file", FILE);
        }
        return JsonParser.parseString(tool.refreshAfterExportAwait(Map.of("projectName", projectName),
            result.toString(), drained)).getAsJsonObject();
    }
}
