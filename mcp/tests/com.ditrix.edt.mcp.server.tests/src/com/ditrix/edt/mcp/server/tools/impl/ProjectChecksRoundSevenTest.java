/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.Status;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckDescription;
import com.e1c.g5.v8.dt.check.settings.ICheckParameterSettings;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Preview snapshots and honest reporting of changes made across EDT's unguarded write interval. */
public class ProjectChecksRoundSevenTest
{
    private static final CheckUid UID = new CheckUid("sample-check", "sample.contributor");
    private static final CheckUid OTHER = new CheckUid("other-check", "other.contributor");

    private ProjectChecksTestFiles temporary;
    private IProject project;
    private IFolder folder;
    private Path settings;
    private ICheckRepository repo;
    private TestTool tool;
    private final AtomicReference<String> active = new AtomicReference<>("Default");
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicReference<IssueSeverity> severity = new AtomicReference<>(IssueSeverity.MAJOR);
    private final List<String> available = new CopyOnWriteArrayList<>(List.of("Default", "Review", "Other"));
    private final Map<CheckUid, ICheckSettings> customized = new LinkedHashMap<>();
    private Runnable onFreshCopy = () -> { };

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
        when(project.getWorkspace()).thenReturn(workspace);
        when(workspace.validateName(anyString(), eq(IResource.FILE))).thenReturn(Status.OK_STATUS);
        when(project.getFolder(".settings")).thenReturn(folder);
        settings = ProjectChecksTestFiles.locate(folder, temporary);
        when(repo.validateProfileName(anyString())).thenReturn(true);
        when(repo.getActiveSettingsProfile(project)).thenAnswer(call -> active.get());
        when(repo.getAvailableSettingsProfiles(project)).thenAnswer(call -> List.copyOf(available));
        when(repo.getChecksWithDescriptions()).thenReturn(Map.of(UID, mock(ICheckDescription.class)));
        when(repo.getSettings(UID, project)).thenAnswer(call -> {
            onFreshCopy.run();
            return mutableSettings(enabled.get(), severity.get());
        });
        when(repo.getCustomizedSettingsFromProfile("Review", project)).thenAnswer(call -> customized);
        customized.put(UID, mutableSettings(false, IssueSeverity.MINOR));
        doAnswer(call -> {
            persist(call.getArgument(0));
            return null;
        }).when(repo).applyChanges(anyCollection(), same(project));
        doAnswer(call -> { recordCopy(call.getArgument(0), call.getArgument(1)); return null; })
            .when(repo).duplicateSettingsProfile(anyString(), anyString(), same(project));
        doAnswer(call -> { available.remove(call.getArgument(0)); customized.clear(); return null; })
            .when(repo).deleteSettingsProfile(anyString(), same(project));
        tool = new TestTool();
    }

    @After
    public void tearDown() throws Exception
    {
        temporary.close();
    }

    @Test
    public void deleteRefusesChangedValuesWithTheSameCustomizedCount()
    {
        tool.onConsent = () -> customized.put(UID, mutableSettings(true, IssueSeverity.CRITICAL));
        assertPreviewChanged(invoke("deleteProfile", "Review"));
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
        assertTrue(available.contains("Review"));
        assertEquals(1, customized.size());
    }

    @Test
    public void deleteRefusesAddedCustomizedCheck()
    {
        tool.onConsent = () -> customized.put(OTHER, mutableSettings(false, IssueSeverity.MINOR));
        assertPreviewChanged(invoke("deleteProfile", "Review"));
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
    }

    @Test
    public void deleteRefusesChangedContributorWithTheSameCheckId()
    {
        tool.onConsent = () -> {
            ICheckSettings value = customized.remove(UID);
            customized.put(new CheckUid(UID.getCheckId(), "replacement.contributor"), value);
        };
        assertPreviewChanged(invoke("deleteProfile", "Review"));
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
    }

    @Test
    public void deleteRefusesChangedParameterValue()
    {
        AtomicReference<String> value = new AtomicReference<>("before");
        ICheckParameterSettings parameter = mock(ICheckParameterSettings.class);
        when(parameter.getValue()).thenAnswer(call -> value.get());
        when(customized.get(UID).getParameters()).thenReturn(Map.of("threshold", parameter));
        tool.onConsent = () -> value.set("after");
        assertPreviewChanged(invoke("deleteProfile", "Review"));
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
    }

    @Test
    public void deleteFingerprintIgnoresCheckAndParameterMapOrdering()
    {
        customized.put(OTHER, mutableSettings(true, IssueSeverity.MAJOR));
        Map<String, ICheckParameterSettings> parameters = new LinkedHashMap<>();
        for (String name : List.of("a", "z"))
        {
            ICheckParameterSettings parameter = mock(ICheckParameterSettings.class);
            when(parameter.getValue()).thenReturn(name);
            parameters.put(name, parameter);
        }
        when(customized.get(UID).getParameters()).thenReturn(parameters);
        tool.onConsent = () -> {
            ICheckSettings value = customized.remove(UID);
            customized.put(UID, value);
            ICheckParameterSettings first = parameters.remove("a");
            parameters.put("a", first);
        };
        JsonObject result = ok(invoke("deleteProfile", "Review"));
        assertEquals(2, result.get("deletedCustomizedChecks").getAsInt());
        assertTrue(result.get("persisted").getAsBoolean());
        verify(repo).deleteSettingsProfile("Review", project);
    }

    @Test
    public void deleteUnchangedProfileReportsTheDeletedCount()
    {
        JsonObject result = ok(invoke("deleteProfile", "Review"));
        assertEquals("Review", result.get("deletedProfile").getAsString());
        assertEquals(1, result.get("deletedCustomizedChecks").getAsInt());
        assertTrue(result.get("persisted").getAsBoolean());
    }

    @Test
    public void setRefusesActiveProfileChangedWhileBuildingWritableCopies()
    {
        tool.onConsent = () -> onFreshCopy = () -> active.set("Review");
        String message = error(invoke("set", "ignored"));
        assertTrue(message, message.contains("active profile changed"));
        assertTrue(message, message.contains("Review"));
        verify(repo, never()).applyChanges(anyCollection(), same(project));
    }

    @Test
    public void setRefusesEnabledValueChangedDuringConsent()
    {
        tool.onConsent = () -> enabled.set(false);
        assertPreviewChanged(invoke("set", "ignored"));
        verify(repo, never()).applyChanges(anyCollection(), same(project));
    }

    @Test
    public void setRefusesOmittedSeverityChangedDuringConsent()
    {
        tool.onConsent = () -> severity.set(IssueSeverity.CRITICAL);
        assertPreviewChanged(invoke("set", "ignored"));
        verify(repo, never()).applyChanges(anyCollection(), same(project));
    }

    @Test
    public void setReportsActualActiveProfileWhenItChangesDuringWrite()
    {
        doAnswer(call -> {
            persist(call.getArgument(0));
            active.set("Review");
            return null;
        }).when(repo).applyChanges(anyCollection(), same(project));
        assertUnverifiedWrite(invoke("set", "ignored"), "Review", "active profile changed during the write");
        assertFalse(enabled.get());
        verify(repo).applyChanges(anyCollection(), same(project));
    }

    @Test
    public void setReportsRequestedValueReadBackMismatch()
    {
        doNothing().when(repo).applyChanges(anyCollection(), same(project));
        assertUnverifiedWrite(invoke("set", "ignored"), "Default", "read-back check values differ");
    }

    @Test
    public void setReportsOmittedValueReadBackMismatch()
    {
        doAnswer(call -> {
            persist(call.getArgument(0));
            severity.set(IssueSeverity.MINOR);
            return null;
        }).when(repo).applyChanges(anyCollection(), same(project));
        assertUnverifiedWrite(invoke("set", "ignored"), "Default", "read-back check values differ");
    }

    @Test
    public void setChecksTheActiveProfileAfterReadingBackChecks()
    {
        doAnswer(call -> {
            persist(call.getArgument(0));
            onFreshCopy = () -> active.set("Review");
            return null;
        }).when(repo).applyChanges(anyCollection(), same(project));
        assertUnverifiedWrite(invoke("set", "ignored"), "Review", "active profile changed during the write");
    }

    @Test
    public void setUnchangedPreviewAndMatchingReadBackReportsPersistence()
    {
        JsonObject result = ok(invoke("set", "ignored"));
        assertTrue(result.get("persisted").getAsBoolean());
        assertTrue(result.getAsJsonArray("before").get(0).getAsJsonObject().get("enabled").getAsBoolean());
        assertFalse(result.getAsJsonArray("after").get(0).getAsJsonObject().get("enabled").getAsBoolean());
    }

    @Test
    public void createReportsSymlinkReplacementDuringWrite() throws Exception
    {
        duplicateReplacesTarget(mockTarget("New", true, settings.resolve("local.cset")));
        assertReplaced(invoke("createProfile", "New"));
        verify(repo).duplicateSettingsProfile("Default", "New", project);
    }

    @Test
    public void copyReportsSymlinkReplacementDuringWrite() throws Exception
    {
        duplicateReplacesTarget(mockTarget("New", true, settings.resolve("local.cset")));
        assertReplaced(invoke("copyProfile", "New"));
        verify(repo).duplicateSettingsProfile("Review", "New", project);
    }

    @Test
    public void deleteReportsSymlinkReplacementDuringWrite() throws Exception
    {
        deleteReplacesTarget(mockTarget("Review", true, settings.resolve("local.cset")));
        String message = error(invoke("deleteProfile", "Review"));
        assertTrue(message, message.contains("was recreated during the write"));
        verify(repo).deleteSettingsProfile("Review", project);
    }

    @Test
    public void copyReportsRealParentReplacementDuringWrite() throws Exception
    {
        duplicateReplacesTarget(mockTarget("New", false, temporary.newFile("outside.cset").toPath()));
        assertReplaced(invoke("copyProfile", "New"));
    }

    @Test
    public void deleteReportsRealParentReplacementDuringWrite() throws Exception
    {
        deleteReplacesTarget(mockTarget("Review", false, temporary.newFile("outside.cset").toPath()));
        String message = error(invoke("deleteProfile", "Review"));
        assertTrue(message, message.contains("was recreated during the write"));
    }

    @Test
    public void copyReportsReplacementOfTheSettingsDirectoryItself() throws Exception
    {
        Path replacement = Files.createDirectory(temporary.getRoot().toPath().resolve("replacement-settings"));
        Runnable replaceTarget = mockTarget("New", false, replacement.resolve("New.cset"));
        duplicateReplacesTarget(() -> {
            when(folder.getLocation()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(replacement.toString()));
            replaceTarget.run();
        });
        assertReplaced(invoke("copyProfile", "New"));
    }

    @Test
    public void unchangedRegularTargetIsAcceptedAfterWrite() throws Exception
    {
        duplicateReplacesTarget(mockTarget("New", false, settings.resolve("New.cset")));
        assertTrue(ok(invoke("copyProfile", "New")).get("persisted").getAsBoolean());
    }

    private void recordCopy(String source, String target)
    {
        Map<CheckUid, ICheckSettings> copied = new LinkedHashMap<>(
            repo.getCustomizedSettingsFromProfile(source, project));
        when(repo.getCustomizedSettingsFromProfile(target, project)).thenReturn(copied);
        available.add(target);
    }

    private void duplicateReplacesTarget(Runnable replacement)
    {
        doAnswer(call -> {
            recordCopy(call.getArgument(0), call.getArgument(1));
            replacement.run();
            return null;
        }).when(repo).duplicateSettingsProfile(anyString(), anyString(), same(project));
    }

    private void deleteReplacesTarget(Runnable replacement)
    {
        doAnswer(call -> {
            available.remove(call.getArgument(0));
            customized.clear();
            replacement.run();
            return null;
        }).when(repo).deleteSettingsProfile(anyString(), same(project));
    }

    private Runnable mockTarget(String profile, boolean symbolic, Path realPath) throws IOException
    {
        return ProjectChecksTestFiles.mockTargetPath(folder, settings, profile, symbolic, realPath);
    }

    private static ICheckSettings mutableSettings(boolean initialEnabled, IssueSeverity initialSeverity)
    {
        ICheckSettings copy = mock(ICheckSettings.class);
        AtomicBoolean enabled = new AtomicBoolean(initialEnabled);
        AtomicReference<IssueSeverity> severity = new AtomicReference<>(initialSeverity);
        when(copy.isEnabled()).thenAnswer(call -> enabled.get());
        when(copy.getSeverity()).thenAnswer(call -> severity.get());
        doAnswer(call -> { enabled.set(call.getArgument(0)); return null; }).when(copy).setEnabled(anyBoolean());
        doAnswer(call -> { severity.set(call.getArgument(0)); return null; })
            .when(copy).setSeverity(any(IssueSeverity.class));
        return copy;
    }

    private void persist(Collection<ICheckSettings> copies)
    {
        for (ICheckSettings copy : copies)
        {
            enabled.set(copy.isEnabled());
            severity.set(copy.getSeverity());
        }
    }

    private String invoke(String action, String profile)
    {
        Map<String, String> params = new LinkedHashMap<>(Map.of("projectName", "TestConfiguration",
            "action", action, "profile", profile,
            "changes", "[{\"checkId\":\"sample-check|sample.contributor\",\"enabled\":false}]"));
        if ("copyProfile".equals(action))
        {
            params.put("from", "Review");
        }
        return tool.execute(params);
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
        assertFalse(text, result.has("persisted") && result.get("persisted").getAsBoolean());
        return result.get("error").getAsString();
    }

    private static void assertPreviewChanged(String text)
    {
        String message = error(text);
        assertTrue(message, message.contains("changed since the preview"));
        assertTrue(message, message.contains("re-run the preview"));
    }

    private static void assertUnverifiedWrite(String text, String actualProfile, String reason)
    {
        String message = error(text);
        assertTrue(message, message.contains("actually active profile '" + actualProfile + "'"));
        assertTrue(message, message.contains("change may have landed in '" + actualProfile + "'"));
        assertTrue(message, message.contains(reason));
        assertTrue(message, message.contains("Restore values from the preview in profile 'Default'"));
        assertTrue(message, message.contains("sample-check|sample.contributor"));
        assertTrue(message, message.contains("\"enabled\":true"));
        assertTrue(message, message.contains("\"severity\":\"MAJOR\""));
    }

    private static void assertReplaced(String text)
    {
        String message = error(text);
        assertTrue(message, message.contains("target was replaced during the write"));
    }

    private class TestTool extends ProjectChecksTool
    {
        Runnable onConsent = () -> { };

        @Override
        protected String buildingErrorOrNull(String name) { return null; }

        @Override
        protected IProject resolveProject(String name) { return project; }

        @Override
        protected ICheckRepository checkRepository() { return repo; }

        @Override
        protected ConsentDecision requireConsent(ConsentPreview preview)
        {
            onConsent.run();
            return ConsentDecision.ALLOW;
        }
    }
}
