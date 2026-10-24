/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.IPath;
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

/** All five writes must bind consent to their verified repository and physical outcomes. */
public class ProjectChecksRoundNineTest
{
    private static final CheckUid UID = new CheckUid("sample-check", "sample.contributor");
    private ProjectChecksTestFiles temporary;
    private IProject project;
    private IFolder folder;
    private Path settings;
    private ICheckRepository repo;
    private TestTool tool;
    private final AtomicReference<String> active = new AtomicReference<>("Default");
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final List<String> available = new CopyOnWriteArrayList<>(List.of("Default", "Review", "Other"));
    private final Map<String, Map<CheckUid, ICheckSettings>> profiles = new LinkedHashMap<>();
    private Runnable afterWrite = () -> { };
    private Runnable onFreshCopy = () -> { };
    private Runnable onFingerprint = () -> { };

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
        when(repo.getCheckUidForCheckId(UID.getCheckId(), project)).thenReturn(Set.of(UID));
        when(repo.getSettings(UID, project)).thenAnswer(call -> {
            onFreshCopy.run();
            return value(enabled.get(), "effective");
        });
        when(repo.getCustomizedSettingsFromProfile(anyString(), same(project))).thenAnswer(call -> {
            onFingerprint.run();
            return profiles.getOrDefault(call.getArgument(0), Map.of());
        });
        for (String name : available)
        {
            profiles.put(name, new LinkedHashMap<>(Map.of(UID, value(true, name))));
            write(name, "saved " + name);
        }
        doAnswer(call -> {
            Collection<ICheckSettings> copies = call.getArgument(0);
            enabled.set(copies.iterator().next().isEnabled());
            if (!Files.isDirectory(settings.resolve(active.get() + ".cset"), LinkOption.NOFOLLOW_LINKS))
            {
                write(active.get(), "persisted settings");
            }
            afterWrite.run();
            return null;
        }).when(repo).applyChanges(anyCollection(), same(project));
        doAnswer(call -> {
            active.set(call.getArgument(0));
            // A switch with an empty overlay loads the file; it does not save an unsaved Default.
            afterWrite.run();
            return null;
        }).when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        doAnswer(call -> {
            String source = call.getArgument(0);
            String target = call.getArgument(1);
            profiles.put(target, new LinkedHashMap<>(profiles.getOrDefault(source, Map.of())));
            available.add(target);
            write(target, "persisted copy");
            afterWrite.run();
            return null;
        }).when(repo).duplicateSettingsProfile(anyString(), anyString(), same(project));
        doAnswer(call -> {
            String target = call.getArgument(0);
            remove(target);
            profiles.remove(target);
            available.remove(target);
            afterWrite.run();
            return null;
        }).when(repo).deleteSettingsProfile(anyString(), same(project));
        tool = new TestTool();
    }

    @After
    public void tearDown() throws Exception
    {
        temporary.close();
    }

    @Test
    public void copyRequiresPhysicalFileEvenWhenCachedFingerprintMatches()
    {
        afterWrite = () -> remove("New");
        assertMissingAfter(invoke("copyProfile"));
        assertTrue(available.contains("New"));
        assertEquals(profiles.get("Review"), profiles.get("New"));
    }

    @Test
    public void createRequiresPhysicalFileForAnEmptyImplicitDefault()
    {
        unsaveDefault();
        afterWrite = () -> remove("New");
        assertMissingAfter(invoke("createProfile"));
        assertTrue(profiles.get("New").isEmpty());
    }

    @Test
    public void createRequiresPhysicalFileForAnExplicitSource()
    {
        afterWrite = () -> remove("New");
        assertMissingAfter(invoke("createProfile", "New", "Review"));
    }

    @Test
    public void setRefusesASymbolicActiveTargetBeforeWriting() throws Exception
    {
        target("Default", true, false, false).run();
        assertUnsafe(invoke("set"));
        verifyNoWrite("set");
    }

    @Test
    public void setRefusesADanglingActiveTargetBeforeWriting() throws Exception
    {
        target("Default", true, true, false).run();
        assertUnsafe(invoke("set"));
        verifyNoWrite("set");
    }

    @Test
    public void setRefusesAnExternalRealParentBeforeWriting() throws Exception
    {
        target("Default", false, false, true).run();
        assertUnsafe(invoke("set"));
        verifyNoWrite("set");
    }

    @Test
    public void setRefusesANonRegularActiveTargetBeforeWriting() throws Exception
    {
        remove("Default");
        Files.createDirectory(settings.resolve("Default.cset"));
        assertUnsafe(invoke("set"));
        verifyNoWrite("set");
    }

    @Test
    public void setValidatesTheActiveProfileFileStemBeforeWriting()
    {
        active.set("../Escaped");
        assertContains(invoke("set"), "a profile name is a file stem in .settings");
        verifyNoWrite("set");
    }

    @Test
    public void setRefusesAMissingSavedActiveTargetBeforeWriting()
    {
        active.set("Review");
        remove("Review");
        assertContains(invoke("set"), ".cset is missing from .settings");
        verifyNoWrite("set");
    }

    @Test
    public void setChecksTheTargetAfterFreshSettingsCopies() throws Exception
    {
        Runnable replace = target("Default", true, false, false);
        tool.onConsent = () -> onFreshCopy = replace;
        assertUnsafe(invoke("set"));
        verifyNoWrite("set");
    }

    @Test
    public void setChecksTheExactActiveAliasUsedByEdt() throws Exception
    {
        active.set("Caf\u00e9");
        write(active.get(), "saved canonical profile");
        String alias = "CAFE\u0301";
        Runnable replace = target(alias, true, false, false);
        tool.onConsent = () -> { active.set(alias); replace.run(); };
        assertUnsafe(invoke("set"));
        verifyNoWrite("set");
    }

    @Test
    public void setChecksAnActiveAliasIntroducedDuringWriting() throws Exception
    {
        Runnable replace = target("default", true, false, false);
        afterWrite = () -> { active.set("default"); replace.run(); };
        assertReplaced(invoke("set"));
    }

    @Test
    public void setRejectsAMissingFileDespiteMatchingRepositoryReadBack()
    {
        afterWrite = () -> remove("Default");
        assertMissingAfter(invoke("set"));
        assertFalse(enabled.get());
    }

    @Test
    public void setRejectsASymbolicTargetAfterWriting() throws Exception
    {
        afterWrite = target("Default", true, false, false);
        assertReplaced(invoke("set"));
    }

    @Test
    public void setRejectsAnExternalRealParentAfterWriting() throws Exception
    {
        afterWrite = target("Default", false, false, true);
        assertReplaced(invoke("set"));
    }

    @Test
    public void setRejectsANonRegularTargetAfterWriting()
    {
        afterWrite = () -> directory("Default");
        assertReplaced(invoke("set"));
    }

    @Test
    public void switchRefusesASymbolicTargetBeforeWriting() throws Exception
    {
        target("Review", true, false, false).run();
        assertUnsafe(invoke("switch"));
        verifyNoWrite("switch");
    }

    @Test
    public void switchRefusesADanglingTargetBeforeWriting() throws Exception
    {
        target("Review", true, true, false).run();
        assertUnsafe(invoke("switch"));
        verifyNoWrite("switch");
    }

    @Test
    public void switchRefusesAnExternalRealParentBeforeWriting() throws Exception
    {
        target("Review", false, false, true).run();
        assertUnsafe(invoke("switch"));
        verifyNoWrite("switch");
    }

    @Test
    public void switchRefusesANonRegularTargetBeforeWriting()
    {
        directory("Review");
        assertUnsafe(invoke("switch"));
        verifyNoWrite("switch");
    }

    @Test
    public void switchRefusesAMissingSavedTargetBeforeWriting()
    {
        remove("Review");
        assertContains(invoke("switch"), ".cset is missing from .settings");
        verifyNoWrite("switch");
    }

    @Test
    public void switchRejectsASymbolicTargetAfterWriting() throws Exception
    {
        afterWrite = target("Review", true, false, false);
        assertReplaced(invoke("switch"));
    }

    @Test
    public void switchRejectsAMissingSavedTargetAfterWriting()
    {
        afterWrite = () -> remove("Review");
        assertMissingAfter(invoke("switch"));
    }

    @Test
    public void switchRejectsARemovedSavedDefaultAfterWriting()
    {
        active.set("Review");
        afterWrite = () -> remove("Default");
        assertMissingAfter(invoke("switch", "Default", null));
    }

    @Test
    public void switchRejectsATargetNoLongerListedAfterWriting()
    {
        afterWrite = () -> available.remove("Review");
        assertContains(invoke("switch"), "switch could not be verified");
    }

    @Test
    public void switchRefusesTargetValuesChangedDuringConsent()
    {
        tool.onConsent = () -> profiles.get("Review").put(UID, value(false, "Review"));
        assertContains(invoke("switch"), "target profile changed since the preview; re-run the preview");
        verifyNoWrite("switch");
    }

    @Test
    public void switchRefusesTargetParametersChangedDuringConsent()
    {
        tool.onConsent = () -> profiles.get("Review").put(UID, value(true, "changed"));
        assertContains(invoke("switch"), "target profile changed since the preview; re-run the preview");
        verifyNoWrite("switch");
    }

    @Test
    public void switchRejectsTargetValuesChangedDuringWriting()
    {
        afterWrite = () -> profiles.get("Review").put(UID, value(false, "Review"));
        assertContains(invoke("switch"), "read-back settings differ from the preview");
    }

    @Test
    public void copyRefusesAnActiveProfileChangedDuringConsent()
    {
        tool.onConsent = () -> active.set("Other");
        assertContains(invoke("copyProfile"), "active profile changed from 'Default' to 'Other'");
        verifyNoWrite("copyProfile");
    }

    @Test
    public void createRefusesAnActiveProfileChangedDuringConsent()
    {
        tool.onConsent = () -> active.set("Other");
        assertContains(invoke("createProfile"), "active profile changed from 'Default' to 'Other'");
        verifyNoWrite("createProfile");
    }

    @Test
    public void copyRejectsAnActiveProfileChangedDuringWriting()
    {
        afterWrite = () -> active.set("Other");
        assertContains(invoke("copyProfile"), "active profile changed during the write");
    }

    @Test
    public void createRejectsAnActiveProfileChangedDuringWriting()
    {
        afterWrite = () -> active.set("Other");
        assertContains(invoke("createProfile"), "active profile changed during the write");
    }

    @Test
    public void deleteRejectsTheDeletedProfileBecomingActiveDuringWriting()
    {
        afterWrite = () -> active.set("Review");
        assertContains(invoke("deleteProfile"), "Deleted profile 'Review' became active during the write");
    }

    @Test
    public void deleteRejectsACanonicalCaseAliasBecomingActiveDuringWriting()
    {
        String name = "Caf\u00e9";
        available.add(name);
        profiles.put(name, Map.of());
        write(name, "saved alias profile");
        afterWrite = () -> active.set("CAFE\u0301");
        assertContains(invoke("deleteProfile", name, null), "became active during the write");
    }

    @Test
    public void deleteRejectsADifferentActiveProfileAfterWriting()
    {
        afterWrite = () -> active.set("Other");
        assertContains(invoke("deleteProfile"), "active profile changed during the write");
    }

    @Test
    public void deleteRechecksActiveAfterReadingTheFinalFingerprint()
    {
        tool.onConsent = () -> onFingerprint = () -> active.set("Other");
        assertContains(invoke("deleteProfile"), "active profile changed from 'Default' to 'Other'");
        verifyNoWrite("deleteProfile");
    }

    @Test
    public void setRefusesALinkedSettingsDirectory() throws Exception { refuseLinkedDirectory("set"); }

    @Test
    public void switchRefusesALinkedSettingsDirectory() throws Exception { refuseLinkedDirectory("switch"); }

    @Test
    public void createRefusesALinkedSettingsDirectory() throws Exception { refuseLinkedDirectory("createProfile"); }

    @Test
    public void copyRefusesALinkedSettingsDirectory() throws Exception { refuseLinkedDirectory("copyProfile"); }

    @Test
    public void deleteRefusesALinkedSettingsDirectory() throws Exception { refuseLinkedDirectory("deleteProfile"); }

    @Test
    public void copyRefusesAJunctionSettingsDirectory() throws Exception
    {
        redirectedSettings(false, true).run();
        assertUnsafe(invoke("copyProfile"));
        verifyNoWrite("copyProfile");
    }

    @Test
    public void setRejectsSettingsDirectoryLinkedDuringWriting() throws Exception { linkedDuringWrite("set"); }

    @Test
    public void switchRejectsSettingsDirectoryLinkedDuringWriting() throws Exception { linkedDuringWrite("switch"); }

    @Test
    public void createRejectsSettingsDirectoryLinkedDuringWriting() throws Exception { linkedDuringWrite("createProfile"); }

    @Test
    public void copyRejectsSettingsDirectoryLinkedDuringWriting() throws Exception { linkedDuringWrite("copyProfile"); }

    @Test
    public void deleteRejectsSettingsDirectoryLinkedDuringWriting() throws Exception { linkedDuringWrite("deleteProfile"); }

    @Test
    public void setAcceptsUnsavedDefaultAndRequiresItsNewRegularFile()
    {
        unsaveDefault();
        persisted(invoke("set"));
        assertTrue(Files.isRegularFile(settings.resolve("Default.cset"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void switchAcceptsUnsavedUnlistedDefaultWithoutCreatingAFile()
    {
        unsaveDefault();
        active.set("Review");
        persisted(invoke("switch", "default", null));
        assertEquals("Default", active.get());
        assertFalse(Files.exists(settings.resolve("Default.cset"), LinkOption.NOFOLLOW_LINKS));
        verify(repo).setActiveSettingsProfile("Default", List.of(), project);
    }

    @Test
    public void switchUsesTheListedSpellingForFingerprintAndEdtCall()
    {
        persisted(invoke("switch", "review", null));
        verify(repo).setActiveSettingsProfile("Review", List.of(), project);
    }

    @Test
    public void allWritesAcceptVerifiedRegularTargetsAndUnchangedProfiles()
    {
        for (String action : List.of("set", "switch", "createProfile", "copyProfile", "deleteProfile"))
        {
            if (action.equals("copyProfile"))
            {
                remove("New");
                available.remove("New");
            }
            if (action.equals("deleteProfile"))
            {
                active.set("Default");
            }
            persisted(invoke(action));
        }
    }

    private void refuseLinkedDirectory(String action) throws IOException
    {
        redirectedSettings(true, false).run();
        assertUnsafe(invoke(action));
        verifyNoWrite(action);
    }

    private void linkedDuringWrite(String action) throws IOException
    {
        afterWrite = redirectedSettings(true, false);
        assertReplaced(invoke(action));
    }

    private Runnable redirectedSettings(boolean symbolic, boolean junction) throws IOException
    {
        // Model the directory itself without requiring Windows symlink privileges. The target
        // remains safe relative to its real root, isolating the check of the .settings directory.
        Path path = mock(Path.class);
        FileSystem fileSystem = mock(FileSystem.class);
        FileSystemProvider provider = mock(FileSystemProvider.class, CALLS_REAL_METHODS);
        BasicFileAttributes attributes = mock(BasicFileAttributes.class);
        when(path.getFileSystem()).thenReturn(fileSystem);
        when(fileSystem.provider()).thenReturn(provider);
        when(provider.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)).thenReturn(attributes);
        when(attributes.isSymbolicLink()).thenReturn(symbolic);
        when(attributes.isDirectory()).thenReturn(true);
        when(path.toRealPath()).thenReturn(settings.toRealPath());
        when(path.getFileName()).thenReturn(settings.getFileName());
        when(path.getParent()).thenReturn(junction
            ? Files.createDirectory(temporary.getRoot().toPath().resolve("junction-parent")) : settings.getParent());
        IPath location = mock(IPath.class);
        File file = mock(File.class);
        when(location.toFile()).thenReturn(file);
        when(file.toPath()).thenReturn(path);
        return () -> when(folder.getLocation()).thenReturn(location);
    }

    private Runnable target(String name, boolean symbolic, boolean dangling, boolean outside) throws IOException
    {
        Path real = outside ? temporary.newFile("external.cset").toPath() : settings.resolve(name + ".cset");
        return ProjectChecksTestFiles.mockTargetPath(folder, settings, name, symbolic, real, dangling);
    }

    private static ICheckSettings value(boolean enabled, String parameterValue)
    {
        ICheckSettings result = mock(ICheckSettings.class);
        AtomicBoolean mutable = new AtomicBoolean(enabled);
        when(result.getId()).thenReturn(UID);
        when(result.isEnabled()).thenAnswer(call -> mutable.get());
        doAnswer(call -> { mutable.set(call.getArgument(0)); return null; }).when(result).setEnabled(anyBoolean());
        when(result.getSeverity()).thenReturn(IssueSeverity.MAJOR);
        ICheckParameterSettings parameter = mock(ICheckParameterSettings.class);
        when(parameter.getValue()).thenReturn(parameterValue);
        when(result.getParameters()).thenReturn(Map.of("threshold", parameter));
        return result;
    }

    private void write(String name, String contents)
    {
        try { Files.writeString(settings.resolve(name + ".cset"), contents); }
        catch (IOException e) { throw new AssertionError(e); }
    }

    private void remove(String name)
    {
        try { Files.deleteIfExists(settings.resolve(name + ".cset")); }
        catch (IOException e) { throw new AssertionError(e); }
    }

    private void directory(String name)
    {
        remove(name);
        try { Files.createDirectory(settings.resolve(name + ".cset")); }
        catch (IOException e) { throw new AssertionError(e); }
    }

    private void unsaveDefault()
    {
        remove("Default");
        available.remove("Default");
        profiles.remove("Default");
    }

    private void verifyNoWrite(String action)
    {
        switch (action)
        {
            case "set": verify(repo, never()).applyChanges(anyCollection(), same(project)); break;
            case "switch": verify(repo, never()).setActiveSettingsProfile(anyString(), anyCollection(), same(project)); break;
            case "deleteProfile": verify(repo, never()).deleteSettingsProfile(anyString(), same(project)); break;
            default: verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project)); break;
        }
    }

    private String invoke(String action)
    {
        return invoke(action, action.equals("createProfile") || action.equals("copyProfile") ? "New" : "Review",
            action.equals("copyProfile") ? "Review" : null);
    }

    private String invoke(String action, String profile, String source)
    {
        Map<String, String> params = new LinkedHashMap<>(Map.of("projectName", "TestConfiguration", "action", action,
            "profile", profile, "changes", "[{\"checkId\":\"sample-check\",\"enabled\":false}]"));
        if (source != null) { params.put("from", source); }
        return tool.execute(params);
    }

    private static void persisted(String text)
    {
        JsonObject result = JsonParser.parseString(text).getAsJsonObject();
        assertTrue(text, result.get("success").getAsBoolean());
        assertTrue(text, result.get("persisted").getAsBoolean());
    }

    private static void assertContains(String text, String expected)
    {
        JsonObject result = JsonParser.parseString(text).getAsJsonObject();
        assertFalse(text, result.get("success").getAsBoolean());
        assertFalse(text, result.has("persisted") && result.get("persisted").getAsBoolean());
        assertTrue(text, result.get("error").getAsString().contains(expected));
    }

    private static void assertUnsafe(String text) { assertContains(text, "Cannot safely access profile"); }
    private static void assertReplaced(String text) { assertContains(text, "target was replaced during the write"); }
    private static void assertMissingAfter(String text) { assertContains(text, ".cset is missing after the write"); }

    private class TestTool extends ProjectChecksTool
    {
        Runnable onConsent = () -> { };
        @Override protected String buildingErrorOrNull(String name) { return null; }
        @Override protected IProject resolveProject(String name) { return project; }
        @Override protected ICheckRepository checkRepository() { return repo; }
        @Override protected ConsentDecision requireConsent(ConsentPreview preview)
        {
            onConsent.run();
            return ConsentDecision.ALLOW;
        }
    }
}
