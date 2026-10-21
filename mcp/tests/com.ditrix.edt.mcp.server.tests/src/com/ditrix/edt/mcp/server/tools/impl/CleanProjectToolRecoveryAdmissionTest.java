/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.Test;

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

/** Exercises admission through the production recovery adapter and workspace-start call. */
public class CleanProjectToolRecoveryAdmissionTest
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

    private static void await(CountDownLatch latch)
    {
        try
        {
            assertTrue("test operation must be released", latch.await(10, TimeUnit.SECONDS));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static Job pendingRestart()
    {
        return new Job("marker restart")
        {
            @Override protected IStatus run(IProgressMonitor monitor) { return Status.OK_STATUS; }
        };
    }

    private static final class Workspace
    {
        final IDtProject target = project("Target");
        final IDtProject sibling = project("Sibling");
        final IDtProject other = project("OtherSibling");
        final IWorkspaceOrchestrator orchestrator = mock(IWorkspaceOrchestrator.class);
        final Map<IDtProject, Boolean> started = new ConcurrentHashMap<>();
        final List<String> starts = new CopyOnWriteArrayList<>();
        Consumer<String> onStart = name -> {};

        Workspace()
        {
            started.put(target, true);
            started.put(sibling, false);
            started.put(other, false);
            doAnswer(call -> {
                Collection<WorkspaceProjectStartRequest> requests = call.getArgument(0);
                assertEquals("each admission covers one start", 1, requests.size());
                String name = requests.iterator().next().getWorkspaceProject().getName();
                starts.add(name);
                onStart.accept(name);
                started.put(name.equals("Sibling") ? sibling : other, true);
                return null;
            }).when(orchestrator).startWorkspaceProjects(anyCollection(), any());
        }
    }

    private static final class Platform extends RestartSupport
    {
        final Workspace workspace;
        final RestartJobFailureSignal failure = mock(RestartJobFailureSignal.class);
        final AtomicReference<Job> pending = new AtomicReference<>();
        Consumer<IDtProject> onProbe = project -> {};
        List<IDtProject> siblings;

        Platform(Workspace workspace)
        {
            super(null, workspace.orchestrator);
            this.workspace = workspace;
            siblings = List.of(workspace.sibling);
            when(failure.hasFailed()).thenReturn(true);
            when(failure.getFailure()).thenReturn("earlier restart failed");
            when(failure.getSeverity()).thenReturn(IStatus.ERROR);
            when(failure.hasPendingRestartJobs()).thenAnswer(call -> {
                Job job = pending.get();
                return job != null && job.getState() != Job.NONE;
            });
        }

        @Override Collection<IDtProject> dependents(IDtProject project) { return siblings; }
        @Override ProjectRestartWaiter prepare(IDtProject project) { return null; }
        @Override RestartJobFailureSignal watchFailures() { return failure; }
        @Override void awaitDerivedData(IProject project) {}
        @Override boolean isStarted(IDtProject project)
        {
            boolean current = workspace.started.get(project);
            onProbe.accept(project);
            return current;
        }

        JsonObject run(long timeoutMs)
        {
            CleanCollection collection = new CleanCollection();
            collection.projectsToClean.add(new ProjectCleanInfo(workspace.target.getWorkspaceProject(),
                workspace.target, "Target"));
            collection.projectNamesList.add("Target");
            return JsonParser.parseString(CleanProjectTool.runCleanLifecycle(collection, timeoutMs, 0,
                (info, monitor) -> {}, this)).getAsJsonObject();
        }
    }

    @Test
    public void testRestartScheduledAfterTheOldCheckPreventsAWorkspaceStart() throws Exception
    {
        Workspace workspace = new Workspace();
        Platform platform = new Platform(workspace);
        Job restart = pendingRestart();
        AtomicBoolean schedule = new AtomicBoolean(true);
        platform.onProbe = project -> {
            if (project == workspace.sibling && schedule.compareAndSet(true, false))
            {
                // waitForRestartJobs has already returned; the stranded-state scan is now in flight.
                restart.schedule(60_000);
                platform.pending.set(restart);
            }
        };
        try
        {
            JsonObject result = platform.run(5_000);
            assertEquals(Job.SLEEPING, restart.getState());
            assertTrue("a newly scheduled restart owns the project", workspace.starts.isEmpty());
            assertFalse(result.get("success").getAsBoolean());
            assertTrue(result.get("error").getAsString().contains("restart jobs became pending"));
        }
        finally
        {
            restart.cancel();
            restart.join();
        }
    }

    @Test
    public void testPendingRestartIsRecheckedBeforeEverySiblingStart() throws Exception
    {
        Workspace workspace = new Workspace();
        Platform platform = new Platform(workspace);
        platform.siblings = List.of(workspace.sibling, workspace.other);
        Job restart = pendingRestart();
        workspace.onStart = name -> {
            if (name.equals("Sibling"))
            {
                restart.schedule(60_000);
                platform.pending.set(restart);
            }
        };
        try
        {
            JsonObject result = platform.run(5_000);
            assertEquals(List.of("Sibling"), workspace.starts);
            assertEquals("OtherSibling", result.getAsJsonArray("notRestarted").get(0).getAsString());
        }
        finally
        {
            restart.cancel();
            restart.join();
        }
    }

    @Test
    public void testTwoConcurrentCallsDoNotStartTheSameSiblingTwice() throws Exception
    {
        Workspace workspace = new Workspace();
        Platform first = new Platform(workspace), second = new Platform(workspace);
        CountDownLatch firstStart = new CountDownLatch(1), secondSnapshot = new CountDownLatch(1);
        workspace.onStart = name -> {
            firstStart.countDown();
            await(secondSnapshot);
        };
        AtomicBoolean snapshot = new AtomicBoolean(true);
        second.onProbe = project -> {
            if (project == workspace.sibling && snapshot.compareAndSet(true, false))
            {
                // This probe returns the captured false even if the first call finishes now.
                secondSnapshot.countDown();
            }
        };
        FutureTask<JsonObject> firstCall = new FutureTask<>(() -> first.run(5_000));
        FutureTask<JsonObject> secondCall = new FutureTask<>(() -> second.run(5_000));
        Thread firstThread = new Thread(firstCall, "first clean"), secondThread = new Thread(secondCall, "second clean");
        try
        {
            firstThread.start();
            assertTrue(firstStart.await(5, TimeUnit.SECONDS));
            secondThread.start();
            assertTrue(firstCall.get(10, TimeUnit.SECONDS).get("success").getAsBoolean());
            assertTrue(secondCall.get(10, TimeUnit.SECONDS).get("success").getAsBoolean());
            assertEquals("separate adapters must share recovery admission", List.of("Sibling"), workspace.starts);
        }
        finally
        {
            secondSnapshot.countDown();
            firstThread.join(10_000);
            secondThread.join(10_000);
        }
    }

    @Test
    public void testAProjectStartedAfterTheSnapshotIsNotStartedAgain()
    {
        Workspace workspace = new Workspace();
        Platform platform = new Platform(workspace);
        platform.onProbe = project -> {
            if (project == workspace.sibling) { workspace.started.put(project, true); }
        };
        assertTrue(platform.run(5_000).get("success").getAsBoolean());
        assertTrue("admission must use the live state", workspace.starts.isEmpty());
    }

    @Test
    public void testAProjectClosedAfterTheSnapshotIsNotStarted()
    {
        Workspace workspace = new Workspace();
        Platform platform = new Platform(workspace);
        AtomicBoolean close = new AtomicBoolean(true);
        when(workspace.sibling.getWorkspaceProject().isOpen()).thenAnswer(call -> {
            // The initial candidate collection saw an open resource; admission must ask again.
            return close.compareAndSet(true, false);
        });
        assertFalse(platform.run(5_000).get("success").getAsBoolean());
        assertTrue("closed resources cannot be recovered", workspace.starts.isEmpty());
    }

    @Test
    public void testTimedOutRecoveryKeepsTheLockUntilItsPlatformCallEnds() throws Exception
    {
        Workspace workspace = new Workspace();
        Platform first = new Platform(workspace), second = new Platform(workspace);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Job> workers = new CopyOnWriteArrayList<>();
        workspace.onStart = name -> {
            workers.add(Job.getJobManager().currentJob());
            entered.countDown();
            await(release); // Deliberately ignores monitor cancellation, like a stuck platform call.
        };
        try
        {
            JsonObject firstResult = first.run(1_000);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(firstResult.get("success").getAsBoolean());
            long start = System.nanoTime();
            JsonObject secondResult = second.run(200);
            assertTrue("lock acquisition belongs to the existing recovery budget",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000);
            assertFalse(secondResult.get("success").getAsBoolean());
            assertEquals("a bounded caller must not release an active recovery's lock",
                List.of("Sibling"), workspace.starts);
        }
        finally
        {
            release.countDown();
            for (Job worker : workers) { worker.join(); }
        }
    }
}
