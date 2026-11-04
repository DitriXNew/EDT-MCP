package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import com.ditrix.edt.mcp.server.ipc.*;
import com.google.gson.*;

public class EndpointRegistryTest extends StdioTestSupport
{
    @Test public void malformedRecordNeverExposesItsContentsThroughAnException() throws Exception
    {
        var registry = registry();
        EndpointRegistry.atomicWrite(registry.recordPath(), "{\"secret\":\"never-log-this-secret\",\"ipcVersion\":\"never-log-this-secret\"}");
        IOException failure = assertThrows(IOException.class, registry::read);
        assertFalse(failure.toString().contains("never-log-this-secret"));
        assertNull("Parser exceptions can quote secret-bearing input", failure.getCause());
    }

    @Test public void rejectsSocketOutsideRegistryAndRedactsRecordDiagnostics() throws Exception
    {
        var registry = registry(); var original = endpoint(registry, "owned", ProcessHandle.current().pid());
        var foreign = new EndpointRegistry.Endpoint(original.workspace(), original.generation(), original.pid(),
            original.startTime(), original.pluginVersion(), original.ipcVersion(), temporary.newFolder().toPath().resolve("socket").toString(),
            original.secret());
        assertFalse(foreign.toString().contains(original.secret()));
        EndpointRegistry.atomicWrite(registry.recordPath(), new Gson().toJson(foreign));
        assertThrows(IOException.class, registry::read);
    }

    @Test public void rejectsLinkedRecordAndDirectoryAncestors() throws Exception
    {
        var directory = temporary.newFolder().toPath(); var target = temporary.newFolder().toPath();
        var link = directory.resolve("link");
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException e) { org.junit.Assume.assumeNoException(e); }
        try
        {
            assertThrows(IOException.class, () -> new EndpointRegistry(link, temporary.getRoot().toPath()));
            assertThrows(IOException.class, () -> EndpointRegistry.privateDirectory(link.resolve("child")));
            assertFalse(Files.exists(target.resolve("child")));
        }
        finally { Files.deleteIfExists(link); }
        var registry = registry();
        var file = directory.resolve("record"); Files.writeString(file, "private-test");
        try { Files.createSymbolicLink(registry.recordPath(), file); }
        catch (IOException | UnsupportedOperationException e) { org.junit.Assume.assumeNoException(e); }
        assertThrows(IOException.class, registry::read);
    }

    @Test public void rejectsWindowsJunctionWithoutChangingItsTarget() throws Exception
    {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var parent = temporary.newFolder().toPath(); var target = temporary.newFolder().toPath();
        var link = parent.resolve("junction");
        Process process = new ProcessBuilder(Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe").toString(),
            "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        org.junit.Assume.assumeTrue(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8), process.exitValue() == 0);
        try
        {
            assertThrows(IOException.class, () -> EndpointRegistry.privateDirectory(link));
            assertThrows(IOException.class, () -> EndpointRegistry.privateDirectory(link.resolve("child")));
            assertFalse(Files.exists(target.resolve("child")));
        }
        finally { Files.deleteIfExists(link); }
    }

    @Test public void posixDirectoriesRecordsAndLocksAreOwnerOnly() throws Exception
    {
        org.junit.Assume.assumeTrue(PrivatePaths.posix(temporary.getRoot().toPath()));
        var directory = EndpointRegistry.privateDirectory(temporary.newFolder().toPath());
        var registry = new EndpointRegistry(directory, temporary.newFolder().toPath());
        registry.publish(endpoint(registry, "secure", ProcessHandle.current().pid()));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        for (Path file : List.of(registry.recordPath(), registry.lockPath()))
        { assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file)); }
        Files.setPosixFilePermissions(registry.recordPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IOException.class, registry::read);
    }

    @Test public void resolvesPlatformSpecificUserDirectoriesWithoutCwdGuessing() throws Exception
    {
        Path home = temporary.getRoot().toPath();
        assertEquals(home.resolve("runtime/edt-mcp"), EndpointRegistry.userPath("Linux", Map.of("XDG_RUNTIME_DIR", home.resolve("runtime").toString()), home));
        assertEquals(home.resolve("state/edt-mcp"), EndpointRegistry.userPath("Linux", Map.of("XDG_STATE_HOME", home.resolve("state").toString()), home));
        assertEquals(home.resolve(".local/state/edt-mcp"), EndpointRegistry.userPath("Linux", Map.of(), home));
        assertEquals(home.resolve("EDT-MCP"), EndpointRegistry.userPath("Windows", Map.of("LOCALAPPDATA", home.toString()), home));
        assertThrows(IOException.class, () -> EndpointRegistry.userPath("Windows", Map.of(), home));
    }
    @Test public void registryChecksPidAndStartAndNeverDeletesReplacement() throws Exception
    {
        var registry = registry();
        var stale = endpoint(registry, "old", Long.MAX_VALUE); registry.publish(stale);
        assertFalse(EndpointRegistry.live(registry.read()));
        EndpointRegistry.atomicWrite(registry.claimPath(), "{\"requested\":\"2026-11-04T00:00:00Z\"}");
        var replacement = endpoint(registry, "new", ProcessHandle.current().pid()); registry.publish(replacement);
        assertFalse("A ready listener clears the startup lease so a later crash can restart promptly", Files.exists(registry.claimPath()));
        assertTrue(EndpointRegistry.live(registry.read()));
        assertFalse(registry.remove("old")); assertEquals(replacement, registry.read());
        assertThrows(IOException.class, () -> registry.publish(endpoint(registry, "third", Long.MAX_VALUE)));
        assertFalse(EndpointRegistry.sameProcess(ProcessHandle.current().pid(), Instant.EPOCH.toString()));
        assertTrue(registry.remove("new")); assertNull(registry.read());
        try (var files = Files.list(registry.directory())) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }
}
