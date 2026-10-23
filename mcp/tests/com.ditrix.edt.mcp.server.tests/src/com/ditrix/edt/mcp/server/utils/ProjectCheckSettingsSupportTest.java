/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.Status;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.ditrix.edt.mcp.server.utils.ProjectCheckSettingsSupport.Plan;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckDescription;
import com.e1c.g5.v8.dt.check.settings.ICheckParameterSettings;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.settings.INamedElement;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

/** Repository protocol tests: no running EDT, disk, or UI needed. */
public class ProjectCheckSettingsSupportTest
{
    private ICheckRepository repo;
    private IProject project;
    private final CheckUid uid = new CheckUid("begin-transaction", "example.checks");
    private final CheckUid other = new CheckUid("z-check", "example.other");
    private Map<CheckUid, ICheckDescription> descriptions;

    @Before
    public void setUp()
    {
        repo = mock(ICheckRepository.class);
        project = mock(IProject.class);
        IWorkspace workspace = mock(IWorkspace.class);
        when(project.getWorkspace()).thenReturn(workspace);
        when(workspace.validateName(anyString(), eq(IResource.FILE))).thenReturn(Status.OK_STATUS);
        when(project.getName()).thenReturn("TestConfiguration");
        IFolder folder = mock(IFolder.class);
        when(project.getFolder(".settings")).thenReturn(folder);
        IFile profileFile = mock(IFile.class);
        when(folder.getFile(anyString())).thenReturn(profileFile);
        when(profileFile.getParent()).thenReturn(folder);
        descriptions = new LinkedHashMap<>();
        when(repo.getChecksWithDescriptions()).thenAnswer(call -> descriptions);
        describe(uid, "Begin", true);
        describe(other, "Other", false);
        when(repo.getSettings(eq(uid), same(project))).thenAnswer(call -> settings(uid, true, IssueSeverity.MAJOR));
        when(repo.getSettings(eq(other), same(project))).thenAnswer(call -> settings(other, true, IssueSeverity.MAJOR));
        when(repo.getAvailableSettingsProfiles(project)).thenReturn(List.of("Default", "Review"));
        when(repo.getActiveSettingsProfile(project)).thenReturn("Default");
    }

    private ICheckDescription describe(CheckUid id, String title, boolean critical)
    {
        ICheckDescription description = mock(ICheckDescription.class);
        when(description.getId()).thenReturn(id);
        when(description.getTitle()).thenReturn(title);
        when(description.getType()).thenReturn(critical ? IssueType.CRITICAL_DATA_INTEGRITY : IssueType.WARNING);
        when(description.getSeverity()).thenReturn(IssueSeverity.MAJOR);
        when(description.isEnabled()).thenReturn(true);
        when(description.isCriticalDataIntegrityCheck()).thenReturn(critical);
        descriptions.put(id, description);
        when(repo.getCheckUidForCheckId(id.getCheckId(), project)).thenReturn(Set.of(id));
        when(repo.getShortUid(id, project)).thenReturn(id.equals(uid) ? "SU23" : "SU24");
        return description;
    }

    private static ICheckSettings settings(CheckUid uid, boolean enabled, IssueSeverity severity)
    {
        ICheckSettings settings = mock(ICheckSettings.class);
        when(settings.getId()).thenReturn(uid);
        when(settings.isEnabled()).thenReturn(enabled);
        when(settings.getSeverity()).thenReturn(severity);
        when(settings.isDefault()).thenReturn(true);
        return settings;
    }

    @Test
    public void testASymbolicIdResolvesThroughGetCheckUidForCheckIdAndAMissIsRefusedNamingList()
    {
        assertEquals(uid, ProjectCheckSettingsSupport.resolveUid(repo, project, uid.getCheckId()));
        verify(repo).getCheckUidForCheckId(uid.getCheckId(), project);
        refuses(() -> ProjectCheckSettingsSupport.resolveUid(repo, project, "missing-check"),
            "missing-check", "project_checks list");
    }

    @Test
    public void testAnAmbiguousSymbolicIdIsRefusedListingTheCheckIdPipeContributorForms()
    {
        CheckUid duplicate = new CheckUid(uid.getCheckId(), "second.contributor");
        when(repo.getCheckUidForCheckId(uid.getCheckId(), project)).thenReturn(Set.of(uid, duplicate));
        refuses(() -> ProjectCheckSettingsSupport.resolveUid(repo, project, uid.getCheckId()),
            "Ambiguous", "begin-transaction|example.checks", "begin-transaction|second.contributor");
        verify(repo, never()).getUidForShortUid(anyString(), same(project));
    }

    @Test
    public void testThePipeFormBuildsTheExactCheckUidAndMustBeRegistered()
    {
        assertEquals(uid, ProjectCheckSettingsSupport.resolveUid(repo, project, "begin-transaction|example.checks"));
        for (String bad : List.of("begin-transaction|absent", "|example.checks", "begin-transaction|", "a|b|c"))
        {
            refuses(() -> ProjectCheckSettingsSupport.resolveUid(repo, project, bad), bad, "project_checks list");
        }
        verify(repo, never()).getCheckUidForCheckId(anyString(), same(project));
    }

    @Test
    public void testAShortUidResolvesThroughGetUidForShortUid()
    {
        when(repo.getUidForShortUid("SU23", project)).thenReturn(uid);
        assertEquals(uid, ProjectCheckSettingsSupport.resolveUid(repo, project, "SU23"));
        verify(repo).getUidForShortUid("SU23", project);
    }

    @Test
    public void testChangesParsingRefusesEmptyArrayEntriesWithoutEnabledOrSeverityUnknownSeverityAndDuplicates()
    {
        for (String json : List.of("[]", "{}", "null", "broken", "[1]", "[{\"checkId\":\"begin-transaction\"}]",
            "[{\"checkId\":\"begin-transaction\",\"enabled\":\"false\"}]",
            "[{\"checkId\":\"begin-transaction\",\"enabled\":null}]"))
        {
            refuses(() -> ProjectCheckSettingsSupport.parseChanges(repo, project, json));
        }
        refuses(() -> ProjectCheckSettingsSupport.parseChanges(repo, project,
            "[{\"checkId\":\"begin-transaction\",\"severity\":\"SEVERE\"}]"),
            "SEVERE", "BLOCKER", "CRITICAL", "MAJOR", "MINOR", "TRIVIAL");
        for (String badSeverity : List.of("null", "true", "7", "{}", "[]", "\"\""))
        {
            refuses(() -> ProjectCheckSettingsSupport.parseChanges(repo, project,
                "[{\"checkId\":\"begin-transaction\",\"severity\":" + badSeverity + "}]"),
                "BLOCKER", "CRITICAL", "MAJOR", "MINOR", "TRIVIAL");
        }
        refuses(() -> ProjectCheckSettingsSupport.parseChanges(repo, project,
            "[{\"checkId\":\"begin-transaction\",\"enabled\":false},"
                + "{\"checkId\":\"begin-transaction|example.checks\",\"severity\":\"minor\"}]"), "Duplicate");
        verify(repo, never()).getSettings(any(CheckUid.class), same(project));
        verify(repo, never()).applyChanges(anyCollection(), same(project));
    }

    @Test
    public void testSeverityIsAcceptedCaseInsensitivelyAndEchoedUpperCase()
    {
        Plan plan = ProjectCheckSettingsSupport.prepare(repo, project, "Default",
            "[{\"checkId\":\"begin-transaction\",\"severity\":\"mInOr\"}]");
        assertEquals("MINOR", plan.requested().get(0).row().get("severity"));
        assertEquals(1, plan.severityChanges());
        assertEquals(1, plan.changed());
    }

    @Test
    public void testBeforeSnapshotReadsTheRepositoryAndANoOpRequestReportsChangedZero()
    {
        Plan plan = ProjectCheckSettingsSupport.prepare(repo, project, "Default",
            "[{\"checkId\":\"begin-transaction\",\"enabled\":true,\"severity\":\"MAJOR\"}]");
        assertEquals(0, plan.changed());
        assertEquals(plan.before(), plan.requested());
        assertEquals("begin-transaction|example.checks", plan.beforeRows().get(0).get("uid"));
        assertEquals(true, plan.beforeRows().get(0).get("enabled"));
        verify(repo).getSettings(uid, project);
        verify(repo, never()).applyChanges(anyCollection(), same(project));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void testApplyCallsGetSettingsPerTargetMutatesTheCopyAndCallsApplyChangesExactlyOnceWithAllOfThem()
    {
        ICheckSettings old = settings(uid, true, IssueSeverity.MAJOR);
        ICheckSettings copy = settings(uid, true, IssueSeverity.MAJOR);
        ICheckSettings after = settings(uid, false, IssueSeverity.MINOR);
        doReturn(old, copy, after).when(repo).getSettings(uid, project);
        doReturn(settings(other, true, IssueSeverity.MAJOR), settings(other, true, IssueSeverity.MAJOR),
            settings(other, false, IssueSeverity.MAJOR)).when(repo).getSettings(other, project);
        Plan plan = ProjectCheckSettingsSupport.prepare(repo, project, "Default",
            "[{\"checkId\":\"begin-transaction\",\"enabled\":false,\"severity\":\"MINOR\"},"
                + "{\"checkId\":\"z-check\",\"enabled\":false}]");
        ProjectCheckSettingsSupport.applyAll(repo, project, plan);
        ArgumentCaptor<Collection> batch = ArgumentCaptor.forClass(Collection.class);
        verify(repo, times(1)).applyChanges(batch.capture(), same(project));
        assertEquals(2, batch.getValue().size());
        assertTrue(batch.getValue().contains(copy));
        verify(copy).setEnabled(false);
        verify(copy).setSeverity(IssueSeverity.MINOR);
        verify(old, never()).setEnabled(anyBoolean());
        verify(repo, times(3)).getSettings(uid, project);
        verify(repo, times(3)).getSettings(other, project);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAfterIsReReadFromTheRepositoryNotFromTheMutatedObjects()
    {
        Plan plan = disablePlan();
        ICheckSettings copy = settings(uid, true, IssueSeverity.MAJOR);
        java.util.concurrent.atomic.AtomicBoolean copyEnabled = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(copy.isEnabled()).thenAnswer(call -> copyEnabled.get());
        doAnswer(call -> { copyEnabled.set(call.getArgument(0)); return null; }).when(copy).setEnabled(anyBoolean());
        doReturn(copy, settings(uid, true, IssueSeverity.MAJOR)).when(repo).getSettings(uid, project);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ProjectCheckSettingsSupport.applyAll(repo, project, plan));
        assertTrue(error.getMessage(), error.getMessage().contains("read-back check values differ"));
        assertTrue(error.getMessage(), error.getMessage().contains("Restore values from the preview"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testDisablingACriticalDataIntegrityCheckIsAllowedAndWarnsAboutLoadingWithoutIt()
    {
        Plan plan = disablePlan();
        doReturn(settings(uid, true, IssueSeverity.MAJOR), settings(uid, false, IssueSeverity.MAJOR))
            .when(repo).getSettings(uid, project);
        Map<String, Object> result = ProjectCheckSettingsSupport.applyAll(repo, project, plan);
        assertEquals(true, result.get("persisted"));
        assertEquals(true, ((List<Map<String, Object>>)result.get("after")).get(0).get("criticalDataIntegrity"));
        assertTrue(result.toString(), result.get("criticalDataIntegrityWarning").toString()
            .contains("EDT will now load the configuration without these critical data integrity checks"));
        assertTrue(result.get("criticalDataIntegrityWarning").toString().contains("begin-transaction|example.checks"));
    }

    @Test
    public void testTheTreeWalkFlattensCategoriesIntoATitlePathAndSkipsNothing()
    {
        tree();
        List<Map<String, Object>> rows = rows(ProjectCheckSettingsSupport.list(repo, project, "Default", null,
            false, 200, 0));
        assertEquals(2, rows.size());
        assertEquals("z-check", rows.get(0).get("checkId"));
        assertEquals("A", rows.get(0).get("category"));
        assertEquals("B / Nested", rows.get(1).get("category"));
        assertFalse(rows.get(0).containsKey("parameters"));
    }

    @Test
    public void testRowsCarryShortUidTypeDefaultsCustomizedAndHasDocumentation()
    {
        tree();
        ICheckSettings effective = settings(uid, false, IssueSeverity.MINOR);
        when(effective.isDefault()).thenReturn(false);
        doReturn(effective).when(repo).getSettings(uid, project);
        List<Map<String, Object>> rows = rows(ProjectCheckSettingsSupport.list(repo, project, "Default", null,
            false, 200, 0));
        Map<String, Object> row = rows.get(1);
        assertEquals("SU23", row.get("shortUid"));
        assertEquals("CRITICAL_DATA_INTEGRITY", row.get("type"));
        assertEquals(true, row.get("defaultEnabled"));
        assertEquals("MAJOR", row.get("defaultSeverity"));
        assertEquals(false, row.get("enabled"));
        assertEquals("MINOR", row.get("severity"));
        assertEquals(true, row.get("customized"));
        assertEquals(true, row.get("criticalDataIntegrity"));
        assertEquals(true, row.get("hasDocumentation"));
        assertEquals(false, rows.get(0).get("hasDocumentation"));
    }

    @Test
    public void testCheckIdsAndOnlyCustomizedIntersectAndParametersAreReadOnlyValues()
    {
        tree();
        doReturn(Map.of(uid, settings(uid, false, IssueSeverity.MINOR)))
            .when(repo).getCustomizedSettingsFromProfile("Review", project);
        ICheckSettings effective = settings(uid, false, IssueSeverity.MINOR);
        ICheckParameterSettings parameter = mock(ICheckParameterSettings.class);
        when(parameter.getName()).thenReturn("threshold");
        when(parameter.getValue()).thenReturn("10");
        doReturn(Integer.class).when(parameter).getType();
        when(parameter.isDefault()).thenReturn(false);
        when(effective.getParameters()).thenReturn(Map.of("threshold", parameter));
        doReturn(effective).when(repo).getSettings(uid, project);
        List<Map<String, Object>> rows = rows(ProjectCheckSettingsSupport.list(repo, project, "Review",
            List.of("begin-transaction", "z-check"), true, 200, 0));
        assertEquals(1, rows.size());
        assertEquals(List.of(Map.of("name", "threshold", "value", "10", "type", "java.lang.Integer",
            "isDefault", false)), rows.get(0).get("parameters"));
        verify(parameter, never()).setValue(anyString());
        assertTrue(rows(ProjectCheckSettingsSupport.list(repo, project, "Review", List.of(), false, 200, 0))
            .isEmpty());
    }

    @Test
    public void testPagingIsStableClampedAndCountsTheFilteredTotal()
    {
        tree();
        Map<String, Object> page = ProjectCheckSettingsSupport.list(repo, project, "Default", null, false, 0, 1);
        assertEquals(2, page.get("total"));
        assertEquals(1, page.get("returned"));
        assertEquals(1, page.get("offset"));
        assertEquals("begin-transaction", rows(page).get(0).get("checkId"));
        assertTrue(rows(ProjectCheckSettingsSupport.list(repo, project, "Default", null, false,
            Integer.MAX_VALUE, Integer.MAX_VALUE)).isEmpty());
    }

    @Test
    public void testProfilesReadEveryCustomizationCountAndNeverMutate()
    {
        doReturn(Map.of(uid, settings(uid, false, IssueSeverity.MINOR)))
            .when(repo).getCustomizedSettingsFromProfile("Review", project);
        Map<String, Object> result = ProjectCheckSettingsSupport.profiles(repo, project, "Default");
        assertEquals("Default", result.get("activeProfile"));
        assertEquals(List.of(Map.of("name", "Default", "active", true, "customizedChecks", 0),
            Map.of("name", "Review", "active", false, "customizedChecks", 1)), result.get("profiles"));
        verify(repo, never()).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
    }

    @Test
    public void testSwitchRefusesAProfileNotInGetAvailableSettingsProfilesAndIsANoOpForTheActiveOne()
    {
        refuses(() -> ProjectCheckSettingsSupport.switchProfile(repo, project, "Default", "Missing"),
            "Missing", "project_checks profiles");
        refuses(() -> ProjectCheckSettingsSupport.validateSwitch(repo, project, "Default", null), "profile");
        assertEquals(Map.of("changed", false),
            ProjectCheckSettingsSupport.switchProfile(repo, project, "Default", "Default"));
        verify(repo, never()).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
    }

    @Test
    public void testSwitchUsesAnEmptyOverlayAndReadsBackTheActiveProfile()
    {
        when(repo.getActiveSettingsProfile(project)).thenReturn("Default", "Review");
        Map<String, Object> result = ProjectCheckSettingsSupport.switchProfile(repo, project, "Default", "Review");
        verify(repo).setActiveSettingsProfile("Review", List.of(), project);
        verify(repo, times(2)).getActiveSettingsProfile(project);
        assertEquals(Map.of("activeProfile", "Default"), result.get("before"));
        assertEquals(Map.of("activeProfile", "Review"), result.get("after"));
        assertEquals(true, result.get("persisted"));
    }

    private Plan disablePlan()
    {
        return ProjectCheckSettingsSupport.prepare(repo, project, "Default",
            "[{\"checkId\":\"begin-transaction\",\"enabled\":false}]");
    }

    private INamedElement category(String id, String title)
    {
        INamedElement category = mock(INamedElement.class);
        when(category.getId()).thenReturn(new CheckUid(id, "categories"));
        when(category.getTitle()).thenReturn(title);
        return category;
    }

    private void tree()
    {
        INamedElement a = category("a", "A");
        INamedElement b = category("b", "B");
        INamedElement nested = category("nested", "Nested");
        when(repo.getRootElements(project)).thenReturn(List.of(b, a));
        when(repo.getChildren(b, project)).thenReturn(List.of(nested));
        doReturn(List.of(settings(uid, true, IssueSeverity.MAJOR))).when(repo).getChildren(nested, project);
        doReturn(List.of(settings(other, true, IssueSeverity.MAJOR))).when(repo).getChildren(a, project);
        when(repo.getPath(descriptions.get(uid))).thenReturn(List.of(nested, b));
        when(repo.getPath(descriptions.get(other))).thenReturn(List.of(a));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result)
    {
        return (List<Map<String, Object>>)result.get("checks");
    }

    private static void refuses(Runnable action, String... fragments)
    {
        try
        {
            action.run();
            fail("Expected a validation refusal");
        }
        catch (IllegalArgumentException e)
        {
            for (String fragment : fragments)
            {
                assertTrue(e.getMessage(), e.getMessage().contains(fragment));
            }
        }
    }
}
