/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.core.PotentialMergeProblemDescription;
import com._1c.g5.v8.dt.compare.model.ComparisonFlags;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonNodeStatus;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.IComparedObjects;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;

/**
 * Tests for the ONE decoding of a comparison node's three-sided state, and for the agreement it
 * exists to guarantee.
 *
 * <h2>What went wrong, and what these tests pin</h2>
 * A caller reads the {@code compare_configurations} report, sees a row, and expands that row with
 * {@code get_comparison_node}. Both documents describe the SAME node, and each used to decode its
 * state itself. The node view decided from MAIN-vs-OTHER alone, so an object both sides had edited
 * the SAME way away from the common ancestor - {@code hasChangedMainOther()} false, both
 * ancestor-relative flags set - fell through to "No differences", while the report the caller had
 * just read called it "changed on both sides". The expanded node contradicted the document it was
 * reached from, about a node that differs from the ancestor on both sides.
 * <p>
 * So the tests come in three kinds:
 * <ol>
 * <li>the decoder's own answers, {@link #testBothSidesChangedTheSameWayIsNotAnAbsenceOfDifference}
 * first among them;</li>
 * <li>PARITY: the two documents are rendered over the same node object and their two cells are
 * READ BACK OUT of the Markdown and compared to each other. Nothing in those assertions names an
 * expected wording, so they pin agreement rather than a string;</li>
 * <li>a ratchet: the platform accessors a verdict is decoded from appear in exactly one production
 * file. Parity between two views is restored by deleting one of two decisions; the ratchet is what
 * stops a third from being written.</li>
 * </ol>
 */
public class ComparisonNodeStateTest
{
    /** The bundle whose sources the ratchet guards. */
    private static final String BUNDLE_SOURCE_ROOT = "mcp/bundles/com.ditrix.edt.mcp.server/src"; //$NON-NLS-1$

    /** The one file allowed to read a node's comparison verdict. */
    private static final String DECODER =
        "com/ditrix/edt/mcp/server/utils/compare/ComparisonNodeState.java"; //$NON-NLS-1$

    /** U+FEFF, the code point a UTF-8 byte-order mark decodes to. */
    private static final int BYTE_ORDER_MARK = 0xFEFF;

    /**
     * The platform accessors a three-sided verdict is decoded FROM. Reading any of them is deciding
     * the state, whatever the reader then calls it.
     */
    private static final String[] VERDICT_ACCESSORS = {
        "getComparisonFlags", //$NON-NLS-1$
        "isOneSideNode", //$NON-NLS-1$
        "getNodeSide", //$NON-NLS-1$
        "isAncestorObjectExists" //$NON-NLS-1$
    };

    private static final String FQN = "Catalog.Products"; //$NON-NLS-1$

    // ==================== The defect this decoder exists for ====================

    /**
     * The case three-way comparison exists for: both sides moved away from the ancestor, so they
     * may well agree with EACH OTHER. A decision taken from main-vs-other alone reads that as
     * equality.
     */
    @Test
    public void testBothSidesChangedTheSameWayIsNotAnAbsenceOfDifference()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.MAIN);
        flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.OTHER);

        assertFalse("the premise of the whole test: main and other do NOT differ from each other", //$NON-NLS-1$
            flags.hasChangedMainOther());
        assertEquals(ComparisonNodeState.CHANGED_ON_BOTH,
            ComparisonNodeState.decode(node(flags, ComparisonNodeStatus.FINISHED), true));
    }

    @Test
    public void testOnlyMainMovedAwayFromTheAncestor()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.MAIN);

        assertEquals(ComparisonNodeState.CHANGED_ON_MAIN,
            ComparisonNodeState.decode(node(flags, ComparisonNodeStatus.FINISHED), true));
    }

    @Test
    public void testOnlyOtherMovedAwayFromTheAncestor()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.OTHER);

        assertEquals(ComparisonNodeState.CHANGED_ON_OTHER,
            ComparisonNodeState.decode(node(flags, ComparisonNodeStatus.FINISHED), true));
    }

    /**
     * The positive control for the test above it: with an EMPTY verdict the decoder really does say
     * "identical", so the assertion there is about the ancestor being consulted and not about the
     * decoder having lost the state.
     */
    @Test
    public void testAnEmptyVerdictIsIdentical()
    {
        assertEquals(ComparisonNodeState.IDENTICAL, ComparisonNodeState
            .decode(node(new ComparisonFlags(), ComparisonNodeStatus.FINISHED), true));
    }

    /**
     * A presence difference recorded in the FLAGS rather than through {@code isOneSideNode()} is
     * still a difference. This is what {@code hasDifferences} covers and {@code hasChanged} does
     * not, and reporting it as "identical" would answer with an equality the flags contradict.
     */
    @Test
    public void testAOneSidedFlagWithoutAOneSidedNodeIsNotIdentical()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setOnOneSide(ComparisonSide.MAIN, ComparisonSide.OTHER);

        assertFalse("the premise: the platform did not record this as a plain main/other change", //$NON-NLS-1$
            flags.hasChangedMainOther());
        assertEquals(ComparisonNodeState.DIFFERS,
            ComparisonNodeState.decode(node(flags, ComparisonNodeStatus.FINISHED), true));
    }

    @Test
    public void testADoubleChangeIsTheConflictThePlatformDeclared()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasDoubleChanges();

        assertEquals(ComparisonNodeState.CONFLICT,
            ComparisonNodeState.decode(node(flags, ComparisonNodeStatus.FINISHED), true));
    }

    /** No flags object at all is the ABSENCE of a verdict, and absence is not equality. */
    @Test
    public void testNoVerdictIsNotIdentical()
    {
        assertEquals(ComparisonNodeState.NOT_REPORTED,
            ComparisonNodeState.decode(node(null, ComparisonNodeStatus.FINISHED), true));
    }

    /** ...and it survives the changedOnly filter, which is what {@code differs()} decides. */
    @Test
    public void testNoVerdictSurvivesTheChangedOnlyFilter()
    {
        assertTrue(ComparisonNodeState.NOT_REPORTED.differs());
        assertTrue(ComparisonNodeState.NOT_COMPARED.differs());
        assertFalse(ComparisonNodeState.IDENTICAL.differs());
    }

    /** The status wins over the flags: an uncompared subtree is never described as an equal one. */
    @Test
    public void testAnUnfinishedNodeIsNotDecodedFromItsFlags()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasDoubleChanges();

        assertEquals(ComparisonNodeState.NOT_COMPARED, ComparisonNodeState
            .decode(node(flags, ComparisonNodeStatus.HAS_UNFINISHED_CHILDREN),
                ComparisonNodeStatus.HAS_UNFINISHED_CHILDREN));
        assertEquals(ComparisonNodeState.NOT_COMPARED,
            ComparisonNodeState.decode(node(flags, null), (ComparisonNodeStatus)null));
    }

    // ==================== One-sided nodes: only an identified side gets a verdict ============

    /**
     * The defect: a node that says it is one-sided WITHOUT saying which side, reported as
     * "deleted on both sides".
     * <p>
     * {@code getNodeSide()} is nullable - this decoder's own contract says so - and the fallback
     * treated "no side named" as "the ancestor's side", which is a three-sided verdict about
     * presence reached without identifying a single side. It reached both documents: the top
     * report's row and the expanded node's State cell.
     */
    @Test
    public void testAOneSidedNodeThatNamesNoSideIsNotReportedAsADeletion()
    {
        ComparisonNodeState state = ComparisonNodeState.decode(oneSided(null, true), true);

        assertEquals(ComparisonNodeState.NOT_REPORTED, state);
    }

    /** The same node, pinned by what it may not SAY - the label is what reaches the caller. */
    @Test
    public void testTheUnnamedSideNeverRendersAsDeletedOnBothSides()
    {
        String label = ComparisonNodeState.decode(oneSided(null, true), true).label();

        assertFalse(label, label.contains("deleted")); //$NON-NLS-1$
    }

    /** And whether the ancestor has it does not turn an unidentified side into an answer. */
    @Test
    public void testAnUnnamedSideIsUnansweredWhateverTheAncestorHolds()
    {
        assertEquals(ComparisonNodeState.NOT_REPORTED,
            ComparisonNodeState.decode(oneSided(null, false), true));
    }

    /** ...and it survives the changedOnly filter: a node nobody judged is not an equal one. */
    @Test
    public void testAnUnnamedSideStillSurvivesTheChangedOnlyFilter()
    {
        assertTrue(ComparisonNodeState.decode(oneSided(null, true), true).differs());
    }

    /**
     * {@link ComparisonNodeState#ONLY_IN_ANCESTOR} is RESERVED for the ancestor side - the one
     * side whose presence really does mean both working sides dropped the object. This is the
     * positive control for the tests above: the verdict still exists, and is still reachable.
     */
    @Test
    public void testOnlyInAncestorIsReservedForTheAncestorSide()
    {
        assertEquals(ComparisonNodeState.ONLY_IN_ANCESTOR,
            ComparisonNodeState.decode(oneSided(ComparisonSide.COMMON_ANCESTOR, true), true));
    }

    /** The two ordinary one-sided cases, unchanged, so the new branch cannot have swallowed them. */
    @Test
    public void testAOneSidedNodeOnAKnownSideIsStillAnAdditionOrADeletion()
    {
        assertEquals(ComparisonNodeState.ADDED_ON_MAIN,
            ComparisonNodeState.decode(oneSided(ComparisonSide.MAIN, false), true));
        assertEquals(ComparisonNodeState.DELETED_ON_OTHER,
            ComparisonNodeState.decode(oneSided(ComparisonSide.MAIN, true), true));
        assertEquals(ComparisonNodeState.ADDED_ON_OTHER,
            ComparisonNodeState.decode(oneSided(ComparisonSide.OTHER, false), true));
        assertEquals(ComparisonNodeState.DELETED_ON_MAIN,
            ComparisonNodeState.decode(oneSided(ComparisonSide.OTHER, true), true));
    }

    /**
     * A node that reports itself as existing on ONE side.
     *
     * @param side the side it names, or {@code null} for the case the platform's accessor allows
     * @param ancestorExists whether the common ancestor still has the object
     * @return the node
     */
    private static TopComparisonNode oneSided(ComparisonSide side, boolean ancestorExists)
    {
        TopComparisonNode node = node(new ComparisonFlags(), ComparisonNodeStatus.FINISHED);
        when(Boolean.valueOf(node.isOneSideNode())).thenReturn(Boolean.TRUE);
        when(node.getNodeSide()).thenReturn(side);
        when(Boolean.valueOf(node.isAncestorObjectExists()))
            .thenReturn(Boolean.valueOf(ancestorExists));
        return node;
    }

    // ==================== Parity: the two documents describe one node alike ====================

    /**
     * The regression itself, stated as agreement: the report row and the expanded node are rendered
     * over the SAME node object, and the two cells are read back out of the Markdown. Before the
     * decoder was shared this failed with "changed on both sides" against "No differences".
     */
    @Test
    public void testTheExpandedNodeAgreesWithTheReportOnABothSidesChange()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.MAIN);
        flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.OTHER);

        assertParity(flags, ComparisonNodeStatus.FINISHED);
    }

    @Test
    public void testTheExpandedNodeAgreesWithTheReportOnAOneSidedChange()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.OTHER);

        assertParity(flags, ComparisonNodeStatus.FINISHED);
    }

    @Test
    public void testTheExpandedNodeAgreesWithTheReportOnAConflict()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasDoubleChanges();

        assertParity(flags, ComparisonNodeStatus.FINISHED);
    }

    @Test
    public void testTheExpandedNodeAgreesWithTheReportOnAnEqualNode()
    {
        assertParity(new ComparisonFlags(), ComparisonNodeStatus.FINISHED);
    }

    @Test
    public void testTheExpandedNodeAgreesWithTheReportOnAnUnjudgedNode()
    {
        assertParity(null, ComparisonNodeStatus.FINISHED);
    }

    @Test
    public void testTheExpandedNodeAgreesWithTheReportOnAnUnfinishedNode()
    {
        assertParity(new ComparisonFlags(), ComparisonNodeStatus.HAS_UNFINISHED_CHILDREN);
    }

    /**
     * The parity assertions above compare two cells with each other and name no wording, so they
     * would also pass if BOTH documents printed the same empty string. This one proves the cells
     * carry the state.
     */
    @Test
    public void testTheAgreedCellIsTheStateAndNotAnEmptyString()
    {
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.MAIN);
        flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.OTHER);
        TopComparisonNode node = node(flags, ComparisonNodeStatus.FINISHED);

        assertEquals(ComparisonNodeState.CHANGED_ON_BOTH.label(), reportChangeCell(node));
        assertEquals(ComparisonNodeState.CHANGED_ON_BOTH.label(), nodeStateCell(node));
    }

    // ==================== Ratchet: one decision, and only one ====================

    /**
     * Only {@link ComparisonNodeState} reads the accessors a three-sided verdict is decoded from.
     * <p>
     * Two views agreeing today is the state of the code, not a property of it: the divergence this
     * class fixes was written by a second reader of {@code getComparisonFlags()}, and nothing but
     * this rule stops a third.
     */
    @Test
    public void noSourceFileOutsideTheDecoderReadsANodesVerdict()
    {
        Map<String, String> sources = bundleSources();
        List<String> violations = new ArrayList<>();
        int seenInDecoder = 0;
        for (Map.Entry<String, String> file : sources.entrySet())
        {
            for (String accessor : VERDICT_ACCESSORS)
            {
                List<Integer> hits = linesMatching(file.getValue(), accessorCall(accessor));
                if (DECODER.equals(file.getKey()))
                {
                    seenInDecoder += hits.size();
                    continue;
                }
                for (int line : hits)
                {
                    violations.add(file.getKey() + ':' + line + " calls " + accessor + "()"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        // The positive control: the decoder DOES call them, so a scan that read nothing - or a
        // pattern that stopped matching - cannot pass this test in silence.
        assertTrue("the decoder must still read the verdict, or this rule is about nothing", //$NON-NLS-1$
            seenInDecoder > 0);
        if (!violations.isEmpty())
        {
            fail("A comparison node's three-sided state is decoded in ComparisonNodeState and " //$NON-NLS-1$
                + "nowhere else. Every view RENDERS ComparisonNodeState.label(); a second reader " //$NON-NLS-1$
                + "of these accessors is a second decision, and the last one made the node view " //$NON-NLS-1$
                + "contradict the report it was reached from:\n  " //$NON-NLS-1$
                + String.join("\n  ", violations)); //$NON-NLS-1$
        }
    }

    /** The detector must see a planted occurrence, or its silence means nothing. */
    @Test
    public void theVerdictDetectorSeesAPlantedOccurrence()
    {
        String planted = "class X\n{\n    void go()\n    {\n" //$NON-NLS-1$
            + "        ComparisonFlags flags = node.getComparisonFlags();\n    }\n}\n"; //$NON-NLS-1$

        assertEquals(List.of(Integer.valueOf(5)),
            linesMatching(planted, accessorCall("getComparisonFlags"))); //$NON-NLS-1$
    }

    /**
     * ...and it must leave the prose alone. Javadoc names these accessors all over this feature -
     * a substring ban would redden the very comments that explain the rule.
     */
    @Test
    public void theVerdictDetectorLeavesJavadocAlone()
    {
        assertTrue("a javadoc link is not a call", linesMatching( //$NON-NLS-1$
            " * decoded from {@link ComparisonNode#getComparisonFlags()} and nothing else\n", //$NON-NLS-1$
            accessorCall("getComparisonFlags")).isEmpty()); //$NON-NLS-1$
        assertTrue("nor is a bare mention inside an @code span", linesMatching( //$NON-NLS-1$
            " * answered next, from {@code isOneSideNode()} - the platform's own verdict\n", //$NON-NLS-1$
            accessorCall("isOneSideNode")).isEmpty()); //$NON-NLS-1$
        assertTrue("a longer method whose name merely begins with the accessor's is not it", //$NON-NLS-1$
            linesMatching("        return delegate.getNodeSideLabel();\n", //$NON-NLS-1$
                accessorCall("getNodeSide")).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void theScanReadTheBundleItClaimsToGuard()
    {
        Map<String, String> sources = bundleSources();
        assertTrue("scanned only " + sources.size() + " files - the locator found the wrong root", //$NON-NLS-1$ //$NON-NLS-2$
            sources.size() > 100);
        assertTrue("the decoder was not scanned", sources.containsKey(DECODER)); //$NON-NLS-1$
        assertNotNull("the report was not scanned", //$NON-NLS-1$
            sources.get("com/ditrix/edt/mcp/server/utils/compare/ComparisonTreeReport.java")); //$NON-NLS-1$
        assertNotNull("the node renderer was not scanned", //$NON-NLS-1$
            sources.get("com/ditrix/edt/mcp/server/utils/compare/ComparisonNodeRenderer.java")); //$NON-NLS-1$
    }

    // ==================== Fixtures ====================

    /**
     * Renders both documents over one node and asserts the two cells match, naming neither.
     *
     * @param flags the node's verdict, or {@code null} for a node the engine never judged
     * @param status the node's own comparison status
     */
    private static void assertParity(ComparisonFlags flags, ComparisonNodeStatus status)
    {
        TopComparisonNode node = node(flags, status);
        String fromReport = reportChangeCell(node);
        String fromNode = nodeStateCell(node);

        assertEquals("the report and the expanded node describe ONE node and must word it the " //$NON-NLS-1$
            + "same way", fromReport, fromNode); //$NON-NLS-1$
    }

    /** @return the {@code Change} cell of the node's row in the tree report */
    private static String reportChangeCell(TopComparisonNode node)
    {
        ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(100, false);
        collector.accept(node);
        String report = ComparisonTreeReport.render(
            new ComparisonTreeReport.Header("cmp-1", "TestConfiguration", "origin/main", "v1.0", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "finished", true), //$NON-NLS-1$
            ComparisonTreeReport.ScopeSnapshot.copyOf(new ComparisonScope(Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList())),
            collector);
        // The row is the one starting with the node id; Change is its fifth cell
        // (nodeId | Main | Other | Ancestor | Change | Node status).
        return cellOfRowStartingWith(report, "7", 4); //$NON-NLS-1$
    }

    /** @return the {@code State} value of the expanded node's summary table */
    private static String nodeStateCell(TopComparisonNode node)
    {
        ComparisonNodeRenderer.Request request = new ComparisonNodeRenderer.Request("cmp-1", FQN, //$NON-NLS-1$
            ComparisonSide.MAIN, node.getComparisonStatus(), 1, 100, null,
            ComparisonNodeRenderer.ContentCoverage.COMPARED);
        String document = ComparisonNodeRenderer.render(request, node, new SilentAccess());
        return cellOfRowStartingWith(document, "State", 1); //$NON-NLS-1$
    }

    /**
     * @param markdown a rendered document
     * @param firstCell the value the wanted row's first cell holds
     * @param index the zero-based cell to return
     * @return the cell's trimmed text
     */
    private static String cellOfRowStartingWith(String markdown, String firstCell, int index)
    {
        for (String line : markdown.split("\n")) //$NON-NLS-1$
        {
            if (!line.startsWith("| ")) //$NON-NLS-1$
            {
                continue;
            }
            String[] cells = line.split("\\|", -1); //$NON-NLS-1$
            if (cells.length > index + 2 && cells[1].trim().equals(firstCell))
            {
                return cells[index + 1].trim();
            }
        }
        fail("no row starting with '" + firstCell + "' in:\n" + markdown); //$NON-NLS-1$ //$NON-NLS-2$
        return null;
    }

    /**
     * One node both documents accept: a top node with all three symlinks, so neither view can take
     * its state from a missing name instead of from the verdict.
     *
     * @param flags the verdict, may be {@code null}
     * @param status the node's own status, may be {@code null}
     * @return the mocked node
     */
    private static TopComparisonNode node(ComparisonFlags flags, ComparisonNodeStatus status)
    {
        TopComparisonNode node = mock(TopComparisonNode.class);
        when(node.bmGetId()).thenReturn(Long.valueOf(7L));
        when(node.getMainSymlink()).thenReturn(FQN);
        when(node.getOtherSymlink()).thenReturn(FQN);
        when(node.getCommonAncestorSymlink()).thenReturn(FQN);
        when(node.getSymlink(ComparisonSide.MAIN)).thenReturn(FQN);
        when(node.getSymlink(ComparisonSide.OTHER)).thenReturn(FQN);
        when(node.getSymlink(ComparisonSide.COMMON_ANCESTOR)).thenReturn(FQN);
        when(node.getComparisonFlags()).thenReturn(flags);
        when(node.getComparisonStatus()).thenReturn(status);
        return node;
    }

    /** A read port that carries nothing, so the parity test measures the state and not a table. */
    private static final class SilentAccess
        implements ComparisonNodeRenderer.NodeAccess
    {
        @Override
        public IComparedObjects<?> comparedObjects(ComparisonNode node)
        {
            return null;
        }

        @Override
        public List<PotentialMergeProblemDescription> potentialProblems(long nodeId)
        {
            return Collections.emptyList();
        }
    }

    // ==================== Detection ====================

    /**
     * A CALL of one accessor: a dot, the exact name, an open parenthesis.
     * <p>
     * The leading dot is what keeps the prose out of the rule - javadoc names these accessors
     * through {@code #} and inside {@code @code} spans all over this feature, and a substring ban
     * would redden the very comments that explain the rule. The parenthesis immediately after the
     * name is what keeps a LONGER method whose name merely begins with the same word out of it.
     *
     * @param accessor the accessor's name
     * @return the pattern that finds a call of it
     */
    private static Pattern accessorCall(String accessor)
    {
        return Pattern.compile("\\." + Pattern.quote(accessor) + "[ \\t]*\\("); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<Integer> linesMatching(String source, Pattern pattern)
    {
        List<Integer> lines = new ArrayList<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find())
        {
            lines.add(Integer.valueOf(lineOf(source, matcher.start())));
        }
        return lines;
    }

    private static int lineOf(String source, int offset)
    {
        int line = 1;
        for (int at = 0; at < offset && at < source.length(); at++)
        {
            if (source.charAt(at) == '\n')
            {
                line++;
            }
        }
        return line;
    }

    // ==================== Source scan ====================

    /** @return every {@code .java} file under the bundle source root, keyed by its relative path */
    private static Map<String, String> bundleSources()
    {
        File root = locate(BUNDLE_SOURCE_ROOT);
        if (root == null)
        {
            fail("could not locate '" + BUNDLE_SOURCE_ROOT + "' by walking up from user.dir=" //$NON-NLS-1$ //$NON-NLS-2$
                + System.getProperty("user.dir")); //$NON-NLS-1$
        }
        Map<String, String> sources = new LinkedHashMap<>();
        Path base = root.toPath();
        try (Stream<Path> files = Files.walk(base))
        {
            files.filter(p -> p.getFileName().toString().endsWith(".java")) //$NON-NLS-1$
                .sorted()
                .forEach(p -> sources.put(base.relativize(p).toString().replace('\\', '/'), read(p)));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        return sources;
    }

    private static String read(Path path)
    {
        try
        {
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            // A UTF-8 BOM survives decoding as U+FEFF and would push the first line's content off
            // the start of its line.
            return text.isEmpty() || text.charAt(0) != BYTE_ORDER_MARK ? text : text.substring(1);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private static File locate(String relative)
    {
        File dir = new File(System.getProperty("user.dir")); //$NON-NLS-1$
        for (int i = 0; i < 12 && dir != null; i++)
        {
            File candidate = new File(dir, relative);
            if (candidate.exists())
            {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
