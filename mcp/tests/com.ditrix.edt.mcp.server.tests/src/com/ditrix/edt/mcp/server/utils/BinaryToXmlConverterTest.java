/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.ConversionException;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.IThickClient;
import com.ditrix.edt.mcp.server.utils.BinaryToXmlConverter.SourceKind;

/**
 * Tests for {@link BinaryToXmlConverter}: the kind of a file, the order of the designer steps per
 * kind, the deadline that stops a step, and the check of what a dump holds.
 */
public class BinaryToXmlConverterTest
{
    private static final long FAR = TimeUnit.MINUTES.toNanos(5);

    private Path workDir;

    @Before
    public void setUp() throws IOException
    {
        workDir = Files.createTempDirectory("converter-test"); //$NON-NLS-1$
    }

    @After
    public void tearDown()
    {
        BinaryToXmlConverter.deleteQuietly(workDir);
    }

    @Test
    public void testKindIsTakenFromTheExtensionIgnoringCase()
    {
        assertEquals(SourceKind.CONFIGURATION, SourceKind.ofFile(Paths.get("a/Trade.cf"))); //$NON-NLS-1$
        assertEquals(SourceKind.EXTENSION, SourceKind.ofFile(Paths.get("Fixes.CFE"))); //$NON-NLS-1$
        assertEquals(SourceKind.EXTERNAL_DATA_PROCESSOR, SourceKind.ofFile(Paths.get("x.y.Epf"))); //$NON-NLS-1$
        assertEquals(SourceKind.EXTERNAL_REPORT, SourceKind.ofFile(Paths.get("r.erf"))); //$NON-NLS-1$
        assertNull(SourceKind.ofFile(Paths.get("dump.dt"))); //$NON-NLS-1$
        assertNull(SourceKind.ofFile(Paths.get("noextension"))); //$NON-NLS-1$
        assertNull(SourceKind.ofFile(Paths.get("cf"))); //$NON-NLS-1$
        assertEquals(".cf, .cfe, .epf, .erf", SourceKind.supportedExtensions()); //$NON-NLS-1$
    }

    @Test
    public void testConfigurationIsCreatedFromTheTemplateThenDumped() throws Exception
    {
        RecordingClient client = new RecordingClient();
        Path cf = Paths.get("Trade.cf"); //$NON-NLS-1$
        Path xml = BinaryToXmlConverter.convert(SourceKind.CONFIGURATION, cf, workDir, client,
            System.nanoTime() + FAR, line -> { });
        assertEquals(workDir.resolve("xml"), xml); //$NON-NLS-1$
        assertEquals(List.of("create:Trade.cf", "dump:null"), client.calls); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExtensionIsLoadedIntoAnEmptyInfobaseAndDumpedByTheSameName() throws Exception
    {
        RecordingClient client = new RecordingClient();
        List<String> progress = new ArrayList<>();
        BinaryToXmlConverter.convert(SourceKind.EXTENSION, Paths.get("Fixes.cfe"), workDir, client, //$NON-NLS-1$
            System.nanoTime() + FAR, progress::add);
        String name = BinaryToXmlConverter.TEMPORARY_EXTENSION_NAME;
        assertEquals("one designer command per step, in this order", //$NON-NLS-1$
            List.of("create:empty", "load:Fixes.cfe:" + name, "dump:" + name), client.calls); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(3, progress.size());
    }

    @Test
    public void testExternalObjectIsDumpedWithoutAnInfobaseUnderItsStem() throws Exception
    {
        RecordingClient client = new RecordingClient();
        BinaryToXmlConverter.convert(SourceKind.EXTERNAL_REPORT, Paths.get("My.Report.erf"), workDir, //$NON-NLS-1$
            client, System.nanoTime() + FAR, line -> { });
        assertEquals(List.of("external:My.Report.erf->My.Report.xml"), client.calls); //$NON-NLS-1$
    }

    @Test
    public void testAStepPastTheDeadlineIsInterruptedAndReported() throws Exception
    {
        AtomicBoolean interrupted = new AtomicBoolean();
        long started = System.nanoTime();
        try
        {
            BinaryToXmlConverter.runStep("dumping", System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200), //$NON-NLS-1$
                () -> {
                    try
                    {
                        Thread.sleep(60_000L);
                    }
                    catch (InterruptedException e)
                    {
                        interrupted.set(true);
                        throw e;
                    }
                });
            fail("a step past its deadline must fail"); //$NON-NLS-1$
        }
        catch (ConversionException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("ran out of time while dumping")); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("process killed")); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("No project was created")); //$NON-NLS-1$
        }
        assertTrue("the step must be interrupted, which is what kills its process", interrupted.get()); //$NON-NLS-1$
        assertTrue("the deadline, not the step, must end the wait", //$NON-NLS-1$
            System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30));
    }

    @Test
    public void testAFailingStepCarriesThePlatformMessage()
    {
        try
        {
            BinaryToXmlConverter.runStep("loading", System.nanoTime() + FAR, () -> { //$NON-NLS-1$
                throw new IOException("1C:Enterprise exited with code 1: wrong file format"); //$NON-NLS-1$
            });
            fail("a failing step must fail"); //$NON-NLS-1$
        }
        catch (ConversionException | InterruptedException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("failed while loading")); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("wrong file format")); //$NON-NLS-1$
        }
    }

    @Test
    public void testAnInterruptedCallerStopsTheStepFirst() throws Exception
    {
        AtomicBoolean stepInterrupted = new AtomicBoolean();
        CountDownLatch stepRunning = new CountDownLatch(1);
        AtomicReference<Throwable> callerOutcome = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try
            {
                BinaryToXmlConverter.runStep("creating", System.nanoTime() + FAR, () -> { //$NON-NLS-1$
                    stepRunning.countDown();
                    try
                    {
                        Thread.sleep(60_000L);
                    }
                    catch (InterruptedException e)
                    {
                        stepInterrupted.set(true);
                        throw e;
                    }
                });
            }
            catch (Throwable t) // NOSONAR recorded for the assertion
            {
                callerOutcome.set(t);
            }
        });
        caller.start();
        assertTrue(stepRunning.await(10, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(30_000L);
        assertFalse(caller.isAlive());
        assertTrue("the caller's interruption must propagate", //$NON-NLS-1$
            callerOutcome.get() instanceof InterruptedException);
        assertTrue("the step must be stopped before the caller leaves", stepInterrupted.get()); //$NON-NLS-1$
    }

    @Test
    public void testDumpCheckTellsAConfigurationFromAnExtension() throws IOException
    {
        Path xml = Files.createDirectories(workDir.resolve("xml")); //$NON-NLS-1$
        assertTrue(BinaryToXmlConverter.verifyDump(SourceKind.CONFIGURATION, xml).contains("no Configuration.xml")); //$NON-NLS-1$

        write(xml.resolve("Configuration.xml"), configurationXml(false)); //$NON-NLS-1$
        assertNull(BinaryToXmlConverter.verifyDump(SourceKind.CONFIGURATION, xml));
        assertTrue(BinaryToXmlConverter.verifyDump(SourceKind.EXTENSION, xml).contains("holds a configuration, not an extension")); //$NON-NLS-1$

        write(xml.resolve("Configuration.xml"), configurationXml(true)); //$NON-NLS-1$
        assertNull(BinaryToXmlConverter.verifyDump(SourceKind.EXTENSION, xml));
        assertTrue(BinaryToXmlConverter.verifyDump(SourceKind.CONFIGURATION, xml).contains("configuration EXTENSION")); //$NON-NLS-1$

        write(xml.resolve("Configuration.xml"), "not xml"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(BinaryToXmlConverter.verifyDump(SourceKind.CONFIGURATION, xml).contains("could not be read")); //$NON-NLS-1$
    }

    @Test
    public void testDumpCheckWantsExactlyOneExternalRoot() throws IOException
    {
        Path xml = Files.createDirectories(workDir.resolve("xml")); //$NON-NLS-1$
        assertTrue(BinaryToXmlConverter.verifyDump(SourceKind.EXTERNAL_DATA_PROCESSOR, xml).contains("0 root XML files")); //$NON-NLS-1$
        write(xml.resolve("Proc.xml"), "<MetaDataObject/>"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(xml.resolve("Proc").resolve("Forms")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(BinaryToXmlConverter.verifyDump(SourceKind.EXTERNAL_DATA_PROCESSOR, xml));
        write(xml.resolve("Other.xml"), "<MetaDataObject/>"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(BinaryToXmlConverter.verifyDump(SourceKind.EXTERNAL_DATA_PROCESSOR, xml).contains("2 root XML files")); //$NON-NLS-1$
    }

    @Test
    public void testDumpCheckRefusesAnExternalRootEdtWouldImportAsAConfiguration() throws IOException
    {
        // EDT's CLI import asks Files.exists(dir/Configuration.xml): the check must ask the same.
        Path xml = Files.createDirectories(workDir.resolve("xml")); //$NON-NLS-1$
        write(xml.resolve("configuration.xml"), "<MetaDataObject/>"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean edtSeesAConfiguration = Files.exists(xml.resolve("Configuration.xml")); //$NON-NLS-1$
        assertEquals("on this file system's case rules", edtSeesAConfiguration, //$NON-NLS-1$
            BinaryToXmlConverter.verifyDump(SourceKind.EXTERNAL_REPORT, xml) != null);

        Files.delete(xml.resolve("configuration.xml")); //$NON-NLS-1$
        write(xml.resolve("Configuration.xml"), "<MetaDataObject/>"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("The file's name makes the root file of its dump Configuration.xml, and EDT imports " //$NON-NLS-1$
            + "a dump holding that file as a configuration, not as an external data processor. Copy the " //$NON-NLS-1$
            + "file under another name and import the copy.", //$NON-NLS-1$
            BinaryToXmlConverter.verifyDump(SourceKind.EXTERNAL_DATA_PROCESSOR, xml));
    }

    @Test
    public void testNoPlatformMessageIsActionable()
    {
        String any = BinaryToXmlConverter.noPlatformMessage(null, new IllegalStateException("none")); //$NON-NLS-1$
        assertTrue(any, any.contains("Installed Installations")); //$NON-NLS-1$
        assertTrue(any, any.contains("import_configuration_from_xml")); //$NON-NLS-1$
        assertTrue(any, any.contains("No project was created")); //$NON-NLS-1$
        assertFalse(any, any.contains("omit platformVersion")); //$NON-NLS-1$
        String pinned = BinaryToXmlConverter.noPlatformMessage("8.3.99", new IllegalStateException("none")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(pinned, pinned.contains("platformVersion '8.3.99'")); //$NON-NLS-1$
        assertTrue(pinned, pinned.contains("omit platformVersion")); //$NON-NLS-1$
    }

    @Test
    public void testInfobasePathProblemFollowsHowEachOsPassesTheSplitPath()
    {
        Path plain = Paths.get("scratch", "edt-mcp-import-1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(BinaryToXmlConverter.infobasePathProblem(plain, true));
        assertNull(BinaryToXmlConverter.infobasePathProblem(plain, false));
        Path oneSpace = Paths.get("John Doe", "Temp"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("Windows re-joins the split pieces with one space", //$NON-NLS-1$
            BinaryToXmlConverter.infobasePathProblem(oneSpace, true));
        assertEquals("that path holds a space, at which EDT's infobase command splits it", //$NON-NLS-1$
            BinaryToXmlConverter.infobasePathProblem(oneSpace, false));
        Path twoSpaces = Paths.get("a  b", "Temp"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("that path holds two spaces in a row, which EDT's infobase command does not " //$NON-NLS-1$
            + "pass on intact", BinaryToXmlConverter.infobasePathProblem(twoSpaces, true)); //$NON-NLS-1$
        assertEquals("that path holds a space, at which EDT's infobase command splits it", //$NON-NLS-1$
            BinaryToXmlConverter.infobasePathProblem(twoSpaces, false));
    }

    @Test
    public void testAShortDesignerLogIsReadWholeWithoutItsBom() throws IOException
    {
        Path log = workDir.resolve("short.log"); //$NON-NLS-1$
        write(log, (char)0xFEFF + "Extension is not loaded\r\n"); //$NON-NLS-1$
        assertEquals("Extension is not loaded", BinaryToXmlConverter.readLog(log)); //$NON-NLS-1$
        assertEquals("(no designer log)", BinaryToXmlConverter.readLog(workDir.resolve("none.log"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testALongDesignerLogIsCutToItsTailOnACharacterBoundary() throws IOException
    {
        // BOM (3 bytes), then two-byte characters from offset 3: an even cut offset lands mid-character.
        StringBuilder text = new StringBuilder().append((char)0xFEFF);
        for (int i = 0; i < 50_000; i++)
        {
            text.append((char)0x041E);
        }
        text.append("FINAL VERDICT"); //$NON-NLS-1$
        Path log = workDir.resolve("long.log"); //$NON-NLS-1$
        write(log, text.toString());
        long size = Files.size(log);
        assertEquals(3 + 100_000 + 13, size);

        String read = BinaryToXmlConverter.readLog(log);
        int kept = BinaryToXmlConverter.LOG_TAIL_BYTES - 1;
        assertTrue(read, read.startsWith("(designer log cut to its last " + kept + " of " + size //$NON-NLS-1$ //$NON-NLS-2$
            + " bytes) ...")); //$NON-NLS-1$
        assertTrue(read.endsWith("FINAL VERDICT")); //$NON-NLS-1$
        assertTrue("no broken character at the cut", read.indexOf((char)0xFFFD) < 0); //$NON-NLS-1$
        assertTrue("the kept text is bounded: " + read.length(), //$NON-NLS-1$
            read.getBytes(StandardCharsets.UTF_8).length < BinaryToXmlConverter.LOG_TAIL_BYTES + 100);
    }

    @Test
    public void testDeleteQuietlyRemovesATree() throws IOException
    {
        Path dir = Files.createDirectories(workDir.resolve("a").resolve("b")); //$NON-NLS-1$ //$NON-NLS-2$
        write(dir.resolve("f.txt"), "x"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(BinaryToXmlConverter.deleteQuietly(workDir.resolve("a"))); //$NON-NLS-1$
        assertFalse(Files.exists(workDir.resolve("a"))); //$NON-NLS-1$
        assertTrue(BinaryToXmlConverter.deleteQuietly(null));
    }

    private static String configurationXml(boolean adopted)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\">" //$NON-NLS-1$
            + "<Configuration uuid=\"1\"><Properties>" //$NON-NLS-1$
            + (adopted ? "<ObjectBelonging>Adopted</ObjectBelonging>" : "") //$NON-NLS-1$ //$NON-NLS-2$
            + "<Name>Cfg</Name></Properties></Configuration></MetaDataObject>"; //$NON-NLS-1$
    }

    private static void write(Path file, String text) throws IOException
    {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Records each designer step. */
    private static final class RecordingClient implements IThickClient
    {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public String platformVersion()
        {
            return "8.3.99.1"; //$NON-NLS-1$
        }

        @Override
        public void createInfobase(Path infobaseDir, Path template)
        {
            calls.add("create:" + (template == null ? "empty" : template.getFileName().toString())); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void loadExtension(Path infobaseDir, Path cfe, String extensionName, Path log)
        {
            calls.add("load:" + cfe.getFileName() + ":" + extensionName); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void dumpConfiguration(Path infobaseDir, String extensionName, Path xmlDir)
        {
            calls.add("dump:" + extensionName); //$NON-NLS-1$
        }

        @Override
        public void dumpExternalObject(Path binary, Path rootXml)
        {
            calls.add("external:" + binary.getFileName() + "->" + rootXml.getFileName()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
