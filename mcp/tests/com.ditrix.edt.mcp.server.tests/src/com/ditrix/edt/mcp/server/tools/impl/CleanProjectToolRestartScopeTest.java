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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

import com._1c.g5.v8.dt.core.lifecycle.ProjectStartType;
import com._1c.g5.v8.dt.core.lifecycle.WorkspaceProjectStartRequest;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IWorkspaceOrchestrator;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool.CleanCollection;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool.ProjectCleanInfo;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool.RestartSupport;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter.ProjectRestartWaiter;
import com.ditrix.edt.mcp.server.utils.RestartJobFailureSignal;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Drives the same phases as production with a failed platform restart and controllable recovery. */
public class CleanProjectToolRestartScopeTest
{
    private static IDtProject project(String name)
    {
        IProject resource = mock(IProject.class);
        when(resource.getName()).thenReturn(name);
        when(resource.exists()).thenReturn(true);
        when(resource.isOpen()).thenReturn(true);
        IDtProject dt = mock(IDtProject.class);
        when(dt.getName()).thenReturn(name);
        when(dt.getWorkspaceProject()).thenReturn(resource);
        return dt;
    }

    private static ProjectCleanInfo info(IDtProject project)
    {
        return new ProjectCleanInfo(project.getWorkspaceProject(), project, project.getName());
    }

    private static CleanCollection collection(IDtProject... targets)
    {
        CleanCollection collection = new CleanCollection();
        for (IDtProject target : targets)
        {
            collection.projectsToClean.add(info(target));
            collection.projectNamesList.add(target.getName());
        }
        return collection;
    }

    private static JsonObject parse(String result)
    {
        return JsonParser.parseString(result).getAsJsonObject();
    }

    private static final class Platform extends RestartSupport
    {
        final RestartJobFailureSignal failure = mock(RestartJobFailureSignal.class);
        final Map<IDtProject, List<IDtProject>> dependents = new LinkedHashMap<>();
        final Map<IDtProject, Boolean> started = new LinkedHashMap<>();
        final Map<IDtProject, ProjectRestartWaiter> waiters = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        final List<String> recoveryRequests = new ArrayList<>();
        final List<String> derived = new ArrayList<>();
        final List<String> recoveryFailures = new ArrayList<>();
        Runnable onDerived = () -> {};
        boolean recover;

        Platform()
        {
            super(null, null);
            when(failure.getFailure()).thenReturn("INITIALIZATION: Broken: Cannot find /Broken/DT-INF/PROJECT.PMF");
        }

        @Override Collection<IDtProject> dependents(IDtProject target)
        {
            return dependents.getOrDefault(target, List.of());
        }

        @Override ProjectRestartWaiter prepare(IDtProject project)
        {
            events.add("prepare:" + project.getName());
            ProjectRestartWaiter waiter = mock(ProjectRestartWaiter.class);
            waiters.put(project, waiter);
            return waiter;
        }

        @Override RestartJobFailureSignal watchFailures()
        {
            events.add("watch");
            return failure;
        }

        @Override boolean isStarted(IDtProject project)
        {
            return started.getOrDefault(project, true);
        }

        @Override Collection<IDtProject> recover(List<ProjectCleanInfo> projects, IProgressMonitor monitor)
        {
            List<IDtProject> issued = new ArrayList<>();
            for (ProjectCleanInfo project : projects)
            {
                recoveryRequests.add(project.name);
                if (recover && !recoveryFailures.contains(project.name))
                {
                    started.put(project.dtProject, true);
                    issued.add(project.dtProject);
                }
            }
            return issued;
        }

        @Override boolean awaitStarted(IDtProject project, long timeoutMs)
        {
            throw new AssertionError("synchronous recovered projects must already be started");
        }

        @Override void awaitDerivedData(IProject project)
        {
            derived.add(project.getName());
            onDerived.run();
        }

        void cleanedUp()
        {
            verify(failure).close();
            for (ProjectRestartWaiter waiter : waiters.values())
            {
                verify(waiter).cleanup();
            }
        }
    }

    private static JsonObject run(CleanCollection collection, Platform platform)
    {
        return parse(CleanProjectTool.runCleanLifecycle(collection, 5_000, 60_000,
            (info, monitor) -> platform.events.add("clean:" + info.name), platform));
    }

    @Test
    public void testTheDependentsOfTheCleanedProjectAreInTheRestartScopeOnce()
    {
        IDtProject first = project("First"), second = project("Second");
        IDtProject d1 = project("D1"), d2 = project("D2");
        Platform platform = new Platform();
        platform.dependents.put(first, List.of(d1, d2, second));
        platform.dependents.put(second, List.of(d1));
        CleanCollection collection = collection(first, second);
        JsonObject result = run(collection, platform);
        assertTrue(result.get("success").getAsBoolean());
        assertEquals(List.of("watch", "prepare:First", "prepare:Second", "prepare:D1", "prepare:D2",
            "clean:First", "clean:Second"), platform.events);
        assertEquals(4, platform.waiters.size());
        for (ProjectRestartWaiter waiter : platform.waiters.values())
        {
            verify(waiter).await(60_000, platform.failure);
        }
        assertEquals(List.of("First", "Second"), platform.derived);
        platform.cleanedUp();
    }

    @Test
    public void testADependentStrandedByAFailedRestartIsNamedInsteadOfReportingOk()
    {
        IDtProject target = project("Target"), sibling = project("Broken");
        Platform platform = new Platform();
        platform.dependents.put(target, List.of(sibling));
        platform.started.put(sibling, false);
        when(platform.failure.hasFailed()).thenReturn(true);
        JsonObject result = run(collection(target), platform);
        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("mutationCommitted").getAsBoolean());
        assertEquals(1, result.getAsJsonArray("projects").size());
        assertEquals("Target", result.getAsJsonArray("projects").get(0).getAsString());
        assertEquals("Broken", result.getAsJsonArray("notRestarted").get(0).getAsString());
        assertEquals(1, result.getAsJsonArray("notRestarted").size());
        assertEquals(0, result.getAsJsonArray("recovered").size());
        assertTrue(result.get("restartFailure").getAsString().contains("PROJECT.PMF"));
        String error = result.get("error").getAsString();
        assertTrue(error.contains("Broken"));
        assertTrue(error.contains("Close and reopen"));
        assertTrue(error.contains("delete_project"));
        assertTrue(error.contains("list_projects"));
        assertEquals(List.of("Broken"), platform.recoveryRequests);
        platform.cleanedUp();
    }

    @Test
    public void testStrandedDependentsAreStartedAgainAndReportedAsRecovered()
    {
        for (boolean lateRecovery : new boolean[] {false, true})
        {
            IDtProject target = project("Target"), sibling = project("Sibling");
            Platform platform = new Platform();
            platform.dependents.put(target, List.of(sibling));
            platform.started.put(sibling, false);
            platform.recover = !lateRecovery;
            if (lateRecovery)
            {
                // A bounded platform call can finish while the target's derived data is awaited.
                platform.onDerived = () -> platform.started.put(sibling, true);
            }
            when(platform.failure.hasFailed()).thenReturn(true);
            JsonObject result = run(collection(target), platform);
            assertTrue("report the final state, including recovery during the derived-data wait",
                result.get("success").getAsBoolean());
            assertEquals("Sibling", result.getAsJsonArray("recovered").get(0).getAsString());
            assertFalse(result.has("notRestarted"));
            assertTrue(result.get("restartFailure").getAsString().contains("PROJECT.PMF"));
            assertTrue(result.get("message").getAsString().contains("Project services restart"));
            assertFalse(result.get("message").getAsString().equals("Clean and revalidation completed."));
            platform.cleanedUp();
        }
    }

    @Test
    public void testPartialRecoveryNamesExactlyWhichProjectsCameBackAndWhichDidNot()
    {
        IDtProject target = project("Target"), broken = project("Broken"), healthy = project("Healthy");
        Platform platform = new Platform();
        platform.dependents.put(target, List.of(broken, healthy));
        platform.started.put(broken, false);
        platform.started.put(healthy, false);
        platform.recover = true;
        platform.recoveryFailures.add("Broken");
        when(platform.failure.hasFailed()).thenReturn(true);
        JsonObject result = run(collection(target), platform);
        assertFalse(result.get("success").getAsBoolean());
        assertEquals(1, result.getAsJsonArray("notRestarted").size());
        assertEquals("Broken", result.getAsJsonArray("notRestarted").get(0).getAsString());
        assertEquals(1, result.getAsJsonArray("recovered").size());
        assertEquals("Healthy", result.getAsJsonArray("recovered").get(0).getAsString());
        assertEquals(List.of("Broken", "Healthy"), platform.recoveryRequests);
        platform.cleanedUp();
    }

    @Test
    public void testTheTargetItselfNotComingBackAfterAFailedRestartIsAnErrorAndIsNotRecovered()
    {
        IDtProject target = project("Target");
        Platform platform = new Platform();
        platform.started.put(target, false);
        when(platform.failure.hasFailed()).thenReturn(true);
        JsonObject result = run(collection(target), platform);
        assertFalse(result.get("success").getAsBoolean());
        assertEquals("Target", result.getAsJsonArray("notRestarted").get(0).getAsString());
        assertTrue(platform.recoveryRequests.isEmpty());
        assertTrue("a stopped target has no derived data to wait for", platform.derived.isEmpty());
        platform.cleanedUp();
    }

    @Test
    public void testAFailureSignalWithEveryProjectStartedIsNotAnError()
    {
        Platform platform = new Platform();
        when(platform.failure.hasFailed()).thenReturn(true);
        JsonObject result = run(collection(project("Target")), platform);
        assertTrue(result.get("success").getAsBoolean());
        assertEquals("Clean and revalidation completed.", result.get("message").getAsString());
        assertFalse(result.has("restartFailure"));
        platform.cleanedUp();
    }

    @Test
    public void testAWaitExpiryWithoutAFailureSignalKeepsTheLoggedOnlyBehaviour()
    {
        Platform platform = new Platform();
        IDtProject target = project("Target"), sibling = project("Sibling");
        platform.dependents.put(target, List.of(sibling));
        platform.started.put(sibling, false);
        JsonObject result = run(collection(target), platform);
        assertTrue(result.get("success").getAsBoolean());
        assertFalse(result.has("notRestarted"));
        assertTrue(platform.recoveryRequests.isEmpty());
        platform.cleanedUp();
    }

    @Test
    public void testTheFailureListenerAndEveryWaiterAreCleanedUpWhenTheCleanFails()
    {
        Platform platform = new Platform();
        IDtProject target = project("Target"), sibling = project("Sibling");
        platform.dependents.put(target, List.of(sibling));
        JsonObject result = parse(CleanProjectTool.runCleanLifecycle(collection(target), 5_000, 60_000,
            (info, monitor) -> { throw new CoreException(new Status(IStatus.ERROR, "test", "clean failed")); }, platform));
        assertFalse(result.get("success").getAsBoolean());
        for (ProjectRestartWaiter waiter : platform.waiters.values())
        {
            verify(waiter, never()).await(anyLong(), any());
        }
        platform.cleanedUp();
    }

    @Test
    public void testTheFailureListenerAndEveryWaiterAreCleanedUpWhenTheCleanTimesOut() throws Exception
    {
        Platform platform = new Platform();
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            JsonObject result = parse(CleanProjectTool.runCleanLifecycle(collection(project("Target")), 100, 60_000,
                (info, monitor) -> {
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }, platform));
            assertFalse(result.get("success").getAsBoolean());
            platform.cleanedUp();
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testClosedAndDeletedSiblingsAreReportedWithoutTryingToStartThem()
    {
        Platform platform = new Platform();
        IDtProject target = project("Target"), closed = project("Closed"), deleted = project("Deleted");
        when(closed.getWorkspaceProject().isOpen()).thenReturn(false);
        when(deleted.getWorkspaceProject().exists()).thenReturn(false);
        platform.dependents.put(target, List.of(closed, deleted));
        platform.started.put(closed, false);
        platform.started.put(deleted, false);
        when(platform.failure.hasFailed()).thenReturn(true);
        JsonObject result = run(collection(target), platform);
        assertFalse(result.get("success").getAsBoolean());
        assertEquals(2, result.getAsJsonArray("notRestarted").size());
        assertTrue(platform.recoveryRequests.isEmpty());
    }

    @Test
    public void testOneBrokenRecoveryRequestDoesNotPreventTheNextSiblingFromStarting()
    {
        IWorkspaceOrchestrator workspace = mock(IWorkspaceOrchestrator.class);
        List<String> starts = new ArrayList<>();
        doAnswer(call -> {
            Collection<WorkspaceProjectStartRequest> requests = call.getArgument(0);
            assertEquals(1, requests.size());
            WorkspaceProjectStartRequest request = requests.iterator().next();
            assertEquals(ProjectStartType.CLEAN_IMPORT, request.getStartType());
            String name = request.getWorkspaceProject().getName();
            starts.add(name);
            if (name.equals("Broken"))
            {
                throw new IllegalStateException("PROJECT.PMF missing");
            }
            return null;
        }).when(workspace).startWorkspaceProjects(anyCollection(), any());
        IDtProject broken = project("Broken"), healthy = project("Healthy");
        Collection<IDtProject> issued = new RestartSupport(null, workspace).recover(
            List.of(info(broken), info(healthy)), new NullProgressMonitor());
        assertEquals(List.of("Broken", "Healthy"), starts);
        assertEquals(List.of(healthy), new ArrayList<>(issued));
    }

    @Test
    public void testRecoveryStopsAtTheBoundAndReportsProjectsStillStopped() throws Exception
    {
        IDtProject target = project("Target"), sibling = project("Sibling");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        RestartJobFailureSignal failure = mock(RestartJobFailureSignal.class);
        when(failure.hasFailed()).thenReturn(true);
        when(failure.getFailure()).thenReturn("restart failed");
        RestartSupport support = new RestartSupport(null, null)
        {
            @Override Collection<IDtProject> dependents(IDtProject project) { return List.of(sibling); }
            @Override ProjectRestartWaiter prepare(IDtProject project) { return null; }
            @Override RestartJobFailureSignal watchFailures() { return failure; }
            @Override boolean isStarted(IDtProject project) { return project == target; }
            @Override void awaitDerivedData(IProject project) {}
            @Override Collection<IDtProject> recover(List<ProjectCleanInfo> projects, IProgressMonitor monitor)
            {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return List.of();
            }
        };
        try
        {
            long start = System.nanoTime();
            JsonObject result = parse(CleanProjectTool.runCleanLifecycle(collection(target), 1_000, 60_000,
                (info, monitor) -> {}, support));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000);
            assertFalse(result.get("success").getAsBoolean());
            assertEquals("Sibling", result.getAsJsonArray("notRestarted").get(0).getAsString());
            assertTrue(result.get("error").getAsString().contains("may still be running"));
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testPartialRegistrationFailureCleansUpTheEarlierWaiters()
    {
        IDtProject first = project("First"), second = project("Second");
        ProjectRestartWaiter registered = mock(ProjectRestartWaiter.class);
        try
        {
            CleanProjectTool.registerRestartWaiters(collection(first, second).projectsToClean,
                project -> List.of(), project -> {
                    if (project == second) { throw new IllegalStateException("registration failed"); }
                    return registered;
                });
            fail("registration must fail");
        }
        catch (IllegalStateException expected)
        {
            assertEquals("registration failed", expected.getMessage());
        }
        verify(registered).cleanup();
    }

    @Test
    public void testOutputSchemaAndGuideDeclareRestartFailureAndRecovery()
    {
        CleanProjectTool tool = new CleanProjectTool();
        JsonObject properties = parse(tool.getOutputSchema()).getAsJsonObject("properties");
        for (String field : List.of("notRestarted", "recovered", "restartFailure"))
        {
            assertTrue(properties.has(field));
            assertTrue(tool.getGuide().contains(field));
        }
        assertTrue(tool.getGuide().contains("dependents"));
        assertTrue(tool.getGuide().contains("without a job failure"));
    }
}
