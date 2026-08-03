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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link BslLsRunner}: the resolution logic (which jar/Java/config is used) AND, via a
 * small compiled-on-the-fly "fixture jar" standing in for the real engine (see
 * {@link #buildFixtureJar()}), the subprocess PLUMBING itself — report discovery under
 * {@code --outputDir}, stdout-capture bounding, and the oversized-report guard. This closes a real
 * gap: the actual BSL Language Server engine's happy-path e2e tests SKIP entirely when its
 * multi-hundred-MB jar is not installed, so without a fixture the subprocess code path in
 * {@link BslLsRunner#run} was never exercised in CI at all — only the pure resolution rules were.
 * The engine's own DIAGNOSTIC correctness (what rules it reports, on real BSL source) still needs
 * the real jar and stays live/e2e; the fixture only proves the runner's OWN process handling.
 * <p>
 * The resolution tests avoid asserting the env-var branch because the ambient environment differs
 * between the developer machine (where {@code EDT_MCP_BSL_LS_*} are set) and CI.
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

    // ==================== Command-line construction (finding: --workspaceDir scoping) ====================

    @Test
    public void testBuildCommandPassesWorkspaceDirWhenSet() throws IOException
    {
        File javaExe = newFile("java.exe");
        File jar = newFile("bsl-language-server-1.0.3-exec.jar");
        File srcDir = newFolder("module-folder");
        File workspaceRoot = newFolder("project-src-root");
        Path outputDir = root.resolve("out");

        BslLsRunner.Request request = new BslLsRunner.Request(srcDir).workspaceDir(workspaceRoot);
        List<String> command = BslLsRunner.buildCommand(javaExe, jar, null, request, outputDir);

        assertTrue("--workspaceDir must be passed explicitly", command.contains("--workspaceDir"));
        int workspaceIdx = command.indexOf("--workspaceDir");
        assertEquals("--workspaceDir must point at the project's own workspace root, not the "
            + "(possibly narrower) analyzed --srcDir", workspaceRoot.getAbsolutePath(), command.get(workspaceIdx + 1));

        int srcDirIdx = command.indexOf("--srcDir");
        assertTrue("--srcDir must still be passed", srcDirIdx >= 0);
        assertEquals(srcDir.getAbsolutePath(), command.get(srcDirIdx + 1));
        // The two must differ here: this is exactly the single-module-narrows-srcDir case
        // --workspaceDir must stay pinned against.
        assertFalse(command.get(srcDirIdx + 1).equals(command.get(workspaceIdx + 1)));
    }

    @Test
    public void testBuildCommandDefaultsWorkspaceDirToSrcDirWhenUnset() throws IOException
    {
        File javaExe = newFile("java.exe");
        File jar = newFile("bsl-language-server-1.0.3-exec.jar");
        File srcDir = newFolder("whole-project-src");
        Path outputDir = root.resolve("out");

        BslLsRunner.Request request = new BslLsRunner.Request(srcDir);
        List<String> command = BslLsRunner.buildCommand(javaExe, jar, null, request, outputDir);

        int workspaceIdx = command.indexOf("--workspaceDir");
        assertTrue("--workspaceDir must be passed even without an explicit override", workspaceIdx >= 0);
        assertEquals(srcDir.getAbsolutePath(), command.get(workspaceIdx + 1));
    }

    @Test
    public void testBuildCommandIncludesConfigurationWhenConfigResolved() throws IOException
    {
        File javaExe = newFile("java.exe");
        File jar = newFile("bsl-language-server-1.0.3-exec.jar");
        File srcDir = newFolder("src");
        File config = newFile(".bsl-language-server.json");
        Path outputDir = root.resolve("out");

        BslLsRunner.Request request = new BslLsRunner.Request(srcDir);
        List<String> command = BslLsRunner.buildCommand(javaExe, jar, config, request, outputDir);

        int configIdx = command.indexOf("--configuration");
        assertTrue("--configuration must be passed when a config file resolved", configIdx >= 0);
        assertEquals(config.getAbsolutePath(), command.get(configIdx + 1));
    }

    @Test
    public void testBuildCommandOmitsConfigurationWhenNoneResolved() throws IOException
    {
        File javaExe = newFile("java.exe");
        File jar = newFile("bsl-language-server-1.0.3-exec.jar");
        File srcDir = newFolder("src-no-config");
        Path outputDir = root.resolve("out");

        BslLsRunner.Request request = new BslLsRunner.Request(srcDir);
        List<String> command = BslLsRunner.buildCommand(javaExe, jar, null, request, outputDir);

        assertFalse("--configuration must be omitted, not passed with a null path",
            command.contains("--configuration"));
    }

    @Test
    public void testResolveWorkspaceDirPrefersExplicitOverride() throws IOException
    {
        File srcDir = newFolder("scoped-dir");
        File workspaceRoot = newFolder("project-root");
        BslLsRunner.Request request = new BslLsRunner.Request(srcDir).workspaceDir(workspaceRoot);
        assertEquals(workspaceRoot, BslLsRunner.resolveWorkspaceDir(request));
    }

    @Test
    public void testResolveWorkspaceDirFallsBackToSrcDir() throws IOException
    {
        File srcDir = newFolder("scoped-dir-2");
        BslLsRunner.Request request = new BslLsRunner.Request(srcDir);
        assertEquals(srcDir, BslLsRunner.resolveWorkspaceDir(request));
    }

    // ==================== Jar version comparison (finding: lexicographic sort picks the wrong "newest") ====================

    @Test
    public void testCompareVersionsMinorVersionNumericNotLexicographic()
    {
        // The exact bug: "1.9.0" lexicographically sorts AFTER "1.10.0" ('9' > '1'), which would
        // keep the OLDER jar. Numeric comparison must get this the other way round.
        assertTrue("1.10.0 must be newer than 1.9.0", BslLsRunner.compareVersions("1.10.0", "1.9.0") > 0);
        assertTrue("1.9.0 must be older than 1.10.0", BslLsRunner.compareVersions("1.9.0", "1.10.0") < 0);
    }

    @Test
    public void testCompareVersionsAcrossMajorLines()
    {
        assertTrue("1.0.0 must be newer than 0.28.0 (the two claimed major lines)",
            BslLsRunner.compareVersions("1.0.0", "0.28.0") > 0);
    }

    @Test
    public void testCompareVersionsEqualPadsMissingComponentsWithZero()
    {
        assertEquals("1.9 must equal 1.9.0 (missing trailing component defaults to 0)",
            0, BslLsRunner.compareVersions("1.9", "1.9.0"));
    }

    @Test
    public void testCompareVersionsIdentical()
    {
        assertEquals(0, BslLsRunner.compareVersions("1.10.0", "1.10.0"));
    }

    @Test
    public void testCompareVersionsPreReleaseSuffixDoesNotThrow()
    {
        // Full SemVer pre-release precedence is not implemented (see the method's javadoc) - this
        // only needs to stay deterministic and not throw on a non-numeric trailing component.
        int result = BslLsRunner.compareVersions("1.10.0-rc1", "1.10.0");
        assertEquals("the same comparison must be stable across repeated calls",
            result, BslLsRunner.compareVersions("1.10.0-rc1", "1.10.0"));
    }

    @Test
    public void testExtractVersionParsesTheExpectedShape()
    {
        assertEquals("1.10.0", BslLsRunner.extractVersion("bsl-language-server-1.10.0-exec.jar"));
        assertEquals("0.28.0", BslLsRunner.extractVersion("bsl-language-server-0.28.0-exec.jar"));
    }

    @Test
    public void testExtractVersionRejectsUnexpectedShape()
    {
        assertNull(BslLsRunner.extractVersion("some-other-tool-1.0.0-exec.jar"));
        assertNull(BslLsRunner.extractVersion("bsl-language-server-1.10.0.jar"));
        assertNull(BslLsRunner.extractVersion(null));
    }

    @Test
    public void testCompareJarVersionsPicksNewerMinorNotLexicographic()
    {
        assertTrue("bsl-language-server-1.10.0-exec.jar must outrank ...-1.9.0-exec.jar",
            BslLsRunner.compareJarVersions("bsl-language-server-1.10.0-exec.jar",
                "bsl-language-server-1.9.0-exec.jar") > 0);
    }

    @Test
    public void testCompareJarVersionsFallsBackToFilenameWhenUnparseable()
    {
        // Neither name matches the expected shape - must still return a deterministic result
        // rather than throwing.
        int result = BslLsRunner.compareJarVersions("weird-a.jar", "weird-b.jar");
        assertEquals("weird-a.jar".compareTo("weird-b.jar"), result);
    }

    @Test
    public void testScanForExecJarPicksNumericallyNewestNotLexicographicallyLargest() throws IOException
    {
        File dir = newFolder("multi-version-engine");
        assertTrue(new File(dir, "bsl-language-server-1.9.0-exec.jar").createNewFile());
        assertTrue(new File(dir, "bsl-language-server-1.10.0-exec.jar").createNewFile());
        assertTrue(new File(dir, "bsl-language-server-0.28.0-exec.jar").createNewFile());

        File best = BslLsRunner.scanForExecJar(dir);

        assertNotNull(best);
        assertEquals("bsl-language-server-1.10.0-exec.jar", best.getName());
    }

    @Test
    public void testScanForExecJarReturnsNullWhenDirectoryHasNoMatch() throws IOException
    {
        File dir = newFolder("empty-engine-dir");
        assertNull(BslLsRunner.scanForExecJar(dir));
    }

    // ==================== Managed fixture process: real subprocess plumbing without the real engine ====================

    @Test
    public void testFixtureRunProducesAParseableReport() throws Exception
    {
        File fixtureJar = buildFixtureJar();
        Assume.assumeTrue("no system Java compiler available to build the fixture jar " //$NON-NLS-1$
            + "(a JRE-only test runtime?) - skip", fixtureJar != null); //$NON-NLS-1$
        File srcDir = newFolder("fixture-src-normal"); //$NON-NLS-1$

        BslLsRunner.Request request = new BslLsRunner.Request(srcDir)
            .jarOverride(fixtureJar).javaOverride(currentJavaExecutable());
        BslLsRunner.Result result = BslLsRunner.run(request);

        assertTrue("fixture run must succeed: " + (result.ok() ? "" : result.errorMessage()), result.ok()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(result.report());
    }

    @Test
    public void testFixtureRunWithHugeStdoutStillSucceedsWithBoundedCapture() throws Exception
    {
        // The fixture prints ~10 MB of stdout (well over MAX_CAPTURED_OUTPUT_CHARS) THEN writes a
        // normal small report - proving the bounded drain neither blocks the child (it must still
        // finish and exit) nor breaks the actual report handling, while never retaining the full
        // 10 MB in the runner's own memory.
        File fixtureJar = buildFixtureJar();
        Assume.assumeTrue("no system Java compiler available to build the fixture jar - skip", //$NON-NLS-1$
            fixtureJar != null);
        File srcDir = newFolder("fixture-src-huge-stdout"); //$NON-NLS-1$
        Files.write(srcDir.toPath().resolve("FIXTURE_BEHAVIOR.txt"), "huge-stdout".getBytes()); //$NON-NLS-1$ //$NON-NLS-2$

        BslLsRunner.Request request = new BslLsRunner.Request(srcDir)
            .jarOverride(fixtureJar).javaOverride(currentJavaExecutable()).timeoutSeconds(60);
        BslLsRunner.Result result = BslLsRunner.run(request);

        assertTrue("a huge-stdout child must still complete and parse: " //$NON-NLS-1$
            + (result.ok() ? "" : result.errorMessage()), result.ok()); //$NON-NLS-1$
    }

    @Test
    public void testFixtureRunWithOversizedReportFailsLoudWithoutReadingIt() throws Exception
    {
        // The fixture writes a ~60 MB report (over MAX_REPORT_BYTES) - run() must reject it with an
        // actionable error BEFORE attempting Files.readAllBytes/Gson-parsing it.
        File fixtureJar = buildFixtureJar();
        Assume.assumeTrue("no system Java compiler available to build the fixture jar - skip", //$NON-NLS-1$
            fixtureJar != null);
        File srcDir = newFolder("fixture-src-huge-report"); //$NON-NLS-1$
        Files.write(srcDir.toPath().resolve("FIXTURE_BEHAVIOR.txt"), "huge-report".getBytes()); //$NON-NLS-1$ //$NON-NLS-2$

        BslLsRunner.Request request = new BslLsRunner.Request(srcDir)
            .jarOverride(fixtureJar).javaOverride(currentJavaExecutable()).timeoutSeconds(60);
        BslLsRunner.Result result = BslLsRunner.run(request);

        assertFalse("an oversized report must be rejected, not silently parsed", result.ok()); //$NON-NLS-1$
        assertNotNull(result.errorMessage());
        assertTrue("the error must name the byte limit that was exceeded: " + result.errorMessage(), //$NON-NLS-1$
            result.errorMessage().contains(String.valueOf(BslLsRunner.MAX_REPORT_BYTES)));
    }

    /** @return the {@code java(.exe)} launching THIS test JVM, for spawning the fixture jar. */
    private static File currentJavaExecutable()
    {
        File bin = new File(System.getProperty("java.home"), "bin"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return new File(bin, windows ? "java.exe" : "java"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Compiles and packages a tiny, REAL runnable jar standing in for the BSL Language Server engine
     * ({@code --analyze --srcDir <s> --workspaceDir <w> --outputDir <o> --reporter json --silent
     * [--configuration <c>]}, exactly {@link BslLsRunner#buildCommand}'s shape): it locates
     * {@code --srcDir}/{@code --outputDir} among its own args, reads an optional
     * {@code FIXTURE_BEHAVIOR.txt} sentinel file from {@code --srcDir} (defaulting to
     * {@code "normal"} when absent) and either writes a small valid {@code bsl-json.json},
     * floods stdout, or writes an oversized report, before exiting 0. Compiled in-process via
     * {@link ToolProvider#getSystemJavaCompiler()} (needs a JDK, not a bare JRE, running the test) so
     * no extra Maven module/dependency is needed just for this fixture.
     *
     * @return the built fixture jar, or {@code null} when no system Java compiler is available (the
     *         calling test then skips via {@link Assume})
     */
    private File buildFixtureJar() throws IOException
    {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
        {
            return null;
        }
        File work = newFolder("fixture-build-" + System.nanoTime()); //$NON-NLS-1$
        File sourceFile = new File(work, "Fixture.java"); //$NON-NLS-1$
        String source = "public class Fixture {\n" //$NON-NLS-1$
            + "  public static void main(String[] args) throws Exception {\n" //$NON-NLS-1$
            + "    String srcDir = null, outputDir = null;\n" //$NON-NLS-1$
            + "    for (int i = 0; i < args.length - 1; i++) {\n" //$NON-NLS-1$
            + "      if (\"--srcDir\".equals(args[i])) srcDir = args[i + 1];\n" //$NON-NLS-1$
            + "      if (\"--outputDir\".equals(args[i])) outputDir = args[i + 1];\n" //$NON-NLS-1$
            + "    }\n" //$NON-NLS-1$
            + "    java.io.File behaviorFile = new java.io.File(srcDir, \"FIXTURE_BEHAVIOR.txt\");\n" //$NON-NLS-1$
            + "    String behavior = behaviorFile.isFile()\n" //$NON-NLS-1$
            + "      ? new String(java.nio.file.Files.readAllBytes(behaviorFile.toPath())).trim() : \"normal\";\n" //$NON-NLS-1$
            + "    java.io.File report = new java.io.File(outputDir, \"bsl-json.json\");\n" //$NON-NLS-1$
            + "    if (\"huge-stdout\".equals(behavior)) {\n" //$NON-NLS-1$
            + "      StringBuilder line = new StringBuilder();\n" //$NON-NLS-1$
            + "      for (int i = 0; i < 2000; i++) line.append('x');\n" //$NON-NLS-1$
            + "      for (int i = 0; i < 5000; i++) System.out.println(line);\n" //$NON-NLS-1$
            + "      java.nio.file.Files.write(report.toPath(), \"{\\\"fileinfos\\\":[]}\".getBytes());\n" //$NON-NLS-1$
            + "    } else if (\"huge-report\".equals(behavior)) {\n" //$NON-NLS-1$
            + "      java.io.FileOutputStream out = new java.io.FileOutputStream(report);\n" //$NON-NLS-1$
            + "      out.write(\"{\\\"fileinfos\\\":[{\\\"path\\\":\\\"x\\\",\\\"diagnostics\\\":[],\\\"pad\\\":\\\"\".getBytes());\n" //$NON-NLS-1$
            + "      byte[] chunk = new byte[1_000_000];\n" //$NON-NLS-1$
            + "      java.util.Arrays.fill(chunk, (byte) 'x');\n" //$NON-NLS-1$
            + "      for (int i = 0; i < 60; i++) out.write(chunk);\n" //$NON-NLS-1$
            + "      out.write(\"\\\"}]}\".getBytes());\n" //$NON-NLS-1$
            + "      out.close();\n" //$NON-NLS-1$
            + "    } else {\n" //$NON-NLS-1$
            + "      java.nio.file.Files.write(report.toPath(), \"{\\\"fileinfos\\\":[]}\".getBytes());\n" //$NON-NLS-1$
            + "    }\n" //$NON-NLS-1$
            + "    System.exit(0);\n" //$NON-NLS-1$
            + "  }\n" //$NON-NLS-1$
            + "}\n"; //$NON-NLS-1$
        Files.write(sourceFile.toPath(), source.getBytes());

        int compileResult = compiler.run(null, null, null, sourceFile.getPath());
        if (compileResult != 0)
        {
            throw new IOException("Failed to compile the test fixture (exit " + compileResult + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        File classFile = new File(work, "Fixture.class"); //$NON-NLS-1$
        File jarFile = new File(work, "fixture-exec.jar"); //$NON-NLS-1$
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0"); //$NON-NLS-1$
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Fixture"); //$NON-NLS-1$
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile), manifest))
        {
            jos.putNextEntry(new JarEntry("Fixture.class")); //$NON-NLS-1$
            jos.write(Files.readAllBytes(classFile.toPath()));
            jos.closeEntry();
        }
        return jarFile;
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
