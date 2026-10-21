/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.NotReadyReason;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;

/** Pins the lifecycle distinction independently of EDT's mutable service registry. */
public class ProjectStateCheckerContextTest
{
    private static final Supplier<Boolean> UNUSED_BOOLEAN = () -> {
        throw new AssertionError("This lifecycle fact must stay lazy");
    };
    private static final BooleanSupplier UNUSED_RESTART = () -> {
        throw new AssertionError("Restart jobs must not be scanned on this branch");
    };
    private static final Supplier<ProjectStateResult> UNUSED_DERIVED_DATA = () -> {
        throw new AssertionError("Derived data must not be read on this branch");
    };

    private static ProjectStateResult unregistered(Boolean nature, boolean restarting)
    {
        return ProjectStateChecker.classifyContext(true, true, true, false,
            () -> nature, UNUSED_BOOLEAN, () -> restarting, UNUSED_DERIVED_DATA);
    }

    @Test
    public void anUnstartedEdtProjectIsReportedAsContextNotStartedNotAsNonEdt()
    {
        ProjectStateResult result = unregistered(Boolean.TRUE, false);
        assertEquals(ProjectState.NOT_AVAILABLE, result.getState());
        assertEquals("not_available", result.getStateValue());
        assertEquals(NotReadyReason.CONTEXT_NOT_STARTED, result.getReason());
        assertTrue(result.getMessage(), result.getMessage().contains("context is not started"));
        assertTrue(result.getMessage(), result.getMessage().contains("close and reopen"));
        assertTrue(result.getMessage(), result.getMessage().contains("delete_project"));
        assertFalse(result.getMessage(), result.getMessage().contains("Not an EDT project"));
    }

    @Test
    public void aPlainEclipseProjectKeepsTheNotAnEdtProjectText()
    {
        ProjectStateResult result = ProjectStateChecker.classifyContext(true, true, true, false,
            () -> Boolean.FALSE, UNUSED_BOOLEAN, UNUSED_RESTART, UNUSED_DERIVED_DATA);
        assertEquals(ProjectState.NOT_AVAILABLE, result.getState());
        assertEquals(NotReadyReason.NOT_EDT_PROJECT, result.getReason());
        assertEquals("Not an EDT project", result.getMessage());
    }

    @Test
    public void anUnreadableNatureIsNeverCalledNotStartedOrNonEdt()
    {
        ProjectStateResult result = ProjectStateChecker.classifyContext(true, true, true, false,
            () -> null, UNUSED_BOOLEAN, UNUSED_RESTART, UNUSED_DERIVED_DATA);
        assertEquals(NotReadyReason.NATURE_UNREADABLE, result.getReason());
        assertTrue(result.getMessage().contains("could not be read"));
        assertFalse(result.getMessage().contains("close and reopen"));
        assertFalse(result.getMessage().contains("Not an EDT project"));
    }

    @Test
    public void aRegisteredContextThatIsNotStartedIsStartingAndSkipsDerivedData()
    {
        AtomicInteger reads = new AtomicInteger();
        ProjectStateResult result = ProjectStateChecker.classifyContext(true, true, true, true,
            UNUSED_BOOLEAN, () -> Boolean.FALSE, UNUSED_RESTART, () -> {
                reads.incrementAndGet();
                return new ProjectStateResult(ProjectState.READY, "ready");
            });
        assertEquals(0, reads.get());
        assertEquals(ProjectState.NOT_AVAILABLE, result.getState());
        assertEquals(NotReadyReason.CONTEXT_STARTING, result.getReason());
        assertTrue(result.getMessage().contains("clean_project"));
        assertTrue(result.getMessage().contains("close and reopen"));
    }

    @Test
    public void aStartedContextIsJudgedByItsDerivedDataVerbatim()
    {
        ProjectStateResult expected = new ProjectStateResult(ProjectState.BUILDING, "building snapshot");
        assertSame(expected, ProjectStateChecker.classifyContext(true, true, true, true,
            UNUSED_BOOLEAN, () -> Boolean.TRUE, UNUSED_RESTART, () -> expected));
    }

    @Test
    public void anUnknownStartedAnswerFallsBackToDerivedData()
    {
        ProjectStateResult expected = new ProjectStateResult(ProjectState.READY, "unchanged snapshot");
        assertSame(expected, ProjectStateChecker.classifyContext(true, true, true, true,
            UNUSED_BOOLEAN, () -> null, UNUSED_RESTART, () -> expected));
    }

    @Test
    public void aPendingRestartJobMakesAnUnstartedEdtContextRestarting()
    {
        ProjectStateResult result = unregistered(Boolean.TRUE, true);
        assertEquals(ProjectState.NOT_AVAILABLE, result.getState());
        assertEquals(NotReadyReason.CONTEXT_RESTARTING, result.getReason());
        assertEquals("EDT context is unavailable: a project-restart job is pending in the workspace; "
            + "poll list_projects until it reads 'ready'", result.getMessage());
    }

    @Test
    public void aClosedProjectIsClosedAndAsksNothingElse()
    {
        AtomicInteger natureReads = new AtomicInteger();
        AtomicInteger startedReads = new AtomicInteger();
        AtomicInteger jobScans = new AtomicInteger();
        ProjectStateResult result = ProjectStateChecker.classifyContext(true, false, true, false,
            () -> { natureReads.incrementAndGet(); return Boolean.TRUE; },
            () -> { startedReads.incrementAndGet(); return Boolean.FALSE; },
            () -> { jobScans.incrementAndGet(); return true; }, UNUSED_DERIVED_DATA);
        assertEquals(NotReadyReason.CLOSED, result.getReason());
        assertEquals("Project is closed", result.getMessage());
        assertEquals(0, natureReads.get());
        assertEquals(0, startedReads.get());
        assertEquals(0, jobScans.get());
    }

    @Test
    public void aMissingProjectAsksNothingElse()
    {
        ProjectStateResult result = ProjectStateChecker.classifyContext(false, false, false, false,
            UNUSED_BOOLEAN, UNUSED_BOOLEAN, UNUSED_RESTART, UNUSED_DERIVED_DATA);
        assertEquals(ProjectState.NOT_AVAILABLE, result.getState());
        assertEquals(NotReadyReason.MISSING, result.getReason());
        assertEquals("Project does not exist", result.getMessage());
    }

    @Test
    public void anUnavailableManagerAsksNothingElse()
    {
        ProjectStateResult result = ProjectStateChecker.classifyContext(true, true, false, false,
            UNUSED_BOOLEAN, UNUSED_BOOLEAN, UNUSED_RESTART, UNUSED_DERIVED_DATA);
        assertEquals(ProjectState.UNKNOWN, result.getState());
        assertEquals(NotReadyReason.MANAGER_UNAVAILABLE, result.getReason());
        assertEquals("DtProjectManager not available", result.getMessage());
    }

    @Test
    public void theSharedUnstartedPredicateMatchesCleanProjectsThreeFacts()
    {
        assertTrue(ProjectStateChecker.isUnstartedEdtProject(true, false, Boolean.TRUE));
        assertFalse(ProjectStateChecker.isUnstartedEdtProject(false, false, Boolean.TRUE));
        assertFalse(ProjectStateChecker.isUnstartedEdtProject(true, true, Boolean.TRUE));
        assertFalse(ProjectStateChecker.isUnstartedEdtProject(true, false, Boolean.FALSE));
        assertFalse(ProjectStateChecker.isUnstartedEdtProject(true, false, null));
    }

    @Test
    public void theLegacyConstructorKeepsDerivedDataReasonMapping()
    {
        assertEquals(NotReadyReason.BUILDING,
            new ProjectStateResult(ProjectState.BUILDING, "busy").getReason());
        assertEquals(NotReadyReason.BUILD_STATE_UNKNOWN,
            new ProjectStateResult(ProjectState.UNKNOWN, "unknown").getReason());
        assertEquals(NotReadyReason.NONE,
            new ProjectStateResult(ProjectState.READY, "ready").getReason());
        assertEquals(NotReadyReason.NONE,
            new ProjectStateResult(ProjectState.NOT_AVAILABLE, "unavailable").getReason());
    }

    @Test
    public void readyOrErrorKeepsTheBuildingTextByteIdentical()
    {
        assertEquals("Project is building: X. Please wait and retry.", ProjectStateChecker.readyOrError(
            new ProjectStateResult(ProjectState.BUILDING, "Project is building: X")));
    }

    @Test
    public void readyOrErrorKeepsTheIncompleteBuildTextByteIdentical()
    {
        assertEquals("Project build in progress (derived data not complete). Please wait and retry.",
            ProjectStateChecker.readyOrError(new ProjectStateResult(ProjectState.BUILDING,
                "Project build in progress (derived data not complete)")));
    }

    @Test
    public void readyOrErrorRetriesAnUnknownBuildState()
    {
        assertEquals("Cannot determine build state. Please wait and retry.",
            ProjectStateChecker.readyOrError(new ProjectStateResult(ProjectState.UNKNOWN,
                "Cannot determine build state")));
    }

    @Test
    public void readyOrErrorRetriesAnUnavailableManager()
    {
        assertEquals("DtProjectManager not available. Please wait and retry.",
            ProjectStateChecker.readyOrError(ProjectStateChecker.classifyContext(
                true, true, false, false, UNUSED_BOOLEAN, UNUSED_BOOLEAN, UNUSED_RESTART, UNUSED_DERIVED_DATA)));
    }

    @Test
    public void readyOrErrorRetriesAStartingContext()
    {
        ProjectStateResult result = ProjectStateChecker.classifyContext(true, true, true, true,
            UNUSED_BOOLEAN, () -> Boolean.FALSE, UNUSED_RESTART, UNUSED_DERIVED_DATA);
        assertEquals(result.getMessage() + ". Please wait and retry.", ProjectStateChecker.readyOrError(result));
    }

    @Test
    public void readyOrErrorRetriesARestartingContext()
    {
        ProjectStateResult result = unregistered(Boolean.TRUE, true);
        assertEquals(result.getMessage() + ". Please wait and retry.", ProjectStateChecker.readyOrError(result));
    }

    @Test
    public void readyOrErrorNeverTellsAnUnstartedContextToWaitAndRetry()
    {
        ProjectStateResult result = unregistered(Boolean.TRUE, false);
        String refusal = ProjectStateChecker.readyOrError(result);
        assertEquals(result.getMessage() + ".", refusal);
        assertFalse(refusal.contains("Please wait and retry"));
    }

    @Test
    public void readyOrErrorDoesNotRetryAPlainProject()
    {
        assertEquals("Not an EDT project.", ProjectStateChecker.readyOrError(unregistered(Boolean.FALSE, false)));
    }

    @Test
    public void readyOrErrorDoesNotRetryAClosedProject()
    {
        assertEquals("Project is closed.", ProjectStateChecker.readyOrError(
            new ProjectStateResult(ProjectState.NOT_AVAILABLE, NotReadyReason.CLOSED, "Project is closed")));
    }

    @Test
    public void readyOrErrorDoesNotRetryAMissingProject()
    {
        assertEquals("Project does not exist.", ProjectStateChecker.readyOrError(
            new ProjectStateResult(ProjectState.NOT_AVAILABLE, NotReadyReason.MISSING, "Project does not exist")));
    }

    @Test
    public void readyOrErrorDoesNotRetryAnUnreadableNature()
    {
        ProjectStateResult result = unregistered(null, false);
        assertEquals(result.getMessage() + ".", ProjectStateChecker.readyOrError(result));
    }

    @Test
    public void readyOrErrorAllowsAReadyProject()
    {
        assertNull(ProjectStateChecker.readyOrError(new ProjectStateResult(ProjectState.READY, "ready")));
    }

    @Test
    public void contextReasonNamesAllThreeContextStates()
    {
        for (ProjectStateResult result : new ProjectStateResult[] {
            unregistered(Boolean.TRUE, false), unregistered(Boolean.TRUE, true),
            ProjectStateChecker.classifyContext(true, true, true, true,
                UNUSED_BOOLEAN, () -> Boolean.FALSE, UNUSED_RESTART, UNUSED_DERIVED_DATA) })
        {
            assertEquals(result.getMessage() + ".", ProjectStateChecker.unstartedContextReasonOrNull(result));
        }
    }

    @Test
    public void contextReasonIgnoresNonContextConditions()
    {
        for (NotReadyReason reason : NotReadyReason.values())
        {
            if (reason != NotReadyReason.CONTEXT_NOT_STARTED && reason != NotReadyReason.CONTEXT_STARTING
                && reason != NotReadyReason.CONTEXT_RESTARTING)
            {
                assertNull(reason.name(), ProjectStateChecker.unstartedContextReasonOrNull(
                    new ProjectStateResult(ProjectState.NOT_AVAILABLE, reason, "unrelated")));
            }
        }
    }

    @Test
    public void siblingRefusalsForPlainAndUnreadableProjectsStayByteIdentical()
    {
        for (Boolean nature : new Boolean[] { Boolean.FALSE, null })
        {
            assertEquals("Not an EDT project: Plain", ProjectStateChecker.withUnstartedContextReason(
                "Not an EDT project: Plain", nature, () -> {
                    fail("A plain or unreadable nature must not query the context");
                    return null;
                }));
        }
    }

    @Test
    public void siblingRefusalsForKnownEdtProjectsAppendTheSharedSentence()
    {
        ProjectStateResult result = unregistered(Boolean.TRUE, false);
        assertEquals("Not an EDT project: Demo. " + result.getMessage() + ".",
            ProjectStateChecker.withUnstartedContextReason("Not an EDT project: Demo",
                Boolean.TRUE, () -> result));
    }

    @Test
    public void siblingRefusalsWithATrailingPeriodDoNotGainADuplicatePeriod()
    {
        ProjectStateResult result = unregistered(Boolean.TRUE, false);
        assertEquals("Original refusal. " + result.getMessage() + ".",
            ProjectStateChecker.withUnstartedContextReason("Original refusal.", Boolean.TRUE, () -> result));
    }

    @Test
    public void siblingRefusalsKeepTheirTextWhenTheCauseIsNotAContext()
    {
        assertEquals("Original refusal", ProjectStateChecker.withUnstartedContextReason(
            "Original refusal", Boolean.TRUE, () -> new ProjectStateResult(ProjectState.UNKNOWN, "unknown")));
    }
}
