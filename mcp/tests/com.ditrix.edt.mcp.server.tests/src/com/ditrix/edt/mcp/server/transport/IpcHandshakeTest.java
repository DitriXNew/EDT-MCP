package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.*;
import com.ditrix.edt.mcp.server.ipc.*;
import com.google.gson.*;

public class IpcHandshakeTest extends StdioTestSupport
{
    @Test public void authenticatedPrefaceCarriesOnlyThisBridgesDeliveryChoice() throws Exception
    {
        var endpoint = endpoint(registry(), "generation", ProcessHandle.current().pid());
        assertNull(IpcHandshake.plainTextMode(IpcHandshake.hello(endpoint)));
        for (Boolean mode : List.of(true, false))
        {
            String hello = IpcHandshake.hello(endpoint, mode);
            assertTrue(IpcHandshake.accepts(hello, endpoint));
            assertEquals(mode, IpcHandshake.plainTextMode(hello));
        }
        assertThrows(java.io.IOException.class, () -> IpcHandshake.plainTextMode("{\"plainTextMode\":\"yes\"}"));
    }
    @Test public void handshakeRejectsWrongSecretGenerationWorkspaceAndVersion() throws Exception
    {
        var registry = registry(); var expected = endpoint(registry, "generation", ProcessHandle.current().pid());
        assertTrue(IpcHandshake.accepts(IpcHandshake.hello(expected), expected));
        for (String field : List.of("secret", "generation", "workspace", "pluginVersion", "startTime", "ipcVersion", "pid"))
        {
            var wrong = JsonParser.parseString(IpcHandshake.hello(expected)).getAsJsonObject();
            if (field.equals("ipcVersion") || field.equals("pid")) { wrong.addProperty(field, -1); }
            else { wrong.addProperty(field, "wrong"); }
            assertFalse(field, IpcHandshake.accepts(wrong.toString(), expected));
        }
        assertFalse(IpcHandshake.accepts("{}", expected)); assertFalse(IpcHandshake.accepts("not-json", expected));
    }
}
