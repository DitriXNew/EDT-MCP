/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link BslLsRunner}'s resolution logic — the parts that decide WHICH jar,
 * Java and configuration file are used, without spawning the engine. The subprocess
 * path itself is validated live/e2e (it needs the real jar + a Java 21). These tests
 * cover the deterministic, side-effect-free resolution rules on temp files; they
 * avoid asserting the env-var branch because the ambient environment differs between
 * the developer machine (where {@code EDT_MCP_BSL_LS_*} are set) and CI.
 * <p>
 * Uses {@code java.nio.file} temp dirs directly rather than JUnit's
 * {@code TemporaryFolder}, which the Tycho target platform treats as non-API.
 */
public class BslLsRunnerTest
{
    private Path root;

    @Before
    public void setUp() throws IOException
    {
        root = Files.createTempDirectory("bslls-test");
    }

    @After
    public void tearDown() throws IOException
    {
        if (root != null && Files.exists(root))
        {
            Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try
                    {
                        Files.deleteIfExists(p);
                    }
                    catch (IOException ignored)
                    {
                        // best effort
                    }
                });
        }
    }

    @Test
    public void testJarOverrideWinsWhenItIsAFile() throws IOException
    {
        File jar = newFile("bsl-language-server-1.0.3-exec.jar");
        assertEquals(jar, BslLsRunner.resolveJar(jar));
    }

    @Test
    public void testBogusJarOverrideIsNeverReturned()
    {
        File bogus = new File(root.toFile(), "does-not-exist-exec.jar");
        File resolved = BslLsRunner.resolveJar(bogus);
        // Falls through to env/default: whatever comes back, it is never the bogus path
        // and, if anything, it is a real existing file.
        assertFalse(bogus.equals(resolved));
        if (resolved != null)
        {
            assertTrue(resolved.isFile());
        }
    }

    @Test
    public void testJavaOverrideWins() throws IOException
    {
        File java = newFile("java.exe");
        assertEquals(java, BslLsRunner.resolveJava(java));
    }

    @Test
    public void testJavaAlwaysResolvableViaJavaHomeFallback()
    {
        // No override: env or the java.home of the running JVM must yield a launcher.
        assertNotNull(BslLsRunner.resolveJava(null));
    }

    @Test
    public void testConfigPrefersProjectConfig() throws IOException
    {
        File jarDir = newFolder("engine");
        File jar = new File(jarDir, "bsl-language-server-1.0.3-exec.jar");
        assertTrue(jar.createNewFile());
        File engineHomeConfig = new File(jarDir, ".bsl-language-server.json");
        assertTrue(engineHomeConfig.createNewFile());

        File projectConfig = newFile(".bsl-language-server.json");
        assertEquals(projectConfig, BslLsRunner.resolveConfig(projectConfig, jar));
    }

    @Test
    public void testConfigFallsBackToEngineHome() throws IOException
    {
        File jarDir = newFolder("engine");
        File jar = new File(jarDir, "bsl-language-server-1.0.3-exec.jar");
        assertTrue(jar.createNewFile());
        File engineHomeConfig = new File(jarDir, ".bsl-language-server.json");
        assertTrue(engineHomeConfig.createNewFile());

        assertEquals(engineHomeConfig, BslLsRunner.resolveConfig(null, jar));
    }

    @Test
    public void testConfigNullWhenNeitherPresent() throws IOException
    {
        File jarDir = newFolder("engine");
        File jar = new File(jarDir, "bsl-language-server-1.0.3-exec.jar");
        assertTrue(jar.createNewFile());
        // No sibling config, no project config.
        assertNull(BslLsRunner.resolveConfig(null, jar));
    }

    @Test
    public void testRunRejectsMissingSourceDirectory()
    {
        File missing = new File(root.toFile(), "no-such-src");
        BslLsRunner.Result result = BslLsRunner.run(new BslLsRunner.Request(missing));
        assertFalse(result.ok());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("Source directory"));
    }

    @Test
    public void testRunRejectsNullRequest()
    {
        BslLsRunner.Result result = BslLsRunner.run(null);
        assertFalse(result.ok());
        assertNotNull(result.errorMessage());
    }

    private File newFile(String name) throws IOException
    {
        File f = new File(root.toFile(), name);
        assertTrue(f.createNewFile());
        return f;
    }

    private File newFolder(String name)
    {
        File f = new File(root.toFile(), name);
        assertTrue(f.mkdirs());
        return f;
    }
}
