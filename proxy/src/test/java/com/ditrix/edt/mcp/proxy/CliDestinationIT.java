/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.ProxyFixture;
import com.sun.net.httpserver.HttpServer;

/** A different listener on 127.0.0.1 must never receive a configured proxy credential. */
public class CliDestinationIT
{
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    @Rule public final Timeout timeout = Timeout.seconds(30);

    @Test
    public void concreteIpv4CommandsUseTheConfiguredListener() throws Exception
    {
        checkDestination("127.0.0.2");
    }

    @Test
    public void concreteIpv6CommandsNeverFallBackToIpv4() throws Exception
    {
        checkDestination("::1");
    }

    private void checkDestination(String bind) throws Exception
    {
        AtomicInteger trapped = new AtomicInteger();
        HttpServer decoy = decoy(trapped);
        ProxyFixture proxy = null;
        try
        {
            Path file = config(bind, decoy.getAddress().getPort());
            String[] args = { "--config", file.toString() };
            proxy = new ProxyFixture(ProxyConfig.parse(args, Map.of()));
            proxy.start();
            try (PrintStream output = new PrintStream(new ByteArrayOutputStream()))
            {
                int status = CliCommands.status(args, output, output);
                assertEquals("No health, MCP or cleanup request may hit the decoy", 0, trapped.get());
                assertEquals(0, status);
                assertEquals(0, proxy.activeSessions());
                assertEquals(0, CliCommands.stop(args, output, output));
                assertEquals(0, trapped.get());
            }
        }
        finally
        {
            if (proxy != null) proxy.stop();
            decoy.stop(0);
        }
    }

    @Test
    public void nonLoopbackStopFailsBeforeSendingAnyRequest() throws Exception
    {
        AtomicInteger trapped = new AtomicInteger();
        HttpServer decoy = decoy(trapped);
        try
        {
            Path file = config("192.0.2.20", decoy.getAddress().getPort());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream output = new PrintStream(bytes))
            {
                assertEquals(1, CliCommands.stop(new String[] { "--config", file.toString() }, output, output));
            }
            assertEquals(0, trapped.get());
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("loopback"));
        }
        finally
        {
            decoy.stop(0);
        }
    }

    private Path config(String bind, int port) throws Exception
    {
        Path file = temporary.newFile().toPath();
        Files.writeString(file, "bind=" + bind + "\nport=" + port
            + "\nscan=2-1\nauthToken=test-destination-key\n");
        return file;
    }

    private HttpServer decoy(AtomicInteger requests) throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"status\":\"ok\",\"role\":\"proxy\"}".getBytes(StandardCharsets.UTF_8);
            try
            {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            finally
            {
                exchange.close();
            }
        });
        server.start();
        return server;
    }
}
