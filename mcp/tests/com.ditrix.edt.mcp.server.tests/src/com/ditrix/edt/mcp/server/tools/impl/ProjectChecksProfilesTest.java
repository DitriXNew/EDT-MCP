/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.junit.Before;
import org.junit.Test;
import org.junit.After;

import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.settings.ProfileStorageException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Profile actions exercise the real execute boundary and the existing consent seam. */
public class ProjectChecksProfilesTest
{
    private ProjectChecksTestFiles temporary;

    private ICheckRepository repo;
    private IProject project;
    private IFolder folder;
    private TestTool tool;
    private String active;
    private final Map<String, Map<CheckUid, ICheckSettings>> profiles = new LinkedHashMap<>();

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
        active = "Default";
        profiles.clear();
        CheckUid first = new CheckUid("first", "checks");
        CheckUid second = new CheckUid("second", "checks");
        ICheckSettings one = mock(ICheckSettings.class);
        ICheckSettings two = mock(ICheckSettings.class);
        profiles.put("Default", Map.of(first, one));
        profiles.put("Review", Map.of(first, one, second, two));
        when(project.getName()).thenReturn("TestConfiguration");
        when(project.isOpen()).thenReturn(true);
        when(project.getFolder(".settings")).thenReturn(folder);
        ProjectChecksTestFiles.locate(folder, temporary);
        when(repo.getActiveSettingsProfile(project)).thenAnswer(call -> active);
        when(repo.getAvailableSettingsProfiles(project)).thenAnswer(call -> List.copyOf(profiles.keySet()));
        when(repo.getCustomizedSettingsFromProfile(anyString(), same(project)))
            .thenAnswer(call -> profiles.getOrDefault(call.getArgument(0), Map.of()));
        when(repo.validateProfileName(anyString())).thenReturn(true);
        doAnswer(call -> {
            profiles.put(call.getArgument(1), new LinkedHashMap<>(profiles.getOrDefault(call.getArgument(0), Map.of())));
            return null;
        }).when(repo).duplicateSettingsProfile(anyString(), anyString(), same(project));
        doAnswer(call -> { profiles.remove(call.getArgument(0)); return null; })
            .when(repo).deleteSettingsProfile(anyString(), same(project));
        tool = new TestTool();
    }

    @After
    public void tearDown() throws Exception
    {
        if (temporary != null)
        {
            temporary.close();
        }
    }

    @Test
    public void createProfileUsesDefaultAndReturnsTheResultingProfiles() throws Exception
    {
        JsonObject result = ok(invoke("createProfile", "New", null));
        assertEquals("Default", result.get("from").getAsString());
        assertEquals("New", result.get("profile").getAsString());
        assertEquals(profiles.get("Default"), profiles.get("New"));
        verify(repo).duplicateSettingsProfile("Default", "New", project);
        assertProfiles(result, "Default", "New", "Review");
        assertConsent("Create", "New");
        verify(folder).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
        // Native duplication creates empty defaults when the implicit Default has no file.
        profiles.remove("Default");
        active = "Review";
        JsonObject empty = ok(invoke("createProfile", "Blank", null));
        assertEquals("Default", empty.get("from").getAsString());
        assertTrue(profiles.get("Blank").isEmpty());
        verify(repo).duplicateSettingsProfile("Default", "Blank", project);
        assertProfiles(empty, "Blank", "New", "Review");
    }

    @Test
    public void createProfileAcceptsAnExplicitSourceWithoutSwitching()
    {
        JsonObject result = ok(invoke("createProfile", "New", "Review"));
        assertEquals("Review", result.get("from").getAsString());
        assertEquals(profiles.get("Review"), profiles.get("New"));
        verify(repo).duplicateSettingsProfile("Review", "New", project);
        assertProfiles(result, "Default", "New", "Review");
    }

    @Test
    public void copyProfileUsesTheRequiredSourceAndReturnsTheResultingProfiles() throws Exception
    {
        JsonObject result = ok(invoke("copyProfile", "Copy", "Review"));
        assertEquals("Review", result.get("from").getAsString());
        assertEquals("Copy", result.get("profile").getAsString());
        assertEquals(profiles.get("Review"), profiles.get("Copy"));
        verify(repo).duplicateSettingsProfile("Review", "Copy", project);
        assertProfiles(result, "Copy", "Default", "Review");
        assertConsent("Copy", "Copy");
        verify(folder).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
    }

    @Test
    public void deleteProfileReturnsTheDeletedCustomizedCountAndTheRemainingProfiles() throws Exception
    {
        JsonObject result = ok(invoke("deleteProfile", "Review", null));
        assertEquals("Review", result.get("deletedProfile").getAsString());
        assertEquals(2, result.get("deletedCustomizedChecks").getAsInt());
        assertFalse(profiles.containsKey("Review"));
        verify(repo).deleteSettingsProfile("Review", project);
        assertProfiles(result, "Default");
        assertConsent("Delete", "Review");
        assertTrue(tool.previews.get(0).getSubtitle().contains("2 customized checks"));
        assertTrue(tool.previews.get(0).getSubtitle().contains("destructive"));
        verify(folder).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
    }

    @Test
    public void createProfileUsesEdtsValidatorAndIncludesEdtsInvalidNameMessage() throws Exception
    {
        invalidName("createProfile");
    }

    @Test
    public void copyProfileUsesEdtsValidatorAndIncludesEdtsInvalidNameMessage() throws Exception
    {
        invalidName("copyProfile");
    }

    @Test
    public void createProfileRefusesADuplicateWithEdtsMessageBeforeConsent() throws Exception
    {
        duplicateName("createProfile");
    }

    @Test
    public void copyProfileRefusesADuplicateWithEdtsMessageBeforeConsent() throws Exception
    {
        duplicateName("copyProfile");
    }

    @Test
    public void createProfileRefusesACaseOnlyDuplicateBeforeConsent() throws Exception
    {
        duplicateName("createProfile", "review");
    }

    @Test
    public void copyProfileRefusesACaseOnlyDuplicateBeforeConsent() throws Exception
    {
        duplicateName("copyProfile", "review");
    }

    @Test
    public void createProfileRequiresTheNewNameBeforeConsent() throws Exception
    {
        missingName("createProfile");
    }

    @Test
    public void copyProfileRequiresTheNewNameBeforeConsent() throws Exception
    {
        missingName("copyProfile");
    }

    @Test
    public void deleteProfileRequiresTheNameBeforeConsent() throws Exception
    {
        missingName("deleteProfile");
    }

    @Test
    public void copyProfileRequiresFromRatherThanSilentlyUsingDefault() throws Exception
    {
        for (String source : new String[] {null, ""})
        {
            assertTrue(error(invoke("copyProfile", "New", source)).contains("from is required"));
        }
        assertNoWritesOrConsent();
    }

    @Test
    public void createProfileRefusesAnAbsentFromBeforeConsent() throws Exception
    {
        absentFrom("createProfile");
    }

    @Test
    public void copyProfileRefusesAnAbsentFromBeforeConsent() throws Exception
    {
        absentFrom("copyProfile");
    }

    @Test
    public void deleteProfileRefusesAnAbsentOrInvalidNameBeforeConsent() throws Exception
    {
        for (String name : List.of("Missing", "invalid profile"))
        {
            String message = error(invoke("deleteProfile", name, null));
            assertTrue(message, message.contains("Unknown profile '" + name + "'"));
        }
        assertNoWritesOrConsent();
    }

    @Test
    public void deleteProfileRefusesTheActiveDefaultBeforeConsent() throws Exception
    {
        // EDT can expose both a saved case alias and the synthetic active Default name.
        profiles.put("default", profiles.get("Default"));
        for (String name : List.of("Default", "default"))
        {
            String message = error(invoke("deleteProfile", name, null));
            assertTrue(message, message.contains("Cannot delete active profile '" + name + "'"));
            assertTrue(message, message.contains("project_checks switch"));
        }
        assertNoWritesOrConsent();
    }

    @Test
    public void deleteProfileRefusesAnActiveUserProfileBeforeConsent() throws Exception
    {
        active = "Review";
        String message = error(invoke("deleteProfile", "Review", null));
        assertTrue(message, message.contains("Cannot delete active profile 'Review'"));
        assertNoWritesOrConsent();
    }

    @Test
    public void createProfileConsultsConsentAndEveryDenialWritesNothing() throws Exception
    {
        denied("createProfile", "New", null);
    }

    @Test
    public void copyProfileConsultsConsentAndEveryDenialWritesNothing() throws Exception
    {
        denied("copyProfile", "New", "Review");
    }

    @Test
    public void deleteProfileConsultsDestructiveConsentAndEveryDenialWritesNothing() throws Exception
    {
        denied("deleteProfile", "Review", null);
    }

    @Test
    public void copyProfileDoesNotOverwriteATargetCreatedDuringConsent() throws Exception
    {
        tool.onConsent = () -> profiles.put("New", Map.of());
        String message = error(invoke("copyProfile", "New", "Review"));
        assertTrue(message, message.contains("already exists"));
        assertEquals(1, tool.previews.size());
        verifyNoWrites();
    }

    @Test
    public void createProfileDoesNotCopyASourceRemovedDuringConsent() throws Exception
    {
        tool.onConsent = () -> profiles.remove("Review");
        String message = error(invoke("createProfile", "New", "Review"));
        assertTrue(message, message.contains("Unknown from profile 'Review'"));
        assertEquals(1, tool.previews.size());
        verifyNoWrites();
    }

    @Test
    public void deleteProfileDoesNotDeleteAProfileMadeActiveDuringConsent() throws Exception
    {
        tool.onConsent = () -> active = "Review";
        String message = error(invoke("deleteProfile", "Review", null));
        assertTrue(message, message.contains("active profile changed"));
        assertTrue(message, message.contains("re-run the preview"));
        assertEquals(1, tool.previews.size());
        verifyNoWrites();
    }

    @Test
    public void deleteProfileDoesNotDeleteAProfileRemovedDuringConsent() throws Exception
    {
        tool.onConsent = () -> profiles.remove("Review");
        String message = error(invoke("deleteProfile", "Review", null));
        assertTrue(message, message.contains("Unknown profile 'Review'"));
        assertEquals(1, tool.previews.size());
        verifyNoWrites();
    }

    @Test
    public void profileListReadsTheCurrentActiveProfileAfterConsent()
    {
        tool.onConsent = () -> active = "Review";
        JsonObject result = ok(invoke("createProfile", "New", null));
        assertProfiles(result, "Default", "New", "Review");
        assertEquals("Review", result.get("activeProfile").getAsString());
    }

    @Test
    public void deleteProfileSurfacesEdtsStorageRefusalIncludingForDefault()
    {
        active = "Review";
        String nativeMessage = "Error while removing check settings profile file Default.cset";
        doThrow(new ProfileStorageException(nativeMessage, new IllegalStateException("EDT refusal")))
            .when(repo).deleteSettingsProfile("Default", project);
        String message = error(invoke("deleteProfile", "Default", null));
        assertTrue(message, message.contains(nativeMessage));
        assertTrue(profiles.containsKey("Default"));
        assertEquals(1, tool.previews.size());
    }

    @Test
    public void annotationsAdvertisePotentiallyDestructiveProfileDeletion()
    {
        var annotations = new ProjectChecksTool().getAnnotations();
        assertEquals(Boolean.FALSE, annotations.getReadOnlyHint());
        assertEquals(Boolean.TRUE, annotations.getDestructiveHint());
        assertEquals(Boolean.FALSE, annotations.getOpenWorldHint());
    }

    @Test
    public void schemaDeclaresAllProfileActionsAndFromAndDescribesTheirConsent()
    {
        ProjectChecksTool production = new ProjectChecksTool();
        JsonObject schema = JsonParser.parseString(production.getInputSchema()).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties");
        assertTrue("from must be declared in the input schema", properties.has("from"));
        assertEquals("string", properties.getAsJsonObject("from").get("type").getAsString());
        JsonArray actions = properties.getAsJsonObject("action").getAsJsonArray("enum");
        for (String action : List.of("createProfile", "copyProfile", "deleteProfile"))
        {
            assertTrue(actions.toString(), actions.toString().contains("\"" + action + "\""));
        }
        assertTrue(production.getDescription().contains("profile deletion is destructive"));
    }

    private void invalidName(String action) throws Exception
    {
        // This otherwise ordinary name is rejected by EDT, proving we use its validator.
        when(repo.validateProfileName("EDTRejected")).thenReturn(false);
        String message = error(invoke(action, "EDTRejected", "Review"));
        assertTrue(message, message.contains("EDT: The profile name EDTRejected is invalid. "
            + "The profile name should not contain spaces, $ or %"));
        verify(repo).validateProfileName("EDTRejected");
        assertNoWritesOrConsent();
    }

    private void duplicateName(String action) throws Exception
    {
        duplicateName(action, "Review");
    }

    private void duplicateName(String action, String name) throws Exception
    {
        String message = error(invoke(action, name, "Default"));
        assertTrue(message, message.contains("EDT: The profile with the name " + name + " already exists. "
            + "Please enter another name"));
        assertEquals(2, profiles.get("Review").size());
        assertNoWritesOrConsent();
    }

    private void missingName(String action) throws Exception
    {
        for (String name : new String[] {null, ""})
        {
            assertTrue(error(invoke(action, name, "Review")).contains("profile is required"));
        }
        assertNoWritesOrConsent();
    }

    private void absentFrom(String action) throws Exception
    {
        String message = error(invoke(action, "New", "Missing"));
        assertTrue(message, message.contains("Unknown from profile 'Missing'"));
        assertTrue(message, message.contains("project_checks profiles"));
        assertNoWritesOrConsent();
    }

    private void denied(String action, String name, String source) throws Exception
    {
        Map<String, Map<CheckUid, ICheckSettings>> before = new LinkedHashMap<>(profiles);
        for (ConsentDecision decision : ConsentDecision.values())
        {
            if (decision == ConsentDecision.ALLOW)
            {
                continue;
            }
            tool.previews.clear();
            tool.decision = decision;
            String message = error(invoke(action, name, source));
            assertEquals(DestructiveConsentGate.consentDeniedMessage(decision, ProjectChecksTool.NAME), message);
            assertEquals(1, tool.previews.size());
            assertEquals(1, tool.previews.get(0).getTotalCount());
            assertEquals(before, profiles);
            assertEquals("Default", active);
        }
        verifyNoWrites();
    }

    private void assertConsent(String verb, String name)
    {
        assertEquals(1, tool.previews.size());
        ConsentPreview preview = tool.previews.get(0);
        assertEquals(verb + " check profile of TestConfiguration", preview.getTitle());
        assertEquals(1, preview.getTotalCount());
        assertEquals(List.of(name), preview.getTopNames());
    }

    private void assertProfiles(JsonObject result, String... names)
    {
        assertEquals(active, result.get("activeProfile").getAsString());
        assertTrue(result.get("changed").getAsBoolean());
        assertTrue(result.get("persisted").getAsBoolean());
        JsonArray rows = result.getAsJsonArray("profiles");
        assertEquals(names.length, rows.size());
        for (int i = 0; i < names.length; i++)
        {
            JsonObject row = rows.get(i).getAsJsonObject();
            assertEquals(names[i], row.get("name").getAsString());
            assertEquals(active.equals(names[i]), row.get("active").getAsBoolean());
            assertEquals(profiles.get(names[i]).size(), row.get("customizedChecks").getAsInt());
        }
    }

    private void assertNoWritesOrConsent() throws Exception
    {
        assertTrue(tool.previews.isEmpty());
        verifyNoWrites();
    }

    private void verifyNoWrites() throws Exception
    {
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
        verify(repo, never()).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        verify(repo, never()).applyChanges(anyCollection(), same(project));
        verify(folder, never()).refreshLocal(anyInt(), any(IProgressMonitor.class));
    }

    private String invoke(String action, String name, String source)
    {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("projectName", "TestConfiguration");
        args.put("action", action);
        if (name != null)
        {
            args.put("profile", name);
        }
        if (source != null)
        {
            args.put("from", source);
        }
        return tool.execute(args);
    }

    private static JsonObject ok(String text)
    {
        JsonObject result = JsonParser.parseString(text).getAsJsonObject();
        assertTrue(text, result.get("success").getAsBoolean());
        return result;
    }

    private static String error(String text)
    {
        JsonObject result = JsonParser.parseString(text).getAsJsonObject();
        assertFalse(text, result.get("success").getAsBoolean());
        return result.get("error").getAsString();
    }

    private class TestTool extends ProjectChecksTool
    {
        final List<ConsentPreview> previews = new ArrayList<>();
        ConsentDecision decision = ConsentDecision.ALLOW;
        Runnable onConsent = () -> { };

        @Override
        protected String buildingErrorOrNull(String name)
        {
            return null;
        }

        @Override
        protected IProject resolveProject(String name)
        {
            return project;
        }

        @Override
        protected ICheckRepository checkRepository()
        {
            return repo;
        }

        @Override
        protected ConsentDecision requireConsent(ConsentPreview preview)
        {
            previews.add(preview);
            onConsent.run();
            return decision;
        }
    }
}
