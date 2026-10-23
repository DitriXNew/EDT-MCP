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
import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.Status;
import org.junit.Assume;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckDescription;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Consent races, per-project serialization, canonical names and physical profile targets. */
public class ProjectChecksRoundSixTest
{
    private static final String NFC = "Café";
    private static final String NFD = Normalizer.normalize(NFC, Normalizer.Form.NFD);
    private static final CheckUid UID = new CheckUid("sample-check", "sample.contributor");

    private ProjectChecksTestFiles temporary;

    private ICheckRepository repo;
    private IProject project;
    private IFolder folder;
    private Path settings;
    private TestTool tool;
    private final AtomicReference<String> active = new AtomicReference<>("Default");
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final List<String> available = new CopyOnWriteArrayList<>(List.of("Default", "Review", "Other"));

    @Before
    public void setUp() throws Exception
    {
        temporary = new ProjectChecksTestFiles();
        repo = mock(ICheckRepository.class);
        project = mock(IProject.class);
        folder = mock(IFolder.class);
        IWorkspace workspace = mock(IWorkspace.class);
        when(project.getName()).thenReturn("TestConfiguration");
        when(project.isOpen()).thenReturn(true);
        when(project.getWorkspace()).thenReturn(workspace);
        when(workspace.validateName(anyString(), eq(IResource.FILE))).thenReturn(Status.OK_STATUS);
        when(project.getFolder(".settings")).thenReturn(folder);
        settings = ProjectChecksTestFiles.locate(folder, temporary, "Default", "Review", "Other");
        when(repo.validateProfileName(anyString())).thenReturn(true);
        when(repo.getActiveSettingsProfile(project)).thenAnswer(call -> active.get());
        when(repo.getAvailableSettingsProfiles(project)).thenAnswer(call -> List.copyOf(available));
        when(repo.getChecksWithDescriptions()).thenReturn(Map.of(UID, mock(ICheckDescription.class)));
        when(repo.getSettings(UID, project)).thenAnswer(call -> {
            ICheckSettings copy = mock(ICheckSettings.class);
            AtomicBoolean value = new AtomicBoolean(enabled.get());
            when(copy.isEnabled()).thenAnswer(ignored -> value.get());
            when(copy.getSeverity()).thenReturn(IssueSeverity.MAJOR);
            doAnswer(update -> { value.set(update.getArgument(0)); return null; })
                .when(copy).setEnabled(anyBoolean());
            return copy;
        });
        doAnswer(call -> {
            for (ICheckSettings copy : call.<Collection<ICheckSettings>>getArgument(0))
            {
                enabled.set(copy.isEnabled());
            }
            return null;
        }).when(repo).applyChanges(anyCollection(), same(project));
        doAnswer(call -> {
            String target = call.getArgument(0);
            // Like EDT, switching to an absent name creates it: a validation race is observable.
            if (!available.contains(target))
            {
                available.add(target);
            }
            active.set(target);
            return null;
        }).when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        doAnswer(call -> { available.add(call.getArgument(1)); ProjectChecksTestFiles.save(folder, call.getArgument(1)); return null; })
            .when(repo).duplicateSettingsProfile(anyString(), anyString(), same(project));
        doAnswer(call -> { available.remove(call.getArgument(0)); ProjectChecksTestFiles.remove(folder, call.getArgument(0)); return null; })
            .when(repo).deleteSettingsProfile(anyString(), same(project));
        tool = new TestTool(project);
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
    public void setRefusesActiveProfileChangedDuringConsent()
    {
        tool.onConsent = () -> active.set("Review");
        assertActiveChanged(invoke("set", "ignored", null));
        verify(repo, never()).applyChanges(anyCollection(), same(project));
        assertTrue(enabled.get());
    }

    @Test
    public void switchRefusesActiveProfileChangedDuringConsent()
    {
        tool.onConsent = () -> active.set("Other");
        assertActiveChanged(invoke("switch", "Review", null));
        verify(repo, never()).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
    }

    @Test
    public void deleteRefusesActiveProfileChangedDuringConsent()
    {
        tool.onConsent = () -> active.set("Other");
        assertActiveChanged(invoke("deleteProfile", "Review", null));
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
    }

    @Test
    public void normalizedActiveSpellingDuringConsentKeepsTheSameSetPlan()
    {
        active.set(NFC);
        ProjectChecksTestFiles.save(folder, NFC);
        ProjectChecksTestFiles.save(folder, NFD.toUpperCase(Locale.ROOT));
        tool.onConsent = () -> active.set(NFD.toUpperCase(Locale.ROOT));
        assertTrue(ok(invoke("set", "ignored", null)).get("persisted").getAsBoolean());
        assertFalse(enabled.get());
    }

    @Test
    public void deleteCannotInterleaveBetweenSwitchValidationAndMutation() throws Exception
    {
        assertWriteWaitsForSwitch("deleteProfile");
    }

    @Test
    public void createCannotInterleaveBetweenSwitchValidationAndMutation() throws Exception
    {
        assertWriteWaitsForSwitch("createProfile");
    }

    @Test
    public void copyCannotInterleaveBetweenSwitchValidationAndMutation() throws Exception
    {
        assertWriteWaitsForSwitch("copyProfile");
    }

    @Test
    public void setCannotInterleaveBetweenSwitchValidationAndMutation() throws Exception
    {
        assertWriteWaitsForSwitch("set");
    }

    @Test
    public void anotherSwitchCannotInterleaveBetweenValidationAndMutation() throws Exception
    {
        assertWriteWaitsForSwitch("switch");
    }

    private void assertWriteWaitsForSwitch(String action) throws Exception
    {
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch competingConsent = new CountDownLatch(1);
        AtomicReference<Thread> switchThread = new AtomicReference<>();
        AtomicReference<Thread> competingThread = new AtomicReference<>();
        AtomicInteger validations = new AtomicInteger();
        when(repo.getAvailableSettingsProfiles(project)).thenAnswer(call -> {
            List<String> snapshot = List.copyOf(available);
            if (Thread.currentThread() == switchThread.get() && validations.incrementAndGet() == 2)
            {
                validated.countDown();
                assertTrue("Release switch validation", release.await(10, TimeUnit.SECONDS));
            }
            return snapshot;
        });
        tool.onConsent = () -> {
            if (Thread.currentThread() == competingThread.get())
            {
                competingConsent.countDown();
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try
        {
            Future<String> switching = workers.submit(() -> {
                switchThread.set(Thread.currentThread());
                return invoke("switch", "Review", null);
            });
            assertTrue("Switch reached its final validation", validated.await(10, TimeUnit.SECONDS));
            String target = "deleteProfile".equals(action) ? "Review"
                : "switch".equals(action) ? "Other" : "New";
            Future<String> competing = workers.submit(() -> {
                competingThread.set(Thread.currentThread());
                return invoke(action, target, "Review");
            });
            assertTrue("Competing call passed consent", competingConsent.await(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!competing.isDone() && competingThread.get().getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline)
            {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertFalse(action + " must wait for the project's final switch validation and mutation",
                competing.isDone());
            assertEquals("Contender is blocked on the project monitor", Thread.State.BLOCKED,
                competingThread.get().getState());
            verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
            verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
            verify(repo, never()).applyChanges(anyCollection(), same(project));
            release.countDown();
            assertTrue(ok(switching.get(10, TimeUnit.SECONDS)).get("persisted").getAsBoolean());
            String completed = competing.get(10, TimeUnit.SECONDS);
            assertActiveChanged(completed);
            assertEquals("Review", active.get());
            assertTrue("The switched profile was never deleted/recreated", available.contains("Review"));
        }
        finally
        {
            release.countDown();
            workers.shutdownNow();
            assertTrue("Workers finished", workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void consentDoesNotHoldTheProfileMutationLock() throws Exception
    {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try
        {
            tool.onConsent = () -> {
                tool.onConsent = () -> { };
                Future<String> copy = worker.submit(() -> invoke("copyProfile", "DuringConsent", "Review"));
                try
                {
                    ok(copy.get(10, TimeUnit.SECONDS));
                }
                catch (Exception e)
                {
                    throw new AssertionError("Another mutation must finish while consent is open", e);
                }
            };
            ok(invoke("createProfile", "AfterConsent", null));
        }
        finally
        {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void anotherProjectCanMutateWhileThisProjectsWriteIsBlocked() throws Exception
    {
        IProject other = mock(IProject.class);
        when(other.getFolder(".settings")).thenReturn(folder);
        IWorkspace workspace = project.getWorkspace();
        when(other.getWorkspace()).thenReturn(workspace);
        AtomicReference<String> otherActive = new AtomicReference<>("Default");
        when(repo.getActiveSettingsProfile(other)).thenAnswer(call -> otherActive.get());
        doAnswer(call -> { otherActive.set(call.getArgument(0)); return null; })
            .when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(other));
        when(repo.getAvailableSettingsProfiles(other)).thenReturn(List.of("Default", "Other"));
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            writing.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            active.set(call.getArgument(0));
            return null;
        }).when(repo).setActiveSettingsProfile(anyString(), anyCollection(), same(project));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try
        {
            Future<String> first = workers.submit(() -> invoke("switch", "Review", null));
            assertTrue(writing.await(10, TimeUnit.SECONDS));
            Future<?> second = workers.submit(() ->
                com.ditrix.edt.mcp.server.utils.ProjectCheckSettingsSupport.switchProfile(
                    repo, other, "Default", "Other"));
            second.get(10, TimeUnit.SECONDS);
            verify(repo).setActiveSettingsProfile("Other", List.of(), other);
            release.countDown();
            ok(first.get(10, TimeUnit.SECONDS));
        }
        finally
        {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void createRefusesCanonicalUnicodeDuplicate()
    {
        available.add(NFC);
        assertTrue(error(invoke("createProfile", NFD, null)).contains("already exists"));
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
        assertEquals(0, tool.consents.get());
    }

    @Test
    public void copyRefusesCanonicalUnicodeDuplicate()
    {
        available.add(NFC);
        assertTrue(error(invoke("copyProfile", NFD, "Review")).contains("already exists"));
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
    }

    @Test
    public void copyRechecksCanonicalUnicodeDuplicateAfterConsent()
    {
        tool.onConsent = () -> available.add(NFC);
        assertTrue(error(invoke("copyProfile", NFD, "Review")).contains("already exists"));
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
    }

    @Test
    public void copySourceAliasUsesOriginalListedSpelling()
    {
        available.add(NFC);
        JsonObject result = ok(invoke("copyProfile", "New", NFD.toUpperCase(Locale.ROOT)));
        assertEquals(NFC, result.get("from").getAsString());
        verify(repo).duplicateSettingsProfile(NFC, "New", project);
    }

    @Test
    public void switchAliasUsesOriginalListedSpelling()
    {
        available.add(NFC);
        ProjectChecksTestFiles.save(folder, NFC);
        assertTrue(ok(invoke("switch", NFD, null)).get("persisted").getAsBoolean());
        verify(repo).setActiveSettingsProfile(NFC, List.of(), project);
    }

    @Test
    public void normalizedActiveAliasIsANoOpAndMarkedActiveInProfiles()
    {
        available.add(NFC);
        active.set(NFD.toUpperCase(Locale.ROOT));
        assertFalse(ok(invoke("switch", NFC, null)).get("changed").getAsBoolean());
        JsonObject profiles = ok(invoke("profiles", "ignored", null));
        assertTrue(profiles.getAsJsonArray("profiles").asList().stream().map(row -> row.getAsJsonObject())
            .filter(row -> NFC.equals(row.get("name").getAsString())).findFirst().orElseThrow()
            .get("active").getAsBoolean());
        assertEquals(0, tool.consents.get());
    }

    @Test
    public void deleteAliasUsesOriginalListedSpelling()
    {
        available.add(NFC);
        assertEquals(NFC, ok(invoke("deleteProfile", NFD, null)).get("deletedProfile").getAsString());
        verify(repo).deleteSettingsProfile(NFC, project);
    }

    @Test
    public void deleteRefusesNormalizedActiveAlias()
    {
        available.add(NFC);
        active.set(NFD);
        assertTrue(error(invoke("deleteProfile", NFC, null)).contains("Cannot delete active profile"));
        assertEquals(0, tool.consents.get());
    }

    @Test
    public void implicitDefaultAliasUsesTheBuiltInSpelling()
    {
        active.set("Review");
        available.remove("Default");
        ok(invoke("switch", "default", null));
        verify(repo).setActiveSettingsProfile("Default", List.of(), project);
        assertEquals("Default", active.get());
    }

    @Test
    public void createRefusesDanglingSymlinkIntroducedDuringConsent() throws Exception
    {
        Path outside = temporary.getRoot().toPath().resolve("must-not-be-created.cset");
        Path staged = stageSymlink(outside);
        tool.onConsent = () -> moveLink(staged, settings.resolve("New.cset"));
        assertExisting(invoke("createProfile", "New", null));
        assertFalse(Files.exists(outside, LinkOption.NOFOLLOW_LINKS));
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
    }

    @Test
    public void copyRefusesRealSymlinkAtTheWriteBoundary() throws Exception
    {
        Path outside = temporary.newFile("outside.cset").toPath();
        Files.writeString(outside, "untouched");
        moveLink(stageSymlink(outside), settings.resolve("New.cset"));
        assertExisting(invoke("copyProfile", "New", "Review"));
        assertEquals("untouched", Files.readString(outside));
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
    }

    @Test
    public void copyRefusesSymlinkEvenWhenItsRealParentIsSettings() throws Exception
    {
        Path local = Files.writeString(settings.resolve("local.cset"), "untouched");
        moveLink(stageSymlink(local), settings.resolve("New.cset"));
        assertExisting(invoke("copyProfile", "New", "Review"));
        assertEquals("untouched", Files.readString(local));
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
    }

    @Test
    public void deleteRefusesSymlinkIntroducedDuringConsent() throws Exception
    {
        Path outside = temporary.newFile("outside.cset").toPath();
        Path staged = stageSymlink(outside);
        tool.onConsent = () -> moveLink(staged, settings.resolve("Review.cset"));
        assertUnsafe(invoke("deleteProfile", "Review", null));
        assertTrue(Files.exists(outside));
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
    }

    @Test
    public void mockedSymbolicTargetIsRefusedWithoutSymlinkPrivileges() throws Exception
    {
        tool.onConsent = mockTargetPath("New", true, settings.resolve("local.cset"));
        assertExisting(invoke("createProfile", "New", null));
        assertEquals(1, tool.consents.get());
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
    }

    @Test
    public void mockedRegularTargetWithRealSettingsParentIsRefused() throws Exception
    {
        tool.onConsent = mockTargetPath("New", false, settings.resolve("local.cset"));
        assertExisting(invoke("createProfile", "New", null));
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
    }

    @Test
    public void deleteRefusesMockedSymlinkIntroducedDuringConsent() throws Exception
    {
        tool.onConsent = mockTargetPath("Review", true, settings.resolve("local.cset"));
        assertUnsafe(invoke("deleteProfile", "Review", null));
        assertEquals(1, tool.consents.get());
        verify(repo, never()).deleteSettingsProfile(anyString(), same(project));
    }

    @Test
    public void mockedTargetWithAnotherRealParentIsRefused() throws Exception
    {
        IFile target = folder.getFile("New.cset");
        Path outside = temporary.newFile("outside.cset").toPath();
        when(target.getLocation()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(outside.toString()));
        when(folder.getFile("New.cset")).thenReturn(target);
        assertExisting(invoke("copyProfile", "New", "Review"));
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
    }

    @Test
    public void mockedJunctionResolvingOutsideSettingsIsRefused() throws Exception
    {
        tool.onConsent = mockTargetPath("New", false, temporary.newFile("junction-target.cset").toPath());
        assertExisting(invoke("createProfile", "New", null));
        verify(repo, never()).duplicateSettingsProfile(anyString(), anyString(), same(project));
    }

    private Runnable mockTargetPath(String profile, boolean symbolic, Path realPath) throws IOException
    {
        return ProjectChecksTestFiles.mockTargetPath(folder, settings, profile, symbolic, realPath);
    }

    private Path stageSymlink(Path target) throws IOException
    {
        Path staged = temporary.getRoot().toPath().resolve("staged-link");
        try
        {
            Files.createSymbolicLink(staged, target);
        }
        catch (IOException | UnsupportedOperationException | SecurityException e)
        {
            Assume.assumeNoException("Real symlink test skipped: creation is not permitted/supported: " + e, e);
        }
        assertTrue(Files.isSymbolicLink(staged));
        return staged;
    }

    private static void moveLink(Path from, Path to)
    {
        try
        {
            Files.move(from, to);
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }
    }

    private String invoke(String action, String profile, String from)
    {
        Map<String, String> params = new LinkedHashMap<>(Map.of("projectName", "TestConfiguration",
            "action", action, "profile", profile,
            "changes", "[{\"checkId\":\"sample-check|sample.contributor\",\"enabled\":false}]"));
        if (from != null)
        {
            params.put("from", from);
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
        return result.get("error").getAsString();
    }

    private static void assertActiveChanged(String text)
    {
        String error = error(text);
        assertTrue(error, error.contains("active profile changed"));
        assertTrue(error, error.contains("re-run the preview"));
    }

    private static void assertExisting(String text)
    {
        String error = error(text);
        assertTrue(error, error.contains("a file named New.cset already exists in .settings"));
        assertTrue(error, error.contains("re-run the preview"));
    }

    private static void assertUnsafe(String text)
    {
        String error = error(text);
        assertTrue(error, error.contains("links and junctions in .settings are refused"));
        assertTrue(error, error.contains("re-run the preview"));
    }

    private class TestTool extends ProjectChecksTool
    {
        final AtomicInteger consents = new AtomicInteger();
        volatile Runnable onConsent = () -> { };
        private final IProject targetProject;

        TestTool(IProject targetProject)
        {
            this.targetProject = targetProject;
        }

        @Override
        protected String buildingErrorOrNull(String name)
        {
            return null;
        }

        @Override
        protected IProject resolveProject(String name)
        {
            return targetProject;
        }

        @Override
        protected ICheckRepository checkRepository()
        {
            return repo;
        }

        @Override
        protected ConsentDecision requireConsent(ConsentPreview preview)
        {
            consents.incrementAndGet();
            onConsent.run();
            return ConsentDecision.ALLOW;
        }
    }
}
