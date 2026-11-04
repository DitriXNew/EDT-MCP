/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Backend;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Extension;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Request;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Target;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class InfobaseExtensionLifecycleTest
{
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final Extension OLD = new Extension("Runtime", "1", false, false, false, "old");
    private static final Extension OTHER = new Extension("Other", "7", true, true, true, "other");
    private static final Target TARGET = new Target("Project", "application");

    private static class Fake implements Backend
    {
        List<Extension> states = List.of();
        final List<String> calls = new ArrayList<>();
        boolean ignoreLoad;
        boolean ignoreFlags;
        boolean ignoreDelete;
        boolean breakOthers;
        int reads;
        Path loadedPath;
        String loadedContent;
        boolean failUpdate;
        boolean failReadAfterWrite;

        @Override public List<Extension> read()
        {
            calls.add("read");
            reads++;
            if (failReadAfterWrite && loadedPath != null)
            {
                throw new IllegalStateException("read-back connection was lost");
            }
            return states;
        }

        @Override public void load(Path file, String name) throws Exception
        {
            calls.add("load:" + name);
            loadedPath = file;
            loadedContent = Files.readString(file);
            if (!ignoreLoad)
            {
                var next = new ArrayList<>(states.stream().filter(e -> !e.name().equals(name)).toList());
                next.add(new Extension(name, "2", true, true, true, "new"));
                states = next;
            }
        }

        @Override public void update(String name)
        {
            calls.add("update:" + name);
            if (failUpdate)
            {
                throw new IllegalStateException("Infobase exclusive lock denied");
            }
            if (breakOthers)
            {
                states = states.stream().filter(e -> !e.name().equals("Other")).toList();
            }
        }

        @Override public void flags(String name, boolean safe, boolean protection, Boolean active)
        {
            calls.add("flags:" + name);
            if (!ignoreFlags)
            {
                states = states.stream().map(e -> !e.name().equals(name) ? e
                    : new Extension(name, e.version(), active == null ? e.active() : active,
                        safe, protection, e.hash())).toList();
            }
        }

        @Override public void delete(String name)
        {
            calls.add("delete:" + name);
            if (!ignoreDelete)
            {
                states = states.stream().filter(e -> !e.name().equals(name)).toList();
            }
        }
    }

    private Request request(String action, boolean confirm) throws Exception
    {
        Path file = folder.newFile("input" + System.nanoTime() + ".cfe").toPath();
        Files.writeString(file, "CFE fixture");
        return new Request(action, "Runtime", action.equals("uninstall") ? null : file, null, null, "uninstall".equals(action) ? null : "2", confirm, null);
    }

    private JsonObject run(Request request, Fake backend) throws Exception
    {
        if (request.confirm() && request.expectedHash() == null)
        {
            JsonObject preview = run(withExpectedHash(request, null, false), backend);
            if (!preview.get("success").getAsBoolean())
            {
                return preview;
            }
            backend.calls.clear();
            backend.reads = 0;
            request = withExpectedHash(request, preview.get("contentHash").getAsString(), true);
        }
        return json(execute(request, backend, preview -> true, () -> {}, () -> {}));
    }

    private static Request withExpectedHash(Request request, String expectedHash, boolean confirm)
    {
        return new Request(request.action(), request.extensionName(), request.cfePath(), request.safeMode(),
            request.protectionFromDangerousActions(), request.expectedVersion(), confirm, expectedHash);
    }

    private ToolResult execute(Request request, Fake backend, Predicate<ConsentPreview> consent,
        InfobaseExtensionLifecycle.WriteGuard guard, Runnable mutationEntered) throws Exception
    {
        return InfobaseExtensionLifecycle.execute(request, TARGET, backend, consent, guard, mutationEntered, this::stagingParent);
    }

    private Path stagingParent() throws java.io.IOException
    {
        return com.ditrix.edt.mcp.server.ipc.PrivatePaths.directory(folder.getRoot().toPath().resolve("staging"));
    }

    private Request previewed(Request request, Fake backend) throws Exception
    {
        JsonObject preview = run(withExpectedHash(request, null, false), backend);
        assertTrue(preview.toString(), preview.get("success").getAsBoolean());
        // Existing call-order and read-count assertions cover the confirmed invocation.
        backend.calls.clear();
        backend.reads = 0;
        return withExpectedHash(request, preview.get("contentHash").getAsString(), true);
    }

    private static JsonObject json(ToolResult result)
    {
        return JsonParser.parseString(result.toJson()).getAsJsonObject();
    }

    @Test public void stagedArtifactHasAPrivateParentAndCleansItAfterSuccess() throws Exception
    {
        assertPrivateStaging(false);
    }

    @Test public void stagedArtifactHasAPrivateParentAndCleansItAfterFailure() throws Exception
    {
        assertPrivateStaging(true);
    }

    private void assertPrivateStaging(boolean failUpdate) throws Exception
    {
        Fake backend = new Fake()
        {
            @Override public void load(Path file, String name) throws Exception
            {
                assertNotEquals("The staged artifact must not be a direct child of the shared temp directory",
                    Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(), file.getParent());
                assertEquals(stagingParent(), file.getParent().getParent());
                com.ditrix.edt.mcp.server.ipc.PrivatePaths.verifyDirectory(file.getParent());
                super.load(file, name);
            }
        };
        backend.failUpdate = failUpdate;
        JsonObject result = run(request("install", true), backend);
        assertEquals(result.toString(), !failUpdate, result.get("success").getAsBoolean());
        assertNotNull(backend.loadedPath);
        assertFalse(Files.exists(backend.loadedPath));
        assertFalse(Files.exists(backend.loadedPath.getParent()));
    }

    @Test public void listReadsActualFlagsAndEmptyInventory() throws Exception
    {
        Request list = new Request("list", null, null, null, null, null, false, null);
        Fake backend = new Fake();
        assertEquals(0, run(list, backend).get("count").getAsInt());
        backend.states = List.of(OLD);
        JsonObject state = run(list, backend).getAsJsonArray("extensions").get(0).getAsJsonObject();
        assertEquals("1", state.get("version").getAsString());
        assertFalse(state.get("active").getAsBoolean());
        assertFalse(state.get("safeMode").getAsBoolean());
        assertFalse(state.get("protectionFromDangerousActions").getAsBoolean());
        assertEquals(List.of("read", "read"), backend.calls);
    }

    @Test public void installPreviewDoesNotAskConsentOrWrite() throws Exception
    {
        Fake backend = new Fake();
        JsonObject result = json(execute(request("install", false), backend,
            preview -> { fail("preview asked consent"); return false; }, () -> fail("preview guarded a write"),
            () -> fail("preview entered a mutation")));
        assertTrue(result.get("requiresConfirmation").getAsBoolean());
        assertTrue(result.get("requestedSafeMode").getAsBoolean());
        assertTrue(result.get("requestedProtectionFromDangerousActions").getAsBoolean());
        assertFalse(result.get("contentHash").getAsString().isBlank());
        assertEquals(List.of("read"), backend.calls);
    }

    @Test public void installVerifiesActualVersionAndSafeDefaults() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OTHER);
        JsonObject result = run(request("install", true), backend);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("verified", result.get("verification").getAsString());
        assertEquals("2", result.getAsJsonObject("actualState").get("version").getAsString());
        assertEquals(List.of("read", "read", "load:Runtime", "flags:Runtime", "update:Runtime", "read"), backend.calls);
        assertTrue(result.get("extensionInstalled").getAsBoolean());
    }

    @Test public void successfulLoadWithoutAnInstalledExtensionIsFailure() throws Exception
    {
        Fake backend = new Fake();
        backend.ignoreLoad = true;
        JsonObject result = run(request("install", true), backend);
        assertFalse(result.get("success").getAsBoolean());
        assertFalse(result.get("extensionInstalled").getAsBoolean());
        assertEquals("mismatched", result.get("verification").getAsString());
    }

    @Test public void updatePreservesFalseFlagsAndDisabledState() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OLD, OTHER);
        JsonObject result = run(request("update", true), backend);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        JsonObject actual = result.getAsJsonObject("actualState");
        assertEquals("2", actual.get("version").getAsString());
        assertFalse(actual.get("safeMode").getAsBoolean());
        assertFalse(actual.get("active").getAsBoolean());
        assertFalse(actual.get("protectionFromDangerousActions").getAsBoolean());
    }

    @Test public void explicitFlagChangesAreVerified() throws Exception
    {
        Fake backend = new Fake();
        Request original = request("install", true);
        Request request = new Request("install", "Runtime", original.cfePath(), false, false, "2", true, null);
        assertTrue(run(request, backend).get("success").getAsBoolean());
        backend = new Fake();
        backend.ignoreFlags = true;
        assertFalse(run(request, backend).get("success").getAsBoolean());
    }

    @Test public void oldVersionIsNotReportedAsVerifiedUpdate() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OLD);
        backend.ignoreLoad = true;
        assertFalse(run(request("update", true), backend).get("success").getAsBoolean());
    }

    @Test public void noOpUpdateWithoutExpectedVersionIsNotVerified() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OLD);
        backend.ignoreLoad = true;
        Request original = request("update", true);
        Request update = new Request("update", "Runtime", original.cfePath(), null, null, null, true, null);
        JsonObject result = run(update, backend);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals("notVerified", result.get("verification").getAsString());
        assertEquals(OLD.version(), result.getAsJsonObject("actualState").get("version").getAsString());
        assertEquals(OLD.hash(), result.getAsJsonObject("actualState").get("hash").getAsString());
    }

    @Test public void changedInventoryHashAloneDoesNotProveTheUploadedArtifact() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OLD);
        Request original = request("update", true);
        JsonObject result = run(new Request("update", "Runtime", original.cfePath(), null, null, null, true, null), backend);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals("notVerified", result.get("verification").getAsString());
        assertEquals("new", result.getAsJsonObject("actualState").get("hash").getAsString());
    }

    @Test public void unchangedExpectedVersionDoesNotProveAnUpdate() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OLD);
        backend.ignoreLoad = true;
        Request original = request("update", true);
        JsonObject result = run(new Request("update", "Runtime", original.cfePath(), null, null, "1", true, null), backend);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals("notVerified", result.get("verification").getAsString());
    }

    @Test public void replacedCfeSincePreviewIsRejectedBeforeConsentOrWrite() throws Exception
    {
        Fake backend = new Fake();
        Request original = request("install", false);
        JsonObject preview = run(original, backend);
        assertTrue(preview.get("requiresConfirmation").getAsBoolean());
        Files.writeString(original.cfePath(), "replacement CFE");
        Request confirm = withExpectedHash(original, preview.get("contentHash").getAsString(), true);
        JsonObject result = json(execute(confirm, backend, consent -> {
            fail("stale preview asked consent"); return false;
        }, () -> fail("stale preview guarded a write"), () -> fail("stale preview entered mutation")));
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertNull("stale preview must not load the replacement", backend.loadedPath);
        JsonObject fresh = run(withExpectedHash(original, null, true), backend);
        assertTrue(fresh.toString(), fresh.get("success").getAsBoolean());
        assertEquals("replacement CFE", backend.loadedContent);
    }

    @Test public void replacedInstalledExtensionSincePreviewIsNotDeleted() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OLD);
        Request confirm = previewed(request("uninstall", true), backend);
        Extension replacement = new Extension("Runtime", "9", true, true, true, "replacement");
        backend.states = List.of(replacement);
        JsonObject result = run(confirm, backend);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals(List.of(replacement), backend.states);
    }

    @Test public void contentHashBindsEffectiveFlagsExpectedVersionPathNameAndTarget() throws Exception
    {
        Fake backend = new Fake();
        Request original = previewed(request("install", true), backend);
        Path otherFile = folder.newFile("other.cfe").toPath();
        Files.writeString(otherFile, "CFE fixture");
        for (Request changed : List.of(
            new Request("install", "Runtime", original.cfePath(), false, null, "2", true, original.expectedHash()),
            new Request("install", "Runtime", original.cfePath(), null, false, "2", true, original.expectedHash()),
            new Request("install", "Runtime", original.cfePath(), null, null, "3", true, original.expectedHash()),
            new Request("install", "Runtime", otherFile, null, null, "2", true, original.expectedHash()),
            new Request("install", "Different", original.cfePath(), null, null, "2", true, original.expectedHash())))
        {
            JsonObject result = json(execute(changed, backend, consent -> {
                fail("changed parameters asked consent"); return false;
            }, () -> fail("changed parameters guarded a write"), () -> fail("changed parameters entered mutation")));
            assertFalse(result.toString(), result.get("success").getAsBoolean());
            assertTrue(result.get("error").getAsString().contains("fresh preview"));
        }
        for (Target changed : List.of(new Target("OtherProject", TARGET.applicationId()),
            new Target(TARGET.projectName(), "otherApplication")))
        {
            JsonObject result = json(InfobaseExtensionLifecycle.execute(original, changed, backend,
                consent -> { fail("changed target asked consent"); return false; },
                () -> fail("changed target guarded a write"), () -> fail("changed target entered mutation"), this::stagingParent));
            assertFalse(result.toString(), result.get("success").getAsBoolean());
        }
        // Effective true defaults and explicit true are the same approved operation.
        JsonObject result = run(new Request("install", "Runtime", original.cfePath(), true, true, "2", true,
            original.expectedHash()), backend);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
    }

    @Test public void changedNeighborSincePreviewIsRejected() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OLD, OTHER);
        Request confirm = previewed(request("uninstall", true), backend);
        backend.states = List.of(OLD, new Extension("Other", "8", true, true, true, "other-new"));
        JsonObject result = run(confirm, backend);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals(List.of("read"), backend.calls);
        assertEquals(2, backend.states.size());
    }

    @Test public void contentHashBindsTheAction() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OLD);
        Request uninstall = previewed(request("uninstall", true), backend);
        Request update = request("update", true);
        JsonObject result = run(withExpectedHash(update, uninstall.expectedHash(), true), backend);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals(List.of("read"), backend.calls);
        assertEquals(List.of(OLD), backend.states);
    }

    @Test public void previewHashIsStableAndConfirmationUsesSharedNormalization() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OLD, OTHER);
        Request original = request("uninstall", false);
        String hash = run(original, backend).get("contentHash").getAsString();
        backend.states = List.of(OTHER, OLD);
        assertEquals(hash, run(original, backend).get("contentHash").getAsString());
        String copiedHash = " \"" + hash.toUpperCase(Locale.ROOT) + "\" ";
        JsonObject result = run(withExpectedHash(original, copiedHash, true), backend);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals(List.of(OTHER), backend.states);
    }

    @Test public void missingOrMismatchedExpectedHashCannotConfirmAWrite() throws Exception
    {
        Fake backend = new Fake();
        Request original = request("install", true);
        for (String expectedHash : new String[] {null, "wrong-hash"})
        {
            JsonObject result = json(execute(withExpectedHash(original, expectedHash, true), backend,
                consent -> { fail("unapproved preview asked consent"); return false; },
                () -> fail("unapproved write"), () -> fail("unapproved mutation")));
            assertFalse(result.toString(), result.get("success").getAsBoolean());
            assertNull(backend.loadedPath);
        }
    }

    @Test public void uninstallVerifiesAbsence() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OLD, OTHER);
        JsonObject result = run(request("uninstall", true), backend);
        assertTrue(result.get("success").getAsBoolean());
        assertFalse(result.get("extensionInstalled").getAsBoolean());
        assertEquals(List.of(OTHER), backend.states);
    }

    @Test public void noOpUninstallFailsVerification() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OLD);
        backend.ignoreDelete = true;
        assertFalse(run(request("uninstall", true), backend).get("success").getAsBoolean());
    }

    @Test public void installNeverOverwritesAndUpdateNeverCreates() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OLD);
        assertFalse(run(withExpectedHash(request("install", true), "unused", true), backend).get("success").getAsBoolean());
        backend.states = List.of();
        assertFalse(run(withExpectedHash(request("update", true), "unused", true), backend).get("success").getAsBoolean());
        assertFalse(run(withExpectedHash(request("uninstall", true), "unused", true), backend).get("success").getAsBoolean());
        assertEquals(List.of("read", "read", "read"), backend.calls);
    }

    @Test public void rejectedConsentNeverWrites() throws Exception
    {
        Fake backend = new Fake();
        JsonObject result = json(execute(previewed(request("install", true), backend), backend,
            preview -> false, () -> fail("rejected write"), () -> fail("rejected write")));
        assertFalse(result.get("success").getAsBoolean());
        assertEquals(List.of("read"), backend.calls);
    }

    @Test public void changedInventoryDuringConsentRequiresFreshPreview() throws Exception
    {
        Fake backend = new Fake();
        JsonObject result = json(execute(previewed(request("install", true), backend), backend,
            preview -> { backend.states = List.of(OLD); return true; }, () -> {}, () -> fail("stale write")));
        assertFalse(result.get("success").getAsBoolean());
        assertEquals(List.of("read", "read"), backend.calls);
    }

    @Test public void changedInventoryDuringAdmissionIsRejectedBeforeMutation() throws Exception
    {
        Extension replacement = new Extension("Runtime", "9", true, true, true, "replacement");
        for (String action : List.of("uninstall", "install", "update"))
        {
            Fake backend = new Fake();
            backend.states = "install".equals(action) ? List.of(OTHER) : List.of(OLD, OTHER);
            Request confirm = previewed(request(action, true), backend);
            AtomicInteger mutations = new AtomicInteger();
            JsonObject result = json(execute(confirm, backend, preview -> true, () -> {
                backend.calls.add("guard");
                backend.states = List.of(replacement, OTHER);
            }, mutations::incrementAndGet));
            assertFalse(action + ": " + result, result.get("success").getAsBoolean());
            assertTrue(result.get("error").getAsString().contains("fresh preview"));
            assertEquals(0, mutations.get());
            assertEquals(List.of(replacement, OTHER), backend.states);
            assertEquals(List.of("read", "guard", "read"), backend.calls);
        }
    }

    @Test public void stagingPreservesPrivateFileIdentityAndPermissions() throws Exception
    {
        Path source = request("install", false).cfePath();
        Path staged = Files.createTempFile(folder.getRoot().toPath(), "private-", ".cfe");
        Path alias = staged.resolveSibling(staged.getFileName() + ".alias");
        Files.createLink(alias, staged);
        boolean posix = Files.getFileAttributeView(staged, PosixFileAttributeView.class) != null;
        if (posix)
        {
            Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-rw-rw-"));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(staged));
        }
        InfobaseExtensionLifecycle.stageCfe(source, staged);
        assertTrue("Staging replaced the private file instead of writing into it", Files.isSameFile(alias, staged));
        assertEquals("CFE fixture", Files.readString(alias));
        if (posix)
        {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(staged));
        }
    }

    @Test public void changedOtherExtensionFailsVerification() throws Exception
    {
        Fake backend = new Fake();
        backend.states = List.of(OTHER);
        backend.breakOthers = true;
        assertFalse(run(request("install", true), backend).get("success").getAsBoolean());
    }

    @Test public void cancellationBetweenStepsPreventsRemainingWrites() throws Exception
    {
        Fake backend = new Fake();
        AtomicInteger guards = new AtomicInteger();
        JsonObject result = json(execute(previewed(request("install", true), backend), backend, preview -> true,
            () -> { if (guards.incrementAndGet() == 2) throw new IllegalStateException("cancelled"); }, () -> {}));
        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("cancelled"));
        assertTrue(result.get("mutationOutcomeUnknown").getAsBoolean());
        assertEquals(List.of("read", "read", "load:Runtime", "read"), backend.calls);
    }

    @Test public void freezesCfeBeforeConsentAndCleansUpItsCopy() throws Exception
    {
        Fake backend = new Fake();
        Request request = previewed(request("install", true), backend);
        JsonObject result = json(execute(request, backend, preview -> {
            assertTrue(preview.getSubtitle().contains("safeMode=true"));
            assertTrue(preview.getSubtitle().contains("protectionFromDangerousActions=true"));
            try { Files.writeString(request.cfePath(), "changed during consent"); }
            catch (Exception e) { throw new IllegalStateException(e); }
            return true;
        }, () -> {}, () -> {}));
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("CFE fixture", backend.loadedContent);
        assertNotEquals(request.cfePath(), backend.loadedPath);
        assertFalse(Files.exists(backend.loadedPath));
    }

    @Test public void stagedBytesChangedAfterConsentAreRejectedBeforeLoad() throws Exception
    {
        for (String action : List.of("install", "update"))
        {
            Fake backend = new Fake();
            backend.states = "install".equals(action) ? List.of(OTHER) : List.of(OLD, OTHER);
            List<Extension> before = backend.states;
            Request original = request(action, true);
            String content = "CFE-" + UUID.randomUUID();
            Files.writeString(original.cfePath(), content);
            Request confirm = previewed(original, backend);
            AtomicInteger guards = new AtomicInteger();
            AtomicInteger mutations = new AtomicInteger();
            AtomicReference<Path> tampered = new AtomicReference<>();
            JsonObject result = json(execute(confirm, backend, preview -> true, () -> {
                if (guards.incrementAndGet() == 1)
                {
                    Path staged = stagedCopyContaining(content);
                    Files.writeString(staged, "changed after consent");
                    tampered.set(staged);
                }
            }, mutations::incrementAndGet));
            assertFalse(action + ": " + result + "; loaded bytes=" + backend.loadedContent,
                result.get("success").getAsBoolean());
            assertTrue(result.get("error").getAsString().contains("Staged .cfe bytes changed"));
            assertTrue(result.get("error").getAsString().contains("nothing was changed"));
            assertFalse(result.has("mutationOutcomeUnknown"));
            assertEquals(0, mutations.get());
            assertNull(backend.loadedPath);
            assertEquals(before, backend.states);
            assertEquals(List.of("read", "read"), backend.calls);
            assertNotNull(tampered.get());
            assertFalse(Files.exists(tampered.get()));
        }
    }

    private Path stagedCopyContaining(String content) throws Exception
    {
        // Match this invocation's unique payload; never alter another worker's staged file.
        try (var candidates = Files.newDirectoryStream(stagingParent(),
            "edt-mcp-extension-*"))
        {
            for (Path directory : candidates)
            {
                if (!Files.isDirectory(directory)) continue;
                try (var files = Files.newDirectoryStream(directory, "*.cfe"))
                {
                    for (Path candidate : files)
                    {
                        if (Files.isReadable(candidate) && Files.size(candidate) == content.length()
                            && Files.readString(candidate).equals(content))
                        {
                            return candidate;
                        }
                    }
                }
                catch (java.nio.file.NoSuchFileException e)
                {
                    // Another worker finished while the temporary directory was being scanned.
                }
            }
        }
        throw new AssertionError("This invocation's staged .cfe was not found");
    }

    @Test public void platformFailureRetainsItsCauseAndRereadsActualState() throws Exception
    {
        Fake backend = new Fake();
        backend.failUpdate = true;
        JsonObject result = run(request("install", true), backend);
        assertFalse(result.get("success").getAsBoolean());
        assertTrue(result.get("error").getAsString().contains("Infobase exclusive lock denied"));
        assertEquals("notVerified", result.get("verification").getAsString());
        assertTrue(result.get("extensionInstalled").getAsBoolean());
        assertTrue(result.get("mutationOutcomeUnknown").getAsBoolean());
        assertEquals(3, backend.reads);
    }

    @Test public void unreadablePostStateNeverBecomesAbsenceOrSuccess() throws Exception
    {
        Fake backend = new Fake();
        backend.failReadAfterWrite = true;
        JsonObject result = run(request("install", true), backend);
        assertFalse(result.get("success").getAsBoolean());
        assertFalse(result.has("extensionInstalled"));
        assertFalse(result.has("actualState"));
        assertTrue(result.get("verificationReason").getAsString().contains("read-back connection was lost"));
    }

    @Test public void validatesFilesAndActionCombinations() throws Exception
    {
        assertNull(InfobaseExtensionLifecycle.validate(request("install", false)));
        assertNotNull(InfobaseExtensionLifecycle.validate(new Request("list", "Runtime", null, null, null, null, false, null)));
        assertNotNull(InfobaseExtensionLifecycle.validate(new Request("install", "Runtime", Path.of("relative.cfe"), null, null, null, false, null)));
        assertNotNull(InfobaseExtensionLifecycle.validate(new Request("uninstall", null, null, null, null, null, true, null)));
        assertNotNull(InfobaseExtensionLifecycle.validate(new Request("install", "Runtime", folder.newFile("wrong.cf").toPath(), null, null, null, true, null)));
        assertNotNull(InfobaseExtensionLifecycle.validate(new Request("install", "Runtime", folder.newFile("empty.cfe").toPath(), null, null, null, true, null)));
    }

    @Test public void acceptsEnglishAndRussianProgrammaticNamesButNotSynonyms() throws Exception
    {
        Request original = request("install", false);
        for (String name : List.of("Runtime", "\u0420\u0430\u0441\u0448\u0438\u0440\u0435\u043d\u0438\u0435"))
        {
            assertNull(InfobaseExtensionLifecycle.validate(new Request("install", name, original.cfePath(), null, null, null, false, null)));
        }
        assertNotNull(InfobaseExtensionLifecycle.validate(new Request("install", "Friendly extension", original.cfePath(), null, null, null, false, null)));
    }
}
