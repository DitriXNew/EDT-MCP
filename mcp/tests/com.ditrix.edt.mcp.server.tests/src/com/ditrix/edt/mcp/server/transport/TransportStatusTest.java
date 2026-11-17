/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.tools.impl.GetServerStatusTool;
import com.google.gson.JsonParser;

public class TransportStatusTest extends StdioTestSupport
{
    @Test
    public void eachTransportContributesIndependently()
    {
        for (boolean http : new boolean[] {false, true})
        {
            for (boolean ipc : new boolean[] {false, true})
            {
                McpServer server = new McpServer() {
                    @Override public boolean isRunning() { return http; }
                };
                TransportStatus status = TransportStatus.read(server, () -> ipc);
                assertEquals(http, status.http());
                assertEquals(ipc, status.ipc());
                assertEquals(http || ipc, status.running());
            }
        }
        assertTrue(TransportStatus.read(null, () -> true).ipc());
        assertFalse(TransportStatus.read(null, () -> false).running());
    }

    @Test
    public void defaultReaderMatchesDiagnosticAcrossRealIpcLifecycle() throws Exception
    {
        Field field = StdioPluginSupport.class.getDeclaredField("LISTENER");
        field.setAccessible(true);
        AtomicReference<IpcListener> slot = (AtomicReference<IpcListener>) field.get(null);
        IpcListener previous = slot.get();
        try (IpcListener listener = new IpcListener(registry(), "test"))
        {
            slot.set(listener);
            assertIpc(true);
            listener.close();
            assertIpc(false);
        }
        finally { slot.set(previous); }
    }

    private void assertIpc(boolean expected)
    {
        assertEquals(expected, TransportStatus.read().ipc());
        assertEquals(expected, JsonParser.parseString(new GetServerStatusTool().execute(java.util.Map.of()))
            .getAsJsonObject().getAsJsonObject("transports").get("ipc").getAsBoolean());
    }
}
