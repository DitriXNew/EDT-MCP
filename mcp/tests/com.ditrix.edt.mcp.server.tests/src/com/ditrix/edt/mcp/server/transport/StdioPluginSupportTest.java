package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.*;
import org.osgi.framework.FrameworkUtil;
import com.ditrix.edt.mcp.server.ipc.PrivatePaths;
import com.ditrix.edt.mcp.server.utils.Log;

public class StdioPluginSupportTest extends StdioTestSupport
{
    private static final byte[] ENTRY = "stable entry".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OLD = "old runtime".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREVIOUS = "previous runtime".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CURRENT = "current runtime".getBytes(StandardCharsets.UTF_8);

    static String runtimeName(byte[] bytes) throws Exception
    { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + ".jar"; }

    static void assertListing(Path directory, String... names) throws IOException
    {
        try (var files = Files.list(directory))
        { assertEquals(new TreeSet<>(List.of(names)), new TreeSet<>(files.map(path -> path.getFileName().toString()).toList())); }
    }

    private Path seeded() throws Exception
    {
        Path directory = PrivatePaths.directory(temporary.newFolder().toPath());
        PrivatePaths.atomicWrite(directory.resolve(runtimeName(OLD)), OLD);
        PrivatePaths.atomicWrite(directory.resolve(runtimeName(PREVIOUS)), PREVIOUS);
        PrivatePaths.atomicWrite(directory.resolve("current"), (runtimeName(PREVIOUS) + "\n").getBytes(StandardCharsets.UTF_8));
        return directory;
    }

    private static Path extract(Path directory, byte[] runtime) throws IOException
    { return StdioPluginSupport.extractBootstrap(directory, runtime, ENTRY, Files::delete); }

    @Test public void removesOldRuntimeAfterPublishingCurrent() throws Exception
    {
        Path directory = seeded();
        assertEquals(directory.resolve("edt-mcp-bootstrap.jar"), extract(directory, CURRENT));
        assertListing(directory, "current", "previous", "extract.lock", "edt-mcp-bootstrap.jar",
            runtimeName(PREVIOUS), runtimeName(CURRENT));
    }

    @Test public void currentRuntimeAndEntryStayByteIdentical() throws Exception
    {
        Path directory = seeded(); extract(directory, CURRENT);
        assertEquals(runtimeName(CURRENT) + "\n", Files.readString(directory.resolve("current")));
        assertArrayEquals(CURRENT, Files.readAllBytes(directory.resolve(runtimeName(CURRENT))));
        assertArrayEquals(ENTRY, Files.readAllBytes(directory.resolve("edt-mcp-bootstrap.jar")));
        assertListing(directory, "current", "previous", "extract.lock", "edt-mcp-bootstrap.jar",
            runtimeName(PREVIOUS), runtimeName(CURRENT));
    }

    @Test public void leavesEveryNonmatchingNameAlone() throws Exception
    {
        Path directory = seeded();
        var other = List.of("notes.jar", "a".repeat(63) + ".jar", "a".repeat(65) + ".jar",
            "A".repeat(64) + ".jar", "g".repeat(64) + ".jar", "b".repeat(64) + ".JAR",
            "a".repeat(64) + ".jar.bak");
        for (String name : other) { PrivatePaths.atomicWrite(directory.resolve(name), OLD); }
        extract(directory, CURRENT);
        var expected = new ArrayList<>(other);
        expected.addAll(List.of("current", "previous", "extract.lock", "edt-mcp-bootstrap.jar",
            runtimeName(PREVIOUS), runtimeName(CURRENT)));
        assertListing(directory, expected.toArray(String[]::new));
    }

    private void assertDeleteFailureIsIsolated(boolean accessDenied) throws Exception
    {
        Path directory = seeded();
        PrivatePaths.atomicWrite(directory.resolve("a".repeat(64) + ".jar"), OLD);
        PrivatePaths.atomicWrite(directory.resolve("b".repeat(64) + ".jar"), OLD);
        var failed = new AtomicReference<Path>(); var attempted = new ArrayList<Path>();
        String current = runtimeName(CURRENT) + "\n";
        var statuses = new ArrayList<IStatus>();
        var bundle = FrameworkUtil.getBundle(Log.class); assertNotNull(bundle);
        var log = Platform.getLog(bundle);
        ILogListener listener = (status, plugin) -> statuses.add(status);
        var gate = Log.class.getDeclaredMethod("setDebugGate", BooleanSupplier.class); gate.setAccessible(true);
        log.addLogListener(listener); gate.invoke(null, (BooleanSupplier)() -> true);
        try
        {
            assertEquals(directory.resolve("edt-mcp-bootstrap.jar"),
                StdioPluginSupport.extractBootstrap(directory, CURRENT, ENTRY, path -> {
                    assertEquals(current, Files.readString(directory.resolve("current")));
                    attempted.add(path);
                    if (failed.compareAndSet(null, path))
                    {
                        if (accessDenied) { throw new AccessDeniedException(path.toString()); }
                        throw new IOException("injected delete failure");
                    }
                    Files.delete(path);
                }));
            assertEquals("Both remaining stale jars must be tried after the first failure", 3, attempted.size());
            assertListing(directory, "current", "previous", "extract.lock", "edt-mcp-bootstrap.jar",
                runtimeName(PREVIOUS), runtimeName(CURRENT), failed.get().getFileName().toString());
            assertEquals(runtimeName(CURRENT) + "\n", Files.readString(directory.resolve("current")));
            assertEquals(1, statuses.size());
            assertEquals(IStatus.INFO, statuses.get(0).getSeverity());
            assertTrue(statuses.get(0).getMessage(), statuses.get(0).getMessage().startsWith("[DEBUG] "));
            assertNull(statuses.get(0).getException());
        }
        finally { gate.invoke(null, (Object)null); log.removeLogListener(listener); }
    }

    @Test public void accessDeniedDoesNotAbortExtractionOrRemainingDeletesOrLogAnError() throws Exception
    { assertDeleteFailureIsIsolated(true); }

    @Test public void anyIoFailureDoesNotAbortExtractionOrRemainingDeletesOrLogAnError() throws Exception
    { assertDeleteFailureIsIsolated(false); }

    @Test public void windowsLockedRuntimeIsSkippedAndRetriedAfterTheHandleCloses() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path directory = seeded();
        var option = (OpenOption)Class.forName("com.sun.nio.file.ExtendedOpenOption", true,
            ClassLoader.getPlatformClassLoader()).getField("NOSHARE_DELETE").get(null);
        try (var handle = Files.newByteChannel(directory.resolve(runtimeName(OLD)), Set.of(StandardOpenOption.READ, option)))
        {
            extract(directory, CURRENT);
            assertListing(directory, "current", "previous", "extract.lock", "edt-mcp-bootstrap.jar",
                runtimeName(OLD), runtimeName(PREVIOUS), runtimeName(CURRENT));
        }
        extract(directory, CURRENT);
        assertListing(directory, "current", "previous", "extract.lock", "edt-mcp-bootstrap.jar",
            runtimeName(PREVIOUS), runtimeName(CURRENT));
    }

    @Test public void repeatedExtractionKeepsTheImmediatePredecessorUntilTheNextGeneration() throws Exception
    {
        Path directory = seeded(); extract(directory, CURRENT); extract(directory, CURRENT);
        assertEquals(runtimeName(PREVIOUS) + "\n", Files.readString(directory.resolve("previous")));
        assertListing(directory, "current", "previous", "extract.lock", "edt-mcp-bootstrap.jar",
            runtimeName(PREVIOUS), runtimeName(CURRENT));
        extract(directory, OLD);
        assertEquals(runtimeName(CURRENT) + "\n", Files.readString(directory.resolve("previous")));
        assertListing(directory, "current", "previous", "extract.lock", "edt-mcp-bootstrap.jar",
            runtimeName(OLD), runtimeName(CURRENT));
    }

    @Test public void lockedCompatibleEntryStillPublishesTheNewRuntimeAndIsReplacedAfterUnlock() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path directory = seeded();
        Path entry = directory.resolve("edt-mcp-bootstrap.jar");
        byte[] held = releasedEntry(), replacement = packagedEntry();
        assertFalse(Arrays.equals(held, replacement));
        PrivatePaths.atomicWrite(entry, held);
        try (var handle = Files.newByteChannel(entry, Set.of(StandardOpenOption.READ, noShareDelete())))
        {
            assertEquals(entry, StdioPluginSupport.extractBootstrap(directory, CURRENT, replacement, Files::delete));
            assertEquals(runtimeName(CURRENT) + "\n", Files.readString(directory.resolve("current")));
            assertArrayEquals(CURRENT, Files.readAllBytes(directory.resolve(runtimeName(CURRENT))));
            assertArrayEquals(held, Files.readAllBytes(entry));
            assertListing(directory, "current", "previous", "extract.lock", "edt-mcp-bootstrap.jar",
                runtimeName(PREVIOUS), runtimeName(CURRENT));
        }
        assertEquals(entry, StdioPluginSupport.extractBootstrap(directory, CURRENT, replacement, Files::delete));
        assertArrayEquals(replacement, Files.readAllBytes(entry));
    }

    @Test public void aDirectoryAtTheEntryPathIsNotAdvertised() throws Exception
    {
        Path directory = seeded();
        Path entry = PrivatePaths.directory(directory.resolve("edt-mcp-bootstrap.jar"));
        assertThrows(IOException.class, () -> extract(directory, CURRENT));
        assertTrue(Files.isDirectory(entry));
        // Fail closed: the pointer must not name a runtime the unusable entry cannot load.
        assertEquals(runtimeName(PREVIOUS) + "\n", Files.readString(directory.resolve("current")));
    }

    private byte[] releasedEntry() throws IOException
    {
        try (var input = getClass().getResourceAsStream("/fixtures/stdio-entry-v2.26.1.jar"))
        {
            assertNotNull("Use the packaged v2.26.1 launcher", input);
            return input.readAllBytes();
        }
    }

    private static byte[] packagedEntry() throws IOException
    {
        try (var input = StdioPluginSupport.class.getResourceAsStream("/bootstrap/edt-mcp-bootstrap.jar"))
        {
            assertNotNull("Use the real entry jar built into the plugin", input);
            return input.readAllBytes();
        }
    }

    private static OpenOption noShareDelete() throws Exception
    {
        return (OpenOption)Class.forName("com.sun.nio.file.ExtendedOpenOption", true,
            ClassLoader.getPlatformClassLoader()).getField("NOSHARE_DELETE").get(null);
    }

    @Test public void aRealStagingAccessDeniedIsNotTreatedAsAnEntryMoveRefusal() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path directory = seeded();
        Path entry = directory.resolve("edt-mcp-bootstrap.jar");
        byte[] held = releasedEntry();
        PrivatePaths.atomicWrite(entry, held);
        var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        var original = acl.getAcl();
        var restricted = new ArrayList<>(original);
        restricted.add(0, AclEntry.newBuilder()
            .setType(AclEntryType.DENY).setPrincipal(acl.getOwner())
            .setPermissions(AclEntryPermission.ADD_FILE).build());
        try
        {
            assertThrows(AccessDeniedException.class,
                () -> StdioPluginSupport.extractBootstrap(directory, CURRENT, ENTRY, stale -> {
                    Files.delete(stale);
                    acl.setAcl(restricted);
                    assertThrows(AccessDeniedException.class, () -> Files.createTempFile(directory, "probe-", ".tmp"));
                }));
            assertEquals(runtimeName(CURRENT) + "\n", Files.readString(directory.resolve("current")));
            assertArrayEquals(held, Files.readAllBytes(entry));
        }
        finally { acl.setAcl(original); }
    }

    @Test public void aLockedMalformedEntryIsNotAdvertised() throws Exception
    {
        assertLockedEntryIsRefused(ENTRY);
    }

    @Test public void aLockedJarClaimingTheExpectedMainClassWithoutTheLauncherIsNotAdvertised() throws Exception
    {
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "com.ditrix.edt.mcp.server.ipc.StdioEntry");
        var bytes = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(bytes, manifest)) { }
        assertLockedEntryIsRefused(bytes.toByteArray());
    }

    private void assertLockedEntryIsRefused(byte[] held) throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path directory = seeded();
        Path entry = directory.resolve("edt-mcp-bootstrap.jar");
        PrivatePaths.atomicWrite(entry, held);
        try (var handle = Files.newByteChannel(entry, Set.of(StandardOpenOption.READ, noShareDelete())))
        {
            byte[] replacement = packagedEntry();
            IOException refusal = assertThrows(IOException.class,
                () -> StdioPluginSupport.extractBootstrap(directory, CURRENT, replacement, Files::delete));
            assertTrue("Must reach the atomic move", refusal.getCause() instanceof AccessDeniedException);
            // An incompatible launcher cannot load the new runtime, so the pointer stays put and
            // no "previous" record is written: the retained entry keeps reading the old runtime.
            assertEquals(runtimeName(PREVIOUS) + "\n", Files.readString(directory.resolve("current")));
            assertArrayEquals(held, Files.readAllBytes(entry));
            assertFalse("A refused swap must not publish a new pointer record",
                Files.exists(directory.resolve("previous"), LinkOption.NOFOLLOW_LINKS));
            assertListing(directory, "current", "extract.lock", "edt-mcp-bootstrap.jar",
                runtimeName(PREVIOUS), runtimeName(CURRENT));
        }
    }

    @Test public void matchingDirectoryIsRefusedWithoutStoppingCleanup() throws Exception
    {
        Path directory = seeded(); String name = "a".repeat(64) + ".jar";
        PrivatePaths.directory(directory.resolve(name)); extract(directory, CURRENT);
        assertListing(directory, "current", "previous", "extract.lock", "edt-mcp-bootstrap.jar",
            runtimeName(PREVIOUS), runtimeName(CURRENT), name);
    }

    @Test public void matchingLinkIsRefusedWithoutTouchingItsTarget() throws Exception
    {
        Path directory = seeded(); Path target = temporary.newFile().toPath(); Files.write(target, OLD);
        String name = "a".repeat(64) + ".jar"; Path link = directory.resolve(name);
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException e) { Assume.assumeNoException(e); }
        try
        {
            extract(directory, CURRENT);
            assertArrayEquals(OLD, Files.readAllBytes(target));
            assertListing(directory, "current", "previous", "extract.lock", "edt-mcp-bootstrap.jar",
                runtimeName(PREVIOUS), runtimeName(CURRENT), name);
        }
        finally { Files.deleteIfExists(link); }
    }
}
