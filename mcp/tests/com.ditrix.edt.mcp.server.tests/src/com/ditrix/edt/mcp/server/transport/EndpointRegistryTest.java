package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import com.ditrix.edt.mcp.server.ipc.*;
import com.google.gson.*;

public class EndpointRegistryTest extends StdioTestSupport
{
    private static Map<String, UserPrincipal> windowsAccounts;
    private static final UserPrincipal ACCOUNT = () -> "ordinary-user";
    private static final UserPrincipal ADMINISTRATORS = () -> "BUILTIN\\Administrators";
    private static final UserPrincipal SYSTEM = () -> "NT AUTHORITY\\SYSTEM";
    private static final UserPrincipal OTHER_ACCOUNT = () -> "another-ordinary-user";
    private static final Set<UserPrincipal> PRIVILEGED = Set.of(ADMINISTRATORS, SYSTEM);

    @Test public void ownershipAcceptsTheCurrentAccount()
    {
        assertTrue(PrivatePaths.isTrustedOwner(ACCOUNT, ACCOUNT, Set.of()));
        assertTrue(PrivatePaths.isTrustedOwner(ACCOUNT, ACCOUNT, PRIVILEGED));
    }

    @Test public void windowsOwnershipAcceptsAdministrators()
    { assertTrue(PrivatePaths.isTrustedOwner(ADMINISTRATORS, ACCOUNT, PRIVILEGED)); }

    @Test public void windowsOwnershipAcceptsSystem()
    { assertTrue(PrivatePaths.isTrustedOwner(SYSTEM, ACCOUNT, PRIVILEGED)); }

    @Test public void ownershipRejectsAnotherOrdinaryAccount()
    { assertFalse(PrivatePaths.isTrustedOwner(OTHER_ACCOUNT, ACCOUNT, PRIVILEGED)); }

    @Test public void posixOwnershipRejectsPrivilegedLookingNames()
    {
        assertFalse(PrivatePaths.isTrustedOwner(ADMINISTRATORS, ACCOUNT, Set.of()));
        assertFalse(PrivatePaths.isTrustedOwner(SYSTEM, ACCOUNT, Set.of()));
    }

    private static String windowsScript(String body) throws Exception
    {
        String script = "$ErrorActionPreference='Stop';[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);" + body;
        Process process = new ProcessBuilder(Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
            "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))).redirectErrorStream(true).start();
        try
        {
            assertTrue("Windows ACL fixture command must finish", process.waitFor(5, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(output, 0, process.exitValue());
            return output;
        }
        finally { if (process.isAlive()) { process.destroyForcibly(); } }
    }

    static synchronized Map<String, UserPrincipal> windowsAccounts() throws Exception
    {
        if (windowsAccounts != null) { return windowsAccounts; }
        String[] names = windowsScript("'S-1-5-18','S-1-5-32-544','S-1-1-0' | ForEach-Object {"
            + "([System.Security.Principal.SecurityIdentifier]::new($_)).Translate([System.Security.Principal.NTAccount]).Value}").split("\\R");
        assertEquals(3, names.length);
        var lookup = FileSystems.getDefault().getUserPrincipalLookupService();
        windowsAccounts = Map.of("system", lookup.lookupPrincipalByName(names[0]),
            "administrators", lookup.lookupPrincipalByName(names[1]), "everyone", lookup.lookupPrincipalByName(names[2]));
        return windowsAccounts;
    }

    @Test public void windowsRejectsAnUnspecifiedDaclRatherThanTreatingItAsPrivate() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var file = temporary.newFile().toPath(); String literal = "'" + file.toString().replace("'", "''") + "'";
        var view = Files.getFileAttributeView(file, AclFileAttributeView.class); var original = view.getAcl();
        try
        {
            windowsScript("$acl=[System.IO.File]::GetAccessControl(" + literal + ");"
                + "$acl.SetSecurityDescriptorSddlForm('D:NO_ACCESS_CONTROL',[System.Security.AccessControl.AccessControlSections]::Access);"
                + "[System.IO.File]::SetAccessControl(" + literal + ",$acl)");
            assertTrue("Java maps an unspecified DACL to an empty list", view.getAcl().isEmpty());
            assertThrows(IOException.class, () -> PrivatePaths.verifyFile(file));
        }
        finally { view.setAcl(original); }
    }

    static AclEntry grant(UserPrincipal principal, Set<AclEntryPermission> permissions, boolean inherit)
    {
        var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal).setPermissions(permissions);
        if (inherit) { entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT); }
        return entry.build();
    }

    static void makeForeignWritable(Path directory) throws Exception
    {
        if (PrivatePaths.posix(directory))
        { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx")); }
        else
        {
            var view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            var entries = new ArrayList<>(view.getAcl());
            entries.add(grant(windowsAccounts().get("everyone"),
                Set.of(AclEntryPermission.WRITE_DATA, AclEntryPermission.DELETE_CHILD), false));
            view.setAcl(entries);
        }
    }

    @Test public void refusesForeignDirectoryAccessAtEveryStorageOperation() throws Exception
    {
        var registry = registry(); registry.publish(endpoint(registry, "secure", ProcessHandle.current().pid()));
        String original = Files.readString(registry.recordPath());
        makeForeignWritable(registry.directory());
        assertThrows(IOException.class, () -> PrivatePaths.verifyDirectory(registry.directory()));
        assertThrows(IOException.class, () -> PrivatePaths.temporaryDirectory(registry.directory(), "extension-"));
        assertThrows(IOException.class, () -> new EndpointRegistry(registry.directory(), registry.workspace()));
        assertThrows(IOException.class, registry::read);
        assertThrows(IOException.class, () -> { try (var channel = PrivatePaths.lockFile(registry.lockPath())) { } });
        assertThrows(IOException.class, () -> EndpointRegistry.atomicWrite(registry.recordPath(), "refused"));
        assertEquals(original, Files.readString(registry.recordPath()));
    }

    @Test public void directoryRepairsOwnedPermissionsBeforeStrictValidation() throws Exception
    {
        var directory = PrivatePaths.directory(temporary.newFolder().toPath()); var owner = Files.getOwner(directory);
        makeForeignWritable(directory);
        assertThrows(IOException.class, () -> PrivatePaths.verifyDirectory(directory));
        assertEquals(directory, PrivatePaths.directory(directory));
        PrivatePaths.verifyDirectory(directory); assertEquals(owner, Files.getOwner(directory));
        if (PrivatePaths.posix(directory))
        { assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory)); }
        else
        {
            assertTrue(Files.getFileAttributeView(directory, AclFileAttributeView.class).getAcl().stream()
                .filter(entry -> entry.type() == AclEntryType.ALLOW).allMatch(entry -> entry.principal().equals(owner)));
        }
    }

    @Test public void windowsRejectsAnUnspecifiedDirectoryDacl() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var directory = temporary.newFolder().toPath();
        String literal = "'" + directory.toString().replace("'", "''") + "'";
        var view = Files.getFileAttributeView(directory, AclFileAttributeView.class); var original = view.getAcl();
        try
        {
            windowsScript("$acl=[System.IO.Directory]::GetAccessControl(" + literal + ");"
                + "$acl.SetSecurityDescriptorSddlForm('D:NO_ACCESS_CONTROL',[System.Security.AccessControl.AccessControlSections]::Access);"
                + "[System.IO.Directory]::SetAccessControl(" + literal + ",$acl)");
            assertTrue("Java maps an unspecified DACL to an empty list", view.getAcl().isEmpty());
            assertThrows(IOException.class, () -> PrivatePaths.verifyDirectory(directory));
            PrivatePaths.directory(directory); PrivatePaths.verifyDirectory(directory);
        }
        finally { view.setAcl(original); }
    }

    @Test public void windowsRejectsForeignInheritOnlyGrantsOnFilesAndDirectories() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        for (Path path : List.of(temporary.newFolder().toPath(), temporary.newFile().toPath()))
        {
            var view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            var entries = new ArrayList<>(view.getAcl());
            entries.add(AclEntry.newBuilder(grant(windowsAccounts().get("everyone"), Set.of(AclEntryPermission.WRITE_DATA), true))
                .setFlags(AclEntryFlag.INHERIT_ONLY, AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build());
            view.setAcl(entries);
            if (Files.isDirectory(path)) { assertThrows(IOException.class, () -> PrivatePaths.verifyDirectory(path)); }
            else { assertThrows(IOException.class, () -> PrivatePaths.verifyFile(path)); }
        }
    }

    @Test public void windowsAcceptsInheritedSystemAndAdministratorsAlongsideOwner() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var directory = temporary.newFolder().toPath();
        var accounts = windowsAccounts(); var full = EnumSet.allOf(AclEntryPermission.class);
        Files.getFileAttributeView(directory, AclFileAttributeView.class).setAcl(List.of(
            grant(Files.getOwner(directory), full, true), grant(accounts.get("system"), full, true),
            grant(accounts.get("administrators"), full, true)));
        var file = directory.resolve("normal-profile-file"); Files.writeString(file, "unchanged");
        var directoryAcl = Files.getFileAttributeView(directory, AclFileAttributeView.class).getAcl();
        PrivatePaths.verifyDirectory(directory);
        assertEquals(directoryAcl, Files.getFileAttributeView(directory, AclFileAttributeView.class).getAcl());
        var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        var before = acl.getAcl();
        assertTrue(before.stream().anyMatch(e -> e.principal().equals(accounts.get("system"))));
        assertTrue(before.stream().anyMatch(e -> e.principal().equals(accounts.get("administrators"))));
        PrivatePaths.verifyFile(file);
        assertEquals("Validation must preserve normal profile ACEs", before, acl.getAcl());
        assertEquals("unchanged", Files.readString(file));
    }

    @Test public void windowsRejectsForeignReadWriteAndInheritedFileGrantsBeforeJarReuse() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var foreign = windowsAccounts().get("everyone");
        var writeJar = StdioPluginSupport.class.getDeclaredMethod("writeJar", Path.class, byte[].class);
        writeJar.setAccessible(true);
        for (var permission : List.of(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA))
        {
            var directory = temporary.newFolder().toPath();
            var directoryAcl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            var entries = new ArrayList<>(directoryAcl.getAcl());
            entries.add(grant(foreign, Set.of(permission), true)); directoryAcl.setAcl(entries);
            var file = directory.resolve("cached.jar"); byte[] contents = "byte-identical cached jar".getBytes(StandardCharsets.UTF_8);
            Files.write(file, contents);
            var fileAcl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            assertTrue(fileAcl.getAcl().stream().anyMatch(e -> e.principal().equals(foreign)));
            assertThrows("An inherited foreign grant must be refused", IOException.class, () -> PrivatePaths.verifyFile(file));
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> writeJar.invoke(null, file, contents));
            assertTrue("Identical bytes must not bypass ACL validation", failure.getCause() instanceof IOException);
            fileAcl.setAcl(List.of(grant(Files.getOwner(file), EnumSet.allOf(AclEntryPermission.class), false),
                grant(foreign, Set.of(permission), false)));
            assertThrows("An explicit foreign grant must be refused", IOException.class, () -> PrivatePaths.verifyFile(file));
        }
    }

    @Test public void windowsRejectsReadableEndpointRecordBeforeReturningTheSecret() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var registry = registry(); registry.publish(endpoint(registry, "secure", ProcessHandle.current().pid()));
        var acl = Files.getFileAttributeView(registry.recordPath(), AclFileAttributeView.class);
        var entries = new ArrayList<>(acl.getAcl());
        entries.add(grant(windowsAccounts().get("everyone"), Set.of(AclEntryPermission.READ_DATA), false)); acl.setAcl(entries);
        assertThrows(IOException.class, registry::read);
    }

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
