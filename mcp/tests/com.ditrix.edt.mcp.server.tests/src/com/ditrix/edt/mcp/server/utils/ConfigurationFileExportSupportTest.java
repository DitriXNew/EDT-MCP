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
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.export.ExportConfigurationFileException;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseEqualityState;
import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.MatchingRuntimeNotFound;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.RuntimeExecutionException;
import com.ditrix.edt.mcp.server.utils.ConfigurationFileExportSupport.OutputFile;
import com.ditrix.edt.mcp.server.utils.ConfigurationFileExportSupport.PublishedFile;
import com.ditrix.edt.mcp.server.utils.ConfigurationFileExportSupport.SyncReading;
import com.ditrix.edt.mcp.server.utils.ConfigurationFileExportSupport.SyncState;

/**
 * Tests the pure half of {@link ConfigurationFileExportSupport}: the output-file rules, the
 * publish step that turns "the platform returned" into "the file is there", and the classification
 * of EDT's infobase-vs-project equality.
 */
public class ConfigurationFileExportSupportTest
{
    private static final String KEY = "outputFile"; //$NON-NLS-1$

    private Path dir;

    @Before
    public void setUp() throws IOException
    {
        dir = Files.createTempDirectory("export-cf-support"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws IOException
    {
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    // ==================== validateOutputFile ====================

    @Test
    public void testValidConfigurationAndExtensionNames()
    {
        OutputFile cf = ConfigurationFileExportSupport.validateOutputFile(KEY, dir.resolve("Main.cf").toString()); //$NON-NLS-1$
        assertTrue(cf.ok());
        assertFalse(cf.isExtensionFile());
        OutputFile cfe = ConfigurationFileExportSupport.validateOutputFile(KEY,
            dir.resolve("Tests.CFE").toString()); //$NON-NLS-1$
        assertTrue(cfe.ok());
        assertTrue("the extension check must ignore case", cfe.isExtensionFile()); //$NON-NLS-1$
    }

    @Test
    public void testPathIsNormalized()
    {
        OutputFile out = ConfigurationFileExportSupport.validateOutputFile(KEY,
            dir.resolve("sub").resolve("..").resolve("Main.cf").toString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(out.error(), out.ok());
        assertEquals(dir.resolve("Main.cf"), out.path()); //$NON-NLS-1$
    }

    @Test
    public void testRelativePathIsRefused()
    {
        OutputFile out = ConfigurationFileExportSupport.validateOutputFile(KEY, "Main.cf"); //$NON-NLS-1$
        assertFalse(out.ok());
        assertNull(out.path());
        assertTrue(out.error().contains("absolute path")); //$NON-NLS-1$
        assertTrue("must name the parameter", out.error().startsWith(KEY)); //$NON-NLS-1$
    }

    @Test
    public void testOtherExtensionsAndBareExtensionAreRefused()
    {
        for (String name : new String[] {"Main.dt", "Main.cf.bak", "Main", ".cf", ".cfe"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            OutputFile out = ConfigurationFileExportSupport.validateOutputFile(KEY, dir.resolve(name).toString());
            assertFalse("must refuse " + name, out.ok()); //$NON-NLS-1$
            assertTrue(out.error().contains("ending in .cf")); //$NON-NLS-1$
        }
    }

    @Test
    public void testMissingFolderIsRefusedAndNotCreated()
    {
        Path missing = dir.resolve("missing"); //$NON-NLS-1$
        OutputFile out = ConfigurationFileExportSupport.validateOutputFile(KEY,
            missing.resolve("Main.cf").toString()); //$NON-NLS-1$
        assertFalse(out.ok());
        assertTrue(out.error().contains(missing.toString()));
        assertFalse(Files.exists(missing));
    }

    @Test
    public void testExistingFileIsRefused() throws IOException
    {
        Path existing = Files.writeString(dir.resolve("Main.cf"), "x", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
        OutputFile out = ConfigurationFileExportSupport.validateOutputFile(KEY, existing.toString());
        assertFalse(out.ok());
        assertTrue(out.error().contains("already exists")); //$NON-NLS-1$
        assertTrue(out.error().contains("never overwrites")); //$NON-NLS-1$
    }

    @Test
    public void testExistingFolderWithACfNameIsRefused() throws IOException
    {
        Path folder = Files.createDirectory(dir.resolve("Looks.cf")); //$NON-NLS-1$
        assertFalse(ConfigurationFileExportSupport.validateOutputFile(KEY, folder.toString()).ok());
    }

    // ==================== kindMismatch ====================

    @Test
    public void testKindMismatch()
    {
        OutputFile cf = ConfigurationFileExportSupport.validateOutputFile(KEY, dir.resolve("a.cf").toString()); //$NON-NLS-1$
        OutputFile cfe = ConfigurationFileExportSupport.validateOutputFile(KEY, dir.resolve("a.cfe").toString()); //$NON-NLS-1$
        assertNull(ConfigurationFileExportSupport.kindMismatch(KEY, cf, null, "Config")); //$NON-NLS-1$
        assertNull(ConfigurationFileExportSupport.kindMismatch(KEY, cfe, "Tests", "Config")); //$NON-NLS-1$ //$NON-NLS-2$
        String cfeForConfiguration = ConfigurationFileExportSupport.kindMismatch(KEY, cfe, null, "Config"); //$NON-NLS-1$
        assertNotNull(cfeForConfiguration);
        assertTrue(cfeForConfiguration.contains("extensionName")); //$NON-NLS-1$
        String cfForExtension = ConfigurationFileExportSupport.kindMismatch(KEY, cf, "Tests", "Config"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(cfForExtension);
        assertTrue(cfForExtension.contains("Use a .cfe name")); //$NON-NLS-1$
    }

    // ==================== partialFileFor ====================

    @Test
    public void testPartialFileSitsBesideTheDestinationWithTheSameExtension()
    {
        Path destination = dir.resolve("Main.release.cfe"); //$NON-NLS-1$
        Path partial = ConfigurationFileExportSupport.partialFileFor(destination, "ab12"); //$NON-NLS-1$
        assertEquals(dir, partial.getParent());
        assertEquals("Main.release.partial-ab12.cfe", partial.getFileName().toString()); //$NON-NLS-1$
    }

    // ==================== publish ====================

    @Test
    public void testPublishMovesAFreshFileAndReadsItBack() throws IOException
    {
        long start = System.currentTimeMillis();
        Path destination = dir.resolve("Main.cf"); //$NON-NLS-1$
        Path partial = ConfigurationFileExportSupport.partialFileFor(destination, "t1"); //$NON-NLS-1$
        Files.write(partial, new byte[] {1, 2, 3, 4, 5});

        PublishedFile published = ConfigurationFileExportSupport.publish(partial, destination, start);

        assertEquals(destination, published.path());
        assertEquals(5L, published.sizeBytes());
        assertFalse(Files.exists(partial));
        assertEquals(5L, Files.size(destination));
    }

    @Test
    public void testPublishRefusesAMissingFile()
    {
        Path destination = dir.resolve("Main.cf"); //$NON-NLS-1$
        assertPublishRefused(ConfigurationFileExportSupport.partialFileFor(destination, "t2"), //$NON-NLS-1$
            destination, System.currentTimeMillis(), "wrote no file"); //$NON-NLS-1$
    }

    @Test
    public void testPublishRefusesAnEmptyFile() throws IOException
    {
        Path destination = dir.resolve("Main.cf"); //$NON-NLS-1$
        Path partial = Files.createFile(ConfigurationFileExportSupport.partialFileFor(destination, "t3")); //$NON-NLS-1$
        assertPublishRefused(partial, destination, System.currentTimeMillis(), "EMPTY"); //$NON-NLS-1$
    }

    @Test
    public void testPublishRefusesAFileOlderThanTheExport() throws IOException
    {
        Path destination = dir.resolve("Main.cf"); //$NON-NLS-1$
        Path partial = ConfigurationFileExportSupport.partialFileFor(destination, "t4"); //$NON-NLS-1$
        Files.write(partial, new byte[] {1});
        long start = System.currentTimeMillis();
        Files.setLastModifiedTime(partial, FileTime.fromMillis(start - 60_000L));
        assertPublishRefused(partial, destination, start, "older than this export"); //$NON-NLS-1$
    }

    @Test
    public void testPublishNeverReplacesAFileThatAppearedMeanwhile() throws IOException
    {
        Path destination = Files.writeString(dir.resolve("Main.cf"), "theirs", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
        Path partial = ConfigurationFileExportSupport.partialFileFor(destination, "t5"); //$NON-NLS-1$
        Files.write(partial, new byte[] {1, 2});
        assertPublishRefused(partial, destination, System.currentTimeMillis(), "left untouched"); //$NON-NLS-1$
        assertEquals("theirs", Files.readString(destination, StandardCharsets.UTF_8)); //$NON-NLS-1$
    }

    @Test
    public void testDeleteQuietly() throws IOException
    {
        Path file = Files.writeString(dir.resolve("x.partial-1.cf"), "x", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(ConfigurationFileExportSupport.deleteQuietly(file));
        assertFalse(Files.exists(file));
        assertTrue("an absent file is already gone", ConfigurationFileExportSupport.deleteQuietly(file)); //$NON-NLS-1$
        assertTrue(ConfigurationFileExportSupport.deleteQuietly(null));
    }

    // ==================== classify ====================

    @Test
    public void testClassifyOnlyTrustsAConnectedProject()
    {
        // EDT answers NOT_EQUAL for a project it is not synchronizing, which measures nothing.
        SyncReading notConnected = ConfigurationFileExportSupport.classify(false,
            InfobaseEqualityState.NOT_EQUAL, "Config"); //$NON-NLS-1$
        assertEquals(SyncState.UNKNOWN, notConnected.state());
        assertTrue(notConnected.detail().contains("Config")); //$NON-NLS-1$

        assertEquals(SyncState.IN_SYNC,
            ConfigurationFileExportSupport.classify(true, InfobaseEqualityState.EQUAL, "Config").state()); //$NON-NLS-1$
        assertEquals(SyncState.OUT_OF_DATE,
            ConfigurationFileExportSupport.classify(true, InfobaseEqualityState.NOT_EQUAL, "Config").state()); //$NON-NLS-1$
        assertEquals(SyncState.UNKNOWN,
            ConfigurationFileExportSupport.classify(true, InfobaseEqualityState.LOADING, "Config").state()); //$NON-NLS-1$
        assertEquals(SyncState.UNKNOWN,
            ConfigurationFileExportSupport.classify(true, null, "Config").state()); //$NON-NLS-1$
    }

    @Test
    public void testSyncStateWireValues()
    {
        assertEquals("inSync", SyncState.IN_SYNC.wire()); //$NON-NLS-1$
        assertEquals("outOfDate", SyncState.OUT_OF_DATE.wire()); //$NON-NLS-1$
        assertEquals("unknown", SyncState.UNKNOWN.wire()); //$NON-NLS-1$
    }

    // ==================== describeExportFailure ====================

    @Test
    public void testMissingPlatformNamesTheFix()
    {
        String message = ConfigurationFileExportSupport.describeExportFailure(
            new IllegalStateException("wrapped", new MatchingRuntimeNotFound("8.3.27")), //$NON-NLS-1$ //$NON-NLS-2$
            null, "Config"); //$NON-NLS-1$
        assertTrue(message.contains("installed platforms")); //$NON-NLS-1$
        assertTrue(message.contains("Config")); //$NON-NLS-1$
    }

    @Test
    public void testGenericFailureKeepsThePlatformTextAndNamesTheExtension()
    {
        String message = ConfigurationFileExportSupport.describeExportFailure(
            new IllegalStateException("Extension not found: Tests"), "Tests", "Config"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(message.contains("Extension not found: Tests")); //$NON-NLS-1$
        assertTrue(message.contains("an extension named 'Tests'")); //$NON-NLS-1$
    }

    @Test
    public void testPlatformTextEndingInAPeriodIsNotDoubled()
    {
        // The Designer's own message ends with a period; the sentence must not read "..".
        String message = ConfigurationFileExportSupport.describeExportFailure(
            new ExportConfigurationFileException("Designer agent interaction error.", //$NON-NLS-1$
                new IllegalStateException("Extension not found.\r\n")), "Tests", "Config"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(message, message.contains("Extension not found. Check that")); //$NON-NLS-1$
        assertFalse(message, message.contains("..")); //$NON-NLS-1$
    }

    @Test
    public void testDeclaredPlatformFailuresAreToldApartFromDefects()
    {
        assertTrue(ConfigurationFileExportSupport.isDeclaredPlatformFailure(
            new ExportConfigurationFileException("x", null))); //$NON-NLS-1$
        assertTrue(ConfigurationFileExportSupport.isDeclaredPlatformFailure(
            new MatchingRuntimeNotFound("x"))); //$NON-NLS-1$
        assertTrue(ConfigurationFileExportSupport.isDeclaredPlatformFailure(
            new RuntimeExecutionException("x"))); //$NON-NLS-1$
        assertFalse(ConfigurationFileExportSupport.isDeclaredPlatformFailure(new NullPointerException()));
        assertFalse(ConfigurationFileExportSupport.isDeclaredPlatformFailure(null));
    }

    private static void assertPublishRefused(Path partial, Path destination, long start, String reason)
    {
        try
        {
            ConfigurationFileExportSupport.publish(partial, destination, start);
            fail("publish must refuse: " + reason); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains(reason));
        }
    }
}
