package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.*;
import com.ditrix.edt.mcp.server.ipc.*;
import com.ditrix.edt.mcp.server.protocol.*;
import com.ditrix.edt.mcp.server.tools.*;
import com.google.gson.*;

public class BootstrapPackagingTest extends StdioTestSupport
{
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
