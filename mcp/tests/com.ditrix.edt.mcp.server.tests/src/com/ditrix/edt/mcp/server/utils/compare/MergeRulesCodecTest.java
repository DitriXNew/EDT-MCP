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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.After;
import org.junit.Assume;
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

    // ============ Mixed content: text keeps its place among the children ============

    /**
     * A payload section with text BOTH before and after a child element - the shape a single text
     * buffer per parse cannot express.
     * <p>
     * Already in the canonical layout, so "round trip" here means byte for byte and not "modulo
     * whitespace": that is the codec's stated promise for a file it has written or read.
     */
    private static final String MIXED_CONTENT_FIXTURE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
        + "<Settings Format_version=\"2.0\">\n" //$NON-NLS-1$
        + "  <Correspondences>\n" //$NON-NLS-1$
        + "    a note before the child\n" //$NON-NLS-1$
        + "    <Correspondence>\n" //$NON-NLS-1$
        + "      <MainConfiguration>Catalog.Alpha</MainConfiguration>\n" //$NON-NLS-1$
        + "    </Correspondence>\n" //$NON-NLS-1$
        + "    a note after the child\n" //$NON-NLS-1$
        + "  </Correspondences>\n" //$NON-NLS-1$
        + "  <MergeSettings>\n" //$NON-NLS-1$
        + "    <Node Key=\"$$Root$$\">\n" //$NON-NLS-1$
        + "      <Node Key=\"commonModules\" MergeRule=\"GetFromOther\"/>\n" //$NON-NLS-1$
        + "    </Node>\n" //$NON-NLS-1$
        + "  </MergeSettings>\n" //$NON-NLS-1$
        + "</Settings>\n"; //$NON-NLS-1$

    @Test
    public void testMixedContentSurvivesARewriteByteForByte() throws Exception
    {
        assertEquals("a payload block with text around a child element is exactly the payload the " //$NON-NLS-1$
            + "codec promises to carry through verbatim", MIXED_CONTENT_FIXTURE, //$NON-NLS-1$
            MergeRulesCodec.serialize(MergeRulesCodec.parse(MIXED_CONTENT_FIXTURE)));
    }

    /**
     * Its own test rather than a second assertion in the one above: JUnit stops a method at the
     * first failed assertion, so a byte comparison that fails would hide which half broke - and
     * the two halves broke for different reasons (one run was dropped, the other was moved).
     */
    @Test
    public void testTextBeforeAChildElementIsNotDropped() throws Exception
    {
        assertTrue("the run that precedes a child element used to be cleared and lost", //$NON-NLS-1$
            MergeRulesCodec.serialize(MergeRulesCodec.parse(MIXED_CONTENT_FIXTURE))
                .contains("a note before the child")); //$NON-NLS-1$
    }

    @Test
    public void testTextAfterAChildElementStaysAfterIt() throws Exception
    {
        String rewritten = MergeRulesCodec.serialize(MergeRulesCodec.parse(MIXED_CONTENT_FIXTURE));
        assertTrue("the trailing run used to be re-emitted as the parent's own text, i.e. BEFORE " //$NON-NLS-1$
            + "every child: " + rewritten, //$NON-NLS-1$
            rewritten.indexOf("a note after the child") //$NON-NLS-1$
                > rewritten.indexOf("</Correspondence>")); //$NON-NLS-1$
    }

    @Test
    public void testMixedContentIsIdempotentOnASecondRewrite() throws Exception
    {
        String once = MergeRulesCodec.serialize(MergeRulesCodec.parse(MIXED_CONTENT_FIXTURE));
        assertEquals("a second round trip must not drift", once, //$NON-NLS-1$
            MergeRulesCodec.serialize(MergeRulesCodec.parse(once)));
    }

    @Test
    public void testInteriorTextIsNotCountedAsAPreservedSection() throws Exception
    {
        // Character data is the text of the element it sits in. Counting it would report blocks a
        // reader cannot find in the file - the count is what tells a caller their payload is still
        // there, so it may not be inflated by the payload's own words.
        assertEquals("only the Correspondences section is a block this tool does not interpret", 1, //$NON-NLS-1$
            MergeRulesCodec.parse(MIXED_CONTENT_FIXTURE).preservedSectionCount());
    }

    // ============ A write must not destroy the identity of its target ============

    @Test
    public void testWriteFollowsASymbolicLinkInsteadOfReplacingIt() throws Exception
    {
        Path real = workDir.resolve("real.xml"); //$NON-NLS-1$
        Files.write(real, FIXTURE.getBytes(StandardCharsets.UTF_8));
        Path link = workDir.resolve("link.xml"); //$NON-NLS-1$
        try
        {
            Files.createSymbolicLink(link, real);
        }
        catch (IOException | UnsupportedOperationException e)
        {
            Assume.assumeNoException("this filesystem or account cannot create symbolic links", e); //$NON-NLS-1$
        }

        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);
        document.setMergeRule(List.of("catalogs"), "DoNotMerge"); //$NON-NLS-1$ //$NON-NLS-2$
        MergeRulesCodec.write(link, document);

        assertTrue("moving over a link replaces the ENTRY, deleting the link and leaving the file " //$NON-NLS-1$
            + "it named untouched - while the report says the rules were written", //$NON-NLS-1$
            Files.isSymbolicLink(link));
        assertTrue("the file the link names is the file that had to be updated", //$NON-NLS-1$
            new String(Files.readAllBytes(real), StandardCharsets.UTF_8)
                .contains("Key=\"catalogs\"")); //$NON-NLS-1$
    }

    @Test
    public void testWriteStillReplacesAPlainExistingFile() throws Exception
    {
        // The control for the link handling above: an ordinary target must still be replaced.
        Path file = workDir.resolve("plain.xml"); //$NON-NLS-1$
        Files.write(file, "stale".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE));
        assertEquals(FIXTURE, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    // ============ A zip entry may not inflate without a bound ============

    @Test
    public void testAZipEntryThatExpandsPastTheBoundIsRefusedNamingIt() throws Exception
    {
        Path zip = workDir.resolve("bomb.zip"); //$NON-NLS-1$
        writeInflatingZip(zip, "Main_Other_Ancestor.xml", 20 * 1024 * 1024); //$NON-NLS-1$
        // The archive itself is tiny; what it unpacks to is not. Reading it whole would spend the
        // workbench's heap before a single tag had been looked at.
        assertTrue("the point of the fixture is that a small file expands hugely", //$NON-NLS-1$
            Files.size(zip) < 256 * 1024);

        try
        {
            MergeRulesCodec.read(zip);
            fail("an entry that unpacks past the bound must be refused, not inflated"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the entry it stopped on: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("Main_Other_Ancestor.xml")); //$NON-NLS-1$
            assertTrue("and say what to do instead: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("Extract the entry")); //$NON-NLS-1$
        }
    }

    @Test
    public void testALyingZipHeaderDoesNotRaiseTheBound() throws Exception
    {
        // The declared size comes from the archive, so it is the attacker's own number. The bound
        // is counted on bytes actually read, and this entry declares a small one while unpacking
        // far past it.
        Path zip = workDir.resolve("liar.zip"); //$NON-NLS-1$
        try (OutputStream out = Files.newOutputStream(zip);
            ZipOutputStream zipOut = new ZipOutputStream(out))
        {
            ZipEntry entry = new ZipEntry("Main_Other_Ancestor.xml"); //$NON-NLS-1$
            entry.setSize(FIXTURE.length());
            zipOut.putNextEntry(entry);
            writeFiller(zipOut, 20 * 1024 * 1024);
            zipOut.closeEntry();
        }

        try
        {
            MergeRulesCodec.read(zip);
            fail("the bound may not be taken from the header the archive supplies"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testAnOrdinaryZipEntryIsStillReadWhole() throws Exception
    {
        // The control: the bound must not have turned into a smaller read. Thousands of real
        // decisions are an ordinary file and have to come back complete.
        Path zip = workDir.resolve("large-but-real.zip"); //$NON-NLS-1$
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<Settings Format_version=\"2.0\">\n  <MergeSettings>\n    <Node Key=\"$$Root$$\">\n"); //$NON-NLS-1$
        int decisions = 8000;
        for (int i = 0; i < decisions; i++)
        {
            xml.append("      <Node Key=\"catalogs").append(i) //$NON-NLS-1$
                .append("\" MergeRule=\"GetFromOther\"/>\n"); //$NON-NLS-1$
        }
        xml.append("    </Node>\n  </MergeSettings>\n</Settings>\n"); //$NON-NLS-1$
        try (OutputStream out = Files.newOutputStream(zip);
            ZipOutputStream zipOut = new ZipOutputStream(out))
        {
            zipOut.putNextEntry(new ZipEntry("Main_Other_Ancestor.xml")); //$NON-NLS-1$
            zipOut.write(xml.toString().getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }

        assertEquals("every decision must come back - the bound guards the heap, it does not " //$NON-NLS-1$
            + "truncate a real file", decisions, MergeRulesCodec.read(zip).decisions().size()); //$NON-NLS-1$
    }


    // ======== Mixed content: the whitespace beside a child element is part of the value ========

    /**
     * A payload block whose text runs BUTT UP against a child element.
     * <p>
     * This is where "layout" and "content" whitespace part company: the two spaces are inside the
     * element's character data, so a rewrite that trims them hands the next reader a different
     * value for a block the codec promises to carry through verbatim. The block is written back
     * inline for the same reason - a newline or an indent inserted beside the child would land
     * INSIDE that character data.
     */
    private static final String EXACT_MIXED_FIXTURE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
        + "<Settings Format_version=\"2.0\">\n" //$NON-NLS-1$
        + "  <Payload>Hello <Child/> world</Payload>\n" //$NON-NLS-1$
        + "  <MergeSettings>\n" //$NON-NLS-1$
        + "    <Node Key=\"$$Root$$\">\n" //$NON-NLS-1$
        + "      <Node Key=\"commonModules\" MergeRule=\"GetFromOther\"/>\n" //$NON-NLS-1$
        + "    </Node>\n" //$NON-NLS-1$
        + "  </MergeSettings>\n" //$NON-NLS-1$
        + "</Settings>\n"; //$NON-NLS-1$

    @Test
    public void testTheSpacesBesideAChildElementAreKeptExactly() throws Exception
    {
        // Trimming the run that ends at the child and the one that starts after it changed the
        // payload's parsed value from "Hello " + " world" to "Hello" + "world" - a rewrite of the
        // caller's data, reported as a verbatim carry-through.
        assertTrue("the character data around a child element is data, not indentation", //$NON-NLS-1$
            MergeRulesCodec.serialize(MergeRulesCodec.parse(EXACT_MIXED_FIXTURE))
                .contains(">Hello <Child/> world<")); //$NON-NLS-1$
    }

    @Test
    public void testAMixedPayloadRoundTripsByteForByte() throws Exception
    {
        assertEquals("a block with text touching a child element must come back as it went in", //$NON-NLS-1$
            EXACT_MIXED_FIXTURE,
            MergeRulesCodec.serialize(MergeRulesCodec.parse(EXACT_MIXED_FIXTURE)));
    }

    @Test
    public void testAMixedPayloadIsIdempotentOnASecondRewrite() throws Exception
    {
        String once = MergeRulesCodec.serialize(MergeRulesCodec.parse(EXACT_MIXED_FIXTURE));
        assertEquals("keeping the whitespace may not make the rewrite drift instead", once, //$NON-NLS-1$
            MergeRulesCodec.serialize(MergeRulesCodec.parse(once)));
    }

    @Test
    public void testWhitespaceBetweenTwoChildrenOfAMixedElementIsContentToo() throws Exception
    {
        // The run between <B/> and <C/> is whitespace ALONE, and it is still part of the value:
        // the element it sits in says something, so its character data is data. A rule asked of
        // the run instead of the element cannot see that, and would delete this one space.
        String fixture = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<Settings Format_version=\"2.0\">\n" //$NON-NLS-1$
            + "  <Payload>a <B/> <C/> b</Payload>\n" //$NON-NLS-1$
            + "  <MergeSettings>\n" //$NON-NLS-1$
            + "    <Node Key=\"$$Root$$\"/>\n" //$NON-NLS-1$
            + "  </MergeSettings>\n" //$NON-NLS-1$
            + "</Settings>\n"; //$NON-NLS-1$

        assertTrue("the space between two children of a mixed element must survive", //$NON-NLS-1$
            MergeRulesCodec.serialize(MergeRulesCodec.parse(fixture)).contains(">a <B/> <C/> b<")); //$NON-NLS-1$
    }

    /** Character data holding a CR, which the file can only spell as a reference. */
    private static final String CARRIAGE_RETURN_FIXTURE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
        + "<Settings Format_version=\"2.0\">\n" //$NON-NLS-1$
        + "  <Payload>line&#13; <Child/></Payload>\n" //$NON-NLS-1$
        + "  <MergeSettings>\n" //$NON-NLS-1$
        + "    <Node Key=\"$$Root$$\"/>\n" //$NON-NLS-1$
        + "  </MergeSettings>\n" //$NON-NLS-1$
        + "</Settings>\n"; //$NON-NLS-1$

    @Test
    public void testACarriageReturnInCharacterDataIsWrittenAsAReference() throws Exception
    {
        // XML normalises line ends before a parser reports any character data, so a CR written as
        // itself comes back as LF. Now that the run is kept verbatim, writing it raw would be a
        // silent edit of the value on the very next read.
        assertTrue("a CR must go back as the reference it came from", //$NON-NLS-1$
            MergeRulesCodec.serialize(MergeRulesCodec.parse(CARRIAGE_RETURN_FIXTURE))
                .contains("&#13;")); //$NON-NLS-1$
    }

    @Test
    public void testACarriageReturnDoesNotDriftOnASecondRewrite() throws Exception
    {
        String once = MergeRulesCodec.serialize(MergeRulesCodec.parse(CARRIAGE_RETURN_FIXTURE));
        assertEquals("a run kept verbatim must survive the trip through a reader", once, //$NON-NLS-1$
            MergeRulesCodec.serialize(MergeRulesCodec.parse(once)));
    }

    // ============ Nesting is bounded where it enters, not where it overflows ============

    @Test
    public void testADocumentNestedPastTheBoundIsRefusedNamingIt() throws Exception
    {
        // Reading is iterative and swallows any depth; the walks over what it produces - the
        // serializer, decisions(), the section count - are recursive. Left unbounded the file
        // parses and the REWRITE dies of a StackOverflowError, which is an Error and so is neither
        // catchable as a bad format nor reportable as one: the write would abort part-way instead
        // of being refused.
        try
        {
            MergeRulesCodec.parse(nested(600));
            fail("a document nested past the bound must be refused, not accepted and re-emitted"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the bound it applied: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("500")); //$NON-NLS-1$
            assertTrue("and say what was wrong with the document: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("nests")); //$NON-NLS-1$
        }
    }

    @Test
    public void testADeepButPlausibleDocumentIsStillReadAndRewritten() throws Exception
    {
        // The control: the bound guards the stack, it does not refuse depth a real file could
        // have. This also walks all three recursive walkers at a hundred levels.
        MergeRulesDocument document = MergeRulesCodec.parse(nested(100));
        assertEquals("a payload-free node tree holds no section to preserve", 0, //$NON-NLS-1$
            document.preservedSectionCount());
        String once = MergeRulesCodec.serialize(document);
        assertEquals("a deep document must round-trip like any other", once, //$NON-NLS-1$
            MergeRulesCodec.serialize(MergeRulesCodec.parse(once)));
    }

    /**
     * Builds a well-formed merge-settings document whose node tree is {@code depth} levels deep.
     *
     * @param depth how many {@code Node} elements to nest
     * @return the document text
     */
    private static String nested(int depth)
    {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<Settings Format_version=\"2.0\">\n  <MergeSettings>\n    "); //$NON-NLS-1$
        for (int i = 0; i < depth; i++)
        {
            xml.append("<Node Key=\"n").append(i).append("\">"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (int i = 0; i < depth; i++)
        {
            xml.append("</Node>"); //$NON-NLS-1$
        }
        xml.append("\n  </MergeSettings>\n</Settings>\n"); //$NON-NLS-1$
        return xml.toString();
    }

    // ============ A chain of symbolic links is followed to its END ============

    @Test
    public void testEveryLinkInAChainIsFollowed() throws Exception
    {
        // Resolving one hop put the write on the INTERMEDIATE link, which the move then replaced
        // with a regular file: a link nobody mentioned deleted, the file at the end of the chain
        // left with its old content, and the call reporting the rules as written.
        Path first = workDir.resolve("first.xml"); //$NON-NLS-1$
        Path second = workDir.resolve("second.xml"); //$NON-NLS-1$
        Path end = workDir.resolve("end.xml"); //$NON-NLS-1$

        assertEquals("the chain must be walked to the file at the end of it", end, //$NON-NLS-1$
            MergeRulesCodec.walkLinkChain(first, links(Map.of(first, second, second, end))));
    }

    @Test
    public void testARelativeDestinationIsResolvedAgainstItsOwnLink() throws Exception
    {
        // Each hop's destination is recorded relative to THAT hop's directory. Resolving every hop
        // against the first link's directory would name a file in the wrong place - and create it.
        Path first = workDir.resolve("here").resolve("first.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        Path second = workDir.resolve("there").resolve("second.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<Path, Path> chain = new HashMap<>();
        chain.put(first, Path.of("..", "there", "second.xml")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        chain.put(second, Path.of("end.xml")); //$NON-NLS-1$

        assertEquals("the last hop's own directory is where its destination lives", //$NON-NLS-1$
            workDir.resolve("there").resolve("end.xml"), //$NON-NLS-1$ //$NON-NLS-2$
            MergeRulesCodec.walkLinkChain(first, links(chain)));
    }

    @Test
    public void testARingOfLinksIsRefusedInsteadOfBeingFollowedForEver() throws Exception
    {
        Path first = workDir.resolve("first.xml"); //$NON-NLS-1$
        Path second = workDir.resolve("second.xml"); //$NON-NLS-1$
        try
        {
            MergeRulesCodec.walkLinkChain(first, links(Map.of(first, second, second, first)));
            fail("a ring has no file at the end of it, so it cannot be resolved into one"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            assertTrue("the refusal must say what it found: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("ring")); //$NON-NLS-1$
            assertTrue("and name the links that form it: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains(second.toString()));
        }
    }

    @Test
    public void testALinkPointingAtItselfIsARingToo() throws Exception
    {
        Path self = workDir.resolve("self.xml"); //$NON-NLS-1$
        try
        {
            MergeRulesCodec.walkLinkChain(self, links(Map.of(self, self)));
            fail("a link to itself is the shortest ring there is"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("ring")); //$NON-NLS-1$
        }
    }

    @Test
    public void testAChainThatNeverEndsIsRefusedByTheHopBound() throws Exception
    {
        // A chain that repeats no path can still be endless - a link reached through a linked
        // directory grows the path at every hop - so "seen this one already" is not on its own a
        // reason to stop.
        Map<Path, Path> chain = new HashMap<>();
        for (int i = 0; i < 100; i++)
        {
            chain.put(workDir.resolve("hop" + i), workDir.resolve("hop" + (i + 1))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            MergeRulesCodec.walkLinkChain(workDir.resolve("hop0"), links(chain)); //$NON-NLS-1$
            fail("the walk must stop somewhere even when no path ever repeats"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            assertTrue("the refusal must name the bound: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("40")); //$NON-NLS-1$
        }
    }

    @Test
    public void testWriteCreatesTheFileAtTheEndOfADanglingChain() throws Exception
    {
        // The same walk through the real filesystem, where the two links exist and the file at the
        // end of them does not.
        Path end = workDir.resolve("end.xml"); //$NON-NLS-1$
        Path second = workDir.resolve("second.xml"); //$NON-NLS-1$
        Path first = workDir.resolve("first.xml"); //$NON-NLS-1$
        try
        {
            Files.createSymbolicLink(second, end);
            Files.createSymbolicLink(first, second);
        }
        catch (IOException | UnsupportedOperationException e)
        {
            Assume.assumeNoException("this filesystem or account cannot create symbolic links", e); //$NON-NLS-1$
        }

        MergeRulesCodec.write(first, MergeRulesCodec.parse(FIXTURE));

        assertTrue("the file the chain names must be the one created", Files.isRegularFile(end)); //$NON-NLS-1$
        assertTrue("the intermediate link may not be replaced by the written file", //$NON-NLS-1$
            Files.isSymbolicLink(second));
        assertTrue("nor the first one", Files.isSymbolicLink(first)); //$NON-NLS-1$
        assertEquals("and the bytes must be the document, not a fragment", FIXTURE, //$NON-NLS-1$
            new String(Files.readAllBytes(end), StandardCharsets.UTF_8));
    }

    /**
     * A link reader backed by a map, so the walk can be proved where the filesystem grants no
     * symbolic links.
     *
     * @param chain link path to the destination recorded in it
     * @return the reader
     */
    private static MergeRulesCodec.LinkReader links(Map<Path, Path> chain)
    {
        return chain::get;
    }

    /**
     * Writes a zip whose single entry unpacks to {@code expandedBytes} of highly compressible
     * data, so the archive on disk stays small.
     *
     * @param zip the archive to create
     * @param entryName the entry name
     * @param expandedBytes how much the entry unpacks to
     * @throws IOException when the archive cannot be written
     */
    private static void writeInflatingZip(Path zip, String entryName, int expandedBytes)
        throws IOException
    {
        try (OutputStream out = Files.newOutputStream(zip);
            ZipOutputStream zipOut = new ZipOutputStream(out))
        {
            zipOut.putNextEntry(new ZipEntry(entryName));
            writeFiller(zipOut, expandedBytes);
            zipOut.closeEntry();
        }
    }

    /**
     * Writes compressible filler that also happens to open a well-formed document, so a refusal
     * cannot be mistaken for "this was not XML".
     *
     * @param out the stream
     * @param bytes how much to write
     * @throws IOException when the stream cannot be written
     */
    private static void writeFiller(OutputStream out, int bytes) throws IOException
    {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Settings Format_version=\"2.0\">\n" //$NON-NLS-1$
            .getBytes(StandardCharsets.UTF_8));
        byte[] chunk = new byte[64 * 1024];
        Arrays.fill(chunk, (byte)' ');
        for (int written = 0; written < bytes; written += chunk.length)
        {
            out.write(chunk);
        }
        out.write("</Settings>\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
    }
}
