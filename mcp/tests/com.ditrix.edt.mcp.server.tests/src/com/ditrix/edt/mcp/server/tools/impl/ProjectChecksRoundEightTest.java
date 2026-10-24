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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
import com.e1c.g5.v8.dt.check.settings.ICheckParameterSettings;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Copy/create plans and physical absence must survive consent and EDT's write interval. */
public class ProjectChecksRoundEightTest
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
    private final List<String> available = new CopyOnWriteArrayList<>(List.of("Default", "Review", "Other"));
    private final Map<String, Map<CheckUid, ICheckSettings>> profiles = new LinkedHashMap<>();
    private Runnable afterDuplicate = () -> { };
    private Runnable afterDelete = () -> { };
    private Runnable onSourceRead = () -> { };

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
        when(repo.getCustomizedSettingsFromProfile(anyString(), same(project))).thenAnswer(call -> {
            String name = call.getArgument(0);
            if (!"New".equals(name))
            {
                onSourceRead.run();
            }
            return profiles.getOrDefault(name, Map.of());
        });
        profiles.put("Default", new LinkedHashMap<>(Map.of(UID, value(false, IssueSeverity.MINOR, "default"))));
        profiles.put("Review", new LinkedHashMap<>(Map.of(UID, value(true, IssueSeverity.CRITICAL, "review"))));
        Files.writeString(settings.resolve("Default.cset"), "default source bytes");
        Files.writeString(settings.resolve("Review.cset"), "review source bytes");
        doAnswer(call -> {
            String source = call.getArgument(0);
            String target = call.getArgument(1);
            profiles.put(target, new LinkedHashMap<>(profiles.getOrDefault(source, Map.of())));
            available.add(target);
            Path sourceFile = settings.resolve(source + ".cset");
            // EDT copies a saved file, or saves an empty customization when no source file exists.
            Files.writeString(settings.resolve(target + ".cset"), Files.exists(sourceFile)
                ? Files.readString(sourceFile) : "{\"version\":1,\"settings\":{}}");
            afterDuplicate.run();
            return null;
        }).when(repo).duplicateSettingsProfile(anyString(), anyString(), same(project));
        doAnswer(call -> {
            String target = call.getArgument(0);
            Files.deleteIfExists(settings.resolve(target + ".cset"));
            profiles.remove(target);
            available.remove(target);
            afterDelete.run();
            return null;
        }).when(repo).deleteSettingsProfile(anyString(), same(project));
        doAnswer(call -> { active.set(call.getArgument(0)); return null; })
            .when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        tool = new TestTool();
    }

    @After
    public void tearDown() throws Exception
    {
        temporary.close();
    }

    @Test
    public void copyRefusesSourceValuesChangedDuringConsent()
    {
        tool.onConsent = () -> profiles.get("Review").put(UID, value(false, IssueSeverity.MINOR, "review"));
        assertSourceChanged(invoke("copyProfile", "New", "Review"));
        verifyNoCopy();
    }

    @Test
    public void createRefusesDefaultValuesChangedDuringConsent()
    {
        tool.onConsent = () -> profiles.get("Default").put(UID, value(true, IssueSeverity.MAJOR, "default"));
        assertSourceChanged(invoke("createProfile", "New", null));
        verifyNoCopy();
    }

    @Test
    public void createRefusesExplicitSourceChangedDuringConsent()
    {
        tool.onConsent = () -> profiles.get("Review").put(UID, value(false, IssueSeverity.MINOR, "changed"));
        assertSourceChanged(invoke("createProfile", "New", "Review"));
        verifyNoCopy();
    }

    @Test
    public void copyRefusesSourceParameterChangedOnTheSameSettingsObject()
    {
        AtomicReference<String> parameter = new AtomicReference<>("before");
        when(profiles.get("Review").get(UID).getParameters().get("threshold").getValue())
            .thenAnswer(call -> parameter.get());
        tool.onConsent = () -> parameter.set("after");
        assertSourceChanged(invoke("copyProfile", "New", "Review"));
        verifyNoCopy();
    }

    @Test
    public void copyRefusesSourceContributorChangedWithTheSameCheckId()
    {
        tool.onConsent = () -> {
            ICheckSettings original = profiles.get("Review").remove(UID);
            profiles.get("Review").put(new CheckUid(UID.getCheckId(), "replacement.contributor"), original);
        };
        assertSourceChanged(invoke("copyProfile", "New", "Review"));
        verifyNoCopy();
    }

    @Test
    public void copyRefusesAnAddedSourceCustomization()
    {
        tool.onConsent = () -> profiles.get("Review").put(OTHER, value(false, IssueSeverity.MINOR, "extra"));
        assertSourceChanged(invoke("copyProfile", "New", "Review"));
        verifyNoCopy();
    }

    @Test
    public void createRefusesCustomizationOfPreviouslyEmptyImplicitDefault() throws Exception
    {
        removeSavedDefault();
        tool.onConsent = () -> profiles.put("Default", Map.of(UID, value(false, IssueSeverity.MINOR, "new")));
        assertSourceChanged(invoke("createProfile", "New", null));
        verifyNoCopy();
    }

    @Test
    public void copyRejectsMismatchingTargetValuesAfterDuplication()
    {
        afterDuplicate = () -> profiles.get("New").put(UID, value(false, IssueSeverity.MINOR, "review"));
        assertCopyMismatch(invoke("copyProfile", "New", "Review"), "Review");
        verify(repo).duplicateSettingsProfile("Review", "New", project);
    }

    @Test
    public void createRejectsMismatchingDefaultCopyAfterDuplication()
    {
        afterDuplicate = () -> profiles.get("New").put(UID, value(true, IssueSeverity.MAJOR, "default"));
        assertCopyMismatch(invoke("createProfile", "New", null), "Default");
    }

    @Test
    public void createRejectsMismatchingExplicitCopyAfterDuplication()
    {
        afterDuplicate = () -> profiles.get("New").put(UID, value(false, IssueSeverity.MAJOR, "review"));
        assertCopyMismatch(invoke("createProfile", "New", "Review"), "Review");
    }

    @Test
    public void copyRejectsMismatchingTargetParameters()
    {
        afterDuplicate = () -> profiles.get("New").put(UID, value(true, IssueSeverity.CRITICAL, "wrong"));
        assertCopyMismatch(invoke("copyProfile", "New", "Review"), "Review");
    }

    @Test
    public void copyRejectsMismatchingTargetContributors()
    {
        afterDuplicate = () -> {
            ICheckSettings original = profiles.get("New").remove(UID);
            profiles.get("New").put(new CheckUid(UID.getCheckId(), "replacement.contributor"), original);
        };
        assertCopyMismatch(invoke("copyProfile", "New", "Review"), "Review");
    }

    @Test
    public void copyRequiresTheTargetToBeListedEvenWhenItsFingerprintMatches()
    {
        afterDuplicate = () -> available.remove("New");
        assertCopyMismatch(invoke("copyProfile", "New", "Review"), "Review");
    }

    @Test
    public void copyComparesTheTargetToThePreviewRatherThanTheNowChangedSource()
    {
        afterDuplicate = () -> {
            ICheckSettings changed = value(false, IssueSeverity.TRIVIAL, "raced");
            profiles.get("Review").put(UID, changed);
            profiles.get("New").put(UID, changed);
        };
        assertCopyMismatch(invoke("copyProfile", "New", "Review"), "Review");
    }

    @Test
    public void copyAcceptsMatchingFingerprintsDespiteMapOrdering()
    {
        profiles.get("Review").put(OTHER, value(false, IssueSeverity.MINOR, "extra"));
        tool.onConsent = () -> {
            ICheckSettings first = profiles.get("Review").remove(UID);
            profiles.get("Review").put(UID, first);
        };
        assertPersisted(invoke("copyProfile", "New", "Review"));
        assertEquals(2, profiles.get("New").size());
    }

    @Test
    public void createAcceptsAnEmptyCopyOfUnsavedUnlistedDefault() throws Exception
    {
        removeSavedDefault();
        JsonObject result = assertPersisted(invoke("createProfile", "New", null));
        assertEquals("Default", result.get("from").getAsString());
        assertTrue(profiles.get("New").isEmpty());
        assertEquals("{\"version\":1,\"settings\":{}}", Files.readString(settings.resolve("New.cset")));
    }

    @Test
    public void copyRefusesAnUnlistedRegularFileBeforeConsent() throws Exception
    {
        existingRegularFile("copyProfile", false);
    }

    @Test
    public void createRefusesAnUnlistedRegularFileBeforeConsent() throws Exception
    {
        existingRegularFile("createProfile", false);
    }

    @Test
    public void copyRefusesAListedPhysicalFileWithTheFileExistenceMessage() throws Exception
    {
        available.add("New");
        existingRegularFile("copyProfile", false);
    }

    @Test
    public void copyRefusesAFileCreatedDuringConsent() throws Exception
    {
        existingRegularFile("copyProfile", true);
    }

    @Test
    public void createRefusesAFileCreatedDuringConsent() throws Exception
    {
        existingRegularFile("createProfile", true);
    }

    @Test
    public void copyChecksAbsenceAgainImmediatelyBeforeDuplication()
    {
        tool.onConsent = () -> onSourceRead = () -> write("New", "arrived during the final source read");
        assertExists(invoke("copyProfile", "New", "Review"));
        verifyNoCopy();
    }

    @Test
    public void copyRefusesAnUnlistedDirectoryBeforeConsent() throws Exception
    {
        Files.createDirectory(settings.resolve("New.cset"));
        assertExists(invoke("copyProfile", "New", "Review"));
        assertEquals(0, tool.consents);
        verifyNoCopy();
    }

    @Test
    public void createRefusesAnUnlistedDirectoryBeforeConsent() throws Exception
    {
        Files.createDirectory(settings.resolve("New.cset"));
        assertExists(invoke("createProfile", "New", null));
        assertEquals(0, tool.consents);
        verifyNoCopy();
    }

    @Test
    public void copyRefusesAnUnlistedDanglingLinkBeforeConsent() throws Exception
    {
        danglingTarget("New").run();
        assertExists(invoke("copyProfile", "New", "Review"));
        assertEquals(0, tool.consents);
        verifyNoCopy();
    }

    @Test
    public void createRefusesADanglingLinkDuringConsent() throws Exception
    {
        tool.onConsent = danglingTarget("New");
        assertExists(invoke("createProfile", "New", null));
        assertEquals(1, tool.consents);
        verifyNoCopy();
    }

    @Test
    public void switchRejectsASilentFailureAndNamesPlannedAndActualProfiles()
    {
        doNothing().when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        assertSwitchMismatch(invoke("switch", "Review", null), "Review", "Default");
    }

    @Test
    public void switchRejectsAProfileChangedByEdtDuringTheWrite()
    {
        doAnswer(call -> { active.set("Other"); return null; })
            .when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        assertSwitchMismatch(invoke("switch", "Review", null), "Review", "Other");
    }

    @Test
    public void switchAcceptsTheVerifiedTarget()
    {
        JsonObject result = assertPersisted(invoke("switch", "Review", null));
        assertEquals("Review", result.getAsJsonObject("after").get("activeProfile").getAsString());
    }

    @Test
    public void deleteRejectsARegularFileRecreatedDuringTheWrite()
    {
        afterDelete = () -> write("Review", "recreated by an external process");
        assertRecreated(invoke("deleteProfile", "Review", null));
        assertFalse(available.contains("Review"));
    }

    @Test
    public void deleteRejectsADirectoryRecreatedDuringTheWrite()
    {
        afterDelete = () -> {
            try
            {
                Files.createDirectory(settings.resolve("Review.cset"));
            }
            catch (IOException e)
            {
                throw new AssertionError(e);
            }
        };
        assertRecreated(invoke("deleteProfile", "Review", null));
    }

    @Test
    public void deleteRejectsADanglingLinkRecreatedDuringTheWrite() throws Exception
    {
        afterDelete = danglingTarget("Review");
        assertRecreated(invoke("deleteProfile", "Review", null));
    }

    @Test
    public void deleteRejectsAProfileStillListedAfterTheWrite()
    {
        afterDelete = () -> available.add("Review");
        String message = error(invoke("deleteProfile", "Review", null));
        assertTrue(message, message.contains("still listed after the write"));
    }

    @Test
    public void deleteAcceptsVerifiedPhysicalAbsence()
    {
        assertPersisted(invoke("deleteProfile", "Review", null));
        assertFalse(Files.exists(settings.resolve("Review.cset"), LinkOption.NOFOLLOW_LINKS));
    }

    private void removeSavedDefault() throws IOException
    {
        Files.delete(settings.resolve("Default.cset"));
        profiles.remove("Default");
        available.remove("Default");
        active.set("Review");
    }

    private void existingRegularFile(String action, boolean duringConsent) throws IOException
    {
        if (duringConsent)
        {
            tool.onConsent = () -> write("New", "must not be overwritten");
        }
        else
        {
            write("New", "must not be overwritten");
        }
        assertExists(invoke(action, "New", "copyProfile".equals(action) ? "Review" : null));
        assertEquals("must not be overwritten", Files.readString(settings.resolve("New.cset")));
        assertEquals(duringConsent ? 1 : 0, tool.consents);
        verifyNoCopy();
    }

    private Runnable danglingTarget(String name) throws IOException
    {
        return ProjectChecksTestFiles.mockTargetPath(folder, settings, name, true,
            settings.resolve("missing-referent.cset"), true);
    }

    private void write(String name, String content)
    {
        try
        {
            Files.writeString(settings.resolve(name + ".cset"), content);
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }
    }

    private void verifyNoCopy()
    {
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
    }

    private static ICheckSettings value(boolean enabled, IssueSeverity severity, String parameterValue)
    {
        ICheckSettings value = mock(ICheckSettings.class);
        ICheckParameterSettings parameter = mock(ICheckParameterSettings.class);
        when(value.isEnabled()).thenReturn(enabled);
        when(value.getSeverity()).thenReturn(severity);
        when(parameter.getValue()).thenReturn(parameterValue);
        when(value.getParameters()).thenReturn(Map.of("threshold", parameter));
        return value;
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

    private static JsonObject assertPersisted(String text)
    {
        JsonObject result = JsonParser.parseString(text).getAsJsonObject();
        assertTrue(text, result.get("success").getAsBoolean());
        assertTrue(text, result.get("persisted").getAsBoolean());
        return result;
    }

    private static String error(String text)
    {
        JsonObject result = JsonParser.parseString(text).getAsJsonObject();
        assertFalse(text, result.get("success").getAsBoolean());
        assertFalse(text, result.has("persisted") && result.get("persisted").getAsBoolean());
        return result.get("error").getAsString();
    }

    private static void assertSourceChanged(String text)
    {
        String message = error(text);
        assertTrue(message, message.contains("source profile changed since the preview"));
        assertTrue(message, message.contains("re-run the preview"));
    }

    private static void assertCopyMismatch(String text, String source)
    {
        String message = error(text);
        assertTrue(message, message.contains("copy could not be verified"));
        assertTrue(message, message.contains("target profile 'New'"));
        assertTrue(message, message.contains("source profile '" + source + "' in the preview"));
    }

    private static void assertExists(String text)
    {
        String message = error(text);
        assertTrue(message, message.contains("a file named New.cset already exists in .settings"));
    }

    private static void assertSwitchMismatch(String text, String planned, String actual)
    {
        String message = error(text);
        assertTrue(message, message.contains("planned profile '" + planned + "'"));
        assertTrue(message, message.contains("actually active profile '" + actual + "'"));
    }

    private static void assertRecreated(String text)
    {
        String message = error(text);
        assertTrue(message, message.contains("Profile 'Review' was recreated during the write"));
    }

    private class TestTool extends ProjectChecksTool
    {
        Runnable onConsent = () -> { };
        int consents;

        @Override
        protected String buildingErrorOrNull(String name) { return null; }

        @Override
        protected IProject resolveProject(String name) { return project; }

        @Override
        protected ICheckRepository checkRepository() { return repo; }

        @Override
        protected ConsentDecision requireConsent(ConsentPreview preview)
        {
            consents++;
            onConsent.run();
            return ConsentDecision.ALLOW;
        }
    }
}
