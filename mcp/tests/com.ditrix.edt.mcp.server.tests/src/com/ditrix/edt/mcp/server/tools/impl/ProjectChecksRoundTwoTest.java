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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.FrameworkUtil;

import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.OutputSizeGuard;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckDescription;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.settings.INamedElement;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Regressions for the round-two gate, exercised through the real execute entry point. */
public class ProjectChecksRoundTwoTest
{
    private ICheckRepository repo;
    private IProject project;
    private TestTool tool;
    private final CheckUid uid = new CheckUid("begin-transaction", "example.checks");
    private final AtomicReference<String> active = new AtomicReference<>("Default");

    @Before
    public void setUp() throws Exception
    {
        repo = mock(ICheckRepository.class);
        project = mock(IProject.class);
        IWorkspace workspace = mock(IWorkspace.class);
        when(project.getWorkspace()).thenReturn(workspace);
        when(workspace.validateName(anyString(), eq(IResource.FILE))).thenReturn(Status.OK_STATUS);
        when(project.getName()).thenReturn("TestConfiguration");
        when(project.isOpen()).thenReturn(true);
        IFolder folder = mock(IFolder.class);
        when(project.getFolder(".settings")).thenReturn(folder);
        IFile profileFile = mock(IFile.class);
        when(folder.getFile(anyString())).thenReturn(profileFile);
        when(profileFile.getParent()).thenReturn(folder);
        when(repo.getActiveSettingsProfile(project)).thenAnswer(call -> active.get());
        when(repo.getAvailableSettingsProfiles(project)).thenReturn(List.of("Default", "Review"));
        doReturn(Map.of(uid, description(uid))).when(repo).getChecksWithDescriptions();
        doReturn(settings(uid)).when(repo).getSettings(uid, project);
        when(repo.getCheckUidForCheckId(uid.getCheckId(), project)).thenReturn(Set.of(uid));
        doAnswer(call -> { active.set(call.getArgument(0)); return null; })
            .when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        tool = new TestTool();
    }

    @Test
    public void closedProjectIsReportedClosedBeforeRepositoryAccess()
    {
        when(project.isOpen()).thenReturn(false);
        String error = error(tool.execute(params("profiles")));
        assertTrue(error, error.contains("Project is closed"));
        assertTrue(error, error.contains("TestConfiguration"));
        assertTrue(error, error.contains("open it in EDT"));
        assertFalse(error, error.contains("not found"));
        verifyZeroInteractions(repo);
    }

    @Test
    public void onlyKnownNonEdtProjectsAreRefusedWithoutEndlessContextRetry() throws Exception
    {
        IProjectDescription description = mock(IProjectDescription.class);
        when(description.getNatureIds()).thenReturn(new String[0]);
        when(project.getDescription()).thenReturn(description);
        when(repo.getSettings(uid, project))
            .thenThrow(new IllegalArgumentException("Unregistered settings for project:TestConfiguration"));
        String error = error(tool.execute(params("list")));
        assertTrue(error, error.contains("not an EDT project"));
        assertTrue(error, error.contains("project_checks needs an EDT configuration/extension project"));
        assertFalse(error, error.contains("wait for EDT"));
        // An unreadable description is unknown, never proof that the project is not EDT.
        when(project.getDescription()).thenThrow(new CoreException(
            new Status(IStatus.ERROR, "test", "Cannot read project description")));
        String unknown = error(tool.execute(params("list")));
        assertTrue(unknown, unknown.contains("wait for EDT to start its project context"));
        assertFalse(unknown, unknown.contains("not an EDT project"));
    }

    @Test
    public void switchReportsTheReadBackProfileEvenWhenItDiffersFromTheTarget()
    {
        JsonObject result = ok(tool.execute(Map.of("projectName", "TestConfiguration", "action", "switch",
            "profile", "Review")));
        assertEquals("Review", result.get("profile").getAsString());
        assertEquals(result.getAsJsonObject("after").get("activeProfile"), result.get("profile"));
        active.set("Default");
        doAnswer(call -> { active.set("ReadBack"); return null; })
            .when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        result = ok(tool.execute(Map.of("projectName", "TestConfiguration", "action", "switch",
            "profile", "Review")));
        assertEquals("ReadBack", result.get("profile").getAsString());
        assertFalse(result.get("persisted").getAsBoolean());
    }

    @Test
    public void consentCountAndNamesExcludeUnchangedEntries()
    {
        CheckUid noop = new CheckUid("unchanged", "example.checks");
        CheckUid anotherNoop = new CheckUid("another-unchanged", "example.checks");
        doReturn(Map.of(uid, description(uid), noop, description(noop), anotherNoop, description(anotherNoop)))
            .when(repo).getChecksWithDescriptions();
        for (CheckUid id : List.of(noop, anotherNoop))
        {
            when(repo.getCheckUidForCheckId(id.getCheckId(), project)).thenReturn(Set.of(id));
            doReturn(settings(id)).when(repo).getSettings(id, project);
        }
        tool.decision = ConsentDecision.REJECT;
        error(tool.execute(Map.of("projectName", "TestConfiguration", "action", "set", "changes",
            "[{\"checkId\":\"unchanged\",\"enabled\":true},"
                + "{\"checkId\":\"begin-transaction\",\"enabled\":false},"
                + "{\"checkId\":\"another-unchanged\",\"severity\":\"MAJOR\"}]")));
        assertEquals(1, tool.previews.size());
        ConsentPreview preview = tool.previews.get(0);
        assertEquals(1, preview.getTotalCount());
        assertTrue(preview.getSubtitle(), preview.getSubtitle().startsWith("1 checks: 1 disabled"));
        assertEquals(List.of("begin-transaction|example.checks"), preview.getTopNames());
        verify(repo, never()).applyChanges(anyCollection(), same(project));
    }

    @Test
    public void unexpectedExceptionsReachTheBundleErrorLogWithTheirCause()
    {
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
            RuntimeException failure = new NullPointerException("Broken check repository");
            when(repo.getActiveSettingsProfile(project)).thenThrow(failure);
            assertTrue(error(tool.execute(params("profiles"))).contains("Broken check repository"));
            assertEquals(1, recorded.size());
            assertEquals(IStatus.ERROR, recorded.get(0).getSeverity());
            assertSame(failure, recorded.get(0).getException());
            assertTrue(recorded.get(0).getMessage().contains("TestConfiguration"));
            recorded.clear();
            doThrow(new IllegalArgumentException("Bad request")).when(repo).getActiveSettingsProfile(project);
            assertTrue(error(tool.execute(params("profiles"))).contains("Bad request"));
            assertTrue("Validation errors must not produce an exception log", recorded.isEmpty());
        }
        finally
        {
            log.removeLogListener(listener);
        }
    }

    @Test
    public void defaultAndMaximumLocalizedPagesStayWellBelowTheTextGuard()
    {
        Map<CheckUid, ICheckDescription> registered = new LinkedHashMap<>();
        List<INamedElement> leaves = new ArrayList<>();
        for (int i = 0; i < 220; i++)
        {
            CheckUid id = new CheckUid("long-documented-validation-check-" + i,
                "com.e1c.g5.v8.dt.validation.example.contributor");
            ICheckDescription description = description(id);
            // Deliberately long NLS titles: Java source remains ASCII-only.
            when(description.getTitle()).thenReturn(String.valueOf((char)0x0416).repeat(140));
            INamedElement category = mock(INamedElement.class);
            when(category.getTitle()).thenReturn(String.valueOf((char)0x042f).repeat(70));
            when(repo.getPath(description)).thenReturn(List.of(category));
            doReturn(settings(id)).when(repo).getSettings(id, project);
            when(repo.getShortUid(id, project)).thenReturn("SU" + i);
            registered.put(id, description);
            leaves.add(description);
        }
        when(repo.getChecksWithDescriptions()).thenReturn(registered);
        when(repo.getRootElements(project)).thenReturn(leaves);
        String defaultText = tool.execute(params("list"));
        JsonObject defaultPage = ok(defaultText);
        assertEquals(50, defaultPage.get("returned").getAsInt());
        String maximumText = tool.execute(Map.of("projectName", "TestConfiguration", "action", "list",
            "limit", "1000"));
        assertEquals(100, ok(maximumText).get("returned").getAsInt());
        assertTrue(defaultText.length() + " chars", defaultText.length() < OutputSizeGuard.MAX_CONTENT_CHARS / 2);
        assertTrue(maximumText.length() + " chars", maximumText.length() < OutputSizeGuard.MAX_CONTENT_CHARS * 4 / 5);
        System.out.println("project_checks localized page chars: default=" + defaultText.length()
            + ", maximum=" + maximumText.length() + ", guard=" + OutputSizeGuard.MAX_CONTENT_CHARS);
    }

    private static ICheckDescription description(CheckUid id)
    {
        ICheckDescription description = mock(ICheckDescription.class);
        when(description.getId()).thenReturn(id);
        when(description.getType()).thenReturn(IssueType.WARNING);
        when(description.getSeverity()).thenReturn(IssueSeverity.MAJOR);
        when(description.isEnabled()).thenReturn(true);
        return description;
    }

    private static ICheckSettings settings(CheckUid id)
    {
        ICheckSettings settings = mock(ICheckSettings.class);
        when(settings.getId()).thenReturn(id);
        when(settings.isEnabled()).thenReturn(true);
        when(settings.getSeverity()).thenReturn(IssueSeverity.MAJOR);
        return settings;
    }

    private static Map<String, String> params(String action)
    {
        return Map.of("projectName", "TestConfiguration", "action", action);
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
            return decision;
        }
    }
}
