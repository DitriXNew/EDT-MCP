/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.CascadeEnvironment;

/**
 * Tests for {@link ProjectStateChecker#buildingErrorOrNull(String)} and the cascade pre-flight
 * {@link ProjectStateChecker#settleBeforeCascadeOrError(IProject, long, CascadeEnvironment)}.
 * <p>
 * The {@code (String, long)} entry point only resolves {@code project} from the live workspace
 * and injects {@link CascadeEnvironment#DEFAULT} - it and the BUILDING / project-not-found
 * branches of {@link ProjectStateChecker#checkProjectState} need a live workspace and are covered
 * by the e2e suite. The seam-taking {@code (IProject, long, CascadeEnvironment)} overload exists
 * precisely so the participant-drain logic - which projects get waited on, whether the shared
 * deadline is respected, and which refusal is returned - can be proven headlessly with a fake
 * {@link CascadeEnvironment} and mocked {@link IProject} handles.
 */
public class ProjectStateCheckerTest
{
    private static final long SETTLE_TIMEOUT_MS = 5_000L;

    @Test
    public void buildingErrorOrNullIsNullForNullName()
    {
        // null name short-circuits to null before any workspace access.
        assertNull(ProjectStateChecker.buildingErrorOrNull((String) null));
    }

    @Test
    public void buildingErrorOrNullIsNullForEmptyName()
    {
        // empty name short-circuits to null before any workspace access.
        assertNull(ProjectStateChecker.buildingErrorOrNull(""));
    }

    @Test
    public void settleBeforeCascadeShortCircuitsWithoutWaitingWhenNothingIsBuilding()
    {
        // The cascade pre-flight drains the pipeline unconditionally for a REAL project, but a
        // null/empty name has no project to drain: it must return before any workspace access,
        // leaving the caller's required-argument error to speak. (A regression here would show as
        // this test hanging on the drain rather than failing.)
        assertNull(ProjectStateChecker.settleBeforeCascadeOrError(null, 60_000L));
        assertNull(ProjectStateChecker.settleBeforeCascadeOrError("", 60_000L));
    }

    // --- settleBeforeCascadeOrError(IProject, long, CascadeEnvironment) --------------------
    //
    // These drive the participant-drain logic headlessly through a fake CascadeEnvironment and
    // mocked IProject handles, so (unlike the DEFAULT environment) none of them touch Activator
    // or the live workspace.

    private static IProject mockOpenProject(String name)
    {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn(name);
        return project;
    }

    @Test
    public void busyParticipantIsRefusedByName()
    {
        // A participant (its base resolves to the renamed project) that is still building must
        // refuse the cascade, and the message must NAME that participant - the actionable detail
        // an agent needs to know which project to wait on.
        IProject base = mockOpenProject("Base");
        IProject participant = mockOpenProject("Ext1");

        CascadeEnvironment env = mock(CascadeEnvironment.class);
        when(env.getOpenDtProjects()).thenReturn(Collections.singletonList(participant));
        when(env.resolveBaseProject(participant)).thenReturn(base);
        when(env.isBuilding(participant)).thenReturn(true);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base, SETTLE_TIMEOUT_MS, env);

        assertTrue("must be a retryable refusal", result != null);
        assertTrue("message must name the busy participant", result.contains("Ext1"));
        assertTrue("message must name the base it extends", result.contains("Base"));
    }

    @Test
    public void busyUnrelatedProjectIsNeverWaitedOnAndNeverCausesRefusal()
    {
        // An unrelated open project (its base does NOT resolve to the renamed project) takes no
        // part in the cascade: it must never be waited on and must never cause a refusal, even
        // while busy - draining it would only let it eat another rename's budget for nothing.
        IProject base = mockOpenProject("Base");
        IProject unrelated = mockOpenProject("Unrelated");
        IProject someOtherBase = mockOpenProject("SomeOtherBase");

        CascadeEnvironment env = mock(CascadeEnvironment.class);
        when(env.getOpenDtProjects()).thenReturn(Collections.singletonList(unrelated));
        // Not a participant: resolves to some OTHER base, not the one being renamed.
        when(env.resolveBaseProject(unrelated)).thenReturn(someOtherBase);
        when(env.isBuilding(unrelated)).thenReturn(true);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base, SETTLE_TIMEOUT_MS, env);

        assertNull("a busy unrelated project must never cause a refusal", result);
        verify(env, never()).waitForDerivedData(eq(unrelated), anyLong());
        verify(env, never()).isBuilding(unrelated);
    }

    @Test
    public void settledParticipantReturnsNull()
    {
        // A participant that is NOT building lets the cascade proceed.
        IProject base = mockOpenProject("Base");
        IProject participant = mockOpenProject("Ext1");

        CascadeEnvironment env = mock(CascadeEnvironment.class);
        when(env.getOpenDtProjects()).thenReturn(Collections.singletonList(participant));
        when(env.resolveBaseProject(participant)).thenReturn(base);
        when(env.isBuilding(participant)).thenReturn(false);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base, SETTLE_TIMEOUT_MS, env);

        assertNull(result);
        verify(env).waitForDerivedData(eq(participant), anyLong());
    }

    @Test
    public void sharedDeadlineLeavesNothingForTheNextParticipantAfterTheFirstConsumesItAll()
    {
        // Both participants share ONE deadline. The first participant's drain "consumes the
        // whole budget" (its fake wait blocks past the deadline); the second must then receive a
        // non-positive remaining budget, or be skipped outright - either way it must not be
        // handed the original full timeout again.
        IProject base = mockOpenProject("Base");
        IProject participant1 = mockOpenProject("Ext1");
        IProject participant2 = mockOpenProject("Ext2");

        long settleTimeoutMs = 30L;
        long overrunMs = 100L;
        AtomicLong participant2RemainingMs = new AtomicLong(Long.MIN_VALUE);

        CascadeEnvironment env = mock(CascadeEnvironment.class);
        when(env.getOpenDtProjects()).thenReturn(Arrays.asList(participant1, participant2));
        when(env.resolveBaseProject(participant1)).thenReturn(base);
        when(env.resolveBaseProject(participant2)).thenReturn(base);
        when(env.isBuilding(participant1)).thenReturn(false);
        when(env.isBuilding(participant2)).thenReturn(false);
        doAnswer(invocation ->
        {
            IProject waited = invocation.getArgument(0);
            if (waited == participant1)
            {
                // Simulate the first participant's drain running long enough to blow through
                // the shared deadline before the second participant is even considered.
                Thread.sleep(settleTimeoutMs + overrunMs);
            }
            else if (waited == participant2)
            {
                participant2RemainingMs.set(invocation.getArgument(1));
            }
            return null;
        }).when(env).waitForDerivedData(any(IProject.class), anyLong());

        ProjectStateChecker.settleBeforeCascadeOrError(base, settleTimeoutMs, env);

        // Either participant 2 was skipped entirely (never asked to wait, budget stays at the
        // sentinel), or it was asked with a non-positive remaining budget - never a fresh timeout.
        long remaining = participant2RemainingMs.get();
        assertTrue("participant 2 must not have been handed a positive remaining budget: " + remaining,
            remaining == Long.MIN_VALUE || remaining <= 0L);
        verify(env).waitForDerivedData(eq(participant1), anyLong());
    }
}
