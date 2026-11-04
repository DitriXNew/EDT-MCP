package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import org.junit.*;
import com.ditrix.edt.mcp.server.ipc.*;
import com.ditrix.edt.mcp.server.protocol.*;
import com.ditrix.edt.mcp.server.tools.*;
import com.google.gson.*;

public class BootstrapPackagingTest extends StdioTestSupport
{
    private static final String MAIN = "com.ditrix.edt.mcp.server.ipc.StdioEntry";
    private byte[] fakeRuntime;
    private record EntryFiles(Path directory, Path entry, Path runtime, Path marker) { }
    private record Execution(int exit, String stdout, String stderr) { }

    private Execution runBootstrap(String... args) throws Exception
    { return runBootstrapInput("", args); }

    private Execution runBootstrapInput(String input, String... args) throws Exception
    {
        Path directory = temporary.newFolder().toPath(), runtime = directory.resolve("runtime.jar");
        try (var resource = StdioBootstrap.class.getResourceAsStream("/bootstrap/edt-mcp-runtime.jar"))
        { assertNotNull(resource); Files.copy(resource, runtime); }
        var command = new ArrayList<>(List.of(jdkBinary("java").toString(), "-cp", runtime.toString(),
            "com.ditrix.edt.mcp.server.ipc.StdioBootstrap"));
        command.addAll(List.of(args)); return executeInput(directory, input, command.toArray(String[]::new));
    }

    @Test public void missingInstallNamesTheOptionAndConfigurationFix() throws Exception
    {
        Path missing = temporary.getRoot().toPath().resolve("no-such-edt");
        var result = runBootstrap("-install", missing.toString(), "-data", temporary.getRoot().toString());
        assertEquals(1, result.exit()); assertEquals("", result.stdout());
        assertEquals("EDT MCP bootstrap: -install path does not exist: " + missing
            + ". Set -install to the EDT installation directory in .mcp.json." + System.lineSeparator(), result.stderr());
    }

    @Test public void installFileNamesTheOptionAndConfigurationFix() throws Exception
    {
        Path file = temporary.newFile("not-an-install").toPath();
        var result = runBootstrap("-install", file.toString(), "-data", temporary.getRoot().toString());
        assertEquals(1, result.exit()); assertEquals("", result.stdout());
        assertEquals("EDT MCP bootstrap: -install is not a directory: " + file
            + ". Set -install to the EDT installation directory in .mcp.json." + System.lineSeparator(), result.stderr());
    }

    @Test public void invalidMainOptionsNameTheOptionAndConfigurationFix() throws Exception
    {
        Path install = temporary.newFolder().toPath(); Files.writeString(install.resolve("1cedt.ini"), "");
        for (String option : List.of("-data", "-configuration"))
        {
            var result = option.equals("-data") ? runBootstrap("-install", install.toString(), "-data", ".")
                : runBootstrap("-install", install.toString(), "-data", temporary.getRoot().toString(), option, ".");
            assertEquals(1, result.exit()); assertEquals("", result.stdout());
            assertEquals("EDT MCP bootstrap: " + option + " must be an absolute path: ."
                + " Set an absolute directory path in .mcp.json." + System.lineSeparator(), result.stderr());
        }
    }

    @Test public void unreadableOrMalformedInstallIniNamesTheInstallFix() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path install = temporary.newFolder().toPath();
        for (boolean malformed : List.of(false, true))
        {
            if (malformed) { Files.writeString(install.resolve("1cedt.ini"), "-Dosgi.requiredJavaVersion=invalid\n"); }
            var result = runBootstrap("-install", install.toString(), "-data", temporary.getRoot().toString());
            assertEquals(1, result.exit()); assertEquals("", result.stdout());
            assertTrue(result.stderr(), result.stderr().startsWith("EDT MCP bootstrap: Cannot use -install " + install + ": "));
            assertTrue(result.stderr(), result.stderr().endsWith("Set -install to the EDT installation directory in .mcp.json." + System.lineSeparator()));
        }
    }

    @Test public void tooOldJavaPreservesVmAdviceWithoutInstallAdvice() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path install = temporary.newFolder().toPath(); int required = Runtime.version().feature() + 1;
        Files.writeString(install.resolve("1cedt.ini"), "-Dosgi.requiredJavaVersion=" + required + "\n");
        var result = runBootstrap("-install", install.toString(), "-data", temporary.getRoot().toString());
        assertEquals(1, result.exit()); assertEquals("", result.stdout());
        assertEquals("EDT MCP bootstrap: This EDT install requires Java " + required
            + "; select it with the EDT launcher -vm option." + System.lineSeparator(), result.stderr());
        assertFalse(result.stderr(), result.stderr().contains("-install"));
    }

    @Test public void malformedRequiredJavaVersionKeepsInstallAdviceWithOneFullStop() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path install = temporary.newFolder().toPath(), ini = install.resolve("1cedt.ini");
        Files.writeString(ini, "-Dosgi.requiredJavaVersion=invalid\n");
        var result = runBootstrap("-install", install.toString(), "-data", temporary.getRoot().toString());
        assertEquals(1, result.exit()); assertEquals("", result.stdout());
        assertEquals("EDT MCP bootstrap: Cannot use -install " + install
            + ": Invalid -Dosgi.requiredJavaVersion value 'invalid' in " + ini
            + ". Set -install to the EDT installation directory in .mcp.json." + System.lineSeparator(), result.stderr());
        assertFalse(result.stderr(), result.stderr().contains(".."));
    }

    @Test public void truncatedStdioFrameNamesClientConnectionAndFrameFix() throws Exception
    {
        Path install = temporary.newFolder().toPath(); Files.writeString(install.resolve("1cedt.ini"), "");
        var result = runBootstrapInput("{", "-install", install.toString(), "-data", temporary.getRoot().toString());
        assertEquals(1, result.exit()); assertEquals("", result.stdout());
        assertEquals("EDT MCP bootstrap: MCP stdio transport failed: Truncated JSON-RPC frame. "
            + "Check the client's stdio connection and newline-delimited UTF-8 JSON-RPC frames."
            + System.lineSeparator(), result.stderr());
    }

    private static Path jdkBinary(String name)
    {
        return Path.of(System.getProperty("java.home"), "bin", name
            + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
    }

    private static Execution execute(Path directory, String... command) throws Exception
    { return executeInput(directory, "", command); }

    private static Execution executeInput(Path directory, String input, String... command) throws Exception
    {
        Path stdout = directory.resolve("stdout"), stderr = directory.resolve("stderr");
        var builder = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))
        { builder.environment().remove(variable); }
        Process process = builder.start();
        try
        {
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            assertTrue("Owned child JVM must finish", process.waitFor(20, TimeUnit.SECONDS));
            return new Execution(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        }
        finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
    }

    private byte[] fakeRuntime() throws Exception
    {
        if (fakeRuntime != null) { return fakeRuntime; }
        Path directory = temporary.newFolder().toPath(); Path source = directory.resolve("StdioBootstrap.java");
        Files.writeString(source, """
            package com.ditrix.edt.mcp.server.ipc;
            public final class StdioBootstrap {
                static {
                    try { java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("entry.marker")), "runtime executed"); }
                    catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                }
                public static void main(String[] args) { }
            }
            """);
        var compiled = execute(directory, jdkBinary("javac").toString(), "--release", "17", "-d", directory.toString(), source.toString());
        assertEquals(compiled.stderr(), 0, compiled.exit());
        var bytes = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(bytes))
        {
            String name = "com/ditrix/edt/mcp/server/ipc/StdioBootstrap.class";
            var entry = new JarEntry(name); entry.setTime(0); jar.putNextEntry(entry);
            jar.write(Files.readAllBytes(directory.resolve(name))); jar.closeEntry();
        }
        fakeRuntime = bytes.toByteArray(); return fakeRuntime;
    }

    private EntryFiles entryFiles() throws Exception
    {
        Path root = EndpointRegistry.userPath(System.getProperty("os.name"),
            Map.of("LOCALAPPDATA", temporary.newFolder().toString(),
                "XDG_RUNTIME_DIR", temporary.newFolder().toString()), temporary.getRoot().toPath());
        PrivatePaths.directory(root);
        Path directory = PrivatePaths.directory(root.resolve("bootstrap/install"));
        Path entry = directory.resolve("entry.jar");
        try (var input = StdioBootstrap.class.getResourceAsStream("/bootstrap/edt-mcp-bootstrap.jar"))
        {
            assertNotNull("Use the real entry jar built into the plugin", input); Files.copy(input, entry);
        }
        byte[] bytes = fakeRuntime();
        String name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + ".jar";
        Path runtime = directory.resolve(name);
        PrivatePaths.atomicWrite(runtime, bytes);
        PrivatePaths.atomicWrite(directory.resolve("current"), (name + "\n").getBytes(StandardCharsets.UTF_8));
        return new EntryFiles(directory, entry, runtime, directory.resolve("executed.marker"));
    }

    private Execution runEntry(EntryFiles files) throws Exception
    {
        return execute(files.directory(), jdkBinary("java").toString(), "-Dentry.marker=" + files.marker(),
            "-cp", files.entry().toString(), MAIN);
    }

    private static void assertRefused(EntryFiles files, Execution result) throws Exception
    {
        assertFalse("Refused runtime must never initialize", Files.exists(files.marker()));
        assertEquals("Nothing may reach stdout", "", result.stdout());
        assertEquals(1, result.exit());
        assertTrue(result.stderr(), result.stderr().startsWith("EDT MCP entry: "));
        assertEquals(result.stderr(), 1, result.stderr().lines().count());
    }

    @Test public void builtEntryExecutesAValidRuntimeWithOnlyTheEntryJarOnItsClasspath() throws Exception
    {
        var files = entryFiles();
        EndpointRegistryTest.makeForeignWritable(files.directory().getParent().getParent().getParent());
        var result = runEntry(files);
        assertTrue("Runtime marker must exist; stderr=" + result.stderr(), Files.exists(files.marker()));
        assertEquals("runtime executed", Files.readString(files.marker()));
        assertEquals(result.stderr(), 0, result.exit());
        assertEquals("", result.stdout()); assertEquals("", result.stderr());
    }

    @Test public void builtEntryRejectsForeignWritableDirectoryBeforeRuntimeInitialization() throws Exception
    {
        var files = entryFiles(); EndpointRegistryTest.makeForeignWritable(files.directory());
        assertRefused(files, runEntry(files));
    }

    private void assertForeignWritableAncestorRefused(int depth) throws Exception
    {
        var files = entryFiles(); Path ancestor = files.directory();
        for (int level = 0; level < depth; level++) { ancestor = ancestor.getParent(); }
        EndpointRegistryTest.makeForeignWritable(ancestor);
        assertRefused(files, runEntry(files));
    }

    @Test public void builtEntryRejectsForeignWritableBootstrapParent() throws Exception
    { assertForeignWritableAncestorRefused(1); }

    @Test public void builtEntryRejectsForeignWritableStorageRoot() throws Exception
    { assertForeignWritableAncestorRefused(2); }

    @Test public void builtEntryRejectsRuntimeBytesThatDoNotMatchTheirContentAddress() throws Exception
    {
        var files = entryFiles();
        // Keep a loadable jar so the marker exposes execution by an unchecked entry.
        try (var fileSystem = FileSystems.newFileSystem(files.runtime(), Map.of()))
        { Files.writeString(fileSystem.getPath("/altered.txt"), "changed after publication"); }
        assertRefused(files, runEntry(files));
    }

    @Test public void builtEntryRejectsAForeignWriteGrantBeforeRuntimeInitialization() throws Exception
    {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var files = entryFiles(); var view = Files.getFileAttributeView(files.runtime(), AclFileAttributeView.class);
        var entries = new ArrayList<>(view.getAcl());
        entries.add(EndpointRegistryTest.grant(EndpointRegistryTest.windowsAccounts().get("everyone"),
            Set.of(AclEntryPermission.WRITE_DATA), false)); view.setAcl(entries);
        assertRefused(files, runEntry(files));
    }

    @Test public void builtEntryRefusesAMissingRuntimeOnStderrWithoutExecutingAnything() throws Exception
    {
        var files = entryFiles(); Files.delete(files.runtime());
        assertRefused(files, runEntry(files));
    }

    private final class PausedEntry implements AutoCloseable
    {
        final Path control;
        final Process process;

        PausedEntry(EntryFiles files) throws Exception
        {
            control = temporary.newFolder().toPath();
            Path source = control.resolve("PauseEntry.java");
            Files.writeString(source, """
                import com.sun.jdi.*;
                import com.sun.jdi.event.*;
                import java.nio.file.*;
                import java.util.concurrent.TimeUnit;
                public class PauseEntry {
                    public static void main(String[] args) throws Exception {
                        Path control = Path.of(args[2]);
                        var connector = Bootstrap.virtualMachineManager().defaultConnector();
                        var options = connector.defaultArguments();
                        options.get("main").setValue("com.ditrix.edt.mcp.server.ipc.StdioEntry");
                        options.get("options").setValue("-cp \\\"" + args[0] + "\\\" -Dentry.marker=\\\"" + args[1] + "\\\"");
                        options.get("home").setValue(System.getProperty("java.home"));
                        var vm = connector.launch(options); var child = vm.process();
                        try {
                            var request = vm.eventRequestManager().createMethodEntryRequest();
                            request.addClassFilter("java.net.URLClassLoader"); request.enable(); vm.resume();
                            boolean paused = false;
                            while (!paused) {
                                var events = vm.eventQueue().remove(15000);
                                if (events == null) { throw new AssertionError("Entry did not reach its loader"); }
                                for (var event : events) {
                                    if (event instanceof MethodEntryEvent method && method.method().isConstructor()) {
                                        request.disable(); paused = true;
                                        Files.writeString(control.resolve("paused"), "verified runtime, unopened loader");
                                        if (System.in.read() != 'x') { throw new AssertionError("Missing resume signal"); }
                                    }
                                }
                                events.resume();
                            }
                            vm.dispose();
                            if (!child.waitFor(15, TimeUnit.SECONDS)) { throw new AssertionError("Entry did not finish"); }
                            Files.writeString(control.resolve("exit"), Integer.toString(child.exitValue()));
                            Files.write(control.resolve("stdout"), child.getInputStream().readAllBytes());
                            Files.write(control.resolve("stderr"), child.getErrorStream().readAllBytes());
                        } finally {
                            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
                        }
                    }
                }
                """);
            var builder = new ProcessBuilder(jdkBinary("java").toString(), "--add-modules", "jdk.jdi",
                source.toString(), files.entry().toString(), files.marker().toString(), control.toString())
                .redirectOutput(control.resolve("probe.stdout").toFile()).redirectError(control.resolve("probe.stderr").toFile());
            for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"))
            { builder.environment().remove(variable); }
            process = builder.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(control.resolve("paused")) && process.isAlive() && System.nanoTime() < deadline)
            { Thread.sleep(10); }
            if (!Files.exists(control.resolve("paused")))
            {
                close(); fail("Entry did not pause: " + Files.readString(control.resolve("probe.stderr")));
            }
        }

        Execution resume() throws Exception
        {
            process.getOutputStream().write('x'); process.getOutputStream().close();
            assertTrue("Debugger helper must finish", process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(Files.readString(control.resolve("probe.stderr")), 0, process.exitValue());
            return new Execution(Integer.parseInt(Files.readString(control.resolve("exit"))),
                Files.readString(control.resolve("stdout")), Files.readString(control.resolve("stderr")));
        }

        @Override public void close() throws Exception
        {
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    @Test public void deletingVerifiedRuntimeBeforeLoaderOpensItMakesTheShippedEntryFail() throws Exception
    {
        var files = entryFiles();
        try (var paused = new PausedEntry(files))
        {
            Files.delete(files.runtime());
            StdioPluginSupportTest.assertListing(files.directory(), "current", "entry.jar");
            var result = paused.resume(); assertRefused(files, result);
            assertEquals("EDT MCP entry: com.ditrix.edt.mcp.server.ipc.StdioBootstrap" + System.lineSeparator(), result.stderr());
        }
    }

    @Test public void cleanupPreservesVerifiedPredecessorUntilTheShippedEntryLoadsIt() throws Exception
    {
        var files = entryFiles(); byte[] stale = "obsolete runtime".getBytes(StandardCharsets.UTF_8);
        String staleName = StdioPluginSupportTest.runtimeName(stale);
        PrivatePaths.atomicWrite(files.directory().resolve(staleName), stale);
        byte[] replacement = "next runtime".getBytes(StandardCharsets.UTF_8);
        String current = StdioPluginSupportTest.runtimeName(replacement);
        try (var paused = new PausedEntry(files))
        {
            byte[] entry = Files.readAllBytes(files.entry());
            StdioPluginSupport.extractBootstrap(files.directory(), replacement, entry, Files::delete);
            StdioPluginSupport.extractBootstrap(files.directory(), replacement, entry, Files::delete);
            var result = paused.resume();
            assertEquals(result.stderr(), 0, result.exit());
            assertEquals("", result.stdout()); assertEquals("", result.stderr());
            assertEquals("runtime executed", Files.readString(files.marker()));
            StdioPluginSupportTest.assertListing(files.directory(), "current", "previous", "extract.lock",
                "entry.jar", "edt-mcp-bootstrap.jar", current, files.runtime().getFileName().toString(), "executed.marker");
        }
    }

    @Test public void entryJarShipsItsCompletePrivatePathsFamilyWithoutRuntimeOrGsonClasses() throws Exception
    {
        var files = entryFiles(); var classes = new HashSet<String>();
        try (var jar = new JarFile(files.entry().toFile()))
        {
            assertEquals(MAIN, jar.getManifest().getMainAttributes().getValue("Main-Class"));
            var entries = jar.entries();
            while (entries.hasMoreElements())
            {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) { continue; }
                assertTrue(entry.getName(), entry.getName().equals("com/ditrix/edt/mcp/server/ipc/StdioEntry.class")
                    || entry.getName().startsWith("com/ditrix/edt/mcp/server/ipc/PrivatePaths"));
                try (var input = jar.getInputStream(entry))
                {
                    byte[] bytes = input.readAllBytes();
                    assertEquals("Entry classes must target Java 17", 61, ((bytes[6] & 255) << 8) | (bytes[7] & 255));
                }
                classes.add(entry.getName());
            }
        }
        assertTrue("Entry must carry PrivatePaths", classes.contains("com/ditrix/edt/mcp/server/ipc/PrivatePaths.class"));
        assertTrue("Entry must carry PrivatePaths' file-attribute helper", classes.contains("com/ditrix/edt/mcp/server/ipc/PrivatePaths$1.class"));
    }

    @Test public void embeddedJarContainsGeneratedSchemasAndNoPlatformClasses() throws Exception
    {
        var saved = McpToolRegistry.getInstance().getAllTools();
        try
        {
            BuiltInToolRegistrar.registerAll(McpToolRegistry.getInstance());
            JsonObject generated = null;
            try (var input = StdioBootstrap.class.getResourceAsStream("/bootstrap/edt-mcp-runtime.jar"))
            {
                assertNotNull("The plugin must ship the bootstrap inside the bundle", input);
                try (var jar = new java.util.jar.JarInputStream(input))
                {
                    java.util.jar.JarEntry entry;
                    while ((entry = jar.getNextJarEntry()) != null)
                    {
                        assertFalse(entry.getName(), entry.getName().startsWith("org/eclipse/"));
                        assertFalse(entry.getName(), entry.getName().startsWith("org/osgi/"));
                        if (entry.getName().equals("bootstrap-catalog.json"))
                        { generated = JsonParser.parseString(new String(jar.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject(); }
                        if (entry.getName().startsWith("com/ditrix/") && entry.getName().endsWith(".class"))
                        {
                            byte[] bytes = jar.readAllBytes();
                            assertEquals("Bootstrap classes must target Java 17", 61, ((bytes[6] & 255) << 8) | (bytes[7] & 255));
                        }
                    }
                }
            }
            assertNotNull(generated);
            assertEquals(GsonProvider.get().toJsonTree(McpProtocolHandler.toolCatalog(
                McpToolRegistry.getInstance().getAllTools(), ClientCapabilities.ABSENT)), generated.get("tools"));
        }
        finally { McpToolRegistry.getInstance().replaceAll(saved); }
    }
}
