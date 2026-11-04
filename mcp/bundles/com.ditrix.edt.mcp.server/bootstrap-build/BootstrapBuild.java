import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;
import java.util.zip.ZipFile;
import com.google.gson.*;
import com.ditrix.edt.mcp.server.protocol.*;
import com.ditrix.edt.mcp.server.tools.*;

/** Build-only generator. Executes the real registrar and protocol schema builders, never a source parser. */
public final class BootstrapBuild
{
    public static void main(String[] args) throws Exception
    {
        Path classes = Path.of(args[0]);
        Path destination = Path.of(args[1]);
        BuiltInToolRegistrar.registerAll(McpToolRegistry.getInstance());
        JsonObject catalog = new JsonObject();
        JsonObject initialize = GsonProvider.get().toJsonTree(new com.ditrix.edt.mcp.server.protocol.jsonrpc.InitializeResult(
            McpConstants.PROTOCOL_VERSION, McpConstants.SERVER_NAME, args[2], McpConstants.AUTHOR)).getAsJsonObject();
        // Offline bootstrap serves only tools; the connected dispatcher still handles the other MCP methods.
        initialize.getAsJsonObject("capabilities").remove("resources");
        catalog.add("initialize", initialize);
        catalog.add("versions", new Gson().toJsonTree(McpConstants.SUPPORTED_VERSIONS));
        catalog.add("tools", GsonProvider.get().toJsonTree(McpProtocolHandler.toolCatalog(
            McpToolRegistry.getInstance().getAllTools(), ClientCapabilities.ABSENT)));
        if (catalog.getAsJsonObject("tools").getAsJsonArray("tools").size() != McpToolRegistry.getInstance().getToolCount())
        { throw new IllegalStateException("Bootstrap catalog must include every registered schema"); }
        JsonObject hostCapabilities = new JsonObject();
        for (IMcpTool tool : McpToolRegistry.getInstance().getAllTools()) {
            var entry = HostToolCapabilities.requireEntry(tool.getName());
            hostCapabilities.addProperty(tool.getName(), entry.capability().name());
        }
        catalog.add("hostCapabilities", hostCapabilities);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "com.ditrix.edt.mcp.server.ipc.StdioBootstrap");
        manifest.getMainAttributes().putValue("Implementation-Version", args[2]);
        Files.createDirectories(destination.getParent());
        Path runtime = destination.resolveSibling("edt-mcp-runtime.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(runtime)))
        {
            addManifest(jar, manifest);
            try (var paths = Files.walk(classes.resolve("com/ditrix/edt/mcp/server/ipc")))
            {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList())
                { add(jar, classes.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path)); }
            }
            add(jar, "bootstrap-catalog.json", catalog.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (String name : List.of("ToolResult", "GsonProvider"))
            {
                String resource = "com/ditrix/edt/mcp/server/protocol/" + name + ".class";
                add(jar, resource, Files.readAllBytes(classes.resolve(resource)));
            }
            add(jar, "META-INF/LICENSE-gson.txt", Files.readAllBytes(Path.of(args[3], "APACHE-2.0.txt")));
            add(jar, "META-INF/NOTICE-gson.txt", ("Gson\nCopyright 2008 Google Inc.\n"
                + "https://github.com/google/gson\nLicensed under Apache License 2.0; see LICENSE-gson.txt.\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            add(jar, "META-INF/LICENSE-edt-mcp.txt", Files.readAllBytes(Path.of(args[4])));
            // The bootstrap carries Gson inside its jar. It never loads Eclipse or OSGi.
            Path gson = Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (ZipFile dependency = new ZipFile(gson.toFile()))
            {
                var entries = dependency.entries();
                while (entries.hasMoreElements())
                {
                    var entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().startsWith("com/google/gson/"))
                    { try (var input = dependency.getInputStream(entry)) { add(jar, entry.getName(), input.readAllBytes()); } }
                }
            }
        }
        Manifest entryManifest = new Manifest();
        entryManifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        entryManifest.getMainAttributes().putValue("Main-Class", "com.ditrix.edt.mcp.server.ipc.StdioEntry");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(destination)))
        {
            addManifest(jar, entryManifest);
            try (var paths = Files.list(classes.resolve("com/ditrix/edt/mcp/server/ipc")))
            {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList())
                {
                    String name = path.getFileName().toString();
                    if (List.of("StdioEntry", "PrivatePaths").stream().anyMatch(type ->
                        name.equals(type + ".class") || (name.startsWith(type + "$") && name.endsWith(".class"))))
                    { add(jar, classes.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path)); }
                }
            }
            add(jar, "META-INF/LICENSE-edt-mcp.txt", Files.readAllBytes(Path.of(args[4])));
        }
        System.out.println("Embedded bootstrap generated from " + McpToolRegistry.getInstance().getToolCount() + " registered tools");
    }
    private static void add(JarOutputStream jar, String name, byte[] bytes) throws IOException
    { JarEntry entry = new JarEntry(name); entry.setTime(0); jar.putNextEntry(entry); jar.write(bytes); jar.closeEntry(); }
    private static void addManifest(JarOutputStream jar, Manifest manifest) throws IOException
    { var bytes = new ByteArrayOutputStream(); manifest.write(bytes); add(jar, "META-INF/MANIFEST.MF", bytes.toByteArray()); }
}
