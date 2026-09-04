/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.compare.MergeRulesCodec.MergeRulesFormatException;
import com.ditrix.edt.mcp.server.utils.compare.MergeRulesDocument.Decision;
import com.ditrix.edt.mcp.server.utils.compare.MergeRulesDocument.Element;
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

    /**
     * One rule inside the reachable tree and two on {@code Node}s beside the root - the shape the
     * duplicate-key refusal deliberately lets through, because no request can address it.
     */
    private static final String RULE_BESIDE_THE_ROOT =
        "<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
            + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
            + "<Node Key=\"commonModules\" MergeRule=\"MergePrioritizingMain\"/></Node>" //$NON-NLS-1$
            + "<Node Key=\"orphan\" MergeRule=\"GetFromOther\"/>" //$NON-NLS-1$
            + "<Node Key=\"orphan\" MergeRule=\"DoNotMerge\"/>" //$NON-NLS-1$
            + "</MergeSettings></Settings>"; //$NON-NLS-1$

    /**
     * One rule in a SECOND {@code <MergeSettings>} element - accepted by the codec, carried
     * through every rewrite, and read by nothing: {@code findContainer} picks the first container
     * and no lookup, decision or write here ever looks past it.
     */
    private static final String RULE_IN_A_SECOND_CONTAINER =
        "<Settings Format_version=\"2.0\">" //$NON-NLS-1$
            + "<MergeSettings><Node Key=\"$$Root$$\"/></MergeSettings>" //$NON-NLS-1$
            + "<MergeSettings><Node Key=\"$$Root$$\">" //$NON-NLS-1$
            + "<Node Key=\"commonModules\" MergeRule=\"GetFromOther\"/></Node></MergeSettings>" //$NON-NLS-1$
            + "</Settings>"; //$NON-NLS-1$

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
        assertEquals(TopObjectKey.SideState.NAMED, key.mainState());
    }

    @Test
    public void testNoneIsReportedAsAmbiguousRatherThanAsAnAbsence() throws Exception
    {
        TopObjectKey key = decisionFor("Added:NONE:Added").topObjectKey().orElseThrow(); //$NON-NLS-1$
        assertEquals("Added", key.main()); //$NON-NLS-1$
        assertEquals(TopObjectKey.SideState.NAMED, key.mainState());
        // The load-bearing pair. The component is handed back as SPELLED and its state says what
        // that spelling establishes - which is not "absent": NONE is also a legal 1C name. This
        // used to answer null, i.e. "the object is not on that side", about a side that may hold
        // an object called NONE.
        assertEquals(MergeRulesDocument.SIDE_ABSENT, key.other());
        assertEquals(TopObjectKey.SideState.AMBIGUOUS, key.otherState());
        assertEquals("Added", key.ancestor()); //$NON-NLS-1$
        assertEquals(TopObjectKey.SideState.NAMED, key.ancestorState());
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

    // ============ A key has to name at least one side that HAS the object ============

    @Test
    public void testAKeySpellingAbsentOnEverySideIsRecognisedAsSuch()
    {
        // The predicate reports the SPELLING and nothing more - what that spelling means is a
        // question only a comparison can answer, see the ambiguity tests below.
        assertTrue(MergeRulesDocument.spellsSideAbsentOnEveryTopObjectKeySide("NONE:NONE:NONE")); //$NON-NLS-1$
    }

    @Test
    public void testOneNamedSideIsEnoughToNameSomething()
    {
        assertFalse("one named side is exactly how a deletion is addressed", //$NON-NLS-1$
            MergeRulesDocument.spellsSideAbsentOnEveryTopObjectKeySide("Added:NONE:NONE")); //$NON-NLS-1$
        assertFalse(MergeRulesDocument.spellsSideAbsentOnEveryTopObjectKeySide("NONE:Renamed:NONE")); //$NON-NLS-1$
        assertFalse(MergeRulesDocument.spellsSideAbsentOnEveryTopObjectKeySide("NONE:NONE:Gone")); //$NON-NLS-1$
    }

    @Test
    public void testTheAbsenceMarkerIsMatchedExactly()
    {
        // The literal is matched exactly, as TopObjectKey.parse matches it. A 1C object may be
        // called 'none', and any other spelling is a name like any other.
        assertFalse(MergeRulesDocument.spellsSideAbsentOnEveryTopObjectKeySide("none:none:none")); //$NON-NLS-1$
        assertFalse(MergeRulesDocument.spellsSideAbsentOnEveryTopObjectKeySide("None:NONE:NONE")); //$NON-NLS-1$
    }

    @Test
    public void testAKeyThatIsNotTopObjectShapedNeverSpellsAbsentEverywhere()
    {
        // Same rule as the empty-sides question: a key with no separators has no sides at all,
        // so it cannot spell anything on them.
        assertFalse(MergeRulesDocument.spellsSideAbsentOnEveryTopObjectKeySide("commonModules")); //$NON-NLS-1$
        assertFalse(MergeRulesDocument.spellsSideAbsentOnEveryTopObjectKeySide("NONE:NONE")); //$NON-NLS-1$
        assertFalse(MergeRulesDocument.spellsSideAbsentOnEveryTopObjectKeySide(null));
    }

    @Test
    public void testReadingStillDecodesAKeySpellingAbsentOnEverySide() throws Exception
    {
        // The deliberate NON-change, pinned so a later tidy-up does not fold the new question
        // into isTopObjectKey. READING such a key has to report it faithfully - it is already in
        // somebody's file - and what it is reported AS is the spelling, not a decision about it.
        MergeRulesDocument document = MergeRulesCodec.parse("<?xml version=\"1.0\"?>" //$NON-NLS-1$
            + "<Settings Format_version=\"2.0\"><MergeSettings><Node Key=\"$$Root$$\">" //$NON-NLS-1$
            + "<Node Key=\"commonModules\"><Node Key=\"NONE:NONE:NONE\" MergeRule=\"DoNotMerge\"/>" //$NON-NLS-1$
            + "</Node></Node></MergeSettings></Settings>"); //$NON-NLS-1$

        Decision decision = document.decisions().get(0);

        assertTrue(MergeRulesDocument.isTopObjectKey("NONE:NONE:NONE")); //$NON-NLS-1$
        TopObjectKey key = decision.topObjectKey().orElseThrow();
        assertEquals(MergeRulesDocument.SIDE_ABSENT, key.main());
        assertEquals(MergeRulesDocument.SIDE_ABSENT, key.other());
        assertEquals(MergeRulesDocument.SIDE_ABSENT, key.ancestor());
        assertEquals(TopObjectKey.SideState.AMBIGUOUS, key.mainState());
        assertEquals(TopObjectKey.SideState.AMBIGUOUS, key.otherState());
        assertEquals(TopObjectKey.SideState.AMBIGUOUS, key.ancestorState());
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

    /**
     * The defect: a hand-edited file could carry two sibling {@code <Node>} elements under one
     * {@code Key}, and the document was read as if it addressed one node. Every lookup stops at
     * the first match, so a rule written to that address updated the first and left the second in
     * the file holding a rule of its own - reported as recorded, applied by nothing. The tool
     * already refuses two decisions addressing one node in ONE request; this is the same question
     * asked of the file it starts from.
     */
    @Test
    public void testParseRefusesTwoSiblingNodesUnderOneKey()
    {
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Node Key=\"commonModules\" MergeRule=\"GetFromOther\"/>" //$NON-NLS-1$
                + "<Node Key=\"commonModules\" MergeRule=\"DoNotMerge\"/>" //$NON-NLS-1$
                + "</Node></MergeSettings></Settings>"); //$NON-NLS-1$
            fail("a document that says two things about one node must not be read"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the key: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("commonModules")); //$NON-NLS-1$
        }
    }

    /** Its own literal: naming the key alone leaves the reader hunting through a nested file. */
    @Test
    public void testTheDuplicateKeyRefusalNamesTheLevel()
    {
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Node Key=\"commonModules\">" //$NON-NLS-1$
                + "<Node Key=\"A:B:C\" MergeRule=\"GetFromOther\"/>" //$NON-NLS-1$
                + "<Node Key=\"A:B:C\" MergeRule=\"DoNotMerge\"/>" //$NON-NLS-1$
                + "</Node></Node></MergeSettings></Settings>"); //$NON-NLS-1$
            fail("a duplicate top-object key must be refused too"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("a top object sits at level 2: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("level 2")); //$NON-NLS-1$
        }
    }

    /**
     * Its own literal, because JUnit stops a method at its first failed assertion: a level number
     * with no ancestors still leaves the reader searching a nested file for which branch it is.
     */
    @Test
    public void testTheDuplicateKeyRefusalNamesTheAncestorsThatLeadToIt()
    {
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Node Key=\"commonModules\">" //$NON-NLS-1$
                + "<Node Key=\"A:B:C\" MergeRule=\"GetFromOther\"/>" //$NON-NLS-1$
                + "<Node Key=\"A:B:C\" MergeRule=\"DoNotMerge\"/>" //$NON-NLS-1$
                + "</Node></Node></MergeSettings></Settings>"); //$NON-NLS-1$
            fail("a duplicate top-object key must be refused too"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the path to the pair must be named: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("$$Root$$ / commonModules")); //$NON-NLS-1$
        }
    }

    /**
     * The duplicate-key scan lists each level's children ONCE, whatever the level holds.
     *
     * <h2>Why the bound and not a duration</h2>
     * The scan checked the siblings for duplicates and then re-resolved every key through
     * {@code findNode}, which rebuilds and rescans the whole child list per call: a flat level of
     * {@code n} uniquely keyed siblings cost {@code n} rebuilds of an {@code n}-element list and
     * about {@code n^2/2} key comparisons. A flat level is not the pathological case - it is what
     * a merge-settings file IS at the object level, one sibling per top object of a collection -
     * and the sizes that reach here are bounded only by 16 MB and the node limit.
     * <p>
     * Nothing about the RESULT changes, which is why this is pinned as a counted bound. The
     * re-resolving walk and the one-pass walk visit the same elements and refuse the same files,
     * so no assertion about the document could tell them apart; what differs is how many times
     * the level's children are listed, and {@code Element} counts exactly that. A timing would
     * measure the machine.
     *
     * @throws Exception when the document cannot be parsed
     */
    @Test
    public void testEachLevelsChildrenAreListedOnce() throws Exception
    {
        int siblings = 200;
        StringBuilder xml = new StringBuilder("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
            + "<Node Key=\"$$Root$$\">"); //$NON-NLS-1$
        for (int i = 0; i < siblings; i++)
        {
            xml.append("<Node Key=\"c").append(i).append("\" MergeRule=\"DoNotMerge\"/>"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        xml.append("</Node></MergeSettings></Settings>"); //$NON-NLS-1$

        MergeRulesDocument document = MergeRulesCodec.parse(xml.toString());

        assertEquals("the scan must list the root's children exactly once, whatever it finds " //$NON-NLS-1$
            + "there - re-resolving each key costs one more listing per key", //$NON-NLS-1$
            1, document.root().nodeChildListings());
        assertEquals("and the document itself is unchanged by how it was walked", //$NON-NLS-1$
            siblings, document.decisions().size());
    }

    /**
     * The positive control for the counter: it really does move. Without this, a counter that was
     * never incremented at all would satisfy the bound above.
     *
     * @throws Exception when the document cannot be parsed
     */
    @Test
    public void testTheListingCounterCountsListings() throws Exception
    {
        MergeRulesDocument document =
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\"><Node Key=\"a\" MergeRule=\"DoNotMerge\"/></Node>" //$NON-NLS-1$
                + "</MergeSettings></Settings>"); //$NON-NLS-1$
        MergeRulesDocument.Element root = document.root();
        int before = root.nodeChildListings();

        document.decisions();

        assertTrue("reading the decisions lists the root's children again", //$NON-NLS-1$
            root.nodeChildListings() > before);
    }

    /**
     * The DECISION walk lists each level's children once too - the same bound as the scan above,
     * over the same shape, and it was the walk that still paid per key.
     *
     * <h2>Why this is a second pin and not covered by the scan's</h2>
     * {@code testEachLevelsChildrenAreListedOnce} asserts the count straight after parsing, so it
     * covers {@code MergeRulesCodec}'s duplicate-key scan and stops there. Reading the decisions
     * is a SECOND walk of the same tree, and it re-resolved every key through {@code findNode} -
     * one rebuild and rescan of the whole level per key, on top of the pass that collected them.
     * A flat level of {@code n} siblings therefore cost {@code n + 1} listings, and unlike the
     * scan this walk runs on every read AND every write of the document: {@code MergeRulesTool}
     * calls {@code decisions()} to list, to count before a write, to report the count after one
     * and twice more while rendering.
     * <p>
     * The DELTA is asserted rather than the absolute count, so this measures the decision walk
     * alone and stays honest if the parse ahead of it ever lists a level for a reason of its own.
     * <p>
     * Nothing about the RESULT changes - which is why the second assertion is here: a walk that
     * listed the level once by simply not descending would satisfy the bound and lose every
     * decision below it.
     *
     * @throws Exception when the document cannot be parsed
     */
    @Test
    public void testReadingTheDecisionsListsEachLevelsChildrenOnce() throws Exception
    {
        int siblings = 200;
        StringBuilder xml = new StringBuilder("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
            + "<Node Key=\"$$Root$$\">"); //$NON-NLS-1$
        for (int i = 0; i < siblings; i++)
        {
            xml.append("<Node Key=\"c").append(i).append("\" MergeRule=\"DoNotMerge\"/>"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        xml.append("</Node></MergeSettings></Settings>"); //$NON-NLS-1$
        MergeRulesDocument document = MergeRulesCodec.parse(xml.toString());
        int before = document.root().nodeChildListings();

        List<Decision> decisions = document.decisions();

        assertEquals("the decision walk may list a level once - resolving each key through " //$NON-NLS-1$
            + "findNode costs one more listing per key", //$NON-NLS-1$
            1, document.root().nodeChildListings() - before);
        assertEquals("and the walk still reaches every decision below it", //$NON-NLS-1$
            siblings, decisions.size());
    }

    /**
     * The control that keeps the refusal from being about the KEY rather than about the pair: the
     * same key at two different levels is two different addresses, and the platform's own path
     * generators produce exactly that (a feature collection and a top object can share a spelling).
     */
    @Test
    public void testTheSameKeyAtTwoDifferentLevelsIsNotADuplicate() throws Exception
    {
        MergeRulesDocument document =
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Node Key=\"same\"><Node Key=\"same\" MergeRule=\"GetFromOther\"/></Node>" //$NON-NLS-1$
                + "</Node></MergeSettings></Settings>"); //$NON-NLS-1$

        assertEquals("GetFromOther", //$NON-NLS-1$
            document.mergeRuleAt(List.of("same", "same")).orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The defect: the scan descended through a {@code <Node>} that carries no {@code Key}, but
     * {@code findNode} matches a child on tag AND key, so no lookup can ever come to rest on such
     * a node and nothing below it is reachable by any request. A file was refused over a pair the
     * document can never reach - and the refusal named it at a level and under a path that do not
     * exist, because the keyless ancestor contributed an empty segment to both.
     */
    @Test
    public void testTwoNodesUnderAKeylessNodeAreNotADuplicate() throws Exception
    {
        MergeRulesDocument document =
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Node><Node Key=\"x\" MergeRule=\"GetFromOther\"/>" //$NON-NLS-1$
                + "<Node Key=\"x\" MergeRule=\"DoNotMerge\"/></Node>" //$NON-NLS-1$
                + "</Node></MergeSettings></Settings>"); //$NON-NLS-1$

        assertNotNull("a pair no lookup can reach is not an ambiguous address", document); //$NON-NLS-1$
        assertTrue("and the shape must survive the round trip it is not judged by", //$NON-NLS-1$
            MergeRulesCodec.serialize(document).contains("Key=\"x\"")); //$NON-NLS-1$
    }

    /**
     * The control that keeps the test above from being passed by a scan that stopped descending
     * at all: the same file with a {@code Key} on the middle node is an addressable path, and the
     * pair at the end of it is still refused.
     */
    @Test
    public void testTheSamePairUnderAKEYEDNodeIsStillRefused()
    {
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Node Key=\"reachable\"><Node Key=\"x\"/><Node Key=\"x\"/></Node>" //$NON-NLS-1$
                + "</Node></MergeSettings></Settings>"); //$NON-NLS-1$
            fail("an addressable pair must still be refused"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the key: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("'x'")); //$NON-NLS-1$
            assertTrue("and the path that leads to it: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("$$Root$$ / reachable")); //$NON-NLS-1$
        }
    }

    /**
     * The THIRD instance of the same defect, and the reason the scan now takes its container, its
     * entry and its pick from {@link MergeRulesDocument} instead of restating them: a
     * {@code <Node>} sitting BESIDE the root, under a key of its own, was treated as an address.
     * It is not one. {@code MergeRulesDocument.root()} enters the container at {@code $$Root$$}
     * and nowhere else, so no request can reach such a node - and a whole file was refused over a
     * pair nothing can address, at "level 0", which the refusal describes as the root's own level.
     */
    @Test
    public void testTwoNodesBesideTheRootUnderOneKeyAreNotADuplicate() throws Exception
    {
        MergeRulesDocument document =
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Node Key=\"commonModules\" MergeRule=\"GetFromOther\"/></Node>" //$NON-NLS-1$
                + "<Node Key=\"orphan\" MergeRule=\"GetFromOther\"/>" //$NON-NLS-1$
                + "<Node Key=\"orphan\" MergeRule=\"DoNotMerge\"/>" //$NON-NLS-1$
                + "</MergeSettings></Settings>"); //$NON-NLS-1$

        assertEquals("the tree the document reads is unambiguous", "GetFromOther", //$NON-NLS-1$ //$NON-NLS-2$
            document.mergeRuleAt(List.of("commonModules")).orElse(null)); //$NON-NLS-1$
        assertTrue("and the shape it is not judged by must survive the round trip", //$NON-NLS-1$
            MergeRulesCodec.serialize(document).contains("Key=\"orphan\"")); //$NON-NLS-1$
    }

    /**
     * The same instance one level down: descending THROUGH a node beside the root judged a subtree
     * that is equally unreachable, and named the pair under a path - {@code orphan / ...} - that
     * no lookup ever walks.
     */
    @Test
    public void testADuplicateUnderANodeBesideTheRootIsNotRefused() throws Exception
    {
        MergeRulesDocument document =
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\"/>" //$NON-NLS-1$
                + "<Node Key=\"orphan\"><Node Key=\"x\"/><Node Key=\"x\"/></Node>" //$NON-NLS-1$
                + "</MergeSettings></Settings>"); //$NON-NLS-1$

        assertNotNull("a pair below an unreachable node is not an ambiguous address", document); //$NON-NLS-1$
        assertTrue("and the shape must survive the round trip it is not judged by", //$NON-NLS-1$
            MergeRulesCodec.serialize(document).contains("Key=\"x\"")); //$NON-NLS-1$
    }

    // ============ decisions() reads the same tree the lookups do, and no other ============

    /**
     * The shape above with a RULE on it, which is where the second reader was still going its own
     * way. {@code decisions()} used to start at every {@code Node} in the container and recurse
     * through the child list, so a rule beside the root came back as a decision at depth 0 - the
     * ROOT's own level - and the pair came back TWICE under one path that
     * {@code mergeRuleAt} / {@code setMergeRule} can never walk. The duplicate-key refusal
     * deliberately does not judge this shape, so nothing else caught it: the tool printed both as
     * root-level decisions and validated a path the comparison has no node for.
     */
    @Test
    public void testARuleBesideTheRootIsNotADecisionAtAnAddress() throws Exception
    {
        List<Decision> decisions = MergeRulesCodec.parse(RULE_BESIDE_THE_ROOT).decisions();

        assertEquals("a rule nothing can address is not a decision at one: " //$NON-NLS-1$
            + describe(decisions), List.of(),
            decisions.stream().filter(decision -> "orphan".equals(decision.key())).toList()); //$NON-NLS-1$
    }

    /** Its own literal: not reporting it is not the same as dropping it. */
    @Test
    public void testARuleBesideTheRootStillSurvivesTheRewrite() throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(RULE_BESIDE_THE_ROOT);

        assertTrue("the rewrite carries what it does not interpret, verbatim", //$NON-NLS-1$
            MergeRulesCodec.serialize(document)
                .contains("<Node Key=\"orphan\" MergeRule=\"GetFromOther\"/>")); //$NON-NLS-1$
    }

    /**
     * The same rule under a KEYLESS node. {@code findNode} matches a child on tag AND key, so a
     * node without one is not a place any path can come to rest and neither is anything below it -
     * yet the old walk gave it the empty string as a key and reported the rule under an address
     * whose middle segment matches nothing in any comparison.
     */
    @Test
    public void testARuleUnderAKeylessNodeIsNotADecisionAtAnAddress() throws Exception
    {
        MergeRulesDocument document =
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Node><Node Key=\"x\" MergeRule=\"GetFromOther\"/></Node>" //$NON-NLS-1$
                + "</Node></MergeSettings></Settings>"); //$NON-NLS-1$

        assertTrue("no path can rest on a keyless node, so nothing below it has an address: " //$NON-NLS-1$
            + describe(document.decisions()), document.decisions().isEmpty());
    }

    /**
     * The control that keeps the three above from being passed by a {@code decisions()} that
     * returns nothing at all: the SAME file's reachable branch still yields its decision, at its
     * real address and its real depth.
     */
    @Test
    public void testARuleInsideTheReachableTreeIsStillADecision() throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(RULE_BESIDE_THE_ROOT);

        assertEquals(List.of(List.of("$$Root$$", "commonModules")), //$NON-NLS-1$ //$NON-NLS-2$
            document.decisions().stream().map(Decision::path).toList());
    }

    // ============ what decisions() cannot report, the document still COUNTS ============
    //
    // Not returning an unreachable rule is right - it has no address to be returned under -
    // but it left the rule named nowhere at all: the preserved-section count measures BLOCKS
    // rather than rules, so it cannot stand in for this. A reader was then told a
    // file with a rule in it records none, which is a false claim of absence.

    /**
     * The two rules beside the root in {@code RULE_BESIDE_THE_ROOT}: unreachable, and counted
     * as such.
     */
    @Test
    public void testARuleBesideTheRootIsCountedAsUnreachable() throws Exception
    {
        assertEquals("both rules beside the root are addressed by nothing", 2, //$NON-NLS-1$
            MergeRulesCodec.parse(RULE_BESIDE_THE_ROOT).unreachableRuleCount());
    }

    /**
     * The same one level down: {@code findNode} matches on tag AND key, so a keyless node is
     * not a place a path can rest and neither is anything below it.
     */
    @Test
    public void testARuleUnderAKeylessNodeIsCountedAsUnreachable() throws Exception
    {
        MergeRulesDocument document =
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Node><Node Key=\"x\" MergeRule=\"GetFromOther\"/></Node>" //$NON-NLS-1$
                + "</Node></MergeSettings></Settings>"); //$NON-NLS-1$

        assertEquals("a rule below a keyless node is addressed by nothing", 1, //$NON-NLS-1$
            document.unreachableRuleCount());
    }

    /**
     * A rule in a SECOND {@code <MergeSettings>} element. {@code findContainer} picks the first
     * and no reader here looks past it, so such a rule is addressed by nothing - and it used to be
     * counted by nothing either: {@code decisions()} never enters that container,
     * {@code unreachableRuleCount} never left the one it reads, and
     * {@code preservedSectionCount} descended into it as if it were structure. A file whose only
     * rule sits there therefore reported no merge rule at all, while every rewrite carried the
     * rule forward.
     */
    @Test
    public void testARuleInASecondMergeSettingsElementIsCountedAsUnreachable() throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(RULE_IN_A_SECOND_CONTAINER);

        assertEquals("only the first container is read, so this rule is addressed by nothing", 1, //$NON-NLS-1$
            document.unreachableRuleCount());
    }

    /** Its half of the same claim: the rule is not returned as a decision at a made-up address. */
    @Test
    public void testARuleInASecondMergeSettingsElementIsNotADecisionAtAnAddress() throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(RULE_IN_A_SECOND_CONTAINER);

        assertTrue("a rule no lookup can reach has no address to be reported under: " //$NON-NLS-1$
            + describe(document.decisions()), document.decisions().isEmpty());
    }

    /** And the literal that makes the count worth printing: the rewrite really does keep it. */
    @Test
    public void testARuleInASecondMergeSettingsElementSurvivesTheRewrite() throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(RULE_IN_A_SECOND_CONTAINER);

        assertTrue("the rewrite carries what it does not interpret, verbatim", //$NON-NLS-1$
            MergeRulesCodec.serialize(document)
                .contains("<Node Key=\"commonModules\" MergeRule=\"GetFromOther\"/>")); //$NON-NLS-1$
    }

    /**
     * The other half of the partition: the second container is itself a block a rewrite carries
     * verbatim, exactly like a {@code Correspondences} section, so it is counted as one. Keyed on
     * the TAG rather than on the container's identity, this count descended into it and reported
     * the payload sections INSIDE it while never naming the element itself.
     */
    @Test
    public void testASecondMergeSettingsElementIsCountedAsAPreservedSection() throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(RULE_IN_A_SECOND_CONTAINER);

        assertEquals("the element this document does not read is payload it carries through", 1, //$NON-NLS-1$
            document.preservedSectionCount());
    }

    /**
     * Its control, and the one that keeps the pin above from being passed by "every
     * {@code MergeSettings} is one block": the container the document DOES read is still descended
     * into, so the two payload sections in it stay two. Three is the only answer that separates
     * the fix from both the old behaviour (which counted 2) and that mutation (which counts 2 as
     * well).
     */
    @Test
    public void testTheContainerTheDocumentReadsIsStillDescendedInto() throws Exception
    {
        MergeRulesDocument document =
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\">" //$NON-NLS-1$
                + "<MergeSettings><Properties/><Notes/>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\"/></MergeSettings>" //$NON-NLS-1$
                + "<MergeSettings><Node Key=\"$$Root$$\" MergeRule=\"GetFromOther\"/></MergeSettings>" //$NON-NLS-1$
                + "</Settings>"); //$NON-NLS-1$

        assertEquals("two payload sections inside the container that is read, plus the container " //$NON-NLS-1$
            + "that is not", 3, document.preservedSectionCount()); //$NON-NLS-1$
    }

    /**
     * The control that keeps the two above from being passed by a counter that counts every
     * rule in the file: the fixture's four rules all sit at addresses, so none of them is one.
     */
    @Test
    public void testARuleAtAnAddressIsNotCountedAsUnreachable() throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);

        assertEquals("the fixture holds four rules, all of them addressable", 4, //$NON-NLS-1$
            document.decisions().size());
        assertEquals("so the file carries no rule at an unreachable node", 0, //$NON-NLS-1$
            document.unreachableRuleCount());
    }

    // ============ the identity: every MergeRule is reported exactly once ============
    //
    // Four separate reviews found four separate shapes whose rule was reported by NOTHING - a Node
    // beside the root, a keyless node, a node shadowed by a same-keyed sibling, a second
    // <MergeSettings>. Every one of them was the same mistake: the counter ENUMERATED the places a
    // rule was known to hide, so a place not on the list fell out of both halves of the report and
    // the file read as carrying nothing. The pins below do not add a fifth place to the list. They
    // assert the identity the counters now hold by construction:
    //
    //     decisions().size() + unreachableRuleCount() == ruleCount()
    //
    // and they check ruleCount() itself against an oracle that reads the FILE TEXT rather than the
    // model, so a walk that skips an element cannot satisfy both sides of the equation by skipping
    // it twice.

    /**
     * The oracle the identity is checked against: how many times the file SPELLS the attribute.
     * <p>
     * Deliberately naive and deliberately not the model - a count derived from the same walk the
     * counters use would agree with them however wrong they both were. It counts a literal, so a
     * fixture it judges must not put that literal in text or in an attribute VALUE; none below
     * does.
     *
     * @param xml the document source
     * @return how many times the attribute name followed by '=' appears in it
     */
    private static int rulesSpelledIn(String xml)
    {
        int count = 0;
        int at = 0;
        String spelling = MergeRulesDocument.ATTR_MERGE_RULE + "="; //$NON-NLS-1$
        while ((at = xml.indexOf(spelling, at)) >= 0)
        {
            count++;
            at += spelling.length();
        }
        return count;
    }

    /**
     * The identity itself, asserted on one document.
     *
     * @param what what the document is, for the failure message
     * @param xml the document source
     * @throws Exception when the document does not parse
     */
    private static void assertEveryRuleIsReportedOnce(String what, String xml) throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(xml);
        int spelled = rulesSpelledIn(xml);
        assertEquals(what + ": the document must count every merge rule the file spells\n" + xml, //$NON-NLS-1$
            spelled, document.ruleCount());
        assertEquals(what + ": every rule must be reported exactly once - as a decision at an " //$NON-NLS-1$
            + "address, or as unreachable, never as neither\n" + xml, spelled, //$NON-NLS-1$
            document.decisions().size() + document.unreachableRuleCount());
    }

    /**
     * A document carrying a rule in EVERY hiding place that can be built at once: on the
     * {@code Settings} root, inside a {@code Correspondences} block beside the tree, on the
     * container element the document reads, on a non-{@code Node} child inside the read tree, on a
     * {@code Node} buried inside such a child, below a keyless node, beside the root marker, on a
     * SECOND container element and on a THIRD, and one honest decision at a real address so the
     * addressed half is not vacuously zero.
     * <p>
     * Ten rules; exactly one of them is addressable.
     */
    private static final String A_RULE_IN_EVERY_HIDING_PLACE =
        "<Settings Format_version=\"2.0\" MergeRule=\"GetFromOther\">" //$NON-NLS-1$
            + "<Correspondences><Correspondence MergeRule=\"DoNotMerge\"/></Correspondences>" //$NON-NLS-1$
            + "<MergeSettings MergeRule=\"GetFromOther\">" //$NON-NLS-1$
            + "<Properties MergeRule=\"DoNotMerge\">" //$NON-NLS-1$
            + "<Node Key=\"buried\" MergeRule=\"GetFromOther\"/></Properties>" //$NON-NLS-1$
            + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
            + "<Node Key=\"commonModules\" MergeRule=\"MergePrioritizingMain\"/>" //$NON-NLS-1$
            + "<Node><Node Key=\"under-a-keyless-node\" MergeRule=\"DoNotMerge\"/></Node>" //$NON-NLS-1$
            + "</Node>" //$NON-NLS-1$
            + "<Node Key=\"beside-the-root\" MergeRule=\"GetFromOther\"/>" //$NON-NLS-1$
            + "</MergeSettings>" //$NON-NLS-1$
            + "<MergeSettings MergeRule=\"DoNotMerge\"><Node Key=\"$$Root$$\"/></MergeSettings>" //$NON-NLS-1$
            + "<MergeSettings><Node Key=\"$$Root$$\" MergeRule=\"GetFromOther\"/></MergeSettings>" //$NON-NLS-1$
            + "</Settings>"; //$NON-NLS-1$

    /**
     * The pin that would have caught all four findings, and the one a fifth shape has to get past:
     * ten rules in nine different hiding places, and the report has to account for every one.
     *
     * @throws Exception when the fixture does not parse
     */
    @Test
    public void testEveryRuleInEveryHidingPlaceIsReportedExactlyOnce() throws Exception
    {
        assertEveryRuleIsReportedOnce("a rule in every hiding place", A_RULE_IN_EVERY_HIDING_PLACE); //$NON-NLS-1$
    }

    /**
     * Its numbers spelled out, so a failure says WHICH half moved. One rule of the ten sits at an
     * address; the other nine are reachable by nothing.
     *
     * @throws Exception when the fixture does not parse
     */
    @Test
    public void testTheHidingPlacesSplitNineToOne() throws Exception
    {
        MergeRulesDocument document = MergeRulesCodec.parse(A_RULE_IN_EVERY_HIDING_PLACE);

        assertEquals("the file spells ten rules", 10, document.ruleCount()); //$NON-NLS-1$
        assertEquals("exactly one of them sits at an address", //$NON-NLS-1$
            List.of(List.of("$$Root$$", "commonModules")), //$NON-NLS-1$ //$NON-NLS-2$
            document.decisions().stream().map(Decision::path).toList());
        assertEquals("so nine are reachable by nothing", 9, document.unreachableRuleCount()); //$NON-NLS-1$
    }

    /**
     * The document from the review finding, verbatim: an EMPTY first container followed by a
     * second that carries a rule on its own element and another on a {@code Properties} child.
     * <p>
     * Both were omitted. The scan of unread containers looked at their direct {@code Node}
     * children only, so a rule on the container element itself and a rule under a tag it did not
     * recognise fell through it; {@code decisions()} could not see them either, the first
     * container being the one it reads and that one being empty. The three counters then totalled
     * ONE - a single preserved block - for a file carrying two rules, and the read report said the
     * file records no merge rule.
     *
     * @throws Exception when the fixture does not parse
     */
    @Test
    public void testTheReportedDocumentCountsBothOfItsRules() throws Exception
    {
        String xml = "<Settings Format_version=\"2.0\"><MergeSettings/>" //$NON-NLS-1$
            + "<MergeSettings MergeRule=\"GetFromOther\">" //$NON-NLS-1$
            + "<Properties MergeRule=\"DoNotMerge\"/></MergeSettings></Settings>"; //$NON-NLS-1$

        MergeRulesDocument document = MergeRulesCodec.parse(xml);

        assertEquals("the codec accepts this document, so the counters have to describe it", 2, //$NON-NLS-1$
            document.ruleCount());
        assertEquals("neither rule sits at an address", 0, document.decisions().size()); //$NON-NLS-1$
        assertEquals("so both are unreachable, and neither is left out", 2, //$NON-NLS-1$
            document.unreachableRuleCount());
    }

    /**
     * A rule on a non-{@code Node} child INSIDE the container the document reads - the same hole
     * as the finding, one level in, and the proof that it was never only about unread containers.
     *
     * @throws Exception when the fixture does not parse
     */
    @Test
    public void testARuleOnANonNodeChildOfTheReadTreeIsCounted() throws Exception
    {
        assertEveryRuleIsReportedOnce("a rule on a Properties map inside the read tree", //$NON-NLS-1$
            "<Settings Format_version=\"2.0\"><MergeSettings><Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Properties MergeRule=\"DoNotMerge\"/></Node></MergeSettings></Settings>"); //$NON-NLS-1$
    }

    /**
     * A rule on the container element the document READS. No walk of node children can see it: it
     * is not a child, it is the thing being walked.
     *
     * @throws Exception when the fixture does not parse
     */
    @Test
    public void testARuleOnTheReadContainerElementIsCounted() throws Exception
    {
        assertEveryRuleIsReportedOnce("a rule on the MergeSettings element itself", //$NON-NLS-1$
            "<Settings Format_version=\"2.0\"><MergeSettings MergeRule=\"GetFromOther\">" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\"/></MergeSettings></Settings>"); //$NON-NLS-1$
    }

    /**
     * A rule on the {@code Settings} root itself - above every container, so above every walk that
     * started at one.
     *
     * @throws Exception when the fixture does not parse
     */
    @Test
    public void testARuleOnTheSettingsRootIsCounted() throws Exception
    {
        assertEveryRuleIsReportedOnce("a rule on the Settings root", //$NON-NLS-1$
            "<Settings Format_version=\"2.0\" MergeRule=\"GetFromOther\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\"/></MergeSettings></Settings>"); //$NON-NLS-1$
    }

    /**
     * A rule inside a preserved block beside the tree. The block is counted as ONE preserved
     * section and the rule as ONE unreachable rule - two facts in two units about one element, and
     * the preserved-section count cannot stand in for the rule count because it does not measure
     * rules.
     *
     * @throws Exception when the fixture does not parse
     */
    @Test
    public void testARuleInsideAPreservedBlockIsCountedAsARuleAndTheBlockAsABlock() throws Exception
    {
        String xml = "<Settings Format_version=\"2.0\">" //$NON-NLS-1$
            + "<Correspondences><Correspondence MergeRule=\"DoNotMerge\"/></Correspondences>" //$NON-NLS-1$
            + "<MergeSettings><Node Key=\"$$Root$$\"/></MergeSettings></Settings>"; //$NON-NLS-1$

        assertEveryRuleIsReportedOnce("a rule inside a Correspondences block", xml); //$NON-NLS-1$
        assertEquals("the block is still one preserved block", 1, //$NON-NLS-1$
            MergeRulesCodec.parse(xml).preservedSectionCount());
    }

    /**
     * A rule in a document with NO container at all. The old counter returned zero here by an
     * early exit, which was the right answer to the wrong question: there is no node tree, but
     * there is still a rule in the file.
     *
     * @throws Exception when the fixture does not parse
     */
    @Test
    public void testARuleInADocumentWithoutAContainerIsCounted() throws Exception
    {
        assertEveryRuleIsReportedOnce("a rule in a document that has no MergeSettings at all", //$NON-NLS-1$
            "<Settings Format_version=\"2.0\"><Stray MergeRule=\"GetFromOther\"/></Settings>"); //$NON-NLS-1$
    }

    /**
     * The one place a rule can sit that no parsed file reaches, so it is built by hand: BESIDE the
     * root element, where the document model holds a prolog and an epilog. XML admits only
     * comments and processing instructions there, so this is not a file anyone can write - it is
     * the boundary of the word "anywhere" in the invariant, pinned so that the walk covers the
     * whole document object and not merely its root.
     */
    @Test
    public void testARuleBesideTheRootElementIsCounted()
    {
        Element settings = new Element(MergeRulesDocument.TAG_SETTINGS);
        settings.attribute(MergeRulesDocument.ATTR_FORMAT_VERSION,
            MergeRulesDocument.SUPPORTED_FORMAT_VERSION);
        Element before = new Element("Before"); //$NON-NLS-1$
        before.attribute(MergeRulesDocument.ATTR_MERGE_RULE, "GetFromOther"); //$NON-NLS-1$
        Element after = new Element("After"); //$NON-NLS-1$
        after.attribute(MergeRulesDocument.ATTR_MERGE_RULE, "DoNotMerge"); //$NON-NLS-1$

        MergeRulesDocument document =
            MergeRulesDocument.of(settings, List.of(before), List.of(after));

        assertEquals("the prolog and the epilog are part of the document", 2, document.ruleCount()); //$NON-NLS-1$
        assertEquals("and neither is at an address", 2, document.unreachableRuleCount()); //$NON-NLS-1$
    }

    /**
     * The controls, and they are what stop the identity from being satisfied by a counter that
     * calls everything unreachable: on this document the addressed half holds every rule and the
     * unreachable half is empty.
     *
     * @throws Exception when the fixture does not parse
     */
    @Test
    public void testTheIdentityHoldsOnAFileWhoseRulesAreAllAddressable() throws Exception
    {
        assertEveryRuleIsReportedOnce("the realistic fixture", FIXTURE); //$NON-NLS-1$
        assertEquals("all four of its rules are decisions", 4, //$NON-NLS-1$
            MergeRulesCodec.parse(FIXTURE).decisions().size());
        assertEquals("and none of them is unreachable", 0, //$NON-NLS-1$
            MergeRulesCodec.parse(FIXTURE).unreachableRuleCount());
    }

    /**
     * And on the shapes the earlier findings were about, so the identity covers the history as
     * well as the future.
     * <p>
     * <b>A regression guard, not a demonstration.</b> The enumerating counter had a branch for
     * every one of these by the time it was replaced, so this pin is green on the old behaviour
     * too - it is here to make sure the derivation did not LOSE what the enumeration had learnt.
     *
     * @throws Exception when a fixture does not parse
     */
    @Test
    public void testTheIdentityHoldsOnEveryShapeAPreviousFindingNamed() throws Exception
    {
        assertEveryRuleIsReportedOnce("a rule beside the root, twice under one key", //$NON-NLS-1$
            RULE_BESIDE_THE_ROOT);
        assertEveryRuleIsReportedOnce("a rule in a second container", RULE_IN_A_SECOND_CONTAINER); //$NON-NLS-1$
        assertEveryRuleIsReportedOnce("a rule under a keyless node", //$NON-NLS-1$
            "<Settings Format_version=\"2.0\"><MergeSettings><Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Node><Node Key=\"x\" MergeRule=\"GetFromOther\"/></Node>" //$NON-NLS-1$
                + "</Node></MergeSettings></Settings>"); //$NON-NLS-1$
        assertEveryRuleIsReportedOnce("a file with no rule at all", //$NON-NLS-1$
            "<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\"/></MergeSettings></Settings>"); //$NON-NLS-1$
    }

    /**
     * What makes the count worth printing at all - stated as a ROUND TRIP, because that is what a
     * caller who writes with {@code basedOn} does: the rewrite carries all ten rules forward, and
     * the document read back out of it accounts for all ten again.
     * <p>
     * Asserting only that the rewrite keeps the rules would have been green on the old counters
     * too: the serializer was never the broken part. Asserting the identity on BOTH sides of the
     * round trip is what the old behaviour fails.
     *
     * @throws Exception when the fixture does not parse
     */
    @Test
    public void testEveryHiddenRuleSurvivesTheRewriteAndIsStillAccountedFor() throws Exception
    {
        String rewritten =
            MergeRulesCodec.serialize(MergeRulesCodec.parse(A_RULE_IN_EVERY_HIDING_PLACE));

        assertEquals("a rewrite carries what it does not interpret, verbatim:\n" + rewritten, //$NON-NLS-1$
            rulesSpelledIn(A_RULE_IN_EVERY_HIDING_PLACE), rulesSpelledIn(rewritten));
        assertEveryRuleIsReportedOnce("the document read back out of the rewrite", rewritten); //$NON-NLS-1$
        assertEquals("and the split is the same on both sides of the round trip", 9, //$NON-NLS-1$
            MergeRulesCodec.parse(rewritten).unreachableRuleCount());
    }

    /**
     * The control that keeps the two above from becoming "judge nothing in the container": the ONE
     * address the container does expose is the root, and a second node carrying that key is the
     * very collision this refusal is about - {@code findRoot} stops at the first, so everything
     * under the second is addressed by nothing while holding rules of its own.
     */
    @Test
    public void testTwoRootNodesAreRefused()
    {
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\"><Node Key=\"a\" MergeRule=\"GetFromOther\"/></Node>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\"><Node Key=\"b\" MergeRule=\"DoNotMerge\"/></Node>" //$NON-NLS-1$
                + "</MergeSettings></Settings>"); //$NON-NLS-1$
            fail("two roots are two answers to the one address the container exposes"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the key: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains(MergeRulesDocument.ROOT_KEY));
        }
    }

    /**
     * Its own literal: the level the refusal reports has to be the level under the NEW definition
     * of what is addressable. Level 0 is the container, and the only thing that can collide there
     * is the root itself - so the refusal says so rather than leaving "directly under
     * MergeSettings" to be read as "any node you put there".
     */
    @Test
    public void testTheDuplicateRootRefusalNamesTheContainerLevel()
    {
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
                + "<Node Key=\"$$Root$$\"/><Node Key=\"$$Root$$\"/>" //$NON-NLS-1$
                + "</MergeSettings></Settings>"); //$NON-NLS-1$
            fail("two roots must be refused"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the root sits at level 0: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("level 0")); //$NON-NLS-1$
            assertTrue("and level 0 holds exactly one address: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("where the only address is the '" //$NON-NLS-1$
                    + MergeRulesDocument.ROOT_KEY + "' node itself")); //$NON-NLS-1$
        }
    }

    /**
     * The second half of the same defect: the scan judged EVERY {@code MergeSettings} element,
     * while {@link MergeRulesDocument#mergeSettings()} returns the FIRST one and never looks past
     * it. A duplicate in a second container is addressed by nothing - it is carried through the
     * round trip untouched - so refusing the file over it judges a structure this codec only
     * preserves.
     */
    @Test
    public void testADuplicateInASecondMergeSettingsElementIsNotRefused() throws Exception
    {
        MergeRulesDocument document =
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\">" //$NON-NLS-1$
                + "<MergeSettings><Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Node Key=\"commonModules\" MergeRule=\"GetFromOther\"/></Node>" //$NON-NLS-1$
                + "</MergeSettings>" //$NON-NLS-1$
                + "<MergeSettings><Node Key=\"dup\"/><Node Key=\"dup\"/></MergeSettings>" //$NON-NLS-1$
                + "</Settings>"); //$NON-NLS-1$

        assertEquals("the document reads the FIRST container, and that one is unambiguous", //$NON-NLS-1$
            "GetFromOther", //$NON-NLS-1$
            document.mergeRuleAt(List.of("commonModules")).orElse(null)); //$NON-NLS-1$
    }

    /**
     * Its control, and the one that keeps the fix from becoming "scan nothing": the container the
     * document DOES read is still judged, even when another one follows it.
     */
    @Test
    public void testADuplicateInTheFirstMergeSettingsElementIsStillRefused()
    {
        try
        {
            MergeRulesCodec.parse("<Settings Format_version=\"2.0\">" //$NON-NLS-1$
                + "<MergeSettings><Node Key=\"$$Root$$\">" //$NON-NLS-1$
                + "<Node Key=\"dup\"/><Node Key=\"dup\"/></Node></MergeSettings>" //$NON-NLS-1$
                + "<MergeSettings/></Settings>"); //$NON-NLS-1$
            fail("the container every lookup reads must still be judged"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the key: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("'dup'")); //$NON-NLS-1$
        }
    }

    /**
     * The other control: a payload section this plugin does not interpret is not the node tree.
     * An element named {@code Node} inside one is somebody else's content, and refusing a file
     * over it would be a judgement about a structure this codec carries rather than reads.
     */
    @Test
    public void testTwoIdenticalNodesInsideAPreservedSectionAreNotADuplicate() throws Exception
    {
        String xml = "<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
            + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
            + "<Properties><Node Key=\"x\"/><Node Key=\"x\"/></Properties>" //$NON-NLS-1$
            + "</Node></MergeSettings></Settings>"; //$NON-NLS-1$

        MergeRulesDocument document = MergeRulesCodec.parse(xml);

        assertTrue("the payload must survive the parse it is not judged by", //$NON-NLS-1$
            MergeRulesCodec.serialize(document).contains("<Properties>")); //$NON-NLS-1$
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

    // ============ Which entry of a zip a comparison would actually restore ============

    /**
     * A zip of merge settings is a bag of documents, one per comparison, and EDT restores the ONE
     * whose name (minus its extension) is the comparison's own id. The platform answers an archive
     * with no such entry by logging a warning and restoring nothing, so this lookup is what lets a
     * caller be told instead of being left with a comparison that quietly ignored their file.
     * <p>
     * Each form the platform's {@code removeExtension} accepts is pinned in its own test: JUnit
     * stops a method at its first failed assertion, so one method holding all of them would only
     * ever exercise the first.
     */
    @Test
    public void testLookUpEntryFindsTheEntryNamedAfterTheComparison() throws Exception
    {
        Path zip = workDir.resolve("saved.zip"); //$NON-NLS-1$
        writeZip(zip, List.of("A_B_C.xml")); //$NON-NLS-1$

        assertTrue("the entry the comparison is named after is the one EDT restores", //$NON-NLS-1$
            MergeRulesCodec.lookUpEntry(zip, "A_B_C").found()); //$NON-NLS-1$
    }

    @Test
    public void testLookUpEntryFindsAnEntryInsideADirectory() throws Exception
    {
        // The platform takes the part after the last separator, so an archive that nests its
        // entries still addresses the comparison. Refusing this one would be a FALSE refusal.
        Path zip = workDir.resolve("nested.zip"); //$NON-NLS-1$
        writeZip(zip, List.of("settings/A_B_C.xml")); //$NON-NLS-1$

        assertTrue("removeExtension drops the directory, so this entry matches", //$NON-NLS-1$
            MergeRulesCodec.lookUpEntry(zip, "A_B_C").found()); //$NON-NLS-1$
    }

    @Test
    public void testLookUpEntryFindsAnEntryThatCarriesNoExtension() throws Exception
    {
        Path zip = workDir.resolve("bare.zip"); //$NON-NLS-1$
        writeZip(zip, List.of("A_B_C")); //$NON-NLS-1$

        assertTrue("a name with no dot is returned unchanged and still matches", //$NON-NLS-1$
            MergeRulesCodec.lookUpEntry(zip, "A_B_C").found()); //$NON-NLS-1$
    }

    @Test
    public void testLookUpEntryDropsOnlyTheLastExtension() throws Exception
    {
        // Only the LAST dot goes, so 'A_B_C.old.xml' reduces to 'A_B_C.old' - a renamed copy is
        // NOT the entry EDT looks for, and saying it was would be the silence this replaces.
        Path zip = workDir.resolve("renamed.zip"); //$NON-NLS-1$
        writeZip(zip, List.of("A_B_C.old.xml")); //$NON-NLS-1$

        assertFalse("a second extension is part of the name the platform compares", //$NON-NLS-1$
            MergeRulesCodec.lookUpEntry(zip, "A_B_C").found()); //$NON-NLS-1$
    }

    @Test
    public void testLookUpEntryMatchesCaseSensitively() throws Exception
    {
        // The platform compares with String.equals. A case-insensitive lookup here would report
        // "found" for an entry EDT then fails to find, which is worse than the defect.
        Path zip = workDir.resolve("cased.zip"); //$NON-NLS-1$
        writeZip(zip, List.of("a_b_c.xml")); //$NON-NLS-1$

        assertFalse("equals is case-sensitive and this lookup must be too", //$NON-NLS-1$
            MergeRulesCodec.lookUpEntry(zip, "A_B_C").found()); //$NON-NLS-1$
    }

    @Test
    public void testLookUpEntryNamesWhatTheArchiveHoldsInstead() throws Exception
    {
        Path zip = workDir.resolve("foreign.zip"); //$NON-NLS-1$
        writeZip(zip, List.of("X_Y_Z.xml", "Q_W_E.xml")); //$NON-NLS-1$ //$NON-NLS-2$

        MergeRulesCodec.ZipEntryLookup lookup = MergeRulesCodec.lookUpEntry(zip, "A_B_C"); //$NON-NLS-1$

        assertFalse("no entry is named after this comparison", lookup.found()); //$NON-NLS-1$
        assertEquals("naming what IS there is what separates 'somebody else's comparison' from " //$NON-NLS-1$
            + "'not a merge-settings archive at all'", "X_Y_Z.xml, Q_W_E.xml", //$NON-NLS-1$ //$NON-NLS-2$
            lookup.describeContents());
    }

    @Test
    public void testLookUpEntrySaysAnEmptyArchiveIsEmpty() throws Exception
    {
        Path zip = workDir.resolve("empty.zip"); //$NON-NLS-1$
        writeZip(zip, List.of());

        MergeRulesCodec.ZipEntryLookup lookup = MergeRulesCodec.lookUpEntry(zip, "A_B_C"); //$NON-NLS-1$

        assertFalse("an empty archive holds nothing for anybody", lookup.found()); //$NON-NLS-1$
        assertEquals("it is empty", lookup.describeContents()); //$NON-NLS-1$
    }

    @Test
    public void testLookUpEntryCountsTheNamesItDoesNotPrint() throws Exception
    {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 25; i++)
        {
            names.add("P" + i + "_Q_R.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Path zip = workDir.resolve("swarm-lookup.zip"); //$NON-NLS-1$
        writeZip(zip, names);

        MergeRulesCodec.ZipEntryLookup lookup = MergeRulesCodec.lookUpEntry(zip, "A_B_C"); //$NON-NLS-1$

        assertFalse(lookup.found());
        String described = lookup.describeContents();
        assertTrue("the first names are printed: " + described, //$NON-NLS-1$
            described.startsWith("P0_Q_R.xml, P1_Q_R.xml")); //$NON-NLS-1$
        assertTrue("the rest are counted, not printed: " + described, //$NON-NLS-1$
            described.endsWith(" and 5 more")); //$NON-NLS-1$
        assertFalse("the twenty-first name must not be printed: " + described, //$NON-NLS-1$
            described.contains("P20_Q_R.xml")); //$NON-NLS-1$
    }

    @Test
    public void testLookUpEntryWalksPastTheListingBoundToFindTheEntry() throws Exception
    {
        // The bound is on what is PRINTED, never on what is looked at: the platform walks every
        // entry, so a lookup that stopped at the twentieth would refuse an archive EDT accepts.
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 40; i++)
        {
            names.add("P" + i + "_Q_R.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        names.add("A_B_C.xml"); //$NON-NLS-1$
        Path zip = workDir.resolve("late-entry.zip"); //$NON-NLS-1$
        writeZip(zip, names);

        assertTrue("the entry sits past the listing bound and is still found", //$NON-NLS-1$
            MergeRulesCodec.lookUpEntry(zip, "A_B_C").found()); //$NON-NLS-1$
    }

    /**
     * The walk stops where the answer is settled. A match makes every entry after it irrelevant -
     * the answer is a boolean and nothing later can change it - so reading on costs a
     * {@code ZipEntry} per remaining entry while a launch waits on this call, and buys nothing.
     *
     * @throws Exception when the archive cannot be written or read
     */
    @Test
    public void testLookUpEntryStopsWalkingAtTheMatch() throws Exception
    {
        List<String> names = new ArrayList<>();
        names.add("A_B_C.xml"); //$NON-NLS-1$
        for (int i = 0; i < 40; i++)
        {
            names.add("P" + i + "_Q_R.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Path zip = workDir.resolve("match-first.zip"); //$NON-NLS-1$
        writeZip(zip, names);

        assertEquals("the match settles the answer, so nothing past it may be read", 1, //$NON-NLS-1$
            MergeRulesCodec.lookUpEntry(zip, "A_B_C").entriesWalked()); //$NON-NLS-1$
    }

    /**
     * Its control, and the one that keeps the pin above from being satisfied by a walk that always
     * stops after the first entry: with no match there is nothing to settle, the platform walks
     * every entry, and the refusal's "it holds X instead" is only true of the whole archive.
     *
     * @throws Exception when the archive cannot be written or read
     */
    @Test
    public void testLookUpEntryWalksTheWholeArchiveWhenNothingMatches() throws Exception
    {
        Path zip = workDir.resolve("no-match.zip"); //$NON-NLS-1$
        writeZip(zip, List.of("X_Y_Z.xml", "Q_W_E.xml", "R_T_Y.xml")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("an absent entry is only absent once every entry has been looked at", 3, //$NON-NLS-1$
            MergeRulesCodec.lookUpEntry(zip, "A_B_C").entriesWalked()); //$NON-NLS-1$
    }

    /**
     * The absence pin for the same stop: because the walk ends at the match, a found lookup has
     * seen the archive only as far as that entry, and handing that half out as "what the archive
     * holds" would be a listing that omits most of the file while reading as the whole of it.
     * Asking is a programming error, and it is refused rather than answered approximately.
     *
     * @throws Exception when the archive cannot be written or read
     */
    @Test
    public void testAFoundLookupRefusesToDescribeWhatTheArchiveHolds() throws Exception
    {
        Path zip = workDir.resolve("found-contents.zip"); //$NON-NLS-1$
        writeZip(zip, List.of("X_Y_Z.xml", "A_B_C.xml", "Q_W_E.xml")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        MergeRulesCodec.ZipEntryLookup lookup = MergeRulesCodec.lookUpEntry(zip, "A_B_C"); //$NON-NLS-1$

        assertTrue("the entry is in there, so this is the found answer", lookup.found()); //$NON-NLS-1$
        try
        {
            String described = lookup.describeContents();
            fail("a found lookup listed " + described //$NON-NLS-1$
                + ", which is what it walked past and not what the archive holds"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertTrue("the refusal must say where the answer belongs: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("found() is false")); //$NON-NLS-1$
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

    // ============ the bound applies to what this codec WRITES, not only to what it reads ============
    //
    // MAX_DOCUMENT_BYTES used to be a rule about sources alone. A document that arrives just under
    // it and grows - one more decision, or the canonical printing expanding a compact source -
    // serialised past it, landed on disk, and was reported as written; the next read of that file,
    // and every same-path rewrite after it, then refused this tool's OWN output as too large. So the
    // serialised bytes are measured before the target is touched, against the same number and by
    // the same count the reader uses.

    @Test
    public void testADocumentSerialisingPastTheBoundIsRefusedInsteadOfWritten() throws Exception
    {
        Path file = workDir.resolve("rules.xml"); //$NON-NLS-1$
        Files.write(file, FIXTURE.getBytes(StandardCharsets.UTF_8));

        try
        {
            MergeRulesCodec.write(file, oversizedDocument(), MergeRulesCodec.Target.MAY_BE_REPLACED);
            fail("a document this codec could not read back must not be written"); //$NON-NLS-1$
        }
        catch (IOException expected)
        {
            assertTrue("the refusal must name the bound it met: " + expected.getMessage(), //$NON-NLS-1$
                expected.getMessage().contains("past the 16 MB")); //$NON-NLS-1$
        }
    }

    /**
     * The pin that matters most: the refusal is not merely worded, the file that was already there
     * still holds what it held. A check made after the temporary had been moved would satisfy the
     * message assertion above and destroy the caller's rules.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void testTheFileAlreadyOnThePathIsUntouchedByTheRefusal() throws Exception
    {
        Path file = workDir.resolve("rules.xml"); //$NON-NLS-1$
        Files.write(file, FIXTURE.getBytes(StandardCharsets.UTF_8));

        try
        {
            MergeRulesCodec.write(file, oversizedDocument(), MergeRulesCodec.Target.MAY_BE_REPLACED);
            fail("expected the write to be refused"); //$NON-NLS-1$
        }
        catch (IOException expected)
        {
            assertTrue("the refusal must say the existing file survived: " + expected.getMessage(), //$NON-NLS-1$
                expected.getMessage().contains("left exactly as it was")); //$NON-NLS-1$
        }

        assertEquals("the decisions that were on the path must still be on it", FIXTURE, //$NON-NLS-1$
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        try (Stream<Path> list = Files.list(workDir))
        {
            assertEquals("and nothing else was created either - no temporary, no reservation", //$NON-NLS-1$
                List.of(file), list.toList());
        }
    }

    /**
     * A zip is measured by what its ENTRY expands to, which is what {@code readZip} counts - not by
     * the archive. A merge-settings document compresses by orders of magnitude, so an archive-sized
     * check would wave through exactly the files that cannot be read back.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void testAZipIsMeasuredByTheEntryItExpandsToAndNotByTheArchive() throws Exception
    {
        Path file = workDir.resolve("rules.zip"); //$NON-NLS-1$

        try
        {
            MergeRulesCodec.writeZip(file, oversizedDocument(),
                MergeRulesCodec.Target.MUST_NOT_EXIST, "Main_Other_Ancestor"); //$NON-NLS-1$
            fail("the entry runs past the bound, so the archive must not be written"); //$NON-NLS-1$
        }
        catch (IOException expected)
        {
            assertTrue("the refusal must say which of the two was measured: " //$NON-NLS-1$
                + expected.getMessage(), expected.getMessage().contains("as it EXPANDS")); //$NON-NLS-1$
            assertTrue("and that the path was left free: " + expected.getMessage(), //$NON-NLS-1$
                expected.getMessage().contains("nothing was created on the path")); //$NON-NLS-1$
        }

        assertFalse("the reservation must not survive a refused write", Files.exists(file)); //$NON-NLS-1$
        try (Stream<Path> list = Files.list(workDir))
        {
            assertEquals("nor a temporary beside it", List.of(), list.toList()); //$NON-NLS-1$
        }
    }

    /**
     * The control, and it is the boundary itself: a document that serialises to EXACTLY the bound
     * is written, and this codec reads its own output back. Without it the fix could be an
     * off-by-one that refuses a document the reader would have accepted.
     *
     * @throws Exception when the fixture cannot be written or read back
     */
    @Test
    public void testADocumentExactlyAtTheBoundIsWrittenAndReadsBack() throws Exception
    {
        Path file = workDir.resolve("at-the-bound.xml"); //$NON-NLS-1$
        MergeRulesDocument document = documentSerialisingTo(MergeRulesCodec.MAX_DOCUMENT_BYTES);

        MergeRulesCodec.write(file, document, MergeRulesCodec.Target.MAY_BE_REPLACED);

        assertEquals("the file must be exactly the bound, not one byte over", //$NON-NLS-1$
            MergeRulesCodec.MAX_DOCUMENT_BYTES, Files.size(file));
        assertEquals("and this codec must read its own output back", 5, //$NON-NLS-1$
            MergeRulesCodec.read(file).decisions().size());
    }

    /** @return a document whose serialisation runs one byte past {@link MergeRulesCodec#MAX_DOCUMENT_BYTES} */
    private static MergeRulesDocument oversizedDocument() throws Exception
    {
        return documentSerialisingTo(MergeRulesCodec.MAX_DOCUMENT_BYTES + 1);
    }

    /**
     * Builds a document that serialises to EXACTLY {@code bytes}, by padding one node key.
     * <p>
     * The padding is plain ASCII, so a character is a byte and nothing in the serializer escapes
     * it - the length is arithmetic rather than a guess, and the size assertions above can be
     * exact instead of "roughly".
     *
     * @param bytes the wanted serialised length
     * @return the document
     * @throws Exception when the fixture cannot be parsed
     */
    private static MergeRulesDocument documentSerialisingTo(int bytes) throws Exception
    {
        MergeRulesDocument probe = MergeRulesCodec.parse(FIXTURE);
        probe.setMergeRule(List.of("a"), "GetFromOther"); //$NON-NLS-1$ //$NON-NLS-2$
        int withoutTheKey = MergeRulesCodec.serialize(probe).getBytes(StandardCharsets.UTF_8).length - 1;

        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);
        document.setMergeRule(List.of("a".repeat(bytes - withoutTheKey)), "GetFromOther"); //$NON-NLS-1$ //$NON-NLS-2$
        return document;
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

    /**
     * The mode is only half of what makes a file shared: the OTHER half is the group those group
     * bits apply to. A move replaces the inode, so the replacement arrives owned by the group of
     * whoever ran the write, and a rules file the team reached through {@code rw-rw-r--} on
     * {@code developers} keeps its mode and stops being the team's - the preserved half doing
     * nothing on its own.
     * <p>
     * Skipped where it cannot be observed: on Windows there is no group, and on a POSIX account
     * that belongs to no group other than the one a new file already gets, a dropped group and a
     * carried one produce the same file.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void testReplacingAFileKeepsTheGroupItIsSharedThrough() throws Exception
    {
        assumePosix();
        Path file = workDir.resolve("group-shared.xml"); //$NON-NLS-1$
        Files.write(file, FIXTURE.getBytes(StandardCharsets.UTF_8));
        GroupPrincipal shared = groupOtherThanTheOneANewFileGets(file);
        Assume.assumeTrue("this account belongs to no group other than the one a new file gets, " //$NON-NLS-1$
            + "so a dropped group would be indistinguishable from a kept one here", shared != null); //$NON-NLS-1$

        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);
        document.setMergeRule(List.of("catalogs"), "DoNotMerge"); //$NON-NLS-1$ //$NON-NLS-2$
        MergeRulesCodec.write(file, document, MergeRulesCodec.Target.MAY_BE_REPLACED);

        assertEquals("a rules file shared through a secondary group must still be the team's " //$NON-NLS-1$
            + "after this tool rewrites it", shared.getName(), //$NON-NLS-1$
            Files.readAttributes(file, PosixFileAttributes.class).group().getName());
    }

    /**
     * The control for the case above: the bytes really were replaced. Without it, a write that
     * wrote nothing at all would keep the group and pass.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void testTheFileWhoseGroupSurvivedIsTheOneThatWasRewritten() throws Exception
    {
        assumePosix();
        Path file = workDir.resolve("group-shared.xml"); //$NON-NLS-1$
        Files.write(file, FIXTURE.getBytes(StandardCharsets.UTF_8));
        GroupPrincipal shared = groupOtherThanTheOneANewFileGets(file);
        Assume.assumeTrue("this account belongs to no second group", shared != null); //$NON-NLS-1$

        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);
        document.setMergeRule(List.of("catalogs"), "DoNotMerge"); //$NON-NLS-1$ //$NON-NLS-2$
        MergeRulesCodec.write(file, document, MergeRulesCodec.Target.MAY_BE_REPLACED);

        assertTrue("the decision must be on disk, or the group was kept by doing nothing", //$NON-NLS-1$
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .contains("DoNotMerge")); //$NON-NLS-1$
    }

    /**
     * Carrying the group may not cost the mode. They are two calls on the same replacement and the
     * group is set AFTER the mode, so an implementation that let the group failure abort the
     * inheritance - or that reordered them - would show up here.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void testTheGroupAndTheModeAreBothCarried() throws Exception
    {
        assumePosix();
        Path file = workDir.resolve("group-and-mode.xml"); //$NON-NLS-1$
        Files.write(file, FIXTURE.getBytes(StandardCharsets.UTF_8));
        GroupPrincipal shared = groupOtherThanTheOneANewFileGets(file);
        Assume.assumeTrue("this account belongs to no second group", shared != null); //$NON-NLS-1$
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--")); //$NON-NLS-1$

        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE),
            MergeRulesCodec.Target.MAY_BE_REPLACED);

        assertEquals(shared.getName(),
            Files.readAttributes(file, PosixFileAttributes.class).group().getName());
        assertEquals("rw-rw-r--", //$NON-NLS-1$
            PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    /**
     * A path with nothing on it has no group to inherit, exactly as it has no mode. Pinned so that
     * "carry the target's group" is never quietly turned into "set some group": the new file keeps
     * the one the filesystem gave it.
     *
     * @throws Exception when the write fails
     */
    @Test
    public void testAPathWithNothingOnItGetsNoInventedGroup() throws Exception
    {
        assumePosix();
        Path probe = Files.createFile(workDir.resolve("group-probe.xml")); //$NON-NLS-1$
        String fromCreateFile = Files.readAttributes(probe, PosixFileAttributes.class).group().getName();
        Path file = workDir.resolve("brand-new-group.xml"); //$NON-NLS-1$

        MergeRulesCodec.write(file, MergeRulesCodec.parse(FIXTURE),
            MergeRulesCodec.Target.MAY_BE_REPLACED);

        assertEquals("a file created out of nothing keeps the group the filesystem gave it", //$NON-NLS-1$
            fromCreateFile,
            Files.readAttributes(file, PosixFileAttributes.class).group().getName());
    }

    /**
     * Sets {@code file} to some group other than the one a newly created file in the same
     * directory would already have, so that carrying the group and dropping it produce DIFFERENT
     * results.
     * <p>
     * Every candidate is tried by actually setting it: an account may only change a file's group
     * to a group it belongs to, and Java has no way to ask which those are. A group that cannot be
     * set is simply not the one this fixture uses - it is not a failure, and it is the same
     * refusal the production path swallows.
     *
     * @param file the fixture file, which is left owned by the returned group
     * @return the group, or {@code null} when this account has no second group to use
     * @throws IOException when the file's own attributes cannot be read
     */
    private static GroupPrincipal groupOtherThanTheOneANewFileGets(Path file) throws IOException
    {
        String current = Files.readAttributes(file, PosixFileAttributes.class).group().getName();
        UserPrincipalLookupService lookup = file.getFileSystem().getUserPrincipalLookupService();
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        for (String name : groupNamesOnThisMachine())
        {
            if (name.equals(current))
            {
                continue;
            }
            GroupPrincipal candidate;
            try
            {
                candidate = lookup.lookupPrincipalByGroupName(name);
            }
            catch (IOException | RuntimeException e)
            {
                continue;
            }
            try
            {
                view.setGroup(candidate);
            }
            catch (IOException | RuntimeException e)
            {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * @return every group name this machine lists, or an empty list when it lists them somewhere
     *     this test cannot read
     */
    private static List<String> groupNamesOnThisMachine()
    {
        Path groups = Paths.get("/etc/group"); //$NON-NLS-1$
        if (!Files.isReadable(groups))
        {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try
        {
            for (String line : Files.readAllLines(groups, StandardCharsets.UTF_8))
            {
                int colon = line.indexOf(':');
                if (colon > 0)
                {
                    names.add(line.substring(0, colon));
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            return List.of();
        }
        return names;
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

    // ==== A failed write removes ITS OWN reservation, and never what replaced it ====

    /** What the other process writes onto the reserved path, so its survival can be pinned. */
    private static final String FOREIGN_DECISIONS = "the other write's decisions"; //$NON-NLS-1$

    /** The failure raised from inside the window, standing in for any step that can fail there. */
    private static final String INTERFERING_FAILURE = "the step after the reservation failed"; //$NON-NLS-1$

    /**
     * The finding: the clean-up on the failure path deleted the target PATH unconditionally.
     * <p>
     * That is sound about the moment the reservation was taken and about no moment after it.
     * Between the claim and the failure the path is an ordinary name in a directory anybody may
     * write to, so another process - another run of this very tool - can remove the empty
     * reservation and put its own rules file there. Deleting by path then deletes SOMEBODY ELSE'S
     * FILE, and the caller is told only that a write failed.
     * <p>
     * The assertion is on the CONTENT and not on the file's existence: a clean-up that deleted it
     * and a caller that put something back would both leave "a file is there".
     */
    @Test
    public void testAFailedWriteDoesNotDeleteTheFileThatReplacedItsReservation() throws Exception
    {
        Path target = workDir.resolve("rules.xml"); //$NON-NLS-1$

        try
        {
            MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE),
                MergeRulesCodec.Target.MUST_NOT_EXIST, null, replaceReservationThenFail(target));
            fail("the write must fail: the interference raised a failure inside it"); //$NON-NLS-1$
        }
        catch (IOException expected)
        {
            // The refusal is expected; what is on disk afterwards is the point.
        }

        assertEquals("the file that replaced the reservation is not this call's litter and must " //$NON-NLS-1$
            + "survive its clean-up", FOREIGN_DECISIONS, //$NON-NLS-1$
            new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    /**
     * A file left on the caller's path is a fact the caller has to be given. Told only "the write
     * failed", they would go on believing the path is in whatever state it was before - and the
     * whole reason it was left is that it may hold somebody's decisions.
     */
    @Test
    public void testAWriteThatLeftAForeignFileSaysSoAndStillReportsWhatFailed() throws Exception
    {
        Path target = workDir.resolve("rules.xml"); //$NON-NLS-1$

        try
        {
            MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE),
                MergeRulesCodec.Target.MUST_NOT_EXIST, null, replaceReservationThenFail(target));
            fail("the write must fail: the interference raised a failure inside it"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            assertTrue("the refusal must say the file was left where it is: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("LEFT THERE")); //$NON-NLS-1$
            assertTrue("and name the path it was left on: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains(target.toString()));
            // The original failure is still what the caller was asking about, so it may not be
            // replaced by the note about the leftover.
            assertTrue("and still report what actually failed: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains(INTERFERING_FAILURE));
        }
    }

    /**
     * The control, and the half that keeps the fix from being "never clean up": a reservation that
     * is still the empty file this call claimed is removed exactly as before. Leaving it behind
     * would make the next write refuse a path that holds no rules at all.
     */
    @Test
    public void testAFailedWriteStillRemovesItsOwnReservation() throws Exception
    {
        Path target = workDir.resolve("rules.xml"); //$NON-NLS-1$

        try
        {
            MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE),
                MergeRulesCodec.Target.MUST_NOT_EXIST, null, failWithoutTouchingAnything());
            fail("the write must fail: the interference raised a failure inside it"); //$NON-NLS-1$
        }
        catch (UncheckedIOException expected)
        {
            // Rethrown as itself: nothing was left behind, so there is nothing to add to it.
        }

        assertFalse("the reservation must not outlive the write that took it", //$NON-NLS-1$
            Files.exists(target));
        try (Stream<Path> list = Files.list(workDir))
        {
            assertEquals("and the failed write must leave the directory as it found it", //$NON-NLS-1$
                List.of(), list.toList());
        }
    }

    /**
     * Stands in for the other process: takes the reserved path away and puts its own file there,
     * then fails the write from inside the same window.
     * <p>
     * A seam rather than a second thread, and that is not convenience: the window between the
     * claim and a failure is microseconds wide and nothing in the codec blocks in it, so a racing
     * thread would occupy it by luck or not at all - and a test that reproduces the defect by luck
     * proves nothing on the run where it loses.
     *
     * @param target the path the write reserved
     * @return the interference
     */
    private static Runnable replaceReservationThenFail(Path target)
    {
        return () -> {
            try
            {
                // Deleted and recreated, which is what a replacement is: on a POSIX store the new
                // file has its own inode, and on NTFS - where this view answers no file key - it
                // is a file of a different SIZE, which is the half of the fallback that matters.
                Files.delete(target);
                Files.write(target, FOREIGN_DECISIONS.getBytes(StandardCharsets.UTF_8));
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
            throw new UncheckedIOException(new IOException(INTERFERING_FAILURE));
        };
    }

    // ==== A SUCCEEDING write installs over its own reservation, and never over what replaced it ====

    /**
     * The finding: the installation carried an unconditional {@code REPLACE_EXISTING} and the
     * recorded reservation identity was consulted ONLY after an exception.
     * <p>
     * So the loss the failure path was taught to avoid was still reachable along the path nobody
     * was watching - the SUCCESSFUL one. Another process removes the empty reservation, puts its
     * own rules file there, and the move replaces it; the caller is told the document was written,
     * and the decisions that were on that path are gone with nothing said about them.
     * <p>
     * The assertion is on the CONTENT of the file that got there first, not on the outcome of the
     * write: a refusal that had already replaced the bytes would be no refusal at all.
     */
    @Test
    public void testASucceedingWriteDoesNotInstallOverTheFileThatReplacedItsReservation()
        throws Exception
    {
        Path target = workDir.resolve("rules.xml"); //$NON-NLS-1$

        try
        {
            MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE),
                MergeRulesCodec.Target.MUST_NOT_EXIST, null, replaceReservationAndCarryOn(target));
            fail("a write whose reservation was replaced must refuse to install over the file " //$NON-NLS-1$
                + "that replaced it"); //$NON-NLS-1$
        }
        catch (IOException expected)
        {
            // The refusal is expected; what is on disk afterwards is the point.
        }

        assertEquals("the file that replaced the reservation is not this call's to overwrite", //$NON-NLS-1$
            FOREIGN_DECISIONS,
            new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    /**
     * The other half of the same refusal: it must not leave the scratch file behind either. A
     * temporary abandoned in the caller's own directory is litter this write is responsible for,
     * and the refusal is a failing exit like any other.
     */
    @Test
    public void testAWriteRefusedAtInstallationLeavesOnlyTheFileThatReplacedItsReservation()
        throws Exception
    {
        Path target = workDir.resolve("rules.xml"); //$NON-NLS-1$

        try
        {
            MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE),
                MergeRulesCodec.Target.MUST_NOT_EXIST, null, replaceReservationAndCarryOn(target));
            fail("the write must refuse rather than install"); //$NON-NLS-1$
        }
        catch (IOException expected)
        {
            // The refusal is expected; the directory listing below is the point.
        }

        try (Stream<Path> list = Files.list(workDir))
        {
            assertEquals("the refused write must leave the foreign file and nothing of its own", //$NON-NLS-1$
                List.of(target), list.toList());
        }
    }

    /**
     * A file left on the caller's path is a fact the caller has to be given, and the refusal that
     * comes from the INSTALLATION owes them the same sentence as the one that comes from a failure
     * - the file it declined to touch may hold somebody's decisions.
     */
    @Test
    public void testAWriteRefusedAtInstallationSaysWhatIsOnThePathAndWhy() throws Exception
    {
        Path target = workDir.resolve("rules.xml"); //$NON-NLS-1$

        try
        {
            MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE),
                MergeRulesCodec.Target.MUST_NOT_EXIST, null, replaceReservationAndCarryOn(target));
            fail("the write must refuse rather than install"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            assertTrue("the refusal must say the file was left where it is: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("LEFT THERE")); //$NON-NLS-1$
            assertTrue("and name the path it was left on: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains(target.toString()));
            // And say what stopped the installation, which is not the same fact: the sentence
            // above is about the CLEAN-UP declining to remove the file, and a caller told only
            // that would not know whether their document was written over it or not.
            assertTrue("and say the path no longer holds this write's reservation: " //$NON-NLS-1$
                + e.getMessage(),
                e.getMessage().contains("NOT the empty reservation this write claimed")); //$NON-NLS-1$
        }
    }

    /**
     * The control, and the half that keeps the fix from being "never install": a reservation still
     * holding the empty file this call claimed is replaced by the document exactly as before. A
     * check that refused here would break every MUST_NOT_EXIST write there is.
     * <p>
     * It goes through the SAME seam as the tests above, with an interference that touches nothing,
     * so a check that simply refused whenever the seam had run would still be caught.
     */
    @Test
    public void testASucceedingWriteStillInstallsOverItsOwnReservation() throws Exception
    {
        Path target = workDir.resolve("rules.xml"); //$NON-NLS-1$

        MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE),
            MergeRulesCodec.Target.MUST_NOT_EXIST, null, () -> {
                // Nothing interferes: the reservation is exactly as the write left it.
            });

        assertEquals(FIXTURE, new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    /**
     * The branch the refusal deliberately does NOT take: the reservation was REMOVED and nothing
     * put back. Nothing is on the path, so nothing can be destroyed by installing there, and
     * {@code MUST_NOT_EXIST} asked for a free path in the first place - refusing here would fail a
     * write that loses nobody anything.
     */
    @Test
    public void testAWriteWhoseReservationVanishedStillInstallsOnTheEmptyPath() throws Exception
    {
        Path target = workDir.resolve("rules.xml"); //$NON-NLS-1$

        MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE),
            MergeRulesCodec.Target.MUST_NOT_EXIST, null, removeReservationAndCarryOn(target));

        assertEquals(FIXTURE, new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    /**
     * Stands in for the other process on the SUCCESS path: takes the reserved path away and puts
     * its own file there, then lets the write carry on to its installation.
     * <p>
     * A seam rather than a second thread, for the reason {@code replaceReservationThenFail} is
     * one: the window is microseconds wide and nothing in the codec blocks in it, so a racing
     * thread would occupy it by luck or not at all.
     *
     * @param target the path the write reserved
     * @return the interference
     */
    private static Runnable replaceReservationAndCarryOn(Path target)
    {
        return () -> {
            try
            {
                // Deleted and recreated, which is what a replacement is: on a POSIX store the new
                // file has its own inode, and on NTFS - where this view answers no file key - it
                // is a file of a different SIZE, which is the half of the fallback that matters.
                Files.delete(target);
                Files.write(target, FOREIGN_DECISIONS.getBytes(StandardCharsets.UTF_8));
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        };
    }

    /**
     * @param target the path the write reserved
     * @return an interference that removes the reservation and puts nothing in its place
     */
    private static Runnable removeReservationAndCarryOn(Path target)
    {
        return () -> {
            try
            {
                Files.delete(target);
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        };
    }

    /**
     * @return an interference that only fails, leaving the reservation exactly as the write made it
     */
    private static Runnable failWithoutTouchingAnything()
    {
        return () -> {
            throw new UncheckedIOException(new IOException(INTERFERING_FAILURE));
        };
    }

    // ==== ... and the file key is not on its own proof that nothing replaced it ====

    /**
     * The instants the reservation carries. Shared by both descriptions on purpose: the timestamps
     * are held EQUAL so they cannot be what any of these tests actually turns on.
     */
    private static final FileTime CLAIMED_AT = FileTime.fromMillis(1_500_000_000_000L);

    /**
     * The two tests above reproduce the replacement through the real filesystem, and on Windows
     * that is not the defect's shape at all: this view answers no file key there, so the fallback
     * runs and the foreign file is rejected by its SIZE. The failure was Linux-only, which means a
     * green Windows run proves nothing about it - and a test that can only fail on the CI machine
     * is a test this repository cannot iterate on.
     * <p>
     * So the reuse is modelled instead of provoked: two prepared descriptions carrying THE SAME
     * key, which is what a POSIX store answers after a delete-and-create hands the just-freed inode
     * straight back to the next create. That pairing is what the defect needed, and no filesystem
     * can be ordered to produce it on demand.
     * <p>
     * Not vacuous: the keys are asserted EQUAL first. A version of this test whose two keys
     * differed would pass on the very code it exists to fail.
     */
    @Test
    public void testAForeignFileHandedTheReservationsOwnInodeIsNotTakenForIt()
    {
        BasicFileAttributes taken = theReservationAsClaimed(aFileKey());
        BasicFileAttributes present = new DescribedFile(aFileKey(),
            FOREIGN_DECISIONS.getBytes(StandardCharsets.UTF_8).length, true, CLAIMED_AT,
            CLAIMED_AT);

        assertEquals("the keys have to MATCH for this to model inode reuse at all - with " //$NON-NLS-1$
            + "different keys the case passes on the code that trusts the key", taken.fileKey(), //$NON-NLS-1$
            present.fileKey());
        assertFalse("a file carrying rules is not the empty reservation, whatever key the store " //$NON-NLS-1$
            + "answers for it - treating the key as the whole answer deletes somebody else's " //$NON-NLS-1$
            + "decisions", MergeRulesCodec.isTheSameFile(taken, present)); //$NON-NLS-1$
    }

    /**
     * The same reuse, with the replacement a DIRECTORY rather than a file: an empty one is size
     * zero on the stores that report a size for it, so the shape check has to ask what the entry
     * IS and not only how large it is.
     */
    @Test
    public void testADirectoryHandedTheReservationsOwnInodeIsNotTakenForIt()
    {
        BasicFileAttributes taken = theReservationAsClaimed(aFileKey());
        BasicFileAttributes present =
            new DescribedFile(aFileKey(), 0L, false, CLAIMED_AT, CLAIMED_AT);

        assertEquals("the keys have to MATCH for this to model inode reuse at all", //$NON-NLS-1$
            taken.fileKey(), present.fileKey());
        assertFalse("a directory is not the empty regular file this call claimed", //$NON-NLS-1$
            MergeRulesCodec.isTheSameFile(taken, present));
    }

    /**
     * The control that keeps the two above from being satisfied by "never the same file": the
     * reservation nobody touched is still recognised, so the clean-up still removes its own
     * litter. Without this, tightening the check into a constant {@code false} would pass.
     */
    @Test
    public void testTheUntouchedReservationIsStillRecognisedAsItself()
    {
        assertTrue("an empty regular file with the claimed key and the claimed timestamps IS " //$NON-NLS-1$
            + "the reservation, and leaving it behind would make the next write refuse a path " //$NON-NLS-1$
            + "that holds no rules", //$NON-NLS-1$
            MergeRulesCodec.isTheSameFile(theReservationAsClaimed(aFileKey()),
                theReservationAsClaimed(aFileKey())));
    }

    /**
     * And the key still NARROWS: same shape, same timestamps, a different inode. This is the case
     * the shape alone cannot see - the one the key was brought in for - so dropping the key
     * comparison has to be caught here rather than nowhere.
     */
    @Test
    public void testAnEmptyFileWearingADifferentInodeIsNotTheReservation()
    {
        BasicFileAttributes present =
            new DescribedFile(List.of(2049L, 999L), 0L, true, CLAIMED_AT, CLAIMED_AT);

        assertFalse("an empty file the store answers a DIFFERENT key for is a different file, " //$NON-NLS-1$
            + "even though it wears the reservation's shape and timestamps", //$NON-NLS-1$
            MergeRulesCodec.isTheSameFile(theReservationAsClaimed(aFileKey()), present));
    }

    /**
     * Where no key is answered at all - Windows, from this view - the shape and the timestamps are
     * the whole of the answer, and they still have to decide.
     */
    @Test
    public void testWithoutAnyKeyTheTimestampsStillDecide()
    {
        BasicFileAttributes taken = theReservationAsClaimed(null);
        BasicFileAttributes touchedSince =
            new DescribedFile(null, 0L, true, CLAIMED_AT, FileTime.fromMillis(1_500_000_001_000L));

        assertTrue("with no key on either side the unchanged reservation is still itself", //$NON-NLS-1$
            MergeRulesCodec.isTheSameFile(taken, theReservationAsClaimed(null)));
        assertFalse("and an empty file last modified at another instant is not", //$NON-NLS-1$
            MergeRulesCodec.isTheSameFile(taken, touchedSince));
    }

    /**
     * @return a file key shaped like the {@code (device, inode)} pair a POSIX store answers - a
     *         fresh object each call, so the comparison under test has to be an {@code equals} one
     */
    private static Object aFileKey()
    {
        return List.of(2049L, 8675309L);
    }

    /**
     * @param key the key the store answers for it, or {@code null} where it answers none
     * @return the description {@code createFile} leaves behind: an empty regular file
     */
    private static BasicFileAttributes theReservationAsClaimed(Object key)
    {
        return new DescribedFile(key, 0L, true, CLAIMED_AT, CLAIMED_AT);
    }

    /**
     * A file description built rather than read, so a pairing no local filesystem will produce on
     * demand - one key, two different files - can be handed to the identity check directly.
     */
    private static final class DescribedFile
        implements BasicFileAttributes
    {
        private final Object key;
        private final long size;
        private final boolean regularFile;
        private final FileTime created;
        private final FileTime modified;

        DescribedFile(Object key, long size, boolean regularFile, FileTime created,
            FileTime modified)
        {
            this.key = key;
            this.size = size;
            this.regularFile = regularFile;
            this.created = created;
            this.modified = modified;
        }

        @Override
        public FileTime lastModifiedTime()
        {
            return modified;
        }

        @Override
        public FileTime lastAccessTime()
        {
            return modified;
        }

        @Override
        public FileTime creationTime()
        {
            return created;
        }

        @Override
        public boolean isRegularFile()
        {
            return regularFile;
        }

        @Override
        public boolean isDirectory()
        {
            return !regularFile;
        }

        @Override
        public boolean isSymbolicLink()
        {
            return false;
        }

        @Override
        public boolean isOther()
        {
            return false;
        }

        @Override
        public long size()
        {
            return size;
        }

        @Override
        public Object fileKey()
        {
            return key;
        }
    }

    // ============ Nor may the NUMBER of nodes a document builds run without a bound ============

    /**
     * The bytes are bounded and the nesting is bounded, and neither bounds the COUNT. Every node
     * becomes an object owning a map and a list, so a document of very many very small tags passes
     * both existing bounds and still builds millions of them - hundreds of megabytes of the
     * workbench's heap for a source of a few.
     */
    @Test
    public void testADocumentPastTheNodeBudgetIsRefusedNamingTheBudget()
    {
        try
        {
            MergeRulesCodec.parse(documentOfEmptyTags(MergeRulesCodec.MAX_DOCUMENT_NODES + 1));
            fail("a document that builds more nodes than the budget must be refused"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the budget it ran out of: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("more than " + MergeRulesCodec.MAX_DOCUMENT_NODES //$NON-NLS-1$
                    + " nodes")); //$NON-NLS-1$
            // Told "too large", a caller would go looking for a file past the size bound and find
            // one a quarter of it; told "too deep", they would go looking for nesting that is not
            // there. The three refusals have to be distinguishable to be actionable.
            assertFalse("it must not read as the nesting refusal: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("levels deep")); //$NON-NLS-1$
            assertFalse("nor as the size refusal: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("MB and was not read")); //$NON-NLS-1$
        }
    }

    /**
     * The boundary: a document of exactly the budget is READ. A bound that refused at its own
     * number would be a different bound from the one documented, and the documented one is what
     * the "no real file reaches it" argument is made about.
     *
     * @throws Exception never; the document is well-formed
     */
    @Test
    public void testADocumentExactlyAtTheNodeBudgetIsStillRead() throws Exception
    {
        MergeRulesDocument document =
            MergeRulesCodec.parse(documentOfEmptyTags(MergeRulesCodec.MAX_DOCUMENT_NODES));

        assertNotNull("a document AT the budget is inside it, not past it", document); //$NON-NLS-1$
    }

    /**
     * The budget is ONE budget: a zip entry is charged for what it EXPANDS to, exactly as the byte
     * bound is, because the parse is charged for the expansion and not for the archive. Bounding
     * only the container the caller happened to choose is no bound at all.
     *
     * @throws Exception never; the archive is written by this test
     */
    @Test
    public void testAZipEntryIsBoundedByTheSameNodeBudget() throws Exception
    {
        Path zip = workDir.resolve("many-nodes.zip"); //$NON-NLS-1$
        try (OutputStream out = Files.newOutputStream(zip);
            ZipOutputStream zipOut = new ZipOutputStream(out))
        {
            zipOut.putNextEntry(new ZipEntry("Main_Other_Ancestor.xml")); //$NON-NLS-1$
            zipOut.write(documentOfEmptyTags(MergeRulesCodec.MAX_DOCUMENT_NODES + 1)
                .getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        }

        try
        {
            MergeRulesCodec.read(zip);
            fail("the entry builds more nodes than the budget and must be refused"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the entry must be charged the same budget as a plain file: " //$NON-NLS-1$
                + e.getMessage(),
                e.getMessage().contains("more than " + MergeRulesCodec.MAX_DOCUMENT_NODES //$NON-NLS-1$
                    + " nodes")); //$NON-NLS-1$
        }
    }

    /**
     * A merge-settings document that builds exactly {@code nodes} nodes: the {@code Settings} root
     * and empty tags under it, with no whitespace, so nothing but the tags is counted.
     *
     * @param nodes how many nodes the parse must build
     * @return the document text
     */
    private static String documentOfEmptyTags(int nodes)
    {
        String open = "<Settings Format_version=\"2.0\">"; //$NON-NLS-1$
        String close = "</Settings>"; //$NON-NLS-1$
        StringBuilder xml =
            new StringBuilder(open.length() + close.length() + 4 * (nodes - 1)).append(open);
        for (int i = 1; i < nodes; i++)
        {
            xml.append("<a/>"); //$NON-NLS-1$
        }
        return xml.append(close).toString();
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

    // ============ Nor may the WALK that chooses an entry run without a bound ============

    @Test
    public void testAZipWithMoreEntriesThanTheBoundIsRefusedWithoutListingThem() throws Exception
    {
        // The third member of the family. The size bound and the depth bound are both spent only
        // AFTER an entry has been chosen, and choosing one walks the whole directory keeping a
        // name per entry - so an archive of millions of tiny entries exhausts the heap in that
        // loop, before a byte is decompressed and before either existing bound is consulted.
        Path zip = workDir.resolve("swarm.zip"); //$NON-NLS-1$
        writeZip(zip, entryNames(1025, ".txt")); //$NON-NLS-1$

        try
        {
            MergeRulesCodec.read(zip);
            fail("an archive past the entry bound must be refused, not walked"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the refusal must name the bound it stopped on: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("more than 1024 entries")); //$NON-NLS-1$
            assertFalse("and must NOT quote the names - they are exactly what it refused to " //$NON-NLS-1$
                + "accumulate: " + e.getMessage(), e.getMessage().contains("entry-0000.txt")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testAZipExactlyAtTheEntryBoundIsStillRead() throws Exception
    {
        // The control that keeps the bound from being off by one: an archive AT the bound is
        // inside it, and a real single-settings archive that happens to carry siblings must not
        // start being refused.
        Path zip = workDir.resolve("at-the-bound.zip"); //$NON-NLS-1$
        List<String> names = new ArrayList<>(entryNames(1023, ".txt")); //$NON-NLS-1$
        names.add("Main_Other_Ancestor.xml"); //$NON-NLS-1$
        writeZip(zip, names);

        assertEquals(4, MergeRulesCodec.read(zip).decisions().size());
    }

    @Test
    public void testTheAmbiguousZipRefusalCountsWhatItDoesNotList() throws Exception
    {
        // The same unbounded accumulation moved into the answer: the refusal used to join EVERY
        // name, so an archive just under the walk bound replied to a one-line question with a
        // message tens of kilobytes long. What is left out is counted, not dropped in silence.
        Path zip = workDir.resolve("many-settings.zip"); //$NON-NLS-1$
        writeZip(zip, entryNames(30, ".xml")); //$NON-NLS-1$

        try
        {
            MergeRulesCodec.read(zip);
            fail("thirty candidate entries are still ambiguous"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException e)
        {
            assertTrue("the first names must still be there: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("entry-0000.xml")); //$NON-NLS-1$
            assertFalse("the twenty-first must not: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("entry-0020.xml")); //$NON-NLS-1$
            assertTrue("and the remainder must be COUNTED, not silently dropped: " //$NON-NLS-1$
                + e.getMessage(), e.getMessage().contains("and 10 more")); //$NON-NLS-1$
        }
    }

    /**
     * Names for a synthetic archive, zero-padded so they sort and read the way the refusal prints
     * them.
     *
     * @param count how many
     * @param extension the extension every name carries
     * @return the names
     */
    private static List<String> entryNames(int count, String extension)
    {
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            names.add(String.format("entry-%04d%s", Integer.valueOf(i), extension)); //$NON-NLS-1$
        }
        return names;
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
    public void testTheReaderExtensionRuleIsCaseSENSITIVE()
    {
        // The platform's own test is String.equals: EDT 2026.2 asserts
        // "zip".equals(FileUtil.getExtension(path)) and 2026.1 branches the same way, so a name
        // spelled in upper case is one NEITHER version reads. Accepting it here produced a
        // perfectly valid archive that the launch it was written for then refused - a file
        // reported as written and usable while being neither.
        assertFalse("no supported EDT reads 'RULES.XML'", //$NON-NLS-1$
            MergeRulesCodec.hasReadableExtension(Paths.get("C:", "RULES.XML"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nor 'rules.ZIP'", //$NON-NLS-1$
            MergeRulesCodec.hasReadableExtension(Paths.get("C:", "rules.ZIP"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nor a mixed spelling", //$NON-NLS-1$
            MergeRulesCodec.hasReadableExtension(Paths.get("C:", "rules.Zip"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOurOwnZipReaderStaysCaseInsensitive()
    {
        // The other half of the split, and the reason it is a split at all: deciding how to OPEN a
        // file somebody already has is not the same question as deciding what the platform will
        // accept. Reading a renamed archive costs nothing; writing one costs a launch.
        assertTrue("we open 'RULES.ZIP' as the zip it is", //$NON-NLS-1$
            MergeRulesCodec.isZip(Paths.get("C:", "RULES.ZIP"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("but the platform's reader will not take that name", //$NON-NLS-1$
            MergeRulesCodec.hasReadableExtension(Paths.get("C:", "RULES.ZIP"))); //$NON-NLS-1$ //$NON-NLS-2$
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

    // ==== writeZip: the container every supported EDT reads, addressed by project names ====
    //
    // EDT 2026.2 reads merge settings from a zip alone (2026.1 reads either), and a zip is a BAG
    // of settings addressed by entry name: the launch restores the entry whose name minus its
    // extension equals its own '<main>_<other>_<ancestor>' and SKIPS the archive otherwise, with
    // a log warning and no decisions applied. So the name is not decoration - it is the whole
    // difference between a file that works and a file that is reported as written and does
    // nothing. These pin the two halves that have to agree: what the writer names, and what the
    // lookup the launch uses will find.

    @Test
    public void testWriteZipNamesTheSingleEntryAfterTheComparisonItIsFor() throws Exception
    {
        Path target = workDir.resolve("rules.zip"); //$NON-NLS-1$

        MergeRulesCodec.writeZip(target, MergeRulesCodec.parse(FIXTURE),
            MergeRulesCodec.Target.MUST_NOT_EXIST, "MainCfg_VendorCfg_BaseCfg"); //$NON-NLS-1$

        assertEquals("one entry, named after the comparison, exactly as EDT's own serializer " //$NON-NLS-1$
            + "names it", List.of("MainCfg_VendorCfg_BaseCfg.xml"), entryNamesIn(target)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheEntryWriteZipProducesIsTheOneALaunchLooksFor() throws Exception
    {
        Path target = workDir.resolve("rules.zip"); //$NON-NLS-1$

        MergeRulesCodec.writeZip(target, MergeRulesCodec.parse(FIXTURE),
            MergeRulesCodec.Target.MUST_NOT_EXIST, "MainCfg_VendorCfg_BaseCfg"); //$NON-NLS-1$

        // The writer and the platform's own matching rule have to agree, or this codec writes
        // files its own launch check would call unaddressed.
        assertTrue("the comparison it was written for must find it", //$NON-NLS-1$
            MergeRulesCodec.lookUpEntry(target, "MainCfg_VendorCfg_BaseCfg").found()); //$NON-NLS-1$
        assertFalse("and no other comparison may", //$NON-NLS-1$
            MergeRulesCodec.lookUpEntry(target, "MainCfg_VendorCfg_NONE").found()); //$NON-NLS-1$
    }

    @Test
    public void testAZipThisCodecWroteReadsBackAsTheSameDocument() throws Exception
    {
        Path target = workDir.resolve("rules.zip"); //$NON-NLS-1$

        MergeRulesCodec.writeZip(target, MergeRulesCodec.parse(FIXTURE),
            MergeRulesCodec.Target.MUST_NOT_EXIST, "A_B_C"); //$NON-NLS-1$

        assertEquals("the archive must carry the document, not a truncated or re-laid-out one", //$NON-NLS-1$
            FIXTURE, MergeRulesCodec.serialize(MergeRulesCodec.read(target)));
    }

    @Test
    public void testWriteZipRefusesToInventAnEntryNameWhenNoneIsGiven() throws Exception
    {
        Path target = workDir.resolve("rules.zip"); //$NON-NLS-1$
        try
        {
            MergeRulesCodec.writeZip(target, MergeRulesCodec.parse(FIXTURE),
                MergeRulesCodec.Target.MUST_NOT_EXIST, null);
            fail("a zip named after nothing is skipped by EDT and reported as written"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue("the refusal must say what is missing: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("entry name")); //$NON-NLS-1$
        }
        assertFalse("nothing may be left on the path", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testWriteZipRefusesABlankEntryName() throws Exception
    {
        // The mirror of the null case, and not the same input: a blank string reaches the entry
        // constructor perfectly happily and produces an archive holding '.xml', which matches the
        // empty id and nothing else.
        Path target = workDir.resolve("rules.zip"); //$NON-NLS-1$
        try
        {
            MergeRulesCodec.writeZip(target, MergeRulesCodec.parse(FIXTURE),
                MergeRulesCodec.Target.MUST_NOT_EXIST, "   "); //$NON-NLS-1$
            fail("a blank id names no comparison either"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException e)
        {
            assertTrue("the refusal must say what is missing: " + e.getMessage(), //$NON-NLS-1$
                e.getMessage().contains("entry name")); //$NON-NLS-1$
        }
        assertFalse("nothing may be left on the path", Files.exists(target)); //$NON-NLS-1$
    }

    @Test
    public void testWriteStillProducesABareXmlFileAndNotAnArchive() throws Exception
    {
        // The control for the split: the xml form is what EDT 2026.1 reads directly, and adding a
        // container to it would break every caller that has one.
        Path target = workDir.resolve("rules.xml"); //$NON-NLS-1$

        MergeRulesCodec.write(target, MergeRulesCodec.parse(FIXTURE),
            MergeRulesCodec.Target.MUST_NOT_EXIST);

        assertEquals(FIXTURE, new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    @Test
    public void testWriteZipTakesTheSameReservationEveryOtherWriteTakes() throws Exception
    {
        // The zip path must not be a second, weaker write: MUST_NOT_EXIST is what keeps one
        // caller's decisions from being replaced by another's.
        Path target = workDir.resolve("rules.zip"); //$NON-NLS-1$
        Files.write(target, "somebody else's rules".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$

        try
        {
            MergeRulesCodec.writeZip(target, MergeRulesCodec.parse(FIXTURE),
                MergeRulesCodec.Target.MUST_NOT_EXIST, "A_B_C"); //$NON-NLS-1$
            fail("an occupied path must be refused, not replaced"); //$NON-NLS-1$
        }
        catch (FileAlreadyExistsException expected)
        {
            // The contract of Target.MUST_NOT_EXIST.
        }
        assertEquals("the file that was there must be untouched", "somebody else's rules", //$NON-NLS-1$ //$NON-NLS-2$
            new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }


    // ============ A source declaring an XML version this codec cannot write ============
    //
    // The serializer prints ONE declaration, version="1.0". Nothing checked what the source
    // declared, so a 1.1 document was read under 1.1's rules and handed back under 1.0's: a
    // character reference that is perfectly legal in 1.1 - '&#x1;' - arrived in the model as
    // U+0001 and was re-emitted as itself beneath a declaration that makes it an invalid
    // character. The write reported success and produced a file this codec's own read refuses,
    // which is the third instance of the defect this class already refuses twice.

    /** A 1.1 source carrying a character only a 1.1 declaration lets a parser accept. */
    private static final String ONE_POINT_ONE_WITH_A_RESTRICTED_CHARACTER =
        "<?xml version=\"1.1\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
            + "<Settings Format_version=\"2.0\"><MergeSettings>" //$NON-NLS-1$
            + "<Node Key=\"$$Root$$\">" //$NON-NLS-1$
            + "<Node Key=\"commonModules\" MergeRule=\"GetFromOther\" Note=\"a&#x1;b\"/>" //$NON-NLS-1$
            + "</Node></MergeSettings></Settings>"; //$NON-NLS-1$

    /**
     * The same source with the declaration swapped for the only one this codec prints - and
     * nothing else changed, which is exactly what makes it a statement about the READER rather
     * than about a rewrite. See the test that uses it.
     */
    private static final String THE_SAME_SOURCE_REDECLARED_AS_ONE_POINT_ZERO =
        ONE_POINT_ONE_WITH_A_RESTRICTED_CHARACTER.replace("version=\"1.1\"", "version=\"1.0\""); //$NON-NLS-1$ //$NON-NLS-2$

    /** U+0001: legal in an XML 1.1 document through a reference, spellable in 1.0 not at all. */
    private static final char RESTRICTED_CHARACTER = 1;

    @Test
    public void testAnXmlOnePointOneSourceIsRefusedInsteadOfRewrittenAsOnePointZero()
    {
        try
        {
            MergeRulesCodec.parse(ONE_POINT_ONE_WITH_A_RESTRICTED_CHARACTER);
            fail("a source this codec cannot re-declare must be refused, not silently downgraded"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException expected)
        {
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * A characterization of the READER, and named as one. It establishes the fact the refusal
     * rests on - the same reference under a 1.0 declaration is not readable, so a 1.1 source
     * cannot simply have its declaration swapped - and it establishes nothing about what this
     * codec WRITES: the reference never reaches the serializer, because the parser has already
     * turned it into the character. What the write would really produce is the two tests below.
     */
    @Test
    public void testTheSameReferenceUnderAOnePointZeroDeclarationIsNotReadableAtAll()
    {
        try
        {
            MergeRulesCodec.parse(THE_SAME_SOURCE_REDECLARED_AS_ONE_POINT_ZERO);
            fail("the whole point of the refusal is that this document does not parse"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException expected)
        {
            assertTrue("the parser must be refusing it over the character itself: " //$NON-NLS-1$
                + expected.getMessage(), expected.getMessage().contains("invalid XML character")); //$NON-NLS-1$
        }
    }

    /**
     * What our own SERIALIZER would write for that character, refused by our own read. This is the
     * statement the refusal is for - "we do not produce what our own read rejects" - and it is
     * asserted on the string the serializer actually returns, not on a hand-written approximation
     * of it.
     * <p>
     * The document is BUILT rather than parsed, and it has to be: the guard is precisely what
     * stops a parser ever handing back a model with U+0001 in it, so a test that reached this
     * state through {@code parse} would be testing that the guard does not work.
     */
    @Test
    public void testWhatTheSerializerWritesForThatCharacterIsAFileThisCodecCannotReadBack()
    {
        String written = MergeRulesCodec.serialize(aDocumentHoldingTheRestrictedCharacter());

        try
        {
            MergeRulesCodec.parse(written);
            fail("this codec must not produce a document its own read refuses: " + written); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException expected)
        {
            assertTrue("the read must be refusing it over the character itself: " //$NON-NLS-1$
                + expected.getMessage(), expected.getMessage().contains("invalid XML character")); //$NON-NLS-1$
        }
    }

    /**
     * And why the test above is about the serializer rather than about the parser a second time:
     * what comes out is the CHARACTER, spelled as itself. The serializer escapes {@code &},
     * {@code <}, {@code >}, the quote and the three whitespace controls, and a restricted
     * character is in none of those lists - so the reference the source spelled it with is gone
     * and cannot make the output readable again.
     */
    @Test
    public void testTheSerializerWritesThatCharacterItselfAndNotTheReferenceItArrivedAs()
    {
        String written = MergeRulesCodec.serialize(aDocumentHoldingTheRestrictedCharacter());

        assertTrue("the character must be in the output as itself", //$NON-NLS-1$
            written.indexOf(RESTRICTED_CHARACTER) >= 0);
        assertFalse("a reference would have been readable again, which is not what happens", //$NON-NLS-1$
            written.contains("&#x1;")); //$NON-NLS-1$
    }

    /**
     * @return a document carrying U+0001 in an attribute value, built directly because no parse
     *         can produce one
     */
    private static MergeRulesDocument aDocumentHoldingTheRestrictedCharacter()
    {
        MergeRulesDocument document = MergeRulesDocument.empty();
        MergeRulesDocument.Element node =
            new MergeRulesDocument.Element(MergeRulesDocument.TAG_NODE);
        node.attribute(MergeRulesDocument.ATTR_KEY, "commonModules"); //$NON-NLS-1$
        node.attribute(MergeRulesDocument.ATTR_MERGE_RULE, "GetFromOther"); //$NON-NLS-1$
        node.attribute("Note", "a" + RESTRICTED_CHARACTER + "b"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        document.root().children().add(node);
        return document;
    }

    // One literal per @Test: JUnit stops a method at its first failed assertion, so pins bundled
    // into one method would leave every literal after the first unloaded.

    @Test
    public void testTheXmlVersionRefusalNamesTheVersionItFound()
    {
        assertTrue(refusalFor(ONE_POINT_ONE_WITH_A_RESTRICTED_CHARACTER).contains("'1.1'")); //$NON-NLS-1$
    }

    @Test
    public void testTheXmlVersionRefusalNamesTheOneVersionThisToolWrites()
    {
        assertTrue(refusalFor(ONE_POINT_ONE_WITH_A_RESTRICTED_CHARACTER).contains("'1.0'")); //$NON-NLS-1$
    }

    @Test
    public void testTheXmlVersionRefusalSaysWhatTheRewriteWouldHaveCost()
    {
        assertTrue(refusalFor(ONE_POINT_ONE_WITH_A_RESTRICTED_CHARACTER)
            .contains("could no longer read")); //$NON-NLS-1$
    }

    @Test
    public void testTheXmlVersionRefusalNamesTheCharacterThatCannotBeCarried()
    {
        assertTrue(refusalFor(ONE_POINT_ONE_WITH_A_RESTRICTED_CHARACTER).contains("&#x1;")); //$NON-NLS-1$
    }

    @Test
    public void testTheXmlVersionRefusalSaysWhereAGoodFileComesFrom()
    {
        assertTrue(refusalFor(ONE_POINT_ONE_WITH_A_RESTRICTED_CHARACTER)
            .contains("Save merge settings")); //$NON-NLS-1$
    }

    /**
     * The decision this refusal makes, pinned as a decision: it judges the DECLARATION, not the
     * characters. A 1.1 file holding nothing but ordinary text is refused too, because the rewrite
     * would still replace a declaration it was never asked to change, and because 1.1 folds
     * U+0085 and U+2028 into line feeds where 1.0 keeps them. A repertoire scan would wave both
     * through while looking stricter, so this test is what stops one being substituted later.
     */
    @Test
    public void testAOnePointOneSourceWithOnlyOrdinaryCharactersIsRefusedToo()
    {
        try
        {
            MergeRulesCodec.parse(FIXTURE.replace("version=\"1.0\"", "version=\"1.1\"")); //$NON-NLS-1$ //$NON-NLS-2$
            fail("the declaration is what is judged, not the characters under it"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException expected)
        {
            assertTrue(expected.getMessage().contains("'1.1'")); //$NON-NLS-1$
        }
    }

    /**
     * The refusal reaches the file path too, and - the part worth pinning - the file it refused is
     * left byte for byte as it was. Asserted on the CONTENT and not on the message: a refusal that
     * had already rewritten or truncated the file would carry exactly the same text.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void testAOnePointOneFileOnDiskIsRefusedAndLeftExactlyAsItWas() throws Exception
    {
        Path file = workDir.resolve("one-point-one.xml"); //$NON-NLS-1$
        byte[] before = ONE_POINT_ONE_WITH_A_RESTRICTED_CHARACTER.getBytes(StandardCharsets.UTF_8);
        Files.write(file, before);

        try
        {
            MergeRulesCodec.read(file);
            fail("a 1.1 file on disk is refused for the same reason a 1.1 string is"); //$NON-NLS-1$
        }
        catch (MergeRulesFormatException expected)
        {
            assertTrue(expected.getMessage().contains("'1.1'")); //$NON-NLS-1$
        }
        assertArrayEquals("the refused file must be exactly the bytes that were there", //$NON-NLS-1$
            before, Files.readAllBytes(file));
    }

    /**
     * The control that stops the refusal from growing into the ordinary case: a 1.0 document reads
     * and rewrites byte for byte, exactly as it did before any of this existed.
     *
     * @throws Exception when the document cannot be parsed
     */
    @Test
    public void testAnOrdinaryOnePointZeroDocumentIsStillReadAndRewrittenUnchanged() throws Exception
    {
        assertEquals(FIXTURE, MergeRulesCodec.serialize(MergeRulesCodec.parse(FIXTURE)));
    }

    /**
     * The other control: a document with NO declaration is not a document declaring something
     * else. The reader applies 1.0's rules to it, so what was parsed is what this codec writes,
     * and the declaration it gains on the way out is the canonical layout it has always gained.
     *
     * @throws Exception when the document cannot be parsed
     */
    @Test
    public void testADocumentWithNoXmlDeclarationAtAllIsStillRead() throws Exception
    {
        String undeclared = FIXTURE.substring(FIXTURE.indexOf('\n') + 1);

        assertEquals(FIXTURE, MergeRulesCodec.serialize(MergeRulesCodec.parse(undeclared)));
    }

    /**
     * And the control on the file path, so that "read the file" is not quietly refusing every
     * source it opens.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void testAnOrdinaryOnePointZeroFileStillReadsFromDisk() throws Exception
    {
        Path file = workDir.resolve("one-point-zero.xml"); //$NON-NLS-1$
        Files.write(file, FIXTURE.getBytes(StandardCharsets.UTF_8));

        assertEquals(FIXTURE, MergeRulesCodec.serialize(MergeRulesCodec.read(file)));
    }

    /**
     * @param xml a document expected to be refused
     * @return the refusal text
     */
    private static String refusalFor(String xml)
    {
        try
        {
            MergeRulesCodec.parse(xml);
            fail("expected a refusal for: " + xml); //$NON-NLS-1$
            return null;
        }
        catch (MergeRulesFormatException expected)
        {
            return expected.getMessage();
        }
    }

    // ======== The write REPLACES the file object, and does NOT carry the target's owner ========
    //
    // Carrying the owner - and refusing the write when it could not be carried - was written,
    // measured and withdrawn. Files.getOwner and Files.setOwner are public and do exist; what is
    // missing is the GUARANTEE that an owner survives a replacing move on this platform:
    // Files.move(REPLACE_EXISTING) goes through MoveFileEx and brings the TEMPORARY's security
    // descriptor onto the path, applying an owner afterwards needs a privilege an ordinary account
    // does not hold, and the ACL view cannot represent a full descriptor - which is also why the
    // target's ACL does not survive even when its owner does.
    // "Carry it or refuse" therefore refused ORDINARY writes - a target left owned by
    // BUILTIN\Administrators after one elevated run, or by a colleague's account on a share -
    // which is a worse defect than the quiet one it replaced. What stands instead is a DECLARED
    // limitation, and these tests hold both halves of it: an ordinary write is not refused for
    // any reason to do with owners, and the limitation is stated where it was promised.

    /** The headline of the bullet the guide and its docs mirror both have to carry. */
    private static final String DECLARED_LIMITATION =
        "- **`write` REPLACES the file object, and the OWNER of the file already on the path is " //$NON-NLS-1$
            + "NOT carried.**"; //$NON-NLS-1$

    /** The guide the tool ships and hands to a caller at run time. */
    private static final String GUIDE =
        "mcp/bundles/com.ditrix.edt.mcp.server/guides/merge_rules.md"; //$NON-NLS-1$

    /** Its mirror under {@code docs/tools}, which has to say the same thing in the same words. */
    private static final String DOC_MIRROR = "docs/tools/merge_rules.md"; //$NON-NLS-1$

    /** The codec whose write the limitation is about. */
    private static final String CODEC_SOURCE = "mcp/bundles/com.ditrix.edt.mcp.server/src/com/" //$NON-NLS-1$
        + "ditrix/edt/mcp/server/utils/compare/MergeRulesCodec.java"; //$NON-NLS-1$

    /**
     * The behaviour the limitation promises is still there: a write over a file that is ALREADY
     * on the path lands, and is not refused. This is precisely the call the withdrawn refusal
     * broke - it stopped a write whose target belonged to an account other than the temporary's,
     * which on Windows is an everyday state and not an exceptional one.
     *
     * @throws Exception when the fixture cannot be written or read back
     */
    @Test
    public void testAWriteOverAnExistingFileIsNotRefusedOverItsOwner() throws Exception
    {
        Path target = workDir.resolve("owned.xml"); //$NON-NLS-1$
        Files.write(target, FIXTURE.getBytes(StandardCharsets.UTF_8));
        MergeRulesDocument document = MergeRulesCodec.parse(FIXTURE);
        document.setMergeRule(List.of("catalogs"), "DoNotMerge"); //$NON-NLS-1$ //$NON-NLS-2$

        MergeRulesCodec.write(target, document, MergeRulesCodec.Target.MAY_BE_REPLACED);

        String written = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
        assertTrue("the replacement must really have landed:\n" + written, //$NON-NLS-1$
            written.contains("Key=\"catalogs\" MergeRule=\"DoNotMerge\"")); //$NON-NLS-1$
    }

    /**
     * A NARROW mutation smoke test, and this javadoc says what it is because the version before it
     * claimed to be more.
     * <p>
     * Reintroducing the withdrawn refusal cannot be caught end-to-end here: building a target that
     * really belongs to another account needs root, so on every machine this project builds on the
     * two owners compare equal, a restored refusal would never fire, and the test above would stay
     * green while the defect was back. Scanning the codec's TEXT for the owner APIs is the cheap
     * stand-in, and it earns its place - a paste of the withdrawn code back into this file trips it
     * at once.
     *
     * <h2>What it does NOT establish, and why the list is not called exhaustive</h2>
     * A predecessor of this test said "every way Java has of reading or applying an owner is
     * named", which was not true and could not be made true by lengthening the list. Three kinds of
     * owner work walk straight past a scan of this shape, and naming them is the point:
     * <ul>
     * <li><b>Another spelling of the same call.</b> The generic attribute API reaches an owner
     * through a STRING - {@code Files.getAttribute(path, "owner:owner")} - and named none of the
     * tokens the old list held. That one specific bypass IS closed below, because it was
     * demonstrated; a token computed at run time, reached by reflection, or simply written with a
     * space before its bracket still is not.</li>
     * <li><b>Another file.</b> Only {@link #CODEC_SOURCE} is read, so the same logic moved into a
     * neutrally named helper elsewhere in the bundle is invisible here. Widening the scan to the
     * whole bundle would not fix that either - it would only move the boundary.</li>
     * <li><b>Anything about behaviour.</b> This reads characters. It cannot tell whether the write
     * still works, which is what the test above it is for.</li>
     * </ul>
     * So what stands is: the owner APIs this scan KNOWS are not called in this one file. That is
     * worth having and it is all that is claimed.
     *
     * <h2>Why a call is told from a mention</h2>
     * The codec's own javadoc now NAMES {@code Files.getOwner} and {@code Files.setOwner}, to
     * explain that what is missing is the guarantee and not the API. A bare-token ban would fire on
     * that explanation, so the invocation forms are what is banned - a call always carries its
     * bracket. That is a discriminator, not a guarantee, and it is one of the holes named above.
     *
     * @throws IOException when the source cannot be read
     */
    @Test
    public void testNoOwnerApiThisScanKnowsIsCalledInTheCodecSource() throws IOException
    {
        String source =
            new String(Files.readAllBytes(repoFile(CODEC_SOURCE)), StandardCharsets.UTF_8);

        assertTrue("the positive control: this scan must really have read the codec", //$NON-NLS-1$
            source.contains("private static void inheritPermissions(Path target, Path replacement)")); //$NON-NLS-1$
        for (String api : List.of("getOwner(", "setOwner(", "FileOwnerAttributeView", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "UserPrincipal", ".owner()", //$NON-NLS-1$ //$NON-NLS-2$
            // The bypass the review demonstrated: the generic attribute API names an owner in a
            // STRING, so it carried none of the tokens above.
            "Files.getAttribute", "Files.setAttribute", "owner:owner", "posix:owner")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertFalse("the owner work is withdrawn and the limitation declared in its place, so '" //$NON-NLS-1$
                + api + "' must not be back in MergeRulesCodec", source.contains(api)); //$NON-NLS-1$
        }
    }

    // One literal per @Test: JUnit stops a method at its first failed assertion, so pins bundled
    // into one method would leave every literal after the first unloaded.

    /**
     * @throws IOException when the guide cannot be read
     */
    @Test
    public void testTheGuideDeclaresTheLimitation() throws IOException
    {
        assertTrue(GUIDE + " must carry the declared limitation", //$NON-NLS-1$
            limitationBulletOf(GUIDE).startsWith(DECLARED_LIMITATION));
    }

    /**
     * @throws IOException when the guide cannot be read
     */
    @Test
    public void testTheLimitationSaysTheOwnerChangesAndThatNothingReportsIt() throws IOException
    {
        assertTrue(limitationBulletOf(GUIDE)
            .contains("the owner CHANGES, and nothing in the answer says so")); //$NON-NLS-1$
    }

    /**
     * A limitation the caller can do nothing about is a complaint, so it names the action.
     *
     * @throws IOException when the guide cannot be read
     */
    @Test
    public void testTheLimitationTellsTheCallerWhatToDoAboutIt() throws IOException
    {
        assertTrue(limitationBulletOf(GUIDE).contains(
            "Do not point `filePath` at a file that belongs to another account.")); //$NON-NLS-1$
    }

    /**
     * The other half of the tidy-up: the GROUP is attempted, never promised. A guide that
     * promised it would be making the same kind of claim the owner one has just lost.
     *
     * @throws IOException when the guide cannot be read
     */
    @Test
    public void testTheLimitationCallsTheGroupBestEffortRatherThanPromisingIt() throws IOException
    {
        assertTrue(limitationBulletOf(GUIDE).contains("carrying the GROUP is BEST-EFFORT")); //$NON-NLS-1$
    }

    /**
     * The bullet used to say there was "no public way" to keep an owner, which is a claim about
     * the API surface and is false - {@code Files.getOwner} and {@code Files.setOwner} are public.
     * What is actually absent is the GUARANTEE, and that is what it has to say, because a reader
     * who checks the first version finds the API and concludes the limitation is imaginary.
     *
     * @throws IOException when the guide cannot be read
     */
    @Test
    public void testTheLimitationBlamesTheMissingGuaranteeRatherThanAMissingApi() throws IOException
    {
        assertTrue(limitationBulletOf(GUIDE)
            .contains("no public Java API guarantees an owner survives a replacing move here")); //$NON-NLS-1$
    }

    /**
     * The owner is the visible half of a bigger fact: the move brings the TEMPORARY's whole
     * security descriptor onto the path, so a target with the SAME owner but an ACL somebody
     * arranged by hand loses that ACL too. Advice written about owners alone does not cover that
     * caller, so the bullet has to name it.
     *
     * @throws IOException when the guide cannot be read
     */
    @Test
    public void testTheLimitationSaysTheAclAndOtherSecurityMetadataAreNotKeptEither()
        throws IOException
    {
        assertTrue(limitationBulletOf(GUIDE).contains(
            "a Windows ACL and the rest of a file's security metadata are not preserved either")); //$NON-NLS-1$
    }

    /**
     * The mode is carried only where the store accepts applying it - the codec skips a
     * {@code setPosixFilePermissions} the filesystem answers as unsupported - so an unqualified
     * "the POSIX mode IS carried" would be the same kind of over-claim this bullet exists to
     * retire.
     *
     * @throws IOException when the guide cannot be read
     */
    @Test
    public void testTheLimitationQualifiesTheModeAsCarriedOnlyWhereItCanBeApplied()
        throws IOException
    {
        assertTrue(limitationBulletOf(GUIDE).contains(
            "The POSIX mode IS carried onto the replacement where the filesystem accepts applying it")); //$NON-NLS-1$
    }

    /**
     * The guide and its {@code docs/tools} mirror say it in the SAME words. Two copies of one
     * limitation that drift apart are one limitation and one piece of folklore, and only the
     * shipped guide is what a caller is handed at run time.
     *
     * @throws IOException when either file cannot be read
     */
    @Test
    public void testTheDocMirrorCarriesTheLimitationWordForWord() throws IOException
    {
        assertEquals("the guide and its docs/tools mirror must state it identically", //$NON-NLS-1$
            limitationBulletOf(GUIDE), limitationBulletOf(DOC_MIRROR));
    }

    /**
     * Reads the one line that declares the limitation, without its line ending. The ending is
     * deliberately not part of the comparison: the guide is checked out with the platform's,
     * while its mirror is pinned to LF by {@code .gitattributes}, and that difference is not what
     * these two files have to agree about.
     *
     * @param repoRelative the file to read, from the repository root
     * @return the bullet, ending stripped
     * @throws IOException when the file cannot be read
     */
    private static String limitationBulletOf(String repoRelative) throws IOException
    {
        String text =
            new String(Files.readAllBytes(repoFile(repoRelative)), StandardCharsets.UTF_8);
        int start = text.indexOf(DECLARED_LIMITATION);
        assertTrue(repoRelative + " must declare the limitation", start >= 0); //$NON-NLS-1$
        int end = text.indexOf('\n', start);
        return (end < 0 ? text.substring(start) : text.substring(start, end)).replace("\r", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Locates a file of this repository by walking up from the test working directory, the way
     * the other source-scanning ratchets in this suite do.
     *
     * @param repoRelative the path from the repository root
     * @return the file
     */
    private static Path repoFile(String repoRelative)
    {
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            File candidate = new File(dir, repoRelative);
            if (candidate.isFile())
            {
                return candidate.toPath();
            }
            dir = dir.getParentFile();
        }
        fail("could not locate " + repoRelative + " by walking up from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
            + System.getProperty("user.dir")); //$NON-NLS-1$
        return null; // unreachable
    }

    /**
     * Every entry name in an archive, in the order the directory lists them.
     *
     * @param zip the archive
     * @return the names
     * @throws IOException when the archive cannot be read
     */
    private static List<String> entryNamesIn(Path zip) throws IOException
    {
        List<String> names = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip.toFile()))
        {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements())
            {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }
}
