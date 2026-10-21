/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool.CleanCollection;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool.ProjectCleanInfo;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool.RestartSupport;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter.ProjectRestartWaiter;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;
import com.ditrix.edt.mcp.server.utils.RestartJobFailureSignal;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Derived-data completion covers started siblings on normal and recovered restart paths. */
public class CleanProjectToolDependentSettleTest
{
    private static IDtProject project(String name)
    {
        IProject resource = mock(IProject.class);
        when(resource.getName()).thenReturn(name);
        when(resource.exists()).thenReturn(true);
        when(resource.isOpen()).thenReturn(true);
        IDtProject project = mock(IDtProject.class);
        when(project.getName()).thenReturn(name);
        when(project.getWorkspaceProject()).thenReturn(resource);
        return project;
    }

    private static final class Platform extends RestartSupport
    {
        final IDtProject target = project("Target");
        final IDtProject extension = project("Ext");
        final IDtProject external = project("ExtObj");
        final RestartJobFailureSignal failure = mock(RestartJobFailureSignal.class);
        final Map<IDtProject, Boolean> started = new LinkedHashMap<>();
        final Map<String, ProjectState> states = new LinkedHashMap<>();
        final List<String> derived = new ArrayList<>();
        final List<String> checked = new ArrayList<>();
        final List<String> recoveryRequests = new ArrayList<>();
        boolean recover;
        boolean recoverExtensionOnly;
        boolean lateRecovery;
        boolean allTargets;

        Platform()
        {
            super(null, null);
            when(failure.getFailure()).thenReturn("Project services restart failed");
        }

        @Override Collection<IDtProject> dependents(IDtProject project)
        {
            return project == target ? List.of(extension, external) : List.of();
        }
        @Override ProjectRestartWaiter prepare(IDtProject project) { return null; }
        @Override RestartJobFailureSignal watchFailures() { return failure; }
        @Override boolean isStarted(IDtProject project) { return started.getOrDefault(project, true); }
        @Override void awaitDerivedData(IProject project)
        {
            derived.add(project.getName());
            if (lateRecovery && project == target.getWorkspaceProject())
            {
                started.put(external, true);
            }
        }

        @Override
        ProjectStateResult stateOf(IProject project)
        {
            checked.add(project.getName());
            assertEquals("check readiness after its derived-data wait", project.getName(),
                derived.get(derived.size() - 1));
            ProjectState state = states.getOrDefault(project.getName(), ProjectState.READY);
            return new ProjectStateResult(state, state.getValue());
        }

        @Override Collection<IDtProject> recover(List<ProjectCleanInfo> projects, IProgressMonitor monitor)
        {
            List<IDtProject> issued = new ArrayList<>();
            for (ProjectCleanInfo project : projects)
            {
                recoveryRequests.add(project.name);
                if (recover || recoverExtensionOnly && project.dtProject == extension)
                {
                    started.put(project.dtProject, true);
                    issued.add(project.dtProject);
                }
            }
            return issued;
        }

        JsonObject run()
        {
            CleanCollection collection = new CleanCollection();
            for (IDtProject project : allTargets ? List.of(target, extension, external) : List.of(target))
            {
                collection.projectsToClean.add(new ProjectCleanInfo(project.getWorkspaceProject(), project,
                    project.getName()));
                collection.projectNamesList.add(project.getName());
            }
            return JsonParser.parseString(CleanProjectTool.runCleanLifecycle(collection, 5_000, 0,
                (info, monitor) -> {}, this)).getAsJsonObject();
        }
    }

    private static List<String> unsettled(JsonObject result)
    {
        assertTrue("every completed clean must declare dependent settlement", result.has("dependentsStillBuilding"));
        List<String> names = new ArrayList<>();
        result.getAsJsonArray("dependentsStillBuilding").forEach(value -> names.add(value.getAsString()));
        return names;
    }

    @Test
    public void awaitsTargetsThenBothKindsOfStartedDependents()
    {
        Platform platform = new Platform();
        JsonObject result = platform.run();
        assertTrue(result.get("success").getAsBoolean());
        assertEquals(List.of("Target", "Ext", "ExtObj"), platform.derived);
        assertEquals(List.of("Ext", "ExtObj"), platform.checked);
        assertEquals(List.of(), unsettled(result));
        assertEquals("Clean and revalidation completed.", result.get("message").getAsString());
    }

    @Test
    public void anUnsettledDependentIsNamedWithoutChangingTheSuccessVerdict()
    {
        Platform platform = new Platform();
        platform.states.put("ExtObj", ProjectState.BUILDING);
        JsonObject result = platform.run();
        assertTrue(result.get("success").getAsBoolean());
        assertEquals(List.of("ExtObj (building)"), unsettled(result));
        assertEquals("Clean and revalidation completed. Dependent project(s) restarted by this clean had "
            + "not settled when the wait ended: ExtObj (building). Wait for list_projects "
            + "to report them 'ready' before relying on their model.", result.get("message").getAsString());
    }

    @Test
    public void notAvailableAfterTheWaitIsAlsoUnsettled()
    {
        Platform platform = new Platform();
        platform.states.put("ExtObj", ProjectState.NOT_AVAILABLE);
        assertEquals(List.of("ExtObj (not_available)"), unsettled(platform.run()));
    }

    @Test
    public void aSiblingThatNeverStartedIsNeitherAwaitedNorNamedAsStillComputing()
    {
        Platform platform = new Platform();
        platform.started.put(platform.external, false);
        platform.states.put("ExtObj", ProjectState.BUILDING);
        JsonObject result = platform.run();
        assertTrue(result.get("success").getAsBoolean());
        assertEquals(List.of("Target", "Ext"), platform.derived);
        assertEquals(List.of("Ext"), platform.checked);
        assertEquals(List.of(), unsettled(result));
        assertFalse(result.get("message").getAsString().contains("had not settled"));
    }

    @Test
    public void recoveryAlsoWaitsAndNamesTheDependentThatHasNotSettled()
    {
        Platform platform = new Platform();
        when(platform.failure.hasFailed()).thenReturn(true);
        platform.started.put(platform.external, false);
        platform.recover = true;
        platform.states.put("ExtObj", ProjectState.BUILDING);
        JsonObject result = platform.run();
        assertTrue(result.get("success").getAsBoolean());
        assertEquals(List.of("ExtObj"), platform.recoveryRequests);
        assertEquals(List.of("Target", "Ext", "ExtObj"), platform.derived);
        assertEquals("ExtObj", result.getAsJsonArray("recovered").get(0).getAsString());
        assertEquals(List.of("ExtObj (building)"), unsettled(result));
        assertTrue(result.get("message").getAsString()
            .contains("had not settled when the wait ended: ExtObj (building)"));
    }

    @Test
    public void failedRecoverySkipsAStoppedSiblingButReportsAnUnsettledRecoveredOne()
    {
        Platform platform = new Platform();
        when(platform.failure.hasFailed()).thenReturn(true);
        platform.started.put(platform.extension, false);
        platform.started.put(platform.external, false);
        platform.recoverExtensionOnly = true;
        platform.states.put("Ext", ProjectState.BUILDING);
        JsonObject result = platform.run();
        assertFalse(result.get("success").getAsBoolean());
        assertEquals(List.of("Target", "Ext"), platform.derived);
        assertEquals(List.of("Ext"), platform.checked);
        assertEquals("ExtObj", result.getAsJsonArray("notRestarted").get(0).getAsString());
        assertEquals(List.of("Ext (building)"), unsettled(result));
        assertTrue(result.get("error").getAsString()
            .contains("had not settled when the wait ended: Ext (building)"));
    }

    @Test
    public void aDependentRecoveredDuringTheTargetWaitIsAwaitedToo()
    {
        Platform platform = new Platform();
        when(platform.failure.hasFailed()).thenReturn(true);
        platform.started.put(platform.external, false);
        platform.lateRecovery = true;
        JsonObject result = platform.run();
        assertTrue(result.get("success").getAsBoolean());
        assertEquals(List.of("Target", "Ext", "ExtObj"), platform.derived);
        assertEquals(List.of(), unsettled(result));
    }

    @Test
    public void cleanAllDoesNotDoubleWaitOrNameTargetsWhoseWaitExpired()
    {
        Platform platform = new Platform();
        platform.allTargets = true;
        platform.states.put("ExtObj", ProjectState.BUILDING);
        JsonObject result = platform.run();
        assertTrue(result.get("success").getAsBoolean());
        assertEquals(List.of("Target", "Ext", "ExtObj"), platform.derived);
        assertTrue(platform.checked.isEmpty());
        assertEquals(List.of(), unsettled(result));
    }

    /** The suffix must also be true for a dependent whose context is not up at all. */
    @Test
    public void aNotAvailableDependentIsDescribedAsNotSettled()
    {
        JsonObject result = JsonParser.parseString(CleanProjectTool.cleanCompletedResult(List.of("Target"),
            List.of(), List.of("ExtObj (not_available)"))).getAsJsonObject();
        assertTrue(result.get("success").getAsBoolean());
        assertEquals(List.of("ExtObj (not_available)"), unsettled(result));
        assertTrue(result.get("message").getAsString()
            .endsWith(" Dependent project(s) restarted by this clean had not settled when the wait ended: "
                + "ExtObj (not_available). Wait for list_projects to report them 'ready' before relying on "
                + "their model."));
    }

    @Test
    public void schemaAndGuideDeclareTheDependentWaitAndItsHonestyField()
    {
        CleanProjectTool tool = new CleanProjectTool();
        JsonObject field = JsonParser.parseString(tool.getOutputSchema()).getAsJsonObject()
            .getAsJsonObject("properties").getAsJsonObject("dependentsStillBuilding");
        assertNotNull(field);
        assertEquals("array", field.get("type").getAsString());
        assertEquals("string", field.getAsJsonObject("items").get("type").getAsString());
        assertEquals("Dependent projects restarted by this clean whose derived data had not settled when the wait ended.",
            field.get("description").getAsString());
        assertTrue(tool.getGuide().contains("dependentsStillBuilding"));
        assertTrue(tool.getGuide().contains("started dependents"));
    }
}
