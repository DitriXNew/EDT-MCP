/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.CascadeEnvironment;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.SearchDependenciesResult;

/** Bounded pure-query polling of the full reference-search scope, including external objects. */
public class ProjectStateCheckerSearchScopeWaitTest
{
    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    private static ProjectStateResult state(ProjectState state)
    {
        return new ProjectStateResult(state, state.getValue());
    }

    private static final class Scope
    {
        final IProject base = project("TestConfiguration");
        final IProject extension = project("TestConfiguration.tests");
        final IProject external = project("ExternalObjects");
        final CascadeEnvironment env = mock(CascadeEnvironment.class);
        final List<Long> waits = new ArrayList<>();

        Scope()
        {
            when(base.exists()).thenReturn(true);
            when(base.isOpen()).thenReturn(true);
            when(env.hasBmModelProjectNature(any(IProject.class))).thenReturn(Boolean.TRUE);
            when(env.getOpenDtProjects()).thenReturn(List.of(base, extension, external));
            when(env.getOpenDependentNatureProjects()).thenReturn(List.of(extension, external));
            when(env.getOpenExtensionNatureProjects()).thenReturn(List.of(extension));
            when(env.resolveBaseProject(extension)).thenReturn(base);
            when(env.resolveBaseProject(external)).thenReturn(base);
            when(env.isExtensionProject(extension)).thenReturn(true);
            when(env.getProjectState(any(IProject.class))).thenReturn(state(ProjectState.READY));
            when(env.getProjectState(external)).thenReturn(state(ProjectState.BUILDING));
            when(env.waitBeforeModelRetry(anyLong())).thenAnswer(call -> {
                waits.add(call.getArgument(0));
                assertTrue("a broken polling deadline must not hang the test", waits.size() <= 100);
                return true;
            });
        }

        SearchDependenciesResult await(long budgetMs)
        {
            return ProjectStateChecker.awaitSearchScopeReady(base, budgetMs, env);
        }
    }

    @Test
    public void pollsUntilTheExternalObjectsDependentIsReady()
    {
        Scope scope = new Scope();
        when(scope.env.getProjectState(scope.external)).thenReturn(state(ProjectState.BUILDING),
            state(ProjectState.BUILDING), state(ProjectState.BUILDING), state(ProjectState.READY));

        SearchDependenciesResult result = scope.await(1_000);

        assertTrue(result.isAllReady());
        assertEquals(List.of(), result.getNotReadyProjectNames());
        assertEquals(3, scope.waits.size());
        assertTrue(scope.waits.stream().allMatch(wait -> wait > 0 && wait <= 50));
        verify(scope.env, never()).waitForDerivedData(any(), anyLong());
    }

    @Test
    public void expiryReturnsTheLastSnapshotAndBoundsTheNumberOfWaits()
    {
        Scope scope = new Scope();
        long budgetMs = 120;
        // doAnswer, not when(...): re-stubbing through when() invokes the constructor's answer
        // once, which would record a phantom zero-length wait before the poll even starts.
        doAnswer(call -> {
            long wait = call.getArgument(0);
            scope.waits.add(wait);
            assertTrue("the deadline must terminate polling even when the project stays building",
                scope.waits.size() <= budgetMs / 50 + 1);
            // Thread.sleep can return before currentTimeMillis advances by the requested
            // amount on Windows. Honor the seam's requested duration using the same clock.
            long retryAt = System.currentTimeMillis() + wait;
            long remaining;
            while ((remaining = retryAt - System.currentTimeMillis()) > 0L)
            {
                Thread.sleep(remaining);
            }
            return true;
        }).when(scope.env).waitBeforeModelRetry(anyLong());

        SearchDependenciesResult result = scope.await(budgetMs);

        assertTrue(result.isDetermined());
        assertFalse(result.isAllReady());
        assertEquals(List.of("ExternalObjects"), result.getNotReadyProjectNames());
        assertEquals(ProjectState.BUILDING, result.getReadiness().get("ExternalObjects"));
        assertEquals(List.of("TestConfiguration", "TestConfiguration.tests", "ExternalObjects"),
            new ArrayList<>(result.getReadiness().keySet()));
        assertFalse(scope.waits.isEmpty());
        assertTrue(scope.waits.stream().allMatch(wait -> wait > 0 && wait <= 50));
        verify(scope.env, times(scope.waits.size() + 1)).getProjectState(scope.external);
    }

    @Test
    public void anInterruptedRetryReturnsTheCurrentSnapshotImmediately()
    {
        Scope scope = new Scope();
        when(scope.env.waitBeforeModelRetry(anyLong())).thenReturn(false);

        SearchDependenciesResult result = scope.await(10_000);

        assertEquals(List.of("ExternalObjects"), result.getNotReadyProjectNames());
        verify(scope.env).waitBeforeModelRetry(anyLong());
        verify(scope.env).getProjectState(scope.external);
    }

    @Test
    public void anUndeterminedRegistryIsRetriedUntilItIsDeterminedAndReady()
    {
        Scope scope = new Scope();
        when(scope.env.getOpenDtProjects()).thenReturn(List.of(scope.base, scope.extension),
            List.of(scope.base, scope.extension, scope.external));
        when(scope.env.getProjectState(scope.external)).thenReturn(state(ProjectState.READY));

        assertTrue(scope.await(1_000).isAllReady());
        assertEquals(1, scope.waits.size());
    }

    @Test
    public void aReadyScopeReturnsWithoutWaiting()
    {
        Scope scope = new Scope();
        when(scope.env.getProjectState(scope.external)).thenReturn(state(ProjectState.READY));

        assertTrue(scope.await(1_000).isAllReady());
        verify(scope.env, never()).waitBeforeModelRetry(anyLong());
    }

    @Test
    public void zeroBudgetReturnsTheFirstSnapshotWithoutWaiting()
    {
        Scope scope = new Scope();
        assertEquals(List.of("ExternalObjects"), scope.await(0).getNotReadyProjectNames());
        verify(scope.env, never()).waitBeforeModelRetry(anyLong());
    }

    @Test
    public void anUndeterminedSnapshotHasNoInventedReadiness()
    {
        Scope scope = new Scope();
        when(scope.env.getOpenDtProjects()).thenReturn(List.of(scope.base));
        SearchDependenciesResult result = scope.await(0);
        assertFalse(result.isDetermined());
        assertEquals(List.of(), result.getNotReadyProjectNames());
        assertTrue(result.getReadiness().isEmpty());
        assertFalse(ProjectStateChecker.awaitSearchScopeReady((String)null, 1_000).isDetermined());
        assertFalse(ProjectStateChecker.awaitSearchScopeReady("", 1_000).isDetermined());
    }

    /** A misspelled projectName must reach the caller's instant "Project not found" without the budget. */
    @Test
    public void aMissingBaseGetsOneSnapshotAndNoWait()
    {
        Scope scope = new Scope();
        when(scope.base.exists()).thenReturn(false);
        when(scope.env.getProjectState(scope.base)).thenReturn(state(ProjectState.NOT_AVAILABLE));

        SearchDependenciesResult result = scope.await(10_000);

        assertFalse(result.isAllReady());
        verify(scope.env, never()).waitBeforeModelRetry(anyLong());
        verify(scope.env, times(1)).getOpenDtProjects();
    }

    @Test
    public void aClosedBaseIsNeverWaitedFor()
    {
        Scope scope = new Scope();
        when(scope.base.isOpen()).thenReturn(false);
        when(scope.env.getProjectState(scope.base)).thenReturn(state(ProjectState.NOT_AVAILABLE));

        assertFalse(scope.await(10_000).isAllReady());
        verify(scope.env, never()).waitBeforeModelRetry(anyLong());
    }

    @Test
    public void aBaseOutsideEdtIsNeverWaitedFor()
    {
        Scope scope = new Scope();
        when(scope.env.hasBmModelProjectNature(scope.base)).thenReturn(Boolean.FALSE);
        when(scope.env.getProjectState(scope.base)).thenReturn(state(ProjectState.NOT_AVAILABLE));

        assertFalse(scope.await(10_000).isAllReady());
        verify(scope.env, never()).waitBeforeModelRetry(anyLong());
    }

    /** An unreadable description is not proof of "outside EDT": that base is still waited for. */
    @Test
    public void anUnreadableNatureIsStillWaitedFor()
    {
        Scope scope = new Scope();
        when(scope.env.hasBmModelProjectNature(scope.base)).thenReturn(null);
        when(scope.env.getProjectState(scope.external)).thenReturn(state(ProjectState.BUILDING),
            state(ProjectState.READY));

        assertTrue(scope.await(1_000).isAllReady());
        assertEquals(1, scope.waits.size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void readinessRemainsAnImmutableSnapshot()
    {
        new Scope().await(0).getReadiness().put("Invented", ProjectState.READY);
    }
}
