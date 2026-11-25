// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.ipc.EndpointRegistry;
import com.ditrix.edt.mcp.server.ipc.HostShutdownClient;
import com.ditrix.edt.mcp.server.ipc.PrivatePaths;

/**
 * The wire contract between the external stop command and a real IPC listener, end to end.
 * <p>
 * The other shutdown tests use a fake host, which proves the client and the fake agree. This one
 * wires the classes production wires: the real {@link IpcListener} serves, the real
 * {@link HostShutdownClient} speaks to it, and the answer the client reports is the one the host
 * actually sent. It lives in this package because the listener constructor takes the server it
 * must arm, and that seam is package-private on purpose.
 */
public class HostShutdownWireContractTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Before public void secureTemporaryDirectory() throws Exception
    { PrivatePaths.directory(temporary.getRoot().toPath()); }

    private EndpointRegistry registry() throws Exception
    {
        return new EndpointRegistry(PrivatePaths.directory(temporary.newFolder().toPath()),
            temporary.newFolder().toPath());
    }

    private static PrintStream sink(ByteArrayOutputStream bytes)
    { return new PrintStream(bytes, true, StandardCharsets.UTF_8); }

    private static String text(ByteArrayOutputStream bytes)
    { return bytes.toString(StandardCharsets.UTF_8); }

    private static void assumeUnixSockets()
    {
        Assume.assumeTrue("AF_UNIX is required for a real listener",
            PrivatePaths.posix(Path.of(System.getProperty("java.io.tmpdir")))
                || System.getProperty("os.name").startsWith("Windows"));
    }

    @Test public void anArmedHostAnswersAcceptedAndTheClientWaitsForTheExit() throws Exception
    {
        assumeUnixSockets();
        var registry = registry();
        var server = new McpServer();
        var accepted = new CountDownLatch(1);
        server.setHostShutdown(completion -> accepted.countDown());
        try (var listener = new IpcListener(registry, "2.26.1", null, server))
        {
            var messages = new ByteArrayOutputStream();
            // The host accepts, but this JVM never exits, so the honest answer is the unknown one.
            assertEquals(text(messages), HostShutdownClient.NOT_STOPPED,
                HostShutdownClient.stop(registry.directory(), registry.workspace(), 1500, sink(messages)));
            assertTrue("The real listener must have run the channel", accepted.await(5, TimeUnit.SECONDS));
            assertTrue(text(messages), text(messages).contains("still running"));
            assertFalse("An accepted host is never reported as refused",
                text(messages).contains("refused to shut down"));
        }
    }

    @Test public void anUnarmedHostAnswersRefusedAndIsNeverKillable() throws Exception
    {
        // The windowed IDE: a real listener, no armed channel. REFUSED is a distinct answer, so a
        // wrapper can tell it apart from "unknown" - killing this host closes the user's IDE.
        assumeUnixSockets();
        var registry = registry();
        try (var listener = new IpcListener(registry, "2.26.1", null, new McpServer()))
        {
            var messages = new ByteArrayOutputStream();
            assertEquals(text(messages), HostShutdownClient.REFUSED,
                HostShutdownClient.stop(registry.directory(), registry.workspace(), 1500, sink(messages)));
            assertTrue(text(messages), text(messages).contains("do not kill it"));
        }
    }

    @Test public void aShutdownRequestWithAnIdIsAnsweredRatherThanIgnored() throws Exception
    {
        assumeUnixSockets();
        var registry = registry();
        var server = new McpServer();
        var shutdowns = new java.util.concurrent.atomic.AtomicInteger();
        server.setHostShutdown(completion -> shutdowns.incrementAndGet());
        var frames = new java.util.concurrent.CopyOnWriteArrayList<String>();
        try (var listener = new IpcListener(registry, "2.26.1", null, server);
             var connection = new IpcConnection(server, new com.ditrix.edt.mcp.server.protocol.McpProtocolHandler(), frames::add))
        {
            connection.accept("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
            connection.accept("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            connection.accept("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"notifications/host_shutdown\"}");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (frames.size() < 2 && System.nanoTime() < deadline) { Thread.sleep(5); }
            assertEquals("An id must be answered, not left waiting", 2, frames.size());
            var reply = com.google.gson.JsonParser.parseString(frames.get(1)).getAsJsonObject();
            assertEquals(7, reply.get("id").getAsInt());
            assertTrue("The answer is an error, because the method is a notification", reply.has("error"));
            assertEquals("A request carrying an id must not start a shutdown", 0, shutdowns.get());
        }
    }
}