/**
 * MCP Server for EDT - Proxy Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.ProxyFixture;

/**
 * End-to-end coverage of the CLI subcommands added by the issue #253 follow-up spec: {@code
 * status} and {@code stop} run against a REAL in-process proxy (the same {@link ProxyFixture}
 * the routing ITs use) by calling the exact package-private static methods {@link Main}
 * dispatches to ({@link CliCommands#status} / {@link CliCommands#stop}), capturing their output
 * instead of touching {@link System#out}/{@link System#err} or forking a process.
 */
public class CliIT
{
    /** Hard cap per test so a transport hang fails fast instead of wedging the build. */
    @Rule
    public final Timeout globalTimeout = Timeout.seconds(60);

    private static final String STATUS_PROJECT = "StatusProject"; //$NON-NLS-1$

    private FakeBackend backend;
    private ProxyFixture proxy;

    /** Stops the proxy and whatever backend a scenario started. */
    @After
    public void tearDown()
    {
        if (proxy != null)
        {
            proxy.stop();
        }
        ProxyRoutingIT.stopQuietly(backend);
    }

    /**
     * {@code status} against a proxy with one live backend must print a table naming that
     * backend's port and the project it serves, and exit {@code 0}.
     */
    @Test
    public void testStatusPrintsBackendPortAndProject() throws Exception
    {
        int[] ports = ProxyRoutingIT.reserveFreePorts(1);
        backend = new FakeBackend(ports[0], List.of(STATUS_PROJECT));
        backend.start();
        proxy = new ProxyFixture(ports[0], ports[0]);
        proxy.start();

        Captured captured = runCommand((out, err) -> CliCommands.status(
            new String[] { "--port", String.valueOf(proxy.port()) }, out, err)); //$NON-NLS-1$

        assertEquals("status must exit 0 when the proxy is reachable: " + captured.err, //$NON-NLS-1$
            0, captured.exitCode);
        assertTrue("status table must mention the backend port: " + captured.out, //$NON-NLS-1$
            captured.out.contains(String.valueOf(ports[0])));
        assertTrue("status table must mention the project it serves: " + captured.out, //$NON-NLS-1$
            captured.out.contains(STATUS_PROJECT));
    }

    /**
     * {@code status} against a port nothing listens on must exit {@code 1} with an actionable
     * message naming that port.
     */
    @Test
    public void testStatusUnreachableExitsOneWithActionableMessage() throws Exception
    {
        int deadPort = ProxyRoutingIT.reserveFreePorts(1)[0]; // reserved then released - nothing listens

        Captured captured = runCommand(
            (out, err) -> CliCommands.status(new String[] { "--port", String.valueOf(deadPort) }, out, err)); //$NON-NLS-1$

        assertEquals(1, captured.exitCode);
        assertTrue("the unreachable message must name the port: " + captured.err, //$NON-NLS-1$
            captured.err.contains(String.valueOf(deadPort)));
    }

    /**
     * {@code stop} against a running proxy must be accepted (exit {@code 0}) and the proxy must
     * actually stop shortly afterwards (its {@code /health} stops answering).
     */
    @Test
    public void testStopActuallyStopsTheProxy() throws Exception
    {
        int deadPort = ProxyRoutingIT.reserveFreePorts(1)[0]; // no backend needed for this scenario
        proxy = new ProxyFixture(deadPort, deadPort);
        proxy.start();
        int proxyPort = proxy.port();

        Captured captured = runCommand(
            (out, err) -> CliCommands.stop(new String[] { "--port", String.valueOf(proxyPort) }, out, err)); //$NON-NLS-1$

        assertEquals("stop must exit 0 when accepted: " + captured.err, 0, captured.exitCode); //$NON-NLS-1$
        assertTrue("stop must confirm the port it stopped: " + captured.out, //$NON-NLS-1$
            captured.out.contains(String.valueOf(proxyPort)));

        ProxyRoutingIT.awaitHealthUnreachable(proxyPort, 10_000);
    }

    /**
     * {@code stop} against a port nothing listens on must exit {@code 1} with an actionable
     * message naming that port.
     */
    @Test
    public void testStopUnreachableExitsOneWithActionableMessage() throws Exception
    {
        int deadPort = ProxyRoutingIT.reserveFreePorts(1)[0];

        Captured captured = runCommand(
            (out, err) -> CliCommands.stop(new String[] { "--port", String.valueOf(deadPort) }, out, err)); //$NON-NLS-1$

        assertEquals(1, captured.exitCode);
        assertTrue("the unreachable message must name the port: " + captured.err, //$NON-NLS-1$
            captured.err.contains(String.valueOf(deadPort)));
    }

    /** Runs a {@code (PrintStream, PrintStream) -> int} CLI command, capturing stdout/stderr as strings. */
    private static Captured runCommand(CliInvocation invocation)
    {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8))
        {
            exitCode = invocation.run(out, err);
        }
        return new Captured(exitCode, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface CliInvocation
    {
        int run(PrintStream out, PrintStream err);
    }

    private static final class Captured
    {
        final int exitCode;
        final String out;
        final String err;

        Captured(int exitCode, String out, String err)
        {
            this.exitCode = exitCode;
            this.out = out;
            this.err = err;
        }
    }
}
