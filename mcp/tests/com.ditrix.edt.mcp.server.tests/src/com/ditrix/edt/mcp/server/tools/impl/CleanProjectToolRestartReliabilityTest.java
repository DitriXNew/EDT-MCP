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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool.CleanCollection;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool.ProjectCleanInfo;
import com.ditrix.edt.mcp.server.tools.impl.CleanProjectTool.RestartSupport;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter.ProjectRestartWaiter;
import com.ditrix.edt.mcp.server.utils.RestartJobFailureSignal;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Uses real job states and the production signal without a live EDT workspace. */
public class CleanProjectToolRestartReliabilityTest
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

    private static final class MarkerRestartJob extends Job
    {
        private final Runnable action;

        MarkerRestartJob(Runnable action)
        {
            super("marker restart");
            this.action = action;
        }

        @Override protected IStatus run(IProgressMonitor monitor)
        {
            action.run();
            return Status.OK_STATUS;
        }
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            assertTrue("test job must be released", latch.await(10, TimeUnit.SECONDS));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class Platform extends RestartSupport implements AutoCloseable
    {
        final IDtProject target = project("Target");
        final IDtProject sibling = project("Sibling");
        final IJobManager manager = mock(IJobManager.class);
        final AtomicReference<Job[]> jobs = new AtomicReference<>(new Job[0]);
        final RestartJobFailureSignal failure;
        final IJobChangeListener listener;
        final Map<IDtProject, Boolean> started = new ConcurrentHashMap<>();
        final List<String> recoveryRequests = new ArrayList<>();
        Runnable onScan = () -> {};
        Runnable beforeProbe = () -> {};
        Runnable onDerived = () -> {};
        boolean recover;
        boolean throwDuringRecovery;

        Platform()
        {
            super(null, null);
            when(manager.find(null)).thenAnswer(call -> {
                onScan.run();
                return jobs.get();
            });
            failure = new RestartJobFailureSignal(manager, job -> job instanceof MarkerRestartJob);
            ArgumentCaptor<IJobChangeListener> capture = ArgumentCaptor.forClass(IJobChangeListener.class);
            verify(manager).addJobChangeListener(capture.capture());
            listener = capture.getValue();
            started.put(target, true);
            started.put(sibling, false);
        }

        void signal(int severity, String message)
        {
            IJobChangeEvent event = mock(IJobChangeEvent.class);
            when(event.getJob()).thenReturn(new MarkerRestartJob(() -> {}));
            when(event.getResult()).thenReturn(new Status(severity, "test", message));
            listener.done(event);
        }

        @Override Collection<IDtProject> dependents(IDtProject project) { return List.of(sibling); }
        @Override ProjectRestartWaiter prepare(IDtProject project) { return null; }
        @Override RestartJobFailureSignal watchFailures() { return failure; }
        @Override boolean isStarted(IDtProject project)
        {
            beforeProbe.run();
            return started.get(project);
        }
        @Override void awaitDerivedData(IProject project) { onDerived.run(); }
        // Platform readiness is stubbed just like the derived-data wait.
        @Override
        ProjectStateResult stateOf(IProject project)
        {
            return new ProjectStateResult(ProjectState.READY, "ready");
        }

        @Override Collection<IDtProject> recover(List<ProjectCleanInfo> projects, IProgressMonitor monitor)
        {
            for (ProjectCleanInfo project : projects)
            {
                recoveryRequests.add(project.name);
                if (recover) { started.put(project.dtProject, true); }
            }
            if (throwDuringRecovery) { throw new IllegalStateException("start request failed"); }
            return List.of();
        }

        JsonObject run(long lifecycleTimeoutMs)
        {
            CleanCollection collection = new CleanCollection();
            collection.projectsToClean.add(new ProjectCleanInfo(target.getWorkspaceProject(), target, "Target"));
            collection.projectNamesList.add("Target");
            return JsonParser.parseString(CleanProjectTool.runCleanLifecycle(collection, 5_000, lifecycleTimeoutMs,
                (info, monitor) -> {}, this)).getAsJsonObject();
        }

        @Override public void close() { failure.close(); }
    }

    @Test
    public void testAnotherRunningRestartFinishesBeforeStrandedProjectsAreProbed() throws Exception
    {
        for (boolean failureAlreadyKnown : new boolean[] {true, false})
        {
            try (Platform platform = new Platform())
            {
                CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
                Job pending = new MarkerRestartJob(() -> {
                    entered.countDown();
                    await(release);
                    platform.started.put(platform.sibling, true);
                });
                try
                {
                    pending.schedule();
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    platform.jobs.set(new Job[] {pending});
                    platform.onScan = () -> {
                        // A completion can arrive after the per-project waits, during settlement.
                        if (!failureAlreadyKnown) { platform.signal(IStatus.ERROR, "late restart failure"); }
                        release.countDown();
                    };
                    platform.beforeProbe = () -> assertEquals("no stranded probe during a restart", Job.NONE,
                        pending.getState());
                    if (failureAlreadyKnown) { platform.signal(IStatus.ERROR, "earlier restart failed"); }
                    JsonObject result = platform.run(5_000);
                    assertTrue(result.get("success").getAsBoolean());
                    assertFalse(result.has("notRestarted"));
                    assertTrue(platform.recoveryRequests.isEmpty());
                    verify(platform.manager, atLeastOnce()).find(null);
                }
                finally
                {
                    release.countDown();
                    pending.join();
                }
            }
        }
    }

    @Test
    public void testAnotherWaitingRestartFinishesBeforeRecoveryIsConsidered() throws Exception
    {
        try (Platform platform = new Platform())
        {
            ISchedulingRule rule = new ISchedulingRule()
            {
                @Override public boolean contains(ISchedulingRule other) { return other == this; }
                @Override public boolean isConflicting(ISchedulingRule other) { return other == this; }
            };
            Job pending = new MarkerRestartJob(() -> platform.started.put(platform.sibling, true));
            pending.setRule(rule);
            AtomicBoolean holdingRule = new AtomicBoolean(true);
            Job.getJobManager().beginRule(rule, null);
            try
            {
                pending.schedule();
                assertEquals(Job.WAITING, pending.getState());
                platform.jobs.set(new Job[] {pending});
                platform.onScan = () -> {
                    if (holdingRule.compareAndSet(true, false)) { Job.getJobManager().endRule(rule); }
                };
                platform.beforeProbe = () -> assertEquals("queued restarts still own their scope", Job.NONE,
                    pending.getState());
                platform.signal(IStatus.ERROR, "earlier restart failed");
                JsonObject result = platform.run(5_000);
                assertTrue(result.get("success").getAsBoolean());
                assertTrue(platform.recoveryRequests.isEmpty());
            }
            finally
            {
                if (holdingRule.compareAndSet(true, false)) { Job.getJobManager().endRule(rule); }
                pending.join();
            }
        }
    }

    @Test
    public void testAnActiveRestartAtTheDeadlinePreventsOverlappingRecovery() throws Exception
    {
        try (Platform platform = new Platform())
        {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            Job pending = new MarkerRestartJob(() -> { entered.countDown(); await(release); });
            try
            {
                pending.schedule();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                platform.jobs.set(new Job[] {pending});
                platform.signal(IStatus.ERROR, "earlier restart failed");
                long start = System.nanoTime();
                JsonObject result = platform.run(150);
                assertTrue("the shared restart allowance must remain bounded",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000);
                assertTrue("platform-owned projects cannot also be recovery-started",
                    platform.recoveryRequests.isEmpty());
                assertFalse(result.get("success").getAsBoolean());
                assertTrue(result.get("error").getAsString().contains("Recovery was not attempted"));
                assertEquals("Sibling", result.getAsJsonArray("notRestarted").get(0).getAsString());
            }
            finally
            {
                release.countDown();
                pending.join();
            }
        }
    }

    @Test
    public void testUnrelatedRunningJobsDoNotDelayRecovery() throws Exception
    {
        try (Platform platform = new Platform())
        {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            Job unrelated = new Job("unrelated")
            {
                @Override protected IStatus run(IProgressMonitor monitor)
                {
                    entered.countDown();
                    await(release);
                    return Status.OK_STATUS;
                }
            };
            try
            {
                unrelated.schedule();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                platform.jobs.set(new Job[] {unrelated});
                platform.signal(IStatus.ERROR, "restart failed");
                platform.recover = true;
                long start = System.nanoTime();
                assertTrue(platform.run(5_000).get("success").getAsBoolean());
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_500);
                assertEquals(List.of("Sibling"), platform.recoveryRequests);
            }
            finally
            {
                release.countDown();
                unrelated.join();
            }
        }
    }

    @Test
    public void testCancellationKeepsItsReasonAndIsNotReportedAsFailure()
    {
        try (Platform platform = new Platform())
        {
            platform.signal(IStatus.CANCEL, "cancelled from Progress");
            platform.signal(IStatus.ERROR, "a later error must not replace cancellation");
            JsonObject result = platform.run(5_000);
            assertFalse(result.get("success").getAsBoolean());
            assertTrue(result.get("error").getAsString().contains("'Project services restart' was cancelled:"));
            assertFalse(result.get("error").getAsString().contains("'Project services restart' failed:"));
            assertEquals("cancelled from Progress", result.get("restartFailure").getAsString());
            assertEquals(List.of("Sibling"), platform.recoveryRequests);
        }
    }

    @Test
    public void testAWarningIsReportedAsAnIncompleteRestart()
    {
        try (Platform platform = new Platform())
        {
            platform.signal(IStatus.WARNING, "restart warning");
            JsonObject result = platform.run(5_000);
            assertFalse(result.get("success").getAsBoolean());
            assertTrue(result.get("error").getAsString().contains("'Project services restart' did not complete:"));
            assertEquals("restart warning", result.get("restartFailure").getAsString());
        }
    }

    @Test
    public void testAStrandedTargetIsDescribedAsStoppedDuringTheRestart()
    {
        try (Platform platform = new Platform())
        {
            platform.started.put(platform.target, false);
            platform.started.put(platform.sibling, true);
            platform.signal(IStatus.ERROR, "target restart failed");
            JsonObject result = platform.run(5_000);
            assertEquals("Target", result.getAsJsonArray("notRestarted").get(0).getAsString());
            String error = result.get("error").getAsString();
            assertTrue(error.contains("stopped these projects during the restart"));
            assertFalse(error.contains("together with the target"));
        }
    }

    @Test
    public void testADeletedSiblingGetsAdviceThatAppliesToDeletedProjects()
    {
        try (Platform platform = new Platform())
        {
            when(platform.sibling.getWorkspaceProject().exists()).thenReturn(false);
            platform.signal(IStatus.ERROR, "deleted sibling restart failed");
            JsonObject result = platform.run(5_000);
            String error = result.get("error").getAsString();
            assertTrue(error.contains("delete_project an existing broken project"));
            assertTrue(error.contains("recreate or re-import deleted projects"));
            assertFalse(error.contains("shows them 'not_available' until then"));
            assertTrue(platform.recoveryRequests.isEmpty());
        }
    }

    @Test
    public void testALateRecoveryDoesNotClaimThisCallRestartedTheProject()
    {
        try (Platform platform = new Platform())
        {
            platform.throwDuringRecovery = true;
            platform.onDerived = () -> platform.started.put(platform.sibling, true);
            platform.signal(IStatus.ERROR, "restart failed");
            JsonObject result = platform.run(5_000);
            assertTrue(result.get("success").getAsBoolean());
            assertEquals("Sibling", result.getAsJsonArray("recovered").get(0).getAsString());
            String message = result.get("message").getAsString();
            assertTrue(message.contains("Back after this call's recovery attempt: Sibling"));
            assertFalse(message.contains("Restarted by this call"));
        }
    }

    @Test
    public void testTheGuideOverviewIncludesDependentRestartsAndTheRecoveryPass()
    {
        String guide = new CleanProjectTool().getGuide();
        String overview = guide.substring(guide.indexOf("## What you get"),
            guide.indexOf("One subtlety about the restart wait"));
        assertTrue(overview.contains("and its direct dependents"));
        assertTrue(overview.contains("bounded recovery pass"));
    }
}
