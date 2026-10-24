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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckDescription;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Disk changes affect the repository cache only when a depth-one resource refresh delivers them. */
public class ProjectChecksEveryActionRefreshTest
{
    private static final String REFRESH_ERROR = "EDT could not refresh .settings before reading check profiles: "
        + "Refresh blocked. Refresh the project in EDT, then retry project_checks.";
    private final CheckUid uid = new CheckUid("begin-transaction", "example.checks");
    private final List<String> cachedProfiles = new ArrayList<>(List.of("Default"));
    private final Map<String, Boolean> enabled = new HashMap<>(Map.of("Default", true, "Review", false));
    private final List<String> events = new ArrayList<>();
    private ProjectChecksTestFiles temporary;
    private IProject project;
    private IFolder folder;
    private ICheckRepository repo;
    private Path settings;
    private String active = "Default";
    private TestTool tool;

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
        ICheckDescription description = mock(ICheckDescription.class);
        when(description.getId()).thenReturn(uid);
        when(description.getTitle()).thenReturn("Begin transaction");
        when(description.getType()).thenReturn(IssueType.WARNING);
        when(description.getSeverity()).thenReturn(IssueSeverity.MAJOR);
        when(description.isEnabled()).thenReturn(true);
        when(repo.getChecksWithDescriptions()).thenAnswer(call -> {
            events.add("repository");
            return Map.of(uid, description);
        });
        when(repo.getRootElements(project)).thenReturn(List.of(description));
        when(repo.getCheckUidForCheckId(uid.getCheckId(), project)).thenReturn(Set.of(uid));
        when(repo.getShortUid(uid, project)).thenReturn("SU23");
        when(repo.getActiveSettingsProfile(project)).thenAnswer(call -> {
            events.add("repository");
            return active;
        });
        when(repo.getAvailableSettingsProfiles(project)).thenAnswer(call -> {
            events.add("repository");
            return List.copyOf(cachedProfiles);
        });
        when(repo.getCustomizedSettingsFromProfile(anyString(), same(project))).thenReturn(Map.of());
        when(repo.validateProfileName(anyString())).thenAnswer(call -> {
            events.add("repository");
            return true;
        });
        when(repo.getSettings(uid, project)).thenAnswer(call -> {
            events.add("repository");
            ICheckSettings copy = mock(ICheckSettings.class);
            AtomicBoolean value = new AtomicBoolean(enabled.getOrDefault(active, true));
            when(copy.getId()).thenReturn(uid);
            when(copy.isEnabled()).thenAnswer(ignored -> value.get());
            when(copy.getSeverity()).thenReturn(IssueSeverity.MAJOR);
            when(copy.isDefault()).thenAnswer(ignored -> value.get());
            doAnswer(update -> { value.set(update.getArgument(0)); return null; }).when(copy).setEnabled(anyBoolean());
            return copy;
        });
        doAnswer(call -> {
            for (ICheckSettings copy : call.<Collection<ICheckSettings>>getArgument(0))
            {
                enabled.put(active, copy.isEnabled());
            }
            writeProfile(active);
            if (!cachedProfiles.contains(active))
            {
                cachedProfiles.add(active);
            }
            return null;
        }).when(repo).applyChanges(anyCollection(), same(project));
        doAnswer(call -> {
            String source = call.getArgument(0);
            String target = call.getArgument(1);
            writeProfile(target);
            enabled.put(target, enabled.getOrDefault(source, true));
            cachedProfiles.add(target);
            return null;
        }).when(repo).duplicateSettingsProfile(anyString(), anyString(), same(project));
        doAnswer(call -> {
            String target = call.getArgument(0);
            Files.delete(settings.resolve(target + ".cset"));
            cachedProfiles.remove(target);
            return null;
        }).when(repo).deleteSettingsProfile(anyString(), same(project));
        doAnswer(call -> { active = call.getArgument(0); return null; })
            .when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        doAnswer(call -> {
            events.add("refresh");
            if (call.<Integer>getArgument(0) == IResource.DEPTH_ONE)
            {
                deliverResourceDelta();
            }
            return null;
        }).when(folder).refreshLocal(anyInt(), any(IProgressMonitor.class));
        tool = new TestTool();
        events.clear();
        clearInvocations(repo);
    }

    @After
    public void tearDown() throws Exception
    {
        temporary.close();
    }

    @Test
    public void listReadsDefaultAndItsChecksAfterTheActiveFileWasExternallyRemoved() throws Exception
    {
        removeActiveReview();

        JsonObject result = ok(invoke("list"));

        assertEquals("Default", result.get("activeProfile").getAsString());
        assertEquals("Default", result.get("profile").getAsString());
        assertTrue(result.getAsJsonArray("checks").get(0).getAsJsonObject().get("enabled").getAsBoolean());
        assertEquals("refresh", events.get(0));
        verify(folder).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
        verify(project, never()).refreshLocal(anyInt(), any(IProgressMonitor.class));
        assertEquals(0, tool.consents);
        noWrites();
    }

    @Test
    public void setDoesNotReportAStaleProfileNoOpAsPersisted() throws Exception
    {
        removeActiveReview();

        JsonObject result = ok(invoke("set"));

        assertEquals("Default", result.get("profile").getAsString());
        assertEquals(1, result.get("changed").getAsInt());
        assertTrue(result.get("persisted").getAsBoolean());
        assertTrue(result.getAsJsonArray("before").get(0).getAsJsonObject().get("enabled").getAsBoolean());
        assertFalse(result.getAsJsonArray("after").get(0).getAsJsonObject().get("enabled").getAsBoolean());
        assertEquals(1, tool.consents);
        verify(repo).applyChanges(anyCollection(), same(project));
        assertFalse(Files.exists(settings.resolve("Review.cset")));
        assertEquals("refresh", events.get(0));
    }

    @Test
    public void setRecognizesANoOpInTheRefreshedDefaultProfile() throws Exception
    {
        removeActiveReview();
        Map<String, String> params = params("set");
        params.put("changes", "[{\"checkId\":\"begin-transaction\",\"enabled\":true}]");

        JsonObject result = ok(tool.execute(params));

        assertEquals("Default", result.get("profile").getAsString());
        assertEquals(0, result.get("changed").getAsInt());
        assertTrue(result.get("persisted").getAsBoolean());
        assertEquals(0, tool.consents);
        noWrites();
    }

    @Test
    public void copyProfileAcceptsAnExternallyAddedSourceAndPreservesIt() throws Exception
    {
        Path source = writeProfile("Review");
        byte[] bytes = Files.readAllBytes(source);
        FileTime time = Files.getLastModifiedTime(source);

        JsonObject result = ok(invoke("copyProfile"));

        assertEquals("Review", result.get("from").getAsString());
        assertTrue(result.get("persisted").getAsBoolean());
        verify(repo).duplicateSettingsProfile("Review", "Copy", project);
        assertArrayEquals(bytes, Files.readAllBytes(source));
        assertEquals(time, Files.getLastModifiedTime(source));
        assertEquals("Default", active);
        assertEquals(1, tool.consents);
    }

    @Test
    public void deleteProfileAcceptsAnExternallyAddedProfile() throws Exception
    {
        writeProfile("Review");

        JsonObject result = ok(invoke("deleteProfile"));

        assertEquals("Review", result.get("deletedProfile").getAsString());
        assertTrue(result.get("persisted").getAsBoolean());
        verify(repo).deleteSettingsProfile("Review", project);
        assertFalse(Files.exists(settings.resolve("Review.cset")));
        assertEquals("Default", active);
        assertEquals(1, tool.consents);
    }

    @Test
    public void createProfileAcceptsAnExternallyAddedExplicitSource() throws Exception
    {
        writeProfile("Review");

        JsonObject result = ok(invoke("createProfile"));

        assertEquals("Review", result.get("from").getAsString());
        assertTrue(result.get("persisted").getAsBoolean());
        verify(repo).duplicateSettingsProfile("Review", "Copy", project);
        assertEquals("Default", active);
    }

    @Test public void listRefreshFailureRefusesBeforeAnyRepositoryRead() throws Exception { initialFailure("list"); }
    @Test public void setRefreshFailureRefusesBeforeConsentOrAnyRepositoryRead() throws Exception { initialFailure("set"); }
    @Test public void createRefreshFailureRefusesBeforeConsentOrAnyRepositoryRead() throws Exception { initialFailure("createProfile"); }
    @Test public void copyRefreshFailureRefusesBeforeConsentOrAnyRepositoryRead() throws Exception { initialFailure("copyProfile"); }
    @Test public void deleteRefreshFailureRefusesBeforeConsentOrAnyRepositoryRead() throws Exception { initialFailure("deleteProfile"); }

    @Test public void setRevalidationRefreshesAfterConsent() throws Exception { removedDuringConsent("set"); }
    @Test public void switchRevalidationRefreshesAfterConsent() throws Exception { removedDuringConsent("switch"); }
    @Test public void createRevalidationRefreshesAfterConsent() throws Exception { removedDuringConsent("createProfile"); }
    @Test public void copyRevalidationRefreshesAfterConsent() throws Exception { removedDuringConsent("copyProfile"); }
    @Test public void deleteRevalidationRefreshesAfterConsent() throws Exception { removedDuringConsent("deleteProfile"); }

    @Test public void setRevalidationRefreshFailureRefusesBeforeAnyFurtherRepositoryRead() throws Exception { revalidationFailure("set"); }
    @Test public void switchRevalidationRefreshFailureRefusesBeforeAnyFurtherRepositoryRead() throws Exception { revalidationFailure("switch"); }
    @Test public void createRevalidationRefreshFailureRefusesBeforeAnyFurtherRepositoryRead() throws Exception { revalidationFailure("createProfile"); }
    @Test public void copyRevalidationRefreshFailureRefusesBeforeAnyFurtherRepositoryRead() throws Exception { revalidationFailure("copyProfile"); }
    @Test public void deleteRevalidationRefreshFailureRefusesBeforeAnyFurtherRepositoryRead() throws Exception { revalidationFailure("deleteProfile"); }

    private void initialFailure(String action) throws Exception
    {
        doThrow(refreshFailure()).when(folder).refreshLocal(anyInt(), any(IProgressMonitor.class));

        assertEquals(REFRESH_ERROR, error(invoke(action)));

        assertEquals(0, tool.consents);
        verifyZeroInteractions(repo);
        verify(folder).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
    }

    private void removedDuringConsent(String action) throws Exception
    {
        writeProfile("Review");
        writeProfile("Other");
        active = "Review";
        deliverResourceDelta();
        Map<String, String> params = params(action);
        params.put("profile", action.equals("createProfile") || action.equals("copyProfile") ? "Copy" : "Other");
        params.put("from", "Other");
        params.put("changes", "[{\"checkId\":\"begin-transaction\",\"enabled\":true}]");
        tool.onConsent = () -> {
            try
            {
                Files.delete(settings.resolve("Review.cset"));
            }
            catch (Exception e)
            {
                throw new AssertionError(e);
            }
            events.clear();
        };

        String error = error(tool.execute(params));

        assertTrue(error, error.contains("changed") && error.contains("preview"));
        assertEquals("Default", active);
        assertEquals("refresh", events.get(0));
        assertEquals(1, tool.consents);
        noWrites();
    }

    private void revalidationFailure(String action) throws Exception
    {
        writeProfile("Review");
        deliverResourceDelta();
        tool.onConsent = () -> {
            try
            {
                doThrow(refreshFailure()).when(folder).refreshLocal(anyInt(), any(IProgressMonitor.class));
            }
            catch (Exception e)
            {
                throw new AssertionError(e);
            }
            clearInvocations(repo);
            events.clear();
        };

        String error = error(invoke(action));

        assertTrue(error, error.contains(REFRESH_ERROR));
        assertEquals(1, tool.consents);
        verifyZeroInteractions(repo);
    }

    private void removeActiveReview() throws Exception
    {
        Path path = writeProfile("Review");
        active = "Review";
        deliverResourceDelta();
        Files.delete(path);
    }

    private Path writeProfile(String name) throws Exception
    {
        return Files.writeString(settings.resolve(name + ".cset"), "{\"version\":1,\"settings\":{}}");
    }

    private void deliverResourceDelta() throws Exception
    {
        try (var files = Files.list(settings))
        {
            List<String> onDisk = files.map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".cset")).map(name -> name.substring(0, name.length() - 5))
                .sorted().toList();
            cachedProfiles.clear();
            cachedProfiles.addAll(onDisk);
        }
        if ("Default".equals(active) && !cachedProfiles.contains("Default"))
        {
            cachedProfiles.add(0, "Default");
        }
        if (!cachedProfiles.contains(active))
        {
            active = "Default";
        }
    }

    private static CoreException refreshFailure()
    {
        return new CoreException(new Status(IStatus.ERROR, "com.ditrix.edt.mcp.server", "Refresh blocked"));
    }

    private void noWrites()
    {
        verify(repo, never()).applyChanges(anyCollection(), same(project));
        verify(repo, never()).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
    }

    private Map<String, String> params(String action)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", "TestConfiguration");
        params.put("action", action);
        params.put("profile", action.equals("createProfile") || action.equals("copyProfile") ? "Copy" : "Review");
        params.put("from", "Review");
        params.put("changes", "[{\"checkId\":\"begin-transaction\",\"enabled\":false}]");
        return params;
    }

    private String invoke(String action)
    {
        return tool.execute(params(action));
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
        int consents;
        Runnable onConsent = () -> {};
        @Override protected String buildingErrorOrNull(String name) { return null; }
        @Override protected IProject resolveProject(String name) { return project; }
        @Override protected ICheckRepository checkRepository() { return repo; }
        @Override protected ConsentDecision requireConsent(ConsentPreview preview)
        {
            consents++;
            onConsent.run();
            return ConsentDecision.ALLOW;
        }
    }
}
