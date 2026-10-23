/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** External files become visible only when the resource delta invalidates EDT's cached profile names. */
public class ProjectChecksExternalProfilesTest
{
    private ProjectChecksTestFiles temporary;
    private IProject project;
    private IFolder folder;
    private ICheckRepository repo;
    private Path settings;
    private TestTool tool;
    private String active = "Default";
    private final List<String> cachedProfiles = new ArrayList<>(List.of("Default"));
    private final List<String> events = new ArrayList<>();

    @Before
    public void setUp() throws Exception
    {
        temporary = new ProjectChecksTestFiles();
        project = mock(IProject.class);
        folder = mock(IFolder.class);
        repo = mock(ICheckRepository.class);
        IWorkspace workspace = mock(IWorkspace.class);
        when(project.getName()).thenReturn("TestConfiguration");
        when(project.isOpen()).thenReturn(true);
        when(project.getFolder(".settings")).thenReturn(folder);
        when(project.getWorkspace()).thenReturn(workspace);
        when(workspace.validateName(anyString(), eq(IResource.FILE))).thenReturn(Status.OK_STATUS);
        settings = ProjectChecksTestFiles.locate(folder, temporary);
        when(repo.getChecksWithDescriptions()).thenReturn(Map.of());
        when(repo.getActiveSettingsProfile(project)).thenAnswer(call -> {
            events.add("active");
            return active;
        });
        when(repo.getAvailableSettingsProfiles(project)).thenAnswer(call -> List.copyOf(cachedProfiles));
        when(repo.getCustomizedSettingsFromProfile(anyString(), same(project))).thenReturn(Map.of());
        doAnswer(call -> {
            Collection<?> overlay = call.getArgument(1);
            assertTrue("A pure switch has no overlay", overlay.isEmpty());
            events.add("switch");
            active = call.getArgument(0);
            return null;
        }).when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        doAnswer(call -> {
            events.add("refresh");
            // DEPTH_ZERO does not discover child files. No automatic refresh runs in this fixture.
            if (call.<Integer>getArgument(0) == IResource.DEPTH_ONE)
            {
                deliverResourceDelta();
            }
            return null;
        }).when(folder).refreshLocal(anyInt(), any(IProgressMonitor.class));
        tool = new TestTool();
    }

    @After
    public void tearDown() throws Exception
    {
        temporary.close();
    }

    @Test
    public void profilesDiscoversAnExternalFileWithoutAutoRefreshAndPreservesItsBytesAndTime() throws Exception
    {
        Path file = writeProfile("Review");
        byte[] bytes = Files.readAllBytes(file);
        FileTime modified = Files.getLastModifiedTime(file);
        assertEquals(List.of("Default"), repo.getAvailableSettingsProfiles(project));

        JsonObject result = ok(invoke("profiles", null));

        assertEquals(List.of("Default", "Review"), names(result));
        assertEquals("Default", result.get("activeProfile").getAsString());
        assertFalse(result.getAsJsonArray("profiles").get(1).getAsJsonObject().get("active").getAsBoolean());
        assertEquals(0, result.getAsJsonArray("profiles").get(1).getAsJsonObject().get("customizedChecks").getAsInt());
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertEquals(modified, Files.getLastModifiedTime(file));
        verify(folder).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
        verify(project, never()).refreshLocal(anyInt(), any(IProgressMonitor.class));
        assertEquals(0, tool.consents);
        noWrites();
    }

    @Test
    public void switchDiscoversAnExternalProfileWithoutAPrecedingProfilesCall() throws Exception
    {
        writeProfile("Review");

        JsonObject result = ok(invoke("switch", "Review"));

        assertEquals("Default", result.getAsJsonObject("before").get("activeProfile").getAsString());
        assertEquals("Review", result.getAsJsonObject("after").get("activeProfile").getAsString());
        assertEquals("Review", result.get("profile").getAsString());
        assertTrue(result.get("persisted").getAsBoolean());
        assertEquals(1, tool.consents);
        verify(repo).setActiveSettingsProfile("Review", List.of(), project);
        verify(folder, times(2)).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
        assertEquals("refresh", events.get(0));
        assertTrue(events.toString(), events.indexOf("refresh") < events.indexOf("consent"));
    }

    @Test
    public void profilesStopsListingAnExternallyRemovedCachedProfile() throws Exception
    {
        cacheThenRemoveReview(false);

        assertEquals(List.of("Default"), names(ok(invoke("profiles", null))));
        noWrites();
    }

    @Test
    public void switchRefusesAnExternallyRemovedCachedProfileBeforeConsent() throws Exception
    {
        cacheThenRemoveReview(false);

        String error = error(invoke("switch", "Review"));

        assertTrue(error, error.contains("Unknown profile 'Review'"));
        assertTrue(error, error.contains("project_checks profiles"));
        assertEquals(0, tool.consents);
        noWrites();
    }

    @Test
    public void profilesReadsTheActiveProfileAfterRefreshCanRestoreDefault() throws Exception
    {
        cacheThenRemoveReview(true);

        JsonObject result = ok(invoke("profiles", null));

        assertEquals("Default", result.get("activeProfile").getAsString());
        assertEquals("Default", result.get("profile").getAsString());
        // EDT collects the names before restoring the implicit, unsaved Default.
        assertTrue(names(result).isEmpty());
        assertEquals("refresh", events.get(0));
        noWrites();
    }

    @Test
    public void switchUsesTheRefreshedActiveProfileForItsNoOpDecision() throws Exception
    {
        cacheThenRemoveReview(true);

        JsonObject result = ok(invoke("switch", "Default"));

        assertFalse(result.get("changed").getAsBoolean());
        assertEquals("Default", result.get("profile").getAsString());
        assertEquals(0, tool.consents);
        noWrites();
    }

    @Test
    public void everyProfilesCallDiscoversLaterExternalFiles() throws Exception
    {
        assertEquals(List.of("Default"), names(ok(invoke("profiles", null))));
        writeProfile("Later");

        assertEquals(List.of("Default", "Later"), names(ok(invoke("profiles", null))));
        verify(folder, times(2)).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
        noWrites();
    }

    @Test
    public void profilesRefreshFailureRefusesStaleResultsBeforeAnyRepositoryRead() throws Exception
    {
        refreshFailureRefuses("profiles", null);
    }

    @Test
    public void switchRefreshFailureRefusesBeforeConsentOrAnyRepositoryRead() throws Exception
    {
        refreshFailureRefuses("switch", "Default");
    }

    @Test
    public void switchPostWriteRefreshFailureRetainsTheVerifiedRestoreRecipe() throws Exception
    {
        writeProfile("Review");
        doAnswer(call -> { deliverResourceDelta(); return null; }).doThrow(refreshFailure())
            .when(folder).refreshLocal(anyInt(), any(IProgressMonitor.class));

        JsonObject result = ok(invoke("switch", "Review"));

        assertTrue(result.get("persisted").getAsBoolean());
        assertEquals("Default", result.getAsJsonObject("before").get("activeProfile").getAsString());
        assertEquals("Review", result.getAsJsonObject("after").get("activeProfile").getAsString());
        assertTrue(result.toString(), result.has("workspaceRefreshWarning"));
        assertEquals("Check settings were applied, but EDT could not refresh .settings: Refresh blocked"
            + ". Refresh the project in EDT, then poll get_project_errors.",
            result.get("workspaceRefreshWarning").getAsString());
    }

    private void refreshFailureRefuses(String action, String target) throws Exception
    {
        doThrow(refreshFailure()).when(folder).refreshLocal(anyInt(), any(IProgressMonitor.class));

        assertEquals("EDT could not refresh .settings before reading check profiles: Refresh blocked"
            + ". Refresh the project in EDT, then retry project_checks.", error(invoke(action, target)));
        assertEquals(0, tool.consents);
        verifyZeroInteractions(repo);
    }

    private static CoreException refreshFailure()
    {
        return new CoreException(new Status(IStatus.ERROR, "com.ditrix.edt.mcp.server", "Refresh blocked"));
    }

    private void cacheThenRemoveReview(boolean makeActive) throws Exception
    {
        Path file = writeProfile("Review");
        deliverResourceDelta();
        if (makeActive)
        {
            active = "Review";
        }
        Files.delete(file);
    }

    private Path writeProfile(String name) throws Exception
    {
        return Files.writeString(settings.resolve(name + ".cset"), "{\"version\":1,\"settings\":{}}");
    }

    private void deliverResourceDelta() throws Exception
    {
        List<String> onDisk;
        try (var files = Files.list(settings))
        {
            onDisk = files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".cset"))
                .map(name -> name.substring(0, name.length() - 5)).sorted().toList();
        }
        cachedProfiles.clear();
        cachedProfiles.addAll(onDisk);
        if ("Default".equals(active) && !cachedProfiles.contains("Default"))
        {
            cachedProfiles.add(0, "Default");
        }
        if (!cachedProfiles.contains(active))
        {
            active = "Default";
        }
    }

    private void noWrites()
    {
        verify(repo, never()).applyChanges(anyCollection(), same(project));
        verify(repo, never()).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
    }

    private String invoke(String action, String target)
    {
        return tool.execute(target == null ? Map.of("projectName", "TestConfiguration", "action", action)
            : Map.of("projectName", "TestConfiguration", "action", action, "profile", target));
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

    private static List<String> names(JsonObject result)
    {
        return result.getAsJsonArray("profiles").asList().stream()
            .map(row -> row.getAsJsonObject().get("name").getAsString()).toList();
    }

    private class TestTool extends ProjectChecksTool
    {
        int consents;
        @Override protected String buildingErrorOrNull(String name) { return null; }
        @Override protected IProject resolveProject(String name) { return project; }
        @Override protected ICheckRepository checkRepository() { return repo; }
        @Override protected ConsentDecision requireConsent(ConsentPreview preview)
        {
            consents++;
            events.add("consent");
            return ConsentDecision.ALLOW;
        }
    }
}
