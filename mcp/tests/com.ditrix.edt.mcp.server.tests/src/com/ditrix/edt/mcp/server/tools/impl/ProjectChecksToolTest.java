/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckDescription;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Action orchestration and consent tested through the real execute entry point. */
public class ProjectChecksToolTest
{
    private ProjectChecksTestFiles temporary;
    private ICheckRepository repo;
    private IProject project;
    private IFolder folder;
    private TestTool tool;
    private final CheckUid uid = new CheckUid("begin-transaction", "example.checks");
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicReference<IssueSeverity> severity = new AtomicReference<>(IssueSeverity.MAJOR);
    private final AtomicReference<String> profile = new AtomicReference<>("Default");

    @Before
    public void setUp() throws Exception
    {
        temporary = new ProjectChecksTestFiles();
        repo = mock(ICheckRepository.class);
        project = mock(IProject.class);
        IWorkspace workspace = mock(IWorkspace.class);
        when(project.getWorkspace()).thenReturn(workspace);
        when(workspace.validateName(anyString(), eq(IResource.FILE))).thenReturn(Status.OK_STATUS);
        folder = mock(IFolder.class);
        when(project.getName()).thenReturn("TestConfiguration");
        when(project.isOpen()).thenReturn(true);
        when(project.getFolder(".settings")).thenReturn(folder);
        ProjectChecksTestFiles.locate(folder, temporary, "Default", "Review");
        when(repo.getActiveSettingsProfile(project)).thenAnswer(call -> profile.get());
        when(repo.getAvailableSettingsProfiles(project)).thenReturn(List.of("Default", "Review"));
        ICheckDescription description = mock(ICheckDescription.class);
        when(description.isCriticalDataIntegrityCheck()).thenReturn(true);
        when(repo.getChecksWithDescriptions()).thenReturn(Map.of(uid, description));
        when(repo.getCheckUidForCheckId(uid.getCheckId(), project)).thenReturn(Set.of(uid));
        when(repo.getSettings(uid, project)).thenAnswer(call -> {
            ICheckSettings copy = mock(ICheckSettings.class);
            when(copy.getId()).thenReturn(uid);
            AtomicBoolean copyEnabled = new AtomicBoolean(enabled.get());
            AtomicReference<IssueSeverity> copySeverity = new AtomicReference<>(severity.get());
            when(copy.isEnabled()).thenAnswer(ignored -> copyEnabled.get());
            when(copy.getSeverity()).thenAnswer(ignored -> copySeverity.get());
            doAnswer(update -> { copyEnabled.set(update.getArgument(0)); return null; })
                .when(copy).setEnabled(anyBoolean());
            doAnswer(update -> { copySeverity.set(update.getArgument(0)); return null; })
                .when(copy).setSeverity(any(IssueSeverity.class));
            return copy;
        });
        doAnswer(call -> {
            for (ICheckSettings settings : call.<java.util.Collection<ICheckSettings>>getArgument(0))
            {
                enabled.set(settings.isEnabled());
                severity.set(settings.getSeverity());
            }
            return null;
        }).when(repo).applyChanges(anyCollection(), same(project));
        doAnswer(call -> { profile.set(call.getArgument(0)); return null; })
            .when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        tool = new TestTool();
    }

    @After
    public void tearDown() throws Exception
    {
        temporary.close();
    }

    @Test
    public void testSchemaDeclaresEveryParameterExecuteReadsAndTheActionEnum()
    {
        ProjectChecksTool production = new ProjectChecksTool();
        assertEquals("project_checks", production.getName());
        assertEquals(ResponseType.JSON, production.getResponseType());
        assertTrue(production.getDescription().contains("get_tool_guide('project_checks')"));
        JsonObject schema = json(production.getInputSchema());
        assertEquals(Set.of("projectName", "action", "checkIds", "onlyCustomized", "limit", "offset",
            "changes", "profile", "from"), schema.getAsJsonObject("properties").keySet());
        assertEquals("[\"projectName\",\"action\"]", schema.getAsJsonArray("required").toString());
        assertEquals("[\"list\",\"set\",\"profiles\",\"switch\",\"createProfile\",\"copyProfile\",\"deleteProfile\"]",
            schema.getAsJsonObject("properties").getAsJsonObject("action").getAsJsonArray("enum").toString());
        assertEquals("string", schema.getAsJsonObject("properties").getAsJsonObject("changes").get("type").getAsString());
        JsonObject output = json(production.getOutputSchema());
        assertTrue(output.getAsJsonObject("properties").keySet().containsAll(Set.of("success", "project",
            "activeProfile", "checks", "profiles", "before", "after", "changed", "persisted")));
        assertEquals(Boolean.FALSE, production.getAnnotations().getReadOnlyHint());
        assertEquals(Boolean.TRUE, production.getAnnotations().getDestructiveHint());
        assertEquals(Boolean.FALSE, production.getAnnotations().getOpenWorldHint());
        assertTrue(production.getGuide().contains(".settings/<profile>.cset"));
    }

    @Test
    public void testUnknownActionIsRefusedNamingTheFour()
    {
        String error = error(tool.execute(Map.of("projectName", "TestConfiguration", "action", "erase")));
        for (String word : List.of("erase", "list", "set", "profiles", "switch"))
        {
            assertTrue(error, error.contains(word));
        }
        assertTrue(tool.events.isEmpty());
    }

    @Test
    public void testRequiredArgumentsAreRefused()
    {
        assertTrue(error(tool.execute(Map.of("action", "list"))).contains("projectName"));
        assertTrue(error(tool.execute(Map.of("projectName", "TestConfiguration"))).contains("action"));
    }

    @Test
    public void testSetAsksConsentOnceWithTheCountAndADeniedDecisionAppliesNothing() throws Exception
    {
        for (ConsentDecision denied : ConsentDecision.values())
        {
            if (denied == ConsentDecision.ALLOW)
            {
                continue;
            }
            tool.previews.clear();
            tool.decision = denied;
            error(tool.execute(set(false)));
            assertEquals(1, tool.previews.size());
            ConsentPreview captured = tool.previews.get(0);
            assertEquals(1, captured.getTotalCount());
            String preview = captured.getTitle() + captured.getSubtitle() + captured.getTopNames();
            assertTrue(preview, preview.contains("Change check settings of TestConfiguration"));
            assertTrue(preview, preview.contains("1 checks: 1 disabled, 0 enabled, 0 severity changes"));
            assertTrue(preview, preview.contains("profile 'Default'"));
            assertTrue(preview, preview.contains("begin-transaction|example.checks"));
        }
        verify(repo, never()).applyChanges(anyCollection(), same(project));
        verify(folder, times(ConsentDecision.values().length - 1))
            .refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
        assertTrue(enabled.get());
    }

    @Test
    public void testAllowedSetPersistsCriticalDisableAndReturnsBeforeAfterAndTheLoadingWarning() throws Exception
    {
        JsonObject result = ok(tool.execute(set(false)));
        assertEquals(1, tool.previews.size());
        assertEquals(1, result.get("changed").getAsInt());
        assertTrue(result.get("persisted").getAsBoolean());
        assertTrue(result.getAsJsonArray("before").get(0).getAsJsonObject().get("enabled").getAsBoolean());
        assertFalse(result.getAsJsonArray("after").get(0).getAsJsonObject().get("enabled").getAsBoolean());
        assertTrue(result.get("criticalDataIntegrityWarning").getAsString()
            .contains("EDT will now load the configuration without"));
        verify(repo, times(1)).applyChanges(anyCollection(), same(project));
        verify(folder, times(3)).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
        assertEquals(List.of("building", "project", "repository"), tool.events);
    }

    @Test
    public void testSetWithNothingToChangeDoesNotAskConsent() throws Exception
    {
        JsonObject result = ok(tool.execute(set(true)));
        assertEquals(0, result.get("changed").getAsInt());
        assertTrue(tool.previews.isEmpty());
        verify(repo, never()).applyChanges(anyCollection(), same(project));
        verify(folder).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
    }

    @Test
    public void testARefreshFailureRetainsTheRestoreRecipeAndReportsTheWorkspaceProblem() throws Exception
    {
        doNothing().doNothing().doThrow(new CoreException(
            new Status(IStatus.ERROR, "com.ditrix.edt.mcp.server", "Refresh blocked")))
            .when(folder).refreshLocal(anyInt(), any(IProgressMonitor.class));
        JsonObject result = ok(tool.execute(set(false)));
        assertTrue(result.get("persisted").getAsBoolean());
        assertTrue(result.has("before"));
        assertTrue(result.has("after"));
        String warning = result.get("workspaceRefreshWarning").getAsString();
        assertTrue(warning, warning.contains("Refresh blocked"));
        assertTrue(warning, warning.contains("Refresh the project in EDT"));
        assertTrue(warning, warning.contains("get_project_errors"));
    }

    @Test
    public void testInvalidBatchNeverAsksConsentOrPartiallyApplies()
    {
        String error = error(tool.execute(Map.of("projectName", "TestConfiguration", "action", "set", "changes",
            "[{\"checkId\":\"begin-transaction\",\"enabled\":false},"
                + "{\"checkId\":\"absent-check\",\"severity\":\"MAJOR\"}]")));
        assertTrue(error, error.contains("absent-check"));
        assertTrue(tool.previews.isEmpty());
        verify(repo, never()).applyChanges(anyCollection(), same(project));
        verify(repo, times(1)).getSettings(uid, project); // read-only context probe, no batch snapshots
    }

    @Test
    public void testSwitchAsksConsentAndCallsSetActiveSettingsProfileWithAnEmptyOverlay() throws Exception
    {
        JsonObject result = ok(tool.execute(switchTo("Review")));
        assertEquals(1, tool.previews.size());
        assertTrue(tool.previews.get(0).getSubtitle().contains("'Default' \u2192 'Review'"));
        assertEquals("Default", result.getAsJsonObject("before").get("activeProfile").getAsString());
        assertEquals("Review", result.getAsJsonObject("after").get("activeProfile").getAsString());
        assertTrue(result.get("persisted").getAsBoolean());
        verify(repo).setActiveSettingsProfile("Review", List.of(), project);
        verify(folder, times(3)).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
    }

    @Test
    public void testDeniedSwitchAppliesNothing()
    {
        tool.decision = ConsentDecision.REJECT;
        error(tool.execute(switchTo("Review")));
        assertEquals(1, tool.previews.size());
        verify(repo, never()).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
    }

    @Test
    public void testMissingAndAlreadyActiveProfilesNeverAskConsent() throws Exception
    {
        assertFalse(ok(tool.execute(switchTo("Default"))).get("changed").getAsBoolean());
        assertTrue(error(tool.execute(switchTo("Missing"))).contains("project_checks profiles"));
        assertTrue(error(tool.execute(Map.of("projectName", "TestConfiguration", "action", "switch")))
            .contains("profile is required"));
        assertTrue(tool.previews.isEmpty());
        verify(repo, never()).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        verify(folder, times(3)).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
    }

    @Test
    public void testProfilesAndListNeverAskConsent()
    {
        assertEquals("Default", ok(tool.execute(Map.of("projectName", "TestConfiguration", "action", "profiles")))
            .get("activeProfile").getAsString());
        JsonObject result = ok(tool.execute(Map.of("projectName", "TestConfiguration", "action", "list")));
        assertEquals(0, result.get("total").getAsInt());
        assertTrue(tool.previews.isEmpty());
        verify(repo, never()).applyChanges(anyCollection(), same(project));
    }

    @Test
    public void testAMissingRepositoryServiceIsAnActionableError()
    {
        tool.missingRepository = true;
        String error = error(tool.execute(Map.of("projectName", "TestConfiguration", "action", "list")));
        assertTrue(error, error.contains("check repository service not available"));
        assertTrue(error, error.contains("retry project_checks"));
    }

    @Test
    public void testBuildingAndMissingProjectUseSharedPreambleWithoutRepositoryAccess()
    {
        tool.building = "Project is building. Please wait and retry.";
        assertEquals(tool.building,
            error(tool.execute(Map.of("projectName", "TestConfiguration", "action", "profiles"))));
        assertEquals(List.of("building"), tool.events);
        tool.events.clear();
        tool.building = null;
        tool.missingProject = true;
        String error = error(tool.execute(Map.of("projectName", "TestConfiguration", "action", "list")));
        assertTrue(error, error.contains("Project not found: TestConfiguration"));
        assertTrue(error, error.contains("list_projects"));
        assertEquals(List.of("building", "project"), tool.events);
        verifyZeroInteractions(repo);
    }

    @Test
    public void testUnregisteredProjectSettingsExceptionIsTranslatedToTheUnstartedContextWording()
    {
        doThrow(new IllegalArgumentException("Unregistered settings for project:TestConfiguration"))
            .when(repo).getSettings(uid, project);
        String error = error(tool.execute(Map.of("projectName", "TestConfiguration", "action", "list")));
        assertTrue(error, error.contains("Shared context recovery advice"));
        assertTrue(error, error.contains("wait for EDT to start its project context"));
        assertFalse(error, error.contains("Unregistered settings"));
        assertEquals(1, tool.contextReasonCalls);
    }

    @Test
    public void testOtherIllegalArgumentsAndStorageFailuresKeepThePlatformMessage()
    {
        when(repo.getActiveSettingsProfile(project)).thenThrow(new IllegalArgumentException("Different problem"));
        assertTrue(error(tool.execute(Map.of("projectName", "TestConfiguration", "action", "profiles")))
            .contains("Different problem"));
        assertEquals(0, tool.contextReasonCalls);
        doReturn("Default").when(repo).getActiveSettingsProfile(project);
        doThrow(new RuntimeException("Cannot save profile: disk full"))
            .when(repo).applyChanges(anyCollection(), same(project));
        String error = error(tool.execute(set(false)));
        assertTrue(error, error.contains("Cannot save profile: disk full"));
        assertTrue(error, error.contains("project_checks list/profiles"));
    }

    @Test
    public void testSetAndSwitchShareExactlyOneProductionConsentCallSite() throws Exception
    {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path relative = Path.of("mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/impl/ProjectChecksTool.java");
        while (dir != null && !Files.isRegularFile(dir.resolve(relative)))
        {
            dir = dir.getParent();
        }
        assertNotNull("Find the production source", dir);
        String source = Files.readString(dir.resolve(relative), StandardCharsets.UTF_8);
        assertEquals(1, source.split("\\.requireConsent\\(", -1).length - 1);
        assertTrue(source.contains("ProjectStateChecker.withUnstartedContextReason(project, refusal)"));
    }

    private static Map<String, String> set(boolean enabled)
    {
        return Map.of("projectName", "TestConfiguration", "action", "set", "changes",
            "[{\"checkId\":\"begin-transaction\",\"enabled\":" + enabled + "}]");
    }

    private static Map<String, String> switchTo(String profile)
    {
        return Map.of("projectName", "TestConfiguration", "action", "switch", "profile", profile);
    }

    private static JsonObject json(String text)
    {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject ok(String text)
    {
        JsonObject result = json(text);
        assertTrue(text, result.get("success").getAsBoolean());
        return result;
    }

    private static String error(String text)
    {
        JsonObject result = json(text);
        assertFalse(text, result.get("success").getAsBoolean());
        return result.get("error").getAsString();
    }

    private class TestTool extends ProjectChecksTool
    {
        final List<ConsentPreview> previews = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        ConsentDecision decision = ConsentDecision.ALLOW;
        String building;
        boolean missingProject;
        boolean missingRepository;
        int contextReasonCalls;

        @Override
        protected ConsentDecision requireConsent(ConsentPreview preview)
        {
            previews.add(preview);
            return decision;
        }

        @Override
        protected String buildingErrorOrNull(String name)
        {
            events.add("building");
            return building;
        }

        @Override
        protected IProject resolveProject(String name)
        {
            events.add("project");
            return missingProject ? null : project;
        }

        @Override
        protected ICheckRepository checkRepository()
        {
            events.add("repository");
            return missingRepository ? null : repo;
        }

        @Override
        protected String unstartedContextReason(IProject project, String refusal)
        {
            contextReasonCalls++;
            return refusal + " Shared context recovery advice";
        }
    }
}
