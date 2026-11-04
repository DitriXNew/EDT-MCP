/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ipc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.google.gson.Gson;

/** A private protocol preface, consumed before any MCP dispatch. Never logged. */
public final class IpcHandshake
{
    private static final Gson JSON = new Gson();
    private IpcHandshake() { }
    public static String hello(EndpointRegistry.Endpoint endpoint) { return JSON.toJson(endpoint); }
    public static boolean accepts(String hello, EndpointRegistry.Endpoint expected)
    {
        try
        {
            var actual = JSON.fromJson(hello, EndpointRegistry.Endpoint.class);
            return actual != null && actual.ipcVersion() == EndpointRegistry.IPC_VERSION
                && expected.generation().equals(actual.generation())
                && expected.workspace().equals(actual.workspace())
                && expected.pluginVersion().equals(actual.pluginVersion())
                && expected.pid() == actual.pid() && expected.startTime().equals(actual.startTime())
                && actual.secret() != null && MessageDigest.isEqual(expected.secret().getBytes(StandardCharsets.UTF_8),
                    actual.secret().getBytes(StandardCharsets.UTF_8));
        }
        catch (RuntimeException e) { return false; }
    }
    public static void client(JsonFrames.Socket socket, EndpointRegistry.Endpoint endpoint) throws IOException
    {
        if (endpoint.ipcVersion() != EndpointRegistry.IPC_VERSION) { throw new IOException("Incompatible EDT IPC version"); }
        socket.write(hello(endpoint));
        if (!"{\"accepted\":true}".equals(socket.read())) { throw new IOException("EDT IPC handshake refused"); }
    }
}
