/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.compare.MergeRulesCodec.MergeRulesFormatException;
import com.ditrix.edt.mcp.server.utils.compare.MergeRulesDocument.Decision;
import com.ditrix.edt.mcp.server.utils.compare.MergeRulesDocument.TopObjectKey;

/**
 * Tests for the merge-rules codec and the document it produces.
 * <p>
 * The fixture is shaped after a REAL saved file (the format was measured on the platform's own
 * {@code MergeSettingsTree} serializer): a {@code Correspondences} section beside the node tree,
 * a {@code Properties} map this plugin does not interpret, a feature-collection node, a rename
 * whose three names all differ, a one-sided add keyed {@code X:NONE:X}, and a positional child
 * keyed by the engine-computed position.
 * <p>
 * The load-bearing assertion is that a rewrite is LOSSLESS: a naive re-emit that keeps only the
 * parts the plugin understands would silently delete exactly the payload that carries the
 * BSL-fragment and custom-merge decisions. The round-trip is therefore pinned byte for byte, not
 * "the rules are still there".
 */
public class MergeRulesCodecTest
{
    private static final String FIXTURE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
        + "<Settings Format_version=\"2.0\">\n" //$NON-NLS-1$
        + "  <Correspondences>\n" //$NON-NLS-1$
        + "    <Correspondence>\n" //$NON-NLS-1$
        + "      <MainConfiguration>Catalog.Alpha</MainConfiguration>\n" //$NON-NLS-1$
        + "      <OtherConfiguration>Catalog.Beta</OtherConfiguration>\n" //$NON-NLS-1$
        + "      <CommonAncestorConfiguration>Catalog.Gamma</CommonAncestorConfiguration>\n" //$NON-NLS-1$
        + "    </Correspondence>\n" //$NON-NLS-1$
        + "  </Correspondences>\n" //$NON-NLS-1$
        + "  <MergeSettings>\n" //$NON-NLS-1$
        + "    <Node Key=\"$$Root$$\">\n" //$NON-NLS-1$
        + "      <Properties>\n" //$NON-NLS-1$
        + "        <SkipUnchanged>true</SkipUnchanged>\n" //$NON-NLS-1$
        + "        <Comment>kept verbatim</Comment>\n" //$NON-NLS-1$
        + "      </Properties>\n" //$NON-NLS-1$
        + "      <Node Key=\"commonModules\" MergeRule=\"GetFromOther\">\n" //$NON-NLS-1$
        + "        <Node Key=\"Alpha:Beta:Gamma\" MergeRule=\"MergePrioritizingMain\"/>\n" //$NON-NLS-1$
        + "        <Node Key=\"Added:NONE:Added\" MergeRule=\"DoNotMerge\">\n" //$NON-NLS-1$
        + "          <Node Key=\"7\" MergeRule=\"GetFromOther\" OrderSide=\"Other\"/>\n" //$NON-NLS-1$
        + "        </Node>\n" //$NON-NLS-1$
        + "      </Node>\n" //$NON-NLS-1$
        + "    </Node>\n" //$NON-NLS-1$
        + "  </MergeSettings>\n" //$NON-NLS-1$
        + "</Settings>\n"; //$NON-NLS-1$

    /** The payload block the reader does not interpret and must never drop. */
    private static final String PROPERTIES_BLOCK = "      <Properties>\n" //$NON-NLS-1$
        + "        <SkipUnchanged>true</SkipUnchanged>\n" //$NON-NLS-1$
        + "        <Comment>kept verbatim</Comment>\n" //$NON-NLS-1$
        + "      </Properties>\n"; //$NON-NLS-1$

    private Path workDir;

    @Before
    public void setUp() throws IOException
    {
        workDir = Files.createTempDirectory("merge-rules-codec-test"); //$NON-NLS-1$
    }

    /**
     * Removes the work directory, retrying briefly.
     * <p>
     * The retry is for Windows and nothing else: a file deleted while another thread still holds a
     * handle to it lingers as a directory entry, so the parent reports {@code DirectoryNotEmpty}
     * for a few milliseconds after every child has been deleted. The concurrent-write test makes
     * that likely, and a suite that fails in TEARDOWN over it reports a defect nobody has.
     * <p>
     * It masks nothing: leftovers are asserted INSIDE the tests that care about them
     * ({@code testWriteLeavesNoTemporaryFileBehind}, {@code testAFailedWriteLeavesNoTemporaryBehind}),
     * never here.
     */
    @After
    public void tearDown() throws IOException, InterruptedException
    {
        IOException last = null;
        for (int attempt = 0; attempt < 20; attempt++)
        {
            if (workDir == null || !Files.exists(workDir))
            {
                return;
            }
            try
            {
                try (Stream<Path> walk = Files.walk(workDir))
                {
                    for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
                    {
                        Files.deleteIfExists(path);
                    }
                }
                return;
            }
            catch (IOException e)
            {
                last = e;
                Thread.sleep(100L);
            }
        }
        if (last != null)
        {
            throw last;
        }
    }

    // ==================== round trip ====================

    @Test
    public void testRoundTripIsByteIdentical() throws Exception
    {
        assertEquals("parse -> serialize must reproduce the file, not a projection of it", //$NON-NLS-1$
            FIXTURE, MergeRulesCodec.serialize(MergeRulesCodec.parse(FIXTURE)));
    }

    @Test
    public void testRoundTripIsIdempotent() throws Exception
    {
        String once = MergeRulesCodec.serialize(MergeRulesCodec.parse(FIXTURE));
        assertEquals("a second round trip must not drift", once, //$NON-NLS-1$
            MergeRulesCodec.serialize(MergeRulesCodec.parse(once)));
    }

    @Test
    public void testDecisionsSurviveARoundTrip() throws Exception
    {
        List<String> before = describe(MergeRulesCodec.parse(FIXTURE).decisions());
        String rewritten = MergeRulesCodec.serialize(MergeRulesCodec.parse(FIXTURE));
        assertEquals("the decision set must be identical after a rewrite", before, //$NON-NLS-1$
            describe(MergeRulesCodec.parse(rewritten).decisions()));
    }

    @Test
    public void testUnknownPropertiesBlockSurvivesAnEditByteIdentically() throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);
        document.setMergeRule(List.of("catalogs", "Products:Products:Products"), "GetFromOther"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String rewritten = MergeRulesCodec.serialize(document);
        assertTrue("the Properties block the reader does not understand must come back verbatim", //$NON-NLS-1$
            rewritten.contains(PROPERTIES_BLOCK));
        assertTrue("the Correspondences section must survive the edit too", //$NON-NLS-1$
            rewritten.contains("<MainConfiguration>Catalog.Alpha</MainConfiguration>")); //$NON-NLS-1$
        assertTrue("the new decision must be in the file", //$NON-NLS-1$
            rewritten.contains("<Node Key=\"Products:Products:Products\" MergeRule=\"GetFromOther\"/>")); //$NON-NLS-1$
    }

    @Test
    public void testPositionalChildIsPreservedWithItsOrderSide() throws Exception
    {
        String rewritten = MergeRulesCodec.serialize(MergeRulesCodec.parse(FIXTURE));
        assertTrue("a positional node is read-only for us, which means preserved - not dropped", //$NON-NLS-1$
            rewritten.contains("<Node Key=\"7\" MergeRule=\"GetFromOther\" OrderSide=\"Other\"/>")); //$NON-NLS-1$
    }

    @Test
    public void testRussianObjectNameSurvivesTheFileRoundTrip() throws Exception
    {
        // A real Russian object name (Tovary = Goods), written through escapes per the repo's
        // rule for Cyrillic in sources.
        String name = "\u0422\u043E\u0432\u0430\u0440\u044B"; //$NON-NLS-1$
        MergeRulesDocument document = MergeRulesDocument.empty();
        document.setMergeRule(List.of("catalogs", TopObjectKey.format(name, name, name)), "DoNotMerge"); //$NON-NLS-1$ //$NON-NLS-2$
        Path file = workDir.resolve("rules.xml"); //$NON-NLS-1$
        MergeRulesCodec.write(file, document);

        List<Decision> decisions = MergeRulesCodec.read(file).decisions();
        assertEquals(1, decisions.size());
        assertEquals(name + ":" + name + ":" + name, decisions.get(0).key()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(name, decisions.get(0).topObjectKey().orElseThrow().main());
    }

    // ==================== the three-name key ====================

    @Test
    public void testTopObjectKeySplitsIntoThreeNames() throws Exception
    {
        TopObjectKey key = decisionFor("Alpha:Beta:Gamma").topObjectKey().orElseThrow(); //$NON-NLS-1$
        assertEquals("Alpha", key.main()); //$NON-NLS-1$
        assertEquals("Beta", key.other()); //$NON-NLS-1$
        assertEquals("Gamma", key.ancestor()); //$NON-NLS-1$
        assertTrue("three different names is what a rename looks like", key.isRename()); //$NON-NLS-1$
    }

    @Test
    public void testNoneMeansTheSideHasNoSuchObject() throws Exception
    {
        TopObjectKey key = decisionFor("Added:NONE:Added").topObjectKey().orElseThrow(); //$NON-NLS-1$
        assertEquals("Added", key.main()); //$NON-NLS-1$
        // The load-bearing one: without the NONE branch this would read back the literal "NONE"
        // as if some object were called that.
        assertNull("NONE is the absence of an object on that side, not a name", key.other()); //$NON-NLS-1$
        assertEquals("Added", key.ancestor()); //$NON-NLS-1$
        assertFalse("an absent side is not a different name", key.isRename()); //$NON-NLS-1$
    }

    @Test
    public void testTopObjectKeyFormatWritesNoneForAnAbsentSide()
    {
        assertEquals("Added:NONE:Added", TopObjectKey.format("Added", null, "Added")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testKeyKinds()
    {
        assertTrue(MergeRulesDocument.isTopObjectKey("A:B:C")); //$NON-NLS-1$
        assertFalse("a feature name is not a three-name key", //$NON-NLS-1$
            MergeRulesDocument.isTopObjectKey("commonModules")); //$NON-NLS-1$
        assertFalse("two names are not three", MergeRulesDocument.isTopObjectKey("A:B")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a computed position is a bare integer", MergeRulesDocument.isPositionKey("7")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MergeRulesDocument.isPositionKey("commonModules")); //$NON-NLS-1$
        assertFalse(MergeRulesDocument.isPositionKey("A:B:C")); //$NON-NLS-1$
    }

    // ==================== addressing ====================

    @Test
    public void testDecisionCarriesItsWholeChainNotJustTheKey() throws Exception
    {
        Decision decision = decisionFor("Alpha:Beta:Gamma"); //$NON-NLS-1$
        assertEquals("a key alone is not an address - sibling members under different owners " //$NON-NLS-1$
            + "share their last segment", //$NON-NLS-1$
            List.of("$$Root$$", "commonModules", "Alpha:Beta:Gamma"), decision.path()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(2, decision.depth());
    }

    @Test
    public void testDepthsAreCountedFromTheRoot() throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);
        assertEquals(1, decision(document, "commonModules").depth()); //$NON-NLS-1$
        assertEquals(3, decision(document, "7").depth()); //$NON-NLS-1$
    }

    @Test
    public void testSetMergeRuleReplacesInPlaceAndKeepsSiblings() throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);
        document.setMergeRule(List.of("commonModules", "Alpha:Beta:Gamma"), "DoNotMerge"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("DoNotMerge", //$NON-NLS-1$
            document.mergeRuleAt(List.of("commonModules", "Alpha:Beta:Gamma")).orElseThrow()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the sibling decision must be untouched", "DoNotMerge", //$NON-NLS-1$ //$NON-NLS-2$
            document.mergeRuleAt(List.of("commonModules", "Added:NONE:Added")).orElseThrow()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("replacing a rule must not add a node", 4, document.decisions().size()); //$NON-NLS-1$
    }

    @Test
    public void testSetMergeRuleOnTheRootAddressesTheRootNode()
    {
        MergeRulesDocument document = MergeRulesDocument.empty();
        document.setMergeRule(List.of(), "GetFromOther"); //$NON-NLS-1$
        Decision decision = document.decisions().get(0);
        assertEquals(List.of("$$Root$$"), decision.path()); //$NON-NLS-1$
        assertEquals(0, decision.depth());
        assertTrue(MergeRulesCodec.serialize(document)
            .contains("<Node Key=\"$$Root$$\" MergeRule=\"GetFromOther\"/>")); //$NON-NLS-1$
    }

    @Test
    public void testPreservedSectionCountReportsWhatIsCarriedThrough() throws Exception
    {
        assertEquals("both blocks a rewrite carries verbatim must be counted - the Correspondences " //$NON-NLS-1$
            + "section beside the node tree AND the Properties map inside it", 2, //$NON-NLS-1$
            MergeRulesCodec.parse(FIXTURE).preservedSectionCount());
    }

    @Test
    public void testPreservedSectionCountCountsASectionBesideTheNodeTree() throws Exception
    {
        // Counting only inside MergeSettings would report 0 here and tell the caller their
        // Correspondences section was not carried - while the rewrite below proves it was.
        String withoutProperties = FIXTURE.replace(PROPERTIES_BLOCK, ""); //$NON-NLS-1$
        MergeRulesDocument document = MergeRulesCodec.parse(withoutProperties);
        assertEquals("a Correspondences section is payload too, and it hangs off Settings", 1, //$NON-NLS-1$
            document.preservedSectionCount());
        assertTrue("the section the count reports must be the one a rewrite keeps", //$NON-NLS-1$
            MergeRulesCodec.serialize(document)
                .contains("<MainConfiguration>Catalog.Alpha</MainConfiguration>")); //$NON-NLS-1$
    }

    // ==================== refusals ====================

    @Test
    public void testParseRefusesAForeignRootElementNamingWhatItFound()
    {
        try
        {
            MergeRulesCodec.parse("<?xml version=\"1.0\"?><Configuration Name=\"X\"/>"); //$NON-NLS-1$
            fail("a configuration file is not a merge-rules file"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name what was found: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("Configuration")); //$NON-NLS-1$
            assertTrue("and what was expected", e.getMessage().contains("Settings")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testParseRefusesAnUnsupportedFormatVersion()
    {
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"1.0\"><MergeSettings/></Settings>"); //$NON-NLS-1$
            fail("only the version EDT itself accepts may be read"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the version found: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("1.0")); //$NON-NLS-1$
            assertTrue("and the one supported", e.getMessage().contains("2.0")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testParseRefusesMalformedXml()
    {
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>"); //$NON-NLS-1$
            fail("a truncated document must be refused, not half-read"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertNotNull(e.getMessage());
        }
    }

    // ==================== containers ====================

    @Test
    public void testReadsTheZipFormAndNamesTheEntryItRead() throws Exception
    {
        Path zip = workDir.resolve("rules.zip"); //$NON-NLS-1$
        writeZip(zip, List.of("Main_Other_Ancestor.xml")); //$NON-NLS-1$

        MergeRulesDocument document = MergeRulesCodec.read(zip);
        assertEquals(4, document.decisions().size());
        assertTrue("a report must say WHICH entry was read, not just 'the file': " //$NON-NLS-1$
            + document.sourceLabel(), document.sourceLabel().endsWith("!Main_Other_Ancestor.xml")); //$NON-NLS-1$
    }

    @Test
    public void testAmbiguousZipIsRefusedNamingTheEntries() throws Exception
    {
        Path zip = workDir.resolve("two.zip"); //$NON-NLS-1$
        writeZip(zip, List.of("A_B_C.xml", "D_E_F.xml")); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            MergeRulesCodec.read(zip);
            fail("picking one of two comparisons' settings silently would be a guess"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the entries: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("A_B_C.xml") && e.getMessage().contains("D_E_F.xml")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testWriteThenReadRoundTripsThroughTheFilesystem() throws Exception
    {
        Path file = workDir.resolve("out.xml"); //$NON-NLS-1$
        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE));
        assertEquals(FIXTURE, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(4, MergeRulesCodec.read(file).decisions().size());
        assertEquals(file.toString(), MergeRulesCodec.read(file).sourceLabel());
    }

    @Test
    public void testWriteLeavesNoTemporaryFileBehind() throws Exception
    {
        Path file = workDir.resolve("out.xml"); //$NON-NLS-1$
        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE));
        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE));
        try (Stream<Path> list = Files.list(workDir))
        {
            assertEquals("the write goes through a temporary that must be moved, not left", //$NON-NLS-1$
                List.of(file), list.toList());
        }
    }

    // ==================== helpers ====================

    private static Decision decisionFor(String key) throws Exception
    {
        return decision(MergeRulesCodec.parse(FIXTURE), key);
    }

    private static Decision decision(MergeRulesDocument document, String key)
    {
        for (Decision decision : document.decisions())
        {
            if (key.equals(decision.key()))
            {
                return decision;
            }
        }
        fail("the fixture has no decision keyed '" + key + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        return null;
    }

    private static List<String> describe(List<Decision> decisions)
    {
        List<String> described = new ArrayList<>();
        for (Decision decision : decisions)
        {
            described.add(String.join("/", decision.path()) + "=" + decision.rule() + "@" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + decision.orderSide());
        }
        return described;
    }

    @Test
    public void testAFailedWriteLeavesNoTemporaryBehind()
        throws IOException, MergeRulesCodec.MergeRulesFormatException
    {
        // The temporary is a sibling of the CALLER's file, so litter lands in a directory they own,
        // and a later write cannot tell that leftover from a real artefact. Making the target a
        // non-empty DIRECTORY fails the move while the temporary already exists.
        Path target = workDir.resolve("rules.xml"); //$NON-NLS-1$
        Files.createDirectory(target);
        Files.write(target.resolve("occupant.txt"), "x".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE));
            fail("writing over a non-empty directory must fail"); //$NON-NLS-1$
        }
        catch (IOException expected)
        {
            // The point of the test is what is left on disk afterwards.
        }

        // Any leftover, not one predicted name: the temporary is per-operation now, so naming it
        // would make this assertion true of a directory full of litter.
        try (Stream<Path> list = Files.list(workDir))
        {
            assertEquals("a failed write must leave no temporary behind", List.of(target), //$NON-NLS-1$
                list.toList());
        }
    }

    private static void writeZip(Path zip, List<String> entryNames) throws IOException
    {
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zipOut = new ZipOutputStream(out))
        {
            for (String name : entryNames)
            {
                zipOut.putNextEntry(new ZipEntry(name));
                zipOut.write(FIXTURE.getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }
        }
    }

    // ============ The temporary is per OPERATION, not per target ============

    /**
     * The defect: the temporary was always {@code <target>.tmp}, so every write aimed at one path
     * used the SAME scratch file. Two concurrent {@code merge_rules} writes interleaved as
     * write-write-move-move - the second overwrote the first's bytes before either move ran, both
     * moves succeeded, and BOTH calls reported that the document they had just validated was the
     * one on disk, while the file held one set of rules and nobody could tell whose.
     *
     * <p>Pinned deterministically by leaving a file at the fixed legacy path and requiring the
     * write to touch neither it nor its bytes: a writer that still used a name derived only from
     * the target would overwrite it and then move it over the target, so it would be gone.</p>
     */
    @Test
    public void testTheTemporaryIsPerOperationAndNotDerivedFromTheTargetAlone() throws Exception
    {
        Path file = workDir.resolve("out.xml"); //$NON-NLS-1$
        Path fixedName = workDir.resolve("out.xml.tmp"); //$NON-NLS-1$
        Files.write(fixedName, "another writer's half-written bytes".getBytes( //$NON-NLS-1$
            StandardCharsets.UTF_8));

        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE));

        assertEquals("the write must still land its own document", FIXTURE, //$NON-NLS-1$
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertTrue("a temporary named after the target alone is shared by every writer aiming " //$NON-NLS-1$
            + "at that path", Files.exists(fixedName)); //$NON-NLS-1$
        assertEquals("and this write must not have taken another writer's scratch file", //$NON-NLS-1$
            "another writer's half-written bytes", //$NON-NLS-1$
            new String(Files.readAllBytes(fixedName), StandardCharsets.UTF_8));
    }

    /**
     * The property itself: whatever ends up on disk after concurrent writes is ONE writer's
     * complete document, never a mixture of two. With a per-operation temporary this holds by
     * construction, which is why this passes deterministically here; on the shared temporary it
     * held only by luck.
     *
     * <p>A move refused by the operating system is tolerated and counted rather than failed:
     * replacing a file another thread is replacing at the same instant is allowed to fail on
     * Windows, and that is a refusal, not a corrupted document. What may never happen is a write
     * that RETURNS and leaves something no writer ever serialized.</p>
     */
    @Test
    public void testConcurrentWritesToOnePathNeverLeaveASpliceOfTwoDocuments() throws Exception
    {
        Path file = workDir.resolve("shared.xml"); //$NON-NLS-1$
        int writers = 6;
        int rounds = 25;
        List<String> documents = new ArrayList<>();
        for (int writer = 0; writer < writers; writer++)
        {
            // Different LENGTHS as well as different content, so a splice cannot coincidentally
            // read back as one of the whole documents.
            StringBuilder name = new StringBuilder("Catalog.Alpha"); //$NON-NLS-1$
            for (int pad = 0; pad <= writer * 7; pad++)
            {
                name.append('x');
            }
            documents.add(MergeRulesCodec.serialize(
                MergeRulesCodec.parse(FIXTURE.replace("Catalog.Alpha", name.toString())))); //$NON-NLS-1$
        }

        AtomicInteger splices = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger landed = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int writer = 0; writer < writers; writer++)
        {
            String text = documents.get(writer);
            threads.add(new Thread(() -> {
                try
                {
                    go.await();
                    for (int round = 0; round < rounds; round++)
                    {
                        try
                        {
                            MergeRulesCodec.write(file, MergeRulesCodec.parse(text));
                            landed.incrementAndGet();
                            String onDisk = new String(Files.readAllBytes(file),
                                StandardCharsets.UTF_8);
                            if (!documents.contains(onDisk))
                            {
                                splices.incrementAndGet();
                            }
                        }
                        catch (IOException e)
                        {
                            refused.incrementAndGet();
                        }
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                catch (MergeRulesFormatException e)
                {
                    throw new IllegalStateException(e);
                }
            }));
        }
        threads.forEach(Thread::start);
        go.countDown();
        for (Thread thread : threads)
        {
            thread.join(TimeUnit.SECONDS.toMillis(60));
        }

        assertEquals("a write that returned left bytes no writer ever serialized (" //$NON-NLS-1$
            + refused.get() + " writes were refused by the OS)", 0, splices.get()); //$NON-NLS-1$
        assertTrue("the test proves nothing unless writes actually landed", landed.get() > 0); //$NON-NLS-1$
    }
}
