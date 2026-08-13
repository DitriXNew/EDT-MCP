/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Utility class for checking project state and readiness.
 * Uses EDT services to determine if a project is ready for operations.
 */
public final class ProjectStateChecker
{
    /** Poll interval while EDT is reopening project storage and registering BM models. */
    private static final long MODEL_REGISTRATION_POLL_MS = 50L;

    /**
     * Persistent project-description natures declared by EDT 2026.1 for projects that can own a BM
     * model. Unlike {@link IDtProjectManager#getDtProject(IProject)} and
     * {@code IV8ProjectManager.getProjects()}, these do not disappear when EDT disposes and restarts a
     * project context: EDT's source removes both runtime registrations during disposal, while the
     * nature IDs remain in the Eclipse {@code .project} description until the project is converted or
     * deleted. That makes the nature the safe permanent/non-EDT discriminator for this bounded wait.
     */
    private static final List<String> BM_MODEL_PROJECT_NATURES = Arrays.asList(
        "com._1c.g5.v8.dt.core.V8ConfigurationNature", //$NON-NLS-1$
        "com._1c.g5.v8.dt.core.V8ExtensionNature", //$NON-NLS-1$
        "com._1c.g5.v8.dt.core.V8ExternalObjectsNature"); //$NON-NLS-1$

    /**
     * Project state enumeration.
     */
    public enum ProjectState
    {
        /** Project is ready for operations */
        READY("ready"), //$NON-NLS-1$
        
        /** Project is building or computing derived data */
        BUILDING("building"), //$NON-NLS-1$
        
        /** Project is not available (closed, not EDT project, etc.) */
        NOT_AVAILABLE("not_available"), //$NON-NLS-1$
        
        /** State cannot be determined */
        UNKNOWN("unknown"); //$NON-NLS-1$
        
        private final String value;
        
        ProjectState(String value)
        {
            this.value = value;
        }
        
        /**
         * Gets the string value for JSON serialization.
         * @return string value
         */
        public String getValue()
        {
            return value;
        }
    }
    
    /**
     * Result of project state check.
     */
    public static class ProjectStateResult
    {
        private final ProjectState state;
        private final String message;
        private final boolean ready;
        
        public ProjectStateResult(ProjectState state, String message)
        {
            this.state = state;
            this.message = message;
            this.ready = state == ProjectState.READY;
        }
        
        public ProjectState getState()
        {
            return state;
        }
        
        public String getMessage()
        {
            return message;
        }
        
        public boolean isReady()
        {
            return ready;
        }
        
        public String getStateValue()
        {
            return state.getValue();
        }
    }
    
    private ProjectStateChecker()
    {
        // Utility class
    }
    
    /**
     * Checks if a project is ready for operations.
     * A project is ready when:
     * - It exists and is open
     * - It is a valid EDT project
     * - Derived data computations are complete (not building)
     * 
     * @param project the IProject to check
     * @return ProjectStateResult with state and message
     */
    public static ProjectStateResult checkProjectState(IProject project)
    {
        if (project == null)
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "Project is null");
        }
        
        if (!project.exists())
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "Project does not exist");
        }
        
        if (!project.isOpen())
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "Project is closed");
        }
        
        // Get DtProject
        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        if (dtProjectManager == null)
        {
            return new ProjectStateResult(ProjectState.UNKNOWN, "DtProjectManager not available");
        }
        
        IDtProject dtProject = dtProjectManager.getDtProject(project);
        if (dtProject == null)
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "Not an EDT project");
        }
        
        return checkDtProjectState(dtProject);
    }
    
    /**
     * Checks if a DT project is ready for operations.
     * 
     * @param dtProject the IDtProject to check
     * @return ProjectStateResult with state and message
     */
    public static ProjectStateResult checkDtProjectState(IDtProject dtProject)
    {
        if (dtProject == null)
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "DtProject is null");
        }
        
        // Check derived data status
        IDerivedDataManagerProvider ddProvider = Activator.getDefault().getDerivedDataManagerProvider();
        if (ddProvider == null)
        {
            // Cannot determine state without DD provider
            Activator.logInfo("DerivedDataManagerProvider not available for " + dtProject.getName());
            return new ProjectStateResult(ProjectState.UNKNOWN, "Cannot determine build state");
        }
        
        IDerivedDataManager ddManager = ddProvider.get(dtProject);
        if (ddManager == null)
        {
            Activator.logInfo("DerivedDataManager not available for " + dtProject.getName());
            return new ProjectStateResult(ProjectState.UNKNOWN, "Cannot determine build state");
        }
        
        // Check if computation pipeline is idle
        if (!ddManager.isIdle())
        {
            DerivedDataStatus status = ddManager.getDerivedDataStatus();
            String statusStr = status != null ? status.toString() : "computing";
            return new ProjectStateResult(ProjectState.BUILDING, 
                "Project is building: " + statusStr);
        }
        
        // Check if all derived data is computed
        if (!ddManager.isAllComputed())
        {
            return new ProjectStateResult(ProjectState.BUILDING, 
                "Project build in progress (derived data not complete)");
        }
        
        return new ProjectStateResult(ProjectState.READY, "Project is ready");
    }
    
    /**
     * Checks if a project is ready and returns error message if not.
     * Convenience method for tools that need to check before executing.
     * 
     * @param project the IProject to check
     * @return null if ready, error message if not ready
     */
    public static String checkReadyOrError(IProject project)
    {
        ProjectStateResult result = checkProjectState(project);
        if (result.isReady())
        {
            return null;
        }
        return result.getMessage() + ". Please wait and retry.";
    }
    
    /**
     * Checks if a project is ready and returns error message if not.
     * Convenience method for tools that need to check before executing.
     * 
     * @param projectName the project name to check
     * @return null if ready, error message if not ready
     */
    public static String checkReadyOrError(String projectName)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return null; // No specific project, skip check
        }
        
        IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
            .getRoot().getProject(projectName);

        return checkReadyOrError(project);
    }

    /**
     * Returns a "still building" error message ONLY when the project's derived data
     * (the reference index) is actively building, otherwise {@code null}.
     * <p>
     * Unlike {@link #checkReadyOrError(IProject)} this does NOT reject a project that is
     * merely missing / closed / unknown: those are PERMANENT conditions a retry will not
     * fix, and the caller's own resolution yields a sharper, value-naming error
     * ("Project not found: X"). Use this for a model-mutating or cascade pre-flight where
     * the only state worth refusing for is a transient in-progress build (running the
     * cascade against an incomplete index would silently miss references).
     *
     * @param project the IProject to check
     * @return the building message with a retry hint, or {@code null} when not building
     */
    public static String buildingErrorOrNull(IProject project)
    {
        ProjectStateResult result = checkProjectState(project);
        if (result.getState() == ProjectState.BUILDING)
        {
            return result.getMessage() + ". Please wait and retry."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The pre-flight for a CASCADE operation (a rename / delete refactoring): actively waits for the
     * project's derived-data pipeline to drain and for EDT to register every BM model the refactoring
     * will use, then returns an actionable error when either condition did not settle in time.
     * <p>
     * {@link #buildingErrorOrNull(IProject)} alone is an INSTANT probe, and a cascade needs more than
     * that. EDT's refactoring opens a BM batch session; a derived-data task that is still pending when
     * it does cannot run ("Unable to execute task because batch session is active") and the refactoring
     * then waits for the pipeline from INSIDE the session - measured at 301 seconds on CI, with the
     * call finally succeeding. Whoever is on the wire has long since timed out, which is what made
     * this look like a flaky test rather than what it is: an unbounded wait we walked into.
     * <p>
     * So: drain first, on the CALLER's thread (never inside the UI-thread scope - the pipeline may
     * need it), and if the pipeline is still busy afterwards, refuse with the same actionable,
     * retryable message every other tool uses instead of blocking the wire for five minutes.
     *
     * @param projectName the project the cascade will mutate (a null/empty name skips the check)
     * @param settleTimeoutMs how long to wait for the pipeline and BM models to settle
     * @return an actionable error, or {@code null} when the cascade may proceed
     */
    public static String settleBeforeCascadeOrError(String projectName, long settleTimeoutMs)
    {
        return settleBeforeCascadeOrError(projectName, settleTimeoutMs,
            "the cascade operation", "No cascade was started."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Operation-aware cascade pre-flight. The operation and state statement keep a BM-model timeout
     * identical to the guarded refusal the calling tool already exposes.
     *
     * @param projectName the project the cascade will mutate (a null/empty name skips the check)
     * @param settleTimeoutMs how long to wait for derived data and BM-model registration
     * @param operationName the MCP tool the caller may retry
     * @param stateStatement what is known about the refused mutation, including punctuation
     * @return an actionable error, or {@code null} when the cascade may proceed
     */
    public static String settleBeforeCascadeOrError(String projectName, long settleTimeoutMs,
        String operationName, String stateStatement)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return null;
        }
        IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
            .getRoot().getProject(projectName);
        return settleBeforeCascadeOrError(project, settleTimeoutMs, CascadeEnvironment.DEFAULT,
            operationName, stateStatement);
    }

    /**
     * Form-refactoring settle variant. EDT's form branch builds one refactoring for the target form
     * through {@code IFormRefactoringService}; it does not execute the mdclass service's dependent-
     * model mapping. Derived data is still drained under the shared deadline, but only the target
     * project's BM model is required.
     *
     * @param projectName the project containing the target form
     * @param settleTimeoutMs how long to wait for derived data and target-model registration
     * @param operationName the MCP tool the caller may retry
     * @param stateStatement what is known about the refused mutation, including punctuation
     * @return an actionable error, or {@code null} when the form refactoring may proceed
     */
    public static String settleBeforeTargetModelOrError(String projectName, long settleTimeoutMs,
        String operationName, String stateStatement)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return null;
        }
        IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
            .getRoot().getProject(projectName);
        return settleBeforeCascadeOrError(project, settleTimeoutMs, CascadeEnvironment.DEFAULT,
            operationName, stateStatement, false);
    }

    /**
     * Seam-taking variant of {@link #settleBeforeCascadeOrError(String, long)}, package-visible so a
     * unit test can drive it with a fake {@link CascadeEnvironment} and no live workspace / EDT
     * services. Production code only ever reaches this through the {@code (String, long)} overload,
     * which resolves {@code project} from the workspace and injects {@link CascadeEnvironment#DEFAULT}.
     *
     * @param project the project the cascade will mutate
     * @param settleTimeoutMs how long to wait for the pipeline and BM models to settle
     * @param env the seam over the workspace/derived-data services
     * @return an actionable error, or {@code null} when the cascade may proceed
     */
    static String settleBeforeCascadeOrError(IProject project, long settleTimeoutMs, CascadeEnvironment env)
    {
        return settleBeforeCascadeOrError(project, settleTimeoutMs, env,
            "the cascade operation", "No cascade was started."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Package-visible operation-aware seam for headless cascade-settle tests. */
    static String settleBeforeCascadeOrError(IProject project, long settleTimeoutMs,
        CascadeEnvironment env, String operationName, String stateStatement)
    {
        return settleBeforeCascadeOrError(project, settleTimeoutMs, env, operationName,
            stateStatement, true);
    }

    /** Package-visible target-model-only seam for headless form-refactoring settle tests. */
    static String settleBeforeTargetModelOrError(IProject project, long settleTimeoutMs,
        CascadeEnvironment env, String operationName, String stateStatement)
    {
        return settleBeforeCascadeOrError(project, settleTimeoutMs, env, operationName,
            stateStatement, false);
    }

    private static String settleBeforeCascadeOrError(IProject project, long settleTimeoutMs,
        CascadeEnvironment env, String operationName, String stateStatement,
        boolean includeDependentModels)
    {
        if (!project.exists() || !project.isOpen())
        {
            // Nothing to drain, and asking anyway can block on a project EDT is still disposing.
            // A missing / closed project is not this method's error to report either - the caller's
            // own resolution names the value ("Project not found: X").
            return null;
        }
        if (Boolean.FALSE.equals(env.hasBmModelProjectNature(project)))
        {
            // This is permanently outside EDT; let the caller's project/configuration validation
            // produce its established error instead of advising a retry that can never succeed.
            return null;
        }
        // Drain UNCONDITIONALLY - do not gate this on the instant probe. On the CI run that
        // exposed this, the probe answered READY and a derived-data task was executing one
        // second later, so a drain that only ran when the probe said BUILDING would have been
        // skipped on the very call it exists for. With a quiet pipeline (or a name that is not
        // an EDT project at all) waitAllComputations returns immediately, so the cost is zero
        // where there is nothing to wait for.
        long deadline = System.currentTimeMillis() + settleTimeoutMs;
        env.waitForDerivedData(project, settleTimeoutMs);
        // A cascade is not confined to the named project: a rename builds one refactoring per
        // PARTICIPATING project, which includes the configuration EXTENSIONS that adopt the
        // renamed object - drain those too, sharing the SAME deadline, so this cannot multiply
        // the wait. An unrelated open project takes no part in the refactoring and cannot collide
        // with its batch session, so it is never drained or asked about here: one slow, unrelated
        // project must not eat the shared deadline and delay a rename of an otherwise-ready project.
        String stillBuilding = drainParticipants(project, deadline, env);
        String building = env.buildingErrorOrNull(project);
        if (building != null)
        {
            return building;
        }
        // A PARTICIPATING extension that did not settle is refused like the base project would be:
        // the cascade is about to enter its refactoring too.
        if (stillBuilding != null)
        {
            return stillBuilding;
        }

        // Derived data and BM-model registration are separate EDT lifecycles. A storage reopen can
        // leave the target or one of EDT's dependent refactoring projects without a registered model
        // after the index is already READY. Poll the shared resolver under the SAME deadline so a
        // transient close/reopen window is waited out, while a model that never returns produces the
        // same actionable refusal as the final guard at the refactoring call site.
        return waitForRefactoringModels(project, deadline, env, operationName, stateStatement,
            includeDependentModels);
    }

    private static String waitForRefactoringModels(IProject project, long deadline,
        CascadeEnvironment env, String operationName, String stateStatement,
        boolean includeDependentModels)
    {
        BmModelResolver.Resolution resolution = includeDependentModels
            ? env.resolveModelsForRefactoring(project)
            : env.resolveTargetModel(project);
        while (!resolution.isAvailable())
        {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0)
            {
                return resolution.actionableError(operationName, stateStatement);
            }
            long waitMs = Math.min(MODEL_REGISTRATION_POLL_MS, remaining);
            if (!env.waitBeforeModelRetry(waitMs))
            {
                return resolution.actionableError(operationName, stateStatement);
            }
            resolution = includeDependentModels
                ? env.resolveModelsForRefactoring(project)
                : env.resolveTargetModel(project);
        }
        return null;
    }

    /**
     * Waits for the PARTICIPATING open EDT projects' derived data, until the shared
     * {@code deadline}, then verifies them unconditionally.
     * <p>
     * The projects that take part in the cascade are the ones that extend {@code base} (per
     * {@link CascadeEnvironment#resolveBaseProject(IProject)}): the rename builds a refactoring
     * for each of them, so one that is still building is the collision this whole pre-flight
     * exists to prevent. An unrelated open project is not a participant - it cannot collide with
     * this cascade, so it is never drained, never asked about, and never able to consume the
     * shared deadline or cause a refusal.
     *
     * @param base the project already drained by the caller
     * @param deadline absolute time (ms) the whole drain must not exceed
     * @param env the seam over the workspace/derived-data services
     * @return the retryable message for a PARTICIPATING extension that is still building (naming
     *         it), or {@code null} when every participant settled
     */
    private static String drainParticipants(IProject base, long deadline, CascadeEnvironment env)
    {
        List<IProject> participants = new ArrayList<>();
        for (IProject candidate : env.getOpenDtProjects())
        {
            if (candidate.equals(base))
            {
                continue;
            }
            if (env.isExtensionProject(candidate) && base.equals(env.resolveBaseProject(candidate)))
            {
                participants.add(candidate);
            }
        }
        drainAll(participants, deadline, env);
        // Checked AFTER the drain and REGARDLESS of the remaining time: running out of deadline
        // is not a reason to stop asking whether a participant is ready - it is a reason to say so.
        for (IProject participant : participants)
        {
            if (env.isBuilding(participant))
            {
                return "Project '" + participant.getName() + "' extends '" + base.getName() //$NON-NLS-1$
                    + "' and is still building, so it takes part in this cascade with an " //$NON-NLS-1$
                    + "incomplete index. Please wait and retry."; //$NON-NLS-1$
            }
        }
        return null;
    }

    /** Drains each project in turn, giving up as soon as the shared deadline is spent. */
    private static void drainAll(List<IProject> projects, long deadline, CascadeEnvironment env)
    {
        for (IProject project : projects)
        {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0)
            {
                return;
            }
            env.waitForDerivedData(project, remaining);
        }
    }

    /**
     * Seam over the workspace / derived-data services the cascade pre-flight needs, so a unit
     * test can substitute a fake and exercise {@link #drainParticipants(IProject, long,
     * CascadeEnvironment)} (and {@link #settleBeforeCascadeOrError(IProject, long,
     * CascadeEnvironment)}) with no live workspace. {@link #DEFAULT} delegates to the same EDT
     * services ({@link IDtProjectManager}, {@link ExtensionOriginUtils#resolveBaseProject(IProject)},
     * {@link BuildUtils#waitForDerivedData(IProject, long)},
     * {@link BmModelResolver#resolveForRefactoring(IProject)}) this pre-flight uses.
     * <p>
     * Public (unlike the package-visible {@code settleBeforeCascadeOrError} overload that takes
     * it): Mockito's proxy generation cannot mock a non-public type across the fragment-test /
     * host-bundle classloader split this test bundle runs under, so the type itself must be
     * accessible even though only test code in this package ever implements or references it.
     */
    public interface CascadeEnvironment
    {
        /** The open EDT projects currently in the workspace (participants and unrelated alike). */
        List<IProject> getOpenDtProjects();

        /**
         * Resolves the BASE (parent) project a dependent project derives from, or {@code null} when
         * {@code project} is not dependent on another project. NB an EXTERNAL-OBJECTS project is
         * dependent too - see {@link #isExtensionProject(IProject)} for why that matters here.
         */
        IProject resolveBaseProject(IProject project);

        /**
         * Whether {@code project} is a configuration EXTENSION (not merely dependent).
         * <p>
         * The cascade builds one refactoring per EXTENSION of the renamed object's configuration.
         * An external-objects project shares the same parent and would answer
         * {@link #resolveBaseProject(IProject)} identically, yet takes no part in that cascade -
         * treating it as a participant would let it spend the shared drain budget and, worse,
         * refuse the rename with an "extends ... still building" error about a project the rename
         * never touches.
         */
        boolean isExtensionProject(IProject project);

        /** Waits, bounded by {@code timeoutMs}, for {@code project}'s derived-data pipeline to drain. */
        void waitForDerivedData(IProject project, long timeoutMs);

        /** Whether {@code project}'s derived-data pipeline is still (transiently) building. */
        boolean isBuilding(IProject project);

        /** The target project's actionable build error, or {@code null} when it has settled. */
        String buildingErrorOrNull(IProject project);

        /**
         * Whether the persistent project description carries an EDT nature that can own a BM model.
         * {@code null} means the description could not be read, which is not proof of a non-EDT project.
         */
        Boolean hasBmModelProjectNature(IProject project);

        /** Resolves all BM models EDT will map while constructing this project's refactoring. */
        BmModelResolver.Resolution resolveModelsForRefactoring(IProject project);

        /** Resolves only the target project's BM model for a single-form refactoring. */
        BmModelResolver.Resolution resolveTargetModel(IProject project);

        /** Waits before another BM-model resolution attempt; {@code false} means the wait was interrupted. */
        boolean waitBeforeModelRetry(long timeoutMs);

        /** Delegates to the live, {@code Activator}-backed EDT services. */
        CascadeEnvironment DEFAULT = new CascadeEnvironment()
        {
            @Override
            public List<IProject> getOpenDtProjects()
            {
                List<IProject> result = new ArrayList<>();
                IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
                if (dtProjectManager == null)
                {
                    return result;
                }
                for (IProject candidate : org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                    .getRoot().getProjects())
                {
                    if (candidate.exists() && candidate.isOpen()
                        && dtProjectManager.getDtProject(candidate) != null)
                    {
                        result.add(candidate);
                    }
                }
                return result;
            }

            @Override
            public IProject resolveBaseProject(IProject project)
            {
                return ExtensionOriginUtils.resolveBaseProject(project);
            }

            @Override
            public boolean isExtensionProject(IProject project)
            {
                return ExtensionOriginUtils.isExtensionProject(project);
            }

            @Override
            public void waitForDerivedData(IProject project, long timeoutMs)
            {
                BuildUtils.waitForDerivedData(project, timeoutMs);
            }

            @Override
            public boolean isBuilding(IProject project)
            {
                return buildingErrorOrNull(project) != null;
            }

            @Override
            public String buildingErrorOrNull(IProject project)
            {
                return ProjectStateChecker.buildingErrorOrNull(project);
            }

            @Override
            public Boolean hasBmModelProjectNature(IProject project)
            {
                return ProjectContext.hasAnyNature(project, BM_MODEL_PROJECT_NATURES);
            }

            @Override
            public BmModelResolver.Resolution resolveModelsForRefactoring(IProject project)
            {
                return BmModelResolver.resolveForRefactoring(project);
            }

            @Override
            public BmModelResolver.Resolution resolveTargetModel(IProject project)
            {
                return BmModelResolver.resolve(project);
            }

            @Override
            public boolean waitBeforeModelRetry(long timeoutMs)
            {
                try
                {
                    Thread.sleep(timeoutMs);
                    return true;
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        };
    }

    /**
     * Name-based variant of {@link #buildingErrorOrNull(IProject)}. A null/empty name
     * skips the check (returns {@code null}), leaving the caller's required-argument
     * handling to produce the proper error.
     *
     * @param projectName the project name to check
     * @return the building message with a retry hint, or {@code null} when not building
     */
    public static String buildingErrorOrNull(String projectName)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return null;
        }
        IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
            .getRoot().getProject(projectName);
        return buildingErrorOrNull(project);
    }
}
