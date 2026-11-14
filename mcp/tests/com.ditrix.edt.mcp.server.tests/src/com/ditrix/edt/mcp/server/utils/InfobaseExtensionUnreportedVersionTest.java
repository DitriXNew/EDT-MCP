/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Backend;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Extension;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Request;
import com.ditrix.edt.mcp.server.utils.InfobaseExtensionLifecycle.Target;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The lifecycle over an inventory whose versions the Designer agent did not report (#916): the
 * standalone server's agent loses them, so a record carries {@code version == null}.
 */
public class InfobaseExtensionUnreportedVersionTest
{
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final Target TARGET = new Target("Project", "application");
    private static final Extension ACTIVE = new Extension("Runtime", null, true, false, false, "old");
    private static final Extension OTHER = new Extension("Other", null, false, true, true, "other");

    /** Records every flags() call and reports versions the way the standalone server's agent does. */
    private static class UnreportedVersions implements Backend
    {
        List<Extension> states = List.of();
        final List<String> calls = new ArrayList<>();
        Boolean activeWritten;

        @Override public List<Extension> read()
        {
            calls.add("read");
            return states;
        }

        @Override public void load(Path file, String name)
        {
            calls.add("load:" + name);
            var next = new ArrayList<>(states.stream().filter(e -> !e.name().equals(name)).toList());
            Extension previous = states.stream().filter(e -> e.name().equals(name)).findFirst().orElse(null);
            next.add(new Extension(name, null, previous != null && previous.active(), true, true, "new"));
            states = next;
        }

        @Override public void update(String name)
        {
            calls.add("update:" + name);
        }

        @Override public void flags(String name, boolean safe, boolean protection, Boolean active)
        {
            calls.add("flags:" + name + ":" + active);
            activeWritten = active;
            states = states.stream().map(e -> !e.name().equals(name) ? e
                : new Extension(name, e.version(), active == null ? e.active() : active, safe, protection, e.hash()))
                .toList();
        }

        @Override public void delete(String name)
        {
            calls.add("delete:" + name);
            states = states.stream().filter(e -> !e.name().equals(name)).toList();
        }
    }

    @Test public void listSaysAnUnreportedVersionWasNotReported() throws Exception
    {
        UnreportedVersions backend = new UnreportedVersions();
        backend.states = List.of(ACTIVE, OTHER);
        JsonObject result = run(new Request("list", null, null, null, null, null, false, null), backend);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals(2, result.get("count").getAsInt());
        JsonObject runtime = result.getAsJsonArray("extensions").get(1).getAsJsonObject();
        assertEquals("Runtime", runtime.get("name").getAsString());
        assertFalse(runtime.toString(), runtime.has("version"));
        assertFalse(runtime.toString(), runtime.get("versionReported").getAsBoolean());
        assertTrue(runtime.get("active").getAsBoolean());
    }

    @Test public void aReportedVersionCarriesNoVersionReportedFlag()
    {
        var details = new Extension("Runtime", "", true, false, false, "h").details();
        assertEquals("", details.get("version"));
        assertFalse(details.toString(), details.containsKey("versionReported"));
    }

    @Test public void updateKeepsTheActiveStateAndRefusesToCallAnUnreadVersionVerified() throws Exception
    {
        UnreportedVersions backend = new UnreportedVersions();
        backend.states = List.of(ACTIVE, OTHER);
        JsonObject result = run(update("2"), backend);
        assertEquals(Boolean.TRUE, backend.activeWritten);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals("notVerified", result.get("verification").getAsString());
        assertTrue(result.toString(), result.get("verificationReason").getAsString().contains("did not report"));
        assertTrue(result.toString(), result.getAsJsonObject("actualState").get("active").getAsBoolean());
        assertFalse(result.getAsJsonObject("actualState").get("versionReported").getAsBoolean());
        assertScopeLeavesOutTheVersion(result);
    }

    @Test public void installWithoutExpectedVersionNeedsNoVersionReadBack() throws Exception
    {
        UnreportedVersions backend = new UnreportedVersions();
        backend.states = List.of(OTHER);
        JsonObject result = run(install(null), backend);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("verified", result.get("verification").getAsString());
        assertFalse(result.toString(), result.has("verificationReason"));
        assertScopeLeavesOutTheVersion(result);
    }

    @Test public void updateWithoutExpectedVersionOverUnreportedVersionsClaimsNoVersionCheck() throws Exception
    {
        UnreportedVersions backend = new UnreportedVersions();
        backend.states = List.of(ACTIVE, OTHER);
        JsonObject result = run(update(null), backend);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals("notVerified", result.get("verification").getAsString());
        assertTrue(result.toString(), result.get("error").getAsString().contains("without an expectedVersion"));
        assertFalse(result.toString(), result.has("verificationReason"));
        assertScopeLeavesOutTheVersion(result);
    }

    @Test public void installWithExpectedVersionIsNotVerifiedRatherThanMismatched() throws Exception
    {
        UnreportedVersions backend = new UnreportedVersions();
        backend.states = List.of(OTHER);
        JsonObject result = run(install("2"), backend);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals("notVerified", result.get("verification").getAsString());
        assertTrue(result.toString(), result.get("verificationReason").getAsString().contains("did not report"));
        assertTrue(result.get("extensionInstalled").getAsBoolean());
        assertScopeLeavesOutTheVersion(result);
    }

    @Test public void aWrongFlagIsStillAMismatchWhenTheVersionIsUnreported() throws Exception
    {
        UnreportedVersions backend = new UnreportedVersions()
        {
            @Override public void flags(String name, boolean safe, boolean protection, Boolean active)
            {
                calls.add("flags-ignored:" + name);
            }
        };
        backend.states = List.of(ACTIVE, OTHER);
        JsonObject result = run(new Request("update", "Runtime", cfe(), false, false, "2", true, null), backend);
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals("mismatched", result.get("verification").getAsString());
    }

    @Test public void outputSchemaDeclaresTheNullVersionAndTheReadBackReason()
    {
        JsonObject properties = JsonParser.parseString(
            new com.ditrix.edt.mcp.server.tools.impl.InfobaseExtensionsTool().getOutputSchema())
            .getAsJsonObject().getAsJsonObject("properties");
        assertTrue(properties.getAsJsonObject("extensions").get("description").getAsString()
            .contains("versionReported:false replaces version when the Designer agent did not report it"));
        assertEquals("Why read-back could not verify the operation.",
            properties.getAsJsonObject("verificationReason").get("description").getAsString());
    }

    /** The scope names only the checks that ran: nothing about expectedVersion was proved. */
    private static void assertScopeLeavesOutTheVersion(JsonObject result)
    {
        String scope = result.get("verificationScope").getAsString();
        assertFalse(scope, scope.contains("expectedVersion when supplied"));
        assertFalse(scope, scope.contains("require an observed transition"));
        assertTrue(scope, scope.contains("no version check"));
        assertTrue(scope, scope.contains("Presence, runtime flags, other extensions and the preserved active state on update"));
    }

    @Test public void aReportedVersionKeepsTheFullScope() throws Exception
    {
        UnreportedVersions backend = new UnreportedVersions()
        {
            @Override public void load(Path file, String name)
            {
                super.load(file, name);
                states = states.stream().map(e -> !e.name().equals(name) ? e
                    : new Extension(name, "2", e.active(), e.safeMode(), e.protectionFromDangerousActions(), e.hash()))
                    .toList();
            }
        };
        backend.states = List.of(OTHER);
        JsonObject result = run(install("2"), backend);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals(InfobaseExtensionLifecycle.SCOPE, result.get("verificationScope").getAsString());
    }
    @Test public void theGuideMakesTheVersionConditional()
    {
        GuideLoader.clearCache();
        String guide = GuideLoader.load("infobase_extensions").replaceAll("\\s+", " ");
        assertFalse(guide, guide.contains("every record contains the actual name, version,"));
        assertTrue(guide, guide.contains("and version, or `versionReported:false` when the agent did not report it"));
    }
    /** The agent did not report the version before the write but reports it afterwards. */
    private static UnreportedVersions reportingAfterLoad(String version)
    {
        UnreportedVersions backend = new UnreportedVersions()
        {
            @Override public void load(Path file, String name)
            {
                super.load(file, name);
                states = states.stream().map(e -> !e.name().equals(name) ? e
                    : new Extension(name, version, e.active(), e.safeMode(), e.protectionFromDangerousActions(), e.hash()))
                    .toList();
            }
        };
        backend.states = List.of(ACTIVE, OTHER);
        return backend;
    }

    @Test public void aReportedVersionDifferentFromExpectedIsAMismatchEvenWhenThePreviousWasUnreported() throws Exception
    {
        JsonObject result = run(update("2"), reportingAfterLoad("3"));
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals("mismatched", result.get("verification").getAsString());
        assertEquals("3", result.getAsJsonObject("actualState").get("version").getAsString());
        assertFalse(result.toString(), result.has("verificationReason"));
    }

    @Test public void reachingExpectedVersionFromAnUnreportedOneLacksOnlyTheTransition() throws Exception
    {
        JsonObject result = run(update("2"), reportingAfterLoad("2"));
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertEquals("notVerified", result.get("verification").getAsString());
        assertEquals(InfobaseExtensionLifecycle.TRANSITION_UNREPORTED, result.get("verificationReason").getAsString());
        assertEquals(InfobaseExtensionLifecycle.SCOPE_WITHOUT_TRANSITION, result.get("verificationScope").getAsString());
    }

    @Test public void anUninstallOfAnUnreportedVersionUsesTheUninstallScope() throws Exception
    {
        UnreportedVersions backend = new UnreportedVersions();
        backend.states = List.of(ACTIVE, OTHER);
        JsonObject result = run(new Request("uninstall", "Runtime", null, null, null, null, true, null), backend);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals("verified", result.get("verification").getAsString());
        assertEquals(InfobaseExtensionLifecycle.SCOPE_UNINSTALL, result.get("verificationScope").getAsString());
    }

    @Test public void anUninstallOfAReportedVersionUsesTheUninstallScopeToo() throws Exception
    {
        UnreportedVersions backend = new UnreportedVersions();
        backend.states = List.of(new Extension("Runtime", "1", true, false, false, "old"), OTHER);
        JsonObject result = run(new Request("uninstall", "Runtime", null, null, null, null, true, null), backend);
        assertTrue(result.toString(), result.get("success").getAsBoolean());
        assertEquals(InfobaseExtensionLifecycle.SCOPE_UNINSTALL, result.get("verificationScope").getAsString());
    }

    @Test public void everyScopeNamesOnlyTheChecksOfItsBranch()
    {
        String uninstall = InfobaseExtensionLifecycle.SCOPE_UNINSTALL;
        for (String claim : List.of("runtime flags", "active state", "expectedVersion", "transition"))
        {
            assertFalse(uninstall, uninstall.contains(claim));
        }
        assertTrue(uninstall, uninstall.contains("resulting absence"));
        // Install has no active state to preserve: the install/update scopes say "on update".
        for (String scope : List.of(InfobaseExtensionLifecycle.SCOPE, InfobaseExtensionLifecycle.SCOPE_WITHOUT_VERSION))
        {
            assertTrue(scope, scope.contains("preserved active state on update"));
        }
        assertTrue(InfobaseExtensionLifecycle.SCOPE.contains("an update additionally requires an observed transition"));
        // This scope is published for install and update, with and without expectedVersion: it states only
        // what holds in every one of those branches - no version was readable - and never that a requested
        // check was skipped (#925).
        assertTrue(InfobaseExtensionLifecycle.SCOPE_WITHOUT_VERSION.contains(
            "did not report the extension version, so no version check (expectedVersion or an update transition) "
            + "was possible"));
        assertFalse(InfobaseExtensionLifecycle.SCOPE_WITHOUT_VERSION.contains("were not checked"));
        // Selected for an update with or without expectedVersion: it says what was impossible, not what was skipped.
        assertTrue(InfobaseExtensionLifecycle.SCOPE_WITHOUT_TRANSITION.contains(
            "did not report the previous version, so no update transition could be observed"));
        assertFalse(InfobaseExtensionLifecycle.SCOPE_WITHOUT_TRANSITION.contains("was not checked"));
    }
    private Request update(String expectedVersion) throws Exception
    {
        return new Request("update", "Runtime", cfe(), null, null, expectedVersion, true, null);
    }

    private Request install(String expectedVersion) throws Exception
    {
        return new Request("install", "Runtime", cfe(), null, null, expectedVersion, true, null);
    }

    private Path cfe() throws Exception
    {
        Path file = folder.newFile("input" + System.nanoTime() + ".cfe").toPath();
        Files.writeString(file, "CFE fixture");
        return file;
    }

    private JsonObject run(Request request, UnreportedVersions backend) throws Exception
    {
        if (request.confirm())
        {
            Request preview = new Request(request.action(), request.extensionName(), request.cfePath(),
                request.safeMode(), request.protectionFromDangerousActions(), request.expectedVersion(), false, null);
            JsonObject previewed = execute(preview, backend);
            assertTrue(previewed.toString(), previewed.get("success").getAsBoolean());
            request = new Request(request.action(), request.extensionName(), request.cfePath(), request.safeMode(),
                request.protectionFromDangerousActions(), request.expectedVersion(), true,
                previewed.get("contentHash").getAsString());
        }
        return execute(request, backend);
    }

    private JsonObject execute(Request request, UnreportedVersions backend) throws Exception
    {
        return JsonParser.parseString(InfobaseExtensionLifecycle.execute(request, TARGET, backend,
            preview -> true, () -> {}, () -> {},
            () -> com.ditrix.edt.mcp.server.ipc.PrivatePaths.directory(folder.getRoot().toPath().resolve("staging")))
            .toJson()).getAsJsonObject();
    }
}
