/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link ProxyConfig}: defaults, CLI parsing, env parsing, precedence
 * (CLI &gt; env &gt; defaults) and invalid-value diagnostics.
 */
public class ProxyConfigTest
{
    private static final String[] NO_ARGS = new String[0];

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testDefaults()
    {
        ProxyConfig cfg = ProxyConfig.parse(NO_ARGS, Map.of());

        assertEquals(8764, cfg.port);
        assertEquals(8765, cfg.scanFrom);
        assertEquals(8774, cfg.scanTo);
        assertEquals(20, cfg.refreshSeconds);
        assertEquals(300, cfg.backendTimeoutSeconds);
        // Security default: loopback only, no bind host configured.
        assertFalse(cfg.allowRemote);
        assertNull(cfg.bindHost);
    }

    @Test
    public void testNullArgsAndEnvGiveDefaults()
    {
        ProxyConfig cfg = ProxyConfig.parse(null, null);

        assertEquals(8764, cfg.port);
        assertEquals(8765, cfg.scanFrom);
        assertEquals(8774, cfg.scanTo);
        assertEquals(20, cfg.refreshSeconds);
        assertEquals(300, cfg.backendTimeoutSeconds);
        assertFalse(cfg.allowRemote);
        assertNull(cfg.bindHost);
    }

    @Test
    public void testBindOptionSetsAllowRemoteAndHost()
    {
        ProxyConfig cfg = ProxyConfig.parse(new String[] { "--bind", "0.0.0.0" }, Map.of());

        assertTrue("--bind must opt into allowRemote", cfg.allowRemote);
        assertEquals("0.0.0.0", cfg.bindHost);
        // Every other setting stays at its default.
        assertEquals(8764, cfg.port);
    }

    @Test
    public void testHostEnvVariableSetsAllowRemoteAndHost()
    {
        Map<String, String> env = Map.of(ProxyConfig.ENV_HOST, "192.168.1.5");

        ProxyConfig cfg = ProxyConfig.parse(NO_ARGS, env);

        assertTrue("EDT_MCP_PROXY_HOST must opt into allowRemote", cfg.allowRemote);
        assertEquals("192.168.1.5", cfg.bindHost);
    }

    @Test
    public void testBindCliWinsOverHostEnv()
    {
        Map<String, String> env = Map.of(ProxyConfig.ENV_HOST, "10.0.0.1");
        String[] args = { "--bind", "0.0.0.0" };

        ProxyConfig cfg = ProxyConfig.parse(args, env);

        assertTrue(cfg.allowRemote);
        assertEquals("CLI --bind must win over " + ProxyConfig.ENV_HOST, "0.0.0.0", cfg.bindHost);
    }

    @Test
    public void testBlankHostEnvValueIgnored()
    {
        Map<String, String> env = Map.of(ProxyConfig.ENV_HOST, "   ");

        ProxyConfig cfg = ProxyConfig.parse(NO_ARGS, env);

        assertFalse(cfg.allowRemote);
        assertNull(cfg.bindHost);
    }

    @Test
    public void testBindOptionRejectsEmptyHost()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--bind", "   " }, Map.of()));

        assertTrue(e.getMessage().contains("--bind"));
    }

    @Test
    public void testBindOptionRequiresAValue()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--bind" }, Map.of()));

        assertTrue(e.getMessage().contains("--bind"));
        assertTrue(e.getMessage().contains("requires a value"));
    }

    @Test
    public void testCliArguments()
    {
        String[] args = { "--port", "9000", "--scan", "9001-9005", "--refresh", "5", "--timeout", "60" };

        ProxyConfig cfg = ProxyConfig.parse(args, Map.of());

        assertEquals(9000, cfg.port);
        assertEquals(9001, cfg.scanFrom);
        assertEquals(9005, cfg.scanTo);
        assertEquals(5, cfg.refreshSeconds);
        assertEquals(60, cfg.backendTimeoutSeconds);
    }

    @Test
    public void testEnvVariables()
    {
        Map<String, String> env = Map.of(
            ProxyConfig.ENV_PORT, "9100",
            ProxyConfig.ENV_SCAN, "9101-9110",
            ProxyConfig.ENV_REFRESH, "7",
            ProxyConfig.ENV_TIMEOUT, "42");

        ProxyConfig cfg = ProxyConfig.parse(NO_ARGS, env);

        assertEquals(9100, cfg.port);
        assertEquals(9101, cfg.scanFrom);
        assertEquals(9110, cfg.scanTo);
        assertEquals(7, cfg.refreshSeconds);
        assertEquals(42, cfg.backendTimeoutSeconds);
    }

    @Test
    public void testCliWinsOverEnv()
    {
        Map<String, String> env = Map.of(
            ProxyConfig.ENV_PORT, "9100",
            ProxyConfig.ENV_SCAN, "9101-9110");
        String[] args = { "--port", "9000" };

        ProxyConfig cfg = ProxyConfig.parse(args, env);

        // CLI value wins where given; env value applies where the CLI is silent.
        assertEquals(9000, cfg.port);
        assertEquals(9101, cfg.scanFrom);
        assertEquals(9110, cfg.scanTo);
        // Defaults apply where neither CLI nor env says anything.
        assertEquals(20, cfg.refreshSeconds);
        assertEquals(300, cfg.backendTimeoutSeconds);
    }

    @Test
    public void testPortZeroAllowedForEphemeralBinding()
    {
        ProxyConfig cfg = ProxyConfig.parse(new String[] { "--port", "0" }, Map.of());

        assertEquals(0, cfg.port);
    }

    @Test
    public void testInvertedScanRangeAcceptedAsEmpty()
    {
        // FROM > TO is the documented way to configure an EMPTY scan range (zero backends).
        ProxyConfig cfg = ProxyConfig.parse(new String[] { "--scan", "9002-9001" }, Map.of());

        assertEquals(9002, cfg.scanFrom);
        assertEquals(9001, cfg.scanTo);
    }

    @Test
    public void testSinglePortScanRange()
    {
        ProxyConfig cfg = ProxyConfig.parse(new String[] { "--scan", "8765-8765" }, Map.of());

        assertEquals(8765, cfg.scanFrom);
        assertEquals(8765, cfg.scanTo);
    }

    @Test
    public void testBlankEnvValueIgnored()
    {
        Map<String, String> env = Map.of(ProxyConfig.ENV_PORT, "   ");

        ProxyConfig cfg = ProxyConfig.parse(NO_ARGS, env);

        assertEquals(8764, cfg.port);
    }

    @Test
    public void testInvalidPortNotANumber()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--port", "abc" }, Map.of()));

        assertTrue("message should name the option: " + e.getMessage(), e.getMessage().contains("--port"));
        assertTrue("message should name the bad value: " + e.getMessage(), e.getMessage().contains("abc"));
    }

    @Test
    public void testInvalidPortOutOfRange()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--port", "70000" }, Map.of()));

        assertTrue(e.getMessage().contains("--port"));
    }

    @Test
    public void testNegativePortRejected()
    {
        // "-1" contains no separator issue for --port; it is simply out of the 0-65535 range.
        assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--port", "-1" }, Map.of()));
    }

    @Test
    public void testMissingOptionValue()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--port" }, Map.of()));

        assertTrue(e.getMessage().contains("--port"));
        assertTrue(e.getMessage().contains("requires a value"));
    }

    @Test
    public void testUnknownOption()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--frobnicate", "1" }, Map.of()));

        assertTrue(e.getMessage().contains("--frobnicate"));
        assertTrue(e.getMessage().contains("--help"));
    }

    @Test
    public void testInvalidScanFormatNoDash()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--scan", "8765" }, Map.of()));

        assertTrue(e.getMessage().contains("FROM-TO"));
    }

    @Test
    public void testInvalidScanFormatMissingBound()
    {
        assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--scan", "8765-" }, Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--scan", "-8774" }, Map.of()));
    }

    @Test
    public void testInvalidScanPortZeroRejected()
    {
        // Unlike the listen port, scan ports must be 1-65535 (0 is not probeable).
        assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--scan", "0-10" }, Map.of()));
    }

    @Test
    public void testInvalidRefreshZero()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--refresh", "0" }, Map.of()));

        assertTrue(e.getMessage().contains("--refresh"));
    }

    @Test
    public void testInvalidTimeoutNegative()
    {
        assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--timeout", "-5" }, Map.of()));
    }

    @Test
    public void testInvalidEnvValueNamesTheVariable()
    {
        Map<String, String> env = Map.of(ProxyConfig.ENV_PORT, "not-a-port");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(NO_ARGS, env));

        assertTrue("message should name the env variable: " + e.getMessage(),
            e.getMessage().contains(ProxyConfig.ENV_PORT));
    }

    @Test
    public void testInvalidEnvScanRange()
    {
        Map<String, String> env = Map.of(ProxyConfig.ENV_SCAN, "oops");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(NO_ARGS, env));

        assertTrue(e.getMessage().contains(ProxyConfig.ENV_SCAN));
    }

    @Test
    public void testUsageMentionsEveryOptionAndEnvVariable()
    {
        String usage = ProxyConfig.usage();

        assertTrue(usage.contains("--port"));
        assertTrue(usage.contains("--scan"));
        assertTrue(usage.contains("--refresh"));
        assertTrue(usage.contains("--timeout"));
        assertTrue(usage.contains("--bind"));
        assertTrue(usage.contains("--config"));
        assertTrue(usage.contains("--help"));
        assertTrue(usage.contains(ProxyConfig.ENV_PORT));
        assertTrue(usage.contains(ProxyConfig.ENV_SCAN));
        assertTrue(usage.contains(ProxyConfig.ENV_REFRESH));
        assertTrue(usage.contains(ProxyConfig.ENV_TIMEOUT));
        assertTrue(usage.contains(ProxyConfig.ENV_HOST));
    }

    // ------------------------------------------------------------------------------------
    // Config file (issue #253 follow-up, spec section 3).
    // ------------------------------------------------------------------------------------

    @Test
    public void testConfigFileSuppliesAllFiveKeys() throws IOException
    {
        Path file = writeConfigFile(
            "port=9200",
            "scan=9201-9210",
            "refresh=15",
            "timeout=90",
            "bind=0.0.0.0");

        ProxyConfig cfg = ProxyConfig.parse(new String[] { "--config", file.toString() }, Map.of());

        assertEquals(9200, cfg.port);
        assertEquals(9201, cfg.scanFrom);
        assertEquals(9210, cfg.scanTo);
        assertEquals(15, cfg.refreshSeconds);
        assertEquals(90, cfg.backendTimeoutSeconds);
        assertTrue("bind in the config file must opt into allowRemote", cfg.allowRemote);
        assertEquals("0.0.0.0", cfg.bindHost);
    }

    @Test
    public void testConfigFileOnlyAppliesWhenNeitherCliNorEnvOverride() throws IOException
    {
        Path file = writeConfigFile("port=9200");

        ProxyConfig cfg = ProxyConfig.parse(new String[] { "--config", file.toString() }, Map.of());

        assertEquals("the config-file value applies when nothing else says otherwise", 9200, cfg.port);
        // Every other field still falls back to the built-in default.
        assertEquals(8765, cfg.scanFrom);
        assertEquals(8774, cfg.scanTo);
        assertEquals(20, cfg.refreshSeconds);
        assertEquals(300, cfg.backendTimeoutSeconds);
    }

    @Test
    public void testEnvBeatsConfigFile() throws IOException
    {
        Path file = writeConfigFile("port=9200");
        Map<String, String> env = Map.of(ProxyConfig.ENV_PORT, "9300");

        ProxyConfig cfg = ProxyConfig.parse(new String[] { "--config", file.toString() }, env);

        assertEquals("env must win over the config file", 9300, cfg.port);
    }

    @Test
    public void testCliBeatsEnvBeatsConfigFile() throws IOException
    {
        Path file = writeConfigFile("port=9200");
        Map<String, String> env = Map.of(ProxyConfig.ENV_PORT, "9300");
        String[] args = { "--config", file.toString(), "--port", "9400" };

        ProxyConfig cfg = ProxyConfig.parse(args, env);

        assertEquals("CLI must win over both env and the config file", 9400, cfg.port);
    }

    @Test
    public void testInvalidConfigFileValueNamesTheSourceFileAndKey() throws IOException
    {
        // No dash: fails the FROM-TO shape check itself instead of being parsed as a range
        // whose FROM half is the reported bad token (matching parseScanRange's own behaviour).
        Path file = writeConfigFile("scan=oops");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--config", file.toString() }, Map.of()));

        assertTrue("message should name the config file: " + e.getMessage(),
            e.getMessage().contains("config file"));
        assertTrue("message should name the file path: " + e.getMessage(),
            e.getMessage().contains(file.toString()));
        assertTrue("message should name the bad key: " + e.getMessage(), e.getMessage().contains("scan"));
        assertTrue("message should name the bad value: " + e.getMessage(), e.getMessage().contains("oops"));
    }

    @Test
    public void testMissingExplicitConfigFileFailsFastNamingThePath()
    {
        String missingPath = tempFolder.getRoot().toPath().resolve("does-not-exist.properties").toString();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--config", missingPath }, Map.of()));

        assertTrue("message should name the missing path: " + e.getMessage(), e.getMessage().contains(missingPath));
    }

    @Test
    public void testConfigOptionRequiresAValue()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ProxyConfig.parse(new String[] { "--config" }, Map.of()));

        assertTrue(e.getMessage().contains("--config"));
        assertTrue(e.getMessage().contains("requires a value"));
    }

    @Test
    public void testBlankConfigFilePropertyIgnored() throws IOException
    {
        Path file = writeConfigFile("port=   ");

        ProxyConfig cfg = ProxyConfig.parse(new String[] { "--config", file.toString() }, Map.of());

        assertEquals("a blank property value must be treated as absent, falling back to the default",
            8764, cfg.port);
    }

    @Test
    public void testAutoDiscoverySkippedCleanlyWhenNoMatchingFileExists()
    {
        // No --config given and (in the test/build environment) no edt-mcp-proxy.properties
        // sits next to the test classes - auto-discovery must find nothing and simply fall
        // back to the built-in defaults, never throwing.
        ProxyConfig cfg = ProxyConfig.parse(NO_ARGS, Map.of());

        assertEquals(8764, cfg.port);
        assertEquals(8765, cfg.scanFrom);
        assertEquals(8774, cfg.scanTo);
    }

    @Test
    public void testJarDirDiscoveryHelperNeverThrows()
    {
        // Package-private test seam: under Surefire the code source is a directory (not a
        // packaged jar), and in other class-loading environments it can be entirely absent
        // (CodeSource == null / getLocation() == null) - either way this must degrade to a
        // Path or null, never an exception (the "no jar dir" contract from the follow-up spec).
        Path discovered = ProxyConfig.discoverConfigFileNextToJar();

        if (discovered != null)
        {
            assertTrue("a discovered path must point at the auto-discovery file name: " + discovered,
                discovered.toString().endsWith("edt-mcp-proxy.properties"));
        }
    }

    /**
     * Writes a {@code java.util.Properties}-format file with the given raw {@code key=value}
     * lines into the JUnit temp folder.
     */
    private Path writeConfigFile(String... lines) throws IOException
    {
        Path file = tempFolder.newFile("edt-mcp-proxy-test.properties").toPath();
        Files.write(file, Arrays.asList(lines));
        return file;
    }
}
