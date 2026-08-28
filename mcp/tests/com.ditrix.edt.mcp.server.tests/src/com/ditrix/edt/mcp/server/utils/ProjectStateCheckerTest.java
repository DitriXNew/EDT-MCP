/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;

import com.ditrix.edt.mcp.server.utils.ExtensionOriginUtils.DeclaredBaseProject;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.CascadeEnvironment;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.SearchDependenciesResult;

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

    private static CascadeEnvironment mockEnvironmentWithAvailableModels()
    {
        IProject project = mockOpenProject("ModelProject"); //$NON-NLS-1$
        IBmModelManager modelManager = mock(IBmModelManager.class);
        when(modelManager.getModel(project)).thenReturn(mock(IBmModel.class));
        BmModelResolver.Resolution available = BmModelResolver.resolve(project, modelManager);
        CascadeEnvironment env = mock(CascadeEnvironment.class);
        when(env.hasBmModelProjectNature(any(IProject.class))).thenReturn(null);
        when(env.resolveModelsForRefactoring(any(IProject.class))).thenReturn(available);
        return env;
    }

    @Test
    public void failureAwareDependencyLookupDistinguishesNoDependenciesFromDiscoveryFailure()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        CascadeEnvironment noDependencies = mock(CascadeEnvironment.class);
        when(noDependencies.getOpenDtProjects()).thenReturn(Collections.singletonList(base));
        when(noDependencies.getOpenDependentNatureProjects()).thenReturn(Collections.emptyList());
        when(noDependencies.getOpenExtensionNatureProjects()).thenReturn(Collections.emptyList());
        ProjectStateResult ready = new ProjectStateResult(ProjectState.READY, "ready"); //$NON-NLS-1$
        when(noDependencies.getProjectState(base)).thenReturn(ready);

        SearchDependenciesResult empty =
            ProjectStateChecker.determineSearchDependencies(base, noDependencies);

        assertTrue(empty.isDetermined());
        assertEquals(Collections.singletonList(base), empty.getProjects());
        assertTrue(empty.getExtensionProjects().isEmpty());

        CascadeEnvironment failed = mock(CascadeEnvironment.class);
        when(failed.getOpenDtProjects()).thenThrow(new IllegalStateException("workspace changed")); //$NON-NLS-1$

        SearchDependenciesResult undetermined =
            ProjectStateChecker.determineSearchDependencies(base, failed);

        assertFalse(undetermined.isDetermined());
        assertTrue(undetermined.getProjects().isEmpty());
        assertTrue(undetermined.getExtensionProjects().isEmpty());
    }

    @Test
    public void extensionWithUnavailableBaseResolutionIsUndetermined()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject extension = mockOpenProject("Base.tests"); //$NON-NLS-1$
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        when(environment.getOpenDtProjects()).thenReturn(Arrays.asList(base, extension));
        when(environment.getOpenDependentNatureProjects())
            .thenReturn(Collections.singletonList(extension));
        when(environment.getOpenExtensionNatureProjects())
            .thenReturn(Collections.singletonList(extension));
        when(environment.resolveBaseProject(extension)).thenReturn(null);

        SearchDependenciesResult result =
            ProjectStateChecker.determineSearchDependencies(base, environment);

        assertFalse(result.isDetermined());
    }

    @Test
    public void extensionNatureWithoutRuntimeExtensionClassificationIsUndetermined()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject extension = mockOpenProject("Base.tests"); //$NON-NLS-1$
        List<IProject> openProjects = Arrays.asList(base, extension);
        List<IProject> extensionProjects = Collections.singletonList(extension);
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        when(environment.getOpenDtProjects()).thenReturn(openProjects);
        when(environment.getOpenDependentNatureProjects()).thenReturn(extensionProjects);
        when(environment.getOpenExtensionNatureProjects()).thenReturn(extensionProjects);
        when(environment.resolveBaseProject(extension)).thenReturn(base);
        when(environment.isExtensionProject(extension)).thenReturn(false);

        SearchDependenciesResult result =
            ProjectStateChecker.determineSearchDependencies(base, environment);

        assertFalse(result.isDetermined());
    }

    @Test
    public void searchDependenciesDeriveExtensionTargetsAsSubsetOfSourceProjects()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject extension = mockOpenProject("Base.tests"); //$NON-NLS-1$
        IProject externalObjects = mockOpenProject("ExternalObjects"); //$NON-NLS-1$
        IProject unrelatedDependent = mockOpenProject("Other.tests"); //$NON-NLS-1$
        IProject otherBase = mockOpenProject("Other"); //$NON-NLS-1$
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        when(environment.getOpenDtProjects()).thenReturn(
            Arrays.asList(base, extension, externalObjects, unrelatedDependent));
        when(environment.getOpenDependentNatureProjects()).thenReturn(
            Arrays.asList(extension, externalObjects, unrelatedDependent));
        when(environment.getOpenExtensionNatureProjects())
            .thenReturn(Arrays.asList(extension, unrelatedDependent));
        when(environment.resolveBaseProject(extension)).thenReturn(base);
        when(environment.resolveBaseProject(externalObjects)).thenReturn(base);
        when(environment.resolveBaseProject(unrelatedDependent)).thenReturn(otherBase);
        when(environment.isExtensionProject(extension)).thenReturn(true);
        when(environment.isExtensionProject(unrelatedDependent)).thenReturn(true);
        when(environment.getProjectState(any(IProject.class)))
            .thenReturn(new ProjectStateResult(ProjectState.READY, "ready")); //$NON-NLS-1$

        SearchDependenciesResult search =
            ProjectStateChecker.determineSearchDependencies(base, environment);

        assertTrue(search.isDetermined());
        assertTrue(search.isAllReady());
        assertEquals(3, search.getProjectNames().size());
        assertTrue(search.getProjectNames().contains("Base")); //$NON-NLS-1$
        assertTrue(search.getProjectNames().contains("Base.tests")); //$NON-NLS-1$
        assertTrue(search.getProjectNames().contains("ExternalObjects")); //$NON-NLS-1$
        assertFalse(search.getProjectNames().contains("Other.tests")); //$NON-NLS-1$
        assertEquals(Collections.singletonList(extension), search.getExtensionProjects());
        assertTrue(search.getProjects().containsAll(search.getExtensionProjects()));
    }

    /**
     * An external-objects project is legitimately UNLINKED - {@code create_project} REJECTS
     * baseProjectName for that kind, so this is the state it is CREATED in. Treating that null
     * parent as "undeterminable" hands every workspace merely containing one the workspace-wide
     * scan this scoping exists to avoid, and (because adopted-target augmentation needs the
     * snapshot) silently drops references living in a genuine extension.
     */
    @Test
    public void unlinkedExternalObjectsProjectIsUnrelatedRatherThanUndetermined()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject extension = mockOpenProject("Base.tests"); //$NON-NLS-1$
        IProject unlinkedExternalObjects = mockOpenProject("UnlinkedProbe"); //$NON-NLS-1$
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        when(environment.getOpenDtProjects())
            .thenReturn(Arrays.asList(base, extension, unlinkedExternalObjects));
        when(environment.getOpenDependentNatureProjects())
            .thenReturn(Arrays.asList(extension, unlinkedExternalObjects));
        when(environment.getOpenExtensionNatureProjects())
            .thenReturn(Collections.singletonList(extension));
        when(environment.resolveBaseProject(extension)).thenReturn(base);
        when(environment.resolveBaseProject(unlinkedExternalObjects)).thenReturn(null);
        when(environment.isExtensionProject(extension)).thenReturn(true);
        when(environment.isExtensionProject(unlinkedExternalObjects)).thenReturn(false);
        when(environment.readDeclaredBaseProject(unlinkedExternalObjects))
            .thenReturn(DeclaredBaseProject.NONE);
        when(environment.getProjectState(any(IProject.class)))
            .thenReturn(new ProjectStateResult(ProjectState.READY, "ready")); //$NON-NLS-1$

        SearchDependenciesResult search =
            ProjectStateChecker.determineSearchDependencies(base, environment);

        assertTrue(search.isDetermined());
        assertEquals(2, search.getProjectNames().size());
        assertTrue(search.getProjectNames().contains("Base")); //$NON-NLS-1$
        assertTrue(search.getProjectNames().contains("Base.tests")); //$NON-NLS-1$
        assertFalse(search.getProjectNames().contains("UnlinkedProbe")); //$NON-NLS-1$
        assertEquals(Collections.singletonList(extension), search.getExtensionProjects());
    }

    /**
     * The unlinked-is-unrelated shortcut applies ONLY where both views agree the project is not an
     * extension. A runtime registration claiming extension kind while the permanent nature does not
     * is unclassifiable, and an extension without a base means an unusable registration - never
     * "unrelated".
     */
    @Test
    public void unlinkedDependentClaimingRuntimeExtensionKindIsUndetermined()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject unlinkedDependent = mockOpenProject("UnlinkedProbe"); //$NON-NLS-1$
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        when(environment.getOpenDtProjects()).thenReturn(Arrays.asList(base, unlinkedDependent));
        when(environment.getOpenDependentNatureProjects())
            .thenReturn(Collections.singletonList(unlinkedDependent));
        when(environment.getOpenExtensionNatureProjects()).thenReturn(Collections.emptyList());
        when(environment.resolveBaseProject(unlinkedDependent)).thenReturn(null);
        when(environment.isExtensionProject(unlinkedDependent)).thenReturn(true);

        SearchDependenciesResult result =
            ProjectStateChecker.determineSearchDependencies(base, environment);

        assertFalse(result.isDetermined());
    }

    /**
     * A null runtime parent is NOT proof of unlinkedness: EDT's {@code AbstractDependentProject}
     * returns null when the parent is not wired yet AND when the parent project is merely not
     * accessible. A project whose manifest still declares a base therefore has an unusable
     * registration - skipping it would drop its indexed references while reporting the scan complete.
     */
    @Test
    public void dependentDeclaringABaseItCannotResolveIsUndetermined()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject externalObjects = mockOpenProject("ExternalObjects"); //$NON-NLS-1$
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        when(environment.getOpenDtProjects()).thenReturn(Arrays.asList(base, externalObjects));
        when(environment.getOpenDependentNatureProjects())
            .thenReturn(Collections.singletonList(externalObjects));
        when(environment.getOpenExtensionNatureProjects()).thenReturn(Collections.emptyList());
        when(environment.resolveBaseProject(externalObjects)).thenReturn(null);
        when(environment.isExtensionProject(externalObjects)).thenReturn(false);
        when(environment.readDeclaredBaseProject(externalObjects))
            .thenReturn(DeclaredBaseProject.DECLARED);

        SearchDependenciesResult result =
            ProjectStateChecker.determineSearchDependencies(base, environment);

        assertFalse(result.isDetermined());
    }

    /** An unreadable manifest proves nothing, so it must not earn the unrelated shortcut either. */
    @Test
    public void dependentWithUnreadableManifestIsUndetermined()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject externalObjects = mockOpenProject("ExternalObjects"); //$NON-NLS-1$
        CascadeEnvironment environment = mock(CascadeEnvironment.class);
        when(environment.getOpenDtProjects()).thenReturn(Arrays.asList(base, externalObjects));
        when(environment.getOpenDependentNatureProjects())
            .thenReturn(Collections.singletonList(externalObjects));
        when(environment.getOpenExtensionNatureProjects()).thenReturn(Collections.emptyList());
        when(environment.resolveBaseProject(externalObjects)).thenReturn(null);
        when(environment.isExtensionProject(externalObjects)).thenReturn(false);
        when(environment.readDeclaredBaseProject(externalObjects))
            .thenReturn(DeclaredBaseProject.UNREADABLE);

        SearchDependenciesResult result =
            ProjectStateChecker.determineSearchDependencies(base, environment);

        assertFalse(result.isDetermined());
    }

    @Test(expected = IllegalArgumentException.class)
    public void dependencySnapshotRejectsTargetExtensionOutsideSourceScope()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject omittedExtension = mockOpenProject("Base.tests"); //$NON-NLS-1$
        List<IProject> searchProjects = Collections.singletonList(base);
        List<IProject> extensionProjects = Collections.singletonList(omittedExtension);
        Map<String, ProjectState> readiness = new LinkedHashMap<>();
        readiness.put("Base", ProjectState.READY); //$NON-NLS-1$

        SearchDependenciesResult.determined(searchProjects, extensionProjects, readiness);
    }

    @Test
    public void openNonEdtProjectSkipsBmModelPolling()
    {
        IProject project = mockOpenProject("PlainJavaProject"); //$NON-NLS-1$
        CascadeEnvironment env = mock(CascadeEnvironment.class);
        when(env.hasBmModelProjectNature(project)).thenReturn(Boolean.FALSE);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(project,
            SETTLE_TIMEOUT_MS, env);

        assertNull(result);
        verify(env, never()).waitForDerivedData(any(IProject.class), anyLong());
        verify(env, never()).resolveModelsForRefactoring(any(IProject.class));
        verify(env, never()).waitBeforeModelRetry(anyLong());
    }

    @Test
    public void formRenameSettleRefusesWhenADependentModelIsMissing()
    {
        IProject project = mockOpenProject("FormProject"); //$NON-NLS-1$
        IProject dependent = mockOpenProject("FormProjectExtension"); //$NON-NLS-1$
        IBmModelManager modelManager = mock(IBmModelManager.class);
        when(modelManager.getModel(dependent)).thenReturn(null);
        BmModelResolver.Resolution unavailable = BmModelResolver.resolve(dependent, modelManager);
        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.resolveModelsForRefactoring(project)).thenReturn(unavailable);
        when(env.waitBeforeModelRetry(anyLong())).thenReturn(false);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(project,
            SETTLE_TIMEOUT_MS, env, "rename_metadata_object", "Nothing was renamed."); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the form rename must be refused by the missing dependent model: " + result, //$NON-NLS-1$
            result.contains("project 'FormProjectExtension'")); //$NON-NLS-1$
        verify(env).resolveModelsForRefactoring(project);
        verify(env).waitBeforeModelRetry(anyLong());
    }

    @Test
    public void busyParticipantIsRefusedByName()
    {
        // A participant (its base resolves to the renamed project) that is still building must
        // refuse the cascade, and the message must NAME that participant - the actionable detail
        // an agent needs to know which project to wait on.
        IProject base = mockOpenProject("Base");
        IProject participant = mockOpenProject("Ext1");

        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.getOpenDtProjects()).thenReturn(Collections.singletonList(participant));
        when(env.isExtensionProject(participant)).thenReturn(true);
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

        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
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
    public void busyExternalObjectsProjectIsNotACascadeParticipant()
    {
        // An EXTERNAL-OBJECTS project is dependent on the same base (resolveBaseProject answers
        // identically to a real participant's), but it is not an EXTENSION, so it takes no part
        // in the rename cascade: even while busy it must never be waited on and must never cause
        // a refusal - doing either would spend the shared drain budget, or reject the rename, over
        // a project the rename never touches.
        IProject base = mockOpenProject("Base");
        IProject externalObjects = mockOpenProject("ExternalObjects");

        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.getOpenDtProjects()).thenReturn(Collections.singletonList(externalObjects));
        when(env.resolveBaseProject(externalObjects)).thenReturn(base);
        when(env.isExtensionProject(externalObjects)).thenReturn(false);
        when(env.isBuilding(externalObjects)).thenReturn(true);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base, SETTLE_TIMEOUT_MS, env);

        assertNull("a busy external-objects project must never cause a refusal", result);
        verify(env, never()).waitForDerivedData(eq(externalObjects), anyLong());
    }

    @Test
    public void settledParticipantReturnsNull()
    {
        // A participant that is NOT building lets the cascade proceed.
        IProject base = mockOpenProject("Base");
        IProject participant = mockOpenProject("Ext1");

        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.getOpenDtProjects()).thenReturn(Collections.singletonList(participant));
        when(env.isExtensionProject(participant)).thenReturn(true);
        when(env.resolveBaseProject(participant)).thenReturn(base);
        when(env.isBuilding(participant)).thenReturn(false);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base, SETTLE_TIMEOUT_MS, env);

        assertNull(result);
        verify(env).waitForDerivedData(eq(participant), anyLong());
    }

    @Test
    public void expiredDeadlineChecksIdleParticipantsWithoutWaitingOrRefusing()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject initialParticipant = mockOpenProject("InitialExtension"); //$NON-NLS-1$
        IProject rediscoveredParticipant = mockOpenProject("RediscoveredExtension"); //$NON-NLS-1$

        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.getOpenDtProjects()).thenReturn(Collections.singletonList(initialParticipant),
            Arrays.asList(initialParticipant, rediscoveredParticipant));
        when(env.isExtensionProject(initialParticipant)).thenReturn(true);
        when(env.isExtensionProject(rediscoveredParticipant)).thenReturn(true);
        when(env.resolveBaseProject(initialParticipant)).thenReturn(base);
        when(env.resolveBaseProject(rediscoveredParticipant)).thenReturn(base);
        when(env.isBuilding(initialParticipant)).thenReturn(false);
        when(env.isBuilding(rediscoveredParticipant)).thenReturn(false);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base, 0L, env);

        assertNull(result);
        verify(env, never()).waitForDerivedData(eq(initialParticipant), anyLong());
        verify(env, never()).waitForDerivedData(eq(rediscoveredParticipant), anyLong());
        verify(env, times(2)).isBuilding(initialParticipant);
        verify(env).isBuilding(rediscoveredParticipant);
    }

    @Test
    public void expiredDeadlineRefusesRediscoveredBuildingParticipantByName()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject participant = mockOpenProject("RestartedExtension"); //$NON-NLS-1$

        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.getOpenDtProjects()).thenReturn(Collections.emptyList(),
            Collections.singletonList(participant));
        when(env.isExtensionProject(participant)).thenReturn(true);
        when(env.resolveBaseProject(participant)).thenReturn(base);
        when(env.isBuilding(participant)).thenReturn(true);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base, 0L, env);

        assertTrue("must refuse the participant that is actually still building", result != null); //$NON-NLS-1$
        assertTrue("the refusal must name the building participant: " + result, //$NON-NLS-1$
            result.contains("RestartedExtension")); //$NON-NLS-1$
        verify(env, never()).waitForDerivedData(eq(participant), anyLong());
        verify(env).isBuilding(participant);
    }

    @Test
    public void lastDiscoveryChecksEveryNewParticipantBeforeProceeding()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject firstPassParticipant = mockOpenProject("FirstPassExtension"); //$NON-NLS-1$
        IProject secondPassParticipant = mockOpenProject("SecondPassExtension"); //$NON-NLS-1$
        IProject finalIdleParticipant = mockOpenProject("FinalIdleExtension"); //$NON-NLS-1$
        IProject finalBuildingParticipant = mockOpenProject("FinalBuildingExtension"); //$NON-NLS-1$

        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.getOpenDtProjects()).thenReturn(Collections.emptyList(),
            Collections.singletonList(firstPassParticipant),
            Collections.singletonList(firstPassParticipant),
            Arrays.asList(firstPassParticipant, secondPassParticipant),
            Arrays.asList(firstPassParticipant, secondPassParticipant),
            Arrays.asList(firstPassParticipant, secondPassParticipant, finalIdleParticipant,
                finalBuildingParticipant));
        for (IProject participant : Arrays.asList(firstPassParticipant, secondPassParticipant,
            finalIdleParticipant, finalBuildingParticipant))
        {
            when(env.isExtensionProject(participant)).thenReturn(true);
            when(env.resolveBaseProject(participant)).thenReturn(base);
        }
        when(env.isBuilding(firstPassParticipant)).thenReturn(false);
        when(env.isBuilding(secondPassParticipant)).thenReturn(false);
        when(env.isBuilding(finalIdleParticipant)).thenReturn(false);
        when(env.isBuilding(finalBuildingParticipant)).thenReturn(true);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base,
            SETTLE_TIMEOUT_MS, env);

        assertTrue("the last discovery must refuse a later participant that is building", //$NON-NLS-1$
            result != null);
        assertTrue("the refusal must name the second newly discovered participant: " + result, //$NON-NLS-1$
            result.contains("FinalBuildingExtension")); //$NON-NLS-1$
        verify(env).isBuilding(finalIdleParticipant);
        verify(env).isBuilding(finalBuildingParticipant);
    }

    @Test
    public void participantThatReappearsDuringModelRegistrationIsDrainedAndChecked()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject restartedExtension = mockOpenProject("RestartedExtension"); //$NON-NLS-1$

        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.getOpenDtProjects()).thenReturn(Collections.emptyList(),
            Collections.singletonList(restartedExtension));
        when(env.isExtensionProject(restartedExtension)).thenReturn(true);
        when(env.resolveBaseProject(restartedExtension)).thenReturn(base);
        when(env.isBuilding(restartedExtension)).thenReturn(false);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base,
            SETTLE_TIMEOUT_MS, env);

        assertNull(result);
        verify(env).waitForDerivedData(eq(restartedExtension), anyLong());
        verify(env, times(3)).isBuilding(restartedExtension);
    }

    @Test
    public void baseThatStartsBuildingDuringModelPollingIsNotReleasedByAStaleProbe()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        String building = "Project 'Base' started building again. Please wait and retry."; //$NON-NLS-1$
        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.buildingErrorOrNull(base)).thenReturn(null, building);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base,
            SETTLE_TIMEOUT_MS, env);

        assertTrue("the post-model base probe must prevent a stale-ready release", result != null); //$NON-NLS-1$
        assertTrue("the refusal must name the base project: " + result, result.contains("Base")); //$NON-NLS-1$ //$NON-NLS-2$
        verify(env).waitForDerivedData(eq(base), anyLong());
        verify(env).waitBeforeModelRetry(anyLong());
    }

    @Test
    public void settledParticipantThatRestartsStaysBlockedThroughTheLastPass()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject participant = mockOpenProject("RestartedExtension"); //$NON-NLS-1$
        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.getOpenDtProjects()).thenReturn(Collections.singletonList(participant));
        when(env.isExtensionProject(participant)).thenReturn(true);
        when(env.resolveBaseProject(participant)).thenReturn(base);
        when(env.isBuilding(participant)).thenReturn(false, true, true);
        when(env.waitBeforeModelRetry(anyLong())).thenAnswer(invocation ->
        {
            Thread.sleep(invocation.<Long>getArgument(0));
            return true;
        });

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base,
            200L, env);

        assertTrue("a participant still rebuilding after every pass must be refused", result != null); //$NON-NLS-1$
        assertTrue("the refusal must name the restarted participant: " + result, //$NON-NLS-1$
            result.contains("RestartedExtension")); //$NON-NLS-1$
        verify(env, atLeast(2)).waitForDerivedData(eq(participant), anyLong());
    }

    @Test
    public void settledParticipantThatRestartsIsDrainedAgainAndMayRecover()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject participant = mockOpenProject("RecoveringExtension"); //$NON-NLS-1$
        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.getOpenDtProjects()).thenReturn(Collections.singletonList(participant));
        when(env.isExtensionProject(participant)).thenReturn(true);
        when(env.resolveBaseProject(participant)).thenReturn(base);
        when(env.isBuilding(participant)).thenReturn(false, true, false);
        when(env.waitBeforeModelRetry(anyLong())).thenReturn(true);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base,
            SETTLE_TIMEOUT_MS, env);

        assertNull(result);
        verify(env, times(2)).waitForDerivedData(eq(participant), anyLong());
        verify(env, times(4)).isBuilding(participant);
    }

    @Test
    public void blinkingBaseMaySettleAfterMoreThanThreePassesBeforeTheDeadline()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        String building = "Project 'Base' is still building. Please wait and retry."; //$NON-NLS-1$
        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.buildingErrorOrNull(base)).thenReturn(building, building, building, building, null);
        when(env.waitBeforeModelRetry(anyLong())).thenReturn(true);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base,
            SETTLE_TIMEOUT_MS, env);

        assertNull("a blinking base must get the whole deadline rather than three passes", result); //$NON-NLS-1$
        verify(env, times(4)).waitBeforeModelRetry(anyLong());
        verify(env, times(5)).waitForDerivedData(eq(base), anyLong());
    }

    @Test
    public void continuouslyBuildingBaseIsRefusedOnlyAfterTheShortDeadline()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        String building = "Project 'Base' is still building. Please wait and retry."; //$NON-NLS-1$
        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.buildingErrorOrNull(base)).thenReturn(building);
        when(env.waitBeforeModelRetry(anyLong())).thenAnswer(invocation ->
        {
            Thread.sleep(invocation.<Long>getArgument(0));
            return true;
        });
        long startedAt = System.currentTimeMillis();

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base, 200L, env);

        long elapsed = System.currentTimeMillis() - startedAt;
        assertTrue("the deadline refusal must name the base project: " + result, //$NON-NLS-1$
            result != null && result.contains("Base")); //$NON-NLS-1$
        assertTrue("a fixed pass count must not reject a still-building base early: " + elapsed, //$NON-NLS-1$
            elapsed >= 150L);
        verify(env, atLeast(3)).waitBeforeModelRetry(anyLong());
    }

    @Test
    public void endlesslyNewParticipantsAreBoundedByDiscoveryPasses()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject first = mockOpenProject("FirstNewExtension"); //$NON-NLS-1$
        IProject second = mockOpenProject("SecondNewExtension"); //$NON-NLS-1$
        IProject third = mockOpenProject("ThirdNewExtension"); //$NON-NLS-1$
        IProject[] participants = {first, second, third};
        AtomicInteger discovery = new AtomicInteger();
        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.getOpenDtProjects()).thenAnswer(invocation -> Collections.singletonList(
            participants[Math.min(discovery.getAndIncrement(), participants.length - 1)]));
        when(env.isExtensionProject(any(IProject.class))).thenReturn(true);
        when(env.resolveBaseProject(any(IProject.class))).thenReturn(base);
        when(env.isBuilding(any(IProject.class))).thenReturn(true);
        when(env.waitBeforeModelRetry(anyLong())).thenReturn(true);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base,
            SETTLE_TIMEOUT_MS, env);

        assertTrue("participant churn must be refused by a concrete project: " + result, //$NON-NLS-1$
            result != null && result.contains("ThirdNewExtension")); //$NON-NLS-1$
        verify(env, times(3)).getOpenDtProjects();
        verify(env, times(2)).waitBeforeModelRetry(anyLong());
    }

    @Test
    public void settledFollowUpAfterStaleBuildingProbeStillChecksModels()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        // Pin the review interleaving: the old first probe said BUILDING, while the actionable
        // follow-up already says settled. Returning that null directly used to bypass model waiting.
        when(env.isBuilding(base)).thenReturn(true);
        when(env.buildingErrorOrNull(base)).thenReturn(null);

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base, SETTLE_TIMEOUT_MS, env);

        assertNull(result);
        verify(env, never()).isBuilding(base);
        verify(env, times(2)).buildingErrorOrNull(base);
        verify(env).resolveModelsForRefactoring(base);
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

        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.getOpenDtProjects()).thenReturn(Arrays.asList(participant1, participant2));
        when(env.isExtensionProject(participant1)).thenReturn(true);
        when(env.isExtensionProject(participant2)).thenReturn(true);
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

    @Test
    public void missingRefactoringModelTimesOutWithActionableError()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IProject dependent = mockOpenProject("DependentConfiguration"); //$NON-NLS-1$
        IBmModelManager modelManager = mock(IBmModelManager.class);
        when(modelManager.getModel(dependent)).thenReturn(null);
        BmModelResolver.Resolution unavailable = BmModelResolver.resolve(dependent, modelManager);
        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.resolveModelsForRefactoring(base)).thenReturn(unavailable);
        when(env.waitBeforeModelRetry(anyLong())).thenAnswer(invocation ->
        {
            Thread.sleep(invocation.<Long>getArgument(0));
            return true;
        });

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base, 5L, env,
            "delete_metadata", "Nothing was deleted."); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("BM model is not available for project 'DependentConfiguration'. Nothing was " //$NON-NLS-1$
            + "deleted. This is a transient window while EDT reopens the project's storage; " //$NON-NLS-1$
            + "list_projects does not expose BM-model registration and will still report the " //$NON-NLS-1$
            + "project as ready. Wait a few seconds, then retry delete_metadata.", result); //$NON-NLS-1$
    }

    @Test
    public void transientMissingRefactoringModelIsWaitedOut()
    {
        IProject base = mockOpenProject("Base"); //$NON-NLS-1$
        IBmModelManager modelManager = mock(IBmModelManager.class);
        when(modelManager.getModel(base)).thenReturn(null);
        BmModelResolver.Resolution unavailable = BmModelResolver.resolve(base, modelManager);
        when(modelManager.getModel(base)).thenReturn(mock(IBmModel.class));
        BmModelResolver.Resolution available = BmModelResolver.resolve(base, modelManager);
        AtomicBoolean registered = new AtomicBoolean(false);
        CascadeEnvironment env = mockEnvironmentWithAvailableModels();
        when(env.resolveModelsForRefactoring(base))
            .thenAnswer(invocation -> registered.get() ? available : unavailable);
        when(env.waitBeforeModelRetry(anyLong())).thenAnswer(invocation ->
        {
            registered.set(true);
            return true;
        });

        String result = ProjectStateChecker.settleBeforeCascadeOrError(base, 100L, env,
            "rename_metadata_object", "Nothing was renamed."); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(result);
        verify(env).waitBeforeModelRetry(anyLong());
        verify(env, times(2)).resolveModelsForRefactoring(base);
    }

    /**
     * Issue #495: a large configuration spends HOURS in the validation checks, and while they ran
     * the project reported "building", which switched metadata editing off for that whole time. The
     * discriminator is the pipeline STAGE, not a list of check segment ids - EDT registers the five
     * long-running check segments plus the marker cleaner in AFTER_BUILD, so reaching that stage
     * proves SYNC, BEFORE_BUILD and NORMAL are done.
     */
    @Test
    public void postBuildValidationDoesNotCountAsBuilding()
    {
        assertTrue(ProjectStateChecker.onlyPostBuildStage("AFTER_BUILD", false)); //$NON-NLS-1$
        assertTrue(ProjectStateChecker.onlyPostBuildStage("FINISHING", false)); //$NON-NLS-1$
    }

    /**
     * The stages up to and including the build still gate. This is the case EDT's own check-segment
     * grouping gets wrong for this question: CDI_CHECKS_SEGMENT IS a check, but it is registered in
     * BEFORE_BUILD and the model is not usable until it has run.
     */
    @Test
    public void stagesUpToTheBuildStillCountAsBuilding()
    {
        assertFalse(ProjectStateChecker.onlyPostBuildStage("BEFORE_BUILD", false)); //$NON-NLS-1$
        assertFalse(ProjectStateChecker.onlyPostBuildStage("NORMAL", false)); //$NON-NLS-1$
        assertFalse(ProjectStateChecker.onlyPostBuildStage("SYNC", false)); //$NON-NLS-1$
        assertFalse(ProjectStateChecker.onlyPostBuildStage("AFTER_SYNC", false)); //$NON-NLS-1$
        assertFalse(ProjectStateChecker.onlyPostBuildStage("STARTING", false)); //$NON-NLS-1$
    }

    /**
     * An active model synchronisation, no stage at all, or a stage name this build does not know
     * prove nothing - and the reflective read that feeds this returns null on any failure, so an
     * unexported-API change degrades to the previous "stay building" behaviour rather than to a
     * silent relaxation.
     */
    @Test
    public void anythingUnprovenKeepsTheProjectBuilding()
    {
        assertFalse(ProjectStateChecker.onlyPostBuildStage("AFTER_BUILD", true)); //$NON-NLS-1$
        assertFalse(ProjectStateChecker.onlyPostBuildStage(null, false));
        assertFalse(ProjectStateChecker.onlyPostBuildStage("", false)); //$NON-NLS-1$
        assertFalse(ProjectStateChecker.onlyPostBuildStage("SOME_NEW_STAGE", false)); //$NON-NLS-1$
    }
}
