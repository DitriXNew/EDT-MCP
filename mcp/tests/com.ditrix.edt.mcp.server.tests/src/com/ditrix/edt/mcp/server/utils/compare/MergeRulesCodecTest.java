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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        MergeRulesCodec.write(file, document, MergeRulesCodec.Target.MAY_BE_REPLACED);

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

    // ==================== two separators are the SHAPE, not the proof ====================
    //
    // One literal per @Test: JUnit stops a method at its first failed assertion, so pins bundled
    // into one method only ever load the first of them.

    @Test
    public void testAnEmptyMiddleComponentIsNotATopObjectKey()
    {
        // 'A::A' has exactly two separators, and the middle part is not a name and not NONE - it
        // is nothing. EDT matches these keys by string equality, so it addresses no node in any
        // comparison.
        assertFalse("an empty component names no side", MergeRulesDocument.isTopObjectKey("A::A")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnEmptyFirstComponentIsNotATopObjectKey()
    {
        assertFalse(MergeRulesDocument.isTopObjectKey(":B:C")); //$NON-NLS-1$
    }

    @Test
    public void testAnEmptyLastComponentIsNotATopObjectKey()
    {
        assertFalse(MergeRulesDocument.isTopObjectKey("A:B:")); //$NON-NLS-1$
    }

    @Test
    public void testAWhitespaceOnlyComponentIsNotATopObjectKeyEither()
    {
        assertFalse("a space is not a name: EDT would look for a node called ' '", //$NON-NLS-1$
            MergeRulesDocument.isTopObjectKey("A: :C")); //$NON-NLS-1$
    }

    @Test
    public void testNoneIsALegalComponentBecauseItNamesAnAbsentObject()
    {
        // The control that keeps the rule honest: NONE is the platform's own spelling for "the
        // object does not exist on this side", so it names something and must stay accepted.
        assertTrue(MergeRulesDocument.isTopObjectKey("Added:NONE:NONE")); //$NON-NLS-1$
    }

    @Test
    public void testAMalformedKeyStillHasTheShapeOfATopObjectKey()
    {
        // The two questions are different: a caller who wrote 'A::A' meant a top-object key and
        // has to be told so, rather than have it read as some other kind of key.
        assertTrue(MergeRulesDocument.hasTopObjectKeyShape("A::A")); //$NON-NLS-1$
        assertFalse(MergeRulesDocument.hasTopObjectKeyShape("commonModules")); //$NON-NLS-1$
    }

    @Test
    public void testTheEmptySidesOfAMalformedKeyAreNamedInOrder()
    {
        assertEquals(List.of("other", "ancestor"), //$NON-NLS-1$ //$NON-NLS-2$
            MergeRulesDocument.emptyTopObjectKeySides("A::")); //$NON-NLS-1$
    }

    @Test
    public void testAWellFormedKeyHasNoEmptySides()
    {
        assertTrue(MergeRulesDocument.emptyTopObjectKeySides("A:NONE:C").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAKeyThatIsNotThatShapeReportsNoSidesAtAll()
    {
        // Not "all three are empty": a key with no separators has no sides to report on, and
        // answering otherwise would make every collection name look like a malformed object key.
        assertTrue(MergeRulesDocument.emptyTopObjectKeySides("commonModules").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testAMalformedKeyReadFromAFileIsNotReportedAsThreeNames() throws Exception
    {
        // The read side of the same rule: a file that carries 'A::A' must not be reported with
        // main/other/ancestor columns filled in from a key that addresses nothing.
        MergeRulesDocument document = MergeRulesCodec.parse("<?xml version=\"1.0\"?>" //$NON-NLS-1$
            + "<Settings Format_version=\"2.0\"><MergeSettings><Node Key=\"$$Root$$\">" //$NON-NLS-1$
            + "<Node Key=\"commonModules\"><Node Key=\"A::A\" MergeRule=\"DoNotMerge\"/>" //$NON-NLS-1$
            + "</Node></Node></MergeSettings></Settings>"); //$NON-NLS-1$

        Decision decision = document.decisions().get(0);

        assertEquals("A::A", decision.key()); //$NON-NLS-1$
        assertTrue("a key that matches no node must not be presented as three names", //$NON-NLS-1$
            decision.topObjectKey().isEmpty());
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
        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE), MergeRulesCodec.Target.MAY_BE_REPLACED);
        assertEquals(FIXTURE, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(4, MergeRulesCodec.read(file).decisions().size());
        assertEquals(file.toString(), MergeRulesCodec.read(file).sourceLabel());
    }

    @Test
    public void testWriteLeavesNoTemporaryFileBehind() throws Exception
    {
        Path file = workDir.resolve("out.xml"); //$NON-NLS-1$
        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE), MergeRulesCodec.Target.MAY_BE_REPLACED);
        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE), MergeRulesCodec.Target.MAY_BE_REPLACED);
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
            MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE), MergeRulesCodec.Target.MAY_BE_REPLACED);
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

        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE), MergeRulesCodec.Target.MAY_BE_REPLACED);

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
                            MergeRulesCodec.write(file, MergeRulesCodec.parse(text), MergeRulesCodec.Target.MAY_BE_REPLACED);
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

    // ============ A replacement must not narrow who can read the file ============

    /**
     * The bytes land in a temporary and the temporary is MOVED over the target, so what ends up on
     * the path is the temporary's inode wearing the temporary's mode - and
     * {@code Files.createTempFile} creates one readable by its owner alone. A merge-rules file a
     * team shares would therefore have been narrowed to whoever ran the write, on every save, with
     * nothing in the answer saying so.
     * <p>
     * Skipped rather than failed where there is no POSIX mode to speak of: Windows has no concept
     * for this test to assert about, and a test that reddens there is a test that gets deleted.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void testReplacingAFileKeepsThePermissionsItHad() throws Exception
    {
        assumePosix();
        Path file = workDir.resolve("shared.xml"); //$NON-NLS-1$
        Files.write(file, FIXTURE.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--")); //$NON-NLS-1$

        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);
        document.setMergeRule(List.of("catalogs"), "DoNotMerge"); //$NON-NLS-1$ //$NON-NLS-2$
        MergeRulesCodec.write(file, document, MergeRulesCodec.Target.MAY_BE_REPLACED);

        assertEquals("a shared rules file must still be the team's after this tool rewrites it", //$NON-NLS-1$
            "rw-rw-r--", //$NON-NLS-1$
            PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    /**
     * The control for the test above: the bytes really were replaced. Without it, a write that
     * failed to write anything at all would keep the mode and pass.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void testTheFileWhosePermissionsSurvivedIsTheOneThatWasRewritten() throws Exception
    {
        assumePosix();
        Path file = workDir.resolve("shared.xml"); //$NON-NLS-1$
        Files.write(file, FIXTURE.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--")); //$NON-NLS-1$

        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);
        document.setMergeRule(List.of("catalogs"), "DoNotMerge"); //$NON-NLS-1$ //$NON-NLS-2$
        MergeRulesCodec.write(file, document, MergeRulesCodec.Target.MAY_BE_REPLACED);

        assertTrue("the decision must be on disk, or the mode was kept by doing nothing", //$NON-NLS-1$
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .contains("DoNotMerge")); //$NON-NLS-1$
    }

    /**
     * An executable bit is carried too, and it is the sharper case: it is the one permission a
     * whitelist of "the ones that matter" would have dropped, and a copy that only ever widens
     * would keep it by accident rather than by carrying the target's mode.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void testTheWholeModeIsCarriedAndNotJustTheReadableBits() throws Exception
    {
        assumePosix();
        Path file = workDir.resolve("odd-mode.xml"); //$NON-NLS-1$
        Files.write(file, FIXTURE.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-x---")); //$NON-NLS-1$

        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE),
            MergeRulesCodec.Target.MAY_BE_REPLACED);

        assertEquals("rwxr-x---", //$NON-NLS-1$
            PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    /**
     * A path with nothing on it has no mode to inherit, and Java cannot read the umask, so the new
     * file keeps the temporary's own - which is owner-only, the restrictive direction. Pinned so
     * that "carry the target's mode" is never quietly turned into "invent one": a mode this code
     * made up would be a permission set nobody chose, on a file it is creating for the caller.
     *
     * @throws Exception when the write fails
     */
    @Test
    public void testAPathWithNothingOnItGetsNoInventedMode() throws Exception
    {
        assumePosix();
        Path file = workDir.resolve("brand-new.xml"); //$NON-NLS-1$

        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE),
            MergeRulesCodec.Target.MAY_BE_REPLACED);

        Set<PosixFilePermission> actual = Files.getPosixFilePermissions(file);
        assertEquals("a file created out of nothing keeps the temporary's own owner-only mode: " //$NON-NLS-1$
            + PosixFilePermissions.toString(actual), "rw-------", //$NON-NLS-1$
            PosixFilePermissions.toString(actual));
    }

    /**
     * {@link MergeRulesCodec.Target#MUST_NOT_EXIST} reserves the path with {@code Files.createFile}
     * before a byte is written, so by the time the mode is carried there IS something on the path -
     * the reservation, created with the process default. The finished file therefore wears that
     * default rather than the temporary's owner-only mode, and a probe created the same way is what
     * says so without this test having to guess the umask of the machine it runs on.
     *
     * @throws Exception when the write fails
     */
    @Test
    public void testAReservedPathKeepsTheModeItsReservationWasCreatedWith() throws Exception
    {
        assumePosix();
        Path probe = Files.createFile(workDir.resolve("probe.xml")); //$NON-NLS-1$
        Set<PosixFilePermission> fromCreateFile = Files.getPosixFilePermissions(probe);
        Path file = workDir.resolve("reserved.xml"); //$NON-NLS-1$

        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE),
            MergeRulesCodec.Target.MUST_NOT_EXIST);

        assertEquals("the reservation's own mode is what the finished file must wear", //$NON-NLS-1$
            PosixFilePermissions.toString(fromCreateFile),
            PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    /**
     * The write still works where there are no POSIX permissions at all - Windows. This one runs
     * everywhere, so the branch that has to do nothing is exercised on the machine where it has to
     * do nothing.
     *
     * @throws Exception when the write fails
     */
    @Test
    public void testAFilesystemWithoutPosixPermissionsIsNotAFailure() throws Exception
    {
        Path file = workDir.resolve("plain.xml"); //$NON-NLS-1$
        Files.write(file, FIXTURE.getBytes(StandardCharsets.UTF_8));

        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);
        document.setMergeRule(List.of("catalogs"), "DoNotMerge"); //$NON-NLS-1$ //$NON-NLS-2$
        MergeRulesCodec.write(file, document, MergeRulesCodec.Target.MAY_BE_REPLACED);

        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            .contains("DoNotMerge")); //$NON-NLS-1$
    }

    /** Skips a test that has nothing to assert on a filesystem with no POSIX mode. */
    private static void assumePosix()
    {
        Assume.assumeTrue("this filesystem has no POSIX permissions to preserve", //$NON-NLS-1$
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix")); //$NON-NLS-1$
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
        MergeRulesCodec.write(link, document, MergeRulesCodec.Target.MAY_BE_REPLACED);

        assertTrue("moving over a link replaces the ENTRY, deleting the link and leaving the file " //$NON-NLS-1$
            + "it named untouched - while the report says the rules were written", //$NON-NLS-1$
            Files.isSymbolicLink(link));
        assertTrue("the file the link names is the file that had to be updated", //$NON-NLS-1$
            new String(Files.readAllBytes(real), StandardCharsets.UTF_8)
                .contains("Key=\"catalogs\"")); //$NON-NLS-1$
    }

    // ============ MUST_NOT_EXIST reserves the name, it does not merely check it ============

    /**
     * The whole point of {@link MergeRulesCodec.Target#MUST_NOT_EXIST}: a caller that established
     * "there is nothing on this path" and then handed the write an unconditional replacing move
     * established it in one step and acted on it in another, so a second write that arrived in
     * between had its decisions destroyed by a call whose contract was to refuse exactly that.
     * <p>
     * The file that got there first is left EXACTLY as it was - the assertion on the content is
     * the half that matters, because a refusal that had already replaced the bytes would be no
     * refusal at all.
     */
    @Test
    public void testAWriteThatMustNotExistRefusesAFileThatGotThereFirst() throws Exception
    {
        Path target = workDir.resolve("rules.xml"); //$NON-NLS-1$
        Files.write(target, "the other write's decisions".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        try
        {
            MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE),
                MergeRulesCodec.Target.MUST_NOT_EXIST);
            fail("a write that must not replace anything must refuse an occupied path"); //$NON-NLS-1$
        }
        catch (FileAlreadyExistsException expected)
        {
            // The refusal is the point; what is on disk afterwards is the proof.
        }

        assertEquals("the file that was there must be untouched", //$NON-NLS-1$
            "the other write's decisions", //$NON-NLS-1$
            new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    /**
     * The reservation is consumed by the move, not left beside it: a successful MUST_NOT_EXIST
     * write leaves the document and nothing else.
     */
    @Test
    public void testAWriteThatMustNotExistLeavesTheDocumentAndNoLitter() throws Exception
    {
        Path target = workDir.resolve("rules.xml"); //$NON-NLS-1$

        MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE),
            MergeRulesCodec.Target.MUST_NOT_EXIST);

        assertEquals(FIXTURE, new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        try (Stream<Path> list = Files.list(workDir))
        {
            assertEquals("the reservation must be consumed by the move, not left behind", //$NON-NLS-1$
                List.of(target), list.toList());
        }
    }

    /**
     * The gap the round before this one named and could not close: the temporary was created
     * BETWEEN the reservation and the block that removes it, so a failure THERE - a filesystem out
     * of inodes, over quota, or a directory whose permissions changed between the two calls - left
     * the reservation behind. An empty file on the caller's path is worse than the failure it
     * followed: the write reports an I/O error, and every later write to that path then refuses it
     * as occupied while it holds no rules at all.
     * <p>
     * The failure is produced by the filesystem's own limit on one path component. A target name
     * just under it can be created, while the temporary - that same name plus a dot, a random
     * number and {@code .tmp} - cannot, so the failure lands exactly between the reservation and
     * the first byte, which is the window this test exists for. The precondition is PROBED rather
     * than assumed: a filesystem with a different limit skips this test instead of failing it.
     */
    @Test
    public void testAReservationIsRemovedWhenTheTemporaryCannotBeCreated() throws Exception
    {
        String name = "n".repeat(250); //$NON-NLS-1$
        Assume.assumeTrue("this filesystem accepts a temporary named after a 250-character " //$NON-NLS-1$
            + "target, so it cannot model a failure between the reservation and the bytes", //$NON-NLS-1$
            temporaryCannotBeCreatedBeside(name));
        Path target = workDir.resolve(name);

        try
        {
            MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE),
                MergeRulesCodec.Target.MUST_NOT_EXIST);
            fail("a write whose scratch file cannot be created must fail"); //$NON-NLS-1$
        }
        catch (IOException expected)
        {
            // The refusal is expected; what is left on disk afterwards is the point.
        }

        assertFalse("the reservation must not outlive the write that took it: nothing was ever " //$NON-NLS-1$
            + "written onto it, so leaving it there makes the next write refuse a path that " //$NON-NLS-1$
            + "holds no rules", Files.exists(target)); //$NON-NLS-1$
        try (Stream<Path> list = Files.list(workDir))
        {
            assertEquals("and the failed write must leave the directory as it found it", //$NON-NLS-1$
                List.of(), list.toList());
        }
    }

    /**
     * @param name the file name a write would aim at
     * @return whether this filesystem refuses the temporary such a write would create beside it -
     *         the precondition the reservation test above is built on
     * @throws IOException when the probe cannot be cleaned up again
     */
    private boolean temporaryCannotBeCreatedBeside(String name) throws IOException
    {
        Path probe = null;
        try
        {
            probe = Files.createTempFile(workDir, name + '.', ".tmp"); //$NON-NLS-1$
            return false;
        }
        catch (IOException | RuntimeException e)
        {
            return true;
        }
        finally
        {
            if (probe != null)
            {
                Files.deleteIfExists(probe);
            }
        }
    }

    @Test
    public void testWriteStillReplacesAPlainExistingFile() throws Exception
    {
        // The control for the link handling above: an ordinary target must still be replaced.
        Path file = workDir.resolve("plain.xml"); //$NON-NLS-1$
        Files.write(file, "stale".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE), MergeRulesCodec.Target.MAY_BE_REPLACED);
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


    // ==== A comment and a processing instruction are payload, not decoration ====

    /**
     * A file annotated the way a human annotates one: a comment and a processing instruction
     * standing BEFORE and AFTER a child element, on every level that has children - the prolog,
     * the root element, and a node inside the tree.
     * <p>
     * Both kinds used to be dropped on the floor by the read loop, which handled character data
     * and element boundaries and silently ignored every other event. A rewrite therefore deleted
     * the one part of a merge-rules file that says WHY a decision was made, while reporting the
     * document as carried through verbatim.
     */
    private static final String ANNOTATED_FIXTURE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
        + "<!-- header kept by hand -->\n" //$NON-NLS-1$
        + "<?edt-mcp origin=\"hand-written\"?>\n" //$NON-NLS-1$
        + "<Settings Format_version=\"2.0\">\n" //$NON-NLS-1$
        + "  <!-- before the tree -->\n" //$NON-NLS-1$
        + "  <?edt-mcp before?>\n" //$NON-NLS-1$
        + "  <MergeSettings>\n" //$NON-NLS-1$
        + "    <Node Key=\"$$Root$$\">\n" //$NON-NLS-1$
        + "      <!-- before the payload -->\n" //$NON-NLS-1$
        + "      <Properties>\n" //$NON-NLS-1$
        + "        <SkipUnchanged>true</SkipUnchanged>\n" //$NON-NLS-1$
        + "      </Properties>\n" //$NON-NLS-1$
        + "      <?edt-mcp after-the-payload?>\n" //$NON-NLS-1$
        + "      <Node Key=\"commonModules\" MergeRule=\"GetFromOther\"/>\n" //$NON-NLS-1$
        + "      <!-- after the last node -->\n" //$NON-NLS-1$
        + "    </Node>\n" //$NON-NLS-1$
        + "  </MergeSettings>\n" //$NON-NLS-1$
        + "  <!-- after the tree -->\n" //$NON-NLS-1$
        + "  <?edt-mcp after?>\n" //$NON-NLS-1$
        + "</Settings>\n" //$NON-NLS-1$
        + "<!-- trailing note -->\n" //$NON-NLS-1$
        + "<?edt-mcp done?>\n"; //$NON-NLS-1$

    @Test
    public void testAnAnnotatedFileRoundTripsByteIdentically() throws Exception
    {
        assertEquals("a comment and a processing instruction are content the rewrite must return, " //$NON-NLS-1$
            + "in the place the document put them", //$NON-NLS-1$
            ANNOTATED_FIXTURE, MergeRulesCodec.serialize(MergeRulesCodec.parse(ANNOTATED_FIXTURE)));
    }

    @Test
    public void testAnAnnotatedRoundTripIsIdempotent() throws Exception
    {
        String once = MergeRulesCodec.serialize(MergeRulesCodec.parse(ANNOTATED_FIXTURE));
        assertEquals("keeping the annotations may not make the rewrite drift instead", once, //$NON-NLS-1$
            MergeRulesCodec.serialize(MergeRulesCodec.parse(once)));
    }

    @Test
    public void testACommentBeforeAChildElementSurvivesAnEdit() throws Exception
    {
        assertTrue("the note standing in front of a payload block is the block's explanation", //$NON-NLS-1$
            rewriteAnnotatedWithAnExtraDecision().contains("<!-- before the payload -->")); //$NON-NLS-1$
    }

    @Test
    public void testACommentAfterAChildElementSurvivesAnEdit() throws Exception
    {
        // The two positions are pinned separately because they fail separately: a loop that
        // flushed its buffer only at an element boundary kept one of them and lost the other.
        assertTrue("a note after the last child is as much content as one before the first", //$NON-NLS-1$
            rewriteAnnotatedWithAnExtraDecision().contains("<!-- after the last node -->")); //$NON-NLS-1$
    }

    @Test
    public void testAProcessingInstructionSurvivesAnEdit() throws Exception
    {
        assertTrue("an instruction is addressed to some other reader of this file, and this " //$NON-NLS-1$
            + "codec is not it", //$NON-NLS-1$
            rewriteAnnotatedWithAnExtraDecision().contains("<?edt-mcp after-the-payload?>")); //$NON-NLS-1$
    }

    @Test
    public void testAPrologCommentSurvivesAnEdit() throws Exception
    {
        // Outside the root element, where XML puts a licence header - held on the document,
        // because an element cannot hold a sibling.
        assertTrue("a header above the root is payload too", //$NON-NLS-1$
            rewriteAnnotatedWithAnExtraDecision().contains("<!-- header kept by hand -->")); //$NON-NLS-1$
    }

    @Test
    public void testAnEpilogProcessingInstructionSurvivesAnEdit() throws Exception
    {
        assertTrue("and so is an instruction below it", //$NON-NLS-1$
            rewriteAnnotatedWithAnExtraDecision().contains("<?edt-mcp done?>")); //$NON-NLS-1$
    }

    @Test
    public void testTheEditItselfStillLands() throws Exception
    {
        // The control for the five assertions above: keeping the annotations must not have cost
        // the write they were carried through.
        assertTrue("the new decision must be in the rewritten file", //$NON-NLS-1$
            rewriteAnnotatedWithAnExtraDecision()
                .contains("<Node Key=\"catalogs\" MergeRule=\"DoNotMerge\"/>")); //$NON-NLS-1$
    }

    @Test
    public void testCommentsDoNotBecomeDecisions() throws Exception
    {
        assertEquals("a comment sits among the Node children and is not one of them", 1, //$NON-NLS-1$
            MergeRulesCodec.parse(ANNOTATED_FIXTURE).decisions().size());
    }

    @Test
    public void testCommentsAreNotCountedAsPreservedSections() throws Exception
    {
        // Only the Properties block is a SECTION. Counting the annotations would report blocks a
        // reader opening the file cannot find as blocks - the same reason character data is not
        // counted either.
        assertEquals(1, MergeRulesCodec.parse(ANNOTATED_FIXTURE).preservedSectionCount());
    }

    /**
     * A comment INSIDE character data: it sits between two runs of a payload's text, so the
     * layout may not touch it and the text around it may not be trimmed.
     */
    private static final String COMMENTED_MIXED_FIXTURE =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<Settings Format_version=\"2.0\">\n" //$NON-NLS-1$
            + "  <Payload>Hello <!-- note --> world</Payload>\n" //$NON-NLS-1$
            + "  <MergeSettings>\n" //$NON-NLS-1$
            + "    <Node Key=\"$$Root$$\">\n" //$NON-NLS-1$
            + "      <Node Key=\"commonModules\" MergeRule=\"GetFromOther\"/>\n" //$NON-NLS-1$
            + "    </Node>\n" //$NON-NLS-1$
            + "  </MergeSettings>\n" //$NON-NLS-1$
            + "</Settings>\n"; //$NON-NLS-1$

    @Test
    public void testACommentInsideCharacterDataStaysInlineWithTheTextAroundIt() throws Exception
    {
        assertEquals("a comment between two runs of text is inside the value: putting it on a " //$NON-NLS-1$
            + "line of its own would insert a newline and an indent INTO that value", //$NON-NLS-1$
            COMMENTED_MIXED_FIXTURE,
            MergeRulesCodec.serialize(MergeRulesCodec.parse(COMMENTED_MIXED_FIXTURE)));
    }

    @Test
    public void testAProcessingInstructionWithNoDataKeepsItsBareForm() throws Exception
    {
        // The separator between target and data is consumed by the parser, so an instruction that
        // carries no data must not be re-emitted with a space it never had.
        String fixture = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<Settings Format_version=\"2.0\">\n" //$NON-NLS-1$
            + "  <?edt-mcp?>\n" //$NON-NLS-1$
            + "  <MergeSettings>\n" //$NON-NLS-1$
            + "    <Node Key=\"$$Root$$\"/>\n" //$NON-NLS-1$
            + "  </MergeSettings>\n" //$NON-NLS-1$
            + "</Settings>\n"; //$NON-NLS-1$

        assertEquals(fixture, MergeRulesCodec.serialize(MergeRulesCodec.parse(fixture)));
    }

    @Test
    public void testAnAnnotatedFileSurvivesTheFileRoundTripToo() throws Exception
    {
        // Through the disk, not only through the string API: a merge_rules write reads a file and
        // writes it back, and that is the path on which the annotations were being lost.
        Path file = workDir.resolve("annotated.xml"); //$NON-NLS-1$
        Files.write(file, ANNOTATED_FIXTURE.getBytes(StandardCharsets.UTF_8));

        MergeRulesCodec.write(file, MergeRulesCodec.read(file), MergeRulesCodec.Target.MAY_BE_REPLACED);

        assertEquals(ANNOTATED_FIXTURE, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    /**
     * @return the annotated fixture rewritten after one decision has been added to it
     * @throws Exception when the fixture does not parse
     */
    private static String rewriteAnnotatedWithAnExtraDecision() throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(ANNOTATED_FIXTURE);
        document.setMergeRule(List.of("catalogs"), "DoNotMerge"); //$NON-NLS-1$ //$NON-NLS-2$
        return MergeRulesCodec.serialize(document);
    }

    // ==== The size bound belongs to the SOURCE, not to the container it arrived in ====

    @Test
    public void testAPlainXmlFileLargerThanTheBoundIsRefusedInsteadOfParsed() throws Exception
    {
        // The zip form was bounded and the plain form was not, so the whole defence rested on the
        // caller having picked the container that is checked. A generated or accidentally bloated
        // .xml went straight into an unbounded tree in the workbench's own heap.
        Path file = workDir.resolve("huge.xml"); //$NON-NLS-1$
        try (OutputStream out = Files.newOutputStream(file))
        {
            writeFiller(out, 20 * 1024 * 1024);
        }
        assertTrue("the fixture has to be past the bound to test it", //$NON-NLS-1$
            Files.size(file) > 16L * 1024 * 1024);

        try
        {
            MergeRulesCodec.read(file);
            fail("a file past the bound must be refused, not parsed"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the file it stopped on: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("huge.xml")); //$NON-NLS-1$
            assertTrue("and say how much it read past: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("past 16 MB and was not read")); //$NON-NLS-1$
        }
    }

    @Test
    public void testTheOversizedFileRefusalIsWordedLikeTheZipOne() throws Exception
    {
        // One bound, one reason, one sentence. A caller who met this on a zip must recognise it
        // on a file rather than learn a second wording for the same rule.
        Path file = workDir.resolve("huge-twin.xml"); //$NON-NLS-1$
        try (OutputStream out = Files.newOutputStream(file))
        {
            writeFiller(out, 20 * 1024 * 1024);
        }
        Path zip = workDir.resolve("bomb-twin.zip"); //$NON-NLS-1$
        writeInflatingZip(zip, "Main_Other_Ancestor.xml", 20 * 1024 * 1024); //$NON-NLS-1$

        String shared = "A merge-settings file records one line per decision somebody made"; //$NON-NLS-1$
        assertTrue("the file refusal must carry the shared sentence", //$NON-NLS-1$
            refusalFor(file).contains(shared));
        assertTrue("and so must the zip one", refusalFor(zip).contains(shared)); //$NON-NLS-1$
    }

    @Test
    public void testAnOrdinaryPlainXmlFileIsStillReadWhole() throws Exception
    {
        // The control: the bound guards the heap, it does not truncate a real file. Thousands of
        // decisions are an ordinary merge-rules file and must come back complete.
        Path file = workDir.resolve("large-but-real.xml"); //$NON-NLS-1$
        int decisions = 8000;
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<Settings Format_version=\"2.0\">\n  <MergeSettings>\n    <Node Key=\"$$Root$$\">\n"); //$NON-NLS-1$
        for (int i = 0; i < decisions; i++)
        {
            xml.append("      <Node Key=\"catalogs").append(i) //$NON-NLS-1$
                .append("\" MergeRule=\"GetFromOther\"/>\n"); //$NON-NLS-1$
        }
        xml.append("    </Node>\n  </MergeSettings>\n</Settings>\n"); //$NON-NLS-1$
        Files.write(file, xml.toString().getBytes(StandardCharsets.UTF_8));

        assertEquals(decisions, MergeRulesCodec.read(file).decisions().size());
    }

    /**
     * @param file a source the codec must refuse for its size
     * @return the refusal message
     * @throws Exception when reading fails for any other reason
     */
    private static String refusalFor(Path file) throws Exception
    {
        try
        {
            MergeRulesCodec.read(file);
            fail("expected a refusal for " + file); //$NON-NLS-1$
            return null; // unreachable
        }
        catch (MergeRulesFormatException e)
        {
            return e.getMessage();
        }
    }

    // ==== Which extensions the PLATFORM's reader accepts, answered in one place ====

    @Test
    public void testTheReaderExtensionRuleAcceptsBothContainers()
    {
        assertTrue(MergeRulesCodec.hasReadableExtension(Paths.get("C:", "rules.xml"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(MergeRulesCodec.hasReadableExtension(Paths.get("C:", "rules.zip"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheReaderExtensionRuleIsCaseInsensitive()
    {
        assertTrue(MergeRulesCodec.hasReadableExtension(Paths.get("C:", "RULES.XML"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheReaderExtensionRuleRefusesAnythingElse()
    {
        assertFalse(MergeRulesCodec.hasReadableExtension(Paths.get("C:", "rules.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MergeRulesCodec.hasReadableExtension(Paths.get("C:", "rules"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MergeRulesCodec.hasReadableExtension(null));
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

        MergeRulesCodec.write(first, MergeRulesCodec.parse(FIXTURE), MergeRulesCodec.Target.MAY_BE_REPLACED);

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

    // ==== A namespace is REFUSED at the parse, because it could not be carried ====

    // The model holds elements under their LOCAL name and keys attributes by local name, so none
    // of the three shapes a namespace takes survives a rewrite: a declaration is not an attribute
    // and is never even read, a prefixed element comes back stripped, and two attributes differing
    // only by their prefix land on ONE key - the second deleting the first. That last one is why
    // this is a refusal and not a stated difference: it does not rewrite the payload, it deletes a
    // value out of it, while the report goes on counting the block as preserved. Each shape gets
    // its own test, and each fixture is written so that only its own check can fire: a prefixed
    // name normally carries its declaration on the very same element, so the checks are ordered
    // prefix-first and these documents pick the branch they mean to pin.

    @Test
    public void testANamespaceDeclarationOnTheRootIsRefusedNamingIt()
    {
        // Nothing is prefixed here: the declaration alone must stop the parse, because a
        // declaration is invisible to the reader that would have to write it back.
        try
        {
            MergeRulesCodec.parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
                + "<Settings Format_version=\"2.0\" xmlns:ext=\"urn:x\">\n" //$NON-NLS-1$
                + "  <MergeSettings/>\n</Settings>\n"); //$NON-NLS-1$
            fail("a declaration that cannot be written back must not be read past"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the declaration it found: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("xmlns:ext=\"urn:x\"")); //$NON-NLS-1$
        }
    }

    @Test
    public void testADefaultNamespaceDeclarationIsRefusedToo()
    {
        // A default namespace changes what every element in the file MEANS while leaving every
        // local name - the root's included - exactly as this codec reads them, so it would sail
        // past the root check and be re-emitted as a document about something else.
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\" xmlns=\"urn:x\">" //$NON-NLS-1$
                + "<MergeSettings/></Settings>"); //$NON-NLS-1$
            fail("a default namespace is a namespace"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the declaration it found: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("xmlns=\"urn:x\"")); //$NON-NLS-1$
        }
    }

    @Test
    public void testAPrefixedElementIsRefusedNamingIt()
    {
        // Read, this element becomes <Payload> and the prefix is gone from the rewrite.
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\">" //$NON-NLS-1$
                + "<ext:Payload xmlns:ext=\"urn:x\">keep me</ext:Payload>" //$NON-NLS-1$
                + "<MergeSettings/></Settings>"); //$NON-NLS-1$
            fail("an element whose prefix a rewrite would drop must not be read"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the prefixed element: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("<ext:Payload>")); //$NON-NLS-1$
        }
    }

    @Test
    public void testTwoAttributesDifferingOnlyByPrefixAreRefusedRatherThanCollapsed()
    {
        // THE EXPENSIVE ONE. Both attributes are reported with the local name 'a', so the map that
        // holds them keeps whichever was written last and the other value is destroyed outright -
        // while the report keeps calling the block preserved.
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\" xmlns:ext=\"urn:x\" ext:a=\"1\" a=\"2\"/>" //$NON-NLS-1$
                + "</MergeSettings></Settings>"); //$NON-NLS-1$
            fail("one attribute silently overwriting the other must be refused, not performed"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the prefixed attribute: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("'ext:a'")); //$NON-NLS-1$
        }
    }

    @Test
    public void testTheImplicitXmlPrefixIsRefusedWithNothingDeclaredAnywhere()
    {
        // The 'xml' prefix is bound by the XML spec itself, so this document declares NO namespace
        // and the collision above happens with the declaration check seeing nothing at all. It is
        // the case only the attribute check can catch - and it is exactly the xml:space the layout
        // rule says it cannot honour.
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\" xml:space=\"preserve\" space=\"x\"/>" //$NON-NLS-1$
                + "</MergeSettings></Settings>"); //$NON-NLS-1$
            fail("a prefix needs no declaration to destroy the attribute beside it"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the prefixed attribute: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("'xml:space'")); //$NON-NLS-1$
        }
    }

    @Test
    public void testTheNamespaceRefusalSaysWhereAGoodFileComesFrom()
    {
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\" xmlns:ext=\"urn:x\"/>"); //$NON-NLS-1$
            fail("a namespaced document must be refused"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("a refusal the caller cannot act on is half a refusal: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("Save merge settings")); //$NON-NLS-1$
        }
    }

    @Test
    public void testTheNamespaceRefusalSaysWhatReadingItWouldHaveCost()
    {
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\" xmlns:ext=\"urn:x\"/>"); //$NON-NLS-1$
            fail("a namespaced document must be refused"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("refusing a file is only justified by naming what reading it would do: " //$NON-NLS-1$
                + e.getMessage(), e.getMessage().contains("DESTROYS")); //$NON-NLS-1$
        }
    }

    @Test
    public void testAFileWithoutNamespacesIsStillReadAndRewrittenUnchanged() throws Exception
    {
        // The control. The check must key on what the READER reports as a prefix, not on text that
        // merely looks like one: the fixture is full of colons (every top-object key is
        // 'Main:Other:Ancestor'), and none of them is a namespace.
        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);
        assertEquals(4, document.decisions().size());
        assertEquals("a file the format allows must round-trip byte for byte as it always did", //$NON-NLS-1$
            FIXTURE, MergeRulesCodec.serialize(document));
    }
}
