/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Unit tests for {@link CliCommands} that need no network: the subcommand-aware {@code --help}
 * usage text, the {@code --version} line, and {@code status}/{@code stop} argument validation
 * (bad {@code --port} values exit {@code 2} before any HTTP call is attempted). The reachable/
 * unreachable-proxy behaviour of {@code status}/{@code stop} is covered end-to-end by
 * {@link CliIT} instead.
 */
public class CliCommandsTest
{
    @Test
    public void testUsageMentionsAllThreeSubcommands()
    {
        String usage = CliCommands.usage();

        assertTrue(usage.contains("serve")); //$NON-NLS-1$
        assertTrue(usage.contains("status")); //$NON-NLS-1$
        assertTrue(usage.contains("stop")); //$NON-NLS-1$
    }

    @Test
    public void testUsageMentionsHelpAndVersionFlags()
    {
        String usage = CliCommands.usage();

        assertTrue(usage.contains("--help")); //$NON-NLS-1$
        assertTrue(usage.contains("--version")); //$NON-NLS-1$
    }

    @Test
    public void testUsageMentionsEveryServeOptionAndEnvVariable()
    {
        // usage() embeds ProxyConfig.usage() so --help shows subcommands + options + env vars
        // in one place (spec section 2).
        String usage = CliCommands.usage();

        assertTrue(usage.contains("--port")); //$NON-NLS-1$
        assertTrue(usage.contains("--scan")); //$NON-NLS-1$
        assertTrue(usage.contains("--refresh")); //$NON-NLS-1$
        assertTrue(usage.contains("--timeout")); //$NON-NLS-1$
        assertTrue(usage.contains("--bind")); //$NON-NLS-1$
        assertTrue(usage.contains("--config")); //$NON-NLS-1$
        assertTrue(usage.contains(ProxyConfig.ENV_PORT));
        assertTrue(usage.contains(ProxyConfig.ENV_SCAN));
        assertTrue(usage.contains(ProxyConfig.ENV_REFRESH));
        assertTrue(usage.contains(ProxyConfig.ENV_TIMEOUT));
        assertTrue(usage.contains(ProxyConfig.ENV_HOST));
    }

    @Test
    public void testVersionLineIncludesProxyNameAndAVersion()
    {
        String line = CliCommands.versionLine();

        assertTrue("version line must identify the proxy: " + line, line.startsWith("edt-mcp-proxy ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("version line must carry a non-empty version after the name: " + line, //$NON-NLS-1$
            line.length() > "edt-mcp-proxy ".length()); //$NON-NLS-1$
    }

    @Test
    public void testStatusRejectsUnknownOptionWithExitCodeTwo()
    {
        Captured captured = runStatus("--bogus", "1"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(2, captured.exitCode);
        assertTrue("message should name the bad option: " + captured.err, captured.err.contains("--bogus")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStatusRequiresAPortValue()
    {
        Captured captured = runStatus("--port"); //$NON-NLS-1$

        assertEquals(2, captured.exitCode);
        assertTrue(captured.err.contains("requires a value")); //$NON-NLS-1$
    }

    @Test
    public void testStatusRejectsANonNumericPort()
    {
        Captured captured = runStatus("--port", "not-a-port"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(2, captured.exitCode);
        assertTrue(captured.err.contains("not-a-port")); //$NON-NLS-1$
    }

    @Test
    public void testStopRejectsUnknownOptionWithExitCodeTwo()
    {
        Captured captured = runStop("--bogus", "1"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(2, captured.exitCode);
        assertTrue("message should name the bad option: " + captured.err, captured.err.contains("--bogus")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Captured runStatus(String... args)
    {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8))
        {
            exitCode = CliCommands.status(args, out, err);
        }
        return new Captured(exitCode, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
    }

    private static Captured runStop(String... args)
    {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8))
        {
            exitCode = CliCommands.stop(args, out, err);
        }
        return new Captured(exitCode, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
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
