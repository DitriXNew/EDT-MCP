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

import org.eclipse.core.resources.IFile;
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
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Names are file stems even when EDT accepts them or lists an unsafe on-disk name. */
public class ProjectChecksProfileSafetyTest
{
    private ProjectChecksTestFiles temporary;

    private static final List<String> UNSAFE_NAMES = List.of("../x", "a/b", "a\\b", "a\\\\b", "x:y", "a/../b");

    private ICheckRepository repo;
    private IProject project;
    private IFolder settings;
    private TestTool tool;
    private String active;
    private final List<String> available = new ArrayList<>();

    @Before
    public void setUp() throws Exception
    {
        temporary = new ProjectChecksTestFiles();
        repo = mock(ICheckRepository.class);
        project = mock(IProject.class);
        IWorkspace workspace = mock(IWorkspace.class);
        when(project.getWorkspace()).thenReturn(workspace);
        when(workspace.validateName(anyString(), eq(IResource.FILE))).thenReturn(Status.OK_STATUS);
        settings = mock(IFolder.class);
        when(project.getName()).thenReturn("TestConfiguration");
        when(project.isOpen()).thenReturn(true);
        when(project.getFolder(".settings")).thenReturn(settings);
        ProjectChecksTestFiles.locate(settings, temporary);
        // The explicit separator guard is required even when canonicalization returns a child.
        when(repo.validateProfileName(anyString())).thenReturn(true);
        active = "P";
        available.clear();
        available.add("P");
        when(repo.getAvailableSettingsProfiles(project)).thenAnswer(call -> List.copyOf(available));
        when(repo.getActiveSettingsProfile(project)).thenAnswer(call -> active);
        doAnswer(call -> { available.add(call.getArgument(1)); ProjectChecksTestFiles.save(settings, call.getArgument(1)); return null; })
            .when(repo).duplicateSettingsProfile(anyString(), anyString(), same(project));
        doAnswer(call -> { available.remove(call.getArgument(0)); return null; })
            .when(repo).deleteSettingsProfile(anyString(), same(project));
        doAnswer(call -> { active = call.getArgument(0); return null; })
            .when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
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
    public void createProfileRefusesPathsBeforeConsent()
    {
        refusePaths("createProfile", false);
    }

    @Test
    public void copyProfileRefusesTargetPathsBeforeConsent()
    {
        refusePaths("copyProfile", false);
    }

    @Test
    public void copyProfileRefusesSourcePathsBeforeConsent()
    {
        refusePaths("copyProfile", true);
    }

    @Test
    public void createProfileRefusesExplicitSourcePathsBeforeConsent()
    {
        refusePaths("createProfile", true);
    }

    @Test
    public void deleteProfileRefusesPathsBeforeConsent()
    {
        refusePaths("deleteProfile", false);
    }

    @Test
    public void switchRefusesPathsBeforeConsent()
    {
        refusePaths("switch", false);
    }

    @Test
    public void everyProfileActionRequiresTheResolvedFileToBeADirectSettingsChild()
    {
        IFile escaped = mock(IFile.class);
        when(escaped.getParent()).thenReturn(mock(IFolder.class));
        when(settings.getFile("Escaped.cset")).thenReturn(escaped);
        for (String action : List.of("createProfile", "copyProfile", "deleteProfile", "switch"))
        {
            available.clear();
            available.add("P");
            if (action.equals("deleteProfile") || action.equals("switch"))
            {
                available.add("Escaped");
            }
            assertFileStemRefusal(invoke(action, "Escaped", "P"));
        }
        available.add("Escaped");
        assertFileStemRefusal(invoke("copyProfile", "New", "Escaped"));
        assertNoWritesOrConsent(1);
    }

    @Test
    public void switchReturnsToImplicitDefaultWhenOnlyPIsAvailable() throws Exception
    {
        assertEquals(List.of("P"), repo.getAvailableSettingsProfiles(project));
        JsonObject result = ok(invoke("switch", "Default", null));
        assertEquals("P", result.getAsJsonObject("before").get("activeProfile").getAsString());
        assertEquals("Default", result.getAsJsonObject("after").get("activeProfile").getAsString());
        assertEquals("Default", result.get("profile").getAsString());
        assertTrue(result.get("changed").getAsBoolean());
        assertTrue(result.get("persisted").getAsBoolean());
        assertEquals(1, tool.consents);
        verify(repo).setActiveSettingsProfile("Default", List.of(), project);
    }

    @Test
    public void switchStillAcceptsDefaultWhenItDisappearsDuringConsent() throws Exception
    {
        available.add("Default");
        tool.onConsent = () -> available.remove("Default");
        JsonObject result = ok(invoke("switch", "Default", null));
        assertTrue(result.get("persisted").getAsBoolean());
        assertEquals(1, tool.consents);
        verify(repo).setActiveSettingsProfile("Default", List.of(), project);
    }

    @Test
    public void switchStillRefusesOtherProfilesRemovedDuringConsent()
    {
        available.add("Review");
        tool.onConsent = () -> available.remove("Review");
        JsonObject result = json(invoke("switch", "Review", null));
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("Unknown profile 'Review'"));
        assertEquals(1, tool.consents);
        verify(repo, never()).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
    }

    private void refusePaths(String action, boolean source)
    {
        List<String> unexpected = new ArrayList<>();
        for (String name : UNSAFE_NAMES)
        {
            available.clear();
            available.add("P");
            if (source || action.equals("deleteProfile") || action.equals("switch"))
            {
                available.add(name);
            }
            JsonObject result = json(invoke(action, source ? "New" : name, source ? name : "P"));
            if (result.get("success").getAsBoolean()
                || !result.get("error").getAsString().contains("a profile name is a file stem in .settings"))
            {
                unexpected.add(name + ": " + result);
            }
        }
        assertTrue("Every unsafe name must be refused as a file stem: " + unexpected, unexpected.isEmpty());
        assertNoWritesOrConsent("switch".equals(action) ? UNSAFE_NAMES.size() : 0);
    }

    private void assertNoWritesOrConsent(int expectedRefreshes)
    {
        assertEquals(0, tool.consents);
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
        verify(repo, never()).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        verify(repo, never()).applyChanges(anyCollection(), same(project));
        try
        {
            verify(settings, times(expectedRefreshes)).refreshLocal(eq(IResource.DEPTH_ONE), any(IProgressMonitor.class));
        }
        catch (Exception e)
        {
            throw new AssertionError(e);
        }
    }

    private static void assertFileStemRefusal(String text)
    {
        JsonObject result = json(text);
        assertFalse(text, result.get("success").getAsBoolean());
        assertTrue(text, result.get("error").getAsString().contains("a profile name is a file stem in .settings"));
    }

    private String invoke(String action, String profile, String source)
    {
        Map<String, String> params = new LinkedHashMap<>(Map.of("projectName", "TestConfiguration",
            "action", action, "profile", profile));
        if (source != null)
        {
            params.put("from", source);
        }
        return tool.execute(params);
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

    private class TestTool extends ProjectChecksTool
    {
        int consents;
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
            consents++;
            onConsent.run();
            return ConsentDecision.ALLOW;
        }
    }
}
