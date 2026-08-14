/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** Bounds passes that discover previously unseen participants while EDT contexts keep changing. */
    private static final int MAX_NEW_PARTICIPANT_DISCOVERY_PASSES = 3;

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
        long deadline = System.currentTimeMillis() + settleTimeoutMs;
        // A cascade is not confined to the named project: a rename builds one refactoring per
        // PARTICIPATING project, which includes the configuration EXTENSIONS that adopt the
        // renamed object - drain those too, sharing the SAME deadline, so this cannot multiply
        // the wait. An unrelated open project takes no part in the refactoring and cannot collide
        // with its batch session, so it is never drained or asked about here: one slow, unrelated
        // project must not eat the shared deadline and delay a rename of an otherwise-ready project.
        Set<String> settledParticipantNames = new HashSet<>();
        Set<String> discoveredParticipantNames = new HashSet<>();
        int newParticipantDiscoveryPasses = 0;
        while (true)
        {
            boolean discoveredNewParticipantThisPass = false;
            IProject lastNewParticipant = null;

            // Drain the base UNCONDITIONALLY on every pass. An instant READY probe can become stale
            // while BM models are registering; a quiet pipeline returns immediately, while a project
            // that restarted since the previous pass gets another chance under the shared deadline.
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0)
            {
                env.waitForDerivedData(project, remaining);
            }
            List<IProject> participants = findParticipants(project, env);
            lastNewParticipant = rememberNewParticipants(participants,
                discoveredParticipantNames);
            discoveredNewParticipantThisPass = lastNewParticipant != null;
            IProject stillBuilding = drainParticipants(participants, deadline, env,
                settledParticipantNames);
            String building = env.buildingErrorOrNull(project);
            if (building != null || stillBuilding != null)
            {
                if (discoveredNewParticipantThisPass)
                {
                    newParticipantDiscoveryPasses++;
                }
                String currentError = building != null ? building
                    : participantBuildingError(project, stillBuilding);
                if (newParticipantDiscoveryPasses >= MAX_NEW_PARTICIPANT_DISCOVERY_PASSES
                    || deadline - System.currentTimeMillis() <= 0)
                {
                    // Both probes above are fresh: a limit is never allowed to manufacture a
                    // "still building" claim about a project that was not actually checked.
                    return currentError;
                }
                if (!waitBeforeAnotherSettlePass(deadline, env))
                {
                    return currentError;
                }
                continue;
            }

            // Derived data and BM-model registration are separate EDT lifecycles. A storage reopen can
            // leave the target or one of EDT's dependent refactoring projects without a registered model
            // after the index is already READY. Both mdclass and form refactorings go through the same
            // RefactoringService, which collects dependent models identically, so both wait for the
            // complete refactoring-model set under the SAME deadline.
            String modelError = waitForRefactoringModels(project, deadline, env, operationName,
                stateStatement);
            if (modelError != null)
            {
                return modelError;
            }

            // Model polling happens during the same storage-reopen window that can start fresh derived
            // data. Re-check the base and every participant that was already settled; a stale pre-model
            // verdict must never be the one used to release the cascade.
            String refreshedBuilding = env.buildingErrorOrNull(project);
            participants = findParticipants(project, env);
            IProject newlyDiscovered = rememberNewParticipants(participants,
                discoveredParticipantNames);
            if (newlyDiscovered != null)
            {
                discoveredNewParticipantThisPass = true;
                lastNewParticipant = newlyDiscovered;
            }
            Set<String> rebuildingParticipantNames = new HashSet<>();
            IProject refreshedParticipantBuilding = null;
            for (IProject participant : participants)
            {
                String participantName = participant.getName();
                if (settledParticipantNames.contains(participantName)
                    && env.isBuilding(participant))
                {
                    settledParticipantNames.remove(participantName);
                    rebuildingParticipantNames.add(participantName);
                    if (refreshedParticipantBuilding == null)
                    {
                        refreshedParticipantBuilding = participant;
                    }
                }
            }
            List<IProject> unserved = findUnsettledParticipants(participants,
                settledParticipantNames);
            if (refreshedBuilding == null && unserved.isEmpty())
            {
                return null;
            }

            // Newly discovered participants have not been drained yet. Probe all of them now so a
            // deadline/discovery-limit refusal names a participant that is verifiably building.
            for (IProject participant : unserved)
            {
                if (!rebuildingParticipantNames.contains(participant.getName())
                    && env.isBuilding(participant)
                    && refreshedParticipantBuilding == null)
                {
                    refreshedParticipantBuilding = participant;
                }
            }
            if (discoveredNewParticipantThisPass)
            {
                newParticipantDiscoveryPasses++;
            }
            String currentError = refreshedBuilding;
            if (currentError == null && refreshedParticipantBuilding != null)
            {
                currentError = participantBuildingError(project, refreshedParticipantBuilding);
            }
            if (newParticipantDiscoveryPasses >= MAX_NEW_PARTICIPANT_DISCOVERY_PASSES)
            {
                return currentError != null ? currentError
                    : participantDiscoveryError(project, lastNewParticipant);
            }
            if (deadline - System.currentTimeMillis() <= 0)
            {
                // The fresh probes found no busy project. The deadline forbids more waiting, not a
                // cascade whose complete currently-visible participant set is already idle.
                return currentError;
            }
            if (currentError != null && !waitBeforeAnotherSettlePass(deadline, env))
            {
                return currentError;
            }
        }
    }

    private static boolean waitBeforeAnotherSettlePass(long deadline, CascadeEnvironment env)
    {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0)
        {
            return false;
        }
        return env.waitBeforeModelRetry(Math.min(MODEL_REGISTRATION_POLL_MS, remaining));
    }

    private static String waitForRefactoringModels(IProject project, long deadline,
        CascadeEnvironment env, String operationName, String stateStatement)
    {
        BmModelResolver.Resolution resolution = env.resolveModelsForRefactoring(project);
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
            resolution = env.resolveModelsForRefactoring(project);
        }
        return null;
    }

    /**
     * Waits for the PARTICIPATING open EDT projects' derived data, until the shared
     * {@code deadline}, then verifies them unconditionally.
     * <p>
     * The supplied projects are the participating extensions discovered for the cascade's base:
     * the rename builds a refactoring for each of them, so one that is still building is the
     * collision this whole pre-flight exists to prevent. An unrelated open project is never
     * supplied here and cannot consume the shared deadline or cause a refusal.
     *
     * @param participants the currently visible participating extension projects
     * @param deadline absolute time (ms) the whole drain must not exceed
     * @param env the seam over the workspace/derived-data services
     * @return the first PARTICIPATING extension that is still building, or {@code null} when every
     *         participant settled
     */
    private static IProject drainParticipants(List<IProject> participants, long deadline,
        CascadeEnvironment env,
        Set<String> settledParticipantNames)
    {
        for (IProject participant : findUnsettledParticipants(participants,
            settledParticipantNames))
        {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0)
            {
                env.waitForDerivedData(participant, remaining);
            }
            // Checked after the drain attempt and REGARDLESS of the remaining time: running out of
            // deadline is not a reason to stop asking whether a participant is ready - it only
            // prevents another wait.
            if (env.isBuilding(participant))
            {
                return participant;
            }
            settledParticipantNames.add(participant.getName());
        }
        return null;
    }

    private static IProject rememberNewParticipants(List<IProject> participants,
        Set<String> discoveredParticipantNames)
    {
        IProject lastNewParticipant = null;
        for (IProject participant : participants)
        {
            if (discoveredParticipantNames.add(participant.getName()))
            {
                lastNewParticipant = participant;
            }
        }
        return lastNewParticipant;
    }

    private static List<IProject> findUnsettledParticipants(List<IProject> discoveredParticipants,
        Set<String> settledParticipantNames)
    {
        List<IProject> unsettledParticipants = new ArrayList<>();
        for (IProject participant : discoveredParticipants)
        {
            if (!settledParticipantNames.contains(participant.getName()))
            {
                unsettledParticipants.add(participant);
            }
        }
        return unsettledParticipants;
    }

    private static List<IProject> findParticipants(IProject base, CascadeEnvironment env)
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
        return participants;
    }

    private static String participantBuildingError(IProject base, IProject participant)
    {
        return "Project '" + participant.getName() + "' extends '" + base.getName() //$NON-NLS-1$
            + "' and is still building, so it takes part in this cascade with an " //$NON-NLS-1$
            + "incomplete index. Please wait and retry."; //$NON-NLS-1$
    }

    private static String participantDiscoveryError(IProject base, IProject participant)
    {
        return "Project '" + participant.getName() + "' newly appeared as a cascade participant of '" //$NON-NLS-1$ //$NON-NLS-2$
            + base.getName() + "' while EDT project contexts were still changing. The participant " //$NON-NLS-1$
            + "set did not stabilize, so no cascade was started. Please wait and retry."; //$NON-NLS-1$
    }

    /**
     * Seam over the workspace / derived-data services the cascade pre-flight needs, so a unit
     * test can substitute a fake and exercise {@code drainParticipants} (and
     * {@link #settleBeforeCascadeOrError(IProject, long,
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
