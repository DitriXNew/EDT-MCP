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
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.junit.Before;
import org.junit.Test;
import org.junit.After;
import org.osgi.framework.FrameworkUtil;

import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.ProjectCheckSettingsSupport;
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Round-five platform-name, implicit-Default and last-inactive-profile regressions. */
public class ProjectChecksRoundFiveTest
{
    private ProjectChecksTestFiles temporary;

    private ICheckRepository repo;
    private IProject project;
    private IWorkspace workspace;
    private TestTool tool;
    private String active;
    private final List<String> available = new ArrayList<>();

    @Before
    public void setUp() throws Exception
    {
        temporary = new ProjectChecksTestFiles();
        repo = mock(ICheckRepository.class);
        project = mock(IProject.class);
        workspace = mock(IWorkspace.class);
        IFolder folder = mock(IFolder.class);
        when(project.getName()).thenReturn("TestConfiguration");
        when(project.isOpen()).thenReturn(true);
        when(project.getWorkspace()).thenReturn(workspace);
        when(workspace.validateName(anyString(), eq(IResource.FILE))).thenReturn(Status.OK_STATUS);
        when(project.getFolder(".settings")).thenReturn(folder);
        ProjectChecksTestFiles.locate(folder, temporary);
        when(repo.validateProfileName(anyString())).thenReturn(true);
        active = "P";
        available.clear();
        available.add("P");
        when(repo.getAvailableSettingsProfiles(project)).thenAnswer(call -> List.copyOf(available));
        when(repo.getActiveSettingsProfile(project)).thenAnswer(call -> active);
        doAnswer(call -> { available.add(call.getArgument(1)); return null; })
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
    public void createRefusesNulBeforeConsent()
    {
        refuseWorkspaceName("createProfile", "NUL");
    }

    @Test
    public void copyRefusesNulBeforeConsent()
    {
        refuseWorkspaceName("copyProfile", "NUL");
    }

    @Test
    public void createRefusesConExtensionBeforeConsent()
    {
        refuseWorkspaceName("createProfile", "CON.x");
    }

    @Test
    public void copyRefusesConExtensionBeforeConsent()
    {
        refuseWorkspaceName("copyProfile", "CON.x");
    }

    @Test
    public void createRefusesQuestionMarkBeforeConsent()
    {
        refuseWorkspaceName("createProfile", "a?b");
    }

    @Test
    public void copyRefusesQuestionMarkBeforeConsent()
    {
        refuseWorkspaceName("copyProfile", "a?b");
    }

    @Test
    public void createRefusesUnlistedDefaultAliasesBeforeConsent()
    {
        refuseDefaultAliases("createProfile");
    }

    @Test
    public void copyRefusesUnlistedDefaultAliasesBeforeConsent()
    {
        refuseDefaultAliases("copyProfile");
    }

    @Test
    public void switchAcceptsLowercaseImplicitDefault()
    {
        acceptSwitchAlias("default");
    }

    @Test
    public void switchAcceptsUppercaseImplicitDefault()
    {
        acceptSwitchAlias("DEFAULT");
    }

    @Test
    public void deleteRefusesLastInactiveProfileBeforeConsent()
    {
        active = "Default";
        assertLastProfileRefusal(error(invoke("deleteProfile", "P")));
        assertNoWritesOrConsent();
    }

    @Test
    public void lastInactiveProfilePreconditionCarriesTheRefusalMarker()
    {
        active = "Default";
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
            () -> ProjectCheckSettingsSupport.validateProfileDelete(repo, project, "P"));
        assertTrue(refusal instanceof Refusals.Marker);
        assertLastProfileRefusal(refusal.getMessage());
        assertEquals(IStatus.INFO, Refusals.statusFor("delete profile", refusal).getSeverity());
    }

    @Test
    public void deleteRechecksTheLastInactiveProfileAfterConsent()
    {
        active = "Default";
        available.add("Other");
        tool.onConsent = () -> available.remove("Other");
        assertLastProfileRefusal(error(invoke("deleteProfile", "P")));
        assertEquals(1, tool.consents);
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
    }

    @Test
    public void edtLastInactiveProfileRefusalIsLoggedAtInfoWithoutAStack()
    {
        active = "Default";
        available.add("Other");
        doThrow(new IllegalStateException("The last profile cannot be inactive"))
            .when(repo).deleteSettingsProfile("P", project);
        ILog log = Platform.getLog(FrameworkUtil.getBundle(ProjectChecksTool.class));
        List<IStatus> recorded = new ArrayList<>();
        ILogListener listener = (status, plugin) -> {
            if (status.getMessage().startsWith("project_checks failed"))
            {
                recorded.add(status);
            }
        };
        log.addLogListener(listener);
        try
        {
            assertLastProfileRefusal(error(invoke("deleteProfile", "P")));
            assertEquals(1, recorded.size());
            assertEquals(IStatus.INFO, recorded.get(0).getSeverity());
            assertNull(recorded.get(0).getException());
        }
        finally
        {
            log.removeLogListener(listener);
        }
    }

    @Test
    public void platformFailureAndRetryHintHaveASentenceSeparator()
    {
        when(repo.getActiveSettingsProfile(project))
            .thenThrow(new IllegalStateException("The last profile cannot be inactive"));
        String text = error(invoke("profiles", "P"));
        assertTrue(text, text.contains("cannot be inactive. Use project_checks"));
    }

    private void refuseWorkspaceName(String action, String name)
    {
        when(workspace.validateName(name + ".cset", IResource.FILE))
            .thenReturn(new Status(IStatus.ERROR, "test", "Invalid resource name"));
        String text = error(invoke(action, name));
        assertTrue(text, text.contains("Invalid profile name '" + name + "'"));
        verify(repo).validateProfileName(name);
        verify(workspace).validateName(name + ".cset", IResource.FILE);
        assertNoWritesOrConsent();
    }

    private void refuseDefaultAliases(String action)
    {
        assertEquals(List.of("P"), available);
        for (String name : List.of("default", "Default", "DEFAULT"))
        {
            String text = error(invoke(action, name));
            assertTrue(text, text.contains("The profile with the name " + name + " already exists"));
        }
        assertNoWritesOrConsent();
    }

    private void acceptSwitchAlias(String name)
    {
        assertEquals(List.of("P"), available);
        String text = invoke("switch", name);
        JsonObject result = JsonParser.parseString(text).getAsJsonObject();
        assertTrue(text, result.get("success").getAsBoolean());
        assertEquals("Default", result.get("profile").getAsString());
        assertEquals(1, tool.consents);
        verify(repo).setActiveSettingsProfile("Default", List.of(), project);
    }

    private static void assertLastProfileRefusal(String text)
    {
        assertTrue(text, text.contains("EDT requires more than one listed profile to delete an inactive profile"));
        assertTrue(text, text.contains("switch to it"));
        assertTrue(text, text.contains("EDT's validation preferences"));
        assertTrue(text, text.contains("restores Default"));
    }

    private void assertNoWritesOrConsent()
    {
        assertEquals(0, tool.consents);
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
        verify(repo, never()).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
    }

    private String invoke(String action, String profile)
    {
        Map<String, String> params = new LinkedHashMap<>(Map.of(
            "projectName", "TestConfiguration", "action", action, "profile", profile));
        if ("copyProfile".equals(action))
        {
            params.put("from", "P");
        }
        return tool.execute(params);
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
